#!/bin/bash
# Copyright 2026 Sriram (Sriram-Codes-SW)
#
# This file is part of Doorprints.
#
# Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
# Public License as published by the Free Software Foundation, version 3 of the License.
#
# Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
# warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
# the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-License-Identifier: AGPL-3.0-only

# SessionStart hook for Claude Code on the web (docs/14 §7): what a fresh cloud container lacks for the local checks,
# installed once and kept by the container cache. Cloud sessions only; a laptop has its own SDK and settings.
# - The Android SDK (the platform and build-tools android/app/build.gradle.kts names) under ~/android-sdk, exported
#   as ANDROID_HOME for the session (Gradle then needs no local.properties).
# - A Gradle init script that adds Google's Maven Central mirror: through the session proxy Maven Central answers
#   429 for the plugin POMs (2026-09-29), which made the first Gradle run fail.
# - The web app's and the guide's dependencies, so `ng test`, `ng build` and `mkdocs build --strict` run at once.
# Idempotent: every step is skipped when its result is already there.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
SDK="$HOME/android-sdk"
# Keep in step with compileSdk in android/app/build.gradle.kts and android/ui/build.gradle.kts.
PLATFORM="platforms;android-37.0"
BUILD_TOOLS="build-tools;36.0.0"
# https://developer.android.com/studio#command-line-tools-only (Linux); the zip is checked against its SHA-256.
CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
CLT_SHA256="7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Android SDK: installing the command-line tools"
  tmp="$(mktemp -d)"
  curl -sSL -o "$tmp/clt.zip" "$CLT_URL"
  echo "$CLT_SHA256  $tmp/clt.zip" | sha256sum -c - >/dev/null
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -d "$SDK/platforms/${PLATFORM#platforms;}" ] || [ ! -d "$SDK/build-tools/${BUILD_TOOLS#build-tools;}" ]; then
  echo "Android SDK: installing $PLATFORM, $BUILD_TOOLS and platform-tools"
  yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
  "$SDKMANAGER" "$PLATFORM" "$BUILD_TOOLS" "platform-tools" >/dev/null
fi
echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
echo "export ANDROID_SDK_ROOT=\"$SDK\"" >> "$CLAUDE_ENV_FILE"

mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/central-mirror.gradle.kts" <<'GRADLE'
// Written by .claude/hooks/session-start.sh (cloud sessions): Google's mirror of Maven Central as an extra repository,
// because Maven Central answers 429 for plugin POMs through the session proxy.
beforeSettings {
    val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
    pluginManagement.repositories.maven(mirror)
    dependencyResolutionManagement.repositories.maven(mirror)
}
GRADLE

# Node 24 (web.yml's version; Angular 22.2 refuses the container's Node 22.22.2) through the image's nvm, once;
# put first on the session's PATH.
NODE_MAJOR=24
if [ -s /opt/nvm/nvm.sh ]; then
  export NVM_DIR=/opt/nvm
  # shellcheck disable=SC1091
  . /opt/nvm/nvm.sh
  if ! nvm ls "$NODE_MAJOR" >/dev/null 2>&1; then
    echo "Node: installing $NODE_MAJOR"
    nvm install "$NODE_MAJOR" >/dev/null 2>&1
  fi
  nvm use "$NODE_MAJOR" >/dev/null 2>&1
  echo "export PATH=\"$(dirname "$(nvm which "$NODE_MAJOR")"):\$PATH\"" >> "$CLAUDE_ENV_FILE"
fi

if [ -f "$ROOT/web/package-lock.json" ] && [ ! -d "$ROOT/web/node_modules" ]; then
  echo "Web: npm ci"
  (cd "$ROOT/web" && npm ci --no-audit --no-fund >/dev/null)
fi

if [ -f "$ROOT/guide/requirements.txt" ] && ! command -v mkdocs >/dev/null 2>&1; then
  echo "Guide: mkdocs"
  pip install -q -r "$ROOT/guide/requirements.txt" >/dev/null
fi

echo "Session start: Android SDK at $SDK, Node $(node --version), Gradle mirror, web and guide dependencies ready"
