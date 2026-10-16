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

import { sha256Hex } from '../../export/sha256';
import {
  CHUNK_UNIT,
  DRIVE_LAYOUT,
  DriveError,
  DriveRetry,
  FOLDER_MIME,
  MULTIPART_LIMIT,
  authorized,
  checkId,
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
import { FakeClock, FakeTokenProvider, FaultScript } from './fake-drive-faults';
import type { DriveFault, DriveOp } from './fake-drive-faults';
import { DRIVE_UPLOAD } from './fetch-drive-client';

interface Stored {
  file: DriveFile;
  content: Uint8Array | null;
  revisions: { revision: DriveRevision; content: Uint8Array }[];
}

interface Session {
  uri: string;
  target: UploadTarget;
  size: number;
  buffer: Uint8Array;
  done: string | null;
  corrupt: boolean;
}

/** Knobs of the in-memory Drive used in tests: clock, quota, listing lag and account. */
export interface FakeDriveOptions {
  clock?: FakeClock;
  quotaBytes?: number | null;
  listingLagMs?: number;
  email?: string;
}

const utf8 = (s: string) => new TextEncoder().encode(s).length;

/**
 * One person's Drive in memory, as Doorprints sees it through `drive.file` (S4b-BL-115; Kotlin `FakeDriveServer`, the
 * same behaviour): folders and files with `appProperties`, content with `sha256Checksum`, revisions, the bin and
 * permanent deletes, a quota, resumable sessions, `modifiedTime` from a {@link FakeClock}, a listing lag, and the
 * {@link faults} script. Each request is counted ({@link requests}); the `…ByHand` functions are the person or another
 * writer and are never counted. Each request runs to its end before the next (JavaScript's one thread), as Drive
 * orders writes; there is no compare-and-swap, so the last write wins. Test code only: nothing in the app imports it.
 */
export class FakeDriveServer {
  readonly clock: FakeClock;
  quotaBytes: number | null;
  listingLagMs: number;
  readonly email: string;
  readonly faults = new FaultScript();
  readonly refusedTokens = new Set<string>();
  readonly requests: { op: DriveOp; about: string | null }[] = [];
  readonly tokensSeen: string[] = [];

  private readonly files = new Map<string, Stored>();
  private readonly sessions = new Map<string, Session>();
  private readonly opCounts = new Map<DriveOp, number>();
  private ids = 0;
  private pendingDrop: number | null = null;
  private pendingCorrupt = false;
  private lateChecksum = false;

  constructor(options: FakeDriveOptions = {}) {
    this.clock = options.clock ?? new FakeClock();
    this.quotaBytes = options.quotaBytes === undefined ? 15 * 1024 ** 3 : options.quotaBytes;
    this.listingLagMs = options.listingLagMs ?? 0;
    this.email = options.email ?? 'person@example.com';
  }

  // --- requests ---

  about(token: string): Promise<DriveAbout> {
    return this.request('ABOUT', null, token, () => ({
      email: this.email, displayName: 'Test Person', quotaLimit: this.quotaBytes, quotaUsage: this.usage(), quotaUsageInDrive: this.usage(),
    }));
  }

  list(token: string, query: DriveQuery, pageToken: string | null, pageSize: number): Promise<DrivePage> {
    return this.request('LIST', toQ(query), token, () => {
      let start = 0;
      if (pageToken != null) {
        if (!/^o\d+$/.test(pageToken)) throw new DriveError('BAD_REQUEST', 400, null, 'invalidPageToken');
        start = Number(pageToken.slice(1));
      }
      const now = this.clock.now();
      const all = [...this.files.values()].map((s) => this.view(s.file))
        .filter((f) => now - f.createdTime >= this.listingLagMs && this.matches(f, query))
        .sort((a, b) => a.createdTime - b.createdTime || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
      const page = all.slice(start, start + pageSize);
      return { files: page, nextPageToken: start + page.length < all.length ? `o${start + page.length}` : null, incompleteSearch: false };
    });
  }

  get(token: string, fileId: string): Promise<DriveFile> {
    return this.request('GET', fileId, token, () => this.view(this.stored(fileId).file));
  }

  create(token: string, file: NewFile): Promise<DriveFile> {
    return this.request('CREATE', file.name, token, () => this.add(file, null));
  }

  upload(token: string, target: UploadTarget, content: Uint8Array): Promise<DriveFile> {
    return this.request('UPLOAD', idOf(target), token, () => this.write(target, content, this.pendingCorrupt));
  }

  startUpload(token: string, target: UploadTarget, size: number): Promise<UploadSession> {
    return this.request('UPLOAD_START', idOf(target), token, () => {
      if (target.kind === 'new') this.checkNew(target.file);
      else this.stored(target.fileId);
      const uri = `${DRIVE_UPLOAD}/files?uploadType=resumable&upload_id=${this.nextId('s')}`;
      this.sessions.set(uri, { uri, target, size, buffer: new Uint8Array(0), done: null, corrupt: false });
      return { uri, size, fileId: target.kind === 'existing' ? target.fileId : null };
    });
  }

  uploadChunk(token: string, uri: string, offset: number, bytes: Uint8Array): Promise<UploadProgress> {
    return this.request('UPLOAD_CHUNK', null, token, (): UploadProgress => {
      const s = this.session(uri);
      if (s.done) return { kind: 'complete', file: this.view(this.stored(s.done).file) };
      const have = s.buffer.length;
      if (offset > have || offset + bytes.length > s.size) throw new DriveError('BAD_REQUEST', 400);
      const last = offset + bytes.length === s.size;
      if (!last && bytes.length % CHUNK_UNIT !== 0) throw new DriveError('BAD_REQUEST', 400, null, 'chunkNotAligned');
      // A resent chunk that overlaps what arrived: the overlap is skipped.
      const fresh = bytes.slice(Math.min(have - offset, bytes.length));
      if (this.pendingDrop != null) {
        s.buffer = concat(s.buffer, fresh.slice(0, Math.min(this.pendingDrop, fresh.length)));
        throw new DriveError('OFFLINE', 0, null, 'dropped');
      }
      s.buffer = concat(s.buffer, fresh);
      if (this.pendingCorrupt) s.corrupt = true;
      return this.finish(s);
    });
  }

  uploadStatus(token: string, uri: string): Promise<UploadProgress> {
    return this.request('UPLOAD_STATUS', null, token, (): UploadProgress => {
      const s = this.session(uri);
      return s.done ? { kind: 'complete', file: this.view(this.stored(s.done).file) } : { kind: 'incomplete', received: s.buffer.length };
    });
  }

  update(token: string, fileId: string, change: MetadataChange): Promise<DriveFile> {
    return this.request('UPDATE', fileId, token, () => this.view(this.applyChange(this.stored(fileId), change).file));
  }

  download(token: string, fileId: string, range: ByteRange | null): Promise<Uint8Array> {
    return this.request('DOWNLOAD', fileId, token, () => {
      const content = this.stored(fileId).content;
      if (content == null) throw new DriveError('FORBIDDEN', 403, null, 'fileNotDownloadable');
      if (range == null) return content.slice();
      if (range.first >= content.length) throw new DriveError('BAD_REQUEST', 416);
      return content.slice(range.first, Math.min(range.last + 1, content.length));
    });
  }

  delete(token: string, fileId: string): Promise<void> {
    return this.request('DELETE', fileId, token, () => {
      this.stored(fileId);
      this.deleteByHand(fileId);
    });
  }

  trash(token: string, fileId: string): Promise<DriveFile> {
    return this.request('TRASH', fileId, token, () => {
      const s = this.stored(fileId);
      s.file = { ...s.file, trashed: true };
      return this.view(s.file);
    });
  }

  revisions(token: string, fileId: string): Promise<DriveRevision[]> {
    return this.request('REVISIONS', fileId, token, () => this.stored(fileId).revisions.map((r) => r.revision));
  }

  // --- the person or another writer (not counted, no faults) ---

  fileOrNull(fileId: string): DriveFile | null {
    const s = this.files.get(fileId);
    return s ? this.view(s.file) : null;
  }

  contentOf(fileId: string): Uint8Array | null {
    return this.files.get(fileId)?.content?.slice() ?? null;
  }

  allFiles(): DriveFile[] {
    return [...this.files.values()].map((s) => this.view(s.file));
  }

  putByHand(file: NewFile, content: Uint8Array | null = null): DriveFile {
    return this.add(file, content);
  }

  editByHand(fileId: string, content: Uint8Array): void {
    this.setContent(this.must(fileId), content);
  }

  renameByHand(fileId: string, name: string): void {
    const s = this.must(fileId);
    s.file = { ...s.file, name };
  }

  moveByHand(fileId: string, parentId: string | null): void {
    const s = this.must(fileId);
    s.file = { ...s.file, parents: parentId == null ? [] : [parentId] };
  }

  trashByHand(fileId: string): void {
    const s = this.must(fileId);
    s.file = { ...s.file, trashed: true };
  }

  untrashByHand(fileId: string): void {
    const s = this.must(fileId);
    s.file = { ...s.file, trashed: false };
  }

  deleteByHand(fileId: string): void {
    const doomed = new Set([fileId]);
    let grew = true;
    while (grew) {
      grew = false;
      for (const s of this.files.values()) {
        if (!doomed.has(s.file.id) && s.file.parents.some((p) => doomed.has(p))) {
          doomed.add(s.file.id);
          grew = true;
        }
      }
    }
    for (const id of doomed) this.files.delete(id);
  }

  rollBack(fileId: string, revisionId: string): void {
    const s = this.must(fileId);
    const old = s.revisions.find((r) => r.revision.id === revisionId);
    if (!old) throw new Error(`no revision ${revisionId}`);
    this.setContent(s, old.content);
  }

  expireSessions(): void {
    for (const [uri, s] of this.sessions) if (!s.done) this.sessions.delete(uri);
  }

  // --- inside ---

  private async request<T>(op: DriveOp, about: string | null, token: string, block: () => T): Promise<T> {
    this.requests.push({ op, about });
    this.tokensSeen.push(token);
    const opCall = (this.opCounts.get(op) ?? 0) + 1;
    this.opCounts.set(op, opCall);
    const fault = this.faults.take(op, this.requests.length, opCall);
    if (fault?.kind === 'interleave') fault.action(this);
    if (!token || this.refusedTokens.has(token)) throw new DriveError('UNAUTHORIZED', 401);
    if (fault && fault.kind !== 'interleave') {
      switch (fault.kind) {
        case 'responseLost':
          block();
          throw new DriveError('OFFLINE', 0, null, 'responseLost');
        case 'dropAfter':
          this.pendingDrop = fault.bytes;
          break;
        case 'corruptContent':
          this.pendingCorrupt = true;
          break;
        case 'lateChecksum':
          this.lateChecksum = true;
          break;
        default:
          throw errorFor(fault);
      }
    }
    try {
      return block();
    } finally {
      this.pendingDrop = null;
      this.pendingCorrupt = false;
      this.lateChecksum = false;
    }
  }

  private nextId(prefix: string): string {
    return prefix + String(++this.ids).padStart(6, '0');
  }

  private must(fileId: string): Stored {
    const s = this.files.get(fileId);
    if (!s) throw new Error(`no file ${fileId}`);
    return s;
  }

  private stored(fileId: string): Stored {
    const s = this.files.get(fileId);
    if (!s) throw new DriveError('NOT_FOUND', 404, null, 'notFound');
    return s;
  }

  private session(uri: string): Session {
    const s = this.sessions.get(uri);
    if (!s) throw new DriveError('NOT_FOUND', 404, null, 'uploadSessionGone');
    return s;
  }

  private usage(): number {
    let n = 0;
    for (const s of this.files.values()) n += s.content?.length ?? 0;
    return n;
  }

  private isTrashed(file: DriveFile): boolean {
    let current: DriveFile | undefined = file;
    const seen = new Set<string>();
    while (current && !seen.has(current.id)) {
      seen.add(current.id);
      if (current.trashed) return true;
      const parent: string | undefined = current.parents[0];
      current = parent ? this.files.get(parent)?.file : undefined;
    }
    return false;
  }

  private view(file: DriveFile): DriveFile {
    const shown = { ...file, trashed: this.isTrashed(file) };
    return this.lateChecksum ? { ...shown, sha256Checksum: null } : shown;
  }

  private matches(file: DriveFile, q: DriveQuery): boolean {
    const trashed = q.trashed === undefined ? false : q.trashed;
    return (q.parentId == null || file.parents.includes(q.parentId)) &&
      (q.name == null || q.name === file.name) &&
      (q.mimeType == null || q.mimeType === file.mimeType) &&
      Object.entries(q.appProperties ?? {}).every(([k, v]) => file.appProperties[k] === v) &&
      (trashed === null || trashed === file.trashed);
  }

  private checkProperties(props: Readonly<Record<string, string | null>>): void {
    for (const [k, v] of Object.entries(props)) {
      if (!k || utf8(k) + (v == null ? 0 : utf8(v)) > DRIVE_LAYOUT.maxPropertyBytes) {
        throw new DriveError('BAD_REQUEST', 400, null, 'invalidAppProperty');
      }
    }
  }

  private checkParents(parents: readonly string[]): void {
    if (parents.length > 1) throw new DriveError('BAD_REQUEST', 400, null, 'multipleParents');
    for (const p of parents) {
      if (this.files.get(p)?.file.mimeType !== FOLDER_MIME) throw new DriveError('NOT_FOUND', 404, null, 'notFound');
    }
  }

  private checkNew(file: NewFile): void {
    this.checkParents(file.parents ?? []);
    this.checkProperties(file.appProperties ?? {});
  }

  private checkQuota(extra: number): void {
    if (this.quotaBytes != null && this.usage() + extra > this.quotaBytes) throw quotaError();
  }

  private add(file: NewFile, content: Uint8Array | null): DriveFile {
    this.checkNew(file);
    if (content) this.checkQuota(content.length);
    const now = this.clock.now();
    const folder = file.mimeType === FOLDER_MIME;
    const stored: Stored = {
      file: {
        id: this.nextId('f'), name: file.name, mimeType: file.mimeType, parents: [...(file.parents ?? [])],
        appProperties: { ...(file.appProperties ?? {}) }, size: null, sha256Checksum: null, createdTime: now, modifiedTime: now,
        trashed: false, headRevisionId: null,
      },
      content: folder ? null : new Uint8Array(0),
      revisions: [],
    };
    this.files.set(stored.file.id, stored);
    if (!folder) this.setContent(stored, content ?? new Uint8Array(0));
    return this.view(stored.file);
  }

  private write(target: UploadTarget, content: Uint8Array, corrupt: boolean): DriveFile {
    const bytes = corrupt && content.length ? content.slice() : content;
    if (corrupt && bytes.length) bytes[0] ^= 1;
    if (target.kind === 'new') return this.add(target.file, bytes);
    const s = this.stored(target.fileId);
    this.checkQuota(bytes.length - (s.content?.length ?? 0));
    this.applyChange(s, target.change ?? {});
    this.setContent(s, bytes);
    return this.view(s.file);
  }

  private finish(s: Session): UploadProgress {
    if (s.buffer.length < s.size) return { kind: 'incomplete', received: s.buffer.length };
    const file = this.write(s.target, s.buffer, s.corrupt);
    s.done = file.id;
    return { kind: 'complete', file };
  }

  private applyChange(s: Stored, change: MetadataChange): Stored {
    this.checkProperties(change.appProperties ?? {});
    const removed = new Set(change.removeParents ?? []);
    const parents = [...new Set([...s.file.parents.filter((p) => !removed.has(p)), ...(change.addParents ?? [])])];
    if (change.addParents?.length) this.checkParents(parents);
    const props: Record<string, string> = { ...s.file.appProperties };
    for (const [k, v] of Object.entries(change.appProperties ?? {})) {
      if (v == null) delete props[k];
      else props[k] = v;
    }
    s.file = { ...s.file, name: change.name ?? s.file.name, appProperties: props, parents };
    return s;
  }

  private setContent(s: Stored, content: Uint8Array): void {
    const now = this.clock.now();
    const revision: DriveRevision = { id: this.nextId('r'), modifiedTime: now, size: content.length };
    s.revisions.push({ revision, content: content.slice() });
    s.content = content.slice();
    s.file = { ...s.file, size: content.length, sha256Checksum: sha256Hex(content), modifiedTime: now, headRevisionId: revision.id };
  }
}

function idOf(target: UploadTarget): string {
  return target.kind === 'new' ? target.file.name : target.fileId;
}

function quotaError(): DriveError {
  return new DriveError('QUOTA_EXCEEDED', 403, null, 'storageQuotaExceeded');
}

function errorFor(fault: DriveFault): DriveError {
  switch (fault.kind) {
    case 'offline': return new DriveError('OFFLINE');
    case 'tokenExpired': return new DriveError('UNAUTHORIZED', 401);
    case 'forbidden': return new DriveError('FORBIDDEN', 403, null, 'insufficientPermissions');
    case 'quotaExceeded': return quotaError();
    case 'rateLimited':
      return fault.as403
        ? new DriveError('RATE_LIMITED', 403, fault.retryAfterMs ?? null, 'userRateLimitExceeded')
        : new DriveError('RATE_LIMITED', 429, fault.retryAfterMs ?? null, 'rateLimitExceeded');
    case 'server': return new DriveError('SERVER', fault.status ?? 503, fault.retryAfterMs ?? null);
    case 'notFound': return new DriveError('NOT_FOUND', 404, null, 'notFound');
    case 'conflict': return new DriveError('CONFLICT', 409);
    case 'cancelled': return new DriveError('CANCELLED', 499);
    default: throw new Error('handled in request');
  }
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

/**
 * The fake Drive as a {@link DriveClient} (Kotlin `InMemoryFakeDrive`): each call one request to `server`, with the same
 * 401 rule and retry as `FetchDriveClient`; by default the retry waits on the server's clock with random 0.5.
 */
export class InMemoryFakeDrive implements DriveClient {
  readonly retry: DriveRetry;

  constructor(
    readonly server: FakeDriveServer = new FakeDriveServer(),
    readonly tokens: TokenProvider = new FakeTokenProvider(),
    retry?: DriveRetry,
  ) {
    this.retry = retry ?? new DriveRetry({ random: () => 0.5, sleep: async (ms) => server.clock.sleep(ms) });
  }

  private call<T>(block: (token: string) => Promise<T>): Promise<T> {
    return this.retry.run(() => authorized(this.tokens, block));
  }

  about(): Promise<DriveAbout> {
    return this.call((t) => this.server.about(t));
  }

  list(query: DriveQuery, pageToken: string | null = null, pageSize = 100): Promise<DrivePage> {
    if (pageSize < 1 || pageSize > 1000) throw new Error('pageSize');
    return this.call((t) => this.server.list(t, query, pageToken, pageSize));
  }

  async getFile(fileId: string): Promise<DriveFile> {
    checkId(fileId);
    return this.call((t) => this.server.get(t, fileId));
  }

  createFile(file: NewFile): Promise<DriveFile> {
    return this.call((t) => this.server.create(t, file));
  }

  async upload(target: UploadTarget, content: Uint8Array): Promise<DriveFile> {
    if (content.length > MULTIPART_LIMIT) throw new Error('use uploadResumable above 5 MiB');
    return this.call((t) => this.server.upload(t, target, content));
  }

  async startUpload(target: UploadTarget, size: number): Promise<UploadSession> {
    if (size <= 0) throw new Error('size');
    return this.call((t) => this.server.startUpload(t, target, size));
  }

  async uploadChunk(session: UploadSession, offset: number, bytes: Uint8Array): Promise<UploadProgress> {
    if (bytes.length === 0 || offset < 0 || offset + bytes.length > session.size) throw new Error('chunk');
    return authorized(this.tokens, (t) => this.server.uploadChunk(t, session.uri, offset, bytes));
  }

  uploadStatus(session: UploadSession): Promise<UploadProgress> {
    return authorized(this.tokens, (t) => this.server.uploadStatus(t, session.uri));
  }

  async updateMetadata(fileId: string, change: MetadataChange): Promise<DriveFile> {
    checkId(fileId);
    return this.call((t) => this.server.update(t, fileId, change));
  }

  async download(fileId: string, range: ByteRange | null = null): Promise<Uint8Array> {
    checkId(fileId);
    if (range && (range.first < 0 || range.last < range.first)) throw new Error('range');
    return this.call((t) => this.server.download(t, fileId, range));
  }

  async delete(fileId: string): Promise<void> {
    checkId(fileId);
    try {
      await this.call((t) => this.server.delete(t, fileId));
    } catch (e) {
      if (!(e instanceof DriveError) || e.kind !== 'NOT_FOUND') throw e;
    }
  }

  async trash(fileId: string): Promise<DriveFile> {
    checkId(fileId);
    return this.call((t) => this.server.trash(t, fileId));
  }

  async revisions(fileId: string): Promise<DriveRevision[]> {
    checkId(fileId);
    return this.call((t) => this.server.revisions(t, fileId));
  }
}
