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
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom, forkJoin } from 'rxjs';
import { Announcer } from '../../core/announcer.service';
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import { ViewingReminderService } from '../../core/viewing-reminder.service';
import type { NotificationState } from '../../core/viewing-reminder.service';
import type { HouseDto } from '../../core/models';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { RunResult, nextRunResult } from '../../shared/run-result';
import { VIEWING_KINDS, VIEWING_STATUSES, filterViewings, groupViewings, viewingMatches } from '../../shared/viewing';
import type { Viewing, ViewingGroups, ViewingKind, ViewingStatus } from '../../shared/viewing';
import { SecondViewingPrompt } from './second-viewing-prompt';

/** One group of the timeline, in the order the screen shows them. */
interface Section {
  key: keyof ViewingGroups;
  heading: TKey;
  rows: Viewing[];
}

/** Start of the local day of a `YYYY-MM-DD` value, or null. */
function dayStart(value: string): number | null {
  const [y, m, d] = value.split('-').map(Number);
  if (!y || !m || !d) return null;
  return new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
}

/**
 * Viewings (slice 3b-1, docs/11 5.8), reached from Your data and from a house: the timeline of planned, missed, done and
 * cancelled viewings with a search box (house label, street, locality, with whom, notes) and filters (date range, kind,
 * status). A PLANNED viewing that ended more than two hours ago is shown under *Missed?* with the buttons *It happened*
 * (marks it done and offers *Book a second viewing?*) and *Cancel*; nothing writes that state by itself.
 */
@Component({
  selector: 'app-viewings-page',
  imports: [FormsModule, RouterLink, TPipe, SecondViewingPrompt],
  templateUrl: './viewings-page.html',
  styleUrl: './viewings-page.css',
})
export class ViewingsPage {
  private readonly api = inject(LocalDataService);
  private readonly announcer = inject(Announcer);
  private readonly route = inject(ActivatedRoute);
  private readonly reminders = inject(ViewingReminderService);
  protected readonly i18n = inject(TranslationService);

  /** *Notify me while Doorprints is open* (local setting \`viewings.remind\`, on unless turned off). */
  protected readonly remind = signal(true);
  protected readonly remindBusy = signal(false);
  /** The browser's permission, read at load (reading never asks) and again after the tap that asks. */
  protected readonly permission = signal<NotificationState>(this.reminders.permission());

  protected readonly loading = signal(true);
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly actionError = signal<Msg | null>(null);
  protected readonly viewings = signal<Viewing[]>([]);
  private readonly houses = signal<ReadonlyMap<string, HouseDto>>(new Map());
  /** The moment the groups are worked out for; moves on every reload. */
  private readonly now = signal(Date.now());

  /** `?houseId=` (the house page's link *All viewings of this house*): only that house's viewings. */
  protected houseId = this.route.snapshot.queryParamMap.get('houseId') ?? '';
  protected query = '';
  protected from = '';
  protected to = '';
  protected kind: ViewingKind | '' = '';
  protected status: ViewingStatus | '' = '';
  /** Bumped by the filter controls, so the list is worked out again. */
  private readonly filterRevision = signal(0);

  protected readonly kinds = VIEWING_KINDS;
  protected readonly statuses = VIEWING_STATUSES;

  /** The house the second-viewing dialog is about, or null while it is closed. */
  protected readonly promptFor = signal<{ id: string; name: string } | null>(null);

  protected readonly filtered = computed(() => {
    this.filterRevision();
    const f = filterViewings(this.viewings(), {
      from: dayStart(this.from),
      to: dayStart(this.to) === null ? null : (dayStart(this.to) as number) + 24 * 60 * 60 * 1000 - 1,
      kind: this.kind,
      status: this.status,
    });
    return f.filter((v) => this.houseId === '' || v.houseId === this.houseId).filter((v) => viewingMatches(v, this.houses().get(v.houseId), this.query));
  });

  protected readonly sections = computed<Section[]>(() => {
    const g = groupViewings(this.filtered(), this.now());
    const all: Section[] = [
      { key: 'upcoming', heading: 'viewings.group.upcoming', rows: g.upcoming },
      { key: 'missed', heading: 'viewings.group.missed', rows: g.missed },
      { key: 'done', heading: 'viewings.group.done', rows: g.done },
      { key: 'cancelled', heading: 'viewings.group.cancelled', rows: g.cancelled },
    ];
    return all.filter((s) => s.rows.length > 0);
  });

  protected readonly filtering = computed(() => {
    this.filterRevision();
    return this.houseId !== '' || this.query.trim() !== '' || this.from !== '' || this.to !== '' || this.kind !== '' || this.status !== '';
  });

  constructor() {
    // Again after writes to this browser's store (a sync pull, an edit in another tab).
    effect(() => {
      this.api.settled();
      this.reload();
    });
    this.api.viewingsRemind().subscribe({ next: (on) => this.remind.set(on), error: () => undefined });
  }

  /**
   * The switch. Turning it on is the only place the browser is asked for permission (a tap, never at load); it works
   * without it, as an in-page banner.
   */
  protected async toggleRemind(event: Event): Promise<void> {
    const on = (event.target as HTMLInputElement).checked;
    this.remindBusy.set(true);
    try {
      if (on) this.permission.set(await this.reminders.requestPermission());
      await firstValueFrom(this.api.setViewingsRemind(on));
      this.remind.set(on);
      this.actionError.set(null);
    } catch (err: unknown) {
      (event.target as HTMLInputElement).checked = !on;
      this.actionError.set(errorMsg(err));
    } finally {
      this.remindBusy.set(false);
    }
  }

  /** Reads all viewings and houses. `userAsked` marks a Retry, so a repeated failure is announced again. */
  protected reload(userAsked = false): void {
    forkJoin({ viewings: this.api.viewings(), houses: this.api.houses() }).subscribe({
      next: ({ viewings, houses }) => {
        this.houses.set(new Map(houses.map((h) => [h.id, h])));
        this.now.set(Date.now());
        this.viewings.set(viewings);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.update((previous) => nextRunResult(previous, errorMsg(err), userAsked));
        this.loading.set(false);
      },
    });
  }

  protected touchFilters(): void {
    this.filterRevision.update((n) => n + 1);
  }

  protected clearFilters(): void {
    this.houseId = '';
    this.query = '';
    this.from = '';
    this.to = '';
    this.kind = '';
    this.status = '';
    this.touchFilters();
  }

  /** The house's label, or the words for a house that is gone. */
  protected houseName(v: Viewing): string {
    const house = this.houses().get(v.houseId);
    return house ? house.label || this.i18n.t('common.untitled') : this.i18n.t('viewings.houseGone');
  }

  /** The name of the house the list is narrowed to, when it is. */
  protected filterHouseName(): string {
    const house = this.houses().get(this.houseId);
    return house ? house.label || this.i18n.t('common.untitled') : this.i18n.t('viewings.houseGone');
  }

  protected when(v: Viewing): string {
    return this.i18n.dateTime(new Date(v.startsAt).toISOString());
  }

  protected kindKey(k: ViewingKind): TKey {
    return `viewings.kind.${k}` as TKey;
  }

  protected statusKey(s: ViewingStatus): TKey {
    return `viewings.status.${s}` as TKey;
  }

  /** A note cut for the row; the full text is in the form. */
  protected preview(notes: string): string {
    return notes.length > 80 ? notes.slice(0, 77) + '…' : notes;
  }

  /** Marks a viewing done, and offers *Book a second viewing?* unless its house is gone. */
  protected async happened(v: Viewing): Promise<void> {
    try {
      await firstValueFrom(this.api.markViewingDone(v.id));
      this.actionError.set(null);
      this.announcer.announce({ key: 'viewings.markedDone' });
      this.reload();
      // A house that is gone has nothing to re-check and no form to open, so there is no prompt for it.
      if (this.houses().has(v.houseId)) this.promptFor.set({ id: v.houseId, name: this.houseName(v) });
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }

  /** Sets the viewing's status to cancelled. */
  protected async cancel(v: Viewing): Promise<void> {
    try {
      await firstValueFrom(this.api.saveViewing({ ...v, status: 'CANCELLED' }));
      this.actionError.set(null);
      this.announcer.announce({ key: 'viewings.cancelled' });
      this.reload();
    } catch (err: unknown) {
      this.actionError.set(errorMsg(err));
    }
  }
}
