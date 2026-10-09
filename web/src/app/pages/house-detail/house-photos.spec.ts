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
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { GeocodeService } from '../../core/geocode.service';
import { ImageResizeError } from '../../core/image-resize';
import { LocalDataService } from '../../core/local-data.service';
import type { PhotoSummary } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_SCORING } from '../../shared/scoring';
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

const photo = (id: string): PhotoSummary => ({ id, createdAt: '2026-09-05T11:05:00.000Z', roomId: null, tags: [], caption: null, metaUpdatedAt: 0 });

interface PhotosPage {
  uploading: () => number;
  canLeave: () => boolean | Promise<boolean>;
  onBeforeUnload: (event: BeforeUnloadEvent) => void;
  openPhoto: (src: string, n: number) => void;
  lightbox: () => { src: string; alt: string } | null;
}

interface Setup {
  /** The photos the fake store holds; an upload adds to it, a delete removes from it. */
  stored?: string[];
  upload?: (file: Blob, meta: unknown) => Observable<{ id: string }>;
  remove?: (id: string) => Observable<unknown>;
  /** Open a new house (no id in the route) instead of a stored one. */
  isNew?: boolean;
  answers?: boolean[];
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 3; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  fixture.detectChanges();
}

/**
 * jsdom decodes and draws nothing, so the browser's side of resizing is stood in for: a file called bad.jpg cannot be
 * decoded (createImageBitmap refuses and so does the <img> fallback); any other becomes a canvas whose JPEG says which
 * file it was drawn from.
 */
function stubImageEngine(): void {
  vi.stubGlobal('createImageBitmap', (file: File) =>
    file.name === 'bad.jpg' ? Promise.reject(new Error('cannot decode')) : Promise.resolve({ width: 100, height: 100, close: () => undefined, name: file.name }),
  );
  let drawn = '';
  // Only the canvas of a 100 x 100 image (resizeImage keeps it at that size); the map's own canvases still get none.
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function (this: HTMLCanvasElement) {
    if (this.width !== 100 || this.height !== 100) return null;
    return {
      fillRect: () => undefined,
      drawImage: (bitmap: { name: string }) => {
        drawn = bitmap.name;
      },
    };
  } as never);
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback: BlobCallback) =>
    callback(new Blob([`resized ${drawn}`], { type: 'image/jpeg' })),
  );
}

async function open(setup: Setup = {}) {
  stubImageEngine();
  const stored = setup.stored ?? [];
  const uploads: { blob: Blob; meta: unknown }[] = [];
  const removed: string[] = [];
  let next = 1;
  const api = {
    houses: () => of([]),
    brokers: () => of([]),
    scoring: () => of(DEFAULT_SCORING),
    questions: () => of([]),
    house: () => of(HOUSE),
    visits: () => of([]),
    viewingsOf: () => of([]),
    areas: () => of([]),
    areaNotes: () => of([]),
    places: () => of([]),
    settled: signal(0),
    photos: () => of(stored.map(photo)),
    photo: () => throwError(() => new Error('no bytes')),
    conditionPhotos: () => of([]),
    uploadPhoto: (_house: string, blob: Blob, _id: unknown, meta: unknown) => {
      uploads.push({ blob, meta });
      if (setup.upload) return setup.upload(blob, meta);
      const id = `new-${next++}`;
      stored.push(id);
      return of({ id });
    },
    deletePhoto: (id: string) => {
      removed.push(id);
      if (setup.remove) return setup.remove(id);
      stored.splice(stored.indexOf(id), 1);
      return of(undefined);
    },
  };
  const query = setup.isNew ? { lat: '12.97', lon: '77.59' } : {};
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([]),
      { provide: LocalDataService, useValue: api },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: convertToParamMap(setup.isNew ? {} : { id: HOUSE.id }), queryParamMap: convertToParamMap(query) } },
      },
      { provide: GeocodeService, useValue: { reverse: () => of({}) } },
      { provide: AiService, useValue: { enabled: signal(false), usesOwnKey: signal(false), ownHost: signal(''), extractListing: () => of() } },
    ],
  });
  await TestBed.inject(TranslationService).setLang('en');
  const answers = setup.answers ?? [];
  const asked: unknown[] = [];
  vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockImplementation((message) => {
    asked.push(message);
    return Promise.resolve(answers.shift() ?? false);
  });
  const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
  const fixture = TestBed.createComponent(HouseDetailPage);
  await settle(fixture);
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, page: fixture.componentInstance as unknown as PhotosPage, uploads, removed, asked, announce, stored };
}

/** The file input of the photos card, with these files chosen. */
async function choose(r: Awaited<ReturnType<typeof open>>, names: string[]): Promise<HTMLInputElement> {
  const input = r.host.querySelector<HTMLInputElement>('#photos-heading')!.closest('section')!.querySelector<HTMLInputElement>('input[type="file"]')!;
  Object.defineProperty(input, 'files', { value: names.map((n) => new File(['x'], n, { type: 'image/jpeg' })), configurable: true });
  input.dispatchEvent(new Event('change'));
  return input;
}

const card = (host: HTMLElement) => host.querySelector('#photos-heading')!.closest('section')!;
const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

afterEach(() => {
  delete (HTMLDialogElement.prototype as { showModal?: () => void }).showModal;
  delete (HTMLDialogElement.prototype as { close?: () => void }).close;
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
});

describe('HouseDetailPage: the photos card', () => {
  it('asks to save a new house first and offers no way to add photos', async () => {
    const { host } = await open({ isNew: true });
    expect(text(card(host).querySelector('p.muted'))).toBe('Save the house first to add photos.');
    expect(card(host).querySelector('input[type="file"]')).toBeNull();
  });

  it('says there are no photos for a stored house without any, and offers Add photos', async () => {
    const { host } = await open();
    expect(text(card(host).querySelector('.empty-inline'))).toBe('📷 No photos yet.');
    expect(text(card(host).querySelector('label.upload'))).toBe('＋ Add photos');
  });

  it('lists the photos in order, each with a delete button named by its number', async () => {
    const { host } = await open({ stored: ['a', 'b'] });
    const items = [...card(host).querySelectorAll('.photo-item')];
    expect(items).toHaveLength(2);
    expect(items.map((li) => li.querySelector('.photo-del')?.getAttribute('aria-label'))).toEqual(['Delete photo 1', 'Delete photo 2']);
  });

  it('adds the chosen photos one by one, resized, says the result once, and lists them', async () => {
    const r = await open({ stored: ['a'] });
    const input = await choose(r, ['one.jpg', 'two.jpg']);
    await settle(r.fixture);
    expect(await Promise.all(r.uploads.map((u) => u.blob.text()))).toEqual(['resized one.jpg', 'resized two.jpg']);
    expect(r.uploads.map((u) => u.meta)).toEqual([undefined, undefined]);
    expect(r.announce.mock.calls.filter(([m]) => m.key.startsWith('house.photos'))).toEqual([[{ key: 'house.photosAdded', params: { n: 2 } }]]);
    expect(card(r.host).querySelectorAll('.photo-item')).toHaveLength(3);
    expect(card(r.host).querySelector('[role="alert"]')?.textContent?.trim()).toBe('');
    expect(input.value).toBe('');
    expect(text(card(r.host).querySelector('label.upload'))).toBe('＋ Add photos');
  });

  it('shows how many are still uploading, and the page asks before it is left', async () => {
    const pending = new Subject<{ id: string }>();
    const r = await open({ upload: () => pending, answers: [false, true] });
    await choose(r, ['one.jpg', 'two.jpg']);
    await settle(r.fixture);
    expect(r.page.uploading()).toBe(2);
    expect(text(card(r.host).querySelector('label.upload'))).toBe('Uploading (2)…');
    // Closing the tab asks, and so does a route change: the first answer says stay, the second leave.
    const event = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
    r.page.onBeforeUnload(event);
    expect(event.defaultPrevented).toBe(true);
    expect(await r.page.canLeave()).toBe(false);
    expect(await r.page.canLeave()).toBe(true);
    expect(r.asked).toEqual([{ key: 'confirm.leaveUploading' }, { key: 'confirm.leaveUploading' }]);
    pending.next({ id: 'x' });
    pending.complete();
  });

  it('lets the tab close without asking when nothing is uploading', async () => {
    const r = await open();
    const event = new Event('beforeunload', { cancelable: true }) as BeforeUnloadEvent;
    r.page.onBeforeUnload(event);
    expect(event.defaultPrevented).toBe(false);
    expect(await r.page.canLeave()).toBe(true);
    expect(r.asked).toEqual([]);
  });

  it('names every file that failed with its reason, and says the counts once', async () => {
    const r = await open();
    await choose(r, ['bad.jpg', 'fine.jpg', 'bad.jpg']);
    await settle(r.fixture);
    const alert = card(r.host).querySelector('[role="alert"]')!;
    expect(text(alert.querySelector('p'))).toBe('These photos were not added:');
    expect([...alert.querySelectorAll('li')].map(text)).toEqual(['bad.jpg: Could not read that image.', 'bad.jpg: Could not read that image.']);
    expect(alert.querySelector('a')).toBeNull();
    expect(r.announce.mock.calls.filter(([m]) => m.key.startsWith('house.photos'))).toEqual([[{ key: 'house.photosResult', params: { added: 1, failed: 2 } }]]);
    expect(card(r.host).querySelectorAll('.photo-item')).toHaveLength(1);
  });

  it('points to Your data when the browser is out of space', async () => {
    const full = Object.assign(new Error('quota'), { name: 'QuotaExceededError' });
    const r = await open({ upload: () => throwError(() => full) });
    await choose(r, ['one.jpg']);
    await settle(r.fixture);
    const alert = card(r.host).querySelector('[role="alert"]')!;
    expect(text(alert.querySelector('li'))).toContain('This browser has no space left for Doorprints.');
    expect(alert.querySelector('a')?.getAttribute('href')).toBe('/data?export=backup');
  });

  it('starts the next batch with a clean list of failures', async () => {
    const r = await open();
    await choose(r, ['bad.jpg']);
    await settle(r.fixture);
    expect(card(r.host).querySelectorAll('.failures li')).toHaveLength(1);
    await choose(r, ['fine.jpg']);
    await settle(r.fixture);
    expect(card(r.host).querySelector('.failures')).toBeNull();
    expect(r.announce.mock.calls.filter(([m]) => m.key.startsWith('house.photos')).at(-1)).toEqual([{ key: 'house.photosAdded', params: { n: 1 } }]);
  });

  it('adds nothing when no file was chosen', async () => {
    const r = await open();
    await choose(r, []);
    await settle(r.fixture);
    expect(r.uploads).toEqual([]);
    expect(r.announce.mock.calls.filter(([m]) => m.key.startsWith('house.photos'))).toEqual([]);
  });

  it('deletes a photo after the question, says so, and moves focus to the heading', async () => {
    const r = await open({ stored: ['a', 'b'], answers: [true] });
    card(r.host).querySelectorAll<HTMLButtonElement>('.photo-del')[0].click();
    await settle(r.fixture);
    expect(r.asked).toEqual([{ key: 'confirm.deletePhoto' }]);
    expect(r.removed).toEqual(['a']);
    expect(card(r.host).querySelectorAll('.photo-item')).toHaveLength(1);
    expect(r.announce).toHaveBeenCalledWith({ key: 'house.photoDeleted' });
    expect(document.activeElement?.id).toBe('photos-heading');
  });

  it('keeps the photo when the question is answered No', async () => {
    const r = await open({ stored: ['a'], answers: [false] });
    card(r.host).querySelector<HTMLButtonElement>('.photo-del')!.click();
    await settle(r.fixture);
    expect(r.removed).toEqual([]);
    expect(card(r.host).querySelectorAll('.photo-item')).toHaveLength(1);
  });

  it('says why a photo could not be deleted, and keeps it', async () => {
    const r = await open({ stored: ['a'], answers: [true], remove: () => throwError(() => new ImageResizeError('read')) });
    card(r.host).querySelector<HTMLButtonElement>('.photo-del')!.click();
    await settle(r.fixture);
    expect(text(card(r.host).querySelector('[role="alert"] p.error'))).toBe('Could not delete the photo: Could not read that image.');
    expect(card(r.host).querySelectorAll('.photo-item')).toHaveLength(1);
    expect(r.announce).not.toHaveBeenCalledWith({ key: 'house.photoDeleted' });
  });

  it('closes the details editor of the photo that is deleted, and the large view', async () => {
    // jsdom has no modal dialogs; this one only has to open and close.
    const dialog = HTMLDialogElement.prototype as { showModal?: () => void; close?: () => void };
    dialog.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
    dialog.close = function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
    const r = await open({ stored: ['a'], answers: [true] });
    card(r.host).querySelector<HTMLButtonElement>('#photo-details-a')!.click();
    await settle(r.fixture);
    expect(card(r.host).querySelector('app-photo-meta-editor')).not.toBeNull();
    r.page.openPhoto('blob:x', 1);
    expect(r.page.lightbox()).toEqual({ src: 'blob:x', alt: 'Photo 1 of Blue gate 2BHK' });
    card(r.host).querySelector<HTMLButtonElement>('.photo-del')!.click();
    await settle(r.fixture);
    expect(card(r.host).querySelector('app-photo-meta-editor')).toBeNull();
    expect(r.page.lightbox()).toBeNull();
  });
});
