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

import { describe, expect, it } from 'vitest';
import { K, TRACE } from './trace-geo';
import type { TracePoint, TraceWalk } from './trace-geo';
import { RepeatAlert, SampleBuilder, bridge, detectRepeats, pieces, splitWalkIndexes, splitWalks, walkSamples } from './trace-repeats';

/** Metres to degrees on the equator at longitude 0, where the vectors live too. */
const DEG = 1 / K;
/** A point `eastM` east and `northM` north of the origin. */
const at = (eastM: number, northM: number, atMs: number, extra: Partial<TracePoint> = {}): TracePoint => ({ lat: northM * DEG, lon: eastM * DEG, atMs, ...extra });
/** A straight street of `lengthM` going north from `eastM`, a point every `stepM`, starting at `t0`. */
const street = (eastM: number, lengthM: number, t0: number, stepM = 20, fromNorthM = 0): TracePoint[] => {
  const pts: TracePoint[] = [];
  for (let d = 0; d <= lengthM + 1e-9; d += stepM) pts.push(at(eastM, fromNorthM + d, t0 + (d / stepM) * 15_000));
  return pts;
};
const walk = (key: string, points: TracePoint[]): TraceWalk => ({ key, points });
const DAY = 86_400_000;

/** mulberry32: the same numbers in any language that has 32-bit integer arithmetic. */
function rng(seed: number): () => number {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

describe('splitWalks', () => {
  it('keeps the input order for a tie in time and drops the point with the time of the one before', () => {
    const pts = [at(0, 0, 0), at(0, 20, 10_000), at(0, 25, 10_000), at(0, 40, 20_000)];
    expect(splitWalkIndexes(pts)).toEqual([[0, 1, 3]]);
  });

  it('drops a point that is not finite or not on the globe', () => {
    const pts = [at(0, 0, 0), { lat: 91, lon: 0, atMs: 1000 }, { lat: Number.NaN, lon: 0, atMs: 2000 }, { lat: 0, lon: 181, atMs: 3000 }, at(0, 20, 4000)];
    expect(splitWalkIndexes(pts)).toEqual([[0, 4]]);
  });

  it('splits at the gap rule alone when a walk id is 0, and by id when both are set and differ', () => {
    const a = [at(0, 0, 0, { walkId: 5 }), at(0, 20, 60_000, { walkId: 5 }), at(0, 40, 120_000, { walkId: 6 }), at(0, 60, 180_000, { walkId: 6 })];
    expect(splitWalkIndexes(a)).toEqual([[0, 1], [2, 3]]);
    const b = [at(0, 0, 0, { walkId: 5 }), at(0, 20, 60_000, { walkId: 0 }), at(0, 40, 120_000, { walkId: 6 })];
    expect(splitWalkIndexes(b)).toEqual([[0, 1, 2]]);
  });

  it('returns the points themselves', () => {
    const pts = street(0, 60, 0);
    expect(splitWalks(pts)).toEqual([pts]);
    expect(splitWalks([])).toEqual([]);
  });
});

describe('SampleBuilder and walkSamples', () => {
  it('puts a sample every 10 m (rounded) and every original point, with arc lengths from the first point', () => {
    const s = walkSamples([at(0, 0, 0), at(0, 26, 1000), at(0, 32, 2000)]);
    // 26 m: n = floor(2.6 + 0.5) = 3 parts, so interior samples at 8.7 m and 17.3 m; 6 m: n = max(1, 1) = 1, none.
    expect(s.arc.map((a) => Math.round(a))).toEqual([0, 9, 17, 26, 32]);
    expect(s.part).toEqual([0, 0, 0, 0, 0]);
  });

  it('gives a zero-length segment (a stay) one sample at the same arc', () => {
    const s = walkSamples([at(0, 0, 0), at(0, 0, 1000), at(0, 20, 2000)]);
    expect(s.arc.map((a) => Math.round(a))).toEqual([0, 0, 10, 20]);
  });

  it('starts a new part at a resumed point and does not grow the arc across it', () => {
    const s = walkSamples([at(0, 0, 0), at(0, 20, 1000), at(0, 5000, 2000, { resumed: true }), at(0, 5020, 3000)]);
    expect(s.part).toEqual([0, 0, 0, 1, 1, 1]);
    expect(s.arc[3]).toBeCloseTo(s.arc[2], 9); // the resumed point is a sample at the arc reached so far
    expect(Math.round(s.arc[5])).toBe(40);
  });

  it('adds one point at a time and reports how many samples each added', () => {
    const b = new SampleBuilder();
    expect(b.add(at(0, 0, 0))).toBe(1);
    expect(b.add(at(0, 40, 1000))).toBe(4);
    expect(b.arcAtLastPoint).toBeCloseTo(40, 6);
  });
});

describe('bridge', () => {
  const samples = (n: number, step: number, part: number[] = []) => ({ arc: Array.from({ length: n }, (_, i) => i * step), part: Array.from({ length: n }, (_, i) => part[i] ?? 0) });
  it('fills a series between near samples at most 30 m apart, and not one at the ends', () => {
    expect(bridge([true, false, false, true], samples(4, 10))).toEqual([true, true, true, true]); // 30 m: filled
    expect(bridge([true, false, false, false, true], samples(5, 10))).toEqual([true, false, false, false, true]); // 40 m: not
    expect(bridge([false, true, false], samples(3, 10))).toEqual([false, true, false]);
  });
  it('never bridges across a part boundary', () => {
    expect(bridge([true, false, true], samples(3, 10, [0, 1, 1]))).toEqual([true, false, true]);
    expect(bridge([true, false, true], samples(3, 10, [0, 0, 1]))).toEqual([true, false, true]);
  });
  it('does not let a filled series bridge further', () => {
    const near = [true, false, false, true, false, false, true];
    expect(bridge(near, samples(7, 10))).toEqual([true, true, true, true, true, true, true]);
    // Two series of 30 m each are filled from the original flags; a 40 m one next to a filled one stays open.
    expect(bridge([true, false, true, false, false, false, true], samples(7, 10))).toEqual([true, true, true, false, false, false, true]);
  });
});

describe('detectRepeats', () => {
  it('returns one empty result per walk when there is nothing to compare', () => {
    expect(detectRepeats([])).toEqual([]);
    expect(detectRepeats([walk('a', street(0, 200, 0))])).toEqual([{ repeated: [], shown: [] }]);
  });

  it('marks a street walked twice, drawn by the newer walk only, in either input order', () => {
    const older = walk('o', street(0, 200, 0));
    const newer = walk('n', street(5, 200, DAY));
    for (const input of [[older, newer], [newer, older]]) {
      const [a, b] = detectRepeats(input);
      const [o, n] = input[0] === older ? [a, b] : [b, a];
      expect(o.repeated).toHaveLength(1);
      expect(n.repeated).toHaveLength(1);
      expect(o.shown).toEqual([]);
      expect(n.shown).toHaveLength(1);
      expect(n.shown[0].toM - n.shown[0].fromM).toBeGreaterThan(190);
    }
  });

  it('puts a walk 24.5 m away on the same path and one 25.5 m away on another (the tolerance is 25 m, inclusive)', () => {
    const base = walk('a', street(0, 200, 0));
    expect(detectRepeats([base, walk('b', street(24.5, 200, DAY))])[1].repeated).toHaveLength(1);
    expect(detectRepeats([base, walk('b', street(25.5, 200, DAY))])[1].repeated).toHaveLength(0);
  });

  it('does not mark a street walked out and back in one walk', () => {
    const there = street(0, 200, 0);
    const back = street(0, 200, 0).reverse().map((p, i) => ({ ...p, atMs: 200_000 + i * 15_000 }));
    expect(detectRepeats([walk('w', [...there, ...back])])).toEqual([{ repeated: [], shown: [] }]);
  });

  it('a shared stretch of 80 m or more counts for the short walk and one of 40 m does not (a junction is not a repeat)', () => {
    const a = walk('a', street(0, 300, 0));
    const [, r90] = detectRepeats([a, walk('b', street(2, 90, DAY, 15, 100))]);
    expect(r90.repeated.length).toBe(1);
    const [, r40] = detectRepeats([a, walk('b', street(2, 40, DAY, 10, 100))]);
    expect(r40.repeated).toEqual([]);
  });

  it('reads the same result with the index as with the plain loops on a random city of 60 walks', () => {
    const rand = rng(20261006);
    const walks: TraceWalk[] = [];
    for (let w = 0; w < 60; w++) {
      // A walk goes along 1 to 3 streets of a 300 m grid (so many share a street), with up to 6 m of noise.
      const pts: TracePoint[] = [];
      let east = Math.floor(rand() * 6) * 300;
      let north = Math.floor(rand() * 6) * 300;
      let t = w * 3 * DAY;
      const legs = 1 + Math.floor(rand() * 3);
      pts.push(at(east, north, t));
      for (let l = 0; l < legs; l++) {
        const horizontal = rand() < 0.5;
        const dir = rand() < 0.5 ? -1 : 1;
        const steps = 5 + Math.floor(rand() * 20);
        for (let i = 0; i < steps; i++) {
          if (horizontal) east += dir * 20;
          else north += dir * 20;
          t += 15_000;
          pts.push(at(east + (rand() - 0.5) * 12, north + (rand() - 0.5) * 12, t));
        }
      }
      walks.push(walk(`t:${w}`, pts));
    }
    const indexed = detectRepeats(walks);
    const plain = detectRepeats(walks, { plain: true });
    expect(indexed).toEqual(plain);
    const repeatedCount = indexed.reduce((n, r) => n + r.repeated.length, 0);
    expect(repeatedCount, 'the city must hold repeats, or the comparison proves nothing').toBeGreaterThan(10);
  });

  it('reads the newest walks first and whole walks only, up to the point budget', () => {
    const a = walk('old', street(0, 200, 0));
    const b = walk('mid', street(3, 200, DAY));
    const c = walk('new', street(6, 200, 2 * DAY));
    const budget = (c.points.length + b.points.length);
    const res = detectRepeats([a, b, c], { maxPoints: budget });
    expect(res[0]).toEqual({ repeated: [], shown: [] }); // the oldest is over the budget: not compared (still drawn by the caller)
    expect(res[1].repeated).toHaveLength(1);
    expect(res[2].repeated).toHaveLength(1);
    expect(detectRepeats([a, b, c], { maxPoints: budget - 1 }).every((r) => r.repeated.length === 0)).toBe(true);
  });

  it('draws no segment across a resumed point', () => {
    const a = walk('a', street(0, 300, 0));
    // The second walk goes up the street, is paused, and resumes 1 km away: the straight line between is never walked.
    const b = walk('b', [...street(2, 100, DAY), at(2, 1100, DAY + 10 * 60_000, { resumed: true }), at(2, 1120, DAY + 10 * 60_000 + 15_000)]);
    const [ra, rb] = detectRepeats([a, b]);
    expect(rb.repeated).toHaveLength(1);
    expect(rb.repeated[0].toM).toBeLessThan(120);
    expect(ra.repeated.every((s) => s.toM <= 130)).toBe(true);
  });
});

describe('pieces', () => {
  it('cuts the stretch out of the walk as [lon, lat] pairs that meet the base line at both ends', () => {
    const w = walk('w', street(0, 200, 0));
    const lines = pieces(w, [{ fromM: 40, toM: 120 }]);
    expect(lines).toHaveLength(1);
    expect(lines[0][0]).toEqual([0, 40 * DEG]);
    expect(lines[0][lines[0].length - 1][1]).toBeCloseTo(120 * DEG, 12);
    expect(pieces(w, [])).toEqual([]);
    expect(pieces(w, [{ fromM: 41, toM: 120 }])).toEqual([]); // not on a sample boundary: nothing invented
  });
});

describe('RepeatAlert', () => {
  const walked = walk('t:old', street(0, 400, -DAY));
  /** The live walk: up the same street from 50 m south of it, a kept point every 20 m. */
  const live = (n: number, east = 0, t0 = 0) => street(east, 20 * (n - 1), t0, 20, -50);
  const ringsAt = (points: TracePoint[], others: TraceWalk[], alert = new RepeatAlert()): number[] => {
    const out: number[] = [];
    for (let i = 0; i < points.length; i++) if (alert.onPoint(points.slice(0, i + 1), others)) out.push(i);
    return out;
  };

  it('rings once, at the first point where the run behind it reaches 100 m', () => {
    // The live street is near from its 0 m (50 m up the walked street): samples near from point 3 (north 10 m).
    const rings = ringsAt(live(30), [walked]);
    expect(rings).toHaveLength(1);
    expect(rings[0]).toBeGreaterThan(5);
  });

  it('hands over the length of the run it rang for (100 m or more), and 0 before any alert and after reset', () => {
    const alert = new RepeatAlert();
    expect(alert.lastRunM).toBe(0);
    ringsAt(live(30), [walked], alert);
    expect(alert.lastRunM).toBeGreaterThanOrEqual(TRACE.alertMinRunM);
    expect(alert.lastRunM).toBeLessThan(TRACE.alertMinRunM + 40);
    alert.reset();
    expect(alert.lastRunM).toBe(0);
  });

  it('never rings for the first point or for a path nobody walked', () => {
    expect(ringsAt(live(1), [walked])).toEqual([]);
    expect(ringsAt(live(30, 200), [walked])).toEqual([]);
  });

  it('gives the same answers incrementally as from scratch at every point (the cache is not a different rule)', () => {
    const points = live(30);
    const others = [walked];
    const cached = new RepeatAlert();
    const rang: boolean[] = [];
    for (let i = 0; i < points.length; i++) rang.push(cached.onPoint(points.slice(0, i + 1), others));
    // Without the cache: a new alert replays the whole walk up to this point and reports the last answer.
    const replay: boolean[] = [];
    for (let i = 0; i < points.length; i++) {
      const fresh = new RepeatAlert();
      let last = false;
      for (let j = 0; j <= i; j++) last = fresh.onPoint(points.slice(0, j + 1), others);
      replay.push(last);
    }
    expect(rang).toEqual(replay);
  });

  it('leaves out a walk that ended less than 30 minutes before this one began, and counts one at exactly 30 minutes', () => {
    const justEnded = walk('t:before', street(0, 400, -10 * 60_000));
    expect(ringsAt(live(30), [justEnded])).toEqual([]);
    const half = walk('t:half', street(0, 400, -TRACE.walkGapMs - 15_000 * 20));
    // Its last point is exactly 30 minutes before the live walk's first point: it counts.
    const lastAt = half.points[half.points.length - 1].atMs;
    expect(0 - lastAt).toBe(TRACE.walkGapMs);
    expect(ringsAt(live(30), [half]).length).toBe(1);
  });

  it('rings again only after leaving the path for more than 30 m and 10 minutes pass', () => {
    const alert = new RepeatAlert();
    // Up the street (ring), 80 m off it to the east, back onto it 11 minutes later and on.
    const first = live(20);
    const away = [at(100, 330, 20 * 15_000), at(100, 360, 21 * 15_000), at(100, 390, 22 * 15_000)];
    const back = street(0, 360, 12 * 60_000, 20, 40).map((p, i) => ({ ...p, atMs: 12 * 60_000 + i * 15_000 }));
    const all = [...first, ...away, ...back];
    const rings: number[] = [];
    for (let i = 0; i < all.length; i++) if (alert.onPoint(all.slice(0, i + 1), [walked])) rings.push(i);
    expect(rings).toHaveLength(2);
  });

  it('reset forgets the block and the cooldown', () => {
    const alert = new RepeatAlert();
    const points = live(30);
    expect(ringsAt(points, [walked], alert)).toHaveLength(1);
    alert.reset();
    expect(ringsAt(points, [walked], alert)).toHaveLength(1);
  });
});
