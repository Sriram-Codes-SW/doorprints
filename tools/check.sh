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

# The local checks before a push (docs/14 §7), run together where they are independent, so a change is validated in
# the time of the slowest one instead of the sum. Areas are chosen from the files changed against main (or given as
# arguments: android, ios, web, guide, licence, all). Each area's log is written under android/build/check/ and its tail is
# shown when it fails. Exit 1 on any failure.
#
#   tools/check.sh            # the areas the branch touches
#   tools/check.sh android ios
#   git diff --name-only origin/main | tools/check.sh --print-areas   # only print what a list of changed files picks
#
# An area is picked from the same paths as its CI workflow's path filter (S4b-BL-105): android from android.yml's
# (android/**, docs/schemas/**, web/public/geo/**, the two AI test-vector files), ios from the inputs of the iOS klib
# compile in shared-ios.yml's, web from web.yml's (web/**, docs/schemas/**, .github/firebase-tools/**) and guide from
# pages.yml's (guide/**, the Android screenshots). Keep them in step when a filter changes. The backend is not an area
# (mvn verify needs PostGIS): a change to backend.yml's inputs prints a reminder.
#
# The Android Gradle checks are one Gradle invocation (Gradle parallelises the modules itself); the iOS klib compile
# needs the Kotlin/Native toolchain and runs as its own Gradle build after the Android one (a second Gradle build in
# the same project directory would contend for the lock), so "android" and "ios" are one sequence; web, the guide and
# the licence headers run beside it.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/android/build/check" # git-ignored (android/build)
mkdir -p "$LOGS"

# The areas a list of changed files (stdin, one path per line) picks, on stdout; notes go to stderr.
areas_for() {
  local changed areas="licence"
  changed="$(cat)"
  grep -qE '^(android/|docs/schemas/|web/public/geo/|docs/ai/evals/parity-vectors\.json$|web/src/app/core/ai/parity-vectors\.json$|\.github/workflows/android\.yml$)' \
    <<<"$changed" && areas="$areas android"
  grep -qE '^(android/(shared|ui)/(src/(commonMain|iosMain|nativeMain)/|build\.gradle\.kts$)|android/(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties)$|android/gradle/|ios/|\.github/workflows/shared-ios\.yml$)' \
    <<<"$changed" && areas="$areas ios"
  grep -qE '^(web/|docs/schemas/|\.github/firebase-tools/|\.github/workflows/web\.yml$)' <<<"$changed" && areas="$areas web"
  grep -qE '^(guide/|android/app/src/test/screenshots/|\.github/workflows/pages\.yml$)' <<<"$changed" && areas="$areas guide"
  grep -qE '^(backend/|docs/ai/evals/|docs/schemas/|docker-compose\.yml$|web/src/app/export/backup-export\.ts$|web/src/app/export/golden/|android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup\.kt$|\.github/workflows/backend\.yml$)' \
    <<<"$changed" && echo "Backend inputs changed: run cd backend && mvn -B -ntp verify (needs PostGIS, see backend.yml); not run here." >&2
  [ "$areas" = "licence" ] && [ -n "$changed" ] && echo "No android, iOS, web or guide input changed: licence headers only." >&2
  echo "$areas"
}

if [ "${1:-}" = "--print-areas" ]; then
  areas_for
  exit 0
elif [ $# -gt 0 ]; then
  AREAS="$*"
else
  base="$(git -C "$ROOT" merge-base HEAD origin/main 2>/dev/null || git -C "$ROOT" merge-base HEAD main)"
  AREAS="$({ git -C "$ROOT" diff --name-only "$base" HEAD; git -C "$ROOT" diff --name-only HEAD
    git -C "$ROOT" ls-files --others --exclude-standard; } | areas_for)"
fi
echo "areas: $AREAS"
case " $AREAS " in *" all "*) AREAS="licence android ios web guide";; esac
has() { case " $AREAS " in *" $1 "*) return 0;; esac; return 1; }

declare -A PIDS
start() { # name, command...
  local name="$1"; shift
  ( "$@" ) > "$LOGS/$name.log" 2>&1 &
  PIDS[$name]=$!
  echo "started: $name (log android/build/check/$name.log)"
}

gradle_seq() {
  cd "$ROOT/android" || return 1
  if has android; then
    ./gradlew --no-daemon assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest \
      :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata -Proborazzi.test.verify=true || return 1
  fi
  if has ios; then
    ./gradlew --no-daemon -Pkotlin.native.enableKlibsCrossCompilation=true \
      :ui:compileKotlinIosSimulatorArm64 :ui:compileKotlinIosArm64 || return 1
  fi
}

has licence && start licence python3 "$ROOT/.github/scripts/licence-headers.py" --check
if has android || has ios; then start gradle gradle_seq; fi
has web && start web bash -c "cd '$ROOT/web' && npx ng test --watch=false && CI=true npm run test:ci && npm run build"
# --strict fails on errors; a WARNING line once passed locally and failed CI (docs/14 §7), so it fails here too.
guide_check() {
  cd "$ROOT/guide" || return 1
  local out rc
  out="$(mkdocs build --strict --site-dir "$LOGS/site" 2>&1)"; rc=$?
  echo "$out"
  [ "$rc" -eq 0 ] && ! grep -q "WARNING" <<<"$out"
}
has guide && start guide guide_check

failed=0
for name in "${!PIDS[@]}"; do
  if wait "${PIDS[$name]}"; then
    echo "ok: $name"
  else
    echo "FAILED: $name"; tail -40 "$LOGS/$name.log"; failed=1
  fi
done
exit $failed
