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
 * How much of the house list shows under the phone map at the top of the page, in px: from the top of the list's
 * heading to the foot of its counters, so "Your houses" and every counter, number and caption, are on screen above
 * the bottom bar before any scrolling (owner report 2026-09-24: on a 384x615 Android phone the counters' captions were
 * under the bottom bar). Without counters (still reading the houses) the heading alone.
 *
 * `head` is the heading block's box and `stats` the counters' (null when there are none), both from
 * getBoundingClientRect(); the result is rounded up so a fraction of a pixel never tucks a caption under the bar.
 */
export function listPeek(head: { top: number; bottom: number }, stats: { bottom: number } | null): number {
  const bottom = stats ? Math.max(stats.bottom, head.bottom) : head.bottom;
  return Math.max(0, Math.ceil(bottom - head.top));
}
