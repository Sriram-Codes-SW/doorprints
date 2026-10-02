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
import { hex } from '../crypto/bytes';
import { sha256Of } from '../crypto/crypto-provider';
import { Dpx, DpxError, PHOTO } from '../crypto/dpx';
import { KeysError } from '../crypto/keys-file';
import { DRIVE_LAYOUT, DriveError } from './drive-client';
import { DEFAULT_PHOTO_CONFIG, EMPTY_PHOTO_STATE, KIND_PHOTO, PROP_PHOTO_ID } from './drive-photo-seams';
import type { PhotoConfig, PhotoRef, PhotoUploadResult } from './drive-photo-seams';
import { DrivePhotos } from './drive-photos';
import { FolderSession } from './drive-sync-seams';
import { DriveSyncBackend, syncRowOf } from './drive-sync-backend';
import { DriveFaults } from './fake-drive-faults';
import { newGuard, photoRow, SyncWorld } from './drive-sync-test-world';
import type { TestDevice } from './drive-sync-test-world';
import { ONE_OFF_TTL_MS, PhotoUploadGate } from './photo-network-policy';

/** TC-U: photos over Drive (S4b-BL-128) on the fake Drive, with the real encryption core (Kotlin `DrivePhotosTest`). */

function bytes(n: number, seed = 1): Uint8Array {
  let a = seed >>> 0;
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    out[i] = ((t ^ (t >>> 14)) >>> 0) & 0xff;
  }
  return out;
}
const count = (w: SyncWorld, op: string) => w.server.requests.filter((r) => r.op === op).length;
const sha = (w: SyncWorld, b: Uint8Array) => hex(sha256Of(w.p, b));
const eq = (x: Uint8Array | null, y: Uint8Array) => x !== null && hex(x) === hex(y);
const up = (d: TestDevice, id: string, b: Uint8Array, photos: DrivePhotos = d.photos()) => photos.upload(id, b);
const refOf = (r: PhotoUploadResult): PhotoRef => (r as { ref: PhotoRef }).ref;
async function fails(p: Promise<unknown>): Promise<unknown> {
  try {
    await p;
  } catch (e) {
    return e;
  }
  throw new Error('expected a failure');
}
const once = <T>(o: { subscribe: (h: { next: (v: T) => void; error: (e: unknown) => void; complete?: () => void }) => unknown }) =>
  new Promise<T>((res, rej) => {
    let v: T;
    o.subscribe({ next: (x) => { v = x; }, error: rej, complete: () => res(v) });
  });
const done = <T>(o: { subscribe: (h: { next: () => void; error: (e: unknown) => void; complete: () => void }) => unknown }) =>
  new Promise<void>((res, rej) => void o.subscribe({ next: () => undefined, error: rej, complete: () => res() }));

describe('DrivePhotos', () => {
  it('each photo is one encrypted file in the Photos folder with only opaque properties', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const one = bytes(5000, 1);
    const r1 = await up(a, 'photo-1', one);
    await up(a, 'photo-2', bytes(5000, 2));
    const files = w.photoFiles().filter((f) => !f.trashed);
    expect(files.length).toBe(2);
    for (const f of files) {
      expect(f.parents).toEqual([w.photosFolderId()]);
      expect(f.appProperties[DRIVE_LAYOUT.kind]).toBe(KIND_PHOTO);
      expect(f.appProperties[DRIVE_LAYOUT.state]).toBe(DRIVE_LAYOUT.stateComplete);
      expect(Object.keys(f.appProperties).sort()).toEqual([DRIVE_LAYOUT.createdAt, DRIVE_LAYOUT.device, DRIVE_LAYOUT.kind, PROP_PHOTO_ID, DRIVE_LAYOUT.state].sort());
      expect(f.name).toMatch(/^p-[0-9a-f]{24}\.dpx$/);
      const content = w.server.contentOf(f.id)!;
      expect(new TextDecoder().decode(content.slice(0, 4))).toBe('DPX1');
      expect(new TextDecoder('utf-8').decode(content)).not.toContain('photo-');
    }
    expect(refOf(r1).driveFileId).toBe(files.find((f) => f.appProperties[PROP_PHOTO_ID] === 'photo-1')!.id);
    expect(refOf(r1).sha256).toBe(sha(w, one));
    const h1 = hex(w.server.contentOf(files[0].id)!.slice(0, 300));
    const h2 = hex(w.server.contentOf(files[1].id)!.slice(0, 300));
    expect(h1).not.toBe(h2);
    expect(eq(await a.photos().download('photo-1'), one)).toBe(true);
  });

  it('a photo is not uploaded again when unchanged', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = bytes(4000);
    await up(a, 'p1', b);
    const uploads = count(w, 'UPLOAD');
    expect((await up(a, 'p1', b)).kind).toBe('Unchanged');
    expect(count(w, 'UPLOAD')).toBe(uploads);
    expect(w.photoFiles().length).toBe(1);
  });

  it('a changed photo under the same id is uploaded and this devices old file goes to the bin', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const first = refOf(await up(a, 'p1', bytes(4000, 1)));
    const second = refOf(await up(a, 'p1', bytes(4000, 2)));
    expect(first.driveFileId).not.toBe(second.driveFileId);
    expect(w.server.fileOrNull(first.driveFileId)!.trashed).toBe(true);
    expect(w.server.fileOrNull(second.driveFileId)!.trashed).toBe(false);
  });

  it('dedupe by photoId adopts another devices file instead of writing a second', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const data = bytes(6000);
    const ra = await up(a, 'p1', data);
    const rb = await up(b, 'p1', data);
    expect(rb.kind).toBe('Adopted');
    expect(refOf(rb)).toEqual(refOf(ra));
    expect(w.photoFiles().length).toBe(1);
    expect(eq(await b.photos().download('p1'), data)).toBe(true);
  });

  it('a large photo uploads in chunks and resumes after a dropped chunk', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const big = bytes(5.5 * 1024 * 1024, 7);
    w.server.faults.on('UPLOAD_CHUNK', 2, DriveFaults.dropAfter(300_000));
    const r = await a.photos(false, { ...DEFAULT_PHOTO_CONFIG, chunkSize: 1024 * 1024 }).upload('big', big);
    expect(r.kind).toBe('Uploaded');
    expect(count(w, 'UPLOAD_START')).toBe(1);
    expect(count(w, 'UPLOAD_STATUS')).toBeGreaterThanOrEqual(1);
    expect(eq(await a.photos().download('big'), big)).toBe(true);
  }, 60_000);

  it('a stopped upload resumes the same session on the next try', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const big = bytes(5.5 * 1024 * 1024, 8);
    const photos = a.photos(false, { ...DEFAULT_PHOTO_CONFIG, chunkSize: 1024 * 1024 });
    w.server.faults.always(DriveFaults.offline, 'UPLOAD_CHUNK');
    const e = await fails(photos.upload('big', big));
    expect((e as DriveError).kind).toBe('OFFLINE');
    w.server.faults.clear();
    expect((await photos.upload('big', big)).kind).toBe('Uploaded');
    expect(count(w, 'UPLOAD_START')).toBe(1);
    expect(eq(await a.photos().download('big'), big)).toBe(true);
    expect(w.photoFiles().filter((f) => !f.trashed).length).toBe(1);
  }, 60_000);

  it('a file Drive stored differently is not completed and its partial goes to the bin', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = bytes(3000);
    w.server.faults.next(DriveFaults.corruptContent, 'UPLOAD');
    const e = await fails(up(a, 'p1', b));
    expect((e as DriveError).kind).toBe('CORRUPT');
    expect(w.photoFiles().filter((f) => !f.trashed && f.appProperties[DRIVE_LAYOUT.state] === DRIVE_LAYOUT.stateComplete)).toEqual([]);
    expect((await up(a, 'p1', b)).kind).toBe('Uploaded');
    expect(eq(await a.photos().download('p1'), b)).toBe(true);
  });

  it('a wrong plaintext hash is refused', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const r = refOf(await up(a, 'p1', bytes(2000)));
    b.photoState = { ...EMPTY_PHOTO_STATE, refs: { p1: { driveFileId: r.driveFileId, sha256: '0'.repeat(64) } } };
    const photos = b.photos();
    expect(await photos.download('p1')).toBeNull();
    expect(photos.skipped.map((s) => s.reason)).toEqual(['CHECKSUM_MISMATCH']);
  });

  it('a swapped photo file is refused', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const r1 = refOf(await up(a, 'p1', bytes(2000, 1)));
    const r2 = refOf(await up(a, 'p2', bytes(2000, 2)));
    b.photoState = { ...EMPTY_PHOTO_STATE, refs: { p1: { driveFileId: r2.driveFileId, sha256: r1.sha256 } } };
    const photos = b.photos();
    expect(await photos.download('p1')).toBeNull();
    expect(photos.skipped.map((s) => s.reason)).toEqual(['CHECKSUM_MISMATCH']);
  });

  it('a photo file opened without the rows hash is refused by the envelope', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const r = refOf(await up(a, 'p1', bytes(2000)));
    const e = await fails(new Dpx(w.p).decryptBytes(a.opened, PHOTO, w.server.contentOf(r.driveFileId)!));
    expect((e as DpxError).kind).toBe('CHECKSUM_REQUIRED');
  });

  it('a tampered photo is skipped and the others still come', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const bad = refOf(await up(a, 'bad', bytes(3000, 1)));
    const good = bytes(3000, 2);
    await up(a, 'good', good);
    const content = w.server.contentOf(bad.driveFileId)!;
    content[content.length - 5] ^= 1;
    w.server.editByHand(bad.driveFileId, content);
    b.photoState = a.photoState;
    const photos = b.photos();
    expect(await photos.download('bad')).toBeNull();
    expect(photos.skipped.map((s) => s.reason)).toEqual(['BAD_ENVELOPE']);
    expect(eq(await photos.download('good'), good)).toBe(true);
    const downloads = count(w, 'DOWNLOAD');
    expect(await photos.download('bad')).toBeNull();
    expect(count(w, 'DOWNLOAD')).toBe(downloads);
    w.server.clock.advance(DEFAULT_PHOTO_CONFIG.badRetryMs + 1);
    expect(await photos.download('bad')).toBeNull();
    expect(count(w, 'DOWNLOAD')).toBeGreaterThan(downloads);
  });

  it('a truncated photo is skipped', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const r = refOf(await up(a, 'p1', bytes(200_000)));
    const content = w.server.contentOf(r.driveFileId)!;
    w.server.editByHand(r.driveFileId, content.slice(0, content.length / 2));
    b.photoState = a.photoState;
    expect(await b.photos().download('p1')).toBeNull();
  });

  it('a planted plain file is ignored everywhere', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    await up(a, 'p1', bytes(3000));
    const text = new TextEncoder().encode('not encrypted, just a photo');
    const planted = w.server.putByHand(
      { name: 'p-planted.dpx', mimeType: 'application/octet-stream', parents: [w.photosFolderId()], appProperties: { [DRIVE_LAYOUT.kind]: KIND_PHOTO, [PROP_PHOTO_ID]: 'p2', [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.stateComplete } },
      text,
    );
    b.photoState = { ...EMPTY_PHOTO_STATE, refs: { p2: { driveFileId: planted.id, sha256: sha(w, text) } } };
    const photos = b.photos();
    expect(await photos.download('p2')).toBeNull();
    expect(photos.skipped.map((s) => s.reason)).toEqual(['NOT_ENCRYPTED']);
    b.photoState = EMPTY_PHOTO_STATE;
    const r = refOf(await up(b, 'p2', bytes(3000, 9)));
    expect(r.driveFileId).not.toBe(planted.id);
    expect(w.server.fileOrNull(planted.id)!.trashed).toBe(false);
    const outside = w.server.putByHand(
      { name: 'p-outside.dpx', mimeType: 'application/octet-stream', parents: [w.rootId], appProperties: { [DRIVE_LAYOUT.kind]: KIND_PHOTO, [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.stateComplete } },
      w.server.contentOf(r.driveFileId)!,
    );
    b.photoState = { ...EMPTY_PHOTO_STATE, refs: { p2: { driveFileId: outside.id, sha256: r.sha256 } } };
    const photos2 = b.photos();
    expect(await photos2.download('p2')).toBeNull();
    expect(photos2.skipped.map((s) => s.reason)).toEqual(['NOT_OURS']);
  });

  it('a file in the bin, not complete or not a photo is not ours', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const r = refOf(await up(a, 'p1', bytes(2000)));
    const content = w.server.contentOf(r.driveFileId)!;
    const folder = w.photosFolderId();
    const planted = (props: Record<string, string>) => w.server.putByHand({ name: 'x.dpx', mimeType: 'application/octet-stream', parents: [folder], appProperties: props }, content).id;
    const partial = planted({ [DRIVE_LAYOUT.kind]: KIND_PHOTO, [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.statePartial });
    const wrongKind = planted({ [DRIVE_LAYOUT.kind]: 'sync', [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.stateComplete });
    for (const id of [partial, wrongKind]) {
      b.photoState = { ...EMPTY_PHOTO_STATE, refs: { p1: { driveFileId: id, sha256: r.sha256 } } };
      const photos = b.photos();
      expect(await photos.download('p1')).toBeNull();
      expect(photos.skipped.map((s) => s.reason)).toEqual(['NOT_OURS']);
    }
    w.server.trashByHand(r.driveFileId);
    b.photoState = { ...EMPTY_PHOTO_STATE, refs: { p1: r } };
    const photos = b.photos();
    expect(await photos.download('p1')).toBeNull();
    expect(photos.skipped.map((s) => s.reason)).toEqual(['NOT_OURS']);
  });

  it('a deleted photo file is reported and nothing else happens', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const r = refOf(await up(a, 'p1', bytes(2000)));
    w.server.deleteByHand(r.driveFileId);
    b.photoState = a.photoState;
    const photos = b.photos();
    expect(await photos.download('p1')).toBeNull();
    expect(photos.skipped.map((s) => s.reason)).toEqual(['UNREADABLE']);
    expect(count(w, 'DELETE')).toBe(0);
  });

  it('a revoked devices photo written after the revoke is skipped', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const c = await w.add('C');
    const before = refOf(await up(b, 'before', bytes(2000, 1)));
    w.server.clock.advance(10_000);
    await w.revoke(a, b);
    w.server.clock.advance(10_000);
    const after = refOf(await up(b, 'after', bytes(2000, 2), b.photos(true)));
    c.photoState = { ...EMPTY_PHOTO_STATE, refs: { before, after } };
    const photos = c.photos();
    expect(await photos.download('before')).not.toBeNull();
    expect(await photos.download('after')).toBeNull();
    expect(photos.skipped.map((s) => s.reason)).toEqual(['REVOKED_WRITER']);
  });

  it('a photo over the cap is skipped without any request', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const cfg: PhotoConfig = { ...DEFAULT_PHOTO_CONFIG, maxPlaintextBytes: 1000 };
    const photos = a.photos(false, cfg);
    const before = w.server.requests.length;
    expect(await photos.upload('p1', bytes(2000))).toEqual({ kind: 'Skipped', reason: 'TOO_LARGE' });
    expect(await photos.upload('p3', new Uint8Array(0))).toEqual({ kind: 'Skipped', reason: 'EMPTY' });
    expect(await photos.upload('../x', bytes(5))).toEqual({ kind: 'Skipped', reason: 'BAD_ID' });
    expect(w.server.requests.length).toBe(before);
    expect(photos.skipped.length).toBe(3);
  });

  it('a huge file is not downloaded', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    await up(a, 'p1', bytes(50_000));
    b.photoState = a.photoState;
    const photos = b.photos(false, { ...DEFAULT_PHOTO_CONFIG, maxPlaintextBytes: 1000 });
    expect(await photos.download('p1')).toBeNull();
    expect(photos.skipped.map((s) => s.reason)).toEqual(['TOO_LARGE']);
    expect(count(w, 'DOWNLOAD')).toBe(0);
  });

  it('without a pin nothing is asked of Drive', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const unpinned = new FolderSession(w.rootId, a.kid, a.opened, newGuard(w.p), null);
    const photos = new DrivePhotos(a.drive, w.p, unpinned, a.photoStore, () => w.now());
    const before = w.server.requests.length;
    for (const call of [() => photos.upload('p1', bytes(3)), () => photos.download('p1')]) {
      const e = await fails(call());
      expect((e as KeysError).kind).toBe('NOT_PINNED');
    }
    expect(w.server.requests.length).toBe(before);
  });

  it('a partial file is never read as a photo and the same service completes it on the next try', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    const data = bytes(4000);
    const photos = a.photos();
    w.server.faults.always(DriveFaults.server(503), 'UPDATE');
    expect(((await fails(photos.upload('p1', data))) as DriveError).kind).toBe('SERVER');
    const partial = w.photoFiles()[0];
    expect(partial.appProperties[DRIVE_LAYOUT.state]).toBe(DRIVE_LAYOUT.statePartial);
    expect(partial.name.startsWith('partial-p-')).toBe(true);
    b.photoState = { ...EMPTY_PHOTO_STATE, refs: { p1: { driveFileId: partial.id, sha256: sha(w, data) } } };
    expect(await b.photos().download('p1')).toBeNull();
    w.server.faults.clear();
    const uploads = count(w, 'UPLOAD');
    expect((await photos.upload('p1', data)).kind).toBe('Uploaded');
    expect(count(w, 'UPLOAD')).toBe(uploads);
    expect(w.photoFiles().length).toBe(1);
    expect(eq(await a.photos().download('p1'), data)).toBe(true);
  });

  it('a restarted device finds its own verified partial and completes it without sending again', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const data = bytes(4000);
    w.server.faults.always(DriveFaults.server(503), 'UPDATE');
    await fails(a.photos().upload('p1', data));
    w.server.faults.clear();
    const uploads = count(w, 'UPLOAD');
    expect((await a.photos().upload('p1', data)).kind).toBe('Adopted');
    expect(count(w, 'UPLOAD')).toBe(uploads);
    const file = w.photoFiles()[0];
    expect(file.appProperties[DRIVE_LAYOUT.state]).toBe(DRIVE_LAYOUT.stateComplete);
    expect(file.name.startsWith('p-')).toBe(true);
  });
});

describe('photos through the backend', () => {
  const apply = async (d: TestDevice, backend: DriveSyncBackend) => {
    for (const c of await once<{ id: string; deleted: boolean; houseId: string }[]>(backend.photoChangesSince(0))) {
      if (c.deleted) d.local.rows.delete(`photos\u0000${c.id}`);
      else d.local.put(syncRowOf('photos', c, d.id), false);
    }
  };

  it('two devices converge with a photo and a tombstone', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    a.edit('h1', 'house');
    a.local.put(photoRow('p1', 'h1', a.now(), a.id), true);
    const data = bytes(8000);
    const ba = a.backend();
    await done(ba.commitPushes());
    w.server.clock.advance(1000);
    const bb = b.backend();
    await done(bb.commitPushes());
    expect(await once<unknown[]>(bb.photoChangesSince(0))).toEqual([]);
    await once(ba.uploadPhoto('h1', new Blob([data as BlobPart]), 'p1'));
    await done(ba.commitPushes());
    w.server.clock.advance(1000);
    const bb2 = b.backend();
    await done(bb2.commitPushes());
    const changes = await once<{ id: string }[]>(bb2.photoChangesSince(0));
    expect(changes.map((c) => c.id)).toEqual(['p1']);
    const blob = await once<Blob | null>(bb2.downloadPhotoIfAvailable('p1'));
    expect(eq(new Uint8Array(await blob!.arrayBuffer()), data)).toBe(true);
    expect(bb2.photoSkips()).toEqual([]);
    await apply(b, bb2);
    expect(b.photoState.refs['p1']).toEqual(a.photoState.refs['p1']);
    // A deletes the photo: a tombstone stays in A's file for ever, even after its local row is gone.
    const bd = a.backend();
    await once(bd.deletePhoto('p1'));
    a.local.rows.delete('photos\u0000p1');
    w.server.clock.advance(1000);
    await done(bd.commitPushes());
    for (let i = 0; i < 2; i++) {
      w.server.clock.advance(1000);
      await done(a.backend().commitPushes());
    }
    w.server.clock.advance(1000);
    const bb3 = b.backend();
    await done(bb3.commitPushes());
    const gone = await once<{ id: string; deleted: boolean }[]>(bb3.photoChangesSince(0));
    expect(gone.map((c) => c.id)).toEqual(['p1']);
    expect(gone[0].deleted).toBe(true);
    expect(count(w, 'DELETE')).toBe(0);
  });

  it('a tampered photo does not stop the backend and is reported', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    const b = await w.add('B');
    a.local.put(photoRow('bad', 'h1', a.now(), a.id), true);
    a.local.put(photoRow('good', 'h1', a.now(), a.id), true);
    const good = bytes(3000, 2);
    const ba = a.backend();
    await once(ba.uploadPhoto('h1', new Blob([bytes(3000, 1) as BlobPart]), 'bad'));
    await once(ba.uploadPhoto('h1', new Blob([good as BlobPart]), 'good'));
    await done(ba.commitPushes());
    const badFile = a.photoState.refs['bad'].driveFileId;
    const c = w.server.contentOf(badFile)!;
    c[c.length - 3] ^= 0x55;
    w.server.editByHand(badFile, c);
    w.server.clock.advance(1000);
    const bb = b.backend();
    await done(bb.commitPushes());
    expect((await once<{ id: string }[]>(bb.photoChangesSince(0))).map((x) => x.id).sort()).toEqual(['bad', 'good']);
    expect(await once<Blob | null>(bb.downloadPhotoIfAvailable('bad'))).toBeNull();
    const g = await once<Blob | null>(bb.downloadPhotoIfAvailable('good'));
    expect(eq(new Uint8Array(await g!.arrayBuffer()), good)).toBe(true);
    expect(bb.photoSkips().map((s) => s.reason)).toEqual(['BAD_ENVELOPE']);
    const e = await fails(once(bb.downloadPhoto('bad')));
    expect((e as DriveError).kind).toBe('NOT_FOUND');
  });

  it('the upload stays off the network when the policy holds it back', async () => {
    const w = new SyncWorld();
    const a = await w.add('A');
    let t = w.now();
    const gate = new PhotoUploadGate(() => ({ online: true, metering: 'METERED', roaming: false, dataSaver: false }), () => ({ uploadOnMobileData: false }), () => t);
    const before = w.server.requests.length;
    const step = async () => {
      if (!gate.photosAllowed()) return false;
      await up(a, 'p1', bytes(2000));
      return true;
    };
    expect(await step()).toBe(false);
    expect(w.server.requests.length).toBe(before);
    gate.grantOneOff();
    expect(await step()).toBe(true);
    t += ONE_OFF_TTL_MS;
    expect(gate.photosAllowed()).toBe(false);
  });
});
