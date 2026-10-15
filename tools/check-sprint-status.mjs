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

// Sprint-log status guard (docs/14 §7 *Status words*). A row of docs/10 said "In review" long after its pull request had
// merged, so a reader could not tell merged from unmerged. The convention: Planned = not started; In progress = a branch
// exists; In review = a pull request is open (its number is in the row); Done = merged to `main` (the PR number and the
// short commit id are in the row). This guard holds the one rule that needs no network: in a row led by a ticket id
// (S4b-BL-12, C-02, CMP-0-BL-6, S4b-FR-13, ...; a struck-through row is closed and skipped), a cell that starts with
// "In review" (also **In review** and *In review*) names a pull request (#NNN). Whether that pull request is still open is
// the merging session's job: it flips the row in the same pull request (a row never says In review without an open PR).
//
//   node tools/check-sprint-status.mjs [file ...]      default: docs/10-sprint-log.md
//
// Exit 1 and one `file:line: problem. Fix: ...` line per violation. No dependencies: plain Node, regex over the text.
import fs from 'node:fs';

const files = process.argv.slice(2).length ? process.argv.slice(2) : ['docs/10-sprint-log.md'];

// A ticket id at the start of a row: letters, then dash-separated parts that include a digit (S4b-BL-12, C-02, CMP-0-BL-6).
const TICKET = /^[A-Z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*-\d+[a-z]?(?:-[A-Za-z0-9]+)*$/;

/** The problems of one document's text as { line, message }, empty when fine. Pure, tested in check-sprint-status.test.mjs. */
export function statusProblems(text) {
  const problems = [];
  text.split('\n').forEach((raw, i) => {
    if (!raw.startsWith('| ')) return;
    const cells = raw.replace(/^\| /, '').replace(/ \|\s*$/, '').split(' | ');
    const id = cells[0].trim();
    if (!TICKET.test(id)) return; // headers, separators, the change log (a version first), struck-through ids (~~...~~)
    for (const cell of cells.slice(1)) {
      if (/^[*_\s]*In review\b/.test(cell) && !/#\d+/.test(cell)) {
        problems.push({ line: i + 1, message: `${id} says "In review" without a pull request number. Fix: put the open pull request (#NNN) in the cell, or set the row to Done (#NNN, short commit id) if it merged, or In progress if only a branch exists.` });
      }
    }
  });
  return problems;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  let bad = 0;
  for (const file of files) {
    for (const p of statusProblems(fs.readFileSync(file, 'utf8'))) {
      console.log(`${file}:${p.line}: ${p.message}`);
      bad++;
    }
  }
  console.log(`${files.length} file(s) checked, ${bad} problem(s).`);
  process.exit(bad ? 1 : 0);
}
