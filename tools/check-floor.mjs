#!/usr/bin/env node
/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// Floor guard (docs/14 §7, S4b-BL-157). A change must not lower the floor it is checked against. This reads the diff of
// the working tree against the merge-base with origin/main and reports each LOOSENING as `file:line: reason`:
//   - a new test skip or focus (in a test file); a deleted test file; fewer test cases in a file than before;
//   - a new suppression comment or annotation (not in documents); a new empty catch block;
//   - a mutation entry removed from tools/mutations/*.json without one added in the same file;
//   - a larger (or removed) maximumWarning / maximumError in an angular.json budget.
// Tightening is silent. Exit 0: none (or allowed); 1: loosening found; 2: could not run (never read 2 as a pass).
// An allowed loosening: a line `Gate-loosening: <reason>` in the last commit message or in $FLOOR_GUARD_ALLOW makes the
// findings warnings (exit 0); the reason must say why, and the pull request description must repeat it.
//
// It cannot see: a weaker test body, a weaker mutation, a test moved to another file, an untracked file, a skip that
// is not on a line of its own, or a Gate-loosening line in an earlier commit. Plain Node, regex over the diff.
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const TEST_FILE = /(?:\.(?:spec|test)\.[cm]?[jt]sx?|Tests?\.(?:kt|java))$/;
const SKIPS = [ // at the start of a statement, so a word inside a string is not a skip
  /(?:^\s*|[;{]\s*)(?:[\w.]+\.)?(?:it|test|describe)\.(?:skip|only)\b/, /(?:^\s*|[;{]\s*)(?:fit|fdescribe|xit|xdescribe)\(/,
  /(?:^|\s)@(?:Ignore|Disabled)\b/, /\bassume(?:True\(\s*false|False\(\s*true)\b/,
];
const WORDS = ['eslint-disable', '@ts-ignore', '@ts-nocheck', 'nosemgrep', 'NOSONAR', 'nolint'];
const SUPPRESSIONS = [new RegExp('(?:^|[\\s;{}),])(?://|/\\*|#)\\s*(?:' + WORDS.join('|') + ')\\b'), /(?:^|\s)@Suppress\b/];
const CASE = /^\s*(?:(?:it|test)\(|@Test\b)/;
const CATCH_OPEN = /\bcatch\s*(?:\([^)]*\))?\s*\{/;
const BUDGET = /"(maximumWarning|maximumError)"\s*:\s*"?(\d+(?:\.\d+)?)\s*(b|kb|mb|gb)?/i;
const BYTES = { b: 1, kb: 1024, mb: 1024 ** 2, gb: 1024 ** 3 };

/** The diff text as [{ path, deleted, added: [{no, text}], removed: [{no, text}] }]. */
export function parseDiff(text) {
  const files = [];
  let file = null, oldNo = 0, newNo = 0, inHunk = false;
  for (const line of text.split('\n')) {
    if (line.startsWith('diff --git ')) {
      file = { path: line.slice(line.lastIndexOf(' b/') + 3), deleted: false, added: [], removed: [] };
      files.push(file); inHunk = false;
    } else if (!file) continue;
    else if (line.startsWith('deleted file mode')) file.deleted = true;
    else if (line.startsWith('@@')) {
      const m = /^@@ -(\d+)(?:,\d+)? \+(\d+)/.exec(line);
      oldNo = Number(m[1]); newNo = Number(m[2]); inHunk = true;
    } else if (inHunk && line.startsWith('+')) file.added.push({ no: newNo++, text: line.slice(1) });
    else if (inHunk && line.startsWith('-')) file.removed.push({ no: oldNo++, text: line.slice(1) });
  }
  return files;
}

const bytes = (m) => Number(m[2]) * BYTES[(m[3] ?? 'b').toLowerCase()];

/** The loosenings of a diff as [{ file, line, reason }], ordered by file, line and reason. Pure: tested on fixture diffs. */
export function findings(diffText) {
  const out = [];
  for (const f of parseDiff(diffText)) {
    const add = (line, reason) => out.push({ file: f.path, line, reason });
    const doc = f.path.endsWith('.md');
    if (TEST_FILE.test(f.path)) {
      f.added.filter((l) => SKIPS.some((re) => re.test(l.text))).forEach((l) => add(l.no, 'new test skip or focus'));
      if (f.deleted) add(1, 'test file deleted');
      else {
        const gone = f.removed.filter((l) => CASE.test(l.text)), now = f.added.filter((l) => CASE.test(l.text));
        if (gone.length > now.length) add(gone[0].no, `net ${gone.length - now.length} test(s) removed (${gone.length} removed, ${now.length} added)`);
      }
    }
    if (!doc) {
      f.added.filter((l) => SUPPRESSIONS.some((re) => re.test(l.text))).forEach((l) => add(l.no, 'new suppression'));
      f.added.forEach((l, i) => {
        const next = f.added[i + 1];
        if (/\bcatch\s*(?:\([^)]*\))?\s*\{\s*\}/.test(l.text)
          || (CATCH_OPEN.test(l.text) && /\{\s*$/.test(l.text) && next?.no === l.no + 1 && next.text.trim().startsWith('}'))) add(l.no, 'new empty catch block');
      });
    }
    if (/^tools\/mutations\/[^/]+\.json$/.test(f.path)) {
      const gone = f.removed.filter((l) => /"find"\s*:/.test(l.text)), now = f.added.filter((l) => /"find"\s*:/.test(l.text));
      if (gone.length > now.length) add(gone[0].no, `mutation entry removed without a replacement (${gone.length} removed, ${now.length} added)`);
    }
    if (f.path.endsWith('angular.json')) {
      const budgets = (lines) => lines.map((l) => ({ no: l.no, m: BUDGET.exec(l.text) })).filter((b) => b.m);
      const now = budgets(f.added), seen = {};
      for (const old of budgets(f.removed)) { // paired by key, in order
        const key = old.m[1], at = (seen[key] = (seen[key] ?? -1) + 1);
        const next = now.filter((b) => b.m[1] === key)[at];
        if (!next) add(old.no, `budget ${key} removed`);
        else if (bytes(next.m) > bytes(old.m)) add(old.no, `budget ${key} loosened from ${old.m[2]}${old.m[3] ?? ''} to ${next.m[2]}${next.m[3] ?? ''}`);
      }
    }
  }
  return out.sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line || a.reason.localeCompare(b.reason));
}

function main() {
  const git = (...args) => spawnSync('git', ['-c', 'core.quotepath=off', ...args], { encoding: 'utf8', maxBuffer: 1 << 28 });
  const cannot = (why) => { console.error(`floor guard cannot run: ${why}`); process.exit(2); };
  if (git('rev-parse', '--is-inside-work-tree').status !== 0) cannot('not a git repository.');
  if (git('rev-parse', '--verify', '-q', 'origin/main').status !== 0) cannot('no origin/main ref. Fix: git fetch --no-tags origin main:refs/remotes/origin/main');
  const base = git('merge-base', 'HEAD', 'origin/main');
  if (base.status !== 0) cannot('no common history with origin/main (a shallow clone?). Fix: git fetch --unshallow, or fetch more of main.');
  const diff = git('diff', '--unified=0', '--no-color', '--no-ext-diff', '-M', base.stdout.trim());
  if (diff.status !== 0) cannot(`git diff failed: ${diff.stderr.trim()}`);
  const found = findings(diff.stdout);
  const allow = /^Gate-loosening:[ \t]*(\S.*)$/m.exec(`${process.env.FLOOR_GUARD_ALLOW ?? ''}\n${git('log', '-1', '--format=%B').stdout}`);
  if (!found.length) console.log('floor guard: no loosening against origin/main.');
  else if (allow) {
    found.forEach((x) => console.log(`warning: ${x.file}:${x.line}: ${x.reason}`));
    console.log(`allowed by Gate-loosening: ${allow[1].trim()}`);
  } else {
    found.forEach((x) => console.log(`${x.file}:${x.line}: ${x.reason}`));
    console.log(`${found.length} loosening(s) of the floor. Fix them, or put a line "Gate-loosening: <why>" in the last commit message and repeat it in the pull request.`);
    process.exit(1);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) main();
