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
 * Sprint 4b adds rooms.csv, answers.csv and viewings.csv when those tables exist.
 */

export const CSV_FILES = ['houses.csv', 'scores.csv', 'visits.csv', 'photos.csv'] as const;
export type CsvFileName = (typeof CSV_FILES)[number];

const CRLF = '\r\n';

export function buildCsvTables(bundle: ExportBundle): Record<CsvFileName, string> {
  const out = {} as Record<CsvFileName, string>;
  for (const table of exportTables(bundle)) out[`${table.name}.csv`] = csvTable(table);
  return out;
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
