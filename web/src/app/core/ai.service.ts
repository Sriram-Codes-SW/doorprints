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

import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, defer, throwError } from 'rxjs';
import type { Msg } from '../i18n/translation.service';
import { ConfigService } from './config.service';
import { AI_OPT_IN_KEY, AI_PROVIDER_KEY, GEMINI_KEY_KEY } from './storage-keys';
import { type AiProviderConfig, aiHostOf, clearAiConfig, isLocalHost, isUsable, readAiConfig, saveAiConfig } from './ai/ai-provider-config';
import { type AiQuality, clearAiQuality, readAiQuality, saveAiQuality } from './ai/ai-quality';
import { AnthropicChatModel } from './ai/anthropic';
import { OpenAiCompatibleChatModel } from './ai/openai-compat';
import { GeminiChatModel, type ModelRef, OnDeviceAiError, OnDeviceAiService } from './ai/on-device-ai.service';
import { errorMsg, retryAfterSeconds } from './format';
import { aiShownIn } from './space-scope';
import { availableKinds } from './kind-setting';
import type { HouseStatus, PriceType } from './models';

// Types follow docs/ai/ai-design.md section 13 and the backend records in app.doorprints.server.ai.*
// (AiStatusController.AiStatus, extract.HouseDraft, rag.AskModels, agent.PlanModels).

export interface AiStatus {
  enabled: boolean;
  mcpEnabled: boolean;
  chatModel: string | null;
  embeddingModel: string | null;
  /** AI is on, but the server's owner turned it off for this device (docs/03 §12.1). Older servers omit it. */
  offForDevice?: boolean;
}

/**
 * Why AI is not offered here, or null when it is: no server; off on the server (or paused, or no Gemini key); off for
 * this device on the owner page; or this browser's own *AI features* switch is off.
 */
export type AiOffReason = 'noServer' | 'server' | 'device' | 'optIn' | 'noKey';

/** Who answers AI requests (docs/03 §13.1, ADR-26): the connected server, or the person's own AI from this browser (Gemini, or any OpenAI-compatible endpoint: §13.2, ADR-35). */
export type AiProvider = 'server' | 'device';

/** A suggestion only; nothing is saved until the user saves the form. */
export interface HouseDraft {
  label: string | null;
  address: string | null;
  street: string | null;
  locality: string | null;
  price: number | null;
  priceType: PriceType | null;
  bedrooms: number | null;
  /** Carpet area in sq ft (slice 1a): the no-AI parser reads it from "1150 sq ft"; the server's draft carries it too. */
  areaSqft: number | null;
  contactName: string | null;
  contactPhone: string | null;
  listingUrl: string | null;
  notes: string | null;
  amenities: string[];
  warnings: string[];
  /** Kinds PR 7 (S4b-BL-211): a kind's typed extras the listing filled, in the wire's shape; never for a house. */
  extras?: Record<string, unknown>;
}

export interface AskFilters {
  status?: HouseStatus;
  priceType?: PriceType;
  maxPrice?: number;
  minBedrooms?: number;
  minRating?: number;
}

export interface Citation {
  houseId: string;
  label: string | null;
  snippet: string | null;
}

/** The answer to a question about the saved houses and the houses it cites; `grounded` is true when it cites at least one, `retrieved` counts the houses the model was shown. */
export interface AskResponse {
  answer: string;
  citations: Citation[];
  grounded: boolean;
  retrieved: number;
}

export interface PlanRequest {
  question: string;
  startLat: number;
  startLon: number;
  maxStops?: number;
}

export interface PlannedStop {
  order: number;
  houseId: string;
  label: string | null;
  lat: number;
  lon: number;
  reason: string | null;
  legMeters: number;
  walkMinutes: number;
}

/** A suggested order for visiting houses from a start point, with walking legs; `fallback` is true when the model's plan was unusable and the stops are the houses it found, nearest first. */
export interface PlanResponse {
  summary: string | null;
  stops: PlannedStop[];
  totalMeters: number;
  totalWalkMinutes: number;
  toolCalls: string[];
  fallback: boolean;
}

/** Same limits as the server defaults (app.ai.max-input-chars / max-question-chars). */
export const AI_MAX_LISTING_CHARS = 8000;
export const AI_MAX_QUESTION_CHARS = 1000;

/** A call refused because AI is off ({@link AiOffReason}); nothing was sent. {@link aiErrorMsg} words it like the pages' own off notice. */
export class AiOffError extends Error {
  constructor(readonly reason: AiOffReason) {
    super(`AI is off (${reason})`);
    this.name = 'AiOffError';
  }
}

/**
 * Optional AI features. Everything AI in the UI is hidden unless GET /api/ai/status says `enabled` (it is off by
 * default on the server, and an unreachable server counts as off) **and** the person turned on *AI features* in this
 * browser (off until then, owner decision of 2026-09-29: they read what is sent to Google first; docs/03 §12.1).
 */
@Injectable({ providedIn: 'root' })
export class AiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ConfigService);
  private readonly status = signal<AiStatus | null>(null);
  private readonly optInState = signal<boolean>(readOptIn());
  /** This browser's own *AI features* switch. */
  readonly optedIn = this.optInState.asReadonly();
  /** The server offers AI to this device (whatever this browser's switch says). */
  readonly serverEnabled = computed(() => this.status()?.enabled === true);
  private readonly onDevice = inject(OnDeviceAiService);
  private readonly providerState = signal<AiProvider>(readProvider());
  private readonly geminiKeyState = signal<string>(readGeminiKey());
  /** Who answers: the server, or Gemini from this browser with the person's own key. */
  readonly provider = this.providerState.asReadonly();
  /** The saved Gemini key's last four characters, or '' (the key itself is never shown again). */
  readonly geminiKeyHint = computed(() => (this.geminiKeyState().length >= 8 ? this.geminiKeyState().slice(-4) : ''));
  readonly hasGeminiKey = computed(() => this.geminiKeyState() !== '');
  private readonly aiConfigState = signal<AiProviderConfig>(readAiConfig());
  /** The person's own AI: its kind, base URL and model (docs/03 §13.2). Device-only; the key keeps the Gemini key's slot. */
  readonly aiConfig = this.aiConfigState.asReadonly();
  private readonly aiQualityState = signal<AiQuality>(readAiQuality());
  /**
   * The *AI speed and cost* choice (S4b-BL-198 step 2): Quality unless the person chose otherwise. Kept while AI is off or
   * another service is chosen, but only the own-key Gemini adapter reads it.
   */
  readonly aiQuality = this.aiQualityState.asReadonly();
  /** The host the person's own AI call goes to (shown in every disclosure), '' while none is set. */
  readonly ownHost = computed(() => aiHostOf(this.aiConfigState()));
  /**
   * On-device AI with the person's own provider: chosen, or no server to choose, and what it needs saved (a Gemini key,
   * or for an OpenAI-compatible one a valid base URL and a model, its key optional; for Anthropic a model and a key).
   */
  readonly usesOwnKey = computed(
    () => (this.providerState() === 'device' || !this.config.configured()) && isUsable(this.aiConfigState(), this.hasGeminiKey()),
  );
  readonly offReason = computed<AiOffReason | null>(() => {
    if (this.usesOwnKey()) return this.optInState() ? null : 'optIn';
    if (this.providerState() === 'device' || !this.config.configured()) {
      // Own key chosen (or no server): the key is what is missing.
      return this.optInState() ? 'noKey' : this.config.configured() ? 'optIn' : 'noServer';
    }
    const s = this.status();
    if (s?.offForDevice) return 'device';
    if (!s?.enabled) return 'server';
    return this.optInState() ? null : 'optIn';
  });

  /** AI shows: on in this browser and nothing missing for the chosen provider (with the own key chosen, not the server). */
  readonly enabled = computed(() => this.offReason() === null);

  /**
   * Whether Ask and Plan are shown in the space of `kind` (kinds PR B, S4b-BL-279; `space-scope-vectors.json`
   * `assistant`): the house whenever AI is on, as it was; any other kind only when this build offers it and the AI is the
   * person's own, in this browser. The server's Ask and Plan take no kind and would read every space.
   */
  enabledFor(kind: string): boolean {
    return aiShownIn(kind, availableKinds(), this.enabled(), this.usesOwnKey());
  }

  constructor() {
    this.refresh();
  }

  /** Turns this browser's *AI features* on or off. Kept in localStorage: a preference, not a secret. */
  setOptIn(on: boolean): void {
    this.optInState.set(on);
    try {
      if (on) localStorage.setItem(AI_OPT_IN_KEY, '1');
      else localStorage.removeItem(AI_OPT_IN_KEY);
    } catch {
      // Storage unavailable: the choice holds for this page only.
    }
  }

  /** Chooses who answers; the Gemini key, if any, is kept. */
  setProvider(p: AiProvider): void {
    this.providerState.set(p);
    try {
      localStorage.setItem(AI_PROVIDER_KEY, p);
    } catch {
      // Storage unavailable: the choice holds for this page only.
    }
  }

  /**
   * Saves the person's own Gemini key and chooses it. Kept like the server key ([ConfigService]): in localStorage
   * with [remember], else in sessionStorage, gone when the tab closes. Only ever sent to Google.
   */
  saveGeminiKey(key: string, remember: boolean): void {
    const clean = key.trim();
    this.geminiKeyState.set(clean);
    try {
      (remember ? sessionStorage : localStorage).removeItem(GEMINI_KEY_KEY);
      // An empty key (a local AI server, or a key that must not follow a changed service) leaves nothing stored.
      if (clean === '') (remember ? localStorage : sessionStorage).removeItem(GEMINI_KEY_KEY);
      else (remember ? localStorage : sessionStorage).setItem(GEMINI_KEY_KEY, clean);
    } catch {
      // Storage unavailable: the key holds for this page only.
    }
    this.setProvider('device');
  }

  /**
   * Saves the choice of provider (kind, base URL, model; localStorage, never in a backup or sent to the server). The key
   * is saved apart, as before ({@link saveGeminiKey}, with an empty key for a local server).
   */
  setAiConfig(config: AiProviderConfig): void {
    this.aiConfigState.set(saveAiConfig(config));
  }

  /** Saves the *AI speed and cost* choice (localStorage, on this device only). */
  setAiQuality(quality: AiQuality): void {
    this.aiQualityState.set(saveAiQuality(quality));
  }

  /** Forgets the own key and the provider choice; AI goes back to the server, if one is connected. */
  removeGeminiKey(): void {
    clearAiConfig();
    clearAiQuality();
    this.aiQualityState.set(readAiQuality());
    this.aiConfigState.set(readAiConfig());
    this.geminiKeyState.set('');
    for (const s of [() => localStorage, () => sessionStorage]) {
      try {
        s().removeItem(GEMINI_KEY_KEY);
      } catch {
        // ignore
      }
    }
    this.setProvider('server');
  }

  /** The saved key, only for *Test key* (the page never shows it). */
  geminiKeyForTest(): string {
    return this.geminiKeyState();
  }

  /** Whether Google accepts [key]: one tiny request, nothing saved. Rejects with an [OnDeviceAiError]. */
  testGeminiKey(key: string): Promise<void> {
    return this.onDevice.test(this.geminiRef(key.trim()));
  }

  /** *Test* for any kind: one tiny request with [config] and [key] as typed, nothing saved. Rejects with an [OnDeviceAiError]. */
  testProvider(config: AiProviderConfig, key: string): Promise<void> {
    return this.onDevice.test(this.modelFor(config, key.trim()));
  }

  /**
   * Gemini with the key: the plain key (the model's own thinking level) for Quality, so nothing changes for anyone who
   * has not touched *AI speed and cost*, else an adapter that asks for less thinking.
   */
  private geminiRef(key: string): ModelRef {
    const quality = this.aiQualityState();
    return quality === 'quality' ? key : new GeminiChatModel(this.http, key, quality);
  }

  /** Who answers a call for [config]: a Gemini key (the native adapter), an OpenAI-compatible model or Anthropic. */
  private modelFor(config: AiProviderConfig, key: string): ModelRef {
    if (config.kind === 'gemini') return this.geminiRef(key);
    if (config.kind === 'openai-compatible') return new OpenAiCompatibleChatModel({ baseUrl: config.baseUrl, model: config.model }, key);
    return new AnthropicChatModel({ baseUrl: config.baseUrl, model: config.model }, key);
  }

  /** Re-reads the status (after connecting or disconnecting). */
  refresh(): void {
    if (!this.config.configured()) {
      this.status.set(null);
      return;
    }
    this.http.get<AiStatus>('/api/ai/status').subscribe({
      next: (s) => this.status.set(s),
      error: () => this.status.set(null),
    });
  }

  private ownModel(): ModelRef {
    return this.modelFor(this.aiConfigState(), this.geminiKeyState());
  }

  /**
   * The guard of the three calls (S4b-BL-251): nothing is sent while {@link offReason} says AI is off, whoever calls, so
   * a caller that forgets the pages' `@if (ai.enabled())` cannot send a listing out. The Observable errors with an
   * {@link AiOffError} when subscribed; the setup tests ({@link testGeminiKey}, {@link testProvider}) are not guarded.
   */
  private guard(): Observable<never> | null {
    const reason = this.offReason();
    return reason === null ? null : throwError(() => new AiOffError(reason));
  }

  // The same three calls, answered by the server or in this browser (ADR-26): the pages do not know which.
  /**
   * `kind` (kinds PR 7, S4b-BL-211): only for a candidate of a built-in kind other than the house that the listing extract
   * fills, while the developer flag is on; a house's request is the text alone, as before.
   */
  extractListing(text: string, kind?: string): Observable<HouseDraft> {
    const off = this.guard();
    if (off) return off;
    if (this.usesOwnKey()) {
      return defer(() => (kind ? this.onDevice.extractListing(this.ownModel(), text, kind) : this.onDevice.extractListing(this.ownModel(), text)));
    }
    return this.http.post<HouseDraft>('/api/ai/extract-listing', kind ? { text, kind } : { text });
  }

  /**
   * `kind` (kinds PR B, S4b-BL-279): the space Ask reads, for a space other than the house only; the house's call is
   * today's. The server takes no kind, so a space other than the house is answered on this device or not at all.
   */
  ask(question: string, filters?: AskFilters, kind?: string): Observable<AskResponse> {
    const off = this.guard() ?? this.noServerSpace(kind);
    if (off) return off;
    if (this.usesOwnKey()) return defer(() => (kind ? this.onDevice.ask(this.ownModel(), question, filters, kind) : this.onDevice.ask(this.ownModel(), question, filters)));
    return this.http.post<AskResponse>('/api/ai/ask', filters ? { question, filters } : { question });
  }

  /** `kind`: as {@link ask}. */
  planVisits(request: PlanRequest, kind?: string): Observable<PlanResponse> {
    const off = this.guard() ?? this.noServerSpace(kind);
    if (off) return off;
    if (this.usesOwnKey()) return defer(() => (kind ? this.onDevice.planVisits(this.ownModel(), request, kind) : this.onDevice.planVisits(this.ownModel(), request)));
    return this.http.post<PlanResponse>('/api/ai/plan-visits', request);
  }

  /** A space other than the house on the server's assistant: nothing is sent (it would read every space). */
  private noServerSpace(kind?: string): Observable<never> | null {
    return kind && kind !== 'house' && !this.usesOwnKey() ? throwError(() => new AiOffError('noKey')) : null;
  }
}

/**
 * Translated message for AI failures: 429 with Retry-After, 503 provider down/quota, else the generic mapping. [host] is
 * the own AI's host when the caller knows it (the Connect page tests what is typed); otherwise the saved provider's.
 */
export function aiErrorMsg(err: unknown, host?: string): Msg {
  if (err instanceof AiOffError) return aiOffMsg(err.reason);
  if (err instanceof OnDeviceAiError) {
    const where = host ?? savedCompatHost();
    if (err.kind === 'rateLimited') return { key: 'ai.rateLimited', params: { s: err.retryAfter } };
    if (err.kind === 'keyRejected') return where ? { key: 'ai.keyRejectedHost', params: { host: where } } : { key: 'ai.keyRejected' };
    if (err.kind === 'modelNotFound') return { key: 'ai.modelNotFound' };
    // The provider's own safety system declined the text (S4b-BL-232): one fixed message, never the provider's words.
    if (err.kind === 'blocked') return { key: 'ai.blocked' };
    // The key's free quota or credit is used up (S4b-BL-243): not a wait, so not the rate-limit words.
    if (err.kind === 'quotaExhausted') return { key: 'ai.quotaExhausted' };
    if (err.kind === 'unreachable' && where) return { key: isLocalHost(where) ? 'ai.unreachableLocal' : 'ai.unreachable', params: { host: where } };
    return { key: 'ai.providerDown' };
  }
  if (err instanceof HttpErrorResponse) {
    if (err.status === 429) {
      return { key: 'ai.rateLimited', params: { s: retryAfterSeconds(err) ?? 60 } };
    }
    // The server's own AI says its provider quota or credit is spent with 402 (S4b-BL-244): not a wait, so not "try again".
    if (err.status === 402) return { key: 'ai.quotaExhausted' };
    // The server's own AI says its provider's safety system declined the text with 422 (S4b-BL-233): the same words as the own key's.
    if (err.status === 422) return { key: 'ai.blocked' };
    if (err.status === 503) return { key: 'ai.providerDown' };
    if (err.status === 403) {
      const code = (err.error as { code?: unknown } | null)?.code;
      if (code === 'AI_OFF_FOR_DEVICE') return { key: 'ai.offForDevice' };
      if (code === 'AI_PAUSED') return { key: 'ai.disabled' };
    }
    if (err.status === 404) return { key: 'ai.disabled' };
  }
  return errorMsg(err);
}

/** The saved host when the own AI is an OpenAI-compatible one or Anthropic; '' for Gemini (its words name Google). */
function savedCompatHost(): string {
  const config = readAiConfig();
  return config.kind === 'gemini' ? '' : aiHostOf(config);
}

/** The words for an {@link AiOffReason}. */
export function aiOffMsg(reason: AiOffReason): Msg {
  switch (reason) {
    case 'noServer':
      return { key: 'ai.noServer' };
    case 'device':
      return { key: 'ai.offForDevice' };
    case 'optIn':
      return { key: 'ai.optInNeeded' };
    case 'noKey':
      return { key: 'ai.noKey' };
    default:
      return { key: 'ai.disabled' };
  }
}

function readProvider(): AiProvider {
  try {
    return typeof localStorage !== 'undefined' && localStorage.getItem(AI_PROVIDER_KEY) === 'device' ? 'device' : 'server';
  } catch {
    return 'server';
  }
}

/** The Gemini key: this tab's (sessionStorage) first, as the server key, then the remembered one. */
function readGeminiKey(): string {
  for (const s of [() => sessionStorage, () => localStorage]) {
    try {
      const v = s().getItem(GEMINI_KEY_KEY);
      if (v) return v;
    } catch {
      // ignore
    }
  }
  return '';
}

function readOptIn(): boolean {
  try {
    return typeof localStorage !== 'undefined' && localStorage.getItem(AI_OPT_IN_KEY) === '1';
  } catch {
    return false;
  }
}

/** Splits an answer into text and [house:<id>] citation markers, so markers can become links. */
export type AnswerPart = { kind: 'text'; text: string } | { kind: 'cite'; houseId: string };

/** Cuts `answer` at each `[house:<uuid>]` marker, in order; text between markers stays as text parts. */
export function splitCitations(answer: string): AnswerPart[] {
  const parts: AnswerPart[] = [];
  const re = /\[house:([0-9a-fA-F-]{36})\]/g;
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(answer)) !== null) {
    if (m.index > last) parts.push({ kind: 'text', text: answer.slice(last, m.index) });
    parts.push({ kind: 'cite', houseId: m[1] });
    last = m.index + m[0].length;
  }
  if (last < answer.length) parts.push({ kind: 'text', text: answer.slice(last) });
  return parts;
}
