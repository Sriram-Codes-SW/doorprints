#!/usr/bin/env python3
"""The release security gate's licence scan (S4b-SEC-1; docs/07 A.5 item 5): every runtime dependency of the backend,
as the CycloneDX SBOM lists them, must carry a licence that can be combined with `AGPL-3.0-only`, the licence the
project is moving to (docs/10 §12.6, docs/14 N4).

Usage: licence-check.py <bom.json> <LICENSE file> [<reviewed exceptions JSON>]

A component passes when one of its licences (a dual-licensed component lists each choice; an SPDX expression's OR
alternatives count the same way) is on ALLOWED, or when it is in the reviewed exceptions file ({"<group>:<name>":
"<who decided, when, why>"}). Everything else is listed.

Blocking only once the repository's LICENSE is the AGPL: until then the licence is still MIT, the scan's question is
"can we move to the AGPL?", and the answer is for the owner, so the findings are GitHub warnings, not a red job.
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
