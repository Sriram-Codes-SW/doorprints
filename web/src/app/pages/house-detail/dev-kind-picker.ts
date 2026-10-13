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

import { Component, input, output } from '@angular/core';
import type { BuiltInKind } from '../../core/dev-kinds';
import { TPipe } from '../../i18n/t.pipe';

/**
 * The developer's kind picker on a new candidate's page (kinds PR 5, S4b-BL-209): *New house* or *New school*, each
 * the kind's own `kind.new` label. Drawn only while the developer flag is on (`core/dev-kinds.ts`), so no release shows
 * it; one kind per data set, chosen in Settings, is S4b-BL-212's.
 */
@Component({
  selector: 'app-dev-kind-picker',
  imports: [TPipe],
  template: `
    <fieldset class="card">
      <legend>{{ 'kind.picker' | t }}</legend>
      <div class="options">
        @for (k of kinds(); track k.id) {
          <label class="option">
            <input type="radio" name="dev-kind" [id]="'dev-kind-' + k.id" [value]="k.id" [checked]="current() === k.id" (change)="picked.emit(k.id)" />
            <span>{{ k.keys.ref('kind.new') | t }}</span>
          </label>
        }
      </div>
    </fieldset>
  `,
})
export class DevKindPicker {
  readonly kinds = input.required<readonly BuiltInKind[]>();
  readonly current = input.required<string>();
  readonly picked = output<string>();
}
