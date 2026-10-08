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

import { Component, ElementRef, afterRenderEffect, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { LocalDataService } from '../../core/local-data.service';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import { criterionName } from '../../shared/criterion-name';
import { DEFAULT_SCORING, activeCriteria } from '../../shared/scoring';

/**
 * *Book a second viewing?* (docs/11 5.8): the dialog that follows a viewing marked done. It offers the form with the
 * kind SECOND and this house, or *Not now*, and lists what is worth checking again: the house's open questions and the
 * criteria it scored 2 or less (photos tagged PROBLEM do not exist yet, so that line is left out). A native modal
 * `<dialog>`: the page behind is inert, Esc closes, and focus goes back to where it was.
 */
@Component({
  selector: 'app-second-viewing-prompt',
  imports: [TPipe],
  template: `
    <dialog #dlg class="prompt" aria-labelledby="second-viewing-title" (cancel)="onCancel($event)" (close)="onClose()">
      @if (open()) {
        <h2 id="second-viewing-title">{{ 'viewings.secondTitle' | t }}</h2>
        <p class="house">{{ houseName() }}</p>
        <h3>{{ 'viewings.recheck' | t }}</h3>
        <ul id="second-viewing-recheck">
          @if (openQuestions() > 0) {
            <li>{{ 'viewings.recheckQuestions' | t: { n: openQuestions() } }}</li>
          }
          @if (lowScores().length > 0) {
            <li>{{ 'viewings.recheckScores' | t: { names: lowScores().join(', ') } }}</li>
          }
          @if (openQuestions() === 0 && lowScores().length === 0) {
            <li>{{ 'viewings.recheckNothing' | t }}</li>
          }
        </ul>
        <div class="actions">
          <button type="button" class="btn" (click)="dismiss()">{{ 'viewings.secondNotNow' | t }}</button>
          <button type="button" class="btn btn-primary" autofocus (click)="book()">{{ 'viewings.secondBook' | t }}</button>
        </div>
      }
    </dialog>
  `,
  styles: `
    dialog.prompt {
      max-width: min(28rem, calc(100vw - 2 * var(--space-4)));
      padding: var(--space-5);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface);
      color: var(--text);
    }
    dialog.prompt::backdrop {
      background: rgba(0, 0, 0, 0.5);
    }
    h2,
    h3,
    p {
      margin: 0 0 var(--space-3);
    }
    .house {
      overflow-wrap: anywhere;
    }
    ul {
      margin: 0 0 var(--space-4);
      padding-inline-start: var(--space-5);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-2);
    }
  `,
})
export class SecondViewingPrompt {
  private readonly api = inject(LocalDataService);
  private readonly router = inject(Router);
  private readonly i18n = inject(TranslationService);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  private opener: HTMLElement | null = null;
  private finished = false;

  readonly houseId = input.required<string>();
  readonly houseName = input<string>('');
  readonly open = input<boolean>(false);
  /** Emitted when the dialog is closed either way (booking included). */
  readonly closed = output<void>();

  protected readonly openQuestions = signal(0);
  protected readonly lowScores = signal<string[]>([]);

  constructor() {
    // What to check again is read when the dialog opens, so it reflects the house as it is then.
    effect(() => {
      if (!this.open()) return;
      forkJoin({ house: this.api.house(this.houseId()), scoring: this.api.scoring() }).subscribe({
        next: ({ house, scoring }) => {
          this.openQuestions.set((house.answers ?? []).filter((a) => a.status === 'OPEN').length);
          const t = (key: Parameters<TranslationService['t']>[0]) => this.i18n.t(key);
          this.lowScores.set(
            activeCriteria(scoring ?? DEFAULT_SCORING)
              .filter((c) => {
                const score = house.checklist?.[c.key];
                return typeof score === 'number' && score >= 1 && score <= 2;
              })
              .map((c) => criterionName(c, t)),
          );
        },
        error: () => {
          this.openQuestions.set(0);
          this.lowScores.set([]);
        },
      });
    });
    afterRenderEffect(() => {
      const wanted = this.open();
      const dlg = this.dialog().nativeElement;
      if (wanted && !dlg.open) {
        this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        this.finished = false;
        if (typeof dlg.showModal === 'function') dlg.showModal();
        else dlg.setAttribute('open', '');
        dlg.querySelector<HTMLElement>('[autofocus]')?.focus();
      } else if (!wanted && dlg.open) {
        if (typeof dlg.close === 'function') dlg.close();
        else dlg.removeAttribute('open');
      }
    });
  }

  protected dismiss(): void {
    this.finish();
  }

  /** Opens the new-viewing form for this house as a SECOND viewing, and closes the dialog. */
  protected book(): void {
    void this.router.navigate(['/viewings/new'], { queryParams: { houseId: this.houseId(), kind: 'SECOND' } });
    this.finish();
  }

  /** Esc is *Not now*. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    this.finish();
  }

  protected onClose(): void {
    this.finish();
    this.opener?.focus();
    this.opener = null;
  }

  /** Tells the page the dialog is closed, once however it was closed. */
  private finish(): void {
    if (this.finished) return;
    this.finished = true;
    this.closed.emit();
  }
}
