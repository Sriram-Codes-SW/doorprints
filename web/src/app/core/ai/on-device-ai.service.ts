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
import { distancesToPlaces, notesReaching } from '../../shared/area';
import type { Viewing } from '../../shared/viewing';
import type { AskResponse, HouseDraft, PlanRequest, PlanResponse } from '../ai.service';
import {
  AgentPlan, AiHouse, AskFilterValues, I_DONT_KNOW, ModelAnswer, PlanCandidate, RawListing, askPrompt, assemblePlan,
  candidateLines, citations, extractionPrompt, nonce, planPrompt, sanitizeDraft, selectForAsk, selectForPlan,
} from './ai-core';
import type { JsonChatModel } from './json-chat-model';

/** `modelNotFound` and `unreachable` come from an OpenAI-compatible provider (docs/03 §13.2); Gemini never raises them. */
export type OnDeviceAiErrorKind = 'rateLimited' | 'keyRejected' | 'unavailable' | 'modelNotFound' | 'unreachable';

/** Why an on-device AI request failed, in the words the screens use (see `aiErrorMsg`). */
export class OnDeviceAiError extends Error {
  constructor(readonly kind: OnDeviceAiErrorKind, readonly retryAfter = 60) {
    super(kind);
  }
}

/** The model and endpoint the `gemini` kind calls; the key travels only in a header, never in the URL. */
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
 * The `gemini` kind (ADR-26), unchanged: Google's `generateContent` with the person's key in `x-goog-api-key`, never in
 * a URL. A [JsonChatModel] for the key it is made with.
 */
export class GeminiChatModel implements JsonChatModel {
  constructor(private readonly http: HttpClient, private readonly key: string) {}

  async generateJson(system: string, user: string, schema: object, temperature: number): Promise<string> {
    const body = {
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: 'user', parts: [{ text: user }] }],
      generationConfig: { temperature, maxOutputTokens: 2048, responseMimeType: 'application/json', responseSchema: schema },
    };
    let res: GeminiResponse;
    try {
      res = await firstValueFrom(this.http.post<GeminiResponse>(GEMINI_URL, body, { headers: { 'x-goog-api-key': this.key } }));
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

  async ping(): Promise<void> {
    await this.generateJson('Reply with {"ok": true}.', 'ping', PING_SCHEMA, 0);
  }
}

/**
 * Who answers an on-device call: a Gemini key (the string, as before ADR-35) or any [JsonChatModel] the caller made
 * from the person's settings (`AiService`).
 */
export type ModelRef = string | JsonChatModel;

/**
 * AI with the person's own provider, in this browser (docs/03 §13.1 and §13.2, ADR-26, ADR-35; a Gemini key as a string): the same three calls as the server's
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

  /**
   * Keeps this browser to {@link RATE_LIMIT} requests a minute, as the server does; throws `rateLimited` with the
   * seconds to wait.
   */
  private admit(): void {
    const t = this.now();
    while (this.recent.length && t - this.recent[0] >= 60_000) this.recent.shift();
    if (this.recent.length >= RATE_LIMIT) {
      throw new OnDeviceAiError('rateLimited', Math.max(1, Math.ceil((60_000 - (t - this.recent[0])) / 1000)));
    }
    this.recent.push(t);
  }

  /** The model for a call: a string is a Gemini key. */
  private model(via: ModelRef): JsonChatModel {
    return typeof via === 'string' ? new GeminiChatModel(this.http, via) : via;
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
    const viewingsByHouse = new Map<string, Viewing[]>();
    for (const viewing of await this.store.viewings()) {
      viewingsByHouse.set(viewing.houseId, [...(viewingsByHouse.get(viewing.houseId) ?? []), viewing]);
    }
    const areas = await this.store.areas();
    const places = await this.store.places();
    const noteRows = await this.store.areaNoteRows();
    const live = (await this.store.allHouses()).filter((h) => !h.deleted);
    live.sort((a, b) => Date.parse(b.updatedAt ?? '') - Date.parse(a.updatedAt ?? '') || 0);
    return live.map((h) => ({
      id: h.id, label: h.label, address: h.address, street: h.street, locality: h.locality, lat: h.lat, lon: h.lon,
      status: h.status, price: h.price, priceType: h.priceType, bedrooms: h.bedrooms, rating: h.rating,
      areaSqft: h.areaSqft, cost: h.cost, rooms: h.rooms, answers: h.answers, moveIn: h.moveIn, floor: h.floor, viewings: viewingsByHouse.get(h.id) ?? [],
      areaNotes: notesReaching(h, areas, noteRows).map((n) => ({ id: n.id, text: n.note.text, updatedAt: Date.parse(n.updatedAt ?? '') || 0 })),
      distances: distancesToPlaces(h, places).map((d) => ({ name: d.place.name, meters: d.meters })),
      contactName: h.contactName, contactPhone: h.contactPhone, listingUrl: h.listingUrl, notes: h.notes,
      checklist: h.checklist, visits: byHouse.get(h.id) ?? [],
    }));
  }

  /**
   * Reads pasted listing text into a draft for the form. Empty or too long text throws; the model's answer always goes
   * through {@link sanitizeDraft}.
   */
  async extractListing(via: ModelRef, text: string): Promise<HouseDraft> {
    if (!text.trim() || text.length > MAX_INPUT_CHARS) throw new Error('listing text is empty or too long');
    this.admit();
    const p = extractionPrompt(text, nonce());
    return sanitizeDraft(this.parse<RawListing>(await this.model(via).generateJson(p.system, p.user, LISTING_SCHEMA, 0)), text);
  }

  /**
   * Answers a question from the saved houses only. With no matching house, or no usable answer, it returns the "I don't
   * know" sentence; citations are only houses that were sent to the model.
   */
  async ask(via: ModelRef, question: string, filters?: AskFilterValues): Promise<AskResponse> {
    if (!question.trim() || question.length > MAX_QUESTION_CHARS) throw new Error('question is empty or too long');
    const docs = selectForAsk(await this.houses(), question, filters);
    if (!docs.length) return { answer: I_DONT_KNOW, citations: [], grounded: false, retrieved: 0 };
    this.admit();
    const p = askPrompt(question, docs, nonce());
    const answer = this.parse<ModelAnswer>(await this.model(via).generateJson(p.system, p.user, ANSWER_SCHEMA, 0.1));
    if (!answer?.answer?.trim()) return { answer: I_DONT_KNOW, citations: [], grounded: false, retrieved: docs.length };
    const cited = citations(answer, docs, question);
    return { answer: answer.answer.trim(), citations: cited, grounded: cited.length > 0, retrieved: docs.length };
  }

  /**
   * Plans visits: the model picks houses from the nearest candidates, and the order and walking legs are computed
   * locally by {@link assemblePlan}. At most 8 stops; an unusable model answer falls back to nearest-neighbour order.
   */
  async planVisits(via: ModelRef, request: PlanRequest): Promise<PlanResponse> {
    if (!request.question.trim() || request.question.length > MAX_QUESTION_CHARS) throw new Error('question is empty or too long');
    const maxStops = Math.max(1, Math.min(request.maxStops ?? MAX_STOPS, MAX_STOPS));
    const candidates = selectForPlan(await this.houses(), request.startLat, request.startLon);
    const seen = new Map<string, PlanCandidate>(candidates.map((c) => [c.id, c]));
    if (!candidates.length) return assemblePlan({ stops: [] }, seen, request.startLat, request.startLon, maxStops);
    this.admit();
    const p = planPrompt(request.question, request.startLat, request.startLon, maxStops, candidateLines(candidates), nonce());
    // As on the server: an unusable answer falls back to the candidates by distance; a failed request is reported.
    const plan = this.parse<AgentPlan>(await this.model(via).generateJson(p.system, p.user, PLAN_SCHEMA, 0.2));
    return assemblePlan(plan, seen, request.startLat, request.startLon, maxStops);
  }

  /** Whether the provider accepts [via] (a Gemini key, or a model): one tiny request, nothing saved. */
  async test(via: ModelRef): Promise<void> {
    await this.model(via).ping();
  }
}
