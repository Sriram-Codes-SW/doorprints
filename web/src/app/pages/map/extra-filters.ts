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
import { extraFilterMatches, filterableExtras, type ExtraFilter } from '../../shared/extra-filter';

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
 * per row. Drawn only for a kind that has such an extra, so a house's list shows nothing new.
 */
@Component({
  selector: 'app-extra-filters',
  imports: [TPipe],
  template: `
    @for (f of extras(); track f.id) {
      <div class="chips" role="group" [attr.aria-label]="fields().label(f) | t">
        <button type="button" class="chip" [attr.aria-pressed]="!value()[f.id]" (click)="pick(f.id, null)">{{ 'map.extraAny' | t }}</button>
        @for (c of choices(f); track c.id) {
          <button type="button" class="chip" [attr.aria-pressed]="value()[f.id] === c.id" (click)="pick(f.id, c.id)">{{ c.label | t }}</button>
        }
      </div>
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

  protected pick(id: string, choice: string | null): void {
    const next: Record<string, string | null> = {};
    for (const f of this.extras()) next[f.id] = f.id === id ? choice : (this.value()[f.id] ?? null);
    this.changed.emit(next);
  }
}
