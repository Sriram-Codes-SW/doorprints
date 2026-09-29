#!/usr/bin/env bash
# The release security gate's API scan (S4b-SEC-1, docs/06 TC-S-04): starts the backend image and its database on a
# private Docker network, then runs OWASP ZAP's API scan (passive and active) against every path of the API's OpenAPI
# description, with the API key, so the scan reaches the code behind the key filter. Used by the `image` job of
# .github/workflows/backend.yml; runs the same on a developer machine with Docker.
#
# Usage: api-scan.sh <api image> <db image> <openapi.json> <output dir>
# Fails (exit 1) on any High-risk alert, or when the API does not start; Medium and Low alerts are listed and kept in
# the report (<out>/zap.html, <out>/zap.json) without failing. Everything it starts is removed on exit.
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <api image> <db image> <openapi.json> <output dir>" >&2
  exit 2
fi
api_image=$1
db_image=$2
spec=$3
out=$4
# ZAP 2.17.0, pinned by digest (the `stable` tag on 2026-09-29).
zap_image=ghcr.io/zaproxy/zaproxy:2.17.0@sha256:781a2bdaea47324e7bab583e2263f21d257b0aee61ed51521a5be45f5f5081ef
net=doorprints-scan-$$
# A throwaway key for this run only (never printed): the scan sends it on every request.
key=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')

[ -s "$spec" ] || { echo "::error::no OpenAPI description at $spec"; exit 2; }
mkdir -p "$out"
cp "$spec" "$out/openapi.json"
# ZAP runs as its own user (uid 1000) and writes its reports here.
chmod 777 "$out"

cleanup() {
  docker rm -f "$net-api" "$net-db" >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$net" >/dev/null
docker run -d --name "$net-db" --network "$net" --network-alias db \
  -e POSTGRES_DB=doorprints -e POSTGRES_USER=doorprints -e POSTGRES_PASSWORD=doorprints "$db_image" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$net-db" pg_isready -h 127.0.0.1 -U doorprints -d doorprints >/dev/null 2>&1 && break
  sleep 2
done

# As a deployment runs it (docs/07 section 7), with AI and MCP off; the database is thrown away afterwards, so the
# active scan may write and delete freely. The per-client rate limit is lifted for the scan only: its thousands of
# requests would otherwise get 429s, which ZAP reads as answers to its payloads (the first run's "SQL injection"
# alerts were all 429s). The limiter has its own tests (ai/web/TokenBucketRateLimiterTest, config/ApiKeyFilterTest).
docker run -d --name "$net-api" --network "$net" --network-alias api \
  -e DB_URL=jdbc:postgresql://db:5432/doorprints -e DB_USER=doorprints -e DB_PASSWORD=doorprints \
  -e APP_API_KEY="$key" -e APP_AI_ENABLED=false -e APP_MCP_ENABLED=false \
  -e RATE_LIMIT_PER_MINUTE=1000000 -e RATE_LIMIT_BURST=1000000 "$api_image" >/dev/null
up=0
for _ in $(seq 1 90); do
  if docker exec "$net-db" bash -c 'exec 3<>/dev/tcp/api/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"UP\"" <&3' 2>/dev/null; then
    up=1
    break
  fi
  sleep 2
done
if [ "$up" != 1 ]; then
  echo "::error::the API did not become healthy"
  docker logs "$net-api" 2>&1 | tail -80
  exit 1
fi
echo "API up; scanning."

# -O: the description names the test's random localhost port; scan the container instead. -I: warnings alone do not
# fail the run here (the High check below decides). -T: at most 10 minutes of active scan. The replacer adds the key
# to every request.
set +e
docker run --rm --network "$net" -v "$(cd "$out" && pwd):/zap/wrk:rw" "$zap_image" \
  zap-api-scan.py -t openapi.json -f openapi -O http://api:8080 -I -T 10 \
  -J zap.json -r zap.html \
  -z "-config replacer.full_list(0).description=apikey -config replacer.full_list(0).enabled=true \
-config replacer.full_list(0).matchtype=REQ_HEADER -config replacer.full_list(0).matchstr=X-API-Key \
-config replacer.full_list(0).regex=false -config replacer.full_list(0).replacement=$key"
zap_status=$?
set -e
if [ ! -s "$out/zap.json" ]; then
  echo "::error::ZAP wrote no report (exit $zap_status)"
  docker logs "$net-api" 2>&1 | tail -40
  exit 1
fi

# The threshold (docs/06 section 11): no High-risk alert. Medium and lower are listed for the release review.
python3 - "$out/zap.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
alerts = [a for site in report.get("site", []) for a in site.get("alerts", [])]
risk = {"3": "High", "2": "Medium", "1": "Low", "0": "Informational"}
high = 0
for a in sorted(alerts, key=lambda a: -int(a.get("riskcode", "0"))):
    level = risk.get(a.get("riskcode", "0"), "?")
    print(f"{level:13} {a.get('pluginid', '?'):>6}  {a.get('name', '?')}  ({len(a.get('instances', []))} instances)")
    if a.get("riskcode") == "3":
        high += 1
        print(f"::error::ZAP High-risk alert: {a.get('name')} (rule {a.get('pluginid')})")
print(f"{len(alerts)} alert types, {high} High.")
sys.exit(1 if high else 0)
PY
