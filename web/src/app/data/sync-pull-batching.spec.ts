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

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, defer, from, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfigService } from '../core/config.service';
import { HouseApiService } from '../core/house-api.service';
import type { HouseDto, PhotoChangeDto, RecordDto, StatsDto, VisitDto } from '../core/models';
import { MemoryDb } from './local-db';
import { LocalStore } from './local-store.service';
import { PULL_READ_CHUNK, SyncService } from './sync.service';

/**
 * S4b-BL-167: the pull reads the local rows of ONE page of the server's answer, in chunks, not one row at a time and
 * not the whole store. Seam: the real `SyncService` and `LocalStore` over `MemoryDb` (what jsdom runs), a fake
 * `HouseApiService`, and a counter on the database's read methods. Expected values are written down here (ids and
 * labels are spelled by the test, the merge outcome is the last-write-wins rule of docs/11), not computed by the code.
 */

/** A UUID-shaped id from a family digit and a number, so the kinds of row never collide. */
function id(kind: number, n: number): string {
  return `0000000${kind}-0000-4000-8000-${String(n).padStart(12, '0')}`;
}

const SERVER_TIME = '2026-09-21T10:00:00.000Z';
const OLDER = Date.parse('2026-09-01T00:00:00.000Z');
const NEWER = Date.parse('2026-09-30T00:00:00.000Z');

class Api {
  houses: HouseDto[] = [];
  visits: VisitDto[] = [];
  photos: PhotoChangeDto[] = [];
  records: RecordDto[] = [];
  readonly fetched: string[] = [];
  version = 100000;
  pushHouse: (h: HouseDto) => Observable<HouseDto> = (h) => of({ ...h, syncVersion: ++this.version });

  stats(): Observable<StatsDto> {
    return of({ houses: 0, shortlisted: 0, rejected: 0, visits: 0, streets: 0 });
  }
  housesSince(): Observable<HouseDto[]> {
    return of(this.houses);
  }
  visitsSince(): Observable<VisitDto[]> {
    return of(this.visits);
  }
  photoChangesSince(): Observable<PhotoChangeDto[]> {
    return of(this.photos);
  }
  recordsSince(): Observable<RecordDto[]> {
    return of(this.records);
  }
  photo(photoId: string): Observable<Blob> {
    this.fetched.push(photoId);
    return of(new Blob([new Uint8Array([0xff, 0xd8])], { type: 'image/jpeg' }));
  }
  pushVisit(v: VisitDto): Observable<VisitDto> {
    return of({ ...v, syncVersion: ++this.version });
  }
  pushRecord(r: RecordDto): Observable<RecordDto> {
    return of({ ...r, syncVersion: ++this.version });
  }
  deletePhoto(): Observable<unknown> {
    return of({});
  }
  uploadPhoto(_h: string, _f: Blob, photoId: string): Observable<{ id: string }> {
    return of({ id: photoId });
  }
  putPhotoMeta(photoId: string): Observable<PhotoChangeDto> {
    return of({ id: photoId, houseId: id(1, 0), deleted: false, syncVersion: ++this.version });
  }
}

const houseDto = (n: number, label: string): HouseDto => ({
  id: id(1, n), label, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: n + 1, updatedAt: SERVER_TIME,
});
const visitDto = (n: number, houseN: number): VisitDto => ({
  id: id(2, n), houseId: id(1, houseN), lat: 13, lon: 80, arrivedAt: SERVER_TIME, source: 'AUTO', deleted: false, syncVersion: n + 1, updatedAt: SERVER_TIME,
});
const recordDto = (n: number, label: string): RecordDto => ({
  type: 'broker', id: id(3, n), payload: { name: label }, updatedAt: SERVER_TIME, deleted: false, syncVersion: n + 1,
});
const photoDto = (n: number, houseN: number, deleted = false): PhotoChangeDto => ({
  id: id(4, n), houseId: id(1, houseN), contentType: 'image/jpeg', sizeBytes: 2, deleted, syncVersion: n + 1, updatedAt: SERVER_TIME,
});

const READS = ['get', 'getAll', 'getAllByIndex', 'getMany', 'keys', 'count'] as const;

describe('SyncService pull batching (S4b-BL-167)', () => {
  let api: Api;
  let store: LocalStore;
  let sync: SyncService;

  beforeEach(async () => {
    api = new Api();
    store = new LocalStore();
    await store.ready();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: HouseApiService, useValue: api as unknown as HouseApiService },
        { provide: LocalStore, useValue: store },
        {
          provide: ConfigService,
          useValue: { configured: () => true, config: signal({ baseUrl: 'https://a.example.com', apiKey: 'k' }).asReadonly() } as unknown as ConfigService,
        },
      ],
    });
    sync = TestBed.inject(SyncService);
    sync.sleep = () => Promise.resolve();
    TestBed.tick();
    await new Promise((r) => setTimeout(r, 0));
    await new Promise((r) => setTimeout(r, 0));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  /** Counts every read the store makes of its database while `run` goes; returns the call counts per method. */
  async function countReads(run: () => Promise<void>): Promise<{ total: number; rows: number; byMethod: Record<string, number>; byStore: Record<string, number> }> {
    const byMethod: Record<string, number> = {};
    const byStore: Record<string, number> = {};
    let rows = 0;
    const proto = MemoryDb.prototype as unknown as Record<string, (...a: unknown[]) => unknown>;
    for (const name of READS) {
      const original = proto[name];
      if (typeof original !== 'function') continue;
      vi.spyOn(proto, name).mockImplementation(function (this: unknown, ...a: unknown[]) {
        byMethod[name] = (byMethod[name] ?? 0) + 1;
        byStore[`${name}:${String(a[0])}`] = (byStore[`${name}:${String(a[0])}`] ?? 0) + 1;
        const result = original.apply(this, a) as Promise<unknown>;
        return result.then((r) => {
          rows += Array.isArray(r) ? r.length : r === undefined ? 0 : 1;
          return r;
        });
      });
    }
    await run();
    vi.restoreAllMocks();
    return { total: Object.values(byMethod).reduce((a, b) => a + b, 0), rows, byMethod, byStore };
  }

  async function seedClean(houses: number): Promise<void> {
    for (let n = 0; n < houses; n++) {
      await store.putHouseFromServer({ ...houseDto(n + 50000, 'bulk'), syncVersion: 1, updatedAt: '2026-01-01T00:00:00.000Z' });
    }
  }

  it('chunks of 500, under SQLite’s limit of 999 variables', () => {
    expect(PULL_READ_CHUNK).toBe(500);
  });

  it('reads 1,001 pulled rows with only 2 more reads per store than 1 row, among many local rows', async () => {
    await seedClean(1500); // local rows that no page row touches: they must not be read
    api.houses = [houseDto(0, 'one')];
    api.visits = [visitDto(0, 0)];
    api.records = [recordDto(0, 'one')];
    const one = await countReads(async () => void (await sync.syncNow(true)));

    const N = 1001; // 3 chunks of at most 500
    api.houses = Array.from({ length: N }, (_, n) => houseDto(n + 10, 'many'));
    api.visits = Array.from({ length: N }, (_, n) => visitDto(n + 10, 0));
    api.records = Array.from({ length: N }, (_, n) => recordDto(n + 10, 'many'));
    const many = await countReads(async () => void (await sync.syncNow(true)));

    // Houses, visits and records each need 2 more chunked reads than a single row, and nothing else grows.
    expect(many.total - one.total).toBe(3 * 2);
    expect(many.total).toBeLessThan(40);
  });

  it('one changed row among 1,500 local rows: the pull does not scan the stores (only the push\u2019s dirty scan does)', async () => {
    await seedClean(1500);
    api.houses = [houseDto(0, 'one')];
    api.visits = [visitDto(0, 0)];
    api.records = [recordDto(0, 'one')];
    const run = await countReads(async () => void (await sync.syncNow(true)));
    // The push phase scans houses, visits and records once each for their dirty rows (`dirtyHouses`, ...): that is the
    // one getAll of each store. The pull used to add a second one (the whole store into a map).
    expect(run.byStore['getAll:houses']).toBe(1);
    expect(run.byStore['getAll:visits']).toBe(1);
    expect(run.byStore['getAll:records']).toBe(1);
    // and the page's own rows were read by key: one chunk per store
    expect(run.byStore['getMany:houses']).toBe(1);
  });

  it('reads photo rows and the houses they name in chunks too', async () => {
    const N = 501; // 501 photo changes of houses that are not here: all skipped, none downloaded
    api.photos = Array.from({ length: N }, (_, n) => photoDto(n, 9000 + n));
    const many = await countReads(async () => void (await sync.syncNow(true)));
    expect(api.fetched).toEqual([]);

    api.photos = [photoDto(0, 9000)];
    const single = await countReads(async () => void (await sync.syncNow(true)));
    expect(many.total - single.total).toBe(2); // one more chunk of photos, one more chunk of houses
  });

  it('splits at exactly 500: 500 ids are one read, 501 are two, 1,000 are two', async () => {
    const reads = async (n: number): Promise<number> => {
      api.houses = Array.from({ length: n }, (_, i) => houseDto(i + 20000, 'edge'));
      const r = await countReads(async () => void (await sync.syncNow(true)));
      return r.byMethod['getMany'] ?? 0;
    };
    const at500 = await reads(500);
    const at501 = await reads(501);
    const at1000 = await reads(1000);
    expect(at501 - at500).toBe(1);
    expect(at1000).toBe(at501);
  });

  it('merges exactly as before: the server wins over clean and absent rows, a newer dirty local edit stays, across chunk edges', async () => {
    const N = 1003;
    // Every 7th house has a newer local edit (dirty again after the push below); every 3rd (not 7th) is absent
    // locally; the rest are clean older copies.
    const keptLocal = (n: number): boolean => n % 7 === 0;
    const absent = (n: number): boolean => n % 3 === 0 && !keptLocal(n);
    for (let n = 0; n < N; n++) {
      if (absent(n)) continue;
      if (keptLocal(n)) await store.saveHouse({ ...houseDto(n, 'mine'), syncVersion: 0 }, OLDER);
      else await store.putHouseFromServer({ ...houseDto(n, 'old copy'), syncVersion: 1, updatedAt: '2026-01-01T00:00:00.000Z' });
    }
    api.pushHouse = (pushed) => defer(() => from(store.saveHouse({ ...pushed, label: 'mine' }, NEWER).then(() => pushed)));
    api.houses = Array.from({ length: N }, (_, n) => houseDto(n, 'server'));
    api.visits = Array.from({ length: N }, (_, n) => visitDto(n, 0));
    api.records = Array.from({ length: N }, (_, n) => recordDto(n, 'server'));
    await store.putRecordFromServer({ ...recordDto(1, 'old'), syncVersion: 1, updatedAt: '2026-01-01T00:00:00.000Z' });

    await sync.syncNow(true);

    const houses = new Map((await store.allHouses()).map((h) => [h.id, h]));
    expect(houses.size).toBe(N);
    for (let n = 0; n < N; n++) {
      expect(houses.get(id(1, n))?.label, `house ${n}`).toBe(keptLocal(n) ? 'mine' : 'server');
    }
    const visits = await store.allVisits();
    expect(new Set(visits.map((v) => v.id))).toEqual(new Set(Array.from({ length: N }, (_, n) => id(2, n))));
    const records = await store.allRecords();
    expect(records.length).toBe(N);
    expect(records.every((r) => r.payload['name'] === 'server')).toBe(true);
  });

  it('photos: a tombstone forgets a local photo, a known house gets the download, an unknown house is skipped, across a chunk edge', async () => {
    await store.putHouseFromServer({ ...houseDto(1, 'here'), syncVersion: 1 });
    await store.photos.put({
      id: id(4, 1), houseId: id(1, 1), blob: new Blob(['x']), contentType: 'image/jpeg', sizeBytes: 1,
      createdAt: null, updatedAt: null, deleted: false, syncVersion: 1, uploaded: true,
    });
    api.houses = [houseDto(1, 'here')];
    api.photos = [
      photoDto(1, 1, true), // forget the local photo
      photoDto(2, 1), // new photo of a known house: download
      photoDto(3, 777), // house not here: skipped
      ...Array.from({ length: 500 }, (_, i) => photoDto(100 + i, 777)), // push the next one over a chunk edge
      photoDto(900, 1), // known house, after the chunk edge: download
    ];
    await sync.syncNow(true);
    expect(await store.photos.get(id(4, 1))).toBeUndefined();
    expect([...api.fetched].sort()).toEqual([id(4, 2), id(4, 900)]);
    expect(await store.photos.get(id(4, 3))).toBeUndefined();
  });
});
