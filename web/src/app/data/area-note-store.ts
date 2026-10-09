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

// The area notes of the browser's store (docs/11 "Design of slice 4a"): records of type `areanote`, a text that is
// about one area (reaching the houses inside its radius) or about one street (reaching the houses on it), read as typed
// rows, and the save that refuses what the server would refuse. It is separate from LocalStore (S4b-BL-168) because
// the areas page, the house's area cards, the export and the on-device AI each read or write notes; it keeps no rows of
// its own and goes through RecordStore. Which house a note reaches is decided in `shared/area.ts`, not here.
import { LocalDataError } from '../core/local-error';
import type { RecordStore } from './record-store';
import {
  AREA_NOTE_TYPE,
  MAX_AREA_ID,
  MAX_AREA_NOTES,
  MAX_NOTE_TEXT,
  MAX_STREET,
  areaNoteFromPayload,
  areaNoteToPayload,
  isRecordKey,
  newAreaNoteIdRandom,
  newestFirst,
} from '../shared/area';
import type { AreaNote, AreaNoteRow } from '../shared/area';

export class AreaNoteStore {
  constructor(private readonly records: RecordStore) {}

  /** The live area-note records (every one, whether or not its area still exists), oldest edit first. */
  async rows(): Promise<AreaNoteRow[]> {
    const out: AreaNoteRow[] = [];
    for (const row of await this.records.ofType(AREA_NOTE_TYPE)) {
      const note = areaNoteFromPayload(row.id, row.payload);
      if (note) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, note });
    }
    return out;
  }

  /** Every live note, newest edit first. */
  async all(): Promise<AreaNoteRow[]> {
    return newestFirst(await this.rows());
  }

  /** A fresh area-note id that no record of that type holds yet. */
  async newId(newId: () => string = newAreaNoteIdRandom): Promise<string> {
    return this.records.freshId(AREA_NOTE_TYPE, newId);
  }

  /**
   * @throws LocalDataError `error.badRecord` for a bad id, neither or both of `areaId` (<= 64) and `street` (1..100),
   *   or a blank or over-long text (1..1000); `areaNotes.max` when a new note would be the 201st.
   */
  async save(note: AreaNote, now: number = Date.now()): Promise<AreaNote> {
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
  async delete(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(AREA_NOTE_TYPE, id, now);
  }
}
