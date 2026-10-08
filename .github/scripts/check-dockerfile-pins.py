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

"""S4b-BL-172 (SEC-024, the supply chain): every `FROM` in every Dockerfile of the repository names its base image by
digest, `image:tag@sha256:<64 hex>` (the tag stays for the reader; the digest is what is pulled). `FROM scratch` and a
`FROM <stage>` that names an earlier `AS <stage>` of the same file need none. Dependabot's docker ecosystem keeps the
digests current (`.github/dependabot.yml`).

Usage: check-dockerfile-pins.py [repository root] (default the current directory). Exit 1 and a list when something is off.
"""

import os
import re
import sys
from pathlib import Path
from typing import NamedTuple

FROM = re.compile(r"^\s*FROM\s+(?:--\S+\s+)*(\S+)(?:\s+AS\s+(\S+))?", re.IGNORECASE)
DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")
SKIPPED_DIRECTORIES = {".git", "node_modules", "build", "target", ".gradle"}


class Problem(NamedTuple):
    name: str
    line: int
    image: str

    def __str__(self) -> str:
        return f"{self.name}:{self.line}: {self.image} has no @sha256:<64 hex> digest"


def problems_in(name: str, text: str) -> list[Problem]:
    found = []
    stages: set[str] = set()
    for number, line in enumerate(text.splitlines(), 1):
        match = FROM.match(line)
        if not match:
            continue
        image, alias = match.group(1), match.group(2)
        if not (image.lower() == "scratch" or image.lower() in stages or DIGEST.search(image)):
            found.append(Problem(name, number, image))
        if alias:
            stages.add(alias.lower())
    return found


def dockerfiles(root: Path) -> list[Path]:
    paths = []
    for directory, subdirectories, files in os.walk(root):
        subdirectories[:] = [d for d in subdirectories if d not in SKIPPED_DIRECTORIES]
        for file in files:
            if file == "Dockerfile" or file.startswith("Dockerfile.") or file.endswith(".Dockerfile"):
                paths.append(Path(directory) / file)
    return sorted(paths)


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(".")
    problems: list[Problem] = []
    files = dockerfiles(root)
    for path in files:
        problems += problems_in(path.relative_to(root).as_posix(), path.read_text(encoding="utf-8"))
    for problem in problems:
        print(problem)
    print(f"{len(files)} Dockerfiles checked, {len(problems)} problem(s).")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
