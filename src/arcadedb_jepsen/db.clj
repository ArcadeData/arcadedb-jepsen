(ns arcadedb-jepsen.db
  "ArcadeDB database lifecycle for Jepsen.
   Handles install, configuration, start, stop, and teardown of ArcadeDB
   nodes via SSH."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [db :as db]
                    [util :as util]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def install-dir "/opt/arcadedb")
(def data-dir "/opt/arcadedb/databases")
(def log-file "/opt/arcadedb/log/arcadedb.log")
(def pid-file "/opt/arcadedb/bin/arcadedb.pid")
(def config-dir "/opt/arcadedb/config")

(defn node-url
  "Returns the download URL for the ArcadeDB distribution."
  [version]
  (str "https://github.com/ArcadeData/arcadedb/releases/download/"
       version "/arcadedb-" version ".tar.gz"))

(defn server-list
  "Builds the ha.serverList string for a Jepsen test.
   Format: host1:raftPort,host2:raftPort,..."
  [test]
  (str/join "," (map #(str (name %) ":2424") (:nodes test))))

(defn install!
  "Downloads and installs ArcadeDB on a node."
  [version]
  (info "Installing ArcadeDB" version)
  (c/su
    (debian/install ["openjdk-21-jdk-headless" "curl"])
    (cu/install-archive! (node-url version) install-dir)))

(defn configure!
  "Writes ArcadeDB server configuration for HA mode."
  [test node]
  (info "Configuring ArcadeDB HA on" node)
  (c/su
    (c/exec :mkdir :-p config-dir)
    ;; Write arcadedb-server.properties
    (c/exec :echo
            (str/join "\n"
              [(str "arcadedb.server.name=" (name node))
               (str "arcadedb.server.rootPassword=" (:root-password test))
               "arcadedb.server.defaultDatabases=jepsen[root]"
               "arcadedb.ha.enabled=true"
               (str "arcadedb.ha.serverList=" (server-list test))
               (str "arcadedb.ha.clusterName=" (:cluster-name test "jepsen-cluster"))
               "arcadedb.ha.quorum=majority"
               "arcadedb.ha.replicationIncomingHost=0.0.0.0"
               "arcadedb.server.httpIncomingHost=0.0.0.0"])
            :> (str config-dir "/arcadedb-server.properties"))))

(defn start!
  "Starts the ArcadeDB server."
  [test node]
  (info "Starting ArcadeDB on" node)
  (c/su
    (c/exec :bash :-c
            (str "cd " install-dir " && "
                 "JAVA_OPTS='-Darcadedb.server.rootPassword=" (:root-password test)
                 " -Darcadedb.server.name=" (name node)
                 " -Darcadedb.server.defaultDatabases=jepsen[root]"
                 " -Darcadedb.ha.enabled=true"
                 " -Darcadedb.ha.serverList=" (server-list test)
                 " -Darcadedb.ha.clusterName=" (:cluster-name test "jepsen-cluster")
                 " -Darcadedb.ha.quorum=majority"
                 " -Darcadedb.ha.replicationIncomingHost=0.0.0.0"
                 " -Darcadedb.server.httpIncomingHost=0.0.0.0"
                 "' nohup bin/server.sh > /dev/null 2>&1 &"))
    ;; Wait for HTTP API to become ready
    (util/await-fn
      (fn []
        (try
          (= "204"
             (c/exec :curl :-s :-o "/dev/null" :-w "%{http_code}"
                     (str "http://localhost:2480/api/v1/ready")))
          (catch Exception _ false)))
      {:retry-interval 2000
       :log-interval   10000
       :log-message    (str "Waiting for ArcadeDB to start on " node)
       :timeout        60000})))

(defn stop!
  "Stops the ArcadeDB server."
  [node]
  (info "Stopping ArcadeDB on" node)
  (c/su
    (cu/grepkill! "arcadedb")))

(defn nuke!
  "Kills the server and wipes all data."
  [node]
  (info "Nuking ArcadeDB on" node)
  (c/su
    (cu/grepkill! "arcadedb")
    (c/exec :rm :-rf data-dir)
    (c/exec :rm :-rf (str install-dir "/log"))
    (c/exec :rm :-rf (str install-dir "/raft-storage*"))))

(defn arcadedb
  "Returns a Jepsen DB implementation for ArcadeDB."
  [version]
  (reify
    db/DB
    (setup! [_ test node]
      (install! version)
      (configure! test node)
      (start! test node))

    (teardown! [_ test node]
      (nuke! node))

    db/LogFiles
    (log-files [_ test node]
      [log-file])

    db/Kill
    (start! [_ test node]
      (start! test node))

    (kill! [_ test node]
      (c/su (cu/grepkill! :9 "arcadedb")))

    db/Pause
    (pause! [_ test node]
      (c/su (cu/grepkill! :stop "arcadedb")))

    (resume! [_ test node]
      (c/su (cu/grepkill! :cont "arcadedb")))))
