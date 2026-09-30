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
 * Which navigation item stands for the page on screen (the app shell's header and bottom bar). Pure, so it is unit
 * tested without the shell.
 */

/** The map and the pages under it: a house and the new-house form (Back goes to the map; Android nests them too). */
export function inMapSection(path: string): boolean {
  return path === '/' || path === '/houses' || path.startsWith('/houses/');
}

/**
 * A page under the map (a house, the new-house form) rather than the map itself: phones hide the bottom bar there and
 * give the form the whole screen, with the toolbar's Back to the map, as Android shows its NavigationBar only on tab
 * destinations (Root.kt). UX lead audit, round 3.
 */
export function hidesBottomBar(path: string): boolean {
  return inMapSection(path) && path !== '/';
}

/**
 * Your data, the Brokers pages linked from it, and on phones also Connect, which has no item of its own in the bottom
 * bar and lives in Your data.
 */
export function inDataSection(path: string, phone: boolean): boolean {
  return path.startsWith('/data') || path.startsWith('/brokers') || (phone && path.startsWith('/connect'));
}

/**
 * `aria-current` of an item that stands for a section: "page" on the section's own page, "true" on a page inside it
 * (a house under the map, Connect inside Your data on phones), and none elsewhere.
 */
export function sectionCurrent(onOwnPage: boolean, inSection: boolean): 'page' | 'true' | null {
  if (onOwnPage) return 'page';
  return inSection ? 'true' : null;
}
