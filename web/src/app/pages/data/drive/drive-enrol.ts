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
import { constantTimeEquals, unb64 } from '../../../data/crypto/bytes';
import { encodeQr } from '../../../data/crypto/qr-code';
import { parseQrOffer, qrOfferText } from '../../../data/crypto/qr-enrol';
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
 * Second-browser enrolment (docs/15 §9.5 i). The new browser can show a QR code (`pk_new ‖ s`) or an 8-digit
 * comparison. The connected browser scans or pastes the code, approves (L2), and the new browser opens the
 * PSK wrap. The 8-digit path still uses the base-mode wrap. The recovery key stays on the sibling join card.
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

  protected readonly role = signal<'choose' | 'newcomer' | 'approver' | 'qr-new' | 'qr-old'>('choose');
  protected readonly qr = signal<boolean[][] | null>(null);
  protected readonly camera = signal(typeof barcodeDetector() === 'function');
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
  private psk: Uint8Array | null = null;
  /** Pairing fields of the reveal whose 8-digit code is on screen. Approval uses this transcript, not a later paste. */
  private compared: PairingFields | null = null;

  /**
   * The new browser starts the 8-digit join: it makes a random nonce and a commitment to its public key and nonce,
   * which the connected browser pastes.
   */
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

  /**
   * The new browser starts the QR join: it makes a random 32-byte secret and shows it with its public key as a QR code,
   * to be scanned by the connected browser.
   */
  protected async becomeQrNewcomer(): Promise<void> {
    this.error.set(null);
    this.joined.set(false);
    const pkNew = await this.publicKey();
    if (!pkNew) return;
    this.psk = crypto.getRandomValues(new Uint8Array(32));
    const text = qrOfferText(pkNew, this.psk);
    this.requestText.set(text);
    this.qr.set(encodeQr(new TextEncoder().encode(text)));
    this.role.set('qr-new');
  }

  /** The connected browser takes the QR role. */
  protected becomeQrApprover(): void {
    this.error.set(null);
    this.approved.set(false);
    this.qr.set(null);
    this.role.set('qr-old');
  }

  /**
   * The connected browser reads the QR offer, approves the new device and produces the reply that holds the wrap that
   * lets it open the folder.
   */
  protected async approveQr(): Promise<void> {
    this.error.set(null);
    this.approved.set(false);
    const offer = parseQrOffer(this.requestText());
    if (!offer) {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const out = await this.service.approveJoinedDevicePsk(offer.publicKey, WEBSITE_DEVICE_NAME, offer.psk);
    if (!out.ok) {
      this.error.set(this.i18n.t(this.reasonKey(out.reason)));
      return;
    }
    this.replyText.set(JSON.stringify({ wrapEnc: out.wrapEnc, wrapCt: out.wrapCt, epoch: out.epoch }));
    this.approved.set(true);
  }

  /** The new browser opens the pasted reply with its secret. The secret is wiped from memory once it has joined. */
  protected async joinFromQr(): Promise<void> {
    this.error.set(null);
    if (!this.psk) {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const msg = parseMessage(this.replyText());
    if (!msg || !msg.wrapEnc || !msg.wrapCt || typeof msg.epoch !== 'number') {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const result = await this.service.joinFromPsk(msg.wrapEnc, msg.wrapCt, msg.epoch, this.psk);
    if (result.state === 'Ready') {
      this.psk.fill(0);
      this.psk = null;
      this.joined.set(true);
      return;
    }
    this.error.set(this.i18n.t(result.error ?? 'driveEnrol.badMessage'));
  }

  /**
   * Reads a QR offer from the camera with the browser's barcode detector (where it has one). Only a valid offer is
   * accepted, and the camera is stopped afterwards.
   */
  protected async useCamera(): Promise<void> {
    this.error.set(null);
    const Detector = barcodeDetector();
    if (!Detector || !navigator.mediaDevices?.getUserMedia) {
      this.error.set(this.i18n.t('driveEnrol.cameraMissing'));
      return;
    }
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
    } catch {
      this.error.set(this.i18n.t('driveEnrol.cameraMissing'));
      return;
    }
    const video = document.createElement('video');
    video.playsInline = true;
    video.srcObject = stream;
    try {
      await video.play();
      const found = await new Detector({ formats: ['qr_code'] }).detect(video);
      const text = found[0]?.rawValue ?? '';
      if (!parseQrOffer(text)) {
        this.error.set(this.i18n.t('driveEnrol.badMessage'));
        return;
      }
      this.requestText.set(text.trim());
    } catch {
      this.error.set(this.i18n.t('driveEnrol.cameraMissing'));
    } finally {
      for (const track of stream.getTracks()) track.stop();
    }
  }

  /** The connected browser takes the 8-digit approver role. */
  protected becomeApprover(): void {
    this.error.set(null);
    this.approved.set(false);
    this.code.set(null);
    this.compared = null;
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

  /** The connected browser answers the pasted request with its own public key and a fresh nonce. */
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

  /**
   * The new browser opens its commitment (it reveals its nonce) and shows the 8-digit code both browsers must show the
   * same.
   */
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

  /**
   * The connected browser checks the revealed message and shows its 8-digit code, and remembers exactly which fields it
   * showed it for.
   */
  protected showApproverCode(): void {
    this.error.set(null);
    this.compared = null;
    let revealed: PairingMessage;
    try {
      revealed = JSON.parse(this.replyText()) as PairingMessage;
    } catch {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const out = codeOf(revealed, Date.now());
    if (!out.ok) {
      this.code.set(null);
      this.error.set(this.i18n.t(out.reason === 'EXPIRED' ? 'driveEnrol.expired' : 'driveEnrol.mismatch'));
      return;
    }
    const fields = pairingFields(revealed);
    if (!fields) {
      this.code.set(null);
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    this.compared = fields;
    this.code.set(out.code);
  }

  /**
   * The connected browser, after the person has compared the codes: list this public key and hand back its wrap.
   * The public key is the one in the transcript just shown. A later paste, or a transcript that replaced this
   * browser's nonce or public key, is refused. An 8-digit collision is not enough.
   */
  protected async confirmNumbers(): Promise<void> {
    this.error.set(null);
    const revealed = parseMessage(this.replyText());
    const fields = revealed ? pairingFields(revealed) : null;
    const snap = this.compared;
    if (!revealed || !fields || !snap || !this.code() || !this.nApprover) {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    if (!samePairingFields(fields, snap)) {
      this.error.set(this.i18n.t('driveEnrol.mismatch'));
      return;
    }
    const checked = codeOf(revealed, Date.now());
    if (!checked.ok || checked.code !== this.code()) {
      this.error.set(this.i18n.t(!checked.ok && checked.reason === 'EXPIRED' ? 'driveEnrol.expired' : 'driveEnrol.mismatch'));
      return;
    }
    const nA = unb64(fields.nApprover);
    const pkA = unb64(fields.pkApprover);
    const mine = await this.publicKey();
    if (!nA || !pkA || !mine || !constantTimeEquals(nA, this.nApprover) || !constantTimeEquals(pkA, mine)) {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return;
    }
    const pk = unb64(fields.pkNew);
    if (!pk) {
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

  /** This browser's device public key; null (with an error shown) if it cannot be made. */
  private async publicKey(): Promise<Uint8Array | null> {
    try {
      return await this.service.devicePublicKey();
    } catch {
      this.error.set(this.i18n.t('driveEnrol.badMessage'));
      return null;
    }
  }

  /**
   * The translation key for a refusal: a ready-made `drive...` key is kept, a passkey requirement gets its own
   * sentence, anything else is a bad message.
   */
  private reasonKey(reason: string): TKey {
    if (reason === 'USE_PHONE') return 'driveEnrol.passkeyNeeded';
    if (reason.startsWith('drive')) return reason as TKey;
    return 'driveEnrol.badMessage';
  }
}

interface BarcodeHit {
  rawValue: string;
}
interface BarcodeDetectorCtor {
  new (options: { formats: string[] }): { detect(source: HTMLVideoElement): Promise<BarcodeHit[]> };
}

/** The browser's `BarcodeDetector`, or undefined where there is none. */
function barcodeDetector(): BarcodeDetectorCtor | undefined {
  return (globalThis as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector;
}

/** Reads pasted JSON as a pairing message; null for anything that is not an object. */
function parseMessage(text: string): PairingMessage | null {
  try {
    const msg = JSON.parse(text) as PairingMessage;
    return msg && typeof msg === 'object' ? msg : null;
  } catch {
    return null;
  }
}

interface PairingFields {
  pkNew: string;
  nNew: string;
  nApprover: string;
  pkApprover: string;
  commit: string;
}

/** The five fields that make up the transcript behind the 8-digit code, or null if any is missing. */
function pairingFields(msg: PairingMessage): PairingFields | null {
  if (!msg.pkNew || !msg.nNew || !msg.nApprover || !msg.pkApprover || !msg.commit) return null;
  return {
    pkNew: msg.pkNew,
    nNew: msg.nNew,
    nApprover: msg.nApprover,
    pkApprover: msg.pkApprover,
    commit: msg.commit,
  };
}

/** Whether two transcripts are identical field by field. */
function samePairingFields(a: PairingFields, b: PairingFields): boolean {
  return a.pkNew === b.pkNew && a.nNew === b.nNew && a.nApprover === b.nApprover && a.pkApprover === b.pkApprover && a.commit === b.commit;
}
