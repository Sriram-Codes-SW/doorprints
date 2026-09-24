import {
  Component,
  DestroyRef,
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
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Announcer } from '../../core/announcer.service';
import { AiService } from '../../core/ai.service';
import { AiSessionState } from '../../core/ai-session.state';
import { ConfigService } from '../../core/config.service';
import { ConfirmService } from '../../core/confirm.service';
import { errorMsg } from '../../core/format';
import { LocalStore } from '../../data/local-store.service';
import { StorageService } from '../../data/storage.service';
import { SyncService } from '../../data/sync.service';
import { ExportService, isAbortError } from '../../export/export.service';
import { PwaService } from '../../core/pwa.service';
import { clearMapView } from '../../shared/map-center';
import { clearLocalLeftovers, clearSessionLeftovers, originLocalStorage, tabSessionStorage } from './session-leftovers';
import type { ExportResult } from '../../export/export.service';
import { DEFAULT_EXPORT_OPTIONS } from '../../export/export-model';
import type { ExportFormat, ExportOptions } from '../../export/export-model';
import { BACKUP_GAP_KEY, backupGaps } from '../../export/backup-completeness';
import { SETTING_KEYS } from '../../data/records';
import { LANGUAGES, isLang } from '../../i18n/languages';
import { Msg, TranslationService } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';
import type { TKey } from '../../i18n/en';
import { RunResult, runResult } from '../../shared/run-result';

interface FormatChoice {
  readonly id: ExportFormat;
  readonly nameKey: TKey;
  readonly hintKey: TKey;
}

/** The six formats of docs/11 §5.2, in the order the export screen offers them. */
const FORMATS: readonly FormatChoice[] = [
  { id: 'html', nameKey: 'data.formatHtml', hintKey: 'data.formatHtmlHint' },
  { id: 'pdf', nameKey: 'data.formatPdf', hintKey: 'data.formatPdfHint' },
  { id: 'csv', nameKey: 'data.formatCsv', hintKey: 'data.formatCsvHint' },
  { id: 'xlsx', nameKey: 'data.formatXlsx', hintKey: 'data.formatXlsxHint' },
  { id: 'markdown', nameKey: 'data.formatMarkdown', hintKey: 'data.formatMarkdownHint' },
  { id: 'backup', nameKey: 'data.formatBackup', hintKey: 'data.formatBackupHint' },
];

const FORMAT_IDS: ReadonlySet<string> = new Set(FORMATS.map((f) => f.id));

/** The progress bar is redrawn at most this often (it is not a live region; see {@link DataPage.run}). */
const PROGRESS_THROTTLE_MS = 250;
/** Two progress announcements are never closer than this, however fast the quarters go by. */
const ANNOUNCE_GAP_MS = 1500;

/** Formats whose file can carry photos: only these show the Photos choice (Android ExportScreen: usesPhotos). */
const PHOTO_FORMATS: ReadonlySet<ExportFormat> = new Set<ExportFormat>(['html', 'pdf', 'backup']);

/** Material glyphs (Apache-2.0), drawn with currentColor like the navigation icons. */
const ICONS = {
  share:
    'M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z',
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
} as const;

/**
 * "Your data": save a copy in any of the six deterministic formats (the "Save a copy" section, named as on Android),
 * see how durable this browser's copy is, install the app, sync with a configured server, and remove everything from
 * this browser (S4-01, S4-03, S4-05, docs/11 §5.2, §5.10).
 */
@Component({
  selector: 'app-data-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './data-page.html',
  styleUrl: './data-page.css',
})
export class DataPage implements OnInit, OnDestroy {
  private readonly exporter = inject(ExportService);
  private readonly store = inject(LocalStore);
  private readonly announcer = inject(Announcer);
  private readonly confirm = inject(ConfirmService);
  private readonly config = inject(ConfigService);
  private readonly ai = inject(AiService);
  private readonly aiSession = inject(AiSessionState);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly storage = inject(StorageService);
  protected readonly sync = inject(SyncService);
  protected readonly pwa = inject(PwaService);
  protected readonly i18n = inject(TranslationService);

  protected readonly formats = FORMATS;
  protected readonly icons = ICONS;
  protected readonly languages = LANGUAGES;
  protected readonly scopes: readonly { id: 'all' | 'shortlisted'; key: TKey }[] = [
    { id: 'all', key: 'data.scopeAll' },
    { id: 'shortlisted', key: 'data.scopeShortlisted' },
  ];
  protected readonly photoChoices: readonly { id: 'all' | 'shortlisted' | 'none'; key: TKey }[] = [
    { id: 'all', key: 'data.photosAll' },
    { id: 'shortlisted', key: 'data.photosShortlisted' },
    { id: 'none', key: 'data.photosNone' },
  ];

  /**
   * The chosen format. A signal, not a plain field: "Save a backup" from a banner changes it from outside any
   * template event (a query-parameter stream, after a render), and in this zoneless app only a signal makes the
   * radios follow — otherwise the screen said "Web page" while Download built the backup ZIP.
   */
  protected readonly format = signal<ExportFormat>('html');
  protected readonly options = signal<ExportOptions>({ ...DEFAULT_EXPORT_OPTIONS, lang: this.i18n.lang() });

  protected readonly counts = signal<{ houses: number; visits: number; photos: number } | null>(null);
  /** Every house in this browser, whatever the options say: 0 means there is nothing to export at all. */
  protected readonly totalHouses = signal<number | null>(null);
  protected readonly busy = signal(false);
  /** "Preparing photos 34 of 212" while a photo-bearing export runs: a progress bar, redrawn at most every 250ms. */
  protected readonly progress = signal<{ done: number; total: number } | null>(null);
  /**
   * Why the last build or share failed. Keyed on its run ({@link RunResult}): pressing the button again and failing
   * the same way is a new node in the alert and is read again (web UX gate R9).
   */
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly lastResult = signal<ExportResult | null>(null);
  /** The last copy **downloaded** ("Saved …" or "Saved a partial backup …"); a copy only shared or printed was not saved. */
  protected readonly saved = signal<Msg | null>(null);
  protected readonly shareable = signal(false);
  /** Checked once, before anything is built: offer "Share" next to "Download" on phones that can share files. */
  protected readonly canShareFiles = signal(false);
  /** iOS and Android: the PDF path opened the copy in a new tab; say how to turn it into a PDF there. */
  protected readonly pdfInTab = signal(false);
  /** Local changes the server has not received yet (sync card, and the "Remove all data" warning). */
  protected readonly pending = signal(0);
  /** The browser said no to "keep my data" when asked from this page: say so in the card, not only once aloud. */
  protected readonly persistDenied = signal(false);
  /**
   * Set once the saved options have been read, so the counts are never taken with the wrong options. Until then the
   * export card shows a placeholder, not the full form that would flip to an empty state a moment later.
   */
  protected readonly started = signal(false);
  private markStarted: () => void = () => undefined;
  private readonly startedOnce = new Promise<void>((resolve) => {
    this.markStarted = () => resolve();
  });
  private abort: AbortController | null = null;
  private counting = false;
  private recount = false;

  protected readonly isIos = StorageService.isIos();
  protected readonly isAndroid = typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent || '');
  protected readonly isStandalone = StorageService.isStandalone();
  /** Only Firefox asks the user about persistent storage; Chromium and Safari decide by themselves, so asking again does nothing. */
  protected readonly persistPrompts = typeof navigator !== 'undefined' && /Firefox\//.test(navigator.userAgent || '');
  /**
   * Phones print through a new tab, never through a hidden frame. iOS Safari and Home Screen apps print the parent
   * page or nothing from `iframe.contentWindow.print()`, and Chromium on Android has the same long-standing report
   * (issues.chromium.org 41222716: printing an iframe's content prints the page around it). Until a device check
   * shows the frame works on Android Chrome and an installed Android app (docs/06 TC-M), both use the tab.
   */
  protected readonly printInTab = this.isIos || this.isAndroid;
  protected readonly pdfHintKey: TKey = this.isIos ? 'data.pdfIosHint' : 'data.pdfAndroidHint';
  protected readonly skeletonRows: readonly number[] = [1, 2, 3];
  protected readonly focusTarget = viewChild<ElementRef<HTMLButtonElement>>('runBtn');
  private readonly shareBtn = viewChild<ElementRef<HTMLButtonElement>>('shareBtn');
  private readonly cancelBtn = viewChild<ElementRef<HTMLButtonElement>>('cancelBtn');
  private readonly syncBtn = viewChild<ElementRef<HTMLButtonElement>>('syncBtn');
  private readonly stopBtn = viewChild<ElementRef<HTMLButtonElement>>('stopBtn');
  /**
   * Focus is on the download's "Stop" (set on focus, cleared only when focus moves to another element): when the
   * download ends by itself, the button goes away under it and focus would otherwise fall to <body>.
   */
  protected stopFocused = false;
  private readonly installHeading = viewChild<ElementRef<HTMLElement>>('installHeading');

  /**
   * Nothing to export with these options, and why: `none` (no house in this browser: Android's export_empty),
   * `shortlisted` (houses exist, none shortlisted) or `filtered` (the options leave every house out). Null while
   * there is something to export, or before the first count.
   */
  protected readonly emptyReason = computed<'none' | 'shortlisted' | 'filtered' | null>(() => {
    const c = this.counts();
    const total = this.totalHouses();
    if (!c || c.houses > 0 || total === null) return null;
    if (total === 0) return 'none';
    return this.options().scope === 'shortlisted' ? 'shortlisted' : 'filtered';
  });

  /**
   * A "Full backup" whose options leave something out (Android: export_partial_note, §14.1). Every gap is named, in
   * the language's list pattern, before the file is made: this is the one file meant for restoring.
   */
  protected readonly partialNote = computed<string | null>(() => {
    if (this.format() !== 'backup') return null;
    const gaps = backupGaps(this.options());
    if (gaps.length === 0) return null;
    return this.i18n.t('data.partialNote', { gaps: this.i18n.list(gaps.map((g) => this.i18n.t(BACKUP_GAP_KEY[g]))) });
  });

  constructor() {
    // What an export would contain, how much space is used and what is waiting for the server all follow the
    // store, not a single read at start-up: a sync pull, the first-run download or "Remove all data" change them.
    // `settled`, not `revision`: a pull writes one row at a time, and rebuilding the export bundle per row made the
    // first-run download on this page cost the square of the number of rows.
    effect(() => {
      this.store.settled();
      if (!this.started()) return;
      void this.refreshCounts();
      void this.storage.refresh();
      void this.refreshPending();
    });
    // The offline line counts again when the connection comes back (the sync then empties the queue).
    effect(() => {
      this.sync.online();
      this.sync.running();
      if (this.started()) void this.refreshPending();
    });
    // The download's "Stop" goes away when the download ends by itself (done, failed): if it had focus, focus goes
    // on to the card's main button, as after pressing Stop, instead of falling to <body>.
    let stopShown = false;
    effect(() => {
      const shown = this.sync.running() && this.sync.migration() === 'running';
      if (shown === stopShown) return;
      stopShown = shown;
      if (shown) return;
      untracked(() => {
        const stop = this.stopBtn()?.nativeElement ?? null;
        const focusedNow = stop !== null && typeof document !== 'undefined' && document.activeElement === stop;
        const hadFocus = this.stopFocused || focusedNow;
        this.stopFocused = false;
        if (!hadFocus) return;
        afterNextRender(
          () => {
            const active = document.activeElement;
            if (active === null || active === document.body || active === stop) this.syncBtn()?.nativeElement.focus();
          },
          { injector: this.injector },
        );
      });
    });
  }

  /** Focus left "Stop" for another element (not merely because the button was removed). */
  protected onStopBlur(event: FocusEvent): void {
    if (event.relatedTarget) this.stopFocused = false;
  }

  async ngOnInit(): Promise<void> {
    // "Save a backup" from the storage-risk banner arrives as /data?export=backup: choose the restorable ZIP and
    // put focus on its button (once the saved options are read), so the next obvious tap makes a backup and not
    // the default web page copy. Read as a stream, because the banner's link can also be followed while this page
    // is already open. Subscribed before the first `await`, so the teardown is registered while the view is alive.
    const sub = this.route.queryParamMap.subscribe((params) => {
      if (params.get('export') !== 'backup') return;
      void this.startedOnce.then(() => afterNextRender(() => this.backupFirst(), { injector: this.injector }));
      void this.router.navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true });
    });
    this.destroyRef.onDestroy(() => sub.unsubscribe());

    this.canShareFiles.set(this.exporter.canShareFiles());
    await this.store.ready();
    await this.storage.refresh();
    const saved = await this.store.setting(SETTING_KEYS.exportOptions);
    if (saved) {
      this.options.update((o) => ({ ...o, ...safeOptions(saved) }));
      // The last format chosen here. A banner's "Save a backup" waits for this (startedOnce) and then chooses the
      // backup over it.
      const format = safeFormat(saved);
      if (format) this.format.set(format);
    }
    await this.refreshCounts();
    await this.refreshPending();
    this.started.set(true);
    this.markStarted();
  }

  /** The Photos choice only matters for a file that can carry photos (Android parity). */
  protected usesPhotos(): boolean {
    return PHOTO_FORMATS.has(this.format());
  }

  ngOnDestroy(): void {
    this.abort?.abort();
  }

  /** Recounts what the current options would put in the file, so the user sees it before pressing anything. */
  protected async refreshCounts(): Promise<void> {
    // Coalesced: a count asked for while one is running makes that one go round once more, never a second build
    // of the bundle next to it.
    if (this.counting) {
      this.recount = true;
      return;
    }
    this.counting = true;
    try {
      do {
        this.recount = false;
        const bundle = await this.exporter.bundle(this.options());
        this.totalHouses.set((await this.store.liveHouses()).length);
        this.counts.set(bundle.counts);
      } while (this.recount);
    } catch {
      this.counts.set(null);
      this.totalHouses.set(null);
    } finally {
      this.counting = false;
    }
  }

  private async refreshPending(): Promise<void> {
    try {
      this.pending.set(this.sync.enabled() ? await this.sync.pendingCount() : 0);
    } catch {
      this.pending.set(0);
    }
  }

  /** The options and the format, remembered together for the next visit. */
  private async persistChoices(): Promise<void> {
    await this.store.setSetting(SETTING_KEYS.exportOptions, JSON.stringify({ ...this.options(), format: this.format() }));
  }

  protected async onOptionChange(): Promise<void> {
    // The options are disabled while a file is built; this is the guard behind that, so a file built with the old
    // options can never be shared as if it matched what the screen now shows.
    if (this.busy()) return;
    this.lastResult.set(null);
    this.saved.set(null);
    await this.persistChoices();
    await this.refreshCounts();
  }

  protected setFormat(format: ExportFormat): void {
    if (this.busy()) return;
    this.format.set(format);
    this.lastResult.set(null);
    this.saved.set(null);
    void this.persistChoices();
  }

  protected setScope(scope: 'all' | 'shortlisted'): void {
    this.options.update((o) => ({ ...o, scope }));
    void this.onOptionChange();
  }

  protected setPhotos(photos: 'all' | 'shortlisted' | 'none'): void {
    this.options.update((o) => ({ ...o, photos }));
    void this.onOptionChange();
  }

  protected setIncludeRejected(includeRejected: boolean): void {
    this.options.update((o) => ({ ...o, includeRejected }));
    void this.onOptionChange();
  }

  protected setIncludeContacts(includeContacts: boolean): void {
    this.options.update((o) => ({ ...o, includeContacts }));
    void this.onOptionChange();
  }

  /** "Include rejected houses" from the "no house matches" line: the one option that left them all out. */
  protected includeRejected(): void {
    this.setIncludeRejected(true);
    afterNextRender(() => this.focusTarget()?.nativeElement.focus(), { injector: this.injector });
  }

  /** "Use everything" under the partial-backup note: every narrowing option back to "all", as on Android. */
  protected useEverything(): void {
    // Like every other option, not while a file is built: the screen would say "everything" about a partial file.
    if (this.busy()) return;
    this.options.update((o) => ({ ...o, scope: 'all', includeRejected: true, photos: 'all', includeContacts: true }));
    void this.onOptionChange();
    this.announcer.announce({ key: 'data.everythingIncluded' });
    // The note and its button are gone: focus goes on to the button that makes the backup.
    afterNextRender(() => this.focusTarget()?.nativeElement.focus(), { injector: this.injector });
  }

  protected onLangChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (isLang(value)) this.options.update((o) => ({ ...o, lang: value }));
    void this.onOptionChange();
  }

  /**
   * Builds the file and hands it over: saved with a download, opened for printing (PDF), or — `share` — sent
   * straight to the share sheet without saving a copy first.
   *
   * The PDF path on phones ({@link printInTab}) opens the copy in a new tab instead of printing a hidden frame. The
   * tab has to be opened **synchronously inside the click**, before the first `await`, or the pop-up blocker stops
   * it; it says "Preparing…" in the app language until the copy replaces it.
   *
   * Progress: the bar is redrawn as photos are prepared, but it is not a live region. A screen reader hears the
   * start, 25, 50 and 75 percent (never two within {@link ANNOUNCE_GAP_MS}), and then the result, instead of a
   * queue of 120 "Preparing photos …" lines still being read after the file is saved.
   *
   * Focus: the buttons stay in place (`aria-disabled`, not `disabled`, which would drop focus to <body>); focus moves
   * to Cancel when the build starts, and back to the main button when it ends or is cancelled.
   */
  protected async run(mode: 'save' | 'share' = 'save'): Promise<void> {
    if (this.busy() || this.emptyReason() !== null) return;
    const format = this.format();
    // A file already built with these exact options (any change clears it) is shared as it is. That is also the
    // way out when the first share was refused because the long build used up the click's user activation
    // (NotAllowedError): this click is fresh, and nothing is built twice.
    const built = this.lastResult();
    if (mode === 'share' && built && built.format === format) {
      this.error.set(null);
      await this.shareResult(built);
      return;
    }
    const printTab = mode === 'save' && format === 'pdf' && this.printInTab ? openBlankTab() : null;
    if (printTab) this.writePlaceholder(printTab);
    const active = typeof document === 'undefined' ? null : document.activeElement;
    const fromButtons =
      active === this.focusTarget()?.nativeElement || active === this.shareBtn()?.nativeElement;
    const partial = format === 'backup' && this.partialNote() !== null;
    this.busy.set(true);
    this.error.set(null);
    this.lastResult.set(null);
    this.saved.set(null);
    this.pdfInTab.set(false);
    this.progress.set(null);
    if (fromButtons) afterNextRender(() => this.cancelBtn()?.nativeElement.focus(), { injector: this.injector });
    const abort = new AbortController();
    this.abort = abort;
    let lastDrawn = 0;
    let lastQuarter = -1;
    let lastAnnounced = 0;
    try {
      const result = await this.exporter.build(format, this.options(), new Date(), {
        signal: abort.signal,
        onProgress: (done, total) => {
          if (total <= 0) return;
          const now = Date.now();
          if (done === total || done === 0 || now - lastDrawn >= PROGRESS_THROTTLE_MS) {
            lastDrawn = now;
            this.progress.set({ done, total });
          }
          // 0 = started, 1..3 = a quarter reached; 100% is said by the result itself ("Saved …").
          const quarter = Math.min(3, Math.floor((done * 4) / total));
          if (done < total && quarter > lastQuarter && (lastQuarter < 0 || now - lastAnnounced >= ANNOUNCE_GAP_MS)) {
            lastQuarter = quarter;
            lastAnnounced = now;
            this.announcer.announce({ key: 'data.progressExport', params: { done, total } });
          }
        },
      });
      this.lastResult.set(result);
      this.shareable.set(this.exporter.canShare(result));
      if (mode === 'share') {
        await this.shareResult(result);
      } else if (format === 'pdf' && printTab && result.blob) {
        const url = URL.createObjectURL(result.blob);
        printTab.location.href = url;
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
        this.pdfInTab.set(true);
        this.announcer.announce({ key: 'data.pdfIosOpened' });
      } else if (format === 'pdf') {
        await this.exporter.printPdf(result);
        this.announcer.announce({ key: 'data.printOpened' });
      } else {
        this.exporter.download(result);
        // A backup that leaves something out is never called simply "Saved": the result says "partial backup".
        const message: Msg = { key: partial ? 'data.donePartial' : 'data.done', params: { file: result.fileName } };
        this.saved.set(message);
        this.announcer.announce(message);
      }
    } catch (err: unknown) {
      printTab?.close();
      if (isAbortError(err)) {
        this.announcer.announce({ key: 'data.cancelled' });
      } else {
        this.error.set(runResult({ key: 'data.failed', params: { reason: errorMsg(err) } }));
      }
    } finally {
      if (this.abort === abort) this.abort = null;
      // "Preparing photos 3 of 10…" says the build is still running. Every end path replaces it (Saved, Cancelled,
      // Shared) or, when it ends without a message of its own (a failure, whose alert must be the last thing heard; a
      // share sheet closed), withdraws it here. Only this message: another feature's announcement is left alone.
      this.announcer.cancel({ key: 'data.progressExport' });
      const focusOnCancel =
        typeof document !== 'undefined' &&
        (document.activeElement === this.cancelBtn()?.nativeElement || document.activeElement === document.body);
      this.busy.set(false);
      this.progress.set(null);
      // Cancel is going away with the build: put focus back on the button that started it.
      if (focusOnCancel) afterNextRender(() => this.focusTarget()?.nativeElement.focus(), { injector: this.injector });
    }
  }

  /**
   * The phone PDF tab opens at once, before the file exists: without this it is a blank white page the user is
   * switched to while the copy is built in the tab they left. A title and "Preparing…" in the file's language.
   */
  private writePlaceholder(tab: Window): void {
    try {
      const doc = tab.document;
      doc.documentElement.lang = this.options().lang;
      doc.title = this.i18n.t('exp.title');
      const p = doc.createElement('p');
      p.style.font = '1.125rem/1.5 system-ui, sans-serif';
      p.style.margin = '2rem';
      p.textContent = this.i18n.t('data.building');
      doc.body?.appendChild(p);
    } catch {
      // A browser that does not let the opener write into its blank tab: it stays blank until the copy arrives.
    }
  }

  /** "Cancel" next to the main button while an export is being prepared. */
  protected cancelExport(): void {
    this.abort?.abort();
  }

  protected async shareFile(): Promise<void> {
    const result = this.lastResult();
    if (result) await this.shareResult(result);
  }

  private async shareResult(result: ExportResult): Promise<void> {
    const outcome = await this.exporter.share(result, this.i18n.t('exp.title'));
    if (outcome === 'shared') {
      this.announcer.announce({ key: 'data.shared' });
    } else if (outcome === 'failed' || outcome === 'unavailable') {
      // The file exists; saving it is the way out, so say that rather than only "it did not work".
      this.error.set(runResult({ key: 'data.shareFailed' }));
    }
  }

  /**
   * "Ask the browser to keep my data". A refusal stays written in the card. Only Firefox asks the user; Chromium and
   * Safari decide by themselves (engagement, installation), so after a refusal there the button is not offered
   * again — it would repeat a request whose answer does not change.
   */
  protected async askForPersistence(): Promise<void> {
    const granted = await this.storage.requestPersistence(true);
    this.persistDenied.set(!granted);
    this.announcer.announce({ key: granted ? 'storage.granted' : 'storage.denied' });
    // The button goes away (granted, or refused where asking again changes nothing): focus moves to what says so.
    if (granted || !this.persistPrompts) {
      afterNextRender(() => document.getElementById(granted ? 'storage-heading' : 'persist-denied')?.focus(), {
        injector: this.injector,
      });
    }
  }

  /** The button is gone afterwards (the browser offers one prompt): focus goes to the section heading. */
  protected async install(): Promise<void> {
    const accepted = await this.pwa.install();
    if (accepted) this.announcer.announce({ key: 'data.installDone' });
    afterNextRender(() => this.installHeading()?.nativeElement.focus(), { injector: this.injector });
  }

  /**
   * The sync card's one button: "Download now" while sync is paused, "Sync now" otherwise. It is one element whose
   * label changes and which is `aria-disabled` while a run is going, so the focus the user put on it stays there
   * (a removed or `disabled` button drops focus to <body>).
   */
  protected syncAction(): void {
    if (this.sync.running()) return;
    if (this.sync.paused()) {
      this.download();
    } else {
      this.syncNow();
    }
  }

  protected syncNow(): void {
    // While the first-run question is open, "Sync now" is the download it asks about: run it as that, so the
    // banner's state moves on with it instead of a forced pull leaving the question on screen.
    if (this.sync.migration() === 'offered') {
      void this.sync.downloadToThisBrowser();
      return;
    }
    void this.sync.syncNow(true);
  }

  /** After "Not now" or "Stop": the full download the first-run banner offered, which also ends the pause. */
  protected download(): void {
    void this.sync.downloadToThisBrowser();
  }

  /** "Stop" on the download: the button goes away with the run, so focus moves to the card's main button. */
  protected stopDownload(): void {
    this.sync.stopDownload();
    afterNextRender(() => this.syncBtn()?.nativeElement.focus(), { injector: this.injector });
  }

  /** "Save a backup first", from the danger card or a banner: choose the backup format and put focus on its button. */
  protected backupFirst(): void {
    if (this.busy() || this.emptyReason() === 'none') return;
    this.setFormat('backup');
    const button = this.focusTarget()?.nativeElement;
    button?.scrollIntoView({ block: 'center' });
    button?.focus();
  }

  /**
   * "Remove all data" (docs/11 §5.10, shared computers).
   *
   * With a server connected, local changes the server has not received yet are **not** safe on the server — they
   * exist only here — so the confirmation counts them and offers "Sync first", which sends them and asks again.
   *
   * Then, in this order: stop any sync (a download in flight would otherwise keep writing houses and photo blobs
   * into the store it is emptying, after "removed" was announced — SyncService.cancel stops every write), forget
   * the server address and key (so nothing restarts it, and Ask and Plan leave the navigation), and only then clear
   * the store and the offline copy. This tab's sessionStorage leftovers go too: an unsaved house draft (which can
   * hold a contact's name and number) and shared listing text (see session-leftovers.ts); so do this app's
   * `doorprints.*` localStorage keys, and the app's service worker is unregistered (docs/07 Appendix A.1, S10). Of the
   * keys, only the chosen language stays. web/README.md, "Storage audit on the live site", checks it all by hand.
   */
  protected async clearBrowser(): Promise<void> {
    if (this.busy()) return;
    for (;;) {
      const unsynced = this.sync.enabled() ? await this.sync.pendingCount() : 0;
      const answer =
        unsynced > 0
          ? await this.confirm.choose(
              { key: 'confirm.clearUnsynced', params: { n: unsynced } },
              { confirmKey: 'data.clear', altKey: this.sync.paused() ? null : 'data.syncFirst', danger: true },
            )
          : (await this.confirm.ask({ key: 'confirm.clearBrowser' }, { confirmKey: 'data.clear', danger: true }))
            ? 'confirm'
            : 'cancel';
      if (answer === 'cancel') return;
      if (answer === 'confirm') break;
      // "Sync first": send what is waiting, then ask again with the new count (0 when it all went through).
      await this.sync.syncNow(true);
      if (this.sync.lastError()) return; // the sync card shows why; nothing was removed
    }
    this.sync.cancel();
    this.config.clear();
    this.ai.refresh();
    this.aiSession.clear();
    this.abort?.abort();
    clearMapView();
    await this.store.clearEverything();
    try {
      clearSessionLeftovers(tabSessionStorage());
    } catch {
      // Storage blocked: nothing was kept there either.
    }
    try {
      clearLocalLeftovers(originLocalStorage());
    } catch {
      // Storage blocked: nothing was kept there either.
    }
    await this.pwa.unregister();
    this.lastResult.set(null);
    this.saved.set(null);
    await this.storage.refresh();
    this.announcer.announce({ key: 'data.cleared' });
  }

  /** "12.3 MB of about 2.1 GB", in the app language's own number and unit formatting (this is a screen, not a file). */
  protected readonly usageText = computed(() => {
    const estimate = this.storage.estimate();
    if (!estimate || estimate.quotaBytes <= 0) return null;
    return this.i18n.t('storage.used', {
      used: this.i18n.size(estimate.usageBytes),
      quota: this.i18n.size(estimate.quotaBytes),
    });
  });
}

/**
 * A blank tab opened synchronously inside the click, for the phone PDF path; null when the browser refused.
 * `noopener` would make `window.open` return null, so the handle is kept; the tab is our own blank page.
 */
function openBlankTab(): Window | null {
  try {
    return typeof window === 'undefined' ? null : window.open('', '_blank');
  } catch {
    return null;
  }
}

/** Reads back saved options defensively: anything unexpected falls back to the default. */
function safeOptions(raw: string): Partial<ExportOptions> {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return {};
    const value = parsed as Partial<ExportOptions>;
    const out: Partial<ExportOptions> = {};
    // 'selected' is not offered on this screen (it needs a picker), so it is never restored from storage either.
    if (value.scope === 'all' || value.scope === 'shortlisted') out.scope = value.scope;
    if (value.photos === 'all' || value.photos === 'shortlisted' || value.photos === 'none') out.photos = value.photos;
    if (typeof value.includeRejected === 'boolean') out.includeRejected = value.includeRejected;
    if (typeof value.includeContacts === 'boolean') out.includeContacts = value.includeContacts;
    if (isLang(value.lang)) out.lang = value.lang;
    return out;
  } catch {
    return {};
  }
}

/** The format saved with the options, or null (older settings have none). */
function safeFormat(raw: string): ExportFormat | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object') return null;
    const format = (parsed as { format?: unknown }).format;
    return typeof format === 'string' && FORMAT_IDS.has(format) ? (format as ExportFormat) : null;
  } catch {
    return null;
  }
}
