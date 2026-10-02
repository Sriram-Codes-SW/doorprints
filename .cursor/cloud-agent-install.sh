#!/usr/bin/env bash
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

# Cloud Agent install (docs/14 section 7, .claude/hooks/session-start.sh): Node 24, Android SDK, Gradle mirror,
# web npm ci, MkDocs for the guide. Idempotent; login shells load ANDROID_HOME from /etc/profile.d/doorprints-env.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NODE_BIN_DIR=""

install_node24() {
  local nvm_sh=""
  if [ -s /opt/nvm/nvm.sh ]; then
    NVM_DIR=/opt/nvm
    nvm_sh=/opt/nvm/nvm.sh
  elif [ -s "$HOME/.nvm/nvm.sh" ]; then
    NVM_DIR="$HOME/.nvm"
    nvm_sh="$HOME/.nvm/nvm.sh"
  else
    echo "Node: installing Node 24 tarball to /usr/local"
    local ver=24.15.0 arch=linux-x64
    curl -fsSL "https://nodejs.org/dist/v${ver}/node-v${ver}-${arch}.tar.xz" -o /tmp/node.tar.xz
    sudo tar -xJ -C /usr/local --strip-components=1 --no-same-owner -f /tmp/node.tar.xz
    rm -f /tmp/node.tar.xz
    NODE_BIN_DIR="/usr/local/bin"
    export PATH="$NODE_BIN_DIR:$PATH"
    echo "Node: $(node --version)"
    return
  fi
  export NVM_DIR
  # shellcheck disable=SC1090
  . "$nvm_sh"
  if ! nvm ls 24 >/dev/null 2>&1; then
    echo "Node: installing 24 via nvm"
    nvm install 24 >/dev/null 2>&1
  fi
  # Angular 22.2 requires ^24.15.0 (web.yml uses Node 24).
  if ! nvm ls 24.15.0 >/dev/null 2>&1; then
    nvm install 24.15.0 >/dev/null 2>&1
  fi
  nvm alias default 24.15.0 >/dev/null 2>&1
  nvm use 24.15.0 >/dev/null 2>&1
  NODE_BIN_DIR="$(dirname "$(nvm which 24.15.0)")"
  export PATH="$NODE_BIN_DIR:$PATH"
  echo "Node: $(node --version)"
}

install_android_sdk() {
  local SDK="$HOME/android-sdk"
  local PLATFORM="platforms;android-37.0"
  local BUILD_TOOLS="build-tools;36.0.0"
  local CLT_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
  local CLT_SHA256="7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee"

  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "Android SDK: installing command-line tools"
    local tmp
    tmp="$(mktemp -d)"
    curl -sSL -o "$tmp/clt.zip" "$CLT_URL"
    echo "$CLT_SHA256  $tmp/clt.zip" | sha256sum -c - >/dev/null
    mkdir -p "$SDK/cmdline-tools"
    unzip -q "$tmp/clt.zip" -d "$tmp"
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  local SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -d "$SDK/platforms/${PLATFORM#platforms;}" ] || [ ! -d "$SDK/build-tools/${BUILD_TOOLS#build-tools;}" ]; then
    echo "Android SDK: installing $PLATFORM and $BUILD_TOOLS"
    yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" "$PLATFORM" "$BUILD_TOOLS" "platform-tools" >/dev/null
  fi
  sudo tee /etc/profile.d/doorprints-env.sh >/dev/null <<EOF
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$NODE_BIN_DIR:\$HOME/.local/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF
  export ANDROID_HOME="$SDK"
  export ANDROID_SDK_ROOT="$SDK"
  export PATH="$NODE_BIN_DIR:$HOME/.local/bin:$ANDROID_HOME/platform-tools:$PATH"
}

install_gradle_mirror() {
  mkdir -p "$HOME/.gradle/init.d"
  cat > "$HOME/.gradle/init.d/central-mirror.gradle.kts" <<'GRADLE'
beforeSettings {
    val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
    pluginManagement.repositories.maven(mirror)
    dependencyResolutionManagement.repositories.maven(mirror)
}
GRADLE
}

install_web_deps() {
  if [ -f "$ROOT/web/package-lock.json" ]; then
    echo "Web: npm ci"
    (
      cd "$ROOT/web"
      export PATH="$NODE_BIN_DIR:$HOME/.local/bin:$PATH"
      npm ci --no-audit --no-fund
    )
  fi
}

install_guide_deps() {
  export PATH="$NODE_BIN_DIR:$HOME/.local/bin:$PATH"
  if [ -f "$ROOT/guide/requirements.txt" ] && ! command -v mkdocs >/dev/null 2>&1; then
    echo "Guide: pip install mkdocs"
    pip install -q -r "$ROOT/guide/requirements.txt"
  fi
}

install_node24
install_android_sdk
install_gradle_mirror
install_web_deps
install_guide_deps
export PATH="$NODE_BIN_DIR:$HOME/.local/bin:${ANDROID_HOME:+$ANDROID_HOME/platform-tools:}$PATH"
echo "cloud-agent-install: done (Node $(node --version), ANDROID_HOME=${ANDROID_HOME:-unset})"
