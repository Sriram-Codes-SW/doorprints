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

import { equalBytes } from '../../crypto/bytes';
import { DRIVE_LAYOUT, DriveError, FOLDER_MIME } from '../drive-client';
import type { DriveClient, DriveFile } from '../drive-client';
import { downloadVerified, listAll } from '../drive-ops';

export const KIND_KEYS = 'keys';
export const KIND_CONTROL = 'control';
export const KEYS_NAME = 'keys.json';
export const CONTROL_NAME = 'doorprints.json';
export const JSON_MIME = 'application/json';

/**
 * Finding and writing the folder's small files (the key list and the control file) in Drive: by kind marker, by
 * canonical name as a fallback (an appProperties query can lag), and uploads that are read back byte for byte.
 * Split out of `DriveBackupService`; it holds no keys and no pins.
 */
export class FolderFiles {
  constructor(private readonly drive: DriveClient) {}

  async findKind(rootId: string, kind: string, knownId: string | null): Promise<DriveFile | null> {
    if (knownId != null) {
      const known = await this.fileIfPresent(knownId);
      if (known && this.isKindFile(known, rootId, kind)) return known;
    }
    const page = await this.drive.list({ parentId: rootId, appProperties: { [DRIVE_LAYOUT.kind]: kind } });
    const listed = page.files.find((f) => f.mimeType !== FOLDER_MIME);
    if (listed) return listed;
    // An appProperties query can lag behind the parent listing (docs/15 §7.1). A name match is only used to
    // refuse a folder, never to trust its bytes: the opener still checks the MAC.
    const name = kind === KIND_KEYS ? KEYS_NAME : kind === KIND_CONTROL ? CONTROL_NAME : null;
    if (name != null) {
      for (const f of await listAll(this.drive, { parentId: rootId })) {
        if (this.isKindFile(f, rootId, kind)) return f;
      }
    }
    if (page.incompleteSearch) throw new DriveError('SERVER', 0, null, 'incompleteSearch');
    return null;
  }

  async fileIfPresent(fileId: string): Promise<DriveFile | null> {
    try {
      return await this.drive.getFile(fileId);
    } catch (e) {
      if (e instanceof DriveError && e.kind === 'NOT_FOUND') return null;
      throw e;
    }
  }

  /** The kind marker, or the canonical name. Empty `parents` still counts: some answers omit them. A file in another folder does not. */
  isKindFile(f: DriveFile, rootId: string, kind: string): boolean {
    if (f.trashed || f.mimeType === FOLDER_MIME) return false;
    const name = kind === KIND_KEYS ? KEYS_NAME : kind === KIND_CONTROL ? CONTROL_NAME : null;
    const marked = f.appProperties[DRIVE_LAYOUT.kind] === kind || (name != null && f.name === name);
    if (!marked) return false;
    return f.parents.length === 0 || f.parents.includes(rootId);
  }

  async listEveryChild(parentId: string): Promise<DriveFile[]> {
    const out: DriveFile[] = [];
    let token: string | null = null;
    for (let i = 0; i < 20; i++) {
      const page = await this.drive.list({ parentId, trashed: null }, token, 100);
      if (page.incompleteSearch) throw new DriveError('SERVER', 0, null, 'incompleteSearch');
      out.push(...page.files);
      token = page.nextPageToken;
      if (token == null) return out;
    }
    throw new DriveError('SERVER', 0, null, 'incompleteSearch');
  }

  /** Uploads a small file of `kind` into the folder and reads it back: Drive must hold exactly `bytes`. */
  async uploadSmall(rootId: string, name: string, kind: string, bytes: Uint8Array): Promise<string> {
    const f = await this.drive.upload({ kind: 'new', file: { name, mimeType: JSON_MIME, parents: [rootId], appProperties: { [DRIVE_LAYOUT.kind]: kind } } }, bytes);
    await this.readBack(f.id, bytes);
    return f.id;
  }

  async writeKeys(keysId: string, bytes: Uint8Array): Promise<void> {
    await this.drive.upload({ kind: 'existing', fileId: keysId, mimeType: JSON_MIME }, bytes);
    await this.readBack(keysId, bytes);
  }

  async readBack(fileId: string, bytes: Uint8Array): Promise<void> {
    const got = await downloadVerified(this.drive, fileId);
    if (!equalBytes(got, bytes)) throw new DriveError('CORRUPT', 0, null, 'readBack');
  }
}
