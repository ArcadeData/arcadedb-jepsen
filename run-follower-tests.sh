#!/bin/bash
# Exercises the follower read-consistency paths that run-all-tests.sh does NOT cover:
#   - register-follower: reads from a non-leader node with LINEARIZABLE + no bookmark
#                        (triggers ReadIndex RPC to the leader)
#   - register-bookmark: reads from a follower with LINEARIZABLE + X-ArcadeDB-Read-After
#                        (triggers local-apply wait)
# Also runs them under the new :clock nemesis (date-based skew) in addition to the
# standard partition/kill/pause set.
#
# Usage: ./run-follower-tests.sh [time-limit]
#   time-limit: test duration in seconds (default: 90)

TIME_LIMIT="${1:-90}"
WORKLOADS=(register-follower register-bookmark)
NEMESES=(none partition kill pause clock all all+clock)
NODES="--node n1 --node n2 --node n3 --node n4 --node n5"
COMMON="--local-dist --username root --password root"
PASS=0
FAIL=0
RESULTS=()

total=0
for w in "${WORKLOADS[@]}"; do
  for n in "${NEMESES[@]}"; do
    total=$((total + 1))
  done
done

count=0
for WORKLOAD in "${WORKLOADS[@]}"; do
  for NEMESIS in "${NEMESES[@]}"; do
    count=$((count + 1))

    # Short partition runs for linearizability checkers to keep Knossos tractable.
    if [[ "$NEMESIS" == "partition" || "$NEMESIS" == "all"* ]]; then
      TL=30
    else
      TL=$TIME_LIMIT
    fi

    echo ""
    echo "======================================================"
    echo "TEST $count/$total: workload=$WORKLOAD nemesis=$NEMESIS (${TL}s)"
    echo "Started at: $(date)"
    echo "======================================================"

    CMD="cd /jepsen && lein run test $COMMON --time-limit $TL $NODES --workload $WORKLOAD --nemesis $NEMESIS --read-consistency linearizable"
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

    RESULTS+=("[$STATUS] workload=$WORKLOAD nemesis=$NEMESIS")
    echo ""
    echo "Result: $STATUS"
    echo "Finished at: $(date)"
  done
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
