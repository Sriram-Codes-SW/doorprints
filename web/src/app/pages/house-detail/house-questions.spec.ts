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
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseAnswer, HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_QUESTIONS, defaultQuestion } from '../../shared/question';
import type { Question } from '../../shared/question';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { HouseDetailPage } from './house-detail-page';

const HOUSE: HouseDto = {
  id: '5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10',
  label: 'Blue gate 2BHK',
  lat: 12.9716,
  lon: 77.5946,
  status: 'NEW',
  priceType: 'RENT',
  checklist: {},
  deleted: false,
  createdAt: '2026-09-20T10:00:00.000Z',
  updatedAt: '2026-09-20T10:00:00.000Z',
  syncVersion: 0,
};
const BANK: Question[] = DEFAULT_QUESTIONS.map((d) => defaultQuestion(d, 'en'));
const ASKED: HouseAnswer = { id: 'a1', questionId: 'qd_water', text: 'Water hours?', answer: 'Twice a day', status: 'ANSWERED', sort: 0 };
const OPEN: HouseAnswer = { id: 'a2', text: 'Is the terrace open?', status: 'OPEN', sort: 1 };

interface Page {
  draft: () => HouseDto;
}

/** The house form on a saved house holding `answers`, with `bank` as the question bank. */
async function open(answers: HouseAnswer[] | null, over: Partial<HouseDto> = {}, bank: Question[] = BANK) {
  const house: HouseDto = { ...HOUSE, ...over, answers };
  const saved: HouseDto[] = [];
  TestBed.configureTestingModule({
    imports: [HouseDetailPage],
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: {
          houses: () => of([]),
          brokers: () => of([]),
          scoring: () => of(DEFAULT_SCORING),
          questions: () => of(bank),
          house: () => of(house),
          visits: () => of([]),
          viewingsOf: () => of([]),
          areas: () => of([]),
          areaNotes: () => of([]),
          places: () => of([]),
          settled: signal(0),
          photos: () => of([]),
          saveHouse: (body: HouseDto) => {
            saved.push(body);
            return of(body);
          },
        },
      },
      { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve('FT') } },
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: house.id }), queryParamMap: convertToParamMap({}) } } },
      { provide: GeocodeService, useValue: { reverse: () => of({}) } },
      { provide: AiService, useValue: { enabled: signal(false), usesOwnKey: signal(false), extractListing: () => of() } },
    ],
  });
  await TestBed.inject(TranslationService).setLang('en');
  const fixture = TestBed.createComponent(HouseDetailPage);
  await settled(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, page: fixture.componentInstance as unknown as Page, saved };
}

async function settled(fixture: ComponentFixture<HouseDetailPage>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function type(fixture: ComponentFixture<HouseDetailPage>, el: HTMLInputElement | HTMLTextAreaElement, value: string) {
  el.value = value;
  el.dispatchEvent(new Event('input'));
  return settled(fixture);
}

const click = async (fixture: ComponentFixture<HouseDetailPage>, el: Element | null) => {
  (el as HTMLElement).click();
  await settled(fixture);
};

// The house page's map watches its container with a ResizeObserver, which jsdom does not have. Another spec may stub
// it globally, so this spec brings its own and does not depend on the order the specs run in.
beforeEach(() => {
  if (typeof ResizeObserver === 'undefined') {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {
          // jsdom lays nothing out.
        }
        unobserve(): void {
          // Nothing observed.
        }
        disconnect(): void {
          // Nothing observed.
        }
      },
    );
  }
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
  TestBed.resetTestingModule();
});

const cards = (host: HTMLElement) => [...host.querySelectorAll('.question')];
const questionsOf = (host: HTMLElement) => cards(host).map((c) => c.querySelector('.question-text')?.textContent?.replace(/\(Skipped\)/, '').trim());

describe('HouseDetailPage: the Questions to ask section (slice 3a)', () => {
  it('shows the empty state, Add the usual questions and Add a question when the house has none', async () => {
    const { host } = await open(null);
    expect(host.querySelector('#questions-heading')?.textContent).toContain('Questions to ask');
    expect(host.querySelector('#questions-empty')?.textContent).toContain('No questions yet. Add the usual questions to bring them to the viewing.');
    expect(host.querySelector('#questions-summary')).toBeNull();
    expect(host.querySelector<HTMLButtonElement>('#questions-usual')!.disabled).toBe(false);
    expect(host.querySelector<HTMLButtonElement>('#questions-add')!.disabled).toBe(false);
    expect(host.querySelector('#questions-usual-hint')).toBeNull();
    expect(host.querySelector('#questions-max')).toBeNull();
  });

  it('adds the usual questions for a rent house: 8 open cards, then disables the button with the hint "Already added"', async () => {
    const { fixture, host, page } = await open(null);
    await click(fixture, host.querySelector('#questions-usual'));
    expect(page.draft().answers!.map((a) => a.questionId)).toEqual([
      'qd_maintenance',
      'qd_deposit',
      'qd_brokerage',
      'qd_lockin',
      'qd_water',
      'qd_power',
      'qd_parking',
      'qd_floor',
    ]);
    expect(cards(host)).toHaveLength(8);
    expect(host.querySelector('#questions-summary')?.textContent).toContain('0 of 8 answered');
    const usual = host.querySelector<HTMLButtonElement>('#questions-usual')!;
    expect(usual.disabled).toBe(true);
    expect(host.querySelector('#questions-usual-hint')?.textContent).toContain('Already added');
    expect(usual.getAttribute('aria-describedby')).toBe('questions-usual-hint');
    expect(host.querySelector('#questions-empty')).toBeNull();
  });

  it('adds the sale set for a sale house', async () => {
    const { fixture, host, page } = await open(null, { priceType: 'SALE' });
    await click(fixture, host.querySelector('#questions-usual'));
    expect(page.draft().answers!.map((a) => a.questionId)).toContain('qd_rera');
    expect(page.draft().answers!.map((a) => a.questionId)).not.toContain('qd_deposit');
    expect(page.draft().answers).toHaveLength(9);
  });

  it('pre-fills the answer from the cost: the deposit, and the maintenance that the rent does not include', async () => {
    const { fixture, host, page } = await open(null, { cost: { deposit: 64000, depositMonths: 2, maintenance: 2500, maintenanceIncluded: false } });
    await click(fixture, host.querySelector('#questions-usual'));
    const deposit = page.draft().answers!.find((a) => a.questionId === 'qd_deposit')!;
    expect(deposit.status).toBe('ANSWERED');
    expect(deposit.answer).toBe('₹64,000, 2 months');
    expect(page.draft().answers!.find((a) => a.questionId === 'qd_maintenance')!.answer).toBe('₹2,500 a month (not included)');
    expect(host.querySelector('#questions-summary')?.textContent).toContain('2 of 8 answered');
    // The person's own text is in the box, ready to edit.
    expect(host.querySelector<HTMLTextAreaElement>(`#answer-${deposit.id}`)!.value).toBe('₹64,000, 2 months');
  });

  it('shows the answered ones with their answer and the open ones first', async () => {
    const { host } = await open([ASKED, OPEN]);
    expect(questionsOf(host)).toEqual(['Is the terrace open?', 'Water hours?']);
    expect(host.querySelector('#questions-summary')?.textContent).toContain('1 of 2 answered');
    expect(host.querySelector<HTMLTextAreaElement>('#answer-a1')!.value).toBe('Twice a day');
  });

  it('typing an answer marks the question Answered without moving its card, and clearing it marks it Open', async () => {
    const { fixture, host, page } = await open([OPEN, { ...OPEN, id: 'a3', text: 'Second open', sort: 2 }]);
    await type(fixture, host.querySelector<HTMLTextAreaElement>('#answer-a2')!, 'Yes, on the 4th floor');
    expect(page.draft().answers![0]).toMatchObject({ id: 'a2', answer: 'Yes, on the 4th floor', status: 'ANSWERED' });
    expect(questionsOf(host)).toEqual(['Is the terrace open?', 'Second open']);
    expect(host.querySelector('#questions-summary')?.textContent).toContain('1 of 2 answered');
    await type(fixture, host.querySelector<HTMLTextAreaElement>('#answer-a2')!, '   ');
    expect(page.draft().answers![0]).toMatchObject({ id: 'a2', answer: null, status: 'OPEN' });
    expect(host.querySelector('#questions-summary')?.textContent).toContain('0 of 2 answered');
  });

  it('limits an answer to 2000 characters', async () => {
    const { host } = await open([OPEN]);
    expect(host.querySelector('#answer-a2')!.getAttribute('maxlength')).toBe('2000');
  });

  it('skips a question with the Skip toggle and takes it back to Answered or Open', async () => {
    const { fixture, host, page } = await open([ASKED, OPEN]);
    const skip = (id: string) => host.querySelector<HTMLButtonElement>(`.question:has(#answer-${id}) button:not(.btn-danger)`)!;
    await click(fixture, skip('a2'));
    expect(page.draft().answers!.find((a) => a.id === 'a2')!.status).toBe('SKIPPED');
    expect(skip('a2').getAttribute('aria-pressed')).toBe('true');
    expect(host.textContent).toContain('(Skipped)');
    await click(fixture, skip('a2'));
    expect(page.draft().answers!.find((a) => a.id === 'a2')!.status).toBe('OPEN');
    await click(fixture, skip('a1'));
    await click(fixture, skip('a1'));
    expect(page.draft().answers!.find((a) => a.id === 'a1')!.status).toBe('ANSWERED');
  });

  it('removes a question from this house only, and shows the empty state when the last one goes', async () => {
    const { fixture, host, page } = await open([OPEN]);
    await click(fixture, host.querySelector('.question .btn-danger'));
    expect(page.draft().answers).toEqual([]);
    expect(host.querySelector('#questions-empty')).not.toBeNull();
  });

  it('adds one bank question from the picker: grouped by category, without the ones already on the house or archived', async () => {
    const bank = BANK.map((q) => (q.id === 'qd_nonveg' ? { ...q, archived: true } : q));
    const { fixture, host, page } = await open([ASKED], {}, bank);
    expect(host.querySelector('#questions-picker')).toBeNull();
    const add = host.querySelector<HTMLButtonElement>('#questions-add')!;
    await click(fixture, add);
    expect(add.getAttribute('aria-expanded')).toBe('true');
    const picker = host.querySelector('#questions-picker')!;
    const groups = [...picker.querySelectorAll('.picker-group')].map((g) => g.textContent?.trim());
    expect(groups).toEqual(['Money', 'Water and power', 'House rules', 'Building', 'Legal']);
    const items = [...picker.querySelectorAll('.picker-item')].map((b) => b.textContent?.trim());
    expect(items).not.toContain(BANK.find((q) => q.id === 'qd_water')!.text);
    expect(items).not.toContain(BANK.find((q) => q.id === 'qd_nonveg')!.text);
    expect(items).toContain(BANK.find((q) => q.id === 'qd_pets')!.text);
    const pets = [...picker.querySelectorAll<HTMLButtonElement>('.picker-item')].find((b) => b.textContent?.trim() === BANK.find((q) => q.id === 'qd_pets')!.text)!;
    await click(fixture, pets);
    expect(page.draft().answers!.at(-1)).toMatchObject({ questionId: 'qd_pets', text: 'Are pets allowed?', status: 'OPEN', sort: 1 });
    expect(host.querySelector('#questions-picker')).toBeNull();
  });

  it('adds a question of its own from "Or ask something else", with no questionId', async () => {
    const { fixture, host, page } = await open([ASKED]);
    await click(fixture, host.querySelector('#questions-add'));
    const button = host.querySelector<HTMLButtonElement>('#questions-adhoc-add')!;
    expect(button.disabled).toBe(true);
    expect(host.querySelector('label[for="questions-adhoc"]')?.textContent).toContain('Or ask something else');
    const input = host.querySelector<HTMLInputElement>('#questions-adhoc')!;
    expect(input.getAttribute('maxlength')).toBe('300');
    await type(fixture, input, '  Is there a rooftop?  ');
    expect(button.disabled).toBe(false);
    await click(fixture, button);
    const added = page.draft().answers!.at(-1)!;
    expect(added.text).toBe('Is there a rooftop?');
    expect(added.questionId).toBeUndefined();
    expect(added.status).toBe('OPEN');
  });

  it('says so in the picker when every question of the bank is already on the house', async () => {
    const { fixture, host } = await open([ASKED], {}, [BANK.find((q) => q.id === 'qd_water')!]);
    await click(fixture, host.querySelector('#questions-add'));
    expect(host.querySelector('#questions-none')?.textContent).toContain('already on this house');
    expect(host.querySelector('#questions-adhoc')).not.toBeNull();
  });

  it('disables Add at 60 questions and says "At most 60 questions"', async () => {
    const sixty = Array.from({ length: 60 }, (_, i): HouseAnswer => ({ id: 'a' + i, text: 'Q' + i, status: 'OPEN', sort: i }));
    const { fixture, host, page } = await open(sixty);
    expect(host.querySelector<HTMLButtonElement>('#questions-add')!.disabled).toBe(true);
    expect(host.querySelector<HTMLButtonElement>('#questions-usual')!.disabled).toBe(true);
    expect(host.querySelector('#questions-max')?.textContent?.trim()).toBe('At most 60 questions');
    await click(fixture, host.querySelector('#questions-add'));
    expect(host.querySelector('#questions-picker')).toBeNull();
    // One fewer and it is offered again.
    await click(fixture, host.querySelector('.question .btn-danger'));
    expect(page.draft().answers).toHaveLength(59);
    expect(host.querySelector<HTMLButtonElement>('#questions-add')!.disabled).toBe(false);
    expect(host.querySelector('#questions-max')).toBeNull();
  });

  it('stops "Add the usual questions" at 60', async () => {
    const fiftyEight = Array.from({ length: 58 }, (_, i): HouseAnswer => ({ id: 'a' + i, text: 'Q' + i, status: 'OPEN', sort: i }));
    const { fixture, host, page } = await open(fiftyEight);
    await click(fixture, host.querySelector('#questions-usual'));
    expect(page.draft().answers).toHaveLength(60);
  });

  it('gives every control the question in its accessible name, and keeps them buttons and a labelled box', async () => {
    const { host } = await open([ASKED]);
    const card = host.querySelector('.question')!;
    expect(card.getAttribute('role')).toBe('group');
    expect(card.getAttribute('aria-label')).toBe('Water hours?');
    expect(card.querySelector('textarea')!.getAttribute('aria-label')).toBe('Answer to: Water hours?');
    const names = [...card.querySelectorAll('button')].map((b) => b.getAttribute('aria-label'));
    expect(names).toEqual(['Skip: Water hours?', 'Remove from this house: Water hours?']);
    expect(host.querySelector('label[for="answer-a1"]')?.textContent).toContain('Answer');
  });

  it('saves the answers through cleanAnswers: coerced, and absent when there are none', async () => {
    const { fixture, host, saved } = await open([ASKED, { ...OPEN, answer: 'Yes' }]);
    await click(fixture, host.querySelector('button.btn-primary'));
    expect(saved[0].answers).toEqual([ASKED, { ...OPEN, answer: 'Yes', status: 'ANSWERED' }]);
    for (const b of [...host.querySelectorAll('.question .btn-danger')]) (b as HTMLElement).click();
    await settled(fixture);
    await click(fixture, host.querySelector('button.btn-primary'));
    expect(saved[1].answers).toBeNull();
  });
});
