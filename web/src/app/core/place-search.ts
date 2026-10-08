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

/**
 * Looking up a locality by name for a shared listing's "Where is it?" (S4b-BL-83, docs/11 5.29 item 4), the parts that
 * need no network: the Nominatim `/search` request, the one-request-a-second rule of its usage policy, and reading the
 * answer. `GeocodeService` makes the call, only when the person taps *Find*. No key; the browser's `Referer` (the app's
 * origin, `strict-origin-when-cross-origin`) identifies the app to Nominatim, as its policy asks of a web page.
 */

export const NOMINATIM_SEARCH = 'https://nominatim.openstreetmap.org/search';

/** A place a lookup found: where to put the pin, and the name Nominatim gives it. */
export interface FoundPlace {
  lat: number;
  lon: number;
  label: string | null;
}

/** The longest name sent, in characters: a locality, or at most an address line. */
export const MAX_QUERY = 200;

/**
 * The query parameters of one search for `place`, in India only (`countrycodes=in`), one answer, in the app's language.
 * `null` for a blank name: nothing is sent.
 */
export function searchParams(place: string, language: string): Record<string, string> | null {
  // Cut on whole characters: a slice by UTF-16 unit can leave half an emoji (a forwarded address has them), which makes the
  // request's encoding throw.
  const q = Array.from(place.replace(/\s+/g, ' ').trim()).slice(0, MAX_QUERY).join('').trim();
  if (q === '') return null;
  return { q, format: 'jsonv2', limit: '1', countrycodes: 'in', 'accept-language': language };
}

/** The first usable answer of a `/search` response (an array of `{lat, lon, display_name}` as strings), or `null`. */
export function placeOf(response: unknown): FoundPlace | null {
  if (!Array.isArray(response)) return null;
  for (const item of response as Record<string, unknown>[]) {
    if (typeof item !== 'object' || item === null) continue;
    const lat = Number(item['lat']);
    const lon = Number(item['lon']);
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
    if (lat === 0 && lon === 0) continue;
    const label = typeof item['display_name'] === 'string' && item['display_name'] ? item['display_name'] : null;
    return { lat, lon, label };
  }
  return null;
}

/**
 * At most one request a second (Nominatim's usage policy), for the reverse lookup and the search together: {@link next}
 * says how long to wait before the next request may go, and books that slot.
 */
export class RequestThrottle {
  private last = Number.NEGATIVE_INFINITY;

  constructor(
    private readonly gapMs = 1000,
    private readonly now: () => number = () => Date.now(),
  ) {}

  /** The wait in milliseconds before a request may be sent; the slot is taken. */
  next(): number {
    const t = this.now();
    const at = Math.max(t, this.last + this.gapMs);
    this.last = at;
    return at - t;
  }
}
