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

// What the house page reads from outside its form: the listing text handed over in the navigation, and the brokers for
// its select in a fixed order, and the small pure helpers of its draft (moved out of house-detail-page.ts with kinds PR 5,
// S4b-BL-209, and kinds PR 16, S4b-BL-259, for the file-size budget).
import type { Router } from '@angular/router';
import type { HouseDto } from '../../core/models';
import type { BrokerRow } from '../../shared/broker';

/**
 * The listing text the share target handed over, from the navigation state.
 *
 * `Router.currentNavigation()` is only non-null while a navigation is in flight, which it is **not** any more by the
 * target component's `ngOnInit` (the zoneless scheduler runs the first change detection in a later task); the last
 * successful navigation is the same navigation by then. `history.state` covers a restored or re-entered route. All
 * are read defensively because anything can be in `history.state`.
 */
export function sharedFromNavigation(router: Router): string | null {
  const fromNavigation = (router.currentNavigation() ?? router.lastSuccessfulNavigation())?.extras.state;
  const state: unknown = fromNavigation ?? (typeof history === 'undefined' ? null : history.state);
  if (!state || typeof state !== 'object') return null;
  const shared = (state as { shared?: unknown }).shared;
  return typeof shared === 'string' && shared !== '' ? shared : null;
}

/** Brokers by name for the select, then by id, so equal names keep a fixed order. */
export function sortBrokers(rows: readonly BrokerRow[]): BrokerRow[] {
  return [...rows].sort(
    (a, b) => a.broker.name.localeCompare(b.broker.name) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0),
  );
}

/** A deep copy by JSON, for the saved state kept to restore on Discard. */
export function clone(h: HouseDto): HouseDto {
  return JSON.parse(JSON.stringify(h)) as HouseDto;
}

/** The form binds the Cost fields to `cost.*`, so a draft always carries an object there (null on the wire). */
export function withCost(h: HouseDto): HouseDto {
  return h.cost ? h : { ...h, cost: {} };
}

/** Trims the text; blank becomes null. */
export function blankToNull(s: string | null | undefined): string | null {
  const t = (s ?? '').trim();
  return t ? t : null;
}

/** A non-negative whole number, or null when the value is not a finite number. */
export function toWholeNumber(n: number | null | undefined): number | null {
  if (n === null || n === undefined || typeof n !== 'number' || !Number.isFinite(n)) return null;
  return Math.max(0, Math.round(n));
}
