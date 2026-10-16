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


// India's boundaries for every kind (kinds PR 32, docs/03 section 18.26, TC-M-25 per-kind clause): a kind with the map capability draws the same
// map as the house. The boundary rules take a style and a URL, never a kind, so no kind (the synthetic one with every capability on included) can
// change a line; this runs the rules with each kind registered and compares the result with the house's, byte for byte.
import type { StyleSpecification } from 'maplibre-gl';
import { afterEach, describe, expect, it } from 'vitest';
import { applyIndiaBoundaries, type BoundaryStyleTarget, indiaBoundaryStyle } from './india-boundaries';
import { libertyExcerpt } from './testing/liberty-style.fixture';
import { FLOW_KINDS, forKind } from './testing/kind-flows';

const URL_ = 'https://doorprints.web.app/geo/in-boundaries.geojson';
const drawn = () => {
  let style = libertyExcerpt();
  const target: BoundaryStyleTarget = {
    getStyle: () => style,
    setStyle: (s: StyleSpecification) => { style = s; },
  } as unknown as BoundaryStyleTarget;
  applyIndiaBoundaries(target, URL_, () => undefined);
  return JSON.stringify({ style, built: indiaBoundaryStyle(libertyExcerpt(), URL_).style });
};

describe('India boundaries are the same for every kind that shows a map', () => {
  const house = drawn();
  let release = () => undefined as void;
  afterEach(() => release());

  it('the rules take no kind: a style and a URL only', () => {
    expect(indiaBoundaryStyle.length).toBeLessThanOrEqual(3);
    expect(applyIndiaBoundaries.length).toBeLessThanOrEqual(3);
  });

  for (const id of FLOW_KINDS) {
    it(`${id}: the map is on and the lines are the house's, byte for byte`, () => {
      const flow = forKind(id);
      release = flow.release;
      expect(flow.caps.map).toBe(true);
      expect(drawn()).toBe(house);
      expect(house).toContain('in-boundaries');
    });
  }
});
