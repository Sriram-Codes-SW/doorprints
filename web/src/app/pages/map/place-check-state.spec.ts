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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Announcer } from '../../core/announcer.service';
import { GEOLOCATION, TraceRecorderService } from '../../core/trace-recorder.service';
import { TraceStore } from '../../data/trace-store';
import { TranslationService } from '../../i18n/translation.service';
import { FakeRecorder } from '../../shared/testing/trace-fakes';
import { PlaceCheckState, boundsOf } from './place-check-state';
import { TraceView } from './trace-view';

const DEG = 1 / 111_194.9266;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);
/** A street 200 m long going north along lon 0, walked at WALK_ID (points 20 m apart). */
const WALK_ID = NOW - 2 * 86_400_000;

class FakeGeo implements Geolocation {
  watches: { success: PositionCallback; error: PositionErrorCallback | null; cleared: boolean }[] = [];
  getCurrentPosition(): void {}
  watchPosition(success: PositionCallback, error?: PositionErrorCallback | null): number {
    this.watches.push({ success, error: error ?? null, cleared: false });
    return this.watches.length;
  }
  clearWatch(id: number): void {
    this.watches[id - 1].cleared = true;
  }
  fix(lat: number, lon: number, accuracy: number): void {
    this.watches[this.watches.length - 1].success({ coords: { latitude: lat, longitude: lon, accuracy }, timestamp: Date.now() } as GeolocationPosition);
  }
  fail(code: number): void {
    this.watches[this.watches.length - 1].error!({ code, message: '' } as GeolocationPositionError);
  }
}

describe('PlaceCheckState', () => {
  let state: PlaceCheckState;
  let store: TraceStore;
  let recorder: FakeRecorder;
  let geo: FakeGeo | null;

  async function seedWalk(walkId = WALK_ID, east = 0, n = 11) {
    for (let i = 0; i < n; i++) await store.putPoint({ lat: i * 20 * DEG, lon: east * DEG, atMs: walkId + i * 30_000, walkId }, 10);
  }

  async function build(geolocation: FakeGeo | null = new FakeGeo()) {
    TestBed.resetTestingModule();
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(NOW);
    geo = geolocation;
    recorder = new FakeRecorder();
    TestBed.configureTestingModule({
      providers: [
        { provide: TraceRecorderService, useValue: recorder },
        { provide: GEOLOCATION, useValue: geolocation },
      ],
    });
    await TestBed.inject(TranslationService).setLang('en');
    store = TestBed.inject(TraceStore);
    state = TestBed.inject(PlaceCheckState);
  }

  beforeEach(() => build());
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  describe('compute', () => {
    it('says WALKED for a house 10 m from a stored walk, with the stretch to draw and the days', async () => {
      await seedWalk();
      const a = await state.compute('house', { lat: 100 * DEG, lon: 9.5 * DEG });
      expect(a.summary!.status).toBe('WALKED');
      expect(a.summary!.distanceM).toBe(10);
      expect(a.stretches).toHaveLength(1);
      expect(state.text(a).headline).toContain('You walked within 10 m of this house on');
    });

    it('draws the matched stretch only along the walk, 60 m each side of the nearest point', async () => {
      await seedWalk();
      const a = await state.compute('house', { lat: 100 * DEG, lon: 9.5 * DEG });
      const lats = a.stretches[0].map(([, lat]) => lat / DEG);
      expect(Math.round(Math.min(...lats))).toBe(40);
      expect(Math.round(Math.max(...lats))).toBe(160);
    });

    it('says CLOSE between 25 and 50 m, NONE farther, EMPTY with no walks', async () => {
      const empty = await state.compute('house', { lat: 0, lon: 0 });
      expect(empty.summary!.status).toBe('EMPTY');
      expect(state.text(empty).headline).toContain('There are no walks to compare yet.');
      await seedWalk();
      const close = await state.compute('house', { lat: 100 * DEG, lon: 40 * DEG });
      expect(close.summary!.status).toBe('CLOSE');
      expect(close.stretches).toEqual([]);
      const none = await state.compute('house', { lat: 100 * DEG, lon: 300 * DEG });
      expect(none.summary!.status).toBe('NONE');
    });

    it('says IMPRECISE for a fix worse than 50 m and INVALID_PLACE for a place that is not a place', async () => {
      await seedWalk();
      const loose = await state.compute('here', { lat: 100 * DEG, lon: 0 }, 60);
      expect(loose.summary!.status).toBe('IMPRECISE');
      const bad = await state.compute('spot', { lat: NaN, lon: 0 });
      expect(bad.summary!.status).toBe('INVALID_PLACE');
    });

    it('warns about a loose but accepted fix', async () => {
      await seedWalk();
      const a = await state.compute('here', { lat: 100 * DEG, lon: 0 }, 35);
      expect(state.text(a).notes.join(' ')).toContain('only accurate to about 35 m');
    });

    it('for here leaves out the walk now recording; for a house or a spot it counts', async () => {
      await seedWalk();
      recorder.live = WALK_ID;
      const here = await state.compute('here', { lat: 100 * DEG, lon: 0 }, 10);
      expect(here.summary!.status).toBe('EMPTY');
      const house = await state.compute('house', { lat: 100 * DEG, lon: 0 });
      expect(house.summary!.status).toBe('WALKED');
      const spot = await state.compute('spot', { lat: 100 * DEG, lon: 0 });
      expect(spot.summary!.status).toBe('WALKED');
    });

    it('reads saved walks of any age and says so in the negative answer', async () => {
      const old = NOW - 90 * 86_400_000;
      await seedWalk(old);
      await store.saveWalk(old, 'h', NOW);
      const none = await state.compute('house', { lat: 100 * DEG, lon: 300 * DEG });
      expect(state.text(none).headline).toContain('in the last 30 days or in your saved walks');
      const hit = await state.compute('house', { lat: 100 * DEG, lon: 0 });
      expect(state.text(hit).rows[0]).toContain('saved walk');
    });

    it('does not count a walk twice when its trace rows survived a save that was cut', async () => {
      await seedWalk();
      await store.saveWalk(WALK_ID, 'h', NOW);
      await seedWalk(); // the trace rows come back (a save cut between its two writes)
      const a = await state.compute('house', { lat: 100 * DEG, lon: 0 });
      expect(a.summary!.rows).toHaveLength(1);
    });

    it('is the same whether or not the trace switch is on (it reads what is stored)', async () => {
      await seedWalk();
      await store.setTraceOn(false);
      const off = await state.compute('house', { lat: 100 * DEG, lon: 0 });
      await store.setTraceOn(true);
      const on = await state.compute('house', { lat: 100 * DEG, lon: 0 });
      expect(off.summary).toEqual(on.summary);
    });

    it('writes nothing and makes no request: the stores are unchanged and fetch, XHR and sendBeacon never run', async () => {
      await seedWalk();
      const fetchSpy = vi.fn(() => Promise.reject(new Error('network')));
      const xhr = vi.fn();
      const beacon = vi.fn();
      vi.stubGlobal('fetch', fetchSpy);
      vi.stubGlobal('XMLHttpRequest', xhr);
      Object.defineProperty(navigator, 'sendBeacon', { value: beacon, configurable: true });
      const before = JSON.stringify(await store.allWalks(NOW));
      const savedBefore = await store.askedUpTo();
      await state.run('house', { lat: 100 * DEG, lon: 0 });
      await state.run('here', { lat: 100 * DEG, lon: 0 }, 10);
      expect(JSON.stringify(await store.allWalks(NOW))).toBe(before);
      expect(await store.askedUpTo()).toBe(savedBefore);
      expect(fetchSpy).not.toHaveBeenCalled();
      expect(xhr).not.toHaveBeenCalled();
      expect(beacon).not.toHaveBeenCalled();
    });

    it('is never run by constructing or injecting the state: only a handler calls compute', async () => {
      const read = vi.spyOn(store, 'placeWalks');
      TestBed.inject(PlaceCheckState);
      TestBed.inject(TraceView);
      await vi.advanceTimersByTimeAsync(60_000);
      expect(read).not.toHaveBeenCalled();
    });
  });

  describe('run, show and close', () => {
    it('shows the answer, draws the halo and says the headline once through the app live region', async () => {
      await seedWalk();
      const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
      await state.run('house', { lat: 100 * DEG, lon: 0 });
      expect(state.answer()!.summary!.status).toBe('WALKED');
      expect(TestBed.inject(TraceView).check()!.features).toHaveLength(1);
      expect(announce).toHaveBeenCalledTimes(1);
      expect(announce.mock.calls[0][0].key).toBe('trace.here.announce');
      expect((announce.mock.calls[0][0].params as { text: string }).text).toContain('You walked within');
    });

    it('requests the map to frame the place and the stretch, and again for each Show on map', async () => {
      await seedWalk();
      await state.run('house', { lat: 100 * DEG, lon: 10 * DEG });
      const first = state.showRequest()!;
      expect(first.bounds[0][1]).toBeLessThan(first.bounds[1][1]);
      state.show();
      expect(state.showRequest()!.n).toBe(first.n + 1);
    });

    it('Close withdraws the answer, the halo, the request and the announcement (the shell\'s region keeps its last text otherwise)', async () => {
      await seedWalk();
      const cancel = vi.spyOn(TestBed.inject(Announcer), 'cancel');
      await state.run('house', { lat: 100 * DEG, lon: 0 });
      state.close();
      expect(state.answer()).toBeNull();
      expect(TestBed.inject(TraceView).check()).toBeNull();
      expect(state.showRequest()).toBeNull();
      expect(cancel).toHaveBeenCalledWith({ key: 'trace.here.announce' });
    });

    it('holds a house with only an area as an answer that compared nothing', () => {
      const answer = { kind: 'house' as const, place: { lat: 1, lon: 1 }, summary: null, stretches: [], bounds: boundsOf({ lat: 1, lon: 1 }, []) };
      expect(state.text(answer).headline).toContain('This house has no exact spot yet, only an area.');
    });

    it('keeps no history: a second answer replaces the first', async () => {
      await seedWalk();
      await state.run('house', { lat: 100 * DEG, lon: 0 });
      await state.run('spot', { lat: 100 * DEG, lon: 300 * DEG });
      expect(state.answer()!.kind).toBe('spot');
      expect(state.answer()!.summary!.status).toBe('NONE');
    });
  });

  describe('where I am now', () => {
    it('watches for a fix with the click (the watch is called before anything is awaited)', () => {
      state.locateHere();
      expect(geo!.watches).toHaveLength(1);
      expect(state.locating()).toBe(true);
    });

    it('uses the first fix of 50 m or better, stops the watch, and answers about that place', async () => {
      await seedWalk();
      state.locateHere();
      geo!.fix(100 * DEG, 0, 12);
      await vi.advanceTimersByTimeAsync(0);
      expect(geo!.watches[0].cleared).toBe(true);
      expect(state.locating()).toBe(false);
      expect(state.answer()!.kind).toBe('here');
      expect(state.answer()!.summary!.status).toBe('WALKED');
    });

    it('at 15 s the best fix so far decides, and a fix worse than 50 m is not precise enough', async () => {
      await seedWalk();
      state.locateHere();
      geo!.fix(100 * DEG, 0, 80);
      expect(state.locating()).toBe(true);
      await vi.advanceTimersByTimeAsync(15_000);
      expect(state.answer()!.summary!.status).toBe('IMPRECISE');
    });

    it('says it timed out when there was no fix at all in 15 s', async () => {
      state.locateHere();
      await vi.advanceTimersByTimeAsync(15_000);
      expect(state.locateProblem()).toBe('timeout');
      expect(state.answer()).toBeNull();
      expect(state.locating()).toBe(false);
    });

    it('says the location is blocked when the permission is refused', () => {
      state.locateHere();
      geo!.fail(1);
      expect(state.locateProblem()).toBe('denied');
      expect(state.locating()).toBe(false);
      expect(geo!.watches[0].cleared).toBe(true);
    });

    it('keeps waiting after a position that is only unavailable for now (a fix may still come), and a new press clears the old problem', async () => {
      state.locateHere();
      geo!.fail(2);
      expect(state.locating()).toBe(true);
      expect(state.locateProblem()).toBeNull();
      state.cancelLocating();
      state.locateHere();
      geo!.fail(1);
      expect(state.locateProblem()).toBe('denied');
      state.locateHere();
      expect(state.locateProblem()).toBeNull();
    });

    it('Cancel stops the watch and nothing is reported afterwards', async () => {
      state.locateHere();
      state.cancelLocating();
      expect(geo!.watches[0].cleared).toBe(true);
      geo!.fix(0, 0, 5);
      await vi.advanceTimersByTimeAsync(20_000);
      expect(state.answer()).toBeNull();
      expect(state.locateProblem()).toBeNull();
    });

    it('with no Geolocation says so and offers no location', async () => {
      await build(null);
      expect(state.canLocate).toBe(false);
      state.locateHere();
      expect(state.locateProblem()).toBe('unavailable');
      expect(state.locating()).toBe(false);
    });

    it('never stores the fix: the answer holds the place for this answer only and Close drops it', async () => {
      await seedWalk();
      state.locateHere();
      geo!.fix(100 * DEG, 0, 12);
      await vi.advanceTimersByTimeAsync(0);
      expect((await store.allWalks(NOW)).trace[0].points).toHaveLength(11);
      state.close();
      expect(state.answer()).toBeNull();
    });
  });

  describe('boundsOf', () => {
    it('holds the place alone as a point and grows to the stretches', () => {
      expect(boundsOf({ lat: 2, lon: 3 }, [])).toEqual([[3, 2], [3, 2]]);
      expect(boundsOf({ lat: 2, lon: 3 }, [[[1, 1], [5, 4]]])).toEqual([[1, 1], [5, 4]]);
    });
  });
});
