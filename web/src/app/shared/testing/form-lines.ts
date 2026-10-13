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

// How the form golden files read the candidate page (kinds PR 4 and 5: house-form.golden.json, S4b-BL-208, and
// school-form.golden.json, S4b-BL-209): one line per control of the Details and Cost cards with its element, id,
// attributes, place in the rows and label; the element tree of those cards; every section heading. Moved here from
// house-form-fields.spec.ts unchanged, so both golden files are read the same way.

export const ATTRS = ['type', 'min', 'max', 'step', 'inputmode', 'maxlength', 'role', 'required', 'readonly', 'autocomplete', 'placeholder', 'rows', 'name', 'aria-label', 'aria-describedby', 'aria-invalid'];
export const text = (el: Element | null | undefined): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

/** Where a control sits under its card: the classes of the elements between them, outermost first. */
function place(el: Element, card: Element): string {
  const chain: string[] = [];
  for (let p = el.parentElement; p && p !== card; p = p.parentElement) {
    const at = p.parentElement ? [...p.parentElement.children].indexOf(p) : 0;
    chain.unshift(`${p.tagName.toLowerCase()}.${[...p.classList].join('.')}[${at}]`);
  }
  return chain.join('>');
}

/** The name a control is given on screen: its `<label for>`, else the label around it, else its fieldset's legend. */
function labelOf(el: Element, host: HTMLElement): string {
  const id = el.getAttribute('id');
  const forLabel = id ? host.querySelector(`label[for="${id}"]`) : null;
  if (forLabel) return text(forLabel);
  const around = el.closest('label');
  if (around) return `${text(el.closest('fieldset')?.querySelector('legend'))} / ${text(around)}`;
  return `aria-label=${el.getAttribute('aria-label') ?? ''}`;
}

/** One line per control of the Details and Cost cards, in page order. */
export function controls(host: HTMLElement): string[] {
  const lines: string[] = [];
  for (const card of ['details-heading', 'cost-heading']) {
    const section = host.querySelector(`section[aria-labelledby="${card}"]`);
    if (!section) continue;
    for (const el of section.querySelectorAll('input, select, textarea')) {
      const attrs = ATTRS.filter((a) => el.hasAttribute(a)).map((a) => `${a}=${el.getAttribute(a)}`).join(' ');
      const options = el instanceof HTMLSelectElement ? ` [${[...el.options].map((o) => text(o)).join('|')}]` : '';
      lines.push(`${card.replace('-heading', '')} ${el.tagName.toLowerCase()}#${el.id || '-'} ${attrs} @${place(el, section)} "${labelOf(el, host)}"${options}`);
    }
  }
  return lines;
}

/** The element tree of the Details and Cost cards: every element's depth, tag, id, classes, role and aria-* names. */
export function structure(host: HTMLElement): string[] {
  const lines: string[] = [];
  const walk = (el: Element, depth: number): void => {
    const aria = [...el.attributes].filter((a) => a.name === 'role' || a.name.startsWith('aria-')).map((a) => `${a.name}=${a.value}`);
    lines.push(`${depth} ${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${[...el.classList].map((c) => '.' + c).join('')} ${aria.join(' ')}`.trim());
    for (const child of el.children) walk(child, depth + 1);
  };
  for (const card of ['details-heading', 'cost-heading']) {
    const section = host.querySelector(`section[aria-labelledby="${card}"]`);
    if (section) walk(section, 0);
  }
  return lines;
}

/** Every section heading of the page, in page order, with the id it is named by. */
export function headings(host: HTMLElement): string[] {
  return [...host.querySelectorAll('h2')].map((h) => `${h.id || '-'} "${text(h)}"`);
}

/** A control line split into its shape (the same in every language) and its words (label, options, placeholder). */
export function split(line: string): [string, string] {
  const m = / "(.*)"( \[.*\])?$/.exec(line)!;
  const head = line.slice(0, m.index);
  const ph = / placeholder=(.*?) @/.exec(head);
  return [head.replace(/ placeholder=(.*?) @/, ' placeholder @'), m[1] + (m[2] ?? '') + (ph ? ` placeholder=${ph[1]}` : '')];
}
