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
import scenariosJson from '../../../../../docs/schemas/drive-sync-scenarios.json';
import { DRIVE_LAYOUT, DriveError } from './drive-client';
import { KIND_SYNC, reportOf, skippedReasons } from './drive-sync-seams';
import type { SyncPassResult } from './drive-sync-seams';
import { DriveFaults } from './fake-drive-faults';
import type { DriveOp } from './fake-drive-faults';
import { SyncWorld } from './drive-sync-test-world';
import type { TestDevice } from './drive-sync-test-world';

/**
 * The shared scenario vectors (`docs/schemas/drive-sync-scenarios.json`, S4b-BL-118): the same steps and expectations
 * as Kotlin's `DriveSyncScenariosTest`, over this stack's fake Drive and the real encryption core.
 */

type Step = Record<string, unknown> & { do: string };
type Scenario = { name: string; devices: string[]; steps: Step[] };
const doc = scenariosJson as unknown as { format: string; scenarios: Scenario[] };

describe('drive sync scenarios', () => {
  it('has the format', () => {
    expect(doc.format).toBe('doorprints-sync-scenarios/1');
    expect(doc.scenarios.length).toBeGreaterThanOrEqual(10);
  });

  for (const scenario of doc.scenarios) {
    it(scenario.name, async () => {
      const w = new SyncWorld();
      const devices = new Map<string, TestDevice>();
      for (const n of scenario.devices) devices.set(n, await w.add(n));
      const snapshots = new Map<string, Uint8Array>();
      const dev = (s: Step, key = 'device') => devices.get(s[key] as string)!;
      const planted = (slot: string, seq: string) => ({
        name: 'again.dpx', mimeType: 'application/octet-stream', parents: [w.syncFolderId()],
        appProperties: { [DRIVE_LAYOUT.kind]: KIND_SYNC, [DRIVE_LAYOUT.device]: slot, [DRIVE_LAYOUT.state]: DRIVE_LAYOUT.stateComplete, seq },
      });
      for (const [index, step] of scenario.steps.entries()) {
        const at = `step ${index} (${step.do})`;
        switch (step.do) {
          case 'edit': dev(step).edit(step['house'] as string, step['label'] as string); break;
          case 'delete': dev(step).delete(step['house'] as string); break;
          case 'advance': w.server.clock.advance(step['ms'] as number); break;
          case 'skew': dev(step).skewMs = step['ms'] as number; break;
          case 'failDrive': {
            const status = step['status'] as number;
            const fault = status === 0 ? DriveFaults.offline : DriveFaults.server(status);
            w.server.faults.always(fault, step['op'] === 'ALL' ? null : (step['op'] as DriveOp));
            break;
          }
          case 'healDrive': w.server.faults.clear(); break;
          case 'corruptNextDownload':
            w.server.faults.next(DriveFaults.interleave((s) => s.editByHand(s.allFiles().find((f) => f.name.startsWith('sync-'))!.id, new Uint8Array(300).fill(7))), 'DOWNLOAD');
            break;
          case 'sync': {
            const d = dev(step);
            const opts = { confirm: step['confirm'] === true, stale: step['stale'] === true, force: step['force'] !== false };
            const expectError = step['expectError'] as string | undefined;
            if (expectError) {
              let kind = '';
              try {
                await d.sync(opts);
              } catch (e) {
                kind = e instanceof DriveError ? e.kind : String(e);
              }
              expect(kind, at).toBe(expectError);
            } else {
              const r = await d.sync(opts);
              if (step['expect']) check(at, r, step['expect'] as Record<string, unknown>);
            }
            break;
          }
          case 'expectLabels': expect(dev(step).local.labels(), at).toEqual(step['labels']); break;
          case 'expectLabel': expect(dev(step).local.label(step['house'] as string), at).toBe(step['label']); break;
          case 'expectDirty': expect(dev(step).local.dirty.size, at).toBe(step['count']); break;
          case 'snapshot': snapshots.set(step['name'] as string, w.server.contentOf(dev(step).state.lastFileId!)!); break;
          case 'trashLastFile': w.server.trashByHand(dev(step).state.lastFileId!); break;
          case 'replay': w.server.putByHand(planted(dev(step).id, '50'), snapshots.get(step['name'] as string)!); break;
          case 'revoke': await w.revoke(dev(step, 'by'), dev(step, 'victim')); break;
          case 'plant': {
            const good = w.server.contentOf(devices.get(step['from'] as string)!.state.lastFileId!)!;
            const slot = step['slot'] === 'X' ? 'f'.repeat(32) : devices.get(step['slot'] as string)!.id;
            let bytes: Uint8Array;
            if (step['how'] === 'copy') bytes = good;
            else if (step['how'] === 'flip') {
              bytes = good.slice();
              bytes[bytes.length - 3] ^= 1;
            } else bytes = new TextEncoder().encode('{"format":"doorprints-sync/1"}');
            w.server.putByHand({ ...planted(slot, '99'), name: 'x.dpx' }, bytes);
            break;
          }
          case 'expectPlantsKept': expect(w.syncFiles().filter((f) => !f.trashed && f.name === 'x.dpx').length, at).toBe(step['count']); break;
          default: throw new Error(`unknown step ${step.do}`);
        }
      }
    });
  }
});

function check(at: string, r: SyncPassResult, e: Record<string, unknown>): void {
  if (e['kind'] !== undefined) expect(r.kind, `${at} kind`).toBe(e['kind']);
  const report = reportOf(r);
  if (e['wrote'] !== undefined) expect(report!.wrote, `${at} wrote`).toBe(e['wrote']);
  if (e['held'] !== undefined) expect(report!.held, `${at} held`).toBe(e['held']);
  if (e['take'] !== undefined) expect(report!.take.length, `${at} take`).toBe(e['take']);
  if (e['skipped'] !== undefined) expect([...new Set(skippedReasons(r))].sort(), `${at} skipped`).toEqual([...(e['skipped'] as string[])].sort());
  if (r.kind === 'NeedsConfirmation') {
    if (e['housesToDelete'] !== undefined) expect(r.housesToDelete, `${at} housesToDelete`).toBe(e['housesToDelete']);
    if (e['liveHouses'] !== undefined) expect(r.liveHouses, `${at} liveHouses`).toBe(e['liveHouses']);
  }
}
