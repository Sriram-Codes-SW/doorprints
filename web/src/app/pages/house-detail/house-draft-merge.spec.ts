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
import type { HouseDraft } from '../../core/ai.service';
import { HouseDto, newHouse } from '../../core/models';
import { SCHOOL_LABELS } from '../../core/school-kind';
import { addressFill, fillLabel, mergeListingDraft, withListingBlock } from './house-draft-merge';
import { HOUSE_LABELS } from '../../core/kind-labels';
import { DRAFT_PREFIX, draftKey, parseStoredDraft } from './draft-store';

function draft(partial: Partial<HouseDraft>): HouseDraft {
  return {
    label: null,
    address: null,
    street: null,
    locality: null,
    price: null,
    priceType: null,
    bedrooms: null,
    areaSqft: null,
    contactName: null,
    contactPhone: null,
    listingUrl: null,
    notes: null,
    amenities: [],
    warnings: [],
    ...partial,
  };
}

function form(partial: Partial<HouseDto> = {}): HouseDto {
  return { ...newHouse(12.97, 77.59), ...partial };
}

describe('"Fill in from listing text"', () => {
  it('fills the empty fields', () => {
    const result = mergeListingDraft(form(), draft({ label: '2BHK near park', price: 25000, contactPhone: '98450 12345' }));
    expect(result.changes).toMatchObject({ label: '2BHK near park', price: 25000, contactPhone: '98450 12345' });
    expect(result.kept).toEqual([]);
  });

  it('never overwrites what the user typed, and reports every value it kept', () => {
    const current = form({ label: 'Blue gate house', price: 22000, contactPhone: '99000 11111' });
    const result = mergeListingDraft(
      current,
      draft({ label: '2BHK near park', price: 25000, contactPhone: '98450 12345', locality: 'Indiranagar' }),
    );
    expect(result.changes['label']).toBeUndefined();
    expect(result.changes['price']).toBeUndefined();
    expect(result.changes['contactPhone']).toBeUndefined();
    expect(result.changes['locality']).toBe('Indiranagar');
    expect(result.kept).toEqual([
      { field: 'label', incoming: '2BHK near park' },
      { field: 'price', incoming: 25000 },
      { field: 'contactPhone', incoming: '98450 12345' },
    ]);
  });

  it('does not report a value that only differs in case or spaces', () => {
    const result = mergeListingDraft(form({ street: '12th Main ' }), draft({ street: '12th main' }));
    expect(result.kept).toEqual([]);
  });

  it('treats the default "Rent" of a form with no price as empty', () => {
    expect(mergeListingDraft(form(), draft({ priceType: 'SALE' })).changes['priceType']).toBe('SALE');
    const typed = mergeListingDraft(form({ price: 5_000_000, priceType: 'RENT' }), draft({ priceType: 'SALE' }));
    expect(typed.changes['priceType']).toBeUndefined();
    expect(typed.kept).toEqual([{ field: 'priceType', incoming: 'SALE' }]);
  });

  it('adds the description and amenities to the notes as a marked block, after what the person wrote', () => {
    const result = mergeListingDraft(form({ notes: 'Owner is friendly' }), draft({ notes: 'Semi-furnished', amenities: ['Lift', 'Gym'] }));
    expect(result.changes.notes).toBe('Owner is friendly\n--- from listing ---\nSemi-furnished\nLift, Gym\n--- end of listing ---');
  });

  it('replaces the block on a second fill instead of appending it, and leaves the notes around it alone (S4b-BL-238)', () => {
    const once = mergeListingDraft(form({ notes: 'Owner is friendly' }), draft({ notes: 'Semi-furnished', amenities: ['Lift'] }));
    const notesAfterOnce = once.changes.notes!;
    const again = mergeListingDraft(form({ notes: notesAfterOnce }), draft({ notes: 'Semi-furnished', amenities: ['Lift'] }));
    expect(again.changes.notes).toBeUndefined();
    const edited = mergeListingDraft(form({ notes: `${notesAfterOnce}\nCalled on Monday` }), draft({ notes: 'Fully furnished', amenities: ['Lift', 'Gym'] }));
    expect(edited.changes.notes).toBe('Owner is friendly\n--- from listing ---\nFully furnished\nLift, Gym\n--- end of listing ---\nCalled on Monday');
    // A block whose end line the person deleted runs to the end of the notes.
    expect(withListingBlock('Mine\n--- from listing ---\nold text', 'new')).toBe('Mine\n--- from listing ---\nnew\n--- end of listing ---');
    expect(withListingBlock(null, 'new')).toBe('--- from listing ---\nnew\n--- end of listing ---');
    expect(withListingBlock('Mine\n--- from listing ---\nold\n--- end of listing ---', '')).toBe('Mine');
  });
});

describe('"Fill address from map"', () => {
  it('fills empty fields, and the name from the street when there is none', () => {
    const result = addressFill(form(), { address: '12, 5th Cross', street: '5th Cross', locality: 'Jayanagar' });
    expect(result.emptyOnly).toEqual({ address: '12, 5th Cross', street: '5th Cross', locality: 'Jayanagar', label: '5th Cross' });
    expect(result.filled).toEqual(['label', 'address', 'street', 'locality']);
    expect(result.conflicts).toEqual([]);
  });

  it('lists typed values it would change instead of replacing them', () => {
    const result = addressFill(form({ label: 'Mine', street: 'Temple Road' }), { street: '5th Cross', locality: 'Jayanagar' });
    expect(result.emptyOnly).toEqual({ locality: 'Jayanagar' });
    expect(result.conflicts).toEqual([{ field: 'street', old: 'Temple Road', incoming: '5th Cross' }]);
  });
});

describe('the unsaved-draft store', () => {
  it('keys a draft by house id, or by the position a new house was opened at', () => {
    expect(draftKey('abc', null, null)).toBe(DRAFT_PREFIX + 'abc');
    expect(draftKey(null, '12.970000', '77.590000')).toBe(DRAFT_PREFIX + 'new:12.970000,77.590000');
    expect(draftKey(null, null, null)).toBe(DRAFT_PREFIX + 'new:');
  });

  it('reads back what it wrote and refuses anything else', () => {
    const house = form({ label: 'Half typed' });
    expect(parseStoredDraft(JSON.stringify({ draft: house, locationSet: false }))).toEqual({ draft: house, locationSet: false });
    expect(parseStoredDraft(null)).toBeNull();
    expect(parseStoredDraft('{')).toBeNull();
    expect(parseStoredDraft(JSON.stringify({ draft: { label: 'x' } }))).toBeNull();
  });
});

// S4b-BL-256: a listing fill reads the candidate's kind: its labels in "Kept your …", and no write into a place the kind hides.
describe('"Fill in from listing text" for a school (S4b-BL-256)', () => {
  it('names a field by the kind’s label: a school’s name and website, the house’s street and address', () => {
    expect(fillLabel('label', SCHOOL_LABELS)).toBe('school.name');
    expect(fillLabel('listingUrl', SCHOOL_LABELS)).toBe('school.website');
    expect(fillLabel('street', SCHOOL_LABELS)).toBe('house.street');
    expect(fillLabel('label', HOUSE_LABELS)).toBe('house.name');
    expect(fillLabel('listingUrl', HOUSE_LABELS)).toBe('house.listingUrl');
  });

  it('the house’s label for every fill field is what it was (the kind’s key, or the house’s own for the price type)', () => {
    const all = ['label', 'address', 'street', 'locality', 'price', 'priceType', 'bedrooms', 'areaSqft', 'contactName', 'contactPhone', 'listingUrl'] as const;
    expect(all.map((f) => fillLabel(f, HOUSE_LABELS))).toEqual([
      'house.name', 'house.address', 'house.street', 'house.locality', 'house.price', 'house.priceType', 'house.bhk', 'house.areaSqft',
      'house.contactName', 'house.contactPhone', 'house.listingUrl',
    ]);
  });

  it('writes nothing into the price, the type, the BHK or the area a school hides, and fills what it shows', () => {
    const result = mergeListingDraft(
      form({ kind: 'school', priceType: null }),
      draft({ label: 'Pine Hill School', price: 96000, priceType: 'RENT', bedrooms: 2, areaSqft: 900, locality: 'Dispur', listingUrl: 'https://pine.example' }),
      SCHOOL_LABELS,
    );
    expect(result.changes).toEqual({ label: 'Pine Hill School', locality: 'Dispur', listingUrl: 'https://pine.example' });
    expect(result.filled).toEqual(['label', 'locality', 'listingUrl']);
    expect(result.kept).toEqual([]);
    // The house, with the same listing, takes all of it.
    const house = mergeListingDraft(form(), draft({ label: 'X', price: 96000, priceType: 'RENT', bedrooms: 2, areaSqft: 900 }));
    expect(house.filled).toEqual(['label', 'price', 'priceType', 'bedrooms', 'areaSqft']);
  });
});
