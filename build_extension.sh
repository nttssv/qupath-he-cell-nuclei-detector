#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$ROOT/build"
STUB_SRC="$BUILD/stub-src"
STUB_CLASSES="$BUILD/stub-classes"
CLASSES="$BUILD/classes"
DIST="$ROOT/dist"

rm -rf "$BUILD" "$DIST"
mkdir -p "$STUB_SRC/qupath/lib/gui/extensions" "$STUB_SRC/qupath/lib/gui" "$STUB_CLASSES" "$CLASSES" "$DIST"

cat > "$STUB_SRC/qupath/lib/gui/QuPathGUI.java" <<'JAVA'
package qupath.lib.gui;
public class QuPathGUI {}
JAVA

cat > "$STUB_SRC/qupath/lib/gui/extensions/QuPathExtension.java" <<'JAVA'
package qupath.lib.gui.extensions;
import qupath.lib.gui.QuPathGUI;
public interface QuPathExtension {
    void installExtension(QuPathGUI qupath);
    String getName();
    String getDescription();
}
JAVA

STUB_SOURCES=()
while IFS= read -r -d '' file; do
  STUB_SOURCES+=("$file")
done < <(find "$STUB_SRC" -name '*.java' -print0)

JAVA_SOURCES=()
while IFS= read -r -d '' file; do
  JAVA_SOURCES+=("$file")
done < <(find "$ROOT/src/main/java" -name '*.java' -print0)

javac -d "$STUB_CLASSES" "${STUB_SOURCES[@]}"
javac -cp "$STUB_CLASSES" -d "$CLASSES" "${JAVA_SOURCES[@]}"
cp -R "$ROOT/src/main/resources/." "$CLASSES/"

jar --create --file "$DIST/qupath-extension-prototype1.jar" -C "$CLASSES" .
echo "$DIST/qupath-extension-prototype1.jar"
