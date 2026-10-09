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

// The viewing-question bank of the browser's store (docs/11 5.5, slice 3a): records of type `question`, read as typed
// rows, the saves that refuse what the server would refuse (a bad id, a blank or long text, the 101st question), and the
// seeding of the standard questions (once per install, and again on *Reset to defaults*). It is separate from LocalStore
// (S4b-BL-168) because the questions page, the house's answers, the export, the import and the app start each reach
// the bank; it keeps no rows of its own and goes through RecordStore, except the seeding, which writes the rows with
// their own edit times and so needs the database and the revision directly.
import { LocalDataError } from '../core/local-error';
import type { LocalDb } from './local-db';
import { SETTING_KEYS, isoNow, recordFromDto } from './records';
import type { RecordRecord, SettingRecord } from './records';
import type { RecordStore } from './record-store';
import {
  DEFAULT_QUESTIONS,
  DEFAULT_QUESTIONS_SEEDED_AT,
  MAX_QUESTIONS,
  MAX_QUESTION_TEXT,
  QUESTION_TYPE,
  defaultQuestion,
  isCustomQuestionId,
  isDefaultQuestionId,
  newQuestionId,
  questionFromPayload,
  questionToPayload,
  sortQuestions,
} from '../shared/question';
import type { Question, QuestionCategory, QuestionRow, QuestionScope } from '../shared/question';

export class QuestionStore {
  /** The language the bank is seeded in, once {@link seedOnce} has been called (also after *Remove all data*). */
  private seedLanguage: (() => string) | null = null;

  /**
   * @param records the record rows every question goes through
   * @param database the opened database, once the store is ready (for the seeding and its setting)
   * @param changed called after the seeding writes, so views and the sync engine see a new revision
   */
  constructor(
    private readonly records: RecordStore,
    private readonly database: () => Promise<LocalDb>,
    private readonly changed: () => void,
  ) {}

  /** The live question records, oldest edit first; a row whose payload is not a question (a blank text) is skipped. */
  async rows(): Promise<QuestionRow[]> {
    const out: QuestionRow[] = [];
    for (const row of await this.records.ofType(QUESTION_TYPE)) {
      const question = questionFromPayload(row.id, row.payload);
      if (question) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, question });
    }
    return out;
  }

  /** The bank, archived questions included, by `sort` then id. */
  async all(): Promise<Question[]> {
    return sortQuestions((await this.rows()).map((r) => r.question));
  }

  /**
   * Seeds the bank: for each default whose id has NO record a clean record stamped {@link DEFAULT_QUESTIONS_SEEDED_AT}
   * with the text in [language] (hi, ta or te; anything else English; S4b-BL-90a). A tombstone counts as a record, so
   * a default the person deleted is not brought back.
   * Returns how many were written.
   */
  async seed(language: string, now: number = Date.now()): Promise<number> {
    return this.writeDefaults(await this.database(), language, now, false);
  }

  /**
   * Seeds once per install: the first call sets the local setting `questions.seeded` (not synced), so later starts do
   * nothing and a bank the person emptied stays empty until *Reset to defaults*. `language` is read at each use
   * (the language the app is in then), also after *Remove all data* seeded the bank again.
   */
  async seedOnce(language: () => string, now: number = Date.now()): Promise<void> {
    this.seedLanguage = language;
    const db = await this.database();
    if (await db.get<SettingRecord>('settings', SETTING_KEYS.questionsSeeded)) return;
    await this.writeDefaults(db, language(), now, false);
    await db.put<SettingRecord>('settings', { key: SETTING_KEYS.questionsSeeded, value: '1' });
  }

  /** After *Remove all data*: an empty browser still starts with the standard questions, if seeding was asked for. */
  async seedAgainIfAsked(): Promise<void> {
    if (this.seedLanguage) await this.seedOnce(this.seedLanguage);
  }

  /**
   * *Reset to defaults*: every default id gets its record again, whatever state it was in (deleted, edited, archived),
   * with the text in [language]; the person's own questions stay. A default that would take the bank past
   * {@link MAX_QUESTIONS} is not brought back.
   */
  async reset(language: string, now: number = Date.now()): Promise<number> {
    return this.writeDefaults(await this.database(), language, now, true);
  }

  private async writeDefaults(db: LocalDb, language: string, now: number, overwrite: boolean): Promise<number> {
    const rows = await db.getAllByIndex<RecordRecord>('records', 'type', QUESTION_TYPE);
    const byId = new Map(rows.map((r) => [r.id, r]));
    let live = rows.filter((r) => !r.deleted).length;
    let written = 0;
    for (const def of DEFAULT_QUESTIONS) {
      const existing = byId.get(def.id);
      if (existing && !overwrite) continue;
      if ((!existing || existing.deleted) && live >= MAX_QUESTIONS) continue;
      if (!existing || existing.deleted) live += 1;
      const record = recordFromDto(
        {
          type: QUESTION_TYPE,
          id: def.id,
          payload: questionToPayload(defaultQuestion(def, language)),
          // A seed is clean and stamped DEFAULT_QUESTIONS_SEEDED_AT (S4b-BL-90a): never pushed, and whatever another
          // device did to it wins when pulled. *Reset to defaults* is the person's own edit: now, dirty.
          updatedAt: isoNow(overwrite ? now : DEFAULT_QUESTIONS_SEEDED_AT),
          deleted: false,
          syncVersion: existing?.syncVersion ?? 0,
        },
        overwrite,
      );
      await db.put('records', record);
      written += 1;
    }
    if (written > 0) this.changed();
    return written;
  }

  /**
   * Saves a question (an edit, an archive, a move): only that record is written. A default keeps its fixed id.
   *
   * @throws LocalDataError `error.badRecord` for an id that is neither a default's nor `q_` and 8 hex characters, or a
   *   blank or over-long text; `questions.max` when a new question would be the 101st.
   */
  async save(question: Question, now: number = Date.now()): Promise<void> {
    if (!isDefaultQuestionId(question.id) && !isCustomQuestionId(question.id)) throw new LocalDataError('error.badRecord');
    const text = question.text.trim();
    if (text === '' || text.length > MAX_QUESTION_TEXT) throw new LocalDataError('error.badRecord');
    if (!(await this.records.get(QUESTION_TYPE, question.id)) && (await this.records.ofType(QUESTION_TYPE)).length >= MAX_QUESTIONS) {
      throw new LocalDataError('questions.max');
    }
    await this.records.save(QUESTION_TYPE, question.id, questionToPayload({ ...question, text }), now);
  }

  /** Saves several questions (a move renumbers two or more): each as {@link save}. */
  async saveMany(list: readonly Question[], now: number = Date.now()): Promise<void> {
    for (const question of list) await this.save(question, now);
  }

  /**
   * Adds a custom question at the end of the bank: a new id `q_` and 8 lowercase hex characters (an id that clashes with
   * any record, a deleted one included, is drawn again). `newId` is a seam for tests.
   *
   * @throws LocalDataError `error.badRecord` for a blank or over-long text; `questions.max` at 100 questions.
   */
  async add(
    text: string,
    category: QuestionCategory = 'OTHER',
    appliesTo: QuestionScope = 'BOTH',
    now: number = Date.now(),
    newId: () => string = newQuestionId,
  ): Promise<Question> {
    const asked = text.trim();
    if (asked === '' || asked.length > MAX_QUESTION_TEXT) throw new LocalDataError('error.badRecord');
    if ((await this.records.ofType(QUESTION_TYPE)).length >= MAX_QUESTIONS) throw new LocalDataError('questions.max');
    const id = await this.records.freshId(QUESTION_TYPE, newId);
    const sort = (await this.all()).reduce((max, q) => Math.max(max, q.sort), -1) + 1;
    const question: Question = { id, text: asked, category, appliesTo, defaultOn: false, sort };
    await this.save(question, now);
    return question;
  }

  /** Deletes a question, a seeded one too (a tombstone): a deleted default stays deleted until *Reset to defaults*. */
  async delete(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(QUESTION_TYPE, id, now);
  }
}
