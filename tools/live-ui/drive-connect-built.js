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

/**
 * Playwright check of the built website's Drive card against an empty Google client id (no real Google).
 * Serves web/dist/web/browser if present. Skips when Chromium is not installed.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

const ROOT = path.resolve(__dirname, '../../web/dist/web/browser');
const PLAYWRIGHT = path.resolve(__dirname, 'node_modules/playwright');

async function main() {
  if (!fs.existsSync(ROOT)) {
    console.log('skip: no web/dist/web/browser (run npm run build first)');
    process.exit(0);
  }
  let chromium;
  try {
    ({ chromium } = require(PLAYWRIGHT));
  } catch {
    console.log('skip: playwright is not installed under tools/live-ui');
    process.exit(0);
  }

  const types = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json',
    '.webmanifest': 'application/manifest+json',
    '.svg': 'image/svg+xml',
    '.woff2': 'font/woff2',
  };
  const server = http.createServer((req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    let file = path.normalize(url.pathname).replace(/^(\.\.(\/|\\|$))+/, '');
    if (file === '/' || !path.extname(file)) file = '/index.html';
    const full = path.join(ROOT, file);
    if (!full.startsWith(ROOT) || !fs.existsSync(full) || fs.statSync(full).isDirectory()) {
      res.writeHead(404);
      res.end();
      return;
    }
    res.writeHead(200, { 'Content-Type': types[path.extname(full)] || 'application/octet-stream' });
    fs.createReadStream(full).pipe(res);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  const origin = `http://127.0.0.1:${port}`;

  let browser;
  try {
    browser = await chromium.launch({ headless: true });
  } catch (e) {
    console.log('skip: Chromium is not available (' + e.message + ')');
    server.close();
    process.exit(0);
  }

  const page = await browser.newPage();
  await page.goto(origin + '/data', { waitUntil: 'networkidle' });
  const heading = await page.getByRole('heading', { name: /Google Drive|Back up to Google Drive/i }).textContent();
  if (!heading) throw new Error('Drive heading missing on Your data');
  const body = await page.locator('#drive-heading').locator('xpath=..').textContent();
  if (!/not available|Connect to Google Drive|unavailable/i.test(body || '')) {
    throw new Error('expected the empty-config Drive card to say unavailable or Connect; got: ' + body);
  }
  const privacy = page.getByRole('link', { name: /Privacy page/i });
  if ((await privacy.count()) < 1) throw new Error('privacy link missing');
  console.log('ok: built Drive card with empty Google client id');
  await browser.close();
  server.close();
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
