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
import { KeysError } from '../crypto/keys-file';
import { DRIVE_LAYOUT, DriveError } from './drive-client';
import { DriveSyncEngine } from './drive-sync-engine';
import { DriveSyncBackend, syncRowOf } from './drive-sync-backend';
import { DriveSyncNotYet, FolderSession, KIND_SYNC, reportOf, skippedReasons } from './drive-sync-seams';
import type { SyncPassResult } from './drive-sync-seams';
import { DriveFaults } from './fake-drive-faults';
import { houseRow, newGuard, SyncWorld } from './drive-sync-test-world';
import type { SyncRow } from './sync-file';
import type { TestDevice } from './drive-sync-test-world';

/** TC-U: the Drive sync engine over the fake Drive and the real encryption core (S4b-BL-118; Kotlin `DriveSyncEngineTest`). */

const perms = <T>(items: T[]): T[][] => (items.length <= 1 ? [items] : items.flatMap((x, i) => perms([...items.slice(0, i), ...items.slice(i + 1)]).map((r) => [x, ...r])));
const done = (r: SyncPassResult) => {
  expect(r.kind).toBe('Done');
  return reportOf(r)!;
};

async function settle(w: SyncWorld, rounds = 3): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    for (const d of w.devices.values()) {
      await d.sync();
      w.server.clock.advance(1000);
    }
  }
}

async function fails(p: Promise<unknown>): Promise<unknown> {
  try {
    await p;
  } catch (e) {
    return e;
  }
  throw new Error('expected a failure');
}

describe('DriveSyncEngine', () => {
  it('two devices converge in every order', async () => {
    for (const order of perms(['A', 'B', 'A', 'B'].slice(0, 2)).flatMap((o) => perms(['A', 'B']).map((q) => [...o, ...q]))) {
      const w = new SyncWorld();
      const a = await w.add('A');
      const b = await w.add('B');
      a.edit('h1', 'one'); a.edit('h2', 'two');
      w.server.clock.advance(10);
      b.edit('h3', 'three'); b.edit('h2', 'two-b');
      for (const n of order) {
        await w.devices.get(n)!.sync();
        w.server.clock.advance(1000);
      }
      await settle(w);
      expect(b.local.labels(), `order ${order}`).toEqual(a.local.labels());
      expect(a.local.labels()).toEqual({ h1: 'one', h2: 'two-b', h3: 'three' });
    }
  });

  it('three devices converge in every order with conflicts and deletes', async () => {
    for (const order of perms(['A', 'B', 'C'])) {
      const w = new SyncWorld();
      const a = await w.add('A'); const b = await w.add('B'); const c = await w.add('C');
      a.edit('h1', 'a1'); await a.sync(); w.server.clock.advance(1000);
      await b.sync(); await c.sync(); w.server.clock.advance(1000);
      b.edit('h1', 'b1'); w.server.clock.advance(1000);
      c.delete('h1'); w.server.clock.advance(1000);
      a.edit('h2', 'a2'); w.server.clock.advance(1000);
      c.edit('h1', 'c-again'); w.server.clock.advance(1000);
      for (const n of [...order, ...order]) {
        await w.devices.get(n)!.sync();
        w.server.clock.advance(1000);
      }
      await settle(w);
      expect(b.local.labels(), `order ${order}`).toEqual(a.local.labels());
      expect(c.local.labels()).toEqual(a.local.labels());
      expect(a.local.label('h1')).toBe('c-again');
    }
  });

  it('a second pass with nothing new writes nothing', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    a.edit('h1', 'one');
    expect(done(await a.sync()).wrote).toBe(true);
    const files = w.syncFiles().length;
    w.server.clock.advance(5000);
    expect(done(await a.sync()).wrote).toBe(false);
    expect(w.syncFiles().length).toBe(files);
  });

  it('the file is dpx and carries only opaque properties', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    a.edit('h1', 'secret label');
    await a.sync();
    const f = w.syncFiles().filter((x) => !x.trashed)[0];
    expect(f.appProperties[DRIVE_LAYOUT.kind]).toBe(KIND_SYNC);
    expect(f.appProperties[DRIVE_LAYOUT.state]).toBe(DRIVE_LAYOUT.stateComplete);
    expect(f.appProperties[DRIVE_LAYOUT.device]).toBe(a.id);
    expect(f.parents).toEqual([w.syncFolderId()]);
    const bytes = w.server.contentOf(f.id)!;
    expect(new TextDecoder().decode(bytes.slice(0, 4))).toBe('DPX1');
    expect(new TextDecoder('latin1').decode(bytes)).not.toContain('secret label');
  });

  it('an interrupted upload leaves a partial file nobody reads, and the next pass heals it', async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B');
    a.edit('h1', 'one');
    w.server.faults.always(DriveFaults.server(503), 'UPDATE');
    expect(((await fails(a.sync())) as DriveError).kind).toBe('SERVER');
    expect(a.local.dirty.size).toBeGreaterThan(0);
    const partial = w.syncFiles()[0];
    expect(partial.appProperties[DRIVE_LAYOUT.state]).toBe(DRIVE_LAYOUT.statePartial);
    w.server.faults.clear();
    expect(done(await b.sync()).take.length).toBe(0);
    expect(b.local.labels()).toEqual({});
    await a.sync();
    expect(w.server.fileOrNull(partial.id)!.trashed).toBe(true);
    expect(a.state.confirmedSeq).toBeGreaterThan(1);
    await b.sync();
    expect(b.local.label('h1')).toBe('one');
  });

  it('a planted partial file is never read', async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B');
    a.edit('h1', 'one'); await a.sync();
    const real = w.syncFiles()[0];
    w.server.putByHand({ name: 'partial-sync-9.dpx', mimeType: 'application/octet-stream', parents: [w.syncFolderId()],
      appProperties: { ...real.appProperties, [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.statePartial, [DRIVE_LAYOUT.device]: a.id } }, w.server.contentOf(real.id));
    const r = done(await b.sync());
    expect(b.local.label('h1')).toBe('one');
    expect(r.skipped).toEqual([]);
  });

  it('rows are marked clean only after the read-back', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    a.edit('h1', 'one');
    w.server.faults.next(DriveFaults.interleave((s) => s.editByHand(s.allFiles().find((f) => f.name.startsWith('sync-'))!.id, new Uint8Array(300).fill(7))), 'DOWNLOAD');
    expect(((await fails(a.sync())) as DriveError).kind).toBe('CORRUPT');
    expect([...a.local.dirty]).toEqual(['houses\u0000h1']);
    expect(a.state.confirmedSeq).toBe(0);
    expect(a.state.lastFileId).toBeNull();
  });

  it('a tampered, a planted and a foreign file are skipped and reported', async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B'); const c = await w.add('C');
    a.edit('h1', 'one'); await a.sync();
    const good = w.server.contentOf(w.syncFiles()[0].id)!;
    const folder = w.syncFolderId();
    const plant = (device: string, bytes: Uint8Array) => w.server.putByHand({ name: 'x.dpx', mimeType: 'application/octet-stream', parents: [folder],
      appProperties: { [DRIVE_LAYOUT.kind]: KIND_SYNC, [DRIVE_LAYOUT.device]: device, [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.stateComplete, seq: '99' } }, bytes);
    const flipped = good.slice();
    flipped[flipped.length - 3] ^= 1;
    plant(a.id, flipped);
    plant(c.id, new TextEncoder().encode('{"format":"doorprints-sync/1"}'));
    plant(c.id, good);
    plant('f'.repeat(32), good);
    const r = done(await b.sync());
    const reasons = r.skipped.map((s) => s.reason);
    expect(reasons).toEqual(expect.arrayContaining(['BAD_ENVELOPE', 'NOT_ENCRYPTED', 'WRONG_DEVICE', 'UNLISTED_DEVICE']));
    expect(b.local.label('h1')).toBe('one');
    expect(w.syncFiles().filter((f) => !f.trashed && f.name === 'x.dpx').length).toBe(4);
  });

  it("a revoked device's file after the revoke is skipped, its earlier file counts", async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B'); const c = await w.add('C');
    c.edit('early', 'before'); await c.sync();
    w.server.clock.advance(60_000);
    await a.sync();
    expect(a.local.label('early')).toBe('before');
    w.server.clock.advance(1000);
    await b.sync();
    await w.revoke(a, c);
    w.server.clock.advance(60_000);
    c.edit('late', 'after');
    await c.sync({ stale: true });
    w.server.clock.advance(60_000);
    const ra = done(await a.sync());
    expect(ra.skipped.some((s) => s.reason === 'REVOKED_WRITER')).toBe(true);
    expect(a.local.label('late')).toBeNull();
    const rb = done(await b.sync());
    expect(b.local.label('late')).toBeNull();
    expect(rb.skipped.some((s) => s.reason === 'REVOKED_WRITER')).toBe(true);
    expect(await fails(c.sync())).toBeInstanceOf(KeysError);
  });

  it('a rolled-back file is ignored', async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B');
    a.edit('h1', 'v1'); await a.sync();
    const oldBytes = w.server.contentOf(w.syncFiles()[0].id)!;
    w.server.clock.advance(1000);
    a.edit('h1', 'v2'); await a.sync();
    await b.sync();
    w.server.clock.advance(1000);
    await b.sync();
    expect(b.local.label('h1')).toBe('v2');
    w.server.trashByHand(a.state.lastFileId!);
    w.server.putByHand({ name: 'again.dpx', mimeType: 'application/octet-stream', parents: [w.syncFolderId()],
      appProperties: { [DRIVE_LAYOUT.kind]: KIND_SYNC, [DRIVE_LAYOUT.device]: a.id, [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.stateComplete, seq: '50' } }, oldBytes);
    const r = done(await b.sync());
    expect(r.skipped.some((s) => s.reason === 'ROLLED_BACK')).toBe(true);
    expect(b.local.label('h1')).toBe('v2');
  });

  it('offline backs off, then recovers', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    a.edit('h1', 'one');
    w.server.faults.always(DriveFaults.offline);
    expect(((await fails(a.sync())) as DriveError).kind).toBe('OFFLINE');
    expect(a.state.failures).toBe(1);
    expect(a.state.notBefore).toBeGreaterThan(w.now());
    expect(a.local.dirty.size).toBeGreaterThan(0);
    w.server.faults.clear();
    expect((await a.sync({ force: false })).kind).toBe('Waiting');
    w.server.clock.advance(60_000);
    expect((await a.sync({ force: false })).kind).toBe('Done');
    expect(a.state.failures).toBe(0);
    expect(a.local.dirty.size).toBe(0);
  });

  it('the shrink guard waits for the user, then applies', async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B');
    for (let i = 1; i <= 12; i++) a.edit(`h${i}`, `house ${i}`);
    await a.sync(); await b.sync();
    w.server.clock.advance(1000);
    for (let i = 1; i <= 11; i++) a.delete(`h${i}`);
    await a.sync();
    const first = await b.sync();
    expect(first.kind).toBe('NeedsConfirmation');
    if (first.kind === 'NeedsConfirmation') {
      expect(first.housesToDelete).toBe(11);
      expect(first.liveHouses).toBe(12);
    }
    expect(b.local.label('h1')).toBe('house 1');
    w.server.clock.advance(1000);
    expect((await b.sync({ confirm: true })).kind).toBe('Done');
    expect(b.local.label('h1')).toBe('<deleted>');
    expect(b.local.label('h12')).toBe('house 12');
  });

  it('a far-future stamp is held until the clock catches up', async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B');
    a.skewMs = 48 * 3_600_000;
    a.edit('h1', 'from the future'); a.edit('h2', 'from the future too');
    a.local.put(houseRow('ok', 'fine', w.now(), a.id), true);
    await a.sync();
    const r = done(await b.sync());
    expect(r.held).toBe(2);
    expect(b.local.labels()).toEqual({ ok: 'fine' });
    w.server.clock.advance(25 * 3_600_000);
    expect(done(await b.sync()).held).toBe(0);
    expect(b.local.label('h1')).toBe('from the future');
  });

  it('isBehind uses the hint but the authenticated seq decides', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    expect(await a.engine().isBehind()).toBe(false);
    a.edit('h1', 'one'); await a.sync();
    const downloads = w.server.requests.filter((r) => r.op === 'DOWNLOAD').length;
    expect(await a.engine().isBehind()).toBe(false);
    expect(w.server.requests.filter((r) => r.op === 'DOWNLOAD').length).toBe(downloads);
    w.server.renameByHand(a.state.lastFileId!, 'renamed.dpx');
    expect(await a.engine().isBehind()).toBe(false);
    w.server.deleteByHand(a.state.lastFileId!);
    expect(await a.engine().isBehind()).toBe(true);
    await a.sync();
    expect(await a.engine().isBehind()).toBe(false);
  });

  it('a device without a pin opens nothing', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const engine = new DriveSyncEngine(a.drive, w.p, new FolderSession(w.rootId, a.kid, a.opened, newGuard(w.p)), a.store, a.local, () => a.now());
    const before = w.server.requests.length;
    const e = await fails(engine.run());
    expect((e as KeysError).kind).toBe('NOT_PINNED');
    expect(w.server.requests.length).toBe(before);
  });

  it('a keys error ends the pass without touching Drive', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const session = new FolderSession(w.rootId, a.kid, a.opened, a.guard, async () => {
      throw new KeysError('REVOKED', 'x');
    });
    const engine = new DriveSyncEngine(a.drive, w.p, session, a.store, a.local, () => a.now());
    const before = w.server.requests.length;
    expect(((await fails(engine.run())) as KeysError).kind).toBe('REVOKED');
    expect(w.server.requests.length).toBe(before);
    expect(w.server.allFiles().filter((f) => f.trashed).length).toBe(0);
  });

  it('a paused device does not sync', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const engine = new DriveSyncEngine(a.drive, w.p, a.session(), a.store, a.local, () => a.now(), async () => true);
    const before = w.server.requests.length;
    expect((await engine.run()).kind).toBe('Paused');
    expect(w.server.requests.length).toBe(before);
  });

  it("a deleted photo's tombstone is kept for ever", async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B');
    a.local.put(syncRowOf('photos', { id: 'p1', houseId: 'h1', deleted: false, updatedAt: '2026-09-01T10:00:00Z' }, a.id), false);
    const backend = new DriveSyncBackend(a.engine(), a.local, () => a.now(), a.id);
    await new Promise<void>((res, rej) => backend.deletePhoto('p1').subscribe({ complete: res, error: rej }));
    a.local.rows.delete('photos\u0000p1'); // what the loop does after the commit
    await new Promise<void>((res, rej) => backend.commitPushes().subscribe({ complete: res, error: rej }));
    w.server.clock.advance(1000);
    a.edit('h1', 'later'); await a.sync();
    await b.sync();
    const tomb = b.local.rows.get('photos\u0000p1');
    expect(tomb).toBeTruthy();
    expect(tomb!.stamp.deleted).toBe(true);
  });

  it('the backend hands out what was taken, and photos are not yet', async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B');
    a.edit('h1', 'one'); await a.sync();
    const backend = new DriveSyncBackend(b.engine(), b.local, () => b.now(), b.id);
    expect(backend.stagesPushes).toBe(true);
    await new Promise<void>((res, rej) => backend.commitPushes().subscribe({ complete: res, error: rej }));
    const houses = await new Promise<{ id: string; syncVersion: number }[]>((res, rej) => backend.housesSince(0).subscribe({ next: res, error: rej }));
    expect(houses.map((h) => h.id)).toEqual(['h1']);
    expect(houses[0].syncVersion).toBe(1);
    const again = await new Promise<unknown[]>((res, rej) => backend.housesSince(1).subscribe({ next: res, error: rej }));
    expect(again).toEqual([]);
    // Without a photo service the photo calls keep their old answer (S4b-BL-128 wires it).
    await expect(new Promise((res, rej) => backend.downloadPhoto('p').subscribe({ next: res, error: rej }))).rejects.toBeInstanceOf(DriveSyncNotYet);
    expect(() => backend.uploadPhoto('h', new Blob(['x']), 'p')).toThrow(DriveSyncNotYet);
  });

  it('if applyRemote throws, the next pass retries the rows and cursor is not advanced', async () => {
    const w = new SyncWorld();
    const a = await w.add('A'); const b = await w.add('B');
    a.edit('h1', 'one'); await a.sync();
    w.server.clock.advance(1000);

    // Create a custom LocalRows that can throw on applyRemote
    class ThrowingLocal extends (b.local.constructor as any) {
      applyCount = 0;
      throwOnce = false;
      async applyRemote(rows: readonly SyncRow[]): Promise<void> {
        this.applyCount++;
        if (this.throwOnce) {
          this.throwOnce = false;
          throw new Error('apply failed');
        }
        for (const r of rows) this.put(r, false);
      }
    }
    const throwingLocal = Object.assign(new ThrowingLocal(), b.local);

    // First sync attempt with throwing applyRemote
    const engine1 = new DriveSyncEngine(b.drive, b.p, b.session(), b.store, throwingLocal, () => b.now());
    throwingLocal.throwOnce = true;
    const result1 = await fails(engine1.run());
    expect(result1).toBeInstanceOf(Error);
    expect((result1 as Error).message).toBe('apply failed');

    // Verify peer cursor was NOT advanced: peer entry not saved when applyRemote failed
    const peerAAfterFail = b.state.peers[a.id];
    expect(peerAAfterFail).toBeUndefined();

    // Verify rows were NOT applied after failure (rows still in throwingLocal, but through put it would be marked dirty)
    expect(throwingLocal.rows.has('houses\u0000h1')).toBe(false);

    // Second sync attempt should apply the rows
    const engine2 = new DriveSyncEngine(b.drive, b.p, b.session(), b.store, throwingLocal, () => b.now());
    const result2 = await engine2.run();
    expect(result2.kind).toBe('Done');
    expect(throwingLocal.applyCount).toBe(2); // Called twice: once threw, once succeeded

    // Verify rows were applied on second attempt
    expect(throwingLocal.label('h1')).toBe('one');
  });
});

export type { TestDevice };
void skippedReasons;
