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


// Tests for tools/check-sprint-status.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { statusProblems } from './check-sprint-status.mjs';

const rows = (...r) => ['| ID | Story | Status |', '|---|---|---|', ...r].join('\n');

test('In review with a pull request number is fine, bold or not, in any cell', () => {
  assert.deepEqual(statusProblems(rows('| S4b-BL-9 | A story | **In review** (#123, `feat/x`) |')), []);
  assert.deepEqual(statusProblems(rows('| S4b-BL-9 | A story | In review (draft PR #123) |')), []);
  assert.deepEqual(statusProblems(rows('| C-02 | A story | *In review (#9):* detail |')), []);
});

test('In review without a pull request number is reported on its line, for every ticket-id shape', () => {
  for (const id of ['S4b-BL-9', 'C-02', 'CMP-0-BL-6', 'S4b-FR-13', 'S4b-BL-174a']) {
    const out = statusProblems(rows(`| ${id} | A story | In review |`));
    assert.equal(out.length, 1, id);
    assert.equal(out[0].line, 3);
    assert.match(out[0].message, new RegExp(`^${id} says "In review" without a pull request number`));
  }
  assert.equal(statusProblems(rows('| S4b-BL-9 | A story | **In review** (branch `feat/x`, draft) |')).length, 1);
  assert.equal(statusProblems(rows('| S4b-BL-9 | In review (the website) | Done |')).length, 1);
});

test('a number elsewhere in the row does not excuse another cell', () => {
  assert.equal(statusProblems(rows('| S4b-BL-9 | See #12 for the cause | In review |')).length, 1);
});

test('only a cell that starts with In review counts; Done, Planned and prose are left alone', () => {
  assert.deepEqual(statusProblems(rows('| S4b-BL-9 | Was In review once | Done (#123, 341f5ba4) |', '| S4b-BL-10 | A story | Planned |')), []);
  assert.deepEqual(statusProblems(rows('| S4b-BL-9 | A story | Partly done (#12): the phone is in review later |')), []);
});

test('the change log, headers, struck-through rows and rows without a ticket id are skipped', () => {
  const text = rows('| 0.335 | 2026-10-15 | Claude | **S4b-BL-221 In review** (voice PR 4) |', '| ~~S4b-BL-3~~ | Web | In review |', '| 4a | 2026-09-22 → | In review | x |', 'In review in prose.');
  assert.deepEqual(statusProblems(text), []);
});

test('the real sprint log passes', () => {
  const file = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'docs', '10-sprint-log.md');
  assert.deepEqual(statusProblems(fs.readFileSync(file, 'utf8')), []);
});

test('the command exits 1 and prints file:line for a violation, 0 for a clean file', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sprint-status-'));
  const bad = path.join(dir, 'bad.md');
  const good = path.join(dir, 'good.md');
  fs.writeFileSync(bad, rows('| S4b-BL-9 | A story | In review |'));
  fs.writeFileSync(good, rows('| S4b-BL-9 | A story | Done (#1, abcdef12) |'));
  const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'check-sprint-status.mjs');
  const r1 = spawnSync('node', [script, bad], { encoding: 'utf8' });
  assert.equal(r1.status, 1);
  assert.match(r1.stdout, /bad\.md:3: S4b-BL-9 says "In review"/);
  assert.equal(spawnSync('node', [script, good], { encoding: 'utf8' }).status, 0);
});
