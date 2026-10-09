
// The house page's location card, through the page's DOM: the typed coordinates, the pin moved on the map, the
// Approximate switch, Use my location, Find, Fill address from map, the start of a new house with no position, and the
// refusal to save with the position unset (S4b-BL-168, slice 9). Written before the code moved, and run on the old
// code first.
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import type { ConfirmAnswer } from '../../core/confirm.service';
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import { TranslationService } from '../../i18n/translation.service';
import { LocationMap } from '../../shared/location-map';
import { COUNTRY_VIEW, MAP_VIEW_KEY } from '../../shared/map-center';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { HouseDetailPage } from './house-detail-page';

interface Page {
  draft: () => HouseDto;
  save: () => void;
}

const SAVED: HouseDto = {
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

interface Setup {
  /** Open the saved house instead of a new one. */
  saved?: HouseDto;
  /** The new house's query: a pin in Kochi by default, none for the share target. */
  query?: Record<string, string>;
  houses?: () => Observable<HouseDto[]>;
  reverse?: (lat: number, lon: number) => Observable<unknown>;
  search?: (place: string, language: string) => Observable<unknown>;
  /** What the person answers when asked to replace typed address parts. */
  answer?: ConfirmAnswer;
  geolocation?: boolean;
}

const KOCHI = { lat: '9.9312', lon: '76.2673' };

/** The location requests the browser was given, answered by the test. */
let located: { ok: PositionCallback; fail: PositionErrorCallback }[] = [];

async function open(setup: Setup = {}) {
  located = [];
  if (setup.geolocation !== false) {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: { getCurrentPosition: (ok: PositionCallback, fail: PositionErrorCallback) => located.push({ ok, fail }) },
    });
  }
  const saveHouse = vi.fn((body: HouseDto) => of(body));
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: {
          houses: setup.houses ?? (() => of([])),
          brokers: () => of([]),
          scoring: () => of(DEFAULT_SCORING),
          questions: () => of([]),
          house: () => of(setup.saved ?? SAVED),
          visits: () => of([]),
          viewingsOf: () => of([]),
          areas: () => of([]),
          areaNotes: () => of([]),
          places: () => of([]),
          settled: signal(0),
          photos: () => of([]),
          saveHouse,
        },
      },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            paramMap: convertToParamMap(setup.saved ? { id: setup.saved.id } : {}),
            queryParamMap: convertToParamMap(setup.query ?? KOCHI),
          },
        },
      },
      {
        provide: GeocodeService,
        useValue: { reverse: setup.reverse ?? (() => of({})), search: setup.search ?? (() => of(null)) },
      },
      { provide: AiService, useValue: { enabled: signal(false), usesOwnKey: signal(false), ownHost: signal(''), extractListing: () => of() } },
    ],
  });
  TestBed.inject(TranslationService).setLang('en');
  const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
  vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const choose = vi.spyOn(TestBed.inject(ConfirmService), 'choose').mockResolvedValue(setup.answer ?? 'confirm');
  const fixture = TestBed.createComponent(HouseDetailPage);
  await settled(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, page: fixture.componentInstance as unknown as Page, announce, choose, saveHouse };
}

async function settled(fixture: ComponentFixture<HouseDetailPage>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

async function type(fixture: ComponentFixture<HouseDetailPage>, selector: string, value: string, event = 'input'): Promise<void> {
  const el = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(selector)!;
  el.value = value;
  el.dispatchEvent(new Event(event));
  await settled(fixture);
}

function t(key: string, params?: Record<string, string | number>): string {
  return TestBed.inject(TranslationService).t(key as never, params);
}

function button(host: HTMLElement, label: string): HTMLButtonElement {
  const found = [...host.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === label);
  if (!found) throw new Error(`no button "${label}"`);
  return found;
}

const hasFind = (host: HTMLElement) => [...host.querySelectorAll('button')].some((b) => b.textContent?.includes('Find'));
const lat = (host: HTMLElement) => host.querySelector<HTMLInputElement>('#house-lat')!;
const lon = (host: HTMLElement) => host.querySelector<HTMLInputElement>('#house-lon')!;
const approx = (host: HTMLElement) => host.querySelector<HTMLInputElement>('#house-approx')!;
const unset = (host: HTMLElement) => host.querySelector('#location-unset');
const coordsError = (host: HTMLElement) => host.querySelector('#coords-error');
const locationFail = (host: HTMLElement) =>
  [...host.querySelectorAll('.refresh-slot p.error')].map((p) => p.textContent?.trim());
const mapOf = (fixture: ComponentFixture<HouseDetailPage>) =>
  fixture.debugElement.query(By.directive(LocationMap)).componentInstance as LocationMap;
const pin = (page: Page) => [page.draft().lat, page.draft().lon, page.draft().locationSource];
const names = (...keys: string[]) => TestBed.inject(TranslationService).list(keys.map((k) => t(k)));

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  Element.prototype.scrollIntoView = () => undefined;
});

afterEach(() => {
  delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
  delete (navigator as { geolocation?: Geolocation }).geolocation;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  sessionStorage.clear();
  localStorage.clear();
});

describe('HouseDetailPage: the pin and the typed coordinates', () => {
  it('moves the pin to a valid typed latitude, keeping the longitude, and marks it as set by hand', async () => {
    const { fixture, host, page } = await open();
    await type(fixture, '#house-lat', ' 10.5 ', 'change');
    expect(pin(page)).toEqual([10.5, 76.2673, 'MAP']);
    expect(coordsError(host)).toBeNull();
    await type(fixture, '#house-lon', '76.123456789', 'change');
    expect(pin(page)).toEqual([10.5, 76.123457, 'MAP']);
  });

  it('keeps an invalid typed value in the field, marks it, names the reason, and leaves the pin where it was', async () => {
    const { fixture, host, page } = await open();
    await type(fixture, '#house-lat', '95', 'change');
    expect(lat(host).value).toBe('95');
    expect(pin(page)).toEqual([9.9312, 76.2673, 'MAP']);
    expect(lat(host).getAttribute('aria-invalid')).toBe('true');
    expect(lon(host).getAttribute('aria-invalid')).toBeNull();
    expect(lat(host).getAttribute('aria-describedby')).toBe('coords-error');
    expect(lon(host).getAttribute('aria-describedby')).toBeNull();
    expect(coordsError(host)?.textContent?.trim()).toBe(t('house.coordsInvalid'));
    await type(fixture, '#house-lon', 'abc', 'change');
    expect(lon(host).getAttribute('aria-describedby')).toBe('coords-error');
    expect(pin(page)).toEqual([9.9312, 76.2673, 'MAP']);
    await type(fixture, '#house-lon', '200', 'change');
    expect(lon(host).getAttribute('aria-invalid')).toBe('true');
  });

  it('accepts 90 as a latitude and 180 as a longitude, the edges of each range', async () => {
    const { fixture, host, page } = await open();
    await type(fixture, '#house-lat', '90', 'change');
    await type(fixture, '#house-lon', '180', 'change');
    expect(coordsError(host)).toBeNull();
    expect(pin(page)).toEqual([90, 180, 'MAP']);
    await type(fixture, '#house-lat', '-90', 'change');
    await type(fixture, '#house-lon', '-180', 'change');
    expect(pin(page)).toEqual([-90, -180, 'MAP']);
  });

  it('clears the mark for an axis once it is valid, while the other stays marked', async () => {
    const { fixture, host } = await open();
    await type(fixture, '#house-lat', '95', 'change');
    await type(fixture, '#house-lon', '200', 'change');
    await type(fixture, '#house-lat', '12', 'change');
    expect(lat(host).getAttribute('aria-invalid')).toBeNull();
    expect(lon(host).getAttribute('aria-invalid')).toBe('true');
    expect(coordsError(host)).not.toBeNull();
    await type(fixture, '#house-lon', '77', 'change');
    expect(coordsError(host)).toBeNull();
  });

  it('refuses to save while a typed coordinate is invalid, and sends focus to that field', async () => {
    const { fixture, host, page, saveHouse } = await open();
    await type(fixture, '#house-name', 'Corner flat');
    await type(fixture, '#house-lon', '999', 'change');
    page.save();
    await settled(fixture);
    expect(saveHouse).not.toHaveBeenCalled();
    expect(host.querySelector('.error[role="alert"]')?.textContent?.trim()).toBe(t('house.coordsInvalid'));
    expect(document.activeElement?.id).toBe('house-lon');
  });

  it('a drag or tap on the map moves the pin, as MAP, and clears a typed value that was invalid', async () => {
    const { fixture, host, page } = await open();
    await type(fixture, '#house-lat', '95', 'change');
    mapOf(fixture).moved.emit({ lat: 11.25, lon: 75.5 });
    await settled(fixture);
    expect(pin(page)).toEqual([11.25, 75.5, 'MAP']);
    expect(coordsError(host)).toBeNull();
    expect(lat(host).getAttribute('aria-invalid')).toBeNull();
  });

  it('keeps a house marked approximate approximate when its pin is nudged by a drag or typed coordinates, and GPS replaces it', async () => {
    const { fixture, host, page } = await open();
    approx(host).click();
    await settled(fixture);
    mapOf(fixture).moved.emit({ lat: 11, lon: 75 });
    await settled(fixture);
    expect(pin(page)).toEqual([11, 75, 'APPROX']);
    await type(fixture, '#house-lat', '12', 'change');
    expect(pin(page)).toEqual([12, 75, 'APPROX']);
    button(host, t('house.useMyLocation')).click();
    located[0].ok({ coords: { latitude: 13, longitude: 74 } } as GeolocationPosition);
    await settled(fixture);
    expect(pin(page)).toEqual([13, 74, 'GPS']);
  });

  it('the Approximate switch goes back to MAP when the house had no source', async () => {
    const { fixture, host, page } = await open({ saved: SAVED });
    expect(page.draft().locationSource).toBeUndefined();
    approx(host).click();
    await settled(fixture);
    expect(page.draft().locationSource).toBe('APPROX');
    expect(approx(host).checked).toBe(true);
    approx(host).click();
    await settled(fixture);
    expect(page.draft().locationSource).toBe('MAP');
    expect(approx(host).checked).toBe(false);
  });

  it('the Approximate switch goes back to the source it was turned on from, each time', async () => {
    const { fixture, host, page } = await open({ saved: { ...SAVED, locationSource: 'GPS' } });
    approx(host).click();
    await settled(fixture);
    expect(page.draft().locationSource).toBe('APPROX');
    approx(host).click();
    await settled(fixture);
    expect(page.draft().locationSource).toBe('GPS');
    approx(host).click();
    await settled(fixture);
    approx(host).click();
    await settled(fixture);
    expect(page.draft().locationSource).toBe('GPS');
  });

  it('a house saved as approximate goes to MAP when the switch is turned off after reopening', async () => {
    const { fixture, host, page } = await open({ saved: { ...SAVED, locationSource: 'APPROX' } });
    expect(approx(host).checked).toBe(true);
    approx(host).click();
    await settled(fixture);
    expect(page.draft().locationSource).toBe('MAP');
  });
});

describe('HouseDetailPage: Use my location', () => {
  it('is not offered when the browser has no geolocation', async () => {
    const { host } = await open({ geolocation: false });
    expect([...host.querySelectorAll('button')].some((b) => b.textContent?.trim() === t('house.useMyLocation'))).toBe(false);
  });

  it('puts the pin where the person is, as GPS, clears invalid typed coordinates and announces it', async () => {
    const { fixture, host, page, announce } = await open();
    await type(fixture, '#house-lat', '95', 'change');
    button(host, t('house.useMyLocation')).click();
    await settled(fixture);
    expect(button(host, t('house.locating')).getAttribute('aria-disabled')).toBe('true');
    located[0].ok({ coords: { latitude: 12.9784123456, longitude: 77.6408 } } as GeolocationPosition);
    await settled(fixture);
    expect(pin(page)).toEqual([12.978412, 77.6408, 'GPS']);
    expect(coordsError(host)).toBeNull();
    expect(announce).toHaveBeenCalledWith({ key: 'house.locationFound' });
    expect(button(host, t('house.useMyLocation')).getAttribute('aria-disabled')).toBeNull();
  });

  it('ignores a second press while the first is waiting', async () => {
    const { fixture, host } = await open();
    const press = button(host, t('house.useMyLocation'));
    press.click();
    press.click();
    await settled(fixture);
    expect(located).toHaveLength(1);
  });

  it('says why it failed (blocked, or any other reason), and ends the wait', async () => {
    const { fixture, host } = await open();
    button(host, t('house.useMyLocation')).click();
    located[0].fail({ code: 1, message: 'denied' } as GeolocationPositionError);
    await settled(fixture);
    expect(locationFail(host)).toEqual([t('house.locationDenied')]);
    expect(button(host, t('house.useMyLocation')).getAttribute('aria-disabled')).toBeNull();
    button(host, t('house.useMyLocation')).click();
    located[1].fail({ code: 3, message: 'timeout' } as GeolocationPositionError);
    await settled(fixture);
    expect(locationFail(host)).toEqual([t('house.locationFailed')]);
  });

  it('drops an answer that arrives after the page is gone', async () => {
    const { fixture, host, page, announce } = await open();
    button(host, t('house.useMyLocation')).click();
    const before = pin(page);
    fixture.destroy();
    located[0].ok({ coords: { latitude: 1, longitude: 2 } } as GeolocationPosition);
    expect(pin(page)).toEqual(before);
    expect(announce).not.toHaveBeenCalledWith({ key: 'house.locationFound' });
  });

  it('puts the pin on a new house that had no position, and the save is then allowed', async () => {
    const { fixture, host, page, saveHouse } = await open({ query: {} });
    expect(unset(host)).not.toBeNull();
    button(host, t('house.useMyLocation')).click();
    located[0].ok({ coords: { latitude: 12.5, longitude: 77.5 } } as GeolocationPosition);
    await settled(fixture);
    expect(unset(host)).toBeNull();
    await type(fixture, '#house-name', 'Flat');
    page.save();
    await settled(fixture);
    expect(saveHouse).toHaveBeenCalledTimes(1);
    expect(saveHouse.mock.calls[0][0]).toMatchObject({ lat: 12.5, lon: 77.5, locationSource: 'GPS' });
  });
});

describe('HouseDetailPage: a new house with no position', () => {
  it('warns that the pin is only a guess, and refuses to save until it is put', async () => {
    const { fixture, host, page, saveHouse } = await open({ query: {} });
    expect(unset(host)?.className).toBe('warn-box');
    expect(lat(host).getAttribute('aria-describedby')).toBe('location-unset');
    await type(fixture, '#house-name', 'Flat');
    page.save();
    await settled(fixture);
    expect(saveHouse).not.toHaveBeenCalled();
    expect(host.querySelector('.error[role="alert"]')?.textContent?.trim()).toBe(t('house.locationRequired'));
    expect(unset(host)?.className).toBe('error');
    expect(lat(host).getAttribute('aria-invalid')).toBe('true');
    expect(lon(host).getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement?.id).toBe('house-lat');
  });

  it('takes the refusal back when the pin is put by typing', async () => {
    const { fixture, host } = await open({ query: {} });
    await type(fixture, '#house-name', 'Flat');
    (fixture.componentInstance as unknown as Page).save();
    await settled(fixture);
    await type(fixture, '#house-lat', '12', 'change');
    expect(unset(host)).toBeNull();
    expect(host.querySelector('.error[role="alert"]')).toBeNull();
    expect(lat(host).getAttribute('aria-invalid')).toBeNull();
    expect(lat(host).getAttribute('aria-describedby')).toBeNull();
  });

  it('leaves a different failure showing when the pin is put', async () => {
    const { fixture, host, page } = await open({ query: {} });
    page.save();
    await settled(fixture);
    expect(host.querySelector('.error[role="alert"]')?.textContent?.trim()).toBe(t('house.nameRequired'));
    await type(fixture, '#house-lat', '12', 'change');
    expect(host.querySelector('.error[role="alert"]')?.textContent?.trim()).toBe(t('house.nameRequired'));
  });

  it('starts at the last map view, at zoom 12 at least', async () => {
    localStorage.setItem(MAP_VIEW_KEY, JSON.stringify({ lat: 13.08, lon: 80.27, zoom: 9 }));
    const { fixture, page } = await open({ query: {} });
    expect(pin(page).slice(0, 2)).toEqual([13.08, 80.27]);
    expect(mapOf(fixture).zoom()).toBe(12);
  });

  it('keeps the zoom of the last map view when it is closer than 12', async () => {
    localStorage.setItem(MAP_VIEW_KEY, JSON.stringify({ lat: 13.08, lon: 80.27, zoom: 15 }));
    const { fixture } = await open({ query: {} });
    expect(mapOf(fixture).zoom()).toBe(15);
  });

  it('starts at the newest house that is not deleted, one level out', async () => {
    const older = { ...SAVED, id: 'a', lat: 1, lon: 2, createdAt: '2026-01-01T00:00:00.000Z' };
    const newest = { ...SAVED, id: 'b', lat: 17.38, lon: 78.48, createdAt: '2026-05-01T00:00:00.000Z' };
    const gone = { ...SAVED, id: 'c', lat: 5, lon: 6, createdAt: '2026-09-01T00:00:00.000Z', deleted: true };
    const { fixture, page } = await open({ query: {}, houses: () => of([older, gone, newest]) });
    expect(pin(page).slice(0, 2)).toEqual([17.38, 78.48]);
    expect(mapOf(fixture).zoom()).toBe(13);
  });

  it('starts on the country when there is no view and no house', async () => {
    const { fixture, page } = await open({ query: {} });
    expect(pin(page).slice(0, 2)).toEqual([COUNTRY_VIEW.lat, COUNTRY_VIEW.lon]);
    expect(mapOf(fixture).zoom()).toBe(COUNTRY_VIEW.zoom);
  });

  it('starts on the country when the houses cannot be read', async () => {
    const { fixture, page } = await open({ query: {}, houses: () => new Observable((s) => s.error(new Error('IndexedDB'))) });
    expect(pin(page).slice(0, 2)).toEqual([COUNTRY_VIEW.lat, COUNTRY_VIEW.lon]);
    expect(mapOf(fixture).zoom()).toBe(COUNTRY_VIEW.zoom);
  });

  it('opens a house at a pin in the URL at street zoom and with no warning', async () => {
    const { fixture, host } = await open();
    expect(mapOf(fixture).zoom()).toBe(16);
    expect(unset(host)).toBeNull();
  });
});

describe('HouseDetailPage: Find on the map', () => {
  const find = (host: HTMLElement, place: string) => button(host, t('house.findPlace', { place }));

  it('offers Find for the address, and for the locality in its place, only while the position is unset', async () => {
    const { fixture, host } = await open({ query: {} });
    expect(hasFind(host)).toBe(false);
    await type(fixture, '#house-address', ' 12 MG Road ');
    expect(find(host, '12 MG Road')).toBeDefined();
    await type(fixture, '#house-locality', ' Indiranagar ');
    expect(find(host, 'Indiranagar')).toBeDefined();
    expect(hasFind(host)).toBe(true);
  });

  it('is not offered once the position is set', async () => {
    const { fixture, host } = await open();
    await type(fixture, '#house-locality', 'Indiranagar');
    expect(hasFind(host)).toBe(false);
  });

  it('puts the pin there as approximate, says so, announces it, and sets the position', async () => {
    const search = vi.fn(() => of({ lat: 12.97849999, lon: 77.64081111, label: 'x' }));
    const { fixture, host, page, announce } = await open({ query: {}, search });
    await type(fixture, '#house-locality', 'Indiranagar');
    find(host, 'Indiranagar').click();
    await settled(fixture);
    expect(pin(page)).toEqual([12.9785, 77.640811, 'APPROX']);
    expect(unset(host)).toBeNull();
    expect(announce).toHaveBeenCalledWith({ key: 'house.placeFound', params: { place: 'Indiranagar' } });
    expect(host.textContent).toContain(t('house.placeFound', { place: 'Indiranagar' }));
  });

  it('stops saying where the pin is once the person turns Approximate off', async () => {
    const { fixture, host } = await open({ query: {}, search: () => of({ lat: 12.9, lon: 77.6, label: 'x' }) });
    await type(fixture, '#house-locality', 'Indiranagar');
    find(host, 'Indiranagar').click();
    await settled(fixture);
    expect(host.textContent).toContain(t('house.placeFound', { place: 'Indiranagar' }));
    approx(host).click();
    await settled(fixture);
    expect(host.textContent).not.toContain(t('house.placeFound', { place: 'Indiranagar' }));
  });

  it('shows the working label and ignores a second press while the place is being looked up', async () => {
    const answer = new Subject<unknown>();
    const search = vi.fn(() => answer);
    const { fixture, host } = await open({ query: {}, search });
    await type(fixture, '#house-locality', 'Indiranagar');
    find(host, 'Indiranagar').click();
    await settled(fixture);
    const working = button(host, t('house.findingPlace', { place: 'Indiranagar' }));
    expect(working.getAttribute('aria-disabled')).toBe('true');
    working.click();
    expect(search).toHaveBeenCalledTimes(1);
    answer.next(null);
    answer.complete();
    await settled(fixture);
    expect(find(host, 'Indiranagar').getAttribute('aria-disabled')).toBeNull();
  });

  it('names the place that was not found, and a later success removes that message', async () => {
    const answers = [of(null), of({ lat: 12.9, lon: 77.6, label: 'x' })];
    const { fixture, host } = await open({ query: {}, search: () => answers.shift()! });
    await type(fixture, '#house-locality', 'Nowhere');
    find(host, 'Nowhere').click();
    await settled(fixture);
    expect(locationFail(host)).toEqual([t('house.placeNotFound', { place: 'Nowhere' })]);
    expect(unset(host)).not.toBeNull();
    await type(fixture, '#house-locality', 'Indiranagar');
    find(host, 'Indiranagar').click();
    await settled(fixture);
    expect(locationFail(host)).toEqual([]);
  });

  it('says why the lookup failed', async () => {
    const { fixture, host } = await open({ query: {}, search: () => new Observable((s) => s.error(new Error('Nominatim is down'))) });
    await type(fixture, '#house-locality', 'Indiranagar');
    find(host, 'Indiranagar').click();
    await settled(fixture);
    expect(locationFail(host)).toEqual([t('house.lookupFailed', { reason: 'Nominatim is down' })]);
    expect(find(host, 'Indiranagar').getAttribute('aria-disabled')).toBeNull();
  });

  it('drops a place that is found after the page is gone', async () => {
    const answer = new Subject<unknown>();
    const { fixture, host, page, announce } = await open({ query: {}, search: () => answer });
    await type(fixture, '#house-locality', 'Indiranagar');
    find(host, 'Indiranagar').click();
    const before = pin(page);
    fixture.destroy();
    answer.next({ lat: 1, lon: 2, label: 'x' });
    expect(pin(page)).toEqual(before);
    expect(announce).not.toHaveBeenCalledWith({ key: 'house.placeFound', params: { place: 'Indiranagar' } });
  });
});

describe('HouseDetailPage: Fill address from map', () => {
  const fill = (host: HTMLElement) => button(host, t('house.fillAddress'));
  const found = { address: '12 MG Road, Bengaluru', street: 'MG Road', locality: 'Indiranagar' };

  it('fills the empty address, street and locality, names the house from the street, and announces the fields', async () => {
    const reverse = vi.fn((_lat: number, _lon: number) => of(found));
    const { fixture, host, page, announce, choose } = await open({ query: KOCHI, reverse });
    fill(host).click();
    await settled(fixture);
    expect(reverse).toHaveBeenCalledWith(9.9312, 76.2673);
    expect(page.draft()).toMatchObject({ label: 'MG Road', address: '12 MG Road, Bengaluru', street: 'MG Road', locality: 'Indiranagar' });
    expect(choose).not.toHaveBeenCalled();
    expect(announce).toHaveBeenCalledWith({
      key: 'house.addressFilledFields',
      params: { fields: names('house.name', 'house.address', 'house.street', 'house.locality') },
    });
  });

  it('says there was nothing new when everything typed already matches', async () => {
    const { fixture, host, announce, choose } = await open({
      saved: { ...SAVED, address: '12 MG Road, Bengaluru', street: 'MG Road', locality: 'Indiranagar' },
      reverse: () => of(found),
    });
    fill(host).click();
    await settled(fixture);
    expect(choose).not.toHaveBeenCalled();
    expect(announce).toHaveBeenCalledWith({ key: 'house.addressNothing' });
  });

  it('asks before replacing what was typed, showing the old and new values, and replaces it on Replace', async () => {
    const { fixture, host, page, choose, announce } = await open({
      saved: { ...SAVED, street: 'Old Street', locality: 'Indiranagar', address: null },
      reverse: () => of(found),
      answer: 'confirm',
    });
    fill(host).click();
    await settled(fixture);
    expect(choose).toHaveBeenCalledTimes(1);
    const [message, options] = choose.mock.calls[0];
    expect(message.key).toBe('house.addressReplaceAsk');
    expect(message.params!['changes']).toBe(`${t('house.street')}: Old Street → MG Road`);
    expect(options).toEqual({ confirmKey: 'house.addressReplace', altKey: 'house.addressFillEmpty' });
    expect(page.draft()).toMatchObject({ address: '12 MG Road, Bengaluru', street: 'MG Road', locality: 'Indiranagar' });
    expect(announce).toHaveBeenCalledWith({
      key: 'house.addressFilledFields',
      params: { fields: names('house.address', 'house.street') },
    });
  });

  it('lists every typed value that differs, one per line', async () => {
    const { fixture, host, choose } = await open({
      saved: { ...SAVED, address: ' A ', street: 'Old Street', locality: 'Old Place' },
      reverse: () => of(found),
      answer: 'cancel',
    });
    fill(host).click();
    await settled(fixture);
    expect(choose.mock.calls[0][0].params!['changes']).toBe(
      [`${t('house.address')}: A → 12 MG Road, Bengaluru`, `${t('house.street')}: Old Street → MG Road`, `${t('house.locality')}: Old Place → Indiranagar`].join('\n'),
    );
  });

  it('on "Fill empty fields only" fills the empty ones and keeps what was typed', async () => {
    const { fixture, host, page } = await open({
      saved: { ...SAVED, street: 'Old Street', address: null },
      reverse: () => of(found),
      answer: 'alt',
    });
    fill(host).click();
    await settled(fixture);
    expect(page.draft()).toMatchObject({ address: '12 MG Road, Bengaluru', street: 'Old Street', locality: 'Indiranagar' });
  });

  it('offers no "fill empty only" button when nothing is empty, and on that choice changes nothing', async () => {
    const typed = { ...SAVED, address: 'A', street: 'Old Street', locality: 'Old Place' };
    const { fixture, host, page, choose, announce } = await open({ saved: typed, reverse: () => of(found), answer: 'alt' });
    fill(host).click();
    await settled(fixture);
    expect(choose.mock.calls[0][1]).toEqual({ confirmKey: 'house.addressReplace', altKey: null });
    expect(page.draft()).toMatchObject({ address: 'A', street: 'Old Street', locality: 'Old Place' });
    expect(announce).toHaveBeenCalledWith({ key: 'house.addressNothing' });
  });

  it('changes nothing and says nothing on Cancel', async () => {
    const { fixture, host, page, announce } = await open({
      saved: { ...SAVED, street: 'Old Street', address: null },
      reverse: () => of(found),
      answer: 'cancel',
    });
    fill(host).click();
    await settled(fixture);
    expect(page.draft().street).toBe('Old Street');
    expect(page.draft().address).toBeFalsy();
    expect(announce.mock.calls.map((c: unknown[]) => (c[0] as { key: string }).key)).not.toContain('house.addressNothing');
    expect(announce.mock.calls.map((c: unknown[]) => (c[0] as { key: string }).key)).not.toContain('house.addressFilledFields');
  });

  it('is disabled and sends nothing while the position is unset', async () => {
    const reverse = vi.fn(() => of({}));
    const { host } = await open({ query: {}, reverse });
    expect(fill(host).getAttribute('aria-disabled')).toBe('true');
    fill(host).click();
    expect(reverse).not.toHaveBeenCalled();
  });

  it('is disabled while a lookup runs, and sends one request', async () => {
    const answer = new Subject<unknown>();
    const reverse = vi.fn(() => answer);
    const { fixture, host } = await open({ reverse });
    fill(host).click();
    await settled(fixture);
    expect(button(host, t('house.lookingUp')).getAttribute('aria-disabled')).toBe('true');
    button(host, t('house.lookingUp')).click();
    expect(reverse).toHaveBeenCalledTimes(1);
    answer.next({});
    answer.complete();
    await settled(fixture);
    expect(fill(host).getAttribute('aria-disabled')).toBeNull();
  });

  it('says why the lookup failed, and a later answer removes that message', async () => {
    const answers = [new Observable<unknown>((s) => s.error(new Error('no network'))), of({})];
    const { fixture, host } = await open({ reverse: () => answers.shift()! });
    fill(host).click();
    await settled(fixture);
    expect(locationFail(host)).toEqual([t('house.lookupFailed', { reason: 'no network' })]);
    expect(fill(host).getAttribute('aria-disabled')).toBeNull();
    fill(host).click();
    await settled(fixture);
    expect(locationFail(host)).toEqual([]);
  });

  it('drops an address that is found after the page is gone', async () => {
    const answer = new Subject<unknown>();
    const { fixture, host, page, announce, choose } = await open({ reverse: () => answer });
    fill(host).click();
    const before = { ...page.draft() };
    fixture.destroy();
    answer.next(found);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(page.draft()).toEqual(before);
    expect(choose).not.toHaveBeenCalled();
    expect(announce).not.toHaveBeenCalledWith({ key: 'house.addressNothing' });
  });
});
