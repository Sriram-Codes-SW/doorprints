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

import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../core/announcer.service';
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import type { PhotoSummary } from '../../core/local-data.service';
import { ROOM_TYPE_KEY } from '../../core/models';
import type { HouseRoom } from '../../core/models';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';
import { FIXED_TAGS, MAX_CAPTION, MAX_TAGS, MAX_TAG_LENGTH, isFixedTag, photoTagKey, withTag, withoutTag } from '../../shared/photo-tags';

/**
 * The photo details editor (docs/11 5.7, slice 5): the room (the house's rooms and "No room"), the tags (the fixed tags as
 * toggles, a field for a custom one, at most 10 of 30 characters) and the caption (200). *Save details* writes them only
 * when something changed; the edit is stamped and goes to the server with the next sync. The fields are not part of the
 * house form, so their input events stop here and the page is not marked as having unsaved edits.
 */
@Component({
  selector: 'app-photo-meta-editor',
  imports: [TPipe],
  host: { '(input)': '$event.stopPropagation()', '(change)': '$event.stopPropagation()' },
  template: `
    <section class="editor" [attr.aria-labelledby]="'photo-meta-title-' + photo().id">
      <h3 [id]="'photo-meta-title-' + photo().id">{{ 'photoMeta.title' | t: { n: n() } }}</h3>

      <div class="field">
        <label [for]="'photo-room-' + photo().id">{{ 'photoMeta.room' | t }}</label>
        <select [id]="'photo-room-' + photo().id" [value]="roomId()" (change)="roomId.set($any($event.target).value)">
          <option value="">{{ 'photoMeta.noRoom' | t }}</option>
          @for (r of rooms(); track r.id) {
            <option [value]="r.id" [selected]="roomId() === r.id">{{ roomName(r) }}</option>
          }
          @if (roomId() !== '' && !hasRoom(roomId())) {
            <!-- The room this photo names is not in the house any more: it stays until the person picks another. -->
            <option [value]="roomId()" selected>{{ 'photoMeta.untagged' | t }}</option>
          }
        </select>
      </div>

      <fieldset>
        <legend>{{ 'photoMeta.tags' | t }}</legend>
        <div class="chips" role="group" [attr.aria-label]="'photoMeta.tags' | t">
          @for (tag of fixed; track tag) {
            <button type="button" class="chip" [attr.aria-pressed]="has(tag)" (click)="toggle(tag)">{{ tagKey(tag) | t }}</button>
          }
        </div>
        @if (customTags().length > 0) {
          <div class="chips custom">
            @for (tag of customTags(); track tag) {
              <span class="chip-custom">
                {{ tag }}
                <button type="button" class="chip-x" [attr.aria-label]="'photoMeta.tagRemove' | t: { tag: tag }" (click)="remove(tag)">
                  <span aria-hidden="true">✕</span>
                </button>
              </span>
            }
          </div>
        }
        <div class="field custom-field">
          <label [for]="'photo-tag-' + photo().id">{{ 'photoMeta.customTag' | t }}</label>
          <div class="inline">
            <input
              [id]="'photo-tag-' + photo().id"
              type="text"
              autocomplete="off"
              [maxLength]="maxTag"
              [value]="draftTag()"
              [disabled]="tags().length >= maxTags"
              (input)="draftTag.set($any($event.target).value)"
              (keydown.enter)="addCustom(); $event.preventDefault()"
            />
            <button type="button" class="btn" [disabled]="tags().length >= maxTags || draftTag().trim() === ''" (click)="addCustom()">
              {{ 'photoMeta.addTag' | t }}
            </button>
          </div>
          @if (tags().length >= maxTags) {
            <span class="field-hint">{{ 'photoMeta.tagsFull' | t }}</span>
          }
          <span class="field-error" role="status">{{ tagNote() ? ('photoMeta.tagNotAdded' | t) : '' }}</span>
        </div>
      </fieldset>

      <div class="field">
        <label [for]="'photo-caption-' + photo().id">{{ 'photoMeta.caption' | t }}</label>
        <input
          [id]="'photo-caption-' + photo().id"
          type="text"
          autocomplete="off"
          [maxLength]="maxCaption"
          [value]="caption()"
          (input)="caption.set($any($event.target).value)"
        />
      </div>

      <div role="alert">
        @if (failure(); as f) {
          <p class="error small">{{ f.key | t: f.params }}</p>
        }
      </div>
      <div class="actions">
        <button type="button" class="btn" (click)="closed.emit()">{{ 'common.cancel' | t }}</button>
        <button type="button" class="btn btn-primary" [disabled]="saving()" (click)="save()">{{ 'photoMeta.save' | t }}</button>
      </div>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .editor {
      border: 1px solid var(--border);
      border-radius: var(--radius);
      padding: var(--space-3);
      background: var(--surface);
    }
    h3 {
      margin: 0 0 var(--space-2);
      font-size: var(--text-md);
    }
    .chips {
      margin-bottom: var(--space-2);
    }
    .chip {
      min-height: var(--target);
    }
    .chip-custom {
      display: inline-flex;
      align-items: center;
      gap: var(--space-1);
      border: 1px solid var(--primary);
      border-radius: var(--radius-pill);
      padding: 0 0 0 var(--space-3);
      background: var(--primary-soft);
      font-size: var(--text-sm);
      min-height: var(--target);
    }
    .chip-x {
      min-width: var(--target);
      min-height: var(--target);
      border: 0;
      border-radius: var(--radius-pill);
      background: none;
      color: inherit;
      font: inherit;
      cursor: pointer;
    }
    .chip-x:focus-visible {
      outline: 3px solid var(--focus);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-2);
    }
  `,
})
export class PhotoMetaEditor implements OnInit {
  private readonly api = inject(LocalDataService);
  private readonly announcer = inject(Announcer);
  protected readonly i18n = inject(TranslationService);

  readonly photo = input.required<PhotoSummary>();
  /** The photo's place in the list (1-based), for the heading. */
  readonly n = input.required<number>();
  /** The rooms of the house, for the room picker. */
  readonly rooms = input<readonly HouseRoom[]>([]);

  /** The meta was saved (or was unchanged): the page reads the photos again and closes the editor. */
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly fixed = FIXED_TAGS;
  protected readonly maxTags = MAX_TAGS;
  protected readonly maxTag = MAX_TAG_LENGTH;
  protected readonly maxCaption = MAX_CAPTION;

  protected readonly roomId = signal('');
  protected readonly tags = signal<string[]>([]);
  protected readonly caption = signal('');
  protected readonly draftTag = signal('');
  protected readonly tagNote = signal(false);
  protected readonly saving = signal(false);
  protected readonly failure = signal<Msg | null>(null);

  protected readonly customTags = computed(() => this.tags().filter((t) => !isFixedTag(t)));

  /** The fields start from the stored meta of the photo this editor was opened for (the page creates one per photo). */
  ngOnInit(): void {
    const p = this.photo();
    this.roomId.set(p.roomId ?? '');
    this.tags.set([...p.tags]);
    this.caption.set(p.caption ?? '');
  }

  protected tagKey(tag: string): TKey {
    return photoTagKey(tag) ?? 'photoMeta.tags';
  }

  protected has(tag: string): boolean {
    return this.tags().some((t) => t.toLowerCase() === tag.toLowerCase());
  }

  protected toggle(tag: string): void {
    this.tagNote.set(false);
    this.tags.update((list) => (this.has(tag) ? withoutTag(list, tag) : withTag(list, tag)));
  }

  protected remove(tag: string): void {
    this.tags.update((list) => withoutTag(list, tag));
    setTimeout(() => document.getElementById('photo-tag-' + this.photo().id)?.focus());
  }

  protected addCustom(): void {
    const before = this.tags();
    const next = withTag(before, this.draftTag());
    if (next.length === before.length) {
      this.tagNote.set(true);
      return;
    }
    this.tagNote.set(false);
    this.tags.set(next);
    this.draftTag.set('');
  }

  protected hasRoom(id: string): boolean {
    return this.rooms().some((r) => r.id === id);
  }

  protected roomName(r: HouseRoom): string {
    return r.name?.trim() || this.i18n.t(ROOM_TYPE_KEY[r.type]);
  }

  protected async save(): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    this.failure.set(null);
    try {
      const changed = await firstValueFrom(
        this.api.setPhotoMeta(this.photo().id, { roomId: this.roomId() || null, tags: this.tags(), caption: this.caption().trim() || null }),
      );
      if (changed) this.announcer.announce({ key: 'photoMeta.saved' });
      this.saved.emit();
    } catch (err: unknown) {
      this.failure.set({ key: 'photoMeta.saveFailed', params: { reason: errorMsg(err) } });
    } finally {
      this.saving.set(false);
    }
  }
}
