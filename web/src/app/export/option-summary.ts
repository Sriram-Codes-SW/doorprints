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
 * The "what is in this file" list that appears on the cover of the HTML copy, at the top of the Markdown file and
 * in the backup manifest. One translation key per chosen option, always in the same order, so the cover of two
 * exports with the same options is identical.
 */
export function optionSummaryKeys(options: ExportOptions): TKey[] {
  const keys: TKey[] = [];
  keys.push(
    options.scope === 'all'
      ? 'exp.scopeAll'
      : options.scope === 'shortlisted'
        ? 'exp.scopeShortlisted'
        : 'exp.scopeSelected',
  );
  keys.push(options.includeRejected ? 'exp.rejectedIncluded' : 'exp.rejectedLeftOut');
  keys.push(
    options.photos === 'all'
      ? 'exp.photosAll'
      : options.photos === 'shortlisted'
        ? 'exp.photosShortlisted'
        : 'exp.photosNone',
  );
  keys.push(options.includeContacts ? 'exp.contactsIncluded' : 'exp.contactsLeftOut');
  return keys;
}
