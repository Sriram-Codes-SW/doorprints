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
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import vectorsJson from '../../../../../docs/schemas/kinds/kind-ai-vectors.json';
import schoolJson from '../../../../../docs/schemas/kinds/school.json';
import { LocalStore } from '../../data/local-store.service';
import { type CandidateDto, houseOf } from '../candidate';
import { DEV_KINDS_KEY } from '../dev-kinds';
import { en } from '../../i18n/en';
import { SCHOOL_FIELDS } from '../school-kind';
import type { HouseDto } from '../models';
import { type AiHouse, Redactor, askPrompt, candidateLines, selectForAsk, selectForPlan } from './ai-core';
import {
  SCHOOL_AI, SCHOOL_LISTING_SCHEMA, aiKindOf, aiText, askView, extraValue, kindExtractionPrompt, kindText, planView,
  sanitizeKindDraft,
} from './kind-ai';
import { GEMINI_URL, OnDeviceAiService } from './on-device-ai.service';
import { schemaName } from './openai-compat';

const V = vectorsJson as any;

/** A wire candidate as the store gives it and the on-device AI reads it (`OnDeviceAiService.houses`). */
function aiHouse(wire: unknown): AiHouse {
  const h: HouseDto = houseOf(wire as CandidateDto);
  return {
    id: h.id, label: h.label, address: h.address, street: h.street, locality: h.locality, lat: h.lat, lon: h.lon,
    status: h.status, price: h.price, priceType: h.priceType, bedrooms: h.bedrooms, rating: h.rating, areaSqft: h.areaSqft,
    cost: h.cost, rooms: h.rooms, answers: h.answers, moveIn: h.moveIn, floor: h.floor, viewings: [], areaNotes: [],
    distances: [], contactName: h.contactName, contactPhone: h.contactPhone, listingUrl: h.listingUrl, notes: h.notes,
    checklist: h.checklist, visits: [], kind: h.kind, extras: h.extrasOther,
  };
}

const docCase = (i: number) => V.documents[i];

describe('kind-aware AI on the website (kinds PR 7, S4b-BL-211; kind-ai-vectors.json)', () => {
  it('the copy is the kind file\'s slots and extras, and the words are the vectors\' and the English labels', () => {
    expect([SCHOOL_AI.extract, SCHOOL_AI.ask, SCHOOL_AI.plan, SCHOOL_AI.never]).toEqual(
      [schoolJson.ai.extract, schoolJson.ai.ask, schoolJson.ai.plan, schoolJson.ai.never]);
    const named = new Set([...SCHOOL_AI.extract, ...SCHOOL_AI.ask, ...SCHOOL_AI.plan]);
    const fileExtras = (schoolJson.extras as any[]).filter((e) => named.has(e.id));
    expect(SCHOOL_AI.extras.map((e) => e.id)).toEqual(fileExtras.map((e) => e.id));
    for (const e of fileExtras) {
      const copy = SCHOOL_AI.extras.find((x) => x.id === e.id)!;
      expect(copy.type).toBe(e.type);
      expect([copy.maxLength, copy.min, copy.max]).toEqual([e.maxLength, e.min ?? (e.type === 'money' ? 0 : undefined), e.max]);
      if (e.cadences) expect([copy.cadence]).toEqual(e.cadences);
      if (e.options) expect(Object.keys(copy.options!)).toEqual(e.options.map((o: any) => o.id));
      const field = SCHOOL_FIELDS.extras.find((f) => f.id === e.id)!;
      const words = V.kinds[0].extras.find((x: any) => x.id === e.id);
      expect(copy.words).toBe(words.words);
      // The words are the website's English labels: a fee's is the form's label before its rupee suffix.
      expect(copy.words).toBe(en[SCHOOL_FIELDS.label(field)]);
      for (const o of field.options ?? []) expect(copy.options![o.id]).toBe(en[o.label]);
    }
    expect(SCHOOL_AI.noun).toBe(V.kinds[0].noun);
    for (const id of SCHOOL_AI.never) expect([...SCHOOL_AI.ask, ...SCHOOL_AI.plan]).not.toContain(id);
  });

  it('with the flag off, for the house and for a kind that is not built in, the AI reads a house', () => {
    expect(aiKindOf('school', false)).toBeNull();
    expect(aiKindOf('school', true)).toBe(SCHOOL_AI);
    for (const k of ['house', 'clinic', 'constructor', null, undefined]) expect(aiKindOf(k, true)).toBeNull();
  });

  it('every document has the vectors\' lines, and the Ask prompt strips a tag in a value', () => {
    for (const c of V.documents) expect(aiText(askView(aiHouse(c.candidate), c.devKinds), c.devKinds), c.name).toBe(c.expected);
    const docs = V.ask.documents.map((i: number) => {
      const h = aiHouse(docCase(i).candidate);
      return { id: h.id, text: aiText(askView(h, true), true) };
    });
    expect(askPrompt(V.ask.question, docs, V.ask.nonce).user).toBe(V.ask.user);
    const tagged = V.askTag.documents.map((i: number) => {
      const h = aiHouse(docCase(i).candidate);
      return { id: h.id, text: aiText(askView(h, true), true) };
    });
    expect(askPrompt(V.askTag.question, tagged, V.askTag.nonce).user).toBe(V.askTag.user);
  });

  it('an Ask filter on a field the kind hides never keeps the school; with the flag off it is a house', () => {
    for (const c of V.filters) {
      const h = aiHouse(docCase(c.candidate).candidate);
      const kept = selectForAsk([askView(h, c.devKinds)], 'school', c.filter, (x) => aiText(x, c.devKinds));
      expect(kept.length === 1, JSON.stringify(c)).toBe(c.matches);
    }
  });

  it('a Plan candidate has only what the plan slot names', () => {
    for (const c of V.plan) {
      const h = aiHouse(docCase(c.candidate).candidate);
      const [candidate] = selectForPlan([planView(h, c.devKinds)], c.start[0], c.start[1]);
      expect(candidateLines([candidate])).toBe(c.line);
      expect({ label: candidate.label, locality: candidate.locality, street: candidate.street, status: candidate.status, price: candidate.price, priceType: candidate.priceType, bedrooms: candidate.bedrooms, rating: candidate.rating }).toEqual(c.summary);
    }
  });

  it('never wins over ask, and a value outside its type is no line', () => {
    const pine = aiHouse(docCase(0).candidate);
    const strict = { ...SCHOOL_AI, never: ['contactName', 'contactPhone', 'notes', 'board'] };
    expect(kindText(pine, strict)).not.toContain('Notes:');
    expect(kindText(pine, strict)).not.toContain('Board:');
    const r = new Redactor(null, null);
    const fees = SCHOOL_AI.extras.find((e) => e.id === 'fees')!;
    expect(extraValue(fees, { amount: 96000, cadence: 'year' }, r)).toBe('Rs 96000');
    for (const bad of [{ amount: 96000, cadence: 'month' }, { amount: -1, cadence: 'year' }, { amount: 1e13, cadence: 'year' }, 96000, null]) {
      expect(extraValue(fees, bad, r)).toBeNull();
    }
  });

  it('the school\'s Extract prompt, schema and drafts are the vectors\'', () => {
    const e = V.extract;
    const p = kindExtractionPrompt(SCHOOL_AI, e.listing, e.nonce);
    expect([p.system, p.user]).toEqual([e.system, e.user]);
    expect(Object.keys(SCHOOL_LISTING_SCHEMA.properties)).toEqual(Object.keys(e.schema));
    for (const [k, d] of Object.entries(e.schema)) expect((SCHOOL_LISTING_SCHEMA.properties as any)[k].description).toBe(d);
    expect(schemaName(SCHOOL_LISTING_SCHEMA)).toBe('listing');
    for (const c of e.sanitize) expect(sanitizeKindDraft(SCHOOL_AI, c.raw, c.source), c.name).toEqual({ ...c.expected, areaSqft: null });
  });
});

describe('kind-aware AI through OnDeviceAiService (kinds PR 7)', () => {
  let http: HttpTestingController;
  let ai: OnDeviceAiService;
  let houses: unknown[];
  const reply = (json: unknown) => ({ candidates: [{ content: { parts: [{ text: JSON.stringify(json) }] } }] });

  beforeEach(() => {
    houses = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: LocalStore, useValue: { allHouses: async () => houses, allVisits: async () => [], viewings: { all: async () => [] }, areas: { all: async () => [] }, places: { all: async () => [] }, areaNotes: { rows: async () => [] } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    ai = TestBed.inject(OnDeviceAiService);
  });

  afterEach(() => {
    localStorage.removeItem(DEV_KINDS_KEY);
    http.verify();
  });

  async function sent() {
    await new Promise((r) => setTimeout(r));
    return http.expectOne(GEMINI_URL);
  }

  it('Ask sends a school\'s lines with the flag on and a house\'s with it off; no unknown extra ever', async () => {
    for (const on of [true, false]) {
      if (on) localStorage.setItem(DEV_KINDS_KEY, 'school');
      else localStorage.removeItem(DEV_KINDS_KEY);
      houses = [houseOf(docCase(2).candidate as CandidateDto)];
      const answer = ai.ask('AIzaTestKey1234', 'Which school runs a bus?');
      const req = await sent();
      const user: string = req.request.body.contents[0].parts[0].text;
      expect(user).toContain(docCase(on ? 2 : 3).expected.replace(/<\/?\s*houses[^>]*>/gi, ''));
      expect(user).not.toContain('99001');
      expect(user).not.toContain('secretNote');
      expect(user).not.toContain('Ramesh');
      req.flush(reply({ answer: 'none' }));
      await answer;
    }
  });

  it('Extract reads a school listing with the flag on, and refuses the kind with it off', async () => {
    localStorage.setItem(DEV_KINDS_KEY, 'school');
    const e = V.extract;
    const draft = ai.extractListing('AIzaTestKey1234', e.listing, 'school');
    const req = await sent();
    expect(req.request.body.systemInstruction.parts[0].text.replace(/listing-[0-9a-f]{6}/g, 'listing-abc123')).toBe(e.system);
    expect(req.request.body.generationConfig.responseSchema).toBe(SCHOOL_LISTING_SCHEMA);
    req.flush(reply(e.sanitize[0].raw));
    expect(await draft).toEqual({ ...e.sanitize[0].expected, areaSqft: null });
    localStorage.removeItem(DEV_KINDS_KEY);
    await expect(ai.extractListing('AIzaTestKey1234', e.listing, 'school')).rejects.toThrow('school');
  });
});
