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

// My places of the browser's store (docs/11 "Design of slice 4a"): records of type `place` (an office, a parent's home)
// that the distances on a house are measured to, read as typed rows, and the save that refuses what the server would
// refuse and writes only when something changed. It is separate from LocalStore (S4b-BL-168) because the places page,
// the house's distances, the export and the on-device AI each read or write places; it keeps no rows of its own and
// goes through RecordStore.
import { LocalDataError } from '../core/local-error';
import type { RecordStore } from './record-store';
import {
  MAX_PLACES,
  MAX_PLACE_NAME,
  PLACE_TYPE,
  isRecordKey,
  newPlaceIdRandom,
  placeFromPayload,
  placeToPayload,
  sortByName,
  validLat,
  validLon,
} from '../shared/area';
import type { Place, PlaceRow } from '../shared/area';

export class PlaceStore {
  constructor(private readonly records: RecordStore) {}

  /** The live places with their edit times; rows whose payload is not a valid place are left out. */
  async rows(): Promise<PlaceRow[]> {
    const out: PlaceRow[] = [];
    for (const row of await this.records.ofType(PLACE_TYPE)) {
      const place = placeFromPayload(row.id, row.payload);
      if (place) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, place });
    }
    return out;
  }

  /** Every live place, by name then id. */
  async all(): Promise<Place[]> {
    return sortByName((await this.rows()).map((r) => r.place), (p) => p.name);
  }

  /** A fresh place id that no record of that type holds yet. */
  async newId(newId: () => string = newPlaceIdRandom): Promise<string> {
    return this.records.freshId(PLACE_TYPE, newId);
  }

  /** @throws LocalDataError `error.badRecord` for a bad id, name (1..60) or point; `places.max` when a new place would be the 11th. */
  async save(place: Place, now: number = Date.now()): Promise<Place> {
    const clean: Place = { ...place, name: place.name.trim() };
    if (!isRecordKey(clean.id) || clean.name === '' || clean.name.length > MAX_PLACE_NAME || !validLat(clean.lat) || !validLon(clean.lon)) {
      throw new LocalDataError('error.badRecord');
    }
    const payload = placeToPayload(clean);
    await this.records.saveIfChanged(PLACE_TYPE, clean.id, payload, MAX_PLACES, 'places.max', now);
    return placeFromPayload(clean.id, payload) as Place;
  }

  /** Deletes a place (a tombstone). */
  async delete(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(PLACE_TYPE, id, now);
  }
}
