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

import type { KindCapabilities } from '../kind-capabilities';

/**
 * The facts of the kinds a person added (docs/03 ADR-38) that the low-level registries read: their ids, capabilities,
 * question scopes and the keys of their words. A leaf with no imports of its own (a type only), so the capabilities, the
 * question banks and the word table can ask it without an import cycle; `user-kinds.ts` fills it, the first time anything
 * asks (its loader reads this browser's stored kind files and checks each one again).
 */

/** A kind a person adds: `u_`, a letter, then 1 to 29 letters, digits or `_` (32 characters at most, door 3's kind id). */
export const USER_KIND_ID = /^u_[a-z][a-z0-9_]{1,29}$/;

/** Whether `id` is the id of a kind a person adds; a built-in id never starts with `u_` (`user-kinds.spec.ts` holds the files to it). */
export function isUserKindId(id: unknown): id is string {
  return typeof id === 'string' && USER_KIND_ID.test(id);
}

/** What the registries need of one kind a person added. */
export interface UserKindFacts {
  readonly id: string;
  readonly capabilities: KindCapabilities;
  /** The question scopes of its own question bank (the profile allows `BOTH` only). */
  readonly scopes: readonly string[];
  /** The key of a word (`heading`, `add`, …) where the kind has its own; every other word is the neutral one. */
  readonly words: Readonly<Record<string, string>>;
}

const FACTS = new Map<string, UserKindFacts>();
let loader: (() => void) | null = null;
let loaded = false;

/** Called once by `user-kinds.ts` when it is first imported: how to read the stored kinds. */
export function setUserKindLoader(load: () => void): void {
  loader = load;
  loaded = false;
}

function ensure(): void {
  if (loaded || !loader) return;
  loaded = true;
  loader();
}

/** Replaces every fact (the loader after reading the store, and after an import or a removal). */
export function replaceUserKindFacts(list: readonly UserKindFacts[]): void {
  FACTS.clear();
  for (const f of list) FACTS.set(f.id, f);
  loaded = true;
}

/** The ids of the kinds this browser has added, in the order they were added. */
export function userKindIds(): readonly string[] {
  ensure();
  return [...FACTS.keys()];
}

/** The facts of the added kind `id`, or undefined (not added here, or not a user kind id). */
export function userKindFacts(id: string | null | undefined): UserKindFacts | undefined {
  if (!isUserKindId(id)) return undefined;
  ensure();
  return FACTS.get(id);
}
