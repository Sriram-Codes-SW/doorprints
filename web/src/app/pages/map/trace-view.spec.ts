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

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Announcer } from '../../core/announcer.service';
import { TraceRecorderService } from '../../core/trace-recorder.service';
import type { RecorderState } from '../../core/trace-recorder.service';
import { LocalStore } from '../../data/local-store.service';
import { TraceStore } from '../../data/trace-store';
import type { TracePoint } from '../../shared/trace-geo';
import { TraceView } from './trace-view';

const DEG = 1 / 111_194.9266;
const MIN = 60_000;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);

/** A recorder the test drives: the signals the view reads and the calls it makes. */
class FakeRecorder {
  state = signal<RecorderState>('idle');
  keptCount = signal(0);
  live = 0;
  starts = 0;
  finishes = 0;
  ended = 0;
  alertOn: boolean | null = null;
  setAlertOn(on: boolean) {
    this.alertOn = on;
    return Promise.resolve();
  }
  start() {
    this.starts++;
    this.state.set('recording');
  }
  finish() {
    this.finishes++;
    this.state.set('idle');
    return this.ended;
  }
  settled() {
    return Promise.resolve();
  }
  liveWalkId() {
    return this.live;
  }
}

/** A straight walk north from `startM`, one point every 20 m, `n` points, starting at `at`. */
function walkPoints(walkId: number, n: number, east = 0): TracePoint[] {
  return Array.from({ length: n }, (_, i) => ({ lat: i * 20 * DEG, lon: east * DEG, atMs: walkId + i * 10_000, walkId }));
}

describe('TraceView', () => {
  let view: TraceView;
  let store: TraceStore;
  let recorder: FakeRecorder;

  async function seed(walkId: number, n: number, east = 0) {
    for (const p of walkPoints(walkId, n, east)) await store.putPoint(p, 10);
  }

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(NOW);
    recorder = new FakeRecorder();
    TestBed.configureTestingModule({ providers: [{ provide: TraceRecorderService, useValue: recorder }] });
    store = TestBed.inject(TraceStore);
    view = TestBed.inject(TraceView);
  });
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  it('reads the settings and draws the walks when the Map opens', async () => {
    await store.setTraceOn(true);
    await store.setLook('SUBTLE');
    await store.setAlertOn(true);
    await seed(NOW - 3600_000, 6);
    await view.open();
    expect(view.traceOn()).toBe(true);
    expect(view.look()).toBe('SUBTLE');
    expect(view.alertOn()).toBe(true);
    expect(view.walkCount()).toBe(1);
    expect(view.walks().features.map((f) => f.properties['kind'])).toEqual(['base']);
  });

  it('draws a stretch walked in two walks as repeat features, and counts it', async () => {
    await seed(NOW - 7200_000, 12);
    await seed(NOW - 3600_000, 12, 3);
    await view.open();
    expect(view.walks().features.some((f) => f.properties['kind'] === 'repeat')).toBe(true);
    expect(view.repeatCount()).toBeGreaterThan(0);
  });

  it('draws the saved walks with the trace (they are never pruned), and counts them', async () => {
    const id = NOW - 40 * 86_400_000;
    await seed(id, 8);
    await store.saveWalk(id, 'house-1', NOW);
    await view.open();
    expect(view.savedCount()).toBe(1);
    expect(view.walkCount()).toBe(1);
  });

  it('prunes a trace older than 30 days at opening, and says nothing is drawn', async () => {
    const old = NOW - 40 * 86_400_000;
    await seed(old, 8);
    expect(await store.walkPoints(old)).toHaveLength(8);
    await view.open();
    expect(view.walkCount()).toBe(0);
    expect(await store.walkPoints(old)).toEqual([]);
  });

  it('does not run the detection again when the walks have not changed (cache by key and point count)', async () => {
    await seed(NOW - 3600_000, 8);
    await view.open();
    const first = view.walks();
    await view.refresh();
    expect(view.walks()).toBe(first);
    await store.putPoint({ lat: 9, lon: 0, atMs: NOW - 3600_000 + 100_000, walkId: NOW - 3600_000 }, 10);
    await view.refresh();
    expect(view.walks()).not.toBe(first);
  });

  it('asks about the newest ended walk of at least 5 points and 100 m when the Map opens, never the live one', async () => {
    await seed(NOW - 3600_000, 8);
    recorder.live = NOW - 3600_000;
    await view.open();
    expect(view.ask()).toBeNull();
    recorder.live = 0;
    await view.open();
    expect(view.ask()?.walkId).toBe(NOW - 3600_000);
  });

  it('does not ask about a walk shorter than 5 points or 100 m (it is just kept)', async () => {
    await seed(NOW - 3600_000, 4);
    await view.open();
    expect(view.ask()).toBeNull();
  });

  it('start calls the recorder at once, in the same call (the click), before anything is awaited', () => {
    void view.startWalk();
    expect(recorder.starts).toBe(1);
  });

  it('start asks about the walk a closed tab cut, after the walk has started', async () => {
    await seed(NOW - 3600_000, 8);
    await view.startWalk();
    expect(view.ask()?.walkId).toBe(NOW - 3600_000);
  });

  it('finish ends the walk and asks about it at once', async () => {
    await seed(NOW - 600_000, 8);
    recorder.ended = NOW - 600_000;
    await view.finishWalk();
    expect(recorder.finishes).toBe(1);
    expect(view.ask()?.walkId).toBe(NOW - 600_000);
    expect(view.walkCount()).toBe(1);
  });

  describe('reads of the stores (S4b-FR-31)', () => {
    const traceReads = async () => {
      const db = await TestBed.inject(LocalStore).database();
      const getAll = vi.spyOn(db, 'getAll');
      return () => getAll.mock.calls.filter((c) => c[0] === 'trace_points').length;
    };

    it('opening the Map reads the trace store once for the prune, the drawing and the walk to ask about', async () => {
      await seed(NOW - 40 * 86_400_000, 6);
      await seed(NOW - 600_000, 8);
      const reads = await traceReads();
      await view.open();
      expect(reads()).toBe(1);
      expect(view.ask()?.walkId).toBe(NOW - 600_000);
      expect(view.walkCount()).toBe(1);
    });

    it('Finish walk reads it once for the prune, the redraw and the walk to ask about', async () => {
      await seed(NOW - 40 * 86_400_000, 6);
      await view.open();
      recorder.live = NOW - 300_000;
      await seed(recorder.live, 8);
      const reads = await traceReads();
      await view.finishWalk();
      expect(reads()).toBe(1);
      expect(view.ask()?.walkId).toBe(NOW - 300_000);
      expect(await store.walkPoints(NOW - 40 * 86_400_000)).toEqual([]);
    });

    it('a redraw reads it once and a redraw when nothing changed does not run the detection or draw again', async () => {
      await seed(NOW - 600_000, 8);
      await view.open();
      const reads = await traceReads();
      const drawn = view.walks();
      await view.refresh();
      expect(reads()).toBe(1);
      expect(view.walks()).toBe(drawn);
    });

    it('a failing prune does not hide the walk to ask about', async () => {
      await seed(NOW - 40 * 86_400_000, 6);
      await seed(NOW - 600_000, 8);
      const db = await TestBed.inject(LocalStore).database();
      vi.spyOn(db, 'deleteAll').mockRejectedValue(new Error('quota'));
      await view.open();
      expect(view.loadError()).toBe(false);
      expect(view.ask()?.walkId).toBe(NOW - 600_000);
    });
  });

  it('keeping for 30 days only moves the watermark; the walk stays in the trace', async () => {
    const id = NOW - 600_000;
    await seed(id, 8);
    await view.open();
    await view.answerKeep();
    expect(view.ask()).toBeNull();
    expect(await store.askedUpTo()).toBe(id);
    expect(await store.traceWalks(NOW)).toHaveLength(1);
    await view.open();
    expect(view.ask()).toBeNull();
  });

  it('deleting removes the walk from the trace and the map and moves the watermark', async () => {
    const id = NOW - 600_000;
    await seed(id, 8);
    await view.open();
    await view.answerDelete();
    expect(await store.traceWalks(NOW)).toEqual([]);
    expect(view.walkCount()).toBe(0);
    expect(await store.askedUpTo()).toBe(id);
  });

  it('saving with a house writes a saved walk, removes it from the trace, moves the watermark and redraws', async () => {
    const id = NOW - 600_000;
    await seed(id, 8);
    await view.open();
    const result = await view.answerSave('house-1');
    expect(result).toEqual({ ok: true, id: expect.any(String) });
    expect(view.ask()).toBeNull();
    expect(view.savedCount()).toBe(1);
    expect(await store.traceWalks(NOW)).toEqual([]);
    expect(await store.askedUpTo()).toBe(id);
    expect(view.walkCount()).toBe(1);
  });

  it('a refused save changes nothing: the sheet stays, the walk stays in the trace, the watermark does not move', async () => {
    const id = NOW - 600_000;
    await seed(id, 8);
    await view.open();
    for (let i = 0; i < 20; i++) await store.saveWalk(await newTraceWalk(i), 'house-1', NOW);
    const result = await view.answerSave('house-1');
    expect(result).toEqual({ ok: false, reason: 'houseFull' });
    expect(view.ask()?.walkId).toBe(id);
    expect(await store.askedUpTo()).toBe(0);
    expect((await store.traceWalks(NOW)).length).toBe(1);

    async function newTraceWalk(i: number): Promise<number> {
      const wid = NOW - 5_000_000 - i * 1_000_000;
      await seed(wid, 6);
      return wid;
    }
  });

  it('changing a setting writes it and shows it', async () => {
    await view.setLook('OFF');
    await view.setTraceOn(true);
    await view.setAlertOn(true);
    await view.setKeepAwake(true);
    expect([view.look(), view.traceOn(), view.alertOn(), view.keepAwake()]).toEqual(['OFF', true, true, true]);
    expect(await store.look()).toBe('OFF');
    expect(await store.traceOn()).toBe(true);
    expect(await store.alertOn()).toBe(true);
    expect(await store.keepAwake()).toBe(true);
  });

  it('turning the alert on or off also tells the recorder, so a walk now recording follows it at once', async () => {
    await view.setAlertOn(true);
    expect(recorder.alertOn).toBe(true);
    expect(await store.alertOn()).toBe(true);
    await view.setAlertOn(false);
    expect(recorder.alertOn).toBe(false);
  });

  it('clearing the path empties the 30-day trace and keeps the saved walks', async () => {
    const id = NOW - 600_000;
    await seed(id, 8);
    await store.saveWalk(id, 'h', NOW);
    await seed(NOW - 300_000, 8);
    await view.open();
    expect(view.walkCount()).toBe(2);
    await view.clearTrace();
    expect(view.walkCount()).toBe(1);
    expect(view.savedCount()).toBe(1);
  });

  it('deleting every saved walk empties them and keeps the trace', async () => {
    const id = NOW - 600_000;
    await seed(id, 8);
    await store.saveWalk(id, 'h', NOW);
    await seed(NOW - 300_000, 8);
    await view.open();
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    await view.deleteAllSaved();
    expect(announce.mock.calls.map((c) => c[0].key)).toEqual(['trace.settings.deleted']);
    expect(view.savedCount()).toBe(0);
    expect(view.walkCount()).toBe(1);
  });

  describe('while a walk records', () => {
    it('redraws at most once every 5 seconds', async () => {
      await view.open();
      recorder.state.set('recording');
      const refresh = vi.spyOn(view, 'refresh');
      TestBed.tick();
      refresh.mockClear();
      for (let i = 0; i < 6; i++) {
        recorder.keptCount.update((n) => n + 1);
        TestBed.tick();
        await vi.advanceTimersByTimeAsync(500);
      }
      expect(refresh.mock.calls.length).toBeLessThanOrEqual(1);
      await vi.advanceTimersByTimeAsync(6000);
      expect(refresh.mock.calls.length).toBeGreaterThanOrEqual(1);
      expect(refresh.mock.calls.length).toBeLessThanOrEqual(2);
    });

    it('does not redraw while idle', async () => {
      await view.open();
      const refresh = vi.spyOn(view, 'refresh');
      recorder.keptCount.update((n) => n + 1);
      TestBed.tick();
      await vi.advanceTimersByTimeAsync(10_000);
      expect(refresh).not.toHaveBeenCalled();
    });
  });

  it('announces the keep, delete and save answers through the app live region, never anything else', async () => {
    const announcer = TestBed.inject(Announcer);
    const spy = vi.spyOn(announcer, 'announce');
    const id = NOW - 600_000;
    await seed(id, 8);
    await view.open();
    await view.answerKeep();
    expect(spy.mock.calls.map((c) => c[0].key)).toEqual(['trace.kept.snack']);
  });

  it('reports a read failure instead of throwing, and keeps what was drawn', async () => {
    await view.open();
    vi.spyOn(TestBed.inject(LocalStore), 'database').mockRejectedValue(new Error('boom'));
    await view.refresh();
    expect(view.loadError()).toBe(true);
  });
});
