#!/usr/bin/env bash
# Installs the pinned firebase-tools CLI into .github/firebase-tools/node_modules, the only way web.yml gets it.
# Owner: DevSecOps (.github/**).
#
# - Version: exact, from package.json here ("firebase-tools": "15.30.2"); Dependabot (npm, /.github/firebase-tools)
#   proposes updates. Never `npx firebase-tools@latest`.
# - Transitive tree: firebase-tools publishes an npm-shrinkwrap.json, which npm honours, so its dependency tree is
#   fixed by the pinned version even before our own lock file exists.
# - Lock file: with package-lock.json present (integrity hashes, including the one for firebase-tools itself) this
#   runs `npm ci`. Without it (the first runs: the registry could not be reached when this directory was written,
#   so no lock file could be produced by hand) it runs `npm install`, warns, and the caller uploads the generated
#   lock file as the artifact `firebase-tools-package-lock` for the owner to commit here.
# - --ignore-scripts: no package install script runs. In the shrinkwrap of 15.30.2 only three production packages
#   declare one: protobufjs (a version-notice postinstall), and the optional native modules re2 (used by superstatic,
#   i.e. the local emulator, not by deploy) and fsevents (macOS only). The deploy path needs none of them.
#
# Outputs (when GITHUB_OUTPUT is set): version=<installed firebase-tools version>, lock=committed|generated.
#
# Change log
# 2026-09-23 (DevSecOps): first version, for the Firebase Hosting deploy.
set -euo pipefail

cd "$(dirname "$0")"
out="${GITHUB_OUTPUT:-/dev/null}"

pinned=$(jq -r '.dependencies["firebase-tools"] // empty' package.json)
if ! [[ "$pinned" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error title=firebase-tools::.github/firebase-tools/package.json must pin firebase-tools to one exact version (x.y.z); found '${pinned:-nothing}'."
  exit 1
fi

if [ -f package-lock.json ]; then
  npm ci --ignore-scripts --no-audit --no-fund
  echo "lock=committed" >> "$out"
else
  echo "::warning title=firebase-tools::.github/firebase-tools/package-lock.json is not committed yet, so npm install resolved firebase-tools ${pinned} (its own npm-shrinkwrap.json still fixes the transitive tree). Commit the package-lock.json from the artifact firebase-tools-package-lock (job 'Firebase Hosting config and CLI') to .github/firebase-tools/."
  npm install --ignore-scripts --no-audit --no-fund
  echo "lock=generated" >> "$out"
fi

# `firebase --version` prints the version on stdout; anything else (notices) would go to stderr.
installed=$(node_modules/.bin/firebase --version 2> /dev/null | tail -n 1 | tr -d '[:space:]') || installed=""
if [ "$installed" != "$pinned" ]; then
  echo "::error title=firebase-tools::installed firebase-tools reports version '${installed:-none}', expected ${pinned} from .github/firebase-tools/package.json."
  exit 1
fi
echo "firebase-tools ${installed} installed."
echo "version=${installed}" >> "$out"
