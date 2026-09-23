import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiSessionState } from '../../core/ai-session.state';
import { AiService } from '../../core/ai.service';
import { ConfigService } from '../../core/config.service';
import { HouseApiService } from '../../core/house-api.service';
import type { StatsDto } from '../../core/models';
import type { ApiConfig } from '../../core/config.service';
import { TranslationService } from '../../i18n/translation.service';
import { ConnectPage } from './connect-page';

const STATS: StatsDto = { houses: 3, shortlisted: 1, rejected: 0, visits: 2, streets: 1 };

/** One testConnection() call: the values it was asked to test, and the response the test controls. */
interface Check {
  readonly baseUrl: string;
  readonly apiKey: string;
  readonly response: Subject<StatsDto>;
}

/**
 * Connect's check (web UX gates R6/R9/R18, round 1 review): a check belongs to the values it tested. Editing a field or
 * leaving the page drops a running check, so its "Connected" (and, from "Save and continue", its save and navigation)
 * can never land on values nobody tested or on another page; what is saved is exactly what was sent to the server.
 */
describe('ConnectPage', () => {
  let fixture: ComponentFixture<ConnectPage>;
  let host: HTMLElement;
  let checks: Check[];
  /** Every ConfigService.save() and Router.navigate() the page made. */
  let saved: ApiConfig[];
  let navigations: number;

  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    checks = [];
    saved = [];
    navigations = 0;
    const api = {
      testConnection: (baseUrl: string, apiKey: string) => {
        const response = new Subject<StatsDto>();
        checks.push({ baseUrl, apiKey, response });
        return response.asObservable();
      },
    };
    TestBed.configureTestingModule({
      imports: [ConnectPage],
      providers: [
        provideRouter([]),
        { provide: HouseApiService, useValue: api },
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
    fixture = TestBed.createComponent(ConnectPage);
    host = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  function element<T extends HTMLElement>(selector: string): T {
    const found = host.querySelector<T>(selector);
    if (!found) throw new Error(`no ${selector}`);
    return found;
  }

  /** Types into a field the way a user does: the input event reaches ngModel and (ngModelChange)="onEdit()". */
  async function type(id: 'baseUrl' | 'apiKey', value: string): Promise<void> {
    const input = element<HTMLInputElement>(`#${id}`);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  /** "Save and continue" (the form's submit). */
  async function saveAndContinue(): Promise<void> {
    element<HTMLFormElement>('form').dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
  }

  /** The "Test" button: the first in the actions row. */
  function testButton(): HTMLButtonElement {
    return element<HTMLButtonElement>('.actions button');
  }

  function busy(): boolean {
    return testButton().getAttribute('aria-disabled') === 'true';
  }

  it('drops a running check when a field is edited: nothing is saved and nothing navigates', async () => {
    await type('baseUrl', 'https://a.example.org');
    await type('apiKey', 'key-a');
    await saveAndContinue();
    expect(checks.length).toBe(1);
    expect(checks[0].response.observed).toBe(true);
    expect(busy()).toBe(true);

    await type('baseUrl', 'https://b.example.org');

    expect(checks[0].response.observed).toBe(false);
    expect(busy()).toBe(false);
    // A late answer for the old values has nowhere to go.
    checks[0].response.next(STATS);
    await fixture.whenStable();
    expect(saved).toEqual([]);
    expect(navigations).toBe(0);
  });

  it('saves exactly the values it tested, not an earlier check that was edited away', async () => {
    await type('baseUrl', 'https://a.example.org');
    await type('apiKey', 'key-a');
    await saveAndContinue();
    // Changed while the first check runs: that check is dropped, and a new "Save and continue" tests the new values.
    await type('baseUrl', '  https://b.example.org/  ');
    await type('apiKey', ' key-b ');
    await saveAndContinue();
    expect(checks.length).toBe(2);
    expect(checks[1]).toMatchObject({ baseUrl: 'https://b.example.org', apiKey: 'key-b' });

    checks[0].response.next(STATS);
    checks[1].response.next(STATS);
    await fixture.whenStable();

    // What was saved is what the server answered for (normalised the same way), never the first values.
    expect(saved).toEqual([{ baseUrl: checks[1].baseUrl, apiKey: checks[1].apiKey }]);
    expect(navigations).toBe(1);
  });

  it('drops a running check when the page goes away: no save and no navigation from another page', async () => {
    await type('baseUrl', 'https://a.example.org');
    await type('apiKey', 'key-a');
    await saveAndContinue();
    expect(checks[0].response.observed).toBe(true);

    fixture.destroy();

    expect(checks[0].response.observed).toBe(false);
    checks[0].response.next(STATS);
    expect(saved).toEqual([]);
    expect(navigations).toBe(0);
  });

  it('a failed "Save and continue" offers "Save anyway"; a Test that then succeeds removes it and focus goes to Save', async () => {
    await type('baseUrl', 'https://a.example.org');
    await type('apiKey', 'key-a');
    await saveAndContinue();
    checks[0].response.error(new Error('offline'));
    await fixture.whenStable();
    const saveAnyway = Array.from(host.querySelectorAll<HTMLButtonElement>('.actions button')).find(
      (b) => b.textContent?.trim() === 'Save anyway',
    );
    expect(saveAnyway).toBeDefined();
    expect(host.querySelector('[role="alert"] .error')).not.toBeNull();

    saveAnyway?.focus();
    testButton().click();
    await fixture.whenStable();
    expect(checks.length).toBe(2);
    checks[1].response.next(STATS);
    await fixture.whenStable();

    expect(host.querySelector('[role="status"] .success')).not.toBeNull();
    expect(saved).toEqual([]);
    expect(document.activeElement?.id).toBe('connect-save');
  });
});
