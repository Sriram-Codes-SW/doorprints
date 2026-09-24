// Detailed UI test of the live web app, run after a merge to main that runs the Web deploy (docs/06 TC-M-26).
// Every route in 4 languages x 2 themes x phone/desktop (loads, <html lang>, title, h1, no horizontal scroll, no
// untranslated key, the language's script, theme background, axe WCAG 2.1 A/AA serious+critical, console errors),
// the add/edit/compare/download/offline/delete flows, map screenshots of India's boundary (TC-M-25 spots), and a
// mobile pass on emulated phones (Pixel 7, Galaxy S9+, iPhone SE, iPhone 14, 320 and 360px wide, landscape, 200%
// text; touch, device pixel ratio and mobile viewport): no sideways scroll of the page or of <main>, touch targets of
// at least 44x44 CSS px, form fields in at least 16px text (iOS zooms into smaller ones), nothing left under the
// bottom bar at the end of a page, MapLibre's controls inside the map and clear of its legend and buttons, the map's
// credits folded into their (i) button after 5 s, its labels drawn, the list's heading and counters on screen under
// the phone map, and a field still visible above the on-screen keyboard.
// Usage: npm ci && npx playwright install chromium && node live-ui.js [baseUrl] [outDir] (or CHROMIUM=<path> for a
// local Chromium build; ONLY=mobile, or another comma-separated list of pages, flows, boundaries, mobile, runs only
// those). Exits 1 when any check fails. A slow check: the full matrix takes about 20-30 minutes.
// It adds and then deletes two "UI test" houses in a fresh browser profile; nothing leaves the browser (the mobile
// pass adds one house per phone profile, which goes with the profile).
const { chromium, devices } = require('playwright');
const fs = require('fs'), path = require('path');
const BASE = (process.argv[2] || 'https://doorprints.web.app').replace(/\/$/, '');
const OUT = process.argv[3] || path.join(__dirname, 'out');
fs.mkdirSync(path.join(OUT, 'shots'), { recursive: true });
const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
const EN = fs.readFileSync(path.join(__dirname, '../../web/src/app/i18n/en.ts'), 'utf8');
const KEYS = [...EN.matchAll(/^\s*'([a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)+)':/gm)].map((m) => m[1]);
/** Every regular-expression metacharacter escaped, so a key is matched literally. */
const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
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
    localStorage.setItem('doorprints.lang', lang);
    if (mapView) localStorage.setItem('doorprints.mapView', JSON.stringify(mapView));
  }, [lang, mapView]);
  return ctx;
}
/** Collects errors for a page; call reset() before each route so late errors are not counted against the next. */
function watch(page) {
  const errors = [];
  errors.reset = () => { errors.length = 0; };
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
    const errors = watch(page);
    for (const route of ROUTES) {
      const tag = `${route} ${lang} ${theme} ${vp}`;
      await page.waitForTimeout(300);
      errors.reset();
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
      const raw = KEYS.filter((k) => new RegExp(`(^|[^\\w.])${escapeRegExp(k)}($|[^\\w.])`).test(info.text));
      check('i18n', `${tag}: no untranslated keys`, raw.length === 0, raw.slice(0, 5).join(', '));
      if (lang !== 'en' && route !== '/does-not-exist') {
        const script = { hi: /[ऀ-ॿ]/, ta: /[஀-௿]/, te: /[ఀ-౿]/ }[lang];
        check('i18n', `${tag}: text in the language's script`, script.test(info.text));
      }
      const dark = /rgba?\((\d+), (\d+), (\d+)/.exec(info.bg);
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
    const leftOnCompare = await page.getByText(name).count();
    await page.goto(`${BASE}/`); await settle(page);
    check('flow', `${vp}: houses deleted`, leftOnCompare === 0 && (await page.getByText(name).count()) === 0);
    check('console', `${vp} flows: no errors`, errors.length === 0, errors.slice(0, 3).join(' ; '));
    await ctx.close();
  }
}

// ---------- Mobile pass (owner report 2026-09-24: display issues on a phone browser) ----------
/** Phones: Playwright's device profiles (touch, isMobile, device pixel ratio, mobile user agent), all in Chromium. */
const PHONES = [
  { name: 'pixel7', device: 'Pixel 7', langs: LANGS, themes: THEMES },
  { name: 'galaxyS9', device: 'Galaxy S9+', langs: ['en', 'ta'] },
  { name: 'iphoneSE', device: 'iPhone SE', langs: ['en', 'te'] },
  { name: 'iphone14', device: 'iPhone 14', langs: ['en', 'hi'] },
  { name: 'w320', device: 'Galaxy S9+', viewport: { width: 320, height: 640 }, langs: ['en', 'ta'] },
  { name: 'w360', device: 'Pixel 7', viewport: { width: 360, height: 740 }, langs: ['en', 'te'] },
  { name: 'pixel7-landscape', device: 'Pixel 7 landscape', langs: ['en', 'ta'] },
  { name: 'iphoneSE-landscape', device: 'iPhone SE landscape', langs: ['en'] },
  // The owner's phone (report of 2026-09-24): a 384px Android screen, about 615px left by Chrome, larger text.
  { name: 'owner384-text130', device: 'Galaxy S9+', viewport: { width: 384, height: 615 }, text: 130, langs: ['en', 'ta'] },
  // Large text: the root font size at 200% (Chrome's and the OS text size scale everything set in rem, as here).
  { name: 'w360-text200', device: 'Pixel 7', viewport: { width: 360, height: 740 }, text: 200, langs: ['en', 'ta'] },
];
/** How long a phone map shows its credits in full before folding them (ATTRIBUTION_SHOW_MS in map-style.ts). */
const CREDITS_FOLD_MS = 5000;
/**
 * Share of dark pixels (luminance under 90) in the map at the India view (the mobile pass stores one, see mobile())
 * with its labels drawn: about 3% with the city and country names, under 1% (hillshade, rivers) without them.
 */
const LABEL_DARK_SHARE = 0.015;

/**
 * Whether the map draws its labels: the glyphs for its text were downloaded (MapLibre asks for a glyph range only to
 * lay out text it will draw) and the map area, left of the control column and above the bottom row, has the dark
 * pixels of the names. Measured in the page from a screenshot, so no image library is needed here.
 */
async function mapLabels(page, glyphs) {
  const box = await page.evaluate(() => {
    const wrap = document.querySelector('.map-wrap'), stack = document.querySelector('.map-stack');
    if (!wrap) return null;
    const r = wrap.getBoundingClientRect(), s = stack ? stack.getBoundingClientRect() : null;
    const bottom = s && s.height > 0 && s.top > r.top + 80 ? s.top : r.bottom;
    return { x: Math.round(r.left), y: Math.round(r.top), width: Math.round(r.width - 60), height: Math.round(bottom - r.top) };
  });
  if (!box || box.width < 50 || box.height < 50) return { glyphs, dark: null };
  const png = (await page.screenshot({ clip: box })).toString('base64');
  const dark = await page.evaluate(async (data) => {
    // An <img>, not fetch(): the site's CSP allows data: images but not data: connections.
    const img = new Image();
    img.src = `data:image/png;base64,${data}`;
    await img.decode();
    const canvas = new OffscreenCanvas(img.naturalWidth, img.naturalHeight), g = canvas.getContext('2d');
    g.drawImage(img, 0, 0);
    const px = g.getImageData(0, 0, img.naturalWidth, img.naturalHeight).data;
    let n = 0;
    for (let i = 0; i < px.length; i += 4) if (0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2] < 90) n++;
    return n / (px.length / 4);
  }, png);
  return { glyphs, dark: Math.round(dark * 1000) / 1000 };
}

/** Runs in the page: the layout problems a phone shows, as a list of findings (empty when all is well). */
function mobileAudit() {
  const out = [];
  // A page wider than the phone's screen widens the layout viewport (innerWidth) and is shown zoomed out, so the
  // screen width is the measure (the context's screen is its viewport).
  const W = Math.min(window.innerWidth, screen.width), H = window.innerHeight;
  if (window.innerWidth > screen.width + 1) out.push(`page laid out ${window.innerWidth}px wide on a ${screen.width}px screen (zoomed out)`);
  const shown = (el) => { const s = getComputedStyle(el); if (s.visibility === 'hidden' || s.display === 'none') return false; const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const hidden = (el) => el.closest('.sr-only, [aria-hidden="true"], .skip-link') !== null;
  const name = (el) => { const t = (el.getAttribute('aria-label') || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 30); return `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${el.classList.length ? '.' + [...el.classList].slice(0, 2).join('.') : ''}${t ? ` "${t}"` : ''}`; };
  const main = document.querySelector('main');
  // Sideways scroll: the document, and <main> (the app's page scroller, which the document check does not see).
  const doc = document.scrollingElement.scrollWidth - W;
  if (doc > 1) out.push(`page scrolls sideways by ${doc}px`);
  if (main && main.scrollWidth - main.clientWidth > 1) out.push(`main scrolls sideways by ${main.scrollWidth - main.clientWidth}px`);
  // Touch targets: 44x44 CSS px (UX-007). An input inside a label counts by its label; links inside running text
  // (WCAG 2.5.8's inline exception), MapLibre's credits (a 24px (i) and links in its text) and the house form's
  // draggable pin (27x41; the map tap, "Use my location" and the typed coordinates do the same, 2.5.8's equivalent
  // exception) are not counted.
  const small = [];
  for (const el of document.querySelectorAll('a[href], button, input:not([type=hidden]), select, textarea, summary, [role=button]')) {
    if (!shown(el) || hidden(el) || el.closest('.maplibregl-ctrl-attrib, .maplibregl-marker')) continue;
    if (el.tagName === 'A' && getComputedStyle(el).display === 'inline' && /^(P|LI|SPAN|SMALL|DD|TD)$/.test(el.parentElement.tagName)) continue;
    let { width: w, height: h } = el.getBoundingClientRect();
    const label = el.tagName === 'INPUT' && (el.closest('label') || (el.id && document.querySelector(`label[for="${el.id}"]`)));
    if (label) { const r = label.getBoundingClientRect(); const row = el.parentElement.getBoundingClientRect(); w = Math.max(w, r.width); h = Math.max(h, r.height, el.type === 'checkbox' || el.type === 'radio' ? row.height : 0); }
    if (w < 43.5 || h < 43.5) small.push(`${name(el)} ${Math.round(w)}x${Math.round(h)}`);
  }
  if (small.length) out.push(`touch targets under 44px: ${small.slice(0, 4).join(', ')}`);
  // Text fields under 16px: iOS Safari zooms the page into them on focus.
  const tiny = [...document.querySelectorAll('input:not([type=checkbox]):not([type=radio]):not([type=range]):not([type=file]):not([type=hidden]), select, textarea')]
    .filter((el) => shown(el) && !hidden(el) && parseFloat(getComputedStyle(el).fontSize) < 16).map((el) => `${name(el)} ${getComputedStyle(el).fontSize}`);
  if (tiny.length) out.push(`fields under 16px text: ${tiny.slice(0, 3).join(', ')}`);
  // Scrolled to the end, nothing is left under the fixed bottom bar.
  const bar = document.querySelector('nav.nav');
  if (main && bar && getComputedStyle(bar).position === 'fixed' && shown(bar)) {
    const top = main.scrollTop; main.scrollTop = main.scrollHeight;
    const barTop = bar.getBoundingClientRect().top;
    const under = [...main.querySelectorAll('button, a[href], input, select, textarea, p, li, h2, h3')].filter((el) => shown(el) && !hidden(el) && el.getBoundingClientRect().bottom > barTop + 1);
    if (under.length) out.push(`under the bottom bar at the end of the page: ${under.slice(0, 3).map((el) => `${name(el)} by ${Math.round(el.getBoundingClientRect().bottom - barTop)}px`).join(', ')}`);
    main.scrollTop = top;
  }
  // MapLibre's controls stay inside the map and clear of the page's legend, buttons and hint.
  const ctrl = document.querySelector('.map-wrap .maplibregl-ctrl-bottom-right, .map-wrap .maplibregl-ctrl-top-right');
  const wrap = document.querySelector('.map-wrap');
  if (ctrl && wrap && shown(wrap)) {
    const box = wrap.getBoundingClientRect();
    const parts = [...ctrl.children].filter(shown).map((c) => c.getBoundingClientRect());
    for (const r of parts) if (r.top < box.top - 1 || r.bottom > box.bottom + 1) out.push(`a map control (${Math.round(r.top)}-${Math.round(r.bottom)}px) outside the map (${Math.round(box.top)}-${Math.round(box.bottom)}px)`);
    for (const el of document.querySelectorAll('.map-stack > *, .add-hint')) {
      if (!shown(el)) continue;
      const b = el.getBoundingClientRect();
      if (parts.some((r) => Math.min(r.right, b.right) - Math.max(r.left, b.left) > 1 && Math.min(r.bottom, b.bottom) - Math.max(r.top, b.top) > 1)) out.push(`a map control overlaps ${name(el)}`);
    }
  }
  // The skip link stays out of sight until it has focus, however many lines its label wraps to.
  const skip = document.querySelector('.skip-link');
  if (skip && document.activeElement !== skip && skip.getBoundingClientRect().bottom > 0) out.push(`skip link showing without focus (bottom ${Math.round(skip.getBoundingClientRect().bottom)}px)`);
  // The app fits the screen: header, banners and page no taller than the viewport.
  const root = document.querySelector('app-root');
  if (root && root.getBoundingClientRect().height > H + 1) out.push(`app taller than the screen: ${Math.round(root.getBoundingClientRect().height)}px of ${H}px`);
  return out;
}

async function mobile(browser) {
  for (const phone of PHONES) for (const lang of phone.langs) for (const theme of phone.themes && lang === 'en' ? phone.themes : ['light']) {
    const { defaultBrowserType, ...profile } = devices[phone.device];
    const viewport = phone.viewport || profile.viewport;
    const ctx = await browser.newContext({ ...profile, viewport, screen: viewport, colorScheme: theme, serviceWorkers: 'block' });
    await ctx.addInitScript(([lang, text]) => {
      try {
        if (!sessionStorage.getItem('__init')) {
          sessionStorage.setItem('__init', '1');
          localStorage.setItem('doorprints.lang', lang);
        }
      } catch {}
      if (text) document.addEventListener('DOMContentLoaded', () => { document.documentElement.style.fontSize = `${text}%`; });
    }, [lang, phone.text]);
    const page = await ctx.newPage();
    const errors = watch(page);
    // Glyph ranges for the map's labels (requested from MapLibre's worker, seen at the context).
    let glyphs = 0;
    ctx.on('response', (r) => { if (/\/fonts\/.+\.pbf/.test(r.url()) && r.status() === 200) glyphs++; });
    const tag0 = `${phone.name} ${lang} ${theme}`;
    // One house is added after the routes, so its page is checked too (it goes with this profile). Not before: the Map
    // page's first visit would fit to it at street level, and the labels check looks at the country view.
    const addHouse = async () => {
      await page.goto(`${BASE}/houses/new?lat=12.9716&lon=77.5946`); await settle(page);
      await page.locator('#house-name').fill('Mobile check: a house with a fairly long name, Indiranagar 2nd Stage');
      await page.locator('.toolbar .btn-primary').first().click();
      await page.waitForURL(/\/houses\/(?!new)[^/?]+/, { timeout: 15000 }).catch(() => {});
      const id = (/\/houses\/(?!new)([^/?]+)/.exec(page.url()) || [])[1];
      check('mobile', `${tag0}: a house added`, !!id, page.url());
      return id ? `/houses/${id}` : null;
    };
    for (const listed of [...ROUTES, 'HOUSE']) {
      const route = listed === 'HOUSE' ? await addHouse() : listed;
      if (!route) continue;
      const tag = `${tag0} ${route.startsWith('/houses/') && route !== ROUTES[7] ? '/houses/:id' : route}`;
      errors.reset();
      await page.goto(BASE + route, { waitUntil: 'domcontentloaded', timeout: 60000 });
      await settle(page);
      if (route === '/') await page.waitForTimeout(CREDITS_FOLD_MS + 1500);
      const found = await page.evaluate(mobileAudit);
      check('mobile', `${tag}: layout`, found.length === 0, found.join(' ; '));
      if (route === '/' && viewport.width <= 640) {
        const open = await page.evaluate(() => { const a = document.querySelector('.map-wrap .maplibregl-ctrl-attrib'); return a ? a.classList.contains('maplibregl-compact-show') : null; });
        check('mobile', `${tag}: map credits folded after ${CREDITS_FOLD_MS / 1000} s`, open !== true, String(open));
      }
      if (route === '/') {
        // The map keeps its names (country, cities): the fixes must not cost the labels.
        const labels = await mapLabels(page, glyphs);
        check('map', `${tag}: map labels drawn`, labels.glyphs > 0 && (labels.dark === null || theme === 'dark' || labels.dark >= LABEL_DARK_SHARE), JSON.stringify(labels));
        // Portrait phones up to 130% text: "Your houses" and every counter, number and caption, are on screen above
        // the bottom bar before any scrolling (owner report 2026-09-24). The one exception is the map-page rule that
        // MapLibre's controls must fit above the legend row: when the map is at that minimum height (map-page.css,
        // min-height), the controls win, as with Tamil at 130% on a 384x615 phone, and the counters start below.
        if (viewport.height >= 560 && viewport.height > viewport.width && viewport.width <= 760 && (phone.text || 100) <= 130) {
          const peek = await page.evaluate(() => {
            const bar = document.querySelector('nav.nav'), limit = bar && getComputedStyle(bar).position === 'fixed' ? bar.getBoundingClientRect().top : innerHeight;
            const parts = [...document.querySelectorAll('#houses-heading, .stats dt, .stats dd')];
            const wrap = document.querySelector('.map-wrap');
            const atControlsMin = !!wrap && wrap.getBoundingClientRect().height <= parseFloat(getComputedStyle(wrap).minHeight) + 1;
            return { limit: Math.round(limit), n: parts.length, lowest: Math.round(Math.max(...parts.map((e) => e.getBoundingClientRect().bottom))), atControlsMin };
          });
          check('mobile', `${tag}: heading and counters above the bottom bar`, peek.n >= 11 && (peek.lowest <= peek.limit || peek.atControlsMin), JSON.stringify(peek));
        }
      }
      // The on-screen keyboard where the browser shrinks the page for it: the focused field stays in view, clear of
      // the bottom bar (portrait only; a landscape phone is already that short).
      if (viewport.height > viewport.width && !phone.text && (route === '/connect' || route.startsWith('/houses/'))) {
        await page.setViewportSize({ width: viewport.width, height: Math.round(viewport.height * 0.55) });
        const field = page.locator('main input[type=text], main input[type=url], main input:not([type]), main textarea').first();
        if (await field.count()) {
          await field.focus(); await page.waitForTimeout(400);
          const kb = await page.evaluate(() => {
            const r = document.activeElement.getBoundingClientRect(), bar = document.querySelector('nav.nav');
            const barTop = bar && getComputedStyle(bar).display !== 'none' && getComputedStyle(bar).position === 'fixed' ? bar.getBoundingClientRect().top : innerHeight;
            return { top: Math.round(r.top), bottom: Math.round(r.bottom), limit: Math.round(Math.min(innerHeight, barTop)) };
          });
          check('mobile', `${tag}: focused field above the keyboard`, kb.top >= 0 && kb.bottom <= kb.limit, JSON.stringify(kb));
        }
        await page.setViewportSize(viewport);
      }
      check('console', `${tag} (mobile): no errors`, errors.length === 0, errors.slice(0, 3).join(' ; '));
      if (lang === 'en' || route === '/') {
        const file = `mobile_${phone.name}_${theme}_${lang}_${route.replace(/[^a-z]+/gi, '_') || 'root'}`.slice(0, 80);
        await page.screenshot({ path: path.join(OUT, 'shots', `${file}.png`) });
      }
    }
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
    await page.addInitScript((v) => localStorage.setItem('doorprints.mapView', JSON.stringify(v)), { lat, lon, zoom });
    await page.goto(`${BASE}/`); await settle(page); await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(OUT, 'shots', `map_${name}.png`) });
    await page.close();
  }
  await ctx.close();
}

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined, args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });
  const t0 = Date.now();
  const only = (process.env.ONLY || '').split(',').filter(Boolean);
  for (const [name, fn] of [['pages', pageMatrix], ['flows', flows], ['boundaries', boundaries], ['mobile', mobile]]) {
    if (only.length && !only.includes(name)) continue;
    try { await fn(browser); } catch (e) { check(name, `${name} ran to the end`, false, e.stack); }
  }
  await browser.close();
  const byArea = {};
  for (const r of results) { byArea[r.area] ??= { pass: 0, fail: 0 }; byArea[r.area][r.ok ? 'pass' : 'fail']++; }
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ base: BASE, at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), byArea, results }, null, 1));
  console.log(JSON.stringify(byArea), `${Math.round((Date.now() - t0) / 1000)} s`);
  process.exitCode = results.some((r) => !r.ok) ? 1 : 0;
})();
