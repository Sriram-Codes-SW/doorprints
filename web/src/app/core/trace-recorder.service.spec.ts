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
import vectorsJson from '../../../../docs/schemas/trace-repeat-vectors.json';
import { LocalStore } from '../data/local-store.service';
import { TraceStore } from '../data/trace-store';
import type { TracePointRow } from '../data/trace-rows';
import { TRACE } from '../shared/trace-geo';
import { AUDIO_CONTEXT_FACTORY, VIBRATE } from './alert-sound.service';
import type { AudioContextLike } from './alert-sound.service';
import { GEOLOCATION, RECORDER_OPTIONS, TraceRecorderService, WAKE_LOCK, WATCH_OPTIONS } from './trace-recorder.service';

const DEG = 1 / 111_194.9266;
const T0 = Date.UTC(2026, 9, 6, 9, 0, 0);
const MIN = 60_000;

/** A Geolocation the test drives: each `watchPosition` is a handle with its callbacks; `clearWatch` marks it cleared. */
class FakeGeolocation implements Geolocation {
  watches: { id: number; success: PositionCallback; error: PositionErrorCallback | null; options: PositionOptions | undefined; cleared: boolean }[] = [];
  currentPositionCalls = 0;
  getCurrentPosition(): void {
    this.currentPositionCalls++;
  }
  watchPosition(success: PositionCallback, error?: PositionErrorCallback | null, options?: PositionOptions): number {
    const id = this.watches.length + 1;
    this.watches.push({ id, success, error: error ?? null, options, cleared: false });
    return id;
  }
  clearWatch(id: number): void {
    const w = this.watches.find((x) => x.id === id);
    if (w) w.cleared = true;
  }
  get live() {
    return this.watches.filter((w) => !w.cleared);
  }
  /** A fix to the newest watch that is not cleared (or, with `stale`, to the newest one at all, as a late callback would). */
  fix(northM: number, atMs: number, accuracy = 10, stale = false, east = 0): void {
    const w = stale ? this.watches[this.watches.length - 1] : this.live[this.live.length - 1];
    w.success({ coords: { latitude: northM * DEG, longitude: east * DEG, accuracy }, timestamp: atMs } as unknown as GeolocationPosition);
  }
  fixAt(lat: number, lon: number, atMs: number, accuracy = 10): void {
    this.live[this.live.length - 1].success({ coords: { latitude: lat, longitude: lon, accuracy }, timestamp: atMs } as unknown as GeolocationPosition);
  }
  fail(code: number): void {
    this.live[this.live.length - 1].error?.({ code, message: '', PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 } as unknown as GeolocationPositionError);
  }
}

class FakeAudio implements AudioContextLike {
  state: AudioContextState = 'suspended';
  currentTime = 0;
  destination = {};
  tones = 0;
  resume() {
    this.state = 'running';
    return Promise.resolve();
  }
  close() {
    return Promise.resolve();
  }
  addEventListener(): void {}
  createGain() {
    return { gain: { value: 0 }, connect: () => undefined };
  }
  createOscillator() {
    return { type: '', frequency: { value: 0 }, connect: () => undefined, start: () => undefined, stop: () => void this.tones++ };
  }
}

class FakeWakeLock {
  requests = 0;
  released = 0;
  fail = false;
  request(): Promise<WakeLockSentinel> {
    this.requests++;
    if (this.fail) return Promise.reject(new DOMException('no', 'NotAllowedError'));
    return Promise.resolve({ release: () => (this.released++, Promise.resolve()) } as unknown as WakeLockSentinel);
  }
}

let visibility: 'visible' | 'hidden' = 'visible';
const setVisibility = (v: 'visible' | 'hidden') => {
  visibility = v;
  document.dispatchEvent(new Event('visibilitychange'));
};

describe('TraceRecorderService', () => {
  let geo: FakeGeolocation;
  let audio: FakeAudio;
  let audioCreated: number;
  let vibrate: ReturnType<typeof vi.fn>;
  let lock: FakeWakeLock;
  let service: TraceRecorderService;
  let store: TraceStore;
  let local: LocalStore;

  async function build(opts: { geo?: boolean; lock?: boolean; recorder?: { minDistanceM?: number; minGapMs?: number } } = {}) {
    geo = new FakeGeolocation();
    audio = new FakeAudio();
    audioCreated = 0;
    vibrate = vi.fn();
    lock = new FakeWakeLock();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: GEOLOCATION, useValue: opts.geo === false ? null : geo },
        { provide: WAKE_LOCK, useValue: opts.lock === false ? null : lock },
        { provide: AUDIO_CONTEXT_FACTORY, useValue: () => (audioCreated++, audio) },
        { provide: VIBRATE, useValue: vibrate },
        ...(opts.recorder ? [{ provide: RECORDER_OPTIONS, useValue: opts.recorder }] : []),
      ],
    });
    local = TestBed.inject(LocalStore);
    await local.ready();
    store = TestBed.inject(TraceStore);
    service = TestBed.inject(TraceRecorderService);
  }
  const rows = async () => (await (await local.database()).getAll<TracePointRow>('trace_points')).sort((a, b) => a.at - b.at);
  /** Starts a walk and lets what `start` kicked off finish. */
  const startWalk = async () => {
    service.start();
    await service.settled();
  };

  beforeEach(async () => {
    visibility = 'visible';
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => visibility });
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(T0);
    await build();
  });
  afterEach(() => {
    service.ngOnDestroy();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    Reflect.deleteProperty(document, 'visibilityState');
  });

  describe('asking for the location', () => {
    it('asks for nothing when the service is created: no watch, no one-off position, no sound context (PRV-031)', () => {
      expect(geo.watches).toHaveLength(0);
      expect(geo.currentPositionCalls).toBe(0);
      expect(audioCreated).toBe(0);
      expect(service.state()).toBe('idle');
    });

    it('Start a walk calls watchPosition once, with the options of docs/11 5.27.8, and makes the audio context in the same call', () => {
      service.start();
      expect(geo.watches).toHaveLength(1);
      expect(geo.watches[0].options).toEqual({ enableHighAccuracy: true, maximumAge: 0, timeout: 30_000 });
      expect(WATCH_OPTIONS).toEqual({ enableHighAccuracy: true, maximumAge: 0, timeout: 30_000 });
      expect(audioCreated).toBe(1); // inside the click, before anything is awaited
      expect(audio.state).toBe('running');
      expect(service.state()).toBe('recording');
    });

    it('a second Start while recording changes nothing', () => {
      service.start();
      service.start();
      expect(geo.watches).toHaveLength(1);
    });

    it('says the browser cannot give a location, and starts nothing', () => {
      return build({ geo: false }).then(() => {
        service.start();
        expect(service.problem()).toBe('unsupported');
        expect(service.state()).toBe('idle');
      });
    });

    it('a refused permission says so and ends the walk; an unavailable position says so and keeps watching', async () => {
      await startWalk();
      geo.fail(2);
      expect(service.problem()).toBe('unavailable');
      expect(service.state()).toBe('recording');
      geo.fix(0, T0 + 1000);
      await service.settled();
      expect(service.problem()).toBeNull();
      geo.fail(1);
      expect(service.problem()).toBe('denied');
      expect(service.state()).toBe('idle');
      expect(geo.live).toHaveLength(0);
    });
  });

  describe('keeping fixes', () => {
    it('applies the 50 m gate and the 20 m thinning, writes each kept point once, and gives the walk the id of its first point', async () => {
      await startWalk();
      geo.fix(0, T0 + 1000, 51); // too inaccurate
      geo.fix(0, T0 + 2000, 50); // exactly 50: kept, and the first point
      geo.fix(10, T0 + 17_000); // under 20 m: dropped
      geo.fix(25, T0 + 32_000);
      geo.fix(30, T0 + 47_000); // 5 m after the last kept point
      geo.fix(60, T0 + 62_000);
      await service.settled();
      const kept = await rows();
      expect(kept.map((r) => r.at)).toEqual([T0 + 2000, T0 + 32_000, T0 + 62_000]);
      expect(new Set(kept.map((r) => r.walk))).toEqual(new Set([T0 + 2000]));
      expect(kept.map((r) => r.id)).toEqual([`${T0 + 2000}-${T0 + 2000}`, `${T0 + 2000}-${T0 + 32_000}`, `${T0 + 2000}-${T0 + 62_000}`]);
      expect(kept[0].acc).toBe(50);
      expect(service.keptCount()).toBe(3);
      expect(service.liveWalkId()).toBe(T0 + 2000);
      expect(service.livePoints().map((p) => p.atMs)).toEqual(kept.map((r) => r.at));
    });

    it('Finish walk stops the watch, ends the walk and returns its id; the next Start is a new walk id', async () => {
      await startWalk();
      geo.fix(0, T0 + 1000);
      geo.fix(40, T0 + 16_000);
      await service.settled();
      const id = service.finish();
      expect(id).toBe(T0 + 1000);
      expect(service.state()).toBe('idle');
      expect(service.liveWalkId()).toBe(0);
      expect(geo.live).toHaveLength(0);
      await startWalk();
      geo.fix(80, T0 + 5 * MIN);
      await service.settled();
      expect((await rows()).map((r) => r.walk)).toEqual([T0 + 1000, T0 + 1000, T0 + 5 * MIN]);
    });

    it('a walk that never gets a kept fix has no id and no row', async () => {
      await startWalk();
      geo.fix(0, T0 + 1000, 80);
      await service.settled();
      expect(service.finish()).toBe(0);
      expect(await rows()).toEqual([]);
    });

    it('a gap of 30 minutes or more between kept points starts a new walk id (and 29:59 does not)', async () => {
      await startWalk();
      geo.fix(0, T0);
      geo.fix(100, T0 + 29 * MIN + 59_000);
      geo.fix(200, T0 + 29 * MIN + 59_000 + 30 * MIN);
      await service.settled();
      expect((await rows()).map((r) => r.walk)).toEqual([T0, T0, T0 + 59 * MIN + 59_000]);
      expect(service.keptCount()).toBe(1);
    });

    it('closing the page ends the walk (a new Start is a new walk)', async () => {
      await startWalk();
      geo.fix(0, T0);
      await service.settled();
      window.dispatchEvent(new Event('pagehide'));
      expect(service.state()).toBe('idle');
      expect(geo.live).toHaveLength(0);
      expect(service.liveWalkId()).toBe(0);
    });

    it('says whether the walk is kept in the browser (false in memory: private browsing)', async () => {
      await startWalk();
      expect(service.keptInBrowser()).toBe(false); // jsdom has no IndexedDB
    });

    it('writes no other record and makes no request: only the trace store is written, no network', async () => {
      const fail = vi.fn(() => {
        throw new Error('network');
      });
      vi.stubGlobal('fetch', fail);
      vi.stubGlobal('XMLHttpRequest', fail);
      const before = local.revision();
      await startWalk();
      for (let i = 0; i < 5; i++) geo.fix(i * 30, T0 + i * 20_000);
      await service.settled();
      service.finish();
      expect(fail).not.toHaveBeenCalled();
      expect(local.revision()).toBe(before);
    });
  });

  describe('a full or refusing store', () => {
    it('stops the walk with the "full" message on a quota error and does not retry in a loop', async () => {
      await startWalk();
      const put = vi.spyOn(store, 'putPoint').mockRejectedValue(new DOMException('The quota has been exceeded.', 'QuotaExceededError'));
      geo.fix(0, T0);
      await service.settled();
      expect(service.problem()).toBe('full');
      expect(service.state()).toBe('idle');
      expect(geo.live).toHaveLength(0);
      geo.fix(60, T0 + 15_000, 10, true); // a late callback of the watch that was cleared
      geo.fix(120, T0 + 30_000, 10, true);
      await service.settled();
      expect(put).toHaveBeenCalledTimes(1);
    });

    it('stops with the "storage" message on any other store failure', async () => {
      await startWalk();
      vi.spyOn(store, 'putPoint').mockRejectedValue(new Error('boom'));
      geo.fix(0, T0);
      await service.settled();
      expect(service.problem()).toBe('storage');
      expect(service.state()).toBe('idle');
    });
  });

  describe('a hidden page', () => {
    it('pauses when hidden (the watch is dropped, a late fix is ignored) and resumes when visible, saying so', async () => {
      await startWalk();
      geo.fix(0, T0);
      await service.settled();
      setVisibility('hidden');
      expect(service.state()).toBe('paused');
      expect(geo.live).toHaveLength(0);
      geo.fix(500, T0 + 20_000, 10, true);
      await service.settled();
      expect(await rows()).toHaveLength(1);
      vi.setSystemTime(T0 + 2 * MIN);
      setVisibility('visible');
      expect(service.state()).toBe('recording');
      expect(geo.watches).toHaveLength(2);
      expect(geo.live).toHaveLength(1);
      expect(service.pausedNotice()).toBe(true);
      service.dismissPausedNotice();
      expect(service.pausedNotice()).toBe(false);
    });

    it('marks the next kept point resumed after MORE than 5 minutes hidden, not after exactly 5 or less (same walk)', async () => {
      await startWalk();
      geo.fix(0, T0);
      await service.settled();
      // exactly 5 minutes
      setVisibility('hidden');
      vi.setSystemTime(T0 + 5 * MIN);
      setVisibility('visible');
      geo.fix(100, T0 + 5 * MIN + 1000);
      await service.settled();
      // 5 minutes and one millisecond
      setVisibility('hidden');
      vi.setSystemTime(T0 + 5 * MIN + 1000 + 5 * MIN + 1);
      setVisibility('visible');
      geo.fix(200, T0 + 10 * MIN + 2000);
      await service.settled();
      const kept = await rows();
      expect(kept).toHaveLength(3);
      expect(kept.map((r) => r.walk)).toEqual([T0, T0, T0]);
      expect('resumed' in kept[1]).toBe(false);
      expect(kept[2].resumed).toBe(true);
      expect(TRACE.pauseSplitMs).toBe(300_000);
    });

    it('a pause of 30 minutes or more is a new walk, and its first point is not "resumed"', async () => {
      await startWalk();
      geo.fix(0, T0);
      await service.settled();
      setVisibility('hidden');
      vi.setSystemTime(T0 + 40 * MIN);
      setVisibility('visible');
      geo.fix(500, T0 + 40 * MIN);
      await service.settled();
      const kept = await rows();
      expect(kept.map((r) => r.walk)).toEqual([T0, T0 + 40 * MIN]);
      expect('resumed' in kept[1]).toBe(false);
    });

    it('does nothing for a page that is not recording', () => {
      setVisibility('hidden');
      setVisibility('visible');
      expect(service.state()).toBe('idle');
      expect(geo.watches).toHaveLength(0);
    });
  });

  describe('keeping the screen on (optional)', () => {
    it('does not ask for it unless the setting is on', async () => {
      await startWalk();
      expect(lock.requests).toBe(0);
    });

    it('asks at the start, releases at the finish, and asks again when the page is visible again', async () => {
      await store.setKeepAwake(true);
      await startWalk();
      expect(lock.requests).toBe(1);
      setVisibility('hidden');
      setVisibility('visible');
      await service.settled();
      await Promise.resolve();
      expect(lock.requests).toBe(2);
      service.finish();
      expect(lock.released).toBe(1);
    });

    it('says so when the browser refuses it, and records all the same', async () => {
      await store.setKeepAwake(true);
      lock.fail = true;
      await startWalk();
      expect(service.keepAwakeFailed()).toBe(true);
      geo.fix(0, T0);
      await service.settled();
      expect(await rows()).toHaveLength(1);
    });

    it('is not offered where the browser has no Wake Lock, and a walk still records', async () => {
      await build({ lock: false });
      await store.setKeepAwake(true);
      expect(service.wakeLockSupported).toBe(false);
      await startWalk();
      geo.fix(0, T0);
      await service.settled();
      expect(await rows()).toHaveLength(1);
    });
  });

  describe('the repeated-path alert', () => {
    /** A walked street 400 m long yesterday (stored), and a live walk coming up it. */
    const seedStreet = async () => {
      for (let i = 0; i <= 20; i++) await store.putPoint({ lat: i * 20 * DEG, lon: 0, atMs: T0 - 86_400_000 + i * 15_000, walkId: T0 - 86_400_000 }, 10);
    };
    const walkUp = async (from = -50, steps = 25, east = 0) => {
      for (let i = 0; i < steps; i++) geo.fix(from + i * 20, T0 + i * 15_000, 10, false, east);
      await service.settled();
    };

    it('does nothing with the alert off, however many times the path is walked', async () => {
      await seedStreet();
      const raised: number[] = [];
      service.onAlert = (m) => raised.push(m);
      await startWalk();
      await walkUp();
      expect(raised).toEqual([]);
      expect(service.alertRaised()).toBe(0);
      expect(audio.tones).toBe(0);
    });

    it('rings once on a path walked before: beeps, counts, and hands over the run length (100 m or more)', async () => {
      await seedStreet();
      await store.setAlertOn(true);
      const raised: number[] = [];
      service.onAlert = (m) => raised.push(m);
      await startWalk();
      await walkUp();
      expect(raised).toHaveLength(1);
      expect(raised[0]).toBeGreaterThanOrEqual(TRACE.alertMinRunM);
      expect(service.alertRaised()).toBe(1);
      expect(audio.tones).toBe(2); // one beep: two tones
      expect(vibrate).not.toHaveBeenCalled();
    });

    it('with the sound not running it shows the banner (the counter) and vibrates instead of beeping', async () => {
      await seedStreet();
      await store.setAlertOn(true);
      await startWalk();
      audio.state = 'suspended';
      await walkUp();
      expect(service.alertRaised()).toBe(1);
      expect(audio.tones).toBe(0);
      expect(vibrate).toHaveBeenCalledWith([200, 100, 200]);
    });

    it('does not ring for the walk itself or for a path nobody walked', async () => {
      await seedStreet();
      await store.setAlertOn(true);
      await startWalk();
      await walkUp(-50, 25, 300); // 300 m east of the walked street
      expect(service.alertRaised()).toBe(0);
    });

    it('is independent of how repeated paths look: the same ring with the look OFF', async () => {
      await seedStreet();
      await store.setAlertOn(true);
      await store.setLook('OFF');
      await startWalk();
      await walkUp();
      expect(service.alertRaised()).toBe(1);
    });

    it('a gap of 30 minutes starts a new walk, and the walk that just ended then counts for the alert', async () => {
      await store.setAlertOn(true);
      await startWalk();
      // Walk 1: up a street (nothing stored yet, so nothing rings).
      for (let i = 0; i < 25; i++) geo.fix(i * 20, T0 + i * 15_000);
      await service.settled();
      expect(service.alertRaised()).toBe(0);
      // 40 minutes later the same street again: a new walk id, and walk 1 is now one of the others.
      const later = T0 + 40 * MIN;
      for (let i = 0; i < 25; i++) geo.fix(i * 20, later + i * 15_000);
      await service.settled();
      expect(service.alertRaised()).toBe(1);
      expect(service.liveWalkId()).toBe(later);
    });

    it('a walk that ended less than 30 minutes before is left out (finish at a house, walk back the same way)', async () => {
      for (let i = 0; i <= 20; i++) await store.putPoint({ lat: i * 20 * DEG, lon: 0, atMs: T0 - 5 * MIN + i * 1000, walkId: T0 - 5 * MIN }, 10);
      await store.setAlertOn(true);
      await startWalk();
      await walkUp();
      expect(service.alertRaised()).toBe(0);
    });

    it('counts the saved walks too, whatever their age', async () => {
      const old = T0 - 90 * 86_400_000;
      for (let i = 0; i <= 20; i++) await store.putPoint({ lat: i * 20 * DEG, lon: 0, atMs: old + i * 15_000, walkId: old }, 10);
      await store.saveWalk(old, 'h1', T0);
      await store.setAlertOn(true);
      await startWalk();
      await walkUp();
      expect(service.alertRaised()).toBe(1);
    });
  });

  describe('the shared alert vectors, through the recorder (docs/06 TC-U-148)', () => {
    type Pt = number[];
    const cases = (vectorsJson as unknown as { cases: { id: string; kind: string; live: Pt[]; others: Pt[][]; expect: { alertAtIndexes: number[] } }[] }).cases.filter((c) => c.kind === 'alert');

    it('has the 8 alert cases', () => {
      expect(cases).toHaveLength(8);
    });

    for (const look of ['CLEAR', 'OFF'] as const) {
      it.each(cases)(`$id (look ${look})`, async (c) => {
        await build({ recorder: { minDistanceM: 1 } });
        // The vector's times are relative; move them so everything is within the last 30 days and the live walk is "now".
        const base = T0 - 5 * 86_400_000;
        // (Walks of one device never overlap in time, so a second "other" is moved two hours earlier than the first.)
        for (const [k, other] of c.others.entries()) {
          const shift = base - k * 2 * 3_600_000;
          for (const p of other) await store.putPoint({ lat: p[0], lon: p[1], atMs: shift + p[2], walkId: shift + other[0][2] }, 10);
        }
        await store.setAlertOn(true);
        await store.setLook(look);
        const rang: number[] = [];
        let fed = 0;
        service.onAlert = () => rang.push(fed - 1);
        await startWalk();
        for (const p of c.live) {
          fed++;
          geo.fixAt(p[0], p[1], base + p[2]);
          await service.settled();
        }
        expect(rang).toEqual(c.expect.alertAtIndexes);
      });
    }
  });
});
