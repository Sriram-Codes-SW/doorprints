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
import { DriveConnectComponent } from './drive-connect';
import { DriveConnectService, type ConnectState } from '../../data/drive/connect/drive-connect.service';
import { TranslationService } from '../../i18n/translation.service';
import { Announcer } from '../../core/announcer.service';

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
      { provide: TranslationService, useValue: { t: (k: string) => k, dateTime: (s: string) => s, lang: () => 'en' } },
    ],
  });
  const fixture = TestBed.createComponent(DriveConnectComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await flush();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, fixture, svc, component: fixture.componentInstance };
}

afterEach(() => TestBed.resetTestingModule());

describe('DriveConnectComponent', () => {
  it('shows Unavailable without a service', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [DriveConnectComponent],
      providers: [{ provide: TranslationService, useValue: { t: (k: string) => k, lang: () => 'en' } }],
    });
    const fixture = TestBed.createComponent(DriveConnectComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('driveConnect.unavailable');
  });

  it('shows Connect when disconnected', async () => {
    const { host } = await render(fakeService('Disconnected'));
    expect(host.textContent).toContain('driveConnect.connect');
  });

  it('clears the recovery key after confirm', async () => {
    const svc = fakeService('FirstConnectShowRecoveryKey');
    const { component, fixture } = await render(svc);
    component['recoveryKey'].set('AAAA-BBBB-CCCC-DDDD-EEEE-FFFF');
    component['recoveryKeySaved'].set(true);
    fixture.detectChanges();
    component.continueFromRecoveryKey();
    fixture.detectChanges();
    expect(svc.confirmRecoveryKeySaved).toHaveBeenCalled();
    expect(component['recoveryKey']()).toBeNull();
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
