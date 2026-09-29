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

"""The release security gate's licence scan (S4b-SEC-1; docs/07 A.5 item 5): every runtime dependency of the backend,
as the CycloneDX SBOM lists them, must carry a licence that can be combined with `AGPL-3.0-only`, the project's licence
(docs/10 §12.6, NOTICE).

Usage: licence-check.py <bom.json> <LICENSE file> [<reviewed exceptions JSON>]

A component passes when one of its licences (a dual-licensed component lists each choice; an SPDX expression's OR
alternatives count the same way) is on ALLOWED, or when it is in the reviewed exceptions file ({"<group>:<name>":
"<who decided, when, why>"}). Everything else is listed.

Blocking when the repository's LICENSE is the AGPL (since 2026-09-29); with any other LICENSE the findings are
GitHub warnings only (the question is then "could we move to the AGPL?", which is for the owner).
"""
import json
import re
import sys

# Licences the FSF lists as compatible with GPL-3.0 (and so with AGPL-3.0) as inbound licences, by SPDX id.
# GPL-2.0-with-classpath-exception: the exception lets a program use the library under its own terms (as with the
# JDK); the library itself stays GPL-2.0.
ALLOWED = {
    "0BSD", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "BSL-1.0", "CC0-1.0", "ISC", "MIT", "MIT-0",
    "PostgreSQL", "Python-2.0", "Unlicense", "Zlib", "UPL-1.0", "MPL-2.0",
    "LGPL-2.1-only", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
    "GPL-3.0-only", "GPL-3.0-or-later", "AGPL-3.0-only", "AGPL-3.0-or-later",
    "GPL-2.0-with-classpath-exception", "Classpath-exception-2.0",
    # Not GPL-compatible on their own, but NOTICE's section 7 permission (owner, 2026-09-29) lets Doorprints' own
    # code be combined with them.
    "EPL-1.0", "EPL-2.0",
}


def choices(component):
    """Each licence the component may be used under: its listed licences, and the OR alternatives of an expression."""
    out = []
    for entry in component.get("licenses") or []:
        if "license" in entry:
            lic = entry["license"]
            out.append(lic.get("id") or "name:" + lic.get("name", "?"))
        elif "expression" in entry:
            expr = entry["expression"].strip("() ")
            # "A OR B" offers a choice; "A AND B" needs both, so it passes only when every part is allowed.
            for alternative in re.split(r"\s+OR\s+", expr):
                parts = [p.strip("() ") for p in re.split(r"\s+AND\s+", alternative)]
                parts = [p.replace(" WITH Classpath-exception-2.0", "-with-classpath-exception") for p in parts]
                out.append(parts[0] if len(parts) == 1 else "+".join(parts))
    return out


def allowed(choice):
    return all(part in ALLOWED for part in choice.split("+"))


def main():
    bom_path, licence_path = sys.argv[1], sys.argv[2]
    exceptions = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else {}
    agpl = "GNU AFFERO GENERAL PUBLIC LICENSE" in open(licence_path, encoding="utf-8").read().upper()
    components = json.load(open(bom_path)).get("components", [])
    problems = []
    for c in components:
        key = f"{c.get('group', '')}:{c.get('name', '?')}"
        options = choices(c)
        if key in exceptions or any(allowed(o) for o in options):
            continue
        problems.append((key, c.get("version", "?"), options or ["no licence listed"]))
    level = "error" if agpl else "warning"
    for key, version, options in problems:
        print(f"::{level}::licence not compatible with AGPL-3.0-only, or not recognised: {key} {version}: "
              f"{' | '.join(options)}")
    print(f"{len(components)} components, {len(problems)} to review; LICENSE is "
          f"{'the AGPL: blocking' if agpl else 'not yet the AGPL: warnings only'}.")
    return 1 if agpl and problems else 0


if __name__ == "__main__":
    sys.exit(main())
