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

(defn- get-leader-conn
  "Returns a client connected to the current leader, or throws."
  [test]
  (or (ac/leader-client test {:password (:root-password test) :database "jepsen"})
      (throw (ex-info "No leader found" {}))))

(defrecord RegisterClient [conn node]
  client/Client
  (open! [this test node]
    (assoc this
           :conn (ac/make-client node {:password (:root-password test)
                                       :database "jepsen"})
           :node node))

  (setup! [this test]
    (locking (:setup-lock test)
      (when (compare-and-set! (:setup-done test) false true)
        (info "Setting up register schema on" node)
        (let [deadline (+ (System/currentTimeMillis) 60000)]
          (loop [attempt 1]
            (let [result (try
                           (let [leader-conn (get-leader-conn test)]
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
    (let [[k v] (:value op)]
      (try
        ;; Always route to the leader for linearizable operations
        (let [leader-conn (get-leader-conn test)]
          (case (:f op)
            :read
            (let [val (read-register leader-conn (str "r" k))]
              (assoc op :type :ok :value (independent/tuple k val)))

            :write
            (do (write-register! leader-conn (str "r" k) v)
                (assoc op :type :ok))

            :cas
            (let [[old-val new-val] v
                  success (cas-register! leader-conn (str "r" k) old-val new-val)]
              (assoc op :type (if success :ok :fail)))))

        (catch Exception e
          (let [msg (.getMessage e)]
            ;; Invalidate leader cache on connection errors so we re-discover
            (when (and msg (or (.contains msg "Connection refused")
                              (.contains msg "connect timed out")
                              (.contains msg "ServerIsNotTheLeader")))
              (ac/invalidate-leader!))
            (cond
              (and msg (or (.contains msg "QuorumNotReached")
                          (.contains msg "quorum")
                          (.contains msg "timeout")
                          (.contains msg "Timeout")
                          (.contains msg "Connection refused")
                          (.contains msg "connect timed out")
                          (.contains msg "No leader found")
                          (.contains msg "ServerIsNotTheLeader")))
              (assoc op :type :info :error [:unavailable msg])

              (and msg (.contains msg "ConcurrentModification"))
              (assoc op :type :fail :error [:conflict msg])

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
