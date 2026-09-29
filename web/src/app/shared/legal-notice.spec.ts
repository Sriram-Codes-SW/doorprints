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
import { en } from '../i18n/en';
import { Lang } from '../i18n/languages';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { LEGAL_NOTICE } from './legal-notice';

describe('LEGAL_NOTICE (AGPL notices, S4b-BL-65)', () => {
  it('names the copyright holder in English and points to the source and the AGPL', () => {
    expect(LEGAL_NOTICE.copyright).toMatch(/^Copyright 2026 /);
    expect(LEGAL_NOTICE.sourceUrl).toBe('https://github.com/Sriram-Codes-SW/doorprints');
    expect(LEGAL_NOTICE.licenceUrl).toBe('https://www.gnu.org/licenses/agpl-3.0.html');
  });

  it('says in English that it is free software under the AGPL version 3, with no warranty', () => {
    expect(en['data.aboutLicence']).toContain('GNU Affero General Public License, version 3');
    expect(en['data.aboutWarranty']).toContain('NO WARRANTY');
  });

  it('keeps the licence name in every language, so it can be looked up', () => {
    for (const lang of Object.keys(DICTIONARIES) as Lang[]) {
      const d = DICTIONARIES[lang] as Record<string, string>;
      expect(d['data.aboutLicence'], lang).toContain('GNU Affero General Public License');
      expect(d['data.aboutLicenceAria'], lang).toContain('GNU Affero General Public License');
    }
  });
});
