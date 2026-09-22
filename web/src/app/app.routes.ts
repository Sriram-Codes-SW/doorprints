import { Routes } from '@angular/router';
import { configGuard } from './core/config.guard';
import { ConnectPage } from './pages/connect/connect-page';
import type { HouseDetailPage } from './pages/house-detail/house-detail-page';

// `title` values are translation keys (see i18n/en.ts), resolved by I18nTitleStrategy.
export const routes: Routes = [
  { path: 'connect', component: ConnectPage, title: 'title.connect' },
  {
    path: '',
    pathMatch: 'full',
    canActivate: [configGuard],
    title: 'title.map',
    loadComponent: () => import('./pages/map/map-page').then((m) => m.MapPage),
  },
  {
    path: 'houses/new',
    canActivate: [configGuard],
    canDeactivate: [(page: HouseDetailPage) => page.canLeave()],
    title: 'title.newHouse',
    loadComponent: () => import('./pages/house-detail/house-detail-page').then((m) => m.HouseDetailPage),
  },
  {
    path: 'houses/:id',
    canActivate: [configGuard],
    canDeactivate: [(page: HouseDetailPage) => page.canLeave()],
    title: 'title.house',
    loadComponent: () => import('./pages/house-detail/house-detail-page').then((m) => m.HouseDetailPage),
  },
  {
    path: 'ask',
    canActivate: [configGuard],
    title: 'title.ask',
    loadComponent: () => import('./pages/ask/ask-page').then((m) => m.AskPage),
  },
  {
    path: 'plan',
    canActivate: [configGuard],
    title: 'title.plan',
    loadComponent: () => import('./pages/plan/plan-page').then((m) => m.PlanPage),
  },
  {
    path: 'compare',
    canActivate: [configGuard],
    title: 'title.compare',
    loadComponent: () => import('./pages/compare/compare-page').then((m) => m.ComparePage),
  },
  { path: '**', redirectTo: '' },
];
