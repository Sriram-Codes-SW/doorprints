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

import type { HouseStatus } from '../core/models';

/**
 * The status rules of slice 5 (docs/11 5.24): at most one house is TAKEN, and *Close this hunt* marks the rest
 * NOT_CHOSEN. Pure, the twin of Kotlin `HouseStatusRules` in android/shared; pinned by the vectors M1..M3.
 */

/** The part of a house the rules read. */
export interface StatusHouse {
  readonly id: string;
  readonly status: HouseStatus;
}

/**
 * `houses` after house `id` is given `status`. Choosing TAKEN returns the house that was TAKEN to SHORTLISTED, so at most
 * one house is TAKEN; any other status only changes that house. A house that is not in the list changes nothing.
 */
export function choose<T extends StatusHouse>(houses: readonly T[], id: string, status: HouseStatus): T[] {
  if (!houses.some((h) => h.id === id)) return [...houses];
  return houses.map((h) => {
    if (h.id === id) return h.status === status ? h : { ...h, status };
    if (status === 'TAKEN' && h.status === 'TAKEN') return { ...h, status: 'SHORTLISTED' as const };
    return h;
  });
}

/** The ids *Close this hunt* would mark NOT_CHOSEN: every house that is not the TAKEN one and not already REJECTED or NOT_CHOSEN. */
export function closeTargets(houses: readonly StatusHouse[], takenId: string): string[] {
  return houses.filter((h) => h.id !== takenId && inTheRunning(h.status)).map((h) => h.id);
}

/**
 * The statuses out of the running (S4b-BL-99 a): REJECTED (turned down after looking) and NOT_CHOSEN (passed over when
 * another house was taken). Compare and the Plan's fallback route leave them out, and the Plan prompt skips them unless
 * asked, the same on the server (`HouseStatus.inTheRunning`) and the phones (`HouseStatusRules.inTheRunning`), pinned by
 * the parity vectors' `inTheRunning`.
 */
export const OUT_OF_THE_RUNNING: readonly HouseStatus[] = ['REJECTED', 'NOT_CHOSEN'];

/** False for a status in `OUT_OF_THE_RUNNING`; any other value (an unknown one or none) is in the running. */
export function inTheRunning(status: string | null | undefined): boolean {
  return !(OUT_OF_THE_RUNNING as readonly (string | null | undefined)[]).includes(status);
}

/** The same helpers as one object, the twin of Kotlin's `HouseStatusRules`. */
export const HouseStatusRules = { choose, closeTargets, inTheRunning } as const;
