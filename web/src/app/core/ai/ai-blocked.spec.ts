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

import { provideHttpClient, HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { aiErrorMsg } from '../ai.service';
import { isBlockedResponse } from './ai-blocked';
import { AnthropicChatModel, resetToolChoiceCache } from './anthropic';
import { OpenAiCompatibleChatModel, resetTierCache } from './openai-compat';
import { ANSWER_SCHEMA, GEMINI_URL, GeminiChatModel, OnDeviceAiError } from './on-device-ai.service';
import vectors from './parity-vectors.json';

/**
 * A response the provider's own safety system blocked is the `blocked` failure (S4b-BL-232, docs/ai/ai-design.md 15): found
 * in the shared vectors (`blockedResponses`), not retried, not walked down the structured-output ladder, and the provider's
 * words are in neither the error nor the message shown. A normal answer, a 429 and a 500 are what they were. The Kotlin
 * side runs the same vectors in `AiBlockedTest`.
 */

const SENTINEL = 'SENTINEL-PROVIDER-WORDS';
type Case = { provider: string; status: number; body: string; expected: { blocked: boolean }; note: string };
const cases = vectors.blockedResponses as Case[];

const GEMINI_BLOCKED = { promptFeedback: { blockReason: 'SAFETY' } };
const GEMINI_SAFETY = { candidates: [{ finishReason: 'SAFETY' }] };
const GEMINI_OK = { candidates: [{ content: { parts: [{ text: '{"ok":true}' }] }, finishReason: 'STOP' }] };
const OPENAI_FILTER = JSON.stringify({ choices: [{ index: 0, finish_reason: 'content_filter', message: { role: 'assistant', content: null } }] });
const OPENAI_POLICY = JSON.stringify({ error: { message: `rejected, response_format ${SENTINEL}`, code: 'content_policy_violation' } });
const OPENAI_OK = JSON.stringify({ choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: '{"ok":true}' } }] });
const ANTHROPIC_REFUSAL = JSON.stringify({ type: 'message', role: 'assistant', stop_reason: 'refusal', content: [{ type: 'text', text: SENTINEL }], stop_details: { type: 'refusal', explanation: SENTINEL } });
const ANTHROPIC_OK = JSON.stringify({ type: 'message', role: 'assistant', stop_reason: 'tool_use', content: [{ type: 'tool_use', id: 't', name: 'answer', input: { ok: true } }] });

type Step = { status: number; body: string };
/** A fetch that answers the steps in turn and counts the calls. */
function fakeFetch(...steps: Step[]) {
  let calls = 0;
  const fetchImpl = async (_url: string, _init: RequestInit): Promise<Response> => {
    const step = steps[Math.min(calls, steps.length - 1)];
    calls++;
    return new Response(step.body, { status: step.status });
  };
  return { fetchImpl, calls: () => calls };
}
const failure = async (p: Promise<unknown>): Promise<OnDeviceAiError> => {
  try {
    await p;
  } catch (e) {
    return e as OnDeviceAiError;
  }
  throw new Error('no error');
};
const openAi = (f: ReturnType<typeof fakeFetch>) =>
  new OpenAiCompatibleChatModel({ baseUrl: 'https://api.example.com/v1', model: 'm' }, 'k', f.fetchImpl).generateJson('s', 'u', ANSWER_SCHEMA, 0);
const anthropic = (f: ReturnType<typeof fakeFetch>) =>
  new AnthropicChatModel({ baseUrl: 'https://api.anthropic.com', model: 'm' }, 'key-not-real', f.fetchImpl).generateJson('s', 'u', ANSWER_SCHEMA, 0);

beforeEach(() => {
  resetTierCache();
  resetToolChoiceCache();
});

describe('blockedResponses vectors', () => {
  it('blockedResponses vectors: every provider response is blocked or not, as the table says', () => {
    expect(cases.length).toBeGreaterThanOrEqual(40);
    expect(new Set(cases.map((c) => c.provider))).toEqual(new Set(['gemini', 'openai', 'anthropic']));
    for (const c of cases) expect(isBlockedResponse(c.provider, c.status, c.body), `${c.provider} ${c.status} ${c.note}`).toBe(c.expected.blocked);
  });
});

describe('a blocked answer from each adapter', () => {
  async function gemini(reply: object): Promise<{ result: string | OnDeviceAiError; calls: number }> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const model = new GeminiChatModel(TestBed.inject(HttpClient), 'AIzaTestKey1234');
    const done = model.generateJson('s', 'u', ANSWER_SCHEMA, 0).then((r) => r, (e) => e as OnDeviceAiError);
    const req = TestBed.inject(HttpTestingController).expectOne(GEMINI_URL);
    req.flush(reply);
    const result = await done;
    TestBed.inject(HttpTestingController).verify();
    return { result, calls: 1 };
  }

  it('Gemini: a blocked prompt and a SAFETY finish are blocked, with one request each', async () => {
    for (const reply of [GEMINI_BLOCKED, GEMINI_SAFETY]) {
      const { result } = await gemini(reply);
      expect(result instanceof OnDeviceAiError && result.kind, JSON.stringify(reply)).toBe('blocked');
      expect((result as OnDeviceAiError).message).not.toContain(SENTINEL);
    }
  });

  it('OpenAI-compatible: finish_reason content_filter is blocked after one call, not walked down the ladder', async () => {
    const f = fakeFetch({ status: 200, body: OPENAI_FILTER }, { status: 200, body: OPENAI_OK });
    expect((await failure(openAi(f))).kind).toBe('blocked');
    expect(f.calls()).toBe(1);
  });

  it('OpenAI-compatible: a 400 content_policy_violation is blocked after one call even when its text names response_format', async () => {
    const f = fakeFetch({ status: 400, body: OPENAI_POLICY }, { status: 200, body: OPENAI_OK });
    const e = await failure(openAi(f));
    expect(e.kind).toBe('blocked');
    expect(e.message).not.toContain(SENTINEL);
    expect(f.calls()).toBe(1);
  });

  it('OpenAI-compatible: a message.refusal is blocked', async () => {
    const body = JSON.stringify({ choices: [{ message: { content: null, refusal: `no ${SENTINEL}` } }] });
    const f = fakeFetch({ status: 200, body });
    const e = await failure(openAi(f));
    expect(e.kind).toBe('blocked');
    expect(e.message).not.toContain(SENTINEL);
  });

  it('Anthropic: stop_reason refusal is blocked after one call', async () => {
    const f = fakeFetch({ status: 200, body: ANTHROPIC_REFUSAL }, { status: 200, body: ANTHROPIC_OK });
    const e = await failure(anthropic(f));
    expect(e.kind).toBe('blocked');
    expect(e.message).not.toContain(SENTINEL);
    expect(f.calls()).toBe(1);
  });

  it('a normal answer, a 429 and a 500 are what they were', async () => {
    expect(await openAi(fakeFetch({ status: 200, body: OPENAI_OK }))).toBe('{"ok":true}');
    expect(await anthropic(fakeFetch({ status: 200, body: ANTHROPIC_OK }))).toBe('{"ok":true}');
    expect((await gemini(GEMINI_OK)).result).toBe('{"ok":true}');
    for (const [status, kind] of [[429, 'rateLimited'], [500, 'unavailable']] as const) {
      expect((await failure(openAi(fakeFetch({ status, body: '{}' })))).kind, `openai ${status}`).toBe(kind);
      expect((await failure(anthropic(fakeFetch({ status, body: '{}' })))).kind, `anthropic ${status}`).toBe(kind);
    }
  });
});

describe('the words for a blocked answer', () => {
  it('are one message, with no parameter that could carry the provider\'s text', () => {
    expect(aiErrorMsg(new OnDeviceAiError('blocked'))).toEqual({ key: 'ai.blocked' });
  });
});
