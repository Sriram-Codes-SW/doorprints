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
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Announcer } from '../../core/announcer.service';
import { BulkGuard } from '../../core/bulk-guard.service';
import { ConfirmService } from '../../core/confirm.service';
import { DeleteUndoService } from '../../core/delete-undo.service';
import { EXPERIMENTAL_KINDS_KEY } from '../../core/kind-setting';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { TranslationService } from '../../i18n/translation.service';
import { KindsSettings, TICK_OVER } from './kinds-settings';

const row = (id: string, kind?: string, deleted = false) => ({ id, kind, label: id, lat: 26, lon: 91, status: 'NEW', deleted, updatedAt: '2026-10-01T00:00:00.000Z' }) as unknown as HouseDto;

/**
 * Settings > What you track > *Delete all in this space* (deletion safeguards PR 2, S4b-BL-283; docs/02 T-D15, docs/06
 * TC-U-216): per card; only that space's live candidates; the count and the kind in the words; a tick box over ten rows;
 * *Save a copy first*; one bulk action per 10 seconds.
 */
describe('Your data: Delete all in this space', () => {
  const removeMany = vi.fn(async (ids: readonly string[]) => ids.length);
  const choose = vi.fn();
  const announce = vi.fn();

  async function open(houses: HouseDto[]) {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    TestBed.configureTestingModule({
      imports: [KindsSettings],
      providers: [
        provideRouter([]),
        { provide: LocalDataService, useValue: { houses: () => of(houses) } },
        { provide: DeleteUndoService, useValue: { removeMany } },
        { provide: ConfirmService, useValue: { choose } },
        { provide: Announcer, useValue: { announce } },
      ],
    });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(KindsSettings);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { host: fixture.nativeElement as HTMLElement, fixture };
  }
  const btn = (host: HTMLElement, id: string) => host.querySelector<HTMLButtonElement>(`#kind-delete-${id}`);
  const click = async (b: HTMLButtonElement | null) => { b!.click(); await new Promise((r) => setTimeout(r, 0)); };

  beforeEach(() => {
    removeMany.mockClear();
    choose.mockReset();
    announce.mockClear();
  });
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('has one button per card with something saved, named for its kind, and none for an empty space', async () => {
    const { host } = await open([row('h1'), row('h2', 'house')]);
    expect(btn(host, 'house')?.textContent?.trim()).toBe('Delete all in this space');
    expect(btn(host, 'house')?.getAttribute('aria-label')).toBe('Delete all in this space: Houses');
    expect(btn(host, 'school')).toBeNull();
  });

  it('deletes only that space: the school card takes the schools and not one house, not a tombstone', async () => {
    choose.mockResolvedValue('confirm');
    const { host } = await open([row('h1'), row('h2', 'house'), row('s1', 'school'), row('s2', 'school'), row('s0', 'school', true)]);
    await click(btn(host, 'school'));
    const [message, options] = choose.mock.calls[0];
    expect(message).toEqual({ key: 'delete.spaceConfirm', params: { n: '2', kind: 'Schools' } });
    expect(options).toMatchObject({ confirmKey: 'delete.spaceDo', altKey: 'driveDelete.saveCopyFirst', danger: true, focusCancel: true, tickKey: null });
    expect(removeMany).toHaveBeenCalledTimes(1);
    expect(removeMany).toHaveBeenCalledWith(['s1', 's2'], 'Schools');
  });

  it('asks for a tick box only over ten rows', async () => {
    choose.mockResolvedValue('cancel');
    const ten = Array.from({ length: TICK_OVER }, (_, i) => row(`a${i}`));
    const { host, fixture } = await open([...ten, row('s1', 'school')]);
    await click(btn(host, 'house'));
    expect(choose.mock.calls[0][1].tickKey).toBeNull();
    TestBed.resetTestingModule();
    const eleven = await open([...ten, row('a10')]);
    await click(btn(eleven.host, 'house'));
    expect(choose.mock.calls[1][1].tickKey).toBe('driveDelete.confirmCheckbox');
    expect(fixture).toBeTruthy();
    expect(removeMany).not.toHaveBeenCalled();
  });

  it('Cancel and Save a copy first delete nothing, and the second leaves for the copies', async () => {
    const target = document.createElement('h2');
    target.id = 'export-heading';
    target.tabIndex = -1;
    document.body.appendChild(target);
    const { host } = await open([row('h1')]);
    choose.mockResolvedValueOnce('cancel');
    await click(btn(host, 'house'));
    choose.mockResolvedValueOnce('alt');
    await click(btn(host, 'house'));
    expect(removeMany).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(target);
    target.remove();
  });

  it('allows one bulk action per 10 seconds: the next card is refused with the time left in the words', async () => {
    choose.mockResolvedValue('confirm');
    const { host } = await open([row('h1'), row('s1', 'school')]);
    await click(btn(host, 'school'));
    expect(removeMany).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(BulkGuard).waitSeconds()).toBeGreaterThan(0);
    await click(btn(host, 'house'));
    expect(choose).toHaveBeenCalledTimes(1);
    expect(removeMany).toHaveBeenCalledTimes(1);
    expect(announce).toHaveBeenCalledWith({ key: 'delete.bulkWait', params: { n: expect.any(Number) } });
  });
});
