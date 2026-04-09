(ns arcadedb-jepsen.db
  "ArcadeDB database lifecycle for Jepsen.
   Handles install, configuration, start, stop, and teardown of ArcadeDB
   nodes via SSH."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [db :as db]
                    [util :as util]]
            [jepsen.control.util :as cu]))

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
  "Downloads and installs ArcadeDB on a node.
   Java and curl are expected to be pre-installed (see docker/Dockerfile.node)."
  [version]
  (info "Installing ArcadeDB" version)
  (cu/install-archive! (node-url version) install-dir))

(defn configure!
  "Writes ArcadeDB server configuration for HA mode."
  [test node]
  (info "Configuring ArcadeDB HA on" node)
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
            :> (str config-dir "/arcadedb-server.properties")))

(defn start!
  "Starts the ArcadeDB server."
  [test node]
  (info "Starting ArcadeDB on" node)
  (let [java-opts (str "-Darcadedb.server.rootPassword=" (:root-password test)
                       " -Darcadedb.server.name=" (name node)
                       " -Darcadedb.server.defaultDatabases=jepsen[root]"
                       " -Darcadedb.ha.enabled=true"
                       " -Darcadedb.ha.serverList=" (server-list test)
                       " -Darcadedb.ha.clusterName=" (:cluster-name test "jepsen-cluster")
                       " -Darcadedb.ha.quorum=majority"
                       " -Darcadedb.ha.replicationIncomingHost=0.0.0.0"
                       " -Darcadedb.server.httpIncomingHost=0.0.0.0")
        script   (str "#!/bin/sh\nexport JAVA_OPTS=\"" java-opts
                      "\"\ncd " install-dir "\nexec bin/server.sh")]
    ;; Ensure no leftover process. [A] trick prevents grep from matching itself.
    (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -9 2>/dev/null; sleep 1; true")
    ;; Write start script and launch as a detached background process
    (c/exec :sh :-c (str "echo '" script "' > /tmp/start-arcadedb.sh && "
                         "chmod +x /tmp/start-arcadedb.sh && "
                         "nohup /tmp/start-arcadedb.sh > /dev/null 2>&1 & "
                         "sleep 3"))
    ;; Wait for HTTP API to become ready
    (info "Waiting for ArcadeDB HTTP API on" node)
    (util/await-fn
      (fn []
        (try
          (c/exec :curl :-sf "http://localhost:2480/api/v1/ready")
          true
          (catch Exception _ false)))
      {:retry-interval 2000
       :log-interval   10000
       :log-message    (str "Waiting for ArcadeDB to start on " node)
       :timeout        120000})))

(defn stop!
  "Stops the ArcadeDB server. Safe to call when no process is running."
  [node]
  (info "Stopping ArcadeDB on" node)
  (try (c/exec :pkill :-f "arcadedb")
       (catch Exception _)))

(defn nuke!
  "Kills the server and wipes all data."
  [node]
  (info "Nuking ArcadeDB on" node)
  ;; Kill all ArcadeDB Java processes and wait for them to die.
  ;; Use pgrep/grep to avoid matching the shell command itself.
  (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -9 2>/dev/null; sleep 1; true")
  (c/exec :sh :-c (str "rm -rf " data-dir " " install-dir "/log " install-dir "/raft-storage*")))

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
      (info "Killing ArcadeDB on" node)
      (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -9 2>/dev/null; true"))

    db/Pause
    (pause! [_ test node]
      (info "Pausing ArcadeDB on" node)
      (c/exec :pkill :-STOP :-f "arcadedb"))

    (resume! [_ test node]
      (info "Resuming ArcadeDB on" node)
      (c/exec :pkill :-CONT :-f "arcadedb"))))
