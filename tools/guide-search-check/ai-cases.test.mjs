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

// Tests of ai-cases.mjs (docs/06 TC-U-176): the guide page matches the golden set now, and the check fails for a stale page.
//   node --test tools/guide-search-check/ai-cases.test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { block, expectation, problems, rupees } from './ai-cases.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const golden = JSON.parse(fs.readFileSync(path.join(root, 'docs', 'ai', 'evals', 'golden-set.json'), 'utf8'));
const page = fs.readFileSync(path.join(root, 'guide', 'docs', 'what-we-test.md'), 'utf8');

test('the page matches the golden set', () => assert.deepEqual(problems(page, golden), []));

test('every case appears with its id and the exact text it sends', () => {
  const text = block(golden);
  assert.equal(golden.cases.length, 75, 'the page says 75 cases: change its prose when the golden set changes');
  for (const c of golden.cases) {
    assert.ok(text.includes(`**${c.id}**`), c.id);
    assert.ok(text.includes(c.input.text ?? c.input.question), `${c.id}: the exact input`);
  }
});

test('a changed, added or removed case makes the check fail', () => {
  const changed = structuredClone(golden);
  changed.cases[0].input.text += ' extra';
  assert.equal(problems(page, changed).length > 0, true, 'a changed text');
  const fewer = structuredClone(golden);
  fewer.cases.pop();
  assert.equal(problems(page, fewer).length > 0, true, 'a removed case');
  assert.deepEqual(problems('no markers here', golden), ['the page has no golden-set-cases markers']);
});

test('the prose must name the number of cases', () => {
  assert.equal(problems(page.replace(/75 cases/g, 'many cases').replace(block(golden), block(golden)), golden).some((p) => p.includes('"75 cases"')), true);
});

test('rupees uses Indian grouping', () => {
  assert.equal(rupees(28000), '₹28,000');
  assert.equal(rupees(13500000), '₹1,35,00,000');
  assert.equal(rupees(950), '₹950');
});

test('the expectation line says it in plain words', () => {
  const label = (id) => golden.fixtureHouses.find((h) => h.id === id)?.label ?? 'a house that does not exist';
  const byId = (id) => golden.cases.find((c) => c.id === id);
  assert.match(expectation(byId('ask-04-unanswerable'), label), /fixed refusal/);
  assert.match(expectation(byId('ask-01-water'), label), /must cite Blue gate house/i);
  assert.match(expectation(byId('extract-04-injection'), label), /ignore the hidden instruction/);
  assert.match(expectation(byId('plan-03-injection-in-question'), label), /never a house that does not exist/i);
});
