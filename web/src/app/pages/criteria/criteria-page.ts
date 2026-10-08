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
import { criterionName } from '../../shared/criterion-name';
import { RunResult, nextRunResult } from '../../shared/run-result';
import { DEFAULT_SCORING, MAX_CRITERIA, MAX_CRITERION_LABEL, WEIGHTS, isBuiltInKey } from '../../shared/scoring';
import type { Criterion, Scoring, Weight } from '../../shared/scoring';

/** The rating shares the selector offers, in percent (the default is 50). */
const SHARE_PERCENTS: readonly number[] = [0, 25, 50, 75, 100];

/**
 * Criteria (slice 2, docs/11 5.4), reached from Your data: what matters in a house. Each criterion has a weight
 * (Ignore, Low, Medium, High), can be a must-have with a minimum score, can be moved up or down, archived and
 * restored; custom criteria are added by name (at most 40 in all). The Rating share sets how much the star rating
 * counts against the checklist, and Reset to defaults removes every choice. Every change is saved at once, and only
 * what differs from the defaults is stored (`LocalStore.saveCriterion`).
 */
@Component({
  selector: 'app-criteria-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './criteria-page.html',
  styleUrl: './criteria-page.css',
})
export class CriteriaPage {
  private readonly api = inject(LocalDataService);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);
  private readonly i18n = inject(TranslationService);

  protected readonly loading = signal(true);
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly scoring = signal<Scoring>(DEFAULT_SCORING);
  /** Keys with a score on some live house: a custom criterion in this set can only be archived. */
  private readonly usedKeys = signal<ReadonlySet<string>>(new Set());
  protected newName = '';
  protected readonly nameError = signal(false);
  /** A refused add or delete (the cap, a criterion a house scored), shown next to the control. */
  protected readonly actionError = signal<Msg | null>(null);

  protected readonly weights = WEIGHTS;
  protected readonly weightKey: Readonly<Record<Weight, TKey>> = {
    0: 'criteria.weight.0',
    1: 'criteria.weight.1',
    2: 'criteria.weight.2',
    3: 'criteria.weight.3',
  };
  protected readonly minScores: readonly number[] = [1, 2, 3, 4, 5];
  protected readonly maxLabel = MAX_CRITERION_LABEL;
  protected readonly maxCriteria = MAX_CRITERIA;

  /** The criteria in the person's order, archived ones apart. */
  protected readonly active = computed(() => this.scoring().criteria.filter((c) => c.archived !== true));
  protected readonly archived = computed(() => this.scoring().criteria.filter((c) => c.archived === true));
  /** At the cap of 40, counting the ten built-ins and the archived ones. */
  protected readonly atCap = computed(() => this.scoring().criteria.length >= MAX_CRITERIA);
  /** The rating share in percent as the selector offers it: the five steps, and the stored value if it is another. */
  protected readonly sharePercent = computed(() => Math.round(this.scoring().ratingShare * 100));
  protected readonly shareOptions = computed(() => {
    const now = this.sharePercent();
    return SHARE_PERCENTS.includes(now) ? SHARE_PERCENTS : [...SHARE_PERCENTS, now].sort((a, b) => a - b);
  });

  constructor() {
    // Again after writes to this browser's store (a sync pull, an edit in another tab).
    effect(() => {
      this.api.settled();
      this.reload();
    });
  }

  /**
   * Reads the scoring (criteria and rating share) and which criteria any house has scored. `userAsked` marks a Retry,
   * so a repeated failure is announced again.
   */
  protected reload(userAsked = false): void {
    this.api.scoring().subscribe({
      next: (scoring) => {
        this.scoring.set(scoring);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.update((previous) => nextRunResult(previous, errorMsg(err), userAsked));
        this.loading.set(false);
      },
    });
    this.api.houses().subscribe({
      next: (list) => {
        const used = new Set<string>();
        for (const h of list) for (const [key, value] of Object.entries(h.checklist ?? {})) if (typeof value === 'number') used.add(key);
        this.usedKeys.set(used);
      },
      error: () => this.usedKeys.set(new Set()),
    });
  }

  protected nameOf(c: Criterion): string {
    return criterionName(c, (key) => this.i18n.t(key));
  }

  protected isCustom(c: Criterion): boolean {
    return !isBuiltInKey(c.key);
  }

  /** A custom criterion no house has scored can be deleted; the others can only be archived. */
  protected canDelete(c: Criterion): boolean {
    return this.isCustom(c) && !this.usedKeys().has(c.key);
  }

  /** Saves a new weight (0 to 3) for the criterion; weight 0 means ignored in the score. */
  protected setWeight(c: Criterion, event: Event): void {
    void this.save([{ ...c, weight: Number((event.target as HTMLSelectElement).value) as Weight }]);
  }

  /** Saves whether the criterion is a must-have; a house that scores below its minimum sorts after the rest. */
  protected setMustHave(c: Criterion, event: Event): void {
    void this.save([{ ...c, mustHave: (event.target as HTMLInputElement).checked }]);
  }

  /** Saves the lowest score (1 to 5) a must-have accepts. */
  protected setMinScore(c: Criterion, event: Event): void {
    void this.save([{ ...c, minScore: Number((event.target as HTMLSelectElement).value) }]);
  }

  /** Moves a criterion one place among the visible ones and renumbers them 0..n; only the rows that changed are saved. */
  protected move(c: Criterion, by: -1 | 1): void {
    const list = [...this.active()];
    const from = list.findIndex((x) => x.key === c.key);
    const to = from + by;
    if (from < 0 || to < 0 || to >= list.length) return;
    [list[from], list[to]] = [list[to], list[from]];
    // Archived criteria keep their own numbers; the visible ones take 0..n-1 in the new order.
    const changed = list.map((x, i) => ({ ...x, sort: i })).filter((x, i) => list[i].sort !== x.sort);
    void this.save(changed, { key: 'criteria.moveDone', params: { name: this.nameOf(c), n: to + 1, total: list.length } });
  }

  /** Hides the criterion from the checklist; scores already given stay on the houses. */
  protected archive(c: Criterion): void {
    void this.save([{ ...c, archived: true }]);
  }

  /** Brings an archived criterion back at the end of the visible list. */
  protected restore(c: Criterion): void {
    // Back at the end of the visible list, so it does not land among criteria that were renumbered meanwhile.
    const last = this.active().reduce((max, x) => Math.max(max, x.sort), -1);
    void this.save([{ ...c, archived: false, sort: last + 1 }]);
  }

  /** Deletes a custom criterion no house has scored, after asking. */
  protected async remove(c: Criterion): Promise<void> {
    const ok = await this.confirm.ask(
      { key: 'criteria.confirmDelete', params: { name: this.nameOf(c) } },
      { confirmKey: 'criteria.delete', danger: true },
    );
    if (!ok) return;
    try {
      await firstValueFrom(this.api.deleteCriterion(c.key));
      this.actionError.set(null);
      this.announcer.announce({ key: 'criteria.updated' });
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }

  /** Adds a custom criterion with the typed name, unless the name is empty or the limit is reached. */
  protected async add(): Promise<void> {
    const name = this.newName.trim();
    if (name === '') {
      this.nameError.set(true);
      document.getElementById('criteria-new')?.focus();
      return;
    }
    if (this.atCap()) return;
    this.nameError.set(false);
    try {
      await firstValueFrom(this.api.addCriterion(name));
      this.newName = '';
      this.actionError.set(null);
      this.announcer.announce({ key: 'criteria.updated' });
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }

  /** Saves how much of the score comes from the star rating, as a percentage of the whole. */
  protected async setShare(event: Event): Promise<void> {
    const percent = Number((event.target as HTMLSelectElement).value);
    try {
      await firstValueFrom(this.api.setRatingShare(percent / 100));
      this.actionError.set(null);
      this.announcer.announce({ key: 'criteria.updated' });
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }

  /** Restores the default criteria after asking. */
  protected async reset(): Promise<void> {
    const ok = await this.confirm.ask({ key: 'criteria.confirmReset' }, { confirmKey: 'criteria.reset', danger: true });
    if (!ok) return;
    try {
      await firstValueFrom(this.api.resetCriteria());
      this.actionError.set(null);
      this.announcer.announce({ key: 'criteria.resetDone' });
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }

  /** Saves the changed criteria, announces it and reads the page again; a failure is shown above the list. */
  private async save(list: readonly Criterion[], announcement: Msg = { key: 'criteria.updated' }): Promise<void> {
    if (list.length === 0) return;
    try {
      await firstValueFrom(this.api.saveCriteria(list));
      this.actionError.set(null);
      this.announcer.announce(announcement);
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }
}
