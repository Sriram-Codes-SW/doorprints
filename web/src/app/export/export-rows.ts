import { CHECKLIST } from '../core/models';
// deterministic.ts holds the **one** implementation of the two cross-platform number rules: `rupees`/`indianGroup`
// (Indian digit grouping, minus before the ₹) and `fixed` (ties away from zero). This file used to carry its own
// `rupees` + `indianGroups` as well. The two agreed, but two copies of a formatting rule that has to match
// `ExportRows.kt` byte for byte are exactly the pair that drifts, and a drift would make a phone and a browser
// disagree about a price. `deterministic.spec.ts` is where both rules are pinned.
import { fixed, rupees } from './deterministic';
import type { ExportBundle } from './export-model';
import { ExportStrings } from './export-strings';
import { photoFileName } from './photo-names';
import type { PhotoRecord, VisitRecord } from '../data/records';

/**
 * **The format-independent model → rows logic.** The TypeScript half of
 * `android/shared/src/commonMain/kotlin/com/househunt/shared/export/ExportRows.kt`, whose own comment is the
 * contract this file exists to keep: *"Every exporter on Android and on the web reads its rows from here, so a
 * CSV, an XLSX sheet and the HTML table always show the same values in the same order, and the two apps agree
 * cell for cell."*
 *
 * Before this file, the web CSV and XLSX used raw English API field names, a different column set (no rank, no
 * visit or photo counts, no `minutes`) and a different order — and, because the headings were hardcoded English,
 * **the export language option did nothing at all** for two of the six formats.
 *
 * Nothing here reads a clock, a locale or a file. Numbers are formatted by hand rather than with `Intl`, for the
 * same reason as in `deterministic.ts`: ICU data differs between browsers and between browser versions, so a
 * platform formatter would make the same house export differently on two machines.
 *
 * **One deliberate difference from the Kotlin file**, which is not a divergence in the rows: timestamps are
 * rendered in **UTC**. `ExportOptions` on Android carries the phone's current offset; the web app has no such
 * option and every date it writes is UTC (see the header of `deterministic.ts`), so an export made at 23:30 IST
 * and one made at 01:00 IST the next day do not disagree about what "today" is. The cover says so. The exact
 * instant, to the millisecond, is in the backup's `data.json` either way.
 */

/**
 * One value in an exported table.
 *
 * The type is kept rather than turned into a string straight away because XLSX wants typed cells — a price must
 * be a number a spreadsheet can total, a date a real date — while CSV wants the machine form and HTML, Markdown
 * and the PDF want the reader's form (₹12,50,000). This is the discriminated-union mirror of Kotlin's
 * `sealed interface Cell`.
 */
export type Cell =
  /** Nothing recorded. An empty CSV/XLSX cell, and "—" in a readable copy. */
  | { readonly kind: 'blank' }
  | { readonly kind: 'text'; readonly value: string }
  /** A plain number with a fixed number of decimals (score 4.5, latitude 12.978321). */
  | { readonly kind: 'num'; readonly value: number; readonly decimals: number }
  /** A whole count (bedrooms, visits, minutes). */
  | { readonly kind: 'count'; readonly value: number }
  /** Rupees, always whole (the app never stores paise). */
  | { readonly kind: 'money'; readonly amount: number }
  /** An instant, rendered in UTC. */
  | { readonly kind: 'stamp'; readonly epochMillis: number };

export const BLANK: Cell = { kind: 'blank' };
export const cellText = (value: string): Cell => ({ kind: 'text', value });
export const cellNum = (value: number, decimals = 0): Cell => ({ kind: 'num', value, decimals });
export const cellCount = (value: number): Cell => ({ kind: 'count', value });
export const cellMoney = (amount: number): Cell => ({ kind: 'money', amount });
export const cellStamp = (epochMillis: number): Cell => ({ kind: 'stamp', epochMillis });

/**
 * A timestamp cell, or blank when there is none.
 *
 * Kotlin's `ExportHouse.createdAt` is a non-null `Long`, so `ExportRows` has no such case; a `HouseRecord` read
 * from IndexedDB can legitimately have a null `createdAt` (a row the server never stamped). Writing epoch 0
 * would print `1970-01-01 00:00` into the copy and, worse, into a spreadsheet's date column.
 */
export function maybeStamp(iso: string | null | undefined): Cell {
  if (!iso) return BLANK;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? BLANK : cellStamp(ms);
}

/** An empty or missing string is "nothing recorded", not an empty cell with a value in it. */
function maybeText(value: string | null | undefined): Cell {
  return value === null || value === undefined || value === '' ? BLANK : cellText(value);
}

function maybeCount(value: number | null | undefined): Cell {
  return value === null || value === undefined || !Number.isFinite(value) ? BLANK : cellCount(Math.round(value));
}

/**
 * A table of the copy: one CSV file, one XLSX sheet, one Markdown or HTML table.
 *
 * `name` is language-neutral (the CSV file name and the sheet name); `title` is the translated heading.
 */
export interface ExportTable {
  readonly name: 'houses' | 'scores' | 'visits' | 'photos';
  readonly title: string;
  readonly columns: readonly string[];
  readonly rows: readonly (readonly Cell[])[];
}

/** The four tables of a copy, in file order. */
export function exportTables(bundle: ExportBundle): ExportTable[] {
  return [housesTable(bundle), scoresTable(bundle), visitsTable(bundle), photosTable(bundle)];
}

/** Translated words for this export's language, independent of the language the app is being used in. */
export function stringsOf(bundle: ExportBundle): ExportStrings {
  return ExportStrings.of(bundle.options.lang);
}

/** 1-based position in `bundle.ranking`; 0 for a house that is not in this copy (which cannot happen here). */
export function rankOf(bundle: ExportBundle, houseId: string): number {
  const index = bundle.ranking.findIndex((entry) => entry.house.id === houseId);
  return index < 0 ? 0 : index + 1;
}

export function housesTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const contacts = bundle.options.includeContacts;
  const columns = [
    s.get('col.rank'),
    s.get('col.label'),
    s.get('col.status'),
    s.get('col.score'),
    s.get('col.price'),
    s.get('col.priceType'),
    s.get('col.bedrooms'),
    s.get('col.rating'),
    s.get('col.address'),
    s.get('col.street'),
    s.get('col.locality'),
    s.get('col.lat'),
    s.get('col.lon'),
    // The columns are left out entirely, not blanked: a file the user asked to have no contacts in should not
    // carry two empty columns headed "Phone". Same rule as the Kotlin writer.
    ...(contacts ? [s.get('col.contactName'), s.get('col.contactPhone')] : []),
    s.get('col.listingUrl'),
    s.get('col.notes'),
    s.get('col.visits'),
    s.get('col.photos'),
    s.get('col.createdAt'),
    s.get('col.updatedAt'),
    s.get('col.id'),
  ];
  const rows = bundle.houses.map((entry) => {
    const h = entry.house;
    return [
      cellCount(rankOf(bundle, h.id)),
      cellText(h.label),
      cellText(s.status(h.status)),
      entry.score === null ? BLANK : cellNum(entry.score, 1),
      h.price === null || h.price === undefined ? BLANK : cellMoney(Math.round(h.price)),
      h.price === null || h.price === undefined ? BLANK : cellText(s.priceType(h.priceType)),
      maybeCount(h.bedrooms),
      maybeCount(h.rating),
      maybeText(h.address),
      maybeText(h.street),
      maybeText(h.locality),
      cellNum(h.lat, 6),
      cellNum(h.lon, 6),
      ...(contacts ? [maybeText(h.contactName), maybeText(h.contactPhone)] : []),
      maybeText(h.listingUrl),
      maybeText(h.notes),
      cellCount(entry.visits.length),
      cellCount(entry.photos.length),
      maybeStamp(h.createdAt),
      maybeStamp(h.updatedAt),
      cellText(h.id),
    ];
  });
  return { name: 'houses', title: s.get('table.houses'), columns, rows };
}

/**
 * One row per **scored** checklist item, in the shared display order, then any key the house carries that this
 * version does not know, alphabetically — Sprint 4b's custom criteria land in the same table without a format
 * change, because the key travels next to its label.
 *
 * Note the difference from the old web table, which wrote a row for every built-in item whether or not it had a
 * score: an unscored item is simply absent, as it is on Android.
 */
export function scoresTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const columns = [s.get('col.house'), s.get('col.item'), s.get('col.itemLabel'), s.get('col.score'), s.get('col.houseId')];
  const rows: Cell[][] = [];
  for (const entry of bundle.houses) {
    const h = entry.house;
    for (const key of orderedChecklistKeys(h.checklist)) {
      rows.push([cellText(h.label), cellText(key), cellText(s.check(key)), cellCount(h.checklist[key]), cellText(h.id)]);
    }
  }
  return { name: 'scores', title: s.get('table.scores'), columns, rows };
}

export function visitsTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const columns = [
    s.get('col.house'),
    s.get('col.arrivedAt'),
    s.get('col.leftAt'),
    s.get('col.minutes'),
    s.get('col.source'),
    s.get('col.street'),
    s.get('col.lat'),
    s.get('col.lon'),
    s.get('col.houseId'),
    s.get('col.id'),
  ];
  const rows = allVisits(bundle).map(({ label, visit }) => [
    cellText(label),
    maybeStamp(visit.arrivedAt),
    maybeStamp(visit.leftAt),
    maybeCount(minutesOf(visit)),
    cellText(s.source(visit.source)),
    maybeText(visit.street),
    cellNum(visit.lat, 6),
    cellNum(visit.lon, 6),
    cellText(visit.houseId ?? ''),
    cellText(visit.id),
  ]);
  return { name: 'visits', title: s.get('table.visits'), columns, rows };
}

export function photosTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const columns = [s.get('col.house'), s.get('col.fileName'), s.get('col.createdAt'), s.get('col.houseId'), s.get('col.id')];
  const rows = allPhotos(bundle).map(({ label, photo }) => [
    cellText(label),
    cellText(photoFileName(photo.id)),
    maybeStamp(photo.createdAt),
    cellText(photo.houseId),
    cellText(photo.id),
  ]);
  return { name: 'photos', title: s.get('table.photos'), columns, rows };
}

/**
 * Every visit in the copy, flattened out of the per-house lists and ordered `arrivedAt` then `id`.
 *
 * The bundle nests visits under their house; `ExportBundle` on Android keeps one flat list in exactly this
 * order, and the visits table is built from that, so the flattening has to sort globally rather than
 * house-by-house or the two apps' visit tables would list the same rows in a different order.
 */
export function allVisits(bundle: ExportBundle): { label: string; visit: VisitRecord }[] {
  const out = bundle.houses.flatMap((entry) => entry.visits.map((visit) => ({ label: entry.house.label, visit })));
  return out.sort((a, b) => compare(a.visit.arrivedAt, b.visit.arrivedAt) || compare(a.visit.id, b.visit.id));
}

/** Every photo in the copy, flattened and ordered `createdAt` then `id`; see {@link allVisits}. */
export function allPhotos(bundle: ExportBundle): { label: string; photo: PhotoRecord }[] {
  const out = bundle.houses.flatMap((entry) => entry.photos.map((photo) => ({ label: entry.house.label, photo })));
  return out.sort(
    (a, b) => compare(a.photo.createdAt ?? '', b.photo.createdAt ?? '') || compare(a.photo.id, b.photo.id),
  );
}

/** Built-in checklist keys the house has scored, in display order, then anything else it has, alphabetically. */
export function orderedChecklistKeys(checklist: Record<string, number>): string[] {
  const builtIn = CHECKLIST.filter((item) => typeof checklist[item.key] === 'number').map((item) => item.key);
  const known = new Set(CHECKLIST.map((item) => item.key));
  const extra = Object.keys(checklist)
    .filter((key) => !known.has(key) && typeof checklist[key] === 'number')
    .sort();
  return [...builtIn, ...extra];
}

/** Whole minutes spent at the house, or null while the visit has no end (Kotlin: `ExportVisit.minutes`). */
export function minutesOf(visit: VisitRecord): number | null {
  if (!visit.leftAt) return null;
  const span = millisOf(visit.leftAt) - millisOf(visit.arrivedAt);
  return Math.floor(Math.max(0, span) / 60_000);
}

// ---- rendering ----

/**
 * Machine form, used by CSV: no currency sign, no grouping, no em dash. A spreadsheet can add these up.
 * Timestamps become `2026-09-22 10:15` (UTC).
 */
export function plain(cell: Cell): string {
  switch (cell.kind) {
    case 'blank':
      return '';
    case 'text':
      return cell.value;
    case 'num':
      return fixed(cell.value, cell.decimals);
    case 'count':
      return String(cell.value);
    case 'money':
      return String(cell.amount);
    case 'stamp':
      return utcDateTime(cell.epochMillis);
  }
}

/** Reader's form, used by HTML, Markdown and the PDF: ₹12,50,000 and an em dash for "nothing recorded". */
export function display(cell: Cell, strings: ExportStrings): string {
  if (cell.kind === 'blank') return strings.get('none');
  if (cell.kind === 'money') return rupees(cell.amount);
  return plain(cell);
}

/** `2026-09-22 10:15`, UTC. Kotlin: `ExportTime.dateTime`, with the offset fixed at 0 here. */
export function utcDateTime(epochMillis: number): string {
  return new Date(epochMillis).toISOString().slice(0, 16).replace('T', ' ');
}

/** `2026-09-22`, UTC. Kotlin: `ExportTime.date`. */
export function utcDate(epochMillis: number): string {
  return new Date(epochMillis).toISOString().slice(0, 10);
}

function millisOf(iso: string | null | undefined): number {
  if (!iso) return 0;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? 0 : ms;
}

function compare(a: string, b: string): number {
  // Code-unit comparison, not localeCompare: collation differs between browsers and would break determinism.
  return a < b ? -1 : a > b ? 1 : 0;
}
