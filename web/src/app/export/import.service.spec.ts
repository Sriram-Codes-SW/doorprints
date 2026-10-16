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

import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import vectorsJson from '../../../../docs/schemas/import-vectors.json';
import { LocalStore } from '../data/local-store.service';
import { millis } from '../data/records';
import { openBackup } from './backup-reader';
import type { BackupArchive } from './backup-reader';
import { ImportService } from './import.service';

const archives = (vectorsJson as unknown as { archives: { name: string; base64: string }[] }).archives;

async function archive(name: string): Promise<BackupArchive> {
  const a = archives.find((x) => x.name === name)!;
  const opened = await openBackup(new Blob([Uint8Array.from(atob(a.base64), (c) => c.charCodeAt(0)) as BlobPart]));
  if (!opened.ok) throw new Error(opened.problem);
  return opened.archive;
}

/** The vectors' times are 2026-05-28 (1780000000000); "now" here is a day later. */
const NOW = 1_780_086_400_000;

/**
 * *Import a backup* writing into this browser (S4b-BL-75): a merge lands the house and its verified photo and is a no-op
 * the second time; an update file deletes an older house (S4b-BL-82) and a restore of the same rows does not; a copy
 * gets new ids and can be undone; a house deleted here comes back only when asked, stamped past its tombstone.
 */
describe('ImportService', () => {
  let store: LocalStore;
  let importer: ImportService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(LocalStore);
    importer = TestBed.inject(ImportService);
  });

  it('merges a backup, photo included, and the same file again changes nothing', async () => {
    const backup = await archive('a deflated backup');
    const first = await importer.preview(backup, { mode: 'MERGE' });
    expect([first.newHouses, first.newPhotos]).toEqual([1, 1]);
    const done = await importer.apply(backup, { mode: 'MERGE' }, NOW);
    expect([done.houses, done.photos, done.photosSkipped, done.undo]).toEqual([1, 1, 0, null]);
    const house = await store.getHouse('h1');
    expect(house?.label).toBe('House h1');
    expect(house?.dirty).toBe(true);
    const photo = await store.photos.get('p1');
    expect(photo?.sizeBytes).toBe(300);
    expect(photo?.uploaded).toBe(false);
    expect((await importer.preview(backup, { mode: 'MERGE' })).isEmpty).toBe(true);
  });

  it('an update file deletes a house that is older here, and only an update does', async () => {
    await store.saveHouse({ id: 'h9', label: 'Gone on the other phone', lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0 }, 1_779_000_000_000);
    const update = await archive('an update file says who it is for');
    expect(update.manifest?.sharedTo).toBe('Priya');
    const before = await importer.preview(update, { mode: 'MERGE' });
    expect([before.newHouses, before.removedHouses]).toEqual([1, 1]);
    expect((await importer.preview(update, { mode: 'MERGE', skipUpdates: true })).removedHouses).toBe(0);
    const done = await importer.apply(update, { mode: 'MERGE' }, NOW);
    expect(done.removedHouses).toBe(1);
    expect(await store.getHouse('h9')).toBeUndefined();
    expect((await store.getHouseRow('h9'))?.deleted).toBe(true);
    expect(await store.getHouse('h1')).toBeDefined();
  });

  it('a copy gets new ids and its undo removes what nobody changed', async () => {
    const backup = await archive('a stored backup');
    await importer.apply(backup, { mode: 'MERGE' }, NOW);
    const copy = await importer.apply(backup, { mode: 'COPY' }, NOW);
    expect(copy.houses).toBe(1);
    expect(copy.undo?.houses.size).toBe(1);
    const [copyId] = [...copy.undo!.houses.keys()];
    expect(copyId).not.toBe('h1');
    expect((await store.liveHouses()).length).toBe(2);
    expect((await store.photos.ofHouse(copyId)).length).toBe(1);
    const undone = await importer.undoCopy(copy.undo!, NOW + 1000);
    expect(undone).toEqual({ removed: 1, kept: 0 });
    expect((await store.liveHouses()).map((h) => h.id)).toEqual(['h1']);
    expect(await store.photos.ofHouse(copyId)).toEqual([]);
  });

  it('a house deleted here stays deleted unless brought back, then it is stamped past the tombstone', async () => {
    const backup = await archive('a stored backup');
    await importer.apply(backup, { mode: 'MERGE' }, NOW);
    await store.deleteHouse('h1', NOW + 5000);
    const kept = await importer.preview(backup, { mode: 'MERGE' });
    expect([kept.deletedHereHouses, kept.isEmpty]).toEqual([1, true]);
    const back = await importer.apply(backup, { mode: 'MERGE', restoreDeleted: true }, NOW + 1000);
    expect(back.restoredHouses).toBe(1);
    const house = await store.getHouseRow('h1');
    expect(house?.deleted).toBe(false);
    expect(millis(house?.updatedAt)).toBe(NOW + 5001);
  });
});
