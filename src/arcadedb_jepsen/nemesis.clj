(ns arcadedb-jepsen.nemesis
  "Nemesis configurations for ArcadeDB Jepsen tests.
   Provides standard fault injection: network partitions, process kills,
   process pauses, and clock skew."
  (:require [jepsen [generator :as gen]
                    [nemesis :as nemesis]]))

(defn partition-nemesis
  "A nemesis that creates random network partitions."
  []
  (nemesis/partition-random-halves))

(defn kill-nemesis
  "A nemesis that kills and restarts ArcadeDB processes."
  []
  (nemesis/node-start-stopper
    rand-nth
    (fn [test node] ((:kill! (:db test)) (:db test) test node))
    (fn [test node] ((:start! (:db test)) (:db test) test node))))

(defn pause-nemesis
  "A nemesis that pauses and resumes ArcadeDB processes (SIGSTOP/SIGCONT)."
  []
  (nemesis/node-start-stopper
    rand-nth
    (fn [test node] ((:pause! (:db test)) (:db test) test node))
    (fn [test node] ((:resume! (:db test)) (:db test) test node))))

(defn combined-nemesis
  "A nemesis that combines partitions and process kills."
  []
  (nemesis/compose
    {#{:partition-start :partition-stop} (partition-nemesis)
     #{:kill           :revive}          (kill-nemesis)
     #{:pause          :resume}          (pause-nemesis)}))

(defn nemesis-generator
  "Returns a generator that interleaves nemesis faults with a quiet period.
   Cycles through: wait -> fault -> wait -> heal."
  [opts]
  (let [faults (:faults opts #{:partition :kill :pause})
        ops    (cond-> []
                 (:partition faults)
                 (conj {:type :info :f :partition-start}
                       {:type :info :f :partition-stop})

                 (:kill faults)
                 (conj {:type :info :f :kill}
                       {:type :info :f :revive})

                 (:pause faults)
                 (conj {:type :info :f :pause}
                       {:type :info :f :resume}))]
    (->> (cycle (mapcat (fn [[start stop]]
                          [(gen/sleep 5) start (gen/sleep 10) stop])
                        (partition 2 ops)))
         (gen/seq))))

(defn full-nemesis
  "Returns a nemesis + generator pair for the given options."
  [opts]
  {:nemesis   (combined-nemesis)
   :generator (nemesis-generator opts)})
