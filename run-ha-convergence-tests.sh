#!/bin/bash
# HA convergence / replica-health sweep.
#
# Runs the ha-convergence workload across the fault types that stress replica recovery
# and divergence (the leader phase-2 commit divergence #4740 and stalled-replica #4728
# fixes). After each run's faults heal and the cluster settles, the workload reads every
# node directly and asserts liveness (no self-halt), convergence (identical sets on all
# nodes) and completeness (every acked add on every node).
#
# Usage: ./run-ha-convergence-tests.sh [time-limit]
#   time-limit: main-phase duration in seconds (default: 60). Each test then adds a
#   ~25s heal+settle phase before the final per-node read.
#
# all+lazyfs runs in production mode (real fsync) and is shortened, like run-lazyfs-tests.sh.

TIME_LIMIT="${1:-60}"
NODES="--node n1 --node n2 --node n3 --node n4 --node n5"
COMMON="--local-dist --username root --password root --workload ha-convergence"
PASS=0
FAIL=0
RESULTS=()

# nemesis : time-override (- = use $TIME_LIMIT) : extra-flags
MATRIX=(
  "none       -  -"
  "partition  -  -"
  "kill       -  -"
  "pause      -  -"
  "all        -  -"
  "all+lazyfs 30 --production"
)

total=${#MATRIX[@]}
count=0
for ROW in "${MATRIX[@]}"; do
  read -r NEMESIS TL_OVERRIDE EXTRA <<< "$ROW"
  count=$((count + 1))
  [ "$TL_OVERRIDE" != "-" ] && TL=$TL_OVERRIDE || TL=$TIME_LIMIT
  [ "$EXTRA" != "-" ] && EXTRA_FLAGS="$EXTRA" || EXTRA_FLAGS=""

  echo ""
  echo "======================================================"
  echo "TEST $count/$total: workload=ha-convergence nemesis=$NEMESIS (${TL}s) $EXTRA_FLAGS"
  echo "Started at: $(date)"
  echo "======================================================"

  CMD="cd /jepsen && lein run test $COMMON --time-limit $TL $NODES --nemesis $NEMESIS $EXTRA_FLAGS"
  OUTPUT=$(docker exec jepsen-control sh -c "$CMD" 2>&1)
  echo "$OUTPUT"

  # Classify on the TOP-LEVEL :valid? (the LAST :valid? in the results map). A plain
  # grep ":valid? true" is unreliable: nested :perf/:clock entries are always true.
  VERDICT=$(echo "$OUTPUT" | grep -oE ':valid\? (true|false|:unknown)' | tail -1)
  if [ "$VERDICT" = ":valid? true" ]; then
    STATUS="PASS"; PASS=$((PASS + 1))
  elif [ "$VERDICT" = ":valid? :unknown" ] || [ -z "$VERDICT" ]; then
    STATUS="UNKNOWN"; FAIL=$((FAIL + 1))
  else
    STATUS="FAIL"; FAIL=$((FAIL + 1))
  fi

  RESULTS+=("[$STATUS] nemesis=$NEMESIS $EXTRA_FLAGS")
  echo ""
  echo "Result: $STATUS"
  echo "Finished at: $(date)"
done

echo ""
echo "======================================================"
echo "FINAL RESULTS ($PASS passed, $FAIL failed/unknown)"
echo "======================================================"
for r in "${RESULTS[@]}"; do echo "  $r"; done
echo ""
echo "All $total ha-convergence tests completed at: $(date)"
