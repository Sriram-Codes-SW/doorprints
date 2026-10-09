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
import type { Area, AreaNote, Place } from '../shared/area';

const T1 = Date.parse('2026-09-01T00:00:00.000Z');
const T2 = Date.parse('2026-09-02T00:00:00.000Z');

/** Slice 4a (docs/11 "Design of slice 4a"): areas, places and area notes, records of type `area`, `place`, `areanote`; and their rows, trimming and ranges. */
describe('areas, places and area notes (LocalStore.areas, .places, .areaNotes)', () => {
  let store: LocalStore;
  const area = (id: string, over: Partial<Area> = {}): Area => ({ id, name: 'Adyar', lat: 13.0067, lon: 80.2574, radiusM: 500, enabled: true, ...over });
  const place = (id: string, over: Partial<Place> = {}): Place => ({ id, name: 'Office', lat: 13.0827, lon: 80.2707, ...over });
  const note = (id: string, over: Partial<AreaNote> = {}): AreaNote => ({ id, street: 'MG Road', text: 'Noisy', ...over });

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('gives each area row its record id, its edit time and the typed area, oldest edit first', async () => {
    await store.areas.save(area('a_00000002', { name: 'Bandra' }), T2);
    await store.areas.save(area('a_00000001', { name: 'Colaba' }), T1);
    expect(await store.areas.rows()).toEqual([
      { id: 'a_00000001', updatedAt: '2026-09-01T00:00:00.000Z', area: area('a_00000001', { name: 'Colaba' }) },
      { id: 'a_00000002', updatedAt: '2026-09-02T00:00:00.000Z', area: area('a_00000002', { name: 'Bandra' }) },
    ]);
  });

  it('gives each place row its edit time and leaves out a deleted place and a stored row that is not a place', async () => {
    await store.places.save(place('p_00000001', { name: 'Mall Road, Shimla', lat: 31.1048, lon: 77.1734 }), T1);
    await store.places.save(place('p_00000002'), T1);
    await store.places.delete('p_00000002', T2);
    await store.records.save('place', 'p_00000003', { name: 'No point' }, T1);
    expect(await store.places.rows()).toEqual([
      { id: 'p_00000001', updatedAt: '2026-09-01T00:00:00.000Z', place: place('p_00000001', { name: 'Mall Road, Shimla', lat: 31.1048, lon: 77.1734 }) },
    ]);
  });

  it('gives each area-note row its edit time, oldest edit first, whether its area exists or not', async () => {
    await store.areaNotes.save(note('n_00000002', { street: undefined, areaId: 'a_gone', text: 'Water at night' }), T2);
    await store.areaNotes.save(note('n_00000001', { street: 'Park Street, Kolkata' }), T1);
    expect(await store.areaNotes.rows()).toEqual([
      { id: 'n_00000001', updatedAt: '2026-09-01T00:00:00.000Z', note: { id: 'n_00000001', street: 'Park Street, Kolkata', text: 'Noisy' } },
      { id: 'n_00000002', updatedAt: '2026-09-02T00:00:00.000Z', note: { id: 'n_00000002', areaId: 'a_gone', text: 'Water at night' } },
    ]);
  });

  it('trims a place name and returns the trimmed place', async () => {
    const saved = await store.places.save(place('p_00000001', { name: '  Amma  ' }), T1);
    expect(saved.name).toBe('Amma');
    expect((await store.records.get('place', 'p_00000001'))?.payload).toMatchObject({ name: 'Amma' });
  });

  it('trims a note text, street and area id; a blank one counts as absent, so the other target is the one', async () => {
    const byStreet = await store.areaNotes.save({ id: 'n_00000001', areaId: '   ', street: '  Linking Road  ', text: '  Loud  ' }, T1);
    expect(byStreet).toEqual({ id: 'n_00000001', street: 'Linking Road', text: 'Loud' });
    const byArea = await store.areaNotes.save({ id: 'n_00000002', areaId: ' a_00000001 ', street: '  ', text: 'Calm' }, T1);
    expect(byArea).toEqual({ id: 'n_00000002', areaId: 'a_00000001', text: 'Calm' });
  });

  it('accepts the edges of the ranges: latitude 90, longitude -180, the smallest and largest radius, names at their cap', async () => {
    await store.areas.save(area('a_00000001', { lat: 90, lon: -180, radiusM: 200, name: 'n'.repeat(100) }), T1);
    await store.areas.save(area('a_00000002', { lat: -90, lon: 180, radiusM: 2000 }), T1);
    await store.places.save(place('p_00000001', { name: 'n'.repeat(60), lat: -90, lon: 180 }), T1);
    await store.areaNotes.save(note('n_00000001', { street: 's'.repeat(100), text: 't'.repeat(1000) }), T1);
    await store.areaNotes.save(note('n_00000002', { street: undefined, areaId: 'a'.repeat(64) }), T1);
    expect((await store.areas.all()).map((a) => a.id).sort()).toEqual(['a_00000001', 'a_00000002']);
    expect(await store.places.all()).toHaveLength(1);
    expect(await store.areaNotes.rows()).toHaveLength(2);
  });

  it('refuses a point or radius one step outside the ranges, a fractional radius, and a name or street over its cap', async () => {
    const badArea = (over: Partial<Area>) => expect(store.areas.save(area('a_00000001', over))).rejects.toBeInstanceOf(LocalDataError);
    await badArea({ lat: 90.1 });
    await badArea({ lon: -180.1 });
    await badArea({ radiusM: 199 });
    await badArea({ radiusM: 2001 });
    await badArea({ radiusM: 500.5 });
    await badArea({ name: 'n'.repeat(101) });
    await expect(store.places.save(place('p_00000001', { lon: 180.1 }))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.places.save(place('p_00000001', { name: '   ' }))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.areaNotes.save(note('n_00000001', { street: 's'.repeat(101) }))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.areaNotes.save(note('n_00000001', { text: 't'.repeat(1001) }))).rejects.toBeInstanceOf(LocalDataError);
    expect(await store.records.allOfType('area')).toEqual([]);
  });

  it('refuses a place or note id that is not a usable record key', async () => {
    await expect(store.places.save(place('bad id'))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.areaNotes.save(note('..'))).rejects.toBeInstanceOf(LocalDataError);
  });

  it('says the id is bad, not that the list is full, when an area or place with a bad id comes at the cap', async () => {
    const hex = (i: number) => i.toString(16).padStart(8, '0');
    for (let i = 0; i < 20; i++) await store.areas.save(area('a_' + hex(i), { name: 'Area ' + i }), T1);
    for (let i = 0; i < 10; i++) await store.places.save(place('p_' + hex(i), { name: 'Place ' + i }), T1);
    await expect(store.areas.save(area('bad id'))).rejects.toMatchObject({ key: 'error.badRecord' });
    await expect(store.places.save(place('bad id'))).rejects.toMatchObject({ key: 'error.badRecord' });
  });

  it('keeps the enabled flag of an area off after a save and a read, and reads a stored area with no radius as 500 m', async () => {
    await store.areas.save(area('a_00000001', { enabled: false }), T1);
    await store.records.save('area', 'a_00000002', { name: 'Hand-made', lat: 1, lon: 1 }, T1);
    const byId = new Map((await store.areas.all()).map((a) => [a.id, a]));
    expect(byId.get('a_00000001')?.enabled).toBe(false);
    expect(byId.get('a_00000002')).toMatchObject({ radiusM: 500, enabled: true });
  });

  it('makes a saved change dirty for the sync: a place edit and an area-note delete show in the dirty records', async () => {
    await store.places.save(place('p_00000001'), T1);
    await store.areaNotes.save(note('n_00000001'), T1);
    for (const r of await store.records.dirty()) await store.records.markClean(r.type, r.id, r.updatedAt);
    await store.places.save(place('p_00000001', { name: 'Home' }), T2);
    await store.areaNotes.delete('n_00000001', T2);
    expect((await store.records.dirty()).map((r) => `${r.type}/${r.id}/${r.deleted}`).sort()).toEqual(['areanote/n_00000001/true', 'place/p_00000001/false']);
  });

  it('saves an area as a dirty record of type area with the keys in the contract order, enabled only when false', async () => {
    await store.areas.save(area('a_00000001'), T1);
    const record = await store.records.get('area', 'a_00000001');
    expect(Object.keys(record!.payload)).toEqual(['name', 'lat', 'lon', 'radiusM']);
    expect(record).toMatchObject({ dirty: true, deleted: false });
    await store.areas.save(area('a_00000001', { enabled: false, name: '  Adyar  ' }), T2);
    expect(Object.keys((await store.records.get('area', 'a_00000001'))!.payload)).toEqual(['name', 'lat', 'lon', 'radiusM', 'enabled']);
    expect(await store.areas.all()).toEqual([area('a_00000001', { enabled: false })]);
  });

  it('writes only when something changed: an unchanged area keeps updatedAt and the sync queue as they were', async () => {
    await store.areas.save(area('a_00000001'), T1);
    const first = await store.records.get('area', 'a_00000001');
    for (const r of await store.records.dirty()) await store.records.markClean('area', r.id, r.updatedAt);
    await store.areas.save(area('a_00000001'), T2);
    expect((await store.records.get('area', 'a_00000001'))?.updatedAt).toBe(first?.updatedAt);
    expect(await store.records.dirty()).toEqual([]);
    await store.areas.save(area('a_00000001', { radiusM: 900 }), T2);
    expect((await store.records.dirty()).map((r) => r.id)).toEqual(['a_00000001']);
  });

  it('lists areas and places by name then id, and notes newest first', async () => {
    await store.areas.save(area('a_0000000b', { name: 'Zed' }), T1);
    await store.areas.save(area('a_0000000a', { name: 'adyar' }), T1);
    expect((await store.areas.all()).map((a) => a.id)).toEqual(['a_0000000a', 'a_0000000b']);
    await store.places.save(place('p_00000002', { name: 'Office' }), T1);
    await store.places.save(place('p_00000001', { name: 'Amma' }), T1);
    expect((await store.places.all()).map((p) => p.id)).toEqual(['p_00000001', 'p_00000002']);
    await store.areaNotes.save(note('n_00000001'), T1);
    await store.areaNotes.save(note('n_00000002', { text: 'Newer' }), T2);
    expect((await store.areaNotes.all()).map((n) => n.id)).toEqual(['n_00000002', 'n_00000001']);
  });

  it('draws new a_, p_ and n_ ids that clash with no record, a deleted one included', async () => {
    await store.areas.save(area('a_aaaaaaaa'), T1);
    await store.areas.delete('a_aaaaaaaa', T1);
    const drawn = ['a_aaaaaaaa', 'a_aaaaaaaa', 'a_bbbbbbbb'];
    expect(await store.areas.newId(() => drawn.shift() ?? 'a_cccccccc')).toBe('a_bbbbbbbb');
    await store.places.save(place('p_aaaaaaaa'), T1);
    await store.places.delete('p_aaaaaaaa', T1);
    const p = ['p_aaaaaaaa', 'p_bbbbbbbb'];
    expect(await store.places.newId(() => p.shift() ?? 'p_cccccccc')).toBe('p_bbbbbbbb');
    await store.areaNotes.save(note('n_aaaaaaaa'), T1);
    await store.areaNotes.delete('n_aaaaaaaa', T1);
    const n = ['n_aaaaaaaa', 'n_bbbbbbbb'];
    expect(await store.areaNotes.newId(() => n.shift() ?? 'n_cccccccc')).toBe('n_bbbbbbbb');
    expect(await store.areas.newId()).toMatch(/^a_[0-9a-f]{8}$/);
  });

  it('orders areas and places by name even when the name order is the reverse of the id order and of the edit order', async () => {
    await store.areas.save(area('a_00000001', { name: 'Zed' }), T1);
    await store.areas.save(area('a_00000002', { name: 'Adyar' }), T2);
    expect((await store.areas.all()).map((a) => a.id)).toEqual(['a_00000002', 'a_00000001']);
    await store.places.save(place('p_00000001', { name: 'Work' }), T1);
    await store.places.save(place('p_00000002', { name: 'Amma' }), T2);
    expect((await store.places.all()).map((p) => p.id)).toEqual(['p_00000002', 'p_00000001']);
  });

  it('deletes as a tombstone the next sync sends, and keeps the notes of a deleted area (they reach no house)', async () => {
    await store.areas.save(area('a_00000001'), T1);
    await store.areaNotes.save(note('n_00000001', { street: undefined, areaId: 'a_00000001' }), T1);
    await store.areas.delete('a_00000001', T2);
    expect(await store.areas.all()).toEqual([]);
    expect((await store.records.dirty()).find((r) => r.id === 'a_00000001')).toMatchObject({ deleted: true, payload: {} });
    expect((await store.areaNotes.all()).map((n) => n.id)).toEqual(['n_00000001']);
  });

  it('refuses a bad area: id, blank or over-long name, point and radius out of range', async () => {
    const refused = (over: Partial<Area>) => expect(store.areas.save(area('a_00000001', over))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.areas.save(area('bad id'))).rejects.toBeInstanceOf(LocalDataError);
    await refused({ name: '  ' });
    await refused({ name: 'n'.repeat(101) });
    await refused({ lat: 90.5 });
    await refused({ lon: -181 });
    await refused({ radiusM: 199 });
    await refused({ radiusM: 2001 });
    await refused({ radiusM: 650.5 });
    expect(await store.areas.all()).toEqual([]);
  });

  it('refuses a bad place and a bad note: neither or both targets, blank or over-long text', async () => {
    await expect(store.places.save(place('p_00000001', { name: 'n'.repeat(61) }))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.places.save(place('p_00000001', { lat: 100 }))).rejects.toBeInstanceOf(LocalDataError);
    const refused = (n: AreaNote) => expect(store.areaNotes.save(n)).rejects.toBeInstanceOf(LocalDataError);
    await refused({ id: 'n_00000001', text: 'no target' });
    await refused({ id: 'n_00000001', areaId: 'a_1', street: 'MG Road', text: 'both' });
    await refused(note('n_00000001', { text: '  ' }));
    await refused(note('n_00000001', { text: 'x'.repeat(1001) }));
    await refused(note('n_00000001', { street: 's'.repeat(101) }));
    await refused({ id: 'n_00000001', areaId: 'a'.repeat(65), text: 'x' });
    expect(await store.areaNotes.all()).toEqual([]);
  });

  it('caps the live records at 20 areas, 10 places and 200 notes; an edit of an existing one and a deleted slot still work', async () => {
    const hex = (i: number) => i.toString(16).padStart(8, '0');
    for (let i = 0; i < 20; i++) await store.areas.save(area('a_' + hex(i), { name: 'Area ' + i }), T1);
    await expect(store.areas.save(area('a_' + hex(99)))).rejects.toMatchObject({ key: 'areas.max' });
    await store.areas.save(area('a_' + hex(3), { name: 'Renamed' }), T2);
    await store.areas.delete('a_' + hex(4), T2);
    await store.areas.save(area('a_' + hex(99)), T2);
    for (let i = 0; i < 10; i++) await store.places.save(place('p_' + hex(i), { name: 'Place ' + i }), T1);
    await expect(store.places.save(place('p_' + hex(99)))).rejects.toMatchObject({ key: 'places.max' });
    for (let i = 0; i < 200; i++) await store.areaNotes.save(note('n_' + hex(i), { text: 'Note ' + i }), T1);
    await expect(store.areaNotes.save(note('n_' + hex(999)))).rejects.toMatchObject({ key: 'areaNotes.max' });
    expect(await store.areaNotes.rows()).toHaveLength(200);
  });

  it('skips a stored row that is not readable, without truncating the rest', async () => {
    await store.records.save('area', 'a_00000001', { name: '', lat: 1, lon: 1, radiusM: 500 }, T1);
    await store.records.save('area', 'a_00000002', { name: 'Good', lat: 1, lon: 1, radiusM: 50 }, T1);
    await store.records.save('place', 'p_00000001', { name: 'Bad', lat: 999, lon: 1 }, T1);
    await store.records.save('areanote', 'n_00000001', { text: 'no target' }, T1);
    await store.records.save('areanote', 'n_00000002', { areaId: 'a', street: 's', text: 'both' }, T1);
    await store.records.save('areanote', 'n_00000003', { street: 'MG Road', text: 'Ok' }, T1);
    expect((await store.areas.all()).map((a) => [a.id, a.radiusM])).toEqual([['a_00000002', 500]]);
    expect(await store.places.all()).toEqual([]);
    expect((await store.areaNotes.all()).map((n) => n.id)).toEqual(['n_00000003']);
  });
});
