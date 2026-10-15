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

import { readFileSync, statSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import manifest from '../../../../../docs/ai/evals/voice/clips.json';
import {
  type ClipResult, ENTITY_FIELDS, type VoiceClip, afterRedaction, digitsOf, entityErrorRate, entityHits, formatVoiceSummary, normalise,
  phoneHeard, scoreClip, voiceSetup,
} from './voice-eval';

/**
 * TC-AI-53 (docs/06): the pure parts of the manual `voice` suite (voice PR 7, S4b-BL-224): the setting (skipped without a key),
 * the clip manifest and its size limits, the entity error rate, the phone digits and the redactor, and the summary with the
 * stop rule. Canned transcripts; no provider is called and no audio is played.
 */
type ManifestClip = VoiceClip & { durationSeconds: number; bytes: number };
const clips = manifest.clips as unknown as ManifestClip[];
const clip = (id: string) => clips.find((c) => c.id === id)!;
const dir = '../docs/ai/evals/voice';

describe('the clip manifest and the files', () => {
  it('has at most 8 clips of up to 20 seconds, about 300 KB in all and never above 500 KB, CC0, with fake numbers only', () => {
    expect(clips.length).toBeLessThanOrEqual(8);
    expect(manifest.licence).toContain('CC0-1.0');
    let total = 0;
    for (const c of clips) {
      const size = statSync(`${dir}/${c.file}`).size;
      total += size;
      expect(c.durationSeconds, c.id).toBeLessThanOrEqual(20);
      expect(size, c.id).toBeLessThan(60_000);
      expect(size, c.id).toBe(c.bytes);
      // An Ogg file starts with OggS and carries an Opus head.
      const head = readFileSync(`${dir}/${c.file}`, 'latin1').slice(0, 64);
      expect(head.startsWith('OggS'), c.id).toBe(true);
      expect(head.includes('OpusHead'), c.id).toBe(true);
    }
    expect(total).toBeLessThan(307_200);
    expect(total).toBeLessThan(500_000);
  });

  it('covers English, Hindi, Tamil and Telugu, and every phone number is one of the fake ones', () => {
    expect(new Set(clips.map((c) => c.lang))).toEqual(new Set(['en', 'hi', 'ta', 'te']));
    const fake = ['9845012345', '9900123456', '9820123456'];
    for (const c of clips) if (c.phoneDigits) expect(fake, c.id).toContain(c.phoneDigits);
    const texts = clips.map((c) => c.text).join(' ');
    expect(texts).not.toMatch(/\b[6-9]\d{9}\b/);
  });

  it('every entity the clip asks for has a form that the clip\'s own text satisfies (the reference passes)', () => {
    for (const c of clips) {
      const hits = entityHits(c, c.text);
      for (const f of ENTITY_FIELDS) if (c.entities[f].length > 0) expect(hits[f], `${c.id} ${f}`).toBe(true);
      if (c.phoneDigits) expect(phoneHeard(c.text, c.phoneDigits), c.id).toBe(true);
    }
  });
});

describe('the entity error rate', () => {
  const rent = clip('en-extract-rent');

  it('matches numbers and words as whole tokens in any accepted form', () => {
    for (const said of ['Rent is 28,000 a month in Indiranagar, 2 BHK.', 'two BHK Indira Nagar rent 28000', 'twenty-eight thousand, 2bhk, indiranagar.']) {
      expect(entityHits(rent, said), said).toEqual({ price: true, bhk: true, locality: true });
    }
  });

  it('does not match inside a longer number or word', () => {
    expect(entityHits(rent, 'rent 128000 for a 12 bhk in Indiranagarx').price).toBe(false);
    expect(entityHits(rent, 'rent 128000 for a 12 bhk in Indiranagarx').locality).toBe(false);
  });

  it('counts a missed entity as an error and a field the clip does not ask for as nothing', () => {
    const hits = [entityHits(rent, 'rent 28000 in Indiranagar'), entityHits(rent, 'rent 18000, 2 bhk, Indiranagar')];
    expect(entityErrorRate(hits, 'bhk')).toBe(0.5);
    expect(entityErrorRate(hits, 'price')).toBe(0.5);
    expect(entityErrorRate(hits, 'locality')).toBe(0);
    expect(entityErrorRate([entityHits(clip('en-ask-water'), 'anything')], 'price')).toBeNull();
  });

  it('reads Devanagari, Tamil and Telugu forms, with zero-width joiners ignored', () => {
    expect(entityHits(clip('hi-extract-rent'), 'इंदिरानगर में 2 बीएचके, किराया 28,000 रुपये।')).toEqual({ price: true, bhk: true, locality: true });
    expect(entityHits(clip('ta-extract-rent'), 'இந்திராநகர் 2 பிஎச்கே வாடகை இருபத்தெட்டாயிரம்')).toEqual({ price: true, bhk: true, locality: true });
    expect(entityHits(clip('te-extract-rent'), 'ఇందిరానగర్‌లో')).toEqual({ price: false, bhk: false, locality: true });
    expect(normalise('ఇందిరా‌నగర్ 1.35, 28,000.')).toBe('ఇందిరానగర్ 1.35 28,000');
  });
});

describe('the phone number and the redactor', () => {
  const rent = clip('en-extract-rent');

  it('reads numerals and English or Hindi digit words', () => {
    expect(digitsOf('call nine eight four five zero one two three four five')).toBe('9845012345');
    expect(digitsOf('call 98450 12345')).toBe('9845012345');
    expect(digitsOf('फ़ोन नौ आठ चार पाँच शून्य एक दो तीन चार पाँच')).toBe('9845012345');
    expect(digitsOf('फ़ोन ९८४५० १२३४५')).toBe('9845012345');
    expect(phoneHeard('call 98450-12345 today', '9845012345')).toBe(false); // a dash splits the groups: reported, not hidden
    expect(phoneHeard('call 98450 12345 today', '9845012345')).toBe(true);
  });

  it('numerals are redacted afterwards; digits spoken as words are not (the limit the disclosure states)', () => {
    expect(afterRedaction('call me on 98450 12345 about the flat', '9845012345')).toEqual({ text: 'call me on [phone] about the flat', stillThere: false });
    expect(afterRedaction('call me on nine eight four five zero one two three four five', '9845012345').stillThere).toBe(true);
  });

  it('scoreClip reports heard, numerals and still-there', () => {
    expect(scoreClip(rent, 'Rent 28000, Indiranagar 2 BHK, call 9845012345').phone).toEqual({ heard: true, numerals: true, stillThereAfterRedaction: false });
    expect(scoreClip(rent, 'Rent 28000, call nine eight four five zero one two three four five').phone).toEqual({ heard: true, numerals: false, stillThereAfterRedaction: true });
    expect(scoreClip(rent, 'no number here').phone?.heard).toBe(false);
  });
});

describe('the setting', () => {
  it('is skipped without a key and refuses a wrong setting', () => {
    expect(voiceSetup({})).toEqual({ status: 'skip', line: 'skipped: no key' });
    expect(voiceSetup({ DOORPRINTS_VOICE_KEY: 'k', VOICE_EVAL_PROVIDER: 'x' }).status).toBe('invalid');
    expect(voiceSetup({ DOORPRINTS_VOICE_KEY: 'k', VOICE_EVAL_PROVIDER: 'openai-compatible', VOICE_EVAL_BASE_URL: 'http://evil.example/v1', VOICE_EVAL_MODEL: 'm' }).status).toBe('invalid');
    expect(voiceSetup({ DOORPRINTS_VOICE_KEY: 'k', VOICE_EVAL_PROVIDER: 'openai-compatible', VOICE_EVAL_BASE_URL: 'https://api.groq.com/openai/v1' }).status).toBe('invalid');
    expect(voiceSetup({ DOORPRINTS_VOICE_KEY: 'k', VOICE_EVAL_PROVIDER: 'sarvam', VOICE_EVAL_DELAY_MS: '1x' }).status).toBe('invalid');
  });

  it('runs Gemini and Sarvam with no address or model, and a compatible provider with both', () => {
    expect(voiceSetup({ DOORPRINTS_VOICE_KEY: ' k ', VOICE_EVAL_PROVIDER: 'sarvam', VOICE_EVAL_MODEL: 'ignored', DOORPRINTS_EVAL_KEY: 'c' })).toEqual({
      status: 'run', provider: 'sarvam', key: 'k', baseUrl: '', model: '', chatKey: 'c', delayMs: 4000,
    });
    expect(voiceSetup({ DOORPRINTS_VOICE_KEY: 'k', VOICE_EVAL_PROVIDER: 'openai-compatible', VOICE_EVAL_BASE_URL: 'https://api.groq.com/openai/v1/', VOICE_EVAL_MODEL: 'whisper-large-v3-turbo', VOICE_EVAL_DELAY_MS: '0' }))
      .toMatchObject({ status: 'run', baseUrl: 'https://api.groq.com/openai/v1', model: 'whisper-large-v3-turbo', delayMs: 0 });
  });
});

describe('the summary and the stop rule', () => {
  const meta = { provider: 'sarvam', host: 'api.sarvam.ai', model: '' };
  const result = (id: string, transcript: string | null): ClipResult => ({ clip: clip(id), transcript, ...(transcript === null ? { error: 'unreachable' } : scoreClip(clip(id), transcript)) });

  it('prints no stop line when price and BHK are heard in hi, ta and te, and never prints the key or a number it was not given', () => {
    const text = formatVoiceSummary(meta, [
      result('hi-extract-rent', 'इंदिरानगर 2 बीएचके 28000'), result('ta-extract-rent', 'இந்திராநகர் 2 பிஎச்கே 28000'), result('te-extract-rent', 'ఇందిరానగర్ 2 బీహెచ్‌కే 28000'),
    ], null);
    expect(text).toContain('Stop rule: entity error on price and BHK is not above 20% for Hindi, Tamil or Telugu');
    expect(text).not.toContain('STOP RULE:');
    expect(text).toContain('Provider: sarvam at api.sarvam.ai');
  });

  it('says the stop rule was not evaluated when a language was not run', () => {
    expect(formatVoiceSummary(meta, [result('en-extract-rent', 'rent 28000')], 'key rejected')).toContain('Stop rule: not evaluated');
  });

  it('prints the stop line for a language above 20%, counts a failed call as all missed, and lists the redaction result', () => {
    const text = formatVoiceSummary(meta, [
      result('hi-extract-rent', 'कुछ और'), result('ta-extract-rent', null), result('te-extract-rent', 'ఇందిరానగర్ 2 బీహెచ్‌కే 28000'), result('en-extract-rent', 'rent 28000 2 bhk indiranagar call 9845012345'),
    ], 'provider unreachable');
    expect(text).toContain('STOP: provider unreachable'.replace('STOP:', 'STOPPED:'));
    expect(text).toMatch(/STOP RULE: .*hi \(price 100%, BHK 100%\), ta \(price 100%, BHK 100%\)/);
    expect(text).toContain('| en-extract-rent | en | ok | ok | ok | yes, digits | yes |');
  });
});
