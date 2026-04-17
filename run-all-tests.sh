#!/bin/bash
# Full Jepsen test sweep:
#   - Leader block:   4 workloads (bank, set, elle, register)
#                     x 5 nemeses (none, partition, kill, pause, all)             = 20 tests
#   - Follower block: 2 workloads (register-follower, register-bookmark)
#                     x 7 nemeses (none, partition, kill, pause, clock, all, all+clock) = 14 tests
# Total: 34 tests. The follower block forces --read-consistency linearizable so the follower
# ReadIndex / bookmark paths are exercised.
#
# Usage: ./run-all-tests.sh [time-limit]
#   time-limit: test duration in seconds (default: 90)
# Note: register/partition and all follower partition/all variants use 30s to avoid
# Knossos analysis explosion on indeterminate operations.

TIME_LIMIT="${1:-90}"
NODES="--node n1 --node n2 --node n3 --node n4 --node n5"
COMMON="--local-dist --username root --password root"
PASS=0
FAIL=0
RESULTS=()

# Matrix: each row is "workload nemesis extra-flags time-override"
# time-override is either empty (use $TIME_LIMIT) or a number in seconds.
MATRIX=(
  # -- Leader block --
  "bank none - -"
  "bank partition - -"
  "bank kill - -"
  "bank pause - -"
  "bank all - -"
  "set none - -"
  "set partition - -"
  "set kill - -"
  "set pause - -"
  "set all - -"
  "elle none - -"
  "elle partition - -"
  "elle kill - -"
  "elle pause - -"
  "elle all - -"
  "register none - -"
  "register partition - 30"
  "register kill - -"
  "register pause - -"
  "register all - -"

  # -- Follower block (reads routed to a non-leader with LINEARIZABLE consistency) --
  "register-follower none --read-consistency=linearizable -"
  "register-follower partition --read-consistency=linearizable 30"
  "register-follower kill --read-consistency=linearizable -"
  "register-follower pause --read-consistency=linearizable -"
  "register-follower clock --read-consistency=linearizable -"
  "register-follower all --read-consistency=linearizable 30"
  "register-follower all+clock --read-consistency=linearizable 30"
  "register-bookmark none --read-consistency=linearizable -"
  "register-bookmark partition --read-consistency=linearizable 30"
  "register-bookmark kill --read-consistency=linearizable -"
  "register-bookmark pause --read-consistency=linearizable -"
  "register-bookmark clock --read-consistency=linearizable -"
  "register-bookmark all --read-consistency=linearizable 30"
  "register-bookmark all+clock --read-consistency=linearizable 30"
)

total=${#MATRIX[@]}
count=0

for ROW in "${MATRIX[@]}"; do
  read -r WORKLOAD NEMESIS EXTRA TL_OVERRIDE <<< "$ROW"
  count=$((count + 1))

  if [ "$TL_OVERRIDE" != "-" ]; then
    TL=$TL_OVERRIDE
  else
    TL=$TIME_LIMIT
  fi

  EXTRA_FLAGS=""
  if [ "$EXTRA" != "-" ]; then
    # Convert --key=value into --key value so lein run parses it correctly
    EXTRA_FLAGS=$(echo "$EXTRA" | sed 's/=/ /')
  fi

  echo ""
  echo "======================================================"
  echo "TEST $count/$total: workload=$WORKLOAD nemesis=$NEMESIS (${TL}s) $EXTRA_FLAGS"
  echo "Started at: $(date)"
  echo "======================================================"

  CMD="cd /jepsen && lein run test $COMMON --time-limit $TL $NODES --workload $WORKLOAD --nemesis $NEMESIS $EXTRA_FLAGS"
  OUTPUT=$(docker exec jepsen-control sh -c "$CMD" 2>&1)
  EXIT_CODE=$?

  echo "$OUTPUT"

  if echo "$OUTPUT" | grep -q ":valid? true"; then
    STATUS="PASS"
    PASS=$((PASS + 1))
  elif echo "$OUTPUT" | grep -q ":valid? :unknown"; then
    STATUS="UNKNOWN"
    FAIL=$((FAIL + 1))
  else
    STATUS="FAIL"
    FAIL=$((FAIL + 1))
  fi

  RESULTS+=("[$STATUS] workload=$WORKLOAD nemesis=$NEMESIS $EXTRA_FLAGS")
  echo ""
  echo "Result: $STATUS"
  echo "Finished at: $(date)"
done

echo ""
echo "======================================================"
echo "FINAL RESULTS ($PASS passed, $FAIL failed/unknown)"
echo "======================================================"
for r in "${RESULTS[@]}"; do
  echo "  $r"
done
echo ""
echo "All $total tests completed at: $(date)"
