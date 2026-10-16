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
 * What the website's trace store READS per operation (S4b-FR-31, docs/11 5.27.8 and 5.27.13): the Map's opening reads the
 * `trace_points` store once, not three times; the saved walks are read one at a time by key (never `getAll`); the place check
 * keeps the points only of a saved walk that could be within its band. A counting wrapper over the database counts the rows
 * each call returns.
 */

import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { TracePoint } from '../shared/trace-geo';
import { placeCheck } from '../shared/trace-place-check';
import { LocalStore } from './local-store.service';
import type { LocalDb } from './local-db';
import { TRACE_KEPT_MS, TraceStore } from './trace-store';

const DEG = 1 / 111_194.9266;
const DAY = 86_400_000;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);
const pt = (northM: number, eastM: number, atMs: number, walkId: number): TracePoint => ({ lat: 13 + northM * DEG, lon: 80 + (eastM * DEG) / Math.cos((13 * Math.PI) / 180), atMs, walkId });
/** A walk of `n` points 20 m apart going north from `eastM` east of (13, 80), 15 s apart, starting at `t0` (its id). */
const walk = (t0: number, n: number, eastM = 0): TracePoint[] => Array.from({ length: n }, (_, i) => pt(i * 20, eastM, t0 + i * 15_000, t0));

interface Counts {
  /** Rows returned per store, by every read call. */
  rows: Record<string, number>;
  /** Whole-store reads (`getAll`) per store. */
  getAll: Record<string, number>;
  /** Single-key reads (`get`) per store. */
  gets: Record<string, number>;
}

/** Wraps the reads of `db` so the rows each store returns are counted (the calls still reach the real database). */
function counting(db: LocalDb): Counts {
  const c: Counts = { rows: {}, getAll: {}, gets: {} };
  const add = (store: string, n: number) => (c.rows[store] = (c.rows[store] ?? 0) + n);
  const getAll = db.getAll.bind(db);
  const get = db.get.bind(db);
  const byIndex = db.getAllByIndex.bind(db);
  vi.spyOn(db, 'getAll').mockImplementation(async (s) => {
    c.getAll[s] = (c.getAll[s] ?? 0) + 1;
    const r = await getAll(s);
    add(s, r.length);
    return r as never;
  });
  vi.spyOn(db, 'get').mockImplementation(async (s, k) => {
    c.gets[s] = (c.gets[s] ?? 0) + 1;
    const r = await get(s, k);
    add(s, r === undefined ? 0 : 1);
    return r as never;
  });
  vi.spyOn(db, 'getAllByIndex').mockImplementation(async (s, i, v) => {
    const r = await byIndex(s, i, v);
    add(s, r.length);
    return r as never;
  });
  return c;
}

describe('TraceStore reads', () => {
  let local: LocalStore;
  let store: TraceStore;

  beforeEach(async () => {
    local = new LocalStore();
    await local.ready();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: LocalStore, useValue: local }] });
    store = TestBed.inject(TraceStore);
  });

  const putWalk = async (points: TracePoint[]) => {
    for (const p of points) await store.putPoint(p, 10);
  };
  /** Saves a walk of `n` points at `t0` to its own house (a house holds 20 at most). */
  const saveOne = async (t0: number, n: number, eastM = 0) => {
    await putWalk(walk(t0, n, eastM));
    expect((await store.saveWalk(t0, `house-${t0}`, NOW, () => `s${t0}`)).ok).toBe(true);
  };

  describe('snapshot: one read of each store per change', () => {
    it('reads trace_points once for the prune, the drawing and the walk to ask about, and each saved walk once by key', async () => {
      await putWalk(walk(NOW - 40 * DAY, 4)); // older than 30 days: pruned
      await putWalk(walk(NOW - 2 * DAY, 4)); // short: no question
      await putWalk(walk(NOW - DAY, 8));
      await saveOne(NOW - 5 * DAY, 6);
      await saveOne(NOW - 6 * DAY, 6);
      const db = await local.database();
      const stored = (await db.getAll('trace_points')).length;
      expect(stored).toBe(16);
      const c = counting(db);
      const snap = await store.snapshot(NOW, { prune: true, liveWalkId: 0, askedUpTo: 0 });
      expect(c.getAll['trace_points']).toBe(1);
      expect(c.rows['trace_points']).toBe(stored);
      expect(c.getAll['saved_walks'] ?? 0).toBe(0);
      expect(c.gets['saved_walks']).toBe(2);
      expect(c.rows['saved_walks']).toBe(2);
      expect(snap.saved.map((s) => s.row.id).sort()).toEqual([`s${NOW - 6 * DAY}`, `s${NOW - 5 * DAY}`]);
      expect(snap.trace.map((w) => w.key)).toEqual([`t:${NOW - 2 * DAY}`, `t:${NOW - DAY}`]);
      expect(snap.ask?.walkId).toBe(NOW - DAY);
      expect((await db.getAll('trace_points')).length).toBe(12); // the 4 old ones went
    });

    it('does not prune or ask unless told to: a plain refresh is one read and writes nothing', async () => {
      await putWalk(walk(NOW - 40 * DAY, 4));
      await putWalk(walk(NOW - DAY, 8));
      const db = await local.database();
      const c = counting(db);
      const deleteAll = vi.spyOn(db, 'deleteAll');
      const snap = await store.snapshot(NOW);
      expect(c.getAll['trace_points']).toBe(1);
      expect(deleteAll).not.toHaveBeenCalled();
      expect(snap.ask).toBeNull();
      expect(snap.trace.map((w) => w.key)).toEqual([`t:${NOW - DAY}`]); // the old walk is not drawn even before it is pruned
    });

    it('the walk to ask about never takes the live walk or one at or below the watermark, and a pruned walk is not asked about', async () => {
      await putWalk(walk(NOW - 3 * DAY, 8));
      await putWalk(walk(NOW - 2 * DAY, 8));
      await putWalk(walk(NOW - DAY, 8));
      expect((await store.snapshot(NOW, { liveWalkId: NOW - DAY, askedUpTo: 0 })).ask?.walkId).toBe(NOW - 2 * DAY);
      expect((await store.snapshot(NOW, { liveWalkId: 0, askedUpTo: NOW - 2 * DAY })).ask?.walkId).toBe(NOW - DAY);
      expect((await store.snapshot(NOW, { liveWalkId: 0, askedUpTo: NOW - DAY })).ask).toBeNull();
    });

    it('a failing prune is housekeeping: the walks are still returned and nothing throws', async () => {
      await putWalk(walk(NOW - 40 * DAY, 4));
      await putWalk(walk(NOW - DAY, 8));
      const db = await local.database();
      vi.spyOn(db, 'deleteAll').mockRejectedValue(new Error('quota'));
      const snap = await store.snapshot(NOW, { prune: true, liveWalkId: 0, askedUpTo: 0 });
      expect(snap.trace).toHaveLength(1);
      expect(snap.ask?.walkId).toBe(NOW - DAY);
    });

    it('keeps a point exactly 30 days old and prunes one a millisecond older', async () => {
      const edge = NOW - TRACE_KEPT_MS;
      await putWalk([pt(0, 0, edge, edge), pt(20, 0, edge + 15_000, edge)]);
      await putWalk([pt(0, 500, edge - 15_000, edge - 15_000), pt(20, 500, edge - 1, edge - 15_000)]);
      const db = await local.database();
      await store.snapshot(NOW, { prune: true });
      expect((await db.getAll<{ at: number }>('trace_points')).map((r) => r.at).sort()).toEqual([edge, edge + 15_000]);
      expect((await store.snapshot(NOW)).trace.map((w) => w.key)).toEqual([`t:${edge}`]);
    });

    it('allWalks, traceWalks, prune and lastEndedWalk stay what they were (each reads trace_points once)', async () => {
      await putWalk(walk(NOW - DAY, 8));
      const db = await local.database();
      const c = counting(db);
      expect((await store.allWalks(NOW)).trace).toHaveLength(1);
      expect(c.getAll['trace_points']).toBe(1);
      expect((await store.lastEndedWalk(0, 0))?.walkId).toBe(NOW - DAY);
      expect(c.getAll['trace_points']).toBe(2);
    });
  });

  describe('placeWalks: saved walks one at a time, the points kept only where they could matter', () => {
    const place = { lat: 13 + 60 * DEG, lon: 80 };

    it('reads 200 saved walks as 200 key reads and never a whole-store read of saved_walks', async () => {
      for (let i = 0; i < 200; i++) await saveOne(NOW - 100 * DAY - i * 3_600_000, 4, 5_000 + i);
      const db = await local.database();
      const c = counting(db);
      const walks = await store.placeWalks(NOW, 0, place);
      expect(c.gets['saved_walks']).toBe(200);
      expect(c.getAll['saved_walks'] ?? 0).toBe(0);
      expect(c.rows['saved_walks']).toBe(200);
      expect(c.getAll['trace_points']).toBe(1);
      expect(walks).toHaveLength(200);
    });

    it('a saved walk that cannot reach the place is kept as one segment only; one near it keeps every point', async () => {
      await saveOne(NOW - 50 * DAY, 300, 0); // along the street through the place
      await saveOne(NOW - 51 * DAY, 300, 4_000); // 4 km east
      const walks = await store.placeWalks(NOW, 0, place);
      const near = walks.find((w) => w.walkId === NOW - 50 * DAY)!;
      const far = walks.find((w) => w.walkId === NOW - 51 * DAY)!;
      expect(near.points).toHaveLength(300);
      expect(far.points.length).toBeLessThanOrEqual(2);
      expect(far.points.length).toBeGreaterThanOrEqual(2);
      expect(far.source).toBe('SAVED');
    });

    it('answers exactly as with every point kept, for places on, near, beside and far from the walks, and for an empty answer', async () => {
      await saveOne(NOW - 50 * DAY, 40, 0);
      await saveOne(NOW - 51 * DAY, 40, 40); // 40 m east: close, not walked
      await saveOne(NOW - 52 * DAY, 40, 900);
      await putWalk(walk(NOW - DAY, 40, 10));
      const full = await store.placeWalks(NOW); // no place: nothing is dropped
      expect(full.every((w) => w.points.length === 40)).toBe(true);
      for (const northM of [-300, -40, 0, 60, 390, 810, 2_000]) {
        for (const eastM of [0, 20, 45, 80, 900, 3_000]) {
          const p = { lat: 13 + northM * DEG, lon: 80 + (eastM * DEG) / Math.cos((13 * Math.PI) / 180) };
          const thin = await store.placeWalks(NOW, 0, p);
          expect(placeCheck(p, thin), `${northM} N ${eastM} E`).toEqual(placeCheck(p, full));
          expect(thin.map((w) => [w.source, w.walkId])).toEqual(full.map((w) => [w.source, w.walkId]));
        }
      }
    });

    it('a far saved walk still counts as a walk: the answer is "no", not "empty", with nothing else stored', async () => {
      await saveOne(NOW - 50 * DAY, 30, 5_000);
      const p = { lat: 13, lon: 80 };
      const thin = await store.placeWalks(NOW, 0, p);
      expect(thin[0].points.length).toBe(2);
      expect(placeCheck(p, thin).status).toBe('NONE');
      expect(placeCheck(p, await store.placeWalks(NOW)).status).toBe('NONE');
    });

    it('the segment kept of a far walk is its first real one: a resumed point has no segment into it', async () => {
      const t0 = NOW - 60 * DAY;
      const at = (n: number, t: number, resumed = false): TracePoint => ({ ...pt(n * 20, 3_000, t0 + t, t0), ...(resumed ? { resumed } : {}) });
      await putWalk([at(0, 0), at(1, 15_000, true), at(2, 30_000), at(3, 45_000)]);
      expect((await store.saveWalk(t0, 'h', NOW, () => 'resumed')).ok).toBe(true);
      const p = { lat: 13, lon: 80 };
      const thin = await store.placeWalks(NOW, 0, p);
      expect(thin[0].points.map((q) => q.atMs - t0)).toEqual([15_000, 30_000]);
      expect(placeCheck(p, thin).status).toBe('NONE'); // the kept segment is 1 to 2 (the resumed point 1 starts a part), so the walk still counts
    });

    it('a far saved walk with no segment of its own (every later point resumed) still counts as no walk: EMPTY stays EMPTY', async () => {
      const t0 = NOW - 60 * DAY;
      await putWalk([pt(0, 3_000, t0, t0), { ...pt(20, 3_000, t0 + 15_000, t0), resumed: true }]);
      await store.saveWalk(t0, 'h', NOW, () => 'frag');
      const p = { lat: 13, lon: 80 };
      const thin = await store.placeWalks(NOW, 0, p);
      expect(thin).toHaveLength(1);
      expect(placeCheck(p, thin).status).toBe('EMPTY');
    });
  });
});
