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

import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import type { PhotoSummary } from '../../core/local-data.service';
import { ROOM_TYPE_KEY, uuid } from '../../core/models';
import type { HouseDto, MoveIn, MoveInItem } from '../../core/models';
import { MAX_MOVE_IN_ITEMS, MAX_MOVE_IN_NOTES, MAX_MOVE_IN_TEXT } from '../../data/records';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';
import { AuthImage } from '../../shared/auth-image';
import { DEFAULT_MOVE_IN_ITEMS, addDefaults, orderedItems, progress } from '../../shared/move-in';

/** A photo of the condition record opened in the page's viewer. */
export interface OpenedPhoto {
  src: string;
  alt: string;
}

/**
 * The *Moving in* card of a TAKEN house (docs/11 5.24, slice 5): *Start moving in* (the six default items in the app's
 * language), the ticked list (tick, edit the text, remove, add your own, at most 30), the move-in date and notes, the
 * **condition record** (the photos tagged MOVE_IN grouped by room, each with its date and caption, and *Add a photo*
 * which takes the photo with MOVE_IN already chosen) and **Close this hunt**.
 *
 * The list, date and notes are part of the house being edited: changes go out through `moveInChange` and are saved
 * with the house. The photos are stored as soon as they are taken (as on the Photos card), so they need a saved house.
 */
@Component({
  selector: 'app-house-move-in-card',
  imports: [RouterLink, TPipe, AuthImage],
  // The photo input is not an edit of the house form; its change must not mark the page as having unsaved edits.
  host: { '(change)': 'onHostChange($event)' },
  template: `
    <section class="card" aria-labelledby="movein-heading">
      <h2 id="movein-heading">{{ 'movein.heading' | t }}</h2>
      <p class="muted small">{{ 'movein.intro' | t }}</p>

      <h3 id="movein-checklist-heading">{{ 'movein.checklist' | t }}</h3>
      @if (items().length > 0) {
        <p id="movein-summary" class="muted small" role="status">{{ 'movein.summary' | t: { done: counts().done, total: counts().total } }}</p>
      }
      @if (canStart()) {
        <p>
          <button type="button" id="movein-start" class="btn btn-primary" (click)="start()">{{ 'movein.start' | t }}</button>
        </p>
      }
      @if (items().length > 0) {
        <ul class="items" aria-labelledby="movein-checklist-heading">
          @for (it of items(); track it.id; let i = $index) {
            <li class="item">
              <input
                type="checkbox"
                class="tick"
                [id]="'movein-tick-' + it.id"
                [checked]="it.done === true"
                [attr.aria-label]="it.text"
                (change)="setDone(it.id, $any($event.target).checked)"
              />
              <input
                type="text"
                class="text"
                [id]="'movein-text-' + it.id"
                [value]="it.text"
                [maxLength]="maxText"
                [attr.aria-label]="'movein.itemEdit' | t: { n: i + 1 }"
                (input)="setText(it.id, $any($event.target).value)"
              />
              <button
                type="button"
                class="btn btn-sm remove"
                [attr.aria-label]="'movein.itemRemove' | t: { n: i + 1 }"
                [title]="'movein.itemRemove' | t: { n: i + 1 }"
                (click)="remove(it.id)"
              >
                <span aria-hidden="true">✕</span>
              </button>
            </li>
          }
        </ul>
      }
      <div class="field add">
        <label for="movein-new">{{ 'movein.itemNew' | t }}</label>
        <div class="inline">
          <input
            id="movein-new"
            type="text"
            [maxLength]="maxText"
            autocomplete="off"
            [value]="newText()"
            [disabled]="items().length >= maxItems"
            [attr.aria-describedby]="items().length >= maxItems ? 'movein-full' : null"
            (input)="newText.set($any($event.target).value)"
            (keydown.enter)="addOwn(); $event.preventDefault()"
          />
          <button type="button" class="btn" [disabled]="items().length >= maxItems || newText().trim() === ''" (click)="addOwn()">
            {{ 'movein.itemAdd' | t }}
          </button>
        </div>
        @if (items().length >= maxItems) {
          <span id="movein-full" class="field-hint">{{ 'movein.itemsFull' | t }}</span>
        }
      </div>

      <div class="row">
        <div class="field">
          <label for="movein-date">{{ 'movein.date' | t }}</label>
          <input id="movein-date" type="date" [value]="dateValue()" (change)="setDate($any($event.target).value)" />
        </div>
        <div class="field">
          <label for="movein-notes">{{ 'movein.notes' | t }}</label>
          <textarea id="movein-notes" rows="3" [maxLength]="maxNotes" [value]="moveIn()?.notes ?? ''" (input)="setNotes($any($event.target).value)"></textarea>
        </div>
      </div>

      <h3 id="movein-condition-heading">{{ 'movein.condition' | t }}</h3>
      <p class="muted small">{{ 'movein.conditionHelp' | t }}</p>
      @if (isNew()) {
        <p class="muted small">{{ 'movein.saveFirst' | t }}</p>
      } @else {
        @for (group of groups(); track group.key) {
          <h4 class="group">{{ group.name }}</h4>
          <ul class="condition">
            @for (p of group.photos; track p.id) {
              <li>
                <div class="thumb">
                  <app-auth-image [photoId]="p.id" [alt]="altOf(p)" (opened)="opened.emit({ src: $event, alt: altOf(p) })" />
                </div>
                <div class="info">
                  <span class="when">{{ i18n.dateTime(p.createdAt) }}</span>
                  @if (p.caption) {
                    <span class="caption">{{ p.caption }}</span>
                  }
                </div>
              </li>
            }
          </ul>
        } @empty {
          <p class="muted small">{{ 'movein.conditionEmpty' | t }}</p>
        }
        <label class="btn btn-sm upload">
          <span aria-hidden="true">＋</span> {{ 'movein.addPhoto' | t }}
          <input type="file" accept="image/*" multiple (change)="files.emit($event)" />
        </label>
      }

      <h3>{{ 'movein.close' | t }}</h3>
      <p class="muted small">{{ 'movein.closeHelp' | t }}</p>
      <div role="alert">
        @if (failure(); as f) {
          <p class="error small">{{ f.key | t: f.params }}</p>
        }
      </div>
      <p>
        <button type="button" id="movein-close" class="btn" [disabled]="closing()" (click)="closeHunt()">{{ 'movein.close' | t }}</button>
      </p>
      @if (closed() !== null) {
        <p id="movein-closed" class="closed" role="status">
          {{ 'movein.closed' | t: { n: closed() ?? 0 } }}
          <a routerLink="/data" [queryParams]="{ export: 'backup' }">{{ 'movein.saveCopy' | t }}</a>
        </p>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    h3 {
      margin: var(--space-4) 0 var(--space-1);
      font-size: var(--text-md);
    }
    .group {
      margin: var(--space-3) 0 var(--space-1);
      font-size: var(--text-sm);
    }
    .items,
    .condition {
      list-style: none;
      margin: 0 0 var(--space-3);
      padding: 0;
    }
    .item {
      display: grid;
      grid-template-columns: auto minmax(0, 1fr) auto;
      align-items: center;
      gap: var(--space-2);
      margin-bottom: var(--space-1);
    }
    .tick {
      width: 1.5rem;
      height: 1.5rem;
      margin: 0 var(--space-1);
      accent-color: var(--primary);
    }
    .remove {
      min-width: var(--target);
      min-height: var(--target);
    }
    input[type='date'] {
      width: 100%;
      min-height: var(--target);
      padding: var(--space-2) var(--space-3);
      border: 1px solid var(--border-strong);
      border-radius: 8px;
      background: var(--surface);
      color: var(--text);
      font: inherit;
    }
    .condition li {
      display: grid;
      grid-template-columns: 5rem minmax(0, 1fr);
      gap: var(--space-3);
      margin-bottom: var(--space-2);
    }
    .thumb {
      width: 5rem;
      height: 5rem;
      border-radius: 8px;
      overflow: hidden;
      background: var(--surface-2);
    }
    .info {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      min-width: 0;
      overflow-wrap: anywhere;
    }
    .when {
      font-size: var(--text-sm);
      color: var(--muted);
    }
    .upload {
      position: relative;
      cursor: pointer;
    }
    .upload input {
      position: absolute;
      inset: 0;
      width: 100%;
      opacity: 0;
      cursor: pointer;
    }
    .upload:focus-within {
      outline: 3px solid var(--focus);
      outline-offset: 2px;
    }
    .closed {
      font-weight: 600;
    }
  `,
})
export class HouseMoveInCard {
  private readonly api = inject(LocalDataService);
  private readonly announcer = inject(Announcer);
  private readonly confirm = inject(ConfirmService);
  protected readonly i18n = inject(TranslationService);

  /** The house as being edited: its `moveIn`, `rooms`, `status` and `id`. */
  readonly house = input.required<HouseDto>();
  /** True while the house is not saved yet: the condition photos need a saved house. */
  readonly isNew = input<boolean>(false);
  /** Saves the house as it is now; true when it is saved. *Close this hunt* needs the TAKEN status stored. */
  readonly saveFirst = input<() => Promise<boolean>>(() => Promise.resolve(true));

  /** The new value of `moveIn`, null when it has nothing left. */
  readonly moveInChange = output<MoveIn | null>();
  /** The file input of *Add a photo*; the page stores the photos with MOVE_IN chosen. */
  readonly files = output<Event>();
  /** A condition photo opened in the page's viewer. */
  readonly opened = output<OpenedPhoto>();

  protected readonly maxItems = MAX_MOVE_IN_ITEMS;
  protected readonly maxText = MAX_MOVE_IN_TEXT;
  protected readonly maxNotes = MAX_MOVE_IN_NOTES;
  protected readonly newText = signal('');
  protected readonly failure = signal<Msg | null>(null);
  protected readonly closing = signal(false);
  protected readonly closed = signal<number | null>(null);

  protected readonly moveIn = computed(() => this.house().moveIn ?? null);
  protected readonly items = computed(() => orderedItems(this.moveIn()?.items));
  protected readonly counts = computed(() => progress(this.moveIn()));
  /** *Start moving in* is offered while a default item is missing and there is room. */
  protected readonly canStart = computed(() => {
    const have = new Set(this.items().map((i) => i.id));
    return this.items().length < MAX_MOVE_IN_ITEMS && DEFAULT_MOVE_IN_ITEMS.some((d) => !have.has(d.id));
  });
  /** The move-in date as the date field holds it (`YYYY-MM-DD`, UTC, like the dates of the copies). */
  protected readonly dateValue = computed(() => {
    const date = this.moveIn()?.date;
    return date ? new Date(date).toISOString().slice(0, 10) : '';
  });

  private readonly photos = signal<PhotoSummary[]>([]);
  /** The condition photos grouped by room (the house's rooms in order, then "Untagged"), each group oldest first. */
  protected readonly groups = computed(() => {
    const rooms = this.house().rooms ?? [];
    const out: { key: string; name: string; photos: PhotoSummary[] }[] = [];
    for (const room of rooms) {
      const inRoom = this.photos().filter((p) => p.roomId === room.id);
      if (inRoom.length > 0) {
        out.push({ key: room.id, name: room.name?.trim() || this.i18n.t(ROOM_TYPE_KEY[room.type]), photos: inRoom });
      }
    }
    const known = new Set(rooms.map((r) => r.id));
    const untagged = this.photos().filter((p) => !p.roomId || !known.has(p.roomId));
    if (untagged.length > 0) out.push({ key: '', name: this.i18n.t('photoMeta.untagged'), photos: untagged });
    return out;
  });

  constructor() {
    // Again after writes to this browser's store (a photo stored here, a sync pull, an edit in another tab).
    effect(() => {
      this.api.settled();
      this.reload();
    });
  }

  private reload(): void {
    const id = this.house().id;
    if (this.isNew() || !id) return;
    this.api.conditionPhotos(id).subscribe({
      next: (list) => this.photos.set(list),
      error: () => this.photos.set([]),
    });
  }

  protected onHostChange(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLInputElement && target.type === 'file') event.stopPropagation();
  }

  protected altOf(p: PhotoSummary): string {
    return this.i18n.t('house.photoAlt', { n: this.photos().findIndex((x) => x.id === p.id) + 1, name: this.house().label || this.i18n.t('common.untitled') });
  }

  /** The page keeps what is typed as it is and coerces it (`cleanMoveIn`) when the house is saved. */
  private emit(next: MoveIn): void {
    this.moveInChange.emit(Object.keys(next).length > 0 ? next : null);
  }

  private withItems(items: MoveInItem[]): MoveIn {
    const base = this.moveIn() ?? {};
    const { items: _old, ...rest } = base;
    return { ...rest, ...(items.length > 0 ? { items } : {}) };
  }

  protected start(): void {
    const before = this.items();
    const next = addDefaults(before, this.i18n.lang());
    if (next.length === before.length) return;
    this.emit(this.withItems(next));
    this.announcer.announce({ key: 'movein.summary', params: { done: next.filter((i) => i.done).length, total: next.length } });
    setTimeout(() => document.getElementById('movein-tick-' + next[before.length].id)?.focus());
  }

  protected setDone(id: string, done: boolean): void {
    this.emit(this.withItems(this.items().map((i) => (i.id === id ? this.tick(i, done) : i))));
  }

  private tick(item: MoveInItem, done: boolean): MoveInItem {
    const { done: _done, ...rest } = item;
    return done ? { id: rest.id, text: rest.text, done: true, sort: rest.sort } : rest;
  }

  protected setText(id: string, text: string): void {
    // A blank text is refused on save (`cleanMoveIn` skips the row); the row keeps what is typed meanwhile.
    this.emit(this.withItems(this.items().map((i) => (i.id === id ? { ...i, text } : i))));
  }

  protected remove(id: string): void {
    const list = this.items();
    const index = list.findIndex((i) => i.id === id);
    this.emit(this.withItems(list.filter((i) => i.id !== id)));
    const focus = list[index + 1] ?? list[index - 1];
    setTimeout(() => document.getElementById(focus ? 'movein-text-' + focus.id : 'movein-new')?.focus());
  }

  protected addOwn(): void {
    const text = this.newText().trim();
    const list = this.items();
    if (text === '' || list.length >= MAX_MOVE_IN_ITEMS) return;
    const sort = list.reduce((max, i) => Math.max(max, i.sort), -1) + 1;
    const item: MoveInItem = { id: uuid(), text: text.slice(0, MAX_MOVE_IN_TEXT), sort };
    this.newText.set('');
    this.emit(this.withItems([...list, item]));
    setTimeout(() => document.getElementById('movein-new')?.focus());
  }

  protected setDate(value: string): void {
    const ms = /^\d{4}-\d{2}-\d{2}$/.test(value) ? Date.parse(value + 'T00:00:00Z') : NaN;
    const { date: _date, ...rest } = this.moveIn() ?? {};
    this.emit(Number.isFinite(ms) && ms > 0 ? { date: ms, ...rest } : rest);
  }

  protected setNotes(value: string): void {
    const { notes: _notes, ...rest } = this.moveIn() ?? {};
    this.emit(value.trim() === '' ? rest : { ...rest, notes: value });
  }

  /**
   * *Close this hunt*: the house is saved first (it must be stored as TAKEN), the person is asked how many other houses
   * will become Not chosen, and on yes they do, in one step. Nothing is deleted. The result offers *Save a copy*.
   */
  protected async closeHunt(): Promise<void> {
    if (this.closing()) return;
    this.closing.set(true);
    this.failure.set(null);
    this.closed.set(null);
    try {
      if (!(await this.saveFirst()())) return;
      const id = this.house().id;
      const n = await firstValueFrom(this.api.closeCount(id));
      const ok = await this.confirm.ask(n > 0 ? { key: 'movein.closeConfirm', params: { n } } : { key: 'movein.closeConfirmNone' }, {
        confirmKey: 'movein.close',
      });
      if (!ok) return;
      const marked = await firstValueFrom(this.api.closeHunt(id));
      this.closed.set(marked);
      this.announcer.announce({ key: 'movein.closed', params: { n: marked } });
    } catch (err: unknown) {
      this.failure.set({ key: 'movein.closeFailed', params: { reason: errorMsg(err) } });
    } finally {
      this.closing.set(false);
    }
  }
}
