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
import { LocalDataError } from '../core/local-error';
import { uuid } from '../core/models';
import type { HouseDto, StatsDto, VisitDto } from '../core/models';
import { openLocalDb } from './local-db';
import type { LocalDb, OpenedDb, StorageProblem } from './local-db';
import { BROKER_TYPE, MAX_BROKER_NAME, MAX_BROKER_PHONE, brokerFromPayload, brokerToPayload, phoneKey, samePhone } from '../shared/broker';
import type { Broker, BrokerRow } from '../shared/broker';
import { deleteSavedWalksOfHouse } from './trace-rows';
import { SETTING_KEYS, compareText, houseFromDto, isoNow, millis, recordFromDto, sortByCreated, visitFromDto } from './records';
import { PhotoStore } from './photo-store';
import { RecordStore, sortRecords } from './record-store';
import { ViewingStore } from './viewing-store';
import {
  BUILT_IN_KEYS,
  CRITERION_TYPE,
  MAX_CRITERIA,
  MAX_CRITERION_LABEL,
  PREFERENCE_TYPE,
  RATING_SHARE_KEY,
  DEFAULT_RATING_SHARE,
  criterionFromPayload,
  criterionToPayload,
  isBuiltInKey,
  isCustomKey,
  isDefaultCriterion,
  newCustomKey,
  ratingShareValue,
  scoringOf,
} from '../shared/scoring';
import type { Criterion, CriterionRow, PreferenceRow, Scoring, Weight } from '../shared/scoring';
import {
  DEFAULT_QUESTIONS,
  DEFAULT_QUESTIONS_SEEDED_AT,
  MAX_QUESTIONS,
  MAX_QUESTION_TEXT,
  QUESTION_TYPE,
  defaultQuestion,
  isCustomQuestionId,
  isDefaultQuestionId,
  newQuestionId,
  questionFromPayload,
  questionToPayload,
  sortQuestions,
} from '../shared/question';
import type { Question, QuestionCategory, QuestionRow, QuestionScope } from '../shared/question';
import {
  AREA_NOTE_TYPE,
  AREA_TYPE,
  MAX_AREAS,
  MAX_AREA_ID,
  MAX_AREA_NAME,
  MAX_AREA_NOTES,
  MAX_NOTE_TEXT,
  MAX_PLACES,
  MAX_PLACE_NAME,
  MAX_STREET,
  PLACE_TYPE,
  areaFromPayload,
  areaNoteFromPayload,
  areaNoteToPayload,
  areaToPayload,
  isRecordKey,
  newAreaIdRandom,
  newAreaNoteIdRandom,
  newPlaceIdRandom,
  newestFirst,
  placeFromPayload,
  placeToPayload,
  sortByName,
  validLat,
  validLon,
  validRadius,
} from '../shared/area';
import type { Area, AreaNote, AreaNoteRow, AreaRow, Place, PlaceRow } from '../shared/area';
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

  private opened: Promise<LocalDb> | null = null;
  private settleTimer: ReturnType<typeof setTimeout> | undefined;
  private settleSince = 0;
  private migrating: Promise<void> | null = null;
  /** The language the bank is seeded in, once {@link seedQuestionsOnce} has been called (also after *Remove all data*). */
  private seedLanguage: (() => string) | null = null;

  /** The opened database, once the one-off move of the contacts into brokers has run (see {@link ready}). */
  private async db(): Promise<LocalDb> {
    const db = await this.rawDb();
    this.migrating ??= this.moveContactsToBrokers(db).then(
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
      ...houseFromDto(await this.withBroker(house, now), true),
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

  // ---- Brokers (docs/11 5.25, slice 1b: records of type `broker`; the house keeps copies of the contact) ----

  /** The live brokers, oldest edit first; a row whose payload is not a broker (a blank name) is skipped. */
  async brokers(): Promise<BrokerRow[]> {
    return brokerRows(await this.records.ofType(BROKER_TYPE));
  }

  /**
   * Saves a broker (create or update) and rewrites the name and phone copies on every live house linked to it, so the
   * exports, the search and an old app keep showing the contact.
   *
   * @throws LocalDataError when the name is blank or too long.
   */
  async saveBroker(id: string, broker: Broker, now: number = Date.now()): Promise<BrokerRow> {
    const clean = brokerFromPayload({ ...broker });
    if (!clean) throw new LocalDataError('error.badRecord');
    const record = await this.records.save(BROKER_TYPE, id, brokerToPayload(clean), now);
    const db = await this.db();
    for (const house of await db.getAll<HouseRecord>('houses')) {
      if (house.deleted || house.brokerId !== id) continue;
      if (house.contactName === clean.name && house.contactPhone === (clean.phone ?? null)) continue;
      await db.put('houses', { ...house, contactName: clean.name, contactPhone: clean.phone ?? null, dirty: true, updatedAt: isoNow(now) });
    }
    this.touch();
    return { id, updatedAt: record.updatedAt ?? null, broker: clean };
  }

  /** Deletes a broker (a tombstone); its houses lose the link and keep the contact details they hold. */
  async deleteBroker(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(BROKER_TYPE, id, now);
    const db = await this.db();
    for (const house of await db.getAll<HouseRecord>('houses')) {
      if (house.brokerId === id) await db.put('houses', { ...house, brokerId: null, dirty: true, updatedAt: isoNow(now) });
    }
    this.touch();
  }

  /** The live houses linked to a broker, in list order, for the broker's page. */
  async brokerHouses(id: string): Promise<HouseRecord[]> {
    return sortByCreated((await this.rawHouses()).filter((h) => !h.deleted && h.brokerId === id));
  }

  /**
   * What {@link saveHouse} does about the broker. A house linked to a broker that exists gets that broker's name and
   * phone as its contact. A house with a phone and no link is linked to the broker with the same number, or to a new
   * one made from it (`ensureBroker`); a blank phone never makes a broker. An id that names no broker is left alone.
   */
  private async withBroker(house: HouseDto, now: number): Promise<HouseDto> {
    if (house.brokerId) {
      const known = (await this.brokers()).find((row) => row.id === house.brokerId);
      return known ? linked(house, known) : house;
    }
    const phone = (house.contactPhone ?? '').trim();
    if (phone === '') return house;
    const found = (await this.brokers()).find((row) => samePhone(row.broker.phone, phone));
    if (found) return linked(house, found);
    const name = (house.contactName ?? '').trim() || phone;
    const broker: Broker = { name: name.slice(0, MAX_BROKER_NAME), phone: phone.slice(0, MAX_BROKER_PHONE) };
    return linked(house, await this.saveBroker(uuid(), broker, now));
  }

  /** Runs {@link moveContactsToBrokers} again (it does nothing once the flag is set); returns the houses it linked. */
  async migrateContactsToBrokers(): Promise<number> {
    return this.moveContactsToBrokers(await this.rawDb());
  }

  /**
   * The one-off migration (slice 1b): each live house with a phone and no broker is linked to a broker made from its
   * contact, one broker per distinct number (the last ten digits; "+91 98400 11111", "098400-11111" and
   * "9840011111" are one). A broker that already has the number is reused. The flag `brokers.migrated` is set at the
   * end, so a person who later unlinks a house on purpose is never re-linked, and a run that stopped half way
   * resumes. Works on the raw database because {@link db} waits for it.
   */
  private async moveContactsToBrokers(db: LocalDb, now: number = Date.now()): Promise<number> {
    if (await db.get<SettingRecord>('settings', SETTING_KEYS.brokersMigrated)) return 0;
    const houses = sortByCreated(
      (await db.getAll<HouseRecord>('houses')).filter((h) => !h.deleted && !h.brokerId && (h.contactPhone ?? '').trim() !== ''),
    );
    const groups = new Map<string, HouseRecord[]>();
    for (const house of houses) {
      const phone = (house.contactPhone ?? '').trim();
      // A number too short to compare stands only for itself.
      const key = phoneKey(phone) ?? `raw:${phone}`;
      groups.set(key, [...(groups.get(key) ?? []), house]);
    }
    const existing = brokerRows(
      (await db.getAllByIndex<RecordRecord>('records', 'type', BROKER_TYPE)).filter((r) => !r.deleted),
    );
    let linkedHouses = 0;
    for (const [key, members] of groups) {
      // The newest edit names the broker.
      const latest = members.reduce((a, b) => (millis(b.updatedAt) >= millis(a.updatedAt) ? b : a));
      let row = existing.find((r) => (phoneKey(r.broker.phone) ?? `raw:${(r.broker.phone ?? '').trim()}`) === key);
      if (!row) {
        const phone = (latest.contactPhone ?? '').trim();
        const name = (latest.contactName ?? '').trim() || phone;
        const broker: Broker = { name: name.slice(0, MAX_BROKER_NAME), phone: phone.slice(0, MAX_BROKER_PHONE) };
        const record = recordFromDto(
          { type: BROKER_TYPE, id: uuid(), payload: brokerToPayload(broker), updatedAt: isoNow(now), deleted: false, syncVersion: 0 },
          true,
        );
        await db.put('records', record);
        row = { id: record.id, updatedAt: record.updatedAt ?? null, broker };
        existing.push(row);
      }
      for (const house of members) {
        await db.put('houses', { ...house, brokerId: row.id, dirty: true, updatedAt: isoNow(now) });
        linkedHouses += 1;
      }
    }
    await db.put<SettingRecord>('settings', { key: SETTING_KEYS.brokersMigrated, value: '1' });
    if (linkedHouses > 0) this.touch();
    return linkedHouses;
  }

  // ---- Criteria and the rating share (docs/11 5.4, slice 2: records of type `criterion` and `preference`) ----

  /** The live criterion records (only what differs from the defaults), oldest edit first; a bad key is skipped. */
  async criterionRows(): Promise<CriterionRow[]> {
    const out: CriterionRow[] = [];
    for (const row of await this.records.ofType(CRITERION_TYPE)) {
      const criterion = criterionFromPayload(row.id, row.payload);
      if (criterion) out.push({ key: row.id, updatedAt: row.updatedAt ?? null, criterion });
    }
    return out;
  }

  /** The live preference records, oldest edit first; a row without a text value is skipped. */
  async preferenceRows(): Promise<PreferenceRow[]> {
    const out: PreferenceRow[] = [];
    for (const row of await this.records.ofType(PREFERENCE_TYPE)) {
      const value = row.payload['value'];
      if (typeof value === 'string') out.push({ key: row.id, value, updatedAt: row.updatedAt ?? null });
    }
    return out;
  }

  /** The effective scoring: the defaults merged with the criterion records and the rating-share preference. */
  async scoring(): Promise<Scoring> {
    return scoringOf(await this.criterionRows(), await this.preferenceRows());
  }

  /**
   * Saves a criterion, writing only what differs from the defaults: a built-in that equals its default has its record
   * deleted, any other is written. A custom criterion needs a label (at most 60 characters); a new one is refused
   * when there are already {@link MAX_CRITERIA} criteria (the ten built-ins included).
   *
   * @throws LocalDataError `error.badRecord` for a key that is neither built-in nor custom, or a bad label;
   *   `criteria.max` when the cap is reached.
   */
  async saveCriterion(criterion: Criterion, now: number = Date.now()): Promise<void> {
    const builtIn = isBuiltInKey(criterion.key);
    if (!builtIn && !isCustomKey(criterion.key)) throw new LocalDataError('error.badRecord');
    if (builtIn && isDefaultCriterion(criterion)) {
      await this.records.delete(CRITERION_TYPE, criterion.key, now);
      return;
    }
    if (!builtIn) {
      const label = criterion.label?.trim() ?? '';
      if (label === '' || label.length > MAX_CRITERION_LABEL) throw new LocalDataError('error.badRecord');
      if (!(await this.records.get(CRITERION_TYPE, criterion.key)) && (await this.criterionCount()) >= MAX_CRITERIA) {
        throw new LocalDataError('criteria.max');
      }
    }
    await this.records.save(CRITERION_TYPE, criterion.key, criterionToPayload({ ...criterion, label: criterion.label?.trim() }), now);
  }

  /** Saves several criteria (a re-ordering, a weight change on each): each as {@link saveCriterion}. */
  async saveCriteria(list: readonly Criterion[], now: number = Date.now()): Promise<void> {
    for (const criterion of list) await this.saveCriterion(criterion, now);
  }

  /**
   * Adds a custom criterion at the end of the list: a new key `c_` and 8 lowercase hex characters (a key that clashes
   * with any record, a deleted one included, is drawn again), weight Medium unless given, not a must-have.
   * `newKey` is a seam for tests.
   *
   * @throws LocalDataError `error.badRecord` for a blank or too long label; `criteria.max` at 40 criteria.
   */
  async addCriterion(
    label: string,
    weight: Weight = 2,
    now: number = Date.now(),
    newKey: () => string = newCustomKey,
  ): Promise<Criterion> {
    const text = label.trim();
    if (text === '' || text.length > MAX_CRITERION_LABEL) throw new LocalDataError('error.badRecord');
    if ((await this.criterionCount()) >= MAX_CRITERIA) throw new LocalDataError('criteria.max');
    const db = await this.db();
    let key = newKey();
    for (let attempt = 0; attempt < 50 && (await db.get<RecordRecord>('records', [CRITERION_TYPE, key])); attempt++) key = newKey();
    if (await db.get<RecordRecord>('records', [CRITERION_TYPE, key])) throw new LocalDataError('error.badRecord');
    const sort = (await this.scoring()).criteria.reduce((max, c) => Math.max(max, c.sort), -1) + 1;
    const criterion: Criterion = { key, label: text, weight, mustHave: false, minScore: 3, sort };
    await this.saveCriterion(criterion, now);
    return criterion;
  }

  /**
   * Deletes a custom criterion (a tombstone). Refused while any live house has a score under its key, so no score is
   * left without a name; archive it instead. Built-ins can only be archived.
   *
   * @throws LocalDataError `error.badRecord` for a built-in or unknown key; `criteria.inUse` when a house scored it.
   */
  async deleteCriterion(key: string, now: number = Date.now()): Promise<void> {
    if (!isCustomKey(key)) throw new LocalDataError('error.badRecord');
    if (await this.criterionInUse(key)) throw new LocalDataError('criteria.inUse');
    await this.records.delete(CRITERION_TYPE, key, now);
  }

  /** True when a live house has a checklist score under `key`. */
  async criterionInUse(key: string): Promise<boolean> {
    return (await this.rawHouses()).some((h) => !h.deleted && typeof h.checklist?.[key] === 'number');
  }

  /** Stores the rating share (0..1); the default 0.5 needs no record, so it deletes it. */
  async setRatingShare(share: number, now: number = Date.now()): Promise<void> {
    const value = ratingShareValue(Math.min(1, Math.max(0, share)));
    if (Number(value) === DEFAULT_RATING_SHARE) await this.records.delete(PREFERENCE_TYPE, RATING_SHARE_KEY, now);
    else await this.records.save(PREFERENCE_TYPE, RATING_SHARE_KEY, { value }, now);
  }

  /** "Reset to defaults": deletes every criterion and preference record (tombstones, so other devices follow). */
  async resetCriteria(now: number = Date.now()): Promise<void> {
    for (const row of await this.records.ofType(CRITERION_TYPE)) await this.records.delete(CRITERION_TYPE, row.id, now);
    for (const row of await this.records.ofType(PREFERENCE_TYPE)) await this.records.delete(PREFERENCE_TYPE, row.id, now);
  }

  /** The ten built-ins plus the live custom records: what the cap of 40 counts (archived ones included). */
  private async criterionCount(): Promise<number> {
    const custom = (await this.records.ofType(CRITERION_TYPE)).filter((r) => !isBuiltInKey(r.id)).length;
    return BUILT_IN_KEYS.length + custom;
  }

  // ---- Viewing questions (docs/11 5.5, slice 3a: records of type `question`) ----

  /** The live question records, oldest edit first; a row whose payload is not a question (a blank text) is skipped. */
  async questionRows(): Promise<QuestionRow[]> {
    const out: QuestionRow[] = [];
    for (const row of await this.records.ofType(QUESTION_TYPE)) {
      const question = questionFromPayload(row.id, row.payload);
      if (question) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, question });
    }
    return out;
  }

  /** The bank, archived questions included, by `sort` then id. */
  async questions(): Promise<Question[]> {
    return sortQuestions((await this.questionRows()).map((r) => r.question));
  }

  /**
   * Seeds the bank: for each default whose id has NO record a clean record stamped {@link DEFAULT_QUESTIONS_SEEDED_AT}
   * with the text in [language] (hi, ta or te; anything else English; S4b-BL-90a). A tombstone counts as a record, so
   * a default the person deleted is not brought back.
   * Returns how many were written.
   */
  async seedQuestions(language: string, now: number = Date.now()): Promise<number> {
    const db = await this.db();
    return this.writeDefaults(db, language, now, false);
  }

  /**
   * Seeds once per install: the first call sets the local setting `questions.seeded` (not synced), so later starts do
   * nothing and a bank the person emptied stays empty until *Reset to defaults*. `language` is read at each use
   * (the language the app is in then), also after *Remove all data* seeded the bank again.
   */
  async seedQuestionsOnce(language: () => string, now: number = Date.now()): Promise<void> {
    this.seedLanguage = language;
    const db = await this.db();
    if (await db.get<SettingRecord>('settings', SETTING_KEYS.questionsSeeded)) return;
    await this.writeDefaults(db, language(), now, false);
    await db.put<SettingRecord>('settings', { key: SETTING_KEYS.questionsSeeded, value: '1' });
  }

  /**
   * *Reset to defaults*: every default id gets its record again, whatever state it was in (deleted, edited, archived),
   * with the text in [language]; the person's own questions stay. A default that would take the bank past
   * {@link MAX_QUESTIONS} is not brought back.
   */
  async resetQuestions(language: string, now: number = Date.now()): Promise<number> {
    const db = await this.db();
    return this.writeDefaults(db, language, now, true);
  }

  private async writeDefaults(db: LocalDb, language: string, now: number, overwrite: boolean): Promise<number> {
    const rows = await db.getAllByIndex<RecordRecord>('records', 'type', QUESTION_TYPE);
    const byId = new Map(rows.map((r) => [r.id, r]));
    let live = rows.filter((r) => !r.deleted).length;
    let written = 0;
    for (const def of DEFAULT_QUESTIONS) {
      const existing = byId.get(def.id);
      if (existing && !overwrite) continue;
      if ((!existing || existing.deleted) && live >= MAX_QUESTIONS) continue;
      if (!existing || existing.deleted) live += 1;
      const record = recordFromDto(
        {
          type: QUESTION_TYPE,
          id: def.id,
          payload: questionToPayload(defaultQuestion(def, language)),
          // A seed is clean and stamped DEFAULT_QUESTIONS_SEEDED_AT (S4b-BL-90a): never pushed, and whatever another
          // device did to it wins when pulled. *Reset to defaults* is the person's own edit: now, dirty.
          updatedAt: isoNow(overwrite ? now : DEFAULT_QUESTIONS_SEEDED_AT),
          deleted: false,
          syncVersion: existing?.syncVersion ?? 0,
        },
        overwrite,
      );
      await db.put('records', record);
      written += 1;
    }
    if (written > 0) this.touch();
    return written;
  }

  /**
   * Saves a question (an edit, an archive, a move): only that record is written. A default keeps its fixed id.
   *
   * @throws LocalDataError `error.badRecord` for an id that is neither a default's nor `q_` and 8 hex characters, or a
   *   blank or over-long text; `questions.max` when a new question would be the 101st.
   */
  async saveQuestion(question: Question, now: number = Date.now()): Promise<void> {
    if (!isDefaultQuestionId(question.id) && !isCustomQuestionId(question.id)) throw new LocalDataError('error.badRecord');
    const text = question.text.trim();
    if (text === '' || text.length > MAX_QUESTION_TEXT) throw new LocalDataError('error.badRecord');
    if (!(await this.records.get(QUESTION_TYPE, question.id)) && (await this.records.ofType(QUESTION_TYPE)).length >= MAX_QUESTIONS) {
      throw new LocalDataError('questions.max');
    }
    await this.records.save(QUESTION_TYPE, question.id, questionToPayload({ ...question, text }), now);
  }

  /** Saves several questions (a move renumbers two or more): each as {@link saveQuestion}. */
  async saveQuestions(list: readonly Question[], now: number = Date.now()): Promise<void> {
    for (const question of list) await this.saveQuestion(question, now);
  }

  /**
   * Adds a custom question at the end of the bank: a new id `q_` and 8 lowercase hex characters (an id that clashes with
   * any record, a deleted one included, is drawn again). `newId` is a seam for tests.
   *
   * @throws LocalDataError `error.badRecord` for a blank or over-long text; `questions.max` at 100 questions.
   */
  async addQuestion(
    text: string,
    category: QuestionCategory = 'OTHER',
    appliesTo: QuestionScope = 'BOTH',
    now: number = Date.now(),
    newId: () => string = newQuestionId,
  ): Promise<Question> {
    const asked = text.trim();
    if (asked === '' || asked.length > MAX_QUESTION_TEXT) throw new LocalDataError('error.badRecord');
    if ((await this.records.ofType(QUESTION_TYPE)).length >= MAX_QUESTIONS) throw new LocalDataError('questions.max');
    const db = await this.db();
    let id = newId();
    for (let attempt = 0; attempt < 50 && (await db.get<RecordRecord>('records', [QUESTION_TYPE, id])); attempt++) id = newId();
    if (await db.get<RecordRecord>('records', [QUESTION_TYPE, id])) throw new LocalDataError('error.badRecord');
    const sort = (await this.questions()).reduce((max, q) => Math.max(max, q.sort), -1) + 1;
    const question: Question = { id, text: asked, category, appliesTo, defaultOn: false, sort };
    await this.saveQuestion(question, now);
    return question;
  }

  /** Deletes a question, a seeded one too (a tombstone): a deleted default stays deleted until *Reset to defaults*. */
  async deleteQuestion(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(QUESTION_TYPE, id, now);
  }

  // ---- Hunting areas, my places and area notes (docs/11 "Design of slice 4a": records of type `area`, `place`, `areanote`) ----

  /** The live area records, oldest edit first; a row that is not an area (bad name or point) is skipped as untrusted. */
  async areaRows(): Promise<AreaRow[]> {
    const out: AreaRow[] = [];
    for (const row of await this.records.ofType(AREA_TYPE)) {
      const area = areaFromPayload(row.id, row.payload);
      if (area) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, area });
    }
    return out;
  }

  /** Every live area, by name then id. */
  async areas(): Promise<Area[]> {
    return sortByName((await this.areaRows()).map((r) => r.area), (a) => a.name);
  }

  /** A fresh `a_` id that no area record, a deleted one included, has (`newId` is a seam for tests). */
  async newAreaId(newId: () => string = newAreaIdRandom): Promise<string> {
    return this.records.freshId(AREA_TYPE, newId);
  }

  /**
   * Saves an area: only that record is written, and only when it differs from what is stored. The name is trimmed.
   *
   * @throws LocalDataError `error.badRecord` for a bad id, a blank or over-long name, a point or radius out of range;
   *   `areas.max` when a new area would be the 21st.
   */
  async saveArea(area: Area, now: number = Date.now()): Promise<Area> {
    const clean: Area = { ...area, name: area.name.trim() };
    if (
      !isRecordKey(clean.id) ||
      clean.name === '' ||
      clean.name.length > MAX_AREA_NAME ||
      !validLat(clean.lat) ||
      !validLon(clean.lon) ||
      !validRadius(clean.radiusM)
    ) {
      throw new LocalDataError('error.badRecord');
    }
    const payload = areaToPayload(clean);
    await this.records.saveIfChanged(AREA_TYPE, clean.id, payload, MAX_AREAS, 'areas.max', now);
    return areaFromPayload(clean.id, payload) as Area;
  }

  /** Deletes an area (a tombstone). Its notes stay but reach no house until the area is back. */
  async deleteArea(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(AREA_TYPE, id, now);
  }

  /** The live places with their edit times; rows whose payload is not a valid place are left out. */
  async placeRows(): Promise<PlaceRow[]> {
    const out: PlaceRow[] = [];
    for (const row of await this.records.ofType(PLACE_TYPE)) {
      const place = placeFromPayload(row.id, row.payload);
      if (place) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, place });
    }
    return out;
  }

  /** Every live place, by name then id. */
  async places(): Promise<Place[]> {
    return sortByName((await this.placeRows()).map((r) => r.place), (p) => p.name);
  }

  /** A fresh place id that no record of that type holds yet. */
  async newPlaceId(newId: () => string = newPlaceIdRandom): Promise<string> {
    return this.records.freshId(PLACE_TYPE, newId);
  }

  /** @throws LocalDataError `error.badRecord` for a bad id, name (1..60) or point; `places.max` when a new place would be the 11th. */
  async savePlace(place: Place, now: number = Date.now()): Promise<Place> {
    const clean: Place = { ...place, name: place.name.trim() };
    if (!isRecordKey(clean.id) || clean.name === '' || clean.name.length > MAX_PLACE_NAME || !validLat(clean.lat) || !validLon(clean.lon)) {
      throw new LocalDataError('error.badRecord');
    }
    const payload = placeToPayload(clean);
    await this.records.saveIfChanged(PLACE_TYPE, clean.id, payload, MAX_PLACES, 'places.max', now);
    return placeFromPayload(clean.id, payload) as Place;
  }

  /** Deletes a place (a tombstone). */
  async deletePlace(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(PLACE_TYPE, id, now);
  }

  /** The live area-note records (every one, whether or not its area still exists), oldest edit first. */
  async areaNoteRows(): Promise<AreaNoteRow[]> {
    const out: AreaNoteRow[] = [];
    for (const row of await this.records.ofType(AREA_NOTE_TYPE)) {
      const note = areaNoteFromPayload(row.id, row.payload);
      if (note) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, note });
    }
    return out;
  }

  /** Every live note, newest edit first. */
  async areaNotes(): Promise<AreaNoteRow[]> {
    return newestFirst(await this.areaNoteRows());
  }

  /** A fresh area-note id that no record of that type holds yet. */
  async newAreaNoteId(newId: () => string = newAreaNoteIdRandom): Promise<string> {
    return this.records.freshId(AREA_NOTE_TYPE, newId);
  }

  /**
   * @throws LocalDataError `error.badRecord` for a bad id, neither or both of `areaId` (<= 64) and `street` (1..100),
   *   or a blank or over-long text (1..1000); `areaNotes.max` when a new note would be the 201st.
   */
  async saveAreaNote(note: AreaNote, now: number = Date.now()): Promise<AreaNote> {
    const clean: AreaNote = { id: note.id, text: note.text.trim() };
    const areaId = note.areaId?.trim();
    const street = note.street?.trim();
    if (areaId) clean.areaId = areaId;
    if (street) clean.street = street;
    if (
      !isRecordKey(clean.id) ||
      (clean.areaId === undefined) === (clean.street === undefined) ||
      (clean.areaId?.length ?? 0) > MAX_AREA_ID ||
      (clean.street?.length ?? 0) > MAX_STREET ||
      clean.text === '' ||
      clean.text.length > MAX_NOTE_TEXT
    ) {
      throw new LocalDataError('error.badRecord');
    }
    const payload = areaNoteToPayload(clean);
    await this.records.saveIfChanged(AREA_NOTE_TYPE, clean.id, payload, MAX_AREA_NOTES, 'areaNotes.max', now);
    return areaNoteFromPayload(clean.id, payload) as AreaNote;
  }

  /** Deletes an area note (a tombstone). */
  async deleteAreaNote(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(AREA_NOTE_TYPE, id, now);
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
    // An empty browser still starts with the standard questions, as a new install does.
    if (this.seedLanguage) await this.seedQuestionsOnce(this.seedLanguage);
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

/** The brokers among some `broker` records, as rows; a payload that is not a broker is skipped as untrusted. */
function brokerRows(rows: readonly RecordRecord[]): BrokerRow[] {
  const out: BrokerRow[] = [];
  for (const row of sortRecords(rows)) {
    const broker = brokerFromPayload(row.payload);
    if (broker) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, broker });
  }
  return out;
}

/** The house linked to a broker: its id, and the broker's name and phone as the contact copies. */
function linked(house: HouseDto, row: BrokerRow): HouseDto {
  return { ...house, brokerId: row.id, contactName: row.broker.name, contactPhone: row.broker.phone ?? null };
}

/** Visits in export order: by arrival, then id. */
function sortVisits(rows: readonly VisitRecord[]): VisitRecord[] {
  return [...rows].sort((a, b) => compareText(a.arrivedAt, b.arrivedAt) || compareText(a.id, b.id));
}
