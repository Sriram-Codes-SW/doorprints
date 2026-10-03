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

import type { CryptoProvider } from '../../crypto/crypto-provider';
import type { OpenedKeys } from '../../crypto/keys-file';
import { revokedEpochRule } from '../../crypto/keys-guard';
import { DRIVE_LAYOUT, DriveError, isFolder } from '../drive-client';
import type { DriveClient, DriveFile } from '../drive-client';
import { listAll } from '../drive-ops';
import { BackupMeta, BACKUP_META } from './backup-meta';
import type { BackupListing, DriveBackup, IgnoredReason, ReadyFolder } from './drive-backup-results';

/**
 * Lists `Backups/` and keeps only what is a backup (S4b-BL-116): `state=complete`, the metadata canonical and its MAC
 * verified over Drive's own checksum under a folder key of the opened list, the writer accepted by the revoked-epoch
 * rule (with the authenticated `createdAt`), made after `backupsDeletedAt`, and one per content. The twin of Kotlin's
 * `BackupLister`.
 */
export class BackupLister {
  constructor(
    private readonly drive: DriveClient,
    private readonly p: CryptoProvider,
  ) {}

  async list(folder: ReadyFolder, backupsId: string | null, knownIds: readonly string[], newestSeenAt: number | null): Promise<BackupListing> {
    if (backupsId == null) return { backups: [], unfinished: [], duplicates: [], junk: [], ignored: [], missingNewer: false };
    const files = await listAll(this.drive, { parentId: backupsId, appProperties: { [DRIVE_LAYOUT.kind]: BACKUP_META.kindBackup } });
    // A listing may lag behind this device's own write (docs/15 §7.1): ask for those by id.
    for (const id of knownIds) {
      if (files.some((f) => f.id === id)) continue;
      let f: DriveFile | null = null;
      try {
        f = await this.drive.getFile(id);
      } catch (e) {
        if (!(e instanceof DriveError) || e.kind !== 'NOT_FOUND') throw e;
      }
      if (f && !f.trashed && f.parents.includes(backupsId)) files.push(f);
    }
    return this.classify(files, folder.keys, folder.control.backupsDeletedAt, newestSeenAt);
  }

  async classify(files: readonly DriveFile[], keys: OpenedKeys, deletedAt: number | null, newestSeenAt: number | null): Promise<BackupListing> {
    const complete: DriveBackup[] = [];
    const partialValid: DriveBackup[] = [];
    const junk: DriveFile[] = [];
    const ignored: [string, IgnoredReason][] = [];
    const ordered = [...files].sort((a, b) => a.createdTime - b.createdTime || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    for (const f of ordered) {
      if (isFolder(f) || f.trashed) continue;
      const partial = f.appProperties[DRIVE_LAYOUT.state] !== DRIVE_LAYOUT.stateComplete;
      if (f.sha256Checksum == null) {
        ignored.push([f.id, 'NO_CHECKSUM']);
        continue;
      }
      const read = BackupMeta.read(f);
      if (!read) {
        if (partial) junk.push(f);
        else ignored.push([f.id, 'NOT_A_BACKUP']);
        continue;
      }
      const { meta, mac } = read;
      if (meta.epoch > keys.epoch) {
        ignored.push([f.id, 'NEWER_EPOCH']);
        continue;
      }
      if (!(await BackupMeta.verify(this.p, keys, meta, mac))) {
        if (partial) junk.push(f);
        else ignored.push([f.id, 'MAC_INVALID']);
        continue;
      }
      if (revokedEpochRule(keys.body, meta.epoch, meta.writerKid, meta.createdAt) !== 'ACCEPT') {
        ignored.push([f.id, 'WRITER_REFUSED']);
        continue;
      }
      if (deletedAt != null && meta.createdAt <= deletedAt) {
        ignored.push([f.id, 'DELETED_BEFORE']);
        continue;
      }
      const b: DriveBackup = {
        fileId: f.id,
        name: f.name,
        createdAt: meta.createdAt,
        houses: meta.houses,
        epoch: meta.epoch,
        writerKid: meta.writerKid,
        sha256: f.sha256Checksum.toLowerCase(),
        size: f.size,
      };
      (partial ? partialValid : complete).push(b);
    }
    const seen = new Set<string>();
    const backups: DriveBackup[] = [];
    const duplicates: string[] = [];
    for (const b of complete) {
      if (!seen.has(b.sha256)) {
        seen.add(b.sha256);
        backups.push(b);
      } else duplicates.push(b.fileId);
    }
    const unfinished: DriveBackup[] = [];
    for (const b of partialValid) {
      if (!seen.has(b.sha256)) {
        seen.add(b.sha256);
        unfinished.push(b);
      } else duplicates.push(b.fileId);
    }
    for (const d of duplicates) ignored.push([d, 'DUPLICATE']);
    backups.sort(newestFirst);
    const newest = backups[0]?.createdAt ?? null;
    const missing = newestSeenAt != null && (newest == null || newest < newestSeenAt);
    return { backups, unfinished, duplicates, junk, ignored, missingNewer: missing };
  }
}

export function newestFirst(a: DriveBackup, b: DriveBackup): number {
  return b.createdAt - a.createdAt || (a.fileId < b.fileId ? -1 : a.fileId > b.fileId ? 1 : 0);
}
