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
 * The Drive access layer (S4b-BL-115, docs/15 §5, §7 phase 2): what Doorprints needs from Google Drive v3 under the one
 * scope `drive.file`. The Kotlin twin is `app.doorprints.drive` in `android/shared` (`DriveClient.kt`, `DriveModels.kt`,
 * `DriveException.kt`, `DriveRetry.kt`); names and meanings match, and `docs/schemas/drive-vectors.json` pins the wire
 * on both. Promises, not Observables: `fetch` is promise-native, and the Drive sync backend (S4b-BL-118) wraps them.
 */

/** Drive's folder MIME type. */
export const FOLDER_MIME = 'application/vnd.google-apps.folder';

/** One file or folder, with the fields Doorprints asks for ({@link DRIVE_FIELDS}.file). Times are epoch milliseconds. */
export interface DriveFile {
  readonly id: string;
  readonly name: string;
  readonly mimeType: string;
  readonly parents: readonly string[];
  readonly appProperties: Readonly<Record<string, string>>;
  /** Bytes of the content; null for a folder. */
  readonly size: number | null;
  /** Drive's own SHA-256 of the content, lowercase hex; null for a folder (or while Drive has not computed it). */
  readonly sha256Checksum: string | null;
  readonly createdTime: number;
  readonly modifiedTime: number;
  /** In the bin, by itself or through a folder above it. */
  readonly trashed: boolean;
  readonly headRevisionId: string | null;
}

export function isFolder(file: DriveFile): boolean {
  return file.mimeType === FOLDER_MIME;
}

/** One page of a listing; see {@link DriveClient.list} for what "complete" means. */
export interface DrivePage {
  readonly files: readonly DriveFile[];
  readonly nextPageToken: string | null;
  readonly incompleteSearch: boolean;
}

/**
 * What to list, as Drive's `q` ({@link toQ}). Found by `appProperties` and folder, never by name (docs/15 §5.8).
 * `trashed` left out is false (the bin left out); null lists both.
 */
export interface DriveQuery {
  readonly parentId?: string | null;
  readonly appProperties?: Readonly<Record<string, string>>;
  readonly mimeType?: string | null;
  readonly name?: string | null;
  readonly trashed?: boolean | null;
}

/** A literal in Drive's query grammar: quoted with `'`, with `\` and `'` escaped by a backslash. */
export function qLiteral(value: string): string {
  return `'${value.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
}

/** Drive's query string, in the order Kotlin writes it (parent, name, MIME type, appProperties by key, trashed). */
export function toQ(query: DriveQuery): string {
  const parts: string[] = [];
  if (query.parentId != null) parts.push(`${qLiteral(query.parentId)} in parents`);
  if (query.name != null) parts.push(`name = ${qLiteral(query.name)}`);
  if (query.mimeType != null) parts.push(`mimeType = ${qLiteral(query.mimeType)}`);
  const props = query.appProperties ?? {};
  for (const key of Object.keys(props).sort()) {
    parts.push(`appProperties has { key=${qLiteral(key)} and value=${qLiteral(props[key])} }`);
  }
  const trashed = query.trashed === undefined ? false : query.trashed;
  if (trashed !== null) parts.push(`trashed = ${trashed}`);
  return parts.join(' and ');
}

/** A file or folder to create; no parents is My Drive's root. */
export interface NewFile {
  readonly name: string;
  readonly mimeType: string;
  readonly parents?: readonly string[];
  readonly appProperties?: Readonly<Record<string, string>>;
}

/** A metadata change: only what is set changes; a null app property value removes that key. */
export interface MetadataChange {
  readonly name?: string | null;
  readonly appProperties?: Readonly<Record<string, string | null>>;
  readonly addParents?: readonly string[];
  readonly removeParents?: readonly string[];
}

/** `about.user` and `about.storageQuota`. */
export interface DriveAbout {
  readonly email: string;
  readonly displayName: string | null;
  /** The limit in bytes; null when Drive reports none. */
  readonly quotaLimit: number | null;
  readonly quotaUsage: number;
  readonly quotaUsageInDrive: number;
}

/** One revision of a file's content, oldest first. */
export interface DriveRevision {
  readonly id: string;
  readonly modifiedTime: number;
  readonly size: number | null;
}

/** A resumable session: its URI is a capability, never logged or shown. `fileId` is set for new content of a file. */
export interface UploadSession {
  readonly uri: string;
  readonly size: number;
  readonly fileId: string | null;
}

/** Where a resumable upload stands. */
export type UploadProgress =
  | { readonly kind: 'incomplete'; readonly received: number }
  | { readonly kind: 'complete'; readonly file: DriveFile };

/** What an upload writes: a new file, or new content (and metadata) for an existing one. */
export type UploadTarget =
  | { readonly kind: 'new'; readonly file: NewFile }
  | { readonly kind: 'existing'; readonly fileId: string; readonly mimeType: string; readonly change?: MetadataChange };

export function targetMime(target: UploadTarget): string {
  return target.kind === 'new' ? target.file.mimeType : target.mimeType;
}

/** An inclusive byte range, as Kotlin's `LongRange`. */
export interface ByteRange {
  readonly first: number;
  readonly last: number;
}

/** The outcome of `deleteAll`: what went, and what is still in Drive when it stopped. */
export interface DeleteReport {
  readonly deleted: readonly string[];
  readonly left: readonly string[];
  readonly error: DriveError | null;
  /** Per file: which ones failed and why (S4b-BL-119); `error` is the first failure that stopped the run. */
  readonly failed: readonly DeleteFailure[];
}

/** One file a deletion could not remove: the error kind and status only, never a name. */
export interface DeleteFailure {
  readonly fileId: string;
  readonly kind: DriveErrorKind;
  readonly httpStatus: number;
}

/** A folder of docs/15 §5.1, found by its `doorprints` app property. */
export interface FolderSpec {
  readonly name: string;
  readonly appProperties: Readonly<Record<string, string>>;
}

/** The layout and app-property keys of docs/15 §5.1 (Kotlin `DriveLayout`). */
export const DRIVE_LAYOUT = {
  role: 'doorprints',
  kind: 'kind',
  device: 'device',
  state: 'state',
  createdAt: 'createdAt',
  statePartial: 'partial',
  stateComplete: 'complete',
  root: { name: 'Doorprints', appProperties: { doorprints: 'root' } } as FolderSpec,
  backups: { name: 'Backups', appProperties: { doorprints: 'backups' } } as FolderSpec,
  sync: { name: 'Sync', appProperties: { doorprints: 'sync' } } as FolderSpec,
  photos: { name: 'Photos', appProperties: { doorprints: 'photos' } } as FolderSpec,
  /** Drive's limit on one app property, key and value together, in UTF-8 bytes. */
  maxPropertyBytes: 124,
} as const;

const FILE_FIELDS =
  'id,name,mimeType,parents,appProperties,size,sha256Checksum,createdTime,modifiedTime,trashed,headRevisionId';

/** The `fields` Doorprints asks for (Kotlin `DriveFields`). */
export const DRIVE_FIELDS = {
  file: FILE_FIELDS,
  list: `nextPageToken,incompleteSearch,files(${FILE_FIELDS})`,
  about: 'user(emailAddress,displayName),storageQuota(limit,usage,usageInDrive)',
  revisions: 'nextPageToken,revisions(id,modifiedTime,size)',
} as const;

/** docs/15 §5.2: multipart up to 5 MB, resumable above. */
export const MULTIPART_LIMIT = 5 * 1024 * 1024;
/** Every resumable chunk but the last is a multiple of 256 KiB. */
export const CHUNK_UNIT = 256 * 1024;
/** The default chunk (4 units). */
export const DEFAULT_CHUNK = 4 * CHUNK_UNIT;

// --- errors ---

/** The kinds of {@link DriveError}, the Kotlin `DriveException.Kind` names. */
export type DriveErrorKind =
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'QUOTA_EXCEEDED'
  | 'RATE_LIMITED'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'BAD_REQUEST'
  | 'SERVER'
  | 'OFFLINE'
  | 'CANCELLED'
  | 'CORRUPT';

/**
 * Every failure of a {@link DriveClient} (Kotlin `DriveException`). The message names the kind and the status only,
 * never a token, a session URI, a file name or content.
 */
export class DriveError extends Error {
  constructor(
    readonly kind: DriveErrorKind,
    readonly httpStatus = 0,
    readonly retryAfterMs: number | null = null,
    readonly reason: string | null = null,
  ) {
    super(`Drive ${kind} (HTTP ${httpStatus})`);
    this.name = 'DriveError';
  }

  /** Waiting and asking again can help. */
  get retryable(): boolean {
    return this.kind === 'RATE_LIMITED' || this.kind === 'SERVER' || this.kind === 'OFFLINE';
  }
}

/** Why Google sign-in did not give a token: the popup was blocked or closed, the person said no, no network, or Google's script is not there. */
export type SignInErrorKind = 'popup_blocked' | 'popup_closed' | 'denied' | 'offline' | 'unavailable';

/** A failed Google sign-in (never carries a token). Its message is the kind. */
export class SignInError extends Error {
  constructor(readonly kind: SignInErrorKind) {
    super(kind);
    this.name = 'SignInError';
  }
}

export function corrupt(reason = 'badAnswer'): DriveError {
  return new DriveError('CORRUPT', 0, null, reason);
}

/** The first `errors[].reason`, else the first `details[].reason`; null when the body is not Google's error JSON. */
export function reasonOf(body: string | null): string | null {
  if (!body || !body.trim()) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return null;
  }
  const error = (parsed as { error?: unknown } | null)?.error;
  if (!error || typeof error !== 'object') return null;
  const first = (key: 'errors' | 'details'): string | null => {
    const list = (error as Record<string, unknown>)[key];
    if (!Array.isArray(list)) return null;
    for (const item of list) {
      const reason = (item as { reason?: unknown } | null)?.reason;
      if (typeof reason === 'string') return reason;
    }
    return null;
  };
  return first('errors') ?? first('details');
}

/** `Retry-After` as delay-seconds or an IMF-fixdate, in milliseconds from `nowMs`; null if neither. */
export function retryAfterMs(value: string | null, nowMs: number): number | null {
  const text = value?.trim() ?? '';
  if (!text) return null;
  if (/^-?\d+$/.test(text)) {
    const seconds = Number(text);
    return seconds >= 0 ? seconds * 1000 : null;
  }
  if (!/^[A-Za-z]{3}, \d{2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} GMT$/.test(text)) return null;
  const at = Date.parse(text);
  return Number.isNaN(at) ? null : Math.max(0, at - nowMs);
}

/** The error for a non-2xx answer (Kotlin `DriveException.fromHttp`; drive-vectors.json `errors`). */
export function driveErrorFromHttp(status: number, retryAfter: string | null, body: string | null, nowMs: number): DriveError {
  const reason = reasonOf(body);
  const wait = retryAfterMs(retryAfter, nowMs);
  let kind: DriveErrorKind;
  if (status === 401) kind = 'UNAUTHORIZED';
  else if (status === 403 && reason === 'storageQuotaExceeded') kind = 'QUOTA_EXCEEDED';
  else if (status === 403 && (reason === 'userRateLimitExceeded' || reason === 'rateLimitExceeded')) kind = 'RATE_LIMITED';
  else if (status === 403) kind = 'FORBIDDEN';
  else if (status === 404 || status === 410) kind = 'NOT_FOUND';
  else if (status === 409 || status === 412) kind = 'CONFLICT';
  else if (status === 429) kind = 'RATE_LIMITED';
  else if (status === 499) kind = 'CANCELLED';
  else if (status >= 300 && status <= 399) kind = 'OFFLINE';
  else if (status >= 400 && status <= 499) kind = 'BAD_REQUEST';
  else kind = 'SERVER';
  return new DriveError(kind, status, wait, reason);
}

// --- tokens and retries ---

/** The access token, from whoever holds the grant (S4b-BL-117). Never stored, logged or shown by this layer. */
export interface TokenProvider {
  accessToken(): Promise<string>;
  /** Drive refused `token` (401): drop it, so the next {@link accessToken} is fresh or throws. */
  onRejected?(token: string): Promise<void>;
}

export interface DriveRetryOptions {
  maxAttempts?: number;
  baseMs?: number;
  capMs?: number;
  maxRetryAfterMs?: number;
  /** In [0, 1). */
  random?: () => number;
  sleep?: (ms: number) => Promise<void>;
}

/**
 * The retry rule of docs/15 §5.2 (Kotlin `DriveRetry`; drive-vectors.json `backoff`): at most `maxAttempts` tries for a
 * retryable failure; the wait is `Retry-After` (at most `maxRetryAfterMs`, longer and the error goes to the caller),
 * else `floor(random() * (min(capMs, baseMs * 2^(n-1)) + 1))`.
 */
export class DriveRetry {
  readonly maxAttempts: number;
  readonly baseMs: number;
  readonly capMs: number;
  readonly maxRetryAfterMs: number;
  private readonly random: () => number;
  private readonly sleep: (ms: number) => Promise<void>;

  constructor(options: DriveRetryOptions = {}) {
    this.maxAttempts = options.maxAttempts ?? 5;
    this.baseMs = options.baseMs ?? 1000;
    this.capMs = options.capMs ?? 32_000;
    this.maxRetryAfterMs = options.maxRetryAfterMs ?? 60_000;
    this.random = options.random ?? Math.random;
    this.sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  }

  backoffMs(attempt: number): number {
    const ceiling = Math.min(this.capMs, this.baseMs * 2 ** Math.min(Math.max(attempt - 1, 0), 30));
    return Math.min(ceiling, Math.max(0, Math.floor(this.random() * (ceiling + 1))));
  }

  waitFor(attempt: number, error: DriveError): number | null {
    if (!error.retryable || attempt >= this.maxAttempts) return null;
    if (error.retryAfterMs == null) return this.backoffMs(attempt);
    return error.retryAfterMs > this.maxRetryAfterMs ? null : error.retryAfterMs;
  }

  async pause(attempt: number, error: DriveError): Promise<void> {
    const wait = this.waitFor(attempt, error);
    if (wait == null) throw error;
    await this.sleep(wait);
  }

  async backoff(attempt: number): Promise<void> {
    await this.sleep(this.backoffMs(attempt));
  }

  async run<T>(block: () => Promise<T>): Promise<T> {
    for (let attempt = 1; ; attempt++) {
      try {
        return await block();
      } catch (e) {
        if (!(e instanceof DriveError)) throw e;
        await this.pause(attempt, e);
      }
    }
  }
}

/** One request with a token; on a 401 the token is reported and the request made once more (Kotlin `authorized`). */
export async function authorized<T>(tokens: TokenProvider, block: (token: string) => Promise<T>): Promise<T> {
  const first = await tokens.accessToken();
  try {
    return await block(first);
  } catch (e) {
    if (!(e instanceof DriveError) || e.kind !== 'UNAUTHORIZED') throw e;
    await tokens.onRejected?.(first);
    return block(await tokens.accessToken());
  }
}

/** A Drive id is letters, digits, `-` and `_`; anything else is refused before it reaches a URL path. */
export function checkId(id: string): string {
  if (!/^[A-Za-z0-9_-]{1,256}$/.test(id)) throw new DriveError('BAD_REQUEST', 0, null, 'badId');
  return id;
}

/**
 * Google Drive v3 as Doorprints uses it (Kotlin `DriveClient`, the same contract): each call one request with a token
 * (a 401 asks the provider once more) and the retry rule, except `uploadChunk` and `uploadStatus` (`uploadResumable`
 * recovers); listings are eventually consistent and `getFile` is not, so a device keeps the ids it wrote; pages come
 * oldest first; failures are {@link DriveError}s; nothing is logged. Implementations: `FetchDriveClient` and
 * `InMemoryFakeDrive`; `drive-contract.spec.ts` runs the same cases on both.
 */
export interface DriveClient {
  readonly retry: DriveRetry;
  about(): Promise<DriveAbout>;
  list(query: DriveQuery, pageToken?: string | null, pageSize?: number): Promise<DrivePage>;
  getFile(fileId: string): Promise<DriveFile>;
  createFile(file: NewFile): Promise<DriveFile>;
  upload(target: UploadTarget, content: Uint8Array): Promise<DriveFile>;
  startUpload(target: UploadTarget, size: number): Promise<UploadSession>;
  uploadChunk(session: UploadSession, offset: number, bytes: Uint8Array): Promise<UploadProgress>;
  uploadStatus(session: UploadSession): Promise<UploadProgress>;
  updateMetadata(fileId: string, change: MetadataChange): Promise<DriveFile>;
  download(fileId: string, range?: ByteRange | null): Promise<Uint8Array>;
  /** For good, not the bin; a folder takes its contents; a 404 counts as deleted. */
  delete(fileId: string): Promise<void>;
  /** To Drive's bin (automatic pruning only). */
  trash(fileId: string): Promise<DriveFile>;
  revisions(fileId: string): Promise<DriveRevision[]>;
}
