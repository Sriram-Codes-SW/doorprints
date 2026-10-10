#!/usr/bin/env python3
"""Resolve the docs version/change-log merge conflicts of the batch PRs.
Usage: resolve-docs.py FILE...   (files with git conflict markers; HEAD = the PR branch, other = main)
Header hunk (| Version | x |): header = max + 1 (last component). Change-log hunk: main's rows first, then ours
renumbered to header+0, +1... Any other hunk: union (main's lines, then ours), reported for review."""
import re, sys
VER = re.compile(r'^\| Version \| (\d+)\.(\d+) \|')
ROW = re.compile(r'^\| (\d+)\.(\d+) \|')
def ver(line, rx):
    m = rx.match(line); return (int(m.group(1)), int(m.group(2)), len(m.group(2))) if m else None
def fmt(maj, mino, width): return f"{maj}.{mino:0{width}d}"
for path in sys.argv[1:]:
    lines = open(path, encoding='utf-8').read().split('\n')
    out, i, hunks = [], 0, []
    while i < len(lines):
        if lines[i].startswith('<<<<<<< '):
            ours, theirs, i = [], [], i + 1
            while not lines[i].startswith('======='): ours.append(lines[i]); i += 1
            i += 1
            while not lines[i].startswith('>>>>>>> '): theirs.append(lines[i]); i += 1
            i += 1
            hunks.append((len(out), ours, theirs)); out.append(None)
        else:
            out.append(lines[i]); i += 1
    # first pass: the header version
    header_v = None
    for idx, ours, theirs in hunks:
        if ours and VER.match(ours[0]) and theirs and VER.match(theirs[0]):
            a, b = ver(ours[0], VER), ver(theirs[0], VER)
            m = max(a[:2], b[:2]); header_v = (m[0], m[1] + 1, a[2])
    res = {}
    nxt = header_v
    for idx, ours, theirs in hunks:
        if ours and VER.match(ours[0]):
            res[idx] = [f"| Version | {fmt(*header_v)} |"]
        elif ours and ROW.match(ours[0]) and header_v:
            rows = list(theirs)
            tmax = max([ver(l, ROW)[:2] for l in theirs if ROW.match(l)] or [(0, 0)])
            cur = max(tmax[1] + 1, header_v[1]) if tmax >= (header_v[0], 0) else header_v[1]
            cur = header_v[1]
            for l in ours:
                m = ROW.match(l)
                rows.append(l if not m else re.sub(r'^\| \d+\.\d+ \|', f"| {fmt(header_v[0], cur, header_v[2])} |", l, count=1))
                cur += 1 if m else 0
            res[idx] = rows
            print(f"{path}: change-log rows renumbered up to {fmt(header_v[0], cur - 1, header_v[2])}")
        else:
            res[idx] = theirs + ours
            print(f"{path}: UNION hunk (review): {len(theirs)} main + {len(ours)} ours lines")
    final = []
    for k, l in enumerate(out):
        if l is None: final.extend(res[k])
        else: final.append(l)
    open(path, 'w', encoding='utf-8').write('\n'.join(final))
