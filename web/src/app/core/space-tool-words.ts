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
import { kindWordKinds, kindWordPairs, userWordFor } from './kind-word-table';
import { isUserKindId } from './user-kinds/user-kind-ids';

/*
 * The words of Compare, Ask, Plan, the copies and the share target in a space other than the house (kinds PR B,
 * S4b-BL-279; docs/03 §18.13), and since kinds PR 16 (S4b-BL-256, S4b-BL-259, S4b-BL-266, S4b-BL-277; §18.9) the house's
 * words left on a school's page: tab titles, the name's placeholder, the walk check, the Areas and Places pages, the
 * readable copy's cover. A page asks for its house key and gets the kind's own where the kind has one, so the
 * house's words are exactly the keys they were: `toolWord('house', 'compare.title')` is `'compare.title'`. The space's
 * list and map words are `core/space-words.ts` (S4b-BL-277), the kind files' `words` (S4b-BL-278).
 *
 * S4b-BL-316 and kinds PR 31 (docs/03 section 18.25): a kind's table is not written by hand any more. Every word has an id in the kind
 * files' `words` (`house.json`, `school.json`), and the table of a kind is the pairs "the house's key, the key this kind reads"
 * of the generated `kind-words.generated.ts` (`tools/gen-kind-words.mjs`); `kind-words.spec.ts` holds the file, the generated
 * rows and the vectors to one another. Only the table of the words of a thing that acts on every space (`*`) is a table here,
 * because it belongs to no kind.
 */

/**
 * The words of a thing that acts on every space (S4b-BL-291): the Full backup's options and gaps and the import preview, read
 * while two or more kinds are on, under the kind id `*`. With one kind they stay the house's.
 */
const ANY: Readonly<Partial<Record<TKey, TKey>>> = {
  'data.scope': 'data.scopeAny',
  'data.scopeAll': 'data.scopeAllAny',
  'data.includeRejected': 'data.includeRejectedAny',
  'data.photosShortlisted': 'data.photosShortlistedAny',
  'data.noShortlisted': 'data.noShortlistedAny',
  'data.noneMatch': 'data.noneMatchAny',
  'data.empty': 'data.emptyAny',
  'data.emptyAction': 'data.emptyActionAny',
  'data.countHouses': 'data.countAny',
  'data.preview': 'data.previewAny',
  'data.gapNotShortlisted': 'data.gapNotShortlistedAny',
  'data.gapNotSelected': 'data.gapNotSelectedAny',
  'data.gapRejected': 'data.gapRejectedAny',
  'data.gapPhotosNotShortlisted': 'data.gapPhotosNotShortlistedAny',
  'imp.intro': 'imp.introAny',
  'imp.modeMergeHint': 'imp.modeMergeHintAny',
  'imp.modeCopyHint': 'imp.modeCopyHintAny',
  'imp.restore': 'imp.restoreAny',
  'imp.newHouses': 'imp.newAny',
  'imp.updatedHouses': 'imp.updatedAny',
  'imp.checklistsCleared': 'imp.checklistsClearedAny',
  'imp.removedHouses': 'imp.removedAny',
  'imp.floorsLeftBlank': 'imp.floorsLeftBlankAny',
  'imp.done': 'imp.doneAny',
  'imp.doneRemoved': 'imp.doneRemovedAny',
  'imp.seeHouses': 'imp.seeAny',
  // Kinds PR 31: the app-wide texts of Connect, Your data and the first download (SpaceService.appWord).
  'connect.intro': 'connect.introAny',
  'connect.success': 'connect.successAny',
  'connect.aiHint': 'connect.aiHintAny',
  'connect.aiSends': 'connect.aiSendsAny',
  'connect.inviteBody': 'connect.inviteBodyAny',
  'data.syncPaused': 'data.syncPausedAny',
  'data.migrationTitle': 'data.migrationTitleAny',
  'data.clearBody': 'data.clearBodyAny',
  'confirm.clearBrowser': 'confirm.clearBrowserAny',
  'data.migrationDone': 'data.migrationDoneAny',
  'data.progressHouses': 'data.progressHousesAny',
};

/**
 * One table per kind, from its kind file's `words` (the house's key of a word and the key the kind reads in its place), and the
 * table of the words of a thing that acts on every space.
 */
const TABLES: Record<string, Readonly<Partial<Record<TKey, TKey>>>> = Object.fromEntries(
  kindWordKinds().slice(1).map((kind) => [kind, Object.fromEntries(kindWordPairs(kind))]),
);
TABLES['*'] = ANY;

/** The key to show for `key` in the space of `kind`: the kind's own where it has one, else `key` (the house's). */
export function toolWord(kind: string | null | undefined, key: TKey): TKey {
  if (isUserKindId(kind)) return userWordFor(kind, key) ?? key;
  const table = kind && Object.prototype.hasOwnProperty.call(TABLES, kind) ? TABLES[kind] : undefined;
  return table?.[key] ?? key;
}

/** The house keys a kind words itself and the keys it reads instead, for the checks of the dictionaries (`school-words.spec.ts`). */
export function toolWordPairs(kind: string): [TKey, TKey][] {
  const table = Object.prototype.hasOwnProperty.call(TABLES, kind) ? TABLES[kind] : {};
  return Object.entries(table) as [TKey, TKey][];
}
