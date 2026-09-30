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
import { MAX_ANSWERS, cleanAnswers } from '../data/records';
import type { HouseAnswer } from '../core/models';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { DEFAULT_QUESTIONS, defaultQuestion } from './question';
import type { Question } from './question';
import { addUsual, answerFor, appliesToHouse, englishSay, ordered, prefillFromCost, usualQuestions } from './house-answers';
import seedJson from '../../../../docs/schemas/default-questions.json';

const SEED = (seedJson as { questions: { id: string; appliesTo: string; defaultOn: boolean; sort: number }[] }).questions;
const bank: Question[] = DEFAULT_QUESTIONS.map((d) => defaultQuestion(d, 'en'));

/** The ids the vectors expect, worked out from the seed file rather than typed a second time. */
function expectedIds(scope: 'RENT' | 'SALE'): string[] {
  return SEED.filter((q) => q.defaultOn && (q.appliesTo === 'BOTH' || q.appliesTo === scope))
    .sort((a, b) => a.sort - b.sort || (a.id < b.id ? -1 : 1))
    .map((q) => q.id);
}

const askedIds = (list: readonly HouseAnswer[]) => list.map((a) => a.questionId);

/**
 * "Add the usual questions" (docs/11 5.5, 5.21): the same six vectors Q1..Q6 as Kotlin `HouseAnswersTest`.
 */
describe('HouseAnswers.addUsual', () => {
  it('Q1: a rent house with the seeded bank asks exactly these 8, in bank order', () => {
    const added = addUsual({ priceType: 'RENT', cost: null }, bank, []);
    expect(askedIds(added)).toEqual(expectedIds('RENT'));
    expect(askedIds(added)).toEqual([
      'qd_maintenance',
      'qd_deposit',
      'qd_brokerage',
      'qd_lockin',
      'qd_water',
      'qd_power',
      'qd_parking',
      'qd_floor',
    ]);
    expect(added.every((a) => a.status === 'OPEN')).toBe(true);
    expect(added.map((a) => a.sort)).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
    expect(added[0].text).toBe(bank.find((q) => q.id === 'qd_maintenance')!.text);
  });

  it('Q1: a house with no price type is a rent house', () => {
    expect(askedIds(addUsual({ priceType: null, cost: null }, bank, []))).toEqual(expectedIds('RENT'));
    expect(askedIds(addUsual({ priceType: undefined, cost: null }, bank, []))).toEqual(expectedIds('RENT'));
  });

  it('Q2: a sale house gets exactly these 9', () => {
    const added = addUsual({ priceType: 'SALE', cost: null }, bank, []);
    expect(askedIds(added)).toEqual(expectedIds('SALE'));
    expect(askedIds(added)).toEqual([
      'qd_maintenance',
      'qd_brokerage',
      'qd_water',
      'qd_power',
      'qd_parking',
      'qd_floor',
      'qd_occupancy',
      'qd_rera',
      'qd_khata',
    ]);
  });

  it('Q3: calling it twice adds nothing', () => {
    const house = { priceType: 'RENT' as const, cost: null };
    const once = addUsual(house, bank, []);
    const twice = addUsual(house, bank, once);
    expect(twice).toEqual(once);
    expect(usualQuestions(house, bank, once)).toEqual([]);
  });

  it('Q3: a question asked ad hoc with the bank text is not matched, only a questionId is', () => {
    const adHoc: HouseAnswer = { id: 'x', text: bank[0].text, status: 'OPEN', sort: 0 };
    expect(askedIds(addUsual({ priceType: 'RENT', cost: null }, bank, [adHoc]))).toContain('qd_maintenance');
  });

  it('Q4: an archived or non-defaultOn question (qd_pets) is not added', () => {
    const house = { priceType: 'RENT' as const, cost: null };
    expect(askedIds(addUsual(house, bank, []))).not.toContain('qd_pets');
    const archived = bank.map((q) => (q.id === 'qd_water' ? { ...q, archived: true } : q));
    expect(askedIds(addUsual(house, archived, []))).not.toContain('qd_water');
    const custom: Question = { id: 'q_00000001', text: 'Custom', category: 'OTHER', appliesTo: 'BOTH', defaultOn: true, sort: 99 };
    expect(askedIds(addUsual(house, [...bank, custom], [])).at(-1)).toBe('q_00000001');
  });

  it('Q5: the cost pre-fill: deposit in rupees and months, maintenance not included', () => {
    const house = { priceType: 'RENT' as const, cost: { deposit: 64000, depositMonths: 2, maintenance: 2500, maintenanceIncluded: false } };
    const added = addUsual(house, bank, []);
    const deposit = added.find((a) => a.questionId === 'qd_deposit')!;
    expect(deposit.status).toBe('ANSWERED');
    expect(deposit.answer).toContain('64,000');
    expect(deposit.answer).toContain('2 months');
    const maintenance = added.find((a) => a.questionId === 'qd_maintenance')!;
    expect(maintenance.status).toBe('ANSWERED');
    expect(maintenance.answer).toContain('2,500');
    expect(maintenance.answer).toContain('not included');
    // A question the cost says nothing about stays open.
    expect(added.find((a) => a.questionId === 'qd_water')?.status).toBe('OPEN');
    expect(added.find((a) => a.questionId === 'qd_brokerage')?.answer).toBeUndefined();
  });

  it('Q6: stops at 60 answers', () => {
    const full: HouseAnswer[] = Array.from({ length: 58 }, (_, i) => ({ id: `f${i}`, text: `Q ${i}`, status: 'OPEN', sort: i }));
    const added = addUsual({ priceType: 'RENT', cost: null }, bank, full);
    expect(added).toHaveLength(MAX_ANSWERS);
    expect(askedIds(added.slice(58))).toEqual(['qd_maintenance', 'qd_deposit']);
    expect(added.map((a) => a.sort).slice(58)).toEqual([58, 59]);
    expect(addUsual({ priceType: 'RENT', cost: null }, bank, added)).toHaveLength(MAX_ANSWERS);
  });

  it('gives every added answer its own id and lets the store keep them all', () => {
    const added = addUsual({ priceType: 'RENT', cost: null }, bank, []);
    expect(new Set(added.map((a) => a.id)).size).toBe(added.length);
    expect(cleanAnswers(added)).toHaveLength(added.length);
  });
});

describe('the cost pre-fill words', () => {
  const say = englishSay;

  it('deposit and brokerage: rupees, months, or both', () => {
    expect(prefillFromCost('qd_deposit', { cost: { deposit: 64000 } }, say)).toBe('₹64,000');
    expect(prefillFromCost('qd_deposit', { cost: { depositMonths: 1 } }, say)).toBe('1 month');
    expect(prefillFromCost('qd_deposit', { cost: { deposit: 64000, depositMonths: 2 } }, say)).toBe('₹64,000, 2 months');
    expect(prefillFromCost('qd_brokerage', { cost: { brokerageMonths: 1 } }, say)).toBe('1 month');
    expect(prefillFromCost('qd_brokerage', { cost: { brokerage: 25000 } }, say)).toBe('₹25,000');
  });

  it('maintenance: per month, and whether the rent includes it', () => {
    expect(prefillFromCost('qd_maintenance', { cost: { maintenance: 2500, maintenanceIncluded: true } }, say)).toBe(
      '₹2,500 a month (included in the rent)',
    );
    expect(prefillFromCost('qd_maintenance', { cost: { maintenance: 2500, maintenanceIncluded: false } }, say)).toBe('₹2,500 a month (not included)');
    expect(prefillFromCost('qd_maintenance', { cost: { maintenance: 2500 } }, say)).toBe('₹2,500 a month');
    expect(prefillFromCost('qd_maintenance', { cost: { maintenanceIncluded: true } }, say)).toBeNull();
  });

  it('lock-in: either period may be absent', () => {
    expect(prefillFromCost('qd_lockin', { cost: { lockInMonths: 11, noticeMonths: 2 } }, say)).toBe('Lock-in 11 months, notice 2 months');
    expect(prefillFromCost('qd_lockin', { cost: { lockInMonths: 11 } }, say)).toBe('Lock-in 11 months');
    expect(prefillFromCost('qd_lockin', { cost: { noticeMonths: 2 } }, say)).toBe('Notice 2 months');
    expect(prefillFromCost('qd_lockin', { cost: {} }, say)).toBeNull();
  });

  it('nothing for other questions or no cost', () => {
    expect(prefillFromCost('qd_water', { cost: { deposit: 1 } }, say)).toBeNull();
    expect(prefillFromCost('qd_deposit', { cost: null }, say)).toBeNull();
  });

  it('is written in the language of the say function it is given', () => {
    const hindi = (key: Parameters<typeof englishSay>[0], params?: Readonly<Record<string, string | number>>) =>
      DICTIONARIES.hi[key].replace(/\{(\w+)\}/g, (_m, n: string) => String(params?.[n]));
    const answer = answerFor(bank.find((q) => q.id === 'qd_lockin')!, { cost: { lockInMonths: 11, noticeMonths: 2 } }, 0, hindi);
    expect(answer.answer).toBe('लॉक-इन 11 महीने, नोटिस 2 महीने');
    expect(answer.status).toBe('ANSWERED');
  });
});

describe('the order of the answers', () => {
  const a = (id: string, status: HouseAnswer['status'], sort: number): HouseAnswer => ({ id, text: id, status, sort });

  it('lists open ones first, then by sort, then id', () => {
    const list = [a('d', 'ANSWERED', 0), a('c', 'OPEN', 5), a('b', 'SKIPPED', 1), a('a', 'OPEN', 5), a('e', 'OPEN', 2)];
    expect(ordered(list).map((x) => x.id)).toEqual(['e', 'a', 'c', 'd', 'b']);
    expect(ordered(null)).toEqual([]);
  });

  it('does not change the list it is given', () => {
    const list = [a('b', 'ANSWERED', 0), a('a', 'OPEN', 1)];
    ordered(list);
    expect(list.map((x) => x.id)).toEqual(['b', 'a']);
  });

  it('knows which questions apply to which house', () => {
    expect(appliesToHouse({ appliesTo: 'RENT' }, { priceType: 'SALE' })).toBe(false);
    expect(appliesToHouse({ appliesTo: 'SALE' }, { priceType: 'RENT' })).toBe(false);
    expect(appliesToHouse({ appliesTo: 'SALE' }, { priceType: null })).toBe(false);
    expect(appliesToHouse({ appliesTo: 'BOTH' }, { priceType: 'SALE' })).toBe(true);
  });
});
