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
