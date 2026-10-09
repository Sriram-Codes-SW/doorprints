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

// What the house page keeps about a pasted listing (S4b-BL-168): the text in the "Fill in from listing text" box and
// how it is cut for Extract, the read of it by the AI, and the merge of any listing draft (the AI's, or the no-AI
// parser's for a shared listing on arrival) into the form without replacing what the person typed. It is separate from
// `house-detail-page.ts` because three readers share it: the listing card of the template, the page's arrival (which
// puts a shared text in the box and applies the parser's draft) and the page's destruction (which ends a read in
// flight). The page owns the draft; this changes it only through `patch`.
import { signal } from '@angular/core';
import type { Subscription } from 'rxjs';
import { AI_MAX_LISTING_CHARS, aiErrorMsg } from '../../core/ai.service';
import type { AiService, HouseDraft } from '../../core/ai.service';
import type { Announcer } from '../../core/announcer.service';
import { cutListing } from '../../core/ai/ai-core';
import type { HouseDto } from '../../core/models';
import type { Msg, TranslationService } from '../../i18n/translation.service';
import { runResult } from '../../shared/run-result';
import type { RunResult } from '../../shared/run-result';
import { FIELD_LABEL, mergeListingDraft } from './house-draft-merge';
import type { FillField } from './house-draft-merge';

/** What the listing card of the house page needs from the page. */
export interface HouseListingFillDeps {
  ai: Pick<AiService, 'extractListing'>;
  i18n: TranslationService;
  announcer: Announcer;
  /** The house as edited, or null before it is opened. */
  draft: () => HouseDto | null;
  /** The page's one way to change the draft: merges the fields in and marks the page dirty. */
  patch: (changes: Partial<HouseDto>) => void;
}

export class HouseListingFill {
  /** The text in the listing box. */
  text = '';
  /** The listing box starts open when the house came from a shared listing (the text is already in it). */
  open = false;
  readonly max = AI_MAX_LISTING_CHARS;
  readonly filling = signal(false);
  readonly warnings = signal<string[]>([]);
  /** Typed values the fill kept, with what the listing says instead ("Kept your Name; the listing says …"). */
  readonly kept = signal<Msg[]>([]);
  /**
   * Why the listing could not be read: shown in the listing card, next to the button. Every message a button can
   * produce twice in a row with the same words is keyed on its run ({@link RunResult}), so the second one is a new
   * node in its live region and is read again (web UX gate R9).
   */
  readonly error = signal<RunResult<Msg> | null>(null);
  private cutFor = '';
  private cutOfText = cutListing('', AI_MAX_LISTING_CHARS);
  /** The read in flight: leaving the page ends it, so its late result fills no form and announces nothing. */
  private request: Subscription | null = null;

  constructor(private readonly deps: HouseListingFillDeps) {}

  /**
   * The pasted text as Extract reads it: trimmed and cut at {@link max}; `leftOut` feeds the hint under the field
   * (S4b-BL-182). Kept for the last text, so the page's checks do not cut it again.
   */
  cut(): { text: string; leftOut: number } {
    if (this.cutFor !== this.text) {
      this.cutFor = this.text;
      this.cutOfText = cutListing(this.text, this.max);
    }
    return this.cutOfText;
  }

  /** A shared listing arrived: all of it goes in the box, which starts open. */
  show(text: string): void {
    this.text = text;
    this.open = true;
  }

  /**
   * Sends pasted listing text to POST /api/ai/extract-listing and fills the **empty** form fields; a typed value is
   * never replaced, and every one the listing disagrees with is named. Nothing is saved: the user reviews, picks
   * the map location and presses Add (docs/ai AI-004 human confirmation).
   */
  fill(): void {
    const text = this.cut().text;
    if (!text || this.filling()) return;
    this.filling.set(true);
    // The last failure stays, drawn as being updated, until this read ends and replaces or removes it (S4b-BL-2).
    this.warnings.set([]);
    this.kept.set([]);
    this.request = this.deps.ai.extractListing(text).subscribe({
      next: (draft) => {
        this.request = null;
        this.error.set(null);
        this.apply(draft);
        this.warnings.set(draft.warnings ?? []);
        this.filling.set(false);
        this.deps.announcer.announce({
          key: this.kept().length > 0 ? 'listingFill.doneKept' : 'listingFill.done',
          params: { n: this.kept().length },
        });
        document.getElementById('house-name')?.focus();
      },
      error: (err: unknown) => {
        this.request = null;
        this.error.set(runResult({ key: 'listingFill.failed', params: { reason: aiErrorMsg(err) } }));
        this.filling.set(false);
      },
    });
  }

  /**
   * Puts a listing draft (from the AI or the no-AI parser) into the form without replacing anything the person typed;
   * what it kept is named in {@link kept}.
   */
  apply(a: HouseDraft): void {
    const d = this.deps.draft();
    if (!d) return;
    const result = mergeListingDraft(d, a);
    this.deps.patch(result.changes);
    this.kept.set(
      result.kept.map((k) => ({
        key: 'listingFill.kept',
        params: { field: { key: FIELD_LABEL[k.field] }, value: this.shown(k.field, k.incoming) },
      })),
    );
  }

  /** Ends the read in flight (the page is going away). */
  stop(): void {
    this.request?.unsubscribe();
  }

  /** A listing value as the form would show it: a price in rupees, a price type in words. */
  private shown(field: FillField, value: string | number): Msg | string {
    if (field === 'price' && typeof value === 'number') return this.deps.i18n.price(value, null);
    if (field === 'priceType') return { key: value === 'SALE' ? 'price.sale' : 'price.rent' };
    if (field === 'areaSqft') return { key: 'common.sqft', params: { n: value } };
    return String(value);
  }
}
