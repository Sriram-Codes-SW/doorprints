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
import { keepLocal, keepLocalRecord } from './sync-rules';

/**
 * The same cases as `android/shared/.../sync/SyncRulesTest.kt`. If one side ever changes, two devices stop
 * converging, so these expectations must stay identical in both apps.
 */
describe('keepLocal', () => {
  it('keeps a local edit only when it is dirty and strictly newer', () => {
    expect(keepLocal(true, 200, 100)).toBe(true);
    expect(keepLocal(true, 100, 200)).toBe(false);
  });

  it('lets the server win on a tie, so every device converges', () => {
    expect(keepLocal(true, 100, 100)).toBe(false);
  });

  it('never keeps a row that has already been pushed', () => {
    expect(keepLocal(false, 500, 100)).toBe(false);
  });
});

describe('keepLocalRecord', () => {
  const incoming = { updatedAt: '2026-09-10T10:00:00.000Z' };

  it('treats a row this browser does not have as new', () => {
    expect(keepLocalRecord(undefined, incoming)).toBe(false);
    expect(keepLocalRecord(null, incoming)).toBe(false);
  });

  it('compares ISO timestamps as instants', () => {
    expect(keepLocalRecord({ updatedAt: '2026-09-10T15:30:00+05:30', dirty: true }, incoming)).toBe(false);
    expect(keepLocalRecord({ updatedAt: '2026-09-10T16:00:00+05:30', dirty: true }, incoming)).toBe(true);
  });

  it('never keeps a clean local row', () => {
    expect(keepLocalRecord({ updatedAt: '2030-01-01T00:00:00.000Z', dirty: false }, incoming)).toBe(false);
  });

  it('treats a missing local timestamp as the epoch', () => {
    expect(keepLocalRecord({ updatedAt: null, dirty: true }, incoming)).toBe(false);
  });
});
