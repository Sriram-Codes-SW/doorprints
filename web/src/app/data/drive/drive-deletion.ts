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

import { DRIVE_LAYOUT, DriveError, isFolder } from './drive-client';
import type { DeleteFailure, DeleteReport, DriveClient, DriveFile } from './drive-client';
import { listAll } from './drive-ops';
import {
  PLAN_MAX_AGE_MS, SUBFOLDER_ROLES, authorizationProblem, classifyFile, classifyFolder, operationId, phaseOf, selectBackups, stopsRun,
} from './drive-deletion-rules';
import type { AuthorizationToken, DeletionAction, DeletionItem, DeletionLevel, ItemKind, Refusal } from './drive-deletion-rules';

/*
 * Deleting what Doorprints keeps in the person's Drive (S4b-BL-119; docs/15 §3, §10.1). Kotlin `DriveDeletionService`, the
 * same rules and names; read its documentation. Short form: pre-flight lists exactly what will go (counts and bytes, no
 * names); L2/L3 need a genuine, fresh, operation-bound token from the injected gate; the list is written before the
 * first delete; permanent `files.delete`, a 404 is done; data first, `keys.json` last of the files, then folders, the
 * root last, a failed phase blocks the later ones; only files with our `kind` in the right folder are deleted, a folder
 * only when empty; nothing is created; the stored root id is verified, never searched for.
 */

/** How many files of one kind a plan removes and how many bytes they hold. */
export interface KindTotal {
  readonly count: number;
  readonly bytes: number;
}

/**
 * Exactly what a deletion will remove, fixed at preflight: the items with their phases, totals per kind and the count of files left alone because they are not ours.
 * The `operationId` binds an authorisation to this plan.
 */
export interface DeletionPlan {
  readonly action: DeletionAction;
  readonly level: DeletionLevel;
  readonly rootId: string;
  readonly items: readonly DeletionItem[];
  readonly totals: Readonly<Partial<Record<ItemKind, KindTotal>>>;
  readonly foreignKept: number;
  readonly createdAtMs: number;
  readonly operationId: string;
}

/** The seam for S4b-BL-127: genuineness of a token, and whether it still holds before each file. */
export interface AuthorizationGate {
  isGenuine(token: AuthorizationToken): Promise<boolean>;
  stillHolds(token: AuthorizationToken, action: DeletionAction): Promise<boolean>;
}

/** Why a run ended before everything was deleted. */
export type StopReason = 'DRIVE_ERROR' | 'AUTHORIZATION_LOST' | 'FILES_FAILED';
/** What a device does when it connects again after a deletion: ask before backing up, or start as a first connect. */
export type ReconnectPath = 'ASK_BEFORE_BACKUP' | 'FULL_FIRST_CONNECT';

/** Kept on the device after a finished deletion: automatic backup stays off; after L3 there is no folder id. */
export interface DeletedMarker {
  readonly level: DeletionLevel;
  readonly atMs: number;
  readonly action: string;
}

/**
 * After a full deletion (L3) no folder id is kept, so the next connect is a first connect; after a smaller one the person is asked before the next backup.
 */
export function reconnectPath(marker: DeletedMarker): ReconnectPath {
  return marker.level === 'L3' ? 'FULL_FIRST_CONNECT' : 'ASK_BEFORE_BACKUP';
}

/** A started deletion saved on the device so an interrupted run can be resumed. */
export interface PendingDeletion {
  readonly operationId: string;
  readonly level: DeletionLevel;
  readonly action: DeletionAction;
  readonly rootId: string;
  readonly items: readonly DeletionItem[];
  readonly total: number;
  readonly createdAtMs: number;
}

/** Where a device keeps the pending deletion and the marker of the last finished one. */
export interface DeletionStore {
  pending(): Promise<PendingDeletion | null>;
  savePending(pending: PendingDeletion): Promise<void>;
  clearPending(): Promise<void>;
  marker(): Promise<DeletedMarker | null>;
  recordFinished(marker: DeletedMarker, forgetFolder: boolean): Promise<void>;
}

/** The preflight outcome: a plan, or the refusal and any Drive error. */
export type PlanResult =
  | { readonly kind: 'ready'; readonly plan: DeletionPlan }
  | { readonly kind: 'refused'; readonly reason: Refusal; readonly error: DriveError | null };

/** What a run did: refused before starting, or how far it got (finished or stopped, with the files that were kept). */
export type DeletionOutcome =
  | { readonly kind: 'refused'; readonly reason: Refusal; readonly error: DriveError | null }
  | {
      readonly kind: 'ran';
      readonly report: DeleteReport;
      readonly finished: boolean;
      readonly total: number;
      readonly keptIds: readonly string[];
      readonly stopped: StopReason | null;
      readonly marker: DeletedMarker | null;
    };

/** The seams of `DriveDeletionService`: Drive, the authorisation gate, the store, connectivity and the clock. */
export interface DriveDeletionDeps {
  readonly drive: DriveClient;
  readonly gate: AuthorizationGate;
  readonly store: DeletionStore;
  readonly isOnline: () => boolean;
  readonly now: () => number;
  readonly saveEvery?: number;
}

const createdAtOf = (f: DriveFile): number => {
  const n = Number(f.appProperties[DRIVE_LAYOUT.createdAt]);
  return /^\d+$/.test(f.appProperties[DRIVE_LAYOUT.createdAt] ?? '') && Number.isSafeInteger(n) ? n : f.createdTime;
};
const cmp = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);

/**
 * Deletes what Doorprints keeps in the person's Drive, safely: it lists exactly what will go before anything is deleted, checks the authorisation before each file, and can resume an interrupted run.
 * Only files with Doorprints' own `kind` in the right folder are deleted; it never creates files and never searches for the root folder.
 */
export class DriveDeletionService {
  private running: Promise<unknown> = Promise.resolve();
  private readonly saveEvery: number;

  constructor(private readonly d: DriveDeletionDeps) {
    this.saveEvery = d.saveEvery ?? 25;
  }

  /** What a deletion of `action` would remove from the Drive folder `rootId` (the device's stored root id). */
  async preflight(rootId: string, action: DeletionAction): Promise<PlanResult> {
    const { drive } = this.d;
    if (!this.d.isOnline()) return { kind: 'refused', reason: 'OFFLINE', error: null };
    try {
      let root: DriveFile;
      try {
        root = await drive.getFile(rootId);
      } catch (e) {
        if (e instanceof DriveError && e.kind === 'NOT_FOUND') return { kind: 'refused', reason: 'ROOT_NOT_FOUND', error: null };
        throw e;
      }
      if (!isFolder(root) || root.trashed || root.appProperties[DRIVE_LAYOUT.role] !== 'root') {
        return { kind: 'refused', reason: 'ROOT_NOT_FOUND', error: null };
      }
      const children = await listAll(drive, { parentId: rootId, trashed: null });
      const folders = new Map<string, DriveFile[]>();
      const rootFiles: [DriveFile, ItemKind][] = [];
      let foreign = 0;
      for (const c of children) {
        if (isFolder(c)) {
          const role = classifyFolder(c.appProperties, 'root');
          if (role) folders.set(role, [...(folders.get(role) ?? []), c]);
          else foreign++;
        } else {
          const kind = classifyFile(c.appProperties, 'root');
          if (kind) rootFiles.push([c, kind]);
          else foreign++;
        }
      }
      const everything = action.type === 'everything';
      const inFolders = new Map<string, [DriveFile, ItemKind][]>();
      for (const role of SUBFOLDER_ROLES) {
        if (!everything && role !== 'backups') continue;
        for (const folder of folders.get(role) ?? []) {
          for (const f of await listAll(drive, { parentId: folder.id, trashed: null })) {
            const kind = isFolder(f) ? null : classifyFile(f.appProperties, role);
            if (kind) inFolders.set(role, [...(inFolders.get(role) ?? []), [f, kind]]);
            else foreign++;
          }
        }
      }
      const item = (f: DriveFile, kind: ItemKind): DeletionItem => ({ id: f.id, kind, bytes: f.size ?? 0, phase: phaseOf(kind) });
      const items: DeletionItem[] = [];
      let level: DeletionLevel;
      if (everything) {
        level = 'L3';
        const ordered = [...[...inFolders.values()].flat(), ...rootFiles];
        ordered.sort((a, b) => phaseOf(a[1]) - phaseOf(b[1]) || createdAtOf(a[0]) - createdAtOf(b[0]) || cmp(a[0].id, b[0].id));
        items.push(...ordered.map(([f, k]) => item(f, k)));
        for (const role of SUBFOLDER_ROLES) {
          const sorted = [...(folders.get(role) ?? [])].sort((a, b) => cmp(a.id, b.id));
          items.push(...sorted.map((f) => ({ id: f.id, kind: 'folder' as const, bytes: 0, phase: phaseOf('folder', role) })));
        }
        items.push({ id: rootId, kind: 'folder', bytes: 0, phase: phaseOf('folder', 'root') });
      } else {
        const entries = inFolders.get('backups') ?? [];
        const refs = entries.map(([f]) => ({
          id: f.id, complete: f.appProperties[DRIVE_LAYOUT.state] === DRIVE_LAYOUT.stateComplete, createdAt: createdAtOf(f),
        }));
        const selection = selectBackups(action, refs);
        if (selection.ids === null) return { kind: 'refused', reason: 'NOT_A_BACKUP', error: null };
        if (selection.ids.length === 0) return { kind: 'refused', reason: 'NOTHING_TO_DELETE', error: null };
        level = selection.level;
        const byId = new Map(entries.map(([f]) => [f.id, f]));
        items.push(...selection.ids.map((id) => item(byId.get(id)!, 'backup')));
      }
      const totals: Partial<Record<ItemKind, KindTotal>> = {};
      for (const i of items) {
        const t = totals[i.kind] ?? { count: 0, bytes: 0 };
        totals[i.kind] = { count: t.count + 1, bytes: t.bytes + i.bytes };
      }
      const plan: DeletionPlan = {
        action, level, rootId, items, totals, foreignKept: foreign, createdAtMs: this.d.now(),
        operationId: operationId(level, rootId, items.map((i) => i.id)),
      };
      return { kind: 'ready', plan };
    } catch (e) {
      if (!(e instanceof DriveError)) throw e;
      return { kind: 'refused', reason: e.kind === 'OFFLINE' ? 'OFFLINE' : 'DRIVE_ERROR', error: e };
    }
  }

  /** Runs `plan`. Nothing is deleted unless the result is `ran`. */
  delete(plan: DeletionPlan, token: AuthorizationToken | null): Promise<DeletionOutcome> {
    return this.exclusive(async () => {
      if (!this.d.isOnline()) return refused('OFFLINE');
      const pending = await this.d.store.pending();
      if (pending && pending.operationId !== plan.operationId) return refused('OTHER_DELETION_PENDING');
      const now = this.d.now();
      if (now - plan.createdAtMs > PLAN_MAX_AGE_MS || now < plan.createdAtMs) return refused('STALE_PLAN');
      const problem = await this.authorizationRefusal(token, plan.level, plan.operationId);
      if (problem) return refused(problem);
      let list = pending;
      if (!list) {
        list = {
          operationId: plan.operationId, level: plan.level, action: plan.action, rootId: plan.rootId,
          items: plan.items, total: plan.items.length, createdAtMs: plan.createdAtMs,
        };
        await this.d.store.savePending(list);
      }
      return this.run(list, token);
    });
  }

  /** Finishes the deletion a stop left. L2 and L3 need a fresh authorization again. */
  resume(token: AuthorizationToken | null): Promise<DeletionOutcome> {
    return this.exclusive(async () => {
      if (!this.d.isOnline()) return refused('OFFLINE');
      const pending = await this.d.store.pending();
      if (!pending) return refused('NOTHING_PENDING');
      const problem = await this.authorizationRefusal(token, pending.level, pending.operationId);
      if (problem) return refused(problem);
      return this.run(pending, token);
    });
  }

  private exclusive<T>(block: () => Promise<T>): Promise<T> {
    const next = this.running.then(block, block);
    this.running = next.catch(() => undefined);
    return next;
  }

  private async authorizationRefusal(token: AuthorizationToken | null, level: DeletionLevel, opId: string): Promise<Refusal | null> {
    const problem = authorizationProblem(token, level, opId, this.d.now());
    if (problem) return problem;
    if (level !== 'L1' && !(await this.d.gate.isGenuine(token!))) return 'NOT_AUTHORIZED';
    return null;
  }

  private async run(pending: PendingDeletion, token: AuthorizationToken | null): Promise<DeletionOutcome> {
    const { drive, store, gate } = this.d;
    const deleted: string[] = [];
    const failed: DeleteFailure[] = [];
    const kept: string[] = [];
    const items = pending.items;
    let firstError: DriveError | null = null;
    let stopped: StopReason | null = null;
    let blockedFrom = Number.MAX_SAFE_INTEGER;
    let sinceSave = 0;
    for (let index = 0; index < items.length; index++) {
      const it = items[index];
      if (it.phase >= blockedFrom) break;
      if (pending.level !== 'L1' && !(await gate.stillHolds(token!, pending.action))) {
        stopped = 'AUTHORIZATION_LOST';
        break;
      }
      try {
        if (it.kind === 'folder' && (await drive.list({ parentId: it.id, trashed: null }, null, 1)).files.length > 0) {
          kept.push(it.id);
        } else {
          await drive.delete(it.id);
          deleted.push(it.id);
        }
      } catch (e) {
        if (!(e instanceof DriveError)) throw e;
        failed.push({ fileId: it.id, kind: e.kind, httpStatus: e.httpStatus });
        firstError ??= e;
        if (stopsRun(e.kind)) {
          stopped = 'DRIVE_ERROR';
          break;
        }
        blockedFrom = Math.min(blockedFrom, it.phase + 1);
      }
      if (++sinceSave >= this.saveEvery) {
        sinceSave = 0;
        const gone = new Set([...deleted, ...kept]);
        await store.savePending({ ...pending, items: items.filter((r) => !gone.has(r.id)) });
      }
    }
    const done = new Set([...deleted, ...kept]);
    const left = items.filter((i) => !done.has(i.id));
    if (stopped === null && left.length > 0) stopped = 'FILES_FAILED';
    const report: DeleteReport = { deleted, left: left.map((i) => i.id), error: firstError, failed };
    if (left.length > 0) {
      await store.savePending({ ...pending, items: left });
      return { kind: 'ran', report, finished: false, total: pending.total, keptIds: kept, stopped, marker: null };
    }
    let marker: DeletedMarker | null = null;
    if (pending.level === 'L3') {
      marker = { level: 'L3', atMs: this.d.now(), action: 'everything' };
      await store.recordFinished(marker, true);
    } else if (pending.action.type === 'allBackups') {
      marker = { level: 'L2', atMs: this.d.now(), action: 'allBackups' };
      await store.recordFinished(marker, false);
    }
    await store.clearPending();
    return { kind: 'ran', report, finished: true, total: pending.total, keptIds: kept, stopped: null, marker };
  }
}

function refused(reason: Refusal): DeletionOutcome {
  return { kind: 'refused', reason, error: null };
}
