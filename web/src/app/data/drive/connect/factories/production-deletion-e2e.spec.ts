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
 * L2 delete through the production factories (createDriveRuntime, createDeletionAdapter, RealAuthorizationGate,
 * WebAuthorizer, WebAuthnPrfAuthenticator). Only navigator.credentials (PRF output) and the Drive HTTP layer are faked.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { utf8 } from '../../../crypto/bytes';
import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import type { DeletionContext } from '../../../device-auth/delete-policy';
import type { WebGrant } from '../../../device-auth/web-authorizer';
import { LocalStore } from '../../../local-store.service';
import { DRIVE_LAYOUT, FOLDER_MIME } from '../../drive-client';
import type { TokenProvider } from '../../drive-client';
import { FakeDriveServer, InMemoryFakeDrive } from '../../in-memory-fake-drive';
import type { FolderSession } from '../../drive-sync-seams';
import { InMemoryKeyValueStore } from '../deletion-adapter';
import { createDeletionAdapter } from './deletion-factory';
import { createDriveRuntime, type DriveRuntime } from './runtime';

if (typeof globalThis.PublicKeyCredential === 'undefined') {
  (globalThis as unknown as { PublicKeyCredential: unknown }).PublicKeyCredential = class PublicKeyCredential {
    static async isUserVerifyingPlatformAuthenticatorAvailable() {
      return true;
    }
  };
}

/** Deterministic PRF from credential id + salt + a fixed secret. Same shape as the authenticator unit fake. */
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
    return {
      id: 'assertion',
      type: 'public-key',
      rawId: credIdUint8.buffer,
      getClientExtensionResults: () => ({
        prf: { enabled: true, results: { first: prfOutput.buffer } },
      }),
    } as PublicKeyCredential;
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

function session(rootId: string): FolderSession {
  return {
    rootId,
    deviceId: 'test-device-id',
    keys: {} as FolderSession['keys'],
    deviceKid: new Uint8Array(16),
    guard: {} as FolderSession['guard'],
    refresh: async () => ({} as FolderSession['keys']),
  } as FolderSession;
}

function plantFolder(server: FakeDriveServer): string {
  const rootId = server.putByHand({
    name: 'Doorprints',
    mimeType: FOLDER_MIME,
    appProperties: { [DRIVE_LAYOUT.role]: 'root' },
  }).id;
  const backupsId = server.putByHand({
    name: 'Backups',
    mimeType: FOLDER_MIME,
    parents: [rootId],
    appProperties: { [DRIVE_LAYOUT.role]: 'backups' },
  }).id;
  for (let i = 1; i <= 3; i++) {
    server.putByHand(
      {
        name: `backup-${i}.zip`,
        mimeType: 'application/octet-stream',
        parents: [backupsId],
        appProperties: {
          [DRIVE_LAYOUT.kind]: 'backup',
          [DRIVE_LAYOUT.createdAt]: String(i * 1000),
          [DRIVE_LAYOUT.state]: 'complete',
        },
      },
      new Uint8Array(100),
    );
  }
  return rootId;
}

describe('production deletion factories (PRF + Drive HTTP faked only)', () => {
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

  async function runtimeOver(
    server: FakeDriveServer,
    kv: InMemoryKeyValueStore,
    rootId: string,
  ): Promise<DriveRuntime> {
    const store = new LocalStore();
    await store.ready();
    const rt = await createDriveRuntime({
      tokens,
      local: store,
      crypto: new WebCryptoProvider(),
      drive: new InMemoryFakeDrive(server),
      kv,
    });
    rt.session = session(rootId);
    return rt;
  }

  it('passkey registered, L2 delete completes, grant cannot be reused, reload still sees the passkey', async () => {
    const server = new FakeDriveServer();
    const rootId = plantFolder(server);
    const kv = new InMemoryKeyValueStore();
    const rt1 = await runtimeOver(server, kv, rootId);
    const adapter1 = createDeletionAdapter(rt1);

    expect(await adapter1.registerPasskey()).toBe('registered');
    expect(await adapter1.passkeyStatus()).toBe('registered');

    const preflight = await adapter1.preflight({ type: 'allBackups' });
    expect(preflight.kind).toBe('ready');
    if (preflight.kind !== 'ready') throw new Error('preflight');

    const auth = await adapter1.authorize({ type: 'allBackups' }, l2Context);
    expect(auth.kind).toBe('granted');
    if (auth.kind !== 'granted') throw new Error('authorize');

    const first = await adapter1.execute(preflight.plan, auth.grant);
    expect(first.kind).toBe('ran');

    const reuse = await adapter1.execute(preflight.plan, auth.grant);
    expect(reuse.kind).toBe('refused');
    if (reuse.kind === 'refused') expect(reuse.reason).toBe('NOT_AUTHORIZED');

    const rt2 = await runtimeOver(server, kv, rootId);
    const adapter2 = createDeletionAdapter(rt2);
    expect(await adapter2.passkeyStatus()).toBe('registered');
  });

  it('a grant id registered in-page without the PRF cannot delete', async () => {
    const server = new FakeDriveServer();
    const rootId = plantFolder(server);
    const kv = new InMemoryKeyValueStore();
    const rt = await runtimeOver(server, kv, rootId);
    const adapter = createDeletionAdapter(rt);

    expect(await adapter.registerPasskey()).toBe('registered');

    const preflight = await adapter.preflight({ type: 'allBackups' });
    expect(preflight.kind).toBe('ready');
    if (preflight.kind !== 'ready') throw new Error('preflight');

    const forged: WebGrant = {
      id: 42,
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
    const outcome = await adapter.execute(preflight.plan, forged);
    expect(outcome.kind).toBe('refused');
    if (outcome.kind === 'refused') expect(outcome.reason).toBe('NOT_AUTHORIZED');
  });
});
