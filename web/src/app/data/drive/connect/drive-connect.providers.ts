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

import { InjectionToken, Provider, inject } from '@angular/core';
import { DriveConnectService, DRIVE_BACKUP_ADAPTER, DRIVE_DELETION_ADAPTER, DRIVE_SYNC_ADAPTER } from './drive-connect.service';
import { GoogleTokenProvider, WindowGoogleConfig } from './google-token-provider';
import type { GoogleConfig } from './google-token-provider';

/**
 * GoogleConfig provider: reads window.__DOORPRINTS__.googleClientId (empty by default, set via index.html).
 * S4b-BL-117, docs/15 §9.4.
 */
export const GOOGLE_CONFIG: InjectionToken<GoogleConfig> = new InjectionToken('GoogleConfig', {
  providedIn: 'root',
  factory: () => new WindowGoogleConfig(),
});

/**
 * Provides DriveConnectService with its dependencies (backup, sync, deletion adapters).
 * Also provides GoogleConfig for Google authentication.
 *
 * Usage:
 * ```
 * providers: [
 *   ...provideDriveConnect(),
 *   // Then provide the adapters from their own modules
 * ]
 * ```
 *
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
export function provideDriveConnect(): Provider[] {
  return [
    { provide: GOOGLE_CONFIG, useClass: WindowGoogleConfig },
    {
      provide: DriveConnectService,
      useFactory: () => {
        const backupAdapter = inject(DRIVE_BACKUP_ADAPTER);
        const syncAdapter = inject(DRIVE_SYNC_ADAPTER);
        const deletionAdapter = inject(DRIVE_DELETION_ADAPTER);
        return new DriveConnectService(backupAdapter, syncAdapter, deletionAdapter);
      },
    },
  ];
}
