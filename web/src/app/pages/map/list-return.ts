import { Injectable, signal } from '@angular/core';
import { DEFAULT_LIST_QUERY, ListQuery, listReturnParams } from './map-list';

/**
 * The house list's last search, filter and sort, for the ways back to the list that are **not** the browser's Back:
 * the house page's "Back to map" link when it was opened from a bookmark or after a reload, and the navigation after
 * Delete or Discard. MapPage writes it whenever the list state changes (and when it opens), so an installed iOS app,
 * which has no Back button, still returns to "Shortlisted, lowest price" and not to the whole list.
 *
 * Memory only: a new tab or a reload starts from the list's defaults, as the URL does.
 */
@Injectable({ providedIn: 'root' })
export class ListReturn {
  /** Query parameters for `[queryParams]` or `router.navigate`, without the defaults. */
  readonly queryParams = signal<Record<string, string>>(listReturnParams(DEFAULT_LIST_QUERY));

  remember(query: ListQuery): void {
    this.queryParams.set(listReturnParams(query));
  }
}
