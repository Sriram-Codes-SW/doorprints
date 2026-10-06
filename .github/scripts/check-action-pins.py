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

"""SEC-030: every third-party GitHub Action in the workflows is pinned to a full commit SHA. Actions from the `actions/`
and `github/` organisations (GitHub's own) and local ones (`./`) may use a version tag. Every workflow must also declare
`permissions` at its top level.

Usage: check-action-pins.py [workflow directory] (default .github/workflows). Exit 1 and a list when something is off.
"""

import re
import sys
from pathlib import Path

USES = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)")
FIRST_PARTY = ("actions/", "github/", "./")
SHA = re.compile(r"@[0-9a-f]{40}$")


def problems_in(name: str, text: str) -> list[str]:
    found = []
    for number, line in enumerate(text.splitlines(), 1):
        match = USES.match(line)
        if not match:
            continue
        ref = match.group(1)
        if ref.startswith(FIRST_PARTY) or ref.startswith("docker://") and "@sha256:" in ref:
            continue
        if not SHA.search(ref):
            found.append(f"{name}:{number}: {ref} is not pinned to a commit SHA")
    if not re.search(r"^permissions:", text, re.MULTILINE):
        found.append(f"{name}: no top-level permissions")
    return found


def main(argv: list[str]) -> int:
    directory = Path(argv[1]) if len(argv) > 1 else Path(".github/workflows")
    problems: list[str] = []
    files = sorted(directory.glob("*.yml"))
    for path in files:
        problems += problems_in(path.name, path.read_text(encoding="utf-8"))
    for problem in problems:
        print(problem)
    print(f"{len(files)} workflow files checked, {len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
