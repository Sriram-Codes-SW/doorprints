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

import { Component, computed, inject, signal } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import { BulkGuard } from '../../core/bulk-guard.service';
import { ConfirmService } from '../../core/confirm.service';
import { DeleteUndoService } from '../../core/delete-undo.service';
import { routesFor } from '../../app.routes';
import {
  availableKinds, customKindsOn, defaultKind, enabledKinds, experimentalKindsOn, kindById, offeredKinds, setCustomKinds, setDefaultKind,
  setExperimentalKinds, setKindEnabled, unreleasedKinds,
} from '../../core/kind-setting';
import { builtInKindIds } from '../../core/dev-kinds';
import { USER_KIND_LIMITS, readUserKind, type UserKindFault, type UserKindFile } from '../../core/user-kinds/user-kind-file';
import { isUserKindId } from '../../core/user-kinds/user-kind-ids';
import { removeUserKind, saveUserKind, userKindFile } from '../../core/user-kinds/user-kinds';
import type { TKey } from '../../i18n/en';
import type { Msg } from '../../i18n/translation.service';
import { LocalDataService } from '../../core/local-data.service';
import { SpaceService, spaceOf } from '../../core/space.service';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import { baselineKindId } from '../../core/candidate';

/** A space with more rows than this wants a tick box before *Delete all in this space*. */
export const TICK_OVER = 10;

/** One row of Settings > Kinds: a kind offered here, whether it is shown, whether it is the default, its entries. */
interface KindRow {
  id: string;
  label: string;
  on: boolean;
  isDefault: boolean;
  count: number;
  /** A kind the person added from a kind file (docs/03 ADR-39): it can be removed here. */
  yours: boolean;
}

/** A checked kind file waiting for *Add this kind* (docs/03 ADR-39): its words and whether it replaces one already added. */
interface KindPreview {
  file: UserKindFile;
  fields: string[];
  checks: string[];
  replaces: string | null;
}

/**
 * Settings > Kinds on *Your data* (kinds PR 8, S4b-BL-212; docs/03 §18.12). Drawn only in a build that offers more than
 * the house or ships an unreleased kind; a production build has neither, so a house hunter's page is as it was.
 * *Experimental: more than houses* (off by default) is shown only while the build ships an unreleased kind and is the only
 * way to see one. Then one row per offered kind: *Show* (the last kind shown cannot be turned off), *Default* (only for
 * a shown kind) and how many are saved. Turning a kind off hides nothing silently: its entries stay, and the chooser
 * lists it as hidden. Everything is this browser's own; a change takes effect at once (the router gets the spaces).
 */
@Component({
  selector: 'app-kinds-settings',
  imports: [TPipe],
  template: `
    @if (visible()) {
      <section class="card" aria-labelledby="kinds-heading">
        <h2 id="kinds-heading" tabindex="-1">{{ 'kinds.heading' | t }}</h2>
        <p class="muted">{{ 'kinds.body' | t }}</p>
        @if (unreleased.length) {
          <div class="field checkbox">
            <input id="kinds-experimental" type="checkbox" [checked]="experimental()" (change)="setSwitch($any($event.target).checked)" aria-describedby="kinds-experimental-hint" />
            <label for="kinds-experimental">{{ 'kinds.switch' | t }}</label>
            <p id="kinds-experimental-hint" class="muted small">{{ 'kinds.switchHint' | t }}</p>
          </div>
        }
        <div class="field checkbox">
          <input id="kinds-custom" type="checkbox" [checked]="custom()" (change)="setCustom($any($event.target).checked)" aria-describedby="kinds-custom-hint" />
          <label for="kinds-custom">{{ 'userKinds.switch' | t }}</label>
          <p id="kinds-custom-hint" class="muted small">{{ 'userKinds.switchHint' | t }}</p>
        </div>
        @if (custom()) {
          <div class="user-kinds" role="group" aria-labelledby="user-kinds-heading">
            <h3 id="user-kinds-heading">{{ 'userKinds.heading' | t }}</h3>
            <p id="user-kinds-hint" class="muted small">{{ 'userKinds.importHint' | t }}</p>
            <div class="field">
              <label for="user-kind-file">{{ 'userKinds.import' | t }}</label>
              <input id="user-kind-file" type="file" accept=".json,application/json" aria-describedby="user-kinds-hint" [disabled]="reading()" (change)="pick($event)" />
            </div>
            @if (reading()) {
              <p role="status">{{ 'userKinds.reading' | t }}</p>
            }
            @if (refusal(); as r) {
              <div role="alert" id="user-kind-refusal">
                <p class="error">{{ 'userKinds.refused' | t: { reason: i18n.t(reasonKey(r)) } }}</p>
                @if (r.path !== '$') {
                  <p class="muted small">{{ 'userKinds.where' | t: { path: r.path } }}</p>
                }
              </div>
            }
            @if (preview(); as p) {
              <div class="preview" id="user-kind-preview" role="region" aria-labelledby="user-kind-preview-title">
                <h4 id="user-kind-preview-title" tabindex="-1">{{ 'userKinds.previewTitle' | t }}</h4>
                <p>{{ 'userKinds.previewName' | t: { many: p.file.labels.many.text } }}</p>
                @if (p.fields.length) {
                  <p>{{ 'userKinds.previewFields' | t: { list: i18n.list(p.fields) } }}</p>
                } @else {
                  <p>{{ 'userKinds.previewNoFields' | t }}</p>
                }
                @if (p.checks.length) {
                  <p>{{ 'userKinds.previewChecklist' | t: { list: i18n.list(p.checks) } }}</p>
                }
                @if (p.replaces; as old) {
                  <p class="warn-box">{{ 'userKinds.previewReplaces' | t: { many: old } }}</p>
                }
                <div class="actions">
                  <button type="button" class="btn btn-primary" id="user-kind-add" (click)="add()">{{ (p.replaces ? 'userKinds.replace' : 'userKinds.add') | t }}</button>
                  <button type="button" class="btn" id="user-kind-cancel" (click)="cancel()">{{ 'userKinds.cancel' | t }}</button>
                </div>
              </div>
            }
            @if (notice(); as n) {
              <p role="status" id="user-kind-notice">{{ n.key | t: n.params }}</p>
            }
          </div>
        }
        @if (rows().length > 1) {
          <ul id="kind-rows" class="rows" role="list" aria-describedby="kinds-default-hint">
            @for (r of rows(); track r.id) {
              <li class="row">
                <p class="name"><strong>{{ r.label }}</strong>&ngsp;<span class="muted small">{{ 'kinds.count' | t: { n: i18n.number(r.count) } }}</span>@if (r.yours) {&ngsp;<span class="badge small">{{ 'userKinds.yours' | t }}</span>}</p>
                <div class="field checkbox">
                  <input type="checkbox" [id]="'kind-on-' + r.id" [checked]="r.on" [disabled]="r.on && shownCount() === 1" (change)="setOn(r.id, $any($event.target).checked)" />
                  <label [for]="'kind-on-' + r.id">{{ 'kinds.show' | t: { kind: r.label } }}</label>
                </div>
                @if (r.yours) {
                  <div class="field">
                    <button type="button" class="btn" [id]="'kind-remove-' + r.id" [attr.aria-label]="'userKinds.removeAria' | t: { kind: r.label }" (click)="remove(r)">
                      {{ 'userKinds.remove' | t }}
                    </button>
                  </div>
                }
                @if (r.count > 0) {
                  <div class="field">
                    <button type="button" class="btn btn-danger" [id]="'kind-delete-' + r.id" [attr.aria-label]="'delete.spaceButtonAria' | t: { kind: r.label }" (click)="deleteAll(r)">
                      {{ 'delete.spaceButton' | t }}
                    </button>
                  </div>
                }
                <div class="field checkbox">
                  <input type="radio" name="defaultKind" [id]="'kind-default-' + r.id" [checked]="r.isDefault" [disabled]="!r.on" [attr.aria-label]="'kinds.defaultAria' | t: { kind: r.label }" (change)="setDefault(r.id)" />
                  <label [for]="'kind-default-' + r.id" aria-hidden="true">{{ 'kinds.default' | t }}</label>
                </div>
              </li>
            }
          </ul>
          <p id="kinds-default-hint" class="muted small">{{ 'kinds.defaultHint' | t }} {{ 'kinds.lastOn' | t }}</p>
        }
      </section>
    }
  `,
  styles: `
    /* No box of its own: in a build without other kinds the page's layout is as it was. */
    :host { display: contents; }
    .rows { list-style: none; margin: var(--space-3) 0 0; padding: 0; display: grid; gap: var(--space-2); }
    .row { display: grid; grid-template-columns: minmax(0, 1fr); gap: 0 var(--space-3); padding: var(--space-2) 0; border-top: 1px solid var(--border); }
    .name { margin: 0; overflow-wrap: anywhere; }
    .user-kinds, .preview { display: grid; gap: var(--space-2); margin-top: var(--space-3); }
    .user-kinds h3, .preview h4 { margin: 0; }
    .preview { padding: var(--space-3); border: 1px solid var(--border); border-radius: 8px; overflow-wrap: anywhere; }
    .preview p { margin: 0; }
    .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
    .badge { padding: 0 var(--space-1, 4px); border: 1px solid var(--border); border-radius: 999px; white-space: nowrap; }
    @media (min-width: 40rem) { .row { grid-template-columns: minmax(0, 1fr) auto auto auto; align-items: center; } }
  `,
})
export class KindsSettings {
  private readonly data = inject(LocalDataService);
  private readonly space = inject(SpaceService);
  private readonly confirm = inject(ConfirmService);
  private readonly undo = inject(DeleteUndoService);
  private readonly bulk = inject(BulkGuard);
  private readonly announcer = inject(Announcer);
  protected readonly i18n = inject(TranslationService);
  private readonly available = availableKinds();
  protected readonly unreleased = unreleasedKinds(this.available);
  /** Bumped after every change, so the rows read the settings again. */
  private readonly version = signal(0);
  private readonly counts = signal<ReadonlyMap<string, number>>(new Map());
  /** The live candidates of each space (by id), the ids a card's *Delete all in this space* takes and no others. */
  private readonly ids = signal<ReadonlyMap<string, readonly string[]>>(new Map());

  protected readonly experimental = computed(() => (this.version(), experimentalKindsOn()));
  protected readonly custom = computed(() => (this.version(), customKindsOn()));
  protected readonly reading = signal(false);
  protected readonly preview = signal<KindPreview | null>(null);
  protected readonly refusal = signal<UserKindFault | null>(null);
  protected readonly notice = signal<Msg | null>(null);
  protected readonly visible = computed(() => this.unreleased.length > 0 || this.available.length > 1);
  protected readonly rows = computed<KindRow[]>(() => {
    this.version();
    // The kinds a person added change while the page is open (an import, a removal): read them again on every change.
    const available = availableKinds();
    const enabled = enabledKinds(available);
    const dflt = defaultKind(available);
    return offeredKinds(available).map((id) => ({
      id,
      label: this.i18n.t(kindById(id).keys.ref('kind.many')),
      on: enabled.includes(id),
      isDefault: id === dflt,
      count: this.counts().get(id) ?? 0,
      yours: isUserKindId(id),
    }));
  });
  protected readonly shownCount = computed(() => this.rows().filter((r) => r.on).length);

  constructor() {
    this.data.houses().subscribe({
      next: (list) => {
        const m = new Map<string, string[]>();
        for (const h of list) {
          if (h.deleted) continue;
          const id = spaceOf(h, true, baselineKindId(), availableKinds());
          m.set(id, [...(m.get(id) ?? []), h.id]);
        }
        this.ids.set(m);
        this.counts.set(new Map([...m].map(([k, v]) => [k, v.length])));
      },
      error: () => {
        this.ids.set(new Map());
        this.counts.set(new Map());
      },
    });
  }

  /**
   * *Delete all in this space* on one card (deletion safeguards PR 2, docs/02 T-D15): only this space's candidates, as
   * tombstones in one batch, then the same ten seconds of *Undo* as one delete. The question names the count and the kind;
   * over {@link TICK_OVER} rows it also wants a tick; *Save a copy first* goes to the copies instead of deleting. Focus
   * starts on Cancel (a danger dialog). One bulk action per 10 seconds per device ({@link BulkGuard}).
   */
  protected async deleteAll(row: KindRow): Promise<void> {
    const ids = this.ids().get(row.id) ?? [];
    if (!ids.length) return;
    const wait = this.bulk.waitSeconds();
    if (wait > 0) {
      this.announcer.announce({ key: 'delete.bulkWait', params: { n: wait } });
      return;
    }
    const answer = await this.confirm.choose(
      { key: 'delete.spaceConfirm', params: { n: this.i18n.number(ids.length), kind: row.label } },
      { confirmKey: 'delete.spaceDo', altKey: 'driveDelete.saveCopyFirst', danger: true, focusCancel: true, tickKey: ids.length > TICK_OVER ? 'driveDelete.confirmCheckbox' : null },
    );
    if (answer === 'alt') {
      document.getElementById('export-heading')?.scrollIntoView?.({ block: 'start' });
      document.getElementById('export-heading')?.focus();
      return;
    }
    if (answer !== 'confirm') return;
    if (!this.bulk.tryStart()) return;
    await this.undo.removeMany(ids, row.label);
    document.getElementById('kinds-heading')?.focus();
  }

  /** The key of a refusal's plain words. */
  protected reasonKey(f: UserKindFault): TKey {
    return `userKinds.err.${f.code}` as TKey;
  }

  /**
   * *Import a kind file* (docs/03 ADR-39): a file over 64 KB is refused before it is read; the text is checked by the same
   * reader as the phones' and the server's (`user-kind-file.ts`), and nothing is stored until the person confirms the preview.
   */
  protected async pick(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.preview.set(null);
    this.refusal.set(null);
    this.notice.set(null);
    if (!file) return;
    if (file.size > USER_KIND_LIMITS.maxBytes) {
      this.refusal.set({ code: 'tooBig', path: '$' });
      return;
    }
    this.reading.set(true);
    let text = '';
    try {
      text = await file.text();
    } catch {
      text = '';
    }
    this.reading.set(false);
    try {
      input.value = '';
    } catch {
      // A test's input: nothing to clear.
    }
    const read = readUserKind(text, builtInKindIds(true));
    if (!read.ok) {
      this.refusal.set(read.fault);
      return;
    }
    const old = userKindFile(read.file.id);
    this.preview.set({
      file: read.file,
      fields: read.file.extras.map((e) => e.label.text),
      checks: read.file.checklist.map((c) => c.label.text),
      replaces: old ? old.labels.many.text : null,
    });
    queueMicrotask(() => document.getElementById('user-kind-preview-title')?.focus());
  }

  /** Stores the previewed kind (or replaces the one with its id); it is available at once and shown once turned on below. */
  protected add(): void {
    const p = this.preview();
    if (!p) return;
    const result = saveUserKind(p.file);
    const many = p.file.labels.many.text;
    const msg: Msg = result === 'added' ? { key: 'userKinds.added', params: { many } }
      : result === 'replaced' ? { key: 'userKinds.replaced', params: { many } }
      : result === 'full' ? { key: 'userKinds.full', params: { n: USER_KIND_LIMITS.maxKinds } }
      : { key: 'userKinds.storage' };
    this.preview.set(null);
    this.notice.set(msg);
    this.announcer.announce(msg);
    this.changed();
  }

  protected cancel(): void {
    this.preview.set(null);
    document.getElementById('user-kind-file')?.focus();
  }

  /**
   * *Remove kind*: its file only, after the person confirms (a danger dialog, focus on Cancel, *Save a copy first*). Its entries are
   * never deleted here: they stay, in the default space, until the kind is imported again; *Delete all in this space* is how to
   * delete them (with its own confirmation, Undo and Recently deleted).
   */
  protected async remove(row: KindRow): Promise<void> {
    const answer = await this.confirm.choose(
      { key: 'userKinds.removeConfirm', params: { kind: row.label, n: this.i18n.number(row.count) } },
      { confirmKey: 'userKinds.removeDo', altKey: 'driveDelete.saveCopyFirst', danger: true, focusCancel: true },
    );
    if (answer === 'alt') {
      document.getElementById('export-heading')?.scrollIntoView?.({ block: 'start' });
      document.getElementById('export-heading')?.focus();
      return;
    }
    if (answer !== 'confirm') return;
    if (row.on) setKindEnabled(row.id, false, availableKinds());
    if (removeUserKind(row.id)) {
      const msg: Msg = { key: 'userKinds.removed', params: { kind: row.label } };
      this.notice.set(msg);
      this.announcer.announce(msg);
    }
    this.changed();
    document.getElementById('kinds-heading')?.focus();
  }

  protected setCustom(on: boolean): void {
    setCustomKinds(on);
    if (!on) {
      this.preview.set(null);
      this.refusal.set(null);
    }
    this.changed();
  }

  protected setSwitch(on: boolean): void {
    setExperimentalKinds(on);
    this.changed();
  }

  protected setOn(id: string, on: boolean): void {
    setKindEnabled(id, on, availableKinds());
    this.changed();
  }

  protected setDefault(id: string): void {
    setDefaultKind(id, availableKinds());
    this.changed();
  }

  private changed(): void {
    this.version.update((v) => v + 1);
    this.space.refresh((multi) => routesFor(multi));
  }
}
