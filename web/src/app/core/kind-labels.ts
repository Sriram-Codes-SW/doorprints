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

import type { TKey } from '../i18n/en';
import { capabilitiesOf } from './kind-capabilities';
import type { KindCapabilities } from './kind-capabilities';

/** One place a kind labels: the translation key (`null`: the website shows no text there) and whether the kind shows it. */
export interface KindLabel {
  readonly key: TKey | null;
  readonly visible: boolean;
}

/** What a kind file says about its labels, as {@link KindLabels} reads it. */
export interface KindLabelTable {
  /** The kind id (`house`). */
  readonly kind: string;
  /** Every ref of the kind and its key (`null`: no text on the website). */
  readonly keys: Readonly<Record<string, TKey | null>>;
  /** `field.<id>@<cadence>` to the key a field shows while the candidate's money has that cadence. */
  readonly byCadence: Readonly<Record<string, TKey>>;
  /** Each field's section id. */
  readonly sections: Readonly<Record<string, string>>;
  /** The refs the kind keeps but hides (`visible: false` in the kind file). */
  readonly hidden?: readonly string[];
  /**
   * What the kind can do (its `capabilities`, docs/03 §18.23): a place a capability switches is shown only while the capability
   * is on, whatever the sections say. Absent: no place is gated (a kind written by hand in a test).
   */
  readonly capabilities?: KindCapabilities;
}

/**
 * Whether the capabilities of a kind allow the place `ref` (kinds PR 29, docs/03 §18.23): the rooms and moving in follow
 * `rooms` and `moveIn`, the viewings and their questions follow `viewings`, and the broker field is the `contacts` of a
 * `broker` role (an office or an agent has a name and a phone and no broker record). Every other place is allowed.
 */
export function capabilityAllows(ref: string, caps: KindCapabilities): boolean {
  switch (ref) {
    case 'section.rooms': return caps.rooms;
    case 'section.moveIn': return caps.moveIn;
    case 'section.viewings':
    case 'section.questions': return caps.viewings !== null;
    case 'field.brokerId': return caps.contacts?.role === 'broker';
    default: return true;
  }
}

const FIELD = 'field.';
const has = (o: object, k: string): boolean => Object.prototype.hasOwnProperty.call(o, k);

/**
 * The label and visibility resolver of a kind (kinds PR 3, S4b-BL-207; docs/03 ADR-36 §18.7), the twin of `:shared`'s
 * `KindLabels`: which translation key a screen shows for a place of the candidate, and whether the kind shows that
 * place. A place is a ref, as `docs/schemas/kinds/kind-vectors.json` names them (`kind.new`, `status.NEW`,
 * `section.cost`, `field.money`, `cadence.month`, `check.water`); a money cadence is an argument. A ref the kind does not
 * have resolves to `undefined`, and the caller shows nothing. `kind-labels.spec.ts` reads `house.json` and
 * `kind-label-vectors.json`, so the copy below cannot drift.
 */
export class KindLabels {
  private readonly hidden: ReadonlySet<string>;

  constructor(private readonly table: KindLabelTable) {
    this.hidden = new Set(table.hidden ?? []);
  }

  get kind(): string {
    return this.table.kind;
  }

  /** Every ref the kind labels. */
  get refs(): string[] {
    return Object.keys(this.table.keys);
  }

  /** The label of `ref`, the `cadence`'s one where the kind has it; `undefined` when the kind has no such place. */
  resolve(ref: string, cadence?: string | null): KindLabel | undefined {
    if (!has(this.table.keys, ref)) return undefined;
    const alt = cadence ? `${ref}@${cadence}` : '';
    const key = alt && has(this.table.byCadence, alt) ? this.table.byCadence[alt] : this.table.keys[ref];
    return { key, visible: this.isVisible(ref) };
  }

  /** Whether the kind shows `ref`: a place it has and does not hide, and for a field, in a section it shows. */
  isVisible(ref: string): boolean {
    if (!has(this.table.keys, ref) || this.hidden.has(ref)) return false;
    if (this.table.capabilities && !capabilityAllows(ref, this.table.capabilities)) return false;
    const id = ref.startsWith(FIELD) ? ref.slice(FIELD.length) : '';
    return !id || !has(this.table.sections, id) || this.isVisible(`section.${this.table.sections[id]}`);
  }

  /** A field's label (`field.<id>`), with the money cadence when the screen knows it. */
  field(id: string, cadence?: string | null): KindLabel | undefined {
    return this.resolve(FIELD + id, cadence);
  }

  /** A section's heading (`section.<id>`). */
  section(id: string): KindLabel | undefined {
    return this.resolve(`section.${id}`);
  }

  /** A status's label: every kind labels the five fixed names (door 8), so this always has a key. */
  status(status: string): TKey {
    const key = has(this.table.keys, `status.${status}`) ? this.table.keys[`status.${status}`] : null;
    if (!key) throw new Error(`${this.kind} labels no ${status}`);
    return key;
  }

  /** A built-in checklist criterion's label, `undefined` for a key the kind does not have. */
  check(key: string): TKey | undefined {
    return (has(this.table.keys, `check.${key}`) && this.table.keys[`check.${key}`]) || undefined;
  }

  /** The kind's checklist keys, in the kind file's order (the house's ten; a kind with none has none). */
  checklistKeys(): string[] {
    return Object.keys(this.table.keys).filter((ref) => ref.startsWith('check.')).map((ref) => ref.slice('check.'.length));
  }
}

const COST_FIELDS = ['deposit', 'depositMonths', 'maintenance', 'maintenanceIncluded', 'brokerage', 'brokerageMonths',
  'lockInMonths', 'noticeMonths', 'availableFrom', 'myOffer', 'agreedPrice'] as const;
const DETAIL_FIELDS = ['label', 'status', 'rating', 'money', 'bedrooms', 'areaSqft', 'floor', 'address', 'street',
  'locality', 'contactName', 'contactPhone', 'brokerId', 'listingUrl', 'notes'];
const CHECKS = ['water', 'power', 'parking', 'sunlight', 'ventilation', 'noise', 'security', 'maintenance',
  'neighbourhood', 'commute'] as const;

/** Houses, the first and so far only kind (`docs/schemas/kinds/house.json`, web keys). */
export const HOUSE_LABELS = new KindLabels({
  kind: 'house',
  capabilities: capabilitiesOf('house'),
  keys: {
    'kind.new': 'house.newTitle', 'kind.many': 'map.stats.houses', 'kind.delete': 'house.delete',
    'status.NEW': 'status.NEW', 'status.SHORTLISTED': 'status.SHORTLISTED', 'status.REJECTED': 'status.REJECTED',
    'status.TAKEN': 'status.TAKEN', 'status.NOT_CHOSEN': 'status.NOT_CHOSEN',
    'section.details': 'house.details', 'section.cost': 'house.cost', 'section.rooms': 'rooms.heading',
    'section.questions': 'questions.title', 'section.viewings': 'viewings.cardHeading', 'section.moveIn': 'movein.heading',
    'section.areaNotes': 'areaNotes.heading', 'section.distances': 'distances.heading', 'section.location': 'house.location',
    'section.walks': 'trace.house.title', 'section.checklist': 'house.checklist', 'section.visits': 'house.visits',
    'section.photos': 'house.photos',
    'field.label': 'house.name', 'field.status': 'house.status', 'field.rating': 'house.rating', 'field.money': 'house.price',
    'field.address': 'house.address', 'field.street': 'house.street', 'field.locality': 'house.locality',
    'field.contactName': 'house.contactName', 'field.contactPhone': 'house.contactPhone', 'field.brokerId': 'house.broker',
    'field.listingUrl': 'house.listingUrl', 'field.notes': 'house.notes', 'field.location': 'house.location',
    'field.locationSource': 'house.approx', 'field.bedrooms': 'house.bhk', 'field.areaSqft': 'house.areaSqft',
    'field.floor': 'house.floor',
    ...Object.fromEntries(COST_FIELDS.map((f) => [`field.${f}`, `cost.${f}` as const])),
    'cadence.month': 'price.rent', 'cadence.once': 'price.sale',
    ...Object.fromEntries(CHECKS.map((c) => [`check.${c}`, `check.${c}` as const])),
  },
  byCadence: {},
  sections: {
    ...Object.fromEntries(DETAIL_FIELDS.map((f) => [f, 'details'])),
    ...Object.fromEntries(COST_FIELDS.map((f) => [f, 'cost'])),
    location: 'location', locationSource: 'location',
  },
});

/**
 * A kind's keys for a screen's own places, as templates read them (`L.field('label')`, `L.section('cost')`,
 * `L.ref('cadence.month')`; kinds PR 4, S4b-BL-255): the field and section labels of the house page come from the kind
 * through these. A place the kind does not label throws: a screen asks only for the places it draws.
 */
export class KindKeys {
  constructor(private readonly labels: KindLabels) {}

  /** The key of any ref. */
  ref(ref: string, cadence?: string | null): TKey {
    const key = this.labels.resolve(ref, cadence)?.key;
    if (!key) throw new Error(`${this.labels.kind} has no text for ${ref}`);
    return key;
  }

  field(id: string, cadence?: string | null): TKey {
    return this.ref(`field.${id}`, cadence);
  }

  section(id: string): TKey {
    return this.ref(`section.${id}`);
  }
}

/** The house's keys for templates. */
export const HOUSE_KEYS = new KindKeys(HOUSE_LABELS);

/**
 * The label key of `ref` on a screen that has a hand-written key of its own for the house (Compare's *Your rating*, the copies'
 * *Price*): the house keeps that key word for word (the house's goldens pin it), any other kind reads its label through the
 * resolver (S4b-BL-256, S4b-BL-266); a kind with no such label keeps the house's key.
 */
export function labelKey(labels: KindLabels, ref: string, house: TKey): TKey {
  return labels.kind === HOUSE_LABELS.kind ? house : (labels.resolve(ref)?.key ?? house);
}

const STATUS_NAMES = ['NEW', 'SHORTLISTED', 'REJECTED', 'TAKEN', 'NOT_CHOSEN'] as const;
const STATUS_KEYS = new WeakMap<KindLabels, Readonly<Record<string, TKey>>>();

/**
 * A kind's status words by status name (the five fixed names, door 8), for the screens that list statuses: the map's
 * legend, filter and rows, Compare, Ask and the page's status buttons read the kind's table, not the house's (S4b-BL-256).
 * Kept per kind, so a template's `statusKey()[s]` is a lookup.
 */
export function statusKeys(labels: KindLabels): Readonly<Record<string, TKey>> {
  let keys = STATUS_KEYS.get(labels);
  if (!keys) {
    keys = Object.fromEntries(STATUS_NAMES.map((s) => [s, labels.status(s)]));
    STATUS_KEYS.set(labels, keys);
  }
  return keys;
}

/** The built-in kind with this id, `undefined` for one this build does not have. */
export function kindLabels(kind: string): KindLabels | undefined {
  return kind === HOUSE_LABELS.kind ? HOUSE_LABELS : undefined;
}
