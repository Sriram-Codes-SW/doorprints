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

import { HouseDto, HouseStatus, STATUSES } from '../../core/models';
import type { Broker } from '../../shared/broker';
import { floorSearchText } from '../../shared/house-floor';
import { NO_COST_FILTER, type CostFilter, type CostRange } from '../../shared/cost-filter';

/**
 * The house list's search, status filter and sort, kept apart from the map page so they can be unit tested without
 * MapLibre, and written to the URL (`/?q=…&status=…&sort=…`): Back from a house then comes back to the same
 * filtered list, and a filtered view can be bookmarked or shared (UX audit 2026-09-23).
 */
export type SortKey = 'recent' | 'score' | 'price';
/** A status to show, or all. */
export type StatusFilter = HouseStatus | 'ALL';

export const SORT_KEYS: readonly SortKey[] = ['recent', 'score', 'price'];

/** The list's state as it goes into the URL: search text, status, sort and cost ranges. */
export interface ListQuery {
  q: string;
  status: StatusFilter;
  sort: SortKey;
  /** The ranges over the cost numbers (S4b-BL-84); absent is none. */
  cost?: CostFilter;
}

/** The defaults, which are left out of the URL. */
export const DEFAULT_LIST_QUERY: Readonly<ListQuery> = { q: '', status: 'ALL', sort: 'recent', cost: NO_COST_FILTER };

/** The cost filters' six ends in the URL (S4b-BL-84), each whole rupees: `monthlyMin=20000&sqftMax=60`. */
const COST_PARAMS: readonly [keyof CostFilter, keyof CostRange, string][] = [
  ['monthly', 'min', 'monthlyMin'],
  ['monthly', 'max', 'monthlyMax'],
  ['moveIn', 'min', 'moveInMin'],
  ['moveIn', 'max', 'moveInMax'],
  ['perSqFt', 'min', 'sqftMin'],
  ['perSqFt', 'max', 'sqftMax'],
];

/** A cost end from the URL: whole rupees 0..10^12, anything else none. */
function rupeesParam(value: string | null): number | null {
  if (value === null || !/^\d{1,13}$/.test(value)) return null;
  const n = Number(value);
  return n <= 1_000_000_000_000 ? n : null;
}

/** Reads the list state from query parameters; anything unknown falls back to the default. */
export function parseListQuery(get: (name: string) => string | null): ListQuery {
  const status = get('status');
  const sort = get('sort');
  return {
    q: (get('q') ?? '').slice(0, 200),
    status: STATUSES.includes(status as HouseStatus) ? (status as HouseStatus) : 'ALL',
    sort: SORT_KEYS.includes(sort as SortKey) ? (sort as SortKey) : 'recent',
    cost: parseCostFilter(get),
  };
}

/** Reads the cost range ends from the URL; an end that is not a whole number of rupees is ignored. */
function parseCostFilter(get: (name: string) => string | null): CostFilter {
  const cost: CostFilter = { monthly: {}, moveIn: {}, perSqFt: {} };
  for (const [range, end, name] of COST_PARAMS) {
    const v = rupeesParam(get(name));
    if (v !== null) cost[range][end] = v;
  }
  return cost;
}

/** Query parameters for a list state, for a `queryParamsHandling: 'merge'` navigation: null removes a default. */
export function listQueryParams(query: ListQuery): Record<string, string | null> {
  const q = query.q.trim();
  const params: Record<string, string | null> = {
    q: q ? q : null,
    status: query.status === 'ALL' ? null : query.status,
    sort: query.sort === 'recent' ? null : query.sort,
  };
  for (const [range, end, name] of COST_PARAMS) {
    const v = query.cost?.[range][end];
    params[name] = v == null ? null : String(v);
  }
  return params;
}

/**
 * The same list state as `routerLink` query parameters with nothing empty in them: what a link or a navigation back to
 * the list carries ("Back to map" from a bookmarked house, after Delete or Discard), so it lands on the filtered list
 * the user left rather than on the plain one.
 */
export function listReturnParams(query: ListQuery): Record<string, string> {
  const params: Record<string, string> = {};
  for (const [name, value] of Object.entries(listQueryParams(query))) {
    if (value !== null) params[name] = value;
  }
  return params;
}

/**
 * "Lowest price": monthly rents first, then sale prices, then prices with no type, each group cheapest first, and
 * houses without a price last. A ₹25,000/month rent and a ₹75,00,000 sale are not on one scale, so they are never
 * ordered as if they were.
 */
export function comparePrice(a: HouseDto, b: HouseDto): number {
  const group = priceGroup(a) - priceGroup(b);
  if (group !== 0) return group;
  return (a.price ?? 0) - (b.price ?? 0);
}

/** Groups houses for the price sort: rent first, then sale, then a price of unknown type, then no price. */
function priceGroup(h: HouseDto): number {
  if (h.price === null || h.price === undefined) return 3;
  if (h.priceType === 'RENT') return 0;
  if (h.priceType === 'SALE') return 1;
  return 2;
}

/**
 * What a query is matched against: the house's own words plus, for a linked broker, `brokerText` (the broker's name,
 * agency and fee terms, from {@link brokerSearchText}). The contact name stays: it is the broker's name copy. Room
 * names and notes are included (slice 1c), and so are the questions asked and their answers (slice 3a), the texts of
 * the area notes that reach the house (`noteTexts`, slice 4a: `notesReaching`), and the move-in notes and checklist
 * item texts (slice 5), and the floor as `floorSearchText` words it (S4b-BL-87) and, when given, as `localFloor` words it
 * in the app's language (`floorLocalSearchText`, S4b-BL-104 b), so "भूतल" finds a ground floor as "ground floor" does. Photo captions and tags are not matched: the list holds no photos (reading them would read
 * every photo of the store).
 */
export function searchText(
  h: HouseDto,
  brokerText = '',
  noteTexts: readonly string[] = [],
  localFloor?: (floor: number) => string,
): string {
  const parts = [h.label, h.address, h.street, h.locality, h.notes, h.contactName, brokerText]
    .filter((x) => !!x);
  // Room names and notes (slice 1c)
  if (h.rooms?.length) {
    for (const room of h.rooms) {
      if (room.name) parts.push(room.name);
      if (room.notes) parts.push(room.notes);
    }
  }
  // The questions asked about the house and what was answered (slice 3a)
  if (h.answers?.length) {
    for (const a of h.answers) {
      parts.push(a.text);
      if (a.answer) parts.push(a.answer);
    }
  }
  // Moving in (slice 5): the notes and the texts of the checklist items
  if (h.moveIn?.notes) parts.push(h.moveIn.notes);
  for (const item of h.moveIn?.items ?? []) parts.push(item.text);
  // The area notes that reach the house (slice 4a)
  for (const text of noteTexts) if (text) parts.push(text);
  // The floor in the same English words as on Android (S4b-BL-87)
  // and in the app's language (S4b-BL-104 b; Kotlin `HouseSearch.localFloorText`)
  if (typeof h.floor === 'number') {
    parts.push(floorSearchText(h.floor));
    if (localFloor) parts.push(localFloor(h.floor));
  }
  return parts.join(' ')
    .toLowerCase();
}

/** The broker's words a search matches: name, agency and fee terms. */
export function brokerSearchText(broker: Pick<Broker, 'name' | 'agency' | 'feeTerms'>): string {
  return [broker.name, broker.agency, broker.feeTerms].filter((x) => !!x).join(' ');
}

/** When the house last changed in ms (updated, else created); 0 if neither is readable. */
export function timeOf(h: HouseDto): number {
  const t = Date.parse(h.updatedAt ?? h.createdAt ?? '');
  return Number.isNaN(t) ? 0 : t;
}
