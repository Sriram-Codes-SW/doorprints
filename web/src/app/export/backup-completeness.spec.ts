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

import { describe, expect, it } from 'vitest';
import { DEFAULT_EXPORT_OPTIONS } from './export-model';
import { backupGaps } from './backup-completeness';
import { joinList } from '../i18n/list-join';

/** Same cases as Android's BackupCompletenessTest (:shared), so both apps name the same gaps. */
describe('backupGaps', () => {
  it('finds nothing missing with the default options', () => {
    expect(backupGaps(DEFAULT_EXPORT_OPTIONS)).toEqual([]);
  });

  it('names every narrowing option', () => {
    expect(
      backupGaps({ ...DEFAULT_EXPORT_OPTIONS, includeRejected: false, photos: 'none', includeContacts: false }),
    ).toEqual(['REJECTED_HOUSES', 'PHOTOS', 'CONTACTS']);
    expect(backupGaps({ ...DEFAULT_EXPORT_OPTIONS, photos: 'shortlisted' })).toEqual(['PHOTOS_NOT_SHORTLISTED']);
    expect(backupGaps({ ...DEFAULT_EXPORT_OPTIONS, scope: 'selected' })).toEqual(['HOUSES_NOT_SELECTED']);
  });

  it('does not list what a shortlist-only backup already leaves out', () => {
    expect(
      backupGaps({ ...DEFAULT_EXPORT_OPTIONS, scope: 'shortlisted', includeRejected: false, photos: 'shortlisted' }),
    ).toEqual(['HOUSES_NOT_SHORTLISTED']);
  });
});

describe('joinList', () => {
  const join = (...items: string[]) =>
    joinList(
      items,
      (a, b) => `${a} and ${b}`,
      (a, b, c) => `${a}, ${b} and ${c}`,
      (a, b) => `${a}, ${b}`,
    );

  it('uses the language pattern for every length, never dropping an item', () => {
    expect(join()).toBe('');
    expect(join('a')).toBe('a');
    expect(join('a', 'b')).toBe('a and b');
    expect(join('a', 'b', 'c')).toBe('a, b and c');
    expect(join('a', 'b', 'c', 'd')).toBe('a, b, c and d');
  });
});
