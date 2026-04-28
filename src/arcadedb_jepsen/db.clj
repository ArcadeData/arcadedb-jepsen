(ns arcadedb-jepsen.db
  "ArcadeDB database lifecycle for Jepsen.
   Handles install, configuration, start, stop, and teardown of ArcadeDB
   nodes via SSH.

   When the test option `:lazyfs?` is true, the data and Ratis log directories
   are mounted on LazyFS — a FUSE filesystem that buffers writes in memory until
   `fsync()` and can drop the buffer on demand to simulate power loss. Tests
   that exercise this path also force `-Darcadedb.server.mode=production` so the
   server actually calls fsync (the default mode does not)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [db :as db]
                    [util :as util]]
            [jepsen.control.util :as cu]))

(def install-dir "/opt/arcadedb")
(def data-dir "/opt/arcadedb/databases")
;; ArcadeDB+Ratis writes log/snapshot state under /opt/arcadedb/ratis-storage/<server>_<port>/.
;; This is the directory that must be on LazyFS for power-loss tests to actually exercise
;; Ratis's durability path.
(def raft-dir "/opt/arcadedb/ratis-storage")
(def log-file "/opt/arcadedb/log/arcadedb.log")
(def config-dir "/opt/arcadedb/config")
(def local-dist-path "/jepsen/dist/arcadedb.tar.gz")

;; -- LazyFS support --

(def lazyfs-bin             "/usr/local/bin/lazyfs")
(def lazyfs-backing-root    "/var/lib/lazyfs-backing")
(def lazyfs-databases-fifo  "/tmp/lazyfs-databases.fifo")
(def lazyfs-raft-fifo       "/tmp/lazyfs-raft.fifo")
(def lazyfs-databases-cfg   "/tmp/lazyfs-databases.toml")
(def lazyfs-raft-cfg        "/tmp/lazyfs-raft.toml")

(defn lazyfs?
  "Returns true when this test should run with LazyFS-backed storage."
  [test]
  (true? (:lazyfs? test)))

(defn- lazyfs-config-content [fifo]
  (str/join "\n"
    ["[faults]"
     (str "fifo_path=\"" fifo "\"")
     "[cache]"
     "apply_eviction=false"
     "[cache.simple]"
     "custom_size=\"256MB\""
     "blocks_per_page=1"
     "[filesystem]"
     "log_all_operations=false"
     ""]))

(defn- write-lazyfs-config! [path fifo]
  (c/exec :sh :-c
    (str "cat > " path " <<'EOF'\n" (lazyfs-config-content fifo) "EOF\n")))

(defn- mountpoint?
  "Best-effort check that `path` is currently a mounted FUSE filesystem."
  [path]
  (try (c/exec :mountpoint :-q path) true
       (catch Exception _ false)))

(defn- unmount-stale!
  "Idempotently unmounts a path if it's a live mount. Strategy: try a clean
   unmount FIRST (signals the daemon to exit gracefully); fall back to lazy
   unmount; last resort is `umount -lf` for already-broken mounts that report
   'Transport endpoint is not connected' (daemon already gone, mount stranded).
   Killing the daemon before unmounting is what creates that stale state, so
   we do not pkill — the daemon exits on its own when fusermount succeeds."
  [path]
  (when (mountpoint? path)
    (or (try (c/exec :fusermount3 :-u path) true (catch Exception _ nil))
        (try (c/exec :fusermount3 :-u :-z path) true (catch Exception _ nil))
        (try (c/exec :fusermount :-u :-z path) true (catch Exception _ nil))
        (try (c/exec :umount :-lf path) true (catch Exception _ nil)))
    (Thread/sleep 200))
  (try (c/exec :rm :-rf path) (catch Exception _)))

(defn ensure-no-stale-lazyfs!
  "Defensive cleanup: unmount any leftover LazyFS mounts and wipe the backing
   directory. Always safe to call. Runs before install! so a crashed prior run
   cannot leave a stale mount sitting on top of the install dir."
  []
  (unmount-stale! data-dir)
  (unmount-stale! raft-dir)
  (try (c/exec :rm :-rf lazyfs-backing-root) (catch Exception _)))

(defn- mount-one-lazyfs!
  "Mounts LazyFS at `mountpoint`, with `backing` as the underlying real
   directory. `cfg` is the path to the LazyFS TOML config."
  [mountpoint backing cfg]
  (info "Mounting LazyFS at" mountpoint "(backed by" backing ")")
  (c/exec :mkdir :-p mountpoint backing)
  ;; LazyFS daemonizes after the mount is ready. allow_other lets root see
  ;; writes from any uid; modules=subdir + subdir=<backing> chroots the backing
  ;; dir into the mount. The CLI requires space-separated --config-path.
  (c/exec :sh :-c
    (str lazyfs-bin " " mountpoint
         " --config-path " cfg
         " -o allow_other -o modules=subdir -o subdir=" backing))
  (util/await-fn
    (fn [] (mountpoint? mountpoint))
    {:retry-interval 250
     :timeout        15000
     :log-message    (str "Waiting for LazyFS mount at " mountpoint)}))

(defn lazyfs-setup!
  "Creates LazyFS mounts over the data and Ratis log directories. No-op when
   `(lazyfs? test)` is false."
  [test]
  (when (lazyfs? test)
    (info "Setting up LazyFS over ArcadeDB data dirs")
    (c/exec :mkdir :-p lazyfs-backing-root)
    (write-lazyfs-config! lazyfs-databases-cfg lazyfs-databases-fifo)
    (write-lazyfs-config! lazyfs-raft-cfg      lazyfs-raft-fifo)
    (mount-one-lazyfs! data-dir
                       (str lazyfs-backing-root "/databases")
                       lazyfs-databases-cfg)
    (mount-one-lazyfs! raft-dir
                       (str lazyfs-backing-root "/ratis-storage")
                       lazyfs-raft-cfg)))

(defn lazyfs-teardown!
  "Unmounts LazyFS and removes backing data. No-op when `(lazyfs? test)` is false.
   Crucially, this does NOT pkill the LazyFS daemon — fusermount tells the daemon
   to exit on its own. Killing the daemon first leaves the mount stranded in
   'Transport endpoint is not connected' state, which subsequent installs can't
   undo without a container restart."
  [test]
  (when (lazyfs? test)
    (info "Tearing down LazyFS mounts")
    (unmount-stale! data-dir)
    (unmount-stale! raft-dir)
    (try (c/exec :rm :-rf lazyfs-backing-root) (catch Exception _))))

;; -- Install / configure / start / stop --

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

(defn install-from-url!
  "Downloads and installs ArcadeDB from a URL."
  [version]
  (info "Installing ArcadeDB" version "from GitHub release")
  (cu/install-archive! (node-url version) install-dir))

(defn install-from-local!
  "Installs ArcadeDB from a local tarball mounted in the container."
  []
  (info "Installing ArcadeDB from local distribution" local-dist-path)
  (c/exec :sh :-c (str "rm -rf " install-dir " && mkdir -p " install-dir
                        " && tar xzf " local-dist-path " -C " install-dir " --strip-components=1")))

(defn install!
  "Installs ArcadeDB. Uses local distribution if available, otherwise downloads."
  [test]
  ;; Always clean up stale LazyFS state first — a prior crashed run could leave
  ;; a mount sitting on top of /opt/arcadedb, in which case `rm -rf install-dir`
  ;; would fail or behave oddly.
  (ensure-no-stale-lazyfs!)
  (if (:local-dist test)
    (install-from-local!)
    (install-from-url! (:version test "25.3.1"))))

(defn configure!
  "Writes ArcadeDB server configuration for HA mode."
  [test node]
  (info "Configuring ArcadeDB HA on" node)
  (c/exec :mkdir :-p config-dir)
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
                       " -Darcadedb.server.httpIncomingHost=0.0.0.0"
                       " -Darcadedb.ha.readConsistency=" (name (:read-consistency test :read_your_writes))
                       ;; LazyFS tests must run in production mode — the default
                       ;; (development) mode skips fsync, which would render any
                       ;; lose-unfsynced-writes test meaningless.
                       (when (lazyfs? test) " -Darcadedb.server.mode=production"))
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
  "Kills the server, unmounts LazyFS (if any), and wipes all data."
  [test node]
  (info "Nuking ArcadeDB on" node)
  (c/exec :sh :-c "ps aux | grep '[A]rcadeDBServer' | awk '{print $2}' | xargs kill -9 2>/dev/null; sleep 1; true")
  ;; Unmount BEFORE rm -rf — otherwise we'd be deleting through a live mount,
  ;; which either fails or trashes the backing dir in unpredictable ways.
  (lazyfs-teardown! test)
  ;; Tolerate rm failures: a partially-detached FUSE mount can briefly report
  ;; 'Is a directory' even with -rf. The next install! does `rm -rf install-dir`,
  ;; so any leftover state is wiped on the way back in. Failing teardown here
  ;; would crash the test framework before the checker reports `:valid?`.
  (try
    (c/exec :sh :-c (str "rm -rf " data-dir " " install-dir "/log " install-dir "/ratis-storage*"))
    (catch Exception e
      (warn "nuke rm-rf failed (likely stale FUSE mount):" (.getMessage e)))))

(defn arcadedb
  "Returns a Jepsen DB implementation for ArcadeDB."
  []
  (reify
    db/DB
    (setup! [_ test node]
      (install! test)
      (configure! test node)
      (lazyfs-setup! test)
      (start! test node))

    (teardown! [_ test node]
      (nuke! test node))

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
