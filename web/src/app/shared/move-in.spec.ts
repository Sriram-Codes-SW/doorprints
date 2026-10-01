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
import { MAX_MOVE_IN_ITEMS, cleanMoveIn } from '../data/records';
import type { MoveInItem } from '../core/models';
import { LANGUAGES } from '../i18n/languages';
import { DEFAULT_MOVE_IN_ITEMS, MoveIn, addDefaults, orderedItems, progress } from './move-in';
import seedJson from '../../../../docs/schemas/default-movein.json';

const SEED = (seedJson as { items: { id: string; sort: number; text: Record<string, string> }[] }).items;

/** Moving in (docs/11 5.24): the vectors M4 and M5 of Kotlin `MoveInTest`, and the coercion of the stored value. */
describe('MoveIn', () => {
  it('m4_the_embedded_defaults_are_the_docs_schemas_default_movein_json_file', () => {
    expect((seedJson as { format: string }).format).toBe('doorprints-default-movein/1');
    expect(DEFAULT_MOVE_IN_ITEMS).toHaveLength(6);
    expect(DEFAULT_MOVE_IN_ITEMS.map((d) => ({ id: d.id, sort: d.sort, text: d.text }))).toEqual(SEED);
    for (const d of DEFAULT_MOVE_IN_ITEMS) {
      for (const lang of LANGUAGES) expect(d.text[lang.code].trim(), `${d.id}/${lang.code}`).not.toBe('');
    }
  });

  it('m4_start_moving_in_adds_the_six_items_in_order_and_again_adds_nothing', () => {
    const added = addDefaults([], 'en');
    expect(added.map((i) => i.id)).toEqual(SEED.map((s) => s.id));
    expect(added.map((i) => i.text)).toEqual(SEED.map((s) => s.text['en']));
    expect(added.map((i) => i.sort)).toEqual([0, 1, 2, 3, 4, 5]);
    expect(added.every((i) => i.done === undefined)).toBe(true);
    expect(addDefaults(added, 'en')).toEqual(added);
  });

  it('m4_the_items_come_in_the_apps_language_and_an_id_already_there_is_skipped', () => {
    const hindi = addDefaults([], 'hi');
    expect(hindi.map((i) => i.text)).toEqual(SEED.map((s) => s.text['hi']));
    const own: MoveInItem = { id: 'mi_keys', text: 'My own keys line', done: true, sort: 9 };
    const mixed = addDefaults([own], 'ta');
    expect(mixed).toHaveLength(6);
    expect(mixed[0]).toEqual(own);
    expect(mixed.slice(1).map((i) => i.id)).toEqual(SEED.filter((s) => s.id !== 'mi_keys').map((s) => s.id));
    expect(mixed.slice(1).map((i) => i.sort)).toEqual([10, 11, 12, 13, 14]);
  });

  it('m5_the_cap_of_30_items_stops_the_defaults', () => {
    const own = (n: number): MoveInItem[] => Array.from({ length: n }, (_, i) => ({ id: `own_${i}`, text: `Item ${i}`, sort: i }));
    expect(MoveIn.MAX_ITEMS).toBe(30);
    expect(addDefaults(own(28), 'en')).toHaveLength(30);
    expect(addDefaults(own(30), 'en')).toHaveLength(30);
    expect(addDefaults(own(25), 'en')).toHaveLength(MAX_MOVE_IN_ITEMS);
    expect(cleanMoveIn({ items: own(35) })?.items).toHaveLength(30);
  });

  it('coerces a stored value: absent when empty, bad rows skipped, done only when true, cut notes', () => {
    expect(cleanMoveIn(null)).toBeNull();
    expect(cleanMoveIn({})).toBeNull();
    expect(cleanMoveIn({ date: 0, notes: '  ', items: [] })).toBeNull();
    const out = cleanMoveIn({
      date: 1790812800000.4,
      notes: 'n'.repeat(2500),
      items: [
        { id: 'b', text: 'Second', done: false, sort: 1 },
        { id: 'a', text: 'First', done: true, sort: 0 },
        { id: 'a', text: 'Repeated id', sort: 2 },
        { id: 'bad id', text: 'Bad id', sort: 3 },
        { id: 'blank', text: '   ', sort: 4 },
        { id: 'long', text: 'x'.repeat(201), sort: 5 },
        { id: 'neg', text: 'Negative sort', sort: -1 },
      ],
    });
    expect(out?.date).toBe(1790812800000);
    expect(out?.notes).toHaveLength(2000);
    expect(out?.items).toEqual([
      { id: 'a', text: 'First', done: true, sort: 0 },
      { id: 'neg', text: 'Negative sort', sort: 0 },
      { id: 'b', text: 'Second', sort: 1 },
    ]);
    expect(Object.keys(out?.items?.[0] ?? {})).toEqual(['id', 'text', 'done', 'sort']);
    expect(Object.keys(cleanMoveIn({ items: [{ id: 'a', text: 'x', sort: 0 }], notes: 'n', date: 5 }) ?? {})).toEqual(['date', 'notes', 'items']);
  });

  it('orders the items by sort then id and counts the ticked ones', () => {
    const items: MoveInItem[] = [
      { id: 'b', text: 'B', sort: 1, done: true },
      { id: 'a', text: 'A', sort: 1 },
      { id: 'c', text: 'C', sort: 0, done: true },
    ];
    expect(orderedItems(items).map((i) => i.id)).toEqual(['c', 'a', 'b']);
    expect(progress({ items })).toEqual({ done: 2, total: 3 });
    expect(progress(null)).toEqual({ done: 0, total: 0 });
  });
});
