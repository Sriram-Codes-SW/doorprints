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

import { Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiSessionState } from '../../core/ai-session.state';
import { AiService } from '../../core/ai.service';
import { ApiConfig, ConfigService } from '../../core/config.service';
import { HouseApiService } from '../../core/house-api.service';
import { PairingPolled, PairingService, PairingStarted } from '../../core/pairing.service';
import { TranslationService } from '../../i18n/translation.service';
import { ConnectPage, spellCode } from './connect-page';

const INVITE = 'Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWo';
const STARTED: PairingStarted = { userCode: 'K7MQ-4XRD', pollToken: 'poll-token', expiresIn: 600, interval: 3 };

/**
 * Connecting without a pasted key (ADR-25, docs/03 §12.1): a code typed on the owner page, or a connect link from its
 * QR code. What is saved is the device key the server handed over, for the server that handed it over; a connect link
 * leaves the address bar at once and sends nothing until "Connect".
 */
describe('ConnectPage pairing', () => {
  let fixture: ComponentFixture<ConnectPage>;
  let host: HTMLElement;
  let saved: ApiConfig[];
  let navigations: number;
  let starts: { baseUrl: string; name: string; response: Subject<PairingStarted> }[];
  let polls: { baseUrl: string; token: string; response: Subject<PairingPolled> }[];
  let redeems: { baseUrl: string; invite: string; response: Subject<{ deviceKey: string }> }[];
  let replaced: string[];

  async function open(query: Record<string, string> = {}): Promise<void> {
    localStorage.clear();
    sessionStorage.clear();
    saved = [];
    navigations = 0;
    starts = [];
    polls = [];
    redeems = [];
    replaced = [];
    const pairing = {
      start: (baseUrl: string, name: string) => {
        const response = new Subject<PairingStarted>();
        starts.push({ baseUrl, name, response });
        return response.asObservable();
      },
      poll: (baseUrl: string, token: string) => {
        const response = new Subject<PairingPolled>();
        polls.push({ baseUrl, token, response });
        return response.asObservable();
      },
      redeem: (baseUrl: string, invite: string) => {
        const response = new Subject<{ deviceKey: string }>();
        redeems.push({ baseUrl, invite, response });
        return response.asObservable();
      },
    };
    TestBed.configureTestingModule({
      imports: [ConnectPage],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } },
        { provide: PairingService, useValue: pairing },
        { provide: HouseApiService, useValue: { testConnection: () => new Subject() } },
        { provide: AiService, useValue: { refresh: vi.fn() } },
        { provide: AiSessionState, useValue: { clear: vi.fn() } },
      ],
    });
    TestBed.inject(TranslationService).setLang('en');
    vi.spyOn(TestBed.inject(ConfigService), 'save').mockImplementation((config) => {
      saved.push(config);
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockImplementation(() => {
      navigations += 1;
      return Promise.resolve(true);
    });
    vi.spyOn(TestBed.inject(Location), 'replaceState').mockImplementation((path: string) => {
      replaced.push(path);
    });
    fixture = TestBed.createComponent(ConnectPage);
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  }

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  function element<T extends HTMLElement>(selector: string): T {
    const found = host.querySelector<T>(selector);
    if (!found) throw new Error(`no ${selector}`);
    return found;
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(host.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.trim() === label,
    );
    if (!found) throw new Error(`no button ${label}`);
    return found;
  }

  function typeUrl(value: string): void {
    const input = element<HTMLInputElement>('#baseUrl');
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('shows the code, polls, and saves the device key it is handed for that server', async () => {
    vi.useFakeTimers();
    await open();
    typeUrl(' https://home.example.org/ ');
    button('Get a code').click();
    fixture.detectChanges();
    expect(starts).toHaveLength(1);
    expect(starts[0].baseUrl).toBe('https://home.example.org');
    expect(starts[0].name).toMatch(/\(website\)$/);

    starts[0].response.next(STARTED);
    fixture.detectChanges();
    expect(element('.code').textContent?.trim()).toBe('K7MQ-4XRD');
    expect(element<HTMLAnchorElement>('.pair-code a').href).toBe('https://home.example.org/owner/');

    vi.advanceTimersByTime(3000);
    expect(polls).toHaveLength(1);
    expect(polls[0]).toMatchObject({ baseUrl: 'https://home.example.org', token: 'poll-token' });
    polls[0].response.next({ status: 'pending', deviceKey: null });
    vi.advanceTimersByTime(3000);
    expect(polls).toHaveLength(2);

    polls[1].response.next({ status: 'approved', deviceKey: 'dpk_approved' });
    expect(saved).toEqual([{ baseUrl: 'https://home.example.org', apiKey: 'dpk_approved' }]);
    expect(navigations).toBe(1);
  });

  it('Enter in the address field asks for a code when no key was typed', async () => {
    await open();
    typeUrl('https://home.example.org');
    element<HTMLFormElement>('form').dispatchEvent(new Event('submit', { cancelable: true }));
    fixture.detectChanges();
    expect(starts).toHaveLength(1);
  });

  it('asks for the address first, and a denied code says so and saves nothing', async () => {
    vi.useFakeTimers();
    await open();
    // Under test the page runs on localhost, where the address starts as the development server's: empty it.
    typeUrl('');
    button('Get a code').click();
    fixture.detectChanges();
    expect(starts).toHaveLength(0);
    expect(host.querySelector('#baseUrl-required')).not.toBeNull();

    typeUrl('https://home.example.org');
    button('Get a code').click();
    starts[0].response.next(STARTED);
    vi.advanceTimersByTime(3000);
    polls[0].response.next({ status: 'denied', deviceKey: null });
    fixture.detectChanges();
    expect(element('.pair-error').textContent).toContain('said no');
    expect(saved).toEqual([]);
    expect(host.querySelector('.code')).toBeNull();
  });

  it('Cancel and editing the address stop the polling', async () => {
    vi.useFakeTimers();
    await open();
    typeUrl('https://home.example.org');
    button('Get a code').click();
    starts[0].response.next(STARTED);
    fixture.detectChanges();
    button('Cancel').click();
    fixture.detectChanges();
    vi.advanceTimersByTime(10_000);
    expect(polls).toHaveLength(0);
    expect(host.querySelector('.code')).toBeNull();

    button('Get a code').click();
    starts[1].response.next(STARTED);
    fixture.detectChanges();
    typeUrl('https://other.example.org');
    vi.advanceTimersByTime(10_000);
    expect(polls).toHaveLength(0);
  });

  it('an older server without pairing says to update it or use a key', async () => {
    await open();
    typeUrl('https://home.example.org');
    button('Get a code').click();
    starts[0].response.error(new HttpErrorResponse({ status: 404 }));
    fixture.detectChanges();
    expect(element('.pair-error').textContent).toContain('cannot connect by code yet');
  });

  it('a connect link leaves the address bar at once, and connects only after "Connect"', async () => {
    await open({ server: 'https://home.example.org', invite: INVITE });
    expect(replaced).toEqual(['/connect']);
    expect(element('.server-name').textContent?.trim()).toBe('https://home.example.org');
    expect(redeems).toHaveLength(0);

    button('Connect').click();
    fixture.detectChanges();
    expect(redeems).toEqual([expect.objectContaining({ baseUrl: 'https://home.example.org', invite: INVITE })]);
    redeems[0].response.next({ deviceKey: 'dpk_invited' });
    expect(saved).toEqual([{ baseUrl: 'https://home.example.org', apiKey: 'dpk_invited' }]);
    expect(navigations).toBe(1);
  });

  it('"Not now" sends nothing; a used link says so; a broken or http link is refused', async () => {
    await open({ server: 'https://home.example.org', invite: INVITE });
    button('Not now').click();
    fixture.detectChanges();
    expect(host.querySelector('.server-name')).toBeNull();
    expect(redeems).toHaveLength(0);

    TestBed.resetTestingModule();
    await open({ server: 'https://home.example.org', invite: INVITE });
    button('Connect').click();
    redeems[0].response.error(new HttpErrorResponse({ status: 410 }));
    fixture.detectChanges();
    expect(element('.invite [role="alert"]').textContent).toContain('already used');
    expect(saved).toEqual([]);

    TestBed.resetTestingModule();
    await open({ server: 'http://home.example.org', invite: INVITE });
    expect(replaced).toEqual(['/connect']);
    expect(host.querySelector('.server-name')).toBeNull();
    expect(host.textContent).toContain('not complete or not for a secure');
  });

  it('spells the code out for screen readers', () => {
    expect(spellCode('K7MQ-4XRD')).toBe('K 7 M Q, 4 X R D');
  });
});
