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
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DeleteUndoService } from '../core/delete-undo.service';
import type { UndoOffer } from '../core/delete-undo.service';
import type { Lang } from '../i18n/languages';
import { TranslationService } from '../i18n/translation.service';
import { audit } from './testing/a11y';
import { UndoSnackbar } from './undo-snackbar';

/** The Undo snackbar of the app shell (deletion safeguards PR 1; docs/06 TC-U-215, TC-M-39). */
describe('UndoSnackbar', () => {
  async function open(offer: UndoOffer | null, lang: Lang = 'en') {
    const offerSignal = signal<UndoOffer | null>(offer);
    const service = { offer: offerSignal, undo: vi.fn(() => Promise.resolve()), hold: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: DeleteUndoService, useValue: service }] });
    await TestBed.inject(TranslationService).setLang(lang);
    const fixture = TestBed.createComponent(UndoSnackbar);
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement as HTMLElement, service, offerSignal };
  }
  const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

  afterEach(() => {
    TestBed.resetTestingModule();
    document.body.replaceChildren();
  });

  it('keeps an empty status region in the page and shows nothing in it without an offer', async () => {
    const { host } = await open(null);
    const status = host.querySelector('[role="status"]')!;
    expect(status).not.toBeNull();
    expect(text(status)).toBe('');
    expect(host.querySelector('button')).toBeNull();
  });

  it('puts the message and a real Undo button into the status region when a delete is offered', async () => {
    const { host, fixture, offerSignal } = await open(null);
    offerSignal.set({ id: 'h1', name: 'Blue gate' });
    fixture.detectChanges();
    const status = host.querySelector('[role="status"]')!;
    expect(text(status)).toBe('Deleted Blue gateUndo');
    const button = status.querySelector('button')!;
    expect(button.type).toBe('button');
    expect(button.tabIndex).toBe(0);
    expect(audit(host)).toEqual([]);
  });

  it('Undo can be reached and pressed from the keyboard (a button: Enter and Space click it)', async () => {
    document.body.append(document.createElement('main'));
    const { host, fixture, service } = await open({ id: 'h1', name: 'Blue gate' });
    document.body.append(host);
    host.querySelector('button')!.focus();
    expect(document.activeElement).toBe(host.querySelector('button'));
    host.querySelector('button')!.click();
    await fixture.whenStable();
    expect(service.undo).toHaveBeenCalledTimes(1);
  });

  it('after Undo, focus goes to the main region when the button took it away', async () => {
    const main = document.createElement('main');
    main.id = 'main';
    main.tabIndex = -1;
    document.body.append(main);
    const { host, fixture } = await open({ id: 'h1', name: 'Blue gate' });
    document.body.append(host);
    host.querySelector('button')!.focus();
    (document.activeElement as HTMLElement).blur();
    host.querySelector('button')!.click();
    await fixture.whenStable();
    expect(document.activeElement).toBe(main);
  });

  it('holds the time for the pointer and the focus, and lets it run again on leaving', async () => {
    const { host, service } = await open({ id: 'h1', name: 'Blue gate' });
    const region = host.querySelector('.undo-host')!;
    region.dispatchEvent(new MouseEvent('mouseenter'));
    region.dispatchEvent(new MouseEvent('mouseleave'));
    region.dispatchEvent(new FocusEvent('focusin'));
    region.dispatchEvent(new FocusEvent('focusout'));
    expect(service.hold.mock.calls).toEqual([[true], [false], [true], [false]]);
  });

  it('speaks the language of the page with the name in it', async () => {
    const { host } = await open({ id: 'h1', name: 'நீல வாசல்' }, 'ta');
    expect(text(host.querySelector('.msg'))).toBe('நீல வாசல் நீக்கப்பட்டது');
    expect(text(host.querySelector('button'))).toBe('செயல்தவிர்');
  });
});
