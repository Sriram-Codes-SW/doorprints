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

import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TRACE } from '../shared/trace-geo';
import type { TracePoint } from '../shared/trace-geo';
import { placeCheck } from '../shared/trace-place-check';
import type { HouseDto } from '../core/models';
import { LocalStore } from './local-store.service';
import { SETTING_KEYS } from './records';
import type { SavedWalkRow, TracePointRow } from './trace-rows';
import { ASK_MIN_LENGTH_M, ASK_MIN_POINTS, MAX_SAVED_WALKS_PER_DEVICE, MAX_SAVED_WALKS_PER_HOUSE, TRACE_KEPT_MS, TraceStore, holdsWalkId, walksOf } from './trace-store';

const DEG = 1 / 111_194.9266;
const DAY = 86_400_000;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);
/** A point `northM` north of (13, 80); `walkId` defaults to the walk's first time. */
const pt = (northM: number, atMs: number, walkId: number, extra: Partial<TracePoint> = {}): TracePoint => ({
  lat: 13 + northM * DEG,
  lon: 80,
  atMs,
  walkId,
  ...extra,
});
/** A walk of `n` points 20 m apart, 15 s apart, starting at `t0` (its id). */
const walk = (t0: number, n: number): TracePoint[] => Array.from({ length: n }, (_, i) => pt(i * 20, t0 + i * 15_000, t0));
const house = (id: string): HouseDto => ({ id, label: id, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0 });

describe('TraceStore', () => {
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
  const traceRows = async () => (await local.database()).getAll<TracePointRow>('trace_points');

  describe('the 30-day trace', () => {
    it('writes a point as a row keyed "<walkId>-<atMs>" and reads a walk back in time order', async () => {
      await store.putPoint(pt(20, 2000, 1000), 8);
      await store.putPoint(pt(0, 1000, 1000, { resumed: true }), 9);
      const rows = await traceRows();
      expect(rows.map((r) => r.id).sort()).toEqual(['1000-1000', '1000-2000']);
      expect(rows.find((r) => r.at === 1000)).toMatchObject({ walk: 1000, acc: 9, resumed: true });
      expect('resumed' in rows.find((r) => r.at === 2000)!).toBe(false);
      const points = await store.walkPoints(1000);
      expect(points.map((p) => p.atMs)).toEqual([1000, 2000]);
      expect(points[0].resumed).toBe(true);
    });

    it('reads the last 30 days as walks keyed t:<walkId>, split by walk id and by the 30-minute gap', async () => {
      const a = walk(NOW - 2 * DAY, 4);
      const b = walk(NOW - DAY, 4);
      await putWalk([...a, ...b]);
      const walks = await store.traceWalks(NOW);
      expect(walks.map((w) => w.key)).toEqual([`t:${NOW - 2 * DAY}`, `t:${NOW - DAY}`]);
      expect(walks.map((w) => w.points.length)).toEqual([4, 4]);
      expect(walksOf([...a, ...b]).length).toBe(2);
    });

    it('reads nothing older than 30 days', async () => {
      await putWalk([...walk(NOW - 31 * DAY, 4), ...walk(NOW - DAY, 4)]);
      expect((await store.traceWalks(NOW)).map((w) => w.key)).toEqual([`t:${NOW - DAY}`]);
    });

    it('prunes the points older than 30 days, keeps one exactly 30 days old, and never reads or touches saved walks', async () => {
      await putWalk(walk(NOW - 40 * DAY, 3));
      await putWalk([pt(0, NOW - TRACE_KEPT_MS, NOW - TRACE_KEPT_MS), pt(20, NOW - TRACE_KEPT_MS + 15_000, NOW - TRACE_KEPT_MS)]);
      await putWalk(walk(NOW - DAY, 3));
      const db = await local.database();
      await db.put('saved_walks', { id: 'old', houseId: 'h', startedAt: NOW - 90 * DAY, endedAt: NOW - 90 * DAY, savedAt: NOW - 90 * DAY, pointCount: 0, lengthM: 0, points: [] });
      const getAll = vi.spyOn(db, 'getAll');
      expect(await store.prune(NOW)).toBe(3);
      expect(getAll.mock.calls.every((c) => c[0] === 'trace_points')).toBe(true);
      expect((await traceRows()).length).toBe(5);
      expect(await db.get('saved_walks', 'old')).toBeDefined();
    });

    it('clears the trace and leaves the saved walks, and deletes one walk by id', async () => {
      await putWalk([...walk(1000, 3), ...walk(900_000_000, 3)]);
      await store.deleteTraceWalk(1000);
      expect((await traceRows()).map((r) => r.walk)).toEqual([900_000_000, 900_000_000, 900_000_000]);
      await (await local.database()).put('saved_walks', { id: 's', houseId: 'h' });
      await store.clearTrace();
      expect(await traceRows()).toEqual([]);
      expect(await store.savedCount()).toBe(1);
    });
  });

  describe('the walk to ask about', () => {
    const at = NOW - 3 * DAY;
    it('is the newest walk that is not live, is above the watermark and has 5 points and 100 m', async () => {
      await putWalk([...walk(at, 6), ...walk(at + DAY, 6)]);
      const found = await store.lastEndedWalk(0, 0);
      expect(found?.walkId).toBe(at + DAY);
      expect(found?.pointCount).toBe(6);
      expect(found?.lengthM).toBeCloseTo(100, 0);
      expect(found?.last.lat).toBeCloseTo(13 + 100 * DEG, 9);
    });

    it('leaves out the live walk, so the walk before it is asked about', async () => {
      await putWalk([...walk(at, 6), ...walk(at + DAY, 6)]);
      expect((await store.lastEndedWalk(at + DAY, 0))?.walkId).toBe(at);
    });

    it('asks once: nothing at or below the watermark, and an unanswered older walk is skipped', async () => {
      await putWalk([...walk(at, 6), ...walk(at + DAY, 6)]);
      expect(await store.lastEndedWalk(0, at + DAY)).toBeNull();
      expect((await store.lastEndedWalk(0, at))?.walkId).toBe(at + DAY);
    });

    it('does not ask about a short walk (under 5 points or under 100 m) when no longer walk is left', async () => {
      await putWalk(walk(at + DAY, ASK_MIN_POINTS - 1));
      expect(await store.lastEndedWalk(0, 0)).toBeNull();
      expect(ASK_MIN_POINTS).toBe(5);
      await store.clearTrace();
      const short = Array.from({ length: 6 }, (_, i) => pt(i * 15, at + i * 15_000, at)); // 75 m
      await putWalk(short);
      expect(await store.lastEndedWalk(0, 0)).toBeNull();
      expect(ASK_MIN_LENGTH_M).toBe(100);
    });

    it('a short newer walk (a false start) does not hide a long older one: the newest QUALIFYING walk is asked about', async () => {
      await putWalk([...walk(at, 6), ...walk(at + DAY, ASK_MIN_POINTS - 1)]);
      expect((await store.lastEndedWalk(0, 0))?.walkId).toBe(at);
      await store.clearTrace();
      const shortLong = Array.from({ length: 6 }, (_, i) => pt(i * 15, at + DAY + i * 15_000, at + DAY)); // 5 points enough, 75 m too short
      await putWalk([...walk(at, 6), ...shortLong]);
      expect((await store.lastEndedWalk(0, 0))?.walkId).toBe(at);
    });

    it('takes the newest of several qualifying walks, skipping the live one and short ones in between', async () => {
      await putWalk([...walk(at, 6), ...walk(at + DAY, 6), ...walk(at + 2 * DAY, 2), ...walk(at + 3 * DAY, 6)]);
      expect((await store.lastEndedWalk(at + 3 * DAY, 0))?.walkId).toBe(at + DAY);
      expect((await store.lastEndedWalk(at + 3 * DAY, at + DAY))).toBeNull();
    });

    it('measures the 100 m without the segment into a resumed point', async () => {
      // 60 m, a pause, 20 m more: 80 m of walking (6 points). Counting the straight line across the pause would make it 5 km.
      const pts = [...walk(at, 4), pt(5000, at + 3_600_000, at, { resumed: true }), pt(5020, at + 3_615_000, at)];
      await putWalk(pts);
      expect(await store.lastEndedWalk(0, 0)).toBeNull();
    });

    it('ignores rows with no walk id', async () => {
      await putWalk(walk(0, 10).map((p) => ({ ...p, walkId: 0, atMs: p.atMs + NOW - DAY })));
      expect(await store.lastEndedWalk(0, 0)).toBeNull();
    });

    it('summarises one walk by id', async () => {
      await putWalk(walk(at, 6));
      expect((await store.walkSummary(at))?.pointCount).toBe(6);
      expect(await store.walkSummary(123)).toBeNull();
    });
  });

  describe('saving a walk', () => {
    const id0 = NOW - DAY;

    it('writes the saved row and removes the walk from the trace in ONE batch, so it is stored once', async () => {
      await putWalk([...walk(id0, 6), ...walk(id0 + DAY, 3)]);
      const db = await local.database();
      const batch = vi.spyOn(db, 'batch');
      const result = await store.saveWalk(id0, 'h1', NOW, () => 'w1');
      expect(result).toEqual({ ok: true, id: 'w1' });
      expect(batch).toHaveBeenCalledTimes(1);
      const ops = batch.mock.calls[0][0];
      expect(ops.filter((o) => o.op === 'put').map((o) => o.store)).toEqual(['saved_walks']);
      expect(ops.filter((o) => o.op === 'delete').map((o) => o.store)).toEqual(Array(6).fill('trace_points'));
      const row = (await db.get<SavedWalkRow>('saved_walks', 'w1'))!;
      expect(row).toMatchObject({ houseId: 'h1', startedAt: id0, savedAt: NOW, pointCount: 6, lengthM: 100 });
      expect((await traceRows()).map((r) => r.walk)).toEqual([id0 + DAY, id0 + DAY, id0 + DAY]); // only the other walk is left
      const { trace, saved } = await store.allWalks(NOW);
      expect(trace.map((w) => w.key)).toEqual([`t:${id0 + DAY}`]);
      expect(saved.map((s) => s.walk.key)).toEqual(['s:w1']);
    });

    it('keeps a saved walk past the 30 days that prune the trace', async () => {
      const old = NOW - 60 * DAY;
      await putWalk(walk(old, 6));
      await store.saveWalk(old, 'h1', NOW, () => 'w1');
      await store.prune(NOW);
      expect((await store.allWalks(NOW)).saved).toHaveLength(1);
      expect(await store.traceWalks(NOW)).toEqual([]);
    });

    it('refuses a walk with no points or one point, and changes nothing', async () => {
      await putWalk([pt(0, id0, id0)]);
      expect(await store.saveWalk(id0, 'h1', NOW)).toEqual({ ok: false, reason: 'noWalk' });
      expect(await store.saveWalk(12345, 'h1', NOW)).toEqual({ ok: false, reason: 'noWalk' });
      expect(await traceRows()).toHaveLength(1);
    });

    it('refuses a walk of more than 5 000 points (the limit is on the points, not the saving)', async () => {
      const db = await local.database();
      const rows: TracePointRow[] = Array.from({ length: TRACE.maxWalkPoints + 1 }, (_, i) => ({ id: `${id0}-${id0 + i * 1000}`, walk: id0, at: id0 + i * 1000, lat: 13, lon: 80, acc: 5 }));
      await db.putAll('trace_points', rows);
      expect(await store.saveWalk(id0, 'h1', NOW)).toEqual({ ok: false, reason: 'tooLong' });
      expect(await store.savedCount()).toBe(0);
      expect(await db.count('trace_points')).toBe(TRACE.maxWalkPoints + 1);
      await db.deleteAll('trace_points', [rows[0].id]);
      expect((await store.saveWalk(id0, 'h1', NOW)).ok).toBe(true);
    });

    it('refuses a 21st walk for a house and a 201st for the device, with the walk left in the trace', async () => {
      const db = await local.database();
      const row = (id: string, houseId: string): SavedWalkRow => ({ id, houseId, startedAt: 1, endedAt: 2, savedAt: 3, pointCount: 0, lengthM: 0, points: [] });
      await db.putAll('saved_walks', Array.from({ length: MAX_SAVED_WALKS_PER_HOUSE }, (_, i) => row(`a${i}`, 'full')));
      await putWalk(walk(id0, 6));
      expect(await store.saveWalk(id0, 'full', NOW)).toEqual({ ok: false, reason: 'houseFull' });
      expect(await traceRows()).toHaveLength(6);
      expect((await store.saveWalk(id0, 'other', NOW)).ok).toBe(true);

      // The device holds exactly 200 saved walks (199 and the one just saved above): the 201st is refused.
      await db.putAll('saved_walks', Array.from({ length: MAX_SAVED_WALKS_PER_DEVICE - 1 - MAX_SAVED_WALKS_PER_HOUSE }, (_, i) => row(`b${i}`, `h${i}`)));
      expect(await store.savedCount()).toBe(MAX_SAVED_WALKS_PER_DEVICE);
      await putWalk(walk(id0 + DAY, 6));
      expect(await store.saveWalk(id0 + DAY, 'new', NOW)).toEqual({ ok: false, reason: 'deviceFull' });
      expect(await traceRows()).toHaveLength(6);
      await db.delete('saved_walks', 'b0');
      expect((await store.saveWalk(id0 + DAY, 'new', NOW)).ok).toBe(true);
      expect([MAX_SAVED_WALKS_PER_HOUSE, MAX_SAVED_WALKS_PER_DEVICE]).toEqual([20, 200]);
    });

    it('two saves of the SAME walk to two houses (two tabs): exactly one wins, the other finds the walk gone', async () => {
      await putWalk(walk(id0, 6));
      const results = await Promise.all([store.saveWalk(id0, 'h1', NOW, () => 'w1'), store.saveWalk(id0, 'h2', NOW, () => 'w2')]);
      expect(results.filter((r) => r.ok)).toHaveLength(1);
      expect(results.filter((r) => !r.ok)).toEqual([{ ok: false, reason: 'noWalk' }]);
      expect(await store.savedCount()).toBe(1);
      expect(await traceRows()).toHaveLength(0);
    });

    it('two tabs saving two walks to a house that holds 19 cannot make it 21: the limit is checked inside the transaction', async () => {
      const db = await local.database();
      const row = (id: string, houseId: string): SavedWalkRow => ({ id, houseId, startedAt: 1, endedAt: 2, savedAt: 3, pointCount: 0, lengthM: 0, points: [] });
      await db.putAll('saved_walks', Array.from({ length: MAX_SAVED_WALKS_PER_HOUSE - 1 }, (_, i) => row(`a${i}`, 'h')));
      await putWalk([...walk(id0, 6), ...walk(id0 + DAY, 6)]);
      const results = await Promise.all([store.saveWalk(id0, 'h', NOW, () => 'w1'), store.saveWalk(id0 + DAY, 'h', NOW, () => 'w2')]);
      expect(results.map((r) => r.ok).sort()).toEqual([false, true]);
      expect(results.find((r) => !r.ok)).toEqual({ ok: false, reason: 'houseFull' });
      expect(await store.savedCount('h')).toBe(MAX_SAVED_WALKS_PER_HOUSE);
      expect(await traceRows()).toHaveLength(6); // the refused walk stays in the trace
    });

    it('and the device limit of 200 the same way', async () => {
      const db = await local.database();
      const row = (id: string, houseId: string): SavedWalkRow => ({ id, houseId, startedAt: 1, endedAt: 2, savedAt: 3, pointCount: 0, lengthM: 0, points: [] });
      await db.putAll('saved_walks', Array.from({ length: MAX_SAVED_WALKS_PER_DEVICE - 1 }, (_, i) => row(`a${i}`, `x${i}`)));
      await putWalk([...walk(id0, 6), ...walk(id0 + DAY, 6)]);
      const results = await Promise.all([store.saveWalk(id0, 'p', NOW, () => 'w1'), store.saveWalk(id0 + DAY, 'q', NOW, () => 'w2')]);
      expect(results.find((r) => !r.ok)).toEqual({ ok: false, reason: 'deviceFull' });
      expect(await store.savedCount()).toBe(MAX_SAVED_WALKS_PER_DEVICE);
    });

    it('rejects, and the trace stays, when the transaction fails (the quota)', async () => {
      await putWalk(walk(id0, 6));
      const db = await local.database();
      vi.spyOn(db, 'batch').mockRejectedValueOnce(new DOMException('full', 'QuotaExceededError'));
      await expect(store.saveWalk(id0, 'h1', NOW)).rejects.toMatchObject({ name: 'QuotaExceededError' });
      expect(await traceRows()).toHaveLength(6);
      expect(await store.savedCount()).toBe(0);
    });

    it('counts a walk once for the place check even when a save was cut between its writes (both stores hold it)', async () => {
      await putWalk(walk(id0, 6));
      const db = await local.database();
      // The cut save: the saved row was written, the trace rows were not yet deleted.
      const saved = await store.saveWalk(id0, 'h1', NOW, () => 'w1');
      expect(saved.ok).toBe(true);
      await putWalk(walk(id0, 6)); // the trace rows come back, as after an interrupted second write
      const walks = await store.placeWalks(NOW);
      expect(walks.map((w) => w.source)).toEqual(['TRACE', 'SAVED']);
      expect(walks[0].walkId).toBe(id0);
      expect(walks[1].walkId).toBe(id0);
      const result = placeCheck({ lat: 13 + 50 * DEG, lon: 80 }, walks);
      expect(result.rows).toHaveLength(1);
      expect(result.rows[0].saved).toBe(true);
      expect(await db.count('saved_walks')).toBe(1);
    });

    it('leaves the live walk out of the place walks only when asked', async () => {
      await putWalk([...walk(id0, 6), ...walk(id0 + DAY, 6)]);
      expect((await store.placeWalks(NOW)).length).toBe(2);
      expect((await store.placeWalks(NOW, id0 + DAY)).map((w) => w.walkId)).toEqual([id0]);
    });

    it('leaves out a walk that MERGED a legacy walk-id-0 row into the live walk (its first point carries 0)', async () => {
      // Rows from before the upgrade (id 0) less than 30 min before the first walk after it merge into that walk.
      const legacy = Array.from({ length: 3 }, (_, i) => pt(i * 20, id0 - 5 * 60_000 + i * 15_000, 0));
      await putWalk([...legacy, ...walk(id0, 6)]);
      const all = await store.placeWalks(NOW);
      expect(all).toHaveLength(1);
      expect(all[0].walkId).toBe(0); // the merge: one walk whose first point has id 0
      expect(await store.placeWalks(NOW, id0)).toEqual([]); // the live walk's own points are never "elsewhere"
      expect(holdsWalkId(walk(id0, 2), id0)).toBe(true);
      expect(holdsWalkId(legacy, id0)).toBe(false);
    });
  });

  describe('reading and deleting saved walks', () => {
    const id0 = NOW - DAY;
    const save = async (t0: number, houseId: string, saveId: string) => {
      await putWalk(walk(t0, 6));
      await store.saveWalk(t0, houseId, NOW, () => saveId);
    };

    it('lists the saved walks of a house newest first, counts them without reading, and decodes one by id', async () => {
      await save(id0, 'h1', 'a');
      await save(id0 + 1000 * 60, 'h1', 'b');
      await save(id0 + 2000 * 60, 'h2', 'c');
      expect((await store.savedWalksOf('h1')).map((r) => r.id)).toEqual(['b', 'a']);
      expect(await store.savedCount('h1')).toBe(2);
      expect(await store.savedCount()).toBe(3);
      const one = await store.savedWalk('a');
      expect(one?.walk.key).toBe('s:a');
      expect(one?.walk.points).toHaveLength(6);
      expect(await store.savedWalk('nope')).toBeNull();
    });

    it('reads every saved walk one at a time, by key, and skips a damaged row', async () => {
      await save(id0, 'h1', 'a');
      await save(id0 + 60_000, 'h1', 'b');
      const db = await local.database();
      await db.put('saved_walks', { id: 'bad', houseId: 'h1', startedAt: 1, endedAt: 2, savedAt: 3, pointCount: 9, lengthM: 0, points: [1, 2] });
      const get = vi.spyOn(db, 'get');
      const getAll = vi.spyOn(db, 'getAll');
      const seen: string[] = [];
      await store.forEachSavedWalk((row) => seen.push(row.id));
      expect(seen.sort()).toEqual(['a', 'b']);
      expect(get).toHaveBeenCalledTimes(3);
      expect(getAll).not.toHaveBeenCalled();
    });

    it('deletes one saved walk and all of them', async () => {
      await save(id0, 'h1', 'a');
      await save(id0 + 60_000, 'h2', 'b');
      await store.deleteSavedWalk('a');
      expect(await store.savedCount()).toBe(1);
      await store.deleteAllSavedWalks();
      expect(await store.savedCount()).toBe(0);
    });

    it('deleting a house deletes its saved walks in the same operation, and no other house\'s', async () => {
      await local.saveHouse(house('h1'));
      await local.saveHouse(house('h2'));
      await save(id0, 'h1', 'a');
      await save(id0 + 60_000, 'h2', 'b');
      await local.deleteHouse('h1');
      expect((await store.savedWalksOf('h1'))).toEqual([]);
      expect((await store.savedWalksOf('h2')).map((r) => r.id)).toEqual(['b']);
    });

    it('is emptied by Remove all Doorprints data from this browser: the trace, the saved walks and the settings', async () => {
      await save(id0, 'h1', 'a');
      await putWalk(walk(id0 + DAY, 3));
      await store.setLook('SUBTLE');
      await local.clearEverything();
      expect(await traceRows()).toEqual([]);
      expect(await store.savedCount()).toBe(0);
      expect(await store.look()).toBe('CLEAR');
    });

    it('does not wake the sync engine: a walk written or deleted leaves LocalStore.revision alone', async () => {
      const before = local.revision();
      await putWalk(walk(id0, 6));
      await store.saveWalk(id0, 'h1', NOW);
      await store.deleteAllSavedWalks();
      await store.prune(NOW);
      await store.clearTrace();
      await store.setTraceOn(true);
      expect(local.revision()).toBe(before);
    });

    it('says the data is not being kept when IndexedDB is unavailable (memory)', async () => {
      expect(await store.persistent()).toBe(false); // jsdom has no IndexedDB: the private-browsing path
    });
  });

  describe('the settings', () => {
    it('starts with the trace off, looks CLEAR, the alert off, the screen lock off and nothing asked', async () => {
      expect(await store.traceOn()).toBe(false);
      expect(await store.look()).toBe('CLEAR');
      expect(await store.alertOn()).toBe(false);
      expect(await store.keepAwake()).toBe(false);
      expect(await store.askedUpTo()).toBe(0);
    });

    it('stores each under its own key in the settings store', async () => {
      await store.setTraceOn(true);
      await store.setLook('OFF');
      await store.setAlertOn(true);
      await store.setKeepAwake(true);
      await store.setAskedUpTo(777);
      expect(await local.setting('trace.on')).toBe('1');
      expect(await local.setting('trace.look')).toBe('OFF');
      expect(await local.setting('trace.alert')).toBe('1');
      expect(await local.setting('trace.keepAwake')).toBe('1');
      expect(await local.setting('trace.askedUpTo')).toBe('777');
      expect([SETTING_KEYS.traceOn, SETTING_KEYS.traceLook, SETTING_KEYS.traceAlert, SETTING_KEYS.traceKeepAwake, SETTING_KEYS.traceAskedUpTo]).toEqual([
        'trace.on',
        'trace.look',
        'trace.alert',
        'trace.keepAwake',
        'trace.askedUpTo',
      ]);
      expect(await store.traceOn()).toBe(true);
      expect(await store.look()).toBe('OFF');
      expect(await store.alertOn()).toBe(true);
      expect(await store.keepAwake()).toBe(true);
      await store.setTraceOn(false);
      expect(await store.traceOn()).toBe(false);
    });

    it('reads an unknown look as CLEAR and a damaged watermark as 0', async () => {
      await local.setSetting('trace.look', 'LOUD');
      await local.setSetting('trace.askedUpTo', 'soon');
      expect(await store.look()).toBe('CLEAR');
      expect(await store.askedUpTo()).toBe(0);
    });

    it('never moves the watermark backwards', async () => {
      await store.setAskedUpTo(500);
      await store.setAskedUpTo(300);
      expect(await store.askedUpTo()).toBe(500);
      await store.setAskedUpTo(900);
      expect(await store.askedUpTo()).toBe(900);
    });
  });
});
