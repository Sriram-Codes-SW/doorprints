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

import { corrupt } from './drive-client';
import type { DriveAbout, DriveFile, DriveRevision, MetadataChange, NewFile } from './drive-client';

/*
 * Drive v3's JSON on the wire (sizes and quota numbers as strings, times RFC 3339) and the bodies Doorprints sends
 * (Kotlin `DriveWire.kt`). Shared by FetchDriveClient and the tests' HTTP face of the fake.
 */

type Json = Record<string, unknown>;

function str(o: Json, key: string): string | null {
  const v = o[key];
  if (v == null) return null;
  if (typeof v !== 'string') throw corrupt();
  return v;
}

function num(text: string | null): number | null {
  if (text == null) return null;
  if (!/^\d+$/.test(text)) throw corrupt();
  return Number(text);
}

function time(text: string | null): number {
  if (text == null) return 0;
  const ms = Date.parse(text);
  if (Number.isNaN(ms) || !/^\d{4}-\d{2}-\d{2}T/.test(text)) throw corrupt();
  return ms;
}

function obj(value: unknown): Json {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw corrupt();
  return value as Json;
}

export function fileFromWire(value: unknown): DriveFile {
  const o = obj(value);
  const id = str(o, 'id');
  if (id == null) throw corrupt();
  const parents = o['parents'] ?? [];
  const props = o['appProperties'] ?? {};
  if (!Array.isArray(parents) || parents.some((p) => typeof p !== 'string')) throw corrupt();
  const appProperties: Record<string, string> = {};
  for (const [k, v] of Object.entries(obj(props))) {
    if (typeof v !== 'string') throw corrupt();
    appProperties[k] = v;
  }
  const trashed = o['trashed'];
  if (trashed != null && typeof trashed !== 'boolean') throw corrupt();
  return {
    id,
    name: str(o, 'name') ?? '',
    mimeType: str(o, 'mimeType') ?? 'application/octet-stream',
    parents: parents as string[],
    appProperties,
    size: num(str(o, 'size')),
    sha256Checksum: str(o, 'sha256Checksum')?.toLowerCase() ?? null,
    createdTime: time(str(o, 'createdTime')),
    modifiedTime: time(str(o, 'modifiedTime')),
    trashed: trashed === true,
    headRevisionId: str(o, 'headRevisionId'),
  };
}

export function fileToWire(file: DriveFile): Json {
  const wire: Json = {
    id: file.id,
    name: file.name,
    mimeType: file.mimeType,
    parents: [...file.parents],
    appProperties: { ...file.appProperties },
    createdTime: new Date(file.createdTime).toISOString(),
    modifiedTime: new Date(file.modifiedTime).toISOString(),
    trashed: file.trashed,
  };
  if (file.size != null) wire['size'] = String(file.size);
  if (file.sha256Checksum != null) wire['sha256Checksum'] = file.sha256Checksum;
  if (file.headRevisionId != null) wire['headRevisionId'] = file.headRevisionId;
  return wire;
}

export function aboutFromWire(value: unknown): DriveAbout {
  const o = obj(value);
  const user = obj(o['user'] ?? {});
  const quota = obj(o['storageQuota'] ?? {});
  const email = str(user, 'emailAddress');
  if (email == null) throw corrupt();
  return {
    email,
    displayName: str(user, 'displayName'),
    quotaLimit: num(str(quota, 'limit')),
    quotaUsage: num(str(quota, 'usage')) ?? 0,
    quotaUsageInDrive: num(str(quota, 'usageInDrive')) ?? 0,
  };
}

export function revisionFromWire(value: unknown): DriveRevision {
  const o = obj(value);
  const id = str(o, 'id');
  if (id == null) throw corrupt();
  return { id, modifiedTime: time(str(o, 'modifiedTime')), size: num(str(o, 'size')) };
}

/** A create's body: name, MIME type, parents and app properties when there are any (keys sorted, as Kotlin). */
export function newFileJson(file: NewFile): Json {
  const body: Json = { name: file.name, mimeType: file.mimeType };
  if (file.parents?.length) body['parents'] = [...file.parents];
  const props = file.appProperties ?? {};
  if (Object.keys(props).length) body['appProperties'] = sortKeys(props);
  return body;
}

/** An update's body: only what changes; a removed app property is null. Parents go in the query. */
export function changeJson(change: MetadataChange): Json {
  const body: Json = {};
  if (change.name != null) body['name'] = change.name;
  const props = change.appProperties ?? {};
  if (Object.keys(props).length) body['appProperties'] = sortKeys(props);
  return body;
}

function sortKeys<T>(o: Readonly<Record<string, T>>): Record<string, T> {
  const out: Record<string, T> = {};
  for (const k of Object.keys(o).sort()) out[k] = o[k];
  return out;
}
