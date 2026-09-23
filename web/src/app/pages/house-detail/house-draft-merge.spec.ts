import { describe, expect, it } from 'vitest';
import type { HouseDraft } from '../../core/ai.service';
import { HouseDto, newHouse } from '../../core/models';
import { addressFill, mergeListingDraft } from './house-draft-merge';
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

  it('adds the description and amenities to the notes without replacing them', () => {
    const result = mergeListingDraft(form({ notes: 'Owner is friendly' }), draft({ notes: 'Semi-furnished', amenities: ['Lift', 'Gym'] }));
    expect(result.changes.notes).toBe('Owner is friendly\nSemi-furnished\nLift, Gym');
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
