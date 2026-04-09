#!/bin/bash
# Builds ArcadeDB from a local source tree and copies the distribution tarball
# into dist/ for use with --local-dist in the Jepsen tests.
#
# Usage:
#   ./build-local.sh /path/to/arcadedb          # Build and copy
#   ./build-local.sh /path/to/arcadedb --skip-build  # Just copy existing build

set -e

ARCADEDB_DIR="${1:?Usage: $0 /path/to/arcadedb [--skip-build]}"
SKIP_BUILD="${2:-}"

if [ ! -f "$ARCADEDB_DIR/pom.xml" ]; then
  echo "ERROR: $ARCADEDB_DIR does not look like an ArcadeDB source tree"
  exit 1
fi

VERSION=$(mvn -f "$ARCADEDB_DIR/pom.xml" help:evaluate -Dexpression=project.version -q -DforceStdout)
echo "ArcadeDB version: $VERSION"

if [ "$SKIP_BUILD" != "--skip-build" ]; then
  echo "Building ArcadeDB (mvn clean install -DskipTests)..."
  cd "$ARCADEDB_DIR" && mvn clean install -DskipTests -q
  echo "Build complete."
fi

TARBALL="$ARCADEDB_DIR/package/target/arcadedb-${VERSION}.tar.gz"
if [ ! -f "$TARBALL" ]; then
  echo "ERROR: Distribution tarball not found at $TARBALL"
  echo "Available files:"
  ls "$ARCADEDB_DIR/package/target/"arcadedb-*.tar.gz 2>/dev/null || echo "  (none)"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$SCRIPT_DIR/dist"
cp "$TARBALL" "$SCRIPT_DIR/dist/arcadedb.tar.gz"

echo "Distribution copied to dist/arcadedb.tar.gz ($(du -h "$SCRIPT_DIR/dist/arcadedb.tar.gz" | awk '{print $1}'))"
echo ""
echo "To run the Jepsen test against this build:"
echo "  cd docker && docker compose down -v && docker compose up -d"
echo "  docker exec jepsen-control sh /jepsen/docker/setup-ssh.sh"
echo "  docker exec jepsen-control sh -c 'cd /jepsen && lein run test --local-dist --workload bank --nemesis all --node n1 --node n2 --node n3 --node n4 --node n5 --username root --password root'"
