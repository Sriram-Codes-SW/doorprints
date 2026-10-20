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
// that the Map page has a map and a canvas at all (so the checks cannot pass for want of a map), that every page loads
// with a layout shift of at most 0.05 (S4b-BL-49: the map jumped by 0.15-0.24 on short phones when a script wrote its
// height after the first paint), and, at normal text and on the owner's phone, that the Map page shows the list's
// heading and every counter above the bottom bar before any scrolling (S4b-BL-49, owner report 2026-09-24), unless the map
// is at its floor (--map-floor: MapLibre's controls win, as live-ui.js's rule). That last check also runs in Tamil and
// Telugu, on the Map page only, at those sizes and on a 360x740 phone: Tamil captions wrap under their numbers in the
// 3 + 2 strip, and on 2026-10-13 the live test found the counters 10px under the bar there (S4b-BL-245).
// Offline and deterministic: every request that is not to the local server is answered by this script (MapLibre's style
// URL with a small valid style that has credits, anything else aborted), so the map's container and canvas exist but nothing depends on a
// network. Not a replacement for tools/live-ui after the deploy, and not for a real phone.
// A layout shift depends on what arrives when: on 2026-10-13 the CI runner, slower than a laptop, got the Tamil web font
// after 1.6 s and the tour offer, drawn in the fallback font, grew and jumped (0.065, S4b-BL-262), which no local run showed.
// So a pass whose only failures are layout shifts runs once more, and the output says so; a second failure fails the gate.
// GATE_FONT_DELAY_MS=<ms> makes the local server send the web fonts (the Indic Noto Sans) that late, as a slow network
// does, to see what a late font moves (not set in CI; the Indic fonts are font-display: optional since S4b-BL-263, so a late font is not swapped in; the shift-only rerun may go one release after that).
// GATE_DEV_KINDS=1 adds the kinds runs (S4b-BL-212, 2026-10-15): on a build that lists the school (the production build does,
// DOORPRINTS_KINDS="'house,school'" in angular.json), at 320x568 (en, ta 130%) and on the owner's phone (ta 130%), twice:
// with Experimental: more than houses OFF (the default: no chooser, the switch off, no kind rows on Your data) and ON (the
// chooser, the chip and a school space). CI sets it in the layout-gate job (web.yml).
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
const FONT_DELAY_MS = Number(process.env.GATE_FONT_DELAY_MS) || 0;
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
    const reply = () => {
      res.writeHead(inBase ? 200 : 404, { 'content-type': TYPES[path.extname(send)] || 'application/octet-stream', 'cache-control': 'no-store' });
      res.end(inBase ? fs.readFileSync(send) : '');
    };
    if (FONT_DELAY_MS && isFile && send.endsWith('.woff2')) setTimeout(reply, FONT_DELAY_MS); // the fallback font first
    else reply();
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
  // The map at its floor: its controls need the room, so the counters may start below the bar (as live-ui.js).
  const wrap = document.querySelector('.map-wrap'), map = document.querySelector('.maplibregl-map');
  if (wrap && map) {
    const probe = document.createElement('div');
    probe.style.cssText = 'position:absolute;visibility:hidden;height:var(--map-floor)';
    wrap.appendChild(probe);
    const floor = probe.getBoundingClientRect().height;
    probe.remove();
    if (floor > 0 && map.getBoundingClientRect().height <= floor + 1) return null;
  }
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

/** Two kinds shown, the house the default, the tour done (S4b-BL-212): set before the app reads its storage. */
function twoKinds() {
  localStorage.setItem('doorprints.kinds.experimental', '1');
  localStorage.setItem('doorprints.kinds.enabled', '["house","school"]');
  localStorage.setItem('doorprints.tour', 'skipped');
}

/** The switch left off, as a person who has not touched it: only the tour is done. */
function switchOff() {
  localStorage.setItem('doorprints.tour', 'skipped');
}

/** One route of the school-off run: the map with no chooser, and Your data with no Experimental switch and a Schools row, off (the school is released, ADR-13b). */
async function offRoute(page, route, tag, check) {
  try {
    const resp = await page.goto(`${BASE_FOR_KINDS}${route}`, { waitUntil: 'load', timeout: 30000 });
    check(`${tag}: loads`, !!resp && resp.status() < 400, resp && resp.status());
    if (route === '/data') await page.waitForSelector('#kind-rows', { timeout: 15000 }).catch(() => null);
    await page.waitForTimeout(route === '/' ? 1500 : 400);
    check(`${tag}: no chooser, no space chip`, (await page.locator('#chooser-heading, .space-chip').count()) === 0, 'a chooser or a chip with the school not shown');
    if (route === '/data') {
      check(`${tag}: no Experimental switch`, (await page.locator('#kinds-experimental').count()) === 0, 'an Experimental switch while no listed kind is unreleased');
      check(`${tag}: a Schools row, off`, (await page.locator('#kind-on-school:not(:checked)').count()) === 1 && (await page.locator('#kind-on-house:checked').count()) === 1, 'no Houses row on and Schools row off');
    }
    const found = await page.evaluate(layoutAudit);
    check(`${tag}: layout`, found.length === 0, found.join(' ; '));
  } catch (e) { check(`${tag}: ran`, false, e.message.split('\n')[0]); }
}

/** One route of the two-kind run: the chooser at `/`, a school space with its chip, What you track on Your data. */
async function kindsRoute(page, route, tag, check) {
  try {
    const resp = await page.goto(`${BASE_FOR_KINDS}${route}`, { waitUntil: 'load', timeout: 30000 });
    check(`${tag}: loads`, !!resp && resp.status() < 400, resp && resp.status());
    const ready = { '/': '#chooser-heading', '/k/school': '.space-chip', '/data': '#kind-rows' }[route];
    if (ready) await page.waitForSelector(ready, { timeout: 15000 }).catch(() => null);
    await page.waitForTimeout(route === '/k/school' ? 2000 : 400);
    if (route === '/') check(`${tag}: the chooser`, (await page.locator('#chooser-heading').count()) === 1 && (await page.locator('button.space-card').count()) === 2, 'no chooser with two cards');
    if (route === '/k/school') {
      check(`${tag}: a school space`, (await page.locator('#houses-heading').count()) === 1 && (await page.locator('.space-chip').count()) === 1, 'no space heading or chip');
    }
    if (route === '/data') check(`${tag}: What you track`, (await page.locator('#kind-rows').count()) === 1, 'no rows on Your data');
    const found = await page.evaluate(layoutAudit);
    check(`${tag}: layout`, found.length === 0, found.join(' ; '));
  } catch (e) { check(`${tag}: ran`, false, e.message.split('\n')[0]); }
}

let BASE_FOR_KINDS = '';

async function run() {
  const arg = process.argv[2] || path.join(__dirname, '../../web/dist/web/browser');
  const remote = /^https?:\/\//.test(arg);
  const local = remote ? null : await serve(path.resolve(arg));
  const base = (remote ? arg : local.base).replace(/\/$/, '');
  BASE_FOR_KINDS = base;
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined, args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });
  const t0 = Date.now();
  // One pass: a phone, a text size and a language over its routes. Returns its checks, its failures (a layout shift
  // marked as such) and its largest shift; it prints nothing, the caller decides whether a failure is final.
  const pass = async (viewport, text, lang = 'en', { counters = false, routes = ROUTES, kinds = false } = {}) => {
    const out = { checks: 0, failures: [], worstShift: 0 };
    const check = (tag, ok, detail, shift = false) => { out.checks++; if (!ok) out.failures.push({ line: `${tag}: ${detail}`, shift }); };
    const { ctx } = await phoneContext(browser, devices, { device: 'Pixel 7', viewport, text, lang });
    await offline(ctx, base);
    await ctx.addInitScript(watchShifts);
    if (kinds) await ctx.addInitScript(kinds === 'off' ? switchOff : twoKinds);
    const page = await ctx.newPage();
    for (const route of routes) {
      if (kinds) {
        const at = `${viewport.width}x${viewport.height}${text ? ` text ${text}%` : ''} ${lang}`;
        if (kinds === 'off') await offRoute(page, route, `${at} switch off ${route}`, check);
        else await kindsRoute(page, route, `${at} two kinds ${route}`, check);
        continue;
      }
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
        // Every route (S4b-BL-249: /data shifted by 0.09-0.10 when the export card became its form; fixed, so now held).
        const cls = await page.evaluate(() => window.__cls());
        out.worstShift = Math.max(out.worstShift, cls);
        const shifted = cls > MAX_CLS || process.env.GATE_VERBOSE ? await page.evaluate(() => window.__shifted()) : '';
        if (process.env.GATE_VERBOSE) console.log(`${tag}: shift ${cls.toFixed(3)}; moved: ${shifted || 'nothing'}`);
        check(`${tag}: layout shift on load`, cls <= MAX_CLS, `${cls.toFixed(3)} (at most ${MAX_CLS}); moved: ${shifted || 'unknown'}`, true);
        if (route === '/' && counters) {
          const under = await page.evaluate(countersUnderBar);
          check(`${tag}: counters above the bottom bar`, under === null, under);
        }
        const found = await page.evaluate(layoutAudit);
        check(`${tag}: layout`, found.length === 0, found.join(' ; '));
      } catch (e) { check(`${tag}: ran`, false, e.message.split('\n')[0]); }
    }
    await ctx.close();
    return out;
  };
  const passes = [
    ...VIEWPORTS.map((v) => [v, undefined, 'en', { counters: true }]),
    ...VIEWPORTS.map((v) => [v, LARGE_TEXT]),
    [OWNER_PHONE, LARGE_TEXT, 'en', { counters: true }],
    [LANDSCAPE_SE],
    [TALL_TAMIL, 200, 'ta'],
    // The counters in the Indic languages whose captions are longest: the Map page only, so the gate stays about a minute.
    ...['ta', 'te'].flatMap((lang) => [...VIEWPORTS, TALL_TAMIL].map((v) => [v, undefined, lang, { counters: true, routes: ['/'] }])),
    ...['ta', 'te'].map((lang) => [OWNER_PHONE, LARGE_TEXT, lang, { counters: true, routes: ['/'] }]),
  ];
  // GATE_DEV_KINDS=1 (S4b-BL-212): on a build that lists the school (the production build), the switch-off run, then the
  // chooser, the space chip and a school space with the switch on. The checks above are unchanged and run first.
  if (process.env.GATE_DEV_KINDS === '1') {
    for (const [v, t, lang] of [[VIEWPORTS[0], undefined, 'en'], [VIEWPORTS[0], LARGE_TEXT, 'ta'], [OWNER_PHONE, LARGE_TEXT, 'ta']]) {
      passes.push([v, t, lang, { kinds: 'off', routes: ['/', '/data'] }]);
      passes.push([v, t, lang, { kinds: true, routes: ['/', '/k/school', '/k/school/compare', '/share', '/data'] }]);
    }
  }
  let results, retried = [];
  try {
    results = await Promise.all(passes.map((p) => pass(...p)));
    // Only a layout shift is timing-dependent (what arrives when); any other failure is final at once.
    retried = results.map((r, i) => (r.failures.length && r.failures.every((f) => f.shift) ? i : -1)).filter((i) => i >= 0);
    for (const i of retried) for (const f of results[i].failures) console.log(`SHIFT (run again once) ${f.line}`);
    const again = await Promise.all(retried.map((i) => pass(...passes[i])));
    retried.forEach((i, k) => { results[i] = again[k]; });
  } finally {
    await browser.close();
    if (local) local.server.close();
  }
  const failures = results.flatMap((r) => r.failures);
  for (const f of failures) console.log(`FAIL ${f.line}`);
  const checks = results.reduce((n, r) => n + r.checks, 0);
  const worstShift = Math.max(...results.map((r) => r.worstShift));
  const retry = retried.length ? `; ${retried.length} pass(es) failed only on a layout shift and ran again: ${retried.filter((i) => results[i].failures.length).length} failed again` : '';
  console.log(`layout gate: ${checks - failures.length}/${checks} checks passed (largest layout shift ${worstShift.toFixed(3)}${retry}) on ${base} in ${Math.round((Date.now() - t0) / 1000)} s`);
  process.exitCode = failures.length ? 1 : 0;
}
run().catch((e) => { console.error(e); process.exit(1); });
