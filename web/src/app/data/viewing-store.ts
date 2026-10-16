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

// The viewings of the browser's store (docs/11 5.8): records of type `viewing`, read as typed rows, and the save that
// refuses what the server would refuse (a bad id, a blank house, a start or duration out of range, text over its cap,
// the 5 001st viewing) and writes only when something changed. It is separate from LocalStore (S4b-BL-168) because the
// viewings page, the house's viewings card, the reminders, the on-device AI, the export and the local-data facade each
// read or write viewings; it keeps no rows of its own and goes through RecordStore.
import { LocalDataError } from '../core/local-error';
import type { RecordStore } from './record-store';
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

export class ViewingStore {
  constructor(private readonly records: RecordStore) {}

  /** The live viewing records, oldest edit first; a row that is not a viewing (no house, no time) is skipped as untrusted. */
  async rows(): Promise<ViewingRow[]> {
    const out: ViewingRow[] = [];
    for (const row of await this.records.ofType(VIEWING_TYPE)) {
      const viewing = viewingFromPayload(row.id, row.payload);
      if (viewing) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, viewing });
    }
    return out;
  }

  /** Every live viewing, by `startsAt` then id. */
  async all(): Promise<Viewing[]> {
    return sortViewings((await this.rows()).map((r) => r.viewing));
  }

  /** The viewings of one house (a house that is gone keeps its viewings; this still finds them by id). */
  async ofHouse(houseId: string): Promise<Viewing[]> {
    return (await this.all()).filter((v) => v.houseId === houseId);
  }

  /** The earliest PLANNED viewing of the house at or after `nowMs`, or null. */
  async next(houseId: string, nowMs: number = Date.now()): Promise<Viewing | null> {
    return nextViewingOf(await this.all(), houseId, nowMs);
  }

  /**
   * `v_` and 8 lowercase hex characters, an id no record of type `viewing` has, a tombstone included (an id that
   * clashes is drawn again). `newId` is a seam for tests.
   */
  async newId(newId: () => string = newViewingIdRandom): Promise<string> {
    return this.records.freshId(VIEWING_TYPE, newId);
  }

  /**
   * Saves a viewing: only that record is written, and only when it differs from what is stored (so an unchanged form
   * does not touch `updatedAt` or the sync queue). A new one is the 5 001st refused.
   *
   * @throws LocalDataError `error.badRecord` for a bad id, a blank house, a start that is not positive, a duration
   *   outside 5..480, a kind, status or reminder outside the lists, or a `withWhom` or `notes` over its cap;
   *   `viewings.max` at 5 000 live viewings.
   */
  async save(viewing: Viewing, now: number = Date.now()): Promise<Viewing> {
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
    await this.records.saveIfChanged(VIEWING_TYPE, clean.id, payload, MAX_VIEWINGS, 'viewings.max', now);
    return viewingFromPayload(clean.id, payload) as Viewing;
  }

  /** Deletes a viewing (a tombstone the next sync sends). The house, if it still exists, is not touched. */
  async delete(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(VIEWING_TYPE, id, now);
  }

  /** *It happened* / *Mark viewing done*: status DONE, and `visitId` when a visit is given (else the old one is kept). */
  async markDone(id: string, visitId?: string | null, now: number = Date.now()): Promise<Viewing> {
    const row = await this.records.get(VIEWING_TYPE, id);
    const viewing = row ? viewingFromPayload(id, row.payload) : null;
    if (!viewing) throw new LocalDataError('error.notFoundLocal');
    return this.save({ ...viewing, status: 'DONE', ...(visitId ? { visitId } : {}) }, now);
  }
}
