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

export interface DeviceKeyPair {
  privateKey: P256PrivateKey;
  publicKey: Uint8Array;
}

/**
 * Manages the device's P-256 key pair for HPKE decapsulation and ECDH with backup encryption.
 * The private key is stored as a non-extractable CryptoKey in IndexedDB; the public key as raw 65 bytes.
 */
export class DeviceKeyStore {
  private db: IDBDatabase | null = null;

  constructor(private dbPromise: Promise<IDBDatabase>, private crypto: CryptoProvider) {}

  private async getDb(): Promise<IDBDatabase> {
    if (!this.db) {
      this.db = await this.dbPromise;
    }
    return this.db;
  }

  /**
   * Load or create the device's key pair.
   * On first load, generates a new key pair using WebCrypto with extractable:false for the private key.
   * On subsequent loads, retrieves the stored CryptoKey and raw public key from IndexedDB,
   * validates them through the CryptoProvider, and returns the wrapped P256PrivateKey.
   */
  async loadOrCreateDeviceKey(): Promise<DeviceKeyPair> {
    const db = await this.getDb();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['device-key'], 'readonly');
      const store = tx.objectStore('device-key');
      const req = store.get('private-key');

      req.onsuccess = async () => {
        if (req.result) {
          // Key exists: validate and rewrap through the CryptoProvider
          const storedKey = req.result as {
            cryptoKey: CryptoKey;
            publicKeyRaw: Uint8Array;
          };
          try {
            const privateKey = await this.crypto.p256FromStoredKey(storedKey.cryptoKey, storedKey.publicKeyRaw);
            resolve({
              privateKey,
              publicKey: storedKey.publicKeyRaw,
            });
          } catch (err) {
            reject(err);
          }
        } else {
          // Key doesn't exist: generate new one
          try {
            const keyPair = await this.generateNewKeyPair();
            await this.storeKeyPair(keyPair);
            resolve(keyPair);
          } catch (err) {
            reject(err);
          }
        }
      };

      req.onerror = () => reject(req.error);
    });
  }

  private async generateNewKeyPair(): Promise<DeviceKeyPair> {
    const keyPair = await crypto.subtle.generateKey(
      { name: 'ECDH', namedCurve: 'P-256' },
      false, // extractable: false for private key
      ['deriveBits']
    ) as CryptoKeyPair;

    // Export the public key as raw 65-byte uncompressed point (SEC1 format)
    const publicKeyJwk = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
    const publicKeyRaw = this.publicKeyFromJwk(publicKeyJwk);

    // Validate and wrap through CryptoProvider
    const privateKey = await this.crypto.p256FromStoredKey(keyPair.privateKey, publicKeyRaw);

    return {
      privateKey,
      publicKey: publicKeyRaw,
    };
  }

  private publicKeyFromJwk(jwk: JsonWebKey): Uint8Array {
    // JWK format for P-256: x and y are base64url-encoded 32-byte coordinates
    const x = this.base64urlToBytes(jwk.x!);
    const y = this.base64urlToBytes(jwk.y!);

    // Build uncompressed SEC1 point: 0x04 || x || y (65 bytes total)
    const point = new Uint8Array(65);
    point[0] = 0x04;
    point.set(x, 1);
    point.set(y, 33);
    return point;
  }

  private base64urlToBytes(str: string): Uint8Array {
    const base64 = str.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const binary = atob(padded);
    return new Uint8Array(binary.split('').map((c) => c.charCodeAt(0)));
  }

  private async storeKeyPair(keyPair: DeviceKeyPair): Promise<void> {
    const db = await this.getDb();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['device-key'], 'readwrite');
      const store = tx.objectStore('device-key');

      // Extract the CryptoKey from the provider's wrapper
      const cryptoKey = (keyPair.privateKey as any).key;

      const req = store.put(
        {
          cryptoKey,
          publicKeyRaw: keyPair.publicKey,
        },
        'private-key'
      );

      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }

  /**
   * Delete the stored device key pair (used on disconnect or lock loss).
   */
  async deleteDeviceKey(): Promise<void> {
    const db = await this.getDb();

    return new Promise((resolve, reject) => {
      const tx = db.transaction(['device-key'], 'readwrite');
      const store = tx.objectStore('device-key');
      const req = store.delete('private-key');

      req.onerror = () => reject(req.error);
      tx.oncomplete = () => resolve();
    });
  }
}
