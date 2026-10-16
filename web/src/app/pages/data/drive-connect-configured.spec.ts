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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../../i18n/all-dictionaries';
import { DriveConnectComponent } from './drive-connect';
import {
  DriveConnectService,
  DRIVE_BACKUP_ADAPTER,
  DRIVE_SYNC_ADAPTER,
  DRIVE_DELETION_ADAPTER,
} from '../../data/drive/connect/drive-connect.service';
import { GOOGLE_CONFIG } from '../../data/drive/connect/drive-connect.providers';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { TranslationService } from '../../i18n/translation.service';
import { en } from '../../i18n/en';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

const backup = {
  connect: async () => ({ kind: 'NO_FOLDER' as const }),
  createFolder: async () => ({ connection: { kind: 'NO_FOLDER' as const }, recoveryKey: null }),
  openWithRecoveryKey: async () => ({ kind: 'NO_FOLDER' as const }),
  listBackups: async () => ({ backups: [], unfinished: [], duplicates: [], junk: [], ignored: [], missingNewer: false }),
  backUpNow: async () => ({ kind: 'failed' as const, problem: { kind: 'SOURCE_FAILED' } }),
  confirmShrink: async () => undefined,
  schedule: async () => ({ backup: false, reason: 'NOT_DUE', nextAt: null, verify: false }),
  writeReadMe: async () => undefined,
};

const sync = {
  syncNow: async () => ({ state: 'synced' as const, lastSyncAt: Date.now(), skipped: [] }),
  getPhotoSettings: () => ({ uploadOnMobileData: false }),
  pendingPhotoBytes: async () => 0,
  setPhotosWifiOnly: () => undefined,
  uploadPhotosNowOverMobile: async () => ({ granted: true }),
};

const deletion = {
  preflight: async () => ({ kind: 'refused' as const, reason: 'USE_PHONE' }),
  decide: () => ({ outcome: 'REFUSED' as const, reason: 'USE_PHONE' }),
  authorize: async () => ({ kind: 'refused' as const, reason: 'USE_PHONE' }),
  execute: async () => ({ kind: 'refused' as const, reason: 'USE_PHONE' }),
  resume: async () => ({ kind: 'refused' as const, reason: 'USE_PHONE' }),
  confirmGate: () => ({ tickBoxRequired: true, delayMs: 0 }),
  registerPasskey: async () => null,
  passkeyStatus: async () => 'none' as const,
};

async function renderWithConfig(clientId: string) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveConnectComponent],
    providers: [
      DriveConnectService,
      { provide: GOOGLE_CONFIG, useValue: { clientId } },
      { provide: DRIVE_BACKUP_ADAPTER, useValue: backup },
      { provide: DRIVE_SYNC_ADAPTER, useValue: sync },
      { provide: DRIVE_DELETION_ADAPTER, useValue: deletion },
      { provide: Announcer, useValue: { announce: vi.fn() } },
      { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } },
    ],
  });
  const fixture = TestBed.createComponent(DriveConnectComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await flush();
  fixture.detectChanges();
  return {
    host: fixture.nativeElement as HTMLElement,
    service: TestBed.inject(DriveConnectService),
    i18n: TestBed.inject(TranslationService),
  };
}

// A language another spec chose (the screens are checked in all four) must not decide the words asserted here.
beforeEach(() => localStorage.clear());
afterEach(() => TestBed.resetTestingModule());

describe('DriveConnectService isConfigured from GOOGLE_CONFIG (real service)', () => {
  it('empty client id: Unavailable, Connect is absent', async () => {
    const { host, service, i18n } = await renderWithConfig('');
    expect(service).toBeInstanceOf(DriveConnectService);
    expect(service.getState()).toBe('Unavailable');
    expect(host.textContent).toContain(i18n.t('driveConnect.unavailable'));
    expect(host.textContent).toContain(en['driveConnect.unavailable']);
    const connect = [...host.querySelectorAll('button')].find((b) =>
      (b.textContent ?? '').includes(i18n.t('driveConnect.connect')),
    );
    expect(connect).toBeUndefined();
  });

  it('a client id: Disconnected, Connect is shown', async () => {
    const { host, service, i18n } = await renderWithConfig('abc.apps.googleusercontent.com');
    expect(service).toBeInstanceOf(DriveConnectService);
    expect(service.getState()).toBe('Disconnected');
    expect(host.textContent).toContain(i18n.t('driveConnect.connect'));
    const connect = [...host.querySelectorAll('button')].find((b) =>
      (b.textContent ?? '').includes(i18n.t('driveConnect.connect')),
    );
    expect(connect).toBeTruthy();
  });
});
