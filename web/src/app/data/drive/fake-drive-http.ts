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

import { DriveError } from './drive-client';
import type { DriveQuery, MetadataChange, NewFile, UploadTarget } from './drive-client';
import { fileToWire } from './drive-wire';
import type { FakeDriveServer } from './in-memory-fake-drive';

/**
 * The fake Drive behind Drive v3's HTTP surface, as a `fetch` (S4b-BL-115; Kotlin `FakeDriveHttp`): `FetchDriveClient`
 * talks to it as to Google, so `drive-contract.spec.ts` runs the same cases on the real client and on the fake. It reads
 * only what the client sends, answers as Drive does, and turns a no-answer fault into a rejected fetch. Tests only.
 */
export class FakeDriveHttp {
  readonly seen: { method: string; url: string; headers: Headers }[] = [];

  constructor(readonly server: FakeDriveServer) {}

  readonly fetch: typeof fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url);
    const method = init?.method ?? 'GET';
    const headers = new Headers(init?.headers);
    this.seen.push({ method, url: url.toString(), headers });
    if (url.protocol !== 'https:' || url.hostname !== 'www.googleapis.com') throw new Error(`not Google: ${url.hostname}`);
    const token = (headers.get('Authorization') ?? '').replace(/^Bearer /, '');
    const body = toBytes(init?.body);
    const path = url.pathname;
    const p = url.searchParams;
    try {
      if (path === '/drive/v3/about') {
        const a = await this.server.about(token);
        return json({
          user: { emailAddress: a.email, displayName: a.displayName },
          storageQuota: { ...(a.quotaLimit != null ? { limit: String(a.quotaLimit) } : {}), usage: String(a.quotaUsage), usageInDrive: String(a.quotaUsageInDrive) },
        });
      }
      if (path === '/drive/v3/files' && method === 'GET') {
        const page = await this.server.list(token, parseQ(p.get('q') ?? ''), p.get('pageToken'), Number(p.get('pageSize')));
        return json({ files: page.files.map(fileToWire), ...(page.nextPageToken ? { nextPageToken: page.nextPageToken } : {}), incompleteSearch: false });
      }
      if (path === '/drive/v3/files' && method === 'POST') return json(fileToWire(await this.server.create(token, newFile(obj(body)))));
      if (path.startsWith('/drive/v3/files/') && path.endsWith('/revisions')) {
        const id = path.slice('/drive/v3/files/'.length, -'/revisions'.length);
        const revs = await this.server.revisions(token, id);
        return json({ revisions: revs.map((r) => ({ id: r.id, modifiedTime: new Date(r.modifiedTime).toISOString(), size: String(r.size) })) });
      }
      if (path.startsWith('/drive/v3/files/')) {
        const id = path.slice('/drive/v3/files/'.length);
        if (method === 'GET' && p.get('alt') === 'media') {
          const range = headers.get('Range');
          const r = range ? range.replace('bytes=', '').split('-').map(Number) : null;
          const bytes = await this.server.download(token, id, r ? { first: r[0], last: r[1] } : null);
          return new Response(bytes as BodyInit, { status: r ? 206 : 200, headers: { 'Content-Type': 'application/octet-stream' } });
        }
        if (method === 'GET') return json(fileToWire(await this.server.get(token, id)));
        if (method === 'DELETE') {
          await this.server.delete(token, id);
          return new Response(null, { status: 204 });
        }
        if (method === 'PATCH') {
          const o = obj(body);
          if (o['trashed'] === true) return json(fileToWire(await this.server.trash(token, id)));
          return json(fileToWire(await this.server.update(token, id, change(o, p.get('addParents'), p.get('removeParents')))));
        }
      }
      if (path.startsWith('/upload/drive/v3/files') && p.get('upload_id')) {
        const range = headers.get('Content-Range') ?? '';
        const progress = range.startsWith('bytes */')
          ? await this.server.uploadStatus(token, url.toString())
          : await this.server.uploadChunk(token, url.toString(), Number(range.slice('bytes '.length).split('-')[0]), body);
        if (progress.kind === 'complete') return json(fileToWire(progress.file));
        return new Response(null, { status: 308, headers: progress.received > 0 ? { Range: `bytes=0-${progress.received - 1}` } : {} });
      }
      if (path.startsWith('/upload/drive/v3/files')) {
        const existing = path.slice('/upload/drive/v3/files'.length).replace(/^\//, '') || null;
        if (p.get('uploadType') === 'multipart') {
          const { meta, mime, content } = multipartParts(headers.get('Content-Type') ?? '', body);
          return json(fileToWire(await this.server.upload(token, target(existing, meta, mime), content)));
        }
        if (p.get('uploadType') === 'resumable') {
          const s = await this.server.startUpload(token, target(existing, obj(body), headers.get('X-Upload-Content-Type') ?? ''),
            Number(headers.get('X-Upload-Content-Length')));
          return new Response(null, { status: 200, headers: { Location: s.uri } });
        }
      }
      throw new Error(`unexpected ${method} ${path}`);
    } catch (e) {
      if (!(e instanceof DriveError)) throw e;
      if (e.kind === 'OFFLINE') throw new TypeError('Failed to fetch');
      const reason = e.reason ? { errors: [{ domain: 'global', reason: e.reason, message: 'x' }] } : {};
      const h: Record<string, string> = { 'Content-Type': 'application/json; charset=UTF-8' };
      if (e.retryAfterMs != null) h['Retry-After'] = String(Math.ceil(e.retryAfterMs / 1000));
      return new Response(JSON.stringify({ error: { code: e.httpStatus, message: 'x', ...reason } }), { status: e.httpStatus, headers: h });
    }
  };
}

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json; charset=UTF-8' } });
}

function toBytes(body: unknown): Uint8Array {
  if (body == null) return new Uint8Array(0);
  if (typeof body === 'string') return new TextEncoder().encode(body);
  if (body instanceof Uint8Array) return body;
  throw new Error('unexpected body');
}

function obj(body: Uint8Array): Record<string, unknown> {
  return body.length ? (JSON.parse(new TextDecoder().decode(body)) as Record<string, unknown>) : {};
}

function newFile(o: Record<string, unknown>): NewFile {
  return {
    name: (o['name'] as string) ?? '',
    mimeType: (o['mimeType'] as string) ?? 'application/octet-stream',
    parents: (o['parents'] as string[]) ?? [],
    appProperties: (o['appProperties'] as Record<string, string>) ?? {},
  };
}

function change(o: Record<string, unknown>, add: string | null, remove: string | null): MetadataChange {
  return {
    name: (o['name'] as string | undefined) ?? null,
    appProperties: (o['appProperties'] as Record<string, string | null>) ?? {},
    addParents: add ? add.split(',') : [],
    removeParents: remove ? remove.split(',') : [],
  };
}

function target(existing: string | null, meta: Record<string, unknown>, mime: string): UploadTarget {
  return existing
    ? { kind: 'existing', fileId: existing, mimeType: mime, change: change(meta, null, null) }
    : { kind: 'new', file: { ...newFile(meta), mimeType: mime } };
}

function find(body: Uint8Array, needle: string, from: number): number {
  const n = new TextEncoder().encode(needle);
  outer: for (let i = from; i + n.length <= body.length; i++) {
    for (let j = 0; j < n.length; j++) if (body[i + j] !== n[j]) continue outer;
    return i;
  }
  throw new Error(`no '${needle}' in the multipart body`);
}

/** Drive's multipart/related, read as bytes: the JSON part, then the content part. */
export function multipartParts(contentType: string, body: Uint8Array): { meta: Record<string, unknown>; mime: string; content: Uint8Array } {
  const boundary = contentType.split('boundary=')[1];
  const metaStart = find(body, '\r\n\r\n', 0) + 4;
  const metaEnd = find(body, `\r\n--${boundary}\r\n`, metaStart);
  const secondHead = metaEnd + `\r\n--${boundary}\r\n`.length;
  const contentStart = find(body, '\r\n\r\n', secondHead) + 4;
  const mime = new TextDecoder().decode(body.slice(secondHead, contentStart - 4)).replace('Content-Type: ', '').trim();
  const end = body.length - `\r\n--${boundary}--\r\n`.length;
  return { meta: JSON.parse(new TextDecoder().decode(body.slice(metaStart, metaEnd))), mime, content: body.slice(contentStart, end) };
}

/** Reads back what `toQ` writes (and nothing else). */
export function parseQ(q: string): DriveQuery {
  let i = 0;
  const literal = (): string => {
    if (q[i] !== "'") throw new Error(`literal at ${i}`);
    i++;
    let out = '';
    while (q[i] !== "'") {
      if (q[i] === '\\') i++;
      out += q[i++];
    }
    i++;
    return out;
  };
  const expect = (text: string) => {
    if (!q.startsWith(text, i)) throw new Error(`expected '${text}' at ${i} in ${q}`);
    i += text.length;
  };
  const query: { parentId?: string; name?: string; mimeType?: string; appProperties: Record<string, string>; trashed: boolean | null } =
    { appProperties: {}, trashed: null };
  while (i < q.length) {
    if (q[i] === "'") {
      query.parentId = literal();
      expect(' in parents');
    } else if (q.startsWith('name = ', i)) {
      expect('name = ');
      query.name = literal();
    } else if (q.startsWith('mimeType = ', i)) {
      expect('mimeType = ');
      query.mimeType = literal();
    } else if (q.startsWith('appProperties has { key=', i)) {
      expect('appProperties has { key=');
      const k = literal();
      expect(' and value=');
      query.appProperties[k] = literal();
      expect(' }');
    } else if (q.startsWith('trashed = ', i)) {
      expect('trashed = ');
      query.trashed = q.startsWith('true', i);
      i += query.trashed ? 4 : 5;
    } else {
      throw new Error(`cannot read q at ${i}: ${q}`);
    }
    if (i < q.length) expect(' and ');
  }
  return query;
}
