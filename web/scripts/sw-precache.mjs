#!/usr/bin/env node
/*
 * Postbuild stamp for the service worker (S4-05, docs/11 §5.10 "Caching").
 *
 * Runs automatically after `npm run build` — npm runs a `postbuild` script after `build`, and the extra arguments
 * of `npm run build -- --base-href=…` are passed to `ng build` only, never here — so CI builds and every host whose
 * build command is `npm run build` get a stamped worker.
 *
 * What it does to dist/web/browser, in this order (the build id covers the final bytes of every precached file,
 * so index.html and the manifest are changed before anything is hashed):
 *
 *  0. writes the manifest's `id` from index.html's <base href> (`/` on Firebase Hosting; see stampManifestId);
 *  1. writes the Content-Security-Policy of `web/firebase.json` (the `**` header rule, the single source of the
 *     policy) into index.html as a <meta>: Firebase Hosting sends the header, and the <meta> is defence in depth
 *     for any host or copy that ignores `firebase.json` (web/README.md "Deploy" says why);
 *  2. lists the build output (scripts/sw-precache-core.mjs decides what is left out);
 *  3. computes a build id from each file's path and SHA-256;
 *  4. writes the id and the list into sw.js, replacing its two placeholders;
 *  5. runs `node --check` on the stamped sw.js, so a stamp that broke the syntax fails the build here rather than
 *     leaving every user on the previous worker with nothing in any log.
 *
 * Usage: node scripts/sw-precache.mjs [output directory]   (default: dist/web/browser)
 * The policy is always read from web/firebase.json, whatever the output directory.
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  baseHrefOf,
  buildId,
  cspFromFirebaseConfig,
  injectMetaCsp,
  metaCspFromPolicy,
  precacheList,
  stampManifestId,
  stampServiceWorker,
} from './sw-precache-core.mjs';

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = resolve(webRoot, process.argv[2] ?? join('dist', 'web', 'browser'));
/** The Firebase Hosting config: host behaviour and the one copy of the Content-Security-Policy. */
const firebaseConfig = join(webRoot, 'firebase.json');

/** Every regular file under `dir`, as a path relative to `root`. No `recursive: true`: plain walk, any Node. */
function walk(dir, root = dir) {
  const files = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) files.push(...walk(full, root));
    else if (entry.isFile()) files.push(relative(root, full));
  }
  return files;
}

function main() {
  const at = (rel) => join(outDir, ...rel.split('/'));

  // 0. The manifest's id, from this build's <base href> (before anything is fingerprinted).
  const basePath = baseHrefOf(readFileSync(at('index.html'), 'utf8'));
  writeFileSync(at('manifest.webmanifest'), stampManifestId(readFileSync(at('manifest.webmanifest'), 'utf8'), basePath));

  // 1. CSP into index.html, from web/firebase.json.
  const header = cspFromFirebaseConfig(readFileSync(firebaseConfig, 'utf8'));
  if (!header) throw new Error('sw-precache: web/firebase.json sets no Content-Security-Policy in its "**" rule');
  const policy = metaCspFromPolicy(header);
  if (!policy) throw new Error('sw-precache: the Content-Security-Policy in web/firebase.json has nothing a <meta> can carry');
  writeFileSync(at('index.html'), injectMetaCsp(readFileSync(at('index.html'), 'utf8'), policy));

  // 2. + 3. List and fingerprint.
  const precache = precacheList(walk(outDir));
  let bytes = 0;
  const entries = precache.map((path) => {
    const content = readFileSync(at(path));
    bytes += content.length;
    return { path, digest: createHash('sha256').update(content).digest('hex') };
  });
  const id = buildId(entries);

  // 4. Stamp.
  const swPath = at('sw.js');
  writeFileSync(swPath, stampServiceWorker(readFileSync(swPath, 'utf8'), { id, precache }));

  // 5. Syntax check of exactly the bytes that ship.
  execFileSync(process.execPath, ['--check', swPath], { stdio: 'inherit' });

  const mb = (bytes / (1024 * 1024)).toFixed(2);
  console.log(`sw-precache: build ${id}, ${precache.length} files (${mb} MB) precached, CSP <meta> in index.html, manifest id ${basePath}`);
  // Everything precached counts against the same origin quota as the user's photos in IndexedDB.
  if (bytes > 15 * 1024 * 1024) {
    console.log(`::warning title=sw-precache::the precache is ${mb} MB; it shares the origin quota with photos`);
  }
}

try {
  main();
} catch (err) {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
}
