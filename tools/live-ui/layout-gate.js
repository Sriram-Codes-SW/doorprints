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
// and again at 130% text: the shared layoutAudit (live-ui.js's mobile pass): the map no taller than the screen, its canvas
// under 4,096px, the app no taller than the screen, no sideways scroll, no map control over the page's own controls; plus
// that the Map page has a map and a canvas at all (so the checks cannot pass for want of a map).
// Offline and deterministic: every request that is not to the local server is answered by this script (MapLibre's style
// URL with an empty valid style, anything else aborted), so the map's container and canvas exist but nothing depends on a
// network. Not a replacement for tools/live-ui after the deploy, and not for a real phone.
const { chromium, devices } = require('playwright');
const { ROUTES, phoneContext, layoutAudit } = require('./layout-audit');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const VIEWPORTS = [{ width: 320, height: 568 }, { width: 360, height: 640 }, { width: 390, height: 664 }];
const LARGE_TEXT = 130; // percent: the root font size, as live-ui.js's text-scale hook sets it
const STYLE_URL = /^https:\/\/tiles\.openfreemap\.org\/styles\/liberty(\?|$)/; // MAP_STYLE_URL in web/src/app/shared/map-style-url.ts
const EMPTY_STYLE = { version: 8, name: 'empty', sources: {}, layers: [] };
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

/** Everything but the local server is answered here: the style with an empty one, the rest not at all. */
async function offline(ctx, base) {
  const origin = new URL(base).origin;
  await ctx.route('**/*', (route) => {
    const url = route.request().url();
    if (url.startsWith(origin + '/') || url.startsWith('data:') || url.startsWith('blob:')) return route.continue();
    if (STYLE_URL.test(url)) return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(EMPTY_STYLE) });
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
  const check = (tag, ok, detail) => { checks++; if (!ok) { failures.push(`${tag}: ${detail}`); console.log(`FAIL ${tag}: ${detail}`); } };
  const pass = async (viewport, text) => {
    const { ctx } = await phoneContext(browser, devices, { device: 'Pixel 7', viewport, text });
    await offline(ctx, base);
    const page = await ctx.newPage();
    for (const route of ROUTES) {
      const tag = `${viewport.width}x${viewport.height}${text ? ` text ${text}%` : ''} ${route}`;
      try {
        const resp = await page.goto(base + route, { waitUntil: 'load', timeout: 30000 });
        check(`${tag}: loads`, !!resp && (resp.status() < 400 || route === '/does-not-exist'), resp && resp.status());
        await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => null); // offline, so it goes quiet at once
        if (route === '/') {
          const drawn = await page.waitForSelector('.maplibregl-map canvas', { timeout: 15000 }).then(() => true, () => false);
          check(`${tag}: the Map page has a map with a canvas`, drawn, 'no .maplibregl-map canvas (WebGL missing, or the map did not start)');
          await page.waitForTimeout(2000); // the map's resize observers and the layout settle
        } else await page.waitForTimeout(400);
        const found = await page.evaluate(layoutAudit);
        check(`${tag}: layout`, found.length === 0, found.join(' ; '));
      } catch (e) { check(`${tag}: ran`, false, e.message.split('\n')[0]); }
    }
    await ctx.close();
  };
  try {
    await Promise.all([...VIEWPORTS.map((v) => pass(v)), ...VIEWPORTS.map((v) => pass(v, LARGE_TEXT))]);
  } finally {
    await browser.close();
    if (local) local.server.close();
  }
  console.log(`layout gate: ${checks - failures.length}/${checks} checks passed on ${base} in ${Math.round((Date.now() - t0) / 1000)} s`);
  process.exitCode = failures.length ? 1 : 0;
}
run().catch((e) => { console.error(e); process.exit(1); });
