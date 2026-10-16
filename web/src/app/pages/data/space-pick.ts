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

import { Component, computed, inject, input } from '@angular/core';
import { kindById } from '../../core/kind-setting';
import { SpaceService } from '../../core/space.service';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';

/**
 * *Save a copy of* (kinds, S4b-BL-292): with two or more kinds enabled, *Your data* belongs to no space and copies the last
 * one; this chooses the list a copy is made of, so a person need not go back to the chooser first. It sets the same space
 * the pages read (`SpaceService.pick`), so the card's words, counts and file name follow. Drawn only with two spaces.
 */
@Component({
  selector: 'app-space-pick',
  imports: [TPipe],
  template: `
    @if (space.multi()) {
      <div class="field">
        <label for="copy-space">{{ 'data.copyOf' | t }}</label>
        <select id="copy-space" name="copySpace" aria-describedby="copy-space-hint" [disabled]="disabled()" (change)="choose($event)">
          @for (o of options(); track o.id) {
            <option [value]="o.id" [selected]="o.id === space.kind()">{{ o.label }}</option>
          }
        </select>
        <p id="copy-space-hint" class="muted small">{{ 'data.copyOfHint' | t }}</p>
      </div>
    }
  `,
})
export class SpacePick {
  protected readonly space = inject(SpaceService);
  private readonly i18n = inject(TranslationService);
  /** True while a file is built: the space is not changed under it. */
  readonly disabled = input(false);
  /** The enabled kinds, each by its plural in the app's language. */
  protected readonly options = computed(() =>
    this.space.enabled().map((id) => ({ id, label: this.i18n.t(kindById(id).keys.ref('kind.many')) })));

  protected choose(event: Event): void {
    this.space.pick((event.target as HTMLSelectElement).value);
  }
}
