#!/usr/bin/env bash
# Launch smoke test of the Doorprints iOS app on an iPhone simulator (CMP-8b; CI job ios-app in
# .github/workflows/shared-ios.yml). Usage: launch-smoke.sh <path/to/Doorprints.app> <output dir>
#
# Installs the Debug build on an available iPhone of the iOS runtime that matches the selected Xcode's simulator SDK
# (the same choice as ios-sim-tests' "Pick an iPhone simulator" step), launches it with -DoorprintsSelfCheck and reads
# the app's console. The self-check (Kotlin, Debug builds only) prints one line per check,
#   DOORPRINTS-SELFCHECK <name> PASS|FAIL|SKIP ...   for resources, database, settings and keychain,
# and finally DOORPRINTS-SELFCHECK done PASS or done FAIL. It prints each line to stdout and with NSLog; when the
# console has no done line, the simulator's unified log (where NSLog lands) is read too, every few seconds and once at
# the end. The script passes only on "done PASS"; no done line within the time limit (a crash, a hang) fails too. It
# writes <out>/launch.log (the console), <out>/unified.log (the self-check's lines from the unified log, when read),
# <out>/launch.png and any crash reports, and always shuts the simulator down.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <path/to/Doorprints.app> <output dir>" >&2
  exit 2
fi
app=$1
out=$2
bundle_id=app.doorprints
wait_seconds=120
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

launch_pid=""
# shellcheck disable=SC2329  # called by the EXIT trap
cleanup() {
  if [ -n "$launch_pid" ]; then
    kill "$launch_pid" 2>/dev/null || true
  fi
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

# The self-check's lines from the simulator's unified log (NSLog), into $unified; never fails the script by itself.
read_unified_log() {
  xcrun simctl spawn "$udid" log show --last 3m --style compact \
    --predicate 'eventMessage CONTAINS "DOORPRINTS-SELFCHECK"' > "$unified" 2>&1 || true
}

# Sets result to the last "DOORPRINTS-SELFCHECK done <OUTCOME>" in the console, or else in the unified log (read now
# when read_log is 1). Returns 1 while there is none.
find_done() {
  local read_log=$1
  if grep -q 'DOORPRINTS-SELFCHECK done' "$log"; then
    result=$(grep -o 'DOORPRINTS-SELFCHECK done [A-Z]*' "$log" | tail -n 1)
    return 0
  fi
  if [ "$read_log" = 1 ]; then
    read_unified_log
    if grep -q 'DOORPRINTS-SELFCHECK done' "$unified" 2>/dev/null; then
      result=$(grep -o 'DOORPRINTS-SELFCHECK done [A-Z]*' "$unified" | tail -n 1)
      return 0
    fi
  fi
  return 1
}

started=$(mktemp)  # marks the launch time for the crash report search
# --console-pty connects the app's stdout and stderr to a pseudo-terminal, so its lines arrive unbuffered; simctl stays
# in the foreground until the app exits, hence the background job.
xcrun simctl launch --console-pty --terminate-running-process "$udid" "$bundle_id" -DoorprintsSelfCheck \
  < /dev/null > "$log" 2>&1 &
launch_pid=$!

result=""
for ((waited = 0; waited < wait_seconds; waited++)); do
  # The console every second; the unified log (slower to query) every fifth.
  if find_done "$(( waited % 5 == 4 ? 1 : 0 ))"; then
    break
  fi
  if ! kill -0 "$launch_pid" 2>/dev/null; then
    break  # the app (and so simctl launch) ended before the self-check finished
  fi
  sleep 1
done
# The app may have printed its last line just before it ended, or only to the unified log.
if [ -z "$result" ]; then
  find_done 1 || true
fi

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

# The self-check's lines from the console, or from the unified log when the console has none.
lines=$(grep -o 'DOORPRINTS-SELFCHECK .*' "$log" 2>/dev/null | tr -d '\r' || true)
if [ -z "$lines" ] && [ -f "$unified" ]; then
  lines=$(grep -o 'DOORPRINTS-SELFCHECK .*' "$unified" 2>/dev/null | tr -d '\r' || true)
fi
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
  skipped=$(echo "$lines" | grep 'DOORPRINTS-SELFCHECK [a-z]* SKIP' || true)
  if [ -n "$skipped" ]; then
    echo "::warning::self-check skipped a check: $(echo "$skipped" | tr '\n' ' ')"
  fi
  echo "Launch smoke test passed."
  exit 0
fi

echo "--- last 80 lines of the app's console ($log) ---"
tail -n 80 "$log" || true
if [ -z "$result" ]; then
  if kill -0 "$launch_pid" 2>/dev/null; then
    echo "::error::no 'DOORPRINTS-SELFCHECK done' line within ${wait_seconds} s (the app hangs, or the self-check never ran)"
  else
    echo "::error::the app ended before 'DOORPRINTS-SELFCHECK done' (a crash at launch?); crash reports, if any, are in $out"
  fi
else
  echo "::error::the self-check failed: $result"
fi
exit 1
