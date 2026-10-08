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

import { costSummary, type CostInput } from './house-cost';

/**
 * One range of the list's cost filters (docs/11 5.21, S4b-BL-84): whole rupees, each end optional; a range typed the
 * wrong way round reads the right way round. Kotlin: `CostRange`.
 */
export interface CostRange {
  min?: number | null;
  max?: number | null;
}

/** The list's filters over the cost numbers: monthly cost, money to move in, cost per sq ft. Kotlin: `CostFilter`. */
export interface CostFilter {
  monthly: CostRange;
  moveIn: CostRange;
  perSqFt: CostRange;
}

/** The filter that accepts every house. */
export const NO_COST_FILTER: Readonly<CostFilter> = { monthly: {}, moveIn: {}, perSqFt: {} };

/** Whether the range has a lower or an upper end. */
export function rangeIsSet(r: CostRange): boolean {
  return r.min != null || r.max != null;
}

/** How many of the three ranges are set: the count on the *Filters* control. */
export function activeCostFilters(f: CostFilter): number {
  return [f.monthly, f.moveIn, f.perSqFt].filter(rangeIsSet).length;
}

/** True when the range is not set, or `value` is known and within it (both ends included). */
export function rangeAccepts(r: CostRange, value: number | null): boolean {
  if (!rangeIsSet(r)) return true;
  if (value === null) return false;
  const both = r.min != null && r.max != null;
  const low = both ? Math.min(r.min!, r.max!) : r.min;
  const high = both ? Math.max(r.min!, r.max!) : r.max;
  return (low == null || value >= low) && (high == null || value <= high);
}

/**
 * Whether a house passes the cost filters (S4b-BL-84): each set range over what {@link costSummary} computes; a house
 * whose number cannot be computed (a sale has no monthly cost, no area no per sq ft) is left out by a range on that
 * number. Kotlin: `CostFilter.matches`, with the same vectors (`cost-filter.spec.ts`, `CostFilterTest`).
 */
export function costFilterMatches(f: CostFilter, house: CostInput): boolean {
  if (activeCostFilters(f) === 0) return true;
  const s = costSummary(house);
  return rangeAccepts(f.monthly, s.monthlyCost) && rangeAccepts(f.moveIn, s.moveIn) && rangeAccepts(f.perSqFt, s.perSqFt);
}
