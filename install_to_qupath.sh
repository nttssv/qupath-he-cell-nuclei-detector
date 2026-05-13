#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$ROOT/dist/qupath-extension-prototype1.jar"

if [[ ! -f "$JAR" ]]; then
  "$ROOT/build_extension.sh" >/dev/null
fi

if [[ -n "${QUPATH_EXTENSIONS_DIR:-}" ]]; then
  DEST="$QUPATH_EXTENSIONS_DIR"
elif [[ "$(uname -s)" == "Darwin" ]]; then
  DEST="$HOME/Library/Application Support/QuPath/extensions"
else
  DEST="$HOME/.local/share/QuPath/extensions"
fi

mkdir -p "$DEST"
cp "$JAR" "$DEST/"

cat <<MSG
Installed:
  $DEST/qupath-extension-prototype1.jar

Next:
  1. Set PROTOTYPE1_HOME and PROTOTYPE1_PYTHON if needed.
  2. Restart QuPath.
MSG
