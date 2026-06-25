(ns arcadedb-jepsen.ha-convergence
  "HA convergence / replica-health workload.

   Targets two recently-fixed Raft HA failure modes that the leader-routed workloads
   do not directly observe:

   - #4740 (leader phase-2 commit divergence): a leader whose commit2ndPhase fails after
     Raft committed the entry diverges (pages at version N while followers are at N+1) and
     could self-halt permanently on rejoin (WALVersionGapException -> fatal halt).
   - #4728 (stalled replica): a replica stuck STALLED at matchIndex=-1 must auto-recover.

   Both manifest as a node that is either OFFLINE (self-halted, never recovers) or DIVERGED
   (holds different data than its peers) after the faults heal. The leader-only `set`
   workload can't see this: it always reads from the leader. Here, after the nemesis heals
   and the cluster settles, we read the full set from EVERY node directly and assert:

     1. liveness   - every node is reachable (no permanent self-halt)
     2. convergence - every reachable node holds the SAME set (no divergence)
     3. completeness - every acknowledged add is present on every reachable node (no lost
                       or under-replicated acked write)

   Writes go through the leader (unique increasing integers, like the set workload)."
  (:require [arcadedb-jepsen.client :as ac]
            [arcadedb-jepsen.set :as set-wl]
            [clojure.set :as cset]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]))

(defn- read-node
  "Reads the full SetEntry set directly from a specific node. Returns a sorted-set of
   values, or :unreachable if the node can't be queried (down / partitioned / halted)."
  [test node]
  (try
    (set-wl/read-all (ac/make-client node {:password (:root-password test)
                                           :database "jepsen"}))
    (catch Exception e
      (warn "Convergence read failed on" node ":" (.getMessage e))
      :unreachable)))

(defrecord HAConvergenceClient [conn node leader-conn-atom]
  client/Client
  (open! [this test node]
    (assoc this
           :conn (ac/make-client node {:password (:root-password test)
                                       :database "jepsen"})
           :node node
           :leader-conn-atom (atom nil)))

  (setup! [this test]
    (locking (:setup-lock test)
      (when (compare-and-set! (:setup-done test) false true)
        (info "Setting up ha-convergence schema on" node)
        (let [deadline (+ (System/currentTimeMillis) 60000)]
          (loop [attempt 1]
            (let [result (try
                           (let [leader (or (ac/leader-client test {:password (:root-password test) :database "jepsen"})
                                            (throw (ex-info "No leader found" {})))]
                             (set-wl/create-schema! leader)
                             (Thread/sleep 3000)
                             :ok)
                           (catch Exception e
                             (if (< (System/currentTimeMillis) deadline)
                               (do (warn "ha-convergence setup attempt" attempt "failed:" (.getMessage e))
                                   (Thread/sleep (* 2000 (min attempt 5)))
                                   :retry)
                               (throw e))))]
              (when (= result :retry)
                (recur (inc attempt)))))))))

  (invoke! [this test op]
    (case (:f op)
      ;; Final op (single, after nemesis heals): read every node directly.
      :converge
      (assoc op :type :ok
             :value (into {} (for [n (:nodes test)] [n (read-node test n)])))

      ;; Write path: unique add through the leader (same semantics as the set workload).
      :add
      (let [leader-conn (or @leader-conn-atom
                            (let [c (or (ac/leader-client test {:password (:root-password test) :database "jepsen"})
                                        (throw (ex-info "No leader found" {})))]
                              (reset! leader-conn-atom c) c))]
        (try
          (set-wl/add-element! leader-conn (:value op))
          (assoc op :type :ok)
          (catch Exception e
            (let [msg (or (.getMessage e) "")]
              (ac/invalidate-leader!)
              (reset! leader-conn-atom nil)
              (cond
                (or (.contains msg "DuplicatedKeyException") (.contains msg "duplicated key"))
                (assoc op :type :ok)
                ;; ConcurrentModification is retryable/indeterminate (see set.clj).
                (.contains msg "ConcurrentModification")
                (assoc op :type :info :error [:conflict msg])
                :else
                (assoc op :type :info :error [:unavailable msg]))))))))

  (teardown! [this test])
  (close! [this test]))

(defn add-op
  [test _]
  {:type :invoke :f :add :value (swap! (:set-counter test) inc)})

(defn convergence-checker
  "Verifies, from the final per-node read, that all nodes are reachable (no self-halt),
   converged (identical sets), and complete (hold every acknowledged add)."
  []
  (reify checker/Checker
    (check [_ test history _]
      (let [acked   (->> history
                         (filter #(and (= :add (:f %)) (= :ok (:type %))))
                         (map :value)
                         (into (sorted-set)))
            converge (->> history
                          (filter #(and (= :converge (:f %)) (= :ok (:type %))))
                          last
                          :value)
            per-node (or converge {})
            all-nodes    (vec (keys per-node))
            unreachable  (->> per-node (filter #(= :unreachable (val %))) (map key) vec)
            reachable    (->> per-node (remove #(= :unreachable (val %))) (into {}))
            node-sets    (vals reachable)
            ;; convergence: every reachable node holds the same set
            reference    (first node-sets)
            diverged     (->> reachable
                              (remove #(= reference (val %)))
                              (map key) vec)
            converged?   (or (empty? reachable) (apply = node-sets))
            ;; completeness: acked adds missing from at least one reachable node
            missing-by-node (into {}
                                  (for [[n s] reachable
                                        :let [m (cset/difference acked s)]
                                        :when (seq m)]
                                    [n (count m)]))
            ;; lost: acked adds present on NO reachable node at all
            present-anywhere (reduce cset/union (sorted-set) node-sets)
            lost             (cset/difference acked present-anywhere)]
        {:valid?            (and (empty? unreachable) converged? (empty? lost) (empty? missing-by-node))
         :node-count        (count all-nodes)
         :reachable-count   (count reachable)
         :unreachable       unreachable
         :converged?        converged?
         :diverged-nodes    diverged
         :acked-adds        (count acked)
         :lost-count        (count lost)
         :lost              (when (seq lost) (into (sorted-set) (take 20 lost)))
         :under-replicated  missing-by-node}))))

(defn workload
  "HA convergence workload. Main phase issues unique adds through the leader under the
   nemesis; the final phase (run by core after the nemesis heals + the cluster settles)
   reads every node directly and checks liveness/convergence/completeness."
  [_opts]
  {:client          (map->HAConvergenceClient {})
   :generator       (gen/mix [add-op])
   :final-generator (gen/once {:type :invoke :f :converge})
   :checker         (checker/compose
                      {:convergence (convergence-checker)})})
