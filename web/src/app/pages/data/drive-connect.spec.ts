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
import { DriveConnectService, type ConnectState } from '../../data/drive/connect/drive-connect.service';
import { TranslationService } from '../../i18n/translation.service';
import type { TKey } from '../../i18n/en';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function fakeService(state: ConnectState, extra: Record<string, unknown> = {}) {
  let current = state;
  return {
    getState: () => current,
    connect: vi.fn(async () => ({ state: current })),
    createFolder: vi.fn(async () => ({ state: 'FirstConnectShowRecoveryKey' as const, recoveryKey: 'XXXX-XXXX-XXXX-XXXX-XXXX-XXXX' })),
    confirmRecoveryKeySaved: vi.fn(() => {
      current = 'Ready';
    }),
    skipRecoveryKeyWithWarning: vi.fn(() => {
      current = 'Ready';
    }),
    disconnect: vi.fn(async () => {
      current = 'Disconnected';
    }),
    listBackups: vi.fn().mockResolvedValue({ ok: true, backups: [], missingNewer: false }),
    autoBackupEnabled: vi.fn(() => false),
    setAutoBackup: vi.fn(),
    runDueBackup: vi.fn().mockResolvedValue({ ran: false, reason: 'DISABLED' }),
    backUpNow: vi.fn(),
    confirmShrink: vi.fn(),
    importFromDrive: vi.fn(),
    syncNow: vi.fn().mockResolvedValue({ state: 'synced', lastSyncAt: Date.now(), skipped: [], needsConfirmation: false }),
    photoSettings: vi.fn().mockResolvedValue({ uploadOnMobileData: false }),
    pendingPhotoBytes: vi.fn().mockResolvedValue(0),
    setPhotosWifiOnly: vi.fn(),
    uploadPhotosNowOverMobile: vi.fn(),
    passkeyStatus: vi.fn().mockResolvedValue('none'),
    registerPasskey: vi.fn(),
    deletePlan: vi.fn(),
    deleteConfirmInfo: vi.fn(),
    authorizeDelete: vi.fn(),
    executeDelete: vi.fn(),
    openWithRecoveryKey: vi.fn(),
    ...extra,
  };
}

async function render(svc: ReturnType<typeof fakeService>) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveConnectComponent],
    providers: [
      { provide: DriveConnectService, useValue: svc },
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
    fixture,
    svc,
    component: fixture.componentInstance,
    i18n: TestBed.inject(TranslationService),
  };
}

function shown(host: HTMLElement, i18n: TranslationService, key: TKey): void {
  const translated = i18n.t(key);
  expect(translated).not.toBe(key);
  expect(host.textContent).toContain(translated);
  expect(host.textContent).not.toContain(key);
}

afterEach(() => TestBed.resetTestingModule());

describe('DriveConnectComponent', () => {
  it('shows Unavailable without a service', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [DriveConnectComponent],
    });
    const i18n = TestBed.inject(TranslationService);
    const fixture = TestBed.createComponent(DriveConnectComponent);
    fixture.detectChanges();
    shown(fixture.nativeElement as HTMLElement, i18n, 'driveConnect.unavailable');
  });

  it('shows Connect when disconnected', async () => {
    const { host, i18n } = await render(fakeService('Disconnected'));
    shown(host, i18n, 'driveConnect.connect');
  });

  it('clears the recovery key after confirm', async () => {
    const svc = fakeService('FirstConnectShowRecoveryKey');
    const { component, fixture, host } = await render(svc);
    const key = 'AAAA-BBBB-CCCC-DDDD-EEEE-FFFF';
    component['recoveryKey'].set(key);
    component['recoveryKeySaved'].set(true);
    fixture.detectChanges();
    expect(host.textContent).toContain(key);
    component.continueFromRecoveryKey();
    fixture.detectChanges();
    expect(svc.confirmRecoveryKeySaved).toHaveBeenCalled();
    expect(component['recoveryKey']()).toBeNull();
    expect(host.textContent).not.toContain(key);
  });

  it('clears the recovery key from the signal and the DOM after skip', async () => {
    const svc = fakeService('FirstConnectShowRecoveryKey');
    const { component, fixture, host } = await render(svc);
    const key = 'SKIP-KEY1-KEY2-KEY3-KEY4-KEY5';
    component['recoveryKey'].set(key);
    fixture.detectChanges();
    expect(host.textContent).toContain(key);
    await component.skipRecoveryKey();
    fixture.detectChanges();
    expect(svc.skipRecoveryKeyWithWarning).toHaveBeenCalled();
    expect(component['recoveryKey']()).toBeNull();
    expect(host.textContent).not.toContain(key);
  });

  it('announces when copying the recovery key fails', async () => {
    const announcer = { announce: vi.fn() };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [DriveConnectComponent],
      providers: [
        { provide: DriveConnectService, useValue: fakeService('FirstConnectShowRecoveryKey') },
        { provide: Announcer, useValue: announcer },
        { provide: ConfirmService, useValue: { ask: vi.fn(async () => true) } },
      ],
    });
    const fixture = TestBed.createComponent(DriveConnectComponent);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn(async () => { throw new Error('denied'); }) } });
    await fixture.componentInstance.copyRecoveryKey('AAAA-BBBB');
    expect(announcer.announce).toHaveBeenCalledWith({ key: 'driveConnect.copyFailed' });
  });

  it('hands an imported Blob out', async () => {
    const svc = fakeService('Ready');
    const { component } = await render(svc);
    const seen: Blob[] = [];
    component.importFile.subscribe((b) => seen.push(b));
    const blob = new Blob(['x']);
    component['onImportFromDrive'](blob);
    expect(seen).toEqual([blob]);
  });
});
