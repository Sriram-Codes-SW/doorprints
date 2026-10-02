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
 * Two devices through the REAL factories (S4b-BL-118/128/117): device A makes the folder, device B joins with the
 * recovery key, houses flow both ways through one shared fake Drive. Nothing here sets `runtime.session` or builds a
 * FolderSession: the backup adapter's connect/createFolder/openWithRecoveryKey must do it, or the sync stays
 * "not connected" and these tests fail.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { WebCryptoProvider } from '../../../crypto/crypto-provider';
import { LocalStore } from '../../../local-store.service';
import type { HouseDto } from '../../../../core/models';
import type { TokenProvider } from '../../drive-client';
import { FakeDriveServer, InMemoryFakeDrive } from '../../in-memory-fake-drive';
import { createDriveRuntime, type DriveRuntime } from './runtime';
import { createLazyBackupAdapterProxy } from './backup-factory';
import { createLazySyncAdapterProxy } from './sync-factory';

const tokens: TokenProvider = { accessToken: async () => 'test-token' };

interface Device {
  readonly store: LocalStore;
  readonly runtime: () => Promise<DriveRuntime>;
  readonly backup: ReturnType<typeof createLazyBackupAdapterProxy>;
  readonly sync: ReturnType<typeof createLazySyncAdapterProxy>;
}

async function device(server: FakeDriveServer): Promise<Device> {
  const store = new LocalStore();
  await store.ready();
  let pending: Promise<DriveRuntime> | null = null;
  const runtime = () =>
    (pending ??= createDriveRuntime({
      tokens,
      local: store,
      crypto: new WebCryptoProvider(),
      drive: new InMemoryFakeDrive(server),
    }));
  return { store, runtime, backup: createLazyBackupAdapterProxy(runtime), sync: createLazySyncAdapterProxy(runtime) };
}

function house(id: string, label: string): HouseDto {
  return { id, label, lat: 12.97, lon: 77.59, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0 };
}

async function synced(label: string, run: Promise<{ state: string; skipped: unknown[]; error?: string }>): Promise<void> {
  const status = await run;
  expect(status.state, `${label}: ${JSON.stringify(status)}`).toBe('synced');
}

describe('two devices through the real factories', () => {
  let server: FakeDriveServer;
  let a: Device;
  let b: Device;

  beforeEach(async () => {
    server = new FakeDriveServer();
    a = await device(server);
    b = await device(server);
  });

  it('A makes the folder, B joins with the recovery key, a house flows A -> B -> A, and a delete reaches B', async () => {
    // A: first connect makes the folder; the recovery key is returned once; the session is set by the adapter.
    expect((await a.runtime()).session).toBeUndefined();
    const created = await a.backup.createFolder();
    expect(created.connection.kind).toBe('READY');
    expect(created.recoveryKey).not.toBeNull();
    expect((await a.runtime()).session).toBeDefined();

    // A writes a house and syncs it.
    await a.store.saveHouse(house('h1', 'Lake View'));
    const dirtyBefore = (await a.store.dirtyHouses()).length;
    expect(dirtyBefore).toBe(1);
    const sa = await a.sync.syncNow();
    expect(sa.state).toBe('synced');
    expect((await a.store.dirtyHouses()).length).toBe(0);

    // B: cannot open the existing folder silently; the recovery key opens it and sets B's session.
    const joining = await b.backup.connect();
    expect(['NEEDS_ENROLMENT', 'NEEDS_RECOVERY_KEY']).toContain(joining.kind);
    expect((await b.runtime()).session).toBeUndefined();
    const opened = await b.backup.openWithRecoveryKey(created.recoveryKey!);
    expect(opened.kind).toBe('READY');
    expect((await b.runtime()).session).toBeDefined();

    // B pulls A's house with the same fields.
    await synced('B first sync', b.sync.syncNow());
    const onB = (await b.store.allHouses()).find((h) => h.id === 'h1');
    expect(onB?.label).toBe('Lake View');

    // B edits; both sync; A sees B's edit (the later edit wins).
    await b.store.saveHouse({ ...house('h1', 'Lake View, 2nd floor'), syncVersion: 0 }, Date.now() + 5_000);
    await synced('B after edit', b.sync.syncNow());
    await synced('A after B edit', a.sync.syncNow());
    expect((await a.store.allHouses()).find((h) => h.id === 'h1')?.label).toBe('Lake View, 2nd floor');

    // A deletes it; B learns of the tombstone.
    await a.store.deleteHouse('h1', Date.now() + 10_000); // after B's edit, so the delete wins
    await synced('A after delete', a.sync.syncNow());
    await synced('B after delete', b.sync.syncNow());
    const gone = (await b.store.allHouses()).find((h) => h.id === 'h1');
    expect(gone === undefined || gone.deleted === true).toBe(true);
  });

  it('sync before any connect is a typed "not connected" status and touches nothing', async () => {
    const status = await a.sync.syncNow();
    expect(status.state).toBe('error');
    expect(status.error).toMatch(/not connected/i);
    expect(server.allFiles().length).toBe(0);
  });

  it('a wrong recovery key does not open the folder and sync stays not connected', async () => {
    const created = await a.backup.createFolder();
    expect(created.connection.kind).toBe('READY');
    const { RecoveryKey } = await import('../../../crypto/recovery-key');
    const wrong = RecoveryKey.generate(new WebCryptoProvider());
    const result = await b.backup.openWithRecoveryKey(wrong);
    expect(result.kind).not.toBe('READY');
    expect((await b.runtime()).session).toBeUndefined();
    expect((await b.sync.syncNow()).state).toBe('error');
  });
});
