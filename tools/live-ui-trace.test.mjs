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

import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import test from 'node:test';

const require = createRequire(import.meta.url);
const { METRES_PER_DEGREE, walkSteps, eastOf, isMapAsset, nonTileRequests, todayWeekday } = require('./live-ui/trace-helpers.js');

const haversine = (a, b) => {
  const rad = (d) => (d * Math.PI) / 180;
  const dLat = rad(b.latitude - a.latitude);
  const dLon = rad(b.longitude - a.longitude);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(h));
};

test('walkSteps goes north in steps of the given length, so every step passes the 20 m thinning of the recorder', () => {
  const steps = walkSteps(12.9716, 77.5946, 30, 22);
  assert.equal(steps.length, 30);
  assert.equal(steps[0].latitude, 12.9716);
  for (let i = 1; i < steps.length; i++) {
    assert.ok(Math.abs(haversine(steps[i - 1], steps[i]) - 22) < 0.01, `step ${i}`);
    assert.equal(steps[i].longitude, 77.5946);
  }
});

test('eastOf puts a place the given distance east, along a line of latitude', () => {
  const p = { latitude: 12.9716, longitude: 77.5946 };
  assert.ok(Math.abs(haversine(p, eastOf(p, 100)) - 100) < 0.5);
  assert.equal(eastOf(p, 100).latitude, p.latitude);
});

test('a tile, a style and a glyph from OpenFreeMap are map assets; the site\'s own API and any other host are not', () => {
  const base = 'https://doorprints.web.app';
  assert.equal(isMapAsset('https://tiles.openfreemap.org/planet/20260101/5/12/12.pbf', base), true);
  assert.equal(isMapAsset('https://tiles.openfreemap.org/styles/liberty', base), true);
  assert.equal(isMapAsset('https://example.com/data.json', base), false);
  assert.equal(isMapAsset('https://example.com/tile.png', base), false);
  assert.equal(isMapAsset('https://openfreemap.org.example.com/x', base), false);
  assert.equal(isMapAsset('/api/houses', base), false);
});

test('nonTileRequests keeps what a place check must not make and drops the map\'s own requests, blob and data URLs', () => {
  const base = 'https://doorprints.web.app';
  const urls = [
    'https://tiles.openfreemap.org/planet/1/2/3.pbf',
    'blob:https://doorprints.web.app/abc',
    'data:image/png;base64,AAAA',
    'https://doorprints.web.app/api/anything',
    'https://example.org/beacon',
  ];
  assert.deepEqual(nonTileRequests(urls, base), ['https://doorprints.web.app/api/anything', 'https://example.org/beacon']);
  assert.deepEqual(nonTileRequests([], base), []);
});

test('todayWeekday is the three-letter English weekday, as the answer writes it', () => {
  assert.equal(todayWeekday(new Date(Date.UTC(2026, 9, 7, 6, 0, 0))), 'Wed');
  assert.equal(todayWeekday(new Date(Date.UTC(2026, 9, 5, 6, 0, 0))), 'Mon');
});

test('the degree is the radius of the app\'s own plane, so a step here is a step there', () => {
  assert.ok(Math.abs(METRES_PER_DEGREE - (6371000 * Math.PI) / 180) < 1);
});
