(ns arcadedb-jepsen.nemesis
  "Nemesis configurations for ArcadeDB Jepsen tests.
   Provides standard fault injection: network partitions, process kills,
   and process pauses."
  (:require [clojure.set :as set]
            [clojure.tools.logging :refer [info]]
            [jepsen [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [net :as net]
                    [util :as util]]))

;; -- Partition helpers --

(defn- partition-halves
  "Partitions nodes into two random halves."
  [nodes]
  (let [nodes (shuffle nodes)
        half  (quot (count nodes) 2)]
    [(set (take half nodes))
     (set (drop half nodes))]))

;; -- Combined nemesis --

(defn combined-nemesis
  "A single nemesis that handles partitions, kills, and pauses.
   Dispatches based on (:f op)."
  []
  (let [grudge (atom nil)]
    (reify nemesis/Nemesis
      (setup! [this test]
        (reset! grudge nil)
        this)

      (invoke! [this test op]
        (case (:f op)
          ;; Network partitions
          :partition-start
          (let [nodes (:nodes test)
                [a b]  (partition-halves nodes)
                g      (nemesis/complete-grudge [a b])]
            (reset! grudge g)
            (net/drop-all! test g)
            (assoc op :value (str "partitioned " a " from " b)))

          :partition-stop
          (do (net/heal! (:net test) test)
              (reset! grudge nil)
              (assoc op :value "healed"))

          ;; Process kills
          :kill
          (let [node (rand-nth (vec (:nodes test)))]
            (c/on node
              (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -9 2>/dev/null; true"))
            (assoc op :value (str "killed " node)))

          :revive
          (let [node (rand-nth (vec (:nodes test)))]
            (try
              (c/on node
                (let [db (:db test)]
                  (db/start! db test node)))
              (catch Exception e
                (info "Revive failed on" node ":" (.getMessage e))))
            (assoc op :value (str "revived " node)))

          ;; Process pauses
          :pause
          (let [node (rand-nth (vec (:nodes test)))]
            (c/on node
              (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -STOP 2>/dev/null; true"))
            (assoc op :value (str "paused " node)))

          :resume
          (let [node (rand-nth (vec (:nodes test)))]
            (c/on node
              (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -CONT 2>/dev/null; true"))
            (assoc op :value (str "resumed " node)))

          ;; Clock skew via `date -s` (best-effort, requires root on the target).
          ;; Shifts one random node's clock by a random offset in [-60s, +60s]. The value
          ;; is intentionally small relative to Raft election timeouts so the cluster stays
          ;; recoverable - the point is to exercise lease-read / time-dependent code paths,
          ;; not to stress-test NTP.
          :clock-skew
          (let [node   (rand-nth (vec (:nodes test)))
                offset (- (rand-int 120) 60)]
            (try
              (c/on node
                (c/exec :sh :-c (str "date -s @$(($(date +%s) + " offset "))")))
              (catch Exception e
                (info "Clock skew failed on" node ":" (.getMessage e))))
            (assoc op :value (str "skewed " node " by " offset "s")))

          :clock-reset
          (doseq [node (:nodes test)]
            (try
              (c/on node
                ;; Best-effort resync via ntpdate if present; otherwise no-op. A follow-up
                ;; hard-reset to the Jepsen control-node's time would require jepsen.nemesis.time.
                (c/exec :sh :-c "command -v ntpdate >/dev/null && ntpdate -u pool.ntp.org >/dev/null 2>&1; true"))
              (catch Exception e
                (info "Clock reset failed on" node ":" (.getMessage e)))))

          ;; Default: ignore unknown ops (e.g. during setup/teardown)
          (assoc op :value :ignored)))

      (teardown! [this test]
        (net/heal! (:net test) test)
        (reset! grudge nil)))))

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
                        {:type :info :f :resume}])

                 (:clock faults)
                 (into [{:type :info :f :clock-skew}
                        {:type :info :f :clock-reset}]))]
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
