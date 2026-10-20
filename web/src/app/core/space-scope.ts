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

import { isBaselineKind } from './kind-capabilities';
import type { HouseDto } from './models';
import { kindOfCandidate } from './candidate';
import { isUserKindId } from './user-kinds/user-kind-ids';

/**
 * The rules that scope the tools to one space (kinds PR B, S4b-BL-279; docs/03 §18.12): what a space contains, Compare's
 * refusal of another kind, the file name of a space's copy, when a space shows the assistant and which kind a shared
 * listing lands in. Pure, so that the phones can read the same answers from `docs/schemas/kinds/space-scope-vectors.json`
 * (`space-scope.spec.ts` holds this file to it). With one kind enabled every rule is today's.
 */

/** A candidate as these rules read it: its kind (none is the house), its status and whether it is deleted. */
export type Scoped = Pick<HouseDto, 'kind'> & Partial<Pick<HouseDto, 'id' | 'status' | 'deleted'>>;

/**
 * Whether `space` holds a candidate: with one space (`multi` false) it holds every candidate; with two or more, a candidate
 * of a kind that has a space here is in its own, and one of a kind that has none (an unavailable kind, a newer app's) is
 * in the default space.
 */
export function holdsFor(space: string, multi: boolean, dflt: string, available: readonly string[]): (h: Pick<HouseDto, 'kind'>) => boolean {
  return (h) => {
    if (!multi) return true;
    const kind = kindOfCandidate(h);
    return (available.includes(kind) ? kind : dflt) === space;
  };
}

/** The most candidates Compare puts side by side, and the fewest. */
export const COMPARE_MAX = 4;

/** What Compare keeps of a pick in a space, and what it refuses (it names a candidate of another space). */
export interface ComparePick {
  readonly kept: readonly string[];
  readonly refused: readonly string[];
}

/**
 * Compare's pick in a space: the picked ids that are live candidates in the running of the space, in order, without
 * duplicates, at most four; a live candidate of another space is refused (the page says so in a sentence); an id that is
 * no candidate, or is out of the running, is dropped as it always was. `inRunning` is the status rule (not Rejected, not
 * Not chosen).
 */
export function comparePick(
  picked: readonly string[],
  candidates: readonly (Scoped & { id: string })[],
  holds: (h: Pick<HouseDto, 'kind'>) => boolean,
  inRunning: (h: Scoped) => boolean,
): ComparePick {
  const byId = new Map(candidates.filter((h) => !h.deleted).map((h) => [h.id, h] as const));
  const kept: string[] = [];
  const refused: string[] = [];
  for (const raw of picked) {
    const id = raw.trim();
    const h = byId.get(id);
    if (!h || kept.includes(id) || refused.includes(id)) continue;
    if (!holds(h)) refused.push(id);
    else if (inRunning(h) && kept.length < COMPARE_MAX) kept.push(id);
  }
  return { kept, refused };
}

/** A kind's plural noun as a file-name word: the English words, lower case, letters and digits, joined by hyphens. */
export function fileSlug(many: string): string {
  return many.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

/**
 * The file name of a space's copy: the plural noun goes before the date (`Doorprints-2026-09-22-csv.zip` becomes
 * `Doorprints-schools-2026-09-22-csv.zip`, `Doorprints-copy-2026-09-22.html` becomes
 * `Doorprints-copy-schools-2026-09-22.html`). The house's copies, and a name with no date or a noun with no letters, are
 * left as they are, byte for byte; a backup is never renamed (it holds everything).
 */
export function spaceFileName(name: string, space: string | undefined, many: string): string {
  const slug = fileSlug(many);
  if (!space || isBaselineKind(space) || !slug) return name;
  return name.replace(/\d{4}-\d{2}-\d{2}/, (date) => `${slug}-${date}`);
}

/**
 * Whether the assistant (Ask and Plan) is shown in a space: the house as today (whenever the assistant is on); any other
 * kind only when this build offers it and the assistant reads it: the person's own, in this browser (`ownKey`), or the
 * server's when its status names the kind (`serverKinds`, `GET /api/ai/status` `kinds`, S4b-BL-290). An older server names
 * none, and its Ask and Plan take no kind and would read every space, so a space other than the house has no assistant of
 * its.
 */
export function aiShownIn(kind: string, available: readonly string[], on: boolean, ownKey: boolean, serverKinds: readonly string[] = []): boolean {
  // No AI reads a kind a person added (its `ai` slots are empty by the profile, docs/03 ADR-38): its space has no assistant.
  if (!on || isUserKindId(kind)) return false;
  return isBaselineKind(kind) || (available.includes(kind) && (ownKey || serverKinds.includes(kind)));
}

/** The kind a shared listing lands in: the one chosen when it is enabled, else the default kind. */
export function shareKind(chosen: string | null | undefined, enabled: readonly string[], dflt: string): string {
  return chosen && enabled.includes(chosen) ? chosen : dflt;
}

/**
 * The kind a new entry is made as: the space's own while that kind is enabled, else the default kind (always enabled). A kind
 * that is off keeps its entries but takes no new one, so nothing is made that the person's chosen lists would not show.
 */
export function createKind(space: string, enabled: readonly string[], dflt: string): string {
  return enabled.includes(space) ? space : dflt;
}
