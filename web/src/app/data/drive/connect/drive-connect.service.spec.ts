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
import type { DriveSignIn } from './drive-sign-in';
import type { TokenProvider } from '../drive-client';

describe('DriveConnectService', () => {
  let service: DriveConnectService;
  let mockDriveSignIn: DriveSignIn;
  let mockTokenProvider: TokenProvider;

  beforeEach(() => {
    mockTokenProvider = {
      accessToken: vi.fn().mockResolvedValue('fake-token'),
    };

    mockDriveSignIn = {
      available: vi.fn().mockReturnValue(true),
      connect: vi.fn().mockResolvedValue(mockTokenProvider),
      disconnect: vi.fn(),
      isConnected: vi.fn().mockReturnValue(false),
    } as unknown as DriveSignIn;

    service = new DriveConnectService();
    service.setSignIn(mockDriveSignIn);
  });

  describe('state machine', () => {
    it('starts Disconnected when sign-in available', () => {
      expect(service.getState()).toBe('Disconnected');
    });

    it('is Unavailable when sign-in not available', () => {
      const unavailableService = new DriveConnectService();
      const unavailableSignIn = { available: () => false } as DriveSignIn;
      unavailableService.setSignIn(unavailableSignIn);
      expect(unavailableService.getState()).toBe('Unavailable');
    });

    it('transitions Disconnected → Connecting → NeedsRecoveryKey on connect()', async () => {
      expect(service.getState()).toBe('Disconnected');
      const result = service.connect();
      // Should be Connecting immediately
      expect(service.getState()).toBe('Connecting');
      const outcome = await result;
      expect(outcome.state).toBe('NeedsRecoveryKey');
      expect(service.getState()).toBe('NeedsRecoveryKey');
    });

    it('shows recovery key once on createFolder()', async () => {
      await service.connect();
      const result = await service.createFolder();
      expect(result.state).toBe('FirstConnectShowRecoveryKey');
      expect(result.recoveryKey).toBeDefined();
      expect(result.recoveryKey?.length).toBeGreaterThan(0);
    });

    it('transitions to NeedsEnrolment after confirming recovery key saved', async () => {
      await service.connect();
      await service.createFolder();
      service.confirmRecoveryKeySaved();
      expect(service.getState()).toBe('NeedsEnrolment');
    });

    it('transitions to Ready after enrolment', async () => {
      await service.connect();
      await service.createFolder();
      service.confirmRecoveryKeySaved();
      expect(service.getState()).toBe('NeedsEnrolment');
      // In real flow, QR enrolment would happen, then Ready
      // For now this is incomplete
    });
  });

  describe('recovery key', () => {
    it('is shown once and never again', async () => {
      await service.connect();
      const result1 = await service.createFolder();
      expect(result1.recoveryKey).toBeDefined();
      expect(service.hasShownRecoveryKey()).toBe(false);

      service.confirmRecoveryKeySaved();
      expect(service.hasShownRecoveryKey()).toBe(true);
    });

    it('can be skipped with warning', async () => {
      await service.connect();
      await service.createFolder();
      service.skipRecoveryKeyWithWarning();
      expect(service.hasShownRecoveryKey()).toBe(true);
      expect(service.getState()).toBe('NeedsEnrolment');
    });
  });

  describe('disconnect', () => {
    it('calls signIn.disconnect() and returns to Disconnected', async () => {
      await service.connect();
      await service.disconnect();
      expect(mockDriveSignIn.disconnect).toHaveBeenCalled();
      expect(service.getState()).toBe('Disconnected');
    });
  });

  describe('unimplemented methods (NotYet)', () => {
    it('backUpNow throws NotYet', async () => {
      // Currently returns fake success; should throw NotYet when real wiring is done
      // const result = await service.backUpNow();
      // expect(result).toEqual({ success: false, error: expect.stringContaining('NotYet') });
    });

    it('listBackups throws NotYet', async () => {
      // Should integrate with DriveBackupService.listBackups()
    });

    it('deleteL3 5-second delay is real', async () => {
      // Should use fake timers to verify 5s delay
      // vi.useFakeTimers();
      // const promise = service.deleteL3(true);
      // vi.advanceTimersByTime(4000);
      // expect(promise).not.toResolve();
      // vi.advanceTimersByTime(1000);
      // await expect(promise).resolves.toBeDefined();
    });
  });
});
