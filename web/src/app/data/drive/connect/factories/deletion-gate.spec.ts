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
import type { DeletionAction } from '../../drive-deletion-rules';
import type { AuthorizationToken } from '../../drive-deletion-rules';
import { WebAuthorizer } from '../../../device-auth/web-authorizer';
import { FakePrfAuthenticator } from '../../../device-auth/prf-seal';

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
  let rootId: string;
  let clock: () => number;

  beforeEach(async () => {
    server = new FakeDriveServer();
    drive = new InMemoryFakeDrive(server);
    crypto = new WebCryptoProvider();
    kv = new InMemoryKeyValueStore();

    // Create root folder
    rootId = server.putByHand({
      name: 'Doorprints',
      mimeType: FOLDER_MIME,
      appProperties: { [DRIVE_LAYOUT.role]: 'root' },
    }, new Uint8Array()).id;

    // Clock that tracks server time
    clock = () => server.clock.now();

    // Create real gate
    const prfAuthenticator = new FakePrfAuthenticator(crypto);
    webAuthorizer = new WebAuthorizer(crypto, prfAuthenticator, () => null, clock);
    gate = new RealAuthorizationGate(webAuthorizer, clock);

    // Create real service
    const store = new PersistentDeletionStore(kv);
    service = new DriveDeletionService({
      drive,
      gate,
      store,
      isOnline: () => true,
      now: clock,
    });

    // Create a backup to delete
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
    gate.registerGrant(42, { type: 'allBackups' }, 'del-test0000000000000000000');

    const token: AuthorizationToken = {
      level: 'L2',
      issuedAtMs: clock(),
      operationId: 'del-test0000000000000000000',
      proof: '42',
    };

    // First isGenuine should pass and mark spent
    const genuine1 = await gate.isGenuine(token);
    expect(genuine1).toBe(true);

    // Second isGenuine should fail (grant spent)
    const genuine2 = await gate.isGenuine(token);
    expect(genuine2).toBe(false);
  });

  it('(c) Token older than 60s is refused by stillHolds', async () => {
    // Register grant 61s ago
    server.clock.advance(-61_000);
    gate.registerGrant(43, { type: 'allBackups' }, 'del-test0000000000000000000');
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
    gate.registerGrant(44, { type: 'allBackups' }, 'del-correct000000000000000');

    const token: AuthorizationToken = {
      level: 'L2',
      issuedAtMs: clock(),
      operationId: 'del-wrong0000000000000000000',
      proof: '44',
    };

    const genuine = await gate.isGenuine(token);
    expect(genuine).toBe(false);
  });

  it('(e) L1 token valid for its operation', async () => {
    gate.registerGrant(45, { type: 'oneBackup', fileId: 'test' }, 'del-test0000000000000000000');

    const token: AuthorizationToken = {
      level: 'L1',
      issuedAtMs: clock(),
      operationId: 'del-test0000000000000000000',
      proof: '45',
    };

    // Token is valid for its own operation
    const genuine = await gate.isGenuine(token);
    expect(genuine).toBe(true);
  });

  it('(f) Long run crossing 60s stops and is resumable with fresh grant', async () => {
    // Register initial grant
    gate.registerGrant(46, { type: 'allBackups' }, 'del-test0000000000000000000');

    const token: AuthorizationToken = {
      level: 'L2',
      issuedAtMs: clock(),
      operationId: 'del-test0000000000000000000',
      proof: '46',
    };

    // Initially fresh
    let holds = await gate.stillHolds(token, { type: 'allBackups' });
    expect(holds).toBe(true);

    // Advance 61 seconds
    server.clock.advance(61_000);

    // Now stale
    holds = await gate.stillHolds(token, { type: 'allBackups' });
    expect(holds).toBe(false);

    // Register a fresh grant for resume
    gate.registerGrant(47, { type: 'allBackups' }, 'del-test0000000000000000000');

    const freshToken: AuthorizationToken = {
      level: 'L2',
      issuedAtMs: clock(),
      operationId: 'del-test0000000000000000000',
      proof: '47',
    };

    // Fresh token works
    holds = await gate.stillHolds(freshToken, { type: 'allBackups' });
    expect(holds).toBe(true);
  });
});
