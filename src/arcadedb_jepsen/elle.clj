(ns arcadedb-jepsen.elle
  "Elle rw-register workload for ArcadeDB Jepsen tests.
   Executes multi-key read/write transactions and uses Elle's cycle-detection
   algorithm to verify transaction isolation. Detects anomalies including:
   - G0 (dirty write), G1a/b/c (dirty/intermediate reads)
   - G2 (anti-dependency), lost updates, write skew
   This is the gold standard for transaction isolation testing."
  (:require [arcadedb-jepsen.client :as ac]
            [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [elle.rw-register :as rw]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]))

(defn create-schema!
  "Creates the ElleReg type if it doesn't exist."
  [client]
  (ac/command! client "sql" "CREATE document TYPE ElleReg IF NOT EXISTS")
  (ac/command! client "sql" "CREATE PROPERTY ElleReg.k IF NOT EXISTS INTEGER")
  (ac/command! client "sql" "CREATE PROPERTY ElleReg.val IF NOT EXISTS INTEGER")
  (ac/command! client "sql" "CREATE INDEX IF NOT EXISTS ON ElleReg (k) UNIQUE"))

(defn txn-script
  "Builds the SQL script for an Elle transaction: every micro-op, in order, inside one
   REPEATABLE_READ transaction. Read i is bound to $ri and returned in a map keyed
   \"ri\", so each read value is the one seen inside the transaction (including the
   transaction's own earlier writes), not a later committed state."
  [txn]
  (let [sb    (StringBuilder. "BEGIN ISOLATION REPEATABLE_READ;\n")
        reads (keep-indexed (fn [i [f]] (when (= :r f) i)) txn)]
    (doseq [[i [f k v]] (map-indexed vector txn)]
      (.append sb (case f
                    :r (str "LET $r" i " = SELECT val FROM ElleReg WHERE k = " k ";\n")
                    :w (str "UPDATE ElleReg SET val = " v " UPSERT WHERE k = " k ";\n"))))
    ;; RETRY re-runs the whole BEGIN..COMMIT block, LETs included, on a conflict.
    (.append sb "COMMIT RETRY 5;\n")
    (when (seq reads)
      (.append sb (str "RETURN {"
                       (str/join ", " (map #(str "\"r" % "\": $r" %) reads))
                       "};\n")))
    (.toString sb)))

(defn execute-txn!
  "Executes an Elle transaction (a vector of [:r k nil] and [:w k v] micro-ops) as a
   single ArcadeDB transaction and returns it with the read values filled in."
  [client txn]
  (let [result  (ac/command! client "sqlscript" (txn-script txn))
        ;; RETURN {...} comes back as {:result [{:value {:r0 [{:val 5}] :r2 []}}]}
        values  (get-in result [:result 0 :value])]
    (vec (map-indexed (fn [i [f k v]]
                        (case f
                          :r [f k (:val (first (get values (keyword (str "r" i)))))]
                          :w [f k v]))
                      txn))))

(defrecord ElleClient [conn node leader-conn-atom]
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
        (info "Setting up Elle schema on" node)
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
                               (do (warn "Elle setup attempt" attempt "failed:" (.getMessage e))
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
        (let [txn    (:value op)
              result (execute-txn! leader-conn txn)]
          (assoc op :type :ok :value result))

        (catch Exception e
          (let [msg (or (.getMessage e) "")]
            (ac/invalidate-leader!)
            (reset! leader-conn-atom nil)
            (cond
              (.contains msg "ConcurrentModification")
              (assoc op :type :fail :error [:conflict msg])

              (or (.contains msg "QuorumNotReached")
                  (.contains msg "quorum")
                  (.contains msg "timeout")
                  (.contains msg "Timeout")
                  (.contains msg "Connection refused")
                  (.contains msg "connect timed out")
                  (.contains msg "No leader found")
                  (.contains msg "ServerIsNotTheLeader"))
              (assoc op :type :info :error [:unavailable msg])

              :else
              (assoc op :type :info :error [:unknown msg])))))))

  (teardown! [this test])

  (close! [this test]))

(defn workload
  "Returns an Elle rw-register test workload map.
   Tests transaction isolation using Elle's cycle-detection algorithm."
  [opts]
  {:client    (map->ElleClient {})
   :generator (rw/gen {:key-count          5
                       :min-txn-length     1
                       :max-txn-length     4
                       :max-writes-per-key 64})
   :checker   (reify checker/Checker
                (check [_ test history opts]
                  ;; Reads run inside the transaction, so internal consistency and
                  ;; G1c are checked too (Elle includes G1c when G2 is requested).
                  ;; Real-time ordering is left out: HTTP latency between a commit and
                  ;; the next request makes it untestable from the client.
                  (rw/check (merge {:anomalies         [:G0 :G1a :G1b :G2
                                                        :lost-update]
                                    :additional-graphs []}
                                   opts)
                            history)))})
