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
// The AI pictures (web-ai-*) run the website's own-key mode against the repository's fake AI provider
// (tools/fake-ai-provider/server.mjs, on a local port: no key, nothing leaves the machine). The fake checks every
// request like a vendor would; the words of each answer are scripted below (EXAMPLE_ANSWERS), so the guide calls every
// one of these pictures an "example answer". Nothing in the app or its configuration changes for them: the choice of
// provider is written into the page's localStorage, as the Connect page would.
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
const KNOWN_LANGS = ['en', 'hi', 'ta', 'te'];
const LANGS = (process.env.LANGS || 'en').split(',');
for (const lang of LANGS) if (!KNOWN_LANGS.includes(lang)) throw new Error(`LANGS: ${lang} is not one of ${KNOWN_LANGS.join(', ')}`);
const I18N_DIR = path.join(__dirname, '..', '..', 'web', 'src', 'app', 'i18n');
const LANG_KEY = 'doorprints.lang';

/** The text the app shows for a key in a language, read from the dictionary file itself (so a shot finds its place by key, not by English words). */
/** The text with every regular-expression metacharacter (backslash included) escaped, to match it literally. */
function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function text(lang, key) {
  const source = fs.readFileSync(path.join(I18N_DIR, `${lang}.ts`), 'utf8');
  const m = source.match(new RegExp(`^\\s*'${escapeRegExp(key)}':\\s*'((?:[^'\\\\]|\\\\.)*)'`, 'm'));
  if (!m) throw new Error(`no ${key} in ${lang}.ts`);
  return m[1].replace(/\\'/g, "'");
}
const SAMPLE_BACKUP = path.join(__dirname, '..', '..', 'docs', 'schemas', 'backup-sample.json');
const { execFileSync } = require('child_process');
const { pathToFileURL } = require('url');
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

// --- The AI pictures: three example houses, the scripted answers, and the page-side wiring. ---
const HOUSE_A = '11111111-1111-4111-8111-111111111111';
const HOUSE_B = '22222222-2222-4222-8222-222222222222';
const HOUSE_C = '33333333-3333-4333-8333-333333333333';
const AI_DAY = Date.UTC(2026, 8, 20);
const house = (id, label, locality, lat, lon, status, price, rating, notes) => ({ id, label, locality, lat, lon, status, price, priceType: 'RENT', bedrooms: id === HOUSE_B ? 3 : 2, rating, notes, checklist: {}, createdAt: AI_DAY, updatedAt: AI_DAY });
/** Made-up houses (no contact details) that the example answers talk about, imported through the Your data page. */
const AI_BACKUP = {
  format: 'doorprints-backup/2',
  exportedAt: AI_DAY,
  houses: [
    house(HOUSE_A, 'Green View 2BHK', 'Adyar', 13.0067, 80.2574, 'SHORTLISTED', 28000, 4, 'Water pressure is good all day. Only bike parking. Owner prefers families.'),
    house(HOUSE_B, 'Sea Breeze 3BHK', 'Besant Nagar', 13.0003, 80.2668, 'NEW', 42000, undefined, 'Two car parking. Landlord wants 10 months deposit. The water is salty in summer.'),
    house(HOUSE_C, 'Lakeview 2BHK', 'Indira Nagar', 13.0012, 80.2565, 'SHORTLISTED', 24000, 3, 'Water comes only twice a day. The main road is noisy.'),
  ],
  visits: [], photos: [], brokers: [], criteria: [], preferences: [], questions: [], viewings: [], areas: [], places: [], areaNotes: [],
};
/** A made-up WhatsApp-style listing (the kind the golden set uses); the number is not a real one. */
const LISTING_TEXT = '2BHK semi furnished for rent @ Indiranagar 12th Main. Rent 28k/month, deposit 1.5L, maintenance 2k. Car parking + lift + power backup. Call Ramesh 98450 12345';
const REFUSAL = "I don't know based on the houses you have saved.";
/** The words the fake provider "answers", by the kind of request (told by the schema's fields). Every picture of them is an example answer. */
const EXAMPLE_ANSWERS = {
  extract: () => ({ label: '2BHK near Indiranagar', locality: 'Indiranagar', street: '12th Main', price: '28k', priceType: 'RENT', bedrooms: '2', contactName: 'Ramesh', contactPhone: '98450 12345', notes: 'Semi furnished. Deposit 1.5 lakh, maintenance 2k a month.', amenities: ['parking', 'lift', 'power backup'] }),
  ask: (prompt) => (prompt.includes('property tax')
    ? { answer: REFUSAL, citedHouseIds: [] }
    : { answer: `Green View 2BHK has the best water supply: the pressure is good all day [house:${HOUSE_A}]. Lakeview 2BHK is weaker, with water only twice a day [house:${HOUSE_C}].`, citedHouseIds: [HOUSE_A, HOUSE_C] }),
  plan: () => ({ summary: 'Both shortlisted 2BHKs are under 35k. Start with Green View 2BHK, which is nearest, then go on to Lakeview 2BHK.', stops: [{ houseId: HOUSE_A, reason: 'Shortlisted 2BHK at 28,000 a month, nearest to your start.' }, { houseId: HOUSE_C, reason: 'Shortlisted 2BHK at 24,000 a month.' }] }),
};
const FAKE_KEY = 'screenshot-only-not-a-key';

/** Imports the example houses through the Your data page, as a person would. */
async function seedHouses(page, t, port) {
  // In a folder of its own: annotate.py reads every *.json directly in the out directory.
  fs.mkdirSync(path.join(OUT, 'seed'), { recursive: true });
  const file = path.join(OUT, 'seed', 'ai-houses.json');
  fs.writeFileSync(file, JSON.stringify(AI_BACKUP));
  await page.goto(`http://localhost:${port}/data`, { waitUntil: 'networkidle' });
  await page.locator('input[type="file"][accept*=".json"]').setInputFiles(file);
  await page.getByRole('button', { name: t('imp.go'), exact: true }).click();
  await page.waitForTimeout(800);
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
  // The AI pictures (`ai: true`): the example houses are imported first and the fake provider answers (see the header).
  // `end` is the element the picture ends below, when the last numbered place is not the last thing to show.
  {
    name: 'web-ai-extract-before',
    clipX: 160,
    clipWidth: 960,
    ai: true,
    route: '/houses/new',
    async stage(page, t) {
      await openListingFill(page, t);
      await page.locator('#listing-text').fill(LISTING_TEXT);
    },
    top: (page, t) => page.getByText(t('listingFill.title'), { exact: true }),
    marks: [(page) => page.locator('#listing-text'), (page, t) => page.getByRole('button', { name: t('listingFill.submit') })],
  },
  {
    name: 'web-ai-extract-after',
    clipX: 160,
    clipWidth: 960,
    ai: true,
    route: '/houses/new',
    viewportHeight: 1500,
    async stage(page, t) {
      await openListingFill(page, t);
      await page.locator('#listing-text').fill(LISTING_TEXT);
      await page.getByRole('button', { name: t('listingFill.submit') }).click();
      await page.getByText(t('listingFill.done'), { exact: false }).first().waitFor();
    },
    top: (page, t) => page.getByText(t('listingFill.title'), { exact: true }),
    end: (page) => page.locator('#house-bhk'),
    marks: [(page) => page.locator('#house-name'), { find: (page) => page.locator('#house-price'), badge: 'top-right' }],
  },
  {
    name: 'web-ai-ask-answer',
    ai: true,
    route: '/ask',
    async stage(page, t) {
      await page.locator('#question').fill('Which house has the best water supply?');
      await page.getByRole('button', { name: t('ask.submit'), exact: true }).click();
      await page.getByRole('heading', { name: t('ask.sources') }).waitFor();
    },
    top: (page, t) => page.getByRole('heading', { name: t('ask.title') }),
    end: (page) => page.locator('ol.sources'),
    marks: [(page) => page.locator('.answer-text'), (page, t) => page.getByRole('heading', { name: t('ask.sources') })],
  },
  {
    name: 'web-ai-ask-refusal',
    ai: true,
    route: '/ask',
    async stage(page, t) {
      await page.locator('#question').fill('What is the property tax on the Adyar flat?');
      await page.getByRole('button', { name: t('ask.submit'), exact: true }).click();
      await page.getByText(REFUSAL, { exact: true }).waitFor();
    },
    top: (page, t) => page.getByRole('heading', { name: t('ask.title') }),
    end: (page) => page.locator('.answer'),
    marks: [(page) => page.locator('.answer-text')],
  },
  {
    name: 'web-ai-plan',
    viewportWidth: 760, // narrow enough for the one-column layout (the map goes below, out of the picture)
    bottom: 8,
    clipX: 8,
    clipWidth: 744,
    ai: true,
    route: '/plan',
    viewportHeight: 1300,
    async stage(page, t) {
      await page.locator('#plan-question').fill('Shortlisted 2BHKs under 35k this afternoon');
      await page.locator('#start-lat').fill('13.0085');
      await page.locator('#start-lat').blur();
      await page.locator('#start-lon').fill('80.2585');
      await page.locator('#start-lon').blur();
      await page.getByRole('button', { name: t('plan.submit'), exact: true }).click();
      await page.getByRole('heading', { name: t('plan.route') }).waitFor();
    },
    top: (page, t) => page.getByRole('heading', { name: t('plan.title') }),
    end: (page) => page.locator('section[aria-labelledby="route-heading"]'),
    marks: [(page) => page.locator('#plan-question'), (page, t) => page.getByRole('heading', { name: t('plan.route') })],
  },
];

/** Opens the "Fill in from listing text" card of the new-house form, if it is not open already. */
async function openListingFill(page, t) {
  const box = page.locator('#listing-text');
  if (!(await box.isVisible())) await page.getByText(t('listingFill.title'), { exact: true }).click();
  await box.waitFor();
}

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
    // The fake AI provider, only when an AI picture is wanted: a real socket on a free local port, checking each request like a vendor.
    const wantsAi = shots.some((s) => s.ai && (!ONLY.length || ONLY.includes(s.name)));
    const fake = wantsAi ? await (await import(pathToFileURL(path.join(__dirname, '..', 'fake-ai-provider', 'server.mjs')).href)).start() : null;
    const fakeBase = fake ? `http://127.0.0.1:${fake.port}` : '';
    for (const lang of LANGS) {
      const t = (key) => text(lang, key);
      const suffix = lang === 'en' ? '' : `-${lang}`;
      for (const shot of shots) {
        if (ONLY.length && !ONLY.includes(shot.name)) continue;
        configJs = shot.config || (shot.drive ? CONFIG_WITH_CLIENT_ID : null);
        const page = await browser.newPage({ viewport: { width: shot.viewportWidth || 1280, height: shot.viewportHeight || 900 } });
        if (shot.drive) {
          await page.addInitScript(fakeGoogleBundle());
          await page.route('https://accounts.google.com/gsi/client', (route) => route.fulfill({ status: 200, contentType: 'text/javascript', body: '/* stand-in: fake-google/entry.ts defines google.accounts */' }));
        }
        await page.addInitScript(([key, value]) => localStorage.setItem(key, value), [LANG_KEY, lang]);
        if (shot.ai) {
          // What the Connect page would save for "my own AI" with an OpenAI-compatible service (storage-keys.ts), pointed at the fake.
          const settings = { 'doorprints.ai-features': '1', 'doorprints.ai-provider': 'device', 'doorprints.ai-kind': 'openai-compatible', 'doorprints.ai-base-url': `${fakeBase}/v1`, 'doorprints.ai-model': 'example-model', 'doorprints.gemini-key': FAKE_KEY };
          await page.addInitScript((entries) => { for (const [k, v] of Object.entries(entries)) localStorage.setItem(k, v); }, settings);
          // Nothing leaves the machine: a request to anything but this computer (map tiles, fonts) is dropped.
          await page.route((url) => !['localhost', '127.0.0.1'].includes(url.hostname), (route) => route.abort());
          // The fake checks the request and answers; the words of the answer are scripted (the schema's fields tell the kind of request).
          await page.route(`${fakeBase}/v1/chat/completions`, async (route) => {
            if (route.request().method() !== 'POST') return route.continue();
            const response = await route.fetch();
            const body = route.request().postDataJSON();
            const fields = Object.keys(body.response_format?.json_schema?.schema?.properties ?? {});
            const kind = fields.includes('locality') ? 'extract' : fields.includes('answer') ? 'ask' : fields.includes('stops') ? 'plan' : null;
            if (!kind) return route.fulfill({ response });
            const json = await response.json();
            json.choices[0].message.content = JSON.stringify(EXAMPLE_ANSWERS[kind](body.messages.map((m) => m.content).join('\n')));
            return route.fulfill({ response, json });
          });
          await seedHouses(page, t, port);
        }
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
        const last = shot.end ? await shot.end(page, t).boundingBox() : marks[marks.length - 1];
        const clip = { x: shot.clipX ?? 296, y: Math.max(0, Math.round(first.y - 14)), width: shot.clipWidth ?? 688, height: Math.round(Math.max(last.y + last.height, ...marks.map((m) => m.y + m.height)) - first.y + (shot.bottom ?? 42)) };
        await page.screenshot({ path: path.join(OUT, `${shot.name}${suffix}.raw.png`), clip, fullPage: true });
        fs.writeFileSync(path.join(OUT, `${shot.name}${suffix}.json`), JSON.stringify({ clip, marks }));
        await page.close();
      }
    }
    await browser.close();
    if (fake) await fake.close();
    server.close();
  });
