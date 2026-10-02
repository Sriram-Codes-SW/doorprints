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

import { Component, ChangeDetectionStrategy, inject, signal, output, ViewChild, ElementRef, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TPipe } from '../../../i18n/t.pipe';
import { TranslationService } from '../../../i18n/translation.service';
import type { TKey } from '../../../i18n/en';
import { DriveConnectService } from '../../../data/drive/connect/drive-connect.service';

/**
 * Join a Doorprints Google Drive folder using a recovery key (26 characters in groups).
 * Shown when state is 'NeedsEnrolment' or 'NeedsRecoveryKey'.
 * Emits 'joined' on successful connection.
 */
@Component({
  selector: 'app-drive-join',
  standalone: true,
  imports: [CommonModule, FormsModule, TPipe],
  templateUrl: './drive-join.html',
  styleUrl: './drive-join.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DriveJoinComponent implements AfterViewInit {
  private readonly service = inject(DriveConnectService);
  protected readonly i18n = inject(TranslationService);

  @ViewChild('keyInput') keyInput?: ElementRef<HTMLInputElement>;

  protected readonly recoveryKeyText = signal('');
  protected readonly busy = signal(false);
  protected readonly errorMessage = signal<TKey | null>(null);

  readonly joined = output<void>();

  ngAfterViewInit(): void {
    // Focus on the input field for better accessibility
    this.keyInput?.nativeElement.focus();
  }

  async onJoin(): Promise<void> {
    if (this.busy()) return;

    const text = this.recoveryKeyText().trim();
    if (!text) {
      this.errorMessage.set('driveJoin.errorEmpty');
      return;
    }

    this.busy.set(true);
    this.errorMessage.set(null);

    try {
      const result = await this.service.openWithRecoveryKey(text);
      if (result.state === 'Ready') {
        this.recoveryKeyText.set('');
        this.errorMessage.set(null);
        this.joined.emit();
      } else if (result.error) {
        this.errorMessage.set(result.error);
      }
    } catch {
      this.errorMessage.set('driveConnect.failed');
    } finally {
      this.busy.set(false);
    }
  }

  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !this.busy()) {
      event.preventDefault();
      void this.onJoin();
    }
  }

  protected onKeyInput(event: Event): void {
    const el = event.target;
    this.recoveryKeyText.set(el instanceof HTMLInputElement ? el.value : '');
  }

  async onDisconnect(): Promise<void> {
    this.busy.set(true);
    try {
      await this.service.disconnect();
    } finally {
      this.busy.set(false);
    }
  }
}
