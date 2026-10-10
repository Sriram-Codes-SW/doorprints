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

import { OnDeviceAiError, type OnDeviceAiErrorKind } from './on-device-ai.service';

/**
 * The one way the website's AI adapters (docs/03 §13.2, ADR-35) call a provider and word a failure, so that one failure
 * is worded one way, as the phones' `postAiJson` and `aiFailure` do: a network failure is status 0 (`unreachable`), a
 * redirect is never followed (so the key goes to the one address the person chose) and reads as `unavailable`, 60 s at
 * most, and a status maps to the same error kinds for every provider. Prompts, answers and keys are not logged.
 */

export const REQUEST_TIMEOUT_MS = 60_000;

/** The `fetch` signature the adapters call, so a test can replace it. */
export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

/** What a provider answered: the status (0 when it never answered), the whole body and its `Retry-After` header. */
export interface AiReply {
  status: number;
  body: string;
  retryAfter: string | null;
}

/**
 * One POST of `body` as JSON with `headers` added, stopped after `timeoutMs` (60 s unless the caller passes another; the
 * app never does, only the loopback eval of the `local-model` suite, S4b-BL-190). Status 0 is a network failure; a timeout is `unavailable`. The redirect
 * mode is `manual`: with `error` a page only learns that `fetch` failed, which is indistinguishable from no network, but a
 * browser answers `manual` with an opaque-redirect response (type `opaqueredirect`, status 0, no status, no location), which
 * is `unavailable` as a 3xx is on the phones. Which 3xx it was is not knowable in a browser; the words do not need it.
 */
export async function postAiJson(
  fetchImpl: FetchLike,
  url: string,
  headers: Record<string, string>,
  body: object,
  timeoutMs: number = REQUEST_TIMEOUT_MS,
): Promise<AiReply> {
  return postAi(fetchImpl, url, { 'Content-Type': 'application/json', ...headers }, JSON.stringify(body), timeoutMs);
}

/**
 * {@link postAiJson} for a `multipart/form-data` body (OpenAI's `/audio/transcriptions`, voice input, ADR-37): the same
 * time limit, redirect mode and failure codes. No `Content-Type` is set, so the browser writes the form's own boundary.
 * The form (the audio in it) stays in memory; neither it nor the answer is logged or put in an error (T-I48).
 */
export async function postAiMultipart(
  fetchImpl: FetchLike,
  url: string,
  headers: Record<string, string>,
  form: FormData,
  timeoutMs = REQUEST_TIMEOUT_MS,
): Promise<AiReply> {
  return postAi(fetchImpl, url, { ...headers }, form, timeoutMs);
}

/** The exchange both helpers share. */
async function postAi(fetchImpl: FetchLike, url: string, headers: Record<string, string>, body: BodyInit, timeoutMs: number): Promise<AiReply> {
  try {
    const res = await fetchImpl(url, {
      method: 'POST',
      headers,
      body,
      redirect: 'manual',
      credentials: 'omit',
      cache: 'no-store',
      referrerPolicy: 'no-referrer',
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (res.type === 'opaqueredirect') throw new OnDeviceAiError('unavailable');
    return { status: res.status, body: await res.text(), retryAfter: res.headers.get('Retry-After') };
  } catch (e) {
    if (e instanceof OnDeviceAiError) throw e;
    if (e instanceof DOMException && (e.name === 'TimeoutError' || e.name === 'AbortError')) throw new OnDeviceAiError('unavailable');
    return { status: 0, body: '', retryAfter: null };
  }
}

/** What a failed response means (the `providerErrors` vectors): the next tier of the ladder, or an error. */
export type ErrorAction =
  | { action: 'nextTier'; tier: 1 | 2 | 3 }
  | { action: 'error'; kind: OnDeviceAiErrorKind; retryAfterSeconds?: number | null };

/** `Retry-After` in whole seconds, or null when absent or not a number. */
export function retryAfterSeconds(header: string | null | undefined): number | null {
  const n = Number.parseInt((header ?? '').trim(), 10);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

/**
 * Status 0 is the network; 401 and 403 a refused key; 404 an unknown model; 429 a rate limit with the seconds of
 * `Retry-After`; anything else (a 400, a 5xx, Anthropic's 529 overloaded, a 3xx) `unavailable`.
 */
export function classifyStatus(status: number, retryAfter: string | null = null): ErrorAction {
  if (status === 0) return { action: 'error', kind: 'unreachable' };
  if (status === 401 || status === 403) return { action: 'error', kind: 'keyRejected' };
  if (status === 404) return { action: 'error', kind: 'modelNotFound' };
  if (status === 429) return { action: 'error', kind: 'rateLimited', retryAfterSeconds: retryAfterSeconds(retryAfter) };
  return { action: 'error', kind: 'unavailable' };
}

/** The error to throw for a verdict that is not the next tier (that one is `unavailable`, which cannot happen at tier 3). */
export function failure(verdict: ErrorAction): OnDeviceAiError {
  if (verdict.action === 'nextTier') return new OnDeviceAiError('unavailable');
  return verdict.kind === 'rateLimited' && verdict.retryAfterSeconds != null
    ? new OnDeviceAiError('rateLimited', verdict.retryAfterSeconds)
    : new OnDeviceAiError(verdict.kind);
}
