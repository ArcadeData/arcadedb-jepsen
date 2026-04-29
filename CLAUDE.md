# CLAUDE.md

Instructions for Claude Code when working in this repository.

## Project Overview

This is a Jepsen distributed systems test suite for ArcadeDB. It verifies correctness of ArcadeDB's Ratis-based HA (High Availability) under network partitions, process crashes, process pauses, clock skew, and **simulated power loss** (LazyFS-backed fsync durability).

Written in Clojure, using the Jepsen framework (v0.3.11).

## Build and Run

### Prerequisites
- Docker (for the 5-node test cluster)
- Leiningen (`brew install leiningen`)
- A local ArcadeDB build (from the `apache-ratis` branch)

### Quick Start

```bash
# 1. Build ArcadeDB and copy distribution
./build-local.sh /path/to/arcadedb

# 2. Start Docker cluster
cd docker && docker compose up -d
docker exec jepsen-control sh /jepsen/docker/setup-ssh.sh

# 3. Run tests
docker exec jepsen-control sh -c 'cd /jepsen && lein run test --local-dist --workload bank --nemesis all --node n1 --node n2 --node n3 --node n4 --node n5 --username root --password root'
```

### Compilation Check
```bash
lein check    # Verifies all 10 namespaces compile cleanly
```

## Project Structure

```
src/arcadedb_jepsen/
  core.clj               - Main entry point, CLI, test assembly
  db.clj                 - ArcadeDB install/start/stop/kill/pause via SSH
  client.clj             - HTTP client for ArcadeDB REST API + leader/follower discovery
                           + consistency headers (X-ArcadeDB-Read-Consistency, X-ArcadeDB-Read-After)
  bank.clj               - Bank workload (ACID balance conservation)
  set.clj                - Set workload (replication completeness)
  elle.clj               - Elle workload (transaction isolation via cycle detection)
  register.clj           - Register workload (linearizability, leader-routed reads)
  register_follower.clj  - Register workload with LINEARIZABLE follower reads (ReadIndex path)
  register_bookmark.clj  - Register workload with bookmark-carrying follower reads
  nemesis.clj            - Fault injection: partitions, kills, pauses, clock skew,
                           LazyFS power loss (lose-unfsynced-writes, leader-targeted variant)
docker/
  Dockerfile.node     - Debian + JDK 21 + SSH + LazyFS (multi-stage build:
                        builder compiles LazyFS, runtime ships only the binary
                        + libpcache.so + fuse3 userland; ~10 MB image growth)
  Dockerfile.control  - Debian + JDK 21 + Leiningen for control
  docker-compose.yml  - 5 nodes + control container (privileged for FUSE mounts)
  setup-ssh.sh        - Configures SSH keys between control and nodes
dist/                 - Local ArcadeDB tarball (gitignored)
run-all-tests.sh         - 34-test baseline sweep (20 leader + 14 follower)
run-follower-tests.sh    - 14-test follower-read sweep (2 workloads × 7 nemeses)
run-lazyfs-tests.sh      - 10-test LazyFS power-loss sweep (5 workloads × 2 nemeses)
```

## Workloads

- **bank**: Creates 5 accounts with 1000 each, runs concurrent transfers, checks total balance is always 5000. Tests ACID under replication.
- **set**: Inserts unique elements concurrently, reads all at the end, verifies no acknowledged write was lost.
- **elle**: Multi-key read/write transactions; Elle's dependency-graph cycle detection checks for G0, G1a, G1b, G2 and lost updates.
- **register**: Single-key read/write/CAS checked by Knossos for linearizability. Uses leader-routing to test the strongest consistency guarantee.
- **register-follower**: Same as register, but READS are routed to a non-leader with `X-ArcadeDB-Read-Consistency: LINEARIZABLE` and no bookmark. Exercises the Ratis `sendReadOnly` ReadIndex path (`RaftHAServer.ensureLinearizableFollowerRead()`). Writes still go to the leader.
- **register-bookmark**: Same as register-follower, but each write's `X-ArcadeDB-Commit-Index` response header is captured and echoed back as `X-ArcadeDB-Read-After` on subsequent reads. Tests the bookmark-wait follower path (cheaper than ReadIndex; read-your-writes for the caller, not global linearizability).

LazyFS is **a nemesis, not a workload** — any of the workloads above can run under `--nemesis lazyfs` or `--nemesis all+lazyfs`. When LazyFS is active, the test map carries `:lazyfs? true` and `db.clj` mounts FUSE on the data + Ratis dirs and adds `-Darcadedb.server.mode=production` to `JAVA_OPTS`.

## CLI Options

```
--workload bank|set|elle|register|register-follower|register-bookmark               (default: bank)
--nemesis  none|partition|kill|pause|clock|lazyfs|all|all+clock|all+lazyfs          (default: all)
--time-limit N              Test duration in seconds (default: 60)
--local-dist                Use local build from dist/ instead of downloading
--version X.Y.Z             ArcadeDB release version (when not using --local-dist)
--read-consistency eventual|read_your_writes|linearizable    Server-side default (default: read_your_writes)
--rate N                    Operations per second (default: 10)
```

## Batch Runners

- `./run-all-tests.sh [time-limit]` — 34-test baseline sweep. **Do not modify**: used by the reported 34/34 pass.
- `./run-follower-tests.sh [time-limit]` — 14-test follower-read sweep. Always sets `--read-consistency linearizable` so the follower paths are exercised. Shortens partition/all variants to 30s.
- `./run-lazyfs-tests.sh [time-limit]` — 10-test LazyFS power-loss sweep (5 workloads × {`lazyfs`, `all+lazyfs`}). Auto-enables production mode; `all+lazyfs` runs are shortened to 30s.

Combined baseline: **44/44 PASS** (20 leader + 14 follower + 10 LazyFS power-loss).

> The 34-test baseline runs in default (development) mode where ArcadeDB does NOT call `fsync()` — it verifies replication and consensus, not on-disk durability. Only the LazyFS sweep runs with production-mode fsync. Flag this nuance if asked about durability guarantees.

## Code Guidelines

- All code is Clojure (not Java)
- Use AssertJ-style assertions in test descriptions
- The nemesis is a single reify (not nemesis/compose) to avoid setup/teardown issues
- Leader discovery caches the leader and invalidates on connection errors; follower discovery is the symmetric inverse (see `client.clj` `find-follower` / `follower-client`)
- Bank client retries setup with backoff for HA leader election
- Process kill uses `ps | grep '[A]rcadeDB'` trick to avoid matching the grep itself
- Consistency headers are added via the package-private helper `apply-consistency-headers!` in `client.clj`; `command!` and `query!` accept an optional trailing opts map `{:consistency :linearizable :bookmark 42}`
- `command-with-index!` is the commit-tracking variant: returns `{:body ... :commit-index N}` by reading `X-ArcadeDB-Commit-Index` from the response
