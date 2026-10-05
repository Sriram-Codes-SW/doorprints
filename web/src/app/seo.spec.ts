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

import { describe, expect, it } from 'vitest';
import indexHtml from '../index.html' with { loader: 'text' };
import aboutHtml from '../../public/about.html' with { loader: 'text' };
import privacyHtml from '../../public/privacy.html' with { loader: 'text' };
import robotsTxt from '../../public/robots.txt' with { loader: 'text' };
import sitemapXml from '../../public/sitemap.xml' with { loader: 'text' };
import { IMPORT_ROUTE } from './core/launch-files.service';
import manifestText from '../../public/manifest.webmanifest' with { loader: 'text' };
import firebaseJson from '../../firebase.json';
import { routes } from './app.routes';
import { en } from './i18n/en';
import { hi } from './i18n/hi';
import { ta } from './i18n/ta';
import { te } from './i18n/te';
import { SITE_URL } from './i18n/i18n-title.strategy';

/**
 * The search-engine invariants of the public pages (Wave E): what a crawler that does not run scripts reads in
 * index.html, about.html and privacy.html, robots.txt, sitemap.xml and the manifest, and which routes the app keeps out of the index.
 * Read from the repository files themselves, so a change that breaks one fails here and not in Search Console.
 */
const ORIGIN = 'https://doorprints.web.app';

function parse(html: string): Document {
  return new DOMParser().parseFromString(html, 'text/html');
}
const meta = (doc: Document, selector: string): string | null | undefined =>
  doc.head.querySelector(`meta[${selector}]`)?.getAttribute('content');

const pages = { 'index.html': parse(indexHtml), 'about.html': parse(aboutHtml), 'privacy.html': parse(privacyHtml) };

describe.each(Object.entries(pages))('%s as a search result and a link preview', (name, doc) => {
  const url = name === 'index.html' ? `${ORIGIN}/` : `${ORIGIN}/${name}`;

  it('has exactly one non-empty title, short enough to show', () => {
    expect(doc.querySelectorAll('title')).toHaveLength(1);
    expect(doc.title.trim().length).toBeGreaterThan(0);
    expect(doc.title.length).toBeLessThanOrEqual(70);
  });

  it('has a meta description of 50 to 160 characters', () => {
    const description = meta(doc, 'name="description"')!;
    expect(description.length).toBeGreaterThanOrEqual(50);
    expect(description.length).toBeLessThanOrEqual(160);
  });

  it('has one absolute https canonical link on the doorprints.web.app domain', () => {
    const canonical = doc.head.querySelectorAll('link[rel="canonical"]');
    expect(canonical).toHaveLength(1);
    expect(canonical[0].getAttribute('href')).toBe(url);
  });

  it('carries the Search Console ownership tag on the landing page only', () => {
    const tag = doc.head.querySelectorAll('meta[name="google-site-verification"]');
    expect(tag).toHaveLength(name === 'index.html' ? 1 : 0);
  });

  it('has Open Graph and Twitter card tags with absolute https URLs', () => {
    for (const property of ['og:type', 'og:site_name', 'og:title', 'og:description', 'og:url', 'og:image']) {
      expect(meta(doc, `property="${property}"`), property).toBeTruthy();
    }
    expect(meta(doc, 'property="og:url"')).toBe(url);
    expect(meta(doc, 'property="og:image"')).toMatch(/^https:\/\/doorprints\.web\.app\/.+\.png$/);
    expect(meta(doc, 'name="twitter:card"')).toBe('summary');
    expect(meta(doc, 'name="twitter:image"') ?? meta(doc, 'property="og:image"')).toMatch(/^https:\/\//);
  });

  it('is indexable (no noindex) and sets a theme colour for each scheme', () => {
    expect(meta(doc, 'name="robots"')).not.toMatch(/noindex/i);
    expect(doc.head.querySelectorAll('meta[name="theme-color"]').length).toBeGreaterThanOrEqual(2);
  });

  it('declares its language', () => {
    expect(doc.documentElement.getAttribute('lang')).toBe('en');
  });
});

describe('index.html', () => {
  const doc = pages['index.html'];

  it('carries valid SoftwareApplication JSON-LD with the required keys', () => {
    const blocks = doc.querySelectorAll('script[type="application/ld+json"]');
    expect(blocks).toHaveLength(1);
    const data = JSON.parse(blocks[0].textContent!);
    expect(data['@context']).toBe('https://schema.org');
    expect(data['@type']).toBe('SoftwareApplication');
    for (const key of ['name', 'applicationCategory', 'operatingSystem', 'offers', 'url', 'inLanguage', 'license']) {
      expect(data, key).toHaveProperty(key);
    }
    expect(data.name).toBe('Doorprints');
    expect(data.offers).toMatchObject({ '@type': 'Offer', price: '0' });
    expect(data.url).toBe(`${ORIGIN}/`);
    expect(data.inLanguage).toEqual(['en', 'hi', 'ta', 'te']);
    expect(data.license).toBe('https://www.gnu.org/licenses/agpl-3.0.html');
  });

  it('keeps the English description in step with the app (the title strategy rewrites it by language)', () => {
    expect(meta(doc, 'name="description"')).toBe(en['app.description']);
  });

  it('has one canonical address, shared with the title strategy', () => {
    expect(SITE_URL).toBe(`${ORIGIN}/`);
  });

  it('does not claim language alternates that are the same page: only x-default, to itself', () => {
    const alternates = [...doc.head.querySelectorAll('link[rel="alternate"][hreflang]')];
    expect(alternates.map((a) => a.getAttribute('hreflang'))).toEqual(['x-default']);
    expect(alternates[0].getAttribute('href')).toBe(`${ORIGIN}/`);
  });

  it('has crawlable landing content inside app-root: one h1, the privacy stance, four languages and the guide', () => {
    const root = doc.body.querySelector('app-root')!;
    expect(root.querySelectorAll('h1')).toHaveLength(1);
    const text = root.textContent!;
    expect(text).toMatch(/no account/i);
    for (const name of ['English', 'हिन्दी', 'தமிழ்', 'తెలుగు']) expect(text).toContain(name);
    const hrefs = [...root.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('about.html');
    expect(hrefs).toContain('privacy.html');
    expect(hrefs).toContain('https://sriram-codes-sw.github.io/doorprints/');
    for (const lang of ['hi', 'ta', 'te']) expect(hrefs).toContain(`https://sriram-codes-sw.github.io/doorprints/${lang}/`);
    expect(text).not.toMatch(/play store/i);
  });
});

describe('about.html', () => {
  const doc = pages['about.html'];

  it('says the same thing in English, Hindi, Tamil and Telugu, each section in its own lang', () => {
    const sections = [...doc.body.querySelectorAll('section[lang]')];
    expect(sections.map((s) => s.getAttribute('lang'))).toEqual(['en', 'hi', 'ta', 'te']);
    for (const s of sections) expect(s.textContent).toContain('AGPL-3.0');
  });

  it('links the guide in each language and the app, and does not link a Play Store listing', () => {
    const hrefs = [...doc.body.querySelectorAll('a')].map((a) => a.getAttribute('href')!);
    expect(hrefs).toContain('./');
    expect(hrefs).toContain('https://sriram-codes-sw.github.io/doorprints/');
    for (const lang of ['hi', 'ta', 'te']) expect(hrefs).toContain(`https://sriram-codes-sw.github.io/doorprints/${lang}/`);
    expect(hrefs.join(' ')).not.toMatch(/play\.google/);
  });

  it('links the privacy policy from every language section', () => {
    for (const s of doc.body.querySelectorAll('section[lang]')) {
      const hrefs = [...s.querySelectorAll('a')].map((a) => a.getAttribute('href')!);
      expect(hrefs.some((h) => h.startsWith('privacy.html')), s.getAttribute('lang')!).toBe(true);
    }
  });
});

/**
 * S4b-BL-121: the privacy policy Google's consent screen links (docs/15 §2.3, §2.4; docs/13 I17). Static, read without
 * scripts, in four languages like about.html (hi/ta/te under review, English governs).
 */
describe('privacy.html', () => {
  const doc = pages['privacy.html'];
  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ');
  const LIMITED_USE =
    "Doorprints' use and transfer to any other app of information received from Google APIs will adhere to the " +
    'Google API Services User Data Policy, including the Limited Use requirements.';

  it('has its own title and canonical address', () => {
    expect(doc.title).toBe('Privacy policy: Doorprints');
    expect(doc.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(`${ORIGIN}/privacy.html`);
  });

  it("carries Google's Limited Use sentence verbatim, in English, in every language section", () => {
    const sections = [...doc.body.querySelectorAll('section[lang]')];
    expect(sections.map((s) => s.getAttribute('lang'))).toEqual(['en', 'hi', 'ta', 'te']);
    for (const s of sections) expect(text(s), s.getAttribute('lang')!).toContain(LIMITED_USE);
    expect(doc.body.querySelector('a[href="https://developers.google.com/terms/api-services-user-data-policy"]')).not.toBeNull();
  });

  it('says what it must: the one scope, no server of ours, encryption on the device, deletion, revoking, children, the date', () => {
    const en = text(doc.body.querySelector('section[lang="en"]'));
    for (const phrase of [
      'drive.file',
      'no server of ours',
      'encrypted on your device',
      'No advertising',
      'Nothing is sold',
      'Delete this backup',
      'Delete all backups',
      'Delete everything Doorprints keeps in my Google Drive',
      'Disconnect Google Drive',
      'Disconnect on all devices',
      'Remove all data',
      'Children',
      'Changes to this policy',
    ]) {
      expect(en, phrase).toContain(phrase);
    }
    const hrefs = [...doc.body.querySelectorAll('a')].map((a) => a.getAttribute('href')!);
    expect(hrefs).toContain('https://myaccount.google.com/connections');
    expect(hrefs).toContain('./');
    expect(hrefs).toContain('about.html');
    expect(doc.body.querySelector('time[datetime]')?.getAttribute('datetime')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('names no email address: the contact is the public issues page (no personal address on a public page)', () => {
    expect(privacyHtml).not.toMatch(/[\w.+-]+@[\w-]+\.[\w.]+/);
    expect(privacyHtml).not.toMatch(/mailto:/i);
    const hrefs = [...doc.body.querySelectorAll('a')].map((a) => a.getAttribute('href')!);
    expect(hrefs).toContain('https://github.com/Sriram-Codes-SW/doorprints/issues');
  });

  it('works without scripts and loads nothing from another site', () => {
    expect(doc.querySelectorAll('script, iframe, object, embed')).toHaveLength(0);
    expect(privacyHtml).not.toMatch(/\son\w+\s*=/i);
    for (const el of doc.querySelectorAll('link[href]:not([rel="canonical"]), img[src], [style*="url("]')) {
      expect(el.getAttribute('href') ?? el.getAttribute('src') ?? '', el.outerHTML).not.toMatch(/^(https?:)?\/\//);
    }
  });

  it('keeps the brand words (never "Restore")', () => {
    expect(text(doc.body)).not.toMatch(/\brestore\b/i);
    expect(text(doc.body)).toContain('Import a backup');
  });
});

describe('sitemap.xml', () => {
  const doc = new DOMParser().parseFromString(sitemapXml, 'application/xml');
  const locs = [...doc.querySelectorAll('url > loc')].map((l) => l.textContent!.trim());

  it('is well-formed with the sitemap namespace', () => {
    expect(doc.querySelector('parsererror')).toBeNull();
    expect(doc.documentElement.namespaceURI).toBe('http://www.sitemaps.org/schemas/sitemap/0.9');
  });

  it('lists only absolute https URLs under the domain, once each, and the landing page first', () => {
    expect(locs[0]).toBe(`${ORIGIN}/`);
    expect(new Set(locs).size).toBe(locs.length);
    for (const loc of locs) expect(loc.startsWith(`${ORIGIN}/`), loc).toBe(true);
  });

  it('lists the public pages and none of the private routes', () => {
    expect(locs).toContain(`${ORIGIN}/about.html`);
    expect(locs).toContain(`${ORIGIN}/privacy.html`);
    const privatePaths = routes.filter((r) => r.data?.['index'] !== true).map((r) => r.path!.split('/')[0]);
    for (const loc of locs) {
      const first = new URL(loc).pathname.split('/')[1];
      expect(privatePaths, loc).not.toContain(first);
    }
  });
});

describe('robots.txt', () => {
  it('allows crawling, references the sitemap and does not block the whole site', () => {
    expect(robotsTxt).toMatch(/^User-agent: \*$/m);
    expect(robotsTxt).toMatch(/^Sitemap: https:\/\/doorprints\.web\.app\/sitemap\.xml$/m);
    expect(robotsTxt).not.toMatch(/^Disallow:\s*\/\s*$/m);
  });
});

describe('Firebase Hosting', () => {
  it('rewrites only what is not a file: robots.txt, sitemap.xml, about.html and privacy.html exist in public/, so they win', () => {
    const rewrites = firebaseJson.hosting.rewrites;
    expect(rewrites).toEqual([{ source: '**', destination: '/index.html' }]);
    // The static files above are copied from public/ to the build output by Angular's assets rule.
    expect(robotsTxt.length).toBeGreaterThan(0);
    expect(sitemapXml.length).toBeGreaterThan(0);
  });
});

describe('web manifest', () => {
  const manifest = JSON.parse(manifestText);

  it('has what an installable, discoverable app needs', () => {
    for (const key of ['name', 'short_name', 'description', 'lang', 'start_url', 'scope', 'display', 'theme_color', 'background_color', 'categories', 'icons']) {
      expect(manifest, key).toHaveProperty(key);
    }
    expect(manifest.description.length).toBeGreaterThanOrEqual(50);
    expect(manifest.theme_color).toMatch(/^#[0-9a-f]{6}$/i);
    expect(manifest.background_color).toMatch(/^#[0-9a-f]{6}$/i);
  });

  it('has 192 and 512 icons, and a maskable one', () => {
    const sizes = manifest.icons.map((i: { sizes: string }) => i.sizes);
    expect(sizes).toContain('192x192');
    expect(sizes).toContain('512x512');
    expect(manifest.icons.some((i: { purpose?: string }) => i.purpose === 'maskable')).toBe(true);
  });

  // S4b-BL-108: the installed app on a computer offers to open a Doorprints backup (a ZIP) and lands on Your data,
  // where Import a backup is; relative like start_url, so a sub-path deployment works too. ZIP only: a bare data.json
  // stays a choice in the picker, not something the system hands every JSON file to.
  it('opens a .zip backup on Your data (file_handlers)', () => {
    expect(manifest.file_handlers).toEqual([{ action: './data', accept: { 'application/zip': ['.zip'] } }]);
    expect(`/${manifest.file_handlers[0].action.replace(/^\.\//, '')}`).toBe(IMPORT_ROUTE);
    expect(routes.some((r) => r.path === IMPORT_ROUTE.slice(1))).toBe(true);
  });

  it('is served with the manifest media type', () => {
    const rule = firebaseJson.hosting.headers.find((h) => h.source === '/manifest.webmanifest');
    expect(rule?.headers[0].value).toContain('application/manifest+json');
  });
});

describe('which routes search engines may index', () => {
  it('only the landing page opts in; every other route is noindex by default', () => {
    const indexable = routes.filter((r) => r.data?.['index'] === true);
    expect(indexable.map((r) => r.path)).toEqual(['']);
    const priv = routes.filter((r) => r.data?.['index'] !== true).map((r) => r.path);
    for (const path of ['houses/new', 'houses/:id', 'compare', 'brokers', 'data', 'viewings', 'areas', 'places', 'share', 'connect']) {
      expect(priv, path).toContain(path);
    }
  });

  it('the landing page title is the brand with a tagline, in all four languages, under 70 characters', () => {
    for (const dict of [en, hi, ta, te]) {
      expect(dict['title.map']).toMatch(/^Doorprints/);
      expect(dict['title.map'].length).toBeLessThanOrEqual(70);
    }
  });
});

describe('security headers for Google sign-in (S4b-BL-73)', () => {
  const headers = () =>
    Object.fromEntries(
      (firebaseJson.hosting.headers.find((h) => h.source === '**')?.headers ?? []).map(
        (h: { key: string; value: string }) => [h.key, h.value],
      ),
    );

  it('lets the Google sign-in popup talk back (COOP same-origin-allow-popups)', () => {
    expect(headers()['Cross-Origin-Opener-Policy']).toBe('same-origin-allow-popups');
  });

  it('allows only the Google sign-in script and frame, and keeps connect-src open to https', () => {
    const csp = headers()['Content-Security-Policy'];
    expect(csp).toContain("script-src 'self' https://accounts.google.com/gsi/client;");
    expect(csp).toContain('frame-src \'self\' blob: https://accounts.google.com/gsi/;');
    // Map tiles, address search and a self-hosted server need any https host.
    expect(csp).toMatch(/connect-src 'self' https: /);
  });

  it('does not reference fonts.googleapis.com or fonts.gstatic.com (self-hosted Noto fonts, S4b-BL-73)', () => {
    const csp = headers()['Content-Security-Policy'];
    expect(csp).not.toContain('fonts.googleapis.com');
    expect(csp).not.toContain('fonts.gstatic.com');
  });
});
