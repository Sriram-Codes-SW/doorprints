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
import type { Subscription } from 'rxjs';
import { LocalDataService } from '../../core/local-data.service';
import { GeocodeService } from '../../core/geocode.service';
import {
  CHECKLIST,
  HouseDto,
  HouseStatus,
  STATUSES,
  STATUS_COLOR,
  STATUS_ICON,
  STATUS_KEY,
  VisitDto,
  houseScore,
  newHouse,
  uuid,
} from '../../core/models';
import { errorMsg, telHref } from '../../core/format';
import { LocalDataError } from '../../core/local-error';
import { Announcer } from '../../core/announcer.service';
import { resizeImage } from '../../core/image-resize';
import { LatLon, LocationMap, round6 } from '../../shared/location-map';
import { AuthImage } from '../../shared/auth-image';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TitleOverride } from '../../i18n/i18n-title.strategy';
import { ConfirmService } from '../../core/confirm.service';
import { AI_MAX_LISTING_CHARS, AiService, HouseDraft, aiErrorMsg } from '../../core/ai.service';
import { TPipe } from '../../i18n/t.pipe';
import { UnsavedChanges } from '../../core/unsaved-changes.service';
import { COUNTRY_VIEW, loadStartPoint, locationErrorKey, parseCoordinate } from '../../shared/map-center';
import { locateOnce } from '../../shared/locate-once';
import { AddressLookup, FIELD_LABEL, FillField, addressFill, mergeListingDraft } from './house-draft-merge';
import { clearDraft, draftKey, readDraft, writeDraft } from './draft-store';
import { type BackKey, HOUSE_BACK_STATE, backTarget, exitAfterRemoval } from './back-target';
import { ListReturn } from '../map/list-return';
import { GLYPHS } from '../../shared/glyphs';
import { RunResult, runResult } from '../../shared/run-result';

interface OpenPhoto {
  src: string;
  alt: string;
}

/** A photo that could not be added, named in the photos card. */
/** Said while a save runs; withdrawn on failure (persist()). */
const SAVING: Msg = { key: 'house.saving' };

interface PhotoFailure {
  file: string;
  reason: Msg;
}

/** How long typing pauses before the unsaved draft is written to sessionStorage. */
const DRAFT_SAVE_MS = 500;

@Component({
  selector: 'app-house-detail-page',
  imports: [FormsModule, RouterLink, LocationMap, AuthImage, TPipe],
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
  private readonly unsaved = inject(UnsavedChanges);
  private readonly pageTitle = inject(TitleOverride);
  private readonly injector = inject(Injector);
  /** The list's last search and filter, for "Back to map" when there is no page behind this one (and after Delete). */
  protected readonly listReturn = inject(ListReturn);
  protected readonly ai = inject(AiService);
  protected readonly i18n = inject(TranslationService);

  // "Fill in from listing text" (AI, new houses only; hidden unless the server has AI enabled).
  protected listingText = '';
  /** The listing box starts open when the house came from a shared listing (the text is already in it). */
  protected listingOpen = false;
  protected readonly listingMax = AI_MAX_LISTING_CHARS;
  protected readonly filling = signal(false);
  protected readonly fillWarnings = signal<string[]>([]);
  /** Typed values the fill kept, with what the listing says instead ("Kept your Name; the listing says …"). */
  protected readonly keptWarnings = signal<Msg[]>([]);
  /**
   * Why the listing could not be read: shown in the listing card, next to the button. Every message a button can
   * produce twice in a row with the same words is keyed on its run ({@link RunResult}), so the second one is a new
   * node in its live region and is read again (web UX gate R9).
   */
  protected readonly fillError = signal<RunResult<Msg> | null>(null);

  protected readonly draft = signal<HouseDto | null>(null);
  protected readonly isNew = signal(false);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly dirty = signal(false);
  protected readonly justSaved = signal(false);
  protected readonly nameError = signal(false);
  /** Save failures only (name or location missing, the save itself failed) and a failed load; at the top. */
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly geocoding = signal(false);
  /**
   * False for a new house opened without a position (the share target, a bookmarked /houses/new) until the user
   * has put the pin: chosen a spot on the map, typed coordinates, or used their location. Saving is refused until
   * then — a house saved at the starting view would be a house in the wrong place.
   */
  protected readonly locationSet = signal(true);
  protected readonly locationError = signal(false);
  protected readonly locating = signal(false);
  /** A failure of "Use my location" or "Fill address from map", shown under those buttons. */
  protected readonly locationMsg = signal<RunResult<Msg> | null>(null);
  /** Typed coordinates that are not valid; the typed text stays in the field and the pin stays where it was. */
  protected readonly coordsInvalid = signal<{ lat: boolean; lon: boolean }>({ lat: false, lon: false });
  protected readonly coordsError = computed(() => this.coordsInvalid().lat || this.coordsInvalid().lon);
  /** Zoom the location map opens at: street level for a known position, wider for a starting guess. */
  protected readonly startZoom = signal(16);
  protected readonly canLocate = typeof navigator !== 'undefined' && 'geolocation' in navigator;
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

  protected readonly photoIds = signal<string[]>([]);
  protected readonly uploading = signal(0);
  /** The files of the current batch that could not be added; one run per batch, so a batch failing again is read. */
  protected readonly photoFailures = signal<RunResult<readonly PhotoFailure[]> | null>(null);
  protected readonly photosMsg = signal<RunResult<Msg> | null>(null);
  protected readonly lightbox = signal<OpenPhoto | null>(null);
  /** A failed delete, shown next to the delete button. */
  protected readonly dangerMsg = signal<RunResult<Msg> | null>(null);
  private readonly viewer = viewChild<ElementRef<HTMLDialogElement>>('viewer');
  private readonly toolbar = viewChild<ElementRef<HTMLElement>>('toolbar');
  private toolbarObserver: ResizeObserver | null = null;
  /** Short viewports (landscape phone, 400% zoom): the toolbar is not sticky there (CSS), so nothing is reserved. */
  private shortQuery: MediaQueryList | null = null;
  private onShortChange: (() => void) | null = null;

  protected readonly checklist = CHECKLIST;
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
  /** Upload results of the files chosen together, announced once when the last one is done. */
  private batch = { added: 0, failed: 0 };
  /**
   * The listing read and the address lookup in flight, and whether the page is gone. Leaving the page is one of the
   * ends of "Reading the listing…", "Filling address…" and "Locating…": their late result must not fill a form,
   * open a question or announce "Form filled in" / "Location found" on whatever page the user went to.
   */
  private fillRequest: Subscription | null = null;
  private lookupRequest: Subscription | null = null;
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
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.fillRequest?.unsubscribe();
    this.lookupRequest?.unsubscribe();
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
    if ((!this.dirty() && this.uploading() === 0) || this.unsaved.leaving) return;
    event.preventDefault();
    // Older browsers only show the prompt when returnValue is set; the text itself is never displayed.
    event.returnValue = '';
  }

  ngOnInit(): void {
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
      const shared = String(sharedFromNavigation(this.router) ?? '').slice(0, AI_MAX_LISTING_CHARS);
      if (shared) {
        this.listingText = shared;
        this.listingOpen = true;
      }
      if (hasPosition) {
        this.openDraft(lat, lon, shared);
      } else {
        // No position (share target, bookmark): never 0°, 0°. Start from the last map view, or the newest house,
        // or the country, and require the pin to be put before saving.
        this.locationSet.set(false);
        void this.startWithoutPosition(shared);
      }
      return;
    }
    this.storeKey = draftKey(id, null, null);
    this.api.house(id).subscribe({
      next: (h) => {
        this.draft.set(h);
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
    this.api.photoIds(id).subscribe({
      next: (ids) => this.photoIds.set(ids),
      error: () => this.photoIds.set([]),
    });
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

  private openDraft(lat: number, lon: number, shared: string): void {
    const draft = newHouse(lat, lon);
    if (shared) {
      draft.notes = shared;
      this.dirty.set(true);
    }
    this.draft.set(draft);
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
      this.startZoom.set(Math.max(view.zoom, 12));
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
        this.startZoom.set(13);
        this.openDraft(newest.lat, newest.lon, shared);
        return;
      }
    } catch {
      // Fall through to the country view.
    }
    // A failed read can also answer after the page is gone.
    if (this.destroyed) return;
    this.startZoom.set(COUNTRY_VIEW.zoom);
    this.openDraft(COUNTRY_VIEW.lat, COUNTRY_VIEW.lon, shared);
  }

  /**
   * The house is on screen: remember it as loaded (for "Discard"), put back unsaved edits a discarded tab left in
   * sessionStorage, and name the browser tab after the house.
   */
  private afterLoad(): void {
    const d = this.draft();
    if (!d) return;
    this.pristine = { draft: clone(d), dirty: this.dirty(), locationSet: this.locationSet() };
    const stored = readDraft(this.storeKey);
    if (stored && (this.isNew() || stored.draft.id === d.id)) {
      this.draft.set(stored.draft);
      this.locationSet.set(stored.locationSet);
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
    this.locationSet.set(p.locationSet);
    this.coordsInvalid.set({ lat: false, lon: false });
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

  /** "Use my location" (Permissions-Policy allows geolocation for this origin): puts the pin where the user is. */
  protected useMyLocation(): void {
    if (!this.canLocate || this.locating()) return;
    this.locating.set(true);
    // The last failure under the buttons stays, drawn as being updated, until this run ends (S4b-BL-2).
    // locateOnce drops the answer, found or failed, when this page is gone by then (the destroyed guard).
    locateOnce({
      gone: () => this.destroyed,
      found: (pos) => {
        this.locating.set(false);
        this.locationMsg.set(null);
        this.coordsInvalid.set({ lat: false, lon: false });
        this.placePin(round6(pos.coords.latitude), round6(pos.coords.longitude));
        this.announcer.announce({ key: 'house.locationFound' });
      },
      failed: (err) => {
        this.locating.set(false);
        this.locationMsg.set(runResult({ key: locationErrorKey(err) }));
      },
    });
  }

  /** The user put the pin somewhere: the position now counts as set. */
  private placePin(lat: number, lon: number): void {
    this.locationSet.set(true);
    if (this.locationError()) {
      this.locationError.set(false);
      if (this.error()?.value.key === 'house.locationRequired') this.error.set(null);
    }
    this.patch({ lat, lon });
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
    if (this.uploading() === 0 && !this.dirty()) return true;
    return this.askToLeave();
  }

  private async askToLeave(): Promise<boolean> {
    if (this.uploading() > 0) {
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

  /**
   * Sends pasted listing text to POST /api/ai/extract-listing and fills the **empty** form fields; a typed value is
   * never replaced, and every one the listing disagrees with is named. Nothing is saved: the user reviews, picks
   * the map location and presses Add (docs/ai AI-004 human confirmation).
   */
  protected fillFromListing(): void {
    const text = this.listingText.trim();
    if (!text || this.filling()) return;
    this.filling.set(true);
    // The last failure stays, drawn as being updated, until this read ends and replaces or removes it (S4b-BL-2).
    this.fillWarnings.set([]);
    this.keptWarnings.set([]);
    this.fillRequest = this.ai.extractListing(text).subscribe({
      next: (draft) => {
        this.fillRequest = null;
        this.fillError.set(null);
        this.applyDraft(draft);
        this.fillWarnings.set(draft.warnings ?? []);
        this.filling.set(false);
        this.announcer.announce({
          key: this.keptWarnings().length > 0 ? 'listingFill.doneKept' : 'listingFill.done',
          params: { n: this.keptWarnings().length },
        });
        document.getElementById('house-name')?.focus();
      },
      error: (err: unknown) => {
        this.fillRequest = null;
        this.fillError.set(runResult({ key: 'listingFill.failed', params: { reason: aiErrorMsg(err) } }));
        this.filling.set(false);
      },
    });
  }

  private applyDraft(a: HouseDraft): void {
    const d = this.draft();
    if (!d) return;
    const result = mergeListingDraft(d, a);
    this.patch(result.changes);
    this.keptWarnings.set(
      result.kept.map((k) => ({
        key: 'listingFill.kept',
        params: { field: { key: FIELD_LABEL[k.field] }, value: this.shownValue(k.field, k.incoming) },
      })),
    );
  }

  /** A listing value as the form would show it: a price in rupees, a price type in words. */
  private shownValue(field: FillField, value: string | number): Msg | string {
    if (field === 'price' && typeof value === 'number') return this.i18n.price(value, null);
    if (field === 'priceType') return { key: value === 'SALE' ? 'price.sale' : 'price.rent' };
    return String(value);
  }

  protected score(h: HouseDto): number | null {
    return houseScore(h);
  }

  protected markDirty(): void {
    this.dirty.set(true);
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
      if (d && this.dirty() && this.storeKey) writeDraft(this.storeKey, { draft: d, locationSet: this.locationSet() });
    }, DRAFT_SAVE_MS);
  }

  private patch(changes: Partial<HouseDto>): void {
    const d = this.draft();
    if (!d) return;
    this.draft.set({ ...d, ...changes });
    this.markDirty();
  }

  protected setStatus(status: HouseStatus): void {
    this.patch({ status });
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

  protected onMoved(p: LatLon): void {
    this.coordsInvalid.set({ lat: false, lon: false });
    this.placePin(p.lat, p.lon);
  }

  /**
   * Typed coordinates: the keyboard alternative to dragging the pin. An invalid value is kept in the field (the user
   * corrects it rather than retyping it), the field is marked invalid with the reason under the fields, and the pin
   * stays where it was until the value is valid.
   */
  protected onCoord(axis: 'lat' | 'lon', event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = parseCoordinate(input.value, axis === 'lat' ? 90 : 180);
    const d = this.draft();
    if (!d) return;
    if (value === null) {
      this.coordsInvalid.update((c) => ({ ...c, [axis]: true }));
      return;
    }
    this.coordsInvalid.update((c) => ({ ...c, [axis]: false }));
    const lat = axis === 'lat' ? round6(value) : d.lat;
    const lon = axis === 'lon' ? round6(value) : d.lon;
    this.placePin(lat, lon);
  }

  protected coordsDescribedBy(axis: 'lat' | 'lon'): string | null {
    const ids = [this.locationSet() ? null : 'location-unset', this.coordsInvalid()[axis] ? 'coords-error' : null];
    return ids.filter((x) => !!x).join(' ') || null;
  }

  /**
   * "Fill address from map" fills the empty Address, Street and Locality (and the name from the street when there is
   * none). A value the user typed is only replaced after asking, with the old and new values shown; afterwards the
   * filled fields are named.
   */
  protected fillAddress(): void {
    const d = this.draft();
    if (!d || this.geocoding() || !this.locationSet()) return;
    this.geocoding.set(true);
    // As for "Use my location": the last failure stays, drawn as being updated, until the lookup ends (S4b-BL-2).
    this.lookupRequest = this.geocode.reverse(d.lat, d.lon).subscribe({
      next: (r) => {
        this.lookupRequest = null;
        this.geocoding.set(false);
        this.locationMsg.set(null);
        void this.applyAddress(r);
      },
      error: (err: unknown) => {
        this.lookupRequest = null;
        this.locationMsg.set(runResult({ key: 'house.lookupFailed', params: { reason: errorMsg(err) } }));
        this.geocoding.set(false);
      },
    });
  }

  private async applyAddress(found: AddressLookup): Promise<void> {
    const cur = this.draft();
    if (!cur) return;
    const fill = addressFill(cur, found);
    const changes: Partial<HouseDto> = { ...fill.emptyOnly };
    const filled: FillField[] = [...fill.filled];
    if (fill.conflicts.length > 0) {
      const lines = fill.conflicts
        .map((c) => this.i18n.t('house.addressChange', { field: { key: FIELD_LABEL[c.field] }, old: c.old, new: c.incoming }))
        .join('\n');
      const answer = await this.confirm.choose(
        { key: 'house.addressReplaceAsk', params: { changes: lines } },
        { confirmKey: 'house.addressReplace', altKey: filled.length > 0 ? 'house.addressFillEmpty' : null },
      );
      if (answer === 'cancel') return;
      if (answer === 'confirm') {
        for (const c of fill.conflicts) {
          changes[c.field] = c.incoming;
          filled.push(c.field);
        }
      }
    }
    if (filled.length === 0) {
      this.announcer.announce({ key: 'house.addressNothing' });
      return;
    }
    this.patch(changes);
    const fields = this.i18n.list(filled.map((f) => this.i18n.t(FIELD_LABEL[f])));
    this.announcer.announce({ key: 'house.addressFilledFields', params: { fields } });
  }

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
    if (!this.locationSet()) {
      this.locationError.set(true);
      this.error.set(runResult({ key: 'house.locationRequired' }));
      const lat = document.getElementById('house-lat');
      lat?.scrollIntoView({ block: 'center' });
      lat?.focus({ preventScroll: true });
      return false;
    }
    const invalid = this.coordsInvalid();
    if (invalid.lat || invalid.lon) {
      // A typed coordinate is not valid: saving now would keep the old pin without saying so.
      this.error.set(runResult({ key: 'house.coordsInvalid' }));
      const field = document.getElementById(invalid.lat ? 'house-lat' : 'house-lon');
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
      if (this.isNew()) {
        if (navigateAfterCreate) {
          // The form's history entry is replaced: its Back decision goes with it to the saved house's page.
          void this.router.navigate(['/houses', saved.id], {
            replaceUrl: true,
            state: this.backToPrevious() ? { [HOUSE_BACK_STATE]: this.backKey() } : undefined,
          });
        }
      } else {
        this.draft.set(saved);
        this.pristine = { draft: clone(saved), dirty: false, locationSet: true };
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
    const ok = await this.confirm.ask({ key: 'confirm.deleteHouse', params: { name } }, {
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

  /**
   * Adds the chosen photos one by one. The result is said **once**, when the last file is done ("Photos added: 3.
   * Not added: 1"), not once per photo, and every file that failed is listed in the photos card with its reason.
   */
  protected async onFiles(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const files: File[] = input.files ? Array.from(input.files) : [];
    input.value = '';
    const d = this.draft();
    if (!d || this.isNew() || files.length === 0) return;
    if (this.uploading() === 0) {
      // A new batch: the previous one's results are no longer news.
      this.batch = { added: 0, failed: 0 };
      this.photoFailures.set(null);
      this.photosMsg.set(null);
    }
    this.uploading.update((n) => n + files.length);
    for (const file of files) {
      try {
        const blob = await resizeImage(file, 1600, 0.8);
        const res = await firstValueFrom(this.api.uploadPhoto(d.id, blob));
        this.photoIds.update((ids) => (ids.includes(res.id) ? ids : [...ids, res.id]));
        this.batch.added++;
      } catch (err: unknown) {
        this.batch.failed++;
        // The batch's first failure starts its run; later ones join it, so the list grows in the same node.
        const failure: PhotoFailure = { file: file.name, reason: errorMsg(err) };
        this.photoFailures.update((r) =>
          r === null ? runResult<readonly PhotoFailure[]>([failure]) : { value: [...r.value, failure], run: r.run },
        );
      } finally {
        this.uploading.update((n) => n - 1);
      }
    }
    if (this.uploading() > 0) return;
    const { added, failed } = this.batch;
    this.announcer.announce(
      failed > 0 ? { key: 'house.photosResult', params: { added, failed } } : { key: 'house.photosAdded', params: { n: added } },
    );
  }

  protected async deletePhoto(id: string): Promise<void> {
    const ok = await this.confirm.ask({ key: 'confirm.deletePhoto' }, { confirmKey: 'common.delete', danger: true });
    if (!ok) return;
    this.photosMsg.set(null);
    this.api.deletePhoto(id).subscribe({
      next: () => {
        this.photoIds.update((ids) => ids.filter((x) => x !== id));
        this.lightbox.set(null);
        this.announcer.announce({ key: 'house.photoDeleted' });
        document.getElementById('photos-heading')?.focus();
      },
      error: (err: unknown) =>
        this.photosMsg.set(runResult({ key: 'house.deletePhotoFailed', params: { reason: errorMsg(err) } })),
    });
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

  /** A save or photo failed because the browser is out of space (directly, or as the reason of a wrapper message). */
  protected isStorageFull(m: Msg): boolean {
    if (m.key === 'error.storageFull') return true;
    const reason = m.params?.['reason'];
    return typeof reason === 'object' && reason !== null && reason.key === 'error.storageFull';
  }

  protected anyStorageFull(failures: readonly PhotoFailure[]): boolean {
    return failures.some((f) => this.isStorageFull(f.reason));
  }

  protected listingHref(url: string | null | undefined): string {
    const u = (url ?? '').trim();
    return /^[a-z][a-z0-9+.-]*:/i.test(u) ? u : `https://${u}`;
  }
}

function clone(h: HouseDto): HouseDto {
  return JSON.parse(JSON.stringify(h)) as HouseDto;
}

function blankToNull(s: string | null | undefined): string | null {
  const t = (s ?? '').trim();
  return t ? t : null;
}

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
