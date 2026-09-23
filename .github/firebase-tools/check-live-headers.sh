#!/usr/bin/env bash
# Checks, after a deploy, that the live Firebase Hosting site serves this build with the security headers and the
# caching rules from web/firebase.json (docs/02 RR-11 / F-31; Firebase Hosting plan PART 2 section 2.9).
# Owner: DevSecOps (.github/**).
#
# Usage: check-live-headers.sh <site URL, e.g. https://doorprints.web.app> <local build directory>
#
# What is checked (every problem is reported before the script fails):
#   /  and the deep link /compare (answered by the "**" -> /index.html rewrite): HTTP 200, HTML, the app shell, a
#     Content-Security-Policy with frame-ancestors 'none', X-Frame-Options DENY, X-Content-Type-Options nosniff,
#     Referrer-Policy, Permissions-Policy, Cross-Origin-Opener-Policy, and a Cache-Control with no-cache (Firebase's
#     own default would be max-age=3600, which would keep an old app shell in browsers for an hour).
#   /sw.js and /manifest.webmanifest: HTTP 200, Cache-Control with no-cache and without immutable, and the right
#     Content-Type (JavaScript; application/manifest+json). The Content-Type also proves that the file itself was
#     served: a missing file would be answered by the rewrite with index.html and HTTP 200.
#   The hashed main-*.js that this build's index.html loads: HTTP 200 and a JavaScript Content-Type. The live "/"
#     must reference that same file, which proves that this deploy, not the previous release, is being served.
#   Strict-Transport-Security: reported, and only a warning when missing. The whole .app TLD is HSTS-preloaded in
#     browsers and Firebase may send its own value instead of ours (firebase-tools #5999). Make it a failure again
#     when a custom domain is added.
#
# Timing: Firebase clears its CDN cache on every deploy, so the new release is normally visible at once. Each URL
# is retried every 10 s until it answers as expected, all within one overall deadline (HEADER_CHECK_SECONDS,
# default 300 s), so the job ends with explicit errors, never with a bare job timeout. The last curl error is kept
# and printed.
#
# Change log
# 2026-09-23 (DevSecOps): first version, replacing the Cloudflare Pages header check of web.yml; adds the overall
#   deadline and the printed curl error (coordinator review of the Cloudflare job).
set -uo pipefail # no errexit: every problem is collected before the script fails

base="${1:?usage: check-live-headers.sh <site URL> <build dir>}"
base="${base%/}"
build="${2:?usage: check-live-headers.sh <site URL> <build dir>}"
deadline=$(($(date +%s) + ${HEADER_CHECK_SECONDS:-300}))
work=$(mktemp -d)
errors=0

fail() {
  echo "::error title=Security headers::$1"
  errors=$((errors + 1))
}
warn() { echo "::warning title=Security headers::$1"; }

# header <file> <name>: value of the last such header (case-insensitive), or nothing.
header() {
  { grep -i "^$2:" "$1" || true; } | tail -n 1 | cut -d: -f2- | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

# fetch <path> <name> [text the body must contain]: GET ${base}<path> until it answers HTTP 200 (and, if given,
# the body contains the text) or the deadline has passed. Leaves $work/<name>.h (headers, CR removed),
# $work/<name>.b (body) and $work/<name>.e (curl's last error); prints the final HTTP status.
fetch() {
  local url="${base}$1" out="${work}/$2" want="${3:-}" status
  while :; do
    : > "${out}.raw"
    status=$(curl -sS --proto '=https' --max-time 20 -H 'Cache-Control: no-cache' \
      -D "${out}.raw" -o "${out}.b" -w '%{http_code}' "$url" 2> "${out}.e") || status=${status:-000}
    if [ "$status" = "200" ] && { [ -z "$want" ] || grep -qF -- "$want" "${out}.b"; }; then
      break
    fi
    [ "$(date +%s)" -ge "$deadline" ] && break
    sleep 10
  done
  tr -d '\r' < "${out}.raw" > "${out}.h"
  echo "${status:-000}"
}

# last_error <name>: curl's last error message for a request, if any.
last_error() {
  local msg
  msg=$(tail -n 1 "${work}/$1.e" 2> /dev/null || true)
  [ -n "$msg" ] && printf ' (%s)' "$msg"
}

# expect_no_cache <name> <path>
expect_no_cache() {
  local cache
  cache=$(header "${work}/$1.h" cache-control)
  printf '%s' "$cache" | grep -qi 'no-cache' || fail "$2: Cache-Control is '${cache:-missing}', expected no-cache"
  if printf '%s' "$cache" | grep -qi 'immutable'; then
    fail "$2: Cache-Control '${cache}' contains immutable"
  fi
}

if [ ! -f "${build}/index.html" ]; then
  echo "::error title=Security headers::no index.html in ${build}; the downloaded build is missing."
  exit 1
fi

# The build's own entry script, e.g. main-ABCD1234.js. It is both the "this release is live" marker and the hashed
# file whose Content-Type is checked.
main_js=$(grep -oE 'main-[A-Za-z0-9_-]+\.js' "${build}/index.html" | head -n 1 || true)
if [ -z "$main_js" ]; then
  main_js=$(find "$build" -maxdepth 1 -type f -name '*-*.js' ! -name 'sw.js' -printf '%f\n' | sort | head -n 1)
  warn "index.html names no main-*.js; checking ${main_js:-no hashed file} instead, without the release check."
  marker=""
else
  marker="$main_js"
fi

echo "Checking ${base} (deadline $(date -u -d "@${deadline}" +%H:%M:%SZ))"

# 1. App shell and a deep link.
for path in / /compare; do
  name=page$(printf '%s' "$path" | tr -c '[:lower:]' '_')
  status=$(fetch "$path" "$name" "$marker")
  h="${work}/${name}.h"
  echo "--- ${base}${path} (HTTP ${status})"
  grep -iE '^(content-type|cache-control|content-security-policy|strict-transport-security|x-content-type-options|x-frame-options|referrer-policy|permissions-policy|cross-origin-opener-policy):' "$h" || true
  if [ "$status" != "200" ]; then
    fail "${path} answered HTTP ${status}, expected 200$(last_error "$name")"
    continue
  fi
  if [ -n "$marker" ] && ! grep -qF -- "$marker" "${work}/${name}.b"; then
    fail "${path} does not load ${marker}: the live site still serves another release"
  fi
  grep -qi '<app-root' "${work}/${name}.b" || fail "${path}: the body is not the app shell (no <app-root>)"
  ctype=$(header "$h" content-type)
  printf '%s' "$ctype" | grep -qi '^text/html' || fail "${path}: Content-Type is '${ctype:-missing}', expected text/html"
  csp=$(header "$h" content-security-policy)
  if [ -z "$csp" ]; then
    fail "${path}: no Content-Security-Policy header"
  else
    case "$csp" in
      *"frame-ancestors 'none'"*) ;;
      *) fail "${path}: Content-Security-Policy has no frame-ancestors 'none'" ;;
    esac
  fi
  xfo=$(header "$h" x-frame-options)
  printf '%s' "$xfo" | grep -qix 'deny' || fail "${path}: X-Frame-Options is '${xfo:-missing}', expected DENY"
  xcto=$(header "$h" x-content-type-options)
  printf '%s' "$xcto" | grep -qix 'nosniff' || fail "${path}: X-Content-Type-Options is '${xcto:-missing}', expected nosniff"
  for hname in referrer-policy permissions-policy cross-origin-opener-policy; do
    [ -n "$(header "$h" "$hname")" ] || fail "${path}: no ${hname} header (web/firebase.json sets it)"
  done
  expect_no_cache "$name" "$path"
  hsts=$(header "$h" strict-transport-security)
  if ! printf '%s' "$hsts" | grep -qi 'max-age=[1-9]'; then
    warn "${path}: Strict-Transport-Security is '${hsts:-missing}'. Warning only on *.web.app (the .app TLD is HSTS-preloaded); make it a failure for a custom domain."
  fi
done

# 2. Service worker and manifest: never cached for long, and served as themselves (not as the rewritten shell).
for path in /sw.js /manifest.webmanifest; do
  name=$(printf '%s' "${path#/}" | tr -c '[:lower:]' '_')
  status=$(fetch "$path" "$name")
  h="${work}/${name}.h"
  ctype=$(header "$h" content-type)
  echo "--- ${base}${path} (HTTP ${status}): Content-Type: ${ctype:-(none)}; Cache-Control: $(header "$h" cache-control)"
  if [ "$status" != "200" ]; then
    fail "${path} answered HTTP ${status}, expected 200$(last_error "$name")"
    continue
  fi
  expect_no_cache "$name" "$path"
  case "$path" in
    /sw.js)
      printf '%s' "$ctype" | grep -qi 'javascript' || fail "/sw.js: Content-Type is '${ctype:-missing}', expected JavaScript (a missing file is answered with index.html)"
      ;;
    /manifest.webmanifest)
      printf '%s' "$ctype" | grep -qi '^application/manifest+json' || fail "/manifest.webmanifest: Content-Type is '${ctype:-missing}', expected application/manifest+json"
      ;;
  esac
done

# 3. One content-hashed file: proves the rewrite does not answer real assets with HTML.
if [ -n "$main_js" ]; then
  status=$(fetch "/${main_js}" hashed)
  ctype=$(header "${work}/hashed.h" content-type)
  echo "--- ${base}/${main_js} (HTTP ${status}): Content-Type: ${ctype:-(none)}; Cache-Control: $(header "${work}/hashed.h" cache-control)"
  if [ "$status" != "200" ]; then
    fail "/${main_js} answered HTTP ${status}, expected 200$(last_error hashed)"
  else
    printf '%s' "$ctype" | grep -qi 'javascript' || fail "/${main_js}: Content-Type is '${ctype:-missing}', expected JavaScript"
  fi
else
  fail "no content-hashed .js file found in ${build}; cannot check that assets are served as files"
fi

rm -rf "$work"
if [ "$errors" -gt 0 ]; then
  echo "${errors} problem(s): ${base} does not serve what web/firebase.json promises (RR-11)."
  exit 1
fi
echo "All security headers are present."
