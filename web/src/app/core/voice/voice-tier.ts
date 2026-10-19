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
 * Whether voice input may send the speaker's audio to the person's provider with the key they gave (owner decision
 * 2026-10-19; docs/03 ADR-37, docs/02 RR-33 and T-I46). A free plan may let the provider use what is said to improve its
 * products, so voice input refuses a free plan and lets a paid one through. This is the rule and the readings of the evidence
 * that feed it; it has no screen and no network. The twin is `VoiceTier.kt` in `:shared`; both run
 * `docs/schemas/voice-tier-vectors.json`.
 *
 * Evidence, per provider (read from the providers' own pages on 2026-10-19; docs/ai/voice-input.md section 8):
 *  - Gemini: a 429 whose `error.details[].violations[].quotaId` contains `FreeTier` (a paid project never returns one). The
 *    probe is one call to a model that has no free tier, so a free key is refused with that quota id.
 *  - OpenRouter: `GET /api/v1/key` answers `data.is_free_tier`; a model id ending `:free` is free whatever the key.
 *  - OpenAI: no free API tier, so paid by policy (the opt-in complimentary-token programme is disclosed, not detectable).
 *  - Groq and Sarvam: the tier cannot be read by any API, so unknown.
 *  - Ollama, LM Studio and a custom address: allowed (nothing leaves the device, or the address is the person's own choice).
 * Unknown refuses until the person says, once per key, that the plan is paid. A confirmed free plan has no such way out.
 */

/** What the rule says. `CHECKING` is only ever transient: the probe has not answered yet. */
export type VoiceVerdict = 'ALLOW' | 'REFUSE_FREE' | 'REFUSE_UNKNOWN' | 'CHECKING';

/** What a probe (or the lack of one) found: `none` not asked yet, `running`, `free`, `paid`, or `unknown` (asked, no signal). */
export type TierProbe = 'none' | 'running' | 'free' | 'paid' | 'unknown';

/** A kept verdict. Only definite evidence is ever kept: `ALLOW` (paid) or `REFUSE_FREE`. */
export interface CachedTier {
  verdict: 'ALLOW' | 'REFUSE_FREE';
  ageMs: number;
}

export interface VoiceTierInput {
  /** `gemini`, `openai`, `openrouter`, `groq`, `sarvam`, `ollama`, `lmstudio`, `custom` or another id. */
  provider: string;
  /** The transcription model the request would name ('' when none). */
  model: string;
  probe: TierProbe;
  cached: CachedTier | null;
  /** The person confirmed, for this key, that the plan is paid. */
  acknowledged: boolean;
}

/** A paid verdict is kept for 7 days; a free one for 24 hours (a person may upgrade). */
export const ALLOW_TTL_MS = 7 * 24 * 3_600_000;
export const REFUSE_FREE_TTL_MS = 24 * 3_600_000;
/** The first 16 hex characters of the SHA-256 of a key name its kept verdict; never the key, never reversible to it. */
export const FINGERPRINT_HEX = 16;

/** Services that run on the device: nothing leaves it. A custom address is the person's own choice. */
const OWN_PLACES: readonly string[] = ['ollama', 'lmstudio', 'custom'];
/** Providers whose evidence is read by a call, so "not asked yet" is `CHECKING`. */
const PROBED: readonly string[] = ['gemini', 'openrouter'];

/** True for a model id ending `:free` (OpenRouter's free variants), whatever the case. */
export function isFreeModel(model: string): boolean {
  return model.trim().toLowerCase().endsWith(':free');
}

/** The rule (the `rule` vectors). A free signal is never turned into `ALLOW`; acknowledging only opens an unknown plan. */
export function decideVoiceTier(i: VoiceTierInput): VoiceVerdict {
  const provider = i.provider.toLowerCase();
  if (OWN_PLACES.includes(provider)) return 'ALLOW';
  if (isFreeModel(i.model)) return 'REFUSE_FREE';
  const fresh = i.cached !== null && i.cached.ageMs < (i.cached.verdict === 'ALLOW' ? ALLOW_TTL_MS : REFUSE_FREE_TTL_MS) ? i.cached : null;
  if (i.probe === 'free' || fresh?.verdict === 'REFUSE_FREE') return 'REFUSE_FREE';
  if (i.probe === 'paid' || fresh?.verdict === 'ALLOW') return 'ALLOW';
  if (i.probe === 'running') return 'CHECKING';
  if (provider === 'openai') return 'ALLOW';
  if (i.probe === 'none' && PROBED.includes(provider)) return 'CHECKING';
  return i.acknowledged ? 'ALLOW' : 'REFUSE_UNKNOWN';
}

/** True when the rule needs a call to read this provider's evidence. */
export function needsProbe(provider: string): boolean {
  return PROBED.includes(provider.toLowerCase());
}

/** True when an unknown plan can be confirmed by the person (every provider the rule cannot read). */
export function canAcknowledge(provider: string): boolean {
  const p = provider.toLowerCase();
  return !OWN_PLACES.includes(p) && p !== 'openai';
}

/** The model Gemini is probed with: documented as having no free tier (ai.google.dev pricing, read 2026-10-19). */
export const GEMINI_PROBE_MODEL = 'gemini-3.1-pro-preview';
/** The fixed one-word prompt; no audio, no candidate data, no device data. */
export const GEMINI_PROBE_PROMPT = 'Hi';

/** The URL of the one Gemini probe call (the key goes in `x-goog-api-key`, set by the caller). */
export function geminiProbeUrl(): string {
  return `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_PROBE_MODEL}:generateContent`;
}

export function geminiProbeBody(): object {
  return { contents: [{ role: 'user', parts: [{ text: GEMINI_PROBE_PROMPT }] }], generationConfig: { maxOutputTokens: 1, temperature: 0 } };
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj | null => (typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Obj) : null);

function parse(body: string): unknown {
  try {
    return JSON.parse(body) as unknown;
  } catch {
    return null;
  }
}

/** Every `quotaId` under `error.details[].violations[]`. */
function quotaIds(body: unknown): string[] {
  const details = obj(obj(body)?.['error'])?.['details'];
  if (!Array.isArray(details)) return [];
  const ids: string[] = [];
  for (const d of details) {
    const violations = obj(d)?.['violations'];
    if (!Array.isArray(violations)) continue;
    for (const v of violations) {
      const id = obj(v)?.['quotaId'];
      if (typeof id === 'string') ids.push(id);
    }
  }
  return ids;
}

/**
 * What Gemini's answer to the probe says (the `geminiProbe` vectors): an answer from a model that has no free tier is a paid
 * project; a 429 naming a `FreeTier` quota id is a free one; a 429 naming only other quotas is a paid one that is being rate
 * limited; anything else (a refused key, a missing model, no network) says nothing.
 */
export function geminiProbeResult(status: number, body: string): TierProbe {
  if (status >= 200 && status < 300) return 'paid';
  if (status !== 429) return 'unknown';
  const ids = quotaIds(parse(body));
  if (ids.length === 0) return 'unknown';
  return ids.some((id) => id.includes('FreeTier')) ? 'free' : 'paid';
}

/** The URL of OpenRouter's key information (the key goes in `Authorization` by the caller). */
export const OPENROUTER_KEY_URL = 'https://openrouter.ai/api/v1/key';

/** What OpenRouter's key information says (the `openRouterKeyInfo` vectors): `data.is_free_tier` as a real boolean, else nothing. */
export function openRouterKeyResult(status: number, body: string): TierProbe {
  if (status < 200 || status >= 300) return 'unknown';
  const flag = obj(obj(parse(body))?.['data'])?.['is_free_tier'];
  return flag === true ? 'free' : flag === false ? 'paid' : 'unknown';
}

const K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be,
  0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa,
  0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85,
  0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
  0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
  0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

const rotr = (x: number, n: number): number => (x >>> n) | (x << (32 - n));

/** SHA-256 of `text` (UTF-8) as 64 hex characters. Synchronous, so a verdict can be read in the same turn as the key. */
function sha256Hex(text: string): string {
  const data = new TextEncoder().encode(text);
  const padded = new Uint8Array(((data.length + 9 + 63) >> 6) << 6);
  padded.set(data);
  padded[data.length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, Math.floor((data.length * 8) / 0x100000000));
  view.setUint32(padded.length - 4, (data.length * 8) >>> 0);
  const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const w = new Array<number>(64);
  for (let off = 0; off < padded.length; off += 64) {
    for (let t = 0; t < 16; t++) w[t] = view.getUint32(off + t * 4);
    for (let t = 16; t < 64; t++) {
      const s0 = rotr(w[t - 15], 7) ^ rotr(w[t - 15], 18) ^ (w[t - 15] >>> 3);
      const s1 = rotr(w[t - 2], 17) ^ rotr(w[t - 2], 19) ^ (w[t - 2] >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
    }
    let [a, b, c, d, e, f, g, hh] = h;
    for (let t = 0; t < 64; t++) {
      const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[t] + w[t]) | 0;
      const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + hh) | 0;
  }
  return h.map((x) => (x >>> 0).toString(16).padStart(8, '0')).join('');
}

/**
 * What names a key's kept verdict and acknowledgement: the first 16 hex characters of its SHA-256 (64 of 256 bits). The key
 * cannot be recovered from it, and the key itself is never kept here (the `fingerprint` vectors).
 */
export function keyHash(key: string): string {
  return sha256Hex(key).slice(0, FINGERPRINT_HEX);
}
