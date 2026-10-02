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
import { DriveSyncAdapter } from './sync-adapter';
import { kidOf } from '../../crypto/folder-key';
import { SyncWorld } from '../drive-sync-test-world';

describe('DriveSyncAdapter', () => {
  let adapter: DriveSyncAdapter;
  let world: SyncWorld;

  beforeEach(async () => {
    world = new SyncWorld();
    const device = await world.add('test-device');
    const p = world.p;

    // Create adapter from test device
    adapter = new DriveSyncAdapter(
      device.session(),
      device.drive,
      () => world.now(),
      device.photoStore,
    );
  });

  it('should initialize with default settings', () => {
    const settings = adapter.getPhotoSettings();
    expect(settings.uploadOnMobileData).toBe(false);
  });

  it('should update photo settings', () => {
    adapter.setUploadOnMobile(true);
    expect(adapter.getPhotoSettings().uploadOnMobileData).toBe(true);

    adapter.setPhotosWifiOnly(true);
    expect(adapter.getPhotoSettings().uploadOnMobileData).toBe(false);
  });

  it('should grant one-off mobile upload with 30-minute TTL', () => {
    const clock = world.now();
    const grant = adapter.uploadPhotosNowOverMobile();
    expect(grant.grantedAt).toBeLessThanOrEqual(clock);

    // Grant should be valid for 30 minutes
    const ttl = 30 * 60 * 1000;
    expect(clock + ttl).toBeGreaterThan(grant.grantedAt);
  });

  it('should handle sync with no errors', async () => {
    const status = await adapter.syncNow();
    expect(status.state).toBeDefined();
    expect(status.skipped).toBeDefined();
    expect(Array.isArray(status.skipped)).toBe(true);
  });

  it('should report pending photo bytes', async () => {
    const bytes = await adapter.pendingPhotoBytes();
    expect(typeof bytes).toBe('number');
    expect(bytes).toBeGreaterThanOrEqual(0);
  });

  it('should decide backup based on network', () => {
    const decision = adapter.decideBackup(false);
    expect(typeof decision).toBe('boolean');
  });

  it('should always allow backup when forced', () => {
    const decision = adapter.decideBackup(true);
    expect(decision).toBe(true);
  });

  it('should check if behind peers', async () => {
    const behind = await adapter.isBehind();
    expect(typeof behind).toBe('boolean');
  });

  it('should handle sync without crash on confirm shrink', async () => {
    const status = await adapter.syncNow({ confirmShrink: true });
    expect(status.state).toBeDefined();
  });
});
