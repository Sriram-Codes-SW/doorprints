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

import { Injectable, signal } from '@angular/core';
import type { HouseDto, StatsDto, VisitDto } from '../core/models';
import { openLocalDb } from './local-db';
import type { LocalDb, OpenedDb, StorageProblem } from './local-db';
import { deleteSavedWalksOfHouse } from './trace-rows';
import { SETTING_KEYS, compareText, houseFromDto, isoNow, millis, sortByCreated, visitFromDto } from './records';
import { PhotoStore } from './photo-store';
import { RecordStore } from './record-store';
import { ViewingStore } from './viewing-store';
import { AreaStore } from './area-store';
import { PlaceStore } from './place-store';
import { AreaNoteStore } from './area-note-store';
import { QuestionStore } from './question-store';
import { CriteriaStore } from './criteria-store';
import { BrokerStore } from './broker-store';
import type { LengthUnit } from '../shared/room-sizes';
import { choose as chooseStatus, closeTargets } from '../shared/house-status';
import type { HouseRecord, PhotoRecord, RecordRecord, SettingRecord, VisitRecord } from './records';

/** Quiet period after the last write before {@link LocalStore.settled} follows `revision`. */
export const SETTLE_MS = 300;
/** During a long run of writes (a first-run download), views still refresh at least this often. */
export const SETTLE_MAX_MS = 2000;

/** This browser's position in the server's change log, per list (`GET /api/<list>?since=`). */
export interface Cursors {
  house: number;
  visit: number;
  photo: number;
  record: number;
}

const CURSOR_KEYS = [
  SETTING_KEYS.houseCursor,
  SETTING_KEYS.visitCursor,
  SETTING_KEYS.photoCursor,
  SETTING_KEYS.recordCursor,
] as const;

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
  /**
   * True once another tab opened a newer version of the app and this tab closed its database for it (S4b-BL-71):
   * nothing can be read or written here any more, and the banner asks the user to reload.
   */
  readonly closedByNewerTab = signal(false);
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

  /** The photo rows (`photo-store.ts`); they share this store's database and revision. */
  readonly photos = new PhotoStore(
    () => this.db(),
    () => this.touch(),
  );

  /** The record rows (`record-store.ts`): the table every kind but houses, visits and photos lives in. */
  readonly records = new RecordStore(
    () => this.db(),
    () => this.touch(),
  );

  /** The viewings (`viewing-store.ts`), records of type `viewing`. */
  readonly viewings = new ViewingStore(this.records);

  /** The hunting areas (`area-store.ts`), records of type `area`. */
  readonly areas = new AreaStore(this.records);

  /** My places (`place-store.ts`), records of type `place`. */
  readonly places = new PlaceStore(this.records);

  /** The area notes (`area-note-store.ts`), records of type `areanote`. */
  readonly areaNotes = new AreaNoteStore(this.records);

  /** The viewing-question bank (`question-store.ts`), records of type `question`, and its seeding. */
  readonly questions = new QuestionStore(
    this.records,
    () => this.db(),
    () => this.touch(),
  );

  /** The checklist criteria and the rating share (`criteria-store.ts`), records of type `criterion` and `preference`. */
  readonly criteria = new CriteriaStore(this.records, () => this.rawHouses());

  /** The brokers (`broker-store.ts`), records of type `broker`, the contact copies on their houses and the contacts migration. */
  readonly brokers = new BrokerStore(
    this.records,
    () => this.db(),
    () => this.rawDb(),
    () => this.touch(),
  );

  private opened: Promise<LocalDb> | null = null;
  private settleTimer: ReturnType<typeof setTimeout> | undefined;
  private settleSince = 0;
  private migrating: Promise<void> | null = null;

  /** The opened database, once the one-off move of the contacts into brokers has run (see {@link ready}). */
  private async db(): Promise<LocalDb> {
    const db = await this.rawDb();
    this.migrating ??= this.brokers.migrate().then(
      () => undefined,
      () => undefined,
    );
    await this.migrating;
    return db;
  }

  private rawDb(): Promise<LocalDb> {
    this.opened ??= openLocalDb(() => this.closedByNewerTab.set(true)).then((result: OpenedDb) => {
      this.storageProblem.set(result.problem);
      return result.db;
    });
    return this.opened;
  }

  /**
   * Waits for the store to be ready; resolves even when IndexedDB was refused. Every read and write waits for the
   * same thing, so the first screen never sees houses before their contacts became brokers (slice 1b).
   */
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
    return sortByCreated(await this.rawHouses());
  }

  /** The houses store as read, unsorted: callers that filter first sort only what they keep. */
  private async rawHouses(): Promise<HouseRecord[]> {
    const db = await this.db();
    return db.getAll<HouseRecord>('houses');
  }

  /** Every house that is not deleted, in export order. */
  async liveHouses(): Promise<HouseRecord[]> {
    return sortByCreated((await this.rawHouses()).filter((h) => !h.deleted));
  }

  /** The stored houses with these ids, tombstones included, in one read; an unknown id adds nothing (the sync pull's merge). */
  async houseRowsByIds(ids: readonly string[]): Promise<HouseRecord[]> {
    const db = await this.db();
    return db.getMany<HouseRecord>('houses', ids);
  }

  /** One live house, or undefined for an unknown or deleted id. */
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
      ...houseFromDto(await this.brokers.link(house, now), true),
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
    for (const photo of await this.photos.ofHouse(id)) await this.photos.delete(photo.id, now);
    // A saved walk is the house's and never outlives it; the website has no undo (docs/11 5.27.6, PRV-030).
    await deleteSavedWalksOfHouse(db, id);
    this.touch();
  }

  /**
   * The opened database, for the stores that live beside the records and are never synced or exported (the path trace,
   * `data/trace-store.ts`). Not for the pages.
   */
  database(): Promise<LocalDb> {
    return this.db();
  }

  /** Applies a row that came from the server (sync or import). The caller has already applied the LWW rule. */
  async putHouseFromServer(dto: HouseDto): Promise<void> {
    const db = await this.db();
    await db.put('houses', houseFromDto(dto, false));
    this.touch();
  }

  /**
   * Clears the dirty flag after a push, but only if the house has not been edited since that push began; otherwise the newer edit stays dirty for the next sync.
   */
  async markHouseClean(id: string, pushedUpdatedAt: string | null | undefined): Promise<void> {
    const db = await this.db();
    const existing = await db.get<HouseRecord>('houses', id);
    // Only clear the flag when nothing changed while the push was in flight (Android: markClean(id, updatedAt)).
    if (existing && millis(existing.updatedAt) === millis(pushedUpdatedAt)) {
      await db.put('houses', { ...existing, dirty: false });
    }
  }

  /** The houses with local changes the remote has not seen, tombstones included: what a push sends. */
  async dirtyHouses(): Promise<HouseRecord[]> {
    return sortByCreated((await this.rawHouses()).filter((h) => h.dirty));
  }

  // ---- Visits ----

  /** Every visit, tombstones included. */
  async allVisits(): Promise<VisitRecord[]> {
    return sortVisits(await this.rawVisits());
  }

  /** The stored visits with these ids, tombstones included, in one read. */
  async visitRowsByIds(ids: readonly string[]): Promise<VisitRecord[]> {
    const db = await this.db();
    return db.getMany<VisitRecord>('visits', ids);
  }

  private async rawVisits(): Promise<VisitRecord[]> {
    const db = await this.db();
    return db.getAll<VisitRecord>('visits');
  }

  /** A house's live visits, through the `houseId` index (not a read of every visit). */
  async visitsOf(houseId: string): Promise<VisitRecord[]> {
    const db = await this.db();
    const rows = await db.getAllByIndex<VisitRecord>('visits', 'houseId', houseId);
    return sortVisits(rows.filter((v) => !v.deleted));
  }

  /** Live visits per house id, from one read of the store (Compare's columns, instead of one read per house). */
  async visitCountsByHouse(): Promise<Map<string, number>> {
    const counts = new Map<string, number>();
    for (const v of await this.rawVisits()) {
      if (!v.deleted && v.houseId) counts.set(v.houseId, (counts.get(v.houseId) ?? 0) + 1);
    }
    return counts;
  }

  /** Saves a local edit of a visit: stamps `updatedAt` and marks it dirty so the next sync pushes it. */
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

  /** Marks a visit deleted (a tombstone, so other devices learn about it). */
  async deleteVisit(id: string, now: number = Date.now()): Promise<void> {
    const db = await this.db();
    const existing = await db.get<VisitRecord>('visits', id);
    if (!existing) return;
    await db.put('visits', { ...existing, deleted: true, dirty: true, updatedAt: isoNow(now) });
    this.touch();
  }

  /** Stores a visit that came from the remote, clean; the caller has already applied the merge rule. */
  async putVisitFromServer(dto: VisitDto): Promise<void> {
    const db = await this.db();
    await db.put('visits', visitFromDto(dto, false));
    this.touch();
  }

  /** Clears the dirty flag after a push, unless the visit was edited while the push was in flight. */
  async markVisitClean(id: string, pushedUpdatedAt: string | null | undefined): Promise<void> {
    const db = await this.db();
    const existing = await db.get<VisitRecord>('visits', id);
    if (existing && millis(existing.updatedAt) === millis(pushedUpdatedAt)) {
      await db.put('visits', { ...existing, dirty: false });
    }
  }

  /** Visits with local changes the remote has not seen, tombstones included. */
  async dirtyVisits(): Promise<VisitRecord[]> {
    return sortVisits((await this.rawVisits()).filter((v) => v.dirty));
  }

  /**
   * Choosing TAKEN, after the chosen house itself is saved: the house that was TAKEN goes back to SHORTLISTED (M1) and,
   * when `markOthers`, every other house still open becomes NOT_CHOSEN (`closeTargets`). Returns how many houses
   * were changed. Each changed house is saved like an edit (dirty, `updatedAt` now).
   */
  async applyTaken(takenId: string, markOthers: boolean, now: number = Date.now()): Promise<number> {
    const live = await this.liveHouses();
    let next = chooseStatus(live, takenId, 'TAKEN');
    if (markOthers) {
      const targets = new Set(closeTargets(next, takenId));
      next = next.map((h) => (targets.has(h.id) ? { ...h, status: 'NOT_CHOSEN' as const } : h));
    }
    return this.saveStatusChanges(live, next, takenId, now);
  }

  /** How many houses *Close this hunt* would mark NOT_CHOSEN for the TAKEN house. */
  async closeCount(takenId: string): Promise<number> {
    return closeTargets(await this.liveHouses(), takenId).length;
  }

  /** *Close this hunt*: every `closeTargets` house becomes NOT_CHOSEN in one step; nothing is deleted. Returns how many. */
  async closeHunt(takenId: string, now: number = Date.now()): Promise<number> {
    const live = await this.liveHouses();
    const targets = new Set(closeTargets(live, takenId));
    const next = live.map((h) => (targets.has(h.id) ? { ...h, status: 'NOT_CHOSEN' as const } : h));
    return this.saveStatusChanges(live, next, takenId, now);
  }

  private async saveStatusChanges(before: readonly HouseRecord[], after: readonly HouseRecord[], exceptId: string, now: number): Promise<number> {
    const db = await this.db();
    const was = new Map(before.map((h) => [h.id, h.status]));
    let changed = 0;
    for (const house of after) {
      if (house.id === exceptId || was.get(house.id) === house.status) continue;
      await db.put('houses', { ...house, dirty: true, updatedAt: isoNow(now) });
      changed++;
    }
    if (changed > 0) this.touch();
    return changed;
  }

  // ---- Import (S4b-BL-75) ----

  /**
   * Writes the rows an import chose, as they are (the caller stamped and marked them; `ImportService`), then bumps the
   * revision once, so a screen re-reads after the whole import rather than per row.
   */
  async putImported(rows: { houses?: readonly HouseRecord[]; visits?: readonly VisitRecord[]; photos?: readonly PhotoRecord[]; records?: readonly RecordRecord[] }): Promise<void> {
    const db = await this.db();
    for (const r of rows.records ?? []) await db.put('records', r);
    for (const h of rows.houses ?? []) await db.put('houses', h);
    for (const v of rows.visits ?? []) await db.put('visits', v);
    for (const p of rows.photos ?? []) await db.put('photos', p);
    this.touch();
  }

  /** A stored house, tombstones included. */
  async getHouseRow(id: string): Promise<HouseRecord | undefined> {
    const db = await this.db();
    return db.get<HouseRecord>('houses', id);
  }

  /** A stored visit, tombstones included. */
  async getVisitRow(id: string): Promise<VisitRecord | undefined> {
    const db = await this.db();
    return db.get<VisitRecord>('visits', id);
  }

  /**
   * After the sync found its server reset (S4b-BL-20, `SyncService`): every house, visit and record, tombstones
   * included, is marked dirty and every stored photo not uploaded, so the next push sends everything this browser holds. The
   * edit times are kept: the server's last-write-wins rule still decides against rows another device sent since.
   * Photo deletes waiting to be sent stay as they are (a delete of a photo the server does not have is a no-op).
   */
  async markAllForResync(): Promise<void> {
    const db = await this.db();
    for (const house of await db.getAll<HouseRecord>('houses')) {
      if (!house.dirty) await db.put('houses', { ...house, dirty: true });
    }
    for (const visit of await db.getAll<VisitRecord>('visits')) {
      if (!visit.dirty) await db.put('visits', { ...visit, dirty: true });
    }
    for (const record of await db.getAll<RecordRecord>('records')) {
      if (!record.dirty) await db.put('records', { ...record, dirty: true });
    }
    await this.photos.markAllForResync();
    this.touch();
  }

  // ---- Settings ----

  /** A per-browser setting, or null when unset. Settings are never synced or exported. */
  async setting(key: string): Promise<string | null> {
    const db = await this.db();
    return (await db.get<SettingRecord>('settings', key))?.value ?? null;
  }

  /** Stores a per-browser setting. */
  async setSetting(key: string, value: string): Promise<void> {
    const db = await this.db();
    await db.put<SettingRecord>('settings', { key, value });
  }

  /** Removes a per-browser setting. */
  async removeSetting(key: string): Promise<void> {
    const db = await this.db();
    await db.delete('settings', key);
  }

  /** The length unit preference for rooms (slice 1c): feet unless the person chose metres. Local only, never synced. */
  async lengthUnit(): Promise<LengthUnit> {
    return (await this.setting(SETTING_KEYS.lengthUnit)) === 'M' ? 'M' : 'FT';
  }

  /** Stores the room length unit preference. */
  async setLengthUnit(unit: LengthUnit): Promise<void> {
    await this.setSetting(SETTING_KEYS.lengthUnit, unit);
  }

  /** *Notify me while Doorprints is open* (slice 3b-2): on unless the person turned it off. Local only, never synced. */
  async viewingsRemind(): Promise<boolean> {
    return (await this.setting(SETTING_KEYS.viewingsRemind)) !== '0';
  }

  /** Stores whether viewing reminders are on. */
  async setViewingsRemind(on: boolean): Promise<void> {
    await this.setSetting(SETTING_KEYS.viewingsRemind, on ? '1' : '0');
  }

  /** A numeric setting; unset or not a number reads as 0. */
  async numberSetting(key: string): Promise<number> {
    const raw = await this.setting(key);
    const n = raw === null ? Number.NaN : Number(raw);
    return Number.isFinite(n) ? n : 0;
  }

  /** The stored sync positions, one per list; each is 0 before the first sync. */
  async cursors(): Promise<Cursors> {
    return {
      house: await this.numberSetting(SETTING_KEYS.houseCursor),
      visit: await this.numberSetting(SETTING_KEYS.visitCursor),
      photo: await this.numberSetting(SETTING_KEYS.photoCursor),
      record: await this.numberSetting(SETTING_KEYS.recordCursor),
    };
  }

  /** Every cursor back to 0: a different server, or one found reset (the sync engine's two callers). */
  async resetCursors(): Promise<void> {
    for (const key of CURSOR_KEYS) await this.setSetting(key, '0');
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
    return (await this.rawHouses()).length === 0 && (await this.rawVisits()).length === 0;
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
   *  * the chosen language (`doorprints.lang`), which is a preference, not a record of anything;
   *  * the saved server address and API key — that is `ConfigService.clear()`'s job, and the "Your data" screen
   *    calls it alongside this so the sensitive half is gone too.
   */
  async clearEverything(): Promise<void> {
    const db = await this.db();
    await db.clear();
    await clearCacheStorage();
    await this.questions.seedAgainIfAsked();
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

/** Visits in export order: by arrival, then id. */
function sortVisits(rows: readonly VisitRecord[]): VisitRecord[] {
  return [...rows].sort((a, b) => compareText(a.arrivedAt, b.arrivedAt) || compareText(a.id, b.id));
}
