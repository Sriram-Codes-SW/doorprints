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
import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import { FakeDriveServer, InMemoryFakeDrive } from '../../in-memory-fake-drive';
import { DRIVE_LAYOUT, FOLDER_MIME } from '../../drive-client';
import { DriveDeletionService } from '../../drive-deletion';
import { InMemoryKeyValueStore, PersistentDeletionStore } from '../deletion-adapter';
import { RealAuthorizationGate } from './deletion-gate';
import type { AuthorizationToken } from '../../drive-deletion-rules';
import { WebAuthorizer } from '../../../device-auth/web-authorizer';
import { FakePrfAuthenticator, sealWithPrf } from '../../../device-auth/prf-seal';
import type { SealedBlob } from '../../../device-auth/prf-seal';
import type { DeletionContext } from '../../../device-auth/delete-policy';
import { utf8 } from '../../../crypto/bytes';

const web: DeletionContext = {
  platform: 'WEBSITE',
  deviceLock: false,
  webPrf: true,
  online: true,
  backupsLeft: 5,
};

/**
 * Tests for RealAuthorizationGate through real DriveDeletionService.
 * Constructs real pieces directly (gate, service, authorizer) with fake drive.
 */
describe('RealAuthorizationGate', () => {
  let server: FakeDriveServer;
  let drive: InMemoryFakeDrive;
  let crypto: WebCryptoProvider;
  let kv: InMemoryKeyValueStore;
  let gate: RealAuthorizationGate;
  let service: DriveDeletionService;
  let webAuthorizer: WebAuthorizer;
  let prf: FakePrfAuthenticator;
  let rootId: string;
  let clock: () => number;
  let blob: SealedBlob;

  beforeEach(async () => {
    server = new FakeDriveServer();
    drive = new InMemoryFakeDrive(server);
    crypto = new WebCryptoProvider();
    kv = new InMemoryKeyValueStore();

    rootId = server.putByHand({
      name: 'Doorprints',
      mimeType: FOLDER_MIME,
      appProperties: { [DRIVE_LAYOUT.role]: 'root' },
    }, new Uint8Array()).id;

    clock = () => server.clock.now();

    prf = new FakePrfAuthenticator(crypto);
    const sealed = await sealWithPrf(crypto, prf, utf8('credential-1'), crypto.randomBytes(32));
    if (!('v' in sealed)) throw new Error('seal failed');
    blob = sealed;
    webAuthorizer = new WebAuthorizer(crypto, prf, () => blob, clock);
    gate = new RealAuthorizationGate(webAuthorizer, clock);

    const store = new PersistentDeletionStore(kv);
    service = new DriveDeletionService({
      drive,
      gate,
      store,
      isOnline: () => true,
      now: clock,
    });

    const backupsFolder = server.putByHand({
      name: 'Backups',
      mimeType: FOLDER_MIME,
      parents: [rootId],
      appProperties: { [DRIVE_LAYOUT.role]: 'backups' },
    }, new Uint8Array()).id;

    server.putByHand({
      name: 'backup-1.zip',
      mimeType: 'application/octet-stream',
      parents: [backupsFolder],
      appProperties: {
        [DRIVE_LAYOUT.kind]: 'backup',
        [DRIVE_LAYOUT.state]: 'complete',
      },
    }, new Uint8Array(1024));
  });

  async function issuedL2(operationId: string): Promise<AuthorizationToken> {
    const auth = await webAuthorizer.authorize('DELETE_ALL_BACKUPS', web);
    if (auth.kind !== 'GRANTED') throw new Error(`not granted: ${JSON.stringify(auth)}`);
    gate.registerGrant(auth.grant.id, { type: 'allBackups' }, operationId, auth.grant.grantedAtMs);
    const proof = await webAuthorizer.proofFor(operationId, auth.grant.grantedAtMs);
    if (!proof) throw new Error('no deletion proof');
    return {
      level: 'L2',
      issuedAtMs: auth.grant.grantedAtMs,
      operationId,
      proof,
    };
  }

  it('(a) Forged token (never issued) is rejected', async () => {
    const forgedToken: AuthorizationToken = {
      level: 'L1',
      issuedAtMs: clock(),
      operationId: 'del-test0000000000000000000',
      proof: '9999',
    };

    const genuine = await gate.isGenuine(forgedToken);
    expect(genuine).toBe(false);
  });

  it('(b) Real token used twice is rejected on second use', async () => {
    const token = await issuedL2('del-test0000000000000000000');

    const genuine1 = await gate.isGenuine(token);
    expect(genuine1).toBe(true);

    const genuine2 = await gate.isGenuine(token);
    expect(genuine2).toBe(false);
  });

  it('(b3) a forged 64-hex proof registered in-page cannot pass isGenuine', async () => {
    gate.registerGrant(44, { type: 'allBackups' }, 'del-test0000000000000000000', clock());
    const token: AuthorizationToken = {
      level: 'L2',
      issuedAtMs: clock(),
      operationId: 'del-test0000000000000000000',
      proof: 'ab'.repeat(32),
    };
    expect(await gate.isGenuine(token)).toBe(false);
  });

  it('(b4) an L2 token whose proof is only the grant id is rejected', async () => {
    const token = await issuedL2('del-test0000000000000000000');
    expect(await gate.isGenuine({ ...token, proof: '1' })).toBe(false);
    expect(await gate.isGenuine(token)).toBe(true);
  });

  it('(b2) a grant id registered in-page without the PRF cannot pass isGenuine', async () => {
    gate.registerGrant(42, { type: 'allBackups' }, 'del-test0000000000000000000', clock());

    const token: AuthorizationToken = {
      level: 'L2',
      issuedAtMs: clock(),
      operationId: 'del-test0000000000000000000',
      proof: '42',
    };

    expect(await gate.isGenuine(token)).toBe(false);
  });

  it('(c) Token older than 60s is refused by stillHolds', async () => {
    server.clock.advance(-61_000);
    gate.registerGrant(43, { type: 'allBackups' }, 'del-test0000000000000000000', clock());
    server.clock.advance(61_000);

    const token: AuthorizationToken = {
      level: 'L2',
      issuedAtMs: clock() - 61000,
      operationId: 'del-test0000000000000000000',
      proof: '43',
    };

    const holds = await gate.stillHolds(token, { type: 'allBackups' });
    expect(holds).toBe(false);
  });

  it('(d) Token for different operationId is rejected', async () => {
    const token = await issuedL2('del-correct000000000000000');
    const genuine = await gate.isGenuine({ ...token, operationId: 'del-wrong0000000000000000000' });
    expect(genuine).toBe(false);
  });

  it('(e) L1 token valid for its operation', async () => {
    const auth = await webAuthorizer.authorize('DELETE_ONE_BACKUP', web);
    if (auth.kind !== 'GRANTED') throw new Error('not granted');
    gate.registerGrant(auth.grant.id, { type: 'oneBackup', fileId: 'test' }, 'del-test0000000000000000000', auth.grant.grantedAtMs);

    const token: AuthorizationToken = {
      level: 'L1',
      issuedAtMs: auth.grant.grantedAtMs,
      operationId: 'del-test0000000000000000000',
      proof: String(auth.grant.id),
    };

    const genuine = await gate.isGenuine(token);
    expect(genuine).toBe(true);
  });

  it('(f) Long run crossing 60s stops and is resumable with fresh grant', async () => {
    const token = await issuedL2('del-test0000000000000000000');

    let holds = await gate.stillHolds(token, { type: 'allBackups' });
    expect(holds).toBe(true);

    server.clock.advance(61_000);

    holds = await gate.stillHolds(token, { type: 'allBackups' });
    expect(holds).toBe(false);

    const fresh = await issuedL2('del-test0000000000000000000');
    holds = await gate.stillHolds(fresh, { type: 'allBackups' });
    expect(holds).toBe(true);
  });

  it('a 64-hex proof that was not signed with the passkey key is rejected even though a grant was registered', async () => {
    const token = await issuedL2('op-hmac');
    const forged = { ...token, proof: 'a'.repeat(64) };

    expect(forged.proof).not.toBe(token.proof);
    expect(await gate.isGenuine(forged)).toBe(false);
    expect(await gate.stillHolds(forged, { type: 'allBackups' })).toBe(false);
    expect(await gate.isGenuine(token)).toBe(true);
  });

  it('a proof signed for a different operationId or issuedAtMs is rejected', async () => {
    const token = await issuedL2('op-correct');

    // Proof signed for a different operationId
    const wrongOpProof = await webAuthorizer.proofFor('other-op', token.issuedAtMs);
    if (!wrongOpProof) throw new Error('no proof for other-op');
    const wrongOp = { ...token, proof: wrongOpProof };
    expect(await gate.isGenuine(wrongOp)).toBe(false);

    // Proof for the right operation but different issuedAtMs
    const wrongTimeProof = await webAuthorizer.proofFor(token.operationId, token.issuedAtMs + 1);
    if (!wrongTimeProof) throw new Error('no proof for different time');
    const wrongTime = { ...token, proof: wrongTimeProof, issuedAtMs: token.issuedAtMs + 1 };
    expect(await gate.isGenuine(wrongTime)).toBe(false);
  });
});
