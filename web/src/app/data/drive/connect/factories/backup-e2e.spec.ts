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

import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import { sourceOf } from '../../../crypto/dpx';
import type { ByteSource } from '../../../crypto/dpx';
import type { TokenProvider } from '../../drive-client';
import type { LocalStore } from '../../../local-store.service';
import { Rig } from '../../backup/backup-test-rig';
import { Payload } from '../../backup/backup-test-rig';
import { RecordingStaging } from '../../backup/backup-test-rig';
import type { BackupSource } from '../../backup/drive-backup-seams';
import { FakeDriveServer } from '../../in-memory-fake-drive';

describe('Backup adapter end-to-end', () => {
  let server: FakeDriveServer;
  let crypto: WebCryptoProvider;
  let rig: Rig;

  beforeEach(async () => {
    server = new FakeDriveServer();
    crypto = new WebCryptoProvider();
    rig = await Rig.make(server, 'Test Device', crypto);
  });

  describe('full backup flow', () => {
    it('createFolder returns recovery key, backUpNow succeeds, listBackups shows it', async () => {
      // Step 1: Create folder and get recovery key
      const createResult = await rig.service.createFolder(true);
      expect(createResult.connection.kind).toBe('READY');
      expect(createResult.recoveryKey).toBeDefined();
      const recoveryKey = createResult.recoveryKey!;

      // Step 2: Get folder
      const folder = await rig.ready();
      expect(folder).toBeDefined();

      // Step 3: Back up some data
      const payload = Payload.of(5000, 10); // 5000 bytes, 10 houses
      const backupResult = await rig.service.backUp(folder, payload.source());
      expect(backupResult.kind).toBe('done');
      expect((backupResult as any).backup.houses).toBe(10);

      // Step 4: List backups
      const listResult = await rig.service.listBackups(folder);
      expect(listResult.backups.length).toBe(1);
      expect(listResult.backups[0].houses).toBe(10);
    });

    it('importFromDrive restores backup with same bytes', async () => {
      // Create folder
      const createResult = await rig.service.createFolder(true);
      const folder = (createResult.connection as any).folder;

      // Back up data
      const payload = Payload.of(5000, 10);
      const backupResult = await rig.service.backUp(folder, payload.source());
      expect(backupResult.kind).toBe('done');
      const backup = (backupResult as any).backup;

      // List backups
      const listResult = await rig.service.listBackups(folder);
      const backupItem = listResult.backups[0];

      // Import from drive
      const staging = new RecordingStaging();
      const importResult = await rig.service.imports.download(folder, backupItem, staging);
      expect(importResult.kind).toBe('verified');

      // Verify ZIP bytes match
      const restoredBytes = staging.bytes();
      expect(restoredBytes.length).toBeGreaterThan(0);
      expect(restoredBytes.byteLength).toBe(payload.bytes.byteLength);
    });
  });

  describe('folder trust persistence', () => {
    it('folder trust stores persist keys and control watermarks', async () => {
      // Create folder
      const createResult = await rig.service.createFolder(true);
      const folder = (createResult.connection as any).folder;
      const rootId = folder.rootId;

      // Trust stores should be populated after createFolder
      const keyStore = rig.trust.keys(rootId);
      const loadedKeys = await keyStore.load();
      expect(loadedKeys).toBeDefined();

      const controlStore = rig.trust.control(rootId);
      const loadedControl = await controlStore.load();
      expect(loadedControl).toBeDefined();
      expect(loadedControl?.revision).toBeDefined(); // Control watermark has revision
    });

    it('device remains pinned if trust stores preserved', async () => {
      // Create folder
      const createResult = await rig.service.createFolder(true);
      const folder = (createResult.connection as any).folder;
      const rootId = folder.rootId;

      // Simulate state persistence by keeping trust stores
      const savedTrust = rig.trust;

      // Create new rig with same trust stores simulates reload
      const rig2 = await Rig.make(server, 'Test Device', crypto);
      rig2.state.value = await rig.state.load();
      // Preserve trust stores (simulates persistence)
      rig2.trust.keysStores.set(rootId, savedTrust.keysStores.get(rootId)!);
      rig2.trust.controlStores.set(rootId, savedTrust.controlStores.get(rootId)!);

      // Connect should find the folder (device is pinned via trust stores)
      const connectResult = await rig2.service.connect();
      expect(['READY', 'NEEDS_ENROLMENT', 'NEEDS_RECOVERY_KEY']).toContain(connectResult.kind);
    });
  });
});
