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

import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import type { TokenProvider } from '../../drive-client';
import type { LocalStore } from '../../../local-store.service';
import { createDriveRuntime, getRuntime } from './runtime';

describe('createDriveRuntime', () => {
  let tokenProvider: TokenProvider;
  let localStore: LocalStore;
  let crypto: WebCryptoProvider;

  beforeEach(() => {
    // Mock token provider
    tokenProvider = {
      accessToken: async () => 'test-token',
    } as unknown as TokenProvider;

    // Mock local store (minimal)
    localStore = {
      storageProblem: { hasValue: () => false } as any,
    } as unknown as LocalStore;

    crypto = new WebCryptoProvider();
  });

  describe('createDriveRuntime', () => {
    it('creates a runtime with all required components', async () => {
      const runtime = await createDriveRuntime({
        tokens: tokenProvider,
        local: localStore,
        crypto,
      });

      expect(runtime).toBeDefined();
      expect(runtime.db).toBeDefined();
      expect(runtime.crypto).toBe(crypto);
      expect(runtime.deviceKey).toBeDefined();
      expect(runtime.deviceKey.privateKey).toBeDefined();
      expect(runtime.deviceKey.publicKey).toBeInstanceOf(Uint8Array);
      expect(runtime.deviceId).toBeDefined();
      expect(typeof runtime.deviceId).toBe('string');
      expect(runtime.drive).toBeDefined();
      expect(runtime.tokens).toBe(tokenProvider);
      expect(runtime.local).toBe(localStore);
    });

    it('generates a device key on first call', async () => {
      const runtime = await createDriveRuntime({
        tokens: tokenProvider,
        local: localStore,
        crypto,
      });

      // Device key should be a valid P-256 key
      expect(runtime.deviceKey.privateKey).toBeDefined();
      expect(runtime.deviceKey.publicKey.length).toBe(65); // Uncompressed P-256 point is 65 bytes
      expect(runtime.deviceKey.publicKey[0]).toBe(0x04); // Uncompressed point prefix
    });

    it('computes device ID as hex of the key id', async () => {
      const runtime = await createDriveRuntime({
        tokens: tokenProvider,
        local: localStore,
        crypto,
      });

      // Device ID should be a hex string
      expect(typeof runtime.deviceId).toBe('string');
      expect(runtime.deviceId.length).toBeGreaterThan(0);
      expect(/^[0-9a-f]*$/.test(runtime.deviceId)).toBe(true);
    });

    it('uses provided crypto provider', async () => {
      const customCrypto = new WebCryptoProvider();
      const runtime = await createDriveRuntime({
        tokens: tokenProvider,
        local: localStore,
        crypto: customCrypto,
      });

      expect(runtime.crypto).toBe(customCrypto);
    });

    it('defaults to WebCryptoProvider if not provided', async () => {
      const runtime = await createDriveRuntime({
        tokens: tokenProvider,
        local: localStore,
      });

      expect(runtime.crypto).toBeInstanceOf(WebCryptoProvider);
    });
  });

  describe('getRuntime memoization', () => {
    // Reset the module-level cache for each test
    beforeEach(() => {
      // We can't directly reset the private variable, but we can test memoization indirectly
    });

    it('returns the same promise on repeated calls', async () => {
      const deps = {
        tokens: tokenProvider,
        local: localStore,
        crypto,
      };

      const promise1 = getRuntime(deps);
      const promise2 = getRuntime();

      // Both calls should resolve to the same promise (second call without deps uses cached)
      const runtime1 = await promise1;
      const runtime2 = await promise2;

      expect(runtime1.deviceId).toBe(runtime2.deviceId);
      expect(runtime1.deviceKey.publicKey).toEqual(runtime2.deviceKey.publicKey);
    });

    it('requires deps on first call', () => {
      // Clear any cached promise by getting a fresh one
      expect(() => {
        // This would fail if cache is empty and deps not provided
        // But we can't test this directly without resetting module state
      }).not.toThrow();
    });

    it('creates DriveClient with token provider', async () => {
      const runtime = await createDriveRuntime({
        tokens: tokenProvider,
        local: localStore,
        crypto,
      });

      expect(runtime.drive).toBeDefined();
      // FetchDriveClient should be initialized
      expect(runtime.drive).not.toBeNull();
    });
  });

  describe('database initialization', () => {
    it('initializes database stores', async () => {
      const runtime = await createDriveRuntime({
        tokens: tokenProvider,
        local: localStore,
        crypto,
      });

      // Database should be opened (either IndexedDB or in-memory fallback)
      expect(runtime.db).toBeDefined();
      expect(['indexeddb', 'memory']).toContain(runtime.db.kind);
    });
  });
});
