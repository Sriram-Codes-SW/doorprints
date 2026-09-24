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
import { TitleOverride } from '../../i18n/i18n-title.strategy';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';
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
  extractListing?: () => Observable<HouseDraft>;
  aiEnabled?: boolean;
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
    house: () => of(HOUSE),
    visits: () => of([]),
    photoIds: () => of([]),
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
      { provide: GeocodeService, useValue: { reverse: fakes.reverse ?? (() => of({})) } },
      {
        provide: AiService,
        useValue: { enabled: signal(fakes.aiEnabled ?? false), extractListing: fakes.extractListing ?? (() => of()) },
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
