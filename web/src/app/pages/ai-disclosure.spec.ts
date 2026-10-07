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

import { Type, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../core/ai.service';
import { LocalDataService } from '../core/local-data.service';
import { TranslationService } from '../i18n/translation.service';
import { AskPage } from './ask/ask-page';
import { PlanPage } from './plan/plan-page';

/**
 * Before anything is sent, the Ask and Plan pages say where it goes (S4b-BL-151, T-I43): the host of the person's own AI
 * service, taken from what they saved, or "the AI provider set up on your server". (The house page's sentence is in
 * house-detail-page.spec.)
 */
describe('Ask and Plan: the disclosure names the host', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  function render(component: Type<AskPage | PlanPage>, own: boolean, host: string) {
    TestBed.resetTestingModule();
    if (typeof ResizeObserver === 'undefined') {
      vi.stubGlobal('ResizeObserver', class { observe(): void { /* jsdom lays nothing out */ } disconnect(): void { /* nothing observed */ } });
    }
    TestBed.configureTestingModule({
      imports: [component],
      providers: [
        provideRouter([]),
        { provide: AiService, useValue: { enabled: signal(true), usesOwnKey: signal(own), ownHost: signal(host) } },
        { provide: LocalDataService, useValue: { houses: () => of([]) } },
      ],
    });
    TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(component);
    return fixture;
  }

  it.each([
    [AskPage, '#ask-disclosure'],
    [PlanPage, '#plan-disclosure'],
  ])('%#: with the own AI it says the host, with the server it does not', async (component, id) => {
    const own = render(component, true, 'openrouter.ai');
    await own.whenStable();
    expect((own.nativeElement as HTMLElement).querySelector(id)!.textContent).toContain(
      'sent from this browser straight to openrouter.ai with your own key',
    );
    const server = render(component, false, '');
    await server.whenStable();
    const said = (server.nativeElement as HTMLElement).querySelector(id)!.textContent;
    expect(said).toContain('the AI provider set up on your server');
    expect(said).not.toContain('straight to');
  });
});
