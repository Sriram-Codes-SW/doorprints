import { Injectable, signal } from '@angular/core';
import type { Msg } from '../i18n/translation.service';
import type { TKey } from '../i18n/en';

export interface ConfirmRequest {
  readonly message: Msg;
  /** Label of the confirming button (default "OK"-like per context). */
  readonly confirmKey: TKey;
  /** Style the confirm button as destructive and put initial focus on Cancel. */
  readonly danger: boolean;
}

interface Pending extends ConfirmRequest {
  readonly resolve: (ok: boolean) => void;
}

/**
 * In-app replacement for window.confirm() (docs/05 A11Y-B05): the buttons are in the app language, the dialog is a
 * native modal <dialog> (focus trap, Esc = cancel, focus returns to the trigger), and callers get a Promise.
 * Rendered once by ConfirmDialog in the app shell.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly state = signal<Pending | null>(null);
  readonly pending = this.state.asReadonly();

  ask(message: Msg, options: { confirmKey?: TKey; danger?: boolean } = {}): Promise<boolean> {
    // A second request while one is open cancels the first.
    this.state()?.resolve(false);
    return new Promise<boolean>((resolve) =>
      this.state.set({
        message,
        confirmKey: options.confirmKey ?? 'confirm.ok',
        danger: options.danger ?? false,
        resolve,
      }),
    );
  }

  /** Called by the dialog component. */
  settle(ok: boolean): void {
    const p = this.state();
    if (!p) return;
    this.state.set(null);
    p.resolve(ok);
  }
}
