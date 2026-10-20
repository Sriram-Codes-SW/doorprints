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

import { isDevMode } from '@angular/core';
import { HOUSE_FIELDS, type KindFields } from './kind-fields';
import { HOUSE_KEYS, HOUSE_LABELS, type KindKeys, type KindLabels } from './kind-labels';
import { SCHOOL_FIELDS, SCHOOL_KEYS, SCHOOL_LABELS } from './school-kind';
import { STORAGE_PREFIX } from './storage-keys';
import { userBuiltInKinds } from './user-kinds/user-kinds';

/**
 * The developer flag of the schools kind (kinds PR 5, S4b-BL-209; docs/03 §18.9). Since 2026-10-15 the release build lists
 * the school too (compiled in, off until Settings' switch); this flag only shows more than the shipped list, in tests.
 * Off by default and never on in
 * a production build: it needs both a development build (`ng serve`, where Angular's `isDevMode()` is true; the
 * deployed site is a production build, where it is false) and this localStorage key set to `school` by hand in the
 * browser's console. Since kinds PR 8 (S4b-BL-212) it does what Settings' *Experimental: more than houses* switch does
 * (`core/kind-setting.ts`, `kindsOn()`: the flag or the switch): Settings offers the kind of the data set and the AI reads
 * a candidate through its kind. A stored candidate of a built-in kind is drawn as its kind either way (`drawnKind`).
 * "Remove all data" sweeps it with the app's other keys.
 */
export const DEV_KINDS_KEY = `${STORAGE_PREFIX}dev.kinds`;

/** Whether the schools kind is on: a development build and the key set to `school`; off on any doubt. */
export function devKindsOn(devMode: boolean = isDevMode(), storage: Pick<Storage, 'getItem'> | null = localStore()): boolean {
  if (!devMode || !storage) return false;
  try {
    return storage.getItem(DEV_KINDS_KEY) === 'school';
  } catch {
    return false;
  }
}

/** A built-in kind as the screens read it: its labels, its keys for templates and its typed extras. */
export interface BuiltInKind {
  readonly id: string;
  readonly labels: KindLabels;
  readonly keys: KindKeys;
  readonly fields: KindFields;
  /** Whether the listing extract fills it (the kind file's `ai.extract` is not empty): *Fill in from listing text*. */
  readonly extracts: boolean;
}

export const HOUSE: BuiltInKind = { id: 'house', labels: HOUSE_LABELS, keys: HOUSE_KEYS, fields: HOUSE_FIELDS, extracts: true };
// The school's `ai.extract` is filled since kinds PR 7 (S4b-BL-211): its listing is read into its fields.
const SCHOOL: BuiltInKind = { id: 'school', labels: SCHOOL_LABELS, keys: SCHOOL_KEYS, fields: SCHOOL_FIELDS, extracts: true };

/** Kinds a test registered (the conformance suite's synthetic kind, docs/03 §18.26). Empty in every build: no build lists one. */
const TEST_KINDS = new Map<string, BuiltInKind>();

/**
 * Registers a kind for a test: the conformance suite runs the same flows for the house, the school and a synthetic kind that has every
 * capability, so a feature that works only for the two built-in kinds fails there. Only a development build may: in a production build it throws,
 * so a build can never offer a kind this file does not hold (`tools/check-kind-literals.mjs` also keeps the synthetic kind's id out of every source).
 * Returns the call that takes it away again.
 */
export function registerTestKind(kind: BuiltInKind): () => void {
  if (!isDevMode()) throw new Error('a test kind is registered only in a development build');
  TEST_KINDS.set(kind.id, kind);
  return () => void TEST_KINDS.delete(kind.id);
}

/**
 * The kinds: the house, and while `on` (the developer flag or Settings' switch) the school, any a test registered, then the
 * kinds this browser added from a kind file (`user-kinds/user-kinds.ts`, docs/03 ADR-38). The built-in ones come first, so a
 * kind a person adds can never stand in for one (its id starts `u_` anyway).
 */
export function creatableKinds(on: boolean): readonly BuiltInKind[] {
  return on ? [HOUSE, SCHOOL, ...TEST_KINDS.values(), ...userBuiltInKinds()] : [HOUSE];
}

/** The ids of the built-in kinds while `on`, without the kinds a person added. */
export function builtInKindIds(on: boolean): readonly string[] {
  return (on ? [HOUSE, SCHOOL, ...TEST_KINDS.values()] : [HOUSE]).map((k) => k.id);
}

/** The kind a candidate is drawn as: its own when this build has it and the flag allows it, else the house (as today). */
export function builtInKind(id: string | null | undefined, on: boolean): BuiltInKind {
  return creatableKinds(on).find((k) => k.id === id) ?? HOUSE;
}

function localStore(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
