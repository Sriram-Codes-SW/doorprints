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
import type { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import { AppDriveSync } from './drive-sync';

const textOf = (el: HTMLElement): string => el.textContent?.replace(/\s+/g, ' ').trim() ?? '';

let fakeService: Pick<DriveConnectService, 'syncNow' | 'photoSettings' | 'pendingPhotoBytes' | 'setPhotosWifiOnly' | 'uploadPhotosNowOverMobile'>;
let windowAddEventListenerSpy: any;
let windowRemoveEventListenerSpy: any;

function makeFakeService(overrides: Partial<typeof fakeService> = {}) {
  return {
    syncNow: vi.fn(async () => ({
      state: 'up-to-date' as const,
      lastSyncAt: Date.now(),
      skipped: [],
      needsConfirmation: false,
    })),
    photoSettings: vi.fn(async () => ({ wifiOnly: false })),
    pendingPhotoBytes: vi.fn(async () => null),
    setPhotosWifiOnly: vi.fn(async () => {}),
    uploadPhotosNowOverMobile: vi.fn(async () => ({})),
    ...overrides,
  };
}

function createComponent() {
  TestBed.configureTestingModule({
    imports: [AppDriveSync],
    providers: [
      { provide: 'DriveConnectService', useValue: fakeService },
    ],
  });

  // Override the inject
  const fixture = TestBed.createComponent(AppDriveSync);
  const component = fixture.componentInstance;

  // Replace the injected service with our fake
  (component as any).service = fakeService;

  fixture.detectChanges();
  return { fixture, component, el: fixture.nativeElement as HTMLElement };
}

describe('AppDriveSync', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    fakeService = makeFakeService();

    windowAddEventListenerSpy = vi.spyOn(window, 'addEventListener');
    windowRemoveEventListenerSpy = vi.spyOn(window, 'removeEventListener');
  });

  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  it('renders Sync now button and status line on init', async () => {
    const { el, fixture } = createComponent();
    await fixture.whenStable();
    fixture.detectChanges();

    const btn = el.querySelector('button.btn-primary') as HTMLButtonElement | null;
    expect(btn?.textContent).toContain('Sync now');

    const status = el.querySelector('[role="status"]');
    expect(status).toBeTruthy();
  });

  it('performs initial sync on init', async () => {
    fakeService = makeFakeService();
    const { fixture } = createComponent();
    await fixture.whenStable();

    expect(fakeService.syncNow).toHaveBeenCalled();
  });

  it('sets up online/offline event listeners on init', async () => {
    fakeService = makeFakeService();
    const { fixture } = createComponent();
    await fixture.whenStable();

    expect(windowAddEventListenerSpy).toHaveBeenCalledWith('online', expect.any(Function));
    expect(windowAddEventListenerSpy).toHaveBeenCalledWith('offline', expect.any(Function));
  });

  it('removes event listeners on destroy', async () => {
    fakeService = makeFakeService();
    const { fixture, component } = createComponent();
    await fixture.whenStable();

    component.ngOnDestroy();

    expect(windowRemoveEventListenerSpy).toHaveBeenCalledWith('online', expect.any(Function));
    expect(windowRemoveEventListenerSpy).toHaveBeenCalledWith('offline', expect.any(Function));
  });

  it('displays status message when sync completes successfully', async () => {
    fakeService = makeFakeService();
    const { fixture, el } = createComponent();
    await fixture.whenStable();
    fixture.detectChanges();

    const status = el.querySelector('[role="status"]');
    expect(textOf(status!)).toContain('Up to date');
  });

  it('shows skipped files notice when sync returns skipped items', async () => {
    fakeService = makeFakeService({
      syncNow: vi.fn(async () => ({
        state: 'up-to-date' as const,
        lastSyncAt: Date.now(),
        skipped: ['file1', 'file2'],
        needsConfirmation: false,
      })),
    });
    const { fixture, el, component } = createComponent();
    await fixture.whenStable();
    await component.performSync();
    fixture.detectChanges();

    const notice = el.querySelector('[role="alert"]');
    expect(notice?.textContent).toContain('2');
  });

  it('shows shrink confirmation dialog when needsConfirmation is true', async () => {
    fakeService = makeFakeService({
      syncNow: vi.fn(async () => ({
        state: 'up-to-date' as const,
        lastSyncAt: Date.now(),
        skipped: [],
        needsConfirmation: true,
      })),
    });
    const { fixture, el, component } = createComponent();
    await fixture.whenStable();
    await component.performSync();
    fixture.detectChanges();

    const confirm = el.querySelector('.confirm-box');
    expect(confirm).toBeTruthy();
    expect(textOf(confirm!)).toContain('Another device deleted');
  });

  it('calls syncNow with confirmShrink when confirming deletion', async () => {
    fakeService = makeFakeService({
      syncNow: vi.fn(async (opts) => {
        expect(opts?.confirmShrink).toBe(true);
        return { state: 'up-to-date' as const, lastSyncAt: Date.now(), skipped: [], needsConfirmation: false };
      }),
    });
    const { fixture, component } = createComponent();
    await fixture.whenStable();
    component.needsShrinkConfirmation.set(true);

    await component.confirmShrinkDeletion();
    fixture.detectChanges();

    expect(fakeService.syncNow).toHaveBeenCalledWith({ confirmShrink: true });
  });

  it('hides shrink confirmation and sets status to up-to-date after confirm', async () => {
    fakeService = makeFakeService();
    const { fixture, component } = createComponent();
    component.needsShrinkConfirmation.set(true);

    await component.confirmShrinkDeletion();

    expect(component.needsShrinkConfirmation()).toBe(false);
    expect(component.syncState()).toBe('up-to-date');
  });

  it('hides shrink confirmation and sets status to up-to-date on cancel', async () => {
    const { component } = createComponent();
    component.needsShrinkConfirmation.set(true);
    component.syncState.set('syncing');

    component.cancelShrinkConfirmation();

    expect(component.needsShrinkConfirmation()).toBe(false);
    expect(component.syncState()).toBe('up-to-date');
  });

  it('loads photo settings on init', async () => {
    fakeService = makeFakeService({
      photoSettings: vi.fn(async () => ({ wifiOnly: true })),
    });
    const { fixture } = createComponent();
    await fixture.whenStable();

    expect(fakeService.photoSettings).toHaveBeenCalled();
  });

  it('toggles wifiOnly setting and calls service', async () => {
    fakeService = makeFakeService();
    const { component } = createComponent();
    await (component as any).loadPhotoSettings();

    await component.togglePhotosWifiOnly(true);

    expect(fakeService.setPhotosWifiOnly).toHaveBeenCalledWith(true);
    expect(component.photosWifiOnly()).toBe(true);
  });

  it('shows photo error on setPhotosWifiOnly failure', async () => {
    fakeService = makeFakeService({
      setPhotosWifiOnly: vi.fn(async () => { throw new Error('Network error'); }),
    });
    const { component } = createComponent();

    await component.togglePhotosWifiOnly(true);

    expect(component.showPhotoError()).toBe(true);
  });

  it('displays upload button when pendingPhotoBytes > 0', async () => {
    fakeService = makeFakeService({
      pendingPhotoBytes: vi.fn(async () => 5 * 1024 * 1024), // 5 MB
    });
    const { fixture, el, component } = createComponent();
    await fixture.whenStable();
    await (component as any).loadPhotoSettings();
    fixture.detectChanges();

    const uploadBtn = el.querySelector('button.btn-sm');
    expect(uploadBtn?.textContent).toContain('MB');
  });

  it('calls uploadPhotosNowOverMobile and clears pending bytes on upload', async () => {
    fakeService = makeFakeService({
      pendingPhotoBytes: vi.fn(async () => 10 * 1024 * 1024),
    });
    const { component } = createComponent();
    await (component as any).loadPhotoSettings();

    await component.uploadPhotosNowOverMobile();

    expect(fakeService.uploadPhotosNowOverMobile).toHaveBeenCalled();
    expect(component.pendingPhotoBytes()).toBe(0);
  });

  it('shows photo error on uploadPhotosNowOverMobile failure', async () => {
    fakeService = makeFakeService({
      uploadPhotosNowOverMobile: vi.fn(async () => { throw new Error('Upload failed'); }),
    });
    const { component } = createComponent();

    await component.uploadPhotosNowOverMobile();

    expect(component.showPhotoError()).toBe(true);
  });

  it('syncs again when online event fires', async () => {
    fakeService = makeFakeService();
    const { fixture } = createComponent();
    await fixture.whenStable();

    vi.clearAllMocks();
    const onlineHandler = windowAddEventListenerSpy.mock.calls.find((call: any[]) => call[0] === 'online')?.[1];
    expect(onlineHandler).toBeDefined();

    onlineHandler!();

    expect(fakeService.syncNow).toHaveBeenCalled();
  });

  it('sets offline state when offline event fires', async () => {
    fakeService = makeFakeService();
    const { component } = createComponent();

    const offlineHandler = windowAddEventListenerSpy.mock.calls.find((call: any[]) => call[0] === 'offline')?.[1];
    expect(offlineHandler).toBeDefined();

    offlineHandler!();

    expect(component.syncState()).toBe('offline');
  });

  it('formats photo bytes correctly (KB, MB, GB)', async () => {
    const { component } = createComponent();

    expect((component as any).formatBytes(512)).toBe('512 B');
    expect((component as any).formatBytes(1024)).toBe('1 KB');
    expect((component as any).formatBytes(1024 * 1024)).toBe('1 MB');
    expect((component as any).formatBytes(1024 * 1024 * 1024)).toBe('1 GB');
  });

  it('handles sync state waiting-wifi', async () => {
    fakeService = makeFakeService({
      syncNow: vi.fn(async () => ({
        state: 'waiting-wifi' as const,
        lastSyncAt: null,
        skipped: [],
        needsConfirmation: false,
      })),
    });
    const { component, fixture, el } = createComponent();
    await fixture.whenStable();
    await component.performSync();
    fixture.detectChanges();

    expect(component.syncState()).toBe('waiting-wifi');
    expect(textOf(el.querySelector('[role="status"]')!)).toContain('Wi-Fi');
  });

  it('handles sync state offline', async () => {
    fakeService = makeFakeService({
      syncNow: vi.fn(async () => ({
        state: 'offline' as const,
        lastSyncAt: null,
        skipped: [],
        needsConfirmation: false,
      })),
    });
    const { component, fixture, el } = createComponent();
    await fixture.whenStable();
    await component.performSync();
    fixture.detectChanges();

    expect(component.syncState()).toBe('offline');
    expect(textOf(el.querySelector('[role="status"]')!)).toContain('Offline');
  });

  it('handles sync error', async () => {
    fakeService = makeFakeService({
      syncNow: vi.fn(async () => { throw new Error('Sync failed'); }),
    });
    const { component, fixture, el } = createComponent();
    await fixture.whenStable();
    await component.performSync();
    fixture.detectChanges();

    expect(component.syncState()).toBe('error');
    expect(textOf(el.querySelector('[role="status"]')!)).toContain('Sync failed');
  });

  it('disables buttons while busy', async () => {
    fakeService = makeFakeService();
    const { component, fixture, el } = createComponent();
    await fixture.whenStable();

    component.busy.set(true);
    fixture.detectChanges();

    const buttons = el.querySelectorAll('button');
    buttons.forEach((btn) => {
      expect(btn.disabled).toBe(true);
    });
  });
});
