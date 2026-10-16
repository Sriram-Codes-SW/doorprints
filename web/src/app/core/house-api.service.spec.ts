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
import { firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HouseApiService } from './house-api.service';
import { newHouse } from './models';
import type { HouseDto, VisitDto } from './models';

/**
 * The server sync client against the server of kinds step 2 (#281): the houses are `/api/candidates` and travel as
 * candidates (`kind`, `money`, `extras`), a visit and a photo name their house as `candidateId` (kinds step 3,
 * S4b-BL-206). The bodies are the server's `HouseDto`, `VisitDto` and `PhotoDto` as written out in its
 * `ApiIntegrationTest`. docs/06 TC-U-200.
 */
describe('HouseApiService on the candidate wire', () => {
  let http: HttpTestingController;
  let api: HouseApiService;

  /** A Shimla rent with a floor and maintenance. */
  const SHIMLA: HouseDto = {
    ...newHouse(31.1048, 77.1734, 'GPS'), id: 's1', label: 'Mall Road flat', price: 22000, floor: 1, cost: { maintenance: 1500 },
    updatedAt: '2026-09-05T09:00:00.000Z', distanceMeters: 12,
  };
  const SHIMLA_WIRE = {
    id: 's1', kind: 'house', label: 'Mall Road flat', lat: 31.1048, lon: 77.1734, status: 'NEW', money: { amount: 22000, cadence: 'month' },
    locationSource: 'GPS', extras: { floor: 1, maintenance: { amount: 1500, cadence: 'month' } }, checklist: {},
    updatedAt: '2026-09-05T09:00:00.000Z', deleted: false, syncVersion: 0,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(HouseApiService);
  });

  afterEach(() => http.verify());

  it('pulls /api/candidates since a cursor and reads each candidate as the house the app holds', async () => {
    const pulled = firstValueFrom(api.housesSince(5));
    const req = http.expectOne((r) => r.url === '/api/candidates' && r.params.get('since') === '5');
    expect(req.request.method).toBe('GET');
    req.flush([{ ...SHIMLA_WIRE, syncVersion: 6 }]);
    const [house] = await pulled;
    expect([house.price, house.priceType, house.floor, house.cost, house.syncVersion]).toEqual([22000, 'RENT', 1, { maintenance: 1500 }, 6]);
    expect('money' in house || 'extras' in house).toBe(false);
  });

  it('pushes a house to /api/candidates/{id} as its candidate, without the search distance', async () => {
    const pushed = firstValueFrom(api.pushHouse(SHIMLA));
    const req = http.expectOne('/api/candidates/s1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(SHIMLA_WIRE);
    req.flush(SHIMLA_WIRE);
    expect((await pushed).price).toBe(22000);
  });

  it('reads one, deletes one, and finds them nearby and on a street under /api/candidates', () => {
    void firstValueFrom(api.house('s1'));
    http.expectOne('/api/candidates/s1').flush(SHIMLA_WIRE);
    void firstValueFrom(api.deleteHouse('s1'));
    expect(http.expectOne('/api/candidates/s1').request.method).toBe('DELETE');
    void firstValueFrom(api.nearby(31.1, 77.17, 50));
    http.expectOne((r) => r.url === '/api/candidates/nearby' && r.params.get('radius') === '50').flush([]);
    void firstValueFrom(api.onStreet('Mall Road'));
    http.expectOne((r) => r.url === '/api/candidates/street' && r.params.get('name') === 'Mall Road').flush([]);
    void firstValueFrom(api.photoIds('s1'));
    http.expectOne('/api/candidates/s1/photos').flush([]);
    void firstValueFrom(api.uploadPhoto('s1', new Blob([new Uint8Array([1])]), 'p1'));
    expect(http.expectOne('/api/candidates/s1/photos').request.method).toBe('POST');
  });

  it('sends a visit\'s house as candidateId in the same place and reads it back as houseId', async () => {
    const visit: VisitDto = { id: 'v1', houseId: 's1', lat: 31.1, lon: 77.17, arrivedAt: '2026-09-05T10:00:00.000Z', source: 'MANUAL', deleted: false, syncVersion: 0 };
    const pushed = firstValueFrom(api.pushVisit(visit));
    const req = http.expectOne('/api/visits/v1');
    expect(Object.keys(req.request.body as object)).toEqual(['id', 'candidateId', 'lat', 'lon', 'arrivedAt', 'source', 'deleted', 'syncVersion']);
    req.flush({ ...(req.request.body as object), syncVersion: 3 });
    expect(await pushed).toEqual({ ...visit, syncVersion: 3 });

    const list = firstValueFrom(api.visits('s1'));
    http.expectOne((r) => r.url === '/api/visits' && r.params.get('candidateId') === 's1' && !r.params.has('houseId')).flush([{ id: 'v1', candidateId: 's1' }]);
    expect((await list)[0].houseId).toBe('s1');

    const since = firstValueFrom(api.visitsSince(0));
    http.expectOne((r) => r.url === '/api/visits').flush([{ id: 'v2', candidateId: null }]);
    expect((await since)[0]).toEqual({ id: 'v2', houseId: null });
  });

  it('reads a photo change\'s candidateId as houseId, in the feed and in the meta answer', async () => {
    const feed = firstValueFrom(api.photoChangesSince(0));
    http.expectOne((r) => r.url === '/api/photos').flush([{ id: 'p1', candidateId: 's1', deleted: false, syncVersion: 2 }]);
    expect(await feed).toEqual([{ id: 'p1', houseId: 's1', deleted: false, syncVersion: 2 }]);
    const meta = firstValueFrom(api.putPhotoMeta('p1', { roomId: null, tags: [], caption: null, metaUpdatedAt: 5 }));
    http.expectOne('/api/photos/p1/meta').flush({ id: 'p1', candidateId: 's1', deleted: false, syncVersion: 3, metaUpdatedAt: 5 });
    expect((await meta).houseId).toBe('s1');
  });
});
