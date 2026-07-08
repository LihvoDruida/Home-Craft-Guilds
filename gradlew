#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.14.3"
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
WRAP_DIR="$ROOT_DIR/.gradle-local"
GRADLE_HOME="$WRAP_DIR/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
GRADLE_ZIP="$WRAP_DIR/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

mkdir -p "$WRAP_DIR"
if [ ! -x "$GRADLE_BIN" ]; then
  echo "[Home Craft] Gradle $GRADLE_VERSION not found locally. Downloading official distribution once..."
  if command -v curl >/dev/null 2>&1; then
    curl -L "$GRADLE_URL" -o "$GRADLE_ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget "$GRADLE_URL" -O "$GRADLE_ZIP"
  else
    echo "[ERROR] curl or wget is required to download Gradle." >&2
    exit 1
  fi
  unzip -o "$GRADLE_ZIP" -d "$WRAP_DIR"
fi
exec "$GRADLE_BIN" "$@"
