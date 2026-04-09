(ns arcadedb-jepsen.set
  "Set workload for ArcadeDB Jepsen tests.
   Adds unique elements to a set (via INSERT), reads all elements (via SELECT),
   and verifies no elements are lost during replication under faults.
   This tests replication completeness - a property not covered by the bank
   (conservation) or register (linearizability) workloads."
  (:require [arcadedb-jepsen.client :as ac]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]))

(defn create-schema!
  "Creates the SetEntry type if it doesn't exist."
  [client]
  (ac/command! client "sql" "CREATE document TYPE SetEntry IF NOT EXISTS")
  (ac/command! client "sql" "CREATE PROPERTY SetEntry.val IF NOT EXISTS INTEGER")
  (ac/command! client "sql" "CREATE INDEX IF NOT EXISTS ON SetEntry (val) UNIQUE"))

(defn add-element!
  "Inserts a unique value into the set."
  [client val]
  (ac/command! client "sql" (str "INSERT INTO SetEntry SET val = " val)))

(defn read-all
  "Reads all elements from the set. Returns a sorted set of values."
  [client]
  (let [result (ac/command! client "sql" "SELECT val FROM SetEntry ORDER BY val")]
    (->> (get-in result [:result])
         (map :val)
         (into (sorted-set)))))

(defrecord SetClient [conn node leader-conn-atom]
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
        (info "Setting up set schema on" node)
        (let [deadline (+ (System/currentTimeMillis) 60000)]
          (loop [attempt 1]
            (let [result (try
                           (let [leader (or (ac/leader-client test {:password (:root-password test) :database "jepsen"})
                                           (throw (ex-info "No leader found" {})))]
                             (create-schema! leader)
                             (Thread/sleep 3000)
                             :ok)
                           (catch Exception e
                             (if (< (System/currentTimeMillis) deadline)
                               (do (warn "Set setup attempt" attempt "failed:" (.getMessage e))
                                   (Thread/sleep (* 2000 (min attempt 5)))
                                   :retry)
                               (throw e))))]
              (when (= result :retry)
                (recur (inc attempt)))))))))

  (invoke! [this test op]
    (let [leader-conn (or @leader-conn-atom
                          (let [c (or (ac/leader-client test {:password (:root-password test) :database "jepsen"})
                                      (throw (ex-info "No leader found" {})))]
                            (reset! leader-conn-atom c)
                            c))]
      (try
        (case (:f op)
          :add
          (do (add-element! leader-conn (:value op))
              (assoc op :type :ok))

          :read
          (let [vals (read-all leader-conn)]
            (assoc op :type :ok :value vals)))

        (catch Exception e
          (let [msg (or (.getMessage e) "")]
            (ac/invalidate-leader!)
            (reset! leader-conn-atom nil)
            (cond
              ;; Duplicate key - element already added (idempotent success)
              (or (.contains msg "DuplicatedKeyException")
                  (.contains msg "duplicated key"))
              (assoc op :type :ok)

              ;; Unavailable - indeterminate
              (or (.contains msg "QuorumNotReached")
                  (.contains msg "quorum")
                  (.contains msg "timeout")
                  (.contains msg "Timeout")
                  (.contains msg "Connection refused")
                  (.contains msg "connect timed out")
                  (.contains msg "No leader found")
                  (.contains msg "ServerIsNotTheLeader"))
              (assoc op :type :info :error [:unavailable msg])

              ;; MVCC conflict - definite failure
              (.contains msg "ConcurrentModification")
              (assoc op :type :fail :error [:conflict msg])

              :else
              (assoc op :type :info :error [:unknown msg])))))))

  (teardown! [this test])

  (close! [this test]))

(defn add-op
  "Generates an add operation with a unique value."
  [test context]
  (let [counter (:set-counter test)]
    {:type :invoke :f :add :value (swap! counter inc)}))

(defn read-op
  "Generates a read operation."
  [_ _]
  {:type :invoke :f :read :value nil})

(defn set-checker
  "Checker that verifies all acknowledged adds appear in the final reads.
   An element is 'lost' if its add was :ok but it never appears in any read."
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [;; Time of the last successful read invocation
            last-read-time (->> history
                                (filter #(and (= :read (:f %)) (= :ok (:type %))))
                                last
                                :time)
            ;; All values successfully added BEFORE the last read started.
            ;; Adds that complete after the last read can't be expected to appear.
            added     (->> history
                           (filter #(and (= :add (:f %))
                                         (= :ok (:type %))
                                         (or (nil? last-read-time)
                                             (< (:time %) last-read-time))))
                           (map :value)
                           (into (sorted-set)))
            ;; All values seen across ALL reads (union)
            all-read  (->> history
                           (filter #(and (= :read (:f %)) (= :ok (:type %))))
                           (mapcat :value)
                           (into (sorted-set)))
            ;; Values that were possibly added (ok or info) - for unexpected check
            attempted (->> history
                           (filter #(and (= :add (:f %))
                                         (#{:ok :info} (:type %))))
                           (map :value)
                           (into (sorted-set)))
            ;; Lost: added successfully before last read but never seen
            lost      (clojure.set/difference added all-read)
            ;; Unexpected: read but never even attempted
            unexpected (clojure.set/difference all-read attempted)]
        {:valid?      (and (empty? lost) (empty? unexpected))
         :add-count   (count added)
         :read-count  (count all-read)
         :lost        (when (seq lost) (into (sorted-set) (take 20 lost)))
         :lost-count  (count lost)
         :unexpected  (when (seq unexpected) (into (sorted-set) (take 20 unexpected)))
         :unexpected-count (count unexpected)
         :ok-count    (count (filter #(= :ok (:type %)) history))
         :info-count  (count (filter #(= :info (:type %)) history))}))))

(defn workload
  "Returns a set test workload map."
  [opts]
  {:client    (map->SetClient {})
   :generator (gen/mix [add-op add-op add-op read-op])
   :checker   (checker/compose
                {:set  (set-checker)
                 :perf (checker/perf)})})
