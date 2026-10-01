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
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';
import { App } from './app';
import { DataPage } from './pages/data/data-page';
import { en } from './i18n/en';
import { hi } from './i18n/hi';
import { ta } from './i18n/ta';
import { te } from './i18n/te';
import { TranslationService } from './i18n/translation.service';
import type { Lang } from './i18n/languages';
import { GUIDE_URL } from './shared/help-link';

/**
 * The in-app Help link (S4b-BL-60 (2)): the header on wider screens, the Help card in Your data on phones. Which of the
 * two shows is CSS (the header link is hidden up to 600px, the card from 601px), so both are in the DOM here.
 */
const DICTS = { en, hi, ta, te } as const;

afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
});

async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

describe('Help link to the user guide', () => {
  it('is in the header: the guide, a new tab, noopener, a name that says so, a 44px-class target', async () => {
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(App);
    await settle(fixture);
    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.help-link')!;
    expect(link).not.toBeNull();
    expect(link.getAttribute('href')).toBe('https://sriram-codes-sw.github.io/doorprints/');
    expect(link.getAttribute('href')).toBe(GUIDE_URL);
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toContain('noopener');
    expect(link.getAttribute('aria-label')).toBe(en['help.linkAria']);
    expect(link.getAttribute('aria-label')).toContain(link.textContent!.replace('↗', '').trim());
    // The arrow is decoration, not part of the name.
    expect(link.querySelector('[aria-hidden="true"]')?.textContent).toBe('↗');
  });

  it('is a card in Your data with the same link', async () => {
    TestBed.configureTestingModule({ imports: [DataPage], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(DataPage);
    await settle(fixture);
    const card = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>('section.help-card')!;
    expect(card.getAttribute('aria-labelledby')).toBe('help-heading');
    expect(card.querySelector('h2')!.textContent).toBe('Help');
    const link = card.querySelector<HTMLAnchorElement>('a')!;
    expect(link.getAttribute('href')).toBe(GUIDE_URL);
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link.getAttribute('aria-label')).toBe(en['help.linkAria']);
  });

  it.each(['hi', 'ta', 'te'] as Lang[])('is labelled in %s (under review) in the header and in Your data', async (lang) => {
    TestBed.configureTestingModule({ imports: [App, DataPage], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang(lang);
    const app = TestBed.createComponent(App);
    await settle(app);
    const link = (app.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a.help-link')!;
    expect(link.textContent).toContain(DICTS[lang]['help.link']);
    expect(link.getAttribute('aria-label')).toBe(DICTS[lang]['help.linkAria']);
    const data = TestBed.createComponent(DataPage);
    await settle(data);
    const card = (data.nativeElement as HTMLElement).querySelector<HTMLElement>('section.help-card')!;
    expect(card.querySelector('h2')!.textContent).toBe(DICTS[lang]['help.link']);
    expect(card.querySelector('a')!.textContent).toContain(DICTS[lang]['help.open']);
    expect(DICTS[lang]['help.link']).not.toBe(en['help.link']);
  });
});
