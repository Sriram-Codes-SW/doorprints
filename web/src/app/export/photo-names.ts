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

/**
 * Where a photo lives inside an export.
 *
 * Shared with the Android exporter (`ExportPhoto.fileName` and `BackupFormat.photoEntry` in
 * `android/shared/.../export/`): the tables show the bare **file name**, and the backup ZIP stores the bytes under
 * `photos/<file name>`. Photos are always re-encoded as JPEG before they are stored, so the extension is fixed.
 */
export const PHOTO_DIR = 'photos/';

/** `<id>.jpg` — what the CSV, XLSX and Markdown copies print, and what a backup row carries. */
export function photoFileName(photoId: string): string {
  return `${photoId}.jpg`;
}

/** `photos/<id>.jpg` — the entry name inside a backup ZIP. */
export function photoEntry(fileName: string): string {
  return PHOTO_DIR + fileName;
}
