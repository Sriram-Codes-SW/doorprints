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
// those; SERIAL=1 runs the areas one after another instead of side by side). Exits 1 when any check fails. The full
// matrix takes about 12-15 minutes. A network fault that a second fetch clears (a 502, a stylesheet with the wrong
// type through a proxy) is reported under "transient" in results.json, not counted as a failure.
// It adds and then deletes two "UI test" houses in a fresh browser profile; nothing leaves the browser (the mobile
// pass adds one house per phone profile, which goes with the profile).
// `--trace` runs ONLY the path trace scenario (docs/11 5.27.8 and 5.27.13, S4b-FR-15, S4b-FR-17, S4b-FR-24), after a merge
// that deploys web/**: in a fresh browser profile with the geolocation permission and a place the test moves
// (setGeolocation), it switches the trace on, presses Start a walk, walks 30 steps of 22 m, presses Finish walk and Keep for
// 30 days, then asks Have I been here? > Where I am now on the street (the answer says "within" and today's weekday) and
// 100 m off it (the "no walk passed" sentence), counts the requests of the click that are not the map's (there must be none),
// checks that Close withdraws the sentence from the page's live region, and takes a screenshot of the answer on a 360x640
// phone. Never run it against anything but the deploy under test; it writes nothing outside its own browser profile.
const { chromium, devices } = require('playwright');
const { walkSteps, eastOf, nonTileRequests, todayWeekday } = require('./trace-helpers');
const fs = require('fs'), path = require('path');
const ARGS = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const WANT_TRACE = process.argv.includes('--trace');
const BASE = (ARGS[0] || 'https://doorprints.web.app').replace(/\/$/, '');
const OUT = ARGS[1] || path.join(__dirname, 'out');
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

async function newCtx(browser, { lang = 'en', theme = 'light', vp = 'desktop', mapView, bypassCSP = false, geolocation, viewport } = {}) {
  // bypassCSP only where axe is injected (the page matrix): the site's CSP rightly refuses inline scripts.
  // geolocation: the place the browser reports (the path trace scenario moves it with ctx.setGeolocation); with it the permission is granted.
  const ctx = await browser.newContext({ viewport: viewport || VIEWPORTS[vp], colorScheme: theme, serviceWorkers: 'allow', acceptDownloads: true, bypassCSP, ...(geolocation ? { geolocation, permissions: ['geolocation'] } : {}) });
  await ctx.addInitScript(([lang, mapView]) => {
    if (sessionStorage.getItem('__init')) return;
    sessionStorage.setItem('__init', '1');
    localStorage.setItem('doorprints.lang', lang);
    if (mapView) localStorage.setItem('doorprints.mapView', JSON.stringify(mapView));
  }, [lang, mapView]);
  return ctx;
}
/**
 * Faults of the network between this test and the site, not of the site: a 502/503/504, or a script or stylesheet
 * delivered with the wrong type (seen through a Claude Code session's proxy, about 1 in 2 000 responses, 2026-09-28
 * and -29), that a second fetch of the same URL gets right. Each is written to results.json with its evidence (status,
 * type, whether the service worker served it, the via/server/cache headers) and printed, but is not a failure; the
 * same fault on the second fetch is. Seen often, it is worth a ticket (docs/06 TC-M-26).
 */
const transient = [];
/** The type a hashed asset must have, or null for other URLs. */
const expectedType = (url) => (/\.css(\?|$)/.test(url) ? 'text/css' : /\.m?js(\?|$)/.test(url) ? 'javascript' : null);
/** Fetches [url] again, outside the page and its service worker; true when that answer is right. */
async function refetchOk(page, url, type) {
  try {
    const again = await page.context().request.get(url, { timeout: 30000 });
    return again.status() < 400 && (!type || (again.headers()['content-type'] || '').includes(type));
  } catch {
    return false;
  }
}
/**
 * Collects errors for a page; call reset() before each route so late errors are not counted against the next, and
 * `await settle()` before reading them (a suspect response is fetched again first, see {@link transient}).
 */
function watch(page) {
  const errors = [];
  const pending = [];
  const cleared = new Set(); // URLs whose fault was transient: their console echoes are dropped too
  let cleared5xx = 0;
  let droppedNet = 0; // requests the network dropped (requestfailed), whose console echoes name no URL
  errors.reset = () => { errors.length = 0; pending.length = 0; cleared.clear(); suspects.clear(); cleared5xx = 0; droppedNet = 0; };
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  const suspects = new Set(); // one second fetch per URL and route
  /** Fetches [url] again; clears it as transient with [evidence], or records it as a real error. */
  const classify = (url, type, evidence, gateway, realError) => {
    if (suspects.has(url)) return;
    suspects.add(url);
    pending.push(refetchOk(page, url, type).then((ok) => {
      if (ok) {
        transient.push({ ...evidence, page: page.url(), at: new Date().toISOString() });
        console.log(`NETWORK (not counted) ${evidence.status} '${evidence.type ?? ''}' ${url} — right on a second fetch`);
        cleared.add(url);
        if (gateway) cleared5xx++;
      } else {
        errors.push(realError);
      }
    }));
  };
  page.on('console', (m) => {
    if (m.type() !== 'error' || IGNORE_CONSOLE.some((r) => r.test(m.text()))) return;
    errors.push(`console: ${m.text().slice(0, 200)}`);
    // Chromium refuses a stylesheet or script with the wrong type before the test sees a response for it; its message
    // names the URL and the type it got.
    const refused = /Refused to (?:apply style|execute script) from '([^']+)' because its MIME type \('([^']*)'\)/.exec(m.text());
    if (refused && refused[1].startsWith(BASE)) {
      const [, url, got] = refused;
      noteFault(page);
      classify(url, expectedType(url), { url, status: 'refused', type: got }, false, `wrong type '${got}' for ${url} (twice)`);
    }
  });
  // A request the network dropped before any answer (a connection reset through this session's proxy, 2026-09-29):
  // no response event, and Chromium's console line "Failed to load resource: net::ERR_…" does not name the URL.
  page.on('requestfailed', (req) => {
    const url = req.url();
    const why = req.failure()?.errorText ?? '';
    if (!url.startsWith(BASE) || !/ERR_(CONNECTION_(RESET|CLOSED|ABORTED)|EMPTY_RESPONSE|TIMED_OUT|HTTP2_PROTOCOL_ERROR|NETWORK_CHANGED)/.test(why)) return;
    noteFault(page);
    const evidence = { url, status: why, type: null };
    suspects.delete(url);
    classify(url, expectedType(url), evidence, false, `${why} for ${url} (twice)`);
    droppedNet++;
  });
  page.on('response', (r) => {
    const url = r.url();
    if (!url.startsWith(BASE) || url.includes('does-not-exist')) return;
    if (r.request().isNavigationRequest() && r.request().frame() === page.mainFrame()) {
      // gotoRetry loads it again and records it; only Chromium's console echo of the 50x is dropped here.
      if (r.status() >= 502 && r.status() <= 504) cleared5xx++;
      return;
    }
    const status = r.status();
    const type = expectedType(url);
    const got = r.headers()['content-type'] || '';
    const wrongType = status < 400 && type && !got.includes(type);
    const gateway = status >= 502 && status <= 504;
    if (!wrongType && status < 400) return;
    if (!wrongType && !gateway) { errors.push(`HTTP ${status} ${url}`); return; }
    noteFault(page);
    const h = r.headers();
    const evidence = { url, status, type: got, fromServiceWorker: r.fromServiceWorker(), via: h.via, server: h.server, cache: h['x-cache'] };
    classify(url, type, evidence, gateway, wrongType ? `wrong type '${got}' for ${url} (twice)` : `HTTP ${status} ${url} (twice)`);
  });
  errors.settle = async () => {
    await Promise.all(pending);
    // Chromium's own console lines about those responses: the MIME refusal names the URL; "Failed to load resource:
    // ... status of 50x" does not, so as many of those as there were transient gateway errors are dropped.
    let drop5xx = cleared5xx;
    // Dropped requests that a second fetch got: only then is their unnamed console echo dropped (a real error stays).
    let dropNet = droppedNet - errors.filter((e) => / \(twice\)$/.test(e) && /ERR_/.test(e)).length;
    for (let i = errors.length - 1; i >= 0; i--) {
      const e = errors[i];
      if ([...cleared].some((u) => e.includes(u))) errors.splice(i, 1);
      else if (drop5xx > 0 && /status of 50[234]/.test(e)) { errors.splice(i, 1); drop5xx--; }
      else if (dropNet > 0 && /Failed to load resource: net::ERR_/.test(e)) { errors.splice(i, 1); dropNet--; }
    }
    return errors;
  };
  return errors;
}
/** Network faults seen on each page's subresources so far (a 50x or a refused type), counted as they arrive. */
const pageFaults = new WeakMap();
const noteFault = (page) => pageFaults.set(page, (pageFaults.get(page) || 0) + 1);
/**
 * page.goto, once more after a transient gateway error or a timeout (recorded in {@link transient}), or when one of the
 * page's own scripts or stylesheets hit a network fault while it loaded: a chunk that failed leaves the app half
 * started (2026-09-29: a 502 on the shared chunk left /houses/new without its form), so the page is loaded again.
 */
async function gotoRetry(page, url, opts = {}) {
  const o = { waitUntil: 'domcontentloaded', timeout: 60000, ...opts };
  const faultsBefore = pageFaults.get(page) || 0;
  let first;
  let why = null;
  try {
    first = await page.goto(url, o);
    if (first && first.status() >= 502 && first.status() <= 504) why = first.status();
  } catch (e) {
    if (!/Timeout/.test(e.message)) throw e;
    why = 'timeout';
  }
  if (why === null) {
    // Lazy chunks load after DOMContentLoaded: wait for the network to go quiet before judging the load.
    try { await page.waitForLoadState('networkidle', { timeout: 15000 }); } catch {}
    if ((pageFaults.get(page) || 0) === faultsBefore) return first;
    why = 'a script or stylesheet failed';
  } else {
    transient.push({ url, status: why, page: 'navigation', at: new Date().toISOString() });
  }
  // Only a retry; whether the fault counts is decided by the second fetch (see watch()).
  console.log(`NETWORK ${why} on ${url} — loading the page again`);
  return page.goto(url, o);
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
      const resp = await gotoRetry(page, BASE + route);
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
      await errors.settle();
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
      await gotoRetry(page, `${BASE}/houses/new?lat=${lat}&lon=${lon}`); await settle(page);
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
    await gotoRetry(page, `${BASE}/`); await settle(page);
    check('flow', `${vp}: both houses in the list`, (await page.getByText(name, { exact: true }).count()) > 0 && (await page.getByText(`${name} B`).count()) > 0);
    await page.screenshot({ path: path.join(OUT, 'shots', `flow_${vp}_list.png`) });
    await gotoRetry(page, `${BASE}/compare`); await settle(page);
    check('flow', `${vp}: houses on Compare`, (await page.getByText(name).count()) > 0);
    await page.screenshot({ path: path.join(OUT, 'shots', `flow_${vp}_compare.png`) });
    // Save a copy (download)
    await gotoRetry(page, `${BASE}/data`); await settle(page);
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
    await gotoRetry(page, `${BASE}/`); await settle(page);
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
      await gotoRetry(page, `${BASE}/houses/${hid}`); await settle(page);
      await page.getByRole('button', { name: /^Delete( house)?$/ }).first().click().catch(() => {});
      await page.getByRole('dialog').getByRole('button', { name: /^Delete( house)?$/ }).click({ timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(1500);
    }
    await gotoRetry(page, `${BASE}/compare`); await settle(page);
    await page.screenshot({ path: path.join(OUT, 'shots', `flow_${vp}_after_delete.png`) });
    const leftOnCompare = await page.getByText(name).count();
    await gotoRetry(page, `${BASE}/`); await settle(page);
    check('flow', `${vp}: houses deleted`, leftOnCompare === 0 && (await page.getByText(name).count()) === 0);
    await errors.settle();
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
  // checkVisibility(): content inside a closed <details> keeps a box in Chrome but is not drawn (the Connect page's
  // "Use an API key instead" was reported as under the bottom bar on 2026-09-29).
  const shown = (el) => { if (el.checkVisibility && !el.checkVisibility()) return false; const s = getComputedStyle(el); if (s.visibility === 'hidden' || s.display === 'none') return false; const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
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
      await gotoRetry(page, `${BASE}/houses/new?lat=12.9716&lon=77.5946`); await settle(page);
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
      await gotoRetry(page, BASE + route);
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
      await errors.settle();
      check('console', `${tag} (mobile): no errors`, errors.length === 0, errors.slice(0, 3).join(' ; '));
      if (lang === 'en' || route === '/') {
        const file = `mobile_${phone.name}_${theme}_${lang}_${route.replace(/[^a-z]+/gi, '_') || 'root'}`.slice(0, 80);
        await page.screenshot({ path: path.join(OUT, 'shots', `${file}.png`) });
      }
    }
    await ctx.close();
  }
}

/**
 * The path trace and the place check, through the UI only (docs/11 5.27.8, 5.27.13): no IndexedDB seeding, so the test does not
 * know the store's names. 30 steps of 22 m north along one street, with the geolocation moved by the test.
 */
async function trace(browser) {
  const START = { latitude: 12.9716, longitude: 77.5946, accuracy: 10 };
  const steps = walkSteps(START.latitude, START.longitude, 30, 22);
  const street = steps[15];
  const walkAndAsk = async (ctx, page, tag) => {
    const errors = watch(page);
    await gotoRetry(page, `${BASE}/`); await settle(page); await page.waitForTimeout(1500);
    // The card is a <details>: open on a desktop, one line on a phone.
    const details = page.locator('app-trace-card details');
    if (!(await details.evaluate((d) => d.open))) await details.locator('summary').click();
    // The trace is off by default, and nothing asks for the location until Start a walk.
    check('trace', `${tag}: the trace switch is off by default and Start a walk is not offered yet`, !(await page.locator('#trace-on').isChecked()) && (await page.locator('#trace-start').count()) === 0);
    await page.locator('#trace-on').check();
    // Angular renders the card's next state a moment after the click: wait for it instead of reading it at once.
    await page.locator('#trace-start').waitFor({ timeout: 5000 }).catch(() => {});
    check('trace', `${tag}: Start a walk and its permission sentence appear`, await page.locator('#trace-start').isVisible() && /your browser will ask for your location/.test(await page.locator('#trace-explain').innerText()));
    await page.locator('#trace-start').click();
    await page.locator('#trace-finish').waitFor({ timeout: 10000 }).catch(() => {});
    check('trace', `${tag}: recording shows Finish walk and the state`, await page.locator('#trace-finish').isVisible() && /Recording your walk/.test(await page.locator('app-trace-card').innerText()));
    for (const step of steps) {
      await ctx.setGeolocation({ ...step, accuracy: 10 });
      await page.waitForTimeout(250);
    }
    await page.waitForTimeout(500);
    const kept = /Points kept: (\d+)/.exec(await page.locator('app-trace-card').innerText());
    check('trace', `${tag}: the points were kept (at least 25 of 30)`, !!kept && +kept[1] >= 25, kept && kept[0]);
    await page.locator('#trace-finish').click();
    await page.locator('dialog.walk-end[open]').waitFor({ timeout: 10000 }).catch(() => {});
    const sheet = page.locator('dialog.walk-end');
    check('trace', `${tag}: Save this walk? opens when the walk ends, with the title focused`, (await sheet.getAttribute('open')) !== null && (await page.evaluate(() => document.activeElement && document.activeElement.id)) === 'walk-end-title');
    check('trace', `${tag}: Keep for 30 days is the primary answer`, /btn-primary/.test((await page.locator('#walk-end-keep').getAttribute('class')) || ''));
    await page.locator('#walk-end-keep').click();
    await page.waitForTimeout(500);
    check('trace', `${tag}: the sheet closes on Keep for 30 days`, (await sheet.getAttribute('open')) === null);
    check('trace', `${tag}: the legend shows Walked once while the trace is not empty`, /Walked once/.test(await page.locator('.legend').innerText()));
    // The check: here, on the street.
    const requests = [];
    page.on('request', (r) => requests.push(r.url()));
    await ctx.setGeolocation({ ...street, accuracy: 10 });
    await page.locator('#place-check-open').click();
    check('trace', `${tag}: the dialog says the permission sentence`, /It is used once, only on this page, and not kept/.test(await page.locator('#check-permission').innerText()));
    requests.length = 0;
    await page.locator('#check-here').click();
    await page.locator('#check-headline').waitFor({ timeout: 20000 }).catch(() => {});
    const onStreet = await page.locator('#check-headline').innerText().catch(() => '');
    check('trace', `${tag}: on the street the answer says within, and today's weekday`, /You walked within \d+ m of here on/.test(onStreet) && onStreet.includes(todayWeekday()), onStreet);
    check('trace', `${tag}: the answer has the title focused`, (await page.evaluate(() => document.activeElement && document.activeElement.id)) === 'check-title');
    const leaked = nonTileRequests(requests, BASE);
    check('trace', `${tag}: the click made no request but the map's`, leaked.length === 0, leaked.slice(0, 3).join(' ; '));
    // 100 m off the street.
    await ctx.setGeolocation({ ...eastOf(street, 100), accuracy: 10 });
    requests.length = 0;
    await page.locator('#check-again').click();
    await page.waitForFunction(() => /No walk of yours passed/.test((document.querySelector('#check-headline') || {}).textContent || ''), null, { timeout: 20000 }).catch(() => {});
    const off = await page.locator('#check-headline').innerText().catch(() => '');
    check('trace', `${tag}: 100 m off the street the answer says no walk passed within 25 m`, /No walk of yours passed within 25 m of here/.test(off), off);
    const leakedAgain = nonTileRequests(requests, BASE);
    check('trace', `${tag}: Check again made no request but the map's`, leakedAgain.length === 0, leakedAgain.slice(0, 3).join(' ; '));
    if (tag.includes('360')) await page.screenshot({ path: path.join(OUT, 'shots', 'trace_check_360x640.png') });
    const live = () => page.evaluate(() => [...document.querySelectorAll('[aria-live]')].map((e) => e.textContent).join(' '));
    await page.waitForTimeout(500);
    check('trace', `${tag}: the answer was said through the page's live region`, /No walk of yours passed|You walked within/.test(await live()), await live());
    await page.locator('#check-close').click();
    await page.waitForTimeout(500);
    check('trace', `${tag}: Close withdraws the sentence from the live region`, !/No walk of yours passed|You walked within/.test(await live()), await live());
    check('trace', `${tag}: Close returns focus to Have I been here?`, (await page.evaluate(() => document.activeElement && document.activeElement.id)) === 'place-check-open');
    check('trace', `${tag}: no result in the URL or in the history state`, !/walked|within|check/i.test(page.url()) && (await page.evaluate(() => JSON.stringify(history.state || {}))).indexOf('within') < 0);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check('trace', `${tag}: no horizontal scroll`, overflow <= 1, `${overflow}px`);
    check('console', `${tag}: no errors`, errors.length === 0, errors.slice(0, 3).join(' ; '));
  };
  for (const [tag, viewport] of [['trace desktop', VIEWPORTS.desktop], ['trace 360x640', { width: 360, height: 640 }]]) {
    const ctx = await newCtx(browser, { viewport, geolocation: START });
    const page = await ctx.newPage();
    try { await walkAndAsk(ctx, page, tag); } catch (e) { check('trace', `${tag} ran to the end`, false, e.stack); }
    await ctx.close();
  }
}

/**
 * The guided tour (S4b-FR-38), at 360 px: the first-visit offer, the steps, the card never covering what it
 * points at, no horizontal scroll, one offer only, and the replay from Your data.
 */
async function tour(browser) {
  const ctx = await newCtx(browser, { viewport: { width: 360, height: 740 } });
  const page = await ctx.newPage();
  const errors = watch(page);
  try {
    await gotoRetry(page, `${BASE}/`); await settle(page);
    await page.locator('#tour-offer-start').waitFor({ timeout: 8000 }).catch(() => {});
    check('tour', 'the offer shows on the first visit', await page.locator('#tour-offer-start').isVisible());
    await page.locator('#tour-offer-start').click();
    let covered = 0; let overflow = 0; let seen = 0; const bad = [];
    for (let i = 0; i < 40; i++) {
      if (i > 0 && (await page.locator('#tour-title').count()) === 0) break;
      await page.locator('#tour-title').waitFor({ timeout: 4000 });
      await page.waitForTimeout(900); // the page and its target settle
      const g = await page.evaluate(() => {
        const c = document.querySelector('[role="dialog"]').getBoundingClientRect(); const s = document.querySelector('.spot')?.getBoundingClientRect();
        return { c: [c.top | 0, c.bottom | 0], s: s ? [s.top | 0, s.bottom | 0] : null, nav: document.querySelector('nav.nav').getBoundingClientRect().top | 0, overlap: !!s && c.top < s.bottom && c.bottom > s.top, wide: document.documentElement.scrollWidth - document.documentElement.clientWidth, onScreen: c.top >= 0 && c.bottom <= innerHeight };
      });
      seen++; if (g.overlap || !g.onScreen) { covered++; bad.push(`${i + 1}:${JSON.stringify(g)}`); } if (g.wide > 1) overflow++;
      await page.locator('#tour-next').click();
    }
    check('tour', 'every step showed (at least 15) and the last one finished the tour', seen >= 15 && seen <= 30, `${seen} steps`);
    check('tour', 'the card never covers its highlight and stays on screen', covered === 0, `${covered} step(s): ${bad.join(' ')}`);
    check('tour', 'no horizontal scroll in any step', overflow === 0, `${overflow} step(s)`);
    await page.waitForTimeout(300);
    check('tour', 'Finish closes the tour', (await page.locator('#tour-title').count()) === 0);
    await gotoRetry(page, `${BASE}/`); await settle(page); await page.waitForTimeout(1000);
    check('tour', 'the offer does not come back after the tour', (await page.locator('#tour-offer-start').count()) === 0);
    await gotoRetry(page, `${BASE}/data`); await settle(page);
    await page.locator('#tour-replay').click();
    await page.locator('#tour-title').waitFor({ timeout: 4000 });
    check('tour', 'Take the tour on Your data starts it again', /Welcome/.test(await page.locator('#tour-title').innerText()));
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    check('tour', 'Escape leaves the tour', (await page.locator('#tour-title').count()) === 0);
    check('console', 'tour: no errors', errors.length === 0, errors.slice(0, 3).join(' ; '));
  } catch (e) { check('tour', 'the tour scenario ran to the end', false, e.stack); }
  await ctx.close();
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
    await gotoRetry(page, `${BASE}/`); await settle(page); await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(OUT, 'shots', `map_${name}.png`) });
    await page.close();
  }
  await ctx.close();
}

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined, args: ['--enable-unsafe-swiftshader', '--use-angle=swiftshader', '--ignore-gpu-blocklist'] });
  const t0 = Date.now();
  const only = (process.env.ONLY || '').split(',').filter(Boolean);
  // The four areas run side by side, each in its own browser contexts (about 12 minutes instead of 25-30 one after
  // another); SERIAL=1 runs them in turn, as before.
  // `--trace` (or ONLY=trace) runs the path trace scenario alone; the default matrix does not include it.
  const areas = [['pages', pageMatrix], ['flows', flows], ['boundaries', boundaries], ['mobile', mobile], ['tour', tour], ['trace', trace]]
    .filter(([name]) => (only.length ? only.includes(name) : WANT_TRACE ? name === 'trace' : name !== 'trace'));
  const run = async ([name, fn]) => {
    try { await fn(browser); } catch (e) { check(name, `${name} ran to the end`, false, e.stack); }
  };
  if (process.env.SERIAL) for (const area of areas) await run(area);
  else await Promise.all(areas.map(run));
  await browser.close();
  const byArea = {};
  for (const r of results) { byArea[r.area] ??= { pass: 0, fail: 0 }; byArea[r.area][r.ok ? 'pass' : 'fail']++; }
  fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ base: BASE, at: new Date().toISOString(), seconds: Math.round((Date.now() - t0) / 1000), byArea, transient, results }, null, 1));
  console.log(JSON.stringify(byArea), `${Math.round((Date.now() - t0) / 1000)} s`, transient.length ? `; ${transient.length} network fault(s) not counted, see results.json "transient"` : '');
  process.exitCode = results.some((r) => !r.ok) ? 1 : 0;
})();
