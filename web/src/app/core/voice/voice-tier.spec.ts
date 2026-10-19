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

import { describe, expect, it } from 'vitest';
import vectorsJson from '../../../../../docs/schemas/voice-tier-vectors.json';
import {
  ALLOW_TTL_MS, GEMINI_PROBE_MODEL, GEMINI_PROBE_PROMPT, REFUSE_FREE_TTL_MS, type CachedTier, type TierProbe, type VoiceVerdict,
  canAcknowledge, decideVoiceTier, geminiProbeBody, geminiProbeResult, geminiProbeUrl, keyFingerprint, openRouterKeyResult,
} from './voice-tier';

/**
 * TC-U-286 (docs/06): the free-plan rule for voice input (owner decision 2026-10-19, ADR-37, RR-33), run on the shared vectors
 * `docs/schemas/voice-tier-vectors.json` that `VoiceTierTest` in `:shared` runs too. Confirmed free never becomes ALLOW,
 * whatever else is true; the readings of the two probes; the key's fingerprint.
 */
interface Vectors {
  constants: { allowTtlMs: number; refuseFreeTtlMs: number; fingerprintHexLength: number; geminiProbeModel: string; geminiProbePrompt: string; geminiProbeMaxOutputTokens: number };
  rule: { note: string; provider: string; model: string; probe: TierProbe; cached: CachedTier | null; acknowledged: boolean; expect: VoiceVerdict }[];
  geminiProbe: { note: string; status: number; body: string; expect: TierProbe }[];
  openRouterKeyInfo: { note: string; status: number; body: string; expect: TierProbe }[];
  fingerprint: { input: string; expect: string }[];
}
const vectors = vectorsJson as unknown as Vectors;

describe('voice free-plan rule (shared vectors)', () => {
  it('the constants of the vectors are the constants of the code', () => {
    expect(vectors.constants.allowTtlMs).toBe(ALLOW_TTL_MS);
    expect(vectors.constants.refuseFreeTtlMs).toBe(REFUSE_FREE_TTL_MS);
    expect(vectors.constants.fingerprintHexLength).toBe(16);
    expect(vectors.constants.geminiProbeModel).toBe(GEMINI_PROBE_MODEL);
    expect(vectors.constants.geminiProbePrompt).toBe(GEMINI_PROBE_PROMPT);
  });

  it.each(vectors.rule.map((c) => [c.note, c] as const))('rule: %s', (_note, c) => {
    expect(decideVoiceTier({ provider: c.provider, model: c.model, probe: c.probe, cached: c.cached, acknowledged: c.acknowledged })).toBe(c.expect);
  });

  it('a free signal is never ALLOW, for every provider, every acknowledgement and every cache', () => {
    const providers = ['gemini', 'openai', 'openrouter', 'groq', 'sarvam', 'somewhere-new'];
    const caches: (CachedTier | null)[] = [null, { verdict: 'ALLOW', ageMs: 0 }, { verdict: 'REFUSE_FREE', ageMs: 0 }];
    for (const provider of providers) {
      for (const acknowledged of [false, true]) {
        for (const cached of caches) {
          expect(decideVoiceTier({ provider, model: '', probe: 'free', cached, acknowledged })).toBe('REFUSE_FREE');
        }
      }
      expect(decideVoiceTier({ provider, model: 'x/y:free', probe: 'paid', cached: null, acknowledged: true })).toBe('REFUSE_FREE');
      expect(decideVoiceTier({ provider, model: '', probe: 'none', cached: { verdict: 'REFUSE_FREE', ageMs: 5 }, acknowledged: true })).toBe('REFUSE_FREE');
    }
  });

  it.each(vectors.geminiProbe.map((c) => [c.note, c] as const))('Gemini probe: %s', (_note, c) => {
    expect(geminiProbeResult(c.status, c.body)).toBe(c.expect);
  });

  it.each(vectors.openRouterKeyInfo.map((c) => [c.note, c] as const))('OpenRouter key info: %s', (_note, c) => {
    expect(openRouterKeyResult(c.status, c.body)).toBe(c.expect);
  });

  it.each(vectors.fingerprint.map((c) => [JSON.stringify(c.input).slice(0, 30), c] as const))('fingerprint of %s is the first 16 hex of its SHA-256', (_n, c) => {
    expect(keyFingerprint(c.input)).toBe(c.expect);
    expect(keyFingerprint(c.input)).toHaveLength(16);
  });

  it('the fingerprint is a hash: a key that differs by one character shares nothing, and the key is not in it', () => {
    const a = keyFingerprint('placeholder-key-aaaa');
    const b = keyFingerprint('placeholder-key-aaab');
    expect(a).not.toBe(b);
    expect(a).toMatch(/^[0-9a-f]{16}$/);
    expect('placeholder-key-aaaa').not.toContain(a);
    expect(a).not.toContain('placeholder');
  });

  it('the Gemini probe sends one fixed one-word prompt asking for one token, to a model with no free tier, and nothing else', () => {
    expect(geminiProbeUrl()).toBe(`https://generativelanguage.googleapis.com/v1beta/models/${vectors.constants.geminiProbeModel}:generateContent`);
    expect(geminiProbeBody()).toEqual({
      contents: [{ role: 'user', parts: [{ text: vectors.constants.geminiProbePrompt }] }],
      generationConfig: { maxOutputTokens: vectors.constants.geminiProbeMaxOutputTokens, temperature: 0 },
    });
    expect(JSON.stringify(geminiProbeBody())).not.toMatch(/inlineData|audio|systemInstruction/);
  });

  it('only providers the rule cannot read may be confirmed by the person', () => {
    expect(['groq', 'sarvam', 'gemini', 'openrouter'].every(canAcknowledge)).toBe(true);
    expect(['openai', 'ollama', 'lmstudio', 'custom'].some(canAcknowledge)).toBe(false);
  });
});
