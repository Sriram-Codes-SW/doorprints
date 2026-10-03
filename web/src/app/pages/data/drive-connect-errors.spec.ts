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
import { afterEach, describe, expect, it, vi } from 'vitest';
import '../../i18n/all-dictionaries';
import { DriveConnectComponent } from './drive-connect';
import { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import type { DriveBackupAdapter } from '../../data/drive/connect/backup-adapter';
import type { DriveSyncAdapter } from '../../data/drive/connect/sync-adapter';
import type { DriveDeletionAdapter } from '../../data/drive/connect/deletion-adapter';
import { DriveProblem, type ReadyFolder } from '../../data/drive/backup/drive-backup-results';
import { msgOfThrown, problemToMsg } from '../../data/drive/backup/drive-backup-results';
import type { DriveProblemKind } from '../../data/drive/backup/drive-backup-results';
import { TranslationService } from '../../i18n/translation.service';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { en } from '../../i18n/en';
import type { TKey } from '../../i18n/en';
import type { Lang } from '../../i18n/languages';
import { SignInError } from '../../data/drive/drive-client';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

const FOLDER: ReadyFolder = {
  rootId: 'root',
  keysId: 'keys',
  controlId: 'control',
  backupsId: 'backups',
  keys: {} as ReadyFolder['keys'],
  control: {} as ReadyFolder['control'],
};

const EMPTY_LISTING = {
  backups: [],
  unfinished: [],
  duplicates: [],
  junk: [],
  ignored: [] as [string, never][],
  missingNewer: false,
};

function stubBackup(extra: Partial<DriveBackupAdapter> = {}): DriveBackupAdapter {
  return {
    connect: async () => ({ kind: 'NO_FOLDER' }),
    createFolder: async () => ({ connection: { kind: 'NO_FOLDER' }, recoveryKey: null }),
    openWithRecoveryKey: async () => ({ kind: 'NO_FOLDER' }),
    listBackups: async () => EMPTY_LISTING,
    backUpNow: async () => ({ kind: 'failed', problem: new DriveProblem('SOURCE_FAILED') }),
    importFromDrive: async () => ({ kind: 'refused', problem: new DriveProblem('BACKUP_GONE') }),
    confirmShrink: async () => undefined,
    schedule: async () => ({ backup: false, reason: 'NOT_DUE' }),
    writeReadMe: async () => undefined,
    ...extra,
  } as unknown as DriveBackupAdapter;
}

function stubSync(): DriveSyncAdapter {
  return {
    syncNow: async () => ({ state: 'synced', lastSyncAt: Date.now(), skipped: [] }),
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

function makeService(clientId: string, backup: DriveBackupAdapter = stubBackup()): DriveConnectService {
  const prefs = (() => {
    const m = new Map<string, string>();
    return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v) };
  })();
  return new DriveConnectService(backup, stubSync(), stubDeletion(), { clientId }, null, prefs);
}

async function renderConnect(service: DriveConnectService, lang: Lang = 'en') {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveConnectComponent],
    providers: [
      { provide: DriveConnectService, useValue: service },
      { provide: Announcer, useValue: { announce: vi.fn() } },
      { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } },
    ],
  });
  const i18n = TestBed.inject(TranslationService);
  await i18n.setLang(lang);
  const fixture = TestBed.createComponent(DriveConnectComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await flush();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, fixture, component: fixture.componentInstance, i18n };
}

function shownIsTranslated(host: HTMLElement, i18n: TranslationService, key: TKey, leak?: string): void {
  const text = host.textContent ?? '';
  const translated = i18n.t(key);
  expect(translated).not.toBe(key);
  expect(translated.length).toBeGreaterThan(4);
  expect(text).toContain(translated);
  expect(text).not.toContain(key);
  expect(text).not.toMatch(/Google Drive not configured|Not connected to folder|Folder was deleted/);
  if (leak) expect(text).not.toContain(leak);
}

afterEach(async () => {
  TestBed.resetTestingModule();
});

describe('Drive problem keys', () => {
  it('maps every DriveProblemKind to a dictionary sentence, never the kind code', () => {
    const kinds = Object.keys({
      OFFLINE: 1, UNAUTHORIZED: 1, QUOTA_EXCEEDED: 1, RATE_LIMITED: 1, SERVER: 1, DRIVE: 1, CORRUPT: 1,
      KEYS_ROLLED_BACK: 1, KEYS_UNTRUSTED: 1, KEYS_UNREADABLE: 1, WRONG_RECOVERY_KEY: 1, NO_RECOVERY_KEY: 1,
      DEVICE_REVOKED: 1, CONTROL_ROLLED_BACK: 1, CONTROL_INVALID: 1, FOLDER_WITHOUT_KEYS: 1, FOLDER_EXISTS: 1,
      BACKUP_REFUSED: 1, BACKUP_GONE: 1, SOURCE_FAILED: 1, CRYPTO_UNAVAILABLE: 1,
      SIGNIN_POPUP_BLOCKED: 1, SIGNIN_CLOSED: 1, SIGNIN_DENIED: 1, SIGNIN_UNAVAILABLE: 1,
    }) as DriveProblemKind[];
    for (const kind of kinds) {
      const key = problemToMsg(kind);
      expect(key in en, kind).toBe(true);
      expect((en as Record<string, string>)[key]).not.toBe(kind);
    }
  });

  it('maps an unknown thrown value to the generic key, never String(err)', () => {
    expect(msgOfThrown(new Error('secret stack'))).toBe('driveConnect.failed');
    expect(msgOfThrown('raw')).toBe('driveConnect.failed');
    expect(msgOfThrown(new DriveProblem('OFFLINE'))).toBe('driveProblem.OFFLINE');
    expect(msgOfThrown(new SignInError('denied'))).toBe('driveProblem.SIGNIN_DENIED');
  });
});

describe('DriveConnectService error states on the card (en and hi)', () => {
  const langs: Lang[] = ['en', 'hi'];

  for (const lang of langs) {
    it(`${lang}: not configured shows the translated unavailable copy, not a key or English leak`, async () => {
      const { host, i18n, component, fixture } = await renderConnect(makeService(''), lang);
      await component.onConnect();
      fixture.detectChanges();
      shownIsTranslated(host, i18n, 'driveConnect.unavailable');
      shownIsTranslated(host, i18n, 'driveConnect.notConfigured');
    });

    it(`${lang}: a deleted folder shows the translated folder-gone copy`, async () => {
      const backup = stubBackup({ connect: async () => ({ kind: 'FOLDER_GONE' }) });
      const { host, i18n, component, fixture } = await renderConnect(makeService('client', backup), lang);
      await component.onConnect();
      await flush();
      fixture.detectChanges();
      shownIsTranslated(host, i18n, 'driveConnect.folderGone');
    });

    it(`${lang}: an unknown thrown error shows the generic translated message, never the throw text`, async () => {
      const leak = 'secret stack from GIS';
      const backup = stubBackup({ connect: async () => { throw new Error(leak); } });
      const { host, i18n, component, fixture } = await renderConnect(makeService('client', backup), lang);
      await component.onConnect();
      await flush();
      fixture.detectChanges();
      shownIsTranslated(host, i18n, 'driveConnect.failed', leak);
    });

    it(`${lang}: a typed DriveProblem on connect shows that problem's translated sentence`, async () => {
      const backup = stubBackup({
        connect: async () => ({ kind: 'ERROR', problem: new DriveProblem('OFFLINE') }),
      });
      const { host, i18n, component, fixture } = await renderConnect(makeService('client', backup), lang);
      await component.onConnect();
      await flush();
      fixture.detectChanges();
      shownIsTranslated(host, i18n, 'driveProblem.OFFLINE');
    });

    it(`${lang}: folder creation after no folder shows that problem, not a blank Try again`, async () => {
      const backup = stubBackup({
        connect: async () => ({ kind: 'NO_FOLDER' }),
        createFolder: async () => ({ connection: { kind: 'ERROR', problem: new DriveProblem('CRYPTO_UNAVAILABLE') }, recoveryKey: null }),
      });
      const { host, i18n, component, fixture } = await renderConnect(makeService('client', backup), lang);
      await component.onConnect();
      await flush();
      fixture.detectChanges();
      shownIsTranslated(host, i18n, 'driveProblem.CRYPTO_UNAVAILABLE');
      const box = host.querySelector('.error-box');
      expect(box?.querySelector('p')?.textContent).toContain(i18n.t('driveProblem.CRYPTO_UNAVAILABLE'));
      expect(box?.textContent).toContain(i18n.t('common.retry'));
    });

    it(`${lang}: a thrown folder-creation error shows the generic sentence, never the throw text`, async () => {
      const leak = 'secret stack from folder create';
      const backup = stubBackup({
        connect: async () => ({ kind: 'NO_FOLDER' }),
        createFolder: async () => { throw new Error(leak); },
      });
      const { host, i18n, component, fixture } = await renderConnect(makeService('client', backup), lang);
      await component.onConnect();
      await flush();
      fixture.detectChanges();
      shownIsTranslated(host, i18n, 'driveConnect.failed', leak);
      expect(host.querySelector('.error-box p')?.textContent).toContain(i18n.t('driveConnect.failed'));
    });

    it(`${lang}: a mistyped recovery key shows the translated invalid-format copy`, async () => {
      const backup = stubBackup({
        connect: async () => ({ kind: 'NEEDS_ENROLMENT', recoveryAvailable: true }),
      });
      const { host, i18n, component, fixture } = await renderConnect(makeService('client', backup), lang);
      await component.onConnect();
      await flush();
      fixture.detectChanges();
      const join = host.querySelector('app-drive-join');
      expect(join).toBeTruthy();
      const input = host.querySelector<HTMLInputElement>('input#recovery-key-input')!;
      input.value = 'not-a-key';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      const joinBtn = host.querySelector<HTMLButtonElement>('app-drive-join button.btn-primary')!;
      joinBtn.click();
      await flush();
      await fixture.whenStable();
      fixture.detectChanges();
      shownIsTranslated(host, i18n, 'driveJoin.errorInvalidFormat');
    });

    it(`${lang}: listing backups after a thrown error shows the generic translated message`, async () => {
      const leak = 'Drive list 500 body';
      const backup = stubBackup({
        connect: async () => ({ kind: 'READY', folder: FOLDER }),
        listBackups: async () => { throw new Error(leak); },
      });
      const { host, i18n, component, fixture } = await renderConnect(makeService('client', backup), lang);
      await component.onConnect();
      await flush();
      await fixture.whenStable();
      fixture.detectChanges();
      shownIsTranslated(host, i18n, 'driveConnect.failed', leak);
    });
  }

  it('a first folder with a recovery key is shown, not an error', async () => {
    const key = 'AAAA-BBBB-CCCC-DDDD-EEEE-FFFF';
    const backup = stubBackup({
      connect: async () => ({ kind: 'NO_FOLDER' }),
      createFolder: async () => ({ connection: { kind: 'READY', folder: FOLDER }, recoveryKey: { display: key } as never }),
    });
    const { host, component, fixture } = await renderConnect(makeService('client', backup));
    await component.onConnect();
    await flush();
    fixture.detectChanges();
    expect(host.textContent).toContain(key);
    expect(host.querySelector('.error-box')).toBeNull();
  });

  it('connect() READY remembers the folder so listBackups is not "not connected"', async () => {
    const backup = stubBackup({
      connect: async () => ({ kind: 'READY', folder: FOLDER }),
      listBackups: async () => EMPTY_LISTING,
    });
    const service = makeService('client', backup);
    expect((await service.connect()).state).toBe('Ready');
    const listing = await service.listBackups();
    expect(listing.ok).toBe(true);
  });
});
