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
import { LocalStore } from '../../local-store.service';
import type { HouseDto, VisitDto } from '../../../../core/models';
import { LocalRowsAdapter } from './local-rows';

describe('LocalRowsAdapter', () => {
  let store: LocalStore;
  let adapter: LocalRowsAdapter;

  beforeEach(async () => {
    // Create a real in-memory LocalStore instance (jsdom has no IndexedDB, so it uses memory).
    store = new LocalStore();
    await store.ready();
    adapter = new LocalRowsAdapter(store);
  });

  afterEach(async () => {
    // Clean up.
    await store.removeAll();
  });

  it('all() returns houses, visits, and photos as SyncRows', async () => {
    // Add a house.
    const house: HouseDto = {
      id: 'h1',
      label: 'Test House',
      lat: 12.34,
      lon: 56.78,
      status: 'NEW',
      checklist: {},
      deleted: false,
      syncVersion: 1,
    };
    await store.putHouseFromServer(house);

    // Add a visit.
    const visit: VisitDto = {
      id: 'v1',
      houseId: 'h1',
      lat: 12.34,
      lon: 56.78,
      arrivedAt: '2026-10-02T14:30:00Z',
      source: 'MANUAL',
      deleted: false,
      syncVersion: 1,
    };
    await store.putVisitFromServer(visit);

    // Call all().
    const rows = await adapter.all();

    // Should include house and visit.
    expect(rows.length).toBeGreaterThanOrEqual(2);
    const houseRow = rows.find((r) => r.kind === 'houses' && r.key === 'h1');
    expect(houseRow).toBeDefined();
    expect(houseRow?.stamp.deleted).toBe(false);

    const visitRow = rows.find((r) => r.kind === 'visits' && r.key === 'v1');
    expect(visitRow).toBeDefined();
    expect(visitRow?.stamp.deleted).toBe(false);
  });

  it('preserves deleted tombstones in all()', async () => {
    const deletedHouse: HouseDto = {
      id: 'h-deleted',
      label: 'Deleted House',
      lat: 0,
      lon: 0,
      status: 'NEW',
      checklist: {},
      deleted: true, // Tombstone
      syncVersion: 2,
    };
    await store.putHouseFromServer(deletedHouse);

    const rows = await adapter.all();

    const deletedRow = rows.find((r) => r.kind === 'houses' && r.key === 'h-deleted');
    expect(deletedRow).toBeDefined();
    expect(deletedRow?.stamp.deleted).toBe(true);
  });

  it('markClean() marks rows as clean after sync push', async () => {
    const house: HouseDto = {
      id: 'h1',
      label: 'Clean House',
      lat: 13,
      lon: 80,
      status: 'NEW',
      checklist: {},
      deleted: false,
      syncVersion: 1,
    };
    await store.putHouseFromServer(house);

    const rows = await adapter.all();
    const houseRow = rows.find((r) => r.kind === 'houses' && r.key === 'h1')!;

    // Mark clean.
    await adapter.markClean([houseRow]);

    // Verify the house is marked clean (dirty flag should be false).
    const cleaned = await store.allHouses();
    const h = cleaned. find((house: HouseRecord) => house.id === 'h1');
    expect(h?.dirty).toBe(false);
  });

  it('applyRemote() merges remote rows via putImported', async () => {
    const remoteHouseRow = {
      kind: 'houses' as const,
      key: 'h-remote',
      stamp: { updatedAt: new Date('2026-10-02T10:00:00Z').getTime(), by: 'device-2', deleted: false },
      json: {
        id: 'h-remote',
        updatedAt: '2026-10-02T10:00:00Z',
        name: 'Remote House',
        label: 'Remote',
        lat: 13,
        lon: 80,
        status: 'NEW',
      },
    };

    await adapter.applyRemote([remoteHouseRow]);

    // Verify the house was imported.
    const houses = await store.allHouses();
    const imported = houses. find((h: HouseRecord) => h.id === 'h-remote');
    expect(imported).toBeDefined();
  });

  it('photo() returns a photo by id', async () => {
    // Add a house first (photo needs a house).
    const house: HouseDto = {
      id: 'h1',
      label: 'Photo House',
      lat: 13,
      lon: 80,
      status: 'NEW',
      checklist: {},
      deleted: false,
      syncVersion: 1,
    };
    await store.putHouseFromServer(house);

    // Add a photo.
    const result = await store.addPhoto('h1', new Blob(['fake image'], { type: 'image/jpeg' }), {});
    if (!result.ok) throw new Error('Failed to add photo');

    // Retrieve it.
    const dto = await adapter.photo(result.id);
    expect(dto).toBeDefined();
    expect(dto?.id).toBe(result.id);
    expect(dto?.houseId).toBe('h1');
  });

  it('photo() returns null for unknown photo id', async () => {
    const dto = await adapter.photo('unknown-photo');
    expect(dto).toBeNull();
  });

  it('round-trip: dirty -> clean after engine confirms', async () => {
    // Add a house.
    const house: HouseDto = {
      id: 'h-dirty',
      label: 'Dirty House',
      lat: 13,
      lon: 80,
      status: 'NEW',
      checklist: {},
      deleted: false,
      syncVersion: 1,
    };
    await store.putHouseFromServer(house);

    // Get all rows.
    let rows = await adapter.all();
    const dirtyRow = rows.find((r) => r.kind === 'houses' && r.key === 'h-dirty')!;

    // Simulate engine confirming the sync: mark clean.
    await adapter.markClean([dirtyRow]);

    // Verify clean.
    const cleaned = await store.allHouses();
    const h = cleaned. find((house: HouseRecord) => house.id === 'h-dirty');
    expect(h?.dirty).toBe(false);
  });
});
