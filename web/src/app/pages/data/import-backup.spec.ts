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
import vectorsJson from '../../../../../docs/schemas/import-vectors.json';
import { LocalStore } from '../../data/local-store.service';
import { EMPTY_LOCAL, preview } from '../../export/import-plan';
import { ImportBackupCard, previewLines } from './import-backup';

const archives = (vectorsJson as unknown as { archives: { name: string; base64: string }[] }).archives;
const fileOf = (name: string): Blob =>
  new Blob([Uint8Array.from(atob(archives.find((a) => a.name === name)!.base64), (c) => c.charCodeAt(0)) as BlobPart]);

async function render() {
  TestBed.configureTestingModule({ imports: [ImportBackupCard], providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(ImportBackupCard);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

const textOf = (el: HTMLElement): string => el.textContent?.replace(/\s+/g, ' ').trim() ?? '';

afterEach(() => TestBed.resetTestingModule());

/**
 * *Import a backup* on Your data (S4b-BL-75): the empty state offers the picker; a file that is not a backup is refused
 * with the reason, in an alert; a backup shows the preview's lines and the two modes, imports on *Import* and says
 * what it wrote; a copy offers its undo.
 */
describe('ImportBackupCard', () => {
  it('starts with the picker only, named Import a backup', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    expect(textOf(el.querySelector('h2')!)).toBe('Import a backup');
    expect(textOf(el.querySelector('.actions .btn-primary')!)).toBe('Choose a backup file');
    expect(el.querySelector('fieldset')).toBeNull();
  });

  it('refuses a file that is not a backup and says why', async () => {
    const fixture = await render();
    await fixture.componentInstance.check(Object.assign(fileOf('a PDF'), { name: 'notes.pdf' }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(textOf(el.querySelector('[role="alert"]')!)).toBe('This file cannot be imported: it is not a Doorprints backup');
    expect(textOf(el)).toContain('notes.pdf');
    expect(textOf(el.querySelector('.actions .btn-primary')!)).toBe('Choose another file');
  });

  it('previews a backup, imports it on Import and offers no undo for a merge', async () => {
    const fixture = await render();
    await fixture.componentInstance.check(Object.assign(fileOf('a stored backup'), { name: 'Doorprints-backup-2026-05-28.zip' }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(textOf(el)).toContain('Backup made on');
    const lines = [...el.querySelectorAll('.preview li')].map((li) => `${textOf(li.querySelector('span')!)} ${textOf(li.querySelector('strong')!)}`);
    expect(lines).toEqual(['New houses 1', 'New photos 1']);
    await fixture.componentInstance.run();
    fixture.detectChanges();
    expect(textOf(el.querySelector('.result .success')!)).toBe('Import finished. Houses written: 1. Visits: 0. Photos: 1.');
    expect(textOf(el)).not.toContain('Undo this import');
    expect(await TestBed.inject(LocalStore).getHouse('h1')).toBeDefined();
  });

  it('an update file names who it is for, and a copy can be undone', async () => {
    const fixture = await render();
    const card = fixture.componentInstance;
    await card.check(fileOf('an update file says who it is for'));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(textOf(el)).toContain('Updates for Priya, made on');
    const copy = el.querySelector('input[value="COPY"]') as HTMLInputElement;
    copy.click();
    fixture.detectChanges();
    await card.run();
    fixture.detectChanges();
    expect(textOf(el)).toContain('Undo this import');
    await card.undoCopy();
    fixture.detectChanges();
    expect(textOf(el)).toContain('Copies removed: 1. Kept, because you changed them since: 0.');
    expect(await TestBed.inject(LocalStore).liveHouses()).toEqual([]);
  });

  it('lists only the lines that are not zero, a loss marked as one', () => {
    const data = { format: 'doorprints-backup/3', exportedAt: 1, houses: [], visits: [], photos: [], deleted: [{ kind: 'house', id: 'h5', updatedAt: 300 }] };
    const local = { ...EMPTY_LOCAL, houses: new Map([['h5', 100]]) };
    const lines = previewLines(preview(data, new Set(), local, { mode: 'MERGE', applyDeletions: true }), 0);
    expect(lines).toEqual([{ key: 'imp.removedHouses', count: 1, loss: true }]);
  });

  it('warns of the houses whose floor is out of range and lands blank (S4b-BL-104 d)', () => {
    const house = (id: string, floor: number) => ({
      id, label: id, lat: 12.97, lon: 77.59, status: 'NEW', floor, checklist: {}, createdAt: 1, updatedAt: 2,
    });
    const data = { format: 'doorprints-backup/2', exportedAt: 1, houses: [house('h1', 201), house('h2', -5)], visits: [], photos: [] };
    const lines = previewLines(preview(data as never, new Set(), EMPTY_LOCAL, { mode: 'MERGE' }), 0);
    expect(lines).toEqual([{ key: 'imp.newHouses', count: 2 }, { key: 'imp.floorsLeftBlank', count: 1, loss: true }]);
  });
});
