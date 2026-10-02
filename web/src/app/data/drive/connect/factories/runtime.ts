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

import type { P256PrivateKey, CryptoProvider } from '../../../crypto/crypto-provider';
import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import { hex } from '../../../crypto/bytes';
import { kidOf } from '../../../crypto/folder-key';
import type { TokenProvider } from '../../drive-client';
import type { LocalStore } from '../../../local-store.service';
import type { DriveDb } from '../stores/drive-db';
import { openDriveDb } from '../stores/drive-db';
import { DeviceKeyStore } from '../stores/device-key-store';
import { FetchDriveClient } from '../../fetch-drive-client';
import type { DriveClient } from '../../drive-client';

/**
 * The runtime needed by Drive backup, sync, and deletion: database, crypto, device identity,
 * and Drive client. All components are opened lazily and memoized per page load.
 *
 * S4b-BL-117, S4b-BL-73, docs/15 §9.4.
 */
export interface DriveRuntime {
  db: DriveDb;
  crypto: CryptoProvider;
  deviceKey: { privateKey: P256PrivateKey; publicKey: Uint8Array };
  deviceId: string; // hex of the device key id, one definition for backup and sync
  drive: DriveClient;
  tokens: TokenProvider;
  local: LocalStore;
  driveStateStore: any; // TODO: Use proper DriveStateStore type from drive-backup-seams
}

/**
 * Opens the Drive runtime once per page load. Memoizes the promise so all callers get
 * the same instance. Called lazily by adapters that need it.
 *
 * @param deps.tokens Token provider for Drive API access
 * @param deps.local LocalStore for backup sources and staging sinks
 * @param deps.crypto Crypto provider (defaults to WebCryptoProvider)
 * @returns Promise resolving to the runtime
 */
export async function createDriveRuntime(deps: {
  tokens: TokenProvider;
  local: LocalStore;
  crypto?: CryptoProvider;
}): Promise<DriveRuntime> {
  const crypto = deps.crypto || new WebCryptoProvider();

  // Open IndexedDB with all stores (or in-memory fallback)
  const opened = await openDriveDb();
  const db = opened.db;

  // For device key store, we need a Promise<IDBDatabase>
  // The opened database may be either indexedDB or in-memory; we need to handle both
  let idbPromise: Promise<IDBDatabase>;

  if (db.kind === 'indexeddb') {
    // If we have a real IndexedDB, reopen it for device-key-store
    // (device-key-store manages its own lifecycle)
    idbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open('doorprints-drive');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  } else {
    // For in-memory fallback, reject so device-key-store falls back gracefully
    idbPromise = Promise.reject(new Error('IndexedDB not available'));
  }

  // Load or create device key pair
  const keyStore = new DeviceKeyStore(idbPromise, crypto);
  const deviceKey = await keyStore.loadOrCreateDeviceKey();

  // Compute device ID as hex of the key ID (same as kidOf but as a string)
  const keyId = kidOf(crypto, deviceKey.publicKey);
  const deviceId = hex(keyId);

  // Build Drive client
  const drive = new FetchDriveClient(deps.tokens);

  return {
    db,
    crypto,
    deviceKey,
    deviceId,
    drive,
    tokens: deps.tokens,
    local: deps.local,
    driveStateStore: opened.driveStateStore,
  };
}

/** Singleton promise for the runtime (memoized per page load) */
let runtimePromise: Promise<DriveRuntime> | null = null;

/**
 * Get the memoized runtime, creating it lazily on first call.
 * All callers within the same page load get the same instance.
 *
 * @param deps Same as createDriveRuntime, only used on first call
 * @returns The memoized runtime promise
 */
export function getRuntime(deps?: {
  tokens: TokenProvider;
  local: LocalStore;
  crypto?: CryptoProvider;
}): Promise<DriveRuntime> {
  if (!runtimePromise) {
    if (!deps) {
      throw new Error('First call to getRuntime() must provide dependencies');
    }
    runtimePromise = createDriveRuntime(deps);
  }
  return runtimePromise;
}
