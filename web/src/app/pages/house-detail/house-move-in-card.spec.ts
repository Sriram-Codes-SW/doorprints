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
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { LocalDataService } from '../../core/local-data.service';
import type { PhotoSummary } from '../../core/local-data.service';
import type { HouseDto, MoveIn } from '../../core/models';
import { TranslationService } from '../../i18n/translation.service';
import type { Lang } from '../../i18n/languages';
import { DEFAULT_MOVE_IN_ITEMS } from '../../shared/move-in';
import { HouseMoveInCard } from './house-move-in-card';
import type { OpenedPhoto } from './house-move-in-card';

const HOUSE: HouseDto = {
  id: 'h1',
  label: 'Blue gate',
  lat: 12.97,
  lon: 77.59,
  status: 'TAKEN',
  checklist: {},
  deleted: false,
  syncVersion: 0,
  rooms: [
    { id: 'r1', type: 'KITCHEN', name: 'Kitchen', sort: 0 },
    { id: 'r2', type: 'BEDROOM', sort: 1 },
  ],
};

const photo = (id: string, over: Partial<PhotoSummary> = {}): PhotoSummary => ({
  id,
  createdAt: '2026-10-01T06:00:00.000Z',
  roomId: null,
  tags: ['MOVE_IN'],
  caption: null,
  metaUpdatedAt: 1,
  ...over,
});

async function make(
  moveIn: MoveIn | null,
  opts: { isNew?: boolean; photos?: PhotoSummary[]; lang?: Lang; closeCount?: number; saveFirst?: () => Promise<boolean>; confirm?: boolean } = {},
) {
  const calls = { closeHunt: [] as string[], closeCount: 0 };
  const api = {
    settled: signal(0),
    conditionPhotos: () => of(opts.photos ?? []),
    photo: () => throwError(() => new Error('no bytes')),
    closeCount: () => {
      calls.closeCount++;
      return of(opts.closeCount ?? 2);
    },
    closeHunt: (id: string) => {
      calls.closeHunt.push(id);
      return of(opts.closeCount ?? 2);
    },
  };
  TestBed.configureTestingModule({ imports: [HouseMoveInCard], providers: [provideRouter([]), { provide: LocalDataService, useValue: api }] });
  await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
  const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(opts.confirm ?? true);
  const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
  const fixture = TestBed.createComponent(HouseMoveInCard);
  const emitted: (MoveIn | null)[] = [];
  fixture.componentInstance.moveInChange.subscribe((m) => emitted.push(m));
  const opened: OpenedPhoto[] = [];
  fixture.componentInstance.opened.subscribe((o) => opened.push(o));
  fixture.componentRef.setInput('house', { ...HOUSE, moveIn });
  fixture.componentRef.setInput('isNew', opts.isNew ?? false);
  if (opts.saveFirst) fixture.componentRef.setInput('saveFirst', opts.saveFirst);
  await settle(fixture);
  /** The page keeps what the card emits as the draft, so the card is shown the new value, as in the page. */
  const apply = async () => {
    fixture.componentRef.setInput('house', { ...HOUSE, moveIn: emitted[emitted.length - 1] });
    await settle(fixture);
  };
  return { fixture, host: fixture.nativeElement as HTMLElement, emitted, apply, calls, ask, announce, opened };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 3; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  fixture.detectChanges();
}

const q = <T extends HTMLElement>(host: HTMLElement, selector: string) => host.querySelector<T>(selector)!;
const items = (host: HTMLElement) => [...host.querySelectorAll<HTMLInputElement>('.item .text')].map((i) => i.value);
const type = (input: HTMLInputElement | HTMLTextAreaElement, value: string, event = 'input') => {
  input.value = value;
  input.dispatchEvent(new Event(event, { bubbles: true }));
};

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('HouseMoveInCard: the checklist (slice 5)', () => {
  it('offers Start moving in on an empty move-in and adds the six defaults in the app language', async () => {
    const { host, emitted, apply, announce } = await make(null, { lang: 'hi' });
    expect(items(host)).toEqual([]);
    q<HTMLButtonElement>(host, '#movein-start').click();
    expect(emitted).toHaveLength(1);
    expect(emitted[0]?.items?.map((i) => i.id)).toEqual(DEFAULT_MOVE_IN_ITEMS.map((d) => d.id));
    expect(emitted[0]?.items?.map((i) => i.text)).toEqual(DEFAULT_MOVE_IN_ITEMS.map((d) => d.text.hi));
    expect(announce).toHaveBeenCalledWith({ key: 'movein.summary', params: { done: 0, total: 6 } });
    await apply();
    expect(items(host)).toHaveLength(6);
    // Added once: with all six there, the button is gone.
    expect(host.querySelector('#movein-start')).toBeNull();
  });

  it('shows the progress and gives every control an accessible name', async () => {
    const { host } = await make({ items: [{ id: 'a', text: 'Keys', done: true, sort: 0 }, { id: 'b', text: 'Meter', sort: 1 }] });
    expect(q(host, '#movein-summary').textContent?.trim()).toBe('1 of 2 done');
    expect(q<HTMLInputElement>(host, '#movein-tick-a').getAttribute('aria-label')).toBe('Keys');
    expect(q<HTMLInputElement>(host, '#movein-tick-a').checked).toBe(true);
    expect(q<HTMLInputElement>(host, '#movein-tick-b').checked).toBe(false);
    expect(q(host, '#movein-text-a').getAttribute('aria-label')).toBe('Text of item 1');
    expect([...host.querySelectorAll('.remove')].map((b) => b.getAttribute('aria-label'))).toEqual(['Remove item 1', 'Remove item 2']);
    for (const input of host.querySelectorAll('input:not([type="file"]), textarea')) {
      const id = input.id;
      expect(input.getAttribute('aria-label') || host.querySelector(`label[for="${id}"]`), id).toBeTruthy();
    }
  });

  it('ticks and unticks an item: done is written only when true', async () => {
    const { host, emitted, apply } = await make({ items: [{ id: 'a', text: 'Keys', sort: 0 }] });
    const tick = q<HTMLInputElement>(host, '#movein-tick-a');
    tick.checked = true;
    tick.dispatchEvent(new Event('change', { bubbles: true }));
    expect(emitted[0]?.items).toEqual([{ id: 'a', text: 'Keys', done: true, sort: 0 }]);
    await apply();
    const again = q<HTMLInputElement>(host, '#movein-tick-a');
    again.checked = false;
    again.dispatchEvent(new Event('change', { bubbles: true }));
    expect(emitted[1]?.items).toEqual([{ id: 'a', text: 'Keys', sort: 0 }]);
    expect(Object.keys(emitted[1]?.items?.[0] ?? {})).toEqual(['id', 'text', 'sort']);
  });

  it('edits the text of an item and removes one', async () => {
    const { host, emitted, apply } = await make({ items: [{ id: 'a', text: 'Keys', sort: 0 }, { id: 'b', text: 'Meter', sort: 1 }] });
    type(q<HTMLInputElement>(host, '#movein-text-b'), 'Meter reading');
    expect(emitted[0]?.items?.map((i) => i.text)).toEqual(['Keys', 'Meter reading']);
    await apply();
    q<HTMLButtonElement>(host, '.item .remove').click();
    expect(emitted[1]?.items?.map((i) => i.id)).toEqual(['b']);
    await apply();
    q<HTMLButtonElement>(host, '.item .remove').click();
    expect(emitted[2]).toBeNull();
  });

  it('adds your own item with the next sort and a trimmed text, then clears the field', async () => {
    const { fixture, host, emitted } = await make({ items: [{ id: 'a', text: 'Keys', sort: 4 }] });
    const add = [...host.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === 'Add item')!;
    expect(add.disabled).toBe(true);
    type(q<HTMLInputElement>(host, '#movein-new'), '  Gas connection  ');
    await settle(fixture);
    add.click();
    expect(emitted).toHaveLength(1);
    expect(emitted[0]?.items?.map((i) => [i.text, i.sort])).toEqual([['Keys', 4], ['Gas connection', 5]]);
    expect(emitted[0]?.items?.[1].id).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('ignores a blank own item', async () => {
    const { fixture, host, emitted } = await make(null);
    type(q<HTMLInputElement>(host, '#movein-new'), '   ');
    const add = [...host.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === 'Add item')!;
    await settle(fixture);
    expect(add.disabled).toBe(true);
    add.click();
    expect(emitted).toEqual([]);
  });

  it('m5_disables adding at 30 items and says "At most 30 items"', async () => {
    const thirty = Array.from({ length: 30 }, (_, i) => ({ id: `i${i}`, text: `Item ${i}`, sort: i }));
    const { host } = await make({ items: thirty });
    expect(q<HTMLInputElement>(host, '#movein-new').disabled).toBe(true);
    expect(q(host, '#movein-full').textContent?.trim()).toBe('At most 30 items');
    expect(q<HTMLInputElement>(host, '#movein-new').getAttribute('aria-describedby')).toBe('movein-full');
    expect(host.querySelector('#movein-start')).toBeNull();
  });

  it('shows no "At most 30 items" below 30, and keeps Start while a default is missing', async () => {
    const { host } = await make({ items: [{ id: 'mi_keys', text: 'Keys', sort: 0 }] });
    expect(host.querySelector('#movein-full')).toBeNull();
    expect(host.querySelector('#movein-start')).not.toBeNull();
  });
});

describe('HouseMoveInCard: the date and the notes (slice 5)', () => {
  it('shows the stored date in the date field and writes a typed one as UTC midnight', async () => {
    const { host, emitted } = await make({ date: 1790812800000, items: [{ id: 'a', text: 'Keys', sort: 0 }] });
    const date = q<HTMLInputElement>(host, '#movein-date');
    expect(date.value).toBe('2026-10-01');
    type(date, '2026-11-05', 'change');
    expect(emitted[0]).toEqual({ date: Date.parse('2026-11-05T00:00:00Z'), items: [{ id: 'a', text: 'Keys', sort: 0 }] });
  });

  it('clears the date when the field is emptied, and is null when nothing else is left', async () => {
    const { host, emitted } = await make({ date: 1790812800000 });
    type(q<HTMLInputElement>(host, '#movein-date'), '', 'change');
    expect(emitted[0]).toBeNull();
  });

  it('writes the notes and drops them when blank', async () => {
    const { host, emitted } = await make({ date: 1790812800000, notes: 'Old' });
    type(q<HTMLTextAreaElement>(host, '#movein-notes'), 'Meter 4521');
    expect(emitted[0]).toEqual({ date: 1790812800000, notes: 'Meter 4521' });
    type(q<HTMLTextAreaElement>(host, '#movein-notes'), '  ');
    expect(emitted[1]).toEqual({ date: 1790812800000 });
    expect(q<HTMLTextAreaElement>(host, '#movein-notes').maxLength).toBe(2000);
  });
});

describe('HouseMoveInCard: the condition record (slice 5)', () => {
  it('groups the MOVE_IN photos by room with their date and caption, the gone-room ones as Untagged', async () => {
    const { host } = await make(null, {
      photos: [photo('p1', { roomId: 'r1', caption: 'Tap drips' }), photo('p2', { roomId: 'r2' }), photo('p3', { roomId: 'gone' }), photo('p4')],
    });
    const groups = [...host.querySelectorAll('h4.group')].map((h) => h.textContent?.trim());
    expect(groups).toEqual(['Kitchen', 'Bedroom', 'Untagged']);
    const lists = [...host.querySelectorAll('ul.condition')];
    expect(lists.map((l) => l.querySelectorAll('li').length)).toEqual([1, 1, 2]);
    expect(lists[0].querySelector('.caption')?.textContent).toBe('Tap drips');
    expect(lists[0].querySelector('.when')?.textContent?.trim()).not.toBe('');
    expect(lists[1].querySelector('.caption')).toBeNull();
  });

  it('says there are no condition photos yet, and offers Add a photo with the file input', async () => {
    const { host, fixture } = await make(null);
    expect(host.textContent).toContain('No condition photos yet.');
    const input = q<HTMLInputElement>(host, '.upload input[type="file"]');
    expect(input.accept).toBe('image/*');
    expect(q(host, '.upload').textContent).toContain('Add a photo');
    const files: Event[] = [];
    fixture.componentInstance.files.subscribe((e) => files.push(e));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    expect(files).toHaveLength(1);
  });

  it('keeps the photo input\'s change from reaching the house form', async () => {
    const { host, fixture } = await make(null);
    const reached = vi.fn();
    (fixture.nativeElement as HTMLElement).parentElement?.addEventListener('change', reached);
    document.body.addEventListener('change', reached);
    q<HTMLInputElement>(host, '.upload input[type="file"]').dispatchEvent(new Event('change', { bubbles: true }));
    expect(reached).not.toHaveBeenCalled();
    document.body.removeEventListener('change', reached);
  });

  it('asks to save the house first for a new house instead of offering photos', async () => {
    const { host } = await make(null, { isNew: true });
    expect(host.textContent).toContain('Save the house first to add photos.');
    expect(host.querySelector('.upload')).toBeNull();
  });
});

describe('HouseMoveInCard: Close this hunt (slice 5)', () => {
  const closeButton = (host: HTMLElement) => q<HTMLButtonElement>(host, '#movein-close');

  it('saves first, names how many houses become Not chosen, closes, announces and offers Save a copy', async () => {
    const saveFirst = vi.fn(() => Promise.resolve(true));
    const { fixture, host, ask, calls, announce } = await make(null, { closeCount: 2, saveFirst });
    closeButton(host).click();
    await settle(fixture);
    expect(saveFirst).toHaveBeenCalledOnce();
    expect(ask).toHaveBeenCalledWith({ key: 'movein.closeConfirm', params: { n: 2 } }, { confirmKey: 'movein.close' });
    expect(calls.closeHunt).toEqual(['h1']);
    expect(announce).toHaveBeenCalledWith({ key: 'movein.closed', params: { n: 2 } });
    expect(q(host, '#movein-closed').textContent).toContain('Houses marked Not chosen: 2');
    const link = q<HTMLAnchorElement>(host, '#movein-closed a');
    expect(link.textContent?.trim()).toBe('Save a copy');
    expect(link.getAttribute('href')).toBe('/data?export=backup');
  });

  it('closes nothing when the person says no', async () => {
    const { fixture, host, calls } = await make(null, { confirm: false });
    closeButton(host).click();
    await settle(fixture);
    expect(calls.closeHunt).toEqual([]);
    expect(host.querySelector('#movein-closed')).toBeNull();
  });

  it('closes nothing when the house could not be saved', async () => {
    const { fixture, host, ask, calls } = await make(null, { saveFirst: () => Promise.resolve(false) });
    closeButton(host).click();
    await settle(fixture);
    expect(ask).not.toHaveBeenCalled();
    expect(calls.closeCount).toBe(0);
    expect(calls.closeHunt).toEqual([]);
  });

  it('says there is nothing to mark when no other house is open', async () => {
    const { fixture, host, ask } = await make(null, { closeCount: 0 });
    closeButton(host).click();
    await settle(fixture);
    expect(ask).toHaveBeenCalledWith({ key: 'movein.closeConfirmNone' }, { confirmKey: 'movein.close' });
  });
});

describe('HouseMoveInCard: the four languages', () => {
  it.each(['en', 'hi', 'ta', 'te'] as const)('shows its headings in %s', async (lang) => {
    const { host } = await make({ items: [{ id: 'a', text: 'Keys', sort: 0 }] }, { lang });
    const t = TestBed.inject(TranslationService);
    expect(q(host, '#movein-heading').textContent?.trim()).toBe(t.t('movein.heading'));
    expect(q(host, '#movein-checklist-heading').textContent?.trim()).toBe(t.t('movein.checklist'));
    expect(q(host, '#movein-close').textContent?.trim()).toBe(t.t('movein.close'));
    if (lang !== 'en') expect(t.t('movein.heading')).not.toBe('Moving in');
  });
});
