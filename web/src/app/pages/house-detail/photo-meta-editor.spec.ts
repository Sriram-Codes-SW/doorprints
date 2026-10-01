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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Announcer } from '../../core/announcer.service';
import { LocalDataService } from '../../core/local-data.service';
import type { PhotoSummary } from '../../core/local-data.service';
import type { HouseRoom } from '../../core/models';
import { TranslationService } from '../../i18n/translation.service';
import type { Lang } from '../../i18n/languages';
import { PhotoMetaEditor } from './photo-meta-editor';

const ROOMS: HouseRoom[] = [
  { id: 'r1', type: 'KITCHEN', name: 'Kitchen', sort: 0 },
  { id: 'r2', type: 'BEDROOM', sort: 1 },
];

const PHOTO: PhotoSummary = { id: 'p1', createdAt: '2026-10-01T06:00:00.000Z', roomId: 'r1', tags: ['DAMP', 'leaky tap'], caption: 'Corner', metaUpdatedAt: 5 };

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 2; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  fixture.detectChanges();
}

async function make(photo: PhotoSummary = PHOTO, opts: { lang?: Lang; save?: () => ReturnType<LocalDataService['setPhotoMeta']> } = {}) {
  const saves: unknown[] = [];
  const api = {
    setPhotoMeta: (id: string, meta: unknown) => {
      saves.push([id, meta]);
      return opts.save ? opts.save() : of(true);
    },
  };
  TestBed.configureTestingModule({ imports: [PhotoMetaEditor], providers: [{ provide: LocalDataService, useValue: api }] });
  await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
  const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
  const fixture = TestBed.createComponent(PhotoMetaEditor);
  fixture.componentRef.setInput('photo', photo);
  fixture.componentRef.setInput('n', 3);
  fixture.componentRef.setInput('rooms', ROOMS);
  const events = { saved: 0, closed: 0 };
  fixture.componentInstance.saved.subscribe(() => events.saved++);
  fixture.componentInstance.closed.subscribe(() => events.closed++);
  await settle(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, saves, events, announce };
}

const q = <T extends HTMLElement>(host: HTMLElement, selector: string) => host.querySelector<T>(selector)!;
const button = (host: HTMLElement, label: string) => [...host.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === label)!;
const type = (input: HTMLInputElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
};
const pressed = (host: HTMLElement) => [...host.querySelectorAll('.chips button.chip[aria-pressed="true"]')].map((b) => b.textContent?.trim());

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('PhotoMetaEditor (slice 5, docs/11 5.7)', () => {
  it('starts from the stored meta: the room, the fixed tags pressed, the custom tags as chips, the caption', async () => {
    const { host } = await make();
    expect(q(host, 'h3').textContent?.trim()).toBe('Photo 3: room, tags and caption');
    expect(q<HTMLSelectElement>(host, '#photo-room-p1').value).toBe('r1');
    expect(pressed(host)).toEqual(['Damp']);
    expect([...host.querySelectorAll('.chip-custom')].map((c) => c.textContent?.replace('✕', '').trim())).toEqual(['leaky tap']);
    expect(q<HTMLInputElement>(host, '#photo-caption-p1').value).toBe('Corner');
  });

  it('offers No room, the rooms of the house by name or type, and keeps a room that is gone as Untagged', async () => {
    const { host } = await make();
    expect([...host.querySelectorAll('#photo-room-p1 option')].map((o) => o.textContent?.trim())).toEqual(['No room', 'Kitchen', 'Bedroom']);
  });

  it('keeps a room the house no longer has as Untagged until another is picked', async () => {
    const { host } = await make({ ...PHOTO, roomId: 'gone' });
    expect([...host.querySelectorAll('#photo-room-p1 option')].map((o) => o.textContent?.trim())).toEqual(['No room', 'Kitchen', 'Bedroom', 'Untagged']);
    expect(q<HTMLSelectElement>(host, '#photo-room-p1').value).toBe('gone');
  });

  it('shows all fifteen fixed tags as toggle buttons', async () => {
    const { host } = await make({ ...PHOTO, tags: [] });
    const chips = [...host.querySelectorAll('.chips button.chip')];
    expect(chips).toHaveLength(15);
    expect(chips.every((c) => c.getAttribute('aria-pressed') === 'false')).toBe(true);
  });

  it('saves the room, tags and caption that were chosen, and says so', async () => {
    const { fixture, host, saves, events, announce } = await make();
    const sel = q<HTMLSelectElement>(host, '#photo-room-p1');
    sel.value = 'r2';
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    button(host, 'Crack').click();
    button(host, 'Damp').click();
    type(q<HTMLInputElement>(host, '#photo-tag-p1'), 'new paint');
    await settle(fixture);
    button(host, 'Add tag').click();
    type(q<HTMLInputElement>(host, '#photo-caption-p1'), '  Wall by the window ');
    await settle(fixture);
    button(host, 'Save details').click();
    await settle(fixture);
    expect(saves).toEqual([['p1', { roomId: 'r2', tags: ['leaky tap', 'CRACK', 'new paint'], caption: 'Wall by the window' }]]);
    expect(events.saved).toBe(1);
    expect(announce).toHaveBeenCalledWith({ key: 'photoMeta.saved' });
  });

  it('saves No room and an empty caption as null', async () => {
    const { fixture, host, saves } = await make();
    const sel = q<HTMLSelectElement>(host, '#photo-room-p1');
    sel.value = '';
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    type(q<HTMLInputElement>(host, '#photo-caption-p1'), '');
    await settle(fixture);
    button(host, 'Save details').click();
    await settle(fixture);
    expect(saves).toEqual([['p1', { roomId: null, tags: ['DAMP', 'leaky tap'], caption: null }]]);
  });

  it('adds a custom tag on Enter, refuses a repeat or a fixed key in another case, and says why', async () => {
    const { fixture, host } = await make({ ...PHOTO, tags: [] });
    const field = q<HTMLInputElement>(host, '#photo-tag-p1');
    type(field, 'Leaky Tap');
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(fixture);
    expect(host.querySelectorAll('.chip-custom')).toHaveLength(1);
    expect(field.value).toBe('');
    type(field, 'leaky tap');
    await settle(fixture);
    button(host, 'Add tag').click();
    await settle(fixture);
    expect(host.querySelectorAll('.chip-custom')).toHaveLength(1);
    expect(host.querySelector('.field-error')?.textContent?.trim()).toBe('That tag is blank or already added.');
    // "damp" is the fixed tag Damp, so it turns that toggle on instead of making a custom tag.
    type(field, 'damp');
    await settle(fixture);
    button(host, 'Add tag').click();
    await settle(fixture);
    expect(pressed(host)).toEqual(['Damp']);
    expect(host.querySelectorAll('.chip-custom')).toHaveLength(1);
  });

  it('allows at most 10 tags of 30 characters, and says "At most 10 tags"', async () => {
    const ten = Array.from({ length: 10 }, (_, i) => `tag ${i}`);
    const { host } = await make({ ...PHOTO, tags: ten });
    expect(q<HTMLInputElement>(host, '#photo-tag-p1').disabled).toBe(true);
    expect(button(host, 'Add tag').disabled).toBe(true);
    expect(host.textContent).toContain('At most 10 tags');
    expect(q<HTMLInputElement>(host, '#photo-tag-p1').maxLength).toBe(30);
    expect(q<HTMLInputElement>(host, '#photo-caption-p1').maxLength).toBe(200);
  });

  it('removes a custom tag with its own button named after the tag', async () => {
    const { fixture, host, saves } = await make();
    const x = q<HTMLButtonElement>(host, '.chip-x');
    expect(x.getAttribute('aria-label')).toBe('Remove tag leaky tap');
    x.click();
    await settle(fixture);
    expect(host.querySelectorAll('.chip-custom')).toHaveLength(0);
    button(host, 'Save details').click();
    await settle(fixture);
    expect(saves[0]).toEqual(['p1', { roomId: 'r1', tags: ['DAMP'], caption: 'Corner' }]);
  });

  it('shows a failed save in an alert and does not close', async () => {
    const { fixture, host, events } = await make(PHOTO, { save: () => throwError(() => new Error('disk full')) });
    button(host, 'Save details').click();
    await settle(fixture);
    expect(q(host, '[role="alert"] .error').textContent).toContain('Could not save the photo details');
    expect(events.saved).toBe(0);
    expect(button(host, 'Save details').disabled).toBe(false);
  });

  it('closes without saving on Cancel, and keeps its typing from reaching the house form', async () => {
    const { fixture, host, saves, events } = await make();
    const reached = vi.fn();
    document.body.addEventListener('input', reached);
    document.body.addEventListener('change', reached);
    type(q<HTMLInputElement>(host, '#photo-caption-p1'), 'x');
    q<HTMLSelectElement>(host, '#photo-room-p1').dispatchEvent(new Event('change', { bubbles: true }));
    document.body.removeEventListener('input', reached);
    document.body.removeEventListener('change', reached);
    expect(reached).not.toHaveBeenCalled();
    button(host, 'Cancel').click();
    await settle(fixture);
    expect(events.closed).toBe(1);
    expect(saves).toEqual([]);
  });

  it.each(['en', 'hi', 'ta', 'te'] as const)('shows the labels in %s', async (lang) => {
    const { host } = await make(PHOTO, { lang });
    const t = TestBed.inject(TranslationService);
    expect(q(host, 'label[for="photo-room-p1"]').textContent?.trim()).toBe(t.t('photoMeta.room'));
    expect(q(host, 'legend').textContent?.trim()).toBe(t.t('photoMeta.tags'));
    expect(q(host, 'label[for="photo-caption-p1"]').textContent?.trim()).toBe(t.t('photoMeta.caption'));
    expect(button(host, t.t('photoMeta.save'))).toBeTruthy();
    expect([...host.querySelectorAll('.chips button.chip')].map((c) => c.textContent?.trim())).toContain(t.t('photoTag.WATER_TANK'));
  });
});
