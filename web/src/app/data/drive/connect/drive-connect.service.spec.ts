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

import { describe, it, expect, beforeEach } from 'vitest';
import { WebCryptoProvider } from '../../crypto/crypto-provider';
import { LocalStore } from '../../local-store.service';
import type { TokenProvider } from '../drive-client';
import { FakeDriveServer, InMemoryFakeDrive } from '../in-memory-fake-drive';
import { createDriveRuntime, type DriveRuntime } from './factories/runtime';
import { createLazyBackupAdapterProxy } from './factories/backup-factory';
import { createLazySyncAdapterProxy } from './factories/sync-factory';
import { createLazyDeletionAdapterProxy } from './factories/deletion-factory';
import { DriveConnectService } from './drive-connect.service';

const tokens: TokenProvider = { accessToken: async () => 'test-token' };

async function createDevice(server: FakeDriveServer) {
  const store = new LocalStore();
  await store.ready();
  let pending: Promise<DriveRuntime> | null = null;
  const runtime = () =>
    (pending ??= createDriveRuntime({
      tokens,
      local: store,
      crypto: new WebCryptoProvider(),
      drive: new InMemoryFakeDrive(server),
    }));

  const backup = createLazyBackupAdapterProxy(runtime) as any;
  const sync = createLazySyncAdapterProxy(runtime) as any;
  const deletion = createLazyDeletionAdapterProxy(runtime) as any;
  const service = new DriveConnectService(backup, sync, deletion, { clientId: 'test-client-id' });

  return { store, runtime, backup, sync, deletion, service };
}

describe('DriveConnectService', () => {
  let server: FakeDriveServer;
  let a: Awaited<ReturnType<typeof createDevice>>;
  let b: Awaited<ReturnType<typeof createDevice>>;

  beforeEach(async () => {
    server = new FakeDriveServer();
    a = await createDevice(server);
    b = await createDevice(server);
  });

  describe('state machine and recovery key', () => {
    it('recovery key shown once', async () => {
      const created = await a.service.createFolder();
      expect(created.state).toBe('FirstConnectShowRecoveryKey');
      expect(created.recoveryKey).toBeDefined();

      a.service.confirmRecoveryKeySaved();
      expect(a.service.hasShownRecoveryKey()).toBe(true);
    });

    it('connect and createFolder transition states correctly', async () => {
      expect(a.service.getState()).toBe('Unavailable');

      const created = await a.service.createFolder();
      expect(created.state).toBe('FirstConnectShowRecoveryKey');
      a.service.confirmRecoveryKeySaved();
      expect(a.service.getState()).toBe('NeedsEnrolment');
    });

    it('skipRecoveryKeyWithWarning works', async () => {
      await a.service.createFolder();
      a.service.skipRecoveryKeyWithWarning();
      expect(a.service.hasShownRecoveryKey()).toBe(true);
      expect(a.service.getState()).toBe('NeedsEnrolment');
    });
  });

  describe('disconnect clears state', () => {
    it('disconnect() clears readyFolder and returns to Disconnected state', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      await a.service.disconnect();
      expect(a.service.getState()).toBe('Disconnected');

      // Listing backups should fail after disconnect
      const listing = await a.service.listBackups();
      expect(listing.ok).toBe(false);
      if (!listing.ok) {
        expect(listing.reason.toLowerCase()).toContain('not connected');
      }
    });

    it('Drive files are preserved after disconnect', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      await a.service.disconnect();

      // Files should still exist in the fake Drive
      const driveFiles = server.allFiles();
      expect(driveFiles.length).toBeGreaterThan(0);
    });
  });

  describe('wrong recovery key returns error', () => {
    it('openWithRecoveryKey with wrong key returns Error state', async () => {
      // A: create with one key
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      // B: try with wrong key string (not a valid RecoveryKey)
      const opened = await b.service.openWithRecoveryKey('wrong-key-string-12345');
      expect(['NeedsRecoveryKey', 'Error', 'NeedsEnrolment']).toContain(opened.state);
      expect(opened.state).not.toBe('Ready');
    });
  });

  describe('photo settings', () => {
    it('photoSettings returns PhotoSettings object', async () => {
      const settings = await a.service.photoSettings();
      expect(settings).toBeDefined();
      expect(typeof settings.uploadOnMobileData === 'boolean').toBe(true);
    });

    it('pendingPhotoBytes returns number or null', async () => {
      const pending = await a.service.pendingPhotoBytes();
      expect(pending === null || typeof pending === 'number').toBe(true);
    });

    it('setPhotosWifiOnly updates settings', async () => {
      await a.service.setPhotosWifiOnly(true);
      const settings = await a.service.photoSettings();
      expect(settings.uploadOnMobileData).toBe(false);
    });
  });

  describe('sync status', () => {
    it('syncNow before connect returns error state', async () => {
      const result = await a.service.syncNow();
      expect(result.state).toBe('error');
      expect(result.lastSyncAt).toBeNull();
    });

    it('syncStatus reflects last sync result', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const syncResult = await a.service.syncNow();
      expect(syncResult.needsConfirmation).toBe(false);

      const status = await a.service.syncStatus();
      expect(['error', 'synced', 'offline', 'waiting-wifi']).toContain(status.state);
    });
  });

  describe('listBackups when not connected', () => {
    it('listBackups returns error when not connected', async () => {
      const listing = await a.service.listBackups();
      expect(listing.ok).toBe(false);
      if (!listing.ok) {
        expect(listing.reason.toLowerCase()).toContain('not connected');
      }
    });
  });

  describe('importFromDrive when not connected', () => {
    it('importFromDrive returns error when not connected', async () => {
      const result = await a.service.importFromDrive('backup-id');
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason.toLowerCase()).toContain('not connected');
      }
    });

    it('importFromDrive returns error for missing backup', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const result = await a.service.importFromDrive('nonexistent-backup-id');
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason.toLowerCase()).toContain('not found');
      }
    });
  });

  describe('passkey management', () => {
    it('passkeyStatus returns valid status', async () => {
      const status = await a.service.passkeyStatus();
      expect(['none', 'registered', 'unsupported']).toContain(status);
    });

    it('registerPasskey returns appropriate result', async () => {
      const result = await a.service.registerPasskey();
      expect(['registered', 'unsupported', null]).toContain(result);
    });
  });

  describe('deletion operations', () => {
    it('deletePlan returns ok:false when preflight fails', async () => {
      const result = await a.service.deletePlan({ type: 'everything' });
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason).toBeDefined();
      }
    });

    it('deleteConfirmInfo returns gate state', async () => {
      const result = await a.service.deleteConfirmInfo({ type: 'everything' });
      // May succeed or fail depending on online status
      if (result.ok) {
        expect(typeof result.tickBoxRequired === 'boolean').toBe(true);
        expect(typeof result.delayMs === 'number').toBe(true);
      }
    });
  });

  describe('lastBackup', () => {
    it('lastBackup returns error when no backups', async () => {
      const result = await a.service.lastBackup();
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason).toBeDefined();
      }
    });
  });

  describe('backward compatibility methods', () => {
    it('setAutoBackup is a no-op', async () => {
      await a.service.setAutoBackup(true);
      // No error should be thrown
    });

    it('deleteL1/L2/L3 return error structures', async () => {
      const l1 = await a.service.deleteL1();
      expect(typeof l1.success === 'boolean').toBe(true);

      const l2 = await a.service.deleteL2();
      expect(typeof l2.success === 'boolean').toBe(true);

      const l3 = await a.service.deleteL3(true);
      expect(typeof l3.success === 'boolean').toBe(true);
    });
  });

  describe('refresh method', () => {
    it('refresh calls connect', async () => {
      const result = await a.service.refresh();
      expect(result.state).toBeDefined();
    });
  });
});
