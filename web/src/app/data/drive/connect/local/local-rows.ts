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

import type { HouseDto, PhotoChangeDto, RecordDto, VisitDto } from '../../../../core/models';
import { AREA_NOTE_TYPE, AREA_TYPE, PLACE_TYPE } from '../../../../shared/area';
import { BROKER_TYPE } from '../../../../shared/broker';
import { CRITERION_TYPE, PREFERENCE_TYPE } from '../../../../shared/scoring';
import { QUESTION_TYPE } from '../../../../shared/question';
import { VIEWING_TYPE } from '../../../../shared/viewing';
import type { LocalStore } from '../../../local-store.service';
import type { HouseRecord, PhotoRecord, RecordRecord, VisitRecord } from '../../../records';
import { houseFromDto, recordFromDto, visitFromDto } from '../../../records';
import type { LocalRows } from '../../drive-sync-seams';
import type { SyncKind, SyncRow } from '../../sync-file';
import { SYNC_EARLIEST_MS, syncRow } from '../../sync-file';

/**
 * Presents the local store's houses, visits, records and photo metadata as sync rows and applies merged remote rows back, so the sync engine does not know about IndexedDB.
 * Local-only fields (`dirty`) never leave the device.
 */
export class LocalRowsAdapter implements LocalRows {
  constructor(private readonly store: LocalStore, private readonly deviceId: string) {}

  /** Every local row of every kind, deleted ones included (their tombstones must sync). */
  async all(): Promise<readonly SyncRow[]> {
    const rows: SyncRow[] = [];
    const houses = await this.store.allHouses();
    for (const house of houses) {
      rows.push(this.toSyncRow('houses', house.id, house.updatedAt, house.deleted, { ...house }));
    }
    const visits = await this.store.allVisits();
    for (const visit of visits) {
      rows.push(this.toSyncRow('visits', visit.id, visit.updatedAt, visit.deleted, { ...visit }));
    }
    const recordTypes = this.collectRecordTypes();
    for (const type of recordTypes) {
      const records = await this.store.allRecordsOf(type);
      for (const rec of records) {
        rows.push(this.toSyncRow('records', `${rec.type}/${rec.id}`, rec.updatedAt, rec.deleted, { ...rec }));
      }
    }
    const photos = await this.store.allPhotos();
    for (const photo of photos) {
      rows.push(this.toSyncRow('photos', photo.id, photo.updatedAt, photo.deleted, { ...photo } as Record<string, unknown>));
    }
    return rows;
  }

  /** Rows edited since the last sync (the dirty ones). Photo bytes are handled separately. */
  async changedRows(): Promise<readonly SyncRow[]> {
    const rows: SyncRow[] = [];
    for (const house of await this.store.dirtyHouses()) {
      rows.push(this.toSyncRow('houses', house.id, house.updatedAt, house.deleted, { ...house }));
    }
    for (const visit of await this.store.dirtyVisits()) {
      rows.push(this.toSyncRow('visits', visit.id, visit.updatedAt, visit.deleted, { ...visit }));
    }
    for (const rec of await this.store.dirtyRecords()) {
      rows.push(this.toSyncRow('records', `${rec.type}/${rec.id}`, rec.updatedAt, rec.deleted, { ...rec }));
    }
    return rows;
  }

  async photo(photoId: string): Promise<PhotoChangeDto | null> {
    const found = await this.store.getPhoto(photoId);
    if (!found) return null;
    return {
      id: found.id,
      houseId: found.houseId,
      contentType: found.contentType,
      sizeBytes: found.sizeBytes,
      createdAt: found.createdAt,
      updatedAt: found.updatedAt,
      deleted: found.deleted,
      syncVersion: found.syncVersion,
    };
  }

  /** Clears the dirty flag of houses, visits and photo metadata that the sync wrote out. */
  async markSynced(rows: readonly SyncRow[]): Promise<void> {
    for (const row of rows) {
      switch (row.kind) {
        case 'houses':
          await this.store.markHouseClean(row.key, row.stamp.updatedAt > 0 ? new Date(row.stamp.updatedAt).toISOString() : null);
          break;
        case 'visits':
          await this.store.markVisitClean(row.key, row.stamp.updatedAt > 0 ? new Date(row.stamp.updatedAt).toISOString() : null);
          break;
        case 'photos':
          await this.store.markPhotoMetaClean(row.key, row.stamp.updatedAt);
          break;
      }
    }
  }

  /** Stores rows that won the merge as imported records (not dirty), so they are not sent back. */
  async applyRemote(rows: readonly SyncRow[]): Promise<void> {
    const imported = { houses: [] as HouseRecord[], visits: [] as VisitRecord[], records: [] as RecordRecord[], photos: [] as PhotoRecord[] };
    for (const row of rows) {
      const json = row.json as Record<string, unknown>;
      switch (row.kind) {
        case 'houses': {
          const dto = { ...json, syncVersion: 1, deleted: row.stamp.deleted } as HouseDto;
          imported.houses.push(houseFromDto(dto, false));
          break;
        }
        case 'visits': {
          const dto = { ...json, syncVersion: 1, deleted: row.stamp.deleted } as VisitDto;
          imported.visits.push(visitFromDto(dto, false));
          break;
        }
        case 'photos':
          imported.photos.push({ ...json, syncVersion: 1, deleted: row.stamp.deleted } as PhotoRecord);
          break;
        case 'records': {
          const dto = { ...json, syncVersion: 1, deleted: row.stamp.deleted } as RecordDto;
          imported.records.push(recordFromDto(dto, false));
          break;
        }
      }
    }
    await this.store.putImported(imported);
  }

  /**
   * One row as the sync file holds it: the record's own fields plus `by` and `deleted`, built by the schema's row
   * builder so what we write is what `parseSyncFile` accepts. Local-only bookkeeping (`dirty`) never leaves the device.
   */
  private toSyncRow(kind: SyncKind, _key: string, _updatedAt: string | null | undefined, deleted: boolean, json: Record<string, unknown>): SyncRow {
    const { dirty: _dirty, ...shared } = json;
    // One record without a usable updatedAt must not make the whole file unwritable: fall back to its creation time,
    // then to the earliest time the schema allows (it then loses every merge, which is the safe side).
    const usable = (v: unknown): v is string => typeof v === 'string' && Date.parse(v) >= SYNC_EARLIEST_MS;
    const updatedAt = usable(shared['updatedAt']) ? shared['updatedAt'] : usable(shared['createdAt']) ? shared['createdAt'] : new Date(SYNC_EARLIEST_MS).toISOString();
    return syncRow(kind, { ...shared, updatedAt, by: this.deviceId, deleted });
  }

  private collectRecordTypes(): string[] {
    return [CRITERION_TYPE, PREFERENCE_TYPE, QUESTION_TYPE, VIEWING_TYPE, AREA_TYPE, PLACE_TYPE, AREA_NOTE_TYPE, BROKER_TYPE];
  }
}
