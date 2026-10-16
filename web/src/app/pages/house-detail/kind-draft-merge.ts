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
import type { HouseDraft } from '../../core/ai.service';
import type { KindExtra, KindFields } from '../../core/kind-fields';
import type { HouseDto } from '../../core/models';
import type { Msg, TranslationService } from '../../i18n/translation.service';

/** A kind's extra the fill did not write because the form already holds another value there. */
export interface KeptExtra {
  field: KindExtra;
  incoming: unknown;
}

/** What a listing fill writes into a kind's extras: the new `extrasOther` (null: nothing to write) and what it kept. */
export interface ExtrasFill {
  extrasOther: HouseDto['extrasOther'] | null;
  filled: string[];
  kept: KeptExtra[];
}

const isEmpty = (v: unknown) => v === null || v === undefined || (typeof v === 'string' && v.trim() === '');

/**
 * Kinds PR 7 (S4b-BL-211): "Fill in from listing text" for a kind's typed extras, by the house's rule (AI-004): only an
 * **empty** extra is filled; one the person set keeps its value and the listing's is reported in `kept`. Only the extras
 * the kind has with no house field (`fields.others()`), so a draft can write nothing else. A house's draft has no extras,
 * so for a house this writes nothing.
 */
export function mergeKindExtras(current: HouseDto, draft: HouseDraft, fields: KindFields): ExtrasFill {
  const out: ExtrasFill = { extrasOther: null, filled: [], kept: [] };
  if (!draft.extras) return out;
  const next: Record<string, unknown> = { ...(current.extrasOther ?? {}) };
  for (const field of fields.others()) {
    const incoming = draft.extras[field.id];
    if (isEmpty(incoming)) continue;
    const now = current.extrasOther?.[field.id];
    if (isEmpty(now)) {
      next[field.id] = incoming;
      out.filled.push(field.id);
    } else if (JSON.stringify(now) !== JSON.stringify(incoming)) {
      out.kept.push({ field, incoming });
    }
  }
  if (out.filled.length) out.extrasOther = next as HouseDto['extrasOther'];
  return out;
}

/** A listing's value of an extra as the form would show it, for "Kept your …; the listing says …". */
export function shownExtra(field: KindExtra, value: unknown, i18n: Pick<TranslationService, 'price'>): Msg | string {
  if (field.type === 'enum') {
    const option = field.options?.find((o) => o.id === value);
    return option ? { key: option.label } : String(value);
  }
  if (field.type === 'bool') return { key: value ? 'common.yes' : 'common.no' };
  if (field.type === 'money') return i18n.price((value as { amount?: number })?.amount ?? null, null);
  return String(value);
}
