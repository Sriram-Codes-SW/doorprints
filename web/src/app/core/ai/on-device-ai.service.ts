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
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { LocalStore } from '../../data/local-store.service';
import type { AskResponse, HouseDraft, PlanRequest, PlanResponse } from '../ai.service';
import {
  AgentPlan, AiHouse, AskFilterValues, I_DONT_KNOW, ModelAnswer, PlanCandidate, RawListing, askPrompt, assemblePlan,
  candidateLines, citations, extractionPrompt, nonce, planPrompt, sanitizeDraft, selectForAsk, selectForPlan,
} from './ai-core';

/** Why an on-device AI request failed, in the words the screens use (see `aiErrorMsg`). */
export class OnDeviceAiError extends Error {
  constructor(readonly kind: 'rateLimited' | 'keyRejected' | 'unavailable', readonly retryAfter = 60) {
    super(kind);
  }
}

export const GEMINI_MODEL = 'gemini-3.5-flash';
export const GEMINI_URL = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`;
export const MAX_INPUT_CHARS = 8000;
export const MAX_QUESTION_CHARS = 1000;
export const RATE_LIMIT = 10;
const MAX_STOPS = 8;

const str = (description: string) => ({ type: 'string', description, nullable: true });
/** The server's `RawListing`, its field descriptions word for word. */
export const LISTING_SCHEMA = {
  type: 'object',
  properties: {
    label: str("Short human label, e.g. '2BHK near Indiranagar metro'"),
    address: str('Full postal address as written in the listing'),
    street: str('Street / road name only'),
    locality: str('Locality / neighbourhood / area'),
    price: str("Monthly rent or sale price in rupees exactly as written, e.g. '25,000' or '1.2 Cr'"),
    priceType: str('RENT or SALE'),
    bedrooms: str("Number of bedrooms, e.g. '2' for 2BHK"),
    contactName: str('Contact person name'),
    contactPhone: str('Contact phone number exactly as written'),
    listingUrl: str('Listing URL if one is present in the text'),
    notes: str('Other useful facts (deposit, floor, furnishing, availability) in one short paragraph'),
    amenities: { type: 'array', description: 'Amenities such as parking, lift, power backup, gym', items: { type: 'string' } },
  },
};
export const ANSWER_SCHEMA = {
  type: 'object',
  properties: {
    answer: { type: 'string', description: 'The answer, in 1-6 sentences, citing houses inline as [house:<id>]' },
    citedHouseIds: { type: 'array', description: 'Ids of the houses cited inline as [house:<id>] in the answer, copied exactly from the context', items: { type: 'string' } },
  },
  required: ['answer'],
};
export const PLAN_SCHEMA = {
  type: 'object',
  properties: {
    summary: { type: 'string', description: '2-4 sentences explaining the plan' },
    stops: {
      type: 'array',
      description: 'Houses to visit, in visiting order',
      items: {
        type: 'object',
        properties: {
          houseId: { type: 'string', description: 'House id exactly as returned by a tool' },
          reason: { type: 'string', description: 'Why this house is in the plan, one sentence' },
        },
      },
    },
  },
};
const PING_SCHEMA = { type: 'object', properties: { ok: { type: 'boolean' } } };

interface GeminiResponse {
  candidates?: { content?: { parts?: { text?: string }[] } }[];
}

/**
 * AI with the person's own Gemini key, in this browser (docs/03 §13.1, ADR-26): the same three calls as the server's
 * AI endpoints, the same prompts, limits and checks (ai-core.ts), and Gemini called directly. The key goes only to
 * Google, in the `x-goog-api-key` header, never in a URL; prompts and answers are not logged. At most 10 requests a
 * minute, as the server allows.
 */
@Injectable({ providedIn: 'root' })
export class OnDeviceAiService {
  private readonly http = inject(HttpClient);
  private readonly store = inject(LocalStore);
  private readonly recent: number[] = [];
  /** The clock (a test's). */
  now: () => number = () => Date.now();

  private admit(): void {
    const t = this.now();
    while (this.recent.length && t - this.recent[0] >= 60_000) this.recent.shift();
    if (this.recent.length >= RATE_LIMIT) {
      throw new OnDeviceAiError('rateLimited', Math.max(1, Math.ceil((60_000 - (t - this.recent[0])) / 1000)));
    }
    this.recent.push(t);
  }

  /** The model's JSON answer as text. */
  private async generate(key: string, system: string, user: string, schema: object, temperature: number): Promise<string> {
    const body = {
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: 'user', parts: [{ text: user }] }],
      generationConfig: { temperature, maxOutputTokens: 2048, responseMimeType: 'application/json', responseSchema: schema },
    };
    let res: GeminiResponse;
    try {
      res = await firstValueFrom(this.http.post<GeminiResponse>(GEMINI_URL, body, { headers: { 'x-goog-api-key': key } }));
    } catch (e) {
      if (e instanceof HttpErrorResponse) {
        const text = JSON.stringify(e.error ?? '');
        if (e.status === 429) throw new OnDeviceAiError('rateLimited');
        if (e.status === 403 || (e.status === 400 && (text.includes('API_KEY_INVALID') || text.includes('API key not valid')))) {
          throw new OnDeviceAiError('keyRejected');
        }
        if (e.status === 0) throw e; // offline: the usual "cannot reach" words
      }
      throw new OnDeviceAiError('unavailable');
    }
    const parts = res?.candidates?.[0]?.content?.parts;
    if (!parts) throw new OnDeviceAiError('unavailable');
    return parts.map((p) => p.text ?? '').join('');
  }

  private parse<T>(text: string): T | null {
    try {
      return JSON.parse(text) as T;
    } catch {
      return null;
    }
  }

  /** The saved houses and their visits as on-device AI reads them, most recently changed first. */
  private async houses(): Promise<AiHouse[]> {
    const visits = (await this.store.allVisits()).filter((v) => !v.deleted);
    const byHouse = new Map<string, { arrivedAt: number; leftAt: number | null }[]>();
    for (const v of visits) {
      if (!v.houseId) continue;
      const list = byHouse.get(v.houseId) ?? [];
      list.push({ arrivedAt: Date.parse(v.arrivedAt), leftAt: v.leftAt ? Date.parse(v.leftAt) : null });
      byHouse.set(v.houseId, list);
    }
    const live = (await this.store.allHouses()).filter((h) => !h.deleted);
    live.sort((a, b) => Date.parse(b.updatedAt ?? '') - Date.parse(a.updatedAt ?? '') || 0);
    return live.map((h) => ({
      id: h.id, label: h.label, address: h.address, street: h.street, locality: h.locality, lat: h.lat, lon: h.lon,
      status: h.status, price: h.price, priceType: h.priceType, bedrooms: h.bedrooms, rating: h.rating,
      areaSqft: h.areaSqft, cost: h.cost,
      contactName: h.contactName, contactPhone: h.contactPhone, listingUrl: h.listingUrl, notes: h.notes,
      checklist: h.checklist, visits: byHouse.get(h.id) ?? [],
    }));
  }

  async extractListing(key: string, text: string): Promise<HouseDraft> {
    if (!text.trim() || text.length > MAX_INPUT_CHARS) throw new Error('listing text is empty or too long');
    this.admit();
    const p = extractionPrompt(text, nonce());
    return sanitizeDraft(this.parse<RawListing>(await this.generate(key, p.system, p.user, LISTING_SCHEMA, 0)), text);
  }

  async ask(key: string, question: string, filters?: AskFilterValues): Promise<AskResponse> {
    if (!question.trim() || question.length > MAX_QUESTION_CHARS) throw new Error('question is empty or too long');
    const docs = selectForAsk(await this.houses(), question, filters);
    if (!docs.length) return { answer: I_DONT_KNOW, citations: [], grounded: false, retrieved: 0 };
    this.admit();
    const p = askPrompt(question, docs, nonce());
    const answer = this.parse<ModelAnswer>(await this.generate(key, p.system, p.user, ANSWER_SCHEMA, 0.1));
    if (!answer?.answer?.trim()) return { answer: I_DONT_KNOW, citations: [], grounded: false, retrieved: docs.length };
    const cited = citations(answer, docs, question);
    return { answer: answer.answer.trim(), citations: cited, grounded: cited.length > 0, retrieved: docs.length };
  }

  async planVisits(key: string, request: PlanRequest): Promise<PlanResponse> {
    if (!request.question.trim() || request.question.length > MAX_QUESTION_CHARS) throw new Error('question is empty or too long');
    const maxStops = Math.max(1, Math.min(request.maxStops ?? MAX_STOPS, MAX_STOPS));
    const candidates = selectForPlan(await this.houses(), request.startLat, request.startLon);
    const seen = new Map<string, PlanCandidate>(candidates.map((c) => [c.id, c]));
    if (!candidates.length) return assemblePlan({ stops: [] }, seen, request.startLat, request.startLon, maxStops);
    this.admit();
    const p = planPrompt(request.question, request.startLat, request.startLon, maxStops, candidateLines(candidates), nonce());
    // As on the server: an unusable answer falls back to the candidates by distance; a failed request is reported.
    const plan = this.parse<AgentPlan>(await this.generate(key, p.system, p.user, PLAN_SCHEMA, 0.2));
    return assemblePlan(plan, seen, request.startLat, request.startLon, maxStops);
  }

  /** Whether Google accepts [key]: one tiny request, nothing saved. */
  async test(key: string): Promise<void> {
    await this.generate(key, 'Reply with {"ok": true}.', 'ping', PING_SCHEMA, 0);
  }
}
