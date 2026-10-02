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

import { Component, ChangeDetectionStrategy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../i18n/t.pipe';
import { TranslationService } from '../../../i18n/translation.service';
import {
  approverReply,
  codeOf,
  newcomerCommit,
  revealAndCode,
  type PairingMessage,
} from '../../../data/drive/connect/pairing-flow';

/**
 * Second-browser enrolment without a camera: the 8-digit comparison (docs/15 §9.5 i) and the recovery-key path
 * on the sibling join card. QR enrolment is a later ticket.
 */
@Component({
  selector: 'app-drive-enrol',
  standalone: true,
  imports: [CommonModule, FormsModule, TPipe],
  templateUrl: './drive-enrol.html',
  styleUrl: './drive-enrol.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveEnrolCard {
  protected readonly i18n = inject(TranslationService);

  protected readonly role = signal<'choose' | 'newcomer' | 'approver'>('choose');
  protected readonly requestText = signal('');
  protected readonly replyText = signal('');
  protected readonly code = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly copied = signal(false);

  private nNew: Uint8Array | null = null;
  private nApprover: Uint8Array | null = null;

  protected becomeNewcomer(): void {
    this.error.set(null);
    this.nNew = crypto.getRandomValues(new Uint8Array(16));
    const pkNew = crypto.getRandomValues(new Uint8Array(65));
    pkNew[0] = 4;
    const msg = newcomerCommit(pkNew, this.nNew, Date.now());
    this.requestText.set(JSON.stringify(msg));
    this.role.set('newcomer');
  }

  protected becomeApprover(): void {
    this.error.set(null);
    this.role.set('approver');
  }

  protected setReply(event: Event): void {
    const el = event.target;
    this.replyText.set(el instanceof HTMLTextAreaElement ? el.value : '');
  }

  protected setRequest(event: Event): void {
    const el = event.target;
    this.requestText.set(el instanceof HTMLTextAreaElement ? el.value : '');
  }

  protected async copyRequest(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.requestText());
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    } catch {
      this.error.set(this.i18n.t('driveEnrol.copyFailed'));
    }
  }

  protected approvePasted(): void {
    this.error.set(null);
    this.code.set(null);
    let commit: PairingMessage;
    try {
      commit = JSON.parse(this.requestText()) as PairingMessage;
    } catch {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    this.nApprover = crypto.getRandomValues(new Uint8Array(16));
    const pkA = crypto.getRandomValues(new Uint8Array(65));
    pkA[0] = 4;
    const reply = approverReply(commit, pkA, this.nApprover, Date.now());
    if (!reply.ok || !reply.message) {
      const reason = !reply.ok ? reply.reason : 'INCOMPLETE';
      this.error.set(this.i18n.t(reason === 'EXPIRED' ? 'driveEnrol.expired' : 'driveEnrol.badMessage'));
      return;
    }
    this.replyText.set(JSON.stringify(reply.message));
  }

  protected revealCode(): void {
    this.error.set(null);
    if (!this.nNew) {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    let reply: PairingMessage;
    try {
      reply = JSON.parse(this.replyText()) as PairingMessage;
    } catch {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const out = revealAndCode(reply, this.nNew, Date.now());
    if (!out.ok || !out.message) {
      const reason = !out.ok ? out.reason : 'INCOMPLETE';
      this.error.set(this.i18n.t(reason === 'EXPIRED' ? 'driveEnrol.expired' : 'driveEnrol.mismatch'));
      return;
    }
    this.code.set(out.code);
    this.replyText.set(JSON.stringify(out.message));
  }

  protected showApproverCode(): void {
    this.error.set(null);
    let revealed: PairingMessage;
    try {
      revealed = JSON.parse(this.replyText()) as PairingMessage;
    } catch {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const out = codeOf(revealed, Date.now());
    if (!out.ok) {
      this.error.set(this.i18n.t(out.reason === 'EXPIRED' ? 'driveEnrol.expired' : 'driveEnrol.mismatch'));
      return;
    }
    this.code.set(out.code);
  }
}
