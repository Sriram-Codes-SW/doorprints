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

import { validateWebBaseUrl } from '../ai/ai-provider-config';
import { redactGeneric } from '../ai/ai-core';

/**
 * The pure parts of the manual `voice` suite of the *AI evals* workflow (voice PR 7, S4b-BL-224, docs/ai/voice-input.md §5):
 * which setting the run is (skipped without a key), the entity error rate on price, BHK and locality, whether the phone
 * digits were heard and whether the redactor then removes them, and the summary with the stop rule. The live spec
 * (`voice-clips.live.spec.ts`) sends the eight clips of `docs/ai/evals/voice/` to the chosen provider; everything here runs in
 * the normal build against canned transcripts (`voice-eval.spec.ts`). The clips are synthetic speech about made-up listings
 * with fake phone numbers; reported, not gating (only the stop rule is printed as a verdict line).
 */

export type VoiceEvalProvider = 'gemini' | 'sarvam' | 'openai-compatible';
export const VOICE_PROVIDERS: readonly VoiceEvalProvider[] = ['gemini', 'sarvam', 'openai-compatible'];

export type VoiceEvalSetup =
  | { status: 'skip'; line: string }
  | { status: 'invalid'; line: string }
  | {
      status: 'run';
      provider: VoiceEvalProvider;
      key: string;
      baseUrl: string;
      model: string;
      /** The Gemini key for the end-to-end Extract and Ask, or '' (then those rows say "not run"). */
      chatKey: string;
      delayMs: number;
    };

/** Reads the run's setting from the environment the workflow passes. Without a speech key: `skipped: no key`. */
export function voiceSetup(env: Record<string, string | undefined>): VoiceEvalSetup {
  const key = (env['DOORPRINTS_VOICE_KEY'] ?? '').trim();
  if (key === '') return { status: 'skip', line: 'skipped: no key' };
  const invalid = (line: string): VoiceEvalSetup => ({ status: 'invalid', line });
  const provider = (env['VOICE_EVAL_PROVIDER'] ?? '').trim() as VoiceEvalProvider;
  if (!VOICE_PROVIDERS.includes(provider)) return invalid('provider must be gemini, sarvam or openai-compatible');
  let baseUrl = '';
  let model = (env['VOICE_EVAL_MODEL'] ?? '').trim();
  if (provider === 'openai-compatible') {
    const check = validateWebBaseUrl((env['VOICE_EVAL_BASE_URL'] ?? '').trim());
    if (!check.valid) return invalid(`base URL is not allowed: ${check.reason}`);
    baseUrl = check.normalised;
    if (model === '') return invalid('a transcription model is required');
  } else {
    model = '';
  }
  const delay = env['VOICE_EVAL_DELAY_MS'] ?? '4000';
  if (!/^\d{1,6}$/.test(delay)) return invalid('delay must be a whole number of milliseconds');
  return { status: 'run', provider, key, baseUrl, model, chatKey: (env['DOORPRINTS_EVAL_KEY'] ?? '').trim(), delayMs: Number(delay) };
}

/** One clip of `docs/ai/evals/voice/clips.json`. */
export interface VoiceClip {
  id: string;
  lang: 'en' | 'hi' | 'ta' | 'te';
  file: string;
  text: string;
  entities: { price: string[]; bhk: string[]; locality: string[] };
  phoneDigits?: string;
  extract?: { price: number; bedrooms: number; locality: string };
  ask?: { mustContain: string[] };
}

export type EntityField = 'price' | 'bhk' | 'locality';
export const ENTITY_FIELDS: readonly EntityField[] = ['price', 'bhk', 'locality'];

/**
 * Lower case, no zero-width joiners, sentence punctuation as spaces (a dot or comma between digits stays: 1.35, 28,000), one
 * space between words. Both the transcript and the accepted forms go through it.
 */
export function normalise(text: string): string {
  return text
    .normalize('NFC')
    .toLowerCase()
    .replace(/[​-‍⁠﻿]/g, '')
    .replace(/(?<![\p{N}]),|,(?![\p{N}])/gu, ' ')
    .replace(/(?<![\p{N}])\.|\.(?![\p{N}])/gu, ' ')
    .replace(/[।॥!?;:"'“”‘’()\[\]{}₹]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Whether `form` (already normalised) stands as a whole word or number in `text` (normalised). Tamil and Telugu join their
 * case endings to the noun (ఇందిరానగర్‌లో, இந்திராநகரில்), so a form in those scripts may be followed by more letters.
 */
function holds(text: string, form: string): boolean {
  if (form === '') return false;
  const escaped = form.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const joins = /[\p{Script=Tamil}\p{Script=Telugu}]$/u.test(form);
  const after = joins ? '' : '(?![\\p{L}\\p{N}\\p{M}])';
  return new RegExp(`(?<![\\p{L}\\p{N}\\p{M}])${escaped}${after}`, 'u').test(text);
}

/** For each field the clip names: true when the transcript holds one accepted form; fields with no forms are absent. */
export function entityHits(clip: VoiceClip, transcript: string): Partial<Record<EntityField, boolean>> {
  const heard = normalise(transcript);
  const out: Partial<Record<EntityField, boolean>> = {};
  for (const f of ENTITY_FIELDS) {
    const forms = clip.entities[f];
    if (forms.length > 0) out[f] = forms.some((form) => holds(heard, normalise(form)));
  }
  return out;
}

/** The entity error rate of a field over some clips: the entities missed over the entities asked for (0 to 1), or null if none. */
export function entityErrorRate(hits: readonly Partial<Record<EntityField, boolean>>[], field: EntityField): number | null {
  const asked = hits.filter((h) => h[field] !== undefined);
  return asked.length === 0 ? null : asked.filter((h) => h[field] === false).length / asked.length;
}

const WORD_DIGITS: Readonly<Record<string, string>> = {
  zero: '0', oh: '0', one: '1', two: '2', three: '3', four: '4', five: '5', six: '6', seven: '7', eight: '8', nine: '9',
  'शून्य': '0', 'एक': '1', 'दो': '2', 'तीन': '3', 'चार': '4', 'पाँच': '5', 'पांच': '5', 'छह': '6', 'छः': '6', 'छे': '6',
  'सात': '7', 'आठ': '8', 'नौ': '9',
};

/** Digits of a script other than ASCII (Devanagari, Tamil, Telugu) as ASCII digits; every other character is unchanged. */
function asciiDigits(text: string): string {
  return text.replace(/\p{Nd}/gu, (c) => {
    const code = c.codePointAt(0)!;
    for (const zero of [0x0966, 0x0be6, 0x0c66, 0x0660, 0x06f0]) if (code >= zero && code <= zero + 9) return String(code - zero);
    return c;
  });
}

/** The digits a transcript carries, as numerals or as English or Hindi digit words, in order (all other words are skipped). */
export function digitsOf(text: string): string {
  const words = asciiDigits(normalise(text)).split(' ');
  return words.map((w) => (/^\d+$/.test(w) ? w : (WORD_DIGITS[w] ?? ''))).join('');
}

/** Whether the transcript carries the clip's phone number (as numerals or digit words). */
export function phoneHeard(transcript: string, digits: string): boolean {
  return digitsOf(transcript).includes(digits);
}

/**
 * The transcript after the redactor of the Ask and Extract paths (`redactGeneric`, the website's twin of `ContactRedactor`),
 * and whether the clip's number is still in it. A number spoken as words stays: the redactor reads numerals, which is why
 * the disclosure says a number said aloud cannot be left out the way a typed one is (docs/ai/voice-input.md §3).
 */
export function afterRedaction(transcript: string, digits: string): { text: string; stillThere: boolean } {
  const text = redactGeneric(transcript) ?? '';
  return { text, stillThere: phoneHeard(text, digits) };
}

/** One clip's outcome. */
export interface ClipResult {
  clip: VoiceClip;
  /** null: the call failed (see `error`). */
  transcript: string | null;
  error?: string;
  hits?: Partial<Record<EntityField, boolean>>;
  phone?: { heard: boolean; numerals: boolean; stillThereAfterRedaction: boolean };
  extract?: { ok: boolean; got: string } | 'not run';
  ask?: { ok: boolean; grounded: boolean } | 'not run';
}

/** Scores one transcript (no network). */
export function scoreClip(clip: VoiceClip, transcript: string): Pick<ClipResult, 'hits' | 'phone'> {
  const out: Pick<ClipResult, 'hits' | 'phone'> = { hits: entityHits(clip, transcript) };
  if (clip.phoneDigits) {
    const redacted = afterRedaction(transcript, clip.phoneDigits);
    out.phone = {
      heard: phoneHeard(transcript, clip.phoneDigits),
      numerals: /\d{6,}|\d{4,5}[ -]\d{5}/.test(asciiDigits(transcript)),
      stillThereAfterRedaction: redacted.stillThere,
    };
  }
  return out;
}

/** The stop rule of the plan (docs/ai/voice-input.md §6): entity error above 20% on price and BHK for hi, ta or te. */
export const STOP_RATE = 0.2;

/** The languages whose price and BHK error rates are checked against {@link STOP_RATE}. */
export const STOP_LANGUAGES: readonly string[] = ['hi', 'ta', 'te'];

const pct = (r: number | null) => (r === null ? '-' : `${Math.round(r * 100)}%`);

/** The Markdown summary of a run: per clip, per language, the stop rule, and what the run did not measure. */
export function formatVoiceSummary(meta: { provider: string; host: string; model: string }, results: readonly ClipResult[], stopped: string | null): string {
  const lines: string[] = [];
  lines.push('## Voice clips eval', '');
  lines.push(`Provider: ${meta.provider} at ${meta.host}${meta.model ? `, model \`${meta.model}\`` : ''}. The key is not shown.`);
  lines.push(`Clips: ${results.length}. Synthetic speech (eSpeak NG), made-up listings, fake phone numbers. Informational: nothing here gates a build.`, '');
  if (stopped) lines.push(`STOPPED: ${stopped}`, '');
  lines.push('| Clip | Language | Price | BHK | Locality | Phone heard | Redacted after | Extract | Ask |', '|---|---|---|---|---|---|---|---|---|');
  const mark = (v: boolean | undefined) => (v === undefined ? '-' : v ? 'ok' : 'MISSED');
  for (const r of results) {
    if (r.transcript === null) {
      lines.push(`| ${r.clip.id} | ${r.clip.lang} | error: ${r.error ?? 'failed'} | | | | | | |`);
      continue;
    }
    const phone = r.phone;
    const extract = r.extract === undefined || r.extract === 'not run' ? 'not run' : r.extract.ok ? 'ok' : `off (${r.extract.got})`;
    const ask = r.ask === undefined ? '-' : r.ask === 'not run' ? 'not run' : r.ask.ok ? (r.ask.grounded ? 'ok' : 'ok, not grounded') : 'off';
    lines.push(
      `| ${r.clip.id} | ${r.clip.lang} | ${mark(r.hits?.price)} | ${mark(r.hits?.bhk)} | ${mark(r.hits?.locality)} | ` +
        `${phone ? (phone.heard ? (phone.numerals ? 'yes, digits' : 'yes, as words') : 'NO') : '-'} | ` +
        `${phone ? (!phone.heard ? '-' : phone.stillThereAfterRedaction ? 'NO, still there' : 'yes') : '-'} | ${extract} | ${ask} |`,
    );
  }
  lines.push('', '### Entity error rate by language (missed over asked; very few clips per language, read as a smoke test)', '');
  lines.push('| Language | Price | BHK | Locality |', '|---|---|---|---|');
  const langs = [...new Set(results.map((r) => r.clip.lang))];
  const exceeded: string[] = [];
  for (const lang of langs) {
    const hits = results.filter((r) => r.clip.lang === lang && r.transcript !== null).map((r) => r.hits ?? {});
    const failed = results.filter((r) => r.clip.lang === lang && r.transcript === null).length;
    // A clip whose call failed counts as every entity missed.
    const all = [...hits, ...Array.from({ length: failed }, () => ({ price: false, bhk: false, locality: false }))];
    const rates = Object.fromEntries(ENTITY_FIELDS.map((f) => [f, entityErrorRate(all, f)])) as Record<EntityField, number | null>;
    lines.push(`| ${lang} | ${pct(rates.price)} | ${pct(rates.bhk)} | ${pct(rates.locality)} |`);
    if (STOP_LANGUAGES.includes(lang) && ((rates.price ?? 0) > STOP_RATE || (rates.bhk ?? 0) > STOP_RATE)) {
      exceeded.push(`${lang} (price ${pct(rates.price)}, BHK ${pct(rates.bhk)})`);
    }
  }
  lines.push('');
  const covered = STOP_LANGUAGES.every((l) => results.some((r) => r.clip.lang === l));
  lines.push(
    !covered
      ? 'Stop rule: not evaluated (no Hindi, Tamil or Telugu clip was run).'
      : exceeded.length > 0
      ? `STOP RULE: entity error above ${STOP_RATE * 100}% on price or BHK for ${exceeded.join(', ')}: keep voice for English (India) only or drop it for that provider (docs/ai/voice-input.md §6).`
      : `Stop rule: entity error on price and BHK is not above ${STOP_RATE * 100}% for Hindi, Tamil or Telugu in this run.`,
  );
  lines.push('', 'Not measured: real voices, noise, accents, longer speech (the clips are 3 to 12 seconds of synthetic speech).');
  return lines.join('\n') + '\n';
}
