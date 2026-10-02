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

import { from, of } from 'rxjs';
import type { Observable } from 'rxjs';
import type { HouseDto, PhotoChangeDto, RecordDto, VisitDto } from '../../core/models';
import type { PhotoMeta } from '../../shared/photo-tags';
import type { SyncBackend } from '../sync-backend';
import type { MergeRule } from '../sync-rules';
import { driveMerge, nextStamp } from './drive-merge';
import { DriveError } from './drive-client';
import type { DriveSyncEngine } from './drive-sync-engine';
import type { DrivePhotos } from './drive-photos';
import { withRef } from './drive-photo-seams';
import type { SkippedPhoto } from './drive-photo-seams';
import { DriveSyncNotYet } from './drive-sync-seams';
import type { LocalRows, SyncPassResult } from './drive-sync-seams';
import { syncRow, syncTime } from './sync-file';
import type { SyncKind, SyncRow } from './sync-file';

/**
 * Google Drive as a {@link SyncBackend} (S4b-BL-118; Kotlin `DriveSyncBackend`): the seam the loop in `SyncService`
 * already uses, over a {@link DriveSyncEngine}. Drive keeps whole snapshots, so: `push*` send nothing (the engine writes
 * this device's whole state once, in {@link commitPushes}, and the loop marks rows clean only after that returned: the
 * file complete and read back); what the other devices' files changed is found in `commitPushes` too and handed out by
 * `*Since`, a row's position being the pass counter. Photos (S4b-BL-128): metadata and tombstones flow in the sync file;
 * the bytes go as one `photo/1` file each through {@link DrivePhotos}. A photo's row carries the Drive file and the
 * plaintext SHA-256 once uploaded; a photo another device has not uploaded yet, or one that cannot be read (tampered,
 * planted, revoked writer), is not handed to the loop: `photoChangesSince` leaves it out until its bytes can be fetched,
 * `downloadPhotoIfAvailable` answers null and the skip is in {@link photoSkips}. Without `photos` the photo calls throw
 * {@link DriveSyncNotYet}. The shrink guard's question is not an error:
 * the last pass is in {@link lastResult}; {@link confirmShrink} and the next sync apply the held deletions.
 */
export class DriveSyncBackend implements SyncBackend {
  readonly mergeRule: MergeRule = driveMerge;
  readonly stagesPushes = true;

  /** The outcome of the last {@link commitPushes}. */
  lastResult: SyncPassResult | null = null;

  private confirmNext = false;
  private taken: readonly SyncRow[] = [];
  private generation = 0;

  constructor(
    private readonly engine: DriveSyncEngine,
    private readonly local: LocalRows,
    private readonly clock: () => number,
    private readonly deviceId: string,
    /** Photo bytes over Drive (S4b-BL-128); null: the photo calls throw {@link DriveSyncNotYet}. */
    private readonly photos: DrivePhotos | null = null,
  ) {}

  /** The person said yes to the shrink guard: the next pass applies the house deletions it held. */
  confirmShrink(): void {
    this.confirmNext = true;
  }

  isBehind(): Observable<boolean> {
    return from(this.engine.isBehind());
  }

  commitPushes(): Observable<void> {
    return from(this.commit());
  }

  private async commit(): Promise<void> {
    const confirm = this.confirmNext;
    this.taken = [];
    const result = await this.engine.run(confirm);
    this.lastResult = result;
    if (result.kind === 'Waiting') throw new DriveError('RATE_LIMITED', 0, Math.max(0, result.notBefore - this.clock()), null);
    if (result.kind === 'Paused') throw new DriveError('CANCELLED', 0, null, 'paused');
    if (confirm) this.confirmNext = false;
    this.taken = result.report.take;
    this.generation = result.report.generation;
  }

  pushHouse(house: HouseDto): Observable<HouseDto> {
    return of({ ...house, syncVersion: 0 });
  }

  pushVisit(visit: VisitDto): Observable<VisitDto> {
    return of({ ...visit, syncVersion: 0 });
  }

  pushRecord(record: RecordDto): Observable<RecordDto> {
    return of({ ...record, syncVersion: 0 });
  }

  /** The tombstone of a photo deleted here (its local row goes right after): kept in the file for ever. */
  deletePhoto(id: string): Observable<unknown> {
    return from(this.tombstone(id));
  }

  private async tombstone(id: string): Promise<void> {
    const photo = await this.local.photo(id);
    if (!photo) return;
    const previous = photo.updatedAt ? syncTime(photo.updatedAt) : null;
    const at = nextStamp(this.clock(), previous);
    // The tombstone keeps the Drive file of the bytes, so a later clean-up (30 days, no kept backup) finds it.
    const row = syncRowOf('photos', { ...photo, deleted: true, updatedAt: new Date(at).toISOString() }, this.deviceId);
    const ref = this.photos ? (await this.photos.refs())[id] : undefined;
    this.engine.stage(ref ? withRef(row, ref) : row);
  }

  /** The photos skipped since the last call (reported, never thrown). */
  photoSkips(): SkippedPhoto[] {
    return this.photos?.drainSkipped() ?? [];
  }

  /** One `photo/1` file per photo; a photo that can never go (too large, empty) stays on this device and counts as sent. */
  uploadPhoto(_houseId: string, blob: Blob, id: string): Observable<unknown> {
    const service = this.photos;
    if (!service) throw new DriveSyncNotYet('photo upload', 'S4b-BL-128');
    return from(blob.arrayBuffer().then((buf) => service.upload(id, new Uint8Array(buf))));
  }

  pushPhotoMeta(id: string, meta: PhotoMeta): Observable<PhotoChangeDto> {
    return from(this.local.photo(id).then((photo) => {
      if (!photo) throw new DriveError('NOT_FOUND', 0, null, 'photo');
      return { ...photo, roomId: meta.roomId, tags: meta.tags, caption: meta.caption, metaUpdatedAt: meta.metaUpdatedAt, syncVersion: 0 };
    }));
  }

  housesSince(cursor: number): Observable<HouseDto[]> {
    return of(this.since<HouseDto>('houses', cursor));
  }

  visitsSince(cursor: number): Observable<VisitDto[]> {
    return of(this.since<VisitDto>('visits', cursor));
  }

  recordsSince(cursor: number): Observable<RecordDto[]> {
    return of(this.since<RecordDto>('records', cursor));
  }

  photoChangesSince(cursor: number): Observable<PhotoChangeDto[]> {
    return from(this.photoChanges(cursor));
  }

  private async photoChanges(cursor: number): Promise<PhotoChangeDto[]> {
    const all = this.since<PhotoChangeDto>('photos', cursor);
    if (!this.photos) return all;
    // A live photo this device does not have and whose bytes are not in Drive yet (the other device waits for Wi-Fi) is
    // left out; its row comes again in a later pass, once the other device's file names the Drive file.
    const refs = await this.photos.refs();
    const out: PhotoChangeDto[] = [];
    for (const c of all) if (c.deleted || c.id in refs || (await this.local.photo(c.id)) !== null) out.push(c);
    return out;
  }

  downloadPhoto(id: string): Observable<Blob> {
    return from(this.fetchPhoto(id).then((blob) => {
      if (!blob) throw new DriveError('NOT_FOUND', 0, null, 'photoUnavailable');
      return blob;
    }));
  }

  /** The photo's bytes, or null when this photo cannot be had and will not be by trying again now (reported in {@link photoSkips}). */
  downloadPhotoIfAvailable(id: string): Observable<Blob | null> {
    return from(this.fetchPhoto(id));
  }

  private async fetchPhoto(id: string): Promise<Blob | null> {
    if (!this.photos) throw new DriveSyncNotYet('photo download', 'S4b-BL-128');
    const bytes = await this.photos.download(id);
    return bytes ? new Blob([bytes as BlobPart], { type: 'image/jpeg' }) : null;
  }

  private since<T>(kind: SyncKind, cursor: number): T[] {
    if (this.generation <= cursor) return [];
    return this.taken.filter((r) => r.kind === kind).map((r) => ({ ...r.json, syncVersion: this.generation }) as T);
  }
}

/** Builds the {@link SyncRow} the platform's `LocalRows` returns from a sync DTO of its stores (Kotlin `SyncRows`). */
export function syncRowOf(kind: SyncKind, dto: object, by: string): SyncRow {
  const json: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(dto)) if (k !== 'syncVersion' && v !== null && v !== undefined) json[k] = v;
  json['by'] = by;
  return syncRow(kind, json);
}
