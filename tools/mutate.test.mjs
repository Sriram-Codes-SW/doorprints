// Tests for tools/mutate.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyMutation, failingTests, verdict } from './mutate.mjs';

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
