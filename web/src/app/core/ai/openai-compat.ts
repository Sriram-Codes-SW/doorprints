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

import type { JsonChatModel } from './json-chat-model';
import { ANSWER_SCHEMA, LISTING_SCHEMA, OnDeviceAiError, PLAN_SCHEMA } from './on-device-ai.service';
import { validateWebBaseUrl } from './ai-provider-config';
import { schemaTrailer, toStrictSchema } from './schema-dialect';
import { type ErrorAction, type FetchLike, classifyStatus, failure, postAiJson } from './ai-request';

/**
 * The `openai-compatible` kind (docs/03 §13.2, ADR-35): `POST {baseUrl}/chat/completions`, the chat API that OpenAI,
 * OpenRouter, Groq, Ollama, LM Studio and many others speak. Called with `fetch` straight from this browser, so the
 * server's API interceptor never sees the request and no server key can ride along; the person's key goes only in the
 * `Authorization` header to the configured host, never in a URL, and prompts and answers are not logged.
 */

/** 1 `json_schema` (strict), 2 `json_object` plus a trailer, 3 nothing plus a trailer. */
export type Tier = 1 | 2 | 3;

export const MAX_TOKENS = 2048;
export const PING_MAX_TOKENS = 5;
const PING_SYSTEM = 'Reply with {"ok": true}.';

/** The body of one request (the `openaiRequest` vectors). `strict` is null for the ping, which asks for no format. */
export function openAiBody(
  tier: Tier,
  model: string,
  name: string,
  system: string,
  user: string,
  temperature: number,
  strict: object | null,
  maxTokens = MAX_TOKENS,
): Record<string, unknown> {
  const withTrailer = tier !== 1 && strict !== null;
  const body: Record<string, unknown> = {
    model,
    temperature,
    max_tokens: maxTokens,
    messages: [
      { role: 'system', content: withTrailer ? `${system}\n\n${schemaTrailer(strict)}` : system },
      { role: 'user', content: user },
    ],
  };
  if (tier === 1 && strict !== null) {
    body['response_format'] = { type: 'json_schema', json_schema: { name, strict: true, schema: strict } };
  } else if (tier === 2) {
    body['response_format'] = { type: 'json_object' };
  }
  return body;
}

/** The `json_schema` name of one of the three schemas. */
export function schemaName(schema: object): string {
  if (schema === LISTING_SCHEMA) return 'listing';
  if (schema === ANSWER_SCHEMA) return 'answer';
  if (schema === PLAN_SCHEMA) return 'plan';
  return 'response';
}

const TIER_1_WORDS = ['response_format', 'json_schema', 'strict', 'unsupported', 'not supported'];
const TIER_2_WORDS = ['response_format', 'json_object'];

/**
 * The ladder moves down only on a 400 whose body (case-insensitive) holds a trigger word of that tier; any other 400, and
 * any 400 at tier 3, is `unavailable`; every other status is {@link classifyStatus}'s.
 */
export function classifyError(tier: Tier, status: number, body: string, retryAfter: string | null = null): ErrorAction {
  if (status === 400 && tier < 3) {
    const text = body.toLowerCase();
    if ((tier === 1 ? TIER_1_WORDS : TIER_2_WORDS).some((w) => text.includes(w))) return { action: 'nextTier', tier: (tier + 1) as Tier };
  }
  return classifyStatus(status, retryAfter);
}

/** The model's text: surrounding whitespace and one Markdown code fence removed. */
export function stripFence(text: string): string {
  const trimmed = text.trim();
  const m = /^```(?:json)?[ \t]*\r?\n?([\s\S]*?)\r?\n?```$/i.exec(trimmed);
  return m ? m[1].trim() : trimmed;
}

/** `choices[0].message.content` of a response body, fence removed; null when there is none or it is not text. */
export function answerText(responseBody: string): string | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(responseBody);
  } catch {
    return null;
  }
  const content = (parsed as { choices?: { message?: { content?: unknown } }[] } | null)?.choices?.[0]?.message?.content;
  return typeof content === 'string' ? stripFence(content) : null;
}

/** The tier that worked for a configuration (base URL and model), kept for the session. */
const winningTier = new Map<string, Tier>();

/** Forgets every cached tier (a test's, or a person who wants the ladder tried again). */
export function resetTierCache(): void {
  winningTier.clear();
}

export interface OpenAiCompatibleSettings {
  baseUrl: string;
  model: string;
}

/** A [JsonChatModel] for one saved configuration; `apiKey` may be empty for a local server. */
export class OpenAiCompatibleChatModel implements JsonChatModel {
  constructor(
    private readonly settings: OpenAiCompatibleSettings,
    private readonly apiKey: string,
    private readonly fetchImpl: FetchLike = (input, init) => fetch(input, init),
  ) {}

  async generateJson(system: string, user: string, schema: object, temperature: number): Promise<string> {
    const check = validateWebBaseUrl(this.settings.baseUrl);
    if (!check.valid) throw new OnDeviceAiError('unavailable');
    const strict = toStrictSchema(schema);
    const name = schemaName(schema);
    const cacheKey = `${check.normalised}\n${this.settings.model}`;
    let tier: Tier = winningTier.get(cacheKey) ?? 1;
    for (;;) {
      const body = openAiBody(tier, this.settings.model, name, system, user, temperature, strict);
      const res = await this.post(check.normalised, body);
      if (res.status >= 200 && res.status < 300) {
        const text = answerText(res.body);
        if (text === null) throw new OnDeviceAiError('unavailable');
        winningTier.set(cacheKey, tier);
        return text;
      }
      const verdict = classifyError(tier, res.status, res.body, res.retryAfter);
      if (verdict.action === 'nextTier') {
        tier = verdict.tier;
        continue;
      }
      throw failure(verdict);
    }
  }

  /** One call with `max_tokens` 5 and no `response_format`; a text answer means the URL, the key and the model work. */
  async ping(): Promise<void> {
    const check = validateWebBaseUrl(this.settings.baseUrl);
    if (!check.valid) throw new OnDeviceAiError('unavailable');
    const body = openAiBody(3, this.settings.model, 'ping', PING_SYSTEM, 'ping', 0, null, PING_MAX_TOKENS);
    const res = await this.post(check.normalised, body);
    if (res.status < 200 || res.status >= 300) {
      throw failure(classifyError(3, res.status, res.body, res.retryAfter));
    }
    if (answerText(res.body) === null) throw new OnDeviceAiError('unavailable');
  }

  /** One POST: the key only in an `Authorization` header, and only when there is one. */
  private post(baseUrl: string, body: object): ReturnType<typeof postAiJson> {
    const headers: Record<string, string> = this.apiKey.trim() !== '' ? { Authorization: `Bearer ${this.apiKey.trim()}` } : {};
    return postAiJson(this.fetchImpl, `${baseUrl}/chat/completions`, headers, body);
  }
}
