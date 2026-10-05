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

import { Component, ChangeDetectionStrategy, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TPipe } from '../../../i18n/t.pipe';
import { TranslationService } from '../../../i18n/translation.service';
import type { TKey } from '../../../i18n/en';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';

/**
 * Passkey setup for deletion authorization on this device.
 * Shown when state is 'Ready'.
 * Displays passkey status and allows registration if not yet set up.
 */
@Component({
  selector: 'app-drive-passkey',
  standalone: true,
  imports: [CommonModule, TPipe],
  templateUrl: './drive-passkey.html',
  styleUrl: './drive-passkey.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DrivePasskeyComponent implements OnInit {
  private readonly service = inject(DriveConnectService);
  protected readonly i18n = inject(TranslationService);

  protected readonly status = signal<'none' | 'registered' | 'unsupported' | 'loading'>('loading');
  protected readonly busy = signal(false);
  protected readonly errorMessage = signal<TKey | null>(null);
  /** True when the browser reports it cannot use the PRF extension (shown above help paragraph). */
  protected readonly prfHeadsUp = signal(false);

  async ngOnInit(): Promise<void> {
    await this.loadStatus();
  }

  private async loadStatus(): Promise<void> {
    try {
      const result = await this.service.passkeyStatus();
      this.status.set(result || 'unsupported');
      // Check PRF capability only when status is 'none' (not yet set up)
      if (result === 'none') {
        const capability = await this.service.passkeyPrfCapability();
        if (capability === false) {
          this.prfHeadsUp.set(true);
          this.details.set('capability: extension:prf=false');
        }
      }
    } catch {
      this.errorMessage.set('driveConnect.failed');
      this.status.set('unsupported');
    }
  }

  /**
   * Replace the button only when this browser has no platform authenticator.
   * A PRF miss on a computer that can still show a prompt stays on *Set up a passkey*.
   */
  private async applyUnsupported(): Promise<void> {
    try {
      const status = await this.service.passkeyStatus();
      if (status === 'unsupported') {
        this.status.set('unsupported');
        return;
      }
      if (status === 'registered') {
        this.status.set('registered');
        return;
      }
      this.status.set('none');
      this.errorMessage.set('drivePasskey.registerFailed');
    } catch {
      this.errorMessage.set('drivePasskey.registerFailed');
      if (this.status() !== 'registered') this.status.set('none');
    }
  }

  /** True after a setup that returned no PRF output: the card then explains what works and offers the details. */
  protected readonly noPrfHelp = signal(false);
  /** Where that setup stopped (step names and flags only, never a value), to copy and send. */
  protected readonly details = signal<string | null>(null);
  protected readonly detailsCopied = signal(false);

  async copyDetails(): Promise<void> {
    const text = this.details();
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      this.detailsCopied.set(true);
    } catch {
      // The text stays on screen to select by hand.
      this.detailsCopied.set(false);
    }
  }

  async onRegisterPasskey(): Promise<void> {
    if (this.busy()) return;

    this.busy.set(true);
    this.errorMessage.set(null);
    this.noPrfHelp.set(false);
    this.prfHeadsUp.set(false);
    this.details.set(null);
    this.detailsCopied.set(false);

    try {
      const result = await this.service.registerPasskey();
      if (result === 'registered') {
        this.status.set('registered');
        this.errorMessage.set(null);
        await this.loadStatus();
      } else if (result === null) {
        // The person dismissed the prompt. Leave *Set up a passkey* and say so.
        this.errorMessage.set('drivePasskey.registerCancelled');
        if (this.status() !== 'registered') this.status.set('none');
      } else if (result === 'unsupported') {
        await this.applyUnsupported();
      } else if (result === 'no-prf') {
        // The PIN can succeed and still leave no PRF output. That is not "no platform authenticator".
        this.errorMessage.set('drivePasskey.registerNoPrf');
        this.noPrfHelp.set(true);
        this.details.set(await this.service.passkeyDetails());
        if (this.status() !== 'registered') this.status.set('none');
      } else {
        this.errorMessage.set('drivePasskey.registerFailed');
        if (this.status() !== 'registered') this.status.set('none');
      }
    } catch {
      this.errorMessage.set('drivePasskey.registerFailed');
      if (this.status() !== 'registered') this.status.set('none');
    } finally {
      this.busy.set(false);
    }
  }
}
