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

// Tests for tools/check-templates.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { templateProblems } from './check-templates.mjs';

test("the date pipe with the app's language is flagged (the line that broke the Drive backups table)", () => {
  const src = "<td>{{ backup.createdAt | date: 'short' : undefined : i18n.lang() }}</td>";
  const found = templateProblems(src);
  assert.equal(found.length, 1);
  assert.equal(found[0].line, 1);
  assert.match(found[0].message, /locale data/);
});

test('the same with the locale() call is flagged too', () => {
  assert.equal(templateProblems("{{ d | date: 'shortTime' : undefined : i18n.locale() }}").length, 1);
});

test('the date pipe with English only, or no language, is not flagged', () => {
  assert.deepEqual(templateProblems("{{ d | date: 'short' }}"), []);
  assert.deepEqual(templateProblems("{{ d | date: 'mediumDate' : undefined : 'en' }}"), []);
});

test("the service's own functions are fine, and the line number is the line", () => {
  const src = "ok\n{{ i18n.dateOnly(x) }}\n{{ y | date: 'short' : undefined : i18n.lang() }}";
  assert.deepEqual(templateProblems("{{ i18n.dateOnly(x) }}"), []);
  assert.equal(templateProblems(src)[0].line, 3);
});
