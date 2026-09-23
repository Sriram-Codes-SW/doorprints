import { Injectable, signal } from '@angular/core';
import { uuid } from '../core/models';
import type { HouseDto, StatsDto, VisitDto } from '../core/models';
import { openLocalDb } from './local-db';
import type { LocalDb, OpenedDb, StorageProblem } from './local-db';
import { SETTING_KEYS, houseFromDto, isoNow, millis, visitFromDto } from './records';
import type { HouseRecord, PhotoRecord, SettingRecord, VisitRecord } from './records';

/** Quiet period after the last write before {@link LocalStore.settled} follows `revision`. */
export const SETTLE_MS = 300;
/** During a long run of writes (a first-run download), views still refresh at least this often. */
export const SETTLE_MAX_MS = 2000;

/** Same ceiling as the Android app and the server (shared MAX_PHOTOS_PER_HOUSE). */
export const MAX_PHOTOS_PER_HOUSE = 20;

export type AddPhotoResult = { ok: true; id: string } | { ok: false; reason: 'limit' };

/**
 * The primary store of the web app (S4-01, docs/11 §5.10).
 *
 * Every read and write goes here, whether or not a server is configured; nothing on the screen waits for the
 * network. `SyncService` later exchanges the same rows with a server when the user has set one up.
 *
 * Ordering is fixed everywhere (createdAt, then id) so exports are byte-for-byte reproducible (docs/11 §5.2).
 */
@Injectable({ providedIn: 'root' })
export class LocalStore {
  /** Non-null when the browser refused IndexedDB; the app then runs from memory for this page only. */
  readonly storageProblem = signal<StorageProblem | null>(null);
  /** Bumped after every write, so views and the sync engine can react. */
  readonly revision = signal(0);
  /**
   * `revision`, coalesced for **views**: it follows `revision` once no write has happened for {@link SETTLE_MS},
   * and at least every {@link SETTLE_MAX_MS} while writes keep coming.
   *
   * A sync pull writes one row per IndexedDB transaction, and every one of them bumps `revision`. A screen that
   * re-read on each bump re-rendered its list once per downloaded row (focus lost, live regions re-announced) and,
   * on Your data, rebuilt the whole export bundle per row. Screens read this instead; the sync engine and tests
   * that need every write keep `revision`.
   */
  readonly settled = signal(0);

  private opened: Promise<LocalDb> | null = null;
  private settleTimer: ReturnType<typeof setTimeout> | undefined;
  private settleSince = 0;

  private db(): Promise<LocalDb> {
    this.opened ??= openLocalDb().then((result: OpenedDb) => {
      this.storageProblem.set(result.problem);
      return result.db;
    });
    return this.opened;
  }

  /** Waits for the store to be ready; resolves even when IndexedDB was refused. */
  async ready(): Promise<void> {
    await this.db();
  }

  private touch(): void {
    this.revision.update((n) => n + 1);
    this.scheduleSettle();
  }

  /** Trailing debounce with a maximum wait: see {@link settled}. */
  private scheduleSettle(): void {
    const now = Date.now();
    if (this.settleTimer === undefined) {
      this.settleSince = now;
    } else {
      clearTimeout(this.settleTimer);
    }
    const wait = Math.max(0, Math.min(SETTLE_MS, this.settleSince + SETTLE_MAX_MS - now));
    this.settleTimer = setTimeout(() => {
      this.settleTimer = undefined;
      this.settled.set(this.revision());
    }, wait);
  }

  // ---- Houses ----

  /** Every house, tombstones included, in export order. */
  async allHouses(): Promise<HouseRecord[]> {
    const db = await this.db();
    return sortByCreated(await db.getAll<HouseRecord>('houses'));
  }

  async liveHouses(): Promise<HouseRecord[]> {
    return (await this.allHouses()).filter((h) => !h.deleted);
  }

  async getHouse(id: string): Promise<HouseRecord | undefined> {
    const db = await this.db();
    const house = await db.get<HouseRecord>('houses', id);
    return house && !house.deleted ? house : undefined;
  }

  /** Saves a local edit: stamps `updatedAt` and marks the row dirty so the next sync pushes it. */
  async saveHouse(house: HouseDto, now: number = Date.now()): Promise<HouseRecord> {
    const db = await this.db();
    const existing = await db.get<HouseRecord>('houses', house.id);
    const record: HouseRecord = {
      ...houseFromDto(house, true),
      createdAt: existing?.createdAt ?? house.createdAt ?? isoNow(now),
      updatedAt: isoNow(now),
      syncVersion: existing?.syncVersion ?? house.syncVersion ?? 0,
    };
    await db.put('houses', record);
    this.touch();
    return record;
  }

  /** Marks a house deleted (a tombstone, so other devices learn about it) and removes its photos here. */
  async deleteHouse(id: string, now: number = Date.now()): Promise<void> {
    const db = await this.db();
    const existing = await db.get<HouseRecord>('houses', id);
    if (!existing) return;
    await db.put('houses', { ...existing, deleted: true, dirty: true, updatedAt: isoNow(now) });
    for (const photo of await this.photosOf(id)) await this.deletePhoto(photo.id, now);
    this.touch();
  }

  /** Applies a row that came from the server (sync or import). The caller has already applied the LWW rule. */
  async putHouseFromServer(dto: HouseDto): Promise<void> {
    const db = await this.db();
    await db.put('houses', houseFromDto(dto, false));
    this.touch();
  }

  async markHouseClean(id: string, pushedUpdatedAt: string | null | undefined): Promise<void> {
    const db = await this.db();
    const existing = await db.get<HouseRecord>('houses', id);
    // Only clear the flag when nothing changed while the push was in flight (Android: markClean(id, updatedAt)).
    if (existing && millis(existing.updatedAt) === millis(pushedUpdatedAt)) {
      await db.put('houses', { ...existing, dirty: false });
    }
  }

  async dirtyHouses(): Promise<HouseRecord[]> {
    return (await this.allHouses()).filter((h) => h.dirty);
  }

  // ---- Visits ----

  async allVisits(): Promise<VisitRecord[]> {
    const db = await this.db();
    const visits = await db.getAll<VisitRecord>('visits');
    return [...visits].sort((a, b) => cmp(a.arrivedAt, b.arrivedAt) || cmp(a.id, b.id));
  }

  async visitsOf(houseId: string): Promise<VisitRecord[]> {
    return (await this.allVisits()).filter((v) => v.houseId === houseId && !v.deleted);
  }

  async saveVisit(visit: VisitDto, now: number = Date.now()): Promise<VisitRecord> {
    const db = await this.db();
    const existing = await db.get<VisitRecord>('visits', visit.id);
    const record: VisitRecord = {
      ...visitFromDto(visit, true),
      updatedAt: isoNow(now),
      syncVersion: existing?.syncVersion ?? visit.syncVersion ?? 0,
    };
    await db.put('visits', record);
    this.touch();
    return record;
  }

  async deleteVisit(id: string, now: number = Date.now()): Promise<void> {
    const db = await this.db();
    const existing = await db.get<VisitRecord>('visits', id);
    if (!existing) return;
    await db.put('visits', { ...existing, deleted: true, dirty: true, updatedAt: isoNow(now) });
    this.touch();
  }

  async putVisitFromServer(dto: VisitDto): Promise<void> {
    const db = await this.db();
    await db.put('visits', visitFromDto(dto, false));
    this.touch();
  }

  async markVisitClean(id: string, pushedUpdatedAt: string | null | undefined): Promise<void> {
    const db = await this.db();
    const existing = await db.get<VisitRecord>('visits', id);
    if (existing && millis(existing.updatedAt) === millis(pushedUpdatedAt)) {
      await db.put('visits', { ...existing, dirty: false });
    }
  }

  async dirtyVisits(): Promise<VisitRecord[]> {
    return (await this.allVisits()).filter((v) => v.dirty);
  }

  // ---- Photos ----

  async allPhotos(): Promise<PhotoRecord[]> {
    const db = await this.db();
    return sortByCreated(await db.getAll<PhotoRecord>('photos'));
  }

  async photosOf(houseId: string): Promise<PhotoRecord[]> {
    return (await this.allPhotos()).filter((p) => p.houseId === houseId && !p.deleted);
  }

  async getPhoto(id: string): Promise<PhotoRecord | undefined> {
    const db = await this.db();
    return db.get<PhotoRecord>('photos', id);
  }

  /** Stores photo bytes for a house. The blob is already resized and re-encoded by the caller. */
  async addPhoto(houseId: string, blob: Blob, id: string = uuid(), now: number = Date.now()): Promise<AddPhotoResult> {
    if ((await this.photosOf(houseId)).length >= MAX_PHOTOS_PER_HOUSE) return { ok: false, reason: 'limit' };
    const db = await this.db();
    const record: PhotoRecord = {
      id,
      houseId,
      blob,
      contentType: blob.type || 'image/jpeg',
      sizeBytes: blob.size,
      createdAt: isoNow(now),
      updatedAt: isoNow(now),
      deleted: false,
      syncVersion: 0,
      uploaded: false,
    };
    await db.put('photos', record);
    this.touch();
    return { ok: true, id };
  }

  /**
   * Drops the bytes now. A photo the server already has keeps a tombstone so the delete is pushed on the next
   * sync (threat model F-15); one that never left this browser is removed outright.
   */
  async deletePhoto(id: string, now: number = Date.now()): Promise<void> {
    const db = await this.db();
    const existing = await db.get<PhotoRecord>('photos', id);
    if (!existing) return;
    if (existing.uploaded) {
      await db.put('photos', { ...existing, blob: null, deleted: true, updatedAt: isoNow(now) });
    } else {
      await db.delete('photos', id);
    }
    this.touch();
  }

  /** Forgets a photo completely (used when the server confirms a delete, or a tombstone arrives from elsewhere). */
  async forgetPhoto(id: string): Promise<void> {
    const db = await this.db();
    await db.delete('photos', id);
    this.touch();
  }

  async putPhotoRecord(record: PhotoRecord): Promise<void> {
    const db = await this.db();
    await db.put('photos', record);
    this.touch();
  }

  // ---- Settings ----

  async setting(key: string): Promise<string | null> {
    const db = await this.db();
    return (await db.get<SettingRecord>('settings', key))?.value ?? null;
  }

  async setSetting(key: string, value: string): Promise<void> {
    const db = await this.db();
    await db.put<SettingRecord>('settings', { key, value });
  }

  async removeSetting(key: string): Promise<void> {
    const db = await this.db();
    await db.delete('settings', key);
  }

  async numberSetting(key: string): Promise<number> {
    const raw = await this.setting(key);
    const n = raw === null ? Number.NaN : Number(raw);
    return Number.isFinite(n) ? n : 0;
  }

  async cursors(): Promise<{ house: number; visit: number; photo: number }> {
    return {
      house: await this.numberSetting(SETTING_KEYS.houseCursor),
      visit: await this.numberSetting(SETTING_KEYS.visitCursor),
      photo: await this.numberSetting(SETTING_KEYS.photoCursor),
    };
  }

  // ---- Derived ----

  /** The same five numbers `GET /api/stats` returns, counted from this browser's copy. */
  async stats(): Promise<StatsDto> {
    const houses = (await this.allHouses()).filter((h) => !h.deleted);
    const visits = (await this.allVisits()).filter((v) => !v.deleted);
    const streets = new Set(houses.map((h) => (h.street ?? '').trim()).filter((s) => s !== ''));
    return {
      houses: houses.length,
      shortlisted: houses.filter((h) => h.status === 'SHORTLISTED').length,
      rejected: houses.filter((h) => h.status === 'REJECTED').length,
      visits: visits.length,
      streets: streets.size,
    };
  }

  /** True when this browser holds nothing yet: used to offer the first-run download from a configured server. */
  async isEmpty(): Promise<boolean> {
    return (await this.allHouses()).length === 0 && (await this.allVisits()).length === 0;
  }

  /**
   * "Remove all Doorprints data from this browser" (docs/11 §5.10, shared computers).
   *
   * Clears **both** places this app keeps data: the IndexedDB object stores, and Cache Storage — the service
   * worker's copy of the app shell and of the build it precached. Clearing only the first leaves the
   * offline copy of the app behind, which is not what the button promises. Cache Storage is origin-wide, so only
   * **our own** caches are removed; see {@link CACHE_NAME_PREFIX}.
   *
   * Two things it deliberately does **not** touch, because they are settings rather than data, and both are
   * spelled out in the confirm dialog so the promise matches the behaviour:
   *  * the chosen language (`house-hunt.lang`), which is a preference, not a record of anything;
   *  * the saved server address and API key — that is `ConfigService.clear()`'s job, and the "Your data" screen
   *    calls it alongside this so the sensitive half is gone too.
   */
  async clearEverything(): Promise<void> {
    const db = await this.db();
    await db.clear();
    await clearCacheStorage();
    this.touch();
  }
}

/**
 * Prefix of every cache this app owns.
 *
 * **Paired with `CACHE_PREFIX` in `public/sw.js`**, which names its cache `${CACHE_NAME_PREFIX}<build id><base path>`
 * (one cache per build, the previous build's dropped on `activate`; builds before the stamp used `v1`); the
 * two strings must stay equal. A service worker served from `public/` is not part of the bundle and cannot import
 * this constant, so it is written out in both places and each comment names the other.
 */
export const CACHE_NAME_PREFIX = 'doorprints-shell-';

/**
 * Empties **this app's** caches. Not a blanket `caches.delete` over `caches.keys()`: Cache Storage is scoped to the
 * *origin*, not to the app, so on a shared host — a GitHub Pages project site, `https://<owner>.github.io/<repo>/`,
 * where the origin carries every project site of that owner (the reason the live site has its own origin,
 * https://doorprints.web.app on Firebase Hosting) — deleting everything the origin holds would destroy an unrelated
 * app's offline copy. A button that says "remove my Doorprints data from this browser" must not take a neighbour's app down with
 * it, which is the same rule `sw.js` applies in its fetch handler and in `activate`.
 *
 * It does delete our caches from *every* base path on this origin, not just this deployment's: IndexedDB is itself
 * origin-scoped, so `db.clear()` above has already emptied the data both deployments share, and leaving the other
 * one's stale app shell behind would be the odd half-measure.
 *
 * Guarded and swallowed: Cache Storage is missing in jsdom and in some private modes, and a browser may refuse a
 * delete — none of which should stop the IndexedDB half of "remove everything" from having worked.
 */
async function clearCacheStorage(): Promise<void> {
  try {
    if (typeof caches === 'undefined') return;
    for (const name of await caches.keys()) {
      if (name.startsWith(CACHE_NAME_PREFIX)) await caches.delete(name);
    }
  } catch {
    // Cache Storage unavailable or refused; the stores above are cleared either way.
  }
}

/** Export and display order everywhere: oldest first by createdAt, ties broken by id. */
function sortByCreated<T extends { createdAt?: string | null; id: string }>(rows: readonly T[]): T[] {
  return [...rows].sort((a, b) => cmp(a.createdAt ?? '', b.createdAt ?? '') || cmp(a.id, b.id));
}

function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
