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

// The accessibility of *Experimental: more than houses* with a kind that is listed but not released (ADR-13b, 2026-10-19; docs/05 §5). The school
// is released on the website, so no real kind is left to stand for the case the switch exists for; the synthetic kind of the conformance suite
// (`forKind('zz_all')`) is registered for each case and the released set is pinned to the house and the school (`pinReleased`), so the switch is
// shown for it and for nothing else. Held in all four languages: the control is a native checkbox (its role), named by its visible label and
// described by its hint, its state follows the stored choice, the list of kinds it reveals has a name, a Show box and a Default radio each, and
// the page has no control without a name (`audit`).
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';
import { EXPERIMENTAL_KINDS_KEY, kindById, unreleasedKinds } from '../../core/kind-setting';
import { LocalDataService } from '../../core/local-data.service';
import { DICTIONARIES } from '../../i18n/all-dictionaries';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { audit } from '../../shared/testing/a11y';
import { forKind } from '../../shared/testing/kind-flows';
import { WEB_RELEASED, pinReleased, unpinReleased } from '../../shared/testing/released-kinds';
import { KindsSettings } from './kinds-settings';

const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('Experimental: more than houses, with a synthetic kind that is not released', () => {
  let flow: ReturnType<typeof forKind>;
  beforeEach(() => {
    flow = forKind('zz_all');
    pinReleased(['house', 'school']);
  });
  afterEach(() => {
    unpinReleased();
    flow.release();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  async function open(lang: Lang) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [KindsSettings], providers: [provideRouter(routes), { provide: LocalDataService, useValue: { houses: () => of([]) } }] });
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(KindsSettings);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }
  const sw = (host: HTMLElement) => host.querySelector<HTMLInputElement>('#kinds-experimental')!;

  it('the synthetic kind is the only one listed and not released, so it is what the switch is for', () => {
    expect(WEB_RELEASED).toContain('house');
    expect(unreleasedKinds()).toEqual(['zz_all']);
  });

  for (const lang of LANGS) {
    const dict = DICTIONARIES[lang] as Record<string, string>;

    it(`${lang}: the switch is a checkbox named by its label and described by its hint, off by default, with nothing of the unreleased kind`, async () => {
      const { host } = await open(lang);
      const input = sw(host);
      expect(input.tagName).toBe('INPUT');
      expect(input.type, 'role: a native checkbox').toBe('checkbox');
      expect(input.hasAttribute('role'), 'no role that would replace the native one').toBe(false);
      expect(input.checked, 'state: off by default').toBe(false);
      expect(input.disabled).toBe(false);
      const label = host.querySelector(`label[for="${input.id}"]`);
      expect(text(label), 'label').toBe(dict['kinds.switch']);
      expect(input.labels?.length).toBe(1);
      const hint = host.querySelector(`#${input.getAttribute('aria-describedby')}`);
      expect(text(hint), 'description').toBe(dict['kinds.switchHint']);
      expect(text(host.querySelector('#kinds-heading'))).toBe(dict['kinds.heading']);
      expect(host.querySelector('#kind-on-zz_all'), 'nothing of the unreleased kind while it is off').toBeNull();
      expect(host.querySelector('#kind-default-zz_all')).toBeNull();
      expect([...host.querySelectorAll('#kind-rows > li')], 'only the released kinds are listed').toHaveLength(2);
      expect(audit(host)).toEqual([]);
    });

    it(`${lang}: turning it on sets the state and the stored choice, and lists the synthetic kind with a named Show box and Default radio`, async () => {
      const { fixture, host } = await open(lang);
      sw(host).click();
      fixture.detectChanges();
      expect(sw(host).checked, 'state: on').toBe(true);
      expect(localStorage.getItem(EXPERIMENTAL_KINDS_KEY)).toBe('1');
      const many = dict[flow.kind().keys.ref('kind.many')];
      const rows = [...host.querySelectorAll('#kind-rows > li')];
      expect(rows.map((r) => text(r.querySelector('.name strong'))), 'a row for each kind, the synthetic one last').toEqual(
        ['house', 'school', 'zz_all'].map((id) => dict[kindById(id).keys.ref('kind.many')]));
      const show = host.querySelector<HTMLInputElement>('#kind-on-zz_all')!;
      expect([show.type, show.checked, show.disabled], 'Show box').toEqual(['checkbox', false, false]);
      expect(text(host.querySelector('label[for="kind-on-zz_all"]')), 'Show label').toBe(dict['kinds.show'].replace('{kind}', many));
      const dflt = host.querySelector<HTMLInputElement>('#kind-default-zz_all')!;
      expect([dflt.type, dflt.checked, dflt.disabled], 'Default radio, only for a kind that is shown').toEqual(['radio', false, true]);
      expect(dflt.getAttribute('aria-label')).toBe(dict['kinds.defaultAria'].replace('{kind}', many));
      expect(audit(host)).toEqual([]);
      sw(host).click();
      fixture.detectChanges();
      expect([sw(host).checked, host.querySelector('#kind-on-zz_all'), localStorage.getItem(EXPERIMENTAL_KINDS_KEY)], 'off again').toEqual([false, null, null]);
    });
  }

  it('the switch is not drawn at all when no kind is left unreleased', async () => {
    pinReleased(['house', 'school', 'zz_all']);
    const { host } = await open('en');
    expect(host.querySelector('#kinds-experimental')).toBeNull();
    expect(audit(host)).toEqual([]);
  });
});
