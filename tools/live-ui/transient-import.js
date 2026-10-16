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

// The pure part of how live-ui.js treats a lazy route chunk that failed to load (docs/06 TC-M-26), tested by
// tools/live-ui-transient.test.mjs. A failed dynamic import is a fault of the network when the chunk's URL answers
// right on a second fetch (a 502/503/504 or a dropped connection a moment before); it is a failure when it does not.

/** The chunk URL of a console or page error "Failed to fetch dynamically imported module: <url>", or null. */
function dynamicImportUrl(text) {
  const m = /Failed to fetch dynamically imported module: (\S+)/.exec(text);
  return m ? m[1] : null;
}

/** True when [text] is that error for a URL in [cleared] (the URLs whose fault was transient). */
function isClearedImportError(text, cleared) {
  const url = dynamicImportUrl(text);
  return url !== null && cleared.has(url);
}

module.exports = { dynamicImportUrl, isClearedImportError };
