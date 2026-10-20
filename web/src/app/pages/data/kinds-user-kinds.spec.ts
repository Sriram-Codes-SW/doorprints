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
import { ConfirmService } from '../../core/confirm.service';
import { DeleteUndoService } from '../../core/delete-undo.service';
import { CUSTOM_KINDS_KEY, availableKinds, enabledKinds } from '../../core/kind-setting';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { USER_KINDS_KEY, reloadUserKinds, userKindFiles } from '../../core/user-kinds/user-kinds';
import type { Lang } from '../../i18n/languages';
import '../../i18n/all-dictionaries';
import { TranslationService } from '../../i18n/translation.service';
import { audit } from '../../shared/testing/a11y';
import { USER_KIND_VECTORS, caseText, userKindSample } from '../../shared/testing/user-kind-vectors';
import { KindsSettings } from './kinds-settings';

const row = (id: string, kind?: string) => ({ id, kind, label: id, lat: 26, lon: 91, status: 'NEW', deleted: false, updatedAt: '2026-10-01T00:00:00.000Z' }) as unknown as HouseDto;

/**
 * Settings > What you track > *Kinds you add* (docs/03 ADR-38; docs/06 TC-U-294): the switch, *Import a kind file* with its preview
 * and its refusals in plain words, and *Remove kind*, which never deletes an entry.
 */
describe('Your data: kinds you add', () => {
  const removeMany = vi.fn(async (ids: readonly string[]) => ids.length);
  const choose = vi.fn();
  const announce = vi.fn();

  async function open(houses: HouseDto[] = [], lang: Lang = 'en') {
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
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(KindsSettings);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { host: fixture.nativeElement as HTMLElement, fixture };
  }
  const settle = async (fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) => {
    for (let i = 0; i < 3; i++) {
      await new Promise((r) => setTimeout(r, 0));
      fixture.detectChanges();
      await fixture.whenStable();
    }
  };
  /** Chooses a file in the kind file input (a stand-in with `size` and `text()`, as a browser's File has). */
  async function choose_(host: HTMLElement, fixture: { detectChanges(): void; whenStable(): Promise<unknown> }, text: string, size = new TextEncoder().encode(text).length) {
    const input = host.querySelector<HTMLInputElement>('#user-kind-file')!;
    const read = vi.fn(async () => text);
    Object.defineProperty(input, 'files', { value: [{ size, name: 'kind.json', text: read }], configurable: true });
    input.dispatchEvent(new Event('change'));
    await settle(fixture);
    return read;
  }
  async function turnOn(host: HTMLElement, fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) {
    const box = host.querySelector<HTMLInputElement>('#kinds-custom')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await settle(fixture);
  }

  beforeEach(() => {
    localStorage.clear();
    reloadUserKinds();
    removeMany.mockClear();
    choose.mockReset();
    announce.mockClear();
  });
  afterEach(() => {
    localStorage.clear();
    reloadUserKinds();
    TestBed.resetTestingModule();
  });

  it('shows the switch off, and the import only once it is on; both have names and hints', async () => {
    const { host, fixture } = await open();
    const box = host.querySelector<HTMLInputElement>('#kinds-custom')!;
    expect(box.checked).toBe(false);
    expect(host.querySelector('label[for="kinds-custom"]')?.textContent?.trim()).toBe('Kinds you add (experimental)');
    expect(host.querySelector('#user-kind-file')).toBeNull();
    await turnOn(host, fixture);
    expect(localStorage.getItem(CUSTOM_KINDS_KEY)).toBe('1');
    expect(host.querySelector('label[for="user-kind-file"]')?.textContent?.trim()).toBe('Import a kind file');
    expect(host.querySelector('#user-kind-file')?.getAttribute('accept')).toContain('.json');
    expect(audit(host)).toEqual([]);
  });

  it('previews a valid file in its own words, stores it only on *Add this kind*, then lists it as yours, off', async () => {
    const { host, fixture } = await open();
    await turnOn(host, fixture);
    await choose_(host, fixture, JSON.stringify(userKindSample('u_gym')));
    const preview = host.querySelector('#user-kind-preview')!;
    expect(preview.textContent).toContain('Name: Gyms');
    expect(preview.textContent).toContain('Focus, Opening hours, Personal trainer');
    expect(preview.textContent).toContain('Checklist: Clean and airy');
    expect(userKindFiles()).toEqual([]);
    expect(audit(host)).toEqual([]);
    host.querySelector<HTMLButtonElement>('#user-kind-add')!.click();
    await settle(fixture);
    expect(userKindFiles().map((f) => f.id)).toEqual(['u_gym']);
    expect(host.querySelector('#user-kind-notice')?.textContent).toContain('Gyms added. Turn it on below to use it.');
    expect(host.querySelector('#kind-on-u_gym')).not.toBeNull();
    expect(host.querySelector<HTMLInputElement>('#kind-on-u_gym')!.checked).toBe(false);
    expect(host.querySelector('#kind-remove-u_gym')?.getAttribute('aria-label')).toBe('Remove the kind Gyms');
    expect(host.textContent).toContain('Added by you');
    expect(availableKinds()).toContain('u_gym');
    expect(enabledKinds()).not.toContain('u_gym');
  });

  it('shows the person’s words as text, never as markup', async () => {
    const { host, fixture } = await open();
    await turnOn(host, fixture);
    await choose_(host, fixture, JSON.stringify(userKindSample('u_gym_two')));
    const preview = host.querySelector('#user-kind-preview')!;
    expect(preview.textContent).toContain('Name: javascript:alert(1)');
    expect(preview.querySelector('a, img, script, iframe')).toBeNull();
  });

  it('refuses a hostile file in plain words and where, and stores nothing', async () => {
    const { host, fixture } = await open();
    await turnOn(host, fixture);
    const xss = USER_KIND_VECTORS.invalid.find((c) => c.name.startsWith('markup in a label'))!;
    await choose_(host, fixture, caseText(xss));
    const alert = host.querySelector('#user-kind-refusal')!;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toContain('This file was not added: a name or label starts or ends with a space, or has a character that is not allowed');
    expect(alert.textContent).toContain('Where in the file: $.labels.many.text');
    expect(host.querySelector('img')).toBeNull();
    for (const name of ['a map style at the top', 'a prototype key at the top', 'the house id', 'the listing extract slot filled']) {
      await choose_(host, fixture, caseText(USER_KIND_VECTORS.invalid.find((c) => c.name === name)!));
      expect(host.querySelector('#user-kind-refusal'), name).not.toBeNull();
      expect(host.querySelector('#user-kind-preview'), name).toBeNull();
    }
    expect(userKindFiles()).toEqual([]);
    expect(localStorage.getItem(USER_KINDS_KEY)).toBeNull();
  });

  it('refuses a file over 64 KB without reading it', async () => {
    const { host, fixture } = await open();
    await turnOn(host, fixture);
    const read = await choose_(host, fixture, '{}', 70_000_000);
    expect(read).not.toHaveBeenCalled();
    expect(host.querySelector('#user-kind-refusal')?.textContent).toContain('the file is larger than 64 KB.');
  });

  for (const lang of ['hi', 'ta', 'te'] as const) {
    it(`speaks ${lang}: the switch and a refusal are translated, the kind's own words are not`, async () => {
      const { host, fixture } = await open([], lang);
      expect(host.querySelector('label[for="kinds-custom"]')?.textContent?.trim()).not.toBe('Kinds you add (experimental)');
      await turnOn(host, fixture);
      await choose_(host, fixture, 'not json');
      expect(host.querySelector('#user-kind-refusal')?.textContent).not.toContain('This file was not added');
      await choose_(host, fixture, JSON.stringify(userKindSample('u_gym')));
      expect(host.querySelector('#user-kind-preview')?.textContent).toContain('Gyms');
    });
  }

  it('removes a kind only after a danger question with *Save a copy first*, and never deletes its entries', async () => {
    localStorage.setItem(USER_KINDS_KEY, JSON.stringify([userKindSample('u_gym')]));
    localStorage.setItem(CUSTOM_KINDS_KEY, '1');
    reloadUserKinds();
    const { host, fixture } = await open([row('g1', 'u_gym'), row('g2', 'u_gym'), row('h1')]);
    choose.mockResolvedValue('alt');
    host.querySelector<HTMLButtonElement>('#kind-remove-u_gym')!.click();
    await settle(fixture);
    const [message, options] = choose.mock.calls[0];
    expect(message).toEqual({ key: 'userKinds.removeConfirm', params: { kind: 'Gyms', n: '2' } });
    expect(options).toMatchObject({ confirmKey: 'userKinds.removeDo', altKey: 'driveDelete.saveCopyFirst', danger: true, focusCancel: true });
    expect(userKindFiles().map((f) => f.id)).toEqual(['u_gym']);
    choose.mockResolvedValue('cancel');
    host.querySelector<HTMLButtonElement>('#kind-remove-u_gym')!.click();
    await settle(fixture);
    expect(userKindFiles().map((f) => f.id)).toEqual(['u_gym']);
    choose.mockResolvedValue('confirm');
    host.querySelector<HTMLButtonElement>('#kind-remove-u_gym')!.click();
    await settle(fixture);
    expect(userKindFiles()).toEqual([]);
    expect(removeMany).not.toHaveBeenCalled();
    expect(host.querySelector('#kind-remove-u_gym')).toBeNull();
    expect(announce).toHaveBeenCalledWith({ key: 'userKinds.removed', params: { kind: 'Gyms' } });
  });

  it('replaces a kind already added only after saying so', async () => {
    localStorage.setItem(USER_KINDS_KEY, JSON.stringify([userKindSample('u_gym')]));
    localStorage.setItem(CUSTOM_KINDS_KEY, '1');
    reloadUserKinds();
    const { host, fixture } = await open();
    const v2 = userKindSample('u_gym');
    v2['version'] = 2;
    v2['labels']['many']['text'] = 'Gyms and pools';
    await choose_(host, fixture, JSON.stringify(v2));
    expect(host.querySelector('#user-kind-preview')?.textContent).toContain('You already have this kind (Gyms).');
    expect(host.querySelector('#user-kind-add')?.textContent?.trim()).toBe('Replace the kind');
    host.querySelector<HTMLButtonElement>('#user-kind-add')!.click();
    await settle(fixture);
    expect(userKindFiles()[0].labels.many.text).toBe('Gyms and pools');
  });
});
