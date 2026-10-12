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

import { parseBody } from './ai-blocked';

/**
 * Whether a provider answered that the person's own key has no free quota or credit left (S4b-BL-243; docs/ai/ai-design.md
 * 2.1), read from the status and named fields only, never from free text a provider may have echoed. A quota used up for
 * the day or the month is not a wait of a minute, so it is its own failure (`quotaExhausted`), not `rateLimited` and not
 * `unavailable`. The same rules as the phones' `AiQuota.kt`, pinned by the shared vectors (`quotaResponses`).
 *  - HTTP 402 from any provider (payment required: a spent balance or an unpaid account).
 *  - Gemini (ai.google.dev/gemini-api/docs/rate-limits): HTTP 429 `RESOURCE_EXHAUSTED` whose
 *    `error.details[].violations[].quotaId` holds `PerDay` (`GenerateRequestsPerDayPerProjectPerModel-FreeTier`). A
 *    `PerMinute` quota id is still `rateLimited` (a wait).
 *  - OpenAI and compatible servers (platform.openai.com/docs/guides/error-codes): HTTP 429 (or 400) whose `error.code`
 *    or `error.type` is `insufficient_quota`. A plain 429 "rate limit reached" is still `rateLimited`.
 *  - Anthropic (platform.claude.com/docs/en/api/errors): `error.type` `billing_error` (HTTP 402), or HTTP 400
 *    `invalid_request_error` whose message says the credit balance is too low (the older form of the same error).
 */

const DAILY = 'PerDay';
const LOW_CREDIT = 'credit balance is too low';

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Obj) : null);
const text = (v: unknown): string => (typeof v === 'string' ? v : '');

/** True when any `quotaId` under `error.details[].violations[]` names a daily quota. */
function dailyQuota(error: Obj): boolean {
  const details = error['details'];
  if (!Array.isArray(details)) return false;
  return details.some((d) => {
    const violations = obj(d)?.['violations'];
    return Array.isArray(violations) && violations.some((v) => text(obj(v)?.['quotaId']).includes(DAILY));
  });
}

/** A failed reply (status and body text) that says the key's free quota or credit is used up. */
export function isQuotaExhausted(status: number, body: string): boolean {
  if (status === 402) return true;
  if (status !== 429 && status !== 400) return false;
  const error = obj(obj(parseBody(body))?.['error']);
  if (!error) return false;
  if (text(error['code']) === 'insufficient_quota' || text(error['type']) === 'insufficient_quota') return true;
  if (text(error['type']) === 'billing_error') return true;
  if (status === 429) return dailyQuota(error);
  return text(error['message']).toLowerCase().includes(LOW_CREDIT);
}
