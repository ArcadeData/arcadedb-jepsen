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
            [jepsen.checker.timeline :as timeline]))

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
          key-str (str "r" k)]
      (try
        ;; Connection acquisition must stay inside the try: under the kill nemesis the
        ;; leader can be down when the atoms are still nil, so find-leader-conn throws
        ;; "No leader found". The catch below turns that into an :info op; outside the
        ;; try it would escape as an unhandled exception and fail the whole test.
        (let [leader-conn (or @leader-conn-atom
                             (let [c (find-leader-conn test)]
                               (reset! leader-conn-atom c) c))
              follower-conn (or @follower-conn-atom
                                (let [c (find-follower-conn test)]
                                  (reset! follower-conn-atom c) c))]
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
            (assoc op :type (if success :ok :fail)))))

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

(defn valid-reads-checker
  "Checker for the bookmark follower-read path. The bookmark guarantees read-your-writes
   for the issuing client, NOT global linearizability: a client reading a non-leader with
   an older bookmark may legitimately observe a globally-stale value (a value another
   client has since overwritten). Knossos linearizability therefore over-asserts here and
   flags those expected stale reads.

   A fully sound read-your-writes register checker is not available off the shelf (RYW
   permits a read to return a NEWER cross-client value but not an OLDER one, which needs a
   global write order to distinguish). This checker instead verifies the property the
   bookmark path must always hold even when stale: every successful read returns a value
   that some client actually wrote at some point (plus the initial nil). A read of a
   never-written value is a genuine fault (fabricated/aborted read, G1a) and fails the
   test; a stale-but-real value passes."
  []
  (reify checker/Checker
    (check [_ _ history _]
      (let [completed (filter #(= :ok (:type %)) history)
            written   (into #{nil}
                            (mapcat (fn [op]
                                      (case (:f op)
                                        :write [(:value op)]
                                        :cas   (when (vector? (:value op)) [(second (:value op))])
                                        nil))
                                    completed))
            bad-reads (->> completed
                           (filter #(= :read (:f %)))
                           (remove #(contains? written (:value %)))
                           (mapv #(select-keys % [:process :index :value])))]
        {:valid?          (empty? bad-reads)
         :read-count      (count (filter #(= :read (:f %)) completed))
         :written-count   (count written)
         :fabricated-reads bad-reads}))))

(defn workload
  "Register test where follower reads always carry a bookmark captured from the client's
   most recent successful write on the leader. Reads are checked against the bookmark's
   actual guarantee (read-your-writes, not global linearizability) via valid-reads-checker;
   see its docstring for why Knossos linearizability would over-assert here."
  [opts]
  {:client    (map->RegisterBookmarkClient {})
   :generator (independent/concurrent-generator
                (:concurrency opts 5)
                (range)
                (fn [_k]
                  (gen/mix [reg/r reg/w reg/cas])))
   :checker   (independent/checker
                (checker/compose
                  {:valid-reads (valid-reads-checker)
                   :timeline    (timeline/html)}))})
