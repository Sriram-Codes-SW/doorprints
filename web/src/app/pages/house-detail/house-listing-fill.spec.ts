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

// The listing paste on the house page, through the page's DOM: the box that starts open for a shared listing, the
// no-AI parser that reads the share text on arrival, and the Fill in the form button with its warnings, kept values
// and announcements (S4b-BL-168, slice 8). Written before the code moved, and run on the old code first.
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import type { HouseDraft } from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { HouseDetailPage } from './house-detail-page';

interface Page {
  draft: () => HouseDto;
}

const BLANK: HouseDraft = {
  label: null,
  address: null,
  street: null,
  locality: null,
  price: null,
  priceType: null,
  bedrooms: null,
  areaSqft: null,
  contactName: null,
  contactPhone: null,
  listingUrl: null,
  notes: null,
  amenities: [],
  warnings: [],
};

/** A new house at a pin in Kochi, `extract` answering the button of the listing card. */
async function open(
  extract: (text: string) => Observable<HouseDraft> = () => of(),
  aiEnabled = true,
) {
  const extractListing = vi.fn(extract);
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: {
          houses: () => of([]),
          brokers: () => of([]),
          scoring: () => of(DEFAULT_SCORING),
          questions: () => of([]),
          house: () => of(),
          visits: () => of([]),
          viewingsOf: () => of([]),
          areas: () => of([]),
          areaNotes: () => of([]),
          places: () => of([]),
          settled: signal(0),
          photos: () => of([]),
          saveHouse: (body: HouseDto) => of(body),
        },
      },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: convertToParamMap({}), queryParamMap: convertToParamMap({ lat: '9.9312', lon: '76.2673' }) } },
      },
      { provide: GeocodeService, useValue: { reverse: () => of({}) } },
      { provide: AiService, useValue: { enabled: signal(aiEnabled), usesOwnKey: signal(false), ownHost: signal(''), extractListing } },
    ],
  });
  TestBed.inject(TranslationService).setLang('en');
  const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
  const fixture = TestBed.createComponent(HouseDetailPage);
  await settled(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, page: fixture.componentInstance as unknown as Page, extractListing, announce };
}

async function settled(fixture: ComponentFixture<HouseDetailPage>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

async function type(fixture: ComponentFixture<HouseDetailPage>, selector: string, value: string): Promise<void> {
  const el = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
  el.value = value;
  el.dispatchEvent(new Event('input'));
  await settled(fixture);
}

const submit = (host: HTMLElement) => host.querySelector<HTMLButtonElement>('.listing-fill > button')!;
const warnings = (host: HTMLElement) => [...host.querySelectorAll('.listing-fill .warnings li')].map((li) => li.textContent?.trim());

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  history.replaceState(null, '');
  sessionStorage.clear();
  localStorage.clear();
});

describe('HouseDetailPage: the listing card (Fill in the form)', () => {
  it('fills the empty fields, lists the listing\'s own warnings, announces it and moves focus to the name', async () => {
    const { fixture, host, page, extractListing, announce } = await open(() =>
      of({ ...BLANK, label: 'Sea view 2BHK', locality: 'Fort Kochi', price: 32000, priceType: 'RENT', bedrooms: 2, warnings: ['The phone number looks short'] }),
    );
    await type(fixture, '#listing-text', '  2BHK sea view, Fort Kochi  ');
    submit(host).click();
    await settled(fixture);
    expect(extractListing).toHaveBeenCalledWith('2BHK sea view, Fort Kochi');
    expect(page.draft()).toMatchObject({ label: 'Sea view 2BHK', locality: 'Fort Kochi', price: 32000, priceType: 'RENT', bedrooms: 2 });
    expect(warnings(host)).toEqual(['The phone number looks short']);
    expect(announce).toHaveBeenCalledWith({ key: 'listingFill.done', params: { n: 0 } });
    expect(document.activeElement?.id).toBe('house-name');
    expect(host.querySelector('.listing-fill p.error')).toBeNull();
    expect(submit(host).getAttribute('aria-disabled')).toBeNull();
  });

  it('appends the listing\'s notes and amenities to the notes', async () => {
    const { fixture, host, page } = await open(() => of({ ...BLANK, notes: 'Lift, near the ferry', amenities: ['Parking', 'Gym'] }));
    await type(fixture, '#listing-text', 'a listing');
    submit(host).click();
    await settled(fixture);
    expect(page.draft().notes).toBe('Lift, near the ferry\nParking, Gym');
  });

  it('keeps what was typed, names the listing\'s value and announces the count', async () => {
    const { fixture, host, page, announce } = await open(() => of({ ...BLANK, label: 'Mattancherry flat', locality: 'Mattancherry' }));
    await type(fixture, '#house-name', 'My own name');
    await type(fixture, '#listing-text', 'a listing');
    submit(host).click();
    await settled(fixture);
    expect(page.draft().label).toBe('My own name');
    expect(page.draft().locality).toBe('Mattancherry');
    expect(warnings(host)).toEqual(['Kept your Name; the listing says “Mattancherry flat”.']);
    expect(announce).toHaveBeenCalledWith({ key: 'listingFill.doneKept', params: { n: 1 } });
  });

  it('shows a kept price in rupees, a kept type as Rent or Sale and a kept area in sq ft', async () => {
    const { fixture, host, page } = await open(() => of({ ...BLANK, price: 9000000, priceType: 'SALE', areaSqft: 1150 }));
    await type(fixture, '#house-price', '25000');
    await type(fixture, '#house-area', '900');
    expect(page.draft().price).toBe(25000);
    expect(page.draft().areaSqft).toBe(900);
    await type(fixture, '#listing-text', 'a listing');
    submit(host).click();
    await settled(fixture);
    const lines = warnings(host);
    expect(lines).toHaveLength(3);
    expect(lines[0]).toBe('Kept your Price (₹); the listing says “₹90,00,000”.');
    expect(lines[1]).toBe('Kept your Price type; the listing says “Sale”.');
    expect(lines[2]).toBe('Kept your Carpet area (sq ft); the listing says “1,150 sq ft”.');
  });

  it('sends nothing for an empty or blank box', async () => {
    const { fixture, host, extractListing } = await open();
    submit(host).click();
    await type(fixture, '#listing-text', '   \n ');
    submit(host).click();
    await settled(fixture);
    expect(extractListing).not.toHaveBeenCalled();
  });

  it('ignores a second press while the first read goes, and shows the working label', async () => {
    const reads: Subject<HouseDraft>[] = [];
    const { fixture, host, extractListing } = await open(() => {
      const s = new Subject<HouseDraft>();
      reads.push(s);
      return s;
    });
    await type(fixture, '#listing-text', 'a listing');
    submit(host).click();
    await settled(fixture);
    expect(submit(host).getAttribute('aria-disabled')).toBe('true');
    expect(submit(host).textContent?.trim()).toBe('Reading the listing…');
    submit(host).click();
    expect(extractListing).toHaveBeenCalledTimes(1);
    reads[0].next(BLANK);
    await settled(fixture);
    expect(submit(host).getAttribute('aria-disabled')).toBeNull();
  });

  it('clears the earlier warnings and kept values when the next read starts', async () => {
    const reads: Subject<HouseDraft>[] = [];
    const { fixture, host } = await open(() => {
      const s = new Subject<HouseDraft>();
      reads.push(s);
      return s;
    });
    await type(fixture, '#house-name', 'My own name');
    await type(fixture, '#listing-text', 'a listing');
    submit(host).click();
    reads[0].next({ ...BLANK, label: 'Other name', warnings: ['Check the rent'] });
    await settled(fixture);
    expect(warnings(host)).toEqual(['Kept your Name; the listing says “Other name”.', 'Check the rent']);
    submit(host).click();
    await settled(fixture);
    expect(host.querySelector('.listing-fill .warnings')).toBeNull();
  });

  it('shows a failure with its reason, announces nothing, and lets the person press again, which removes it', async () => {
    let calls = 0;
    const { fixture, host, extractListing, announce } = await open(() =>
      ++calls === 1 ? new Observable<HouseDraft>((s) => s.error(new Error('model busy'))) : of(BLANK),
    );
    await type(fixture, '#listing-text', 'a listing');
    submit(host).click();
    await settled(fixture);
    expect(host.querySelector('.listing-fill p.error')?.textContent).toContain('model busy');
    expect(announce).not.toHaveBeenCalled();
    expect(submit(host).getAttribute('aria-disabled')).toBeNull();
    submit(host).click();
    await settled(fixture);
    expect(extractListing).toHaveBeenCalledTimes(2);
    expect(host.querySelector('.listing-fill p.error')).toBeNull();
  });

  it('shows a kept Rent as Rent', async () => {
    const { fixture, host } = await open(() => of({ ...BLANK, priceType: 'RENT' }));
    await type(fixture, '#house-price', '25000');
    const select = host.querySelector<HTMLSelectElement>('#house-price-type')!;
    select.selectedIndex = 1;
    select.dispatchEvent(new Event('change'));
    await settled(fixture);
    await type(fixture, '#listing-text', 'a listing');
    submit(host).click();
    await settled(fixture);
    expect(warnings(host)).toEqual(['Kept your Price type; the listing says “Rent (per month)”.']);
  });

  it('drops a read that answers after the page is gone: nothing filled, nothing announced', async () => {
    const read = new Subject<HouseDraft>();
    const { fixture, host, page, announce } = await open(() => read);
    await type(fixture, '#listing-text', 'a listing');
    submit(host).click();
    expect(read.observed).toBe(true);
    fixture.destroy();
    expect(read.observed).toBe(false);
    read.next({ ...BLANK, label: 'Late' });
    expect(page.draft().label).toBe('');
    expect(announce).not.toHaveBeenCalled();
  });

  it('is not on the page when the AI is off', async () => {
    const off = await open(() => of(), false);
    expect(off.host.querySelector('.listing-fill')).toBeNull();
  });
});

describe('HouseDetailPage: a shared listing on arrival', () => {
  const SHARED = '2 BHK for rent in Fort Kochi, Rs 32,000 per month https://www.magicbricks.com/propertyDetails/abc';

  it('puts the whole text in the box, opens the box, and fills the form with the no-AI parser', async () => {
    history.replaceState({ shared: SHARED }, '');
    const { host, page } = await open();
    expect(host.querySelector<HTMLTextAreaElement>('#listing-text')!.value).toBe(SHARED);
    expect(host.querySelector<HTMLDetailsElement>('.listing-fill')!.open).toBe(true);
    expect(page.draft()).toMatchObject({ price: 32000, priceType: 'RENT', bedrooms: 2 });
    expect(page.draft().notes).toContain('Fort Kochi');
    expect(page.draft().listingUrl).toContain('magicbricks.com');
  });

  it('leaves the box closed and empty when nothing was shared', async () => {
    const { host, page } = await open();
    expect(host.querySelector<HTMLTextAreaElement>('#listing-text')!.value).toBe('');
    expect(host.querySelector<HTMLDetailsElement>('.listing-fill')!.open).toBe(false);
    expect(page.draft().price ?? null).toBeNull();
  });

  it('still parses the share text when the AI is off, with no box to show', async () => {
    history.replaceState({ shared: SHARED }, '');
    const { host, page } = await open(() => of(), false);
    expect(host.querySelector('.listing-fill')).toBeNull();
    expect(page.draft().price).toBe(32000);
  });

  it('gives the box all of a long share and the parser only the first 8,000 characters', async () => {
    const long = '3 BHK for rent ' + 'x'.repeat(9000) + ' Rs 99,999 per month';
    history.replaceState({ shared: long }, '');
    const { host, page } = await open();
    expect(host.querySelector<HTMLTextAreaElement>('#listing-text')!.value).toBe(long);
    expect(page.draft().bedrooms).toBe(3);
    expect(page.draft().price ?? null).toBeNull();
    expect(host.querySelector('#listing-cut-hint')).not.toBeNull();
  });
});
