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
 * Focuses the element with this id when focus has fallen to <body>, which is where it goes when the focused
 * control is removed (Cancel on Ask and Plan when the request ends, "Save anyway" on Connect when a new check
 * succeeds). Focus the user has already moved elsewhere is left alone. Call it after the render that removed the
 * control, e.g. `afterNextRender(() => focusIfLost('ask-submit'), { injector })` (WCAG 2.4.3).
 */
export function focusIfLost(id: string, doc: Document = document): void {
  const active = doc.activeElement;
  if (active === null || active === doc.body) doc.getElementById(id)?.focus();
}
