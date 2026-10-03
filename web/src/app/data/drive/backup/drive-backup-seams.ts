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

import type { P256PrivateKey } from '../../crypto/crypto-provider';
import type { ByteSink, ByteSource } from '../../crypto/dpx';
import type { DevicePlatform, KeysWatermarkStore } from '../../crypto/keys-file';
import type { ControlWatermarkStore } from './control-file';
import type { ScheduleFailure } from './backup-schedule';

/*
 * The platform seams of the Drive backups (S4b-BL-116): what the shared services need from the app and the browser,
 * each a small interface so the services are plain code and the tests run on fakes. The twin of Kotlin's
 * `DriveBackupSeams.kt`.
 */

/**
 * This device in the folder: its key pair (a non-extractable WebCrypto key on the website; S4b-BL-126), and the name
 * and platform it is listed under.
 */
export interface DeviceIdentity {
  readonly key: P256PrivateKey;
  readonly name: string;
  readonly platform: DevicePlatform;
}

/**
 * The device's own Drive bookkeeping (sealed on the device; never in a backup, docs/15 §1.2). Ids are remembered
 * because a listing may lag behind a write (docs/15 §7.1); times are epoch milliseconds.
 */
export interface DriveDeviceState {
  /** A random id for `appProperties device` (never the key id, never a hardware id). */
  readonly deviceId: string | null;
  readonly rootId: string | null;
  readonly backupsId: string | null;
  readonly keysId: string | null;
  readonly controlId: string | null;
  /** This device is creating `rootId`'s key list and has not finished: a retry may start it again. */
  readonly creatingRootId: string | null;
  /** This device's newest uploaded backup. */
  readonly lastBackupId: string | null;
  readonly lastSuccessAt: number | null;
  readonly lastAttemptAt: number | null;
  readonly lastFailure: ScheduleFailure | null;
  readonly lastVerifyAt: number | null;
  /** The newest authenticated `createdAt` this device has seen in the folder: a listing older than it lost a file. */
  readonly newestSeenAt: number | null;
  /** Backups whose drop in houses the person confirmed here (the shrink guard). */
  readonly confirmedDrops: readonly string[];
}

export const EMPTY_DEVICE_STATE: DriveDeviceState = {
  deviceId: null,
  rootId: null,
  backupsId: null,
  keysId: null,
  controlId: null,
  creatingRootId: null,
  lastBackupId: null,
  lastSuccessAt: null,
  lastAttemptAt: null,
  lastFailure: null,
  lastVerifyAt: null,
  newestSeenAt: null,
  confirmedDrops: [],
};

/** Where `DriveDeviceState` lives. One writer at a time (the tab lock). */
export interface DriveStateStore {
  load(): Promise<DriveDeviceState>;
  save(state: DriveDeviceState): Promise<void>;
}

/** The two watermarks of one folder (`rootId`), each with an atomic compare-and-set, sealed on the device. */
export interface FolderTrustStores {
  keys(rootId: string): KeysWatermarkStore;
  control(rootId: string): ControlWatermarkStore;
}

/**
 * The backup to upload: the app's existing *Full backup* ZIP read from `source`. `format` is the ZIP's manifest format
 * (`doorprints-backup/<n>`), which becomes the `dpx/1` inner format; `houses` its live houses (the shrink guard).
 */
export interface BackupPayload {
  readonly source: ByteSource;
  readonly format: string;
  readonly houses: number;
  readonly close?: () => void;
}

/** Makes a `BackupPayload` for one run. */
export type BackupSource = () => Promise<BackupPayload>;

/** A temporary place for one encrypted backup, written once, then read by ranges, then deleted. */
export interface Scratch {
  readonly size: number;
  write(bytes: Uint8Array): void | Promise<void>;
  /** Up to `length` bytes at `position`; fewer (or none) at the end. */
  read(position: number, length: number): Promise<Uint8Array>;
  delete(): void | Promise<void>;
}

/** Makes `Scratch` places (the website: memory for small backups, the origin private file system for large ones). */
export interface ScratchSpace {
  create(): Scratch;
}

class MemoryScratch implements Scratch {
  private buf = new Uint8Array(1024);
  private length = 0;

  get size(): number {
    return this.length;
  }

  write(bytes: Uint8Array): void {
    if (this.length + bytes.length > this.buf.length) {
      const grown = new Uint8Array(Math.max(this.buf.length * 2, this.length + bytes.length));
      grown.set(this.buf.subarray(0, this.length));
      this.buf = grown;
    }
    this.buf.set(bytes, this.length);
    this.length += bytes.length;
  }

  async read(position: number, length: number): Promise<Uint8Array> {
    if (position >= this.length) return new Uint8Array(0);
    return this.buf.slice(position, Math.min(this.length, position + length));
  }

  delete(): void {
    this.buf.fill(0);
    this.buf = new Uint8Array(0);
    this.length = 0;
  }
}

/** A `ScratchSpace` in memory, for tests and for backups that are small anyway (1 to 5 MB without photos). */
export const memoryScratchSpace: ScratchSpace = { create: () => new MemoryScratch() };

/** A `Scratch` read from the start, for the `dpx/1` decryption. */
export function scratchSource(scratch: Scratch, step = 65536 + 16): ByteSource {
  let at = 0;
  return {
    async read(max: number) {
      const b = await scratch.read(at, Math.min(max, step));
      if (b.length === 0) return null;
      at += b.length;
      return b;
    },
  };
}

/**
 * Where an import from Drive writes the decrypted backup ZIP: the same staged file a picked file goes to. `discard`
 * is called on any refusal: what was written is then unconfirmed and must not reach the import (docs/15 §9.9).
 */
export interface StagingSink {
  write: ByteSink;
  discard(): void | Promise<void>;
}
