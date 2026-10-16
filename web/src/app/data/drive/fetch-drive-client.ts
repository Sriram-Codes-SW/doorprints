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

import {
  DRIVE_FIELDS,
  DriveError,
  DriveRetry,
  MULTIPART_LIMIT,
  authorized,
  checkId,
  corrupt,
  driveErrorFromHttp,
  targetMime,
  toQ,
} from './drive-client';
import type {
  ByteRange,
  DriveAbout,
  DriveClient,
  DriveFile,
  DrivePage,
  DriveQuery,
  DriveRevision,
  MetadataChange,
  NewFile,
  TokenProvider,
  UploadProgress,
  UploadSession,
  UploadTarget,
} from './drive-client';
import { aboutFromWire, changeJson, fileFromWire, newFileJson, revisionFromWire } from './drive-wire';

/** Base URL of the Drive v3 metadata API. */
export const DRIVE_API = 'https://www.googleapis.com/drive/v3';
/** Base URL of the Drive v3 upload API. */
export const DRIVE_UPLOAD = 'https://www.googleapis.com/upload/drive/v3';

/** Only Google's API host, over https, ever sees the token. */
export function isGoogleApi(target: string): boolean {
  try {
    const url = new URL(target);
    return url.protocol === 'https:' && url.hostname === 'www.googleapis.com' && (url.port === '' || url.port === '443');
  } catch {
    return false;
  }
}

/** `base/path` with the query `params` (null values left out). */
export function driveUrl(base: string, path: string, params: Record<string, string | null | undefined> = {}): string {
  const url = new URL(`${base}/${path}`);
  for (const [k, v] of Object.entries(params)) if (v != null) url.searchParams.append(k, v);
  return url.toString();
}

/** Seams of `FetchDriveClient`: the fetch function and retry policy (tests pass fakes). */
export interface FetchDriveClientOptions {
  fetch?: typeof fetch;
  retry?: DriveRetry;
  now?: () => number;
  random?: () => number;
}

interface Exchange {
  status: number;
  headers: Headers;
  body: Uint8Array;
}

/**
 * {@link DriveClient} over the browser's `fetch` against Drive v3 (S4b-BL-115; Kotlin `HttpDriveClient`). The token goes
 * only to `https://www.googleapis.com`; cookies are never sent (`credentials: 'omit'`). Ordinary calls use
 * `redirect: 'manual'`, so a captive portal's redirect is OFFLINE and never followed; a session PUT uses `follow`,
 * because the fetch standard returns Google's `308 Resume Incomplete` (it has no `Location`) as it is only then
 * (the spike S4b-BL-122 confirms Google's CORS exposes `Location` and `Range` to the website).
 */
export class FetchDriveClient implements DriveClient {
  readonly retry: DriveRetry;
  private readonly fetchFn: typeof fetch;
  private readonly now: () => number;
  private readonly random: () => number;

  constructor(private readonly tokens: TokenProvider, options: FetchDriveClientOptions = {}) {
    this.retry = options.retry ?? new DriveRetry();
    this.fetchFn = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
    this.now = options.now ?? Date.now;
    this.random = options.random ?? Math.random;
  }

  async about(): Promise<DriveAbout> {
    return aboutFromWire(await this.json('GET', driveUrl(DRIVE_API, 'about', { fields: DRIVE_FIELDS.about })));
  }

  async list(query: DriveQuery, pageToken: string | null = null, pageSize = 100): Promise<DrivePage> {
    if (pageSize < 1 || pageSize > 1000) throw new Error('pageSize');
    const target = driveUrl(DRIVE_API, 'files', {
      q: toQ(query), fields: DRIVE_FIELDS.list, pageSize: String(pageSize), orderBy: 'createdTime', spaces: 'drive', pageToken,
    });
    const page = (await this.json('GET', target)) as { files?: unknown[]; nextPageToken?: unknown; incompleteSearch?: unknown };
    if (page.files != null && !Array.isArray(page.files)) throw corrupt();
    return {
      files: (page.files ?? []).map(fileFromWire),
      nextPageToken: typeof page.nextPageToken === 'string' ? page.nextPageToken : null,
      incompleteSearch: page.incompleteSearch === true,
    };
  }

  async getFile(fileId: string): Promise<DriveFile> {
    return fileFromWire(await this.json('GET', driveUrl(DRIVE_API, `files/${checkId(fileId)}`, { fields: DRIVE_FIELDS.file })));
  }

  async createFile(file: NewFile): Promise<DriveFile> {
    return fileFromWire(await this.json('POST', driveUrl(DRIVE_API, 'files', { fields: DRIVE_FIELDS.file }), jsonBody(newFileJson(file))));
  }

  async upload(target: UploadTarget, content: Uint8Array): Promise<DriveFile> {
    if (content.length > MULTIPART_LIMIT) throw new Error('use uploadResumable above 5 MiB');
    const [method, path, meta] = this.uploadRequest(target);
    const boundary = this.boundaryFor(content);
    const body = multipart(boundary, JSON.stringify(meta), targetMime(target), content);
    const answer = await this.json(method, driveUrl(DRIVE_UPLOAD, path, { uploadType: 'multipart', fields: DRIVE_FIELDS.file }), {
      body, contentType: `multipart/related; boundary=${boundary}`,
    });
    return fileFromWire(answer);
  }

  async startUpload(target: UploadTarget, size: number): Promise<UploadSession> {
    if (size <= 0) throw new Error('size');
    const [method, path, meta] = this.uploadRequest(target);
    const answer = await this.call(method, driveUrl(DRIVE_UPLOAD, path, { uploadType: 'resumable', fields: DRIVE_FIELDS.file }), {
      ...jsonBody(meta),
      headers: { 'X-Upload-Content-Type': targetMime(target), 'X-Upload-Content-Length': String(size) },
    });
    const location = answer.headers.get('Location');
    if (!location) throw corrupt('noSession');
    if (!isGoogleApi(location)) throw corrupt('insecureUrl');
    return { uri: location, size, fileId: target.kind === 'existing' ? target.fileId : null };
  }

  uploadChunk(session: UploadSession, offset: number, bytes: Uint8Array): Promise<UploadProgress> {
    if (bytes.length === 0 || offset < 0 || offset + bytes.length > session.size) throw new Error('chunk');
    return this.progress(session, `bytes ${offset}-${offset + bytes.length - 1}/${session.size}`, bytes);
  }

  uploadStatus(session: UploadSession): Promise<UploadProgress> {
    return this.progress(session, `bytes */${session.size}`, new Uint8Array(0));
  }

  async updateMetadata(fileId: string, change: MetadataChange): Promise<DriveFile> {
    const join = (ids?: readonly string[]) => (ids?.length ? ids.map(checkId).join(',') : null);
    const target = driveUrl(DRIVE_API, `files/${checkId(fileId)}`, {
      fields: DRIVE_FIELDS.file, addParents: join(change.addParents), removeParents: join(change.removeParents),
    });
    return fileFromWire(await this.json('PATCH', target, jsonBody(changeJson(change))));
  }

  async download(fileId: string, range: ByteRange | null = null): Promise<Uint8Array> {
    if (range && (range.first < 0 || range.last < range.first)) throw new Error('range');
    const headers: Record<string, string> = range ? { Range: `bytes=${range.first}-${range.last}` } : {};
    return (await this.call('GET', driveUrl(DRIVE_API, `files/${checkId(fileId)}`, { alt: 'media' }), { headers })).body;
  }

  async delete(fileId: string): Promise<void> {
    try {
      await this.call('DELETE', driveUrl(DRIVE_API, `files/${checkId(fileId)}`));
    } catch (e) {
      if (!(e instanceof DriveError) || e.kind !== 'NOT_FOUND') throw e;
    }
  }

  async trash(fileId: string): Promise<DriveFile> {
    return fileFromWire(await this.json('PATCH', driveUrl(DRIVE_API, `files/${checkId(fileId)}`, { fields: DRIVE_FIELDS.file }), jsonBody({ trashed: true })));
  }

  async revisions(fileId: string): Promise<DriveRevision[]> {
    const out: DriveRevision[] = [];
    let token: string | null = null;
    do {
      const target = driveUrl(DRIVE_API, `files/${checkId(fileId)}/revisions`, { fields: DRIVE_FIELDS.revisions, pageSize: '200', pageToken: token });
      const page = (await this.json('GET', target)) as { revisions?: unknown[]; nextPageToken?: unknown };
      if (page.revisions != null && !Array.isArray(page.revisions)) throw corrupt();
      out.push(...(page.revisions ?? []).map(revisionFromWire));
      token = typeof page.nextPageToken === 'string' ? page.nextPageToken : null;
    } while (token != null && out.length < 10_000);
    return out;
  }

  // --- requests ---

  private uploadRequest(target: UploadTarget): [string, string, Record<string, unknown>] {
    return target.kind === 'new'
      ? ['POST', 'files', newFileJson(target.file)]
      : ['PATCH', `files/${checkId(target.fileId)}`, changeJson(target.change ?? {})];
  }

  private async progress(session: UploadSession, range: string, body: Uint8Array): Promise<UploadProgress> {
    if (!isGoogleApi(session.uri)) throw corrupt('insecureUrl');
    const answer = await authorized(this.tokens, (token) =>
      this.exchange('PUT', session.uri, { headers: { 'Content-Range': range }, body, resume: true }, token));
    if (answer.status === 308) {
      const have = /^bytes=0-(\d+)$/.exec(answer.headers.get('Range')?.trim() ?? '');
      return { kind: 'incomplete', received: have ? Number(have[1]) + 1 : 0 };
    }
    return { kind: 'complete', file: fileFromWire(decode(answer)) };
  }

  private async json(method: string, target: string, request: RequestParts = {}): Promise<unknown> {
    return decode(await this.call(method, target, request));
  }

  private call(method: string, target: string, request: RequestParts = {}): Promise<Exchange> {
    return this.retry.run(() => authorized(this.tokens, (token) => this.exchange(method, target, request, token)));
  }

  private async exchange(method: string, target: string, request: RequestParts, token: string): Promise<Exchange> {
    if (!isGoogleApi(target)) throw corrupt('insecureUrl');
    const headers: Record<string, string> = { Authorization: `Bearer ${token}`, ...request.headers };
    if (request.contentType) headers['Content-Type'] = request.contentType;
    let response: Response;
    try {
      response = await this.fetchFn(target, {
        method,
        headers,
        body: request.body as BodyInit | undefined,
        redirect: request.resume ? 'follow' : 'manual',
        credentials: 'omit',
        cache: 'no-store',
      });
    } catch (e) {
      // fetch rejects with a TypeError when there is no answer; anything else (an abort, a bug) passes through.
      if (e instanceof TypeError) throw new DriveError('OFFLINE');
      throw e;
    }
    if (response.type === 'opaqueredirect' || response.redirected) throw new DriveError('OFFLINE', 0, null, 'redirect');
    let body: Uint8Array;
    try {
      body = new Uint8Array(await response.arrayBuffer());
    } catch {
      throw new DriveError('OFFLINE');
    }
    const answer = { status: response.status, headers: response.headers, body };
    if ((answer.status >= 200 && answer.status <= 299) || (request.resume && answer.status === 308)) return answer;
    const text = (answer.headers.get('Content-Type') ?? '').includes('json') ? new TextDecoder().decode(body) : null;
    throw driveErrorFromHttp(answer.status, answer.headers.get('Retry-After'), text, this.now());
  }

  private boundaryFor(content: Uint8Array): string {
    for (;;) {
      const boundary = `doorprints-${Math.floor(this.random() * 2 ** 52).toString(16)}`;
      if (!contains(content, new TextEncoder().encode(boundary))) return boundary;
    }
  }
}

interface RequestParts {
  headers?: Record<string, string>;
  body?: Uint8Array | string;
  contentType?: string;
  resume?: boolean;
}

function jsonBody(value: Record<string, unknown>): RequestParts {
  return { body: JSON.stringify(value), contentType: 'application/json; charset=UTF-8' };
}

function decode(answer: Exchange): unknown {
  if (!(answer.headers.get('Content-Type') ?? '').toLowerCase().startsWith('application/json')) throw corrupt('notJson');
  try {
    return JSON.parse(new TextDecoder().decode(answer.body));
  } catch {
    throw corrupt();
  }
}

/** A `multipart/related` body: the metadata JSON, then the content (Drive's multipart upload; Kotlin the same bytes). */
export function multipart(boundary: string, metadata: string, mimeType: string, content: Uint8Array): Uint8Array {
  const enc = new TextEncoder();
  const head = enc.encode(
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metadata}\r\n--${boundary}\r\nContent-Type: ${mimeType}\r\n\r\n`,
  );
  const tail = enc.encode(`\r\n--${boundary}--\r\n`);
  const out = new Uint8Array(head.length + content.length + tail.length);
  out.set(head, 0);
  out.set(content, head.length);
  out.set(tail, head.length + content.length);
  return out;
}

function contains(haystack: Uint8Array, needle: Uint8Array): boolean {
  outer: for (let i = 0; i + needle.length <= haystack.length; i++) {
    for (let j = 0; j < needle.length; j++) if (haystack[i + j] !== needle[j]) continue outer;
    return true;
  }
  return false;
}
