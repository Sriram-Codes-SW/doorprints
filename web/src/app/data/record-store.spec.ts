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

  it('lists every record of one type with its tombstones, oldest edit first, for an import to compare', async () => {
    await store.saveRecord('place', 'p2', { name: 'B' }, T2);
    await store.saveRecord('place', 'p1', { name: 'A' }, T1);
    await store.saveRecord('place', 'p3', { name: 'C' }, T3);
    await store.saveRecord('broker', 'b1', { name: 'Other type' }, T1);
    await store.deleteRecord('place', 'p3', T3);
    const rows = await store.allRecordsOf('place');
    expect(rows.map((r) => r.id).sort()).toEqual(['p1', 'p2', 'p3']);
    expect(rows.find((r) => r.id === 'p3')).toMatchObject({ deleted: true, payload: {} });
    expect(await store.allRecordsOf('area')).toEqual([]);
  });

  it('lists every record of every type, tombstones included, oldest edit first', async () => {
    await store.saveRecord('place', 'p1', { name: 'Late' }, T3);
    await store.saveRecord('broker', 'b1', { name: 'Early' }, T1);
    await store.saveRecord('viewing', 'v1', {}, T2);
    await store.deleteRecord('viewing', 'v1', T3);
    expect((await store.allRecords()).map((r) => `${r.type}/${r.id}`)).toEqual(['broker/b1', 'place/p1', 'viewing/v1']);
    expect((await store.allRecords()).find((r) => r.id === 'v1')?.deleted).toBe(true);
  });

  it('reads the rows under some [type, id] keys in one go, a tombstone included and an unknown key left out', async () => {
    await store.saveRecord('place', 'p1', { name: 'A' }, T1);
    await store.saveRecord('broker', 'p1', { name: 'Same id, other type' }, T1);
    await store.saveRecord('place', 'p2', { name: 'B' }, T1);
    await store.deleteRecord('place', 'p2', T2);
    const rows = await store.recordRowsByKeys([['place', 'p1'], ['place', 'p2'], ['place', 'nope'], ['broker', 'p1']]);
    expect(rows.map((r) => `${r.type}/${r.id}`).sort()).toEqual(['broker/p1', 'place/p1', 'place/p2']);
    expect(rows.find((r) => r.id === 'p2')?.deleted).toBe(true);
    expect(await store.recordRowsByKeys([])).toEqual([]);
  });

  it('stores a record from the remote clean, with the server’s time and sync version, and refuses one it cannot trust', async () => {
    await store.putRecordFromServer({ type: 'area', id: 'a1', payload: { name: 'Adyar' }, updatedAt: '2026-09-05T00:00:00.000Z', deleted: false, syncVersion: 4 });
    expect(await store.getRecord('area', 'a1')).toEqual({ type: 'area', id: 'a1', payload: { name: 'Adyar' }, updatedAt: '2026-09-05T00:00:00.000Z', deleted: false, syncVersion: 4, dirty: false });
    expect(await store.dirtyRecords()).toEqual([]);
    await store.putRecordFromServer({ type: 'area', id: 'a1', payload: {}, updatedAt: '2026-09-06T00:00:00.000Z', deleted: true, syncVersion: 5 });
    expect(await store.getRecord('area', 'a1')).toBeUndefined();
    expect((await store.allRecords())[0]).toMatchObject({ deleted: true, syncVersion: 5, dirty: false });
    await expect(store.putRecordFromServer({ type: 'Bad Type', id: 'a2', payload: {}, updatedAt: null, deleted: false, syncVersion: 0 })).rejects.toBeInstanceOf(LocalDataError);
  });

  it('bumps the revision on a save, a delete of a stored record and a record from the remote, and not on a no-op', async () => {
    const start = store.revision();
    await store.saveRecord('place', 'p1', { name: 'A' }, T1);
    expect(store.revision()).toBe(start + 1);
    await store.deleteRecord('place', 'never-there', T2);
    await store.markRecordClean('place', 'never-there', null);
    expect(store.revision()).toBe(start + 1);
    await store.deleteRecord('place', 'p1', T2);
    expect(store.revision()).toBe(start + 2);
    await store.putRecordFromServer({ type: 'place', id: 'p2', payload: {}, updatedAt: null, deleted: false, syncVersion: 1 });
    expect(store.revision()).toBe(start + 3);
  });

  it('writes a record back after a delete: it lives again, keeps its sync version and is dirty', async () => {
    await store.putRecordFromServer({ type: 'place', id: 'p1', payload: { name: 'A' }, updatedAt: '2026-09-01T00:00:00.000Z', deleted: false, syncVersion: 7 });
    await store.deleteRecord('place', 'p1', T2);
    await store.saveRecord('place', 'p1', { name: 'Back' }, T3);
    expect(await store.getRecord('place', 'p1')).toMatchObject({ payload: { name: 'Back' }, deleted: false, syncVersion: 7, dirty: true });
  });

  it('keeps a record dirty when the clean mark names no time, and ignores an unknown record', async () => {
    await store.saveRecord('place', 'p1', { name: 'A' }, T1);
    await store.markRecordClean('place', 'p1', null);
    await store.markRecordClean('place', 'p1', undefined);
    expect((await store.getRecord('place', 'p1'))?.dirty).toBe(true);
    await store.markRecordClean('place', 'unknown', '2026-09-01T00:00:00.000Z');
    expect(await store.getRecord('place', 'unknown')).toBeUndefined();
  });

  it('lists the dirty records, tombstones included, oldest edit first, and not the clean ones', async () => {
    await store.saveRecord('place', 'p2', { name: 'B' }, T2);
    await store.saveRecord('place', 'p1', { name: 'A' }, T1);
    await store.saveRecord('place', 'p3', { name: 'C' }, T3);
    await store.markRecordClean('place', 'p3', '2026-09-03T00:00:00.000Z');
    await store.deleteRecord('place', 'p2', T3);
    const dirty = await store.dirtyRecords();
    expect(dirty.map((r) => r.id)).toEqual(['p1', 'p2']);
    expect(dirty[1].deleted).toBe(true);
  });

  it('draws a viewing-style id again while it clashes with a stored row, and gives up after 50 draws', async () => {
    await store.saveRecord('viewing', 'v_aaaaaaaa', {}, T1);
    expect(await store.newViewingId(() => 'v_bbbbbbbb')).toBe('v_bbbbbbbb');
    let draws = 0;
    await expect(store.newViewingId(() => (draws++, 'v_aaaaaaaa'))).rejects.toMatchObject({ key: 'error.badRecord' });
    expect(draws).toBe(50);
  });

  it('gives each saved viewing row its edit time', async () => {
    await store.saveViewing({ id: 'v_00000001', houseId: 'h1', startsAt: T3, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60 }, T2);
    expect(await store.viewingRows()).toEqual([
      { id: 'v_00000001', updatedAt: '2026-09-02T00:00:00.000Z', viewing: expect.objectContaining({ id: 'v_00000001', houseId: 'h1' }) },
    ]);
  });
});
