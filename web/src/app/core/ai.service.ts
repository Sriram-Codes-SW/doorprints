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
import { Observable, defer } from 'rxjs';
import type { Msg } from '../i18n/translation.service';
import { ConfigService } from './config.service';
import { AI_OPT_IN_KEY, AI_PROVIDER_KEY, GEMINI_KEY_KEY } from './storage-keys';
import { OnDeviceAiError, OnDeviceAiService } from './ai/on-device-ai.service';
import { errorMsg } from './format';
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

/** Who answers AI requests (docs/03 §13.1, ADR-26): the connected server, or Gemini from this browser with the person's own key. */
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
  contactName: string | null;
  contactPhone: string | null;
  listingUrl: string | null;
  notes: string | null;
  amenities: string[];
  warnings: string[];
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
  /** On-device AI with the person's own key: chosen, or no server to choose, and a key saved. */
  readonly usesOwnKey = computed(() => (this.providerState() === 'device' || !this.config.configured()) && this.hasGeminiKey());
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
      (remember ? localStorage : sessionStorage).setItem(GEMINI_KEY_KEY, clean);
    } catch {
      // Storage unavailable: the key holds for this page only.
    }
    this.setProvider('device');
  }

  /** Forgets the Gemini key; AI goes back to the server, if one is connected. */
  removeGeminiKey(): void {
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
    return this.onDevice.test(key.trim());
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

  // The same three calls, answered by the server or in this browser (ADR-26): the pages do not know which.
  extractListing(text: string): Observable<HouseDraft> {
    if (this.usesOwnKey()) return defer(() => this.onDevice.extractListing(this.geminiKeyState(), text));
    return this.http.post<HouseDraft>('/api/ai/extract-listing', { text });
  }

  ask(question: string, filters?: AskFilters): Observable<AskResponse> {
    if (this.usesOwnKey()) return defer(() => this.onDevice.ask(this.geminiKeyState(), question, filters));
    return this.http.post<AskResponse>('/api/ai/ask', filters ? { question, filters } : { question });
  }

  planVisits(request: PlanRequest): Observable<PlanResponse> {
    if (this.usesOwnKey()) return defer(() => this.onDevice.planVisits(this.geminiKeyState(), request));
    return this.http.post<PlanResponse>('/api/ai/plan-visits', request);
  }
}

/** Translated message for AI failures: 429 with Retry-After, 503 provider down/quota, else the generic mapping. */
export function aiErrorMsg(err: unknown): Msg {
  if (err instanceof OnDeviceAiError) {
    if (err.kind === 'rateLimited') return { key: 'ai.rateLimited', params: { s: err.retryAfter } };
    if (err.kind === 'keyRejected') return { key: 'ai.keyRejected' };
    return { key: 'ai.providerDown' };
  }
  if (err instanceof HttpErrorResponse) {
    if (err.status === 429) {
      const seconds = Number.parseInt(err.headers.get('Retry-After') ?? '', 10);
      return { key: 'ai.rateLimited', params: { s: Number.isFinite(seconds) ? seconds : 60 } };
    }
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
