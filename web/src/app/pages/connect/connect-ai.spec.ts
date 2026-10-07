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
import { OnDeviceAiError, OnDeviceAiService } from '../../core/ai/on-device-ai.service';
import { HouseApiService } from '../../core/house-api.service';
import { GEMINI_KEY_KEY } from '../../core/storage-keys';
import { TranslationService } from '../../i18n/translation.service';
import { ConnectPage } from './connect-page';

/**
 * The Connect page's AI card (ADR-26): shown with or without a server; the person turns AI on, chooses the server or
 * their own Gemini key in this browser, and a key is saved only after Google accepts it.
 */
describe('ConnectPage AI card', () => {
  let fixture: ComponentFixture<ConnectPage>;
  let host: HTMLElement;
  let test: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    test = vi.fn(async () => undefined);
    TestBed.configureTestingModule({
      imports: [ConnectPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: HouseApiService, useValue: { testConnection: () => new Subject() } },
        { provide: OnDeviceAiService, useValue: { test } },
      ],
    });
    TestBed.inject(TranslationService).setLang('en');
    fixture = TestBed.createComponent(ConnectPage);
    host = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  const el = <T extends HTMLElement>(selector: string) => host.querySelector<T>(selector);
  const text = () => el('#ai')?.textContent ?? '';

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function typeKey(key: string): Promise<void> {
    const input = el<HTMLInputElement>('#gemini-key')!;
    input.value = key;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  it('with no server, turning AI on offers only the own key, and a key Google accepts turns AI on', async () => {
    expect(el('#ai')).not.toBeNull();
    expect(el('#gemini-key')).toBeNull();
    el<HTMLInputElement>('#ai-features')!.click();
    await settle();
    expect(el<HTMLInputElement>('#ai-provider-server')!.disabled).toBe(true);
    expect(el<HTMLInputElement>('#ai-provider-device')!.checked).toBe(true);
    expect(text()).toContain('Connect a server above to use this.');
    expect(text()).toContain('Save your AI service settings and key below');
    expect(el<HTMLAnchorElement>('#ai a[href="https://aistudio.google.com/apikey"]')?.rel).toContain('noopener');

    el<HTMLButtonElement>('#gemini-save')!.click();
    await settle();
    expect(text()).toContain('Paste your Gemini API key first.');
    expect(test).not.toHaveBeenCalled();

    await typeKey('AIzaTestKey1234');
    el<HTMLButtonElement>('#gemini-save')!.click();
    await settle();
    expect(test).toHaveBeenCalledWith('AIzaTestKey1234');
    expect(text()).toContain('Google accepted this key.');
    expect(text()).toContain('A key ending in 1234 is saved in this browser.');
    expect(text()).toContain('AI features are on');
    expect(sessionStorage.getItem(GEMINI_KEY_KEY)).toBe('AIzaTestKey1234');
    expect(el<HTMLInputElement>('#gemini-key')!.value).toBe('');
    expect(TestBed.inject(AiService).enabled()).toBe(true);

    host.querySelectorAll<HTMLButtonElement>('#ai .gemini-actions button').forEach((b) => {
      if (b.textContent?.includes('Remove key')) b.click();
    });
    await settle();
    expect(sessionStorage.getItem(GEMINI_KEY_KEY)).toBeNull();
    expect(TestBed.inject(AiService).enabled()).toBe(false);
  });

  it('a key Google rejects is not saved and says so', async () => {
    test.mockRejectedValueOnce(new OnDeviceAiError('keyRejected'));
    el<HTMLInputElement>('#ai-features')!.click();
    await settle();
    await typeKey('AIzaWrongKey999');
    el<HTMLButtonElement>('#gemini-save')!.click();
    await settle();
    expect(text()).toContain('Google did not accept your Gemini key.');
    expect(sessionStorage.getItem(GEMINI_KEY_KEY)).toBeNull();
    expect(localStorage.getItem(GEMINI_KEY_KEY)).toBeNull();
    expect(TestBed.inject(AiService).enabled()).toBe(false);
  });
});
