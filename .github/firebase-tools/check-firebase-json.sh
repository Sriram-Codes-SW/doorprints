#!/usr/bin/env bash
# Checks web/firebase.json against the deploy rules of .github/workflows/web.yml before any credential exists.
# Owner: DevSecOps (.github/**). The checked file belongs to the Web team; this gate is what DevSecOps enforces on it.
#
# Usage: check-firebase-json.sh <path to firebase.json> [expected Hosting site ID]
#   Without a site ID (a pull request from a fork gets no repository variables) the site is only format-checked.
#
# Rules (Firebase Hosting plan, 2026-09-23, PART 2 section 2.4). The job fails when:
#   - the file is not plain JSON (firebase-tools also accepts comments; this gate does not);
#   - any top-level key other than "hosting" is present (no functions, firestore, storage, emulators ...);
#   - "hosting" is not a single object (no multi-site array);
#   - hosting.public is not exactly "dist/web/browser". google-github-actions/auth writes its credential file
#     gha-creds-*.json into the workspace root, so a public directory that contains the workspace root would publish it;
#   - hosting has "source" or "frameworksBackend" (web frameworks: firebase-tools would build the app itself) or
#     "target" (resolved through a .firebaserc, which this repository does not have);
#   - hosting.site differs from the expected site ID (repository variable FIREBASE_SITE_ID);
#   - a "predeploy" or "postdeploy" key appears anywhere: firebase-tools runs them as shell commands
#     (src/deploy/lifecycleHooks.ts), which would execute next to the deploy credential;
#   - a rewrite has "function", "run" or "dynamicLinks", or a "destination" other than "/index.html".
#
# Change log
# 2026-09-23 (DevSecOps): first version, for the Firebase Hosting deploy (web.yml jobs firebase-config and
#   deploy-firebase).
set -euo pipefail

file="${1:?usage: check-firebase-json.sh <firebase.json> [expected site id]}"
expected_site="${2:-}"
id_pattern='^[a-z0-9][a-z0-9-]{0,29}$'
errors=0

fail() {
  echo "::error title=web/firebase.json::$1"
  errors=$((errors + 1))
}

if [ ! -f "$file" ]; then
  echo "::error title=web/firebase.json::${file} does not exist. The Web team keeps the Hosting configuration in web/firebase.json."
  exit 1
fi
if ! jq -e 'type == "object"' "$file" > /dev/null 2>&1; then
  echo "::error title=web/firebase.json::${file} is not a plain JSON object (comments are not allowed here, although firebase-tools would accept them)."
  exit 1
fi

extra=$(jq -r '[keys[] | select(. != "hosting")] | join(", ")' "$file")
[ -z "$extra" ] || fail "only the key \"hosting\" is allowed at the top level; found also: ${extra}"

hosting_type=$(jq -r '.hosting | type' "$file")
if [ "$hosting_type" != "object" ]; then
  fail "\"hosting\" must be a single object, found ${hosting_type}"
  echo "${errors} problem(s) in ${file}."
  exit 1
fi

public=$(jq -r '.hosting.public // "(unset)" | tostring' "$file")
[ "$public" = "dist/web/browser" ] || fail "hosting.public is '${public}', must be exactly 'dist/web/browser' (the downloaded build; never a directory that contains the workspace root, where the deploy credential file lives)"

forbidden=$(jq -r '[.hosting | keys[] | select(. == "source" or . == "frameworksBackend" or . == "target")] | join(", ")' "$file")
[ -z "$forbidden" ] || fail "hosting must not have: ${forbidden} (use \"site\" and \"public\" only; no web-frameworks build, no .firebaserc targets)"

site=$(jq -r '.hosting.site // "" | tostring' "$file")
if [ -z "$site" ]; then
  fail "hosting.site is missing; it must name the Hosting site (repository variable FIREBASE_SITE_ID)"
elif ! [[ "$site" =~ $id_pattern ]]; then
  fail "hosting.site '${site}' is not a valid site ID (${id_pattern})"
elif [ -n "$expected_site" ] && [ "$site" != "$expected_site" ]; then
  fail "hosting.site is '${site}', but the repository variable FIREBASE_SITE_ID is '${expected_site}'"
elif [ -z "$expected_site" ]; then
  echo "::notice title=web/firebase.json::FIREBASE_SITE_ID is not available to this run; hosting.site '${site}' was only format-checked."
fi

hooks=$(jq -r '[.. | objects | keys[] | select(. == "predeploy" or . == "postdeploy")] | unique | join(", ")' "$file")
[ -z "$hooks" ] || fail "lifecycle hooks are not allowed (${hooks}): firebase-tools runs them as shell commands next to the deploy credential"

rewrites_type=$(jq -r '.hosting.rewrites // [] | type' "$file")
if [ "$rewrites_type" != "array" ]; then
  fail "hosting.rewrites must be an array, found ${rewrites_type}"
else
  bad_rewrites=$(jq -r '
    [ (.hosting.rewrites // []) | to_entries[]
      | select((.value | type) != "object"
               or (.value | has("function") or has("run") or has("dynamicLinks"))
               or (.value.destination != "/index.html"))
      | "#\(.key) \(.value | tojson)" ] | join("; ")' "$file")
  [ -z "$bad_rewrites" ] || fail "only rewrites to \"/index.html\" are allowed (no function, run or dynamicLinks): ${bad_rewrites}"
fi

if [ "$errors" -gt 0 ]; then
  echo "${errors} problem(s) in ${file}; nothing is deployed from it."
  exit 1
fi

rewrites=$(jq -r '.hosting.rewrites // [] | length' "$file")
headers=$(jq -r '.hosting.headers // [] | length' "$file")
echo "ok: ${file}: site '${site}', public '${public}', ${rewrites} rewrite(s) to /index.html, ${headers} header rule(s), no lifecycle hooks."
