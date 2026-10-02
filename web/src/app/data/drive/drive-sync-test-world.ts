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

import { KeysFile } from '../crypto/keys-file';
import type { OpenedKeys, WrittenKeys } from '../crypto/keys-file';
import { sameWatermark, KeysGuard } from '../crypto/keys-guard';
import type { KeysWatermark, KeysWatermarkStore } from '../crypto/keys-guard';
import { WebCryptoProvider } from '../crypto/crypto-provider';
import type { P256PrivateKey } from '../crypto/crypto-provider';
import { kidOf } from '../crypto/folder-key';
import type { PhotoChangeDto } from '../../core/models';
import { DRIVE_LAYOUT, FOLDER_MIME } from './drive-client';
import type { DriveFile } from './drive-client';
import { nextStamp, takesIncoming } from './drive-merge';
import { DriveSyncEngine } from './drive-sync-engine';
import { EMPTY_SYNC_STATE, FolderSession, KIND_SYNC, reportOf, syncDeviceId } from './drive-sync-seams';
import type { DriveSyncState, LocalRows, SyncPassResult, SyncStateStore } from './drive-sync-seams';
import { FakeDriveServer, InMemoryFakeDrive } from './in-memory-fake-drive';
import { syncRow } from './sync-file';
import type { SyncKind, SyncRow } from './sync-file';

/** Test support for the Drive sync (S4b-BL-118; Kotlin `SyncTestWorld.kt`): a fake Drive, real encryption, named devices. */

class MemoryStore implements KeysWatermarkStore {
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

export const newGuard = (p: WebCryptoProvider) => new KeysGuard(p, new MemoryStore());

export function houseRow(id: string, label: string, updatedAt: number, by: string, deleted = false): SyncRow {
  return syncRow('houses', { id, label, lat: 12.9, lon: 77.5, updatedAt: new Date(updatedAt).toISOString(), deleted, by });
}

const rk = (kind: SyncKind, key: string) => `${kind}\u0000${key}`;

export class MemLocal implements LocalRows {
  readonly rows = new Map<string, SyncRow>();
  readonly dirty = new Set<string>();

  async all(): Promise<readonly SyncRow[]> {
    return [...this.rows.values()];
  }

  async photo(photoId: string): Promise<PhotoChangeDto | null> {
    const r = this.rows.get(rk('photos', photoId));
    return r ? ({ syncVersion: 0, ...r.json } as unknown as PhotoChangeDto) : null;
  }

  put(row: SyncRow, markDirty: boolean): void {
    this.rows.set(rk(row.kind, row.key), row);
    if (markDirty) this.dirty.add(rk(row.kind, row.key));
    else this.dirty.delete(rk(row.kind, row.key));
  }

  stamp(id: string) {
    return this.rows.get(rk('houses', id))?.stamp;
  }

  label(id: string): string | null {
    const r = this.rows.get(rk('houses', id));
    return r ? (r.stamp.deleted ? '<deleted>' : (r.json['label'] as string)) : null;
  }

  labels(): Record<string, string> {
    const out: Record<string, string> = {};
    for (const key of [...this.rows.keys()].filter((k) => k.startsWith('houses\u0000')).map((k) => k.slice(7)).sort()) out[key] = this.label(key)!;
    return out;
  }
}

export class SyncWorld {
  readonly p = new WebCryptoProvider();
  readonly keysFile = new KeysFile(this.p);
  readonly devices = new Map<string, TestDevice>();
  keysBytes: Uint8Array = new Uint8Array(0);
  rootId = '';
  private first: TestDevice | null = null;

  constructor(readonly server: FakeDriveServer = new FakeDriveServer()) {
    this.rootId = server.putByHand({ name: 'Doorprints', mimeType: FOLDER_MIME, parents: [], appProperties: DRIVE_LAYOUT.root.appProperties }).id;
  }

  now(): number {
    return this.server.clock.nowMs;
  }

  async add(name: string): Promise<TestDevice> {
    const key = await this.p.p256Generate();
    const nd = { publicKey: key.publicKey, name, platform: 'web' as const };
    const guard = newGuard(this.p);
    let opened: OpenedKeys;
    if (!this.first) {
      const written = await this.keysFile.createFirstDevice(nd, null, this.now());
      await guard.pinCreated(written);
      this.keysBytes = written.bytes;
      opened = written.opened;
    } else {
      const f = this.first;
      const written: WrittenKeys = await this.keysFile.addDevice(f.opened, f.kid, nd, this.now());
      await f.guard.acceptWritten(written);
      f.opened = written.opened;
      this.keysBytes = written.bytes;
      opened = await this.keysFile.openFirstPin(written.bytes, key, guard, written.opened.currentFolderKey());
    }
    const d = new TestDevice(this, name, key, guard, opened);
    this.devices.set(name, d);
    this.first ??= d;
    return d;
  }

  async revoke(by: TestDevice, victim: TestDevice): Promise<void> {
    const written = await this.keysFile.newEpoch(by.opened, this.now(), { revokeKid: victim.kid });
    this.keysBytes = written.bytes;
    await by.guard.acceptWritten(written);
    by.opened = written.opened;
  }

  syncFolderId(): string {
    return this.server.allFiles().find((f) => f.appProperties[DRIVE_LAYOUT.role] === 'sync')!.id;
  }

  syncFiles(): DriveFile[] {
    return this.server.allFiles().filter((f) => f.appProperties[DRIVE_LAYOUT.kind] === KIND_SYNC);
  }
}

export class TestDevice {
  readonly p: WebCryptoProvider;
  readonly kid: Uint8Array;
  readonly id: string;
  readonly local = new MemLocal();
  readonly drive: InMemoryFakeDrive;
  skewMs = 0;
  state: DriveSyncState = EMPTY_SYNC_STATE;
  readonly store: SyncStateStore = {
    load: async () => this.state,
    save: async (s) => {
      this.state = s;
    },
  };

  constructor(readonly world: SyncWorld, readonly name: string, readonly key: P256PrivateKey, readonly guard: KeysGuard, public opened: OpenedKeys) {
    this.p = world.p;
    this.kid = kidOf(this.p, key.publicKey);
    this.id = syncDeviceId(this.kid);
    this.drive = new InMemoryFakeDrive(world.server);
  }

  now(): number {
    return this.world.now() + this.skewMs;
  }

  session(stale = false): FolderSession {
    return new FolderSession(this.world.rootId, this.kid, this.opened, this.guard, stale ? null : async () => (this.opened = await this.world.keysFile.open(this.world.keysBytes, this.key, this.guard)));
  }

  engine(stale = false): DriveSyncEngine {
    return new DriveSyncEngine(this.drive, this.p, this.session(stale), this.store, this.local, () => this.now());
  }

  edit(houseId: string, label: string): void {
    const prev = this.local.stamp(houseId)?.updatedAt;
    this.local.put(houseRow(houseId, label, nextStamp(this.now(), prev), this.id), true);
  }

  delete(houseId: string): void {
    const prev = this.local.rows.get(rk('houses', houseId))!;
    this.local.put(houseRow(houseId, prev.json['label'] as string, nextStamp(this.now(), prev.stamp.updatedAt), this.id, true), true);
  }

  /** The loop in miniature: on success the rows taken are applied by the Drive rule and the pushed rows marked clean (not before). */
  async sync(opts: { confirm?: boolean; stale?: boolean; force?: boolean; engine?: DriveSyncEngine } = {}): Promise<SyncPassResult> {
    const engine = opts.engine ?? this.engine(opts.stale ?? false);
    const result = await engine.run(opts.confirm ?? false, opts.force ?? true);
    const report = reportOf(result);
    if (report) {
      for (const r of report.take) {
        const here = this.local.rows.get(rk(r.kind, r.key))?.stamp;
        if (takesIncoming(here, r.stamp)) this.local.put(r, false);
      }
      this.local.dirty.clear();
    }
    return result;
  }
}
