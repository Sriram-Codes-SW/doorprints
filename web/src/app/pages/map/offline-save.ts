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

/**
 * *Save this area for offline* on the Map (docs/11 5.20, S4b-BL-79): a control among the map's actions and the dialog
 * it opens, the website's counterpart of Android's download control. The dialog shows the size to download and the room
 * the browser has, warns on mobile data, takes a name, refuses a box over the cap with the numbers, and then shows the
 * progress with *Stop*. The download itself is `OfflineMapsService`.
 */

import { Component, ElementRef, afterRenderEffect, computed, inject, input, signal, viewChild } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { OfflineMapsService } from '../../offline/offline-maps.service';
import type { SaveOutcome, SavePlan } from '../../offline/offline-maps.service';
import { MAX_AREAS, MAX_TILES, megabyteDigits, megabytes } from '../../offline/offline-tiles';
import type { GeoBounds } from '../../offline/offline-tiles';

type Phase = 'plan' | 'saving' | 'done';

/** The sentence for a save that did not finish as saved, or null for one that did. */
export function outcomeMessage(outcome: SaveOutcome, name: string): Msg | null {
  switch (outcome) {
    case 'saved':
      return null;
    case 'cancelled':
      return { key: 'offline.cancelled', params: { name } };
    case 'offline':
      return { key: 'offline.needNetwork' };
    case 'unsupported':
      return { key: 'offline.unsupported' };
    default:
      // 'failed', and the refusals the dialog already showed before the download started.
      return { key: 'offline.failed', params: { name } };
  }
}

/**
 * The *Save this area for offline* button and dialog on the Map: plan (size, free room, refusals), name, progress with
 * Stop, and the result. The download itself is `OfflineMapsService`; nothing is kept if it is stopped.
 */
@Component({
  selector: 'app-offline-save',
  imports: [TPipe],
  host: { class: 'offline-save' },
  template: `
    @if (offline.supported()) {
      <button type="button" class="btn" [title]="'offline.save' | t" (click)="open()">
        <span class="icon" aria-hidden="true">⇩</span>
        <span class="label">{{ 'offline.save' | t }}</span>
      </button>
    }
    <dialog #dlg class="offline" aria-labelledby="offline-title" (cancel)="onCancel($event)" (close)="onClose()">
      <h2 id="offline-title">{{ 'offline.save' | t }}</h2>
      @switch (phase()) {
        @case ('plan') {
          @if (plan(); as p) {
            <p>{{ 'offline.dialogText' | t: { mb: mb(p.bytes) } }}</p>
            @if (p.free !== null && !p.refusal) {
              <p class="muted small">{{ 'offline.storageFree' | t: { mb: mb(p.free) } }}</p>
            }
            @if (p.metered) {
              <p class="warn-box small">{{ 'offline.metered' | t }}</p>
            }
            <div role="alert">
              @if (refusalText(p); as m) {
                <p class="error">{{ m.key | t: m.params }}</p>
              }
              @if (!p.online) {
                <p class="error">{{ 'offline.needNetwork' | t }}</p>
              }
            </div>
            <div class="field">
              <label for="offline-name">{{ 'offline.nameLabel' | t }}</label>
              <input id="offline-name" type="text" maxlength="60" autocomplete="off" [value]="name()" (input)="onName($event)" />
            </div>
            <div class="actions">
              <button type="button" class="btn" (click)="close()">{{ 'common.cancel' | t }}</button>
              <button type="button" class="btn btn-primary" [disabled]="!!p.refusal || !p.online" (click)="start()">
                {{ 'offline.start' | t }}
              </button>
            </div>
          } @else {
            <p>{{ 'common.loading' | t }}</p>
          }
        }
        @case ('saving') {
          @if (offline.progress(); as pr) {
            <!-- Not a live region: the start is announced once, so a screen reader is not reading counts all the way. -->
            <p>{{ 'offline.progress' | t: { name: pr.name, done: i18n.number(pr.done), total: i18n.number(pr.total), mb: mb(pr.bytes) } }}</p>
            <progress [value]="pr.done" [max]="pr.total || 1" [attr.aria-label]="'offline.save' | t"></progress>
          } @else {
            <p>{{ 'common.loading' | t }}</p>
          }
          <div class="actions">
            <button type="button" class="btn" (click)="stop()">{{ 'offline.stop' | t }}</button>
          </div>
        }
        @case ('done') {
          <div role="status">
            @if (result(); as r) {
              <p [class.error]="r.failed">{{ r.msg.key | t: r.msg.params }}</p>
            }
          </div>
          <div class="actions">
            <button type="button" class="btn btn-primary" id="offline-done" (click)="close()">{{ 'common.close' | t }}</button>
          </div>
        }
      }
    </dialog>
  `,
  styles: `
    :host {
      display: inline-flex;
      /* The actions row of the map page lets no pointer through; this control takes it back. */
      pointer-events: auto;
    }
    .btn {
      box-shadow: var(--shadow-lg);
    }
    /* Phones: the glyph alone, still a 44px target with its name for assistive technology (the tooltip is the title). */
    @media (max-width: 760px) {
      .label {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
        white-space: nowrap;
      }
    }
    dialog.offline {
      width: min(30rem, calc(100vw - 2 * var(--space-4)));
      padding: var(--space-5);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface);
      color: var(--text);
    }
    dialog.offline::backdrop {
      background: rgba(0, 0, 0, 0.5);
    }
    h2 {
      margin: 0 0 var(--space-3);
      font-size: var(--text-lg);
    }
    p {
      margin: 0 0 var(--space-3);
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      margin-bottom: var(--space-4);
    }
    progress {
      width: 100%;
      height: 1rem;
      margin-bottom: var(--space-4);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-2);
    }
    @media (max-width: 480px) {
      .actions {
        flex-direction: column;
        align-items: stretch;
      }
      .actions .btn-primary {
        order: -1;
      }
    }
  `,
})
export class OfflineSave {
  protected readonly offline = inject(OfflineMapsService);
  protected readonly i18n = inject(TranslationService);
  private readonly announcer = inject(Announcer);

  /** The box on the map's screen now (read when the dialog opens), or null when there is no map. */
  readonly boundsOf = input.required<() => GeoBounds | null>();

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  protected readonly phase = signal<Phase>('plan');
  protected readonly plan = signal<SavePlan | null>(null);
  protected readonly name = signal('');
  protected readonly result = signal<{ msg: Msg; failed: boolean } | null>(null);
  private bounds: GeoBounds | null = null;
  private opener: HTMLElement | null = null;
  /** Esc and Close while the dialog is open are answered here: `true` once the dialog is to be shown. */
  private readonly shown = signal(false);
  /** What should hold focus now: the field once the numbers are in, Stop while saving, Close at the end; null when shut. */
  private readonly focusTarget = computed(() => {
    if (!this.shown()) return null;
    const phase = this.phase();
    if (phase === 'plan') return this.plan() ? '#offline-name' : null;
    return phase === 'done' ? '#offline-done' : 'button';
  });

  constructor() {
    // After render, so the field exists when focus goes to it.
    afterRenderEffect(() => {
      const target = this.focusTarget();
      if (target) this.dialog().nativeElement.querySelector<HTMLElement>(target)?.focus();
    });
  }

  protected mb(bytes: number): string {
    return this.i18n.number(megabytes(bytes), megabyteDigits(bytes));
  }

  /** The sentence for a plan that cannot go ahead (too large, too many areas, not enough room), or null. */
  protected refusalText(p: SavePlan): Msg | null {
    switch (p.refusal) {
      case 'too-large':
        return { key: 'offline.tooLarge', params: { tiles: this.i18n.number(p.tiles), limit: this.i18n.number(MAX_TILES) } };
      case 'too-many':
        return { key: 'offline.tooMany', params: { n: MAX_AREAS } };
      case 'no-room':
        return { key: 'offline.noRoom', params: { mb: this.mb(p.bytes), free: this.mb(p.free ?? 0) } };
      default:
        return null;
    }
  }

  /** Opens the dialog for the box now on screen and works out the plan (tiles, bytes, room, connection). */
  protected async open(): Promise<void> {
    const bounds = this.boundsOf()();
    if (!bounds) return;
    this.bounds = bounds;
    this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.phase.set('plan');
    this.plan.set(null);
    this.result.set(null);
    this.name.set(this.i18n.t('offline.defaultName'));
    const dlg = this.dialog().nativeElement;
    if (!dlg.open) dlg.showModal();
    this.shown.set(true);
    this.plan.set(await this.offline.plan(bounds));
  }

  protected onName(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value);
  }

  /** Downloads the area under the chosen name, unless the plan is refused or the device is offline. */
  protected async start(): Promise<void> {
    const bounds = this.bounds;
    const p = this.plan();
    if (!bounds || !p || p.refusal || !p.online) return;
    const name = this.name().trim() || this.i18n.t('offline.defaultName');
    this.phase.set('saving');
    this.announcer.announce({
      key: 'offline.progress',
      params: { name, done: 0, total: this.i18n.number(p.tiles), mb: this.mb(0) },
    });
    const outcome = await this.offline.save(name, bounds);
    const done = this.offline.areas().find((a) => a.name === name);
    this.finish(outcome, name, done?.bytes ?? 0);
  }

  /** Shows how the download ended; a stop closes the dialog and says nothing was kept. */
  private finish(outcome: SaveOutcome, name: string, bytes: number): void {
    if (outcome === 'cancelled') {
      // Stop closes the dialog, as Cancel does; the polite message says nothing was kept.
      this.announcer.announce({ key: 'offline.cancelled', params: { name } });
      this.close();
      return;
    }
    if (outcome === 'saved') {
      this.result.set({ msg: { key: 'offline.saved', params: { name, mb: this.mb(bytes) } }, failed: false });
    } else {
      const msg = outcomeMessage(outcome, name) ?? { key: 'offline.failed' as TKey, params: { name } };
      this.result.set({ msg, failed: true });
    }
    this.phase.set('done');
  }

  /** Stops the download; nothing half-saved is kept. */
  protected stop(): void {
    this.offline.cancel();
  }

  protected close(): void {
    const dlg = this.dialog().nativeElement;
    if (dlg.open) dlg.close();
  }

  /** Esc: while saving it stops the download (nothing half-saved is kept); otherwise it closes. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    if (this.phase() === 'saving') this.offline.cancel();
    else this.close();
  }

  protected onClose(): void {
    this.shown.set(false);
    this.opener?.focus();
    this.opener = null;
  }
}
