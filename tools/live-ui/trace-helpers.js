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

// The pure parts of the live-ui `--trace` scenario (docs/06 TC-M-26, S4b-FR-24), tested by tools/live-ui-trace.test.mjs.
const METRES_PER_DEGREE = 111194.9266; // the radius of the app's own plane (6 371 000 m)

/** `n` places going north from (lat, lon), `stepM` metres apart: a street walked one fix at a time. */
function walkSteps(lat, lon, n, stepM) {
  return Array.from({ length: n }, (_, i) => ({ latitude: lat + (i * stepM) / METRES_PER_DEGREE, longitude: lon }));
}

/** A place `eastM` metres east of `p` (at the equator's scale of a degree; exact enough at 13 degrees for a 100 m offset test). */
function eastOf(p, eastM) {
  const cos = Math.cos((p.latitude * Math.PI) / 180);
  return { latitude: p.latitude, longitude: p.longitude + eastM / (METRES_PER_DEGREE * cos) };
}

/** Map tiles, styles, glyphs and sprites come from OpenFreeMap: the only requests the Map makes by itself while it is looked at. Host only: a beacon to another host with a .png name is still a request. */
function isMapAsset(url, base) {
  return /(^|\.)openfreemap\.org$/.test(new URL(url, base).hostname);
}

/** The requests of a click that are not map assets and not the page's own blob and data URLs: for *Have I been here?* there must be none. */
function nonTileRequests(urls, base) {
  return urls.filter((url) => !/^(blob|data):/.test(url) && !isMapAsset(url, base));
}

/** The short weekday the answer shows for today, in English (`Wed`), as `Intl` writes it for en-IN. */
function todayWeekday(now = new Date()) {
  return new Intl.DateTimeFormat('en-IN', { weekday: 'short' }).format(now);
}

module.exports = { METRES_PER_DEGREE, walkSteps, eastOf, isMapAsset, nonTileRequests, todayWeekday };
