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
 * A strict look at a JSON text before it is parsed (docs/03 ADR-38, docs/02 T-T25): one document and nothing after it, no
 * key twice in one object (`JSON.parse` would keep the last, where another reader might keep the first), at most `maxDepth`
 * arrays and objects inside one another, at most `maxValues` values and no string longer than `maxString` characters. It
 * walks the text once, keeps only the keys of the objects it is inside, and stops at the first fault, so a hostile file
 * (deep nesting, a huge list, a long string) costs at most one pass over text that is already capped in size. The answers
 * are the ones the phones' and the server's readers give (`docs/schemas/kinds/user-kind-vectors.json`).
 */

export type ScanCode = 'notJson' | 'duplicateKey' | 'tooDeep' | 'tooMany' | 'tooLong';

export interface ScanFault {
  readonly code: ScanCode;
  /** Where: `$` for the document, `.key` for an object's key, `[n]` for a list's item. */
  readonly path: string;
}

export interface ScanLimits {
  readonly maxDepth: number;
  readonly maxValues: number;
  readonly maxString: number;
}

class Stop {
  constructor(readonly fault: ScanFault) {}
}

const NUMBER = /-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/y;

/** The first fault of `text` as a JSON document within `limits`, or null when it is one well-formed document within them. */
export function scanJson(text: string, limits: ScanLimits): ScanFault | null {
  let i = 0;
  let values = 0;
  // A syntax fault is the document's (`$`): where a broken text breaks is no help to the person, and the readers agree on `$`.
  const fail = (code: ScanCode, path: string): never => {
    throw new Stop({ code, path: code === 'notJson' ? '$' : path });
  };
  const space = () => {
    while (i < text.length) {
      const c = text.charCodeAt(i);
      if (c === 0x20 || c === 0x09 || c === 0x0a || c === 0x0d) i++;
      else break;
    }
  };
  const string = (path: string): string => {
    const start = i;
    i++;
    while (i < text.length) {
      const c = text.charCodeAt(i);
      if (c === 0x22) {
        i++;
        // The escapes are checked, and the text decoded, by the platform's own parser of this one token.
        const s = JSON.parse(text.slice(start, i)) as string;
        if (s.length > limits.maxString) fail('tooLong', path);
        return s;
      }
      if (c === 0x5c) i += 2;
      else if (c < 0x20) fail('notJson', path);
      else i++;
    }
    return fail('notJson', path);
  };
  const value = (path: string, depth: number): void => {
    values++;
    if (values > limits.maxValues) fail('tooMany', path);
    space();
    const c = text[i];
    if (c === '{' || c === '[') {
      if (depth >= limits.maxDepth) fail('tooDeep', path);
      i++;
      space();
      const close = c === '{' ? '}' : ']';
      if (text[i] === close) {
        i++;
        return;
      }
      const seen = c === '{' ? new Set<string>() : null;
      for (let n = 0; ; n++) {
        let item = `${path}[${n}]`;
        if (seen) {
          space();
          if (text[i] !== '"') fail('notJson', path);
          const key = string(path);
          item = `${path}.${key}`;
          if (seen.has(key)) fail('duplicateKey', item);
          seen.add(key);
          space();
          if (text[i] !== ':') fail('notJson', item);
          i++;
        }
        value(item, depth + 1);
        space();
        if (text[i] === ',') {
          i++;
          continue;
        }
        if (text[i] === close) {
          i++;
          return;
        }
        fail('notJson', item);
      }
    }
    if (c === '"') {
      string(path);
      return;
    }
    for (const word of ['true', 'false', 'null']) {
      if (text.startsWith(word, i)) {
        i += word.length;
        return;
      }
    }
    NUMBER.lastIndex = i;
    if (NUMBER.exec(text)) {
      i = NUMBER.lastIndex;
      return;
    }
    fail('notJson', path);
  };
  try {
    value('$', 0);
    space();
    return i === text.length ? null : { code: 'notJson', path: '$' };
  } catch (e) {
    if (e instanceof Stop) return e.fault;
    return { code: 'notJson', path: '$' };
  }
}
