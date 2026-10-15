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

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TranslationService } from '../../i18n/translation.service';
import { AiService } from '../ai.service';
import type { AiProviderConfig } from '../ai/ai-provider-config';
import { GeminiTranscriber, OpenAiAudioTranscriber } from '../ai/transcriber';
import { VOICE_INPUT_KEY } from '../storage-keys';
import { FAKE_AUDIO, FAKE_AUDIO_B64, FakeRecorder, fakeMic } from './testing/voice-fakes';
import { RECORDER_ENV, VoiceInputService, keyTermsFor, transcriberFor } from './voice-input.service';

/**
 * TC-U-244 (docs/06): the *Voice input* setting and the one place a clip becomes text (voice PR 4, S4b-BL-221, ADR-37). Off
 * until the person turns it on; works only with their own key and a service that takes audio; the key is the existing
 * own-key slot; the call is the existing `Transcriber`, here through a stubbed `fetch` with canned answers (no provider is
 * called, no paid call).
 */
const GEMINI: AiProviderConfig = { kind: 'gemini', baseUrl: '', model: '' };
const compat = (baseUrl: string): AiProviderConfig => ({ kind: 'openai-compatible', baseUrl, model: 'chat-model' });
const OPENAI = compat('https://api.openai.com/v1');
const GROQ = compat('https://api.groq.com/openai/v1');
const OPENROUTER = compat('https://openrouter.ai/api/v1');

function setup(opts: { config?: AiProviderConfig; own?: boolean; enabled?: boolean; recorder?: boolean } = {}) {
  const mic = fakeMic();
  const ai = {
    enabled: signal(opts.enabled ?? true),
    usesOwnKey: signal(opts.own ?? true),
    aiConfig: signal(opts.config ?? GEMINI),
    ownKey: () => 'own-key-not-real-1234',
  };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: AiService, useValue: ai },
      { provide: RECORDER_ENV, useValue: opts.recorder === false ? { ...mic.env, recorder: null } : mic.env },
    ],
  });
  return { service: TestBed.inject(VoiceInputService), ai, mic };
}

const clip = (mime = 'audio/webm;codecs=opus') => ({ bytes: new TextEncoder().encode(FAKE_AUDIO), mime, durationMs: 4000, limit: null });

describe('VoiceInputService', () => {
  beforeEach(() => {
    localStorage.clear();
    FakeRecorder.reset();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('is off by default: no microphone offered, nothing stored, the microphone never asked for', () => {
    const { service, mic } = setup();
    expect(service.enabled()).toBe(false);
    expect(service.available()).toBe(false);
    expect(mic.asked()).toBe(0);
    expect(localStorage.getItem(VOICE_INPUT_KEY)).toBeNull();
  });

  it('the switch is kept in this browser only and read again on the next start', () => {
    const { service } = setup();
    service.setEnabled(true);
    expect(localStorage.getItem(VOICE_INPUT_KEY)).toBe('1');
    expect(service.available()).toBe(true);
    expect(setup().service.enabled()).toBe(true);
    TestBed.inject(VoiceInputService).setEnabled(false);
    expect(localStorage.getItem(VOICE_INPUT_KEY)).toBeNull();
    expect(setup().service.enabled()).toBe(false);
  });

  it('a blocked storage does not break the switch: it holds for the page', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    const { service } = setup();
    service.setEnabled(true);
    expect(service.enabled()).toBe(true);
  });

  it.each([
    ['Gemini', GEMINI, true],
    ['OpenAI', OPENAI, true],
    ['Groq', GROQ, true],
    ['OpenRouter', OPENROUTER, true],
    ['Anthropic', { kind: 'anthropic', baseUrl: '', model: 'm' } as AiProviderConfig, false],
    ['Ollama', compat('http://localhost:11434/v1'), false],
    ['LM Studio', compat('http://localhost:1234/v1'), false],
    ['a custom address', compat('https://llm.example.in/v1'), false],
  ] as const)('with %s the microphone is offered: %s', (_name, config, offered) => {
    const { service } = setup({ config });
    service.setEnabled(true);
    expect(service.providerOk()).toBe(offered);
    expect(service.available()).toBe(offered);
  });

  it('is never offered with the connected server answering (no proxy, no key to use)', () => {
    const { service } = setup({ own: false });
    service.setEnabled(true);
    expect(service.providerOk()).toBe(false);
    expect(service.available()).toBe(false);
  });

  it('is not offered while AI features are off, or in a browser that cannot record', () => {
    const off = setup({ enabled: false });
    off.service.setEnabled(true);
    expect(off.service.available()).toBe(false);
    const noRecorder = setup({ recorder: false });
    noRecorder.service.setEnabled(true);
    expect(noRecorder.service.available()).toBe(false);
  });

  it('names the host and the list price for the disclosure', () => {
    const { service, ai } = setup();
    expect(service.host()).toBe('generativelanguage.googleapis.com');
    expect(service.price()).toBe('0.17');
    ai.aiConfig.set(GROQ);
    expect(service.host()).toBe('api.groq.com');
    expect(service.price()).toBe('0.06');
    ai.aiConfig.set(OPENAI);
    expect(service.price()).toBe('0.26');
    ai.aiConfig.set(OPENROUTER);
    expect(service.price()).toBe('0.26');
    ai.aiConfig.set(compat('https://llm.example.in/v1'));
    expect(service.price()).toBeNull();
  });

  it('builds the Gemini transcriber for Gemini and a model-named OpenAI one for each speech preset', () => {
    expect(transcriberFor(GEMINI, 'k', 'house')).toBeInstanceOf(GeminiTranscriber);
    for (const [config, model] of [[OPENAI, 'gpt-4o-mini-transcribe'], [OPENROUTER, 'openai/gpt-4o-mini-transcribe'], [GROQ, 'whisper-large-v3-turbo']] as const) {
      const t = transcriberFor(config, 'k', 'house') as unknown as { settings: { model: string; baseUrl: string } };
      expect(t).toBeInstanceOf(OpenAiAudioTranscriber);
      expect(t.settings.model).toBe(model);
      expect(t.settings.baseUrl).toBe(config.baseUrl);
    }
    expect(transcriberFor({ kind: 'anthropic', baseUrl: '', model: 'm' }, 'k', 'house')).toBeNull();
    expect(transcriberFor(compat('http://localhost:11434/v1'), 'k', 'house')).toBeNull();
  });

  it('key terms: a house hunt gets BHK and lakh, a school its own', () => {
    expect(keyTermsFor('house')).toContain('BHK');
    expect(keyTermsFor('house')).toContain('lakh');
    expect(keyTermsFor('school')).toContain('CBSE');
    expect(keyTermsFor('school')).not.toContain('BHK');
  });

  it('transcribes through Gemini with the person\'s own key in the header, the language of the app, and returns the words', async () => {
    const calls: { url: string; init: RequestInit }[] = [];
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: JSON.stringify({ text: ' two bhk in Baner ', language: 'hi-IN' }) }] } }] }));
    });
    const { service } = setup();
    service.setEnabled(true);
    await TestBed.inject(TranslationService).setLang('hi');
    const words = await service.transcribe(clip(), 'house');
    expect(words).toEqual({ text: 'two bhk in Baner', language: 'hi-IN' });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toContain('https://generativelanguage.googleapis.com/');
    expect(new Headers(calls[0].init.headers).get('x-goog-api-key')).toBe('own-key-not-real-1234');
    expect(calls[0].url).not.toContain('own-key');
    const body = String(calls[0].init.body);
    expect(body).toContain(FAKE_AUDIO_B64);
    expect(body).toContain('Language hint: hi.');
    expect(body).toContain('"mimeType":"audio/webm"');
  });

  it('transcribes through Groq as a form with the turbo model and the key terms, the key only as a Bearer header', async () => {
    const calls: { url: string; init: RequestInit }[] = [];
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ text: 'three bhk Adyar' }));
    });
    const { service } = setup({ config: GROQ });
    service.setEnabled(true);
    const words = await service.transcribe(clip(), 'house');
    expect(words.text).toBe('three bhk Adyar');
    expect(calls[0].url).toBe('https://api.groq.com/openai/v1/audio/transcriptions');
    const form = calls[0].init.body as FormData;
    expect(form.get('model')).toBe('whisper-large-v3-turbo');
    expect(String(form.get('prompt'))).toContain('BHK');
    expect(new Headers(calls[0].init.headers).get('Authorization')).toBe('Bearer own-key-not-real-1234');
  });

  it('refuses without a call when the microphone is not offered (switch off, or a provider without audio)', async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    const off = setup();
    await expect(off.service.transcribe(clip(), 'house')).rejects.toMatchObject({ kind: 'unavailable' });
    const anthropic = setup({ config: { kind: 'anthropic', baseUrl: '', model: 'm' } });
    anthropic.service.setEnabled(true);
    await expect(anthropic.service.transcribe(clip(), 'house')).rejects.toMatchObject({ kind: 'unavailable' });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('a clip over the 2 MB cap is refused before anything is sent', async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);
    const { service } = setup();
    service.setEnabled(true);
    await expect(service.transcribe({ ...clip(), bytes: new Uint8Array(2 * 1024 * 1024 + 1) }, 'house')).rejects.toMatchObject({ reason: 'tooLarge' });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('language hint follows the app language', async () => {
    const { service } = setup();
    const i18n = TestBed.inject(TranslationService);
    expect(service.langHint()).toBe('en-IN');
    await i18n.setLang('ta');
    expect(service.langHint()).toBe('ta-IN');
    await i18n.setLang('te');
    expect(service.langHint()).toBe('te-IN');
    await i18n.setLang('en');
  });
});
