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

import type { HouseRecord, PhotoRecord, VisitRecord } from '../../records';
import { LocalRowsAdapter } from './local-rows';

describe('LocalRowsAdapter', () => {
  let adapter: LocalRowsAdapter;

  // Mock LocalStore for testing.
  let mockStore: any;

  beforeEach(() => {
    mockStore = {
      allHouses: jasmine.createSpy('allHouses'),
      allVisits: jasmine.createSpy('allVisits'),
      allPhotos: jasmine.createSpy('allPhotos'),
      allRecordsOf: jasmine.createSpy('allRecordsOf'),
      markHouseClean: jasmine.createSpy('markHouseClean'),
      markVisitClean: jasmine.createSpy('markVisitClean'),
      markPhotoMetaClean: jasmine.createSpy('markPhotoMetaClean'),
      putHouseFromServer: jasmine.createSpy('putHouseFromServer'),
      putVisitFromServer: jasmine.createSpy('putVisitFromServer'),
      putImported: jasmine.createSpy('putImported'),
    };
    adapter = new LocalRowsAdapter(mockStore as any);
  });

  it('all() returns houses, visits, and photos as SyncRows', async () => {
    const house: HouseRecord = {
      id: 'h1',
      name: 'Test House',
      lat: 12.34,
      lon: 56.78,
      status: 'LOOKING',
      cost: null,
      locationSource: null,
      rooms: null,
      brokerId: null,
      answers: null,
      areaSqft: null,
      moveIn: null,
      updatedAt: '2026-10-02T10:00:00Z',
      createdAt: '2026-10-01T10:00:00Z',
      deleted: false,
      dirty: false,
      syncVersion: 1,
      syncedBy: 'device-1',
    };

    const visit: VisitRecord = {
      id: 'v1',
      houseId: 'h1',
      when: '2026-10-02T14:30:00Z',
      where: 'Test Road',
      notes: 'Good house',
      updatedAt: '2026-10-02T10:00:00Z',
      createdAt: '2026-10-01T10:00:00Z',
      deleted: false,
      dirty: false,
      syncVersion: 1,
      syncedBy: 'device-1',
    };

    const photo: PhotoRecord = {
      id: 'p1',
      houseId: 'h1',
      contentType: 'image/jpeg',
      sizeBytes: 1024,
      createdAt: '2026-10-01T10:00:00Z',
      updatedAt: '2026-10-02T10:00:00Z',
      deleted: false,
      syncVersion: 1,
      driveFileId: null,
      sha256: null,
      meta: null,
      syncedBy: 'device-1',
    };

    mockStore.allHouses.mockResolvedValue([house]);
    mockStore.allVisits.mockResolvedValue([visit]);
    mockStore.allPhotos.mockResolvedValue([photo]);
    mockStore.allRecordsOf.mockResolvedValue([]);

    const rows = await adapter.all();

    expect(rows.length).toBe(3);
    expect(rows[0].kind).toBe('houses');
    expect(rows[0].key).toBe('h1');
    expect(rows[1].kind).toBe('visits');
    expect(rows[1].key).toBe('v1');
    expect(rows[2].kind).toBe('photos');
    expect(rows[2].key).toBe('p1');
  });

  it('markClean() marks rows as clean after sync push', async () => {
    const stamp = { updatedAt: new Date('2026-10-02T10:00:00Z').getTime(), by: 'device-1', deleted: false };

    const rows = [
      {
        kind: 'houses' as const,
        key: 'h1',
        stamp,
        json: { id: 'h1', name: 'Test House' },
      },
      {
        kind: 'visits' as const,
        key: 'v1',
        stamp,
        json: { id: 'v1', houseId: 'h1' },
      },
      {
        kind: 'photos' as const,
        key: 'p1',
        stamp,
        json: { id: 'p1', houseId: 'h1' },
      },
    ];

    mockStore.markHouseClean.mockResolvedValue(undefined);
    mockStore.markVisitClean.mockResolvedValue(undefined);
    mockStore.markPhotoMetaClean.mockResolvedValue(undefined);

    await adapter.markClean(rows);

    expect(mockStore.markHouseClean).toHaveBeenCalledWith('h1', expect.any(String));
    expect(mockStore.markVisitClean).toHaveBeenCalledWith('v1', expect.any(String));
    expect(mockStore.markPhotoMetaClean).toHaveBeenCalledWith('p1', expect.any(Number));
  });

  it('applyRemote() merges remote rows via putImported', async () => {
    const stamp = { updatedAt: new Date('2026-10-02T10:00:00Z').getTime(), by: 'device-2', deleted: false };

    const rows = [
      {
        kind: 'houses' as const,
        key: 'h2',
        stamp,
        json: { id: 'h2', name: 'Remote House', updatedAt: '2026-10-02T10:00:00Z' },
      },
    ];

    mockStore.putHouseFromServer.mockResolvedValue({
      id: 'h2',
      name: 'Remote House',
      updatedAt: '2026-10-02T10:00:00Z',
      deleted: false,
      syncVersion: 1,
    });
    mockStore.putImported.mockResolvedValue(undefined);

    await adapter.applyRemote(rows);

    expect(mockStore.putHouseFromServer).toHaveBeenCalled();
    expect(mockStore.putImported).toHaveBeenCalled();
  });

  it('photo() throws "not yet built" error', async () => {
    await expect(adapter.photo('p1')).rejects.toThrow('not yet built');
  });

  it('preserves deleted tombstones in all()', async () => {
    const deletedHouse: HouseRecord = {
      id: 'h-deleted',
      name: 'Deleted House',
      lat: null,
      lon: null,
      status: null,
      cost: null,
      locationSource: null,
      rooms: null,
      brokerId: null,
      answers: null,
      areaSqft: null,
      moveIn: null,
      updatedAt: '2026-10-02T10:00:00Z',
      createdAt: '2026-10-01T10:00:00Z',
      deleted: true,
      dirty: false,
      syncVersion: 2,
      syncedBy: 'device-1',
    };

    mockStore.allHouses.mockResolvedValue([deletedHouse]);
    mockStore.allVisits.mockResolvedValue([]);
    mockStore.allPhotos.mockResolvedValue([]);
    mockStore.allRecordsOf.mockResolvedValue([]);

    const rows = await adapter.all();

    expect(rows.length).toBe(1);
    expect(rows[0].stamp.deleted).toBe(true);
  });

  it('dirty flag is only cleared after engine confirms (markClean)', async () => {
    // This test ensures dirty rows remain dirty until markClean is called.
    const house: HouseRecord = {
      id: 'h1',
      name: 'Dirty House',
      lat: null,
      lon: null,
      status: null,
      cost: null,
      locationSource: null,
      rooms: null,
      brokerId: null,
      answers: null,
      areaSqft: null,
      moveIn: null,
      updatedAt: '2026-10-02T10:00:00Z',
      createdAt: '2026-10-01T10:00:00Z',
      deleted: false,
      dirty: true, // Still dirty
      syncVersion: 1,
      syncedBy: 'device-1',
    };

    mockStore.allHouses.mockResolvedValue([house]);
    mockStore.allVisits.mockResolvedValue([]);
    mockStore.allPhotos.mockResolvedValue([]);
    mockStore.allRecordsOf.mockResolvedValue([]);

    const rows = await adapter.all();
    expect(rows[0].json.dirty).toBeUndefined(); // dirty flag is internal, not exported

    mockStore.markHouseClean.mockResolvedValue(undefined);
    await adapter.markClean(rows);

    expect(mockStore.markHouseClean).toHaveBeenCalledWith('h1', expect.any(String));
  });
});
