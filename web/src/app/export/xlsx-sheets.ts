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
