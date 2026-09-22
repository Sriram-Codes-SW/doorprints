import { Component, ElementRef, afterRenderEffect, inject, viewChild } from '@angular/core';
import { ConfirmService } from '../core/confirm.service';
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
          <button type="button" class="btn" [attr.autofocus]="p.danger ? '' : null" (click)="answer(false)">
            {{ 'common.cancel' | t }}
          </button>
          <button
            type="button"
            [class]="p.danger ? 'btn btn-danger' : 'btn btn-primary'"
            [attr.autofocus]="p.danger ? null : ''"
            (click)="answer(true)"
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
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-2);
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

  protected answer(ok: boolean): void {
    this.answered = true;
    this.confirm.settle(ok);
  }

  /** Esc: treat as Cancel. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    this.answer(false);
  }

  protected onClose(): void {
    if (!this.answered) this.confirm.settle(false);
    this.opener?.focus();
    this.opener = null;
  }
}
