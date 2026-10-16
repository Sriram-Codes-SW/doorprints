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

/**
 * Whether the provider's own safety system declined a call (S4b-BL-232; docs/ai/ai-design.md 15), read from the real
 * response shapes and only from named fields, never from free text, so words the provider echoed (which can be the abusive
 * words the person typed) are neither read, kept nor logged. The person's text is not filtered or censored anywhere in the
 * app; this is only the provider's verdict. The same rules as the phones' `AiBlocked.kt`, pinned by the shared vectors
 * (`blockedResponses`).
 *  - Gemini (`generateContent`, ai.google.dev/api/generate-content, read 2026-10-10): `promptFeedback.blockReason` other
 *    than `BLOCK_REASON_UNSPECIFIED`, or a candidate `finishReason` of `SAFETY`, `PROHIBITED_CONTENT`, `BLOCKLIST`, `SPII`
 *    or `RECITATION`. Anything else (`MAX_TOKENS`, `OTHER`, `LANGUAGE`, no candidates at all) stays `unavailable`.
 *  - OpenAI-compatible (`chat/completions`): `choices[0].finish_reason` `content_filter`, a non-empty
 *    `choices[0].message.refusal`, or an HTTP 400 whose `error.code` is `content_policy_violation` or `content_filter`
 *    (Azure OpenAI's filtered prompt, learn.microsoft.com content filtering, read 2026-10-10).
 *  - Anthropic (`/v1/messages`, platform.claude.com handle-streaming-refusals, read 2026-10-10): HTTP 200 with
 *    `stop_reason` `refusal`.
 */

const GEMINI_FINISH = ['SAFETY', 'PROHIBITED_CONTENT', 'BLOCKLIST', 'SPII', 'RECITATION'];
const OPENAI_CODES = ['content_policy_violation', 'content_filter'];

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Obj) : null);
const text = (v: unknown): string => (typeof v === 'string' ? v : '');
const ok = (status: number) => status >= 200 && status < 300;

/** A Gemini `generateContent` answer (already parsed) the provider blocked. */
export function geminiBlocked(root: unknown): boolean {
  const body = obj(root);
  if (!body) return false;
  const reason = text(obj(body['promptFeedback'])?.['blockReason']);
  if (reason !== '' && reason !== 'BLOCK_REASON_UNSPECIFIED') return true;
  const candidates = body['candidates'];
  const first = Array.isArray(candidates) ? obj(candidates[0]) : null;
  return GEMINI_FINISH.includes(text(first?.['finishReason']));
}

/** An OpenAI-compatible reply (status and parsed body) the provider blocked. */
export function openAiBlocked(status: number, root: unknown): boolean {
  const body = obj(root);
  if (!body) return false;
  if (status === 400) return OPENAI_CODES.includes(text(obj(body['error'])?.['code']));
  if (!ok(status)) return false;
  const choices = body['choices'];
  const choice = Array.isArray(choices) ? obj(choices[0]) : null;
  if (!choice) return false;
  return text(choice['finish_reason']) === 'content_filter' || text(obj(choice['message'])?.['refusal']) !== '';
}

/** An Anthropic reply (status and parsed body) the provider refused. */
export function anthropicBlocked(status: number, root: unknown): boolean {
  return ok(status) && text(obj(root)?.['stop_reason']) === 'refusal';
}

/** The parsed JSON of a response body, or null when it is not JSON. */
export function parseBody(body: string): unknown {
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

/** The shared vectors' entry point: [provider] is `gemini`, `openai` or `anthropic`. */
export function isBlockedResponse(provider: string, status: number, body: string): boolean {
  const root = parseBody(body);
  switch (provider) {
    case 'gemini':
      return ok(status) && geminiBlocked(root);
    case 'openai':
      return openAiBlocked(status, root);
    case 'anthropic':
      return anthropicBlocked(status, root);
    default:
      return false;
  }
}
