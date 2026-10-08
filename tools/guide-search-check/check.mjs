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

// MANUAL check of the user guide's site search, in a real browser, for English, Hindi, Tamil and Telugu
// (docs/06 TC-M-65; run it after a change under guide/, and against the live site after a deploy).
//
// What it does, per language site: types real queries into the search box of Chromium and reads the result list.
//   1. every page is found by its own title, in the top 3 result groups (Home included);
//   2. every 3rd section heading (at most 14) finds its section's page;
//   3. single words typed alone find their page (the Hindi, Tamil and Telugu words of the bug of 2026-10-08);
//   4. a nonsense query shows the language's own "no matching documents" message, an empty box its own
//      placeholder (both read from the page's __config translations, not typed here);
//   5. a result link lands on the page with ?h= and highlighted <mark>s; "/" focuses the box; Escape leaves it.
// Steps 1 to 3 run twice: "keys" (every query ends with a key press, as a keyboard does) and "input" (the text
// arrives with input events only and no key-up, as with a paste from the context menu, voice typing or a tapped
// suggestion: Material reads the box on key-up and focus only, so the guide adds guide/docs/javascripts/search-input.js).
// Playwright types Indic characters as "input" only, which is why this check names both.
// Expected values come from the site's own search_index.json (its page titles and headings), never from the code
// that builds the index.
//
// Run (Playwright is the one tools/live-ui installs; PLAYWRIGHT_BROWSERS_PATH is honoured; nothing is downloaded here):
//   cd tools/live-ui && npm ci && npx playwright install chromium    # once
//   # a local build, as pages.yml makes it, served on 127.0.0.1:
//   (cd guide && mkdocs build --strict --site-dir /tmp/guide-site) && python3 -m http.server 8000 -d /tmp/guide-site &
//   node tools/guide-search-check/check.mjs http://127.0.0.1:8000/
//   # or the live site, after the deploy (default when no address is given is the local one above):
//   node tools/guide-search-check/check.mjs https://sriram-codes-sw.github.io/doorprints/
// Options: --lang=hi,ta (only these), --typing=keys|input|both (default both), or --typing=type (Playwright's own
// pressSequentially alone: a key-up only for Latin characters, which is how the first live check of 2026-10-08 typed).
// Expected on a good build: "0 problem(s)", exit 0. Any problem is printed as PROBLEM [lang/typing] what: detail.

import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
// PLAYWRIGHT_MODULE: another checkout's playwright/index.mjs (a git worktree has no node_modules of its own).
const { chromium } = await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE
  ?? path.join(here, '..', 'live-ui', 'node_modules', 'playwright', 'index.mjs')).href);

const args = process.argv.slice(2);
const opt = (name, dflt) => (args.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${dflt}`).split('=')[1];
const BASE = ((args.find((a) => !a.startsWith('--')) ?? 'http://127.0.0.1:8000/').replace(/\/?$/, '/'));
const ALL = [['en', ''], ['hi', 'hi/'], ['ta', 'ta/'], ['te', 'te/']];
const LANGS = ALL.filter(([l]) => opt('lang', 'en,hi,ta,te').split(',').includes(l));
const TYPINGS = opt('typing', 'both') === 'both' ? ['keys', 'input'] : [opt('typing', 'both')];

// Single words typed alone (the bug report's): each must find a page. 'server' etc. are the guide's own words.
const LONE_WORDS = {
  en: ['server', 'privacy', 'backup'],
  hi: ['सर्वर', 'गोपनीयता', 'वेबसाइट', 'बैकअप'],
  ta: ['சர்வர்', 'முகப்பு', 'தனியுரிமை'],
  te: ['సర్వర్', 'హోమ్', 'గోప్యత'],
};

const problems = [];
const note = (tag, what, detail) => { problems.push(1); console.log(`  PROBLEM [${tag}] ${what}: ${detail}`); };
const strip = (s) => s.replace(/<[^>]+>/g, ' ').replace(/&[a-z]+;/g, ' ').replace(/\s+/g, ' ').trim();

async function search(page, query, typing) {
  const input = page.locator('input[name="query"]');
  const icon = page.locator('label[for="__search"]').first();
  if (await icon.isVisible()) await icon.click(); // a narrow screen shows an icon; the viewport here shows the box
  await input.click();
  await input.fill('');
  if (typing === 'input') await page.keyboard.insertText(query);
  else {
    await input.pressSequentially(query); // a key-up for the characters a US keyboard has, input only for the others
    if (typing === 'keys') await page.keyboard.press('ArrowRight');
  }
  await page.waitForTimeout(500);
  const meta = (await page.locator('.md-search-result__meta').first().innerText().catch(() => '')).trim();
  // One entry per result group (a page and its sections), in rank order, each with the links it holds.
  const groups = await page.locator('li.md-search-result__item').evaluateAll(
    (lis) => lis.map((li) => [...li.querySelectorAll('a.md-search-result__link')].map((a) => a.getAttribute('href'))));
  return { meta, groups };
}

const rankOf = (groups, pagePath) =>
  groups.findIndex((links) => links.some((h) => h && new URL(h, 'http://x/').pathname.endsWith(pagePath)));

const browser = await chromium.launch();
let queries = 0;
for (const [lang, prefix] of LANGS) {
  console.log(`\n== ${lang}`);
  const idx = await (await fetch(`${BASE}${prefix}search/search_index.json`)).json();
  const pages = idx.docs.filter((d) => !d.location.includes('#'));
  const sections = idx.docs.filter((d) => d.location.includes('#'));
  console.log(`  index: ${pages.length} pages, ${sections.length} sections, config lang=${JSON.stringify(idx.config.lang)}`);
  const ctx = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  const page = await ctx.newPage();
  await page.goto(`${BASE}${prefix}`, { waitUntil: 'networkidle' });
  const tr = await page.evaluate(() => JSON.parse(document.getElementById('__config').textContent).translations);

  for (const typing of TYPINGS) {
    const tag = `${lang}/${typing}`;

    // 1. each page by its title, top 3
    for (const d of pages) {
      const q = strip(d.title);
      queries++;
      const { groups } = await search(page, q, typing);
      const want = d.location.replace(/#.*/, '');
      const rank = rankOf(groups, want);
      if (rank < 0) note(tag, 'page not found by its title', `"${q}" -> ${want}; got ${groups.length} group(s)`);
      else if (rank > 2) note(tag, 'page found by its title but not in the top 3', `"${q}" -> ${want} at #${rank + 1}`);
    }

    // 2. a sample of section headings
    for (const d of sections.filter((_, i) => i % 3 === 0).slice(0, 14)) {
      const q = strip(d.title);
      if (q.length < 3) continue;
      queries++;
      const { groups } = await search(page, q, typing);
      if (rankOf(groups, d.location.replace(/#.*/, '')) < 0) note(tag, 'section not found by its heading', `"${q}" -> ${d.location}`);
    }

    // 3. single words alone: results, and (when a page title holds the word) one of those pages among them
    for (const word of LONE_WORDS[lang]) {
      queries++;
      const { meta, groups } = await search(page, word, typing);
      if (!groups.length) { note(tag, 'single word typed alone finds nothing', `"${word}" -> "${meta}"`); continue; }
      const titled = pages.filter((d) => strip(d.title).includes(word)).map((d) => d.location);
      if (titled.length && !titled.some((p) => rankOf(groups, p) >= 0)) note(tag, 'single word did not find the page titled with it', `"${word}" -> ${titled.join(', ')}`);
    }
  }

  // 4. nonsense, and the empty box
  const none = await search(page, 'zzqxjv', 'keys');
  if (none.groups.length) note(lang, 'nonsense query returned results', JSON.stringify(none.groups[0]));
  else if (none.meta !== tr['search.result.none']) note(lang, 'no-results message is not the language\'s own', `${JSON.stringify(none.meta)} != ${JSON.stringify(tr['search.result.none'])}`);
  await page.locator('input[name="query"]').fill('');
  await page.waitForTimeout(300);
  const empty = (await page.locator('.md-search-result__meta').first().innerText().catch(() => '')).trim();
  if (empty !== tr['search.result.placeholder']) note(lang, 'empty box does not show the placeholder', `${JSON.stringify(empty)} != ${JSON.stringify(tr['search.result.placeholder'])}`);

  // 5. a result lands highlighted
  const r = await search(page, strip(pages[Math.min(3, pages.length - 1)].title), 'keys');
  if (!r.groups.length) note(lang, 'no result to click', '');
  else {
    await page.locator('a.md-search-result__link').first().click();
    await page.waitForLoadState('domcontentloaded');
    await page.waitForTimeout(600);
    if (!/[?&]h=/.test(page.url())) note(lang, 'result link has no highlight parameter', page.url());
    if ((await page.locator('mark').count()) === 0) note(lang, 'no highlighted terms on the landing page', page.url());
  }

  // keyboard: "/" focuses the box, Escape leaves it
  await page.goto(`${BASE}${prefix}`, { waitUntil: 'networkidle' });
  await page.keyboard.press('/');
  await page.waitForTimeout(300);
  const focused = await page.evaluate(() => document.activeElement?.getAttribute('name'));
  if (focused !== 'query') note(lang, '"/" does not focus the search box', `active element: ${focused}`);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  if (await page.locator('input[name="query"]').evaluate((el) => el === document.activeElement)) note(lang, 'Escape does not leave the search box', '');
  await ctx.close();
}
await browser.close();
console.log(`\n${queries} queries; ${problems.length} problem(s).`);
process.exit(problems.length ? 1 : 0);
