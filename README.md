# arcadedb-jepsen

[Jepsen](https://jepsen.io) tests for [ArcadeDB](https://arcadedb.com), a multi-model distributed database.

Verifies correctness of ArcadeDB's Raft-based high availability under network partitions, process crashes, and process pauses.

## Results

Tested against the `apache-ratis` branch, 5-node cluster, 90-second runs, `--read-consistency read_your_writes` (default).

Each test uses a fresh Docker cluster to eliminate cross-test state contamination.

### Bank Workload (ACID balance conservation)

Tests that the total balance across 5 accounts (initially 1000 each = 5000 total) is always conserved, even under concurrent transfers and faults.

| Nemesis | Result |
|---------|--------|
| none | :white_check_mark: PASS |
| partition | :white_check_mark: PASS |
| kill | :white_check_mark: PASS |
| pause | :white_check_mark: PASS |
| all | :white_check_mark: PASS |

### Set Workload (replication completeness)

Tests that no acknowledged writes are lost during replication. Inserts unique elements, reads all, verifies every successfully added element appears in subsequent reads.

| Nemesis | Result |
|---------|--------|
| none | :white_check_mark: PASS |
| partition | :white_check_mark: PASS |
| kill | :white_check_mark: PASS |
| pause | :white_check_mark: PASS |
| all | :white_check_mark: PASS |

### Elle Workload (transaction isolation via cycle detection)

Tests transaction isolation using Elle's dependency-graph cycle detection. Executes multi-key read/write transactions and checks for anomalies: G0 (dirty write), G1a/G1b (dirty/intermediate reads), G2 (anti-dependency), and lost updates. G1c is excluded because writes commit atomically while reads execute as separate HTTP calls after the transaction commits, making circular information flow cycles a test implementation artifact rather than a real isolation violation.

| Nemesis | Result |
|---------|--------|
| none | :white_check_mark: PASS |
| partition | :white_check_mark: PASS |
| kill | :white_check_mark: PASS |
| pause | :white_check_mark: PASS |
| all | :white_check_mark: PASS |

### Register Workload (linearizability via Knossos)

Tests single-key read/write/CAS operations routed to the leader, checked by the Knossos linearizability checker for strict linearizability.

| Nemesis | Result |
|---------|--------|
| none | :white_check_mark: PASS |
| partition | :white_check_mark: PASS |
| kill | :white_check_mark: PASS |
| pause | :white_check_mark: PASS |
| all | :white_check_mark: PASS |

Each test takes ~3-4 minutes (cluster startup + 90s test + analysis + teardown). The full matrix of 20 tests takes ~60 minutes with fresh cluster restarts between each test.

### Register-Follower Workload (follower linearizability via ReadIndex)

Same register workload, but READS are routed to a non-leader node with `X-ArcadeDB-Read-Consistency: LINEARIZABLE` and no bookmark. This exercises the Ratis ReadIndex path on followers (`RaftHAServer.ensureLinearizableFollowerRead()`): the follower issues `sendReadOnly()` to the leader, the leader verifies it still holds a quorum and returns its current commit index, the follower waits for its local state machine to catch up, then serves the read. Without that round-trip a lagging follower would serve stale data and fail Knossos. Writes still go to the leader.

| Nemesis | Result |
|---------|--------|
| none | :white_check_mark: PASS |
| partition | :white_check_mark: PASS |
| kill | :white_check_mark: PASS |
| pause | :white_check_mark: PASS |
| clock | :white_check_mark: PASS |
| all | :white_check_mark: PASS |
| all+clock | :white_check_mark: PASS |

### Register-Bookmark Workload (follower read-your-writes via commit-index bookmark)

Same register workload with follower reads, but every write response's `X-ArcadeDB-Commit-Index` header is captured and echoed back as `X-ArcadeDB-Read-After` on subsequent reads. The follower waits for its local apply to reach that index before serving. This covers the bookmark-carrying path, which is cheaper than ReadIndex but only guarantees read-your-writes for the issuing client (not global linearizability across clients).

| Nemesis | Result |
|---------|--------|
| none | :white_check_mark: PASS |
| partition | :white_check_mark: PASS |
| kill | :white_check_mark: PASS |
| pause | :white_check_mark: PASS |
| clock | :white_check_mark: PASS |
| all | :white_check_mark: PASS |
| all+clock | :white_check_mark: PASS |

Total suite: **34/34 PASS** (20 leader-only + 14 follower-read variants).

### Read Consistency Levels

ArcadeDB supports three read consistency levels via `arcadedb.ha.readConsistency` (or per-request via the `X-ArcadeDB-Read-Consistency` HTTP header):

| Level | Performance | Consistency | Use case |
|-------|-------------|-------------|----------|
| `eventual` | Fastest | May read stale data on followers | Analytics, dashboards |
| `read_your_writes` (default) | Fast | Leader reads from local DB; followers wait for client's last write | Most OLTP workloads |
| `linearizable` | +1 RTT when lease expired | Full linearizability even under process pauses | Financial transactions, coordination |

In **linearizable mode** (recommended for Jepsen testing), the leader verifies it still holds the Raft lease before every read via Ratis's `sendReadOnly()` API (Section 6.4 of the Raft paper). If the lease is valid (common case), this is a local timestamp check with no network round-trip. If the lease expired (e.g., after VM suspend or extreme GC pause), Ratis sends heartbeats to a majority (~1 RTT) before serving the read.

## Workloads

| Workload | What it tests | Checker |
|----------|---------------|---------|
| **bank** | ACID transactions: transfers between 5 accounts, checks total balance conservation (5000) | Custom conservation checker |
| **set** | Replication completeness: inserts unique elements, verifies none are lost | Custom set checker |
| **elle** | Transaction isolation: multi-key read/write txns, checks for G0/G1a/G1b/G2/lost-update | Elle cycle-detection checker |
| **register** | Linearizability: single-key read/write/CAS, all operations routed to the leader | Knossos linearizability checker |
| **register-follower** | Linearizability of reads routed to a follower with `LINEARIZABLE` + no bookmark (ReadIndex path) | Knossos linearizability checker |
| **register-bookmark** | Read-your-writes of reads routed to a follower with `LINEARIZABLE` + write-derived bookmark | Knossos linearizability checker |

## Nemesis Faults

| Nemesis | Description |
|---------|-------------|
| `none` | No faults (baseline) |
| `partition` | Random network partitions via iptables |
| `kill` | SIGKILL random nodes (simulates crashes) |
| `pause` | SIGSTOP/SIGCONT random nodes (simulates GC pauses) |
| `clock` | `date -s` shifts one node's clock by a random ±60s, best-effort `ntpdate` to reset |
| `all` | partition + kill + pause combined |
| `all+clock` | all + clock |

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

## Batch Scripts

Two scripts sweep the test matrix:

| Script | Matrix | Purpose |
|--------|--------|---------|
| `run-all-tests.sh [time-limit]` | Leader block (20 tests) + Follower block (14 tests) = **34 tests** | Full regression sweep; the follower block auto-passes `--read-consistency linearizable` |
| `run-follower-tests.sh [time-limit]` | 2 workloads (register-follower, register-bookmark) × 7 nemeses (none, partition, kill, pause, clock, all, all+clock) = **14 tests** | Follower read-consistency paths only; useful for focused iteration |

Both default to a 90s time-limit per test. Partition / all variants are shortened to 30s to keep Knossos analysis tractable.

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
| `--workload` | `bank` | Workload: `bank`, `set`, `elle`, `register`, `register-follower`, `register-bookmark` |
| `--nemesis` | `all` | Faults: `none`, `partition`, `kill`, `pause`, `clock`, `all`, `all+clock` |
| `--time-limit` | `60` | Test duration in seconds |
| `--local-dist` | `false` | Use local build from `dist/` instead of downloading |
| `--version` | `25.3.1` | ArcadeDB release version (ignored with `--local-dist`) |
| `--read-consistency` | `read_your_writes` | ArcadeDB server read consistency: `eventual`, `read_your_writes`, `linearizable` |
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
    set.clj                Set workload (replication completeness)
    elle.clj               Elle workload (transaction isolation via cycle detection)
    register.clj           Register workload (linearizability, leader-only reads)
    register_follower.clj  Register workload with LINEARIZABLE follower reads (ReadIndex)
    register_bookmark.clj  Register workload with bookmark-carrying follower reads
    nemesis.clj            Fault injection: partitions, kills, pauses, clock skew
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
