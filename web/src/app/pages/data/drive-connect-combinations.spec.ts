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

import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import '../../i18n/all-dictionaries';
import { sha256Of } from '../../data/crypto/crypto-provider';
import { kidOf } from '../../data/crypto/folder-key';
import { RecoveryKey } from '../../data/crypto/recovery-key';
import { DriveBackupAdapter } from '../../data/drive/connect/backup-adapter';
import type { DriveDeletionAdapter } from '../../data/drive/connect/deletion-adapter';
import type { DriveSyncAdapter } from '../../data/drive/connect/sync-adapter';
import { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import { Rig } from '../../data/drive/backup/backup-test-rig';
import { DRIVE_LAYOUT, FOLDER_MIME } from '../../data/drive/drive-client';
import { FakeDriveServer } from '../../data/drive/in-memory-fake-drive';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { TranslationService } from '../../i18n/translation.service';
import { DriveConnectComponent } from './drive-connect';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

/** A button click starts crypto that outlives one timer tick. Poll until the card has settled. */
async function settle(
  fixture: { detectChanges(): void },
  host: HTMLElement,
  ready: (text: string) => boolean,
): Promise<string> {
  const deadline = Date.now() + 20000;
  let text = host.textContent ?? '';
  while (Date.now() < deadline) {
    await flush();
    fixture.detectChanges();
    text = host.textContent ?? '';
    if (ready(text)) return text;
  }
  return text;
}

function stubSync(): DriveSyncAdapter {
  return {
    syncNow: async () => ({ state: 'synced', lastSyncAt: null, skipped: [] }),
    getPhotoSettings: async () => ({ uploadOnMobileData: false }),
    pendingPhotoBytes: async () => 0,
    setPhotosWifiOnly: () => undefined,
    uploadPhotosNowOverMobile: async () => ({ granted: true }),
  } as unknown as DriveSyncAdapter;
}

function stubDeletion(): DriveDeletionAdapter {
  return {
    preflight: async () => ({ kind: 'refused', reason: 'USE_PHONE' }),
    decide: () => ({ outcome: 'REFUSED', reason: 'USE_PHONE' }),
    authorize: async () => ({ kind: 'refused', reason: 'USE_PHONE' }),
    execute: async () => ({ kind: 'refused', reason: 'USE_PHONE' }),
    resume: async () => ({ kind: 'refused', reason: 'USE_PHONE' }),
    confirmGate: () => ({ tickBoxRequired: true, delayMs: 0 }),
    registerPasskey: async () => null,
    passkeyStatus: async () => 'none',
  } as unknown as DriveDeletionAdapter;
}

function serviceFor(rig: Rig): DriveConnectService {
  const adapter = new DriveBackupAdapter(rig.service, rig.service.imports, rig.drive, rig.state);
  const m = new Map<string, string>();
  const prefs = {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
  };
  return new DriveConnectService(adapter, stubSync(), stubDeletion(), { clientId: 'client' }, null, prefs);
}

async function render(service: DriveConnectService) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveConnectComponent],
    providers: [
      { provide: DriveConnectService, useValue: service },
      { provide: Announcer, useValue: { announce: () => undefined } },
      { provide: ConfirmService, useValue: { ask: async () => true } },
    ],
  });
  const i18n = TestBed.inject(TranslationService);
  const fixture = TestBed.createComponent(DriveConnectComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await flush();
  fixture.detectChanges();
  return {
    host: fixture.nativeElement as HTMLElement,
    fixture,
    component: fixture.componentInstance,
    i18n,
  };
}

function click(host: HTMLElement, label: string): void {
  const button = [...host.querySelectorAll('button')].find((b) => (b.textContent ?? '').includes(label));
  expect(button, label).toBeTruthy();
  button!.click();
}

function liveRoots(server: FakeDriveServer) {
  return server.allFiles().filter((f) => f.appProperties[DRIVE_LAYOUT.role] === 'root' && !f.trashed);
}

function plantRoot(server: FakeDriveServer): string {
  return server.putByHand({
    name: 'Doorprints', mimeType: FOLDER_MIME, parents: [],
    appProperties: { ...DRIVE_LAYOUT.root.appProperties },
  }).id;
}

afterEach(() => TestBed.resetTestingModule());

describe('Drive connect card combinations', () => {
  it('a deleted folder: Connect and Try again each create a folder and show the recovery key', async () => {
    const server = new FakeDriveServer();
    const rig = await Rig.make(server);
    const folder = await rig.created();
    const oldRoot = folder.rootId;
    server.trashByHand(oldRoot);
    let creates = 0;
    const orig = rig.service.createFolder.bind(rig.service);
    rig.service.createFolder = async (withKey: boolean) => {
      creates += 1;
      return orig(withKey);
    };
    const service = serviceFor(rig);
    const { host, component, fixture, i18n } = await render(service);

    component['error'].set('driveConnect.folderGone');
    fixture.detectChanges();
    let text = host.textContent ?? '';
    const intro = i18n.t('data.intro');
    const gone = i18n.t('driveConnect.folderGone');
    expect(text).toContain(i18n.t('driveConnect.heading'));
    expect(text).toContain(gone);
    expect(text).toContain(i18n.t('driveConnect.connect'));
    expect(text).toContain(i18n.t('common.retry'));
    expect(text.includes(intro) && text.includes(gone)).toBe(false);

    click(host, i18n.t('driveConnect.connect'));
    text = await settle(fixture, host, (t) => t.includes(i18n.t('driveConnect.firstConnect')));
    expect(creates).toBe(1);
    expect(host.querySelector('.recovery-key-box code')?.textContent?.length).toBeGreaterThan(10);
    expect(text.includes(intro) && text.includes(gone)).toBe(false);
    expect(host.querySelector('.error-box')).toBeNull();
    expect(liveRoots(server)).toHaveLength(1);
    expect(liveRoots(server)[0].id).not.toBe(oldRoot);

    const again = liveRoots(server)[0].id;
    server.trashByHand(again);
    await service.disconnect();
    component['recoveryKey'].set(null);
    component['error'].set('driveConnect.folderGone');
    fixture.detectChanges();
    text = host.textContent ?? '';
    expect(text.includes(intro) && text.includes(gone)).toBe(false);
    click(host, i18n.t('common.retry'));
    text = await settle(fixture, host, (t) => t.includes(i18n.t('driveConnect.firstConnect')));
    expect(creates).toBe(2);
    expect(text).toContain(i18n.t('driveConnect.firstConnect'));
    expect(text.includes(intro) && text.includes(gone)).toBe(false);
    expect(host.querySelector('.error-box')).toBeNull();
    expect(liveRoots(server)).toHaveLength(1);
    expect(liveRoots(server)[0].id).not.toBe(again);
  });

  it('after the occupied-folder sentence, deleting the folder and trying again creates one', async () => {
    const server = new FakeDriveServer();
    const rig = await Rig.make(server);
    const rootId = plantRoot(server);
    const kept = server.putByHand({
      name: 'note.txt', mimeType: 'text/plain', parents: [rootId], appProperties: {},
    }, new Uint8Array([9, 9, 9]));
    const service = serviceFor(rig);
    const { host, component, fixture, i18n } = await render(service);
    await component.onConnect();
    await flush();
    fixture.detectChanges();
    expect(host.textContent).toContain(i18n.t('driveProblem.FOLDER_WITHOUT_KEYS'));
    expect(server.contentOf(kept.id)).toEqual(new Uint8Array([9, 9, 9]));
    expect(server.allFiles().filter((f) => f.appProperties[DRIVE_LAYOUT.kind] === 'keys')).toHaveLength(0);
    expect(host.querySelector('.recovery-key-box')).toBeNull();

    server.trashByHand(rootId);
    click(host, i18n.t('common.retry'));
    const text = await settle(fixture, host, (t) => t.includes(i18n.t('driveConnect.firstConnect')));
    expect(text).toContain(i18n.t('driveConnect.firstConnect'));
    expect(host.querySelector('.recovery-key-box code')?.textContent?.length).toBeGreaterThan(10);
    expect(text.includes(i18n.t('data.intro')) && text.includes(i18n.t('driveConnect.folderGone'))).toBe(false);
    expect(server.contentOf(kept.id)).toEqual(new Uint8Array([9, 9, 9]));
    expect(liveRoots(server)).toHaveLength(1);
    expect(liveRoots(server)[0].id).not.toBe(rootId);
  });

  it('an interrupted create continues, shows Connected, and does not replace a file or show a new key', async () => {
    const server = new FakeDriveServer();
    const rig = await Rig.make(server);
    const folder = await rig.created();
    const keysBefore = server.contentOf(folder.keysId);
    expect(keysBefore).not.toBeNull();
    const extra = server.putByHand({
      name: 'keep.txt', mimeType: 'text/plain', parents: [folder.rootId], appProperties: {},
    }, new Uint8Array([4, 5]));
    rig.trust.keys(folder.rootId).value = null;
    rig.state.value = {
      ...rig.state.value,
      creatingRootId: folder.rootId,
      creatingKeysHash: sha256Of(rig.p, keysBefore!),
    };
    let creates = 0;
    const orig = rig.service.createFolder.bind(rig.service);
    rig.service.createFolder = async (withKey: boolean) => {
      creates += 1;
      return orig(withKey);
    };
    const service = serviceFor(rig);
    const { host, component, fixture, i18n } = await render(service);
    await component.onConnect();
    await flush();
    fixture.detectChanges();
    const text = host.textContent ?? '';
    expect(creates).toBe(0);
    expect(text).toContain(i18n.t('driveConnect.ready'));
    expect(text).not.toContain(i18n.t('driveProblem.FOLDER_EXISTS'));
    expect(text).not.toContain(i18n.t('driveConnect.firstConnect'));
    expect(text).not.toContain(i18n.t('driveConnect.recoveryKeyNote'));
    expect(host.querySelector('.recovery-key-box')).toBeNull();
    expect(server.contentOf(folder.keysId)).toEqual(keysBefore);
    expect(server.contentOf(extra.id)).toEqual(new Uint8Array([4, 5]));
    expect(liveRoots(server)).toHaveLength(1);
    expect(server.allFiles().filter((f) => f.appProperties[DRIVE_LAYOUT.kind] === 'keys' && !f.trashed)).toHaveLength(1);
  });

  it('an interrupted create this browser is not in asks to join and does not rewrite the folder', async () => {
    const server = new FakeDriveServer();
    const a = await Rig.make(server, 'First');
    const folder = await a.created();
    const keysBefore = server.contentOf(folder.keysId);
    const extra = server.putByHand({
      name: 'keep.txt', mimeType: 'text/plain', parents: [folder.rootId], appProperties: {},
    }, new Uint8Array([7]));
    const b = await Rig.make(server, 'Second');
    b.state.value = { ...b.state.value, rootId: folder.rootId, creatingRootId: folder.rootId };
    const service = serviceFor(b);
    const { host, component, fixture, i18n } = await render(service);
    await component.onConnect();
    await flush();
    fixture.detectChanges();
    const text = host.textContent ?? '';
    expect(text).toContain(i18n.t('driveJoin.heading'));
    expect(host.querySelector('input#recovery-key-input')).toBeTruthy();
    expect(text).not.toContain(i18n.t('driveProblem.FOLDER_EXISTS'));
    expect(text).not.toContain(i18n.t('driveConnect.firstConnect'));
    expect(server.contentOf(folder.keysId)).toEqual(keysBefore);
    expect(server.contentOf(extra.id)).toEqual(new Uint8Array([7]));
    expect(liveRoots(server)).toHaveLength(1);
  });

  it('a wrong recovery key keeps the join form and names that key', async () => {
    const server = new FakeDriveServer();
    const a = await Rig.make(server, 'First');
    await a.created();
    const b = await Rig.make(server, 'Second');
    const service = serviceFor(b);
    const { host, component, fixture, i18n } = await render(service);
    await component.onConnect();
    await flush();
    fixture.detectChanges();
    expect(host.textContent).toContain(i18n.t('driveJoin.heading'));
    const input = host.querySelector<HTMLInputElement>('input#recovery-key-input');
    expect(input).toBeTruthy();
    input!.value = RecoveryKey.generate(b.p).display;
    input!.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('app-drive-join button.btn-primary')!.click();
    const text = await settle(
      fixture,
      host,
      (t) => t.includes(i18n.t('driveJoin.errorWrongKey')) || t.includes(i18n.t('driveConnect.failed')),
    );
    expect(text).toContain(i18n.t('driveJoin.errorWrongKey'));
    expect(text).toContain(i18n.t('driveJoin.heading'));
    expect(host.querySelector('input#recovery-key-input')).toBeTruthy();
    expect(text).not.toContain(i18n.t('driveConnect.failed'));
    expect(host.querySelector('.error-box')).toBeNull();
  });

  it('a revoked device is told it is no longer allowed, and the join form stays', async () => {
    const server = new FakeDriveServer();
    const a = await Rig.make(server, 'First');
    await a.created();
    const b = await Rig.make(server, 'Second');
    const approved = await a.service.approveDevice(b.identity.key.publicKey, b.identity.name, 'web');
    expect(approved.kind).toBe('approved');
    if (approved.kind !== 'approved') return;
    expect((await b.service.joinFromWrap(approved.wrapEnc, approved.wrapCt, approved.epoch)).kind).toBe('READY');
    const revoked = await a.service.revokeDevice(kidOf(b.p, b.identity.key.publicKey));
    expect(revoked.connection.kind).toBe('READY');
    const service = serviceFor(b);
    const { host, component, fixture, i18n } = await render(service);
    await component.onConnect();
    await flush();
    fixture.detectChanges();
    const text = host.textContent ?? '';
    expect(text).toContain(i18n.t('driveProblem.DEVICE_REVOKED'));
    expect(text).not.toContain(i18n.t('driveConnect.needsEnrolmentMessage'));
    expect(text).toContain(i18n.t('driveJoin.heading'));
    expect(host.querySelector('input#recovery-key-input')).toBeTruthy();
  });
});
