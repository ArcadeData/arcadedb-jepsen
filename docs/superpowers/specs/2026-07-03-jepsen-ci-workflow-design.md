# Design: Scheduled/Manual Jepsen CI Workflow

Date: 2026-07-03

## Purpose

Run the ArcadeDB Jepsen suite automatically against the latest `main`-branch
build of ArcadeDB — once a day on a schedule, and on demand via manual
dispatch — so HA regressions surface without a human remembering to kick off
a local run.

## Background

The suite currently only runs locally: a developer builds ArcadeDB from
source (`build-local.sh`) or points at a downloaded release tarball, then
brings up the 5-node Docker cluster and runs one of the `run-*-tests.sh`
sweeps by hand. `CLAUDE.md` and `README.md` say the suite targets ArcadeDB's
`apache-ratis` feature branch; that's stale — the HA/Ratis work has since
landed on `main`, and `main` is now the branch under test.

ArcadeData publishes `arcadedata/arcadedb:latest` on Docker Hub, rebuilt from
`main` on (at least) every day's activity — confirmed via the Docker Hub API,
its `last_pushed` timestamp matches the current date. Inspecting the image
(`docker run --rm --entrypoint sh arcadedata/arcadedb:latest -c 'ls /home/arcadedb'`)
shows `/home/arcadedb` contains `bin/`, `lib/`, `config/`, `databases/`,
`log/`, `backups/`, `replication/` — the same top-level layout ArcadeDB's
release tarball extracts to, and the same layout `db.clj`'s `install-dir`
(`/opt/arcadedb`) expects.

## Approach

### 1. Getting the ArcadeDB binaries: repackage the Docker image as a tarball

Rather than adding a Dockerfile stage that does `FROM arcadedata/arcadedb:latest`
and copies `/home/arcadedb` into the node image at build time (which would
fight with `db.clj`'s existing `install!` logic — it always runs `rm -rf
install-dir` then re-populates it from either `--local-dist` or a downloaded
release, so anything baked into the image ahead of time would just get wiped
and re-fetched), a new script extracts the image's contents into
`dist/arcadedb.tar.gz`, wrapped in a single top-level directory so it's
byte-for-byte compatible with the existing `install-from-local!` path (which
does `tar xzf ... --strip-components=1`).

New script: `fetch-arcadedb-image.sh` (sibling to `build-local.sh`):

```bash
#!/bin/bash
# Pulls arcadedata/arcadedb:<tag> and repackages its contents as dist/arcadedb.tar.gz,
# in the same layout build-local.sh produces, for use with --local-dist.
set -e
IMAGE="${1:-arcadedata/arcadedb:latest}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

docker pull "$IMAGE"
CID=$(docker create "$IMAGE")
trap 'docker rm -f "$CID" >/dev/null 2>&1' EXIT

WORKDIR=$(mktemp -d)
docker cp "$CID:/home/arcadedb/." "$WORKDIR/arcadedb"

mkdir -p "$SCRIPT_DIR/dist"
tar czf "$SCRIPT_DIR/dist/arcadedb.tar.gz" -C "$WORKDIR" arcadedb
rm -rf "$WORKDIR"

echo "Distribution repackaged to dist/arcadedb.tar.gz ($(du -h "$SCRIPT_DIR/dist/arcadedb.tar.gz" | awk '{print $1}'))"
```

This means **no changes** are needed to `Dockerfile.node`, `docker-compose.yml`,
or `db.clj` — the CI workflow uses the exact same `--local-dist` flag and
mount (`../dist:/jepsen/dist:ro`) that local development already exercises
and that's already proven correct.

### 2. Workflow: `.github/workflows/jepsen.yml`

**Triggers:**
- `schedule`: `0 3 * * *` (03:00 UTC daily)
- `workflow_dispatch`, with inputs:
  - `suite`: choice of `all` (default), `baseline`, `follower`, `lazyfs`, `ha-convergence`
  - `time-limit`: number, default `90` (seconds; forwarded to whichever `run-*-tests.sh` script(s) run)

**Runner:** `ubuntu-latest` (GitHub-hosted). Privileged containers and FUSE
mounts (LazyFS) are supported on standard hosted runners. Job timeout is set
generously (`timeout-minutes: 360`, GitHub's max) since a full nightly sweep
across all four scripts (~50 tests) can run several hours.

**Steps:**
1. Checkout this repo.
2. Run `fetch-arcadedb-image.sh` to produce `dist/arcadedb.tar.gz`.
3. `docker compose build` then `docker compose up -d` (in `docker/`).
4. `docker exec jepsen-control sh /jepsen/docker/setup-ssh.sh`.
5. Run the suite selected by `suite` (or all four, for scheduled runs):
   - `baseline` → `run-all-tests.sh $TIME_LIMIT`
   - `follower` → `run-follower-tests.sh $TIME_LIMIT`
   - `lazyfs` → `run-lazyfs-tests.sh $TIME_LIMIT`
   - `ha-convergence` → `run-ha-convergence-tests.sh $TIME_LIMIT`
   - `all` → all four, sequentially
6. Copy `store/` out of the control container (`docker cp jepsen-control:/jepsen/store ./store`) and upload as a workflow artifact — always, even on failure — so a failing run's Jepsen history/analysis is inspectable afterward.
7. Append a PASS/FAIL/UNKNOWN summary to `$GITHUB_STEP_SUMMARY`.
8. `docker compose down -v` — always (`if: always()`), so a failed run doesn't leave containers/volumes behind on the (ephemeral) runner.
9. Job fails if any invoked script reported a failure (see below — this needs a small fix first).

Scheduled (cron) runs always use `suite=all` and the default 90s time limit;
manual dispatch lets a developer pick a narrower/faster suite for a quick
check.

When `suite=all` runs the four scripts sequentially in one step, an early
script's non-zero exit (once the fix in §3 lands) must not skip the
remaining scripts — the step tracks failure across all four and exits
non-zero only at the end, e.g.:

```bash
OVERALL=0
./run-all-tests.sh "$TIME_LIMIT" || OVERALL=1
./run-follower-tests.sh "$TIME_LIMIT" || OVERALL=1
./run-lazyfs-tests.sh "$TIME_LIMIT" || OVERALL=1
./run-ha-convergence-tests.sh "$TIME_LIMIT" || OVERALL=1
exit $OVERALL
```

### 3. Required fix: `run-*-tests.sh` must exit non-zero on failure

Today, none of the four sweep scripts (`run-all-tests.sh`,
`run-follower-tests.sh`, `run-lazyfs-tests.sh`,
`run-ha-convergence-tests.sh`) exit with a non-zero status when `FAIL>0` —
they only print a results table and implicitly return 0. Run under CI as-is,
the job would always report green regardless of actual test outcomes. Each
script gets one line added at the end:

```bash
[ "$FAIL" -gt 0 ] && exit 1
exit 0
```

### 4. Doc updates

`CLAUDE.md` and `README.md` currently say the suite is "tested against the
apache-ratis branch" — confirmed stale; the suite now targets ArcadeDB's
`main` branch. Update both to reflect that, and mention the new
`fetch-arcadedb-image.sh` script and the scheduled workflow alongside the
other batch runners.

## Explicitly out of scope (for now)

- No Slack/webhook or auto-filed-GitHub-issue notification on failure —
  relying on GitHub Actions' own UI/email failure signal, the run summary,
  and the uploaded `store/` artifact. Can be layered on later once the
  workflow has proven itself out.
- No self-hosted runner — GitHub-hosted `ubuntu-latest` is assumed sufficient;
  revisit if runs prove too slow or resource-constrained in practice.
- No Maven-snapshot-based fetch path — the Docker-image repackaging approach
  covers the "latest main build" need without a second code path to maintain.

## Testing / verification plan

- Exercise `fetch-arcadedb-image.sh` locally against `arcadedata/arcadedb:latest`
  and confirm `dist/arcadedb.tar.gz` installs correctly via the existing
  `--local-dist` flow (i.e. a single `bank`/`none` run passes).
- Exercise the new workflow via `workflow_dispatch` with `suite=baseline`
  and a short `time-limit` (e.g. 30s) before relying on the nightly cron,
  to confirm the end-to-end pipeline (image fetch → cluster up → tests →
  artifact upload → summary → teardown) works in CI.
- Deliberately break one script's exit-code fix (or introduce a failing
  assertion) to confirm the job actually goes red — don't just trust the
  code, observe a real failing run before considering this done.
