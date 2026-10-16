*Session record; the decisions are in [docs/11](../11-feature-parity-and-export-spec.md) §5.27.13 and [docs/03](../03-design.md) §6.2b. Applied 2026-10-06 on the owner's "go with the recommendations": all MUST and SHOULD items are in the spec; the open questions of section 9 are decided (1: the 15 s best-fix watch; 2: not in S4b, LATER; 3: as recommended).*

# Senior review: *Have I been here?* (the on-demand place check, S4b-FR-24) and how the trace comes to the website

| Field | Value |
|---|---|
| Document | Review of the place-check design (`feat/path-trace-check-spec`, five docs-only commits on `main` `d6c3c2b7`) and the website integration plan for the path trace v2 and the check |
| Version | 0.1 |
| Date | 2026-10-06 |
| Author | Claude, senior reviewer |
| Scope | [11](../11-feature-parity-and-export-spec.md) 5.27.13 (+5.27.0 item 7, 5.27.2, 5.27.9, 5.27.10, 5.27.11 q15-17), [01](../01-requirements.md) FR-108, PRV-032, [02](../02-threat-model.md) T-I42, RR-31, [03](../03-design.md) §7.2b and the shared API, [schemas/trace-repeat-vectors.json](../schemas/trace-repeat-vectors.json) `placeChecks` (21 cases, 58 in all), [06](../06-test-plan.md) TC-U-153, TC-U-154, TC-M-61, [manual-test-checklist.md](manual-test-checklist.md) MT-81, [10](../10-sprint-log.md) S4b-FR-24; and the real website code under `web/src/app` (the Map page, the house page, `data/local-db.ts`, `shared/map-style.ts`, `shared/india-boundaries.ts`, `shared/locate-once.ts`, `i18n/*`, `public/sw.js`, `firebase.json`, `tools/check-templates.mjs`, `tools/check-specs.mjs`, `tools/live-ui`) |
| Verdict | **GO with changes.** The semantics are exact: an independent reference written from 5.27.13 alone passes all 21 `placeChecks` cases, every named mutation of TC-U-153 fails exactly the case it names, and a second reference written from 5.27.3 passes the 37 repeat cases. The changes are to the text, not to the idea: six MUST items (five fit the spec to the website's real code, one closes a leak path in the website's live region), all cheap. |

## 0. Verdict in one paragraph

The place check is a good, small feature: one pure function over the segments the trace already stores, no store, no setting, no
network. The vectors are sound and the hygiene holds (moving `TOLERANCE_M` and `NEAR_BAND_M` by ±0.02 m changes no result). What
must change before S4b-FR-24 starts: (1) the website's *here* flow is written as a stream (*the first fix of 50 m or better, at 15 s the
best so far*) but specified as one `getCurrentPosition`, so TC-U-154's gate test cannot be written for the web twin as it stands;
(2) four strings hard-code *25 m*, which 5.27.2 itself names as replaceable by 20; (3) `trace.here.and2`/`and3` duplicate the dictionary's
`list.two`/`list.three` and `trace.here.a11y.stretch` is the only four-level key in a dictionary of two- and three-level keys;
(4) *the sentence the first time* needs a flag the spec says it does not have; (5) *Show on map* from the house page on the website
must not navigate with the answer (the house page has its own `LocationMap`: draw there); (6) the website's app-wide polite live region
keeps its last text in the DOM, so *closing the sheet discards everything* is false on the website unless the close withdraws the
announcement (`Announcer.cancel`). The integration plan (section 4) is file by file with order and risks; the short version: a
`shared/trace-geo.ts` for the plane and the point-to-segment distance that both algorithms use, a `data/trace-store.ts` on
`LocalDb` version 3 with one new batch operation, three small child components on the Map page (a trace card, the trace layers, the
check control and panel) instead of growing the 894-line `MapPage`, the check on the house page as a card under the location card,
and no Web Worker for the check.

## 1. The vectors: 21 of 21 (and 37 of 37) agree with independent references

Two references in the reviewer's scratch directory (not committed): `pcref.py` from 5.27.13 alone (the gate, the plane, the clamped
segment distance, no segment into a resumed point, the interpolated time with `floor(t * dt + 0.5)`, bands, status order, newest-first
with the later index on a tie) and `rpref.py` from 5.27.3 alone (split, samples, near, bridge, runs, `shown`, the alert with the
`blocked` and cooldown rules and the walk-just-finished exclusion). Results:

| Check | Result |
|---|---|
| `placeChecks`, 21 cases: status, `fuzzy`, `nearestM` and `distanceM` to 0.5 m, `atMs` to 1 s, row order, band, source | **0 disagreements** |
| The 37 repeat cases (`split`, `repeats` with `repeated` and `shown`, `alert`) with the second reference | **0 disagreements** |
| The twelve named mutations of TC-U-153 applied to the reference | each fails the case it names (and some others: dropping the clamp fails 16 cases, the segment-start time 15; fine, the named case is among them) |
| Hygiene: `TOLERANCE_M` 24.98 / 25.02 and `NEAR_BAND_M` 49.98 / 50.02, all four combinations | **no case changes** |
| The construction of each case (the distance it was built to have) | 24.8999, 25.1, 49.8998, 50.1 past the band, 20.0006 and 30.0004 past the end, 40.0002 in the gap, 3.0 / 22.0, 8.0005 / 30.0, 10.0 after the pause, 5.0004 out-and-back, 24.8997 at latitude 13: every one on its side of the constant by at least 0.09 m |

One remark on the vectors, not a defect: `pc-the-middle-of-a-pause-is-not-walked` and `pc-after-the-pause-the-walk-counts-again` carry
walk id 0 in the fourth slot. That is right (0 is no id), but a port that reads the fourth number as *the* id and refuses 0 would not
be caught here; it is caught by `split-walk-id-zero-is-no-id` in the repeat cases, which S4b-FR-13 runs first. Nothing to change.

## 2. Edge cases probed against the reference

| Probe | Result | Verdict |
|---|---|---|
| A place exactly on a walk's **endpoint** (first or last point) | 0 m, `atMs` of that point (t = 0 or 1), one row | As specified; nothing to add |
| **Round cap against flat cap** at a walk's end | A place 20 m past the last point on the line is 20 m away (the clamp gives the endpoint); a flat cap would give a perpendicular distance of 0 and *walked* at any distance along the line | The vector `pc-beyond-the-end-uses-the-round-cap` pins it, the *no clamp* mutation fails it. Good |
| A place **near a resumed (hidden-pause) break** | The two parts are matched separately; the gap is matched by nothing; the time never interpolates across the break (no segment, so no `t`) | Pinned by two vectors. Good |
| **Antimeridian** | A place at longitude 179.9999 against a walk at -179.9999: the local plane gives `(lon - place.lon)` of 360°, so *none* | Irrelevant for India, as the ask says; the text need not mention it |
| A place at a **pole** | Latitude 90 passes the gate; `cos(90°) = 0` collapses every x to 0, so a walk at longitude 90 at latitude 89.9999 reads as 11 m away | Irrelevant; the gate's [-90, 90] is right (a corrupt house location is 91, not 90). Nothing to add |
| **Floating point at 25 / 50 m** | The closest cases are 24.8999 and 25.1, 49.8998 and 50.1; ±0.02 m on either constant changes nothing; the inclusive 25.0 is a unit test, as 5.27.13 step 4 says | Hygiene respected |
| A **one-point walk** | No segment, no row, not counted: *empty* | Pinned (`pc-a-fragment-of-one-point-is-no-walk`) |
| A **zero-length walk** (two points at one spot, 5 min apart: a stay) | One segment of length 0, `t = 0`, distance to the spot, `atMs` of the first point, a row | Right: she stood there. Not a vector; worth one line in step 2 so an implementer does not divide by the zero length (the clamp must treat `L² = 0` as `t = 0`) |
| A **saved walk and the trace overlapping** (the same walk in both) | Two rows for one walk, one `trace`, one `saved`, on the same day: the headline says *and 1 more walks* for a walk that happened once | **Possible on the website.** 5.27.6 saves by moving the walk in one transaction, but `LocalDb` has no multi-store transaction (`putAll` is one store; `put` and `delete` are one transaction each), so a save that writes `saved_walks` and then deletes `trace_points` can be cut between the two (the tab closed, the quota hit) and the walk is then in both stores. Phones: Room does it in one transaction, no double. Fix in the design (section 5, SHOULD-3): the check drops a trace walk whose walk id equals a saved walk's id, and `TraceStore.save` deletes the trace rows **first** and writes the saved row second only after the plan below adds a multi-store transaction to `LocalDb`; until then the de-duplication rule covers it |
| **Time interpolation across a thinning gap** inside a segment (5 min between kept points at a stay, then a 20 m step) | Linear along the segment: a place next to the stay reads the stay's start time | Acceptable and stated by *the time at the nearest point*; no change |
| **Dates in the device's time zone** against the walk's recorded zone | Stated and accepted in 5.27.13 (*Dates*) | Agree. The website helper needs an optional `timeZone` parameter so the unit test (*a walk at 23:50 against two zones*) can run under Vitest, whose process has one zone (SHOULD-5) |
| **Weekday text in hi / ta / te** | `Intl` in Node 24 (the Vitest runtime) and every current browser: en-IN *Wed, 7 Oct*, hi-IN *बुध, 7 अक्टू॰*, ta-IN *அக். 7, புத.*, te-IN *7 అక్టో, బుధ*: Tamil and Telugu put the weekday **last** | Right as a result (each language's own order), but the phones' *twin pattern of the same fields* must be a **skeleton**, not a literal pattern, or the website and the phones disagree in ta and te: Android `DateFormat.getBestDateTimePattern(locale, "EEEdMMM")` / `"EEEdMMMy"`, iOS `setLocalizedDateFormatFromTemplate("EEEdMMM")` (SHOULD-5) |
| `fuzzy` with status `EMPTY` or `NONE` | The reference sets it (the text says the result carries it after the gate); the sheet shows the line only when there is an answer | Say it in one clause (section 5, SHOULD-6) |
| Two walks tie on `atMs` | The later input index first, as written; not a vector | Fine; the random-city test covers the sort |

## 3. The CLOSE band, the *here* flow, the approximate-house note

**The 25-50 m band: sensible, keep it.** The argument in 5.27.13 is right and the owner's own example proves it: a house's pin is
*placed on the map* (a tap, 10-30 m off the gate) and the walk is a 20 m-thinned line of 5-15 m fixes, so a wall at 25 m would say *no* to
a person who walked past the next gate about as often as it says *yes*. Reporting *came within 41 m* in other words is honest, and the
band costs nothing (one comparison; the repeat algorithm never reads it). One thing to fix in the words: `trace.here.close` and
`trace.here.none`/`noneSaved` write *25 m* into the sentence. 5.27.2 names `TOLERANCE_M = 20` as the fallback if the owner's field check
lights up parallel lanes; four strings in four languages would then be wrong. Make it a `{tolerance}` placeholder (MUST-2).

**The *here* flow (15 s, 50 m gate): sensible on the phones, under-specified on the website.** The phones' rule (*the first fix of 50 m
or better; fixes worse are ignored while waiting; at 15 s the best so far decides*) is a stream rule and matches how the Map's location
button already works. The website says `getCurrentPosition` with `timeout: 15000`, which returns **one** fix: a first fix of 80 m
(indoors, a laptop) gives `IMPRECISE` at once although a 12 m fix would have come three seconds later, and the browser's single
`timeout` fires only when *no* fix arrives, so *at 15 s the best so far decides* has no meaning there. Two ways to make the three
stacks one: `watchPosition` for at most 15 s with `clearWatch` on the first fix of 50 m or better (the same words as the phones; the
cost is nil; it is still *one fix used*), or keep `getCurrentPosition` and say honestly that the website takes the one fix the browser
gives. I recommend the first (MUST-1), because TC-U-154's `PlaceCheckGateTest` is written once for all three stacks. Either way the
existing `shared/locate-once.ts` is the seam (section 4.6).

**The approximate-house note: sensible.** An `APPROX` house is an area; a distance to its centre means nothing (q17's alternative).
The website's house form already knows `locationSource` (`house-detail-page.ts`, the *Approximate location* switch), so the rule is
one `@if`. The button is hidden for a house with no location at all; on the website a new house (`/houses/new`) has no stored
walks link to it and no saved location: hide it there too (`isNew()`), one clause in 5.27.13's table (SHOULD-7).

## 4. The website: how the trace v2 and the check fit the real code

### 4.1 What the website has today (facts the plan rests on)

- **The Map page is one 894-line component** (`pages/map/map-page.ts`) holding the map, the house layer, the list, the search, the cost
  filters, add mode, the share handover and the offline overlay; its child controls are small standalone components placed in the
  map's action row (`pages/map/offline-save.ts`, a button plus a `<dialog>`). New map features go in that shape, not into `MapPage`.
- **Sources and layers are added on every `style.load`**, not on `load` (an offline start reloads the style when the connection
  returns, `watchMapStyle`), and `createMlMap` registers `applyIndiaBoundaries` on `style.load` **before** the page's own listener, so
  the page's layers always land on a style that already carries the boundary layers (ADR-22). The house layer is `houses-circles`
  on source `houses`; it is added last, so anything added with `beforeId: 'houses-circles'` sits under the markers and above the
  boundaries.
- **The house page has its own 240 px map**, `shared/location-map.ts` (`app-location-map`), with a DOM `Marker`; it exposes no map.
- **One location seam exists**: `shared/locate-once.ts` (`getCurrentPosition`, `LOCATE_OPTIONS = { enableHighAccuracy, timeout: 15 s,
  maximumAge: 60 s }`, a `gone()` guard so a late answer never acts on a page that was left); `shared/map-center.ts` maps a
  `GeolocationPositionError` to the keys `map.locationBlocked` / `map.locationUnavailable` (`locationErrorKey`). MapLibre's own
  `GeolocateControl` is on the Map (its fix is never reused by the app).
- **IndexedDB**: `data/local-db.ts`, `DB_VERSION = 2`, five stores, `upgradeLocalDb(db, oldVersion, tx)` one step per version, pinned in
  `local-db.spec.ts` with a fake; `MemoryDb` for jsdom and for a browser that refuses IndexedDB (`storageProblem` = `unavailable` or
  `blocked`); `onblocked` falls back to memory; `onversionchange` closes and sets `closedByNewerTab` (the reload banner). The
  interface has `get`, `getAll`, `getAllByIndex`, `put`, `putAll` (one transaction), `delete`, `clear(store?)`, `close`: **no cursor, no
  range, no multi-store transaction, no batch delete.** `clear()` with no store empties every store in `STORE_NAMES`, which is what
  *Remove all Doorprints data from this browser* calls (`LocalStore.clearEverything`). Settings live in the `settings` store under
  `SETTING_KEYS` (`'storage.persist-asked'`, `'units.length'`, `'viewings.remind'`: dotted, short).
- **i18n**: flat dotted keys grouped by screen (`area.thing`; 46 three-level keys such as `map.sort.recent`; **no four-level key**);
  hi/ta/te must hold exactly the same keys and placeholders (`dictionaries.spec.ts`); `TranslationService.list()` joins with
  `list.two`/`list.three`/`list.middle`; dates go through `dateOnly`/`timeOnly`/`dateTime` (Intl; `tools/check-templates.mjs` fails a
  `date` pipe given a language). Android's twin is `formatDate(epochMillis, language, withTime)` in `:ui` `Format.kt` (expect/actual).
- **Accessibility**: one app-wide polite live region in the shell (`app.ts`, fed by `core/announcer.service.ts`, with `cancel(msg)`);
  errors use `role="alert"` in place; the jsdom a11y sweep is `shared/testing/a11y.ts`.
- **Themes**: `prefers-color-scheme: dark` only (no theme attribute), tokens on `:root` in `styles.css`; the map tiles stay light in both.
- **Offline/PWA**: `public/sw.js` precaches every file of the build (lazy chunks included) and never caches tiles; the Map page shows an
  overlay without a style and still offers *Add at my location*.
- **CSP** (`web/firebase.json`): `worker-src 'self'` (MapLibre's worker is copied to the site root), `connect-src 'self' https: ...`,
  `Permissions-Policy: geolocation=(self)`. Nothing blocks a trace, a check or a Web Worker; nothing else in the headers is needed.
- **Tests**: Angular's Vitest builder on jsdom; specs beside the code; shared vectors are imported as JSON modules
  (`import vectorsJson from '../../../../../docs/schemas/drive-vectors.json'` in `data/drive/drive-vectors.spec.ts`; `resolveJsonModule`
  is on in `tsconfig.spec.json`); `tools/check-specs.mjs` refuses a spec without a production import or a test without an assertion.
- **Live UI**: `tools/live-ui/live-ui.js` makes Playwright contexts in `newCtx()` (no geolocation yet) and has a mobile pass with real
  device profiles; the India boundary check downloads the GeoJSON.
- **Guide**: `guide/docs/*.md` in English with `guide/i18n/{hi,ta,te}/` twins and four `mkdocs*.yml`; `hunt-mode.md` is the page the
  trace belongs beside; pictures are made by `tools/guide-shots`.

### 4.2 The plan, file by file, in order

Order follows N20: FR-13 (algorithm) → FR-17 (the website's trace, stores first) → FR-15's website part (layers, card, sheet) →
FR-24 (the check) → FR-18 (guide). Each row is one pull request or part of one; *risk* names what can go wrong.

| # | Ticket | File (new unless *edit*) | What | Risk and how it is kept small |
|---|---|---|---|---|
| 1 | FR-13 | `web/src/app/shared/trace-geo.ts` | `K`, `localXY(place, point)`, `segmentLengthM`, `distanceToSegmentM(q, a, b)` with the clamp (`L² = 0` gives `t = 0`), `interpolatedAtMs`. **The one plane for both algorithms** (`trace-repeats.ts` and `trace-place-check.ts` import it; the vector tests of both run against it) | None; the spec's *two engineers* rule is Kotlin against TypeScript, not within a stack |
| 2 | FR-13 | `web/src/app/shared/trace-repeats.ts`, `trace-repeats.spec.ts`, `trace-repeats-vectors.spec.ts` | 5.27.3 as planned; `TRACE` constants object (`toleranceM`, `nearBandM`, `maxFixAccuracyM`, ...) exported from `trace-geo.ts` or a `trace-constants.ts`; the vectors spec imports `../../../../docs/schemas/trace-repeat-vectors.json` (four `..` from `shared/`) and asserts `vectors.constants` equals the code's, so the drift test covers `nearBandM` and `maxFixAccuracyM` from day one | `it.each` over 37 cases: `check-specs.mjs` sees the `expect` inside the body (checked: its regex accepts `it.each`) |
| 3 | FR-17 | *edit* `web/src/app/data/local-db.ts`, `local-db.spec.ts` | `DB_VERSION = 3`; `StoreName` gains `'trace_points' \| 'saved_walks'`; `STORE_NAMES`, `STORE_KEY_PATH` (`id`, `id`), `STORE_INDEXES` (`['walk']`, `['houseId']`); `upgradeLocalDb` step `if (oldVersion < 3)` creating both stores with their indexes (the `UpgradeDb`/`UpgradeStore` fakes already support it); **rewrite the stale header comment** (*version 3 (slice 1) will hold the house's own new values*); add **`deleteAll(store, keys)`** (one transaction, the twin of `putAll`) and **`transaction(stores, fn)`** or at least `moveWalk(points, saved)` as one `readwrite` transaction over both stores, so a save is atomic; `MemoryDb` follows from `STORE_NAMES` automatically and `clear()` already empties the new stores (so *Remove all data* needs no change: assert it) | **The upgrade with several tabs:** a tab at version 2 from a current build closes its database on `versionchange` and shows *reload*; a tab from a build before S4b-BL-71 blocks and the new tab falls back to memory (`onblocked`): the trace card then shows the existing *nothing is kept* warning (`storageProblem === 'blocked'`). Test both with the fake. **Safari private / Firefox never-remember:** memory; the card says a walk lives for this page only. **Quota:** `put` rejects with `QuotaExceededError`; `errorMsg` already maps it to `error.storageFull`; the recorder must stop the walk and say so, never retry in a loop |
| 4 | FR-17 | `web/src/app/data/trace-store.ts`, `trace-store.spec.ts` | `TraceStore` over `LocalStore.db()`: `putPoint`, `walks(sinceMs)` (30 days: `getAll('trace_points')`, group by `walk`, sort by `at`, split by 5.27.3 step 1), `savedWalks()`, `savedWalksOf(houseId)` (index), `saveWalk` (atomic, see 3), `deleteWalk`, `deleteWalksOfHouse` (called from `LocalStore.deleteHouse` beside `deletePhoto`), `deleteAllSaved`, `prune(now)` (`getAll` then `deleteAll`), `clearTrace`; settings `trace.on`, `trace.look`, `trace.alert`, `trace.keepAwake`, `trace.askedUpTo` added to `SETTING_KEYS` (`'trace.on'` etc. fit the convention) | `getAll` reads the whole store: 30 days of points is a few thousand rows (fine); 200 saved walks of 5 000 plain-array points is up to about 8 MB structured-cloned in one call: read them **per house or per id in a loop** when the Map draws, as 5.27.13's *one saved walk at a time* says. The not-in-any-export source test (TC-U-152) greps `trace_points`/`saved_walks` under `export/`, `data/drive/`, `core/ai/`, `sync*.ts`: run it from the first commit |
| 5 | FR-17 | `web/src/app/core/trace-recorder.service.ts`, spec | `watchPosition` only after *Start a walk*, the 50 m gate and 20 m / 5 min thinning (a twin of `TrackRecorder`), walk ids, `resumed` after a hidden pause of more than 5 min (`document.visibilityState` and `visibilitychange`, as `pages/data/drive/drive-sync.ts` lines 114-127 already do), the Wake Lock (`navigator.wakeLock?.request('screen')`, released by the browser when hidden, re-requested on visible), the beep (`AudioContext` created and resumed inside the *Start a walk* click, two 0.15 s 880 Hz tones; `navigator.vibrate` as the fallback), the banner through a signal the Map card renders with `role="alert"`, `RepeatAlert` per kept point. Exposes `liveWalkId()` (the check leaves it out for *here*) | A service that holds a `watchPosition` id across route changes: the Map page may be left mid-walk; the service, not the page, owns the watch and the lock, and the page's card re-binds on return. The `AudioContext` gesture rule is already stated in 5.27.5; no change |
| 6 | FR-15 (web) | `web/src/app/shared/trace-style.ts`, spec | `TRACK_SOURCE = 'track'`, `TRACK_LAYER`, `TRACK_REPEAT_LAYER`, `TRACK_CHECK_SOURCE = 'track-check'`, the three check layers (`track-check-halo`, `track-check-line`, `track-check-label`), `trackGeoJson(walks, shown)`, `trackLayerJson()`, `trackRepeatLayerJson(look)`, `repeatWidthExpression(look)`, `checkLayersJson()`; the widths table of 5.27.4 pinned | Pure data; no risk |
| 7 | FR-15 (web) | `web/src/app/pages/map/trace-layers.ts` (a class, not a component) | `TraceLayers.attach(map)`: on **every** `style.load` adds `track`, `track-check` sources and the layers **with `beforeId: 'houses-circles'`** (so under the markers, above India's boundary layers, which `applyIndiaBoundaries` has already placed; the check layers above `track-repeat-line`), `setWalks(features)`, `setLook(look)` (`setPaintProperty` + `setLayoutProperty('visibility')`), `setCheck(features \| null)`, `fitTo(bounds, padding)`; `MapPage` calls `attach` in its own `style.load` handler right before it adds `houses-circles` (so the house layer exists first; or add the track layers after it with `beforeId`) | **ADR-22 / TC-M-25:** the boundary layers are never touched (no `moveLayer`, no `setFilter`, no id of theirs used); the new layers sit above them by construction. One base-map change (new layers) means **TC-M-25 is re-run once** (CLAUDE.md), as S4b-FR-15 already plans. **Offline reload:** everything is re-added on `style.load`, as the house layer is |
| 8 | FR-15 (web) | `web/src/app/pages/map/trace-card.ts` (standalone component, `app-trace-card`) | The *Trace my path* card of 5.27.1/5.27.8: the switch, *How repeated paths look* (radio group), *Warn me when I walk a path again*, *Keep the screen on while I walk*, *Saved walks: n* + *Delete all saved walks* (confirm through `ConfirmService`), *Clear the path*, *Start a walk* / *Finish walk*, the only-while-open sentence, the paused sentence (live region: the app `Announcer`, not a second region), the shared-browser sentence, the alert banner (`role="alert"` + *Dismiss*). Rendered in `MapPage`'s `.panel` section **above `.panel-head`** as a `<details>` collapsed by default on phones (the list is the page's job), open on wide screens | **Where it lives in navigation:** the owner's spec puts it on the Map page, not on *Your data* (the phones have it under Settings > Hunt mode; the website has no Hunt mode). Keep that: *Your data* gets only the two numbers it already lists for other stores (*Saved walks: n*, *Delete all saved walks*) inside the existing `storage-heading` card (section 4.3). **Phone peek:** `watchStack()` measures `.panel-head` into `--map-peek` so the list heading shows under the map; a card above the heading pushes the heading down. A collapsed `<details>` is one line (fits the peek); when opened the person is in the card, which is what she asked for |
| 9 | FR-15 (web) | `web/src/app/pages/map/walk-end-sheet.ts` (`app-walk-end-sheet`) | *Save this walk?* as a `<dialog>` (the `OfflineSave` shape: `showModal`, focus to the title, Esc = *Keep for 30 days*), the house picker inside it (nearest within 30 m preselected, within 150 m listed, the search box over `searchText` of `map-list.ts`) | The picker reuses `searchText`; 5.27.6 says the search rule of S4b-FR-1 does not apply (a walk is not a house field): right |
| 10 | FR-15 (web) | *edit* `pages/house-detail/house-detail-page.html/.ts` | The *Saved walks* card (rows, *Show on map*, *Delete walk*) under the location card; *Show on map* draws the walk on the page's own `LocationMap` (see 11), not a navigation | None once 11 exists |
| 11 | FR-15 (web) | *edit* `web/src/app/shared/location-map.ts` | An optional `overlay = input<GeoJSON.FeatureCollection \| null>(null)` and `overlayLayers` input; on every `style.load` the component adds a `track-check`/`track` source and the layers from `trace-style.ts` (reusing `TraceLayers`) and an optional ring `Marker`; `fitTo` when the overlay changes. Its single DOM marker stays | The component is used by the house form and the areas' `PointPicker`; the new inputs default to null, so nothing changes for them. jsdom: `createMlMap` returns null there, so the overlay code must guard `map === null` as the rest does |
| 12 | FR-24 | `web/src/app/shared/trace-place-check.ts`, `trace-place-check.spec.ts`, `trace-place-check-vectors.spec.ts` | `placeCheck(place, walks, fixAccuracyM?)` from 5.27.13 over `trace-geo.ts`; `PlaceStatus`, `PlaceRow`, `PlaceCheckResult`; the cheap box rejection (`NEAR_BAND_M` grown box) and the random-city equality test; `matchedStretch(walk, row)` (60 m each side along the walk, within one part); the vectors spec runs the 21 cases and the drift test | **Dependency on FR-13 is only `trace-geo.ts`** (row 1): the check can be written as soon as row 1 exists, in parallel with the repeat algorithm |
| 13 | FR-24 | `web/src/app/shared/trace-place-text.ts`, spec | `placeCheckText(result, place, i18n, now, timeZone?)`: the headline (distinct days newest first, at most three, `i18n.list()`, `and {n} more`, the largest distance among the listed days, `ceil` and at least 1 m), the rows (at most five, `and {n} more walks`, *saved walk*), the sentences for the other states, `fuzzy`, the website's *only while this page was open* line; dates through a new `TranslationService.dateWithWeekday(epochMs, timeZone?)` (`weekday: 'short', day: 'numeric', month: 'short'`, plus `year: 'numeric'` when not the current year); distances through a new `TranslationService.metres(n)` (`Intl.NumberFormat` `style: 'unit', unit: 'meter'`, as `size()` does for bytes) | Pure; the four-language date test runs here. `check-templates.mjs` is satisfied by construction (no `date` pipe) |
| 14 | FR-24 | `web/src/app/pages/map/place-check.ts` (`app-place-check`, the control) | In `.map-actions` beside `app-offline-save`: the button *Have I been here?* (footprints glyph, label hidden on phones the way `OfflineSave` hides its label, 44 px target) opening a `<dialog>` with two choices (*Where I am now*, *A spot on the map*), the permission sentence **shown every time** in that dialog (no flag), and the *Finding your location...* state with *Cancel*; *here* uses `locateOnce` with options `{ enableHighAccuracy: true, maximumAge: 0, timeout: 15000 }` (row 16); *A spot on the map* closes the dialog and asks `MapPage` for pick mode (row 15). On a result it closes the dialog and hands the result to the panel (row 17) and the layers (row 7) | The dialog is modal only while choosing and locating, so the halo is never under a backdrop. Outputs: `picked`/`result` signals, no router state |
| 15 | FR-24 | *edit* `pages/map/map-page.ts/.html/.css` | `addMode` becomes `pickMode = signal<'house' \| 'check' \| null>(null)` (the crosshair `div`, the `applyGestures` call, Esc, the hint and the `#place-here` button read it: *Place here* for a house, *Check this spot* for the check, `map.pickHintShort` / `trace.here.pickHint`); `placeAtCenter()` branches on the mode; the `style.load` handler calls `TraceLayers.attach`; the `fitToHouses` padding (`fitPadding`) is reused for the check's fit. **No new query parameter, no `history.state`**: the check never goes through `createAt`'s `lat`/`lon` query (that path is for a new house, where the coordinates are data the person is about to save) | **Risk: `MapPage` grows.** Keep every new line a one-line delegation; the three new child components own the behaviour. **Cooperative gestures:** pick mode disables them (as add mode does), so one finger moves the map under the crosshair, and they come back on exit; same code. **Small phones:** the crosshair is already clear of the bottom row and the control column; the check adds a fourth action button to the column: measure on the 360x640 profile of the live-ui mobile pass (the column height is `--map-stack-h`, which lifts MapLibre's controls, so nothing overlaps, but the map's `min-height` rule (`196px + stack`) grows by one button) |
| 16 | FR-24 | *edit* `web/src/app/shared/locate-once.ts` | An optional `options: PositionOptions = LOCATE_OPTIONS` parameter, and (if MUST-1 is taken) a sibling `locateBest(handlers, { maxWaitMs: 15000, maxAccuracyM: 50 })` that runs `watchPosition`, keeps the best fix, stops at the first of 50 m or better or at 15 s, and resolves with `fix \| 'timeout'`; the same `gone()` guard | None for the three existing callers (the default keeps `LOCATE_OPTIONS`) |
| 17 | FR-24 | `web/src/app/pages/map/place-check-panel.ts` (`app-place-check-panel`) and `web/src/app/pages/house-detail/house-check-card.ts` | The result **panel**: an `h2` with `tabindex="-1"` that takes focus on arrival (which also scrolls it into view on a phone), the headline and rows as text, `trace.here.privacy`, the actions *Show on map* (`fitTo` the stretch with `fitPadding`; on the house page the page's own `LocationMap` already shows it), *Check again* (*here* only), *Close* (focus back to the opener, `announcer.cancel(headline)`, `setCheck(null)`). The headline is announced **once** through the app `Announcer` (the shell's polite region), not a second `aria-live` region on the page. Placed at the top of `MapPage`'s `.panel` section; on the house page as a card *Did I walk past this house?* under the location card, with the button, the `APPROX` note, the hidden state for a new house, and the answer in place | **Privacy on the website (MUST-6):** the shell's live region keeps its last text in the DOM until the next announcement, so *Close* must `cancel` it; the panel's result is a component signal, never `history.state`, `sessionStorage`, `ListReturn` or a query param (the source test of TC-U-154 greps for those names in the four check files) |
| 18 | FR-24 | *edit* `i18n/en.ts`, `hi.ts`, `ta.ts`, `te.ts` | The `trace.here.*` keys of 5.27.13 minus `and2`/`and3` (use `list.*`) and with `a11y.stretch` renamed `stretchA11y`; `{tolerance}` in `close`, `none`, `noneSaved`; `metres` not needed (Intl unit) | `dictionaries.spec.ts` enforces parity; hi/ta/te *under review* |
| 19 | FR-24 | *edit* `tools/live-ui/live-ui.js` | `newCtx` gains `permissions: ['geolocation']` and `geolocation: { latitude, longitude, accuracy }` (Playwright's `browser.newContext` options; `ctx.setGeolocation` to move); a `--trace` run after the deploy (as `--hunt` is proposed in S4b-FR-21): *Start a walk*, 30 steps of 20 m along a street with `setGeolocation` and `page.clock`, *Finish walk* → *Keep for 30 days*, then *Have I been here?* > *Where I am now* on the street (the headline contains *within* and today's weekday) and 100 m off it (the *none* sentence), the network panel asserted empty for the click (`page.on('request')` counting non-tile requests), the mobile pass taking a screenshot of the panel on the 360x640 profile. No CSP bypass needed | Seeding IndexedDB by hand would couple the test to store names; stepping a real walk uses only the UI. TC-M-26's rule: run after a `web/**` merge deploys |
| 20 | FR-18 | `guide/docs/hunt-mode.md` (+ `guide/i18n/{hi,ta,te}/hunt-mode.md`) or the planned *Your paths* page and the four `mkdocs*.yml` navs | A section *Have I been here?*: the button, the three places, *close* against *walked*, that nothing is kept or sent, that the website's answer covers only walks recorded while a page was open, that the location button may use the phone's network service. Pictures: LATER (a map picture needs the tile server; `tools/guide-shots` fakes Drive, not tiles) | None |

### 4.3 The settings card, *Your data* and navigation

The spec puts the trace's settings on the **Map page's card** (5.27.1, 5.27.8). That fits the website: there is no Settings page;
per-device choices sit where they act (the offline *Save this area* is on the Map, the offline *areas* list is on *Your data*). So:
the card with the switch, the look, the alert, the wake lock, *Start a walk* / *Finish walk*, *Clear the path* and the two saved-walk
lines is `app-trace-card` on the Map (row 8); *Your data*'s `storage-heading` card gains two lines (*Saved walks: n* and *Delete all saved
walks*, TC-U-152 already names `data-page`) and `app-offline-areas` is the pattern for a card component if the lines grow. The
`nav-section.ts` rules need nothing: the Map is `/`, the house is under it, *Your data* is `/data`. The check has **no setting**, so it
touches neither card.

### 4.4 The map layers, ADR-22 and layer order

Order on the style, bottom to top: Liberty's own layers · India's boundary overlay (`in-boundary-world`, `in-boundary-claim`,
`in-boundary-state`, placed by `applyIndiaBoundaries` on every `style.load`, before the page's listener runs) · `track-line` (base,
purple) · `track-repeat-line` (orange dashes) · `track-check-halo` (a wide white casing with a thin dark outer line: a form) ·
`track-check-line` (the stretch, purple, over the casing) · `houses-circles` · the ring `Marker` (DOM, `pointer-events: none`,
`aria-label` = *You are here* / *This house* / *This spot*) and the label. Every page layer is added with `beforeId:
'houses-circles'`; nothing calls `moveLayer`, `setFilter` or `setLayoutProperty` on a boundary layer. The ring is a DOM marker rather
than a circle layer so that it is a different **form** from the `APPROX` hollow ring of `house-markers.ts` (which is a circle layer
without fill): a hollow ring **with a centre cross** drawn as inline SVG, 28 px, theme-independent (the tiles are light in both themes).
`TC-M-25` is re-run once for the new layers (CLAUDE.md), as S4b-FR-15 plans. The live-ui `boundaries()` check is unaffected.

### 4.5 Small phones: the list peek, cooperative gestures, fit padding

The phone layout (`max-width: 760px`) has the map at the top (`height: max(min(256px, 100cqh), calc(100cqh - var(--map-peek)))`),
the bottom row with the legend and the action column (`--map-stack-h`), and the list under it. The check adds one action button to
the column (hidden label, as `OfflineSave`), so `--map-stack-h` grows by one 44 px button and the map's `min-height` with it: fine on a
360x640 phone (measured by the mobile pass). The crosshair mode reuses add mode's placement (the hint at the top of the map, the
confirm button lowest in the column). The result panel sits at the top of the list section, so on a phone the person reads the answer
under the map and *Show on map* fits the stretch into the map above with `fitPadding(bottomControls, stackHeight, w, h)`, which already
keeps the bottom row and the control column clear. Cooperative gestures: pick mode disables them exactly as add mode does
(`applyGestures`), so one finger moves the map under the cross.

### 4.6 Geolocation, permission, Wake Lock, visibility, sound

Reuse `locateOnce` (row 16) for *here*: it already drops a late answer when the page was left, and `locationErrorKey` already
yields the blocked/unavailable keys (the spec's `trace.here.deniedWeb` and the existing `trace.web.unavailable` map onto them; keep
`deniedWeb` because its sentence says *to check where you are*). The permission is asked by the browser inside the click, as the
spec says; a page can read `navigator.permissions.query({ name: 'geolocation' })` to show *blocked* without a prompt, but it is not
needed. The Wake Lock, `visibilitychange` and the `AudioContext` belong to the recorder (row 5), not to the check: the check asks for
one fix and plays no sound. The only visibility rule the check has: a `locateBest` still waiting when the page is hidden keeps waiting
(the browser pauses fixes) and the 15 s timer decides; no special case.

### 4.7 IndexedDB version 3

Row 3 says what to change. Three more notes. (a) **Migration safety:** the step creates stores only; existing stores are untouched,
so a failed upgrade (quota during `versionchange`) aborts the whole transaction and the database stays at 2, which `openLocalDb`
reports as `blocked` → memory for the session; the person loses nothing. (b) **Testing the fake:** `local-db.spec.ts` pins each step
by `oldVersion`; add *from 0*, *from 2* and *from 3 (no-op)* cases and one that asserts `STORE_NAMES` has seven entries so `clear()`
covers the new stores. (c) **The `records` precedent:** the spec's row shapes (`{id, walk, at, lat, lon, acc, resumed?}`) fit
`STORE_KEY_PATH`; keep `resumed` absent when false (as the spec says) so old rows need no rewrite.

### 4.8 i18n

Keys: `trace.here.<name>` is a three-level key, which matches `map.sort.recent` and the 45 others; `trace.here.a11y.stretch` would be
the dictionary's only four-level key: rename it `trace.here.stretchA11y` (MUST-3). `trace.here.and2`/`and3` duplicate `list.two`/
`list.three`, which `TranslationService.list()` already applies with each language's own words (Hindi joins with *और*): drop them and
say *joined by `TranslationService.list`* (MUST-3). Dates: `dateWithWeekday` (row 13) with `Intl`, which produces each language's own
order (Tamil and Telugu put the weekday last); the phones must use a skeleton (`EEEdMMM`) so the three agree (SHOULD-5). The Android
resource names follow docs/05 §9.1 (`trace_here_walked`), with `%1$s` placeholders in the order given; `StringParityTest` holds them.

### 4.9 Accessibility of a map-only cue, and themes

The text is the whole answer (the headline and the rows list every walk the headline covers, so a person who cannot see the map loses
nothing); the halo and the ring are decoration with the `stretchA11y` description on the map region's `aria-describedby`. The headline
goes through the app's polite region once; focus moves to the panel's heading; *Close* returns focus to the opener and withdraws the
announcement. Buttons keep the site's 44 px targets. The jsdom sweep (`shared/testing/a11y.ts`) runs over the dialog, the panel and the
house card in four languages. Themes: the panel uses the tokens (`--surface`, `--text`, `--border-strong`, `--warn-*` for the fuzzy line);
the map's halo is white-on-light-tiles in both themes, so no theme branch.

### 4.10 Offline and the PWA

The check's code ships in the Map's lazy chunk (and the house page's), which `sw.js` precaches, so a house or a spot answers offline
with the stored walks; *here* works offline too (geolocation needs no network; the OS may use its own). Without a map style (offline
overlay) the Map page still shows the panel's text; the halo simply has no map to draw on, and *Show on map* is hidden while
`!mapUsable()`. No tile, no API call, nothing to cache: the service worker needs no change.

### 4.11 Performance: no Web Worker for the check

The check is one pass over segments with a box rejection. 20 000 segments is about 2-5 ms in V8 on a mid phone; the worst case the spec
allows (200 saved walks of 5 000 points plus the trace, about a million segments) is 50-150 ms, and the box rejection cuts most of
it before the trigonometry. A Worker would be possible (Angular's application builder bundles `new Worker(new URL('./x.worker',
import.meta.url), { type: 'module' })`, the CSP has `worker-src 'self'`, `sw.js` precaches every emitted file) but costs a second
module graph for a few milliseconds. Recommendation: synchronous, and the loop over saved walks yields between walks (`await
scheduler.yield?.() ?? new Promise(r => setTimeout(r))` every 20 walks) so the page stays responsive; the spec's *off the main thread*
becomes *without blocking the page* for the website (SHOULD-1). The pairwise **repeat detection** (FR-17) is where a Worker may be
needed; decide there from the random-city timing, not here.

### 4.12 Tests and where they go

Specs beside the code, as everywhere in `web/src/app`; the vectors spec imports the JSON module as the Drive one does (row 2). The
source tests of TC-U-154 for the website: a Vitest spec that reads the four check files (`shared/trace-place-check*.ts`,
`shared/trace-place-text.ts`, `pages/map/place-check*.ts`, `pages/house-detail/house-check-card.ts`) with `fs` and asserts none mentions
`fetch(`, `XMLHttpRequest`, `sendBeacon`, `history.`, `sessionStorage`, `localStorage`, `queryParams`, `ListReturn`, `console.` or a
store write; the behaviour test runs the check with `fetch` and `XMLHttpRequest` replaced by throwing stubs; the *only caller* grep
finds `placeCheck(` in exactly the panel/control files. `check-specs.mjs` wants a production import and an `expect` per test: the
source test imports nothing from production, so it carries `// check-specs: allow-no-production-import (a source test)`.

## 5. Spec changes, with the text (MUST before S4b-FR-24 starts; SHOULD with it; LATER after)

**MUST-1 (5.27.13, *Getting here*, the website bullet).** Replace *`navigator.geolocation.getCurrentPosition` with
`{ enableHighAccuracy: true, maximumAge: 0, timeout: 15000 }`, called from the button's click (a user gesture)* by:
> **Website:** the same rule as the phones through `shared/locate-once.ts`: `watchPosition` with `{ enableHighAccuracy: true, maximumAge: 0 }`
> started inside the button's click (a user gesture), stopped (`clearWatch`) at the first fix of 50 m or better or after 15 s, when the best
> fix so far decides (worse than 50 m gives `IMPRECISE`, none gives the timeout words). One fix is used; none is stored.

(If the owner prefers one `getCurrentPosition`, then instead change TC-U-154's gate test to say *the website takes the one fix the
browser returns within 15 s* and add that sentence here. One of the two must change.)

**MUST-2 (5.27.13 strings and 5.27.9's rule).** `trace.here.close` → *No walk of yours passed within {tolerance} of {place}, but one came
within {distance} on {dates}.*; `trace.here.none` → *No walk of yours passed within {tolerance} of {place} in the last 30 days.*;
`trace.here.noneSaved` the same with *or in your saved walks*. Add under the table: *`{tolerance}` is `TOLERANCE_M` formatted as a
distance (so the 20 m fallback of 5.27.2 changes no string).*

**MUST-3 (5.27.13 strings).** Delete `trace.here.and2` and `trace.here.and3`; in *Headline* replace *joined by the language's list words
(A and B, A, B and C)* by *joined by the dictionary's existing list words (`list.two`, `list.three`; website `TranslationService.list`,
phones `joinList`)*. Rename `trace.here.a11y.stretch` to `trace.here.stretchA11y` (the website's dictionary has no four-level key;
`map.sort.recent` is the depth in use).

**MUST-4 (5.27.13, the website bullet).** Replace *with one sentence first the first time* by *with one sentence in the choice dialog,
every time (the check keeps no flag)*. The sentence stays `trace.here.permissionExplain`.

**MUST-5 (5.27.13, *Actions* and *The map shows the matched stretch*).** Add: *On the website the house page's answer and its halo and
ring are drawn on the house page's own map (`LocationMap`), and the Map page's on the Map; *Show on map* never navigates with a
result, and no result, place or distance is ever put in a URL, in `history.state` or in session storage.*

**MUST-6 (5.27.13, *Screen readers*; PRV-032; TC-U-154).** Add after *focus moves to the sheet's title*: *The website announces the
headline once through the app's polite live region (`Announcer`) and withdraws it when the panel closes (`Announcer.cancel`), so the
sentence is not left in the page.* TC-U-154 gains: *the web panel's close leaves the shell's live region empty.*

**SHOULD-1 (5.27.13, *Cost*).** *The check runs off the main thread* → *The check runs off the main thread on the phones and without
blocking the page on the website (a synchronous pass that yields between saved walks; no Web Worker: a few milliseconds).*

**SHOULD-2 (5.27.13, *Inputs*, and S4b-FR-24's dependency column).** Add: *The website has no trace until S4b-FR-17's store exists, so on the
website nothing of the check lands before FR-17; the `placeCheck` function itself needs only the shared plane and distance
(`shared/trace-geo.ts`), which S4b-FR-13 writes first and both algorithms use.*

**SHOULD-3 (5.27.13, *Inputs*).** Add: *A trace walk whose walk id equals a saved walk's id is left out (a save that was cut between its two
writes on the website); the store's save deletes the trace rows and writes the saved row in one transaction where the platform has one.*

**SHOULD-4 (5.27.13, step 2).** Add after *the segment clamped at its ends*: *(a segment of length 0, a stay, takes `t = 0`)*.

**SHOULD-5 (5.27.13, *Dates*).** Replace *phones' twin pattern of the same fields* by *phones: the locale's own pattern for the skeleton
`EEEdMMM` (`EEEdMMMy` for another year): Android `DateFormat.getBestDateTimePattern`, iPhone `setLocalizedDateFormatFromTemplate`;
Tamil and Telugu put the weekday last, and the three stacks must agree*; and add *the website helper takes an optional time zone for
its unit test*.

**SHOULD-6 (5.27.13, step 1).** *The result carries `fuzzy = fixAccuracyM > TOLERANCE_M`* → *... for every status but `IMPRECISE` and
`INVALID_PLACE`; the sheet shows the fuzzy line only with a `WALKED`, `CLOSE` or `NONE` answer.*

**SHOULD-7 (5.27.13, the *A house* row).** Add: *A house not yet saved (the website's `/houses/new`) hides the button.*

**SHOULD-8 (docs/03 §4.2.1 or the website row of §6.2).** Name the website files of section 4.2 (`shared/trace-geo.ts`,
`data/trace-store.ts`, `core/trace-recorder.service.ts`, `shared/trace-style.ts`, `pages/map/trace-layers.ts`, `trace-card.ts`,
`walk-end-sheet.ts`, `place-check.ts`, `place-check-panel.ts`, `pages/house-detail/house-check-card.ts`, `shared/trace-place-check.ts`,
`trace-place-text.ts`) so S4b-FR-15/17/24 do not each invent them; and the `LocalDb` additions (`deleteAll`, a two-store transaction).

**SHOULD-9 (`web/src/app/data/local-db.ts`, with S4b-FR-17, not a spec change).** The header comment *version 3 (slice 1) will hold the house's
own new values* is stale and contradicts docs/03 §6.2; rewrite it in the version-3 change.

**LATER-1.** `trace.here.rowsMore` *and {n} more walks* with n = 1 reads *1 more walks*; a `rowsMoreOne` key, or *{n} more* without the noun,
when hi/ta/te are reviewed.

## 6. Product gaps and improvements

| Rank | Item | Recommendation |
|---|---|---|
| MUST | None beyond section 5 | The feature as asked is complete |
| SHOULD | **A *Walked past* filter on the house list** (several houses at once) | Not now: it is the check run for every house without a press, which PRV-032's *on demand only* forbids by construction, and it would turn the list into a map of where she walked. If wanted, an explicit button *Mark the houses I walked past* that runs once, shows transient chips (never stored) and clears on leaving the page; an owner decision, after FR-24 (q18) |
| SHOULD | **Where I have NOT been** | Already the Map: the drawn trace is the coverage; a *walked once / more than once* legend exists. Nothing to add |
| LATER | **A *visited* badge on houses the trace passed** | A stored badge is a derived fact from location history written into a house row, which sync, export, share and AI then carry (PRV-028 broken by a flag). No, unless computed live and never stored (then it is the SHOULD above) |
| LATER | **The answer in the viewing notes** | No by default: notes sync, export, share and reach the AI. If a person wants it, she types it; the app must not offer a one-tap copy into a synced field (T-I42) |
| LATER | **Sharing or copying the answer** | No share button, no clipboard: the answer is location history (T-I42). The shared-listing file (5.28) must never gain it |
| LATER | **Auto-suggesting a Visit from a stay** | Hunt mode's stay detector (docs/03 §7.3) already does this on the phones; the check must not grow a background job. Out of scope, by the owner's own *on demand* rule (q17) |
| LATER | **Checking a house from the Map's popup** (hover card) | A third entry point, cheap once the panel exists; after the owner has used the two |
| LATER | **Hunt mode on the website** (5.27.12) | Unchanged: proposed, not scheduled |

## 7. Privacy: leak paths checked

| Path | Finding |
|---|---|
| A notification | None is sent (no sound, no Web Notification, no push): right |
| Screenshots, recents | The same exposure as the trace (T-I29, RR-31); the app lock on the phones; the website has none, as for the whole site. Stated |
| The AI assistant | `core/ai/**` builds from houses; the check writes nothing to a house. TC-U-151/152's grep extends to the check's files (section 4.12) |
| Shared listings, export, backup, Drive, sync | Untouched; *a backup built after checks is identical* is in TC-U-154. Good |
| Clipboard | No copy action; the panel's text is selectable like any text, which is the person's own act |
| Logs | The website has no breadcrumb; `console.` is in the source-grep list (section 4.12) |
| **The website's back stack and URL** | The existing new-house flow puts `lat`/`lon` in the URL (`createAt`) because those are the house's own data; the check must not use that path (MUST-5). `ListReturn` and `history.state` (the share handover) must not carry a result: in the grep list |
| **The app's live region** | Keeps the last announcement's text in the DOM until the next one: MUST-6 |
| The *here* fix | Used once, dropped; `TraceRecorderService` is not told; the live walk is left out: right |
| The OS location service's own network use | Said in the spec and to be said in the guide: right |
| `Permissions-Policy` | `geolocation=(self)`: the page may ask; no `camera`/`microphone` involved |

## 8. Testability and process

- **TC rows:** TC-U-153 and TC-U-154 are testable as written once MUST-1 is settled (the gate test cannot be written for one
  `getCurrentPosition`). Add to TC-U-154: the live-region withdrawal (MUST-6), the website's *no URL, no history, no session storage*
  grep, the `APPROX` and new-house hidden states, `LocationMap`'s overlay on the house page. TC-M-61 / MT-81 are right; MT-81 step (7)
  could add *press Back from the Map after a check: no result is shown again* (the website's back stack).
- **Mutation lists:** complete for the algorithm; add one for the text: *the largest distance among the listed days* replaced by the
  smallest (the headline then says *within 3 m* for a day whose walk was 22 m away).
- **Vectors hygiene:** respected (section 1); the file's `placeCheckConventions` say so; nothing to add.
- **The dependency on S4b-FR-13:** real only for `trace-geo.ts` (SHOULD-2); the ticket's *the same distance code* is right, its *may land
  first with the 30-day trace only* is true for the phones and false for the website (no trace there before FR-17).
- **Open question 15 (the band, the weekday):** agree with the recommendation; keep `CLOSE`; keep the short weekday with the skeleton rule
  (SHOULD-5). **16 (the live walk left out of *here* only):** agree. **17 (approximate house: a note, no answer; no history, no sound,
  no automatic check):** agree on all four; they are what keeps PRV-032 true.
- **Process:** the five commits are docs-only and consistent (versions, dates, cross-references, the vector count 58, the renumbering
  FR-109/PRV-033 for 5.27.12). The reference files of this review are not committed, as the previous review's were not.

## 9. For the owner to decide

1. MUST-1: the website's *here* as a 15-second best-fix watch (recommended) or one `getCurrentPosition` with the test row changed.
2. Section 6's SHOULD: a one-press *Mark the houses I walked past* on the list, later, or not at all (my recommendation: not in S4b).
3. Questions 15 to 17: the recommended answers stand unless you say otherwise.
