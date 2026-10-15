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
import { appendTranscript } from './transcript-insert';

/** TC-U-243 (docs/06): where a transcript lands in the box (voice PR 4, S4b-BL-221): after what is there, as plain text. */
describe('appendTranscript', () => {
  it('puts the words in an empty box as they are', () => {
    expect(appendTranscript('', ' two bhk near Baner under 28 thousand ')).toEqual({ text: 'two bhk near Baner under 28 thousand', cut: false });
  });

  it('adds one space after text that does not end in white space, none after text that does', () => {
    expect(appendTranscript('Which flat', 'is cheapest?').text).toBe('Which flat is cheapest?');
    expect(appendTranscript('Which flat ', 'is cheapest?').text).toBe('Which flat is cheapest?');
    expect(appendTranscript('Line one\n', 'line two').text).toBe('Line one\nline two');
  });

  it('adds nothing for empty or blank speech and keeps the box as it was', () => {
    expect(appendTranscript('keep', '   ')).toEqual({ text: 'keep', cut: false });
  });

  it('cuts at the box limit, never touching what was already typed, and says it cut', () => {
    expect(appendTranscript('abcde', 'fghij klmno', 12)).toEqual({ text: 'abcde fghij', cut: true });
    expect(appendTranscript('abcdefghij', 'klm', 4)).toEqual({ text: 'abcdefghij', cut: true });
    expect(appendTranscript('abc', 'def', 7)).toEqual({ text: 'abc def', cut: false });
  });

  it('leaves the words as ordinary text: a number said aloud is not changed or redacted here', () => {
    expect(appendTranscript('', 'call 98450 12345').text).toBe('call 98450 12345');
  });
});
