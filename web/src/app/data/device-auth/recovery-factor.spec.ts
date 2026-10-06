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

import { describe, it, expect } from 'vitest';
import { recoveryProofKey } from './recovery-factor';
import { RecoveryKey } from '../crypto/recovery-key';
import { WebCryptoProvider } from '../crypto/crypto-provider';
import { checkProof, signProof, importProofKey } from './deletion-proof';
import { ab, utf8 } from '../crypto/bytes';

describe('recoveryProofKey', () => {
  const p = new WebCryptoProvider();
  const operationId = 'DELETE_ONE_BACKUP';
  const time = 1000;
  const differentOperation = 'DELETE_ALL_BACKUPS';
  const differentTime = 1001;

  it('(a) the same recovery key signs the same operation to the same proof', async () => {
    const key1 = RecoveryKey.generate(p);
    const proof1 = await recoveryProofKey(key1);
    const sig1 = await signProof(proof1, operationId, time);

    // Same key, same operation and time should give same signature
    const proof1Again = await recoveryProofKey(key1);
    const sig1Again = await signProof(proof1Again, operationId, time);

    expect(sig1).toBe(sig1Again);
  });

  it('(b) a different recovery key gives a different proof', async () => {
    const key1 = RecoveryKey.generate(p);
    const key2 = RecoveryKey.generate(p);

    const proof1 = await recoveryProofKey(key1);
    const proof2 = await recoveryProofKey(key2);

    const sig1 = await signProof(proof1, operationId, time);
    const sig2 = await signProof(proof2, operationId, time);

    expect(sig1).not.toBe(sig2);
  });

  it('(c) a proof for operation A and time t does not verify for operation B nor for t+1', async () => {
    const key = RecoveryKey.generate(p);
    const proofKey = await recoveryProofKey(key);
    const sig = await signProof(proofKey, operationId, time);

    // Should verify for the exact operation and time
    expect(await checkProof(proofKey, operationId, time, sig)).toBe(true);

    // Should not verify for different operation
    expect(await checkProof(proofKey, differentOperation, time, sig)).toBe(false);

    // Should not verify for different time
    expect(await checkProof(proofKey, operationId, differentTime, sig)).toBe(false);
  });

  it('(d) the key is non-extractable and export fails', async () => {
    const key = RecoveryKey.generate(p);
    const proofKey = await recoveryProofKey(key);

    expect(proofKey.extractable).toBe(false);

    // Attempting to export should throw
    await expect(() => crypto.subtle.exportKey('raw', proofKey)).rejects.toThrow();
  });

  it('(e) domain separation: recovery proof differs from PRF-based proof', async () => {
    const key = RecoveryKey.generate(p);
    const recoveryKey = await recoveryProofKey(key);
    const recoveryProof = await signProof(recoveryKey, operationId, time);

    // Import a 32-byte PRF output and derive proof key the PRF way
    const prfOutput = p.randomBytes(32);
    const prfKey = await importProofKey(prfOutput);
    const prfProof = await signProof(prfKey, operationId, time);

    // The proofs should be different (domain separation)
    expect(recoveryProof).not.toBe(prfProof);

    // Cross-verification should fail: recovery proof should not verify with PRF key
    expect(await checkProof(prfKey, operationId, time, recoveryProof)).toBe(false);

    // PRF proof should not verify with recovery key
    expect(await checkProof(recoveryKey, operationId, time, prfProof)).toBe(false);
  });

  it('(e-alt) known-answer test: documented HKDF parameters produce expected results', async () => {
    // This test ensures that changing salt or info strings is caught
    const keyBytes = new Uint8Array(16).fill(0x42);
    const key = RecoveryKey.fromBytes(keyBytes);

    // Derive the recovery proof key using documented parameters
    const recoveryKey = await recoveryProofKey(key);

    // Also derive using crypto.subtle directly with documented parameters
    const base = await crypto.subtle.importKey('raw', ab(key.bytes), 'HKDF', false, ['deriveKey']);
    const directKey = await crypto.subtle.deriveKey(
      { name: 'HKDF', hash: 'SHA-256', salt: utf8('doorprints/deletion-proof/recovery'), info: utf8('doorprints/deletion-proof/recovery/1') },
      base,
      { name: 'HMAC', hash: 'SHA-256', length: 256 },
      false,
      ['sign'],
    );

    // Both should produce the same proof for the same operation
    const sig1 = await signProof(recoveryKey, operationId, time);
    const sig2 = await signProof(directKey, operationId, time);

    expect(sig1).toBe(sig2);
  });
});
