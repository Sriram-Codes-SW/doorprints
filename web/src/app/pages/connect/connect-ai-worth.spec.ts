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

import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OnDeviceAiService } from '../../core/ai/on-device-ai.service';
import { HouseApiService } from '../../core/house-api.service';
import { AI_OPT_IN_KEY } from '../../core/storage-keys';
import { DICTIONARIES } from '../../i18n/all-dictionaries';
import { en } from '../../i18n/en';
import { LANGUAGES } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { GUIDE_URL, aiWorthItUrl } from '../../shared/help-link';
import { ConnectPage } from './connect-page';

/**
 * TC-U-196 (docs/06): the three short sentences on the AI features card (S4b-BL-215): what AI is for, what it costs, what
 * it sends, in the words of the guide's "Is AI worth it for me?", with a link to that section in the app's language.
 * Shown whether AI is on or off (the choice to turn it on is informed); the existing disclosure stays.
 */
describe('ConnectPage: is AI worth it for me', () => {
  let fixture: ComponentFixture<ConnectPage>;
  let host: HTMLElement;

  async function open(on: boolean, lang: 'en' | 'hi' | 'ta' | 'te' = 'en'): Promise<void> {
    localStorage.clear();
    sessionStorage.clear();
    if (on) localStorage.setItem(AI_OPT_IN_KEY, '1');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ConnectPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: HouseApiService, useValue: { testConnection: () => new Subject() } },
        { provide: OnDeviceAiService, useValue: { test: vi.fn(async () => undefined) } },
      ],
    });
    TestBed.inject(TranslationService).setLang(lang);
    fixture = TestBed.createComponent(ConnectPage);
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => TestBed.resetTestingModule());
  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  const block = () => host.querySelector<HTMLElement>('#ai-worth');

  it('shows the three sentences with AI off, after the switch and its disclosure, in reading order', async () => {
    await open(false);
    const paragraphs = [...block()!.querySelectorAll('p')].map((p) => p.textContent!.trim());
    expect(paragraphs.slice(0, 3)).toEqual([en['connect.aiWorth'], en['connect.aiCost'], en['connect.aiSends']]);
    const hint = host.querySelector('#ai-features-hint')!;
    expect(hint.textContent).toContain('Contact names and phone numbers saved with a house are left out');
    expect(hint.compareDocumentPosition(block()!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('keeps the same sentences with AI on, beside the service form', async () => {
    await open(true);
    expect(block()?.textContent).toContain('knows nothing about the market, the law or a locality');
    expect(host.querySelector('#ai-service')).not.toBeNull();
  });

  it('says only what the guide says: cost, free tier with a daily limit, what is sent, pasted ads as pasted', () => {
    expect(en['connect.aiCost']).toContain('about a rupee or a few per use');
    expect(en['connect.aiCost']).toContain('daily limit');
    expect(en['connect.aiCost']).toContain('Google may read what you send');
    expect(en['connect.aiSends']).toContain('the matching house notes go to the service you chose');
    expect(en['connect.aiSends']).toContain('A pasted ad is sent as pasted');
  });

  it('links the guide section in the app language, in a new tab, with an accessible name that starts with its label', async () => {
    await open(false);
    const a = block()!.querySelector<HTMLAnchorElement>('a')!;
    expect(a.href).toBe(`${GUIDE_URL}settings-and-privacy.html#is-ai-worth-it`);
    expect(a.target).toBe('_blank');
    expect(a.rel).toContain('noopener');
    expect(a.getAttribute('aria-label')!.startsWith(a.textContent!.trim())).toBe(true);
    await open(false, 'ta');
    expect(block()!.querySelector<HTMLAnchorElement>('a')!.href).toBe(`${GUIDE_URL}ta/settings-and-privacy.html#is-ai-worth-it`);
  });

  it('builds the address for each language', () => {
    expect(aiWorthItUrl('en')).toBe(`${GUIDE_URL}settings-and-privacy.html#is-ai-worth-it`);
    expect(aiWorthItUrl('hi')).toBe(`${GUIDE_URL}hi/settings-and-privacy.html#is-ai-worth-it`);
    expect(aiWorthItUrl('te')).toBe(`${GUIDE_URL}te/settings-and-privacy.html#is-ai-worth-it`);
  });

  it.each(['hi', 'ta', 'te'] as const)('shows the sentences in %s with its own words, not English', async (lang) => {
    await open(false, lang);
    const dict = DICTIONARIES[lang] as Readonly<Record<string, string>>;
    expect(block()!.textContent).toContain(dict['connect.aiWorth']);
    expect(block()!.textContent).toContain(dict['connect.aiCost']);
    expect(block()!.textContent).toContain(dict['connect.aiSends']);
    expect(block()!.textContent).not.toContain('knows nothing about the market');
  });

  it('has all five strings in en, hi, ta and te, none empty', () => {
    const keys = ['connect.aiWorth', 'connect.aiCost', 'connect.aiSends', 'connect.aiGuide', 'connect.aiGuideAria'];
    for (const lang of LANGUAGES.map((l) => l.code)) {
      const dict = DICTIONARIES[lang] as Readonly<Record<string, string>>;
      for (const key of keys) expect(dict[key]?.trim(), `${lang} ${key}`).toBeTruthy();
    }
  });
});
