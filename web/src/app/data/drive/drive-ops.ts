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

import { sha256Hex } from '../../export/sha256';
import {
  CHUNK_UNIT,
  DEFAULT_CHUNK,
  DRIVE_LAYOUT,
  DriveError,
  FOLDER_MIME,
  MULTIPART_LIMIT,
  corrupt,
  isFolder,
} from './drive-client';
import type {
  DeleteReport,
  DriveClient,
  DriveFile,
  DriveQuery,
  FolderSpec,
  UploadProgress,
  UploadSession,
  UploadTarget,
} from './drive-client';

/*
 * What docs/15 builds from the DriveClient calls, written once over the interface (Kotlin `DriveOps.kt`, the same
 * names and rules), so the fetch client and the fake behave the same.
 */

/** Every page of `query`. */
export async function listAll(drive: DriveClient, query: DriveQuery, pageSize = 100, maxPages = 1000): Promise<DriveFile[]> {
  const out: DriveFile[] = [];
  let token: string | null = null;
  for (let i = 0; i < maxPages; i++) {
    const page = await drive.list(query, token, pageSize);
    out.push(...page.files);
    token = page.nextPageToken;
    if (token == null) return out;
  }
  throw corrupt('tooManyPages');
}

/**
 * The folder of `spec` under `parentId` (null: anywhere), by its app properties, the oldest when there are several;
 * `knownId` is read first. **Never re-created silently** (docs/15 §3.4): `create` false gives null for a folder gone.
 */
export async function ensureFolder(
  drive: DriveClient,
  spec: FolderSpec,
  parentId: string | null,
  create: boolean,
  knownId: string | null = null,
): Promise<DriveFile | null> {
  if (knownId != null) {
    let known: DriveFile | null = null;
    try {
      known = await drive.getFile(knownId);
    } catch (e) {
      if (!(e instanceof DriveError) || e.kind !== 'NOT_FOUND') throw e;
    }
    if (known && isFolder(known) && !known.trashed &&
      Object.entries(spec.appProperties).every(([k, v]) => known.appProperties[k] === v)) {
      return known;
    }
  }
  const page = await drive.list({ parentId, appProperties: spec.appProperties, mimeType: FOLDER_MIME });
  const found = page.files[0] ?? null;
  if (found || !create) return found;
  return drive.createFile({
    name: spec.name,
    mimeType: FOLDER_MIME,
    parents: parentId == null ? [] : [parentId],
    appProperties: spec.appProperties,
  });
}

export interface ResumableOptions {
  chunkSize?: number;
  session?: UploadSession | null;
  onSession?: (session: UploadSession) => void;
  onProgress?: (sent: number) => void;
  shouldStop?: () => boolean;
}

/**
 * A resumable upload of `size` bytes read by `read(offset, length)` (Kotlin `uploadResumable`): after a dropped
 * connection, a 5xx or a rate limit it waits by the client's retry rule, asks Drive how far it got and goes on; a session
 * Drive forgot (404) starts again once.
 */
export async function uploadResumable(
  drive: DriveClient,
  target: UploadTarget,
  size: number,
  read: (offset: number, length: number) => Promise<Uint8Array> | Uint8Array,
  options: ResumableOptions = {},
): Promise<DriveFile> {
  const chunkSize = options.chunkSize ?? DEFAULT_CHUNK;
  if (size <= 0) throw new Error('a resumable upload needs content');
  if (chunkSize <= 0 || chunkSize % CHUNK_UNIT !== 0) throw new Error('chunks are multiples of 256 KiB');
  let current = options.session ?? (await drive.startUpload(target, size));
  if (!options.session) options.onSession?.(current);
  let offset = 0;
  let needStatus = options.session != null;
  let restarted = false;
  let attempt = 0;
  for (;;) {
    if (options.shouldStop?.()) throw new DriveError('CANCELLED', 0, null, 'stopped');
    let progress: UploadProgress;
    try {
      if (needStatus) {
        progress = await drive.uploadStatus(current);
      } else {
        const length = Math.min(chunkSize, size - offset);
        progress = await drive.uploadChunk(current, offset, await read(offset, length));
      }
    } catch (e) {
      if (!(e instanceof DriveError)) throw e;
      if (e.kind === 'NOT_FOUND' && !restarted) {
        restarted = true;
        current = await drive.startUpload(target, size);
        options.onSession?.(current);
        offset = 0;
        needStatus = false;
        continue;
      }
      attempt++;
      await drive.retry.pause(attempt, e);
      needStatus = true;
      continue;
    }
    needStatus = false;
    if (progress.kind === 'complete') {
      options.onProgress?.(size);
      return progress.file;
    }
    if (progress.received > offset) attempt = 0;
    offset = progress.received;
    options.onProgress?.(offset);
  }
}

/** Multipart when small enough, else resumable. */
export function uploadBytes(drive: DriveClient, target: UploadTarget, content: Uint8Array, chunkSize = DEFAULT_CHUNK): Promise<DriveFile> {
  if (content.length <= MULTIPART_LIMIT) return drive.upload(target, content);
  return uploadResumable(drive, target, content.length, (offset, length) => content.slice(offset, offset + length), { chunkSize });
}

/** Drive's `sha256Checksum` of `fileId` is `expectedSha256`; a late one is asked again by the backoff; else CORRUPT. */
export async function confirmChecksum(
  drive: DriveClient,
  fileId: string,
  expectedSha256: string,
  uploaded: DriveFile | null = null,
): Promise<DriveFile> {
  let file = uploaded?.sha256Checksum != null ? uploaded : await drive.getFile(fileId);
  let attempt = 1;
  while (file.sha256Checksum == null && attempt < drive.retry.maxAttempts) {
    await drive.retry.backoff(attempt++);
    file = await drive.getFile(fileId);
  }
  if (file.sha256Checksum == null) throw corrupt('noChecksum');
  if (file.sha256Checksum.toLowerCase() !== expectedSha256.toLowerCase()) throw corrupt('checksumMismatch');
  return file;
}

/** The checksum checked, then one update setting `state=complete` and the real name (docs/15 §1.4 item 2). */
export async function markComplete(
  drive: DriveClient,
  fileId: string,
  expectedSha256: string,
  finalName: string | null = null,
  uploaded: DriveFile | null = null,
): Promise<DriveFile> {
  await confirmChecksum(drive, fileId, expectedSha256, uploaded);
  return drive.updateMetadata(fileId, {
    name: finalName,
    appProperties: { [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.stateComplete },
  });
}

/** The content, checked against Drive's checksum and `expectedSha256` when given (every Drive file is untrusted). */
export async function downloadVerified(drive: DriveClient, fileId: string, expectedSha256: string | null = null): Promise<Uint8Array> {
  const file = await drive.getFile(fileId);
  const bytes = await drive.download(fileId);
  const actual = sha256Hex(bytes);
  if ((file.sha256Checksum != null && file.sha256Checksum.toLowerCase() !== actual) ||
    (expectedSha256 != null && expectedSha256.toLowerCase() !== actual)) {
    throw corrupt('checksumMismatch');
  }
  return bytes;
}

/** The content in ranges of `chunkSize`, handed to `sink` in order. */
export async function downloadTo(
  drive: DriveClient,
  fileId: string,
  size: number,
  sink: (bytes: Uint8Array) => Promise<void> | void,
  chunkSize = DEFAULT_CHUNK,
): Promise<void> {
  let offset = 0;
  while (offset < size) {
    const bytes = await drive.download(fileId, { first: offset, last: Math.min(size, offset + chunkSize) - 1 });
    if (bytes.length === 0) throw corrupt('shortRange');
    await sink(bytes);
    offset += bytes.length;
  }
}

/** Deletes for good, one by one in order; stops at the first failure and reports what is left (docs/15 §3.3). */
export async function deleteAll(drive: DriveClient, fileIds: readonly string[]): Promise<DeleteReport> {
  const deleted: string[] = [];
  for (let i = 0; i < fileIds.length; i++) {
    try {
      await drive.delete(fileIds[i]);
    } catch (e) {
      if (!(e instanceof DriveError)) throw e;
      return { deleted, left: fileIds.slice(i), error: e, failed: [] };
    }
    deleted.push(fileIds[i]);
  }
  return { deleted, left: [], error: null, failed: [] };
}
