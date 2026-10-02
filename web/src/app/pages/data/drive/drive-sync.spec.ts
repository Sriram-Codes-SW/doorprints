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
import { DriveSyncCard } from './drive-sync';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import { TranslationService } from '../../../i18n/translation.service';

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
    providers: [
      { provide: DriveConnectService, useValue: svc },
      {
        provide: TranslationService,
        useValue: { t: (k: string) => k, dateTime: (s: string) => s, lang: () => 'en' },
      },
    ],
  });
  const fixture = TestBed.createComponent(DriveSyncCard);
  fixture.detectChanges();
  await fixture.whenStable();
  await flush();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, fixture, svc, component: fixture.componentInstance };
}

afterEach(() => TestBed.resetTestingModule());

describe('DriveSyncCard', () => {
  it('syncs on init and shows a live status line', async () => {
    const { host, svc } = await render();
    expect(svc.syncNow).toHaveBeenCalled();
    expect(host.querySelector('[role="status"]')).toBeTruthy();
    expect(host.textContent).toContain('driveSync.syncNow');
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
    const { host } = await render(svc);
    expect(host.querySelector('[role="dialog"]')).toBeTruthy();
    expect(host.textContent).toContain('driveSync.shrinkConfirm');
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
    const { host } = await render(svc);
    expect(host.textContent).toContain('driveSync.skippedFiles');
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
    const { host } = await render(svc);
    expect(host.querySelector('[role="status"]')?.textContent).toContain('driveSync.statusError');
  });
});
