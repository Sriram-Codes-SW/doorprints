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
import styles from '../../../styles.css' with { loader: 'text' };
import { STATUS_COLOR } from '../../core/models';
import { HOUSE_PAINT } from '../../pages/map/house-markers';
import { contrastRatio, over, parseColor, themeTokens, tokenColor } from './a11y';
import type { Rgba } from './a11y';

/**
 * WCAG 2.2 contrast (SC 1.4.3 text 4.5:1, SC 1.4.11 controls and graphics 3:1) of every foreground and background pair
 * the stylesheets use, computed from the tokens of `src/styles.css` in both themes (Wave D). A colour changed there
 * that drops a pair under its minimum fails here, with the pair and its ratio.
 */
const STATUS = ['status-new', 'status-shortlisted', 'status-rejected', 'status-taken', 'status-not-chosen'];
const SURFACES = ['bg', 'surface', 'surface-2'];

/** [foreground, background, minimum, where it is used] */
type Pair = [string, string, number, string];

const TEXT = 4.5;
const UI = 3;

const PAIRS: Pair[] = [
  ...SURFACES.flatMap((s): Pair[] => [
    ['text', s, TEXT, 'body text'],
    ['muted', s, TEXT, 'hints, captions, empty states'],
    ['primary', s, TEXT, 'links'],
    ['error-text', s, TEXT, 'field errors'],
    ['success-text', s, TEXT, 'saved'],
    ['warn-text', s, TEXT, 'caution text'],
    ['star', s, UI, 'rating stars (a glyph, not text)'],
    ['focus', s, UI, 'focus ring'],
    ['border-strong', s, UI, 'form control borders'],
    ['primary', s, UI, 'selected borders, icons'],
  ]),
  ['text', 'primary-soft', TEXT, 'selected chip'],
  ['muted', 'primary-soft', TEXT, 'hints in a tinted card'],
  ['primary', 'primary-soft', TEXT, 'link or glyph in a tinted card'],
  ['on-primary', 'primary', TEXT, 'primary button, selected option'],
  ['on-primary', 'primary-dark', TEXT, 'primary button hovered'],
  ['on-header', 'header-bg', TEXT, 'the header'],
  ['on-header-alert', 'header-bg', UI, 'sync-problem mark on the header'],
  ['focus', 'primary-soft', UI, 'focus ring on a selected chip'],
  ['error-text', 'error-bg', TEXT, 'error box'],
  ['success-text', 'success-bg', TEXT, 'success box'],
  ['warn-text', 'warn-bg', TEXT, 'caution box'],
  ['on-best', 'best', TEXT, 'best-score cell'],
  ['text', 'best', TEXT, 'best-score cell text'],
  ['error-text', 'error-bg', UI, 'error box glyph'],
  ...STATUS.flatMap((s): Pair[] => [
    ['on-status', s, TEXT, 'status pill and selected status option'],
    [s, 'surface', TEXT, 'status as text'],
    [s, 'bg', TEXT, 'status as text'],
  ]),
];

for (const theme of ['light', 'dark'] as const) {
  describe(`design tokens, ${theme} theme (TC-U-WEB-A11Y-1)`, () => {
    const tokens = themeTokens(styles, theme);
    const page = tokenColor(tokens, '--bg');
    const solid = (name: string, backdrop: Rgba = page) => tokenColor(tokens, name, backdrop);

    it('reads the tokens it needs', () => {
      expect(Object.keys(tokens).length).toBeGreaterThan(40);
      expect(tokens['--text']).toBeDefined();
      expect(tokens['--bg']).toBe(theme === 'dark' ? '#101614' : '#f4f6f5');
    });

    it('every foreground and background pair of the stylesheets meets WCAG 2.2 AA', () => {
      const failures: string[] = [];
      for (const [fg, bg, min, use] of PAIRS) {
        const ratio = contrastRatio(solid(`--${fg}`, solid(`--${bg}`)), solid(`--${bg}`));
        if (ratio < min) failures.push(`${fg} on ${bg} (${use}): ${ratio.toFixed(2)}:1, needs ${min}:1`);
      }
      expect(failures).toEqual([]);
    });

    it('the translucent overlays keep text readable over the page', () => {
      const overlay = over(
        [...(tokens['--overlay'].match(/[\d.]+/g) ?? []).map(Number)] as unknown as Rgba,
        solid('--bg'),
      );
      expect(contrastRatio(solid('--text'), overlay)).toBeGreaterThanOrEqual(TEXT);
      expect(contrastRatio(solid('--muted'), overlay)).toBeGreaterThanOrEqual(TEXT);
    });

    it('the header navigation keeps white text readable on its hover and current backgrounds', () => {
      const header = solid('--header-bg');
      const white = solid('--on-header', header);
      for (const a of [0.15, 0.25]) {
        expect(contrastRatio(white, over([0, 0, 0, a], header))).toBeGreaterThanOrEqual(TEXT);
      }
    });
  });
}

describe('the contrast function (TC-U-WEB-A11Y-2)', () => {
  it('matches the WCAG reference values', () => {
    expect(contrastRatio([0, 0, 0, 1], [255, 255, 255, 1])).toBeCloseTo(21, 5);
    expect(contrastRatio([119, 119, 119, 1], [255, 255, 255, 1])).toBeCloseTo(4.48, 2);
    expect(contrastRatio([255, 255, 255, 1], [255, 255, 255, 1])).toBeCloseTo(1, 5);
  });

  it('reads the light and the dark overrides of :root', () => {
    expect(themeTokens(styles, 'light')['--primary']).toBe('#1f6f5c');
    expect(themeTokens(styles, 'dark')['--primary']).toBe('#6fd1b3');
    // Tokens a theme does not override keep their light value.
    expect(themeTokens(styles, 'dark')['--target']).toBe('44px');
  });
});

describe('the map markers (TC-U-WEB-A11Y-4)', () => {
  /** The marker's fill opacity by status, read from the paint the map uses. */
  const opacity = (status: string): number => {
    const match = (HOUSE_PAINT['circle-opacity'] as readonly unknown[])[3] as readonly unknown[];
    const at = match.indexOf(status);
    return at < 0 ? (match[match.length - 1] as number) : (match[at + 1] as number);
  };

  it('every status marker, faded as drawn, stays 3:1 against its white ring and against the light map tiles', () => {
    for (const [status, colour] of Object.entries(STATUS_COLOR)) {
      const drawn = (backdrop: string) => over([...parseColor(colour).slice(0, 3), opacity(status)] as Rgba, parseColor(backdrop));
      expect(contrastRatio(drawn('#ffffff'), parseColor('#ffffff')), `${status} on white`).toBeGreaterThanOrEqual(3);
      // The Liberty land colour; a marker on water or a park is helped by the ring.
      expect(contrastRatio(drawn('#f8f4f0'), parseColor('#f8f4f0')), `${status} on land`).toBeGreaterThanOrEqual(3);
    }
  });
});
