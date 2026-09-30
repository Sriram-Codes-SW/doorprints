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

import { CHECKLIST } from '../core/models';
// deterministic.ts holds the **one** implementation of the two cross-platform number rules: `rupees`/`indianGroup`
// (Indian digit grouping, minus before the ₹) and `fixed` (ties away from zero). This file used to carry its own
// `rupees` + `indianGroups` as well. The two agreed, but two copies of a formatting rule that has to match
// `ExportRows.kt` byte for byte are exactly the pair that drifts, and a drift would make a phone and a browser
// disagree about a price. `deterministic.spec.ts` is where both rules are pinned.
import { fixed, rupees } from './deterministic';
import type { ExportBundle, ExportHouse } from './export-model';
import { ExportStrings } from './export-strings';
import { photoFileName } from './photo-names';
import { brokerLine } from '../shared/broker';
import { isBuiltInKey } from '../shared/scoring';
import { costSummary } from '../shared/house-cost';
import { ordered } from '../shared/house-answers';
import type { PhotoRecord, VisitRecord } from '../data/records';

/**
 * **The format-independent model → rows logic.** The TypeScript half of
 * `android/shared/src/commonMain/kotlin/app/doorprints/shared/export/ExportRows.kt`, whose own comment is the
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

function maybeMoney(value: number | null | undefined): Cell {
  return value === null || value === undefined || !Number.isFinite(value) ? BLANK : cellMoney(Math.round(value));
}

/**
 * A table of the copy: one CSV file, one XLSX sheet, one Markdown or HTML table.
 *
 * `name` is language-neutral (the CSV file name and the sheet name); `title` is the translated heading.
 */
export interface ExportTable {
  readonly name: 'houses' | 'scores' | 'visits' | 'photos' | 'brokers' | 'rooms' | 'criteria' | 'answers' | 'viewings';
  readonly title: string;
  readonly columns: readonly string[];
  readonly rows: readonly (readonly Cell[])[];
}

/** The four tables of a copy, in file order, then brokers (slice 1b), rooms (1c), criteria (slice 2) answers (3a) and viewings (3b-1) when it has them. */
export function exportTables(bundle: ExportBundle): ExportTable[] {
  const tables = [housesTable(bundle), scoresTable(bundle), visitsTable(bundle), photosTable(bundle)];
  if (bundle.brokers.length > 0) tables.push(brokersTable(bundle));
  if (bundle.houses.some((h) => h.rooms?.length)) tables.push(roomsTable(bundle));
  // Slice 2: only a copy whose owner changed something has a criteria table (the records that exist).
  if (bundle.criteria.length > 0) tables.push(criteriaTable(bundle));
  // Slice 3a: the answers of the houses of the copy; the question bank is settings and travels in the backup only.
  if (bundle.houses.some((h) => h.answers.length > 0)) tables.push(answersTable(bundle));
  // Slice 3b-1: the viewings of the copy (a viewing of a house that is gone included in a copy of every house).
  if (bundle.viewings.length > 0) tables.push(viewingsTable(bundle));
  return tables;
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
    ...(contacts ? [s.get('col.contactName'), s.get('col.contactPhone'), s.get('col.broker')] : []),
    s.get('col.listingUrl'),
    s.get('col.notes'),
    // Slice 1a: the house values after the notes and before the counts and ids; the last three are computed
    // (`costSummary`, the same arithmetic as Kotlin `CostSummary`).
    s.get('col.areaSqft'),
    s.get('col.locationSource'),
    s.get('col.deposit'),
    s.get('col.depositMonths'),
    s.get('col.maintenance'),
    s.get('col.maintenanceIncluded'),
    s.get('col.brokerage'),
    s.get('col.brokerageMonths'),
    s.get('col.lockInMonths'),
    s.get('col.noticeMonths'),
    s.get('col.availableFrom'),
    s.get('col.myOffer'),
    s.get('col.agreedPrice'),
    s.get('col.monthlyCost'),
    s.get('col.moveIn'),
    s.get('col.perSqFt'),
    s.get('col.visits'),
    s.get('col.photos'),
    s.get('col.createdAt'),
    s.get('col.updatedAt'),
    s.get('col.id'),
  ];
  const rows = bundle.houses.map((entry) => {
    const h = entry.house;
    const c = h.cost ?? {};
    const summary = costSummary(h);
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
      ...(contacts ? [maybeText(h.contactName), maybeText(h.contactPhone), maybeText(brokerName(bundle, h.brokerId))] : []),
      maybeText(h.listingUrl),
      maybeText(h.notes),
      maybeCount(h.areaSqft),
      // The source is written as its enum word (GPS, MAP, APPROX), like a checklist key: a machine value.
      maybeText(h.locationSource),
      maybeMoney(c.deposit),
      maybeCount(c.depositMonths),
      maybeMoney(c.maintenance),
      typeof c.maintenanceIncluded === 'boolean' ? cellText(s.get(c.maintenanceIncluded ? 'yes' : 'no')) : BLANK,
      maybeMoney(c.brokerage),
      maybeCount(c.brokerageMonths),
      maybeCount(c.lockInMonths),
      maybeCount(c.noticeMonths),
      maybeText(c.availableFrom),
      maybeMoney(c.myOffer),
      maybeMoney(c.agreedPrice),
      maybeMoney(summary.monthlyCost),
      maybeMoney(summary.moveIn),
      summary.perSqFt === null ? BLANK : cellNum(summary.perSqFt, 1),
      cellCount(entry.visits.length),
      cellCount(entry.photos.length),
      maybeStamp(h.createdAt),
      maybeStamp(h.updatedAt),
      cellText(h.id),
    ];
  });
  return { name: 'houses', title: s.get('table.houses'), columns, rows };
}

/** The broker's name plus " (agency)" when it has one, for a house linked to a broker of the copy; else nothing. */
export function brokerName(bundle: ExportBundle, brokerId: string | null | undefined): string {
  const found = brokerId ? bundle.brokers.find((b) => b.id === brokerId) : undefined;
  return found ? brokerLine(found.broker) : '';
}

/** One row per broker of the copy, with the number of live houses of the copy that use it. */
export function brokersTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const columns = [
    s.get('col.name'),
    s.get('col.phone'),
    s.get('col.agency'),
    s.get('col.feeTerms'),
    s.get('col.notes'),
    s.get('col.rating'),
    s.get('col.houses'),
    s.get('col.id'),
  ];
  const rows = bundle.brokers.map((b) => [
    cellText(b.broker.name),
    maybeText(b.broker.phone),
    maybeText(b.broker.agency),
    maybeText(b.broker.feeTerms),
    maybeText(b.broker.notes),
    maybeCount(b.broker.rating),
    cellCount(b.houses.length),
    cellText(b.id),
  ]);
  return { name: 'brokers', title: s.get('table.brokers'), columns, rows };
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
      rows.push([cellText(h.label), cellText(key), cellText(criterionName(bundle, key)), cellCount(h.checklist[key]), cellText(h.id)]);
    }
  }
  return { name: 'scores', title: s.get('table.scores'), columns, rows };
}

/**
 * A criterion's name in the export language: a custom criterion's own label (slice 2; it used to fall back to the raw
 * key), a built-in's translated name, and the raw key for something a newer app wrote.
 */
export function criterionName(bundle: ExportBundle, key: string): string {
  const label = customLabels(bundle).get(key);
  return label ?? stringsOf(bundle).check(key);
}

/** The labels of the custom criteria of a copy, by key (built-ins keep their translated names). */
export function customLabels(bundle: ExportBundle): ReadonlyMap<string, string> {
  const out = new Map<string, string>();
  for (const c of bundle.scoring.criteria) if (!isBuiltInKey(c.key) && c.label) out.set(c.key, c.label);
  return out;
}

/**
 * One row per criterion of the effective scoring (the built-ins with their defaults too, so a reader sees every
 * weight), in the app's order: key, name, weight, must-have, minimum score, archived, order. Only a copy with criterion
 * records has it (`exportTables`).
 */
export function criteriaTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const columns = [
    s.get('col.item'),
    s.get('col.name'),
    s.get('col.weight'),
    s.get('col.mustHave'),
    s.get('col.minScore'),
    s.get('col.archived'),
    s.get('col.sort'),
  ];
  const rows = bundle.scoring.criteria.map((c) => [
    cellText(c.key),
    cellText(criterionName(bundle, c.key)),
    cellText(s.get(`weight.${c.weight}`)),
    cellText(s.get(c.mustHave ? 'yes' : 'no')),
    cellCount(c.minScore),
    cellText(s.get(c.archived ? 'yes' : 'no')),
    cellCount(c.sort),
  ]);
  return { name: 'criteria', title: s.get('table.criteria'), columns, rows };
}

/** "Rating counts for 40%": the rating share, when the copy carries any criterion or preference record; else empty. */
export function ratingShareLine(bundle: ExportBundle): string {
  if (bundle.criteria.length === 0 && bundle.preferences.length === 0) return '';
  return `${stringsOf(bundle).get('col.ratingShare')} ${Math.round(bundle.scoring.ratingShare * 100)}%`;
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

/** Room display cells for HTML/Markdown (one house), following the broker pattern of `brokerEntries`. */
export function roomCells(house: ExportHouse, unit: 'FT' | 'M', strings: ExportStrings): string[][] {
  if (!house.rooms?.length) return [];
  const isFeet = unit === 'FT';
  const rows: string[][] = [];
  let totalArea = 0;
  let sizedRoomCount = 0;

  for (const room of house.rooms) {
    const name = room.name?.trim() || strings.get(`roomType.${room.type}`);
    const type = strings.get(`roomType.${room.type}`);
    const length = room.lengthCm != null ? (isFeet ? (room.lengthCm / 30.48).toFixed(1) : (room.lengthCm / 100).toFixed(2)) : '';
    const width = room.widthCm != null ? (isFeet ? (room.widthCm / 30.48).toFixed(1) : (room.widthCm / 100).toFixed(2)) : '';
    let area = '';
    if (room.lengthCm != null && room.widthCm != null) {
      const areaValue = isFeet ? Math.round((room.lengthCm * room.widthCm) / 929.0304) : (room.lengthCm * room.widthCm) / 10000;
      area = isFeet ? String(areaValue) : areaValue.toFixed(1);
      totalArea += areaValue;
      sizedRoomCount++;
    }
    const condition = room.condition ? `${room.condition}/5` : '';
    const notes = room.notes?.trim() || '';
    rows.push([name, type, length, width, area, condition, notes]);
  }

  // Add total row when 2+ rooms have sizes (slice 1c contract)
  if (sizedRoomCount >= 2) {
    const totalAreaStr = isFeet ? String(Math.round(totalArea)) : totalArea.toFixed(1);
    rows.push([strings.get('rooms.total'), '', '', '', totalAreaStr, '', '']);
  }

  return rows;
}

/** Display columns for rooms in readable exports (HTML/Markdown): room name, type, dimensions, condition, notes. */
export function roomDisplayColumns(bundle: ExportBundle): string[] {
  const s = stringsOf(bundle);
  const isFeet = bundle.lengthUnit === 'FT';
  return [
    s.get('col.roomName'),
    s.get('col.roomType'),
    s.get(isFeet ? 'col.lengthFt' : 'col.lengthM'),
    s.get(isFeet ? 'col.widthFt' : 'col.widthM'),
    s.get(isFeet ? 'col.areaSqFt' : 'col.areaSqM'),
    s.get('col.condition'),
    s.get('col.notes'),
  ];
}

/** Rooms table columns for CSV/XLSX export (slice 1c): includes house and room identifiers. */
export function roomColumns(bundle: ExportBundle): string[] {
  const s = stringsOf(bundle);
  const isFeet = bundle.lengthUnit === 'FT';
  return [
    s.get('col.house'),
    s.get('col.roomName'),
    s.get('col.roomType'),
    s.get(isFeet ? 'col.lengthFt' : 'col.lengthM'),
    s.get(isFeet ? 'col.widthFt' : 'col.widthM'),
    s.get(isFeet ? 'col.areaSqFt' : 'col.areaSqM'),
    s.get('col.condition'),
    s.get('col.notes'),
    s.get('col.houseId'),
    s.get('col.id'),
  ];
}

/** Rooms table for CSV/XLSX export, built on `roomCells`. Only included when the copy has rooms. */
export function roomsTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const isFeet = bundle.lengthUnit === 'FT';
  const columns = roomColumns(bundle);
  const rows: Cell[][] = [];
  for (const house of bundle.houses) {
    if (!house.rooms?.length) continue;
    for (const room of house.rooms) {
      rows.push([
        cellText(house.house.label),
        cellText(room.name?.trim() || s.get(`roomType.${room.type}`)),
        cellText(s.get(`roomType.${room.type}`)),
        room.lengthCm != null ? cellNum(isFeet ? room.lengthCm / 30.48 : room.lengthCm / 100, isFeet ? 1 : 2) : BLANK,
        room.widthCm != null ? cellNum(isFeet ? room.widthCm / 30.48 : room.widthCm / 100, isFeet ? 1 : 2) : BLANK,
        room.lengthCm != null && room.widthCm != null
          ? cellNum(isFeet ? (room.lengthCm * room.widthCm) / 929.0304 : (room.lengthCm * room.widthCm) / 10000, isFeet ? 0 : 1)
          : BLANK,
        room.condition != null ? cellCount(room.condition) : BLANK,
        room.notes ? cellText(room.notes) : BLANK,
        cellText(house.house.id),
        cellText(room.id),
      ]);
    }
  }
  return { name: 'rooms', title: s.get('table.rooms'), columns, rows };
}

/** The word for an answer's status in the export language. */
function answerStatus(status: string, strings: ExportStrings): string {
  return status === 'ANSWERED' || status === 'SKIPPED' ? strings.get(`answerStatus.${status}`) : strings.get('answerStatus.OPEN');
}

/** Display columns of the Questions section in HTML/Markdown: question, answer, status. */
export function answerDisplayColumns(bundle: ExportBundle): string[] {
  const s = stringsOf(bundle);
  return [s.get('col.question'), s.get('col.answer'), s.get('col.status')];
}

/**
 * The rows of a house's Questions section (HTML, Markdown, PDF): question, answer ("–" when empty) and the translated
 * status, open ones first (`ordered`). Empty when the house has no answers.
 */
export function answerCells(house: ExportHouse, strings: ExportStrings): string[][] {
  return ordered(house.answers).map((a) => [a.text, a.answer?.trim() ? a.answer : '–', answerStatus(a.status, strings)]);
}

/** The Answers table for CSV/XLSX (slice 3a): house, question, answer, status, then the three ids. */
export function answersTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const columns = [
    s.get('col.house'),
    s.get('col.question'),
    s.get('col.answer'),
    s.get('col.status'),
    s.get('col.houseId'),
    s.get('col.id'),
    s.get('col.questionId'),
  ];
  const rows: Cell[][] = [];
  for (const house of bundle.houses) {
    for (const a of ordered(house.answers)) {
      rows.push([
        cellText(house.house.label),
        cellText(a.text),
        maybeText(a.answer),
        cellText(answerStatus(a.status, s)),
        cellText(house.house.id),
        cellText(a.id),
        maybeText(a.questionId),
      ]);
    }
  }
  return { name: 'answers', title: s.get('table.answers'), columns, rows };
}

/** Display columns of the Viewings section in HTML/Markdown: when, kind, status, notes, and with whom (only with contact details). */
export function viewingDisplayColumns(bundle: ExportBundle): string[] {
  const s = stringsOf(bundle);
  return [s.get('col.when'), s.get('col.kind'), s.get('col.status'), s.get('col.notes'), ...(bundle.options.includeContacts ? [s.get('col.withWhom')] : [])];
}

/**
 * The rows of a house's Viewings section (HTML, Markdown, PDF): date and time (UTC like the other dates), kind and status
 * translated, the notes ("–" when empty) and, only with contact details, with whom. In the order `ExportHouse.viewings`
 * holds them: upcoming PLANNED first, then the rest newest first. Empty when the house has no viewing.
 */
export function viewingCells(house: ExportHouse, strings: ExportStrings, includeContacts: boolean): string[][] {
  return house.viewings.map((v) => [
    utcDateTime(v.startsAt),
    strings.viewingKind(v.kind),
    strings.viewingStatus(v.status),
    v.notes ? v.notes : '–',
    ...(includeContacts ? [v.withWhom ? v.withWhom : '–'] : []),
  ]);
}

/**
 * The Viewings table for CSV/XLSX (slice 3b-1): house (label, blank when the house is gone), when, duration, kind,
 * status, reminder minutes, with whom (blank without contact details), notes, then the house, viewing and visit ids.
 * By `startsAt`, then id.
 */
export function viewingsTable(bundle: ExportBundle): ExportTable {
  const s = stringsOf(bundle);
  const columns = [
    s.get('col.house'),
    s.get('col.when'),
    s.get('col.durationMin'),
    s.get('col.kind'),
    s.get('col.status'),
    s.get('col.remindMin'),
    s.get('col.withWhom'),
    s.get('col.notes'),
    s.get('col.houseId'),
    s.get('col.id'),
    s.get('col.visitId'),
  ];
  const labels = new Map(bundle.houses.map((h) => [h.house.id, h.house.label]));
  const rows = bundle.viewings
    .map((r) => r.viewing)
    .sort((a, b) => a.startsAt - b.startsAt || compare(a.id, b.id))
    .map((v) => [
      maybeText(labels.get(v.houseId)),
      cellStamp(v.startsAt),
      cellCount(v.durationMin),
      cellText(s.viewingKind(v.kind)),
      cellText(s.viewingStatus(v.status)),
      cellCount(v.remindMin),
      bundle.options.includeContacts ? maybeText(v.withWhom) : BLANK,
      maybeText(v.notes),
      cellText(v.houseId),
      cellText(v.id),
      maybeText(v.visitId),
    ]);
  return { name: 'viewings', title: s.get('table.viewings'), columns, rows };
}

/** Columns of the Area notes section in HTML/Markdown: the text, then where it comes from (slice 4a). */
export function areaNoteDisplayColumns(bundle: ExportBundle): string[] {
  const s = stringsOf(bundle);
  return [s.get('col.notes'), s.get('col.noteSource')];
}

/** The rows of a house's Area notes section (newest first, as `ExportHouse.areaNotes` holds them); empty when none reach it. */
export function areaNoteCells(house: ExportHouse): string[][] {
  return house.areaNotes.map((n) => [n.text, n.source]);
}

/** Columns of the Distances section: the place, then the straight-line kilometres with one decimal (slice 4a). */
export function distanceDisplayColumns(bundle: ExportBundle): string[] {
  const s = stringsOf(bundle);
  return [s.get('col.place'), s.get('col.km')];
}

/** The rows of a house's Distances section, nearest first; empty for a house with no point or without places. */
export function distanceCells(house: ExportHouse): string[][] {
  return house.distances.map((d) => [d.place.name, d.km]);
}

function compare(a: string, b: string): number {
  // Code-unit comparison, not localeCompare: collation differs between browsers and would break determinism.
  return a < b ? -1 : a > b ? 1 : 0;
}
