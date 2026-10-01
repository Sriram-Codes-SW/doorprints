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

import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LaunchFileHandle, LaunchParamsLike } from './launch-files.service';
import { IMPORT_ROUTE, LaunchFilesService, isBackupFileName, launchQueueOf } from './launch-files.service';

@Component({ template: '' })
class Blank {}

/** A fake `window.launchQueue`: keeps the consumer so a test can launch the app with files. */
function fakeWindow() {
  let consumer: ((p: LaunchParamsLike) => void) | null = null;
  return {
    win: { launchQueue: { setConsumer: (c: (p: LaunchParamsLike) => void) => (consumer = c) } },
    launch: (params: LaunchParamsLike) => consumer?.(params),
    has: () => consumer !== null,
  };
}

const handleOf = (name: string, body = 'PK'): LaunchFileHandle => ({ kind: 'file', getFile: async () => new File([body], name) });

function setUp() {
  TestBed.configureTestingModule({
    providers: [provideRouter([{ path: '', component: Blank }, { path: 'data', component: Blank }, { path: 'compare', component: Blank }])],
  });
  return { service: TestBed.inject(LaunchFilesService), router: TestBed.inject(Router) };
}

afterEach(() => TestBed.resetTestingModule());

/**
 * S4b-BL-108: a backup double-clicked on a computer reaches Import a backup through `window.launchQueue` (Chromium's File
 * Handling API). One `.zip` is handed on and the app moves to Your data; anything else is ignored; a browser without
 * the API is left alone.
 */
describe('LaunchFilesService', () => {
  it('does nothing without a launch queue (Firefox, Safari, a phone, a tab)', () => {
    const { service } = setUp();
    expect(service.start({})).toBe(false);
    expect(service.start(undefined)).toBe(false);
    expect(service.start({ launchQueue: {} })).toBe(false);
    expect(service.start({ launchQueue: { setConsumer: 'no' } })).toBe(false);
    expect(launchQueueOf(null)).toBeNull();
    expect(service.pending()).toBeNull();
  });

  it('hands one .zip backup to Import a backup and opens Your data', async () => {
    const { service, router } = setUp();
    await router.navigateByUrl('/compare');
    const fake = fakeWindow();
    expect(service.start(fake.win)).toBe(true);
    expect(fake.has()).toBe(true);
    fake.launch({ files: [handleOf('Doorprints-backup-2026-05-28.zip')] });
    await vi.waitFor(() => expect(router.url).toBe(IMPORT_ROUTE));
    expect(service.pending()?.name).toBe('Doorprints-backup-2026-05-28.zip');
    expect(service.take()?.name).toBe('Doorprints-backup-2026-05-28.zip');
    expect(service.take()).toBeNull();
  });

  it('stays on Your data when the app was opened there', async () => {
    const { service, router } = setUp();
    await router.navigateByUrl('/data');
    const navigate = vi.spyOn(router, 'navigateByUrl');
    expect(await service.receive({ files: [handleOf('backup.ZIP')] })).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    expect(service.pending()?.name).toBe('backup.ZIP');
  });

  it('ignores a file that is not a .zip, two files, no files and a handle that cannot be read', async () => {
    const { service, router } = setUp();
    await router.navigateByUrl('/compare');
    expect(await service.receive({ files: [handleOf('notes.pdf')] })).toBe(false);
    expect(await service.receive({ files: [handleOf('data.json')] })).toBe(false);
    expect(await service.receive({ files: [handleOf('a.zip'), handleOf('b.zip')] })).toBe(false);
    expect(await service.receive({ files: [] })).toBe(false);
    expect(await service.receive({})).toBe(false);
    expect(await service.receive(null)).toBe(false);
    expect(await service.receive({ files: [{ kind: 'directory', getFile: async () => new File([], 'x.zip') }] })).toBe(false);
    expect(await service.receive({ files: [{ kind: 'file', getFile: () => Promise.reject(new Error('gone')) }] })).toBe(false);
    expect(service.pending()).toBeNull();
    expect(router.url).toBe('/compare');
  });

  it('drops the file when the move to Your data is cancelled (Keep editing)', async () => {
    const { service, router } = setUp();
    await router.navigateByUrl('/compare');
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(false);
    expect(await service.receive({ files: [handleOf('backup.zip')] })).toBe(false);
    expect(service.pending()).toBeNull();
  });

  it('knows a backup by its .zip name', () => {
    expect(isBackupFileName('Doorprints-backup-2026-05-28.zip')).toBe(true);
    expect(isBackupFileName('copy.Zip')).toBe(true);
    expect(isBackupFileName('data.json')).toBe(false);
    expect(isBackupFileName('zip')).toBe(false);
    expect(isBackupFileName(undefined)).toBe(false);
  });
});
