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

import { describe, expect, it } from 'vitest';
import type { BackupAdapterInterface } from './backup-adapter';
import type { DriveBackup, ReadyFolder } from '../backup/drive-backup-results';
import type { KeysWatermarkStore } from '../../crypto/keys-file';

/** `false` when T is `any` (`0 extends 1 & any` is true). */
type NotAny<T> = 0 extends 1 & T ? false : true;

describe('Drive production types are not any', () => {
  it('BackupAdapterInterface takes ReadyFolder and DriveBackup', () => {
    type FolderArg = Parameters<BackupAdapterInterface['listBackups']>[0];
    type BackupArg = Parameters<BackupAdapterInterface['importFromDrive']>[2];
    const folderIsReady: NotAny<FolderArg> = true;
    const folderIsFolder: FolderArg extends ReadyFolder ? true : false = true;
    const backupIsBackup: BackupArg extends DriveBackup ? true : false = true;
    const backupNotAny: NotAny<BackupArg> = true;
    expect(folderIsReady && folderIsFolder && backupIsBackup && backupNotAny).toBe(true);
  });

  it('KeysWatermarkStore load/compareAndSet are not any', () => {
    type Loaded = Awaited<ReturnType<KeysWatermarkStore['load']>>;
    type Expected = Parameters<KeysWatermarkStore['compareAndSet']>[0];
    const loadNotAny: NotAny<Loaded> = true;
    const expectedNotAny: NotAny<Expected> = true;
    expect(loadNotAny && expectedNotAny).toBe(true);
  });
});
