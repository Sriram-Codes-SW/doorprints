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


/*
 * The Survey of India's lines as the map draws them (S4b-BL-114): the polyline decoder, the GeoJSON it makes, the
 * checksum the parity test pins and the once-per-page loader. Split from india-boundaries.ts to keep that file
 * within the size budget (tools/check-size-budget.mjs); india-boundaries.ts re-exports all of it.
 */

/** The features of a GeoJSON FeatureCollection's text, or null when it is not one. */
export function featuresOf(text: string): unknown[] | null {
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    return null;
  }
  const features = (root as { type?: unknown; features?: unknown } | null)?.features;
  if ((root as { type?: unknown } | null)?.type !== 'FeatureCollection' || !Array.isArray(features)) return null;
  return features;
}

/** A GeoJSON FeatureCollection of the Survey of India's lines as the map draws them ({@link soiBoundaryGeoJson}). */
export interface SoiFeatureCollection {
  type: 'FeatureCollection';
  features: {
    type: 'Feature';
    properties: { kind: 'claim' | 'state'; state: string };
    geometry: { type: 'LineString'; coordinates: [number, number][] };
  }[];
}

/** No Survey of India line yet: the source's data until {@link loadSoiBoundary} has read the file. */
export const NO_SOI_LINES: SoiFeatureCollection = Object.freeze({ type: 'FeatureCollection', features: [] }) as SoiFeatureCollection;

/**
 * Google's encoded polyline algorithm, decoded at a precision of 1e-7 degree (`in-boundaries-soi.json`, written by
 * web/scripts/geo/build_in_boundaries_soi.py): latitude then longitude, each a zigzag-encoded delta in 5-bit chunks
 * offset by 63. Gives [longitude, latitude] pairs, each the 1e-7 integer divided by 1e7, which is the double nearest
 * to the 7-decimal value the build wrote, so every vertex is the Survey of India's as published. Arithmetic, not
 * 32-bit bitwise operators: a longitude delta can reach 3.6e9. Throws on a character outside the alphabet or a
 * truncated value. Android's `SoiPolyline.decode` (common Kotlin); the parity test pins both to the same counts and
 * checksums.
 */
export function decodePolyline7(encoded: string): [number, number][] {
  const out: [number, number][] = [];
  let i = 0;
  let lat = 0;
  let lon = 0;
  const next = (): number => {
    let result = 0;
    let factor = 1;
    for (;;) {
      if (i >= encoded.length) throw new Error('polyline7: truncated value');
      const b = encoded.charCodeAt(i++) - 63;
      if (b < 0 || b > 63) throw new Error(`polyline7: character ${JSON.stringify(encoded[i - 1])} out of range`);
      result += (b & 0x1f) * factor;
      factor *= 32;
      if (b < 0x20) break;
      if (factor > 2 ** 45) throw new Error('polyline7: value too long');
    }
    return result % 2 === 1 ? -(result + 1) / 2 : result / 2;
  };
  while (i < encoded.length) {
    lat += next();
    lon += next();
    out.push([lon / 1e7, lat / 1e7]);
  }
  return out;
}

/**
 * The Survey of India's file (`geo/in-boundaries-soi.json`) as the map's GeoJSON: one LineString per run, with its
 * kind (`claim` or `state`) and state, the vertices decoded ({@link decodePolyline7}) and none added, moved or
 * removed. Null when the text is not that file (not JSON, a run of another kind, a run whose decoded vertex count
 * differs from the `vertices` it states, or one of fewer than two), so a broken or partial file never reaches the
 * map. Android's `SoiPolyline.geoJson`.
 */
export function soiBoundaryGeoJson(text: string): SoiFeatureCollection | null {
  const features = featuresOf(text);
  if (!features || features.length === 0) return null;
  const out: SoiFeatureCollection['features'] = [];
  for (const f of features) {
    const p = (f as { properties?: Record<string, unknown> } | null)?.properties;
    const kind = p?.['kind'];
    const encoded = p?.['polyline7'];
    if ((kind !== 'claim' && kind !== 'state') || typeof encoded !== 'string') return null;
    let coordinates: [number, number][];
    try {
      coordinates = decodePolyline7(encoded);
    } catch {
      return null;
    }
    if (coordinates.length < 2 || coordinates.length !== p?.['vertices']) return null;
    out.push({
      type: 'Feature',
      properties: { kind, state: typeof p?.['state'] === 'string' ? p['state'] : '' },
      geometry: { type: 'LineString', coordinates },
    });
  }
  return { type: 'FeatureCollection', features: out };
}

/**
 * Adler-style sums (mod 2^31 - 1) over the vertices of `lines` as 1e-7 degree integers, longitude then latitude, in
 * order: [s1, s2]. The parity check of the decoders (web/scripts/geo/test_build_in_boundaries.py and Android's
 * `SoiPolyline.checksum` compute the same); both stay below 2^32, so plain numbers are exact.
 */
export function soiChecksum(lines: readonly (readonly (readonly number[])[])[]): [number, number] {
  const m = 2147483647;
  let s1 = 0;
  let s2 = 0;
  for (const line of lines) {
    for (const [lon, lat] of line) {
      for (const v of [Math.round(lon * 1e7), Math.round(lat * 1e7)]) {
        s1 = (s1 + (((v % m) + m) % m)) % m;
        s2 = (s2 + s1) % m;
      }
    }
  }
  return [s1, s2];
}

const soiPromises = new Map<string, Promise<SoiFeatureCollection | null>>();
const soiLoaded = new Map<string, SoiFeatureCollection>();

/**
 * The Survey of India's lines from `url`, fetched and decoded once per page ({@link soiBoundaryGeoJson}); null when
 * the file cannot be read or is not that file (then the next call tries again). Same-origin and precached by sw.js,
 * so it also loads offline once the app has been installed or opened.
 */
export function loadSoiBoundary(
  url: string,
  fetchFn: (url: string) => Promise<{ ok: boolean; text(): Promise<string> }> = (u) => fetch(u),
): Promise<SoiFeatureCollection | null> {
  let pending = soiPromises.get(url);
  if (!pending) {
    pending = fetchFn(url)
      .then((r) => (r.ok ? r.text() : null))
      .then((text) => (text === null ? null : soiBoundaryGeoJson(text)))
      .catch(() => null)
      .then((fc) => {
        if (fc) soiLoaded.set(url, fc);
        else soiPromises.delete(url);
        return fc;
      });
    soiPromises.set(url, pending);
  }
  return pending;
}

/** The lines {@link loadSoiBoundary} has already read from `url`, or undefined. */
export function loadedSoiBoundary(url: string): SoiFeatureCollection | undefined {
  return soiLoaded.get(url);
}

/** Forgets what {@link loadSoiBoundary} has read (for the specs). */
export function forgetSoiBoundary(): void {
  soiPromises.clear();
  soiLoaded.clear();
}
