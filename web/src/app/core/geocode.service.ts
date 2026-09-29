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
import { Observable, map } from 'rxjs';

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
 * Reverse geocoding via OpenStreetMap Nominatim. Their usage policy allows at most 1 request/second,
 * so this is only ever called from an explicit button press.
 */
@Injectable({ providedIn: 'root' })
export class GeocodeService {
  private readonly http = inject(HttpClient);

  reverse(lat: number, lon: number): Observable<ReverseGeocode> {
    const params = new HttpParams().set('format', 'jsonv2').set('lat', lat).set('lon', lon);
    return this.http.get<NominatimResponse>('https://nominatim.openstreetmap.org/reverse', { params }).pipe(
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
