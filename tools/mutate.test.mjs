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

// Tests for tools/mutate.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyMutation, failingTests, gradleFailingTests, includeArgs, mavenFailingTests, verdict } from './mutate.mjs';

test('includeArgs takes one spec file or an array of them, by file name', () => {
  assert.deepEqual(includeArgs('web/src/app/a/x.spec.ts'), ['--include=**/x.spec.ts']);
  assert.deepEqual(includeArgs(['web/src/app/a/x.spec.ts', 'web/src/app/b/y.spec.ts']), ['--include=**/x.spec.ts', '--include=**/y.spec.ts']);
});

test('gradleFailingTests reads Gradle FAILED lines and nothing else', () => {
  const out = [
    'app.doorprints.ui.TourTest > walksForwardAndBack[host] FAILED',
    '    kotlin.AssertionError at TourTest.kt:1',
    'app.doorprints.ui.TourTest > theOfferComesOnce FAILED',
    '> Task :ui:testAndroidHostTest FAILED',
    '2 tests completed, 2 failed',
  ].join('\n');
  assert.deepEqual(gradleFailingTests(out), [
    'app.doorprints.ui.TourTest > walksForwardAndBack[host]',
    'app.doorprints.ui.TourTest > theOfferComesOnce',
  ]);
});

test('mavenFailingTests reads Surefire <<< FAILURE and <<< ERROR lines, once each, and nothing else', () => {
  const out = [
    '[ERROR] Tests run: 27, Failures: 3, Errors: 2, Skipped: 0, Time elapsed: 0.656 s <<< FAILURE! -- in app.doorprints.server.ai.eval.EvalScorerTest',
    '[ERROR] app.doorprints.server.ai.eval.EvalScorerTest.everyMetricIsComputedPerRegion -- Time elapsed: 0.028 s <<< FAILURE!',
    'java.lang.AssertionError: boom',
    '[ERROR] app.doorprints.server.ai.eval.EvalScorerTest.spreadOfOneRegion -- Time elapsed: 0.004 s <<< ERROR!',
    '[ERROR] Failures: ',
    '[ERROR]   EvalScorerTest.everyMetricIsComputedPerRegion:370 ',
  ].join('\n');
  assert.deepEqual(mavenFailingTests(out), [
    'app.doorprints.server.ai.eval.EvalScorerTest > everyMetricIsComputedPerRegion',
    'app.doorprints.server.ai.eval.EvalScorerTest > spreadOfOneRegion',
  ]);
  assert.deepEqual(mavenFailingTests('[INFO] BUILD SUCCESS'), []);
});

test('applyMutation changes the one occurrence', () => {
  assert.deepEqual(applyMutation('if (a !== b) x();', { find: 'a !== b', replace: 'false' }), { text: 'if (false) x();' });
});

test('applyMutation refuses a find text that is missing or ambiguous', () => {
  assert.match(applyMutation('abc', { find: 'zzz', replace: '' }).error, /not found/);
  assert.match(applyMutation('aXaX', { find: 'X', replace: '' }).error, /more than once/);
});

test('failingTests reads Vitest FAIL lines, colours stripped', () => {
  const out = '\x1b[41m FAIL \x1b[49m web  src/a.spec.ts > suite > names both versions\n Tests  1 failed | 11 passed (12)\n';
  assert.deepEqual(failingTests(out), ['src/a.spec.ts > suite > names both versions']);
});

test('verdict: no failure means the mutation survived', () => {
  assert.equal(verdict([], 'x').killed, false);
});

test('verdict: a failure with another name still counts as survived', () => {
  const v = verdict(['a > some other test'], 'names both versions');
  assert.equal(v.killed, false);
  assert.match(v.why, /none named/);
});

test('verdict: a failure with the expected name kills it', () => {
  assert.equal(verdict(['a > names both versions'], 'names both versions').killed, true);
});
