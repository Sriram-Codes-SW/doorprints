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

/**
 * "a", "a and b", "a, b and c", "a, b, c and d", … with each language's own list pattern — the same rule as
 * Android's `ImportWorker.joinList`: two items use `two`; three or more use `three`, with everything before the
 * last two folded into its first slot by `middle` ("a, b"). Any number of items: a list that exists to say what is
 * left out must never drop the fourth one.
 */
export function joinList(
  items: readonly string[],
  two: (a: string, b: string) => string,
  three: (a: string, b: string, c: string) => string,
  middle: (a: string, b: string) => string,
): string {
  if (items.length === 0) return '';
  if (items.length === 1) return items[0];
  if (items.length === 2) return two(items[0], items[1]);
  const head = items.slice(0, items.length - 2).reduce(middle);
  return three(head, items[items.length - 2], items[items.length - 1]);
}
