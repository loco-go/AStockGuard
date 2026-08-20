#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_DIR="$APP_HOME/.gradle-dist/gradle-8.9"
ZIP="$APP_HOME/.gradle-dist/gradle-8.9-bin.zip"
if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  mkdir -p "$APP_HOME/.gradle-dist"
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -L --fail --retry 3 https://services.gradle.org/distributions/gradle-8.9-bin.zip -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget https://services.gradle.org/distributions/gradle-8.9-bin.zip -O "$ZIP"
    else
      echo "curl/wget not found. Install Gradle 8.9 or download the distribution manually." >&2
      exit 1
    fi
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$ZIP" -d "$APP_HOME/.gradle-dist"
  else
    echo "unzip not found. Install unzip or Gradle 8.9." >&2
    exit 1
  fi
fi
exec "$DIST_DIR/bin/gradle" "$@"
