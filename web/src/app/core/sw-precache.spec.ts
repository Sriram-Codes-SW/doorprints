import { describe, expect, it } from 'vitest';
import {
  BUILD_PLACEHOLDER,
  PRECACHE_PLACEHOLDER,
  baseHrefOf,
  buildId,
  cspFromFirebaseConfig,
  injectMetaCsp,
  isAcceptable,
  metaCspFromPolicy,
  navigationPlan,
  precacheList,
  stampManifestId,
  stampServiceWorker,
} from '../../../scripts/sw-precache-core.mjs';
import firebaseJson from '../../../firebase.json';

/**
 * The postbuild stamp that makes `public/sw.js` a per-build worker (`scripts/sw-precache.mjs`, run by npm after
 * `npm run build`). Only the pure half is imported: the runner reads files and hashes their bytes with Node's
 * crypto, which the browser-platform test bundle cannot load.
 *
 * What these pin, and why it matters at runtime:
 *  * the precache list is the whole build and nothing else — anything missing from it is a blank page on the
 *    first offline start;
 *  * the build id is stable for the same build (no spurious "new version" banner, no cache churn) and changes when
 *    any file's bytes change — including the unhashed MapLibre worker a dependency bump replaces in place;
 *  * the stamp is all-or-nothing, so an unstamped or double-stamped worker fails the build instead of shipping.
 */
describe('service worker precache stamp', () => {
  const build = [
    'index.html',
    'main-ABCD2345.js',
    'polyfills-QWER7890.js',
    'styles-ZXCV4567.css',
    'chunk-AB_cd-12.js',
    'maplibre/maplibre-gl-worker.mjs',
    'maplibre/maplibre-gl-shared.mjs',
    'manifest.webmanifest',
    'favicon.svg',
    'icons/icon-192.png',
    'media/font-ABCDEFGH.woff2',
    // Never precached:
    '404.html',
    'sw.js',
    '.DS_Store',
    'main-ABCD2345.js.map',
  ];

  describe('precacheList', () => {
    it('lists every file of the build, sorted, without a 404 page, dotfiles, source maps or the worker itself', () => {
      expect(precacheList(build)).toEqual([
        'chunk-AB_cd-12.js',
        'favicon.svg',
        'icons/icon-192.png',
        'index.html',
        'main-ABCD2345.js',
        'manifest.webmanifest',
        'maplibre/maplibre-gl-shared.mjs',
        'maplibre/maplibre-gl-worker.mjs',
        'media/font-ABCDEFGH.woff2',
        'polyfills-QWER7890.js',
        'styles-ZXCV4567.css',
      ]);
    });

    it('leaves the install-sheet screenshots out (only the browser install UI asks for them)', () => {
      const withShots = [...build, 'screenshots/map-narrow.png', 'screenshots/house-wide.png'];
      expect(precacheList(withShots)).toEqual(precacheList(build));
      expect(precacheList([...build, 'icons/shortcut-map.png'])).toContain('icons/shortcut-map.png');
      // The 32 px tab icon index.html lists first: offline, the tab keeps its icon.
      expect(precacheList([...build, 'icons/favicon-32.png'])).toContain('icons/favicon-32.png');
    });

    it("precaches the map's India boundary data, so the boundary is right offline once the worker is installed", () => {
      // web/public/geo/in-boundaries.geojson is copied by the `public` assets glob; `shared/india-boundaries.ts`
      // loads it from the same path relative to the base href, and sw.js answers it cache-first from PRECACHED.
      expect(precacheList([...build, 'geo/in-boundaries.geojson'])).toContain('geo/in-boundaries.geojson');
    });

    it('does not depend on the order or the separator the file system reports', () => {
      const windows = [...build].reverse().map((p) => p.replaceAll('/', '\\'));
      expect(precacheList(windows)).toEqual(precacheList(build));
      expect(precacheList(['index.html', './index.html'])).toEqual(['index.html']);
    });

    it('refuses an output with no app shell, and a path that leaves the directory', () => {
      expect(() => precacheList(['main-ABCD2345.js'])).toThrow(/index\.html/);
      expect(() => precacheList(['index.html', '../secret.txt'])).toThrow(/relative/);
      expect(() => precacheList(['index.html', '/etc/passwd'])).toThrow(/relative/);
    });
  });

  describe('buildId', () => {
    const entries = precacheList(build).map((path) => ({ path, digest: `sha256-of-${path}` }));

    it('is 16 hex characters and the same for the same build, in any order', () => {
      const id = buildId(entries);
      expect(id).toMatch(/^[0-9a-f]{16}$/);
      expect(buildId([...entries].reverse())).toBe(id);
      expect(buildId(entries.map((e) => ({ ...e })))).toBe(id);
    });

    it('is 64-bit FNV-1a, whose value for no input is the offset basis', () => {
      expect(buildId([])).toBe('cbf29ce484222325');
    });

    it('changes when an unhashed file changes its bytes under the same name', () => {
      // A maplibre-gl bump replaces maplibre/maplibre-gl-worker.mjs in place: same path, new digest. That alone
      // has to give a new worker and a new cache, or a new main bundle meets last month's cached map worker.
      const bumped = entries.map((e) =>
        e.path === 'maplibre/maplibre-gl-worker.mjs' ? { ...e, digest: 'sha256-of-the-new-worker' } : e,
      );
      expect(buildId(bumped)).not.toBe(buildId(entries));
    });

    it('changes when a file is added or renamed', () => {
      expect(buildId([...entries, { path: 'chunk-NEWCHUNK.js', digest: 'x' }])).not.toBe(buildId(entries));
      const renamed = entries.map((e) => (e.path === 'main-ABCD2345.js' ? { ...e, path: 'main-EFGH6789.js' } : e));
      expect(buildId(renamed)).not.toBe(buildId(entries));
    });
  });

  describe('stampServiceWorker', () => {
    // The two lines of public/sw.js the stamp touches, as written there.
    const template = `const BUILD = ${BUILD_PLACEHOLDER};\nconst PRECACHE = ${PRECACHE_PLACEHOLDER};\n`;
    const read = (source: string) => new Function(`${source}; return { BUILD, PRECACHE };`)() as {
      BUILD: string;
      PRECACHE: string[];
    };

    it('writes the id and the list in as JavaScript the worker can run', () => {
      const precache = ['index.html', 'main-ABCD2345.js', 'maplibre/maplibre-gl-worker.mjs'];
      const stamped = stampServiceWorker(template, { id: '0123456789abcdef', precache });
      expect(read(stamped)).toEqual({ BUILD: '0123456789abcdef', PRECACHE: precache });
      // Unstamped, the template is valid JavaScript too — that is what `ng serve` serves — with an empty list.
      expect(read(template)).toEqual({ BUILD: '__BUILD_ID__', PRECACHE: [] });
    });

    it('inserts file names literally, whatever characters they contain', () => {
      // String.prototype.replace would expand `$&` in a replacement string into the matched placeholder.
      const stamped = stampServiceWorker(template, { id: '0123456789abcdef', precache: ['index.html', 'a$&b.js'] });
      expect(read(stamped).PRECACHE).toEqual(['index.html', 'a$&b.js']);
    });

    it('fails instead of shipping a worker that was stamped twice, never, or with a bad id', () => {
      const once = stampServiceWorker(template, { id: '0123456789abcdef', precache: ['index.html'] });
      expect(() => stampServiceWorker(once, { id: '0123456789abcdef', precache: ['index.html'] })).toThrow(
        /exactly once/,
      );
      expect(() => stampServiceWorker('self.addEventListener("fetch", () => {});', {
        id: '0123456789abcdef',
        precache: [],
      })).toThrow(/exactly once/);
      expect(() => stampServiceWorker(template, { id: '__BUILD_ID__', precache: [] })).toThrow(/build id/);
    });
  });

  describe('the CSP <meta>, defence in depth for hosts or copies that ignore web/firebase.json', () => {
    const policy = "default-src 'self'; script-src 'self'; frame-src 'self' blob:; frame-ancestors 'none'";
    const config = (headers: unknown) =>
      JSON.stringify({ hosting: { site: 'doorprints', public: 'dist/web/browser', headers } });
    const everywhere = (value: string) => ({
      source: '**',
      headers: [
        { key: 'Content-Security-Policy', value },
        { key: 'X-Frame-Options', value: 'DENY' },
      ],
    });

    it('takes the policy from the "**" header rule of firebase.json', () => {
      const manifest = { source: '/manifest.webmanifest', headers: [{ key: 'Content-Type', value: 'x' }] };
      expect(cspFromFirebaseConfig(config([everywhere(policy), manifest]))).toBe(policy);
      expect(cspFromFirebaseConfig(config([{ source: '**', headers: [{ key: 'X-Frame-Options', value: 'DENY' }] }])))
        .toBeNull();
      expect(cspFromFirebaseConfig(config(undefined))).toBeNull();
    });

    it('refuses a config the <meta> could disagree with, or one that is not a single hosting object', () => {
      const deepOnly = { source: '/houses/**', headers: [{ key: 'content-security-policy', value: policy }] };
      expect(() => cspFromFirebaseConfig(config([everywhere(policy), deepOnly]))).toThrow(/only in the "\*\*" rule/);
      expect(() => cspFromFirebaseConfig(config([everywhere(policy), everywhere(policy)]))).toThrow(/more than once/);
      expect(() => cspFromFirebaseConfig(JSON.stringify({ hosting: [{ headers: [] }] }))).toThrow(/one "hosting"/);
      expect(() => cspFromFirebaseConfig('{ "hosting": ')).toThrow(/not valid JSON/);
    });

    it('drops what a <meta> policy cannot carry', () => {
      expect(metaCspFromPolicy(policy)).toBe("default-src 'self'; script-src 'self'; frame-src 'self' blob:");
      expect(metaCspFromPolicy("frame-ancestors 'none'; report-uri /csp")).toBeNull();
    });

    it('matches the policy the live host sends (web/firebase.json itself)', () => {
      const live = cspFromFirebaseConfig(JSON.stringify(firebaseJson));
      expect(live).toContain("frame-ancestors 'none'");
      expect(live).toContain("frame-src 'self' blob:");
      expect(metaCspFromPolicy(live ?? '')).not.toContain('frame-ancestors');
    });

    it('puts it right after <meta charset>, once', () => {
      const html = '<!doctype html>\n<html>\n<head>\n  <meta charset="utf-8">\n  <title>x</title>\n</head></html>';
      const out = injectMetaCsp(html, "default-src 'self'");
      expect(out).toContain(
        '<meta charset="utf-8">\n  <meta http-equiv="Content-Security-Policy" content="default-src \'self\'">\n  <title>',
      );
      expect(() => injectMetaCsp(out, "default-src 'self'")).toThrow(/already/);
      expect(() => injectMetaCsp('<p>no head</p>', "default-src 'self'")).toThrow(/no <meta charset>/);
    });
  });

  /**
   * Navigations used to go to the network first with no timeout: on a connection that neither works nor fails —
   * the normal house-hunting signal — a local-first app showed a blank page for tens of seconds with this build's
   * shell in the cache. They are now answered from the cache first, which also means a slow or hanging network
   * can never delay the start at all (there is no timeout left to get wrong). `public/sw.js` has the same body.
   */
  describe('navigationPlan (the worker answers every app route from the cached shell)', () => {
    const precache = ['index.html', 'main-ABCD2345.js', 'manifest.webmanifest', 'icons/icon-192.png'];

    it.each(['/', '/doorprints/'])('serves the shell for the app directory and every client route under %s', (basePath) => {
      for (const route of ['', 'index.html', 'houses/42', 'houses/new', 'share', 'data', 'compare']) {
        expect(navigationPlan(`${basePath}${route}`, { basePath, precache }), route).toBe('shell');
      }
    });

    it("opens this build's own copy of a precached file, never the app in its place", () => {
      expect(navigationPlan('/doorprints/manifest.webmanifest', { basePath: '/doorprints/', precache })).toBe('file');
      expect(navigationPlan('/doorprints/icons/icon-192.png', { basePath: '/doorprints/', precache })).toBe('file');
    });

    it('leaves any other file to the network', () => {
      expect(navigationPlan('/doorprints/404.html', { basePath: '/doorprints/', precache })).toBe('network');
      expect(navigationPlan('/doorprints/notes/plan.pdf', { basePath: '/doorprints/', precache })).toBe('network');
    });
  });

  /**
   * What `install` may store. A host's SPA fallback answers a file that no longer exists with the HTML shell and
   * status 200 (Firebase's `**` rewrite does), and a captive portal answers everything with its sign-in page; either
   * stored under a script's name is a blank page offline and a failed install on every later visit. The same test
   * decides whether a content-hashed file from the HTTP cache is re-downloaded. `public/sw.js` has the same body.
   */
  describe('isAcceptable (what the worker may precache under a name)', () => {
    const answer = (status: number, contentType: string | null) => ({
      ok: status >= 200 && status < 300,
      headers: { get: (name: string) => (name.toLowerCase() === 'content-type' ? contentType : null) },
    });
    const html = answer(200, 'text/html; charset=utf-8');

    it('takes the shell only as HTML', () => {
      expect(isAcceptable('index.html', true, html)).toBe(true);
      expect(isAcceptable('index.html', true, answer(200, 'application/json'))).toBe(false);
      expect(isAcceptable('index.html', true, answer(200, null))).toBe(false);
    });

    it('refuses HTML under any other name: the SPA fallback or a captive portal, never the file', () => {
      expect(isAcceptable('chunk-AB_cd-12.js', false, html)).toBe(false);
      expect(isAcceptable('main-ABCD2345.js', false, answer(200, 'text/javascript'))).toBe(true);
      expect(isAcceptable('media/font-ABCDEFGH.woff2', false, answer(200, 'font/woff2'))).toBe(true);
    });

    it("does not insist on a script type, so a host's odd type for .mjs or none at all still installs", () => {
      expect(isAcceptable('maplibre/maplibre-gl-worker.mjs', false, answer(200, 'application/octet-stream'))).toBe(true);
      expect(isAcceptable('icons/icon-192.png', false, answer(200, null))).toBe(true);
      // web/firebase.json types .geojson explicitly; any non-HTML type would do.
      expect(isAcceptable('geo/in-boundaries.geojson', false, answer(200, 'application/geo+json'))).toBe(true);
      expect(isAcceptable('geo/in-boundaries.geojson', false, html)).toBe(false);
    });

    it('lets an .html file be HTML, and refuses every failure', () => {
      expect(isAcceptable('offline.html', false, html)).toBe(true);
      expect(isAcceptable('main-ABCD2345.js', false, answer(404, 'text/javascript'))).toBe(false);
      expect(isAcceptable('index.html', true, answer(503, 'text/html'))).toBe(false);
    });
  });

  describe('the manifest id (per deployment, written from <base href>)', () => {
    const manifest = JSON.stringify({ name: 'Doorprints', short_name: 'Doorprints', start_url: './', scope: './' });

    it('reads the base path from the built index.html', () => {
      expect(baseHrefOf('<head>\n  <base href="/doorprints/">\n</head>')).toBe('/doorprints/');
      expect(baseHrefOf("<base href='/'>")).toBe('/');
      expect(baseHrefOf('<base target="_self" href="/doorprints">')).toBe('/doorprints/');
      expect(() => baseHrefOf('<head></head>')).toThrow(/no <base href>/);
    });

    it('writes the absolute base path, never a relative id that would name the shared origin root', () => {
      const pages = JSON.parse(stampManifestId(manifest, '/doorprints/')) as Record<string, unknown>;
      expect(pages['id']).toBe('/doorprints/');
      expect(pages['start_url']).toBe('./');
      expect(JSON.parse(stampManifestId(manifest, '/'))['id']).toBe('/');
      // Idempotent on a manifest that already has one, and strict about the path.
      expect(JSON.parse(stampManifestId(stampManifestId(manifest, '/a/'), '/b/'))['id']).toBe('/b/');
      expect(() => stampManifestId(manifest, 'doorprints')).toThrow(/bad base path/);
    });
  });
});
