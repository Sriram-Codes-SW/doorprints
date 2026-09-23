/*
 * Pure half of the service-worker build stamp (scripts/sw-precache.mjs runs it after `npm run build`).
 *
 * No imports and no Node APIs on purpose: `src/app/core/sw-precache.spec.ts` imports this file into the Vitest
 * run, which Angular's unit-test builder bundles for the browser platform, so anything from `node:` would not
 * resolve there. Reading files, hashing their bytes and writing sw.js stay in the runner.
 */

/**
 * Build-output files that are never precached.
 *
 *  * `404.html` — not produced: on Firebase Hosting (the live host) the SPA rewrite in `web/firebase.json` answers
 *    every path that is not a file with /index.html, so the build needs none (web/README.md "Deploy"). Still
 *    excluded in case a build for another host adds one: a precached 404 page is never what the app wants.
 *  * `sw.js` — the worker itself: the browser fetches it for update checks, and caching it would be circular.
 *
 * Host configuration is not in the build output at all: `web/firebase.json` sits next to `package.json`, outside
 * `public/`, so nothing here has to keep it out (the Cloudflare-era `_headers` and `_redirects` are gone).
 */
export const EXCLUDED = Object.freeze(['404.html', 'sw.js']);

/**
 * Build-output folders that are never precached: `screenshots/` holds the manifest's install-sheet pictures
 * (about 80 KB each), which only the browser's install UI ever asks for. Precaching them would make every user
 * download them on every new build, on the metered connections this app is used on, for nothing the app shows.
 */
export const EXCLUDED_DIRS = Object.freeze(['screenshots/']);

/** The two literals in `public/sw.js` that the stamp replaces; each must appear there exactly once. */
export const BUILD_PLACEHOLDER = "'__BUILD_ID__'";
export const PRECACHE_PLACEHOLDER = '[/*__PRECACHE__*/]';

/**
 * The files the worker downloads on `install`, as paths relative to the deployment directory ("BASE" in sw.js).
 *
 * Input: every file under dist/web/browser, relative, with either separator. Output: sorted (code-unit order, so
 * the result does not depend on the machine's locale or on readdir order), de-duplicated, `/`-separated, without
 * the {@link EXCLUDED} files, dotfiles and source maps. Throws when the input is not a plausible production
 * build — no index.html, or a path that climbs out of the directory — because a worker stamped from such a list
 * would install "successfully" and then start offline to a blank page.
 */
export function precacheList(files) {
  const out = new Set();
  for (const raw of files) {
    const path = String(raw).replaceAll('\\', '/').replace(/^\.\//, '');
    if (path === '' || path.startsWith('/') || path.split('/').some((seg) => seg === '..' || seg === '')) {
      throw new Error(`sw-precache: refusing path "${raw}": not a plain relative path`);
    }
    if (EXCLUDED.includes(path)) continue;
    if (EXCLUDED_DIRS.some((dir) => path.startsWith(dir))) continue;
    if (path.split('/').some((seg) => seg.startsWith('.'))) continue;
    if (path.endsWith('.map')) continue;
    out.add(path);
  }
  const list = [...out].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  if (!list.includes('index.html')) {
    throw new Error('sw-precache: no index.html in the build output; is this the right directory?');
  }
  return list;
}

/**
 * A short, stable id for one build: 16 lowercase hex characters.
 *
 * Input: one `{ path, digest }` per precached file, where `digest` is a hash of the file's **bytes** (the runner
 * uses SHA-256). Hashing contents rather than names alone matters for the files whose names carry no hash:
 * a maplibre-gl bump changes `maplibre/maplibre-gl-worker.mjs` under the same name, and that alone must produce
 * a new id — a new sw.js, a new cache, and the update banner.
 *
 * The combining step is 64-bit FNV-1a over a canonical, order-independent text of the entries. It is not a
 * security boundary — the id only names a cache and makes sw.js differ between builds — so a small pure function
 * that runs identically in Node and in the test bundle is the right tool; the per-file digests carry the strength.
 */
export function buildId(entries) {
  const lines = entries
    .map((e) => `${String(e.path)}\u0000${String(e.digest)}`)
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const bytes = new TextEncoder().encode(lines.join('\n'));
  const prime = BigInt('0x100000001b3');
  let hash = BigInt('0xcbf29ce484222325');
  for (const byte of bytes) {
    hash ^= BigInt(byte);
    hash = BigInt.asUintN(64, hash * prime);
  }
  return hash.toString(16).padStart(16, '0');
}

/**
 * `public/sw.js` with this build's id and precache list written in.
 *
 * Each placeholder must occur exactly once; anything else is a build error rather than a worker that silently
 * keeps `__BUILD_ID__` (which sw.js treats as "unstamped, cache nothing") or stamps the wrong literal.
 */
export function stampServiceWorker(source, { id, precache }) {
  if (!/^[0-9a-f]{16}$/.test(id)) throw new Error(`sw-precache: bad build id "${id}"`);
  let out = String(source);
  for (const [token, value] of [
    [BUILD_PLACEHOLDER, JSON.stringify(id)],
    [PRECACHE_PLACEHOLDER, JSON.stringify(precache, null, 2)],
  ]) {
    const count = out.split(token).length - 1;
    if (count !== 1) {
      throw new Error(`sw-precache: expected ${token} exactly once in sw.js, found ${count}; already stamped?`);
    }
    out = out.replace(token, () => value);
  }
  return out;
}

/**
 * The `Content-Security-Policy` that `web/firebase.json` sends on every path: the value of the header with that
 * key in the `hosting.headers` rule whose `source` is `**`.
 *
 * That file is the single source of the policy (Firebase Hosting sends it as a header, and the postbuild step
 * copies it into index.html as a `<meta>`, see {@link metaCspFromPolicy}), so this is strict about its shape: it
 * throws when the text is not JSON, when `hosting` is not one object, when the policy is set by any rule other
 * than `**` (then a deep link and a file could get different policies, and the `<meta>` would match only one of
 * them), or when it is set twice (Firebase's rule for two values of one header is undocumented). Returns null
 * when no rule sets a policy at all; the runner turns that into a build error.
 */
export function cspFromFirebaseConfig(configText) {
  let config;
  try {
    config = JSON.parse(String(configText));
  } catch (err) {
    throw new Error(`sw-precache: web/firebase.json is not valid JSON (${err instanceof Error ? err.message : err})`);
  }
  const hosting = config && typeof config === 'object' ? config.hosting : undefined;
  if (!hosting || typeof hosting !== 'object' || Array.isArray(hosting)) {
    throw new Error('sw-precache: web/firebase.json must have exactly one "hosting" object');
  }
  const rules = Array.isArray(hosting.headers) ? hosting.headers : [];
  const found = [];
  for (const rule of rules) {
    const headers = rule && Array.isArray(rule.headers) ? rule.headers : [];
    for (const header of headers) {
      if (!header || String(header.key).toLowerCase() !== 'content-security-policy') continue;
      if (rule.source !== '**') {
        throw new Error(
          `sw-precache: web/firebase.json sets Content-Security-Policy for "${rule.source ?? rule.regex}"; ` +
            'set it only in the "**" rule',
        );
      }
      found.push(String(header.value ?? ''));
    }
  }
  if (found.length > 1) throw new Error('sw-precache: web/firebase.json sets Content-Security-Policy more than once');
  const policy = found.length === 1 ? found[0].trim() : '';
  return policy === '' ? null : policy;
}

/**
 * A `Content-Security-Policy` value reduced to what a `<meta http-equiv>` may carry.
 *
 * Firebase Hosting sends the header; a copy of the build on a host that ignores `web/firebase.json` (a plain
 * static server, `npx serve dist/web/browser`) would serve the app with no CSP at all, so the postbuild step also
 * writes the policy into index.html as defence in depth (web/README.md "Deploy" says why it is kept).
 * `frame-ancestors`, `report-uri`, `report-to` and `sandbox` are ignored in a meta policy (CSP3 §6.1 / HTML), and
 * browsers log a warning for each, so they are dropped here. Returns null when nothing is left.
 */
export function metaCspFromPolicy(policy) {
  const dropped = new Set(['frame-ancestors', 'report-uri', 'report-to', 'sandbox']);
  const kept = String(policy ?? '')
    .split(';')
    .map((d) => d.trim())
    .filter((d) => d !== '' && !dropped.has(d.split(/\s+/)[0].toLowerCase()));
  return kept.length > 0 ? kept.join('; ') : null;
}

/**
 * index.html with a `<meta http-equiv="Content-Security-Policy">` inserted right after `<meta charset>` (so the
 * charset stays within the first 1024 bytes) or, failing that, right after `<head>`. Refuses a document that
 * already has one, and one with neither anchor.
 */
export function injectMetaCsp(html, policy) {
  const source = String(html);
  if (/http-equiv=["']?Content-Security-Policy/i.test(source)) {
    throw new Error('sw-precache: index.html already has a Content-Security-Policy <meta>; stamped twice?');
  }
  if (/["<>]/.test(policy)) throw new Error('sw-precache: the policy contains a character that needs escaping');
  const tag = `<meta http-equiv="Content-Security-Policy" content="${policy}">`;
  const charset = /<meta\s+charset=[^>]*>/i.exec(source);
  const head = /<head(?:\s[^>]*)?>/i.exec(source);
  const anchor = charset ?? head;
  if (!anchor) throw new Error('sw-precache: index.html has no <meta charset> and no <head>');
  const at = anchor.index + anchor[0].length;
  return `${source.slice(0, at)}\n  ${tag}${source.slice(at)}`;
}

/**
 * How the service worker answers a navigation to `pathname` inside the deployment at `basePath`:
 *
 *  * `'shell'` — this build's index.html from the cache: the app shell for `/`, `index.html` and every client
 *    route (`/houses/42`, `/share`, `/data`), whatever the network is doing;
 *  * `'file'` — this build's own copy of a precached file opened directly (`manifest.webmanifest`, an icon);
 *  * `'network'` — any other path with a file extension (`404.html`, a file added on the host by hand): left to
 *    the browser, so the app never stands in for something that is not a route.
 *
 * **Paired with `navigationPlan` in `public/sw.js`**, which computes the same thing from its own BASE and
 * PRECACHE (a worker cannot import this module); the two bodies must stay the same.
 */
export function navigationPlan(pathname, { basePath, precache }) {
  const base = new URL(String(basePath), 'https://doorprints.invalid');
  const shell = new URL('index.html', base).pathname;
  const precached = new Set(precache.map((path) => new URL(path, base).pathname));
  if (pathname === shell || pathname === base.pathname) return 'shell';
  if (precached.has(pathname)) return 'file';
  const last = pathname.slice(pathname.lastIndexOf('/') + 1);
  return last.includes('.') ? 'network' : 'shell';
}

/**
 * True when `response` may be stored in the precache as `path`: a success, and HTML only for the app shell and
 * `.html` files. The shell must be HTML; nothing else may be, because an HTML page under a script's name is a
 * host's SPA fallback (Firebase Hosting's `**` rewrite answers a missing file with index.html and status 200) or
 * a captive portal's sign-in page, never the file. Only HTML is refused, not "anything but JavaScript", so a
 * host's odd type for `.mjs` cannot turn into "no offline start at all".
 *
 * **Paired with `isAcceptable` in `public/sw.js`** (a worker cannot import this module); the two bodies must stay
 * the same. `response` needs only `ok` and `headers.get`, so the tests can pass a plain object.
 */
export function isAcceptable(path, isShell, response) {
  if (!response.ok) return false;
  const html = (response.headers.get('content-type') || '').includes('text/html');
  return isShell ? html : !html || path.endsWith('.html');
}

/**
 * The path of `<base href>` in a built index.html (`/` on Firebase Hosting; a sub-path such as `/doorprints/` when
 * built for one), always starting and ending in `/`. Throws when there is none: the router and every PWA path
 * depend on it, and so does the manifest `id` below.
 */
export function baseHrefOf(html) {
  const match = /<base\s[^>]*href\s*=\s*["']([^"']*)["']/i.exec(String(html));
  if (!match) throw new Error('sw-precache: index.html has no <base href>');
  const path = new URL(match[1], 'https://doorprints.invalid/').pathname;
  return path.endsWith('/') ? path : `${path}/`;
}

/**
 * manifest.webmanifest with an `id` for **this** deployment: the base path (`/` on Firebase Hosting, or a sub-path
 * such as `/doorprints/`).
 *
 * `id` is resolved against the origin of `start_url`, not against the manifest URL, so a relative `"id": "./"`
 * in `public/` would name the origin root for every app deployed under it (on a shared host such as
 * `https://<owner>.github.io/`, every project site of that owner).
 * The build knows its base href, so it writes the absolute path instead. That is exactly the identity an
 * installed copy already has (with no `id`, the identity is `start_url`, which `"./"` resolves to the same
 * directory), so installs made before this keep being the same app; what the `id` adds is that Chromium's
 * install dialog and update checks no longer depend on `start_url` never changing.
 */
export function stampManifestId(manifestText, basePath) {
  const manifest = JSON.parse(String(manifestText));
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    throw new Error('sw-precache: manifest.webmanifest is not a JSON object');
  }
  const path = String(basePath);
  if (!path.startsWith('/') || !path.endsWith('/')) throw new Error(`sw-precache: bad base path "${path}"`);
  const { id: _previous, ...rest } = manifest;
  const out = {};
  for (const [key, value] of Object.entries(rest)) {
    out[key] = value;
    if (key === 'short_name') out.id = path;
  }
  if (!('id' in out)) out.id = path;
  return `${JSON.stringify(out, null, 2)}\n`;
}
