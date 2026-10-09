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

// What the house page keeps about the floor of one house (S4b-BL-87): the level typed in Floor, the Basement switch
// that holds its sign, and whether what is typed is a floor at all. It is separate from `house-detail-page.ts`
// (S4b-BL-168) because three readers share it: the Floor field and the Basement switch of the template, the page's
// save (which refuses a floor that would be dropped without a word) and the duplicate-flat warning (`floorOf`). The
// page owns the draft and the save; this changes the draft only through `patch`.
import { signal } from '@angular/core';
import type { HouseDto } from '../../core/models';
import { parseFloor } from '../../shared/house-floor';

/** A typed floor (a number input gives a number, or "" when cleared) as -5..200, else null (S4b-BL-87). */
export function floorOf(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? parseFloor(String(value)) : null;
}

/** What the floor of the house page needs from the page. */
export interface HouseFloorDeps {
  /** The page's one way to change the draft: merges the fields in and marks the page dirty. */
  patch: (changes: Partial<HouseDto>) => void;
}

export class HouseFloor {
  /**
   * The Basement switch under Floor set with no level to carry the sign (blank or 0), by house id (S4b-BL-104 c). A
   * level other than 0 carries the sign itself, so this only matters until one is typed.
   */
  private readonly basementSet = signal<{ id: string; on: boolean } | null>(null);

  constructor(private readonly deps: HouseFloorDeps) {}

  /** The Basement switch: the floor's sign once a level is typed, else what the switch was last set to. */
  basement(d: HouseDto): boolean {
    if (typeof d.floor === 'number' && d.floor !== 0) return d.floor < 0;
    const set = this.basementSet();
    return set !== null && set.id === d.id && set.on;
  }

  /** What Floor shows: the level without its sign, which the Basement switch holds. */
  shown(d: HouseDto): unknown {
    return typeof d.floor === 'number' ? Math.abs(d.floor) : d.floor;
  }

  /** A level typed in Floor: below the ground while the switch is on; a minus typed anyway turns the switch on. */
  type(d: HouseDto, value: unknown): void {
    const typed = typeof value === 'number' && Number.isFinite(value) ? value : null;
    // Clearing the level keeps the switch as it was, so "2" can become "3" of a basement without it turning off.
    const below = (typed !== null && typed < 0) || this.basement(d);
    this.basementSet.set({ id: d.id, on: below });
    if (typed === null) this.deps.patch({ floor: value as number | null });
    else this.deps.patch({ floor: below && typed !== 0 ? -Math.abs(typed) : typed });
  }

  /** The Basement switch: turns the typed level into a basement level or back. */
  setBasement(d: HouseDto, event: Event): void {
    const on = (event.target as HTMLInputElement).checked;
    this.basementSet.set({ id: d.id, on });
    if (typeof d.floor === 'number' && d.floor !== 0) this.deps.patch({ floor: on ? -Math.abs(d.floor) : Math.abs(d.floor) });
  }

  /** Something is typed in Floor that is not a floor from -5 to 200, or a basement level that is not 1 to 5. */
  invalid(d: HouseDto): boolean {
    if (d.floor == null || (d.floor as unknown) === '') return false;
    return (d.floor === 0 && this.basement(d)) || floorOf(d.floor) === null;
  }
}
