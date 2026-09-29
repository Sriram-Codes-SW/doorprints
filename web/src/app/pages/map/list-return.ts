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
