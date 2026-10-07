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

// Document version guard (docs/14 §7, S4b-BL-154). Parallel and stacked pull requests each bump a document's version, and
// a hand merge used to keep every side's `| Version | x |` header row and every side's change-log row. The convention,
// per docs/*.md (any depth):
//   1. at most one `| Version | x |` header row;
//   2. the header version equals the highest version in the change log (the `| Version | Date | ... |` table);
//   3. the change-log versions are unique, and the five highest stand in order, all oldest first or all newest first (the
//      older rows are history and keep whatever order they have: several logs already run in both directions).
// A document with a change log but no header row (docs/ai/*.md, the review notes) is checked for 3 only.
//
//   node tools/check-docs-versions.mjs [dir ...]      default: docs
//
// Exit 1 and one `file:line: problem. Fix: ...` line per violation. No dependencies: plain Node, regex over the text.
import fs from 'node:fs';
import path from 'node:path';

const roots = process.argv.slice(2).length ? process.argv.slice(2) : ['docs'];

// How many of the highest change-log versions must be in order (see versionProblems).
const RECENT = 5;

/** The version cell as numbers ("0.10" -> [0, 10], "v0.4" -> [0, 4]), or null when it is not a version. */
export function parseVersion(cell) {
  const m = /^v?(\d+(?:\.\d+)*)$/.exec(cell.trim());
  return m ? m[1].split('.').map(Number) : null;
}

/** Negative, zero or positive, comparing two parsed versions segment by segment (a missing segment is 0). */
export function compareVersions(a, b) {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (a[i] ?? 0) - (b[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

/** The problems of one document's text as { line, message }, empty when fine. Pure, tested in check-docs-versions.test.mjs. */
export function versionProblems(text) {
  const problems = [];
  const headers = [];
  const tables = [];
  let table = null;
  let fenced = false;
  text.split('\n').forEach((raw, i) => {
    const line = raw.replace(/\r$/, '');
    const no = i + 1;
    if (/^\s*(```|~~~)/.test(line)) fenced = !fenced;
    if (fenced) return;
    const header = /^\|\s*Version\s*\|([^|]*)\|\s*$/.exec(line);
    if (header) headers.push({ no, cell: header[1].trim() });
    if (/^\|\s*Version\s*\|\s*Date\s*\|/.test(line)) {
      table = { rows: [] };
      tables.push(table);
    } else if (table && !line.startsWith('|')) table = null;
    else if (table && !/^\|[-\s|:]+$/.test(line)) table.rows.push({ no, cell: line.split('|')[1].trim() });
  });

  let latest = null; // the highest change-log version of the document, for the header check
  for (const { rows } of tables) {
    const parsed = [];
    for (const row of rows) {
      const v = parseVersion(row.cell);
      if (v) parsed.push({ ...row, v });
      else problems.push({ line: row.no, message: `change-log version "${row.cell}" is not a number like 0.12. Fix: write the version as major.minor.` });
    }
    const seen = new Map();
    for (const row of parsed) {
      const key = row.v.join('.');
      if (seen.has(key)) {
        problems.push({ line: row.no, message: `change-log version ${row.cell} repeats line ${seen.get(key)}. Fix: renumber this row to the next free version, or merge the two rows.` });
      } else seen.set(key, row.no);
    }
    // History is never rewritten and several change logs already hold runs in both directions, so only the live end
    // is held to the order: the RECENT highest versions must stand in one direction, top to bottom or bottom to top.
    const recent = parsed.filter((row) => seen.get(row.v.join('.')) === row.no)
      .sort((a, b) => compareVersions(b.v, a.v)).slice(0, RECENT);
    const steps = recent.slice(1).map((row, i) => Math.sign(row.no - recent[i].no)); // +1: the lower version is further down
    const dir = Math.sign(steps.reduce((a, b) => a + b, 0)) || steps[0] || 0; // the majority, a tie goes to the first
    for (let i = 1; i < recent.length; i++) {
      if (steps[i - 1] !== dir) {
        problems.push({ line: recent[i].no, message: `change-log version ${recent[i].cell} is out of order among the latest rows: ${recent[i - 1].cell} is on line ${recent[i - 1].no} but the others run ${dir > 0 ? 'newest first' : 'oldest first'}. Fix: move the row so the latest versions follow each other in one direction.` });
      }
    }
    for (const row of parsed) if (!latest || compareVersions(row.v, latest.v) > 0) latest = row;
  }

  headers.slice(1).forEach((h) => {
    problems.push({ line: h.no, message: `a second "| Version | ${h.cell} |" header row (first at line ${headers[0].no}). Fix: delete the extra rows and keep one${latest ? `, ${latest.cell}` : ''}.` });
  });
  if (headers.length > 0 && latest) {
    const v = parseVersion(headers[0].cell);
    if (!v || compareVersions(v, latest.v) !== 0) {
      problems.push({ line: headers[0].no, message: `header version ${headers[0].cell} differs from the highest change-log version ${latest.cell} (line ${latest.no}). Fix: set the header to ${latest.cell}, or add the missing change-log row.` });
    }
  }
  return problems.sort((a, b) => a.line - b.line);
}

function* markdown(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* markdown(full);
    else if (entry.name.endsWith('.md')) yield full;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  let bad = 0;
  let count = 0;
  for (const root of roots) {
    for (const file of markdown(root)) {
      count++;
      for (const p of versionProblems(fs.readFileSync(file, 'utf8'))) {
        console.log(`${file}:${p.line}: ${p.message}`);
        bad++;
      }
    }
  }
  console.log(`${count} documents checked, ${bad} problem(s).`);
  process.exit(bad ? 1 : 0);
}
