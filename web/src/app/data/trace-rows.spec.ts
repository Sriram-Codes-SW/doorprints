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
import type { TracePoint } from '../shared/trace-geo';
import { MemoryDb } from './local-db';
import { decodeWalk, deleteSavedWalksOfHouse, encodeWalk, newWalkId, pointId, pointOfRow, pointRow, walkLengthM } from './trace-rows';
import type { SavedWalkRow } from './trace-rows';

const DEG = 1 / 111_194.9266;
const pt = (eastM: number, northM: number, atMs: number, extra: Partial<TracePoint> = {}): TracePoint => ({
  lat: 13 + northM * DEG,
  lon: 80 + eastM * DEG,
  atMs,
  walkId: 1_000,
  ...extra,
});
const rowOf = (points: TracePoint[]): SavedWalkRow => ({ id: 's', houseId: 'h', savedAt: 5, ...encodeWalk(points) });

describe('trace rows', () => {
  it('names a point row "<walkId>-<atMs>" and keeps `resumed` only when true', () => {
    expect(pointId(1000, 2500)).toBe('1000-2500');
    const plain = pointRow(pt(0, 0, 1000), 12);
    expect(plain).toEqual({ id: '1000-1000', walk: 1000, at: 1000, lat: 13, lon: 80, acc: 12 });
    expect('resumed' in plain).toBe(false);
    const resumed = pointRow(pt(0, 0, 2000, { resumed: true }), 8);
    expect(resumed.resumed).toBe(true);
    expect(pointOfRow(resumed)).toMatchObject({ walkId: 1000, atMs: 2000, resumed: true });
    expect(pointOfRow(plain).resumed).toBe(false);
  });

  it('writes a point with no walk id as walk 0', () => {
    expect(pointRow({ lat: 1, lon: 2, atMs: 5 }, 3)).toMatchObject({ id: '0-5', walk: 0 });
  });
});

describe('the saved walk coding (a plain array, three numbers per point)', () => {
  const walk = [pt(0, 0, 10_000), pt(0, 20, 25_000), pt(5, 40, 41_000), pt(5, 60, 100_500)];

  it('stores the changes in micro-degrees and whole seconds from the first point, whose time is startedAt', () => {
    const e = encodeWalk(walk);
    expect(e.startedAt).toBe(10_000);
    expect(e.endedAt).toBe(100_500);
    expect(e.pointCount).toBe(4);
    expect(e.points).toHaveLength(12);
    expect(e.points[0]).toBe(Math.round(walk[0].lat * 1e6)); // the first change is from 0
    expect(e.points[2]).toBe(0); // the first dt is 0
    expect(e.points[5]).toBe(15); // 15 s from the first to the second
    expect('resumed' in e).toBe(false);
  });

  it('round-trips to 1e-6 degrees and a second, with the walk id startedAt on every point', () => {
    const back = decodeWalk(rowOf(walk))!;
    expect(back).toHaveLength(4);
    back.forEach((p, i) => {
      expect(Math.abs(p.lat - walk[i].lat)).toBeLessThanOrEqual(5e-7 + 1e-12);
      expect(Math.abs(p.lon - walk[i].lon)).toBeLessThanOrEqual(5e-7 + 1e-12);
      expect(Math.abs(p.atMs - walk[i].atMs)).toBeLessThanOrEqual(500);
      expect(p.walkId).toBe(10_000);
      expect(p.resumed).toBe(false);
    });
    expect(back[0].atMs).toBe(10_000);
  });

  it('does not drift over a long walk (the sums are of integers)', () => {
    const long = Array.from({ length: 3000 }, (_, i) => pt((i % 7) * 3, i * 20, 1_000 + i * 15_000));
    const back = decodeWalk(rowOf(long))!;
    expect(back).toHaveLength(3000);
    expect(Math.abs(back[2999].lat - long[2999].lat)).toBeLessThanOrEqual(5e-7 + 1e-12);
    expect(Math.abs(back[2999].atMs - long[2999].atMs)).toBeLessThanOrEqual(500);
  });

  it('keeps the resumed marks (a saved walk must not draw a line across a pause), never for point 0', () => {
    const paused = [pt(0, 0, 1000, { resumed: true }), pt(0, 20, 16_000), pt(0, 5000, 900_000, { resumed: true }), pt(0, 5020, 915_000)];
    const e = encodeWalk(paused);
    expect(e.resumed).toEqual([2]);
    expect(decodeWalk(rowOf(paused))!.map((p) => p.resumed)).toEqual([false, false, true, false]);
  });

  it('measures the length without the segment into a resumed point, rounded to a metre', () => {
    const paused = [pt(0, 0, 1000), pt(0, 100, 2000), pt(0, 5000, 900_000, { resumed: true }), pt(0, 5100, 910_000)];
    expect(walkLengthM(paused)).toBeCloseTo(200, 0);
    expect(encodeWalk(paused).lengthM).toBe(200);
  });

  it('reads a damaged row as no walk, not as a short one', () => {
    const good = rowOf(walk);
    expect(decodeWalk({ ...good, points: good.points.slice(0, 7) })).toBeNull(); // not a multiple of three
    expect(decodeWalk({ ...good, pointCount: 5 })).toBeNull();
    expect(decodeWalk({ ...good, points: [...good.points.slice(0, 11), Number.NaN] })).toBeNull();
    expect(decodeWalk({ ...good, points: 'x' as unknown as number[] })).toBeNull();
    expect(decodeWalk({ ...good, pointCount: 0, points: [] })).toBeNull();
  });

  it('holds 5 000 points in well under 200 KB of JSON (the phones hold them in about 25 KB)', () => {
    const big = Array.from({ length: 5000 }, (_, i) => pt((i % 11) * 2, i * 20, 1_000 + i * 15_000));
    expect(JSON.stringify(encodeWalk(big).points).length).toBeLessThan(200_000);
  });
});

describe('deleteSavedWalksOfHouse and newWalkId', () => {
  it('deletes the walks of one house only', async () => {
    const db = new MemoryDb();
    await db.putAll('saved_walks', [
      { id: 'a', houseId: 'h1' },
      { id: 'b', houseId: 'h2' },
      { id: 'c', houseId: 'h1' },
    ]);
    await deleteSavedWalksOfHouse(db, 'h2');
    expect((await db.getAll<{ id: string }>('saved_walks')).map((r) => r.id)).toEqual(['a', 'c']);
    await deleteSavedWalksOfHouse(db, 'nobody');
    expect(await db.count('saved_walks')).toBe(2);
    await deleteSavedWalksOfHouse(db, 'h1');
    expect(await db.count('saved_walks')).toBe(0);
  });

  it('makes ids that differ', () => {
    expect(newWalkId()).not.toBe(newWalkId());
    expect(newWalkId().length).toBeGreaterThanOrEqual(32);
  });
});
