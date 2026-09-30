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
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfirmService } from '../../core/confirm.service';
import { LocalDataService } from '../../core/local-data.service';
import { LocalDataError } from '../../core/local-error';
import type { HouseDto } from '../../core/models';
import { TranslationService } from '../../i18n/translation.service';
import type { Viewing, ViewingRow } from '../../shared/viewing';
import { viewingIcs } from '../../shared/viewing-ics';
import { ViewingPage, fromLocalInput, toLocalInput } from './viewing-page';

const house = (id: string, over: Partial<HouseDto> = {}): HouseDto => ({
  id, label: `House ${id}`, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, ...over,
});
const H1 = house('h1', { label: 'Green View 2BHK', address: '12, MG Road' });
const H2 = house('h2', { label: 'Blue Gate' });
const STARTS = new Date(2026, 8, 27, 15, 30).getTime();
const STORED: Viewing = {
  id: 'v_a1b2c3d4', houseId: 'h1', startsAt: STARTS, durationMin: 45, kind: 'SECOND', status: 'PLANNED', remindMin: 30,
  huntReminder: true, withWhom: 'Meena Iyer', notes: 'Bring a tape', visitId: 'vis-1',
};
const ROW: ViewingRow = { id: STORED.id, updatedAt: '2026-09-22T10:15:30.000Z', viewing: STORED };

interface Fakes {
  rows: ViewingRow[];
  saveViewing: ReturnType<typeof vi.fn>;
  deleteViewing: ReturnType<typeof vi.fn>;
  newViewingId: ReturnType<typeof vi.fn>;
}
const fakes = (rows: ViewingRow[] = []): Fakes => ({
  rows,
  saveViewing: vi.fn((x: Viewing) => of(x)),
  deleteViewing: vi.fn(() => of(undefined)),
  newViewingId: vi.fn(() => of('v_0a0b0c0d')),
});

// A Tamil test saves its language in localStorage; a later spec file in the same worker (the compare page) would start
// in Tamil and fail order-dependently, so every test ends with a clean store.
afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
});

async function render(f: Fakes, params: Record<string, string> = {}, query: Record<string, string> = {}, confirm = true, lang: 'en' | 'ta' = 'en') {
  const ask = vi.fn(() => Promise.resolve(confirm));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [ViewingPage],
    providers: [
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap(params), queryParamMap: convertToParamMap(query) } } },
      { provide: ConfirmService, useValue: { ask } },
      {
        provide: LocalDataService,
        useValue: {
          houses: () => of([H2, H1]),
          viewingRows: () => of(f.rows),
          saveViewing: f.saveViewing,
          deleteViewing: f.deleteViewing,
          newViewingId: f.newViewingId,
        },
      },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(ViewingPage);
  fixture.detectChanges();
  await fixture.whenStable();
  // The page loads through promises Angular does not track.
  await flush();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, f, ask, navigate, fixture };
}

function flush(): Promise<unknown> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
const setValue = (host: HTMLElement, selector: string, value: string) => {
  const el = host.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(selector)!;
  el.value = value;
  el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input'));
};
const submit = async (host: HTMLElement, fixture: { detectChanges: () => void }) => {
  host.querySelector('form')!.dispatchEvent(new Event('submit'));
  await flush();
  fixture.detectChanges();
};

describe('ViewingPage', () => {
  let originalCreate: typeof URL.createObjectURL;
  let originalRevoke: typeof URL.revokeObjectURL;
  beforeEach(() => {
    originalCreate = URL.createObjectURL;
    originalRevoke = URL.revokeObjectURL;
  });
  afterEach(() => {
    URL.createObjectURL = originalCreate;
    URL.revokeObjectURL = originalRevoke;
  });

  it('converts a local date and time to an instant and back, and refuses a blank or broken value', () => {
    expect(toLocalInput(STARTS)).toBe('2026-09-27T15:30');
    expect(fromLocalInput('2026-09-27T15:30')).toBe(STARTS);
    expect(fromLocalInput('')).toBeNull();
    expect(fromLocalInput('27/09/2026')).toBeNull();
  });

  it('opens a new viewing with the house and the kind from the link (Plan a viewing, Book a second viewing)', async () => {
    const { host } = await render(fakes(), {}, { houseId: 'h2', kind: 'SECOND' });
    expect(host.querySelector('h1')?.textContent).toContain('Plan a viewing');
    expect(host.querySelector<HTMLSelectElement>('#viewing-house')!.value).toBe('h2');
    expect(host.querySelector<HTMLSelectElement>('#viewing-kind')!.value).toBe('SECOND');
    expect(host.querySelector<HTMLSelectElement>('#viewing-duration')!.selectedOptions[0].textContent?.trim()).toBe('30 min');
    expect(host.querySelector<HTMLSelectElement>('#viewing-remind')!.selectedOptions[0].textContent?.trim()).toBe('1 hour before');
    expect(host.querySelector('button.btn-danger')).toBeNull();
  });

  it('ignores a house in the link that does not exist and an unknown kind', async () => {
    const { host } = await render(fakes(), {}, { houseId: 'nope', kind: 'THIRD' });
    expect(host.querySelector<HTMLSelectElement>('#viewing-house')!.value).toBe('');
    expect(host.querySelector<HTMLSelectElement>('#viewing-kind')!.value).toBe('FIRST');
  });

  it('offers the houses by name, durations in 5-minute steps, the three kinds and the six reminders, and no Hunt reminder switch', async () => {
    const { host } = await render(fakes());
    expect([...host.querySelectorAll('#viewing-house option')].map((o) => o.textContent?.trim())).toEqual(['Choose a house', 'Blue Gate', 'Green View 2BHK']);
    const durations = [...host.querySelectorAll('#viewing-duration option')].map((o) => o.textContent?.trim());
    expect(durations).toHaveLength(96);
    expect(durations.slice(0, 3)).toEqual(['5 min', '10 min', '15 min']);
    expect(durations[durations.length - 1]).toBe('8 h 0 min');
    expect([...host.querySelectorAll('#viewing-kind option')].map((o) => o.textContent?.trim())).toEqual(['First viewing', 'Second viewing', 'Follow-up']);
    expect([...host.querySelectorAll('#viewing-remind option')].map((o) => o.textContent?.trim())).toEqual([
      'Off', '15 min before', '30 min before', '1 hour before', '2 hours before', '1 day before',
    ]);
    expect(host.querySelector('input[type="checkbox"], [role="switch"]')).toBeNull();
    expect(host.textContent?.toLowerCase()).not.toContain('hunt');
  });

  it('requires a house and a time: it says which, focuses the first, and saves nothing', async () => {
    const { host, f, fixture } = await render(fakes());
    await submit(host, fixture);
    expect(f.saveViewing).not.toHaveBeenCalled();
    expect(host.querySelector('#viewing-house-error')?.textContent).toContain('Choose a house.');
    expect(host.querySelector('#viewing-house')?.getAttribute('aria-invalid')).toBe('true');
    setValue(host, '#viewing-house', 'h1');
    await submit(host, fixture);
    expect(host.querySelector('#viewing-house-error')).toBeNull();
    expect(host.querySelector('#viewing-when-error')?.textContent).toContain('Enter a date and time.');
    expect(host.querySelector('#viewing-when')?.getAttribute('aria-invalid')).toBe('true');
    expect(f.saveViewing).not.toHaveBeenCalled();
  });

  it('saves a new viewing with a v_ id, the local time as an instant, PLANNED, and goes to the list', async () => {
    const { host, f, fixture, navigate } = await render(fakes(), {}, { houseId: 'h1' });
    setValue(host, '#viewing-when', '2026-09-27T15:30');
    setValue(host, '#viewing-whom', '  Meena Iyer ');
    setValue(host, '#viewing-notes', 'Bring a tape');
    await submit(host, fixture);
    expect(f.newViewingId).toHaveBeenCalled();
    expect(f.saveViewing).toHaveBeenCalledTimes(1);
    expect(f.saveViewing).toHaveBeenCalledWith({
      id: 'v_0a0b0c0d', houseId: 'h1', startsAt: STARTS, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60,
      withWhom: 'Meena Iyer', notes: 'Bring a tape',
    });
    expect(navigate).toHaveBeenCalledWith(['/viewings']);
  });

  it('allows a time in the past, to log a viewing afterwards', async () => {
    const { host, f, fixture } = await render(fakes(), {}, { houseId: 'h1' });
    setValue(host, '#viewing-when', '2020-01-02T10:00');
    await submit(host, fixture);
    expect(f.saveViewing).toHaveBeenCalledWith(expect.objectContaining({ startsAt: new Date(2020, 0, 2, 10, 0).getTime(), status: 'PLANNED' }));
  });

  it('saves the duration, kind and reminder that were chosen, and leaves the empty text fields out', async () => {
    const { host, f, fixture } = await render(fakes(), {}, { houseId: 'h1' });
    setValue(host, '#viewing-when', '2026-09-27T15:30');
    // ngValue options carry an internal key, so choose by position: 5-minute steps, index 8 is 45 minutes.
    const pick = (selector: string, index: number) => {
      const el = host.querySelector<HTMLSelectElement>(selector)!;
      el.selectedIndex = index;
      el.dispatchEvent(new Event('change'));
    };
    pick('#viewing-duration', 8);
    setValue(host, '#viewing-kind', 'FOLLOW_UP');
    pick('#viewing-remind', 0);
    await submit(host, fixture);
    const saved = f.saveViewing.mock.calls[0][0] as Viewing;
    expect(saved).toMatchObject({ kind: 'FOLLOW_UP', remindMin: 0, durationMin: 45, status: 'PLANNED' });
    expect(saved).not.toHaveProperty('withWhom');
    expect(saved).not.toHaveProperty('notes');
    expect(saved).not.toHaveProperty('huntReminder');
  });

  it('edits a stored viewing: every field is filled in, and a save keeps its status, visit and hunt flag', async () => {
    const { host, f, fixture } = await render(fakes([ROW]), { id: STORED.id });
    expect(host.querySelector('h1')?.textContent).toContain('Viewing');
    expect(host.querySelector<HTMLSelectElement>('#viewing-house')!.value).toBe('h1');
    expect(host.querySelector<HTMLInputElement>('#viewing-when')!.value).toBe('2026-09-27T15:30');
    expect(host.querySelector<HTMLSelectElement>('#viewing-kind')!.value).toBe('SECOND');
    expect(host.querySelector<HTMLInputElement>('#viewing-whom')!.value).toBe('Meena Iyer');
    expect(host.querySelector<HTMLTextAreaElement>('#viewing-notes')!.value).toBe('Bring a tape');
    setValue(host, '#viewing-notes', 'Bring a tape and a torch');
    await submit(host, fixture);
    expect(f.newViewingId).not.toHaveBeenCalled();
    expect(f.saveViewing).toHaveBeenCalledWith({ ...STORED, notes: 'Bring a tape and a torch' });
  });

  it('keeps a house that is gone as an option, so saving does not change it', async () => {
    const gone: ViewingRow = { ...ROW, viewing: { ...STORED, houseId: 'h-gone' } };
    const { host, f, fixture } = await render(fakes([gone]), { id: STORED.id });
    const select = host.querySelector<HTMLSelectElement>('#viewing-house')!;
    expect(select.selectedOptions[0].textContent?.trim()).toBe('A house that is gone');
    await submit(host, fixture);
    expect(f.saveViewing).toHaveBeenCalledWith(expect.objectContaining({ houseId: 'h-gone' }));
  });

  it('keeps a stored duration that is not a multiple of 5 in the list', async () => {
    const odd: ViewingRow = { ...ROW, viewing: { ...STORED, durationMin: 7 } };
    const { host } = await render(fakes([odd]), { id: STORED.id });
    expect(host.querySelector<HTMLSelectElement>('#viewing-duration')!.selectedOptions[0].textContent?.trim()).toBe('7 min');
  });

  it('says so when the viewing is not in this browser', async () => {
    const { host } = await render(fakes([]), { id: 'v_00000000' });
    expect(host.textContent).toContain('This viewing is not in this browser.');
    expect(host.querySelector('form')).toBeNull();
  });

  it('cancels a stored viewing with Cancel viewing: the same form with the status CANCELLED', async () => {
    const { host, f, navigate } = await render(fakes([ROW]), { id: STORED.id });
    const cancel = [...host.querySelectorAll<HTMLButtonElement>('.actions button')].find((b) => b.textContent?.trim() === 'Cancel viewing')!;
    cancel.click();
    await flush();
    expect(f.saveViewing).toHaveBeenCalledWith({ ...STORED, status: 'CANCELLED' });
    expect(navigate).toHaveBeenCalledWith(['/viewings']);
  });

  it('deletes a viewing only after asking', async () => {
    const yes = await render(fakes([ROW]), { id: STORED.id });
    yes.host.querySelector<HTMLButtonElement>('button.btn-danger')!.click();
    await flush();
    expect(yes.ask).toHaveBeenCalledWith({ key: 'viewings.confirmDelete' }, expect.objectContaining({ danger: true }));
    expect(yes.f.deleteViewing).toHaveBeenCalledWith(STORED.id);
    expect(yes.navigate).toHaveBeenCalledWith(['/viewings']);
    const no = await render(fakes([ROW]), { id: STORED.id }, {}, false);
    no.host.querySelector<HTMLButtonElement>('button.btn-danger')!.click();
    await flush();
    expect(no.f.deleteViewing).not.toHaveBeenCalled();
    expect(no.navigate).not.toHaveBeenCalled();
  });

  it('shows a refused save (the cap) and stays on the form', async () => {
    const f = fakes();
    f.saveViewing = vi.fn(() => throwError(() => new LocalDataError('viewings.max')));
    const { host, fixture, navigate } = await render(f, {}, { houseId: 'h1' });
    setValue(host, '#viewing-when', '2026-09-27T15:30');
    await submit(host, fixture);
    expect(host.querySelector('.error [role="alert"]')?.textContent).toContain('At most 5,000 viewings');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('adds to the calendar by downloading the .ics file of the stored viewing, the sample text byte for byte', async () => {
    const created: Blob[] = [];
    URL.createObjectURL = vi.fn((blob: Blob) => {
      created.push(blob);
      return 'blob:test';
    });
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const { host } = await render(fakes([ROW]), { id: STORED.id });
    const button = [...host.querySelectorAll<HTMLButtonElement>('.actions button')].find((b) => b.textContent?.trim() === 'Add to calendar')!;
    button.click();
    await flush();
    expect(click).toHaveBeenCalledTimes(1);
    expect(created).toHaveLength(1);
    expect(created[0].type).toBe('text/calendar;charset=utf-8');
    const expected = viewingIcs(
      { ...STORED, withWhom: 'Meena Iyer', visitId: 'vis-1' }, 'Green View 2BHK', '12, MG Road', 'Viewing', Date.parse(ROW.updatedAt!),
    );
    expect(await created[0].text()).toBe(expected);
    expect(expected).not.toContain('Meena');
    expect(expected).toContain('UID:v_a1b2c3d4@doorprints');
    click.mockRestore();
  });

  it('builds the calendar file of a form that is not saved yet, and needs a house and a time first', async () => {
    const created: Blob[] = [];
    URL.createObjectURL = vi.fn((blob: Blob) => {
      created.push(blob);
      return 'blob:test';
    });
    URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const { host, fixture } = await render(fakes(), {}, {});
    const button = [...host.querySelectorAll<HTMLButtonElement>('.actions button')].find((b) => b.textContent?.trim() === 'Add to calendar')!;
    button.click();
    await flush();
    fixture.detectChanges();
    expect(created).toHaveLength(0);
    expect(host.querySelector('#viewing-house-error')).not.toBeNull();
    setValue(host, '#viewing-house', 'h2');
    setValue(host, '#viewing-when', '2026-09-27T15:30');
    button.click();
    await flush();
    expect(created).toHaveLength(1);
    const text = await created[0].text();
    expect(text).toContain('SUMMARY:Viewing: Blue Gate\r\n');
    expect(text).not.toContain('LOCATION');
    expect(text).toContain('UID:v_0a0b0c0d@doorprints\r\n');
    click.mockRestore();
  });

  it('shows the form in Tamil and labels every control', async () => {
    const { host } = await render(fakes(), {}, {}, true, 'ta');
    expect(host.querySelector('h1')?.textContent).toContain('பார்வையிடலைத் திட்டமிடு');
    for (const id of ['viewing-house', 'viewing-when', 'viewing-duration', 'viewing-kind', 'viewing-remind', 'viewing-whom', 'viewing-notes']) {
      expect(host.querySelector(`label[for="${id}"]`)?.textContent?.trim(), id).not.toBe('');
    }
    expect(host.querySelector('#viewing-when')?.getAttribute('type')).toBe('datetime-local');
    expect(host.querySelector('#viewing-whom')?.getAttribute('maxlength')).toBe('200');
    expect(host.querySelector('#viewing-notes')?.getAttribute('maxlength')).toBe('2000');
  });
});
