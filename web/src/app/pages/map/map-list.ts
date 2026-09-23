import { HouseDto, HouseStatus, STATUSES } from '../../core/models';

/**
 * The house list's search, status filter and sort, kept apart from the map page so they can be unit tested without
 * MapLibre, and written to the URL (`/?q=…&status=…&sort=…`): Back from a house then comes back to the same
 * filtered list, and a filtered view can be bookmarked or shared (UX audit 2026-09-23).
 */
export type SortKey = 'recent' | 'score' | 'price';
export type StatusFilter = HouseStatus | 'ALL';

export const SORT_KEYS: readonly SortKey[] = ['recent', 'score', 'price'];

export interface ListQuery {
  q: string;
  status: StatusFilter;
  sort: SortKey;
}

/** The defaults, which are left out of the URL. */
export const DEFAULT_LIST_QUERY: Readonly<ListQuery> = { q: '', status: 'ALL', sort: 'recent' };

/** Reads the list state from query parameters; anything unknown falls back to the default. */
export function parseListQuery(get: (name: string) => string | null): ListQuery {
  const status = get('status');
  const sort = get('sort');
  return {
    q: (get('q') ?? '').slice(0, 200),
    status: STATUSES.includes(status as HouseStatus) ? (status as HouseStatus) : 'ALL',
    sort: SORT_KEYS.includes(sort as SortKey) ? (sort as SortKey) : 'recent',
  };
}

/** Query parameters for a list state, for a `queryParamsHandling: 'merge'` navigation: null removes a default. */
export function listQueryParams(query: ListQuery): Record<string, string | null> {
  const q = query.q.trim();
  return {
    q: q ? q : null,
    status: query.status === 'ALL' ? null : query.status,
    sort: query.sort === 'recent' ? null : query.sort,
  };
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

function priceGroup(h: HouseDto): number {
  if (h.price === null || h.price === undefined) return 3;
  if (h.priceType === 'RENT') return 0;
  if (h.priceType === 'SALE') return 1;
  return 2;
}

export function searchText(h: HouseDto): string {
  return [h.label, h.address, h.street, h.locality, h.notes, h.contactName]
    .filter((x) => !!x)
    .join(' ')
    .toLowerCase();
}

export function timeOf(h: HouseDto): number {
  const t = Date.parse(h.updatedAt ?? h.createdAt ?? '');
  return Number.isNaN(t) ? 0 : t;
}
