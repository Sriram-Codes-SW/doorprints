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

import type { TKey } from '../i18n/en';
import type { ExportOptions } from './export-model';

/**
 * What a "Full backup" made with some options leaves out — the web copy of Android's `BackupCompleteness`
 * (`:shared`, UX review round 11), gap for gap and in the same order, so both apps name the same gaps.
 *
 * The scope, rejected, photos and contacts options apply to the backup as to every other format, and a backup is
 * the one file a user keeps in order to restore. A "Full backup" that quietly restores only part of their data puts
 * it at risk, so the export card names every gap before the file is made, and the result says "partial backup".
 * Gaps that another choice already covers are not listed twice: with "Shortlisted only" the rejected houses and the
 * photos of houses that are not shortlisted are already part of `HOUSES_NOT_SHORTLISTED`.
 */
export type BackupGap =
  | 'HOUSES_NOT_SHORTLISTED'
  | 'HOUSES_NOT_SELECTED'
  | 'REJECTED_HOUSES'
  | 'PHOTOS_NOT_SHORTLISTED'
  | 'PHOTOS'
  | 'CONTACTS';

/** The translation key that words each gap on the export card. */
export const BACKUP_GAP_KEY: Readonly<Record<BackupGap, TKey>> = {
  HOUSES_NOT_SHORTLISTED: 'data.gapNotShortlisted',
  HOUSES_NOT_SELECTED: 'data.gapNotSelected',
  REJECTED_HOUSES: 'data.gapRejected',
  PHOTOS_NOT_SHORTLISTED: 'data.gapPhotosNotShortlisted',
  PHOTOS: 'data.gapPhotos',
  CONTACTS: 'data.gapContacts',
};

type GapOptions = Pick<ExportOptions, 'scope' | 'includeRejected' | 'photos' | 'includeContacts'>;

/**
 * The gaps a "Full backup" made with [options] would have, in the order the export card lists them. An option another choice already covers is not listed twice.
 */
export function backupGaps(options: GapOptions): BackupGap[] {
  const gaps: BackupGap[] = [];
  if (options.scope === 'all') {
    if (!options.includeRejected) gaps.push('REJECTED_HOUSES');
  } else if (options.scope === 'shortlisted') {
    gaps.push('HOUSES_NOT_SHORTLISTED');
  } else {
    gaps.push('HOUSES_NOT_SELECTED');
  }
  if (options.photos === 'shortlisted') {
    // Every house in a shortlist-only file is shortlisted, so all of their photos are in it.
    if (options.scope !== 'shortlisted') gaps.push('PHOTOS_NOT_SHORTLISTED');
  } else if (options.photos === 'none') {
    gaps.push('PHOTOS');
  }
  if (!options.includeContacts) gaps.push('CONTACTS');
  return gaps;
}
