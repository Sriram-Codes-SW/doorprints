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
import { CHECKLIST, STATUSES, STATUS_KEY } from './models';
import { en } from '../i18n/en';
import { hi } from '../i18n/hi';
import { ta } from '../i18n/ta';
import { te } from '../i18n/te';
// The resolver's vectors and the labels they point at (docs/03 ADR-36 §18.7, S4b-BL-207); Android's
// KindLabelsVectorsTest and KindLabelsScreensTest read the same two files.
import labelVectorsJson from '../../../../docs/schemas/kinds/kind-label-vectors.json';
import kindVectorsJson from '../../../../docs/schemas/kinds/kind-vectors.json';

interface LabelCase {
  name: string;
  kind: string;
  ref: string;
  cadence?: string;
  web?: string | null;
  android?: string | null;
  visible?: boolean;
  vector?: string;
  resolved?: null;
}

const labelVectors = labelVectorsJson as unknown as { format: string; cases: LabelCase[] };
const pinned = (kindVectorsJson as unknown as { kinds: { labels: { web: Record<string, Record<string, string | null>> } }[] })
  .kinds[0].labels.web;
const DICTS: Record<string, Readonly<Record<string, string>>> = { en, hi, ta, te };
const LANGS = ['en', 'hi', 'ta', 'te'];
const resolving = labelVectors.cases.filter((c) => c.resolved !== null);
const caseOf = (ref: string) => resolving.find((c) => c.ref === ref && c.cadence === undefined)!;

/**
 * The safety test of kinds PR 3, written before anything moved (S4b-BL-207, docs/06 TC-U-209): the keys the vectors
 * pin are the ones the website reads today, and they show the pinned words in the four languages.
 */
describe('the label vectors and today’s screens (kind-label-vectors.json)', () => {
  it('is the vectors file this reader knows, and every label of kind-vectors.json has a case', () => {
    expect(labelVectors.format).toBe('doorprints-kind-label-vectors/1');
    expect(new Set(resolving.map((c) => c.vector)).size).toBe(Object.keys(pinned['en']).length);
    expect([...new Set(resolving.map((c) => c.vector!))].sort()).toEqual(Object.keys(pinned['en']).sort());
  });

  it('every pinned web key shows the words kind-vectors.json pins, in en, hi, ta and te', () => {
    for (const c of resolving) {
      for (const lang of LANGS) {
        const text = c.web === null ? null : DICTS[lang][c.web!];
        expect(text, `${lang} ${c.name} (${c.web})`).not.toBeUndefined();
        expect(text, `${lang} ${c.name} (${c.web})`).toBe(pinned[lang][c.vector!]);
      }
    }
  });

  it('the status and checklist tables the screens read are the pinned keys', () => {
    for (const s of STATUSES) expect(STATUS_KEY[s], s).toBe(caseOf(`status.${s}`).web);
    for (const item of CHECKLIST) expect(item.labelKey, item.key).toBe(caseOf(`check.${item.key}`).web);
    expect(CHECKLIST.length).toBe(resolving.filter((c) => c.ref.startsWith('check.')).length);
  });
});
