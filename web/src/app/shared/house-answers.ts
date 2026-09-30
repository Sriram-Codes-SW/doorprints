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

import { en } from '../i18n/en';
import type { TKey } from '../i18n/en';
import { rupees } from '../export/deterministic';
import { MAX_ANSWERS, cleanAnswers } from '../data/records';
import { uuid } from '../core/models';
import type { HouseAnswer, HouseDto } from '../core/models';
import { sortQuestions } from './question';
import type { Question } from './question';

/**
 * The questions asked about one house (docs/11 5.5, slice 3a), the pure part: the order they are shown in and "Add the
 * usual questions". The twin of Kotlin `HouseAnswers` in android/shared, pinned by the same six vectors Q1..Q6
 * (`house-answers.spec.ts`, `HouseAnswersTest`), like `costSummary`/`CostSummary`.
 */

/** Words for the cost pre-fill in the app's language: `TranslationService.t`, or {@link englishSay}. */
export type Say = (key: TKey, params?: Readonly<Record<string, string | number>>) => string;

/** English from the dictionary, for callers with no translation service (the tests, the vectors). */
export const englishSay: Say = (key, params) =>
  (en[key] as string).replace(/\{(\w+)\}/g, (match, name: string) => (params && name in params ? String(params[name]) : match));

/** The house values a question depends on. */
export type AnswerHouse = Pick<HouseDto, 'priceType' | 'cost'>;

/** Open ones first, then by `sort`, then id: how the form, the copies and the AI text list them. */
export function ordered(list: readonly HouseAnswer[] | null | undefined): HouseAnswer[] {
  return [...(list ?? [])].sort(
    (a, b) =>
      Number(a.status !== 'OPEN') - Number(b.status !== 'OPEN') || a.sort - b.sort || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0),
  );
}

/** True when the question is for this kind of house: BOTH, RENT for a rent or unset price type, SALE for a sale. */
export function appliesToHouse(question: Pick<Question, 'appliesTo'>, house: Pick<HouseDto, 'priceType'>): boolean {
  if (question.appliesTo === 'BOTH') return true;
  return question.appliesTo === (house.priceType === 'SALE' ? 'SALE' : 'RENT');
}

/** The bank questions "Add the usual questions" would add now, in bank order (`sort`, then id). */
export function usualQuestions(house: AnswerHouse, bank: readonly Question[], existing: readonly HouseAnswer[]): Question[] {
  const asked = new Set(existing.map((a) => a.questionId).filter((id): id is string => typeof id === 'string'));
  return sortQuestions(bank).filter((q) => q.archived !== true && q.defaultOn && appliesToHouse(q, house) && !asked.has(q.id));
}

/** A new open answer for a bank question, or a pre-filled one when the house's cost already has the value (5.21). */
export function answerFor(question: Question, house: AnswerHouse, sort: number, say: Say = englishSay): HouseAnswer {
  const prefill = prefillFromCost(question.id, house, say);
  const base: HouseAnswer = { id: uuid(), questionId: question.id, text: question.text, status: 'OPEN', sort };
  return prefill === null ? base : { ...base, answer: prefill, status: 'ANSWERED' };
}

/**
 * `existing` plus the usual questions, each an OPEN answer with the question's text and the next `sort`; stops at 60.
 * Calling it again adds nothing, because the questions are matched by `questionId`.
 */
export function addUsual(house: AnswerHouse, bank: readonly Question[], existing: readonly HouseAnswer[], say: Say = englishSay): HouseAnswer[] {
  const out = [...existing];
  let sort = out.reduce((max, a) => Math.max(max, a.sort), -1) + 1;
  for (const question of usualQuestions(house, bank, existing)) {
    if (out.length >= MAX_ANSWERS) break;
    out.push(answerFor(question, house, sort++, say));
  }
  return out;
}

/**
 * What the cost fields already say for the four money questions, in words, or null (5.21): the deposit and the
 * brokerage in rupees and/or months, the maintenance per month with whether the rent includes it, the lock-in and the
 * notice period.
 */
export function prefillFromCost(questionId: string, house: AnswerHouse, say: Say): string | null {
  const cost = house.cost;
  if (!cost) return null;
  const money = (rupeesValue: number | null | undefined, monthsValue: number | null | undefined): string | null => {
    const r = typeof rupeesValue === 'number' ? rupees(rupeesValue) : null;
    const m = typeof monthsValue === 'number' ? say(monthsValue === 1 ? 'answer.month' : 'answer.months', { n: monthsValue }) : null;
    return r !== null && m !== null ? say('answer.both', { rupees: r, months: m }) : (r ?? m);
  };
  switch (questionId) {
    case 'qd_deposit':
      return money(cost.deposit, cost.depositMonths);
    case 'qd_brokerage':
      return money(cost.brokerage, cost.brokerageMonths);
    case 'qd_maintenance': {
      if (typeof cost.maintenance !== 'number') return null;
      const v = rupees(cost.maintenance);
      if (cost.maintenanceIncluded === true) return say('answer.maintenanceIncluded', { v });
      return say(cost.maintenanceIncluded === false ? 'answer.maintenanceExtra' : 'answer.maintenance', { v });
    }
    case 'qd_lockin': {
      const lock = typeof cost.lockInMonths === 'number' ? cost.lockInMonths : null;
      const notice = typeof cost.noticeMonths === 'number' ? cost.noticeMonths : null;
      if (lock !== null && notice !== null) return say('answer.lockInNotice', { n: lock, m: notice });
      if (lock !== null) return say('answer.lockInOnly', { n: lock });
      return notice !== null ? say('answer.noticeOnly', { m: notice }) : null;
    }
    default:
      return null;
  }
}

/** The same helpers as one object, the twin of Kotlin's `HouseAnswers`. */
export const HouseAnswers = { MAX: MAX_ANSWERS, coerced: cleanAnswers, ordered, addUsual, usualQuestions } as const;
