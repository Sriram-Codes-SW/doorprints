import type { Dict, TKey } from '../i18n/en';
import type { PriceType } from '../core/models';

/**
 * Formatting for the exported files (S4-03, docs/11 §5.2: "the same output for the same data and options").
 *
 * Nothing here uses `Intl`: ICU data differs between browsers and browser versions, so `Intl.NumberFormat` and
 * `Intl.DateTimeFormat` would make the same house export differently on two machines and break the golden-file
 * tests. Screens keep using `TranslationService` (which does use Intl, correctly); files use these functions.
 *
 * Dates are written as UTC, so an export made at 23:30 IST and one made at 01:00 IST the next day do not disagree
 * about what "today" is; the file name and the cover both say UTC too.
 */

/** Translates a key with `{placeholders}`, exactly like TranslationService.t but without Angular. */
export function tr(dict: Dict, key: TKey, params?: Readonly<Record<string, string | number>>): string {
  const template = dict[key] ?? key;
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match,
  );
}

/** Indian digit grouping: 1,25,000 — last three digits, then pairs. Works on the absolute value. */
export function indianGroup(value: number): string {
  const negative = value < 0;
  const digits = Math.abs(Math.round(value)).toString();
  let grouped: string;
  if (digits.length <= 3) {
    grouped = digits;
  } else {
    const last3 = digits.slice(-3);
    let rest = digits.slice(0, -3);
    const parts: string[] = [];
    while (rest.length > 2) {
      parts.unshift(rest.slice(-2));
      rest = rest.slice(0, -2);
    }
    if (rest.length > 0) parts.unshift(rest);
    grouped = `${parts.join(',')},${last3}`;
  }
  return negative ? `-${grouped}` : grouped;
}

/**
 * `₹12,50,000`, with the minus **before** the sign for a negative amount. Byte-identical to
 * `ExportRows.rupees` in `android/shared/.../export/ExportRows.kt`, so both apps print a price the same way.
 */
export function rupees(amount: number): string {
  const negative = amount < 0;
  const digits = indianGroup(Math.abs(Math.round(amount)));
  return (negative ? '-₹' : '₹') + digits;
}

/** "₹1,25,000" or, for rent, the translated "₹1,25,000/month". Empty input becomes an en dash. */
export function formatPrice(dict: Dict, price: number | null | undefined, type: PriceType | null | undefined): string {
  if (price === null || price === undefined || !Number.isFinite(price)) return '–';
  const base = rupees(price);
  return type === 'RENT' ? tr(dict, 'price.perMonth', { price: base }) : base;
}

/**
 * Fixed decimals, always a dot, always `digits` digits, **ties away from zero**.
 *
 * `Number.prototype.toFixed` is not used: it rounds by the binary value, so `(3.85).toFixed(1)` is "3.8" in a
 * browser while Kotlin's `floor(x * 10 + 0.5)` gives "3.9" — the two apps' copies of the same house would then
 * disagree about its score. This mirrors `ExportRows.fixed` in `android/shared/.../export/ExportRows.kt`.
 */
export function fixed(value: number, digits: number): string {
  if (!Number.isFinite(value)) return '';
  const negative = value < 0;
  const abs = negative ? -value : value;
  const scale = 10 ** digits;
  const scaled = Math.floor(abs * scale + 0.5);
  const whole = Math.floor(scaled / scale);
  const frac = scaled - whole * scale;
  let out = negative && scaled !== 0 ? '-' : '';
  out += String(whole);
  if (digits > 0) out += '.' + String(frac).padStart(digits, '0');
  return out;
}

/** Fixed decimals for a readable copy; an en dash when nothing is recorded. */
export function formatDecimal(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '–';
  return fixed(value, digits);
}

export function formatInt(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '–';
  return Math.round(value).toString();
}

/** `YYYY-MM-DD` in UTC. */
export function formatDate(iso: string | null | undefined): string {
  const date = toDate(iso);
  return date ? date.toISOString().slice(0, 10) : '–';
}

/** `YYYY-MM-DD HH:MM UTC`. */
export function formatDateTime(iso: string | null | undefined): string {
  const date = toDate(iso);
  return date ? `${date.toISOString().slice(0, 10)} ${date.toISOString().slice(11, 16)} UTC` : '–';
}

/** Six decimals, the precision the map and the API use for coordinates. */
export function formatCoord(value: number): string {
  return Number.isFinite(value) ? fixed(value, 6) : '–';
}

/**
 * The canonical machine-readable form used in data files (CSV, XLSX, JSON): UTC with milliseconds. Normalising
 * here means a row written by the server ("2026-09-22T10:15:30Z") and one written here ("…:30.000Z") match.
 */
export function isoUtc(iso: string | null | undefined): string {
  const date = toDate(iso);
  return date ? date.toISOString() : '';
}

export function toDate(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? null : new Date(ms);
}

/** The date stamp used in every export file name: `Doorprints-copy-2026-09-22.html`. */
export function fileStamp(exportedAt: string): string {
  return formatDate(exportedAt) === '–' ? '0000-00-00' : formatDate(exportedAt);
}

/**
 * The readable HTML copy's file name, `Doorprints-copy-2026-09-22.html`: the download (HTML and PDF) and the entry
 * inside a backup ZIP. The word `copy` says it cannot be imported, as opposed to `Doorprints-backup-…`, which can
 * (naming rule G.3.4: every exported file name says what it is; only a backup is ever imported). English on every
 * language setting, UTC date, so the files sort and match across devices.
 */
export function htmlCopyName(exportedAt: string): string {
  return `Doorprints-copy-${fileStamp(exportedAt)}.html`;
}

// ---- Escaping ----

const HTML_ESCAPES: Readonly<Record<string, string>> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

/** Escapes every character that could end an attribute or start a tag. The HTML export runs no scripts anyway. */
export function escapeHtml(text: string | null | undefined): string {
  if (text === null || text === undefined) return '';
  return text.replace(/[&<>"']/g, (c) => HTML_ESCAPES[c] ?? c);
}

/** XML for the XLSX parts: the same five characters, plus control characters that are illegal in XML 1.0. */
export function escapeXml(text: string | null | undefined): string {
  if (text === null || text === undefined) return '';
  // eslint-disable-next-line no-control-regex
  const clean = text.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '');
  return clean.replace(/[&<>"']/g, (c) => HTML_ESCAPES[c] ?? c);
}

/**
 * Escapes the Markdown control characters so a house called `**deal**` is not shown in bold, a `|` cannot break out
 * of a table cell and `<script>` in a note stays literal. Digits, hyphens and full stops are left alone, so dates
 * and prices stay readable (`2026-09-22`, not `2026\\-09\\-22`).
 *
 * Inline characters are not enough: Markdown's **block** constructs are decided by the first non-space character
 * of a line, so a note that begins `# ` became a heading and one that began `- ` became a list item — the
 * exported text was then not what the user wrote. {@link escapeLeadingMarker} handles those, per line.
 *
 * `#`, `+` and `-` match `MarkdownWriter.text` in `android/shared/.../export/MarkdownWriter.kt` case for case,
 * including the rule that a `-` only starts a list when it is alone or followed by a space (so a negative
 * coordinate is left readable). The ordered-list case (`1.`) is the one that file's comment names but does not
 * implement; it is handled here and should be ported back. `!` is deliberately **not** escaped: `[` in
 * the class above already is, so `![` can never form an image, and escaping every exclamation mark would put a
 * backslash into ordinary prose while changing nothing about how it renders.
 */
export function escapeMarkdown(text: string | null | undefined): string {
  if (text === null || text === undefined) return '';
  const inline = text.replace(/([\\`*_[\]|<>~])/g, '\\$1');
  return inline.split('\n').map(escapeLeadingMarker).join('\n');
}

/** Escapes a leading heading, bullet or ordered-list marker on one line, keeping its indentation. */
export function escapeLeadingMarker(line: string): string {
  const match = /^([ \t]*)([\s\S]*)$/.exec(line);
  const indent = match ? match[1] : '';
  const rest = match ? match[2] : line;
  const first = rest.charAt(0);
  // ATX heading: `#` starts one whatever follows it.
  if (first === '#') return `${indent}\\${rest}`;
  // Bullet list. `+` is escaped whatever follows it and `-` only when alone or followed by a space, which is
  // what the Kotlin writer does: `+` is over-escaped there (CommonMark needs a space after it), but `\+` renders
  // as `+`, and the two apps agreeing matters more than one backslash in front of a phone number.
  if (first === '+' || (first === '-' && (rest.length === 1 || rest.charAt(1) === ' '))) {
    return `${indent}\\${rest}`;
  }
  // Ordered list: up to nine digits, then `.` or `)`, then a space or the end of the line.
  const ordered = /^(\d{1,9})([.)])( |$)/.exec(rest);
  if (ordered) return `${indent}${ordered[1]}\\${rest.slice(ordered[1].length)}`;
  return line;
}

export function markdownCell(text: string | null | undefined): string {
  return escapeMarkdown(text).replace(/\r?\n/g, ' ');
}

/** What a CSV cell can hold before it is rendered; `undefined` and `null` both become an empty field. */
export type CsvValue = string | number | null | undefined;

/**
 * One CSV field (RFC 4180), matching `CsvWriter.kt` field for field.
 *
 * The spreadsheet formula-injection guard from the threat model is applied when `guardFormula` is true: a field
 * starting with `=`, `+`, `-`, `@`, a tab or a carriage return is prefixed with an apostrophe, so Excel,
 * LibreOffice and Google Sheets treat it as text. Callers pass `false` for a **number**, because a leading minus
 * is part of the number and an apostrophe in front of it would stop a spreadsheet reading -12.978321 as a
 * coordinate (`csv-export.ts` decides this per cell type).
 *
 * Quoting covers a comma, a quote or a line break, and also a leading or trailing space, which some readers
 * would otherwise strip.
 */
export function csvCell(value: CsvValue, guardFormula = true): string {
  if (value === null || value === undefined) return '';
  let text = typeof value === 'number' ? (Number.isFinite(value) ? String(value) : '') : value;
  if (guardFormula && /^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  if (/[",\r\n]/.test(text) || /^ | $/.test(text)) text = `"${text.replace(/"/g, '""')}"`;
  return text;
}

/** Byte-order mark: Excel on Windows needs it to read UTF-8, and every CSV in the ZIP starts with it. */
export const BOM = '\uFEFF';
