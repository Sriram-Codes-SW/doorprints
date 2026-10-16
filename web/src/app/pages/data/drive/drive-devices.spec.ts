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
import '../../../i18n/all-dictionaries';
import { DriveDevicesCard } from './drive-devices';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import { TranslationService } from '../../../i18n/translation.service';
import type { TKey } from '../../../i18n/en';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function fakeService(overrides: Record<string, unknown> = {}) {
  return {
    listedDevices: vi.fn(async () => [
      { kidHex: 'aa', name: 'Website', platform: 'web', self: true },
      { kidHex: 'bb', name: 'Other browser', platform: 'web', self: false },
    ]),
    accountEmail: vi.fn(async () => 'person@example.com'),
    revokeListedDevice: vi.fn(async () => ({ ok: true, recoveryKey: 'AAAA-BBBB-CCCC-DDDD-EEEE-FFFF' })),
    disconnectAll: vi.fn(async () => ({ ok: true })),
    ...overrides,
  };
}

async function render(svc = fakeService()) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveDevicesCard],
    providers: [{ provide: DriveConnectService, useValue: svc }],
  });
  const fixture = TestBed.createComponent(DriveDevicesCard);
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

describe('DriveDevicesCard', () => {
  it('lists devices, the account, and the three disconnect actions', async () => {
    const { host, i18n } = await render();
    expect(host.textContent).toContain('Other browser');
    expect(host.textContent).toContain('person@example.com');
    shown(host, i18n, 'driveDevices.self');
    shown(host, i18n, 'driveDevices.threeDisconnect');
    shown(host, i18n, 'driveDevices.threeRemove');
    shown(host, i18n, 'driveDevices.threeDelete');
    shown(host, i18n, 'driveDevices.browserLock');
    shown(host, i18n, 'driveDevices.checks');
    const revoke = Array.from(host.querySelectorAll('button')).find((b) => b.textContent?.includes(i18n.t('driveDevices.revoke')));
    expect(revoke).toBeTruthy();
  });

  it('revoke shows the new recovery key once and says it does not sign out of Google', async () => {
    const { component, fixture, host, i18n, svc } = await render();
    await component['revoke']('bb');
    fixture.detectChanges();
    expect(svc.revokeListedDevice).toHaveBeenCalledWith('bb');
    expect(host.textContent).toContain('AAAA-BBBB-CCCC-DDDD-EEEE-FFFF');
    shown(host, i18n, 'driveDevices.newRecovery');
    shown(host, i18n, 'driveDevices.revokeNote');
    const next = Array.from(host.querySelectorAll('button')).find((b) => b.textContent?.includes(i18n.t('common.next')));
    expect(next?.disabled).toBe(true);
  });

  it('asks to use the phone when revoke is refused without a passkey', async () => {
    const svc = fakeService({
      revokeListedDevice: vi.fn(async () => ({ ok: false, reason: 'USE_PHONE' })),
    });
    const { component, fixture, host, i18n } = await render(svc);
    await component['revoke']('bb');
    fixture.detectChanges();
    shown(host, i18n, 'driveDelete.usePhone');
    expect(host.textContent).not.toContain('AAAA');
  });

  it('disconnect on all devices asks the service', async () => {
    const { component, svc } = await render();
    await component['disconnectAll']();
    expect(svc.disconnectAll).toHaveBeenCalled();
  });

  it('disconnect on all devices without a passkey does not use the deletion sentence', async () => {
    const svc = fakeService({
      disconnectAll: vi.fn(async () => ({ ok: false, reason: 'USE_PHONE' })),
    });
    const { component, fixture, host, i18n } = await render(svc);
    await component['disconnectAll']();
    fixture.detectChanges();
    shown(host, i18n, 'driveDevices.disconnectPasskey');
    expect(host.textContent).not.toContain(i18n.t('driveDelete.usePhone'));
  });
});
