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

import type { TKey } from '../i18n/en';
import { MAX_FLOOR, MIN_FLOOR } from '../data/records';

/**
 * The house's floor (S4b-BL-87): -5..200, 0 the ground floor and a negative one a basement level. The words a page or a
 * readable copy shows ({@link floorWords}), the words search finds ({@link floorSearchText}, Kotlin
 * `HouseSearch.floorText`) and the form's reading of typed text ({@link parseFloor}, Kotlin `floorOf`).
 */

/** "Ground floor", "Basement 2" or the number, through `t` (the app's translation, or `tr` over a copy's dictionary). */
export function floorWords(t: (key: TKey, params?: Record<string, string | number>) => string, floor: number): string {
  if (floor === 0) return t('house.floorGround');
  if (floor < 0) return t('house.floorBasement', { n: -floor });
  return String(floor);
}

/** The English words a floor is found by on both apps: "floor 3", "ground floor 0", "basement 2". */
export function floorSearchText(floor: number): string {
  if (floor === 0) return 'ground floor 0';
  if (floor < 0) return `basement ${-floor}`;
  return `floor ${floor}`;
}

/** Typed text as a floor: an optional "-" and up to three digits within -5..200, else null (blank too). */
export function parseFloor(text: string): number | null {
  const t = text.trim();
  if (!/^-?\d{1,3}$/.test(t)) return null;
  const n = Number(t);
  return n < MIN_FLOOR || n > MAX_FLOOR ? null : n;
}
