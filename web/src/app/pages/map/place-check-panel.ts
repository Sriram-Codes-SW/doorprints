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

/**
 * The answer of *Have I been here?* as a panel (docs/11 5.27.13, docs/03 section 6.2b row 17, S4b-FR-24). {@link CheckResultView}
 * is the panel itself (the house page uses it too); {@link PlaceCheckPanel} puts the Map's answer at the top of the list
 * section. The text is the whole answer: the headline and the rows, as words. Focus moves to the title on arrival (which also
 * scrolls it into view on a phone); the headline is said once through the app's polite live region by `PlaceCheckState`, not by
 * a second region here; *Close* returns focus to the button that opened it and withdraws the announcement.
 */

import { Component, ElementRef, afterNextRender, computed, inject, input, output, viewChild } from '@angular/core';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { PlaceCheckState } from './place-check-state';
import type { CheckAnswer, LocateProblem } from './place-check-state';

const TITLE_KEY: Record<CheckAnswer['kind'], TKey> = { here: 'trace.here.title', house: 'trace.here.titleHouse', spot: 'trace.here.titleSpot' };
const PROBLEM_KEY: Record<LocateProblem, TKey> = { denied: 'trace.here.deniedWeb', unavailable: 'trace.web.unavailable', timeout: 'trace.here.timeout' };

@Component({
  selector: 'app-check-result',
  imports: [TPipe],
  template: `
    <section class="check-result" aria-labelledby="check-title">
      <h2 id="check-title" #title tabindex="-1">{{ titleKey() | t }}</h2>
      <p id="check-headline" class="headline">{{ text().headline }}</p>
      @if (text().rows.length > 0) {
        <ul class="rows" id="check-rows">
          @for (row of text().rows; track $index) {
            <li>{{ row }}</li>
          }
        </ul>
        @if (text().rowsMore; as more) {
          <p class="muted small" id="check-rows-more">{{ more }}</p>
        }
      }
      @for (note of text().notes; track $index) {
        <p class="muted small check-note">{{ note }}</p>
      }
      <p class="muted small">{{ 'trace.here.privacy' | t }}</p>
      @if (locating()) {
        <p role="status" id="check-locating">{{ 'trace.here.locating' | t }}</p>
      }
      <!-- Present before a problem arrives, so it is announced. -->
      <div role="alert">
        @if (problem(); as p) {
          <p class="error" id="check-problem">{{ problemKey(p) | t }}</p>
        }
      </div>
      <div class="actions">
        @if (locating()) {
          <button type="button" class="btn" id="check-cancel" (click)="cancelLocate.emit()">{{ 'common.cancel' | t }}</button>
        } @else {
          @if (showMap()) {
            <button type="button" class="btn" id="check-show" (click)="show.emit()">{{ 'trace.house.show' | t }}</button>
          }
          @if (answer().kind === 'here') {
            <button type="button" class="btn" id="check-again" (click)="again.emit()">{{ 'trace.here.again' | t }}</button>
          }
        }
        <button type="button" class="btn btn-primary" id="check-close" (click)="closed.emit()">{{ 'common.close' | t }}</button>
      </div>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .check-result {
      padding: var(--space-3) var(--space-4);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface);
    }
    h2 {
      margin: 0 0 var(--space-2);
      font-size: var(--text-lg);
    }
    h2:focus {
      outline: none;
    }
    .headline {
      margin: 0 0 var(--space-2);
      font-weight: 600;
    }
    .rows {
      margin: 0 0 var(--space-2);
      padding-inline-start: var(--space-5);
    }
    p {
      margin: 0 0 var(--space-2);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin-top: var(--space-3);
    }
    .error {
      color: var(--error-text);
    }
  `,
})
export class CheckResultView {
  private readonly state = inject(PlaceCheckState);
  readonly answer = input.required<CheckAnswer>();
  /** The map can show the place and the stretch: *Show on map* is offered. */
  readonly showMap = input(true);
  readonly locating = input(false);
  readonly problem = input<LocateProblem | null>(null);
  readonly show = output<void>();
  readonly again = output<void>();
  readonly cancelLocate = output<void>();
  readonly closed = output<void>();

  private readonly title = viewChild.required<ElementRef<HTMLElement>>('title');
  protected readonly titleKey = computed(() => TITLE_KEY[this.answer().kind]);
  protected readonly text = computed(() => this.state.text(this.answer()));

  constructor() {
    // Focus goes to the title on arrival: a screen reader reads it, and a phone scrolls the panel into view.
    afterNextRender(() => this.title().nativeElement.focus());
  }

  protected problemKey(problem: LocateProblem): TKey {
    return PROBLEM_KEY[problem];
  }
}

@Component({
  selector: 'app-place-check-panel',
  imports: [CheckResultView],
  template: `
    @if (state.answer(); as answer) {
      <app-check-result
        [answer]="answer"
        [showMap]="mapUsable()"
        [locating]="state.locating()"
        [problem]="state.locateProblem()"
        (show)="state.show()"
        (again)="state.locateHere()"
        (cancelLocate)="state.cancelLocating()"
        (closed)="close()"
      />
    }
  `,
  styles: `
    :host {
      display: block;
    }
    :host:has(app-check-result) {
      margin: var(--space-3) var(--space-4) 0;
    }
  `,
})
export class PlaceCheckPanel {
  protected readonly state = inject(PlaceCheckState);
  /** A map is on screen: *Show on map* needs one. */
  readonly mapUsable = input(true);

  /** *Close*: the answer, the halo and the announcement go, and focus returns to the button that opened it. */
  protected close(): void {
    this.state.close();
    document.getElementById('place-check-open')?.focus();
  }
}
