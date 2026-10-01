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

import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';
import { en } from '../../i18n/en';
import { hi } from '../../i18n/hi';
import { ta } from '../../i18n/ta';
import { te } from '../../i18n/te';
import { TranslationService } from '../../i18n/translation.service';

/**
 * The *Your data* cards for the question bank (slice 3a) and the viewings (slice 3b-1), tested since S4b-BL-103: each
 * is a section named by its heading, says what it holds, and has one link that opens its page through the real routes
 * (`/questions`, `/viewings`), in all four languages.
 */
const LANGS = { en, hi, ta, te } as const;
type Lang = keyof typeof LANGS;

const CARDS = [
  { key: 'questions', path: '/questions', page: 'APP-QUESTIONS-PAGE' },
  { key: 'viewings', path: '/viewings', page: 'APP-VIEWINGS-PAGE' },
] as const;

afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
});

async function openData(lang: Lang) {
  TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
  await TestBed.inject(TranslationService).setLang(lang);
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl('/data');
  await settle(harness);
  return harness;
}

async function settle(harness: RouterTestingHarness) {
  for (let i = 0; i < 3; i++) {
    await harness.fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    harness.detectChanges();
  }
}

const card = (harness: RouterTestingHarness, key: string) =>
  (harness.routeNativeElement as HTMLElement).querySelector<HTMLElement>(`section.card[aria-labelledby="${key}-heading"]`);

describe.each(Object.keys(LANGS) as Lang[])('Your data: the Questions and Viewings cards (%s)', (lang) => {
  const t = LANGS[lang] as Record<string, string>;

  it.each(CARDS)('the $key card is named by its heading, says what it holds and links to $path', async ({ key, path }) => {
    const harness = await openData(lang);
    const section = card(harness, key);
    expect(section).not.toBeNull();
    const h2 = section!.querySelector('h2')!;
    expect(h2.id).toBe(`${key}-heading`);
    expect(h2.textContent?.trim()).toBe(t[`${key}.dataHeading`]);
    expect(section!.querySelector('p.muted')?.textContent?.trim()).toBe(t[`${key}.dataBody`]);
    const links = section!.querySelectorAll('a');
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('href')).toBe(path);
    expect(links[0].textContent?.trim()).toBe(t[`${key}.open`]);
  });
});

describe('Your data: the cards open their pages', () => {
  it.each(CARDS)('the $key card\'s link goes to $path and shows that page', async ({ key, path, page }) => {
    const harness = await openData('en');
    card(harness, key)!.querySelector('a')!.click();
    await settle(harness);
    expect(TestBed.inject(Router).url).toBe(path);
    expect((harness.routeNativeElement as HTMLElement).tagName).toBe(page);
  });

  it('places the two cards after Criteria and before Areas, the order of the Android Settings rows', async () => {
    const harness = await openData('en');
    const order = [...(harness.routeNativeElement as HTMLElement).querySelectorAll('section.card[aria-labelledby]')]
      .map((s) => s.getAttribute('aria-labelledby'));
    const at = (id: string) => order.indexOf(id);
    expect(at('criteria-heading')).toBeGreaterThanOrEqual(0);
    expect(at('questions-heading')).toBe(at('criteria-heading') + 1);
    expect(at('viewings-heading')).toBe(at('questions-heading') + 1);
    expect(at('areas-heading')).toBe(at('viewings-heading') + 1);
  });
});
