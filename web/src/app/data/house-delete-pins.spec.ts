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

/**
 * Pins of what deleting ONE candidate does in this browser's store today (deletion safeguards PR 1, docs/11 5.27.6).
 * They were written before the Undo snackbar and the Recently deleted list, and they stay true after: the new code
 * reads the same tombstones and never changes what `LocalStore.deleteHouse` writes. The page's side (the question it
 * asked, the announcement) is pinned in `pages/house-detail/house-delete-undo.spec.ts`.
 */
const T1 = Date.parse('2026-09-01T10:00:00.000Z');
const T2 = Date.parse('2026-09-02T10:00:00.000Z');

function house(id: string, over: Partial<HouseDto> = {}): HouseDto {
  return { id, label: `House ${id}`, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, ...over };
}

const jpeg = (): Blob => new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' });

describe('what deleting one candidate writes today', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('turns the row into a dirty tombstone with a newer updatedAt and keeps every other field', async () => {
    const saved = await store.saveHouse(house('h1', { street: 'MG Road', kind: 'school' }), T1);
    await store.deleteHouse('h1', T2);
    const [row] = await store.allHouses();
    expect(row).toEqual({ ...saved, deleted: true, dirty: true, updatedAt: new Date(T2).toISOString() });
  });

  it('leaves every other house as it was', async () => {
    await store.saveHouse(house('h1'), T1);
    const other = await store.saveHouse(house('h2', { kind: 'school' }), T1);
    await store.deleteHouse('h1', T2);
    expect(await store.getHouse('h2')).toEqual(other);
  });

  it('drops the bytes of a photo the server has and keeps a tombstone; one that never left is removed', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.photos.add('h1', jpeg(), 'sent', T1);
    await store.photos.add('h1', jpeg(), 'local', T1);
    await store.photos.put({ ...(await store.photos.get('sent'))!, uploaded: true });
    await store.deleteHouse('h1', T2);
    expect(await store.photos.get('local')).toBeUndefined();
    const sent = (await store.photos.get('sent'))!;
    expect(sent).toMatchObject({ deleted: true, blob: null, uploaded: true });
    expect(await store.photos.ofHouse('h1')).toEqual([]);
  });

  it('deletes the saved walks of the house and no one else\'s: a walk has no tombstone', async () => {
    await store.saveHouse(house('h1'), T1);
    const db = await store.database();
    const walk = (id: string, houseId: string) => ({ id, houseId, startedAt: 1, endedAt: 2, savedAt: 3, pointCount: 0, lengthM: 0, points: [] });
    await db.put('saved_walks', walk('w1', 'h1'));
    await db.put('saved_walks', walk('w2', 'h2'));
    await store.deleteHouse('h1', T2);
    expect(await db.get('saved_walks', 'w1')).toBeUndefined();
    expect(await db.get('saved_walks', 'w2')).toBeDefined();
  });

  it('does nothing for an unknown id', async () => {
    await store.deleteHouse('nope', T2);
    expect(await store.allHouses()).toEqual([]);
  });
});
