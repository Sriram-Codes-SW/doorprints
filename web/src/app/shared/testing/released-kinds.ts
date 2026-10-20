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

// Test helper (ADR-13b, 2026-10-19): the website released the school, so a kind that is *listed but not released* (the case
// *Experimental: more than houses* exists for) has no real example left. A spec of that mechanism, or of a rule the shared
// vectors pin for the phones (which still release the house alone), pins the released set to the house for its cases; the
// website's own set is back after each (`unpinReleased`). Never used by production code.
import { RELEASED_KINDS } from '../../core/kind-setting';

/** The website's real released set, read once at load. */
export const WEB_RELEASED: readonly string[] = [...RELEASED_KINDS];

/** Makes `ids` the released kinds until {@link unpinReleased} (the house alone by default: the school is then listed, unreleased). */
export function pinReleased(ids: readonly string[] = ['house']): void {
  (RELEASED_KINDS as string[]).splice(0, RELEASED_KINDS.length, ...ids);
}

/** Puts the website's real released set back. */
export function unpinReleased(): void {
  pinReleased(WEB_RELEASED);
}
