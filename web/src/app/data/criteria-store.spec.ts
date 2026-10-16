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
import { LocalStore } from './local-store.service';
import type { HouseDto } from '../core/models';
import { BUILT_IN_KEYS } from '../shared/scoring';
import type { Criterion } from '../shared/scoring';

const T1 = Date.parse('2026-09-01T00:00:00.000Z');
const T2 = Date.parse('2026-09-02T00:00:00.000Z');
const T3 = Date.parse('2026-09-03T00:00:00.000Z');

const house = (id: string, over: Partial<HouseDto> = {}): HouseDto => ({
  id,
  label: `House ${id}`,
  lat: 13,
  lon: 80,
  status: 'NEW',
  checklist: {},
  deleted: false,
  syncVersion: 0,
  ...over,
});

const own = (n: number, over: Partial<Criterion> = {}): Criterion => ({
  key: `c_${n.toString(16).padStart(8, '0')}`,
  label: `Own ${n}`,
  weight: 2,
  mustHave: false,
  minScore: 3,
  sort: 10 + n,
  ...over,
});

/** Criteria and the rating share (records of type `criterion` and `preference`) through the store's own methods. */
describe('criteria and the rating share (LocalStore.criteria)', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('gives each criterion row its record key, its edit time and the typed criterion, oldest edit first', async () => {
    await store.criteria.save(own(2), T2);
    await store.criteria.save({ key: 'power', weight: 3, mustHave: true, minScore: 4, sort: 1 }, T1);
    expect(await store.criteria.rows()).toEqual([
      { key: 'power', updatedAt: '2026-09-01T00:00:00.000Z', criterion: { key: 'power', weight: 3, mustHave: true, minScore: 4, sort: 1 } },
      { key: own(2).key, updatedAt: '2026-09-02T00:00:00.000Z', criterion: own(2) },
    ]);
  });

  it('does not list a deleted criterion record', async () => {
    await store.criteria.save(own(1), T1);
    await store.criteria.delete(own(1).key, T2);
    expect(await store.criteria.rows()).toEqual([]);
  });

  it('gives each preference row its key, text value and edit time, oldest edit first, and skips a row without a text value', async () => {
    await store.records.save('preference', 'z.other', { value: 'b' }, T2);
    await store.records.save('preference', 'a.first', { value: 'a' }, T1);
    await store.records.save('preference', 'm.number', { value: 3 }, T3);
    expect(await store.criteria.preferenceRows()).toEqual([
      { key: 'a.first', value: 'a', updatedAt: '2026-09-01T00:00:00.000Z' },
      { key: 'z.other', value: 'b', updatedAt: '2026-09-02T00:00:00.000Z' },
    ]);
  });

  it('merges the stored rows into the scoring: a changed built-in, a custom criterion and the rating share', async () => {
    await store.criteria.save({ key: 'power', weight: 0, mustHave: false, minScore: 3, sort: 1 }, T1);
    await store.criteria.save(own(1), T1);
    await store.criteria.setRatingShare(0.75, T1);
    const scoring = await store.criteria.scoring();
    expect(scoring.criteria.find((c) => c.key === 'power')?.weight).toBe(0);
    expect(scoring.criteria.map((c) => c.key)).toContain(own(1).key);
    expect(scoring.criteria).toHaveLength(BUILT_IN_KEYS.length + 1);
    expect(scoring.ratingShare).toBe(0.75);
  });

  it('saves a list one criterion at a time: a changed one is written, a built-in back at its default deletes its record', async () => {
    await store.criteria.save({ key: 'water', weight: 3, mustHave: false, minScore: 3, sort: 0 }, T1);
    await store.criteria.saveMany(
      [{ key: 'water', weight: 2, mustHave: false, minScore: 3, sort: 0 }, { key: 'power', weight: 1, mustHave: false, minScore: 3, sort: 1 }, own(1)],
      T2,
    );
    expect((await store.records.ofType('criterion')).map((r) => r.id).sort()).toEqual([own(1).key, 'power']);
    expect((await store.records.get('criterion', 'power'))?.updatedAt).toBe('2026-09-02T00:00:00.000Z');
  });

  it('trims the label of a custom criterion on a save, and stamps the edit time', async () => {
    await store.criteria.save(own(1, { label: '  Pets  ' }), T2);
    const stored = await store.records.get('criterion', own(1).key);
    expect(stored?.payload['label']).toBe('Pets');
    expect(stored?.updatedAt).toBe('2026-09-02T00:00:00.000Z');
  });

  it('refuses a custom criterion with no label, or one of 61 characters, and takes one of 60', async () => {
    await expect(store.criteria.save(own(1, { label: undefined }))).rejects.toMatchObject({ key: 'error.badRecord' });
    await expect(store.criteria.save(own(1, { label: '   ' }))).rejects.toMatchObject({ key: 'error.badRecord' });
    await expect(store.criteria.save(own(1, { label: 'x'.repeat(61) }))).rejects.toMatchObject({ key: 'error.badRecord' });
    await expect(store.criteria.save(own(1, { label: 'x'.repeat(60) }))).resolves.toBeUndefined();
  });

  it('accepts only the ten built-in keys and keys of c_ and 8 lowercase hex characters', async () => {
    for (const key of ['c_1234567', 'c_123456789', 'c_1234567G', 'c_1234567A', 'other', 'C_12345678']) {
      await expect(store.criteria.save(own(1, { key }))).rejects.toMatchObject({ key: 'error.badRecord' });
    }
    await expect(store.criteria.save(own(1, { key: 'c_1234abcd' }))).resolves.toBeUndefined();
  });

  it('counts the ten built-ins and the live custom records toward the cap of 40, an archived custom one included', async () => {
    for (let i = 0; i < 29; i++) await store.criteria.save(own(i), T1);
    await store.criteria.save({ key: 'water', weight: 3, mustHave: false, minScore: 3, sort: 0 }, T1);
    await store.criteria.save(own(29, { archived: true }), T1);
    await expect(store.criteria.save(own(30))).rejects.toMatchObject({ key: 'criteria.max' });
    await expect(store.criteria.save(own(29, { label: 'Edited', archived: true }))).resolves.toBeUndefined();
    await store.criteria.delete(own(0).key, T2);
    await expect(store.criteria.save(own(30))).resolves.toBeUndefined();
  });

  it('adds with weight Medium unless one is given, not a must-have, a minimum score of 3, and a trimmed label', async () => {
    const plain = await store.criteria.add('  Lift  ', undefined, T1);
    expect(plain).toMatchObject({ label: 'Lift', weight: 2, mustHave: false, minScore: 3 });
    const heavy = await store.criteria.add('Park', 3, T1);
    expect(heavy.weight).toBe(3);
  });

  it('numbers an added criterion after the highest sort number, an archived one included', async () => {
    expect((await store.criteria.add('First', 2, T1)).sort).toBe(10);
    await store.criteria.save(own(5, { sort: 40, archived: true }), T1);
    expect((await store.criteria.add('Next', 2, T1)).sort).toBe(41);
  });

  it('refuses the 41st criterion before it draws a key', async () => {
    for (let i = 0; i < 30; i++) await store.criteria.save(own(i), T1);
    let drawn = 0;
    await expect(store.criteria.add('Too many', 2, T1, () => `c_${(drawn++).toString(16).padStart(8, 'f')}`)).rejects.toMatchObject({ key: 'criteria.max' });
    expect(drawn).toBe(0);
  });

  it('gives up with a bad-record error when every drawn key clashes with a stored one', async () => {
    await store.criteria.save(own(1), T1);
    await expect(store.criteria.add('Again', 2, T2, () => own(1).key)).rejects.toMatchObject({ key: 'error.badRecord' });
  });

  it('adds a criterion that is stored dirty with the edit time it was given', async () => {
    const added = await store.criteria.add('Pets', 2, T2);
    const stored = await store.records.get('criterion', added.key);
    expect(stored).toMatchObject({ dirty: true, updatedAt: '2026-09-02T00:00:00.000Z' });
  });

  it('deletes a custom criterion as a tombstone with the edit time it was given, and bumps the revision', async () => {
    await store.criteria.save(own(1), T1);
    const start = store.revision();
    await store.criteria.delete(own(1).key, T3);
    expect(store.revision()).toBeGreaterThan(start);
    const stored = (await store.records.dirty()).find((r) => r.id === own(1).key);
    expect(stored).toMatchObject({ deleted: true, updatedAt: '2026-09-03T00:00:00.000Z' });
  });

  it('refuses to delete a custom criterion a house still has a score under, and deletes it once the house is gone', async () => {
    const added = await store.criteria.add('Pets', 2, T1);
    await store.saveHouse(house('h1', { checklist: { [added.key]: 4 } }), T1);
    await expect(store.criteria.delete(added.key, T2)).rejects.toMatchObject({ key: 'criteria.inUse' });
    expect(await store.records.get('criterion', added.key)).toBeDefined();
    await store.deleteHouse('h1', T2);
    await store.criteria.delete(added.key, T3);
    expect(await store.records.get('criterion', added.key)).toBeUndefined();
  });

  it('refuses to delete a key that is not a custom key, even when the built-in has a record', async () => {
    await store.criteria.save({ key: 'water', weight: 3, mustHave: false, minScore: 3, sort: 0 }, T1);
    await expect(store.criteria.delete('water')).rejects.toMatchObject({ key: 'error.badRecord' });
    expect(await store.records.get('criterion', 'water')).toBeDefined();
  });

  it('writes nothing when a custom criterion with no record is deleted', async () => {
    const start = store.revision();
    await store.criteria.delete('c_00000009', T1);
    expect(store.revision()).toBe(start);
  });

  it('counts a criterion as in use for a score of 0 as well, and not for a deleted house or another key', async () => {
    await store.saveHouse(house('h1', { checklist: { c_00000001: 0, c_00000002: 4 } }), T1);
    await store.saveHouse(house('h2', { checklist: { c_00000003: 5 } }), T1);
    await store.deleteHouse('h2', T2);
    expect(await store.criteria.inUse('c_00000001')).toBe(true);
    expect(await store.criteria.inUse('c_00000002')).toBe(true);
    expect(await store.criteria.inUse('c_00000003')).toBe(false);
    expect(await store.criteria.inUse('c_00000004')).toBe(false);
  });

  it('keeps a rating share of 0 and of 1 as records, rounds it to two places, and clamps what is outside', async () => {
    await store.criteria.setRatingShare(0, T1);
    expect((await store.records.get('preference', 'score.ratingShare'))?.payload).toEqual({ value: '0' });
    await store.criteria.setRatingShare(-3, T1);
    expect((await store.records.get('preference', 'score.ratingShare'))?.payload).toEqual({ value: '0' });
    await store.criteria.setRatingShare(7, T1);
    expect((await store.records.get('preference', 'score.ratingShare'))?.payload).toEqual({ value: '1' });
    await store.criteria.setRatingShare(1, T1);
    expect((await store.records.get('preference', 'score.ratingShare'))?.payload).toEqual({ value: '1' });
    await store.criteria.setRatingShare(0.333, T2);
    const stored = await store.records.get('preference', 'score.ratingShare');
    expect(stored).toMatchObject({ payload: { value: '0.33' }, dirty: true, updatedAt: '2026-09-02T00:00:00.000Z' });
  });

  it('deletes the rating share record when the value rounds to the default', async () => {
    await store.criteria.setRatingShare(0.25, T1);
    await store.criteria.setRatingShare(0.499, T3);
    expect(await store.records.get('preference', 'score.ratingShare')).toBeUndefined();
    const tombstone = (await store.records.dirty()).find((r) => r.id === 'score.ratingShare');
    expect(tombstone).toMatchObject({ deleted: true, updatedAt: '2026-09-03T00:00:00.000Z' });
  });

  it('resets every criterion and preference record at the given time, and leaves other records alone', async () => {
    await store.criteria.save(own(1), T1);
    await store.criteria.setRatingShare(0.75, T1);
    await store.records.save('preference', 'other.pref', { value: 'x' }, T1);
    await store.records.save('area', 'a1', { name: 'Adyar' }, T1);
    const start = store.revision();
    await store.criteria.reset(T3);
    expect(store.revision()).toBeGreaterThan(start);
    expect(await store.criteria.rows()).toEqual([]);
    expect(await store.criteria.preferenceRows()).toEqual([]);
    expect(await store.records.get('area', 'a1')).toBeDefined();
    const tombstones = (await store.records.dirty()).filter((r) => r.deleted);
    expect(tombstones.map((r) => r.updatedAt)).toEqual(['2026-09-03T00:00:00.000Z', '2026-09-03T00:00:00.000Z', '2026-09-03T00:00:00.000Z']);
  });
});
