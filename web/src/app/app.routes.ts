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

import { Routes } from '@angular/router';
import { ConnectPage } from './pages/connect/connect-page';
import type { HouseDetailPage } from './pages/house-detail/house-detail-page';
import type { SharePage } from './pages/share/share-page';

// `title` values are translation keys (see i18n/en.ts), resolved by I18nTitleStrategy.
//
// Since Sprint 4a the app is local-first (docs/11 D-01): no route is guarded any more. Everything works with no
// account and no server, straight from IndexedDB; connecting a server only adds sync.
export const routes: Routes = [
  { path: 'connect', component: ConnectPage, title: 'title.connect' },
  {
    path: '',
    pathMatch: 'full',
    title: 'title.map',
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
