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

/**
 * The ring that marks the place of *Have I been here?* on a map (docs/11 5.27.13, docs/03 section 6.2b): a hollow ring with a
 * centre cross, 28 px, with the label *You are here* / *This house* / *This spot* under it. A form, not a colour: a house
 * marker is a filled dot. A DOM element for a MapLibre `Marker`; it lets every pointer through. The tiles stay light in both
 * themes, so its colours do not change with the theme.
 */

const NS = 'http://www.w3.org/2000/svg';

export function ringElement(label: string): HTMLElement {
  const el = document.createElement('div');
  el.setAttribute('role', 'img');
  el.setAttribute('aria-label', label);
  el.style.cssText = 'width:28px;height:28px;position:relative;pointer-events:none;';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('viewBox', '0 0 28 28');
  svg.setAttribute('width', '28');
  svg.setAttribute('height', '28');
  svg.setAttribute('aria-hidden', 'true');
  // A white casing under a dark stroke keeps the ring readable on any tile.
  for (const [stroke, width] of [['#FFFFFF', 5], ['#4A148C', 2.5]] as const) {
    const circle = document.createElementNS(NS, 'circle');
    circle.setAttribute('cx', '14');
    circle.setAttribute('cy', '14');
    circle.setAttribute('r', '10');
    circle.setAttribute('fill', 'none');
    circle.setAttribute('stroke', stroke);
    circle.setAttribute('stroke-width', String(width));
    svg.appendChild(circle);
    for (const [x1, y1, x2, y2] of [[14, 8, 14, 20], [8, 14, 20, 14]]) {
      const line = document.createElementNS(NS, 'line');
      line.setAttribute('x1', String(x1));
      line.setAttribute('y1', String(y1));
      line.setAttribute('x2', String(x2));
      line.setAttribute('y2', String(y2));
      line.setAttribute('stroke', stroke);
      line.setAttribute('stroke-width', String(width === 5 ? 4 : 2));
      svg.appendChild(line);
    }
  }
  const text = document.createElement('span');
  text.className = 'ring-label';
  text.textContent = label;
  text.setAttribute('aria-hidden', 'true');
  text.style.cssText =
    'position:absolute;top:100%;left:50%;transform:translateX(-50%);margin-top:2px;padding:1px 6px;border-radius:6px;' +
    'background:rgba(255,255,255,0.94);color:#1c2421;font:600 12px/1.4 system-ui,sans-serif;white-space:nowrap;box-shadow:0 1px 3px rgba(0,0,0,0.25);';
  el.append(svg, text);
  return el;
}
