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
import { DriveBackupAdapter } from './backup-adapter';
import { DriveSyncAdapter } from './sync-adapter';
import type { DriveDeletionAdapter } from './deletion-adapter';
import { GoogleTokenProvider, WindowGoogleConfig, DefaultScriptLoader } from './google-token-provider';
import type { GoogleConfig } from './google-token-provider';
import { FetchDriveClient } from '../fetch-drive-client';
import { WebCryptoProvider } from '../../crypto/crypto-provider';
import { createLazyBackupAdapterProxy, createBackupAdapter } from './factories/backup-factory';
import { createDriveRuntime, getRuntime } from './factories/runtime';
import { LocalStore } from '../../local-store.service';

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
 * All dependencies are lazy: nothing touches Google, IndexedDB, or WebCrypto until actual use.
 *
 * Usage:
 * ```
 * providers: [
 *   ...provideDriveConnect(),
 * ]
 * ```
 *
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
export function provideDriveConnect(): Provider[] {
  return [
    { provide: GOOGLE_CONFIG, useClass: WindowGoogleConfig },

    // Google token provider (lazy-loads GIS script only when needed)
    {
      provide: GoogleTokenProvider,
      useFactory: () => {
        const config = inject(GOOGLE_CONFIG);
        return new GoogleTokenProvider(new DefaultScriptLoader(), config);
      },
    },

    // WebCryptoProvider for encryption/decryption operations
    {
      provide: WebCryptoProvider,
      useClass: WebCryptoProvider,
    },

    // FetchDriveClient for Google Drive API calls
    {
      provide: FetchDriveClient,
      useFactory: () => {
        const tokenProvider = inject(GoogleTokenProvider);
        return new FetchDriveClient(tokenProvider);
      },
    },

    // Backup adapter (lazy-loaded: nothing created until first use)
    {
      provide: DRIVE_BACKUP_ADAPTER,
      useFactory: () => {
        const tokenProvider = inject(GoogleTokenProvider);
        const localStore = inject(LocalStore);
        const crypto = inject(WebCryptoProvider);

        // Lazy proxy that defers getRuntime() until first adapter method is called
        return createLazyBackupAdapterProxy(() =>
          getRuntime({
            tokens: tokenProvider,
            local: localStore,
            crypto,
          })
        );
      },
    },

    // Sync adapter (TODO: wire FolderSession, LocalRows, and DriveSyncEngine)
    {
      provide: DRIVE_SYNC_ADAPTER,
      useFactory: () => {
        const drive = inject(FetchDriveClient);
        // TODO: Wire FolderSession from connection state and LocalRows from LocalStore
        return new DriveSyncAdapter(
          null as any, // TODO: FolderSession
          drive,
        );
      },
    },

    // Deletion adapter (TODO: wire DriveDeletionService and authorization gates)
    {
      provide: DRIVE_DELETION_ADAPTER,
      useFactory: (): DriveDeletionAdapter => {
        // TODO: Wire DriveDeletionService and web authorizer
        // Stub implementation for now
        return {
          preflight: async () => ({ kind: 'refused', reason: 'Not implemented' } as any),
          decide: () => ({ outcome: 'REFUSED', reason: 'Not implemented' } as any),
          authorize: async () => ({ kind: 'refused', reason: 'Not implemented' } as any),
          execute: async () => ({ kind: 'refused', reason: 'Not implemented' } as any),
          resume: async () => ({ kind: 'refused', reason: 'Not implemented' } as any),
          confirmGate: () => ({ tickBoxRequired: false, delayMs: 0, enabled: () => false } as any),
        } as unknown as DriveDeletionAdapter;
      },
    },

    // DriveConnectService: orchestrates backup, sync, and deletion
    {
      provide: DriveConnectService,
      useFactory: () => {
        const googleConfig = inject(GOOGLE_CONFIG);
        const backupAdapter = inject(DRIVE_BACKUP_ADAPTER);
        const syncAdapter = inject(DRIVE_SYNC_ADAPTER);
        const deletionAdapter = inject(DRIVE_DELETION_ADAPTER);
        return new DriveConnectService(backupAdapter, syncAdapter, deletionAdapter, googleConfig);
      },
    },
  ];
}
