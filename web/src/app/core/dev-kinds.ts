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

/**
 * The developer flag of the hidden schools kind (kinds PR 5, S4b-BL-209; docs/03 §18.9). Off by default and never on in
 * a production build: it needs both a development build (`ng serve`, where Angular's `isDevMode()` is true; the
 * deployed site is a production build, where it is false) and this localStorage key set to `school` by hand in the
 * browser's console. With it off nothing changes anywhere: a new candidate is a house, the kind picker is not drawn,
 * and a row of another kind (synced from a device with the flag on) is drawn as a house is today, its extras kept and
 * written back unread (`core/candidate.ts`). "Remove all data" sweeps it with the app's other keys.
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
const SCHOOL: BuiltInKind = { id: 'school', labels: SCHOOL_LABELS, keys: SCHOOL_KEYS, fields: SCHOOL_FIELDS, extracts: false };

/** The kinds a new candidate may be: the house, and the school while the flag is on. */
export function creatableKinds(on: boolean): readonly BuiltInKind[] {
  return on ? [HOUSE, SCHOOL] : [HOUSE];
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
