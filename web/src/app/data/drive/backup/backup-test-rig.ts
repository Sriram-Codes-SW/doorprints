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

import { WebCryptoProvider } from '../../crypto/crypto-provider';
import type { CryptoProvider, P256PrivateKey } from '../../crypto/crypto-provider';
import type { ByteSource } from '../../crypto/dpx';
import { sourceOf } from '../../crypto/dpx';
import { patternBytes } from '../../crypto/fake-random';
import type { KeysWatermark, KeysWatermarkStore } from '../../crypto/keys-guard';
import { sameWatermark } from '../../crypto/keys-guard';
import { DRIVE_LAYOUT } from '../drive-client';
import type { DriveFile } from '../drive-client';
import { InMemoryFakeDrive } from '../in-memory-fake-drive';
import type { FakeDriveServer } from '../in-memory-fake-drive';
import { sameControlWatermark } from './control-file';
import type { ControlWatermark, ControlWatermarkStore } from './control-file';
import { EMPTY_DEVICE_STATE } from './drive-backup-seams';
import type { BackupSource, DeviceIdentity, DriveDeviceState, DriveStateStore, FolderTrustStores } from './drive-backup-seams';
import type { DriveConnection, ReadyFolder } from './drive-backup-results';
import { DriveBackupService } from './drive-backup.service';

/** In-memory seams and a ready-made "device" for the Drive backup specs (S4b-BL-116). Test code only. */

export class MemoryStateStore implements DriveStateStore {
  value: DriveDeviceState = EMPTY_DEVICE_STATE;
  async load() {
    return this.value;
  }
  async save(state: DriveDeviceState) {
    this.value = state;
  }
}

export class MemoryControlStore implements ControlWatermarkStore {
  constructor(public value: ControlWatermark | null = null) {}
  async load() {
    return this.value;
  }
  async compareAndSet(expected: ControlWatermark | null, next: ControlWatermark) {
    if (!sameControlWatermark(this.value, expected)) return false;
    this.value = next;
    return true;
  }
}

export class MemoryKeysStore implements KeysWatermarkStore {
  value: KeysWatermark | null = null;
  async load() {
    return this.value;
  }
  async compareAndSet(expected: KeysWatermark | null, next: KeysWatermark) {
    if (!sameWatermark(this.value, expected)) return false;
    this.value = next;
    return true;
  }
}

export class MemoryTrust implements FolderTrustStores {
  readonly keysStores = new Map<string, MemoryKeysStore>();
  readonly controlStores = new Map<string, MemoryControlStore>();
  keys(rootId: string) {
    if (!this.keysStores.has(rootId)) this.keysStores.set(rootId, new MemoryKeysStore());
    return this.keysStores.get(rootId)!;
  }
  control(rootId: string) {
    if (!this.controlStores.has(rootId)) this.controlStores.set(rootId, new MemoryControlStore());
    return this.controlStores.get(rootId)!;
  }
}

/** A staging sink that records what was written and whether it was discarded. */
export class RecordingStaging {
  private parts: Uint8Array[] = [];
  discards = 0;
  write = (bytes: Uint8Array) => {
    this.parts.push(bytes.slice());
  };
  discard() {
    this.discards++;
    this.parts = [];
  }
  bytes(): Uint8Array {
    const out = new Uint8Array(this.parts.reduce((n, p) => n + p.length, 0));
    let at = 0;
    for (const p of this.parts) {
      out.set(p, at);
      at += p.length;
    }
    return out;
  }
}

/** What a device's backup ZIP is in the specs: bytes the Drive layer never looks into. */
export class Payload {
  constructor(
    readonly bytes: Uint8Array,
    readonly houses: number,
    readonly format = 'doorprints-backup/1',
  ) {}

  static of(size: number, houses: number, seed = 0): Payload {
    return new Payload(patternBytes(size + seed).slice(seed, size + seed), houses);
  }

  source(): BackupSource {
    return async () => ({ source: sourceOf(this.bytes) as ByteSource, format: this.format, houses: this.houses });
  }
}

/** One device of one person: its own key, state, watermarks and client, on the shared server. */
export class Rig {
  readonly drive: InMemoryFakeDrive;
  readonly state = new MemoryStateStore();
  readonly trust = new MemoryTrust();
  offset = 330;
  identity!: DeviceIdentity;
  service!: DriveBackupService;

  private constructor(
    readonly server: FakeDriveServer,
    readonly p: CryptoProvider,
  ) {
    this.drive = new InMemoryFakeDrive(server);
  }

  static async make(server: FakeDriveServer, name = 'Pixel 8', p: CryptoProvider = new WebCryptoProvider()): Promise<Rig> {
    const rig = new Rig(server, p);
    const key: P256PrivateKey = await p.p256Generate();
    rig.identity = { key, name, platform: 'web' };
    rig.service = new DriveBackupService(rig.drive, p, rig.identity, rig.state, rig.trust, () => server.clock.now(), () => rig.offset);
    return rig;
  }

  async created(): Promise<ReadyFolder> {
    const out = await this.service.createFolder(true);
    return (out.connection as Extract<DriveConnection, { kind: 'READY' }>).folder;
  }

  async ready(): Promise<ReadyFolder> {
    const c = await this.service.connect();
    if (c.kind !== 'READY') throw new Error(`not ready: ${c.kind}`);
    return c.folder;
  }

  backupsFolder(): DriveFile {
    return this.server.allFiles().find((f) => f.appProperties[DRIVE_LAYOUT.role] === 'backups')!;
  }

  /** Drive files in `Backups/` that are not in the bin. */
  live(): DriveFile[] {
    const id = this.backupsFolder().id;
    return this.server.allFiles().filter((f) => f.parents.includes(id) && !f.trashed);
  }

  binned(): DriveFile[] {
    const id = this.backupsFolder().id;
    return this.server.allFiles().filter((f) => f.parents.includes(id) && f.trashed);
  }
}
