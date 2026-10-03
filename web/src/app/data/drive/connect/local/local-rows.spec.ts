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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LocalStore } from '../../../local-store.service';
import type { HouseDto, VisitDto } from '../../../../core/models';
import type { HouseRecord } from '../../../records';
import { LocalRowsAdapter } from './local-rows';

describe('LocalRowsAdapter', () => {
  let store: LocalStore;
  let adapter: LocalRowsAdapter;
  const deviceId = 'device-123abc';

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
    adapter = new LocalRowsAdapter(store, deviceId);
  });

  afterEach(async () => {
    await store.clearEverything();
  });

  it('all() returns houses and visits with correct device id', async () => {
    const house: HouseDto = { id: 'h1', label: 'Test House', lat: 12.34, lon: 56.78, status: 'NEW', checklist: {}, deleted: false, syncVersion: 1 };
    await store.putHouseFromServer(house);
    const visit: VisitDto = { id: 'v1', houseId: 'h1', lat: 12.34, lon: 56.78, arrivedAt: '2026-10-02T14:30:00Z', source: 'MANUAL', deleted: false, syncVersion: 1 };
    await store.putVisitFromServer(visit);

    const rows = await adapter.all();
    const houseRow = rows.find((r) => r.kind === 'houses' && r.key === 'h1');
    expect(houseRow?.stamp.by).toBe(deviceId);
    const visitRow = rows.find((r) => r.kind === 'visits' && r.key === 'v1');
    expect(visitRow?.stamp.by).toBe(deviceId);
  });

  it('different device ids produce different by values', async () => {
    const adapter2 = new LocalRowsAdapter(store, 'device-456def');
    const house: HouseDto = { id: 'h1', label: 'Test', lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 1 };
    await store.putHouseFromServer(house);

    const rows1 = await adapter.all();
    const rows2 = await adapter2.all();
    const row1 = rows1.find((r) => r.kind === 'houses')!;
    const row2 = rows2.find((r) => r.kind === 'houses')!;
    expect(row1.stamp.by).toBe(deviceId);
    expect(row2.stamp.by).toBe('device-456def');
  });

  it('applyRemote does not mark rows dirty', async () => {
    const remoteRow = {
      kind: 'houses' as const,
      key: 'h-remote',
      stamp: { updatedAt: new Date('2026-10-02T10:00:00Z').getTime(), by: 'device-remote', deleted: false },
      json: { id: 'h-remote', updatedAt: '2026-10-02T10:00:00Z', label: 'Remote', lat: 13, lon: 80, status: 'NEW', checklist: {} },
    };
    await adapter.applyRemote([remoteRow]);
    const houses = await store.allHouses();
    const imported = houses.find((h) => h.id === 'h-remote');
    expect(imported?.dirty).toBe(false);
  });

  it('markSynced() marks rows clean', async () => {
    const house: HouseDto = { id: 'h1', label: 'Clean', lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 1 };
    await store.putHouseFromServer(house);
    const rows = await adapter.all();
    const houseRow = rows.find((r) => r.kind === 'houses')!;
    await adapter.markSynced([houseRow]);
    const cleaned = await store.allHouses();
    const h = cleaned.find((house: HouseRecord) => house.id === 'h1');
    expect(h?.dirty).toBe(false);
  });

  it('changedRows reads dirty ids only, and photo() is a keyed get', async () => {
    const house = (id: string, label: string): HouseDto => ({
      id, label, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 1,
    });
    await store.saveHouse(house('h1', 'Dirty'), Date.now());
    await store.putHouseFromServer(house('h2', 'Clean'));
    const allHouses = vi.spyOn(store, 'allHouses');
    const dirtyHouses = vi.spyOn(store, 'dirtyHouses');
    const allPhotos = vi.spyOn(store, 'allPhotos');
    const getPhoto = vi.spyOn(store, 'getPhoto');
    const changed = await adapter.changedRows();
    expect(changed.map((r) => r.key)).toEqual(['h1']);
    expect(dirtyHouses).toHaveBeenCalled();
    expect(allHouses).not.toHaveBeenCalled();
    await adapter.photo('missing-photo');
    expect(getPhoto).toHaveBeenCalledWith('missing-photo');
    expect(allPhotos).not.toHaveBeenCalled();
  });
});
