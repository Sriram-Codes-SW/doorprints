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
import { LocalDataError } from '../core/local-error';
import { LocalStore } from './local-store.service';

const T1 = Date.parse('2026-09-01T00:00:00.000Z');
const T2 = Date.parse('2026-09-02T00:00:00.000Z');
const T3 = Date.parse('2026-09-03T00:00:00.000Z');

/** The record rows every other kind of data is kept in: reads, the tombstone, the dirty flag and the sync's reads. */
describe('RecordStore (LocalStore.records)', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('lists the live records of one type only, oldest edit first, and hides a deleted one from the list and from a read', async () => {
    await store.records.save('place', 'p2', { name: 'B' }, T2);
    await store.records.save('place', 'p1', { name: 'A' }, T1);
    await store.records.save('place', 'p3', { name: 'C' }, T3);
    await store.records.save('broker', 'b1', { name: 'Other type' }, T1);
    await store.records.delete('place', 'p3', T3);
    expect((await store.records.ofType('place')).map((r) => r.id)).toEqual(['p1', 'p2']);
    expect(await store.records.get('place', 'p3')).toBeUndefined();
    expect(await store.records.get('place', 'p2')).toMatchObject({ payload: { name: 'B' } });
    expect(await store.records.get('broker', 'p2')).toBeUndefined();
  });

  it('leaves a tombstone with an empty payload, dirty and stamped with the time of the delete', async () => {
    await store.records.save('place', 'p1', { name: 'A' }, T1);
    await store.records.markClean('place', 'p1', '2026-09-01T00:00:00.000Z');
    await store.records.delete('place', 'p1', T2);
    expect(await store.records.all()).toEqual([
      { type: 'place', id: 'p1', payload: {}, updatedAt: '2026-09-02T00:00:00.000Z', deleted: true, syncVersion: 0, dirty: true },
    ]);
  });

  it('lists every record of one type with its tombstones, oldest edit first, for an import to compare', async () => {
    await store.records.save('place', 'p2', { name: 'B' }, T2);
    await store.records.save('place', 'p1', { name: 'A' }, T1);
    await store.records.save('place', 'p3', { name: 'C' }, T3);
    await store.records.save('broker', 'b1', { name: 'Other type' }, T1);
    await store.records.delete('place', 'p3', T3);
    const rows = await store.records.allOfType('place');
    expect(rows.map((r) => r.id).sort()).toEqual(['p1', 'p2', 'p3']);
    expect(rows.find((r) => r.id === 'p3')).toMatchObject({ deleted: true, payload: {} });
    expect(await store.records.allOfType('area')).toEqual([]);
  });

  it('lists every record of every type, tombstones included, oldest edit first', async () => {
    await store.records.save('place', 'p1', { name: 'Late' }, T3);
    await store.records.save('broker', 'b1', { name: 'Early' }, T1);
    await store.records.save('viewing', 'v1', {}, T2);
    await store.records.delete('viewing', 'v1', T3);
    expect((await store.records.all()).map((r) => `${r.type}/${r.id}`)).toEqual(['broker/b1', 'place/p1', 'viewing/v1']);
    expect((await store.records.all()).find((r) => r.id === 'v1')?.deleted).toBe(true);
  });

  it('reads the rows under some [type, id] keys in one go, a tombstone included and an unknown key left out', async () => {
    await store.records.save('place', 'p1', { name: 'A' }, T1);
    await store.records.save('broker', 'p1', { name: 'Same id, other type' }, T1);
    await store.records.save('place', 'p2', { name: 'B' }, T1);
    await store.records.delete('place', 'p2', T2);
    const rows = await store.records.rowsByKeys([['place', 'p1'], ['place', 'p2'], ['place', 'nope'], ['broker', 'p1']]);
    expect(rows.map((r) => `${r.type}/${r.id}`).sort()).toEqual(['broker/p1', 'place/p1', 'place/p2']);
    expect(rows.find((r) => r.id === 'p2')?.deleted).toBe(true);
    expect(await store.records.rowsByKeys([])).toEqual([]);
  });

  it('stores a record from the remote clean, with the server’s time and sync version, and refuses one it cannot trust', async () => {
    await store.records.putFromServer({ type: 'area', id: 'a1', payload: { name: 'Adyar' }, updatedAt: '2026-09-05T00:00:00.000Z', deleted: false, syncVersion: 4 });
    expect(await store.records.get('area', 'a1')).toEqual({ type: 'area', id: 'a1', payload: { name: 'Adyar' }, updatedAt: '2026-09-05T00:00:00.000Z', deleted: false, syncVersion: 4, dirty: false });
    expect(await store.records.dirty()).toEqual([]);
    await store.records.putFromServer({ type: 'area', id: 'a1', payload: {}, updatedAt: '2026-09-06T00:00:00.000Z', deleted: true, syncVersion: 5 });
    expect(await store.records.get('area', 'a1')).toBeUndefined();
    expect((await store.records.all())[0]).toMatchObject({ deleted: true, syncVersion: 5, dirty: false });
    await expect(store.records.putFromServer({ type: 'Bad Type', id: 'a2', payload: {}, updatedAt: null, deleted: false, syncVersion: 0 })).rejects.toBeInstanceOf(LocalDataError);
  });

  it('bumps the revision on a save, a delete of a stored record and a record from the remote, and not on a no-op', async () => {
    const start = store.revision();
    await store.records.save('place', 'p1', { name: 'A' }, T1);
    expect(store.revision()).toBe(start + 1);
    await store.records.delete('place', 'never-there', T2);
    await store.records.markClean('place', 'never-there', null);
    expect(store.revision()).toBe(start + 1);
    await store.records.delete('place', 'p1', T2);
    expect(store.revision()).toBe(start + 2);
    await store.records.putFromServer({ type: 'place', id: 'p2', payload: {}, updatedAt: null, deleted: false, syncVersion: 1 });
    expect(store.revision()).toBe(start + 3);
  });

  it('writes a record back after a delete: it lives again, keeps its sync version and is dirty', async () => {
    await store.records.putFromServer({ type: 'place', id: 'p1', payload: { name: 'A' }, updatedAt: '2026-09-01T00:00:00.000Z', deleted: false, syncVersion: 7 });
    await store.records.delete('place', 'p1', T2);
    await store.records.save('place', 'p1', { name: 'Back' }, T3);
    expect(await store.records.get('place', 'p1')).toMatchObject({ payload: { name: 'Back' }, deleted: false, syncVersion: 7, dirty: true });
  });

  it('keeps a record dirty when the clean mark names no time, and ignores an unknown record', async () => {
    await store.records.save('place', 'p1', { name: 'A' }, T1);
    await store.records.markClean('place', 'p1', null);
    await store.records.markClean('place', 'p1', undefined);
    expect((await store.records.get('place', 'p1'))?.dirty).toBe(true);
    await store.records.markClean('place', 'unknown', '2026-09-01T00:00:00.000Z');
    expect(await store.records.get('place', 'unknown')).toBeUndefined();
  });

  it('lists the dirty records, tombstones included, oldest edit first, and not the clean ones', async () => {
    await store.records.save('place', 'p2', { name: 'B' }, T2);
    await store.records.save('place', 'p1', { name: 'A' }, T1);
    await store.records.save('place', 'p3', { name: 'C' }, T3);
    await store.records.markClean('place', 'p3', '2026-09-03T00:00:00.000Z');
    await store.records.delete('place', 'p2', T3);
    const dirty = await store.records.dirty();
    expect(dirty.map((r) => r.id)).toEqual(['p1', 'p2']);
    expect(dirty[1].deleted).toBe(true);
  });

  it('draws an id again while it clashes with a stored row, and gives up after 50 draws', async () => {
    await store.records.save('viewing', 'v_aaaaaaaa', {}, T1);
    expect(await store.records.freshId('viewing', () => 'v_bbbbbbbb')).toBe('v_bbbbbbbb');
    let draws = 0;
    await expect(store.records.freshId('viewing', () => (draws++, 'v_aaaaaaaa'))).rejects.toMatchObject({ key: 'error.badRecord' });
    expect(draws).toBe(50);
    // The same id under another type does not clash.
    expect(await store.records.freshId('place', () => 'v_aaaaaaaa')).toBe('v_aaaaaaaa');
  });
});
