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

import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, defer, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ConfigService } from '../core/config.service';
import { HouseApiService } from '../core/house-api.service';
import type { HouseDto, PhotoChangeDto, RecordDto, StatsDto, VisitDto } from '../core/models';
import type { PhotoMeta } from '../shared/photo-tags';
import { LocalStore } from './local-store.service';
import { SETTING_KEYS, millis } from './records';
import { SYNC_BACKEND, ServerSyncBackend } from './sync-backend';
import type { SyncBackend } from './sync-backend';
import { keepLocalRecord, serverMerge } from './sync-rules';
import type { MergeRule } from './sync-rules';
import { SyncService } from './sync.service';

const H1 = '11111111-1111-4111-8111-111111111111';
const H2 = '22222222-2222-4222-8222-222222222222';
const V1 = '33333333-3333-4333-8333-333333333333';
const B1 = '55555555-5555-4555-8555-555555555555';
const P1 = '77777777-7777-4777-8777-777777777777';
const P2 = '88888888-8888-4888-8888-888888888888';
const AT = '2026-09-21T10:00:00.000Z';

function house(id: string, over: Partial<HouseDto> = {}): HouseDto {
  return { id, label: `House ${id}`, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, ...over };
}

/** An in-memory remote: every call in order, and the answers a test set up. Not the server: no HouseApiService. */
class FakeSyncBackend implements SyncBackend {
  readonly calls: string[] = [];
  mergeRule: MergeRule = serverMerge;
  /** A snapshot backend (Drive): pushes are noted, `commitPushes` sends them (S4b-BL-118). */
  stagesPushes = false;
  commitFails = false;
  commitPushes(): Observable<void> {
    return defer(() => {
      this.calls.push('commitPushes');
      return this.commitFails ? throwError(() => new Error('no connection')) : of(undefined);
    });
  }
  behind: () => Observable<boolean> = () => of(false);
  houses: HouseDto[] = [];
  visits: VisitDto[] = [];
  records: RecordDto[] = [];
  photos: PhotoChangeDto[] = [];
  /** The answer to a pushed house; a test makes it fail. */
  houseAnswer: (house: HouseDto) => Observable<HouseDto> = (h) => of({ ...h, syncVersion: ++this.version });
  private version = 1000;

  isBehind(cursors: readonly number[]): Observable<boolean> {
    this.calls.push(`isBehind ${cursors.join(',')}`);
    return this.behind();
  }
  pushHouse(house: HouseDto): Observable<HouseDto> {
    this.calls.push(`pushHouse ${house.id}`);
    return this.houseAnswer(house);
  }
  pushVisit(visit: VisitDto): Observable<VisitDto> {
    this.calls.push(`pushVisit ${visit.id}`);
    return of({ ...visit, syncVersion: ++this.version });
  }
  pushRecord(record: RecordDto): Observable<RecordDto> {
    this.calls.push(`pushRecord ${record.id}`);
    return of({ ...record, syncVersion: ++this.version });
  }
  deletePhoto(id: string): Observable<unknown> {
    this.calls.push(`deletePhoto ${id}`);
    return of({});
  }
  uploadPhoto(_houseId: string, _blob: Blob, id: string): Observable<unknown> {
    this.calls.push(`uploadPhoto ${id}`);
    return of({ id });
  }
  pushPhotoMeta(id: string, meta: PhotoMeta): Observable<PhotoChangeDto> {
    this.calls.push(`pushPhotoMeta ${id}`);
    return of({ id, houseId: H1, deleted: false, syncVersion: ++this.version, ...meta });
  }
  housesSince(cursor: number): Observable<HouseDto[]> {
    return defer(() => {
      this.calls.push(`housesSince ${cursor}`);
      return of(this.houses);
    });
  }
  visitsSince(cursor: number): Observable<VisitDto[]> {
    this.calls.push(`visitsSince ${cursor}`);
    return of(this.visits);
  }
  recordsSince(cursor: number): Observable<RecordDto[]> {
    this.calls.push(`recordsSince ${cursor}`);
    return of(this.records);
  }
  photoChangesSince(cursor: number): Observable<PhotoChangeDto[]> {
    this.calls.push(`photoChangesSince ${cursor}`);
    return of(this.photos);
  }
  downloadPhoto(id: string): Observable<Blob> {
    this.calls.push(`downloadPhoto ${id}`);
    return of(new Blob([new Uint8Array([0xff, 0xd8])], { type: 'image/jpeg' }));
  }
  /** Photos the backend cannot have (Drive: tampered, planted): null, the loop skips them (S4b-BL-128). */
  unavailable = new Set<string>();
  downloadPhotoIfAvailable(id: string): Observable<Blob | null> {
    this.calls.push(`downloadPhoto ${id}`);
    return this.unavailable.has(id) ? of(null) : of(new Blob([new Uint8Array([0xff, 0xd8])], { type: 'image/jpeg' }));
  }
  /** The network policy (S4b-BL-128): false holds photo bytes back. */
  allowed = true;
  photosAllowed(): boolean {
    return this.allowed;
  }
}

function httpError(status: number): HttpErrorResponse {
  return new HttpErrorResponse({ status, statusText: 'x', url: 'https://sync.example/api/houses' });
}

/**
 * S4b-BL-70: `SyncService` drives whatever `SyncBackend` it is given (`SYNC_BACKEND`), not only the server: a fake
 * backend, with no `HouseApiService` behind it, gets the dirty rows, is pulled since the stored cursors, decides the
 * merge with its own rule, is asked "is it behind", and its failures are worded by `errorMsg` as the server's are.
 */
describe('SyncService through the SyncBackend seam', () => {
  let backend: FakeSyncBackend;
  let store: LocalStore;
  let sync: SyncService;

  beforeEach(async () => {
    backend = new FakeSyncBackend();
    store = new LocalStore();
    await store.ready();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: SYNC_BACKEND, useValue: backend },
        { provide: LocalStore, useValue: store },
        {
          provide: ConfigService,
          useValue: { configured: () => true, config: () => ({ baseUrl: 'https://a.example.com', apiKey: 'key-a' }) } as unknown as ConfigService,
        },
      ],
    });
    sync = TestBed.inject(SyncService);
    sync.sleep = () => Promise.resolve();
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await new Promise((resolve) => setTimeout(resolve, 0));
    backend.calls.length = 0;
  });

  afterEach(() => TestBed.resetTestingModule());

  it('pushes the dirty rows, then pulls since the stored cursors, through any backend', async () => {
    await store.saveHouse(house(H1, { label: 'Mine' }), Date.parse(AT));
    await store.saveVisit({ id: V1, houseId: H1, lat: 1, lon: 2, arrivedAt: AT, source: 'MANUAL', deleted: false, syncVersion: 0 }, Date.parse(AT));
    await store.records.save('broker', B1, { name: 'Ravi' }, Date.parse(AT));
    await store.setSetting(SETTING_KEYS.houseCursor, '10');
    await store.setSetting(SETTING_KEYS.visitCursor, '20');
    await store.setSetting(SETTING_KEYS.recordCursor, '30');
    await store.setSetting(SETTING_KEYS.photoCursor, '40');
    backend.houses = [house(H2, { label: 'From elsewhere', updatedAt: AT, syncVersion: 15 })];

    await sync.syncNow(true);

    expect(sync.lastError()).toBeNull();
    expect(backend.calls).toEqual([
      'isBehind 10,20,40,30',
      `pushHouse ${H1}`,
      `pushVisit ${V1}`,
      `pushRecord ${B1}`,
      'housesSince 10',
      'visitsSince 20',
      'recordsSince 30',
      'photoChangesSince 40',
    ]);
    expect(sync.lastOutcome()).toMatchObject({ pushed: 3, pulled: 1, skipped: 0 });
    expect(await store.dirtyHouses()).toEqual([]);
    expect((await store.getHouse(H2))?.label).toBe('From elsewhere');
    expect((await store.cursors()).house).toBe(15);
  });

  it('a snapshot backend: rows are marked clean only after its commit (S4b-BL-118)', async () => {
    await store.saveHouse(house(H1, { label: 'Mine' }), Date.parse(AT));
    await store.saveVisit({ id: V1, houseId: H1, lat: 1, lon: 2, arrivedAt: AT, source: 'MANUAL', deleted: false, syncVersion: 0 }, Date.parse(AT));
    backend.stagesPushes = true;
    backend.commitFails = true;
    await sync.syncNow(true);
    expect(sync.lastError()).not.toBeNull();
    // Pushed, but the snapshot was not confirmed: nothing may be marked clean.
    expect((await store.dirtyHouses()).map((h) => h.id)).toEqual([H1]);
    expect((await store.dirtyVisits()).map((v) => v.id)).toEqual([V1]);

    backend.commitFails = false;
    backend.calls.length = 0;
    await sync.syncNow(true);
    expect(sync.lastError()).toBeNull();
    expect(await store.dirtyHouses()).toEqual([]);
    expect(await store.dirtyVisits()).toEqual([]);
    expect(backend.calls.indexOf('commitPushes')).toBeGreaterThan(backend.calls.indexOf(`pushVisit ${V1}`));
    expect(backend.calls.indexOf('commitPushes')).toBeLessThan(backend.calls.indexOf('housesSince 0'));
  });

  it('does not ask whether the backend is behind before anything was pulled', async () => {
    await sync.syncNow(true);
    expect(backend.calls[0]).toBe('housesSince 0');
  });

  /** A pulled row older than a clean local one: the backend's rule decides, not a rule the loop hard-wires. */
  async function pullOlderOverCleanLocal(): Promise<string | undefined> {
    await store.putHouseFromServer({ ...house(H1, { label: 'Newer here' }), updatedAt: '2026-09-22T00:00:00.000Z' });
    backend.houses = [house(H1, { label: 'Older snapshot', updatedAt: AT, syncVersion: 1 })];
    await sync.syncNow(true);
    return (await store.getHouse(H1))?.label;
  }

  it("lets the server's rule overwrite a clean row", async () => {
    expect(await pullOlderOverCleanLocal()).toBe('Older snapshot');
  });

  it('uses the merge rule the backend supplies', async () => {
    // Last write wins whether or not the local row is dirty (the shape of Drive's rule, docs/15 §5.1).
    backend.mergeRule = (local, incoming) => !!local && millis(local.updatedAt) >= millis(incoming.updatedAt);
    expect(await pullOlderOverCleanLocal()).toBe('Newer here');
  });

  it('shows the rule every local record, clean ones too (S4b-BL-130)', async () => {
    // A clean record newer than the incoming one: the server's rule overwrites it, a last-write-wins rule keeps it.
    const pullOlderRecord = async (): Promise<unknown> => {
      await store.records.putFromServer({ type: 'broker', id: B1, payload: { name: 'Newer here' }, updatedAt: '2026-09-22T00:00:00.000Z', deleted: false, syncVersion: 1 });
      backend.records = [{ type: 'broker', id: B1, payload: { name: 'Older snapshot' }, updatedAt: AT, deleted: false, syncVersion: 2 }];
      await sync.syncNow(true);
      return (await store.records.all()).find((r) => r.id === B1)?.payload;
    };
    expect(await pullOlderRecord()).toEqual({ name: 'Older snapshot' });
    backend.mergeRule = (local, incoming) => !!local && millis(local.updatedAt) >= millis(incoming.updatedAt);
    await store.records.putFromServer({ type: 'broker', id: B1, payload: { name: 'Newer here' }, updatedAt: '2026-09-22T00:00:00.000Z', deleted: false, syncVersion: 3 });
    backend.records = [{ type: 'broker', id: B1, payload: { name: 'Older snapshot' }, updatedAt: AT, deleted: false, syncVersion: 4 }];
    await sync.syncNow(true);
    expect((await store.records.all()).find((r) => r.id === B1)?.payload).toEqual({ name: 'Newer here' });
    expect(await store.records.dirty()).toEqual([]);
  });

  it('sends everything again and pulls from 0 when the backend is behind', async () => {
    await store.putHouseFromServer({ ...house(H1), updatedAt: AT, syncVersion: 250 });
    await store.setSetting(SETTING_KEYS.houseCursor, '250');
    backend.behind = () => of(true);
    await sync.syncNow(true);
    expect(backend.calls).toContain(`pushHouse ${H1}`);
    expect(backend.calls.filter((c) => c.includes('Since'))).toEqual([
      'housesSince 0',
      'visitsSince 0',
      'recordsSince 0',
      'photoChangesSince 0',
    ]);
    expect(sync.remoteResetAt()).not.toBeNull();
  });

  it('treats a failed "is it behind" as unknown and carries on', async () => {
    await store.setSetting(SETTING_KEYS.houseCursor, '5');
    backend.behind = () => throwError(() => httpError(500));
    await sync.syncNow(true);
    expect(sync.lastError()).toBeNull();
    expect(backend.calls).toContain('housesSince 5');
  });

  it('words a backend failure as the server one is, and leaves the row dirty', async () => {
    await store.saveHouse(house(H1), Date.parse(AT));
    backend.houseAnswer = () => throwError(() => httpError(401));
    await sync.syncNow(true);
    expect(sync.lastError()).toEqual({ key: 'error.auth' });
    expect((await store.dirtyHouses()).map((h) => h.id)).toEqual([H1]);
    expect(backend.calls.some((c) => c.includes('Since'))).toBe(false);
  });

  it('words a lost connection as a network failure and keeps the cursors', async () => {
    await store.setSetting(SETTING_KEYS.houseCursor, '7');
    backend.houses = [];
    backend.housesSince = () => throwError(() => httpError(0));
    await sync.syncNow(true);
    expect(sync.lastError()).toEqual({ key: 'error.network' });
    expect((await store.cursors()).house).toBe(7);
  });

  it('moves photo bytes through the backend', async () => {
    await store.putHouseFromServer({ ...house(H1), updatedAt: AT, syncVersion: 1 });
    backend.photos = [{ id: P1, houseId: H1, contentType: 'image/jpeg', sizeBytes: 2, deleted: false, syncVersion: 9 }];
    await sync.syncNow(true);
    expect(backend.calls).toContain(`downloadPhoto ${P1}`);
    expect((await store.photos.get(P1))?.uploaded).toBe(true);
    expect((await store.cursors()).photo).toBe(9);
  });

  it('a photo the backend cannot have is skipped and the others still come (S4b-BL-128)', async () => {
    await store.putHouseFromServer({ ...house(H1), updatedAt: AT, syncVersion: 1 });
    backend.unavailable.add(P1);
    backend.photos = [
      { id: P1, houseId: H1, contentType: 'image/jpeg', sizeBytes: 2, deleted: false, syncVersion: 9 },
      { id: P2, houseId: H1, contentType: 'image/jpeg', sizeBytes: 2, deleted: false, syncVersion: 10 },
    ];
    await sync.syncNow(true);
    expect(sync.lastError()).toBeNull();
    expect(await store.photos.get(P1)).toBeUndefined();
    expect((await store.photos.get(P2))?.uploaded).toBe(true);
    expect((await store.cursors()).photo).toBe(10);
  });

  it('photo bytes wait while the backend says photos are not allowed (S4b-BL-128)', async () => {
    await store.putHouseFromServer({ ...house(H1), updatedAt: AT, syncVersion: 1 });
    await store.photos.put({
      id: P2, houseId: H1, blob: new Blob([new Uint8Array([9])], { type: 'image/jpeg' }), contentType: 'image/jpeg', sizeBytes: 1,
      createdAt: AT, updatedAt: AT, deleted: false, syncVersion: 0, uploaded: false,
    });
    backend.allowed = false;
    backend.photos = [{ id: P1, houseId: H1, contentType: 'image/jpeg', sizeBytes: 2, deleted: false, syncVersion: 9 }];
    await sync.syncNow(true);
    expect(sync.lastError()).toBeNull();
    expect(backend.calls.filter((c) => c.startsWith('uploadPhoto') || c.includes('downloadPhoto'))).toEqual([]);
    expect((await store.cursors()).photo).toBe(0);
    // Wi-Fi: both go, and the held photo is not lost.
    backend.allowed = true;
    await sync.syncNow(true);
    expect(backend.calls).toContain(`uploadPhoto ${P2}`);
    expect(backend.calls).toContain(`downloadPhoto ${P1}`);
    expect((await store.photos.get(P1))?.uploaded).toBe(true);
    expect((await store.photos.get(P2))?.uploaded).toBe(true);
  });

  it('keeps keepLocalRecord as the server rule', () => {
    expect(serverMerge).toBe(keepLocalRecord);
  });
});

/** The server behind the seam: the same `HouseApiService` calls, answers and errors as before it (S4b-BL-70). */
describe('ServerSyncBackend', () => {
  const seen: string[] = [];
  let stats: () => Observable<StatsDto> = () => of({ houses: 0, shortlisted: 0, rejected: 0, visits: 0, streets: 0 });
  let failure: HttpErrorResponse | null = null;
  const answer = <T>(call: string, value: T): Observable<T> => {
    seen.push(call);
    return failure ? throwError(() => failure) : of(value);
  };
  const api = {
    stats: () => stats(),
    pushHouse: (h: HouseDto) => answer(`pushHouse ${h.id}`, h),
    pushVisit: (v: VisitDto) => answer(`pushVisit ${v.id}`, v),
    pushRecord: (r: RecordDto) => answer(`pushRecord ${r.id}`, r),
    deletePhoto: (id: string) => answer(`deletePhoto ${id}`, {}),
    uploadPhoto: (houseId: string, _b: Blob, id: string) => answer(`uploadPhoto ${houseId} ${id}`, { id }),
    putPhotoMeta: (id: string) => answer(`putPhotoMeta ${id}`, { id, houseId: H1, deleted: false, syncVersion: 1 }),
    housesSince: (n: number) => answer(`housesSince ${n}`, []),
    visitsSince: (n: number) => answer(`visitsSince ${n}`, []),
    recordsSince: (n: number) => answer(`recordsSince ${n}`, []),
    photoChangesSince: (n: number) => answer(`photoChangesSince ${n}`, []),
    photo: (id: string) => answer(`photo ${id}`, new Blob()),
  };
  let backend: ServerSyncBackend;

  beforeEach(() => {
    seen.length = 0;
    failure = null;
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: HouseApiService, useValue: api as unknown as HouseApiService }] });
    backend = TestBed.inject(ServerSyncBackend);
  });

  it('is the default backend, with the server merge rule', () => {
    expect(TestBed.inject(SYNC_BACKEND)).toBe(backend);
    expect(backend.mergeRule).toBe(serverMerge);
  });

  it('is behind when the stats show a highest version below a cursor, and not when an older server says nothing', async () => {
    stats = () => of({ houses: 0, shortlisted: 0, rejected: 0, visits: 0, streets: 0, maxSyncVersion: 30 });
    expect(await firstValue(backend.isBehind([42, 0]))).toBe(true);
    expect(await firstValue(backend.isBehind([30, 0]))).toBe(false);
    stats = () => of({ houses: 0, shortlisted: 0, rejected: 0, visits: 0, streets: 0 });
    expect(await firstValue(backend.isBehind([42]))).toBe(false);
  });

  it('makes the same calls as before the seam', async () => {
    await firstValue(backend.pushHouse(house(H1)));
    await firstValue(backend.uploadPhoto(H1, new Blob(), P1));
    await firstValue(backend.pushPhotoMeta(P1, { roomId: null, tags: [], caption: null, metaUpdatedAt: 1 }));
    await firstValue(backend.housesSince(3));
    await firstValue(backend.downloadPhoto(P1));
    expect(seen).toEqual([`pushHouse ${H1}`, `uploadPhoto ${H1} ${P1}`, `putPhotoMeta ${P1}`, 'housesSince 3', `photo ${P1}`]);
  });

  it('passes the HTTP error through unchanged, for errorMsg to word', async () => {
    failure = httpError(429);
    await expect(firstValue(backend.recordsSince(0))).rejects.toBe(failure);
    failure = httpError(0);
    await expect(firstValue(backend.deletePhoto(P1))).rejects.toBe(failure);
  });
});

function firstValue<T>(source: Observable<T>): Promise<T> {
  return new Promise((resolve, reject) => source.subscribe({ next: resolve, error: reject }));
}
