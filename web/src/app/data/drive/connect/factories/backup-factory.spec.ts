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

import { DriveBackupAdapter } from '../backup-adapter';
import { createBackupAdapter, createLazyBackupAdapterProxy } from './backup-factory';
import type { DriveRuntime } from './runtime';

describe('backup factories', () => {
  it('createBackupAdapter is exported as a function', () => {
    expect(typeof createBackupAdapter).toBe('function');
  });

  it('createLazyBackupAdapterProxy is exported as a function', () => {
    expect(typeof createLazyBackupAdapterProxy).toBe('function');
  });

  it('createLazyBackupAdapterProxy returns a proxy with adapter methods', () => {
    const mockRuntime = {} as DriveRuntime;
    const getRuntime = async () => mockRuntime;
    const proxy = createLazyBackupAdapterProxy(getRuntime);

    expect(proxy).toBeDefined();
    expect(typeof proxy.connect).toBe('function');
    expect(typeof proxy.createFolder).toBe('function');
    expect(typeof proxy.backUpNow).toBe('function');
    expect(typeof proxy.listBackups).toBe('function');
    expect(typeof proxy.importFromDrive).toBe('function');
    expect(typeof proxy.writeReadMe).toBe('function');
    expect(typeof proxy.schedule).toBe('function');
  });
});
