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

import { Component, Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import indexHtml from '../index.html' with { loader: 'text' };
import styles from '../styles.css' with { loader: 'text' };
import askCss from './pages/ask/ask-page.css' with { loader: 'text' };
import houseCss from './pages/house-detail/house-detail-page.css' with { loader: 'text' };
import mapCss from './pages/map/map-page.css' with { loader: 'text' };
import { hi } from './i18n/hi';
import { ta } from './i18n/ta';
import { te } from './i18n/te';
import { App } from './app';
import { Announcer } from './core/announcer.service';
import { AiService } from './core/ai.service';
import { ConfirmDialog } from './shared/confirm-dialog';
import { ConfirmService } from './core/confirm.service';
import { HouseApiService } from './core/house-api.service';
import { LocalDataService } from './core/local-data.service';
import type { PhotoSummary } from './core/local-data.service';
import { newHouse } from './core/models';
import type { HouseDto } from './core/models';
import { LocalStore } from './data/local-store.service';
import { SyncService } from './data/sync.service';
import type { Lang } from './i18n/languages';
import { TranslationService } from './i18n/translation.service';
import { OFFLINE_ENV, OfflineMapsService } from './offline/offline-maps.service';
import { setOfflineActive } from './offline/offline-protocol';
import { fakeEnv } from './offline/testing/offline-fakes';
import { AskPage } from './pages/ask/ask-page';
import { AreasPage } from './pages/areas/areas-page';
import { BrokerPage } from './pages/brokers/broker-page';
import { BrokersPage } from './pages/brokers/brokers-page';
import { ComparePage } from './pages/compare/compare-page';
import { ConnectPage } from './pages/connect/connect-page';
import { CriteriaPage } from './pages/criteria/criteria-page';
import { DataPage } from './pages/data/data-page';
import { DriveConnectComponent } from './pages/data/drive-connect';
import { DriveConnectService, type ConnectState } from './data/drive/connect/drive-connect.service';
import { ImportBackupCard } from './pages/data/import-backup';
import { OfflineAreasCard } from './pages/data/offline-areas';
import { HouseDetailPage } from './pages/house-detail/house-detail-page';
import { HouseMoveInCard } from './pages/house-detail/house-move-in-card';
import { PhotoMetaEditor } from './pages/house-detail/photo-meta-editor';
import { MapPage } from './pages/map/map-page';
import { OfflineSave } from './pages/map/offline-save';
import { PlacesPage } from './pages/places/places-page';
import { PlanPage } from './pages/plan/plan-page';
import { QuestionsPage } from './pages/questions/questions-page';
import { HouseViewingsCard } from './pages/viewings/house-viewings-card';
import { SecondViewingPrompt } from './pages/viewings/second-viewing-prompt';
import { ViewingPage } from './pages/viewings/viewing-page';
import { ViewingsPage } from './pages/viewings/viewings-page';
import { DEFAULT_SCORING } from './shared/scoring';
import { audit, watchClickListeners } from './shared/testing/a11y';
import type { Violation } from './shared/testing/a11y';

/**
 * The accessibility sweep (Wave D, docs/14 N14): every page and the new components are rendered in English and in
 * Hindi (an Indic script, whose long labels and different lang attribute are where the gaps hide), then checked by the
 * rules of `shared/testing/a11y.ts` (names, dialogs, ids, aria references, tab order, headings, keyboard operability of
 * click handlers) with the app's real components and templates. jsdom has no layout and no focus ring, so sizes, the
 * focus indicator and a screen reader's reading are manual (docs/06 TC-M).
 */

const HOUSE: HouseDto = {
  ...newHouse(12.9716, 77.5946),
  id: 'h1',
  label: 'Blue gate 2BHK',
  address: '12, 4th Cross, Indiranagar',
  status: 'SHORTLISTED',
  price: 32000,
  priceType: 'RENT',
  bedrooms: 2,
  rating: 4,
  areaSqft: 1100,
  floor: 3,
  contactName: 'Ravi',
  contactPhone: '+91 98450 12345',
  listingUrl: 'https://example.com/listing/1',
  notes: 'Near the park',
  checklist: { security: 4 },
  cost: { deposit: 64000, maintenance: 2500, maintenanceIncluded: false, brokerageMonths: 1, availableFrom: '2026-10-15', agreedPrice: 31000 },
  rooms: [
    { id: 'r1', type: 'KITCHEN', name: 'Kitchen', lengthCm: 300, widthCm: 250, condition: 4, sort: 0 },
    { id: 'r2', type: 'BEDROOM', sort: 1 },
  ],
  answers: [{ id: 'a1', text: 'Is there a water meter?', answer: 'Yes', status: 'ANSWERED', sort: 0 }],
  moveIn: { date: Date.parse('2026-11-01'), notes: 'Keys on the 1st', items: [{ id: 'i1', text: 'Take meter readings', sort: 0 }] },
  createdAt: '2026-09-20T10:00:00.000Z',
  updatedAt: '2026-09-20T10:00:00.000Z',
};
const OTHER: HouseDto = { ...newHouse(12.95, 77.6), id: 'h2', label: 'Lake view', price: 5200000, priceType: 'SALE', areaSqft: 1450, createdAt: '2026-09-21T10:00:00.000Z' };
const PHOTO: PhotoSummary = { id: 'p1', createdAt: '2026-10-01T06:00:00.000Z', roomId: 'r1', tags: ['MOVE_IN', 'DAMP'], caption: 'Corner', metaUpdatedAt: 5 };
const VIEWING = { id: 'v1', houseId: 'h1', startsAt: Date.now() + 3_600_000, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60 };

/** LocalDataService with data on the methods the pages call and an empty list for any other. */
function fakeApi(over: Record<string, unknown> = {}) {
  const known: Record<string, unknown> = {
    settled: signal(0),
    revision: signal(0),
    stats: () => of({ houses: 2, shortlisted: 1, rejected: 0, visits: 1, streets: 1 }),
    houses: () => of([HOUSE, OTHER]),
    house: () => of(HOUSE),
    scoring: () => of(DEFAULT_SCORING),
    visitCounts: () => of(new Map()),
    viewings: () => of([VIEWING]),
    viewingsOf: () => of([VIEWING]),
    viewingsRemind: () => of(false),
    nextViewing: () => of(VIEWING),
    newViewingId: () => of('v2'),
    photos: () => of([PHOTO]),
    conditionPhotos: () => of([PHOTO]),
    photo: () => throwError(() => new Error('no bytes')),
    closeCount: () => of(2),
    ...over,
  };
  return new Proxy(known, {
    get: (target, key: string) => (key in target ? target[key] : (): Observable<unknown[]> => of([])),
  });
}

/** The AI service with the assistant on (the Ask and Plan pages, the Connect card) or off. */
function fakeAi(on: boolean) {
  return {
    enabled: signal(on),
    optedIn: signal(on),
    serverEnabled: signal(on),
    provider: signal('server'),
    geminiKeyHint: signal(''),
    hasGeminiKey: signal(false),
    usesOwnKey: signal(false),
    ownHost: signal(''),
    aiConfig: signal({ kind: 'gemini', baseUrl: '', model: '' }),
    aiQuality: signal('quality'),
    setAiQuality: () => undefined,
    offReason: signal(on ? null : 'optOut'),
    refresh: () => undefined,
  };
}

function route(params: Record<string, string> = {}, query: Record<string, string> = {}) {
  return {
    provide: ActivatedRoute,
    useValue: {
      snapshot: { paramMap: convertToParamMap(params), queryParamMap: convertToParamMap(query), params, queryParams: query },
      paramMap: of(convertToParamMap(params)),
      queryParamMap: of(convertToParamMap(query)),
      params: of(params),
      queryParams: of(query),
    },
  };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 4; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  fixture.detectChanges();
}

const baseProviders = (api: unknown = fakeApi(), params: Record<string, string> = {}, query: Record<string, string> = {}) => [
  provideRouter([]),
  { provide: LocalDataService, useValue: api },
  { provide: SyncService, useValue: { migration: signal('done'), enabled: signal(false), lastError: signal(null), lastErrorForced: signal(false), downloadToThisBrowser: vi.fn() } },
  { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
  { provide: AiService, useValue: fakeAi(true) },
  { provide: HouseApiService, useValue: { testConnection: () => new Subject() } },
  route(params, query),
];

interface Case {
  name: string;
  render: (lang: Lang) => Promise<{ fixture: ComponentFixture<unknown>; page: boolean }>;
}

/** Renders a standalone component with the base providers. */
function page<T>(component: Type<T>, opts: { api?: unknown; params?: Record<string, string>; query?: Record<string, string>; inputs?: Record<string, unknown>; extra?: unknown[]; whole?: boolean } = {}): Case['render'] {
  return async (lang) => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [component], providers: [...baseProviders(opts.api, opts.params, opts.query), ...((opts.extra ?? []) as never[])] });
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(component);
    for (const [k, v] of Object.entries(opts.inputs ?? {})) fixture.componentRef.setInput(k, v);
    await settle(fixture as ComponentFixture<unknown>);
    return { fixture: fixture as ComponentFixture<unknown>, page: opts.whole ?? true };
  };
}

/** A component with nothing replaced but the router (the real stores, which jsdom runs in memory). */
function plain<T>(component: Type<T>, whole = true): Case['render'] {
  return async (lang) => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [component], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(component);
    await settle(fixture as ComponentFixture<unknown>);
    return { fixture: fixture as ComponentFixture<unknown>, page: whole };
  };
}

const AREA = { south: 12.97, west: 77.64, north: 12.9701, east: 77.6401 };

/** The offline-maps card with two saved areas, and the Map's save dialog open. */
function offline<T>(component: Type<T>, open: boolean): Case['render'] {
  return async (lang) => {
    TestBed.resetTestingModule();
    const rig = fakeEnv();
    TestBed.configureTestingModule({ imports: [component], providers: [provideRouter([]), { provide: OFFLINE_ENV, useValue: rig.env }] });
    await TestBed.inject(TranslationService).setLang(lang);
    await TestBed.inject(OfflineMapsService).save('Indiranagar', AREA);
    const fixture = TestBed.createComponent(component);
    if (open) fixture.componentRef.setInput('boundsOf', () => AREA);
    await settle(fixture as ComponentFixture<unknown>);
    if (open) {
      (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(':scope > button')!.click();
      await settle(fixture as ComponentFixture<unknown>);
    }
    return { fixture: fixture as ComponentFixture<unknown>, page: false };
  };
}

/** The confirm dialog opened by a request, with three buttons. */
const confirmOpen: Case['render'] = async (lang) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ imports: [ConfirmDialog], providers: [provideRouter([])] });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(ConfirmDialog);
  await settle(fixture as ComponentFixture<unknown>);
  void TestBed.inject(ConfirmService).choose({ key: 'common.retry' }, { altKey: 'common.retry', danger: true });
  await settle(fixture as ComponentFixture<unknown>);
  return { fixture: fixture as ComponentFixture<unknown>, page: false };
};

const boom = () => throwError(() => new Error('boom'));
const EMPTY = fakeApi({ houses: () => of([]), brokers: () => of([]), viewings: () => of([]), viewingRows: () => of([]), viewingsOf: () => of([]) });
const BROKEN = fakeApi({ houses: boom, brokers: boom, viewings: boom, scoring: boom, questions: boom, questionRows: boom, criterionRows: boom, stats: boom });

function driveCard(state: ConnectState, theme: 'light' | 'dark', after?: (c: DriveConnectComponent) => void): Case['render'] {
  return async (lang) => {
    TestBed.resetTestingModule();
    document.documentElement.style.colorScheme = theme;
    const svc = {
      getState: () => state,
      enrolmentNotice: () => null,
      listBackups: async () => ({ ok: true, backups: [], missingNewer: false }),
      autoBackupEnabled: () => false,
      setAutoBackup: () => undefined,
      runDueBackup: async () => ({ ran: false, reason: 'DISABLED' }),
      syncNow: async () => ({ state: 'synced', lastSyncAt: Date.now(), skipped: [], needsConfirmation: false }),
      photoSettings: async () => ({ uploadOnMobileData: false }),
      pendingPhotoBytes: async () => 0,
      passkeyStatus: async () => 'none',
      confirmRecoveryKeySaved: () => undefined,
      skipRecoveryKeyWithWarning: () => undefined,
      disconnect: async () => undefined,
      connect: async () => ({ state }),
      createFolder: async () => ({ state, recoveryKey: 'AAAA-BBBB-CCCC-DDDD-EEEE-FFFF' }),
      openWithRecoveryKey: async () => ({ state: 'Ready' }),
      listedDevices: async () => [],
      accountEmail: async () => null,
    };
    TestBed.configureTestingModule({
      imports: [DriveConnectComponent],
      providers: [
        provideRouter([]),
        { provide: DriveConnectService, useValue: svc },
        { provide: Announcer, useValue: { announce: () => undefined } },
        { provide: ConfirmService, useValue: { ask: async () => true, choose: async () => 'confirm' } },
      ],
    });
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(DriveConnectComponent);
    after?.(fixture.componentInstance);
    await settle(fixture as ComponentFixture<unknown>);
    return { fixture: fixture as ComponentFixture<unknown>, page: false };
  };
}

/** The Connect page's AI card with another service chosen and *Save* pressed on an empty form (every field error shown). */
function connectService(service: string, theme: 'light' | 'dark'): Case['render'] {
  return async (lang) => {
    document.documentElement.style.colorScheme = theme;
    const rendered = await page(ConnectPage)(lang);
    const root = rendered.fixture.nativeElement as HTMLElement;
    const select = root.querySelector<HTMLSelectElement>('#ai-service')!;
    select.value = service;
    select.dispatchEvent(new Event('change'));
    await settle(rendered.fixture);
    root.querySelector<HTMLButtonElement>('#ai-save')!.click();
    await settle(rendered.fixture);
    return rendered;
  };
}

/** The Connect page's AI card on Google Gemini, with the *AI speed and cost* group (S4b-BL-198): it must be there, or this case proves nothing. */
function connectGemini(theme: 'light' | 'dark'): Case['render'] {
  return async (lang) => {
    document.documentElement.style.colorScheme = theme;
    const rendered = await page(ConnectPage)(lang);
    if (!(rendered.fixture.nativeElement as HTMLElement).querySelector('fieldset#ai-quality input[type="radio"]')) {
      throw new Error('the AI speed and cost group is not on the page');
    }
    return rendered;
  };
}

const CASES: Case[] = [
  { name: 'Map (empty)', render: async (lang) => {
    vi.spyOn(MapPage.prototype, 'ngAfterViewInit').mockImplementation(() => undefined);
    return page(MapPage, { api: EMPTY })(lang);
  } },
  { name: 'Map (error)', render: async (lang) => {
    vi.spyOn(MapPage.prototype, 'ngAfterViewInit').mockImplementation(() => undefined);
    return page(MapPage, { api: BROKEN })(lang);
  } },
  { name: 'Compare (error)', render: page(ComparePage, { api: BROKEN, query: { ids: 'h1,h2' } }) },
  { name: 'Brokers (empty)', render: page(BrokersPage, { api: EMPTY }) },
  { name: 'Brokers (error)', render: page(BrokersPage, { api: BROKEN }) },
  { name: 'Viewings (empty)', render: page(ViewingsPage, { api: EMPTY }) },
  { name: 'Viewings (error)', render: page(ViewingsPage, { api: BROKEN }) },
  { name: 'Criteria (error)', render: page(CriteriaPage, { api: BROKEN }) },
  { name: 'Questions (error)', render: page(QuestionsPage, { api: BROKEN }) },
  { name: 'App shell', render: plain(App, false) },
  { name: 'Your data', render: plain(DataPage) },
  { name: 'Drive card (disconnected, light)', render: driveCard('Disconnected', 'light') },
  { name: 'Drive card (disconnected, dark)', render: driveCard('Disconnected', 'dark') },
  { name: 'Drive card (recovery key, light)', render: driveCard('FirstConnectShowRecoveryKey', 'light', (c) => c['recoveryKey'].set('AAAA-BBBB-CCCC-DDDD-EEEE-FFFF')) },
  { name: 'Drive card (recovery key, dark)', render: driveCard('FirstConnectShowRecoveryKey', 'dark', (c) => c['recoveryKey'].set('AAAA-BBBB-CCCC-DDDD-EEEE-FFFF')) },
  { name: 'Drive card (ready, light)', render: driveCard('Ready', 'light') },
  { name: 'Drive card (ready, dark)', render: driveCard('Ready', 'dark') },
  { name: 'Drive card (unavailable, light)', render: driveCard('Unavailable', 'light') },
  { name: 'Drive card (unavailable, dark)', render: driveCard('Unavailable', 'dark') },
  { name: 'Drive card (connecting, light)', render: driveCard('Connecting', 'light') },
  { name: 'Drive card (connecting, dark)', render: driveCard('Connecting', 'dark') },
  { name: 'Drive card (connection in progress, light)', render: driveCard('Disconnected', 'light', (c) => c['connectWork'].set(true)) },
  { name: 'Drive card (connection in progress, dark)', render: driveCard('Disconnected', 'dark', (c) => c['connectWork'].set(true)) },
  { name: 'Drive card (needs enrolment, light)', render: driveCard('NeedsEnrolment', 'light') },
  { name: 'Drive card (needs enrolment, dark)', render: driveCard('NeedsEnrolment', 'dark') },
  { name: 'Drive card (error, light)', render: driveCard('Error', 'light', (c) => c['error'].set('driveConnect.failed')) },
  { name: 'Drive card (error, dark)', render: driveCard('Error', 'dark', (c) => c['error'].set('driveConnect.failed')) },
  { name: 'Import a backup card', render: plain(ImportBackupCard, false) },
  { name: 'Offline maps card (areas saved)', render: offline(OfflineAreasCard, false) },
  { name: 'Offline maps dialog (open)', render: offline(OfflineSave, true) },
  { name: 'Confirm dialog (open)', render: confirmOpen },
  { name: 'Plan', render: page(PlanPage) },
  { name: 'Broker', render: page(BrokerPage, { params: { id: 'b1' } }) },
  { name: 'Map (list, filters)', render: async (lang) => {
    vi.spyOn(MapPage.prototype, 'ngAfterViewInit').mockImplementation(() => undefined);
    return page(MapPage)(lang);
  } },
  { name: 'Compare', render: page(ComparePage, { query: { ids: 'h1,h2' } }) },
  { name: 'Criteria', render: page(CriteriaPage) },
  { name: 'Questions', render: page(QuestionsPage) },
  { name: 'Brokers', render: page(BrokersPage) },
  { name: 'Areas', render: page(AreasPage) },
  { name: 'Places', render: page(PlacesPage) },
  { name: 'Viewings', render: page(ViewingsPage) },
  { name: 'Viewing form', render: page(ViewingPage, { query: { houseId: 'h1' } }) },
  { name: 'Connect (assistant on)', render: page(ConnectPage) },
  { name: 'Connect (assistant off)', render: page(ConnectPage, { extra: [{ provide: AiService, useValue: fakeAi(false) }] }) },
  { name: 'Connect (Custom service, errors, light)', render: connectService('custom', 'light') },
  { name: 'Connect (Custom service, errors, dark)', render: connectService('custom', 'dark') },
  { name: 'Connect (Ollama, errors, light)', render: connectService('ollama', 'light') },
  { name: 'Connect (Ollama, errors, dark)', render: connectService('ollama', 'dark') },
  { name: 'Connect (Gemini, AI speed and cost, light)', render: connectGemini('light') },
  { name: 'Connect (Gemini, AI speed and cost, dark)', render: connectGemini('dark') },
  { name: 'Ask', render: page(AskPage) },
  { name: 'House detail (existing)', render: page(HouseDetailPage, { params: { id: 'h1' } }) },
  { name: 'House detail (new)', render: page(HouseDetailPage, { query: { lat: '12.9', lon: '77.6' } }) },
  { name: 'Moving in card', render: page(HouseMoveInCard, { inputs: { house: HOUSE }, whole: false }) },
  { name: 'Photo meta editor', render: page(PhotoMetaEditor, { inputs: { photo: PHOTO, n: 1, rooms: HOUSE.rooms }, whole: false }) },
  { name: 'House viewings card', render: page(HouseViewingsCard, { inputs: { houseId: 'h1' }, whole: false }) },
  { name: 'Second viewing prompt', render: page(SecondViewingPrompt, { inputs: { houseId: 'h1' }, whole: false }) },
];

let hadShowModal = false;
beforeEach(() => {
  // jsdom has no layout, so no scrolling either.
  Element.prototype.scrollIntoView = function () {
    return undefined;
  };
  // jsdom has no modal <dialog>: open and close are the attribute (and the close event).
  hadShowModal = typeof HTMLDialogElement.prototype.showModal === 'function';
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
    this.dispatchEvent(new Event('close'));
  };
});

afterEach(() => {
  TestBed.resetTestingModule();
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
  setOfflineActive(false);
  document.documentElement.lang = 'en';
  void hadShowModal;
});

const fmt = (v: Violation[]) => v.map((x) => `[${x.rule}] ${x.message}`);

describe('Drive a11y sweep coverage', () => {
  it('includes Unavailable, Connecting, NeedsEnrolment and Error', () => {
    const names = CASES.map((c) => c.name).join('\n').toLowerCase();
    for (const state of ['unavailable', 'connecting', 'needs enrolment', 'error']) {
      expect(names, state).toContain(`drive card (${state}`);
    }
  });
});

describe('accessibility sweep of the pages (TC-U-WEB-A11Y-3)', () => {
  for (const lang of ['en', 'hi', 'ta', 'te'] as const) {
    for (const c of CASES) {
      it(`${c.name} in ${lang} has names, labelled dialogs, valid ids and aria, and no mouse-only control`, async () => {
        const watch = watchClickListeners();
        let found: Violation[];
        let mouseOnly: Element[];
        let controls: number;
        try {
          const { fixture, page: isPage } = await c.render(lang);
          mouseOnly = watch.stop();
          const root = fixture.nativeElement as HTMLElement;
          found = audit(root, { page: isPage });
          controls = root.querySelectorAll(
            'button, a[href], input, select, textarea, dialog, [aria-labelledby], [role="status"]',
          ).length;
        } catch (e) {
          watch.stop();
          throw e;
        }
        // It rendered something to check, not an empty shell.
        expect(controls).toBeGreaterThan(0);
        expect(fmt(found)).toEqual([]);
        expect(mouseOnly.map((e) => e.outerHTML.slice(0, 80))).toEqual([]);
      });
    }
  }
});

/** Pages with a required field, submitted empty: the errors shown must be announced and the page must stay valid. */
const SUBMIT_CASES: { name: string; render: Case['render']; button: string; then?: string }[] = [
  { name: 'Ask', render: page(AskPage), button: '#ask-submit' },
  { name: 'Plan', render: page(PlanPage), button: '#plan-submit' },
  { name: 'Connect', render: page(ConnectPage), button: 'form .btn-primary, button[type="submit"]' },
  { name: 'Broker (new)', render: page(BrokerPage, { params: { id: 'new' } }), button: 'button.btn-primary' },
  { name: 'Viewing form', render: page(ViewingPage, { query: {} }), button: 'button.btn-primary' },
  { name: 'Areas', render: page(AreasPage), button: 'button.btn-primary', then: 'form button.btn-primary' },
  { name: 'Places', render: page(PlacesPage), button: 'button.btn-primary', then: 'form button.btn-primary' },
  { name: 'Criteria', render: page(CriteriaPage), button: 'button.btn-primary' },
  { name: 'Questions', render: page(QuestionsPage), button: 'button.btn-primary' },
  { name: 'House detail (new)', render: page(HouseDetailPage, { query: { lat: '12.9', lon: '77.6' } }), button: '.toolbar button.btn-primary' },
];

describe('accessibility sweep of the error states (TC-U-WEB-A11Y-8)', () => {
  for (const c of SUBMIT_CASES) {
    for (const lang of ['en', 'ta'] as const) {
      it(`${c.name} in ${lang}: submitted empty, its errors are announced and focus is on a field`, async () => {
        const { fixture } = await c.render(lang);
        const root = fixture.nativeElement as HTMLElement;
        const submit = root.querySelector<HTMLButtonElement>(c.button);
        if (!submit) return; // the page has no such action in this state; the render sweep above covers it
        submit.click();
        await settle(fixture);
        if (c.then) {
          root.querySelector<HTMLButtonElement>(c.then)!.click();
          await settle(fixture);
        }
        // Something was shown that says what is wrong (Connect has no required field to submit empty here).
        if (c.name !== 'Connect') expect(root.querySelectorAll('.error, .field-error, [aria-invalid="true"]').length).toBeGreaterThan(0);
        expect(fmt(audit(root))).toEqual([]);
        const active = document.activeElement;
        if (active && root.contains(active)) expect(active.matches('input, textarea, select, button, [tabindex]')).toBe(true);
      });
    }
  }
});

describe('a position that is missing (TC-U-WEB-A11Y-9)', () => {
  for (const [name, component, pre, button] of [
    ['Areas', AreasPage, 'area', 'form button.btn-primary'],
    ['Places', PlacesPage, 'place', 'form button.btn-primary'],
  ] as const) {
    it(`${name}: with a name but no position, focus goes to the latitude field, which the error describes`, async () => {
      const { fixture } = await page(component as Type<unknown>)('en');
      const root = fixture.nativeElement as HTMLElement;
      root.querySelector<HTMLButtonElement>('button.btn-primary')!.click();
      await settle(fixture);
      const field = root.querySelector<HTMLInputElement>(`#${pre}-name`)!;
      field.value = 'Indiranagar';
      field.dispatchEvent(new Event('input'));
      await settle(fixture);
      root.querySelector<HTMLButtonElement>(button)!.click();
      await settle(fixture);
      const lat = root.querySelector<HTMLInputElement>(`#${pre}-point-lat`)!;
      expect(document.activeElement).toBe(lat);
      expect(lat.getAttribute('aria-invalid')).toBe('true');
      const message = root.querySelector(`#${lat.getAttribute('aria-describedby')}`);
      expect(message?.textContent?.trim()).not.toBe('');
      expect(message?.closest('[role="alert"]')).not.toBeNull();
    });
  }
});

const q = <T extends HTMLElement>(root: ParentNode, selector: string) => root.querySelector<T>(selector)!;

describe('focus, language, announcements and the page shell (TC-U-WEB-A11Y-6)', () => {
  it('sets lang on <html> to the chosen language, for every language of the app', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const i18n = TestBed.inject(TranslationService);
    for (const lang of ['hi', 'ta', 'te', 'en'] as const) {
      await i18n.setLang(lang);
      expect(document.documentElement.lang).toBe(lang);
    }
  });

  it('the shell: a skip link first, a main that focus can be sent to, a labelled navigation and language picker', async () => {
    const { fixture } = await plain(App, false)('en');
    const root = fixture.nativeElement as HTMLElement;
    const first = root.querySelector<HTMLElement>('a[href], button, input, select');
    expect(first?.classList.contains('skip-link')).toBe(true);
    expect(first?.getAttribute('href')).toBe('#main');
    const main = q(root, 'main#main');
    expect(main.getAttribute('tabindex')).toBe('-1');
    first!.click();
    expect(document.activeElement).toBe(main);
    expect(q(root, 'nav').getAttribute('aria-label')).toBe('Main');
    const select = q<HTMLSelectElement>(root, '#lang-select');
    expect(select.labels?.length).toBe(1);
    expect([...select.options].map((o) => o.getAttribute('lang'))).toEqual(['en', 'hi', 'ta', 'te']);
  });

  it('the shell has one polite live region that speaks what the Announcer is told, in the chosen language', async () => {
    const { fixture } = await plain(App, false)('hi');
    const root = fixture.nativeElement as HTMLElement;
    const live = root.querySelector('[role="status"][aria-live="polite"]')!;
    expect(live.getAttribute('aria-atomic')).toBe('true');
    expect(live.textContent?.trim()).toBe('');
    TestBed.inject(Announcer).announce({ key: 'lang.changed' });
    await new Promise((resolve) => setTimeout(resolve, 150));
    fixture.detectChanges();
    expect(live.textContent?.trim()).not.toBe('');
    expect(live.textContent).not.toContain('lang.changed');
  });

  it('moves focus to the new page\'s heading after an in-app navigation', async () => {
    @Component({ template: '<h1>First</h1>' })
    class A {}
    @Component({ template: '<h1 id="second">Second</h1><p>text</p>' })
    class B {}
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([{ path: '', component: A }, { path: 'b', component: B }])] });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(App);
    await settle(fixture as ComponentFixture<unknown>);
    await TestBed.inject(Router).navigateByUrl('/');
    await settle(fixture as ComponentFixture<unknown>);
    await TestBed.inject(Router).navigateByUrl('/b');
    await settle(fixture as ComponentFixture<unknown>);
    expect(document.activeElement?.id).toBe('second');
  });

  it('the confirm dialog takes focus on its safe button, is modal and labelled, Esc answers Cancel and focus returns', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [ConfirmDialog], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang('en');
    const opener = document.createElement('button');
    opener.textContent = 'Delete';
    document.body.appendChild(opener);
    opener.focus();
    const fixture = TestBed.createComponent(ConfirmDialog);
    await settle(fixture as ComponentFixture<unknown>);
    const answer = TestBed.inject(ConfirmService).ask({ key: 'common.retry' }, { danger: true });
    await settle(fixture as ComponentFixture<unknown>);
    const dlg = q<HTMLDialogElement>(fixture.nativeElement, 'dialog');
    expect(dlg.hasAttribute('open')).toBe(true);
    expect(accessibleNameOf(dlg)).not.toBe('');
    // A destructive request starts on Cancel, not on the destructive button.
    expect(document.activeElement?.closest('dialog')).toBe(dlg);
    expect(document.activeElement?.textContent?.trim()).toBe('Cancel');
    dlg.dispatchEvent(new Event('cancel', { cancelable: true }));
    expect(await answer).toBe(false);
    await settle(fixture as ComponentFixture<unknown>);
    expect(document.activeElement).toBe(opener);
    opener.remove();
  });

  it('saving a house with a floor out of range keeps the page, shows the message and puts focus in the field', async () => {
    const { fixture } = await page(HouseDetailPage, { params: { id: 'h1' } })('en');
    const root = fixture.nativeElement as HTMLElement;
    const floor = q<HTMLInputElement>(root, '#house-floor');
    floor.value = '300';
    floor.dispatchEvent(new Event('input'));
    await settle(fixture);
    expect(floor.getAttribute('aria-invalid')).toBe('true');
    const message = q(root, '#house-floor-error');
    expect(message.getAttribute('role')).toBe('alert');
    expect(floor.getAttribute('aria-describedby')).toBe('house-floor-error');
    [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Save')!.click();
    await settle(fixture);
    expect(document.activeElement?.id).toBe('house-floor');
    expect(root.querySelector('.error')?.textContent).toContain('-5 to 200');
  });

  it('saving a house with no name puts focus in the name field, marked invalid', async () => {
    const { fixture } = await page(HouseDetailPage, { params: { id: 'h1' } })('en');
    const root = fixture.nativeElement as HTMLElement;
    const name = q<HTMLInputElement>(root, '#house-name');
    name.value = '';
    name.dispatchEvent(new Event('input'));
    await settle(fixture);
    [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Save')!.click();
    await settle(fixture);
    expect(document.activeElement).toBe(name);
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('name');
  });
});

describe('the page shell and the stylesheet (TC-U-WEB-A11Y-7)', () => {
  it('index.html declares the language, allows pinch zoom and links the Indic fallback fonts', () => {
    expect(indexHtml).toMatch(/<html lang="en">/);
    expect(indexHtml).toMatch(/name="viewport"/);
    const viewport = new DOMParser().parseFromString(indexHtml, 'text/html').querySelector('meta[name="viewport"]')?.getAttribute('content') ?? '';
    expect(viewport).not.toMatch(/user-scalable\s*=\s*(no|0)|maximum-scale/);
    // Self-hosted fonts: @font-face rules in styles.css, no external Google Fonts links (S4b-BL-73)
    expect(indexHtml).not.toContain('fonts.googleapis.com');
    expect(indexHtml).not.toContain('fonts.gstatic.com');
  });

  it('the font stack ends in the Indic fonts and Indic text gets more line height', () => {
    expect(styles).toMatch(/--font-sans:[^;]*'Noto Sans Devanagari'[^;]*'Noto Sans Tamil'[^;]*'Noto Sans Telugu'/);
    expect(styles).toMatch(/:lang\(hi\),\s*:lang\(ta\),\s*:lang\(te\)\s*\{\s*--leading: var\(--leading-indic\)/);
  });

  it('stops animations and transitions under prefers-reduced-motion, for every element', () => {
    const at = styles.indexOf('@media (prefers-reduced-motion: reduce)');
    expect(at).toBeGreaterThan(0);
    const block = styles.slice(at, styles.indexOf('/* Windows High Contrast'));
    expect(block).toMatch(/\*,\s*\*::before,\s*\*::after/);
    expect(block).toMatch(/animation-duration: 0\.01ms !important/);
    expect(block).toMatch(/transition-duration: 0\.01ms !important/);
    expect(block).toMatch(/scroll-behavior: auto !important/);
  });

  it('keeps a 3px focus ring in --focus, and 44px targets: the compact sizes grow on touch screens', () => {
    expect(styles).toMatch(/:focus-visible\s*\{\s*outline: 3px solid var\(--focus\)/);
    expect(styles).toMatch(/--target: 44px/);
    expect(styles).toMatch(/@media \(pointer: coarse\)\s*\{\s*\.btn-sm\s*\{\s*min-height: var\(--target\)/);
    expect(styles).toMatch(/@media \(pointer: coarse\)\s*\{\s*\.chip\s*\{\s*min-height: var\(--target\)/);
    // Date and time fields get the same size and text as the other fields (the live UI test found the date field at
    // 21px high and 13px text), and a plain link in a row of actions is a 44px target.
    expect(styles).toMatch(/input\[type='date'\],\s*input\[type='time'\],\s*input\[type='datetime-local'\],\s*select,\s*textarea\s*\{\s*width: 100%;\s*min-height: var\(--target\)/);
    expect(styles).toMatch(/@media \(pointer: coarse\)\s*\{\s*\.actions > a:not\(\.btn\)\s*\{[^}]*min-height: var\(--target\)/);
    expect(styles).toMatch(/\.btn\s*\{[^}]*min-height: var\(--target\)/);
  });

  it('sizes text in rem, so browser text size and zoom reach it', () => {
    const px = [...styles.matchAll(/font-size:\s*[0-9.]+px/g)].map((m) => m[0]);
    expect(px).toEqual([]);
  });
});

describe('what a screen reader says (TC-U-WEB-A11Y-10)', () => {
  /** The code point ranges a language's words may hold: its own script, Latin (names, units), digits and symbols. */
  const OWN: Record<'hi' | 'ta' | 'te', [number, number]> = { hi: [0x900, 0x97f], ta: [0xb80, 0xbff], te: [0xc00, 0xc7f] };
  const COMMON = (cp: number) =>
    cp < 0x250 || (cp >= 0x2000 && cp <= 0x2bff) || (cp >= 0x3000 && cp <= 0x303f) || (cp >= 0xfe00 && cp <= 0xfe0f) || (cp >= 0xff00 && cp <= 0xffef) || cp >= 0x1f000;

  it('no Hindi, Tamil or Telugu string holds a letter of another script (a screen reader would switch voice)', () => {
    const stray: string[] = [];
    for (const [lang, dict] of Object.entries({ hi, ta, te }) as ['hi' | 'ta' | 'te', Record<string, string>][]) {
      const [lo, hi2] = OWN[lang];
      for (const [key, value] of Object.entries(dict)) {
        for (const ch of value) {
          const cp = ch.codePointAt(0)!;
          if (!COMMON(cp) && !(cp >= lo && cp <= hi2) && cp !== 0x200c && cp !== 0x200d) {
            stray.push(`${lang} ${key}: ${ch}`);
            break;
          }
        }
      }
    }
    expect(stray).toEqual([]);
  });

  it('a disclosure summary keeps its triangle (not display: flex or grid) and is a 44px target', () => {
    for (const [name, css] of [['ask', askCss], ['map', mapCss], ['house', houseCss]] as const) {
      const rule = /summary\s*\{[^}]*\}/.exec(css)?.[0] ?? '';
      expect(rule, name).toContain('min-height: var(--target)');
      expect(rule, name).not.toMatch(/display:\s*(flex|grid|inline-flex)/);
    }
  });

  it('on short phones the legend is placed at the top of the map, the actions at the bottom (S4b-BL-49)', () => {
    const block = /@media \(max-width: 760px\) and \(max-height: 700px\) and \(orientation: portrait\) \{([\s\S]*)\n\}/.exec(mapCss)?.[1] ?? '';
    expect(block).not.toBe('');
    expect(/\.legend \{[^}]*position: absolute;[^}]*top: var\(--space-3\);/.test(block)).toBe(true);
    // Clear of MapLibre's control column at the end, as the add-mode hint is.
    expect(/\.legend \{[^}]*max-width: calc\(100% - var\(--space-3\) - var\(--target\)/.test(block)).toBe(true);
    expect(/\.map-stack \{[^}]*position: static;/.test(block)).toBe(true);
    expect(/\.map-actions,\s*\.map-stack\.with-legend \.map-actions \{[^}]*bottom: var\(--space-2\);/.test(block)).toBe(true);
  });
});

function accessibleNameOf(el: Element): string {
  return audit(el.parentElement!).some((v) => v.rule === 'dialog-name') ? '' : 'named';
}
