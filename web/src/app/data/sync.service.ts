import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { Observable } from 'rxjs';
import { ConfigService, normalizeBaseUrl } from '../core/config.service';
import { HouseApiService } from '../core/house-api.service';
import { errorMsg, isQuotaError, retryAfterSeconds } from '../core/format';
import type { Msg } from '../i18n/translation.service';
import type { RunResult } from '../shared/run-result';
import { LocalDataError } from '../core/local-error';
import { LocalStore } from './local-store.service';
import {
  SETTING_KEYS,
  houseToDto,
  isRecordId,
  isoNow,
  tryHouseFromDto,
  tryVisitFromDto,
  visitToDto,
} from './records';
import { keepLocalRecord } from './sync-rules';
import type { HouseRecord, PhotoRecord, VisitRecord } from './records';

/** Debounce for `syncSoon()`; matches the Android app's "sync soon after a change" (docs/11 §5.10). */
const DEBOUNCE_MS = 3000;
/** Background sync while the app is open. */
const PERIOD_MS = 30 * 60 * 1000;
/** Attempts per request when the server says 429 (Android `RetryPolicy.maxAttempts` is 3; one more for a long pull). */
export const RATE_LIMIT_ATTEMPTS = 4;
/** Longest `Retry-After` slept through (Android `RetryPolicy.maxDelayMs`); a longer one ends the run. */
export const MAX_RATE_LIMIT_WAIT_MS = 15_000;

export interface SyncOutcome {
  pushed: number;
  pulled: number;
  /** Rows the server sent that this app refused to store (no usable id, no usable sync version). */
  skipped: number;
  at: string;
}

/** Where a running sync is, for the first-run banner ("Downloading photos 34 of 212"). */
export interface SyncProgress {
  phase: 'sending' | 'houses' | 'visits' | 'photos';
  done: number;
  total: number;
}

/**
 * What the first-run migration ("download my houses to this browser") is doing.
 *
 *  * `unknown` — not checked yet (no server, or the check has not finished).
 *  * `offered` — the browser is empty and the user has not answered; nothing is sent or fetched.
 *  * `running` — the user said "Download now"; the full pull is in progress.
 *  * `done` — downloaded, or the browser already held data: sync runs normally.
 *  * `skipped` — the user said "Not now", or pressed "Stop" during the download: **sync is paused in this
 *    browser**, in both directions, until they choose "Download now" on the Your data page, which carries on from
 *    wherever a stopped download got to. Remembered across reloads.
 */
export type MigrationState = 'unknown' | 'offered' | 'running' | 'done' | 'skipped';

/** Thrown inside a run that {@link SyncService.cancel} has overtaken: it must not write anything more. */
class SyncCancelled extends Error {}
/** Thrown inside a pull the user stopped: the cursors reached so far are already stored. */
class SyncStopped extends Error {}

/**
 * Two-way sync between this browser's IndexedDB and the optional API-key server (S4-01).
 *
 * Same algorithm and the same conflict rule as the Android app (docs/03 §10, Repository.sync): push dirty rows,
 * pull with `since` cursors, last-write-wins via {@link keepLocalRecord}, tombstones for deletes, photo metadata
 * with the bytes fetched separately. It runs only when the user has configured a server; with no server the app is
 * complete on its own and this service stays quiet.
 *
 * Three things can end a run early, and each is handled differently:
 *  * **cancel** ({@link cancel}) — "Remove all data", or a different server: every `await` in push and pull is
 *    followed by a generation check, so a run that was in flight writes **nothing** after the call, not a house,
 *    not a photo blob, not a cursor. It says nothing either: the caller is already reporting what happened.
 *  * **stop** ({@link stopDownload}) — "Stop" on the first-run download: checked between rows, the cursors reached
 *    so far are stored, and the migration goes to `skipped`, so "Download now" on Your data resumes it.
 *  * **offline** — normal for a local-first app. Unforced runs (the debounce, the 30-minute timer) are skipped
 *    while `navigator.onLine` is false and happen on the `online` event instead; only a run the user asked for
 *    reports a network error.
 */
@Injectable({ providedIn: 'root' })
export class SyncService {
  private readonly api = inject(HouseApiService);
  private readonly config = inject(ConfigService);
  private readonly store = inject(LocalStore);

  /** True when a server is configured; until Sprint 5 that is the API-key server. */
  readonly enabled = computed(() => this.config.configured());
  readonly running = signal(false);
  readonly lastOutcome = signal<SyncOutcome | null>(null);
  readonly lastError = signal<Msg | null>(null);
  /**
   * True when {@link lastError} came from something the user asked for ("Sync now", "Download now"). A failed
   * background run is reported quietly; only a failure the user is waiting for is an alert.
   */
  readonly lastErrorForced = signal(false);
  /** Bumped by every failed run, so the same failure twice in a row renders as a new node (see {@link lastFailure}). */
  private readonly failureRun = signal(0);
  /**
   * {@link lastError} keyed on the run that produced it, for the screens that show it in a live region: "Sync now"
   * or "Try again" failing again with the same words is a new node and is read again (web UX gate R9). Rendered as
   * `@for (c of [failure]; track c.run)`, like every other run result (shared/run-result.ts).
   */
  readonly lastFailure = computed<RunResult<Msg> | null>(() => {
    const error = this.lastError();
    return error === null ? null : { value: error, run: this.failureRun() };
  });
  readonly migration = signal<MigrationState>('unknown');
  /** Rows dropped by the last pull, so the screen can say so instead of quietly losing them. */
  readonly lastSkipped = signal(0);
  /** "Not now" was chosen: sync is paused in this browser until the user asks for the download. */
  readonly paused = computed(() => this.enabled() && this.migration() === 'skipped');
  /** Progress of the running pass, or null when nothing is running. */
  readonly progress = signal<SyncProgress | null>(null);
  /** Mirrors `navigator.onLine`, so the Your data page can say "offline, N changes waiting" instead of an error. */
  readonly online = signal(typeof navigator === 'undefined' || navigator.onLine !== false);

  private timer: ReturnType<typeof setTimeout> | undefined;
  private interval: ReturnType<typeof setInterval> | undefined;
  private queued: Promise<void> = Promise.resolve();
  /** Bumped by {@link cancel}; a run whose generation is no longer current stops at its next check. */
  private generation = 0;
  private stopRequested = false;

  constructor() {
    const destroyRef = inject(DestroyRef);
    // Starts, and restarts, whenever the user connects, disconnects or **changes** a server. Reading the address
    // here is what makes "connect a different server" restart sync: `enabled()` alone stays true across it.
    //
    // Only `enabled()` and the address are dependencies; the rest runs `untracked`, because `cancel()` reads
    // `migration()` and `start()` writes it, and tracking either would re-run this effect on every state change.
    effect(() => {
      const server = this.enabled() ? this.serverUrl() : null;
      untracked(() => {
        this.cancel();
        if (server !== null) {
          void this.start();
        } else {
          this.migration.set('unknown');
          this.lastError.set(null);
          this.lastErrorForced.set(false);
        }
      });
    });
    if (typeof window !== 'undefined') {
      const onOnline = () => {
        this.online.set(true);
        if (this.enabled()) this.syncSoon(0);
      };
      const onOffline = () => this.online.set(false);
      window.addEventListener('online', onOnline);
      window.addEventListener('offline', onOffline);
      this.interval = setInterval(() => {
        if (this.enabled()) this.syncSoon(0);
      }, PERIOD_MS);
      destroyRef.onDestroy(() => {
        window.removeEventListener('online', onOnline);
        window.removeEventListener('offline', onOffline);
        clearInterval(this.interval);
        clearTimeout(this.timer);
      });
    }
  }

  /** The configured server's address, normalized; '' when a key is configured without a readable address. */
  private serverUrl(): string {
    const base = this.config.config()?.baseUrl ?? '';
    return base ? normalizeBaseUrl(base) : '';
  }

  /**
   * After a server is configured: ask first when this browser is still empty, otherwise start syncing.
   *
   * Public so `sync.service.spec.ts` can drive the first-run state machine without a change-detection tick; the
   * app only ever calls it from the effect above.
   */
  async start(): Promise<void> {
    await this.adoptServer();
    await this.checkMigration();
    // 'offered' means the user has not yet answered "download my houses to this browser", and 'skipped' that they
    // said "not now": either way, do not pull behind their back on what may be a shared computer or a metered
    // connection. `runOnce` refuses both as well; this just avoids scheduling a run that would do nothing.
    if (this.ready) this.syncSoon(0);
  }

  /**
   * Makes the stored cursors belong to the configured server (Android parity: Settings.saveServer, "A different
   * server means a fresh full download").
   *
   * The cursors are positions in **one** server's change log. Kept across a switch to another server (or a
   * disconnect and a connect elsewhere), they would pull `since` numbers that mean nothing there: every older row
   * would silently never arrive while "0 received" said all was well. So when the address differs from the one the
   * cursors were recorded against, all three go back to 0 and the migration answer is forgotten, so the first-run
   * question is asked afresh for the new server (or, with data here already, a full two-way sync simply runs).
   *
   * With no address recorded yet — every browser that synced before this was added — the current server is
   * adopted as it is: those cursors were made against it, and resetting them would re-download every account.
   */
  private async adoptServer(): Promise<void> {
    const current = this.serverUrl();
    if (!current) return;
    const recorded = await this.store.setting(SETTING_KEYS.syncServer);
    if (recorded === current) return;
    if (recorded !== null) {
      await this.store.setSetting(SETTING_KEYS.houseCursor, '0');
      await this.store.setSetting(SETTING_KEYS.visitCursor, '0');
      await this.store.setSetting(SETTING_KEYS.photoCursor, '0');
      await this.store.removeSetting(SETTING_KEYS.migration);
      this.lastOutcome.set(null);
      this.lastSkipped.set(0);
      this.lastError.set(null);
    }
    await this.store.setSetting(SETTING_KEYS.syncServer, current);
  }

  /** Schedules a sync a few seconds from now, collapsing a burst of edits into one run. */
  syncSoon(delayMs: number = DEBOUNCE_MS): void {
    if (!this.enabled()) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.syncNow(), delayMs);
  }

  /**
   * True when an ordinary (unforced) sync would run: a server is configured and the user has either had their
   * houses downloaded or already had data here. False while the question is open and after "Not now".
   */
  get ready(): boolean {
    const state = this.migration();
    return this.enabled() && (state === 'done' || state === 'running');
  }

  /** Runs a full sync. Never throws: the reason is put in {@link lastError} as a translated message. */
  syncNow(force = false): Promise<void> {
    // Chain, so two overlapping calls never push the same row twice.
    this.queued = this.queued.then(() => this.runOnce(force));
    return this.queued;
  }

  /**
   * Stops whatever sync is running or scheduled, **before** the caller changes what it would write into: "Remove
   * all data" calls this right before clearing the store, and the enabled effect calls it when the server changes.
   *
   * The run in flight is not waited for — its network request may take a long time — but from this call on it
   * cannot write: it fails its next generation check, which follows every `await`. The queue is restarted, so the
   * next sync does not wait behind a request nobody wants any more.
   */
  cancel(): void {
    this.generation++;
    clearTimeout(this.timer);
    this.timer = undefined;
    this.stopRequested = false;
    this.queued = Promise.resolve();
    this.running.set(false);
    this.progress.set(null);
    if (this.migration() === 'running') this.migration.set('unknown');
  }

  /**
   * "Stop" on the first-run download: the pull ends between two rows, keeps what it has, stores the cursors it
   * reached and pauses sync (`skipped`). "Download now" on Your data carries on from there.
   */
  stopDownload(): void {
    if (this.running()) this.stopRequested = true;
  }

  /**
   * Local changes a server has not received yet: dirty houses and visits, photos never uploaded, and photo
   * deletes not yet sent. What "Remove all data" would destroy, and what the offline line says is waiting.
   */
  async pendingCount(): Promise<number> {
    const houses = (await this.store.dirtyHouses()).length;
    const visits = (await this.store.dirtyVisits()).length;
    const photos = (await this.store.allPhotos()).filter(
      (p) => (p.deleted && p.uploaded) || (!p.deleted && !p.uploaded && !!p.blob),
    ).length;
    return houses + visits + photos;
  }

  /**
   * One push-then-pull pass. Unforced runs — the debounce after an edit, the `online` event, the 30-minute timer —
   * only happen when {@link ready}: never while the migration question is open ('offered'), never before it has
   * been checked ('unknown'), and never after "Not now" ('skipped'), which would otherwise pull the whole account
   * (every cursor is still 0) and every photo blob, the very download the user just declined. `force` is for an
   * explicit user action and for the migration itself.
   *
   * An unforced run is also skipped while the browser says it is offline: that is a normal state for this app, not
   * a failure, and the `online` event runs the sync as soon as the connection is back.
   */
  private async runOnce(force: boolean): Promise<void> {
    if (!this.enabled()) return;
    if (!force && !this.ready) return;
    if (!force && isOffline()) return;
    const gen = this.generation;
    this.stopRequested = false;
    this.running.set(true);
    this.lastError.set(null);
    this.lastErrorForced.set(false);
    try {
      const pushed = await this.push(gen);
      const { pulled, skipped } = await this.pull(gen);
      this.live(gen);
      this.lastSkipped.set(skipped);
      this.lastOutcome.set({ pushed, pulled, skipped, at: isoNow() });
      if (this.migration() === 'running') {
        this.migration.set('done');
        await this.store.setSetting(SETTING_KEYS.migration, 'done');
      }
    } catch (err: unknown) {
      if (err instanceof SyncCancelled) return;
      if (err instanceof SyncStopped) {
        if (gen === this.generation && this.migration() === 'running') {
          this.migration.set('skipped');
          await this.store.setSetting(SETTING_KEYS.migration, 'skipped');
        }
        return;
      }
      if (gen !== this.generation) return;
      this.failureRun.update((n) => n + 1);
      this.lastError.set(errorMsg(err));
      this.lastErrorForced.set(force);
    } finally {
      // A cancelled run must not switch off the indicator of the run that replaced it.
      if (gen === this.generation) {
        this.running.set(false);
        this.progress.set(null);
        this.stopRequested = false;
      }
    }
  }

  /**
   * One request of a sync run, with the server's rate limit honoured (Android parity: `:shared` ApiClient and
   * RetryPolicy). The backend answers `429` with `Retry-After` (seconds) once a client address has used its
   * burst (application.yml `app.rate-limit`: 600 a minute, burst 300), which a first-run download of a few
   * hundred photos reaches on a fast link. A short wait (up to {@link MAX_RATE_LIMIT_WAIT_MS}) is slept through
   * and the request repeated, up to {@link RATE_LIMIT_ATTEMPTS} times in all; a longer one, or one more refusal,
   * is thrown and reported as `error.rateLimited`.
   *
   * Every call a sync makes is idempotent (GET, PUT, DELETE, and the photo upload with its client-chosen id), so
   * repeating one is safe. Only 429 is retried here; other failures end the run as before. The generation is
   * checked after the wait, so a run cancelled meanwhile does not send the request again.
   */
  private async call<T>(gen: number, request: () => Observable<T>): Promise<T> {
    for (let attempt = 1; ; attempt++) {
      try {
        return await firstValueFrom(request());
      } catch (err: unknown) {
        this.live(gen);
        const wait = rateLimitWaitMs(err, attempt);
        if (wait === null || attempt >= RATE_LIMIT_ATTEMPTS || wait > MAX_RATE_LIMIT_WAIT_MS) throw err;
        await this.sleep(wait);
        this.live(gen);
      }
    }
  }

  /** Waits between rate-limited attempts. A field so tests can record the waits instead of sleeping through them. */
  sleep: (ms: number) => Promise<void> = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  /** Throws when {@link cancel} was called since the run started. Called after every `await` of a run. */
  private live(gen: number): void {
    if (gen !== this.generation) throw new SyncCancelled();
  }

  private async push(gen: number): Promise<number> {
    const houses = await this.store.dirtyHouses();
    this.live(gen);
    const visits = await this.store.dirtyVisits();
    this.live(gen);
    const photos = await this.store.allPhotos();
    this.live(gen);
    // Deletes first, so a house that lost a photo does not re-upload it.
    const photoDeletes = photos.filter((p) => p.deleted && p.uploaded);
    const photoUploads = photos.filter((p) => !p.deleted && !p.uploaded && p.blob);
    const total = houses.length + visits.length + photoDeletes.length + photoUploads.length;
    let pushed = 0;
    const step = () => {
      pushed++;
      this.progress.set({ phase: 'sending', done: pushed, total });
    };
    if (total > 0) this.progress.set({ phase: 'sending', done: 0, total });

    for (const house of houses) {
      await this.call(gen, () => this.api.pushHouse(houseToDto(house)));
      this.live(gen);
      await this.store.markHouseClean(house.id, house.updatedAt);
      this.live(gen);
      step();
    }
    for (const visit of visits) {
      await this.call(gen, () => this.api.pushVisit(visitToDto(visit)));
      this.live(gen);
      await this.store.markVisitClean(visit.id, visit.updatedAt);
      this.live(gen);
      step();
    }
    for (const photo of photoDeletes) {
      await this.call(gen, () => this.api.deletePhoto(photo.id));
      this.live(gen);
      await this.store.forgetPhoto(photo.id);
      this.live(gen);
      step();
    }
    for (const photo of photoUploads) {
      const bytes = photo.blob;
      if (!bytes) continue;
      await this.call(gen, () => this.api.uploadPhoto(photo.houseId, bytes, photo.id));
      this.live(gen);
      await this.store.putPhotoRecord({ ...photo, uploaded: true });
      this.live(gen);
      step();
    }
    return pushed;
  }

  /**
   * Between two rows of a pull: when the user pressed "Stop", store the cursor reached so far and end the run.
   * See {@link resumeCursor} for why the stored value is not simply the highest version seen.
   */
  private async stopPoint(gen: number, key: string, cursor: number, rows: readonly unknown[], next: number): Promise<void> {
    if (!this.stopRequested) return;
    this.live(gen);
    await this.store.setSetting(key, String(resumeCursor(cursor, rows.slice(next))));
    throw new SyncStopped();
  }

  /**
   * Applies everything the server has changed since the stored cursors.
   *
   * Every row gets **two** independent decisions, and conflating them is a bug each way round:
   *
   *  1. *Can this row move the cursor?* Only if `wireVersion` gave a finite number. `Math.max(n, undefined)` is
   *     `NaN`, which is stored as the string `"NaN"`, read back by `numberSetting` as `0`, and then re-pulls the
   *     whole account on every sync for ever. So a row with no usable `syncVersion` holds the cursor back.
   *  2. *Can this row be stored?* Only if it has a usable id (and, for a photo, a usable `houseId`) — see
   *     `tryHouseFromDto`. Such a row is counted in `skipped` and reported, not written and not silently dropped.
   *
   * A row can fail (2) and still pass (1), and then the cursor **does** advance: the row has been considered and
   * cannot become usable, so holding the cursor back would re-download the same page on every sync for ever,
   * leave "N rows skipped" on screen permanently, and give the user nothing they can act on. Only an unusable
   * *version* is a reason to stay put, because there the cursor arithmetic itself is what would break.
   *
   * It also reads each store **once**, not once per incoming row: the first-run migration is exactly the large-N
   * case this feature exists for, and `allHouses()` is a full `getAll` plus a sort.
   */
  private async pull(gen: number): Promise<{ pulled: number; skipped: number }> {
    const cursors = await this.store.cursors();
    this.live(gen);
    let pulled = 0;
    let skipped = 0;

    const houses = new Map<string, HouseRecord>(
      (await this.store.allHouses()).map((h): [string, HouseRecord] => [h.id, h]),
    );
    this.live(gen);
    let houseCursor = cursors.house;
    const houseRows = wireRows(await this.call(gen, () => this.api.housesSince(houseCursor)));
    this.live(gen);
    for (let i = 0; i < houseRows.length; i++) {
      await this.stopPoint(gen, SETTING_KEYS.houseCursor, houseCursor, houseRows, i);
      this.progress.set({ phase: 'houses', done: i + 1, total: houseRows.length });
      const dto = houseRows[i];
      const version = wireVersion(dto?.syncVersion);
      if (version === null) {
        skipped++;
        continue;
      }
      houseCursor = Math.max(houseCursor, version);
      const record = tryHouseFromDto(dto);
      if (!record) {
        skipped++;
        continue;
      }
      if (keepLocalRecord(houses.get(record.id), record)) continue;
      await this.store.putHouseFromServer(record);
      this.live(gen);
      houses.set(record.id, record);
      pulled++;
    }
    await this.store.setSetting(SETTING_KEYS.houseCursor, String(houseCursor));
    this.live(gen);

    const visits = new Map<string, VisitRecord>(
      (await this.store.allVisits()).map((v): [string, VisitRecord] => [v.id, v]),
    );
    this.live(gen);
    let visitCursor = cursors.visit;
    const visitRows = wireRows(await this.call(gen, () => this.api.visitsSince(visitCursor)));
    this.live(gen);
    for (let i = 0; i < visitRows.length; i++) {
      await this.stopPoint(gen, SETTING_KEYS.visitCursor, visitCursor, visitRows, i);
      this.progress.set({ phase: 'visits', done: i + 1, total: visitRows.length });
      const dto = visitRows[i];
      const version = wireVersion(dto?.syncVersion);
      if (version === null) {
        skipped++;
        continue;
      }
      visitCursor = Math.max(visitCursor, version);
      const record = tryVisitFromDto(dto);
      if (!record) {
        skipped++;
        continue;
      }
      if (keepLocalRecord(visits.get(record.id), record)) continue;
      await this.store.putVisitFromServer(record);
      this.live(gen);
      visits.set(record.id, record);
      pulled++;
    }
    await this.store.setSetting(SETTING_KEYS.visitCursor, String(visitCursor));
    this.live(gen);

    let photoCursor = cursors.photo;
    const photoRows = wireRows(await this.call(gen, () => this.api.photoChangesSince(photoCursor)));
    this.live(gen);
    for (let i = 0; i < photoRows.length; i++) {
      await this.stopPoint(gen, SETTING_KEYS.photoCursor, photoCursor, photoRows, i);
      this.progress.set({ phase: 'photos', done: i + 1, total: photoRows.length });
      const change = photoRows[i];
      const version = wireVersion(change?.syncVersion);
      if (version === null) {
        skipped++;
        continue;
      }
      const cursorBefore = photoCursor;
      photoCursor = Math.max(photoCursor, version);
      if (!change || !isRecordId(change.id) || !isRecordId(change.houseId)) {
        skipped++;
        continue;
      }
      const local = await this.store.getPhoto(change.id);
      this.live(gen);
      if (change.deleted) {
        if (local) {
          await this.store.forgetPhoto(change.id);
          this.live(gen);
          pulled++;
        }
        continue;
      }
      if (local) continue;
      const house = houses.get(change.houseId);
      if (!house || house.deleted) continue; // the house is gone or deleted here: nothing to attach the photo to
      const photoId = change.id;
      let blob: Blob;
      try {
        // One request per photo: on a large first-run download this is what meets the server's rate limit, and
        // `call` waits out a short 429 and carries on instead of failing the whole pull.
        blob = await this.call(gen, () => this.api.photo(photoId));
      } catch (err: unknown) {
        // Still refused (a long Retry-After, or the network went away): keep the position reached, so "Try again"
        // resumes at this photo rather than walking the whole photo list again. A cancelled run writes nothing.
        if (!(err instanceof SyncCancelled)) await this.keepPhotoPosition(gen, cursorBefore, photoRows, i);
        throw err;
      }
      // The download may have taken a while: "Remove all data" during it must not see this blob arrive afterwards.
      this.live(gen);
      const record: PhotoRecord = {
        id: change.id,
        houseId: change.houseId,
        blob,
        contentType: change.contentType || blob.type || 'image/jpeg',
        sizeBytes: change.sizeBytes ?? blob.size,
        createdAt: change.createdAt ?? null,
        updatedAt: change.updatedAt ?? null,
        deleted: false,
        syncVersion: version,
        uploaded: true,
      };
      try {
        await this.store.putPhotoRecord(record);
      } catch (err: unknown) {
        if (isQuotaError(err)) await this.keepPhotoPosition(gen, cursorBefore, photoRows, i);
        throw err;
      }
      this.live(gen);
      pulled++;
    }
    await this.store.setSetting(SETTING_KEYS.photoCursor, String(photoCursor));
    this.live(gen);

    return { pulled, skipped };
  }

  /**
   * The photo phase ends at photo `next`: the browser is out of space for it, or its download still failed after
   * {@link call} (a long `Retry-After`, the connection gone). It ends on the **first** refusal, rather than
   * downloading every remaining blob only to have each one refused too. The position reached is kept, so "Try
   * again" (after the user has freed space, or a minute later) carries on from this photo instead of starting the
   * phase over. Best effort: with the disk that full even this small write may be refused, and then the next run
   * simply re-checks the photos it already has (they are skipped as already stored).
   */
  private async keepPhotoPosition(gen: number, cursor: number, rows: readonly unknown[], next: number): Promise<void> {
    if (gen !== this.generation) return;
    try {
      await this.store.setSetting(SETTING_KEYS.photoCursor, String(resumeCursor(cursor, rows.slice(next))));
    } catch {
      // No room for the cursor either; see above.
    }
  }

  // ---- First-run migration (docs/11 §5.10, "Migration of today's online web users") ----

  /**
   * On the first start of the local-first app with a server already configured, offer to copy everything into this
   * browser instead of doing it silently: on a shared or metered connection that is the user's decision.
   */
  private async checkMigration(): Promise<void> {
    const state = await this.store.setting(SETTING_KEYS.migration);
    if (state === 'done' || state === 'skipped') {
      this.migration.set(state);
      return;
    }
    this.migration.set((await this.store.isEmpty()) ? 'offered' : 'done');
  }

  /**
   * "Download my houses to this browser": a full pull (cursors start at 0, so this is the whole account), from the
   * first-run banner or, after "Not now" or "Stop", from the Your data page — which then carries on from the
   * cursors a stopped download stored. On failure the previous answer is put back — the banner's question, or the
   * paused state — rather than pretending the download worked. A stopped or cancelled download has already set
   * the state it ends in, so that is left alone.
   */
  async downloadToThisBrowser(): Promise<void> {
    if (!this.enabled()) return;
    const before = this.migration();
    this.migration.set('running');
    await this.syncNow(true);
    if (this.migration() === 'running' && this.lastError()) {
      this.migration.set(before === 'skipped' ? 'skipped' : 'offered');
    }
  }

  /**
   * "Not now": **nothing is downloaded and sync is paused in this browser**, in both directions, until the user
   * chooses "Download now" on the Your data page ({@link downloadToThisBrowser}). The answer is remembered, so the
   * banner does not come back on every start. Deliberately no sync here: with every cursor at 0 even a "sync from
   * here on" would be the whole account and every photo, on the shared or metered connection the offer exists for.
   */
  async skipMigration(): Promise<void> {
    clearTimeout(this.timer);
    this.migration.set('skipped');
    await this.store.setSetting(SETTING_KEYS.migration, 'skipped');
  }
}

/**
 * How long to wait before repeating a request the server refused with `429`, or `null` when `err` is anything
 * else. The server's `Retry-After` wins when it gives one; without it the wait doubles from one second
 * (1 s, 2 s, 4 s ...), capped like Android's backoff. No jitter: one browser, one request at a time.
 */
export function rateLimitWaitMs(err: unknown, attempt: number): number | null {
  if (!(err instanceof HttpErrorResponse) || err.status !== 429) return null;
  const seconds = retryAfterSeconds(err);
  if (seconds !== null) return seconds * 1000;
  return Math.min(MAX_RATE_LIMIT_WAIT_MS, 1000 * 2 ** Math.max(0, attempt - 1));
}

function isOffline(): boolean {
  return typeof navigator !== 'undefined' && navigator.onLine === false;
}

/**
 * The cursor to store when a pull stops before `rest` (the rows not yet applied, in the order received).
 *
 * The backend returns rows ordered by `syncVersion` (`findBySyncVersionGreaterThanOrderBySyncVersion`), so the
 * highest version applied so far is below every remaining one and is the right place to resume. Relying on that
 * order silently would lose rows if it ever changed, so the cursor is also kept below the lowest version still to
 * come: a later "Download now" then asks for those rows again rather than skipping them.
 */
export function resumeCursor(reached: number, rest: readonly unknown[]): number {
  let lowest = Number.POSITIVE_INFINITY;
  for (const row of rest) {
    const version = wireVersion((row as { syncVersion?: unknown } | null | undefined)?.syncVersion);
    if (version !== null && version < lowest) lowest = version;
  }
  return Number.isFinite(lowest) ? Math.min(reached, lowest - 1) : reached;
}

/**
 * The rows of a list endpoint.
 *
 * Angular turns a non-JSON 200 (a captive portal's sign-in page, a proxy's error page) into an
 * `HttpErrorResponse` on its own, so the case left to guard is well-formed JSON of the wrong shape — an object
 * where an array was promised. Iterating that throws a bare `TypeError`; this fails with a translated message
 * instead, before any cursor has moved.
 */
export function wireRows<T>(body: readonly T[]): readonly T[] {
  if (!Array.isArray(body)) throw new LocalDataError('error.server');
  return body as readonly T[];
}

/**
 * A row's `syncVersion` as a finite number, or `null` when it cannot be used to move a cursor.
 *
 * Deliberately **not** `Number(value)` with a list of exceptions: `Number()` coerces objects and arrays through
 * `valueOf`/`toString`, so `Number([])` is `0` and `Number(['5'])` is `5`. A hand-rolled or proxied body carrying
 * `"syncVersion": []` would then move the cursor to 0 and store the row as version 0 — exactly the poisoning this
 * function exists to prevent. So only the two primitive shapes the wire can legitimately use are accepted:
 *
 *  * a finite `number` — what the backend writes (`HouseDto.syncVersion` is a JSON number);
 *  * a `string` that is a finite number once trimmed — tolerated because a proxy or a hand-written fixture may
 *    quote it, and `"12"` means 12 to every reader. `""` and `"  "` are not numbers and are refused.
 *
 * Everything else — `undefined`, `null`, booleans, objects, arrays, `NaN`, `Infinity` — is `null`.
 */
export function wireVersion(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (trimmed === '') return null;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}
