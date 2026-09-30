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

import { RECORD_ID_PATTERN } from '../data/records';

/**
 * Viewings (docs/11 5.8, slice 3b-1 of the Sprint 4b data model): a planned or logged visit to a house at a time. A
 * viewing is a record of type `viewing` whose id is the viewing's own key (`v_` and 8 lowercase hex characters). The
 * pure part, the twin of Kotlin `Viewing`/`Viewings` in android/shared: reading a payload, the derived "missed" state,
 * the visit a viewing may be linked to, the timeline groups and the search.
 */

export const VIEWING_TYPE = 'viewing';
/** The record cap of the store: at most this many live viewings. */
export const MAX_VIEWINGS = 5_000;
export const MAX_WITH_WHOM = 200;
export const MAX_VIEWING_NOTES = 2_000;
/** A house or visit id is at most this long (the record-id pattern); a longer one is untrusted. */
export const MAX_ID_LENGTH = 64;
export const DEFAULT_DURATION_MIN = 30;
export const MIN_DURATION_MIN = 5;
export const MAX_DURATION_MIN = 480;
export const DEFAULT_REMIND_MIN = 60;
/** How long after its end a PLANNED viewing counts as missed, and how far from a visit's arrival it may be linked. */
export const TWO_HOURS_MS = 2 * 60 * 60 * 1000;

export type ViewingKind = 'FIRST' | 'SECOND' | 'FOLLOW_UP';
export type ViewingStatus = 'PLANNED' | 'DONE' | 'CANCELLED';

export const VIEWING_KINDS: readonly ViewingKind[] = ['FIRST', 'SECOND', 'FOLLOW_UP'];
export const VIEWING_STATUSES: readonly ViewingStatus[] = ['PLANNED', 'DONE', 'CANCELLED'];
/** Minutes before the start; 0 is no reminder. */
export const REMIND_OPTIONS: readonly number[] = [0, 15, 30, 60, 120, 1440];

/** A viewing's own id: `v_` and 8 lowercase hex characters. */
const APP_ID_PATTERN = /^v_[0-9a-f]{8}$/;

export interface Viewing {
  id: string;
  /** A house id; the house may be gone (the history then says "a house that is gone"). */
  houseId: string;
  /** Epoch milliseconds, > 0. */
  startsAt: number;
  /** 5..480. */
  durationMin: number;
  kind: ViewingKind;
  status: ViewingStatus;
  /** One of {@link REMIND_OPTIONS}. */
  remindMin: number;
  /** Written only when true (used by slice 3c; no screen sets it yet). */
  huntReminder?: true;
  /** 1..200, CONTACT DATA: never in a calendar file, the AI text or a copy made without contact details. */
  withWhom?: string;
  /** 1..2000. */
  notes?: string;
  /** The visit that made it DONE. */
  visitId?: string;
}

/** A stored viewing record, as read. */
export interface ViewingRow {
  id: string;
  updatedAt: string | null;
  viewing: Viewing;
}

/** The part of a visit the link needs. */
export interface VisitLike {
  id: string;
  houseId?: string | null;
  arrivedAt: string;
  deleted?: boolean;
}

/** True for an id the app makes (`v_` and 8 hex). */
export function isAppViewingId(id: string): boolean {
  return APP_ID_PATTERN.test(id);
}

/** True when `id` can be a record id at all (`.` and `..` are path segments, so never). */
export function isViewingId(id: string): boolean {
  return RECORD_ID_PATTERN.test(id) && id !== '.' && id !== '..';
}

export function viewingKind(value: unknown): ViewingKind {
  return VIEWING_KINDS.includes(value as ViewingKind) ? (value as ViewingKind) : 'FIRST';
}

export function viewingStatus(value: unknown): ViewingStatus {
  return VIEWING_STATUSES.includes(value as ViewingStatus) ? (value as ViewingStatus) : 'PLANNED';
}

function optionalText(value: unknown, max: number): string | undefined {
  return typeof value === 'string' && value.trim() !== '' && value.length <= max ? value : undefined;
}

/**
 * Reads a record payload as a viewing. A blank or missing `houseId`, a missing or non-positive `startsAt`, or an id
 * outside the record-id pattern is untrusted, so the row is `null` and the caller skips it. An unknown kind is FIRST
 * and an unknown status PLANNED, a duration outside 5..480 is 30, a reminder not in the list 60; an over-long or
 * blank `withWhom`, `notes` or `visitId` is left out. Kotlin: `Viewings.fromPayload`.
 */
export function viewingFromPayload(id: string, payload: Record<string, unknown> | null | undefined): Viewing | null {
  if (!isViewingId(id) || !payload || typeof payload !== 'object') return null;
  const houseId = payload['houseId'];
  if (typeof houseId !== 'string' || houseId.trim() === '' || houseId.length > MAX_ID_LENGTH) return null;
  const startsAt = payload['startsAt'];
  if (typeof startsAt !== 'number' || !Number.isSafeInteger(startsAt) || startsAt <= 0) return null;
  const duration = payload['durationMin'];
  const remind = payload['remindMin'];
  const out: Viewing = {
    id,
    houseId,
    startsAt,
    durationMin:
      typeof duration === 'number' && Number.isInteger(duration) && duration >= MIN_DURATION_MIN && duration <= MAX_DURATION_MIN
        ? duration
        : DEFAULT_DURATION_MIN,
    kind: viewingKind(payload['kind']),
    status: viewingStatus(payload['status']),
    remindMin: typeof remind === 'number' && REMIND_OPTIONS.includes(remind) ? remind : DEFAULT_REMIND_MIN,
  };
  if (payload['huntReminder'] === true) out.huntReminder = true;
  const withWhom = optionalText(payload['withWhom'], MAX_WITH_WHOM);
  if (withWhom !== undefined) out.withWhom = withWhom;
  const notes = optionalText(payload['notes'], MAX_VIEWING_NOTES);
  if (notes !== undefined) out.notes = notes;
  const visitId = optionalText(payload['visitId'], MAX_ID_LENGTH);
  if (visitId !== undefined) out.visitId = visitId;
  return out;
}

/** The payload keys in the contract's order: houseId, startsAt, durationMin, kind, status, remindMin, then the optional ones. */
export function viewingToPayload(v: Viewing): Record<string, unknown> {
  const out: Record<string, unknown> = {
    houseId: v.houseId,
    startsAt: v.startsAt,
    durationMin: v.durationMin,
    kind: v.kind,
    status: v.status,
    remindMin: v.remindMin,
  };
  if (v.huntReminder === true) out['huntReminder'] = true;
  if (v.withWhom) out['withWhom'] = v.withWhom;
  if (v.notes) out['notes'] = v.notes;
  if (v.visitId) out['visitId'] = v.visitId;
  return out;
}

const compareIds = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);

/** By `startsAt`, then id (code-unit order, so the same on every device). */
export function sortViewings<T extends { startsAt: number; id: string }>(list: readonly T[]): T[] {
  return [...list].sort((a, b) => a.startsAt - b.startsAt || compareIds(a.id, b.id));
}

/** `v_` and 8 random lowercase hex characters: a viewing's id. */
export function newViewingId(): string {
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  return 'v_' + Array.from(bytes, (x) => x.toString(16).padStart(2, '0')).join('');
}

/**
 * Derived, never stored: a PLANNED viewing whose end (start plus duration) was more than two hours before `nowMs`. Two
 * devices never fight over it, because nothing writes it. Kotlin: `Viewings.missed`.
 */
export function viewingMissed(v: Viewing, nowMs: number): boolean {
  return v.status === 'PLANNED' && v.startsAt + v.durationMin * 60_000 < nowMs - TWO_HOURS_MS;
}

/** The earliest PLANNED viewing of the house that starts at or after `nowMs`, or null. */
export function nextViewingOf(list: readonly Viewing[], houseId: string, nowMs: number): Viewing | null {
  const coming = list.filter((v) => v.houseId === houseId && v.status === 'PLANNED' && v.startsAt >= nowMs);
  return sortViewings(coming)[0] ?? null;
}

/**
 * The visit at the viewing's house whose arrival is within two hours (before or after) of the start, the closest one
 * (an equal distance: the earlier arrival, then the smaller id); null if none. A visit of another house, or one that
 * is deleted, is ignored. Kotlin: `Viewings.suggestedVisitFor`.
 */
export function suggestedVisitFor<T extends VisitLike>(viewing: Viewing, visits: readonly T[]): T | null {
  let best: T | null = null;
  let bestGap = Infinity;
  for (const visit of visits) {
    if (visit.deleted || visit.houseId !== viewing.houseId) continue;
    const at = Date.parse(visit.arrivedAt);
    if (Number.isNaN(at)) continue;
    const gap = Math.abs(at - viewing.startsAt);
    if (gap > TWO_HOURS_MS) continue;
    const bestAt = best ? Date.parse(best.arrivedAt) : 0;
    if (gap < bestGap || (gap === bestGap && (at < bestAt || (at === bestAt && best !== null && compareIds(visit.id, best.id) < 0)))) {
      best = visit;
      bestGap = gap;
    }
  }
  return best;
}

/**
 * The PLANNED viewing of a house that the house card offers *Mark viewing done* for: the latest one that has a visit
 * near it, with that visit; null when none.
 */
export function markableViewing<T extends VisitLike>(
  ofHouse: readonly Viewing[],
  visits: readonly T[],
): { viewing: Viewing; visit: T } | null {
  for (const viewing of sortViewings(ofHouse.filter((v) => v.status === 'PLANNED')).reverse()) {
    const visit = suggestedVisitFor(viewing, visits);
    if (visit) return { viewing, visit };
  }
  return null;
}

/** The timeline groups of the Viewings screen. */
export interface ViewingGroups {
  /** PLANNED and not missed, soonest first. */
  upcoming: Viewing[];
  /** PLANNED and missed ("Missed?"), newest first. */
  missed: Viewing[];
  done: Viewing[];
  cancelled: Viewing[];
}

export function groupViewings(list: readonly Viewing[], nowMs: number): ViewingGroups {
  const asc = sortViewings(list);
  const newestFirst = [...asc].reverse();
  return {
    upcoming: asc.filter((v) => v.status === 'PLANNED' && !viewingMissed(v, nowMs)),
    missed: newestFirst.filter((v) => viewingMissed(v, nowMs)),
    done: newestFirst.filter((v) => v.status === 'DONE'),
    cancelled: newestFirst.filter((v) => v.status === 'CANCELLED'),
  };
}

/** The Viewings screen's filters; a blank field is no filter. Dates are epoch milliseconds (from: start of day, to: end of day). */
export interface ViewingFilter {
  from?: number | null;
  to?: number | null;
  kind?: ViewingKind | '';
  status?: ViewingStatus | '';
}

export function filterViewings(list: readonly Viewing[], f: ViewingFilter): Viewing[] {
  return list.filter(
    (v) =>
      (f.from == null || v.startsAt >= f.from) &&
      (f.to == null || v.startsAt <= f.to) &&
      (!f.kind || v.kind === f.kind) &&
      (!f.status || v.status === f.status),
  );
}

/** The house values the search reads (a house that is gone is `null`). */
export interface ViewingHouseText {
  label?: string | null;
  street?: string | null;
  locality?: string | null;
}

/**
 * The Viewings screen's search: a case-insensitive substring of the house label, street or locality, or of the
 * viewing's `withWhom` or `notes`. A blank query matches everything. (The house list's search does not read viewings.)
 */
export function viewingMatches(v: Viewing, house: ViewingHouseText | null | undefined, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === '') return true;
  return [house?.label, house?.street, house?.locality, v.withWhom, v.notes].some((s) => !!s && s.toLowerCase().includes(q));
}

/** The Viewings of a copy's house page: upcoming PLANNED (from `nowMs`) soonest first, then the rest newest first. */
export function viewingsForCopy(list: readonly Viewing[], nowMs: number): Viewing[] {
  const asc = sortViewings(list);
  const upcoming = asc.filter((v) => v.status === 'PLANNED' && v.startsAt >= nowMs);
  return [...upcoming, ...asc.filter((v) => !upcoming.includes(v)).reverse()];
}
