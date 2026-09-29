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

# Launch smoke test of the Doorprints iOS app on an iPhone simulator (CMP-8b; CI job ios-app in
# .github/workflows/shared-ios.yml). Usage: launch-smoke.sh <path/to/Doorprints.app> <output dir>
#
# Installs the Debug build on an available iPhone of the iOS runtime that matches the selected Xcode's simulator SDK
# (the same choice as ios-sim-tests' "Pick an iPhone simulator" step), launches it with -DoorprintsSelfCheck and reads
# the simulator's unified log. The self-check (Kotlin, Debug builds only) writes one line per check with NSLog,
#   DOORPRINTS-SELFCHECK <name> START, then PASS|FAIL|SKIP ...   for resources, database, settings, keychain,
#   indiaView and map (CMP-8c: the in-app boundary check, the owner's CI gate for the iOS map),
# and finally DOORPRINTS-SELFCHECK done PASS or done FAIL. The script passes only on "done PASS" with indiaView and map
# both PASS (they download the map's style, so they need the network); no done line within the time limit (a crash, a
# hang) fails too. It writes <out>/launch.log (the streamed DOORPRINTS- lines),
# <out>/unified.log (the same from `log show`), <out>/app-unified.log and <out>/system-unified.log (everything the app
# logged, and what the system logged about it), <out>/launch.png and any crash reports, and always shuts the
# simulator down.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <path/to/Doorprints.app> <output dir>" >&2
  exit 2
fi
app=$1
out=$2
bundle_id=app.doorprints
# The checks run one after another: four of up to 30 s, indiaView up to 60 s and map up to 90 s (SelfCheck.kt).
wait_seconds=300
[ -d "$app" ] || { echo "::error::no app bundle at $app"; exit 2; }
mkdir -p "$out"
log="$out/launch.log"
unified="$out/unified.log"
: > "$log"

sdk=$(xcrun --sdk iphonesimulator --show-sdk-version)
runtime="iOS-$(echo "$sdk" | tr . -)"
udid=$(xcrun simctl list devices available --json | RUNTIME="$runtime" python3 -c '
import json, os, sys
devices = json.load(sys.stdin)["devices"]
wanted = [r for r in devices if r.endswith("." + os.environ["RUNTIME"])]
phones = sorted((d for r in wanted for d in devices[r] if d["name"].startswith("iPhone")), key=lambda d: d["name"])
if phones:
    print(phones[-1]["udid"])
')
if [ -z "$udid" ]; then
  echo "::error::no available iPhone simulator on runtime $runtime (SDK $sdk)"
  xcrun simctl list devices available
  exit 1
fi
echo "Simulator: $(xcrun simctl list devices available | grep "$udid" | sed 's/^ *//')"

# shellcheck disable=SC2329  # called by the EXIT trap
cleanup() {
  xcrun simctl shutdown "$udid" 2>/dev/null || true
}
trap cleanup EXIT

# Boots the device if needed and waits until it is ready for installs and launches.
xcrun simctl bootstatus "$udid" -b

if ! xcrun simctl install "$udid" "$app"; then
  # The app is ad-hoc signed (CODE_SIGN_IDENTITY=- in the xcodebuild step of shared-ios.yml), which the simulator
  # accepts; a refusal points at the build's signing settings.
  echo "::error::simctl install failed; check the app's ad-hoc signature (codesign -dv \"$app\")"
  exit 1
fi

# The app is launched detached and its lines are read from the unified log, where the self-check and the start-up
# steps write them with NSLog: a `log stream` started before the launch, so nothing is missed, and a `log show` at
# the end as a second source. (The first CI launches crashed with SIGSEGV inside NSLog, a Kotlin String passed as a
# variadic argument, fixed in the app's IosLog.kt; launching detached also lets ReportCrash's report be found.)
stream_pid=""
# shellcheck disable=SC2329  # called by the EXIT trap
stop_stream() {
  if [ -n "$stream_pid" ]; then
    kill "$stream_pid" 2>/dev/null || true
  fi
}
read_unified_log() {
  xcrun simctl spawn "$udid" log show --last 5m --style compact \
    --predicate 'eventMessage CONTAINS "DOORPRINTS-"' > "$unified" 2>&1 || true
}
# Sets result to the last "DOORPRINTS-SELFCHECK done <OUTCOME>" in the streamed log (and, when read_log is 1, in a
# fresh `log show`). Returns 1 while there is none.
find_done() {
  local read_log=$1 file
  [ "$read_log" = 1 ] && read_unified_log
  for file in "$log" "$unified"; do
    if grep -q 'DOORPRINTS-SELFCHECK done' "$file" 2>/dev/null; then
      result=$(grep -o 'DOORPRINTS-SELFCHECK done [A-Z]*' "$file" | tail -n 1)
      return 0
    fi
  done
  return 1
}
# True while the app runs: launchd inside the simulator lists it as UIKitApplication:app.doorprints[...].
app_running() {
  xcrun simctl spawn "$udid" launchctl list 2>/dev/null | grep -q "UIKitApplication:$bundle_id"
}

xcrun simctl spawn "$udid" log stream --style compact --level debug \
  --predicate 'eventMessage CONTAINS "DOORPRINTS-"' > "$log" 2>&1 &
stream_pid=$!
trap 'stop_stream; cleanup' EXIT
sleep 2  # let the stream attach before the app starts

started=$(mktemp)  # marks the launch time for the crash report search
xcrun simctl launch --terminate-running-process "$udid" "$bundle_id" -DoorprintsSelfCheck

result=""
seen_running=0
for ((waited = 0; waited < wait_seconds; waited++)); do
  # The stream every second; a full `log show` (slower) every tenth.
  if find_done "$(( waited % 10 == 9 ? 1 : 0 ))"; then
    break
  fi
  if app_running; then
    seen_running=1
  elif [ "$seen_running" = 1 ] || [ "$waited" -ge 10 ]; then
    break  # the app ended (or never showed up) before the self-check finished
  fi
  sleep 1
done
# The app may have logged its last line just before it ended.
if [ -z "$result" ]; then
  sleep 2
  find_done 1 || true
fi
stop_stream

# A screenshot of what the app shows once it has settled (also after a failure: it may show why).
if [ -n "$result" ]; then
  sleep 5
fi
xcrun simctl io "$udid" screenshot "$out/launch.png" || echo "::warning::no screenshot could be taken"

# Crash reports of this run, if any (the simulator's processes report to the host's DiagnosticReports, named after
# the process: Doorprints-<date>.ips), and everything the app wrote to the unified log in the last minutes, for a
# crash that left no report (an uncaught Kotlin exception logs DOORPRINTS-CRASH lines there).
# ReportCrash writes the report some seconds after the app ends, so wait for one (up to a minute) when it failed.
if [ "$result" != "DOORPRINTS-SELFCHECK done PASS" ]; then
  for ((i = 0; i < 60; i++)); do
    find "$HOME/Library/Logs/DiagnosticReports" -name '*.ips' -newer "$started" 2>/dev/null | grep -q . && break
    sleep 1
  done
fi
find "$HOME/Library/Logs/DiagnosticReports" -name '*.ips' -newer "$started" -exec cp {} "$out/" \; 2>/dev/null || true
xcrun simctl spawn "$udid" log show --last 5m --style compact --predicate 'process == "Doorprints"' \
  > "$out/app-unified.log" 2>&1 || true
# What the simulator's own processes (launchd, runningboardd, FrontBoard) logged about the app, e.g. why it ended.
xcrun simctl spawn "$udid" log show --last 5m --style compact \
  --predicate 'process != "Doorprints" AND eventMessage CONTAINS[c] "doorprints"' > "$out/system-unified.log" 2>&1 || true

# The self-check's lines from both sources: the stream and a fresh `log show`. Either can miss a line (on
# 2026-09-29 the stream dropped "indiaView PASS" while it kept the lines around it), so neither is read alone; each
# check's lines are kept in the order the app wrote them, once.
read_unified_log
lines=$(cat "$log" "$unified" 2>/dev/null | grep -o 'DOORPRINTS-SELFCHECK .*' | tr -d '\r' | awk '!seen[$0]++' || true)
echo "--- self-check lines ---"
echo "${lines:-(none)}"
echo "--- start-up steps ---"
grep -ho 'DOORPRINTS-STARTUP .*' "$out/app-unified.log" 2>/dev/null || echo "(none)"
echo "--- how the app ended (system log) ---"
grep -iE 'exit|termin|signal|crash|kill|reason' "$out/system-unified.log" 2>/dev/null | tail -15 | cut -c1-300 || true
crash=$(grep -h -A 25 'DOORPRINTS-CRASH' "$log" "$out/app-unified.log" 2>/dev/null | head -40 || true)
if [ -n "$crash" ]; then
  echo "--- uncaught exception ---"
  echo "$crash"
fi
for report in "$out"/*.ips; do
  [ -f "$report" ] || continue
  echo "--- crash report $(basename "$report") (first lines) ---"
  head -c 3000 "$report"; echo
done

if [ "$result" = "DOORPRINTS-SELFCHECK done PASS" ]; then
  # The boundary gate: both map checks must have passed, not merely not failed.
  for gate in indiaView map; do
    if ! echo "$lines" | grep -q "DOORPRINTS-SELFCHECK $gate PASS"; then
      echo "::error::the self-check passed without '$gate PASS' (the iOS map's boundary gate)"
      exit 1
    fi
  done
  skipped=$(echo "$lines" | grep 'DOORPRINTS-SELFCHECK [a-z]* SKIP' || true)
  if [ -n "$skipped" ]; then
    echo "::warning::self-check skipped a check: $(echo "$skipped" | tr '\n' ' ')"
  fi
  echo "Launch smoke test passed."
  exit 0
fi

echo "--- last 80 streamed lines ($log) ---"
tail -n 80 "$log" || true
if [ -z "$result" ]; then
  if app_running; then
    echo "::error::no 'DOORPRINTS-SELFCHECK done' line within ${wait_seconds} s (the app hangs, or the self-check never ran)"
  else
    echo "::error::the app ended before 'DOORPRINTS-SELFCHECK done' (a crash at launch?); crash reports, if any, are in $out"
  fi
else
  echo "::error::the self-check failed: $result"
fi
exit 1
