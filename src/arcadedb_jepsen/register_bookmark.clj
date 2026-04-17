(ns arcadedb-jepsen.register-bookmark
  "Linearizable register workload that captures the X-ArcadeDB-Commit-Index from write
   responses and echoes it back as X-ArcadeDB-Read-After on subsequent follower reads
   with LINEARIZABLE consistency. This exercises the bookmark-carrying code path: the
   follower must wait for its local apply to reach the bookmark before serving the read,
   guaranteeing read-your-writes for this client even when reads are routed to a lagging
   follower.

   Contrast with register-follower, which tests LINEARIZABLE + NO bookmark (ReadIndex
   path). Both paths must pass the Knossos linearizability check."
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
  [test]
  (or (ac/follower-client test {:password (:root-password test) :database "jepsen"})
      (find-leader-conn test)))

(defn- write-and-track!
  "Runs the register upsert and updates `bookmark-atom` with the max commit index seen."
  [client bookmark-atom k v]
  (let [{:keys [commit-index]}
        (ac/command-with-index! client "sqlscript"
                                (str "BEGIN ISOLATION REPEATABLE_READ;"
                                     "LET $existing = SELECT FROM Register WHERE key = '" k "';"
                                     "IF ($existing.size() > 0) {"
                                     "  UPDATE Register SET val = " v " WHERE key = '" k "';"
                                     "} ELSE {"
                                     "  INSERT INTO Register SET key = '" k "', val = " v ";"
                                     "}"
                                     "COMMIT RETRY 10;"))]
    (when commit-index
      (swap! bookmark-atom (fn [cur] (if (or (nil? cur) (> commit-index cur)) commit-index cur))))))

(defn- cas-and-track!
  [client bookmark-atom k old-val new-val]
  (let [{:keys [body commit-index]}
        (ac/command-with-index! client "sql"
                                (str "UPDATE Register SET val = " new-val
                                     " WHERE key = '" k "' AND val = " old-val))
        cnt (or (get-in body [:result 0 :count])
                (get-in body [:result :count])
                0)]
    (when commit-index
      (swap! bookmark-atom (fn [cur] (if (or (nil? cur) (> commit-index cur)) commit-index cur))))
    (pos? cnt)))

(defn- read-register-with-bookmark
  [client bookmark k]
  (let [opts (cond-> {:consistency :linearizable}
               bookmark (assoc :bookmark bookmark))
        result (ac/command! client "sql"
                            (str "SELECT val FROM Register WHERE key = '" k "'")
                            nil
                            opts)]
    (when-let [records (seq (get-in result [:result]))]
      (:val (first records)))))

(defrecord RegisterBookmarkClient [conn node bookmark leader-conn-atom follower-conn-atom]
  client/Client
  (open! [this test node]
    (assoc this
           :conn (ac/make-client node {:password (:root-password test)
                                       :database "jepsen"})
           :node node
           :bookmark           (atom nil)
           :leader-conn-atom   (atom nil)
           :follower-conn-atom (atom nil)))

  (setup! [this test]
    (locking (:setup-lock test)
      (when (compare-and-set! (:setup-done test) false true)
        (info "Setting up register-bookmark schema on" node)
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
          (let [val (read-register-with-bookmark follower-conn @bookmark key-str)]
            (assoc op :type :ok :value (independent/tuple k val)))

          :write
          (do (write-and-track! leader-conn bookmark key-str v)
              (assoc op :type :ok))

          :cas
          (let [[old-val new-val] v
                success (cas-and-track! leader-conn bookmark key-str old-val new-val)]
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
  "Register test where follower reads always carry a bookmark captured from the client's
   most recent successful write on the leader."
  [opts]
  {:client    (map->RegisterBookmarkClient {})
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
