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
    # combined runs short. register-follower is a Knossos (linearizable) workload, and
    # the post-power-loss leaderless window produces many indeterminate ops that blow up
    # the analysis (90s register-follower/lazyfs returned :unknown) -- shorten both of
    # its lazyfs variants to 30s as well.
    if [[ "$NEMESIS" == "all+lazyfs" || "$WORKLOAD" == "register-follower" ]]; then
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

    # Classify on the TOP-LEVEL :valid? — the LAST :valid? token in the results map.
    # A plain `grep ":valid? true"` is unreliable: the output always contains nested
    # `:perf`/`:clock` `:valid? true` entries, so it reports PASS even when the workload
    # failed (or no results were written at all). Empty verdict => crash/UNKNOWN.
    VERDICT=$(echo "$OUTPUT" | grep -oE ':valid\? (true|false|:unknown)' | tail -1)
    if [ "$VERDICT" = ":valid? true" ]; then
      STATUS="PASS"
      PASS=$((PASS + 1))
    elif [ "$VERDICT" = ":valid? :unknown" ] || [ -z "$VERDICT" ]; then
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
