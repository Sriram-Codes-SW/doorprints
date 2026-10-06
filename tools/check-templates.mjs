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

// Template rules that a unit test cannot see because the test build differs from the live one (docs/14 §7).
//
//   node tools/check-templates.mjs [dir ...]      default: web/src
//
// 1. No `date` pipe given a language. Angular's DatePipe needs locale data for the language, the app registers none (only
//    English is built in), and in the production build `| date: 'short' : undefined : i18n.lang()` threw for hi, ta and te
//    and left the Google Drive backups table of such a reader empty (found 2026-10-06 taking the guide pictures; the
//    unit tests passed). Dates go through TranslationService (`dateTime`, `dateOnly`, `timeOnly`: Intl).
import fs from 'node:fs';
import path from 'node:path';

const DATE_PIPE_WITH_LANGUAGE = /\|\s*date\s*:[^}]*?(lang\(\)|locale\(\)|\blang\b)/;

/** The problems in one template's text, as `{ line, message }`. Pure, tested in tools/check-templates.test.mjs. */
export function templateProblems(source) {
  const problems = [];
  source.split('\n').forEach((text, i) => {
    if (DATE_PIPE_WITH_LANGUAGE.test(text)) {
      problems.push({ line: i + 1, message: "the date pipe is given a language: Angular has no locale data for hi, ta or te; use TranslationService.dateTime/dateOnly/timeOnly" });
    }
  });
  return problems;
}

function* templates(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== 'node_modules') yield* templates(full);
    } else if (entry.name.endsWith('.html') || (entry.name.endsWith('.ts') && !entry.name.endsWith('.spec.ts'))) yield full;
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const roots = process.argv.slice(2).length ? process.argv.slice(2) : ['web/src'];
  let bad = 0;
  let count = 0;
  for (const root of roots) {
    for (const file of templates(root)) {
      count++;
      for (const p of templateProblems(fs.readFileSync(file, 'utf8'))) {
        console.log(`${file}:${p.line}: ${p.message}`);
        bad++;
      }
    }
  }
  console.log(`${count} template and component files checked, ${bad} problem(s).`);
  process.exit(bad ? 1 : 0);
}
