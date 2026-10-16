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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { REQUEST_TIMEOUT_MS, postAiJson } from './ai-request';
import { AnthropicChatModel } from './anthropic';
import { OpenAiCompatibleChatModel } from './openai-compat';
import { ANSWER_SCHEMA, OnDeviceAiError } from './on-device-ai.service';

/**
 * S4b-BL-175-F2 (docs/03 §13.2): a redirect is never followed and reads as `unavailable` on the website as on the phones.
 * A browser hides a redirect answered to `fetch` with `redirect: 'manual'` behind an opaque-redirect response (type
 * `opaqueredirect`, status 0, nothing readable), so the status 0 that means "no answer" must not be taken for it; a runtime
 * that does show the response (Node, in tests) gives the status, and a 3xx status is `unavailable` as ever.
 */

/** What a browser gives `fetch` for a redirect it was told not to follow. */
const opaqueRedirect = async (): Promise<Response> =>
  ({ type: 'opaqueredirect', status: 0, headers: new Headers(), text: async () => '' }) as unknown as Response;

const kindOf = async (p: Promise<unknown>) => {
  try {
    await p;
    return 'no error';
  } catch (e) {
    return e instanceof OnDeviceAiError ? e.kind : `other: ${String(e)}`;
  }
};

describe('a redirect on the website (S4b-BL-175-F2)', () => {
  it('asks fetch not to follow it, with no cookies', async () => {
    const inits: RequestInit[] = [];
    await postAiJson(async (_url, init) => (inits.push(init), new Response('{}', { status: 200 })), 'https://api.example.com/x', {}, {});
    expect(inits[0]).toMatchObject({ redirect: 'manual', credentials: 'omit' });
  });

  it('reads the opaque redirect of a browser as unavailable, not as a network failure', async () => {
    expect(await kindOf(postAiJson(opaqueRedirect, 'https://api.example.com/x', {}, {}))).toBe('unavailable');
    const openAi = new OpenAiCompatibleChatModel({ baseUrl: 'https://api.example.com/v1', model: 'm' }, 'test-key-one', opaqueRedirect);
    expect(await kindOf(openAi.generateJson('S', 'U', ANSWER_SCHEMA, 0))).toBe('unavailable');
    expect(await kindOf(openAi.ping())).toBe('unavailable');
    const anthropic = new AnthropicChatModel({ baseUrl: '', model: 'm' }, 'test-key-one', opaqueRedirect);
    expect(await kindOf(anthropic.generateJson('S', 'U', ANSWER_SCHEMA, 0))).toBe('unavailable');
    expect(await kindOf(anthropic.ping())).toBe('unavailable');
  });

  it('reads a 3xx status that is shown as unavailable, and a failed fetch still as unreachable', async () => {
    for (const status of [301, 302, 307, 308]) {
      const model = new AnthropicChatModel({ baseUrl: '', model: 'm' }, 'test-key-one', async () => new Response('', { status }));
      expect(await kindOf(model.ping()), `HTTP ${status}`).toBe('unavailable');
    }
    const down = new AnthropicChatModel({ baseUrl: '', model: 'm' }, 'test-key-one', async () => {
      throw new TypeError('Failed to fetch');
    });
    expect(await kindOf(down.ping())).toBe('unreachable');
  });
});

/**
 * S4b-BL-190: the request limit is 60 s for every provider, and only the loopback eval (the `local-model` suite) may pass a
 * longer one, through the adapter's optional last argument. The default must not move: it is what the app's people get.
 */
describe('the request limit of one call (S4b-BL-190)', () => {
  afterEach(() => vi.restoreAllMocks());

  const ok = async () => new Response('{"choices":[{"message":{"content":"ok"}}]}', { status: 200 });

  it('is 60 seconds unless a caller says otherwise', async () => {
    const spy = vi.spyOn(AbortSignal, 'timeout');
    expect(REQUEST_TIMEOUT_MS).toBe(60_000);
    await postAiJson(ok, 'https://api.example.com/x', {}, {});
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith(60_000);
  });

  it('takes the limit a caller passes', async () => {
    const spy = vi.spyOn(AbortSignal, 'timeout');
    await postAiJson(ok, 'http://127.0.0.1:11434/v1/x', {}, {}, 180_000);
    expect(spy).toHaveBeenCalledWith(180_000);
  });

  it('really stops a call that never answers at the limit it was given', async () => {
    let signal: AbortSignal | null | undefined;
    const hangs = (_url: string, init: RequestInit) => {
      signal = init.signal;
      return new Promise<Response>((_resolve, reject) => init.signal?.addEventListener('abort', () => reject(init.signal?.reason)));
    };
    const started = Date.now();
    await postAiJson(hangs, 'http://127.0.0.1:11434/v1/x', {}, {}, 20).catch(() => undefined);
    expect(signal?.aborted).toBe(true);
    expect(Date.now() - started).toBeLessThan(5_000);
  });

  it('is passed on by the openai-compatible adapter for the ladder and for the ping, and is 60 s without it', async () => {
    const spy = vi.spyOn(AbortSignal, 'timeout');
    const settings = { baseUrl: 'http://127.0.0.1:11434/v1', model: 'm' };
    const slow = new OpenAiCompatibleChatModel(settings, '', ok, 180_000);
    await slow.ping();
    await slow.generateJson('S', 'U', ANSWER_SCHEMA, 0);
    expect(spy.mock.calls.map((c) => c[0])).toEqual([180_000, 180_000]);
    spy.mockClear();
    const normal = new OpenAiCompatibleChatModel(settings, '', ok);
    await normal.ping();
    expect(spy.mock.calls.map((c) => c[0])).toEqual([60_000]);
  });
});
