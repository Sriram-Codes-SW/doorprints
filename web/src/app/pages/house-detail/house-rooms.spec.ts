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
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto, HouseRoom } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { draftKey, readDraft, writeDraft } from './draft-store';
import { HouseDetailPage } from './house-detail-page';

const ROOM: HouseRoom = { id: 'r1', type: 'HALL', name: 'Big hall', lengthCm: 396, widthCm: 366, condition: 4, notes: 'Bright', sort: 0 };
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
  rooms: [ROOM],
};

interface Page {
  draft: () => HouseDto;
  rooms: { lengthUnit: { set: (u: 'FT' | 'M') => void }; add: () => void };
}

/** The house form on a saved house holding `rooms`, the length unit as the local setting has it. */
async function open(rooms: HouseRoom[] | null, unit: 'FT' | 'M' = 'FT', extra: Partial<HouseDto> = {}, others: HouseDto[] = []) {
  const house: HouseDto = { ...HOUSE, rooms, ...extra };
  const saved: HouseDto[] = [];
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: {
          houses: () => of([house, ...others]),
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
          photos: () => of([]),
          saveHouse: (body: HouseDto) => {
            saved.push(body);
            return of(body);
          },
        },
      },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve(unit) } },
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: house.id }), queryParamMap: convertToParamMap({}) } } },
      { provide: GeocodeService, useValue: { reverse: () => of({}) } },
      { provide: AiService, useValue: { enabled: signal(false), usesOwnKey: signal(false), ownHost: signal(''), extractListing: () => of() } },
    ],
  });
  TestBed.inject(TranslationService).setLang('en');
  const fixture = TestBed.createComponent(HouseDetailPage);
  await settled(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, page: fixture.componentInstance as unknown as Page, saved };
}

async function settled(fixture: ComponentFixture<HouseDetailPage>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function type(fixture: ComponentFixture<HouseDetailPage>, el: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string, event = 'input') {
  el.value = value;
  el.dispatchEvent(new Event(event));
  return settled(fixture);
}

// The house page's map watches its container with a ResizeObserver, which jsdom does not have. Another spec (the Plan
// page's) stubs it globally, so this spec passed or failed with the order the specs ran in; it brings its own.
beforeEach(() => {
  if (typeof ResizeObserver === 'undefined') {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {
          // jsdom lays nothing out.
        }
        unobserve(): void {
          // Nothing observed.
        }
        disconnect(): void {
          // Nothing observed.
        }
      },
    );
  }
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
});

describe('HouseDetailPage: the Rooms section (slice 1c)', () => {
  it('shows the empty state and an enabled Add room button when the house has no rooms', async () => {
    const { host } = await open(null);
    expect(host.querySelector('#rooms-heading')?.textContent).toContain('Rooms');
    expect(host.querySelector('#rooms-empty')?.textContent).toContain('No rooms yet');
    expect(host.querySelectorAll('.room').length).toBe(0);
    expect(host.querySelector<HTMLButtonElement>('#rooms-add')!.disabled).toBe(false);
    expect(host.querySelector('#rooms-max')).toBeNull();
  });

  it('adds a room of type BEDROOM with the next sort and shows its card', async () => {
    const { fixture, host, page } = await open([ROOM]);
    host.querySelector<HTMLButtonElement>('#rooms-add')!.click();
    await settled(fixture);
    const rooms = page.draft().rooms!;
    expect(rooms.length).toBe(2);
    expect(rooms[1]).toMatchObject({ type: 'BEDROOM', sort: 1 });
    expect(host.querySelectorAll('.room').length).toBe(2);
    expect(host.querySelector('#rooms-empty')).toBeNull();
  });

  it('edits a room: type, name, size in feet and inches, condition and notes, in centimetres', async () => {
    const { fixture, host, page } = await open([{ id: 'r9', type: 'BEDROOM', sort: 0 }]);
    await type(fixture, host.querySelector<HTMLSelectElement>('#room-type-r9')!, 'KITCHEN', 'change');
    await type(fixture, host.querySelector<HTMLInputElement>('#room-name-r9')!, 'Galley');
    const feet = host.querySelector<HTMLInputElement>('#room-lengthCm-r9')!;
    const inches = feet.parentElement!.querySelectorAll('input')[1];
    inches.value = '6';
    await type(fixture, feet, '10', 'change');
    await type(fixture, host.querySelector<HTMLSelectElement>('#room-condition-r9')!, '3', 'change');
    await type(fixture, host.querySelector<HTMLTextAreaElement>('#room-notes-r9')!, 'Damp wall');
    expect(page.draft().rooms![0]).toMatchObject({ type: 'KITCHEN', name: 'Galley', lengthCm: 320, condition: 3, notes: 'Damp wall' });
  });

  it('shows each room area under its sizes and the total below the list', async () => {
    const { host } = await open([ROOM, { id: 'r2', type: 'BEDROOM', lengthCm: 305, widthCm: 305, sort: 1 }]);
    const areas = [...host.querySelectorAll('.room-area')].map((p) => p.textContent?.trim());
    expect(areas).toEqual(['Area: 156 sq ft', 'Area: 100 sq ft']);
    expect(host.querySelector('.room-total')?.textContent?.trim()).toBe('Total area: 256 sq ft');
  });

  it('deletes a room and shows the empty state when the last one goes', async () => {
    const { fixture, host, page } = await open([ROOM]);
    host.querySelector<HTMLButtonElement>('#room-delete-r1')!.click();
    await settled(fixture);
    expect(page.draft().rooms).toEqual([]);
    expect(host.querySelector('#rooms-empty')).not.toBeNull();
  });

  it('gives every control the room in its accessible name and keeps Add and Delete as buttons', async () => {
    const { host } = await open([ROOM]);
    const names = [...host.querySelectorAll('.room [aria-label]')].map((e) => e.getAttribute('aria-label'));
    expect(names.length).toBeGreaterThanOrEqual(8);
    for (const name of names) expect(name).toContain('1. Big hall');
    expect(host.querySelector('#room-delete-r1')!.tagName).toBe('BUTTON');
    expect(host.querySelector('#rooms-add')!.tagName).toBe('BUTTON');
  });

  it('disables Add room at 30 rooms and shows "At most 30 rooms"', async () => {
    const thirty = Array.from({ length: 30 }, (_, i): HouseRoom => ({ id: 'r' + i, type: 'OTHER', sort: i }));
    const { fixture, host, page } = await open(thirty);
    const add = host.querySelector<HTMLButtonElement>('#rooms-add')!;
    expect(add.disabled).toBe(true);
    expect(host.querySelector('#rooms-max')?.textContent?.trim()).toBe('At most 30 rooms');
    add.click();
    await settled(fixture);
    expect(page.draft().rooms!.length).toBe(30);
    // One fewer and it is offered again.
    host.querySelector<HTMLButtonElement>('#room-delete-r0')!.click();
    await settled(fixture);
    expect(host.querySelector<HTMLButtonElement>('#rooms-add')!.disabled).toBe(false);
    expect(host.querySelector('#rooms-max')).toBeNull();
  });

  it('switching the length unit changes the display only: the stored centimetres stay', async () => {
    const { fixture, host, page } = await open([ROOM], 'FT');
    const first = () => host.querySelectorAll<HTMLInputElement>('#room-lengthCm-r1, .room-size input');
    expect(host.querySelector<HTMLInputElement>('#room-lengthCm-r1')!.value).toBe('13');
    page.rooms.lengthUnit.set('M');
    await settled(fixture);
    expect(first().length).toBeGreaterThan(0);
    expect(host.querySelector<HTMLInputElement>('#room-lengthCm-r1')!.value).toBe('3.96');
    expect(host.querySelector('.room-area')?.textContent).toContain('14.5 m²');
    expect(page.draft().rooms![0]).toMatchObject({ lengthCm: 396, widthCm: 366 });
  });

  it('takes a size in metres with one decimal number and stores it in centimetres', async () => {
    const { fixture, host, page } = await open([{ id: 'r5', type: 'STUDY', sort: 0 }], 'M');
    await type(fixture, host.querySelector<HTMLInputElement>('#room-widthCm-r5')!, '3.66', 'change');
    expect(page.draft().rooms![0].widthCm).toBe(366);
  });

  it('saves the rooms through cleanRooms: coerced, and absent when there are none', async () => {
    const { fixture, host, saved } = await open([ROOM, { id: 'r2', type: 'BEDROOM', lengthCm: 9999, sort: 1 }]);
    host.querySelector<HTMLButtonElement>('.toolbar .btn-primary, button.btn-primary')!.click();
    await settled(fixture);
    expect(saved[0].rooms).toEqual([ROOM, { id: 'r2', type: 'BEDROOM', sort: 1 }]);
    host.querySelector<HTMLButtonElement>('#room-delete-r1')!.click();
    host.querySelector<HTMLButtonElement>('#room-delete-r2')!.click();
    await settled(fixture);
    host.querySelector<HTMLButtonElement>('button.btn-primary')!.click();
    await settled(fixture);
    expect(saved[1].rooms).toBeNull();
  });

  it('keeps the rooms in the unsaved draft and puts them back after a reload', async () => {
    const first = await open([ROOM]);
    first.host.querySelector<HTMLButtonElement>('#rooms-add')!.click();
    await settled(first.fixture);
    await new Promise((resolve) => setTimeout(resolve, 600));
    const stored = readDraft(draftKey(HOUSE.id, null, null));
    expect(stored?.draft.rooms?.length).toBe(2);
    // A reload keeps sessionStorage; leaving the page in-app would clear the draft, so put it back as a reload leaves it.
    first.fixture.destroy();
    writeDraft(draftKey(HOUSE.id, null, null), stored!);
    TestBed.resetTestingModule();
    const again = await open([ROOM]);
    expect(again.page.draft().rooms?.length).toBe(2);
    expect(again.host.querySelectorAll('.room').length).toBe(2);
  });
});

describe('HouseDetailPage: moving rooms and the floor (S4b-BL-87, S4b-BL-85)', () => {
  const two: HouseRoom[] = [ROOM, { id: 'r2', type: 'KITCHEN', name: 'Kitchen', sort: 1 }];

  it('moves a room up and down with buttons named after it, renumbering the sort; the ends are disabled', async () => {
    const { fixture, host, page } = await open(two);
    const up = (id: string) => host.querySelector<HTMLButtonElement>('#room-up-' + id)!;
    const down = (id: string) => host.querySelector<HTMLButtonElement>('#room-down-' + id)!;
    expect(up('r1').disabled).toBe(true);
    expect(down('r2').disabled).toBe(true);
    expect(up('r2').getAttribute('aria-label')).toBe('Move 2. Kitchen up');
    up('r2').click();
    await settled(fixture);
    expect(page.draft().rooms!.map((r) => [r.id, r.sort])).toEqual([['r2', 0], ['r1', 1]]);
    expect([...host.querySelectorAll('.room-title')].map((h) => h.textContent?.trim())).toEqual(['1. Kitchen', '2. Big hall']);
    down('r2').click();
    await settled(fixture);
    expect(page.draft().rooms!.map((r) => r.id)).toEqual(['r1', 'r2']);
  });

  it('saves a typed floor, 0 and a basement level included, and leaves one out of range unknown with a message', async () => {
    const { fixture, host, saved } = await open(null);
    const floor = host.querySelector<HTMLInputElement>('#house-floor')!;
    const basement = host.querySelector<HTMLInputElement>('#house-floor-basement')!;
    expect(host.querySelector('#house-floor-hint')?.textContent).toContain('0 is the ground floor');
    // A minus typed anyway turns the Basement switch on, and the field keeps the level (S4b-BL-104 c).
    await type(fixture, floor, '-2');
    expect(host.querySelector('#house-floor-error')).toBeNull();
    expect(basement.checked).toBe(true);
    await type(fixture, floor, '300');
    expect(host.querySelector('#house-floor-error')?.textContent).toContain('basement level from 1 to 5');
    basement.click();
    await settled(fixture);
    expect(host.querySelector('#house-floor-error')?.textContent).toContain('-5 to 200');
    expect(floor.getAttribute('aria-invalid')).toBe('true');
    await type(fixture, floor, '0');
    [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Save')!.click();
    await settled(fixture);
    expect(saved.at(-1)!.floor).toBe(0);
  });

  it('enters a basement with the Basement switch, no minus key needed (S4b-BL-104 c)', async () => {
    const { fixture, host, saved, page } = await open(null, 'FT', { floor: -3 });
    const floor = host.querySelector<HTMLInputElement>('#house-floor')!;
    const basement = host.querySelector<HTMLInputElement>('#house-floor-basement')!;
    const save = () => [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Save')!.click();
    // A saved basement shows its level, the switch on, and the switch's own label and role.
    expect(floor.value).toBe('3');
    expect(basement.checked).toBe(true);
    expect(basement.getAttribute('role')).toBe('switch');
    expect(host.querySelector('label[for="house-floor-basement"]')?.textContent?.trim()).toBe('Basement');
    expect(host.querySelector('#house-floor-hint')?.textContent).toContain('1 to 5');
    // Clearing the level keeps the switch, so a new level stays below the ground.
    await type(fixture, floor, '');
    expect(basement.checked).toBe(true);
    await type(fixture, floor, '1');
    expect(page.draft()!.floor).toBe(-1);
    // Off, the same digits are a floor above the ground; on again, below.
    basement.click();
    await settled(fixture);
    expect(page.draft()!.floor).toBe(1);
    basement.click();
    await settled(fixture);
    save();
    await settled(fixture);
    expect(saved.at(-1)!.floor).toBe(-1);
    // A basement 0 is not a level: the save is blocked with the message and the focus on Floor.
    await type(fixture, floor, '0');
    expect(host.querySelector('#house-floor-error')?.textContent).toContain('basement level from 1 to 5');
    const count = saved.length;
    floor.scrollIntoView = () => undefined; // jsdom has no layout
    save();
    await settled(fixture);
    expect(saved.length).toBe(count);
    expect(document.activeElement).toBe(floor);
  });

  it('warns, without blocking, of another house within 30 m with the same bedrooms and floor', async () => {
    const twin: HouseDto = { ...HOUSE, id: 'twin', label: 'Same flat, other broker', lat: 12.97161, bedrooms: 2, floor: 3, rooms: null };
    const far: HouseDto = { ...twin, id: 'far', label: 'Down the road', lat: 12.9726 };
    const { fixture, host } = await open(null, 'FT', { bedrooms: 2, floor: 3, locationSource: 'GPS' }, [twin, far]);
    expect(host.querySelector('#house-same-flat')?.textContent).toContain('Maybe the same flat as Same flat, other broker');
    expect(host.querySelector('#house-same-flat')?.textContent).not.toContain('Down the road');
    await type(fixture, host.querySelector<HTMLInputElement>('#house-floor')!, '4');
    expect(host.querySelector('#house-same-flat')).toBeNull();
  });
});


describe('HouseDetailPage: the rooms and floor edges (S4b-BL-168 slice 6, written before the code moved)', () => {
  it('numbers a new room after the highest sort, counting a room without one as 0', async () => {
    const { fixture, host, page } = await open([
      { id: 'a', type: 'HALL', sort: 4 },
      { id: 'b', type: 'HALL', sort: 9 },
      { id: 'c', type: 'HALL' },
    ]);
    host.querySelector<HTMLButtonElement>('#rooms-add')!.click();
    await settled(fixture);
    expect(page.draft().rooms!.map((r) => r.sort)).toEqual([4, 9, undefined, 10]);
    expect(page.draft().rooms![3]).toMatchObject({ type: 'BEDROOM' });
  });

  it('puts the focus on the new room\'s type, on Add room after a delete, and on the other arrow at the end of the list', async () => {
    const { fixture, host, page } = await open([ROOM, { id: 'r2', type: 'KITCHEN', sort: 1 }]);
    host.querySelector<HTMLButtonElement>('#rooms-add')!.click();
    await settled(fixture);
    const added = page.draft().rooms![2].id;
    expect(document.activeElement?.id).toBe('room-type-' + added);
    host.querySelector<HTMLButtonElement>('#room-delete-' + added)!.click();
    await settled(fixture);
    expect(document.activeElement?.id).toBe('rooms-add');
    // The second room moves up to the top, where its Up button is disabled: the Down button takes the focus.
    host.querySelector<HTMLButtonElement>('#room-up-r2')!.click();
    await settled(fixture);
    expect(document.activeElement?.id).toBe('room-down-r2');
    host.querySelector<HTMLButtonElement>('#room-down-r2')!.click();
    await settled(fixture);
    expect(document.activeElement?.id).toBe('room-up-r2');
  });

  it('names a room by its type when the name is blank, and keeps a typed name trimmed', async () => {
    const { host } = await open([
      { id: 'a', type: 'KITCHEN', name: '   ', sort: 0 },
      { id: 'b', type: 'HALL', name: '  Den ', sort: 1 },
    ]);
    expect([...host.querySelectorAll('.room-title')].map((h) => h.textContent?.trim())).toEqual(['1. Kitchen', '2. Den']);
  });

  it('turns a type the list does not know into Other, and a cleared condition into none', async () => {
    const { fixture, host, page } = await open([{ id: 'r1', type: 'HALL', condition: 4, sort: 0 }]);
    await type(fixture, host.querySelector<HTMLSelectElement>('#room-condition-r1')!, '', 'change');
    expect(page.draft().rooms![0].condition).toBeNull();
    const select = host.querySelector<HTMLSelectElement>('#room-type-r1')!;
    const option = document.createElement('option');
    option.value = 'ATTIC';
    select.append(option);
    await type(fixture, select, 'ATTIC', 'change');
    expect(page.draft().rooms![0].type).toBe('OTHER');
  });

  it('reads feet with blank inches, clears a size when both boxes are blank, and leaves the other size alone', async () => {
    const { fixture, host, page } = await open([{ id: 'r1', type: 'HALL', lengthCm: 305, widthCm: 244, sort: 0 }]);
    const length = host.querySelector<HTMLInputElement>('#room-lengthCm-r1')!;
    const inches = length.parentElement!.querySelectorAll('input')[1];
    inches.value = '';
    await type(fixture, length, '12', 'change');
    expect(page.draft().rooms![0]).toMatchObject({ lengthCm: 366, widthCm: 244 });
    length.value = '';
    await type(fixture, inches, '', 'change');
    expect(page.draft().rooms![0].lengthCm).toBeNull();
    expect(page.draft().rooms![0].widthCm).toBe(244);
    expect(length.value).toBe('');
    expect(inches.value).toBe('');
  });

  it('clears a size typed in metres when it is blank or out of range, and shows an unknown size as empty', async () => {
    const { fixture, host, page } = await open([{ id: 'r1', type: 'HALL', lengthCm: 305, sort: 0 }], 'M');
    const width = host.querySelector<HTMLInputElement>('#room-widthCm-r1')!;
    const length = host.querySelector<HTMLInputElement>('#room-lengthCm-r1')!;
    expect(width.value).toBe('');
    expect(length.value).toBe('3.05');
    await type(fixture, length, '', 'change');
    expect(page.draft().rooms![0].lengthCm).toBeNull();
    await type(fixture, width, '99', 'change');
    expect(page.draft().rooms![0].widthCm).toBeNull();
  });

  it('shows an area only when both sizes are known, and no total when no room has both', async () => {
    const { host } = await open([{ id: 'r1', type: 'HALL', lengthCm: 305, sort: 0 }, { id: 'r2', type: 'HALL', sort: 1 }]);
    expect(host.querySelector('.room-area')).toBeNull();
    expect(host.querySelector('.room-total')).toBeNull();
  });

  it('shows areas in square metres in the metres setting and groups thousands of square feet', async () => {
    const metres = await open([ROOM, { id: 'r2', type: 'BEDROOM', lengthCm: 305, widthCm: 305, sort: 1 }], 'M');
    expect(metres.host.querySelector('.room-total')?.textContent?.trim()).toBe('Total area: 23.8 m²');
    TestBed.resetTestingModule();
    const big = await open([{ id: 'r1', type: 'HALL', lengthCm: 1500, widthCm: 1200, sort: 0 }], 'FT');
    expect(big.host.querySelector('.room-area')?.textContent?.trim()).toBe('Area: 1,938 sq ft');
  });

  it('shows the level without its sign, and leaves a blank or zero level to the switch', async () => {
    const { fixture, host, page } = await open(null, 'FT', { floor: -2 });
    const floor = host.querySelector<HTMLInputElement>('#house-floor')!;
    const basement = host.querySelector<HTMLInputElement>('#house-floor-basement')!;
    expect(floor.value).toBe('2');
    // The switch with no level typed changes nothing in the draft; the first level typed then follows the switch.
    await type(fixture, floor, '');
    expect(basement.checked).toBe(true);
    basement.click();
    await settled(fixture);
    expect(page.draft().floor).toBeNull();
    await type(fixture, floor, '2');
    expect(page.draft().floor).toBe(2);
    basement.click();
    await settled(fixture);
    expect(page.draft().floor).toBe(-2);
    // Zero carries no sign, whatever the switch says; a basement 0 stays 0 and is refused by the message.
    await type(fixture, floor, '0');
    expect(page.draft().floor).toBe(0);
    basement.click();
    await settled(fixture);
    expect(page.draft().floor).toBe(0);
    expect(host.querySelector('#house-floor-error')).toBeNull();
    basement.click();
    await settled(fixture);
    expect(page.draft().floor).toBe(0);
    expect(host.querySelector('#house-floor-error')?.textContent).toContain('basement level from 1 to 5');
  });

  it('turns a floor above the ground into a basement and back with the switch, and refuses a basement past 5 and a floor past 200', async () => {
    const { fixture, host, page } = await open(null, 'FT', { floor: 4 });
    const basement = host.querySelector<HTMLInputElement>('#house-floor-basement')!;
    basement.click();
    await settled(fixture);
    expect(page.draft().floor).toBe(-4);
    await type(fixture, host.querySelector<HTMLInputElement>('#house-floor')!, '6');
    expect(host.querySelector('#house-floor-error')?.textContent).toContain('basement level from 1 to 5');
    expect(host.querySelector('#house-floor')!.getAttribute('aria-describedby')).toBe('house-floor-error');
    basement.click();
    await settled(fixture);
    expect(host.querySelector('#house-floor-error')).toBeNull();
    await type(fixture, host.querySelector<HTMLInputElement>('#house-floor')!, '201');
    expect(host.querySelector('#house-floor-error')?.textContent).toContain('-5 to 200');
    await type(fixture, host.querySelector<HTMLInputElement>('#house-floor')!, '200');
    expect(host.querySelector('#house-floor-error')).toBeNull();
    expect(host.querySelector('#house-floor')!.getAttribute('aria-describedby')).toBe('house-floor-hint');
  });

  it('adds no 31st room even when asked directly, and shows a size as feet and inches', async () => {
    const thirty = Array.from({ length: 30 }, (_, i): HouseRoom => ({ id: 'r' + i, type: 'OTHER', lengthCm: 320, sort: i }));
    const { fixture, host, page } = await open(thirty);
    page.rooms.add();
    await settled(fixture);
    expect(page.draft().rooms!.length).toBe(30);
    const length = host.querySelector<HTMLInputElement>('#room-lengthCm-r0')!;
    expect([length.value, length.parentElement!.querySelectorAll('input')[1].value]).toEqual(['10', '6']);
  });

  it('calls a fractional floor not a floor: the message shows and the save is refused', async () => {
    const { fixture, host, saved } = await open(null);
    const floor = host.querySelector<HTMLInputElement>('#house-floor')!;
    await type(fixture, floor, '2.5');
    expect(host.querySelector('#house-floor-error')?.textContent).toContain('-5 to 200');
    floor.scrollIntoView = () => undefined; // jsdom has no layout
    [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Save')!.click();
    await settled(fixture);
    expect(saved.length).toBe(0);
  });

  it('keeps the focus on the arrow pressed while the room can still move that way, and shows no feet or inches for an unknown size', async () => {
    const three: HouseRoom[] = [
      { id: 'a', type: 'HALL', sort: 0 },
      { id: 'b', type: 'HALL', sort: 1 },
      { id: 'c', type: 'HALL', sort: 2 },
    ];
    const { fixture, host } = await open(three);
    host.querySelector<HTMLButtonElement>('#room-up-c')!.click();
    await settled(fixture);
    expect(document.activeElement?.id).toBe('room-up-c');
    host.querySelector<HTMLButtonElement>('#room-down-a')!.click();
    await settled(fixture);
    expect(document.activeElement?.id).toBe('room-down-a');
    const length = host.querySelector<HTMLInputElement>('#room-lengthCm-a')!;
    expect([length.value, length.parentElement!.querySelectorAll('input')[1].value]).toEqual(['', '']);
  });

  it('names an untitled twin and joins several twins in the duplicate-flat warning', async () => {
    const twin: HouseDto = { ...HOUSE, id: 'twin', label: '  ', lat: 12.97161, bedrooms: 2, floor: 3, rooms: null };
    const other: HouseDto = { ...twin, id: 'other', label: 'Corner flat' };
    const { host } = await open(null, 'FT', { bedrooms: 2.4, floor: 3 }, [twin, other]);
    const line = host.querySelector('#house-same-flat')?.textContent ?? '';
    expect(line).toContain('Untitled');
    expect(line).toContain('Corner flat');
    expect(line).toContain(' and ');
  });
});
