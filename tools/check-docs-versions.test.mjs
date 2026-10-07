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

// Tests for tools/check-docs-versions.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { versionProblems, parseVersion, compareVersions } from './check-docs-versions.mjs';

// A small document: header rows from `headers`, then a change log with one row per version (line numbers in the
// expected values below count from the title on line 1: the header table starts on line 3, so a first header row is
// line 5, and with one header row the change-log rows start on line 12).
const doc = (headers, versions) => [
  '# Title', '', '| Field | Value |', '|---|---|', ...headers.map((h) => `| Version | ${h} |`), '| Date | 2026-10-07 |', '',
  '## Change log', '', '| Version | Date | Author | Change |', '|---|---|---|---|',
  ...versions.map((v) => `| ${v} | 2026-10-07 | Claude | change ${v} |`), '',
].join('\n');

test('one header row equal to the top change-log row is fine, whichever way the log runs', () => {
  assert.deepEqual(versionProblems(doc(['0.3'], ['0.1', '0.2', '0.3'])), []);
  assert.deepEqual(versionProblems(doc(['0.3'], ['0.3', '0.2', '0.1'])), []);
});

test('versions compare as numbers per segment: 0.10 is above 0.9', () => {
  assert.deepEqual(versionProblems(doc(['0.10'], ['0.8', '0.9', '0.10'])), []);
  assert.deepEqual(parseVersion('v0.4'), [0, 4]);
  assert.equal(parseVersion('0.4 (draft)'), null);
  assert.ok(compareVersions([0, 10], [0, 9]) > 0);
});

test('three header rows (a hand merge of parallel pull requests) are reported on the second and third', () => {
  assert.deepEqual(versionProblems(doc(['0.3', '0.1', '0.2'], ['0.1', '0.2', '0.3'])), [
    { line: 6, message: 'a second "| Version | 0.1 |" header row (first at line 5). Fix: delete the extra rows and keep one, 0.3.' },
    { line: 7, message: 'a second "| Version | 0.2 |" header row (first at line 5). Fix: delete the extra rows and keep one, 0.3.' },
  ]);
});

test('a header that is not the highest change-log version is reported on the header line', () => {
  assert.deepEqual(versionProblems(doc(['0.2'], ['0.1', '0.2', '0.3'])), [
    { line: 5, message: 'header version 0.2 differs from the highest change-log version 0.3 (line 14). Fix: set the header to 0.3, or add the missing change-log row.' },
  ]);
});

test('a header above the change log is reported too (the row was lost in the merge)', () => {
  const [p] = versionProblems(doc(['0.4'], ['0.1', '0.2', '0.3']));
  assert.match(p.message, /^header version 0.4 differs from the highest change-log version 0.3 \(line 14\)/);
});

test('a repeated change-log version is reported on the later row', () => {
  assert.deepEqual(versionProblems(doc(['0.2'], ['0.1', '0.2', '0.2'])), [
    { line: 14, message: 'change-log version 0.2 repeats line 13. Fix: renumber this row to the next free version, or merge the two rows.' },
  ]);
});

test('0.2 and 0.20 are different versions, not a repeat', () => {
  assert.deepEqual(versionProblems(doc(['0.20'], ['0.2', '0.20'])), []);
});

test('the latest rows swapped (two pull requests appended side by side) are out of order', () => {
  const problems = versionProblems(doc(['0.4'], ['0.1', '0.2', '0.4', '0.3']));
  assert.equal(problems.length, 1);
  assert.deepEqual(problems[0], {
    line: 15,
    message: 'change-log version 0.3 is out of order among the latest rows: 0.4 is on line 14 but the others run oldest first. Fix: move the row so the latest versions follow each other in one direction.',
  });
});

test('old history in the opposite direction does not matter, only the five highest', () => {
  // The five highest (0.5..0.9) run oldest first; the older rows 0.4..0.1 below them run the other way.
  assert.deepEqual(versionProblems(doc(['0.9'], ['0.5', '0.6', '0.7', '0.8', '0.9', '0.4', '0.3', '0.2', '0.1'])), []);
});

test('a version out of order among the five highest is reported even with older rows after it', () => {
  const problems = versionProblems(doc(['0.9'], ['0.5', '0.7', '0.6', '0.8', '0.9', '0.1']));
  assert.equal(problems.length, 1);
  assert.match(problems[0].message, /^change-log version 0\.\d is out of order among the latest rows/);
});

test('a log of one row, or a document without a header row (docs/ai), checks cleanly', () => {
  assert.deepEqual(versionProblems(doc(['0.1'], ['0.1'])), []);
  const ai = '# T\n\n| Version | Date       | Author | Change |\n|---------|------|---|---|\n| v0.1 | 2026-09-22 | A | x |\n| v0.2 | 2026-09-22 | A | y |\n';
  assert.deepEqual(versionProblems(ai), []);
});

test('a change-log version that is not a number is reported', () => {
  const [p] = versionProblems(doc(['0.1'], ['0.1', 'next']));
  assert.deepEqual(p, { line: 13, message: 'change-log version "next" is not a number like 0.12. Fix: write the version as major.minor.' });
});

test('other tables that start with Version, and tables inside code fences, are ignored', () => {
  const text = doc(['0.2'], ['0.1', '0.2'])
    + '\n| Version | File | Changes | Sprint |\n|---|---|---|---|\n| 0.9 | a.md | x | 1 |\n'
    + '\n```\n| Version | 0.7 |\n| Version | Date | Author | Change |\n|---|---|---|---|\n| 0.1 | d | a | c |\n| 0.1 | d | a | c |\n```\n';
  assert.deepEqual(versionProblems(text), []);
});

test('CRLF line endings do not hide a header row', () => {
  assert.equal(versionProblems(doc(['0.1', '0.1'], ['0.1']).replace(/\n/g, '\r\n')).length, 1);
});

// The command line: exit code and the lines it prints, on a temporary directory of documents.
function run(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'docs-versions-'));
  try {
    for (const [name, text] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(dir, name)), { recursive: true });
      fs.writeFileSync(path.join(dir, name), text);
    }
    const script = path.join(path.dirname(new URL(import.meta.url).pathname), 'check-docs-versions.mjs');
    const r = spawnSync(process.execPath, [script, dir], { encoding: 'utf8' });
    return { status: r.status, lines: r.stdout.split('\n').filter(Boolean).map((l) => l.replace(dir + path.sep, '')) };
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test('the command exits 0 and counts the documents when all are fine', () => {
  const r = run({ 'a.md': doc(['0.2'], ['0.1', '0.2']), 'sub/b.md': doc(['0.1'], ['0.1']), 'note.txt': 'ignored' });
  assert.deepEqual(r, { status: 0, lines: ['2 documents checked, 0 problem(s).'] });
});

test('the command exits 1 and prints file:line and a fix per problem', () => {
  const r = run({ 'sub/bad.md': doc(['0.1', '0.2'], ['0.1', '0.2']), 'ok.md': doc(['0.1'], ['0.1']) });
  assert.equal(r.status, 1);
  assert.deepEqual(r.lines, [
    'sub/bad.md:5: header version 0.1 differs from the highest change-log version 0.2 (line 14). Fix: set the header to 0.2, or add the missing change-log row.',
    'sub/bad.md:6: a second "| Version | 0.2 |" header row (first at line 5). Fix: delete the extra rows and keep one, 0.2.',
    '2 documents checked, 2 problem(s).',
  ]);
});
