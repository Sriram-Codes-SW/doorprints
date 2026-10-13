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

// Pre-merge layout gate (docs/06 TC-S-46, S4b-BL-245): the web app's layout on short phones, in about a minute, with no
// network. A runaway map (14,000-21,000px tall, its canvas at the 4,096px texture limit) reached production on 2026-10-12
// because only the live UI test, after the deploy, looked at it. This runs on the build before the merge.
// Usage: node layout-gate.js [distDir | baseUrl]   (default ../../web/dist/web/browser, served by a tiny static server here;
// CHROMIUM=<path> for a local Chromium build). Exits 1 when any check fails.
// For 320x568, 360x640 and 390x664 phones, in English and the light theme, on every route live-ui.js visits, at normal text
// and again at 130% text (and the owner's 384x615 phone at 130%, an iPhone SE in landscape at 568x320, and a 360x740 phone
// in Tamil at 200% text): the shared layoutAudit (live-ui.js's mobile pass): the map no taller than the screen, its canvas
// under 4,096px, the app no taller than the screen, nothing left under the bottom bar at the end of a page, no sideways scroll, no map control over the page's own controls; plus
// that the Map page has a map and a canvas at all (so the checks cannot pass for want of a map), that it loads
// with a layout shift of at most 0.05 (S4b-BL-49: the map jumped by 0.15-0.24 on short phones when a script wrote its
// height after the first paint), and, at normal text and on the owner's phone, that the Map page shows the list's
// heading and every counter above the bottom bar before any scrolling (S4b-BL-49, owner report 2026-09-24).
// Offline and deterministic: every request that is not to the local server is answered by this script (MapLibre's style
// URL with a small valid style that has credits, anything else aborted), so the map's container and canvas exist but nothing depends on a
// network. Not a replacement for tools/live-ui after the deploy, and not for a real phone.
const { chromium, devices } = require('playwright');
const { ROUTES, phoneContext, layoutAudit } = require('./layout-audit');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const VIEWPORTS = [{ width: 320, height: 568 }, { width: 360, height: 640 }, { width: 390, height: 664 }];
const OWNER_PHONE = { width: 384, height: 615 }; // the owner's phone (S4b-BL-49): its text size is 130%, as live-ui.js's owner384-text130
const LARGE_TEXT = 130; // percent: the root font size, as live-ui.js's text-scale hook sets it
// Where live-ui.js found the map taller than the screen on main (2026-10-13): iPhone SE in landscape, and Tamil at 200%.
const LANDSCAPE_SE = { width: 568, height: 320 };
const TALL_TAMIL = { width: 360, height: 740 };
const MAX_CLS = 0.05; // web.dev's "good" is 0.1; the Map page measured 0.000-0.003 after S4b-BL-49 (TC-S-46)
const STYLE_URL = /^https:\/\/tiles\.openfreemap\.org\/styles\/liberty(\?|$)/; // MAP_STYLE_URL in web/src/app/shared/map-style-url.ts
// The style the app really loads has vector sources whose TileJSON carries the credits (OpenFreeMap, OpenMapTiles,
// OpenStreetMap); MapLibre adds them to its attribution control when the source arrives, which made the bottom-right
// control column taller after the first paint (S4b-BL-49 follow-up). So the gate answers with the same kind of style and
// TileJSON, an empty style would hide it. Tiles themselves are still aborted.
const TILEJSON_URL = /^https:\/\/tiles\.openfreemap\.org\/planet(\?|$)/;
const CREDITS = '<a href="https://openfreemap.org" target="_blank">OpenFreeMap</a> <a href="https://www.openmaptiles.org/" target="_blank">&copy; OpenMapTiles</a> Data from <a href="https://www.openstreetmap.org/copyright" target="_blank">OpenStreetMap</a>';
const STYLE = {
  version: 8, name: 'liberty-like',
  sources: { openmaptiles: { type: 'vector', url: 'https://tiles.openfreemap.org/planet' } },
  layers: [
    { id: 'background', type: 'background', paint: { 'background-color': '#f8f4f0' } },
    { id: 'water', type: 'fill', source: 'openmaptiles', 'source-layer': 'water', paint: { 'fill-color': '#9ecae1' } },
  ],
};
const TILEJSON_DELAY_MS = 1500;
const TILEJSON = { tilejson: '3.0.0', name: 'planet', attribution: CREDITS, minzoom: 0, maxzoom: 14, tiles: ['https://tiles.openfreemap.org/planet/20260101_000000_pt/{z}/{x}/{y}.pbf'] };
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.geojson': 'application/geo+json', '.webmanifest': 'application/manifest+json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.pbf': 'application/x-protobuf', '.txt': 'text/plain', '.xml': 'application/xml' };

/** Serves [dir] under the app's base href (read from its index.html), index.html for any path that is not a file. */
function serve(dir) {
  const index = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
  const href = /<base\s+href="([^"]*)"/i.exec(index);
  const prefix = new URL(href ? href[1] : '/', 'http://x').pathname.replace(/\/$/, '');
  const server = http.createServer((req, res) => {
    const pathname = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const inBase = pathname === prefix || pathname.startsWith(prefix + '/');
    const file = inBase ? path.join(dir, path.normalize(pathname.slice(prefix.length))) : null;
    const isFile = file !== null && file.startsWith(dir + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile();
    const send = isFile ? file : path.join(dir, 'index.html');
    res.writeHead(inBase ? 200 : 404, { 'content-type': TYPES[path.extname(send)] || 'application/octet-stream', 'cache-control': 'no-store' });
    res.end(inBase ? fs.readFileSync(send) : '');
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, base: `http://127.0.0.1:${server.address().port}${prefix}` })));
}

/**
 * In the page, before anything else: the layout shift as web-vitals counts it (the largest burst of shifts less than 1s
 * apart and at most 5s long; a shift right after an input does not count). Read back with window.__cls().
 */
function watchShifts() {
  let cls = 0, burst = 0, first = 0, last = 0;
  const moved = []; // what moved, so a failure names the element (a layout shift's sources)
  window.__cls = () => cls;
  window.__shifted = () => moved.join(' ; ');
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        if (e.hadRecentInput) continue;
        const box = (r) => (r ? `y=${Math.round(r.y)},h=${Math.round(r.height)}` : '?');
        for (const s of e.sources || []) {
          const n = s.node;
          const name = n && n.nodeType === 1 ? `${n.tagName}${n.className && typeof n.className === 'string' ? '.' + n.className.trim().split(/\s+/).join('.') : ''}` : '(removed element)';
          moved.push(`${name} ${box(s.previousRect)} -> ${box(s.currentRect)} (${e.value.toFixed(3)} at ${Math.round(e.startTime)} ms)`);
        }
        if (burst && e.startTime - last < 1000 && e.startTime - first < 5000) burst += e.value;
        else { burst = e.value; first = e.startTime; }
        last = e.startTime;
        cls = Math.max(cls, burst);
      }
    }).observe({ type: 'layout-shift', buffered: true });
  } catch { /* no Layout Instability API: nothing is counted */ }
}

/** In the page: what of the Map page's list heading and counters is under the fixed bottom bar at the top (null: none). */
function countersUnderBar() {
  const main = document.querySelector('main');
  const bar = document.querySelector('nav.nav');
  const head = document.querySelector('.panel-head');
  if (!main || !bar || !head || getComputedStyle(bar).position !== 'fixed') return null;
  const top = main.scrollTop;
  main.scrollTop = 0;
  const barTop = bar.getBoundingClientRect().top;
  const stats = document.querySelector('.stats');
  const bottom = Math.max(head.getBoundingClientRect().bottom, stats ? stats.getBoundingClientRect().bottom : 0);
  main.scrollTop = top;
  return bottom > barTop + 1 ? `the list's heading and counters end ${Math.round(bottom - barTop)}px under the bottom bar before scrolling` : null;
}

/** Everything but the local server is answered here: the style and its TileJSON with a small copy of the real ones (credits included), the rest not at all. */
async function offline(ctx, base) {
  const origin = new URL(base).origin;
  await ctx.route('**/*', async (route) => {
    const url = route.request().url();
    if (url.startsWith(origin + '/') || url.startsWith('data:') || url.startsWith('blob:')) return route.continue();
    if (STYLE_URL.test(url)) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(STYLE) });
    if (TILEJSON_URL.test(url)) {
      await new Promise((r) => setTimeout(r, TILEJSON_DELAY_MS)); // the network: the credits come after the first paint, as on the live site
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TILEJSON) });
    }
    return route.abort();
  });
}

async function run() {
  const arg = process.argv[2] || path.join(__dirname, '../../web/dist/web/browser');
  const remote = /^https?:\/\//.test(arg);
  const local = remote ? null : await serve(path.resolve(arg));
  const base = (remote ? arg : local.base).replace(/\/$/, '');
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined, args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });
  const t0 = Date.now();
  const failures = [];
  let checks = 0;
  let worstShift = 0;
  const check = (tag, ok, detail) => { checks++; if (!ok) { failures.push(`${tag}: ${detail}`); console.log(`FAIL ${tag}: ${detail}`); } };
  const pass = async (viewport, text, lang = 'en', { counters = false } = {}) => {
    const { ctx } = await phoneContext(browser, devices, { device: 'Pixel 7', viewport, text, lang });
    await offline(ctx, base);
    await ctx.addInitScript(watchShifts);
    const page = await ctx.newPage();
    for (const route of ROUTES) {
      const tag = `${viewport.width}x${viewport.height}${text ? ` text ${text}%` : ''}${lang === 'en' ? '' : ` ${lang}`} ${route}`;
      try {
        const resp = await page.goto(base + route, { waitUntil: 'load', timeout: 30000 });
        check(`${tag}: loads`, !!resp && (resp.status() < 400 || route === '/does-not-exist'), resp && resp.status());
        await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => null); // offline, so it goes quiet at once
        if (route === '/') {
          const drawn = await page.waitForSelector('.maplibregl-map canvas', { timeout: 15000 }).then(() => true, () => false);
          check(`${tag}: the Map page has a map with a canvas`, drawn, 'no .maplibregl-map canvas (WebGL missing, or the map did not start)');
          // The style's credits come with its TileJSON (delayed above): wait for them, so a shift they cause is in the count.
          const credits = await page.waitForFunction(() => /OpenMapTiles/.test(document.querySelector('.maplibregl-ctrl-attrib')?.textContent || ''), null, { timeout: 15000 }).then(() => true, () => false);
          check(`${tag}: the map shows the style's credits`, credits, 'no OpenMapTiles credit in the attribution control (the style or its TileJSON did not load)');
          await page.waitForTimeout(2000); // the map's resize observers and the layout settle
        } else await page.waitForTimeout(400);
        // The Map page only: /data and /connect still shift on load (S4b-BL-249), which the gate does not hold yet.
        if (route === '/') {
          const cls = await page.evaluate(() => window.__cls());
          worstShift = Math.max(worstShift, cls);
          const shifted = cls > MAX_CLS || process.env.GATE_VERBOSE ? await page.evaluate(() => window.__shifted()) : '';
          if (process.env.GATE_VERBOSE) console.log(`${tag}: shift ${cls.toFixed(3)}; moved: ${shifted || 'nothing'}`);
          check(`${tag}: layout shift on load`, cls <= MAX_CLS, `${cls.toFixed(3)} (at most ${MAX_CLS}); moved: ${shifted || 'unknown'}`);
        }
        if (route === '/' && counters) {
          const under = await page.evaluate(countersUnderBar);
          check(`${tag}: counters above the bottom bar`, under === null, under);
        }
        const found = await page.evaluate(layoutAudit);
        check(`${tag}: layout`, found.length === 0, found.join(' ; '));
      } catch (e) { check(`${tag}: ran`, false, e.message.split('\n')[0]); }
    }
    await ctx.close();
  };
  try {
    await Promise.all([
      ...VIEWPORTS.map((v) => pass(v, undefined, 'en', { counters: true })),
      ...VIEWPORTS.map((v) => pass(v, LARGE_TEXT)),
      pass(OWNER_PHONE, LARGE_TEXT, 'en', { counters: true }),
      pass(LANDSCAPE_SE),
      pass(TALL_TAMIL, 200, 'ta'),
    ]);
  } finally {
    await browser.close();
    if (local) local.server.close();
  }
  console.log(`layout gate: ${checks - failures.length}/${checks} checks passed (largest Map page layout shift ${worstShift.toFixed(3)}) on ${base} in ${Math.round((Date.now() - t0) / 1000)} s`);
  process.exitCode = failures.length ? 1 : 0;
}
run().catch((e) => { console.error(e); process.exit(1); });
