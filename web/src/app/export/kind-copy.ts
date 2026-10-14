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

import type { Dict, TKey } from '../i18n/en';
import type { HouseDto } from '../core/models';
import type { KindFields } from '../core/kind-fields';
import { toolWord } from '../core/space-tool-words';
import { kindValues, type KindValue } from '../shared/kind-values';
import { tr } from './deterministic';
import { cellCount, cellMoney, cellText, display, type Cell } from './export-rows';
import type { ExportStrings } from './export-strings';

/**
 * A candidate's typed extras in the copies (kinds PR 6, S4b-BL-210; docs/03 ADR-36 §18.10), through its kind, never a
 * branch per kind: each extra the kind has no `was` for, labelled with the kind's key in the copy's language and written
 * by its type (money as rupees in a readable copy and a number in a table, a switch as the copy's yes or no, an option
 * by its label, a number's digits, a text as typed). A house has no such extra, so its copies are as they were
 * (`house-pins.spec.ts`); a school's are pinned by `docs/schemas/kinds/kind-search-vectors.json`.
 */

/** The kinds a copy reads its candidates with: the kind of each (the house unless the flag allows it) and the copy's words. */
export interface CopyKinds {
  readonly fieldsOf: (kind: string | null | undefined) => KindFields;
  /** The app's dictionary in the copy's language. */
  readonly dict: Dict;
}

/** One extra of a candidate in a copy: its column, its label, its table cell and its readable text. */
export interface ExportExtra {
  /** `<kind>.<extra id>`: two kinds may give one id two meanings. */
  readonly key: string;
  /** The kind section the extra belongs to (`details`, `cost`): a readable copy writes it under that section's heading. */
  readonly section: string;
  readonly label: string;
  readonly cell: Cell;
  readonly text: string;
}

/** One column of the Houses table for an extra: every extra of each kind the copy has a candidate of. */
export interface ExportExtraColumn {
  readonly key: string;
  readonly label: string;
}

/** The extras `h` holds, in the kind's order. */
export function extrasOf(h: Pick<HouseDto, 'kind' | 'extrasOther'>, kinds: CopyKinds, s: ExportStrings): ExportExtra[] {
  const fields = kinds.fieldsOf(h.kind);
  return kindValues(fields, h).map((v) => {
    const cell = cellOf(v, kinds.dict, s);
    return { key: `${fields.kind}.${v.field.id}`, section: v.field.section, label: tr(kinds.dict, fields.label(v.field)), cell, text: display(cell, s) };
  });
}

/** The columns: each kind in the order its first candidate comes in the copy, each with all its other extras. */
export function extraColumnsOf(houses: readonly Pick<HouseDto, 'kind'>[], kinds: CopyKinds): ExportExtraColumn[] {
  const seen = new Set<string>();
  const out: ExportExtraColumn[] = [];
  for (const h of houses) {
    const fields = kinds.fieldsOf(h.kind);
    if (seen.has(fields.kind)) continue;
    seen.add(fields.kind);
    for (const f of fields.others()) out.push({ key: `${fields.kind}.${f.id}`, label: tr(kinds.dict, fields.label(f)) });
  }
  return out;
}

/**
 * The kind a copy speaks in (S4b-BL-266): the kind of a copy of one space, or of candidates that are all of one kind, when
 * that kind is not the house; `undefined` for a copy of houses or of several kinds, which is written as it always was
 * (`house-pins.spec.ts`). Its words and hidden places shape the copy: see {@link copyWord}, {@link copyShows} and
 * {@link copyField}.
 */
export function copyVoice(chosen: readonly Pick<HouseDto, 'kind'>[], kinds: CopyKinds, space?: string): KindFields | undefined {
  const own = space ? kinds.fieldsOf(space) : undefined;
  const all = new Set(chosen.map((h) => kinds.fieldsOf(h.kind)));
  const voice = own ?? (all.size === 1 ? [...all][0] : undefined);
  return voice && voice.kind !== 'house' ? voice : undefined;
}

/** A word of the app's dictionary in the copy's voice: the kind's own where it has one, else the house's key. */
export function copyWord(voice: KindFields | undefined, key: TKey): TKey {
  return toolWord(voice?.kind, key);
}

/** Whether the copy shows a place of its kind (`field.money`, `section.checklist`): every place, in a copy without a voice. */
export function copyShows(voice: KindFields | undefined, ref: string): boolean {
  return !voice || voice.labels.isVisible(ref);
}

/**
 * The key of a field or section label in the copy: the kind's own through `KindLabels` (a school's *Website* and *Fees*),
 * the hand-written `house` key in a copy without a voice, which `house-pins.spec.ts` holds word for word.
 */
export function copyField(voice: KindFields | undefined, ref: string, house: TKey): TKey {
  return (voice && voice.labels.resolve(ref)?.key) || house;
}

function cellOf(v: KindValue, dict: Dict, s: ExportStrings): Cell {
  switch (v.type) {
    case 'money':
      return cellMoney(Math.round(v.amount));
    case 'number':
      return cellCount(Math.round(v.value));
    case 'bool':
      return cellText(s.get(v.value ? 'yes' : 'no'));
    case 'enum':
      return cellText(tr(dict, v.label));
    default:
      return cellText(v.value);
  }
}
