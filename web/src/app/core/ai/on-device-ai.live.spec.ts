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
import { HttpRequest, provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { LocalStore } from '../../data/local-store.service';
import { OnDeviceAiService } from './on-device-ai.service';

/** The key, from the environment of the manual *AI evals* workflow only (the `AI_API_KEY` secret). */
const KEY =
  (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env?.['DOORPRINTS_LIVE_GEMINI_KEY'] ?? '';
const QUIET = '11111111-1111-4111-8111-111111111111';
const NOISY = '22222222-2222-4222-8222-222222222222';
const pause = () => new Promise((r) => setTimeout(r, 4000)); // free-tier requests-per-minute limits

/**
 * The website's on-device AI against the real Gemini API (docs/03 §13.1, ADR-26; docs/06 TC-U-88): one Extract, one
 * Ask and one Plan through [OnDeviceAiService] with the key from `DOORPRINTS_LIVE_GEMINI_KEY`. Skipped without it, so
 * the normal build never calls Google. Checks what a fake cannot (Google accepts the request, schemas and key
 * header; the answers pass the server's checks) and reads every request body as sent: no saved contact name, phone or email address
 * number leaves the browser.
 */
describe.skipIf(!KEY)('OnDeviceAiService against Gemini (real key)', () => {
  it('extracts, asks and plans', { timeout: 120_000 }, async () => {
    const sent: string[] = [];
    const houses = [
      {
        id: QUIET, label: 'Blue gate', locality: 'Indiranagar', lat: 12.9719, lon: 77.6412, status: 'SHORTLISTED',
        price: 25000, priceType: 'RENT', bedrooms: 2, contactName: 'Ramesh Kumar', contactPhone: '98450 12345',
        notes: 'Very quiet lane, 24x7 water. Ramesh says call 98450 12345 after 6 pm or mail kumar.r83@example.com.',
        updatedAt: '2026-09-20T10:00:00Z', deleted: false,
      },
      {
        id: NOISY, label: 'Green view', locality: 'Koramangala', lat: 12.9352, lon: 77.6245, status: 'SHORTLISTED',
        price: 40000, priceType: 'RENT', bedrooms: 3, notes: 'On the main road, traffic noise all day.',
        updatedAt: '2026-09-21T10:00:00Z', deleted: false,
      },
    ];
    // Typed on purpose: the service reads these six methods of the store; a method it starts to use that is missing here
    // (the live run once failed on `viewings`, added after this test was written) fails the build, not the manual run.
    const store: Pick<LocalStore, 'allHouses' | 'allVisits' | 'viewings' | 'areas' | 'places' | 'areaNoteRows'> = {
      allHouses: async () => houses as unknown as Awaited<ReturnType<LocalStore['allHouses']>>,
      allVisits: async () => [],
      viewings: async () => [],
      areas: async () => [],
      places: async () => [],
      areaNoteRows: async () => [],
    };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withFetch(),
          withInterceptors([
            (req: HttpRequest<unknown>, next) => {
              sent.push(JSON.stringify(req.body));
              return next(req);
            },
          ]),
        ),
        { provide: LocalStore, useValue: store },
      ],
    });
    const ai = TestBed.inject(OnDeviceAiService);

    const draft = await ai.extractListing(KEY, '2BHK flat in Indiranagar for rent, 25,000 a month, lift and parking. Call 98450 12345.');
    expect(draft.price).toBe(25000);
    expect(draft.bedrooms).toBe(2);
    console.log(`Extract: label=${draft.label}, price=${draft.price}, bedrooms=${draft.bedrooms}`);
    await pause();

    const answer = await ai.ask(KEY, 'Which house is quiet?');
    expect(answer.answer.trim()).not.toBe('');
    expect(answer.citations.every((c) => c.houseId === QUIET || c.houseId === NOISY)).toBe(true);
    console.log(`Ask: grounded=${answer.grounded}, cited=${answer.citations.map((c) => c.houseId).join(',')}`);
    await pause();

    const plan = await ai.planVisits(KEY, { question: 'Plan visits to my shortlisted houses', startLat: 12.9716, startLon: 77.5946, maxStops: 2 });
    expect(plan.stops.length).toBeGreaterThan(0);
    expect(plan.stops.every((s) => s.houseId === QUIET || s.houseId === NOISY)).toBe(true);
    console.log(`Plan: fallback=${plan.fallback}, stops=${plan.stops.map((s) => s.houseId).join(',')}, total=${plan.totalMeters} m`);

    expect(sent.length).toBe(3);
    for (const body of sent.slice(1)) {
      expect(body.includes('Ramesh') || body.includes('98450'), 'a saved contact left the browser').toBe(false);
      expect(body.includes('@example.com') || body.includes('kumar.r83'), 'an email address left the browser').toBe(false);
    }
  });
});
