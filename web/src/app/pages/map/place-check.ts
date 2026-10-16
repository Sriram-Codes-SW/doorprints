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
 * *Have I been here?* on the Map (docs/11 5.27.13, docs/03 section 6.2b row 14, S4b-FR-24): the button among the map's actions
 * and the dialog it opens. The dialog shows the permission sentence EVERY time (the check keeps no flag) and two choices:
 * *Where I am now* (one fresh fix; the watch is started inside that click, a user gesture) and *A spot on the map* (the page's
 * crosshair mode). While the fix is awaited the dialog says so and has *Cancel*. It is modal only while choosing and locating,
 * so the halo is never under a backdrop. The result goes to `PlaceCheckState` (the panel, the halo, the ring); nothing goes in
 * a URL, `history.state` or a storage. Shown whether or not the trace is on.
 */

import { Component, ElementRef, afterRenderEffect, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { TPipe } from '../../i18n/t.pipe';
import { GLYPHS } from '../../shared/glyphs';
import { PlaceCheckState } from './place-check-state';
import type { LocateProblem } from './place-check-state';
import type { TKey } from '../../i18n/en';

const PROBLEM_KEY: Record<LocateProblem, TKey> = { denied: 'trace.here.deniedWeb', unavailable: 'trace.web.unavailable', timeout: 'trace.here.timeout' };

/**
 * The *Have I been here?* button and the dialog that offers *Where I am now* and *A spot on the map*. The result goes
 * to {@link PlaceCheckState}.
 */
@Component({
  selector: 'app-place-check',
  imports: [TPipe],
  host: { class: 'place-check' },
  template: `
    <button type="button" id="place-check-open" class="btn" [title]="'trace.here.button' | t" (click)="open()">
      <svg class="glyph" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path [attr.d]="glyph" fill="currentColor" /></svg>
      <span class="label">{{ 'trace.here.button' | t }}</span>
    </button>
    <dialog #dlg class="check" aria-labelledby="check-dialog-title" (cancel)="onCancel($event)" (close)="onClose()">
      <h2 id="check-dialog-title" tabindex="-1">{{ 'trace.here.title' | t }}</h2>
      <p id="check-permission">{{ 'trace.here.permissionExplain' | t }}</p>
      @if (state.locating()) {
        <p role="status" id="check-dialog-locating">{{ 'trace.here.locating' | t }}</p>
        <div class="actions">
          <button type="button" class="btn" id="check-dialog-cancel-locate" (click)="state.cancelLocating()">{{ 'common.cancel' | t }}</button>
        </div>
      } @else {
        <!-- Present before a problem arrives, so it is announced. -->
        <div role="alert">
          @if (state.locateProblem(); as p) {
            <p class="error" id="check-dialog-problem">{{ problemKey(p) | t }}</p>
          }
        </div>
        <div class="choices">
          @if (state.canLocate) {
            <button type="button" class="btn btn-primary" id="check-here" aria-describedby="check-permission" (click)="here()">
              {{ 'trace.here.menuHere' | t }}
            </button>
          }
          @if (mapUsable()) {
            <button type="button" class="btn" id="check-spot" (click)="spot()">{{ 'trace.here.menuSpot' | t }}</button>
          }
        </div>
        <div class="actions">
          <button type="button" class="btn" id="check-dialog-close" (click)="close()">{{ 'common.cancel' | t }}</button>
        </div>
      }
    </dialog>
  `,
  styles: `
    :host {
      display: inline-flex;
      /* The actions row of the map page lets no pointer through; this control takes it back. */
      pointer-events: auto;
    }
    .btn {
      box-shadow: var(--shadow-lg);
    }
    .glyph {
      flex: none;
    }
    /* Phones: the glyph alone, still a 44px target with its name for assistive technology (the tooltip is the title). */
    @media (max-width: 760px) {
      .label {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }
    }
    dialog.check {
      width: min(28rem, calc(100vw - 2 * var(--space-4)));
      padding: var(--space-5);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface);
      color: var(--text);
    }
    dialog.check::backdrop {
      background: rgba(0, 0, 0, 0.5);
    }
    h2 {
      margin: 0 0 var(--space-3);
      font-size: var(--text-lg);
    }
    h2:focus {
      outline: none;
    }
    p {
      margin: 0 0 var(--space-3);
    }
    .choices,
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      margin-bottom: var(--space-3);
    }
    .actions {
      justify-content: flex-end;
      margin-bottom: 0;
    }
    .error {
      color: var(--error-text);
    }
    @media (max-width: 480px) {
      .choices,
      .actions {
        flex-direction: column;
        align-items: stretch;
      }
    }
  `,
})
export class PlaceCheck {
  protected readonly state = inject(PlaceCheckState);
  protected readonly glyph = GLYPHS.directionsWalk;

  /** A map is on screen: *A spot on the map* needs one. */
  readonly mapUsable = input(true);
  /** The person chose *A spot on the map*: the page enters its crosshair mode. */
  readonly pickSpot = output<void>();

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  private readonly shown = signal(false);
  private opener: HTMLElement | null = null;

  constructor() {
    // An answer closes the dialog (the panel takes over, and takes the focus).
    effect(() => {
      if (this.state.answer() !== null) untracked(() => this.shut());
    });
    // The title holds focus while the dialog is open; it moves to the choices once they are there.
    afterRenderEffect(() => {
      if (!this.shown()) return;
      const dlg = this.dialog().nativeElement;
      dlg.querySelector<HTMLElement>(this.state.locating() ? '#check-dialog-cancel-locate' : '#check-here, #check-spot')?.focus();
    });
  }

  protected problemKey(problem: LocateProblem): TKey {
    return PROBLEM_KEY[problem];
  }

  /** Opens the dialog, ending any wait for a fix and clearing a location problem. */
  protected open(): void {
    this.opener = document.getElementById('place-check-open');
    this.state.cancelLocating();
    this.state.locateProblem.set(null);
    const dlg = this.dialog().nativeElement;
    if (!dlg.open) dlg.showModal();
    this.shown.set(true);
  }

  /** *Where I am now*: the watch is started in this call, inside the click. */
  protected here(): void {
    this.state.locateHere();
  }

  /** Closes the dialog and asks the page to enter its crosshair mode. */
  protected spot(): void {
    this.shut();
    this.pickSpot.emit();
  }

  /** Cancels a wait for a fix and closes the dialog. */
  protected close(): void {
    this.state.cancelLocating();
    this.shut();
  }

  private shut(): void {
    const dlg = this.dialog().nativeElement;
    if (dlg.open) dlg.close();
  }

  /** Esc: while finding the location it cancels the wait; otherwise it closes. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    this.close();
  }

  protected onClose(): void {
    this.shown.set(false);
    this.state.cancelLocating();
    // With an answer the panel has the focus; without one it goes back to the button.
    if (this.state.answer() === null) this.opener?.focus();
    this.opener = null;
  }
}
