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


// Tests for tools/gen-kind-words.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { kotlinSource, outputs, readKinds, webSource } from './gen-kind-words.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const kind = (id, words) => ({ id, words });
const word = (web, android) => ({ web, android });

test('the generated files are what the kind files say (the check CI runs)', () => {
  for (const [rel, text] of outputs()) assert.equal(fs.readFileSync(path.join(ROOT, rel), 'utf8'), text, `${rel} is stale`);
});

test('every word of the house file is in the school file, in the same order, and both are generated', () => {
  const [house, school] = readKinds();
  assert.deepEqual(Object.keys(school.words), Object.keys(house.words));
  assert.ok(Object.keys(house.words).length >= 140);
});

test('a word the website lacks in every kind has no row; a word one kind lacks is null in its column', () => {
  const kinds = [kind('house', { a: word('x.a', 'a_h'), b: word(null, 'b_h'), c: word('x.c', null) }), kind('school', { a: word('s.a', 'a_s'), b: word(null, 'b_s'), c: word(null, null) })];
  const src = webSource(kinds);
  assert.match(src, /\['a', 'x\.a', 's\.a'\],/);
  assert.match(src, /\['c', 'x\.c', null\],/);
  assert.doesNotMatch(src, /\['b'/);
  const kt = kotlinSource(kinds);
  assert.match(kt, /"a" to "a_h", "b" to "b_h", "c" to null/);
  assert.match(kt, /"school" to table\(/);
});

test('files that do not hold the same ids in the same order are refused', () => {
  assert.throws(() => webSource([kind('house', { a: word('x', null), b: word('y', null) }), kind('school', { b: word('y', null), a: word('x', null) })]), /same ids|house's ids/);
});
