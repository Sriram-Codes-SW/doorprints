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

/** How many houses Compare shows side by side. */
export const MAX_SELECTED = 4;
export const MIN_SELECTED = 2;

/**
 * The house ids a `?ids=a,b,c` query parameter asks for, in order and without duplicates, kept only when they are
 * houses that can be compared (`allowed`); at most {@link MAX_SELECTED}. Null when the parameter is absent, so the
 * page knows to pick a default instead.
 */
export function idsFromQuery(raw: string | null, allowed: ReadonlySet<string>): string[] | null {
  if (raw === null) return null;
  const ids: string[] = [];
  for (const id of raw.split(',').map((x) => x.trim())) {
    if (id && allowed.has(id) && !ids.includes(id)) ids.push(id);
    if (ids.length === MAX_SELECTED) break;
  }
  return ids;
}
