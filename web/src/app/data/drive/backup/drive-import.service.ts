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

import { equalBytes, hex } from '../../crypto/bytes';
import type { CryptoProvider } from '../../crypto/crypto-provider';
import { Dpx, DpxError } from '../../crypto/dpx';
import { revokedEpochRule } from '../../crypto/keys-guard';
import { BACKUP_FORMATS_READ } from '../../../export/backup-export';
import { Sha256 } from '../../../export/sha256';
import { DRIVE_LAYOUT, DriveError } from '../drive-client';
import type { DriveClient } from '../drive-client';
import { downloadTo } from '../drive-ops';
import { BackupMeta } from './backup-meta';
import { DriveProblem } from './drive-backup-results';
import type { DriveBackup, ImportDownload, ReadyFolder } from './drive-backup-results';
import { memoryScratchSpace, scratchSource } from './drive-backup-seams';
import type { ScratchSpace, StagingSink } from './drive-backup-seams';

/** A backup ZIP is at most 1 GiB of contents, plus its directory (Kotlin `DriveBackupService.MAX_PLAINTEXT`). */
const MAX_PLAINTEXT = 1024 * 1024 * 1024 + 64 * 1024 * 1024;

/**
 * *Import a backup* > *From Google Drive* (S4b-BL-116; docs/15 §1.1 G2, §1.4 item 8); the twin of Kotlin's
 * `DriveImportService`. The list comes from `DriveBackupService.listBackups` (only checked backups, newest first by
 * their authenticated time); `download` fetches the chosen one, proves it, and writes the decrypted ZIP into the staging
 * sink (the website hands it on as a Blob to the existing import page's `check`). No import logic is repeated here.
 *
 * **Fail closed**: the metadata is read and verified again (the file may have changed since the listing), the bytes
 * must have the checksum the metadata's MAC covers, the `dpx/1` header must name the metadata's epoch and writer, and
 * every chunk must authenticate. On any refusal the staging sink is discarded: nothing reaches the import.
 */
export class DriveImportService {
  constructor(
    private readonly drive: DriveClient,
    private readonly p: CryptoProvider,
    private readonly scratch: ScratchSpace = memoryScratchSpace,
  ) {}

  async download(folder: ReadyFolder, backup: DriveBackup, staging: StagingSink, maxPlaintext = MAX_PLAINTEXT): Promise<ImportDownload> {
    const file = this.scratch.create();
    const refused = async (kind: ConstructorParameters<typeof DriveProblem>[0]): Promise<ImportDownload> => {
      await staging.discard();
      return { kind: 'refused', problem: new DriveProblem(kind) };
    };
    try {
      const f = await this.drive.getFile(backup.fileId);
      if (f.trashed || (folder.backupsId != null && !f.parents.includes(folder.backupsId))) return await refused('BACKUP_GONE');
      const read = BackupMeta.read(f);
      if (!read) return await refused('BACKUP_REFUSED');
      const { meta, mac } = read;
      const deletedAt = folder.control.backupsDeletedAt;
      if (
        f.appProperties[DRIVE_LAYOUT.state] !== DRIVE_LAYOUT.stateComplete ||
        hex(meta.ciphertextSha256) !== backup.sha256 || meta.createdAt !== backup.createdAt ||
        meta.epoch !== backup.epoch || !equalBytes(meta.writerKid, backup.writerKid) ||
        (deletedAt != null && meta.createdAt <= deletedAt) ||
        meta.epoch > folder.keys.epoch || !(await BackupMeta.verify(this.p, folder.keys, meta, mac)) ||
        revokedEpochRule(folder.keys.body, meta.epoch, meta.writerKid, meta.createdAt) !== 'ACCEPT'
      ) {
        return await refused('BACKUP_REFUSED');
      }
      const size = f.size != null && f.size > 0 ? f.size : null;
      if (size == null) return await refused('BACKUP_REFUSED');
      const hash = new Sha256();
      await downloadTo(this.drive, f.id, size, async (bytes) => {
        hash.update(bytes);
        await file.write(bytes);
      });
      if (file.size !== size || hex(hash.digest()) !== backup.sha256) throw new DriveError('CORRUPT', 0, null, 'checksumMismatch');
      const dpx = new Dpx(this.p);
      const inner = (await dpx.readHeader(scratchSource(file))).inner;
      if (!BACKUP_FORMATS_READ.includes(inner)) throw new DpxError('INNER_MISMATCH', 'not a backup');
      const result = await dpx.decrypt(folder.keys, inner, scratchSource(file), staging.write, {
        maxPlaintext,
        expectedCiphertextSha256: meta.ciphertextSha256,
        headerCheck: (h) => {
          if (h.epoch !== meta.epoch || !equalBytes(h.kid, meta.writerKid)) throw new DpxError('HEADER_INVALID', 'header and metadata differ');
        },
      });
      return { kind: 'verified', backup, format: inner, plaintextSize: result.plaintextSize };
    } catch (e) {
      await staging.discard();
      return { kind: 'refused', problem: DriveProblem.of(e) };
    } finally {
      await file.delete();
    }
  }
}
