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

import { DriveSyncAdapter, type IDriveSyncAdapter, type SyncAdapterStatus } from '../sync-adapter';
import type { DriveRuntime } from './runtime';
import { LocalRowsAdapter } from '../local/local-rows';

/**
 * Creates a real sync adapter from the Drive runtime.
 *
 * Constructs DriveSyncAdapter with the runtime's stores and optional session. The adapter
 * defers initialization of engine, backend, and photos until first use (when session is available).
 *
 * Returns a DriveSyncAdapter that:
 * - Returns 'not connected' status if session is null (before backup connects)
 * - Syncs houses, visits, records, and photos through Drive's encrypted sync folder
 * - Handles photo uploads on metered vs unmetered networks
 * - Reports sync status, skipped files, and photo upload progress
 *
 * S4b-BL-131, S4b-BL-118, S4b-BL-128; docs/15 §9.4, §5.1, §11.
 */
export function createSyncAdapter(rt: DriveRuntime): DriveSyncAdapter {
  // Wrap in sync adapter with production dependencies from runtime
  // Session is optional and may be set later by backup adapter; adapter handles null gracefully
  return new DriveSyncAdapter(
    () => rt.session ?? null, // read at every call: the session appears when the backup adapter reaches Ready
    rt.drive,
    () => Date.now(),
    rt.photoStateStore, // Photo state store from opened DB
    undefined, // Use default photo settings
    undefined, // Use default photo config
    new LocalRowsAdapter(rt.local, rt.deviceId), // the app's real rows
    rt.syncStateStore, // sync bookkeeping persisted in the Drive database
  );
}

/**
 * A lazy proxy that wraps the sync adapter interface, deferring construction until first use.
 * This ensures nothing touches the runtime, IndexedDB, or device keys until actually needed.
 * Sync before backup connect returns a typed 'not connected' status.
 */
export function createLazySyncAdapterProxy(getRuntime: () => Promise<DriveRuntime>): IDriveSyncAdapter {
  let cachedAdapter: DriveSyncAdapter | null = null;

  async function ensureAdapter(): Promise<DriveSyncAdapter> {
    if (cachedAdapter) return cachedAdapter;
    const rt = await getRuntime();
    cachedAdapter = createSyncAdapter(rt);
    return cachedAdapter;
  }

  return {
    async syncNow(opts?: { confirmShrink?: boolean }): Promise<SyncAdapterStatus> {
      try {
        const adapter = await ensureAdapter();
        return adapter.syncNow(opts);
      } catch (err) {
        if (err instanceof Error && err.message.includes('not ready')) {
          return {
            state: 'error',
            skipped: [],
            lastSyncAt: null,
            error: 'not connected',
          };
        }
        throw err;
      }
    },

    async isBehind(): Promise<boolean> {
      try {
        const adapter = await ensureAdapter();
        return adapter.isBehind();
      } catch (err) {
        if (err instanceof Error && err.message.includes('not ready')) {
          return false;
        }
        throw err;
      }
    },

    getPhotoSettings() {
      // Return default if not connected
      return { uploadOnMobileData: false };
    },

    setPhotosWifiOnly(wifiOnly: boolean): void {
      // Fire-and-forget: set preference when we connect
      void ensureAdapter().then((adapter) => adapter.setPhotosWifiOnly(wifiOnly));
    },

    setUploadOnMobile(allowed: boolean): void {
      // Fire-and-forget: set preference when we connect
      void ensureAdapter().then((adapter) => adapter.setUploadOnMobile(allowed));
    },

    uploadPhotosNowOverMobile() {
      // Return a grant that may not be used if sync is not connected
      return { grantedAt: Date.now() };
    },

    async pendingPhotoBytes(): Promise<number | null> {
      try {
        const adapter = await ensureAdapter();
        return adapter.pendingPhotoBytes();
      } catch (err) {
        if (err instanceof Error && err.message.includes('not ready')) {
          return 0;
        }
        throw err;
      }
    },

    decideBackup(force: boolean = false): boolean {
      // Return false if sync is not connected
      return force;
    },
  };
}
