#!/usr/bin/env node
// Hollow-test guard (docs/14 §7). A spec must exercise production code: it has to import at least one module that is
// not a spec or a test helper, and at least one symbol it imports from such a module has to be used somewhere beyond its
// import line. This catches the two shapes of test that cannot fail: one that never imports what it claims to test
// (the code under test re-implemented inside the test) and one that imports it and never calls it.
//
//   node tools/check-specs.mjs [dir ...]      default: web/src
//
// Exit 1 and one line per offending spec. A spec that needs an exception adds the line
//   // check-specs: allow-no-production-import (why)
// near its top. No dependencies: plain Node, regex over the source text.
import fs from 'node:fs';
import path from 'node:path';

const roots = process.argv.slice(2).length ? process.argv.slice(2) : ['web/src'];
const ALLOW = 'check-specs: allow-no-production-import';

function* specs(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules') continue;
      yield* specs(full);
    } else if (entry.name.endsWith('.spec.ts')) yield full;
  }
}

// Modules that are test tooling, not code under test.
const isTooling = (from) =>
  !from.startsWith('.') || /(\.spec|\.mock|\.fake|\.testing|test-helpers?|-fakes?)(\.ts|\.mjs)?$/.test(from) || /\/testing\//.test(from);

/** The imported names and the module they come from, for every import statement of the source. */
export function importsOf(source) {
  const found = [];
  const re = /import\s+(?:type\s+)?(?:([A-Za-z_$][\w$]*)\s*,?\s*)?(?:\{([^}]*)\}|\*\s+as\s+([A-Za-z_$][\w$]*))?\s*from\s*['"]([^'"]+)['"]/g;
  for (const m of source.matchAll(re)) {
    const names = [];
    if (m[1]) names.push(m[1]);
    if (m[3]) names.push(m[3]);
    if (m[2]) {
      for (const part of m[2].split(',')) {
        const t = part.trim().replace(/^type\s+/, '');
        if (!t) continue;
        names.push(t.split(/\s+as\s+/).pop().trim());
      }
    }
    found.push({ from: m[4], names, whole: m[0] });
  }
  return found;
}

/** The problems with one spec's source, empty when fine. Pure, tested in tools/check-specs.test.mjs. */
export function specProblems(source) {
  if (source.includes(ALLOW)) return [];
  const imports = importsOf(source).filter((i) => !isTooling(i.from));
  if (imports.length === 0) return ['imports no production module (the code under test must be imported, not re-implemented)'];
  let body = source;
  for (const i of importsOf(source)) body = body.replace(i.whole, '');
  // Comments do not exercise anything; a '//' right after ':' is a URL, not a comment.
  body = body.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`\\])\/\/.*$/gm, '$1');
  // A spec that ignores one of its imports is only untidy; one that uses none of them tests nothing it imports.
  const names = imports.flatMap((i) => i.names);
  const used = names.filter((name) => new RegExp(`(?<![\\w$.])${name.replace(/\$/g, '\\$')}(?![\\w$])`).test(body));
  if (names.length > 0 && used.length === 0) {
    return [`imports ${names.join(', ')} from production code but uses none of it`];
  }
  return [];
}

if (import.meta.url === `file://${process.argv[1]}`) {
  let bad = 0;
  let count = 0;
  for (const root of roots) {
    for (const file of specs(root)) {
      count++;
      for (const p of specProblems(fs.readFileSync(file, 'utf8'))) {
        console.log(`${file}: ${p}`);
        bad++;
      }
    }
  }
  console.log(`${count} spec files checked, ${bad} problem(s).`);
  process.exit(bad ? 1 : 0);
}
