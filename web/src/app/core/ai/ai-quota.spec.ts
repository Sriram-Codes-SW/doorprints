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

import { HttpClient, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { aiErrorMsg } from '../ai.service';
import { classifyStatus } from './ai-request';
import { isQuotaExhausted } from './ai-quota';
import { AnthropicChatModel, resetToolChoiceCache } from './anthropic';
import { OpenAiCompatibleChatModel, resetTierCache } from './openai-compat';
import { ANSWER_SCHEMA, GEMINI_URL, GeminiChatModel, OnDeviceAiError } from './on-device-ai.service';
import vectors from './parity-vectors.json';

/**
 * A key whose free quota or credit is used up is the `quotaExhausted` failure (S4b-BL-243, docs/ai/ai-design.md 2.1), not a
 * wait of a minute and not "unavailable": an HTTP 402, a Gemini 429 with a `PerDay` quota id, an OpenAI-compatible
 * `insufficient_quota`, an Anthropic billing error. A per-minute 429 stays `rateLimited`. The Kotlin side runs the same
 * vectors (`quotaResponses`) in `AiQuotaTest`.
 */

type Case = { provider: string; status: number; body: string; expected: { quota: boolean }; note: string };
const cases = vectors.quotaResponses as Case[];
const row = (note: string): Case => cases.find((c) => c.note === note) as Case;

type Step = { status: number; body: string; retryAfter?: string };
let calls = 0;
function fakeFetch(step: Step) {
  const fetchImpl = async (_url: string, _init: RequestInit): Promise<Response> => {
    calls++;
    return new Response(step.body, { status: step.status, headers: step.retryAfter ? { 'Retry-After': step.retryAfter } : {} });
  };
  return fetchImpl;
}
const failure = async (p: Promise<unknown>): Promise<OnDeviceAiError> => {
  try {
    await p;
  } catch (e) {
    return e as OnDeviceAiError;
  }
  throw new Error('no error');
};
const openAi = (c: Case) =>
  new OpenAiCompatibleChatModel({ baseUrl: 'https://api.example.com/v1', model: 'm' }, 'k', fakeFetch(c)).generateJson('s', 'u', ANSWER_SCHEMA, 0);
const anthropic = (c: Case) =>
  new AnthropicChatModel({ baseUrl: 'https://api.anthropic.com', model: 'm' }, 'key-not-real', fakeFetch(c)).generateJson('s', 'u', ANSWER_SCHEMA, 0);

beforeEach(() => {
  calls = 0;
  resetTierCache();
  resetToolChoiceCache();
});

describe('quotaResponses vectors', () => {
  it('quotaResponses vectors: every provider reply says the quota is used up or not, as the table says', () => {
    expect(cases.length).toBeGreaterThanOrEqual(19);
    expect(new Set(cases.map((c) => c.provider))).toEqual(new Set(['gemini', 'openai', 'anthropic']));
    for (const c of cases) expect(isQuotaExhausted(c.status, c.body), `${c.provider} ${c.status} ${c.note}`).toBe(c.expected.quota);
  });

  it('classifyStatus names the kind: quotaExhausted for a spent quota, rateLimited with its seconds for a wait', () => {
    expect(classifyStatus(402)).toEqual({ action: 'error', kind: 'quotaExhausted' });
    expect(classifyStatus(429, '30', row('Gemini 429 with a PerDay quotaId').body)).toEqual({ action: 'error', kind: 'quotaExhausted' });
    expect(classifyStatus(429, '30', row('Gemini 429 with a PerMinute quotaId waits').body)).toEqual({ action: 'error', kind: 'rateLimited', retryAfterSeconds: 30 });
    expect(classifyStatus(429, null)).toEqual({ action: 'error', kind: 'rateLimited', retryAfterSeconds: null });
    expect(classifyStatus(401, null, row('OpenAI insufficient_quota (code and type)').body)).toEqual({ action: 'error', kind: 'keyRejected' });
  });
});

describe('a spent quota from each adapter', () => {
  it('OpenAI-compatible: 402 and insufficient_quota are quotaExhausted, after one call each', async () => {
    for (const note of ['OpenAI insufficient_quota (code and type)', 'OpenAI-compatible 402 (OpenRouter style)', '402 with no body']) {
      expect((await failure(openAi(row(note)))).kind, note).toBe('quotaExhausted');
    }
  });

  it('OpenAI-compatible: a 400 insufficient_quota is not walked down the structured-output ladder', async () => {
    const body = JSON.stringify({ error: { message: 'response_format json_schema not supported; quota', code: 'insufficient_quota' } });
    expect((await failure(openAi({ ...row('type alone'), status: 400, body }))).kind).toBe('quotaExhausted');
    expect(calls, 'asked once, not again at a simpler tier').toBe(1);
  });

  it('OpenAI-compatible: a plain rate limit still waits, with the seconds it asked for', async () => {
    const e = await failure(
      new OpenAiCompatibleChatModel({ baseUrl: 'https://api.example.com/v1', model: 'm' }, 'k', fakeFetch({ ...row('OpenAI rate_limit_exceeded waits'), retryAfter: '20' })).generateJson('s', 'u', ANSWER_SCHEMA, 0),
    );
    expect([e.kind, e.retryAfter]).toEqual(['rateLimited', 20]);
  });

  it('Anthropic: a billing_error and a low credit balance are quotaExhausted; an overloaded 529 is still unavailable', async () => {
    expect((await failure(anthropic(row('Anthropic billing_error 402')))).kind).toBe('quotaExhausted');
    expect((await failure(anthropic(row('Anthropic low credit 400')))).kind).toBe('quotaExhausted');
    expect((await failure(anthropic(row('billing_error type on its own')))).kind).toBe('quotaExhausted');
    expect((await failure(anthropic(row('Anthropic 529')))).kind).toBe('unavailable');
    expect((await failure(anthropic(row('Anthropic 429 waits')))).kind).toBe('rateLimited');
  });

  describe('Gemini', () => {
    async function gemini(status: number, body: string): Promise<OnDeviceAiError> {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
      const model = new GeminiChatModel(TestBed.inject(HttpClient), 'AIzaTestKey1234');
      const done = failure(model.generateJson('s', 'u', ANSWER_SCHEMA, 0));
      TestBed.inject(HttpTestingController).expectOne(GEMINI_URL).flush(JSON.parse(body), { status, statusText: 'err' });
      return done;
    }

    it('a 429 with a PerDay quota id is quotaExhausted; a PerMinute one waits a minute', async () => {
      expect((await gemini(429, row('Gemini 429 with a PerDay quotaId').body)).kind).toBe('quotaExhausted');
      const wait = await gemini(429, row('Gemini 429 with a PerMinute quotaId waits').body);
      expect([wait.kind, wait.retryAfter]).toEqual(['rateLimited', 60]);
      expect((await gemini(429, row('Gemini 429 without details waits').body)).kind).toBe('rateLimited');
    });

    it('a 402 is quotaExhausted and an invalid key is still keyRejected', async () => {
      expect((await gemini(402, '{"error":{"message":"payment required"}}')).kind).toBe('quotaExhausted');
      expect((await gemini(400, row('Gemini 400 invalid key').body)).kind).toBe('keyRejected');
    });
  });
});

describe('the words for a spent quota', () => {
  it('are one message with no parameter, and not the rate-limit or provider-down words', () => {
    expect(aiErrorMsg(new OnDeviceAiError('quotaExhausted'))).toEqual({ key: 'ai.quotaExhausted' });
  });
});
