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
