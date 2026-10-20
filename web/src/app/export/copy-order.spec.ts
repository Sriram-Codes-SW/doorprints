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

// The order of a copy's blocks follows the kind file (docs/03 §18.28): `capabilities.copyOrder` names the blocks that come first after the
// details, the rest follow in the copies' fixed order. The Markdown and the readable (HTML) copy are held to the shared vectors
// (`capabilities-vectors.json`, `copyOrder`) and to the synthetic kind's file; a kind with no order, the house and the school, is as it was
// (`house-pins.spec.ts` holds the house's copies byte for byte).
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import vectorsJson from '../../../../docs/schemas/kinds/capabilities-vectors.json';
import { COPY_BLOCKS, copyBlockOrder, parseCapabilities, registerTestCapabilities } from '../core/kind-capabilities';
import { drawnKind } from '../core/kind-setting';
import { tryHouseFromDto, type HouseRecord } from '../data/records';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { forKind } from '../shared/testing/kind-flows';
import { DEFAULT_EXPORT_OPTIONS, collect } from './export-model';
import { buildHtml } from './html-export';
import { buildMarkdown } from './markdown-export';

const V = (vectorsJson as unknown as { copyOrder: { blocks: string[]; effective: { copyOrder: string[]; order: string[] }[] } }).copyOrder;
const dict = DICTIONARIES.en;

describe('the order of a copy’s blocks (docs/03 §18.28)', () => {
  let flow: ReturnType<typeof forKind>;
  beforeEach(() => {
    flow = forKind('zz_all');
  });
  afterEach(() => flow.release());

  it('has the blocks and the orders the shared vectors have', () => {
    expect([...COPY_BLOCKS]).toEqual(V.blocks);
    for (const [i, c] of V.effective.entries()) {
      const undo = registerTestCapabilities(`zz_order${i}`, parseCapabilities({ copyOrder: c.copyOrder })!);
      expect([...copyBlockOrder(`zz_order${i}`)], JSON.stringify(c.copyOrder)).toEqual(c.order);
      undo();
    }
    // The house, the school and a kind the build does not know keep the fixed order.
    for (const id of ['house', 'school', 'nothing', null, undefined]) expect([...copyBlockOrder(id)]).toEqual(V.blocks);
    expect([...copyBlockOrder('zz_all')].slice(0, 3)).toEqual(['visits', 'notes', 'cost']);
  });

  function copies(order: boolean) {
    if (!order) registerTestCapabilities('zz_all', { ...flow.caps, copyOrder: [] });
    const house: HouseRecord = tryHouseFromDto(flow.house(0))!;
    const visit = { id: 'v1', houseId: house.id, lat: 13, lon: 80.25, street: 'Lake Road', arrivedAt: '2026-10-02T10:00:00.000Z', leftAt: '2026-10-02T10:30:00.000Z', source: 'MANUAL', deleted: false, syncVersion: 0, dirty: false };
    const bundle = collect({
      houses: [house], visits: [visit as never], photos: [], exportedAt: '2026-10-14T10:00:00.000Z',
      kinds: { fieldsOf: (kd) => drawnKind(kd).fields, dict },
      options: { ...DEFAULT_EXPORT_OPTIONS, lang: 'en', space: 'zz_all' },
      inSpace: () => true,
    });
    const md = buildMarkdown(bundle, dict);
    const html = buildHtml(bundle, dict, new Map());
    const heads = (text: string, open: string, close: string) =>
      [...text.matchAll(new RegExp(`${open}(.+?)${close}`, 'g'))].map((m) => m[1]);
    return { md: heads(md, '### ', '\\n'), html: heads(html, '<h3>', '</h3>') };
  }

  it('writes the synthetic kind’s blocks in its order in the Markdown and the readable copy, and in the fixed order without it', () => {
    const cost = dict[flow.kind().keys.ref('section.cost') as never] as string;
    const want = [dict['house.visits'], dict['house.notes'], cost];
    const ordered = copies(true);
    const own = (heads: string[]) => heads.filter((h) => want.includes(h));
    expect(own(ordered.md), 'Markdown').toEqual(want);
    expect(own(ordered.html), 'HTML').toEqual(want);
  });

  it('keeps the fixed order for a kind with no order: the cost, then the visits, then the notes', () => {
    const cost = dict[flow.kind().keys.ref('section.cost') as never] as string;
    const want = [cost, dict['house.visits'], dict['house.notes']];
    const fixed = copies(false);
    const own = (heads: string[]) => heads.filter((h) => want.includes(h));
    expect(own(fixed.md), 'Markdown').toEqual(want);
    expect(own(fixed.html), 'HTML').toEqual(want);
  });
});
