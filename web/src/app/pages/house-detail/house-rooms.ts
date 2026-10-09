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

// What the house page keeps about the rooms of one house (docs/11 5.6): the list in the draft with each room's type,
// name, sizes, condition and notes, adding, moving and deleting one, and the size and area words in the length unit
// the person chose. It is separate from `house-detail-page.ts` (S4b-BL-168) so that a change to what a room holds edits
// this file and the Rooms card of the page's template, not the 1,600-line page. The page owns the draft and the save;
// this changes the draft only through `patch`.
import { afterNextRender, signal } from '@angular/core';
import type { Injector } from '@angular/core';
import { ROOM_TYPES, ROOM_TYPE_KEY, uuid } from '../../core/models';
import type { HouseDto, HouseRoom, RoomType } from '../../core/models';
import type { TranslationService } from '../../i18n/translation.service';
import {
  areaNumber,
  areaSqCm,
  cmToFeetInches,
  metresText,
  moveRoom,
  parseFeetInches,
  parseMetres,
  totalAreaSqCm,
} from '../../shared/room-sizes';
import type { LengthUnit } from '../../shared/room-sizes';

/** The most rooms a house holds (the server and Android agree). */
const MAX_ROOMS = 30;

/** What the rooms of the house page need from the page and from the app. */
export interface HouseRoomsDeps {
  i18n: TranslationService;
  injector: Injector;
  /** The house being edited, or null before it is read. */
  draft: () => HouseDto | null;
  /** The page's one way to change the draft: merges the fields in and marks the page dirty. */
  patch: (changes: Partial<HouseDto>) => void;
}

export class HouseRooms {
  /** The length unit is a local display preference; the draft always holds centimetres. */
  readonly lengthUnit = signal<LengthUnit>('FT');
  readonly types = ROOM_TYPES;
  readonly typeKey = ROOM_TYPE_KEY;
  readonly max = MAX_ROOMS;
  readonly conditions = [1, 2, 3, 4, 5];
  readonly dims = [
    { key: 'lengthCm', label: 'rooms.length' },
    { key: 'widthCm', label: 'rooms.width' },
  ] as const;

  constructor(private readonly deps: HouseRoomsDeps) {}

  list(d: HouseDto): HouseRoom[] {
    return d.rooms ?? [];
  }

  /** The name typed, else the type's translated name; with the room's position so every control's name is unique. */
  title(r: HouseRoom, index: number): string {
    return `${index + 1}. ${r.name?.trim() || this.deps.i18n.t(ROOM_TYPE_KEY[r.type])}`;
  }

  add(): void {
    const d = this.deps.draft();
    if (!d) return;
    const rooms = this.list(d);
    if (rooms.length >= MAX_ROOMS) return;
    const sort = rooms.reduce((max, r) => Math.max(max, (r.sort ?? 0) + 1), 0);
    const room: HouseRoom = { id: uuid(), type: 'BEDROOM', sort };
    this.deps.patch({ rooms: [...rooms, room] });
    afterNextRender(() => document.getElementById('room-type-' + room.id)?.focus(), { injector: this.deps.injector });
  }

  edit(id: string, changes: Partial<HouseRoom>): void {
    const d = this.deps.draft();
    if (!d) return;
    this.deps.patch({ rooms: this.list(d).map((r) => (r.id === id ? { ...r, ...changes } : r)) });
  }

  /** Up (-1) or down (+1) in the order shown, every sort renumbered (S4b-BL-87); focus stays on the button pressed. */
  move(id: string, by: -1 | 1): void {
    const d = this.deps.draft();
    if (!d) return;
    this.deps.patch({ rooms: moveRoom(this.list(d), id, by) });
    const button = `room-${by < 0 ? 'up' : 'down'}-${id}`;
    afterNextRender(() => {
      const el = document.getElementById(button) as HTMLButtonElement | null;
      // At the top or the bottom the pressed button is disabled: the other one takes the focus.
      (el && !el.disabled ? el : document.getElementById(`room-${by < 0 ? 'down' : 'up'}-${id}`))?.focus();
    }, { injector: this.deps.injector });
  }

  remove(id: string): void {
    const d = this.deps.draft();
    if (!d) return;
    this.deps.patch({ rooms: this.list(d).filter((r) => r.id !== id) });
    // The focus goes to Add room, not to the top of the page.
    afterNextRender(() => document.getElementById('rooms-add')?.focus(), { injector: this.deps.injector });
  }

  setType(id: string, type: string): void {
    this.edit(id, { type: ROOM_TYPES.includes(type as RoomType) ? (type as RoomType) : 'OTHER' });
  }

  setCondition(id: string, value: string): void {
    this.edit(id, { condition: value === '' ? null : Number(value) });
  }

  /** Feet mode: the two boxes (feet, inches) make one size in cm; both blank is unknown. */
  setFeet(id: string, key: 'lengthCm' | 'widthCm', feet: string, inches: string): void {
    const blank = feet.trim() === '' && inches.trim() === '';
    this.edit(id, { [key]: blank ? null : parseFeetInches(feet, inches) });
  }

  /** Metres mode: one decimal number is one size in cm; blank or out of range is unknown. */
  setMetres(id: string, key: 'lengthCm' | 'widthCm', metres: string): void {
    this.edit(id, { [key]: parseMetres(metres) });
  }

  feetOf(cm: number | null | undefined): number | '' {
    return cm == null ? '' : cmToFeetInches(cm).feet;
  }

  inchesOf(cm: number | null | undefined): number | '' {
    return cm == null ? '' : cmToFeetInches(cm).inches;
  }

  metresOf(cm: number | null | undefined): string {
    return cm == null ? '' : metresText(cm);
  }

  /** "Area: 156 sq ft" (or "14.5 m²") once both sizes are known; the area of the stored centimetres, whatever the unit. */
  areaLine(r: HouseRoom): string | null {
    const sq = areaSqCm(r);
    return sq === null ? null : this.deps.i18n.t('rooms.area', { v: this.areaText(sq) });
  }

  /** "Total area: 312 sq ft" below the list, when at least one room has both sizes. */
  totalLine(d: HouseDto): string | null {
    const { total, sized } = totalAreaSqCm(this.list(d));
    return sized === 0 ? null : this.deps.i18n.t('rooms.total', { v: this.areaText(total) });
  }

  private areaText(sqCm: number): string {
    const unit = this.lengthUnit();
    const i18n = this.deps.i18n;
    return unit === 'M'
      ? i18n.t('rooms.sqm', { v: areaNumber(sqCm, unit) })
      : i18n.t('rooms.sqft', { v: i18n.number(Number(areaNumber(sqCm, unit))) });
  }
}
