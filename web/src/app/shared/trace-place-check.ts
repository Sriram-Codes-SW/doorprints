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
 * *Have I been here?* (docs/11 5.27.13, docs/03 section 6.2b row 12): compares one place with the person's walks
 * and says whether she walked there, when and how close. Written from the specification, not from the Kotlin twin
 * (`app.doorprints.shared.trace.PlaceCheck`), and held to the `placeChecks` section of the vector file by
 * `trace-place-check-vectors.spec.ts`. Pure: no clock, no DOM, no I/O, no network, nothing stored or logged. The
 * only caller is the check button's handler (a source test, TC-U-154).
 */

import { K, TRACE, interpolatedAtMs, isValidLatLon, nearestOnSegment, segmentLengthM } from './trace-geo';
import type { TracePoint } from './trace-geo';

/**
 * The answer in one word. WALKED: a walk passed within the tolerance. CLOSE: walks passed nearby but not that close.
 * NONE: there are walks but none nearby. EMPTY: there are no walks to compare. IMPRECISE: the location fix was too
 * inexact to use. INVALID_PLACE: the position is not on the globe.
 */
export type PlaceStatus = 'WALKED' | 'CLOSE' | 'NONE' | 'EMPTY' | 'IMPRECISE' | 'INVALID_PLACE';

/** A walk to compare: its points (already split), where it came from, and the walk id the save rule compares. */
export interface PlaceWalk {
  readonly points: readonly TracePoint[];
  readonly source: 'TRACE' | 'SAVED';
  /** The walk id (the `atMs` of its first kept point; a saved walk's `startedAt`); 0 or absent when unknown. */
  readonly walkId?: number;
}

/** One walk's answer: its nearest approach to the place. `walkIndex` is the walk's index in the input. */
export interface PlaceRow {
  readonly walkIndex: number;
  readonly distanceM: number;
  /** The time at the nearest point, interpolated along its segment. */
  readonly atMs: number;
  /** True in the `WALKED` band (within the tolerance, inclusive), false in the `CLOSE` band. */
  readonly walked: boolean;
  readonly saved: boolean;
  /** Where the nearest point is, for `matchedStretch`: the segment (from point `segment` to `segment + 1`) and the fraction along it. */
  readonly segment: number;
  readonly t: number;
}

/**
 * The result of {@link placeCheck}: the status, whether the fix was loose, the nearest distance and one row per walk
 * near the place.
 */
export interface PlaceCheckResult {
  readonly status: PlaceStatus;
  /** An accepted but loose fix (its accuracy is above the tolerance): the sheet adds a warning. Never makes `WALKED` easier. */
  readonly fuzzy: boolean;
  /** The smallest distance of any row, or null. */
  readonly nearestM: number | null;
  /** Rows newest first by `atMs`; a tie by the later input index. */
  readonly rows: readonly PlaceRow[];
}

/** Options for {@link placeCheck}, for tests only. */
export interface PlaceCheckOptions {
  /**
   * TEST ONLY. Overrides {@link TRACE.toleranceM} for the *walked* bound (the unit test that pins the inclusive bound passes
   * the computed distance); never read by the app, and it does not move the `fuzzy` flag (that uses the constant).
   */
  readonly toleranceM?: number;
  /** Switches off the cheap box rejection (the random-city test compares the two). */
  readonly noBoxRejection?: boolean;
}

const RESULT = (status: PlaceStatus): PlaceCheckResult => ({ status, fuzzy: false, nearestM: null, rows: [] });

/**
 * A trace walk whose walk id equals a saved walk's id is left out: a save that was cut between its two writes (the
 * website writes both in one transaction where it can) must not count one walk twice. Returns the indexes to keep.
 */
export function withoutSavedDuplicates(walks: readonly PlaceWalk[]): number[] {
  const saved = new Set<number>();
  for (const w of walks) if (w.source === 'SAVED' && (w.walkId ?? 0) !== 0) saved.add(w.walkId!);
  const keep: number[] = [];
  walks.forEach((w, i) => {
    if (w.source === 'TRACE' && (w.walkId ?? 0) !== 0 && saved.has(w.walkId!)) return;
    keep.push(i);
  });
  return keep;
}

/**
 * The points of a walk that can matter to `placeCheck(place, ...)`: all of them when the walk's bounding box reaches the
 * place's box (the near band grown by 1%, the same box the check rejects segments by), else just its first segment (two
 * points), which keeps the walk counted as a walk (a walk with a segment makes the answer *no*, not *empty*) without
 * keeping its points. The answer is exactly the same as with every point; the store uses it to let go of a saved walk
 * that is nowhere near the place before it reads the next one (docs/11 5.27.13: one saved walk at a time).
 */
export function pointsThatCanMatter(points: readonly TracePoint[], place: { readonly lat: number; readonly lon: number }): readonly TracePoint[] {
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLon = Infinity;
  let maxLon = -Infinity;
  for (const p of points) {
    if (p.lat < minLat) minLat = p.lat;
    if (p.lat > maxLat) maxLat = p.lat;
    if (p.lon < minLon) minLon = p.lon;
    if (p.lon > maxLon) maxLon = p.lon;
  }
  const dLat = (TRACE.nearBandM * 1.01) / K;
  const dLon = (TRACE.nearBandM * 1.01) / (K * Math.max(Math.cos((place.lat * Math.PI) / 180), 1e-9));
  const reaches = maxLat >= place.lat - dLat && minLat <= place.lat + dLat && maxLon >= place.lon - dLon && minLon <= place.lon + dLon;
  if (reaches) return points;
  const i = points.findIndex((p, at) => at > 0 && p.resumed !== true);
  return i < 0 ? points.slice(0, 2) : [points[i - 1], points[i]];
}

/** The answer for `place`: `fixAccuracyM` is given only for the *here* source (one fresh location fix). */
export function placeCheck(
  place: { readonly lat: number; readonly lon: number },
  walks: readonly PlaceWalk[],
  fixAccuracyM?: number | null,
  options: PlaceCheckOptions = {},
): PlaceCheckResult {
  // 1. Gate.
  if (!isValidLatLon(place.lat, place.lon)) return RESULT('INVALID_PLACE');
  const fix = fixAccuracyM ?? null;
  if (fix !== null && (!Number.isFinite(fix) || fix < 0 || fix > TRACE.maxFixAccuracyM)) return RESULT('IMPRECISE');
  const tolerance = options.toleranceM ?? TRACE.toleranceM;
  // `fuzzy` always compares with the walked constant (the phones do): `options.toleranceM` is a test-only override of the walked
  // bound, never of the fix's quality.
  const fuzzy = fix !== null && fix > TRACE.toleranceM;

  // 2-3. One row per walk with a segment, at its nearest point; a fragment is no walk.
  const band = TRACE.nearBandM;
  const cosLat = Math.cos((place.lat * Math.PI) / 180);
  const dLat = (band * 1.01) / K;
  const dLon = (band * 1.01) / (K * Math.max(cosLat, 1e-9));
  const boxRows = options.noBoxRejection !== true;
  const rows: PlaceRow[] = [];
  let counted = 0;
  for (const w of withoutSavedDuplicates(walks)) {
    const walk = walks[w];
    let best: { distanceM: number; segment: number; t: number } | null = null;
    let hasSegment = false;
    for (let i = 1; i < walk.points.length; i++) {
      const b = walk.points[i];
      if (b.resumed === true) continue; // no segment into a resumed point
      hasSegment = true;
      const a = walk.points[i - 1];
      if (
        boxRows &&
        ((a.lat < place.lat - dLat && b.lat < place.lat - dLat) ||
          (a.lat > place.lat + dLat && b.lat > place.lat + dLat) ||
          (a.lon < place.lon - dLon && b.lon < place.lon - dLon) ||
          (a.lon > place.lon + dLon && b.lon > place.lon + dLon))
      ) {
        continue;
      }
      const n = nearestOnSegment(place, a, b);
      if (best === null || n.distanceM < best.distanceM) best = { distanceM: n.distanceM, segment: i - 1, t: n.t };
    }
    if (!hasSegment) continue;
    counted++;
    if (best === null || best.distanceM > band) continue;
    const a = walk.points[best.segment];
    const b = walk.points[best.segment + 1];
    rows.push({
      walkIndex: w,
      distanceM: best.distanceM,
      atMs: interpolatedAtMs(a, b, best.t),
      walked: best.distanceM <= tolerance,
      saved: walk.source === 'SAVED',
      segment: best.segment,
      t: best.t,
    });
  }
  if (counted === 0) return RESULT('EMPTY');

  // 6. Order: newest first, a tie by the later input index (never by distance).
  rows.sort((x, y) => y.atMs - x.atMs || y.walkIndex - x.walkIndex);
  const nearestM = rows.length === 0 ? null : Math.min(...rows.map((r) => r.distanceM));
  // 5. Status.
  const status: PlaceStatus = rows.some((r) => r.walked) ? 'WALKED' : rows.length > 0 ? 'CLOSE' : 'NONE';
  return { status, fuzzy, nearestM, rows };
}

/**
 * The matched stretch of a `WALKED` row, for the map's halo: the walk's polyline from `checkStretchM` before to
 * `checkStretchM` after the nearest point, along the walk and within one part (never across a resumed point; shorter at a
 * walk's end), as `[lon, lat]` pairs for GeoJSON.
 */
export function matchedStretch(walk: PlaceWalk, row: Pick<PlaceRow, 'segment' | 't'>, reachM: number = TRACE.checkStretchM): [number, number][] {
  const pts = walk.points;
  // Arc length at every point within the walk (a resumed point adds none) and the part's first and last point.
  const arc: number[] = [0];
  for (let i = 1; i < pts.length; i++) arc.push(arc[i - 1] + (pts[i].resumed === true ? 0 : segmentLengthM(pts[i - 1], pts[i])));
  let first = row.segment;
  while (first > 0 && pts[first].resumed !== true) first--;
  let last = row.segment + 1;
  while (last + 1 < pts.length && pts[last + 1].resumed !== true) last++;
  const length = arc[row.segment + 1] - arc[row.segment];
  const here = arc[row.segment] + row.t * length;
  const lo = Math.max(arc[first], here - reachM);
  const hi = Math.min(arc[last], here + reachM);
  const out: [number, number][] = [];
  const add = (lat: number, lon: number) => {
    const prev = out[out.length - 1];
    if (!prev || prev[0] !== lon || prev[1] !== lat) out.push([lon, lat]);
  };
  for (let i = first + 1; i <= last; i++) {
    const a = pts[i - 1];
    const b = pts[i];
    const segLen = arc[i] - arc[i - 1];
    if (arc[i] < lo || arc[i - 1] > hi) continue;
    const s0 = segLen > 0 ? Math.max(0, (lo - arc[i - 1]) / segLen) : 0;
    const s1 = segLen > 0 ? Math.min(1, (hi - arc[i - 1]) / segLen) : 1;
    add(a.lat + (b.lat - a.lat) * s0, a.lon + (b.lon - a.lon) * s0);
    add(a.lat + (b.lat - a.lat) * s1, a.lon + (b.lon - a.lon) * s1);
  }
  return out;
}
