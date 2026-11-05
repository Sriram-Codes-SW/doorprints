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

"""Tests for dependabot_auto_merge.py: the allowlist lets a digest-only Docker move and a minor or patch step of the guide's
pip packages through, and nothing else; the rebase mode names only pull requests that are behind with auto-merge on."""

import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("auto", Path(__file__).with_name("dependabot_auto_merge.py"))
auto = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auto)

DOCKER_MSG = (
    "chore(deps): bump postgis/postgis in /backend/db\n\nBumps postgis/postgis from `60f6ad1` to `e84455a`.\n\n---\n"
    "updated-dependencies:\n- dependency-name: postgis/postgis\n  dependency-version: 18-3.6\n"
    "  dependency-type: direct:production\n...\n\nSigned-off-by: dependabot[bot] <support@github.com>"
)


def pip_msg(update_type: str) -> str:
    return (
        "chore(deps): bump mkdocs-material in /guide in the guide-pip group\n\n---\nupdated-dependencies:\n"
        "- dependency-name: mkdocs-material\n  dependency-version: 9.8.0\n  dependency-type: direct:production\n"
        f"  update-type: version-update:semver-{update_type}\n  dependency-group: guide-pip\n...\n"
    )


def pr(ref="dependabot/docker/backend/db/postgis/postgis-e84455a", **over):
    base = {"user": "dependabot[bot]", "head_ref": ref, "head_repo": "o/r", "base_repo": "o/r", "draft": False}
    base.update(over)
    return base


def bot(message):
    return [{"author": "dependabot[bot]", "message": message}]


class DecideTest(unittest.TestCase):
    def test_a_digest_only_docker_move_passes(self):
        ok, reason = auto.decide(pr(), bot(DOCKER_MSG), ["backend/db/Dockerfile"])
        self.assertTrue(ok, reason)
        self.assertTrue(auto.decide(pr(), bot(DOCKER_MSG), ["backend/Dockerfile"])[0])

    def test_a_docker_version_step_does_not(self):
        message = DOCKER_MSG.replace("dependency-type: direct:production\n", "dependency-type: direct:production\n  update-type: version-update:semver-minor\n")
        self.assertFalse(auto.decide(pr(), bot(message), ["backend/Dockerfile"])[0])

    def test_pip_minor_and_patch_pass_major_does_not(self):
        ref = "dependabot/pip/guide/guide-pip-e7dbc60c8d"
        for kind in ("minor", "patch"):
            self.assertTrue(auto.decide(pr(ref), bot(pip_msg(kind)), ["guide/requirements.txt"])[0], kind)
        ok, reason = auto.decide(pr(ref), bot(pip_msg("major")), ["guide/requirements.txt"])
        self.assertFalse(ok)
        self.assertIn("major", reason)

    def test_other_ecosystems_are_left_for_a_person(self):
        for eco in ("npm_and_yarn", "gradle", "maven", "github_actions"):
            ok, reason = auto.decide(pr(f"dependabot/{eco}/x"), bot(pip_msg("patch")), ["guide/requirements.txt"])
            self.assertFalse(ok, eco)
            self.assertIn("allowlist", reason)

    def test_a_file_outside_the_allowed_paths_stops_it(self):
        ok, reason = auto.decide(pr(), bot(DOCKER_MSG), ["backend/Dockerfile", ".github/workflows/release.yml"])
        self.assertFalse(ok)
        self.assertIn("outside the allowed paths", reason)
        self.assertFalse(auto.decide(pr(), bot(DOCKER_MSG), [])[0])

    def test_a_human_commit_on_the_branch_stops_it(self):
        commits = bot(DOCKER_MSG) + [{"author": "someone", "message": "tweak"}]
        self.assertFalse(auto.decide(pr(), commits, ["backend/Dockerfile"])[0])
        self.assertFalse(auto.decide(pr(), [], ["backend/Dockerfile"])[0])

    def test_a_message_without_the_metadata_block_stops_it(self):
        self.assertFalse(auto.decide(pr(), bot("chore(deps): bump x"), ["backend/Dockerfile"])[0])

    def test_the_pull_request_itself_must_be_dependabots_in_this_repository(self):
        files = ["backend/Dockerfile"]
        self.assertFalse(auto.decide(pr(user="someone"), bot(DOCKER_MSG), files)[0])
        self.assertFalse(auto.decide(pr(draft=True), bot(DOCKER_MSG), files)[0])
        self.assertFalse(auto.decide(pr(head_repo="fork/r"), bot(DOCKER_MSG), files)[0])
        self.assertFalse(auto.decide(pr("feature/docker/x"), bot(DOCKER_MSG), files)[0])
        self.assertFalse(auto.decide(pr("dependabot/docker"), bot(DOCKER_MSG), files)[0])


class RebaseTest(unittest.TestCase):
    def test_only_behind_pull_requests_with_auto_merge_are_named(self):
        prs = [
            {"number": 1, "autoMergeRequest": {"enabledAt": "x"}, "mergeStateStatus": "BEHIND"},
            {"number": 2, "autoMergeRequest": None, "mergeStateStatus": "BEHIND"},
            {"number": 3, "autoMergeRequest": {"enabledAt": "x"}, "mergeStateStatus": "CLEAN"},
            {"number": 4, "autoMergeRequest": {"enabledAt": "x"}, "mergeStateStatus": "BLOCKED"},
        ]
        self.assertEqual(auto.behind_with_auto_merge(prs), [1])
        self.assertEqual(auto.behind_with_auto_merge([]), [])


if __name__ == "__main__":
    unittest.main()
