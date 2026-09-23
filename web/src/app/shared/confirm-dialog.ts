import { Component, ElementRef, afterRenderEffect, inject, viewChild } from '@angular/core';
import { ConfirmService } from '../core/confirm.service';
import type { ConfirmAnswer } from '../core/confirm.service';
import { TPipe } from '../i18n/t.pipe';

/**
 * The single confirmation dialog of the app (see ConfirmService). Uses the native modal <dialog>:
 * the page behind is inert, Tab stays inside, Esc cancels (the `cancel` event), and focus is returned
 * to the element that opened it.
 */
@Component({
  selector: 'app-confirm-dialog',
  imports: [TPipe],
  template: `
    <dialog
      #dlg
      class="confirm"
      aria-labelledby="confirm-message"
      (cancel)="onCancel($event)"
      (close)="onClose()"
    >
      @if (confirm.pending(); as p) {
        <p id="confirm-message">{{ p.message.key | t: p.message.params }}</p>
        <div class="actions">
          <button type="button" class="btn" [attr.autofocus]="p.danger && !p.altKey ? '' : null" (click)="answer('cancel')">
            {{ 'common.cancel' | t }}
          </button>
          @if (p.altKey) {
            <!-- The safer way forward ("Sync first", "Save first") gets the initial focus, not the destructive one. -->
            <button type="button" class="btn btn-primary" autofocus (click)="answer('alt')">{{ p.altKey | t }}</button>
          }
          <button
            type="button"
            [class]="p.danger ? 'btn btn-danger' : p.altKey ? 'btn' : 'btn btn-primary'"
            [attr.autofocus]="p.danger || p.altKey ? null : ''"
            (click)="answer('confirm')"
          >
            {{ p.confirmKey | t }}
          </button>
        </div>
      }
    </dialog>
  `,
  styles: `
    dialog.confirm {
      max-width: min(28rem, calc(100vw - 2 * var(--space-4)));
      padding: var(--space-5);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface);
      color: var(--text);
    }
    dialog.confirm::backdrop {
      background: rgba(0, 0, 0, 0.5);
    }
    p {
      margin: 0 0 var(--space-4);
      font-size: var(--text-md);
      /* A message may list changes one per line ("Street: Temple Road → 5th Cross"). */
      white-space: pre-line;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-2);
    }
    /*
     * Narrow phones: stacked, full-width buttons (M3's stacked dialog actions) instead of a wrapping row, where
     * long Tamil or Telugu labels broke into uneven rows and the destructive button could end up alone on top.
     * Top to bottom: the safe way forward (the primary "Save first" / "Sync first", or a plain confirm), then the
     * destructive action, then Cancel. Only the visual order changes. Focus still reads top to bottom: it starts on
     * the safe action (autofocus), Tab goes on to the destructive one, and the dialog's Tab cycle then reaches
     * Cancel, which is first in the DOM.
     */
    @media (max-width: 480px) {
      .actions {
        flex-direction: column;
        align-items: stretch;
      }
      .actions .btn-primary {
        order: -1;
      }
      .actions .btn:first-child {
        order: 1;
      }
    }
  `,
})
export class ConfirmDialog {
  protected readonly confirm = inject(ConfirmService);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  private opener: HTMLElement | null = null;
  private answered = false;

  constructor() {
    // After render, so the buttons exist when the dialog opens and takes focus.
    afterRenderEffect(() => {
      const open = this.confirm.pending() !== null;
      const dlg = this.dialog().nativeElement;
      if (open && !dlg.open) {
        this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        this.answered = false;
        dlg.showModal();
        dlg.querySelector<HTMLElement>('[autofocus]')?.focus();
      } else if (!open && dlg.open) {
        dlg.close();
      }
    });
  }

  protected answer(choice: ConfirmAnswer): void {
    this.answered = true;
    this.confirm.settle(choice);
  }

  /** Esc: treat as Cancel. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    this.answer('cancel');
  }

  protected onClose(): void {
    if (!this.answered) this.confirm.settle('cancel');
    this.opener?.focus();
    this.opener = null;
  }
}
