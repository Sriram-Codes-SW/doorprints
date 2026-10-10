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
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LocalStore } from '../../data/local-store.service';
import { backupJson, buildBackupData, buildBackupZip } from '../../export/backup-export';
import { fixtureBundle } from '../../export/golden/fixture';
import { clearLocalLeftovers } from '../../pages/data/session-leftovers';
import { AiService } from '../ai.service';
import { ConfigService } from '../config.service';
import { AI_BASE_URL_KEY, AI_KIND_KEY, AI_MODEL_KEY, AI_OPT_IN_KEY, AI_PROVIDER_KEY, GEMINI_KEY_KEY, LANG_KEY, STORAGE_PREFIX } from '../storage-keys';
import { AI_PRESETS, readAiConfig, saveAiConfig, validateBaseUrl, validateWebBaseUrl } from './ai-provider-config';
import { AnthropicChatModel } from './anthropic';
import type { JsonChatModel } from './json-chat-model';
import {
  OpenAiCompatibleChatModel, answerText, classifyError, openAiBody, resetTierCache, type Tier,
} from './openai-compat';
import { ANSWER_SCHEMA, LISTING_SCHEMA, OnDeviceAiError, OnDeviceAiService, PLAN_SCHEMA } from './on-device-ai.service';
import { schemaTrailer, toStrictSchema } from './schema-dialect';
import vectors from './parity-vectors.json';

/** TC-U-169 and TC-U-170 (docs/06): the TypeScript side of the AI provider of the person's choice (docs/03 §13.2, ADR-35). */

const SCHEMAS: Record<string, object> = { listing: LISTING_SCHEMA, answer: ANSWER_SCHEMA, plan: PLAN_SCHEMA };

type Step = number | { status: number; body?: string; retryAfter?: string } | 'network';
const OK_BODY = (content: string) => JSON.stringify({ choices: [{ message: { role: 'assistant', content } }] });
const OK = (content = '{"ok":true}'): Step => ({ status: 200, body: OK_BODY(content) });

/** A fetch that answers the given steps in turn and records each call. */
function fakeFetch(...steps: Step[]) {
  const calls: { url: string; init: RequestInit; body: Record<string, any> }[] = [];
  const fetchImpl = async (url: string, init: RequestInit): Promise<Response> => {
    calls.push({ url, init, body: JSON.parse(String(init.body)) });
    const step = steps[Math.min(calls.length - 1, steps.length - 1)];
    if (step === 'network') throw new TypeError('Failed to fetch');
    const s = typeof step === 'number' ? { status: step } : step;
    return new Response(s.body ?? '', { status: s.status, headers: s.retryAfter ? { 'Retry-After': s.retryAfter } : {} });
  };
  return { fetchImpl, calls };
}

const SETTINGS = { baseUrl: 'https://api.example.com/v1', model: 'test-model' };
const model = (f: ReturnType<typeof fakeFetch>, key = 'sk-test-1234', settings = SETTINGS) =>
  new OpenAiCompatibleChatModel(settings, key, f.fetchImpl);
const run = (m: JsonChatModel) => m.generateJson('You answer.', 'Quiet?', ANSWER_SCHEMA, 0.1);
const kindOf = async (p: Promise<unknown>) => {
  try {
    await p;
    return 'no error';
  } catch (e) {
    return e instanceof OnDeviceAiError ? e.kind : `other: ${String(e)}`;
  }
};

beforeEach(() => {
  resetTierCache();
  localStorage.clear();
  sessionStorage.clear();
});
afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

describe('schemaDialect vectors (Gemini dialect to strict JSON Schema)', () => {
  it('schemaDialect vectors: each real schema converts to the strict schema and its trailer', () => {
    expect(vectors.schemaDialect).toHaveLength(3);
    for (const c of vectors.schemaDialect) {
      expect(SCHEMAS[c.name], c.name).toEqual(c.gemini);
      const strict = toStrictSchema(c.gemini);
      expect(strict, c.name).toEqual(c.strict);
      expect(JSON.stringify(strict), `${c.name} key order`).toBe(JSON.stringify(c.strict));
      expect(schemaTrailer(strict), c.name).toBe(c.trailer);
    }
  });

  it('nullable becomes a type that also allows null', () => {
    expect(toStrictSchema({ type: 'string', description: 'd', nullable: true })).toEqual({ type: ['string', 'null'], description: 'd' });
    expect(toStrictSchema({ type: 'string', nullable: false })).toEqual({ type: 'string' });
  });

  it('every object forbids extra properties and requires all its properties, nested ones too', () => {
    const strict = toStrictSchema(ANSWER_SCHEMA) as { required: string[]; additionalProperties: boolean };
    expect(strict.additionalProperties).toBe(false);
    expect(strict.required).toEqual(['answer', 'citedHouseIds']);
    const plan = toStrictSchema(PLAN_SCHEMA) as { required: string[]; properties: { stops: { items: { required: string[]; additionalProperties: boolean } } } };
    expect(plan.required).toEqual(['summary', 'stops']);
    expect(plan.properties.stops.items.required).toEqual(['houseId', 'reason']);
    expect(plan.properties.stops.items.additionalProperties).toBe(false);
  });

  it('does not change the schema it is given', () => {
    const before = JSON.stringify(LISTING_SCHEMA);
    toStrictSchema(LISTING_SCHEMA);
    expect(JSON.stringify(LISTING_SCHEMA)).toBe(before);
  });
});

describe('openaiRequest vectors', () => {
  it('openaiRequest vectors: the body of every call and tier', () => {
    expect(vectors.openaiRequest).toHaveLength(8);
    for (const c of vectors.openaiRequest) {
      const ping = c.call === 'ping';
      const strict = ping ? null : toStrictSchema(SCHEMAS[c.call]);
      const body = openAiBody(c.tier as Tier, c.model, c.call, c.system, c.user, c.temperature, strict, ping ? 5 : 2048);
      expect(body, `${c.call} tier ${c.tier}`).toEqual(c.expected);
    }
  });

  it('only tier 1 is strict, and tiers 2 and 3 carry the shape in the system prompt', () => {
    const strict = toStrictSchema(ANSWER_SCHEMA);
    const t1 = openAiBody(1, 'm', 'answer', 'S', 'U', 0, strict) as { response_format: { json_schema: { strict: boolean } }; messages: { content: string }[] };
    expect(t1.response_format.json_schema.strict).toBe(true);
    expect(t1.messages[0].content).toBe('S');
    const t2 = openAiBody(2, 'm', 'answer', 'S', 'U', 0, strict) as { response_format: unknown; messages: { content: string }[] };
    expect(t2.response_format).toEqual({ type: 'json_object' });
    expect(t2.messages[0].content).toBe(`S\n\n${schemaTrailer(strict)}`);
    expect('response_format' in openAiBody(3, 'm', 'answer', 'S', 'U', 0, strict)).toBe(false);
  });
});

describe('openaiContent vectors', () => {
  it('openaiContent vectors: the answer text, fences and whitespace removed, or nothing', () => {
    expect(vectors.openaiContent).toHaveLength(9);
    for (const c of vectors.openaiContent) {
      const expected = c.expected as { text?: string; error?: string };
      expect(answerText(c.response), c.response).toBe(expected.error ? null : expected.text);
    }
  });

  it('reads the first choice only', () => {
    const body = JSON.stringify({ choices: [{ message: { content: 'first' } }, { message: { content: 'second' } }] });
    expect(answerText(body)).toBe('first');
  });
});

describe('providerErrors vectors', () => {
  it('providerErrors vectors: the next tier or the kind of error for every status and body', () => {
    // The rows without a `provider` are this adapter's; the Anthropic rows are anthropic.spec.ts's.
    const rows = (vectors.providerErrors as { provider?: string; tier: number; status: number; body: string; retryAfter?: string; expected: Record<string, unknown> }[])
      .filter((c) => c.provider === undefined);
    expect(rows).toHaveLength(25);
    for (const c of rows) {
      const got = classifyError(c.tier as Tier, c.status, c.body, c.retryAfter ?? null) as unknown as Record<string, unknown>;
      const label = `tier ${c.tier} status ${c.status} ${c.body}`;
      expect(got['action'], label).toBe(c.expected['action']);
      if (c.expected['action'] === 'nextTier') expect(got['tier'], label).toBe(c.expected['tier']);
      else expect(got['kind'], label).toBe(c.expected['kind']);
      if ('retryAfterSeconds' in c.expected) expect(got['retryAfterSeconds'], label).toBe(c.expected['retryAfterSeconds']);
    }
  });

  it('401 and 403 mean the key was not accepted, whatever the tier', async () => {
    for (const status of [401, 403]) expect(await kindOf(run(model(fakeFetch(status))))).toBe('keyRejected');
  });

  it('404 means the model is not found, and a network failure means unreachable', async () => {
    expect(await kindOf(run(model(fakeFetch(404))))).toBe('modelNotFound');
    expect(await kindOf(run(model(fakeFetch('network'))))).toBe('unreachable');
  });

  it('429 carries the seconds the provider asked for, or a minute when it did not say', async () => {
    const asked = await run(model(fakeFetch({ status: 429, retryAfter: '20' }))).catch((e) => e as OnDeviceAiError);
    expect([(asked as OnDeviceAiError).kind, (asked as OnDeviceAiError).retryAfter]).toEqual(['rateLimited', 20]);
    const silent = await run(model(fakeFetch(429))).catch((e) => e as OnDeviceAiError);
    expect([(silent as OnDeviceAiError).kind, (silent as OnDeviceAiError).retryAfter]).toEqual(['rateLimited', 60]);
  });

  it('a missing choice or a page that is not JSON is unavailable', async () => {
    for (const body of ['{"choices":[]}', 'not json']) {
      expect(await kindOf(run(model(fakeFetch({ status: 200, body }))))).toBe('unavailable');
    }
  });

  it('a refusal is blocked, not unavailable (S4b-BL-232)', async () => {
    const body = '{"choices":[{"message":{"content":null,"refusal":"no"}}]}';
    expect(await kindOf(run(model(fakeFetch({ status: 200, body }))))).toBe('blocked');
  });
});

describe('the fallback ladder', () => {
  const unsupported = { status: 400, body: '{"error":{"message":"response_format json_schema is not supported"}}' };
  const noJsonObject = { status: 400, body: '{"error":{"message":"response_format json_object is not available"}}' };

  it('moves down one tier at a time on a 400 with trigger words, and returns the text with its fence removed', async () => {
    const f = fakeFetch(unsupported, noJsonObject, OK('```json\n{"answer":"Yes","citedHouseIds":[]}\n```'));
    expect(await run(model(f))).toBe('{"answer":"Yes","citedHouseIds":[]}');
    expect(f.calls.map((c) => c.body['response_format']?.type ?? 'none')).toEqual(['json_schema', 'json_object', 'none']);
    expect(f.calls[2].body['messages'][0].content).toContain('Reply with only a JSON object of this shape: ');
  });

  it('does not move down on any 4xx but a 400, even with trigger words', async () => {
    const f = fakeFetch({ status: 422, body: unsupported.body });
    expect(await kindOf(run(model(f)))).toBe('unavailable');
    expect(f.calls).toHaveLength(1);
  });

  it('gives up on a 400 without trigger words, and on a 400 at tier 3', async () => {
    const f = fakeFetch({ status: 400, body: '{"error":"max_tokens is too large"}' });
    expect(await kindOf(run(model(f)))).toBe('unavailable');
    expect(f.calls).toHaveLength(1);
    const g = fakeFetch(unsupported, noJsonObject, { status: 400, body: 'response_format is not supported' });
    expect(await kindOf(run(model(g)))).toBe('unavailable');
    expect(g.calls).toHaveLength(3);
  });

  it('caches the winning tier per base URL and model for the session', async () => {
    const f = fakeFetch(unsupported, OK(), OK(), OK());
    await run(model(f));
    expect(f.calls.map((c) => c.body['response_format']?.type)).toEqual(['json_schema', 'json_object']);
    await run(model(f));
    expect(f.calls).toHaveLength(3);
    expect(f.calls[2].body['response_format']).toEqual({ type: 'json_object' });
    // A changed configuration starts again at tier 1.
    await run(model(f, 'sk-test-1234', { ...SETTINGS, model: 'other-model' }));
    expect(f.calls[3].body['response_format'].type).toBe('json_schema');
  });
});

describe('the request', () => {
  it('posts to the absolute base URL plus /chat/completions with Authorization: Bearer only when a key is set', async () => {
    const withKey = fakeFetch(OK());
    await run(model(withKey, ' sk-test-1234 '));
    const [call] = withKey.calls;
    expect(call.url).toBe('https://api.example.com/v1/chat/completions');
    expect(call.init.method).toBe('POST');
    expect(call.init.headers).toEqual({ 'Content-Type': 'application/json', Authorization: 'Bearer sk-test-1234' });
    expect(call.url).not.toContain('sk-test');
    const withoutKey = fakeFetch(OK());
    await run(model(withoutKey, '  '));
    expect(withoutKey.calls[0].init.headers).toEqual({ 'Content-Type': 'application/json' });
  });

  it('follows no redirect (manual), sends no cookies, and names the schema it was given', async () => {
    const f = fakeFetch(OK());
    await model(f).generateJson('S', 'U', PLAN_SCHEMA, 0.2);
    expect(f.calls[0].init).toMatchObject({ redirect: 'manual', credentials: 'omit' });
    expect(f.calls[0].body['response_format'].json_schema.name).toBe('plan');
    expect(f.calls[0].body['temperature']).toBe(0.2);
    expect(f.calls[0].body['max_tokens']).toBe(2048);
  });

  it('calls out to a normalised base URL, and to nothing that is not a valid one', async () => {
    const f = fakeFetch(OK());
    await run(model(f, '', { baseUrl: ' HTTPS://API.Example.com/v1/ ', model: 'm' }));
    expect(f.calls[0].url).toBe('https://api.example.com/v1/chat/completions');
    for (const baseUrl of ['http://192.168.1.20:11434/v1', 'https://user:pw@example.com/v1', '']) {
      const g = fakeFetch(OK());
      expect(await kindOf(run(model(g, 'k', { baseUrl, model: 'm' })))).toBe('unavailable');
      expect(await kindOf(model(g, 'k', { baseUrl, model: 'm' }).ping())).toBe('unavailable');
      expect(g.calls).toHaveLength(0);
    }
  });

  it('ping asks for 5 tokens and no format, and tells a wrong model, key and URL apart', async () => {
    const f = fakeFetch(OK());
    await model(f).ping();
    expect(f.calls[0].body).toEqual({
      model: 'test-model', temperature: 0, max_tokens: 5,
      messages: [{ role: 'system', content: 'Reply with {"ok": true}.' }, { role: 'user', content: 'ping' }],
    });
    expect(await kindOf(model(fakeFetch(404)).ping())).toBe('modelNotFound');
    expect(await kindOf(model(fakeFetch(401)).ping())).toBe('keyRejected');
    expect(await kindOf(model(fakeFetch('network')).ping())).toBe('unreachable');
    expect(await kindOf(model(fakeFetch(400)).ping())).toBe('unavailable');
    expect(await kindOf(model(fakeFetch({ status: 200, body: '{"choices":[]}' })).ping())).toBe('unavailable');
  });
});

describe('baseUrl vectors and the presets', () => {
  it('baseUrl vectors: the reason or the normalised URL and host of every input', () => {
    expect(vectors.baseUrl).toHaveLength(34);
    for (const c of vectors.baseUrl as { input: string; android?: boolean; expected: { valid: boolean; reason?: string; normalised?: string; host?: string } }[]) {
      expect(validateBaseUrl(c.input, c.android === true), c.input).toEqual(c.expected);
    }
  });

  it('the website never accepts 10.0.2.2 over http (the emulator is Android only)', () => {
    expect(validateBaseUrl('http://10.0.2.2:11434/v1')).toEqual({ valid: false, reason: 'insecureHost' });
    expect(validateBaseUrl('https://10.0.2.2/v1').valid).toBe(true);
  });

  it('the presets hold the default base URLs of ADR-35, each of them valid, and Custom is empty', () => {
    expect(Object.fromEntries(AI_PRESETS.map((p) => [p.id, p.baseUrl]))).toEqual({
      openai: 'https://api.openai.com/v1',
      openrouter: 'https://openrouter.ai/api/v1',
      groq: 'https://api.groq.com/openai/v1',
      ollama: 'http://localhost:11434/v1',
      lmstudio: 'http://localhost:1234/v1',
      custom: '',
    });
    for (const p of AI_PRESETS.filter((x) => x.id !== 'custom')) expect(validateBaseUrl(p.baseUrl).valid, p.id).toBe(true);
    expect(AI_PRESETS.filter((p) => p.keyOptional).map((p) => p.id)).toEqual(['ollama', 'lmstudio']);
  });
});

describe('where the choice is kept (TC-U-170)', () => {
  function create(connected = false): AiService {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    if (connected) TestBed.inject(ConfigService).save({ baseUrl: 'https://a.example.org', apiKey: 'dpk_x' }, false);
    return TestBed.inject(AiService);
  }

  it('the three keys carry the doorprints. prefix', () => {
    for (const key of [AI_KIND_KEY, AI_BASE_URL_KEY, AI_MODEL_KEY]) expect(key.startsWith(STORAGE_PREFIX), key).toBe(true);
    expect(new Set([AI_KIND_KEY, AI_BASE_URL_KEY, AI_MODEL_KEY, GEMINI_KEY_KEY, AI_PROVIDER_KEY]).size).toBe(5);
  });

  it('migration: a saved own Gemini key with provider "device" reads as kind gemini, and nothing is rewritten', () => {
    localStorage.setItem(AI_PROVIDER_KEY, 'device');
    localStorage.setItem(GEMINI_KEY_KEY, 'AIzaTestKey1234');
    localStorage.setItem(AI_OPT_IN_KEY, '1');
    const ai = create(true);
    expect(ai.aiConfig().kind).toBe('gemini');
    expect(ai.usesOwnKey()).toBe(true);
    expect(localStorage.getItem(AI_KIND_KEY)).toBeNull();
    expect(localStorage.getItem(AI_BASE_URL_KEY)).toBeNull();
    expect(localStorage.getItem(AI_MODEL_KEY)).toBeNull();
  });

  it('migration: someone on the server stays on the server', () => {
    const ai = create(true);
    expect(ai.provider()).toBe('server');
    expect(ai.usesOwnKey()).toBe(false);
  });

  it('saves the kind, the base URL (normalised) and the model, and reads them back', () => {
    const saved = saveAiConfig({ kind: 'openai-compatible', baseUrl: ' HTTPS://Api.Example.com/v1/ ', model: ' gpt-x ' });
    expect(saved).toEqual({ kind: 'openai-compatible', baseUrl: 'https://api.example.com/v1', model: 'gpt-x' });
    expect(readAiConfig()).toEqual(saved);
    expect(localStorage.getItem(AI_KIND_KEY)).toBe('openai-compatible');
    localStorage.setItem(AI_KIND_KEY, 'something-else');
    expect(readAiConfig().kind).toBe('gemini');
  });

  it('removing the key removes the three settings with it, and the choice falls back to the server', () => {
    const ai = create(true);
    ai.setAiConfig({ kind: 'openai-compatible', baseUrl: 'http://localhost:11434/v1', model: 'llama' });
    ai.saveGeminiKey('local-key-123', true);
    ai.removeGeminiKey();
    for (const key of [AI_KIND_KEY, AI_BASE_URL_KEY, AI_MODEL_KEY, GEMINI_KEY_KEY]) expect(localStorage.getItem(key), key).toBeNull();
    expect(ai.aiConfig()).toEqual({ kind: 'gemini', baseUrl: '', model: '' });
    expect(ai.provider()).toBe('server');
  });

  it('"Remove all data" sweeps the three settings and keeps only the language and the server settings', () => {
    saveAiConfig({ kind: 'openai-compatible', baseUrl: 'https://api.example.com/v1', model: 'm' });
    localStorage.setItem(LANG_KEY, 'ta');
    clearLocalLeftovers(localStorage);
    for (const key of [AI_KIND_KEY, AI_BASE_URL_KEY, AI_MODEL_KEY]) expect(localStorage.getItem(key), key).toBeNull();
    expect(localStorage.getItem(LANG_KEY)).toBe('ta');
  });

  it('nothing of the AI settings or the key reaches the backup JSON or the backup file', () => {
    const ai = create(false);
    ai.setAiConfig({ kind: 'openai-compatible', baseUrl: 'https://llm.secret-host.example/v1', model: 'secret-model-x' });
    ai.saveGeminiKey('sk-secret-key-9876', true);
    const json = backupJson(buildBackupData(fixtureBundle()));
    const zip = new TextDecoder().decode(buildBackupZip(fixtureBundle(), new Map(), 'x', new Date('2026-10-07T00:00:00Z')));
    for (const text of [json, zip]) {
      for (const leak of ['baseUrl', 'apiKey', 'gemini', 'secret-host', 'secret-model-x', 'sk-secret-key-9876', 'ai-kind']) {
        expect(text.toLowerCase().includes(leak.toLowerCase()), leak).toBe(false);
      }
    }
  });
});

describe('AiService with the person\'s own provider', () => {
  const ASK = { answer: 'local', citations: [], grounded: false, retrieved: 0 };

  function create(onDevice: Record<string, unknown>): AiService {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: OnDeviceAiService, useValue: onDevice }],
    });
    return TestBed.inject(AiService);
  }

  it('answers with an OpenAI-compatible model when one is chosen, with no key for a local server', async () => {
    const onDevice = { ask: vi.fn(async (..._a: unknown[]) => ASK), test: vi.fn(async (..._a: unknown[]) => undefined) };
    const ai = create(onDevice);
    ai.setOptIn(true);
    expect(ai.usesOwnKey()).toBe(false);
    ai.setAiConfig({ kind: 'openai-compatible', baseUrl: 'http://localhost:11434/v1', model: 'llama3' });
    ai.setProvider('device');
    expect(ai.usesOwnKey()).toBe(true);
    expect(ai.enabled()).toBe(true);
    await new Promise<void>((resolve) => ai.ask('Quiet?').subscribe(() => resolve()));
    expect(onDevice.ask.mock.calls[0][0]).toBeInstanceOf(OpenAiCompatibleChatModel);
    await ai.testProvider(ai.aiConfig(), '');
    expect(onDevice.test.mock.calls[0][0]).toBeInstanceOf(OpenAiCompatibleChatModel);
  });

  it('answers with Anthropic when it is chosen and a model and a key are saved, and builds its adapter for a test', async () => {
    const onDevice = { ask: vi.fn(async (..._a: unknown[]) => ASK), test: vi.fn(async (..._a: unknown[]) => undefined) };
    const ai = create(onDevice);
    ai.setOptIn(true);
    ai.setProvider('device');
    ai.setAiConfig({ kind: 'anthropic', baseUrl: 'https://api.anthropic.com', model: 'my-model' });
    expect(ai.usesOwnKey(), 'no key yet').toBe(false);
    ai.saveGeminiKey('test-key-not-real', false);
    expect(ai.usesOwnKey()).toBe(true);
    expect(ai.ownHost()).toBe('api.anthropic.com');
    await new Promise<void>((resolve) => ai.ask('Quiet?').subscribe(() => resolve()));
    expect(onDevice.ask.mock.calls[0][0]).toBeInstanceOf(AnthropicChatModel);
    await ai.testProvider(ai.aiConfig(), 'test-key-not-real');
    expect(onDevice.test.mock.calls[0][0]).toBeInstanceOf(AnthropicChatModel);
  });

  it('is not ready without a valid base URL and a model', () => {
    const ai = create({});
    ai.setProvider('device');
    ai.setAiConfig({ kind: 'openai-compatible', baseUrl: 'http://192.168.1.2/v1', model: 'm' });
    expect(ai.usesOwnKey()).toBe(false);
    ai.setAiConfig({ kind: 'openai-compatible', baseUrl: 'https://api.example.com/v1', model: ' ' });
    expect(ai.usesOwnKey()).toBe(false);
    ai.saveGeminiKey('AIzaTestKey1234', false);
    ai.setAiConfig({ kind: 'anthropic', baseUrl: 'http://example.com', model: 'm' });
    expect(ai.usesOwnKey()).toBe(false);
    ai.setAiConfig({ kind: 'anthropic', baseUrl: '', model: ' ' });
    expect(ai.usesOwnKey()).toBe(false);
    ai.setAiConfig({ kind: 'gemini', baseUrl: '', model: '' });
    expect(ai.usesOwnKey()).toBe(true);
  });
});

describe('OnDeviceAiService depends on the interface only', () => {
  it('asks any JsonChatModel with the answer schema at temperature 0.1', async () => {
    const generateJson = vi.fn(async () => '{"answer":"House h1 is quiet [house:h1].","citedHouseIds":["h1"]}');
    const fake: JsonChatModel = { generateJson, ping: async () => undefined };
    const house = {
      id: 'h1', label: 'House h1', address: null, street: null, locality: 'Indiranagar', lat: 12.97, lon: 77.64, status: 'SHORTLISTED',
      price: 25000, priceType: 'RENT', bedrooms: 2, rating: 4, contactName: null, contactPhone: null, listingUrl: null,
      notes: 'Quiet street', checklist: null, updatedAt: '2026-09-20T10:00:00Z', deleted: false,
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: LocalStore, useValue: { allHouses: async () => [house], allVisits: async () => [], viewings: { all: async () => [] }, areas: { all: async () => [] }, places: { all: async () => [] }, areaNotes: { rows: async () => [] } } },
      ],
    });
    const res = await TestBed.inject(OnDeviceAiService).ask(fake, 'Which house is quiet?');
    expect(res.citations.map((c) => c.houseId)).toEqual(['h1']);
    expect(generateJson).toHaveBeenCalledOnce();
    expect(generateJson.mock.calls[0] as unknown[]).toEqual([expect.any(String), expect.any(String), ANSWER_SCHEMA, 0.1]);
  });
});

describe('validateWebBaseUrl (the website refuses [::1]: its CSP cannot name an IPv6 literal)', () => {
  it('refuses http://[::1] as an insecure host, and leaves every other answer as the shared rules give it', () => {
    expect(validateWebBaseUrl('http://[::1]:11434/v1')).toEqual({ valid: false, reason: 'insecureHost' });
    expect(validateBaseUrl('http://[::1]:11434/v1').valid).toBe(true);
    expect(validateWebBaseUrl('http://localhost:11434/v1')).toEqual(validateBaseUrl('http://localhost:11434/v1'));
    expect(validateWebBaseUrl('http://127.0.0.1:1234/v1').valid).toBe(true);
    expect(validateWebBaseUrl('https://api.openai.com/v1')).toEqual(validateBaseUrl('https://api.openai.com/v1'));
    expect(validateWebBaseUrl('https://[::1]/v1').valid).toBe(true); // https: the CSP allows any https origin
  });
});
