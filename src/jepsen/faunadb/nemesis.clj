(ns jepsen.faunadb.nemesis
  "nemesis for FaunaDB"
  (:require [jepsen
             [control :as c]
             [nemesis :as nemesis]
             [net :as net]
             [generator :as gen]
             [reconnect :as rc]
             [util :as util :refer [letr]]]
            [jepsen.nemesis.time :as nt]
            [jepsen.faunadb.auto :as auto]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer :all]))

;; duration between interruptions
(def nemesis-delay 5) ; seconds

;; duration of an interruption
(def nemesis-duration 5) ; seconds

;;;;;;;;;;;;;;;;;;; Common definitions ;;;;;;;;;;;;;;;;;;;;;;

(defn nemesis-no-gen
  []
  {:during gen/void
   :final gen/void})

(defn nemesis-single-gen
  []
  {:during (gen/seq (cycle [(gen/sleep nemesis-delay)
                            {:type :info, :f :start}
                            (gen/sleep nemesis-duration)
                            {:type :info, :f :stop}]))
   :final (gen/once {:type :info, :f :stop})})

(defn nemesis-double-gen
  []
  {:during (gen/seq (cycle [(gen/sleep nemesis-delay)
                            {:type :info, :f :start1}
                            (gen/sleep (/ nemesis-duration 2))
                            {:type :info, :f :start2}
                            (gen/sleep (/ nemesis-duration 2))
                            {:type :info, :f :stop1}
                            (gen/sleep (/ nemesis-duration 2))
                            {:type :info, :f :stop2}
                            (gen/sleep nemesis-delay)
                            {:type :info, :f :start2}
                            (gen/sleep (/ nemesis-duration 2))
                            {:type :info, :f :start1}
                            (gen/sleep (/ nemesis-duration 2))
                            {:type :info, :f :stop2}
                            (gen/sleep (/ nemesis-duration 2))
                            {:type :info, :f :stop1}
                            ]))
   :final (gen/seq [{:type :info, :f :stop1}
                    {:type :info, :f :stop2}])})

(defn compose
  "Takes a collection of nemesis maps, each with a :name, :during and :final
  generators, and a :client. Creates a merged nemesis map, with a generator
  that emits a mix of operations destined for each nemesis, and a composed
  nemesis that maps those back to their originals."
  [nemeses]
  (let [nemeses (remove nil? nemeses)]
    (assert (distinct? (map :names nemeses)))
    (let [; unwrap :f [name, inner] -> :f inner
          nemesis (->> nemeses
                       (map (fn [nem]
                              (let [my-name (:name nem)]
                                ; Function that selects our specific ops
                                [(fn f-select [[name f]]
                                   (when (= name my-name)
                                     (assert (not (nil? f)))
                                     f))
                                 (:nemesis nem)])))
                       (into {})
                       ((fn [x] (pprint [:nemesis-map x :nem-map]) x))
                       nemesis/compose)
          ; wrap :f inner -> :f [name, inner]
          during (->> nemeses
                      (map (fn [nemesis]
                             (let [gen  (:during nemesis)
                                   name (:name nemesis)]
                               (reify gen/Generator
                                 (op [_ test process]
                                   (when-let [op (gen/op gen test process)]
                                     (update op :f (partial vector name))))))))
                      gen/mix)
          final (->> nemeses
                     (map (fn [nemesis]
                            (let [gen  (:final nemesis)
                                  name (:name nemesis)]
                              (reify gen/Generator
                                (op [_ test process]
                                   (when-let [op (gen/op gen test process)]
                                     (update op :f (partial vector name))))))))
                     (apply gen/concat))]
      {:name   (str/join "+" (map :name nemeses))
       :clocks (reduce #(or %1 %2) (map :clocks nemeses))
       :nemesis nemesis
       :during during
       :final  final})))

;;;;;;;;;;;;;;;;;;;;;;;;;;; Nemesis definitions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; empty nemesis
(defn none
  []
  (merge (nemesis-no-gen)
         {:name "blank"
          :nemesis nemesis/noop
          :clocks false}))

; random partitions
(defn parts
  []
  {:name "parts"
   :generator (nemesis-single-gen)
   :nemesis (nemesis/partition-random-halves)
   :clocks false})

;; start/stop server
(defn startstop
  [n]
  (merge (nemesis-single-gen)
         {:name (str "startstop" (if (> n 1) n ""))
          :nemesis (nemesis/hammer-time
                    (comp (partial take n) shuffle) "java")
          :clocks false}))

(defn startkill
  [n]
  (merge (nemesis-single-gen)
         {:name (str "startkill" (if (> n 1) n ""))
          :nemesis (nemesis/node-start-stopper (comp (partial take n) shuffle)
                                              auto/kill!
                                              auto/start!)
          :clocks false}))

;; majorities ring
(defn majring
  []
  (merge (nemesis-single-gen)
         {:name "majring"
          :nemesis (nemesis/partition-majorities-ring)
          :clocks false}))

(defn slowing
  "Wraps a nemesis. Before underlying nemesis starts, slows the network by dt
  s. When underlying nemesis resolves, restores network speeds."
  [nem dt]
  (reify nemesis/Nemesis
    (setup! [this test]
      (net/fast! (:net test) test)
      (nemesis/setup! nem test)
      this)

    (invoke! [this test op]
      (case (:f op)
        :start (do (net/slow! (:net test) test {:mean (* dt 1000) :variance 1})
                   (nemesis/invoke! nem test op))

        :stop (try (nemesis/invoke! nem test op)
                   (finally
                     (net/fast! (:net test) test)))

        (nemesis/invoke! nem test op)))

    (teardown! [this test]
      (net/fast! (:net test) test)
      (nemesis/teardown! nem test))))

(defn strobe-time
  "In response to a :start op, strobes the clock between current time and delta
  ms ahead, flipping every period ms, for duration seconds. On stop, restarts
  nodes."
  [delta period duration]
  (reify nemesis/Nemesis
    (setup! [this test]
      (c/with-test-nodes test (nt/install!))
      (nt/reset-time! test)
      this)

    (invoke! [this test op]
      (assoc op :value
             (case (:f op)
               :start (c/with-test-nodes test
                        (nt/strobe-time! delta period duration))
               :stop nil)))

    (teardown! [_ test]
      (nt/reset-time! test))))

(defn strobe-skews
  []
  ; This nemesis takes time to run for start, so we don't include any sleeping.
  {:during (gen/seq (cycle [{:type :info, :f :start}
                            {:type :info, :f :stop}]))
   :final  (gen/once {:type :info, :f :stop})
   :name   "strobe-skews"
   :nemesis (strobe-time 200 10 10)
   :clocks true})

(defn bump-time
  "On randomly selected nodes, adjust the system clock by dt seconds.  Uses
  millisecond precision.  Restarts the db server if it stops."
  [dt]
  (reify nemesis/Nemesis
    (setup! [this test]
      (c/with-test-nodes test (nt/install!))
      (nt/reset-time! test)
      this)

    (invoke! [this test op]
      (assoc op :value
             (case (:f op)
               :start
               (c/with-test-nodes
                 test
                 (if (< (rand) 0.5)
                   (do (nt/bump-time! (* 1000 dt)) dt)
                   0))

               :stop
               (c/with-test-nodes
                 test
                 (nt/reset-time!)))))

    (teardown! [this test]
      (nt/reset-time! test))))

(defn skew
  "A skew nemesis"
  [name offset]
  (merge (nemesis-single-gen)
         {:name   name
          :nemesis (bump-time offset)
          :clocks true}))

; TODO: I think these were taken from the Cockroach test; do these thresholds
; actually mean anything for FaunaDB?
(defn small-skews        [] (skew "small-skews"       0.100))
(defn subcritical-skews  [] (skew "subcritical-skews" 0.200))
(defn critical-skews     [] (skew "critical-skews"    0.250))
(defn big-skews          [] (-> (skew "big-skews" 0.5)
                                (update :nemesis slowing 0.5)))
(defn huge-skews         [] (-> (skew "huge-skews" 5)
                                (update :nemesis slowing 5)))
