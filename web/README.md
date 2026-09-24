# Doorprints — web

*Remember every house you've seen.*

Angular 22 installable web app (PWA): a map + list of houses, house details (checklist, rating, photos, visits),
a side-by-side comparison and an offline copy of your data in six formats. Uses MapLibre GL with the free
[OpenFreeMap](https://openfreemap.org) "liberty" style (no API key).

**India's boundaries (2026-09-24).** Every map shows India's external boundary as the Government of India does, the
only view, because every user is in India: all of Jammu and Kashmir and Ladakh (Pakistan-occupied Kashmir,
Gilgit-Baltistan, Shaksgam and Aksai Chin included) and Arunachal Pradesh inside India, one solid outline, no Line of
Control or Line of Actual Control. Liberty itself draws the ISO view, so `src/app/shared/india-boundaries.ts` changes it
on every `style.load` (`createMlMap` in `shared/map-style.ts`, used by the Map, Plan and house pages). It hides
`boundary_disputed`. It starts `boundary_2` at zoom 5 and keeps only the lines that carry an `adm0_l` or `adm0_r`
side and are not the Pakistan-China line (`COUNTRY_LINE_RULE`, the adm0 clause; `COUNTRY_LINE_RULE_LEGACY` for a
filter in the deprecated syntax). It adds the tile-zoom guard `TILE_ZOOM_GUARD`, `[">=", ["zoom"], 5]` (the zoom of
the tile a feature comes from), to `boundary_2`, `boundary_3` and every other `boundary` line layer from zoom 5, never
`boundary_disputed`, so a zoom 0-4 tile shown while a zoom 5+ tile loads, or offline, draws no line through them.
maplibre-gl already skips those layers in such a tile because of their minzoom, so on the web the guard is defence in
depth and parity with Android. It adds the bundled
`public/geo/in-boundaries.geojson` (Natural Earth, public domain; built by the lead's `scripts/geo/build_in_boundaries.py`,
byte-identical to Android's copy) as source `in-boundaries` with layers `in-boundary-world` (below zoom 5) and
`in-boundary-claim` (every zoom) directly above `boundary_2`, and `in-boundary-state` (`IN_BOUNDARY_STATE_LAYER`, the
Assam-Arunachal Pradesh state line, from zoom 5) directly above `boundary_3`, copying its colour, width, dashes and
opacity (`STATE_FALLBACK_LINE_PAINT` without it), and filters the "Azad Kashmir" and "Gilgit-Baltistan"
state labels out. A missing layer is a console warning, never a broken map. The file is same-origin (base href), so it
needs no CSP change, is precached by `sw.js` like the rest of the build and is served as `application/geo+json`
(`firebase.json`). The attribution adds "Natural Earth". Near Arunachal Pradesh the tiles carry the India-China line
only as disputed lines (rule 1 hides them) and the overlay's `claim` outline draws India's boundary there. **Android
applies the same rules** to the same style and the same file
(`android/app/src/main/java/com/househunt/app/ui/IndiaView.kt`, the rules as data in `IndiaViewRules.kt`); the only
deliberate difference is the "Natural Earth" credit, web only. **One line from zoom
5 (branch `fix/india-boundary-lines`, PR #16):** rule 2 also leaves out India's line with China (`INDIA_CHINA_LINE`:
China on one side, India or no country on the other), which the tiles cut into drawn and hidden pieces, so India's
outline draws that whole border at every zoom; China's lines with Nepal, Bhutan and Myanmar still draw. Along the 7
stretches where the tiles draw India's border themselves (Nepal near Kalapani; Sikkim and the Darjeeling and Kalimpong
hills with Nepal and with Bhutan; Bhutan's south-east corner; Myanmar south of about 26.65°N; the Wakhan), the file
holds India's outline as kind `world`, so it draws below zoom 5 only and the tiles' line takes over from zoom 5; each
`claim` piece ends with a connector of about 7 km at most to the tile line (none at a box edge). The stretches come
from `scripts/geo/find_shared_stretches.py` (planet 20260913, zooms 7, 9 and 11; tile cache `scripts/geo/.tilecache`,
git-ignored), pasted into `SHARED` in `build_in_boundaries.py`; re-run it after each OpenFreeMap planet or style update
(S4b-BL-9). The Assam-Arunachal Pradesh state line, which the tiles carry as a disputed admin-4 line claimed by China,
comes from the file's `state` kind. **Known limits, both apps:** the `claim` outline (Natural Earth 1:10m) is
typically 1.5-3 km off the true line, up to about 5 km in a few mountain stretches; at street zoom a hand-over to the
tile line shows as a small step, and at Sikkim's two tri-junctions as a loop (about 13 x 3 km at Nepal-China-India,
on glaciers, from about zoom 10; about 2 km at Doklam); from zoom 11 the tile line runs on past the hand-over at
Jomotsangkha (about 9 km) and Longwa (about 3 km); `INDIA_CHINA_LINE` also hides about 12 km of the China-North Korea
line on the Tumen islets (harmless for India); while zoom 5+ tiles load, or offline without them, the 7 shared stretches show no line at zoom 5 and above
([docs/03](../docs/03-design.md) ADR-22).

**Local-first (Sprint 4a, [docs/11](../docs/11-feature-parity-and-export-spec.md) D-01).** Everything you save
lives in this browser, in IndexedDB. There is no sign-in and no server to set up: the app opens straight on the
map, and after one online visit every screen opens offline too (see "Installable" below for what does not work
without a network: map tiles, address lookup, the AI features and sync). Connecting a Doorprints server is optional and only adds sync between your
devices — the same protocol, DTOs and last-write-wins rule as the Android app
(`android/shared/.../sync/SyncRules.kt`, ported in `src/app/data/sync-rules.ts`).

## Develop

```bash
npm install
npx ng serve        # http://localhost:4200
```

The app starts working immediately with no configuration. **Connect** (in the header; on phones under Your data → Server connection) is where you optionally
add a Doorprints server: an API base URL (e.g. `http://localhost:8080`; the field starts empty except on localhost)
and an API key. "Save and continue" tests the connection first and offers "Save anyway" if it fails. By default they are kept
only for this browser tab (sessionStorage); tick "Remember on this device" to keep them in localStorage. The API
must list the web app's origin in `APP_CORS_ORIGINS`. The first time a server is configured on a browser that is
still empty, the app offers "Download your houses to this browser" rather than pulling silently. "Not now"
means nothing is downloaded and sync stays paused in that browser (both directions) until you choose
"Download now" under **Your data**.

### How the data layer fits together

| Layer | File | What it does |
|---|---|---|
| Store | `src/app/data/local-db.ts` | A few dozen lines over IndexedDB, plus an in-memory stand-in used by the tests and when a browser blocks storage (private mode). No new npm dependency. |
| Repository | `src/app/data/local-store.service.ts` | Houses, visits, photos (as `Blob`s) and settings; fixed ordering; tombstones and `dirty` flags. |
| Screens | `src/app/core/local-data.service.ts` | The Observable API the pages use. Same method names the old `HouseApiService` had, so the screens did not change. |
| Sync | `src/app/data/sync.service.ts` | Optional. Push dirty rows, pull with `since` cursors, last-write-wins, photo metadata + bytes. |
| Durability | `src/app/data/storage.service.ts` | `navigator.storage.persist()`, usage estimate, and the warning when the browser will not promise to keep the data. |

## Your data: save a copy (readable copies and the full backup)

The **Save a copy** section of the **Your data** page (the same name as Android's *Save a copy* screen) builds six
deterministic formats from IndexedDB, entirely offline
([docs/11 §5.2](../docs/11-feature-parity-and-export-spec.md)). Naming follows the brand brief §G: *save a copy*
is the only verb for data going out, and *import* is reserved for a backup coming in, so no user-facing string
says "export" (the storage banner's button is **Save a backup**, and the danger card's is **Save a backup first**):

| Format | File | Notes |
|---|---|---|
| HTML | `Doorprints-copy-<date>.html` | Self-contained: inline CSS, no JavaScript, photos as `data:` URIs, a restrictive CSP `<meta>`. The word `copy` says it is a readable copy, not a backup that can be imported (naming rule: only `Doorprints-backup-…` is ever imported); the same name is used for the copy inside the backup ZIP. |
| PDF | – | The HTML copy opened in the browser's print view ("Save as PDF"); browsers shape Devanagari, Tamil and Telugu correctly, JS PDF libraries do not. |
| CSV | `Doorprints-<date>-csv.zip` | `houses`, `scores`, `visits`, `photos`; UTF-8 with BOM, CRLF, RFC 4180, formula-injection guard. |
| XLSX | `Doorprints-<date>.xlsx` | Own SpreadsheetML writer: one sheet per table, frozen header, ₹ and dates as typed cells. |
| Markdown | `Doorprints-<date>.md` | Headings per house, tables, photos listed by file name. |
| JSON backup | `Doorprints-backup-<date>.zip` | `data.json`, the HTML copy (`Doorprints-copy-<date>.html`), `photos/<id>.jpg`, then `manifest.json` (format `doorprints-backup/1`, SHA-256 per entry). The only re-importable format (import is S4-04). |

The naming guideline (brand brief §G.3.4) also gives the CSV, XLSX and Markdown copies the `Doorprints-copy-…`
form. Those three keep their current names for now: the rename will be made together with Android's
`ExportFormat`, so that the two apps give the same file the same name. Android's HTML copy is still called
`Doorprints-<date>.html` until its handover lands. No importer reads the HTML entry inside a backup, so the
different name does not affect import in either direction.

**The backup is a cross-platform contract.** `src/app/export/backup-export.ts` mirrors
`android/shared/src/commonMain/kotlin/com/househunt/shared/export/Backup.kt` and `ExportModel.kt` field for
field: the same `format` id, entry names, property names and order; epoch-millisecond timestamps rather than the
API's ISO strings; no `deleted` or `syncVersion`; null optionals left out (Kotlin's `explicitNulls = false`) and
compact JSON. A backup written on a phone must import in a browser and the other way round, so nothing in that
file may be renamed on one side only. The ranking order and the ₹/decimal formatting follow
`ExportModel.ranked` and `ExportRows.rupees`/`fixed` for the same reason — `toFixed` rounds differently from the
Kotlin arithmetic for values like 0.015, which would make the two apps disagree about a score.

The **column layout is aligned**: `src/app/export/export-rows.ts` and `export-strings.ts` are TypeScript ports of
`ExportRows.kt` and `ExportStrings.kt`, and both the CSV writer and the XLSX writer read their rows, columns and
headings from them. So the headings are translated (choosing Tamil gives a `houses.csv` header of
`வரிசை,வீடு,நிலை,மதிப்பெண்,விலை,…`) and rank, visit count, photo count, minutes and `itemLabel` are all
present. Note that the `scores` table now has a row only for an item the house actually has a score for, as
`ExportRows.scores` does; the older web table wrote a row for every built-in item, so an existing user's
`scores.csv` gets shorter.

*What is still not aligned*, all three by design or still open:

1. **Timestamps.** The web files are UTC (`2026-09-05 11:00`); Android formats in the phone's offset. The same
   data therefore gives different bytes on the two platforms for every dated column — this is a deliberate choice
   (a browser has no user-chosen time zone worth trusting for a file), not a bug to chase.
2. **XLSX cell rendering.** Rows, columns and headings agree, but the web writer puts a real number in the cell
   (`12.9`) where `XlsxWriter.kt` writes fixed-decimal *text* through `ExportRows.fixed` (`12.900000`). A
   spreadsheet shows the same value; the file bytes and the cell type differ. See the comment at the top of
   `xlsx-sheets.ts`.
3. **HTML and Markdown** are still driven by the app's own i18n catalogue (`DICTIONARIES[lang]`) rather than by
   `ExportStrings`. They honour the export-language option, so nothing is wrong for the user, but they are not yet
   on the shared contract and their headings can drift from the Kotlin writers'. Moving them across is the
   remaining half of this convergence.

Options: which houses, photos, contact details, and the language of the file. Output is **deterministic** —
the same data and options give byte-identical files, which the golden-file tests in
`src/app/export/golden/` check. The ZIP, XLSX, SHA-256 and CRC-32 writers are all in `src/app/export/`; the web
app has no npm dependency for any of them.

## Installable (PWA)

`public/manifest.webmanifest`, `public/icons/` and a small hand-written service worker (`public/sw.js`) make the
app installable and let it start with no network.

**The worker is stamped per build.** `public/sw.js` is a template; `npm run build` runs
`scripts/sw-precache.mjs` as its `postbuild` step, which writes a build id (from every output file's SHA-256)
and the list of every file in `dist/web/browser` into `dist/web/browser/sw.js`, then runs `node --check` on the
result. So:

- **What is cached:** exactly this build's files — `index.html`, the entry bundles, every lazy route chunk, the
  MapLibre worker (`maplibre/*.mjs`), the India boundary data (`geo/in-boundaries.geojson`, 413 KB), the manifest,
  the icons — downloaded on `install`, in one cache named
  `doorprints-shell-<build id><base path>`. Installing fails rather than half-succeeds if any file cannot be
  fetched. **API responses are never put in Cache Storage** (the data is in IndexedDB, under the app's control),
  map tiles are not cached (OpenFreeMap fair use), and nothing cross-origin is touched.
- **What works offline after one online visit:** every screen and the whole app shell, including routes you have
  never opened, because the precache holds every chunk (the worker registers 3 s after start and fetches the
  build itself; the files the page had already loaded are not lost). What needs a network: the map background
  (tiles), "Fill address from map" (Nominatim), the AI features and server sync. Your data in IndexedDB is there
  regardless. Offline, the map region says so instead of staying blank grey (the list still works, and add mode
  offers "Add at my location" and typed coordinates), and the map style is requested again on the `online` event.
- **Starting the app:** navigations to any route are answered with this build's `index.html` **from the cache
  first**, so a weak signal (a basement flat, a site visit) never means a blank page while the network makes up
  its mind. A navigation to one of the build's own files (the manifest, an icon) gets that file; any other path
  with a file extension goes to the network. `navigationPlan` in `scripts/sw-precache-core.mjs` is the tested
  copy of that rule.
- **Updates:** each build's `sw.js` differs, so the browser finds the new worker on the next visit and the
  "new version — reload" banner appears; nothing swaps under the user's hands. Reload asks first when a house
  has unsaved edits ("Save first" / "Reload"), and the house page also warns on tab close or browser reload. When it takes over, the previous
  build's cache is deleted, so Cache Storage holds one build (a few MB) and does not grow with every deploy — it
  shares the origin's quota with your photos. Unhashed files (the MapLibre worker, the manifest, icons) are
  always served from the same build as the bundle that asks for them.
- **Development:** `ng serve` serves the unstamped template, which caches nothing and intercepts nothing. A bare
  `ng build` also leaves it unstamped (no offline start, no update banner), so always build with `npm run build`.

`share_target` points at `/share`, which for now shows the shared
text and offers to create a house from it — via the map, where the user places the pin first (a new house is
never saved at 0°, 0°); parsing listings is Sprint 4b.

**Install** lives on **Your data** ("Install the app": the browser's install button where Chromium offers one,
the Add-to-Home-Screen steps on iOS). The app also offers it once as a banner, only after the first saved house;
"Not now" there is remembered for 30 days (`hh.installDismissedAt` in localStorage, which "Remove all data" clears). At most one non-error banner
shows at a time (migration, then update, then install, then storage advice).

The postbuild step also writes the manifest's `id` as the **absolute base path** read from the built
`index.html` (`/` on Firebase Hosting), because an `id` resolves against the origin, not the manifest URL; it is
the same identity `start_url` already gave installed copies. The splash `background_color` is the brand teal so
it matches the icon in light and dark mode. `screenshots` (for Chromium's richer install sheet) are
`public/screenshots/map-narrow.png` (1080×1920) and `house-wide.png` (1920×1080): **drawn mock-ups of the app's
own layout** (their header shows the brand mark of `favicon.svg`, as the app's header does), not captures of a running build (nothing here or in CI can run a browser yet); replace them with
real captures when a device is at hand. They are left out of the precache (`EXCLUDED_DIRS`), since only the
install UI requests them. Each shortcut has its own 96×96 icon (the map and data glyphs of the bottom bar, white on
teal) instead of reusing the app icon.

**The app icon is the brand mark**, the same as Android's launcher icon
(`android/app/src/main/res/drawable/ic_launcher.xml`): a white arched front door with its doorstep and three small gold
(#F2B84B) footprints walking up beside it, left, right, left (each a sole, a heel and four toes; option C of
[docs/14](../docs/14-lead-backlog-and-handoff.md) N3, 2026-09-24), on the brand teal #1F6F5C (the manifest's `theme_color` and
`background_color`, and the light `theme-color` meta). `icon-192.png` and `icon-512.png` are rounded squares with
transparent corners; `icon-maskable-512.png` is full bleed with the whole mark inside the 80 % safe circle;
`apple-touch-icon.png` (180×180) is full bleed with no transparency, because iOS rounds the corners itself. The PNGs
were rendered from SVG with headless Chromium. `favicon.svg` is the same mark with the same three prints, drawn a
little larger in its rounded square (the owner chose one mark everywhere, 2026-09-24: at 16×16 the prints are small
gold marks beside the door, and the toes do not show). `icons/favicon-32.png` is that
same `favicon.svg` rendered at 32×32 by headless Chromium (transparent corners), and `index.html` lists it first,
with `sizes="32x32"`, then the 192 px PNG, then the SVG: a browser that picks a bitmap tab icon by size takes the
32 px one for a 16 px (1x) or 32 px (2x) tab instead of shrinking the 192 px icon. It is in the
build, so the precache (and the offline tab) has it too.

On iPhone and iPad, Safari can evict a non-installed site's storage when it has had no user interaction in the
last seven days of browser use (docs/05 §14.5), so the
app shows "Add to Home Screen" help and recommends saving a backup regularly (NFR-027). The installed app uses
`black-translucent` for the status bar with `viewport-fit=cover`, and the header and the bottom navigation are
padded by `env(safe-area-inset-*)`, so nothing sits under the clock, notch or home indicator.

**PDF on phones:** printing from a hidden frame has historically printed the wrong page, or nothing, in iOS Safari
and in Home Screen apps, and Chromium on Android has the same long-standing report (issues.chromium.org 41222716).
So on iOS **and Android** the PDF choice opens the HTML copy in a new tab (opened inside the tap, before the file
is built) with a platform hint: "Share → Print → Save to Files" on iOS, "⋮ → Share → Print → Save as PDF" on
Android. Desktop browsers keep the hidden print frame. **Unverified on a device** from this sandbox; check iOS
18/26 in Safari and standalone, and Android Chrome in the browser and as an installed app.

### Where this deviates from docs/11 §5.10 (open with Docs)

[docs/11 §5.10](../docs/11-feature-parity-and-export-spec.md) names a specific toolchain that this implementation
deliberately does not use, and its own Headers row still names `/ngsw.json` and `/ngsw-worker.js` — files this
build never produces. **An ADR line recording these four choices has been asked of the Docs team**; until it
exists, they are written down here so nobody reads the spec as describing the shipped app:

| The spec says | This app does | Why |
|---|---|---|
| `ng add @angular/pwa` | a hand-written `public/sw.js`, stamped per build by `scripts/sw-precache.mjs` | `@angular/service-worker` is a new runtime dependency and a regenerated lock file for about a hundred lines of caching code that is worth reading in full; the zero-cost and small-dependency constraints. The postbuild stamp does the one thing `ngsw.json` would have: a per-build file list and version. |
| Dexie or `idb` | `src/app/data/local-db.ts` over raw IndexedDB | one small wrapper with its own `MemoryDb` fallback, which is also what makes the store testable under jsdom. |
| `fflate` for the ZIP | `src/app/export/zip.ts` (stored entries, own CRC-32) | the backup is deterministic and never compressed, so there is nothing for a deflate library to do. |
| `/ngsw.json`, `/ngsw-worker.js` in the Headers row | `/sw.js` and `/manifest.webmanifest` | consequence of the first row: those two paths do not exist in `dist/`, and `web/firebase.json` covers the ones that do. |

## Optional AI features

When the server runs with `APP_AI_ENABLED=true` (see [docs/ai/ai-design.md](../docs/ai/ai-design.md)), the app
shows **Ask** (questions about your saved houses, with linked sources), **Plan visits** (an ordered walking route
on a map) and **Fill in from listing text** on the new-house form (i18n keys `listingFill.*`; it fills in the form
from a pasted ad and saves nothing — "import" is reserved for Doorprints backups). With AI off (the default) these
are hidden.

## Languages and accessibility

The UI is available in English, हिन्दी, தமிழ் and తెలుగు (switcher in the header; the choice is kept in
localStorage). Strings live in `src/app/i18n/` — `en.ts` is the source of truth and the build fails if another
language misses a key. The app targets WCAG 2.2 AA; see
[docs/05-ux-accessibility-i18n.md](../docs/05-ux-accessibility-i18n.md) for tokens, the checklist and how to add
a string or a language.

## Build

```bash
npm run build         # output: dist/web/browser (then the postbuild step stamps sw.js and adds the CSP <meta>)
```

Always build through `npm run build` (extra flags go after `--`, e.g. `npm run build -- --base-href=/some/path/`):
npm runs the `postbuild` script only for `npm run build`, and without it the service worker is not stamped and
`index.html` has no CSP `<meta>`. This is the build CI deploys. The build reads `web/firebase.json` for the policy
and fails if it has none. It creates no `404.html`; see "Deploy". (The former `build:pages` script, the same
build with `--base-href=/`, was removed: `src/index.html` already has `<base href="/">`, so it produced identical
output under a name that belonged to a host this app does not use.)

## Test

Unit tests use Angular's built-in `@angular/build:unit-test` builder with Vitest and jsdom (no browser needed).
Spec files sit next to the code (`*.spec.ts`) and cover the score calculation, rupee/number formatting per
language, completeness of the translation dictionaries (same keys and `{placeholders}` as `en.ts`), the API
interceptor (only `/api` URLs get the base URL and key), where the API config is stored, the frame refusal
(`src/app/core/frame-guard.spec.ts`: the app does not start inside a frame and shows the message in all four
languages), the IndexedDB
repository, the sync conflict rules (the same cases as the Kotlin `SyncRulesTest`), the sync engine against
recorded backend payloads, the exporters against checked-in golden files, and the service-worker stamp
(`src/app/core/sw-precache.spec.ts`: precache list, build id stability, all-or-nothing stamping, the CSP read
from `web/firebase.json`, and `isAcceptable`, the rule for what the worker may precache), the house page's Back and
where it goes after Delete or Discard (`back-target.spec.ts`), the router restoring history on a cancelled Back
(`app.config.spec.ts`), which navigation item is current (`nav-section.spec.ts`), what "Remove all data" clears
from the tab's sessionStorage and from this origin's localStorage (`session-leftovers.spec.ts`: every `hh.*` and
`doorprints.*` key in both), that it unregisters
only this deployment's service worker (`pwa.service.spec.ts`, *unregisterOwnWorker*), and that a location answer
arriving after its page was left is dropped (`shared/locate-once.spec.ts`: Map's *Add at my location*, Plan's and the
house form's *Use my location*), which start field Plan's *Plan route* focuses when the start cannot be used, and
that a start field turned invalid or empty drops the coordinate kept for it (`pages/plan/start-field.spec.ts`,
*nextTypedStart*), and that a stored map view still equal to the starting view over India is not read as a place
the user chose (`shared/map-center.spec.ts`).

Golden files live in `src/app/export/golden/`. They are generated from `golden/fixture.ts` and checked in; a
format change shows up as a diff there and has to be deliberate. Never edit a golden file to make a test pass.

```bash
npm test            # watch mode while developing
npm run test:ci     # single headless run (ng test --watch=false), used by CI
```

## Deploy (free static hosting)

**Live host: Firebase Hosting, at `https://doorprints.web.app`** (Firebase project `doorprints`, site `doorprints`,
no-cost Spark plan with no billing account; owner decision of 2026-09-23). It replaces the Cloudflare Pages plan,
which was never deployed, and GitHub Pages. The site is served from the **root of its own origin**. Firebase also
serves the same site at `https://doorprints.firebaseapp.com`: never share that address. It is a different origin,
so houses saved there live in a different browser store (see "Moving to another address" below).

- **Configuration: `web/firebase.json`**, next to `package.json` and outside `public/`, so it is never part of the
  build output. The Web team owns it; DevSecOps reviews every change to it and enforces the rules below in CI
  before any deploy credential exists. **Keep all of these:**
  - one `hosting` object and no other top-level key;
  - `"public": "dist/web/browser"`, never the workspace root;
  - `"site"` equal to the repository variable `FIREBASE_SITE_ID` (`doorprints`);
  - no `predeploy` or `postdeploy` hook (Firebase runs them as shell commands next to the credential);
  - the only rewrite is to `/index.html`: no `function`, `run` or `dynamicLinks` rewrites.

  There is no `.firebaserc`: the project and site IDs come from repository variables.
- **Build and deploy.** CI builds with `npm run build` (output `dist/web/browser`, plain static files). The
  `deploy-firebase` job of `.github/workflows/web.yml` (DevSecOps) then deploys that artifact with a pinned
  `firebase-tools` and a short-lived Workload Identity token that only `web.yml` on `main` can get. Before deploying,
  it checks that the base href is `/` and that there is no `404.html`. After deploying, it checks the live
  headers. There is no manual deploy step. Rolling back is done in the Firebase console, under Hosting > release
  history, without a build.
- **SPA fallback: exactly one mechanism, the `**` rewrite to `/index.html`.** Firebase serves an existing file
  first and rewrites only paths that are not files, with **status 200**. So `/sw.js` and the chunks are served as
  themselves, and a deep link such as `/houses/42` gets the app shell. The build ships no `404.html`: nothing
  needs one, and the service worker leaves `*.html` navigations to the network.
- **Other static hosts** work with the same build, as long as they provide an SPA fallback and send the headers
  from `firebase.json`, translated into that host's own format. Under a sub-path, build with
  `npm run build -- --base-href=/that/path/`.

**Everything PWA follows the base href.** `manifest.webmanifest` uses relative URLs (`"scope": "./"`,
`"start_url": "./"`, `"icons"` `./icons/…`), which resolve against the manifest URL. `PwaService` registers the
worker with `new URL('sw.js', new URL('./', document.baseURI))` and claims that directory as its scope. `sw.js`
derives its app-shell path and its API bypass from `self.registration.scope`. On `doorprints.web.app` all of this
is simply `/`: worker `/sw.js`, scope `/`, manifest `id` `/`. Nothing is hardcoded to `/`, though, so the same build
also works under a sub-path. There, an absolute `/` scope would make `register()` throw `SecurityError`, and the
app would have no offline start and no install offer; `pwa.service.spec.ts` pins this.

**Security headers (RR-11).** The `**` header rule in `firebase.json` matches the **request path**, so it covers
every file and every deep link. It sends:
- the Content-Security-Policy, including `frame-ancestors 'none'`;
- HSTS;
- `X-Content-Type-Options: nosniff` and `X-Frame-Options: DENY`;
- `Referrer-Policy`, `Permissions-Policy` and `Cross-Origin-Opener-Policy`;
- `Cache-Control: no-cache`.

A second rule gives `/manifest.webmanifest` its `Content-Type`.

**No two rules set the same header**, because Firebase does not document which value wins when rules overlap
(its local server, superstatic, applies matches in order, so the last one wins). Two things are outside this
file's reach:
- **HSTS** on `*.web.app` may be replaced by Firebase's own value. The whole `.app` domain is HSTS-preloaded in
  browsers anyway.
- Firebase's **reserved `/__/*` paths** are not under these headers. No Firebase Web App is registered in the
  project, so `/__/firebase/init.js` exposes no configuration.

**The Content-Security-Policy has one source: the `**` rule in `web/firebase.json`.** Edit it there and nowhere
else. `npm run build` copies it into `index.html` as a `<meta>` (see "Defence in depth" below). The build fails
when the policy is missing, is set twice, or is set by any rule other than `**`: `cspFromFirebaseConfig` in
`scripts/sw-precache-core.mjs`, tested in `sw-precache.spec.ts`. Why each directive is there:

- `script-src 'self'`: the production build has no inline scripts. `angular.json` sets `inlineCritical: false`,
  which would otherwise add an inline `onload` handler.
- `style-src 'unsafe-inline'`: Angular injects component styles as `<style>` elements at runtime.
- `connect-src https:`: the user chooses the API address at runtime, so any HTTPS origin is allowed. The map tiles
  (OpenFreeMap) and Nominatim are HTTPS too.
- `connect-src http://localhost:* http://127.0.0.1:*`: a Doorprints server on the user's **own** machine. This is
  also the default server address (`http://localhost:8080`, the docker-compose setup) and the local API during
  development. It opens only the user's own loopback interface, `https:` is already wider, and `script-src 'self'`
  leaves no injected script to use it. Two browser behaviours to expect:
  - Chrome's Local Network Access check asks the user for permission first.
  - Safari may block `http://localhost` from an HTTPS page outright. Then run the server behind HTTPS, or use the
    app from `http://localhost` too.
- `worker-src 'self'`: MapLibre GL 6 loads its module worker from `/maplibre/maplibre-gl-worker.mjs` (copied by
  `angular.json`, set with `setWorkerUrl`), so no `blob:` worker is allowed.
- `frame-src 'self' blob:` and `img-src … blob:`: the PDF export prints the HTML copy from an
  `<iframe src="blob:…">` (`export/export.service.ts`, `printPdf`). `default-src 'self'` does not cover a `blob:`
  frame. Without this directive the frame is blocked, and the export would hang if `printPdf` had no timeout. If a
  future change drops the iframe, drop this directive too.

**No long-lived caching, not even for hashed files.** `Cache-Control: no-cache` is on `**` for three reasons:
- Firebase's default for a static file is `max-age=3600`.
- An `/index.html` rule would not match `/` or a deep link such as `/houses/42`. Those would keep the shell cached
  for an hour, and a new build's shell could then meet deleted chunks.
- `no-cache` still allows ETag revalidation, so a repeat visit costs a 304 per file. After the first visit, the
  service worker serves the whole build from its precache anyway.

**`main-*.js`, `chunk-*.js`, `polyfills-*.js`, `styles-*.css`, `media/*` and `icons/*` must not get an
`immutable` rule.** Here is why:
1. The `**` rewrite answers a file that no longer exists with the HTML shell and **status 200**. For example, a
   tab left open over a deploy asks for a previous build's `/chunk-OLDHASH.js`.
2. A year-long rule for `/chunk-*.js` would also cover that HTML answer, and the browser would keep the HTML under
   the chunk's name for a year.
3. Hashed names are hashes of their content, so the same name comes back after a rollback or a revert. For that
   user, the lazy route then breaks (HTML as a module script, under `nosniff`), and every service-worker install
   fails.

If long caching is ever wanted, first narrow the rewrite so that asset-shaped paths get a 404 instead of the
shell. Only then add the rules, together with a CI check that a missing chunk returns 404. As a second line of
defence, `sw.js` re-downloads a content-hashed file with `cache: 'reload'` when the HTTP cache hands it HTML, and
it never stores an HTML answer under a non-HTML name (`isAcceptable`, tested through its twin in
`scripts/sw-precache-core.mjs`).

**Free-plan limits (Spark).**
- Storage is 10 GB. Old releases count towards it, so the owner set the console to keep 10 releases.
- Transfer is 360 MB a day on the pricing page, or 10 GB a month on the quota page; plan for the stricter
  figure. When the limit is exceeded, Firebase disables the site until the period resets.
- The precache makes repeat visits cheap. The whole build is downloaded once per new build, not on every visit.

**Defence in depth, for a host or copy that ignores `firebase.json`** (a copy of the build on a plain static
server, `npx serve dist/web/browser`, or a proxy that strips headers):

- **CSP `<meta>`.** The postbuild step writes the policy from `firebase.json` into `index.html` as a
  `<meta http-equiv>`, without `frame-ancestors`, which a `<meta>` cannot carry. The live host sends the header
  too, and the two are identical apart from `frame-ancestors`, so nothing is loosened. Where the header does not
  arrive, the page still has its CSP.
- **Frame refusal.** A page cannot set `frame-ancestors` for itself, but it can decline to start. If
  `window.top !== window.self`, `main.ts` does not bootstrap Angular at all. It shows a short message in the
  user's language (en/hi/ta/te), with a link that opens the app in a new tab and the address to copy, for frames
  whose `sandbox` blocks new tabs (`src/app/core/frame-guard.ts`). On `doorprints.web.app` the headers already
  stop the frame from loading, so users never see this message there.

**Why not GitHub Pages or Cloudflare Pages.**
- **GitHub Pages** cannot send response headers: no frame protection, and no nosniff, Permissions-Policy or COOP
  (RR-11). Also, every Pages site of an owner shares the origin `https://<owner>.github.io`. Browser storage is
  per origin, not per path, so IndexedDB (houses and photos) and localStorage (the server address and API key,
  when "Remember on this device" is ticked) would be readable by a script in any of the owner's other project
  sites.
- **Cloudflare Pages** was chosen on 2026-09-23 and replaced the same day, before any deploy, for its address.
  A `pages.dev` name reads as a developer site, and the project name was not claimed.
- **Nothing was ever published** at a `github.io` or `pages.dev` address from this repository, so no user has
  data there to move.

**Moving to another address (for example a custom domain later).** IndexedDB and localStorage belong to one
origin, and a browser never moves them to another. The web app cannot read a backup back in yet: **web import
arrives in Sprint 4b** (UX-B07). Until then, the only way to carry houses from one address to another in the
browser is **through a connected Doorprints server**:
1. On the old address, connect your server under **Connect** and let it sync.
2. On the new address, connect the same server and choose **Download your houses to this browser**.

A backup downloaded on the old address can already be imported by the Android app. The live address will not
move before web import ships.

Remember to add `https://doorprints.web.app` to the API's `APP_CORS_ORIGINS`, and to use an HTTPS API URL:
browsers block calls from an HTTPS page to an HTTP API.

Browser storage keys keep the old `house-hunt.` prefix (`house-hunt.lang`, `house-hunt.api-config`) so
settings saved before the rename to Doorprints are not lost.

Address lookup ("Fill address from map") uses the public OpenStreetMap Nominatim service and is only
called when you press the button, in line with its usage policy.

## Storage audit on the live site

This is **TC-S-19 on `https://doorprints.web.app`**: an exit check of the first web deploy in docs/06 §11
(*First web deploy*; docs/10 §12.5 Decision 1). Run it right after the deploy, **before the address is shared**. It
checks what the live site keeps in the browser after a session with test data, and that **Remove all data** leaves
none of it. It takes about 10 minutes in desktop Chrome. Other browsers keep the same data, but their developer tools
look different.

**Any result that differs from *Expected* is a finding.** Roll back through Firebase Hosting's release history
(docs/07 §6.3) before the address is shared, and send the difference to Web and DevSecOps.

### What the app may keep in the browser

Anything that is not in this table is a finding.

| Where (DevTools → Application) | Name | What it holds | When it is there |
|---|---|---|---|
| IndexedDB | database `doorprints`, stores `houses`, `visits`, `photos`, `settings` | Houses, visits, photos (each photo record holds the image as a Blob), and app settings: `storage.persist-asked`, `sync.server` (the server address, never the key), `cursor.house`, `cursor.visit`, `cursor.photo`, `migration.state`, `export.options` | The database is created on the first start, with 4 empty stores. The settings appear as they are used |
| Cache Storage | **one** cache, `doorprints-shell-<16 hex characters>/` | Only this build's own files, from `https://doorprints.web.app/` | A few seconds after the first start: the worker registers 3 s after start and then downloads the whole build |
| Local storage (`https://doorprints.web.app`) | `house-hunt.lang` | The chosen language (`en`, `hi`, `ta` or `te`) | After the language was changed in the header |
| | `hh.mapView` | The last map position, `{"lat":…,"lon":…,"zoom":…}` | From the first time the map page shows: MapLibre reports its first fit to the page as a move, so the starting view is saved at once. Missing only when the map cannot be drawn (no WebGL 2) or when the browser blocks storage for the site. While it still holds that starting view, the app does not read it as a place the user chose: Plan's start and a new house's pin skip it |
| | `hh.installDismissedAt`, `hh.storageRiskDismissedAt` | The date of a "Not now" on the install offer or the storage advice | Only after that "Not now" |
| | `house-hunt.api-config` | `{"baseUrl":…,"apiKey":…}` | **Only when "Remember on this device" is on** |
| Session storage (`https://doorprints.web.app`) | `house-hunt.api-config` | `{"baseUrl":…,"apiKey":…}` | When a server is connected with "Remember on this device" **off** (the default) |
| | `hh.houseDraft:<house id>` or `hh.houseDraft:new:…` | The unsaved house form, contact included | Only while a house form has unsaved edits. It goes when the house is saved or the page is left |
| | `hh.shareText` | Listing text shared to the app | Only while the Share page holds it |
| Cookies | none | | Never |
| Service workers | `https://doorprints.web.app/`, source `sw.js` | The app's worker. It holds no data itself, and its only store is the cache above | From about 3 s after the first start |

DevTools does not refresh these views by itself. Before reading a view, refresh it: right-click **IndexedDB** and
choose **Refresh IndexedDB** (or use the refresh button above a store's entries), right-click **Cache storage** and
choose **Refresh Caches**, and click **Local storage** or **Session storage** again. In **Service workers**, leave
*Update on reload* and *Bypass for network* off. Delete nothing by hand, except with *Clear site data* in pass 1
step 1.

### Pass 1: a normal window (about 7 minutes)

1. **Start clean.** In Chrome, open `https://doorprints.web.app`, press F12 (Cmd+Option+I on a Mac) and open
   **Application**. Under **Storage**, keep every box ticked, press **Clear site data**, and reload the page.
   This is the browser's own button. It is used only so that the audit starts from nothing. Wait until the map
   shows and **Service workers** says *activated and is running*. On a slow connection this takes a few seconds
   more, because the worker downloads the whole build first.
   **Expected:**
   - **Service workers:** one registration for `https://doorprints.web.app/`, source `sw.js`, status *activated and
     is running*.
   - **Cache storage:** exactly one cache, named `doorprints-shell-` + 16 lowercase hex characters + `/` (for example
     `doorprints-shell-3f9c0a1b2d4e5f60/`). Write the name down: it is this build's id. Every entry is a
     `https://doorprints.web.app/…` file of the build: `index.html`, `main-….js`, `polyfills-….js`,
     `styles-….css`, `chunk-….js`, `maplibre/maplibre-gl-worker.mjs`, `maplibre/maplibre-gl-shared.mjs`,
     `manifest.webmanifest`, `favicon.svg` and `icons/….png`, plus `media/…` if the build has any. There is no other
     host (no map tiles, fonts, Nominatim or server), no `/api/` and no `screenshots/`.
   - **IndexedDB:** one database, `doorprints`, with the stores `houses`, `visits`, `photos` and `settings`, all empty.
   - **Local storage:** only `hh.mapView`, the starting view over India, about
     `{"lat":20.5937,"lon":78.9629,"zoom":4}`. The map page writes it as soon as it shows (see the table). Nothing
     else.
   - **Session storage:** empty. **Cookies:** none.
2. **Add test data.** This takes about 3 minutes. Use made-up details only.
   1. On the map, choose **Add house**, then a spot on the map (or **Place here**). Fill in **Name** `Audit house 1`,
      **Contact name** `Audit Contact`, **Contact phone** `+91 90000 00000`, **Notes** `audit`, and choose **Save**.
      Then choose **Add photos** and pick two small pictures.
   2. Add a second house, `Audit house 2`, with nothing else, and save it.
   3. Open `Audit house 1`, add a word to **Notes**, and wait one second **without saving**.
      **Expected:** **Session storage** now has `hh.houseDraft:<id of house 1>`, holding the unsaved form (with the
      contact). Choose **Save**. **Expected:** that key is gone.
   4. Switch the language in the header to தமிழ் and back to English, and move the map a little.
   5. Open `https://doorprints.web.app/connect`. Enter **API address (URL)** `https://doorprints.web.app` and **API key**
      `audit-not-a-real-key-0000000000000000`, and leave **Remember on this device** off. Choose **Save and
      continue**. The check fails, because the site itself has no API (so the made-up key goes nowhere but this
      site). Then choose **Save anyway**. The header then shows a sync problem, which is expected.
3. **Check the session.**
   **Expected:**
   - **IndexedDB** `doorprints`: `houses` has 2 entries. `photos` has 2 entries, each with its house id and the
     image as a Blob. `visits` has 0 entries. `settings` has `sync.server` = `https://doorprints.web.app`, and
     `storage.persist-asked` = `yes` unless Chrome had already granted this site persistent storage. It may also
     have `cursor.*` keys, and `export.options` if you changed options on Your data. It has nothing else, and **no
     API key** anywhere.
   - **Cache storage:** the same single cache as in step 1, still only build files. There are no photos, houses or
     `/api/` answers in it.
   - **Local storage:** `house-hunt.lang` = `en` and `hh.mapView`. `hh.installDismissedAt` or
     `hh.storageRiskDismissedAt` appear only if you pressed "Not now" on those notices. There is **no
     `house-hunt.api-config`**, because "Remember on this device" was off.
   - **Session storage:** `house-hunt.api-config` =
     `{"baseUrl":"https://doorprints.web.app","apiKey":"audit-not-a-real-key-0000000000000000"}`. The key is here and
     nowhere else. No `hh.houseDraft:*` key remains.
   - The contact's name and phone number appear only inside the `houses` record, never in local storage, in the
     cache or in the address bar.

   Then open `/connect` again, turn **Remember on this device** on, choose **Save and continue**, and then **Save
   anyway**. **Expected:** `house-hunt.api-config` has moved to **Local storage** and is gone from **Session storage**.
4. **Remove all data.** Open **Your data**, go to *Remove data from this browser*, and choose **Remove all data**.
   With the unsent test houses, the question is "Changes that have not reached your server yet: N. They will be
   lost. Remove all data from this browser anyway?". Choose **Remove all data** (not *Sync first*). Wait for "All
   data was removed from this browser". Then, **still on Your data**, refresh each view.
   **Expected:**
   - **IndexedDB:** the `doorprints` database is still listed, and its 4 stores are **empty** (0 entries each). The
     database itself stays, and holds nothing.
   - **Cache storage:** **no** `doorprints-shell-…` cache. The list is empty.
   - **Local storage:** only `house-hunt.lang`. It is kept on purpose, and Your data says "Your language choice
     stays". There is no `hh.*` key and no `house-hunt.api-config`.
   - **Session storage:** empty.
   - **Service workers:** the registration for `https://doorprints.web.app/` is gone from the list, or is shown as
     deleted. The browser lets the worker go on serving this open tab until the tab is closed.

   Do these checks before you open another screen. Using the app again is normal use, not leftover data: the map
   writes `hh.mapView` again as soon as the map page shows, and the worker that still serves this tab stores the app files
   that a newly opened screen loads.
5. **Close the tab**, and every other `doorprints.web.app` tab. In a new tab, open `chrome://serviceworker-internals`
   and search the page (Ctrl+F or Cmd+F) for `doorprints.web.app`.
   **Expected:** no registration is listed. Opening the site again writes `hh.mapView` again, registers a new worker
   after 3 s, and a new `doorprints-shell-…` cache holds the app's files again. That is the next start of the app, not leftover data.

### Pass 2: a private window (about 3 minutes)

Open a Chrome Incognito window (Ctrl+Shift+N, or Cmd+Shift+N on a Mac). It starts empty, so step 1 needs no
*Clear site data*.

1. Open `https://doorprints.web.app`, open DevTools, and check as in pass 1 step 1. **Expected:** the same result.
2. Add one house with one photo. Connect as in pass 1 step 2.5, with **Remember on this device** off. Check as in
   pass 1 step 3. **Expected:** the same result, with 1 house and 1 photo, and no `house-hunt.lang`, because the
   language was not changed in this window. Your data may say that the browser has not promised to keep the data.
   Incognito does not grant that promise, so this is expected.
3. **Remove all data**, and check as in pass 1 step 4. **Expected:** the same result, except that local storage is
   now empty (no language was chosen in this window).
4. Close **every** Incognito window. Open a new Incognito window, open the site, and check before you do anything
   else. **Expected:** the pass 1 step 1 result: a new `doorprints` database with 4 empty stores, local storage
   holding only `hh.mapView` (the starting view over India), empty session storage, and after a few seconds one new
   `doorprints-shell-…` cache. Nothing from the earlier Incognito
   session is left.

**Record** the date, the Chrome version (from `chrome://version`), the cache name from pass 1 step 1, and for each
step either ✓ or what differed. Send the record to Web and DevSecOps. It is the live evidence for TC-S-19, and Docs
records it in docs/06.

## Deferred to Sprint 4b

### From the coordinator's final review (2026-09-23)

The web tickets of the pre-deploy close-out. docs/10 §12.7 is the one register of backlog ids: this table never
makes up an id of its own. S4b-BL-1, -2, -3 and -5 are the ids docs/10 §12.7 already gives these tickets (S4b-BL-4
there is an Android ticket, the map attribution hidden behind snackbars, so it is not in this table). S4b-BL-6 and
S4b-BL-7 are **new**: they are the next free numbers after S4b-BL-5, the last id docs/10 §12.7 held on 2026-09-23,
and are handed to Docs to add there (change log, round 3 row).

| Ticket | What is wrong | Planned fix | State |
|---|---|---|---|
| S4b-BL-1 | **`sync.service.ts` `lastError`** (raised three times). `runOnce()` sets `lastError` to null at the start of every run. So during *Sync now* or *Try again*, the forced failure card and the first-run banner disappear and the content below jumps. The polite "Automatic sync failed" note is removed and read out again on every failed background pass (every 30 min, and 3 s after an edit). `failureRun` also goes up on runs the user did not ask for | Clear `lastError` only on success. Bump `failureRun` only when the run is forced or the message changed. While `sync.running()`, render the card in `.refresh-slot.stale` with a "Syncing…" refresh bar | **Deferred to Sprint 4b** |
| S4b-BL-2 | **House page retry cards** (raised twice). `persist()`, `useMyLocation()`, the listing fill and the address lookup clear their card before the run, so the form jumps on a retry | Keep the `RunResult` card, dimmed with `.refresh-slot.stale`, while saving, filling, geocoding or locating runs, and replace it when the run ends. Docs then records the scope in docs/05 §5 and §5.1 | **Deferred to Sprint 4b** |
| W2 (docs/10 §11.7) | **Plan's submit focuses the start latitude** when the latitude is typed and the longitude is empty (rounds 3 and 4) | *Plan route* focuses the first start field still to fix, in page order: an invalid one, or while no start is set an empty one (`start-lon` in that case). `pages/plan/start-field.ts` (`startFieldToFix`, with `start-field.spec.ts`). Round 1 review: a coordinate kept while the other one is typed is dropped when its field turns invalid or empty (`nextTypedStart`), so the start is never set from, and the field never refilled with, a value the user removed | **Done** (2026-09-23), before a third carry: under playbook §4.4 it would have become a major |
| S4b-BL-5 (rule (f) candidate) | **Map's *Download now* from the empty list** (`downloadHere()`, `map-page.ts`) announces "N houses downloaded" and moves focus when the download ends, even if the page was left meanwhile. Found while applying the coordinator's candidate rule (f): an async callback that can navigate, announce or change a map first checks that its page still exists. Nothing is navigated or filled: the announcement is heard on another page, and the focus calls find no element | Return early from the part after `await done` when the page is gone (the same `destroyed` flag), and again after the houses are counted | **Done** (2026-09-23) |
| S4b-BL-3 | **Favicon at 16 px.** Chromium may shrink the 192 px PNG (the version with toes) to 16 px instead of using the simplified SVG | `icons/favicon-32.png`, rendered from `favicon.svg`, is listed first with `sizes="32x32"` (see *Installable (PWA)*) | **Done** in this round (2026-09-23) |
| §12.7 "Web, before the first deploy" | **Map's *Add at my location* and Plan's *Use my location*** acted on a position that arrived after the page was left. The map opened a new-house form the user had not asked for | The `destroyed` guard of the house form, now in one tested helper, `shared/locate-once.ts`, used by all three pages | **Done** in this round (2026-09-23) |
| S4b-BL-6 (new) | **No visible invalid state** (round 2 design review). Fields marked `aria-invalid="true"` (Plan start fields, house name and coordinates, Ask and Plan requests, Connect) look the same as valid ones; only the message under them shows the error | One rule in `styles.css` for `input`/`textarea` with `[aria-invalid="true"]`: border in `--error-text` (at least 3:1 against the page in both themes, WCAG 1.4.11), checked with the docs/05 §4 contrast table | **Deferred to Sprint 4b** |
| S4b-BL-7 (new) | **No TestBed spec for the Plan page or the new-house start** (round 2 and round 3 reviews). The typed-start and start-message rules are tested through `start-field.spec.ts` and `map-center.spec.ts`, but the Plan page wiring (`onCoord`, `setStart`, `fieldInvalid`, `coordsDescribedBy`) is checked by reading only. The house form's two rule (f) guards in `startWithoutPosition()` (after the houses are read, and before the country-view fallback) are also checked by reading only | A `plan-page.spec.ts` with TestBed: typed start, invalid then cleared field, a location failure withdrawn by a map click, and the `aria-describedby`/`aria-invalid` attributes. A `house-detail-page.spec.ts` with TestBed: `/houses/new` with no position, the page destroyed before `houses()` resolves (and, second case, before it rejects), then no draft opened, no *Draft restored* announcement and no document title set | **Deferred to Sprint 4b** |

### Android parity on "Your data"

This handover from `android/shared/README.md` §9 is **not implemented** on `/data` yet, and is planned for
Sprint 4b:

| Handover | What Android does | What `/data` does today |
|---|---|---|
| 11(a) | Hides *Include rejected houses* unless the scope is *All houses*. | Shows it for *Shortlisted only* too, where it has no effect. The planned fix wraps the field in `@if (options.scope === 'all')` and keeps the stored value, as the Photos field does. |

Implemented since this list was written: **13**, the empty-state button is labelled `data.emptyAction` ("Add a house
on the map", in all four languages); and **17(a)**, a partial *Full backup* shows an amber note naming each gap
(`partialNote`, from `backupGaps`) with a *Use everything* button, before the backup is made.

The importer items of the same handovers (11(b), 13's duplicate count, and 17(c) with the 1.14 relink rules)
belong to the web import, which also arrives in Sprint 4b.

## Change log

| Date       | Change                                                                  |
|------------|-------------------------------------------------------------------------|
| 2026-09-22 | Added Vitest unit tests (`npm test`, `npm run test:ci`) and this log.   |
| 2026-09-22 | Renamed the product to Doorprints (tagline "Remember every house you've seen."): title, meta description, favicon, header, page titles, all four dictionaries (new `app.tagline` key), package name `doorprints-web`. Storage keys unchanged. |
| 2026-09-22 | **Sprint 4a.** S4-01 local-first: IndexedDB repository (`src/app/data/`), TypeScript sync engine against the optional API-key server (same DTOs and last-write-wins rule as Android), "download your houses to this browser" first-run offer, `navigator.storage.persist()` with a clear warning when it is refused, and a readable message when a browser blocks storage entirely. S4-03: the six deterministic exporters plus options, download and Web Share, with golden-file tests. S4-05: manifest, icons, service worker, install and update banners, iOS "Add to Home Screen" help, `/share` share-target stub, `_headers` additions. The JSON backup, the ranking order and the ₹/decimal arithmetic match `android/shared/.../export/` field for field so a backup round-trips between the two apps. New "Your data" page; routes are no longer guarded by the API config; 123 new strings in all four languages. |
| 2026-09-22 | **Sprint 4a review fixes.** PWA now works where it is deployed: relative manifest paths, `PwaService` registering `sw.js` from `document.baseURI` with that directory as its scope, and `sw.js` deriving its shell and `api/` bypass from `self.registration.scope` (`pwa.service.spec.ts`). `_headers`: `immutable` limited to the hashed bundle names so `/sw.js` is no longer served `immutable, no-cache`, and `frame-src 'self' blob:` added for the PDF print frame. `printPdf` now times out and rejects with a translated `error.printBlocked` instead of hanging for ever. `LocalStore.revision` is consumed: Map, Compare and "Your data" re-read after any write, so a sync pull shows up without a page reload. New `sync.service.spec.ts` (26 cases) built on the recorded backend payloads from `android/shared/.../api/RecordedResponses.kt`; cursors ignore a non-finite `syncVersion` and rows with no usable id are skipped and reported rather than stored as `"undefined"`. Exports: CSV and XLSX now read their rows, columns and **headings** from a TypeScript port of the shared `ExportRows`/`ExportStrings` contract, so the export-language option works for all six formats; backups refuse above 250 MB of photos instead of killing the tab, and no longer embed every photo a second time as base64. `clearEverything()` also empties Cache Storage, and "Your data" forgets the saved server address and key. Markdown escaping now covers leading `#`, `+`, `-` and `1.`. Shared listing text moves to navigation state instead of the URL. |
| 2026-09-22 | **Sprint 4a second review round.** `wireVersion` no longer leans on `Number()`'s coercion of non-primitives (`Number([])` is `0`), so an array or a whitespace-only string in `syncVersion` is refused instead of moving a cursor to 0. A pull now advances the cursor for a row whose *version* is usable even when the row itself is refused, so one permanently broken row on the server no longer re-downloads the same page every 30 minutes and leaves "N rows skipped" on screen for ever. `sync.service.spec.ts` flushes the constructor `effect()` with `TestBed.tick()` in `beforeEach` and then resets the fake's recorders, so the zoneless scheduler cannot interleave a second `start()` with a test. Export goldens regenerated from the new `docs/schemas/backup-sample.json` (three visits, two photos, handover S4-00/b): the fixture's extra visit and photo interleave with house 1's on purpose, and the suite now asserts that the JSON backup groups them by house — 2 144 bytes, byte-identical to the canonical sample — while the CSV/XLSX tables stay globally sorted to match Android's `ExportBundle`. Cache Storage is no longer wiped origin-wide: `sw.js` names its cache per deployment (`doorprints-shell-v1<base path>`) and deletes only its own, and `clearEverything()` filters by `CACHE_NAME_PREFIX`, so a sibling app on `<owner>.github.io` keeps its offline copy. Dropped the manifest's `"id": "./"` — an `id` resolves against `start_url`'s **origin**, so it claimed the shared origin root for every deployment; with it absent the identity defaults to the base-href-relative `start_url`. |
| 2026-09-22 | **Sprint 4a fourth review round (design and UX).** Phones (≤600px) get a Material 3 bottom navigation bar with icons (Connect moves into Your data), a one-row header, and safe-area padding for the installed iPhone app (`viewport-fit=cover`). Install: permanent section on Your data; the banner shows once, after the first saved house, and "Not now" lasts 30 days; one non-error banner at a time; 44px banner buttons on touch. First-run download: progress ("Downloading photos 34 of 212"), Stop (keeps what arrived, resumes from Your data), the failure reason in the banner, and an announcement plus focus move on success. Sync: `cancel()` stops every write of an in-flight run (used by "Remove all data", which now warns about unsent changes and offers "Sync first"), cursors reset when the server address changes (Android parity), unforced runs wait while offline, and background failures are no longer red alerts. Update "Reload" asks before losing unsaved house edits; `beforeunload` on the house page. Share target: empty state, copy feedback, and "Create a house" goes to the map to place the pin; a new house with no position starts at the last map view and cannot be saved until the pin is placed ("Use my location" added). Your data: empty state, plural-free stat chips, segmented options, 44px checkbox rows, warn tokens, export progress with Cancel, Share without downloading first, share errors shown, iOS PDF in a new tab, used space refreshed. Service worker serves navigations cache-first. Manifest `id` stamped per deployment, teal splash. 42 new strings in all four languages. |
| 2026-09-22 | **Sprint 4a third review round.** The service worker is stamped per build: `npm run build` now runs `scripts/sw-precache.mjs` (`postbuild`), which writes a build id (FNV-1a over every output file's path and SHA-256) and the full file list into `dist/.../sw.js`, then `node --check`s it. `install` precaches the whole build (entry bundles, every lazy chunk, the MapLibre worker, manifest, icons) and fails rather than half-installs, so the first offline start after one visit works on GitHub Pages; each build has its own cache and `activate` drops the previous one; unhashed files are served from the same build as the bundle that asks for them; navigations no longer write into the cache, so opening an icon or the manifest in a tab cannot replace the app shell; and a new build now really raises the reload banner. The same step writes `_headers`' CSP into `index.html` as a `<meta>`, so the Pages site has a CSP too. "Not now" on the first-run download now downloads nothing and pauses sync in that browser (it used to start a full pull, photos included, a moment later); **Your data** says so and offers "Download now" (new string `data.syncPaused`, banner text updated, all four languages). The share target removes the shared text from the address bar and history. README: corrected the caching and offline claims, and a plain statement that GitHub Pages sends no security headers and shares its origin's storage with the owner's other project sites. `_headers` documents the loopback `connect-src`. New `sw-precache.spec.ts`; the "not now" tests now assert that nothing was fetched or stored. |
| 2026-09-22 | Meta description now comes from a new `app.description` key (English matches `index.html`) and is updated only on language change, in its own effect; the tagline is no longer used for it. Hindi tagline confirmed as "देखा हुआ हर मकान याद रखें।" (same as Android and the docs/05 glossary). Added `i18n-title.strategy.spec.ts`. |
| 2026-09-22 | **Sprint 4a fifth review round (design and UX).** Bottom bar: one-word labels (`nav.plan` is "Plan" in all four languages), its measured height (`ResizeObserver` → `--nav-h`) reserved by `.content` with `scroll-padding-bottom`, `scroll-padding-top` from the house page's sticky toolbar (`--sticky-top`), items top-aligned (WCAG 2.2 SC 2.4.11). `LocalStore.settled` (revision, debounced 300 ms, at least every 2 s) drives Map, Compare, Your data and the banners, so a sync pull no longer redraws the list, recreates live regions or rebuilds the export bundle once per row; the map shows its skeleton only on the first read and frames the houses on the first **non-empty** load, starting over India instead of the globe. Offline map state with retry and a reload on `online` (sources added on `style.load`), on the map page and the house page. `QuotaExceededError` → `error.storageFull` (translated advice, a Your data link, and the photo download stops on the first refusal keeping its position). "Export a backup" from the storage banner opens Your data with the backup ZIP chosen and focused (`?export=backup`). Android PDF uses the new-tab path. Your data: page header, one filled primary button (Share outlined with an icon), options disabled while building, a `<progress>` bar with quarter announcements, the photo choice only for HTML/PDF/backup (Android parity), a lean first-run empty state, iOS note only on iOS, one sync button that keeps focus. Banners: surface style with a start-edge accent and leading icons, update announced once, focus kept when a banner closes, storage-risk "Not now" remembered for 7 days and the refusal remembered across sessions. Stacked dialog actions under 480px, 44px `.btn-sm` on touch screens, "Discard" asks first when the new house has changes, /share uses the Your data page header. Manifest screenshots and shortcut icons. 8 new strings, `map.checkConnection` removed. |
| 2026-09-22 | **Sprint 4a sixth review round (coordinator rework).** Sync honours the backend rate limit (`app.rate-limit`, 600/min, burst 300, per address): every request of a run goes through `SyncService.call`, which on `429` waits the server's `Retry-After` (or 1 s, 2 s, 4 s without one) when it is at most 15 s and repeats the request, up to 4 attempts — Android `RetryPolicy` parity — so a first-run download of several hundred photos no longer fails part-way. A photo download that still fails keeps the photo cursor at that photo. `errorMsg` maps `429` to the new translated `error.rateLimited` (with the seconds) before the server's English detail, and any other unreadable 4xx (e.g. a Blob body from a photo GET) to `error.httpStatus` instead of Angular's English "Http failure response …"; both keys in en/hi/ta/te. `BACKUP_LIMITS.maxDataJsonBytes` is now 16 MiB, equal to `:shared` `BackupFormat.MAX_DATA_JSON_BYTES` (pinned in `exporters.spec.ts`). `StorageService` no longer claims Safari never grants persistent storage (MDN wording, as docs/02 RR-10, 04 D9, 05 §14.5). |
| 2026-09-22 | **Cloudflare Pages is the live host** (owner decision of 2026-09-23 07:30 IST; replaces GitHub Pages, which cannot send the security headers of RR-11 and shares its origin with the owner's other project sites). Served from the root of its own origin (`https://<project>.pages.dev`, host taken from the deploy output). `build:pages` is now `npm run build -- --base-href=/` and no longer copies `index.html` to `404.html`: Cloudflare's SPA mode (no `404.html` in the output) is the one fallback, and `_redirects`' `/* /index.html 200`, which Cloudflare rejects as an infinite loop, is removed (the file stays, comments only). `_headers` and this README now state that the headers apply; the CSP `<meta>` copy stays as defence in depth, and why. New frame refusal: inside a frame the app does not start and shows a translated "open in its own tab" message with a link and the address (`core/frame-guard.ts`, `frame-guard.spec.ts`, 4 new strings `frame.*` in all four languages). Stale GitHub Pages comments updated in `sw.js`, `scripts/`, `pwa.service`, `config.service`, `local-store.service`; `pwa.service.spec.ts` pins the root-origin registration. Review follow-up: `_headers` no longer has the year-long `immutable` rules for hashed files (`/main-*.js`, `/chunk-*.js`, `/polyfills-*.js`, `/styles-*.css`, `/media/*`) nor the 7-day `/icons/*` rule, because Pages matches header rules by request path and its SPA fallback answers a missing file with the HTML shell and status 200, so a stale tab's request for an old chunk would have cached HTML under that name for a year (workers-sdk `handler.ts`, `generateNotFoundResponse`/`attachHeaders`); the caching comment and README "Deploy" say why. `sw.js` retries a hashed file with `cache: 'reload'` when the HTTP cache returns HTML and never keeps an HTML answer under a non-HTML name. README "Deploy" now says CI deploys the `npm run build` artifact and that `build:pages` produces the same output today. Redundant assertion dropped from the root-origin test in `pwa.service.spec.ts`. |
| 2026-09-22 | **Firebase Hosting is the live host, at `https://doorprints.web.app`** (owner decision of 2026-09-23; replaces the Cloudflare Pages plan, which was never deployed). New `web/firebase.json`: the same headers as the former `public/_headers`, copied one to one (CSP with `frame-ancestors 'none'`, HSTS, nosniff, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, COOP), plus `Cache-Control: no-cache` on `**`, because Firebase's default is `max-age=3600` and an `/index.html` rule does not match `/` or deep links; the manifest `Content-Type`; the `**` → `/index.html` rewrite as the SPA fallback; `public` `dist/web/browser`; site `doorprints`; no hooks. The postbuild step now reads the CSP from the `**` rule of `web/firebase.json` (`cspFromFirebaseConfig`, `metaCspFromPolicy`) and fails when it is missing, set twice, or set by another rule. `public/_headers` and `public/_redirects` are deleted, `EXCLUDED` no longer lists them, and the `build:pages` script is removed (it produced the same output as `npm run build`). `sw.js`: the stale local `immutable` is now `contentHashed`, and `isAcceptable` has a tested twin in `sw-precache-core.mjs` (`sw-precache.spec.ts`). The comments in `sw.js`, `config.service`, `frame-guard`, `pwa.service`, `local-store.service`, `export.service` and `main.ts` name Firebase Hosting, and the specs use `https://doorprints.web.app`. The AI feature "Import from listing text" is renamed **"Fill in from listing text"**, with its keys moved from `import.*` to `listingFill.*` in all four languages, so that "import" means only bringing in a backup. The readable HTML copy is now `Doorprints-copy-<date>.html`, for the download and inside the backup ZIP (`htmlCopyName`). README "Deploy" is rewritten for Firebase: the migration advice now says that web import arrives in Sprint 4b and that data moves through a connected server until then, and `APP_CORS_ORIGINS` names `https://doorprints.web.app`. New section "Deferred to Sprint 4b" lists Android parity handovers 11(a), 13 and 17(a). |
| 2026-09-22 | **"Save a copy" wording (brand brief §G: *import* = a backup comes in, *save a copy* = data goes out).** The Your data section heading `data.exportHeading` now reads **Save a copy** / एक प्रति सहेजें / ஒரு நகலைச் சேமி / ఒక కాపీ సేవ్ చేయండి, the same words as Android's `export_title`. The strings that used "export" as the verb for data going out now say *save*, in all four languages: `storage.makeBackup` ("Save a backup"), `data.backupFirst` ("Save a backup first"), `storage.denied`, `storage.iosNote`, `storage.warnBody`, `storage.noIdbBody`, `error.storageFull`, `data.deterministic` ("two saved files") and `data.cancelled` ("Cancelled. Nothing was saved.", its second sentence as in Android's `export_stopped`). The Tamil strings changed here use the joined காப்புப்பிரதி. Keys, routes (`/data?export=backup`), file names and behaviour are unchanged. No spec asserted the old text. |
| 2026-09-22 | **Whole-app UX audit fixes (UX lead, before the first deploy).** No WebGL 2: the map page offers "Add at my location" and "Type latitude and longitude" instead of a dead "Place here" (blocker). `<main>` scroll resets on navigation and is restored on Back/Forward (`app.ts`); the house list keeps search, filter and sort in the URL (`?q=&status=&sort=`, `map-list.ts`) and Back keeps the map view instead of re-fitting; Compare keeps its selection in `?ids=`. House page: each card shows its own failures (location, coordinates with `aria-describedby`, visits, every failed photo, delete); "Fill in from listing text" and "Fill address from map" fill only empty fields and name or ask about the rest (`house-draft-merge.ts`); unsaved edits survive a discarded tab (sessionStorage, `draft-store.ts`) with a Discard note; leave dialog offers Save first; uploads announce once and guard leaving; Back returns to where the house was opened from; the tab title names the house (`TitleOverride`). Busy buttons use `aria-disabled` and removed buttons hand focus on (Download → Cancel → Download, banner Download → Stop → h1, Disconnect → h1, Clear rating → first star). Your data: partial-backup note with "Use everything" and "Saved a partial backup" (`backup-completeness.ts`, Android `BackupCompleteness` port), `format` is a signal and is remembered, backup hint no longer promises a web import, empty/filtered actions, print-tab placeholder, AI refreshed after Remove all data, sizes in `Intl` units. Connect: empty on the live site, errors after blur/submit. Plan no longer asks for the location on arrival; Ask/Plan keep their last answer/route for Back (`AiSessionState`), have Cancel, and are not live regions. Update banner "Not now" and a reload fallback; 44px chips, language select and photo delete on touch; list names wrap; "Lowest price" groups rent before sale; cooperative gestures on phones; MapLibre labels follow a language switch; permission-denied location message; offline errors say "You are offline"; sync-problem dot on Your data; device-neutral "Choose the spot on the map" wording; share page "Add a shared listing" with its text kept in sessionStorage. 53 new strings in all four languages. |
| 2026-09-22 | **Whole-app UX audit, round 1 review fixes (UX lead).** Header sync-problem mark: a 16px "!" disc in the new `--on-header-alert` token (#ffd27a light, 4.23:1 on the header; #f2b84b dark, 6.52:1) with a white ring, replacing the `--warn-text` dot that was 1.02:1 on the teal header (WCAG 1.4.11); the bottom-bar dot is unchanged. Checklist: "–" (not scored) moved after 0–5 as on Android, 44px squares on touch screens (the ≤400px shrink is gone), and the item name on its own line under 600px. House page on phones: one-row toolbar (icon-only Back with its name for screen readers, one-line title with an ellipsis, ●/✓ save state), and not sticky below 500px of height; the header navigation stays on one scrolling row on short screens. Every map gets `cooperativeGestures` on phones from `createMlMap` (house location map and Plan map too); the Plan map is capped at `min(60vh, 26rem)` on phones. Map page on phones: legend and actions share one bottom row (actions in a column at the end, primary last), the add-mode hint moves to the top and the legend is hidden in add mode, MapLibre's controls go bottom-right above that row (measured `--map-stack-h`), and every MapLibre control button is 44px on touch. Stats: 5 even columns, or 3 + 2 in a narrow panel (container query); stats and bottom-bar labels break only words that cannot fit (`overflow-wrap: break-word`, `hyphens: auto`). Empty states all use `.empty-glyph` with Material glyphs (`shared/glyphs.ts`); `.empty-icon` is removed. One page-top rhythm (`.page` 24px top, 16px ≤480px) and `<header class="page-head">` on every page (Connect's title moves out of its card). Share page buttons use `aria-disabled` with guards. Compare: the corner header cell is sticky with the row headers, and house links are underlined with →. Blocked chips keep full text contrast (`--muted`, dashed border) instead of 50% opacity. House page Back: decided in the constructor while the navigation is current (`back-target.ts`, with `back-target.spec.ts`), never `Location.back()` after a Back/Forward arrival, kept across the first save of a new house; the "Back to map" link, Delete and Discard carry the list's last search, filter and sort (`ListReturn`, `listReturnParams`). "Use everything" does nothing while a file is built. Focus no longer falls to `<body>` when Ask/Plan fail with focus on Cancel, when the download's Stop goes away by itself, or when Esc leaves add mode. The docs/05 §4 contrast table needs the new token's ratios (docs team). |
| 2026-09-22 | **Whole-app UX audit, round 2 review fixes (UX lead).** History: the router now uses `withRouterConfig({ canceledNavigationResolution: 'computed' })` (`app.config.ts`), so a Back that the unsaved-changes guard cancels ("Keep editing") goes forward again with `historyGo()` instead of writing the house URL over the list's entry; the house page's own Back asks first and then calls `Location.back()` with a one-shot pass for that popstate (`leaveApproved`), so edit → Back → Keep editing → Save → Back lands on the list (TC-NAV, `app.config.spec.ts`). Delete, and Discard on a new house, go back in history when the list is behind the page and otherwise replace the entry with the list (`exitAfterRemoval`, `back-target.spec.ts`), so Back from the list never opens "House not found" or an empty form. Navigation: Map is the current item on `/houses/*` too (`aria-current="true"` there, `"page"` on the map; `nav-section.ts`); from 601 to 900px the header keeps one scrolling row with the brand as its icon, as on short screens. Messages: the ⚠ of the restored-edits banner, the partial-backup note and the photo-failure box now starts the sentence instead of standing alone as a flex item or on its own line. Indic scripts: the phone toolbar title uses `--leading` with `padding-block: 0.1em` so Telugu vattulu are not clipped; Hindi, Tamil and Telugu headings get line-height 1.45 and buttons 1.4. Layout: the stats strip switches to 3 + 2 below 24rem (was 22rem), covering 360px phones; the landscape add-mode hint stops short of MapLibre's controls; the house toolbar's Back is 44px like Save; Connect, Ask, Your data and Share share one `--content-narrow` column (44rem). Map: add mode turns MapLibre's cooperative gestures off while it lasts, so one finger moves the map to the cross. Plan: the start fields stay empty with the example as a placeholder, and there is no start marker, until a start is set (a tap on the map now sets it); `plan.startHint` and `plan.startRequired` say so. Ask and Plan: pressing the button with no question shows a field error (new `ask.questionRequired`, `plan.questionRequired`, en/hi/ta/te) tied with `aria-describedby`, and focus moves to the box. "Remove all data" also clears this tab's unsaved house drafts and shared listing text from sessionStorage (`session-leftovers.ts`; `SHARE_TEXT_KEY` moves to `share-text.ts`). Deferred table: 13 and 17(a) marked implemented. |
| 2026-09-22 | **Whole-app UX audit, round 3 review fixes (UX lead).** Map page on phones: the add-mode hint is inset clear of MapLibre's control column and, under 760px, says the new short sentence `map.addHintShort` (en/hi/ta/te; the full `map.addHint` is still announced when add mode starts), so a Tamil or Telugu hint no longer grows down onto the crosshair. Framing the houses uses padding measured from the overlays (`fitPadding`, `pages/map/fit-padding.ts`, with `fit-padding.spec.ts`), so on a phone the southernmost house is not left under the legend, the action column or MapLibre's control column. Header from 601 to 900px (and on short screens): the one scrolling navigation row fades out at its end while more items follow (not while an item has focus, so its ring is never faded), and the current item is scrolled into view clear of the fade. One shared `.checkbox` row (Connect, Your data) and one shared `.field-error` style (Connect, Ask, Plan, the house page) in `styles.css`. Phones (≤600px): the bottom bar is hidden on the house pages (`hidesBottomBar`, `nav-section.ts`, with `nav-section.spec.ts`), whose one-row toolbar has a borderless 44px icon Back. House Save: `aria-busy` while saving, with a small spinner beside "Saving…" in the state slot and the Save label unchanged, so the row does not reflow. Header: `.lang` comes before `<nav>` in the DOM (the same order as on screen, WCAG 2.4.3), and the hover colours sit inside `@media (hover: hover)`, so a tap on a touch screen leaves no stuck hover state. |
| 2026-09-22 | **Final Sprint 4a round (delivery coordinator's cross-team review, 2026-09-23).** **North-up map (Android handover 33, WCAG 2.5.1):** `createMlMap` (`shared/map-style.ts`), which makes the map, Plan and house-location maps, now turns off right-drag and Ctrl-drag rotate and tilt (`dragRotate: false`, `pitchWithRotate: false`), two-finger tilt (`touchPitch: false`), the two-finger twist (`touchZoomRotate.disableRotation()`) and Shift+arrow rotate and tilt (`keyboard.disableRotation()`). The zoom control has no compass, so a rotated map had no way back to north; Android 1.31 made its map north-up for the same reason. Option names and methods checked against maplibre-gl-js v6.10.0. **Retry keeps the last card (Android 1.33 `RefreshableResultCard`):** asking or planning again after an error, and a new connection check on Connect, keep the earlier error or result card in place until the run ends, instead of clearing it first and making the content below jump up and back. While the run is busy the card's ⚠/✓ glyph and its border are dimmed to the new `--stale-alpha` token (0.5, Android's `STALE_RESULT_ALPHA`), its text stays at full contrast, and an indeterminate bar runs along its foot (`.refresh-slot`, `.refresh-bar`, `styles.css`). The bar is a `role="progressbar"` named "Thinking…", "Planning…" or "Testing…", outside the live region, so a screen reader finds it by browsing but does not announce it. Each card is keyed on its run (`shared/run-result.ts`), so a new failure with the same words is still read. Connect's *Save anyway* stays in place during a new check but does nothing until the check ends. **Device-neutral wording:** `map.addHintShort`, `plan.startHint` and `plan.startRequired` say "choose" instead of "tap" in all four languages, using the verbs of `map.addHint` and `house.mapHint` (hi चुनें, ta தேர்ந்தெடுக்கவும், te ఎంచుకోండి). **Spoken "Saving…":** the house form's Save now announces `house.saving` through the app's announcer when the save starts, as Android's Save button does through its contentDescription. A save that ends within the announcer's 100 ms is heard only as "Saved". **Icons:** `favicon.svg`, `icon-192.png`, `icon-512.png`, `icon-maskable-512.png` and `apple-touch-icon.png` are redrawn from the brand mark (see *Installable (PWA)*), with the same file names and sizes. The manifest and theme colours already matched the Android brand colours and are unchanged, as are the shortcut icons (white on #1F6F5C). No new strings. For Docs: docs/05 §5 (the keep-in-place retry rule now holds on both clients), docs/05 §10 (the three reworded hints), and docs/10 handover item 33 (implemented on the web). |
| 2026-09-22 | **Sprint 4a pre-review fixes (web buddy review).** A failed house save cancels the pending "Saving…" (`Announcer.cancel()`, `announcer.service.spec.ts`), so a fast IndexedDB refusal such as `error.storageFull` is heard as the "Could not save…" alert only, not followed by a late "Saving…" (R9). Connect: when a check started with *Test* removes "Save anyway" while it has focus, focus goes to *Save and continue* (`id="connect-save"`), not `<body>` (R18); `focusIfLost` now lives in `shared/focus.ts` (with `focus.spec.ts`) for Ask, Plan and Connect. Connect saves the address and key **that were tested**, and editing either field (or leaving the page) drops a check still running, so edited, untested values are never saved with a "Connected" announcement. Forced colours: the "being updated" bar is kept, drawn in `Highlight` (R7). The two manifest screenshots show the door-and-footprints brand mark in the header instead of the old house glyph (R15). |
| 2026-09-22 | **Sprint 4a pre-review fixes, round 2 (web buddy, UX and design review).** `Announcer.cancel(msg)` now withdraws only the message its caller queued (same key), pending or shown, so one feature can no longer drop another's announcement from the app-wide region (a "Photo deleted" queued during a save survives the save's failure; two new cases in `announcer.service.spec.ts`). Every in-progress announcement ends on every path: the house save's "Saving…" is replaced by "Saved" or withdrawn on failure, and the export's "Preparing photos … of …" is withdrawn when the build ends without a message of its own (failure, closed share sheet; `data-page.ts`). Every alert or status message that the same button can give twice in a row with the same words is keyed on its run (`RunResult`, `@for (c of [card]; track c.run)`), so the second one is read again: the house page's save, listing, location, visits, photos and delete messages, the export and share failures, the sync failure on Your data and in the first-run banner (`SyncService.lastFailure`, with a `sync.service.spec.ts` case), the map and Compare load errors, the map's and Plan's location messages, and Share's copy failure. On the map and Compare only the sentence is keyed, so a focused Retry keeps focus (R18), and a background re-read that fails the same way keeps its run and is not read out on every sync pull (`nextRunResult`, `run-result.spec.ts`). Leaving the house page drops a listing read or address lookup still running, and a late position is ignored, so no form is filled, no question opened and nothing announced on another page. New `connect-page.spec.ts` (TestBed, stub `HouseApiService`): editing a field or leaving the page drops a running check (no save, no navigation), "Save and continue" saves exactly the values it tested, and a *Test* that removes a focused "Save anyway" moves focus to *Save and continue*. `index.html` adds the 192px PNG as a tab icon before `favicon.svg`, for browsers without SVG tab icons (Safari before 26), which otherwise asked for `/favicon.ico` and got the SPA fallback page. No new strings. For Docs: docs/05 §15.3 R9 and R17 gain the three rules the round 1 reviewers added (an in-progress message is replaced or withdrawn on every end path, and a shared service clears only what its caller put there; a repeatable alert or status is keyed on its run; every busy indicator has its failure and cancel paths checked). |
| 2026-09-22 | **Pre-deploy close-out (coordinator's final review, second pass; Sprint 4a, 2026-09-23).** **Location answers after the page is gone:** Map's *Add at my location* and Plan's *Use my location* now have the house form's guard. A `destroyed` flag is set first in `ngOnDestroy`, and `shared/locate-once.ts` (`locateOnce`, with `locate-once.spec.ts`) drops the answer, found or failed, when the page is gone by the time the permission prompt or the 15 s fix answers. So leaving the page never opens a new-house form, moves a removed map or announces on another page. The house form uses the same helper, so its error callback is now guarded too. Plan's first start, read from the store, is also dropped after the page is gone. All three ask with one `LOCATE_OPTIONS`. **Favicon:** new `icons/favicon-32.png`, which is `favicon.svg` rendered at 32×32 by headless Chromium. `index.html` lists it first with `sizes="32x32"`, then the 192 px PNG, then the SVG (S4b-BL-3). `sw-precache.spec.ts` checks that it is precached. **Remove all data** now also removes this app's `hh.*` and `doorprints.*` localStorage keys: `hh.installDismissedAt` and `hh.storageRiskDismissedAt` were left behind (`clearLocalLeftovers`, `session-leftovers.spec.ts`). It also unregisters this deployment's service worker, and only that one (`PwaService.unregister`, `unregisterOwnWorker`, `pwa.service.spec.ts`). Both are required by docs/07 Appendix A.1 S10. The language choice stays, as the Your data text already says. No new strings. New section **Storage audit on the live site**: the exact steps and expected results of TC-S-19 on `https://doorprints.web.app` for the owner, a normal pass and a private-window pass. **Deferred to Sprint 4b** now lists the web backlog tickets of the review (S4b-BL-1, S4b-BL-2, W2, and a new minor found with candidate rule (f)), and records S4b-BL-3 and the geolocation guard as done. For Docs: docs/06 TC-S-19 (the new scope of "Remove all data"; the live steps are in this README), TC-U-53 and §14.3 (the new `shared/locate-once.spec.ts`, and the new cases in `session-leftovers.spec.ts`, `pwa.service.spec.ts` and `sw-precache.spec.ts`), and docs/10 §12.7 (the geolocation guard and S4b-BL-3 done, and the new minor under rule (f)). |
| 2026-09-22 | **Pre-deploy close-out, web buddy pre-review (Sprint 4a, 2026-09-23).** **Storage audit (TC-S-19) corrected:** the map page's first `ResizeObserver` callback calls `map.resize()`, which in MapLibre 6.10 fires `movestart`, `move`, `resize` and `moveend` (`Map.resize`, `src/ui/map.ts` at v6.10.0), so `saveMapView` writes `hh.mapView` on the first start. Pass 1 step 1 and pass 2 step 4 now expect local storage to hold only `hh.mapView` (the starting view over India) and session storage to be empty, the table says "From the first time the map page shows", and pass 1 steps 4 and 5 say the map page writes it again when it next shows. Without this, the owner would have rolled back a correct deploy. **Remove all data** now removes every `hh.*` and `doorprints.*` key from this tab's sessionStorage too, as the audit and docs/07 Appendix A.1 S10 say, instead of only `hh.houseDraft:*` and `hh.shareText` (`clearSessionLeftovers` and `clearLocalLeftovers` share one sweep over `LOCAL_KEY_PREFIXES`; `session-leftovers.spec.ts` adds `hh.somethingElse`, `doorprints.x`, a kept `house-hunt.api-config`, and a check that `DRAFT_PREFIX` and `SHARE_TEXT_KEY` fall under the prefixes). **W2 done** (docs/10 §11.7, carried twice, so a major under playbook §4.4 if carried again): *Plan route* with an unusable start focuses the first start field still to fix, so with the latitude typed and the longitude empty it focuses the longitude (`pages/plan/start-field.ts`, `startFieldToFix`, with `start-field.spec.ts`). **Rule (f) minor done:** Map's `downloadHere()` returns after `await done`, and again after counting the houses, when the page is gone, so nothing is announced on another page. No new strings. For Docs: docs/06 TC-S-19 (the corrected expected results for `hh.mapView`, and the session sweep), TC-U-53 and §14.3 (the new `start-field.spec.ts` and the new `session-leftovers.spec.ts` cases), and docs/10 §11.7 and §12.7 (W2 and the rule (f) minor done). |
| 2026-09-22 | **Pre-deploy close-out, round 1 review fixes (Sprint 4a, 2026-09-23).** **Plan's typed start:** a coordinate typed while no start is set is kept only while its field is valid. A field that turns invalid or is cleared drops it (`nextTypedStart` in `pages/plan/start-field.ts`, specs for typed, then invalid or cleared, then the other typed: no start is set and *Plan route* focuses the latitude). Setting the start writes each field it fills and clears that field's invalid flag in the same step; editing one field with a start set leaves the other field's text and flag alone. The newest-house start that is read after the page opens no longer overwrites fields the user has typed in. **Stored default view:** `hh.mapView` still holding the untouched country view (`COUNTRY_VIEW`, saved by MapLibre's first-layout `moveend`) is not a user choice. `parseMapView` refuses it, so the map page still frames the houses the first time there are some. The new `loadStartPoint` also refuses the country centre at any other zoom, and Plan's first start and the new-house form use it, so neither starts in central India (`map-center.spec.ts`). **Plan start message:** the message under the start fields has the id `start-msg` and is in both fields' `aria-describedby` while shown. The field *Plan route* focuses for *Choose a start point first* is `aria-invalid` while that message shows. **Download now:** if the houses cannot be read back for the count, the count is not announced and focus still moves to the list heading. Storage audit: the step now names the field **API address (URL)**, and the `hh.mapView` row also allows storage blocked for the site. No new strings. For Docs: docs/06 TC-U-53 and §14.3 (`map-center.spec.ts`: the stored map view, the untouched `COUNTRY_VIEW` refused, `startPointFromView` refuses the country centre at any zoom; `start-field.spec.ts`: `nextTypedStart` drops a coordinate whose field turned invalid or empty), docs/05 §5 Plan and new-house start (the default view is not a start the user chose), and the new-rule candidate for docs/05 R6/R11 (a value the app writes by itself, such as a default or a first-layout event, is never read back as a user choice). |
| 2026-09-22 | **Pre-deploy close-out, round 2 review fixes (Sprint 4a, 2026-09-23).** **Plan start message withdrawn by every start:** `setStart` now clears the message under the start fields whatever its key, so a *location blocked* or *location unavailable* note goes away when the start is then set from the map, a marker drag, typing both fields, editing one field over a set start, the newest house or a later *Use my location*. Before, only *Choose a start point first* was cleared, and the stale note was read through the fields' `aria-describedby` on every focus of a valid field. **New house with no position:** `startWithoutPosition()` returns when the page is gone after the houses are read, and again after a failed read, so leaving `/houses/new` during that read no longer announces *Draft restored* or sets the title on the next page (rule (f), as Plan's `initialStart`). It also leaves deleted houses out itself, as Plan does, although `houses()` already does. **Deferred to Sprint 4b** adds S4b-BL-6 (no visible `aria-invalid` style) and S4b-BL-7 (no TestBed spec for the Plan page); these ids were corrected in round 3, see the next row. No new strings, no new storage keys. For Docs: docs/05 §5 Plan (every way of setting the start withdraws the message under the start fields) and the new-rule candidate for docs/05 R9/R18 (when a message is referenced from a field's `aria-describedby`, every path that resolves its cause withdraws it, not only the path that raised it), and docs/10 §12.7 (the rule (f) guard in the house form's `startWithoutPosition`, and S4b-BL-6 and S4b-BL-7 as new Sprint 4b tickets). |
| 2026-09-22 | **Pre-deploy close-out, round 3 review fixes (Sprint 4a, 2026-09-23). README only, no code change.** **Backlog ids:** the round 2 row gave the two new web tickets S4b-BL-4 and S4b-BL-5, ids that docs/10 §12.7 already gives to the Android map attribution hidden behind snackbars (licence-credit exposure) and to Map's `downloadHere()` guard (done). They are now **S4b-BL-6** (no visible `aria-invalid` style) and **S4b-BL-7** (no TestBed spec for the Plan page), the next free numbers after S4b-BL-5, and the round 2 row says so too. The *Rule (f) candidate* row of *Deferred to Sprint 4b* now carries its register id, S4b-BL-5, so the table and docs/10 §12.7 agree row for row, and the table's lead-in says which ids come from the register and which are new. **House form TestBed case:** S4b-BL-7 now also plans a `house-detail-page.spec.ts` case for the two rule (f) guards in `startWithoutPosition()`: `/houses/new` with no position, destroyed before `houses()` resolves or rejects, then no draft, no *Draft restored* and no title. Web's open tickets from this close-out are S4b-BL-1, S4b-BL-2, S4b-BL-6 and S4b-BL-7; S4b-BL-4 is Android's. For Docs: docs/10 §12.7, add **S4b-BL-6 and S4b-BL-7 as new tickets** (owner Web; they reuse no existing id), with S4b-BL-7's two spec files; and the new-rule candidate for the docs/10 §12.7 list (a team README never makes up a backlog id: it takes the next free number from docs/10 §12.7, and its Docs handover says the id is new). |
| 2026-09-23 | **Sprint 4a CI fix: stale offline expectation in `sync.service.spec.ts` (Web run 35897717543, commit c676794, 1 of 420 failed; 2026-09-23). Spec only, no production change.** `errorMsg` (`core/format.ts`) maps a request that got no response (HTTP status 0) to `error.offline` when `navigator.onLine` is false and to `error.network` otherwise (the UX audit's "a phone on a bus is not a CORS case", pinned by `format.spec.ts` *errorMsg when the device is offline*). The *offline* case `'reports a failure the user asked for, as such'` still expected `error.network` after `setOnline(false)` and a forced *Sync now*; it now expects `error.offline` with `lastErrorForced()` true. New sibling case `'keeps the connection message for a forced run that got no response while the browser says online'` pins the other branch through the sync engine: same status-0 failure, browser online, `error.network`, `lastErrorForced()` true. The other `error.network` expectations in this spec (first-run migration, failure paths) and in `run-result.spec.ts` run with the browser online (jsdom default, restored by `afterEach`), so they are correct as they are. No new strings (`error.offline` is already in en/hi/ta/te). For Docs: docs/06 TC-U-44 says "status 0 is `error.network`"; it should say status 0 is `error.offline` while the browser is offline and `error.network` otherwise, and TC-U-31 can name the two `sync.service.spec.ts` *offline* cases. |
| 2026-09-24 | **India's boundaries on every map (owner P0, Jammu and Kashmir, and the same issue near Arunachal Pradesh; branch `fix/india-boundaries`).** New `shared/india-boundaries.ts`: `indiaBoundaryStyle` (pure: a style in, a new style out) applies the five rules — (1) `boundary_disputed` hidden; (2) `boundary_2` from zoom 5, its filter ANDed with "not both sides in PAK/CHN"; (3) GeoJSON source `in-boundaries` from `geo/in-boundaries.geojson` resolved against the base href (so deep links work), attribution "Natural Earth", with `in-boundary-world` (kind `world`, maxzoom 5) and `in-boundary-claim` (kind `claim`) inserted directly above `boundary_2` with its colour, width and opacity (Liberty's values as the fallback, the same as Android's `FALLBACK_LINE_*`), round joins and caps (fallback: above the first `boundary` layer, else below the first symbol layer); (4) every `place` symbol layer that can show a state (`label_state`, and `label_other`, where it changes nothing) ANDed with "not Azad Kashmir / Azad Jammu and Kashmir / Gilgit-Baltistan" by `name:en`/`name` and by the Urdu `name`; (5) a missing layer is a `console.warn`, never an error, and the overlay is still added. Arunachal Pradesh: in the OpenFreeMap tiles of 2026-09-13 (z5 tile 24/13) every India-China line there is `disputed` (with or without `claimed_by: CN`), so rule 1 removes it and the `claim` outline of the lead's `east` box draws India's boundary; the state label is "Arunachal Pradesh" (its `name:zh` is never shown by Liberty). A filter in the deprecated syntax gets the rules in that syntax (MapLibre refuses a mix). `applyIndiaBoundaries` makes exactly those changes on a live map with `setLayoutProperty`/`setFilter`/`setLayerZoomRange`/`addSource`/`addLayer` on **every** `style.load`, registered in `createMlMap` before any page listener, so the offline retry of `watchMapStyle` gets it too. Not `setStyle`'s `transformStyle`: in MapLibre 6.10 `Map._updateStyle` then waits for the previous style's `style.load`, which never comes after an offline start. Checked against maplibre-gl-js v6.10.0 and the style spec 26.4.4 sources: the whole transformed Liberty style validates with no error (`validateStyleMin`), and `featureFilter` drops Khunjerab (PAK/CHN) and both state labels while keeping Ladakh, Jammu and Kashmir and Arunachal Pradesh. New `india-boundaries.spec.ts` (30 cases) against `shared/testing/liberty-style.fixture.ts`, an excerpt of the real Liberty JSON (boundary, place and neighbouring layers); the `testing/` folder is excluded from the app build (`tsconfig.app.json`) and compiled by `tsconfig.spec.json`. `sw-precache.spec.ts`: the data file is precached and accepted as `application/geo+json`, refused as HTML. `firebase.json`: `**/*.geojson` served as `application/geo+json` (under `nosniff`); the CSP is unchanged (same origin). For Docs: docs/05 and docs/11 map rows (India's boundary view, both platforms), and TC-M-19 (live headers) after the next deploy. |
| 2026-09-24 | **India's boundaries, parity fix with Android 1.37/1.38 (zoom 0-4 placeholder tiles; branch `fix/india-boundaries`; `android/shared/README.md` item 36).** MapLibre draws a zoom 0-4 tile, overzoomed, while a zoom 5+ tile loads and offline, and those tiles carry Natural Earth's ISO-view lines with no `adm0_l`/`adm0_r`. `shared/india-boundaries.ts`: (a) `boundary_2`'s extra filter is now `["all", ["any", ["has", "adm0_l"], ["has", "adm0_r"]], ["!", <both sides in PAK/CHN>]]` (Android's `COUNTRY_LINE_EXTRA_FILTER`), and in the deprecated syntax `["all", ["any", …has…], ["none", ["all", ["in", "adm0_l", "PAK", "CHN"], ["in", "adm0_r", "PAK", "CHN"]]]]` for a layer whose own filter is in it; (b) new `TILE_ZOOM_GUARD`, `[">=", ["zoom"], 5]`, ANDed into the filter of `boundary_2`, `boundary_3` (new `STATE_LINES_LAYER`) and every other line layer on source-layer `boundary` with minzoom 5 or more (never `boundary_disputed`, a symbol layer or a zoom 0-4 layer; minzooms kept). A layer whose filter is in the deprecated syntax, which has no `zoom`, is left as it is with a `console.warn`; no guarded layer at all is a warning too. A filter's `zoom` is the tile's: maplibre-gl v6.10.0 runs a line layer's filter with `new EvaluationParameters(this.zoom)` (`src/data/bucket/line_bucket.ts:155-160`), the worker tile's zoom (`src/source/worker_tile.ts:50`), which is `tile.tileID.overscaledZ` (`src/source/vector_tile_source.ts:211`); the style spec 26.4.4 compiles a filter with `createExpression` and parameters `['zoom', 'feature']` (`src/feature_filter/index.ts:222-265`), so the "zoom only in a top-level step/interpolate" rule of `createPropertyExpression` (`src/expression/index.ts:517-527`) does not apply, and `validateFilter` refuses only `feature-state` there (`src/validate/validate_expression.ts:56-63`). Found while checking: maplibre-gl 6.10 already builds no bucket on a tile for a layer whose `floor(minzoom)` is above the tile's zoom (`worker_tile.ts:109`, `layer.isHidden(this.zoom, true)`), so on the web the zoom 4 tiles' `boundary_3` and `boundary_2` lines were already not drawn at zoom 5+; the guard makes that hold whatever the renderer does and matches Android. `india-boundaries.spec.ts`: 37 cases (30 before; changed expectations for the new filters, the extra warning when no boundary line layer exists and the `setFilter` of `boundary_3`), the test evaluator now takes the tile zoom for `zoom` and has `>=`/`<=`. New cases: no adm0 side never drawn (and Liberty alone drew it); a zoom 4 `{admin_level: 2, disputed: 0, adm0_r: "BTN"}` dropped at tile zoom 0 and 4, kept at 5, 8, 14 and 16; `boundary_3` drops `{admin_level: 4, disputed: 0, maritime: 0}` at 0 and 4 (Liberty alone kept it at 4), keeps it at 5, 8, 14 and 16; one syntax in every written filter (port of `findMixedLegacyFilter`); without `boundary_3`; a deprecated `boundary_3` left as it is with a warning; the guarded-layer list. Type-checked with `tsc` 6.0.3 against the style spec types and run with a local shim of the vitest API used (37 of 37); not run under the real vitest or in a browser. No string, layout, CSP, dependency or data file change; en/hi/ta/te unchanged. For Docs: corrected in the next row (this row first said "nothing new beyond item 37's device check"; the docs/11 §10 parity row and TC-U-54 also change). |
| 2026-09-24 | **India's boundaries, comment and README sync (branch `fix/india-boundaries`, after HEAD `3ad2b58`). No behaviour or code change: every expression, filter and constant in `shared/india-boundaries.ts` and all of `india-boundaries.spec.ts` (37 cases) are byte-identical; only comments and this README change.** (a) **`takesTileZoomGuard` KDoc:** it said the minzoom of a zoom 5+ layer was "checked against the map zoom". It now names each renderer: maplibre-gl 6.10.0 builds no bucket for a layer in a tile whose zoom is below floor(minzoom) (`src/source/worker_tile.ts:109`, `src/style/style_layer.ts:321-322`), so on the web minzoom 5 alone already kept the zoom 0-4 tiles' lines off the map and `TILE_ZOOM_GUARD` is defence in depth and parity with Android. It also gives the maplibre-native reading of (4). (b) **Intro paragraph *India's boundaries*:** it now names the adm0 clause (`COUNTRY_LINE_RULE`, `COUNTRY_LINE_RULE_LEGACY`) and the tile-zoom guard (`TILE_ZOOM_GUARD` on `boundary_2`, `boundary_3` and every `boundary` line layer from zoom 5, never `boundary_disputed`). It says that Android applies the same rules (`ui/IndiaView.kt`, `ui/IndiaViewRules.kt`; 18 India tests there, `IndiaViewRulesTest` 16 and `IndiaBoundaryDataTest` 2) and that the "Natural Earth" credit is the only deliberate difference. Before, it said the rules were only "specified" for Android. (c) **Known limits, both apps, recorded in the intro with measured figures in place of "about a kilometre":** (i) doubled lines from zoom 5 where the tiles' `boundary_2` also draws India's outline, in the middle sector (Himachal Pradesh, Uttarakhand, Kalapani/Dharchula) and Wakhan. The tile line and the `claim` outline are a median of 1.5-2.8 km apart, at most 5.3 km (Web team's tile decode). Arunachal Pradesh-Bhutan (0 of 96 samples) and Arunachal Pradesh-Myanmar (0 of 206) are drawn by the `claim` outline alone (`android/shared/README.md` 1.38 item 38 (b)); the only shared stretches there are Bhutan's south-east corner and Myanmar south of 26.65°N. (ii) The `claim` outline is a median of about 1.55 km (Jammu-Sialkot) and 1.6 km (McMahon Line) from the true line, 3.9 km at the 90th percentile. (iii) From zoom 5 the Assam-Arunachal Pradesh state line is not drawn on either app. In tile 5/24/13 it is admin level 4, `disputed` 1, `claimed_by` CN; Liberty's `boundary_3` never draws a disputed line, and rule 1 hides `boundary_disputed`. **For Docs (corrects the previous row's handover):** (1) docs/11 §10 *Map boundaries (India)* still records "Open parity gap (not deliberate; Web, item 36)" and "web adm0 guard open" in its status column. Web has closed that gap: `COUNTRY_LINE_RULE` with the adm0 clause, and `TILE_ZOOM_GUARD`, as on Android. That row now says "the same five rules" (as docs/03 ADR-22 v0.18 does), with the "Natural Earth" credit as the only deliberate difference. (2) docs/06 TC-U-54: 37 cases. (3) docs/10 §12.7: S4b-BL-10's "about a kilometre" becomes the figures in (c)(ii). S4b-BL-11's list of doubled stretches drops Arunachal Pradesh with Bhutan and Myanmar, except the two shared stretches in (c)(i). The next free id is S4b-BL-13. **New tickets proposed by the coordinator:** **S4b-BL-15** (Both), draw India's Assam-Arunachal Pradesh state line from the bundled data, or unhide the admin-4 disputed lines claimed by CN inside India; and **S4b-BL-16** (Both), remove the doubled tile and `claim` lines in the middle sector and Wakhan, for example by hiding `boundary_2` inside the claim boxes with a `within` filter or trimming the `claim` outline where the tiles already draw India's line. S4b-BL-16 overlaps S4b-BL-11: Docs may fold one into the other. S4b-BL-14 (this intro) is not needed: this row does it. S4b-BL-13 (renderer wording) is done for the web here; see (4). (4) **Finding for Android and Docs; not edited here, because the files are theirs:** `android/shared/README.md` 1.39 (a), the `IndiaViewRules.kt` KDoc and docs/03 ADR-22 say that maplibre-native has no minzoom skip, so on Android the guard is the actual fix. The worker's parse loop has none (`src/mln/tile/geometry_tile_worker.cpp:446-502`, filter with `overscaledZ` at :502, `android-v13.6.1`, c7506d6). But `GeometryTile::setLayers` leaves out a layer when `id.overscaledZ < std::floor(minZoom)` before the worker gets the layers (`src/mln/tile/geometry_tile.cpp:317`, called for new and relaid-out tiles at `src/mln/renderer/tile_pyramid.cpp:167,193`). Read from the source, not checked on a device, this means Android also skips a minzoom 5 layer in a zoom 0-4 tile, and the guard is defence in depth there too. Please re-check before recording "the actual fix"; S4b-BL-13 is the free id if the wording is not settled this round. |
| 2026-09-24 | **App icon footprints, option C (branch `fix/brand-footprints`, PR #15; [docs/14](../docs/14-lead-backlog-and-handoff.md) N3).** `favicon.svg` and the five app-icon PNGs in `public/icons` (not the shortcut icons) show three small footprints walking up beside the door, left, right, left (sole, heel, four toes), rendered from SVG with headless Chromium; each icon's layout, the door and the colours are unchanged; the favicon keeps the same prints (owner's choice). *Installable (PWA)* and the `index.html` comment updated. No code, string, CSP or dependency change. |
| 2026-09-24 | **India's boundary: one line from zoom 5 (India's line with China ours alone, shared stretches handed to the tiles); the Assam-Arunachal Pradesh state line (branch `fix/india-boundary-lines`, PR #16, `5af2f4d` then `9e0036e` after the design review; [docs/10](../docs/10-sprint-log.md) §12.10; S4b-BL-11, S4b-BL-15, S4b-BL-16).** `shared/india-boundaries.ts`: `COUNTRY_LINE_RULE` (and its deprecated-syntax form) also leaves out India's line with China (`INDIA_CHINA_LINE`), which showed as stray pieces beside the outline at zoom 10-12 (Shipki La, the Mana Pass). `public/geo/in-boundaries.geojson` rebuilt (sha256 `2c497e2e…56d7`, 415 689 bytes; kinds `world` 359 lines, `claim` 7 pieces, `state` 2 lines), byte-identical to Android's copy: the 7 stretches where the tiles draw India's border themselves (none with China) moved from `claim` to `world` (below zoom 5 only), found by the new `scripts/geo/find_shared_stretches.py` (zooms 7, 9 and 11) and pasted into `SHARED` in `scripts/geo/build_in_boundaries.py` (`--no-shared` gives the whole outline for the finder; no connector-only spur at a box edge; repeated points removed). New layer `in-boundary-state` (`IN_BOUNDARY_STATE_LAYER`, kind `state`, minzoom 5), directly above `boundary_3` with its `line-color`, `line-width`, `line-dasharray` and `line-opacity` copied and butt caps; without `boundary_3`, directly below `in-boundary-world` with `STATE_FALLBACK_LINE_PAINT`; a taken id is a warning. `india-boundaries.spec.ts`: 41 cases (4 new: rule 2, India's line with China left to India's outline; the state line from zoom 5, dashed and drawn like `boundary_3`; without `boundary_3`; the state-line id already taken; ordering expectations updated); the whole suite, 463 tests, passes. Renders of `9e0036e` at zoom 8-12 over every hand-over and the spots flagged in review: one line; the state line dashed like the other state lines. CI green on `5af2f4d`; the run on `9e0036e` pending. |
| 2026-09-24 | **India's boundary, round 2 review fix (branch `fix/india-boundary-lines`, PR #16; [docs/10](../docs/10-sprint-log.md) §12.10).** `scripts/geo/build_in_boundaries.py`: a `SHARED` cut within 1e-4 degrees of a claim line's end counts as the end (`SHARED` is rounded to 5 decimals), so the two connector-only pieces on the Singalila ridge (2.5 km and 2.3 km), drawn as spurs into Nepal from zoom 5, are gone. `public/geo/in-boundaries.geojson` rebuilt (sha256 `25984afa…a024`, 415 608 bytes; `world` 359 lines, `claim` 5 pieces, `state` 2 lines; the 7 shared stretches unchanged), byte-identical to Android's copy. No change to `shared/india-boundaries.ts` or its spec. Known minors recorded in the intro and in docs/03 ADR-22: the Sikkim tri-junction loops (about 13 x 3 km and 2 km), the tile line's overrun at Jomotsangkha and Longwa from about zoom 10 (a small hook at Jomotsangkha from zoom 9) (S4b-BL-17), and `INDIA_CHINA_LINE` also hiding the Tumen China-North Korea line. |
