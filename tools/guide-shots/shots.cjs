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

// The guide's website pictures (guide/docs/images/web-*.png made here): the built site is served on a local port (no
// network), a page is put into the state the guide describes, and a clip of it is written with the places to point at.
// `python3 tools/guide-shots/annotate.py` then numbers those places and shrinks each PNG to about 10-30 KB.
//
//   cd web && npm ci && npm run build            # the built site
//   cd ../tools/live-ui && npm ci                # playwright-core
//   CHROMIUM=<path to chrome> node tools/guide-shots/shots.cjs [builtSiteDir] [outDir] [name ...]
//
// Every shot is real UI: nothing is drawn. Two things are staged and are not secrets: the example server address, and
// for the Google Drive card a client id that is not a real one (the card shows its Connect button only when the build
// has some client id; no sign-in is attempted, and nothing leaves the machine).
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require(path.join(__dirname, '..', 'live-ui', 'node_modules', 'playwright-core'));

const ROOT = path.resolve(process.argv[2] || path.join(__dirname, '..', '..', 'web', 'dist', 'web', 'browser'));
const OUT = path.resolve(process.argv[3] || path.join(__dirname, 'out'));
const ONLY = [...(process.env.SHOTS || '').split(',').filter(Boolean), ...process.argv.slice(4)]; // SHOTS=name,name picks pictures
// LANGS=en,hi,ta,te (default en): one set of pictures per language, named <name>.png for English and <name>-<lang>.png otherwise.
const LANGS = (process.env.LANGS || 'en').split(',');
const I18N_DIR = path.join(__dirname, '..', '..', 'web', 'src', 'app', 'i18n');
const LANG_KEY = 'doorprints.lang';

/** The text the app shows for a key in a language, read from the dictionary file itself (so a shot finds its place by key, not by English words). */
function text(lang, key) {
  const source = fs.readFileSync(path.join(I18N_DIR, `${lang}.ts`), 'utf8');
  const m = source.match(new RegExp(`^\\s*'${key.replace(/\./g, '\\.')}':\\s*'((?:[^'\\\\]|\\\\.)*)'`, 'm'));
  if (!m) throw new Error(`no ${key} in ${lang}.ts`);
  return m[1].replace(/\\'/g, "'");
}
const SAMPLE_BACKUP = path.join(__dirname, '..', '..', 'docs', 'schemas', 'backup-sample.json');
const { execFileSync } = require('child_process');
const WEB = path.join(__dirname, '..', '..', 'web');

/** The page-side stand-ins for a connected Drive (a fake Drive behind fetch, a stub for Google's sign-in), bundled once. */
let fakeGoogle = null;
function fakeGoogleBundle() {
  if (fakeGoogle === null) {
    const out = path.join(OUT, 'fake-google.js');
    execFileSync(path.join(WEB, 'node_modules', '.bin', 'esbuild'), [path.join(__dirname, 'fake-google', 'entry.ts'), '--bundle', '--format=iife', '--platform=browser', `--outfile=${out}`, '--log-level=error'], { stdio: 'inherit' });
    fakeGoogle = fs.readFileSync(out, 'utf8');
  }
  return fakeGoogle;
}

const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.webmanifest': 'application/manifest+json' };
const CONFIG_WITH_CLIENT_ID = "window.__DOORPRINTS__ = { googleClientId: 'screenshot-only.invalid', googleRedirectUri: '' };";

/**
 * What to point at is found by the app's own translated text (`t(key)` reads the dictionary of the language being shot), so
 * a renamed label fails here, in every language, not silently. `top` is the element the picture starts at (a margin above it);
 * it ends a margin below the last numbered place. A mark is a finder, or `{ find, badge }` to say where its number sits
 * (`top-left`; the default is beside a button and on the top right corner of a wide field).
 */
const shots = [
  {
    name: 'web-connect-url',
    route: '/connect',
    async stage(page) {
      await page.locator('input[type="url"]').first().fill('https://my-pc.tail1234.ts.net');
    },
    top: (page, t) => page.getByRole('heading', { name: t('connect.title') }),
    marks: [(page) => page.locator('input[type="url"]').first(), (page, t) => page.getByRole('button', { name: t('connect.getCode') })],
  },
  {
    name: 'web-import-choose',
    route: '/data',
    async stage() {},
    top: (page, t) => page.getByRole('heading', { name: t('imp.heading') }),
    marks: [(page, t) => page.getByRole('button', { name: t('imp.pick') })],
  },
  {
    name: 'web-import-preview',
    route: '/data',
    bottom: 12, // another button stands just below Import; do not show half of it
    viewportHeight: 1900,
    async stage(page, t) {
      await page.locator('input[type="file"][accept*=".json"]').setInputFiles(SAMPLE_BACKUP);
      await page.getByRole('button', { name: t('imp.go'), exact: true }).waitFor();
    },
    // The choice of how to import (1) and the Import button (2).
    top: (page, t) => page.getByText(t('imp.fileLabel'), { exact: true }),
    marks: [
      (page, t) => page.getByText(t('imp.modeMerge'), { exact: true }),
      // Cancel stands right beside Import, so its number sits on the button's top left corner.
      { find: (page, t) => page.getByRole('button', { name: t('imp.go'), exact: true }), badge: 'top-left' },
    ],
  },
  {
    name: 'web-drive-connect',
    route: '/data',
    config: CONFIG_WITH_CLIENT_ID,
    async stage() {},
    top: (page, t) => page.getByRole('heading', { name: t('driveConnect.heading') }),
    marks: [(page, t) => page.getByRole('button', { name: t('driveConnect.connect') })],
  },
  // The connected Google Drive card, with the repository's fake Drive behind it (fake-google/entry.ts): no account, nothing leaves the machine.
  {
    name: 'web-drive-recovery-key',
    route: '/data',
    drive: true,
    viewportHeight: 3300,
    async stage(page, t) {
      await page.getByRole('button', { name: t('driveConnect.connect') }).click();
      await page.getByRole('heading', { name: t('driveConnect.firstConnect') }).waitFor();
    },
    top: (page, t) => page.getByRole('heading', { name: t('driveConnect.firstConnect') }),
    // The key (shown once) (1), the tick box (2) and Next (3).
    marks: [
      { find: (page) => page.locator('.recovery-key-box code'), badge: 'top-right' },
      (page, t) => page.getByText(t('driveConnect.confirmSavedRecoveryKey'), { exact: true }),
      { find: (page, t) => page.getByRole('button', { name: t('common.next'), exact: true }), badge: 'top-left' },
    ],
  },
  {
    name: 'web-drive-connected',
    route: '/data',
    drive: true,
    viewportHeight: 3300,
    async stage(page, t) {
      await connectDrive(page, t);
      await page.getByRole('button', { name: t('driveBackups.backUpNow') }).click();
      await page.locator('table button', { hasText: t('driveBackups.import') }).first().waitFor();
    },
    top: (page, t) => page.getByRole('heading', { name: t('driveConnect.heading') }),
    // Back up now (1) and a backup's Import a backup button (2).
    marks: [(page, t) => page.getByRole('button', { name: t('driveBackups.backUpNow') }), (page, t) => page.locator('table button', { hasText: t('driveBackups.import') }).first()],
  },
  {
    name: 'web-drive-delete',
    route: '/data',
    drive: true,
    viewportHeight: 3300, // the card sits far down the page, which scrolls inside the viewport
    async stage(page, t) {
      await connectDrive(page, t);
      await page.getByRole('heading', { name: t('driveDelete.heading') }).waitFor();
    },
    top: (page, t) => page.getByRole('heading', { name: t('driveDelete.heading') }),
    // The three levels: older backups (1), all backups (2) and everything (3).
    marks: [
      { find: (page, t) => page.getByRole('button', { name: t('driveDelete.olderBackups'), exact: true }), badge: 'left' },
      { find: (page, t) => page.getByRole('button', { name: t('driveDelete.allBackups'), exact: true }), badge: 'top-right' },
      { find: (page, t) => page.getByRole('button', { name: t('driveDelete.everything'), exact: true }), badge: 'left' },
    ],
  },
];

/** Connects the fake Drive through the card: Connect, the recovery key ticked, Next. */
async function connectDrive(page, t) {
  await page.getByRole('button', { name: t('driveConnect.connect') }).click();
  await page.getByText(t('driveConnect.confirmSavedRecoveryKey'), { exact: true }).click();
  await page.getByRole('button', { name: t('common.next'), exact: true }).click();
  await page.getByText(t('driveConnect.ready'), { exact: true }).waitFor();
}

fs.mkdirSync(OUT, { recursive: true });
// The files the server may send are listed once, from the built site's own tree; a request is only a key into that
// list, never part of a path (so a crafted URL cannot reach anything else).
function listFiles(dir, prefix = '') {
  const found = new Map();
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      for (const [url, file] of listFiles(path.join(dir, entry.name), `${prefix}/${entry.name}`)) found.set(url, file);
    } else found.set(`${prefix}/${entry.name}`, path.join(dir, entry.name));
  }
  return found;
}
const files = listFiles(ROOT);
let configJs = null;
const server = http
  .createServer((req, res) => {
    const url = req.url.split('?')[0];
    if (url === '/config.js' && configJs !== null) {
      res.writeHead(200, { 'content-type': 'text/javascript' });
      return res.end(configJs);
    }
    let decoded = '';
    try {
      decoded = decodeURIComponent(url);
    } catch {
      /* a malformed escape is just an unknown path: the app shell answers */
    }
    const file = files.get(decoded) || files.get('/index.html');
    res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  })
  .listen(0, async () => {
    const port = server.address().port;
    const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined });
    for (const lang of LANGS) {
      const t = (key) => text(lang, key);
      const suffix = lang === 'en' ? '' : `-${lang}`;
      for (const shot of shots) {
        if (ONLY.length && !ONLY.includes(shot.name)) continue;
        configJs = shot.config || (shot.drive ? CONFIG_WITH_CLIENT_ID : null);
        const page = await browser.newPage({ viewport: { width: 1280, height: shot.viewportHeight || 900 } });
        if (shot.drive) {
          await page.addInitScript(fakeGoogleBundle());
          await page.route('https://accounts.google.com/gsi/client', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body: '/* stand-in: fake-google/entry.ts defines google.accounts */' }));
        }
        await page.addInitScript(([key, value]) => localStorage.setItem(key, value), [LANG_KEY, lang]);
        await page.goto(`http://localhost:${port}${shot.route}`, { waitUntil: 'networkidle' });
        await shot.stage(page, t);
        await page.waitForTimeout(300);
        const marks = [];
        for (const mark of shot.marks) {
          const find = typeof mark === 'function' ? mark : mark.find;
          const box = await find(page, t).boundingBox();
          marks.push({ ...box, badge: typeof mark === 'function' ? undefined : mark.badge });
        }
        const first = await shot.top(page, t).boundingBox();
        const last = marks[marks.length - 1];
        const clip = { x: 296, y: Math.max(0, Math.round(first.y - 14)), width: 688, height: Math.round(last.y + last.height - first.y + (shot.bottom ?? 42)) };
        await page.screenshot({ path: path.join(OUT, `${shot.name}${suffix}.raw.png`), clip, fullPage: true });
        fs.writeFileSync(path.join(OUT, `${shot.name}${suffix}.json`), JSON.stringify({ clip, marks }));
        await page.close();
      }
    }
    await browser.close();
    server.close();
  });
