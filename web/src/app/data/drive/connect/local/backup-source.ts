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
import { BACKUP_FORMAT } from '../../../../export/backup-export';
import type { ExportOptions } from '../../../../export/export-model';
import { ExportService } from '../../../../export/export.service';
import type { BackupPayload, BackupSource } from '../../backup/drive-backup-seams';

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

    const payload: BackupPayload = {
      source: blobSource(result.blob),
      format: BACKUP_FORMAT,
      houses: result.counts.houses,
      close: undefined,
    };
    return payload;
  };
}

/**
 * A {@link ByteSource} that reads the ZIP in slices so the whole backup is not copied into a second ArrayBuffer.
 */
function blobSource(blob: Blob): ByteSource {
  let at = 0;
  return {
    async read(max: number) {
      if (at >= blob.size) return null;
      const end = Math.min(at + max, blob.size);
      const buf = await blob.slice(at, end).arrayBuffer();
      at = end;
      return new Uint8Array(buf);
    },
  };
}
