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
import { ensureMapStyles, foldAttribution, relabelBoundaryCredit } from './map-style';

/** A map container with MapLibre's credits control in the given state, `width` px wide. */
function mapRoot(width: number, classes: string): HTMLElement {
  const root = document.createElement('div');
  Object.defineProperty(root, 'offsetWidth', { value: width });
  const credits = document.createElement('details');
  credits.className = `maplibregl-ctrl maplibregl-ctrl-attrib ${classes}`;
  root.append(credits);
  return root;
}

const creditsOf = (root: HTMLElement) => root.querySelector('.maplibregl-ctrl-attrib')!;

describe('foldAttribution (owner report 2026-09-24: credits open over a phone map for good)', () => {
  it('folds open compact credits on a phone-width map into the (i) button', () => {
    const root = mapRoot(384, 'maplibregl-compact maplibregl-compact-show');
    expect(foldAttribution(root)).toBe(true);
    expect(creditsOf(root).classList).toContain('maplibregl-compact');
    expect(creditsOf(root).classList).not.toContain('maplibregl-compact-show');
  });

  it('folds at MapLibre own compact width, 640px, and leaves a wider (desktop) map as it is', () => {
    expect(foldAttribution(mapRoot(640, 'maplibregl-compact maplibregl-compact-show'))).toBe(true);
    const wide = mapRoot(641, 'maplibregl-compact maplibregl-compact-show');
    expect(foldAttribution(wide)).toBe(false);
    expect(creditsOf(wide).classList).toContain('maplibregl-compact-show');
  });

  it('does nothing when the credits are already folded, or not compact', () => {
    expect(foldAttribution(mapRoot(360, 'maplibregl-compact'))).toBe(false);
    const full = mapRoot(360, '');
    expect(foldAttribution(full)).toBe(false);
    expect(creditsOf(full).className).toBe('maplibregl-ctrl maplibregl-ctrl-attrib ');
  });

  it('does nothing on a map with no credits control (map unavailable)', () => {
    expect(foldAttribution(document.createElement('div'))).toBe(false);
  });
});

describe('ensureMapStyles (MapLibre CSS out of the render-blocking stylesheet)', () => {
  it('adds maplibre.css once, and hides the controls only until it has loaded', () => {
    const doc = document.implementation.createHTMLDocument('t');
    const base = doc.createElement('base');
    base.href = 'https://doorprints.example/';
    doc.head.append(base);
    ensureMapStyles(doc);
    ensureMapStyles(doc);
    const links = doc.querySelectorAll<HTMLLinkElement>('link[data-maplibre-css]');
    expect(links).toHaveLength(1);
    expect(links[0].rel).toBe('stylesheet');
    expect(links[0].href).toBe('https://doorprints.example/maplibre.css');
    expect(doc.documentElement.classList.contains('maplibre-css-loading')).toBe(true);
    links[0].dispatchEvent(new Event('load'));
    expect(doc.documentElement.classList.contains('maplibre-css-loading')).toBe(false);
  });
});

describe('relabelBoundaryCredit (the Survey of India credit after a language switch, S4b-BL-114)', () => {
  /** A map with the Survey of India's source (or none) and an attribution control that counts its rereads. */
  function fakeMap(withSource: boolean) {
    const source = { attribution: 'Boundary: Survey of India' };
    const control = { rereads: 0, _updateAttributions() { this.rereads++; } };
    const map = {
      getSource: (id: string) => (withSource && id === 'in-boundaries-soi' ? source : undefined),
      _controls: [{}, control],
    };
    return { map: map as unknown as Parameters<typeof relabelBoundaryCredit>[0], source, control };
  }

  it('sets the source\'s credit and has the attribution control read it again', () => {
    const { map, source, control } = fakeMap(true);
    expect(relabelBoundaryCredit(map, 'सीमा: भारतीय सर्वेक्षण विभाग')).toBe(true);
    expect(source.attribution).toBe('सीमा: भारतीय सर्वेक्षण विभाग');
    expect(control.rereads).toBe(1);
    // The same text again changes nothing.
    expect(relabelBoundaryCredit(map, 'सीमा: भारतीय सर्वेक्षण विभाग')).toBe(false);
    expect(control.rereads).toBe(1);
  });

  it('leaves a map without the source alone (the next style load sets the credit)', () => {
    const { map, control } = fakeMap(false);
    expect(relabelBoundaryCredit(map, 'x')).toBe(false);
    expect(control.rereads).toBe(0);
  });
});
