(ns arcadedb-jepsen.register-follower
  "Linearizable register workload that intentionally routes READS to a follower with
   X-ArcadeDB-Read-Consistency: LINEARIZABLE and NO bookmark.

   This exercises the follower ReadIndex path (Ratis sendReadOnly round-trip to the
   leader). Without that wiring, followers silently serve reads from their locally-known
   commit index, which can lag the leader's - so the Knossos linearizability checker
   would catch stale reads here that it never sees in the leader-only register workload.

   Writes still go to the leader (ArcadeDB rejects writes on followers)."
  (:require [arcadedb-jepsen.client :as ac]
            [arcadedb-jepsen.register :as reg]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]))

(defn- find-leader-conn
  [test]
  (or (ac/leader-client test {:password (:root-password test) :database "jepsen"})
      (throw (ex-info "No leader found" {}))))

(defn- find-follower-conn
  "Tries to get a follower client. Falls back to the leader if no follower is reachable
   (e.g. during partitions that isolate every follower). In that fallback the read path
   degenerates to a leader read - acceptable for liveness; correctness is still checked
   by Knossos."
  [test]
  (or (ac/follower-client test {:password (:root-password test) :database "jepsen"})
      (find-leader-conn test)))

(defn read-register-linearizable
  "Reads from a follower with LINEARIZABLE consistency and no bookmark. This is the
   exact request shape that the follower ReadIndex code path must serve correctly."
  [client k]
  (let [result (ac/command! client "sql"
                            (str "SELECT val FROM Register WHERE key = '" k "'")
                            nil
                            {:consistency :linearizable})]
    (when-let [records (seq (get-in result [:result]))]
      (:val (first records)))))

(defrecord RegisterFollowerClient [conn node leader-conn-atom follower-conn-atom]
  client/Client
  (open! [this test node]
    (assoc this
           :conn (ac/make-client node {:password (:root-password test)
                                       :database "jepsen"})
           :node node
           :leader-conn-atom   (atom nil)
           :follower-conn-atom (atom nil)))

  (setup! [this test]
    (locking (:setup-lock test)
      (when (compare-and-set! (:setup-done test) false true)
        (info "Setting up register-follower schema on" node)
        (let [deadline (+ (System/currentTimeMillis) 60000)]
          (loop [attempt 1]
            (let [result (try
                           (let [leader-conn (find-leader-conn test)]
                             (reg/create-schema! leader-conn)
                             (Thread/sleep 3000)
                             :ok)
                           (catch Exception e
                             (if (< (System/currentTimeMillis) deadline)
                               (do (warn "Setup attempt" attempt "failed:" (.getMessage e))
                                   (Thread/sleep (* 2000 (min attempt 5)))
                                   :retry)
                               (throw e))))]
              (when (= result :retry)
                (recur (inc attempt)))))))))

  (invoke! [this test op]
    (let [[k v] (:value op)
          key-str (str "r" k)
          leader-conn (or @leader-conn-atom
                         (let [c (find-leader-conn test)]
                           (reset! leader-conn-atom c) c))
          follower-conn (or @follower-conn-atom
                            (let [c (find-follower-conn test)]
                              (reset! follower-conn-atom c) c))]
      (try
        (case (:f op)
          :read
          (let [val (read-register-linearizable follower-conn key-str)]
            (assoc op :type :ok :value (independent/tuple k val)))

          :write
          (do (reg/write-register! leader-conn key-str v)
              (assoc op :type :ok))

          :cas
          (let [[old-val new-val] v
                success (reg/cas-register! leader-conn key-str old-val new-val)]
            (assoc op :type (if success :ok :fail))))

        (catch Exception e
          (let [msg (or (.getMessage e) "")]
            (ac/invalidate-leader!)
            (reset! leader-conn-atom nil)
            (reset! follower-conn-atom nil)
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
  "Linearizable register test whose reads go to a follower with LINEARIZABLE + no bookmark."
  [opts]
  {:client    (map->RegisterFollowerClient {})
   :generator (independent/concurrent-generator
                (:concurrency opts 5)
                (range)
                (fn [_k]
                  (gen/mix [reg/r reg/w reg/cas])))
   :checker   (independent/checker
                (checker/compose
                  {:linear   (checker/linearizable
                               {:model     (model/cas-register)
                                :algorithm :linear})
                   :timeline (timeline/html)}))})
