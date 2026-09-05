#!/bin/sh
# Same as jetty.sh, but starts the JVM in suspended JDWP mode listening on
# port 8000 for an attaching debugger.
set -eu

REPO=$(cd "$(dirname "$0")" && pwd)
RUN_DIR="$REPO/.bazel-archiva-run"
DIST="$REPO/bazel-bin/archiva-jetty/archiva-jetty-dist.tar.gz"

bazel build //archiva-jetty:archiva-jetty-dist
rm -rf "$RUN_DIR"
mkdir -p "$RUN_DIR"
tar -xzf "$DIST" -C "$RUN_DIR"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8000"
exec "$RUN_DIR/archiva/bin/archiva" "$@"
