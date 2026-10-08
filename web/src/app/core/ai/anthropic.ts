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

import { ANTHROPIC_BASE_URL, validateWebBaseUrl } from './ai-provider-config';
import { type FetchLike, failure, classifyStatus, postAiJson } from './ai-request';
import type { JsonChatModel } from './json-chat-model';
import { schemaName } from './openai-compat';
import { OnDeviceAiError } from './on-device-ai.service';
import { toStrictSchema } from './schema-dialect';

/**
 * The `anthropic` kind (docs/03 §13.2, ADR-35): Anthropic's Messages API, `POST {baseUrl}/v1/messages`, called with
 * `fetch` straight from this browser with the person's own key. The key goes only in an `x-api-key` header to the
 * configured host, never in a URL, and it is required: without one nothing is sent. A browser is refused unless the
 * request says it knows (`anthropic-dangerous-direct-browser-access`), which is the website's alone: the key is the
 * person's own and goes nowhere but Anthropic. The answer is forced through one tool whose input schema is the call's
 * strict schema, and it is that tool call's `input`. Newer Claude models refuse a forced tool (a 400 saying `tool_choice`
 * is not supported): the call is then repeated once with `tool_choice: auto` and an instruction, and that choice is
 * kept for the address and model for the session. Prompts and answers are not logged.
 */

export const ANTHROPIC_VERSION = '2023-06-01';
export const MAX_TOKENS = 2048;
export const PING_MAX_TOKENS = 5;
const PING_SYSTEM = 'Reply with {"ok": true}.';
const TOOL_DESCRIPTION = 'Reply by calling this tool with the answer.';

/**
 * The body of one request (the `anthropicRequest` vectors). `strict` is null for the ping, which has no tool. With
 * `forceTool` false (a model that refuses a forced tool) `tool_choice` is `auto` and the system text ends with an
 * instruction to answer by calling the tool.
 */
export function anthropicBody(
  name: string,
  model: string,
  system: string,
  user: string,
  temperature: number,
  strict: object | null,
  maxTokens = MAX_TOKENS,
  forceTool = true,
): Record<string, unknown> {
  const body: Record<string, unknown> = {
    model,
    max_tokens: maxTokens,
    temperature,
    system: strict !== null && !forceTool ? `${system}\n\nAnswer by calling the ${name} tool.` : system,
    messages: [{ role: 'user', content: user }],
  };
  if (strict !== null) {
    body['tools'] = [{ name, description: TOOL_DESCRIPTION, input_schema: strict }];
    body['tool_choice'] = forceTool ? { type: 'tool', name } : { type: 'auto' };
  }
  return body;
}

/**
 * Whether a failed answer is a model refusing a forced tool (the `anthropicToolChoice` vectors): a 400 whose body
 * (case-insensitive) says `tool_choice` and `not supported`, as platform.claude.com/docs/en/api/errors documents.
 */
export function retryWithAuto(status: number, body: string): boolean {
  const text = body.toLowerCase();
  return status === 400 && text.includes('tool_choice') && text.includes('not supported');
}

/** The addresses and models (`base\nmodel`) that refused a forced tool, kept for the session. */
const unforced = new Set<string>();

/** Forgets what was learned about forced tools (a test's). */
export function resetToolChoiceCache(): void {
  unforced.clear();
}

/** The response body as an object, or null when it is not JSON or not an object. */
function parseMessage(responseBody: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(responseBody);
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/**
 * The first `tool_use` block's `input` as compact JSON text (the `anthropicContent` vectors). Null when there is none (the
 * model refused or wrote text), when its input is not an object, or when the answer stopped at the token limit.
 */
export function anthropicInput(responseBody: string): string | null {
  const message = parseMessage(responseBody);
  if (message === null || message['stop_reason'] === 'max_tokens' || !Array.isArray(message['content'])) return null;
  const call = (message['content'] as { type?: unknown; input?: unknown }[]).find((b) => b?.type === 'tool_use');
  const input = call?.input;
  return typeof input === 'object' && input !== null && !Array.isArray(input) ? JSON.stringify(input) : null;
}

/** A ping is answered when the reply is a message with a `content` list, whatever it holds (it may stop at the limit). */
export function anthropicPingAnswered(responseBody: string): boolean {
  return Array.isArray(parseMessage(responseBody)?.['content']);
}

/** The saved base URL (empty means Anthropic's own) and model. */
export interface AnthropicSettings {
  baseUrl: string;
  model: string;
}

/** A [JsonChatModel] for one saved configuration; an empty base URL means Anthropic's own address. */
export class AnthropicChatModel implements JsonChatModel {
  constructor(
    private readonly settings: AnthropicSettings,
    private readonly apiKey: string,
    private readonly fetchImpl: FetchLike = (input, init) => fetch(input, init),
  ) {}

  /**
   * Asks for JSON by forcing a tool call whose input schema is the call's schema; the tool input is the answer. A
   * failed status is mapped to an {@link OnDeviceAiError}, and so is an answer without a usable tool call.
   */
  async generateJson(system: string, user: string, schema: object, temperature: number): Promise<string> {
    const name = schemaName(schema);
    const strict = toStrictSchema(schema);
    const cacheKey = `${this.baseUrl()}\n${this.settings.model}`;
    let force = !unforced.has(cacheKey);
    for (;;) {
      const body = anthropicBody(name, this.settings.model, system, user, temperature, strict, MAX_TOKENS, force);
      const res = await this.post(body);
      if (force && retryWithAuto(res.status, res.body)) {
        force = false;
        unforced.add(cacheKey);
        continue;
      }
      if (res.status < 200 || res.status >= 300) throw failure(classifyStatus(res.status, res.retryAfter));
      const text = anthropicInput(res.body);
      if (text === null) throw new OnDeviceAiError('unavailable');
      return text;
    }
  }

  /** One call with 5 tokens and no tool; an answer, even one cut off at the limit, means the key and the model work. */
  async ping(): Promise<void> {
    const body = anthropicBody('ping', this.settings.model, PING_SYSTEM, 'ping', 0, null, PING_MAX_TOKENS);
    const res = await this.post(body);
    if (res.status < 200 || res.status >= 300) throw failure(classifyStatus(res.status, res.retryAfter));
    if (!anthropicPingAnswered(res.body)) throw new OnDeviceAiError('unavailable');
  }

  private baseUrl(): string {
    const check = validateWebBaseUrl(this.settings.baseUrl.trim() || ANTHROPIC_BASE_URL);
    return check.valid ? check.normalised : '';
  }

  /** One POST to `{base}/v1/messages`; no key, no request. */
  private post(body: object): ReturnType<typeof postAiJson> {
    const check = validateWebBaseUrl(this.settings.baseUrl.trim() || ANTHROPIC_BASE_URL);
    if (!check.valid) throw new OnDeviceAiError('unavailable');
    const key = this.apiKey.trim();
    if (key === '') throw new OnDeviceAiError('keyRejected');
    return postAiJson(this.fetchImpl, `${check.normalised}/v1/messages`, {
      'x-api-key': key,
      'anthropic-version': ANTHROPIC_VERSION,
      'anthropic-dangerous-direct-browser-access': 'true',
    }, body);
  }
}
