#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/build/desktopQt"

cmake -S "$ROOT_DIR/desktopQt" -B "$BUILD_DIR"
cmake --build "$BUILD_DIR" --target NuvioQtDesktop --parallel

if [[ "$(uname -s)" == "Darwin" ]]; then
    "$BUILD_DIR/Nuvio.app/Contents/MacOS/Nuvio"
else
    "$BUILD_DIR/Nuvio"
fi
