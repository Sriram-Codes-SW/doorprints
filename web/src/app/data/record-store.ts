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

// The record rows of the browser's store (docs/11 5.30 item 2): the one table every kind that is not a house, visit or
// photo lives in (brokers, criteria, questions, viewings, areas, places, area notes, saved walks). It owns the reads by
// type and key, the save that stamps a row and marks it dirty, the tombstone, the clean mark after a push, and the id
// draw that avoids a row already stored. It is separate from LocalStore (S4b-BL-168) because the sync, the import, the
// Drive rows and every kind built on records call it, so a change to how a record is stored edits this file and not
// the 1,300-line service. LocalStore owns the database and the revision; it hands both in.
import { LocalDataError } from '../core/local-error';
import type { RecordDto } from '../core/models';
import type { LocalDb } from './local-db';
import { compareText, isoNow, millis, recordFromDto } from './records';
import type { RecordRecord } from './records';

/** Records in backup order (docs/11 5.30 item 3): by last edit, then id. */
export function sortRecords(rows: readonly RecordRecord[]): RecordRecord[] {
  return [...rows].sort((a, b) => compareText(a.updatedAt ?? '', b.updatedAt ?? '') || compareText(a.id, b.id));
}

export class RecordStore {
  /**
   * @param database the opened database, once the store is ready
   * @param changed called after each write, so views and the sync engine see a new revision
   */
  constructor(
    private readonly database: () => Promise<LocalDb>,
    private readonly changed: () => void,
  ) {}

  /** The live records of one `type`, through the `type` index, oldest edit first. */
  async ofType(type: string): Promise<RecordRecord[]> {
    const db = await this.database();
    const rows = await db.getAllByIndex<RecordRecord>('records', 'type', type);
    return sortRecords(rows.filter((r) => !r.deleted));
  }

  /** Every record of one `type`, tombstones included: an import's last-write-wins comparison. */
  async allOfType(type: string): Promise<RecordRecord[]> {
    const db = await this.database();
    return db.getAllByIndex<RecordRecord>('records', 'type', type);
  }

  /** One live record, or undefined for an unknown or deleted one. */
  async get(type: string, id: string): Promise<RecordRecord | undefined> {
    const db = await this.database();
    const record = await db.get<RecordRecord>('records', [type, id]);
    return record && !record.deleted ? record : undefined;
  }

  /**
   * Saves a local edit of one record: stamps `updatedAt` and marks it dirty. The sync version of the stored row is
   * kept, as for a house.
   *
   * @throws LocalDataError when the type or id is not usable, or the payload is over the server's cap.
   */
  async save(type: string, id: string, payload: Record<string, unknown>, now: number = Date.now()): Promise<RecordRecord> {
    const db = await this.database();
    const existing = await db.get<RecordRecord>('records', [type, id]);
    const record = recordFromDto(
      { type, id, payload, updatedAt: isoNow(now), deleted: false, syncVersion: existing?.syncVersion ?? 0 },
      true,
    );
    await db.put('records', record);
    this.changed();
    return record;
  }

  /** Marks a record deleted: a tombstone with an empty payload, so other devices learn about it. */
  async delete(type: string, id: string, now: number = Date.now()): Promise<void> {
    const db = await this.database();
    const existing = await db.get<RecordRecord>('records', [type, id]);
    if (!existing) return;
    await db.put<RecordRecord>('records', { ...existing, payload: {}, deleted: true, dirty: true, updatedAt: isoNow(now) });
    this.changed();
  }

  /** Stores a record that came from the remote, clean; the caller has already applied the merge rule. */
  async putFromServer(dto: RecordDto): Promise<void> {
    const db = await this.database();
    await db.put('records', recordFromDto(dto, false));
    this.changed();
  }

  /** Clears the dirty flag after a push, unless the record was edited while the push was in flight. */
  async markClean(type: string, id: string, pushedUpdatedAt: string | null | undefined): Promise<void> {
    const db = await this.database();
    const existing = await db.get<RecordRecord>('records', [type, id]);
    if (existing && millis(existing.updatedAt) === millis(pushedUpdatedAt)) {
      await db.put('records', { ...existing, dirty: false });
    }
  }

  /** Every record of every type, tombstones included (the pull's merge rule needs the clean ones too, S4b-BL-130). */
  async all(): Promise<RecordRecord[]> {
    const db = await this.database();
    return sortRecords(await db.getAll<RecordRecord>('records'));
  }

  /** The stored records under these `[type, id]` keys, tombstones included, in one read. */
  async rowsByKeys(keys: readonly (readonly [string, string])[]): Promise<RecordRecord[]> {
    const db = await this.database();
    return db.getMany<RecordRecord>('records', keys.map(([type, id]): [string, string] => [type, id]));
  }

  /** Records with local changes the remote has not seen, tombstones included. */
  async dirty(): Promise<RecordRecord[]> {
    const db = await this.database();
    return sortRecords((await db.getAll<RecordRecord>('records')).filter((r) => r.dirty));
  }

  /** An id from `newId` that no record of `type` holds, a tombstone included; an id that clashes is drawn again. */
  async freshId(type: string, newId: () => string): Promise<string> {
    const db = await this.database();
    for (let attempt = 0; attempt < 50; attempt++) {
      const id = newId();
      if (!(await db.get<RecordRecord>('records', [type, id]))) return id;
    }
    throw new LocalDataError('error.badRecord');
  }

  /** Writes a record only when its payload differs from the stored one; a new record past `cap` live ones is refused. */
  async saveIfChanged(
    type: string,
    id: string,
    payload: Record<string, unknown>,
    cap: number,
    capKey: 'areas.max' | 'places.max' | 'areaNotes.max' | 'viewings.max',
    now: number,
  ): Promise<void> {
    const existing = await this.get(type, id);
    if (existing) {
      if (JSON.stringify(existing.payload) === JSON.stringify(payload)) return;
    } else if ((await this.ofType(type)).length >= cap) {
      throw new LocalDataError(capKey);
    }
    await this.save(type, id, payload, now);
  }
}
