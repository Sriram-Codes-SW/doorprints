import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { ConfirmService } from './confirm.service';
import { UnsavedChanges } from './unsaved-changes.service';

/** The non-standard event Chromium fires when the app could be installed. */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

/** How long to wait after the first paint before registering, so the worker never competes with it. */
const REGISTER_DELAY_MS = 3000;

/** How long "Reload" waits for the new worker to take over before reloading anyway. */
export const UPDATE_FALLBACK_MS = 3000;

/** localStorage key holding when the user last said "Not now" to the install offer (an ISO date). */
export const INSTALL_DISMISSED_KEY = 'doorprints.installDismissedAt';
/** How long "Not now" on the install banner lasts. The install section on Your data is always there. */
export const INSTALL_SNOOZE_MS = 30 * 24 * 60 * 60 * 1000;

/**
 * True while an install dismissal stored as `raw` is still in force at `now`. Anything unreadable counts as "not
 * dismissed", and so does a date in the future (a clock that was wrong when it was stored must not hide the offer
 * for years).
 */
export function installSnoozed(raw: string | null, now: number = Date.now()): boolean {
  if (!raw) return false;
  const at = Date.parse(raw);
  if (!Number.isFinite(at) || at > now) return false;
  return now - at < INSTALL_SNOOZE_MS;
}

function readDismissal(): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(INSTALL_DISMISSED_KEY);
  } catch {
    return null;
  }
}

function writeDismissal(value: string): void {
  try {
    localStorage.setItem(INSTALL_DISMISSED_KEY, value);
  } catch {
    // Storage blocked (private mode): the dismissal lasts for this page only, which is the best there is.
  }
}

/**
 * Where the app is deployed, as an absolute URL ending in `/`.
 *
 * **Everything PWA is derived from this and nothing is hardcoded to `/`.** The live site (Firebase Hosting) is
 * served from the root of its own origin, so there this is `https://doorprints.web.app/`; a build with
 * `--base-href=/some/path/` works under that sub-path too. A service worker may only claim a scope at or below its
 * own path, so registering `/sw.js` with `{ scope: '/' }` from a sub-path throws `SecurityError` and the app would
 * silently have no offline shell and no install offer. `document.baseURI` is `<base href>` resolved against the
 * page, which is exactly the directory the worker and the manifest were deployed into. `src/index.html` always
 * carries a `<base href>` (CI's pwa-files check reports it), so this never falls back to the current route's path.
 */
export function appBaseUrl(doc: Pick<Document, 'baseURI'> = document): URL {
  return new URL('./', doc.baseURI);
}

/** The URL the service worker is registered from, and the scope it claims: both under {@link appBaseUrl}. */
export function serviceWorkerRegistration(base: URL): { url: URL; scope: string } {
  return { url: new URL('sw.js', base), scope: base.pathname };
}

/** The part of `ServiceWorkerContainer` {@link unregisterOwnWorker} needs (a fake in the unit test). */
export type WorkerContainer = Pick<ServiceWorkerContainer, 'getRegistrations'>;

/**
 * Unregisters this deployment's service worker: only a registration whose scope is exactly `base` (the app
 * directory, {@link appBaseUrl}). The origin may hold other registrations (another deployment path, another app on a
 * shared origin), and those are left alone, as `sw.js` leaves their caches alone. Returns how many were removed.
 */
export async function unregisterOwnWorker(container: WorkerContainer | null, base: URL): Promise<number> {
  if (!container) return 0;
  let removed = 0;
  for (const registration of await container.getRegistrations()) {
    if (registration.scope !== base.href) continue;
    if (await registration.unregister()) removed += 1;
  }
  return removed;
}

/**
 * Registers the service worker and drives the install and update prompts (S4-05, docs/11 §5.10).
 *
 * **Install** has a permanent home: the "Install the app" section on Your data (docs/05 §14.4, docs/11 §5.10), with
 * the browser's install button where Chromium offers one and the Add-to-Home-Screen steps on iOS. In addition, the
 * app offers it **once** as a banner, and only after the user has saved a house (the same moment it asks the
 * browser to keep the data): "Not now" there is remembered for {@link INSTALL_SNOOZE_MS} (30 days) in
 * localStorage, so it does not come back on every start. Nothing ever opens the browser's own install dialog
 * without a tap.
 *
 * **Updates** are announced with a button rather than reloading under the user's hands, and the reload itself
 * checks {@link UnsavedChanges} first, so a half-typed house is saved or knowingly given up, never lost.
 *
 * The update banner depends on the build, not on this class: the browser only reports `updatefound` when the
 * bytes of `sw.js` change, and `public/sw.js` is a template that `npm run build` stamps with a build id and the
 * build's file list (`scripts/sw-precache.mjs`). A build made with a bare `ng build` has an unstamped worker,
 * which caches nothing and can never announce anything — which is why the README's build commands all go through
 * `npm run build`. Registering late (below) costs nothing offline: the stamped worker downloads the whole build
 * on `install`, so what the page loaded before it existed is cached all the same.
 */
@Injectable({ providedIn: 'root' })
export class PwaService {
  /** Set when Chromium has offered an install prompt we can trigger. */
  readonly canInstall = signal(false);
  /** True once a newer build is waiting to take over. */
  readonly updateReady = signal(false);
  /**
   * "Not now" on the update notice, for this visit only (memory, not storage): the waiting build is offered again
   * on the next start. Until then the notices behind it (install, storage risk) can show.
   */
  readonly updateDismissed = signal(false);
  /** The user said "Not now" to the install banner within the last 30 days (persisted, see {@link installSnoozed}). */
  readonly installDismissed = signal(installSnoozed(readDismissal()));
  /**
   * Why registration did not happen or did not work.
   *
   * Nothing shows it: the app works without a worker, and there is nothing useful to tell the user. It exists so
   * the reason is legible in a debugger or a console instead of vanishing into an empty `catch` — which is how
   * the whole PWA came to be silently dead on the former GitHub Pages project site (sub-path `/doorprints/`).
   */
  readonly registrationProblem = signal<'unsupported' | 'insecure-origin' | 'failed' | null>(null);

  private deferredPrompt: BeforeInstallPromptEvent | null = null;
  private waiting: ServiceWorker | null = null;
  private readonly unsaved = inject(UnsavedChanges);
  private readonly confirm = inject(ConfirmService);

  constructor() {
    if (typeof window === 'undefined') return;
    const destroyRef = inject(DestroyRef);

    const onBeforeInstall = (event: Event) => {
      // Keep the event so the offer can be shown where it belongs, not as a browser pop-up.
      event.preventDefault();
      this.deferredPrompt = event as BeforeInstallPromptEvent;
      this.canInstall.set(true);
    };
    const onInstalled = () => {
      this.deferredPrompt = null;
      this.canInstall.set(false);
    };
    window.addEventListener('beforeinstallprompt', onBeforeInstall);
    window.addEventListener('appinstalled', onInstalled);
    destroyRef.onDestroy(() => {
      window.removeEventListener('beforeinstallprompt', onBeforeInstall);
      window.removeEventListener('appinstalled', onInstalled);
    });

    void this.register();
  }

  /**
   * Registers `sw.js` **relative to the base href** once the page is quiet.
   *
   * The URL and the scope come from {@link serviceWorkerRegistration}, which `pwa.service.spec.ts` pins: a
   * literal `/` in either is the bug that left the PWA dead under the former `/doorprints/` sub-path. The delay is
   * a parameter so a caller can register immediately rather than after the first-paint pause.
   */
  async register(delayMs: number = REGISTER_DELAY_MS): Promise<ServiceWorkerRegistration | null> {
    if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) {
      this.registrationProblem.set('unsupported');
      return null;
    }
    // file:// and plain http:// (other than localhost) have no service worker; registering would throw.
    if (location.protocol !== 'https:' && location.hostname !== 'localhost' && location.hostname !== '127.0.0.1') {
      this.registrationProblem.set('insecure-origin');
      return null;
    }
    if (delayMs > 0) await new Promise<void>((resolve) => setTimeout(resolve, delayMs));
    const { url, scope } = serviceWorkerRegistration(appBaseUrl());
    try {
      // `url.href`, not the URL object: the DOM typing for `register` has varied between lib versions.
      const registration = await navigator.serviceWorker.register(url.href, { scope });
      this.registrationProblem.set(null);
      if (registration.waiting) this.markWaiting(registration.waiting);
      registration.addEventListener('updatefound', () => {
        const installing = registration.installing;
        if (!installing) return;
        installing.addEventListener('statechange', () => {
          // "installed" with a controller already present means this is an update, not the first install.
          if (installing.state === 'installed' && navigator.serviceWorker.controller) this.markWaiting(installing);
        });
      });
      return registration;
    } catch {
      // No service worker (private mode, blocked by policy): the app still works, just without offline start.
      this.registrationProblem.set('failed');
      return null;
    }
  }

  /**
   * Part of "Remove all data" (docs/07 Appendix A.1, S10): unregisters this deployment's worker, so a shared
   * computer keeps no Doorprints worker once this tab is closed. The worker holds none of the user's data (its only
   * store, the app-shell cache, is emptied by `LocalStore.clearEverything`); what goes is the trace that the app
   * was used here. The browser lets it go on serving this open tab until the tab is closed or reloaded, so the app
   * keeps working; nothing registers it again during this page's life, and the next start registers a fresh one
   * (offline start then comes back, with the app's own files only). Never throws: a refusal leaves the rest of
   * "Remove all data" done.
   */
  async unregister(): Promise<void> {
    try {
      const container = typeof navigator !== 'undefined' && 'serviceWorker' in navigator ? navigator.serviceWorker : null;
      await unregisterOwnWorker(container, appBaseUrl());
    } catch {
      // Unsupported, blocked or refused: nothing else depends on it.
    }
  }

  private markWaiting(worker: ServiceWorker): void {
    this.waiting = worker;
    this.updateReady.set(true);
  }

  /** Shows the browser's install dialog. Returns true when the user accepted. */
  async install(): Promise<boolean> {
    const prompt = this.deferredPrompt;
    if (!prompt) return false;
    this.deferredPrompt = null;
    this.canInstall.set(false);
    try {
      await prompt.prompt();
      const choice = await prompt.userChoice;
      return choice.outcome === 'accepted';
    } catch {
      return false;
    }
  }

  dismissUpdate(): void {
    this.updateDismissed.set(true);
  }

  /** "Not now" on the install banner: hidden for 30 days, across reloads. Your data keeps the install section. */
  dismissInstall(now: number = Date.now()): void {
    writeDismissal(new Date(now).toISOString());
    this.installDismissed.set(true);
  }

  /**
   * Lets the waiting worker take over and reloads once, so the user sees the new version.
   *
   * A reload skips the router's unsaved-changes guard, so it asks here first when the screen holds unsaved edits:
   * "Save first" saves and then reloads, "Reload" gives the edits up knowingly, Cancel keeps the banner. Resolves
   * false when nothing was reloaded.
   */
  async applyUpdate(): Promise<boolean> {
    if (this.unsaved.dirty()) {
      const answer = await this.confirm.choose(
        { key: 'confirm.reloadUnsaved' },
        { confirmKey: 'pwa.reload', altKey: this.unsaved.canSave ? 'confirm.saveFirst' : null, danger: true },
      );
      if (answer === 'cancel') return false;
      if (answer === 'alt' && !(await this.unsaved.save())) return false;
    }
    // Agreed (or nothing to lose): the page's own beforeunload warning must not ask a second time.
    this.unsaved.leaving = true;
    const worker = this.waiting;
    this.updateReady.set(false);
    if (!worker) {
      location.reload();
      return true;
    }
    let reloaded = false;
    const reloadOnce = () => {
      if (reloaded) return;
      reloaded = true;
      location.reload();
    };
    navigator.serviceWorker.addEventListener('controllerchange', reloadOnce);
    worker.postMessage('skip-waiting');
    // A worker that never takes over (it was replaced, or the message was lost) would leave the user with the banner
    // gone and nothing happening. Reload anyway: the page then loads whatever version the browser has.
    setTimeout(reloadOnce, UPDATE_FALLBACK_MS);
    return true;
  }
}
