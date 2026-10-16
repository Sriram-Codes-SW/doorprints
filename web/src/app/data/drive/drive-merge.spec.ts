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
import { driveMerge, isHeld, nextStamp, takesIncoming } from './drive-merge';
import type { SyncKind, SyncStamp } from './sync-file';
import { syncFile } from './sync-file';
import { digestOf, SimDevice, simulate, XorShift32 } from './sync-sim';
import { serverMerge } from '../sync-rules';

/**
 * The Drive merge rule (S4b-BL-130) as properties: whatever the order of the files, merging one twice, or merging
 * through a third device, three devices end with the same rows; plus the races, skew, hold, shrink and rollback rules.
 * The shared cases are in `sync-vectors.spec.ts`. Twin of Kotlin's `DriveMergeTest`.
 */

const a = 'device-a01';
const b = 'device-b02';
const c = 'device-c03';
const now = 1_790_000_000_000;
const DAY = 86_400_000;
const keys = ['houses/h0', 'houses/h1', 'houses/h2', 'visits/v0', 'records/note/r0', 'photos/p0'];

/** Three devices that each edited and deleted at random, never synced, and their files. */
function threeFiles(seed: number): [string, string][] {
  const rng = new XorShift32(seed);
  const devices = [new SimDevice(a), new SimDevice(b, -7_000), new SimDevice(c, 4_000)];
  let clock = now - 100_000;
  for (let i = 0; i < 30; i++) {
    clock += rng.next(2_000);
    const d = devices[rng.next(3)];
    d.edit(keys[rng.next(keys.length)], clock, rng.next(4) === 0);
  }
  return devices.map((d) => [d.snapshot(clock), d.id]);
}

function permutations(n: number): number[][] {
  if (n === 0) return [[]];
  return permutations(n - 1).flatMap((p) => Array.from({ length: p.length + 1 }, (_, i) => [...p.slice(0, i), n - 1, ...p.slice(i)]));
}

describe('the Drive merge rule', () => {
  it('is a total order, antisymmetric', () => {
    const rng = new XorShift32(7);
    const stamps: SyncStamp[] = Array.from({ length: 200 }, () => ({ updatedAt: now + rng.next(3), by: [a, b, c, ''][rng.next(4)], deleted: rng.next(2) === 0 }));
    for (const x of stamps) {
      for (const y of stamps) {
        const xy = takesIncoming(x, y);
        const yx = takesIncoming(y, x);
        if (x.updatedAt === y.updatedAt && x.by === y.by && x.deleted === y.deleted) expect(xy || yx).toBe(false);
        else expect(xy !== yx).toBe(true);
      }
    }
  });

  it('makes three devices converge in every order', () => {
    for (let seed = 1; seed <= 40; seed++) {
      const files = threeFiles(seed);
      const results = permutations(3).map((order) => {
        const reader = new SimDevice('device-r00');
        for (const i of order) reader.merge(files[i][0], files[i][1], now);
        return reader.digest();
      });
      expect(new Set(results).size, `seed ${seed}`).toBe(1);
    }
  });

  it('is the maximum per key', () => {
    for (let seed = 1; seed <= 20; seed++) {
      const files = threeFiles(seed);
      const reader = new SimDevice('device-r00');
      for (const [text, from] of files) reader.merge(text, from, now);
      const expected = new Map<string, SyncStamp>();
      const peer = new SimDevice('device-p00');
      for (const [text, from] of files) {
        peer.merge(text, from, now);
        for (const [path, row] of peer.rows) {
          const kept = expected.get(path);
          if (!kept || takesIncoming(kept, row.stamp)) expected.set(path, row.stamp);
        }
      }
      expect(reader.digest(), `seed ${seed}`).toBe(digestOf(expected));
    }
  });

  it('changes nothing when a file is merged twice', () => {
    for (let seed = 1; seed <= 20; seed++) {
      const [text, from] = threeFiles(seed)[1];
      const reader = new SimDevice('device-r00');
      for (const [t, f] of threeFiles(seed + 100)) reader.merge(t, f, now);
      reader.merge(text, from, now);
      const once = reader.digest();
      const again = reader.merge(text, from, now);
      expect(again.take.length, `seed ${seed}`).toBe(0);
      expect(reader.digest()).toBe(once);
    }
  });

  it('is the same through a third device as directly', () => {
    for (let seed = 1; seed <= 20; seed++) {
      const [f1, f2, f3] = threeFiles(seed);
      const x = new SimDevice('device-x00');
      x.merge(f1[0], f1[1], now);
      x.merge(f2[0], f2[1], now);
      const relayed = x.snapshot(now);
      const y = new SimDevice('device-y00');
      y.merge(f3[0], f3[1], now);
      y.merge(relayed, x.id, now);
      const direct = new SimDevice('device-z00');
      for (const f of [f2, f3, f1]) direct.merge(f[0], f[1], now);
      expect(y.digest(), `seed ${seed}`).toBe(direct.digest());
    }
  });

  it('converges in the shared random runs', () => {
    for (let seed = 1; seed <= 60; seed++) {
      const devices = simulate(seed, now, [a, b, c], [0, -7_000, 4_000], 80, keys);
      expect(new Set(devices.map((d) => d.digest())).size, `seed ${seed}`).toBe(1);
    }
  });

  it('a later stamp on the same key wins (last-write-wins)', () => {
    const earlier: SyncStamp = { updatedAt: now, by: a, deleted: false };
    const later: SyncStamp = { updatedAt: now + 1, by: b, deleted: false };
    expect(takesIncoming(earlier, later)).toBe(true);
    expect(takesIncoming(later, earlier)).toBe(false);
  });

  it('settles a delete and an edit race', () => {
    const deleted: SyncStamp = { updatedAt: now, by: a, deleted: true };
    expect(takesIncoming({ updatedAt: now - 1, by: b, deleted: false }, deleted)).toBe(true);
    expect(takesIncoming({ updatedAt: now + 1, by: b, deleted: false }, deleted)).toBe(false);
    expect(takesIncoming(deleted, { updatedAt: now + 1, by: c, deleted: false })).toBe(true);
    expect(takesIncoming({ updatedAt: now, by: a, deleted: false }, deleted)).toBe(true);
    expect(takesIncoming({ updatedAt: now, by: b, deleted: false }, deleted)).toBe(false);
  });

  it('moves a row forward even on a clock that is behind', () => {
    const fast = new SimDevice(a);
    const slow = new SimDevice(b, -3_600_000);
    fast.edit('houses/h1', now, false);
    slow.merge(fast.snapshot(now), a, now);
    slow.edit('houses/h1', now + 1_000, false, 'later, on a slow clock');
    expect(slow.stamp('houses/h1')!.updatedAt).toBe(now + 1);
    fast.merge(slow.snapshot(now + 1_000), b, now + 1_000);
    expect(fast.stamp('houses/h1')!.by).toBe(b);
    expect(nextStamp(now, null)).toBe(now);
    expect(nextStamp(now, now + 5)).toBe(now + 6);
  });

  it('holds a far-future stamp and applies a near-future one', () => {
    const liar = new SimDevice(a, 30 * DAY);
    liar.edit('houses/h1', now, true);
    liar.edit('houses/h2', now - 30 * DAY + 23 * 3_600_000, false);
    const honest = new SimDevice(b);
    honest.edit('houses/h1', now, false);
    const plan = honest.merge(liar.snapshot(now), a, now);
    expect(plan.held.map((r) => r.key)).toEqual(['h1']);
    expect(honest.stamp('houses/h1')!.by).toBe(b);
    expect(plan.take.map((r) => r.key)).toEqual(['h2']);
    honest.edit('houses/h2', now, false);
    expect(honest.stamp('houses/h2')!.updatedAt).toBeGreaterThan(now + 23 * 3_600_000);
    const later = honest.merge(liar.snapshot(now), a, now + 30 * DAY);
    expect(later.take.map((r) => r.key)).toEqual(['h1']);
    expect(isHeld({ updatedAt: now + DAY + 1, by: a, deleted: false }, now)).toBe(true);
    expect(isHeld({ updatedAt: now + DAY, by: a, deleted: false }, now)).toBe(false);
  });

  it('holds a mass delete until confirmed', () => {
    const here = new SimDevice(b);
    const wiper = new SimDevice(a);
    for (let i = 0; i < 40; i++) here.edit(`houses/h${i}`, now - 10_000, false);
    wiper.merge(here.snapshot(now), b, now);
    for (let i = 0; i < 40; i++) wiper.edit(`houses/h${i}`, now, true);
    wiper.edit('records/note/r1', now, false);
    const text = wiper.snapshot(now);
    const plan = here.merge(text, a, now);
    expect(plan.deferred.length).toBe(40);
    expect(plan.take.map((r) => r.key)).toEqual(['note/r1']);
    expect(here.liveHouses()).toBe(40);
    const confirmed = here.merge(text, a, now, true);
    expect(confirmed.take.length).toBe(40);
    expect(here.liveHouses()).toBe(0);
  });

  it('treats a rolled-back file as stale, and it would change nothing newer anyway', () => {
    const writer = new SimDevice(a);
    writer.edit('houses/h1', now - 5_000, false);
    const old = writer.snapshot(now - 5_000);
    writer.edit('houses/h1', now, true);
    const reader = new SimDevice(b);
    reader.merge(writer.snapshot(now), a, now);
    const plan = reader.merge(old, a, now);
    expect(plan.stale).toBe(true);
    expect(plan.take.length).toBe(0);
    const fresh = new SimDevice(c);
    fresh.merge(reader.snapshot(now), b, now);
    expect(fresh.merge(old, a, now).take.length).toBe(0);
    expect(fresh.stamp('houses/h1')!.deleted).toBe(true);
  });

  it("is the loop's rule: dirty plays no part and ties go to the writer", () => {
    const row = (updatedAt: number, dirty: boolean, extra: { deleted?: boolean; by?: string } = {}) => ({ updatedAt: new Date(updatedAt).toISOString(), dirty, ...extra });
    const t = (ms: number) => ({ updatedAt: new Date(ms).toISOString() });
    expect(driveMerge(row(now, false), t(now - 1))).toBe(true);
    expect(serverMerge(row(now, false), t(now - 1))).toBe(false);
    expect(driveMerge(row(now, true), t(now + 1))).toBe(false);
    expect(driveMerge(null, t(now))).toBe(false);
    expect(driveMerge(row(now, false, { by: a }), { ...t(now), by: b })).toBe(false);
    expect(driveMerge(row(now, false, { by: b }), { ...t(now), by: a })).toBe(true);
    expect(driveMerge(row(now, false), { ...t(now), by: a })).toBe(false);
    expect(driveMerge(row(now, false, { by: a }), { ...t(now), by: a, deleted: true })).toBe(false);
    expect(driveMerge(row(now, false, { by: a }), { ...t(now), by: a })).toBe(true);
  });

  it('builds a file from rows in any order', () => {
    const file = syncFile(a, 1, now, {});
    const kinds: SyncKind[] = ['houses', 'visits', 'records', 'photos'];
    for (const k of kinds) expect(file.rows[k]).toEqual([]);
  });
});
