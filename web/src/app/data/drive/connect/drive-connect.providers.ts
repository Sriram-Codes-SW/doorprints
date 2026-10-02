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

import { Provider } from '@angular/core';
import { DriveConnectService } from './drive-connect.service';
import { DriveBackupAdapter } from './backup-adapter';
import { DriveSyncAdapter } from './sync-adapter';
import { DriveDeletionAdapterImpl } from './deletion-adapter';

/**
 * Provides DriveConnectService with its dependencies (backup, sync, deletion adapters).
 * The service is a plain class that receives adapters as constructor parameters.
 * Each adapter is also a plain class receiving its own dependencies.
 *
 * Usage in a component or module:
 * ```
 * providers: [...provideDriveConnect()]
 * ```
 *
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
export function provideDriveConnect(): Provider[] {
  return [
    {
      provide: DriveConnectService,
      useFactory: (
        backupAdapter: DriveBackupAdapter,
        syncAdapter: DriveSyncAdapter,
        deletionAdapter: DriveDeletionAdapterImpl,
      ) => new DriveConnectService(backupAdapter, syncAdapter, deletionAdapter),
      deps: [DriveBackupAdapter, DriveSyncAdapter, DriveDeletionAdapterImpl],
    },
    // Adapters are provided by other modules; this just wires them together
  ];
}
