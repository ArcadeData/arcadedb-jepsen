# arcadedb-jepsen

[Jepsen](https://jepsen.io) tests for [ArcadeDB](https://arcadedb.com), a multi-model distributed database.

These tests verify the correctness of ArcadeDB's Raft-based high availability under network partitions, process crashes, and clock skew.

## Workloads

- **bank** - Transfers between accounts; checks total balance conservation (ACID test)
- **register** - Single-key read/write/CAS; checks linearizability via Knossos

## Nemesis Faults

- **partition** - Random network partitions (iptables)
- **kill** - SIGKILL random nodes (simulates crashes)
- **pause** - SIGSTOP/SIGCONT random nodes (simulates GC pauses or freezes)
- **all** - All of the above combined
- **none** - No faults (baseline)

## Prerequisites

- [Leiningen](https://leiningen.org/) 2.x
- JDK 21+
- [Gnuplot](http://gnuplot.info/) (for result graphs)
- 5 Debian nodes (n1-n5) accessible via SSH as root
- See the [Jepsen README](https://github.com/jepsen-io/jepsen#setting-up-a-jepsen-environment) for detailed setup

## Usage

```bash
# Bank workload with all faults, 60 seconds
lein run test --workload bank --nemesis all --time-limit 60

# Linearizable register with partitions only
lein run test --workload register --nemesis partition --time-limit 120

# Bank workload without faults (baseline)
lein run test --workload bank --nemesis none --time-limit 60

# Specify ArcadeDB version
lein run test --workload bank --version 25.3.1

# Serve results web UI
lein run serve
```

Results are written to `store/` and can be browsed via `lein run serve`.

## Licensing

This test suite is licensed under the [Apache License 2.0](LICENSE).

It depends on the [Jepsen framework](https://github.com/jepsen-io/jepsen) (EPL-1.0) as a library dependency. EPL-1.0 is weak copyleft: it requires modifications to EPL code itself to stay EPL, but does not require downstream code that merely uses EPL libraries to adopt the EPL. This test suite does not modify Jepsen source code.
