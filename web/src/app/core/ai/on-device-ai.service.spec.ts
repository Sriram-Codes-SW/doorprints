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
import { LocalStore } from '../../data/local-store.service';
import { GEMINI_URL, OnDeviceAiError, OnDeviceAiService, RATE_LIMIT } from './on-device-ai.service';

/** Gemini's answer carrying [json] as the model's text. */
const reply = (json: unknown) => ({ candidates: [{ content: { parts: [{ text: JSON.stringify(json) }] } }] });

const house = (id: string, over: Record<string, unknown> = {}) => ({
  id, label: `House ${id}`, address: null, street: null, locality: 'Indiranagar', lat: 12.97, lon: 77.64,
  status: 'SHORTLISTED', price: 25000, priceType: 'RENT', bedrooms: 2, rating: 4, contactName: 'Ravi',
  contactPhone: '+91 98450 12345', listingUrl: null, notes: 'Quiet street, call 9845012345', checklist: null,
  updatedAt: '2026-09-20T10:00:00Z', deleted: false, ...over,
});

describe('OnDeviceAiService (ADR-26)', () => {
  let http: HttpTestingController;
  let ai: OnDeviceAiService;
  let houses: unknown[];

  beforeEach(() => {
    houses = [house('h1'), house('h2', { locality: 'Koramangala', lat: 12.93, lon: 77.62 })];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LocalStore, useValue: { allHouses: async () => houses, allVisits: async () => [], viewings: async () => [], areas: async () => [], places: async () => [], areaNoteRows: async () => [] } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    ai = TestBed.inject(OnDeviceAiService);
  });

  afterEach(() => http.verify());

  /** Waits for the one Gemini request, checks it went only to Google with the key, and returns it. */
  async function geminiRequest() {
    await new Promise((r) => setTimeout(r)); // the store's reads come first
    const req = http.expectOne(GEMINI_URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('x-goog-api-key')).toBe('AIzaTestKey1234');
    expect(req.request.headers.has('X-API-Key')).toBe(false);
    return req;
  }

  it('extracts a listing with a schema at temperature 0 and cleans the draft as the server does', async () => {
    const draft = ai.extractListing('AIzaTestKey1234', '2BHK in Indiranagar, 25,000 a month. Call Ravi 98450 12345');
    const req = await geminiRequest();
    const body = req.request.body;
    expect(body.generationConfig).toMatchObject({ temperature: 0, responseMimeType: 'application/json' });
    expect(body.generationConfig.responseSchema.properties.price).toBeTruthy();
    req.flush(reply({ label: '2BHK Indiranagar', price: '25,000', priceType: 'rent', bedrooms: '2', contactPhone: '98450 12345' }));
    expect(await draft).toMatchObject({ label: '2BHK Indiranagar', price: 25000, priceType: 'RENT', bedrooms: 2 });
  });

  it('asks over the saved houses with contacts left out, and keeps only citations of houses it sent', async () => {
    const answer = ai.ask('AIzaTestKey1234', 'Which house is quiet?');
    const req = await geminiRequest();
    const sent = JSON.stringify(req.request.body);
    expect(sent).toContain('h1');
    expect(sent).not.toContain('Ravi');
    expect(sent).not.toContain('9845012345');
    expect(req.request.body.generationConfig.temperature).toBe(0.1);
    req.flush(reply({ answer: 'House h1 is quiet [house:h1] [house:nope].', citedHouseIds: ['h1', 'nope'] }));
    const res = await answer;
    expect(res.grounded).toBe(true);
    expect(res.citations.map((c) => c.houseId)).toEqual(['h1']);
  });

  it('with no houses, Ask answers "I don\'t know" without calling Google', async () => {
    houses = [];
    const res = await ai.ask('AIzaTestKey1234', 'Anything?');
    expect(res.grounded).toBe(false);
    http.expectNone(GEMINI_URL);
  });

  it('plans in one call and falls back to the nearest houses when the answer is unusable', async () => {
    const plan = ai.planVisits('AIzaTestKey1234', { question: 'Plan my Saturday', startLat: 12.97, startLon: 77.64, maxStops: 2 });
    const req = await geminiRequest();
    expect(req.request.body.generationConfig.temperature).toBe(0.2);
    req.flush({ candidates: [{ content: { parts: [{ text: 'not json' }] } }] });
    const res = await plan;
    expect(res.fallback).toBe(true);
    expect(res.stops.map((s) => s.houseId)).toEqual(['h1', 'h2']);
  });

  it('maps Google\'s errors: a bad key, too many requests, anything else', async () => {
    const cases: [number, object, string][] = [
      [400, { error: { status: 'INVALID_ARGUMENT', details: [{ reason: 'API_KEY_INVALID' }] } }, 'keyRejected'],
      [403, { error: { status: 'PERMISSION_DENIED' } }, 'keyRejected'],
      [429, { error: { status: 'RESOURCE_EXHAUSTED' } }, 'rateLimited'],
      [500, { error: { status: 'INTERNAL' } }, 'unavailable'],
    ];
    for (const [status, error, kind] of cases) {
      const test = ai.test('AIzaTestKey1234');
      (await geminiRequest()).flush(error, { status, statusText: 'x' });
      await expect(test).rejects.toMatchObject({ kind });
    }
  });

  it('allows ten requests a minute in this browser, then says when to try again', async () => {
    let t = 1_000_000;
    ai.now = () => t;
    for (let i = 0; i < RATE_LIMIT; i++) {
      const draft = ai.extractListing('AIzaTestKey1234', 'Flat for rent');
      (await geminiRequest()).flush(reply({}));
      await draft;
    }
    t += 30_000;
    const refused = ai.extractListing('AIzaTestKey1234', 'Flat for rent');
    await expect(refused).rejects.toBeInstanceOf(OnDeviceAiError);
    await expect(refused).rejects.toMatchObject({ kind: 'rateLimited', retryAfter: 30 });
    t += 30_000;
    const again = ai.extractListing('AIzaTestKey1234', 'Flat for rent');
    (await geminiRequest()).flush(reply({}));
    await again;
  });
});
