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

// The kind tripwire (kinds PR 32, docs/03 section 18.26). A kind is a file; the code that serves it asks the kind's capabilities, never "is this the
// house". This fails when non-test source says it the old way, so a feature cannot quietly become the house's again:
//
//   1. a literal `'house'`, `"house"`, `HOUSE_KIND` or `KindSettingRules.HOUSE` in a source file that is not on the allow-list
//      (tools/kind-literals-allow.json: the kind files' loaders and registries, the record format of a candidate, the wire adapters of the backup
//      and the sync, the dictionaries). Comments are not read. The allow-list asserts its exact size and, per file, the most hits it may have,
//      so a new literal in an allowed file is a conscious edit; an entry whose file has no hit any more is stale and fails (it only shrinks).
//   2. any mention of `zz_all`, the synthetic kind of the conformance suite (docs/schemas/kinds/test-all.json), in a source file: it exists in
//      tests only, so no build can list it.
//   3. a kind symbol in a map-rules file (the style and the India boundary lines: they must read no kind, so no kind can change what the map draws).
//
//   node tools/check-kind-literals.mjs        the working tree of this checkout
//
// Exit 1 and one `file:line: problem` per failure. Plain Node, offline.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export const ALLOW_FILE = 'tools/kind-literals-allow.json';
const ROOTS = ['web/src/', 'android/', 'backend/src/main/', 'ios/'];
const EXTENSIONS = new Set(['.ts', '.html', '.kt', '.java', '.swift']);
const TEST = [/\.spec\.ts$/, /Tests?\.(?:kt|java|swift)$/, /\/src\/[A-Za-z]*[Tt]est[A-Za-z]*\//, /(?:^|\/)testing\//, /\/build\//, /\/node_modules\//, /\/golden\//];
/** The map-rules files (docs/03 section 11.1, 18.19): the style and the India boundary lines. */
export const MAP_RULES_FILES = [
  'web/src/app/shared/map-style-url.ts',
  'web/src/app/shared/india-boundaries.ts',
  'android/ui/src/commonMain/kotlin/app/doorprints/ui/IndiaViewRules.kt',
  'android/ui/src/commonMain/kotlin/app/doorprints/ui/MapStyleJson.kt',
  'android/ui/src/commonMain/kotlin/app/doorprints/ui/IndiaViewOps.kt',
];

// `class="house"` is a CSS class and `Type['house']` a property index: neither is a kind.
const LITERALS = /(?<!\[)'house'|'house'(?!\])|(?<!class=)"house"|\bHOUSE_KIND\b|\bKindSettingRules\.HOUSE\b/g;
const TEST_KIND = /\bzz_all\b/g;
/** What a map-rules file must not name: the kind registries, resolvers, settings and spaces, a candidate's kind, a kind's id. */
const MAP_SYMBOLS =
  /capabilitiesOf|KindCapabilities|kind-capabilities|isBaselineKind|dev-kinds|DevKinds|BuiltInKind|builtInKind|KindLabels|kind-labels|KindSetting|kind-setting|LocalSpace|SpaceService|space\.service|\bSpaceUi\b|\bSpace\b|kindById|drawnKind|spaceWords|kindId|candidate\.kind|house\.kind|HOUSE_KIND|'school'|"school"|'house'|"house"/;

export const isSource = (file) =>
  ROOTS.some((r) => file.startsWith(r)) && EXTENSIONS.has(path.extname(file)) && !TEST.some((re) => re.test(file));

/**
 * The text with every comment blanked (block comments, JSDoc and KDoc, and `//` comments), line breaks kept, so a hit has its real line.
 * A `//` inside a string literal (a URL) is not a comment: it must follow whitespace, an opening bracket or the line's start.
 */
export function withoutComments(text) {
  const blank = (s) => s.replace(/[^\n]/g, ' ');
  return text
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/(^|[\s([{,;])\/\/[^\n]*/gm, (m, lead) => lead + blank(m.slice(lead.length)));
}

/** The 1-based lines of the matches of `re` in `text` (comments already blanked). */
export function hitsOf(text, re) {
  const lines = [];
  text.split('\n').forEach((line, i) => {
    if (new RegExp(re.source, re.flags.replace('g', '')).test(line)) lines.push(i + 1);
  });
  return lines;
}

/**
 * The verdict for `files` ([{ file, text }]) against the allow-list { size, files: { path: { max, reason } } }: a list of `file:line: problem` strings,
 * and `notes` (entries that can be lowered). Pure: tested on fixtures.
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
    const hits = hitsOf(code, LITERALS);
    for (const line of hitsOf(code, TEST_KIND)) errors.push(`${file}:${line}: names zz_all, the test-only kind. Fix: it lives in tests and docs/schemas/kinds/test-all.json only.`);
    if (MAP_RULES_FILES.includes(file)) {
      for (const line of hitsOf(code, MAP_SYMBOLS)) errors.push(`${file}:${line}: a map-rules file reads a kind. Fix: the map draws the same for every kind (docs/03 section 11.1); pass nothing about a kind in.`);
    }
    const entry = allow.files?.[file];
    if (entry) {
      seen.add(file);
      if (hits.length === 0) errors.push(`${file}: is on the allow-list but has no kind literal any more. Fix: remove it from ${ALLOW_FILE} and lower "size".`);
      else if (hits.length > entry.max) errors.push(`${file}:${hits[entry.max]}: ${hits.length} kind literals, the allow-list says at most ${entry.max}. Fix: ask the capability (isBaselineKind, kindOfCandidate) instead of naming the house.`);
      else if (hits.length < entry.max) notes.push(`${file}: ${hits.length} kind literals, under its ${entry.max}. Lower the entry to ${hits.length} in ${ALLOW_FILE}.`);
    } else {
      for (const line of hits) errors.push(`${file}:${line}: names the house kind. Fix: ask the kind's capability (isBaselineKind, capabilitiesOf, kindOfCandidate), not the id; or, for a record format, put it in an allowed file.`);
    }
  }
  const present = new Set(files.map((f) => f.file));
  for (const [file] of entries) if (!seen.has(file) && !present.has(file)) errors.push(`${file}: is on the allow-list but is not a source file of this checkout. Fix: remove it from ${ALLOW_FILE} and lower "size".`);
  return { errors, notes };
}

function main() {
  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
  const ls = spawnSync('git', ['-c', 'core.quotepath=off', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 });
  if (ls.status !== 0) {
    console.error(`kind tripwire cannot run: git ls-files failed: ${ls.stderr.trim()}`);
    process.exit(2);
  }
  const files = ls.stdout.split('\0').filter((f) => f && isSource(f) && fs.existsSync(path.join(root, f))).map((file) => ({ file, text: fs.readFileSync(path.join(root, file), 'utf8') }));
  const allow = JSON.parse(fs.readFileSync(path.join(root, ALLOW_FILE), 'utf8'));
  const { errors, notes } = check(files, allow);
  for (const n of notes) console.log(`note: ${n}`);
  for (const e of errors) console.error(e);
  if (errors.length) {
    console.error(`${errors.length} kind tripwire failure(s).`);
    process.exit(1);
  }
  console.log(`kind tripwire: ${files.length} source files, ${Object.keys(allow.files).length} allowed, no new kind literal, no zz_all, the map rules read no kind.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main();
