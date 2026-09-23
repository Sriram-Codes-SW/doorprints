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
