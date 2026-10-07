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

// Tests for tools/check-floor.mjs: `node --test tools/*.test.mjs`
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { findings } from './check-floor.mjs';

// The fixtures name the words the guard looks for. They are joined here so that this file does not itself contain them
// (the guard reads this file's added lines on the pull request that adds it).
const IT_SKIP = 'it' + '.skip';
const IT_ONLY = 'it' + '.only';
const DISABLE = '// eslint-' + 'disable-next-line no-console';
const TS_IGNORE = '// @ts-' + 'ignore';
const KT_IGNORE = '@' + 'Ignore';
const KT_SUPPRESS = '@' + 'Suppress("UNCHECKED_CAST")';
const EMPTY_CATCH = 'try { x(); } catch (e) {' + '}';

// A unified=0 diff of one file: `removed` lines from old line `oldStart`, `added` lines from new line `newStart`.
const edit = (file, oldStart, removed, newStart, added) => [
  `diff --git a/${file} b/${file}`, `--- a/${file}`, `+++ b/${file}`,
  `@@ -${oldStart},${removed.length} +${newStart},${added.length} @@`,
  ...removed.map((l) => `-${l}`), ...added.map((l) => `+${l}`), '',
].join('\n');
const gone = (file, lines) => [
  `diff --git a/${file} b/${file}`, 'deleted file mode 100644', `--- a/${file}`, '+++ /dev/null',
  `@@ -1,${lines.length} +0,0 @@`, ...lines.map((l) => `-${l}`), '',
].join('\n');
const f = (diff) => findings(diff).map((x) => `${x.file}:${x.line}: ${x.reason}`);

test('a skipped or focused test in a spec is reported on its new line, in each framework', () => {
  assert.deepEqual(f(edit('web/a.spec.ts', 9, [], 10, [`  ${IT_SKIP}('x', () => {});`, `  ${IT_ONLY}('y', () => {});`])), [
    'web/a.spec.ts:10: new test skip or focus', 'web/a.spec.ts:11: new test skip or focus']);
  assert.deepEqual(f(edit('web/a.spec.ts', 3, [], 4, ["  fit('x', () => {});"])), ['web/a.spec.ts:4: new test skip or focus']);
  assert.deepEqual(f(edit('web/a.spec.ts', 3, [], 4, ["xdescribe('x', () => {"])), ['web/a.spec.ts:4: new test skip or focus']);
  assert.deepEqual(f(edit('app/FooTest.kt', 7, [], 8, [`    ${KT_IGNORE}`])), ['app/FooTest.kt:8: new test skip or focus']);
  assert.deepEqual(f(edit('app/FooTest.kt', 7, [], 8, ['    assumeTrue(' + 'false)'])), ['app/FooTest.kt:8: new test skip or focus']);
});

test('the same words outside a test file, in documents or inside a string are not skips', () => {
  assert.deepEqual(f(edit('web/a.ts', 1, [], 2, [`  ${IT_SKIP}('x');`])), []);
  assert.deepEqual(f(edit('docs/a.md', 1, [], 2, [`Do not add ${IT_SKIP}.`])), []);
  assert.deepEqual(f(edit('web/a.spec.ts', 1, [], 2, [`  const w = '${IT_SKIP}(';`])), []);
});

test('a deleted test file is reported once, on line 1', () => {
  assert.deepEqual(f(gone('web/a.spec.ts', ["it('a', () => {});", "it('b', () => {});"])), ['web/a.spec.ts:1: test file deleted']);
});

test('fewer tests in a file than before are reported on the first removed one; a replacement or a new test balances', () => {
  const two = ["  it('a', () => {});", "  it('b', () => {});"];
  assert.deepEqual(f(edit('web/a.spec.ts', 20, two, 20, ["  it('c', () => {});"])), ['web/a.spec.ts:20: net 1 test(s) removed (2 removed, 1 added)']);
  assert.deepEqual(f(edit('app/FooTest.kt', 5, ['  @Test', '  @Test'], 5, [])), ['app/FooTest.kt:5: net 2 test(s) removed (2 removed, 0 added)']);
  assert.deepEqual(f(edit('web/a.spec.ts', 20, two, 20, ["  it('c', () => {});", "  test('d', () => {});", "  it('e', () => {});"])), []);
  assert.deepEqual(f(edit('web/a.spec.ts', 20, ["  it('a', () => {});"], 20, ["  it('a renamed', () => {});"])), []);
});

test('a new suppression is reported in code and test files; the same words in a document or a string are not', () => {
  assert.deepEqual(f(edit('web/b.ts', 4, [], 5, [`  ${DISABLE}`])), ['web/b.ts:5: new suppression']);
  assert.deepEqual(f(edit('web/b.ts', 4, [], 5, [TS_IGNORE])), ['web/b.ts:5: new suppression']);
  assert.deepEqual(f(edit('app/Foo.kt', 4, [], 5, [`${KT_SUPPRESS} fun f() {}`])), ['app/Foo.kt:5: new suppression']);
  assert.deepEqual(f(edit('web/b.spec.ts', 4, [], 5, [`  ${DISABLE}`])), ['web/b.spec.ts:5: new suppression']);
  assert.deepEqual(f(edit('docs/a.md', 4, [], 5, [DISABLE])), []);
  assert.deepEqual(f(edit('tools/c.mjs', 4, [], 5, ["const WORDS = ['eslint-" + "disable', '@ts-" + "ignore'];"])), []);
});

test('a removed suppression is a tightening and is silent', () => {
  assert.deepEqual(f(edit('web/b.ts', 4, [`  ${DISABLE}`], 4, [])), []);
});

test('a mutation entry removed without a replacement is reported on the removed line; an edited or added one is not', () => {
  const entry = (n) => `    { "file": "a.ts", "find": "x${n}", "replace": "y", "expect": "z" },`;
  assert.deepEqual(f(edit('tools/mutations/t.json', 6, [entry(1), entry(2)], 6, [entry(3)])),
    ['tools/mutations/t.json:6: mutation entry removed without a replacement (2 removed, 1 added)']);
  assert.deepEqual(f(edit('tools/mutations/t.json', 6, [entry(1)], 6, [entry(2), entry(3)])), []);
  assert.deepEqual(f(gone('tools/mutations/t.json', ['{', '  "mutations": [', entry(1), '  ]', '}'])),
    ['tools/mutations/t.json:3: mutation entry removed without a replacement (1 removed, 0 added)']);
  assert.deepEqual(f(edit('tools/other.json', 6, [entry(1)], 6, [])), []);
});

test('a raised budget is reported with both values, in any unit; a lowered one is silent', () => {
  const b = (key, v) => `                  "${key}": "${v}"`;
  assert.deepEqual(f(edit('web/angular.json', 54, [b('maximumError', '4mb')], 54, [b('maximumError', '5mb')])),
    ['web/angular.json:54: budget maximumError loosened from 4mb to 5mb']);
  assert.deepEqual(f(edit('web/angular.json', 58, [b('maximumWarning', '8kb')], 58, [b('maximumWarning', '9000b')])),
    ['web/angular.json:58: budget maximumWarning loosened from 8kb to 9000b']);
  assert.deepEqual(f(edit('web/angular.json', 54, [b('maximumError', '4mb')], 54, [b('maximumError', '3mb')])), []);
  assert.deepEqual(f(edit('web/angular.json', 54, [b('maximumError', '4mb')], 54, [b('maximumError', '4096kb')])), []);
  assert.deepEqual(f(edit('web/angular.json', 54, [b('maximumError', '4mb')], 54, [])), ['web/angular.json:54: budget maximumError removed']);
});

test('a new empty catch block is reported, on one line or on two; one with a body is not', () => {
  assert.deepEqual(f(edit('web/c.ts', 1, [], 2, [`  ${EMPTY_CATCH}`])), ['web/c.ts:2: new empty catch block']);
  assert.deepEqual(f(edit('app/Foo.kt', 1, [], 2, ['  } catch (e: Exception) {', '  }'])), ['app/Foo.kt:2: new empty catch block']);
  assert.deepEqual(f(edit('web/c.ts', 1, [], 2, ['  } catch {', '    log();', '  }'])), []);
});

test('findings come back ordered by file and line', () => {
  const diff = edit('web/z.ts', 1, [], 2, [`  ${EMPTY_CATCH}`]) + edit('web/a.ts', 1, [], 7, [TS_IGNORE, `  ${EMPTY_CATCH}`]);
  assert.deepEqual(f(diff), ['web/a.ts:7: new suppression', 'web/a.ts:8: new empty catch block', 'web/z.ts:2: new empty catch block']);
});

// The command line, on a real repository in a temporary directory: `main` with a base commit, then a branch.
const script = path.join(path.dirname(new URL(import.meta.url).pathname), 'check-floor.mjs');
function sh(cwd, ...args) {
  const r = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', ...args], { cwd, encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
}
function scenario({ base, change, message = 'change', originMain = true, env = {} }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'floor-'));
  try {
    const write = (files) => Object.entries(files).forEach(([n, t]) => {
      if (t === null) return fs.rmSync(path.join(dir, n));
      fs.mkdirSync(path.dirname(path.join(dir, n)), { recursive: true });
      fs.writeFileSync(path.join(dir, n), t);
    });
    sh(dir, 'init', '-q', '-b', 'main');
    write(base);
    sh(dir, 'add', '.');
    sh(dir, 'commit', '-q', '-m', 'base');
    if (originMain) sh(dir, 'update-ref', 'refs/remotes/origin/main', 'HEAD');
    sh(dir, 'checkout', '-q', '-b', 'feature');
    write(change);
    sh(dir, 'add', '-A');
    sh(dir, 'commit', '-q', '-m', message);
    const r = spawnSync(process.execPath, [script], { cwd: dir, encoding: 'utf8', env: { ...process.env, FLOOR_GUARD_ALLOW: '', ...env } });
    return { status: r.status, out: r.stdout.split('\n').filter(Boolean), err: r.stderr.split('\n').filter(Boolean) };
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}
const spec = (...names) => names.map((n) => `  it('${n}', () => {});`).join('\n') + '\n';

test('a branch that adds a skip, drops a test and tightens a budget exits 1 with file:line and a reason each', () => {
  const r = scenario({
    base: { 'web/a.spec.ts': spec('a', 'b'), 'web/angular.json': '{\n  "maximumError": "4mb"\n}\n' },
    change: { 'web/a.spec.ts': `  ${IT_SKIP}('a', () => {});\n`, 'web/angular.json': '{\n  "maximumError": "3mb"\n}\n' },
  });
  assert.equal(r.status, 1);
  assert.deepEqual(r.out, [
    'web/a.spec.ts:1: net 2 test(s) removed (2 removed, 0 added)',
    'web/a.spec.ts:1: new test skip or focus',
    '2 loosening(s) of the floor. Fix them, or put a line "Gate-loosening: <why>" in the last commit message and repeat it in the pull request.',
  ]);
});

test('a branch that only adds tests and tightens exits 0', () => {
  const r = scenario({ base: { 'web/a.spec.ts': spec('a') }, change: { 'web/a.spec.ts': spec('a', 'b') } });
  assert.deepEqual({ status: r.status, out: r.out }, { status: 0, out: ['floor guard: no loosening against origin/main.'] });
});

test('a deleted spec file exits 1', () => {
  const r = scenario({ base: { 'web/a.spec.ts': spec('a') }, change: { 'web/a.spec.ts': null } });
  assert.equal(r.status, 1);
  assert.equal(r.out[0], 'web/a.spec.ts:1: test file deleted');
});

test('a Gate-loosening line in the last commit message turns the findings into warnings and exits 0', () => {
  const r = scenario({
    base: { 'web/b.ts': 'x\n' }, change: { 'web/b.ts': `x\n${TS_IGNORE}\n` },
    message: 'chore: x\n\nGate-loosening: generated typings, issue 12',
  });
  assert.deepEqual({ status: r.status, out: r.out }, { status: 0, out: [
    'warning: web/b.ts:2: new suppression', 'allowed by Gate-loosening: generated typings, issue 12'] });
});

test('FLOOR_GUARD_ALLOW carries the same line (CI passes the pull request head commit there)', () => {
  const r = scenario({
    base: { 'web/b.ts': 'x\n' }, change: { 'web/b.ts': `x\n${TS_IGNORE}\n` },
    env: { FLOOR_GUARD_ALLOW: 'merge\n\nGate-loosening: reason given' },
  });
  assert.equal(r.status, 0);
  assert.equal(r.out[1], 'allowed by Gate-loosening: reason given');
});

test('a Gate-loosening line with no reason does not allow anything', () => {
  const r = scenario({ base: { 'web/b.ts': 'x\n' }, change: { 'web/b.ts': `x\n${TS_IGNORE}\n` }, message: 'x\n\nGate-loosening:   ' });
  assert.equal(r.status, 1);
});

test('without origin/main the guard exits 2 and says so, never 0', () => {
  const r = scenario({ base: { 'a.txt': '1\n' }, change: { 'a.txt': '2\n' }, originMain: false });
  assert.equal(r.status, 2);
  assert.deepEqual(r.err, ['floor guard cannot run: no origin/main ref. Fix: git fetch --no-tags origin main:refs/remotes/origin/main']);
});

test('outside a git repository the guard exits 2', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'floor-none-'));
  try {
    const r = spawnSync(process.execPath, [script], { cwd: dir, encoding: 'utf8', env: { ...process.env, GIT_CEILING_DIRECTORIES: path.dirname(dir) } });
    assert.equal(r.status, 2);
    assert.match(r.stderr, /^floor guard cannot run: not a git repository/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
