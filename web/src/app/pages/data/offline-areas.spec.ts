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
import { afterEach, describe, expect, it } from 'vitest';
import { ConfirmService } from '../../core/confirm.service';
import { en } from '../../i18n/en';
import { hi } from '../../i18n/hi';
import { ta } from '../../i18n/ta';
import { te } from '../../i18n/te';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { OFFLINE_ENV, OfflineMapsService } from '../../offline/offline-maps.service';
import { setOfflineActive } from '../../offline/offline-protocol';
import { fakeEnv } from '../../offline/testing/offline-fakes';
import { OfflineAreasCard } from './offline-areas';

/** *Offline maps* on Your data (S4b-BL-79): the list of saved areas, delete after asking, the storage line, the empty and unsupported states. */
const DICTS = { en, hi, ta, te } as const;
const point = { south: 12.97, west: 77.64, north: 12.9701, east: 77.6401 };

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

async function render(opts: { lang?: Lang; seed?: (service: OfflineMapsService) => Promise<void>; unsupported?: boolean } = {}) {
  TestBed.resetTestingModule();
  const rig = fakeEnv();
  rig.env.hasCache = !opts.unsupported;
  TestBed.configureTestingModule({ imports: [OfflineAreasCard], providers: [provideRouter([]), { provide: OFFLINE_ENV, useValue: rig.env }] });
  await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
  const service = TestBed.inject(OfflineMapsService);
  await opts.seed?.(service);
  const fixture = TestBed.createComponent(OfflineAreasCard);
  await tick(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, service, rig };
}

const two = async (s: OfflineMapsService) => {
  await s.save('Indiranagar', point);
  await s.save('HSR Layout', { ...point, west: 77.9, east: 77.9001 });
};

describe('Offline maps card on Your data', () => {
  it('lists each saved area with its size and a Delete button named for it (44px class)', async () => {
    const { host } = await render({ seed: two });
    expect(host.querySelector('h2')!.textContent).toBe('Offline maps');
    expect(host.querySelector('section')!.getAttribute('aria-labelledby')).toBe('offline-heading');
    const rows = [...host.querySelectorAll('li')];
    expect(rows.map((li) => li.querySelector('strong')!.textContent)).toEqual(['HSR Layout', 'Indiranagar']);
    expect(rows[0].textContent).toMatch(/\d(\.\d)? MB/);
    const del = rows[1].querySelector('button')!;
    expect(del.getAttribute('aria-label')).toBe('Delete Indiranagar');
    expect(del.classList).toContain('btn');
    expect(host.textContent).toContain('Saved areas use about');
    expect(host.textContent).toContain('room left');
  });

  it('shows the empty state, and says so when the browser cannot keep maps', async () => {
    const empty = await render();
    expect(empty.host.querySelector('.empty')!.textContent).toBe('No areas saved yet.');
    expect(empty.host.querySelector('li')).toBeNull();
    const none = await render({ unsupported: true });
    expect(none.host.querySelector('.warn-box')!.textContent).toContain('cannot keep offline maps');
    expect(none.host.querySelector('li')).toBeNull();
  });

  it('shows an area the browser no longer holds as "Could not save"', async () => {
    const r = await render({ seed: async (s) => void (await s.save('Gone', point)) });
    r.rig.cache.entries.clear();
    await r.service.verify();
    await tick(r.fixture);
    expect(r.service.areas()[0].state).toBe('failed');
    expect(r.host.querySelector('li')!.textContent).toContain('Could not save');
    expect(r.host.querySelector('li')!.textContent).not.toMatch(/\d MB/);
  });

  it('asks before deleting, deletes on yes, and keeps the area on no; focus goes to the heading', async () => {
    const r = await render({ seed: two });
    const confirm = TestBed.inject(ConfirmService);
    const click = async () => {
      r.host.querySelectorAll('li')[1].querySelector<HTMLButtonElement>('button')!.click();
      await tick(r.fixture);
    };
    await click();
    expect(confirm.pending()!.message).toEqual({ key: 'offline.deleteConfirm', params: { name: 'Indiranagar' } });
    expect(confirm.pending()!.danger).toBe(true);
    confirm.settle('cancel');
    await tick(r.fixture);
    expect(r.service.areas()).toHaveLength(2);
    await click();
    confirm.settle('confirm');
    await tick(r.fixture);
    expect(r.service.areas().map((a) => a.name)).toEqual(['HSR Layout']);
    expect(r.host.querySelectorAll('li')).toHaveLength(1);
    expect(document.activeElement).toBe(r.host.querySelector('#offline-heading'));
  });

  it.each(['hi', 'ta', 'te'] as Lang[])('is in %s (under review): heading, hint, empty state', async (lang) => {
    const { host } = await render({ lang });
    expect(host.querySelector('h2')!.textContent).toBe(DICTS[lang]['offline.settingsHeading']);
    expect(host.textContent).toContain(DICTS[lang]['offline.settingsHint']);
    expect(host.textContent).toContain(DICTS[lang]['offline.settingsEmpty']);
  });
});
