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
"""Every source file carries the copyright notice and the licence notice, as the FSF asks ("How to Use GNU Licenses
for Your Own Software": give each file the proper copyright notices and put a license notice in each file; docs/10
§12.6).

Usage: licence-headers.py --check   lists the tracked source files without the notice and exits 1 if there are any
       licence-headers.py --fix     adds the notice to them

Which files: the tracked files whose type takes a comment (SOURCE below), minus SKIP. The notice goes first, after
only a line that has to stay first (a shebang, an XML declaration, an HTML doctype, a Dockerfile parser directive).
Data, images, docs and generated files are covered by NOTICE instead.
"""
import re
import subprocess
import sys

HOLDER = "Sriram (Sriram-Codes-SW)"
YEAR = "2026"
MARKER = "SPDX-License-Identifier: AGPL-3.0-only"

NOTICE_LINES = [
    f"Copyright {YEAR} {HOLDER}",
    "",
    "This file is part of Doorprints.",
    "",
    "Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General",
    "Public License as published by the Free Software Foundation, version 3 of the License.",
    "",
    "Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied",
    "warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more",
    "details.",
    "",
    "You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;",
    "the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.",
    "",
    MARKER,
]

# Files that also hold material from another project: its notice follows ours (the FSF: copy their notices too).
EXTRA = {
    "android/ui/src/commonTest/kotlin/app/doorprints/ui/LibertyExcerpt.kt": "LIBERTY",
    "web/src/app/shared/testing/liberty-style.fixture.ts": "LIBERTY",
}
EXTRA_LINES = {
    "LIBERTY": [
        "",
        "The style JSON in this file is an excerpt of OpenFreeMap's Liberty style (MIT License,",
        "https://github.com/hyperknot/openfreemap-styles), a fork of OSM Liberty (https://github.com/maputnik/osm-liberty),",
        "which derives from OSM Bright of the Mapbox Open Styles: copyright (c) 2014, Mapbox, all rights reserved, under",
        "the BSD licence. Their licence texts are in the file NOTICE, section \"Third-party material\".",
    ],
}

BLOCK = ("/*", " *", " */")      # C-style block comment
HASH = (None, "#", None)         # one # per line
MARKUP = ("<!--", "  ", "-->")   # XML and HTML

SOURCE = {
    ".kt": BLOCK, ".kts": BLOCK, ".java": BLOCK, ".ts": BLOCK, ".mts": BLOCK, ".js": BLOCK, ".mjs": BLOCK, ".cjs": BLOCK,
    ".swift": BLOCK, ".css": BLOCK, ".strings": BLOCK,
    ".sh": HASH, ".py": HASH, ".yml": HASH, ".yaml": HASH, ".toml": HASH, ".properties": HASH, ".in": HASH,
    ".xml": MARKUP, ".html": MARKUP,
}
NAMED = {"Dockerfile": HASH}

SKIP = [
    re.compile(r"^\.claude/"),
    re.compile(r"(^|/)gradle-wrapper\.properties$"),   # written by `gradle wrapper`
    re.compile(r"(^|/)db/migration/.*\.sql$"),         # not a SOURCE type anyway; Flyway checksums the applied files
    re.compile(r"\.plist$"),                           # Xcode rewrites plists and drops comments
]


def style_for(path):
    name = path.rsplit("/", 1)[-1]
    if name in NAMED:
        return NAMED[name]
    for ext, style in SOURCE.items():
        if name.endswith(ext):
            return style
    return None


def tracked():
    out = subprocess.run(["git", "ls-files", "-z"], check=True, capture_output=True).stdout.decode()
    return [p for p in out.split("\0") if p and style_for(p) and not any(s.search(p) for s in SKIP)]


def has_notice(text):
    return MARKER in "\n".join(text.splitlines()[:40])


def render(style, lines):
    start, mid, end = style
    body = [(mid + (" " + line if line else "")).rstrip() for line in lines]
    return "\n".join(([start] if start else []) + body + ([end] if end else [])) + "\n"


def keep_first(path, lines):
    """How many leading lines must stay above the notice."""
    if not lines:
        return 0
    first = lines[0]
    if first.startswith("#!") or first.startswith("<?xml") or re.match(r"(?i)<!doctype", first):
        return 1
    if path.rsplit("/", 1)[-1] == "Dockerfile":
        n = 0
        while n < len(lines) and re.match(r"#\s*(syntax|escape|check)=", lines[n]):
            n += 1
        return n
    return 0


def add_notice(path):
    text = open(path, encoding="utf-8").read()
    if has_notice(text):
        return False
    lines = text.splitlines(keepends=True)
    n = keep_first(path, lines)
    notice = render(style_for(path), NOTICE_LINES + EXTRA_LINES.get(EXTRA.get(path), []))
    head = "".join(lines[:n])
    if head and not head.endswith("\n"):
        head += "\n"
    rest = "".join(lines[n:])
    sep = "" if rest.startswith("\n") or not rest else "\n"
    open(path, "w", encoding="utf-8").write(head + notice + sep + rest)
    return True


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "--check"
    files = tracked()
    missing = [p for p in files if not has_notice(open(p, encoding="utf-8").read())]
    if mode == "--fix":
        for p in missing:
            add_notice(p)
        print(f"{len(files)} source files; notice added to {len(missing)}.")
        return 0
    for p in missing:
        print(f"::error file={p}::no copyright and licence notice (run .github/scripts/licence-headers.py --fix)")
    print(f"{len(files)} source files, {len(missing)} without the notice.")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
