import type { HouseDraft } from '../../core/ai.service';
import type { HouseDto } from '../../core/models';
import type { TKey } from '../../i18n/en';

/** The form fields "Fill in from listing text" and "Fill address from map" may write, with their label keys. */
export type FillField =
  | 'label'
  | 'address'
  | 'street'
  | 'locality'
  | 'price'
  | 'priceType'
  | 'bedrooms'
  | 'contactName'
  | 'contactPhone'
  | 'listingUrl';

export const FIELD_LABEL: Readonly<Record<FillField, TKey>> = {
  label: 'house.name',
  address: 'house.address',
  street: 'house.street',
  locality: 'house.locality',
  price: 'house.price',
  priceType: 'house.priceType',
  bedrooms: 'house.bhk',
  contactName: 'house.contactName',
  contactPhone: 'house.contactPhone',
  listingUrl: 'house.listingUrl',
};

type FieldValue = string | number | null | undefined;

/** A value the fill did not write because the user had already typed something else there. */
export interface KeptValue {
  field: FillField;
  /** What the listing (or the map) says instead. */
  incoming: string | number;
}

export interface FillResult {
  changes: Partial<HouseDto>;
  /** Fields that were empty and are now filled, in form order. */
  filled: FillField[];
  kept: KeptValue[];
}

function isEmpty(v: FieldValue): boolean {
  return v === null || v === undefined || (typeof v === 'string' && v.trim() === '');
}

function same(a: FieldValue, b: FieldValue): boolean {
  if (typeof a === 'string' && typeof b === 'string') return a.trim().toLowerCase() === b.trim().toLowerCase();
  return a === b;
}

/**
 * "Fill in from listing text" fills only the **empty** fields (AI-004: a suggestion never overwrites what the user
 * typed). Where the listing disagrees with a value already in the form, the typed value stays and the listing's
 * value is reported in `kept`, so the page can say "Kept your Name; the listing says …".
 *
 * `priceType` counts as empty while there is no price yet: a new form starts on "Rent", which is a default, not a
 * choice. The listing's description and amenities are appended to the notes, never replacing them.
 */
export function mergeListingDraft(current: HouseDto, draft: HouseDraft): FillResult {
  const changes: Partial<HouseDto> = {};
  const filled: FillField[] = [];
  const kept: KeptValue[] = [];
  const consider = (field: FillField, incoming: FieldValue, emptyNow: boolean) => {
    if (isEmpty(incoming)) return;
    const value = incoming as string | number;
    if (emptyNow) {
      (changes as Record<string, unknown>)[field] = value;
      filled.push(field);
    } else if (!same(current[field] as FieldValue, value)) {
      kept.push({ field, incoming: value });
    }
  };
  consider('label', draft.label, isEmpty(current.label));
  consider('address', draft.address, isEmpty(current.address));
  consider('street', draft.street, isEmpty(current.street));
  consider('locality', draft.locality, isEmpty(current.locality));
  consider('price', draft.price, isEmpty(current.price));
  consider('priceType', draft.priceType, isEmpty(current.priceType) || isEmpty(current.price));
  consider('bedrooms', draft.bedrooms, isEmpty(current.bedrooms));
  consider('contactName', draft.contactName, isEmpty(current.contactName));
  consider('contactPhone', draft.contactPhone, isEmpty(current.contactPhone));
  consider('listingUrl', draft.listingUrl, isEmpty(current.listingUrl));
  const extra = [draft.notes, draft.amenities?.length ? draft.amenities.join(', ') : null].filter(
    (x): x is string => !!x && !!x.trim() && !(current.notes ?? '').includes(x.trim()),
  );
  if (extra.length > 0) {
    changes.notes = [current.notes, ...extra].filter((x): x is string => !!x && !!x.trim()).join('\n');
  }
  return { changes, filled, kept };
}

/** What reverse geocoding found for the pin. */
export interface AddressLookup {
  address?: string | null;
  street?: string | null;
  locality?: string | null;
}

export interface AddressConflict {
  field: 'address' | 'street' | 'locality';
  old: string;
  incoming: string;
}

export interface AddressFill {
  /** Writes into empty fields only (and the name, when it is empty, from the street). */
  emptyOnly: Partial<HouseDto>;
  filled: FillField[];
  /** Typed values the lookup would change; the page asks before replacing any of them. */
  conflicts: AddressConflict[];
}

/** "Fill address from map": fills the empty fields, and lists the typed ones it would change. */
export function addressFill(current: HouseDto, found: AddressLookup): AddressFill {
  const emptyOnly: Partial<HouseDto> = {};
  const filled: FillField[] = [];
  const conflicts: AddressConflict[] = [];
  for (const field of ['address', 'street', 'locality'] as const) {
    const incoming = found[field];
    if (isEmpty(incoming)) continue;
    const old = current[field];
    if (isEmpty(old)) {
      emptyOnly[field] = incoming;
      filled.push(field);
    } else if (!same(old, incoming)) {
      conflicts.push({ field, old: (old as string).trim(), incoming: (incoming as string).trim() });
    }
  }
  if (isEmpty(current.label) && !isEmpty(found.street)) {
    emptyOnly.label = found.street as string;
    filled.unshift('label');
  }
  return { emptyOnly, filled, conflicts };
}
