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
 * What a kind can do beyond its fields (docs/03 ADR-36 §18.19): the `capabilities` block of a kind file
 * (`docs/schemas/kinds/kind.schema.json`), resolved. Compare reads `compare` through `compareCriteria`
 * (`shared/compare-criteria.ts`, kinds PR 27, docs/03 §18.21); Hunt mode (the one other capability the phones read, docs/03
 * §18.20) is the phones' only, so the website shows no Hunt UI for any kind; no other screen reads {@link capabilitiesOf} yet.
 * `docs/schemas/kinds/capabilities-vectors.json` holds the resolved sets, the
 * defaults and the files that must be refused, and the phones' `KindCapabilities` answers the same.
 *
 * A feature a kind leaves out is `null` (or `false` for a plain switch). A kind the build does not know has
 * {@link DEFAULT_CAPABILITIES}: the map and the paths, nothing else. (Where an unknown stored kind is *drawn* as a house,
 * §18.9, the caller resolves the drawn kind first; this module does not guess.)
 */

export type ContactRole = 'broker' | 'office' | 'agent';

export interface KindCapabilities {
  readonly map: boolean;
  /**
   * Hunt mode (docs/03 §18.20): the alert radius in metres (30 to 300), whether a stay at an unsaved place offers to save it as
   * an entry of this kind, whether the walk tells about streets this kind has places or visits on, and whether an area
   * wake-up may start a walk of this kind.
   */
  readonly hunt: {
    readonly alertRadiusM: number;
    readonly stayCreates: boolean;
    readonly streetMemory: boolean;
    readonly areas: boolean;
  } | null;
  readonly rooms: boolean;
  readonly moveIn: boolean;
  readonly contacts: { readonly role: ContactRole; readonly autoCreateFromPhone: boolean } | null;
  readonly viewings: { readonly questionsSeed: 'house' } | null;
  /** Compare's criteria in order: checklist keys or extra ids of the kind. */
  readonly compare: { readonly criteria: readonly string[] } | null;
  readonly paths: boolean;
  readonly identity: { readonly icon: string; readonly colour: string } | null;
}

/** The bundled icons a kind may name (`kind.schema.json`, `capabilities.identity.icon`). */
export const CAPABILITY_ICONS: readonly string[] = [
  'home', 'school', 'building', 'apartment', 'shop', 'hospital', 'office', 'park', 'car', 'bike', 'bus', 'train',
  'cart', 'hotel', 'restaurant', 'gym', 'book', 'heart', 'star', 'flag', 'key', 'pin', 'briefcase', 'paw',
];
/** The palette ids a kind may name (`capabilities.identity.colour`). */
export const CAPABILITY_COLOURS: readonly string[] = ['terracotta', 'indigo', 'teal', 'forest', 'amber', 'rose', 'slate', 'plum'];

export const MIN_HUNT_RADIUS_M = 30;
export const MAX_HUNT_RADIUS_M = 300;
export const MAX_COMPARE_CRITERIA = 10;

/** Everything off except the map and the paths: what a kind with no `capabilities`, or no kind file, has. */
export const DEFAULT_CAPABILITIES: KindCapabilities = Object.freeze({
  map: true, hunt: null, rooms: false, moveIn: false, contacts: null, viewings: null, compare: null, paths: true, identity: null,
});

const KEYS = ['map', 'hunt', 'rooms', 'moveIn', 'contacts', 'viewings', 'compare', 'paths', 'identity'];
const CRITERION = /^[a-z][A-Za-z0-9_]{0,39}$/;

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Whether `v` is an object with exactly these keys (all required when `all`), own keys only: `__proto__` is just an unknown key. */
function keysOk(v: unknown, allowed: readonly string[], required: readonly string[]): v is Record<string, unknown> {
  if (!isRecord(v)) return false;
  const own = Object.keys(v);
  return own.every((k) => allowed.includes(k)) && required.every((k) => own.includes(k));
}

/**
 * The strict reader of a `capabilities` object, the schema's twin in code: the resolved set, or `null` when anything is
 * outside the schema (an unknown key at any level, a wrong type, a radius outside 30..300, an integer that is not one,
 * more than ten or a repeated or malformed criterion, an icon or colour not on the lists). A missing key takes the default
 * (off; the map and the paths on). Never throws, never reads a prototype.
 */
export function parseCapabilities(raw: unknown): KindCapabilities | null {
  if (!keysOk(raw, KEYS, [])) return null;
  const has = (k: string) => Object.keys(raw).includes(k);
  const bool = (k: string, dflt: boolean): boolean | null => (has(k) ? (typeof raw[k] === 'boolean' ? (raw[k] as boolean) : null) : dflt);
  const map = bool('map', true);
  const rooms = bool('rooms', false);
  const moveIn = bool('moveIn', false);
  const paths = bool('paths', true);
  if (map === null || rooms === null || moveIn === null || paths === null) return null;

  let hunt: KindCapabilities['hunt'] = null;
  if (has('hunt')) {
    const h = raw['hunt'];
    const huntKeys = ['alertRadiusM', 'stayCreates', 'streetMemory', 'areas'];
    if (!keysOk(h, huntKeys, huntKeys)) return null;
    const r = h['alertRadiusM'];
    if (typeof r !== 'number' || !Number.isInteger(r) || r < MIN_HUNT_RADIUS_M || r > MAX_HUNT_RADIUS_M) return null;
    if (typeof h['stayCreates'] !== 'boolean' || typeof h['streetMemory'] !== 'boolean' || typeof h['areas'] !== 'boolean') return null;
    hunt = Object.freeze({ alertRadiusM: r, stayCreates: h['stayCreates'], streetMemory: h['streetMemory'], areas: h['areas'] });
  }
  let contacts: KindCapabilities['contacts'] = null;
  if (has('contacts')) {
    const c = raw['contacts'];
    if (!keysOk(c, ['role', 'autoCreateFromPhone'], ['role', 'autoCreateFromPhone'])) return null;
    const role = c['role'];
    if (role !== 'broker' && role !== 'office' && role !== 'agent') return null;
    if (typeof c['autoCreateFromPhone'] !== 'boolean') return null;
    contacts = Object.freeze({ role, autoCreateFromPhone: c['autoCreateFromPhone'] });
  }
  let viewings: KindCapabilities['viewings'] = null;
  if (has('viewings')) {
    const v = raw['viewings'];
    if (!keysOk(v, ['questionsSeed'], ['questionsSeed']) || v['questionsSeed'] !== 'house') return null;
    viewings = Object.freeze({ questionsSeed: 'house' as const });
  }
  let compare: KindCapabilities['compare'] = null;
  if (has('compare')) {
    const c = raw['compare'];
    if (!keysOk(c, ['criteria'], ['criteria'])) return null;
    const list = c['criteria'];
    if (!Array.isArray(list) || list.length < 1 || list.length > MAX_COMPARE_CRITERIA) return null;
    if (!list.every((x) => typeof x === 'string' && CRITERION.test(x)) || new Set(list).size !== list.length) return null;
    compare = Object.freeze({ criteria: Object.freeze([...(list as string[])]) });
  }
  let identity: KindCapabilities['identity'] = null;
  if (has('identity')) {
    const i = raw['identity'];
    if (!keysOk(i, ['icon', 'colour'], ['icon', 'colour'])) return null;
    const icon = i['icon'];
    const colour = i['colour'];
    if (typeof icon !== 'string' || !CAPABILITY_ICONS.includes(icon)) return null;
    if (typeof colour !== 'string' || !CAPABILITY_COLOURS.includes(colour)) return null;
    identity = Object.freeze({ icon, colour });
  }
  return Object.freeze({ map, hunt, rooms, moveIn, contacts, viewings, compare, paths, identity });
}

/**
 * The built-in kinds' capabilities, copies of `house.json` and `school.json` (`kind-capabilities.spec.ts` reads the files,
 * so a copy cannot drift). The house's are today's hard-coded behaviour: Hunt at 30 m, the rooms, moving in, the broker
 * made from a phone, the house questions, Compare's ten checklist keys, the paths.
 */
const BUILT_IN: Readonly<Record<string, KindCapabilities>> = Object.freeze({
  house: Object.freeze({
    map: true,
    hunt: Object.freeze({ alertRadiusM: 30, stayCreates: true, streetMemory: true, areas: true }),
    rooms: true,
    moveIn: true,
    contacts: Object.freeze({ role: 'broker' as const, autoCreateFromPhone: true }),
    viewings: Object.freeze({ questionsSeed: 'house' as const }),
    compare: Object.freeze({
      criteria: Object.freeze(['water', 'power', 'parking', 'sunlight', 'ventilation', 'noise', 'security', 'maintenance', 'neighbourhood', 'commute']),
    }),
    paths: true,
    identity: null,
  }),
  // The school has a contact (the admissions office) that never makes a broker, Hunt at 100 m (a school is passed on foot or
  // by car, not walked up to), no rooms, moving in or viewings; its walks stay. Compare scores its eight checklist items and
  // two extras, the fees and the commute (kinds PR 27, §18.21).
  school: Object.freeze({
    map: true, hunt: Object.freeze({ alertRadiusM: 100, stayCreates: true, streetMemory: true, areas: true }), rooms: false, moveIn: false,
    contacts: Object.freeze({ role: 'office' as const, autoCreateFromPhone: false }),
    viewings: null,
    compare: Object.freeze({
      criteria: Object.freeze(['dropoff', 'grounds', 'sanitation', 'teachers', 'classsize', 'homework', 'activities', 'safety', 'fees', 'commuteMinutes']),
    }),
    paths: true, identity: null,
  }),
});

/** The capabilities of a kind by its id: the built-in set, or {@link DEFAULT_CAPABILITIES} for a kind this build does not know. */
export function capabilitiesOf(kindId: string | null | undefined): KindCapabilities {
  return typeof kindId === 'string' && Object.hasOwn(BUILT_IN, kindId) ? BUILT_IN[kindId] : DEFAULT_CAPABILITIES;
}

/** The built-in kind ids that have capabilities, for the spec. */
export const CAPABILITY_KINDS: readonly string[] = Object.keys(BUILT_IN);
