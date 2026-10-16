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

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { HouseDto, HouseStatus } from '../core/models';
import { LocalStore } from './local-store.service';

function house(id: string, status: HouseStatus = 'NEW', over: Partial<HouseDto> = {}): HouseDto {
  return { id, label: `House ${id}`, lat: 13, lon: 80, status, checklist: {}, deleted: false, syncVersion: 0, ...over };
}

const jpeg = () => new Blob([new Uint8Array([1])], { type: 'image/jpeg' });
const T1 = Date.parse('2026-09-01T00:00:00.000Z');
const T2 = Date.parse('2026-09-02T00:00:00.000Z');

/** Photo meta (docs/11 5.7) and the Taken/Not chosen statuses (5.24) in the browser's store. */
describe('LocalStore photo meta', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
    await store.saveHouse(house('h1'), T1);
    await store.photos.add('h1', jpeg(), 'p1', T1);
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('a photo starts without any meta and without a dirty flag', async () => {
    const p = await store.photos.get('p1');
    expect(p).not.toHaveProperty('roomId');
    expect(p).not.toHaveProperty('tags');
    expect(p).not.toHaveProperty('metaUpdatedAt');
    expect(p).not.toHaveProperty('metaDirty');
  });

  it('saving the meta coerces it, stamps metaUpdatedAt and marks the photo for the next sync', async () => {
    expect(await store.photos.setMeta('p1', { roomId: 'c1', tags: ['damp', 'leaky tap', 'LEAKY TAP'], caption: '  Corner  ' }, T2)).toBe(true);
    const p = await store.photos.get('p1');
    expect(p).toMatchObject({ roomId: 'c1', tags: ['DAMP', 'leaky tap'], caption: '  Corner  ', metaUpdatedAt: T2, metaDirty: true });
  });

  it('writes nothing when the meta is what it already is', async () => {
    await store.photos.setMeta('p1', { roomId: null, tags: ['DAMP'], caption: null }, T1);
    const revision = store.revision();
    expect(await store.photos.setMeta('p1', { roomId: null, tags: ['DAMP'], caption: '' }, T2)).toBe(false);
    expect((await store.photos.get('p1'))?.metaUpdatedAt).toBe(T1);
    expect(store.revision()).toBe(revision);
  });

  it('removing the last of the meta still counts as an edit, so it reaches the server', async () => {
    await store.photos.setMeta('p1', { roomId: 'c1', tags: [], caption: null }, T1);
    expect(await store.photos.setMeta('p1', { roomId: null, tags: [], caption: null }, T2)).toBe(true);
    const p = await store.photos.get('p1');
    expect(p).not.toHaveProperty('roomId');
    expect(p).toMatchObject({ metaUpdatedAt: T2, metaDirty: true });
  });

  it('two edits in one millisecond still order', async () => {
    await store.photos.setMeta('p1', { roomId: null, tags: ['DAMP'], caption: null }, T1);
    await store.photos.setMeta('p1', { roomId: null, tags: ['CRACK'], caption: null }, T1);
    expect((await store.photos.get('p1'))?.metaUpdatedAt).toBe(T1 + 1);
  });

  it('a deleted or unknown photo is not edited', async () => {
    expect(await store.photos.setMeta('nope', { roomId: null, tags: ['DAMP'], caption: null }, T1)).toBe(false);
    await store.photos.delete('p1', T2);
    expect(await store.photos.setMeta('p1', { roomId: null, tags: ['DAMP'], caption: null }, T2)).toBe(false);
  });

  it('last write wins on metaUpdatedAt: only a strictly newer server meta replaces ours', async () => {
    await store.photos.setMeta('p1', { roomId: null, tags: ['DAMP'], caption: 'mine' }, T2);
    const theirs = { roomId: 'r9', tags: ['LEAK'], caption: 'theirs' };
    expect(await store.photos.applyMetaFromServer('p1', { ...theirs, metaUpdatedAt: T2 })).toBe(false);
    expect(await store.photos.applyMetaFromServer('p1', { ...theirs, metaUpdatedAt: T1 })).toBe(false);
    expect((await store.photos.get('p1'))?.caption).toBe('mine');
    expect(await store.photos.applyMetaFromServer('p1', { ...theirs, metaUpdatedAt: T2 + 1 })).toBe(true);
    const p = await store.photos.get('p1');
    expect(p).toMatchObject({ roomId: 'r9', tags: ['LEAK'], caption: 'theirs', metaUpdatedAt: T2 + 1 });
    expect(p).not.toHaveProperty('metaDirty');
  });

  it('marking the push clean keeps the flag when the meta was edited again meanwhile', async () => {
    await store.photos.setMeta('p1', { roomId: null, tags: ['DAMP'], caption: null }, T1);
    await store.photos.setMeta('p1', { roomId: null, tags: ['CRACK'], caption: null }, T2);
    await store.photos.markMetaClean('p1', T1);
    expect((await store.photos.get('p1'))?.metaDirty).toBe(true);
    await store.photos.markMetaClean('p1', T2);
    expect(await store.photos.get('p1')).not.toHaveProperty('metaDirty');
  });

  it('a photo added with MOVE_IN chosen has that meta, stamped and waiting', async () => {
    await store.photos.add('h1', jpeg(), 'p2', T2, { roomId: null, tags: ['MOVE_IN'], caption: null });
    expect(await store.photos.get('p2')).toMatchObject({ tags: ['MOVE_IN'], metaUpdatedAt: T2, metaDirty: true });
    expect((await store.photos.conditionOf('h1')).map((p) => p.id)).toEqual(['p2']);
    await store.photos.setMeta('p1', { roomId: null, tags: ['move_in'], caption: null }, T2);
    expect((await store.photos.conditionOf('h1')).map((p) => p.id).sort()).toEqual(['p1', 'p2']);
  });

  it('a full resend marks a photo with meta dirty again', async () => {
    await store.photos.setMeta('p1', { roomId: null, tags: ['DAMP'], caption: null }, T1);
    await store.photos.markMetaClean('p1', T1);
    await store.markAllForResync();
    expect((await store.photos.get('p1'))?.metaDirty).toBe(true);
  });
});

describe('LocalStore Taken and Not chosen', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
    await store.saveHouse(house('a', 'TAKEN'), T1);
    await store.saveHouse(house('b', 'SHORTLISTED'), T1);
    await store.saveHouse(house('c', 'NEW'), T1);
    await store.saveHouse(house('d', 'REJECTED'), T1);
  });

  afterEach(() => {
    localStorage.clear();
  });

  const statusOf = async () => Object.fromEntries((await store.liveHouses()).map((h) => [h.id, h.status]));

  it('stores the two new statuses as they are', async () => {
    await store.saveHouse(house('e', 'NOT_CHOSEN'), T1);
    expect((await store.getHouse('e'))?.status).toBe('NOT_CHOSEN');
    expect((await store.getHouse('a'))?.status).toBe('TAKEN');
  });

  it('m1_taking_another_house_returns_the_previous_one_to_shortlisted', async () => {
    await store.saveHouse(house('b', 'TAKEN'), T2);
    expect(await store.applyTaken('b', false, T2)).toBe(1);
    expect(await statusOf()).toEqual({ a: 'SHORTLISTED', b: 'TAKEN', c: 'NEW', d: 'REJECTED' });
    const a = await store.getHouse('a');
    expect(a?.dirty).toBe(true);
    expect(a?.updatedAt).toBe(new Date(T2).toISOString());
  });

  it('marks the other open houses Not chosen when asked, and leaves the rejected one', async () => {
    await store.saveHouse(house('b', 'TAKEN'), T2);
    expect(await store.applyTaken('b', true, T2)).toBe(2);
    expect(await statusOf()).toEqual({ a: 'NOT_CHOSEN', b: 'TAKEN', c: 'NOT_CHOSEN', d: 'REJECTED' });
  });

  it('m2_close_this_hunt_marks_every_open_house_in_one_step_and_deletes_nothing', async () => {
    expect(await store.closeCount('a')).toBe(2);
    expect(await store.closeHunt('a', T2)).toBe(2);
    expect(await statusOf()).toEqual({ a: 'TAKEN', b: 'NOT_CHOSEN', c: 'NOT_CHOSEN', d: 'REJECTED' });
    expect((await store.allHouses()).every((h) => !h.deleted)).toBe(true);
    expect(await store.closeCount('a')).toBe(0);
    expect(await store.closeHunt('a', T2)).toBe(0);
  });
});
