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
import { describe, expect, it } from 'vitest';
import houseKind from '../../../../../docs/schemas/kinds/house.json';
import slotVectors from '../../../../../docs/schemas/kinds/house-ai-slot-vectors.json';
import { type AiHouse, type RawListing, houseText, sanitizeDraft, selectForPlan } from './ai-core';

/**
 * The slots of `docs/schemas/kinds/house.json` against the house's hand-written AI code (S4b-BL-271, docs/ai/ai-design.md
 * 16.8). A house is not read through its slots: its Ask document, Plan candidates and Extract draft are pinned byte for byte
 * (`house-ai-pins.spec.ts`) and stay as they are. This holds the slots to what that code carries, with the shared
 * `house-ai-slot-vectors.json`: a line or field the code writes that no slot names, or a slot that nothing carries, fails.
 * The twins: the server's `HouseAiSlotsTest` and the phone's `HouseAiSlotsTest`.
 */
const SLOTS = houseKind.ai as Record<'extract' | 'ask' | 'plan' | 'never', string[]>;
const idsOf = (map: Record<string, string[]>, keys: Iterable<string>) => [...new Set([...keys].flatMap((k) => map[k] ?? []))];

/** A house with every field the AI reads (the pins' rich house) and a visit, so every line of a document is written. */
const FULL: AiHouse = {
  id: '99999999-9999-4999-8999-999999999999', label: "Ramesh's 2BHK, Indiranagar", address: 'C/o Ramesh Kumar, 12 MG Road',
  street: 'MG Road', locality: 'Indiranagar', lat: 12.9731, lon: 77.6405, status: 'TAKEN', price: 28000, priceType: 'RENT',
  bedrooms: 2, rating: 4, contactName: 'Ramesh Kumar', contactPhone: '+91 98450 12345', listingUrl: 'https://example.com/l/9845012345',
  notes: 'Quiet lane.', areaSqft: 1150, floor: -1,
  cost: {
    deposit: 64000, depositMonths: 3, maintenance: 2500, maintenanceIncluded: false, brokerage: 30000, brokerageMonths: 1, lockInMonths: 11,
    noticeMonths: 2, availableFrom: '2026-10-15', agreedPrice: 31000, myOffer: 33333,
  },
  rooms: [{ id: 'k', type: 'KITCHEN', name: 'Kitchen', lengthCm: 300, widthCm: 244, notes: 'x', sort: 1 }],
  answers: [
    { id: 'a1', text: 'Any pets rule?', answer: 'No dogs', status: 'ANSWERED', sort: 0 },
    { id: 'a2', text: 'Is there a lift?', status: 'OPEN', sort: 1 },
  ],
  viewings: [{ id: 'v1', houseId: '99999999-9999-4999-8999-999999999999', startsAt: Date.parse('2026-10-02T05:00:00Z'), durationMin: 30, kind: 'SECOND', status: 'PLANNED', remindMin: 60, notes: 'Water bill' }],
  areaNotes: [{ id: 'n1', text: 'Quiet lanes', updatedAt: 5 }],
  distances: [{ name: 'Office', meters: 589.4 }],
  moveIn: { notes: 'Keys on Friday', items: [{ done: true }, { done: null }] },
  checklist: { water: 5 },
  visits: [{ arrivedAt: Date.parse('2026-09-14T10:00:00Z'), leftAt: Date.parse('2026-09-14T10:35:00Z') }],
} as AiHouse;

/** The label of an Ask line: the text before the first colon, `Distance to <place>` read as `Distance to`. */
const labelOf = (line: string): string => {
  const head = line.slice(0, line.indexOf(':'));
  return head.startsWith('Distance to ') ? 'Distance to' : head;
};

describe('house.json ai slots and the house\'s hand-written AI code (S4b-BL-271)', () => {
  it('Ask: every line of a house document is in the vectors, names only ask slot ids, and the slot has no id no line carries', () => {
    const labels = new Set(houseText(FULL).split('\n').filter((l) => /^[A-Z][A-Za-z -]*[^:]*: /.test(l)).map(labelOf));
    expect([...labels].sort()).toEqual(Object.keys(slotVectors.ask).sort());
    const ids = idsOf(slotVectors.ask, labels);
    for (const id of ids) expect(SLOTS.ask, id).toContain(id);
    expect([...SLOTS.ask].sort()).toEqual([...ids].sort());
    for (const never of SLOTS.never) expect(ids, never).not.toContain(never);
  });

  it('Ask: no line carries the contact or the person\'s own offer, whatever the slots say', () => {
    const text = houseText(FULL);
    for (const secret of ['Ramesh Kumar', '98450', '33333', 'example.com']) expect(text, secret).not.toContain(secret);
  });

  it('Plan: every field of a candidate is in the vectors, names only plan slot ids, and the slot has no id no field carries', () => {
    const [candidate] = selectForPlan([FULL], 12.97, 77.59);
    const fields = Object.keys(candidate).filter((k) => !slotVectors.planStructural.includes(k));
    expect(fields.sort()).toEqual(Object.keys(slotVectors.plan).sort());
    const ids = idsOf(slotVectors.plan, fields);
    for (const id of ids) expect(SLOTS.plan, id).toContain(id);
    expect([...SLOTS.plan].sort()).toEqual([...ids].sort());
    for (const never of SLOTS.never) expect(ids, never).not.toContain(never);
  });

  it('Extract: every field of a draft is in the vectors, names only extract slot ids, and what no draft carries is the no-AI parsers\' alone', () => {
    const raw = { label: 'x', address: 'a', street: 's', locality: 'l', price: '1', priceType: 'RENT', bedrooms: '2', contactName: 'n', contactPhone: '98450 12345', listingUrl: 'https://e.test', notes: 'n', amenities: ['lift'] } as RawListing;
    const fields = Object.keys(sanitizeDraft(raw, 'x 98450 12345 https://e.test')).filter((k) => !slotVectors.extractStructural.includes(k));
    expect(fields.sort()).toEqual(Object.keys(slotVectors.extract).sort());
    const ids = idsOf(slotVectors.extract, fields);
    for (const id of ids) expect(SLOTS.extract, id).toContain(id);
    expect(SLOTS.extract.filter((id) => !ids.includes(id))).toEqual([]);
  });

  it('the vectors name only ids of the kind file (a field, a section or a draft hint), and the unfilled one is the area', () => {
    const known = new Set([...houseKind.core, ...houseKind.extras].map((f) => f.id).concat(houseKind.sections.map((s) => s.id), ['questions', 'amenities']));
    for (const map of [slotVectors.ask, slotVectors.plan, slotVectors.extract]) for (const id of Object.values(map).flat()) expect(known.has(id), id).toBe(true);
    expect(slotVectors.noAiOnly).toEqual(['areaSqft']);
  });
});
