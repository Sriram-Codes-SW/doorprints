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

// The shared vectors of the kinds a person adds (docs/schemas/kinds/user-kind-vectors.json, docs/03 ADR-38) and the text of each case, as the
// file's note defines its `json`, `generate` and `patch` forms; the phones and the server build the same texts.
import vectorsJson from '../../../../../docs/schemas/kinds/user-kind-vectors.json';

type Path = (string | number)[];
export interface UserKindCase {
  name: string;
  code: string;
  path: string;
  schema: boolean | null;
  base: boolean;
  json?: string;
  generate?: { prefix: string; repeat: string; times: number; suffix: string };
  patch?: {
    base: string; set?: [Path, unknown][]; remove?: Path[]; manyExtras?: number; manyOptions?: { extra: number; count: number };
    manyEnums?: { count: number; options: number }; manyChecks?: number;
  };
}
export interface UserKindVectors {
  limits: Record<string, number | string>;
  reserved: string[];
  forbiddenKeys: string[];
  codes: string[];
  valid: { name: string; file: Record<string, unknown>; resolved: { id: string; many: string; extras: string[]; checklist: string[]; search: string[]; capabilities: unknown } }[];
  invalid: UserKindCase[];
}

export const USER_KIND_VECTORS = vectorsJson as unknown as UserKindVectors;

/** A valid sample by id (a deep copy). */
export function userKindSample(id: string): Record<string, any> {
  const v = USER_KIND_VECTORS.valid.find((x) => x.file['id'] === id);
  if (!v) throw new Error(`no sample ${id}`);
  return JSON.parse(JSON.stringify(v.file));
}

const T = (text: string) => ({ text });

/** The text of an invalid case. */
export function caseText(c: UserKindCase): string {
  if (c.json !== undefined) return c.json;
  if (c.generate) return c.generate.prefix + c.generate.repeat.repeat(c.generate.times) + c.generate.suffix;
  const p = c.patch!;
  const file = userKindSample(p.base);
  for (const [path, value] of p.set ?? []) {
    let o: any = file;
    for (const k of path.slice(0, -1)) o = o[k];
    Object.defineProperty(o, path[path.length - 1], { value, enumerable: true, writable: true, configurable: true });
  }
  for (const path of p.remove ?? []) {
    let o: any = file;
    for (const k of path.slice(0, -1)) o = o[k];
    const last = path[path.length - 1];
    if (Array.isArray(o) && typeof last === 'number') o.splice(last, 1);
    else delete o[last];
  }
  if (p.manyExtras !== undefined) {
    file['extras'] = Array.from({ length: p.manyExtras }, (_, i) => ({ id: `x${i + 1}`, type: 'text', maxLength: 10, section: 'details', order: i + 1, label: T(`Field ${i + 1}`) }));
  }
  if (p.manyOptions) {
    file['extras'][p.manyOptions.extra].options = Array.from({ length: p.manyOptions.count }, (_, i) => ({ id: `O${i + 1}`, label: T(`Option ${i + 1}`) }));
  }
  if (p.manyEnums) {
    const { count, options } = p.manyEnums;
    file['extras'] = Array.from({ length: count }, (_, i) => ({
      id: `e${i + 1}`, type: 'enum', section: 'details', order: i + 1, label: T(`Field ${i + 1}`),
      options: Array.from({ length: options }, (_, j) => ({ id: `O${j + 1}`, label: T(`Option ${j + 1}`) })),
    }));
  }
  if (p.manyChecks !== undefined) {
    file['checklist'] = Array.from({ length: p.manyChecks }, (_, i) => ({ key: `c${i + 1}`, label: T(`Check ${i + 1}`) }));
  }
  return JSON.stringify(file);
}
