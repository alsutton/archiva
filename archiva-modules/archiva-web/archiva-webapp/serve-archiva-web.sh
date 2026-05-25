#!/usr/bin/env bash
# Launches the Angular dev server for the archiva-web frontend.
# Invoked via `bazel run //archiva-modules/archiva-web/archiva-webapp:serve`.
set -euo pipefail

if [[ -z "${BUILD_WORKSPACE_DIRECTORY:-}" ]]; then
  echo "error: this script must be run with 'bazel run' (BUILD_WORKSPACE_DIRECTORY is unset)" >&2
  exit 1
fi

APP_DIR="${BUILD_WORKSPACE_DIRECTORY}/archiva-modules/archiva-web/archiva-webapp/src/main/archiva-web"

if [[ ! -f "${APP_DIR}/package.json" ]]; then
  echo "error: package.json not found at ${APP_DIR}" >&2
  exit 1
fi

cd "${APP_DIR}"

if [[ ! -d node_modules ]]; then
  echo "node_modules missing; running 'npm install'..."
  npm install
fi

exec npm start -- "$@"
