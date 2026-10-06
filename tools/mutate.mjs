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

// Mutation runner (docs/14 §7). A ticket's acceptance is "each of these one-line changes to production code makes a
// NAMED test fail". The list lives in tools/mutations/<name>.json; this applies each one, runs the spec, checks that a
// test with the expected name failed, and always puts the file back, so the claim is checked, not asserted.
//
//   node tools/mutate.mjs tools/mutations/maplibre-worker-check.json
//
// File format: { "spec": "web/src/app/data/x.spec.ts", "mutations": [
//   { "file": "web/scripts/x.mjs", "find": "a !== b", "replace": "false", "expect": "names both versions" } ] }
// `find` must occur exactly once in `file`; `expect` is a substring of the failing test's name. The spec is run with
// `npx ng test --watch=false --include=<spec>` in web/. Exit 1 when any mutation survives (no failing test, or none
// with the expected name). Plain Node, no dependencies.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');

/** Names of the failing tests in Vitest's output (`FAIL  web  file > suite > test`). */
export function failingTests(output) {
  const clean = output.replace(/\x1b\[[0-9;]*m/g, '');
  return [...clean.matchAll(/^\s*FAIL\s+\S+\s+(.+)$/gm)].map((m) => m[1].trim());
}

/** The mutated text, or an error string when `find` does not occur exactly once. */
export function applyMutation(text, { find, replace }) {
  const first = text.indexOf(find);
  if (first < 0) return { error: `find text not found: ${find}` };
  if (text.indexOf(find, first + 1) >= 0) return { error: `find text occurs more than once: ${find}` };
  return { text: text.slice(0, first) + replace + text.slice(first + find.length) };
}

/** What a run says about one mutation: killed by a test with the expected name, or survived. */
export function verdict(failures, expect) {
  if (failures.length === 0) return { killed: false, why: 'SURVIVED: no test failed' };
  if (expect && !failures.some((f) => f.includes(expect))) {
    return { killed: false, why: `SURVIVED: tests failed (${failures.join('; ')}) but none named "${expect}"` };
  }
  return { killed: true, why: `killed by: ${failures.find((f) => !expect || f.includes(expect))}` };
}

function runSpec(spec) {
  const r = spawnSync('npx', ['ng', 'test', '--watch=false', `--include=**/${path.basename(spec)}`], {
    cwd: path.join(ROOT, 'web'),
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  return (r.stdout || '') + (r.stderr || '');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const listFile = process.argv[2];
  if (!listFile) {
    console.error('Usage: node tools/mutate.mjs tools/mutations/<name>.json');
    process.exit(2);
  }
  const { spec, mutations } = JSON.parse(fs.readFileSync(listFile, 'utf8'));
  const base = failingTests(runSpec(spec));
  if (base.length > 0) {
    console.log(`The spec already fails without a mutation: ${base.join('; ')}`);
    process.exit(1);
  }
  let survived = 0;
  for (const m of mutations) {
    const file = path.join(ROOT, m.file);
    const original = fs.readFileSync(file, 'utf8');
    const mutated = applyMutation(original, m);
    if (mutated.error) {
      console.log(`ERROR ${m.file}: ${mutated.error}`);
      survived++;
      continue;
    }
    try {
      fs.writeFileSync(file, mutated.text);
      const v = verdict(failingTests(runSpec(spec)), m.expect);
      console.log(`${v.killed ? 'ok  ' : 'FAIL'} ${m.file}: ${m.find} -> ${m.replace}  [${v.why}]`);
      if (!v.killed) survived++;
    } finally {
      fs.writeFileSync(file, original);
    }
  }
  console.log(`${mutations.length} mutation(s), ${survived} survived.`);
  process.exit(survived ? 1 : 0);
}
