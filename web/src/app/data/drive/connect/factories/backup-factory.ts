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

import type { DriveBackupService } from '../../backup/drive-backup.service';
import type { DriveImportService } from '../../backup/drive-import.service';
import { DriveBackupService as BackupServiceClass } from '../../backup/drive-backup.service';
import { DriveImportService as ImportServiceClass } from '../../backup/drive-import.service';
import { DriveBackupAdapter, type BackupAdapterInterface } from '../backup-adapter';
import type { DriveRuntime } from './runtime';

/**
 * Creates a real backup adapter from the Drive runtime.
 *
 * Constructs DriveBackupService with the runtime's crypto, device identity, state store,
 * and folder trust stores (DB-backed via the runtime), then DriveImportService with the same,
 * and finally wraps both in DriveBackupAdapter for the screens to use.
 *
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
export function createBackupAdapter(rt: DriveRuntime): DriveBackupAdapter {
  // Create the backup service with production dependencies from runtime
  const backupService: DriveBackupService = new BackupServiceClass(
    rt.drive,
    rt.crypto,
    {
      key: rt.deviceKey.privateKey,
      name: 'Doorprints Browser',
      platform: 'web',
    },
    rt.driveStateStore, // Device state store from opened DB
    rt.folderTrustStores, // Folder key watermarks and control watermarks from opened DB
    () => Date.now(),
    () => {
      // Get UTC offset in minutes (e.g., -300 for EST, 330 for IST)
      const now = new Date();
      return -now.getTimezoneOffset();
    },
  );

  // The import service shares the same drive and crypto
  const importService: DriveImportService = new ImportServiceClass(
    rt.drive,
    rt.crypto,
  );

  // Wrap both services in the adapter
  return new DriveBackupAdapter(backupService, importService, rt.drive, rt.driveStateStore);
}

/**
 * A lazy proxy that wraps the backup adapter interface, deferring construction until first use.
 * This ensures nothing is built at app startup; the adapter is only created when actually needed.
 */
export function createLazyBackupAdapterProxy(getRuntime: () => Promise<DriveRuntime>): BackupAdapterInterface {
  return {
    async connect() {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.connect();
    },

    async createFolder() {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.createFolder();
    },

    async openWithRecoveryKey(recoveryKey) {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.openWithRecoveryKey(recoveryKey);
    },

    async backUpNow(folder, source) {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.backUpNow(folder, source);
    },

    async confirmShrink(backupId) {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.confirmShrink(backupId);
    },

    async listBackups(folder) {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.listBackups(folder);
    },

    async importFromDrive(folder, backupId, backupItem, staging) {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.importFromDrive(folder, backupId, backupItem, staging);
    },

    async writeReadMe(lang) {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.writeReadMe(lang);
    },

    async schedule(enabled, ready) {
      const rt = await getRuntime();
      const adapter = createBackupAdapter(rt);
      return adapter.schedule(enabled, ready);
    },
  };
}
