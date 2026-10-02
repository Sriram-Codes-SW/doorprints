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

import type { ByteSource } from '../../../crypto/dpx';
import { BACKUP_FORMAT } from '../../../export/backup-export';
import type { ExportOptions } from '../../../export/export-model';
import { ExportService } from '../../../export/export.service';
import type { BackupPayload, BackupSource } from '../backup/drive-backup-seams';

/**
 * Production {@link BackupSource} for Drive sync: reads the app's existing *Full backup* ZIP via
 * {@link ExportService.build} and wraps it as a {@link BackupPayload} for upload.
 */
export function createDriveBackupSource(exporter: ExportService, options: ExportOptions): BackupSource {
  return async () => {
    // Build the full backup ZIP as the app normally does for 'Save a copy'.
    const result = await exporter.build('backup', options, new Date());

    if (!result.blob) {
      throw new Error('Backup build returned no blob');
    }

    // Read the ZIP into a byte array.
    const arrayBuffer = await result.blob.arrayBuffer();
    const bytes = new Uint8Array(arrayBuffer);

    // Return a ByteSource that reads the ZIP sequentially.
    const payload: BackupPayload = {
      source: bytesSource(bytes),
      format: BACKUP_FORMAT,
      houses: result.counts.houses,
      close: undefined,
    };
    return payload;
  };
}

/**
 * A {@link ByteSource} that reads a fixed byte array sequentially from the start.
 */
function bytesSource(bytes: Uint8Array): ByteSource {
  let at = 0;
  return {
    async read(max: number) {
      if (at >= bytes.length) return null;
      const end = Math.min(at + max, bytes.length);
      const chunk = bytes.slice(at, end);
      at = end;
      return chunk;
    },
  };
}
