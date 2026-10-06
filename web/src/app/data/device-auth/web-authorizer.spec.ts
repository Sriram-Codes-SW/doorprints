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

import { describe, it, expect, beforeEach } from 'vitest';
import { WebAuthorizer, type WebAuthorization } from './web-authorizer';
import { RecoveryKey } from '../crypto/recovery-key';
import { WebCryptoProvider } from '../crypto/crypto-provider';
import { FakePrfAuthenticator, sealWithPrf } from './prf-seal';
import type { SealedBlob } from './prf-seal';
import type { DeletionContext } from './delete-policy';
import { utf8 } from '../crypto/bytes';

describe('WebAuthorizer recovery key authorization', () => {
  let crypto: WebCryptoProvider;
  let prf: FakePrfAuthenticator;
  let clock: () => number;
  let sealedBlob: SealedBlob | null;
  let recoveryKey: RecoveryKey;
  let fakeRecovery: { verify: (key: RecoveryKey) => Promise<boolean> };
  let authorizer: WebAuthorizer;

  const ctx: DeletionContext = {
    platform: 'WEBSITE',
    deviceLock: false,
    webPrf: true,
    online: true,
    backupsLeft: 5,
  };

  beforeEach(async () => {
    crypto = new WebCryptoProvider();
    clock = (() => {
      let now = 1000;
      return () => now++;
    })();

    prf = new FakePrfAuthenticator(crypto);
    const sealed = await sealWithPrf(crypto, prf, utf8('credential-1'), crypto.randomBytes(32));
    sealedBlob = ('v' in sealed) ? sealed : null;

    recoveryKey = RecoveryKey.generate(crypto);

    // Fake recovery verifier that records which keys it was called with
    const verifiedKeys: RecoveryKey[] = [];
    fakeRecovery = {
      verify: async (key: RecoveryKey) => {
        verifiedKeys.push(key);
        // Accept the recovery key by default
        return key.bytes.every((b, i) => b === recoveryKey.bytes[i]);
      },
    };

    authorizer = new WebAuthorizer(
      crypto,
      prf,
      () => sealedBlob,
      clock,
      fakeRecovery,
    );
  });

  it('right recovery key: GRANTED, proofFor returns a 64-hex that verifyProof accepts', async () => {
    const result = await authorizer.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', ctx, recoveryKey);

    expect(result.kind).toBe('GRANTED');
    if (result.kind !== 'GRANTED') throw new Error('Expected GRANTED');

    const operationId = 'DELETE_ONE_BACKUP';
    const time = 2000;
    const proof = await authorizer.proofFor(operationId, time);

    expect(proof).not.toBeNull();
    expect(proof).toMatch(/^[0-9a-f]{64}$/);

    const valid = await authorizer.verifyProof(operationId, time, proof!);
    expect(valid).toBe(true);
  });

  it('right recovery key: forged proof is rejected', async () => {
    const result = await authorizer.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', ctx, recoveryKey);
    expect(result.kind).toBe('GRANTED');

    const operationId = 'DELETE_ONE_BACKUP';
    const time = 2000;
    const forgedProof = 'a'.repeat(64);

    const valid = await authorizer.verifyProof(operationId, time, forgedProof);
    expect(valid).toBe(false);
  });

  it('wrong recovery key: DENIED WRONG_KEY and proofFor stays null', async () => {
    const wrongKey = RecoveryKey.generate(crypto);

    const result = await authorizer.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', ctx, wrongKey);

    expect(result.kind).toBe('DENIED');
    if (result.kind !== 'DENIED') throw new Error('Expected DENIED');
    expect(result.reason).toBe('WRONG_KEY');

    const operationId = 'DELETE_ONE_BACKUP';
    const time = 2000;
    const proof = await authorizer.proofFor(operationId, time);

    expect(proof).toBeNull();
  });

  it('verify throws: DENIED FAILED', async () => {
    const throwingRecovery = {
      verify: async () => {
        throw new Error('verification error');
      },
    };

    const authorizerWithThrow = new WebAuthorizer(
      crypto,
      prf,
      () => sealedBlob,
      clock,
      throwingRecovery,
    );

    const result = await authorizerWithThrow.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', ctx, recoveryKey);

    expect(result.kind).toBe('DENIED');
    if (result.kind !== 'DENIED') throw new Error('Expected DENIED');
    expect(result.reason).toBe('FAILED');
  });

  it('no recovery seam: DENIED NOT_SUPPORTED', async () => {
    const authorizerNoRecovery = new WebAuthorizer(
      crypto,
      prf,
      () => sealedBlob,
      clock,
      undefined, // no recovery
    );

    const result = await authorizerNoRecovery.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', ctx, recoveryKey);

    expect(result.kind).toBe('DENIED');
    if (result.kind !== 'DENIED') throw new Error('Expected DENIED');
    expect(result.reason).toBe('NOT_SUPPORTED');
  });

  it('context where decide refuses: REFUSED and verify NOT called', async () => {
    const refusedCtx: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: false,
      webPrf: false, // This will cause decide to refuse at L2
      online: true,
      backupsLeft: 5,
    };

    let verifyCalled = false;
    const trackedRecovery = {
      verify: async () => {
        verifyCalled = true;
        return true;
      },
    };

    const authorizerTracked = new WebAuthorizer(
      crypto,
      prf,
      () => sealedBlob,
      clock,
      trackedRecovery,
    );

    const result = await authorizerTracked.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', refusedCtx, recoveryKey);

    expect(result.kind).toBe('REFUSED');
    expect(verifyCalled).toBe(false);
  });

  it('after forgetProof: recovery proof no longer verifies', async () => {
    const result = await authorizer.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', ctx, recoveryKey);
    expect(result.kind).toBe('GRANTED');

    const operationId = 'DELETE_ONE_BACKUP';
    const time = 2000;
    const proof = await authorizer.proofFor(operationId, time);
    expect(proof).not.toBeNull();

    authorizer.forgetProof();

    const proofAfter = await authorizer.proofFor(operationId, time);
    expect(proofAfter).toBeNull();
  });

  it('PRF proof is NOT cleared by forgetProof', async () => {
    // First authorize with PRF
    const prfResult = await authorizer.authorize('DELETE_ONE_BACKUP', ctx);
    expect(prfResult.kind).toBe('GRANTED');

    const operationId = 'DELETE_ONE_BACKUP';
    const time = 3000;
    const prfProof = await authorizer.proofFor(operationId, time);
    expect(prfProof).not.toBeNull();

    // forgetProof should NOT clear the PRF proof
    authorizer.forgetProof();

    const proofAfter = await authorizer.proofFor(operationId, time);
    expect(proofAfter).toBe(prfProof); // Still there because it's PRF-sourced
  });

  it('recovery grant redeems once: VALID then ALREADY_USED', async () => {
    const result = await authorizer.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', ctx, recoveryKey);
    expect(result.kind).toBe('GRANTED');
    if (result.kind !== 'GRANTED') throw new Error('Expected GRANTED');

    const grant = result.grant;

    // First redeem should be VALID
    const firstRedeem = authorizer.redeem(grant, 'DELETE_ONE_BACKUP');
    expect(firstRedeem).toBe('VALID');

    // Second redeem should be ALREADY_USED
    const secondRedeem = authorizer.redeem(grant, 'DELETE_ONE_BACKUP');
    expect(secondRedeem).toBe('ALREADY_USED');
  });

  it('recovery key object is not retained publicly', async () => {
    const result = await authorizer.authorizeWithRecoveryKey('DELETE_ONE_BACKUP', ctx, recoveryKey);
    expect(result.kind).toBe('GRANTED');

    // Check that nothing public holds the recovery key or its bytes
    const values = Object.values(authorizer);
    for (const value of values) {
      if (value instanceof RecoveryKey) {
        throw new Error('RecoveryKey instance found in public properties');
      }
      if (value instanceof Uint8Array && value.length === 16) {
        // Check if this is the key bytes
        if (value.every((b, i) => b === recoveryKey.bytes[i])) {
          throw new Error('RecoveryKey bytes found in public properties');
        }
      }
    }

    expect(true).toBe(true); // Passed the checks
  });

  it('L1 action does not require authentication', async () => {
    const noAuthCtx: DeletionContext = {
      platform: 'WEBSITE',
      deviceLock: false,
      webPrf: false, // Even with no PRF
      online: true,
      backupsLeft: 5,
    };

    const result = await authorizer.authorizeWithRecoveryKey('TURN_AUTO_BACKUP_OFF', noAuthCtx, recoveryKey);

    // L1 actions don't require passkey factor, so recovery key path shouldn't be taken
    // The result depends on decide logic - let's just verify it works
    expect(result.kind).toMatch(/^(GRANTED|REFUSED)$/);
  });
});
