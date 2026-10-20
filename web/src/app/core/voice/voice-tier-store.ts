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

import { signal } from '@angular/core';
import { GEMINI_KEY_KEY, SARVAM_KEY_KEY, STORAGE_PREFIX } from '../storage-keys';
import { type CachedTier, keyHash, geminiProbeResult } from './voice-tier';

/**
 * Where the voice free-plan rule keeps what it learned (docs/03 ADR-37, docs/02 RR-33, TC-U-170 pattern): a verdict and an
 * acknowledgement, named by the first 16 hex characters of the SHA-256 of the key they belong to, in the **same browser store
 * as that key** (session storage while the key is not remembered, else local storage). Device-only: never in a backup, copy,
 * sync or share file, and "Remove all data" sweeps it with the other `doorprints.` keys. Neither the key, a response body
 * nor a word of speech is ever written here: the entry is the verdict, a time and the name of the evidence.
 */

const TIER_PREFIX = `${STORAGE_PREFIX}voice.tier.`;
const ACK_PREFIX = `${STORAGE_PREFIX}voice.tier-ack.`;

/** Bumped by every write, so a screen showing a verdict re-reads it. Shared by the service and the AI request hook. */
export const tierVersion = signal(0);

export interface StoredTier {
  verdict: 'ALLOW' | 'REFUSE_FREE';
  checkedAt: number;
  /** `<provider>:<evidence>`, for example `gemini:probe` or `gemini:ordinary-429`. */
  source: string;
}

/** The store that holds `key` under `keyName`: this tab's while it is there, else the remembered one. */
function storeHolding(keyName: string, key: string): Storage | null {
  try {
    if (key !== '' && sessionStorage.getItem(keyName) === key) return sessionStorage;
    return localStorage;
  } catch {
    return null;
  }
}

/** The name of the key's store: the Sarvam key has a slot of its own. */
export function keySlot(provider: string): string {
  return provider === 'sarvam' ? SARVAM_KEY_KEY : GEMINI_KEY_KEY;
}

/** A kept verdict for `fp`, with its age, or null (none kept, unreadable, or a time in the future). */
export function readTier(fp: string, provider: string, key: string, now: number): CachedTier | null {
  try {
    const raw = storeHolding(keySlot(provider), key)?.getItem(TIER_PREFIX + fp);
    if (!raw) return null;
    const v = JSON.parse(raw) as Partial<StoredTier>;
    if ((v.verdict !== 'ALLOW' && v.verdict !== 'REFUSE_FREE') || typeof v.checkedAt !== 'number' || typeof v.source !== 'string') return null;
    if (!v.source.startsWith(`${provider}:`) || v.checkedAt > now) return null;
    return { verdict: v.verdict, ageMs: now - v.checkedAt };
  } catch {
    return null;
  }
}

/** Keeps a definite verdict for `key` (never the key). */
export function writeTier(provider: string, key: string, verdict: StoredTier['verdict'], evidence: string, now: number): void {
  try {
    const entry: StoredTier = { verdict, checkedAt: now, source: `${provider}:${evidence}` };
    storeHolding(keySlot(provider), key)?.setItem(TIER_PREFIX + keyHash(key), JSON.stringify(entry));
  } catch {
    // Storage unavailable: the verdict holds for this page only (the probe runs again next time).
  }
  tierVersion.update((n) => n + 1);
}

/** Whether the person confirmed, for this key and this provider, that the plan is paid. */
export function readAck(fp: string, provider: string, key: string): boolean {
  try {
    const raw = storeHolding(keySlot(provider), key)?.getItem(ACK_PREFIX + fp);
    return raw !== null && raw !== undefined && (JSON.parse(raw) as { provider?: unknown }).provider === provider;
  } catch {
    return false;
  }
}

/** Records or withdraws the confirmation for this key (shown again when the key changes, because the name is the key's). */
export function writeAck(provider: string, key: string, on: boolean): void {
  try {
    const store = storeHolding(keySlot(provider), key);
    const name = ACK_PREFIX + keyHash(key);
    if (on) store?.setItem(name, JSON.stringify({ provider }));
    else store?.removeItem(name);
  } catch {
    // Storage unavailable: nothing is kept, so the confirmation is asked again.
  }
  tierVersion.update((n) => n + 1);
}

/** Drops everything kept for a key that was replaced or removed. */
export function forgetTier(fp: string): void {
  for (const s of [() => sessionStorage, () => localStorage]) {
    try {
      s().removeItem(TIER_PREFIX + fp);
      s().removeItem(ACK_PREFIX + fp);
    } catch {
      // ignore
    }
  }
  tierVersion.update((n) => n + 1);
}

/**
 * A later ordinary Gemini request answered 429 with a free-tier quota id: this key is on a free plan whatever was cached
 * before, so the kept verdict becomes `REFUSE_FREE` at once. Reads the status and the quota id only; keeps neither the body
 * nor the key.
 */
export function noteGeminiAnswer(key: string, status: number, body: string, now: number = Date.now()): void {
  if (status !== 429 || key === '' || geminiProbeResult(status, body) !== 'free') return;
  writeTier('gemini', key, 'REFUSE_FREE', 'ordinary-429', now);
}
