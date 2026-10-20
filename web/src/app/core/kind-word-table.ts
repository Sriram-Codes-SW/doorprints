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
import { KIND_WORD_KINDS, KIND_WORD_ROWS } from './kind-words.generated';
import { userKindFacts } from './user-kinds/user-kind-ids';

/**
 * The words of the kind files on the website (kinds PR 31, S4b-BL-316; docs/03 section 18.25): the `words` of `docs/schemas/kinds/<kind>.json`,
 * read through the generated `kind-words.generated.ts` (`tools/gen-kind-words.mjs`), so no table of words is written by hand. A kind
 * with no file of its own reads the house's column, the schema's rule ("a kind with no `words` reads the house's").
 */
const COLUMN = new Map<string, number>(KIND_WORD_KINDS.map((id, i) => [id, i]));
const ROW = new Map(KIND_WORD_ROWS.map((r) => [r[0], r]));

/** The column of `kind` in the generated rows: its own where it has a kind file with words, else the baseline's (0). */
function column(kind: string | null | undefined): number {
  return (kind ? COLUMN.get(kind) : undefined) ?? 0;
}

/**
 * The website's key of word `id` in `kind` (`null`: the website has no such word). Throws for an id the kind files do not have.
 * A kind a person added (docs/03 ADR-38) reads its own labels where it has them (the heading, the add button) and the neutral
 * word `ukind.<id>` everywhere else, so no word of a gym says *house*.
 */
export function kindWordKey(kind: string | null | undefined, id: string): TKey | null {
  const row = ROW.get(id);
  if (!row) throw new Error(`no word ${id} in the kind files`);
  const user = userKindFacts(kind);
  if (user) return row[1] === null ? null : ((user.words[id] ?? NEUTRAL[id] ?? `ukind.${id}`) as TKey);
  return ((row as readonly (string | null)[])[1 + column(kind)] ?? null) as TKey | null;
}

/** The neutral words a kind a person added reads that are shared keys already (the counts that take the kind's own noun). */
const NEUTRAL: Readonly<Record<string, string>> = { dataCount: 'data.countKind', dataPreview: 'data.previewKind' };

/** The word ids of the website, by the house's key of each (what `toolWord` turns into the kind's own for a kind a person added). */
const BY_HOUSE_KEY = new Map<string, string>(KIND_WORD_ROWS.filter((r) => r[1]).map((r) => [r[1] as string, r[0]]));

/** The key a kind a person added reads in place of the house's `key`, or undefined when `key` is no word of the kind files. */
export function userWordFor(kind: string, key: TKey): TKey | undefined {
  const id = BY_HOUSE_KEY.get(key);
  return id === undefined ? undefined : (kindWordKey(kind, id) ?? undefined);
}

/** Every word id the website has, in the kind files' order. */
export function kindWordIds(): string[] {
  return KIND_WORD_ROWS.map((r) => r[0]);
}

/** The kinds that have their own words (their kind files), the baseline first. */
export function kindWordKinds(): readonly string[] {
  return KIND_WORD_KINDS;
}

/**
 * The house key of every word and the key `kind` reads in its place, for the words where both exist: what `toolWord` turns into
 * a lookup. A word the kind does not word differently is not in the list.
 */
export function kindWordPairs(kind: string): [TKey, TKey][] {
  const col = COLUMN.get(kind);
  if (col === undefined || col === 0) return [];
  const out: [TKey, TKey][] = [];
  for (const r of KIND_WORD_ROWS) {
    const house = r[1] as TKey | null;
    const own = (r as readonly (string | null)[])[1 + col] as TKey | null;
    if (house && own && house !== own) out.push([house, own]);
  }
  return out;
}
