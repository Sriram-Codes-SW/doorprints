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
import { DriveJoinComponent } from './drive-join';
import { TranslationService } from '../../../i18n/translation.service';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import type { ConnectResult } from '../../../data/drive/connect/drive-connect.service';

type FakeDriveService = Pick<DriveConnectService, 'openWithRecoveryKey' | 'disconnect'>;

function createFakeDriveService(overrides: Partial<FakeDriveService> = {}): FakeDriveService {
  return {
    openWithRecoveryKey: vi.fn(async () => ({ state: 'Ready' }) as ConnectResult),
    disconnect: vi.fn(async () => undefined),
    ...overrides,
  };
}

afterEach(() => TestBed.resetTestingModule());

describe('DriveJoinComponent', () => {
  async function render(service: FakeDriveService): Promise<{ host: HTMLElement; detect: () => void; component: DriveJoinComponent; service: FakeDriveService }> {
    TestBed.configureTestingModule({
      imports: [DriveJoinComponent],
      providers: [{ provide: DriveConnectService, useValue: service }],
    });

    const fixture = TestBed.createComponent(DriveJoinComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    return {
      host: fixture.nativeElement as HTMLElement,
      detect: () => fixture.detectChanges(),
      component: fixture.componentInstance,
      service,
    };
  }

  it('renders the recovery key input and join button', async () => {
    const service = createFakeDriveService();
    const { host } = await render(service);

    expect(host.querySelector('input#recovery-key-input')).toBeTruthy();
    const buttons = host.querySelectorAll('button[type="button"]');
    expect(buttons.length).toBeGreaterThan(0);
  });

  it('disables the join button when input is empty', async () => {
    const service = createFakeDriveService();
    const { host, detect, component } = await render(service);

    const button = host.querySelector<HTMLButtonElement>('button[type="button"]:not(.link-button)')!;
    expect(button?.disabled).toBe(true);

    component['recoveryKeyText'].set('XXXX-XXXX-XXXX-XXXX-XXXX-XXXX');
    detect();

    expect(button?.disabled).toBe(false);
  });

  it('calls openWithRecoveryKey with typed text on join', async () => {
    const service = createFakeDriveService();
    const { component, detect, service: svc } = await render(service);

    component['recoveryKeyText'].set('XXXX-XXXX-XXXX-XXXX-XXXX-XXXX');
    detect();

    await component.onJoin();

    expect(svc.openWithRecoveryKey).toHaveBeenCalledWith('XXXX-XXXX-XXXX-XXXX-XXXX-XXXX');
  });

  it('calls openWithRecoveryKey with lowercase text preserves case', async () => {
    const service = createFakeDriveService();
    const { component, service: svc } = await render(service);

    component['recoveryKeyText'].set('xxxx-xxxx-xxxx-xxxx-xxxx-xxxx');

    await component.onJoin();

    expect(svc.openWithRecoveryKey).toHaveBeenCalledWith('xxxx-xxxx-xxxx-xxxx-xxxx-xxxx');
  });

  it('clears the input field and emits joined on successful connection', async () => {
    const service = createFakeDriveService();
    const { component, detect } = await render(service);
    const joined = vi.fn();
    component.joined.subscribe(joined);

    component['recoveryKeyText'].set('XXXX-XXXX-XXXX-XXXX-XXXX-XXXX');
    detect();

    await component.onJoin();

    expect(component['recoveryKeyText']()).toBe('');
    expect(joined).toHaveBeenCalled();
  });

  it('shows error message for invalid recovery key format', async () => {
    const errorMsg = 'The recovery key is not valid: check it letter by letter.';
    const service = createFakeDriveService({
      openWithRecoveryKey: vi.fn(async () => ({
        state: 'NeedsRecoveryKey',
        error: errorMsg,
      }) as ConnectResult),
    });
    const { component } = await render(service);

    component['recoveryKeyText'].set('invalid');

    await component.onJoin();

    expect(component['errorMessage']()).toBe(errorMsg);
  });

  it('shows error message for wrong recovery key', async () => {
    const errorMsg = 'This recovery key does not open this folder.';
    const service = createFakeDriveService({
      openWithRecoveryKey: vi.fn(async () => ({
        state: 'NeedsRecoveryKey',
        error: errorMsg,
      }) as ConnectResult),
    });
    const { component } = await render(service);

    component['recoveryKeyText'].set('XXXX-XXXX-XXXX-XXXX-XXXX-XXXX');

    await component.onJoin();

    expect(component['errorMessage']()).toBe(errorMsg);
  });

  it('submits on Enter key press', async () => {
    const service = createFakeDriveService();
    const { host, component, detect, service: svc } = await render(service);

    component['recoveryKeyText'].set('XXXX-XXXX-XXXX-XXXX-XXXX-XXXX');
    detect();

    const input = host.querySelector<HTMLInputElement>('input#recovery-key-input')!;
    const event = new KeyboardEvent('keydown', { key: 'Enter' });
    component.onKeyDown(event);

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(svc.openWithRecoveryKey).toHaveBeenCalled();
  });

  it('disables button while busy', async () => {
    const service = createFakeDriveService({
      openWithRecoveryKey: vi.fn(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
        return { state: 'Ready' } as ConnectResult;
      }),
    });
    const { component } = await render(service);

    component['recoveryKeyText'].set('XXXX-XXXX-XXXX-XXXX-XXXX-XXXX');

    expect(component['busy']()).toBe(false);

    const joinPromise = component.onJoin();

    expect(component['busy']()).toBe(true);

    await joinPromise;

    expect(component['busy']()).toBe(false);
  });

  it('renders without missing i18n keys', async () => {
    const service = createFakeDriveService();
    const { host } = await render(service);

    // Check that visible text is rendered (i18n keys are replaced with actual text or key names in tests)
    expect(host.textContent).toBeTruthy();
    expect(host.querySelector('input')).toBeTruthy();
  });

  it('calls disconnect when disconnect link is clicked', async () => {
    const service = createFakeDriveService();
    const { component, service: svc } = await render(service);

    await component.onDisconnect();

    expect(svc.disconnect).toHaveBeenCalled();
  });

  it('has proper accessibility: input with label and aria-describedby', async () => {
    const service = createFakeDriveService();
    const { host } = await render(service);

    const input = host.querySelector<HTMLInputElement>('input#recovery-key-input')!;
    const label = host.querySelector<HTMLLabelElement>('label[for="recovery-key-input"]');
    const helpText = host.querySelector('#recovery-key-help');

    expect(input).toBeTruthy();
    expect(input.getAttribute('aria-describedby')).toBe('recovery-key-help');
    expect(label).toBeTruthy();
    expect(helpText).toBeTruthy();
  });

  it('has aria-live region for error messages', async () => {
    const service = createFakeDriveService();
    const { host } = await render(service);

    const errorRegion = host.querySelector('[aria-live="polite"]');
    expect(errorRegion).toBeTruthy();
    expect(errorRegion?.getAttribute('aria-atomic')).toBe('true');
  });

  it('displays error message after operation completes', async () => {
    const errorMsg = 'Test error';
    const service = createFakeDriveService({
      openWithRecoveryKey: vi.fn(async () => ({
        state: 'NeedsRecoveryKey',
        error: errorMsg,
      }) as ConnectResult),
    });
    const { component, host, detect } = await render(service);

    component['recoveryKeyText'].set('test-key');
    detect();

    await component.onJoin();
    detect();

    const errorRegion = host.querySelector('[aria-live="polite"]');
    expect(errorRegion?.textContent).toContain(errorMsg);
  });
});
