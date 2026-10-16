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
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import type { ConfirmAnswer } from '../../core/confirm.service';
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { PhotoSummary } from '../../core/local-data.service';
import type { HouseDto, HouseRoom, HouseStatus } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { HouseDetailPage } from './house-detail-page';

const ROOMS: HouseRoom[] = [
  { id: 'r1', type: 'KITCHEN', name: 'Kitchen', sort: 0 },
  { id: 'r2', type: 'BEDROOM', sort: 1 },
];

function houseOf(status: HouseStatus, over: Partial<HouseDto> = {}): HouseDto {
  return {
    id: '5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10',
    label: 'Blue gate 2BHK',
    lat: 12.9716,
    lon: 77.5946,
    status,
    checklist: {},
    deleted: false,
    createdAt: '2026-09-20T10:00:00.000Z',
    updatedAt: '2026-09-20T10:00:00.000Z',
    syncVersion: 0,
    rooms: ROOMS,
    ...over,
  };
}

const photo = (id: string, over: Partial<PhotoSummary> = {}): PhotoSummary => ({
  id,
  createdAt: '2026-09-05T11:05:00.000Z',
  roomId: null,
  tags: [],
  caption: null,
  metaUpdatedAt: 0,
  ...over,
});

interface Page {
  draft: () => HouseDto;
  save: () => void;
  setMoveIn: (m: unknown) => void;
  onConditionFiles: (e: unknown) => Promise<void>;
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 3; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  fixture.detectChanges();
}

async function open(house: HouseDto, photos: PhotoSummary[] = [], answers: ConfirmAnswer[] = []) {
  const saved: HouseDto[] = [];
  const calls = { applyTaken: [] as [string, boolean][], setPhotoMeta: [] as [string, unknown][], uploads: [] as unknown[], closeHunt: [] as string[] };
  const api = {
    houses: () => of([]),
    brokers: () => of([]),
    scoring: () => of(DEFAULT_SCORING),
    questions: () => of([]),
    house: () => of(house),
    visits: () => of([]),
    viewingsOf: () => of([]),
    areas: () => of([]),
    areaNotes: () => of([]),
    places: () => of([]),
    settled: signal(0),
    photos: () => of(photos),
    // The thumbnails: a photo that cannot be read shows the placeholder (jsdom has no object URLs).
    photo: () => throwError(() => new Error('no bytes')),
    conditionPhotos: () => of(photos.filter((p) => p.tags.includes('MOVE_IN'))),
    saveHouse: (body: HouseDto) => {
      saved.push(body);
      return of(body);
    },
    applyTaken: (id: string, mark: boolean) => {
      calls.applyTaken.push([id, mark]);
      return of(mark ? 2 : 1);
    },
    closeCount: () => of(2),
    closeHunt: (id: string) => {
      calls.closeHunt.push(id);
      return of(2);
    },
    setPhotoMeta: (id: string, meta: unknown) => {
      calls.setPhotoMeta.push([id, meta]);
      return of(true);
    },
    uploadPhoto: (...args: unknown[]) => {
      calls.uploads.push(args);
      return of({ id: 'new-photo' });
    },
  };
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([]),
      { provide: LocalDataService, useValue: api },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: house.id }), queryParamMap: convertToParamMap({}) } } },
      { provide: GeocodeService, useValue: { reverse: () => of({}) } },
      { provide: AiService, useValue: { enabled: signal(false), usesOwnKey: signal(false), ownHost: signal(''), extractListing: () => of() } },
    ],
  });
  await TestBed.inject(TranslationService).setLang('en');
  const confirm = TestBed.inject(ConfirmService);
  const asked: { message: unknown; options: unknown }[] = [];
  vi.spyOn(confirm, 'choose').mockImplementation((message, options) => {
    asked.push({ message, options });
    return Promise.resolve(answers.shift() ?? 'cancel');
  });
  vi.spyOn(confirm, 'ask').mockImplementation((message, options) => {
    asked.push({ message, options });
    return Promise.resolve((answers.shift() ?? 'cancel') === 'confirm');
  });
  const fixture = TestBed.createComponent(HouseDetailPage);
  await settle(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, page: fixture.componentInstance as unknown as Page, saved, calls, asked, announce: vi.spyOn(TestBed.inject(Announcer), 'announce') };
}

const radio = (host: HTMLElement, status: string) => host.querySelector<HTMLInputElement>(`input[name="status"][value="${status}"]`)!;

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  sessionStorage.clear();
});

describe('HouseDetailPage: status Taken and Not chosen (slice 5)', () => {
  it('offers the five statuses, each with an icon and a name (colour is not the only cue)', async () => {
    const { host } = await open(houseOf('NEW'));
    const labels = [...host.querySelectorAll('.option[class*="status-"]')].map((l) => l.textContent?.replace(/\s+/g, ' ').trim());
    expect(labels).toEqual(['● New', '★ Shortlisted', '✕ Rejected', '⌂ Taken', '– Not chosen']);
  });

  it('asks "Mark the other houses Not chosen?" when Taken is chosen, with Mark them Not chosen and Keep them', async () => {
    const { fixture, host, asked } = await open(houseOf('SHORTLISTED'), [], ['confirm']);
    radio(host, 'TAKEN').click();
    await settle(fixture);
    expect(asked[0].message).toEqual({ key: 'status.markOthersTitle' });
    expect(asked[0].options).toEqual({ confirmKey: 'status.markThem', altKey: 'status.keepThem' });
  });

  it('applies Mark them Not chosen when the house is saved, once', async () => {
    const { fixture, host, page, calls, announce } = await open(houseOf('SHORTLISTED'), [], ['confirm']);
    radio(host, 'TAKEN').click();
    await settle(fixture);
    expect(page.draft().status).toBe('TAKEN');
    expect(calls.applyTaken).toEqual([]);
    page.save();
    await settle(fixture);
    expect(calls.applyTaken).toEqual([[page.draft().id, true]]);
    expect(announce).toHaveBeenCalledWith({ key: 'status.othersMarked', params: { n: 2 } });
    page.save();
    await settle(fixture);
    expect(calls.applyTaken).toHaveLength(1);
  });

  it('applies Keep them as a choice that marks no one', async () => {
    const { fixture, host, page, calls } = await open(houseOf('NEW'), [], ['alt']);
    radio(host, 'TAKEN').click();
    await settle(fixture);
    page.save();
    await settle(fixture);
    expect(calls.applyTaken).toEqual([[page.draft().id, false]]);
  });

  it('Cancel leaves the status as it was and puts the old radio back', async () => {
    const { fixture, host, page, calls } = await open(houseOf('SHORTLISTED'), [], ['cancel']);
    radio(host, 'TAKEN').click();
    await settle(fixture);
    expect(page.draft().status).toBe('SHORTLISTED');
    expect(radio(host, 'SHORTLISTED').checked).toBe(true);
    expect(radio(host, 'TAKEN').checked).toBe(false);
    page.save();
    await settle(fixture);
    expect(calls.applyTaken).toEqual([]);
  });

  it('asks nothing for a house that is already Taken, and nothing for the other statuses', async () => {
    const taken = await open(houseOf('TAKEN'));
    expect(taken.asked).toEqual([]);
    radio(taken.host, 'NOT_CHOSEN').click();
    await settle(taken.fixture);
    expect(taken.page.draft().status).toBe('NOT_CHOSEN');
    expect(taken.asked).toEqual([]);
    expect([...taken.host.querySelectorAll('h2')].map((h) => h.textContent?.trim())).not.toContain('Moving in');
  });
});

describe('HouseDetailPage: the Moving in card and the photo list (slice 5)', () => {
  it('shows the Moving in card for a Taken house only, after Viewings', async () => {
    const taken = await open(houseOf('TAKEN'));
    const headings = [...taken.host.querySelectorAll('h2')].map((h) => h.textContent?.trim());
    expect(headings).toContain('Moving in');
    expect(headings.indexOf('Moving in')).toBe(headings.indexOf('Viewings') + 1);
  });

  it('shows no Moving in card for a house that is not Taken, and shows it when the status is changed to Taken', async () => {
    const { fixture, host } = await open(houseOf('SHORTLISTED'), [], ['alt']);
    const headings = () => [...host.querySelectorAll('h2')].map((h) => h.textContent?.trim());
    expect(headings()).not.toContain('Moving in');
    radio(host, 'TAKEN').click();
    await settle(fixture);
    expect(headings()).toContain('Moving in');
  });

  it('stores what the card changes with the house, coerced', async () => {
    const { fixture, host, page, saved } = await open(houseOf('TAKEN'));
    host.querySelector<HTMLButtonElement>('#movein-start')!.click();
    await settle(fixture);
    expect(page.draft().moveIn?.items).toHaveLength(6);
    page.save();
    await settle(fixture);
    expect(saved[0].moveIn?.items).toHaveLength(6);
    expect(saved[0].moveIn?.items?.[0]).toMatchObject({ id: 'mi_agreement', text: 'Rental agreement signed and registered', sort: 0 });
  });

  it('saves a house whose move-in has nothing in it without a moveIn', async () => {
    const { fixture, page, saved } = await open(houseOf('TAKEN', { moveIn: { items: [{ id: 'x', text: '   ', sort: 0 }] } }));
    page.save();
    await settle(fixture);
    expect(saved[0].moveIn).toBeNull();
  });

  it('shows the room, tags and caption under a photo, tags translated and custom ones as typed', async () => {
    const { host } = await open(houseOf('NEW'), [photo('p1', { roomId: 'r1', tags: ['KITCHEN_FITTINGS', 'damp corner'], caption: 'Tap drips' }), photo('p2')]);
    const items = [...host.querySelectorAll('.photo-item')];
    expect(items).toHaveLength(2);
    expect(items[0].querySelector('.photo-room')?.textContent).toBe('Kitchen');
    expect(items[0].querySelector('.photo-tags')?.textContent).toBe('Kitchen fittings, damp corner');
    expect(items[0].querySelector('.photo-caption')?.textContent).toBe('Tap drips');
    expect(items[1].querySelector('.photo-room')).toBeNull();
    expect(items[1].querySelector('.photo-tags')).toBeNull();
  });

  it('names a room the house no longer has "Untagged"', async () => {
    const { host } = await open(houseOf('NEW'), [photo('p1', { roomId: 'gone' })]);
    expect(host.querySelector('.photo-room')?.textContent).toBe('Untagged');
  });

  it('opens the details editor from the photo\'s Details button, with an accessible name, and closes it with Cancel', async () => {
    const { fixture, host } = await open(houseOf('NEW'), [photo('p1', { tags: ['DAMP'] })]);
    const button = host.querySelector<HTMLButtonElement>('#photo-details-p1')!;
    expect(button.getAttribute('aria-label')).toBe('Edit details of photo 1');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    button.click();
    await settle(fixture);
    expect(host.querySelector('app-photo-meta-editor')).not.toBeNull();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    [...host.querySelectorAll<HTMLButtonElement>('app-photo-meta-editor .actions button')].find((b) => b.textContent?.trim() === 'Cancel')!.click();
    await settle(fixture);
    expect(host.querySelector('app-photo-meta-editor')).toBeNull();
  });

  it('does not mark the house as having unsaved edits when the photo details are typed', async () => {
    const { fixture, host } = await open(houseOf('NEW'), [photo('p1')]);
    host.querySelector<HTMLButtonElement>('#photo-details-p1')!.click();
    await settle(fixture);
    const caption = host.querySelector<HTMLInputElement>('#photo-caption-p1')!;
    caption.value = 'Hello';
    caption.dispatchEvent(new Event('input', { bubbles: true }));
    await settle(fixture);
    expect((fixture.componentInstance as unknown as { dirty: () => boolean }).dirty()).toBe(false);
  });

  it('takes a condition photo with MOVE_IN already chosen', async () => {
    const { page } = await open(houseOf('TAKEN'));
    const onFiles = vi.spyOn(page as unknown as { onFiles: (e: Event, m?: unknown) => Promise<void> }, 'onFiles').mockResolvedValue();
    const event = new Event('change');
    await page.onConditionFiles(event);
    expect(onFiles).toHaveBeenCalledWith(event, { roomId: null, tags: ['MOVE_IN'], caption: null });
  });
});
