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

import type { MoveIn as MoveInData, MoveInItem } from '../core/models';
import type { Lang } from '../i18n/languages';
import { cleanMoveIn, MAX_MOVE_IN_ITEMS } from '../data/records';

/**
 * Moving in (docs/11 5.24, slice 5), the pure part: the six default items and "Start moving in". The twin of Kotlin
 * `MoveIn` in android/shared, pinned by the vectors M4 and M5 (`move-in.spec.ts`, `MoveInTest`).
 */

/** One default item: a fixed id (so two devices add the same items) and its text in the four languages. */
export interface DefaultMoveInItem {
  readonly id: string;
  readonly sort: number;
  readonly text: Readonly<Record<Lang, string>>;
}

/**
 * The move-in checklist, copied from `docs/schemas/default-movein.json` (`move-in.spec.ts` reads that file and
 * compares). hi, ta and te are under review.
 */
export const DEFAULT_MOVE_IN_ITEMS: readonly DefaultMoveInItem[] = [
  {
    id: "mi_agreement",
    sort: 0,
    text: {
      en: "Rental agreement signed and registered",
      hi: "किराया अनुबंध पर हस्ताक्षर और पंजीकरण हो गया",
      ta: "வாடகை ஒப்பந்தம் கையெழுத்திடப்பட்டு பதிவு செய்யப்பட்டது",
      te: "అద్దె ఒప్పందం సంతకం చేసి నమోదు చేయబడింది",
    },
  },
  {
    id: "mi_police",
    sort: 1,
    text: {
      en: "Police verification done",
      hi: "पुलिस सत्यापन हो गया",
      ta: "காவல்துறை சரிபார்ப்பு முடிந்தது",
      te: "పోలీసు ధృవీకరణ పూర్తయింది",
    },
  },
  {
    id: "mi_id",
    sort: 2,
    text: {
      en: "ID copies exchanged",
      hi: "पहचान पत्र की प्रतियां आपस में दी गईं",
      ta: "அடையாள அட்டை நகல்கள் பரிமாறப்பட்டன",
      te: "గుర్తింపు కార్డు కాపీలు మార్చుకున్నారు",
    },
  },
  {
    id: "mi_deposit",
    sort: 3,
    text: {
      en: "Deposit receipt received",
      hi: "जमा राशि की रसीद मिली",
      ta: "வைப்புத் தொகை ரசீது பெறப்பட்டது",
      te: "డిపాజిట్ రసీదు అందింది",
    },
  },
  {
    id: "mi_meters",
    sort: 4,
    text: {
      en: "Meter readings noted (electricity, water, gas)",
      hi: "मीटर रीडिंग नोट की (बिजली, पानी, गैस)",
      ta: "மீட்டர் அளவீடுகள் குறிக்கப்பட்டன (மின்சாரம், தண்ணீர், எரிவாயு)",
      te: "మీటర్ రీడింగ్‌లు నమోదు చేశాను (విద్యుత్, నీరు, గ్యాస్)",
    },
  },
  {
    id: "mi_keys",
    sort: 5,
    text: {
      en: "Keys received",
      hi: "चाबियां मिल गईं",
      ta: "சாவிகள் பெறப்பட்டன",
      te: "తాళాలు అందాయి",
    },
  },
];

/**
 * `existing` plus the default items it does not have yet (an id already there is skipped), in the file's order, each
 * with the text in `language` and the next `sort`; stops at 30 items. Calling it again adds nothing.
 */
export function addDefaults(existing: readonly MoveInItem[], language: Lang): MoveInItem[] {
  const out = [...existing];
  const have = new Set(out.map((item) => item.id));
  let sort = out.reduce((max, item) => Math.max(max, item.sort), -1) + 1;
  for (const d of DEFAULT_MOVE_IN_ITEMS) {
    if (out.length >= MAX_MOVE_IN_ITEMS) break;
    if (have.has(d.id)) continue;
    out.push({ id: d.id, text: d.text[language] ?? d.text.en, sort: sort++ });
  }
  return out;
}

/** Items in the order the card shows them: by `sort`, then id. */
export function orderedItems(items: readonly MoveInItem[] | null | undefined): MoveInItem[] {
  return [...(items ?? [])].sort((a, b) => a.sort - b.sort || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** How many items are ticked and how many there are: the AI line and the card's summary. */
export function progress(moveIn: MoveInData | null | undefined): { done: number; total: number } {
  const items = moveIn?.items ?? [];
  return { done: items.filter((i) => i.done === true).length, total: items.length };
}

/** The same helpers as one object, the twin of Kotlin's `MoveIn`. */
export const MoveIn = { MAX_ITEMS: MAX_MOVE_IN_ITEMS, coerced: cleanMoveIn, addDefaults } as const;
