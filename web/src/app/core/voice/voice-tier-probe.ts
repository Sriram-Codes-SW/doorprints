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

import { type FetchLike, postAiJson } from '../ai/ai-request';
import {
  OPENROUTER_KEY_URL, type TierProbe, geminiProbeBody, geminiProbeResult, geminiProbeUrl, openRouterKeyResult,
} from './voice-tier';

/**
 * The only two calls the free-plan rule makes to read a plan (docs/03 ADR-37). They run once, after the person turned *Voice
 * input* on and only online, with the person's own key straight to the provider, and carry no audio, no house or candidate
 * data and nothing about the device: Gemini gets one fixed one-word prompt asking for one token; OpenRouter gets a GET with
 * no body. Neither answer is kept or logged: only the verdict read from it (`voice-tier-store.ts`).
 */

/** A probe is not worth waiting a minute for. */
export const PROBE_TIMEOUT_MS = 15_000;

/** One GET with the same guards as `postAiJson`: no redirect, no credentials, no referrer, no cache, a time limit. */
async function getJson(fetchImpl: FetchLike, url: string, headers: Record<string, string>): Promise<{ status: number; body: string }> {
  try {
    const res = await fetchImpl(url, {
      method: 'GET', headers, redirect: 'manual', credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
      signal: AbortSignal.timeout(PROBE_TIMEOUT_MS),
    });
    if (res.type === 'opaqueredirect') return { status: 0, body: '' };
    return { status: res.status, body: await res.text() };
  } catch {
    return { status: 0, body: '' };
  }
}

/** Reads the plan behind `key` for `provider`; `unknown` for any provider whose plan no call can tell. */
export async function probeTier(provider: string, key: string, fetchImpl: FetchLike): Promise<TierProbe> {
  if (provider === 'gemini') {
    const res = await postAiJson(fetchImpl, geminiProbeUrl(), { 'x-goog-api-key': key }, geminiProbeBody(), PROBE_TIMEOUT_MS).catch(() => ({ status: 0, body: '' }));
    return geminiProbeResult(res.status, res.body);
  }
  if (provider === 'openrouter') {
    const res = await getJson(fetchImpl, OPENROUTER_KEY_URL, { Authorization: `Bearer ${key}` });
    return openRouterKeyResult(res.status, res.body);
  }
  return 'unknown';
}
