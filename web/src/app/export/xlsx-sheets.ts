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

import type { ExportBundle } from './export-model';
import { exportTables } from './export-rows';
import type { Cell } from './export-rows';
import type { CellValue, Sheet } from './xlsx-export';

/**
 * One sheet per CSV table, with the same columns in the same order and the same headings in the same language —
 * both come from `export-rows.ts`, the shared `ExportRows` contract.
 *
 * What the workbook adds over the CSV is **types**: a ₹ amount is a number with a currency format, a timestamp is
 * a real date, so the user can sort, filter and total in Excel or LibreOffice. Only the rendering differs from
 * the Kotlin writer (which puts the fixed-decimal text in the cell); the rows and columns are the same.
 */
export function buildWorkbook(bundle: ExportBundle): Sheet[] {
  return exportTables(bundle).map((table) => ({
    name: table.name,
    header: table.columns,
    rows: table.rows.map((row) => row.map(toCellValue)),
  }));
}

/** A shared `Cell` as a typed spreadsheet cell. */
export function toCellValue(cell: Cell): CellValue {
  switch (cell.kind) {
    case 'blank':
      return { kind: 'blank' };
    case 'text':
      // Always an inline string, never a formula: a label of "=SUM(A1:A9)" is a label (threat model, CSV/XLSX
      // injection). The writer has no `<f>` element at all, so there is nothing to opt out of.
      return cell.value === '' ? { kind: 'blank' } : { kind: 'text', value: cell.value };
    case 'num':
      return Number.isFinite(cell.value) ? { kind: 'number', value: cell.value } : { kind: 'blank' };
    case 'count':
      return Number.isFinite(cell.value) ? { kind: 'number', value: cell.value } : { kind: 'blank' };
    case 'money':
      return Number.isFinite(cell.amount) ? { kind: 'money', value: cell.amount } : { kind: 'blank' };
    case 'stamp':
      return { kind: 'date', value: new Date(cell.epochMillis) };
  }
}
