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

// What the live UI test's mobile pass (live-ui.js) and the pre-merge layout gate (layout-gate.js, docs/06 TC-S-46) share:
// the routes, the phone browser context, and the in-page layout audit (sideways scroll, map controls against the page's
// own controls, the map and the app against the screen). One definition, so the gate checks what the live test checks.

/** The routes both scripts visit; the 8th is the house form, which a caller may point at a place of its own. */
const ROUTES = ['/', '/compare', '/data', '/connect', '/ask', '/plan', '/share', '/houses/new?lat=12.9716&lon=77.5946', '/does-not-exist'];

/**
 * A browser context for a phone: Playwright's device profile (touch, isMobile, device pixel ratio, mobile user agent) with
 * the viewport (and screen) replaced when one is given, the language stored before the app starts, and the root font size
 * set to [text] percent once the document is parsed (Chrome's and the OS text size scale everything set in rem).
 */
async function phoneContext(browser, devices, { device, viewport: own, theme = 'light', lang = 'en', text }) {
  const { defaultBrowserType, ...profile } = devices[device];
  const viewport = own || profile.viewport;
  const ctx = await browser.newContext({ ...profile, viewport, screen: viewport, colorScheme: theme, serviceWorkers: 'block' });
  await ctx.addInitScript(([lang, text]) => {
    try {
      if (!sessionStorage.getItem('__init')) {
        sessionStorage.setItem('__init', '1');
        localStorage.setItem('doorprints.lang', lang);
      }
    } catch { /* storage blocked: the app starts in its default language */ }
    if (text) document.addEventListener('DOMContentLoaded', () => { document.documentElement.style.fontSize = `${text}%`; });
  }, [lang, text]);
  return { ctx, viewport };
}

/** Runs in the page: layout findings that need no phone-specific rule, as a list (empty when all is well). */
function layoutAudit() {
  const out = [];
  // A page wider than the phone's screen widens the layout viewport (innerWidth) and is shown zoomed out, so the
  // screen width is the measure (the context's screen is its viewport).
  const W = Math.min(window.innerWidth, screen.width), H = window.innerHeight;
  if (window.innerWidth > screen.width + 1) out.push(`page laid out ${window.innerWidth}px wide on a ${screen.width}px screen (zoomed out)`);
  const main = document.querySelector('main');
  const shown = (el) => { if (el.checkVisibility && !el.checkVisibility()) return false; const s = getComputedStyle(el); if (s.visibility === 'hidden' || s.display === 'none') return false; const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const hidden = (el) => el.closest('.sr-only, [aria-hidden="true"], .skip-link') !== null;
  const name = (el) => { const t = (el.getAttribute('aria-label') || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 30); return `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${el.classList.length ? '.' + [...el.classList].slice(0, 2).join('.') : ''}${t ? ` "${t}"` : ''}`; };
  // Sideways scroll: the document, and <main> (the app's page scroller, which the document check does not see).
  const doc = document.scrollingElement.scrollWidth - W;
  if (doc > 1) out.push(`page scrolls sideways by ${doc}px`);
  if (main && main.scrollWidth - main.clientWidth > 1) out.push(`main scrolls sideways by ${main.scrollWidth - main.clientWidth}px`);
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
  // The map fits the screen and its canvas matches its box. A runaway layout once made the map 22,000px tall (and its canvas
  // 4,096px, the texture limit) on phones under 700px tall, with the page itself still the right size (2026-10-12).
  const mapEl = document.querySelector('.maplibregl-map');
  if (mapEl && shown(mapEl)) {
    const mh = mapEl.getBoundingClientRect().height;
    if (mh > H + 1) out.push(`the map is taller than the screen: ${Math.round(mh)}px of ${H}px`);
    const cv = mapEl.querySelector('canvas');
    if (cv && cv.height >= 4096) out.push(`the map canvas is at the texture limit (${cv.width}x${cv.height})`);
  }
  // The app fits the screen: header, banners and page no taller than the viewport.
  const root = document.querySelector('app-root');
  if (root && root.getBoundingClientRect().height > H + 1) out.push(`app taller than the screen: ${Math.round(root.getBoundingClientRect().height)}px of ${H}px`);
  return out;
}

module.exports = { ROUTES, phoneContext, layoutAudit };
