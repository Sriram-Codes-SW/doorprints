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
      { provide: TranslationService, useValue: { t: (k: string) => k, dateTime: (s: string) => new Date(s).toLocaleString() } },
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
});
