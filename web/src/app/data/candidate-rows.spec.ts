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
import { houseOfRow, upgradeHouseRow, withCandidateRows } from './candidate-rows';
import { MemoryDb } from './local-db';
import { houseFromDto } from './records';
import type { HouseRecord } from './records';
import { newHouse } from '../core/models';

/**
 * The `houses` store holds candidates since IndexedDB version 4 (kinds step 3, S4b-BL-206): the wrapper writes a
 * candidate for every house put in any way (put, putAll, batch) and gives a house back from every read, so nothing
 * above the store changes. docs/06 TC-U-200.
 */

/** A Kochi rent with a floor and a deposit, as the screens hold it. */
function kochi(dirty = true): HouseRecord {
  return houseFromDto({ ...newHouse(9.9312, 76.2673, 'MAP'), id: 'k1', label: 'Kakkanad 1BHK', price: 15000, floor: 2, cost: { deposit: 45000 }, createdAt: '2026-09-03T08:00:00.000Z' }, dirty);
}

describe('withCandidateRows', () => {
  it('stores a house as a candidate (kind, money, extras, no nulls) and reads the same house back', async () => {
    const raw = new MemoryDb();
    const db = withCandidateRows(raw);
    await db.put('houses', kochi());
    expect(await raw.get('houses', 'k1')).toEqual({
      id: 'k1', kind: 'house', label: 'Kakkanad 1BHK', lat: 9.9312, lon: 76.2673, status: 'NEW', money: { amount: 15000, cadence: 'month' },
      locationSource: 'MAP', extras: { floor: 2, deposit: { amount: 45000, cadence: 'once' } }, checklist: {}, createdAt: '2026-09-03T08:00:00.000Z',
      deleted: false, syncVersion: 0, dirty: true,
    });
    expect(await db.get('houses', 'k1')).toEqual(kochi());
  });

  it('converts in every write path and every read path, and leaves the other stores alone', async () => {
    const raw = new MemoryDb();
    const db = withCandidateRows(raw);
    await db.putAll('houses', [kochi(false)]);
    await db.batch([{ op: 'put', store: 'houses', value: { ...kochi(), id: 'k2' } }, { op: 'put', store: 'visits', value: { id: 'v1', houseId: 'k1' } }]);
    for (const row of await raw.getAll<Record<string, unknown>>('houses')) expect(row['kind']).toBe('house');
    expect(await raw.get('visits', 'v1')).toEqual({ id: 'v1', houseId: 'k1' });
    expect((await db.getAll<HouseRecord>('houses')).map((h) => [h.id, h.price, h.floor, h.dirty])).toEqual([['k1', 15000, 2, false], ['k2', 15000, 2, true]]);
    expect((await db.getMany<HouseRecord>('houses', ['k2'])).map((h) => h.cost)).toEqual([{ deposit: 45000 }]);
    expect(await db.get('houses', 'missing')).toBeUndefined();
  });

  it('stores a row that is a candidate already as it is, and reads a row a version-3 database left as the house it is', async () => {
    const raw = new MemoryDb();
    const db = withCandidateRows(raw);
    const candidate = { id: 'c1', kind: 'house', label: 'x', lat: 0, lon: 0, status: 'NEW', money: { amount: 9, cadence: 'once' }, checklist: {}, deleted: false, syncVersion: 0, dirty: false };
    await db.put('houses', candidate);
    expect(await raw.get('houses', 'c1')).toEqual(candidate);
    const legacy = kochi();
    await raw.put('houses', legacy);
    expect(houseOfRow(await raw.get('houses', 'k1'))).toEqual(legacy);
    expect(upgradeHouseRow(candidate)).toBe(candidate);
  });
});
