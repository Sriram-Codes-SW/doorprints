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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { K, TRACE, distanceToSegmentM } from './trace-geo';
import type { TracePoint } from './trace-geo';
import { matchedStretch, placeCheck, withoutSavedDuplicates } from './trace-place-check';
import type { PlaceWalk } from './trace-place-check';

const DEG = 1 / K;
const at = (eastM: number, northM: number, atMs: number, extra: Partial<TracePoint> = {}): TracePoint => ({ lat: northM * DEG, lon: eastM * DEG, atMs, ...extra });
const trace = (points: TracePoint[], walkId = 0): PlaceWalk => ({ points, source: 'TRACE', walkId });
const saved = (points: TracePoint[], walkId = 0): PlaceWalk => ({ points, source: 'SAVED', walkId });
/** A 300 m street going north from the origin, a point every 100 m, one minute apart. */
const STREET = [at(0, 0, 0), at(0, 100, 60_000), at(0, 200, 120_000), at(0, 300, 180_000)];
const place = (eastM: number, northM: number) => ({ lat: northM * DEG, lon: eastM * DEG });

function rng(seed: number): () => number {
  let a = seed;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

afterEach(() => vi.unstubAllGlobals());

describe('placeCheck: the gate', () => {
  it('accepts a fix of exactly 50 m and refuses 50.01, a negative one and NaN, before looking at any walk', () => {
    expect(placeCheck(place(0, 150), [trace(STREET)], 50).status).toBe('WALKED');
    expect(placeCheck(place(0, 150), [trace(STREET)], 50.01).status).toBe('IMPRECISE');
    expect(placeCheck(place(0, 150), [trace(STREET)], -1).status).toBe('IMPRECISE');
    expect(placeCheck(place(0, 150), [trace(STREET)], Number.NaN).status).toBe('IMPRECISE');
    expect(placeCheck(place(0, 150), [], 70).status).toBe('IMPRECISE');
  });

  it('marks a loose fix fuzzy but never lets it make walked easier', () => {
    expect(placeCheck(place(0, 150), [trace(STREET)], 30).fuzzy).toBe(true);
    expect(placeCheck(place(0, 150), [trace(STREET)], 25).fuzzy).toBe(false);
    expect(placeCheck(place(0, 150), [trace(STREET)]).fuzzy).toBe(false);
    // 30 m off the street with a 40 m fix is still only close, not walked.
    expect(placeCheck(place(30, 150), [trace(STREET)], 40).status).toBe('CLOSE');
  });

  it('refuses a place off the globe or not finite, before the accuracy', () => {
    for (const bad of [{ lat: 91, lon: 0 }, { lat: 0, lon: -181 }, { lat: Number.NaN, lon: 0 }, { lat: 0, lon: Number.POSITIVE_INFINITY }]) {
      expect(placeCheck(bad, [trace(STREET)], 80).status).toBe('INVALID_PLACE');
    }
    expect(placeCheck({ lat: 90, lon: 180 }, [], null).status).toBe('EMPTY');
  });
});

describe('placeCheck: the bands', () => {
  it('counts exactly the tolerance as walked (inclusive), by passing the computed distance as the tolerance', () => {
    const p = place(31.3, 150);
    const distance = distanceToSegmentM(p, STREET[1], STREET[2]);
    expect(placeCheck(p, [trace(STREET)], null, { toleranceM: distance }).status).toBe('WALKED');
    expect(placeCheck(p, [trace(STREET)], null, { toleranceM: distance - 1e-9 }).status).toBe('CLOSE');
  });

  it('keeps a walk 50 m away in the close band and drops one beyond it', () => {
    expect(placeCheck(place(49.9, 150), [trace(STREET)]).status).toBe('CLOSE');
    expect(placeCheck(place(50.1, 150), [trace(STREET)]).status).toBe('NONE');
  });

  it('takes t = 0 for a segment of length 0 (a stay) with no division by zero', () => {
    const stay = [at(10, 0, 0), at(10, 0, 60_000)];
    const r = placeCheck(place(0, 0), [trace(stay)]);
    expect(r.rows[0].distanceM).toBeCloseTo(10, 6);
    expect(r.rows[0].atMs).toBe(0);
    expect(r.rows[0].t).toBe(0);
  });

  it('answers EMPTY for no walk or a walk of one point, and NONE once a real walk exists', () => {
    expect(placeCheck(place(0, 0), []).status).toBe('EMPTY');
    expect(placeCheck(place(0, 0), [trace([at(0, 0, 0)])]).status).toBe('EMPTY');
    expect(placeCheck(place(0, 0), [trace([at(0, 0, 0), at(5000, 0, 10_000, { resumed: true })])]).status).toBe('EMPTY');
    expect(placeCheck(place(0, 5000), [trace(STREET)]).status).toBe('NONE');
  });

  it('gives one row per walk, newest first, and a tie to the later input index', () => {
    const near = trace(STREET);
    const later = trace(STREET.map((p) => ({ ...p, atMs: p.atMs + 1_000_000 })));
    const r = placeCheck(place(10, 150), [later, near, near]);
    expect(r.rows.map((x) => x.walkIndex)).toEqual([0, 2, 1]);
    const out = placeCheck(place(0, 150), [trace([...STREET, ...[at(5, 300, 200_000), at(5, 150, 260_000), at(5, 0, 320_000)]])]);
    expect(out.rows).toHaveLength(1);
  });

  it('marks a saved walk, whatever its age', () => {
    const r = placeCheck(place(0, 150), [saved(STREET.map((p) => ({ ...p, atMs: p.atMs - 400 * 86_400_000 })))]);
    expect(r.rows[0].saved).toBe(true);
    expect(r.status).toBe('WALKED');
  });

  it('reads nothing but its arguments: no network, no storage', () => {
    const fail = vi.fn(() => {
      throw new Error('network');
    });
    vi.stubGlobal('fetch', fail);
    vi.stubGlobal('XMLHttpRequest', fail);
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    placeCheck(place(0, 150), [trace(STREET)], 12);
    expect(fail).not.toHaveBeenCalled();
    expect(setItem).not.toHaveBeenCalled();
  });
});

describe('placeCheck: a save cut between its two writes', () => {
  it('leaves out a trace walk whose walk id equals a saved walk id, so the walk counts once', () => {
    const t = trace(STREET, 777);
    const s = saved(STREET, 777);
    expect(withoutSavedDuplicates([t, s])).toEqual([1]);
    const r = placeCheck(place(0, 150), [t, s]);
    expect(r.rows).toHaveLength(1);
    expect(r.rows[0].saved).toBe(true);
    expect(r.rows[0].walkIndex).toBe(1);
  });

  it('keeps both when the ids differ or are unknown (0)', () => {
    expect(withoutSavedDuplicates([trace(STREET, 1), saved(STREET, 2)])).toEqual([0, 1]);
    expect(withoutSavedDuplicates([trace(STREET, 0), saved(STREET, 0)])).toEqual([0, 1]);
  });
});

describe('placeCheck: the cheap box rejection', () => {
  it('gives the same rows with and without it, for 200 places over a random city of 60 walks', () => {
    const rand = rng(61);
    const walks: PlaceWalk[] = [];
    for (let w = 0; w < 60; w++) {
      const pts: TracePoint[] = [];
      let east = (rand() - 0.5) * 3000;
      let north = (rand() - 0.5) * 3000;
      let t = w * 100_000;
      for (let i = 0; i < 25; i++) {
        pts.push(at(east, north, t, i === 12 && rand() < 0.3 ? { resumed: true } : {}));
        east += (rand() - 0.5) * 120;
        north += (rand() - 0.5) * 120;
        t += 15_000;
      }
      walks.push({ points: pts, source: rand() < 0.2 ? 'SAVED' : 'TRACE', walkId: pts[0].atMs });
    }
    let nonEmpty = 0;
    for (let i = 0; i < 200; i++) {
      const p = place((rand() - 0.5) * 3000, (rand() - 0.5) * 3000);
      const fast = placeCheck(p, walks);
      const plain = placeCheck(p, walks, null, { noBoxRejection: true });
      expect(fast).toEqual(plain);
      if (fast.rows.length > 0) nonEmpty++;
    }
    expect(nonEmpty, 'the places must hit walks, or the comparison proves nothing').toBeGreaterThan(20);
  });
});

describe('matchedStretch', () => {
  const row = (segment: number, t: number) => ({ segment, t });
  const lat = (c: [number, number]) => c[1] / DEG;

  it('runs 60 m each side of the nearest point along the walk', () => {
    const line = matchedStretch(trace(STREET), row(1, 0.5)); // the nearest point is 150 m up
    expect(lat(line[0])).toBeCloseTo(90, 4);
    expect(lat(line[line.length - 1])).toBeCloseTo(210, 4);
    expect(line.length).toBeGreaterThanOrEqual(3); // the point at 200 m lies inside
  });

  it('is clipped at the walk ends', () => {
    const line = matchedStretch(trace(STREET), row(0, 0.1)); // 10 m up
    expect(lat(line[0])).toBeCloseTo(0, 6);
    expect(lat(line[line.length - 1])).toBeCloseTo(70, 4);
    const end = matchedStretch(trace(STREET), row(2, 0.95));
    expect(lat(end[end.length - 1])).toBeCloseTo(300, 4);
  });

  it('never runs across a resumed point', () => {
    const paused = [...STREET, at(0, 3000, 600_000, { resumed: true }), at(0, 3100, 660_000)];
    const line = matchedStretch(trace(paused), row(2, 0.9)); // 290 m up, 10 m from the pause
    expect(Math.max(...line.map(lat))).toBeCloseTo(300, 4);
    const after = matchedStretch(trace(paused), row(4, 0.1)); // in the part after the pause
    expect(Math.min(...after.map(lat))).toBeGreaterThanOrEqual(3000 - 1e-6);
  });

  it('uses the constant 60 m', () => {
    expect(TRACE.checkStretchM).toBe(60);
  });
});
