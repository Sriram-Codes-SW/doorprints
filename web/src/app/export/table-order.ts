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

import type { CopyBlock } from '../core/kind-capabilities';
import { capabilitiesOf } from '../core/kind-capabilities';
import type { ExportTable } from './export-rows';

// The order of the tables of a CSV or XLSX copy follows the kind file like the Markdown and the readable copy do (docs/03 §18.29):
// `capabilities.copyOrder` names the blocks that come first after the houses table, the rest follow in the tables' fixed order. The
// Kotlin half is `TableOrder.kt`; both are held to `docs/schemas/kinds/capabilities-vectors.json`, `copyOrder.tables`.

/** The block of a copy each table belongs to. The houses table (always first) and brokers belong to none; the cost, area notes, distances, move-in and notes blocks have no table of their own. */
export const TABLE_BLOCK: Readonly<Record<string, CopyBlock>> = {
  scores: 'checklist', criteria: 'checklist', visits: 'visits', photos: 'photos', rooms: 'rooms', answers: 'questions', viewings: 'viewings',
};

/** The tables with the tables of the kind's `copyOrder` blocks right after the houses table, in that order; with no `copyOrder` (the house, the school), as given. */
export function inCopyOrder(tables: readonly ExportTable[], kindId: string | null | undefined): ExportTable[] {
  const order = capabilitiesOf(kindId).copyOrder;
  if (!order.length) return [...tables];
  const [head, ...rest] = tables;
  const first = order.flatMap((block) => rest.filter((t) => TABLE_BLOCK[t.name] === block));
  return [head, ...first, ...rest.filter((t) => !first.includes(t))];
}
