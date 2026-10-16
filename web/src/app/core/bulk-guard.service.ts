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

import { Injectable } from '@angular/core';

/** The pause between two bulk actions on one device, in milliseconds (deletion safeguards, docs/02 T-D15). */
export const BULK_MS = 10_000;
const KEY = 'doorprints.bulkAt';

/**
 * One bulk action per 10 seconds per device (deletion safeguards PR 2): *Delete all in this space*, *Remove all data* and
 * *Delete all saved walks* each ask {@link tryStart} first. A slip, a double click or a script cannot empty one space
 * after another in a blink; the person has the time of the Undo to see what happened. The time is kept in this browser's
 * local storage so a second tab shares it, and in memory when storage is blocked.
 */
@Injectable({ providedIn: 'root' })
export class BulkGuard {
  private last = 0;

  /** True and starts the pause when no bulk action ran in the last 10 seconds; false otherwise (nothing is started). */
  tryStart(now: number = Date.now()): boolean {
    if (this.waitSeconds(now) > 0) return false;
    this.last = now;
    try {
      localStorage.setItem(KEY, String(now));
    } catch {
      // Storage blocked: the memory above still holds the pause for this tab.
    }
    return true;
  }

  /** Whole seconds left of the pause, 0 when a bulk action may start. */
  waitSeconds(now: number = Date.now()): number {
    let at = this.last;
    try {
      at = Math.max(at, Number(localStorage.getItem(KEY)) || 0);
    } catch {
      // Storage blocked.
    }
    const left = at + BULK_MS - now;
    return left > 0 && left <= BULK_MS ? Math.ceil(left / 1000) : 0;
  }
}
