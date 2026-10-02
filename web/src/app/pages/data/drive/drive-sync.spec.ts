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
import { DriveSyncCard } from './drive-sync';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import { TranslationService } from '../../../i18n/translation.service';
import type { TKey } from '../../../i18n/en';
import type { Params } from '../../../i18n/translation.service';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function fakeService(overrides: Record<string, unknown> = {}) {
  return {
    syncNow: vi.fn().mockResolvedValue({ state: 'synced', lastSyncAt: Date.now(), skipped: [], needsConfirmation: false }),
    photoSettings: vi.fn().mockResolvedValue({ uploadOnMobileData: false }),
    pendingPhotoBytes: vi.fn().mockResolvedValue(0),
    setPhotosWifiOnly: vi.fn().mockResolvedValue(undefined),
    uploadPhotosNowOverMobile: vi.fn().mockResolvedValue({ granted: true }),
    ...overrides,
  };
}

async function render(svc = fakeService()) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveSyncCard],
    providers: [{ provide: DriveConnectService, useValue: svc }],
  });
  const fixture = TestBed.createComponent(DriveSyncCard);
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

function shown(host: HTMLElement, i18n: TranslationService, key: TKey, params?: Params): void {
  const translated = i18n.t(key, params);
  expect(translated).not.toBe(key);
  expect(host.textContent).toContain(translated);
  expect(host.textContent).not.toContain(key);
}

afterEach(() => TestBed.resetTestingModule());

describe('DriveSyncCard', () => {
  it('syncs on init and shows a live status line', async () => {
    const { host, svc, i18n } = await render();
    expect(svc.syncNow).toHaveBeenCalled();
    expect(host.querySelector('[role="status"]')).toBeTruthy();
    shown(host, i18n, 'driveSync.syncNow');
  });

  it('shows confirmation questions when sync asks for them', async () => {
    const svc = fakeService({
      syncNow: vi.fn().mockResolvedValue({
        state: 'needs-confirmation',
        lastSyncAt: null,
        skipped: [],
        needsConfirmation: true,
      }),
    });
    const { host, i18n } = await render(svc);
    expect(host.querySelector('[role="dialog"]')).toBeTruthy();
    shown(host, i18n, 'driveSync.shrinkConfirm');
  });

  it('shows skipped-file notice', async () => {
    const svc = fakeService({
      syncNow: vi.fn().mockResolvedValue({
        state: 'synced',
        lastSyncAt: Date.now(),
        skipped: ['a', 'b'],
        needsConfirmation: false,
      }),
    });
    const { host, i18n } = await render(svc);
    shown(host, i18n, 'driveSync.skippedFiles', { count: 2 });
  });

  it('toggles Wi-Fi-only photos', async () => {
    const { component, svc } = await render();
    const event = { target: { checked: false } } as unknown as Event;
    await component['togglePhotosWifiOnly'](event);
    expect(svc.setPhotosWifiOnly).toHaveBeenCalledWith(false);
  });

  it('maps a sync error to the error status line', async () => {
    const svc = fakeService({
      syncNow: vi.fn().mockRejectedValue(new Error('offline')),
    });
    const { host, i18n } = await render(svc);
    const status = host.querySelector('[role="status"]')?.textContent ?? '';
    expect(status).toContain(i18n.t('driveSync.statusError'));
    expect(status).not.toContain('driveSync.statusError');
    expect(status).not.toContain('offline');
  });
});
