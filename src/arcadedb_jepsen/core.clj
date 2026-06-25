(ns arcadedb-jepsen.core
  "Main entry point for ArcadeDB Jepsen tests.
   Ties together DB lifecycle, workloads, nemesis, and CLI."
  (:require [arcadedb-jepsen.bank :as bank]
            [arcadedb-jepsen.db :as db]
            [arcadedb-jepsen.nemesis :as arcn]
            [arcadedb-jepsen.register :as register]
            [arcadedb-jepsen.register-follower :as register-follower]
            [arcadedb-jepsen.register-bookmark :as register-bookmark]
            [arcadedb-jepsen.set :as set-workload]
            [arcadedb-jepsen.elle :as elle-workload]
            [arcadedb-jepsen.ha-convergence :as ha-convergence]
            [clojure.tools.logging :refer [info]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [generator :as gen]
                    [os :as os]
                    [tests :as tests]]))

(def root-password "playwithdata")

(def workloads
  "Map of workload names to constructors."
  {:bank              bank/workload
   :register          register/workload
   :register-follower register-follower/workload
   :register-bookmark register-bookmark/workload
   :set               set-workload/workload
   :elle              elle-workload/workload
   :ha-convergence    ha-convergence/workload})

(def heal-op
  "Single deterministic nemesis op that fully restores the cluster before the final
   convergence read (heal partitions, resume paused, recover power loss, start down nodes)."
  {:type :info :f :heal-all})

(def fault-sets
  "Named sets of faults for the nemesis."
  {:partition  #{:partition}
   :kill       #{:kill}
   :pause      #{:pause}
   :clock      #{:clock}
   :lazyfs     #{:lazyfs}
   :all        #{:partition :kill :pause}
   :all+clock  #{:partition :kill :pause :clock}
   :all+lazyfs #{:partition :kill :pause :lazyfs}
   :none       #{}})

(defn arcadedb-test
  "Constructs a Jepsen test map for ArcadeDB."
  [opts]
  (let [workload-name (:workload opts :bank)
        workload      ((workloads workload-name) opts)
        faults        (get fault-sets (:nemesis opts :all) #{:partition})
        nem           (arcn/full-nemesis {:faults faults})
        local-dist?   (:local-dist opts false)
        lazyfs?       (contains? faults :lazyfs)]
    (merge tests/noop-test
           opts
           {:name          (str "arcadedb-" (name workload-name)
                               "-" (name (:nemesis opts :all))
                               (when local-dist? "-local"))
            :os            os/noop
            :db            (db/arcadedb)
            :client        (:client workload)
            :nemesis       (:nemesis nem)
            :checker       (checker/compose
                             {:workload (:checker workload)
                              :perf     (checker/perf)
                              :clock    (checker/clock-plot)
                              :ex       (checker/unhandled-exceptions)})
            :generator     (let [client-gen (->> (:generator workload)
                                               (gen/stagger (/ (:rate opts 10)))
                                               (gen/clients))
                                  nem-gen   (:generator nem)
                                  main      (->> (if nem-gen
                                                   (gen/any client-gen (gen/nemesis nem-gen))
                                                   client-gen)
                                                 (gen/time-limit (:time-limit opts 60)))
                                  final-gen (:final-generator workload)]
                            ;; If the workload has a :final-generator (e.g. ha-convergence's
                            ;; per-node read), run it AFTER healing the nemesis and letting
                            ;; the cluster settle, so it observes the recovered steady state.
                            (if final-gen
                              (gen/phases
                                main
                                (gen/nemesis (gen/once heal-op))
                                (gen/sleep 25)
                                (gen/clients final-gen))
                              main))
            :root-password     root-password
            :cluster-name      "jepsen-cluster"
            :local-dist        local-dist?
            :lazyfs?           lazyfs?
            :read-consistency  (:read-consistency opts :read_your_writes)
            :setup-lock    (Object.)
            :setup-done    (atom false)
            :set-counter   (atom 0)
            :pure-generators true})))

(def cli-opts
  "Additional CLI options for arcadedb-jepsen."
  [[nil "--version VERSION" "ArcadeDB version to install (ignored with --local-dist)"
    :default "25.3.1"]

   [nil "--local-dist" "Use local ArcadeDB distribution from dist/ instead of downloading"
    :default false]

   [nil "--production" "Force -Darcadedb.server.mode=production (real fsync) even without LazyFS"
    :default false]

   ["-w" "--workload WORKLOAD" "Workload: bank, register, register-follower, register-bookmark, set, elle, ha-convergence"
    :default :bank
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]

   [nil "--nemesis NEMESIS" "Nemesis: partition, kill, pause, clock, lazyfs, all, all+clock, all+lazyfs, none"
    :default :all
    :parse-fn keyword
    :validate [fault-sets (cli/one-of fault-sets)]]

   [nil "--read-consistency LEVEL" "Read consistency: eventual, read_your_writes, linearizable"
    :default :read_your_writes
    :parse-fn keyword
    :validate [#{:eventual :read_your_writes :linearizable}
               "Must be eventual, read_your_writes, or linearizable"]]

   [nil "--rate RATE" "Operations per second"
    :default 10
    :parse-fn read-string
    :validate [pos? "Must be positive"]]])

(defn -main
  "Entry point for lein run."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  arcadedb-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
