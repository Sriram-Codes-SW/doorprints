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

"""Tests for check-dockerfile-pins.py (S4b-BL-172). The second part runs the checker's source with one named mutation
at a time and requires the cases to notice it (a gate that cannot fail is not a gate)."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-dockerfile-pins.py")
SOURCE = SCRIPT.read_text(encoding="utf-8")
D = "sha256:" + "ab12" * 16  # 64 hex characters


def load(source: str):
    spec = importlib.util.spec_from_loader("pins_under_test", loader=None)
    module = importlib.util.module_from_spec(spec)
    exec(compile(source, str(SCRIPT), "exec"), module.__dict__)
    return module


pins = load(SOURCE)

# (name, Dockerfile text, expected problems as (line, image))
CASES = [
    ("tag only", "FROM maven:3.9-eclipse-temurin-25 AS build\n", [(1, "maven:3.9-eclipse-temurin-25")]),
    ("tag and digest", f"FROM maven:3.9-eclipse-temurin-25@{D} AS build\n", []),
    ("digest without a tag is allowed", f"FROM postgres@{D}\n", []),
    ("no tag, no digest", "FROM alpine\n", [(1, "alpine")]),
    ("short digest", "FROM alpine:3@sha256:abc123\n", [(1, "alpine:3@sha256:abc123")]),
    ("non-hex digest", "FROM alpine:3@sha256:" + "z" * 64 + "\n", [(1, "alpine:3@sha256:" + "z" * 64)]),
    ("digest not at the end", f"FROM alpine:3@{D}extra\n", [(1, f"alpine:3@{D}extra")]),
    ("sha1 is not a digest here", "FROM alpine:3@sha1:" + "a" * 40 + "\n", [(1, "alpine:3@sha1:" + "a" * 40)]),
    ("scratch", "FROM scratch\n", []),
    ("scratch in capitals", "from SCRATCH\n", []),
    ("stage alias", f"FROM a:1@{D} AS build\nFROM build AS test\nFROM Build\n", []),
    ("alias only after its stage", f"FROM build\nFROM a:1@{D} AS build\n", [(1, "build")]),
    ("platform flag", f"FROM --platform=$BUILDPLATFORM a:1@{D}\nFROM --platform=linux/amd64 b:2\n", [(2, "b:2")]),
    ("registry with a port", f"FROM localhost:5000/a:1@{D}\nFROM localhost:5000/b:1\n", [(2, "localhost:5000/b:1")]),
    ("comments, blanks and other instructions", "# FROM x:1\n\nRUN echo FROM y:2\nCOPY --from=build /a /b\n", []),
    ("indented and lower case", "  from x:1\n", [(1, "x:1")]),
    ("a variable image cannot be pinned", "ARG BASE=x:1\nFROM ${BASE}\n", [(2, "${BASE}")]),
]


def found(module, text: str) -> list[tuple[int, str]]:
    return [(p.line, p.image) for p in module.problems_in("Dockerfile", text)]


def failures(module) -> list[str]:
    return [name for name, text, expected in CASES if found(module, text) != expected]


class DockerfilePins(unittest.TestCase):
    def test_every_case(self):
        for name, text, expected in CASES:
            with self.subTest(name):
                self.assertEqual(expected, found(pins, text))

    def test_the_message_names_file_line_and_image(self):
        problem = pins.problems_in("backend/Dockerfile", "FROM a:1\n")[0]
        self.assertEqual("backend/Dockerfile:1: a:1 has no @sha256:<64 hex> digest", str(problem))

    def test_the_walk_finds_dockerfiles_and_skips_node_modules(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "backend" / "db").mkdir(parents=True)
            (root / "web" / "node_modules" / "x").mkdir(parents=True)
            (root / "backend" / "Dockerfile").write_text("FROM a:1\n")
            (root / "backend" / "db" / "Dockerfile").write_text(f"FROM a:1@{D}\n")
            (root / "backend" / "app.Dockerfile").write_text("FROM c:1\n")
            (root / "web" / "node_modules" / "x" / "Dockerfile").write_text("FROM bad:1\n")
            names = sorted(str(p.relative_to(root)) for p in pins.dockerfiles(root))
            self.assertEqual(["backend/Dockerfile", "backend/app.Dockerfile", "backend/db/Dockerfile"], names)
            self.assertEqual(1, pins.main(["x", str(root)]))

    def test_main_passes_on_pinned_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "Dockerfile").write_text(f"FROM a:1@{D}\n")
            self.assertEqual(0, pins.main(["x", tmp]))


# name -> (text in the checker, replacement). The unmutated source must pass every case; each mutant must fail one.
MUTATIONS = {
    "accepts a tag-only line": ('DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")', 'DIGEST = re.compile(r"")'),
    "accepts a short digest": ("[0-9a-f]{64}$", "[0-9a-f]+$"),
    "accepts a non-hex digest": ("[0-9a-f]{64}$", "[0-9a-z]{64}$"),
    "accepts a digest that is not at the end": ("{64}$", "{64}"),
    "drops the stage alias exemption": ("image.lower() in stages", "False"),
    "does not register aliases": ("stages.add(", "(lambda _: None)("),
    "drops the scratch exemption": ('image.lower() == "scratch"', "False"),
    "forgets the platform flag": ("(?:--\\S+\\s+)*", ""),
    "ignores lower-case from": ("re.IGNORECASE", "0"),
}


class TheCheckerCatchesItsMutations(unittest.TestCase):
    def mutant(self, name):
        old, new = MUTATIONS[name]
        self.assertEqual(1, SOURCE.count(old), f"the mutation {name!r} must match exactly one place in the checker")
        return load(SOURCE.replace(old, new))

    def test_the_unmutated_source_is_clean(self):
        self.assertEqual([], failures(pins))

    def test_every_mutation_is_killed(self):
        for name in MUTATIONS:
            with self.subTest(name):
                self.assertNotEqual([], failures(self.mutant(name)), f"survived: {name}")

    def test_accepting_a_tag_only_line_is_killed_by_the_tag_only_case(self):
        self.assertIn("tag only", failures(self.mutant("accepts a tag-only line")))

    def test_a_malformed_digest_is_killed_by_the_digest_cases(self):
        self.assertIn("short digest", failures(self.mutant("accepts a short digest")))
        self.assertIn("non-hex digest", failures(self.mutant("accepts a non-hex digest")))

    def test_dropping_the_alias_exemption_is_killed_by_the_alias_case(self):
        self.assertIn("stage alias", failures(self.mutant("drops the stage alias exemption")))


if __name__ == "__main__":
    unittest.main()
