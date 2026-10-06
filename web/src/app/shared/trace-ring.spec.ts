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
import { ringElement } from './trace-ring';

describe('the ring at the place of a check', () => {
  it('is a hollow ring with a centre cross (a form no house marker has), named for a screen reader, with the label in view', () => {
    const el = ringElement('This house');
    expect(el.getAttribute('role')).toBe('img');
    expect(el.getAttribute('aria-label')).toBe('This house');
    const circles = [...el.querySelectorAll('svg circle')];
    expect(circles.map((c) => c.getAttribute('fill'))).toEqual(['none', 'none']);
    // The cross: a horizontal and a vertical stroke, each with its white casing.
    expect(el.querySelectorAll('svg line')).toHaveLength(4);
    expect(el.querySelector('.ring-label')!.textContent).toBe('This house');
  });

  it('lets every pointer and key through (the marker must not catch a tap meant for the map)', () => {
    expect(ringElement('x').style.pointerEvents).toBe('none');
  });

  it('is 28 px, as docs/03 section 6.2b says', () => {
    const el = ringElement('x');
    expect(el.style.width).toBe('28px');
    expect(el.style.height).toBe('28px');
  });

  it('puts the label in text, never in markup (a house name or a language string cannot inject HTML)', () => {
    const el = ringElement('<img src=x onerror=alert(1)>');
    expect(el.querySelector('img')).toBeNull();
    expect(el.querySelector('.ring-label')!.textContent).toBe('<img src=x onerror=alert(1)>');
  });
});
