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

import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import test from 'node:test';

const require = createRequire(import.meta.url);
const { dynamicImportUrl, isClearedImportError } = require('./live-ui/transient-import.js');

const URL_A = 'https://doorprints.web.app/chunk-ABC123.js';

test('dynamicImportUrl reads the chunk address of the console line, with or without Angular\'s prefix', () => {
  assert.equal(dynamicImportUrl(`TypeError: Failed to fetch dynamically imported module: ${URL_A}`), URL_A);
  assert.equal(dynamicImportUrl(`ERROR TypeError: Failed to fetch dynamically imported module: ${URL_A}`), URL_A);
  assert.equal(dynamicImportUrl(`pageerror: Failed to fetch dynamically imported module: ${URL_A}`), URL_A);
});

test('dynamicImportUrl is null for any other error', () => {
  assert.equal(dynamicImportUrl('Failed to load resource: the server responded with a status of 502 (Bad Gateway)'), null);
  assert.equal(dynamicImportUrl('Failed to fetch'), null);
});

test('only an import error of a cleared URL is dropped: a chunk that failed twice stays a failure', () => {
  const cleared = new Set([URL_A]);
  assert.equal(isClearedImportError(`console: ERROR TypeError: Failed to fetch dynamically imported module: ${URL_A}`, cleared), true);
  assert.equal(isClearedImportError('console: ERROR TypeError: Failed to fetch dynamically imported module: https://doorprints.web.app/chunk-OTHER.js', cleared), false);
  assert.equal(isClearedImportError('console: Uncaught TypeError: x is not a function', cleared), false);
  assert.equal(isClearedImportError(`console: Failed to fetch dynamically imported module: ${URL_A}`, new Set()), false);
});
