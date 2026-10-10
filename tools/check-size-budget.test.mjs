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

// Tests for tools/check-size-budget.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { check, isBudgeted, lineCount } from './check-size-budget.mjs';

const BUDGET = {
  warn: 600, fail: 900, roots: ['android/', 'web/src/', 'backend/src/main/'], extensions: ['.kt', '.ts', '.html', '.java'],
  tables: { 'web/src/app/i18n/en.ts': 'the English dictionary' },
  allow: { 'android/ui/src/commonMain/kotlin/app/doorprints/ui/HouseEditScreen.kt': 1779, 'web/src/app/pages/house-detail/house-detail-page.ts': 1214 },
};
const EDIT = 'android/ui/src/commonMain/kotlin/app/doorprints/ui/HouseEditScreen.kt';
const PAGE = 'web/src/app/pages/house-detail/house-detail-page.ts';
const today = [{ file: EDIT, lines: 1779 }, { file: PAGE, lines: 1214 }, { file: 'web/src/app/i18n/en.ts', lines: 1683 }];
const run = (extra, budget = BUDGET) => check([...today, ...extra], budget);

test('counts lines like wc -l: newline characters, a last line without one is not counted', () => {
  assert.equal(lineCount(''), 0);
  assert.equal(lineCount('a\nb\n'), 2);
  assert.equal(lineCount('a\nb'), 1);
});

test('measures product sources only: not tests, test helpers, tables, other roots or extensions', () => {
  assert.equal(isBudgeted(EDIT, BUDGET), true);
  assert.equal(isBudgeted('backend/src/main/java/app/doorprints/server/backup/BackupService.java', BUDGET), true);
  for (const file of [
    'web/src/app/pages/house-detail/house-detail-page.spec.ts', 'android/shared/src/androidHostTest/kotlin/a/HouseKindFileTest.kt',
    'android/shared/src/commonTest/kotlin/a/Helpers.kt', 'backend/src/test/java/a/B.java', 'android/app/src/test/kotlin/a/C.kt',
    'web/src/app/shared/testing/a11y.ts', 'web/src/app/i18n/en.ts', 'tools/live-ui/live-ui.js', 'web/src/app/a.json', 'docs/03-design.md',
  ]) assert.equal(isBudgeted(file, BUDGET), false, file);
});

test('the budget is green at today\'s sizes', () => {
  assert.deepEqual(run([]), { errors: [], warnings: [], notes: [] });
});

test('an allowlisted file one line over its budget fails, naming the file and the budget', () => {
  const r = check([{ file: EDIT, lines: 1780 }, today[1], today[2]], BUDGET);
  assert.deepEqual(r.errors, [`${EDIT}: 1780 lines, over its budget of 1779. Fix: move a part into its own file (the allowlist only shrinks).`]);
});

test('a new file over 900 fails, over 600 warns, at 900 passes', () => {
  assert.deepEqual(run([{ file: 'web/src/app/x.ts', lines: 901 }]).errors, ['web/src/app/x.ts: 901 lines, over 900. Fix: split it by kind of data (docs/14 §9, §10); a new allowlist entry needs the owner.']);
  assert.deepEqual(run([{ file: 'web/src/app/x.ts', lines: 601 }]).warnings, ['web/src/app/x.ts: 601 lines, over 600 (fails at 900).']);
  assert.deepEqual(run([{ file: 'web/src/app/x.ts', lines: 900 }]), { errors: [], warnings: ['web/src/app/x.ts: 900 lines, over 600 (fails at 900).'], notes: [] });
  assert.deepEqual(run([{ file: 'web/src/app/x.spec.ts', lines: 5000 }]).errors, []);
});

test('a shrunk file asks for a lower entry, and one under 900 for its removal', () => {
  assert.deepEqual(check([{ file: EDIT, lines: 1500 }, today[1], today[2]], BUDGET).notes, [`${EDIT}: 1500 lines, under its budget of 1779. Lower the entry to 1500 in tools/size-budget.json.`]);
  assert.deepEqual(check([{ file: EDIT, lines: 880 }, today[1], today[2]], BUDGET).notes, [`${EDIT}: 880 lines, now under 900. Remove its entry from tools/size-budget.json.`]);
});

test('a stale entry, an entry at or under the limit and a table that is not tracked fail', () => {
  assert.deepEqual(check([today[1], today[2]], BUDGET).errors, [`tools/size-budget.json: ${EDIT} is not a budgeted file of this checkout. Fix: remove its entry.`]);
  assert.deepEqual(run([{ file: 'web/src/b.ts', lines: 10 }], { ...BUDGET, allow: { ...BUDGET.allow, 'web/src/b.ts': 900 } }).errors, ['tools/size-budget.json: the entry for web/src/b.ts (900) must be a whole number over 900.']);
  assert.deepEqual(check(today.slice(0, 2), BUDGET).errors, ['tools/size-budget.json: the table web/src/app/i18n/en.ts is not a tracked file. Fix: remove its entry.']);
});

test('the repository is within its recorded budget (the command line, as tools/check.sh and CI run it)', () => {
  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
  const budget = JSON.parse(fs.readFileSync(path.join(root, 'tools/size-budget.json'), 'utf8'));
  assert.equal(budget.warn, 600);
  assert.equal(budget.fail, 900);
  const r = spawnSync(process.execPath, [path.join(root, 'tools/check-size-budget.mjs')], { cwd: root, encoding: 'utf8' });
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /size budget: \d+ files within budget \(\d+ on the allowlist\)\./);
});
