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
import { DRIVE_LAYOUT } from './drive-client';
import type { DriveErrorKind } from './drive-client';

/**
 * The pure rules of a Drive deletion (S4b-BL-119; Kotlin `DeletionRules` and the models of `DeletionModels.kt`, the same
 * names and rules), pinned on both stacks by `docs/schemas/delete-vectors.json`: what counts as Doorprints' own, the
 * order, which backups go and at what level, the operation id, the authorization check, which failure stops a run.
 */

export type DeletionLevel = 'L1' | 'L2' | 'L3';
const LEVELS: readonly DeletionLevel[] = ['L1', 'L2', 'L3'];
export const levelRank = (l: DeletionLevel): number => LEVELS.indexOf(l);

export type DeletionAction =
  | { readonly type: 'oneBackup'; readonly fileId: string }
  | { readonly type: 'olderBackups' }
  | { readonly type: 'allBackups' }
  | { readonly type: 'everything' };

export type ItemKind = 'backup' | 'sync' | 'photo' | 'shared' | 'readme' | 'control' | 'keys' | 'folder';

export interface DeletionItem {
  readonly id: string;
  readonly kind: ItemKind;
  readonly bytes: number;
  readonly phase: number;
}

export interface AuthorizationToken {
  readonly level: DeletionLevel;
  readonly issuedAtMs: number;
  readonly operationId: string;
  /** What the gate needs to know the token is genuine; never shown or logged. */
  readonly proof: string;
}

export type Refusal =
  | 'OFFLINE' | 'NOT_AUTHORIZED' | 'AUTHORIZATION_TOO_WEAK' | 'AUTHORIZATION_STALE' | 'AUTHORIZATION_OTHER_OPERATION'
  | 'STALE_PLAN' | 'ROOT_NOT_FOUND' | 'NOT_A_BACKUP' | 'NOTHING_TO_DELETE' | 'OTHER_DELETION_PENDING' | 'NOTHING_PENDING'
  | 'DRIVE_ERROR';

/** A grant is good for one operation, at most 60 seconds (docs/15 §10.2). */
export const AUTHORIZATION_MAX_AGE_MS = 60_000;
/** A pre-flight older than this is shown again before anything is deleted. */
export const PLAN_MAX_AGE_MS = 10 * 60_000;
/** Folder roles under the root, in the order their folders are deleted. */
export const SUBFOLDER_ROLES: readonly string[] = ['backups', 'sync', 'photos', 'shared'];

const KINDS: Readonly<Record<string, ItemKind>> = {
  backup: 'backup', sync: 'sync', photo: 'photo', shared: 'shared', readme: 'readme', control: 'control', keys: 'keys',
};

/** The folder a kind of file must sit in to count as ours. */
export function containerOf(kind: ItemKind): string | null {
  switch (kind) {
    case 'backup': return 'backups';
    case 'sync': return 'sync';
    case 'photo': return 'photos';
    case 'shared': return 'shared';
    case 'readme':
    case 'control':
    case 'keys': return 'root';
    default: return null;
  }
}

/** A file is ours only with a known `kind` app property AND in the folder that kind belongs in; else foreign. */
export function classifyFile(appProperties: Readonly<Record<string, string>>, containerRole: string): ItemKind | null {
  const code = appProperties[DRIVE_LAYOUT.kind];
  const kind = code !== undefined && Object.prototype.hasOwnProperty.call(KINDS, code) ? KINDS[code] : undefined;
  return kind !== undefined && containerOf(kind) === containerRole ? kind : null;
}

/** A folder directly under the root is ours only with a known role; the role, else null. */
export function classifyFolder(appProperties: Readonly<Record<string, string>>, containerRole: string): string | null {
  if (containerRole !== 'root') return null;
  const role = appProperties[DRIVE_LAYOUT.role];
  return role !== undefined && SUBFOLDER_ROLES.includes(role) ? role : null;
}

/** Data first, then the read-me, `doorprints.json`, `keys.json` last of the files, then sub-folders, the root last. */
export function phaseOf(kind: ItemKind, folderRole: string | null = null): number {
  switch (kind) {
    case 'backup': return 0;
    case 'sync': return 1;
    case 'photo': return 2;
    case 'shared': return 3;
    case 'readme': return 4;
    case 'control': return 5;
    case 'keys': return 6;
    default: return folderRole === 'root' ? 8 : 7;
  }
}

export interface BackupRef {
  readonly id: string;
  readonly complete: boolean;
  readonly createdAt: number;
}

/** Which backups go for an action, oldest first, and the level that needs; `ids` null: the named backup is not there. */
export interface BackupSelection {
  readonly ids: string[] | null;
  readonly level: DeletionLevel;
}

const byAge = (a: BackupRef, b: BackupRef): number => a.createdAt - b.createdAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);

export function selectBackups(action: DeletionAction, backups: readonly BackupRef[]): BackupSelection {
  const sorted = [...backups].sort(byAge);
  switch (action.type) {
    case 'oneBackup': {
      const target = sorted.find((b) => b.id === action.fileId);
      if (!target) return { ids: null, level: 'L1' };
      const otherComplete = sorted.some((b) => b.complete && b.id !== target.id);
      return { ids: [target.id], level: target.complete && !otherComplete ? 'L2' : 'L1' };
    }
    case 'allBackups':
      return { ids: sorted.map((b) => b.id), level: 'L2' };
    case 'olderBackups': {
      const keep = [...sorted].reverse().find((b) => b.complete);
      if (!keep) return { ids: [], level: 'L2' };
      return { ids: sorted.filter((b) => b.id !== keep.id && b.createdAt <= keep.createdAt).map((b) => b.id), level: 'L2' };
    }
    default:
      return { ids: sorted.map((b) => b.id), level: 'L3' };
  }
}

/** The id device authentication is bound to: the level, the root and the ordered ids (docs/15 §10.2). */
export function operationId(level: DeletionLevel, rootId: string, orderedIds: readonly string[]): string {
  const text = `doorprints-delete/1\n${level}\n${rootId}\n${orderedIds.join('\n')}`;
  return `del-${sha256Hex(new TextEncoder().encode(text)).slice(0, 24)}`;
}

/** Why `token` does not cover `required` for `opId` at `nowMs`, else null. L1 needs none. The gate checks genuineness. */
export function authorizationProblem(
  token: AuthorizationToken | null,
  required: DeletionLevel,
  opId: string,
  nowMs: number,
): Refusal | null {
  if (required === 'L1') return null;
  if (!token) return 'NOT_AUTHORIZED';
  if (levelRank(token.level) < levelRank(required)) return 'AUTHORIZATION_TOO_WEAK';
  if (token.operationId !== opId) return 'AUTHORIZATION_OTHER_OPERATION';
  const age = nowMs - token.issuedAtMs;
  if (age < 0 || age > AUTHORIZATION_MAX_AGE_MS) return 'AUTHORIZATION_STALE';
  return null;
}

/** A failed delete that ends the run on the spot; a refusal for one file lets the phase go on (later phases wait). */
export function stopsRun(kind: DriveErrorKind): boolean {
  return !(kind === 'FORBIDDEN' || kind === 'BAD_REQUEST' || kind === 'CONFLICT' || kind === 'CORRUPT' || kind === 'NOT_FOUND');
}
