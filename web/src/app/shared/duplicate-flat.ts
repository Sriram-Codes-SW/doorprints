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

import { haversineMeters } from '../core/ai/ai-core';

/** The facts the duplicate-flat warning compares (docs/11 5.25, S4b-BL-85). Kotlin: `FlatFacts`. */
export interface FlatFacts {
  id: string;
  lat: number;
  lon: number;
  locationSource?: string | null;
  bedrooms?: number | null;
  rooms?: readonly { type: string }[] | null;
  floor?: number | null;
}

/** "Within about 30 m": a GPS fix at a door is good to some metres, and two flats of a building share it. */
export const DUPLICATE_RADIUS_M = 30;

/** The bedrooms the check compares: the rooms of type BEDROOM when the house lists any, else its BHK. */
export function flatBedrooms(bedrooms: number | null | undefined, rooms: FlatFacts['rooms']): number | null {
  const fromRooms = (rooms ?? []).filter((r) => r.type === 'BEDROOM').length;
  if (fromRooms > 0) return fromRooms;
  return typeof bedrooms === 'number' && Number.isFinite(bedrooms) ? bedrooms : null;
}

/** A point that means a place: not "no location yet" (0, 0) and not only approximate. Kotlin: `HousePoint.placed`. */
function placed(h: FlatFacts): boolean {
  return !(h.lat === 0 && h.lon === 0) && h.locationSource !== 'APPROX';
}

/**
 * "Two brokers show the same flat" (docs/11 5.25, S4b-BL-85): another house within {@link DUPLICATE_RADIUS_M} with the
 * same bedrooms and the same floor, both known, both houses placed. A warning only, never a refusal. Kotlin:
 * `DuplicateFlat.isSameFlat`, with the same vectors (`duplicate-flat.spec.ts`, `DuplicateFlatTest`).
 */
export function isSameFlat(a: FlatFacts, b: FlatFacts): boolean {
  if (a.id === b.id || a.floor == null || a.floor !== b.floor) return false;
  const beds = flatBedrooms(a.bedrooms, a.rooms);
  if (beds === null || beds !== flatBedrooms(b.bedrooms, b.rooms)) return false;
  if (!placed(a) || !placed(b)) return false;
  return haversineMeters(a.lat, a.lon, b.lat, b.lon) <= DUPLICATE_RADIUS_M;
}

/** The ids of `others` that look like the same flat as `house`, in the order given. Kotlin: `DuplicateFlat.of`. */
export function duplicateFlats(house: FlatFacts, others: readonly FlatFacts[]): string[] {
  return others.filter((o) => isSameFlat(house, o)).map((o) => o.id);
}
