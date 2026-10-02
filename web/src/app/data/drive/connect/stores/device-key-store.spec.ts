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

import { describe, expect, it } from 'vitest';
import { WebCryptoProvider, CryptoError } from '../../../crypto/crypto-provider';

describe('CryptoProvider.p256FromStoredKey', () => {
  const crypto = new WebCryptoProvider();

  it('rewrapped key can perform p256Agree with a peer', async () => {
    // Generate two key pairs
    const keyPair1 = await crypto.p256Generate();
    const keyPair2 = await crypto.p256Generate();

    // Extract CryptoKey from provider's wrapper
    const storedCryptoKey1 = (keyPair1 as any).key;

    // Rewrap the first key through p256FromStoredKey
    const rewrapped1 = await crypto.p256FromStoredKey(storedCryptoKey1, keyPair1.publicKey);

    // Both sides perform ECDH
    const secret1 = await crypto.p256Agree(rewrapped1, keyPair2.publicKey);
    const secret2 = await crypto.p256Agree(keyPair2, rewrapped1.publicKey);

    // Secrets should match
    expect(secret1).toEqual(secret2);
  });

  it('rejects extractable private keys', async () => {
    try {
      // Create an extractable key (this is invalid for our use case)
      const extractableKey = await globalThis.crypto.subtle.generateKey(
        { name: 'ECDH', namedCurve: 'P-256' },
        true, // extractable (not allowed)
        ['deriveBits']
      ) as CryptoKeyPair;

      // Generate a valid 65-byte public key
      const publicKeyRaw = new Uint8Array(await globalThis.crypto.subtle.exportKey('raw', extractableKey.publicKey));

      await crypto.p256FromStoredKey(extractableKey.privateKey, publicKeyRaw);
      throw new Error('Should have thrown INVALID_KEY');
    } catch (err: any) {
      expect(err.message).toContain('non-extractable');
    }
  });

  it('validates public key format (rejects wrong length)', async () => {
    const keyPair = await crypto.p256Generate();
    const storedCryptoKey = (keyPair as any).key;

    // Invalid public key (too short)
    try {
      await crypto.p256FromStoredKey(storedCryptoKey, new Uint8Array(32));
      throw new Error('Should have thrown INVALID_KEY');
    } catch (err: any) {
      expect(err.message).toContain('not an uncompressed P-256 point');
    }
  });

  it('validates public key is on P-256 curve', async () => {
    const keyPair = await crypto.p256Generate();
    const storedCryptoKey = (keyPair as any).key;

    // Invalid public key (wrong curve point)
    const invalidKey = new Uint8Array(65);
    invalidKey[0] = 0x04;
    for (let i = 1; i < 65; i++) invalidKey[i] = 0xff;

    try {
      await crypto.p256FromStoredKey(storedCryptoKey, invalidKey);
      throw new Error('Should have thrown INVALID_KEY');
    } catch (err: any) {
      expect(err.message).toContain('INVALID_KEY');
    }
  });

  it('foreign key (non-provider wrapper) throws INVALID_KEY on p256Agree', async () => {
    const peer = await crypto.p256Generate();

    // Create a foreign key object
    const foreignKey = { publicKey: new Uint8Array(65) };

    try {
      await crypto.p256Agree(foreignKey as any, peer.publicKey);
      throw new Error('Should have thrown INVALID_KEY');
    } catch (err: any) {
      expect(err.message).toContain('foreign key');
    }
  });

  it('private key cannot be exported (non-extractable)', async () => {
    const keyPair = await crypto.p256Generate();
    const storedCryptoKey = (keyPair as any).key;

    // Attempt to export should fail
    try {
      await globalThis.crypto.subtle.exportKey('jwk', storedCryptoKey);
      throw new Error('Should have thrown error on export');
    } catch (err: any) {
      expect(err.message).toContain('not extractable');
    }
  });

  it('validates CryptoKey has deriveBits usage', async () => {
    const keyPair = await crypto.p256Generate();
    const storedCryptoKey = (keyPair as any).key;
    const publicKeyRaw = keyPair.publicKey;

    // The real key should have deriveBits usage
    expect(storedCryptoKey.usages).toContain('deriveBits');

    // Rewrap should succeed
    const rewrapped = await crypto.p256FromStoredKey(storedCryptoKey, publicKeyRaw);
    expect(rewrapped.publicKey).toEqual(publicKeyRaw);
  });

  it('two sequential p256Agree calls with rewrapped key produce same secret', async () => {
    const deviceKey = await crypto.p256Generate();
    const storedCryptoKey = (deviceKey as any).key;
    const rewrapped = await crypto.p256FromStoredKey(storedCryptoKey, deviceKey.publicKey);

    const peer1 = await crypto.p256Generate();
    const peer2 = await crypto.p256Generate();

    // Both ECDH operations should work with rewrapped key
    const secret1 = await crypto.p256Agree(rewrapped, peer1.publicKey);
    const secret2 = await crypto.p256Agree(rewrapped, peer2.publicKey);

    // Secrets should be different (different peers)
    expect(secret1).not.toEqual(secret2);

    // But each peer should derive matching secrets
    const peerSecret1 = await crypto.p256Agree(peer1, deviceKey.publicKey);
    const peerSecret2 = await crypto.p256Agree(peer2, deviceKey.publicKey);

    expect(secret1).toEqual(peerSecret1);
    expect(secret2).toEqual(peerSecret2);
  });

  it('rejects stored private key with mismatched public key', async () => {
    // Generate two independent key pairs
    const keyPair1 = await crypto.p256Generate();
    const keyPair2 = await crypto.p256Generate();

    const storedPrivateKey1 = (keyPair1 as any).key;
    const wrongPublicKey = keyPair2.publicKey; // Public key from a different pair

    try {
      // Try to create a key with private key from keyPair1 but public key from keyPair2
      await crypto.p256FromStoredKey(storedPrivateKey1, wrongPublicKey);
      throw new Error('Should have thrown INVALID_KEY for mismatched key pair');
    } catch (err: any) {
      expect(err.message).toContain('INVALID_KEY');
      expect(err.message).toContain('public key does not match');
    }
  });
});
