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
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_QUESTIONS,
  MAX_QUESTION_TEXT,
  QUESTION_CATEGORIES,
  QUESTION_SCOPES,
  QUESTION_KINDS,
  defaultQuestion,
  defaultQuestionsOf,
  isCustomQuestionId,
  isDefaultQuestionId,
  newQuestionId,
  questionFromPayload,
  questionKindOf,
  questionToPayload,
  questionsOfKind,
  sortQuestions,
} from './question';
import { SCHOOL_QUESTIONS } from './school-questions';
import schoolSeedJson from '../../../../docs/schemas/kinds/school-questions.json';
// The one seed file both apps embed (docs/schemas, slice 3a); resolveJsonModule is on for the specs.
import seedJson from '../../../../docs/schemas/default-questions.json';

interface Seed {
  categories: string[];
  questions: {
    id: string;
    category: string;
    appliesTo: string;
    defaultOn: boolean;
    sort: number;
    text: Record<'en' | 'hi' | 'ta' | 'te', string>;
  }[];
}

/**
 * The seed of the question bank (docs/11 5.5): the list embedded in `question.ts` is the file
 * `docs/schemas/default-questions.json`, which Android's `DefaultQuestionsTest` reads too.
 */
describe('DEFAULT_QUESTIONS', () => {
  const seed = seedJson as Seed;

  it('is the docs/schemas/default-questions.json file: ids, categories, scopes, flags, order and the four texts', () => {
    expect(DEFAULT_QUESTIONS.length).toBe(seed.questions.length);
    expect(JSON.parse(JSON.stringify(DEFAULT_QUESTIONS))).toEqual(seed.questions);
  });

  it('names the same categories as the file, in the same order', () => {
    expect([...QUESTION_CATEGORIES]).toEqual(seed.categories);
  });

  it('has fixed ids of the form qd_ and a name, no duplicates, and texts within the limit', () => {
    const ids = DEFAULT_QUESTIONS.map((d) => d.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const d of DEFAULT_QUESTIONS) {
      expect(d.id).toMatch(/^qd_[a-z]+$/);
      expect(isDefaultQuestionId(d.id)).toBe(true);
      for (const lang of ['en', 'hi', 'ta', 'te'] as const) {
        expect(d.text[lang].trim(), `${d.id}/${lang}`).not.toBe('');
        expect(d.text[lang].length).toBeLessThanOrEqual(MAX_QUESTION_TEXT);
      }
    }
  });

  it('gives the text of the language asked for, and English for any other', () => {
    const water = DEFAULT_QUESTIONS.find((d) => d.id === 'qd_water')!;
    expect(defaultQuestion(water, 'ta').text).toBe(water.text.ta);
    expect(defaultQuestion(water, 'fr').text).toBe(water.text.en);
    expect(defaultQuestion(water, 'en')).toEqual({
      id: 'qd_water',
      text: water.text.en,
      category: 'WATER_POWER',
      appliesTo: 'BOTH',
      defaultOn: true,
      sort: water.sort,
    });
  });
});

describe('a question read from a record', () => {
  const good = { text: 'Is there a water meter?', category: 'WATER_POWER', appliesTo: 'RENT', defaultOn: true, sort: 4 };

  it('reads every field, and archived only when true', () => {
    expect(questionFromPayload('q_9f8e7d6c', good)).toEqual({ id: 'q_9f8e7d6c', ...good });
    expect(questionFromPayload('q_9f8e7d6c', { ...good, archived: true })?.archived).toBe(true);
    expect(questionFromPayload('q_9f8e7d6c', { ...good, archived: false })?.archived).toBeUndefined();
  });

  it('reads an unknown category as OTHER and an unknown scope as BOTH', () => {
    const q = questionFromPayload('q_9f8e7d6c', { ...good, category: 'PETS', appliesTo: 'LEASE' });
    expect(q?.category).toBe('OTHER');
    expect(q?.appliesTo).toBe('BOTH');
  });

  it('takes the default for a flag or a sort out of range', () => {
    const q = questionFromPayload('q_9f8e7d6c', { ...good, defaultOn: 'yes', sort: -3 });
    expect(q?.defaultOn).toBe(false);
    expect(q?.sort).toBe(0);
    expect(questionFromPayload('q_9f8e7d6c', { ...good, sort: 1.5 })?.sort).toBe(0);
  });

  it('skips a row with a blank or over-long text, no text at all, or an id that cannot be a record id', () => {
    expect(questionFromPayload('q_9f8e7d6c', { ...good, text: '   ' })).toBeNull();
    expect(questionFromPayload('q_9f8e7d6c', { ...good, text: 'x'.repeat(MAX_QUESTION_TEXT + 1) })).toBeNull();
    expect(questionFromPayload('q_9f8e7d6c', { category: 'MONEY' })).toBeNull();
    expect(questionFromPayload('..', good)).toBeNull();
    expect(questionFromPayload('a b', good)).toBeNull();
    expect(questionFromPayload('q_9f8e7d6c', null)).toBeNull();
  });

  it('writes the payload keys in the contract order', () => {
    const q = { id: 'q_9f8e7d6c', ...good, category: 'WATER_POWER' as const, appliesTo: 'RENT' as const, archived: true };
    expect(Object.keys(questionToPayload(q))).toEqual(['text', 'category', 'appliesTo', 'defaultOn', 'sort', 'archived']);
    expect(Object.keys(questionToPayload({ ...q, archived: undefined }))).toEqual(['text', 'category', 'appliesTo', 'defaultOn', 'sort']);
  });

  it('names the same scopes and categories the screens offer', () => {
    expect([...QUESTION_SCOPES]).toEqual(['RENT', 'SALE', 'BOTH']);
  });
});

describe('question ids and order', () => {
  it('makes a custom id of q_ and 8 lowercase hex characters', () => {
    for (let i = 0; i < 20; i++) expect(isCustomQuestionId(newQuestionId())).toBe(true);
    expect(isCustomQuestionId('q_XYZ12345')).toBe(false);
    expect(isCustomQuestionId('qd_water')).toBe(false);
  });

  it('sorts by sort, then id', () => {
    const list = [
      { id: 'b', sort: 1 },
      { id: 'c', sort: 0 },
      { id: 'a', sort: 1 },
    ];
    expect(sortQuestions(list).map((q) => q.id)).toEqual(['c', 'a', 'b']);
  });
});

/**
 * The school's question seed (kinds PR 29, docs/03 §18.23): `docs/schemas/kinds/school-questions.json`, a DRAFT for the owner's
 * review, which the phones' `DefaultQuestionsFileTest` reads too. Its ids carry the kind, so the two banks never meet.
 */
describe('SCHOOL_QUESTIONS', () => {
  const seed = schoolSeedJson as unknown as Seed & { format: string };

  it('is the docs/schemas/kinds/school-questions.json file: ids, categories, scopes, flags, order and the four texts', () => {
    expect(seed.format).toBe('doorprints-kind-questions/1');
    expect(JSON.parse(JSON.stringify(SCHOOL_QUESTIONS))).toEqual(seed.questions);
    expect(seed.questions).toHaveLength(6);
  });

  it('has ids qd_school_ and a name, categories of the bank, scope BOTH (a school has no rent or sale), and short texts', () => {
    for (const q of SCHOOL_QUESTIONS) {
      expect(q.id, q.id).toMatch(/^qd_school_[a-z]+$/);
      expect(QUESTION_CATEGORIES, q.id).toContain(q.category);
      expect(q.appliesTo, q.id).toBe('BOTH');
      for (const lang of ['en', 'hi', 'ta', 'te'] as const) {
        expect(q.text[lang].length, `${q.id} ${lang}`).toBeGreaterThan(10);
        expect(q.text[lang].length, `${q.id} ${lang}`).toBeLessThanOrEqual(MAX_QUESTION_TEXT);
      }
    }
    expect(new Set(SCHOOL_QUESTIONS.map((q) => q.id)).size).toBe(6);
  });

  it('shares no id with the house’s seed, and the kind of a question is read from its id alone', () => {
    const house = new Set(DEFAULT_QUESTIONS.map((d) => d.id));
    for (const q of SCHOOL_QUESTIONS) expect(house.has(q.id), q.id).toBe(false);
    expect(DEFAULT_QUESTIONS.every((d) => questionKindOf(d.id) === 'house')).toBe(true);
    expect(SCHOOL_QUESTIONS.every((d) => questionKindOf(d.id) === 'school')).toBe(true);
    expect(questionKindOf('q_0123abcd')).toBe('house');
    expect(questionKindOf('q_school_0123abcd')).toBe('school');
    // The house’s own ids never start with a kind: nothing like qd_school is a house’s default.
    expect(QUESTION_KINDS).toEqual(['school']);
  });

  it('is the seed of its kind, a default for the delete and reset rules, and a school’s own question is custom', () => {
    expect(defaultQuestionsOf('school')).toBe(SCHOOL_QUESTIONS);
    expect(defaultQuestionsOf('house')).toBe(DEFAULT_QUESTIONS);
    expect(defaultQuestionsOf('villa')).toEqual([]);
    expect(SCHOOL_QUESTIONS.every((q) => isDefaultQuestionId(q.id))).toBe(true);
    expect(isCustomQuestionId('q_school_0123abcd')).toBe(true);
    expect(isCustomQuestionId('q_school_0123abc')).toBe(false);
    expect(isCustomQuestionId('qd_school_fees')).toBe(false);
    expect(newQuestionId('school')).toMatch(/^q_school_[0-9a-f]{8}$/);
    expect(newQuestionId()).toMatch(/^q_[0-9a-f]{8}$/);
  });

  it('keeps one kind’s questions when a bank is filtered', () => {
    const all = [...DEFAULT_QUESTIONS.slice(0, 2), ...SCHOOL_QUESTIONS.slice(0, 2)];
    expect(questionsOfKind(all, 'school').map((q) => q.id)).toEqual(SCHOOL_QUESTIONS.slice(0, 2).map((q) => q.id));
    expect(questionsOfKind(all, 'house').map((q) => q.id)).toEqual(DEFAULT_QUESTIONS.slice(0, 2).map((q) => q.id));
  });
});
