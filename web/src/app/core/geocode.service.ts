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

import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map, of, switchMap, timer } from 'rxjs';
import { NOMINATIM_SEARCH, RequestThrottle, placeOf, searchParams } from './place-search';
import type { FoundPlace } from './place-search';

/** The address text found for a position; every part is null when OpenStreetMap has none. */
export interface ReverseGeocode {
  address: string | null;
  street: string | null;
  locality: string | null;
}

interface NominatimResponse {
  display_name?: string;
  address?: Record<string, string | undefined>;
}

/**
 * Reverse geocoding and the locality search (S4b-BL-83) via OpenStreetMap Nominatim. Their usage policy allows at most
 * 1 request/second, so both are only ever called from an explicit button press, and one throttle spaces the two.
 */
@Injectable({ providedIn: 'root' })
export class GeocodeService {
  private readonly http = inject(HttpClient);
  private readonly throttle = new RequestThrottle();

  /** The request, once its one-a-second slot has come. */
  private spaced<T>(request: () => Observable<T>): Observable<T> {
    return defer(() => timer(this.throttle.next())).pipe(switchMap(request));
  }

  /** Where `place` (a locality, or an address) is in India, or `null` when Nominatim knows none (or the name is blank). */
  search(place: string, language: string): Observable<FoundPlace | null> {
    const query = searchParams(place, language);
    if (!query) return of(null);
    const params = new HttpParams({ fromObject: query });
    return this.spaced(() => this.http.get<unknown>(NOMINATIM_SEARCH, { params })).pipe(map(placeOf));
  }

  /**
   * The address, street and locality at a position, taken from the first of the usual OpenStreetMap address parts that
   * is present.
   */
  reverse(lat: number, lon: number): Observable<ReverseGeocode> {
    const params = new HttpParams().set('format', 'jsonv2').set('lat', lat).set('lon', lon);
    return this.spaced(() => this.http.get<NominatimResponse>('https://nominatim.openstreetmap.org/reverse', { params })).pipe(
      map((r) => {
        const a = r.address ?? {};
        return {
          address: r.display_name ?? null,
          street: a['road'] ?? a['pedestrian'] ?? a['residential'] ?? null,
          locality: a['suburb'] ?? a['neighbourhood'] ?? a['city_district'] ?? a['city'] ?? a['town'] ?? a['village'] ?? null,
        };
      }),
    );
  }
}
