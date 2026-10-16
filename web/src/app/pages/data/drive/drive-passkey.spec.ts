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

type FakeDriveService = Pick<DriveConnectService, 'passkeyStatus' | 'registerPasskey' | 'passkeyDetails' | 'passkeyPrfCapability' | 'passkeyBuiltIn'>;

function createFakeDriveService(overrides: Partial<FakeDriveService> = {}): FakeDriveService {
  return {
    passkeyStatus: vi.fn(async () => 'none' as const),
    registerPasskey: vi.fn(async () => 'registered' as const),
    passkeyDetails: vi.fn(async () => null),
    passkeyPrfCapability: vi.fn(async () => null),
    passkeyBuiltIn: vi.fn(async () => null),
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
    const { component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    expect(component['status']()).toBe('none');
    expect(component['status']()).not.toBe('loading');
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
    const { component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    expect(component['status']()).toBe('unsupported');
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
    const { host, component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    await component.onRegisterPasskey();
    detect();

    expect(component['status']()).toBe('none');
    expect(component['errorMessage']()).toBe('drivePasskey.registerFailed');
    expect(host.querySelector('button')?.textContent).toContain('Set up a passkey');
    expect(host.querySelector('.error-message')?.textContent).toContain('The passkey could not be set up. Try again.');
    expect(host.textContent).not.toContain('This browser cannot make the kind of passkey');
  });

  it('renders without missing i18n keys', async () => {
    const service = createFakeDriveService();
    const { host } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(host.textContent).toBeTruthy();
    expect(host.querySelector('h3')).toBeTruthy();
  });

  it('stays on Set up a passkey and says the prompt was cancelled', async () => {
    const service = createFakeDriveService({
      registerPasskey: vi.fn(async () => null),
      passkeyStatus: vi.fn(async () => 'none' as const),
    });
    const { host, component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    (host.querySelector('button') as HTMLButtonElement).click();
    await vi.waitFor(() => {
      expect(component['errorMessage']()).toBe('drivePasskey.registerCancelled');
    });
    detect();

    expect(component['status']()).toBe('none');
    expect(component['errorMessage']()).toBe('drivePasskey.registerCancelled');
    expect(host.querySelector('button')?.textContent).toContain('Set up a passkey');
    expect(host.querySelector('.error-message')?.textContent).toContain('The prompt was cancelled.');
    expect(host.textContent).not.toContain('This browser cannot make the kind of passkey');
  });

  it('stays on Set up a passkey and shows a sentence when registration fails', async () => {
    const service = createFakeDriveService({
      registerPasskey: vi.fn(async () => 'failed' as const),
      passkeyStatus: vi.fn(async () => 'none' as const),
    });
    const { host, component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    await component.onRegisterPasskey();
    detect();

    expect(component['status']()).toBe('none');
    expect(component['errorMessage']()).toBe('drivePasskey.registerFailed');
    expect(host.querySelector('button')?.textContent).toContain('Set up a passkey');
    expect(host.querySelector('.error-message')?.textContent).toContain('The passkey could not be set up. Try again.');
    expect(host.textContent).not.toContain('This browser cannot make the kind of passkey');
  });

  it('keeps the button when registration is unsupported but a platform authenticator is still available', async () => {
    const service = createFakeDriveService({
      registerPasskey: vi.fn(async () => 'unsupported' as const),
      passkeyStatus: vi.fn(async () => 'none' as const),
    });
    const { host, component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    await component.onRegisterPasskey();
    detect();

    expect(component['status']()).toBe('none');
    expect(component['errorMessage']()).toBe('drivePasskey.registerFailed');
    expect(host.querySelector('button')?.textContent).toContain('Set up a passkey');
    expect(host.textContent).not.toContain('This browser cannot make the kind of passkey');
  });

  it('replaces the button only when the platform authenticator is unavailable', async () => {
    const service = createFakeDriveService({
      registerPasskey: vi.fn(async () => 'unsupported' as const),
      passkeyStatus: vi.fn(async () => 'unsupported' as const),
    });
    const { host, component, detect } = await render(service);

    await new Promise((resolve) => setTimeout(resolve, 50));
    detect();

    await component.onRegisterPasskey();
    detect();

    expect(component['status']()).toBe('unsupported');
    expect(host.querySelector('button')).toBeNull();
    expect(host.textContent).toContain('This browser cannot make the kind of passkey');
  });

  describe('a passkey that returns no PRF output', () => {
    const DETAILS = 'create: prf-present enabled=false first=no; assertion: no-prf-results enabled=false';

    async function noPrf(details: string | null = DETAILS) {
      const service = createFakeDriveService({
        registerPasskey: vi.fn(async () => 'no-prf' as const),
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyDetails: vi.fn(async () => details),
      });
      const view = await render(service);
      await new Promise((resolve) => setTimeout(resolve, 50));
      view.detect();
      await view.component.onRegisterPasskey();
      view.detect();
      return { ...view, service };
    }

    it('stays on Set up a passkey, says it could not protect deletions and what works instead', async () => {
      const { host, component } = await noPrf();
      expect(component['status']()).toBe('none');
      expect(component['errorMessage']()).toBe('drivePasskey.registerNoPrf');
      expect(host.querySelector('button')?.textContent).toContain('Set up a passkey');
      expect(host.querySelector('.error-message')?.textContent).toContain('could not protect deletions');
      const help = host.querySelector('.passkey-help')?.textContent ?? '';
      expect(help).toContain('Deleting a single backup still works');
      expect(help).toContain('security key');
      expect(host.textContent).not.toContain('This browser cannot make the kind of passkey');
      expect(host.textContent).not.toContain('Try again.');
    });

    it('offers the technical details (step names and flags) and copies them', async () => {
      const writeText = vi.fn(async () => undefined);
      Object.defineProperty(globalThis.navigator, 'clipboard', { value: { writeText }, configurable: true });
      const { host, detect, service } = await noPrf();
      expect(service.passkeyDetails).toHaveBeenCalledTimes(1);
      expect(host.querySelector('details summary')?.textContent).toContain('Technical details');
      expect(host.querySelector('.passkey-details')?.textContent).toBe(DETAILS);
      const copy = [...host.querySelectorAll('details button')].find((b) => b.textContent?.includes('Copy details')) as HTMLButtonElement;
      copy.click();
      await new Promise((resolve) => setTimeout(resolve, 0));
      detect();
      expect(writeText).toHaveBeenCalledWith(DETAILS);
      expect(host.textContent).toContain('Details copied.');
    });

    it('shows no details box when the authenticator reported none, but still the help', async () => {
      const { host } = await noPrf(null);
      expect(host.querySelector('.passkey-help')).toBeTruthy();
      expect(host.querySelector('details')).toBeNull();
    });

    it('clears the help and the details when the next attempt succeeds', async () => {
      const results: Array<'no-prf' | 'registered'> = ['no-prf', 'registered'];
      const service = createFakeDriveService({
        registerPasskey: vi.fn(async () => results.shift()!),
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyDetails: vi.fn(async () => DETAILS),
      });
      const { host, component, detect } = await render(service);
      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();
      await component.onRegisterPasskey();
      detect();
      expect(host.querySelector('.passkey-help')).toBeTruthy();
      await component.onRegisterPasskey();
      detect();
      expect(host.querySelector('.passkey-help')).toBeNull();
      expect(host.querySelector('details')).toBeNull();
    });
  });

  describe('PRF capability heads-up', () => {
    it('shows heads-up when capability is false', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyPrfCapability: vi.fn(async () => false),
      });
      const { host, component, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(component['prfHeadsUp']()).toBe(true);
      const headsUp = host.querySelector('.passkey-help')?.textContent ?? '';
      expect(headsUp).toContain('This browser says it cannot use the passkey feature');
      expect(host.querySelector('button')?.textContent).toContain('Set up a passkey');
      expect(host.querySelector('button')?.hasAttribute('disabled')).toBe(false);
    });

    it('does not show heads-up when capability is true', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyPrfCapability: vi.fn(async () => true),
      });
      const { host, component, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(component['prfHeadsUp']()).toBe(false);
      const headsUp = host.querySelector('.passkey-help')?.textContent ?? '';
      expect(headsUp).not.toContain('This browser says it cannot use the passkey feature');
    });

    it('does not show heads-up when capability is null', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyPrfCapability: vi.fn(async () => null),
      });
      const { host, component, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(component['prfHeadsUp']()).toBe(false);
      const headsUp = host.querySelector('.passkey-help')?.textContent ?? '';
      expect(headsUp).not.toContain('This browser says it cannot use the passkey feature');
    });

    it('does not check capability when status is registered', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'registered' as const),
        passkeyPrfCapability: vi.fn(async () => false),
      });
      const { component, service: svc, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(component['status']()).toBe('registered');
      expect(svc.passkeyPrfCapability).not.toHaveBeenCalled();
    });

    it('keeps Set up button enabled and renders no error alert when heads-up is shown', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyPrfCapability: vi.fn(async () => false),
      });
      const { host, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      const button = host.querySelector('button');
      expect(button?.hasAttribute('disabled')).toBe(false);
      expect(button?.textContent).toContain('Set up a passkey');

      const errorMessage = host.querySelector('.error-message');
      expect(errorMessage).toBeNull();
    });
  });

  it('has aria-live region for error messages', async () => {
    const service = createFakeDriveService();
    const { host } = await render(service);

    const errorRegion = host.querySelector('[aria-live="polite"]');
    expect(errorRegion).toBeTruthy();
    expect(errorRegion?.getAttribute('aria-atomic')).toBe('true');
  });

  describe('no built-in authenticator help', () => {
    it('shows help when status is none and builtIn is false', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyBuiltIn: vi.fn(async () => false),
      });
      const { host, component, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(component['noBuiltIn']()).toBe(true);
      const help = host.querySelector('.passkey-help')?.textContent ?? '';
      expect(help).toContain('This computer reports no built-in lock for passkeys');
      expect(host.querySelector('button')?.textContent).toContain('Set up a passkey');
      expect(host.querySelector('button')?.hasAttribute('disabled')).toBe(false);
    });

    it('does not show help when builtIn is true', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyBuiltIn: vi.fn(async () => true),
      });
      const { host, component, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(component['noBuiltIn']()).toBe(false);
      const helps = host.querySelectorAll('.passkey-help');
      const noBuiltInHelp = Array.from(helps).find((h) => h.textContent?.includes('This computer reports no built-in lock'));
      expect(noBuiltInHelp).toBeUndefined();
    });

    it('does not show help when builtIn is null', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyBuiltIn: vi.fn(async () => null),
      });
      const { host, component, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(component['noBuiltIn']()).toBe(false);
      const helps = host.querySelectorAll('.passkey-help');
      const noBuiltInHelp = Array.from(helps).find((h) => h.textContent?.includes('This computer reports no built-in lock'));
      expect(noBuiltInHelp).toBeUndefined();
    });

    it('does not show help when status is registered', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'registered' as const),
        passkeyBuiltIn: vi.fn(async () => false),
      });
      const { host, component, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(component['noBuiltIn']()).toBe(false);
      const helps = host.querySelectorAll('.passkey-help');
      const noBuiltInHelp = Array.from(helps).find((h) => h.textContent?.includes('This computer reports no built-in lock'));
      expect(noBuiltInHelp).toBeUndefined();
    });

    it('does not disable button when noBuiltIn is shown', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyBuiltIn: vi.fn(async () => false),
      });
      const { host, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      const button = host.querySelector('button');
      expect(button?.hasAttribute('disabled')).toBe(false);
      expect(button?.textContent).toContain('Set up a passkey');
    });

    it('does not render recovery key input when noBuiltIn is shown', async () => {
      const service = createFakeDriveService({
        passkeyStatus: vi.fn(async () => 'none' as const),
        passkeyBuiltIn: vi.fn(async () => false),
      });
      const { host, detect } = await render(service);

      await new Promise((resolve) => setTimeout(resolve, 50));
      detect();

      expect(host.querySelector('input[type="password"]')).toBeNull();
    });
  });
});
