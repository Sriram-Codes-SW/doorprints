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

import { HttpClient, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GeocodeService } from './geocode.service';
import { NOMINATIM_SEARCH, RequestThrottle, placeOf, searchParams } from './place-search';

/**
 * The locality lookup of a shared listing (S4b-BL-83): the request, the one-a-second throttle and the answer, without
 * the network; then `GeocodeService.search` against a fake backend, spaced from a reverse lookup by a second.
 */
describe('place search', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('asks Nominatim for one place in India, in the app language, and nothing for a blank name', () => {
    expect(searchParams('  Indiranagar,\n Bengaluru ', 'hi')).toEqual({
      q: 'Indiranagar, Bengaluru', format: 'jsonv2', limit: '1', countrycodes: 'in', 'accept-language': 'hi',
    });
    expect(searchParams('x'.repeat(300), 'en')!.q.length).toBe(200);
    expect(searchParams('   ', 'en')).toBeNull();
  });

  it('reads the first usable answer and nothing from an empty or odd one', () => {
    expect(placeOf([{ lat: '12.9784', lon: '77.6408', display_name: 'Indiranagar, Bengaluru' }])).toEqual({ lat: 12.9784, lon: 77.6408, label: 'Indiranagar, Bengaluru' });
    expect(placeOf([{ lat: 'x', lon: '1' }, { lat: '13.05', lon: '80.28' }])).toEqual({ lat: 13.05, lon: 80.28, label: null });
    expect(placeOf([])).toBeNull();
    expect(placeOf({ error: 'nope' })).toBeNull();
    expect(placeOf([{ lat: '0', lon: '0' }])).toBeNull();
    expect(placeOf([{ lat: '95', lon: '10' }])).toBeNull();
  });

  it('lets one request through a second', () => {
    let now = 10_000;
    const throttle = new RequestThrottle(1000, () => now);
    expect(throttle.next()).toBe(0);
    expect(throttle.next()).toBe(1000);
    now += 300;
    expect(throttle.next()).toBe(1700);
    now += 5000;
    expect(throttle.next()).toBe(0);
  });

  it('GeocodeService.search sends the request, a second after a reverse lookup', async () => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(HttpClient);
    const geocode = TestBed.inject(GeocodeService);
    const http = TestBed.inject(HttpTestingController);
    geocode.reverse(12.97, 77.64).subscribe();
    let found: unknown = 'pending';
    geocode.search('Indiranagar', 'en').subscribe((p) => (found = p));
    await vi.advanceTimersByTimeAsync(0);
    http.expectOne((r) => r.url.endsWith('/reverse')).flush({});
    http.expectNone((r) => r.url === NOMINATIM_SEARCH);
    await vi.advanceTimersByTimeAsync(1000);
    const req = http.expectOne((r) => r.url === NOMINATIM_SEARCH);
    expect(req.request.params.get('q')).toBe('Indiranagar');
    expect(req.request.params.get('countrycodes')).toBe('in');
    expect(req.request.params.has('key')).toBe(false);
    req.flush([{ lat: '12.9784', lon: '77.6408', display_name: 'Indiranagar' }]);
    expect(found).toEqual({ lat: 12.9784, lon: 77.6408, label: 'Indiranagar' });
    http.verify();
  });
});
