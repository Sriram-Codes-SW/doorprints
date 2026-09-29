#!/usr/bin/env bash
# Launch smoke test of the Doorprints iOS app on an iPhone simulator (CMP-8b; CI job ios-app in
# .github/workflows/shared-ios.yml). Usage: launch-smoke.sh <path/to/Doorprints.app> <output dir>
#
# Installs the Debug build on an available iPhone of the iOS runtime that matches the selected Xcode's simulator SDK
# (the same choice as ios-sim-tests' "Pick an iPhone simulator" step), launches it with -DoorprintsSelfCheck and reads
# the app's console. The self-check (Kotlin, Debug builds only) prints one line per check,
#   DOORPRINTS-SELFCHECK <name> PASS|FAIL|SKIP ...   for resources, database, settings and keychain,
# and finally DOORPRINTS-SELFCHECK done PASS or done FAIL. The script passes only on "done PASS"; no done line within
# the time limit (a crash, a hang) fails too. It writes <out>/launch.log, <out>/launch.png and any crash reports, and
# always shuts the simulator down.
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
  # An unsigned app (CODE_SIGNING_ALLOWED=NO) the simulator refuses: the owner-approved fallback is ad-hoc signing,
  # CODE_SIGN_IDENTITY=- in the xcodebuild step of shared-ios.yml.
  echo "::error::simctl install failed; if the simulator refuses the unsigned app, build with ad-hoc signing (CODE_SIGN_IDENTITY=-)"
  exit 1
fi

started=$(mktemp)  # marks the launch time for the crash report search
# --console-pty connects the app's stdout and stderr to a pseudo-terminal, so its lines arrive unbuffered; simctl stays
# in the foreground until the app exits, hence the background job.
xcrun simctl launch --console-pty --terminate-running-process "$udid" "$bundle_id" -DoorprintsSelfCheck \
  < /dev/null > "$log" 2>&1 &
launch_pid=$!

result=""
for ((waited = 0; waited < wait_seconds; waited++)); do
  if grep -q 'DOORPRINTS-SELFCHECK done' "$log"; then
    result=$(grep -o 'DOORPRINTS-SELFCHECK done [A-Z]*' "$log" | tail -n 1)
    break
  fi
  if ! kill -0 "$launch_pid" 2>/dev/null; then
    break  # the app (and so simctl launch) ended before the self-check finished
  fi
  sleep 1
done
# The app may have printed its last line just before it ended.
if [ -z "$result" ] && grep -q 'DOORPRINTS-SELFCHECK done' "$log"; then
  result=$(grep -o 'DOORPRINTS-SELFCHECK done [A-Z]*' "$log" | tail -n 1)
fi

# A screenshot of what the app shows once it has settled (also after a failure: it may show why).
if [ -n "$result" ]; then
  sleep 5
fi
xcrun simctl io "$udid" screenshot "$out/launch.png" || echo "::warning::no screenshot could be taken"

# Crash reports of this run, if any (the simulator's processes report to the host's DiagnosticReports).
find "$HOME/Library/Logs/DiagnosticReports" -maxdepth 1 -name 'Doorprints*' -newer "$started" \
  -exec cp {} "$out/" \; 2>/dev/null || true

echo "--- self-check lines ---"
grep 'DOORPRINTS-SELFCHECK' "$log" | tr -d '\r' || echo "(none)"

if [ "$result" = "DOORPRINTS-SELFCHECK done PASS" ]; then
  if grep -q 'DOORPRINTS-SELFCHECK [a-z]* SKIP' "$log"; then
    echo "::warning::self-check skipped a check: $(grep 'DOORPRINTS-SELFCHECK [a-z]* SKIP' "$log" | tr -d '\r' | tr '\n' ' ')"
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
