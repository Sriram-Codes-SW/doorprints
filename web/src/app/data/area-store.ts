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

// The hunting areas of the browser's store (docs/11 "Design of slice 4a"): records of type `area`, read as typed rows,
// and the save that refuses what the server would refuse (a bad id, a blank or long name, a point or radius out of
// range, the 21st area) and writes only when something changed. It is separate from LocalStore (S4b-BL-168) because
// the areas page, the house's area cards, the map, the offline maps, the export and the on-device AI each read or
// write areas; it keeps no rows of its own and goes through RecordStore.
import { LocalDataError } from '../core/local-error';
import type { RecordStore } from './record-store';
import {
  AREA_TYPE,
  MAX_AREAS,
  MAX_AREA_NAME,
  areaFromPayload,
  areaToPayload,
  isRecordKey,
  newAreaIdRandom,
  sortByName,
  validLat,
  validLon,
  validRadius,
} from '../shared/area';
import type { Area, AreaRow } from '../shared/area';

export class AreaStore {
  constructor(private readonly records: RecordStore) {}

  /** The live area records, oldest edit first; a row that is not an area (bad name or point) is skipped as untrusted. */
  async rows(): Promise<AreaRow[]> {
    const out: AreaRow[] = [];
    for (const row of await this.records.ofType(AREA_TYPE)) {
      const area = areaFromPayload(row.id, row.payload);
      if (area) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, area });
    }
    return out;
  }

  /** Every live area, by name then id. */
  async all(): Promise<Area[]> {
    return sortByName((await this.rows()).map((r) => r.area), (a) => a.name);
  }

  /** A fresh `a_` id that no area record, a deleted one included, has (`newId` is a seam for tests). */
  async newId(newId: () => string = newAreaIdRandom): Promise<string> {
    return this.records.freshId(AREA_TYPE, newId);
  }

  /**
   * Saves an area: only that record is written, and only when it differs from what is stored. The name is trimmed.
   *
   * @throws LocalDataError `error.badRecord` for a bad id, a blank or over-long name, a point or radius out of range;
   *   `areas.max` when a new area would be the 21st.
   */
  async save(area: Area, now: number = Date.now()): Promise<Area> {
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
  async delete(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(AREA_TYPE, id, now);
  }
}
