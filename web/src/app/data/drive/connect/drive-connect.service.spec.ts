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
import { DriveConnectService } from './drive-connect.service';
import type { DriveBackupAdapter } from './backup-adapter';
import type { DriveSyncAdapter } from './sync-adapter';
import type { DriveDeletionAdapter } from './deletion-adapter';
import type { DeletionAction } from '../drive-deletion-rules';

describe('DriveConnectService', () => {
  let service: DriveConnectService;
  let mockBackupAdapter: Partial<DriveBackupAdapter>;
  let mockSyncAdapter: Partial<DriveSyncAdapter>;
  let mockDeletionAdapter: Partial<DriveDeletionAdapter>;

  beforeEach(() => {
    mockBackupAdapter = {
      connect: vi.fn(),
      createFolder: vi.fn(),
      openWithRecoveryKey: vi.fn(),
    };

    mockSyncAdapter = {
      syncNow: vi.fn(),
      setPhotosWifiOnly: vi.fn(),
      uploadPhotosNowOverMobile: vi.fn(),
    };

    mockDeletionAdapter = {
      preflight: vi.fn(),
      authorize: vi.fn(),
      execute: vi.fn(),
    };

    service = new DriveConnectService(
      mockBackupAdapter as DriveBackupAdapter,
      mockSyncAdapter as DriveSyncAdapter,
      mockDeletionAdapter as DriveDeletionAdapter,
    );
  });

  describe('state machine', () => {
    it('starts Unavailable', () => {
      expect(service.getState()).toBe('Unavailable');
    });

    it('transitions Unavailable → Connecting → Disconnected on connect() with NO_FOLDER', async () => {
      (mockBackupAdapter.connect as any).mockResolvedValue({ kind: 'NO_FOLDER' });

      const result = await service.connect();
      expect(result.state).toBe('Disconnected');
      expect(service.getState()).toBe('Disconnected');
    });

    it('transitions Unavailable → Connecting → NeedsRecoveryKey on connect()', async () => {
      (mockBackupAdapter.connect as any).mockResolvedValue({
        kind: 'NEEDS_RECOVERY_KEY',
        recoveryAvailable: true,
        reason: 'RECOVERY_MISMATCH',
      });

      const result = await service.connect();
      expect(result.state).toBe('NeedsRecoveryKey');
      expect(service.getState()).toBe('NeedsRecoveryKey');
    });

    it('transitions Unavailable → Connecting → NeedsEnrolment on connect()', async () => {
      (mockBackupAdapter.connect as any).mockResolvedValue({
        kind: 'NEEDS_ENROLMENT',
        recoveryAvailable: true,
      });

      const result = await service.connect();
      expect(result.state).toBe('NeedsEnrolment');
      expect(service.getState()).toBe('NeedsEnrolment');
    });

    it('transitions Unavailable → Connecting → Ready on connect()', async () => {
      (mockBackupAdapter.connect as any).mockResolvedValue({
        kind: 'READY',
        folder: { rootId: 'root' },
      });

      const result = await service.connect();
      expect(result.state).toBe('Ready');
      expect(service.getState()).toBe('Ready');
    });

    it('shows recovery key once on createFolder()', async () => {
      const fakeRecoveryKey = { display: 'fake-key-123' } as any;
      (mockBackupAdapter.createFolder as any).mockResolvedValue({
        connection: { kind: 'NEEDS_ENROLMENT', recoveryAvailable: true },
        recoveryKey: fakeRecoveryKey,
      });

      const result = await service.createFolder();
      expect(result.state).toBe('FirstConnectShowRecoveryKey');
      expect(result.recoveryKey).toBe('fake-key-123');
    });

    it('transitions to NeedsEnrolment after confirming recovery key saved', async () => {
      service.confirmRecoveryKeySaved();
      expect(service.getState()).toBe('NeedsEnrolment');
    });
  });

  describe('recovery key', () => {
    it('is shown once and never again', async () => {
      const fakeRecoveryKey = { display: 'fake-key-456' } as any;
      (mockBackupAdapter.createFolder as any).mockResolvedValue({
        connection: { kind: 'NEEDS_ENROLMENT', recoveryAvailable: true },
        recoveryKey: fakeRecoveryKey,
      });

      const result = await service.createFolder();
      expect(result.recoveryKey).toBe('fake-key-456');
      expect(service.hasShownRecoveryKey()).toBe(false);

      service.confirmRecoveryKeySaved();
      expect(service.hasShownRecoveryKey()).toBe(true);
    });

    it('can be skipped with warning', async () => {
      const fakeRecoveryKey = { display: 'fake-key-789' } as any;
      (mockBackupAdapter.createFolder as any).mockResolvedValue({
        connection: { kind: 'NEEDS_ENROLMENT', recoveryAvailable: true },
        recoveryKey: fakeRecoveryKey,
      });

      await service.createFolder();
      service.skipRecoveryKeyWithWarning();
      expect(service.hasShownRecoveryKey()).toBe(true);
      expect(service.getState()).toBe('NeedsEnrolment');
    });
  });

  describe('disconnect', () => {
    it('returns to Disconnected', async () => {
      await service.disconnect();
      expect(service.getState()).toBe('Disconnected');
    });
  });

  describe('backup operations', () => {
    it('backUpNow returns success when sync succeeds', async () => {
      (mockSyncAdapter.syncNow as any).mockResolvedValue({
        state: 'synced',
      });

      const result = await service.backUpNow();
      expect(result.success).toBe(true);
    });

    it('backUpNow returns error when sync fails', async () => {
      (mockSyncAdapter.syncNow as any).mockResolvedValue({
        state: 'error',
        error: 'Offline',
      });

      const result = await service.backUpNow();
      expect(result.success).toBe(false);
      expect(result.error).toBe('Offline');
    });
  });

  describe('deletion operations', () => {
    it('deleteL1 returns success when deletion succeeds', async () => {
      (mockDeletionAdapter.preflight as any).mockResolvedValue({
        kind: 'ready',
        plan: { rootId: 'root', items: [], totals: {}, operationId: 'op1' },
      });
      (mockDeletionAdapter.execute as any).mockResolvedValue({
        kind: 'ran',
        report: {},
      });

      const result = await service.deleteL1();
      expect(result.success).toBe(true);
    });

    it('deleteL1 returns error when preflight refused', async () => {
      (mockDeletionAdapter.preflight as any).mockResolvedValue({
        kind: 'refused',
        reason: 'OFFLINE',
      });

      const result = await service.deleteL1();
      expect(result.success).toBe(false);
      expect(result.error).toBe('OFFLINE');
    });
  });
});
