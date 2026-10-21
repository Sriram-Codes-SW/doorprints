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

// The reader of a kind file a person imports against the shared vectors, the schema and hostile input (docs/03 ADR-39; docs/02 T-T23;
// docs/06 TC-U-292). The phones and the server read the same vectors.
import Ajv2020 from 'ajv/dist/2020';
import { describe, expect, it } from 'vitest';
import kindSchema from '../../../../../docs/schemas/kinds/kind.schema.json';
import userSchema from '../../../../../docs/schemas/kinds/user-kind.schema.json';
import houseJson from '../../../../../docs/schemas/kinds/house.json';
import schoolJson from '../../../../../docs/schemas/kinds/school.json';
import testAllJson from '../../../../../docs/schemas/kinds/test-all.json';
import { USER_KIND_VECTORS, caseText, userKindSample } from '../../shared/testing/user-kind-vectors';
import { FORBIDDEN_MAP_KEYS, USER_KIND_LIMITS, readUserKind, utf8Bytes } from './user-kind-file';
import { USER_KIND_ID, isUserKindId } from './user-kind-ids';
import { searchOf } from './user-kinds';
import capabilitiesVectors from '../../../../../docs/schemas/kinds/capabilities-vectors.json';

const ajv = new Ajv2020({ strict: false, allErrors: false });
ajv.addSchema(kindSchema);
const schemaAccepts = ajv.compile(userSchema);
const builtInAccepts = ajv.getSchema('https://doorprints.web.app/schemas/kinds/kind.schema.json')!;

/** A small seeded random source, so a fuzz failure is reproducible from its seed. */
function rng(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

describe('a kind file a person adds (user-kind-vectors.json, ADR-39)', () => {
  it('keeps the limits the vectors and the schema name', () => {
    const { idPattern, ...limits } = USER_KIND_VECTORS.limits;
    expect({ ...USER_KIND_LIMITS }).toEqual(limits);
    expect(USER_KIND_ID.source).toBe(idPattern);
    expect((userSchema as any).properties.id.pattern).toBe(idPattern);
    expect((userSchema as any).properties.extras.maxItems).toBe(USER_KIND_LIMITS.maxExtras);
    expect((userSchema as any).properties.checklist.maxItems).toBe(USER_KIND_LIMITS.maxChecklist);
    expect((userSchema as any).properties.note.maxLength).toBe(USER_KIND_LIMITS.maxNote);
    expect((kindSchema as any).$defs.userText.maxLength).toBe(USER_KIND_LIMITS.maxText);
    expect([...FORBIDDEN_MAP_KEYS]).toEqual((capabilitiesVectors as { forbiddenMapKeys: string[] }).forbiddenMapKeys);
  });

  it('reads every valid sample as the vectors resolve it, and the schema takes it too', () => {
    expect(USER_KIND_VECTORS.valid.length).toBeGreaterThanOrEqual(4);
    for (const v of USER_KIND_VECTORS.valid) {
      const read = readUserKind(JSON.stringify(v.file));
      if (!read.ok) throw new Error(`${v.name}: ${read.fault.code} at ${read.fault.path}`);
      expect(read.file.id, v.name).toBe(v.resolved.id);
      expect(read.file.labels.many.text, v.name).toBe(v.resolved.many);
      expect(read.file.extras.map((e) => e.id), v.name).toEqual(v.resolved.extras);
      expect(read.file.checklist.map((c) => c.key), v.name).toEqual(v.resolved.checklist);
      expect(searchOf(read.file), v.name).toEqual(v.resolved.search);
      expect(read.capabilities, v.name).toEqual(v.resolved.capabilities);
      expect(schemaAccepts(v.file), `${v.name}: ${JSON.stringify(schemaAccepts.errors?.slice(0, 2))}`).toBe(true);
      // Read again from what it gave: the same file (the store and the backup keep the checked file).
      const again = readUserKind(JSON.stringify(read.file));
      expect(again.ok && again.file, v.name).toEqual(read.file);
    }
  });

  it('refuses every invalid case with the fault and the place the vectors name; the schema agrees where it can say', () => {
    expect(USER_KIND_VECTORS.invalid.length).toBeGreaterThan(100);
    for (const c of USER_KIND_VECTORS.invalid) {
      expect(USER_KIND_VECTORS.codes, c.name).toContain(c.code);
      const read = readUserKind(caseText(c));
      expect(read.ok, c.name).toBe(false);
      if (!read.ok) expect(read.fault, c.name).toEqual({ code: c.code, path: c.path });
      if (c.schema !== null) {
        const parsed: unknown = JSON.parse(caseText(c));
        expect(schemaAccepts(parsed), `${c.name} (schema)`).toBe(!c.schema);
      }
    }
    // Every code is used by a case, so no rule of the reader goes untested.
    for (const code of USER_KIND_VECTORS.codes) expect(USER_KIND_VECTORS.invalid.some((c) => c.code === code), code).toBe(true);
  });

  it('never lets a built-in kind file through, nor names a built-in kind with the prefix', () => {
    for (const file of [houseJson, schoolJson, testAllJson] as { id: string }[]) {
      expect(isUserKindId(file.id), file.id).toBe(false);
      expect(builtInAccepts(file), file.id).toBe(true);
      const read = readUserKind(JSON.stringify(file));
      expect(read.ok, file.id).toBe(false);
    }
    for (const id of USER_KIND_VECTORS.reserved) {
      expect(isUserKindId(id)).toBe(false);
      const gym = userKindSample('u_gym');
      gym['id'] = id;
      const read = readUserKind(JSON.stringify(gym));
      expect(!read.ok && read.fault.code).toBe('id');
    }
    // The reserved list is a second lock: a u_ id the build has is refused too.
    const read = readUserKind(JSON.stringify(userKindSample('u_gym')), ['u_gym']);
    expect(!read.ok && read.fault).toEqual({ code: 'reserved', path: '$.id' });
  });

  it('keeps the built-in files on key labels: no built-in file uses a person’s words', () => {
    const texts: string[] = [];
    const walk = (n: unknown, path: string) => {
      if (Array.isArray(n)) n.forEach((x, i) => walk(x, `${path}[${i}]`));
      else if (n && typeof n === 'object') {
        if (Object.prototype.hasOwnProperty.call(n, 'text') && Object.keys(n).length === 1) texts.push(path);
        for (const [k, v] of Object.entries(n)) walk(v, `${path}.${k}`);
      }
    };
    for (const file of [houseJson, schoolJson, testAllJson]) walk(file, (file as { id: string }).id);
    expect(texts).toEqual([]);
  });

  it('leaves Object.prototype alone whatever the file holds', () => {
    for (const c of USER_KIND_VECTORS.invalid) readUserKind(caseText(c));
    readUserKind('{"__proto__":{"polluted":"yes"},"constructor":{"prototype":{"polluted":"yes"}}}');
    expect(({} as Record<string, unknown>)['polluted']).toBeUndefined();
    expect(({} as Record<string, unknown>)['isAdmin']).toBeUndefined();
    expect(Object.prototype.hasOwnProperty.call(Object.prototype, 'polluted')).toBe(false);
  });

  it('answers a hostile file of the largest size quickly (no slow pattern, no deep recursion)', () => {
    const hostile = [
      '['.repeat(65_000),
      '{"a":' + '{"a":'.repeat(13_000),
      '"' + 'a'.repeat(65_000) + '"',
      '[' + '0,'.repeat(32_000) + '0]',
      JSON.stringify({ ...userKindSample('u_gym'), id: 'u_' + 'a'.repeat(1990) + '!' }),
      JSON.stringify({ ...userKindSample('u_gym'), labels: { new: { text: 'a'.repeat(59) + '<' }, many: { text: 'b' }, delete: { text: 'c' } } }),
      '{"k":"' + '\\u0061'.repeat(10_000) + '"}',
    ];
    for (const text of hostile) {
      const start = performance.now();
      const read = readUserKind(text);
      const ms = performance.now() - start;
      expect(read.ok).toBe(false);
      expect(ms, text.slice(0, 20)).toBeLessThan(250);
    }
  });

  it('counts UTF-8 bytes as the phones and the server do', () => {
    expect(utf8Bytes('a')).toBe(1);
    expect(utf8Bytes('é')).toBe(2);
    expect(utf8Bytes('अ')).toBe(3);
    expect(utf8Bytes('😀')).toBe(4);
    expect(utf8Bytes('\ud800')).toBe(3);
    expect(utf8Bytes('x'.repeat(65_536))).toBe(USER_KIND_LIMITS.maxBytes);
    expect(readUserKind(' '.repeat(65_537)).ok).toBe(false);
  });

  it('fuzz: a thousand random changes to a valid file never throw, and what is accepted keeps to the rules', () => {
    const next = rng(20261020);
    const hostileWords = ['<b>', '{x}', '\u202e', '\u0000', ' x', 'x ', '', 'a'.repeat(61), '\ud800', '__proto__', 'constructor', 'style', 'tiles', 'Gym', 'ठीक', 'சரி'];
    const values: unknown[] = [null, true, 0, -1, 1.5, 1e309, 'x', [], {}, { text: 'ok' }, { web: 'house.name', android: 'house_name' }, ...hostileWords];
    const pick = <T>(list: readonly T[]): T => list[Math.floor(next() * list.length)];
    const paths = (n: unknown, path: (string | number)[], out: (string | number)[][]) => {
      out.push(path);
      if (Array.isArray(n)) n.forEach((x, i) => paths(x, [...path, i], out));
      else if (n && typeof n === 'object') for (const [k, v] of Object.entries(n)) paths(v, [...path, k], out);
      return out;
    };
    let accepted = 0;
    for (let round = 0; round < 1000; round++) {
      const file = userKindSample(pick(['u_gym', 'u_cafe', 'u_office_space']));
      const all = paths(file, [], []).filter((p) => p.length > 0);
      const changes = 1 + Math.floor(next() * 3);
      for (let i = 0; i < changes; i++) {
        const path = pick(all);
        let o: any = file;
        for (const k of path.slice(0, -1)) o = o?.[k];
        if (!o || typeof o !== 'object') continue;
        const last = path[path.length - 1];
        const r = next();
        if (r < 0.3) delete o[last];
        else if (r < 0.6) Object.defineProperty(o, last, { value: pick(values), enumerable: true, writable: true, configurable: true });
        else if (r < 0.8 && typeof o[last] === 'string') o[last] = o[last] + pick(hostileWords);
        else Object.defineProperty(o, pick(['__proto__', 'constructor', 'style', 'extra', 'tiles', 'text']), { value: pick(values), enumerable: true, writable: true, configurable: true });
      }
      const text = JSON.stringify(file);
      let read: ReturnType<typeof readUserKind> | undefined;
      expect(() => (read = readUserKind(text)), `round ${round}`).not.toThrow();
      if (read!.ok) {
        accepted++;
        const again = readUserKind(JSON.stringify(read!.file));
        expect(again.ok, `round ${round} again`).toBe(true);
        expect(schemaAccepts(JSON.parse(text)), `round ${round}: the schema takes what the reader takes`).toBe(true);
        const keys: string[] = [];
        const texts: string[] = [];
        const walk = (n: unknown) => {
          if (Array.isArray(n)) n.forEach(walk);
          else if (n && typeof n === 'object') for (const [k, v] of Object.entries(n)) { keys.push(k); if (k === 'text') texts.push(v as string); walk(v); }
        };
        walk(read!.file);
        for (const bad of ['__proto__', 'constructor', 'prototype', ...FORBIDDEN_MAP_KEYS]) expect(keys, `round ${round} ${bad}`).not.toContain(bad);
        for (const t of texts) expect(/[<>{}]/.test(t) || t.trim() !== t || t.length > 60, `round ${round} ${t}`).toBe(false);
      } else {
        expect(USER_KIND_VECTORS.codes).toContain(read!.fault.code);
      }
    }
    expect(accepted).toBeGreaterThan(0);
    // Random text never throws either.
    for (let round = 0; round < 300; round++) {
      const n = Math.floor(next() * 200);
      const text = Array.from({ length: n }, () => String.fromCharCode(Math.floor(next() * 0x80))).join('');
      expect(() => readUserKind(text)).not.toThrow();
      expect(readUserKind(text).ok).toBe(false);
    }
  });
});
