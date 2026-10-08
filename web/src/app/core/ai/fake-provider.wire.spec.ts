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

import { HttpClient, provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { LocalStore } from '../../data/local-store.service';
import golden from '../../../../../docs/ai/evals/golden-set.json';
import { AnthropicChatModel, resetToolChoiceCache } from './anthropic';
import { OpenAiCompatibleChatModel, resetTierCache } from './openai-compat';
import { ANSWER_SCHEMA, GEMINI_URL, GeminiChatModel, OnDeviceAiError, OnDeviceAiService } from './on-device-ai.service';

/**
 * The website's adapters over real `fetch` and a real socket against the fake provider server
 * (`tools/fake-ai-provider/server.mjs`, a Node subprocess; S4b-BL-175, docs/06 TC-AI-24). The other specs answer from an
 * in-memory `fetch`; this one shows what a vendor-like server does with the requests as they leave the adapter. The server
 * checks each request like a vendor (bearer key, `x-api-key`, forced `tool_choice`, strict schemas), so a wrong request is a
 * failing test. Expected shapes come from the vendors' documentation cited in `server.mjs`. Node's fetch enforces no CORS:
 * the preflight is tested for real by `tools/fake-ai-provider/browser-check.mjs` (Chromium). The 60 s timeout is tested on
 * the phones (FakeProviderWireTest), where it can be shortened. Not proved here: that a vendor still behaves like its
 * documentation, any quota, the quality of a real model.
 */
type Spawned = { stdout: { once(ev: 'data', cb: (b: { toString(): string }) => void): void }; kill(): void };
type NodeProcess = { cwd(): string; getBuiltinModule?: (id: string) => any };
const proc = (globalThis as { process?: NodeProcess }).process;
const hasNode = typeof proc?.getBuiltinModule === 'function';

describe.skipIf(!hasNode)('Adapters over a real socket against the fake provider server', () => {
  let child: Spawned;
  let api = '';

  const control = async (method: string, path: string, body?: object) =>
    (await fetch(api + path, { method, body: body ? JSON.stringify(body) : undefined })).json();
  const mode = (m: string) => control('POST', '/__mode', { mode: m, cors: 'permissive', key: null });
  const requests = async (): Promise<{ method: string; url: string; headers: Record<string, string>; body: any }[]> =>
    (await control('GET', '/__requests')).requests.filter((r: { method: string }) => r.method === 'POST');
  const openAi = (key = 'sk-test', base = '/v1') => new OpenAiCompatibleChatModel({ baseUrl: `${api}${base}`, model: 'test-model' }, key);
  const anthropic = (key = 'ak-test') => new AnthropicChatModel({ baseUrl: api, model: 'test-model' }, key);
  const ask = (m: { generateJson(s: string, u: string, schema: object, t: number): Promise<string> }) => m.generateJson('system', 'user', ANSWER_SCHEMA, 0.1);

  beforeAll(async () => {
    const node = proc!.getBuiltinModule!('node:child_process');
    const path = proc!.getBuiltinModule!('node:path');
    const server = path.resolve(proc!.cwd(), '../tools/fake-ai-provider/server.mjs');
    const goldenFile = path.resolve(proc!.cwd(), '../docs/ai/evals/golden-set.json');
    child = node.spawn('node', [server, '--port', '0', '--golden', goldenFile]);
    const line: string = await new Promise((ok) => child.stdout.once('data', (b) => ok(b.toString())));
    api = `http://127.0.0.1:${JSON.parse(line).listening}`;
  });
  afterAll(() => child?.kill());
  beforeEach(async () => {
    resetTierCache();
    resetToolChoiceCache();
    await control('DELETE', '/__requests');
    await mode('ok');
  });
  afterEach(() => vi.unstubAllGlobals());

  describe('openai-compatible', () => {
    it('answers a strict json_schema request on the first tier with the bearer key and no query', async () => {
      const text = await ask(openAi());
      expect(Object.keys(JSON.parse(text))).toContain('answer');
      const [req] = await requests();
      expect(req.url).toBe('/v1/chat/completions');
      expect(req.headers['authorization']).toBe('Bearer sk-test');
      expect(req.body.response_format.type).toBe('json_schema');
    });

    it('sends no Authorization to a keyless Ollama-style server', async () => {
      await mode('ollama');
      await ask(openAi(''));
      expect((await requests())[0].headers['authorization']).toBeUndefined();
    });

    it('uses the base URL as given: /chat/completions without a path prefix', async () => {
      await ask(openAi('sk-test', ''));
      expect((await requests())[0].url).toBe('/chat/completions');
    });

    it('moves the ladder from json_schema to json_object and keeps that tier for the session', async () => {
      await mode('json-object-only');
      const model = openAi();
      await ask(model);
      expect((await requests()).map((r) => r.body.response_format.type)).toEqual(['json_schema', 'json_object']);
      await ask(model);
      expect(await requests()).toHaveLength(3);
    });

    it('moves the ladder to a plain request on tier 3, the shape in the system trailer', async () => {
      await mode('no-structured');
      const text = await ask(openAi());
      expect(Object.keys(JSON.parse(text))).toContain('answer');
      const bodies = (await requests()).map((r) => r.body);
      expect(bodies).toHaveLength(3);
      expect(bodies[2].response_format).toBeUndefined();
      expect(bodies[2].messages[0].content).toContain('of this shape');
    });

    it('maps 401, 403, 404, 429 with Retry-After, 500 and 529 to the same error kinds as the phones', async () => {
      const expected: [string, string][] = [['unauthorized', 'keyRejected'], ['forbidden', 'keyRejected'], ['not-found', 'modelNotFound'], ['server-error', 'unavailable'], ['overloaded', 'unavailable']];
      for (const [m, kind] of expected) {
        await mode(m);
        await expect(ask(openAi())).rejects.toMatchObject({ kind });
      }
      await mode('rate-limit');
      await expect(ask(openAi())).rejects.toMatchObject({ kind: 'rateLimited', retryAfter: 7 });
    });

    it('does not follow a redirect; nobody reaches the target', async () => {
      await mode('redirect');
      await expect(ask(openAi())).rejects.toBeInstanceOf(OnDeviceAiError);
      expect((await control('GET', '/__requests')).redirected).toBe(0);
    });

    // S4b-BL-175-F2: docs/03 §13.2 says a 3xx is "unavailable"; with redirect 'manual' the website says so too (a browser
    // shows an opaque redirect, Node shows the 302 here; both are unavailable, and neither follows it).
    it('a redirect reads as unavailable, as on the phones, and nobody reaches the target', async () => {
      await mode('redirect');
      await expect(ask(openAi())).rejects.toMatchObject({ kind: 'unavailable' });
      await expect(ask(anthropic())).rejects.toMatchObject({ kind: 'unavailable' });
      expect((await control('GET', '/__requests')).redirected).toBe(0);
    });

    it('unwraps a fenced answer; no choices and an error-only 200 are unavailable', async () => {
      await mode('fenced');
      expect(Object.keys(JSON.parse(await ask(openAi())))).toContain('answer');
      for (const m of ['empty-choices', 'openrouter-200-error']) {
        await mode(m);
        await expect(ask(openAi())).rejects.toMatchObject({ kind: 'unavailable' });
      }
    });

    it('returns a truncated answer as is (finish_reason is not read) and the caller fails on the cut JSON', async () => {
      await mode('truncated');
      const text = await ask(openAi());
      expect(() => JSON.parse(text)).toThrow();
    });

    it('extracts the golden-set listing through OnDeviceAiService and the adapter', async () => {
      const c = (golden.cases as { id: string; input: { text: string } }[]).find((g) => g.id === 'extract-01-whatsapp-rent')!;
      TestBed.configureTestingModule({
        providers: [provideHttpClient(withFetch()), { provide: LocalStore, useValue: { allHouses: async () => [] } }],
      });
      const draft = await TestBed.inject(OnDeviceAiService).extractListing(openAi(), c.input.text);
      expect(draft.price).toBe(28000);
      expect(draft.bedrooms).toBe(2);
    });
  });

  describe('anthropic', () => {
    it('forces the tool and sends the documented headers, the browser header included', async () => {
      const text = await ask(anthropic());
      expect(Object.keys(JSON.parse(text))).toContain('answer');
      const [req] = await requests();
      expect(req.headers['x-api-key']).toBe('ak-test');
      expect(req.headers['anthropic-version']).toBe('2023-06-01');
      expect(req.headers['anthropic-dangerous-direct-browser-access']).toBe('true');
      expect(req.body.tool_choice.type).toBe('tool');
    });

    it('accepts a tool-less ping that stops at the token limit', async () => {
      await anthropic().ping();
      expect((await requests())[0].body.tools).toBeUndefined();
    });

    it('maps every status to the same kinds, 429 with Retry-After, 529 overloaded as unavailable', async () => {
      const expected: [string, string][] = [['unauthorized', 'keyRejected'], ['forbidden', 'keyRejected'], ['not-found', 'modelNotFound'], ['server-error', 'unavailable'], ['overloaded', 'unavailable']];
      for (const [m, kind] of expected) {
        await mode(m);
        await expect(ask(anthropic())).rejects.toMatchObject({ kind });
      }
      await mode('rate-limit');
      await expect(ask(anthropic())).rejects.toMatchObject({ kind: 'rateLimited', retryAfter: 7 });
    });

    it('treats max_tokens and a text-only reply as unavailable', async () => {
      for (const m of ['max-tokens', 'text-only']) {
        await mode(m);
        await expect(ask(anthropic())).rejects.toMatchObject({ kind: 'unavailable' });
      }
    });

    it('sends nothing without a key', async () => {
      await expect(ask(anthropic(''))).rejects.toMatchObject({ kind: 'keyRejected' });
      expect(await requests()).toHaveLength(0);
    });

    // S4b-BL-175-F1: newer Claude models refuse a forced tool_choice with a 400 (platform.claude.com/docs/en/api/errors,
    // "Forced tool use not supported"); the adapter repeats the call once with tool_choice auto and remembers it.
    it('a model that refuses a forced tool is asked again with tool_choice auto, once, and then remembered', async () => {
      await mode('no-forced-tool');
      const model = anthropic();
      expect(Object.keys(JSON.parse(await ask(model)))).toContain('answer');
      expect(Object.keys(JSON.parse(await ask(model)))).toContain('answer');
      const types = (await requests()).map((r) => r.body.tool_choice.type);
      expect(types).toEqual(['tool', 'auto', 'auto']);
      expect((await requests())[1].body.system).toMatch(/\n\nAnswer by calling the answer tool\.$/);
    });
  });

  describe('gemini', () => {
    /** Google's address is a constant; send it to the fake server and keep the rest of the request. */
    const toFake = () => {
      const real = globalThis.fetch.bind(globalThis);
      vi.stubGlobal('fetch', (input: string, init?: RequestInit) => {
        const url = new URL(input); // the host is compared as a parsed host, not as a text prefix
        return real(url.hostname === 'generativelanguage.googleapis.com' ? api + url.pathname + url.search : input, init);
      });
      TestBed.configureTestingModule({ providers: [provideHttpClient(withFetch())] });
      return new GeminiChatModel(TestBed.inject(HttpClient), 'gk-test');
    };

    it('sends the key in x-goog-api-key and parses candidates[0].content.parts', async () => {
      const text = await ask(toFake());
      expect(Object.keys(JSON.parse(text))).toContain('answer');
      const [req] = await requests();
      expect(req.headers['x-goog-api-key']).toBe('gk-test');
      expect(req.url).toBe(new URL(GEMINI_URL).pathname);
    });

    it('maps a quota stop to rateLimited and an invalid key (400 API_KEY_INVALID) to keyRejected', async () => {
      const model = toFake();
      await mode('rate-limit');
      await expect(ask(model)).rejects.toMatchObject({ kind: 'rateLimited' });
      await mode('bad-key');
      await expect(ask(model)).rejects.toMatchObject({ kind: 'keyRejected' });
    });
  });
});
