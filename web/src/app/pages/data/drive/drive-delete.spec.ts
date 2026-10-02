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

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function fakeService(overrides: Record<string, unknown> = {}) {
  return {
    passkeyStatus: vi.fn().mockResolvedValue('none'),
    registerPasskey: vi.fn().mockResolvedValue('registered'),
    deletePlan: vi.fn().mockResolvedValue({ ok: true, plan: { operationId: 'op1' } }),
    deleteConfirmInfo: vi.fn().mockResolvedValue({ ok: true, tickBoxRequired: true, delayMs: 0 }),
    authorizeDelete: vi.fn().mockResolvedValue({ ok: true, grant: { id: 1, requirements: { level: 'L3' } } }),
    executeDelete: vi.fn().mockResolvedValue({ ok: true }),
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
    const deleteBtn = Array.from(del).find((b) => b.textContent?.includes(i18n.t('common.delete')));
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
});
