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
 * The one plane under the path trace (docs/11 5.27.3 step 2, 5.27.13, docs/03 section 6.2b row 1): the repeat
 * detection (`trace-repeats.ts`) and the place check (`trace-place-check.ts`) both measure on it, so a distance
 * means the same in both. Pure and deterministic: no clock, no DOM, no I/O.
 *
 * All distances are metres on a local flat plane: for a point `q` and a point or segment around it,
 * `x = (lon - q.lon) * cos(rad(q.lat)) * K`, `y = (lat - q.lat) * K`, `K = EARTH_RADIUS_M * pi / 180`. Between two
 * points of a walk (a segment's length, an arc length) the latitude used is the mean of the two.
 */

/** A kept fix: where, when, which walk (0 is no id) and whether it resumes after a long hidden pause (web only). */
export interface TracePoint {
  readonly lat: number;
  readonly lon: number;
  /** Epoch milliseconds. */
  readonly atMs: number;
  /** The `atMs` of the walk's first kept point; 0 is no id (rows from before an id was known): only the gap rule splits. */
  readonly walkId?: number;
  /** True for the first point after the page was hidden for more than {@link TRACE.pauseSplitMs}: no segment joins it to the point before. */
  readonly resumed?: boolean;
}

/** A walk: one run of points, already split (`splitWalks`); `key` is `t:<walkId>` (the 30-day trace) or `s:<savedId>`. */
export interface TraceWalk {
  readonly key: string;
  readonly points: readonly TracePoint[];
}

/** Every constant the path trace reads. The first twelve are in the vector file's `constants` block (the drift test). */
export const TRACE = {
  earthRadiusM: 6_371_000,
  /** Sample spacing for matching. */
  densifyM: 10,
  /** Two walks are on one path when within this of each other (inclusive). */
  toleranceM: 25,
  /** A run may be broken by one bad fix: a non-near series between two near samples at most this far apart is filled. */
  bridgeM: 30,
  /** A junction is not a repeat: a run shorter than this is not a repeated stretch. */
  minRunM: 80,
  /** A gap of this or more between two points starts a new walk. */
  walkGapMs: 1_800_000,
  alertMinRunM: 100,
  alertCooldownMs: 600_000,
  maxWalkPoints: 5_000,
  maxDetectionPoints: 20_000,
  /** The place check only: a walk this near is reported as close, never as walked. */
  nearBandM: 50,
  /** The place check only: a `here` fix worse than this is refused (the Hunt gate). */
  maxFixAccuracyM: 50,
  /** Website only, in the recorder, not in the detection: a page hidden longer than this marks the next point resumed. */
  pauseSplitMs: 300_000,
  /** The place check, drawing only: the highlighted stretch runs this far each side of the nearest point. */
  checkStretchM: 60,
  /** The recorder's thinning (a fix is kept 20 m from the last kept one, or 5 minutes after it). */
  thinDistanceM: 20,
  thinGapMs: 300_000,
} as const;

/** The constants the vector file's `constants` block carries (everything the algorithm or its callers read). */
export const VECTOR_CONSTANT_KEYS = [
  'earthRadiusM',
  'densifyM',
  'toleranceM',
  'bridgeM',
  'minRunM',
  'walkGapMs',
  'alertMinRunM',
  'alertCooldownMs',
  'maxWalkPoints',
  'maxDetectionPoints',
  'nearBandM',
  'maxFixAccuracyM',
] as const;

/** Metres per degree of latitude on the sphere of {@link TRACE.earthRadiusM}. */
export const K = (TRACE.earthRadiusM * Math.PI) / 180;

const RAD = Math.PI / 180;

/** True for a latitude and longitude that are finite and on the globe. */
export function isValidLatLon(lat: number, lon: number): boolean {
  return Number.isFinite(lat) && Number.isFinite(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
}

/** `point` on the plane centred on `place`, in metres. */
export function localXY(place: { lat: number; lon: number }, point: { lat: number; lon: number }): { x: number; y: number } {
  return { x: (point.lon - place.lon) * Math.cos(place.lat * RAD) * K, y: (point.lat - place.lat) * K };
}

/** The length of the segment between two points of a walk, on the plane of their mean latitude. */
export function segmentLengthM(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const dx = (b.lon - a.lon) * Math.cos(((a.lat + b.lat) / 2) * RAD) * K;
  const dy = (b.lat - a.lat) * K;
  return Math.sqrt(dx * dx + dy * dy);
}

/** Where the nearest point of a segment is: its distance from the place and the clamped fraction `t` from `a` to `b`. */
export interface Nearest {
  readonly distanceM: number;
  readonly t: number;
}

/**
 * The distance from `q` to the segment `a`-`b`, the segment clamped at its ends (a round cap at each end), on the
 * plane centred on `q`; and the fraction `t` (0 at `a`, 1 at `b`) of the nearest point. A segment of length 0 (a
 * stay) takes `t = 0` with no division by zero.
 */
export function nearestOnSegment(
  q: { lat: number; lon: number },
  a: { lat: number; lon: number },
  b: { lat: number; lon: number },
): Nearest {
  const c = Math.cos(q.lat * RAD) * K;
  const ax = (a.lon - q.lon) * c;
  const ay = (a.lat - q.lat) * K;
  const dx = (b.lon - a.lon) * c;
  const dy = (b.lat - a.lat) * K;
  const len2 = dx * dx + dy * dy;
  let t = 0;
  if (len2 > 0) {
    t = -(ax * dx + ay * dy) / len2;
    if (t < 0) t = 0;
    else if (t > 1) t = 1;
  }
  const px = ax + t * dx;
  const py = ay + t * dy;
  return { distanceM: Math.sqrt(px * px + py * py), t };
}

/** The distance from `q` to the segment `a`-`b` (clamped at both ends). */
export function distanceToSegmentM(
  q: { lat: number; lon: number },
  a: { lat: number; lon: number },
  b: { lat: number; lon: number },
): number {
  return nearestOnSegment(q, a, b).distanceM;
}

/** The time at fraction `t` of the way from `a` to `b`: `a.atMs + floor(t * (b.atMs - a.atMs) + 0.5)`. */
export function interpolatedAtMs(a: { atMs: number }, b: { atMs: number }, t: number): number {
  return a.atMs + Math.floor(t * (b.atMs - a.atMs) + 0.5);
}

/** The great-circle distance in metres (haversine, the same radius), for the recorder's 20 m thinning. */
export function haversineM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const dLat = (lat2 - lat1) * RAD;
  const dLon = (lon2 - lon1) * RAD;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * RAD) * Math.cos(lat2 * RAD) * Math.sin(dLon / 2) ** 2;
  return 2 * TRACE.earthRadiusM * Math.asin(Math.min(1, Math.sqrt(h)));
}
