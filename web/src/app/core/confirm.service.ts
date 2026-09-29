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

import { Injectable, signal } from '@angular/core';
import type { Msg } from '../i18n/translation.service';
import type { TKey } from '../i18n/en';

/** What the user chose: the main action, the optional alternative ("Sync first", "Save first"), or Cancel/Esc. */
export type ConfirmAnswer = 'confirm' | 'alt' | 'cancel';

export interface ConfirmRequest {
  readonly message: Msg;
  /** Label of the confirming button (default "OK"-like per context). */
  readonly confirmKey: TKey;
  /**
   * Label of an optional third button between Cancel and the main action — the safer way forward, such as
   * "Sync first" before removing data or "Save first" before a reload. Null when there is none.
   */
  readonly altKey: TKey | null;
  /** Style the confirm button as destructive and put initial focus on Cancel. */
  readonly danger: boolean;
}

interface Pending extends ConfirmRequest {
  readonly resolve: (answer: ConfirmAnswer) => void;
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

  /** Two buttons: resolves true for the main action, false for Cancel or Esc. */
  async ask(message: Msg, options: { confirmKey?: TKey; danger?: boolean } = {}): Promise<boolean> {
    return (await this.choose(message, options)) === 'confirm';
  }

  /** Like {@link ask}, with an optional third button (`altKey`) whose answer is `'alt'`. */
  choose(message: Msg, options: { confirmKey?: TKey; altKey?: TKey | null; danger?: boolean } = {}): Promise<ConfirmAnswer> {
    // A second request while one is open cancels the first.
    this.state()?.resolve('cancel');
    return new Promise<ConfirmAnswer>((resolve) =>
      this.state.set({
        message,
        confirmKey: options.confirmKey ?? 'confirm.ok',
        altKey: options.altKey ?? null,
        danger: options.danger ?? false,
        resolve,
      }),
    );
  }

  /** Called by the dialog component. `true`/`false` are kept for the two-button case. */
  settle(answer: ConfirmAnswer | boolean): void {
    const p = this.state();
    if (!p) return;
    this.state.set(null);
    p.resolve(answer === true ? 'confirm' : answer === false ? 'cancel' : answer);
  }
}
