# CLAUDE.md

Instructions for Claude Code when working in this repository.

## Project Overview

This is a Jepsen distributed systems test suite for ArcadeDB. It verifies correctness of ArcadeDB's Ratis-based HA (High Availability) under network partitions, process crashes, and process pauses.

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
lein check    # Verifies all 6 namespaces compile cleanly
```

## Project Structure

```
src/arcadedb_jepsen/
  core.clj      - Main entry point, CLI, test assembly
  db.clj        - ArcadeDB install/start/stop/kill/pause via SSH
  client.clj    - HTTP client for ArcadeDB REST API + leader discovery
  bank.clj      - Bank workload (ACID balance conservation)
  register.clj  - Register workload (linearizability via Knossos)
  nemesis.clj   - Fault injection: partitions, kills, pauses
docker/
  Dockerfile.node     - Debian + JDK 21 + SSH for test nodes
  Dockerfile.control  - Debian + JDK 21 + Leiningen for control
  docker-compose.yml  - 5 nodes + control container
  setup-ssh.sh        - Configures SSH keys between control and nodes
dist/                 - Local ArcadeDB tarball (gitignored)
```

## Workloads

- **bank**: Creates 5 accounts with 1000 each, runs concurrent transfers, checks total balance is always 5000. Tests ACID under replication.
- **register**: Single-key read/write/CAS checked by Knossos for linearizability. Uses leader-routing to test the strongest consistency guarantee.

## CLI Options

```
--workload bank|register    Workload to run (default: bank)
--nemesis none|partition|kill|pause|all    Faults to inject (default: all)
--time-limit N              Test duration in seconds (default: 60)
--local-dist                Use local build from dist/ instead of downloading
--version X.Y.Z             ArcadeDB release version (when not using --local-dist)
--rate N                    Operations per second (default: 10)
```

## Code Guidelines

- All code is Clojure (not Java)
- Use AssertJ-style assertions in test descriptions
- The nemesis is a single reify (not nemesis/compose) to avoid setup/teardown issues
- Leader discovery caches the leader and invalidates on connection errors
- Bank client retries setup with backoff for HA leader election
- Process kill uses `ps | grep '[A]rcadeDB'` trick to avoid matching the grep itself
