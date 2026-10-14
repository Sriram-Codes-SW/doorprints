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

import { Component, inject } from '@angular/core';
import { DeleteUndoService } from '../core/delete-undo.service';
import { TPipe } from '../i18n/t.pipe';

/**
 * The *Undo* snackbar after a delete (deletion safeguards PR 1; the website's twin of the phones' DeletedHouseUndo.kt).
 * Drawn once in the app shell, so it outlives the page that deleted the candidate. The status region is always in the
 * page and the message is put into it, which is what makes a screen reader read it; *Undo* is a plain button in the tab
 * order, placed in the page before the main content (Shift+Tab from the top of the page reaches it), and the ten
 * seconds stand still while the pointer or the focus is on it.
 */
@Component({
  selector: 'app-undo-snackbar',
  imports: [TPipe],
  template: `
    <div class="undo-host" role="status" aria-atomic="true" (mouseenter)="undo.hold(true)" (mouseleave)="undo.hold(false)" (focusin)="undo.hold(true)" (focusout)="undo.hold(false)">
      @if (undo.offer(); as o) {
        <div class="snack">
          <span class="msg">{{ 'delete.deleted' | t: { name: o.name } }}</span>
          <button type="button" class="btn" (click)="run()">{{ 'delete.undo' | t }}</button>
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: contents; }
    .undo-host {
      position: fixed;
      z-index: 900;
      left: max(var(--space-3), env(safe-area-inset-left));
      right: max(var(--space-3), env(safe-area-inset-right));
      bottom: calc(var(--nav-h, 0px) + var(--space-3));
      display: flex;
      justify-content: center;
      pointer-events: none;
    }
    .snack {
      pointer-events: auto;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2) var(--space-4);
      max-width: 36rem;
      width: 100%;
      padding: var(--space-2) var(--space-3) var(--space-2) var(--space-4);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface);
      color: var(--text);
      box-shadow: 0 2px 10px rgba(0, 0, 0, 0.25);
    }
    .msg { flex: 1 1 12rem; min-width: 0; overflow-wrap: anywhere; }
    @media (forced-colors: active) { .snack { border-color: CanvasText; } }
  `,
})
export class UndoSnackbar {
  protected readonly undo = inject(DeleteUndoService);

  /** Undoes the delete; the button leaves with the offer, so focus goes to the page's main region instead of the document. */
  protected async run(): Promise<void> {
    await this.undo.undo();
    if (document.activeElement === document.body || document.activeElement === null) document.getElementById('main')?.focus();
  }
}
