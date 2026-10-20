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

// Tests for tools/check-country-literals.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ALLOW_FILE, PACK_FILES, check } from './check-country-literals.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const file = (name, text) => ({ file: name, text });
const PACKS = PACK_FILES.map((f) => file(f, 'const x = 1;'));
const NONE = { size: 0, files: {} };
const errorsOf = (files, allow = NONE) => check([...PACKS, ...files], allow).errors;

test('the rupee sign, +91, lakh and crore are hits, in any case, with their line, in web, phone and server sources', () => {
  const src = ["const a = '₹' + n;", "const b = '+91 98400 11111';", 'val c = "2 Lakhs"', "if (u === 'crore') {}", 'const d = 1;'].join('\n');
  for (const f of ['web/src/app/x.ts', 'android/shared/src/commonMain/kotlin/X.kt', 'backend/src/main/java/X.java', 'android/ui/src/commonMain/kotlin/Y.kt']) {
    assert.deepEqual(errorsOf([file(f, src)]).map((e) => e.split(':')[1]), ['1', '2', '3', '4'], f);
  }
});

test('comments are not read', () => {
  const src = ['// ₹12,50,000 in a comment', '/* +91 and', ' lakh */', "const u = 'https://x.test/crore-free';"].join('\n');
  assert.deepEqual(errorsOf([file('web/src/app/x.ts', src)]).map((e) => e.split(':')[1]), ['4']);
});

test('tests, goldens, build output and files outside the roots are not read', () => {
  const text = "const k = '₹';";
  for (const f of ['web/src/app/x.spec.ts', 'android/app/src/test/java/A.kt', 'android/shared/src/commonTest/kotlin/A.kt', 'web/src/app/shared/testing/zz-pack.ts',
    'web/src/app/export/golden/x.ts', 'android/ui/build/generated/X.kt', 'docs/x.ts', 'tools/x.mjs', 'web/src/app/x.json', 'backend/src/test/java/X.java',
    'android/shared/src/androidHostTest/kotlin/app/doorprints/shared/country/ZzPack.kt']) {
    assert.deepEqual(errorsOf([file(f, text)]), [], f);
  }
});

test('a pack file may hold the literals', () => {
  const rich = PACK_FILES.map((f) => file(f, "val symbol = '₹'; val cc = '+91'; val w = 'lakh'; val c = 'crore'"));
  assert.deepEqual(check(rich, NONE).errors, []);
});

test('a pack file that reads a kind or names the boundary lines is refused; a pack that points at the border test is not', () => {
  for (const bad of ['capabilitiesOf(k)', "const k = 'house';", 'val c = KindCapabilities.of(x)', 'applyIndiaBoundaries(map)', "import x from './in-boundaries.geojson'", '"type": "MultiPolygon"', 'MapStyleJson.build()']) {
    for (const pack of PACK_FILES) {
      const out = check(PACK_FILES.map((f) => file(f, f === pack ? bad : 'const x = 1;')), NONE).errors;
      assert.equal(out.length, 1, `${pack}: ${bad}`);
      assert.match(out[0], /reads a kind or names the boundary lines/);
    }
  }
  const fine = PACK_FILES.map((f) => file(f, "import { inIndia } from './india-border'; contains: inIndia, houseKeyTerms: 'rent'"));
  assert.deepEqual(check(fine, NONE).errors, []);
});

test('the allow-list asserts its size and each file its most hits; a stale or missing entry fails; fewer hits is a note', () => {
  const two = file('web/src/app/i18n/en.ts', "const a = '₹';\nconst b = '₹';");
  const allow = { size: 1, files: { 'web/src/app/i18n/en.ts': { max: 2, reason: 'x' } } };
  assert.deepEqual(check([...PACKS, two], allow), { errors: [], notes: [] });
  assert.match(errorsOf([two], { size: 2, files: allow.files })[0], /lists 1 files but says size 2/);
  assert.match(errorsOf([two], { size: 1, files: { 'web/src/app/i18n/en.ts': { max: 1, reason: 'x' } } })[0], /2 country literals, the allow-list says at most 1/);
  assert.match(check([...PACKS, file('web/src/app/i18n/en.ts', "const a = '₹';")], allow).notes[0], /Lower the entry to 1/);
  assert.match(errorsOf([file('web/src/app/i18n/en.ts', 'const a = 1;')], allow)[0], /no country literal any more/);
  assert.match(errorsOf([file('web/src/app/other.ts', 'const a = 1;')], allow)[0], /not a source file of this checkout/);
});

test('a missing pack file is reported', () => {
  assert.match(check([], NONE).errors[0], /the country pack file is missing/);
});

test('this checkout passes (the command line, as tools/check.sh and CI run it) and the allow-list file holds what it says', () => {
  const allow = JSON.parse(fs.readFileSync(path.join(ROOT, ALLOW_FILE), 'utf8'));
  assert.equal(Object.keys(allow.files).length, allow.size);
  for (const [f, e] of Object.entries(allow.files)) {
    assert.ok(fs.existsSync(path.join(ROOT, f)), f);
    assert.ok(Number.isInteger(e.max) && e.max >= 1 && e.reason.length > 20, f);
  }
  for (const f of PACK_FILES) assert.ok(fs.existsSync(path.join(ROOT, f)), f);
  const run = spawnSync('node', [path.join(ROOT, 'tools/check-country-literals.mjs')], { cwd: ROOT, encoding: 'utf8' });
  assert.equal(run.status, 0, run.stdout + run.stderr);
  assert.match(run.stdout, /country tripwire: \d+ source files/);
});
