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

// File-size budget (docs/14 N21 rule 1, docs/03 ADR-36 §18.6, S4b-BL-205). A product source file that grows past
// `fail` lines (900) fails the check; past `warn` (600) it prints a warning. A file already over `fail` when the budget
// came in is on the allowlist (`allow` in tools/size-budget.json) at the size it had then, and may not grow beyond it;
// the allowlist only shrinks (the floor guard, tools/check-floor.mjs, reports a raised or added entry). Counted like
// `wc -l`: the newline characters of a tracked file. Product sources are the tracked files under `roots` with one of
// `extensions`, minus tests (`*.spec.ts`, `*Test.kt`, `src/test/`, `src/androidHostTest/` and the other test source sets,
// `testing/` helpers) and minus the translation `tables` (dictionaries grow with every string by design).
//
//   node tools/check-size-budget.mjs            the tracked files of this checkout against tools/size-budget.json
//
// Exit 1 and one `file: problem. Fix: ...` line per failure; warnings and notes print and exit 0. Plain Node.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export const BUDGET_FILE = 'tools/size-budget.json';
const TEST = [/\.spec\.ts$/, /Tests?\.(?:kt|java|swift)$/, /\/src\/[A-Za-z]*[Tt]est[A-Za-z]*\//, /(?:^|\/)testing\//];

/** True for a file the budget measures: under a root, with a listed extension, not a test, not a table. */
export function isBudgeted(file, budget) {
  return budget.roots.some((r) => file.startsWith(r)) && budget.extensions.includes(path.extname(file))
    && !TEST.some((re) => re.test(file)) && !Object.hasOwn(budget.tables, file);
}

/** The `wc -l` count of a text: its newline characters. */
export const lineCount = (text) => (text.match(/\n/g) ?? []).length;

/**
 * The verdict for the measured files ([{ file, lines }], every tracked file is fine: the non-budgeted are skipped) as
 * { errors, warnings, notes }, each a list of lines. Pure: tested on fixtures.
 */
export function check(files, budget) {
  const errors = [], warnings = [], notes = [];
  const seen = new Set();
  for (const { file, lines } of files) {
    if (!isBudgeted(file, budget)) continue;
    seen.add(file);
    const allowed = budget.allow[file];
    if (allowed !== undefined) {
      if (lines > allowed) errors.push(`${file}: ${lines} lines, over its budget of ${allowed}. Fix: move a part into its own file (the allowlist only shrinks).`);
      else if (lines <= budget.fail) notes.push(`${file}: ${lines} lines, now under ${budget.fail}. Remove its entry from ${BUDGET_FILE}.`);
      else if (lines < allowed) notes.push(`${file}: ${lines} lines, under its budget of ${allowed}. Lower the entry to ${lines} in ${BUDGET_FILE}.`);
    } else if (lines > budget.fail) {
      errors.push(`${file}: ${lines} lines, over ${budget.fail}. Fix: split it by kind of data (docs/14 §9, §10); a new allowlist entry needs the owner.`);
    } else if (lines > budget.warn) {
      warnings.push(`${file}: ${lines} lines, over ${budget.warn} (fails at ${budget.fail}).`);
    }
  }
  for (const [file, allowed] of Object.entries(budget.allow)) {
    if (!Number.isInteger(allowed) || allowed <= budget.fail) errors.push(`${BUDGET_FILE}: the entry for ${file} (${allowed}) must be a whole number over ${budget.fail}.`);
    else if (!seen.has(file)) errors.push(`${BUDGET_FILE}: ${file} is not a budgeted file of this checkout. Fix: remove its entry.`);
  }
  for (const file of Object.keys(budget.tables)) {
    if (!files.some((f) => f.file === file)) errors.push(`${BUDGET_FILE}: the table ${file} is not a tracked file. Fix: remove its entry.`);
  }
  return { errors, warnings, notes };
}

function main() {
  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
  const budget = JSON.parse(fs.readFileSync(path.join(root, BUDGET_FILE), 'utf8'));
  const ls = spawnSync('git', ['-c', 'core.quotepath=off', 'ls-files', '-z'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 });
  if (ls.status !== 0) { console.error(`size budget cannot run: git ls-files failed: ${ls.stderr.trim()}`); process.exit(2); }
  const files = ls.stdout.split('\0').filter(Boolean).filter((f) => fs.existsSync(path.join(root, f)))
    .map((file) => ({ file, lines: isBudgeted(file, budget) ? lineCount(fs.readFileSync(path.join(root, file), 'utf8')) : 0 }));
  const { errors, warnings, notes } = check(files, budget);
  warnings.forEach((w) => console.log(`warning: ${w}`));
  notes.forEach((n) => console.log(`note: ${n}`));
  if (errors.length) {
    errors.forEach((e) => console.log(e));
    console.log(`${errors.length} file-size budget failure(s).`);
    process.exit(1);
  }
  console.log(`size budget: ${files.filter((f) => isBudgeted(f.file, budget)).length} files within budget (${Object.keys(budget.allow).length} on the allowlist).`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) main();
