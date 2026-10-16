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

import { Injectable, InjectionToken, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import { map, of } from 'rxjs';
import { HouseApiService } from '../core/house-api.service';
import type { HouseDto, PhotoChangeDto, RecordDto, VisitDto } from '../core/models';
import type { PhotoMeta } from '../shared/photo-tags';
import { serverBehind, serverMerge } from './sync-rules';
import type { MergeRule } from './sync-rules';

/**
 * Where {@link SyncService} sends this browser's changes and gets the others' (S4b-BL-70, docs/15 §7 phase 1; Kotlin:
 * `SyncBackend` in `:shared`): the seam between the sync loop and one remote. Today the only one is the self-hosted
 * server ({@link ServerSyncBackend}); the Google Drive backend (S4b-BL-118) comes later behind the same interface.
 *
 * The loop stays in `SyncService` and does not change with the backend: it reads the dirty rows, marks them clean,
 * validates every pulled row as untrusted input (`tryHouseFromDto`, `wireVersion`), keeps the cursors, the progress,
 * "Stop" and cancellation, and waits out a rate limit (`call`). A backend only moves rows and photo bytes, and says how
 * a pulled row meets the local one ({@link mergeRule}).
 *
 * The contract, written for what Drive needs as well (docs/15 §5):
 *  * **Rows travel as the sync DTOs**, tombstones included (`deleted`), so the validation is the same for every
 *    backend; a backend that keeps whole snapshots (Drive's one sync file per device) keeps tombstones for ever.
 *  * **The cursor is a position the loop only compares and stores** (one per kind): each pulled row's `syncVersion` is
 *    its position and the loop asks for rows after the highest it has handled. The server's is its change-log counter;
 *    a backend without one gives a number that only grows within its own log (a time or a revision) and keeps whatever
 *    else it needs (Drive: the map of device id to file checksum) itself. A pushed row answered with no usable
 *    `syncVersion` says nothing about the remote being behind (`pushShowsReset`).
 *  * **"Complete" means handled**: the loop moves a cursor only past rows it has handled, and a stopped download
 *    stores where it got to; a backend never lists a row it has only half written (Drive's `partial-` files).
 *  * **Photos**: deletes and metadata go on any network; on the web every photo moves today, and Drive's network
 *    policy (docs/15 §11, S4b-BL-128) will gate {@link uploadPhoto} and {@link downloadPhoto} in the loop.
 *  * **Failures are thrown** as `HttpErrorResponse` (a 429 is waited out by the loop) or a `LocalDataError`, so
 *    `errorMsg` words them the same for every backend; a Drive backend maps its answers to the same (S4b-BL-115).
 */
export interface SyncBackend {
  /** How a pulled row meets the local one: {@link serverMerge} for the server. */
  readonly mergeRule: MergeRule;

  /**
   * "Is it behind": true when the remote has lost what this browser sent (S4b-BL-20: the server's highest position
   * below one of the stored `cursors`), so the loop sends everything again and pulls from 0. Asked only when a cursor
   * is above 0; a failure is unknown (the loop treats it as false, and a real failure shows in the push).
   */
  isBehind(cursors: readonly number[]): Observable<boolean>;

  /**
   * True for a backend that keeps whole snapshots (Drive): its `push*` calls only note the row, and the rows are sent
   * together in {@link commitPushes}. The loop then marks pushed rows clean (and forgets pushed photo deletions) **only
   * after {@link commitPushes} returned**: the snapshot is complete and confirmed by read-back (docs/15 §1.4). The
   * server's is false: each push is confirmed by its answer.
   */
  readonly stagesPushes?: boolean;

  /** Sends what the pushes noted and takes in what the others changed (only when {@link stagesPushes}); failures are thrown. */
  commitPushes?(): Observable<void>;

  /** Sends one changed house and answers with the row the remote keeps (last write wins there, or the one sent). */
  pushHouse(house: HouseDto): Observable<HouseDto>;
  /** Sends one changed visit; see {@link pushHouse}. */
  pushVisit(visit: VisitDto): Observable<VisitDto>;
  /** Sends one changed record; see {@link pushHouse}. */
  pushRecord(record: RecordDto): Observable<RecordDto>;
  /** Removes a photo; one the remote never had is fine. */
  deletePhoto(id: string): Observable<unknown>;
  /** Uploads a photo's bytes under its client-chosen id. */
  uploadPhoto(houseId: string, blob: Blob, id: string): Observable<unknown>;
  /** Sends a photo's metadata and answers with the remote's current one (last write wins on `metaUpdatedAt`). */
  pushPhotoMeta(id: string, meta: PhotoMeta): Observable<PhotoChangeDto>;

  /** The houses changed after `cursor` (positions in `syncVersion`), tombstones included. */
  housesSince(cursor: number): Observable<HouseDto[]>;
  /** The visits changed after `cursor`; see {@link housesSince}. */
  visitsSince(cursor: number): Observable<VisitDto[]>;
  /** The records changed after `cursor`; see {@link housesSince}. */
  recordsSince(cursor: number): Observable<RecordDto[]>;
  /** The photos added, deleted or with new metadata after `cursor`; the bytes come from {@link downloadPhoto}. */
  photoChangesSince(cursor: number): Observable<PhotoChangeDto[]>;
  /** One photo's bytes. */
  downloadPhoto(id: string): Observable<Blob>;

  /**
   * Like {@link downloadPhoto}, but null when this one photo cannot be had and will not be by trying again now (Drive: the
   * file was altered, swapped, planted, written by a revoked device, or is gone). The loop skips it and goes on; the backend
   * reports why. A failure that may pass (offline, rate limit) is still thrown. Absent: {@link downloadPhoto}.
   */
  downloadPhotoIfAvailable?(id: string): Observable<Blob | null>;

  /**
   * Whether photo bytes may move now (the network policy, S4b-BL-128, docs/15 §11). False: photos are not uploaded or
   * downloaded this run (deletes and metadata still go); they wait and come again. Absent: always.
   */
  photosAllowed?(): boolean;
}

/**
 * The self-hosted server as a {@link SyncBackend} (S4b-BL-70): the `HouseApiService` calls `SyncService` made before
 * the seam, unchanged. Positions are the server's change-log counter (`sync_seq`), the merge rule is
 * {@link serverMerge}, and failures are Angular's `HttpErrorResponse`.
 */
@Injectable({ providedIn: 'root' })
export class ServerSyncBackend implements SyncBackend {
  private readonly api = inject(HouseApiService);

  readonly mergeRule: MergeRule = serverMerge;

  /** `GET /api/stats`' `maxSyncVersion` below a stored cursor ({@link serverBehind}); an older server's none: false. */
  isBehind(cursors: readonly number[]): Observable<boolean> {
    return this.api.stats().pipe(map((stats) => serverBehind(stats?.maxSyncVersion, cursors)));
  }

  readonly stagesPushes = false;

  commitPushes(): Observable<void> {
    return of(undefined);
  }

  pushHouse(house: HouseDto): Observable<HouseDto> {
    return this.api.pushHouse(house);
  }

  pushVisit(visit: VisitDto): Observable<VisitDto> {
    return this.api.pushVisit(visit);
  }

  pushRecord(record: RecordDto): Observable<RecordDto> {
    return this.api.pushRecord(record);
  }

  deletePhoto(id: string): Observable<unknown> {
    return this.api.deletePhoto(id);
  }

  uploadPhoto(houseId: string, blob: Blob, id: string): Observable<unknown> {
    return this.api.uploadPhoto(houseId, blob, id);
  }

  pushPhotoMeta(id: string, meta: PhotoMeta): Observable<PhotoChangeDto> {
    return this.api.putPhotoMeta(id, meta);
  }

  housesSince(cursor: number): Observable<HouseDto[]> {
    return this.api.housesSince(cursor);
  }

  visitsSince(cursor: number): Observable<VisitDto[]> {
    return this.api.visitsSince(cursor);
  }

  recordsSince(cursor: number): Observable<RecordDto[]> {
    return this.api.recordsSince(cursor);
  }

  photoChangesSince(cursor: number): Observable<PhotoChangeDto[]> {
    return this.api.photoChangesSince(cursor);
  }

  downloadPhoto(id: string): Observable<Blob> {
    return this.api.photo(id);
  }
}

/** The backend `SyncService` syncs through; the server's until Drive sync (S4b-BL-118) chooses between them. */
export const SYNC_BACKEND = new InjectionToken<SyncBackend>('SyncBackend', {
  providedIn: 'root',
  factory: () => inject(ServerSyncBackend),
});
