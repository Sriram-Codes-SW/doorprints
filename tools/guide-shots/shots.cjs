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
const ONLY = process.argv.slice(4);
const SAMPLE_BACKUP = path.join(__dirname, '..', '..', 'docs', 'schemas', 'backup-sample.json');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.webmanifest': 'application/manifest+json' };
const CONFIG_WITH_CLIENT_ID = "window.__DOORPRINTS__ = { googleClientId: 'screenshot-only.invalid', googleRedirectUri: '' };";

/** What to point at is found by role and name (English), so a renamed label fails here, not silently. */
const shots = [
  {
    name: 'web-connect-url',
    route: '/connect',
    async stage(page) {
      await page.locator('input[type="url"]').first().fill('https://my-pc.tail1234.ts.net');
    },
    clip: { x: 296, y: 196, width: 688, height: 408 },
    marks: [(page) => page.locator('input[type="url"]').first(), (page) => page.getByRole('button', { name: /get a code/i })],
  },
  {
    name: 'web-import-choose',
    route: '/data',
    async stage() {},
    clip: { x: 296, y: 455, width: 688, height: 262 },
    marks: [(page) => page.getByRole('button', { name: /choose a backup file/i })],
  },
  {
    name: 'web-import-preview',
    route: '/data',
    viewportHeight: 1700,
    async stage(page) {
      await page.locator('input[type="file"][accept*=".json"]').setInputFiles(SAMPLE_BACKUP);
      await page.getByRole('button', { name: /^import$/i }).waitFor();
    },
    // The choice of how to import (1) and the Import button (2); the clip runs from the file name down past the button.
    marks: [(page) => page.getByText('Merge with what I have'), (page) => page.getByRole('button', { name: /^import$/i })],
    clipFrom: (page) => page.getByText('Backup file', { exact: true }),
  },
  {
    name: 'web-drive-connect',
    route: '/data',
    config: CONFIG_WITH_CLIENT_ID,
    async stage() {},
    clipTo: (page) => page.getByRole('heading', { name: /back up to google drive/i }),
    marks: [(page) => page.getByRole('button', { name: /connect to google drive/i })],
  },
];

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
    for (const shot of shots) {
      if (ONLY.length && !ONLY.includes(shot.name)) continue;
      configJs = shot.config || null;
      const page = await browser.newPage({ viewport: { width: 1280, height: shot.viewportHeight || 900 } });
      await page.goto(`http://localhost:${port}${shot.route}`, { waitUntil: 'networkidle' });
      await shot.stage(page);
      await page.waitForTimeout(300);
      const marks = [];
      for (const find of shot.marks) marks.push(await find(page).boundingBox());
      let clip = shot.clip;
      if (!clip && shot.clipTo) {
        // From the card's top edge down to the last thing pointed at, with room around it.
        const heading = await shot.clipTo(page).boundingBox();
        const last = marks[marks.length - 1];
        clip = { x: 296, y: Math.round(heading.y - 28), width: 688, height: Math.round(last.y + last.height - heading.y + 56) };
      }
      if (!clip && shot.clipFrom) {
        const first = await shot.clipFrom(page).boundingBox();
        const last = marks[marks.length - 1];
        clip = { x: 296, y: Math.round(first.y - 28), width: 688, height: Math.round(last.y + last.height - first.y + 30) };
      }
      await page.screenshot({ path: path.join(OUT, `${shot.name}.raw.png`), clip });
      fs.writeFileSync(path.join(OUT, `${shot.name}.json`), JSON.stringify({ clip, marks }));
      await page.close();
    }
    await browser.close();
    server.close();
  });
