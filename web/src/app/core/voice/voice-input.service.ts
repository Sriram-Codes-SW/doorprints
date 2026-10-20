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

import { Injectable, InjectionToken, computed, effect, inject, signal, untracked } from '@angular/core';
import { TranslationService } from '../../i18n/translation.service';
import { LANGUAGES } from '../../i18n/languages';
import { country } from '../../shared/country/country-pack';
import { AiService } from '../ai.service';
import { type AiProviderConfig, aiHostOf } from '../ai/ai-provider-config';
import type { FetchLike } from '../ai/ai-request';
import { GEMINI_MODEL, OnDeviceAiError } from '../ai/on-device-ai.service';
import {
  GeminiTranscriber, MAX_AUDIO_MS, OpenAiAudioTranscriber, SARVAM_HOST, SARVAM_MAX_AUDIO_MS, SarvamTranscriber, type Transcriber, type Transcript,
  canTranscribe, transcribePresetOf,
} from '../ai/transcriber';
import { SARVAM_KEY_KEY, VOICE_INPUT_KEY, VOICE_PROVIDER_KEY } from '../storage-keys';
import { AudioRecorder, type RecordedClip, type RecorderEnv, browserRecorderEnv, canRecord } from './audio-recorder';
import { ALLOW_TTL_MS, REFUSE_FREE_TTL_MS, type TierProbe, type VoiceVerdict, canAcknowledge, decideVoiceTier, keyHash, needsProbe } from './voice-tier';
import { forgetTier, readAck, readTier, tierVersion, writeAck, writeTier } from './voice-tier-store';
import { probeTier } from './voice-tier-probe';

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

/** The call the free-plan check makes (a test's replacement); the real one is the browser's `fetch`. */
export const VOICE_TIER_FETCH = new InjectionToken<FetchLike>('VOICE_TIER_FETCH', { factory: () => (input, init) => fetch(input, init) });

/** Thrown, before any request, when the free-plan rule does not allow voice for this key (the words come from `voice.tier*`). */
export class VoiceTierRefusedError extends Error {
  constructor(readonly verdict: Exclude<VoiceVerdict, 'ALLOW'>, readonly provider: string) {
    super(verdict);
  }
}

/** The provider's name as the person knows it, for the words about its plan. */
const PROVIDER_NAMES: Readonly<Record<string, string>> = { gemini: 'Gemini', openai: 'OpenAI', openrouter: 'OpenRouter', groq: 'Groq', sarvam: 'Sarvam' };

/** A probe that could not tell is asked again after this long, when something asks (the switch, a new screen). */
const REPROBE_AFTER_MS = 60_000;

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
    : country().voice.houseKeyTerms;
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
  private readonly tierFetch = inject(VOICE_TIER_FETCH);
  /** What the probe found for one key (by fingerprint), in memory only; a verdict worth keeping is also written by the store. */
  private readonly probeState = signal<{ fp: string; probe: TierProbe; at: number } | null>(null);
  private readonly inflight = new Map<string, Promise<void>>();
  private lastFp: Record<string, string> = {};
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

  constructor() {
    // The plan is read once the person has turned Voice on and the service has a key; a replaced key's verdict and
    // confirmation are dropped, so the next key is asked again.
    effect(() => {
      if (!this.enabledState() || !this.usable()) return;
      const provider = this.tierProvider();
      const key = this.tierKey();
      untracked(() => {
        const fp = key === '' ? '' : keyHash(key);
        const before = this.lastFp[provider];
        if (before !== undefined && before !== fp && before !== '') forgetTier(before);
        this.lastFp[provider] = fp;
        void this.refreshTier();
      });
    });
  }

  /** The person's own AI is the one in use and it takes audio: what the switch can do anything for. */
  readonly providerOk = computed(() => this.ai.usesOwnKey() && canTranscribe(this.ai.aiConfig()));

  /** The chosen service can write out words now: the own AI takes audio, or Sarvam has its key. */
  readonly usable = computed(() => (this.providerState() === 'sarvam' ? this.hasSarvamKey() : this.providerOk()));

  /**
   * The microphone is offered: the switch is on, AI features are on with the person's own key, the provider takes audio and
   * this browser can record. (The switch is read first: with it off, nothing else is looked at.)
   */
  readonly available = computed(() => this.enabledState() && this.ai.enabled() && this.usable() && canRecord(this.env));

  /** The provider the audio would go to, as the rule knows it: `sarvam` for the Sarvam service, else the AI provider's preset. */
  readonly tierProvider = computed(() => (this.providerState() === 'sarvam' ? 'sarvam' : transcribePresetOf(this.ai.aiConfig())));
  /** The provider's name for the words about its plan. */
  readonly tierProviderName = computed(() => PROVIDER_NAMES[this.tierProvider()] ?? this.tierProvider());

  /**
   * Whether the free-plan rule lets this key send audio (owner decision 2026-10-19, ADR-37): `ALLOW`, `CHECKING` (the one
   * probe is under way), `REFUSE_FREE` (a free plan, no way round) or `REFUSE_UNKNOWN` (the plan cannot be read: refused until
   * the person confirms it is paid). Read again after every write of a verdict or an acknowledgement.
   */
  readonly verdict = computed<VoiceVerdict>(() => {
    tierVersion();
    this.probeState();
    return this.currentVerdict();
  });

  /** The microphone may record: it is offered and the rule says `ALLOW`. Nothing is recorded while this is false. */
  readonly micEnabled = computed(() => this.available() && this.verdict() === 'ALLOW');

  /** Whether the person confirmed this key's plan is paid. */
  readonly acknowledged = computed(() => {
    tierVersion();
    const key = this.tierKey();
    return key !== '' && readAck(keyHash(key), this.tierProvider(), key);
  });

  /** The confirmation is offered: the rule cannot read this provider's plan (or its probe could not tell) and it is not a free plan. */
  readonly canConfirmPaid = computed(() => {
    const provider = this.tierProvider();
    if (!canAcknowledge(provider) || this.verdict() === 'REFUSE_FREE' || this.verdict() === 'CHECKING') return false;
    return !needsProbe(provider) || this.probeFor(this.tierKey()) === 'unknown';
  });

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

  /** The key the audio call would use: Sarvam's own, else the AI key. */
  private tierKey(): string {
    return this.providerState() === 'sarvam' ? this.sarvamKeyState() : this.ai.ownKey();
  }

  /** The transcription model the request would name, for the `:free` rule. */
  private tierModel(): string {
    const preset = this.tierProvider();
    return preset === 'gemini' ? GEMINI_MODEL : (TRANSCRIBE_MODELS[preset] ?? '');
  }

  private probeFor(key: string): TierProbe {
    const state = this.probeState();
    if (state === null || key === '' || state.fp !== keyHash(key)) return 'none';
    // A definite answer is also kept in storage with its lifetime; here it holds only for that long (storage may be blocked).
    const ttl = state.probe === 'free' ? REFUSE_FREE_TTL_MS : state.probe === 'paid' ? ALLOW_TTL_MS : Infinity;
    return Date.now() - state.at < ttl ? state.probe : 'none';
  }

  /** The rule, applied to what is known now. A plain method: the second check before a request calls it, not a cached value. */
  private currentVerdict(): VoiceVerdict {
    const provider = this.tierProvider();
    const key = this.tierKey();
    const fp = keyHash(key);
    return decideVoiceTier({
      provider,
      model: this.tierModel(),
      probe: this.probeFor(key),
      cached: key === '' ? null : readTier(fp, provider, key, Date.now()),
      acknowledged: key !== '' && readAck(fp, provider, key),
    });
  }

  /**
   * Reads the plan if the rule is still waiting for it: one call, only for Gemini and OpenRouter, only online, never twice at
   * once for a key. Resolves when the answer is in (or there was nothing to ask).
   */
  refreshTier(): Promise<void> {
    const provider = this.tierProvider();
    const key = this.tierKey();
    if (key === '' || !needsProbe(provider) || !this.usable()) return Promise.resolve();
    const fp = keyHash(key);
    const state = this.probeState();
    const retry = state?.fp === fp && state.probe === 'unknown' && Date.now() - state.at > REPROBE_AFTER_MS;
    if (this.inflight.has(fp)) return this.inflight.get(fp)!;
    if (!retry && this.currentVerdict() !== 'CHECKING') return Promise.resolve();
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return Promise.resolve();
    this.probeState.set({ fp, probe: 'running', at: Date.now() });
    const run = probeTier(provider, key, this.tierFetch)
      .then((probe) => {
        if (probe === 'free') writeTier(provider, key, 'REFUSE_FREE', 'probe', Date.now());
        else if (probe === 'paid') writeTier(provider, key, 'ALLOW', 'probe', Date.now());
        this.probeState.set({ fp, probe, at: Date.now() });
      })
      .finally(() => this.inflight.delete(fp));
    this.inflight.set(fp, run);
    return run;
  }

  /** Records or withdraws "my plan is paid" for the key now in use (asked again for a new key). */
  setAcknowledged(on: boolean): void {
    const key = this.tierKey();
    if (key !== '') writeAck(this.tierProvider(), key, on);
  }

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
    return LANGUAGES.find((l) => l.code === this.i18n.lang())?.locale ?? country().locale.fallback;
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
    // The second check, before the request (ADR-37): the rule is asked again here, from what is stored now, so a button that
    // was drawn, a stale value or a caller that skipped the first check cannot send audio for a plan the rule refuses.
    return this.confirmAllowed().then(() => t.transcribe(clip.bytes, clip.mime, this.langHint(), clip.durationMs));
  }

  /** Resolves when the rule says `ALLOW` (reading the plan first when it is still unknown); else rejects, and nothing is sent. */
  private async confirmAllowed(): Promise<void> {
    if (this.currentVerdict() === 'CHECKING') await this.refreshTier();
    const verdict = this.currentVerdict();
    if (verdict !== 'ALLOW') throw new VoiceTierRefusedError(verdict, this.tierProvider());
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
