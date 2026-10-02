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
 * The one JSON form the crypto formats write, the twin of Kotlin's `CanonicalJson`: no whitespace, keys in the
 * format's order, integers in plain decimal, strings with only `"`, `\` and control characters escaped (`\b \f \n
 * \r \t` short, the rest `\u00xx` lowercase), everything else as UTF-8. A reader re-writes what it parsed and
 * requires the same bytes.
 */
export class CanonicalJson {
  private s = '';

  raw(text: string): this {
    this.s += text;
    return this;
  }

  string(value: string): this {
    let out = '"';
    for (const ch of value) {
      const c = ch.charCodeAt(0);
      if (ch === '"') out += '\\"';
      else if (ch === '\\') out += '\\\\';
      else if (ch === '\b') out += '\\b';
      else if (ch === '\f') out += '\\f';
      else if (ch === '\n') out += '\\n';
      else if (ch === '\r') out += '\\r';
      else if (ch === '\t') out += '\\t';
      else if (c < 0x20) out += '\\u00' + c.toString(16).padStart(2, '0');
      else out += ch;
    }
    this.s += out + '"';
    return this;
  }

  number(value: number): this {
    if (!Number.isSafeInteger(value)) throw new RangeError('not a safe integer');
    this.s += String(value);
    return this;
  }

  toString(): string {
    return this.s;
  }

  bytes(): Uint8Array {
    return new TextEncoder().encode(this.s);
  }
}

/** The largest integer both stacks read exactly. */
export const MAX_SAFE = Number.MAX_SAFE_INTEGER;

/** Parses UTF-8 JSON, or undefined when the bytes are not valid UTF-8 or not JSON. */
export function parseJson(bytes: Uint8Array): unknown {
  let text: string;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return undefined;
  }
  // A byte-order mark would be dropped by TextDecoder; the canonical check would then fail, but refuse it here.
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export type Obj = Record<string, unknown>;

export function isObj(v: unknown): v is Obj {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** The object only if its keys are exactly `keys`. */
export function exactObj(v: unknown, ...keys: string[]): Obj | null {
  if (!isObj(v)) return null;
  const own = Object.keys(v);
  if (own.length !== keys.length) return null;
  for (const k of keys) if (!Object.prototype.hasOwnProperty.call(v, k)) return null;
  return v;
}

export function str(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}

/** An integer in [min, max]; anything else (a fraction, a string, a boolean) is null. */
export function int(v: unknown, min: number, max: number): number | null {
  return typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max ? v : null;
}
