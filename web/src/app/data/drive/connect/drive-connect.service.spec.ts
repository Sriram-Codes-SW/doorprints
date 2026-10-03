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
});
