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
import { TraceRecorder } from './trace-recorder';
import type { Fix } from './trace-recorder';

const DEG = 1 / K;
const T0 = 1_800_000_000_000;
/** A fix `northM` north of the origin, `sec` seconds after T0, with `acc` metres of accuracy. */
const fix = (northM: number, sec: number, acc = 10): Fix => ({ lat: northM * DEG, lon: 0, atMs: T0 + sec * 1000, accuracyM: acc });

describe('TraceRecorder: the gate and the thinning (the phones\' TrackRecorder, same rules)', () => {
  it('keeps the first fix, then one 20 m or more away, and drops the ones between', () => {
    const r = new TraceRecorder();
    expect(r.accept(fix(0, 0))).not.toBeNull();
    expect(r.accept(fix(10, 15))).toBeNull();
    expect(r.accept(fix(19.9, 30))).toBeNull();
    expect(r.accept(fix(20.1, 45))).not.toBeNull();
    expect(r.accept(fix(30, 60))).toBeNull(); // 9.9 m from the last kept point
    expect(r.accept(fix(45, 75))).not.toBeNull();
  });

  it('keeps a stay once every 5 minutes: 4:59 drops, 5:00 keeps', () => {
    const r = new TraceRecorder();
    r.accept(fix(0, 0));
    expect(r.accept(fix(0, 299))).toBeNull();
    expect(r.accept(fix(0, 300))).not.toBeNull();
    expect(TRACE.thinGapMs).toBe(300_000);
    expect(TRACE.thinDistanceM).toBe(20);
  });

  it('refuses a fix worse than 50 m, accepts exactly 50, and refuses one that is not finite or negative', () => {
    const r = new TraceRecorder();
    expect(r.accept(fix(0, 0, 50.01))).toBeNull();
    expect(r.accept(fix(0, 1, Number.NaN))).toBeNull();
    expect(r.accept(fix(0, 2, -1))).toBeNull();
    expect(r.accept(fix(0, 3, 50))).not.toBeNull();
  });

  it('refuses a fix off the globe or not finite, and one not newer than the last kept point', () => {
    const r = new TraceRecorder();
    expect(r.accept({ lat: 91, lon: 0, atMs: T0, accuracyM: 5 })).toBeNull();
    expect(r.accept({ lat: 0, lon: Number.NaN, atMs: T0, accuracyM: 5 })).toBeNull();
    expect(r.accept({ lat: 0, lon: 0, atMs: Number.NaN, accuracyM: 5 })).toBeNull();
    expect(r.accept(fix(0, 10))).not.toBeNull();
    expect(r.accept(fix(500, 10))).toBeNull();
    expect(r.accept(fix(500, 5))).toBeNull();
  });

  it('a gate failure does not move the thinning anchor', () => {
    const r = new TraceRecorder();
    r.accept(fix(0, 0));
    expect(r.accept(fix(100, 15, 80))).toBeNull(); // too inaccurate
    expect(r.accept(fix(25, 30))).not.toBeNull(); // still measured from the first point
  });

  it('can be told other thinning limits', () => {
    const r = new TraceRecorder({ minDistanceM: 5, minGapMs: 60_000 });
    r.accept(fix(0, 0));
    expect(r.accept(fix(6, 15))).not.toBeNull();
    expect(r.accept(fix(6, 74))).toBeNull();
    expect(r.accept(fix(6, 75))).not.toBeNull();
  });
});

describe('TraceRecorder: the walk id', () => {
  it('is the atMs of the first kept point and stays the same within a walk; 0 before one is kept', () => {
    const r = new TraceRecorder();
    expect(r.liveWalkId).toBe(0);
    expect(r.accept(fix(0, 0, 80))).toBeNull();
    expect(r.liveWalkId).toBe(0); // a walk that never gets a fix has no id
    const a = r.accept(fix(0, 10))!;
    const b = r.accept(fix(30, 25))!;
    expect(a.walkId).toBe(T0 + 10_000);
    expect(b.walkId).toBe(T0 + 10_000);
    expect(r.liveWalkId).toBe(T0 + 10_000);
  });

  it('starts a new id after finish(), and the first point is not "resumed"', () => {
    const r = new TraceRecorder();
    r.accept(fix(0, 0));
    r.markResumed();
    r.finish();
    expect(r.liveWalkId).toBe(0);
    const p = r.accept(fix(5, 5))!;
    expect(p.walkId).toBe(T0 + 5000);
    expect(p.resumed).toBeUndefined();
  });

  it('starts a new walk at a gap of 30 minutes or more (exactly 30 does, 29:59 does not)', () => {
    const r = new TraceRecorder();
    const first = r.accept(fix(0, 0))!;
    expect(r.accept(fix(100, 1799))!.walkId).toBe(first.walkId);
    const later = r.accept(fix(200, 1799 + 1800))!;
    expect(later.walkId).toBe(T0 + (1799 + 1800) * 1000);
    expect(later.walkId).not.toBe(first.walkId);
  });
});

describe('TraceRecorder: the resumed mark', () => {
  it('marks only the first kept point after markResumed(), and only within a walk', () => {
    const r = new TraceRecorder();
    r.accept(fix(0, 0));
    r.markResumed();
    expect(r.accept(fix(5, 5))).toBeNull(); // dropped by the thinning: the mark waits for the next KEPT point
    const resumed = r.accept(fix(40, 400))!;
    expect(resumed.resumed).toBe(true);
    expect(r.accept(fix(80, 420))!.resumed).toBeUndefined();
  });

  it('does not mark the first point of a new walk (a gap of 30 minutes or more is a new walk, not a pause)', () => {
    const r = new TraceRecorder();
    r.accept(fix(0, 0));
    r.markResumed();
    const p = r.accept(fix(500, 4000))!;
    expect(p.resumed).toBeUndefined();
    expect(p.walkId).toBe(T0 + 4_000_000);
  });
});
