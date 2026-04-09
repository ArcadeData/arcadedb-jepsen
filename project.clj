(defproject com.arcadedb/arcadedb-jepsen "0.1.0-SNAPSHOT"
  :description "Jepsen tests for ArcadeDB distributed database"
  :url "https://github.com/ArcadeData/arcadedb-jepsen"
  :license {:name "Apache-2.0"
            :url  "https://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [jepsen "0.3.11"]
                 [cheshire "5.13.0"]]
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"
             "-Xmx4g"]
  :main arcadedb-jepsen.core
  :repl-options {:init-ns arcadedb-jepsen.core}
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "1.5.0"]]}})
