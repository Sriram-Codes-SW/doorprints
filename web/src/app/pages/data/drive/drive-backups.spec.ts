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

import { TestBed, ComponentFixture } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DriveBackupsCard } from './drive-backups';
import { DriveConnectService, type BackupSummary } from '../../../data/drive/connect/drive-connect.service';
import { Announcer } from '../../../core/announcer.service';
import { TranslationService } from '../../../i18n/translation.service';

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function fakeDriveService() {
  return {
    listBackups: vi.fn().mockResolvedValue({ ok: true, backups: [], missingNewer: false }),
    autoBackupEnabled: vi.fn(() => false),
    setAutoBackup: vi.fn(),
    runDueBackup: vi.fn().mockResolvedValue({ ran: false, reason: 'DISABLED' }),
    backUpNow: vi.fn(),
    confirmShrink: vi.fn(),
    importFromDrive: vi.fn(),
  };
}

async function render(fakes: ReturnType<typeof fakeDriveService>) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [DriveBackupsCard],
    providers: [
      { provide: DriveConnectService, useValue: fakes },
      { provide: Announcer, useValue: { announce: vi.fn() } },
      { provide: TranslationService, useValue: { t: (k: string, p?: any) => k, dateTime: (s: string) => new Date(s).toLocaleString(), lang: () => 'en' } },
    ],
  });
  const fixture = TestBed.createComponent(DriveBackupsCard);
  fixture.detectChanges();
  await fixture.whenStable();
  await flush();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, fakes, fixture };
}

afterEach(() => {
  TestBed.resetTestingModule();
});

describe('DriveBackupsCard', () => {
  it('creates and loads empty backups on init', async () => {
    const fakes = fakeDriveService();
    const { host, fakes: f } = await render(fakes);

    expect(f.listBackups).toHaveBeenCalledOnce();
    expect(host.textContent).toContain('driveBackups.emptyState');
  });

  it('shows two backup rows with houses and size text', async () => {
    const backups: BackupSummary[] = [
      { id: 'b1', createdAt: new Date('2026-10-01T10:00:00Z').getTime(), houses: 5, bytes: 1024 * 512, name: 'b1' },
      { id: 'b2', createdAt: new Date('2026-09-30T15:00:00Z').getTime(), houses: 3, bytes: 1024 * 256, name: 'b2' },
    ];
    const fakes = fakeDriveService();
    fakes.listBackups.mockResolvedValue({ ok: true, backups, missingNewer: false });
    const { host } = await render(fakes);

    const rows = host.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('5');
    expect(rows[0].textContent).toContain('512');
    expect(rows[1].textContent).toContain('3');
    expect(rows[1].textContent).toContain('256');
  });

  it('backs up now and shows how many houses', async () => {
    const fakes = fakeDriveService();
    fakes.backUpNow.mockResolvedValue({
      ok: true,
      backup: { id: 'n1', createdAt: Date.parse('2026-10-02T12:00:00Z'), houses: 4, bytes: 2048, name: 'n1' },
      needsShrinkConfirmation: false,
      missingNewer: false,
    });
    const { host, fixture } = await render(fakes);
    const btn = host.querySelector<HTMLButtonElement>('button[aria-label="driveBackups.backUpNow"]')!;
    btn.click();
    await flush();
    fixture.detectChanges();
    expect(fakes.backUpNow).toHaveBeenCalledOnce();
    expect(host.textContent).toContain('driveBackups.housesBackedUp');
  });

  it('shows a backup error from the service', async () => {
    const fakes = fakeDriveService();
    fakes.backUpNow.mockResolvedValue({ ok: false, reason: 'Not connected to folder' });
    const { host, fixture } = await render(fakes);
    host.querySelector<HTMLButtonElement>('button[aria-label="driveBackups.backUpNow"]')!.click();
    await flush();
    fixture.detectChanges();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('driveBackups.error.notConnected');
  });

  it('asks before shrinking older backups, and confirm calls the service', async () => {
    const fakes = fakeDriveService();
    fakes.backUpNow.mockResolvedValue({
      ok: true,
      backup: { id: 'small', createdAt: Date.now(), houses: 1, bytes: 100, name: 'small' },
      needsShrinkConfirmation: true,
      missingNewer: false,
    });
    fakes.confirmShrink.mockResolvedValue(undefined);
    const { host, fixture } = await render(fakes);
    host.querySelector<HTMLButtonElement>('button[aria-label="driveBackups.backUpNow"]')!.click();
    await flush();
    fixture.detectChanges();
    expect(host.querySelector('[role="dialog"]')?.textContent).toContain('driveBackups.shrinkConfirmQuestion');
    host.querySelector<HTMLButtonElement>('button[aria-label="driveBackups.confirmShrink"]')!.click();
    await flush();
    expect(fakes.confirmShrink).toHaveBeenCalledWith('small');
  });

  it('emits the imported Blob to the parent', async () => {
    const blob = new Blob(['zip']);
    const backups: BackupSummary[] = [
      { id: 'b1', createdAt: Date.now(), houses: 2, bytes: 1024, name: 'b1' },
    ];
    const fakes = fakeDriveService();
    fakes.listBackups.mockResolvedValue({ ok: true, backups, missingNewer: false });
    fakes.importFromDrive.mockResolvedValue({ ok: true, file: blob });
    const { host, fixture } = await render(fakes);
    const seen: Blob[] = [];
    fixture.componentInstance.importFile.subscribe((f) => seen.push(f));
    host.querySelector<HTMLButtonElement>('button[aria-label="driveBackups.importBackup"]')!.click();
    await flush();
    expect(fakes.importFromDrive).toHaveBeenCalledWith('b1');
    expect(seen).toEqual([blob]);
  });

  it('toggles automatic backup', async () => {
    const fakes = fakeDriveService();
    const { host } = await render(fakes);
    const box = host.querySelector<HTMLInputElement>('input[type="checkbox"][aria-label="driveBackups.autoBackup"]')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    expect(fakes.setAutoBackup).toHaveBeenCalledWith(true);
  });

  it('retries a failed list', async () => {
    const fakes = fakeDriveService();
    fakes.listBackups.mockResolvedValueOnce({ ok: false, reason: 'offline' });
    const { host, fixture } = await render(fakes);
    expect(host.querySelector('[role="alert"]')).toBeTruthy();
    fakes.listBackups.mockResolvedValue({ ok: true, backups: [], missingNewer: false });
    const retry = Array.from(host.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('driveBackups.retry'),
    );
    retry!.click();
    await flush();
    fixture.detectChanges();
    expect(fakes.listBackups.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('shows a live region for the empty list', async () => {
    const { host } = await render(fakeDriveService());
    const empty = host.querySelector('.empty');
    expect(empty?.getAttribute('role')).toBe('status');
  });
});
