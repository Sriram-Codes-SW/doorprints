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
import { DriveDeleteCard } from './drive-delete';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import { TranslationService } from '../../../i18n/translation.service';
import type { TKey } from '../../../i18n/en';
import type { Params } from '../../../i18n/translation.service';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function fakeService(overrides: Record<string, unknown> = {}) {
  return {
    passkeyStatus: vi.fn().mockResolvedValue('none'),
    registerPasskey: vi.fn().mockResolvedValue('registered'),
    deletePlan: vi.fn().mockResolvedValue({ ok: true, plan: { operationId: 'op1' } }),
    deleteConfirmInfo: vi.fn().mockResolvedValue({ ok: true, tickBoxRequired: true, delayMs: 0 }),
    deleteFactor: vi.fn().mockResolvedValue(null),
    recoveryKeyOffered: vi.fn().mockResolvedValue(false),
    authorizeDelete: vi.fn().mockResolvedValue({ ok: true, grant: { id: 1, requirements: { level: 'L3' } } }),
    executeDelete: vi.fn().mockResolvedValue({ ok: true, finished: true, left: 0, total: 1 }),
    resumeDelete: vi.fn().mockResolvedValue({ ok: true, finished: true, left: 0, total: 1 }),
    ...overrides,
  };
}

async function render(svc = fakeService()) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveDeleteCard],
    providers: [{ provide: DriveConnectService, useValue: svc }],
  });
  const fixture = TestBed.createComponent(DriveDeleteCard);
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

describe('DriveDeleteCard', () => {
  it('shows the three deletion levels', async () => {
    const { host, i18n } = await render();
    shown(host, i18n, 'driveDelete.olderBackups');
    shown(host, i18n, 'driveDelete.allBackups');
    shown(host, i18n, 'driveDelete.everything');
  });

  it('asks for a tick box on confirm and does not wait', async () => {
    const { component, fixture, host, i18n } = await render();
    await component['startDeletion']({ type: 'everything' });
    fixture.detectChanges();
    await component['proceedToConfirm']();
    fixture.detectChanges();
    expect(host.querySelector('input[type="checkbox"]')).toBeTruthy();
    const countdown = i18n.t('driveDelete.countdown', { seconds: 5 });
    expect(host.textContent).not.toContain('driveDelete.countdown');
    expect(host.textContent).not.toContain(countdown);
    const del = host.querySelectorAll('button');
    const deleteBtn = Array.from(del).find((b) => b.textContent?.includes(i18n.t('driveDelete.deleteForGood')));
    expect(deleteBtn?.disabled).toBe(true);
  });

  it('shows use-your-phone when L2 is refused without a passkey', async () => {
    const svc = fakeService({
      deletePlan: vi.fn().mockResolvedValue({ ok: false, reason: 'USE_PHONE' }),
    });
    const { component, fixture, host, i18n } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    fixture.detectChanges();
    shown(host, i18n, 'driveDelete.usePhone');
  });

  it('executes after the tick box is checked', async () => {
    const { component, fixture, svc } = await render();
    await component['startDeletion']({ type: 'everything' });
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    await component['confirmDelete']();
    fixture.detectChanges();
    expect(svc.executeDelete).toHaveBeenCalled();
  });

  it('disables the tick box while busy or while the delete is running', async () => {
    const { component, fixture, host } = await render();
    await component['startDeletion']({ type: 'everything' });
    await component['proceedToConfirm']();
    fixture.detectChanges();
    const box = () => host.querySelector('input[type="checkbox"]') as HTMLInputElement | null;
    expect(box()?.disabled).toBe(false);
    component['busy'].set(true);
    fixture.detectChanges();
    expect(box()?.disabled).toBe(true);
    component['busy'].set(false);
    component['phase'].set('running');
    fixture.detectChanges();
    expect(box()).toBeNull();
  });

  it('does not execute if the tick box is cleared during the passkey prompt', async () => {
    let resolveAuth!: (value: { ok: true; grant: { id: number; requirements: { level: string } } }) => void;
    const authPromise = new Promise<{ ok: true; grant: { id: number; requirements: { level: string } } }>((resolve) => {
      resolveAuth = resolve;
    });
    const svc = fakeService({
      authorizeDelete: vi.fn().mockReturnValue(authPromise),
    });
    const { component, fixture } = await render(svc);
    await component['startDeletion']({ type: 'everything' });
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    const done = component['confirmDelete']();
    await flush();
    expect(component['busy']()).toBe(true);
    expect(svc.authorizeDelete).toHaveBeenCalled();
    component['ticked'].set(false);
    resolveAuth({ ok: true, grant: { id: 1, requirements: { level: 'L3' } } });
    await done;
    fixture.detectChanges();
    expect(svc.executeDelete).not.toHaveBeenCalled();
    expect(component['error']()).toBe('driveDelete.tickRequired');
    expect(component['phase']()).toBe('error');
  });

  it('shows how many files are left and Try again resumes', async () => {
    const svc = fakeService({
      executeDelete: vi.fn().mockResolvedValue({ ok: true, finished: false, left: 2, total: 5 }),
      resumeDelete: vi.fn().mockResolvedValue({ ok: true, finished: true, left: 0, total: 5 }),
    });
    const { component, fixture, host, i18n } = await render(svc);
    await component['startDeletion']({ type: 'everything' });
    fixture.detectChanges();
    shown(host, i18n, 'driveDelete.saveCopyFirst');
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    await component['confirmDelete']();
    fixture.detectChanges();
    expect(host.textContent).toContain(i18n.t('driveDelete.left', { left: 2, total: 5 }));
    shown(host, i18n, 'driveDelete.tryAgain');
    await component['tryAgain']();
    expect(svc.resumeDelete).toHaveBeenCalled();
    fixture.detectChanges();
    shown(host, i18n, 'driveDelete.done');
  });

  it('shows recovery key input when passkey is needed but not registered and recovery is offered', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
    });
    const { component, fixture, host, i18n } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    fixture.detectChanges();
    await component['proceedToConfirm']();
    fixture.detectChanges();
    const label = host.querySelector('label[for="drive-recovery-key"]');
    expect(label?.textContent).toContain(i18n.t('driveDelete.recoveryLabel'));
    const input = host.querySelector('input[id="drive-recovery-key"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(input?.type).toBe('password');
    expect(input?.getAttribute('autocomplete')).toBe('off');
  });

  it('shows the recovery key input when a passkey returned no PRF output after this card loaded (it reads the state again)', async () => {
    // At load nothing had gone wrong: the key is not offered. Then the person sets up a passkey, it returns no PRF output,
    // and they delete again from the same card.
    const offered = vi.fn().mockResolvedValueOnce(false).mockResolvedValue(true);
    const svc = fakeService({ deleteFactor: vi.fn().mockResolvedValue('PASSKEY'), recoveryKeyOffered: offered });
    const { component, fixture, host } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    fixture.detectChanges();
    await component['proceedToConfirm']();
    fixture.detectChanges();
    expect(host.querySelector('input[id="drive-recovery-key"]')).toBeTruthy();
  });

  it('hides the recovery key input when a passkey was registered after this card loaded', async () => {
    const status = vi.fn().mockResolvedValueOnce('none').mockResolvedValue('registered');
    const svc = fakeService({ passkeyStatus: status, deleteFactor: vi.fn().mockResolvedValue('PASSKEY'), recoveryKeyOffered: vi.fn().mockResolvedValue(true) });
    const { component, fixture, host } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    fixture.detectChanges();
    await component['proceedToConfirm']();
    fixture.detectChanges();
    expect(host.querySelector('input[id="drive-recovery-key"]')).toBeNull();
  });

  it('does not show recovery key input when passkey is registered', async () => {
    const svc = fakeService({
      passkeyStatus: vi.fn().mockResolvedValue('registered'),
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
    });
    const { component, fixture, host } = await render(svc);
    component['passkeyStatus'].set('registered');
    await component['startDeletion']({ type: 'allBackups' });
    fixture.detectChanges();
    await component['proceedToConfirm']();
    fixture.detectChanges();
    const input = host.querySelector('input[id="drive-recovery-key"]');
    expect(input).toBeNull();
  });

  it('does not show recovery key input when recovery is not offered', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(false),
    });
    const { component, fixture, host } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    fixture.detectChanges();
    await component['proceedToConfirm']();
    fixture.detectChanges();
    const input = host.querySelector('input[id="drive-recovery-key"]');
    expect(input).toBeNull();
  });

  it('does not show recovery key input for NONE factor', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('NONE'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
    });
    const { component, fixture, host } = await render(svc);
    await component['startDeletion']({ type: 'olderBackups' });
    fixture.detectChanges();
    await component['proceedToConfirm']();
    fixture.detectChanges();
    const input = host.querySelector('input[id="drive-recovery-key"]');
    expect(input).toBeNull();
  });

  it('calls authorizeDelete with recovery key text when typed and Delete is clicked', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
    });
    const { component, fixture, svc: mockSvc } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('input[id="drive-recovery-key"]') as HTMLInputElement;
    input.value = 'test-recovery-key';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await component['confirmDelete']();
    fixture.detectChanges();
    expect(mockSvc.authorizeDelete).toHaveBeenCalledWith(
      { type: 'allBackups' },
      'op1',
      'test-recovery-key'
    );
  });

  it('clears recovery key text after authorization attempt', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
    });
    const { component, fixture, host } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    fixture.detectChanges();
    const input = host.querySelector('input[id="drive-recovery-key"]') as HTMLInputElement;
    input.value = 'test-key';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await component['confirmDelete']();
    fixture.detectChanges();
    expect(component['recoveryKeyText']()).toBe('');
    expect(host.textContent).not.toContain('test-key');
  });

  it('shows recoveryWrong error when authorizeDelete returns RECOVERY_KEY_WRONG', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
      authorizeDelete: vi.fn().mockResolvedValue({ ok: false, reason: 'RECOVERY_KEY_WRONG' }),
    });
    const { component, fixture, host, i18n } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    fixture.detectChanges();
    component['recoveryKeyText'].set('wrong-key');
    fixture.detectChanges();
    await component['confirmDelete']();
    fixture.detectChanges();
    expect(component['phase']()).toBe('confirm');
    expect(component['recoveryError']()).toBe('driveDelete.recoveryWrong');
    const alert = host.querySelector('p[role="alert"]');
    expect(alert?.textContent).toContain(i18n.t('driveDelete.recoveryWrong'));
  });

  it('shows recoveryInvalid error when authorizeDelete returns RECOVERY_KEY_INVALID', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
      authorizeDelete: vi.fn().mockResolvedValue({ ok: false, reason: 'RECOVERY_KEY_INVALID' }),
    });
    const { component, fixture, host, i18n } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    fixture.detectChanges();
    component['recoveryKeyText'].set('invalid-key');
    fixture.detectChanges();
    await component['confirmDelete']();
    fixture.detectChanges();
    expect(component['phase']()).toBe('confirm');
    expect(component['recoveryError']()).toBe('driveDelete.recoveryInvalid');
    const alert = host.querySelector('p[role="alert"]');
    expect(alert?.textContent).toContain(i18n.t('driveDelete.recoveryInvalid'));
  });

  it('stays in confirm phase with empty recovery key field and does not call Delete', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
    });
    const { component, fixture, host } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    fixture.detectChanges();
    const deleteBtn = Array.from(host.querySelectorAll('button')).find((b) => b.textContent?.includes('Delete for good'));
    expect(deleteBtn?.disabled).toBe(true);
  });

  it('shows recovery key input again in partial phase and Try again passes new key', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
      executeDelete: vi.fn().mockResolvedValue({ ok: true, finished: false, left: 2, total: 5 }),
      resumeDelete: vi.fn().mockResolvedValue({ ok: true, finished: true, left: 0, total: 5 }),
    });
    const { component, fixture, host } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    await component['proceedToConfirm']();
    component['toggleTickBox']();
    component['recoveryKeyText'].set('key1');
    await component['confirmDelete']();
    fixture.detectChanges();
    expect(component['phase']()).toBe('partial');
    const input = host.querySelector('input[id="drive-recovery-key"]') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(input?.value).toBe('');
    input.value = 'key2';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await component['tryAgain']();
    fixture.detectChanges();
    expect(svc.resumeDelete).toHaveBeenCalled();
  });

  it('cancel clears recovery key text and error', async () => {
    const svc = fakeService({
      deleteFactor: vi.fn().mockResolvedValue('PASSKEY'),
      recoveryKeyOffered: vi.fn().mockResolvedValue(true),
    });
    const { component, fixture } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    await component['proceedToConfirm']();
    component['recoveryKeyText'].set('some-key');
    component['recoveryError'].set('driveDelete.recoveryWrong');
    fixture.detectChanges();
    component['cancel']();
    fixture.detectChanges();
    expect(component['recoveryKeyText']()).toBe('');
    expect(component['recoveryError']()).toBeNull();
    expect(component['askRecoveryKey']()).toBe(false);
    expect(component['phase']()).toBe('menu');
  });
});
