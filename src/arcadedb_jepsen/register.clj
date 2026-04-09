(ns arcadedb-jepsen.register
  "Linearizable register workload for ArcadeDB Jepsen tests.
   Tests single-key read/write/CAS operations for linearizability
   using the Knossos checker."
  (:require [arcadedb-jepsen.client :as ac]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]))

(defn create-schema!
  "Creates the Register type if it doesn't exist."
  [client]
  (ac/command! client "sql" "CREATE document TYPE Register IF NOT EXISTS")
  (ac/command! client "sql"
               "CREATE PROPERTY Register.key IF NOT EXISTS STRING")
  (ac/command! client "sql"
               "CREATE PROPERTY Register.val IF NOT EXISTS INTEGER")
  (ac/command! client "sql"
               "CREATE INDEX IF NOT EXISTS ON Register (key) UNIQUE"))

(defn read-register
  "Reads the value of a register key. Returns nil if not found."
  [client k]
  (let [result (ac/command! client "sql"
                            (str "SELECT val FROM Register WHERE key = '" k "'"))]
    (when-let [records (seq (get-in result [:result]))]
      (:val (first records)))))

(defn write-register!
  "Writes a value to a register key (upsert)."
  [client k v]
  (ac/command! client "sqlscript"
               (str "BEGIN ISOLATION REPEATABLE_READ;"
                    "LET $existing = SELECT FROM Register WHERE key = '" k "';"
                    "IF ($existing.size() > 0) {"
                    "  UPDATE Register SET val = " v " WHERE key = '" k "';"
                    "} ELSE {"
                    "  INSERT INTO Register SET key = '" k "', val = " v ";"
                    "}"
                    "COMMIT RETRY 10;")))

(defn cas-register!
  "Compare-and-swap on a register key. Returns true if successful."
  [client k old-val new-val]
  (let [result (ac/command! client "sqlscript"
                            (str "BEGIN ISOLATION REPEATABLE_READ;"
                                 "LET $r = SELECT FROM Register WHERE key = '" k "' AND val = " old-val ";"
                                 "IF ($r.size() > 0) {"
                                 "  UPDATE Register SET val = " new-val " WHERE key = '" k "' AND val = " old-val ";"
                                 "}"
                                 "COMMIT RETRY 3;"))
        ;; Check if the update affected a row
        updated (some-> result :result first :count)]
    (and updated (pos? updated))))

(defn- find-leader-conn
  "Discovers the current leader and returns a client connected to it, or throws."
  [test]
  (or (ac/leader-client test {:password (:root-password test) :database "jepsen"})
      (throw (ex-info "No leader found" {}))))

(defrecord RegisterClient [conn node leader-conn-atom]
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
        (info "Setting up register schema on" node)
        (let [deadline (+ (System/currentTimeMillis) 60000)]
          (loop [attempt 1]
            (let [result (try
                           (let [leader-conn (find-leader-conn test)]
                             (create-schema! leader-conn)
                             (Thread/sleep 3000)
                             :ok)
                           (catch Exception e
                             (if (< (System/currentTimeMillis) deadline)
                               (do (warn "Register setup attempt" attempt "failed:" (.getMessage e))
                                   (Thread/sleep (* 2000 (min attempt 5)))
                                   :retry)
                               (throw e))))]
              (when (= result :retry)
                (recur (inc attempt)))))))))

  (invoke! [this test op]
    (let [[k v] (:value op)
          key-str (str "r" k)
          ;; Reuse the leader connection for this client, refresh on error
          leader-conn (or @leader-conn-atom
                         (let [c (find-leader-conn test)]
                           (reset! leader-conn-atom c)
                           c))]
      (try
        (let [leader-conn leader-conn]
          (case (:f op)
            :read
            (let [val (read-register leader-conn key-str)]
              (assoc op :type :ok :value (independent/tuple k val)))

            :write
            (do (write-register! leader-conn key-str v)
                (assoc op :type :ok))

            :cas
            (let [[old-val new-val] v
                  success (cas-register! leader-conn key-str old-val new-val)]
              (assoc op :type (if success :ok :fail)))))

        (catch Exception e
          (let [msg (or (.getMessage e) "")]
            (ac/invalidate-leader!)
            (reset! leader-conn-atom nil)
            (cond
              ;; Definite failure: MVCC conflict
              (.contains msg "ConcurrentModification")
              (assoc op :type :fail :error [:conflict msg])

              ;; Indeterminate: try to resolve by reading back
              (or (.contains msg "QuorumNotReached")
                  (.contains msg "quorum")
                  (.contains msg "timeout")
                  (.contains msg "Timeout")
                  (.contains msg "Connection refused")
                  (.contains msg "connect timed out")
                  (.contains msg "No leader found")
                  (.contains msg "ServerIsNotTheLeader"))
              (try
                ;; Wait briefly for cluster to stabilize, then try to resolve
                (Thread/sleep 2000)
                (let [leader-conn (let [c (find-leader-conn test)]
                                   (reset! leader-conn-atom c)
                                   c)]
                  (case (:f op)
                    ;; Reads: just retry - idempotent
                    :read
                    (let [val (read-register leader-conn key-str)]
                      (assoc op :type :ok :value (independent/tuple k val)))

                    ;; Writes: read back to check if our value landed.
                    ;; If current == v, the write definitely succeeded.
                    ;; If current != v, another write may have overwritten ours,
                    ;; so we can't tell - keep as :info.
                    :write
                    (let [current (read-register leader-conn key-str)]
                      (if (= current v)
                        (assoc op :type :ok)
                        (assoc op :type :info :error [:write-uncertain msg])))

                    ;; CAS: read back to check if the new value landed.
                    ;; Same logic - success is definitive, mismatch is ambiguous.
                    :cas
                    (let [[_ new-val] v
                          current (read-register leader-conn key-str)]
                      (if (= current new-val)
                        (assoc op :type :ok)
                        (assoc op :type :info :error [:cas-uncertain msg])))))
                ;; Resolution failed - truly indeterminate
                (catch Exception _
                  (assoc op :type :info :error [:unavailable msg])))

              :else
              (assoc op :type :info :error [:unknown msg])))))))

  (teardown! [this test])

  (close! [this test]))

(defn r [_ _] {:type :invoke :f :read :value nil})
(defn w [_ _] {:type :invoke :f :write :value (rand-int 100)})
(defn cas [_ _] {:type :invoke :f :cas :value [(rand-int 100) (rand-int 100)]})

(defn workload
  "Returns a linearizable register test workload map."
  [opts]
  {:client    (map->RegisterClient {})
   :generator (independent/concurrent-generator
                (:concurrency opts 5)
                (range)
                (fn [k]
                  (gen/mix [r w cas])))
   :checker   (independent/checker
                (checker/compose
                  {:linear   (checker/linearizable
                               {:model     (model/cas-register)
                                :algorithm :linear})
                   :timeline (timeline/html)}))})
