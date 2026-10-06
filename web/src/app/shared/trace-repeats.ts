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
 * Repeated paths (docs/11 5.27.3, docs/03 section 6.2b rows 1-2): which stretches of a walk follow a path another walk
 * took, and the alert's one-point-at-a-time test. Written from the specification, not from the Kotlin twin
 * (`app.doorprints.shared.trace.RepeatDetector`), and held to the same vector file by `trace-repeats-vectors.spec.ts`.
 * Pure and deterministic: no clock, no DOM, no I/O.
 */

import { K, TRACE, isValidLatLon, nearestOnSegment, segmentLengthM } from './trace-geo';
import type { TracePoint, TraceWalk } from './trace-geo';

export { TRACE } from './trace-geo';
export type { TracePoint, TraceWalk } from './trace-geo';

/** A repeated or shown stretch of one walk, as arc lengths in metres from the walk's first point. */
export interface Stretch {
  readonly fromM: number;
  readonly toM: number;
}

/** What `detectRepeats` says about one walk: every repeated stretch, and the part of them this walk draws. */
export interface WalkRepeats {
  readonly repeated: Stretch[];
  readonly shown: Stretch[];
}

// ---- Step 1: clean and split ----

/**
 * The walks of a flat trace, as indexes into `points` (`[0, 1, 2]` is the first three points): invalid points are
 * dropped, the rest sorted by time (stable: the input order breaks a tie), a point with the time of the one before
 * is dropped, a walk ends at a gap of `walkGapMs` or more or where both points carry a non-zero walk id and the ids
 * differ, and a walk of fewer than two points is dropped. A walk id of 0 is no id (docs/11 5.27.3 step 1).
 */
export function splitWalkIndexes(points: readonly TracePoint[]): number[][] {
  const order: number[] = [];
  points.forEach((p, i) => {
    if (Number.isFinite(p.atMs) && isValidLatLon(p.lat, p.lon)) order.push(i);
  });
  order.sort((a, b) => points[a].atMs - points[b].atMs || a - b); // Array.sort is stable; the tie break is explicit anyway
  const walks: number[][] = [];
  let current: number[] = [];
  let previous: TracePoint | null = null;
  for (const i of order) {
    const p = points[i];
    if (previous !== null) {
      if (p.atMs === previous.atMs) continue;
      const idA = previous.walkId ?? 0;
      const idB = p.walkId ?? 0;
      if (p.atMs - previous.atMs >= TRACE.walkGapMs || (idA !== 0 && idB !== 0 && idA !== idB)) {
        walks.push(current);
        current = [];
      }
    }
    current.push(i);
    previous = p;
  }
  walks.push(current);
  return walks.filter((w) => w.length >= 2);
}

/** The walks of a flat trace (see {@link splitWalkIndexes}). */
export function splitWalks(points: readonly TracePoint[]): TracePoint[][] {
  return splitWalkIndexes(points).map((walk) => walk.map((i) => points[i]));
}

// ---- Steps 2 and 3: samples ----

/** The samples of one walk, in parallel arrays: where, the arc length from the walk's first point, and the part. */
export interface Samples {
  readonly lat: number[];
  readonly lon: number[];
  readonly arc: number[];
  readonly part: number[];
}

/**
 * Builds a walk's samples one point at a time: every original point, plus points that divide each segment into
 * `max(1, floor(L / 10 + 0.5))` equal parts (step 3). A resumed point begins a new part: no segment joins it to the point
 * before, no samples, and the arc length does not grow across it.
 */
export class SampleBuilder implements Samples {
  readonly lat: number[] = [];
  readonly lon: number[] = [];
  readonly arc: number[] = [];
  readonly part: number[] = [];
  private previous: TracePoint | null = null;
  private currentArc = 0;
  private currentPart = 0;

  /** Adds the next point; returns the number of samples it added. */
  add(p: TracePoint): number {
    const before = this.lat.length;
    const prev = this.previous;
    this.previous = p;
    if (prev === null) {
      this.push(p.lat, p.lon);
    } else if (p.resumed === true) {
      this.currentPart += 1;
      this.push(p.lat, p.lon);
    } else {
      const length = segmentLengthM(prev, p);
      const n = Math.max(1, Math.floor(length / TRACE.densifyM + 0.5));
      for (let j = 1; j < n; j++) {
        const f = j / n;
        this.lat.push(prev.lat + (p.lat - prev.lat) * f);
        this.lon.push(prev.lon + (p.lon - prev.lon) * f);
        this.arc.push(this.currentArc + length * f);
        this.part.push(this.currentPart);
      }
      this.currentArc += length;
      this.push(p.lat, p.lon);
    }
    return this.lat.length - before;
  }

  /** The arc length of the last point added. */
  get arcAtLastPoint(): number {
    return this.currentArc;
  }

  private push(lat: number, lon: number): void {
    this.lat.push(lat);
    this.lon.push(lon);
    this.arc.push(this.currentArc);
    this.part.push(this.currentPart);
  }
}

/** The samples of a walk (see {@link SampleBuilder}). */
export function walkSamples(points: readonly TracePoint[]): Samples {
  const builder = new SampleBuilder();
  for (const p of points) builder.add(p);
  return builder;
}

// ---- Step 4: near, by an index or by the plain loops ----

/** One segment of a walk (between two kept points, never into a resumed point), with the arc length at its start. */
interface Segment {
  readonly walk: number;
  readonly a: TracePoint;
  readonly b: TracePoint;
  readonly arcA: number;
  readonly lengthM: number;
}

/** The nearest point of one walk to a sample: the distance and the arc length of that point along the walk. */
interface Hit {
  walk: number;
  distanceM: number;
  arcM: number;
  /** The segment the nearest point lies on (the lower id wins a tie, so the answer does not depend on the index). */
  segment: number;
}

const CELL_M = 100;
const MAX_CELLS_PER_SEGMENT = 400;

/**
 * The segments of a set of walks, and an index over them: a grid of 100 m cells (a segment sits in every cell its
 * box grown by the tolerance touches; a very long one is kept apart and always tried) plus a box test per walk. The
 * answer never depends on the index: `plain` switches it off and the tests compare the two.
 */
export class SegmentIndex {
  private readonly segments: Segment[] = [];
  private readonly walkBox: { minLat: number; maxLat: number; minLon: number; maxLon: number }[] = [];
  private readonly cells = new Map<string, number[]>();
  private readonly long: number[] = [];
  private readonly cellLat = CELL_M / K;
  private cellLon = CELL_M / K;

  /** `walks[i]` is walk `i`; a walk with fewer than two points has no segment. */
  constructor(
    walks: readonly (readonly TracePoint[])[],
    private readonly plain = false,
  ) {
    const first = walks.find((w) => w.length > 0)?.[0];
    if (first) this.cellLon = CELL_M / (K * Math.max(0.05, Math.cos((first.lat * Math.PI) / 180)));
    walks.forEach((points, w) => {
      let arc = 0;
      let minLat = Infinity;
      let maxLat = -Infinity;
      let minLon = Infinity;
      let maxLon = -Infinity;
      for (let i = 0; i < points.length; i++) {
        const p = points[i];
        minLat = Math.min(minLat, p.lat);
        maxLat = Math.max(maxLat, p.lat);
        minLon = Math.min(minLon, p.lon);
        maxLon = Math.max(maxLon, p.lon);
        if (i === 0 || p.resumed === true) continue;
        const a = points[i - 1];
        const lengthM = segmentLengthM(a, p);
        const id = this.segments.length;
        this.segments.push({ walk: w, a, b: p, arcA: arc, lengthM });
        arc += lengthM;
        if (!plain) this.register(id);
      }
      this.walkBox.push({ minLat, maxLat, minLon, maxLon });
    });
  }

  private grown(a: TracePoint, b: TracePoint): { minLat: number; maxLat: number; minLon: number; maxLon: number } {
    const worstLat = Math.min(89.9, Math.max(Math.abs(a.lat), Math.abs(b.lat)) + 0.01);
    const margin = 1.1 * TRACE.toleranceM;
    const dLat = margin / K;
    const dLon = margin / (K * Math.cos((worstLat * Math.PI) / 180));
    return {
      minLat: Math.min(a.lat, b.lat) - dLat,
      maxLat: Math.max(a.lat, b.lat) + dLat,
      minLon: Math.min(a.lon, b.lon) - dLon,
      maxLon: Math.max(a.lon, b.lon) + dLon,
    };
  }

  private register(id: number): void {
    const s = this.segments[id];
    const box = this.grown(s.a, s.b);
    const i0 = Math.floor(box.minLat / this.cellLat);
    const i1 = Math.floor(box.maxLat / this.cellLat);
    const j0 = Math.floor(box.minLon / this.cellLon);
    const j1 = Math.floor(box.maxLon / this.cellLon);
    if ((i1 - i0 + 1) * (j1 - j0 + 1) > MAX_CELLS_PER_SEGMENT) {
      this.long.push(id);
      return;
    }
    for (let i = i0; i <= i1; i++) {
      for (let j = j0; j <= j1; j++) {
        const key = `${i},${j}`;
        const list = this.cells.get(key);
        if (list) list.push(id);
        else this.cells.set(key, [id]);
      }
    }
  }

  private candidates(lat: number, lon: number): Iterable<number> {
    if (this.plain) return this.segments.keys();
    const list = this.cells.get(`${Math.floor(lat / this.cellLat)},${Math.floor(lon / this.cellLon)}`);
    if (!list) return this.long;
    return this.long.length === 0 ? list : [...list, ...this.long];
  }

  private inBox(walk: number, lat: number, lon: number): boolean {
    if (this.plain) return true;
    const b = this.walkBox[walk];
    const dLat = (1.1 * TRACE.toleranceM) / K;
    const dLon = (1.1 * TRACE.toleranceM) / (K * Math.cos((Math.min(89.9, Math.max(Math.abs(b.minLat), Math.abs(b.maxLat)) + 0.01) * Math.PI) / 180));
    return lat >= b.minLat - dLat && lat <= b.maxLat + dLat && lon >= b.minLon - dLon && lon <= b.maxLon + dLon;
  }

  /** True when the sample is within the tolerance of some walk other than `skipWalk` (inclusive). */
  isNear(lat: number, lon: number, skipWalk: number): boolean {
    const q = { lat, lon };
    for (const id of this.candidates(lat, lon)) {
      const s = this.segments[id];
      if (s.walk === skipWalk || !this.inBox(s.walk, lat, lon)) continue;
      if (nearestOnSegment(q, s.a, s.b).distanceM <= TRACE.toleranceM) return true;
    }
    return false;
  }

  /**
   * For every walk other than `skipWalk` within the tolerance of the sample, its nearest point: the smallest
   * distance over that walk's segments (the first segment on a tie) and the arc length of that point along the walk.
   */
  hits(lat: number, lon: number, skipWalk: number): Hit[] {
    const q = { lat, lon };
    const best = new Map<number, Hit>();
    for (const id of this.candidates(lat, lon)) {
      const s = this.segments[id];
      if (s.walk === skipWalk || !this.inBox(s.walk, lat, lon)) continue;
      const n = nearestOnSegment(q, s.a, s.b);
      const have = best.get(s.walk);
      if (have === undefined || n.distanceM < have.distanceM || (n.distanceM === have.distanceM && id < have.segment)) {
        best.set(s.walk, { walk: s.walk, distanceM: n.distanceM, arcM: s.arcA + n.t * s.lengthM, segment: id });
      }
    }
    return [...best.values()].filter((h) => h.distanceM <= TRACE.toleranceM);
  }
}

// ---- Steps 5 and 6: bridging and runs ----

/** Step 5: a non-near series between two near samples of one part, at most `bridgeM` apart along the walk, is filled. */
export function bridge(near: readonly boolean[], samples: Pick<Samples, 'arc' | 'part'>): boolean[] {
  const out = near.slice();
  const n = near.length;
  let i = 0;
  while (i < n) {
    if (near[i]) {
      i++;
      continue;
    }
    let e = i;
    while (e + 1 < n && !near[e + 1]) e++;
    if (i > 0 && e < n - 1 && samples.part[i - 1] === samples.part[e + 1] && samples.arc[e + 1] - samples.arc[i - 1] <= TRACE.bridgeM) {
      for (let k = i; k <= e; k++) out[k] = true;
    }
    i = e + 1;
  }
  return out;
}

/** A run of consecutive near samples of one part, as sample indexes. */
interface Run {
  readonly first: number;
  readonly last: number;
}

function runsOf(near: readonly boolean[], part: readonly number[]): Run[] {
  const runs: Run[] = [];
  let start = -1;
  for (let i = 0; i <= near.length; i++) {
    const continues = i < near.length && near[i] && start >= 0 && part[i] === part[start];
    if (continues) continue;
    if (start >= 0) runs.push({ first: start, last: i - 1 });
    start = i < near.length && near[i] ? i : -1;
  }
  return runs;
}

// ---- The detection (steps 4 to 7) ----

export interface DetectOptions {
  /** True to use the plain loops instead of the index (the tests compare the two). */
  readonly plain?: boolean;
  /** Overrides {@link TRACE.maxDetectionPoints} (a test). */
  readonly maxPoints?: number;
}

/**
 * The repeated stretches of every walk, and the part each walk draws. One result per walk in input order; a walk
 * with fewer than two points, or left out because the newest walks already hold `maxDetectionPoints` points, has none
 * (it is still drawn whole by the caller). Walks are compared against all the others, saved walks included.
 */
export function detectRepeats(walks: readonly TraceWalk[], options: DetectOptions = {}): WalkRepeats[] {
  const result: WalkRepeats[] = walks.map(() => ({ repeated: [], shown: [] }));
  const included = includedWalks(walks, options.maxPoints ?? TRACE.maxDetectionPoints);
  const includedSet = new Set(included);
  const index = new SegmentIndex(
    walks.map((w, i) => (includedSet.has(i) ? w.points : [])),
    options.plain === true,
  );

  const samples = new Map<number, Samples>();
  const rep = new Map<number, boolean[]>();
  const repeated = new Map<number, Stretch[]>();
  for (const w of included) {
    const s = walkSamples(walks[w].points);
    const near = s.lat.map((lat, k) => index.isNear(lat, s.lon[k], w));
    const bridged = bridge(near, s);
    const flags = s.lat.map(() => false);
    const stretches: Stretch[] = [];
    for (const run of runsOf(bridged, s.part)) {
      if (s.arc[run.last] - s.arc[run.first] >= TRACE.minRunM) {
        stretches.push({ fromM: s.arc[run.first], toM: s.arc[run.last] });
        for (let k = run.first; k <= run.last; k++) flags[k] = true;
      }
    }
    samples.set(w, s);
    rep.set(w, flags);
    repeated.set(w, stretches);
    result[w].repeated.push(...stretches);
  }

  // Step 7: a repeated sample is left to a newer walk that is within the tolerance and whose own nearest point lies in
  // one of its own repeated stretches (a margin of 0.5 m).
  const idOf = (w: number) => walks[w].points[0].atMs;
  const newer = (v: number, w: number) => idOf(v) > idOf(w) || (idOf(v) === idOf(w) && v > w);
  for (const w of included) {
    const s = samples.get(w)!;
    const flags = rep.get(w)!;
    const keep = s.lat.map((lat, k) => {
      if (!flags[k]) return false;
      for (const hit of index.hits(lat, s.lon[k], w)) {
        if (!newer(hit.walk, w)) continue;
        if (repeated.get(hit.walk)!.some((r) => hit.arcM >= r.fromM - 0.5 && hit.arcM <= r.toM + 0.5)) return false;
      }
      return true;
    });
    for (const run of runsOf(keep, s.part)) {
      if (run.last > run.first) result[w].shown.push({ fromM: s.arc[run.first], toM: s.arc[run.last] });
    }
  }
  return result;
}

/** The walks the detection reads: the newest first (by the walk's first point), whole walks only, within the point budget. */
function includedWalks(walks: readonly TraceWalk[], maxPoints: number): number[] {
  const order = walks
    .map((w, i) => i)
    .filter((i) => walks[i].points.length >= 2)
    .sort((a, b) => walks[b].points[0].atMs - walks[a].points[0].atMs || b - a);
  const out: number[] = [];
  let total = 0;
  for (const i of order) {
    total += walks[i].points.length;
    if (total > maxPoints) break;
    out.push(i);
  }
  return out;
}

/**
 * The polylines of a walk's stretches, as `[lon, lat]` pairs for GeoJSON: the walk's samples from the stretch's start to
 * its end (the boundary samples are part of the stretch, so the overlay meets the base line without a gap). A stretch
 * never runs across a part boundary.
 */
export function pieces(walk: TraceWalk, stretches: readonly Stretch[]): [number, number][][] {
  const s = walkSamples(walk.points);
  const eps = 1e-6;
  const out: [number, number][][] = [];
  for (const st of stretches) {
    for (let a = 0; a < s.arc.length; a++) {
      if (Math.abs(s.arc[a] - st.fromM) > eps) continue;
      let b = a;
      while (b + 1 < s.arc.length && s.part[b + 1] === s.part[a] && s.arc[b + 1] <= st.toM + eps) b++;
      if (Math.abs(s.arc[b] - st.toM) > eps) continue;
      const line: [number, number][] = [];
      for (let k = a; k <= b; k++) line.push([s.lon[k], s.lat[k]]);
      out.push(line);
      break;
    }
  }
  return out;
}

// ---- The alert (docs/11 5.27.3, "The alert's test, one point at a time") ----

/**
 * The alert's test for the walk in progress, one kept point at a time. State: `blocked` (one run, one alert) and the
 * time of the last alert (the 10 minute cooldown). Call {@link RepeatAlert.onPoint} with the live walk's kept points so
 * far and every other walk stored; it is cheap, because the live walk's near flags are kept between calls (a call
 * with one more point than the last does only the new samples).
 */
export class RepeatAlert {
  private blocked = false;
  private lastAlertAt: number | null = null;
  private othersKey: readonly TraceWalk[] | null = null;
  private othersFirstAt = NaN;
  private index: SegmentIndex | null = null;
  private builder = new SampleBuilder();
  private near: boolean[] = [];
  private seen: TracePoint[] = [];

  reset(): void {
    this.blocked = false;
    this.lastAlertAt = null;
    this.othersKey = null;
    this.index = null;
    this.restart();
  }

  private restart(): void {
    this.builder = new SampleBuilder();
    this.near = [];
    this.seen = [];
  }

  /** True when the alert rings at the newest of `live` (index 0 never rings). */
  onPoint(live: readonly TracePoint[], others: readonly TraceWalk[]): boolean {
    if (live.length < 2) return false;
    const first = live[0];
    const point = live[live.length - 1];
    if (this.othersKey !== others || this.othersFirstAt !== first.atMs || this.index === null) {
      // The walk just finished (its last point less than 30 minutes before this one began) is left out of the alert.
      const kept = others.filter((o) => o.points.length >= 2 && !(first.atMs - o.points[o.points.length - 1].atMs < TRACE.walkGapMs));
      this.index = new SegmentIndex(kept.map((o) => o.points));
      this.othersKey = others;
      this.othersFirstAt = first.atMs;
      this.restart();
    }
    this.extend(live);
    const s = this.builder;
    const near = this.near;
    const n = near.length;
    const last = n - 1;
    if (!near[last]) {
      // Unblock only when bridging could no longer join the newest point to the run behind it.
      let start = last;
      while (start > 0 && !near[start - 1]) start--;
      const behind = start - 1;
      if (behind < 0 || s.part[behind] !== s.part[last] || s.arc[last] - s.arc[behind] > TRACE.bridgeM) this.blocked = false;
      return false;
    }
    // The trailing run: back over near samples of one part, and over a non-near series that bridging fills.
    let runStart = last;
    for (;;) {
      const left = runStart - 1;
      if (left < 0) break;
      if (near[left]) {
        if (s.part[left] !== s.part[runStart]) break;
        runStart = left;
        continue;
      }
      let seriesStart = left;
      while (seriesStart > 0 && !near[seriesStart - 1]) seriesStart--;
      if (seriesStart === 0) break;
      const nearBefore = seriesStart - 1;
      if (s.part[nearBefore] === s.part[runStart] && s.arc[runStart] - s.arc[nearBefore] <= TRACE.bridgeM) runStart = nearBefore;
      else break;
    }
    const runM = s.arc[last] - s.arc[runStart];
    if (runM >= TRACE.alertMinRunM && !this.blocked && (this.lastAlertAt === null || point.atMs - this.lastAlertAt >= TRACE.alertCooldownMs)) {
      this.blocked = true;
      this.lastAlertAt = point.atMs;
      return true;
    }
    return false;
  }

  /** Brings the live walk's samples up to date with `live`: only the new points, unless the walk is not an extension. */
  private extend(live: readonly TracePoint[]): void {
    const same = (a: TracePoint, b: TracePoint) => a.lat === b.lat && a.lon === b.lon && a.atMs === b.atMs && (a.resumed ?? false) === (b.resumed ?? false);
    let ok = live.length >= this.seen.length && this.seen.length > 0 && same(this.seen[0], live[0]);
    if (ok) ok = same(this.seen[this.seen.length - 1], live[this.seen.length - 1]);
    if (!ok) this.restart();
    for (let i = this.seen.length; i < live.length; i++) {
      const before = this.builder.lat.length;
      this.builder.add(live[i]);
      for (let k = before; k < this.builder.lat.length; k++) {
        this.near.push(this.index!.isNear(this.builder.lat[k], this.builder.lon[k], -1));
      }
      this.seen.push(live[i]);
    }
  }
}
