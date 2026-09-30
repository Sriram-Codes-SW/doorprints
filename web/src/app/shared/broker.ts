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

/**
 * A broker (or owner): a contact of its own that houses link to (docs/11 5.25, slice 1b of the Sprint 4b data
 * model). It is a record of type `broker`: the record id is the broker's UUID and the payload holds exactly the
 * keys below, all optional except `name`; absent means unknown, never `null`. The twin of Kotlin `Broker` in
 * android/shared (`BrokerTest`, `broker.spec.ts`), and the key names the server's `BackupService` stores.
 */
export interface Broker {
  /** 1..200 characters. */
  name: string;
  /** At most 50 characters, as the person typed it. */
  phone?: string;
  /** At most 200 characters. */
  agency?: string;
  /** Free text, at most 500 characters: "15 days' rent, once". */
  feeTerms?: string;
  /** At most 2000 characters. */
  notes?: string;
  /** Stars, 1..5. */
  rating?: number;
}

/** The `type` of a broker's record envelope. */
export const BROKER_TYPE = 'broker';

export const MAX_BROKER_NAME = 200;
export const MAX_BROKER_PHONE = 50;
export const MAX_BROKER_AGENCY = 200;
export const MAX_BROKER_FEE_TERMS = 500;
export const MAX_BROKER_NOTES = 2000;

/** A stored broker: the record id, the last edit (ISO, as the envelope has it) and the fields. */
export interface BrokerRow {
  id: string;
  updatedAt: string | null;
  broker: Broker;
}

/** The payload keys in the order every writer keeps them. */
const KEYS = ['name', 'phone', 'agency', 'feeTerms', 'notes', 'rating'] as const;

/**
 * Reads a record payload as a broker. Out-of-range values are unknown (the key is dropped), like every coerced
 * value; a blank or oversized name leaves nothing to show, so the row is `null` and the caller skips it as untrusted.
 */
export function brokerFromPayload(payload: Record<string, unknown> | null | undefined): Broker | null {
  if (!payload || typeof payload !== 'object') return null;
  const name = payload['name'];
  if (typeof name !== 'string' || name.trim() === '' || name.length > MAX_BROKER_NAME) return null;
  const out: Broker = { name };
  const phone = text(payload['phone'], MAX_BROKER_PHONE);
  if (phone !== undefined) out.phone = phone;
  const agency = text(payload['agency'], MAX_BROKER_AGENCY);
  if (agency !== undefined) out.agency = agency;
  const feeTerms = text(payload['feeTerms'], MAX_BROKER_FEE_TERMS);
  if (feeTerms !== undefined) out.feeTerms = feeTerms;
  const notes = text(payload['notes'], MAX_BROKER_NOTES);
  if (notes !== undefined) out.notes = notes;
  const rating = stars(payload['rating']);
  if (rating !== undefined) out.rating = rating;
  return out;
}

/** The payload of a broker: only the set keys, in {@link KEYS} order (blank text is unknown, so it is left out). */
export function brokerToPayload(broker: Broker): Record<string, unknown> {
  const clean = brokerFromPayload({ ...broker }) ?? broker;
  const out: Record<string, unknown> = {};
  for (const key of KEYS) {
    const value = clean[key];
    if (value !== undefined) out[key] = value;
  }
  return out;
}

/** The broker as one line: "Ravi Kumar (Adyar Homes)", or just the name when it has no agency. */
export function brokerLine(broker: Pick<Broker, 'name' | 'agency'>): string {
  return broker.agency ? `${broker.name} (${broker.agency})` : broker.name;
}

/**
 * A phone number reduced to what identifies it: its digits, compared on the last ten (so "+91 98400 11111",
 * "098400-11111" and "9840011111" are one number). `null` when there are fewer than six digits: too short to say
 * two numbers are the same. Twin of Kotlin `PhoneKey.of`.
 */
export function phoneKey(phone: string | null | undefined): string | null {
  const digits = (phone ?? '').replace(/\D/g, '');
  return digits.length < 6 ? null : digits.slice(-10);
}

/** True when both numbers have a {@link phoneKey} and it is the same. */
export function samePhone(a: string | null | undefined, b: string | null | undefined): boolean {
  const key = phoneKey(a);
  return key !== null && key === phoneKey(b);
}

function text(value: unknown, max: number): string | undefined {
  return typeof value === 'string' && value.trim() !== '' && value.length <= max ? value : undefined;
}

function stars(value: unknown): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  const n = Math.round(value);
  return n >= 1 && n <= 5 ? n : undefined;
}
