#!/bin/bash
# Test harness for jepsen-log-to-junit.py. Feeds it a fixture log in the format
# the run-*-tests.sh sweeps print (PASS, FAIL, UNKNOWN, extra flags, and a test
# cut short with no Result line) and asserts the generated JUnit XML.
#
# Usage: ./docker/jepsen-log-to-junit.test.sh

set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONVERTER="$SCRIPT_DIR/jepsen-log-to-junit.py"
FIXTURE_DIR="$(mktemp -d)"
FAILED=0

cleanup() { [ -n "${FIXTURE_DIR:-}" ] && rm -rf "$FIXTURE_DIR"; }
trap cleanup EXIT

assert_xpath() {
  local desc="$1" expr="$2" expected="$3"
  local actual
  actual=$(python3 - "$FIXTURE_DIR/report.xml" "$expr" <<'EOF'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
print(eval(sys.argv[2], {"root": root}))
EOF
)
  if [ "$actual" != "$expected" ]; then
    echo "FAIL: $desc (expected '$expected', got '$actual')"
    FAILED=1
  else
    echo "PASS: $desc"
  fi
}

cat > "$FIXTURE_DIR/sweep.log" <<'EOF'

======================================================
TEST 1/4: workload=bank none (90s)
Started at: Thu Oct 23 03:00:00 UTC 2026
======================================================
INFO jepsen.core - Run complete
 :valid? true}

Result: PASS
Finished at: Thu Oct 23 03:01:40 UTC 2026

======================================================
TEST 2/4: workload=register-follower nemesis=partition (30s) --read-consistency linearizable
Started at: Thu Oct 23 03:01:40 UTC 2026
======================================================
 :valid? false}
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻

Result: FAIL
Finished at: Thu Oct 23 03:02:30 UTC 2026

======================================================
TEST 3/4: workload=set nemesis=kill (90s)
Started at: Thu Oct 23 03:02:30 UTC 2026
======================================================
 :valid? :unknown}

Result: UNKNOWN
Finished at: Thu Oct 23 03:04:00 UTC 2026

======================================================
TEST 4/4: workload=elle nemesis=all (90s)
Started at: Thu Oct 23 03:04:00 UTC 2026
======================================================
lein run crashed half way
EOF

python3 "$CONVERTER" baseline "$FIXTURE_DIR/sweep.log" "$FIXTURE_DIR/report.xml" >/dev/null
assert_xpath "converter wrote 4 testcases" "root.get('tests')" 4
assert_xpath "FAIL and UNKNOWN count as failures" "root.get('failures')" 2
assert_xpath "test with no Result line is an error" "root.get('errors')" 1
assert_xpath "suite name is the given suite" "root.get('name')" baseline
assert_xpath "classname is jepsen.<suite>" "root[0].get('classname')" jepsen.baseline
assert_xpath "extra flags are part of the name" "root[1].get('name')" \
  "workload=register-follower nemesis=partition --read-consistency linearizable"
assert_xpath "duration comes from Started/Finished" "root[0].get('time')" 100.000
assert_xpath "passing test has no failure child" "len(root[0])" 0
assert_xpath "failure message carries the verdict" "root[1].find('failure').get('message')" \
  "Jepsen verdict FAIL: :valid? false}"
assert_xpath "UNKNOWN failure type" "root[2].find('failure').get('type')" UNKNOWN
assert_xpath "failure body includes test output" "'Analysis invalid' in root[1].find('failure').text" True
assert_xpath "incomplete test body includes test output" "'crashed half way' in root[3].find('error').text" True

if [ "$FAILED" -ne 0 ]; then
  echo "jepsen-log-to-junit.py tests: FAILED"
  exit 1
fi
echo "jepsen-log-to-junit.py tests: ALL PASSED"
