# Scheduled/Manual Jepsen CI Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a GitHub Actions workflow that runs the ArcadeDB Jepsen suite against the latest `main`-branch build of ArcadeDB, once a day on a schedule and on demand via manual dispatch.

**Architecture:** A new script (`fetch-arcadedb-image.sh`) pulls `arcadedata/arcadedb:latest` (which tracks ArcadeDB's `main` branch) and repackages its contents as `dist/arcadedb.tar.gz`, reusing the project's existing `--local-dist` install path unmodified. A new wrapper (`docker/ci-run-suite.sh`) runs any of the four `run-*-tests.sh` sweep scripts and turns their existing "FINAL RESULTS" text summary into a process exit code, without editing those scripts (one of them is marked "do not modify" in `CLAUDE.md`). A new workflow (`.github/workflows/jepsen.yml`) wires these together: fetch binaries → bring up the Docker cluster → run the selected suite(s) via the wrapper → upload the Jepsen `store/` artifact → tear down.

**Tech Stack:** Bash, GitHub Actions (YAML), Docker/Docker Compose. No changes to the Clojure codebase.

## Global Constraints

- `run-all-tests.sh`, `run-follower-tests.sh`, `run-lazyfs-tests.sh`, `run-ha-convergence-tests.sh` must remain byte-for-byte unmodified — `CLAUDE.md` marks `run-all-tests.sh` "Do not modify: used by the reported 34/34 pass."
- No changes to `src/arcadedb_jepsen/db.clj`, `docker/Dockerfile.node`, or `docker/docker-compose.yml` — the design deliberately reuses the existing `--local-dist` mechanism as-is.
- The suite now targets ArcadeDB's `main` branch (not `apache-ratis`, which is stale in the docs and has already landed on `main`).
- No Slack/webhook/issue-filing notifications, no self-hosted runner, no Maven-snapshot fetch path — all explicitly out of scope per the spec.

---

### Task 1: `fetch-arcadedb-image.sh` — repackage the ArcadeDB Docker image as a local-dist tarball

**Files:**
- Create: `fetch-arcadedb-image.sh`

**Interfaces:**
- Produces: an executable script, invoked as `./fetch-arcadedb-image.sh [image]` (image defaults to `arcadedata/arcadedb:latest`), which writes `dist/arcadedb.tar.gz` in the repo root — the exact file path `build-local.sh` also produces, consumed by `db.clj`'s `install-from-local!` via the `--local-dist` flag and the `../dist:/jepsen/dist:ro` compose mount.

This task has already been manually dry-run this session (`docker create` + `docker cp arcadedb:/home/arcadedb/.` + `tar czf ... arcadedb` + `tar xzf --strip-components=1`) and confirmed the resulting tarball extracts to exactly `bin/`, `lib/`, `config/`, `databases/`, `log/`, `backups/`, `replication/` at its top level — matching what `install-from-local!` expects at `/opt/arcadedb`. This task just captures that into a committed, tested script.

- [ ] **Step 1: Write the script**

```bash
#!/bin/bash
# Pulls an ArcadeDB Docker image and repackages its contents as dist/arcadedb.tar.gz,
# in the same layout build-local.sh produces, for use with --local-dist. Used by CI
# to test against the latest ArcadeDB main-branch build without a source checkout.
#
# Usage:
#   ./fetch-arcadedb-image.sh                       # uses arcadedata/arcadedb:latest
#   ./fetch-arcadedb-image.sh arcadedata/arcadedb:25.3.1

set -e

IMAGE="${1:-arcadedata/arcadedb:latest}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKDIR="$(mktemp -d)"

cleanup() {
  [ -n "${CID:-}" ] && docker rm -f "$CID" >/dev/null 2>&1
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

echo "Pulling $IMAGE..."
docker pull "$IMAGE"

CID=$(docker create "$IMAGE")
docker cp "$CID:/home/arcadedb/." "$WORKDIR/arcadedb"

mkdir -p "$SCRIPT_DIR/dist"
tar czf "$SCRIPT_DIR/dist/arcadedb.tar.gz" -C "$WORKDIR" arcadedb

echo "Distribution repackaged to dist/arcadedb.tar.gz ($(du -h "$SCRIPT_DIR/dist/arcadedb.tar.gz" | awk '{print $1}'))"
echo ""
echo "To run the Jepsen test against this build:"
echo "  cd docker && docker compose down -v && docker compose up -d"
echo "  docker exec jepsen-control sh /jepsen/docker/setup-ssh.sh"
echo "  docker exec jepsen-control sh -c 'cd /jepsen && lein run test --local-dist --workload bank --nemesis all --node n1 --node n2 --node n3 --node n4 --node n5 --username root --password root'"
```

- [ ] **Step 2: Make it executable**

Run: `chmod +x fetch-arcadedb-image.sh`

- [ ] **Step 3: Run it and verify the tarball is produced correctly**

Run:
```bash
./fetch-arcadedb-image.sh
ls -la dist/arcadedb.tar.gz
tar tzf dist/arcadedb.tar.gz | head -10
```

Expected: `dist/arcadedb.tar.gz` exists (several hundred MB), and every listed entry is prefixed `arcadedb/` (e.g. `arcadedb/bin/`, `arcadedb/lib/`, `arcadedb/config/`).

- [ ] **Step 4: Verify it's compatible with `install-from-local!`'s `--strip-components=1` extraction**

Run:
```bash
mkdir -p /tmp/arcadedb-fetch-test
tar xzf dist/arcadedb.tar.gz -C /tmp/arcadedb-fetch-test --strip-components=1
ls /tmp/arcadedb-fetch-test
rm -rf /tmp/arcadedb-fetch-test
```

Expected: the `ls` output shows `bin`, `lib`, `config`, `databases`, `log`, `backups`, `replication`, `README.md`, `LICENSE`, `NOTICE`, `ATTRIBUTIONS.md` — i.e. the distribution's contents directly, not wrapped in an extra directory. This is the exact layout `db.clj`'s `install-from-local!` (which runs this same `tar xzf ... --strip-components=1` against `/jepsen/dist/arcadedb.tar.gz`) expects at `/opt/arcadedb`.

- [ ] **Step 5: Remove the test tarball before committing (it's gitignored but no need to leave a 470MB+ file on disk)**

`dist/` is already in `.gitignore`, so nothing to unstage — just confirm:

Run: `git status --short`
Expected: no `dist/` entries shown (already ignored), and `fetch-arcadedb-image.sh` shown as untracked.

- [ ] **Step 6: Commit**

```bash
git add fetch-arcadedb-image.sh
git commit -m "Add fetch-arcadedb-image.sh to repackage the arcadedb Docker image as dist/arcadedb.tar.gz"
```

---

### Task 2: `docker/ci-run-suite.sh` — turn a sweep script's summary line into an exit code

**Files:**
- Create: `docker/ci-run-suite.sh`
- Test: `docker/ci-run-suite.test.sh`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: an executable script invoked as `docker/ci-run-suite.sh <path-to-sweep-script> [args...]` (e.g. `docker/ci-run-suite.sh ./run-all-tests.sh 90`). Runs the given script with the given args, echoes its full stdout/stderr, and exits `0` only if the script's output contains a line matching `FINAL RESULTS (N passed, M failed/unknown)` with `M == 0`; exits `1` if `M > 0` or if no such line is found at all. Task 3's workflow calls this directly.

- [ ] **Step 1: Write the failing test**

Create `docker/ci-run-suite.test.sh`:

```bash
#!/bin/bash
# Test harness for ci-run-suite.sh. Builds small fixture "sweep" scripts that
# mimic the FINAL RESULTS output format the four run-*-tests.sh scripts print,
# then asserts ci-run-suite.sh's exit code reacts correctly to each case.
#
# Usage: ./docker/ci-run-suite.test.sh

set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CI_RUN_SUITE="$SCRIPT_DIR/ci-run-suite.sh"
FIXTURE_DIR="$(mktemp -d)"
FAILED=0

cleanup() { rm -rf "$FIXTURE_DIR"; }
trap cleanup EXIT

assert_exit() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL: $desc (expected exit $expected, got $actual)"
    FAILED=1
  else
    echo "PASS: $desc"
  fi
}

# Fixture 1: all tests passed
cat > "$FIXTURE_DIR/all-pass.sh" <<'EOF'
#!/bin/bash
echo "TEST 1/3: workload=bank nemesis=none"
echo "Result: PASS"
echo "FINAL RESULTS (3 passed, 0 failed/unknown)"
EOF
chmod +x "$FIXTURE_DIR/all-pass.sh"
"$CI_RUN_SUITE" "$FIXTURE_DIR/all-pass.sh" >/dev/null 2>&1
assert_exit "all-pass sweep exits 0" 0 $?

# Fixture 2: one test failed
cat > "$FIXTURE_DIR/some-failed.sh" <<'EOF'
#!/bin/bash
echo "TEST 1/3: workload=bank nemesis=partition"
echo "Result: FAIL"
echo "FINAL RESULTS (2 passed, 1 failed/unknown)"
EOF
chmod +x "$FIXTURE_DIR/some-failed.sh"
"$CI_RUN_SUITE" "$FIXTURE_DIR/some-failed.sh" >/dev/null 2>&1
assert_exit "some-failed sweep exits 1" 1 $?

# Fixture 3: script crashed before printing any summary
cat > "$FIXTURE_DIR/no-summary.sh" <<'EOF'
#!/bin/bash
echo "crashed before printing a summary"
exit 0
EOF
chmod +x "$FIXTURE_DIR/no-summary.sh"
"$CI_RUN_SUITE" "$FIXTURE_DIR/no-summary.sh" >/dev/null 2>&1
assert_exit "missing-summary sweep exits 1" 1 $?

# Fixture 4: double-digit counts parse correctly (not just single digits)
cat > "$FIXTURE_DIR/double-digit.sh" <<'EOF'
#!/bin/bash
echo "FINAL RESULTS (34 passed, 0 failed/unknown)"
EOF
chmod +x "$FIXTURE_DIR/double-digit.sh"
"$CI_RUN_SUITE" "$FIXTURE_DIR/double-digit.sh" >/dev/null 2>&1
assert_exit "double-digit all-pass sweep exits 0" 0 $?

if [ "$FAILED" -ne 0 ]; then
  echo "ci-run-suite.sh tests: FAILED"
  exit 1
fi
echo "ci-run-suite.sh tests: ALL PASSED"
```

- [ ] **Step 2: Make the test executable and run it to verify it fails**

Run:
```bash
chmod +x docker/ci-run-suite.test.sh
./docker/ci-run-suite.test.sh
```

Expected: FAIL — `docker/ci-run-suite.sh` doesn't exist yet, so `"$CI_RUN_SUITE" ...` errors out (`No such file or directory`) on the first assertion, and the script likely reports `FAIL: all-pass sweep exits 0 (expected exit 0, got 127)` or similar (exit 127 = command not found), ending with `ci-run-suite.sh tests: FAILED`.

- [ ] **Step 3: Write the implementation**

Create `docker/ci-run-suite.sh`:

```bash
#!/bin/bash
# CI-only wrapper: runs one of the run-*-tests.sh sweep scripts (path given by
# the caller), parses its "FINAL RESULTS (N passed, M failed/unknown)" summary
# line, and exits non-zero if any test failed or no summary was printed at all.
# Never modifies the underlying scripts -- run-all-tests.sh in particular is
# marked "do not modify" in CLAUDE.md since it's used for the reported 34/34 pass.
#
# Usage: docker/ci-run-suite.sh <path-to-sweep-script> [args...]
#   e.g. docker/ci-run-suite.sh ./run-all-tests.sh 90

set -uo pipefail

SCRIPT="$1"
shift

OUTPUT=$("$SCRIPT" "$@" 2>&1)
echo "$OUTPUT"

SUMMARY=$(echo "$OUTPUT" | grep -o 'FINAL RESULTS ([0-9]\+ passed, [0-9]\+ failed/unknown)' | tail -1)
if [ -z "$SUMMARY" ]; then
  echo "ci-run-suite.sh: no FINAL RESULTS summary found in $SCRIPT output -- treating as failure"
  exit 1
fi

FAILED_COUNT=$(echo "$SUMMARY" | grep -o '[0-9]\+ failed/unknown' | grep -o '^[0-9]\+')
if [ "$FAILED_COUNT" -gt 0 ]; then
  echo "ci-run-suite.sh: $SCRIPT reported $FAILED_COUNT failed/unknown test(s)"
  exit 1
fi

exit 0
```

- [ ] **Step 4: Make it executable and run the test to verify it passes**

Run:
```bash
chmod +x docker/ci-run-suite.sh
./docker/ci-run-suite.test.sh
```

Expected:
```
PASS: all-pass sweep exits 0
PASS: some-failed sweep exits 1
PASS: missing-summary sweep exits 1
PASS: double-digit all-pass sweep exits 0
ci-run-suite.sh tests: ALL PASSED
```

- [ ] **Step 5: Sanity-check it against a real sweep script with a near-instant run**

Run:
```bash
docker/ci-run-suite.sh ./run-all-tests.sh 2>&1 | tail -5
```

This will attempt to actually run the full baseline sweep (requires the Docker cluster to be up — it isn't yet at this point in the plan, so this is expected to fail fast with connection errors from the very first `docker exec jepsen-control ...` call inside `run-all-tests.sh`, not hang). Expected: the wrapper prints `run-all-tests.sh`'s output (including Docker errors), then `ci-run-suite.sh: no FINAL RESULTS summary found in ./run-all-tests.sh output -- treating as failure`, and exits 1. This isn't a pass/fail assertion — it's confirming the wrapper handles a real script's early-exit/no-summary case gracefully rather than hanging. Press Ctrl-C if it doesn't return within ~30 seconds and inspect why (e.g. a missing `jepsen-control` container should fail fast, not hang).

- [ ] **Step 6: Commit**

```bash
git add docker/ci-run-suite.sh docker/ci-run-suite.test.sh
git commit -m "Add ci-run-suite.sh wrapper to turn sweep-script summaries into exit codes"
```

---

### Task 3: `.github/workflows/jepsen.yml` — the scheduled/manual workflow

**Files:**
- Create: `.github/workflows/jepsen.yml`

**Interfaces:**
- Consumes: `fetch-arcadedb-image.sh` (Task 1) and `docker/ci-run-suite.sh` (Task 2), both at the repo root / `docker/` respectively, both executable.
- Produces: a GitHub Actions workflow triggerable by `schedule` or `workflow_dispatch`, with inputs `suite` (`all` | `baseline` | `follower` | `lazyfs` | `ha-convergence`) and `time-limit` (seconds, string).

- [ ] **Step 1: Write the workflow file**

Create `.github/workflows/jepsen.yml`:

```yaml
name: Jepsen

on:
  schedule:
    - cron: '0 3 * * *'
  workflow_dispatch:
    inputs:
      suite:
        description: 'Which suite to run'
        required: true
        default: 'all'
        type: choice
        options:
          - all
          - baseline
          - follower
          - lazyfs
          - ha-convergence
      time-limit:
        description: 'Time limit per test, in seconds'
        required: true
        default: '90'

jobs:
  jepsen:
    runs-on: ubuntu-latest
    timeout-minutes: 360
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Fetch latest ArcadeDB build (main branch, via Docker image)
        run: ./fetch-arcadedb-image.sh

      - name: Build and start the Docker cluster
        working-directory: docker
        run: |
          docker compose build
          docker compose up -d

      - name: Set up SSH between control and nodes
        run: docker exec jepsen-control sh /jepsen/docker/setup-ssh.sh

      - name: Run Jepsen suite
        id: run_suite
        shell: bash
        run: |
          set +e
          SUITE="${{ github.event.inputs.suite || 'all' }}"
          TIME_LIMIT="${{ github.event.inputs.time-limit || '90' }}"
          OVERALL=0
          LOG=jepsen-run.log
          : > "$LOG"

          run_one() {
            docker/ci-run-suite.sh "./$1" "$TIME_LIMIT" 2>&1 | tee -a "$LOG"
            return "${PIPESTATUS[0]}"
          }

          case "$SUITE" in
            baseline)
              run_one run-all-tests.sh || OVERALL=1
              ;;
            follower)
              run_one run-follower-tests.sh || OVERALL=1
              ;;
            lazyfs)
              run_one run-lazyfs-tests.sh || OVERALL=1
              ;;
            ha-convergence)
              run_one run-ha-convergence-tests.sh || OVERALL=1
              ;;
            all)
              run_one run-all-tests.sh || OVERALL=1
              run_one run-follower-tests.sh || OVERALL=1
              run_one run-lazyfs-tests.sh || OVERALL=1
              run_one run-ha-convergence-tests.sh || OVERALL=1
              ;;
            *)
              echo "Unknown suite: $SUITE"
              OVERALL=1
              ;;
          esac

          {
            echo "## Jepsen suite: $SUITE"
            echo '```'
            grep -E 'FINAL RESULTS|^  \[(PASS|FAIL|UNKNOWN)\]' "$LOG"
            echo '```'
          } >> "$GITHUB_STEP_SUMMARY"

          exit $OVERALL

      - name: Collect Jepsen store
        if: always()
        run: docker cp jepsen-control:/jepsen/store ./store || true

      - name: Upload Jepsen store artifact
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: jepsen-store-${{ github.run_id }}
          path: store
          if-no-files-found: warn
          retention-days: 14

      - name: Tear down the Docker cluster
        if: always()
        working-directory: docker
        run: docker compose down -v
```

The `Run Jepsen suite` step uses `shell: bash` explicitly (rather than GitHub Actions' default `sh`) so `PIPESTATUS` is available — needed to get `ci-run-suite.sh`'s real exit code through the `tee` pipe (without it, `run_one`'s return value would reflect `tee`'s exit code, which is always 0).

- [ ] **Step 2: Validate the YAML is well-formed**

Run:
```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/jepsen.yml')); print('YAML OK')"
```

Expected: `YAML OK`. If `python3`/`pyyaml` isn't available, use `ruby -ryaml -e "YAML.load_file('.github/workflows/jepsen.yml'); puts 'YAML OK'"` instead — either just needs to confirm the file parses as valid YAML, not validate GitHub Actions' schema.

- [ ] **Step 3: Cross-check against the design spec's required behavior**

Re-open `docs/superpowers/specs/2026-07-03-jepsen-ci-workflow-design.md` §2 and confirm each bullet has a corresponding step in the workflow file:
- [ ] Checkout — yes (Step 1)
- [ ] Fetch ArcadeDB image → `dist/arcadedb.tar.gz` — yes (`fetch-arcadedb-image.sh`)
- [ ] `docker compose build && up -d` — yes
- [ ] `setup-ssh.sh` — yes
- [ ] Run selected suite(s) via `ci-run-suite.sh`, `all` running all four without early-exit — yes (`run_one` + `|| OVERALL=1` per call)
- [ ] Collect + upload `store/` always, even on failure — yes (`if: always()` on both steps)
- [ ] PASS/FAIL summary in `$GITHUB_STEP_SUMMARY` — yes
- [ ] Teardown always — yes (`if: always()`)
- [ ] Job fails if any suite failed — yes (`exit $OVERALL`)

This can't be fully verified without a real GitHub Actions run (out of scope for local execution) — note in the commit message / PR description that a `workflow_dispatch` run with `suite=baseline` and a short `time-limit` should be triggered manually after merge to confirm end-to-end, per the spec's testing plan.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/jepsen.yml
git commit -m "Add scheduled/manual Jepsen CI workflow"
```

---

### Task 4: Update `CLAUDE.md` and `README.md`

**Files:**
- Modify: `CLAUDE.md:16`, `CLAUDE.md:91-100` (Batch Runners section)
- Modify: `README.md:9`, `README.md:177-187` (Quick Start §1), `README.md:243-254` (Batch Scripts table), `README.md:267` (Running Against a Released Version note)

**Interfaces:**
- None — documentation only, no code interfaces.

- [ ] **Step 1: Update `CLAUDE.md`'s prerequisite line**

In `CLAUDE.md`, change:
```markdown
- A local ArcadeDB build (from the `apache-ratis` branch)
```
to:
```markdown
- A local ArcadeDB build (from `main`) — or none at all if using the CI workflow, which fetches `arcadedata/arcadedb:latest` automatically
```

- [ ] **Step 2: Add the new scripts to `CLAUDE.md`'s Project Structure listing**

In `CLAUDE.md`, in the `## Project Structure` code block, after the `build-local.sh` line (find the line listing `run-ha-convergence-tests.sh - 6-test replica-health sweep...`), add two new lines directly after it:
```
run-ha-convergence-tests.sh - 6-test replica-health sweep (ha-convergence × 6 nemeses)
fetch-arcadedb-image.sh  - Repackages arcadedata/arcadedb:latest as dist/arcadedb.tar.gz (for CI)
docker/ci-run-suite.sh   - CI-only wrapper: exits non-zero if a run-*-tests.sh sweep reports failures
```
(only the first line already exists — insert the two new ones after it, matching the existing indentation/column style of that block.)

- [ ] **Step 3: Update `CLAUDE.md`'s Batch Runners section**

In `CLAUDE.md`, after the existing four bullet points under `## Batch Runners` (ending with the `run-ha-convergence-tests.sh` bullet and the `Combined baseline: **44/44 PASS**` line), add:

```markdown
- `./fetch-arcadedb-image.sh [image]` — pulls an ArcadeDB Docker image (default `arcadedata/arcadedb:latest`, which tracks `main`) and repackages it as `dist/arcadedb.tar.gz`, for use with `--local-dist`. Used by the scheduled CI workflow; equally usable locally as an alternative to `build-local.sh`.
- `docker/ci-run-suite.sh <script> [args...]` — CI-only wrapper that runs a `run-*-tests.sh` sweep and turns its `FINAL RESULTS` summary line into a process exit code, without modifying the sweep scripts themselves. Used by `.github/workflows/jepsen.yml`; not needed for local interactive use.

A GitHub Actions workflow (`.github/workflows/jepsen.yml`) runs the full sweep (or a chosen subset) daily at 03:00 UTC against `arcadedata/arcadedb:latest`, and on demand via `workflow_dispatch`.
```

- [ ] **Step 4: Update `README.md`'s "Tested against" line**

In `README.md`, change:
```markdown
Tested against the `apache-ratis` branch, 5-node cluster, 90-second runs, `--read-consistency read_your_writes` (default).
```
to:
```markdown
Tested against ArcadeDB's `main` branch, 5-node cluster, 90-second runs, `--read-consistency read_your_writes` (default).
```

- [ ] **Step 5: Update `README.md`'s Quick Start build step**

In `README.md`, the section currently reads (heading through closing code fence):

> `### 1. Build ArcadeDB`
> `Build ArcadeDB from the `apache-ratis` branch and copy the distribution:`
> then a ```bash fenced block with the two `build-local.sh` options.

Replace the heading and its introductory sentence:

Change:
```
### 1. Build ArcadeDB

Build ArcadeDB from the `apache-ratis` branch and copy the distribution:
```
to:
```
### 1. Build (or Fetch) ArcadeDB

Either build ArcadeDB from `main` and copy the distribution, or fetch a
prebuilt copy from ArcadeDB's Docker image -- no local Maven build required:
```

Then, inside the existing ` ```bash ` fenced block right after it (which
currently has "Option A" and "Option B"), add a third option after
`./build-local.sh /path/to/arcadedb --skip-build`:
```
# Option C: Fetch a prebuilt copy from arcadedata/arcadedb:latest (tracks main)
./fetch-arcadedb-image.sh
```

- [ ] **Step 6: Update `README.md`'s Batch Scripts section**

In `README.md`, in the `## Batch Scripts` table, after the `run-ha-convergence-tests.sh` row, the table and its trailing note stay as-is (they describe the sweep scripts, which are unchanged). Instead, add a new subsection immediately after that table (before `## Running Against a Released Version`):

```markdown
## Continuous Integration

`.github/workflows/jepsen.yml` runs the full sweep automatically:
- **Scheduled**: daily at 03:00 UTC, running all four batch scripts (`suite=all`, ~50 tests total) against `arcadedata/arcadedb:latest` (tracks `main`).
- **Manual**: trigger via the Actions tab (`workflow_dispatch`), choosing `suite` (`all`, `baseline`, `follower`, `lazyfs`, `ha-convergence`) and a `time-limit`.

Results are uploaded as a `jepsen-store-<run-id>` artifact (the `store/`
directory) and summarized in the run's step summary.
```

- [ ] **Step 7: Update `README.md`'s released-version note**

In `README.md`, change:
```markdown
Note: released versions before the `apache-ratis` branch do not have Ratis HA, so HA-specific tests won't apply.
```
to:
```markdown
Note: released versions before Ratis HA landed on `main` do not have HA support, so HA-specific tests won't apply.
```

- [ ] **Step 8: Verify no stale references remain**

Run:
```bash
grep -rn "apache-ratis" README.md CLAUDE.md
```

Expected: no output (empty) — all references updated.

- [ ] **Step 9: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "Update docs: suite targets main (not apache-ratis), document CI workflow and new scripts"
```

---

## Final Verification

- [ ] Run `grep -rn "apache-ratis" .` from the repo root (excluding `docs/superpowers/specs/`, which is a dated historical record) and confirm no stale references remain outside the spec.
- [ ] Run `./docker/ci-run-suite.test.sh` one more time to confirm it still passes after all edits.
- [ ] Run `git log --oneline -6` and confirm four commits landed (Tasks 1, 2, 3, 4) plus the prior spec commits.
- [ ] After merging, manually trigger `.github/workflows/jepsen.yml` via `workflow_dispatch` with `suite=baseline` and a short `time-limit` (e.g. `30`) to confirm the end-to-end pipeline works in real GitHub Actions — this can't be validated from local execution alone. This is a manual follow-up action for the repo owner, not part of this plan's automated steps.
