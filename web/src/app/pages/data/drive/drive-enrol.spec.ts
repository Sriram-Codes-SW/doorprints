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
import { DriveEnrolCard } from './drive-enrol';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import { TranslationService } from '../../../i18n/translation.service';
import type { TKey } from '../../../i18n/en';
import { unb64 } from '../../../data/crypto/bytes';
import { type PairingMessage } from '../../../data/drive/connect/pairing-flow';

const deviceKey = (() => {
  const pk = new Uint8Array(65);
  pk[0] = 4;
  pk[1] = 7;
  return pk;
})();

function fakeService() {
  return {
    devicePublicKey: async () => deviceKey.slice(),
    approveJoinedDevice: vi.fn(async () => ({ ok: true, wrapEnc: 'YQ', wrapCt: 'Yg', epoch: 1 })),
    joinFromWrap: vi.fn(async () => ({ state: 'Ready' as const })),
  };
}

async function render(svc = fakeService()) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveEnrolCard],
    providers: [{ provide: DriveConnectService, useValue: svc }],
  });
  const fixture = TestBed.createComponent(DriveEnrolCard);
  fixture.detectChanges();
  return {
    host: fixture.nativeElement as HTMLElement,
    fixture,
    component: fixture.componentInstance,
    svc,
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

describe('DriveEnrolCard', () => {
  it('offers newcomer and approver paths', async () => {
    const { host, i18n } = await render();
    shown(host, i18n, 'driveEnrol.newcomer');
    shown(host, i18n, 'driveEnrol.approver');
    shown(host, i18n, 'driveEnrol.qrDeferred');
  });

  it('newcomer and approver compute the same 8-digit code', async () => {
    const a = await render();
    await a.component['becomeNewcomer']();
    a.fixture.detectChanges();
    const request = a.component['requestText']();
    expect(request.length).toBeGreaterThan(10);

    const b = await render();
    b.component['becomeApprover']();
    b.component['requestText'].set(request);
    await b.component['approvePasted']();
    const reply = b.component['replyText']();

    a.component['replyText'].set(reply);
    a.component['revealCode']();
    const code = a.component['code']();
    expect(code).toMatch(/^\d{8}$/);

    const revealed = JSON.parse(a.component['replyText']()) as PairingMessage;
    b.component['replyText'].set(JSON.stringify(revealed));
    b.component['showApproverCode']();
    expect(b.component['code']()).toBe(code);
  });

  it('refuses a garbled reply', async () => {
    const { component, fixture, host, i18n } = await render();
    await component['becomeNewcomer']();
    component['replyText'].set('not-json');
    component['revealCode']();
    fixture.detectChanges();
    expect(component['error']()).toBe(i18n.t('driveEnrol.badMessage'));
    shown(host, i18n, 'driveEnrol.badMessage');
  });

  it('approves with this device key and joins from the wrap, not a recovery key', async () => {
    const a = await render();
    await a.component['becomeNewcomer']();
    const request = JSON.parse(a.component['requestText']()) as PairingMessage;
    expect(unb64(request.pkNew ?? '')).toEqual(deviceKey);

    const b = await render();
    b.component['becomeApprover']();
    b.component['requestText'].set(a.component['requestText']());
    await b.component['approvePasted']();
    a.component['replyText'].set(b.component['replyText']());
    a.component['revealCode']();
    b.component['replyText'].set(a.component['replyText']());
    b.component['showApproverCode']();
    expect(b.component['code']()).toBe(a.component['code']());

    await b.component['confirmNumbers']();
    expect(b.svc.approveJoinedDevice).toHaveBeenCalledWith(deviceKey, 'Website');
    const wrapped = JSON.parse(b.component['replyText']()) as PairingMessage;
    expect(wrapped.wrapEnc).toBe('YQ');
    expect(wrapped.epoch).toBe(1);

    a.component['replyText'].set(b.component['replyText']());
    a.fixture.detectChanges();
    await a.component['joinFolder']();
    expect(a.svc.joinFromWrap).toHaveBeenCalledWith('YQ', 'Yg', 1);
    a.fixture.detectChanges();
    expect(a.component['joined']()).toBe(true);
  });
});
