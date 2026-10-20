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
 * The words of the kinds a person added (docs/03 ADR-39): their own text, shown as written in every language. Each one is
 * given a key of its own under {@link USER_TEXT_PREFIX} (`uk.u_gym.kind.many`) and put into every dictionary that is
 * loaded, so every screen and every copy that reads a kind's label key (a template's `t`, a copy's `dict[key]`) shows it
 * with no code of its own. The texts are checked before they get here (`user-kind-file.ts`: no `<`, `>`, `{`, `}`, no
 * control or direction marks), so a text cannot carry markup or a placeholder; the screens show it as text anyway.
 */

/** The prefix of every key of a person's own words; no key of the dictionaries starts with it (`user-kinds.spec.ts`). */
export const USER_TEXT_PREFIX = 'uk.';

type Dict = Record<string, string>;

let texts: Readonly<Record<string, string>> = {};
const dictionaries = new Set<Dict>();

/** Puts the current texts into `dict` (a dictionary as it is loaded), and remembers it for later changes. */
export function withUserTexts<T extends object>(dict: T): T {
  const d = dict as unknown as Dict;
  dictionaries.add(d);
  for (const k of Object.keys(d)) if (k.startsWith(USER_TEXT_PREFIX) && !Object.prototype.hasOwnProperty.call(texts, k)) delete d[k];
  for (const [k, v] of Object.entries(texts)) d[k] = v;
  return dict;
}

/** Replaces every person's text (keys must start with {@link USER_TEXT_PREFIX}); every remembered dictionary follows. */
export function setUserTexts(next: Readonly<Record<string, string>>): void {
  const clean: Record<string, string> = {};
  for (const [k, v] of Object.entries(next)) if (k.startsWith(USER_TEXT_PREFIX) && typeof v === 'string') clean[k] = v;
  texts = Object.freeze(clean);
  for (const d of dictionaries) withUserTexts(d);
}

/** The person's text of `key`, or undefined. */
export function userText(key: string): string | undefined {
  return Object.prototype.hasOwnProperty.call(texts, key) ? texts[key] : undefined;
}
