(ns arcadedb-jepsen.nemesis
  "Nemesis configurations for ArcadeDB Jepsen tests.
   Provides standard fault injection: network partitions, process kills,
   process pauses, and clock skew."
  (:require [jepsen [control :as c]
                    [generator :as gen]
                    [nemesis :as nemesis]]))

(defn combined-nemesis
  "A nemesis that combines partitions, process kills, and pauses.
   Uses f-map to translate external :f values to the :start/:stop
   that sub-nemeses expect."
  []
  (nemesis/compose
    {{:partition-start :start
      :partition-stop  :stop}  (nemesis/partition-random-halves)
     {:kill  :start
      :revive :stop}           (nemesis/node-start-stopper
                                 rand-nth
                                 (fn [test node]
                                   (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -9 2>/dev/null; true"))
                                 (fn [test node]
                                   (let [db (:db test)]
                                     (.start! db test node))))
     {:pause  :start
      :resume :stop}           (nemesis/node-start-stopper
                                 rand-nth
                                 (fn [test node]
                                   (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -STOP 2>/dev/null; true"))
                                 (fn [test node]
                                   (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -CONT 2>/dev/null; true")))}))

(defn nemesis-generator
  "Returns a generator that cycles through fault injection phases."
  [opts]
  (let [faults (:faults opts #{:partition :kill :pause})
        ops    (cond-> []
                 (:partition faults)
                 (into [{:type :info :f :partition-start}
                        {:type :info :f :partition-stop}])

                 (:kill faults)
                 (into [{:type :info :f :kill}
                        {:type :info :f :revive}])

                 (:pause faults)
                 (into [{:type :info :f :pause}
                        {:type :info :f :resume}]))]
    (when (seq ops)
      (gen/cycle
        (fn [] (->> (partition 2 ops)
                    (mapcat (fn [[start stop]]
                              [(gen/sleep 5) start (gen/sleep 10) stop]))
                    (into [])))))))

(defn full-nemesis
  "Returns a nemesis + generator pair for the given options."
  [opts]
  {:nemesis   (combined-nemesis)
   :generator (nemesis-generator opts)})
