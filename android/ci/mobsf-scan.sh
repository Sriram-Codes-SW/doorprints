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

# The release security gate's static scan of the Android APK (S4b-SEC-1, docs/06 TC-S-06): MobSF, pinned by digest,
# in Docker; the APK goes through its REST API (upload, scan, report). Used by android.yml's `mobsf` job; runs the
# same on a developer machine with Docker.
#
# Usage: mobsf-scan.sh <apk> <output dir> <accepted findings JSON>
# Fails (exit 1) on any High finding not in the accepted list (android/ci/mobsf-accepted.json, each with its reason)
# and on any tracker (the app promises no ads and no analytics). Warnings are listed. Writes <out>/mobsf-report.json
# and <out>/mobsf-scorecard.json.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <apk> <output dir> <accepted findings JSON>" >&2
  exit 2
fi
apk=$1
out=$2
accepted=$3
# MobSF v4.5.4, pinned by digest (Docker Hub's `latest` on 2026-09-29).
# MOBSF_IMAGE overrides it (the same digest from a mirror, where Docker Hub rate-limits pulls).
image=${MOBSF_IMAGE:-opensecurity/mobile-security-framework-mobsf@sha256:83bc8aaf940d66344b7c10ebc12e921086bec43369a8259582e6dc258f2924b0}
name=doorprints-mobsf-$$
port=18000
# A throwaway key for this run's API calls (never printed).
key=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')

[ -s "$apk" ] || { echo "::error::no APK at $apk"; exit 2; }
mkdir -p "$out"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$name" -p "127.0.0.1:$port:8000" -e MOBSF_API_KEY="$key" "$image" >/dev/null
up=0
for _ in $(seq 1 120); do
  if curl -s --noproxy '*' -o /dev/null "http://127.0.0.1:$port/"; then
    up=1
    break
  fi
  sleep 2
done
[ "$up" = 1 ] || { echo "::error::MobSF did not start"; docker logs "$name" 2>&1 | tail -40; exit 1; }

api() { curl -sS --noproxy '*' --fail-with-body -H "Authorization: $key" "$@"; }
hash=$(api -F "file=@$apk" "http://127.0.0.1:$port/api/v1/upload" | python3 -c 'import json,sys; print(json.load(sys.stdin)["hash"])')
api -d "hash=$hash" "http://127.0.0.1:$port/api/v1/scan" -o "$out/mobsf-report.json"
api -d "hash=$hash" "http://127.0.0.1:$port/api/v1/scorecard" -o "$out/mobsf-scorecard.json"

python3 - "$out/mobsf-scorecard.json" "$accepted" <<'PY'
import json, sys
card = json.load(open(sys.argv[1]))
accepted = {(a["section"], a["title"]) for a in json.load(open(sys.argv[2]))["accepted"]}
print(f"MobSF {card.get('version')}: security score {card.get('security_score')}, "
      f"{len(card.get('high', []))} high, {len(card.get('warning', []))} warning, trackers {card.get('trackers')}")
failed = 0
for f in card.get("high", []):
    key = (f.get("section"), f.get("title"))
    if key in accepted:
        print(f"HIGH (accepted) [{key[0]}] {key[1]}")
    else:
        failed += 1
        print(f"::error::MobSF High finding not reviewed: [{key[0]}] {key[1]}")
for f in card.get("warning", []):
    print(f"warning [{f.get('section')}] {f.get('title')}")
if card.get("trackers"):
    failed += 1
    print(f"::error::MobSF found {card.get('trackers')} tracker(s); the app promises none")
sys.exit(1 if failed else 0)
PY
