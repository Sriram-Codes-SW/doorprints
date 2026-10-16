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
import { Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DeleteUndoService } from '../../core/delete-undo.service';
import { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import type { PhotoRestoreReport, PhotoRestoreRequest } from '../../data/drive/photo-restorer';
import type { PhotoRestoreReason } from '../../data/drive/photo-restore-rules';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from '../../core/kind-setting';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import { StorageService } from '../../data/storage.service';
import { SyncService } from '../../data/sync.service';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { audit } from '../../shared/testing/a11y';
import { RecentlyDeleted } from './recently-deleted';

/** *Recently deleted* on Your data (deletion safeguards PR 1; docs/06 TC-U-215, TC-M-39). */
const NOW = Date.parse('2026-10-14T12:00:00.000Z');
const DAY = 24 * 60 * 60 * 1000;
const house = (id: string, over: Partial<HouseDto> = {}): HouseDto => ({ id, label: `House ${id}`, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, ...over });
const jpeg = (): Blob => new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' });
const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('Recently deleted', () => {
  let store: LocalStore;
  let syncSoon: ReturnType<typeof vi.fn>;

  async function open(lang: Lang = 'en', url = '/data', drive?: (req: PhotoRestoreRequest) => PhotoRestoreReport) {
    syncSoon = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        ...(drive ? [{ provide: DriveConnectService, useValue: { restorePhotos: async (req: PhotoRestoreRequest) => drive(req) } }] : []),
        provideRouter([{ path: '**', children: [] }]),
        { provide: SyncService, useValue: { syncSoon } },
        { provide: StorageService, useValue: { requestPersistence: () => Promise.resolve() } },
      ],
    });
    store = TestBed.inject(LocalStore);
    await TestBed.inject(TranslationService).setLang(lang);
    await TestBed.inject(Router).navigateByUrl(url);
    return { seed: async () => undefined };
  }

  async function render() {
    const fixture = TestBed.createComponent(RecentlyDeleted);
    document.body.append(fixture.nativeElement);
    fixture.detectChanges();
    await fixture.whenStable();
    await tick();
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  async function settle(fixture: { detectChanges(): void }) {
    await tick();
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
  }

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
    TestBed.resetTestingModule();
    document.body.replaceChildren();
  });

  it('shows the heading, what it keeps, and an empty state when nothing was deleted', async () => {
    await open();
    await store.saveHouse(house('h1'), NOW - DAY);
    const { host } = await render();
    expect(text(host.querySelector('h2'))).toBe('Recently deleted');
    expect(text(host)).toContain('Saved walks are not kept.');
    expect(text(host.querySelector('.empty'))).toBe('Nothing deleted in the last 30 days.');
    expect(host.querySelector('ul')).toBeNull();
    expect(host.querySelector('h3')).toBeNull(); // houses only: no space name
    expect(audit(host)).toEqual([]);
  });

  it('lists this space\'s tombstones under 30 days as a plain list: name, when, and a Restore button named for the entry', async () => {
    await open();
    await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
    await store.saveHouse(house('h2', { label: '', address: 'Temple Road' } as Partial<HouseDto>), NOW - 9 * DAY);
    await store.saveHouse(house('h3'), NOW - 90 * DAY);
    await store.deleteHouse('h1', NOW - 3 * DAY);
    await store.deleteHouse('h2', NOW - DAY);
    await store.deleteHouse('h3', NOW - 40 * DAY);
    const { host } = await render();
    const items = [...host.querySelectorAll('ul[role="list"] > li')];
    expect(items.map((li) => text(li.querySelector('.name')))).toEqual(['Temple Road', 'Blue gate']);
    expect(text(items[1])).toContain('Deleted ');
    const buttons = [...host.querySelectorAll('button.restore')];
    expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual(['Restore Temple Road', 'Restore Blue gate']);
    expect(buttons.map((b) => text(b))).toEqual(['Restore', 'Restore']);
    expect(audit(host)).toEqual([]);
  });

  it('Restore brings the candidate back, leaves the list, says so in a status region and syncs; focus moves to the next Restore', async () => {
    await open();
    await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
    await store.saveHouse(house('h2', { label: 'Green view' }), NOW - 9 * DAY);
    await store.deleteHouse('h1', NOW - 2 * DAY);
    await store.deleteHouse('h2', NOW - DAY);
    const { host, fixture } = await render();
    host.querySelectorAll<HTMLButtonElement>('button.restore')[0].click();
    vi.useRealTimers();
    await settle(fixture);
    expect(await store.getHouse('h2')).toMatchObject({ deleted: false, dirty: true });
    expect(await store.getHouse('h1')).toBeUndefined();
    expect(syncSoon).toHaveBeenCalled();
    expect(text(host.querySelector('[role="status"]'))).toBe('Restored Green view');
    expect(host.querySelectorAll('li')).toHaveLength(1);
    expect(document.activeElement).toBe(host.querySelector('button.restore'));
  });

  it('after the last entry of several is restored, focus goes to the new last Restore', async () => {
    await open();
    for (const [id, days] of [['h1', 3], ['h2', 2], ['h3', 1]] as const) {
      await store.saveHouse(house(id, { label: `Name ${id}` }), NOW - 9 * DAY);
      await store.deleteHouse(id, NOW - days * DAY);
    }
    const { host, fixture } = await render();
    const buttons = host.querySelectorAll<HTMLButtonElement>('button.restore');
    expect([...buttons].map((b) => b.getAttribute('aria-label'))).toEqual(['Restore Name h3', 'Restore Name h2', 'Restore Name h1']);
    buttons[2].click();
    vi.useRealTimers();
    await settle(fixture);
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Restore Name h2');
  });

  it('after the last entry is restored, focus goes to the heading and the empty state shows', async () => {
    await open();
    await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
    await store.deleteHouse('h1', NOW - DAY);
    const { host, fixture } = await render();
    host.querySelector<HTMLButtonElement>('button.restore')!.click();
    vi.useRealTimers();
    await settle(fixture);
    expect(document.activeElement).toBe(host.querySelector('h2'));
    expect(text(host.querySelector('.empty'))).toBe('Nothing deleted in the last 30 days.');
  });

  it('says why the photos could not be restored when the candidate had photos and their bytes are gone', async () => {
    await open();
    await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
    await store.photos.add('h1', jpeg(), 'p1', NOW - 9 * DAY);
    const undo = TestBed.inject(DeleteUndoService);
    await undo.remove('h1', 'Blue gate');
    undo.dismiss();
    const { host, fixture } = await render();
    expect(text(host.querySelector('li'))).toContain('Photos: 1');
    host.querySelector<HTMLButtonElement>('button.restore')!.click();
    vi.useRealTimers();
    await settle(fixture);
    expect(text(host.querySelector('[role="status"]'))).toBe('Restored Blue gate. Its photos could not be restored: Google Drive is not connected on this device.');
    expect(await store.getHouse('h1')).toBeDefined();
  });

  describe('photos from Google Drive (S4b-BL-293)', () => {
    const deletedWithPhotos = async (n: number) => {
      await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
      for (let i = 1; i <= n; i++) {
        await store.photos.add('h1', jpeg(), `p${i}`, NOW - 9 * DAY);
        await store.photos.put({ ...(await store.photos.get(`p${i}`))!, uploaded: true });
      }
      const undo = TestBed.inject(DeleteUndoService);
      await undo.remove('h1', 'Blue gate');
      undo.dismiss();
    };
    const answer = (back: string[], why: PhotoRestoreReason | null, connection: PhotoRestoreReport['connection'] = 'OK') => (req: PhotoRestoreRequest): PhotoRestoreReport => ({
      connection,
      fetched: req.known.filter((p) => back.includes(p.id)).map((photo) => ({ photo, bytes: new Uint8Array([1, 2]) })),
      failed: req.known.filter((p) => !back.includes(p.id)).map((photo) => ({ photo, reason: why! })),
    });
    const restoreAndRead = async (drive: (req: PhotoRestoreRequest) => PhotoRestoreReport, lang: Lang = 'en', photos = 2) => {
      await open(lang, '/data', drive);
      await deletedWithPhotos(photos);
      const { host, fixture } = await render();
      host.querySelector<HTMLButtonElement>('button.restore')!.click();
      vi.useRealTimers();
      await settle(fixture);
      return { host, status: text(host.querySelector('[role="status"]')) };
    };

    it('says every photo came back from Google Drive', async () => {
      const { host, status } = await restoreAndRead(answer(['p1', 'p2'], null));
      expect(status).toBe('Restored Blue gate. Its photos came back from your Google Drive (photos: 2).');
      expect(await store.photos.ofHouse('h1')).toHaveLength(2);
      expect(audit(host)).toEqual([]);
    });

    it('says how many of the photos came back and why the others did not', async () => {
      const { status } = await restoreAndRead(answer(['p1'], 'MISSING'));
      expect(status).toBe('Restored Blue gate. Photos that came back: 1 of 2. The others could not be restored: no copy is left in your Google Drive.');
    });

    it.each([
      ['NOT_CONNECTED', 'Google Drive is not connected on this device'],
      ['OFFLINE', 'there was no connection to Google Drive'],
      ['PAST_WINDOW', 'they were deleted more than 30 days ago'],
      ['MISSING', 'no copy is left in your Google Drive'],
      ['UNREADABLE', 'a copy in your Google Drive could not be opened or checked'],
    ] as const)('says %s in words when no photo came back', async (reason, words) => {
      const { status } = await restoreAndRead(answer([], reason, reason === 'OFFLINE' ? 'OFFLINE' : 'OK'));
      expect(status).toBe(`Restored Blue gate. Its photos could not be restored: ${words}.`);
    });

    it('joins several reasons in the language\'s own list pattern', async () => {
      const { status } = await restoreAndRead((req) => ({
        connection: 'OK',
        fetched: [],
        failed: [{ photo: req.known[0], reason: 'MISSING' }, { photo: req.known[1], reason: 'UNREADABLE' }],
      }));
      expect(status).toBe('Restored Blue gate. Its photos could not be restored: no copy is left in your Google Drive and a copy in your Google Drive could not be opened or checked.');
    });

    it('says only "Restored" when the photos came back from bytes still stored here, without Google Drive', async () => {
      await open('en', '/data', answer([], null));
      await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
      await store.deleteHouse('h1', NOW - DAY);
      await store.photos.put({ id: 'kept', houseId: 'h1', blob: jpeg(), contentType: 'image/jpeg', sizeBytes: 3, createdAt: null, updatedAt: new Date(NOW - DAY).toISOString(), deleted: true, syncVersion: 0, uploaded: false });
      const { host, fixture } = await render();
      host.querySelector<HTMLButtonElement>('button.restore')!.click();
      vi.useRealTimers();
      await settle(fixture);
      expect(text(host.querySelector('[role="status"]'))).toBe('Restored Blue gate');
      expect(await store.photos.ofHouse('h1')).toHaveLength(1);
    });

    it('says nothing about photos for a candidate that had none', async () => {
      await open('en', '/data', answer([], null));
      await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
      await store.deleteHouse('h1', NOW - DAY);
      const { host, fixture } = await render();
      host.querySelector<HTMLButtonElement>('button.restore')!.click();
      vi.useRealTimers();
      await settle(fixture);
      expect(text(host.querySelector('[role="status"]'))).toBe('Restored Blue gate');
    });

    it('speaks Hindi, Tamil and Telugu too (under review)', async () => {
      const hi = await restoreAndRead(answer(['p1'], 'OFFLINE', 'OFFLINE'), 'hi');
      expect(hi.status).toContain('Blue gate');
      expect(hi.status).toContain('2 में से 1');
      expect(hi.status).toContain('Google Drive से कनेक्शन नहीं था');
      TestBed.resetTestingModule();
      document.body.replaceChildren();
      const ta = await restoreAndRead(answer(['p1'], 'PAST_WINDOW'), 'ta');
      expect(ta.status).toContain('2-இல் 1');
      expect(ta.status).toContain('30 நாட்களுக்கு முன்');
      TestBed.resetTestingModule();
      document.body.replaceChildren();
      const te = await restoreAndRead(answer([], 'UNREADABLE'), 'te');
      expect(te.status).toContain('Google Driveలోని కాపీని తెరవలేకపోయాము');
    });
  });

  it('with spaces on shows the current space\'s name as a sub-heading and only its tombstones', async () => {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
    localStorage.setItem(DEFAULT_KIND_KEY, 'house');
    await open('en', '/k/school');
    await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
    await store.saveHouse(house('s1', { label: 'Pine Hill', kind: 'school' }), NOW - 9 * DAY);
    await store.deleteHouse('h1', NOW - DAY);
    await store.deleteHouse('s1', NOW - DAY);
    const before = JSON.stringify((await store.allHouses()).find((h) => h.id === 'h1'));
    const { host, fixture } = await render();
    expect(text(host.querySelector('h3'))).toBe('Schools');
    expect([...host.querySelectorAll('li .name')].map((n) => text(n))).toEqual(['Pine Hill']);
    host.querySelector<HTMLButtonElement>('button.restore')!.click();
    vi.useRealTimers();
    await settle(fixture);
    expect(await store.getHouse('s1')).toBeDefined();
    expect(JSON.stringify((await store.allHouses()).find((h) => h.id === 'h1'))).toBe(before);
    expect(audit(host)).toEqual([]);
  });

  it('shows an error when the list cannot be read', async () => {
    await open();
    vi.spyOn(TestBed.inject(LocalStore), 'deletedHouses').mockRejectedValue(new Error('disk'));
    const { host } = await render();
    expect(text(host.querySelector('[role="alert"]'))).toBe('Could not read the list of recently deleted entries: disk');
  });

  it('shows a failed restore in an alert and keeps the entry', async () => {
    await open();
    await store.saveHouse(house('h1', { label: 'Blue gate' }), NOW - 9 * DAY);
    await store.deleteHouse('h1', NOW - DAY);
    const { host, fixture } = await render();
    vi.spyOn(store, 'restoreHouse').mockRejectedValue(new Error('disk'));
    host.querySelector<HTMLButtonElement>('button.restore')!.click();
    vi.useRealTimers();
    await settle(fixture);
    expect(text(host.querySelector('[role="alert"]'))).toBe('Could not restore: disk');
    expect(host.querySelectorAll('li')).toHaveLength(1);
  });

  it('is in Tamil, with the entry named in the button', async () => {
    await open('ta');
    await store.saveHouse(house('h1', { label: 'நீல வாசல்' }), NOW - 9 * DAY);
    await store.deleteHouse('h1', NOW - DAY);
    const { host } = await render();
    expect(text(host.querySelector('h2'))).toBe('சமீபத்தில் நீக்கியவை');
    expect(host.querySelector('button.restore')!.getAttribute('aria-label')).toBe('நீல வாசல் ஐ மீட்கவும்');
    expect(text(host.querySelector('button.restore'))).toBe('மீட்கவும்');
  });
});
