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

import {
  Component,
  ElementRef,
  Injector,
  OnDestroy,
  OnInit,
  afterNextRender,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { LocalDataService } from '../../core/local-data.service';
import { GeocodeService } from '../../core/geocode.service';
import {
  HouseDto,
  HouseStatus,
  LocationSource,
  STATUSES,
  STATUS_COLOR,
  STATUS_ICON,
  STATUS_KEY,
  VisitDto,
  newHouse,
  uuid,
} from '../../core/models';
import { errorMsg, telHref } from '../../core/format';
import type { TKey } from '../../i18n/en';
import { MAX_ANSWERS, MAX_ANSWER, MAX_ANSWER_TEXT, cleanAnswers, cleanCost, cleanMoveIn, cleanRooms } from '../../data/records';
import { LocalStore } from '../../data/local-store.service';
import type { HouseAnswer, MoveIn } from '../../core/models';
import { MOVE_IN_TAG } from '../../shared/photo-tags';
import type { PhotoMetaInput } from '../../data/photo-store';
import { HouseMoveInCard } from './house-move-in-card';
import { HouseCheckCard } from './house-check-card';
import { HouseWalksCard } from './house-walks-card';
import { TraceStore } from '../../data/trace-store';
import type { OpenedPhoto } from './house-move-in-card';
import { PhotoMetaEditor } from './photo-meta-editor';
import { HousePhotos, isStorageFull } from './house-photos';
import { HouseRooms } from './house-rooms';
import { HouseFloor, floorOf } from './house-floor';
import { QUESTION_CATEGORIES } from '../../shared/question';
import type { Question, QuestionCategory } from '../../shared/question';
import { HouseViewingsCard } from '../viewings/house-viewings-card';
import { HouseAreaNotesCard, HouseDistancesCard } from '../areas/house-area-cards';
import { addUsual, answerFor, ordered, usualQuestions } from '../../shared/house-answers';
import { duplicateFlats } from '../../shared/duplicate-flat';
import { costSummary } from '../../shared/house-cost';
import { brokerLine } from '../../shared/broker';
import { criterionName } from '../../shared/criterion-name';
import { DEFAULT_SCORING, evaluateScore } from '../../shared/scoring';
import type { Criterion, ScoreResult, Scoring } from '../../shared/scoring';
import type { BrokerRow } from '../../shared/broker';
import { LocalDataError } from '../../core/local-error';
import { Announcer } from '../../core/announcer.service';
import { LocationMap, round6 } from '../../shared/location-map';
import type { MapOverlay } from '../../shared/location-map';
import { AuthImage } from '../../shared/auth-image';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TitleOverride } from '../../i18n/i18n-title.strategy';
import { ConfirmService } from '../../core/confirm.service';
import { AI_MAX_LISTING_CHARS, AiService } from '../../core/ai.service';
import { TPipe } from '../../i18n/t.pipe';
import { UnsavedChanges } from '../../core/unsaved-changes.service';
import { COUNTRY_VIEW, loadStartPoint } from '../../shared/map-center';
import { HouseListingCard, HouseListingFill } from './house-listing-card';
import { HouseLocation } from './house-location';
import { clearDraft, draftKey, readDraft, writeDraft } from './draft-store';
import { type BackKey, HOUSE_BACK_STATE, backTarget, exitAfterRemoval } from './back-target';
import { ListReturn } from '../map/list-return';
import { GLYPHS } from '../../shared/glyphs';
import { parseListingText } from '../../shared/listing-text';
import { RunResult, runResult } from '../../shared/run-result';

interface OpenPhoto {
  src: string;
  alt: string;
}

/** Said while a save runs; withdrawn on failure (persist()). */
const SAVING: Msg = { key: 'house.saving' };

/** How long typing pauses before the unsaved draft is written to sessionStorage. */
const DRAFT_SAVE_MS = 500;

/**
 * The page to view, add or edit one house (`/houses/:id` and `/houses/new`): the form with its cost, rooms, questions,
 * checklist and score, the location map, visits, photos and the cards for viewings, area notes, move-in and walks. It
 * is the one place a house is changed by hand.
 *
 * Edits go to a local `draft` signal and only {@link persist} writes them to the local store; sync to a server happens
 * later and elsewhere. While there are unsaved edits the draft is kept in sessionStorage (draft-store.ts), the router
 * guard and the reload banner ask before leaving, and a new house opened without a position cannot be saved until the
 * pin is put. States: loading, not found, error, saving, just saved.
 */
@Component({
  selector: 'app-house-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    LocationMap,
    AuthImage,
    TPipe,
    HouseViewingsCard,
    HouseAreaNotesCard,
    HouseDistancesCard,
    HouseMoveInCard, HouseListingCard,
    HouseCheckCard,
    HouseWalksCard,
    PhotoMetaEditor,
  ],
  templateUrl: './house-detail-page.html',
  styleUrl: './house-detail-page.css',
  // Tab close, browser reload and the update banner's reload do not go through the router's canDeactivate.
  host: { '(window:beforeunload)': 'onBeforeUnload($event)' },
})
export class HouseDetailPage implements OnInit, OnDestroy {
  private readonly api = inject(LocalDataService);
  private readonly geocode = inject(GeocodeService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly announcer = inject(Announcer);
  private readonly confirm = inject(ConfirmService);
  private readonly traces = inject(TraceStore);
  private readonly unsaved = inject(UnsavedChanges);
  private readonly pageTitle = inject(TitleOverride);
  private readonly injector = inject(Injector);
  private readonly store = inject(LocalStore);
  /** The list's last search and filter, for "Back to map" when there is no page behind this one (and after Delete). */
  protected readonly listReturn = inject(ListReturn);
  protected readonly ai = inject(AiService);
  protected readonly i18n = inject(TranslationService);

  /** "Fill in from listing text" (AI, new houses only; hidden unless the server has AI enabled) (house-listing-fill.ts). */
  protected readonly listing = new HouseListingFill({
    ai: this.ai, i18n: this.i18n, announcer: this.announcer, draft: () => this.draft(), patch: (changes) => this.patch(changes),
  });

  protected readonly draft = signal<HouseDto | null>(null);
  /** The rooms of this house: the list in the draft, the sizes in the chosen unit (house-rooms.ts). */
  protected readonly rooms = new HouseRooms({
    i18n: this.i18n,
    injector: this.injector,
    draft: () => this.draft(),
    patch: (changes) => this.patch(changes),
  });
  /** The floor of this house: the level, the Basement switch and whether it is a floor (house-floor.ts). */
  protected readonly floor = new HouseFloor({ patch: (changes) => this.patch(changes) });
  /** The brokers the Broker select offers (slice 1b), by name. */
  protected readonly brokers = signal<BrokerRow[]>([]);
  /** Every house in this browser, for the duplicate-flat warning under the floor (S4b-BL-85). */
  private readonly others = signal<HouseDto[]>([]);
  /** The broker the draft is linked to, when it exists: the contact fields then show its name and phone. */
  protected readonly linkedBroker = computed(() => {
    const id = this.draft()?.brokerId;
    return id ? (this.brokers().find((b) => b.id === id) ?? null) : null;
  });
  protected readonly brokerLine = (row: BrokerRow): string => brokerLine(row.broker);
  protected readonly isNew = signal(false);
  /** Walks and the place check's halo drawn on this page's own map (docs/11 5.27.6, 5.27.13); never a navigation, never kept. */
  protected readonly overlay = signal<MapOverlay | null>(null);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly dirty = signal(false);
  protected readonly justSaved = signal(false);
  protected readonly nameError = signal(false);
  /** Save failures only (name or location missing, the save itself failed) and a failed load; at the top. */
  protected readonly error = signal<RunResult<Msg> | null>(null);
  /** Where the house is: the pin, the typed coordinates, Use my location, Find, Fill address from map (house-location.ts). */
  protected readonly position = new HouseLocation({
    geocode: this.geocode,
    i18n: this.i18n,
    announcer: this.announcer,
    confirm: this.confirm,
    draft: () => this.draft(),
    patch: (changes) => this.patch(changes),
    error: this.error,
    gone: () => this.destroyed,
  });
  /** Unsaved edits from an earlier visit to this page (a discarded tab) were put back; offer to discard them. */
  protected readonly restored = signal(false);
  /** "Back" goes back in history (to Compare, an Ask answer, a Plan stop, the filtered list), not always to "/". */
  protected readonly backToPrevious = signal(false);
  protected readonly backKey = signal<BackKey>('house.back');

  protected readonly visits = signal<VisitDto[]>([]);
  protected readonly sortedVisits = computed(() =>
    this.visits()
      .filter((v) => !v.deleted)
      .sort((a, b) => Date.parse(b.arrivedAt) - Date.parse(a.arrivedAt)),
  );
  protected readonly markingVisit = signal(false);
  protected readonly visitsMsg = signal<RunResult<Msg> | null>(null);

  /** The photos of this house: the list, the uploads, the details editor (house-photos.ts). */
  protected readonly photos = new HousePhotos({
    api: this.api,
    announcer: this.announcer,
    confirm: this.confirm,
    i18n: this.i18n,
    house: () => this.draft(),
    isNew: () => this.isNew(),
    onDeleted: () => this.lightbox.set(null),
  });
  /** The photo open in the large view: one of the photos card, or a condition photo of the Moving in card. */
  protected readonly lightbox = signal<OpenPhoto | null>(null);
  /** A failed delete, shown next to the delete button. */
  protected readonly dangerMsg = signal<RunResult<Msg> | null>(null);
  private readonly viewer = viewChild<ElementRef<HTMLDialogElement>>('viewer');
  private readonly toolbar = viewChild<ElementRef<HTMLElement>>('toolbar');
  private toolbarObserver: ResizeObserver | null = null;
  /** Short viewports (landscape phone, 400% zoom): the toolbar is not sticky there (CSS), so nothing is reserved. */
  private shortQuery: MediaQueryList | null = null;
  private onShortChange: (() => void) | null = null;

  /** The question bank (slice 3a), archived questions included; empty until it is read. */
  private readonly bank = signal<Question[]>([]);
  protected readonly maxAnswers = MAX_ANSWERS;
  protected readonly maxAnswerText = MAX_ANSWER_TEXT;
  protected readonly maxAnswer = MAX_ANSWER;
  /** "Add a question" is open: the picker over the bank and the field for something else. */
  protected readonly pickerOpen = signal(false);
  protected adhocText = '';
  /** How many bank questions "Add the usual questions" would add to the draft now; 0 disables it ("Already added"). */
  protected readonly usualCount = computed(() => {
    const d = this.draft();
    return d ? usualQuestions(d, this.bank(), d.answers ?? []).length : 0;
  });
  /** The questions the picker offers: not archived, not yet on this house, grouped by category. */
  protected readonly pickable = computed(() => {
    const on = new Set((this.draft()?.answers ?? []).map((a) => a.questionId));
    const free = this.bank().filter((q) => q.archived !== true && !on.has(q.id));
    return QUESTION_CATEGORIES.map((category) => ({ category, questions: free.filter((q) => q.category === category) })).filter(
      (g) => g.questions.length > 0,
    );
  });
  /** The ids of the answers in the order the cards are shown; kept while typing so a card does not jump when it turns Answered. */
  private answerIds: string[] = [];
  /** The effective scoring (slice 2); the defaults until it is read. */
  private readonly scoring = signal<Scoring>(DEFAULT_SCORING);
  /**
   * The criteria the checklist asks about: the active ones in the person's order, custom ones by label, weight-0 ones
   * marked "ignored". Archived criteria are hidden here but their stored scores stay on the house.
   */
  protected readonly checklist = computed<Criterion[]>(() => this.scoring().criteria.filter((c) => c.archived !== true));
  protected readonly statuses = STATUSES;
  protected readonly statusKey = STATUS_KEY;
  protected readonly statusIcon = STATUS_ICON;
  protected readonly statusColor = STATUS_COLOR;
  protected readonly checkValues: readonly number[] = [0, 1, 2, 3, 4, 5];
  protected readonly starValues: readonly number[] = [1, 2, 3, 4, 5];
  protected readonly telHref = telHref;
  protected readonly glyphs = GLYPHS;

  /** sessionStorage key of this page's unsaved draft (see draft-store.ts). */
  private storeKey = '';
  private draftTimer: ReturnType<typeof setTimeout> | undefined;
  /** The house as loaded, for "Discard" on restored edits. */
  private pristine: { draft: HouseDto; dirty: boolean; locationSet: boolean } | null = null;
  /** Set first thing in `ngOnDestroy`: the late answer of a read, a lookup or a location request is dropped. */
  private destroyed = false;
  /**
   * Set just before this page calls `Location.back()` itself, once leaving has been settled (asked and answered in
   * {@link goBack}, or the house deleted or discarded): the guard then lets that one Back through without asking
   * again. Only a popstate navigation uses it, and any edit clears it.
   */
  private leaveApproved = false;

  constructor() {
    // Here, not in ngOnInit: the router's navigation to this page is only "current" while the page is being created
    // (activation); by ngOnInit, which runs at the first change detection in a later task, it has finished.
    this.initBack();
    // Native <dialog>.showModal() gives focus trapping, Esc to close and focus return for free.
    effect(() => {
      const open = this.lightbox() !== null;
      const dialog = this.viewer()?.nativeElement;
      if (!dialog) return;
      if (open && !dialog.open) dialog.showModal();
      if (!open && dialog.open) dialog.close();
    });
    // Tell the app shell about unsaved edits, so the update banner's "Reload" asks first (PwaService.applyUpdate).
    effect(() => {
      const dirty = this.dirty();
      untracked(() => this.unsaved.dirty.set(dirty));
    });
    this.unsaved.register(this, () => this.persist());
    afterNextRender(() => this.watchToolbar());
    this.store.lengthUnit().then((u) => this.rooms.lengthUnit.set(u), () => undefined);
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.listing.stop();
    this.position.stop();
    this.unsaved.release(this);
    this.toolbarObserver?.disconnect();
    if (this.shortQuery && this.onShortChange) this.shortQuery.removeEventListener('change', this.onShortChange);
    document.getElementById('main')?.style.removeProperty('--sticky-top');
    this.pageTitle.message.set(null);
    // In-app navigation away: saved, discarded or knowingly left (canLeave), so the stored draft has served.
    clearTimeout(this.draftTimer);
    if (this.storeKey) clearDraft(this.storeKey);
  }

  /**
   * The sticky toolbar (Back, title, Save) sits over the scrolling content: one row on a phone, like Android's top
   * app bar, and more when a long translation wraps on a wider screen. Its height goes to `--sticky-top` on #main,
   * which the app shell uses as `scroll-padding-top`, so a field reached with Tab is scrolled clear of the toolbar
   * instead of under it (WCAG 2.2 SC 2.4.11). On a short viewport (landscape phone, 400% zoom) the toolbar scrolls
   * away with the page (CSS, WCAG 1.4.10) and nothing is reserved.
   */
  private watchToolbar(): void {
    const toolbar = this.toolbar()?.nativeElement;
    const main = document.getElementById('main');
    if (!toolbar || !main || typeof ResizeObserver === 'undefined') return;
    const update = () => {
      if (getComputedStyle(toolbar).position !== 'sticky') {
        main.style.removeProperty('--sticky-top');
        return;
      }
      main.style.setProperty('--sticky-top', `${Math.ceil(toolbar.getBoundingClientRect().height) + 8}px`);
    };
    this.toolbarObserver = new ResizeObserver(update);
    this.toolbarObserver.observe(toolbar);
    if (typeof matchMedia !== 'undefined') {
      // The same breakpoint as the CSS: crossing it changes position, not necessarily the toolbar's size.
      this.shortQuery = matchMedia('(max-height: 500px)');
      this.onShortChange = update;
      this.shortQuery.addEventListener('change', update);
    }
    update();
  }

  /** Browser reload or tab close with unsaved edits or photos still uploading: the browser's own "Leave site?". */
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if ((!this.dirty() && this.photos.uploading() === 0) || this.unsaved.leaving) return;
    event.preventDefault();
    // Older browsers only show the prompt when returnValue is set; the text itself is never displayed.
    event.returnValue = '';
  }

  /**
   * Loads what the form needs (question bank, scoring, brokers, other houses) and then either starts a new house (from
   * a position in the URL, a shared listing, or none) or reads the house, its visits and its photos. Shared listing
   * text arrives in navigation state, never in the URL, because it often holds a phone number.
   */
  ngOnInit(): void {
    this.api.questions().subscribe({
      next: (list) => this.bank.set(list),
      error: () => this.bank.set([]),
    });
    this.api.scoring().subscribe({
      next: (scoring) => this.scoring.set(scoring),
      error: () => this.scoring.set(DEFAULT_SCORING),
    });
    this.api.brokers().subscribe({
      next: (rows) => this.brokers.set(sortBrokers(rows)),
      error: () => this.brokers.set([]),
    });
    this.api.houses().subscribe({
      next: (rows) => this.others.set(rows),
      error: () => this.others.set([]),
    });
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      const q = this.route.snapshot.queryParamMap;
      this.storeKey = draftKey(null, q.get('lat'), q.get('lon'));
      const lat = Number.parseFloat(q.get('lat') ?? '');
      const lon = Number.parseFloat(q.get('lon') ?? '');
      const hasPosition = Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
      this.isNew.set(true);
      // Text handed over by the PWA share target (/share, S4-05) through the map. Sprint 4b parses it into fields
      // (S4-13); for now it is kept verbatim in the notes so nothing the user shared is lost, and with AI on it is
      // also put in the "Fill in from listing text" box, open, so the user need not copy their own text across.
      //
      // It arrives in the navigation **state**, not in a query parameter: shared listing text routinely contains
      // an owner's or broker's phone number, and a query parameter would put it in the URL bar and in browser
      // history, which is exactly the shared-computer case docs/11 §5.10 is about. Read now, synchronously: the
      // navigation is only "current" during this call.
      const sharedAll = String(sharedFromNavigation(this.router) ?? '');
      const shared = sharedAll.slice(0, AI_MAX_LISTING_CHARS);
      if (shared) {
        // The box gets all of it, so the hint says how much Extract leaves out; the notes keep the first part, as before.
        this.listing.show(sharedAll);
      }
      if (hasPosition) {
        // Placed by a tap or the crosshair on the map (MapPage.createAt): the source is MAP until the person says
        // otherwise (FR-068); *Use my location* makes it GPS.
        this.openDraft(lat, lon, shared, 'MAP');
      } else {
        // No position (share target, bookmark): never 0°, 0°. Start from the last map view, or the newest house,
        // or the country, and require the pin to be put before saving.
        this.position.placed.set(false);
        void this.startWithoutPosition(shared);
      }
      return;
    }
    this.storeKey = draftKey(id, null, null);
    this.api.house(id).subscribe({
      next: (h) => {
        this.draft.set(withCost(h));
        this.loading.set(false);
        this.afterLoad();
      },
      error: (err: unknown) => {
        // A house that is not in this browser: the "House not found" card says it; a second message on top of the
        // page would say the same thing twice.
        if (!(err instanceof LocalDataError && err.key === 'error.notFoundLocal')) this.error.set(runResult(errorMsg(err)));
        this.loading.set(false);
      },
    });
    this.api.visits(id).subscribe({
      next: (v) => this.visits.set(v),
      error: () => this.visits.set([]),
    });
    this.photos.refresh(id);
  }

  /**
   * "Back" returns to where the house was opened from — Compare, an Ask citation, a Plan stop, the filtered list —
   * when the app has an earlier page in this tab; otherwise (a bookmark, a shared link, a reload, an arrival with
   * Back or Forward) it is a link to the map carrying the list's last search and filter. See back-target.ts.
   *
   * Called from the constructor, while the navigation to this page is still current; `lastSuccessfulNavigation` is
   * the same navigation once it has finished, as a fallback.
   */
  private initBack(): void {
    const nav = this.router.currentNavigation() ?? this.router.lastSuccessfulNavigation();
    if (!nav) return;
    const previous = nav.previousNavigation;
    const previousPath = previous
      ? this.router.serializeUrl(previous.finalUrl ?? previous.extractedUrl).split(/[?#]/)[0]
      : null;
    const state: Record<string, unknown> | undefined = nav.extras.state;
    const target = backTarget({ trigger: nav.trigger, previousPath, handedBackKey: state?.[HOUSE_BACK_STATE] });
    this.backToPrevious.set(target.toPrevious);
    this.backKey.set(target.key);
  }

  /**
   * The toolbar's Back. The unsaved-changes question is asked **before** going back, not by the guard during the
   * popstate: "Keep editing" then leaves history exactly as it was, and "Leave" or "Save first" goes back once the
   * answer is in. (The router is also set to restore history on a cancelled Back, app.config.ts, which covers the
   * browser's and Android's own Back button.)
   */
  protected async goBack(): Promise<void> {
    if (!this.backToPrevious()) {
      this.toList();
      return;
    }
    if (this.leaveApproved) return; // a second press while the first Back is under way
    if (!(await this.canLeave())) return;
    this.leaveApproved = true;
    this.location.back();
  }

  /** To the list as the user left it (search, filter, sort), not the plain list: a bookmark, a reload. */
  private toList(): void {
    void this.router.navigate(['/'], { queryParams: this.listReturn.queryParams() });
  }

  /**
   * After Delete, or Discard on a new house: to the list, without leaving the removed page behind it in history
   * (see {@link exitAfterRemoval}). Back from the list must not open "House not found" or a fresh empty form.
   */
  private leaveAfterRemoval(): void {
    if (exitAfterRemoval({ toPrevious: this.backToPrevious(), key: this.backKey() }) === 'back') {
      this.leaveApproved = true;
      this.location.back();
      return;
    }
    void this.router.navigate(['/'], { queryParams: this.listReturn.queryParams(), replaceUrl: true });
  }

  /**
   * Starts a new house at the given position. A shared listing is first read by the no-AI parser, and the draft then
   * counts as edited.
   */
  private openDraft(lat: number, lon: number, shared: string, source: LocationSource | null = null): void {
    const draft = withCost(newHouse(lat, lon, source));
    this.draft.set(draft);
    if (shared) {
      // The no-AI parser first (docs/11 5.29): the price, BHK, locality, link and phone the share text says, and the
      // whole text in the notes so nothing shared is lost; *Fill in from listing text* (AI) stays the second pass.
      this.listing.apply(parseListingText(shared));
      this.dirty.set(true);
    }
    this.loading.set(false);
    this.afterLoad();
  }

  /**
   * A starting view for a new house with no position: the last map view, else the newest house, else India. A stored
   * view still centred on the country placeholder is skipped (loadStartPoint): the map page saves it on its first
   * layout, so it is not a place the user chose, and the form would otherwise open at street zoom over central India.
   */
  private async startWithoutPosition(shared: string): Promise<void> {
    const view = loadStartPoint();
    if (view) {
      this.position.startZoom.set(Math.max(view.zoom, 12));
      this.openDraft(round6(view.lat), round6(view.lon), shared);
      return;
    }
    try {
      const houses = await firstValueFrom(this.api.houses());
      // The user may have left /houses/new during the read: no draft, no "draft restored" or title on the next page.
      if (this.destroyed) return;
      // houses() already leaves deleted houses out (liveHouses); filtered here too, as Plan does, so a change there
      // can never open a new house over a deleted one.
      const newest = [...houses]
        .filter((h) => !h.deleted)
        .sort((a, b) => Date.parse(b.createdAt ?? '') - Date.parse(a.createdAt ?? ''))[0];
      if (newest) {
        this.position.startZoom.set(13);
        this.openDraft(newest.lat, newest.lon, shared);
        return;
      }
    } catch {
      // Fall through to the country view.
    }
    // A failed read can also answer after the page is gone.
    if (this.destroyed) return;
    this.position.startZoom.set(COUNTRY_VIEW.zoom);
    this.openDraft(COUNTRY_VIEW.lat, COUNTRY_VIEW.lon, shared);
  }

  /**
   * The house is on screen: remember it as loaded (for "Discard"), put back unsaved edits a discarded tab left in
   * sessionStorage, and name the browser tab after the house.
   */
  private afterLoad(): void {
    const d = this.draft();
    if (!d) return;
    this.pristine = { draft: clone(d), dirty: this.dirty(), locationSet: this.position.placed() };
    const stored = readDraft(this.storeKey);
    if (stored && (this.isNew() || stored.draft.id === d.id)) {
      this.draft.set(withCost(stored.draft));
      this.position.placed.set(stored.locationSet);
      this.dirty.set(true);
      this.restored.set(true);
      this.announcer.announce({ key: 'house.draftRestored' });
    }
    this.applyTitle();
  }

  /** "Discard" on the restored-edits note: back to the house as it is saved. */
  protected discardRestored(): void {
    const p = this.pristine;
    if (!p) return;
    clearTimeout(this.draftTimer);
    clearDraft(this.storeKey);
    this.draft.set(clone(p.draft));
    this.dirty.set(p.dirty);
    this.position.placed.set(p.locationSet);
    this.position.coordsInvalid.set({ lat: false, lon: false });
    this.restored.set(false);
    this.announcer.announce({ key: 'house.draftDiscarded' });
    // The note and its button are gone: focus goes to the page title rather than to <body>.
    afterNextRender(() => document.getElementById('house-title')?.focus(), { injector: this.injector });
  }

  /** "Blue gate 2BHK · Doorprints" in the tab bar, once the house is loaded or saved (not for a new one). */
  private applyTitle(): void {
    const d = this.draft();
    if (!d || this.isNew()) return;
    const name: Msg | string = d.label.trim() || { key: 'common.untitled' };
    this.pageTitle.message.set({ key: 'title.houseNamed', params: { name } });
  }

  /** The *Included in the rent* switch of the Cost section. */
  protected setIncluded(event: Event): void {
    const d = this.draft();
    if (!d) return;
    this.patch({ cost: { ...d.cost, maintenanceIncluded: (event.target as HTMLInputElement).checked } });
  }

  /**
   * "Monthly cost ₹34,500 · To move in ₹1,28,000 · ₹27 per sq ft" under the Cost fields, from what is typed so far
   * (the same `costSummary` as Compare and the readable copies); null while nothing computes.
   */
  protected costLine(d: HouseDto): string | null {
    const s = costSummary({
      price: toWholeNumber(d.price),
      priceType: d.priceType,
      areaSqft: toWholeNumber(d.areaSqft),
      cost: cleanCost(d.cost),
    });
    const parts: string[] = [];
    if (s.monthlyCost !== null) parts.push(this.i18n.t('cost.lineMonthly', { v: this.i18n.price(s.monthlyCost, null) }));
    if (s.moveIn !== null) parts.push(this.i18n.t('cost.lineMoveIn', { v: this.i18n.price(s.moveIn, null) }));
    if (s.perSqFt !== null) parts.push(this.i18n.t('cost.linePerSqFt', { v: this.i18n.price(Math.round(s.perSqFt), null) }));
    return parts.length ? parts.join(' · ') : null;
  }

  /**
   * Route guard hook: photos still uploading, or unsaved edits, are never left behind without asking. The unsaved
   * question offers the same three ways as the reload dialog and Android: Cancel (keep editing), Save first, and
   * Leave without saving.
   */
  canLeave(): boolean | Promise<boolean> {
    if (this.leaveApproved && this.router.currentNavigation()?.trigger === 'popstate') {
      // The Back this page started itself, after asking (goBack) or after Delete or Discard: once only.
      this.leaveApproved = false;
      return true;
    }
    if (this.photos.uploading() === 0 && !this.dirty()) return true;
    return this.askToLeave();
  }

  /**
   * The three-way question before leaving with unsaved edits or photos still uploading: stay, leave, or save first
   * (which leaves only if the save succeeds).
   */
  private async askToLeave(): Promise<boolean> {
    if (this.photos.uploading() > 0) {
      const go = await this.confirm.ask({ key: 'confirm.leaveUploading' }, { confirmKey: 'confirm.leaveAnyway', danger: true });
      if (!go) return false;
    }
    if (!this.dirty()) return true;
    const answer = await this.confirm.choose(
      { key: 'confirm.leaveUnsaved' },
      { altKey: 'confirm.saveFirst', confirmKey: 'confirm.leave', danger: true },
    );
    if (answer === 'cancel') return false;
    // "Save first": the navigation the user asked for carries on once the house is saved (a refused save, such as
    // a missing name, keeps the page with the reason shown).
    if (answer === 'alt') return this.persist(false);
    return true;
  }

  protected score(h: HouseDto): number | null {
    return this.result(h).overall;
  }

  /** The score result of the house as edited: overall, coverage and the must-haves it misses or has not checked. */
  protected result(h: HouseDto): ScoreResult {
    return evaluateScore(h.checklist, h.rating, this.scoring());
  }

  /** A criterion's name: a built-in's translation, a custom criterion's own label. */
  protected nameOf(c: Pick<Criterion, 'key' | 'label'>): string {
    return criterionName(c, (key) => this.i18n.t(key));
  }

  /** The names of some criterion keys, for the must-have lines. */
  protected namesOf(keys: readonly string[]): string {
    return keys.map((key) => this.nameOf(this.scoring().criteria.find((c) => c.key === key) ?? { key })).join(', ');
  }

  /**
   * Records that the form has unsaved edits: clears the saved notice and the name error, withdraws the leave approval
   * and schedules the sessionStorage copy.
   */
  protected markDirty(): void {
    this.dirty.set(true);
    this.listing.touched(); // an edit may take a "from the listing" mark away (S4b-BL-238)
    this.justSaved.set(false);
    this.leaveApproved = false;
    if (this.nameError() && this.draft()?.label.trim()) {
      this.nameError.set(false);
      if (this.error()?.value.key === 'house.nameRequired') this.error.set(null);
    }
    this.scheduleDraftSave();
  }

  /** Writes the unsaved draft to sessionStorage once typing pauses (see draft-store.ts). */
  private scheduleDraftSave(): void {
    clearTimeout(this.draftTimer);
    this.draftTimer = setTimeout(() => {
      const d = this.draft();
      if (d && this.dirty() && this.storeKey) writeDraft(this.storeKey, { draft: d, locationSet: this.position.placed() });
    }, DRAFT_SAVE_MS);
  }

  /** The one way the form changes the draft: merges the fields in and marks the page dirty. */
  private patch(changes: Partial<HouseDto>): void {
    const d = this.draft();
    if (!d) return;
    this.draft.set({ ...d, ...changes });
    this.markDirty();
  }

  // ---- Questions to ask (slice 3a, docs/11 5.5) ----

  /**
   * The answers in card order: open ones first, then by sort. The order is worked out again only when an answer is
   * added or removed, not while one is typed or skipped, so the card being typed in stays where it is.
   */
  protected shownAnswers(d: HouseDto): HouseAnswer[] {
    const list = d.answers ?? [];
    if (list.length !== this.answerIds.length || list.some((a) => !this.answerIds.includes(a.id))) {
      this.answerIds = ordered(list).map((a) => a.id);
    }
    return this.answerIds.map((id) => list.find((a) => a.id === id)).filter((a): a is HouseAnswer => a !== undefined);
  }

  /** "3 of 8 answered". */
  protected answeredSummary(d: HouseDto): string {
    const list = d.answers ?? [];
    return this.i18n.t('questions.summary', { n: list.filter((a) => a.status === 'ANSWERED').length, total: list.length });
  }

  private say = (key: TKey, params?: Readonly<Record<string, string | number>>): string => this.i18n.t(key, params);

  /** Adds the bank's usual questions this house does not have yet, and moves focus to the first new one. */
  protected addUsualQuestions(): void {
    const d = this.draft();
    if (!d) return;
    const before = d.answers ?? [];
    const next = addUsual(d, this.bank(), before, this.say);
    if (next.length === before.length) return;
    this.patch({ answers: next });
    this.announcer.announce({ key: 'questions.usualAdded', params: { n: next.length - before.length } });
    afterNextRender(() => document.getElementById('answer-' + next[before.length].id)?.focus(), { injector: this.injector });
  }

  /** Adds one bank question; a cost value already on the house pre-fills the answer (5.21). */
  protected addBankQuestion(question: Question): void {
    const d = this.draft();
    if (!d || (d.answers ?? []).length >= MAX_ANSWERS) return;
    this.addAnswer(d, answerFor(question, d, this.nextSort(d), this.say));
  }

  /** Adds a question that is not in the bank ("Or ask something else"). */
  protected addAdhocQuestion(): void {
    const d = this.draft();
    const text = this.adhocText.trim();
    if (!d || text === '' || (d.answers ?? []).length >= MAX_ANSWERS) return;
    this.adhocText = '';
    this.addAnswer(d, { id: uuid(), text, status: 'OPEN', sort: this.nextSort(d) });
  }

  private nextSort(d: HouseDto): number {
    return (d.answers ?? []).reduce((max, a) => Math.max(max, a.sort + 1), 0);
  }

  private addAnswer(d: HouseDto, answer: HouseAnswer): void {
    this.patch({ answers: [...(d.answers ?? []), answer] });
    this.pickerOpen.set(false);
    afterNextRender(() => document.getElementById('answer-' + answer.id)?.focus(), { injector: this.injector });
  }

  private editAnswer(id: string, changes: Partial<HouseAnswer>): void {
    const d = this.draft();
    if (!d) return;
    this.patch({ answers: (d.answers ?? []).map((a) => (a.id === id ? { ...a, ...changes } : a)) });
  }

  /** Typing an answer marks the question Answered; clearing it marks it Open again. */
  protected setAnswer(id: string, value: string): void {
    this.editAnswer(id, value.trim() === '' ? { answer: null, status: 'OPEN' } : { answer: value, status: 'ANSWERED' });
  }

  /** The Skip toggle: Skipped, or back to Answered/Open by whether there is an answer. */
  protected toggleSkip(a: HouseAnswer): void {
    this.editAnswer(a.id, { status: a.status === 'SKIPPED' ? (a.answer?.trim() ? 'ANSWERED' : 'OPEN') : 'SKIPPED' });
  }

  protected removeAnswer(id: string): void {
    const d = this.draft();
    if (!d) return;
    this.patch({ answers: (d.answers ?? []).filter((a) => a.id !== id) });
    // The focus goes to Add a question, not to the top of the page.
    afterNextRender(() => document.getElementById('questions-add')?.focus(), { injector: this.injector });
  }

  protected categoryKey(category: QuestionCategory): TKey {
    return `questions.category.${category}` as TKey;
  }

  // ---- The duplicate-flat warning (S4b-BL-85) ----

  /**
   * "Maybe the same flat as …": the other houses within about 30 m with the same bedrooms and floor as what is typed
   * (`duplicateFlats`, docs/11 5.25); null when there are none. A warning, never a block.
   */
  protected sameFlatLine(d: HouseDto): string | null {
    const others = this.others();
    const facts = { ...d, bedrooms: toWholeNumber(d.bedrooms), floor: floorOf(d.floor), rooms: cleanRooms(d.rooms) };
    const ids = duplicateFlats(facts, others);
    if (ids.length === 0) return null;
    const names = ids.map((id) => others.find((h) => h.id === id)?.label?.trim() || this.i18n.t('common.untitled'));
    return this.i18n.t('house.duplicateFlat', { names: this.i18n.list(names) });
  }

  /** The Broker select: a broker fills the contact name and phone from it; None keeps what is there and unlinks. */
  protected pickBroker(id: string): void {
    const row = this.brokers().find((b) => b.id === id);
    if (row) this.patch({ brokerId: row.id, contactName: row.broker.name, contactPhone: row.broker.phone ?? null });
    else this.patch({ brokerId: null });
  }

  /** "New broker": unlinks and clears the contact, so a broker is made from what is typed when the house is saved. */
  protected newBroker(): void {
    this.patch({ brokerId: null, contactName: null, contactPhone: null });
    document.getElementById('house-contact')?.focus();
  }

  /**
   * The answer to *Mark the other houses Not chosen?* for a house just given TAKEN, kept until the house is saved (the
   * other houses change then, in `persist`); null while the house is not being newly taken.
   */
  private takenChoice: 'mark' | 'keep' | null = null;

  /**
   * Choosing a status. Choosing Taken asks *Mark the other houses Not chosen?* first (Mark them Not chosen / Keep them);
   * Cancel leaves the status as it was. Either way the house that was Taken goes back to Shortlisted when this one is
   * saved (at most one house is Taken).
   */
  protected async setStatus(status: HouseStatus, event?: Event): Promise<void> {
    const d = this.draft();
    if (!d || d.status === status) return;
    if (status === 'TAKEN') {
      const answer = await this.confirm.choose({ key: 'status.markOthersTitle' }, { confirmKey: 'status.markThem', altKey: 'status.keepThem' });
      if (answer === 'cancel') {
        // The radio the person pressed is checked in the DOM already; put the old one back.
        const pressed = event?.target instanceof HTMLInputElement ? event.target : null;
        pressed?.closest('fieldset')?.querySelector<HTMLInputElement>(`input[name="status"][value="${d.status}"]`)?.click();
        return;
      }
      this.takenChoice = answer === 'confirm' ? 'mark' : 'keep';
    } else {
      this.takenChoice = null;
    }
    this.patch({ status });
  }

  /** The Moving in card changed the date, notes or items; saved with the house. */
  protected setMoveIn(moveIn: MoveIn | null): void {
    this.patch({ moveIn });
  }

  /** *Close this hunt* saves the house first, so it is stored as Taken. */
  protected readonly saveBeforeClose = (): Promise<boolean> => (this.dirty() || this.isNew() ? this.persist(false) : Promise.resolve(true));

  /** A condition photo opened from the Moving in card. */
  protected openConditionPhoto(photo: OpenedPhoto): void {
    this.lightbox.set(photo);
  }

  protected setRating(n: number | null): void {
    this.patch({ rating: n });
  }

  /** "Clear rating" goes away with the rating: focus moves to the first star instead of falling to <body>. */
  protected clearRating(): void {
    this.setRating(null);
    afterNextRender(() => document.getElementById('rating-1')?.focus(), { injector: this.injector });
  }

  protected checkValue(h: HouseDto, key: string): number | null {
    const v = h.checklist[key];
    return typeof v === 'number' ? v : null;
  }

  protected setCheck(key: string, value: number | null): void {
    const d = this.draft();
    if (!d) return;
    const checklist: Record<string, number> = { ...d.checklist };
    if (value === null) {
      delete checklist[key];
    } else {
      checklist[key] = value;
    }
    this.patch({ checklist });
  }

  /** The Save button; see {@link persist}. */
  protected save(): void {
    void this.persist();
  }

  /**
   * Validates and saves; resolves true once saved. Also the update banner's "Save first" (UnsavedChanges) and the
   * leave dialog's, so a refused save (no name, no position) keeps the page — and the reload or navigation waits.
   * `navigateAfterCreate` is false from the leave dialog: the navigation the user started goes on from there.
   */
  private async persist(navigateAfterCreate = true): Promise<boolean> {
    const d = this.draft();
    if (!d || this.saving()) return false;
    const label = d.label.trim();
    if (!label) {
      this.nameError.set(true);
      this.error.set(runResult({ key: 'house.nameRequired' }));
      document.getElementById('house-name')?.focus();
      return false;
    }
    this.nameError.set(false);
    if (!this.position.placed()) {
      this.position.required.set(true);
      this.error.set(runResult({ key: 'house.locationRequired' }));
      const lat = document.getElementById('house-lat');
      lat?.scrollIntoView({ block: 'center' });
      lat?.focus({ preventScroll: true });
      return false;
    }
    const invalid = this.position.coordsInvalid();
    if (invalid.lat || invalid.lon) {
      // A typed coordinate is not valid: saving now would keep the old pin without saying so.
      this.error.set(runResult({ key: 'house.coordsInvalid' }));
      const field = document.getElementById(invalid.lat ? 'house-lat' : 'house-lon');
      field?.scrollIntoView({ block: 'center' });
      field?.focus({ preventScroll: true });
      return false;
    }
    if (this.floor.invalid(d)) {
      // Not a floor from -5 to 200: saving would drop what was typed without a word, so the field's message stays and
      // focus goes to it (WCAG 3.3.1, 3.3.3), as for the name and the position above.
      this.error.set(runResult({ key: this.floor.basement(d) ? 'house.floorBasementInvalid' : 'house.floorInvalid' }));
      const field = document.getElementById('house-floor');
      field?.scrollIntoView({ block: 'center' });
      field?.focus({ preventScroll: true });
      return false;
    }
    const body: HouseDto = {
      ...d,
      label,
      address: blankToNull(d.address),
      street: blankToNull(d.street),
      locality: blankToNull(d.locality),
      contactName: blankToNull(d.contactName),
      contactPhone: blankToNull(d.contactPhone),
      listingUrl: blankToNull(d.listingUrl),
      notes: blankToNull(d.notes),
      price: toWholeNumber(d.price),
      bedrooms: toWholeNumber(d.bedrooms),
      areaSqft: toWholeNumber(d.areaSqft),
      // -5..200 or unknown (S4b-BL-87); the field says so when what is typed is not a floor.
      floor: floorOf(d.floor),
      // Only the set fields, in range, or null: the store and the wire never see an empty `{}` (slice 1a).
      cost: cleanCost(d.cost),
      // At most 30, coerced and sorted; absent when empty (never [] on the wire).
      rooms: cleanRooms(d.rooms),
      // At most 60, coerced and sorted; absent when empty (slice 3a).
      answers: cleanAnswers(d.answers),
      // Date, notes and at most 30 items; absent when it has none of them (slice 5).
      moveIn: cleanMoveIn(d.moveIn),
    };
    this.saving.set(true);
    // Said to screen readers as Android's Save says it through its contentDescription (web UX gate r4): aria-busy on
    // the button is not spoken. The announcer sets its text 100 ms later, and a new announcement cancels a pending
    // one, so a save that ends first is heard only as "Saved", not as "Saving…" and "Saved" one after the other.
    // A failure withdraws "Saving…", pending or shown (a local IndexedDB refusal such as storageFull usually comes
    // back inside the 100 ms), so the role="alert" "Could not save…" is the last thing heard; cancel() drops only
    // this message, never another feature's. Every end path replaces or withdraws it: success says "Saved", failure
    // withdraws it, and leaving the page does not stop the save, whose own end still does one or the other. A save
    // refused before it starts (no name, no position) never says "Saving…".
    this.announcer.announce(SAVING);
    // An earlier "Could not save" stays at the top, drawn as being updated, while this save runs; its end removes it
    // or puts the new failure in its place, so the form does not jump up and back (S4b-BL-2).
    try {
      const saved = await firstValueFrom(this.api.saveHouse(body));
      this.saving.set(false);
      this.error.set(null);
      this.dirty.set(false);
      this.restored.set(false);
      clearTimeout(this.draftTimer);
      clearDraft(this.storeKey);
      this.announcer.announce({ key: 'house.saved' });
      await this.applyTaken(saved);
      if (this.isNew()) {
        if (navigateAfterCreate) {
          // The form's history entry is replaced: its Back decision goes with it to the saved house's page.
          void this.router.navigate(['/houses', saved.id], {
            replaceUrl: true,
            state: this.backToPrevious() ? { [HOUSE_BACK_STATE]: this.backKey() } : undefined,
          });
        }
      } else {
        this.draft.set(withCost(saved));
        this.pristine = { draft: clone(withCost(saved)), dirty: false, locationSet: true };
        this.justSaved.set(true);
        this.applyTitle();
      }
      return true;
    } catch (err: unknown) {
      this.saving.set(false);
      this.announcer.cancel(SAVING);
      this.error.set(runResult({ key: 'house.saveFailed', params: { reason: errorMsg(err) } }));
      return false;
    }
  }

  /** A house newly given Taken is saved: the Taken one before goes back to Shortlisted, and the others are marked if asked. */
  private async applyTaken(saved: HouseDto): Promise<void> {
    const choice = this.takenChoice;
    if (choice === null || saved.status !== 'TAKEN') return;
    this.takenChoice = null;
    try {
      const n = await firstValueFrom(this.api.applyTaken(saved.id, choice === 'mark'));
      if (n > 0) this.announcer.announce({ key: 'status.othersMarked', params: { n } });
    } catch (err: unknown) {
      this.error.set(runResult({ key: 'house.saveFailed', params: { reason: errorMsg(err) } }));
    }
  }

  /** A saved walk (or null) for the page's own map; the map is brought into view so *Show on map* shows something. */
  protected showOverlay(overlay: MapOverlay | null): void {
    this.overlay.set(overlay);
    if (overlay?.fit) document.querySelector('app-location-map')?.scrollIntoView({ block: 'nearest' });
  }

  /**
   * Deletes the house after asking (the question says if saved walks go with it), or for a new house discards the form.
   * Either way the page leaves without staying in history.
   */
  protected async deleteHouse(): Promise<void> {
    const d = this.draft();
    if (!d) return;
    this.dangerMsg.set(null);
    if (this.isNew()) {
      // "Discard" on a half-typed form, or on a listing handed over from /share, loses it with one tap on a red
      // button, and clearing `dirty` first also skips canLeave(); so ask the same question canLeave() would.
      if (this.dirty()) {
        const discard = await this.confirm.ask({ key: 'confirm.leaveUnsaved' }, { confirmKey: 'house.discard', danger: true });
        if (!discard) return;
      }
      this.dirty.set(false);
      clearTimeout(this.draftTimer);
      clearDraft(this.storeKey);
      this.leaveAfterRemoval();
      return;
    }
    const name = d.label || this.i18n.t('house.thisHouse');
    // The website has no undo: a house's saved walks are deleted with it, and the question says so when it has any.
    let walks = 0;
    try {
      walks = await this.traces.savedCount(d.id);
    } catch {
      // Unreadable: the plain question; LocalStore.deleteHouse still deletes the walks.
    }
    const ok = await this.confirm.ask({ key: walks > 0 ? 'trace.houseDelete.confirm' : 'confirm.deleteHouse', params: { name } }, {
      confirmKey: 'house.delete',
      danger: true,
    });
    if (!ok) return;
    this.api.deleteHouse(d.id).subscribe({
      next: () => {
        this.dirty.set(false);
        clearTimeout(this.draftTimer);
        clearDraft(this.storeKey);
        this.announcer.announce({ key: 'house.deleted' });
        this.leaveAfterRemoval();
      },
      error: (err: unknown) =>
        this.dangerMsg.set(runResult({ key: 'house.deleteFailed', params: { reason: errorMsg(err) } })),
    });
  }

  /** Records a manual visit to this house now, at its position. */
  protected markVisited(): void {
    const d = this.draft();
    if (!d || this.isNew() || this.markingVisit()) return;
    const now = new Date().toISOString();
    const visit: VisitDto = {
      id: uuid(),
      houseId: d.id,
      lat: d.lat,
      lon: d.lon,
      street: d.street ?? null,
      arrivedAt: now,
      leftAt: null,
      source: 'MANUAL',
      deleted: false,
      syncVersion: 0,
    };
    this.markingVisit.set(true);
    this.visitsMsg.set(null);
    this.api.saveVisit(visit).subscribe({
      next: (saved) => {
        this.visits.update((list) => [saved ?? visit, ...list.filter((v) => v.id !== visit.id)]);
        this.markingVisit.set(false);
        this.announcer.announce({ key: 'house.visitRecorded' });
      },
      error: (err: unknown) => {
        this.visitsMsg.set(runResult({ key: 'house.visitFailed', params: { reason: errorMsg(err) } }));
        this.markingVisit.set(false);
      },
    });
  }

  protected async deleteVisit(v: VisitDto): Promise<void> {
    const ok = await this.confirm.ask({ key: 'confirm.removeVisit' }, { confirmKey: 'confirm.remove', danger: true });
    if (!ok) return;
    this.visitsMsg.set(null);
    this.api.deleteVisit(v.id).subscribe({
      next: () => {
        this.visits.update((list) => list.filter((x) => x.id !== v.id));
        this.announcer.announce({ key: 'house.visitRemoved' });
        document.getElementById('visits-heading')?.focus();
      },
      error: (err: unknown) =>
        this.visitsMsg.set(runResult({ key: 'house.removeVisitFailed', params: { reason: errorMsg(err) } })),
    });
  }

  /** *Add a photo* of the condition record: the photo is stored with MOVE_IN already chosen. */
  protected onConditionFiles(event: Event): Promise<void> {
    return this.onFiles(event, { roomId: null, tags: [MOVE_IN_TAG], caption: null });
  }

  /** The files chosen in a photo input are added to the house (the input is emptied, so the same file can be chosen again). */
  protected onFiles(event: Event, meta?: PhotoMetaInput): Promise<void> {
    const input = event.target as HTMLInputElement;
    const files: File[] = input.files ? Array.from(input.files) : [];
    input.value = '';
    return this.photos.add(files, meta);
  }

  protected openPhoto(src: string, n: number): void {
    const name = this.draft()?.label || this.i18n.t('common.untitled');
    this.lightbox.set({ src, alt: this.i18n.t('house.photoAlt', { n, name }) });
  }

  protected closePhoto(): void {
    this.lightbox.set(null);
  }

  /** A click on the backdrop (the dialog element itself, outside its content) closes the viewer. */
  protected onViewerClick(event: MouseEvent): void {
    const dialog = this.viewer()?.nativeElement;
    if (dialog && event.target === dialog) dialog.close();
  }

  protected readonly isStorageFull = isStorageFull;

  /**
   * The listing link for the page: http(s) as typed, a bare domain with `https://` added, and null for any other scheme
   * (such as javascript:), so a saved link can never run code.
   */
  protected listingHref(url: string | null | undefined): string | null {
    const u = (url ?? '').trim();
    // Match http:// or https://
    if (/^https?:\/\//i.test(u)) return u;
    // Block malicious schemes (javascript:, intent:, ms-word:, data:, file:, etc)
    if (/^[a-z][a-z0-9+.-]*:/i.test(u)) return null;
    // Bare domain without scheme: add https://
    return `https://${u}`;
  }
}

/** A deep copy by JSON, for the saved state kept to restore on Discard. */
function clone(h: HouseDto): HouseDto {
  return JSON.parse(JSON.stringify(h)) as HouseDto;
}

/** The form binds the Cost fields to `cost.*`, so a draft always carries an object there (null on the wire). */
function withCost(h: HouseDto): HouseDto {
  return h.cost ? h : { ...h, cost: {} };
}

/** Trims the text; blank becomes null. */
function blankToNull(s: string | null | undefined): string | null {
  const t = (s ?? '').trim();
  return t ? t : null;
}

/** A non-negative whole number, or null when the value is not a finite number. */
function toWholeNumber(n: number | null | undefined): number | null {
  if (n === null || n === undefined || typeof n !== 'number' || !Number.isFinite(n)) return null;
  return Math.max(0, Math.round(n));
}

/**
 * The listing text the share target handed over, from the navigation state.
 *
 * `Router.currentNavigation()` is only non-null while a navigation is in flight, which it is **not** any more by the
 * target component's `ngOnInit` (the zoneless scheduler runs the first change detection in a later task); the last
 * successful navigation is the same navigation by then. `history.state` covers a restored or re-entered route. All
 * are read defensively because anything can be in `history.state`.
 */
export function sharedFromNavigation(router: Router): string | null {
  const fromNavigation = (router.currentNavigation() ?? router.lastSuccessfulNavigation())?.extras.state;
  const state: unknown = fromNavigation ?? (typeof history === 'undefined' ? null : history.state);
  if (!state || typeof state !== 'object') return null;
  const shared = (state as { shared?: unknown }).shared;
  return typeof shared === 'string' && shared !== '' ? shared : null;
}

/** Brokers by name for the select, then by id, so equal names keep a fixed order. */
export function sortBrokers(rows: readonly BrokerRow[]): BrokerRow[] {
  return [...rows].sort(
    (a, b) => a.broker.name.localeCompare(b.broker.name) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0),
  );
}
