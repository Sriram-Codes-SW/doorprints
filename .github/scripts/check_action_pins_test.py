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

"""Tests for check-action-pins.py: an unpinned third-party action and a workflow with no permissions are found."""

import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("pins", Path(__file__).with_name("check-action-pins.py"))
pins = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pins)

SHA = "a" * 40


class ActionPins(unittest.TestCase):
    def test_a_tag_on_a_third_party_action_is_found(self):
        found = pins.problems_in("w.yml", "permissions: {}\njobs:\n  j:\n    steps:\n      - uses: some/action@v1\n")
        self.assertEqual(["w.yml:5: some/action@v1 is not pinned to a commit SHA"], found)

    def test_a_sha_a_first_party_tag_and_a_local_action_pass(self):
        text = f"permissions: {{}}\nsteps:\n  - uses: some/action@{SHA} # v1\n  - uses: actions/checkout@v7\n  - uses: ./local\n"
        self.assertEqual([], pins.problems_in("w.yml", text))

    def test_a_short_sha_is_not_enough(self):
        self.assertEqual(1, len(pins.problems_in("w.yml", "permissions: {}\n- uses: some/action@abcdef1\n")))

    def test_a_workflow_without_permissions_is_found(self):
        self.assertEqual(["w.yml: no top-level permissions"], pins.problems_in("w.yml", "on: push\n"))


if __name__ == "__main__":
    unittest.main()
