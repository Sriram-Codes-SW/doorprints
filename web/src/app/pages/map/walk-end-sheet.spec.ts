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
import { ConfirmService } from '../../core/confirm.service';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { TraceRecorderService } from '../../core/trace-recorder.service';
import { TraceStore } from '../../data/trace-store';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { audit } from '../../shared/testing/a11y';
import { FakeRecorder, settle } from '../../shared/testing/trace-fakes';
import { TraceView } from './trace-view';
import { WalkEndSheet, refusalMessage } from './walk-end-sheet';

const DEG = 1 / 111_194.9266;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);
const WALK_ID = NOW - 900_000;
const house = (id: string, label: string, northM: number, eastM = 0, over: Partial<HouseDto> = {}): HouseDto => ({
  ...newHouse(northM * DEG, eastM * DEG, 'MAP'),
  id,
  label,
  ...over,
});
/** A walk of 8 points 20 m apart going north: 140 m, ending at 140 m. */
const WALK_END_M = 140;

describe('WalkEndSheet', () => {
  let store: TraceStore;
  let view: TraceView;
  let recorder: FakeRecorder;

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
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  async function render(opts: { houses?: HouseDto[]; lang?: Lang; points?: number; ask?: boolean } = {}) {
    TestBed.resetTestingModule();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
    recorder = new FakeRecorder();
    TestBed.configureTestingModule({ providers: [{ provide: TraceRecorderService, useValue: recorder }] });
    await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
    store = TestBed.inject(TraceStore);
    view = TestBed.inject(TraceView);
    for (let i = 0; i < (opts.points ?? 8); i++) await store.putPoint({ lat: i * 20 * DEG, lon: 0, atMs: WALK_ID + i * 60_000, walkId: WALK_ID }, 10);
    const fixture = TestBed.createComponent(WalkEndSheet);
    fixture.componentRef.setInput('houses', opts.houses ?? []);
    await settle(fixture);
    if (opts.ask !== false) {
      await view.open();
      await settle(fixture);
    }
    const host = fixture.nativeElement as HTMLElement;
    const dialog = () => host.querySelector<HTMLDialogElement>('dialog')!;
    const $ = <T extends HTMLElement>(sel: string) => host.querySelector<T>(sel);
    const click = async (sel: string) => {
      $<HTMLButtonElement>(sel)!.click();
      await settle(fixture);
    };
    return { fixture, host, dialog, $, click };
  }

  it('stays shut while no walk is to be asked about', async () => {
    const r = await render({ points: 3 });
    expect(r.dialog().hasAttribute('open')).toBe(false);
  });

  it('opens when a walk ended, with the title focused, the distance and minutes, and the 30-day sentence', async () => {
    const r = await render();
    expect(r.dialog().hasAttribute('open')).toBe(true);
    expect(r.dialog().getAttribute('aria-labelledby')).toBe('walk-end-title');
    const title = r.$('#walk-end-title')!;
    expect(title.textContent).toBe('Save this walk?');
    expect(document.activeElement).toBe(title);
    expect(r.$('#walk-end-summary')!.textContent!.trim()).toBe('Walk of 140 m in 7 min');
    expect(r.dialog().textContent).toContain('It stays in this browser and is never in a backup or a copy.');
  });

  it('has the three answers and Keep for 30 days is the primary one', async () => {
    const r = await render();
    expect(r.$('#walk-end-keep')!.classList).toContain('btn-primary');
    expect(r.$('#walk-end-save')!.textContent!.trim()).toBe('Save with a house');
    expect(r.$('#walk-end-delete')!.textContent!.trim()).toBe('Delete this walk');
    expect(r.$('#walk-end-save')!.classList).not.toContain('btn-primary');
  });

  it('Keep for 30 days moves the watermark and closes, leaving the walk in the trace', async () => {
    const r = await render();
    await r.click('#walk-end-keep');
    expect(r.dialog().hasAttribute('open')).toBe(false);
    expect(await store.askedUpTo()).toBe(WALK_ID);
    expect((await store.traceWalks(NOW)).length).toBe(1);
  });

  it('Esc is Keep for 30 days, and so is a tap outside the sheet', async () => {
    const esc = await render();
    const event = new Event('cancel', { cancelable: true });
    esc.dialog().dispatchEvent(event);
    await settle(esc.fixture);
    expect(event.defaultPrevented).toBe(true);
    expect(await store.askedUpTo()).toBe(WALK_ID);
    const outside = await render();
    outside.dialog().dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await settle(outside.fixture);
    expect(await store.askedUpTo()).toBe(WALK_ID);
    expect(outside.dialog().hasAttribute('open')).toBe(false);
  });

  it('a tap inside the sheet is not a tap outside it', async () => {
    const r = await render();
    r.$('#walk-end-summary')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await settle(r.fixture);
    expect(r.dialog().hasAttribute('open')).toBe(true);
    expect(await store.askedUpTo()).toBe(0);
  });

  it('a close by any other way is Keep for 30 days', async () => {
    const r = await render();
    r.dialog().removeAttribute('open');
    r.dialog().dispatchEvent(new Event('close'));
    await settle(r.fixture);
    expect(await store.askedUpTo()).toBe(WALK_ID);
  });

  describe('Delete this walk', () => {
    it('asks first; Cancel changes nothing and the sheet stays', async () => {
      const r = await render();
      const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(false);
      await r.click('#walk-end-delete');
      expect(ask.mock.calls[0][0]).toEqual({ key: 'trace.end.deleteConfirm' });
      expect(r.dialog().hasAttribute('open')).toBe(true);
      expect((await store.traceWalks(NOW)).length).toBe(1);
    });

    it('deletes the walk on a yes', async () => {
      const r = await render();
      vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(true);
      await r.click('#walk-end-delete');
      expect(await store.traceWalks(NOW)).toEqual([]);
      expect(r.dialog().hasAttribute('open')).toBe(false);
    });
  });

  describe('the house picker', () => {
    const houses = [house('far', 'Far house', 1000), house('end', 'End house', WALK_END_M + 9.5), house('mid', 'Mid house', 60, 39.5)];

    it('has no houses to choose: says so, offers no list, and Save walk is off', async () => {
      const r = await render();
      await r.click('#walk-end-save');
      expect(r.$('#walk-end-title')!.textContent).toBe('Which house was this walk to?');
      expect(r.$('#walk-end-none')!.textContent).toContain('You have no saved houses yet.');
      expect(r.$('input[type="search"]')).toBeNull();
      expect(r.$<HTMLButtonElement>('#walk-end-confirm')!.disabled).toBe(true);
    });

    it('preselects the house nearest to where the walk stopped, with its distance, then the houses near the walk', async () => {
      const r = await render({ houses });
      await r.click('#walk-end-save');
      const rows = [...r.host.querySelectorAll('label.row')].map((x) => [x.querySelector('strong')!.textContent, x.querySelector('.muted')!.textContent]);
      expect(rows).toEqual([
        ['End house', 'Nearest to where you stopped, 10 m away'],
        ['Mid house', '40 m from your walk'],
      ]);
      const radios = [...r.host.querySelectorAll<HTMLInputElement>('input[name="walk-house"]')];
      expect(radios.map((x) => x.checked)).toEqual([true, false]);
      expect(r.$<HTMLButtonElement>('#walk-end-confirm')!.disabled).toBe(false);
      expect(document.activeElement).toBe(r.$('#walk-end-title'));
    });

    it('preselects nothing when no house is within 30 m of the stop, and Save walk waits for a choice', async () => {
      const r = await render({ houses: [house('mid', 'Mid house', 60, 40)] });
      await r.click('#walk-end-save');
      expect(r.$<HTMLButtonElement>('#walk-end-confirm')!.disabled).toBe(true);
      r.$<HTMLInputElement>('input[name="walk-house"]')!.click();
      await settle(r.fixture);
      expect(r.$<HTMLButtonElement>('#walk-end-confirm')!.disabled).toBe(false);
    });

    it('searches all houses, not only the near ones', async () => {
      const r = await render({ houses });
      await r.click('#walk-end-save');
      const input = r.$<HTMLInputElement>('#walk-end-search')!;
      expect(r.host.querySelector('label[for="walk-end-search"]')!.textContent).toBe('Search your houses');
      input.value = 'far';
      input.dispatchEvent(new Event('input'));
      await settle(r.fixture);
      const rows = [...r.host.querySelectorAll('label.row')].map((x) => x.textContent!.replace(/\s+/g, ' ').trim());
      expect(rows).toEqual(['Far house']);
      input.value = 'zzz';
      input.dispatchEvent(new Event('input'));
      await settle(r.fixture);
      expect(r.$('fieldset')!.textContent).toContain('No house matches.');
    });

    it('saves the walk with the chosen house: a saved walk, out of the trace, the sheet closed', async () => {
      const r = await render({ houses });
      await r.click('#walk-end-save');
      await r.click('#walk-end-confirm');
      expect(await store.savedCount('end')).toBe(1);
      expect(await store.traceWalks(NOW)).toEqual([]);
      expect(r.dialog().hasAttribute('open')).toBe(false);
      expect(await store.askedUpTo()).toBe(WALK_ID);
    });

    it('saves with the house the person picked instead of the preselected one', async () => {
      const r = await render({ houses });
      await r.click('#walk-end-save');
      r.host.querySelectorAll<HTMLInputElement>('input[name="walk-house"]')[1].click();
      await settle(r.fixture);
      await r.click('#walk-end-confirm');
      expect(await store.savedCount('mid')).toBe(1);
      expect(await store.savedCount('end')).toBe(0);
    });

    it('says why a refused save was refused, in an alert, and changes nothing', async () => {
      const r = await render({ houses });
      for (let i = 0; i < 20; i++) {
        const id = NOW - 5_000_000 - i * 1_000_000;
        for (let k = 0; k < 6; k++) await store.putPoint({ lat: k * 20 * DEG, lon: 1, atMs: id + k * 10_000, walkId: id }, 10);
        await store.saveWalk(id, 'end', NOW);
      }
      await r.click('#walk-end-save');
      await r.click('#walk-end-confirm');
      expect(r.$('#walk-end-refusal')!.textContent).toBe('This house already has 20 saved walks. Delete one first.');
      expect(r.dialog().hasAttribute('open')).toBe(true);
      expect((await store.traceWalks(NOW)).length).toBe(1);
      expect(await store.askedUpTo()).toBe(0);
    });

    it('Cancel goes back to the three answers', async () => {
      const r = await render({ houses });
      await r.click('#walk-end-save');
      await r.click('#walk-end-back');
      expect(r.$('#walk-end-keep')).not.toBeNull();
      expect(r.dialog().hasAttribute('open')).toBe(true);
    });

    it('Esc inside the picker is Keep for 30 days too', async () => {
      const r = await render({ houses });
      await r.click('#walk-end-save');
      r.dialog().dispatchEvent(new Event('cancel', { cancelable: true }));
      await settle(r.fixture);
      expect(await store.askedUpTo()).toBe(WALK_ID);
    });
  });

  describe('the refusal sentences', () => {
    it('names the limits of docs/11 5.27.6', () => {
      expect(refusalMessage('tooLong')).toEqual({ key: 'trace.save.tooLong', params: { max: 5000 } });
      expect(refusalMessage('houseFull')).toEqual({ key: 'trace.save.houseFull', params: { max: 20 } });
      expect(refusalMessage('deviceFull')).toEqual({ key: 'trace.save.allFull', params: { max: 200 } });
      expect(refusalMessage('noWalk')).toEqual({ key: 'trace.save.noWalk' });
    });
  });

  describe.each(['en', 'hi', 'ta', 'te'] as const)('in %s', (lang) => {
    it('shows no raw key in either step and passes the accessibility rules', async () => {
      const r = await render({ lang, houses: [house('end', 'End house', WALK_END_M + 10)] });
      expect(r.dialog().textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(audit(r.dialog())).toEqual([]);
      await r.click('#walk-end-save');
      expect(r.dialog().textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(audit(r.dialog())).toEqual([]);
    });
  });
});
