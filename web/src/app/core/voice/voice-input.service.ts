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

import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { TranslationService } from '../../i18n/translation.service';
import { LANGUAGES } from '../../i18n/languages';
import { AiService } from '../ai.service';
import { type AiProviderConfig, aiHostOf } from '../ai/ai-provider-config';
import { OnDeviceAiError } from '../ai/on-device-ai.service';
import {
  GeminiTranscriber, MAX_AUDIO_MS, OpenAiAudioTranscriber, SARVAM_HOST, SARVAM_MAX_AUDIO_MS, SarvamTranscriber, type Transcriber, type Transcript,
  canTranscribe, transcribePresetOf,
} from '../ai/transcriber';
import { SARVAM_KEY_KEY, VOICE_INPUT_KEY, VOICE_PROVIDER_KEY } from '../storage-keys';
import { AudioRecorder, type RecordedClip, type RecorderEnv, browserRecorderEnv, canRecord } from './audio-recorder';

/**
 * The *Voice input* setting and the one place a recorded clip becomes text (docs/03 ADR-37, docs/ai/voice-input.md).
 *
 * Off until the person turns it on, in this browser only. It works with the person's **own** AI key and nothing else: the
 * key is the one *AI features* already holds (`AiService.ownKey()`), the call goes from this browser straight to the
 * provider, and there is no proxy and no server route (a person whose AI is the connected server has no microphone). Where
 * the provider takes no audio (Anthropic, Ollama, LM Studio, and a custom address, whose opt-in comes later) the
 * microphone is not drawn. The audio and the words are returned to the caller and kept nowhere here (T-I48).
 */

/** The browser's microphone and recorder, replaceable in tests. */
export const RECORDER_ENV = new InjectionToken<RecorderEnv>('RECORDER_ENV', { factory: () => browserRecorderEnv() });

/**
 * The transcription model each speech-capable preset is called with (the later setting may let the person choose): OpenAI's
 * and OpenRouter's `gpt-4o-mini-transcribe` (OpenRouter lists `openai/gpt-4o-mini-transcribe` in its
 * `output_modalities=transcription` catalogue, read 2026-10-15) and Groq's `whisper-large-v3-turbo`.
 */
const TRANSCRIBE_MODELS: Readonly<Record<string, string>> = {
  openai: 'gpt-4o-mini-transcribe',
  openrouter: 'openai/gpt-4o-mini-transcribe',
  groq: 'whisper-large-v3-turbo',
};

/**
 * The list price shown in the disclosure, INR a minute, read from the vendors' pages on 2026-10-10 at 88 INR to the dollar
 * (docs/ai/voice-input.md §2): Gemini at its higher audio price, OpenAI's and OpenRouter's `gpt-4o-mini-transcribe`, Groq's turbo.
 */
const PRICE_INR_PER_MINUTE: Readonly<Record<string, string>> = { gemini: '0.17', openai: '0.26', openrouter: '0.26', groq: '0.06' };

/** Sarvam's list price, INR a minute (30 INR an hour, docs/ai/voice-input.md §2, read 2026-10-10). */
export const SARVAM_PRICE_INR = '0.50';

/** Which service writes out the words: the person's AI provider (the default) or Sarvam with its own key. */
export type VoiceProvider = 'own' | 'sarvam';

/** Words the model should spell right, by kind of space; a house hunt's are the default. Sent as the OpenAI form's `prompt`. */
export function keyTermsFor(kind: string): string {
  return kind === 'school'
    ? 'CBSE, ICSE, fees, classes, admission, school bus'
    : '2 BHK, 3 BHK, lakh, crore, sq ft, rent, deposit, maintenance, society, locality';
}

/** What a `Transcriber` is built from: the saved choice and the key, or null where the provider cannot take audio. */
export function transcriberFor(config: AiProviderConfig, key: string, kind: string): Transcriber | null {
  if (config.kind === 'gemini') return new GeminiTranscriber(key);
  if (config.kind !== 'openai-compatible') return null;
  const model = TRANSCRIBE_MODELS[transcribePresetOf(config)];
  return model ? new OpenAiAudioTranscriber({ baseUrl: config.baseUrl, model, prompt: keyTermsFor(kind) }, key) : null;
}

@Injectable({ providedIn: 'root' })
export class VoiceInputService {
  private readonly ai = inject(AiService);
  private readonly i18n = inject(TranslationService);
  private readonly env = inject(RECORDER_ENV);
  private readonly enabledState = signal(readVoice());
  /** This browser's own *Voice input* switch. */
  readonly enabled = this.enabledState.asReadonly();
  private readonly providerState = signal<VoiceProvider>(readProvider());
  /** Which service writes out the words (this browser's choice). */
  readonly provider = this.providerState.asReadonly();
  private readonly sarvamKeyState = signal(readSarvamKey());
  /** Whether a Sarvam key is saved (the key itself is read only at the moment of a call). */
  readonly hasSarvamKey = computed(() => this.sarvamKeyState() !== '');
  /** The last four characters of the saved Sarvam key, for "saved (ends …)"; empty for a short key. */
  readonly sarvamKeyHint = computed(() => (this.sarvamKeyState().length >= 8 ? this.sarvamKeyState().slice(-4) : ''));

  /** The person's own AI is the one in use and it takes audio: what the switch can do anything for. */
  readonly providerOk = computed(() => this.ai.usesOwnKey() && canTranscribe(this.ai.aiConfig()));

  /** The chosen service can write out words now: the own AI takes audio, or Sarvam has its key. */
  readonly usable = computed(() => (this.providerState() === 'sarvam' ? this.hasSarvamKey() : this.providerOk()));

  /**
   * The microphone is offered: the switch is on, AI features are on with the person's own key, the provider takes audio and
   * this browser can record. (The switch is read first: with it off, nothing else is looked at.)
   */
  readonly available = computed(() => this.enabledState() && this.ai.enabled() && this.usable() && canRecord(this.env));

  /** The host the audio goes to, for the disclosure. */
  readonly host = computed(() => (this.providerState() === 'sarvam' ? SARVAM_HOST : aiHostOf(this.ai.aiConfig())));

  /** The list price in INR a minute for the disclosure, or null where none is known. */
  readonly price = computed<string | null>(() =>
    this.providerState() === 'sarvam' ? SARVAM_PRICE_INR : (PRICE_INR_PER_MINUTE[transcribePresetOf(this.ai.aiConfig())] ?? null),
  );

  /** The hard stop of a recording in milliseconds: Sarvam's 30 s, else a minute. */
  readonly maxMs = computed(() => (this.providerState() === 'sarvam' ? SARVAM_MAX_AUDIO_MS : MAX_AUDIO_MS));
  /** True when the limit is the short one (the words on screen say "30 seconds"). */
  readonly shortLimit = computed(() => this.providerState() === 'sarvam');

  /** Chooses the service (localStorage: a preference, not a secret). */
  setProvider(p: VoiceProvider): void {
    this.providerState.set(p);
    try {
      if (p === 'sarvam') localStorage.setItem(VOICE_PROVIDER_KEY, p);
      else localStorage.removeItem(VOICE_PROVIDER_KEY);
    } catch {
      // Storage unavailable: the choice holds for this page only.
    }
  }

  /**
   * Saves the person's own Sarvam key in its own slot: localStorage with `remember`, else sessionStorage (gone when the tab
   * closes). Only ever sent to api.sarvam.ai; never in a backup, copy, sync or share file.
   */
  saveSarvamKey(key: string, remember: boolean): void {
    const clean = key.trim();
    this.sarvamKeyState.set(clean);
    for (const [s, keep] of [[() => sessionStorage, !remember], [() => localStorage, remember]] as const) {
      try {
        if (keep && clean !== '') s().setItem(SARVAM_KEY_KEY, clean);
        else s().removeItem(SARVAM_KEY_KEY);
      } catch {
        // Storage unavailable: the key holds for this page only.
      }
    }
  }

  /** Forgets the Sarvam key and goes back to the person's AI provider. */
  removeSarvamKey(): void {
    this.saveSarvamKey('', false);
    this.setProvider('own');
  }


  /** Turns the switch on or off for this browser (localStorage: a preference, not a secret). */
  setEnabled(on: boolean): void {
    this.enabledState.set(on);
    try {
      if (on) localStorage.setItem(VOICE_INPUT_KEY, '1');
      else localStorage.removeItem(VOICE_INPUT_KEY);
    } catch {
      // Storage unavailable: the choice holds for this page only.
    }
  }

  /** A recorder on this browser's microphone; the microphone is asked for only when its `start()` is called. */
  newRecorder(): AudioRecorder {
    return new AudioRecorder(this.env, this.maxMs());
  }

  /** The spoken language as a BCP 47 tag, from the app's language (`hi-IN`); the provider may hear another one. */
  langHint(): string {
    return LANGUAGES.find((l) => l.code === this.i18n.lang())?.locale ?? 'en-IN';
  }

  /**
   * The words in `clip`, from the person's own provider with the person's own key. Rejects with an `OnDeviceAiError` (worded by
   * `aiErrorMsg`) or an `AudioClipRejectedError`; nothing is stored, logged or kept here, and the transcript is only returned.
   * `kind` is the space on screen (key terms for the model).
   */
  transcribe(clip: RecordedClip, kind: string): Promise<Transcript> {
    const t = !this.available()
      ? null
      : this.providerState() === 'sarvam'
        ? new SarvamTranscriber(this.sarvamKeyState())
        : transcriberFor(this.ai.aiConfig(), this.ai.ownKey(), kind);
    if (t === null) return Promise.reject(new OnDeviceAiError('unavailable'));
    return t.transcribe(clip.bytes, clip.mime, this.langHint(), clip.durationMs);
  }
}

function readVoice(): boolean {
  try {
    return typeof localStorage !== 'undefined' && localStorage.getItem(VOICE_INPUT_KEY) === '1';
  } catch {
    return false;
  }
}

function readProvider(): VoiceProvider {
  try {
    return typeof localStorage !== 'undefined' && localStorage.getItem(VOICE_PROVIDER_KEY) === 'sarvam' ? 'sarvam' : 'own';
  } catch {
    return 'own';
  }
}

/** The Sarvam key: this tab's (sessionStorage) first, then the remembered one. */
function readSarvamKey(): string {
  for (const s of [() => sessionStorage, () => localStorage]) {
    try {
      const v = s().getItem(SARVAM_KEY_KEY);
      if (v) return v;
    } catch {
      // ignore
    }
  }
  return '';
}
