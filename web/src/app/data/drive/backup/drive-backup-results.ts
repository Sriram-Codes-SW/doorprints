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

import { CryptoError } from '../../crypto/crypto-provider';
import { DpxError } from '../../crypto/dpx';
import type { DpxErrorKind } from '../../crypto/dpx';
import { hex } from '../../crypto/bytes';
import type { OpenedKeys } from '../../crypto/keys-file';
import { KeysError } from '../../crypto/keys-file';
import type { KeysErrorKind } from '../../crypto/keys-file';
import { RecoveryKeyError } from '../../crypto/recovery-key';
import type { RecoveryKey } from '../../crypto/recovery-key';
import { DriveError, SignInError } from '../drive-client';
import type { DriveErrorKind, DriveFile } from '../drive-client';
import type { ShrinkHold } from './backup-retention';
import type { ScheduleFailure } from './backup-schedule';
import { ControlError } from './control-file';
import type { ControlBody, ControlErrorKind } from './control-file';
import type { TKey } from '../../../i18n/en';

/*
 * The typed results of the Drive backups (S4b-BL-116) that the screens show: where the folder stands on this device,
 * one backup run, one listing, one download. The twin of Kotlin's `DriveBackupResults.kt`.
 */

/**
 * What went wrong, in the terms a screen can word: Drive and network failures, key and control-file trust failures, sign-in failures.
 */
export type DriveProblemKind =
  | 'OFFLINE'
  | 'UNAUTHORIZED'
  | 'QUOTA_EXCEEDED'
  | 'RATE_LIMITED'
  | 'SERVER'
  | 'DRIVE'
  | 'CORRUPT'
  | 'KEYS_ROLLED_BACK'
  | 'KEYS_UNTRUSTED'
  | 'KEYS_UNREADABLE'
  | 'WRONG_RECOVERY_KEY'
  | 'NO_RECOVERY_KEY'
  | 'DEVICE_REVOKED'
  | 'CONTROL_ROLLED_BACK'
  | 'CONTROL_INVALID'
  | 'FOLDER_WITHOUT_KEYS'
  | 'FOLDER_EXISTS'
  | 'BACKUP_REFUSED'
  | 'BACKUP_GONE'
  | 'SOURCE_FAILED'
  /** An unknown failure inside `connect()`, not a backup that could not be prepared. */
  | 'CONNECT_FAILED'
  | 'CRYPTO_UNAVAILABLE'
  | 'SIGNIN_POPUP_BLOCKED'
  | 'SIGNIN_CLOSED'
  | 'SIGNIN_DENIED'
  | 'SIGNIN_UNAVAILABLE';

/** Why something did not happen, in the words a screen needs; the underlying kind is kept for support. */
export class DriveProblem {
  constructor(
    readonly kind: DriveProblemKind,
    readonly driveKind: DriveErrorKind | null = null,
    readonly keysKind: KeysErrorKind | null = null,
    readonly controlKind: ControlErrorKind | null = null,
    readonly dpxKind: DpxErrorKind | null = null,
  ) {}

  /** Waiting and trying again can help. */
  get retryable(): boolean {
    return this.kind === 'OFFLINE' || this.kind === 'RATE_LIMITED' || this.kind === 'SERVER';
  }

  /** How the schedule treats it. */
  get scheduleFailure(): ScheduleFailure {
    switch (this.kind) {
      case 'OFFLINE': case 'RATE_LIMITED': case 'SERVER': case 'DRIVE': case 'CORRUPT': case 'SOURCE_FAILED': case 'BACKUP_GONE':
        return 'RETRYABLE';
      case 'QUOTA_EXCEEDED':
        return 'QUOTA';
      case 'UNAUTHORIZED':
        return 'UNAUTHORIZED';
      default:
        return 'BLOCKED';
    }
  }

  static of(e: unknown): DriveProblem {
    if (e instanceof SignInError) {
      const kinds: Record<SignInError['kind'], DriveProblemKind> = {
        popup_blocked: 'SIGNIN_POPUP_BLOCKED',
        popup_closed: 'SIGNIN_CLOSED',
        denied: 'SIGNIN_DENIED',
        offline: 'OFFLINE',
        unavailable: 'SIGNIN_UNAVAILABLE',
      };
      return new DriveProblem(kinds[e.kind]);
    }
    if (e instanceof DriveError) {
      const known: Partial<Record<DriveErrorKind, DriveProblemKind>> = {
        OFFLINE: 'OFFLINE',
        UNAUTHORIZED: 'UNAUTHORIZED',
        QUOTA_EXCEEDED: 'QUOTA_EXCEEDED',
        RATE_LIMITED: 'RATE_LIMITED',
        SERVER: 'SERVER',
        CORRUPT: 'CORRUPT',
      };
      return new DriveProblem(known[e.kind] ?? 'DRIVE', e.kind);
    }
    if (e instanceof KeysError) {
      let kind: DriveProblemKind;
      switch (e.kind) {
        case 'ROLLED_BACK':
          kind = 'KEYS_ROLLED_BACK';
          break;
        case 'PIN_MISMATCH': case 'FORK_DETECTED': case 'MAC_INVALID': case 'RECOVERY_ANCHOR_INVALID': case 'CHAIN_BROKEN':
        case 'NOT_PINNED': case 'REVISION_JUMP':
          kind = 'KEYS_UNTRUSTED';
          break;
        case 'RECOVERY_MISMATCH':
          kind = 'WRONG_RECOVERY_KEY';
          break;
        case 'NO_RECOVERY':
          kind = 'NO_RECOVERY_KEY';
          break;
        case 'REVOKED':
          kind = 'DEVICE_REVOKED';
          break;
        default:
          kind = 'KEYS_UNREADABLE';
      }
      return new DriveProblem(kind, null, e.kind);
    }
    if (e instanceof ControlError) {
      return new DriveProblem(e.kind === 'ROLLED_BACK' ? 'CONTROL_ROLLED_BACK' : 'CONTROL_INVALID', null, null, e.kind);
    }
    if (e instanceof DpxError) return new DriveProblem('BACKUP_REFUSED', null, null, null, e.kind);
    if (e instanceof RecoveryKeyError) return new DriveProblem('WRONG_RECOVERY_KEY');
    if (e instanceof CryptoError) return new DriveProblem(e.kind === 'UNAVAILABLE' ? 'CRYPTO_UNAVAILABLE' : 'BACKUP_REFUSED');
    return new DriveProblem('SOURCE_FAILED');
  }
}


const PROBLEM_MSG: Record<DriveProblemKind, TKey> = {
  OFFLINE: 'driveProblem.OFFLINE',
  UNAUTHORIZED: 'driveProblem.UNAUTHORIZED',
  QUOTA_EXCEEDED: 'driveProblem.QUOTA_EXCEEDED',
  RATE_LIMITED: 'driveProblem.RATE_LIMITED',
  SERVER: 'driveProblem.SERVER',
  DRIVE: 'driveProblem.DRIVE',
  CORRUPT: 'driveProblem.CORRUPT',
  KEYS_ROLLED_BACK: 'driveProblem.KEYS_ROLLED_BACK',
  KEYS_UNTRUSTED: 'driveProblem.KEYS_UNTRUSTED',
  KEYS_UNREADABLE: 'driveProblem.KEYS_UNREADABLE',
  WRONG_RECOVERY_KEY: 'driveJoin.errorWrongKey',
  NO_RECOVERY_KEY: 'driveProblem.NO_RECOVERY_KEY',
  DEVICE_REVOKED: 'driveProblem.DEVICE_REVOKED',
  CONTROL_ROLLED_BACK: 'driveProblem.CONTROL_ROLLED_BACK',
  CONTROL_INVALID: 'driveProblem.CONTROL_INVALID',
  FOLDER_WITHOUT_KEYS: 'driveProblem.FOLDER_WITHOUT_KEYS',
  FOLDER_EXISTS: 'driveProblem.FOLDER_EXISTS',
  BACKUP_REFUSED: 'driveProblem.BACKUP_REFUSED',
  BACKUP_GONE: 'driveBackups.error.backupNotFound',
  SOURCE_FAILED: 'driveProblem.SOURCE_FAILED',
  CONNECT_FAILED: 'driveProblem.CONNECT_FAILED',
  CRYPTO_UNAVAILABLE: 'driveProblem.CRYPTO_UNAVAILABLE',
  SIGNIN_POPUP_BLOCKED: 'driveProblem.SIGNIN_POPUP_BLOCKED',
  SIGNIN_CLOSED: 'driveProblem.SIGNIN_CLOSED',
  SIGNIN_DENIED: 'driveProblem.SIGNIN_DENIED',
  SIGNIN_UNAVAILABLE: 'driveProblem.SIGNIN_UNAVAILABLE',
};

/** The screen key for a Drive problem; never a raw English sentence. */
export function problemToMsg(kind: DriveProblemKind): TKey {
  return PROBLEM_MSG[kind];
}

const TYPED_ERRORS = [SignInError, DriveError, KeysError, ControlError, DpxError, RecoveryKeyError, CryptoError] as const;

/** A thrown value as a screen key: typed Drive failures map; anything else is the generic message, never String(err). */
export function msgOfThrown(err: unknown): TKey {
  if (err instanceof DriveProblem) return problemToMsg(err.kind);
  if (TYPED_ERRORS.some((C) => err instanceof C)) return problemToMsg(DriveProblem.of(err).kind);
  return 'driveConnect.failed';
}

/** An opened folder: its ids, the trusted key list and the control file. Only the services make one. */
export interface ReadyFolder {
  readonly rootId: string;
  readonly keysId: string;
  readonly controlId: string;
  /** Null until the first backup makes `Backups/`. */
  readonly backupsId: string | null;
  readonly keys: OpenedKeys;
  readonly control: ControlBody;
}

/**
 * Where the Doorprints folder stands for this device (`DriveBackupService.connect`). `NEEDS_ENROLMENT`: a key set this
 * device has no pin for; nothing is written until it joins by QR code or the recovery key. `NEEDS_RECOVERY_KEY`: this
 * device trusted the folder before, but its own key no longer opens the list.
 */
export type DriveConnection =
  | { readonly kind: 'NO_FOLDER' }
  | { readonly kind: 'FOLDER_GONE' }
  | { readonly kind: 'NEEDS_ENROLMENT'; readonly recoveryAvailable: boolean }
  | { readonly kind: 'NEEDS_RECOVERY_KEY'; readonly recoveryAvailable: boolean; readonly reason: KeysErrorKind }
  | { readonly kind: 'READY'; readonly folder: ReadyFolder; readonly recoveryKeyUnshown?: boolean }
  | { readonly kind: 'ERROR'; readonly problem: DriveProblem };

/** `createFolder`'s result: `recoveryKey` is shown **once** by the caller and never stored. */
export interface CreateOutcome {
  readonly connection: DriveConnection;
  readonly recoveryKey: RecoveryKey | null;
}

/** One backup that passed every check of the listing: complete, its metadata authenticated, its writer accepted. */
export interface DriveBackup {
  readonly fileId: string;
  /** Drive's name: for the person's eyes only (the app goes by the metadata). */
  readonly name: string;
  readonly createdAt: number;
  readonly houses: number;
  readonly epoch: number;
  readonly writerKid: Uint8Array;
  /** The SHA-256 of the `dpx/1` file, lowercase hex: Drive's `sha256Checksum`, bound by the metadata's MAC. */
  readonly sha256: string;
  readonly size: number | null;
}

/** A one-line description of a backup for logs and test output; holds the key id in hex, no secret. */
export function describeBackup(b: DriveBackup): string {
  return `DriveBackup(${b.fileId}, createdAt=${b.createdAt}, houses=${b.houses}, epoch=${b.epoch}, kid=${hex(b.writerKid)})`;
}

/** Why a file in `Backups/` is not a backup. */
export type IgnoredReason =
  | 'NOT_A_BACKUP'
  | 'NO_CHECKSUM'
  | 'NEWER_EPOCH'
  | 'MAC_INVALID'
  | 'WRITER_REFUSED'
  | 'DELETED_BEFORE'
  | 'DUPLICATE';

/**
 * The backups of one folder, newest first by the authenticated `createdAt`. `unfinished` verified but never marked
 * complete; `junk` partial files that do not verify; `missingNewer`: the newest backup this device has seen is not
 * here any more (reported, never acted on).
 */
export interface BackupListing {
  readonly backups: DriveBackup[];
  readonly unfinished: DriveBackup[];
  readonly duplicates: string[];
  readonly junk: DriveFile[];
  readonly ignored: [string, IgnoredReason][];
  readonly missingNewer: boolean;
}

/** What retention did after a backup: trashed ids, completed unfinished uploads, and the shrink guard's hold. */
export interface TidyReport {
  readonly trashed: string[];
  readonly completed: string[];
  readonly hold: ShrinkHold | null;
  readonly problem: DriveProblem | null;
}

/** One backup run: done (checked in Drive; `tidy` says what retention did) or failed (nothing new is in Drive). */
export type BackupOutcome =
  | { readonly kind: 'done'; readonly backup: DriveBackup; readonly tidy: TidyReport; readonly missingNewer: boolean }
  | { readonly kind: 'failed'; readonly problem: DriveProblem };

/** One download for *Import a backup* > *From Google Drive*: verified (the decrypted ZIP is in the staging sink) or refused. */
export type ImportDownload =
  | { readonly kind: 'verified'; readonly backup: DriveBackup; readonly format: string; readonly plaintextSize: number }
  | { readonly kind: 'refused'; readonly problem: DriveProblem };
