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


// Tests for tools/check-kind-literals.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ALLOW_FILE, MAP_RULES_FILES, check, isSource, withoutComments } from './check-kind-literals.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const file = (name, text) => ({ file: name, text });
const NONE = { size: 0, files: {} };
const errorsOf = (files, allow = NONE) => check(files, allow).errors;

test('comments are blanked with their line breaks, and a URL in a string is not a comment', () => {
  const text = "const a = 1; // 'house' here\n/* \"house\"\n HOUSE_KIND */\nconst u = 'https://x.test/house'; const k = 'house';";
  const code = withoutComments(text);
  assert.equal(code.split('\n').length, text.split('\n').length);
  assert.ok(!code.includes('HOUSE_KIND'));
  assert.ok(code.includes("https://x.test/house"));
  assert.deepEqual(errorsOf([file('web/src/app/a.ts', text)]).map((e) => e.split(':')[1]), ['4']);
});

test('the four spellings are hits, with their line, in web, phone and server sources', () => {
  const src = [
    "if (kind === 'house') {}",
    'val k = "house"',
    'const x = HOUSE_KIND;',
    'if (kind == KindSettingRules.HOUSE) return',
  ].join('\n');
  for (const f of ['web/src/app/x.ts', 'android/shared/src/commonMain/kotlin/X.kt', 'backend/src/main/java/X.java', 'android/ui/src/commonMain/kotlin/Y.kt']) {
    assert.deepEqual(errorsOf([file(f, src)]).map((e) => e.split(':')[1]), ['1', '2', '3', '4'], f);
  }
});

test('a CSS class, a property index and the word house in text are not hits; another kind is not either', () => {
  const src = [
    '<section class="house">',
    "type T = ExportHouse['house'];",
    "const s = 'a house on the map';",
    "const k = 'school';",
    'const h = "houses";',
    "const l = ['house', 'school'];",
  ].join('\n');
  assert.deepEqual(errorsOf([file('web/src/app/x.ts', src)]).map((e) => e.split(':')[1]), ['6']);
});

test('tests, goldens, build output and files outside the roots are not read', () => {
  const text = "const k = 'house';";
  for (const f of ['web/src/app/x.spec.ts', 'android/app/src/test/java/A.kt', 'android/shared/src/commonTest/kotlin/A.kt', 'web/src/app/shared/testing/a.ts',
    'web/src/app/export/golden/x.ts', 'android/ui/build/generated/X.kt', 'docs/x.ts', 'tools/x.mjs', 'web/src/app/x.json', 'backend/src/test/java/X.java', 'android/shared/src/androidHostTest/kotlin/XTest.kt']) {
    assert.equal(isSource(f), false, f);
    assert.deepEqual(errorsOf([file(f, text)]), [], f);
  }
  for (const f of ['web/src/app/x.ts', 'web/src/app/x.html', 'android/shared/src/commonMain/kotlin/X.kt', 'android/app/src/main/java/X.kt', 'backend/src/main/java/X.java']) assert.equal(isSource(f), true, f);
});

test('the allow-list asserts its size and each file its most hits; a stale or missing entry fails; fewer hits is a note', () => {
  const two = file('web/src/app/core/candidate.ts', "const a = 'house';\nconst b = 'house';");
  const allow = { size: 1, files: { 'web/src/app/core/candidate.ts': { max: 2, reason: 'x' } } };
  assert.deepEqual(check([two], allow), { errors: [], notes: [] });
  assert.match(errorsOf([two], { size: 2, files: allow.files })[0], /lists 1 files but says size 2/);
  assert.match(errorsOf([two], { size: 1, files: { 'web/src/app/core/candidate.ts': { max: 1, reason: 'x' } } })[0], /2 kind literals, the allow-list says at most 1/);
  assert.match(check([file('web/src/app/core/candidate.ts', "const a = 'house';")], allow).notes[0], /Lower the entry to 1/);
  assert.match(errorsOf([file('web/src/app/core/candidate.ts', 'const a = 1;')], allow)[0], /no kind literal any more/);
  assert.match(errorsOf([file('web/src/app/other.ts', 'const a = 1;')], allow)[0], /not a source file of this checkout/);
});

test('zz_all, the test-only kind, is named by no source, anywhere', () => {
  const out = errorsOf([file('web/src/app/core/dev-kinds.ts', "registerKind('zz_all');"), file('android/shared/src/commonMain/kotlin/K.kt', '// zz_all in a comment is fine\nval x = 1')]);
  assert.equal(out.length, 1);
  assert.match(out[0], /dev-kinds\.ts:1: names zz_all/);
});

test('a map-rules file reads no kind: the registries, the resolvers, the spaces and a kind literal are refused; a layer or feature kind is not', () => {
  assert.equal(MAP_RULES_FILES.length, 5);
  for (const f of MAP_RULES_FILES) {
    for (const bad of ['capabilitiesOf(k)', 'isBaselineKind(k)', 'val c = KindCapabilities.of(x)', 'LocalSpace.current', 'DevKinds.of(x)', "const k = 'school';", 'house.kind', "from './kind-labels'"]) {
      assert.equal(errorsOf([file(f, bad)]).length, 1, `${f}: ${bad}`);
    }
    for (const fine of ['val f = get("kind")', 'enum class Kind { LINE }', 'data class Layer(val id: String, val kind: Kind)', "// the kind `world`\nconst a = 1;"]) {
      assert.deepEqual(errorsOf([file(f, fine)]), [], `${f}: ${fine}`);
    }
  }
  assert.deepEqual(errorsOf([file('web/src/app/shared/other.ts', 'capabilitiesOf(k)')]), [], 'only the five files');
});

test('this checkout passes (the command line, as tools/check.sh and CI run it) and the allow-list file holds what it says', () => {
  const allow = JSON.parse(fs.readFileSync(path.join(ROOT, ALLOW_FILE), 'utf8'));
  assert.equal(Object.keys(allow.files).length, allow.size);
  for (const [f, e] of Object.entries(allow.files)) {
    assert.ok(fs.existsSync(path.join(ROOT, f)), f);
    assert.ok(Number.isInteger(e.max) && e.max >= 1 && e.reason.length > 20, f);
  }
  for (const f of MAP_RULES_FILES) assert.ok(fs.existsSync(path.join(ROOT, f)), f);
  const run = spawnSync('node', [path.join(ROOT, 'tools/check-kind-literals.mjs')], { cwd: ROOT, encoding: 'utf8' });
  assert.equal(run.status, 0, run.stdout + run.stderr);
  assert.match(run.stdout, /kind tripwire: \d+ source files/);
});
