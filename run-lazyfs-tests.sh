#!/bin/bash
# Exercises the LazyFS power-loss path: each node's data and Ratis log dirs are
# mounted on a FUSE filesystem that buffers writes in memory until fsync(). The
# nemesis fires `lazyfs::clear-cache` (drops unfsync'd pages) + SIGKILL, then
# restarts the node. Models a power outage on a node that was mid-write.
#
# Requires `-Darcadedb.server.mode=production` (set automatically by db.clj
# when :lazyfs? is true) — without production mode ArcadeDB doesn't fsync, so
# the test would lose data on every cycle and prove nothing.
#
# The nemesis enforces a safety cap: at most ⌊(n-1)/2⌋ nodes power-killed
# concurrently. With n=5 that's max 2 — losing 3 simultaneously would put the
# cluster outside Raft's failure model.
#
# Usage: ./run-lazyfs-tests.sh [time-limit]
#   time-limit: test duration in seconds (default: 90)

TIME_LIMIT="${1:-90}"
WORKLOADS=(bank set elle register register-follower)
NEMESES=(lazyfs all+lazyfs)
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

    # Linearizability + lots of process restarts is hard on Knossos; keep the
    # combined runs short.
    if [[ "$NEMESIS" == "all+lazyfs" ]]; then
      TL=30
    else
      TL=$TIME_LIMIT
    fi

    # register-follower implies linearizable reads from a non-leader.
    READ_CONSISTENCY=""
    if [[ "$WORKLOAD" == register-follower ]]; then
      READ_CONSISTENCY="--read-consistency linearizable"
    fi

    echo ""
    echo "======================================================"
    echo "TEST $count/$total: workload=$WORKLOAD nemesis=$NEMESIS (${TL}s)"
    echo "Started at: $(date)"
    echo "======================================================"

    CMD="cd /jepsen && lein run test $COMMON --time-limit $TL $NODES --workload $WORKLOAD --nemesis $NEMESIS $READ_CONSISTENCY"
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
