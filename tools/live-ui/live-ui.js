// Detailed UI test of the live web app, run after every merge to main (owner rule, 2026-09-24; docs/06 TC-M-26).
// Every route in 4 languages x 2 themes x phone/desktop (loads, <html lang>, title, h1, no horizontal scroll, no
// untranslated key, the language's script, theme background, axe WCAG 2.1 A/AA serious+critical, console errors),
// the add/edit/compare/download/offline/delete flows, and map screenshots of India's boundary (TC-M-25 spots).
// Usage: npm ci && node live-ui.js [baseUrl] [outDir]. CHROMIUM=<path> to use a local Chromium build.
// It adds and then deletes two "UI test" houses in a fresh browser profile; nothing leaves the browser.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const BASE = (process.argv[2] || 'https://doorprints.web.app').replace(/\/$/, '');
const OUT = process.argv[3] || path.join(__dirname, 'out');
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });
const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
const EN = fs.readFileSync(path.join(__dirname, '../../web/src/app/i18n/en.ts'), 'utf8');
const KEYS = [...EN.matchAll(/^\s*'([a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)+)':/gm)].map((m) => m[1]);
const results = [];
const check = (area, name, ok, detail = '') => {
  results.push({ area, name, ok, detail });
  if (!ok) console.log(`FAIL [${area}] ${name}${detail ? ' — ' + String(detail).slice(0, 300) : ''}`);
};
const ROUTES = ['/', '/compare', '/data', '/connect', '/ask', '/plan', '/share', '/houses/new?lat=12.9716&lon=77.5946', '/does-not-exist'];
const LANGS = ['en', 'hi', 'ta', 'te'];
const THEMES = ['light', 'dark'];
const VIEWPORTS = { phone: { width: 390, height: 844 }, desktop: { width: 1280, height: 900 } };
const IGNORE_CONSOLE = [/tiles\.openfreemap\.org.*(ERR_|40[34])/i, /favicon/i];

async function newCtx(browser, { lang = 'en', theme = 'light', vp = 'desktop', mapView, bypassCSP = false } = {}) {
  // bypassCSP only where axe is injected (the page matrix): the site's CSP rightly refuses inline scripts.
  const ctx = await browser.newContext({ viewport: VIEWPORTS[vp], colorScheme: theme, serviceWorkers: 'allow', acceptDownloads: true, bypassCSP });
  await ctx.addInitScript(([lang, mapView]) => {
    if (sessionStorage.getItem('__init')) return;
    sessionStorage.setItem('__init', '1');
    localStorage.setItem('house-hunt.lang', lang);
    if (mapView) localStorage.setItem('hh.mapView', JSON.stringify(mapView));
  }, [lang, mapView]);
  return ctx;
}
function watch(page) {
  const errors = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error' && !IGNORE_CONSOLE.some((r) => r.test(m.text()))) errors.push(`console: ${m.text().slice(0, 200)}`); });
  page.on('response', (r) => { if (r.url().startsWith(BASE) && r.status() >= 400 && !r.url().includes('does-not-exist')) errors.push(`HTTP ${r.status()} ${r.url()}`); });
  return errors;
}
async function settle(page) {
  try { await page.waitForLoadState('networkidle', { timeout: 15000 }); } catch {}
  await page.waitForTimeout(800);
}

async function pageMatrix(browser) {
  for (const vp of Object.keys(VIEWPORTS)) for (const theme of THEMES) for (const lang of LANGS) {
    const ctx = await newCtx(browser, { lang, theme, vp, bypassCSP: true });
    const page = await ctx.newPage();
    for (const route of ROUTES) {
      const tag = `${route} ${lang} ${theme} ${vp}`;
      const errors = watch(page);
      const resp = await page.goto(BASE + route, { waitUntil: 'domcontentloaded', timeout: 60000 });
      await settle(page);
      check('pages', `${tag}: loads`, !!resp && resp.status() < 400, resp && resp.status());
      const info = await page.evaluate(() => ({
        lang: document.documentElement.lang, title: document.title,
        h1: [...document.querySelectorAll('h1')].map((h) => h.textContent.trim()).filter(Boolean),
        overflow: document.scrollingElement.scrollWidth - window.innerWidth,
        text: document.body.innerText,
        bg: getComputedStyle(document.body).backgroundColor,
      }));
      check('pages', `${tag}: <html lang>`, info.lang.startsWith(lang), info.lang);
      check('pages', `${tag}: title`, info.title.trim().length > 0, info.title);
      check('pages', `${tag}: one h1`, info.h1.length >= 1, JSON.stringify(info.h1));
      check('pages', `${tag}: no horizontal scroll`, info.overflow <= 1, `${info.overflow}px`);
      const raw = KEYS.filter((k) => new RegExp(`(^|[^\\w.])${k.replace(/\./g, '\\.')}($|[^\\w.])`).test(info.text));
      check('i18n', `${tag}: no untranslated keys`, raw.length === 0, raw.slice(0, 5).join(', '));
      if (lang !== 'en' && route !== '/does-not-exist') {
        const script = { hi: /[ऀ-ॿ]/, ta: /[஀-௿]/, te: /[ఀ-౿]/ }[lang];
        check('i18n', `${tag}: text in the language's script`, script.test(info.text));
      }
      const dark = /rgb\((\d+), (\d+), (\d+)/.exec(info.bg);
      if (dark) { const l = (+dark[1] + +dark[2] + +dark[3]) / 3; check('theme', `${tag}: background matches theme`, theme === 'dark' ? l < 100 : l > 150, info.bg); }
      await page.addScriptTag({ content: AXE });
      const axe = await page.evaluate(async () => {
        const r = await window.axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] }, resultTypes: ['violations'] });
        return r.violations.filter((v) => ['serious', 'critical'].includes(v.impact)).map((v) => `${v.id} (${v.nodes.length}): ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`);
      });
      check('a11y', `${tag}: axe serious/critical`, axe.length === 0, axe.join(' ; '));
      check('console', `${tag}: no errors`, errors.length === 0, errors.slice(0, 3).join(' ; '));
      if (lang === 'en' || route === '/') {
        const name = `${vp}_${theme}_${lang}_${route.replace(/[^a-z]+/gi, '_') || 'root'}`.slice(0, 80);
        await page.screenshot({ path: path.join(OUT, 'shots', `${name}.png`) });
      }
    }
    await ctx.close();
  }
}

async function flows(browser) {
  for (const vp of Object.keys(VIEWPORTS)) {
    const ctx = await newCtx(browser, { vp });
    const page = await ctx.newPage();
    const errors = watch(page);
    const name = `UI test ${vp} ${Date.now()}`;
    const addHouse = async (label, lat, lon) => {
      await page.goto(`${BASE}/houses/new?lat=${lat}&lon=${lon}`); await settle(page);
      await page.locator('#house-name').fill(label);
      await page.locator('#house-price').fill('25000');
      await page.locator('#house-bhk').fill('2');
      await page.locator('#house-notes').fill('Created by the live UI test.');
      await page.getByRole('button', { name: /^Add house$/ }).first().click();
      await page.waitForURL(/\/houses\/(?!new)[^/?]+/, { timeout: 15000 }).catch(() => {});
      return (/\/houses\/([^/?]+)/.exec(page.url()) || [])[1];
    };
    const id2 = await addHouse(`${name} B`, 12.975, 77.60);
    const id = await addHouse(name, 12.9716, 77.5946);
    check('flow', `${vp}: add two houses`, !!id && id !== 'new' && !!id2 && id2 !== 'new', page.url());
    // Edit and save
    await page.locator('#house-notes').fill('Edited by the live UI test.');
    await page.getByRole('button', { name: /^Save$/ }).first().click();
    await page.waitForTimeout(1500);
    await page.reload(); await settle(page);
    check('flow', `${vp}: edit persists after reload`, (await page.locator('#house-notes').inputValue()) === 'Edited by the live UI test.');
    // In the list and on Compare
    await page.goto(`${BASE}/`); await settle(page);
    check('flow', `${vp}: both houses in the list`, (await page.getByText(name, { exact: true }).count()) > 0 && (await page.getByText(`${name} B`).count()) > 0);
    await page.screenshot({ path: path.join(OUT, 'shots', `flow_${vp}_list.png`) });
    await page.goto(`${BASE}/compare`); await settle(page);
    check('flow', `${vp}: houses on Compare`, (await page.getByText(name).count()) > 0);
    await page.screenshot({ path: path.join(OUT, 'shots', `flow_${vp}_compare.png`) });
    // Save a copy (download)
    await page.goto(`${BASE}/data`); await settle(page);
    const saveBtn = page.getByRole('button', { name: /^Download$/ }).first();
    if (await saveBtn.isVisible().catch(() => false)) {
      const dl = page.waitForEvent('download', { timeout: 20000 }).catch(() => null);
      await saveBtn.click();
      const d = await dl;
      const file = d && path.join(OUT, `copy_${vp}_${d.suggestedFilename()}`);
      if (d) await d.saveAs(file);
      check('flow', `${vp}: Save a copy downloads a file`, !!d && fs.statSync(file).size > 0, d && d.suggestedFilename());
    } else check('flow', `${vp}: Download button present`, false);
    await page.screenshot({ path: path.join(OUT, 'shots', `flow_${vp}_data.png`) });
    // Offline: the service worker serves the app shell
    await page.goto(`${BASE}/`); await settle(page);
    const sw = await page.evaluate(async () => !!(await navigator.serviceWorker.getRegistration()));
    check('pwa', `${vp}: service worker registered`, sw);
    await page.waitForTimeout(2000);
    await ctx.setOffline(true);
    await page.goto(`${BASE}/houses/${id}`).catch(() => {});
    await page.waitForTimeout(2500);
    check('pwa', `${vp}: house opens offline`, (await page.locator('#house-name').inputValue().catch(() => '')) === name);
    await page.screenshot({ path: path.join(OUT, 'shots', `flow_${vp}_offline.png`) });
    await ctx.setOffline(false);
    // Delete the house
    for (const hid of [id, id2]) {
      await page.goto(`${BASE}/houses/${hid}`); await settle(page);
      await page.getByRole('button', { name: /^Delete( house)?$/ }).first().click().catch(() => {});
      await page.getByRole('dialog').getByRole('button', { name: /^Delete( house)?$/ }).click({ timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(1500);
    }
    await page.goto(`${BASE}/compare`); await settle(page);
    await page.screenshot({ path: path.join(OUT, 'shots', `flow_${vp}_after_delete.png`) });
    check('flow', `${vp}: houses deleted`, (await page.getByText(name).count()) === 0);
    check('console', `${vp} flows: no errors`, errors.length === 0, errors.slice(0, 3).join(' ; '));
    await ctx.close();
  }
}

async function boundaries(browser) {
  const r = await (await browser.newContext()).request.get(`${BASE}/geo/in-boundaries.geojson`);
  const g = r.status() === 200 ? await r.json() : null;
  check('map', 'in-boundaries.geojson served with world, claim and state', !!g && ['world', 'claim', 'state'].every((k) => g.features.some((f) => f.properties.kind === k)));
  const views = [
    ['india_z4', 23.5, 80, 4], ['kashmir_z6', 34.5, 76, 6], ['arunachal_z7', 28, 94, 7],
    ['sikkim_z9', 27.5, 88.4, 9], ['singalila_z10', 27.2, 88.0, 10], ['assam_state_z7', 27.3, 93.5, 7],
  ];
  const ctx = await newCtx(browser, { vp: 'desktop' });
  for (const [name, lat, lon, zoom] of views) {
    await ctx.clearCookies();
    const page = await ctx.newPage();
    await page.addInitScript((v) => localStorage.setItem('hh.mapView', JSON.stringify(v)), { lat, lon, zoom });
    await page.goto(`${BASE}/`); await settle(page); await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(OUT, 'shots', `map_${name}.png`) });
    await page.close();
  }
  await ctx.close();
}

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined, args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });
  const t0 = Date.now();
  for (const [name, fn] of [['pages', pageMatrix], ['flows', flows], ['boundaries', boundaries]]) {
    try { await fn(browser); } catch (e) { check(name, `${name} ran to the end`, false, e.stack); }
  }
  await browser.close();
  const byArea = {};
  for (const r of results) { byArea[r.area] ??= { pass: 0, fail: 0 }; byArea[r.area][r.ok ? 'pass' : 'fail']++; }
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ base: BASE, at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), byArea, results }, null, 1));
  console.log(JSON.stringify(byArea), `${Math.round((Date.now() - t0) / 1000)} s`);
})();
