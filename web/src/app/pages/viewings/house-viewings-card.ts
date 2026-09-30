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

import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom, forkJoin } from 'rxjs';
import { Announcer } from '../../core/announcer.service';
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import type { VisitDto } from '../../core/models';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { markableViewing, nextViewingOf } from '../../shared/viewing';
import type { Viewing } from '../../shared/viewing';
import { SecondViewingPrompt } from './second-viewing-prompt';

/**
 * The *Viewings* card of the house page (slice 3b-1, docs/11 5.8): the next PLANNED viewing (date and kind) or "No
 * viewing planned", *Plan a viewing* (the form with this house), a link to all the viewings of the house, and, when a
 * visit was saved within two hours of a PLANNED viewing, *Mark viewing done* (DONE, linked to that visit), which then
 * offers *Book a second viewing?*. A house that is not saved yet has no id to plan against, so it says so.
 */
@Component({
  selector: 'app-house-viewings-card',
  imports: [RouterLink, TPipe, SecondViewingPrompt],
  template: `
    <section class="card" aria-labelledby="house-viewings-heading">
      <h2 id="house-viewings-heading">{{ 'viewings.cardHeading' | t }}</h2>
      @if (isNew()) {
        <p class="muted small">{{ 'viewings.saveFirst' | t }}</p>
      } @else {
        @if (failure(); as f) {
          <p class="error small" role="alert">{{ f.key | t: f.params }}</p>
        }
        @if (next(); as v) {
          <p id="house-viewings-next">{{ 'viewings.next' | t: { when: when(v), kind: (kindKey(v) | t) } }}</p>
        } @else {
          <p id="house-viewings-next" class="muted">{{ 'viewings.none' | t }}</p>
        }
        @if (markable(); as m) {
          <p class="muted small">{{ 'viewings.markDoneHint' | t: { when: visitWhen(m.visit) } }}</p>
          <p>
            <button type="button" class="btn btn-primary" (click)="markDone(m.viewing, m.visit)">{{ 'viewings.markDone' | t }}</button>
          </p>
        }
        <div class="actions">
          <a class="btn" [routerLink]="['/viewings/new']" [queryParams]="{ houseId: houseId() }">{{ 'viewings.plan' | t }}</a>
          <a [routerLink]="['/viewings']" [queryParams]="{ houseId: houseId() }">{{ 'viewings.allOfHouse' | t }}</a>
        </div>
      }
    </section>
    <app-second-viewing-prompt [houseId]="houseId()" [houseName]="houseName()" [open]="promptOpen()" (closed)="promptOpen.set(false)" />
  `,
  styles: `
    :host {
      display: block;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-3);
    }
  `,
})
export class HouseViewingsCard {
  private readonly api = inject(LocalDataService);
  private readonly announcer = inject(Announcer);
  private readonly i18n = inject(TranslationService);

  readonly houseId = input.required<string>();
  readonly houseName = input<string>('');
  /** True while the house is not saved yet. */
  readonly isNew = input<boolean>(false);

  protected readonly viewings = signal<Viewing[]>([]);
  private readonly visits = signal<VisitDto[]>([]);
  private readonly now = signal(Date.now());
  protected readonly failure = signal<Msg | null>(null);
  protected readonly promptOpen = signal(false);

  protected readonly next = computed(() => nextViewingOf(this.viewings(), this.houseId(), this.now()));
  protected readonly markable = computed(() => markableViewing(this.viewings().filter((v) => v.houseId === this.houseId()), this.visits()));

  constructor() {
    // Again after writes to this browser's store (a sync pull, a visit saved on this page, an edit in another tab).
    effect(() => {
      this.api.settled();
      this.reload();
    });
  }

  private reload(): void {
    if (this.isNew() || this.houseId() === '') return;
    forkJoin({ viewings: this.api.viewingsOf(this.houseId()), visits: this.api.visits(this.houseId()) }).subscribe({
      next: ({ viewings, visits }) => {
        this.now.set(Date.now());
        this.viewings.set(viewings);
        this.visits.set(visits);
      },
      error: () => {
        this.viewings.set([]);
        this.visits.set([]);
      },
    });
  }

  protected when(v: Viewing): string {
    return this.i18n.dateTime(new Date(v.startsAt).toISOString());
  }

  protected visitWhen(visit: VisitDto): string {
    return this.i18n.dateTime(visit.arrivedAt);
  }

  protected kindKey(v: Viewing): TKey {
    return `viewings.kind.${v.kind}` as TKey;
  }

  protected async markDone(viewing: Viewing, visit: VisitDto): Promise<void> {
    try {
      await firstValueFrom(this.api.markViewingDone(viewing.id, visit.id));
      this.failure.set(null);
      this.announcer.announce({ key: 'viewings.markedDone' });
      this.reload();
      this.promptOpen.set(true);
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    }
  }
}
