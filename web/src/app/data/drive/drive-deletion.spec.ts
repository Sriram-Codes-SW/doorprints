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
import { DRIVE_LAYOUT, FOLDER_MIME } from './drive-client';
import type { DriveFile } from './drive-client';
import { DriveDeletionService } from './drive-deletion';
import type { AuthorizationGate, DeletedMarker, DeletionOutcome, DeletionPlan, DeletionStore, PendingDeletion, PlanResult } from './drive-deletion';
import { reconnectPath } from './drive-deletion';
import type { AuthorizationToken, DeletionAction, DeletionLevel } from './drive-deletion-rules';
import { DriveFaults } from './fake-drive-faults';
import { FakeDriveServer, InMemoryFakeDrive } from './in-memory-fake-drive';

/**
 * `DriveDeletionService` over `InMemoryFakeDrive` and its fault script (S4b-BL-119; Kotlin `DriveDeletionServiceTest`, the
 * same cases): every level, a stop at every step then a resume, 404s, rate and quota faults, refused without (fresh)
 * authorization, refused offline, the order, and files that are not Doorprints' own.
 */

class FakeGate implements AuthorizationGate {
  genuine = true;
  lockOn = true;
  holdsFor = Number.MAX_SAFE_INTEGER;
  asked = 0;
  async isGenuine(t: AuthorizationToken): Promise<boolean> {
    return this.genuine && t.proof === 'ok';
  }
  async stillHolds(): Promise<boolean> {
    return this.lockOn && ++this.asked <= this.holdsFor;
  }
}

class FakeStore implements DeletionStore {
  current: PendingDeletion | null = null;
  markerValue: DeletedMarker | null = null;
  folderForgotten = false;
  saves = 0;
  constructor(private readonly crashy = false) {}
  async pending() { return this.current; }
  async savePending(p: PendingDeletion) {
    this.saves++;
    if (!this.crashy || this.current == null) this.current = p;
  }
  async clearPending() { this.current = null; }
  async marker() { return this.markerValue; }
  async recordFinished(m: DeletedMarker, forget: boolean) {
    this.markerValue = m;
    if (forget) this.folderForgotten = true;
  }
}

class Rig {
  readonly server = new FakeDriveServer();
  readonly drive = new InMemoryFakeDrive(this.server);
  readonly gate = new FakeGate();
  readonly store: FakeStore;
  online = true;
  readonly service: DriveDeletionService;
  readonly root = this.folder('Doorprints', 'root', null);
  readonly backups = this.folder('Backups', 'backups', this.root.id);
  readonly sync = this.folder('Sync', 'sync', this.root.id);
  readonly photos = this.folder('Photos', 'photos', this.root.id);
  readonly shared = this.folder('Shared', 'shared', this.root.id);
  readonly b1 = this.file('backup-1', 'backup', this.backups.id, 100);
  readonly b2 = this.file('backup-2', 'backup', this.backups.id, 200);
  readonly b3 = this.file('backup-3', 'backup', this.backups.id, 300);
  readonly bPartial = this.file('partial-backup', 'backup', this.backups.id, 400, false);
  readonly s1 = this.file('device-a', 'sync', this.sync.id, 0);
  readonly s2 = this.file('device-b', 'sync', this.sync.id, 0);
  readonly p1 = this.file('p-1', 'photo', this.photos.id, 0);
  readonly p2 = this.file('p-2', 'photo', this.photos.id, 0);
  readonly p3 = this.file('p-3', 'photo', this.photos.id, 0);
  readonly sh = this.file('shared-1', 'shared', this.shared.id, 0);
  readonly readme = this.file('Read me.txt', 'readme', this.root.id, 0);
  readonly control = this.file('doorprints.json', 'control', this.root.id, 0);
  readonly keys = this.file('keys.json', 'keys', this.root.id, 0);

  constructor(crashy = false) {
    this.store = new FakeStore(crashy);
    this.service = new DriveDeletionService({
      drive: this.drive, gate: this.gate, store: this.store, isOnline: () => this.online, now: () => this.server.clock.now(), saveEvery: 3,
    });
  }

  folder(name: string, role: string, parent: string | null): DriveFile {
    return this.server.putByHand({ name, mimeType: FOLDER_MIME, parents: parent ? [parent] : [], appProperties: { [DRIVE_LAYOUT.role]: role } });
  }

  file(name: string, kind: string, parent: string, createdAt: number, complete = true): DriveFile {
    const props: Record<string, string> = { [DRIVE_LAYOUT.kind]: kind };
    if (kind === 'backup') {
      props[DRIVE_LAYOUT.createdAt] = String(createdAt);
      props[DRIVE_LAYOUT.state] = complete ? DRIVE_LAYOUT.stateComplete : DRIVE_LAYOUT.statePartial;
    }
    return this.server.putByHand({ name, mimeType: 'application/octet-stream', parents: [parent], appProperties: props }, new Uint8Array(10 + name.length));
  }

  foreign(name: string, parent: string, props: Record<string, string> = {}): DriveFile {
    return this.server.putByHand({ name, mimeType: 'text/plain', parents: [parent], appProperties: props }, new Uint8Array(5));
  }

  get now(): number { return this.server.clock.now(); }
  token(plan: DeletionPlan, o: { level?: DeletionLevel; at?: number; proof?: string } = {}): AuthorizationToken {
    return { level: o.level ?? plan.level, issuedAtMs: o.at ?? this.now, operationId: plan.operationId, proof: o.proof ?? 'ok' };
  }
  async plan(action: DeletionAction, rootId = this.root.id): Promise<DeletionPlan> {
    const r = await this.service.preflight(rootId, action);
    if (r.kind !== 'ready') throw new Error(`refused ${r.reason}`);
    return r.plan;
  }
  deletes(): string[] { return this.server.requests.filter((r) => r.op === 'DELETE').map((r) => r.about!); }
  exists(id: string): boolean { return this.server.fileOrNull(id) != null; }
  ids(...f: DriveFile[]): string[] { return f.map((x) => x.id); }
}

function ran(o: DeletionOutcome) {
  if (o.kind !== 'ran') throw new Error(`refused ${o.reason}`);
  return o;
}
const reason = (o: DeletionOutcome | PlanResult) => (o.kind === 'refused' ? o.reason : `ran:${o.kind}`);
const everything: DeletionAction = { type: 'everything' };
const allBackups: DeletionAction = { type: 'allBackups' };

describe('DriveDeletionService', () => {
  it('lists the tree with counts and sizes and no names', async () => {
    const r = new Rig();
    r.foreign('holiday-photos.txt', r.root.id);
    const plan = await r.plan(everything);
    expect(plan.level).toBe('L3');
    expect(plan.totals.backup).toEqual({ count: 4, bytes: [r.b1, r.b2, r.b3, r.bPartial].reduce((n, f) => n + r.server.fileOrNull(f.id)!.size!, 0) });
    expect(plan.totals.sync?.count).toBe(2);
    expect(plan.totals.photo?.count).toBe(3);
    expect(plan.totals.keys?.count).toBe(1);
    expect(plan.totals.folder?.count).toBe(5);
    expect(plan.foreignKept).toBe(1);
    expect(plan.items.length).toBe(r.server.allFiles().length - 1);
    const text = JSON.stringify(plan);
    for (const f of r.server.allFiles()) expect(text.includes(f.name), f.name).toBe(false);
    expect(r.deletes()).toEqual([]);
  });

  it('verifies the stored root and never searches for one', async () => {
    const r = new Rig();
    const other = r.server.putByHand({ name: 'Doorprints', mimeType: FOLDER_MIME, appProperties: { [DRIVE_LAYOUT.role]: 'root' } });
    expect(reason(await r.service.preflight('nope-id', everything))).toBe('ROOT_NOT_FOUND');
    expect(reason(await r.service.preflight(r.sync.id, everything))).toBe('ROOT_NOT_FOUND');
    r.server.trashByHand(other.id);
    expect(reason(await r.service.preflight(other.id, everything))).toBe('ROOT_NOT_FOUND');
    expect((await r.plan(everything)).items.map((i) => i.id)).not.toContain(other.id);
  });

  it('refuses a backup action on something that is not a backup', async () => {
    const r = new Rig();
    const planted = r.foreign('planted.dpx', r.sync.id, { [DRIVE_LAYOUT.kind]: 'backup' });
    for (const id of [r.s1.id, r.keys.id, r.backups.id, 'missing', planted.id]) {
      expect(reason(await r.service.preflight(r.root.id, { type: 'oneBackup', fileId: id }))).toBe('NOT_A_BACKUP');
    }
  });

  it('deletes one backup at L1 without a token', async () => {
    const r = new Rig();
    const plan = await r.plan({ type: 'oneBackup', fileId: r.b2.id });
    expect(plan.level).toBe('L1');
    expect(ran(await r.service.delete(plan, null)).finished).toBe(true);
    expect(r.deletes()).toEqual([r.b2.id]);
    for (const id of r.ids(r.b1, r.b3, r.bPartial, r.s1, r.p1, r.keys, r.control, r.backups, r.root)) expect(r.exists(id)).toBe(true);
    expect(r.store.markerValue).toBeNull();
    expect(r.store.current).toBeNull();
  });

  it('makes the last complete backup L2', async () => {
    const r = new Rig();
    r.server.deleteByHand(r.b1.id);
    r.server.deleteByHand(r.b2.id);
    const plan = await r.plan({ type: 'oneBackup', fileId: r.b3.id });
    expect(plan.level).toBe('L2');
    expect(reason(await r.service.delete(plan, null))).toBe('NOT_AUTHORIZED');
    expect(r.exists(r.b3.id)).toBe(true);
    expect(ran(await r.service.delete(plan, r.token(plan))).finished).toBe(true);
    expect(r.store.markerValue).toBeNull();
  });

  it('keeps the newest complete backup and a newer partial one for older backups', async () => {
    const r = new Rig();
    const plan = await r.plan({ type: 'olderBackups' });
    expect(plan.level).toBe('L2');
    expect(plan.items.map((i) => i.id)).toEqual(r.ids(r.b1, r.b2));
    ran(await r.service.delete(plan, r.token(plan)));
    expect(r.exists(r.b3.id) && r.exists(r.bPartial.id)).toBe(true);
    r.server.deleteByHand(r.b2.id);
    expect(reason(await r.service.preflight(r.root.id, { type: 'olderBackups' }))).toBe('NOTHING_TO_DELETE');
  });

  it('deletes all backups oldest first, leaves the rest and switches backup off', async () => {
    const r = new Rig();
    const plan = await r.plan(allBackups);
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.finished).toBe(true);
    expect(r.deletes()).toEqual(r.ids(r.b1, r.b2, r.b3, r.bPartial));
    for (const id of r.ids(r.backups, r.s1, r.s2, r.p1, r.sh, r.keys, r.control, r.root)) expect(r.exists(id)).toBe(true);
    expect(reconnectPath(r.store.markerValue!)).toBe('ASK_BEFORE_BACKUP');
    expect(r.store.folderForgotten).toBe(false);
  });

  it('deletes everything data first, keys.json last of the files, then folders, and forgets the folder', async () => {
    const r = new Rig();
    const before = r.server.allFiles().length;
    const plan = await r.plan(everything);
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.finished).toBe(true);
    expect(out.report.deleted.length).toBe(before);
    expect(r.server.allFiles()).toEqual([]);
    const order = r.deletes();
    const pos = (f: DriveFile) => order.indexOf(f.id);
    for (const d of [r.b1, r.b2, r.b3, r.bPartial, r.s1, r.s2, r.p1, r.p2, r.p3, r.sh]) {
      expect(pos(d) < pos(r.readme) && pos(r.readme) < pos(r.control) && pos(r.control) < pos(r.keys)).toBe(true);
    }
    expect(order[order.length - 6]).toBe(r.keys.id);
    expect(order[order.length - 1]).toBe(r.root.id);
    expect(pos(r.b3) < pos(r.s1) && pos(r.s2) < pos(r.p1) && pos(r.p3) < pos(r.sh)).toBe(true);
    for (const f of [r.backups, r.sync, r.photos, r.shared]) expect(pos(r.keys) < pos(f) && pos(f) < pos(r.root)).toBe(true);
    expect(reconnectPath(out.marker!)).toBe('FULL_FIRST_CONNECT');
    expect(r.store.folderForgotten).toBe(true);
    expect(r.store.current).toBeNull();
  });

  it('only reads, lists and deletes: nothing is created, uploaded or trashed', async () => {
    const r = new Rig();
    const plan = await r.plan(everything);
    ran(await r.service.delete(plan, r.token(plan)));
    expect([...new Set(r.server.requests.map((q) => q.op))].sort()).toEqual(['DELETE', 'GET', 'LIST']);
  });

  it('never deletes a file that is not ours, and keeps its folders', async () => {
    const r = new Rig();
    const mine = r.foreign('tax-return.pdf', r.photos.id);
    const wrongPlace = r.foreign('fake-keys', r.backups.id, { [DRIVE_LAYOUT.kind]: 'keys' });
    const stranger = r.foreign('notes.txt', r.root.id);
    const nested = r.server.putByHand({ name: 'Mine', mimeType: FOLDER_MIME, parents: [r.root.id] });
    const inNested = r.foreign('deep.txt', nested.id, { [DRIVE_LAYOUT.kind]: 'backup' });
    const plan = await r.plan(everything);
    expect(plan.foreignKept).toBe(4);
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.finished).toBe(true);
    for (const f of [mine, wrongPlace, stranger, nested, inNested]) expect(r.exists(f.id), f.name).toBe(true);
    expect(new Set(out.keptIds)).toEqual(new Set([r.photos.id, r.backups.id, r.root.id]));
    expect(r.exists(r.sync.id) || r.exists(r.shared.id)).toBe(false);
    for (const id of r.ids(r.b1, r.s1, r.p1, r.sh, r.keys, r.control, r.readme)) expect(r.exists(id)).toBe(false);
  });

  it('keeps a folder that got a new file while deleting', async () => {
    const r = new Rig();
    const plan = await r.plan(everything);
    let added: DriveFile | null = null;
    r.server.faults.onCall(r.server.requests.length + 1, DriveFaults.interleave((s) => {
      added = s.putByHand({ name: 'late.txt', mimeType: 'text/plain', parents: [r.shared.id] });
    }));
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.finished).toBe(true);
    expect(r.exists(added!.id)).toBe(true);
    expect(out.keptIds).toContain(r.shared.id);
    expect(out.keptIds).toContain(r.root.id);
  });

  it('refuses L2 and L3 without a genuine, fresh, strong enough, operation-bound token', async () => {
    const r = new Rig();
    for (const action of [allBackups, { type: 'olderBackups' } as DeletionAction, everything]) {
      const plan = await r.plan(action);
      const other = await r.plan(action.type === 'everything' ? allBackups : everything);
      expect(reason(await r.service.delete(plan, null))).toBe('NOT_AUTHORIZED');
      expect(reason(await r.service.delete(plan, r.token(plan, { proof: 'forged' })))).toBe('NOT_AUTHORIZED');
      expect(reason(await r.service.delete(plan, r.token(other, { level: 'L3' })))).toBe('AUTHORIZATION_OTHER_OPERATION');
      expect(reason(await r.service.delete(plan, r.token(plan, { at: r.now - 61_000 })))).toBe('AUTHORIZATION_STALE');
      expect(reason(await r.service.delete(plan, r.token(plan, { at: r.now + 5_000 })))).toBe('AUTHORIZATION_STALE');
      r.gate.genuine = false;
      expect(reason(await r.service.delete(plan, r.token(plan)))).toBe('NOT_AUTHORIZED');
      r.gate.genuine = true;
    }
    const plan = await r.plan(everything);
    expect(reason(await r.service.delete(plan, r.token(plan, { level: 'L2' })))).toBe('AUTHORIZATION_TOO_WEAK');
    expect(r.deletes()).toEqual([]);
    expect(r.store.current).toBeNull();
  });

  it('refuses a token that aged while planning, and a plan that aged', async () => {
    const r = new Rig();
    const plan = await r.plan(allBackups);
    const t = r.token(plan);
    r.server.clock.advance(61_000);
    expect(reason(await r.service.delete(plan, t))).toBe('AUTHORIZATION_STALE');
    r.server.clock.advance(10 * 60_000);
    expect(reason(await r.service.delete(plan, r.token(plan)))).toBe('STALE_PLAN');
    expect(r.deletes()).toEqual([]);
  });

  it('stops before the next file when the lock is lost, and a fresh grant finishes', async () => {
    const r = new Rig();
    const plan = await r.plan(everything);
    r.gate.holdsFor = 4;
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.finished).toBe(false);
    expect(out.stopped).toBe('AUTHORIZATION_LOST');
    expect(r.deletes().length).toBe(4);
    expect(out.report.left.length).toBe(plan.items.length - 4);
    expect(r.exists(r.keys.id)).toBe(true);
    r.gate.holdsFor = Number.MAX_SAFE_INTEGER;
    r.server.clock.advance(61_000);
    expect(reason(await r.service.resume(r.token(plan, { at: r.now - 61_000 })))).toBe('AUTHORIZATION_STALE');
    expect(ran(await r.service.resume(r.token(plan))).finished).toBe(true);
    expect(r.server.allFiles()).toEqual([]);
  });

  it('refuses offline and queues nothing', async () => {
    const r = new Rig();
    const plan = await r.plan(everything);
    r.online = false;
    expect(reason(await r.service.delete(plan, r.token(plan)))).toBe('OFFLINE');
    expect(reason(await r.service.preflight(r.root.id, everything))).toBe('OFFLINE');
    expect(reason(await r.service.resume(r.token(plan)))).toBe('OFFLINE');
    expect(r.deletes()).toEqual([]);
    expect(r.store.current).toBeNull();
    r.online = true;
    r.server.faults.always(DriveFaults.offline);
    expect(reason(await r.service.preflight(r.root.id, everything))).toBe('OFFLINE');
  });

  it('reports what is left when the connection drops, and Try again finishes', async () => {
    const r = new Rig();
    const plan = await r.plan(allBackups);
    r.server.faults.stopAfter(r.server.requests.length + 2);
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.finished).toBe(false);
    expect(out.stopped).toBe('DRIVE_ERROR');
    expect(out.report.error!.kind).toBe('OFFLINE');
    expect(out.report.deleted).toEqual(r.ids(r.b1, r.b2));
    expect(out.report.left).toEqual(r.ids(r.b3, r.bPartial));
    expect(r.store.markerValue).toBeNull();
    expect(r.store.current).not.toBeNull();
    r.server.faults.clear();
    expect(ran(await r.service.resume(r.token(plan))).finished).toBe(true);
    expect(r.store.current).toBeNull();
    expect(r.store.markerValue).not.toBeNull();
  });

  it('counts a file that is already gone as deleted', async () => {
    const r = new Rig();
    const plan = await r.plan(allBackups);
    r.server.deleteByHand(r.b2.id);
    r.server.faults.on('DELETE', 4, DriveFaults.notFound);
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.finished).toBe(true);
    expect(out.report.failed).toEqual([]);
    expect(out.report.error).toBeNull();
    expect(out.report.deleted.length).toBe(4);
    expect(r.exists(r.bPartial.id)).toBe(true);
  });

  it('retries a rate limit and stops the run at a long one', async () => {
    const r = new Rig();
    const plan = await r.plan(allBackups);
    r.server.faults.next(DriveFaults.rateLimited(2_000), 'DELETE', 2);
    expect(ran(await r.service.delete(plan, r.token(plan))).finished).toBe(true);
    expect(r.server.clock.slept).toEqual([2_000, 2_000]);

    const r2 = new Rig();
    const plan2 = await r2.plan(allBackups);
    r2.server.faults.on('DELETE', 2, DriveFaults.rateLimited(120_000));
    const out = ran(await r2.service.delete(plan2, r2.token(plan2)));
    expect(out.finished).toBe(false);
    expect(out.report.error!.kind).toBe('RATE_LIMITED');
    expect(out.report.deleted).toEqual(r2.ids(r2.b1));
    expect(out.report.left.length).toBe(3);
    expect(out.report.failed.map((f) => f.kind)).toEqual(['RATE_LIMITED']);
    expect(ran(await r2.service.resume(r2.token(plan2))).finished).toBe(true);
  });

  it('stops at a quota fault and reports it per file', async () => {
    const r = new Rig();
    const plan = await r.plan(allBackups);
    r.server.faults.on('DELETE', 1, DriveFaults.quotaExceeded);
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.report.failed).toEqual([{ fileId: r.b1.id, kind: 'QUOTA_EXCEEDED', httpStatus: 403 }]);
    expect(out.report.left.length).toBe(4);
    expect(r.deletes().length).toBe(1);
  });

  it('blocks later phases after a failed file, so keys.json never goes alone', async () => {
    const r = new Rig();
    const plan = await r.plan(everything);
    r.server.faults.on('DELETE', 5, DriveFaults.forbidden);
    const out = ran(await r.service.delete(plan, r.token(plan)));
    expect(out.finished).toBe(false);
    expect(out.stopped).toBe('FILES_FAILED');
    expect(out.report.failed.map((f) => f.fileId)).toEqual([r.s1.id]);
    expect(out.report.failed[0].kind).toBe('FORBIDDEN');
    expect(r.exists(r.keys.id) && r.exists(r.control.id) && r.exists(r.p1.id)).toBe(true);
    expect(r.exists(r.s2.id)).toBe(false);
    expect(r.exists(r.s1.id)).toBe(true);
    expect(out.marker).toBeNull();
    expect(r.store.folderForgotten).toBe(false);
    expect(ran(await r.service.resume(r.token(plan))).finished).toBe(true);
    expect(r.server.allFiles()).toEqual([]);
  });

  it('survives a stop at every step of everything, then a resume, with keys.json always last', async () => {
    const probe = new Rig();
    const probePlan = await probe.plan(everything);
    const base0 = probe.server.requests.length;
    ran(await probe.service.delete(probePlan, probe.token(probePlan)));
    const total = probe.server.requests.length - base0;
    for (const crashy of [false, true]) {
      for (let stopAt = 0; stopAt <= total + 1; stopAt++) {
        const r = new Rig(crashy);
        const plan = await r.plan(everything);
        r.server.faults.stopAfter(r.server.requests.length + stopAt);
        const first = ran(await r.service.delete(plan, r.token(plan)));
        if (!r.exists(r.keys.id)) {
          const dataLeft = r.server.allFiles().filter((f) => ['backup', 'sync', 'photo', 'shared'].includes(f.appProperties[DRIVE_LAYOUT.kind]));
          expect(dataLeft, `stop ${stopAt} crashy ${crashy}`).toEqual([]);
        }
        r.server.faults.clear();
        if (!first.finished) {
          expect(r.store.current).not.toBeNull();
          expect(ran(await r.service.resume(r.token(plan))).finished, `stop ${stopAt}`).toBe(true);
        }
        expect(r.server.allFiles(), `stop ${stopAt} crashy ${crashy}`).toEqual([]);
        expect(r.store.current).toBeNull();
        expect(r.store.folderForgotten).toBe(true);
        expect(r.server.requests.some((q) => q.op === 'CREATE' || q.op === 'UPLOAD')).toBe(false);
        const order = [...new Set(r.deletes())];
        expect(new Set(order.slice(order.indexOf(r.keys.id) + 1))).toEqual(new Set(r.ids(r.backups, r.sync, r.photos, r.shared, r.root)));
      }
    }
  });

  it('survives a stop at every step of all backups and keeps keys and everything else', async () => {
    for (let stopAt = 0; stopAt <= 12; stopAt++) {
      const r = new Rig();
      const plan = await r.plan(allBackups);
      r.server.faults.stopAfter(r.server.requests.length + stopAt);
      const first = ran(await r.service.delete(plan, r.token(plan)));
      r.server.faults.clear();
      if (!first.finished) expect(ran(await r.service.resume(r.token(plan))).finished).toBe(true);
      for (const id of r.ids(r.b1, r.b2, r.b3, r.bPartial)) expect(r.exists(id)).toBe(false);
      for (const id of r.ids(r.keys, r.control, r.s1, r.p1, r.sh, r.backups, r.root)) expect(r.exists(id), `stop ${stopAt}`).toBe(true);
    }
  });

  it('writes the whole list before the first delete and makes another deletion wait', async () => {
    const r = new Rig();
    const plan = await r.plan(allBackups);
    let seen: PendingDeletion | null = null;
    r.server.faults.on('DELETE', 1, DriveFaults.interleave(() => { seen = r.store.current; }));
    r.server.faults.stopAfter(r.server.requests.length + 1);
    ran(await r.service.delete(plan, r.token(plan)));
    expect(seen!.items).toEqual(plan.items);
    expect(seen!.operationId).toBe(plan.operationId);
    r.server.faults.clear();
    const other = await r.plan(everything);
    expect(reason(await r.service.delete(other, r.token(other)))).toBe('OTHER_DELETION_PENDING');
    expect(ran(await r.service.resume(r.token(plan))).finished).toBe(true);
    expect(reason(await r.service.resume(r.token(plan)))).toBe('NOTHING_PENDING');
  });

  it('finishes when the same plan is run again', async () => {
    const r = new Rig();
    const plan = await r.plan(allBackups);
    r.server.faults.stopAfter(r.server.requests.length + 1);
    expect(ran(await r.service.delete(plan, r.token(plan))).finished).toBe(false);
    r.server.faults.clear();
    expect(ran(await r.service.delete(plan, r.token(plan))).finished).toBe(true);
  });

  it('saves its progress along the way and deletes files in the bin too', async () => {
    const r = new Rig();
    r.server.trashByHand(r.b1.id);
    const plan = await r.plan(everything);
    expect(plan.items.map((i) => i.id)).toContain(r.b1.id);
    ran(await r.service.delete(plan, r.token(plan)));
    expect(r.store.saves).toBeGreaterThan(2);
    expect(r.server.allFiles()).toEqual([]);
  });
});
