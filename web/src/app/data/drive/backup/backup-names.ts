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

import { civil } from './backup-retention';

/** The file names of docs/15 §5.1 (local time; the names are for the person, the app goes by the metadata). */
export const BACKUP_PARTIAL_PREFIX = 'partial-';
export const BACKUP_MIME = 'application/octet-stream';

/** `Doorprints-backup-2026-10-02-0930.dpx` for `createdAt` at `utcOffsetMinutes`. */
export function backupName(createdAt: number, utcOffsetMinutes: number): string {
  const local = createdAt + utcOffsetMinutes * 60_000;
  const day = Math.floor(local / 86_400_000);
  const [y, m, d] = civil(day);
  const minutes = Math.floor((local - day * 86_400_000) / 60_000);
  const two = (v: number) => String(v).padStart(2, '0');
  return `Doorprints-backup-${y}-${two(m)}-${two(d)}-${two(Math.floor(minutes / 60))}${two(minutes % 60)}.dpx`;
}
