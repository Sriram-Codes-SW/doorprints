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

import { Component, computed, input, output, signal } from '@angular/core';
import type { KindExtra, KindFields } from '../../core/kind-fields';
import type { HouseDto } from '../../core/models';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { extraFilterMatches, filterableExtras, isRangeExtra, rangeText, type ExtraFilter } from '../../shared/extra-filter';

/**
 * The list's filter state by a kind's typed extras (S4b-BL-265): the chosen rows, the test of a candidate, and the callback
 * the page runs after a change (write the address, announce the count). Kept out of the page, which is at its size budget.
 */
export class ExtraFilterState {
  readonly value = signal<ExtraFilter>({});
  constructor(private readonly changed: () => void) {}

  set(next: ExtraFilter): void {
    this.value.set(next);
    this.changed();
  }

  matches(fields: KindFields, h: Pick<HouseDto, 'extrasOther'>): boolean {
    return extraFilterMatches(this.value(), fields, h);
  }
}

/**
 * One chip row per enum or switch of the space's kind, under the status chips: *Any* and the extra's choices, one pressed
 * per row; and, for a number or an amount (docs/03 §18.29), a pair of fields, at least and at most, each blank for no limit. Drawn
 * only for a kind that has such an extra, so a house's list shows nothing new.
 */
@Component({
  selector: 'app-extra-filters',
  imports: [TPipe],
  styles: `
    .range { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 8px; }
    .range .field { flex: 1 1 9rem; margin: 0; }
  `,
  template: `
    @for (f of extras(); track f.id) {
      @if (isRange(f)) {
        <div class="range" role="group" [attr.aria-label]="fields().label(f) | t">
          <div class="field">
            <label [for]="'xf-min-' + f.id">{{ 'map.extraMin' | t: { name: fields().label(f) | t } }}</label>
            <input [id]="'xf-min-' + f.id" #lo type="number" inputmode="decimal" [value]="end(f.id, 0)" (change)="range(f.id, lo.value, hi.value)" />
          </div>
          <div class="field">
            <label [for]="'xf-max-' + f.id">{{ 'map.extraMax' | t: { name: fields().label(f) | t } }}</label>
            <input [id]="'xf-max-' + f.id" #hi type="number" inputmode="decimal" [value]="end(f.id, 1)" (change)="range(f.id, lo.value, hi.value)" />
          </div>
        </div>
      } @else {
        <div class="chips" role="group" [attr.aria-label]="fields().label(f) | t">
          <button type="button" class="chip" [attr.aria-pressed]="!value()[f.id]" (click)="pick(f.id, null)">{{ 'map.extraAny' | t }}</button>
          @for (c of choices(f); track c.id) {
            <button type="button" class="chip" [attr.aria-pressed]="value()[f.id] === c.id" (click)="pick(f.id, c.id)">{{ c.label | t }}</button>
          }
        </div>
      }
    }
  `,
})
export class ExtraFilters {
  readonly fields = input.required<KindFields>();
  readonly value = input.required<ExtraFilter>();
  /** The whole map after a click: every filterable extra of the kind, `null` for a row on *Any*. */
  readonly changed = output<ExtraFilter>();
  protected readonly extras = computed(() => filterableExtras(this.fields()));

  protected choices(f: KindExtra): { id: string; label: TKey }[] {
    return f.type === 'bool'
      ? [{ id: 'yes', label: 'common.yes' }, { id: 'no', label: 'common.no' }]
      : (f.options ?? []).map((o) => ({ id: o.id, label: o.label }));
  }

  protected isRange(f: KindExtra): boolean {
    return isRangeExtra(f);
  }

  /** One end of a row's range as shown in its field (0: at least, 1: at most); empty for an end left out. */
  protected end(id: string, index: 0 | 1): string {
    return this.value()[id]?.split(':')[index] ?? '';
  }

  /** The two fields of a range row after a change: the new range (the ends swapped when the minimum is above the maximum), or *Any* when both are blank. */
  protected range(id: string, min: string, max: string): void {
    this.pick(id, rangeText(min, max));
  }

  protected pick(id: string, choice: string | null): void {
    const next: Record<string, string | null> = {};
    for (const f of this.extras()) next[f.id] = f.id === id ? choice : (this.value()[f.id] ?? null);
    this.changed.emit(next);
  }
}
