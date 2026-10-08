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
 * The words of *Have I been here?* as data (docs/11 5.27.13, docs/03 section 6.2b row 13): which sentence, which days,
 * which distance, which rows. Pure parts only: `placeCheckSummary` decides what is said (no language, no clock),
 * `placeCheckText` writes it through the app's helpers (the dictionary, `TranslationService.list`, `dateWithWeekday` and
 * `metres`). Nothing here stores, logs or sends anything.
 */

import type { TKey } from '../i18n/en';
import type { TranslationService } from '../i18n/translation.service';
import { TRACE } from './trace-geo';
import type { PlaceCheckResult, PlaceRow, PlaceStatus } from './trace-place-check';

/** What was asked about: where I am now, a house, or a spot on the map. */
export type PlaceKind = 'here' | 'house' | 'spot';

/** At most this many days in the headline and this many rows under it. */
export const MAX_HEADLINE_DAYS = 3;
export const MAX_ROWS = 5;

/** The calendar day (`YYYY-MM-DD`) of an epoch time in a time zone (the device's when absent). */
export function dayKey(epochMs: number, timeZone?: string): string {
  return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone }).format(new Date(epochMs));
}

/** What {@link placeCheckSummary} needs besides the result. */
export interface PlaceSummaryOptions {
  /** True when at least one saved walk exists (the *none* sentence then says so). */
  readonly hasSaved: boolean;
  /** The fix's reported accuracy (the *here* source), for the loose-fix line. */
  readonly fixAccuracyM?: number | null;
  /** The time zone the days are read in (the device's by default; a test passes one). */
  readonly timeZone?: string;
}

/** What the sheet says, with no language in it. */
export interface PlaceSummary {
  readonly status: PlaceStatus;
  readonly hasSaved: boolean;
  /** The time of the newest row of each listed day, newest first, at most {@link MAX_HEADLINE_DAYS}. */
  readonly days: readonly number[];
  /** The distinct days beyond the listed ones. */
  readonly moreDays: number;
  /** The largest distance among the rows on the listed days, rounded up to a whole metre, at least 1; null without rows. */
  readonly distanceM: number | null;
  /** One row per walk of the headline's band, newest first, at most {@link MAX_ROWS}. */
  readonly rows: readonly { readonly atMs: number; readonly distanceM: number; readonly saved: boolean }[];
  /** The rows beyond {@link MAX_ROWS}. */
  readonly rowsMore: number;
  /** The accuracy to show in the loose-fix line, rounded; null when there is no such line. */
  readonly fuzzyM: number | null;
}

const wholeMetres = (d: number) => Math.max(1, Math.ceil(d));

/**
 * Decides what the answer says, with no words in it: the days and the distance for the headline, the rows to list, and
 * the loose-fix accuracy. Only the walks in the headline's band are listed: within the tolerance for WALKED, all nearby
 * ones for CLOSE.
 */
export function placeCheckSummary(result: PlaceCheckResult, options: PlaceSummaryOptions): PlaceSummary {
  const band: readonly PlaceRow[] = result.status === 'WALKED' ? result.rows.filter((r) => r.walked) : result.status === 'CLOSE' ? result.rows : [];
  // Distinct days of the headline's band, newest first (the rows are already newest first).
  const firstOfDay = new Map<string, number>();
  for (const r of band) if (!firstOfDay.has(dayKey(r.atMs, options.timeZone))) firstOfDay.set(dayKey(r.atMs, options.timeZone), r.atMs);
  const dayKeys = [...firstOfDay.keys()];
  const listed = dayKeys.slice(0, MAX_HEADLINE_DAYS);
  const onListed = band.filter((r) => listed.includes(dayKey(r.atMs, options.timeZone)));
  const showsFuzzy = result.fuzzy && (result.status === 'WALKED' || result.status === 'CLOSE' || result.status === 'NONE');
  const accuracy = options.fixAccuracyM ?? null;
  return {
    status: result.status,
    hasSaved: options.hasSaved,
    days: listed.map((k) => firstOfDay.get(k)!),
    moreDays: dayKeys.length - listed.length,
    distanceM: onListed.length === 0 ? null : wholeMetres(Math.max(...onListed.map((r) => r.distanceM))),
    rows: band.slice(0, MAX_ROWS).map((r) => ({ atMs: r.atMs, distanceM: wholeMetres(r.distanceM), saved: r.saved })),
    rowsMore: Math.max(0, band.length - MAX_ROWS),
    fuzzyM: showsFuzzy && accuracy !== null ? Math.round(accuracy) : null,
  };
}

/** What the text needs from the app: the dictionary (keys as plain strings), the list joiner, the date and the distance. */
export interface PlaceTextHelpers {
  t(key: string, params?: Readonly<Record<string, string | number>>): string;
  list(items: readonly string[]): string;
  dateWithWeekday(epochMs: number, timeZone?: string): string;
  metres(n: number): string;
}

/** The app's {@link TranslationService} as helpers. The `trace.here.*` keys are added to the dictionaries by the UI change. */
export function placeTextHelpers(i18n: TranslationService, timeZone?: string): PlaceTextHelpers {
  return {
    t: (key, params) => i18n.t(key as TKey, params),
    list: (items) => i18n.list(items),
    dateWithWeekday: (ms) => i18n.dateWithWeekday(ms, timeZone),
    metres: (n) => i18n.metres(n),
  };
}

/**
 * The answer as sentences in the app language: the headline, one line per walk, the count of rows left out, and the
 * notes after them.
 */
export interface PlaceText {
  readonly headline: string;
  readonly rows: readonly string[];
  readonly rowsMore: string | null;
  /** The lines after the rows: what the answer covers, and the loose-fix warning. */
  readonly notes: readonly string[];
}

const PLACE_KEY: Record<PlaceKind, string> = { here: 'trace.here.placeHere', house: 'trace.here.placeHouse', spot: 'trace.here.placeSpot' };

/** The sentences of {@link PlaceSummary} in the app language. `web` picks the website's empty and negative-answer lines. */
export function placeCheckText(summary: PlaceSummary, kind: PlaceKind, i18n: PlaceTextHelpers, web: boolean, timeZone?: string): PlaceText {
  const place = i18n.t(PLACE_KEY[kind]);
  const dates = () => {
    const joined = i18n.list(summary.days.map((d) => i18n.dateWithWeekday(d, timeZone)));
    return summary.moreDays > 0 ? i18n.t('trace.here.andMore', { dates: joined, n: summary.moreDays }) : joined;
  };
  const tolerance = i18n.metres(TRACE.toleranceM);
  let headline: string;
  switch (summary.status) {
    case 'WALKED':
      headline = i18n.t('trace.here.walked', { distance: i18n.metres(summary.distanceM ?? 1), place, dates: dates() });
      break;
    case 'CLOSE':
      headline = i18n.t('trace.here.close', { tolerance, place, distance: i18n.metres(summary.distanceM ?? 1), dates: dates() });
      break;
    case 'NONE':
      headline = i18n.t(summary.hasSaved ? 'trace.here.noneSaved' : 'trace.here.none', { tolerance, place });
      break;
    case 'EMPTY':
      headline = i18n.t(web ? 'trace.here.emptyWeb' : 'trace.here.empty');
      break;
    case 'IMPRECISE':
      headline = i18n.t('trace.here.imprecise');
      break;
    case 'INVALID_PLACE':
      headline = i18n.t('trace.here.invalid');
      break;
  }
  const notes: string[] = [];
  if (summary.status === 'CLOSE' || summary.status === 'NONE') {
    notes.push(i18n.t('trace.here.onlyRecorded'));
    if (web) notes.push(i18n.t('trace.here.onlyRecordedWeb'));
  }
  if (summary.fuzzyM !== null) notes.push(i18n.t('trace.here.fuzzy', { n: summary.fuzzyM }));
  return {
    headline,
    rows: summary.rows.map((r) =>
      i18n.t(r.saved ? 'trace.here.rowSaved' : 'trace.here.row', { date: i18n.dateWithWeekday(r.atMs, timeZone), distance: i18n.metres(r.distanceM) }),
    ),
    rowsMore: summary.rowsMore > 0 ? i18n.t('trace.here.rowsMore', { n: summary.rowsMore }) : null,
    notes,
  };
}
