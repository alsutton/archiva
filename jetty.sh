#!/bin/sh
# Build the Bazel jetty distribution, unpack it under .bazel-archiva-run/,
# and launch bin/archiva. All extra args are forwarded to the launcher.
set -eu

REPO=$(cd "$(dirname "$0")" && pwd)
RUN_DIR="$REPO/.bazel-archiva-run"
DIST="$REPO/bazel-bin/archiva-jetty/archiva-jetty-dist.tar.gz"

bazel build //archiva-jetty:archiva-jetty-dist
rm -rf "$RUN_DIR"
mkdir -p "$RUN_DIR"
tar -xzf "$DIST" -C "$RUN_DIR"
exec "$RUN_DIR/archiva/bin/archiva" "$@"
