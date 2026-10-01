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
 * The website's accessibility checks that need no browser (Wave D, docs/14 N14): a few rules written for jsdom, in the
 * spirit of axe-core, which is not a dependency (zero cost, no new library). They read the DOM a component rendered
 * and the design tokens of styles.css, and report what a screen reader, a keyboard or a low-vision user would hit:
 * a control without a name, a dialog without a label, a positive tabindex, a duplicate id, an id that an aria
 * attribute points to and that is not there, an image without alt, a skipped heading level, a click handler on an
 * element a keyboard cannot reach, and a colour pair under WCAG 2.2 AA. What needs layout, a real focus ring or a
 * screen reader (target sizes in a real layout, VoiceOver/NVDA reading order, zoom at 200%) is not here: it is in the
 * manual list (docs/06 TC-M).
 */

export interface Violation {
  rule: string;
  message: string;
}

/** A short description of an element for a failure message: `<button class="chip">`. */
export function describe(el: Element): string {
  const cls = el.getAttribute('class');
  const id = el.id ? `#${el.id}` : '';
  return `<${el.tagName.toLowerCase()}${id}${cls ? ` class="${cls}"` : ''}>${(el.textContent ?? '').trim().slice(0, 30)}`;
}

function hiddenFromAt(el: Element): boolean {
  for (let n: Element | null = el; n; n = n.parentElement) {
    if (n.getAttribute('aria-hidden') === 'true' || n.hasAttribute('hidden') || n.getAttribute('inert') !== null) return true;
    // A closed <dialog> is not rendered.
    if (n.tagName === 'DIALOG' && !n.hasAttribute('open')) return true;
  }
  return false;
}

/** The text a screen reader takes from the content: children's text, images' alt, skipping aria-hidden parts. */
function contentText(el: Element): string {
  let out = '';
  for (const n of Array.from(el.childNodes)) {
    if (n.nodeType === 3) out += n.textContent ?? '';
    else if (n.nodeType === 1) {
      const c = n as Element;
      if (c.getAttribute('aria-hidden') === 'true' || c.hasAttribute('hidden')) continue;
      if (c.tagName === 'IMG') out += ` ${c.getAttribute('alt') ?? ''} `;
      else if (c.tagName === 'SVG' || c.tagName === 'svg') out += c.getAttribute('aria-label') ?? c.querySelector('title')?.textContent ?? '';
      else out += ` ${c.getAttribute('aria-label') ?? contentText(c)} `;
    }
  }
  return out;
}

/** A simplified accessible name computation (aria-labelledby, aria-label, label, content, alt, title). Empty = none. */
export function accessibleName(el: Element): string {
  const doc = el.ownerDocument;
  const by = el.getAttribute('aria-labelledby');
  if (by) {
    const text = by
      .split(/\s+/)
      .map((id) => doc.getElementById(id))
      .filter((e): e is HTMLElement => e !== null)
      .map((e) => contentText(e) || e.getAttribute('aria-label') || '')
      .join(' ')
      .trim();
    if (text) return text.replace(/\s+/g, ' ');
  }
  const label = el.getAttribute('aria-label')?.trim();
  if (label) return label;
  const tag = el.tagName.toLowerCase();
  if (['input', 'select', 'textarea', 'meter', 'progress'].includes(tag)) {
    const labels = (el as HTMLInputElement).labels;
    const fromLabels = labels ? Array.from(labels).map((l) => contentText(l)).join(' ').trim() : '';
    if (fromLabels) return fromLabels.replace(/\s+/g, ' ');
    const type = (el.getAttribute('type') ?? '').toLowerCase();
    if (tag === 'input' && ['button', 'submit', 'reset'].includes(type)) return (el.getAttribute('value') ?? '').trim();
    if (tag === 'input' && type === 'image') return (el.getAttribute('alt') ?? '').trim();
  } else if (tag === 'img') {
    return (el.getAttribute('alt') ?? '').trim();
  } else if (tag !== 'dialog' && tag !== 'table') {
    const text = contentText(el).replace(/\s+/g, ' ').trim();
    if (text) return text;
  } else if (tag === 'table') {
    const cap = el.querySelector('caption');
    if (cap) return contentText(cap).trim();
  }
  return (el.getAttribute('title') ?? '').trim();
}

const NAMED_ROLES = new Set([
  'button', 'link', 'tab', 'switch', 'checkbox', 'radio', 'menuitem', 'option', 'combobox', 'textbox', 'listbox', 'slider',
  'spinbutton', 'searchbox', 'progressbar', 'dialog', 'alertdialog', 'img', 'tablist', 'radiogroup',
]);
const FOCUSABLE = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, [tabindex], [contenteditable="true"]';
const ARIA_REFS = ['aria-labelledby', 'aria-describedby', 'aria-controls', 'aria-owns', 'aria-errormessage', 'aria-activedescendant'];
const TRI = ['true', 'false', 'mixed'];
const ENUMS: Record<string, string[]> = {
  'aria-pressed': TRI,
  'aria-checked': TRI,
  'aria-expanded': ['true', 'false'],
  'aria-selected': ['true', 'false'],
  'aria-invalid': ['true', 'false', 'grammar', 'spelling'],
  'aria-live': ['off', 'polite', 'assertive'],
  'aria-current': ['page', 'step', 'location', 'date', 'time', 'true', 'false'],
  'aria-modal': ['true', 'false'],
  'aria-busy': ['true', 'false'],
  'aria-haspopup': ['true', 'false', 'menu', 'listbox', 'tree', 'grid', 'dialog'],
};

/** The rules of the module comment, over `root` (a rendered component, the app shell, a dialog). */
export function audit(root: ParentNode, options: { page?: boolean } = {}): Violation[] {
  const out: Violation[] = [];
  const add = (rule: string, el: Element, message: string) => out.push({ rule, message: `${describe(el)}: ${message}` });
  const all = Array.from(root.querySelectorAll('*'));

  // ids: unique
  const seen = new Map<string, Element>();
  for (const el of all) {
    if (!el.id) continue;
    const first = seen.get(el.id);
    if (first) add('duplicate-id', el, `id "${el.id}" is used twice`);
    else seen.set(el.id, el);
  }
  const doc = (root as Node).ownerDocument ?? (root as Document);
  const inScope = (id: string) => doc.getElementById(id) !== null;

  for (const el of all) {
    const tag = el.tagName.toLowerCase();
    const role = el.getAttribute('role');
    const hidden = hiddenFromAt(el);

    // aria references point at something that exists (a closed dialog is not rendered, so it is not asked)
    for (const attr of hidden ? [] : ARIA_REFS) {
      const v = el.getAttribute(attr);
      if (v === null) continue;
      if (v.trim() === '') add('aria-ref', el, `${attr} is empty`);
      else for (const id of v.split(/\s+/)) if (!inScope(id)) add('aria-ref', el, `${attr} points at "${id}", which is not in the page`);
    }
    for (const [attr, allowed] of Object.entries(ENUMS)) {
      const v = el.getAttribute(attr);
      if (v !== null && !allowed.includes(v)) add('aria-value', el, `${attr}="${v}" is not one of ${allowed.join(', ')}`);
    }
    const ti = el.getAttribute('tabindex');
    if (ti !== null && Number(ti) > 0) add('tabindex', el, `positive tabindex ${ti} breaks the tab order`);
    const lang = el.getAttribute('lang');
    if (lang !== null && !['en', 'hi', 'ta', 'te'].includes(lang)) add('lang', el, `lang="${lang}" is not a language of the app`);

    if (tag === 'label' && el.hasAttribute('for') && !inScope(el.getAttribute('for') ?? '')) {
      add('label-for', el, `for="${el.getAttribute('for')}" points at nothing`);
    }
    if (tag === 'img' && !el.hasAttribute('alt') && role !== 'presentation' && role !== 'none') add('img-alt', el, 'image without an alt attribute');
    if ((tag === 'svg' && role === 'img') || (tag === 'img' && el.getAttribute('alt') === '' && el.hasAttribute('title'))) {
      if (tag === 'svg' && !accessibleName(el)) add('img-alt', el, 'svg role=img without a name');
    }
    if (hidden) {
      continue;
    }

    // names
    const needsName =
      tag === 'button' ||
      tag === 'select' ||
      tag === 'textarea' ||
      tag === 'summary' ||
      (tag === 'a' && el.hasAttribute('href')) ||
      (tag === 'input' && !['hidden'].includes((el.getAttribute('type') ?? '').toLowerCase())) ||
      (role !== null && NAMED_ROLES.has(role)) ||
      tag === 'dialog' ||
      /^h[1-6]$/.test(tag);
    if (needsName && role !== 'presentation' && role !== 'none') {
      const isImgRole = role === 'img';
      if (!accessibleName(el) && !(isImgRole && el.getAttribute('aria-hidden') === 'true')) {
        add(tag === 'dialog' || role === 'dialog' || role === 'alertdialog' ? 'dialog-name' : /^h[1-6]$/.test(tag) ? 'empty-heading' : 'name', el, 'no accessible name');
      }
    }
    // Label in Name (WCAG 2.5.3): a control whose aria-label replaces visible words must still contain them, so a
    // person who says what they see ("click Call") reaches it.
    const aria = el.getAttribute('aria-label');
    if (aria && ['button', 'a'].includes(tag) && !el.hasAttribute('aria-labelledby')) {
      const visible = contentText(el).replace(/\s+/g, ' ').trim().toLowerCase();
      if (/\p{L}/u.test(visible) && !aria.toLowerCase().includes(visible)) {
        add('label-in-name', el, `aria-label "${aria}" does not contain the visible text "${visible}"`);
      }
    }
    if (role === 'dialog' || role === 'alertdialog') {
      if (el.getAttribute('aria-modal') !== 'true') add('dialog-modal', el, 'role=dialog without aria-modal="true"');
    }
    if (role === 'tab' && !el.hasAttribute('aria-selected')) add('tab-state', el, 'role=tab without aria-selected');
    if ((role === 'switch' || role === 'checkbox' || role === 'radio') && tag !== 'input' && !el.hasAttribute('aria-checked')) {
      add('toggle-state', el, `role=${role} without aria-checked`);
    }
    if (el.getAttribute('aria-invalid') === 'true' && !el.hasAttribute('aria-describedby') && !el.hasAttribute('aria-errormessage')) {
      add('error-association', el, 'aria-invalid without aria-describedby pointing at the error text');
    }
    if (tag === 'a' && !el.hasAttribute('href') && role !== 'link' && !el.hasAttribute('routerlink')) {
      add('link-href', el, 'a without href is not keyboard reachable');
    }
    if (tag === 'table' && el.querySelector('th') === null) add('table-header', el, 'table without header cells');
    if (ti === '-1' && ['button', 'a', 'input', 'select', 'textarea'].includes(tag) && !el.closest('[role="dialog"], dialog')) {
      // A control taken out of the tab order must have another keyboard route; reported for a human to judge.
    }
  }

  // an error or a field error is announced: it sits in a live region or is the description of a field (WCAG 3.3.1, 4.1.3)
  const described = new Set<string>();
  for (const el of all) {
    for (const attr of ['aria-describedby', 'aria-errormessage']) (el.getAttribute(attr) ?? '').split(/\s+/).forEach((id) => id && described.add(id));
  }
  for (const err of Array.from(root.querySelectorAll('.error, .field-error'))) {
    if (hiddenFromAt(err)) continue;
    if (err.closest('[role="alert"], [role="status"], [aria-live]') !== null) continue;
    if (err.querySelector('[role="alert"], [role="status"], [aria-live]') !== null) continue;
    if (err.id && described.has(err.id)) continue;
    add('error-announced', err, 'an error that no live region announces and no field is described by');
  }

  // aria-hidden that holds a focusable control: a keyboard user lands on something a screen reader hides
  for (const hidden of Array.from(root.querySelectorAll('[aria-hidden="true"]'))) {
    for (const f of Array.from(hidden.querySelectorAll(FOCUSABLE))) {
      if (f.getAttribute('tabindex') === '-1' || (f as HTMLButtonElement).disabled) continue;
      add('aria-hidden-focusable', f, 'is focusable inside aria-hidden');
    }
    if (hidden.matches(FOCUSABLE) && hidden.getAttribute('tabindex') !== '-1' && !(hidden as HTMLButtonElement).disabled) {
      add('aria-hidden-focusable', hidden, 'is focusable and aria-hidden');
    }
  }

  // headings: no skipped level, one h1 at most
  const headings = Array.from(root.querySelectorAll('h1, h2, h3, h4, h5, h6, [role="heading"]')).filter((h) => !hiddenFromAt(h));
  let prev = 0;
  for (const h of headings) {
    const level = h.hasAttribute('aria-level') ? Number(h.getAttribute('aria-level')) : Number(h.tagName.slice(1));
    if (prev > 0 && level > prev + 1) add('heading-order', h, `h${level} follows h${prev}`);
    prev = level;
  }
  if (options.page) {
    const h1s = headings.filter((h) => h.tagName === 'H1');
    if (h1s.length !== 1) out.push({ rule: 'h1', message: `a page has ${h1s.length} h1 headings, expected 1` });
  }

  // landmarks
  const mains = Array.from(root.querySelectorAll('main, [role="main"]'));
  if (mains.length > 1) out.push({ rule: 'landmark-main', message: `${mains.length} main landmarks` });
  const navs = Array.from(root.querySelectorAll('nav, [role="navigation"]')).filter((n) => !hiddenFromAt(n));
  const navNames = navs.map((n) => accessibleName(n));
  if (navs.length > 1 && new Set(navNames).size !== navNames.length) out.push({ rule: 'landmark-name', message: 'two nav landmarks share one name' });
  if (navs.length > 1) for (const n of navs) if (!accessibleName(n)) add('landmark-name', n, 'one of several navs without a label');
  return out;
}

/** Fails (with every violation listed) when {@link audit} finds any. */
export function expectNoViolations(root: ParentNode, options: { page?: boolean } = {}): void {
  const v = audit(root, options);
  if (v.length > 0) throw new Error(`${v.length} accessibility violation(s):\n${v.map((x) => `  [${x.rule}] ${x.message}`).join('\n')}`);
}

/**
 * Records every element a `click` (or `mousedown`/`pointerdown`) listener is added to while a component renders, so
 * {@link mouseOnly} can name the ones a keyboard cannot operate: a handler on a `div` or `span` with no role and no
 * tabindex. A `<dialog>`'s own click (the backdrop) is allowed: Esc and a Close button are the keyboard route.
 */
export function watchClickListeners(): { stop: () => Element[] } {
  const original = EventTarget.prototype.addEventListener;
  const targets = new Set<Element>();
  EventTarget.prototype.addEventListener = function (this: EventTarget, type: string, ...rest: unknown[]) {
    if (['click', 'mousedown', 'pointerdown', 'mouseup', 'dblclick'].includes(type) && this instanceof Element) targets.add(this);
    return (original as (...a: unknown[]) => void).call(this, type, ...rest);
  } as typeof original;
  return {
    stop: () => {
      EventTarget.prototype.addEventListener = original;
      return mouseOnly(Array.from(targets));
    },
  };
}

const NATIVE = new Set(['a', 'button', 'input', 'select', 'textarea', 'summary', 'label', 'option', 'dialog', 'form', 'details']);

/** The elements among `els` that have a pointer handler and no keyboard route of their own. */
export function mouseOnly(els: Element[]): Element[] {
  return els.filter((el) => {
    if (!el.isConnected) return false;
    const tag = el.tagName.toLowerCase();
    if (NATIVE.has(tag) || tag === 'body' || tag === 'html' || tag === 'app-root' || tag === 'main') return false;
    if (el.getAttribute('role') && el.hasAttribute('tabindex')) return false;
    // An element inside a native control (a span in a button) bubbles up to it.
    return el.closest('a[href], button, label, summary') === null;
  });
}

// ---------- Colour contrast (WCAG 2.2 SC 1.4.3 and 1.4.11) on the tokens of styles.css ----------

export type Rgba = [number, number, number, number];

export function parseColor(value: string): Rgba {
  const v = value.trim().toLowerCase();
  let m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/.exec(v);
  if (m) {
    const h = m[1].length === 3 ? m[1].replace(/./g, (c) => c + c) : m[1];
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1];
  }
  m = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:[\s,/]+([\d.]+))?\s*\)$/.exec(v);
  if (m) return [Number(m[1]), Number(m[2]), Number(m[3]), m[4] === undefined ? 1 : Number(m[4])];
  throw new Error(`cannot read the colour "${value}"`);
}

/** `top` laid over the opaque `bottom`. */
export function over(top: Rgba, bottom: Rgba): Rgba {
  const a = top[3];
  return [0, 1, 2].map((i) => top[i] * a + bottom[i] * (1 - a)).concat(1) as Rgba;
}

export function luminance([r, g, b]: Rgba): number {
  const f = (c: number) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

/** The WCAG contrast ratio of two opaque colours. */
export function contrastRatio(a: Rgba, b: Rgba): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

/** The text between the braces that follow `start` in `css` (nested braces counted), or null. */
function block(css: string, start: number): string | null {
  const open = css.indexOf('{', start);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < css.length; i++) {
    if (css[i] === '{') depth++;
    else if (css[i] === '}' && --depth === 0) return css.slice(open + 1, i);
  }
  return null;
}

function declarations(body: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of body.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) out[m[1]] = m[2].trim();
  return out;
}

/** The design tokens of `:root` in styles.css, light, and with the `prefers-color-scheme: dark` overrides applied, dark. */
export function themeTokens(css: string, theme: 'light' | 'dark'): Record<string, string> {
  const rootAt = css.indexOf(':root {');
  const light = declarations(block(css, rootAt) ?? '');
  if (theme === 'light') return light;
  const mediaAt = css.indexOf('@media (prefers-color-scheme: dark)');
  const media = block(css, mediaAt) ?? '';
  return { ...light, ...declarations(block(media, media.indexOf(':root')) ?? '') };
}

/** Reads one token as an opaque colour over `backdrop` (translucent tokens such as --overlay are composited). */
export function tokenColor(tokens: Record<string, string>, name: string, backdrop?: Rgba): Rgba {
  const raw = tokens[name];
  if (raw === undefined) throw new Error(`no token ${name}`);
  const c = parseColor(raw);
  return c[3] < 1 ? over(c, backdrop ?? [255, 255, 255, 1]) : c;
}
