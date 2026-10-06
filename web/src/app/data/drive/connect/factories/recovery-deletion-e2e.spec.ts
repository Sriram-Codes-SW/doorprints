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

/**
 * Recovery key deletion end-to-end: real connected folder created via createBackupAdapter.createFolder,
 * then deletion with recovery key (no passkey registered). The folder, keys.json, backups are real.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { utf8, equalBytes } from '../../../crypto/bytes';
import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import type { DeletionContext } from '../../../device-auth/delete-policy';
import type { WebGrant } from '../../../device-auth/web-authorizer';
import { RecoveryKey } from '../../../crypto/recovery-key';
import { LocalStore } from '../../../local-store.service';
import { DRIVE_LAYOUT, FOLDER_MIME } from '../../drive-client';
import type { TokenProvider } from '../../drive-client';
import { FakeDriveServer, InMemoryFakeDrive } from '../../in-memory-fake-drive';
import { InMemoryKeyValueStore } from '../deletion-adapter';
import { createDeletionAdapter } from './deletion-factory';
import { createDriveRuntime, type DriveRuntime } from './runtime';
import { createBackupAdapter } from './backup-factory';
import type { ReadyFolder } from '../../backup/drive-backup-results';
import { Payload } from '../../backup/backup-test-rig';
import { FolderSession } from '../../drive-sync-seams';
import { KeysGuard } from '../../../crypto/keys-guard';
import { kidOf } from '../../../crypto/folder-key';

if (typeof globalThis.PublicKeyCredential === 'undefined') {
  (globalThis as unknown as { PublicKeyCredential: unknown }).PublicKeyCredential = class PublicKeyCredential {
    static async isUserVerifyingPlatformAuthenticatorAvailable() {
      return true;
    }
  };
}

/** Deterministic PRF from credential id + salt + a fixed secret. */
class FakeCredentialsContainer {
  constructor(private readonly prfSecret: Uint8Array = utf8('fake-prf-secret')) {}

  async create(_options: CredentialCreationOptions): Promise<Credential | null> {
    const credId = new Uint8Array(16);
    crypto.getRandomValues(credId);
    const idString = btoa(String.fromCharCode(...credId))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=/g, '');
    return {
      id: idString,
      type: 'public-key',
      rawId: credId.buffer,
      getClientExtensionResults: () => ({ prf: { enabled: true } }),
    } as PublicKeyCredential;
  }

  async get(options: CredentialRequestOptions): Promise<Credential | null> {
    const pubKeyOpts = options.publicKey as PublicKeyCredentialRequestOptions & {
      extensions?: { prf?: { eval?: { first?: BufferSource } } };
    };
    const credIdUint8 = new Uint8Array(pubKeyOpts.allowCredentials![0].id as ArrayBuffer);
    const saltFirst = pubKeyOpts.extensions?.prf?.eval?.first
      ? new Uint8Array(pubKeyOpts.extensions.prf.eval.first as ArrayBuffer)
      : new Uint8Array(32);
    const hashData = new Uint8Array(credIdUint8.length + saltFirst.length + this.prfSecret.length);
    hashData.set(credIdUint8, 0);
    hashData.set(saltFirst, credIdUint8.length);
    hashData.set(this.prfSecret, credIdUint8.length + saltFirst.length);
    const hash = await crypto.subtle.digest('SHA-256', hashData);
    const prfOutput = new Uint8Array(hash).slice(0, 32);
    const idString = btoa(String.fromCharCode(...credIdUint8))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=/g, '');
    const authenticatorData = new Uint8Array(37);
    authenticatorData[32] = 0x05; // user present and user verified
    return {
      id: idString,
      type: 'public-key',
      rawId: credIdUint8.slice().buffer,
      response: { authenticatorData: authenticatorData.buffer },
      getClientExtensionResults: () => ({
        prf: { enabled: true, results: { first: prfOutput.buffer } },
      }),
    } as unknown as PublicKeyCredential;
  }
}

const tokens: TokenProvider = { accessToken: async () => 'test-token' };

const l2Context: DeletionContext = {
  platform: 'WEBSITE',
  deviceLock: false,
  webPrf: true,
  online: true,
  backupsLeft: 3,
};

const noVerifyContext: DeletionContext = {
  platform: 'WEBSITE',
  deviceLock: false,
  webPrf: false, // Policy will refuse this
  online: true,
  backupsLeft: 3,
};

function updateSessionFromReady(rt: DriveRuntime, folder: ReadyFolder): void {
  const deviceKid = kidOf(rt.crypto, rt.deviceKey.publicKey);
  rt.session = new FolderSession(
    folder.rootId,
    deviceKid,
    folder.keys,
    new KeysGuard(rt.crypto, rt.folderTrustStores.keys(folder.rootId)),
    async () => {
      const again = await createBackupAdapter(rt).connect();
      if (again.kind !== 'READY') throw new Error(`keys.json no longer opens: ${again.kind}`);
      return again.folder.keys;
    },
  );
}

describe('recovery key deletion on real connected folder', () => {
  const originalNavigator = globalThis.navigator;
  let fakeCredentials: FakeCredentialsContainer;

  beforeEach(() => {
    fakeCredentials = new FakeCredentialsContainer();
    Object.defineProperty(globalThis, 'navigator', {
      value: { ...originalNavigator, credentials: fakeCredentials, onLine: true },
      configurable: true,
    });
  });

  afterEach(() => {
    Object.defineProperty(globalThis, 'navigator', {
      value: originalNavigator,
      configurable: true,
    });
  });

  async function runtimeWithConnectedFolder(): Promise<{
    rt: DriveRuntime;
    folder: ReadyFolder;
    recoveryKey: RecoveryKey;
    kv: InMemoryKeyValueStore;
    backupAdapter: ReturnType<typeof createBackupAdapter>;
  }> {
    const server = new FakeDriveServer();
    const kv = new InMemoryKeyValueStore();
    const store = new LocalStore();
    await store.ready();
    const rt = await createDriveRuntime({
      tokens,
      local: store,
      crypto: new WebCryptoProvider(),
      drive: new InMemoryFakeDrive(server),
      kv,
    });

    // Create a real connected folder via backup adapter
    const backupAdapter = createBackupAdapter(rt);
    const createOut = await backupAdapter.createFolder();
    if (createOut.connection.kind !== 'READY' || !createOut.recoveryKey) {
      throw new Error('failed to create folder');
    }
    const folder = createOut.connection.folder;
    const recoveryKey = createOut.recoveryKey;

    // Set up the session so deletion adapter can use it
    updateSessionFromReady(rt, folder);

    // Add 2 backups to the Drive
    for (let i = 1; i <= 2; i++) {
      const payload = Payload.of(1000, 5, i);
      const backup = await backupAdapter.backUpNow(folder, payload.source());
      if (backup.kind !== 'done') throw new Error(`backup ${i} failed: ${backup.problem.kind}`);
    }

    return { rt, folder, recoveryKey, kv, backupAdapter };
  }

  it('right key authorizes and deletes all backups', async () => {
    const { rt, folder, recoveryKey, kv, backupAdapter } = await runtimeWithConnectedFolder();
    const recovery = { verify: (key: RecoveryKey) => backupAdapter.verifyRecoveryKey(key) };
    const adapter = createDeletionAdapter(rt, kv, undefined, undefined, recovery);

    // Preflight
    const preflight = await adapter.preflight({ type: 'allBackups' });
    if (preflight.kind === 'refused') {
      throw new Error(`preflight refused: ${preflight.reason}`);
    }
    expect(preflight.kind).toBe('ready');
    if (preflight.kind !== 'ready') throw new Error('preflight');

    // Authorize with recovery key
    const auth = await adapter.authorizeWithRecoveryKey(
      { type: 'allBackups' },
      l2Context,
      preflight.plan.operationId,
      recoveryKey,
    );
    expect(auth.kind).toBe('granted');
    if (auth.kind !== 'granted') throw new Error('authorize');

    // Verify backups exist before deletion
    const backupsFolder = (await rt.drive.list({ parentId: folder.rootId })).files.find(
      (f) => f.appProperties[DRIVE_LAYOUT.role] === 'backups',
    );
    if (!backupsFolder) throw new Error('no backups folder');
    const backupsBefore = (await rt.drive.list({ parentId: backupsFolder.id })).files.filter(
      (f) => f.appProperties[DRIVE_LAYOUT.kind] === 'backup' && !f.trashed,
    );
    expect(backupsBefore.length).toBe(2);

    // Execute deletion
    const outcome = await adapter.execute(preflight.plan, auth.grant);
    expect(outcome.kind).toBe('ran');

    // Verify backups are deleted
    const backupsAfter = (await rt.drive.list({ parentId: backupsFolder.id })).files.filter(
      (f) => f.appProperties[DRIVE_LAYOUT.kind] === 'backup' && !f.trashed,
    );
    expect(backupsAfter.length).toBe(0);

    // Verify keys.json and control are still there
    const keysFile = (await rt.drive.list({ parentId: folder.rootId })).files.find(
      (f) => f.appProperties[DRIVE_LAYOUT.kind] === 'keys',
    );
    expect(keysFile).toBeDefined();
    const controlFile = (await rt.drive.list({ parentId: folder.rootId })).files.find(
      (f) => f.appProperties[DRIVE_LAYOUT.kind] === 'control',
    );
    expect(controlFile).toBeDefined();
  });

  it('wrong key is refused, nothing deleted, forged grant fails', async () => {
    const { rt, folder, recoveryKey, kv, backupAdapter } = await runtimeWithConnectedFolder();
    const recovery = { verify: (key: RecoveryKey) => backupAdapter.verifyRecoveryKey(key) };
    const adapter = createDeletionAdapter(rt, kv, undefined, undefined, recovery);

    // Preflight
    const preflight = await adapter.preflight({ type: 'allBackups' });
    expect(preflight.kind).toBe('ready');
    if (preflight.kind !== 'ready') throw new Error('preflight');

    // Try to authorize with wrong key
    const wrongKey = RecoveryKey.generate(rt.crypto);
    const auth = await adapter.authorizeWithRecoveryKey(
      { type: 'allBackups' },
      l2Context,
      preflight.plan.operationId,
      wrongKey,
    );
    expect(auth.kind).toBe('refused');
    if (auth.kind === 'refused') {
      expect(auth.reason).toBe('RECOVERY_KEY_WRONG');
    }

    // Verify backups still exist
    const backupsFolder = (await rt.drive.list({ parentId: folder.rootId })).files.find(
      (f) => f.appProperties[DRIVE_LAYOUT.role] === 'backups',
    );
    if (!backupsFolder) throw new Error('no backups folder');
    const backups = (await rt.drive.list({ parentId: backupsFolder.id })).files.filter(
      (f) => f.appProperties[DRIVE_LAYOUT.kind] === 'backup' && !f.trashed,
    );
    expect(backups.length).toBe(2);

    // Try to execute with forged grant
    const forgedGrant: WebGrant = {
      id: 999,
      action: 'DELETE_ALL_BACKUPS',
      requirements: {
        level: 'L2',
        factor: 'PASSKEY',
        tickBox: true,
        delaySeconds: 0,
        pairing: 'NONE',
        authValidMs: 60_000,
      },
      grantedAtMs: Date.now(),
    };
    const outcome1 = await adapter.execute(preflight.plan, forgedGrant);
    expect(outcome1.kind).toBe('refused');

    // Try to execute with null grant
    const outcome2 = await adapter.execute(preflight.plan, null);
    expect(outcome2.kind).toBe('refused');

    // Verify backups are STILL there
    const backupsAfter = (await rt.drive.list({ parentId: backupsFolder.id })).files.filter(
      (f) => f.appProperties[DRIVE_LAYOUT.kind] === 'backup' && !f.trashed,
    );
    expect(backupsAfter.length).toBe(2);
  });

  it('after forgetProof, old grant cannot be reused', async () => {
    const { rt, folder, recoveryKey, kv, backupAdapter } = await runtimeWithConnectedFolder();
    const recovery = { verify: (key: RecoveryKey) => backupAdapter.verifyRecoveryKey(key) };
    const adapter = createDeletionAdapter(rt, kv, undefined, undefined, recovery);

    // First authorization and execution
    const preflight = await adapter.preflight({ type: 'allBackups' });
    expect(preflight.kind).toBe('ready');
    if (preflight.kind !== 'ready') throw new Error('preflight');

    const auth = await adapter.authorizeWithRecoveryKey(
      { type: 'allBackups' },
      l2Context,
      preflight.plan.operationId,
      recoveryKey,
    );
    expect(auth.kind).toBe('granted');
    if (auth.kind !== 'granted') throw new Error('authorize');

    const first = await adapter.execute(preflight.plan, auth.grant);
    expect(first.kind).toBe('ran');

    // Verify all backups are deleted
    const backupsFolder = (await rt.drive.list({ parentId: folder.rootId })).files.find(
      (f) => f.appProperties[DRIVE_LAYOUT.role] === 'backups',
    );
    if (!backupsFolder) throw new Error('no backups folder');
    const afterFirst = (await rt.drive.list({ parentId: backupsFolder.id })).files.filter(
      (f) => f.appProperties[DRIVE_LAYOUT.kind] === 'backup' && !f.trashed,
    );
    expect(afterFirst.length).toBe(0);

    // Forget proof and try to use the old grant
    adapter.forgetProof();

    const preflight2 = await adapter.preflight({ type: 'allBackups' });
    expect(preflight2.kind).toBe('ready');
    if (preflight2.kind !== 'ready') throw new Error('preflight2');

    const outcome = await adapter.execute(preflight2.plan, auth.grant);
    expect(outcome.kind).toBe('refused');
  });

  it('policy that refuses never calls recovery verification', async () => {
    const { rt, folder, recoveryKey, kv, backupAdapter } = await runtimeWithConnectedFolder();

    // Create a spy on verifyRecoveryKey
    const verifySpy = vi.spyOn(backupAdapter, 'verifyRecoveryKey');

    const recovery = { verify: (key: RecoveryKey) => backupAdapter.verifyRecoveryKey(key) };
    const adapter = createDeletionAdapter(rt, kv, undefined, undefined, recovery);

    // Try to authorize with a context the policy refuses
    const auth = await adapter.authorizeWithRecoveryKey(
      { type: 'allBackups' },
      noVerifyContext, // webPrf: false, so policy refuses
      'some-op-id',
      recoveryKey,
    );

    // Should refuse before checking recovery key
    expect(auth.kind).toBe('refused');
    // Verify that recovery key was never checked
    expect(verifySpy).not.toHaveBeenCalled();
  });
});

