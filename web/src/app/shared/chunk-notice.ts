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

import { Component, inject } from '@angular/core';
import { ChunkRecovery } from '../core/chunk-recovery';
import { TPipe } from '../i18n/t.pipe';

/**
 * The message when a page's code could not be loaded even after the automatic retry (core/chunk-recovery.ts): a
 * non-blocking strip under the banners with `role="alert"` (read once when it appears), the plain words in the
 * person's language, *Try again* (the same navigation) and *Reload*. Nothing is rendered otherwise.
 */
@Component({
  selector: 'app-chunk-notice',
  imports: [TPipe],
  template: `
    @if (recovery.failedUrl()) {
      <div class="notice" role="alert">
        <p>{{ 'error.pageLoad' | t }}</p>
        <div class="actions">
          <button type="button" class="btn btn-primary btn-sm" (click)="recovery.retry()">{{ 'common.retry' | t }}</button>
          <button type="button" class="btn btn-sm" (click)="recovery.reload()">{{ 'pwa.reload' | t }}</button>
        </div>
      </div>
    }
  `,
  styles: `
    .notice {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2) var(--space-4);
      padding: var(--space-3) max(var(--space-4), env(safe-area-inset-right)) var(--space-3)
        max(var(--space-4), env(safe-area-inset-left));
      background: var(--surface);
      border-inline-start: 4px solid var(--error-text);
      border-bottom: 1px solid var(--border);
      color: var(--text);
    }
    p {
      margin: 0;
      flex: 1 1 16rem;
      font-size: var(--text-sm);
    }
    .actions {
      display: flex;
      gap: var(--space-2);
    }
  `,
})
export class ChunkNotice {
  protected readonly recovery = inject(ChunkRecovery);
}
