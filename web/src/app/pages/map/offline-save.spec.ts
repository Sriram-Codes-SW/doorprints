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
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { en } from '../../i18n/en';
import { hi } from '../../i18n/hi';
import { ta } from '../../i18n/ta';
import { te } from '../../i18n/te';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { OFFLINE_ENV, OfflineMapsService } from '../../offline/offline-maps.service';
import { setOfflineActive } from '../../offline/offline-protocol';
import { fakeEnv } from '../../offline/testing/offline-fakes';
import type { GeoBounds } from '../../offline/offline-tiles';
import { OfflineSave } from './offline-save';

/** *Save this area for offline* on the Map (S4b-BL-79): the control, the dialog, the progress and the way out. */
const point: GeoBounds = { south: 12.97, west: 77.64, north: 12.9701, east: 77.6401 };
const india: GeoBounds = { south: 6.5, west: 68, north: 37.5, east: 97.5 };
const DICTS = { en, hi, ta, te } as const;

beforeEach(() => {
  // jsdom has no modal <dialog>: open and close are the attribute, plus the `close` event the component listens to.
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
  localStorage.clear();
  setOfflineActive(false);
});

async function tick(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }): Promise<void> {
  for (let i = 0; i < 4; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  fixture.detectChanges();
}

async function render(opts: { lang?: Lang; bounds?: GeoBounds | null; configure?: (r: ReturnType<typeof fakeEnv>) => void } = {}) {
  TestBed.resetTestingModule();
  const rig = fakeEnv();
  opts.configure?.(rig);
  TestBed.configureTestingModule({ imports: [OfflineSave], providers: [{ provide: OFFLINE_ENV, useValue: rig.env }] });
  await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
  const fixture = TestBed.createComponent(OfflineSave);
  const bounds = opts.bounds === undefined ? point : opts.bounds;
  fixture.componentRef.setInput('boundsOf', () => bounds);
  await tick(fixture);
  const host = fixture.nativeElement as HTMLElement;
  const dialog = () => host.querySelector<HTMLDialogElement>('dialog')!;
  const button = () => host.querySelector<HTMLButtonElement>(':scope > button')!;
  const open = async () => {
    button().click();
    await tick(fixture);
  };
  const press = async (text: string) => {
    const b = [...dialog().querySelectorAll('button')].find((x) => x.textContent!.trim() === text)!;
    b.click();
    await tick(fixture);
  };
  return { fixture, host, rig, dialog, button, open, press, service: TestBed.inject(OfflineMapsService) };
}

describe('OfflineSave', () => {
  it('is a button with the name and a tooltip, 44px class, and is absent where the browser cannot keep maps', async () => {
    const r = await render();
    expect(r.button().textContent).toContain('Save this area for offline');
    expect(r.button().title).toBe('Save this area for offline');
    expect(r.button().classList).toContain('btn');
    const none = await render({ configure: (x) => (x.env.hasCache = false) });
    expect(none.host.querySelector(':scope > button')).toBeNull();
  });

  it('opens a labelled dialog with the estimate, the room left and a named field', async () => {
    const r = await render();
    await r.open();
    expect(r.dialog().hasAttribute('open')).toBe(true);
    expect(r.dialog().getAttribute('aria-labelledby')).toBe('offline-title');
    const text = r.dialog().textContent!;
    expect(text).toMatch(/about \d+(\.\d)? MB to download/);
    expect(text).toContain('This browser has room for about');
    const input = r.dialog().querySelector<HTMLInputElement>('#offline-name')!;
    expect(input.value).toBe('My area');
    expect(r.dialog().querySelector('label[for="offline-name"]')!.textContent).toBe('Name');
    expect(document.activeElement === input || r.dialog().contains(document.activeElement)).toBe(true);
    const save = [...r.dialog().querySelectorAll('button')].find((b) => b.textContent!.trim() === 'Save area')!;
    expect(save.disabled).toBe(false);
  });

  it('warns on mobile data, and refuses with the numbers when the box is too large', async () => {
    const m = await render({ configure: (x) => (x.env.isMetered = true) });
    await m.open();
    expect(m.dialog().textContent).toContain('mobile data');
    const big = await render({ bounds: india });
    await big.open();
    expect(big.dialog().querySelector('[role="alert"]')!.textContent).toMatch(/too large \(about [\d,]+ map tiles; the limit is 2,000\)/);
    const save = [...big.dialog().querySelectorAll('button')].find((b) => b.textContent!.trim() === 'Save area')!;
    expect(save.disabled).toBe(true);
  });

  it('says it needs the internet when offline, and does not offer to start', async () => {
    const r = await render({ configure: (x) => (x.env.isOnline = false) });
    await r.open();
    expect(r.dialog().querySelector('[role="alert"]')!.textContent).toContain('You are offline');
    const save = [...r.dialog().querySelectorAll('button')].find((b) => b.textContent!.trim() === 'Save area')!;
    expect(save.disabled).toBe(true);
  });

  it('saves under the typed name and says so in a status, then Close returns focus to the control', async () => {
    const r = await render();
    r.button().focus();
    await r.open();
    const input = r.dialog().querySelector<HTMLInputElement>('#offline-name')!;
    input.value = 'Koramangala';
    input.dispatchEvent(new Event('input'));
    await r.press('Save area');
    await new Promise((resolve) => setTimeout(resolve, 30));
    await tick(r.fixture);
    expect(r.service.areas().map((a) => a.name)).toEqual(['Koramangala']);
    const status = r.dialog().querySelector('[role="status"]')!;
    expect(status.textContent).toMatch(/‘Koramangala’ is saved for offline \(\d/);
    await r.press('Close');
    expect(r.dialog().hasAttribute('open')).toBe(false);
    expect(document.activeElement).toBe(r.button());
  });

  it('shows a failure in an error, with the name, and keeps nothing', async () => {
    const r = await render();
    r.rig.net.status.set('https://tiles.openfreemap.org/planet/20260927_080001_pt/3/5/3.pbf', 500);
    await r.open();
    await r.press('Save area');
    await new Promise((resolve) => setTimeout(resolve, 30));
    await tick(r.fixture);
    expect(r.dialog().querySelector('[role="status"] p')!.classList).toContain('error');
    expect(r.dialog().querySelector('[role="status"]')!.textContent).toContain('could not be saved');
    expect(r.service.areas()).toEqual([]);
  });

  it('Stop ends the download and closes the dialog with nothing kept; Esc does the same while saving', async () => {
    const r = await render();
    r.rig.net.onRequest = (_u, n) => {
      if (n === 10) r.dialog().dispatchEvent(new Event('cancel', { cancelable: true }));
    };
    await r.open();
    await r.press('Save area');
    await new Promise((resolve) => setTimeout(resolve, 30));
    await tick(r.fixture);
    expect(r.service.areas()).toEqual([]);
    expect(r.dialog().hasAttribute('open')).toBe(false);
    const stop = await render();
    stop.rig.net.onRequest = (_u, n) => {
      if (n === 10) [...stop.dialog().querySelectorAll('button')].find((b) => b.textContent!.trim() === 'Stop')!.click();
    };
    await stop.open();
    await stop.press('Save area');
    await new Promise((resolve) => setTimeout(resolve, 30));
    await tick(stop.fixture);
    expect(stop.service.areas()).toEqual([]);
    expect(stop.dialog().hasAttribute('open')).toBe(false);
  });

  it('shows a progress bar and a Stop button while it runs', async () => {
    const r = await render();
    let sawProgress = false;
    r.rig.net.onRequest = (_u, n) => {
      if (n === 8) {
        r.fixture.detectChanges();
        sawProgress = !!r.dialog().querySelector('progress') && [...r.dialog().querySelectorAll('button')].some((b) => b.textContent!.trim() === 'Stop');
      }
    };
    await r.open();
    await r.press('Save area');
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(sawProgress).toBe(true);
  });

  it.each(['hi', 'ta', 'te'] as Lang[])('is in %s (under review): the control, the dialog and the estimate', async (lang) => {
    const r = await render({ lang });
    expect(r.button().textContent).toContain(DICTS[lang]['offline.save']);
    expect(r.button().title).toBe(DICTS[lang]['offline.save']);
    await r.open();
    expect(r.dialog().querySelector('h2')!.textContent).toBe(DICTS[lang]['offline.save']);
    expect(r.dialog().querySelector('label')!.textContent).toBe(DICTS[lang]['offline.nameLabel']);
    expect(r.dialog().querySelector<HTMLInputElement>('#offline-name')!.value).toBe(DICTS[lang]['offline.defaultName']);
    expect(r.dialog().textContent).toContain(DICTS[lang]['offline.start']);
    expect(r.dialog().textContent).not.toContain('{mb}');
  });
});
