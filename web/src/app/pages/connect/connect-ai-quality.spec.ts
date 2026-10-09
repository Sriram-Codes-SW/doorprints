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
import { AiService } from '../../core/ai.service';
import { OnDeviceAiService } from '../../core/ai/on-device-ai.service';
import { ConfigService } from '../../core/config.service';
import { HouseApiService } from '../../core/house-api.service';
import { AI_OPT_IN_KEY, AI_PROVIDER_KEY, AI_QUALITY_KEY } from '../../core/storage-keys';
import { en } from '../../i18n/en';
import { LANGUAGES } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { DICTIONARIES } from '../../i18n/all-dictionaries';
import { ConnectPage } from './connect-page';

/**
 * TC-U-191 (docs/06): the *AI speed and cost* control on the Connect page (S4b-BL-198 step 2, docs/05 AI settings). It is
 * not rendered at all unless AI features are on, the person's own AI is the one that answers, and the service is Google
 * Gemini; a stored choice is kept but ignored then. Three radios in a named group, Quality first and chosen by default,
 * each with one line of help; a choice is kept at once; all four languages.
 */
describe('ConnectPage: AI speed and cost', () => {
  let fixture: ComponentFixture<ConnectPage>;
  let host: HTMLElement;

  async function open(storage: Record<string, string> = {}, connected = false): Promise<void> {
    localStorage.clear();
    sessionStorage.clear();
    for (const [k, v] of Object.entries(storage)) localStorage.setItem(k, v);
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
    if (connected) TestBed.inject(ConfigService).save({ baseUrl: 'https://a.example.org', apiKey: 'dpk_x' }, false);
    TestBed.inject(TranslationService).setLang('en');
    fixture = TestBed.createComponent(ConnectPage);
    host = fixture.nativeElement as HTMLElement;
    await settle();
  }

  const el = <T extends HTMLElement>(selector: string) => host.querySelector<T>(selector);
  const radios = () => [...host.querySelectorAll<HTMLInputElement>('input[name="aiQuality"]')];
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }
  async function choose(service: string): Promise<void> {
    const select = el<HTMLSelectElement>('#ai-service')!;
    select.value = service;
    select.dispatchEvent(new Event('change'));
    await settle();
  }

  /** AI on, no server, so the own AI is the one that answers. */
  const ON = { [AI_OPT_IN_KEY]: '1' };

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  describe('visible only for AI on and Gemini', () => {
    it('AI off: not rendered at all, whatever is stored', async () => {
      await open({ [AI_QUALITY_KEY]: 'economy' });
      expect(el('#ai-quality')).toBeNull();
      expect(radios()).toHaveLength(0);
      expect(host.textContent).not.toContain('AI speed and cost');
      expect(localStorage.getItem(AI_QUALITY_KEY)).toBe('economy');
    });

    it('AI on with Google Gemini: shown', async () => {
      await open(ON);
      expect(el('#ai-service')).not.toBeNull();
      expect(el<HTMLSelectElement>('#ai-service')!.value).toBe('gemini');
      expect(el('#ai-quality')).not.toBeNull();
      expect(radios()).toHaveLength(3);
    });

    it('every other service: not rendered at all, and the stored choice is kept', async () => {
      await open({ ...ON, [AI_QUALITY_KEY]: 'economy' });
      for (const service of ['openai', 'openrouter', 'groq', 'ollama', 'lmstudio', 'anthropic', 'custom']) {
        await choose(service);
        expect(el('#ai-quality'), service).toBeNull();
        expect(radios(), service).toHaveLength(0);
      }
      expect(localStorage.getItem(AI_QUALITY_KEY)).toBe('economy');
      await choose('gemini');
      expect(radios().find((r) => r.checked)?.value).toBe('economy');
    });

    it('turning AI off removes it again, and on shows the same choice', async () => {
      await open({ ...ON, [AI_QUALITY_KEY]: 'balanced' });
      el<HTMLInputElement>('#ai-features')!.click();
      await settle();
      expect(el('#ai-quality')).toBeNull();
      el<HTMLInputElement>('#ai-features')!.click();
      await settle();
      expect(radios().find((r) => r.checked)?.value).toBe('balanced');
    });

    it('the server chosen to answer: not rendered, since the setting is for an own Gemini key only', async () => {
      await open(ON, true);
      expect(el('#ai-quality')).toBeNull();
      expect(el('#ai-service')).toBeNull();
      expect(TestBed.inject(AiService).provider()).toBe('server');
      el<HTMLInputElement>('#ai-provider-device')!.click();
      await settle();
      expect(el('#ai-quality')).not.toBeNull();
    });
  });

  describe('the control', () => {
    beforeEach(async () => open(ON));

    it('is a named group of three radios in the order Quality, Balanced, Economy, with Quality chosen by default', () => {
      const group = el<HTMLFieldSetElement>('#ai-quality')!;
      expect(group.tagName).toBe('FIELDSET');
      expect(group.querySelector('legend')?.textContent?.trim()).toBe('AI speed and cost');
      expect(radios().map((r) => r.value)).toEqual(['quality', 'balanced', 'economy']);
      expect(radios().every((r) => r.type === 'radio' && r.name === 'aiQuality')).toBe(true);
      expect(radios().filter((r) => r.checked).map((r) => r.value)).toEqual(['quality']);
    });

    it('gives each radio a label and one line of help it is described by', () => {
      for (const r of radios()) {
        const label = host.querySelector<HTMLLabelElement>(`label[for="${r.id}"]`);
        expect(label?.textContent?.trim(), r.value).toBeTruthy();
        const help = el(`#${r.getAttribute('aria-describedby')}`);
        expect(help?.textContent?.trim(), r.value).toBeTruthy();
      }
      const text = el('#ai-quality')!.textContent!;
      expect(text).toContain('Quality');
      expect(text).toContain('Balanced');
      expect(text).toContain('Economy');
      expect(text).toContain('About 40% cheaper');
      expect(text).toContain('about twice as fast');
    });

    it('keeps a choice at once, on this device, and the radios follow it', async () => {
      radios()[2].click();
      await settle();
      expect(localStorage.getItem(AI_QUALITY_KEY)).toBe('economy');
      expect(TestBed.inject(AiService).aiQuality()).toBe('economy');
      expect(radios().filter((r) => r.checked).map((r) => r.value)).toEqual(['economy']);
      radios()[1].click();
      await settle();
      expect(localStorage.getItem(AI_QUALITY_KEY)).toBe('balanced');
      radios()[0].click();
      await settle();
      expect(TestBed.inject(AiService).aiQuality()).toBe('quality');
    });

    it('shows the saved choice when the page opens, and Quality for an unknown stored value', async () => {
      await open({ ...ON, [AI_QUALITY_KEY]: 'balanced' });
      expect(radios().filter((r) => r.checked).map((r) => r.value)).toEqual(['balanced']);
      await open({ ...ON, [AI_QUALITY_KEY]: 'warp-speed' });
      expect(radios().filter((r) => r.checked).map((r) => r.value)).toEqual(['quality']);
    });

    it('uses no new script: it is plain form controls, reachable by keyboard in order (no tabindex, none disabled)', () => {
      for (const r of radios()) {
        expect(r.hasAttribute('tabindex')).toBe(false);
        expect(r.disabled).toBe(false);
      }
    });
  });

  describe('words in the four languages', () => {
    const KEYS = [
      'connect.aiQualityHeading', 'connect.aiQualityQuality', 'connect.aiQualityQualityHint', 'connect.aiQualityBalanced',
      'connect.aiQualityBalancedHint', 'connect.aiQualityEconomy', 'connect.aiQualityEconomyHint',
    ] as const;

    it('has all seven strings in en, hi, ta and te, none empty', () => {
      for (const lang of LANGUAGES.map((l) => l.code)) {
        const dict = DICTIONARIES[lang] as Readonly<Record<string, string>>;
        for (const key of KEYS) expect(dict[key]?.trim(), `${lang} ${key}`).toBeTruthy();
      }
      expect(en['connect.aiQualityHeading']).toBe('AI speed and cost');
    });

    it.each(['hi', 'ta', 'te'] as const)('shows the control in %s with its own words', async (lang) => {
      await open(ON);
      TestBed.inject(TranslationService).setLang(lang);
      await settle();
      const dict = DICTIONARIES[lang] as Readonly<Record<string, string>>;
      expect(el('#ai-quality legend')?.textContent?.trim()).toBe(dict['connect.aiQualityHeading']);
      expect(el('#ai-quality')?.textContent).toContain(dict['connect.aiQualityEconomyHint']);
      expect(el('#ai-quality')?.textContent).not.toContain('About 40% cheaper');
    });

    it('names Economy, Balanced and Quality the same way in every language, as the brand words', () => {
      for (const lang of LANGUAGES.map((l) => l.code)) {
        const dict = DICTIONARIES[lang] as Readonly<Record<string, string>>;
        expect(dict['connect.aiQualityQuality'], lang).toContain('Quality');
        expect(dict['connect.aiQualityBalanced'], lang).toContain('Balanced');
        expect(dict['connect.aiQualityEconomy'], lang).toContain('Economy');
      }
    });
  });
});
