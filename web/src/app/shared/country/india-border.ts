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

// India's outline as the Government of India depicts it (S4b-BL-174c), bundled as text like the held areas' polygon; the
// same rings as the apps' IndiaOutlineData.kt (built together by web/scripts/geo/build_in_outline.py).
import outlineText from '../india-outline.geojson' with { loader: 'text' };

type Ring = [number, number][];
let rings: Ring[] | null = null;

/** The rings of India's outline (longitude, latitude), read from the bundled file on first use. */
function outline(): Ring[] {
  rings ??= (JSON.parse(outlineText) as { features: { geometry: { coordinates: Ring[][] } }[] }).features[0].geometry.coordinates.map((p) => p[0]);
  return rings;
}

/**
 * Whether a point is in India as the Government of India depicts it (S4b-BL-174c, ADR-22): inside the outline built from the
 * repository's own boundary data, the test of the apps' `IndiaOutline`. India's bounding box is not it: Dhaka, Kathmandu and
 * Colombo are inside the box. A point within a few hundred metres of the line may fall on either side.
 *
 * This is the boundary test itself, moved out of `core/place-search.ts` unchanged so the India pack can point at it (ADR-38);
 * the country pack never carries the outline.
 */
export function inIndia(lat: number, lon: number): boolean {
  return outline().some((ring) => {
    let hit = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const [xi, yi] = ring[i];
      const [xj, yj] = ring[j];
      if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) hit = !hit;
    }
    return hit;
  });
}
