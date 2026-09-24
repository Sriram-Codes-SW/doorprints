import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import type { PlanResponse } from '../../core/ai.service';
import { LocalDataService } from '../../core/local-data.service';
import { TranslationService } from '../../i18n/translation.service';
import { PlanPage } from './plan-page';

/**
 * jsdom has no WebGL 2, so the real maplibre-gl map is never made (createMlMap shows "map unavailable" instead).
 * The page's map is replaced by this stand-in, which keeps the handlers the page registers, so a test can choose a
 * spot on the map the way a click does. A package import can be mocked under the Angular unit-test builder; a
 * relative one cannot, so the page's own code (createMlMap included) runs unchanged. The classes are made in
 * `vi.hoisted`, because `vi.mock` is hoisted above everything else in the file.
 */
const { maps, FakeMap, FakeMarker } = vi.hoisted(() => {
  const made: FakeMapLike[] = [];

  interface FakeMapLike {
    fire(type: string, event: unknown): void;
  }

  class Map implements FakeMapLike {
    readonly handlers = new globalThis.Map<string, ((event: unknown) => void)[]>();
    readonly touchZoomRotate = { disableRotation: () => undefined };
    readonly keyboard = { disableRotation: () => undefined };
    constructor() {
      made.push(this);
    }
    on(type: string, handler: (event: unknown) => void): this {
      this.handlers.set(type, [...(this.handlers.get(type) ?? []), handler]);
      return this;
    }
    fire(type: string, event: unknown): void {
      for (const handler of this.handlers.get(type) ?? []) handler(event);
    }
    addControl(): this {
      return this;
    }
    removeControl(): this {
      return this;
    }
    easeTo(): this {
      return this;
    }
    resize(): this {
      return this;
    }
    remove(): void {
      // Nothing to tear down.
    }
  }

  class Marker {
    private readonly element = document.createElement('div');
    setLngLat(): this {
      return this;
    }
    addTo(): this {
      return this;
    }
    getElement(): HTMLElement {
      return this.element;
    }
    on(): this {
      return this;
    }
    remove(): this {
      return this;
    }
  }

  return { maps: made, FakeMap: Map, FakeMarker: Marker };
});

// Every maplibre-gl value this page's module graph (plan-page, map-style, location-map) uses, and no more; the
// original module is not loaded (in the full suite it shares a chunk with map-style, and loading it from the factory
// would be a cycle).
vi.mock('maplibre-gl', () => ({
  Map: FakeMap,
  Marker: FakeMarker,
  NavigationControl: class {},
  LngLatBounds: class {
    extend(): this {
      return this;
    }
  },
  GeoJSONSource: class {},
  GPUInitializationError: class extends Error {},
  setWorkerUrl: () => undefined,
}));

/**
 * The Plan page's start wiring (S4b-BL-7): the rules themselves are unit-tested in `start-field.spec.ts` and
 * `map-center.spec.ts`; these cases check how the page puts them together — `onCoord`, `setStart`, `fieldInvalid`
 * and `coordsDescribedBy` — through the rendered fields and their `aria-invalid` and `aria-describedby`.
 */
describe('PlanPage', () => {
  let fixture: ComponentFixture<PlanPage>;
  let host: HTMLElement;
  let i18n: TranslationService;
  let planVisits: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    maps.length = 0;
    if (typeof ResizeObserver === 'undefined') {
      vi.stubGlobal(
        'ResizeObserver',
        class {
          observe(): void {
            // jsdom lays nothing out.
          }
          disconnect(): void {
            // Nothing observed.
          }
        },
      );
    }
    planVisits = vi.fn(() => new Subject<PlanResponse>());
    TestBed.configureTestingModule({
      imports: [PlanPage],
      providers: [
        provideRouter([]),
        { provide: AiService, useValue: { enabled: signal(true), planVisits } },
        // No stored map view and no houses: the start stays unset until the user sets it.
        { provide: LocalDataService, useValue: { houses: () => of([]) } },
      ],
    });
    i18n = TestBed.inject(TranslationService);
    i18n.setLang('en');
    fixture = TestBed.createComponent(PlanPage);
    host = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  afterEach(() => {
    fixture.destroy();
    delete (navigator as { geolocation?: Geolocation }).geolocation;
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  function field(axis: 'lat' | 'lon'): HTMLInputElement {
    const input = host.querySelector<HTMLInputElement>(`#start-${axis}`);
    if (!input) throw new Error(`no #start-${axis}`);
    return input;
  }

  /** Types a value and leaves the field, which is when the page reads it (`(change)`). */
  async function type(axis: 'lat' | 'lon', value: string): Promise<void> {
    const input = field(axis);
    input.value = value;
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  function state(axis: 'lat' | 'lon'): { invalid: string | null; describedBy: string | null } {
    return { invalid: field(axis).getAttribute('aria-invalid'), describedBy: field(axis).getAttribute('aria-describedby') };
  }

  async function submit(question: string): Promise<void> {
    const box = host.querySelector<HTMLTextAreaElement>('#plan-question')!;
    box.value = question;
    box.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    host.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
  }

  it('sets the start from a typed latitude and longitude, with neither field marked', async () => {
    await type('lat', '12.9716');
    // One field is not a start: nothing is planned from it yet, and it is not an error either.
    expect(state('lat')).toEqual({ invalid: null, describedBy: 'start-hint' });
    await type('lon', '77.5946');
    expect(state('lat')).toEqual({ invalid: null, describedBy: 'start-hint' });
    expect(state('lon')).toEqual({ invalid: null, describedBy: 'start-hint' });
    expect(field('lat').value).toBe('12.9716');
    expect(field('lon').value).toBe('77.5946');

    await submit('Two quiet 2BHKs');
    expect(host.querySelector('#start-msg')).toBeNull();
    expect(planVisits).toHaveBeenCalledWith(expect.objectContaining({ startLat: 12.9716, startLon: 77.5946 }));
  });

  it('marks a field made invalid, and clears the mark and the message when it is corrected', async () => {
    await type('lat', '123');
    expect(state('lat')).toEqual({ invalid: 'true', describedBy: 'start-hint start-coords-error' });
    // The other field is not touched by this one's error.
    expect(state('lon')).toEqual({ invalid: null, describedBy: 'start-hint' });
    expect(host.querySelector('#start-coords-error')?.textContent?.trim()).toBe(i18n.t('house.coordsInvalid'));

    await type('lat', '12.9716');
    expect(state('lat')).toEqual({ invalid: null, describedBy: 'start-hint' });
    expect(host.querySelector('#start-coords-error')).toBeNull();
  });

  it('withdraws a location failure when the start is then chosen on the map', async () => {
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (_ok: PositionCallback, fail: PositionErrorCallback) =>
          fail({ code: 1, message: 'denied' } as GeolocationPositionError),
      },
    });
    const locate = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === i18n.t('plan.useMyLocation'),
    );
    locate!.click();
    await fixture.whenStable();
    expect(host.querySelector('#start-msg')?.textContent?.trim()).toBe(i18n.t('house.locationDenied'));
    // Read with whichever field gets focus, but a refused location marks neither field invalid.
    expect(state('lat')).toEqual({ invalid: null, describedBy: 'start-hint start-msg' });
    expect(state('lon')).toEqual({ invalid: null, describedBy: 'start-hint start-msg' });

    expect(maps.length).toBe(1);
    maps[0].fire('click', { lngLat: { lat: 12.9716, lng: 77.5946 } });
    await fixture.whenStable();
    expect(host.querySelector('#start-msg')).toBeNull();
    expect(state('lat')).toEqual({ invalid: null, describedBy: 'start-hint' });
    expect(state('lon')).toEqual({ invalid: null, describedBy: 'start-hint' });
    expect(field('lat').value).toBe('12.9716');
    expect(field('lon').value).toBe('77.5946');
  });

  it('points both fields at "Choose a start point first" and marks the one to fix until it is typed', async () => {
    await submit('Two quiet 2BHKs');
    expect(planVisits).not.toHaveBeenCalled();
    expect(host.querySelector('#start-msg')?.textContent?.trim()).toBe(i18n.t('plan.startRequired'));
    expect(state('lat')).toEqual({ invalid: 'true', describedBy: 'start-hint start-msg' });
    expect(state('lon')).toEqual({ invalid: null, describedBy: 'start-hint start-msg' });
    expect(document.activeElement).toBe(field('lat'));

    // The latitude typed: no longer the field to fix, though the start is not set until the longitude is.
    await type('lat', '12.9716');
    expect(state('lat')).toEqual({ invalid: null, describedBy: 'start-hint start-msg' });
    await type('lon', '77.5946');
    expect(host.querySelector('#start-msg')).toBeNull();
    expect(state('lat')).toEqual({ invalid: null, describedBy: 'start-hint' });
    expect(state('lon')).toEqual({ invalid: null, describedBy: 'start-hint' });
  });
});
