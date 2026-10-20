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
import { HttpClient, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../ai.service';
import type { AiProviderConfig } from '../ai/ai-provider-config';
import { GeminiChatModel } from '../ai/on-device-ai.service';
import { GEMINI_KEY_KEY } from '../storage-keys';
import { FAKE_AUDIO, FakeRecorder, fakeMic } from './testing/voice-fakes';
import { RECORDER_ENV, VOICE_TIER_FETCH, VoiceInputService, VoiceTierRefusedError } from './voice-input.service';
import { ALLOW_TTL_MS, keyHash } from './voice-tier';
import { readTier, writeTier } from './voice-tier-store';

/**
 * TC-U-287 (docs/06): the free-plan rule wired into voice input (owner decision 2026-10-19, ADR-37): fake providers per tier
 * (Gemini 429 with a free-tier quota id, 429 without, 200; OpenRouter key information true and false; Groq and Sarvam with no
 * signal), the confirmation per key, the lifetimes, the probe only after Voice is on and only online, the second check before the
 * request, and a later free-tier 429 on an ordinary request downgrading the kept verdict at once.
 */
const placeholder = (n: string) => ['plan', 'rule', 'placeholder', n].join('-');
const GEMINI: AiProviderConfig = { kind: 'gemini', baseUrl: '', model: '' };
const compat = (baseUrl: string): AiProviderConfig => ({ kind: 'openai-compatible', baseUrl, model: 'chat-model' });
const FREE_429 = JSON.stringify({ error: { code: 429, details: [{ violations: [{ quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier' }] }] } });
const PAID_429 = JSON.stringify({ error: { code: 429, details: [{ violations: [{ quotaId: 'GenerateContentPaidTierInputTokensPerModelPerMinute' }] }] } });

interface Setup { config?: AiProviderConfig; key?: string; reply?: (url: string) => Response }

function setup(opts: Setup = {}) {
  const mic = fakeMic();
  const key = signal(opts.key ?? placeholder('1111'));
  const ai = { enabled: signal(true), usesOwnKey: signal(true), aiConfig: signal(opts.config ?? GEMINI), ownKey: () => key() };
  const calls: { url: string; init: RequestInit }[] = [];
  const tierFetch = vi.fn((url: string, init: RequestInit) => {
    calls.push({ url, init });
    return Promise.resolve(opts.reply ? opts.reply(url) : new Response('{}', { status: 200 }));
  });
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [{ provide: AiService, useValue: ai }, { provide: RECORDER_ENV, useValue: mic.env }, { provide: VOICE_TIER_FETCH, useValue: tierFetch }],
  });
  const service = TestBed.inject(VoiceInputService);
  return { service, ai, key, calls, tierFetch };
}
const settle = async () => {
  TestBed.tick();
  for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
  TestBed.tick();
};
const clip = { bytes: new TextEncoder().encode(FAKE_AUDIO), mime: 'audio/webm;codecs=opus', durationMs: 3000, limit: null };

describe('VoiceInputService and the free-plan rule', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    FakeRecorder.reset();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('asks nothing while Voice input is off: no probe, no request, however the key is set', async () => {
    const { calls, service } = setup();
    await settle();
    expect(service.enabled()).toBe(false);
    expect(calls).toEqual([]);
  });

  it('Gemini: after Voice is turned on one call is made, with the key in the header, a fixed one-word prompt and no audio', async () => {
    const { service, calls } = setup({ reply: () => new Response(FREE_429, { status: 429 }) });
    service.setEnabled(true);
    expect(service.verdict()).toBe('CHECKING');
    await settle();
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-pro-preview:generateContent');
    expect(new Headers(calls[0].init.headers).get('x-goog-api-key')).toBe(placeholder('1111'));
    expect(calls[0].url).not.toContain(placeholder('1111'));
    const body = JSON.parse(calls[0].init.body as string) as { contents: { parts: { text: string }[] }[]; generationConfig: { maxOutputTokens: number } };
    expect(body.contents[0].parts).toEqual([{ text: 'Hi' }]);
    expect(body.generationConfig.maxOutputTokens).toBe(1);
  });

  it('Gemini 429 with a free-tier quota id: refused with no way round, and kept for 24 hours', async () => {
    const { service } = setup({ reply: () => new Response(FREE_429, { status: 429 }) });
    service.setEnabled(true);
    await settle();
    expect(service.verdict()).toBe('REFUSE_FREE');
    expect(service.micEnabled()).toBe(false);
    service.setAcknowledged(true);
    expect(service.verdict()).toBe('REFUSE_FREE');
    expect(service.canConfirmPaid()).toBe(false);
    const kept = readTier(keyHash(placeholder('1111')), 'gemini', placeholder('1111'), Date.now());
    expect(kept?.verdict).toBe('REFUSE_FREE');
  });

  it('Gemini 429 without a free-tier quota id (a paid project rate limited), and Gemini 200: allowed, kept for 7 days', async () => {
    for (const reply of [() => new Response(PAID_429, { status: 429 }), () => new Response('{}', { status: 200 })]) {
      localStorage.clear();
      const { service } = setup({ reply });
      service.setEnabled(true);
      await settle();
      expect(service.verdict()).toBe('ALLOW');
      expect(service.micEnabled()).toBe(true);
      expect(readTier(keyHash(placeholder('1111')), 'gemini', placeholder('1111'), Date.now())?.verdict).toBe('ALLOW');
    }
  });

  it('Gemini that cannot be read (a refused key, no network): refused as unknown until the person confirms, for this key only', async () => {
    const { service, key, calls } = setup({ reply: () => new Response('{}', { status: 403 }) });
    service.setEnabled(true);
    await settle();
    expect(service.verdict()).toBe('REFUSE_UNKNOWN');
    expect(service.canConfirmPaid()).toBe(true);
    service.setAcknowledged(true);
    expect(service.verdict()).toBe('ALLOW');
    expect(service.acknowledged()).toBe(true);
    // A new key asks again, and the old key's confirmation is dropped.
    const before = calls.length;
    key.set(placeholder('2222'));
    await settle();
    expect(service.acknowledged()).toBe(false);
    expect(service.verdict()).not.toBe('ALLOW');
    expect(calls.length).toBeGreaterThan(before);
    expect(localStorage.getItem(`doorprints.voice.tier-ack.${keyHash(placeholder('1111'))}`)).toBeNull();
  });

  it('a verdict older than its lifetime is asked again; a fresh one is not', async () => {
    const { service, calls } = setup({ reply: () => new Response('{}', { status: 200 }) });
    writeTier('gemini', placeholder('1111'), 'ALLOW', 'probe', Date.now() - (ALLOW_TTL_MS - 60_000));
    service.setEnabled(true);
    await settle();
    expect(calls).toEqual([]);
    expect(service.verdict()).toBe('ALLOW');
    localStorage.clear();
    writeTier('gemini', placeholder('1111'), 'ALLOW', 'probe', Date.now() - ALLOW_TTL_MS - 1);
    const again = setup({ reply: () => new Response('{}', { status: 200 }) });
    again.service.setEnabled(true);
    await settle();
    expect(again.calls).toHaveLength(1);
  });

  it('offline: no call is made and the answer stays "checking"', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    const { service, calls } = setup();
    service.setEnabled(true);
    await settle();
    expect(calls).toEqual([]);
    expect(service.verdict()).toBe('CHECKING');
  });

  it.each([
    ['true: free', true, 'REFUSE_FREE'],
    ['false: paid', false, 'ALLOW'],
  ] as const)('OpenRouter key information is_free_tier %s', async (_n, flag, expected) => {
    const { service, calls } = setup({
      config: compat('https://openrouter.ai/api/v1'),
      reply: () => new Response(JSON.stringify({ data: { is_free_tier: flag } }), { status: 200 }),
    });
    service.setEnabled(true);
    await settle();
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('https://openrouter.ai/api/v1/key');
    expect(calls[0].init.method).toBe('GET');
    expect(calls[0].init.body).toBeUndefined();
    expect(new Headers(calls[0].init.headers).get('Authorization')).toBe(`Bearer ${placeholder('1111')}`);
    expect(service.verdict()).toBe(expected);
  });

  it('Groq: no call can tell the plan, so it is refused as unknown and no call is made', async () => {
    const { service, calls } = setup({ config: compat('https://api.groq.com/openai/v1') });
    service.setEnabled(true);
    await settle();
    expect(calls).toEqual([]);
    expect(service.verdict()).toBe('REFUSE_UNKNOWN');
    service.setAcknowledged(true);
    expect(service.verdict()).toBe('ALLOW');
    service.setAcknowledged(false);
    expect(service.verdict()).toBe('REFUSE_UNKNOWN');
  });

  it('Sarvam (its own key): unknown until confirmed, and the confirmation is for that key', async () => {
    const { service, calls } = setup();
    service.setProvider('sarvam');
    service.saveSarvamKey(placeholder('sarvam-1'), true);
    service.setEnabled(true);
    await settle();
    expect(calls).toEqual([]);
    expect(service.tierProvider()).toBe('sarvam');
    expect(service.verdict()).toBe('REFUSE_UNKNOWN');
    service.setAcknowledged(true);
    expect(service.verdict()).toBe('ALLOW');
    service.saveSarvamKey(placeholder('sarvam-2'), true);
    await settle();
    expect(service.verdict()).toBe('REFUSE_UNKNOWN');
  });

  it('OpenAI has no free API tier: allowed with no call and no confirmation', async () => {
    const { service, calls } = setup({ config: compat('https://api.openai.com/v1') });
    service.setEnabled(true);
    await settle();
    expect(calls).toEqual([]);
    expect(service.verdict()).toBe('ALLOW');
    expect(service.canConfirmPaid()).toBe(false);
  });

  it('transcribe asks again before the request: a free verdict kept a moment ago stops it, nothing is sent', async () => {
    const { service, tierFetch } = setup({ config: compat('https://api.openai.com/v1') });
    const send = vi.fn(() => Promise.resolve(new Response('{}')));
    vi.stubGlobal('fetch', send);
    service.setEnabled(true);
    writeTier('openai', placeholder('1111'), 'REFUSE_FREE', 'test', Date.now());
    await expect(service.transcribe(clip, 'house')).rejects.toBeInstanceOf(VoiceTierRefusedError);
    expect(send).not.toHaveBeenCalled();
    expect(tierFetch).not.toHaveBeenCalled();
  });

  it('transcribe waits for the one probe when it is still unknown, then refuses a free key without sending the audio', async () => {
    const { service } = setup({ reply: () => new Response(FREE_429, { status: 429 }) });
    const send = vi.fn(() => Promise.resolve(new Response('{}')));
    vi.stubGlobal('fetch', send);
    service.setEnabled(true);
    await expect(service.transcribe(clip, 'house')).rejects.toMatchObject({ verdict: 'REFUSE_FREE' });
    expect(send).not.toHaveBeenCalled();
  });

  it('a later free-tier 429 on an ordinary Gemini request downgrades the kept verdict at once', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const key = placeholder('1111');
    localStorage.setItem(GEMINI_KEY_KEY, key);
    writeTier('gemini', key, 'ALLOW', 'probe', Date.now());
    expect(readTier(keyHash(key), 'gemini', key, Date.now())?.verdict).toBe('ALLOW');
    const model = new GeminiChatModel(TestBed.inject(HttpClient), key);
    const pending = model.generateJson('s', 'u', {}, 0).catch(() => 'failed');
    TestBed.inject(HttpTestingController).expectOne((r) => r.url.includes('generateContent')).flush(JSON.parse(FREE_429), { status: 429, statusText: 'Too Many Requests' });
    await pending;
    expect(readTier(keyHash(key), 'gemini', key, Date.now())).toMatchObject({ verdict: 'REFUSE_FREE' });
  });

  it('an ordinary 429 without a free-tier quota id changes nothing', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const key = placeholder('1111');
    writeTier('gemini', key, 'ALLOW', 'probe', Date.now());
    const model = new GeminiChatModel(TestBed.inject(HttpClient), key);
    const pending = model.generateJson('s', 'u', {}, 0).catch(() => 'failed');
    TestBed.inject(HttpTestingController).expectOne((r) => r.url.includes('generateContent')).flush(JSON.parse(PAID_429), { status: 429, statusText: 'Too Many Requests' });
    await pending;
    expect(readTier(keyHash(key), 'gemini', key, Date.now())?.verdict).toBe('ALLOW');
  });
});
