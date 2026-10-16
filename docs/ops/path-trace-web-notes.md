# The website's path trace and place check: what is built, and the API the UI change calls

Session record (2026-10-06), written by the engineer of S4b-FR-13 (TypeScript twin), S4b-FR-17 (storage and recorder) and the
logic of S4b-FR-24. It is a hand-over note for the later change that builds the components, pages and CSS
([03](../03-design.md) section 6.2b rows 6 to 11, 14, 15 and 17 to 19). It is not a specification: the contract stays
[11](../11-feature-parity-and-export-spec.md) 5.27.2, 5.27.3, 5.27.8 and 5.27.13.

Everything below is pure TypeScript with no template, no style and no new dependency. Paths are under `web/src/app/`.

## 1. What exists

| File | What |
|---|---|
| `shared/trace-geo.ts` | The one plane: `TRACE` constants, `K`, `localXY`, `segmentLengthM`, `nearestOnSegment`, `distanceToSegmentM`, `interpolatedAtMs`, `haversineM`, `isValidLatLon`; the types `TracePoint`, `TraceWalk` |
| `shared/trace-repeats.ts` | `splitWalks`, `detectRepeats`, `pieces`, `RepeatAlert`, and the pieces they are made of (`walkSamples`, `SampleBuilder`, `bridge`, `SegmentIndex`) |
| `shared/trace-recorder.ts` | `TraceRecorder`: the 50 m gate, the 20 m / 5 minutes thinning, the walk id, the *resumed* mark (pure) |
| `shared/trace-place-check.ts` | `placeCheck`, `withoutSavedDuplicates`, `matchedStretch` |
| `shared/trace-place-text.ts` | `placeCheckSummary` (what is said, no language) and `placeCheckText` (the sentences) |
| `shared/locate-once.ts` (edit) | `locateBest` and `LOCATE_BEST`: the 15 s best-fix watch of the check's *here* source |
| `i18n/translation.service.ts` (edit) | `dateWithWeekday(epochMs, timeZone?, nowMs?)` and `metres(n)` (Intl) |
| `data/local-db.ts` (edit) | IndexedDB version 3; `deleteAll`, `keys`, `count`, `batch` (one transaction over several stores) |
| `data/trace-rows.ts` | The row shapes and the plain-array coding of a saved walk |
| `data/trace-store.ts` | `TraceStore`: the 30-day trace, saved walks, the walk to ask about, the settings |
| `core/trace-recorder.service.ts` | `TraceRecorderService`: the walk in progress |
| `core/alert-sound.service.ts` | `AlertSound`: the beep, the vibration fallback |
| `data/local-store.service.ts` (edit) | `database()`; `deleteHouse` deletes the house's saved walks |
| `data/records.ts` (edit) | `SETTING_KEYS.traceOn / traceLook / traceAlert / traceKeepAwake / traceAskedUpTo` |

Tests: `trace-geo`, `trace-repeats` (+ `-vectors`), `trace-recorder`, `trace-place-check` (+ `-vectors`), `trace-place-text`,
`trace-store`, `trace-rows`, `local-db`, `local-db-idb` (a fake IndexedDB), `trace-recorder.service`, `alert-sound.service`,
`locate-once`, `translation.service`, `trace-privacy` (behavioural) and `trace-sources` (a source test). Mutation lists:
`tools/mutations/trace-*.json`, `local-db*.json`, `alert-sound.json` (run `node tools/mutate.mjs tools/mutations/<name>.json`).

## 2. The API the components call

### 2.1 Drawing (Map, layers rows 6 and 7)

```ts
// data/trace-store.ts
traceStore.allWalks(nowMs): Promise<{ trace: TraceWalk[]; saved: { row: SavedWalkRow; walk: TraceWalk }[] }>
// shared/trace-repeats.ts
detectRepeats(walks: readonly TraceWalk[], options?: { plain?: boolean; maxPoints?: number }): WalkRepeats[]   // one per walk, input order
pieces(walk: TraceWalk, stretches: readonly Stretch[]): [number, number][][]                                   // [lon, lat] lines for GeoJSON
```

- The base line of a walk is `walk.points` (as `[lon, lat]`), but **not across a resumed point**: cut the line at every point
  with `resumed === true` (a saved walk keeps the mark too). The overlay is `pieces(walk, result.shown)`.
- `detectRepeats` is synchronous and pure. Call it when the Map opens, when the walks change (a walk ends, is saved or deleted)
  and, while a walk records, at most once every 5 seconds; cache by the walks' `key` and point count (5.27.3, "Cost and limits").
  `TraceWalk.key` is `t:<walkId>` for the trace and `s:<savedId>` for a saved walk. Walks over `maxDetectionPoints` get empty
  results (still draw them whole). Pass the saved walks too: detection reads all walks.
- `RepeatLook` (`'CLEAR' | 'SUBTLE' | 'OFF'`) and `REPEAT_LOOKS` are exported by `data/trace-store.ts`. The look never changes a
  result; `OFF` only hides the overlay. The alert does not depend on it (a test runs the 8 alert vectors with CLEAR and OFF).

### 2.2 The trace store (`TraceStore`, `providedIn: 'root'`)

```ts
persistent(): Promise<boolean>                     // false in memory (private browsing): "a walk lives for this page only"
putPoint(point, accuracyM) / walkPoints(walkId)    // the recorder writes; the check reads
traceWalks(nowMs, sinceMs?): Promise<TraceWalk[]>  // the last 30 days, split by 5.27.3 step 1
prune(nowMs): Promise<number>                      // 30 days; never reads saved_walks. Call at the Map's opening and at a walk's end
clearTrace(): Promise<void>                        // "Clear the path": the trace only
deleteTraceWalk(walkId) / walkSummary(walkId)
lastEndedWalk(liveWalkId, askedUpTo): Promise<WalkSummary | null>   // the walk to ask about (5.27.6)
saveWalk(walkId, houseId, nowMs, newId?): Promise<SaveWalkResult>   // one two-store transaction; refusals below
savedWalksOf(houseId): Promise<SavedWalkRow[]>     // newest first; savedWalk(id): decoded; savedCount(houseId?)
deleteSavedWalk(id) / deleteAllSavedWalks()
forEachSavedWalk(visit)                            // one at a time, by key (200 walks are about 8 MB)
placeWalks(nowMs, leaveOutWalkId = 0): Promise<PlaceWalk[]>          // what the place check compares with
traceOn()/setTraceOn, look()/setLook, alertOn()/setAlertOn, keepAwake()/setKeepAwake, askedUpTo()/setAskedUpTo
```

- `SaveWalkResult` is `{ ok: true; id }` or `{ ok: false; reason: 'noWalk' | 'tooLong' | 'houseFull' | 'deviceFull' }`; the
  strings are 5.27.6 (5 000 points, 20 a house, 200 a device). A refused save changes nothing.
- *Keep for 30 days* and a dismissed sheet do nothing but `setAskedUpTo(walkId)`. *Delete this walk* is `deleteTraceWalk`.
  Any answer calls `setAskedUpTo` (it never goes backwards). The sheet's data is `WalkSummary` (`pointCount`, `lengthM`,
  `startedAt`, `endedAt`, `last: { lat, lon }`, the nearest-house suggestion starts at `last`).
- Where to ask: right after `TraceRecorderService.finish()` (call `lastEndedWalk(0, await askedUpTo())`), when the Map opens
  (`lastEndedWalk(recorder.liveWalkId(), ...)`), at *Start a walk* before it (the walk a closed tab cut).
- Writing a walk never touches `LocalStore.revision`, so nothing here wakes the sync engine (a test asserts it).
- *Delete all saved walks* confirms with `savedCount()`. *Remove all Doorprints data* (`LocalStore.clearEverything`) already
  clears both stores and the settings. `LocalStore.deleteHouse` already deletes the house's saved walks.
- *Your data* (`data-page`) may show `Saved walks: n` from `savedCount()` and call `deleteAllSavedWalks()`. The privacy source
  test does **not** list `pages/data/**`, on purpose, but it fails when any file of `export/`, `data/drive/`, `core/ai/`,
  `data/sync*.ts`, `shared/listing-text.ts` or `core/launch-files.service.ts` names `TraceStore`, `trace_points`, `saved_walks`
  or the other trace names.

### 2.3 The recorder (`TraceRecorderService`, `providedIn: 'root'`, owns the watch, the screen lock and the beep)

```ts
start(): void        // call it INSIDE the click of "Start a walk": prepares the sound and calls watchPosition before anything is awaited
finish(): number     // "Finish walk" or a closed page; returns the ended walk id (0 when no point was kept)
liveWalkId(): number; livePoints(): TracePoint[]
settled(): Promise<void>                      // every fix so far has been handled (for a caller that reads the store next)
dismissPausedNotice(): void
state: Signal<'idle' | 'recording' | 'paused'>
problem: Signal<null | 'unsupported' | 'denied' | 'unavailable' | 'full' | 'storage'>   // the card says it in words
pausedNotice: Signal<boolean>    // "Recording paused while this page was hidden." on return
keptCount: Signal<number>; keptInBrowser: Signal<boolean>; keepAwakeFailed: Signal<boolean>; wakeLockSupported: boolean
alertRaised: Signal<number>      // +1 per alert: the banner (role="alert", 10 s, Dismiss) shows when it changes
onAlert: ((runM: number) => void) | null
```

- Nothing asks for the location until `start()`. `problem` `'denied'` and `'full'` / `'storage'` have already stopped the walk;
  `'unavailable'` keeps watching and clears itself at the next kept point.
- The alert: if `TraceStore.alertOn()` the service tests every kept point with `RepeatAlert` against the 30-day trace and every
  saved walk except the live one; a ring calls `AlertSound.beep()` (beep when the context runs, else vibrate), bumps
  `alertRaised` and calls `onAlert`. `AlertSound.running` is the signal for *Sound is off until you start a walk from this page.*
- Settings are read once per walk (at `start()` and after a gap of 30 minutes starts a new walk). Changing the alert switch
  during a walk applies at the next walk.
- Wake Lock: asked at the start when `keepAwake()` is on, released at `finish()`, asked again when the page is visible again.
- The service survives route changes; the page's card only reads the signals.
- Test seams (tokens, all `providedIn: 'root'`): `GEOLOCATION`, `WAKE_LOCK`, `RECORDER_OPTIONS` (thinning), `AUDIO_CONTEXT_FACTORY`, `VIBRATE`.

### 2.4 The place check

```ts
const walks = await traceStore.placeWalks(Date.now(), source === 'here' ? recorder.liveWalkId() : 0);
const result = placeCheck({ lat, lon }, walks, fixAccuracyM /* only for here */);       // PlaceCheckResult
const summary = placeCheckSummary(result, { hasSaved: walks.some((w) => w.source === 'SAVED'), fixAccuracyM });
const text = placeCheckText(summary, 'here' | 'house' | 'spot', placeTextHelpers(i18n), /* web */ true);
// text: { headline, rows[], rowsMore | null, notes[] }  (notes: "covers only the walks recorded", the website line, the loose-fix line)
matchedStretch(walk: PlaceWalk, row): [number, number][]    // the halo for each WALKED row: [lon, lat], 60 m each side, within one part
locateBest(handlers, geolocation?): () => void              // the 15 s watch; returns Cancel
```

- `result.rows[i].walkIndex` indexes the `walks` array you passed (not a filtered one). `row.walked` is the band. Show
  `matchedStretch(walks[row.walkIndex], row)` for each `walked` row.
- The place check applies the saved/trace de-duplication itself. It has no clock, no store, no network.
- `locateBest` handlers: `found(position)` (first fix of 50 m or better, or at 15 s the best so far, which may be worse: pass its
  `accuracy` to `placeCheck`, which answers `IMPRECISE`), `timedOut()`, `failed(error)` (permission refused), `gone()`.
  Call it inside the button's click. A house or a picked spot needs no location at all.
- The `trace.here.*` strings are **not in the dictionaries yet** (they are the UI change's, with hi, ta, te under review).
  `placeCheckText` asks for the keys of docs/11 5.27.13 as plain strings through `placeTextHelpers(i18n)`, which casts them to
  `TKey`; until the keys exist `TranslationService.t` returns the key itself. Keys it reads: `trace.here.placeHere|placeHouse|placeSpot`,
  `walked`, `close`, `none`, `noneSaved`, `onlyRecorded`, `onlyRecordedWeb`, `andMore`, `row`, `rowSaved`, `rowsMore`, `empty`,
  `emptyWeb`, `imprecise`, `fuzzy`, `invalid`. The panel adds `privacy`, `again`, the permission and denied sentences itself.
- After the panel closes call `Announcer.cancel`; never put a result, place or distance in a URL, `history.state`, a storage, or
  `ListReturn`: `trace-sources.spec.ts` fails the build of any trace or check file that names them (it already scans
  `pages/map/trace-*.ts`, `pages/map/place-check*.ts`, `pages/map/walk-end-sheet*.ts`, `pages/house-detail/house-check-*.ts` and
  `shared/trace-style.ts` when they exist, with an allowed list of none, comments excluded) and requires every caller of `placeCheck(`
  to be one of the check's own files.

## 3. Decisions made while building (the lead confirms or overrules)

1. **A saved walk row carries an optional `resumed: number[]`** (the indexes of resumed points), which the row shape of 03 section 6.2
   does not list. Without it a saved walk would draw a straight line across a pause. Absent when there is none.
2. **The recorder gives a new walk id after a gap of 30 minutes or more** (not only after *Finish walk*), so two walks never share a
   key `t:<walkId>`. The phones' `TrackRecorder` leaves this to the detection's gap rule; the detection result is the same.
3. **`lastEndedWalk` considers only the newest candidate**: if it has fewer than 5 points or 100 m it returns null and does not
   fall back to an older one (5.27.6: *an unanswered older walk is skipped*).
4. **Row distances in the place check's text are rounded up to a whole metre, at least 1** like the headline (the spec says it only for
   the headline). `placeCheckSummary.rows[i].distanceM` is that value.
5. **`MAX_DETECTION_POINTS`**: the newest walks are taken whole until the next one would not fit; that walk and every older one
   are left out (a smaller older walk is not squeezed in after a larger newer one).
6. **The alert's unblock rule** is implemented as: with the newest point not near, `blocked` becomes false when the arc length from
   the last near sample to the newest point is more than `BRIDGE_M` (or there is no run behind it, or a part boundary lies between).
   5.27.3 says "the trailing series ... is longer than `BRIDGE_M`"; read as the series' own length the rule would unblock later.
   All 8 alert vectors pass with this reading; please state which reading the text means.
7. **`dateWithWeekday` writes what Intl writes for `en-IN`: *Wed, 7 Oct*** (a comma after the weekday), not the owner's example
   *Tue 7 Oct*. With the comma a list of three days reads *Wed, 7 Oct, Tue, 6 Oct and Mon, 5 Oct*. If the owner wants no comma,
   the helper can use `en-GB` for English; Hindi, Tamil and Telugu are whatever ICU gives. Left as Intl gives it.
8. **A walk's points are read whole** (`getAll`) for the 30-day trace and `lastEndedWalk`, and saved walks one at a time by key
   (`keys` then `get`). At most a few thousand rows for the trace: fine; revisit only if a month of recording exceeds that.
9. **Two walks that overlap in time** (two tabs recording at once) would be mixed by `splitWalks`, which sorts all points by time.
   The website records from one page; not handled.

## 4. Findings about the vector file and the text (nothing was edited)

All 58 cases pass unmodified (37 repeat, split and alert; 21 place checks). Gaps in what the vectors pin, found by mutation
(each mutation is in `tools/mutations/` or reported here):

- `bridgeM` 30 to 40 survives every vector (only the constants drift test fails): no case has a detour of 35 to 40 m that must
  not be bridged.
- `latitude-13-east-west-street` does not kill dropping `cos(latitude)` from the point-to-segment distance (it kills the segment
  length); `pc-latitude-13-east-west-offset-uses-cos` does. A unit test pins it for the repeat side.
- `alert-needs-100m-not-60m`: its description says the longest run is 80 m, but no *kept point* sits inside that run (the point at
  the turn is 60 m from the street), so the run at a kept point is 60.0 m. The case fails for an `ALERT_MIN_RUN_M` of 50 but not
  for 60, because 60.0 compared with 60 is a floating-point tie; thresholds of 80 and 90 are killed by other cases. Harmless,
  but the description and the case disagree.
- `junction-crossing` likewise sits exactly at 40 m: `MIN_RUN_M` 30 kills it, 40 does not (`overlap-60m-is-not-a-repeat` does).

## 5. Not done, on purpose

- No component, page, CSS, map layer, `trace-style.ts` (row 6), `TraceLayers` (row 7), card, sheet, panel, house card, `LocationMap`
  overlay, `MapPage` change, i18n keys, `tools/live-ui` `--trace` run, guide page (rows 6 to 11, 14, 15, 17 to 20).
- The website's Hunt mode (5.27.12) is not built.
- `docs/` other than this file are untouched; the lead brings 01, 02, 03, 06, 10, 11, 14 from *planned* to *built*, and the vector
  file's status stays *proposed* until the Kotlin twin passes too.
