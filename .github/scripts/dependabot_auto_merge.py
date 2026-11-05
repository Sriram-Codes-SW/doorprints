#!/usr/bin/env python3
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

"""Dependabot auto-merge (docs/07 section 3.3): turn on auto-merge for the few Dependabot updates that CI alone can judge.

Two modes, both run by .github/workflows/dependabot-auto-merge.yml from main's copy of this file:

  enable   One Dependabot pull request: decide(), and when it passes, `gh pr merge --auto --squash`. GitHub then merges it
           by itself the moment the ruleset's required checks are green. A refusal is not a failure (exit 0, reason printed).
  rebase   After a push to main: ask Dependabot (`@dependabot rebase`) to rebase each open Dependabot pull request that has
           auto-merge on and has fallen behind main. The ruleset wants a current branch, and a push made with the workflow's
           own token would not start CI, so the rebase is Dependabot's own push.

The allowlist is deliberately small: a digest-only move of the same Docker tag in the two backend Dockerfiles, and a
minor or patch step of the user guide's pip packages. Everything else (npm, Gradle, Maven, GitHub Actions, majors, security
groups) stays for a person. Nothing is read from the pull request's own files: only its metadata, its commits and the list of
changed paths, as data.
"""

import json
import os
import re
import subprocess
import sys

BOT = "dependabot[bot]"
UPDATE_TYPE = re.compile(r"^\s*update-type:\s*version-update:semver-(major|minor|patch)\s*$", re.MULTILINE)
DEPENDENCY_BLOCK = re.compile(r"^updated-dependencies:", re.MULTILINE)

# ecosystem (the second segment of the branch name dependabot/<ecosystem>/...) -> (allowed update types, allowed paths)
# Docker has no semver step in a digest-only move: the commit message carries no update-type line at all.
POLICY = {
    "docker": (set(), re.compile(r"^backend/(db/)?Dockerfile$")),
    "pip": ({"minor", "patch"}, re.compile(r"^guide/requirements\.txt$")),
}


def decide(pr: dict, commits: list[dict], files: list[str]) -> tuple[bool, str]:
    """Return (ok, reason). `pr` holds user, head_ref, head_repo, base_repo, draft; each commit has author and message."""
    if pr.get("user") != BOT:
        return False, "not opened by Dependabot"
    if pr.get("draft"):
        return False, "a draft"
    if pr.get("head_repo") != pr.get("base_repo"):
        return False, "from a fork"
    parts = str(pr.get("head_ref", "")).split("/")
    if len(parts) < 3 or parts[0] != "dependabot":
        return False, "the branch name is not dependabot/<ecosystem>/..."
    ecosystem = parts[1]
    if ecosystem not in POLICY:
        return False, f"the {ecosystem} ecosystem is not on the allowlist"
    if not commits:
        return False, "no commits"
    for commit in commits:
        if commit.get("author") != BOT:
            return False, "a commit on the branch is not Dependabot's"
        if not DEPENDENCY_BLOCK.search(commit.get("message", "")):
            return False, "a commit has no updated-dependencies block, so the update type cannot be read"
    allowed_types, allowed_paths = POLICY[ecosystem]
    found = {m for c in commits for m in UPDATE_TYPE.findall(c["message"])}
    if not found <= allowed_types:
        return False, "update type " + "/".join(sorted(found - allowed_types)) + " is not allowed for " + ecosystem
    if not files:
        return False, "no changed files"
    stray = [f for f in files if not allowed_paths.match(f)]
    if stray:
        return False, "changes outside the allowed paths: " + ", ".join(stray[:3])
    return True, f"{ecosystem}: " + (", ".join(sorted(found)) if found else "digest-only") + " within the allowlist"


def behind_with_auto_merge(prs: list[dict]) -> list[int]:
    """Numbers of the open pull requests that have auto-merge on and are behind the base branch."""
    return [p["number"] for p in prs if p.get("autoMergeRequest") and p.get("mergeStateStatus") == "BEHIND"]


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def api_list(path: str) -> list:
    out = gh("api", "--paginate", "--slurp", path)
    return [item for page in json.loads(out) for item in page]


def enable(repo: str, number: str) -> int:
    raw = json.loads(gh("api", f"repos/{repo}/pulls/{number}"))
    pr = {
        "user": raw["user"]["login"],
        "head_ref": raw["head"]["ref"],
        "head_repo": (raw["head"].get("repo") or {}).get("full_name"),
        "base_repo": raw["base"]["repo"]["full_name"],
        "draft": raw.get("draft", False),
    }
    commits = [
        {"author": (c.get("author") or {}).get("login"), "message": c["commit"]["message"]}
        for c in api_list(f"repos/{repo}/pulls/{number}/commits")
    ]
    files = [f["filename"] for f in api_list(f"repos/{repo}/pulls/{number}/files")]
    ok, reason = decide(pr, commits, files)
    print(f"#{number}: {'auto-merge on' if ok else 'left for a person'}: {reason}")
    if ok:
        gh("pr", "merge", number, "--repo", repo, "--auto", "--squash")
    return 0


def rebase(repo: str) -> int:
    prs = json.loads(gh("pr", "list", "--repo", repo, "--author", "app/dependabot", "--state", "open",
                        "--json", "number,autoMergeRequest,mergeStateStatus"))
    for number in behind_with_auto_merge(prs):
        print(f"#{number}: behind main with auto-merge on, asking Dependabot to rebase")
        gh("pr", "comment", str(number), "--repo", repo, "--body", "@dependabot rebase")
    return 0


def main(argv: list[str]) -> int:
    repo = os.environ["REPO"]
    if len(argv) > 1 and argv[1] == "enable":
        return enable(repo, os.environ["PR_NUMBER"])
    if len(argv) > 1 and argv[1] == "rebase":
        return rebase(repo)
    print("usage: dependabot_auto_merge.py enable|rebase (REPO, PR_NUMBER and GH_TOKEN in the environment)")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
