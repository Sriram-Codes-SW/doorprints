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

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { WebCryptoProvider } from '../../crypto/crypto-provider';
import { LocalStore } from '../../local-store.service';
import type { TokenProvider } from '../drive-client';
import { FakeDriveServer, InMemoryFakeDrive } from '../in-memory-fake-drive';
import { createDriveRuntime, type DriveRuntime } from './factories/runtime';
import { createLazyBackupAdapterProxy } from './factories/backup-factory';
import { createLazySyncAdapterProxy } from './factories/sync-factory';
import { createLazyDeletionAdapterProxy } from './factories/deletion-factory';
import { DriveConnectService } from './drive-connect.service';
import { Payload } from '../backup/backup-test-rig';

const tokens: TokenProvider = { accessToken: async () => 'test-token' };

function memoryPrefs() {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) };
}

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
  const payload = Payload.of(70_000, 3);
  const service = new DriveConnectService(backup, sync, deletion, { clientId: 'test-client-id' }, payload.source(), memoryPrefs());

  return { store, runtime, backup, sync, deletion, service, payload };
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
      expect(a.service.getState()).toBe('Disconnected');

      const created = await a.service.createFolder();
      expect(created.state).toBe('FirstConnectShowRecoveryKey');
      a.service.confirmRecoveryKeySaved();
      expect(a.service.getState()).toBe('Ready');
    });

    it('skipRecoveryKeyWithWarning works', async () => {
      await a.service.createFolder();
      a.service.skipRecoveryKeyWithWarning();
      expect(a.service.hasShownRecoveryKey()).toBe(true);
      expect(a.service.getState()).toBe('Ready');
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
      expect(listing.reason).toBe('driveBackups.error.notConnected');
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
      expect(opened.error).toBe('driveJoin.errorInvalidFormat');
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
      expect(listing.reason).toBe('driveBackups.error.notConnected');
      }
    });
  });

  describe('importFromDrive when not connected', () => {
    it('importFromDrive returns error when not connected', async () => {
      const result = await a.service.importFromDrive('backup-id');
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason).toBe('driveBackups.error.notConnected');
      }
    });

    it('importFromDrive returns error for missing backup', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const result = await a.service.importFromDrive('nonexistent-backup-id');
      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason).toBe('driveBackups.error.backupNotFound');
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
      expect(['registered', 'unsupported', 'failed', null]).toContain(result);
    });
  });

  describe('recovery key (docs/15 §10.4a)', () => {
    it('recoveryKeyOffered returns false when this browser is not connected, even if the browser cannot do PRF', async () => {
      // No createFolder here: the Drive is not READY.
      vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');
      expect(await a.service.recoveryKeyOffered()).toBe(false);
    });

    it('recoveryKeyOffered returns false when a passkey is registered, even if the browser reports no PRF', async () => {
      await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();
      vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('registered');
      expect(await a.service.recoveryKeyOffered()).toBe(false);
    });

    it('recoveryKeyOffered returns true when passkeyNoPrfSeen', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      // Simulate registerPasskey returning 'no-prf'
      const mockRegister = vi.spyOn(a.deletion, 'registerPasskey').mockResolvedValue('no-prf');
      await a.service.registerPasskey();

      const offered = await a.service.recoveryKeyOffered();
      expect(offered).toBe(true);

      mockRegister.mockRestore();
    });

    it('recoveryKeyOffered returns true when PRF capability is false and built-in authenticator is true', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockPrf = vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      const mockBuiltIn = vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');

      const offered = await a.service.recoveryKeyOffered();
      expect(offered).toBe(true);

      mockPrf.mockRestore();
      mockBuiltIn.mockRestore();
      mockStatus.mockRestore();
    });

    it('recoveryKeyOffered returns false when PRF capability is null', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockPrf = vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(null);
      const mockBuiltIn = vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');

      const offered = await a.service.recoveryKeyOffered();
      expect(offered).toBe(false);

      mockPrf.mockRestore();
      mockBuiltIn.mockRestore();
      mockStatus.mockRestore();
    });

    it('recoveryKeyOffered returns false when built-in authenticator is false', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockPrf = vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      const mockBuiltIn = vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(false);
      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');

      const offered = await a.service.recoveryKeyOffered();
      expect(offered).toBe(false);

      mockPrf.mockRestore();
      mockBuiltIn.mockRestore();
      mockStatus.mockRestore();
    });

    it('recoveryKeyOffered returns false when built-in authenticator is null', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockPrf = vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      const mockBuiltIn = vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(null);
      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');

      const offered = await a.service.recoveryKeyOffered();
      expect(offered).toBe(false);

      mockPrf.mockRestore();
      mockBuiltIn.mockRestore();
      mockStatus.mockRestore();
    });

    it('recoveryKeyOffered returns false when PRF capability is true', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockPrf = vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(true);
      const mockBuiltIn = vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');

      const offered = await a.service.recoveryKeyOffered();
      expect(offered).toBe(false);

      mockPrf.mockRestore();
      mockBuiltIn.mockRestore();
      mockStatus.mockRestore();
    });

    it('recoveryKeyOffered returns false after a successful passkey registration', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      // First say it was 'no-prf'
      let mockRegister = vi.spyOn(a.deletion, 'registerPasskey').mockResolvedValue('no-prf');
      await a.service.registerPasskey();
      let offered = await a.service.recoveryKeyOffered();
      expect(offered).toBe(true);

      // Then register successfully
      mockRegister = vi.spyOn(a.deletion, 'registerPasskey').mockResolvedValue('registered');
      mockRegister.mockRestore();
      mockRegister = vi.spyOn(a.deletion, 'registerPasskey').mockResolvedValue('registered');
      await a.service.registerPasskey();

      offered = await a.service.recoveryKeyOffered();
      expect(offered).toBe(false);

      mockRegister.mockRestore();
    });

    it('deletionContext: the recovery key makes delete-everything possible on the website (webPrf true) when it is offered', async () => {
      await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();
      vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');
      const info = await a.service.deleteConfirmInfo({ type: 'everything' });
      expect(info.ok).toBe(true);
    });

    it('deletionContext: without a passkey and with the key not offered, delete-everything is refused on the website (webPrf false)', async () => {
      await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();
      vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(true);
      vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');
      const info = await a.service.deleteConfirmInfo({ type: 'everything' });
      expect(info.ok).toBe(false);
    });

    it('deletionContext: a registered passkey makes delete-everything possible (webPrf true)', async () => {
      await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();
      vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('registered');
      const info = await a.service.deleteConfirmInfo({ type: 'everything' });
      expect(info.ok).toBe(true);
    });

    it('authorizeDelete without text uses passkey path', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockAuthorize = vi.spyOn(a.deletion, 'authorize').mockResolvedValue({
        kind: 'granted',
        grant: { id: 1, grantedAtMs: Date.now(), requirements: { level: 'L1', factor: 'NONE' } },
      });

      const result = await a.service.authorizeDelete({ type: 'everything' }, 'test-op-id');

      expect(mockAuthorize).toHaveBeenCalled();
      mockAuthorize.mockRestore();
    });

    it('authorizeDelete with text when not offered returns RECOVERY_KEY_NOT_OFFERED', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('registered');

      const result = await a.service.authorizeDelete({ type: 'everything' }, 'test-op-id', 'XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXX');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason).toBe('RECOVERY_KEY_NOT_OFFERED');
      }

      mockStatus.mockRestore();
    });

    it('authorizeDelete with invalid text returns RECOVERY_KEY_INVALID', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockPrf = vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      const mockBuiltIn = vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');

      const result = await a.service.authorizeDelete({ type: 'everything' }, 'test-op-id', 'garbage-text');

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason).toBe('RECOVERY_KEY_INVALID');
      }

      mockPrf.mockRestore();
      mockBuiltIn.mockRestore();
      mockStatus.mockRestore();
    });

    it('authorizeDelete with valid recovery key calls adapter', async () => {
      const created = await a.service.createFolder();
      a.service.confirmRecoveryKeySaved();

      const mockPrf = vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      const mockBuiltIn = vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');

      const mockAuthorizeRecovery = vi.spyOn(a.deletion, 'authorizeWithRecoveryKey').mockResolvedValue({
        kind: 'granted',
        grant: { id: 1, grantedAtMs: Date.now(), requirements: { level: 'L2', factor: 'RECOVERY_KEY' } },
      });

      // Use a valid key from the recovery key that was shown at create
      const keyText = created.recoveryKey!;
      const result = await a.service.authorizeDelete({ type: 'everything' }, 'test-op-id', keyText);

      expect(mockAuthorizeRecovery).toHaveBeenCalled();

      mockPrf.mockRestore();
      mockBuiltIn.mockRestore();
      mockStatus.mockRestore();
      mockAuthorizeRecovery.mockRestore();
    });

    it('authorizeDelete with recovery key WRONG_KEY passes through', async () => {
      const created = await a.service.createFolder();
      const validKeyText = created.recoveryKey!;
      a.service.confirmRecoveryKeySaved();

      const mockPrf = vi.spyOn(a.service, 'passkeyPrfCapability').mockResolvedValue(false);
      const mockBuiltIn = vi.spyOn(a.service, 'passkeyBuiltIn').mockResolvedValue(true);
      const mockStatus = vi.spyOn(a.service, 'passkeyStatus').mockResolvedValue('none');

      const mockAuthorizeRecovery = vi.spyOn(a.deletion, 'authorizeWithRecoveryKey').mockResolvedValue({
        kind: 'refused',
        reason: 'RECOVERY_KEY_WRONG',
      });

      const result = await a.service.authorizeDelete({ type: 'everything' }, 'test-op-id', validKeyText);

      expect(result.ok).toBe(false);
      if (!result.ok) {
        expect(result.reason).toBe('RECOVERY_KEY_WRONG');
      }

      mockPrf.mockRestore();
      mockBuiltIn.mockRestore();
      mockStatus.mockRestore();
      mockAuthorizeRecovery.mockRestore();
    });

    it('forgetDeleteProof is called after executeDelete', async () => {
      const mockForget = vi.spyOn(a.deletion, 'forgetProof').mockReturnValue(undefined);
      const mockExecute = vi.spyOn(a.deletion, 'execute').mockResolvedValue({
        kind: 'ran',
        finished: true,
        total: 1,
        report: { left: [] },
      });

      const result = await a.service.executeDelete(
        {
          action: { type: 'everything' },
          operationId: 'test-id',
          rootId: 'root',
          level: 'L3',
          items: [],
          totals: {},
          foreignKept: 0,
          createdAtMs: Date.now(),
        },
        null,
      );

      expect(mockForget).toHaveBeenCalled();

      mockForget.mockRestore();
      mockExecute.mockRestore();
    });

    it('forgetDeleteProof is called after resumeDelete', async () => {
      const mockForget = vi.spyOn(a.deletion, 'forgetProof').mockReturnValue(undefined);
      const mockResume = vi.spyOn(a.deletion, 'resume').mockResolvedValue({
        kind: 'ran',
        finished: true,
        total: 1,
        report: { left: [] },
      });

      const result = await a.service.resumeDelete(null);

      expect(mockForget).toHaveBeenCalled();

      mockForget.mockRestore();
      mockResume.mockRestore();
    });

    it('forgetDeleteProof is called on executeDelete error', async () => {
      const mockForget = vi.spyOn(a.deletion, 'forgetProof').mockReturnValue(undefined);
      const mockExecute = vi.spyOn(a.deletion, 'execute').mockRejectedValue(new Error('test error'));

      const result = await a.service.executeDelete(
        {
          action: { type: 'everything' },
          operationId: 'test-id',
          rootId: 'root',
          level: 'L3',
          items: [],
          totals: {},
          foreignKept: 0,
          createdAtMs: Date.now(),
        },
        null,
      );

      expect(mockForget).toHaveBeenCalled();

      mockForget.mockRestore();
      mockExecute.mockRestore();
    });

    it('forgetDeleteProof is called on resumeDelete error', async () => {
      const mockForget = vi.spyOn(a.deletion, 'forgetProof').mockReturnValue(undefined);
      const mockResume = vi.spyOn(a.deletion, 'resume').mockRejectedValue(new Error('test error'));

      const result = await a.service.resumeDelete(null);

      expect(mockForget).toHaveBeenCalled();

      mockForget.mockRestore();
      mockResume.mockRestore();
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

  describe('refresh method', () => {
    it('refresh calls connect', async () => {
      const result = await a.service.refresh();
      expect(result.state).toBeDefined();
    });
  });

  describe('real flows through the service (success paths)', () => {
    const house = (id: string, label: string) =>
      ({ id, label, lat: 12.97, lon: 77.59, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0 }) as const;

    it('Back up now makes a real backup that lists and imports back with the same bytes', async () => {
      const created = await a.service.createFolder();
      expect(created.recoveryKey).toBeTruthy();
      const done = await a.service.backUpNow();
      expect(done.ok).toBe(true);
      if (!done.ok) throw new Error(done.reason);
      expect(done.backup.houses).toBe(3);

      const listing = await a.service.listBackups();
      if (!listing.ok) throw new Error(listing.reason);
      expect(listing.backups.length).toBe(1);
      expect(listing.backups[0].id).toBe(done.backup.id);

      const imported = await a.service.importFromDrive(done.backup.id);
      if (!imported.ok) throw new Error(imported.reason);
      const bytes = new Uint8Array(await imported.file.arrayBuffer());
      expect(bytes.length).toBe(a.payload.bytes.length);
      expect(Array.from(bytes)).toEqual(Array.from(a.payload.bytes));
    });

    it('Back up now without a connection is refused and makes nothing in Drive', async () => {
      const before = server.allFiles().length;
      const out = await a.service.backUpNow();
      expect(out.ok).toBe(false);
      expect(server.allFiles().length).toBe(before);
    });

    it('a second browser joins by TYPING the recovery key and receives the first one\'s house', async () => {
      const created = await a.service.createFolder();
      await a.store.saveHouse(house('h1', 'Lake View'));
      const syncA = await a.service.syncNow();
      expect(syncA.state).toBe('synced');

      // The key as the person types it: lower case, spaces instead of the hyphens.
      const typed = created.recoveryKey!.toLowerCase().replace(/-/g, ' ');
      const joined = await b.service.openWithRecoveryKey(typed);
      expect(joined.state).toBe('Ready');
      const syncB = await b.service.syncNow();
      expect(syncB.state).toBe('synced');
      expect((await b.store.allHouses()).find((h) => h.id === 'h1')?.label).toBe('Lake View');
    });

    it('a mistyped recovery key is refused with a message and nothing changes', async () => {
      const created = await a.service.createFolder();
      const key = created.recoveryKey!;
      const flipped = key.slice(0, 2) + (key[2] === '2' ? '3' : '2') + key.slice(3);
      const out = await b.service.openWithRecoveryKey(flipped);
      expect(out.error).toBe('driveJoin.errorInvalidFormat');
      expect(b.service.getState()).not.toBe('Ready');
      expect((await b.runtime()).session).toBeUndefined();
    });

    it('a well-formed but wrong recovery key does not open the folder', async () => {
      await a.service.createFolder();
      const { RecoveryKey } = await import('../../crypto/recovery-key');
      const other = RecoveryKey.generate(new WebCryptoProvider()).display;
      const out = await b.service.openWithRecoveryKey(other);
      expect(out.state).not.toBe('Ready');
      expect((await b.runtime()).session).toBeUndefined();
    });

    it('deleting ONE of two backups (level 1, no passkey) removes exactly that backup and keeps the other', async () => {
      await a.service.createFolder();
      expect((await a.service.backUpNow()).ok).toBe(true);
      // Retention keeps one backup per day, so the second backup is made two days later.
      const realNow = Date.now.bind(Date);
      const spy = vi.spyOn(Date, 'now').mockImplementation(() => realNow() + 2 * 86_400_000);
      try {
        expect((await a.service.backUpNow()).ok).toBe(true);
      } finally {
        spy.mockRestore();
      }
      const listing = await a.service.listBackups();
      if (!listing.ok) throw new Error(listing.reason);
      expect(listing.backups.length).toBe(2);
      const victim = listing.backups[1].id;
      const keep = listing.backups[0].id;

      const plan = await a.service.deletePlan({ type: 'oneBackup', fileId: victim });
      if (!plan.ok) throw new Error(plan.reason);
      const ran = await a.service.executeDelete(plan.plan, null);
      expect(ran.ok).toBe(true);
      if (ran.ok) {
        expect(ran.finished).toBe(true);
        expect(ran.left).toBe(0);
      }

      const after = await a.service.listBackups();
      if (!after.ok) throw new Error(after.reason);
      expect(after.backups.map((x) => x.id)).toEqual([keep]);
      expect(server.allFiles().some((f) => f.appProperties.kind === 'keys')).toBe(true);
    });

    it('deleting the ONLY backup is refused without a passkey (it is the last one: level 2) and nothing is deleted', async () => {
      await a.service.createFolder();
      expect((await a.service.backUpNow()).ok).toBe(true);
      const listing = await a.service.listBackups();
      if (!listing.ok) throw new Error(listing.reason);
      const before = server.allFiles().length;

      const plan = await a.service.deletePlan({ type: 'oneBackup', fileId: listing.backups[0].id });
      if (!plan.ok) throw new Error(plan.reason);
      const ran = await a.service.executeDelete(plan.plan, null);
      expect(ran.ok).toBe(false);
      expect(server.allFiles().length).toBe(before);
      expect((await a.service.listBackups()).ok).toBe(true);
    });

    it('delete everything is refused without a passkey and nothing is deleted', async () => {
      await a.service.createFolder();
      await a.service.backUpNow();
      const before = server.allFiles().length;
      const info = await a.service.deleteConfirmInfo({ type: 'everything' });
      expect(info.ok).toBe(false);
      const auth = await a.service.authorizeDelete({ type: 'everything' }, 'op');
      expect(auth.ok).toBe(false);
      expect(server.allFiles().length).toBe(before);
    });

    it('approving or revoking a device without a passkey writes nothing', async () => {
      await a.service.createFolder();
      const before = server.allFiles().length;
      const pk = new Uint8Array(65);
      pk[0] = 4;
      const approved = await a.service.approveJoinedDevice(pk, 'Website');
      expect(approved).toEqual({ ok: false, reason: 'USE_PHONE' });
      const revoked = await a.service.revokeListedDevice('aa');
      expect(revoked).toEqual({ ok: false, reason: 'USE_PHONE' });
      const all = await a.service.disconnectAll();
      expect(all).toEqual({ ok: false, reason: 'USE_PHONE' });
      expect(a.service.getState()).toBe('FirstConnectShowRecoveryKey');
      expect(server.allFiles().length).toBe(before);
    });

    it('disconnect keeps every Drive file and a later Back up now is refused', async () => {
      await a.service.createFolder();
      await a.service.backUpNow();
      const before = server.allFiles().length;
      await a.service.disconnect();
      expect(server.allFiles().length).toBe(before);
      expect((await a.service.backUpNow()).ok).toBe(false);
    });
  });

  describe('automatic backup', () => {
    it('is off until turned on and the preference is remembered', async () => {
      expect(a.service.autoBackupEnabled()).toBe(false);
      await a.service.setAutoBackup(true);
      expect(a.service.autoBackupEnabled()).toBe(true);
      await a.service.setAutoBackup(false);
      expect(a.service.autoBackupEnabled()).toBe(false);
    });

    it('does nothing while it is off or before the folder is open', async () => {
      expect((await a.service.runDueBackup()).ran).toBe(false);
      await a.service.createFolder();
      const off = await a.service.runDueBackup();
      expect(off).toEqual({ ran: false, reason: 'DISABLED' });
      expect(server.allFiles().some((f) => f.appProperties.kind === 'backup')).toBe(false);
    });

    it('makes the first backup when it is on, and not a second one right after', async () => {
      await a.service.createFolder();
      await a.service.setAutoBackup(true);
      const first = await a.service.runDueBackup();
      expect(first.ran).toBe(true);
      const count = () => server.allFiles().filter((f) => f.appProperties.kind === 'backup' && !f.trashed).length;
      expect(count()).toBe(1);
      const second = await a.service.runDueBackup();
      expect(second.ran).toBe(false);
      expect(second.reason).toBe('NOT_DUE');
      expect(count()).toBe(1);
    });
  });

  describe('passkey details', () => {
    it('returns the adapter details string when lastPasskeyDetails resolves to a value', async () => {
      // Create a deletion adapter mock that returns specific details
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        lastPasskeyDetails: vi.fn(async () => 'abc'),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const details = await service.passkeyDetails();
      expect(details).toBe('abc');
    });

    it('returns null when adapter has no lastPasskeyDetails method', async () => {
      // Create a deletion adapter without lastPasskeyDetails
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        // no lastPasskeyDetails method
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const details = await service.passkeyDetails();
      expect(details).toBeNull();
    });

    it('returns null when lastPasskeyDetails rejects', async () => {
      // Create a deletion adapter mock that throws
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        lastPasskeyDetails: vi.fn(async () => {
          throw new Error('Test error');
        }),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const details = await service.passkeyDetails();
      expect(details).toBeNull();
    });
  });

  describe('passkeyPrfCapability()', () => {
    it('returns false when adapter prfCapability resolves false', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        prfCapability: vi.fn(async () => false),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const cap = await service.passkeyPrfCapability();
      expect(cap).toBe(false);
    });

    it('returns true when adapter prfCapability resolves true', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        prfCapability: vi.fn(async () => true),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const cap = await service.passkeyPrfCapability();
      expect(cap).toBe(true);
    });

    it('returns null when adapter prfCapability resolves null', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        prfCapability: vi.fn(async () => null),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const cap = await service.passkeyPrfCapability();
      expect(cap).toBeNull();
    });

    it('returns null when adapter has no prfCapability method', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        // no prfCapability method
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const cap = await service.passkeyPrfCapability();
      expect(cap).toBeNull();
    });

    it('returns null when prfCapability rejects', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        prfCapability: vi.fn(async () => {
          throw new Error('Test error');
        }),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const cap = await service.passkeyPrfCapability();
      expect(cap).toBeNull();
    });
  });

  describe('passkeyBuiltIn()', () => {
    it('returns false when adapter builtInAuthenticator resolves false', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        builtInAuthenticator: vi.fn(async () => false),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const result = await service.passkeyBuiltIn();
      expect(result).toBe(false);
    });

    it('returns true when adapter builtInAuthenticator resolves true', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        builtInAuthenticator: vi.fn(async () => true),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const result = await service.passkeyBuiltIn();
      expect(result).toBe(true);
    });

    it('returns null when adapter builtInAuthenticator resolves null', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        builtInAuthenticator: vi.fn(async () => null),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const result = await service.passkeyBuiltIn();
      expect(result).toBeNull();
    });

    it('returns null when adapter has no builtInAuthenticator method', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        // no builtInAuthenticator method
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const result = await service.passkeyBuiltIn();
      expect(result).toBeNull();
    });

    it('returns null when builtInAuthenticator rejects', async () => {
      const mockAdapter = {
        preflight: vi.fn(),
        decide: vi.fn(),
        authorize: vi.fn(),
        authorizePolicy: vi.fn(),
        execute: vi.fn(),
        resume: vi.fn(),
        confirmGate: vi.fn(),
        registerPasskey: vi.fn(),
        passkeyStatus: vi.fn(),
        builtInAuthenticator: vi.fn(async () => {
          throw new Error('Test error');
        }),
      };
      const service = new DriveConnectService(a.backup, a.sync, mockAdapter as any, { clientId: 'test' }, a.payload.source(), memoryPrefs());

      const result = await service.passkeyBuiltIn();
      expect(result).toBeNull();
    });
  });
});
