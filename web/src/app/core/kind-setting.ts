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
import { type BuiltInKind, builtInKind, creatableKinds, devKindsOn } from './dev-kinds';
import type { HouseDto } from './models';
import { STORAGE_PREFIX } from './storage-keys';

/**
 * Which kinds this browser shows, and which one is the default (kinds PR 8, S4b-BL-212; docs/03 §18.12). A kind is a
 * *space*: a filter over the one candidate store, chosen in the chooser, never a store, a wire field or a sync family of
 * its own. Everything here is this browser's own preference (localStorage), never synced, shared or put in a copy, and
 * every doubt falls back to the house alone, which is the app a house hunter has always had.
 *
 * - **Available** kinds are the kinds this build ships with their release gate open ({@link availableKinds}): a production
 *   build lists only the house; a development build lists every built-in kind; a build made with
 *   `--define DOORPRINTS_KINDS="'house,school'"` lists those. The owner decides the release gate.
 * - **Released** kinds ({@link RELEASED_KINDS}) need no switch. Every other available kind is offered only while the
 *   person turns on *Experimental: more than houses*, which is shown only in a build that ships such a kind.
 * - **Enabled** kinds are the offered ones the person turned on in Settings > Kinds (the house alone by default), and
 *   the **default** kind is the one the app opens with when only one is enabled, and the one deep links, `/houses/new`,
 *   the share target and notifications assume.
 */
export const EXPERIMENTAL_KINDS_KEY = `${STORAGE_PREFIX}kinds.experimental`;
/** The enabled kinds, as a JSON list of kind ids; absent means the house alone. */
export const ENABLED_KINDS_KEY = `${STORAGE_PREFIX}kinds.enabled`;
/** The default kind's id; absent means the first enabled kind. */
export const DEFAULT_KIND_KEY = `${STORAGE_PREFIX}kinds.default`;

/** The kinds that need no experimental switch: the owner opens a kind's release gate by adding it here. */
export const RELEASED_KINDS: readonly string[] = ['house'];
/** Every built-in kind, the house first. */
const BUILT_IN: readonly string[] = creatableKinds(true).map((k) => k.id);
const HOUSE_ONLY: readonly string[] = ['house'];

type Read = Pick<Storage, 'getItem'> | null;
type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> | null;

declare const DOORPRINTS_KINDS: string | undefined;

/** The build flag `DOORPRINTS_KINDS` (a comma list), or null when the build was made without it. */
function buildFlag(): string | null {
  return typeof DOORPRINTS_KINDS === 'string' ? DOORPRINTS_KINDS : null;
}

/** The kinds this build may offer: the house always, then the built-in kinds the build lists, in their own order. */
export function availableKinds(devMode: boolean = isDevMode(), flag: string | null = buildFlag()): readonly string[] {
  if (devMode) return BUILT_IN;
  if (!flag) return HOUSE_ONLY;
  const listed = new Set(flag.split(',').map((s) => s.trim()));
  return BUILT_IN.filter((id) => id === 'house' || listed.has(id));
}

/** The kinds this build lists that are not released: what *Experimental: more than houses* would show. */
export function unreleasedKinds(available: readonly string[] = availableKinds()): readonly string[] {
  return available.filter((id) => !RELEASED_KINDS.includes(id));
}

/** Whether the person turned *Experimental: more than houses* on ('1'); off on any doubt. */
export function experimentalKindsOn(storage: Read = localStore()): boolean {
  return read(storage, EXPERIMENTAL_KINDS_KEY) === '1';
}

/** The kinds Settings > Kinds lists: the released ones, and the unreleased ones only while the switch is on. */
export function offeredKinds(available: readonly string[] = availableKinds(), storage: Read = localStore()): readonly string[] {
  const on = experimentalKindsOn(storage);
  return available.filter((id) => RELEASED_KINDS.includes(id) || on);
}

/** The enabled kinds, in the built-in order: the stored list kept to the offered kinds; none left means the house. */
export function enabledKinds(available: readonly string[] = availableKinds(), storage: Read = localStore()): readonly string[] {
  const offered = offeredKinds(available, storage);
  let stored: unknown = null;
  try {
    stored = JSON.parse(read(storage, ENABLED_KINDS_KEY) ?? 'null');
  } catch {
    stored = null;
  }
  const ids = Array.isArray(stored) ? (stored as unknown[]) : ['house'];
  const list = offered.filter((id) => ids.includes(id));
  return list.length ? list : HOUSE_ONLY;
}

/** The default kind: the stored one when it is enabled, else the first enabled kind. */
export function defaultKind(available: readonly string[] = availableKinds(), storage: Read = localStore()): string {
  const enabled = enabledKinds(available, storage);
  const stored = read(storage, DEFAULT_KIND_KEY);
  return stored !== null && enabled.includes(stored) ? stored : enabled[0];
}

/** Whether the app has spaces: two or more kinds enabled. With one, the app is that kind's alone, on today's paths. */
export function spacesOn(available: readonly string[] = availableKinds(), storage: Read = localStore()): boolean {
  return enabledKinds(available, storage).length > 1;
}

/** Whether kinds other than the house are read by the AI and the listing fill: the developer flag, or one offered. */
export function kindsOn(): boolean {
  return devKindsOn() || offeredKinds().length > 1;
}

/** Turns the switch on or off. Off, the unreleased kinds leave Settings and the chooser; their entries are kept. */
export function setExperimentalKinds(on: boolean, storage: Store = localStore()): void {
  write(storage, EXPERIMENTAL_KINDS_KEY, on ? '1' : null);
}

/**
 * Turns kind `id` on or off. The last enabled kind cannot be turned off; turning off the default makes the next
 * enabled kind the default. A kind that is off keeps every entry (the chooser lists it as hidden).
 */
export function setKindEnabled(id: string, on: boolean, available: readonly string[] = availableKinds(), storage: Store = localStore()): void {
  const offered = offeredKinds(available, storage);
  if (!offered.includes(id)) return;
  const now = enabledKinds(available, storage);
  const next = offered.filter((k) => (k === id ? on : now.includes(k)));
  if (!next.length) return;
  write(storage, ENABLED_KINDS_KEY, next.length === 1 && next[0] === 'house' ? null : JSON.stringify(next));
  const dflt = read(storage, DEFAULT_KIND_KEY) ?? 'house';
  if (!next.includes(dflt)) setDefaultKind(next[0], available, storage);
}

/** Makes `id` the default kind, when it is enabled; the house as default is stored as no key. */
export function setDefaultKind(id: string, available: readonly string[] = availableKinds(), storage: Store = localStore()): void {
  if (!enabledKinds(available, storage).includes(id)) return;
  write(storage, DEFAULT_KIND_KEY, id === 'house' ? null : id);
}

/**
 * A backup's kinds and default (`search.kinds`, `search.defaultKind`), taken only by a browser that never chose its own
 * (neither key stored): a hint, never applied over a choice and never a reason to refuse or change a file (S4b-BL-212).
 * The kinds this build does not offer stay stored but unread, as any stored choice. Returns whether it was taken.
 */
export function adoptSpaces(spaces: { kinds: readonly string[]; defaultKind: string } | null | undefined, storage: Store = localStore()): boolean {
  if (!spaces || read(storage, ENABLED_KINDS_KEY) !== null || read(storage, DEFAULT_KIND_KEY) !== null) return false;
  write(storage, ENABLED_KINDS_KEY, JSON.stringify(spaces.kinds));
  write(storage, DEFAULT_KIND_KEY, spaces.defaultKind);
  return true;
}

/** The built-in kinds by id, for the chooser and Settings (their labels and keys). */
export function kindById(id: string): BuiltInKind {
  return builtInKind(id, true);
}

/** The kind a stored candidate is drawn as: its own when it is built in, else the house (whatever the settings say). */
export function drawnKind(id: string | null | undefined): BuiltInKind {
  return builtInKind(id, true);
}

/** A new candidate of the given kind: its id, and no price type when the kind hides its money (a school's fees are extras). */
export function ofKind(d: HouseDto, kind: string): HouseDto {
  if (kind === 'house') return d;
  return { ...d, kind, priceType: drawnKind(kind).labels.isVisible('field.money') ? d.priceType : null };
}

function read(storage: Read, key: string): string | null {
  try {
    return storage?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

function write(storage: Store, key: string, value: string | null): void {
  try {
    if (value === null) storage?.removeItem(key);
    else storage?.setItem(key, value);
  } catch {
    // Storage blocked: the setting stays as it was.
  }
}

function localStore(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
