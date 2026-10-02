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

import { describe, expect, it } from 'vitest';
import vectorsJson from '../../../../../docs/schemas/drive-vectors.json';
import { sha256Hex } from '../../export/sha256';
import { DriveError, DriveRetry, toQ } from './drive-client';
import type { DriveErrorKind, DriveQuery, NewFile } from './drive-client';
import { listAll, uploadResumable } from './drive-ops';
import { multipartParts } from './fake-drive-http';
import { FakeTokenProvider } from './fake-drive-faults';
import { FetchDriveClient } from './fetch-drive-client';

/**
 * The shared Drive vectors (`docs/schemas/drive-vectors.json`, docs/schemas/README.md section 6.2): the same `q`
 * strings, requests, answers, error mapping and backoff as Kotlin's `DriveVectorsTest`.
 */

type Obj = Record<string, unknown>;
interface Step { request: Obj; response?: { status: number; headers: Record<string, string>; body: unknown }; drop?: boolean }
interface Exchange { name: string; call: Obj; steps: Step[]; result: Obj }
interface ErrorCase { name: string; status: number; headers: Record<string, string>; body: unknown; kind: string; retryAfterMs: number | null; reason: string | null; retryable: boolean; nowMs?: number }
interface BackoffCase { attempt: number; random: number; kind: string; retryAfterMs: number | null; waitMs: number | null; maxAttempts?: number }
const vectors = vectorsJson as unknown as {
  format: string;
  queries: { name: string; query: Obj; q: string }[];
  exchanges: Exchange[];
  errors: ErrorCase[];
  backoff: { config: { maxAttempts: number; baseMs: number; capMs: number; maxRetryAfterMs: number }; cases: BackoffCase[] };
};

function query(o: Obj): DriveQuery {
  return {
    parentId: (o['parentId'] as string) ?? null,
    appProperties: (o['appProperties'] as Record<string, string>) ?? {},
    mimeType: (o['mimeType'] as string) ?? null,
    name: (o['name'] as string) ?? null,
    trashed: 'trashed' in o ? (o['trashed'] as boolean | null) : false,
  };
}

const same = (a: Uint8Array | null, b: Uint8Array) => a != null && a.length === b.length && a.every((v, i) => v === b[i]);
const content = (n: number, seed: number) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + seed) % 251);

function respond(status: number, headers: Record<string, string>, body: unknown): Response {
  const text = body == null ? null : typeof body === 'string' ? body : JSON.stringify(body);
  return new Response(status === 204 || status === 308 && text == null ? null : text, { status, headers });
}

describe('Drive vectors (shared with Kotlin)', () => {
  it('reads the vectors file it was written for', () => {
    expect(vectors.format).toBe('doorprints-drive-vectors/1');
  });

  it('writes every query the same', () => {
    for (const c of vectors.queries) expect(toQ(query(c.query)), c.name).toBe(c.q);
  });

  it('maps every error the same', async () => {
    for (const c of vectors.errors) {
      const fetch: typeof globalThis.fetch = async () => respond(c.status, c.headers, c.body);
      const client = new FetchDriveClient(new FakeTokenProvider(), { fetch, retry: new DriveRetry({ maxAttempts: 1 }), now: () => c.nowMs ?? 0 });
      let error: DriveError | null = null;
      try {
        await client.getFile('fA');
      } catch (e) {
        error = e as DriveError;
      }
      expect(error, c.name).toBeInstanceOf(DriveError);
      expect(error!.kind, c.name).toBe(c.kind);
      expect(error!.retryAfterMs, c.name).toBe(c.retryAfterMs);
      expect(error!.reason, c.name).toBe(c.reason);
      expect(new DriveRetry().waitFor(1, error!) != null, c.name).toBe(c.retryable);
      expect(error!.message).not.toContain('token');
    }
  });

  it('waits the same backoff', () => {
    const config = vectors.backoff.config;
    for (const c of vectors.backoff.cases) {
      const retry = new DriveRetry({ ...config, maxAttempts: c.maxAttempts ?? config.maxAttempts, random: () => c.random });
      expect(retry.waitFor(c.attempt, new DriveError(c.kind as DriveErrorKind, 0, c.retryAfterMs)), JSON.stringify(c)).toBe(c.waitMs);
    }
    expect(new DriveRetry().maxAttempts).toBe(config.maxAttempts);
    expect(new DriveRetry().capMs).toBe(config.capMs);
  });

  for (const c of vectors.exchanges) {
    it(`asks and reads the same: ${c.name}`, async () => {
      const call = c.call;
      const upload = typeof call['size'] === 'number' ? content(call['size'], call['seed'] as number) : null;
      let n = 0;
      const fetch: typeof globalThis.fetch = async (input, init) => {
        const step = c.steps[n++];
        expect(step, `request ${n} not in the vector`).toBeDefined();
        const want = step.request;
        const url = new URL(input as string);
        const where = `step ${n}`;
        expect(init?.method, where).toBe(want['method']);
        expect(`${url.protocol}//${url.host}${url.pathname}`, where).toBe(want['url']);
        expect(Object.fromEntries(url.searchParams.entries()), where).toEqual(want['params']);
        const headers = new Headers(init?.headers);
        for (const [k, v] of Object.entries(want['headers'] as Record<string, string>)) expect(headers.get(k), `${where} ${k}`).toBe(v);
        const raw = init?.body;
        const body = raw == null ? new Uint8Array(0) : typeof raw === 'string' ? new TextEncoder().encode(raw) : (raw as Uint8Array);
        if ('json' in want) {
          expect(headers.get('Content-Type') ?? '', where).toMatch(/^application\/json/);
          expect(JSON.parse(new TextDecoder().decode(body)), where).toEqual(want['json']);
        }
        if ('bodyLength' in want) {
          const from = want['bodyFrom'] as number;
          expect(same(body, upload!.slice(from, from + (want['bodyLength'] as number))), where).toBe(true);
        }
        if ('multipart' in want) {
          const m = want['multipart'] as Obj;
          const type = headers.get('Content-Type') ?? '';
          expect(type, where).toMatch(/^multipart\/related; boundary=/);
          const parts = multipartParts(type, body);
          expect(parts.meta, where).toEqual(m['json']);
          expect(parts.mime, where).toBe(m['mimeType']);
          expect(same(parts.content, upload!), where).toBe(true);
        }
        if (step.drop) throw new TypeError('Failed to fetch');
        const r = step.response!;
        return respond(r.status, r.headers, r.body);
      };
      const sleeps: number[] = [];
      const tokens = new FakeTokenProvider();
      const drive = new FetchDriveClient(tokens, { fetch, retry: new DriveRetry({ random: () => 0.5, sleep: async (ms) => void sleeps.push(ms) }) });
      const result = c.result;
      const file = call['file'] as NewFile | undefined;
      try {
        switch (call['op']) {
          case 'listAll': {
            const files = await listAll(drive, query(call['query'] as Obj), call['pageSize'] as number);
            expect(files.map((f) => f.id)).toEqual(result['ids']);
            const first = result['first'] as Obj;
            expect(files[0].size).toBe(first['size']);
            expect(files[0].sha256Checksum).toBe(first['sha256Checksum']);
            expect(files[0].createdTime).toBe(first['createdTime']);
            expect(files[0].appProperties).toEqual(first['appProperties']);
            expect(files[0].parents).toEqual(first['parents']);
            break;
          }
          case 'getFile':
            expect((await drive.getFile(call['fileId'] as string)).id).toBe(result['id']);
            break;
          case 'about': {
            const about = await drive.about();
            expect(about.email).toBe(result['email']);
            expect(about.quotaLimit).toBe(result['quotaLimit']);
            expect(about.quotaUsage).toBe(result['quotaUsage']);
            break;
          }
          case 'uploadResumable': {
            const made = await uploadResumable(drive, { kind: 'new', file: file! }, upload!.length,
              (offset, length) => upload!.slice(offset, offset + length), { chunkSize: call['chunkSize'] as number });
            expect(made.id).toBe(result['id']);
            expect(made.sha256Checksum).toBe(result['sha256Checksum']);
            expect(made.sha256Checksum).toBe(sha256Hex(upload!));
            break;
          }
          case 'updateMetadata': {
            const change = call['change'] as { name: string; appProperties: Record<string, string | null> };
            expect((await drive.updateMetadata(call['fileId'] as string, change)).id).toBe(result['id']);
            break;
          }
          case 'delete':
            await drive.delete(call['fileId'] as string);
            break;
          case 'upload':
            await drive.upload({ kind: 'new', file: file! }, upload!);
            break;
          default:
            throw new Error(`unknown op ${String(call['op'])}`);
        }
        expect(result['error']).toBeUndefined();
      } catch (e) {
        if (!(e instanceof DriveError)) throw e;
        expect(e.kind).toBe(result['error']);
      }
      expect(n).toBe(c.steps.length);
      expect(sleeps).toEqual(result['sleeps']);
      if ('rejected' in result) expect(tokens.rejected).toEqual(result['rejected']);
    });
  }
});
