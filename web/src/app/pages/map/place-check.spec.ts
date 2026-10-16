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

import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GEOLOCATION, TraceRecorderService } from '../../core/trace-recorder.service';
import { TraceStore } from '../../data/trace-store';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { audit } from '../../shared/testing/a11y';
import { FakeRecorder, settle } from '../../shared/testing/trace-fakes';
import { PlaceCheck } from './place-check';
import { PlaceCheckState } from './place-check-state';

const DEG = 1 / 111_194.9266;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);

class FakeGeo implements Geolocation {
  watches: { success: PositionCallback; error: PositionErrorCallback | null; cleared: boolean }[] = [];
  getCurrentPosition(): void {}
  watchPosition(success: PositionCallback, error?: PositionErrorCallback | null): number {
    this.watches.push({ success, error: error ?? null, cleared: false });
    return this.watches.length;
  }
  clearWatch(id: number): void {
    this.watches[id - 1].cleared = true;
  }
  fix(lat: number, lon: number, accuracy: number): void {
    this.watches[this.watches.length - 1].success({ coords: { latitude: lat, longitude: lon, accuracy }, timestamp: Date.now() } as GeolocationPosition);
  }
  fail(code: number): void {
    this.watches[this.watches.length - 1].error!({ code, message: '' } as GeolocationPositionError);
  }
}

describe('PlaceCheck (the Have I been here? control)', () => {
  let geo: FakeGeo | null;

  beforeEach(() => {
    // jsdom has no modal <dialog>: open and close are the attribute, plus the close event the component listens to.
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
    HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    };
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  async function render(opts: { lang?: Lang; geolocation?: FakeGeo | null; mapUsable?: boolean } = {}) {
    TestBed.resetTestingModule();
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    vi.setSystemTime(NOW);
    geo = opts.geolocation === undefined ? new FakeGeo() : opts.geolocation;
    TestBed.configureTestingModule({
      providers: [
        { provide: TraceRecorderService, useValue: new FakeRecorder() },
        { provide: GEOLOCATION, useValue: geo },
      ],
    });
    await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
    const fixture = TestBed.createComponent(PlaceCheck);
    fixture.componentRef.setInput('mapUsable', opts.mapUsable ?? true);
    const picked = vi.fn();
    fixture.componentInstance.pickSpot.subscribe(picked);
    document.body.appendChild(fixture.nativeElement);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const $ = <T extends HTMLElement>(s: string) => host.querySelector<T>(s);
    const click = async (sel: string) => {
      $<HTMLButtonElement>(sel)!.click();
      await settle(fixture);
    };
    return { fixture, host, $, click, picked, dialog: () => $<HTMLDialogElement>('dialog')!, state: TestBed.inject(PlaceCheckState) };
  }

  it('is a button named Have I been here?, with a tooltip, a hidden glyph and a label that phones hide visually', async () => {
    const r = await render();
    const b = r.$<HTMLButtonElement>('#place-check-open')!;
    expect(b.textContent).toContain('Have I been here?');
    expect(b.title).toBe('Have I been here?');
    expect(b.classList).toContain('btn');
    expect(b.querySelector('svg')!.getAttribute('aria-hidden')).toBe('true');
    expect(b.querySelector('.label')).not.toBeNull();
    expect(r.dialog().hasAttribute('open')).toBe(false);
  });

  it('asks nothing of the location until Where I am now is pressed: rendering, opening and choosing a spot start no watch', async () => {
    const r = await render();
    await r.click('#place-check-open');
    expect(geo!.watches).toHaveLength(0);
    await r.click('#check-spot');
    expect(geo!.watches).toHaveLength(0);
  });

  it('opens a labelled dialog with the permission sentence and the two choices', async () => {
    const r = await render();
    await r.click('#place-check-open');
    expect(r.dialog().hasAttribute('open')).toBe(true);
    expect(r.dialog().getAttribute('aria-labelledby')).toBe('check-dialog-title');
    expect(r.$('#check-dialog-title')!.textContent).toBe('Have I been here?');
    expect(r.$('#check-permission')!.textContent).toContain('your browser will ask for your location. It is used once, only on this page, and not kept.');
    expect(r.$('#check-here')!.textContent!.trim()).toBe('Where I am now');
    expect(r.$('#check-spot')!.textContent!.trim()).toBe('A spot on the map');
    expect(r.$('#check-here')!.getAttribute('aria-describedby')).toBe('check-permission');
    expect(r.dialog().contains(document.activeElement)).toBe(true);
  });

  it('shows the permission sentence every time it opens (no flag is kept)', async () => {
    const r = await render();
    const before = JSON.stringify({ ...localStorage });
    for (let i = 0; i < 3; i++) {
      await r.click('#place-check-open');
      expect(r.$('#check-permission')).not.toBeNull();
      await r.click('#check-dialog-close');
    }
    expect(JSON.stringify({ ...localStorage })).toBe(before);
  });

  it('Where I am now starts the watch in the same call as the click, with a fresh high-accuracy watch', async () => {
    const r = await render();
    await r.click('#place-check-open');
    const locate = vi.spyOn(r.state, 'locateHere');
    r.$<HTMLButtonElement>('#check-here')!.click();
    expect(locate).toHaveBeenCalledTimes(1);
    expect(geo!.watches).toHaveLength(1);
  });

  it('says it is finding the location, with Cancel, which stops the watch and goes back to the choices', async () => {
    const r = await render();
    await r.click('#place-check-open');
    await r.click('#check-here');
    expect(r.$('#check-dialog-locating')!.textContent).toBe('Finding your location...');
    expect(r.$('#check-here')).toBeNull();
    await r.click('#check-dialog-cancel-locate');
    expect(geo!.watches[0].cleared).toBe(true);
    expect(r.$('#check-here')).not.toBeNull();
  });

  it('closes when the answer arrives and leaves the focus to the panel (it does not take it back)', async () => {
    const r = await render();
    const opener = r.$<HTMLButtonElement>('#place-check-open')!;
    await seedWalk();
    await r.click('#place-check-open');
    await r.click('#check-here');
    geo!.fix(100 * DEG, 0, 10);
    await vi.advanceTimersByTimeAsync(50);
    await settle(r.fixture);
    expect(r.state.answer()).not.toBeNull();
    expect(r.dialog().hasAttribute('open')).toBe(false);
    expect(document.activeElement).not.toBe(opener);
  });

  it('says the location is blocked when the permission is refused, and keeps the choices', async () => {
    const r = await render();
    await r.click('#place-check-open');
    await r.click('#check-here');
    geo!.fail(1);
    await settle(r.fixture);
    expect(r.$('#check-dialog-problem')!.textContent).toContain('Location is blocked for this site.');
    expect(r.$('#check-here')).not.toBeNull();
    expect(r.$('#check-dialog-problem')!.closest('[role="alert"]')).not.toBeNull();
  });

  it('says it could not get the location after 15 seconds with no fix', async () => {
    const r = await render();
    await r.click('#place-check-open');
    await r.click('#check-here');
    await vi.advanceTimersByTimeAsync(15_000);
    await settle(r.fixture);
    expect(r.$('#check-dialog-problem')!.textContent).toContain('Could not get your location. Try again outdoors.');
  });

  it('opening again clears the old problem', async () => {
    const r = await render();
    await r.click('#place-check-open');
    await r.click('#check-here');
    geo!.fail(1);
    await settle(r.fixture);
    await r.click('#check-dialog-close');
    await r.click('#place-check-open');
    expect(r.$('#check-dialog-problem')).toBeNull();
  });

  it('offers no Where I am now where the browser has no Geolocation', async () => {
    const r = await render({ geolocation: null });
    await r.click('#place-check-open');
    expect(r.$('#check-here')).toBeNull();
    expect(r.$('#check-spot')).not.toBeNull();
  });

  it('offers no A spot on the map while there is no map', async () => {
    const r = await render({ mapUsable: false });
    await r.click('#place-check-open');
    expect(r.$('#check-spot')).toBeNull();
    expect(r.$('#check-here')).not.toBeNull();
  });

  it('A spot on the map closes the dialog and asks the page for its crosshair mode', async () => {
    const r = await render();
    await r.click('#place-check-open');
    await r.click('#check-spot');
    expect(r.picked).toHaveBeenCalledTimes(1);
    expect(r.dialog().hasAttribute('open')).toBe(false);
  });

  it('Esc closes it, stops a wait in progress and returns focus to the button', async () => {
    const r = await render();
    await r.click('#place-check-open');
    await r.click('#check-here');
    const esc = new Event('cancel', { cancelable: true });
    r.dialog().dispatchEvent(esc);
    await settle(r.fixture);
    expect(esc.defaultPrevented).toBe(true);
    expect(r.dialog().hasAttribute('open')).toBe(false);
    expect(geo!.watches[0].cleared).toBe(true);
    expect(document.activeElement).toBe(r.$('#place-check-open'));
  });

  it('writes nothing to any storage and puts nothing in the URL while it is used', async () => {
    const r = await render();
    const set = vi.spyOn(Storage.prototype, 'setItem');
    const url = location.href;
    await r.click('#place-check-open');
    await r.click('#check-here');
    geo!.fail(1);
    await settle(r.fixture);
    expect(set).not.toHaveBeenCalled();
    expect(location.href).toBe(url);
  });

  describe.each(['en', 'hi', 'ta', 'te'] as const)('in %s', (lang) => {
    it('shows no raw key and passes the accessibility rules, closed, open and with a problem', async () => {
      const r = await render({ lang });
      expect(r.host.textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(audit(r.host)).toEqual([]);
      await r.click('#place-check-open');
      await r.click('#check-here');
      geo!.fail(1);
      await settle(r.fixture);
      expect(r.host.textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(audit(r.host)).toEqual([]);
    });
  });
});

/** A walk 200 m north along lon 0, walked two days ago, in the browser's store (the control's answer then has something to say). */
async function seedWalk(): Promise<void> {
  const store = TestBed.inject(TraceStore);
  const id = NOW - 2 * 86_400_000;
  for (let i = 0; i < 11; i++) await store.putPoint({ lat: i * 20 * DEG, lon: 0, atMs: id + i * 30_000, walkId: id }, 10);
}
