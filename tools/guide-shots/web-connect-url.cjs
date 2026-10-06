// Takes the raw picture for the guide's "Set up your own server" page, step 9: the website's Connect page with an
// example server address typed in. Then `python3 tools/guide-shots/annotate.py` numbers the two things to look at and
// shrinks the PNG (about 12 KB).
//
//   cd web && npm ci && npm run build            # the built site
//   cd ../tools/live-ui && npm ci                # playwright-core
//   CHROMIUM=<path to chrome> node tools/guide-shots/web-connect-url.cjs [builtSiteDir] [outDir]
//
// Serves the built site on a local port (no network), opens /connect at 1280x700, types the example address, writes
// raw.png (a 688x408 clip of the card) and boxes.json (where the field and the button are). Nothing leaves the machine.
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require(path.join(__dirname, '..', 'live-ui', 'node_modules', 'playwright-core'));

const ROOT = path.resolve(process.argv[2] || path.join(__dirname, '..', '..', 'web', 'dist', 'web', 'browser'));
const OUT = path.resolve(process.argv[3] || path.join(__dirname, 'out'));
const EXAMPLE = 'https://my-pc.tail1234.ts.net';
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.png': 'image/png', '.webmanifest': 'application/manifest+json' };

fs.mkdirSync(OUT, { recursive: true });
const server = http
  .createServer((req, res) => {
    let file = path.join(ROOT, decodeURIComponent(req.url.split('?')[0]));
    if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(ROOT, 'index.html');
    res.writeHead(200, { 'content-type': types[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  })
  .listen(0, async () => {
    const port = server.address().port;
    const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined });
    const page = await browser.newPage({ viewport: { width: 1280, height: 700 } });
    await page.goto(`http://localhost:${port}/connect`, { waitUntil: 'networkidle' });
    const input = page.locator('input[type="url"]').first();
    await input.fill(EXAMPLE);
    const boxes = { url: await input.boundingBox(), btn: await page.getByRole('button', { name: /get a code/i }).boundingBox() };
    fs.writeFileSync(path.join(OUT, 'boxes.json'), JSON.stringify(boxes));
    await page.screenshot({ path: path.join(OUT, 'raw.png'), clip: { x: 296, y: 196, width: 688, height: 408 } });
    await browser.close();
    server.close();
  });
