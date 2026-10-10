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
  | 'areaSqft'
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
  areaSqft: 'house.areaSqft',
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

/**
 * What a listing fill wants to change: the field values to write, the fields that were empty and are now filled, and
 * the values it kept out.
 */
export interface FillResult {
  changes: Partial<HouseDto>;
  /** Fields that were empty and are now filled, in form order. */
  filled: FillField[];
  kept: KeptValue[];
}

function isEmpty(v: FieldValue): boolean {
  return v === null || v === undefined || (typeof v === 'string' && v.trim() === '');
}

/** Whether two values are equal; text ignores case and surrounding spaces. */
export function same(a: FieldValue, b: FieldValue): boolean {
  if (typeof a === 'string' && typeof b === 'string') return a.trim().toLowerCase() === b.trim().toLowerCase();
  return a === b;
}

/**
 * The lines that bracket what a listing fill wrote into the notes (S4b-BL-238): a second fill replaces that block instead
 * of appending another, and the person's own notes around it are never touched. The same two lines on the phones
 * (`HouseFormRules.kt`), so a house synced between them keeps one block.
 */
export const LISTING_NOTES_START = '--- from listing ---';
export const LISTING_NOTES_END = '--- end of listing ---';

/**
 * `current` notes with the listing block set to `block`: an existing block (from the start line to the end line, or to
 * the end of the notes when the end line is missing) is replaced, otherwise the block is appended after the person's
 * notes. An empty `block` removes the block. Idempotent: the same block twice gives the same notes.
 */
export function withListingBlock(current: string | null | undefined, block: string): string {
  const notes = current ?? '';
  const start = notes.indexOf(LISTING_NOTES_START);
  const wrapped = block.trim() ? `${LISTING_NOTES_START}\n${block.trim()}\n${LISTING_NOTES_END}` : '';
  if (start < 0) return [notes.trim(), wrapped].filter((x) => x).join('\n');
  const endAt = notes.indexOf(LISTING_NOTES_END, start);
  const after = endAt < 0 ? '' : notes.slice(endAt + LISTING_NOTES_END.length);
  return [notes.slice(0, start).trim(), wrapped, after.trim()].filter((x) => x).join('\n');
}

/**
 * "Fill in from listing text" fills only the **empty** fields (AI-004: a suggestion never overwrites what the user
 * typed). Where the listing disagrees with a value already in the form, the typed value stays and the listing's
 * value is reported in `kept`, so the page can say "Kept your Name; the listing says …".
 *
 * `priceType` counts as empty while there is no price yet: a new form starts on "Rent", which is a default, not a
 * choice. The listing's description and amenities go into the notes as one marked block ({@link withListingBlock}),
 * after the person's own notes; a second fill replaces that block, so running Extract twice never duplicates text.
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
  consider('areaSqft', draft.areaSqft, isEmpty(current.areaSqft));
  consider('contactName', draft.contactName, isEmpty(current.contactName));
  consider('contactPhone', draft.contactPhone, isEmpty(current.contactPhone));
  consider('listingUrl', draft.listingUrl, isEmpty(current.listingUrl));
  const block = [draft.notes, draft.amenities?.length ? draft.amenities.join(', ') : null]
    .filter((x): x is string => !!x && !!x.trim())
    .join('\n');
  if (block) {
    const notes = withListingBlock(current.notes, block);
    if (notes !== (current.notes ?? '')) changes.notes = notes;
  }
  return { changes, filled, kept };
}

/** What reverse geocoding found for the pin. */
export interface AddressLookup {
  address?: string | null;
  street?: string | null;
  locality?: string | null;
}

/** A typed address part the lookup would change: shown to the person, who decides. */
export interface AddressConflict {
  field: 'address' | 'street' | 'locality';
  old: string;
  incoming: string;
}

/** What "Fill address from map" would write: empty fields are filled at once, typed ones are listed as conflicts. */
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
