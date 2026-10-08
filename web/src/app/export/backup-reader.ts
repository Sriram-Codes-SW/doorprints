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

import { BACKUP_FORMATS_READ, BACKUP_LIMITS, DATA_ENTRY, MANIFEST_ENTRY } from './backup-export';
import type { BackupData, BackupManifest } from './backup-export';
import { checkData } from './backup-check';
import type { BackupProblem } from './backup-check';
import { PHOTO_DIR } from './photo-names';
import { sha256Hex } from './sha256';
import { bytesAt, ZipFile } from './zip-read';

/**
 * A `doorprints-backup` file read on the website (S4b-BL-75): the ZIP the apps write, or a bare `data.json` (the server's
 * `GET /api/export`). The same checks, in the same order and with the same answers, as the common `BackupArchive` (the
 * iPhone) and Android's `BackupReader` (docs/schemas/README.md sections 2, 6 and 7): the first bytes, not the name, tell
 * the two apart; then the entry count, every entry's path (zip slip), the declared sizes and ratio, the manifest, the
 * SHA-256 of `data.json` and the rows' shapes, all before anything is written. A photo's SHA-256 is checked when it is
 * read; a running total caps the whole import at 1 GiB. `docs/schemas/import-vectors.json` pins the answers.
 */
export interface BackupArchive {
  readonly manifest: BackupManifest | null;
  readonly data: BackupData;
  /** Names of the `photos/…` entries actually present in the archive. */
  readonly photoEntries: ReadonlySet<string>;
  /** A photo's verified bytes, or `null` (missing, too large, or failing the manifest's SHA-256): skipped, never fatal. */
  photoBytes(entry: string): Promise<Uint8Array | null>;
}

/** The result of opening a backup file: the checked archive, or the first problem found. */
export type ArchiveOpen = { ok: true; archive: BackupArchive } | { ok: false; problem: BackupProblem };

const MAX_PHOTO_BYTES = 32 * 1024 * 1024;
const MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
const HEAD_BYTES = 64;
const FORMAT_FAMILY = 'doorprints-backup/';

/** Opens `source`; any failure while reading it is `NOT_A_BACKUP`, never a crash. */
export async function openBackup(source: Blob): Promise<ArchiveOpen> {
  try {
    return await openChecked(source);
  } catch {
    return { ok: false, problem: 'NOT_A_BACKUP' };
  }
}

/**
 * Tells a ZIP from a bare `data.json` by the first bytes, then runs the checks in order (entry count, paths, sizes and ratio, manifest, the SHA-256 of `data.json`, the rows) before any data is returned.
 */
async function openChecked(source: Blob): Promise<ArchiveOpen> {
  const head = (await bytesAt(source, 0, Math.min(source.size, HEAD_BYTES))) ?? new Uint8Array(0);
  if (!isZip(head)) return isJsonObject(head) ? openBareData(source) : { ok: false, problem: 'NOT_A_BACKUP' };
  const opened = await ZipFile.open(source, BACKUP_LIMITS.maxEntries);
  if (!opened.ok) return { ok: false, problem: opened.problem === 'TOO_MANY_ENTRIES' ? 'TOO_MANY_ENTRIES' : 'NOT_A_BACKUP' };
  const zip = opened.zip;
  // First filter only: these numbers are the file author's claim; readAtMost measures.
  let declared = 0;
  for (const entry of zip.entries) {
    if (isSuspiciousPath(entry.name.replace(/\/$/, ''))) return { ok: false, problem: 'SUSPICIOUS_PATH' };
    declared += entry.size;
    if (declared > BACKUP_LIMITS.maxUncompressedBytes) return { ok: false, problem: 'TOO_LARGE' };
    if (entry.compressedSize > 0 && Math.floor(entry.size / entry.compressedSize) > BACKUP_LIMITS.maxCompressionRatio) {
      return { ok: false, problem: 'TOO_LARGE' };
    }
  }
  const dataEntry = zip.entry(DATA_ENTRY);
  if (!dataEntry) return { ok: false, problem: 'NOT_A_BACKUP' };
  if (dataEntry.size > BACKUP_LIMITS.maxDataJsonBytes) return { ok: false, problem: 'TOO_LARGE' };
  const dataBytes = await zip.readAtMost(dataEntry, BACKUP_LIMITS.maxDataJsonBytes);
  if (!dataBytes) return { ok: false, problem: 'TOO_LARGE' };
  const manifestEntry = zip.entry(MANIFEST_ENTRY);
  if (!manifestEntry) return { ok: false, problem: 'NOT_A_BACKUP' };
  const manifestBytes = await zip.readAtMost(manifestEntry, MAX_MANIFEST_BYTES);
  if (!manifestBytes) return { ok: false, problem: 'TOO_LARGE' };
  const manifest = readManifest(manifestBytes);
  if (!manifest) return { ok: false, problem: 'NOT_A_BACKUP' };
  const manifestProblem = checkManifest(manifest);
  if (manifestProblem) return { ok: false, problem: manifestProblem };
  const listed = manifest.files.find((f) => f.path === DATA_ENTRY);
  if (listed && sha256Hex(dataBytes) !== listed.sha256.toLowerCase()) return { ok: false, problem: 'CHECKSUM_MISMATCH' };
  let raw: unknown;
  try {
    raw = JSON.parse(new TextDecoder('utf-8').decode(dataBytes));
  } catch {
    return { ok: false, problem: 'BROKEN_DATA' };
  }
  const checked = checkData(raw);
  if (!checked.ok) return checked;
  const photos = new Set(zip.entries.map((e) => e.name).filter(isPhotoEntry));
  const hashes = new Map(manifest.files.map((f) => [f.path, f.sha256.toLowerCase()]));
  let decompressed = dataBytes.length + manifestBytes.length;
  return {
    ok: true,
    archive: {
      manifest,
      data: checked.data,
      photoEntries: photos,
      async photoBytes(entry: string): Promise<Uint8Array | null> {
        if (!isPhotoEntry(entry)) return null;
        const info = zip.entry(entry);
        if (!info) return null;
        const budget = Math.min(MAX_PHOTO_BYTES, BACKUP_LIMITS.maxUncompressedBytes - decompressed);
        if (budget <= 0) return null;
        const bytes = await zip.readAtMost(info, budget).catch(() => null);
        if (!bytes) return null;
        decompressed += bytes.length;
        const expected = hashes.get(entry);
        return expected === undefined || sha256Hex(bytes) === expected ? bytes : null;
      },
    },
  };
}

/** A bare `data.json`: the size first, then the format id, then the shape. */
async function openBareData(source: Blob): Promise<ArchiveOpen> {
  if (source.size > BACKUP_LIMITS.maxDataJsonBytes) return { ok: false, problem: 'TOO_LARGE' };
  if (source.size === 0) return { ok: false, problem: 'NOT_A_BACKUP' };
  let text: string;
  try {
    text = new TextDecoder('utf-8').decode(new Uint8Array(await source.arrayBuffer())).replace(/^﻿/, '');
  } catch {
    return { ok: false, problem: 'READ_FAILED' };
  }
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    return { ok: false, problem: 'NOT_A_BACKUP' };
  }
  if (typeof root !== 'object' || root === null || Array.isArray(root)) return { ok: false, problem: 'NOT_A_BACKUP' };
  const format = (root as Record<string, unknown>)['format'];
  if (typeof format !== 'string') return { ok: false, problem: 'NOT_A_BACKUP' };
  if (!BACKUP_FORMATS_READ.includes(format)) {
    return { ok: false, problem: format.startsWith(FORMAT_FAMILY) ? 'UNSUPPORTED_VERSION' : 'NOT_A_BACKUP' };
  }
  const checked = checkData(root);
  if (!checked.ok) return checked;
  return { ok: true, archive: { manifest: null, data: checked.data, photoEntries: new Set(), photoBytes: async () => null } };
}

/** The manifest as Kotlin's decoder takes it, or `null`: `format`, `createdAt` and the three counts must be there. */
function readManifest(bytes: Uint8Array): BackupManifest | null {
  try {
    const m = JSON.parse(new TextDecoder('utf-8').decode(bytes)) as Record<string, unknown>;
    if (typeof m !== 'object' || m === null || typeof m['format'] !== 'string' || typeof m['createdAt'] !== 'string') return null;
    const counts = m['counts'] as Record<string, unknown> | null;
    if (typeof counts !== 'object' || counts === null) return null;
    for (const k of ['houses', 'visits', 'photos']) if (!Number.isInteger(counts[k])) return null;
    for (const v of Object.values(counts)) if (v != null && !Number.isInteger(v)) return null;
    const files = m['files'] ?? [];
    if (!Array.isArray(files)) return null;
    for (const f of files as Record<string, unknown>[]) {
      if (typeof f !== 'object' || f === null || typeof f['path'] !== 'string' || typeof f['sha256'] !== 'string' || !Number.isInteger(f['sizeBytes'])) return null;
    }
    for (const k of ['sharedSince', 'sharedTo']) if (m[k] != null && typeof m[k] !== 'string') return null;
    return { ...(m as unknown as BackupManifest), files: files as BackupManifest['files'] };
  } catch {
    return null;
  }
}

function checkManifest(m: BackupManifest): BackupProblem | null {
  if (!BACKUP_FORMATS_READ.includes(m.format)) return 'UNSUPPORTED_VERSION';
  if (Object.values(m.counts).some((v) => typeof v === 'number' && v < 0)) return 'BROKEN_DATA';
  return null;
}

/** An update file's manifest (docs/11 5.28): only then are its deletions applied (S4b-BL-82). Kotlin `ImportPlan.isUpdate`. */
export function isUpdate(manifest: BackupManifest | null): boolean {
  return manifest?.sharedSince != null;
}

/** A ZIP entry name that must never be written (zip slip): Kotlin `BackupValidation.isSuspiciousPath`. */
export function isSuspiciousPath(name: string): boolean {
  if (name === '' || name.startsWith('/') || name.startsWith('\\') || name.includes('\\')) return true;
  if (name.length >= 2 && name[1] === ':') return true;
  return name.split('/').some((s) => s === '..' || s === '.');
}

/** Exactly `photos/<name>`, one level deep. */
export function isPhotoEntry(name: string): boolean {
  return name.startsWith(PHOTO_DIR) && name.length > PHOTO_DIR.length && !name.slice(PHOTO_DIR.length).includes('/') && !isSuspiciousPath(name);
}

function isZip(head: Uint8Array): boolean {
  return head.length >= 4 && head[0] === 0x50 && head[1] === 0x4b && ((head[2] === 3 && head[3] === 4) || (head[2] === 5 && head[3] === 6));
}

/** `{` after an optional UTF-8 byte-order mark and whitespace. */
function isJsonObject(head: Uint8Array): boolean {
  let i = head.length >= 3 && head[0] === 0xef && head[1] === 0xbb && head[2] === 0xbf ? 3 : 0;
  while (i < head.length && (head[i] === 0x20 || head[i] === 0x09 || head[i] === 0x0d || head[i] === 0x0a)) i++;
  return i < head.length && head[i] === 0x7b;
}
