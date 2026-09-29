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
 * The result or error of one run (Ask, Plan visits, Connect's check), numbered so that its card can be keyed on the
 * run: `@for (r of [card]; track r.run)`.
 *
 * Why (UX, cross-team parity with Android 1.33 `RefreshableResultCard`): a retry keeps the earlier card in place,
 * drawn as being updated (`.refresh-slot.stale`, styles.css), instead of clearing it first and making the content
 * below jump up by its height and back. A card that stays in place only changes its text, and a live region whose
 * text does not change is not read again, so a second failure with the same words would go unsaid. Keyed on the
 * run, each new result is a new node in its live region and is announced, as Android keys its card on the run.
 */
export interface RunResult<T> {
  readonly value: T;
  readonly run: number;
}

let lastRun = 0;

/** Wraps a new run's result or error with a number no earlier run had. */
export function runResult<T>(value: T): RunResult<T> {
  lastRun += 1;
  return { value, run: lastRun };
}

/**
 * The result of a run the user did **not** ask for (a re-read after a sync pull or an edit in another tab) keeps the
 * run of the card already on screen, so a background failure repeating itself is not read out again on every pull;
 * a changed text in the same node is still announced. A run the user asked for (Retry) is always a new run.
 */
export function nextRunResult<T>(previous: RunResult<T> | null, value: T, userAsked: boolean): RunResult<T> {
  return previous !== null && !userAsked ? { value, run: previous.run } : runResult(value);
}
