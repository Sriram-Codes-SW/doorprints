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

# Smoke test of a running Doorprints server (guide: "Set up your own server", docs/10 S4b-BL-63). Needs only bash and
# curl. It checks health, that a request without a key is refused, a house, a sync record, an import of
# docs/schemas/backup-sample.json (a dry run, then for real) and an export that holds it all, and then deletes what it
# made. It writes made-up houses, so it refuses to run on a server that holds or ever held a house (deleted houses leave
# tombstones, and the newest write wins, so a second run would not import the sample's houses again). Run it once on a
# new server; FORCE=1 runs it anyway, and its houses are written, then deleted.
#
#   tools/server-smoke.sh [server-address]        default http://localhost:8080
#
# The key: DOORPRINTS_KEY or APP_API_KEY in the environment, else APP_API_KEY from ./.env. To also test pairing, set
# OWNER_SETUP to the part after "#setup=" of the link under "Doorprints owner page" in `docker compose logs api`
# (the link works once: this uses it up, restart the server for a new one).
set -u
BASE="${1:-http://localhost:8080}"; BASE="${BASE%/}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLE="$HERE/docs/schemas/backup-sample.json"
KEY="${DOORPRINTS_KEY:-${APP_API_KEY:-}}"
if [ -z "$KEY" ] && [ -f .env ]; then KEY="$(sed -n 's/^APP_API_KEY=//p' .env | head -1)"; fi
[ -n "$KEY" ] || { echo "No key: set DOORPRINTS_KEY or APP_API_KEY, or run this in the folder with .env" >&2; exit 2; }
[ -f "$SAMPLE" ] || { echo "Missing $SAMPLE (run this from a full copy of the repository)" >&2; exit 2; }

fail=0
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
ok()  { echo "PASS  $1"; }
bad() { echo "FAIL  $1"; fail=1; }
# req NAME EXPECTED-STATUS curl-args...: body in $TMP/body
req() {
  local name="$1" want="$2"; shift 2
  local got; got="$(curl -sS -o "$TMP/body" -w '%{http_code}' --max-time 60 "$@")" || { bad "$name (curl failed)"; return 1; }
  if [ "$got" = "$want" ]; then ok "$name"; else bad "$name (HTTP $got, wanted $want): $(head -c 200 "$TMP/body")"; return 1; fi
}
has() { grep -q "$2" "$TMP/body" && ok "$1" || bad "$1 (no $2 in the answer)"; }
JSON='Content-Type: application/json'
HOUSE=5a0e5a0e-5a0e-45a0-85a0-5a0e5a0e5a0e
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

req "health" 200 "$BASE/actuator/health" && has "health says UP" '"status":"UP"'
req "no key is refused" 401 "$BASE/api/houses"
req "wrong key is refused" 401 -H "X-API-Key: not-the-key" "$BASE/api/houses"

if [ -n "${OWNER_SETUP:-}" ]; then
  OH='X-Doorprints-Owner: 1'
  req "owner sign-in" 204 -c "$TMP/jar" -H "$OH" -H "$JSON" -d "{\"setup\":\"$OWNER_SETUP\"}" "$BASE/owner/api/session"
  req "pairing start" 200 -H "$JSON" -d '{"deviceName":"server-smoke.sh"}' "$BASE/api/pair/start"
  code="$(sed -n 's/.*"userCode":"\([^"]*\)".*/\1/p' "$TMP/body")"; poll="$(sed -n 's/.*"pollToken":"\([^"]*\)".*/\1/p' "$TMP/body")"
  req "owner finds the code" 200 -b "$TMP/jar" -H "$OH" -H "$JSON" -d "{\"code\":\"$code\"}" "$BASE/owner/api/pairings/find"
  req "owner approves" 204 -b "$TMP/jar" -H "$OH" -H "$JSON" -d "{\"code\":\"$code\"}" "$BASE/owner/api/pairings/approve"
  req "device polls" 200 -H "$JSON" -d "{\"pollToken\":\"$poll\"}" "$BASE/api/pair/poll" && has "device got its key" '"status":"approved"'
  DEVKEY="$(sed -n 's/.*"deviceKey":"\([^"]*\)".*/\1/p' "$TMP/body")"
  [ -n "$DEVKEY" ] && KEY="$DEVKEY"   # the rest runs as the paired device
  # revoke the test device at the end
  DEVID="$(curl -s -b "$TMP/jar" -H "$OH" "$BASE/owner/api/overview" | grep -o '"id":"[^"]*","name":"server-smoke.sh"' | head -1 | sed 's/"id":"\([^"]*\)".*/\1/')"
fi
A=(-H "X-API-Key: $KEY")

req "key is accepted" 200 "${A[@]}" "$BASE/api/houses?since=0" || { echo "Stopping: the key does not work."; exit 1; }
if [ "$(tr -d ' \n' < "$TMP/body")" != "[]" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "This server holds, or once held, houses. Not writing test houses (set FORCE=1 to run anyway)."; exit 3
fi

req "create a house" 200 "${A[@]}" -X PUT -H "$JSON" \
  -d "{\"label\":\"Smoke test house\",\"lat\":13.0067,\"lon\":80.2573,\"status\":\"NEW\",\"price\":25000,\"priceType\":\"RENT\",\"createdAt\":\"$NOW\",\"updatedAt\":\"$NOW\"}" "$BASE/api/houses/$HOUSE"
req "read it back" 200 "${A[@]}" "$BASE/api/houses/$HOUSE" && has "house label" 'Smoke test house'
req "sync a record" 200 "${A[@]}" -X PUT -H "$JSON" \
  -d "{\"type\":\"smoketest\",\"id\":\"s1\",\"payload\":{\"text\":\"hello\"},\"updatedAt\":\"$NOW\",\"deleted\":false}" "$BASE/api/records/smoketest/s1"
req "list records" 200 "${A[@]}" "$BASE/api/records?since=0&type=smoketest" && has "record in the list" '"text":"hello"'

req "import, dry run" 200 "${A[@]}" -X POST -H "$JSON" --data-binary @"$SAMPLE" "$BASE/api/import?dryRun=true"
req "dry run wrote nothing" 404 "${A[@]}" "$BASE/api/houses/11111111-1111-4111-8111-111111111111"
req "import for real" 200 "${A[@]}" -X POST -H "$JSON" --data-binary @"$SAMPLE" "$BASE/api/import"
req "imported house is there" 200 "${A[@]}" "$BASE/api/houses/11111111-1111-4111-8111-111111111111"
req "export" 200 "${A[@]}" "$BASE/api/export" && {
  has "export is a backup" '"format":"doorprints-backup/'
  has "export holds the imported house" 'Green View 2BHK'
  has "export holds the test house" 'Smoke test house'
}
req "records after the import" 200 "${A[@]}" "$BASE/api/records?since=0" && has "a viewing from the import" '"type":"viewing"'

# clean up what this script made
for id in $(grep -o '"id":"[0-9a-f-]\{36\}"' "$SAMPLE" | head -50 | sed 's/"id":"\(.*\)"/\1/' | sort -u); do
  curl -s -o /dev/null "${A[@]}" -X DELETE "$BASE/api/houses/$id"
done
curl -s -o /dev/null "${A[@]}" -X DELETE "$BASE/api/houses/$HOUSE"
req "delete the test record" 204 "${A[@]}" -X DELETE "$BASE/api/records/smoketest/s1"
[ -n "${DEVID:-}" ] && req "revoke the test device" 204 -b "$TMP/jar" -H 'X-Doorprints-Owner: 1' -X POST "$BASE/owner/api/devices/$DEVID/revoke"

echo; [ "$fail" = 0 ] && echo "All checks passed." || echo "Some checks failed."
exit "$fail"
