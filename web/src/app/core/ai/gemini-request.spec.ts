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
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { afterEach, describe, expect, it } from 'vitest';
import { AnthropicChatModel } from './anthropic';
import type { AiQuality } from './ai-quality';
import { OpenAiCompatibleChatModel } from './openai-compat';
import { ANSWER_SCHEMA, GEMINI_MAX_OUTPUT_TOKENS, GEMINI_URL, GeminiChatModel, PLAN_SCHEMA, geminiBody } from './on-device-ai.service';
import vectors from './parity-vectors.json';

/**
 * TC-U-190 (docs/06): the body of a Gemini `generateContent` request (S4b-BL-198 step 2, docs/ai/ai-design.md 13.2):
 * the `geminiRequest` vectors for the three settings, the answer budget of 8,192 tokens (the limit includes Gemini 3.x
 * thinking tokens; 2,048 cut the JSON off, S4b-BL-194), and that no other adapter is given a thinking field. The Kotlin
 * side runs the same vectors in a later pull request.
 */

const SCHEMAS: Record<string, object> = { answer: ANSWER_SCHEMA, plan: PLAN_SCHEMA, ping: { type: 'object', properties: { ok: { type: 'boolean' } } } };
type Case = { call: string; quality: AiQuality; system: string; user: string; temperature: number; expected: Record<string, any> };
const cases = vectors.geminiRequest as Case[];

describe('geminiRequest vectors', () => {
  it('geminiRequest vectors: the exact body for every call and setting, keys in order', () => {
    expect(cases.map((c) => `${c.call}/${c.quality}`)).toEqual([
      'answer/quality', 'answer/balanced', 'answer/economy', 'plan/economy', 'ping/quality', 'ping/economy',
    ]);
    for (const c of cases) {
      const body = geminiBody(c.system, c.user, SCHEMAS[c.call], c.temperature, c.quality);
      expect(body, `${c.call}/${c.quality}`).toEqual(c.expected);
      expect(JSON.stringify(body), `${c.call}/${c.quality} key order`).toBe(JSON.stringify(c.expected));
    }
  });

  it('geminiRequest vectors: Quality has no thinkingConfig, Balanced MEDIUM, Economy LOW, and every body asks for 8192 tokens', () => {
    const level = (q: AiQuality) => cases.find((c) => c.call === 'answer' && c.quality === q)!.expected['generationConfig'];
    expect('thinkingConfig' in level('quality')).toBe(false);
    expect(level('balanced').thinkingConfig).toEqual({ thinkingLevel: 'MEDIUM' });
    expect(level('economy').thinkingConfig).toEqual({ thinkingLevel: 'LOW' });
    for (const c of cases) expect(c.expected['generationConfig'].maxOutputTokens, c.call).toBe(8192);
  });
});

describe('the Gemini adapter\'s request', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  /** Sends one call through the adapter and returns the body it posted. */
  async function sent(quality?: AiQuality): Promise<Record<string, any>> {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const model = new GeminiChatModel(TestBed.inject(HttpClient), 'AIzaTestKey1234', quality);
    const done = model.generateJson('You answer.', 'Quiet?', ANSWER_SCHEMA, 0.1);
    const req = TestBed.inject(HttpTestingController).expectOne(GEMINI_URL);
    expect(req.request.headers.get('x-goog-api-key')).toBe('AIzaTestKey1234');
    req.flush({ candidates: [{ content: { parts: [{ text: '{"answer":"Yes"}' }] } }] });
    await done;
    return req.request.body;
  }

  it('sends no thinking field with Quality, and none when no setting is given: nothing changes for anyone who does not touch it', async () => {
    const body = await sent('quality');
    expect(body['generationConfig']).not.toHaveProperty('thinkingConfig');
    expect(JSON.stringify(body)).not.toMatch(/thinking/i);
    TestBed.resetTestingModule();
    expect(JSON.stringify(await sent())).not.toMatch(/thinking/i);
  });

  it('Balanced sends generationConfig.thinkingConfig.thinkingLevel MEDIUM', async () => {
    expect((await sent('balanced'))['generationConfig'].thinkingConfig).toEqual({ thinkingLevel: 'MEDIUM' });
  });

  it('Economy sends generationConfig.thinkingConfig.thinkingLevel LOW', async () => {
    expect((await sent('economy'))['generationConfig'].thinkingConfig).toEqual({ thinkingLevel: 'LOW' });
  });

  it('asks for up to 8192 output tokens in every setting, because the limit includes the thinking tokens', async () => {
    expect(GEMINI_MAX_OUTPUT_TOKENS).toBe(8192);
    for (const q of ['quality', 'balanced', 'economy'] as const) {
      TestBed.resetTestingModule();
      expect((await sent(q))['generationConfig'].maxOutputTokens, q).toBe(8192);
    }
  });

  it('keeps the rest of the request as it was', async () => {
    const body = await sent('economy');
    expect(body['systemInstruction']).toEqual({ parts: [{ text: 'You answer.' }] });
    expect(body['contents']).toEqual([{ role: 'user', parts: [{ text: 'Quiet?' }] }]);
    expect(body['generationConfig']).toMatchObject({ temperature: 0.1, responseMimeType: 'application/json', responseSchema: ANSWER_SCHEMA });
  });
});

describe('the other adapters are given no thinking field and keep their 2048-token limit', () => {
  it('OpenAI-compatible and Anthropic bodies carry neither a thinking field nor an effort, and max_tokens stays 2048', async () => {
    const calls: Record<string, any>[] = [];
    const fetchImpl = async (_url: string, init: RequestInit): Promise<Response> => {
      calls.push(JSON.parse(String(init.body)));
      return new Response('{}', { status: 500 });
    };
    const openai = new OpenAiCompatibleChatModel({ baseUrl: 'https://api.example.com/v1', model: 'm' }, 'k', fetchImpl);
    const anthropic = new AnthropicChatModel({ baseUrl: 'https://api.anthropic.com', model: 'm' }, 'k', fetchImpl);
    await openai.generateJson('s', 'u', ANSWER_SCHEMA, 0.1).catch(() => undefined);
    await anthropic.generateJson('s', 'u', ANSWER_SCHEMA, 0.1).catch(() => undefined);
    expect(calls.length).toBeGreaterThanOrEqual(2);
    for (const body of calls) {
      expect(JSON.stringify(body)).not.toMatch(/thinking|reasoning/i);
      expect(body['max_tokens']).toBe(2048);
    }
  });
});
