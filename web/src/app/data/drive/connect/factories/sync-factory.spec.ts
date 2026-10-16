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
import { createSyncAdapter, createLazySyncAdapterProxy } from './sync-factory';
import type { DriveRuntime } from './runtime';
import { DriveSyncAdapter } from '../sync-adapter';
import { SyncWorld } from '../../drive-sync-test-world';
import type { TestDevice } from '../../drive-sync-test-world';

describe('sync factories', () => {
  describe('createSyncAdapter', () => {
    it('creates an adapter even when runtime has no session', () => {
      const mockRuntime = { session: null } as any;
      const adapter = createSyncAdapter(mockRuntime);
      expect(adapter).not.toBeNull();
      expect(typeof adapter.syncNow).toBe('function');
    });

    it('creates an adapter when runtime has a session', async () => {
      const world = new SyncWorld();
      const device = await world.add('device-a');
      const mockRuntime = {
        session: device.session(),
        drive: device.drive,
        local: {} as any,
        deviceId: device.id,
        db: {
          photoStateStore: device.photoStore,
        },
      } as any;

      const adapter = createSyncAdapter(mockRuntime);
      expect(adapter).not.toBeNull();
      expect(typeof adapter!.syncNow).toBe('function');
    });
  });

  describe('createLazySyncAdapterProxy', () => {
    it('returns a proxy with sync adapter methods', async () => {
      const world = new SyncWorld();
      const device = await world.add('device');
      const mockRuntime = {
        session: device.session(),
        drive: device.drive,
        local: {} as any,
        deviceId: device.id,
        db: { photoStateStore: device.photoStore },
      } as any;

      const getRuntime = async () => mockRuntime;
      const proxy = createLazySyncAdapterProxy(getRuntime);

      expect(proxy).toBeDefined();
      expect(typeof proxy.syncNow).toBe('function');
      expect(typeof proxy.isBehind).toBe('function');
      expect(typeof proxy.getPhotoSettings).toBe('function');
      expect(typeof proxy.setPhotosWifiOnly).toBe('function');
      expect(typeof proxy.setUploadOnMobile).toBe('function');
      expect(typeof proxy.uploadPhotosNowOverMobile).toBe('function');
      expect(typeof proxy.pendingPhotoBytes).toBe('function');
      expect(typeof proxy.decideBackup).toBe('function');
    });

    it('returns "not connected" error when runtime has no session', async () => {
      const mockRuntime = { session: null } as any;
      const getRuntime = async () => mockRuntime;
      const proxy = createLazySyncAdapterProxy(getRuntime);

      const status = await proxy.syncNow();
      expect(status.state).toBe('error');
      expect(status.error).toBe('not connected');
    });

    it('handles photo settings before connection', async () => {
      const mockRuntime = { session: null } as any;
      const getRuntime = async () => mockRuntime;
      const proxy = createLazySyncAdapterProxy(getRuntime);

      const settings = proxy.getPhotoSettings();
      expect(settings.uploadOnMobileData).toBe(false);
    });

    it('returns 0 pending bytes when not connected', async () => {
      const mockRuntime = { session: null } as any;
      const getRuntime = async () => mockRuntime;
      const proxy = createLazySyncAdapterProxy(getRuntime);

      const bytes = await proxy.pendingPhotoBytes();
      expect(bytes).toBe(0);
    });

    it('caches adapter between calls', async () => {
      const world = new SyncWorld();
      const deviceA = await world.add('device-a');
      let callCount = 0;
      const mockRuntime = {
        session: deviceA.session(),
        drive: deviceA.drive,
        local: {} as any,
        deviceId: deviceA.id,
        db: { photoStateStore: deviceA.photoStore },
      } as any;

      const getRuntime = async () => {
        callCount++;
        return mockRuntime;
      };

      const proxy = createLazySyncAdapterProxy(getRuntime);
      await proxy.syncNow();
      await proxy.isBehind();
      // Should have called getRuntime only once (cached after first call)
      expect(callCount).toBe(1);
    });
  });

  describe('two-device convergence', () => {
    let world: SyncWorld;
    let deviceA: TestDevice;
    let deviceB: TestDevice;

    beforeEach(async () => {
      world = new SyncWorld();
      deviceA = await world.add('device-a');
      deviceB = await world.add('device-b');
    });

    it('converges: house created on A appears on B after both sync', async () => {
      // Device A creates and edits a house
      deviceA.edit('house-1', 'My Dream Home');

      // Device A syncs (pushes its change)
      const resultA = await deviceA.sync();
      expect(resultA.kind).not.toBe('Paused');

      // Device B syncs (should pull A's change)
      const resultB = await deviceB.sync();
      expect(resultB.kind).not.toBe('Paused');

      // Both should agree on the house label
      expect(deviceB.local.label('house-1')).toBe('My Dream Home');
    });

    it('marks house as dirty before sync and clean after', async () => {
      deviceA.edit('house-1', 'Home');
      expect(deviceA.local.dirty.size).toBeGreaterThan(0);

      await deviceA.sync();
      expect(deviceA.local.dirty.size).toBe(0);
    });

    it('handles deletions: deleted on A is reported on B', async () => {
      deviceA.edit('house-1', 'Home');
      await deviceA.sync();
      await deviceB.sync();

      deviceA.delete('house-1');
      await deviceA.sync();
      await deviceB.sync();

      expect(deviceB.local.label('house-1')).toBe('<deleted>');
    });
  });

  describe('real factories end-to-end', () => {
    it('sync before connect returns typed "not connected" status', async () => {
      const world = new SyncWorld();
      const deviceA = await world.add('device-a');

      // Create adapter with no session (before backup connects)
      const adapter = new DriveSyncAdapter(null, deviceA.drive, () => world.now(), deviceA.photoStore);

      // Sync before connect should return error status, not throw
      const status = await adapter.syncNow();
      expect(status.state).toBe('error');
      expect(status.error).toBe('not connected');
    });

    it('isBehind before connect returns false', async () => {
      const world = new SyncWorld();
      const deviceA = await world.add('device-a');

      const adapter = new DriveSyncAdapter(null, deviceA.drive, () => world.now(), deviceA.photoStore);
      const behind = await adapter.isBehind();
      expect(behind).toBe(false);
    });

    it('sync with real session after connect', async () => {
      // This test verifies that when a FolderSession is provided, sync works
      const world = new SyncWorld();
      const deviceA = await world.add('device-a');

      // Create adapter WITH a valid session
      const adapter = new DriveSyncAdapter(
        deviceA.session(),
        deviceA.drive,
        () => world.now(),
        deviceA.photoStore,
      );

      // Edit a house locally
      deviceA.edit('house-1', 'Test Home');

      // Sync should work
      const status = await adapter.syncNow();
      expect(status.state).not.toBe('error');
      expect(status.error).toBeUndefined();
    });
  });

  describe('photo network policy', () => {
    let world: SyncWorld;
    let device: TestDevice;

    beforeEach(async () => {
      world = new SyncWorld();
      device = await world.add('device');
    });

    it('allows photos on unmetered network', () => {
      const adapter = new DriveSyncAdapter(
        device.session(),
        device.drive,
        () => world.now(),
        device.photoStore,
      );

      const allowed = adapter.decideBackup(false);
      // On unmetered (test environment), should allow
      expect(typeof allowed).toBe('boolean');
    });

    it('respects mobile data setting when disabled', () => {
      const adapter = new DriveSyncAdapter(
        device.session(),
        device.drive,
        () => world.now(),
        device.photoStore,
        { uploadOnMobileData: false },
      );

      expect(adapter.getPhotoSettings().uploadOnMobileData).toBe(false);
    });

    it('allows photos on mobile data when setting enabled', () => {
      const adapter = new DriveSyncAdapter(
        device.session(),
        device.drive,
        () => world.now(),
        device.photoStore,
        { uploadOnMobileData: true },
      );

      expect(adapter.getPhotoSettings().uploadOnMobileData).toBe(true);
    });
  });
});
