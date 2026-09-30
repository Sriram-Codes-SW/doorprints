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

import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { MAX_QUESTIONS, MAX_QUESTION_TEXT, QUESTION_CATEGORIES, QUESTION_SCOPES, sortQuestions } from '../../shared/question';
import type { Question, QuestionCategory, QuestionScope } from '../../shared/question';
import { RunResult, nextRunResult } from '../../shared/run-result';

/** One category of the bank, in the order the screen shows them. */
interface Group {
  category: QuestionCategory;
  questions: Question[];
}

/**
 * Questions (slice 3a, docs/11 5.5), reached from Your data: the bank of questions to ask at a viewing, grouped by
 * category. Each question can be edited, given a category and a scope (Rent, Buy, Both), switched to "Ask by default",
 * moved, archived and brought back, or deleted (a seeded one too: it stays deleted until Reset to defaults). Every change
 * is saved at once and writes only the record that changed (`LocalStore.saveQuestion`).
 */
@Component({
  selector: 'app-questions-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './questions-page.html',
  styleUrl: './questions-page.css',
})
export class QuestionsPage {
  private readonly api = inject(LocalDataService);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);
  private readonly i18n = inject(TranslationService);

  protected readonly loading = signal(true);
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly bank = signal<Question[]>([]);
  protected newText = '';
  protected newCategory: QuestionCategory = 'OTHER';
  protected readonly textError = signal(false);
  /** A refused add or save (the cap, a bad text), shown next to the control. */
  protected readonly actionError = signal<Msg | null>(null);

  protected readonly categories = QUESTION_CATEGORIES;
  protected readonly scopes = QUESTION_SCOPES;
  protected readonly maxText = MAX_QUESTION_TEXT;
  protected readonly maxQuestions = MAX_QUESTIONS;

  /** The visible questions by category, each group in the person's order; empty categories are left out. */
  protected readonly groups = computed<Group[]>(() =>
    QUESTION_CATEGORIES.map((category) => ({
      category,
      questions: this.bank().filter((q) => q.archived !== true && q.category === category),
    })).filter((g) => g.questions.length > 0),
  );
  protected readonly archived = computed(() => this.bank().filter((q) => q.archived === true));
  /** At the cap of 100, archived questions included. */
  protected readonly atCap = computed(() => this.bank().length >= MAX_QUESTIONS);

  constructor() {
    // Again after writes to this browser's store (a sync pull, an edit in another tab).
    effect(() => {
      this.api.settled();
      this.reload();
    });
  }

  protected reload(userAsked = false): void {
    this.api.questions().subscribe({
      next: (list) => {
        this.bank.set(list);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.update((previous) => nextRunResult(previous, errorMsg(err), userAsked));
        this.loading.set(false);
      },
    });
  }

  protected categoryKey(category: QuestionCategory): TKey {
    return `questions.category.${category}` as TKey;
  }

  protected scopeKey(scope: QuestionScope): TKey {
    return `questions.scope.${scope}` as TKey;
  }

  /** The question's own words name its buttons; a long one is cut so a screen reader is not read a paragraph. */
  protected nameOf(q: Question): string {
    return q.text.length > 60 ? q.text.slice(0, 57) + '…' : q.text;
  }

  protected editText(q: Question, event: Event): void {
    const input = event.target as HTMLInputElement;
    const text = input.value.trim();
    if (text === q.text) return;
    if (text === '') {
      // A blank text is refused: put the old one back rather than deleting the question by accident.
      input.value = q.text;
      this.actionError.set({ key: 'questions.textRequired' });
      return;
    }
    void this.save([{ ...q, text }]);
  }

  protected setCategory(q: Question, event: Event): void {
    void this.save([{ ...q, category: (event.target as HTMLSelectElement).value as QuestionCategory }]);
  }

  protected setScope(q: Question, event: Event): void {
    void this.save([{ ...q, appliesTo: (event.target as HTMLSelectElement).value as QuestionScope }]);
  }

  protected setDefaultOn(q: Question, event: Event): void {
    void this.save([{ ...q, defaultOn: (event.target as HTMLInputElement).checked }]);
  }

  /**
   * Moves a question one place among those of its category and renumbers all the visible ones 0..n, so the order is
   * the same on every screen that lists the bank; only the rows whose number changed are saved.
   */
  protected move(q: Question, by: -1 | 1): void {
    const all = sortQuestions(this.bank().filter((x) => x.archived !== true));
    const from = all.findIndex((x) => x.id === q.id);
    let to = from + by;
    while (to >= 0 && to < all.length && all[to].category !== q.category) to += by;
    if (from < 0 || to < 0 || to >= all.length) return;
    [all[from], all[to]] = [all[to], all[from]];
    const changed = all.map((x, i) => ({ ...x, sort: i })).filter((x, i) => all[i].sort !== x.sort);
    const inGroup = all.filter((x) => x.category === q.category);
    void this.save(changed, {
      key: 'questions.moveDone',
      params: { n: inGroup.findIndex((x) => x.id === q.id) + 1, total: inGroup.length },
    });
  }

  protected archive(q: Question): void {
    void this.save([{ ...q, archived: true }]);
  }

  protected bringBack(q: Question): void {
    // Back at the end of the visible list, so it does not land among questions that were renumbered meanwhile.
    const last = this.bank()
      .filter((x) => x.archived !== true)
      .reduce((max, x) => Math.max(max, x.sort), -1);
    void this.save([{ ...q, archived: false, sort: last + 1 }]);
  }

  protected async remove(q: Question): Promise<void> {
    const ok = await this.confirm.ask(
      { key: 'questions.confirmDelete', params: { name: this.nameOf(q) } },
      { confirmKey: 'questions.delete', danger: true },
    );
    if (!ok) return;
    try {
      await firstValueFrom(this.api.deleteQuestion(q.id));
      this.actionError.set(null);
      this.announcer.announce({ key: 'questions.updated' });
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }

  protected async add(): Promise<void> {
    const text = this.newText.trim();
    if (text === '') {
      this.textError.set(true);
      document.getElementById('questions-new')?.focus();
      return;
    }
    if (this.atCap()) return;
    this.textError.set(false);
    try {
      await firstValueFrom(this.api.addQuestion(text, this.newCategory));
      this.newText = '';
      this.actionError.set(null);
      this.announcer.announce({ key: 'questions.updated' });
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }

  protected async reset(): Promise<void> {
    const ok = await this.confirm.ask({ key: 'questions.confirmReset' }, { confirmKey: 'questions.reset', danger: true });
    if (!ok) return;
    try {
      await firstValueFrom(this.api.resetQuestions(this.i18n.lang()));
      this.actionError.set(null);
      this.announcer.announce({ key: 'questions.resetDone' });
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }

  private async save(list: readonly Question[], announcement: Msg = { key: 'questions.updated' }): Promise<void> {
    if (list.length === 0) return;
    try {
      await firstValueFrom(this.api.saveQuestions(list));
      this.actionError.set(null);
      this.announcer.announce(announcement);
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }
}
