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
import { provideHttpClient, withFetch } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import golden from '../../../../../docs/ai/evals/golden-set.json';
import manifest from '../../../../../docs/ai/evals/voice/clips.json';
import { LocalStore } from '../../data/local-store.service';
import { fixtureRecords } from '../ai/ai-eval';
import { OnDeviceAiError, OnDeviceAiService } from '../ai/on-device-ai.service';
import { GeminiTranscriber, OpenAiAudioTranscriber, SARVAM_HOST, SarvamTranscriber, type Transcriber } from '../ai/transcriber';
import { aiHostOf } from '../ai/ai-provider-config';
import { type ClipResult, type VoiceClip, formatVoiceSummary, scoreClip, voiceSetup } from './voice-eval';
import { keyTermsFor } from './voice-input.service';

type NodeProcess = {
  env?: Record<string, string | undefined>;
  getBuiltinModule?: (id: string) => { writeFileSync(path: string, data: string): void; readFileSync(path: string): Uint8Array };
};
const proc = (globalThis as { process?: NodeProcess }).process;
const setup = voiceSetup(proc?.env ?? {});
const LANG_HINT: Readonly<Record<string, string>> = { en: 'en-IN', hi: 'hi-IN', ta: 'ta-IN', te: 'te-IN' };

/**
 * The manual `voice` suite of the *AI evals* workflow (voice PR 7, S4b-BL-224, docs/06 TC-AI-53): the eight clips of
 * `docs/ai/evals/voice/` (synthetic speech about made-up listings, fake phone numbers) through the chosen speech provider, then
 * the entity error rate on price, BHK and locality, whether the phone digits were heard and are removed by the redactor, and,
 * with a Gemini chat key, one end-to-end Extract per clip and one Ask. The keys come from the environment the workflow sets
 * (`DOORPRINTS_VOICE_KEY`, `DOORPRINTS_EVAL_KEY`); without a speech key the workflow prints `skipped: no key` and this spec
 * skips itself, so the normal build never calls a provider. Reported, not gating: it fails only on a wrong setting, a provider
 * that could not be used, or when no clip got an answer.
 */
describe.skipIf(setup.status === 'skip')('Voice clips through the chosen speech provider (real key)', () => {
  it('transcribes the clips and writes the summary', { timeout: 3_600_000 }, async () => {
    if (setup.status !== 'run') throw new Error(setup.line);
    const fs = proc?.getBuiltinModule?.('node:fs');
    if (!fs) throw new Error('the Node file module is not available');
    const clips = manifest.clips as unknown as VoiceClip[];
    const transcriber: Transcriber =
      setup.provider === 'gemini' ? new GeminiTranscriber(setup.key)
      : setup.provider === 'sarvam' ? new SarvamTranscriber(setup.key)
      : new OpenAiAudioTranscriber({ baseUrl: setup.baseUrl, model: setup.model, prompt: keyTermsFor('house') }, setup.key);
    const host = setup.provider === 'gemini' ? aiHostOf({ kind: 'gemini', baseUrl: '', model: '' })
      : setup.provider === 'sarvam' ? SARVAM_HOST : aiHostOf({ kind: 'openai-compatible', baseUrl: setup.baseUrl, model: setup.model });

    // The fixtures of the golden set stand in for the person's saved houses (the Ask clip).
    const now = '2026-09-20T10:00:00Z';
    const houses = fixtureRecords(golden.fixtureHouses as never, now);
    const visits = (golden.fixtureVisits as object[]).map((v) => ({ ...v, deleted: false, updatedAt: now }));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withFetch()),
        {
          provide: LocalStore,
          useValue: {
            allHouses: async () => houses, allVisits: async () => visits, viewings: { all: async () => [] }, areas: { all: async () => [] },
            places: { all: async () => [] }, areaNotes: { rows: async () => [] },
          },
        },
      ],
    });
    const ai = TestBed.inject(OnDeviceAiService);
    let clock = 0;
    ai.now = () => (clock += 60_000);

    const results: ClipResult[] = [];
    let stopped: string | null = null;
    let answered = 0;
    for (const clip of clips) {
      let result: ClipResult;
      try {
        const bytes = new Uint8Array(fs.readFileSync(`../docs/ai/evals/voice/${clip.file}`));
        const heard = await transcriber.transcribe(bytes, 'audio/ogg', LANG_HINT[clip.lang], Math.round((clip as unknown as { durationSeconds: number }).durationSeconds * 1000));
        answered++;
        result = { clip, transcript: heard.text, ...scoreClip(clip, heard.text) };
        if (clip.extract) {
          if (!setup.chatKey) result.extract = 'not run';
          else {
            try {
              const draft = await ai.extractListing(setup.chatKey, heard.text);
              const ok = draft.price === clip.extract.price && draft.bedrooms === clip.extract.bedrooms &&
                (draft.locality ?? '').toLowerCase().includes(clip.extract.locality.toLowerCase());
              result.extract = { ok, got: `${draft.price ?? '-'} / ${draft.bedrooms ?? '-'} BHK / ${draft.locality ?? '-'}` };
            } catch (e) {
              result.extract = { ok: false, got: e instanceof OnDeviceAiError ? e.kind : 'error' };
            }
          }
        }
        if (clip.ask) {
          if (!setup.chatKey) result.ask = 'not run';
          else {
            try {
              const reply = await ai.ask(setup.chatKey, heard.text);
              result.ask = { ok: clip.ask.mustContain.every((m) => reply.answer.toLowerCase().includes(m.toLowerCase())), grounded: reply.grounded };
            } catch {
              result.ask = { ok: false, grounded: false };
            }
          }
        }
      } catch (e) {
        const kind = e instanceof OnDeviceAiError ? e.kind : String((e as Error)?.message ?? e).slice(0, 80);
        result = { clip, transcript: null, error: kind };
        // Every further clip would fail the same way: stop instead of spending the pauses and the quota.
        const why: Record<string, string> = { keyRejected: 'key rejected', modelNotFound: 'model not found', unreachable: 'provider unreachable' };
        stopped = why[kind] ?? null;
      }
      results.push(result);
      if (stopped) break;
      await new Promise((r) => setTimeout(r, setup.delayMs));
    }

    const text = formatVoiceSummary({ provider: setup.provider, host, model: setup.model }, results, stopped);
    console.log(text);
    const file = proc?.env?.['DOORPRINTS_EVAL_SUMMARY'];
    if (file && fs) fs.writeFileSync(file, text);
    expect(stopped, `the run stopped early: ${stopped}`).toBeNull();
    expect(answered, 'no clip got an answer from the provider').toBeGreaterThan(0);
  });
});
