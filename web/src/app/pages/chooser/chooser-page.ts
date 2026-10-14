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
import { Router, RouterLink } from '@angular/router';
import { kindById } from '../../core/kind-setting';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { SpaceService, spaceOf } from '../../core/space.service';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import { STORAGE_PREFIX } from '../../core/storage-keys';

/** The chooser's one hint has been dismissed ('done'), in this browser. */
export const HINT_KEY = `${STORAGE_PREFIX}chooser.hint`;

function readHint(): boolean {
  try {
    return localStorage.getItem(HINT_KEY) !== 'done';
  } catch {
    return false;
  }
}

/** One card of the chooser: a kind, how many of its candidates are saved and when the newest changed. */
export interface SpaceCard {
  id: string;
  /** The kind's list noun (the kind file's `kind.many`: *Houses*, *Schools*). */
  label: string;
  count: number;
  /** The newest `updatedAt` of its candidates, epoch ms; null when it has none. */
  updated: number | null;
  isDefault: boolean;
}

/**
 * Groups the live candidates by space: one card per enabled kind (the default first, then the built-in order), then one
 * per kind that is turned off but still has entries (`hidden`), so a kind with data is never hidden silently.
 */
export function spaceCards(
  houses: readonly HouseDto[],
  enabled: readonly string[],
  dflt: string,
  available: readonly string[],
  label: (id: string) => string,
): { shown: SpaceCard[]; hidden: SpaceCard[] } {
  const tally = new Map<string, { count: number; updated: number | null }>();
  for (const h of houses) {
    if (h.deleted) continue;
    const id = spaceOf(h, true, dflt, available);
    const t = tally.get(id) ?? { count: 0, updated: null };
    const at = Date.parse(h.updatedAt ?? '');
    t.count++;
    if (Number.isFinite(at) && (t.updated === null || at > t.updated)) t.updated = at;
    tally.set(id, t);
  }
  const card = (id: string): SpaceCard => ({ id, label: label(id), count: tally.get(id)?.count ?? 0, updated: tally.get(id)?.updated ?? null, isDefault: id === dflt });
  const order = [dflt, ...enabled.filter((id) => id !== dflt)];
  const hidden = available.filter((id) => !enabled.includes(id) && (tally.get(id)?.count ?? 0) > 0);
  return { shown: order.map(card), hidden: hidden.map(card) };
}

/**
 * The chooser (kinds PR 8, S4b-BL-212; docs/03 §18.12): the first screen while two or more kinds are enabled. One
 * button per enabled kind, the default first and marked, each with its count and its last change in its name; the
 * kinds turned off that still have entries below, muted (they still open). Choosing one opens its space; the chooser is
 * one history entry, so Back from a space's first page comes here. With one kind enabled the app never shows it.
 */
@Component({
  selector: 'app-chooser-page',
  imports: [TPipe, RouterLink],
  template: `
    <section class="chooser" aria-labelledby="chooser-heading">
      <h1 id="chooser-heading" tabindex="-1">{{ 'chooser.heading' | t }}</h1>
      <!-- Once, when a second kind is first shown (S4b-BL-212): how the chooser works; no second tour. -->
      @if (hint()) {
        <p class="hint" role="note">
          <span>{{ 'chooser.hint' | t }}</span>
          <button type="button" class="btn btn-sm" (click)="dismissHint()">{{ 'chooser.hintDismiss' | t }}</button>
        </p>
      }
      <ul class="cards" role="list">
        @for (c of cards().shown; track c.id) {
          <li>
            <!-- The visible words are the name: the kind, Default, the count and the last change (label in name). -->
            <button type="button" class="space-card" [id]="'space-' + c.id" (click)="open(c.id)">
              <span class="name">{{ c.label }}</span>
              @if (c.isDefault) { &ngsp;<span class="badge">{{ 'chooser.default' | t }}</span> }
              @if (c.count > 0) {
                &ngsp;<span class="meta">{{ 'chooser.count' | t: { n: i18n.number(c.count) } }}</span>
                @if (c.updated !== null) { &ngsp;<span class="meta">{{ 'chooser.updated' | t: { date: i18n.dateOnly(c.updated) } }}</span> }
              } @else {
                &ngsp;<span class="meta muted">{{ 'chooser.none' | t }}</span>
              }
            </button>
          </li>
        }
      </ul>
      @if (cards().hidden.length) {
        <h2 class="hidden-heading">{{ 'chooser.hiddenHeading' | t }}</h2>
        <ul class="cards" role="list">
          @for (c of cards().hidden; track c.id) {
            <li>
              <button type="button" class="space-card muted-card" [id]="'space-' + c.id" (click)="open(c.id)">
                <span class="name">{{ c.label }}</span>
                &ngsp;<span class="meta">{{ 'chooser.hidden' | t: { n: i18n.number(c.count) } }}</span>
              </button>
            </li>
          }
        </ul>
      }
      <p class="manage"><a routerLink="/data" fragment="kinds-heading">{{ 'chooser.manage' | t }}</a></p>
    </section>
  `,
  styles: `
    .chooser { max-width: 40rem; margin: 0 auto; padding: var(--space-4) var(--space-3); }
    h1 { font-size: var(--text-xl); margin: 0 0 var(--space-4); }
    .cards { list-style: none; margin: 0; padding: 0; display: grid; gap: var(--space-3); }
    .space-card {
      width: 100%; min-height: var(--target); display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--space-1) var(--space-3);
      padding: var(--space-3) var(--space-4); text-align: start; font: inherit; color: var(--text);
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); cursor: pointer;
    }
    .space-card:hover { border-color: var(--primary); }
    .space-card:focus-visible { outline: 3px solid var(--focus); outline-offset: 2px; }
    .name { font-size: var(--text-lg); font-weight: 700; flex: 1 1 auto; min-width: 0; overflow-wrap: anywhere; }
    .badge { font-size: var(--text-sm); font-weight: 600; color: var(--primary); border: 1px solid var(--primary); border-radius: var(--radius-pill); padding: 0 var(--space-2); }
    .meta { flex-basis: 100%; font-size: var(--text-sm); color: var(--muted); }
    .muted-card { background: transparent; color: var(--muted); }
    .hidden-heading { font-size: var(--text-md); margin: var(--space-5) 0 var(--space-2); color: var(--muted); }
    .manage { margin-top: var(--space-5); }
    .hint { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2) var(--space-3); margin: 0 0 var(--space-4); padding: var(--space-3); background: var(--primary-soft); border-radius: var(--radius); }
    .hint span { flex: 1 1 12rem; min-width: 0; }
  `,
})
export class ChooserPage {
  private readonly data = inject(LocalDataService);
  private readonly router = inject(Router);
  protected readonly space = inject(SpaceService);
  protected readonly i18n = inject(TranslationService);
  private readonly houses = signal<HouseDto[]>([]);
  /** The one in-place hint, until it is dismissed (this browser's own). */
  protected readonly hint = signal(readHint());

  protected readonly cards = computed(() =>
    spaceCards(this.houses(), this.space.enabled(), this.space.dflt(), this.space.available, (id) => this.i18n.t(kindById(id).keys.ref('kind.many'))),
  );

  constructor() {
    // Read again whenever the store settles (a sync pull, an import), as the map does.
    effect(() => {
      this.data.settled();
      this.data.houses().subscribe({ next: (list) => this.houses.set(list), error: () => this.houses.set([]) });
    });
  }

  protected dismissHint(): void {
    this.hint.set(false);
    try {
      localStorage.setItem(HINT_KEY, 'done');
    } catch {
      // Storage blocked: the hint comes back next time, which is harmless.
    }
  }

  protected open(id: string): void {
    void this.router.navigateByUrl(this.space.home(id));
  }
}
