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

import { LocalRowsAdapter } from '../local/local-rows';
import { DriveSyncAdapter, type IDriveSyncAdapter, type SyncAdapterStatus } from '../sync-adapter';
import type { DriveRuntime } from './runtime';

/**
 * Creates a real sync adapter from the Drive runtime and an opened folder session.
 *
 * Constructs LocalRowsAdapter with the runtime's local store and device ID, then wraps both
 * in DriveSyncAdapter for the Connect page UI to use.
 *
 * Returns a DriveSyncAdapter that:
 * - Syncs houses, visits, records, and photos through Drive's encrypted sync folder
 * - Handles photo uploads on metered vs unmetered networks
 * - Reports sync status, skipped files, and photo upload progress
 *
 * S4b-BL-131, S4b-BL-118, S4b-BL-128; docs/15 §9.4, §5.1, §11.
 */
export function createSyncAdapter(rt: DriveRuntime): DriveSyncAdapter | null {
  // Sync cannot run without a folder session (happens before backup connect)
  if (!rt.session) {
    return null;
  }

  // Wrap in sync adapter with production dependencies from runtime
  return new DriveSyncAdapter(
    rt.session,
    rt.drive,
    () => Date.now(),
    rt.photoStateStore, // Photo state store from opened DB
    undefined, // Use default photo settings
    undefined, // Use default photo config
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
    const adapter = createSyncAdapter(rt);
    if (!adapter) {
      throw new Error('Sync adapter not ready: folder not connected');
    }
    cachedAdapter = adapter;
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

    async pendingPhotoBytes(): Promise<number> {
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
