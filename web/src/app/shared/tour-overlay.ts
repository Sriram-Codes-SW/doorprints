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

import { Component, DestroyRef, ElementRef, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { TourService } from '../core/tour.service';
import { TPipe } from '../i18n/t.pipe';
import { TranslationService } from '../i18n/translation.service';

/** The highlight's box in viewport pixels. */
interface Spot {
  readonly top: number;
  readonly left: number;
  readonly width: number;
  readonly height: number;
}

/** How long a step waits for its target to appear (a page still loading) before it shows without a highlight. */
const WAIT_MS = 2000;
const POLL_MS = 100;
const PAD = 6;
const GAP = 12;
/** Clear space between the card and the highlight's outline. */
const CLEAR = 6;
/** The least room the card is squeezed to (its buttons stay in view; the text scrolls). */
const MIN_CARD = 160;

/**
 * The first-visit offer and the guided tour itself (S4b-FR-38). The tour is not modal: the highlight ignores the pointer,
 * so the person can do what the step says on the real screen and then press Next. The card is a dialog that takes focus
 * when a step appears, Escape skips, and the whole thing is a few fixed boxes, so it adds nothing to the pages.
 * The card sits below the highlight (above the phone's bottom bar) when it fits there, else above it, else on the side
 * with more room, its text scrolling and its buttons staying in view, so it never covers what it points at.
 */
@Component({
  selector: 'app-tour',
  imports: [TPipe],
  template: `
    @if (tour.offered(url())) {
      <section class="offer" aria-labelledby="tour-offer-title">
        <h2 id="tour-offer-title">{{ 'tour.offer.title' | t }}</h2>
        <p>{{ 'tour.offer.body' | t }}</p>
        <div class="actions">
          <button type="button" class="btn btn-primary" id="tour-offer-start" (click)="tour.start()">{{ 'tour.offer.start' | t }}</button>
          <button type="button" class="btn" id="tour-offer-skip" (click)="tour.skip()">{{ 'tour.offer.skip' | t }}</button>
        </div>
      </section>
    }
    @if (tour.step(); as s) {
      @if (spot(); as r) {
        <div class="spot" aria-hidden="true" [style.top.px]="r.top" [style.left.px]="r.left" [style.width.px]="r.width" [style.height.px]="r.height"></div>
      } @else {
        <div class="dim" aria-hidden="true"></div>
      }
      <section
        #card
        class="card"
        [class.top]="onTop()"
        [class.middle]="!spot()"
        [style.max-height.px]="spot() ? maxHeight() : null"
        role="dialog"
        aria-labelledby="tour-title"
        aria-describedby="tour-body"
        (keydown.escape)="tour.skip()"
      >
        <p class="count">{{ 'tour.count' | t: { n: i18n.number(tour.position() + 1), total: i18n.number(tour.steps.length) } }}</p>
        <h2 id="tour-title" #title tabindex="-1">{{ s.title | t }}</h2>
        <div id="tour-body">
          <p>{{ s.body | t }}</p>
          <p class="do"><strong>{{ 'tour.tryIt' | t }}</strong> {{ s.action | t }}</p>
        </div>
        <div class="actions">
          <button type="button" class="btn" id="tour-skip" (click)="tour.skip()">{{ 'tour.skip' | t }}</button>
          @if (!tour.isFirst()) {
            <button type="button" class="btn" id="tour-back" (click)="tour.back()">{{ 'tour.back' | t }}</button>
          }
          <button type="button" class="btn btn-primary" id="tour-next" (click)="tour.next()">
            {{ (tour.isLast() ? 'tour.finish' : 'tour.next') | t }}
          </button>
        </div>
      </section>
    }
  `,
  styles: `
    .offer,
    .card {
      position: fixed;
      z-index: 60;
      left: 50%;
      transform: translateX(-50%);
      width: min(32rem, calc(100% - 2 * var(--space-4)));
      box-sizing: border-box;
      padding: var(--space-4);
      background: var(--surface);
      color: var(--text);
      border: 2px solid var(--focus);
      border-radius: 12px;
      box-shadow: 0 6px 24px rgba(0, 0, 0, 0.3);
      max-height: 60dvh;
      overflow: auto;
    }
    .offer {
      bottom: calc(var(--nav-h, 0px) + var(--space-3));
    }
    .card {
      bottom: calc(var(--nav-h, 0px) + var(--space-3));
    }
    .card.top {
      bottom: auto;
      top: calc(var(--header-h, 56px) + var(--space-2));
    }
    .card.middle {
      bottom: auto;
      top: 50%;
      transform: translate(-50%, -50%);
    }
    h2 {
      margin: 0 0 var(--space-2);
      font-size: var(--text-lg);
    }
    h2:focus {
      outline: none;
    }
    p {
      margin: 0 0 var(--space-2);
    }
    .count {
      color: var(--muted, inherit);
      font-size: var(--text-sm, 0.875rem);
    }
    .do {
      padding: var(--space-2);
      background: var(--primary-soft, transparent);
      border-radius: 8px;
    }
    .card .actions {
      position: sticky;
      bottom: calc(-1 * var(--space-4));
      padding: var(--space-2) 0 0;
      background: var(--surface);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
      justify-content: flex-end;
      margin-top: var(--space-3);
    }
    .spot {
      position: fixed;
      z-index: 55;
      pointer-events: none;
      border-radius: 8px;
      outline: 3px solid var(--focus);
      box-shadow: 0 0 0 9999px rgba(0, 0, 0, 0.55);
    }
    .dim {
      position: fixed;
      inset: 0;
      z-index: 55;
      pointer-events: none;
      background: rgba(0, 0, 0, 0.55);
    }
    @media (forced-colors: active) {
      .spot {
        outline-color: Highlight;
      }
    }
  `,
})
export class TourOverlay {
  protected readonly tour = inject(TourService);
  private readonly router = inject(Router);
  /** The current address as a signal (the router's own \`url\` is not one), so the offer and the step follow navigation. */
  protected readonly url = signal(this.router.url);
  protected readonly i18n = inject(TranslationService);
  private readonly title = viewChild<ElementRef<HTMLElement>>('title');
  private readonly card = viewChild<ElementRef<HTMLElement>>('card');
  protected readonly spot = signal<Spot | null>(null);
  protected readonly onTop = signal(false);
  /** The room the card has on its side of the highlight, so it never covers what it points at. */
  protected readonly maxHeight = signal(0);
  private timer: ReturnType<typeof setTimeout> | undefined;
  private target: Element | null = null;

  constructor() {
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd), takeUntilDestroyed()).subscribe(() => this.url.set(this.router.url));
    const measure = () => this.measure();
    window.addEventListener('resize', measure);
    window.addEventListener('scroll', measure, true);
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('resize', measure);
      window.removeEventListener('scroll', measure, true);
      clearTimeout(this.timer);
    });
    // A new step: wait for its page and target, then highlight it and move focus to the card.
    effect(() => {
      const step = this.tour.step();
      const url = this.url(); // the effect re-runs once the route has changed
      untracked(() => {
        clearTimeout(this.timer);
        this.target = null;
        this.spot.set(null);
        if (step) this.find(step.route, step.target, url, 0);
      });
    });
    effect(() => {
      this.tour.step();
      const el = this.title()?.nativeElement;
      if (el) queueMicrotask(() => el.focus());
    });
  }

  /**
   * Looks for the element a tour step points at: the first of its selectors on the page, once the route is right,
   * polling until the wait runs out. When found it is scrolled into view and measured.
   */
  private find(route: string, selector: string | undefined, url: string, waited: number): void {
    const onRoute = url.split(/[?#]/)[0] === route;
    // 'a || b': the first selector that is on the page (an empty list has no search box, so the heading stands in).
    const el = onRoute && selector ? selector.split('||').map((q) => document.querySelector(q.trim())).find((e) => e) ?? null : null;
    if (el) {
      this.target = el;
      el.scrollIntoView({ block: 'center' });
      this.timer = setTimeout(() => this.measure());
      return;
    }
    if (selector && waited < WAIT_MS) {
      this.timer = setTimeout(() => this.find(route, selector, this.router.url, waited + POLL_MS), POLL_MS);
    }
  }

  /**
   * Puts the highlight around the target and places the card above or below it, on the side with room, limiting its
   * height to that room.
   */
  private measure(): void {
    const el = this.target;
    if (!el || !el.isConnected) return;
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) return;
    this.spot.set({ top: r.top - PAD, left: r.left - PAD, width: r.width + 2 * PAD, height: r.height + 2 * PAD });
    const nav = document.querySelector('nav.nav');
    const floor = nav && getComputedStyle(nav).position === 'fixed' ? nav.getBoundingClientRect().top : window.innerHeight;
    const ceiling = (document.querySelector('header.topbar')?.getBoundingClientRect().bottom ?? 0) + GAP;
    const below = floor - GAP - (r.top + r.height + PAD) - CLEAR;
    const above = r.top - PAD - GAP - ceiling - CLEAR;
    const need = this.card()?.nativeElement.scrollHeight ?? 0;
    const top = need > below && above > below;
    this.onTop.set(top);
    this.maxHeight.set(Math.max(MIN_CARD, Math.floor(top ? above : below)));
  }
}
