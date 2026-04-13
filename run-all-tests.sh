#!/bin/bash
# Run all 20 Jepsen tests: 4 workloads x 5 nemesis, 90s each

WORKLOADS=(bank set elle register)
NEMESES=(none partition kill pause all)
NODES="--node n1 --node n2 --node n3 --node n4 --node n5"
COMMON="--local-dist --username root --password root --time-limit 90"
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
    echo ""
    echo "======================================================"
    echo "TEST $count/$total: workload=$WORKLOAD nemesis=$NEMESIS"
    echo "Started at: $(date)"
    echo "======================================================"

    CMD="cd /jepsen && lein run test $COMMON $NODES --workload $WORKLOAD --nemesis $NEMESIS"
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
echo "All 20 tests completed at: $(date)"
