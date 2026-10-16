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

import { DEFAULT_CHUNK } from './drive-client';
import { syncRow } from './sync-file';
import type { SyncRow } from './sync-file';

/*
 * What the photos over Drive (S4b-BL-128, docs/15 §5.1, §11) keep and report. Kotlin twin: `PhotoSeams.kt`, the same names.
 */

/** `appProperties kind` of a photo file. */
export const KIND_PHOTO = 'photo';

/** `appProperties photoId`: the photo's random id and nothing else about it; a lookup hint only. */
export const PROP_PHOTO_ID = 'photoId';

/** Where a photo's bytes are in Drive: the file and the SHA-256 (lowercase hex) of the **plaintext**, from the photo's row. */
export interface PhotoRef {
  readonly driveFileId: string;
  readonly sha256: string;
}

/** A photo this device could not read, remembered so every pass does not try again. */
export interface PhotoBad {
  readonly fileId: string;
  readonly reason: PhotoSkipReason;
  readonly at: number;
}

/** The device's own photo bookkeeping, sealed on the device by the platform; never in a backup. */
export interface PhotoState {
  readonly photosFolderId: string | null;
  readonly refs: Readonly<Record<string, PhotoRef>>;
  readonly bad: Readonly<Record<string, PhotoBad>>;
}

/** The photo bookkeeping of a device that has uploaded nothing. */
export const EMPTY_PHOTO_STATE: PhotoState = { photosFolderId: null, refs: {}, bad: {} };

/** Where a device keeps its `PhotoState`. */
export interface PhotoStateStore {
  load(): Promise<PhotoState>;
  save(state: PhotoState): Promise<void>;
}

/** The part of `DrivePhotos` the sync engine uses. */
export interface PhotoRefs {
  refs(): Promise<Readonly<Record<string, PhotoRef>>>;
  /** Refs read from authenticated rows of other devices' files; they fill gaps and replace only a ref known to be dead. */
  learn(found: Readonly<Record<string, PhotoRef>>): Promise<void>;
}

/** Why one photo was skipped; every skip is reported, none aborts the sync, none deletes anything. */
export type PhotoSkipReason =
  | 'TOO_LARGE' | 'EMPTY' | 'BAD_ID' | 'NO_REFERENCE' | 'NO_FOLDER' | 'UNREADABLE' | 'NOT_OURS' | 'NOT_ENCRYPTED'
  | 'BAD_ENVELOPE' | 'CHECKSUM_MISMATCH' | 'DOWNLOAD_CORRUPT' | 'REVOKED_WRITER' | 'OLD_EPOCH_AFTER_REVOKE'
  | 'UNKNOWN_WRITER' | 'NEWER_EPOCH' | 'UNSUPPORTED_VERSION';

/** A photo that was skipped, with the reason; never aborts a sync. */
export interface SkippedPhoto {
  readonly photoId: string;
  readonly reason: PhotoSkipReason;
  readonly detail?: string | null;
}

/**
 * What happened to one photo: uploaded, already there unchanged, an existing, verified Drive file taken as its copy (adopted, e.g. another device uploaded the same bytes), or skipped.
 */
export type PhotoUploadResult =
  | { readonly kind: 'Uploaded'; readonly ref: PhotoRef }
  | { readonly kind: 'Unchanged'; readonly ref: PhotoRef }
  | { readonly kind: 'Adopted'; readonly ref: PhotoRef }
  | { readonly kind: 'Skipped'; readonly reason: PhotoSkipReason };

/** Limits and timings; the tests make them small. */
export interface PhotoConfig {
  readonly maxPlaintextBytes: number;
  readonly chunkSize: number;
  readonly maxCandidates: number;
  readonly badRetryMs: number;
  readonly keysFreshMs: number;
}

/** The production limits: photos up to 32 MiB, and a bad photo is retried after a day. */
export const DEFAULT_PHOTO_CONFIG: PhotoConfig = {
  maxPlaintextBytes: 32 * 1024 * 1024,
  chunkSize: DEFAULT_CHUNK,
  maxCandidates: 4,
  badRetryMs: 24 * 60 * 60 * 1000,
  keysFreshMs: 2 * 60 * 1000,
};

/** The largest file: the photo plus the `dpx/1` header and one tag per 64 KiB chunk. */
export function maxPhotoFileBytes(c: PhotoConfig): number {
  return c.maxPlaintextBytes + Math.floor(c.maxPlaintextBytes / 4096) + 16 * 1024;
}

/** The Drive file of a photo's bytes that `row` names, or null when it names none (Kotlin `SyncRows.refOf`). */
export function refOfRow(row: SyncRow): PhotoRef | null {
  if (row.kind !== 'photos') return null;
  const file = row.json['driveFileId'];
  const sha = row.json['sha256'];
  return typeof file === 'string' && typeof sha === 'string' ? { driveFileId: file, sha256: sha } : null;
}

/** `row` with the Drive file of its bytes written in (the row's stamp does not change). */
export function withRef(row: SyncRow, ref: PhotoRef): SyncRow {
  return syncRow(row.kind, { ...row.json, driveFileId: ref.driveFileId, sha256: ref.sha256 });
}
