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

import { Component, ChangeDetectionStrategy, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { unb64 } from '../../../data/crypto/bytes';
import { TPipe } from '../../../i18n/t.pipe';
import { TranslationService } from '../../../i18n/translation.service';
import type { TKey } from '../../../i18n/en';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';
import {
  approverReply,
  codeOf,
  newcomerCommit,
  revealAndCode,
  withWrap,
  type PairingMessage,
} from '../../../data/drive/connect/pairing-flow';

/** The name written into keys.json for a browser that joins by the 8-digit code. */
const WEBSITE_DEVICE_NAME = 'Website';

/**
 * Second-browser enrolment without a camera (docs/15 §9.5 i). Both sides use this device's real public key.
 * After the codes match, the connected browser approves (L2) and the new browser opens the returned wrap.
 * QR enrolment is a later ticket. The recovery key stays on the sibling join card.
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
  private readonly service = inject(DriveConnectService);
  protected readonly i18n = inject(TranslationService);

  protected readonly role = signal<'choose' | 'newcomer' | 'approver'>('choose');
  protected readonly requestText = signal('');
  protected readonly replyText = signal('');
  protected readonly code = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly copied = signal(false);
  protected readonly approved = signal(false);
  protected readonly joined = signal(false);

  protected readonly canJoin = computed(() => {
    const msg = parseMessage(this.replyText());
    return !!msg && typeof msg.wrapEnc === 'string' && typeof msg.wrapCt === 'string' && typeof msg.epoch === 'number';
  });

  private nNew: Uint8Array | null = null;
  private nApprover: Uint8Array | null = null;

  protected async becomeNewcomer(): Promise<void> {
    this.error.set(null);
    this.joined.set(false);
    const pkNew = await this.publicKey();
    if (!pkNew) return;
    this.nNew = crypto.getRandomValues(new Uint8Array(16));
    const msg = newcomerCommit(pkNew, this.nNew, Date.now());
    this.requestText.set(JSON.stringify(msg));
    this.role.set('newcomer');
  }

  protected becomeApprover(): void {
    this.error.set(null);
    this.approved.set(false);
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

  protected async approvePasted(): Promise<void> {
    this.error.set(null);
    this.code.set(null);
    this.approved.set(false);
    const commit = parseMessage(this.requestText());
    if (!commit) {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const pkA = await this.publicKey();
    if (!pkA) return;
    this.nApprover = crypto.getRandomValues(new Uint8Array(16));
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

  /** The connected browser, after the person has compared the codes: list this public key and hand back its wrap. */
  protected async confirmNumbers(): Promise<void> {
    this.error.set(null);
    const revealed = parseMessage(this.replyText());
    const pk = revealed?.pkNew ? unb64(revealed.pkNew) : null;
    if (!revealed || !pk) {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const out = await this.service.approveJoinedDevice(pk, WEBSITE_DEVICE_NAME);
    if (!out.ok) {
      this.error.set(this.i18n.t(this.reasonKey(out.reason)));
      return;
    }
    this.replyText.set(JSON.stringify(withWrap(revealed, out.wrapEnc, out.wrapCt, out.epoch)));
    this.approved.set(true);
  }

  /** The new browser opens the wrap from the reply. The recovery key is not required. */
  protected async joinFolder(): Promise<void> {
    this.error.set(null);
    const msg = parseMessage(this.replyText());
    if (!msg || !msg.wrapEnc || !msg.wrapCt || typeof msg.epoch !== 'number') {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const result = await this.service.joinFromWrap(msg.wrapEnc, msg.wrapCt, msg.epoch);
    if (result.state === 'Ready') {
      this.joined.set(true);
      return;
    }
    this.error.set(this.i18n.t(result.error ?? 'driveEnrol.badMessage'));
  }

  private async publicKey(): Promise<Uint8Array | null> {
    try {
      return await this.service.devicePublicKey();
    } catch {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return null;
    }
  }

  private reasonKey(reason: string): TKey {
    if (reason === 'USE_PHONE') return 'driveDelete.usePhone';
    if (reason.startsWith('drive')) return reason as TKey;
    return 'driveEnrol.badMessage';
  }
}

function parseMessage(text: string): PairingMessage | null {
  try {
    const msg = JSON.parse(text) as PairingMessage;
    return msg && typeof msg === 'object' ? msg : null;
  } catch {
    return null;
  }
}
