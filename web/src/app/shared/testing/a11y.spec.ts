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

import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { accessibleName, audit, expectNoViolations, watchClickListeners } from './a11y';

/** The helper's own checks (TC-U-WEB-A11Y-5): each rule fires on a bad fragment and stays quiet on a good one. */
const render = (html: string): HTMLElement => {
  const host = document.createElement('div');
  host.innerHTML = html;
  document.body.appendChild(host);
  return host;
};
const rules = (html: string, options: { page?: boolean } = {}): string[] => {
  const host = render(html);
  try {
    return audit(host, options).map((v) => v.rule);
  } finally {
    host.remove();
  }
};

describe('the accessibility audit', () => {
  it('names a button from its text, aria-label, aria-labelledby, an image alt and a label, and flags one with none', () => {
    expect(rules('<button>Save</button><button aria-label="Close"></button><span id="x">Add</span><button aria-labelledby="x"></button>')).toEqual([]);
    expect(rules('<button><img src="a.png" alt="Delete"></button><label for="n">Name</label><input id="n"><label>Phone <input></label>')).toEqual([]);
    expect(rules('<button></button>')).toEqual(['name']);
    expect(rules('<button><span aria-hidden="true">x</span></button>')).toEqual(['name']);
    expect(rules('<input id="n">')).toEqual(['name']);
    expect(rules('<select></select><textarea></textarea><a href="/x"></a>')).toEqual(['name', 'name', 'name']);
    expect(rules('<div role="tab" aria-selected="false"></div>')).toEqual(['name']);
  });

  it('wants a labelled dialog, and aria-modal on a role=dialog; a closed dialog is not asked', () => {
    expect(rules('<dialog open aria-labelledby="t"><h2 id="t">Sure?</h2></dialog>', {})).toEqual([]);
    expect(rules('<dialog open><p>Sure?</p></dialog>')).toEqual(['dialog-name']);
    expect(rules('<div role="dialog" aria-label="Edit"></div>')).toEqual(['dialog-modal']);
    expect(rules('<div role="dialog" aria-modal="true" aria-label="Edit"></div>')).toEqual([]);
    expect(rules('<dialog aria-labelledby="gone"></dialog>')).toEqual([]);
  });

  it('flags a positive tabindex, a duplicate id, an aria reference to nothing, an image with no alt, a bad lang', () => {
    expect(rules('<button tabindex="3">a</button>')).toEqual(['tabindex']);
    expect(rules('<p id="a"></p><p id="a"></p>')).toEqual(['duplicate-id']);
    expect(rules('<input aria-label="x" aria-describedby="nowhere">')).toEqual(['aria-ref']);
    expect(rules('<img src="a.png">')).toEqual(['img-alt']);
    expect(rules('<img src="a.png" alt="">')).toEqual([]);
    expect(rules('<p lang="fr">x</p>')).toEqual(['lang']);
    expect(rules('<p lang="hi">x</p>')).toEqual([]);
    expect(rules('<label for="nowhere">x</label>')).toEqual(['label-for']);
  });

  it('flags invalid input without an error association, wrong aria values, tabs and switches without state', () => {
    expect(rules('<input aria-label="x" aria-invalid="true">')).toEqual(['error-association']);
    expect(rules('<input aria-label="x" aria-invalid="true" aria-describedby="e"><p id="e" role="alert">bad</p>')).toEqual([]);
    expect(rules('<button aria-pressed="yes">a</button>')).toEqual(['aria-value']);
    expect(rules('<div role="tab">a</div>')).toEqual(['tab-state']);
    expect(rules('<div role="switch" aria-label="x"></div>')).toEqual(['toggle-state']);
  });

  it('flags a skipped heading level, a page without exactly one h1 and a focusable control inside aria-hidden', () => {
    expect(rules('<h1>a</h1><h3>b</h3>')).toEqual(['heading-order']);
    expect(rules('<h1>a</h1><h2>b</h2><h3>c</h3><h2>d</h2>', { page: true })).toEqual([]);
    expect(rules('<h2>a</h2>', { page: true })).toEqual(['h1']);
    expect(rules('<h1>a</h1><h1>b</h1>', { page: true })).toEqual(['h1']);
    expect(rules('<h1></h1>')).toEqual(['empty-heading']);
    expect(rules('<div aria-hidden="true"><button>x</button></div>')).toEqual(['aria-hidden-focusable']);
    expect(rules('<div aria-hidden="true"><button tabindex="-1">x</button></div>')).toEqual([]);
  });

  it('flags two navigations with one name, a link without href, a table without headers', () => {
    expect(rules('<nav aria-label="Main"></nav><nav aria-label="Main"></nav>')).toEqual(['landmark-name']);
    expect(rules('<nav aria-label="Main"></nav><nav aria-label="Footer"></nav>')).toEqual([]);
    expect(rules('<a>x</a>')).toEqual(['link-href']);
    expect(rules('<table><tr><td>1</td></tr></table>')).toEqual(['table-header']);
  });

  it('wants an error to be in a live region or to describe a field', () => {
    expect(rules('<p class="error">Bad</p>')).toEqual(['error-announced']);
    expect(rules('<div role="alert"><p class="error">Bad</p></div>')).toEqual([]);
    expect(rules('<div class="error"><span role="alert">Bad</span></div>')).toEqual([]);
    expect(rules('<input aria-label="x" aria-describedby="e"><span id="e" class="field-error">Bad</span>')).toEqual([]);
    expect(rules('<span class="field-error">Bad</span>')).toEqual(['error-announced']);
  });

  it('wants an aria-label to contain the visible words (label in name)', () => {
    expect(rules('<a href="/x" aria-label="Call 98450 12345">Call</a>')).toEqual([]);
    expect(rules('<button aria-label="Close dialog"><span aria-hidden="true">x</span></button>')).toEqual([]);
    expect(rules('<button aria-label="Dismiss">Close</button>')).toEqual(['label-in-name']);
  });

  it('computes names the way a screen reader reads them', () => {
    const host = render('<button id="b">Save <span aria-hidden="true">✓</span><span class="sr-only">now</span></button>');
    expect(accessibleName(host.querySelector('#b')!)).toBe('Save now');
    host.remove();
  });

  it('expectNoViolations throws with every violation listed', () => {
    const host = render('<button></button><img src="x">');
    expect(() => expectNoViolations(host)).toThrow(/2 accessibility violation[\s\S]*\[name\][\s\S]*\[img-alt\]/);
    host.remove();
  });
});

@Component({
  selector: 'app-mouse-fixture',
  template: `
    <div id="bad" (click)="go()">Open</div>
    <div id="keyboard" role="button" tabindex="0" (click)="go()" (keydown.enter)="go()">Open</div>
    <button id="ok" (click)="go()"><span id="inner" (click)="go()">Open</span></button>
    <a id="link" href="/x" (click)="go()">Open</a>
  `,
})
class MouseFixture {
  go(): void {
    return undefined;
  }
}

describe('the mouse-only watch', () => {
  it('names a click handler on a div with no role and tabindex, and accepts buttons, links and a div with both', () => {
    const watch = watchClickListeners();
    const fixture = TestBed.createComponent(MouseFixture);
    fixture.detectChanges();
    const found = watch.stop().map((e) => e.id);
    expect(found).toEqual(['bad']);
  });

  it('stops watching: a listener added afterwards is not recorded and addEventListener is restored', () => {
    const before = EventTarget.prototype.addEventListener;
    const watch = watchClickListeners();
    expect(EventTarget.prototype.addEventListener).not.toBe(before);
    watch.stop();
    expect(EventTarget.prototype.addEventListener).toBe(before);
  });
});
