import { describe, expect, it } from 'vitest';
import {
  INSTALL_SNOOZE_MS,
  type WorkerContainer,
  appBaseUrl,
  installSnoozed,
  serviceWorkerRegistration,
  unregisterOwnWorker,
} from './pwa.service';

/**
 * The live site is on Firebase Hosting, served from the root of its own origin (`https://doorprints.web.app/`,
 * base href `/`, the `npm run build` artifact). The same build must keep working under a sub-path (it was built
 * for `/doorprints/` on a GitHub Pages project site before 2026-09-23), so both shapes are pinned here.
 *
 * A service worker may only claim a scope at or below its own path, and a static host sends no
 * `Service-Worker-Allowed` (`web/firebase.json` deliberately sets none), so a worker served from
 * `/doorprints/sw.js` asking for `{ scope: '/' }` makes `register()` throw `SecurityError` — which `PwaService` catches, leaving the site with
 * no offline shell and no install offer while the build stays green. Everything therefore comes from
 * `<base href>`, and that is what these check: a literal `/` anywhere here is the bug.
 */
describe('PWA paths follow the base href', () => {
  const at = (baseURI: string) => appBaseUrl({ baseURI });

  /**
   * `appBaseUrl` trusts `document.baseURI` and nothing else. It does not — and cannot — tell a deep link from the
   * app directory: handed `…/doorprints/houses/42` it would answer `…/doorprints/houses/`. What keeps the result
   * right on a deep link is that a real document's `baseURI` **is** the `<base href>` (`/doorprints/`), not the
   * page URL, which is why `src/index.html` must always carry a `<base>` tag (CI's pwa-files job reports the
   * built one, and `baseHrefOf` in the postbuild step fails the build without it).
   */
  it('is the directory of document.baseURI, which <base href> pins to the app directory', () => {
    expect(at('https://example.com/').href).toBe('https://example.com/');
    expect(at('https://owner.github.io/doorprints/').href).toBe('https://owner.github.io/doorprints/');
  });

  it('registers the worker beside index.html and claims exactly its own directory', () => {
    const root = serviceWorkerRegistration(at('https://example.com/'));
    expect(root.url.href).toBe('https://example.com/sw.js');
    expect(root.scope).toBe('/');

    const project = serviceWorkerRegistration(at('https://owner.github.io/doorprints/'));
    expect(project.url.href).toBe('https://owner.github.io/doorprints/sw.js');
    // Not '/': that scope is above the worker's own path, and registration would throw SecurityError.
    expect(project.scope).toBe('/doorprints/');
    expect(project.url.pathname.startsWith(project.scope)).toBe(true);
  });

  it('at the root of its own origin (Firebase Hosting) registers /sw.js for scope /', () => {
    const live = serviceWorkerRegistration(at('https://doorprints.web.app/'));
    expect(live.url.href).toBe('https://doorprints.web.app/sw.js');
    expect(live.scope).toBe('/');
    // A deep link such as /houses/42 is served index.html (the `**` rewrite in firebase.json). That case rests on <base href="/">
    // keeping document.baseURI at the root, not on anything here, so it is not re-asserted: see the appBaseUrl
    // test above and "derives the scope from document.baseURI rather than the location" below.
  });

  it('derives the scope from document.baseURI rather than the location', () => {
    // The page really is at /doorprints/data, and the scope is still the app directory.
    const scope = serviceWorkerRegistration(at('https://owner.github.io/doorprints/')).scope;
    expect(scope).not.toBe('/');
    expect(scope).toBe(new URL('./', 'https://owner.github.io/doorprints/data').pathname);
  });
});

/**
 * "Not now" on the install banner is stored (localStorage `doorprints.installDismissedAt`) and lasts 30 days, so the
 * banner does not come back on every start; the Your data page keeps the install section either way.
 */
describe('install dismissal', () => {
  const now = Date.parse('2026-09-22T12:00:00.000Z');

  it('hides the offer for 30 days after "Not now", then offers it again', () => {
    expect(installSnoozed(new Date(now).toISOString(), now)).toBe(true);
    expect(installSnoozed(new Date(now - INSTALL_SNOOZE_MS + 60_000).toISOString(), now)).toBe(true);
    expect(installSnoozed(new Date(now - INSTALL_SNOOZE_MS).toISOString(), now)).toBe(false);
  });

  it('treats a missing, unreadable or future date as "not dismissed"', () => {
    expect(installSnoozed(null, now)).toBe(false);
    expect(installSnoozed('', now)).toBe(false);
    expect(installSnoozed('yesterday', now)).toBe(false);
    // A clock that was wrong when it was stored must not hide the offer for years.
    expect(installSnoozed(new Date(now + 24 * 60 * 60 * 1000).toISOString(), now)).toBe(false);
  });
});

/**
 * "Remove all data" unregisters the app's worker (docs/07 Appendix A.1, S10), and only the app's own: the one whose
 * scope is this deployment's directory. Anything else registered on the origin is someone else's.
 */
describe('unregisterOwnWorker', () => {
  function containerWith(scopes: readonly string[]) {
    const unregistered: string[] = [];
    const registrations = scopes.map((scope) => ({
      scope,
      unregister: () => {
        unregistered.push(scope);
        return Promise.resolve(true);
      },
    }));
    const container = {
      getRegistrations: () => Promise.resolve(registrations as unknown as readonly ServiceWorkerRegistration[]),
    } satisfies WorkerContainer;
    return { container, unregistered };
  }

  it('unregisters the registration for the app directory at the root of its own origin', async () => {
    const { container, unregistered } = containerWith(['https://doorprints.web.app/']);
    expect(await unregisterOwnWorker(container, new URL('https://doorprints.web.app/'))).toBe(1);
    expect(unregistered).toEqual(['https://doorprints.web.app/']);
  });

  it('leaves other deployment paths and other apps on the same origin alone', async () => {
    const { container, unregistered } = containerWith([
      'https://owner.github.io/',
      'https://owner.github.io/doorprints/',
      'https://owner.github.io/other-app/',
    ]);
    expect(await unregisterOwnWorker(container, new URL('https://owner.github.io/doorprints/'))).toBe(1);
    expect(unregistered).toEqual(['https://owner.github.io/doorprints/']);
  });

  it('does nothing without service workers, or with none registered', async () => {
    expect(await unregisterOwnWorker(null, new URL('https://doorprints.web.app/'))).toBe(0);
    const { container, unregistered } = containerWith([]);
    expect(await unregisterOwnWorker(container, new URL('https://doorprints.web.app/'))).toBe(0);
    expect(unregistered).toEqual([]);
  });
});

/*
 * `public/manifest.webmanifest` carries the same rule — `start_url`, `scope`, the share target's `action`, the icon
 * `src`s and the shortcut `url`s are all relative, because those resolve against the **manifest URL**, which
 * `<link rel="manifest" href="manifest.webmanifest">` already makes base-href-relative. The same bytes therefore
 * describe the `/` deployment (Firebase Hosting, live) and a sub-path one such as `/doorprints/`.
 *
 * **`id` is the one member that does not work that way, so `public/` carries none and the build writes it.** An
 * `id` is resolved against the origin of `start_url`, not against the manifest URL: MDN, "Since `id` is resolved
 * against `start_url`'s origin, `id` values such as `../foo`, `foo`, `/foo`, and `./foo` all resolve to the same
 * identifier relative to the origin." A literal `"id": "./"` would therefore claim `https://<owner>.github.io/` —
 * the shared origin root — for every app deployed under it. `npm run build`'s postbuild step instead writes the
 * **absolute base path** from the built index.html's `<base href>` (`/` on Firebase Hosting, `stampManifestId` in
 * `scripts/sw-precache-core.mjs`, tested in `sw-precache.spec.ts`). That is the same identity an installed copy
 * already had through `start_url`, so nothing installed before becomes a different app.
 *
 * There is deliberately no assertion about the manifest here. It is a static asset under `public/`, not a module
 * the unit-test builder resolves, so any test of it in this file would have to restate its values and could then
 * only ever check its own copy. The real guard is CI: the `pwa-files` job parses the **built** manifest and
 * fails on a missing or inconsistent field (`.github/workflows/web.yml`, owned by the DevOps team).
 */
