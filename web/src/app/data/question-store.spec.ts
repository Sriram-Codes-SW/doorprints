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

import { beforeEach, describe, expect, it } from 'vitest';
import { LocalDataError } from '../core/local-error';
import { LocalStore } from './local-store.service';
import { SETTING_KEYS } from './records';
import { DEFAULT_QUESTIONS, MAX_QUESTIONS, MAX_QUESTION_TEXT } from '../shared/question';
import type { Question } from '../shared/question';

const T1 = Date.parse('2026-09-01T00:00:00.000Z');
const T2 = Date.parse('2026-09-02T00:00:00.000Z');
const T3 = Date.parse('2026-09-03T00:00:00.000Z');

const own = (n: number, over: Partial<Question> = {}): Question => ({
  id: `q_${n.toString(16).padStart(8, '0')}`,
  text: `Own ${n}`,
  category: 'OTHER',
  appliesTo: 'BOTH',
  defaultOn: false,
  sort: n,
  ...over,
});

/** The question bank (records of type `question`) through the store's own methods: rows, seeding, the cap and the ids. */
describe('the question bank (LocalStore.questions)', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('gives each question row its record id, its edit time and the typed question, oldest edit first, and skips a blank one', async () => {
    await store.saveQuestion(own(2, { text: 'Second asked' }), T2);
    await store.saveQuestion(own(1, { text: 'First asked' }), T1);
    await store.records.save('question', 'q_bad00001', { text: '   ' }, T3);
    expect(await store.questionRows()).toEqual([
      { id: own(1).id, updatedAt: '2026-09-01T00:00:00.000Z', question: own(1, { text: 'First asked' }) },
      { id: own(2).id, updatedAt: '2026-09-02T00:00:00.000Z', question: own(2, { text: 'Second asked' }) },
    ]);
  });

  it('lists the bank by sort number then id, an archived question included', async () => {
    await store.saveQuestion(own(3, { sort: 5 }), T1);
    await store.saveQuestion(own(2, { sort: 5, archived: true }), T1);
    await store.saveQuestion(own(1, { sort: 9 }), T1);
    await store.saveQuestion(own(4, { sort: 0 }), T1);
    expect((await store.questions()).map((q) => q.id)).toEqual([own(4).id, own(2).id, own(3).id, own(1).id]);
  });

  it('bumps the revision when a seeding writes something and not when every default is already there', async () => {
    const start = store.revision();
    await store.seedQuestions('en', T1);
    expect(store.revision()).toBe(start + 1);
    await store.seedQuestions('en', T2);
    expect(store.revision()).toBe(start + 1);
  });

  it('seeds the first start in the language it is asked for and sets the setting after the bank is written', async () => {
    expect(await store.setting(SETTING_KEYS.questionsSeeded)).toBeNull();
    await store.seedQuestionsOnce(() => 'te', T1);
    expect((await store.questions()).find((q) => q.id === 'qd_water')!.text).toBe(DEFAULT_QUESTIONS.find((d) => d.id === 'qd_water')!.text.te);
    expect(await store.setting(SETTING_KEYS.questionsSeeded)).toBe('1');
  });

  it('seeds again after Remove all data in the language the app is in then, and sets the setting again', async () => {
    let language = 'hi';
    await store.seedQuestionsOnce(() => language, T1);
    language = 'ta';
    await store.clearEverything();
    expect((await store.questions()).find((q) => q.id === 'qd_water')!.text).toBe(DEFAULT_QUESTIONS.find((d) => d.id === 'qd_water')!.text.ta);
    expect(await store.setting(SETTING_KEYS.questionsSeeded)).toBe('1');
  });

  it('resets every default, edited ones included, as the own dirty edit of the person stamped now', async () => {
    await store.seedQuestions('en', T1);
    expect(await store.records.dirty()).toEqual([]);
    const start = store.revision();
    expect(await store.resetQuestions('hi', T2)).toBe(DEFAULT_QUESTIONS.length);
    expect(store.revision()).toBe(start + 1);
    const dirty = await store.records.dirty();
    expect(dirty).toHaveLength(DEFAULT_QUESTIONS.length);
    expect(dirty.every((r) => r.updatedAt === '2026-09-02T00:00:00.000Z')).toBe(true);
  });

  it('keeps the sync version of a default when a reset writes it again, a deleted one included', async () => {
    const at = '2026-09-01T00:00:00.000Z';
    const payload = { text: 'Edited elsewhere', category: 'OTHER', appliesTo: 'BOTH', defaultOn: false, sort: 3 };
    await store.records.putFromServer({ type: 'question', id: 'qd_water', payload, updatedAt: at, deleted: false, syncVersion: 7 });
    await store.records.putFromServer({ type: 'question', id: 'qd_pets', payload: {}, updatedAt: at, deleted: true, syncVersion: 5 });
    await store.resetQuestions('en', T2);
    expect((await store.records.get('question', 'qd_water'))!.syncVersion).toBe(7);
    expect((await store.records.get('question', 'qd_pets'))!.syncVersion).toBe(5);
  });

  it('brings back only as many defaults as fit under 100 questions, in the order of the list', async () => {
    for (let i = 0; i < MAX_QUESTIONS - 2; i++) await store.saveQuestion(own(i), T1);
    expect(await store.resetQuestions('en', T2)).toBe(2);
    const bank = await store.questions();
    expect(bank).toHaveLength(MAX_QUESTIONS);
    expect(bank.filter((q) => q.id.startsWith('qd_')).map((q) => q.id).sort()).toEqual([DEFAULT_QUESTIONS[0].id, DEFAULT_QUESTIONS[1].id].sort());
  });

  it('resets the defaults that are already in a full bank, because they take no new place', async () => {
    await store.seedQuestions('en', T1);
    for (let i = 0; i < MAX_QUESTIONS - DEFAULT_QUESTIONS.length; i++) await store.saveQuestion(own(i), T1);
    expect(await store.questions()).toHaveLength(MAX_QUESTIONS);
    expect(await store.resetQuestions('en', T2)).toBe(DEFAULT_QUESTIONS.length);
  });

  it('does not seed a default past the cap either', async () => {
    for (let i = 0; i < MAX_QUESTIONS - 1; i++) await store.saveQuestion(own(i), T1);
    expect(await store.seedQuestions('en', T2)).toBe(1);
    expect(await store.questions()).toHaveLength(MAX_QUESTIONS);
  });

  it('trims the text on a save, accepts exactly 300 characters and refuses a blank or a 301st', async () => {
    await store.saveQuestion(own(1, { text: '  Is it gated?  ' }), T1);
    expect((await store.records.get('question', own(1).id))!.payload['text']).toBe('Is it gated?');
    await store.saveQuestion(own(2, { text: 'x'.repeat(MAX_QUESTION_TEXT) }), T1);
    await expect(store.saveQuestion(own(3, { text: 'x'.repeat(MAX_QUESTION_TEXT + 1) }), T1)).rejects.toMatchObject({ key: 'error.badRecord' });
    await expect(store.saveQuestion(own(4, { text: '   ' }), T1)).rejects.toMatchObject({ key: 'error.badRecord' });
    expect((await store.records.all()).map((r) => r.id)).toEqual([own(1).id, own(2).id]);
  });

  it('accepts the fixed id of a default and refuses an id that is not q_ and 8 lowercase hex characters', async () => {
    await store.saveQuestion(own(1, { id: 'qd_water' }), T1);
    expect((await store.records.get('question', 'qd_water'))!.dirty).toBe(true);
    for (const id of ['q_AAAAAAAA', 'q_aaaaaaa', 'q_aaaaaaaaa', 'x_aaaaaaaa', 'qd_unknown']) {
      await expect(store.saveQuestion(own(1, { id }), T1)).rejects.toMatchObject({ key: 'error.badRecord' });
    }
  });

  it('counts only live questions toward the cap: a deleted one frees its place', async () => {
    for (let i = 0; i < MAX_QUESTIONS; i++) await store.saveQuestion(own(i), T1);
    await expect(store.saveQuestion(own(MAX_QUESTIONS), T1)).rejects.toMatchObject({ key: 'questions.max' });
    await store.deleteQuestion(own(0).id, T2);
    await store.saveQuestion(own(MAX_QUESTIONS), T2);
    expect(await store.questions()).toHaveLength(MAX_QUESTIONS);
  });

  it('numbers the first question of an empty bank 0 and the next one past the highest, an archived one counted', async () => {
    expect((await store.addQuestion('First', 'OTHER', 'BOTH', T1)).sort).toBe(0);
    await store.saveQuestion(own(1, { sort: 40, archived: true }), T1);
    await store.saveQuestion(own(2, { sort: 7 }), T1);
    expect((await store.addQuestion('After the highest', 'OTHER', 'BOTH', T2)).sort).toBe(41);
  });

  it('adds with the category Other and the scope Both unless it is told otherwise', async () => {
    expect(await store.addQuestion('Plain', undefined, undefined, T1)).toMatchObject({ category: 'OTHER', appliesTo: 'BOTH', defaultOn: false });
  });

  it('accepts a text of exactly 300 characters once trimmed and refuses a blank or a 301st without writing', async () => {
    await store.addQuestion(' ' + 'x'.repeat(MAX_QUESTION_TEXT) + ' ', 'OTHER', 'BOTH', T1);
    await expect(store.addQuestion('x'.repeat(MAX_QUESTION_TEXT + 1), 'OTHER', 'BOTH', T1)).rejects.toMatchObject({ key: 'error.badRecord' });
    await expect(store.addQuestion('  ', 'OTHER', 'BOTH', T1)).rejects.toMatchObject({ key: 'error.badRecord' });
    expect(await store.records.all()).toHaveLength(1);
  });

  it('adds at 99 questions and counts only live ones against the 100: a deleted question frees its place', async () => {
    for (let i = 0; i < MAX_QUESTIONS; i++) await store.saveQuestion(own(i), T1);
    await store.deleteQuestion(own(0).id, T2);
    await store.addQuestion('Fits now', 'OTHER', 'BOTH', T2);
    await expect(store.addQuestion('Does not', 'OTHER', 'BOTH', T2)).rejects.toMatchObject({ key: 'questions.max' });
  });

  it('gives up with a bad-record error when every drawn id clashes, and writes nothing', async () => {
    await store.saveQuestion(own(1), T1);
    await expect(store.addQuestion('Two', 'OTHER', 'BOTH', T2, () => own(1).id)).rejects.toBeInstanceOf(LocalDataError);
    expect(await store.records.all()).toHaveLength(1);
  });

  it('writes nothing and does not bump the revision for a delete of an unknown question, or for an empty list', async () => {
    const start = store.revision();
    await store.deleteQuestion('q_00000000', T1);
    await store.saveQuestions([], T1);
    expect(store.revision()).toBe(start);
    expect(await store.records.all()).toEqual([]);
  });

  it('bumps the revision on a save and on the delete of a stored question', async () => {
    const start = store.revision();
    await store.saveQuestion(own(1), T1);
    expect(store.revision()).toBe(start + 1);
    await store.deleteQuestion(own(1).id, T2);
    expect(store.revision()).toBe(start + 2);
  });

  it('keeps the questions saved before the one that fails when a list is saved', async () => {
    await expect(store.saveQuestions([own(1), own(2, { id: 'nope' }), own(3)], T1)).rejects.toBeInstanceOf(LocalDataError);
    expect((await store.questions()).map((q) => q.id)).toEqual([own(1).id]);
  });
});
