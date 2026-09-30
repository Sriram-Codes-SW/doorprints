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
import type { HouseDto, RecordDto, StatsDto, VisitDto } from '../core/models';
import { openLocalDb } from './local-db';
import type { LocalDb, OpenedDb, StorageProblem } from './local-db';
import { BROKER_TYPE, MAX_BROKER_NAME, MAX_BROKER_PHONE, brokerFromPayload, brokerToPayload, phoneKey, samePhone } from '../shared/broker';
import type { Broker, BrokerRow } from '../shared/broker';
import { SETTING_KEYS, houseFromDto, isoNow, millis, recordFromDto, visitFromDto } from './records';
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
  MAX_DURATION_MIN,
  MAX_ID_LENGTH,
  MAX_VIEWINGS,
  MAX_VIEWING_NOTES,
  MAX_WITH_WHOM,
  MIN_DURATION_MIN,
  REMIND_OPTIONS,
  VIEWING_KINDS,
  VIEWING_STATUSES,
  VIEWING_TYPE,
  isViewingId,
  newViewingId as newViewingIdRandom,
  nextViewingOf,
  sortViewings,
  viewingFromPayload,
  viewingToPayload,
} from '../shared/viewing';
import type { Viewing, ViewingRow } from '../shared/viewing';
import type { LengthUnit } from '../shared/room-sizes';
import type { HouseRecord, PhotoRecord, RecordRecord, SettingRecord, VisitRecord } from './records';

/** Quiet period after the last write before {@link LocalStore.settled} follows `revision`. */
export const SETTLE_MS = 300;
/** During a long run of writes (a first-run download), views still refresh at least this often. */
export const SETTLE_MAX_MS = 2000;

/** Same ceiling as the Android app and the server (shared MAX_PHOTOS_PER_HOUSE). */
export const MAX_PHOTOS_PER_HOUSE = 20;

export type AddPhotoResult = { ok: true; id: string } | { ok: false; reason: 'limit' };

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

  async liveHouses(): Promise<HouseRecord[]> {
    return sortByCreated((await this.rawHouses()).filter((h) => !h.deleted));
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
    return sortByCreated((await this.rawHouses()).filter((h) => h.dirty));
  }

  // ---- Visits ----

  async allVisits(): Promise<VisitRecord[]> {
    return sortVisits(await this.rawVisits());
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
    return sortVisits((await this.rawVisits()).filter((v) => v.dirty));
  }

  // ---- Photos ----

  async allPhotos(): Promise<PhotoRecord[]> {
    const db = await this.db();
    return sortByCreated(await db.getAll<PhotoRecord>('photos'));
  }

  /** A house's live photos, through the `houseId` index: the store holds Blobs, so every photo is not read (S4b-BL-66). */
  async photosOf(houseId: string): Promise<PhotoRecord[]> {
    const db = await this.db();
    const rows = await db.getAllByIndex<PhotoRecord>('photos', 'houseId', houseId);
    return sortByCreated(rows.filter((p) => !p.deleted));
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

  // ---- Records (docs/11 5.30 item 2: every other Sprint 4b entity, in one store) ----

  /** The live records of one `type`, through the `type` index, oldest edit first. */
  async recordsOf(type: string): Promise<RecordRecord[]> {
    const db = await this.db();
    const rows = await db.getAllByIndex<RecordRecord>('records', 'type', type);
    return sortRecords(rows.filter((r) => !r.deleted));
  }

  async getRecord(type: string, id: string): Promise<RecordRecord | undefined> {
    const db = await this.db();
    const record = await db.get<RecordRecord>('records', [type, id]);
    return record && !record.deleted ? record : undefined;
  }

  /**
   * Saves a local edit of one record: stamps `updatedAt` and marks it dirty. The sync version of the stored row is
   * kept, as for a house.
   *
   * @throws LocalDataError when the type or id is not usable, or the payload is over the server's cap.
   */
  async saveRecord(
    type: string,
    id: string,
    payload: Record<string, unknown>,
    now: number = Date.now(),
  ): Promise<RecordRecord> {
    const db = await this.db();
    const existing = await db.get<RecordRecord>('records', [type, id]);
    const record = recordFromDto(
      { type, id, payload, updatedAt: isoNow(now), deleted: false, syncVersion: existing?.syncVersion ?? 0 },
      true,
    );
    await db.put('records', record);
    this.touch();
    return record;
  }

  /** Marks a record deleted: a tombstone with an empty payload, so other devices learn about it. */
  async deleteRecord(type: string, id: string, now: number = Date.now()): Promise<void> {
    const db = await this.db();
    const existing = await db.get<RecordRecord>('records', [type, id]);
    if (!existing) return;
    await db.put<RecordRecord>('records', { ...existing, payload: {}, deleted: true, dirty: true, updatedAt: isoNow(now) });
    this.touch();
  }

  async putRecordFromServer(dto: RecordDto): Promise<void> {
    const db = await this.db();
    await db.put('records', recordFromDto(dto, false));
    this.touch();
  }

  async markRecordClean(type: string, id: string, pushedUpdatedAt: string | null | undefined): Promise<void> {
    const db = await this.db();
    const existing = await db.get<RecordRecord>('records', [type, id]);
    if (existing && millis(existing.updatedAt) === millis(pushedUpdatedAt)) {
      await db.put('records', { ...existing, dirty: false });
    }
  }

  async dirtyRecords(): Promise<RecordRecord[]> {
    const db = await this.db();
    return sortRecords((await db.getAll<RecordRecord>('records')).filter((r) => r.dirty));
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
    for (const photo of await db.getAll<PhotoRecord>('photos')) {
      if (!photo.deleted && photo.blob && photo.uploaded) await db.put('photos', { ...photo, uploaded: false });
    }
    this.touch();
  }

  // ---- Brokers (docs/11 5.25, slice 1b: records of type `broker`; the house keeps copies of the contact) ----

  /** The live brokers, oldest edit first; a row whose payload is not a broker (a blank name) is skipped. */
  async brokers(): Promise<BrokerRow[]> {
    return brokerRows(await this.recordsOf(BROKER_TYPE));
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
    const record = await this.saveRecord(BROKER_TYPE, id, brokerToPayload(clean), now);
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
    await this.deleteRecord(BROKER_TYPE, id, now);
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
    for (const row of await this.recordsOf(CRITERION_TYPE)) {
      const criterion = criterionFromPayload(row.id, row.payload);
      if (criterion) out.push({ key: row.id, updatedAt: row.updatedAt ?? null, criterion });
    }
    return out;
  }

  /** The live preference records, oldest edit first; a row without a text value is skipped. */
  async preferenceRows(): Promise<PreferenceRow[]> {
    const out: PreferenceRow[] = [];
    for (const row of await this.recordsOf(PREFERENCE_TYPE)) {
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
      await this.deleteRecord(CRITERION_TYPE, criterion.key, now);
      return;
    }
    if (!builtIn) {
      const label = criterion.label?.trim() ?? '';
      if (label === '' || label.length > MAX_CRITERION_LABEL) throw new LocalDataError('error.badRecord');
      if (!(await this.getRecord(CRITERION_TYPE, criterion.key)) && (await this.criterionCount()) >= MAX_CRITERIA) {
        throw new LocalDataError('criteria.max');
      }
    }
    await this.saveRecord(CRITERION_TYPE, criterion.key, criterionToPayload({ ...criterion, label: criterion.label?.trim() }), now);
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
    await this.deleteRecord(CRITERION_TYPE, key, now);
  }

  /** True when a live house has a checklist score under `key`. */
  async criterionInUse(key: string): Promise<boolean> {
    return (await this.rawHouses()).some((h) => !h.deleted && typeof h.checklist?.[key] === 'number');
  }

  /** Stores the rating share (0..1); the default 0.5 needs no record, so it deletes it. */
  async setRatingShare(share: number, now: number = Date.now()): Promise<void> {
    const value = ratingShareValue(Math.min(1, Math.max(0, share)));
    if (Number(value) === DEFAULT_RATING_SHARE) await this.deleteRecord(PREFERENCE_TYPE, RATING_SHARE_KEY, now);
    else await this.saveRecord(PREFERENCE_TYPE, RATING_SHARE_KEY, { value }, now);
  }

  /** "Reset to defaults": deletes every criterion and preference record (tombstones, so other devices follow). */
  async resetCriteria(now: number = Date.now()): Promise<void> {
    for (const row of await this.recordsOf(CRITERION_TYPE)) await this.deleteRecord(CRITERION_TYPE, row.id, now);
    for (const row of await this.recordsOf(PREFERENCE_TYPE)) await this.deleteRecord(PREFERENCE_TYPE, row.id, now);
  }

  /** The ten built-ins plus the live custom records: what the cap of 40 counts (archived ones included). */
  private async criterionCount(): Promise<number> {
    const custom = (await this.recordsOf(CRITERION_TYPE)).filter((r) => !isBuiltInKey(r.id)).length;
    return BUILT_IN_KEYS.length + custom;
  }

  // ---- Viewing questions (docs/11 5.5, slice 3a: records of type `question`) ----

  /** The live question records, oldest edit first; a row whose payload is not a question (a blank text) is skipped. */
  async questionRows(): Promise<QuestionRow[]> {
    const out: QuestionRow[] = [];
    for (const row of await this.recordsOf(QUESTION_TYPE)) {
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
   * Seeds the bank: for each default whose id has NO record a dirty record with the text in [language] (hi, ta or te;
   * anything else English). A tombstone counts as a record, so a default the person deleted is not brought back.
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
          updatedAt: isoNow(now),
          deleted: false,
          syncVersion: existing?.syncVersion ?? 0,
        },
        true,
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
    if (!(await this.getRecord(QUESTION_TYPE, question.id)) && (await this.recordsOf(QUESTION_TYPE)).length >= MAX_QUESTIONS) {
      throw new LocalDataError('questions.max');
    }
    await this.saveRecord(QUESTION_TYPE, question.id, questionToPayload({ ...question, text }), now);
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
    if ((await this.recordsOf(QUESTION_TYPE)).length >= MAX_QUESTIONS) throw new LocalDataError('questions.max');
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
    await this.deleteRecord(QUESTION_TYPE, id, now);
  }

  // ---- Viewings (docs/11 5.8, slice 3b-1: records of type `viewing`) ----

  /** The live viewing records, oldest edit first; a row that is not a viewing (no house, no time) is skipped as untrusted. */
  async viewingRows(): Promise<ViewingRow[]> {
    const out: ViewingRow[] = [];
    for (const row of await this.recordsOf(VIEWING_TYPE)) {
      const viewing = viewingFromPayload(row.id, row.payload);
      if (viewing) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, viewing });
    }
    return out;
  }

  /** Every live viewing, by `startsAt` then id. */
  async viewings(): Promise<Viewing[]> {
    return sortViewings((await this.viewingRows()).map((r) => r.viewing));
  }

  /** The viewings of one house (a house that is gone keeps its viewings; this still finds them by id). */
  async viewingsOf(houseId: string): Promise<Viewing[]> {
    return (await this.viewings()).filter((v) => v.houseId === houseId);
  }

  /** The earliest PLANNED viewing of the house at or after `nowMs`, or null. */
  async nextViewing(houseId: string, nowMs: number = Date.now()): Promise<Viewing | null> {
    return nextViewingOf(await this.viewings(), houseId, nowMs);
  }

  /**
   * `v_` and 8 lowercase hex characters, an id no record of type `viewing` has, a tombstone included (an id that
   * clashes is drawn again). `newId` is a seam for tests.
   */
  async newViewingId(newId: () => string = newViewingIdRandom): Promise<string> {
    const db = await this.db();
    for (let attempt = 0; attempt < 50; attempt++) {
      const id = newId();
      if (!(await db.get<RecordRecord>('records', [VIEWING_TYPE, id]))) return id;
    }
    throw new LocalDataError('error.badRecord');
  }

  /**
   * Saves a viewing: only that record is written, and only when it differs from what is stored (so an unchanged form
   * does not touch `updatedAt` or the sync queue). A new one is the 5 001st refused.
   *
   * @throws LocalDataError `error.badRecord` for a bad id, a blank house, a start that is not positive, a duration
   *   outside 5..480, a kind, status or reminder outside the lists, or a `withWhom` or `notes` over its cap;
   *   `viewings.max` at 5 000 live viewings.
   */
  async saveViewing(viewing: Viewing, now: number = Date.now()): Promise<Viewing> {
    const clean: Viewing = { ...viewing, houseId: viewing.houseId.trim() };
    if (clean.withWhom !== undefined) clean.withWhom = clean.withWhom.trim();
    if (clean.notes !== undefined) clean.notes = clean.notes.trim();
    if (
      !isViewingId(clean.id) ||
      clean.houseId === '' ||
      clean.houseId.length > MAX_ID_LENGTH ||
      (clean.visitId?.length ?? 0) > MAX_ID_LENGTH ||
      !Number.isSafeInteger(clean.startsAt) ||
      clean.startsAt <= 0 ||
      !Number.isInteger(clean.durationMin) ||
      clean.durationMin < MIN_DURATION_MIN ||
      clean.durationMin > MAX_DURATION_MIN ||
      !VIEWING_KINDS.includes(clean.kind) ||
      !VIEWING_STATUSES.includes(clean.status) ||
      !REMIND_OPTIONS.includes(clean.remindMin) ||
      (clean.withWhom?.length ?? 0) > MAX_WITH_WHOM ||
      (clean.notes?.length ?? 0) > MAX_VIEWING_NOTES
    ) {
      throw new LocalDataError('error.badRecord');
    }
    const payload = viewingToPayload(clean);
    const existing = await this.getRecord(VIEWING_TYPE, clean.id);
    if (existing) {
      if (JSON.stringify(existing.payload) === JSON.stringify(payload)) return viewingFromPayload(clean.id, payload) as Viewing;
    } else if ((await this.recordsOf(VIEWING_TYPE)).length >= MAX_VIEWINGS) {
      throw new LocalDataError('viewings.max');
    }
    await this.saveRecord(VIEWING_TYPE, clean.id, payload, now);
    return viewingFromPayload(clean.id, payload) as Viewing;
  }

  /** Deletes a viewing (a tombstone the next sync sends). The house, if it still exists, is not touched. */
  async deleteViewing(id: string, now: number = Date.now()): Promise<void> {
    await this.deleteRecord(VIEWING_TYPE, id, now);
  }

  /** *It happened* / *Mark viewing done*: status DONE, and `visitId` when a visit is given (else the old one is kept). */
  async markViewingDone(id: string, visitId?: string | null, now: number = Date.now()): Promise<Viewing> {
    const row = await this.getRecord(VIEWING_TYPE, id);
    const viewing = row ? viewingFromPayload(id, row.payload) : null;
    if (!viewing) throw new LocalDataError('error.notFoundLocal');
    return this.saveViewing({ ...viewing, status: 'DONE', ...(visitId ? { visitId } : {}) }, now);
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

  /** The length unit preference for rooms (slice 1c): feet unless the person chose metres. Local only, never synced. */
  async lengthUnit(): Promise<LengthUnit> {
    return (await this.setting(SETTING_KEYS.lengthUnit)) === 'M' ? 'M' : 'FT';
  }

  async setLengthUnit(unit: LengthUnit): Promise<void> {
    await this.setSetting(SETTING_KEYS.lengthUnit, unit);
  }

  async numberSetting(key: string): Promise<number> {
    const raw = await this.setting(key);
    const n = raw === null ? Number.NaN : Number(raw);
    return Number.isFinite(n) ? n : 0;
  }

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

/** Records in backup order (docs/11 5.30 item 3): by last edit, then id. */
function sortRecords(rows: readonly RecordRecord[]): RecordRecord[] {
  return [...rows].sort((a, b) => cmp(a.updatedAt ?? '', b.updatedAt ?? '') || cmp(a.id, b.id));
}

/** Visits in export order: by arrival, then id. */
function sortVisits(rows: readonly VisitRecord[]): VisitRecord[] {
  return [...rows].sort((a, b) => cmp(a.arrivedAt, b.arrivedAt) || cmp(a.id, b.id));
}

/** Export and display order everywhere: oldest first by createdAt, ties broken by id. */
function sortByCreated<T extends { createdAt?: string | null; id: string }>(rows: readonly T[]): T[] {
  return [...rows].sort((a, b) => cmp(a.createdAt ?? '', b.createdAt ?? '') || cmp(a.id, b.id));
}

function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}
