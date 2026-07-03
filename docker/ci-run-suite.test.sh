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

cleanup() { [ -n "${FIXTURE_DIR:-}" ] && rm -rf "$FIXTURE_DIR"; }
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
