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

import { hex } from '../crypto/bytes';
import type { OpenedKeys } from '../crypto/keys-file';
import type { KeysGuard } from '../crypto/keys-guard';
import type { PhotoChangeDto } from '../../core/models';
import type { SyncKind, SyncRow } from './sync-file';

/*
 * What the Drive sync (S4b-BL-118, docs/15 §5.1, §7 phase 5) needs from the rest of the app, as small interfaces, so
 * the engine runs on fakes in the tests. Kotlin twin: `DriveSyncSeams.kt`, the same names.
 */

/** The inner format of a sync file inside `dpx/1` (the plaintext's own format is `doorprints-sync/1`). */
export const SYNC_INNER = 'sync/1';

/** `appProperties kind` of a sync file (docs/15 §5.1). */
export const KIND_SYNC = 'sync';

/** `appProperties seq`: the writer's counter as a hint to order candidates; never trusted (the file's own is). */
export const PROP_SEQ = 'seq';

/** The id a device writes sync files under: the hex of its key id, so the authenticated `kid` of a file names it. */
export function syncDeviceId(kid: Uint8Array): string {
  return hex(kid);
}

/**
 * An opened Drive folder, handed in by the connect ticket: the folder's id, this device's key id, the list of keys opened
 * **through the device's pin** (`guard`; nothing in Drive is read before a pin exists), and `reopen`, which reads
 * `keys.json` again and opens it through the same guard (so a revoke or a new epoch is seen at the start of every
 * pass). No keys error of `reopen` triggers anything here: it ends the pass and is reported.
 */
export class FolderSession {
  keys: OpenedKeys;
  readonly deviceId: string;

  constructor(
    readonly rootId: string,
    readonly deviceKid: Uint8Array,
    keys: OpenedKeys,
    readonly guard: KeysGuard,
    private readonly reopen: (() => Promise<OpenedKeys>) | null = null,
  ) {
    this.keys = keys;
    this.deviceId = syncDeviceId(deviceKid);
  }

  async refresh(): Promise<OpenedKeys> {
    if (this.reopen) this.keys = await this.reopen();
    return this.keys;
  }
}

/**
 * This device's rows, read for one pass: every house, visit, record and photo row it holds, tombstones included, as
 * `SyncRow`s whose `by` is the device that made that version (this device's id for a row whose writer the store does not
 * keep yet).
 */
export interface LocalRows {
  all(): Promise<readonly SyncRow[]>;
  /** One photo row (live or not) by id, for the tombstone of a photo deleted here and for a metadata push. */
  photo(photoId: string): Promise<PhotoChangeDto | null>;
}

/** What a device remembers of another device's sync file. */
export interface SyncPeer {
  /** The highest authenticated `seq` accepted from it: a lower one is a rollback. */
  readonly highestSeq: number;
  /** SHA-256 (hex) of the last file of this device fully merged here (nothing left to apply, hold or confirm). */
  readonly mergedChecksum: string | null;
}

/** The device's own Drive-sync bookkeeping, sealed on the device by the platform; never in a backup. */
export interface DriveSyncState {
  readonly syncFolderId: string | null;
  /** The highest `seq` this device has used (reserved before an upload, so a retry never reuses one). */
  readonly lastSeq: number;
  /** The `seq` of the last file confirmed by read-back. */
  readonly confirmedSeq: number;
  readonly lastFileId: string | null;
  readonly lastChecksum: string | null;
  /** SHA-256 of the rows of the last confirmed file: unchanged rows are not written again. */
  readonly lastRowsHash: string | null;
  /** Counts the passes that handed rows to the loop: their `syncVersion`. */
  readonly generation: number;
  readonly peers: Readonly<Record<string, SyncPeer>>;
  readonly failures: number;
  /** No pass before this time (epoch ms) unless forced: the backoff after failures. */
  readonly notBefore: number;
}

export const EMPTY_SYNC_STATE: DriveSyncState = {
  syncFolderId: null, lastSeq: 0, confirmedSeq: 0, lastFileId: null, lastChecksum: null, lastRowsHash: null,
  generation: 0, peers: {}, failures: 0, notBefore: 0,
};

/** Where {@link DriveSyncState} lives. One writer at a time (the website's one-tab lock). */
export interface SyncStateStore {
  load(): Promise<DriveSyncState>;
  save(state: DriveSyncState): Promise<void>;
}

/** Why a file of another device was not used; every skip is reported, none aborts the pass, none deletes anything. */
export type SkipReason =
  | 'UNLISTED_DEVICE'
  | 'REVOKED_WRITER'
  | 'OLD_EPOCH_AFTER_REVOKE'
  | 'UNKNOWN_WRITER'
  | 'NEWER_EPOCH'
  | 'NOT_ENCRYPTED'
  | 'BAD_ENVELOPE'
  | 'WRONG_DEVICE'
  | 'BAD_FILE'
  | 'UNSUPPORTED_VERSION'
  | 'TOO_LARGE'
  | 'DOWNLOAD_CORRUPT'
  | 'UNREADABLE'
  | 'ROLLED_BACK'
  | 'TOO_MANY_FILES';

/** A skipped file. `detail` is a code (never file content). */
export interface SkippedFile {
  readonly deviceId: string | null;
  readonly fileId: string | null;
  readonly reason: SkipReason;
  readonly detail?: string | null;
}

/** What a pass did. */
export interface SyncReport {
  /** The rows to apply here, at most one per key. */
  readonly take: readonly SyncRow[];
  /** Rows stamped too far ahead of this clock: kept for a later pass. */
  readonly held: number;
  /** House deletions waiting for the person's confirmation. */
  readonly deferred: number;
  readonly skipped: readonly SkippedFile[];
  /** True when this pass wrote (and confirmed by read-back) a new file of this device. */
  readonly wrote: boolean;
  readonly seq: number | null;
  readonly peersRead: number;
  /** The `syncVersion` of `take`. */
  readonly generation: number;
}

export type SyncPassResult =
  | { readonly kind: 'Done'; readonly report: SyncReport }
  | {
      readonly kind: 'NeedsConfirmation';
      readonly report: SyncReport;
      readonly housesToDelete: number;
      readonly liveHouses: number;
      readonly fromDevices: readonly string[];
    }
  | { readonly kind: 'Waiting'; readonly notBefore: number }
  | { readonly kind: 'Paused' };

export function reportOf(result: SyncPassResult): SyncReport | null {
  return result.kind === 'Done' || result.kind === 'NeedsConfirmation' ? result.report : null;
}

export function skippedReasons(result: SyncPassResult): SkipReason[] {
  return reportOf(result)?.skipped.map((s) => s.reason) ?? [];
}

/** The part of Drive sync that is not built yet (photos: S4b-BL-128). Typed, so the loop and the screens can tell. */
export class DriveSyncNotYet extends Error {
  constructor(readonly feature: string, readonly ticket: string) {
    super(`Drive sync: ${feature} is not built yet (${ticket})`);
    this.name = 'DriveSyncNotYet';
  }
}

export type { SyncKind };
