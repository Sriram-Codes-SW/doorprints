import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { signal } from '@angular/core';
import type { WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, defer, from, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfigService } from '../core/config.service';
import type { ApiConfig } from '../core/config.service';
import { HouseApiService } from '../core/house-api.service';
import type { HouseDto, PhotoChangeDto, VisitDto } from '../core/models';
import { LocalStore } from './local-store.service';
import { SETTING_KEYS } from './records';
import { MAX_RATE_LIMIT_WAIT_MS, RATE_LIMIT_ATTEMPTS, SyncService, rateLimitWaitMs, resumeCursor, wireVersion } from './sync.service';
import type { MigrationState } from './sync.service';

/**
 * The sync engine: the only code in the web app that parses third-party HTTP responses, and the only one that
 * writes into the user's primary store. Everything below is driven through the public API with a fake
 * `HouseApiService` and the real `LocalStore` (jsdom has no IndexedDB, so it runs on `MemoryDb` — the same code
 * path a browser in private mode takes).
 *
 * **The payloads are copied verbatim from
 * `android/shared/src/commonTest/kotlin/com/househunt/shared/api/RecordedResponses.kt`** and must stay
 * byte-identical to them, so the Kotlin contract suite and this one drift together. They are what the Spring
 * Boot backend really writes: Jackson 3 record-component order, Postgres microsecond timestamps, explicit nulls,
 * server-only fields the app has to ignore, and a status (`ARCHIVED`) this build has never heard of.
 */

const HOUSES_SINCE = `[
  {"id":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","label":"2BHK near Indiranagar metro","address":"12, 5th Cross, HAL 2nd Stage, Indiranagar, Bengaluru","street":"5th Cross","locality":"Indiranagar","lat":12.978321,"lon":77.640812,"status":"SHORTLISTED","price":32000,"priceType":"RENT","bedrooms":2,"rating":4,"contactName":null,"contactPhone":null,"listingUrl":"https://example.com/listing/123","notes":"Water 24x7, lift, 1 covered parking","checklist":{"water":5,"parking":4,"noise":2},"createdAt":"2026-09-20T08:30:12.345678Z","updatedAt":"2026-09-21T17:02:44.901234Z","deleted":false,"syncVersion":41,"distanceMeters":null},
  {"id":"9e7c2a44-1b3f-4f0e-8a77-2c5d9e0f1a22","label":"Old villa","address":null,"street":null,"locality":null,"lat":12.9352,"lon":77.6245,"status":"ARCHIVED","price":null,"priceType":null,"bedrooms":null,"rating":null,"contactName":null,"contactPhone":null,"listingUrl":null,"notes":null,"checklist":{},"createdAt":"2026-09-01T10:00:00Z","updatedAt":"2026-09-22T06:15:00Z","deleted":true,"syncVersion":42,"distanceMeters":null}
]`;

const VISITS_SINCE = `[{"id":"0c6f5a2b-7d4e-4b8a-9c1d-3e2f1a0b9c88","houseId":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","lat":12.97829,"lon":77.64079,"street":"5th Cross","arrivedAt":"2026-09-21T11:05:00.250Z","leftAt":"2026-09-21T11:31:42.750Z","source":"AUTO","updatedAt":"2026-09-21T11:31:43.001122Z","deleted":false,"syncVersion":17}]`;

const PHOTOS_SINCE = `[
  {"id":"a1b2c3d4-0000-4000-8000-000000000001","houseId":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","contentType":"image/jpeg","sizeBytes":184233,"createdAt":"2026-09-21T11:20:00.123456Z","updatedAt":"2026-09-21T11:20:00.123456Z","deleted":false,"syncVersion":7},
  {"id":"a1b2c3d4-0000-4000-8000-000000000002","houseId":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","contentType":"image/jpeg","sizeBytes":0,"createdAt":"2026-09-21T11:21:00Z","updatedAt":"2026-09-22T07:00:00Z","deleted":true,"syncVersion":8}
]`;

/** ApiRateLimitFilter's body; sent with `Retry-After`. */
const RATE_LIMITED = `{"status":429,"detail":"Rate limit exceeded, retry in 2s"}`;

const HOUSE_ID = '5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10';
const VILLA_ID = '9e7c2a44-1b3f-4f0e-8a77-2c5d9e0f1a22';
const PHOTO_NEW = 'a1b2c3d4-0000-4000-8000-000000000001';
const PHOTO_GONE = 'a1b2c3d4-0000-4000-8000-000000000002';

function parse<T>(json: string): T[] {
  return JSON.parse(json) as T[];
}

/** A `HouseApiService` stand-in: records what it was asked for and answers with whatever a test set up. */
class FakeApi {
  houses: () => Observable<HouseDto[]> = () => of([]);
  visits: () => Observable<VisitDto[]> = () => of([]);
  photoChanges: () => Observable<PhotoChangeDto[]> = () => of([]);
  photoBytes: () => Observable<Blob> = () => of(new Blob([new Uint8Array([0xff, 0xd8])], { type: 'image/jpeg' }));

  readonly since = { house: [] as number[], visit: [] as number[], photo: [] as number[] };
  readonly pushedHouses: HouseDto[] = [];
  readonly pushedVisits: VisitDto[] = [];
  readonly deletedPhotos: string[] = [];
  readonly uploadedPhotos: string[] = [];
  readonly fetchedPhotos: string[] = [];

  housesSince(since: number): Observable<HouseDto[]> {
    this.since.house.push(since);
    return this.houses();
  }
  visitsSince(since: number): Observable<VisitDto[]> {
    this.since.visit.push(since);
    return this.visits();
  }
  photoChangesSince(since: number): Observable<PhotoChangeDto[]> {
    this.since.photo.push(since);
    return this.photoChanges();
  }
  photo(id: string): Observable<Blob> {
    this.fetchedPhotos.push(id);
    return this.photoBytes();
  }
  pushHouse(house: HouseDto): Observable<HouseDto> {
    this.pushedHouses.push(house);
    return of(house);
  }
  pushVisit(visit: VisitDto): Observable<VisitDto> {
    this.pushedVisits.push(visit);
    return of(visit);
  }
  deletePhoto(id: string): Observable<unknown> {
    this.deletedPhotos.push(id);
    return of({});
  }
  uploadPhoto(houseId: string, file: Blob, id: string): Observable<{ id: string }> {
    this.uploadedPhotos.push(id);
    return of({ id });
  }
}

/** ApiRateLimitFilter's answer, with the `Retry-After` it sends (seconds; `null` leaves the header out). */
function rateLimited(retryAfter: string | null = '2'): HttpErrorResponse {
  return new HttpErrorResponse({
    status: 429,
    statusText: 'Too Many Requests',
    error: JSON.parse(RATE_LIMITED),
    headers: retryAfter === null ? new HttpHeaders() : new HttpHeaders({ 'Retry-After': retryAfter }),
    url: 'https://api.example.com/api/houses',
  });
}

/**
 * The same 429 for a photo download: the request uses `responseType: 'blob'`, so Angular hands the problem body
 * over as a Blob and `errorMsg` cannot read its `detail`.
 */
function photoRateLimited(retryAfter: string): HttpErrorResponse {
  return new HttpErrorResponse({
    status: 429,
    statusText: 'Too Many Requests',
    error: new Blob([RATE_LIMITED], { type: 'application/json' }),
    headers: new HttpHeaders({ 'Retry-After': retryAfter }),
    url: `https://api.example.com/api/photos/${PHOTO_NEW}`,
  });
}

/** What Angular reports for a connection that never opened (offline, DNS failure, CORS). */
function offline(): HttpErrorResponse {
  return new HttpErrorResponse({ status: 0, statusText: 'Unknown Error', error: new ProgressEvent('error') });
}

/**
 * Lets everything the constructor effect started finish: `checkMigration` awaits two store reads, and a macrotask
 * drains the microtask queue behind them.
 */
async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('SyncService', () => {
  let api: FakeApi;
  let store: LocalStore;
  let sync: SyncService;
  /** What the constructor effect had done before, and after, the one tick below. */
  let migrationBeforeTick: MigrationState;
  let migrationAfterTick: MigrationState;
  /** The configured server; a test switches it with `.set()` and then calls `sync.start()`, as the effect would. */
  let server: WritableSignal<ApiConfig | null>;
  /** The rate-limit waits a run asked for, in ms; recorded instead of slept through. */
  let waits: number[];

  beforeEach(async () => {
    api = new FakeApi();
    server = signal<ApiConfig | null>({ baseUrl: 'https://a.example.com', apiKey: 'key-a' });
    store = new LocalStore();
    await store.ready();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: HouseApiService, useValue: api as unknown as HouseApiService },
        { provide: LocalStore, useValue: store },
        {
          provide: ConfigService,
          useValue: { configured: () => true, config: server.asReadonly() } as unknown as ConfigService,
        },
      ],
    });
    sync = TestBed.inject(SyncService);
    waits = [];
    sync.sleep = (ms: number) => {
      waits.push(ms);
      return Promise.resolve();
    };

    // The constructor `effect()` calls `start()` whenever a server is configured, and this fake ConfigService
    // always says one is. Rather than assume the zoneless scheduler never flushes that effect in the middle of a
    // test — which would interleave a second `start()` → `syncSoon(0)` with the run the test drives and make the
    // `since` and `pushed*` assertions order-dependent in CI only — flush it here, on purpose, and then clear the
    // fake's recorders. Whatever the effect does has therefore already happened before any test body runs.
    migrationBeforeTick = sync.migration();
    TestBed.tick();
    await settle();
    migrationAfterTick = sync.migration();
    for (const list of [api.since.house, api.since.visit, api.since.photo]) list.length = 0;
    api.pushedHouses.length = 0;
    api.pushedVisits.length = 0;
    for (const ids of [api.deletedPhotos, api.uploadedPhotos, api.fetchedPhotos]) ids.length = 0;
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    setOnline(true);
  });

  function recordedServer(): void {
    api.houses = () => of(parse<HouseDto>(HOUSES_SINCE));
    api.visits = () => of(parse<VisitDto>(VISITS_SINCE));
    api.photoChanges = () => of(parse<PhotoChangeDto>(PHOTOS_SINCE));
  }

  // ---- first-run migration ----

  describe('first-run migration', () => {
    /**
     * The effect is the only thing that starts sync in the real app (`start()` is public so this suite can drive
     * the state machine without a tick). Pinned here rather than assumed: `beforeEach` reads `migration()` before
     * and after one `TestBed.tick()`, so this asserts that the tick — not the test — ran the migration check.
     */
    it('runs the migration check from the enabled effect, on a tick and not before', () => {
      expect(migrationBeforeTick).toBe('unknown');
      expect(migrationAfterTick).toBe('offered');
      // 'offered' means it stopped there: nothing was asked of the server behind the user's back.
      expect(api.since.house).toEqual([]);
    });

    it('offers the download rather than pulling behind the user’s back on an empty browser', async () => {
      recordedServer();
      await sync.start();
      expect(sync.migration()).toBe('offered');
      // Nothing was asked of the server: this may be a shared computer or a metered connection.
      expect(api.since.house).toEqual([]);
      expect(sync.lastOutcome()).toBeNull();
    });

    it('blocks an ordinary sync while the question is unanswered', async () => {
      recordedServer();
      await sync.start();
      await sync.syncNow();
      expect(api.since.house).toEqual([]);
      expect(sync.ready).toBe(false);
    });

    it('pulls the whole account when the user says yes, and remembers that it is done', async () => {
      recordedServer();
      await sync.start();
      await sync.downloadToThisBrowser();
      expect(sync.migration()).toBe('done');
      expect(await store.setting(SETTING_KEYS.migration)).toBe('done');
      // since = 0 is what makes this the whole account rather than a delta.
      expect(api.since.house).toEqual([0]);
      expect((await store.liveHouses()).map((h) => h.id)).toEqual([HOUSE_ID]);
    });

    it('puts the question back when the download fails, instead of pretending it worked', async () => {
      api.houses = () => throwError(() => offline());
      await sync.start();
      await sync.downloadToThisBrowser();
      expect(sync.migration()).toBe('offered');
      expect(sync.lastError()).toEqual({ key: 'error.network' });
      expect(await store.setting(SETTING_KEYS.migration)).toBeNull();
    });

    it('does not ask at all when this browser already holds data', async () => {
      await store.saveHouse(house('local-1'), Date.parse('2026-09-01T00:00:00.000Z'));
      recordedServer();
      await sync.start();
      expect(sync.migration()).toBe('done');
    });

    /**
     * "Not now" exists because the download may be unwanted — a shared computer, a metered connection. With every
     * cursor still at 0, *any* sync after it is the whole account and every photo blob, so "not now" has to mean
     * that nothing moves at all. `settle()` lets any timer the answer might have scheduled run before asserting:
     * the previous version passed this test while a `setTimeout(0)` downloaded everything a moment later.
     */
    it('downloads nothing at all on "not now", not even a moment later', async () => {
      recordedServer();
      await sync.start();
      await sync.skipMigration();
      await settle();
      expect(sync.migration()).toBe('skipped');
      expect(await store.setting(SETTING_KEYS.migration)).toBe('skipped');
      expect(api.since.house).toEqual([]);
      expect(api.since.visit).toEqual([]);
      expect(api.since.photo).toEqual([]);
      expect(api.fetchedPhotos).toEqual([]);
      expect(await store.allHouses()).toEqual([]);
      expect(await store.allPhotos()).toEqual([]);
      expect(sync.lastOutcome()).toBeNull();
    });

    it('stays paused after "not now": no debounced, online or periodic sync, in either direction', async () => {
      recordedServer();
      await sync.start();
      await sync.skipMigration();
      // A house saved afterwards is kept in this browser and not sent: sync is paused, not half-on.
      await store.saveHouse(house('local-1'), Date.parse('2026-09-01T00:00:00.000Z'));
      // The debounce after an edit, the `online` event and the 30-minute timer all come through here, unforced.
      sync.syncSoon(0);
      await settle();
      await sync.syncNow();
      expect(api.pushedHouses).toEqual([]);
      expect(api.since.house).toEqual([]);
      expect(sync.ready).toBe(false);
      expect(sync.paused()).toBe(true);
      expect((await store.dirtyHouses()).map((h) => h.id)).toEqual(['local-1']);
    });

    it('remembers "not now" across a restart and does not ask again', async () => {
      recordedServer();
      await sync.start();
      await sync.skipMigration();
      // What the enabled effect does on the next start of the app.
      await sync.start();
      await settle();
      expect(sync.migration()).toBe('skipped');
      expect(api.since.house).toEqual([]);
    });

    it('still downloads the whole account from 0 when the user changes their mind later', async () => {
      recordedServer();
      await sync.start();
      await sync.skipMigration();
      await settle();
      // "Download now" on the Your data page.
      await sync.downloadToThisBrowser();
      expect(api.since.house).toEqual([0]);
      expect(api.since.visit).toEqual([0]);
      expect(api.since.photo).toEqual([0]);
      expect(api.fetchedPhotos).toEqual([PHOTO_NEW]);
      expect((await store.liveHouses()).map((h) => h.id)).toEqual([HOUSE_ID]);
      expect(sync.migration()).toBe('done');
      expect(await store.setting(SETTING_KEYS.migration)).toBe('done');
      expect(sync.paused()).toBe(false);
      expect(sync.ready).toBe(true);
    });

    it('goes back to paused, not to the banner, when that later download fails', async () => {
      api.houses = () => throwError(() => offline());
      await sync.start();
      await sync.skipMigration();
      await sync.downloadToThisBrowser();
      expect(sync.migration()).toBe('skipped');
      expect(sync.lastError()).toEqual({ key: 'error.network' });
      expect(await store.setting(SETTING_KEYS.migration)).toBe('skipped');
    });
  });

  // ---- cursors ----

  describe('cursors', () => {
    it('advances and persists all three, from the highest sync version seen', async () => {
      recordedServer();
      await sync.syncNow(true);
      expect(await store.cursors()).toEqual({ house: 42, visit: 17, photo: 8 });
    });

    it('asks for changes since the stored cursor on the next run', async () => {
      recordedServer();
      await sync.syncNow(true);
      api.houses = () => of([]);
      api.visits = () => of([]);
      api.photoChanges = () => of([]);
      await sync.syncNow(true);
      expect(api.since.house).toEqual([0, 42]);
      expect(api.since.visit).toEqual([0, 17]);
      expect(api.since.photo).toEqual([0, 8]);
    });

    /**
     * The bug this pins: `Math.max(cursor, undefined)` is `NaN`, which is stored as the string "NaN", read back
     * by `numberSetting` as 0, and then re-pulls the whole account on every sync for ever.
     */
    it('never stores NaN when a row arrives with no sync version', async () => {
      const rows = parse<HouseDto>(HOUSES_SINCE);
      delete (rows[1] as Partial<HouseDto>).syncVersion;
      api.houses = () => of(rows);
      await sync.syncNow(true);
      expect(await store.setting(SETTING_KEYS.houseCursor)).toBe('41');
      expect((await store.cursors()).house).toBe(41);
      // The row itself is not stored either: without a version it cannot take part in sync.
      expect(await store.getHouse(VILLA_ID)).toBeUndefined();
      expect(sync.lastOutcome()?.skipped).toBe(1);
    });

    it('leaves the cursor alone when the pull fails', async () => {
      recordedServer();
      await sync.syncNow(true);
      api.houses = () => throwError(() => rateLimited());
      await sync.syncNow(true);
      expect((await store.cursors()).house).toBe(42);
    });
  });

  // ---- what arrives from the server ----

  describe('applying what the server sent', () => {
    it('stores the recorded rows, mapping an unknown status rather than trusting it', async () => {
      recordedServer();
      await sync.syncNow(true);
      const all = await store.allHouses();
      expect(all.map((h) => h.id).sort()).toEqual([HOUSE_ID, VILLA_ID].sort());
      const villa = all.find((h) => h.id === VILLA_ID);
      // "ARCHIVED" is a status this build has never heard of (threat model F-16, NFR-025).
      expect(villa?.status).toBe('NEW');
      expect(villa?.deleted).toBe(true);
      const kept = all.find((h) => h.id === HOUSE_ID);
      expect(kept?.checklist).toEqual({ noise: 2, parking: 4, water: 5 });
      expect(kept?.dirty).toBe(false);
      expect(kept?.contactPhone).toBeNull();
      expect((await store.allVisits()).map((v) => v.source)).toEqual(['AUTO']);
    });

    /**
     * Last-write-wins only decides anything for a row that is **still dirty** when the pull reaches it, and the
     * way that happens in practice is the race `markHouseClean(id, updatedAt)` exists for: the user edits the
     * house again while its push is in flight, so the flag is not cleared and the local copy is newer than the
     * one the server is about to send back. `editWhilePushing` reproduces exactly that.
     *
     * The rule itself, including the tie, is `keepLocalRecord` in sync-rules.ts (a port of SyncRules.kt, with
     * its own spec); these two check that the engine really consults it with the right rows.
     */
    function editWhilePushing(label: string, at: string): void {
      api.pushHouse = (pushed: HouseDto) =>
        defer(() => from(store.saveHouse({ ...pushed, label }, Date.parse(at)).then(() => pushed)));
    }

    it('keeps a local edit made while the push was in flight', async () => {
      await store.saveHouse(house(HOUSE_ID, { label: 'Mine' }), Date.parse('2026-09-21T00:00:00.000Z'));
      recordedServer();
      // The server's row says 2026-09-21T17:02:44.901234Z; this edit is later.
      editWhilePushing('Edited while pushing', '2026-09-22T00:00:00.000Z');
      await sync.syncNow(true);
      const kept = await store.getHouse(HOUSE_ID);
      expect(kept?.label).toBe('Edited while pushing');
      expect(kept?.dirty).toBe(true);
    });

    it('lets the server win on a tie', async () => {
      await store.saveHouse(house(HOUSE_ID, { label: 'Mine' }), Date.parse('2026-09-21T00:00:00.000Z'));
      recordedServer();
      // Exactly the instant the server's row carries: "last edit wins" needs a strictly later local edit
      // (SyncRules.kt, "tie goes to the server"), so this one is overwritten.
      editWhilePushing('Edited while pushing', '2026-09-21T17:02:44.901Z');
      await sync.syncNow(true);
      expect((await store.getHouse(HOUSE_ID))?.label).toBe('2BHK near Indiranagar metro');
    });

    it('overwrites a clean local row with whatever the server has', async () => {
      await store.putHouseFromServer({ ...house(HOUSE_ID, { label: 'Old server copy' }), updatedAt: '2026-01-01T00:00:00.000Z' });
      recordedServer();
      await sync.syncNow(true);
      expect((await store.getHouse(HOUSE_ID))?.label).toBe('2BHK near Indiranagar metro');
    });

    it('pushes local edits before it pulls, and clears the dirty flag', async () => {
      await store.saveHouse(house('local-1'), Date.parse('2026-09-01T00:00:00.000Z'));
      recordedServer();
      await sync.syncNow(true);
      expect(api.pushedHouses.map((h) => h.id)).toEqual(['local-1']);
      expect(await store.dirtyHouses()).toEqual([]);
      expect(sync.lastOutcome()?.pushed).toBe(1);
    });

    it('sends photo deletes before uploads, so a removed photo is not re-uploaded', async () => {
      await store.saveHouse(house('local-1'), Date.parse('2026-09-01T00:00:00.000Z'));
      await store.addPhoto('local-1', new Blob([new Uint8Array([1])], { type: 'image/jpeg' }), 'p-keep');
      await store.addPhoto('local-1', new Blob([new Uint8Array([2])], { type: 'image/jpeg' }), 'p-gone');
      const gone = await store.getPhoto('p-gone');
      await store.putPhotoRecord({ ...gone!, uploaded: true });
      await store.deletePhoto('p-gone');
      await sync.syncNow(true);
      expect(api.deletedPhotos).toEqual(['p-gone']);
      expect(api.uploadedPhotos).toEqual(['p-keep']);
      expect(await store.getPhoto('p-gone')).toBeUndefined();
    });

    it('removes a photo the server says is gone, and fetches one it says is new', async () => {
      recordedServer();
      // Seed the tombstoned photo so there is something for the delete to remove.
      await store.putPhotoRecord({
        id: PHOTO_GONE,
        houseId: HOUSE_ID,
        blob: new Blob([new Uint8Array([9])], { type: 'image/jpeg' }),
        contentType: 'image/jpeg',
        sizeBytes: 1,
        createdAt: '2026-09-21T11:21:00.000Z',
        updatedAt: '2026-09-21T11:21:00.000Z',
        deleted: false,
        syncVersion: 1,
        uploaded: true,
      });
      await sync.syncNow(true);
      expect(await store.getPhoto(PHOTO_GONE)).toBeUndefined();
      const fetched = await store.getPhoto(PHOTO_NEW);
      expect(api.fetchedPhotos).toEqual([PHOTO_NEW]);
      expect(fetched?.uploaded).toBe(true);
      expect(fetched?.syncVersion).toBe(7);
    });

    it('does not fetch photo bytes for a house this browser does not have', async () => {
      api.photoChanges = () => of(parse<PhotoChangeDto>(PHOTOS_SINCE));
      await sync.syncNow(true);
      expect(api.fetchedPhotos).toEqual([]);
      expect(await store.getPhoto(PHOTO_NEW)).toBeUndefined();
      // The cursor still moves: those changes have been considered and need not be sent again.
      expect((await store.cursors()).photo).toBe(8);
    });

    it('counts an empty response as nothing pulled, and still finishes the migration', async () => {
      await sync.start();
      await sync.downloadToThisBrowser();
      expect(sync.lastOutcome()).toMatchObject({ pushed: 0, pulled: 0, skipped: 0 });
      expect(sync.lastError()).toBeNull();
      // The download did run and the server had nothing; asking again would be asking twice.
      expect(sync.migration()).toBe('done');
      expect(await store.cursors()).toEqual({ house: 0, visit: 0, photo: 0 });
    });
  });

  // ---- rows that cannot be trusted ----

  describe('rows that cannot be trusted', () => {
    /**
     * `String(undefined)` is the literal id "undefined": the row would become a real house on the map, and two
     * such rows would collide on that one key. The server refuses a blank id too (`BackupValidation.checkData`).
     */
    it('skips a row with no id instead of inventing one, and says how many it dropped', async () => {
      const rows = parse<HouseDto>(HOUSES_SINCE);
      delete (rows[0] as Partial<HouseDto>).id;
      rows[1].id = '   ';
      api.houses = () => of(rows);
      await sync.syncNow(true);
      expect(await store.allHouses()).toEqual([]);
      expect(sync.lastOutcome()?.skipped).toBe(2);
      expect(sync.lastSkipped()).toBe(2);
      expect(sync.lastError()).toBeNull();
      // The cursor still moves: both rows carried a usable syncVersion, so they have been considered.
      expect((await store.cursors()).house).toBe(42);
    });

    /**
     * The consequence of that: a row the app can never store must not wedge the cursor. Holding it back would
     * re-download the same page every 30 minutes for ever and leave "2 rows skipped" on screen permanently, with
     * nothing the user could do about it. Only an unusable *version* keeps the cursor where it is — which is what
     * the NaN guard above is for.
     */
    it('does not re-download a permanently unusable page on the next sync', async () => {
      const rows = parse<HouseDto>(HOUSES_SINCE);
      delete (rows[0] as Partial<HouseDto>).id;
      rows[1].id = '   ';
      // A real server answers `since` with the rows above it, so an advanced cursor means it does not resend them.
      api.houses = () => {
        const since = api.since.house[api.since.house.length - 1];
        return of(rows.filter((row) => row.syncVersion > since));
      };
      await sync.syncNow(true);
      expect(sync.lastOutcome()?.skipped).toBe(2);

      await sync.syncNow(true);
      expect(api.since.house).toEqual([0, 42]);
      expect(sync.lastOutcome()?.skipped).toBe(0);
      expect(sync.lastSkipped()).toBe(0);
    });

    it('skips a visit and a photo change with no usable id', async () => {
      const visits = parse<VisitDto>(VISITS_SINCE);
      delete (visits[0] as Partial<VisitDto>).id;
      const photos = parse<PhotoChangeDto>(PHOTOS_SINCE);
      delete (photos[0] as Partial<PhotoChangeDto>).houseId;
      api.visits = () => of(visits);
      api.photoChanges = () => of(photos);
      await sync.syncNow(true);
      expect(await store.allVisits()).toEqual([]);
      expect(sync.lastOutcome()?.skipped).toBe(2);
    });

    /**
     * Angular turns a non-JSON 200 (a captive portal's sign-in page) into an HttpErrorResponse on its own; the
     * case left to guard is well-formed JSON of the wrong shape, which used to throw a bare TypeError.
     */
    it('fails with a translated reason when a list endpoint answers with something that is not a list', async () => {
      api.houses = () => of({ error: 'nope' } as unknown as HouseDto[]);
      await sync.syncNow(true);
      expect(sync.lastError()).toEqual({ key: 'error.server', params: undefined });
      expect(sync.lastOutcome()).toBeNull();
      expect(await store.allHouses()).toEqual([]);
    });
  });

  // ---- failure paths ----

  describe('failure paths', () => {
    it('waits out a short 429, then reports it translated and never claims a successful run', async () => {
      api.houses = () => throwError(() => rateLimited());
      await sync.syncNow(true);
      // Retry-After: 2 honoured between attempts, then given up after the last one (Android RetryPolicy parity).
      expect(api.since.house).toEqual(Array(RATE_LIMIT_ATTEMPTS).fill(0));
      expect(waits).toEqual(Array(RATE_LIMIT_ATTEMPTS - 1).fill(2000));
      expect(sync.lastOutcome()).toBeNull();
      // Translated, with the server's wait; not the server's English detail.
      expect(sync.lastError()).toEqual({ key: 'error.rateLimited', params: { s: 2 } });
      expect(sync.running()).toBe(false);
    });

    /**
     * The coordinator's case: a first-run download of more than about 300 photos exhausts the per-address burst
     * (600 a minute, burst 300), and one photo GET is refused with a short Retry-After. The pull waits and carries
     * on instead of failing the whole download.
     */
    it('carries on with the photo download after a short 429', async () => {
      recordedServer();
      let calls = 0;
      api.photoBytes = () =>
        ++calls === 1
          ? throwError(() => photoRateLimited('1'))
          : of(new Blob([new Uint8Array([0xff, 0xd8])], { type: 'image/jpeg' }));
      await sync.syncNow(true);
      expect(sync.lastError()).toBeNull();
      expect(waits).toEqual([1000]);
      expect(api.fetchedPhotos).toEqual([PHOTO_NEW, PHOTO_NEW]);
      expect(await store.getPhoto(PHOTO_NEW)).toBeTruthy();
      expect((await store.cursors()).photo).toBe(8);
      expect(sync.lastOutcome()).not.toBeNull();
    });

    it('does not sit through a long Retry-After: reports it and keeps the photo position', async () => {
      recordedServer();
      api.photoBytes = () => throwError(() => photoRateLimited('300'));
      await sync.syncNow(true);
      expect(waits).toEqual([]);
      expect(api.fetchedPhotos).toEqual([PHOTO_NEW]);
      // In the app language, even though the Blob body cannot be read (no raw "Http failure response ... 429").
      expect(sync.lastError()).toEqual({ key: 'error.rateLimited', params: { s: 300 } });
      expect((await store.cursors()).house).toBe(42);
      expect((await store.cursors()).visit).toBe(17);
      expect((await store.cursors()).photo).toBe(0);
      expect(sync.lastOutcome()).toBeNull();
    });

    it('does not retry anything but 429', async () => {
      api.houses = () => throwError(() => offline());
      await sync.syncNow(true);
      expect(api.since.house).toEqual([0]);
      expect(waits).toEqual([]);
    });

    it('reports a connection that never opened', async () => {
      api.visits = () => throwError(() => offline());
      api.houses = () => of(parse<HouseDto>(HOUSES_SINCE));
      await sync.syncNow(true);
      expect(sync.lastError()).toEqual({ key: 'error.network' });
      // Houses were applied before the visits call failed; their cursor is kept so they are not re-pulled.
      expect((await store.cursors()).house).toBe(42);
      expect((await store.cursors()).visit).toBe(0);
    });

    it('clears the previous error once a run succeeds', async () => {
      api.houses = () => throwError(() => offline());
      await sync.syncNow(true);
      expect(sync.lastError()).not.toBeNull();
      api.houses = () => of([]);
      await sync.syncNow(true);
      expect(sync.lastError()).toBeNull();
      expect(sync.lastOutcome()).not.toBeNull();
    });

    it('gives the same failure twice in a row a new run, so "Sync now" failing again is read again (R9)', async () => {
      api.houses = () => throwError(() => offline());
      await sync.syncNow(true);
      const first = sync.lastFailure();
      await sync.syncNow(true);
      const second = sync.lastFailure();
      expect(first?.value).toEqual({ key: 'error.network' });
      expect(second?.value).toEqual({ key: 'error.network' });
      expect(second?.run).not.toBe(first?.run);
      api.houses = () => of([]);
      await sync.syncNow(true);
      expect(sync.lastFailure()).toBeNull();
    });

    it('stops the photo phase on the first "storage full" and says so in the app language', async () => {
      recordedServer();
      const put = vi
        .spyOn(store, 'putPhotoRecord')
        .mockRejectedValue(new DOMException('The quota has been exceeded.', 'QuotaExceededError'));
      await sync.syncNow(true);
      // Translated advice, not the browser's English DOMException text through error.detail.
      expect(sync.lastError()).toEqual({ key: 'error.storageFull' });
      expect(put).toHaveBeenCalledTimes(1);
      expect(api.fetchedPhotos).toEqual([PHOTO_NEW]);
      // Houses and visits were stored before the photos; the photo position is kept below the refused photo.
      expect((await store.cursors()).house).toBe(42);
      expect((await store.cursors()).visit).toBe(17);
      expect((await store.cursors()).photo).toBe(0);
      expect(sync.lastOutcome()).toBeNull();
      put.mockRestore();
    });

    it('never runs two syncs over each other', async () => {
      recordedServer();
      await Promise.all([sync.syncNow(true), sync.syncNow(true)]);
      expect(api.since.house).toEqual([0, 42]);
    });
  });

  // ---- a different server (Android parity: Settings.saveServer) ----

  describe('switching servers', () => {
    it('starts again from 0 on a different server instead of pulling "since" the old one’s cursors', async () => {
      recordedServer();
      await sync.start();
      await sync.downloadToThisBrowser();
      expect(await store.cursors()).toEqual({ house: 42, visit: 17, photo: 8 });

      // Disconnect-and-connect elsewhere, or a changed address: the enabled effect calls start() again.
      server.set({ baseUrl: 'https://b.example.com/', apiKey: 'key-b' });
      for (const list of [api.since.house, api.since.visit, api.since.photo]) list.length = 0;
      await sync.start();
      await settle();
      await sync.syncNow();
      expect(api.since.house[0]).toBe(0);
      expect(api.since.visit[0]).toBe(0);
      expect(api.since.photo[0]).toBe(0);
      expect(await store.setting(SETTING_KEYS.syncServer)).toBe('https://b.example.com');
    });

    it('keeps the cursors when the same server is started again', async () => {
      recordedServer();
      await sync.start();
      await sync.downloadToThisBrowser();
      for (const list of [api.since.house, api.since.visit, api.since.photo]) list.length = 0;
      await sync.start();
      await settle();
      expect(await store.cursors()).toEqual({ house: 42, visit: 17, photo: 8 });
      expect(api.since.house.every((since) => since === 42)).toBe(true);
    });

    it('asks the first-run question again for the new server when this browser is empty', async () => {
      await sync.start();
      await sync.skipMigration();
      server.set({ baseUrl: 'https://b.example.com', apiKey: 'key-b' });
      await sync.start();
      expect(sync.migration()).toBe('offered');
    });
  });

  // ---- cancel ("Remove all data") and stop (the first-run banner) ----

  describe('cancel and stop', () => {
    it('writes nothing more once cancelled, even when the server answers afterwards', async () => {
      const houses = new Subject<HouseDto[]>();
      api.houses = () => houses;
      const run = sync.syncNow(true);
      await settle();
      expect(sync.running()).toBe(true);

      // "Remove all data": cancel, then clear, then the slow answer finally arrives.
      sync.cancel();
      await store.clearEverything();
      houses.next(parse<HouseDto>(HOUSES_SINCE));
      houses.complete();
      await settle();

      expect(await store.allHouses()).toEqual([]);
      expect(await store.cursors()).toEqual({ house: 0, visit: 0, photo: 0 });
      expect(sync.running()).toBe(false);
      // Cancelling is not a failure: nothing to report.
      expect(sync.lastError()).toBeNull();
      expect(sync.lastOutcome()).toBeNull();
      void run;
    });

    it('does not let a cancelled run hold up the next one', async () => {
      api.houses = () => new Subject<HouseDto[]>(); // never answers
      void sync.syncNow(true);
      await settle();
      sync.cancel();
      recordedServer();
      await sync.syncNow(true);
      expect(sync.lastOutcome()?.pulled).toBeGreaterThan(0);
    });

    it('stops the download between rows, keeps what arrived, pauses sync and resumes from there', async () => {
      recordedServer();
      // "Stop" is pressed while the visits are being fetched.
      api.visits = () => {
        sync.stopDownload();
        return of(parse<VisitDto>(VISITS_SINCE));
      };
      await sync.start();
      await sync.downloadToThisBrowser();

      expect(sync.migration()).toBe('skipped');
      expect(await store.setting(SETTING_KEYS.migration)).toBe('skipped');
      expect(sync.lastError()).toBeNull();
      expect((await store.liveHouses()).map((h) => h.id)).toEqual([HOUSE_ID]);
      expect(await store.allVisits()).toEqual([]);
      expect(await store.cursors()).toEqual({ house: 42, visit: 0, photo: 0 });

      // "Download now" on Your data carries on from the stored cursors.
      api.visits = () => of(parse<VisitDto>(VISITS_SINCE));
      for (const list of [api.since.house, api.since.visit, api.since.photo]) list.length = 0;
      await sync.downloadToThisBrowser();
      expect(api.since.house).toEqual([42]);
      expect(api.since.visit).toEqual([0]);
      expect(sync.migration()).toBe('done');
      expect((await store.allVisits()).length).toBe(1);
    });

    it('resumes below every row it did not apply, whatever order they came in', () => {
      expect(resumeCursor(41, [{ syncVersion: 42 }, { syncVersion: 43 }])).toBe(41);
      // A row with a lower version still to come holds the cursor below it.
      expect(resumeCursor(50, [{ syncVersion: 45 }, { syncVersion: 60 }])).toBe(44);
      expect(resumeCursor(7, [])).toBe(7);
      expect(resumeCursor(7, [{ syncVersion: 'x' }, null])).toBe(7);
    });

    it('counts what the server has not received yet', async () => {
      await store.saveHouse(house('local-1'), Date.parse('2026-09-01T00:00:00.000Z'));
      await store.saveHouse(house('local-2'), Date.parse('2026-09-01T00:00:00.000Z'));
      await store.addPhoto('local-1', new Blob([new Uint8Array([1])], { type: 'image/jpeg' }), PHOTO_NEW);
      expect(await sync.pendingCount()).toBe(3);
    });
  });

  // ---- offline is a normal state ----

  describe('offline', () => {
    it('skips unforced runs while offline instead of reporting a failure', async () => {
      await store.saveHouse(house('local-1'), Date.parse('2026-09-01T00:00:00.000Z'));
      await sync.start();
      expect(sync.migration()).toBe('done');
      api.houses = () => throwError(() => offline());
      setOnline(false);
      await sync.syncNow();
      expect(api.pushedHouses).toEqual([]);
      expect(sync.lastError()).toBeNull();
    });

    // Both branches of errorMsg's status-0 rule (core/format.ts), reached through a real forced run: while the
    // browser says it is offline the user is told so (not the CORS advice); while it says online, a request that
    // got no response is still the connection/CORS message.
    it('reports a failure the user asked for, as such', async () => {
      api.houses = () => throwError(() => offline());
      setOnline(false);
      await sync.syncNow(true);
      expect(sync.lastError()).toEqual({ key: 'error.offline' });
      expect(sync.lastErrorForced()).toBe(true);
    });

    it('keeps the connection message for a forced run that got no response while the browser says online', async () => {
      api.houses = () => throwError(() => offline());
      setOnline(true);
      await sync.syncNow(true);
      expect(sync.lastError()).toEqual({ key: 'error.network' });
      expect(sync.lastErrorForced()).toBe(true);
    });

    it('marks a background failure as not user-initiated', async () => {
      await store.saveHouse(house('local-1'), Date.parse('2026-09-01T00:00:00.000Z'));
      await sync.start();
      api.houses = () => throwError(() => rateLimited());
      await sync.syncNow();
      expect(sync.lastError()).not.toBeNull();
      expect(sync.lastErrorForced()).toBe(false);
    });
  });

  describe('rateLimitWaitMs', () => {
    it('uses the server’s Retry-After, in seconds', () => {
      expect(rateLimitWaitMs(rateLimited('2'), 1)).toBe(2000);
      expect(rateLimitWaitMs(rateLimited('0'), 3)).toBe(0);
    });

    it('doubles from one second without one, capped like Android', () => {
      expect([1, 2, 3, 4, 5, 6].map((a) => rateLimitWaitMs(rateLimited(null), a))).toEqual([
        1000, 2000, 4000, 8000, MAX_RATE_LIMIT_WAIT_MS, MAX_RATE_LIMIT_WAIT_MS,
      ]);
    });

    it('is null for anything that is not a 429', () => {
      expect(rateLimitWaitMs(offline(), 1)).toBeNull();
      expect(rateLimitWaitMs(new HttpErrorResponse({ status: 503 }), 1)).toBeNull();
      expect(rateLimitWaitMs({ status: 429 }, 1)).toBeNull();
      expect(rateLimitWaitMs(new Error('boom'), 1)).toBeNull();
    });
  });

  describe('wireVersion', () => {
    /**
     * `[]` and `[5]` are the cases a `Number(value)` implementation gets wrong: `Number([])` is `0` and
     * `Number([5])` is `5`, so an array in `syncVersion` would move the cursor and store the row as version 0 or
     * 5. Only a finite number, or a string that is one once trimmed, is accepted.
     */
    it('accepts a finite number and refuses everything that would poison a cursor', () => {
      expect(wireVersion(41)).toBe(41);
      expect(wireVersion(0)).toBe(0);
      expect(wireVersion(-3)).toBe(-3);
      // A quoted number is tolerated: a proxy or a hand-written fixture may send one, and "12" means 12.
      expect(wireVersion('12')).toBe(12);
      expect(wireVersion(' 12 ')).toBe(12);
      for (const bad of [
        undefined,
        null,
        '',
        '  ',
        'abc',
        Number.NaN,
        Number.POSITIVE_INFINITY,
        true,
        false,
        {},
        [],
        [5],
      ]) {
        expect(wireVersion(bad), String(bad)).toBeNull();
      }
    });
  });
});

/** jsdom's navigator.onLine is always true; an own property on the instance shadows it, and removing it restores. */
function setOnline(online: boolean): void {
  if (online) {
    delete (navigator as { onLine?: boolean }).onLine;
  } else {
    Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => false });
  }
}

function house(id: string, over: Partial<HouseDto> = {}): HouseDto {
  return {
    id,
    label: `House ${id}`,
    lat: 13,
    lon: 80,
    status: 'NEW',
    checklist: {},
    deleted: false,
    syncVersion: 0,
    ...over,
  };
}
