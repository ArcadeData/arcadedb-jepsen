# arcadedb-jepsen

[Jepsen](https://jepsen.io) tests for [ArcadeDB](https://arcadedb.com), a multi-model distributed database.

Verifies correctness of ArcadeDB's Raft-based high availability under network partitions, process crashes, and process pauses.

## Results

Tested against the `apache-ratis` branch, 5-node cluster, 120-second runs.

### Bank Workload (ACID balance conservation)

Tests that the total balance across 5 accounts (initially 1000 each = 5000 total) is always conserved, even under concurrent transfers and faults.

| Nemesis | Result | Details |
|---------|--------|---------|
| none | PASS | Baseline - all transfers succeed, balance conserved |
| partition | PASS | Network partitions via iptables - balance conserved |
| kill | PASS | SIGKILL random nodes - balance conserved |
| pause | PASS | SIGSTOP/SIGCONT random nodes - balance conserved |
| all | PASS | All faults combined - balance conserved |

### Register Workload (linearizability via Knossos)

Tests single-key read/write/CAS operations routed to the leader, checked by the Knossos linearizability checker.

| Nemesis | Result | Details |
|---------|--------|---------|
| none | PASS | Baseline - linearizable |
| partition | UNKNOWN | No violation found; Knossos cannot prove linearizability due to indeterminate operations during partitions |
| kill | UNKNOWN | Knossos runs out of memory exploring the search space created by many indeterminate operations from killed nodes |
| pause | FAIL | Stale read from a deposed leader after SIGCONT (see analysis below) |
| all | FAIL | Includes pause - same root cause |

### Analysis

**ACID transactions are solid.** The bank test passes under all fault types. Money is never created or destroyed, even under network partitions, process crashes, and process pauses combined.

**Linearizability has one design limitation with SIGSTOP/SIGCONT (process pause).** When a leader is frozen with SIGSTOP, other nodes elect a new leader and continue processing writes. When the old leader is resumed with SIGCONT, there is a brief window where it still believes it is the leader (Ratis hasn't detected the lease expiry yet) and serves stale reads from its local database.

This happens because ArcadeDB reads bypass the Ratis consensus protocol - they go directly to the local database for performance. The `waitForLocalApply()` barrier (which prevents stale reads during normal leadership transitions from partitions/kills) cannot help here because the deposed leader's own commit index is stale.

**The SIGSTOP scenario maps to:** VM suspend/resume, extreme GC pauses (minutes), or `kill -STOP`/`kill -CONT`. It does not occur during normal network partitions or process crashes.

**The `UNKNOWN` results for partition and kill** are not failures - they mean Knossos found no violation but couldn't exhaustively prove linearizability due to the large number of indeterminate (`:info`) operations. This is a checker limitation, not a database issue. With fewer concurrent operations or shorter test durations, the checker can complete and returns PASS.

**Fix for full linearizability:** Route leader reads through Ratis's `sendReadOnly()` API, which verifies the leader lease is still valid (or sends a heartbeat round-trip to confirm majority). This adds latency per read but guarantees linearizability even under process pauses. This can be configured via `arcadedb.ha.readConsistency=linearizable`.

## Workloads

| Workload | What it tests | Checker |
|----------|---------------|---------|
| **bank** | ACID transactions: transfers between 5 accounts, checks total balance conservation (5000) | Custom conservation checker |
| **register** | Linearizability: single-key read/write/CAS, all operations routed to the leader | Knossos linearizability checker |

## Nemesis Faults

| Nemesis | Description |
|---------|-------------|
| `none` | No faults (baseline) |
| `partition` | Random network partitions via iptables |
| `kill` | SIGKILL random nodes (simulates crashes) |
| `pause` | SIGSTOP/SIGCONT random nodes (simulates GC pauses) |
| `all` | All of the above combined |

## Quick Start (Docker)

The test cluster runs in Docker: 5 Debian nodes (n1-n5) + 1 control node with Leiningen.

### 1. Build ArcadeDB

Build ArcadeDB from the `apache-ratis` branch and copy the distribution:

```bash
# Option A: Build from source (takes a few minutes)
./build-local.sh /path/to/arcadedb

# Option B: Skip build, just copy an existing build
./build-local.sh /path/to/arcadedb --skip-build
```

### 2. Start the Docker Cluster

```bash
cd docker
docker compose up -d
docker exec jepsen-control sh /jepsen/docker/setup-ssh.sh
```

This starts 5 Debian nodes with JDK 21 and SSH, plus a control node with Leiningen.

### 3. Run Tests

```bash
# Bank workload with all faults (120 seconds)
docker exec jepsen-control sh -c 'cd /jepsen && lein run test \
  --local-dist --workload bank --nemesis all --time-limit 120 \
  --node n1 --node n2 --node n3 --node n4 --node n5 \
  --username root --password root'

# Register linearizability with partitions only
docker exec jepsen-control sh -c 'cd /jepsen && lein run test \
  --local-dist --workload register --nemesis partition --time-limit 120 \
  --node n1 --node n2 --node n3 --node n4 --node n5 \
  --username root --password root'

# No faults baseline
docker exec jepsen-control sh -c 'cd /jepsen && lein run test \
  --local-dist --workload bank --nemesis none --time-limit 60 \
  --node n1 --node n2 --node n3 --node n4 --node n5 \
  --username root --password root'
```

### 4. View Results

Results are written to `store/` inside the control container. To browse them:

```bash
docker exec jepsen-control sh -c 'cd /jepsen && lein run serve'
# Then open http://localhost:8080 in your browser
```

Or copy them to the host:

```bash
docker cp jepsen-control:/jepsen/store ./store
```

### 5. Tear Down

```bash
cd docker
docker compose down -v
```

## Running Against a Released Version (No Build Required)

To test a released ArcadeDB version (downloaded from GitHub):

```bash
docker exec jepsen-control sh -c 'cd /jepsen && lein run test \
  --version 25.3.1 --workload bank --nemesis all --time-limit 120 \
  --node n1 --node n2 --node n3 --node n4 --node n5 \
  --username root --password root'
```

Note: released versions before the `apache-ratis` branch do not have Ratis HA, so HA-specific tests won't apply.

## CLI Options

| Option | Default | Description |
|--------|---------|-------------|
| `--workload` | `bank` | Workload: `bank` or `register` |
| `--nemesis` | `all` | Faults: `none`, `partition`, `kill`, `pause`, `all` |
| `--time-limit` | `60` | Test duration in seconds |
| `--local-dist` | `false` | Use local build from `dist/` instead of downloading |
| `--version` | `25.3.1` | ArcadeDB release version (ignored with `--local-dist`) |
| `--rate` | `10` | Operations per second |
| `--node` | (required) | Node hostname (repeat for each node) |
| `--username` | (required) | SSH username |
| `--password` | (required) | SSH password |

## Project Structure

```
arcadedb-jepsen/
  project.clj              Leiningen project (Jepsen 0.3.11)
  build-local.sh           Build ArcadeDB and copy tarball to dist/
  src/arcadedb_jepsen/
    core.clj               Main entry point, CLI, test assembly
    db.clj                 DB lifecycle: install, start, stop, kill, pause
    client.clj             HTTP client for ArcadeDB REST API + leader discovery
    bank.clj               Bank workload (ACID balance conservation)
    register.clj           Register workload (linearizability)
    nemesis.clj            Fault injection: partitions, kills, pauses
  docker/
    docker-compose.yml     5 nodes + control container
    Dockerfile.node        Debian + Temurin JDK 21 + SSH
    Dockerfile.control     Debian + Temurin JDK 21 + Leiningen
    setup-ssh.sh           SSH key distribution
  resources/
    logback.xml            Logging config
```

## Licensing

Licensed under the [Apache License 2.0](LICENSE).

Depends on the [Jepsen framework](https://github.com/jepsen-io/jepsen) (EPL-1.0) as a library dependency. EPL-1.0 is weak copyleft: it requires modifications to EPL code itself to stay EPL, but does not require downstream code that merely uses EPL libraries to adopt the EPL. This test suite does not modify Jepsen source code.
