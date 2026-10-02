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
import vectorsJson from '../../../../../docs/schemas/delete-vectors.json';
import {
  authorizationProblem, classifyFile, classifyFolder, operationId, phaseOf, selectBackups, stopsRun,
} from './drive-deletion-rules';
import type { AuthorizationToken, BackupRef, DeletionAction, DeletionLevel, ItemKind } from './drive-deletion-rules';
import type { DriveErrorKind } from './drive-client';

/** The shared deletion vectors (`docs/schemas/delete-vectors.json`, README section 6.3): the same rules as Kotlin's `DeletionVectorsTest`. */

type Props = Record<string, string>;
const vectors = vectorsJson as unknown as {
  format: string;
  classifyFile: { name: string; props: Props; container: string; item: string | null }[];
  classifyFolder: { name: string; props: Props; container: string; role: string | null }[];
  phases: { kind: ItemKind; folderRole: string | null; phase: number }[];
  selectBackups: { name: string; action: DeletionAction; backups: BackupRef[]; ids: string[] | null; level: DeletionLevel }[];
  operationIds: { level: DeletionLevel; rootId: string; ids: string[]; operationId: string }[];
  authorization: {
    name: string; required: DeletionLevel; token: Omit<AuthorizationToken, 'proof'> | null; operationId: string; nowMs: number; problem: string | null;
  }[];
  stopsRun: { kind: DriveErrorKind; stops: boolean }[];
};

describe('deletion vectors (shared with Kotlin)', () => {
  it('reads the vectors file it was written for', () => {
    expect(vectors.format).toBe('doorprints-delete-vectors/1');
  });

  it('counts a file as ours only by kind and folder', () => {
    for (const c of vectors.classifyFile) expect(classifyFile(c.props, c.container), c.name).toBe(c.item);
  });

  it('counts a folder as ours only under the root with a known role', () => {
    for (const c of vectors.classifyFolder) expect(classifyFolder(c.props, c.container), c.name).toBe(c.role);
  });

  it('orders data, then keys, then folders', () => {
    for (const c of vectors.phases) expect(phaseOf(c.kind, c.folderRole), JSON.stringify(c)).toBe(c.phase);
  });

  it('selects backups oldest first with their level', () => {
    for (const c of vectors.selectBackups) {
      const got = selectBackups(c.action, c.backups);
      expect(got.ids, c.name).toEqual(c.ids);
      expect(got.level, c.name).toBe(c.level);
    }
  });

  it('makes the same operation ids', () => {
    for (const c of vectors.operationIds) expect(operationId(c.level, c.rootId, c.ids)).toBe(c.operationId);
  });

  it('checks authorization in the same order', () => {
    for (const c of vectors.authorization) {
      const token = c.token ? { ...c.token, proof: 'proof' } : null;
      expect(authorizationProblem(token, c.required, c.operationId, c.nowMs), c.name).toBe(c.problem);
    }
  });

  it('stops a run only for some failures', () => {
    for (const c of vectors.stopsRun) expect(stopsRun(c.kind), c.kind).toBe(c.stops);
  });
});
