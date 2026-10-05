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

import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import type { HouseDraft } from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import type { BrokerRow } from '../../shared/broker';
import { TitleOverride } from '../../i18n/i18n-title.strategy';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';
import { DEFAULT_SCORING, scoringOf } from '../../shared/scoring';
import type { CriterionRow, Scoring } from '../../shared/scoring';
import { draftKey, writeDraft } from './draft-store';
import { HouseDetailPage } from './house-detail-page';

const HOUSE: HouseDto = {
  id: '5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10',
  label: 'Blue gate 2BHK',
  lat: 12.9716,
  lon: 77.5946,
  status: 'NEW',
  checklist: {},
  deleted: false,
  createdAt: '2026-09-20T10:00:00.000Z',
  updatedAt: '2026-09-20T10:00:00.000Z',
  syncVersion: 0,
};

/** What a test controls of the page's data: every call answers with what the test hands it. */
interface Fakes {
  houses?: () => Observable<HouseDto[]>;
  saveHouse?: () => Observable<HouseDto>;
  reverse?: () => Observable<unknown>;
  search?: (place: string, language: string) => Observable<unknown>;
  extractListing?: () => Observable<HouseDraft>;
  aiEnabled?: boolean;
  brokers?: () => Observable<BrokerRow[]>;
  scoring?: () => Observable<Scoring>;
}

/** Lets the page's promise chains and afterNextRender callbacks run. */
async function settle(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

function create(
  params: Record<string, string>,
  query: Record<string, string>,
  fakes: Fakes,
): { fixture: ComponentFixture<HouseDetailPage>; announce: ReturnType<typeof vi.spyOn>; setTitle: ReturnType<typeof vi.spyOn> } {
  const api = {
    houses: fakes.houses ?? (() => of([])),
    brokers: fakes.brokers ?? (() => of([])),
    scoring: fakes.scoring ?? (() => of(DEFAULT_SCORING)),
    questions: () => of([]),
    house: () => of(HOUSE),
    visits: () => of([]),
    viewingsOf: () => of([]),
    areas: () => of([]),
    areaNotes: () => of([]),
    places: () => of([]),
    settled: signal(0),
    photos: () => of([]),
    saveHouse: fakes.saveHouse ?? (() => of(HOUSE)),
  };
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([]),
      { provide: LocalDataService, useValue: api },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: convertToParamMap(params), queryParamMap: convertToParamMap(query) } },
      },
      { provide: GeocodeService, useValue: { reverse: fakes.reverse ?? (() => of({})), search: fakes.search ?? (() => of(null)) } },
      {
        provide: AiService,
        useValue: { enabled: signal(fakes.aiEnabled ?? false), usesOwnKey: signal(false), extractListing: fakes.extractListing ?? (() => of()) },
      },
    ],
  });
  TestBed.inject(TranslationService).setLang('en');
  const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
  const setTitle = vi.spyOn(TestBed.inject(TitleOverride).message, 'set');
  return { fixture: TestBed.createComponent(HouseDetailPage), announce, setTitle };
}

afterEach(() => {
  delete (navigator as { geolocation?: Geolocation }).geolocation;
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
});

/**
 * The new-house form opened with no position (the share target, a bookmarked `/houses/new`) reads the houses to
 * start at the newest one. Rule (f) (S4b-BL-7): when the user has left the page by the time that read answers, the
 * answer is dropped, whether it resolves or rejects — no draft is opened (and so no stored draft is put back with
 * "Draft restored"), and no document title is set on whatever page the user went to.
 */
describe('HouseDetailPage: a new house with no position, left before the houses are read', () => {
  function leftBeforeTheRead(): { houses: Subject<HouseDto[]>; announce: ReturnType<typeof vi.spyOn>; setTitle: ReturnType<typeof vi.spyOn>; draft: () => HouseDto | null } {
    // Unsaved edits a discarded tab left: had a draft been opened, they would be put back and announced.
    writeDraft(draftKey(null, null, null), { draft: { ...HOUSE, label: 'Typed before the tab was discarded' }, locationSet: true });
    const houses = new Subject<HouseDto[]>();
    const { fixture, announce, setTitle } = create({}, {}, { houses: () => houses });
    fixture.detectChanges();
    const page = fixture.componentInstance as unknown as { draft: () => HouseDto | null };
    expect(houses.observed).toBe(true);
    fixture.destroy();
    return { houses, announce, setTitle, draft: () => page.draft() };
  }

  function expectNothingDone(r: ReturnType<typeof leftBeforeTheRead>): void {
    expect(r.draft()).toBeNull();
    expect(r.announce).not.toHaveBeenCalledWith({ key: 'house.draftRestored' });
    expect(r.setTitle.mock.calls.filter((call: unknown[]) => call[0] !== null)).toEqual([]);
    expect(TestBed.inject(TitleOverride).message()).toBeNull();
  }

  it('opens no draft when the read resolves after the page is gone', async () => {
    const r = leftBeforeTheRead();
    r.houses.next([HOUSE]);
    r.houses.complete();
    await settle();
    expectNothingDone(r);
  });

  it('opens no draft when the read rejects after the page is gone', async () => {
    const r = leftBeforeTheRead();
    r.houses.error(new Error('IndexedDB went away'));
    await settle();
    expectNothingDone(r);
  });
});

/**
 * S4b-BL-2: a retry keeps the last failure in place, drawn as being updated (`.refresh-slot.stale`, the bar named by
 * the run), until the run ends; the same node stays (it is keyed on the run that failed), and the run's end replaces
 * or removes it. So nothing below the card jumps up and back.
 */
describe('HouseDetailPage: a failure card kept while the next run goes', () => {
  function button(host: HTMLElement, label: string): HTMLButtonElement {
    const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === label);
    if (!found) throw new Error(`no button "${label}"`);
    return found;
  }

  /** The card's slot, the card and the bar, as the page shows them now. */
  function slotOf(card: Element | null): { stale: boolean; bar: string | null } {
    const slot = card?.closest('.refresh-slot') ?? null;
    return {
      stale: slot?.classList.contains('stale') ?? false,
      bar: slot?.querySelector('.refresh-bar[role="progressbar"]')?.getAttribute('aria-label') ?? null,
    };
  }

  function t(msg: Msg): string {
    return TestBed.inject(TranslationService).t(msg.key, msg.params);
  }

  it('keeps "Save failed" while saving again, and removes it when the save ends', async () => {
    const saves: Subject<HouseDto>[] = [];
    const { fixture } = create({ id: HOUSE.id }, {}, {
      saveHouse: () => {
        const s = new Subject<HouseDto>();
        saves.push(s);
        return s;
      },
    });
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const save = button(host, t({ key: 'house.save' }));

    save.click();
    await fixture.whenStable();
    saves[0].error(new Error('disk'));
    await settle();
    await fixture.whenStable();
    const card = host.querySelector('.error[role="alert"]');
    expect(card?.textContent).toContain('disk');
    expect(slotOf(card)).toEqual({ stale: false, bar: null });

    save.click();
    await fixture.whenStable();
    expect(host.querySelector('.error[role="alert"]')).toBe(card);
    expect(slotOf(card)).toEqual({ stale: true, bar: t({ key: 'house.saving' }) });

    saves[1].next(HOUSE);
    saves[1].complete();
    await settle();
    await fixture.whenStable();
    expect(host.querySelector('.error[role="alert"]')).toBeNull();
  });

  it('keeps a location failure while locating again, and removes it when the location is found', async () => {
    const answers: { ok: PositionCallback; fail: PositionErrorCallback }[] = [];
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: (ok: PositionCallback, fail: PositionErrorCallback) => answers.push({ ok, fail }) },
    });
    const { fixture } = create({ id: HOUSE.id }, {}, {});
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const locate = button(host, t({ key: 'house.useMyLocation' }));

    locate.click();
    answers[0].fail({ code: 3, message: 'timeout' } as GeolocationPositionError);
    await fixture.whenStable();
    const card = [...host.querySelectorAll('p.error')].find((p) => p.textContent?.trim() === t({ key: 'house.locationFailed' }));
    expect(card).toBeDefined();
    expect(slotOf(card!)).toEqual({ stale: false, bar: null });

    locate.click();
    await fixture.whenStable();
    expect(card!.isConnected).toBe(true);
    expect(slotOf(card!)).toEqual({ stale: true, bar: t({ key: 'house.locating' }) });

    answers[1].ok({ coords: { latitude: 12.98, longitude: 77.6 } } as GeolocationPosition);
    await fixture.whenStable();
    expect(card!.isConnected).toBe(false);
  });

  /** Slice 1a, FR-068: how the pin was placed travels with the house, and the person can call it approximate. */
  it('starts a map-placed house as MAP, makes it GPS on "Use my location", and APPROX by the switch', async () => {
    const answers: { ok: PositionCallback }[] = [];
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: (ok: PositionCallback) => answers.push({ ok }) },
    });
    const saved: HouseDto[] = [];
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {
      saveHouse: () => {
        saved.push((fixture.componentInstance as unknown as { draft: () => HouseDto }).draft());
        return of(HOUSE);
      },
    });
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const draft = () => (fixture.componentInstance as unknown as { draft: () => HouseDto }).draft();
    expect(draft().locationSource).toBe('MAP');
    expect(draft().cost).toEqual({});

    button(host, t({ key: 'house.useMyLocation' })).click();
    answers[0].ok({ coords: { latitude: 12.98, longitude: 77.6 } } as GeolocationPosition);
    await fixture.whenStable();
    expect(draft().locationSource).toBe('GPS');

    const approx = host.querySelector<HTMLInputElement>('#house-approx')!;
    approx.click();
    await fixture.whenStable();
    expect(draft().locationSource).toBe('APPROX');
    approx.click();
    await fixture.whenStable();
    expect(draft().locationSource).toBe('GPS');

    // The Cost section: a sale hides the rent-only fields; the computed line follows the typed values.
    expect(host.querySelector('#cost-deposit')).not.toBeNull();
    const name = host.querySelector<HTMLInputElement>('#house-name')!;
    name.value = 'Blue gate';
    name.dispatchEvent(new Event('input'));
    const price = host.querySelector<HTMLInputElement>('#house-price')!;
    price.value = '32000';
    price.dispatchEvent(new Event('input'));
    const deposit = host.querySelector<HTMLInputElement>('#cost-deposit')!;
    deposit.value = '64000';
    deposit.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(host.querySelector('.cost-line')?.textContent).toContain(t({ key: 'cost.lineMoveIn', params: { v: '₹96,000' } }));
  });

  /** Slice 1b: the Broker select right after Contact; a broker fills the contact and locks the two fields. */
  it('offers the brokers in a select, fills the contact from the one chosen, and unlinks on None', async () => {
    const brokers: BrokerRow[] = [
      { id: 'b-2', updatedAt: null, broker: { name: 'Zed' } },
      { id: 'b-1', updatedAt: null, broker: { name: 'Ravi Kumar', phone: '+91 98400 11111', agency: 'Adyar Homes' } },
    ];
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, { brokers: () => of(brokers) });
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const draft = () => (fixture.componentInstance as unknown as { draft: () => HouseDto }).draft();
    const select = host.querySelector<HTMLSelectElement>('#house-broker')!;
    expect([...select.options].map((o) => o.textContent?.trim())).toEqual([t({ key: 'house.brokerNone' }), 'Ravi Kumar (Adyar Homes)', 'Zed']);

    select.value = 'b-1';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(draft().brokerId).toBe('b-1');
    expect(draft().contactName).toBe('Ravi Kumar');
    expect(draft().contactPhone).toBe('+91 98400 11111');
    expect(host.querySelector<HTMLInputElement>('#house-contact')!.readOnly).toBe(true);

    select.value = '';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(draft().brokerId).toBeNull();
    expect(draft().contactName).toBe('Ravi Kumar');
    expect(host.querySelector<HTMLInputElement>('#house-contact')!.readOnly).toBe(false);

    button(host, t({ key: 'house.brokerNew' })).click();
    await fixture.whenStable();
    expect(draft().brokerId).toBeNull();
    expect(draft().contactName).toBeNull();
    expect(draft().contactPhone).toBeNull();
  });

  it('keeps "Address lookup failed" while looking up again, and removes it when the lookup answers', async () => {
    const lookups: Subject<unknown>[] = [];
    const { fixture } = create({ id: HOUSE.id }, {}, {
      reverse: () => {
        const s = new Subject<unknown>();
        lookups.push(s);
        return s;
      },
    });
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const lookup = button(host, t({ key: 'house.fillAddress' }));

    lookup.click();
    lookups[0].error(new Error('nominatim down'));
    await fixture.whenStable();
    const card = [...host.querySelectorAll('p.error')].find((p) => p.textContent?.includes('nominatim down'));
    expect(card).toBeDefined();

    lookup.click();
    await fixture.whenStable();
    expect(card!.isConnected).toBe(true);
    expect(slotOf(card!)).toEqual({ stale: true, bar: t({ key: 'house.lookingUp' }) });

    lookups[1].next({});
    lookups[1].complete();
    await fixture.whenStable();
    expect(card!.isConnected).toBe(false);
  });

  it('keeps "Could not read the listing" while reading again, and removes it when the listing is read', async () => {
    const reads: Subject<HouseDraft>[] = [];
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {
      aiEnabled: true,
      extractListing: () => {
        const s = new Subject<HouseDraft>();
        reads.push(s);
        return s;
      },
    });
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const text = host.querySelector<HTMLTextAreaElement>('#listing-text')!;
    text.value = '2BHK near the metro, Rs 32,000 a month';
    text.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    const fill = button(host, t({ key: 'listingFill.submit' }));

    fill.click();
    reads[0].error(new Error('model busy'));
    await fixture.whenStable();
    const card = host.querySelector('.listing-fill p.error');
    expect(card?.textContent).toContain('model busy');

    fill.click();
    await fixture.whenStable();
    expect(card!.isConnected).toBe(true);
    expect(slotOf(card!)).toEqual({ stale: true, bar: t({ key: 'listingFill.working' }) });

    reads[1].next({
      label: null,
      address: null,
      street: null,
      locality: null,
      price: 32000,
      priceType: 'RENT',
      bedrooms: 2,
      areaSqft: null,
      contactName: null,
      contactPhone: null,
      listingUrl: null,
      notes: null,
      amenities: [],
      warnings: [],
    });
    reads[1].complete();
    await fixture.whenStable();
    expect(card!.isConnected).toBe(false);
  });
});

/** Slice 2 (docs/11 5.4): the checklist lists the active criteria, the coverage line and the must-have warnings. */
describe('HouseDetailPage: the checklist under the effective scoring (slice 2)', () => {
  const PETS: CriterionRow = {
    key: 'c_1a2b3c4d',
    updatedAt: null,
    criterion: { key: 'c_1a2b3c4d', label: 'Pets allowed', weight: 2, mustHave: false, minScore: 3, sort: 10 },
  };
  const NOISE: CriterionRow = { key: 'noise', updatedAt: null, criterion: { key: 'noise', weight: 0, mustHave: false, minScore: 3, sort: 5, archived: true } };
  const POWER_IGNORED: CriterionRow = { key: 'power', updatedAt: null, criterion: { key: 'power', weight: 0, mustHave: false, minScore: 3, sort: 1 } };
  const WATER_MUST: CriterionRow = { key: 'water', updatedAt: null, criterion: { key: 'water', weight: 3, mustHave: true, minScore: 4, sort: 0 } };
  const SECURITY_MUST: CriterionRow = { key: 'security', updatedAt: null, criterion: { key: 'security', weight: 2, mustHave: true, minScore: 4, sort: 6 } };

  async function open(rows: CriterionRow[], checklist: Record<string, number>, rating: number | null = null) {
    const house: HouseDto = { ...HOUSE, checklist, rating };
    TestBed.resetTestingModule();
    const { fixture } = create({ id: house.id }, {}, { scoring: () => of(scoringOf(rows, [])) });
    (fixture.componentInstance as unknown as { api: { house: () => Observable<HouseDto> } }).api.house = () => of(house);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const legends = (host: HTMLElement) => [...host.querySelectorAll('.check-row > legend')].map((l) => l.textContent?.replace(/\s+/g, ' ').trim());

  it('lists the active criteria in the person\'s order: a custom one by its label, an ignored one marked, an archived one hidden', async () => {
    const host = await open([PETS, NOISE, POWER_IGNORED], { noise: 2 });
    const list = legends(host);
    expect(list).toHaveLength(10);
    expect(list[0]).toBe('Water supply');
    expect(list[1]).toBe('Power backup (ignored)');
    expect(list).not.toContain('Quiet (low noise)');
    expect(list[9]).toBe('Pets allowed');
    // Every listed criterion is still a group of 0..5 and "not scored".
    expect(host.querySelectorAll('input[name="check-c_1a2b3c4d"]')).toHaveLength(7);
  });

  it('says how many of the criteria that matter are scored', async () => {
    const host = await open([], { water: 5, parking: 4 });
    expect(host.querySelector('#coverage-line')?.textContent).toContain('Scored 2 of 10 that matter');
    const none = await open([], {});
    expect(none.querySelector('#coverage-line')?.textContent).toContain('Scored 0 of 10 that matter');
  });

  it('warns about a must-have scored below its minimum, and only names one not checked yet', async () => {
    const host = await open([WATER_MUST, SECURITY_MUST], { water: 2 });
    const text = host.textContent ?? '';
    expect(text).toContain('Must-have missed: Water supply');
    expect(text).toContain('Not checked yet: Safety and security');
    const ok = await open([WATER_MUST, SECURITY_MUST], { water: 4, security: 5 });
    expect(ok.textContent).not.toContain('Must-have missed');
    expect(ok.textContent).not.toContain('Not checked yet');
  });

  it('shows the overall score under the person\'s weights and rating share', async () => {
    const host = await open([{ key: 'water', updatedAt: null, criterion: { key: 'water', weight: 3, mustHave: false, minScore: 3, sort: 0 } }], { water: 5, power: 1 });
    // (3*5 + 2*1) / 5 = 3.4
    expect(host.querySelector('.score b')?.textContent?.trim()).toBe('3.4');
  });
});

/** Slice 4a (docs/11 5.22, 5.23): the house page shows the notes that reach the house and its distances to my places. */
describe('HouseDetailPage: area notes and distances (slice 4a)', () => {
  async function open(house: HouseDto, over: Record<string, unknown>) {
    TestBed.resetTestingModule();
    const { fixture } = create({ id: house.id }, {}, {});
    const api = (fixture.componentInstance as unknown as { api: Record<string, unknown> }).api;
    api['house'] = () => of(house);
    Object.assign(api, over);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const AREA = { id: 'a_1f2e3d4c', name: 'Adyar', lat: 12.9716, lon: 77.5946, radiusM: 500, enabled: true };
  const NOTE = { id: 'n_11223344', updatedAt: '2026-09-04T00:00:00.000Z', note: { id: 'n_11223344', areaId: 'a_1f2e3d4c', text: 'Water tanker every morning' } };
  const OFFICE = { id: 'p_0a1b2c3d', name: 'Office', lat: 13.0, lon: 77.6 };

  it('shows the Area notes card with the note and its source, and the Distances card with the km and the walk', async () => {
    const host = await open(HOUSE, { areas: () => of([AREA]), areaNotes: () => of([NOTE]), places: () => of([OFFICE]) });
    const notes = host.querySelector('app-house-area-notes-card');
    expect(notes?.querySelector('h2')?.textContent?.trim()).toBe('Area notes');
    expect(notes?.textContent).toContain('Water tanker every morning');
    expect(notes?.textContent).toContain('Area: Adyar');
    expect(host.querySelector('app-house-distances-card h2')?.textContent?.trim()).toBe('Distances');
    expect(host.querySelector('app-house-distances-card li')?.textContent?.trim()).toBe('Office: 3.2 km, about 53 min on foot');
  });

  it('hides Distances without places and offers no street note for a house with no street', async () => {
    const host = await open(HOUSE, {});
    expect(host.querySelector('app-house-distances-card section')).toBeNull();
    const labels = [...host.querySelectorAll('app-house-area-notes-card button')].map((b) => b.textContent?.trim());
    expect(labels).toEqual(['Add a note for an area']);
    const withStreet = await open({ ...HOUSE, street: 'MG Road' }, {});
    expect([...withStreet.querySelectorAll('app-house-area-notes-card button')].map((b) => b.textContent?.trim())).toEqual([
      'Add a note for this street',
      'Add a note for an area',
    ]);
  });
});

/**
 * S4b-BL-83: a new house with no position offers *Find “<locality>” on the map*; the lookup runs only on the tap, puts
 * the pin at the place marked approximate for the person to move, and a name it does not know says so.
 */
describe('HouseDetailPage: finding the locality on the map', () => {
  function button(host: HTMLElement, label: string): HTMLButtonElement {
    const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === label);
    if (!found) throw new Error(`no button "${label}"`);
    return found;
  }

  function t(msg: Msg): string {
    return TestBed.inject(TranslationService).t(msg.key, msg.params);
  }

  async function newHouseWithLocality(search: (place: string, language: string) => Observable<unknown>) {
    const { fixture } = create({}, {}, { search });
    fixture.detectChanges();
    await settle();
    await fixture.whenStable();
    const host = fixture.nativeElement as HTMLElement;
    const locality = host.querySelector<HTMLInputElement>('#house-locality')!;
    locality.value = 'Indiranagar';
    locality.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    const page = fixture.componentInstance as unknown as { draft: () => HouseDto };
    return { fixture, host, draft: () => page.draft() };
  }

  it('looks the place up only on the tap and puts an approximate pin there', async () => {
    const search = vi.fn((place: string, language: string) => of({ lat: 12.9784, lon: 77.6408, label: `${place} (${language})` }));
    const { fixture, host, draft } = await newHouseWithLocality(search);
    expect(search).not.toHaveBeenCalled();
    button(host, t({ key: 'house.findPlace', params: { place: 'Indiranagar' } })).click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(search).toHaveBeenCalledWith('Indiranagar', 'en');
    expect([draft().lat, draft().lon, draft().locationSource]).toEqual([12.9784, 77.6408, 'APPROX']);
    expect(host.textContent).toContain(t({ key: 'house.placeFound', params: { place: 'Indiranagar' } }));
    expect(host.textContent).toContain(t({ key: 'house.lookupNote' }));
  });

  it('says so when the place is not found, and leaves the pin unset', async () => {
    const { fixture, host, draft } = await newHouseWithLocality(() => of(null));
    const before = [draft().lat, draft().lon];
    button(host, t({ key: 'house.findPlace', params: { place: 'Indiranagar' } })).click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect([draft().lat, draft().lon]).toEqual(before);
    expect(host.textContent).toContain(t({ key: 'house.placeNotFound', params: { place: 'Indiranagar' } }));
  });
});

/** S4b-BL-N13: listingHref validation for malicious URL schemes. */
describe('HouseDetailPage: listingHref URL validation', () => {
  it('accepts and returns valid https URLs unchanged', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    const page = fixture.componentInstance as unknown as { listingHref: (url: string | null | undefined) => string | null };
    expect(page.listingHref('https://a.com/x')).toBe('https://a.com/x');
  });

  it('accepts and returns valid http URLs unchanged', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    const page = fixture.componentInstance as unknown as { listingHref: (url: string | null | undefined) => string | null };
    expect(page.listingHref('HTTP://a.com')).toBe('HTTP://a.com');
  });

  it('converts bare domains to https URLs', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    const page = fixture.componentInstance as unknown as { listingHref: (url: string | null | undefined) => string | null };
    expect(page.listingHref('example.com/x')).toBe('https://example.com/x');
  });

  it('blocks intent scheme URLs', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    const page = fixture.componentInstance as unknown as { listingHref: (url: string | null | undefined) => string | null };
    expect(page.listingHref('intent://x#Intent;scheme=a;end')).toBeNull();
  });

  it('blocks javascript scheme URLs', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    const page = fixture.componentInstance as unknown as { listingHref: (url: string | null | undefined) => string | null };
    expect(page.listingHref('javascript:alert(1)')).toBeNull();
  });

  it('blocks ms-word scheme URLs', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    const page = fixture.componentInstance as unknown as { listingHref: (url: string | null | undefined) => string | null };
    expect(page.listingHref('ms-word:ofe|u|https://a')).toBeNull();
  });

  it('blocks data scheme URLs', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    const page = fixture.componentInstance as unknown as { listingHref: (url: string | null | undefined) => string | null };
    expect(page.listingHref('data:text/html,x')).toBeNull();
  });

  it('trims whitespace from URLs', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    const page = fixture.componentInstance as unknown as { listingHref: (url: string | null | undefined) => string | null };
    expect(page.listingHref('  https://a.com  ')).toBe('https://a.com');
  });

  it('does not show the Open listing link when listingUrl has a malicious scheme', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const input = host.querySelector<HTMLInputElement>('#house-listing')!;
    input.value = 'intent://x';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    const link = host.querySelector('div.inline a.btn');
    expect(link).toBeNull();
  });

  it('shows the Open listing link when listingUrl has a valid https scheme', async () => {
    const { fixture } = create({}, { lat: '12.9716', lon: '77.5946' }, {});
    await fixture.whenStable();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const input = host.querySelector<HTMLInputElement>('#house-listing')!;
    input.value = 'https://a.com';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    const link = host.querySelector('div.inline a.btn');
    expect(link).not.toBeNull();
    expect(link?.getAttribute('href')).toBe('https://a.com');
  });
});
