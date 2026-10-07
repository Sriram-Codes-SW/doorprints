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
import { AI_PRESETS, GEMINI_HOST } from '../../core/ai/ai-provider-config';
import { OpenAiCompatibleChatModel } from '../../core/ai/openai-compat';
import { OnDeviceAiError, OnDeviceAiService } from '../../core/ai/on-device-ai.service';
import { HouseApiService } from '../../core/house-api.service';
import { AI_BASE_URL_KEY, AI_KIND_KEY, AI_MODEL_KEY, AI_PROVIDER_KEY, GEMINI_KEY_KEY } from '../../core/storage-keys';
import { TranslationService } from '../../i18n/translation.service';
import { ConnectPage } from './connect-page';

/**
 * The Connect page's *AI service* picker (S4b-BL-151, docs/03 §13.2, ADR-35): presets prefill the base URL, the checks
 * name the field that is wrong, *Save* keeps the settings in this browser, *Test* names the host and the failure, and
 * *Remove key* forgets everything. The network is a fake `OnDeviceAiService.test`.
 */
describe('ConnectPage AI service', () => {
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
    await open();
  });

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  async function open(): Promise<void> {
    fixture = TestBed.createComponent(ConnectPage);
    host = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
    if (!el('#ai-service')) {
      el<HTMLInputElement>('#ai-features')!.click();
      await settle();
    }
  }

  const el = <T extends HTMLElement>(selector: string) => host.querySelector<T>(selector);
  const text = () => el('#ai')?.textContent ?? '';
  const ai = () => TestBed.inject(AiService);

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

  async function type(selector: string, value: string): Promise<void> {
    const input = el<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  async function click(selector: string): Promise<void> {
    el<HTMLButtonElement>(selector)!.click();
    await settle();
  }

  /** What the saved settings and key look like to the rest of the app. */
  const stored = () => ({
    kind: localStorage.getItem(AI_KIND_KEY),
    baseUrl: localStorage.getItem(AI_BASE_URL_KEY),
    model: localStorage.getItem(AI_MODEL_KEY),
    key: sessionStorage.getItem(GEMINI_KEY_KEY),
  });

  it('offers Gemini first and then every preset, and not Anthropic', () => {
    const options = [...host.querySelectorAll<HTMLOptionElement>('#ai-service option')];
    expect(options.map((o) => o.value)).toEqual(['gemini', ...AI_PRESETS.map((p) => p.id)]);
    expect(options.map((o) => o.textContent?.trim())).toEqual([
      'Google Gemini', 'OpenAI', 'OpenRouter', 'Groq', 'Ollama (on this device)', 'LM Studio (on this device)', 'Custom (OpenAI-compatible)',
    ]);
    expect(el<HTMLSelectElement>('#ai-service')!.value).toBe('gemini');
    expect(el('#gemini-key')).not.toBeNull();
    expect(el('#ai-base-url')).toBeNull();
  });

  it('each preset prefills its own base URL, and only Custom can be edited', async () => {
    for (const preset of AI_PRESETS) {
      await choose(preset.id);
      const url = el<HTMLInputElement>('#ai-base-url')!;
      expect(url.value, preset.id).toBe(preset.baseUrl);
      expect(url.readOnly, preset.id).toBe(preset.id !== 'custom');
      expect(el('#gemini-key'), preset.id).toBeNull();
    }
    await choose('custom');
    await type('#ai-base-url', 'https://llm.example.org/v1');
    expect(el<HTMLInputElement>('#ai-base-url')!.value).toBe('https://llm.example.org/v1');
  });

  it('shows the sample model only as a placeholder, never as a value', async () => {
    const expected: Record<string, string> = { openai: 'gpt-4o-mini', groq: 'llama-3.3-70b-versatile', ollama: 'llama3.1' };
    for (const [id, model] of Object.entries(expected)) {
      await choose(id);
      expect(el<HTMLInputElement>('#ai-model')!.placeholder, id).toBe(model);
      expect(el<HTMLInputElement>('#ai-model')!.value, id).toBe('');
    }
  });

  it('Ollama and LM Studio say the key is optional and show the CORS hint with OLLAMA_ORIGINS; hosted services do not', async () => {
    await choose('ollama');
    expect(text()).toContain('OLLAMA_ORIGINS=https://doorprints.web.app');
    expect(text()).toContain('The browser blocks this site');
    expect(text()).toContain('usually needs no key');
    await choose('lmstudio');
    expect(text()).toContain('https://doorprints.web.app in its CORS settings');
    expect(text()).not.toContain('OLLAMA_ORIGINS');
    await choose('openai');
    expect(el('#ai-cors-hint')).toBeNull();
    expect(text()).not.toContain('usually needs no key');
    expect(el<HTMLAnchorElement>('#ai a[href="https://platform.openai.com/api-keys"]')?.rel).toContain('noopener');
  });

  it('masks the key and the show button reveals it', async () => {
    await choose('openai');
    const key = el<HTMLInputElement>('#ai-key')!;
    expect(key.type).toBe('password');
    el<HTMLButtonElement>('#ai-key + button')!.click();
    await settle();
    expect(key.type).toBe('text');
  });

  it('a base URL that breaks a rule is shown as a field error in a polite live region, and nothing is saved or tested', async () => {
    await choose('custom');
    await type('#ai-base-url', 'http://192.168.1.5:8080/v1');
    await type('#ai-model', 'llama3.1');
    await type('#ai-key', 'fake-key-1234');
    await click('#ai-save');
    const error = el('#ai-base-url-error')!;
    expect(error.textContent).toContain('http:// works only for this device (localhost)');
    expect(error.classList.contains('field-error')).toBe(true);
    expect(error.parentElement?.getAttribute('aria-live')).toBe('polite');
    expect(el('#ai-base-url')!.getAttribute('aria-invalid')).toBe('true');
    expect(el('#ai-base-url')!.getAttribute('aria-describedby')).toContain('ai-base-url-error');
    expect(text()).toContain('Use https, or run it on this device.');
    expect(stored()).toEqual({ kind: null, baseUrl: null, model: null, key: null });

    await click('#ai-test');
    expect(test).not.toHaveBeenCalled();

    await type('#ai-base-url', 'https://x.example/v1/chat/completions');
    await click('#ai-save');
    expect(el('#ai-base-url-error')!.textContent).toContain('without /chat/completions');
    await type('#ai-base-url', '');
    await click('#ai-save');
    expect(el('#ai-base-url-error')!.textContent).toContain('Enter the service address.');
    await type('#ai-base-url', 'https://x.example/v1');
    expect(el('#ai-base-url-error')).toBeNull();
  });

  it('asks for the model, and for a key unless the service is on this device', async () => {
    await choose('openai');
    await click('#ai-save');
    expect(el('#ai-model-error')!.textContent).toContain('Enter the model name first.');
    expect(el('#ai-key-error')!.textContent).toContain('Paste your API key first.');
    expect(el('#ai-model')!.getAttribute('aria-describedby')).toContain('ai-model-error');
    expect(stored().kind).toBeNull();

    await choose('ollama');
    await type('#ai-model', 'llama3.1');
    await click('#ai-save');
    expect(el('#ai-model-error')).toBeNull();
    expect(el('#ai-key-error')).toBeNull();
    expect(stored()).toEqual({ kind: 'openai-compatible', baseUrl: 'http://localhost:11434/v1', model: 'llama3.1', key: null });
    expect(ai().usesOwnKey()).toBe(true);
  });

  it('Save keeps the three settings and the key in this browser and chooses this device', async () => {
    const setAiConfig = vi.spyOn(ai(), 'setAiConfig');
    await choose('groq');
    await type('#ai-model', ' llama-3.3-70b-versatile ');
    await type('#ai-key', 'fake-groq-key-5678');
    await click('#ai-save');
    expect(setAiConfig).toHaveBeenCalledWith({ kind: 'openai-compatible', baseUrl: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile' });
    expect(stored()).toEqual({ kind: 'openai-compatible', baseUrl: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile', key: 'fake-groq-key-5678' });
    expect(localStorage.getItem(AI_PROVIDER_KEY)).toBe('device');
    expect(el<HTMLInputElement>('#ai-key')!.value).toBe('');
    expect(text()).toContain('Saved in this browser.');
    expect(text()).toContain('A key ending in 5678 is saved in this browser.');
    expect(el('#ai-remove')).not.toBeNull();
    expect(test).not.toHaveBeenCalled();
    expect(ai().ownHost()).toBe('api.groq.com');
  });

  it('saving again with no new key keeps the saved key; another service never inherits it', async () => {
    await choose('groq');
    await type('#ai-model', 'llama-3.3-70b-versatile');
    await type('#ai-key', 'fake-groq-key-5678');
    await click('#ai-save');
    await type('#ai-model', 'llama-3.1-8b-instant');
    await click('#ai-save');
    expect(stored().key).toBe('fake-groq-key-5678');
    expect(stored().model).toBe('llama-3.1-8b-instant');

    await choose('openrouter');
    expect(text()).not.toContain('5678');
    expect(el('#ai-remove')).toBeNull();
    await type('#ai-model', 'openai/gpt-4o-mini');
    await click('#ai-save');
    expect(el('#ai-key-error')).not.toBeNull();
    expect(stored().baseUrl).toBe('https://api.groq.com/openai/v1');

    await choose('ollama');
    await type('#ai-model', 'llama3.1');
    await click('#ai-save');
    expect(stored().key).toBeNull();
    expect(ai().hasGeminiKey()).toBe(false);
  });

  it('Gemini keeps today\'s fields and a saved key is not offered to another service', async () => {
    await choose('openai');
    await type('#ai-model', 'gpt-4o-mini');
    await type('#ai-key', 'fake-openai-key-4321');
    await click('#ai-save');
    await choose('gemini');
    expect(el('#gemini-key')).not.toBeNull();
    expect(text()).not.toContain('4321');
    expect(el('.gemini-actions button.btn-danger')).toBeNull();

    el<HTMLInputElement>('#gemini-key')!.value = 'AIzaFakeGeminiKey9';
    el('#gemini-key')!.dispatchEvent(new Event('input'));
    await settle();
    await click('#gemini-save');
    expect(test).toHaveBeenCalledWith('AIzaFakeGeminiKey9');
    expect(text()).toContain('Google accepted this key.');
    expect(stored()).toEqual({ kind: 'gemini', baseUrl: '', model: '', key: 'AIzaFakeGeminiKey9' });
    expect(ai().ownHost()).toBe(GEMINI_HOST);
  });

  it('Test names the host that accepted the key, with what is typed, and saves nothing', async () => {
    await choose('openrouter');
    await type('#ai-model', 'openai/gpt-4o-mini');
    await type('#ai-key', 'fake-or-key-0001');
    await click('#ai-test');
    expect(test).toHaveBeenCalledTimes(1);
    expect(test.mock.calls[0][0]).toBeInstanceOf(OpenAiCompatibleChatModel);
    expect(el('[role="alert"] .success')!.textContent).toContain('openrouter.ai accepted this key.');
    expect(stored()).toEqual({ kind: null, baseUrl: null, model: null, key: null });
  });

  it('Test with no key (a service on this device) says the host answered', async () => {
    await choose('lmstudio');
    await type('#ai-model', 'qwen2.5-7b-instruct');
    await click('#ai-test');
    expect(el('[role="alert"] .success')!.textContent).toContain('localhost answered with these settings.');
  });

  it('Test uses the saved key when none is typed', async () => {
    await choose('openai');
    await type('#ai-model', 'gpt-4o-mini');
    await type('#ai-key', 'fake-openai-key-4321');
    await click('#ai-save');
    await click('#ai-test');
    expect(test).toHaveBeenCalledTimes(1);
    expect(el('[role="alert"] .success')!.textContent).toContain('api.openai.com accepted this key.');
  });

  it.each([
    [new OnDeviceAiError('keyRejected'), 'api.openai.com did not accept your key.'],
    [new OnDeviceAiError('modelNotFound'), 'The AI service does not know this model.'],
    [new OnDeviceAiError('rateLimited', 17), 'Too many requests. Try again in 17 seconds.'],
    [new OnDeviceAiError('unreachable'), 'Could not reach api.openai.com. Check the address and your connection.'],
    [new OnDeviceAiError('unavailable'), 'The AI provider is unavailable or out of free quota.'],
  ])('Test names the failure: %#', async (failure, words) => {
    test.mockRejectedValueOnce(failure);
    await choose('openai');
    await type('#ai-model', 'gpt-4o-mini');
    await type('#ai-key', 'fake-openai-key-4321');
    await click('#ai-test');
    const alert = el('[role="alert"] .error')!;
    expect(alert.textContent).toContain(words);
    expect(el('[role="alert"] .success')).toBeNull();
    expect(stored().kind).toBeNull();
  });

  it('a local service that cannot be reached says so, with the host and the CORS reason', async () => {
    test.mockRejectedValueOnce(new OnDeviceAiError('unreachable'));
    await choose('ollama');
    await type('#ai-model', 'llama3.1');
    await click('#ai-test');
    expect(el('[role="alert"] .error')!.textContent).toContain('Could not reach localhost.');
    expect(el('[role="alert"] .error')!.textContent).toContain('(CORS)');
  });

  it('the result is in a live region that exists before the result does, so it is announced', async () => {
    await choose('openai');
    const region = el('.ai-compat [role="alert"]')!;
    expect(region.textContent?.trim()).toBe('');
    await type('#ai-model', 'gpt-4o-mini');
    await type('#ai-key', 'fake-openai-key-4321');
    await click('#ai-test');
    expect(el('.ai-compat [role="alert"]')).toBe(region);
    expect(region.textContent).toContain('accepted this key');
  });

  it('Remove key forgets the key and the three settings and says so', async () => {
    await choose('openai');
    await type('#ai-model', 'gpt-4o-mini');
    await type('#ai-key', 'fake-openai-key-4321');
    await click('#ai-save');
    expect(stored().kind).toBe('openai-compatible');
    await click('#ai-remove');
    expect(stored()).toEqual({ kind: null, baseUrl: null, model: null, key: null });
    expect(localStorage.getItem(GEMINI_KEY_KEY)).toBeNull();
    expect(ai().aiConfig()).toEqual({ kind: 'gemini', baseUrl: '', model: '' });
    expect(ai().usesOwnKey()).toBe(false);
    expect(el('#ai-remove')).toBeNull();
    expect(el<HTMLInputElement>('#ai-model')!.value).toBe('');
    expect(el<HTMLInputElement>('#ai-base-url')!.value).toBe('https://api.openai.com/v1');
  });

  it('reopening the page shows the saved service, URL and model', async () => {
    await choose('custom');
    await type('#ai-base-url', 'https://llm.example.org/v1');
    await type('#ai-model', 'my-model');
    await type('#ai-key', 'fake-custom-key-7777');
    await click('#ai-save');
    fixture.destroy();
    await open();
    expect(el<HTMLSelectElement>('#ai-service')!.value).toBe('custom');
    expect(el<HTMLInputElement>('#ai-base-url')!.value).toBe('https://llm.example.org/v1');
    expect(el<HTMLInputElement>('#ai-model')!.value).toBe('my-model');
    expect(text()).toContain('A key ending in 7777 is saved in this browser.');
  });

  it('names the host the text goes to, from what is on the screen', async () => {
    expect(el('#ai-disclosure')!.textContent).toContain(`straight to ${GEMINI_HOST} with your own key`);
    await choose('groq');
    expect(el('#ai-disclosure')!.textContent).toContain('straight to api.groq.com with your own key');
    await choose('custom');
    await type('#ai-base-url', 'https://LLM.Example.org:8443/v1');
    expect(el('#ai-disclosure')!.textContent).toContain('straight to llm.example.org with your own key');
    await type('#ai-base-url', 'nonsense');
    expect(el('#ai-disclosure')).toBeNull();
  });

  it('speaks no Gemini-only words outside the Gemini choice', async () => {
    await choose('openai');
    expect(el('.ai-compat')!.textContent).not.toContain('Gemini');
    expect(el('#ai-disclosure')!.textContent).not.toContain('Gemini');
  });
});
