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

import { beforeEach, describe, expect, it } from 'vitest';
import type { HouseDto } from '../core/models';
import { LocalStore } from './local-store.service';
import { MAX_PHOTOS_PER_HOUSE } from './photo-store';
import type { PhotoRecord } from './records';

const T1 = Date.parse('2026-09-01T00:00:00.000Z');
const T2 = Date.parse('2026-09-02T00:00:00.000Z');
const T3 = Date.parse('2026-09-03T00:00:00.000Z');

const house = (id: string): HouseDto => ({ id, label: `House ${id}`, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0 });
const jpeg = (bytes = 1) => new Blob([new Uint8Array(bytes)], { type: 'image/jpeg' });

/** The photo rows of the browser's store: what is kept, what a delete leaves, and what a full resend marks. */
describe('PhotoStore (LocalStore.photos)', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
    await store.saveHouse(house('h1'), T1);
    await store.saveHouse(house('h2'), T1);
  });

  const markUploaded = async (id: string) => store.photos.put({ ...(await store.photos.get(id))!, uploaded: true });

  it('keeps the size and type of the bytes, and says JPEG for a blob with no type', async () => {
    await store.photos.add('h1', jpeg(3), 'typed', T1);
    await store.photos.add('h1', new Blob([new Uint8Array(5)]), 'untyped', T2);
    expect(await store.photos.get('typed')).toMatchObject({ houseId: 'h1', contentType: 'image/jpeg', sizeBytes: 3, deleted: false, uploaded: false, syncVersion: 0 });
    expect(await store.photos.get('untyped')).toMatchObject({ contentType: 'image/jpeg', sizeBytes: 5 });
  });

  it('stamps createdAt and updatedAt with the time given, and writes no meta when none is given', async () => {
    await store.photos.add('h1', jpeg(), 'p1', T2);
    const row = (await store.photos.get('p1'))!;
    expect(row.createdAt).toBe('2026-09-02T00:00:00.000Z');
    expect(row.updatedAt).toBe('2026-09-02T00:00:00.000Z');
    for (const key of ['roomId', 'tags', 'caption', 'metaUpdatedAt', 'metaDirty']) expect(row).not.toHaveProperty(key);
  });

  it('writes no meta for a meta that holds nothing', async () => {
    await store.photos.add('h1', jpeg(), 'p1', T2, { roomId: null, tags: [], caption: '   ' });
    expect(await store.photos.get('p1')).not.toHaveProperty('metaDirty');
  });

  it('counts only the live photos of that house against the limit', async () => {
    for (let i = 0; i < MAX_PHOTOS_PER_HOUSE; i++) await store.photos.add('h1', jpeg(), `p${i}`, T1);
    // Another house has its own room.
    expect((await store.photos.add('h2', jpeg(), 'other', T1)).ok).toBe(true);
    expect(await store.photos.add('h1', jpeg(), 'over', T1)).toEqual({ ok: false, reason: 'limit' });
    expect(await store.photos.get('over')).toBeUndefined();
    // A tombstone (a photo the server has, deleted here) leaves room.
    await markUploaded('p0');
    await store.photos.delete('p0', T2);
    expect(await store.photos.add('h1', jpeg(), 'again', T2)).toEqual({ ok: true, id: 'again' });
  });

  it('lists a house’s live photos oldest first, ties by id, and every photo with tombstones in allPhotos', async () => {
    await store.photos.add('h1', jpeg(), 'b', T2);
    await store.photos.add('h1', jpeg(), 'a', T2);
    await store.photos.add('h1', jpeg(), 'first', T1);
    await store.photos.add('h2', jpeg(), 'h2-photo', T1);
    await markUploaded('a');
    await store.photos.delete('a', T3);
    expect((await store.photos.ofHouse('h1')).map((p) => p.id)).toEqual(['first', 'b']);
    expect((await store.photos.all()).map((p) => p.id)).toEqual(['first', 'h2-photo', 'a', 'b']);
  });

  it('reads photo rows by id in one go, tombstones included, an unknown id adding nothing', async () => {
    await store.photos.add('h1', jpeg(), 'p1', T1);
    await store.photos.add('h1', jpeg(), 'p2', T1);
    await markUploaded('p2');
    await store.photos.delete('p2', T2);
    const rows = await store.photos.rowsByIds(['p2', 'nope', 'p1']);
    expect(rows.map((r) => r.id).sort()).toEqual(['p1', 'p2']);
    expect(rows.find((r) => r.id === 'p2')?.deleted).toBe(true);
    expect(await store.photos.rowsByIds([])).toEqual([]);
  });

  it('gives back a deleted photo by id, and nothing for an unknown one', async () => {
    await store.photos.add('h1', jpeg(), 'p1', T1);
    await markUploaded('p1');
    await store.photos.delete('p1', T2);
    expect((await store.photos.get('p1'))?.deleted).toBe(true);
    expect(await store.photos.get('nope')).toBeUndefined();
  });

  it('forgets a photo completely, a tombstone too', async () => {
    await store.photos.add('h1', jpeg(), 'p1', T1);
    await markUploaded('p1');
    await store.photos.delete('p1', T2);
    const before = store.revision();
    await store.photos.forget('p1');
    expect(await store.photos.get('p1')).toBeUndefined();
    expect(await store.photos.all()).toEqual([]);
    expect(store.revision()).toBe(before + 1);
  });

  it('stores a row as given for sync and import: no dirty flag, no limit', async () => {
    const row = (id: string): PhotoRecord => ({
      id, houseId: 'h1', blob: jpeg(), contentType: 'image/jpeg', sizeBytes: 1, createdAt: '2026-09-01T00:00:00.000Z',
      updatedAt: '2026-09-01T00:00:00.000Z', deleted: false, syncVersion: 4, uploaded: true,
    });
    const before = store.revision();
    for (let i = 0; i <= MAX_PHOTOS_PER_HOUSE; i++) await store.photos.put(row(`p${i}`));
    expect(await store.photos.ofHouse('h1')).toHaveLength(MAX_PHOTOS_PER_HOUSE + 1);
    expect(await store.photos.get('p0')).toEqual(row('p0'));
    expect(store.revision()).toBe(before + MAX_PHOTOS_PER_HOUSE + 1);
  });

  it('changes nothing, and does not bump the revision, for a delete of an unknown photo', async () => {
    const before = store.revision();
    await store.photos.delete('nope', T2);
    expect(store.revision()).toBe(before);
  });

  it('keeps a tombstone without bytes for an uploaded photo, with the time of the delete', async () => {
    await store.photos.add('h1', jpeg(), 'p1', T1);
    await markUploaded('p1');
    await store.photos.delete('p1', T2);
    expect(await store.photos.get('p1')).toMatchObject({ blob: null, deleted: true, uploaded: true, updatedAt: '2026-09-02T00:00:00.000Z' });
  });

  it('deleting a house tombstones its uploaded photos and removes the others, leaving other houses alone', async () => {
    await store.photos.add('h1', jpeg(), 'sent', T1);
    await store.photos.add('h1', jpeg(), 'local', T1);
    await store.photos.add('h2', jpeg(), 'kept', T1);
    await markUploaded('sent');
    await store.deleteHouse('h1', T2);
    expect((await store.photos.all()).map((p) => [p.id, p.deleted])).toEqual([['kept', false], ['sent', true]]);
    expect((await store.photos.get('sent'))?.blob).toBeNull();
  });

  it('a full resend asks for the bytes of uploaded photos again and for the meta that was clean', async () => {
    await store.photos.add('h1', jpeg(), 'sent', T1);
    await store.photos.add('h1', jpeg(), 'local', T1);
    await store.photos.add('h1', jpeg(), 'gone', T1, { roomId: null, tags: ['DAMP'], caption: null });
    await store.photos.markMetaClean('gone', T1);
    await store.photos.add('h1', jpeg(), 'waiting', T1, { roomId: null, tags: ['DAMP'], caption: null });
    await store.photos.add('h1', jpeg(), 'clean', T1, { roomId: null, tags: ['DAMP'], caption: null });
    await markUploaded('sent');
    await markUploaded('gone');
    await store.photos.delete('gone', T2);
    await store.photos.markMetaClean('clean', T1);
    await store.markAllForResync();
    expect((await store.photos.get('sent'))?.uploaded).toBe(false);
    expect((await store.photos.get('local'))?.uploaded).toBe(false);
    // A deleted photo's tombstone is left as it is: its delete is still to be sent.
    expect(await store.photos.get('gone')).toMatchObject({ deleted: true, uploaded: true });
    expect(await store.photos.get('gone')).not.toHaveProperty('metaDirty');
    expect((await store.photos.get('waiting'))?.metaDirty).toBe(true);
    expect((await store.photos.get('clean'))?.metaDirty).toBe(true);
    expect(await store.photos.get('sent')).not.toHaveProperty('metaDirty');
  });
});
