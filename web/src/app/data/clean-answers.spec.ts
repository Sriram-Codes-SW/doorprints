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
import type { HouseAnswer, HouseDto } from '../core/models';
import { MAX_ANSWER, MAX_ANSWER_TEXT, MAX_ANSWERS, cleanAnswers, houseFromDto } from './records';

const ok = (over: Partial<HouseAnswer> = {}): HouseAnswer => ({ id: 'a1', text: 'Water hours?', status: 'OPEN', sort: 0, ...over });

/** Answers as the store keeps them (docs/11 5.5): what a clean, capped, sorted list looks like. */
describe('cleanAnswers', () => {
  it('is null for nothing, an empty list or a list of nothing usable', () => {
    expect(cleanAnswers(null)).toBeNull();
    expect(cleanAnswers(undefined)).toBeNull();
    expect(cleanAnswers([])).toBeNull();
    expect(cleanAnswers([ok({ id: '..' })])).toBeNull();
  });

  it('keeps a good answer with its keys in the contract order', () => {
    const [a] = cleanAnswers([ok({ questionId: 'qd_water', answer: 'Twice a day', status: 'ANSWERED' })])!;
    expect(Object.keys(a)).toEqual(['id', 'questionId', 'text', 'answer', 'status', 'sort']);
    expect(Object.keys(cleanAnswers([ok()])![0])).toEqual(['id', 'text', 'status', 'sort']);
  });

  it('skips a bad id, keeps the first of a duplicate id', () => {
    const list = cleanAnswers([ok({ id: 'a b' }), ok({ id: '.' }), ok({ id: 'x', text: 'first' }), ok({ id: 'x', text: 'second' })])!;
    expect(list.map((a) => a.text)).toEqual(['first']);
  });

  it('skips a blank or over-long question text and drops an over-long answer', () => {
    expect(cleanAnswers([ok({ text: '  ' })])).toBeNull();
    expect(cleanAnswers([ok({ text: 'x'.repeat(MAX_ANSWER_TEXT + 1) })])).toBeNull();
    const [a] = cleanAnswers([ok({ answer: 'y'.repeat(MAX_ANSWER + 1), status: 'ANSWERED' })])!;
    expect(a.answer).toBeUndefined();
    expect(a.status).toBe('OPEN');
  });

  it('reads an unknown status as OPEN, a non-blank answer with OPEN as ANSWERED, ANSWERED with no answer as OPEN', () => {
    expect(cleanAnswers([ok({ status: 'DONE' as never })])![0].status).toBe('OPEN');
    expect(cleanAnswers([ok({ answer: 'Yes', status: 'OPEN' })])![0].status).toBe('ANSWERED');
    expect(cleanAnswers([ok({ answer: '   ', status: 'ANSWERED' })])![0]).toEqual(ok());
    expect(cleanAnswers([ok({ answer: null, status: 'ANSWERED' })])![0].status).toBe('OPEN');
  });

  it('leaves SKIPPED alone, with or without an answer', () => {
    expect(cleanAnswers([ok({ status: 'SKIPPED' })])![0].status).toBe('SKIPPED');
    expect(cleanAnswers([ok({ status: 'SKIPPED', answer: 'Maybe' })])![0].status).toBe('SKIPPED');
  });

  it('drops a questionId that cannot be an id, and takes 0 for a sort out of range', () => {
    expect(cleanAnswers([ok({ questionId: 'not an id' })])![0].questionId).toBeUndefined();
    expect(cleanAnswers([ok({ sort: -4 })])![0].sort).toBe(0);
  });

  it('sorts by sort then id and only then keeps the first 60', () => {
    const many = Array.from({ length: 65 }, (_, i) => ok({ id: `a${String(i).padStart(2, '0')}`, sort: 65 - i }));
    const list = cleanAnswers(many)!;
    expect(list).toHaveLength(MAX_ANSWERS);
    expect(list[0].id).toBe('a64');
    expect(list.at(-1)!.id).toBe('a05');
  });

  it('is what a stored house holds', () => {
    const dto = { id: 'h', label: 'x', lat: 0, lon: 0, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, answers: [ok({ answer: 'Yes' })] } as HouseDto;
    expect(houseFromDto(dto).answers).toEqual([ok({ answer: 'Yes', status: 'ANSWERED' })]);
    expect(houseFromDto({ ...dto, answers: [] }).answers).toBeNull();
  });
});
