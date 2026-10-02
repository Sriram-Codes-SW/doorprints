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
import { DriveDeleteCard } from './drive-delete';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import { TranslationService } from '../../../i18n/translation.service';

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
    providers: [
      { provide: DriveConnectService, useValue: svc },
      { provide: TranslationService, useValue: { t: (k: string) => k, lang: () => 'en' } },
    ],
  });
  const fixture = TestBed.createComponent(DriveDeleteCard);
  fixture.detectChanges();
  await fixture.whenStable();
  await flush();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, fixture, svc, component: fixture.componentInstance };
}

afterEach(() => TestBed.resetTestingModule());

describe('DriveDeleteCard', () => {
  it('shows the three deletion levels', async () => {
    const { host } = await render();
    expect(host.textContent).toContain('driveDelete.olderBackups');
    expect(host.textContent).toContain('driveDelete.allBackups');
    expect(host.textContent).toContain('driveDelete.everything');
  });

  it('asks for a tick box on confirm and does not wait', async () => {
    const { component, fixture, host } = await render();
    await component['startDeletion']({ type: 'everything' });
    fixture.detectChanges();
    await component['proceedToConfirm']();
    fixture.detectChanges();
    expect(host.querySelector('input[type="checkbox"]')).toBeTruthy();
    expect(host.textContent).not.toContain('driveDelete.countdown');
    const del = host.querySelectorAll('button');
    const deleteBtn = Array.from(del).find((b) => b.textContent?.includes('common.delete'));
    expect(deleteBtn?.disabled).toBe(true);
  });

  it('shows use-your-phone when L2 is refused without a passkey', async () => {
    const svc = fakeService({
      deletePlan: vi.fn().mockResolvedValue({ ok: false, reason: 'USE_PHONE' }),
    });
    const { component, fixture, host } = await render(svc);
    await component['startDeletion']({ type: 'allBackups' });
    fixture.detectChanges();
    expect(host.textContent).toContain('driveDelete.usePhone');
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
});
