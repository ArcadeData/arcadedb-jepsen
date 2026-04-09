(ns arcadedb-jepsen.bank
  "Bank workload for ArcadeDB Jepsen tests.
   Models a set of accounts with balances. Transfers move money between
   accounts; reads check the total balance is conserved. This tests
   ACID transaction correctness under Raft replication with faults."
  (:require [arcadedb-jepsen.client :as ac]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]
                    [independent :as independent]]
            [knossos.op :as op]))

(def account-count
  "Number of accounts in the bank."
  5)

(def initial-balance
  "Starting balance for each account."
  1000)

(defn create-schema!
  "Creates the Account type if it doesn't exist."
  [client]
  (ac/command! client "sql" "CREATE document TYPE Account IF NOT EXISTS")
  (ac/command! client "sql"
               "CREATE PROPERTY Account.acctId IF NOT EXISTS INTEGER")
  (ac/command! client "sql"
               "CREATE PROPERTY Account.balance IF NOT EXISTS INTEGER"))

(defn populate-accounts!
  "Creates initial accounts with equal balances."
  [client]
  (dotimes [i account-count]
    (try
      (ac/command! client "sql"
                   (str "INSERT INTO Account SET acctId = " i
                        ", balance = " initial-balance))
      (catch Exception e
        ;; Might already exist from another node's setup
        (warn "Account" i "insert failed (may already exist):" (.getMessage e))))))

(defn read-balances
  "Reads all account balances. Returns a map of {acctId balance}."
  [client]
  (let [result (ac/command! client "sql"
                            "SELECT acctId, balance FROM Account ORDER BY acctId")]
    (->> (get-in result [:result])
         (map (fn [row] [(:acctId row) (:balance row)]))
         (into (sorted-map)))))

(defn transfer!
  "Transfers amount from account `from` to account `to`.
   Uses a SQL script with retry to handle MVCC conflicts."
  [client from to amount]
  (ac/command! client "sqlscript"
               (str "BEGIN ISOLATION REPEATABLE_READ;"
                    "LET $from = SELECT FROM Account WHERE acctId = " from ";"
                    "LET $to = SELECT FROM Account WHERE acctId = " to ";"
                    "IF ($from.size() > 0 AND $to.size() > 0 AND $from[0].balance >= " amount ") {"
                    "  UPDATE Account SET balance = balance - " amount " WHERE acctId = " from ";"
                    "  UPDATE Account SET balance = balance + " amount " WHERE acctId = " to ";"
                    "}"
                    "COMMIT RETRY 10;")))

(defrecord BankClient [conn node]
  client/Client
  (open! [this test node]
    (assoc this
           :conn (ac/make-client node {:password (:root-password test)
                                       :database "jepsen"})
           :node node))

  (setup! [this test]
    ;; Only one node needs to create schema/data; others will see it via replication.
    ;; Retry with backoff: the HA cluster needs time for leader election.
    (locking (:setup-lock test)
      (when (compare-and-set! (:setup-done test) false true)
        (info "Setting up bank schema and accounts on" node)
        (let [deadline (+ (System/currentTimeMillis) 60000)]
          (loop [attempt 1]
            (let [result (try
                           (create-schema! conn)
                           (Thread/sleep 3000) ;; Wait for schema to replicate
                           (populate-accounts! conn)
                           :ok
                           (catch Exception e
                             (if (< (System/currentTimeMillis) deadline)
                               (do (warn "Setup attempt" attempt "failed:" (.getMessage e) "- retrying...")
                                   (Thread/sleep (* 2000 (min attempt 5)))
                                   :retry)
                               (throw e))))]
              (when (= result :retry)
                (recur (inc attempt)))))))))

  (invoke! [this test op]
    (try
      (case (:f op)
        :read
        (let [balances (read-balances conn)]
          (assoc op :type :ok :value balances))

        :transfer
        (let [{:keys [from to amount]} (:value op)]
          (transfer! conn from to amount)
          (assoc op :type :ok)))
      (catch Exception e
        (let [msg (.getMessage e)]
          (cond
            ;; Quorum not reached - cluster may be partitioned
            (and msg (or (.contains msg "QuorumNotReached")
                        (.contains msg "quorum")
                        (.contains msg "timeout")
                        (.contains msg "Timeout")
                        (.contains msg "Connection refused")
                        (.contains msg "connect timed out")))
            (assoc op :type :info :error [:unavailable msg])

            ;; MVCC conflict - definite failure
            (and msg (.contains msg "ConcurrentModification"))
            (assoc op :type :fail :error [:conflict msg])

            ;; Unknown error
            :else
            (assoc op :type :info :error [:unknown msg]))))))

  (teardown! [this test])

  (close! [this test]))

(defn read-op
  "Generates a read operation."
  [_ _]
  {:type :invoke :f :read :value nil})

(defn transfer-op
  "Generates a random transfer operation."
  [_ _]
  (let [from   (rand-int account-count)
        to     (mod (+ from 1 (rand-int (dec account-count))) account-count)
        amount (inc (rand-int 50))]
    {:type :invoke :f :transfer :value {:from from :to to :amount amount}}))

(defn bank-checker
  "Checker that verifies:
   1. Total balance is conserved across all reads
   2. No negative balances
   3. All accounts are present in every read"
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [reads    (->> history
                          (filter #(and (= :read (:f %)) (op/ok? %)))
                          (map :value))
            expected-total (* account-count initial-balance)
            bad-reads (remove
                        (fn [balances]
                          (and (= account-count (count balances))
                               (= expected-total (reduce + (vals balances)))
                               (every? #(>= % 0) (vals balances))))
                        reads)]
        {:valid?         (empty? bad-reads)
         :read-count     (count reads)
         :bad-reads      (take 10 bad-reads)
         :expected-total expected-total
         :account-count  account-count}))))

(defn workload
  "Returns a bank test workload map."
  [opts]
  {:client    (map->BankClient {})
   :generator (gen/mix [read-op transfer-op])
   :checker   (checker/compose
                {:bank     (bank-checker)
                 :perf     (checker/perf)})})
