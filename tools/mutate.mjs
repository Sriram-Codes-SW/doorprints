#!/usr/bin/env node
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

// Mutation runner (docs/14 §7). A ticket's acceptance is "each of these one-line changes to production code makes a
// NAMED test fail". The list lives in tools/mutations/<name>.json; this applies each one, runs the spec, checks that a
// test with the expected name failed, and always puts the file back, so the claim is checked, not asserted.
//
//   node tools/mutate.mjs tools/mutations/maplibre-worker-check.json
//
// File format: { "spec": "web/src/app/data/x.spec.ts" (or an array of spec files), "mutations": [
//   { "file": "web/scripts/x.mjs", "find": "a !== b", "replace": "false", "expect": "names both versions" } ] }
// `find` must occur exactly once in `file`; `expect` is a substring of the failing test's name. The spec is run with
// `npx ng test --watch=false --include=<spec>` in web/. Exit 1 when any mutation survives (no failing test, or none
// with the expected name). Plain Node, no dependencies.
//
// Server code: a list with a "maven" array runs `mvn -B -ntp -q <those arguments> test` in backend/ instead (for example
// ["-Dtest=EvalScorerTest"]) and reads Surefire's `<class>.<method> ... <<< FAILURE!` lines. Extra Maven arguments (such
// as -o for offline, or a JAVA_HOME on the PATH) come from the environment variable MUTATE_MAVEN_ARGS.
//
// Android and iPhone code (S4b-FR-39): a list with a "gradle" array runs `./gradlew <those arguments>` in android/
// instead (for example [":ui:testAndroidHostTest", "--tests", "app.doorprints.ui.TourTest"]) and reads Gradle's
// `<class> > <test> FAILED` lines; `spec` is then only the test file's name for the log. Extra Gradle arguments (such as
// --offline) come from the environment variable MUTATE_GRADLE_ARGS. A mutation that no longer compiles fails no test, so
// it counts as survived: write mutations that compile.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');

/** Names of the failing tests in Vitest's output (`FAIL  web  file > suite > test`). */
export function failingTests(output) {
  const clean = output.replace(/\x1b\[[0-9;]*m/g, '');
  return [...clean.matchAll(/^\s*FAIL\s+\S+\s+(.+)$/gm)].map((m) => m[1].trim());
}

/** Names of the failing tests in Gradle's output (`app.x.TourTest > theNameOfTheTest FAILED`, `[host]` suffixes kept). */
export function gradleFailingTests(output) {
  const clean = output.replace(/\x1b\[[0-9;]*m/g, '');
  return [...clean.matchAll(/^(\S+) > (.+?) FAILED\s*$/gm)].map((m) => `${m[1]} > ${m[2].trim()}`);
}

/** Names of the failing tests in Surefire's output (`app.x.FooTest.method -- Time elapsed: 0.1 s <<< FAILURE!`, or `<<< ERROR!`). */
export function mavenFailingTests(output) {
  const clean = output.replace(/\x1b\[[0-9;]*m/g, '');
  const names = [...clean.matchAll(/^\[ERROR\] (\S+)\.([^.\s]+) -- Time elapsed: [^<]*<<< (?:FAILURE|ERROR)!\s*$/gm)].map((m) => `${m[1]} > ${m[2]}`);
  return [...new Set(names)];
}

/** The mutated text, or an error string when `find` does not occur exactly once. */
export function applyMutation(text, { find, replace }) {
  const first = text.indexOf(find);
  if (first < 0) return { error: `find text not found: ${find}` };
  if (text.indexOf(find, first + 1) >= 0) return { error: `find text occurs more than once: ${find}` };
  return { text: text.slice(0, first) + replace + text.slice(first + find.length) };
}

/** What a run says about one mutation: killed by a test with the expected name, or survived. */
export function verdict(failures, expect) {
  if (failures.length === 0) return { killed: false, why: 'SURVIVED: no test failed' };
  if (expect && !failures.some((f) => f.includes(expect))) {
    return { killed: false, why: `SURVIVED: tests failed (${failures.join('; ')}) but none named "${expect}"` };
  }
  return { killed: true, why: `killed by: ${failures.find((f) => !expect || f.includes(expect))}` };
}

function runGradle(gradle) {
  const extra = (process.env.MUTATE_GRADLE_ARGS || '').split(/\s+/).filter(Boolean);
  const r = spawnSync('./gradlew', [...gradle, '--continue', ...extra], {
    cwd: path.join(ROOT, 'android'),
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
  });
  return { output: (r.stdout || '') + (r.stderr || ''), status: r.status };
}

function runMaven(args) {
  const extra = (process.env.MUTATE_MAVEN_ARGS || '').split(/\s+/).filter(Boolean);
  const r = spawnSync('mvn', ['-B', '-ntp', '-q', '-Dsurefire.failIfNoSpecifiedTests=false', ...extra, ...args, 'test'], {
    cwd: path.join(ROOT, 'backend'),
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
  });
  return { output: (r.stdout || '') + (r.stderr || ''), status: r.status };
}

/** The `--include` arguments for a list's `spec`: one spec file, or an array of them when the killing tests are in several. */
export function includeArgs(spec) {
  return [].concat(spec).map((s) => `--include=**/${path.basename(s)}`);
}

function runSpec(spec) {
  const r = spawnSync('npx', ['ng', 'test', '--watch=false', ...includeArgs(spec)], {
    cwd: path.join(ROOT, 'web'),
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  return (r.stdout || '') + (r.stderr || '');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const listFile = process.argv[2];
  if (!listFile) {
    console.error('Usage: node tools/mutate.mjs tools/mutations/<name>.json');
    process.exit(2);
  }
  const { spec, mutations, gradle, maven } = JSON.parse(fs.readFileSync(listFile, 'utf8'));
  // One way to run the spec and read its failures, whichever stack it is on.
  const failures = () => {
    if (maven) {
      const run = runMaven(maven);
      const found = mavenFailingTests(run.output);
      // A mutation that does not compile fails the build without a failing test: that is not a kill.
      return found.length === 0 && run.status !== 0 ? ['BUILD FAILED without a failing test'] : found;
    }
    if (!gradle) return failingTests(runSpec(spec));
    const run = runGradle(gradle);
    const found = gradleFailingTests(run.output);
    // A build that fails without a failing test (a mutation that does not compile) is not a kill.
    return found.length === 0 && run.status !== 0 ? ['BUILD FAILED without a failing test'] : found;
  };
  const base = failures();
  if (base.length > 0) {
    console.log(`The spec already fails without a mutation: ${base.join('; ')}`);
    process.exit(1);
  }
  let survived = 0;
  for (const m of mutations) {
    const file = path.join(ROOT, m.file);
    const original = fs.readFileSync(file, 'utf8');
    const mutated = applyMutation(original, m);
    if (mutated.error) {
      console.log(`ERROR ${m.file}: ${mutated.error}`);
      survived++;
      continue;
    }
    try {
      fs.writeFileSync(file, mutated.text);
      const v = verdict(failures(), m.expect);
      console.log(`${v.killed ? 'ok  ' : 'FAIL'} ${m.file}: ${m.find} -> ${m.replace}  [${v.why}]`);
      if (!v.killed) survived++;
    } finally {
      fs.writeFileSync(file, original);
    }
  }
  console.log(`${mutations.length} mutation(s), ${survived} survived.`);
  process.exit(survived ? 1 : 0);
}
