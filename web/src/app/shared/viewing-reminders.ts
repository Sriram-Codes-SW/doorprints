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

import type { Viewing } from './viewing';

/** The most reminders kept at once (iOS keeps 64 pending; the phones and the website share the number). */
export const MAX_REMINDERS = 60;

/** A viewing and the moment its reminder is due. */
export interface Reminder {
  viewing: Viewing;
  /** Epoch milliseconds: `startsAt` minus `remindMin` minutes. */
  fireAt: number;
}

/**
 * When the viewing's reminder is due, or null when it has none: only a PLANNED viewing with `remindMin` > 0 has one.
 * Kotlin twin: `ViewingReminders` in android/shared.
 */
export function reminderAt(v: Viewing): number | null {
  return v.status === 'PLANNED' && v.remindMin > 0 ? v.startsAt - v.remindMin * 60_000 : null;
}

/**
 * The reminders still to come (docs/11 5.8, slice 3b-2): `fireAt` after `nowMs`, earliest first, ties by id, at most
 * `limit`. A reminder whose time has passed is never sent late.
 */
export function upcomingReminders(viewings: readonly Viewing[], nowMs: number, limit: number = MAX_REMINDERS): Reminder[] {
  const out: Reminder[] = [];
  for (const viewing of viewings) {
    const fireAt = reminderAt(viewing);
    if (fireAt !== null && fireAt > nowMs) out.push({ viewing, fireAt });
  }
  out.sort((a, b) => a.fireAt - b.fireAt || (a.viewing.id < b.viewing.id ? -1 : a.viewing.id > b.viewing.id ? 1 : 0));
  return out.slice(0, Math.max(0, limit));
}

/** The reminders that came due after `afterMs` up to and including `nowMs` (what one timer tick shows). */
export function dueReminders(viewings: readonly Viewing[], afterMs: number, nowMs: number): Reminder[] {
  const out: Reminder[] = [];
  for (const viewing of viewings) {
    const fireAt = reminderAt(viewing);
    if (fireAt !== null && fireAt > afterMs && fireAt <= nowMs) out.push({ viewing, fireAt });
  }
  return out.sort((a, b) => a.fireAt - b.fireAt || (a.viewing.id < b.viewing.id ? -1 : 1));
}
