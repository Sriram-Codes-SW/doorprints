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

import { isBaselineKind } from './core/kind-capabilities';
import { type CanActivateFn, type Route, Router, Routes } from '@angular/router';
import { Injector, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { availableKinds } from './core/kind-setting';
import { toolWord } from './core/space-tool-words';
import { ConnectPage } from './pages/connect/connect-page';
import type { HouseDetailPage } from './pages/house-detail/house-detail-page';
import type { SharePage } from './pages/share/share-page';

// `title` values are translation keys (see i18n/en.ts), resolved by I18nTitleStrategy.
//
// `data: { index: true }` marks the routes a search engine may index (default: none; I18nTitleStrategy writes
// `noindex` for the rest). Only the landing page is public; see seo.spec.ts.
//
// Since Sprint 4a the app is local-first (docs/11 D-01): no route is guarded any more. Everything works with no
// account and no server, straight from IndexedDB; connecting a server only adds sync.
/**
 * The app's pages. All but Connect load on demand, and no route needs an account or a server. The house pages and the
 * share page ask before leaving unsaved edits (`canDeactivate`); an unknown address goes to the map.
 */
export const routes: Routes = [
  { path: 'connect', component: ConnectPage, title: 'title.connect' },
  {
    path: '',
    pathMatch: 'full',
    title: 'title.map',
    data: { index: true },
    loadComponent: () => import('./pages/map/map-page').then((m) => m.MapPage),
  },
  {
    path: 'houses/new',
    canDeactivate: [(page: HouseDetailPage) => page.canLeave()],
    title: 'title.newHouse',
    loadComponent: () => import('./pages/house-detail/house-detail-page').then((m) => m.HouseDetailPage),
  },
  {
    path: 'houses/:id',
    canDeactivate: [(page: HouseDetailPage) => page.canLeave()],
    title: 'title.house',
    loadComponent: () => import('./pages/house-detail/house-detail-page').then((m) => m.HouseDetailPage),
  },
  {
    path: 'ask',
    title: 'title.ask',
    loadComponent: () => import('./pages/ask/ask-page').then((m) => m.AskPage),
  },
  {
    path: 'plan',
    title: 'title.plan',
    loadComponent: () => import('./pages/plan/plan-page').then((m) => m.PlanPage),
  },
  {
    path: 'compare',
    title: 'title.compare',
    loadComponent: () => import('./pages/compare/compare-page').then((m) => m.ComparePage),
  },
  {
    path: 'brokers',
    title: 'title.brokers',
    loadComponent: () => import('./pages/brokers/brokers-page').then((m) => m.BrokersPage),
  },
  {
    path: 'brokers/:id',
    title: 'title.broker',
    loadComponent: () => import('./pages/brokers/broker-page').then((m) => m.BrokerPage),
  },
  {
    path: 'criteria',
    title: 'title.criteria',
    loadComponent: () => import('./pages/criteria/criteria-page').then((m) => m.CriteriaPage),
  },
  {
    path: 'questions',
    title: 'title.questions',
    loadComponent: () => import('./pages/questions/questions-page').then((m) => m.QuestionsPage),
  },
  {
    path: 'viewings',
    title: 'title.viewings',
    loadComponent: () => import('./pages/viewings/viewings-page').then((m) => m.ViewingsPage),
  },
  {
    path: 'viewings/new',
    title: 'title.viewing',
    loadComponent: () => import('./pages/viewings/viewing-page').then((m) => m.ViewingPage),
  },
  {
    path: 'viewings/:id',
    title: 'title.viewing',
    loadComponent: () => import('./pages/viewings/viewing-page').then((m) => m.ViewingPage),
  },
  {
    path: 'areas',
    title: 'title.areas',
    loadComponent: () => import('./pages/areas/areas-page').then((m) => m.AreasPage),
  },
  {
    path: 'places',
    title: 'title.places',
    loadComponent: () => import('./pages/places/places-page').then((m) => m.PlacesPage),
  },
  {
    path: 'data',
    title: 'title.data',
    loadComponent: () => import('./pages/data/data-page').then((m) => m.DataPage),
  },
  {
    // PWA share target (manifest.webmanifest). Full listing parsing is S4-13 in Sprint 4b.
    path: 'share',
    // Leaving after editing the shared text asks first (the text is not kept anywhere else).
    canDeactivate: [(page: SharePage) => page.canLeave()],
    title: 'title.share',
    loadComponent: () => import('./pages/share/share-page').then((m) => m.SharePage),
  },
  { path: '**', redirectTo: '' },
];

/**
 * The router config (kinds PR 8, S4b-BL-212; docs/03 §18.12). With one kind enabled it is {@link routes}, the very same
 * array, so a house hunter's app has today's addresses, start URL and share target. With two or more, `/` is the
 * chooser and each kind this build offers has `/k/<kind>` (its list and map), `/k/<kind>/new` and `/k/<kind>/<id>`, one
 * route each, so going from one space to another builds the page again; the house keeps its own pages
 * (`/houses/<id>`, `/houses/new` for the default kind, `/compare`, `/ask`, `/plan`).
 */
/**
 * `/houses/<id>` of a candidate of another kind (a link from before the spaces, a notification) opens it in its own space,
 * `/k/<kind>/<id>`; a house, a candidate that is not there and a read that fails open as before.
 */
const toOwnSpace: CanActivateFn = async (route) => {
  const router = inject(Router);
  const injector = inject(Injector);
  const id = route.paramMap.get('id') ?? '';
  try {
    const { LocalDataService } = await import('./core/local-data.service');
    const kind = (await firstValueFrom(injector.get(LocalDataService).house(id)))?.kind;
    return kind && !isBaselineKind(kind) && availableKinds().includes(kind) ? router.createUrlTree(['/k', kind, id]) : true;
  } catch {
    return true;
  }
};

/** The pages that read one space (docs/03 §18.13). */
const TOOLS = ['compare', 'ask', 'plan'] as const;

export function routesFor(spaces: boolean, kinds: readonly string[] = availableKinds()): Routes {
  if (!spaces) return routes;
  const detail = routes.find((r) => r.path === 'houses/:id')!;
  const map = routes.find((r) => r.path === '')!;
  return [
    routes[0],
    { path: '', pathMatch: 'full', title: 'title.chooser', loadComponent: () => import('./pages/chooser/chooser-page').then((m) => m.ChooserPage) },
    ...kinds.flatMap((k): Routes => [
      // The tab titles are the kind's own (S4b-BL-259, S4b-BL-277): the house's keys for the house, a school's for a school.
      { ...map, path: `k/${k}`, data: undefined, title: toolWord(k, 'title.map') },
      { ...detail, path: `k/${k}/new`, title: toolWord(k, 'title.newHouse') },
      // Compare, Ask and Plan of a space other than the house (S4b-BL-279); the house keeps its own three. Before `:id`.
      ...(isBaselineKind(k) ? [] : TOOLS.map((t): Route => ({ ...routes.find((r) => r.path === t)!, path: `k/${k}/${t}` }))),
      { ...detail, path: `k/${k}/:id`, title: toolWord(k, 'title.house') },
    ]),
    ...routes.slice(1).filter((r) => r !== map).map((r) => (r === detail ? { ...detail, canActivate: [toOwnSpace] } : r)),
  ];
}
