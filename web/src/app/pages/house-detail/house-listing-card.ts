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
import { Component, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiService } from '../../core/ai.service';
import { TPipe } from '../../i18n/t.pipe';
import { HouseListingFill } from './house-listing-fill';

export { HouseListingFill } from './house-listing-fill';

/**
 * The "Fill in from listing text" card of the house page (S4b-BL-168; its own component since S4b-BL-238, when it gained
 * *Undo fill*, the "from the listing" marks and the price warning). The page owns the {@link HouseListingFill} and the
 * draft; this is only the markup. The card's ids (`listing-text`, `listing-fill-submit`, `house-name` for the focus after
 * a fill) and classes (`.listing-fill`, `.warnings`) are what the page's spec and styles use.
 */
@Component({
  selector: 'app-house-listing-card',
  imports: [FormsModule, TPipe],
  template: `
    <details class="card listing-fill" [open]="listing().open">
      <summary>{{ 'listingFill.title' | t }}</summary>
      <div class="field">
        <label for="listing-text">{{ 'listingFill.label' | t }}</label>
        <textarea
          id="listing-text"
          rows="5"
          [(ngModel)]="listing().text"
          [placeholder]="'listingFill.placeholder' | t"
          aria-describedby="listing-fill-hint listing-cut-slot"
        ></textarea>
        <!-- The slot is there before the hint, so a screen reader announces it when a long paste fills it (S4b-BL-182). -->
        <div id="listing-cut-slot" class="field-hint" aria-live="polite">
          @if (listing().cut().leftOut > 0) {
            <span id="listing-cut-hint">{{ 'listingFill.cut' | t: { max: listing().max, n: listing().cut().leftOut } }}</span>
          }
        </div>
        <span id="listing-fill-hint" class="field-hint">{{ 'listingFill.hint' | t }} {{ (ai.usesOwnKey() ? 'ai.disclosureOwnKeyHost' : 'ai.disclosure') | t: { host: ai.ownHost() } }}</span>
      </div>
      <button
        type="button"
        id="listing-fill-submit"
        class="btn"
        [attr.aria-disabled]="listing().filling() || !listing().text.trim() ? 'true' : null"
        (click)="listing().fill()"
      >
        {{ (listing().filling() ? 'listingFill.working' : 'listingFill.submit') | t }}
      </button>
      @if (listing().before(); as before) {
        <!-- Undo fill (S4b-BL-238): the form goes back to what it was before the last fill; the button goes with it. -->
        <button type="button" class="btn btn-sm listing-undo" (click)="listing().undo()" [attr.aria-describedby]="'listing-undo-hint'">
          {{ 'listingFill.undo' | t }}
        </button>
        <span id="listing-undo-hint" class="visually-hidden">{{ 'listingFill.undoHint' | t }}</span>
      }
      <div class="refresh-slot" [class.stale]="listing().filling()">
        <div role="alert">
          @if (listing().error(); as card) {
            @for (c of [card]; track c.run) {
              <p class="error">{{ c.value.key | t: c.value.params }}</p>
            }
          }
        </div>
        @if (listing().filling() && listing().error()) {
          <div class="refresh-bar" role="progressbar" [attr.aria-label]="'listingFill.working' | t"></div>
        }
      </div>
      <!-- The marks (S4b-BL-238): the fields the fill wrote and the person has not edited since; one line, not a badge per field. -->
      @if (listing().marks().length > 0) {
        <p class="field-hint listing-marks">
          {{ 'listingFill.fromListing' | t }}
          @for (m of listing().markNames(); track $index; let last = $last) {
            <span class="chip">{{ m.key | t }}</span>
          }
        </p>
      }
      @if (listing().warnings().length > 0 || listing().kept().length > 0 || listing().priceWarning()) {
        <div class="warnings">
          <p>{{ 'listingFill.warnings' | t }}</p>
          <ul>
            @if (listing().priceWarning(); as p) {
              <li class="price-differs">{{ p.key | t: p.params }}</li>
            }
            @for (k of listing().kept(); track $index) {
              <li>{{ k.key | t: k.params }}</li>
            }
            @for (w of listing().warnings(); track $index) {
              <li>{{ w }}</li>
            }
          </ul>
        </div>
      }
    </details>
  `,
})
export class HouseListingCard {
  protected readonly ai = inject(AiService);
  /** The page's listing state: the text, the read in flight, what the last fill did. */
  readonly listing = input.required<HouseListingFill>();
}
