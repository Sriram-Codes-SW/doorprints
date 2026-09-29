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

import { afterEach, describe, expect, it } from 'vitest';
import { focusIfLost } from './focus';

/** Ask, Plan and Connect bring focus back to their main button only when the focused control was removed. */
describe('focusIfLost', () => {
  afterEach(() => document.body.replaceChildren());

  function buttons(): [HTMLButtonElement, HTMLButtonElement] {
    const target = document.createElement('button');
    target.id = 'target';
    const other = document.createElement('button');
    document.body.append(target, other);
    return [target, other];
  }

  it('focuses the element when focus is on <body>', () => {
    const [target] = buttons();
    (document.activeElement as HTMLElement | null)?.blur();
    expect(document.activeElement).toBe(document.body);
    focusIfLost('target');
    expect(document.activeElement).toBe(target);
  });

  it('leaves focus the user moved elsewhere alone', () => {
    const [, other] = buttons();
    other.focus();
    focusIfLost('target');
    expect(document.activeElement).toBe(other);
  });

  it('does nothing when the element is not there', () => {
    (document.activeElement as HTMLElement | null)?.blur();
    expect(() => focusIfLost('missing')).not.toThrow();
    expect(document.activeElement).toBe(document.body);
  });
});
