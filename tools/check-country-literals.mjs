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

// The country tripwire (ADR-38, docs/03 section 21; WP-C1). What a feature knows about the one country the app is built
// for lives in the country pack (web/src/app/shared/country/india-pack.ts, android/shared/.../country/IndiaPack.kt). This
// fails when non-test source says it another way, so a rupee sign, a +91 or a lakh/crore cannot quietly creep back into a
// feature:
//
//   1. a literal `₹`, `+91`, `lakh` or `crore` (any case) in a source file that is neither a pack file nor on the allow-list
//      (tools/country-literals-allow.json: the dictionaries, which are language work, a few sample strings, and the server's
//      own copies until the backend has its pack). Comments are not read. The allow-list asserts its exact size and, per
//      file, the most hits it may have, so a new literal in an allowed file is a conscious edit; an entry whose file has no
//      hit any more is stale and fails (it only shrinks).
//   2. a pack file that reads a kind or names a map layer: a country pack is about money, phones, places and words, never
//      about which kind is open, and the boundary lines are not its to carry (ADR-22): it may only point at the border test.
//
//   node tools/check-country-literals.mjs        the working tree of this checkout
//
// Exit 1 and one `file:line: problem` per failure. Plain Node, offline. Shares its source rules with tools/check-kind-literals.mjs.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { hitsOf, isSource, withoutComments } from './check-kind-literals.mjs';

export const ALLOW_FILE = 'tools/country-literals-allow.json';
/** The pack files: the one place each stack may hold the country's literals. */
export const PACK_FILES = [
  'web/src/app/shared/country/india-pack.ts',
  'android/shared/src/commonMain/kotlin/app/doorprints/shared/country/IndiaPack.kt',
];
/** The rupee sign, the +91 country code, and the lakh/crore amount words. */
const LITERALS = /₹|\+91|lakh|crore/i;
/** What a pack file must not read: a kind, and the boundary lines' data or layers. */
const PACK_FORBIDDEN =
  /capabilitiesOf|KindCapabilities|isBaselineKind|DevKinds|KindSetting|kindById|kindId|'house'|"house"|'school'|"school"|HOUSE_KIND|LineString|MultiPolygon|in-boundaries|IndiaViewRules|applyIndiaBoundaries|map-style|MapStyleJson/;

/**
 * The verdict for `files` ([{ file, text }]) against the allow-list { size, files: { path: { max, reason } } }: a list of
 * `file:line: problem` strings, and `notes` (entries that can be lowered). Pure: tested on fixtures.
 */
export function check(files, allow) {
  const errors = [];
  const notes = [];
  const entries = Object.entries(allow.files ?? {});
  if (entries.length !== allow.size) errors.push(`${ALLOW_FILE}: lists ${entries.length} files but says size ${allow.size}. Fix: a new allowed file is a conscious edit, change both.`);
  const seen = new Set();
  for (const { file, text } of files) {
    if (!isSource(file)) continue;
    const code = withoutComments(text);
    if (PACK_FILES.includes(file)) {
      for (const line of hitsOf(code, PACK_FORBIDDEN)) errors.push(`${file}:${line}: a country pack reads a kind or names the boundary lines. Fix: the pack holds money, phones, places and words only; it may point at the border test, never carry the lines (ADR-22).`);
      continue;
    }
    const hits = hitsOf(code, LITERALS);
    const entry = allow.files?.[file];
    if (entry) {
      seen.add(file);
      if (hits.length === 0) errors.push(`${file}: is on the allow-list but has no country literal any more. Fix: remove it from ${ALLOW_FILE} and lower "size".`);
      else if (hits.length > entry.max) errors.push(`${file}:${hits[entry.max]}: ${hits.length} country literals, the allow-list says at most ${entry.max}. Fix: ask the country pack (country().money.symbol, derived(), Country.pack) instead of writing the rupee sign, +91, lakh or crore.`);
      else if (hits.length < entry.max) notes.push(`${file}: ${hits.length} country literals, under its ${entry.max}. Lower the entry to ${hits.length} in ${ALLOW_FILE}.`);
    } else {
      for (const line of hits) errors.push(`${file}:${line}: writes a rupee sign, +91, lakh or crore. Fix: ask the country pack (web shared/country/country-pack.ts, Kotlin Country.pack); a dictionary string belongs in an allowed file.`);
    }
  }
  const present = new Set(files.map((f) => f.file));
  for (const [file] of entries) if (!seen.has(file) && !present.has(file)) errors.push(`${file}: is on the allow-list but is not a source file of this checkout. Fix: remove it from ${ALLOW_FILE} and lower "size".`);
  for (const file of PACK_FILES) if (!present.has(file)) errors.push(`${file}: the country pack file is missing from this checkout. Fix: ${ALLOW_FILE} and this tool name it.`);
  return { errors, notes };
}

function main() {
  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
  const ls = spawnSync('git', ['-c', 'core.quotepath=off', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 });
  if (ls.status !== 0) {
    console.error(`country tripwire cannot run: git ls-files failed: ${ls.stderr.trim()}`);
    process.exit(2);
  }
  const files = ls.stdout.split('\0').filter((f) => f && isSource(f) && fs.existsSync(path.join(root, f))).map((file) => ({ file, text: fs.readFileSync(path.join(root, file), 'utf8') }));
  const allow = JSON.parse(fs.readFileSync(path.join(root, ALLOW_FILE), 'utf8'));
  const { errors, notes } = check(files, allow);
  for (const n of notes) console.log(`note: ${n}`);
  for (const e of errors) console.error(e);
  if (errors.length) {
    console.error(`${errors.length} country tripwire failure(s).`);
    process.exit(1);
  }
  console.log(`country tripwire: ${files.length} source files, ${Object.keys(allow.files).length} allowed, no new rupee sign, +91, lakh or crore outside the pack, the packs read no kind.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main();
