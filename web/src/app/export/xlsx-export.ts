import { escapeXml } from './deterministic';
import { utf8, zip } from './zip';
import type { ZipEntry } from './zip';

/**
 * A minimal SpreadsheetML (XLSX) writer.
 *
 * An XLSX file is a ZIP of XML parts, so the ZIP writer in zip.ts is all the machinery it needs. Apache POI is far
 * too large for the Android APK (docs/11 §5.2) and SheetJS's current releases are not on npm, so both apps write
 * their own; this is the TypeScript half.
 *
 * Choices that keep the file small, valid and reproducible:
 *  - inline strings (`t="inlineStr"`), so there is no shared-string table to keep in sync;
 *  - three cell formats only: general, ₹ currency and `yyyy-mm-dd` date;
 *  - dates as real serial numbers (days since 1899-12-30), so sorting and filtering work in Excel;
 *  - the header row frozen on every sheet, and an autofilter over the used range.
 */

export type CellValue =
  | { kind: 'text'; value: string }
  | { kind: 'number'; value: number }
  | { kind: 'money'; value: number }
  | { kind: 'date'; value: Date }
  | { kind: 'blank' };

export interface Sheet {
  /** Sheet name; Excel allows at most 31 characters and none of `[]:*?/\`. */
  name: string;
  header: readonly string[];
  rows: readonly (readonly CellValue[])[];
}

export const text = (value: string | null | undefined): CellValue =>
  value === null || value === undefined || value === '' ? { kind: 'blank' } : { kind: 'text', value };
export const num = (value: number | null | undefined): CellValue =>
  value === null || value === undefined || !Number.isFinite(value) ? { kind: 'blank' } : { kind: 'number', value };
export const money = (value: number | null | undefined): CellValue =>
  value === null || value === undefined || !Number.isFinite(value) ? { kind: 'blank' } : { kind: 'money', value };
export const date = (iso: string | null | undefined): CellValue => {
  if (!iso) return { kind: 'blank' };
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? { kind: 'blank' } : { kind: 'date', value: new Date(ms) };
};

const STYLE_GENERAL = 0;
const STYLE_MONEY = 1;
const STYLE_DATE = 2;
const STYLE_HEADER = 3;

export function buildXlsx(sheets: readonly Sheet[], modifiedAt: Date): Uint8Array {
  const entries: ZipEntry[] = [
    { path: '[Content_Types].xml', data: utf8(contentTypes(sheets.length)) },
    { path: '_rels/.rels', data: utf8(rootRels()) },
    { path: 'xl/workbook.xml', data: utf8(workbook(sheets)) },
    { path: 'xl/_rels/workbook.xml.rels', data: utf8(workbookRels(sheets.length)) },
    { path: 'xl/styles.xml', data: utf8(styles()) },
    ...sheets.map((sheet, index) => ({
      path: `xl/worksheets/sheet${index + 1}.xml`,
      data: utf8(worksheet(sheet)),
    })),
  ];
  return zip(entries, modifiedAt);
}

function contentTypes(sheetCount: number): string {
  const sheetParts = Array.from(
    { length: sheetCount },
    (_, i) =>
      `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ` +
      `ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`,
  ).join('');
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
    '<Default Extension="xml" ContentType="application/xml"/>' +
    '<Override PartName="/xl/workbook.xml" ' +
    'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
    '<Override PartName="/xl/styles.xml" ' +
    'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
    sheetParts +
    '</Types>'
  );
}

function rootRels(): string {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    '<Relationship Id="rId1" ' +
    'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" ' +
    'Target="xl/workbook.xml"/>' +
    '</Relationships>'
  );
}

function workbook(sheets: readonly Sheet[]): string {
  const tags = sheets
    .map((sheet, i) => `<sheet name="${escapeXml(sheetName(sheet.name))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`)
    .join('');
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" ' +
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
    `<sheets>${tags}</sheets>` +
    '</workbook>'
  );
}

function workbookRels(sheetCount: number): string {
  const rels = Array.from(
    { length: sheetCount },
    (_, i) =>
      `<Relationship Id="rId${i + 1}" ` +
      'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" ' +
      `Target="worksheets/sheet${i + 1}.xml"/>`,
  ).join('');
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${rels}</Relationships>`
  );
}

function styles(): string {
  // numFmtId 164: ₹ with Indian grouping; 165: ISO date. Ids below 164 are reserved by the format.
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    '<numFmts count="2">' +
    '<numFmt numFmtId="164" formatCode="&quot;₹&quot;#,##,##0"/>' +
    '<numFmt numFmtId="165" formatCode="yyyy\\-mm\\-dd"/>' +
    '</numFmts>' +
    '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font>' +
    '<font><b/><sz val="11"/><name val="Calibri"/></font></fonts>' +
    '<fills count="2"><fill><patternFill patternType="none"/></fill>' +
    '<fill><patternFill patternType="gray125"/></fill></fills>' +
    '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>' +
    '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
    '<cellXfs count="4">' +
    '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>' +
    '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>' +
    '<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>' +
    '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>' +
    '</cellXfs>' +
    // Excel and openpyxl both expect a named default style to exist.
    '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>' +
    '</styleSheet>'
  );
}

function worksheet(sheet: Sheet): string {
  const width = Math.max(sheet.header.length, ...sheet.rows.map((row) => row.length), 1);
  const lastColumn = columnName(width);
  const lastRow = sheet.rows.length + 1;
  const headerCells = sheet.header
    .map((value, col) => cellXml(col, 1, { kind: 'text', value }, STYLE_HEADER))
    .join('');
  const bodyRows = sheet.rows
    .map((row, index) => {
      const rowNumber = index + 2;
      const cells = row.map((value, col) => cellXml(col, rowNumber, value, styleFor(value))).join('');
      return `<row r="${rowNumber}">${cells}</row>`;
    })
    .join('');
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
    `<dimension ref="A1:${lastColumn}${lastRow}"/>` +
    '<sheetViews><sheetView workbookViewId="0">' +
    '<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>' +
    '</sheetView></sheetViews>' +
    '<sheetFormatPr defaultRowHeight="15"/>' +
    `<sheetData><row r="1">${headerCells}</row>${bodyRows}</sheetData>` +
    `<autoFilter ref="A1:${lastColumn}${lastRow}"/>` +
    '</worksheet>'
  );
}

function styleFor(value: CellValue): number {
  if (value.kind === 'money') return STYLE_MONEY;
  if (value.kind === 'date') return STYLE_DATE;
  return STYLE_GENERAL;
}

function cellXml(columnIndex: number, rowNumber: number, value: CellValue, style: number): string {
  const ref = `${columnName(columnIndex + 1)}${rowNumber}`;
  const s = style === STYLE_GENERAL ? '' : ` s="${style}"`;
  switch (value.kind) {
    case 'blank':
      return '';
    case 'text':
      return `<c r="${ref}"${s} t="inlineStr"><is><t xml:space="preserve">${escapeXml(value.value)}</t></is></c>`;
    case 'number':
    case 'money':
      return `<c r="${ref}"${s}><v>${cleanNumber(value.value)}</v></c>`;
    case 'date':
      return `<c r="${ref}"${s}><v>${excelSerial(value.value)}</v></c>`;
  }
}

/**
 * Plain decimal, no exponent and no locale separators, so the XML is the same everywhere. Ten decimals: a date
 * serial is a fraction of a day, and six decimals would round a timestamp by up to a tenth of a second.
 */
function cleanNumber(value: number): string {
  if (!Number.isFinite(value)) return '0';
  if (Number.isInteger(value)) return value.toFixed(0);
  return value.toFixed(10).replace(/0+$/, '').replace(/\.$/, '');
}

/** Days since 1899-12-30 (the serial epoch Excel uses), with the time of day as the fraction, in UTC. */
export function excelSerial(when: Date): string {
  const days = when.getTime() / 86400000 + 25569;
  return cleanNumber(days);
}

/** 1 -> A, 26 -> Z, 27 -> AA. */
export function columnName(index1Based: number): string {
  let n = Math.max(1, index1Based);
  let name = '';
  while (n > 0) {
    const rest = (n - 1) % 26;
    name = String.fromCharCode(65 + rest) + name;
    n = Math.floor((n - rest) / 26);
  }
  return name;
}

/** Excel rejects these characters in a sheet name and truncates at 31 characters. */
export function sheetName(name: string): string {
  return name.replace(/[[\]:*?/\\]/g, '_').slice(0, 31);
}
