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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Announcer } from './announcer.service';

/**
 * The polite live region is filled 100 ms after announce(), so a repeated message is a change screen readers
 * read again, and a newer message replaces one that is still pending. cancel(msg) is what a failed save uses so a
 * late "Saving…" is not read after its "Could not save…" alert (web UX gate R9), and it withdraws only the
 * caller's own message: the region is shared by every feature.
 */
describe('Announcer', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('speaks a message 100 ms after announce()', () => {
    const a = new Announcer();
    a.announce({ key: 'house.saving' });
    expect(a.message()).toBeNull();
    vi.advanceTimersByTime(100);
    expect(a.message()).toEqual({ key: 'house.saving' });
  });

  it('replaces a pending message with a newer one, so a fast save is heard only as "Saved"', () => {
    const a = new Announcer();
    a.announce({ key: 'house.saving' });
    a.announce({ key: 'house.saved' });
    vi.advanceTimersByTime(150);
    expect(a.message()).toEqual({ key: 'house.saved' });
  });

  it('cancel(msg) drops its own pending message: a fast failure is not followed by a late "Saving…"', () => {
    const a = new Announcer();
    a.announce({ key: 'house.saving' });
    a.cancel({ key: 'house.saving' });
    vi.advanceTimersByTime(150);
    expect(a.message()).toBeNull();
  });

  it('cancel(msg) after its message was set empties the region', () => {
    const a = new Announcer();
    a.announce({ key: 'house.saving' });
    vi.advanceTimersByTime(100);
    a.cancel({ key: 'house.saving' });
    expect(a.message()).toBeNull();
  });

  it('cancel(msg) leaves a different pending message alone: another feature\'s announcement survives', () => {
    const a = new Announcer();
    a.announce({ key: 'house.saving' });
    // A photo upload finishes inside the save's 100 ms and replaces "Saving…"; then the save fails.
    a.announce({ key: 'house.photoDeleted' });
    a.cancel({ key: 'house.saving' });
    vi.advanceTimersByTime(150);
    expect(a.message()).toEqual({ key: 'house.photoDeleted' });
  });

  it('cancel(msg) leaves a different message on screen alone', () => {
    const a = new Announcer();
    a.announce({ key: 'house.photoDeleted' });
    vi.advanceTimersByTime(100);
    a.cancel({ key: 'house.saving' });
    expect(a.message()).toEqual({ key: 'house.photoDeleted' });
  });
});
