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

// Tests for tools/check-specs.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { specProblems, importsOf } from './check-specs.mjs';

const real = `import { describe, expect, it } from 'vitest';
import { maplibreWorkerProblems } from '../../../scripts/maplibre-worker-check.mjs';
describe('x', () => { it('y', () => { expect(maplibreWorkerProblems({ worker: null, shared: null })).not.toEqual([]); }); });`;

test('a spec that calls the code it imports is fine', () => {
  assert.deepEqual(specProblems(real), []);
});

test('a spec that imports nothing but vitest is flagged (the code under test was re-implemented inside)', () => {
  const hollow = `import { describe, expect, it } from 'vitest';
it('y', () => { const v = 'a'.match(/a/)?.[0]; expect(v).toBe('a'); });`;
  assert.match(specProblems(hollow)[0], /imports no production module/);
});

test('a spec that imports the function and never calls it is flagged', () => {
  const unused = `import { describe, it, vi } from 'vitest';
import { checkMaplibreWorkerFiles } from '../../../scripts/maplibre-worker-check.mjs';
describe('x', () => { it('y', () => { vi.fn(); }); });`;
  assert.match(specProblems(unused)[0], /uses none of it/);
});

test('a name that only appears in a comment or as a property does not count as used', () => {
  const sneaky = `import { it } from 'vitest';
import { thing } from './thing';
// thing is tested elsewhere
it('y', () => { const o = { a: 1 }; void o.thing; });`;
  assert.equal(specProblems(sneaky).length, 1);
});

test('one unused import next to a used one is only untidy, not flagged', () => {
  const ok = `import { it } from 'vitest';
import { a, b } from './thing';
it('y', () => { a(); });`;
  assert.deepEqual(specProblems(ok), []);
});

test('imports of test helpers and fakes do not count as production code', () => {
  const onlyFakes = `import { it } from 'vitest';
import { FakeDrive } from './fake-drive.fake';
it('y', () => { new FakeDrive(); });`;
  assert.match(specProblems(onlyFakes)[0], /imports no production module/);
});

test('the explicit exception marker is honoured', () => {
  const marked = `// check-specs: allow-no-production-import (pins a JSON file)
import { it } from 'vitest';
it('y', () => {});`;
  assert.deepEqual(specProblems(marked), []);
});

test('importsOf reads default, named, aliased and namespace imports', () => {
  const found = importsOf(`import D, { a as b, type C } from './m'; import * as ns from '../n';`);
  assert.deepEqual(found.map((f) => f.names), [['D', 'b', 'C'], ['ns']]);
});
