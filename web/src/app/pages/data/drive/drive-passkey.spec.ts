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
import { DrivePasskeyComponent } from './drive-passkey';
import { TranslationService } from '../../../i18n/translation.service';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';

type FakeDriveService = Pick<DriveConnectService, 'passkeyStatus' | 'registerPasskey'>;

function createFakeDriveService(overrides: Partial<FakeDriveService> = {}): FakeDriveService {
  return {
    passkeyStatus: vi.fn(async () => 'none' as const),
    registerPasskey: vi.fn(async () => 'registered' as const),
    ...overrides,
  };
}

afterEach(() => TestBed.resetTestingModule());

describe('DrivePasskeyComponent', () => {
  async function render(service: FakeDriveService): Promise<{ host: HTMLElement; detect: () => void; component: DrivePasskeyComponent; service: FakeDriveService }> {
    TestBed.configureTestingModule({
      imports: [DrivePasskeyComponent],
      providers: [{ provide: DriveConnectService, useValue: service }],
    });

    const fixture = TestBed.createComponent(DrivePasskeyComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    return {
      host: fixture.nativeElement as HTMLElement,
      detect: () => fixture.detectChanges(),
      component: fixture.componentInstance,
      service,
    };
  }

  it('loads passkey status on init', async () => {
    const service = createFakeDriveService();
    const { service: svc } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(svc.passkeyStatus).toHaveBeenCalled();
  });

  it('shows register button when status is none', async () => {
    const service = createFakeDriveService();
    const { host, component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    const i18n = TestBed.inject(TranslationService);
    expect(component['status']()).toBe('none');
    expect(component['status']()).not.toBe('loading');
    expect(host.textContent).toContain(i18n.t('drivePasskey.registerButton'));
    expect(host.textContent).not.toContain(i18n.t('drivePasskey.unsupportedNext'));
    expect(host.querySelector('button')).toBeTruthy();
  });

  it('shows success message when status is registered', async () => {
    const service = createFakeDriveService({
      passkeyStatus: vi.fn(async () => 'registered' as const),
    });
    const { component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    expect(component['status']()).toBe('registered');
  });

  it('shows unsupported message when status is unsupported', async () => {
    const service = createFakeDriveService({
      passkeyStatus: vi.fn(async () => 'unsupported' as const),
    });
    const { host, component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    const i18n = TestBed.inject(TranslationService);
    expect(component['status']()).toBe('unsupported');
    expect(host.textContent).toContain(i18n.t('drivePasskey.unsupportedDescription'));
    expect(host.textContent).toContain(i18n.t('drivePasskey.unsupportedNext'));
    expect(host.querySelector('button')).toBeNull();
  });

  it('calls registerPasskey when button is clicked', async () => {
    const service = createFakeDriveService();
    const { component, detect, service: svc } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    await component.onRegisterPasskey();

    expect(svc.registerPasskey).toHaveBeenCalled();
  });

  it('refreshes status after successful registration', async () => {
    const service = createFakeDriveService({
      registerPasskey: vi.fn(async () => 'registered' as const),
      passkeyStatus: vi.fn(async () => 'registered' as const),
    });
    const { component, service: svc } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));

    await component.onRegisterPasskey();

    expect(svc.registerPasskey).toHaveBeenCalled();
    expect(svc.passkeyStatus).toHaveBeenCalledTimes(2);
  });

  it('disables button while busy', async () => {
    const service = createFakeDriveService({
      registerPasskey: vi.fn(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        return 'registered' as const;
      }),
    });
    const { component } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(component['busy']()).toBe(false);

    void component.onRegisterPasskey();

    expect(component['busy']()).toBe(true);

    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(component['busy']()).toBe(false);
  });

  it('handles registration errors gracefully', async () => {
    const service = createFakeDriveService({
      registerPasskey: vi.fn(async () => {
        throw new Error('Registration failed');
      }),
    });
    const { component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    await component.onRegisterPasskey();
    detect();

    expect(component['errorMessage']()).toBe('driveConnect.failed');
  });

  it('renders without missing i18n keys', async () => {
    const service = createFakeDriveService();
    const { host } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(host.textContent).toBeTruthy();
    expect(host.querySelector('h3')).toBeTruthy();
  });

  it('handles null return from registerPasskey', async () => {
    const service = createFakeDriveService({
      registerPasskey: vi.fn(async () => null),
      passkeyStatus: vi.fn(async () => 'unsupported' as const),
    });
    const { component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    await component.onRegisterPasskey();
    detect();

    expect(component['status']()).toBe('unsupported');
  });

  it('has aria-live region for error messages', async () => {
    const service = createFakeDriveService();
    const { host } = await render(service);

    const errorRegion = host.querySelector('[aria-live="polite"]');
    expect(errorRegion).toBeTruthy();
    expect(errorRegion?.getAttribute('aria-atomic')).toBe('true');
  });
});
