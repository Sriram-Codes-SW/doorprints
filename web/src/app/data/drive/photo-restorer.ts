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

import { KeysError } from '../crypto/keys-file';
import { DriveError, SignInError } from './drive-client';
import type { DrivePhotos } from './drive-photos';
import type { PhotoRef, PhotoSkipReason } from './drive-photo-seams';
import type { DriveSyncEngine } from './drive-sync-engine';
import { planRestore } from './photo-restore-rules';
import type { GonePhoto, PhotoRestoreReason, RestoreConnection } from './photo-restore-rules';
import type { SyncRow } from './sync-file';

/** What went wrong with Drive itself, as opposed to with one photo. */
type Fault = 'NOT_CONNECTED' | 'OFFLINE';

/** What the caller knows of the restored candidate's photos and of when it was deleted. */
export interface PhotoRestoreRequest {
  readonly houseId: string;
  /** Epoch milliseconds of the candidate's tombstone. */
  readonly houseDeletedAt: number;
  /** The photos with a tombstone still stored on this device (their bytes are gone). */
  readonly known: readonly GonePhoto[];
}

/** One photo that came back from Drive. */
export interface FetchedPhoto {
  readonly photo: GonePhoto;
  readonly bytes: Uint8Array;
}

/** What a run did: the photos fetched, and the reason for each that was not. Never an error. */
export interface PhotoRestoreReport {
  readonly connection: RestoreConnection;
  readonly fetched: readonly FetchedPhoto[];
  readonly failed: readonly { readonly photo: GonePhoto; readonly reason: PhotoRestoreReason }[];
}

/** The report of a device with no open Drive folder: nothing fetched, each known photo not connected. */
export function notConnectedReport(known: readonly GonePhoto[], fault: Fault = 'NOT_CONNECTED'): PhotoRestoreReport {
  return { connection: fault, fetched: [], failed: known.map((photo) => ({ photo, reason: fault })) };
}

/** How a failure while talking to Drive reads to the person: no sign-in or pin, no answer, or something to count against one photo. */
export function connectionOf(e: unknown): Fault | null {
  if (e instanceof SignInError) return e.kind === 'offline' ? 'OFFLINE' : 'NOT_CONNECTED';
  if (e instanceof KeysError && e.kind === 'NOT_PINNED') return 'NOT_CONNECTED';
  if (e instanceof DriveError) {
    if (e.kind === 'UNAUTHORIZED' || e.kind === 'CANCELLED') return 'NOT_CONNECTED';
    if (e.kind === 'OFFLINE' || e.kind === 'RATE_LIMITED' || e.kind === 'SERVER') return 'OFFLINE';
  }
  return null;
}

/** A photo Drive could not give, as the reason the person reads. */
export function reasonOfSkip(reason: PhotoSkipReason, detail: string | null): PhotoRestoreReason {
  switch (reason) {
    case 'NO_REFERENCE':
    case 'NO_FOLDER':
    case 'NOT_OURS':
      return 'MISSING';
    case 'UNREADABLE':
      return detail === 'NOT_FOUND' || detail === 'FORBIDDEN' ? 'MISSING' : 'UNREADABLE';
    default:
      return 'UNREADABLE';
  }
}

/**
 * Getting the photos of a restored candidate back from the person's own Google Drive (S4b-BL-293; docs/15 §1.4). It finds
 * the candidate's photo tombstones (the ones this device still stores, and the ones its own sync file holds), checks each
 * is inside the 30 days and has a Drive file, and reads, checks and decrypts that file exactly like a sync download. It
 * uploads nothing, deletes nothing and writes nothing; the caller puts the bytes in the store. Every failure becomes a
 * reason for a photo, none is thrown, so the candidate's own restore never fails because of it. Kotlin twin: `PhotoRestorer`.
 */
export class DrivePhotoRestorer {
  constructor(
    private readonly engine: DriveSyncEngine,
    private readonly photos: DrivePhotos,
    private readonly clock: () => number,
  ) {}

  async restore(req: PhotoRestoreRequest): Promise<PhotoRestoreReport> {
    let rows: SyncRow[];
    try {
      rows = await this.engine.ownPhotoRows();
    } catch (e) {
      const connection = connectionOf(e);
      if (connection) return notConnectedReport(req.known, connection);
      rows = []; // the file cannot be read (a rule refused it): the photos this device still stores are tried
    }
    let refs: Readonly<Record<string, PhotoRef>> = {};
    try {
      refs = await this.photos.refs();
    } catch {
      refs = {};
    }
    const plan = planRestore({ houseId: req.houseId, houseDeletedAt: req.houseDeletedAt, now: this.clock(), local: req.known, file: rows, refs });
    const fetched: FetchedPhoto[] = [];
    const failed: { photo: GonePhoto; reason: PhotoRestoreReason }[] = [];
    let fault: Fault | null = null;
    for (const item of plan) {
      if (fault) {
        failed.push({ photo: item.photo, reason: fault });
        continue;
      }
      if (item.action !== 'FETCH') {
        failed.push({ photo: item.photo, reason: item.action });
        continue;
      }
      try {
        const got = await this.photos.fetchForRestore(item.photo.ref!);
        if (got.kind === 'Bytes') fetched.push({ photo: item.photo, bytes: got.bytes });
        else failed.push({ photo: item.photo, reason: reasonOfSkip(got.reason, got.detail) });
      } catch (e) {
        const c = connectionOf(e);
        if (c) {
          fault = c;
          failed.push({ photo: item.photo, reason: c });
        } else {
          failed.push({ photo: item.photo, reason: 'UNREADABLE' });
        }
      }
    }
    const connection: RestoreConnection = fault ?? 'OK';
    return { connection, fetched, failed };
  }
}
