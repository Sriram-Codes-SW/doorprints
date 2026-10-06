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
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../../app.routes';
import { ConfirmService } from '../../core/confirm.service';
import { Announcer } from '../../core/announcer.service';
import { TraceStore } from '../../data/trace-store';
import { TranslationService } from '../../i18n/translation.service';
import type { Lang } from '../../i18n/languages';
import { en } from '../../i18n/en';
import { hi } from '../../i18n/hi';
import { ta } from '../../i18n/ta';
import { te } from '../../i18n/te';

/** *Your data* shows how many saved walks the browser holds and deletes them all after asking (docs/11 5.27.6); the 30-day trace is not touched. */
const DICTS = { en, hi, ta, te } as const;
const DEG = 1 / 111_194.9266;
const NOW = Date.now();

afterEach(() => {
  vi.restoreAllMocks();
  TestBed.resetTestingModule();
  localStorage.clear();
});

async function open(lang: Lang = 'en', saved = 0) {
  TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
  await TestBed.inject(TranslationService).setLang(lang);
  const store = TestBed.inject(TraceStore);
  for (let i = 0; i < saved; i++) {
    const id = NOW - (i + 1) * 3_600_000;
    for (let k = 0; k < 6; k++) await store.putPoint({ lat: k * 20 * DEG, lon: i, atMs: id + k * 10_000, walkId: id }, 10);
    await store.saveWalk(id, 'house-1', NOW);
  }
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl('/data');
  for (let i = 0; i < 4; i++) {
    await harness.fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    harness.detectChanges();
  }
  const host = harness.routeNativeElement as HTMLElement;
  const settle = async () => {
    for (let i = 0; i < 4; i++) {
      await harness.fixture.whenStable();
      await new Promise((resolve) => setTimeout(resolve, 0));
      harness.detectChanges();
    }
  };
  return { host, store, settle };
}

describe('Your data: saved walks', () => {
  it('shows Saved walks: 0 and no delete button when there are none', async () => {
    const r = await open();
    expect(r.host.querySelector('#saved-walks')!.textContent!.trim()).toBe('Saved walks: 0');
    expect(r.host.querySelector('#delete-saved-walks')).toBeNull();
  });

  it('shows the count and Delete all saved walks inside the storage card', async () => {
    const r = await open('en', 3);
    expect(r.host.querySelector('#saved-walks')!.textContent!.trim()).toBe('Saved walks: 3');
    const button = r.host.querySelector<HTMLButtonElement>('#delete-saved-walks')!;
    expect(button.textContent!.trim()).toBe('Delete all saved walks');
    expect(button.closest('section')!.getAttribute('aria-labelledby')).toBe('storage-heading');
  });

  it('asks with the count; Cancel deletes nothing', async () => {
    const r = await open('en', 2);
    const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(false);
    r.host.querySelector<HTMLButtonElement>('#delete-saved-walks')!.click();
    await r.settle();
    expect(ask.mock.calls[0][0]).toEqual({ key: 'trace.settings.deleteAllConfirm', params: { n: '2' } });
    expect(await r.store.savedCount()).toBe(2);
  });

  it('deletes every saved walk on a yes, says so once, and keeps the 30-day trace', async () => {
    const r = await open('en', 2);
    await r.store.putPoint({ lat: 0, lon: 9, atMs: NOW - 1000, walkId: NOW - 1000 }, 10);
    vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(true);
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    r.host.querySelector<HTMLButtonElement>('#delete-saved-walks')!.click();
    await r.settle();
    expect(await r.store.savedCount()).toBe(0);
    expect((await r.store.walkPoints(NOW - 1000)).length).toBe(1);
    expect(r.host.querySelector('#saved-walks')!.textContent!.trim()).toBe('Saved walks: 0');
    expect(r.host.querySelector('#delete-saved-walks')).toBeNull();
    expect(announce.mock.calls.map((c) => c[0].key)).toContain('trace.settings.deleted');
  });

  it.each(['hi', 'ta', 'te'] as const)('is in %s, with the count in the language', async (lang) => {
    const r = await open(lang, 1);
    const text = r.host.querySelector('#saved-walks')!.textContent!.trim();
    expect(text).toContain(DICTS[lang]['trace.settings.saved'].replace('{n}', '').trim());
    expect(text).not.toContain('trace.');
  });

  it('Remove all Doorprints data from this browser empties the saved walks, the trace and the trace settings, and the page shows 0', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    await TestBed.inject(TranslationService).setLang('en');
    const store = TestBed.inject(TraceStore);
    for (let i = 0; i < 2; i++) {
      const id = NOW - (i + 1) * 3_600_000;
      for (let k = 0; k < 6; k++) await store.putPoint({ lat: k * 20 * DEG, lon: i, atMs: id + k * 10_000, walkId: id }, 10);
      await store.saveWalk(id, 'house-1', NOW);
    }
    await store.putPoint({ lat: 0, lon: 9, atMs: NOW - 1000, walkId: NOW - 1000 }, 10);
    await store.setLook('OFF');
    await store.setAlertOn(true);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/data');
    for (let i = 0; i < 4; i++) {
      await harness.fixture.whenStable();
      await new Promise((resolve) => setTimeout(resolve, 0));
      harness.detectChanges();
    }
    const host = harness.routeNativeElement as HTMLElement;
    expect(host.querySelector('#saved-walks')!.textContent!.trim()).toBe('Saved walks: 2');
    const confirm = TestBed.inject(ConfirmService);
    vi.spyOn(confirm, 'ask').mockResolvedValue(true);
    vi.spyOn(confirm, 'choose').mockResolvedValue('confirm');
    await (harness.routeDebugElement!.componentInstance as { clearBrowser(): Promise<void> }).clearBrowser();
    harness.detectChanges();
    expect(await store.savedCount()).toBe(0);
    expect(await store.walkPoints(NOW - 1000)).toEqual([]);
    expect(await store.look()).toBe('CLEAR');
    expect(await store.alertOn()).toBe(false);
    expect(host.querySelector('#saved-walks')!.textContent!.trim()).toBe('Saved walks: 0');
    expect(host.querySelector('#delete-saved-walks')).toBeNull();
  });
});
