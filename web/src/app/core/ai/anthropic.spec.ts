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

import { describe, expect, it } from 'vitest';
import { classifyStatus } from './ai-request';
import { AnthropicChatModel, anthropicBody, anthropicInput, anthropicPingAnswered } from './anthropic';
import type { JsonChatModel } from './json-chat-model';
import { ANSWER_SCHEMA, LISTING_SCHEMA, OnDeviceAiError, PLAN_SCHEMA } from './on-device-ai.service';
import { toStrictSchema } from './schema-dialect';
import vectors from './parity-vectors.json';

/**
 * TC-U-172 (docs/06): the TypeScript side of the Anthropic adapter (S4b-BL-152, docs/03 §13.2, ADR-35): the request
 * vectors, the content vectors, the error vectors, and the adapter through a fake `fetch` (address, headers, forced
 * tool, key rules, statuses). The Kotlin side runs the same vectors in `AiProviderVectorsTest`.
 */

const SCHEMAS: Record<string, object> = { listing: LISTING_SCHEMA, answer: ANSWER_SCHEMA, plan: PLAN_SCHEMA };
const KEY = 'test-key-not-real';
const ANSWER = '{"answer":"Yes","citedHouseIds":[]}';

type Step = number | { status: number; body?: string; retryAfter?: string } | 'network';
const message = (input: unknown, stop = 'tool_use') =>
  JSON.stringify({ type: 'message', role: 'assistant', stop_reason: stop, content: [{ type: 'tool_use', id: 't1', name: 'answer', input }] });
const OK: Step = { status: 200, body: message(JSON.parse(ANSWER)) };
const PING_OK: Step = { status: 200, body: JSON.stringify({ type: 'message', stop_reason: 'max_tokens', content: [{ type: 'text', text: '{"ok"' }] }) };

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

const model = (f: ReturnType<typeof fakeFetch>, key = KEY, settings = { baseUrl: 'https://api.anthropic.com', model: 'test-model' }) =>
  new AnthropicChatModel(settings, key, f.fetchImpl);
const run = (m: JsonChatModel) => m.generateJson('You answer.', 'Quiet?', ANSWER_SCHEMA, 0.1);
const kindOf = async (p: Promise<unknown>) => {
  try {
    await p;
    return 'no error';
  } catch (e) {
    return e instanceof OnDeviceAiError ? e.kind : `other: ${String(e)}`;
  }
};

describe('anthropicRequest vectors', () => {
  it('anthropicRequest vectors: the body of every call, the ping without a tool', () => {
    expect(vectors.anthropicRequest.map((c) => c.call)).toEqual(['listing', 'answer', 'plan', 'ping']);
    for (const c of vectors.anthropicRequest) {
      const ping = c.call === 'ping';
      const strict = ping ? null : toStrictSchema(SCHEMAS[c.call]);
      const body = anthropicBody(c.call, c.model, c.system, c.user, c.temperature, strict, ping ? 5 : 2048);
      expect(body, c.call).toEqual(c.expected);
      expect(JSON.stringify(body), `${c.call} key order`).toBe(JSON.stringify(c.expected));
    }
  });
});

describe('anthropicContent vectors', () => {
  it('anthropicContent vectors: the first tool call\'s input as text, or nothing', () => {
    expect(vectors.anthropicContent.length).toBeGreaterThanOrEqual(16);
    for (const c of vectors.anthropicContent) {
      const expected = c.expected as { text?: string; ok?: boolean; error?: string };
      if (c.call === 'ping') expect(anthropicPingAnswered(c.response), c.response).toBe(expected.error === undefined);
      else expect(anthropicInput(c.response), c.response).toBe(expected.error ? null : expected.text);
    }
  });
});

describe('anthropic error vectors (the Anthropic rows of providerErrors)', () => {
  it('providerErrors vectors: the kind for every status, 529 included, and no ladder', () => {
    const rows = (vectors.providerErrors as { provider?: string; status: number; body: string; retryAfter?: string; expected: Record<string, unknown> }[])
      .filter((c) => c.provider === 'anthropic');
    expect(rows).toHaveLength(12);
    for (const c of rows) {
      const got = classifyStatus(c.status, c.retryAfter ?? null) as unknown as Record<string, unknown>;
      const label = `status ${c.status} ${c.retryAfter ?? ''}`;
      expect(got['action'], label).toBe('error');
      expect(got['kind'], label).toBe(c.expected['kind']);
      if ('retryAfterSeconds' in c.expected) expect(got['retryAfterSeconds'], label).toBe(c.expected['retryAfterSeconds']);
    }
  });
});

describe('the request', () => {
  it('posts to {base}/v1/messages with the key and version headers and the browser header, never the key in the URL', async () => {
    const f = fakeFetch(OK);
    expect(await run(model(f, ` ${KEY} `))).toBe(ANSWER);
    const [call] = f.calls;
    expect(call.url).toBe('https://api.anthropic.com/v1/messages');
    expect(call.init.method).toBe('POST');
    expect(call.init.headers).toEqual({
      'Content-Type': 'application/json',
      'x-api-key': KEY,
      'anthropic-version': '2023-06-01',
      'anthropic-dangerous-direct-browser-access': 'true',
    });
    expect(call.url).not.toContain(KEY);
    expect(call.init).toMatchObject({ redirect: 'error', credentials: 'omit' });
  });

  it('forces the answer through one tool named for the call', async () => {
    const f = fakeFetch(OK);
    await model(f).generateJson('S', 'U', PLAN_SCHEMA, 0.2);
    expect(f.calls[0].body['tool_choice']).toEqual({ type: 'tool', name: 'plan' });
    expect(f.calls[0].body['tools'].map((t: { name: string }) => t.name)).toEqual(['plan']);
    // The tool takes the strict schema the vectors hold for this call, not the Gemini-dialect one.
    expect(f.calls[0].body['tools'][0].input_schema).toEqual(vectors.schemaDialect.find((c) => c.name === 'plan')!.strict);
    expect(f.calls[0].body['temperature']).toBe(0.2);
  });

  it('uses the address it is given, normalised, and calls nothing for one that is not valid', async () => {
    const f = fakeFetch(OK);
    await run(model(f, KEY, { baseUrl: ' HTTPS://Proxy.Example.com/anthropic/ ', model: 'm' }));
    expect(f.calls[0].url).toBe('https://proxy.example.com/anthropic/v1/messages');
    const defaulted = fakeFetch(OK);
    await run(model(defaulted, KEY, { baseUrl: '', model: 'm' }));
    expect(defaulted.calls[0].url).toBe('https://api.anthropic.com/v1/messages');
    for (const baseUrl of ['http://example.com', 'https://user:pw@example.com']) {
      const g = fakeFetch(OK);
      expect(await kindOf(run(model(g, KEY, { baseUrl, model: 'm' }))), baseUrl).toBe('unavailable');
      expect(g.calls).toHaveLength(0);
    }
  });

  it('needs a key: without one nothing is sent and the call is a refused key', async () => {
    for (const blank of ['', '   ']) {
      const f = fakeFetch(OK);
      expect(await kindOf(run(model(f, blank))), `key '${blank}'`).toBe('keyRejected');
      expect(await kindOf(model(f, blank).ping()), `ping '${blank}'`).toBe('keyRejected');
      expect(f.calls).toHaveLength(0);
    }
  });

  it('returns the tool call\'s input, not the text around it', async () => {
    const noisy = JSON.stringify({
      stop_reason: 'tool_use',
      content: [{ type: 'text', text: 'Let me look.' }, { type: 'tool_use', id: 't1', name: 'answer', input: JSON.parse(ANSWER) }],
    });
    expect(await run(model(fakeFetch({ status: 200, body: noisy })))).toBe(ANSWER);
  });
});

describe('the errors', () => {
  it('words each status for a generate and for a ping', async () => {
    const cases: [Step, string][] = [
      [401, 'keyRejected'], [403, 'keyRejected'], [404, 'modelNotFound'], [429, 'rateLimited'],
      [400, 'unavailable'], [500, 'unavailable'], [{ status: 529, body: '{"type":"error","error":{"type":"overloaded_error"}}' }, 'unavailable'],
      ['network', 'unreachable'],
    ];
    for (const [step, kind] of cases) {
      expect(await kindOf(run(model(fakeFetch(step)))), JSON.stringify(step)).toBe(kind);
      expect(await kindOf(model(fakeFetch(step)).ping()), `ping ${JSON.stringify(step)}`).toBe(kind);
    }
  });

  it('429 carries the seconds the provider asked for, or a minute when it did not say', async () => {
    const asked = (await run(model(fakeFetch({ status: 429, retryAfter: '20' }))).catch((e) => e)) as OnDeviceAiError;
    expect([asked.kind, asked.retryAfter]).toEqual(['rateLimited', 20]);
    const silent = (await run(model(fakeFetch(429))).catch((e) => e)) as OnDeviceAiError;
    expect([silent.kind, silent.retryAfter]).toEqual(['rateLimited', 60]);
  });

  it('a bad request is not retried down a ladder', async () => {
    const f = fakeFetch({ status: 400, body: '{"error":{"message":"response_format json_schema strict is not supported"}}' }, OK);
    expect(await kindOf(run(model(f)))).toBe('unavailable');
    expect(f.calls).toHaveLength(1);
  });

  it('an answer cut off by the token limit is unavailable, even with a tool call in it', async () => {
    expect(await kindOf(run(model(fakeFetch({ status: 200, body: message({ answer: 'Yes, bal' }, 'max_tokens') }))))).toBe('unavailable');
  });

  it('a page that is not a message is unavailable', async () => {
    for (const body of ['', 'not json', '{"content":"text"}', '{"content":[]}']) {
      expect(await kindOf(run(model(fakeFetch({ status: 200, body })))), body).toBe('unavailable');
    }
  });
});

describe('ping', () => {
  it('sends 5 tokens and no tool and accepts an answer cut off at the limit', async () => {
    const f = fakeFetch(PING_OK);
    await model(f).ping();
    expect(f.calls[0].body).toEqual({
      model: 'test-model', max_tokens: 5, temperature: 0, system: 'Reply with {"ok": true}.',
      messages: [{ role: 'user', content: 'ping' }],
    });
    expect(f.calls[0].init.headers).toMatchObject({ 'x-api-key': KEY });
  });
});
