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

// Spaces (kinds PR 8, S4b-BL-212; docs/03 §18.12): the router config is today's with one kind enabled, the spaces'
// addresses with two or more, and the space service reads the space from the address. docs/06 TC-U-214.
import { TestBed } from '@angular/core/testing';
import { Router, type Routes, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { LocalDataService } from './local-data.service';
import { afterEach, describe, expect, it } from 'vitest';
import { routes, routesFor } from '../app.routes';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY, spacesOn } from './kind-setting';
import { SpaceService, kindOfPath, spaceOf } from './space.service';
import { spaceWords } from './space-words';

/** Today's top-level paths, in order: the house hunter's addresses, start URL and share target. */
const TODAY = ['connect', '', 'houses/new', 'houses/:id', 'ask', 'plan', 'compare', 'brokers', 'brokers/:id', 'criteria', 'questions', 'viewings', 'viewings/new', 'viewings/:id', 'areas', 'places', 'data', 'share', '**'];
const paths = (r: Routes) => r.map((x) => x.path);

function twoKinds(dflt = 'house'): void {
  localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
  localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
  localStorage.setItem(DEFAULT_KIND_KEY, dflt);
}

afterEach(() => {
  localStorage.clear();
  TestBed.resetTestingModule();
});

describe('the router config (S4b-BL-212)', () => {
  it('with one kind enabled the router config equals today’s', () => {
    expect(paths(routes)).toEqual(TODAY);
    expect(spacesOn()).toBe(false);
    expect(routesFor(spacesOn())).toBe(routes);
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    expect(routesFor(spacesOn())).toBe(routes);
    localStorage.setItem(ENABLED_KINDS_KEY, '["school"]');
    expect(routesFor(spacesOn())).toBe(routes);
  });

  it('the app’s router has today’s config with nothing stored', () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routesFor(spacesOn()))] });
    expect(TestBed.inject(Router).config).toEqual(routes);
  });

  it('with two kinds: the chooser at /, a list, a new form and a page per kind, and every other page as today', () => {
    const r = routesFor(true, ['house', 'school']);
    expect(paths(r)).toEqual(['connect', '', 'k/house', 'k/house/new', 'k/house/:id', 'k/school', 'k/school/new', 'k/school/compare', 'k/school/ask', 'k/school/plan', 'k/school/:id', ...TODAY.slice(2)]);
    expect(r[1].title).toBe('title.chooser');
    expect(r.find((x) => x.path === 'k/school')!.loadComponent).toBe(routes[1].loadComponent);
    expect(r.find((x) => x.path === 'k/school/:id')!.canDeactivate).toBe(routes[3].canDeactivate);
  });
});

describe('the space of an address and of a candidate', () => {
  it('a space path names its kind; the house keeps its pages; other pages belong to no space', () => {
    expect(['/k/school', '/k/school/new', '/k/school/abc', '/k/house', '/houses/abc', '/houses/new', '/compare', '/ask', '/plan', '/', '/data', '/connect', '/k/X'].map(kindOfPath))
      .toEqual(['school', 'school', 'school', 'house', 'house', 'house', 'house', 'house', 'house', null, null, null, null]);
  });

  it('a candidate is in its own kind’s space when that kind has one, else in the default space', () => {
    const av = ['house', 'school'];
    expect([spaceOf({}, true, 'house', av), spaceOf({ kind: 'school' }, true, 'house', av), spaceOf({ kind: 'clinic' }, true, 'school', av)])
      .toEqual(['house', 'school', 'school']);
    expect(spaceOf({ kind: 'school' }, false, 'house', av)).toBe('house');
    expect(spaceOf({ kind: 'school' }, true, 'house', ['house'])).toBe('house');
  });

  it('a space’s words: the house’s keys as today, the school’s own, the counter from the kind file', () => {
    expect(spaceWords('house')).toMatchObject({ heading: 'map.heading', add: 'map.add', many: 'map.stats.houses', create: 'house.create', areaNotesNone: 'areaNotes.none' });
    expect(spaceWords('school')).toMatchObject({ heading: 'school.heading', add: 'school.add', many: 'school.many', create: 'school.create', emptyTitle: 'school.emptyTitle' });
    expect(spaceWords('clinic')).toEqual(spaceWords('house'));
  });
});

describe('SpaceService', () => {
  function service(spaces: boolean) {
    TestBed.configureTestingModule({ providers: [provideRouter(routesFor(spaces, ['house', 'school']))] });
    return { space: TestBed.inject(SpaceService), router: TestBed.inject(Router) };
  }

  it('with one kind: today’s links, every candidate held, a new candidate of the default kind', () => {
    const { space } = service(false);
    expect([space.multi(), space.kind(), space.home(), space.detailLink('a'), space.newLink(), space.newKind()])
      .toEqual([false, 'house', '/', ['/houses', 'a'], '/houses/new', 'house']);
    expect([space.holds({ kind: 'school' }), space.holds({}), space.toolLink('compare'), space.hidden()]).toEqual([true, true, '/compare', false]);
  });

  it('with two kinds: the space from the address, its links and its candidates; Back from a space comes to the chooser', async () => {
    twoKinds('school');
    const { space, router } = service(true);
    expect([space.multi(), space.inChooser(), space.kind()]).toEqual([true, true, 'school']);
    await router.navigateByUrl('/k/house');
    expect([space.kind(), space.home(), space.newLink(), space.detailLink('a'), space.toolLink('ask'), space.newKind()]).toEqual(['house', '/k/house', '/k/house/new', ['/houses', 'a'], '/ask', 'house']);
    expect([space.holds({}), space.holds({ kind: 'school' }), space.holds({ kind: 'clinic' })]).toEqual([true, false, false]);
    await router.navigateByUrl('/k/school');
    expect([space.kind(), space.newKind(), space.detailLink('a'), space.toolLink('plan'), space.inChooser()]).toEqual(['school', 'school', ['/k', 'school', 'a'], '/k/school/plan', false]);
    expect([space.holds({ kind: 'school' }), space.holds({ kind: 'clinic' })]).toEqual([true, true]);
    // A page of no space keeps the last; `/houses/new` (a deep link, the share target) is the default kind's.
    await router.navigateByUrl('/data');
    expect(space.kind()).toBe('school');
    await router.navigateByUrl('/houses/new');
    expect([space.kind(), space.newKind()]).toEqual(['house', 'school']);
  });

  it('a space whose kind is turned off (a third kind with entries) still opens, marked hidden', async () => {
    twoKinds();
    const { space, router } = service(true);
    await router.navigateByUrl('/k/school');
    expect(space.hidden()).toBe(false);
    space.enabled.set(['house']);
    expect([space.kind(), space.hidden()]).toEqual(['school', true]);
  });

  it('refresh hands the router the other config only when spaces turn on or off', () => {
    const { space, router } = service(false);
    const before = router.config;
    space.refresh(routesFor);
    expect(router.config).toBe(before);
    twoKinds();
    space.refresh((multi) => routesFor(multi, ['house', 'school']));
    expect([space.multi(), paths(router.config).includes('k/school')]).toEqual([true, true]);
    localStorage.clear();
    space.refresh(routesFor);
    expect([space.multi(), router.config]).toEqual([false, routes]);
  });
});

describe('the school as the default kind (docs/03 §18.18)', () => {
  function service(spaces: boolean) {
    TestBed.configureTestingModule({ providers: [provideRouter(routesFor(spaces, ['house', 'school']))] });
    return { space: TestBed.inject(SpaceService), router: TestBed.inject(Router) };
  }

  it('the school the default with both on: the house keeps its space; /houses/new and a page of no space make a school', async () => {
    twoKinds('school');
    const { space, router } = service(true);
    expect([space.inChooser(), space.kind()]).toEqual([true, 'school']);
    await router.navigateByUrl('/k/house');
    expect([space.kind(), space.newKind(), space.newLink()]).toEqual(['house', 'house', '/k/house/new']);
    await router.navigateByUrl('/data');
    await router.navigateByUrl('/houses/new');
    expect([space.kind(), space.newKind()]).toEqual(['house', 'school']);
    expect([space.dflt(), space.holds({}), space.holds({ kind: 'school' })]).toEqual(['school', true, false]);
  });

  it('the school the default and the house off: one space, every kept house is listed and held, a new entry is a school', async () => {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    localStorage.setItem(ENABLED_KINDS_KEY, '["school"]');
    localStorage.setItem(DEFAULT_KIND_KEY, 'school');
    const { space, router } = service(false);
    await router.navigateByUrl('/');
    expect([space.multi(), space.enabled(), space.kind(), space.inChooser(), space.hidden()]).toEqual([false, ['school'], 'school', false, false]);
    expect([space.holds({}), space.holds({ kind: 'school' }), space.holds({ kind: 'clinic' })]).toEqual([true, true, true]);
    expect([space.home(), space.newLink(), space.newKind(), space.detailLink('a'), space.toolLink('ask')]).toEqual(['/', '/houses/new', 'school', ['/houses', 'a'], '/ask']);
  });

  it('a space that is turned off takes no new entry: its form and its Add go to the default kind', async () => {
    twoKinds();
    const { space, router } = service(true);
    await router.navigateByUrl('/k/school');
    expect(space.newKind()).toBe('school');
    space.enabled.set(['house']);
    expect([space.hidden(), space.newKind(), space.newLink(), space.createIn('school'), space.createIn('house')]).toEqual([true, 'house', '/k/house/new', 'house', 'house']);
    space.dflt.set('school');
    space.enabled.set(['school']);
    await router.navigateByUrl('/k/house');
    expect([space.newKind(), space.newLink('house')]).toEqual(['school', '/k/school/new']);
  });
});

describe('a deep link to a candidate of another kind (S4b-BL-212)', () => {
  async function go(url: string, house: () => unknown) {
    twoKinds();
    TestBed.configureTestingModule({
      providers: [provideRouter(routesFor(true, ['house', 'school'])), { provide: LocalDataService, useValue: { house } }],
    });
    const router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    return router.url;
  }

  it('/houses/<id> of a school opens it in the school space', async () => {
    expect(await go('/houses/s1', () => of({ id: 's1', kind: 'school' }))).toBe('/k/school/s1');
  });

  it('/houses/<id> of a house, of a kind this build does not have, or that cannot be read stays where it is', async () => {
    expect(await go('/houses/h1', () => of({ id: 'h1' }))).toBe('/houses/h1');
    TestBed.resetTestingModule();
    expect(await go('/houses/c1', () => of({ id: 'c1', kind: 'clinic' }))).toBe('/houses/c1');
    TestBed.resetTestingModule();
    expect(await go('/houses/x', () => throwError(() => new Error('not found')))).toBe('/houses/x');
  });
});
