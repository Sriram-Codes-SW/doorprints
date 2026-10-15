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
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { fakeMic } from '../../core/voice/testing/voice-fakes';
import { RECORDER_ENV, VoiceInputService } from '../../core/voice/voice-input.service';
import { TranslationService } from '../../i18n/translation.service';
import { HouseListingCard, HouseListingFill } from './house-listing-card';

/**
 * TC-U-248 (docs/06): voice input in *Fill in from listing text* (S4b-BL-221, ADR-37). The microphone sits under the listing
 * box, the words are added after what was pasted, and the form is read only when the person presses Fill in, with the text
 * as it is then.
 */
describe('HouseListingCard: voice input', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  async function open(on: boolean) {
    localStorage.clear();
    const extractListing = vi.fn(() => of());
    const mic = fakeMic();
    vi.stubGlobal('fetch', async () =>
      new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: JSON.stringify({ text: 'rent twenty eight thousand deposit one lakh', language: 'en-IN' }) }] } }] }) ));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AiService,
          useValue: {
            enabled: signal(true), usesOwnKey: signal(true), ownHost: signal('generativelanguage.googleapis.com'),
            aiConfig: signal({ kind: 'gemini', baseUrl: '', model: '' }), ownKey: () => 'k-not-real', extractListing,
          },
        },
        { provide: RECORDER_ENV, useValue: mic.env },
      ],
    });
    TestBed.inject(VoiceInputService).setEnabled(on);
    const i18n = TestBed.inject(TranslationService);
    await i18n.setLang('en');
    const listing = new HouseListingFill({ ai: { extractListing }, i18n, announcer: TestBed.inject(Announcer), draft: () => null, patch: () => undefined });
    listing.open = true;
    const fixture = TestBed.createComponent(HouseListingCard);
    fixture.componentRef.setInput('listing', listing);
    await fixture.whenStable();
    return { fixture, listing, extractListing, mic, host: fixture.nativeElement as HTMLElement };
  }

  it('has no microphone while the setting is off', async () => {
    const { host, mic } = await open(false);
    expect(host.querySelector('#listing-text-mic')).toBeNull();
    expect(mic.asked()).toBe(0);
  });

  it('adds the spoken words after the pasted text and reads nothing until Fill in is pressed', async () => {
    const { fixture, host, listing, extractListing } = await open(true);
    listing.text = '2 BHK in Beltola, Guwahati.';
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('#listing-text-mic')!.click();
    await vi.waitFor(() => expect(host.querySelector('#listing-text-mic')?.textContent).toContain('Stop'));
    host.querySelector<HTMLButtonElement>('#listing-text-mic')!.click();
    await vi.waitFor(() => expect(listing.text).toBe('2 BHK in Beltola, Guwahati. rent twenty eight thousand deposit one lakh'));
    expect(extractListing).not.toHaveBeenCalled();
  });
});
