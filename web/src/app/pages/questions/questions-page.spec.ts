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
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfirmService } from '../../core/confirm.service';
import { LocalDataService } from '../../core/local-data.service';
import { LocalDataError } from '../../core/local-error';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_QUESTIONS, MAX_QUESTIONS, defaultQuestion, sortQuestions } from '../../shared/question';
import type { Question } from '../../shared/question';
import { QuestionsPage } from './questions-page';

const seeded = (): Question[] => DEFAULT_QUESTIONS.map((d) => defaultQuestion(d, 'en'));
const MINE: Question = { id: 'q_1a2b3c4d', text: 'Is there a lift?', category: 'BUILDING', appliesTo: 'BOTH', defaultOn: false, sort: 20 };
const OLD: Question = { id: 'q_9f8e7d6c', text: 'Is there a water meter?', category: 'WATER_POWER', appliesTo: 'RENT', defaultOn: false, sort: 21, archived: true };

interface Fakes {
  bank: Question[];
  saveQuestions: ReturnType<typeof vi.fn>;
  addQuestion: ReturnType<typeof vi.fn>;
  deleteQuestion: ReturnType<typeof vi.fn>;
  resetQuestions: ReturnType<typeof vi.fn>;
}

function fakes(bank: Question[] = seeded()): Fakes {
  return {
    bank,
    saveQuestions: vi.fn(() => of(undefined)),
    addQuestion: vi.fn(() => of(MINE)),
    deleteQuestion: vi.fn(() => of(undefined)),
    resetQuestions: vi.fn(() => of(undefined)),
  };
}

afterEach(() => TestBed.resetTestingModule());

async function render(f: Fakes, confirm = true, lang: 'en' | 'ta' = 'en') {
  const ask = vi.fn(() => Promise.resolve(confirm));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [QuestionsPage],
    providers: [
      provideRouter([]),
      { provide: ConfirmService, useValue: { ask } },
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0),
          questions: () => of(sortQuestions(f.bank)),
          saveQuestions: f.saveQuestions,
          addQuestion: f.addQuestion,
          deleteQuestion: f.deleteQuestion,
          resetQuestions: f.resetQuestions,
        },
      },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(QuestionsPage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, f, ask, fixture };
}

const change = (el: Element, value: string): void => {
  (el as HTMLSelectElement).value = value;
  el.dispatchEvent(new Event('change'));
};
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const texts = (host: HTMLElement) => [...host.querySelectorAll<HTMLInputElement>('li.question input[type="text"]')].map((i) => i.value);
/** The name a button carries: the question's words, cut after 60 characters. */
const nm = (text: string) => (text.length > 60 ? text.slice(0, 57) + '…' : text);
const textOf = (id: string) => defaultQuestion(DEFAULT_QUESTIONS.find((d) => d.id === id)!, 'en').text;
const byLabel = <T extends Element>(host: HTMLElement, label: string) => host.querySelector<T>(`[aria-label="${label}"]`)!;

describe('QuestionsPage', () => {
  it('groups the bank by category in the person\'s order, archived questions apart', async () => {
    const { host } = await render(fakes([...seeded(), MINE, OLD]));
    expect([...host.querySelectorAll('h2[id^="questions-group-"]')].map((h) => h.textContent?.trim())).toEqual([
      'Money',
      'Water and power',
      'House rules',
      'Building',
      'Legal',
    ]);
    expect(texts(host)).toContain('Is there a lift?');
    expect(texts(host)).not.toContain('Is there a water meter?');
    expect(host.querySelector('#questions-archived-heading')?.textContent).toContain('Archived');
    expect(host.querySelector('.archived-row')?.textContent).toContain('Is there a water meter?');
  });

  it('shows the empty state when the bank is empty, and the add and reset controls', async () => {
    const { host } = await render(fakes([]));
    expect(host.querySelector('#questions-empty')?.textContent).toContain('No questions.');
    expect(host.querySelector('#questions-new')).not.toBeNull();
    expect(host.querySelector('.card.danger button')).not.toBeNull();
  });

  it('shows a skeleton first and the questions in Tamil when the app is in Tamil', async () => {
    const { host } = await render(fakes(), true, 'ta');
    expect(host.querySelector('h1')?.textContent).toContain('கேட்க வேண்டிய கேள்விகள்');
    expect(host.querySelector('li.question')).not.toBeNull();
  });

  it('edits the text, saves only that question, and puts a blank text back rather than deleting', async () => {
    const { host, f } = await render(fakes());
    const input = host.querySelector<HTMLInputElement>('#question-text-qd_water')!;
    expect(input.getAttribute('maxlength')).toBe('300');
    input.value = '  Is the water from a borewell?  ';
    input.dispatchEvent(new Event('change'));
    await flush();
    expect(f.saveQuestions).toHaveBeenCalledTimes(1);
    expect(f.saveQuestions).toHaveBeenCalledWith([expect.objectContaining({ id: 'qd_water', text: 'Is the water from a borewell?' })]);
    input.value = '   ';
    input.dispatchEvent(new Event('change'));
    await flush();
    expect(f.saveQuestions).toHaveBeenCalledTimes(1);
    expect(input.value).not.toBe('   ');
    expect(host.querySelector('.error [role="alert"]')?.textContent).toContain('Enter a question.');
  });

  it('sets the category and the scope (Rent, Buy, Both), each naming its question', async () => {
    const { host, f } = await render(fakes());
    const name = nm(textOf('qd_water'));
    const category = byLabel<HTMLSelectElement>(host, `Category of ${name}`);
    expect([...category.options].map((o) => o.textContent?.trim())).toEqual(['Money', 'Water and power', 'House rules', 'Building', 'Legal', 'Other']);
    change(category, 'RULES');
    await flush();
    expect(f.saveQuestions).toHaveBeenCalledWith([expect.objectContaining({ id: 'qd_water', category: 'RULES' })]);
    const scope = byLabel<HTMLSelectElement>(host, `Applies to, ${name}`);
    expect([...scope.options].map((o) => o.textContent?.trim())).toEqual(['Rent', 'Buy', 'Both']);
    change(scope, 'SALE');
    await flush();
    expect(f.saveQuestions).toHaveBeenCalledWith([expect.objectContaining({ id: 'qd_water', appliesTo: 'SALE' })]);
  });

  it('switches "Ask by default" on and off', async () => {
    const { host, f } = await render(fakes());
    const pets = host.querySelector<HTMLInputElement>('input[role="switch"][aria-label="Ask by default: Are pets allowed?"]')!;
    expect(pets.checked).toBe(false);
    pets.checked = true;
    pets.dispatchEvent(new Event('change'));
    await flush();
    expect(f.saveQuestions).toHaveBeenCalledWith([expect.objectContaining({ id: 'qd_pets', defaultOn: true })]);
    expect(byLabel<HTMLInputElement>(host, `Ask by default: ${nm(textOf('qd_water'))}`).checked).toBe(true);
  });

  it('moves a question within its category and renumbers 0..n, saving only the rows that changed', async () => {
    const { host, f } = await render(fakes());
    const bank = sortQuestions(seeded());
    const first = bank.find((q) => q.category === 'MONEY')!;
    const second = bank.filter((q) => q.category === 'MONEY')[1];
    byLabel<HTMLButtonElement>(host, `Move ${nm(first.text)} down`).click();
    await flush();
    const [saved] = f.saveQuestions.mock.calls[0] as [Question[]];
    // Saved in the new order of the list; only the two rows whose number changed.
    expect(saved.map((q) => [q.id, q.sort])).toEqual([
      [second.id, first.sort],
      [first.id, second.sort],
    ]);
    // The first of a category cannot go up, the last cannot go down.
    const up = byLabel<HTMLButtonElement>(host, `Move ${nm(first.text)} up`);
    expect(up.disabled).toBe(true);
  });

  it('skips a category that is not next to it when it moves', async () => {
    const bank = [
      { ...MINE, id: 'q_00000001', text: 'A', category: 'MONEY' as const, sort: 0 },
      { ...MINE, id: 'q_00000002', text: 'B', category: 'BUILDING' as const, sort: 1 },
      { ...MINE, id: 'q_00000003', text: 'C', category: 'MONEY' as const, sort: 2 },
    ];
    const { host, f } = await render(fakes(bank));
    byLabel<HTMLButtonElement>(host, 'Move A down').click();
    await flush();
    const [saved] = f.saveQuestions.mock.calls[0] as [Question[]];
    expect(saved.map((q) => [q.id, q.sort])).toEqual([
      ['q_00000003', 0],
      ['q_00000001', 2],
    ]);
  });

  it('archives a question and brings it back at the end, with "Bring back" and never "Restore"', async () => {
    const { host, f } = await render(fakes([MINE, OLD]));
    byLabel<HTMLButtonElement>(host, 'Archive Is there a lift?').click();
    await flush();
    expect(f.saveQuestions).toHaveBeenCalledWith([expect.objectContaining({ id: MINE.id, archived: true })]);
    const back = byLabel<HTMLButtonElement>(host, 'Bring back Is there a water meter?');
    expect(back.textContent?.trim()).toBe('Bring back');
    expect(host.textContent).not.toContain('Restore');
    back.click();
    await flush();
    expect(f.saveQuestions).toHaveBeenLastCalledWith([expect.objectContaining({ id: OLD.id, archived: false, sort: MINE.sort + 1 })]);
  });

  it('deletes any question, a seeded one too, only after asking', async () => {
    const yes = await render(fakes());
    byLabel<HTMLButtonElement>(yes.host, 'Delete Are pets allowed?').click();
    await flush();
    expect(yes.ask).toHaveBeenCalledWith(expect.objectContaining({ key: 'questions.confirmDelete' }), expect.objectContaining({ danger: true }));
    expect(yes.f.deleteQuestion).toHaveBeenCalledWith('qd_pets');
    const no = await render(fakes(), false);
    byLabel<HTMLButtonElement>(no.host, 'Delete Are pets allowed?').click();
    await flush();
    expect(no.f.deleteQuestion).not.toHaveBeenCalled();
  });

  it('adds a question with a category, and asks for a text when it is blank', async () => {
    const { host, f } = await render(fakes());
    const input = host.querySelector<HTMLInputElement>('#questions-new')!;
    expect(input.getAttribute('maxlength')).toBe('300');
    host.querySelector('form.add')!.dispatchEvent(new Event('submit'));
    await flush();
    expect(f.addQuestion).not.toHaveBeenCalled();
    expect(host.querySelector('#questions-new-error')?.textContent).toContain('Enter a question.');
    input.value = 'Is there a lift?';
    input.dispatchEvent(new Event('input'));
    const category = host.querySelector<HTMLSelectElement>('#questions-new-category')!;
    category.value = 'BUILDING';
    category.dispatchEvent(new Event('change'));
    host.querySelector('form.add')!.dispatchEvent(new Event('submit'));
    await flush();
    expect(f.addQuestion).toHaveBeenCalledWith('Is there a lift?', 'BUILDING');
  });

  it('disables Add question at 100 questions and says "At most 100 questions"', async () => {
    const full: Question[] = Array.from({ length: MAX_QUESTIONS }, (_, i) => ({ ...MINE, id: `q_${i.toString(16).padStart(8, '0')}`, text: `Own ${i}`, sort: i }));
    const { host } = await render(fakes(full));
    expect(host.querySelector<HTMLButtonElement>('form.add button[type="submit"]')!.disabled).toBe(true);
    expect(host.querySelector<HTMLInputElement>('#questions-new')!.disabled).toBe(true);
    expect(host.textContent).toContain('At most 100 questions');
    const few = await render(fakes([MINE]));
    expect(few.host.textContent).not.toContain('At most 100 questions');
  });

  it('counts archived questions toward the cap of 100', async () => {
    const full: Question[] = Array.from({ length: MAX_QUESTIONS }, (_, i) => ({ ...MINE, id: `q_${i.toString(16).padStart(8, '0')}`, text: `Own ${i}`, sort: i, archived: i === 0 ? true : undefined }));
    const { host } = await render(fakes(full));
    expect(host.querySelector<HTMLButtonElement>('form.add button[type="submit"]')!.disabled).toBe(true);
  });

  it('shows a refused add as an error next to the control', async () => {
    const f = fakes();
    f.addQuestion = vi.fn(() => throwError(() => new LocalDataError('questions.max')));
    const { host } = await render(f);
    const input = host.querySelector<HTMLInputElement>('#questions-new')!;
    input.value = 'One more';
    input.dispatchEvent(new Event('input'));
    host.querySelector('form.add')!.dispatchEvent(new Event('submit'));
    await flush();
    expect(host.querySelector('.error [role="alert"]')?.textContent).toContain('At most 100 questions');
  });

  it('resets to the defaults in the app language, only after the person confirms', async () => {
    const yes = await render(fakes([MINE]));
    expect(yes.host.querySelector('.card.danger')?.textContent).toContain('Brings back the standard questions in your language. Your own questions stay.');
    yes.host.querySelector<HTMLButtonElement>('.card.danger button')!.click();
    await flush();
    expect(yes.ask).toHaveBeenCalledWith({ key: 'questions.confirmReset' }, expect.objectContaining({ danger: true }));
    expect(yes.f.resetQuestions).toHaveBeenCalledWith('en');
    const no = await render(fakes([MINE]), false);
    no.host.querySelector<HTMLButtonElement>('.card.danger button')!.click();
    await flush();
    expect(no.f.resetQuestions).not.toHaveBeenCalled();
  });

  it('gives every button a name that says which question it belongs to', async () => {
    const { host } = await render(fakes([MINE]));
    const card = host.querySelector('li.question')!;
    const names = [...card.querySelectorAll('button')].map((b) => b.getAttribute('aria-label'));
    expect(names).toEqual([
      'Move Is there a lift? up',
      'Move Is there a lift? down',
      'Archive Is there a lift?',
      'Delete Is there a lift?',
    ]);
  });
});
