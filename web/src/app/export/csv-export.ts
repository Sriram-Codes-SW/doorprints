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

import { BOM, csvCell } from './deterministic';
import type { ExportBundle } from './export-model';
import { exportTables, plain } from './export-rows';
import type { Cell, ExportTable } from './export-rows';

/**
 * The CSV tables of docs/11 §5.2, one file per table, zipped together.
 *
 * The rows, the columns and their order come from `export-rows.ts`, the TypeScript half of the shared
 * `ExportRows` contract, so a CSV written on a phone and one written in a browser hold the same values in the
 * same order — **including the headings, which are in the export language**. They used to be hardcoded English
 * API field names, which quietly made the language option a no-op for this format and for XLSX.
 *
 * Everything else about the file is unchanged and matches `CsvWriter.kt`: UTF-8 with a BOM (Excel on Windows
 * needs it to read Indic text), CRLF line endings, RFC 4180 quoting, and the formula-injection guard applied to
 * **text** cells only — a number's leading minus is part of the number, and prefixing it would stop a spreadsheet
 * reading -12.978321 as a coordinate.
 *
 * `brokers.csv` follows when the copy has brokers and `rooms.csv` when it has rooms and `criteria.csv` when it has criterion records (slice 2) and `answers.csv` when a house has answers (slice 3a) and `viewings.csv` when the copy has viewings (slice 3b-1).
 */

export const CSV_FILES = ['houses.csv', 'scores.csv', 'visits.csv', 'photos.csv'] as const;
export type CsvFileName = (typeof CSV_FILES)[number];
/** The four files of every copy, `brokers.csv` when it has brokers (slice 1b) and `rooms.csv` when it has rooms (slice 1c). */
export type CsvTables = Record<CsvFileName, string> & { 'brokers.csv'?: string; 'rooms.csv'?: string; 'criteria.csv'?: string; 'answers.csv'?: string; 'viewings.csv'?: string };

const CRLF = '\r\n';

export function buildCsvTables(bundle: ExportBundle): CsvTables {
  const out: Record<string, string> = {};
  for (const table of exportTables(bundle)) out[`${table.name}.csv`] = csvTable(table);
  return out as CsvTables;
}

/** One table as an RFC 4180 file. Exported so a test can read a single table without building the ZIP. */
export function csvTable(table: ExportTable): string {
  const lines = [table.columns.map((column) => csvCell(column)).join(',')];
  for (const row of table.rows) lines.push(row.map(csvField).join(','));
  return BOM + lines.join(CRLF) + CRLF;
}

function csvField(cell: Cell): string {
  return csvCell(plain(cell), cell.kind === 'text');
}
