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

// The user guide's site search, checked on the BUILT site without a browser (docs/06 TC-U-175; pages.yml and
// tools/check.sh run it after `mkdocs build --strict`):
//
//   node tools/guide-search-check/site-check.mjs <built site directory>
//
// For the English site and each translation (<site>/hi, ta, te) it checks:
//   1. config: the search index's `lang` is the expected one (hi: "hi"; en, ta and te: "en", because Material's
//      search worker loads wordcut.js, which lunr.ta and lunr.te need, for Hindi and Thai only, so lang "ta" or "te"
//      throws in the worker and kills the search: docs/10 S4b-BL-183), and the `separator` splits on neither a letter
//      nor a combining mark (virama, vowel signs, ZWJ, ZWNJ) of Devanagari, Tamil or Telugu, so a word stays whole.
//   2. content: the index holds a page for every link of the built navigation, titled as the navigation or the page's
//      own <h1> titles it, and an entry for every <h1>-<h3> of every built page, titled as the page prints it (the
//      plugin folds a page's first <h1> into the page's own entry). Both lists are read from the built HTML.
//   3. behaviour: Material's own search worker (the built site's assets/javascripts/workers/search.*.min.js, run here
//      in node:vm with the site's index) finds every page by its title in the top 3 pages, every heading's entry,
//      the single words of the bug of 2026-10-08 typed alone, and nothing for "zzqxjv".
//   4. input: the page loads javascripts/search-input.js, and that script makes the search box answer an `input`
//      event the way Material wants a key-up (Material reads the box on key-up and focus only, so a paste, voice
//      typing or a tapped suggestion searched nothing).
// What it cannot see: the real DOM, the result panel, highlight and keyboard behaviour: tools/guide-search-check/check.mjs
// (a browser; manual, docs/06 TC-M-65) does.
// Exit 0 when all hold, 1 and a list of problems when not.

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const site = process.argv[2];
if (!site || !fs.existsSync(path.join(site, 'search', 'search_index.json'))) {
  console.error('usage: node tools/guide-search-check/site-check.mjs <built site directory (mkdocs build --site-dir)>');
  process.exit(2);
}

// Spec, not computed: the language of the index per site, and the words the 2026-10-08 report typed alone.
const EXPECTED_LANG = { en: ['en'], hi: ['hi'], ta: ['en'], te: ['en'] };
// None is a stop word of its language (those are dropped from the index by design); each must still be in the guide.
const LONE_WORDS = {
  en: ['server', 'privacy', 'backup', 'houses'],
  hi: ['सर्वर', 'गोपनीयता', 'वेबसाइट', 'बैकअप', 'मकान'],
  ta: ['சர்வர்', 'முகப்பு', 'தனியுரிமை', 'வீடுகள்'],
  te: ['సర్వర్', 'హోమ్', 'గోప్యత', 'ఇళ్లు'],
};
const SITES = [['en', ''], ['hi', 'hi'], ['ta', 'ta'], ['te', 'te']];

const problems = [];
const bad = (lang, what, detail) => problems.push(`[${lang}] ${what}: ${detail}`);

const decode = (s) => s
  .replace(/<[^>]+>/g, '')
  .replace(/&para;/g, '')
  .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(+n))
  .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&amp;/g, '&')
  .replace(/\s+/g, ' ').trim();

// The built navigation: [href, title] of every page link, from index.html's sidebar.
function navPages(dir) {
  const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
  const pages = new Map();
  for (const m of html.matchAll(/<a href="([^"#]+\.html)" class="md-nav__link[^"]*"[^>]*>([\s\S]*?)<\/a>/g)) {
    if (!pages.has(m[1])) pages.set(m[1], decode(m[2]));
  }
  return { html, pages };
}

// Every <h1>-<h3> with an id of a built page: [location, title].
function headings(dir, file) {
  const html = fs.readFileSync(path.join(dir, file), 'utf8');
  const article = html.slice(html.indexOf('<article'), html.indexOf('</article>'));
  return [...article.matchAll(/<h([1-3]) id="([^"]+)"[^>]*>([\s\S]*?)<\/h\1>/g)].map((m) => [`${file}#${m[2]}`, decode(m[3])]);
}

// Material's search worker, run as the browser runs it, over the site's own index.
async function loadWorker(dir, index) {
  const js = path.join(dir, 'assets', 'javascripts');
  const file = fs.readdirSync(path.join(js, 'workers')).find((f) => /^search\..*\.min\.js$/.test(f));
  if (!file) throw new Error(`no search worker under ${js}/workers`);
  let onmessage; let reply;
  const ctx = { console };
  ctx.self = ctx;
  ctx.importScripts = (...urls) => urls.forEach((u) => {
    const f = path.resolve(js, 'workers', u);
    vm.runInContext(fs.readFileSync(f, 'utf8'), ctx, { filename: f });
  });
  ctx.addEventListener = (type, fn) => { if (type === 'message') onmessage = fn; };
  ctx.postMessage = (m) => reply(m);
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(path.join(js, 'workers', file), 'utf8'), ctx, { filename: file });
  // the worker's listener is async: a script that throws in importScripts rejects its promise, so catch that here
  const send = (msg) => new Promise((resolve, reject) => { reply = resolve; Promise.resolve(onmessage({ data: msg })).catch(reject); });
  // Message types are Material's (SearchMessageType): SETUP 0, READY 1, QUERY 2, RESULT 3.
  const ready = await send({ type: 0, data: { config: index.config, docs: index.docs, options: { suggest: false } } });
  if (ready.type !== 1) throw new Error(`the search worker did not become ready: ${JSON.stringify(ready)}`);
  return async (q) => (await send({ type: 2, data: q })).data.items
    .map((group) => ({ page: (group[0].parent ?? group[0]).location, sections: group.map((i) => i.location) }));
}

for (const [lang, sub] of SITES) {
  const dir = path.join(site, sub);
  const index = JSON.parse(fs.readFileSync(path.join(dir, 'search', 'search_index.json'), 'utf8'));

  // 1. config
  if (JSON.stringify(index.config.lang) !== JSON.stringify(EXPECTED_LANG[lang])) {
    bad(lang, 'search index lang', `${JSON.stringify(index.config.lang)}, expected ${JSON.stringify(EXPECTED_LANG[lang])}`);
  }
  const sep = new RegExp(index.config.separator, 'u');
  const split = [];
  for (const [from, to] of [[0x0900, 0x097f], [0x0b80, 0x0bff], [0x0c00, 0x0c7f], [0x200c, 0x200d]]) {
    for (let cp = from; cp <= to; cp++) {
      const ch = String.fromCodePoint(cp);
      if (/^[\p{L}\p{M}\p{N}‌‍]$/u.test(ch) && sep.test(ch)) split.push(`U+${cp.toString(16).toUpperCase()}`);
    }
  }
  if (split.length) bad(lang, 'search separator splits inside words', `${index.config.separator} matches ${split.slice(0, 6).join(' ')}`);

  // 2. content, expected from the built HTML
  const { html: indexHtml, pages } = navPages(dir);
  const byLocation = new Map(index.docs.map((d) => [d.location, d.title]));
  if (pages.size === 0) bad(lang, 'built navigation', 'no page links found in index.html');
  const sections = [];
  for (const [href, navTitle] of pages) {
    const hs = headings(dir, href);
    const own = byLocation.get(href);
    if (own === undefined) bad(lang, 'page missing from the index', href);
    else if (own !== navTitle && own !== hs[0]?.[1]) bad(lang, 'page titled neither as the navigation nor as its <h1>', `${href}: nav "${navTitle}", h1 "${hs[0]?.[1]}", index ${JSON.stringify(own)}`);
    for (const [loc, h] of hs) {
      // the first <h1> may be folded into the page's own entry
      const title = byLocation.has(loc) ? byLocation.get(loc) : (h === hs[0][1] ? own : undefined);
      sections.push([loc, h]);
      if (title !== h) bad(lang, 'heading missing from the index or titled differently', `${loc}: page "${h}", index ${JSON.stringify(title)}`);
    }
  }
  const stray = index.docs.filter((d) => !d.location.includes('#') && !pages.has(d.location));
  if (stray.length) bad(lang, 'index has pages the navigation does not', stray.map((d) => d.location).join(', '));
  const guideText = index.docs.map((d) => `${d.title} ${d.text}`).join(' ').toLowerCase();

  // 3. behaviour of Material's own worker
  let query;
  try {
    query = await loadWorker(dir, index);
  } catch (err) {
    bad(lang, 'the search worker fails to start', String(err.message ?? err).split('\n')[0]);
    continue;
  }
  for (const href of pages.keys()) {
    const title = byLocation.get(href);
    const rank = (await query(title)).findIndex((g) => g.page === href);
    if (rank < 0 || rank > 2) bad(lang, 'page not in the top 3 for its own title', `"${title}" -> ${href} at ${rank < 0 ? 'none' : '#' + (rank + 1)}`);
  }
  for (const [loc, h] of sections) {
    const [page] = loc.split('#');
    const entry = byLocation.has(loc) ? loc : page;
    if (!(await query(h)).some((g) => g.page === page && g.sections.includes(entry))) bad(lang, 'section not found by its heading', `"${h}" -> ${loc}`);
  }
  for (const word of LONE_WORDS[lang]) {
    if (!guideText.includes(word)) { bad(lang, 'a word of the check is no longer in the guide', `"${word}"`); continue; }
    if ((await query(word)).length === 0) bad(lang, 'single word typed alone finds nothing', `"${word}"`);
  }
  if ((await query('zzqxjv')).length !== 0) bad(lang, 'nonsense query returns results', 'zzqxjv');

  // 4. the input bridge
  const boxMarkup = /<input[^>]*data-md-component="search-query"/.test(indexHtml);
  if (!boxMarkup) bad(lang, 'search box markup', 'no <input data-md-component="search-query"> in index.html (Material changed it?)');
  const scriptTag = indexHtml.match(/<script src="([^"]*javascripts\/search-input\.js)"/);
  if (!scriptTag) { bad(lang, 'search-input.js is not loaded by the page', 'no <script src=".../javascripts/search-input.js">'); continue; }
  const scriptFile = path.join(dir, 'javascripts', 'search-input.js');
  if (!fs.existsSync(scriptFile)) { bad(lang, 'search-input.js is not in the site', scriptFile); continue; }
  const box = new EventTarget();
  const keyups = [];
  box.addEventListener('keyup', () => keyups.push('keyup'));
  const inputs = [];
  box.addEventListener('input', () => inputs.push('input'));
  const selectors = [];
  const sandbox = { Event, document: { querySelector: (s) => { selectors.push(s); return box; } } };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(scriptFile, 'utf8'), sandbox, { filename: scriptFile });
  if (!selectors.some((s) => s.includes('data-md-component="search-query"'))) bad(lang, 'search-input.js does not look for the search box', JSON.stringify(selectors));
  box.dispatchEvent(new Event('input'));
  if (keyups.length !== 1) bad(lang, 'an input event does not become one key-up', `${keyups.length} key-up(s)`);
  if (inputs.length !== 1) bad(lang, 'search-input.js feeds back into input events', `${inputs.length} input event(s)`);
}

for (const p of problems) console.log(`PROBLEM ${p}`);
console.log(`${SITES.length} sites checked, ${problems.length} problem(s).`);
process.exit(problems.length ? 1 : 0);
