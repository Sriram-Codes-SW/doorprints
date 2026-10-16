*Session record; the decisions are in [docs/11](../11-feature-parity-and-export-spec.md) §5.27.*

# Senior review: the path trace, version 2 (design, `feat/path-trace-t0-spec` at `8953e862`)

| Field | Value |
|---|---|
| Document | Review of the path trace v2 design before implementation (S4b-FR-13..S4b-FR-18) |
| Version | 0.1 |
| Date | 2026-10-06 |
| Author | Claude, senior reviewer |
| Scope | [11](../11-feature-parity-and-export-spec.md) 5.27.0..5.27.11 (v0.56), [01](../01-requirements.md) FR-102..107, PRV-028/030/031, [02](../02-threat-model.md) T-I30, RR-31, [03](../03-design.md) §4.2.1, §6.2, §7.2a, ADR-34, [schemas/trace-repeat-vectors.json](../schemas/trace-repeat-vectors.json), [06](../06-test-plan.md) TC-U-147..152, TC-M-57..60, [manual-test-checklist.md](manual-test-checklist.md) §15, [10](../10-sprint-log.md) S4b-FR-13..18, [14](../14-lead-backlog-and-handoff.md) N20 |
| Verdict | **GO with changes.** The algorithm is well defined: an independent reference written from the text alone passes all 31 vectors. Six MUST items below are spec corrections (three touch the code's shape, three the contract text) and are cheap to make before S4b-FR-13 starts. |

## 0. Verdict in one paragraph

The design fits the code: Room 11 is the next free number at `main` `4dd94a3f` (`AppDatabase.version = 10`, `MIGRATION_9_10` last,
`exportSchema = true`, schemas `2.json..10.json` committed), IndexedDB `DB_VERSION = 2` so 3 is next, `LOCAL_TABLES` in
`DatabaseTransactions.kt` is `houses, visits, photos, records` (no trace), `RoomSyncRows` reads only the four DAOs, the Android manifest
excludes the database from cloud backup, and the iOS data directory is `NSURLIsExcludedFromBackupKey`. The vectors are
sound. What must change: (1) the "live look through `JsonStyleOps` / `setPaintProperty`" claim is wrong for both phones (`JsonStyleOps`
edits the style JSON before load; Android builds the track layer with `LineLayer`/`PropertyFactory` in `PlatformMap.android.kt`, iOS
through a Swift `setTrack`); (2) the alert's `blocked` flag is reset by a single off-corridor fix, so one 30 m jitter rings the
same street again after 10 minutes (probe below); (3) the walk-id-0 rule of step 1 is ambiguous and untested; (4) a walk cut by
process death or an app kill never gets `walkToAsk`; (5) `commonTest` cannot read `docs/schemas/*.json` (the Drive precedent is
`androidHostTest`); (6) "a new phone starts without saved walks" is false for Android's device-to-device transfer, which the
`data_extraction_rules.xml` lets copy the whole database.

## 1. The vectors: 31 of 31 agree with an independent reference

An independent Python reference (`trace_ref.py`, written from 5.27.3 only, in the reviewer's scratch directory, not committed) runs
every case: **0 disagreements** (`repeated` and `shown` to 0.5 m, split indexes and alert indexes exactly). So the vectors and the
text agree with each other for everything they cover. What they do **not** cover, found by probing the reference:

| Probe | Result | Consequence |
|---|---|---|
| Walk id 0 next to a non-zero id (`[.., id 0]`, `[.., id 7]`, gap 60 s) | Text: "when both points carry a walk id and the ids differ"; Kotlin's `TracePoint.walkId` defaults to 0, so every point "carries" one. The vector `split-a-new-walk-id-splits-within-30-minutes` uses ids 1 and 2 only | MUST-3: say "a walk id of 0 is no id" and add a vector |
| One fix 40 m off the corridor (passes the 50 m gate) in the middle of a 1 200 m street walked before, 20 s per 20 m | Alert at index 5 **and again at index 45** (the off sample sets `blocked = false`; the next sample is near, bridging makes the trailing run the whole street, the cooldown has passed) | MUST-2; the sentence "walking a long street rings once" is not true as written |
| The same with the jitter inside the cooldown (index 20) | Rings again at the first point 600 s after the first alert (index 35) | Same |
| Two 400 m passes of one street, Gaussian error σ per axis, 200 trials | σ 5 and 8 m: 100 % repeated, one stretch; σ 12 m: 31 of 200 trials split into two stretches; σ 15 m: 81 of 200 | Fine for urban phones (5–15 m); dashes fragment on a bad day. Acceptable; say so in 5.27.11 q11 |
| Two parallel lanes D m apart, σ 8 m, 400 m | D 25: a false repeat in 175 of 200 trials; **D 30: 97 of 200 (17 % of the length on average); D 35: 26; D 40: 2; D 50: 0** | The vector `parallel-30m-apart` is noise-free. The claim "25 m refuses lanes 30 to 60 m apart" holds only from about 40 m with real noise. SHOULD-4: owner's field check (q11) must include two lanes 30 m apart; consider `TOLERANCE_M = 20` if they light up |
| Two readings of one street 50 m apart (both 25 m off, opposite sides, each inside the gate) | Not a repeat | A miss, never a false mark: acceptable |
| Exactly 25.0 m apart, no noise | Near (inclusive) at the equator; 25.0000001 not | The vectors avoid the boundary: keep it so. Add a note that no vector may sit within 0.01 m of `TOLERANCE_M` or within 0.01 of a densify step boundary (`L/10 + 0.5` integral) because `cos`, `floor` and `K` differ by an ulp between JVM and V8 |
| A stay (three points at one spot) inside a walk | Zero-length segments give duplicate samples at one arc; harmless | Say in step 3 that a zero-length segment gives one sample at the same arc (so an implementer does not "fix" it) |
| Three walks, the newest first in the input, the oldest in the middle | Only the newest draws; the input order does not matter, the walk id does | Covered by the text; add one vector (`shown-newest-draws-whatever-the-input-order`) so a port that uses the index instead of the id fails |
| Plain loops, 2 000 points in 40 walks | 12 s in Python; 20 000 points ≈ 1 200 s Python, so tens of seconds in Kotlin on a phone | The index of "Cost and limits" is required, not optional, as the spec says. Good |
| `floor(L / 10 + 0.5)` at L = 15.0 exactly | n = 2 (JVM and V8 agree on exact halves because 15/10 + 0.5 = 2.0 exactly) | Fine |
| Duplicate timestamps across walks, points out of order | Sorted stably (Kotlin `sortedBy`, JS `Array.prototype.sort` stable since ES2019); Room's `ORDER BY at` does not order ties: the "first stays" rule is deterministic only for the in-memory list, which is enough | Say in step 1 "stable: input order breaks a tie" (the vector `split-unsorted-and-duplicate-times` depends on it) |
| The alert's "window" optimisation (5.27.3 *Cost and limits*: "one pass over the live walk's last `ALERT_MIN_RUN_M + BRIDGE_M` metres") versus the definition ("build the live walk's samples up to P") | Equivalent only if the window starts on a sample and bridging inside the window gives the same flags for the last 100 m; a window that cuts a non-near series can bridge it differently | Say: an implementation may look at the last 130 m plus one sample, and the vectors and the random-city test must give the same alert indexes as the full definition |

## 2. MUST (before S4b-FR-13 / FR-15 start)

### MUST-1 The live look on the phones has no seam yet; `JsonStyleOps` is not a live style API

`JsonStyleOps` (`android/ui/.../JsonStyleOps.kt`) edits a style's JSON **before the map loads it** (`putSource`, `addLayerOnTop`,
`toJson`); it has no `setPaintProperty`. Android does not even use it for the trace: `PlatformMap.android.kt` lines 233–246 build
`LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(...)` from `TRACK_COLOR`/`TRACK_WIDTHS`, and iOS calls a Swift `map.setTrack(json)`.
So "the Map ... sets the overlay layer's `line-width` and `visibility` on the running style (`setPaintProperty`/`setLayoutProperty`;
on Android and iPhone through `JsonStyleOps`)" and the test `TrackStyleTest.lookChangesOnlyTheOverlayLayer` "through a recording
`JsonStyleOps`" cannot be built as written.

Replace in 5.27.4 *Live* and TC-U-149 with:

> **Live.** `PlatformMap` gains a parameter `repeatLook: RepeatLook`. Android: `LaunchedEffect(style, repeatLook)` finds the layer
> `style.getLayerAs<LineLayer>(TRACK_REPEAT_LAYER)` and calls `setProperties(lineWidth(repeatWidthExpression(look)), visibility(if OFF
> NONE else VISIBLE))`; the layer is added once, after `track-line`, from the same values as `trackRepeatLayerJson`. iPhone: a Swift
> method `setRepeatLook(widthStops: [[Double]], visible: Bool)` on the map wrapper, called from `PlatformMap.ios.kt` the way `setTrack` is.
> Website: `map.setPaintProperty('track-repeat-line', 'line-width', ...)` and `setLayoutProperty(..., 'visibility', ...)`. The GeoJSON
> is not rebuilt. **Proof:** `TrackStyleTest` pins `trackRepeatLayerJson(look)` and `repeatWidthExpression(look)` (2.7/5.4/9.0 and
> 1.5/3.0/5.0, `visibility none` for OFF); `MapScreenLookTest` (`:ui` commonTest, a fake `PlatformMap` recording its parameters) shows a
> settings change re-renders with the new `repeatLook` and the same `track` list instance; the Android layer call is covered by the
> Roborazzi screenshot `trace_look_*`; the web by `trace-style.spec.ts` with a fake map.

### MUST-2 The alert's `blocked` flag must survive one bad fix

5.27.3 *The alert's test*: "If `P` (the last sample) is not near: `blocked = false`". A single fix 26–50 m off the path (inside the
accuracy gate; the spec's own reason for `BRIDGE_M`) clears `blocked`; the next point is near, bridging restores the whole run, and
the alert rings again as soon as the cooldown allows. The reference shows `[5, 45]` on one street where the text promises one alert.

Replace the sentence with:

> If `P` is not near, no alert; `blocked` becomes false only when the trailing series of non-near samples (ending at `P`, before
> bridging) is longer than `BRIDGE_M` along the walk, that is, when bridging could no longer join `P` to the run behind it. One or
> two off samples inside a run neither ring nor unblock.

Add the vector `alert-one-bad-fix-does-not-ring-again` (a 1 200 m street walked before; the live walk 20 m per 20 s with one point
40 m east at index 40; expected `[5]`) and the named mutation "unblock on any non-near sample (`alert-one-bad-fix-does-not-ring-again`)".
TC-U-148 gains the case.

### MUST-3 Walk id 0 is "no id"

Step 1 and the *Walk id* row: add "A walk id of 0 is no id (rows from before this design, and the website's points before a walk id is
known): the id rule applies only when both ids are non-zero; otherwise the gap rule alone." Add the vector `split-walk-id-zero-is-no-id`
(`[.., 0]`, `[.., 0]`, `[.., 5]`, `[.., 5]` with 60 s gaps: one walk `[0,1,2,3]`; and `[.., 5]`, `[.., 0]`, `[.., 6]` with 60 s gaps: one
walk, because 0 breaks no pair). The Kotlin `TracePoint.walkId: Long = 0` default and the web's `walk` field then mean the same thing.

### MUST-4 A walk that ends without `stopped()` is never asked about

`walkToAsk` is set by the engine at `stop`/`Finish walk`. The engine's `stopped()` does not run when Android kills the process (the
service restarts `START_STICKY` and `engine.start()` calls `track.reset()`: a new id, no question for the old walk), when iOS terminates
the app, or when the website's tab is closed (no reliable `pagehide` write). The design says the sheet appears "when the Map next opens
with `walkToAsk` set", so these walks silently become 30-day walks.

Replace `walkToAsk` by a watermark in 5.27.6 and [03](../03-design.md) §6.2:

> **`walkAskedUpTo`** (a walk id, default 0; `AppSettings.walkAskedUpTo`, website `trace.askedUpTo`): the newest walk the *Save this walk?*
> sheet has handled. **The walk to ask about** is computed, not stored: the newest walk id in `track_points` that is not the live walk's
> id, is greater than `walkAskedUpTo`, and has at least 5 points and 100 m. It is looked for (a) at once after *Finish walk* or a stop from
> the Map, (b) when the Map opens, (c) at Hunt start (before `reset()`, for the walk a restart cut). Any answer or dismissal sets
> `walkAskedUpTo` to that id, so each walk is asked once and an unanswered older walk is skipped (kept 30 days). `HuntData` gains
> `suspend fun lastEndedWalk(): Long?` and `Settings.saveWalkAskedUpTo`.

This also removes "a new `walkToAsk` replaces an unanswered one" and the engine writing a setting it has no writer for today
(`HuntData` has no settings write).

### MUST-5 Where the vector tests run

TC-U-147 says `RepeatDetectorVectorsTest` is "`:shared` commonTest on JVM and the iOS simulator". `commonTest` has no `java.io.File`; the
precedent `DriveVectorsTest` is in **`androidHostTest`** and walks up from the working directory to find `docs/schemas/drive-vectors.json`.
Correct TC-U-147 and S4b-FR-13: `RepeatDetectorVectorsTest` in `androidHostTest` (the JVM, like Drive); a small `RepeatDetectorTest` in
`commonTest` with six inline cases (same street, junction, bridge, gap split, alert 100 m, cooldown) so the iOS simulator job executes
the common code too; `trace-repeats-vectors.spec.ts` reads the file as `drive-vectors.spec.ts` does.

### MUST-6 "A new phone starts without saved walks" is not true for a cable transfer

`android/app/src/main/res/xml/data_extraction_rules.xml`: cloud backup excludes everything (good), but **device transfer includes the
database** on purpose (houses and photos move to a new phone). Room tables cannot be excluded one by one, so `track_points` and
`saved_walks` move with it. iOS is clean (`IosDataDirectory` excludes the directory from iCloud and computer backups). Options:
(a) accept and say it; (b) on first start after a transfer, nothing can tell a transfer from an upgrade, so (b) is not available.
Take (a): in 5.27.7, PRV-028, T-I30 and the guide (S4b-FR-18) replace "a new phone starts without saved walks" with "a phone set up
from a backup, a copy, Drive or the server starts without saved walks; Android's own phone-to-phone transfer (cable or Wi-Fi at setup)
copies the whole Doorprints database, walks included, because it is the phone's own copy and not a backup ([02](../02-threat-model.md)
T-I30, `data_extraction_rules.xml`)". Add it to RR-31.

## 3. SHOULD (before the ticket that owns it)

1. **The "others" after *Finish walk*** (5.27.5, q1): after *Finish walk* at a house, walking back the way one came is a different walk,
   so the alert rings on the way back (the reference: `[5]`). Say so, or exclude the walk ended in the last `WALK_GAP_MS` from the alert's
   others (not from the Map's detection). Recommend the exclusion and a vector `alert-not-for-the-walk-just-finished`; owner decides.
2. **The web's `trace_points` key** `"<walkId>-<atMs>"`: a point written before the walk id is known (the first kept point *is* the id)
   is fine, but say the id is assigned at the first kept point, not at *Start a walk* (a walk that never gets a fix has no id, no row).
3. **The sweeper's trigger "when the Undo snackbar closes without Undo"** runs inside `offerDeletedHouseUndo`'s coroutine, which the screen
   cancels when it leaves (the snackbar is dismissed with it). Add: the sweep after `Dismissed` runs under `NonCancellable`, like
   `restore()`; and a missed sweep is caught by the next trigger. Also list the two other places that tombstone houses: an import that
   deletes (`CommonRepository` line 1415 `deleteHouse(id)` inside the import) and `CopyUndo` (line 1710): both end in "import end", fine,
   but name them so the test covers them.
4. **Tolerance in the field** (q11): the owner's check TC-M-57 must include two parallel lanes about 30 m apart (the probe shows a false
   mark about half the time with 8 m error); the fallback is `TOLERANCE_M = 20` with the same vectors regenerated.
5. **`AppSettings.toString`** prints `pathTrace`; the spec says `repeatLook` and `repeatAlert` are "not in the redacted print". Either is
   fine (booleans and an enum reveal nothing), but `walkAskedUpTo` is a timestamp of a walk: keep it out. Say which.
6. **The search rule** (CLAUDE.md): a saved walk is not a house field, so `searchText` and `HouseListScreen` do not change. Say so in
   5.27.6 in one line ("not a house field: the search rule of S4b-FR-1 does not apply; the picker reuses `HouseSearch.matches`"). Note
   that the Android filter is `HouseSearch.matches(...)` in `HouseListScreen.kt` line 469, the same function the picker should call.
7. **The web geocoder is never automatic**: irrelevant to the trace, relevant to §9 below.
8. **docs/05**: add the legend row *Your paths* and the *Save this walk?* sheet to §5.1 and §5.2 (component tables), and 1.4.1's Android
   column ("marker size, ring and opacity + legend") gains "repeat: dash + width + legend". The map's content description sentence of
   5.27.4 belongs in §7 under 1.1.1.
9. **Colour text correction** (5.27.4): relative luminance is 0.10 (purple) against 0.23 (orange), not 0.12; contrast with white 3.79:1.
10. **`MAX_WALK_POINTS` refusal**: a walk over 5 000 points cannot be saved, but a 5-minute-gap walk on a bus is 12 points an hour, and a
    20 m walk is 250 points a 5 km: nobody reaches 5 000 on foot. Fine; but say the Map's base line draws it whole anyway.
11. **The alert channel and the app lock**: `VISIBILITY_SECRET` with the app lock is what the Hunt alerts do (§17 of 03); say that the
    public version *Doorprints alert* is the one of `CHANNEL_ALERTS` so no new string is needed on the lock screen.
12. **Vector hygiene** (schemas/README §6.4): no expected boundary within 0.01 m of `TOLERANCE_M`, `BRIDGE_M`, `MIN_RUN_M` or a densify
    step; the file's `constants` must also carry `maxWalkPoints` and `maxDetectionPoints` so the drift test covers them.

## 4. LATER (backlog rows, not this batch)

- Per-walk summary beyond date/distance/minutes: the houses passed (within `alertRadiusM`) listed on the row; "visited" is already the
  visits table.
- Coverage ("where I have not been"): a *Streets not walked* view needs the road network, which the app does not have offline; park.
- Naming a walk; linking one walk to several houses (q3); *Show saved walks* switch (q5); a third level (q6).
- Export of one saved walk as GPX on explicit request: against the owner's "phone only"; park until asked.
- The alert's quiet hours or "not while driving" (a speed gate: no alert when the last two kept points imply more than 12 km/h) is cheap
  and avoids a bus ride ringing through a known street; worth a line in q-list for the owner.
- A "new area" cue (first time in a locality): the inverse of the alert; not asked for.

## 5. Colour-vision verdict for `#E65100` on `#8E24AA`

Machado, Oliveira and Fernandes (2009) matrices at severity 1.0 in linear sRGB; distances CIEDE2000 (ΔE ≥ 10 is clearly apart,
≥ 20 a different colour); WCAG contrast where relevant.

| View | Orange becomes | Purple becomes | ΔE2000 orange vs purple | Nearest other colour to the orange (ΔE) |
|---|---|---|---|---|
| Normal | #E65100 | #8E24AA | 48.5 | marker rejected #B3261E (18.2); Liberty motorway casing #E9AC77 (23.0) |
| Protanopia | #7E6E00 (olive) | #0052AE (blue) | 57.1 | marker star #966000 (3.9), amber #8A5A00 (6.5), shortlisted green #1A7A43 (8.6) |
| Deuteranopia | #A29000 | #2A5CA7 | 59.1 | star (12.2), amber (15.7), motorway casing (15.9) |
| Tritanopia | #FE2046 | #8D4267 | 26.3 | marker rejected (13.5) |

Contrast with white: orange 3.79:1 (≥ 3:1, WCAG 1.4.11), purple 7.04:1; orange on purple 1.86:1 (the dash carries the pair, as the spec
says). **Verdict: pass.** The pair sits on the blue–yellow axis for protan and deutan eyes exactly as 5.27.4 argues. The one weakness:
under protanopia the orange line and the amber/star marker colours are near-identical in colour (ΔE 4–7), told apart by form only (a
dot with a white ring against a dashed line) and by the purple base under the dash. That is acceptable; it is why *Off* must never be
the default and why the dash is non-negotiable. No change to the colour. TC-M-57 step (4) stands as the device confirmation.

## 6. Privacy and security: T-I30 re-check

Checked and correct in the spec: `ExportBundle`/`LocalRows`, `localTablesChanged()`, `ExportBuilder`, `AutoBackupWorker`, `DriveBackupService`,
`DriveSyncBackend`/`RoomSyncRows` (reads houses, visits, records, photos only), `ServerSyncBackend`, `AiHouse`/`HouseDocuments`, the web
exporters, `drive-backup.service.ts`, `sync-file.ts`, `sync.service.ts`, `core/ai/**`; no exporter reads the web `settings` store; the
saved-walk tables having no `dirty`/`updatedAt`/`deleted` columns is the right structural guard. Add to T-I30 and 5.27.7:

| Leak path | Finding | Add |
|---|---|---|
| Android device-to-device transfer | Copies the database (MUST-6) | Named in T-I30 and RR-31 |
| Android `allowBackup` / cloud | `allowBackup="false"`, cloud excludes the database: clean | Cite `data_extraction_rules.xml` in T-I30 |
| iOS backups | `NSURLIsExcludedFromBackupKey` on the data directory: clean | Cite `IosDataDirectory.kt` |
| Recent-apps thumbnail, screenshots | T-I29 residual; the Map with dashes shows where she walked and the house page the walk rows | Mention "the Map and the house page with walks" in T-I29's residual |
| Notification text | *You have walked this way before* / *This path is on your map from an earlier walk*: no place name. Good. `VISIBILITY_PRIVATE` with a public version; `SECRET` with the app lock | Nothing |
| Snackbar *Walk saved with {house}* | Names the house on screen only | Nothing |
| Logs, logcat, crash reports | No crash reporter (zero cost); `breadcrumb()` in `IosHunt` logs stop reasons only. Add the rule: no walk id, point or count in any log line; `HuntService` must not log fixes | One sentence in 5.27.7 and a grep in the source test (`Log.` / `breadcrumb(` in the trace package) |
| *Show on map* navigation argument | The walk id (a timestamp) travels in a nav route; routes are not logged | Nothing |
| Website: shared computer | IndexedDB per browser profile; *Remove all Doorprints data from this browser* clears; the sheet's `bodyWeb` text says "this browser" | Add to the Map card: *Other people using this browser profile can see your walks.* (one string) |
| Website: service worker caches | `sw.js` caches the app shell and hashed build files only; IndexedDB is never in Cache Storage; tiles go through `addProtocol` over Cache Storage (offline packs): a tile request reveals where the map was looked at, not where she walked | Nothing |
| Website: `trace.*` settings in the `settings` store | No exporter or sync reads `settings`; `clearEverything` clears it | Nothing |
| Drive sync of houses referencing walk ids | Houses do not reference walks (walks reference houses); nothing to leak | Say "the reference points one way: `saved_walks.houseId`" |
| Shared listing / share updates file | `ExportBundle.build(since)` | Covered by TC-U-151 |
| The AI assistant context | `AiHouse`, `HouseDocuments` read houses, visits, records; the house page's walk card is not a document | Covered |
| A walk linked to a house that is later **shared** (S4b-BL-120) | Sharing sends files from `ExportBundle`: covered | Nothing |

Nothing breaks "phone only" except the Android transfer (MUST-6), which is honest to document rather than possible to prevent.

## 7. The twelve open questions

| # | Recommendation in 5.27.11 | Reviewer |
|---|---|---|
| 1 | Out-and-back inside one walk is not a repeat | **Agree** for the Map. For the alert, see SHOULD-1: the walk just finished is a different walk and rings on the way back; decide |
| 2 | Deleted house: hidden, swept when final | **Agree**; add SHOULD-3 (NonCancellable sweep after Dismissed) |
| 3 | One house per walk | **Agree** |
| 4 | Confirm `#E65100` by simulation | **Done above: pass.** Keep TC-M-57 (4) as the device check |
| 5 | No *Show saved walks* switch | **Agree** |
| 6 | No third level | **Agree** |
| 7 | Straight line over a hidden gap under 30 min | **Disagree mildly**: on the website a hidden page is the normal case (the screen locks), and a straight leap across a neighbourhood draws a path never walked and can create false repeats with it. Split the walk when the page was hidden for more than 5 minutes (same id, the gap rule of step 1 unchanged; a `hidden` flag on the next point starts a new segment that the samples skip). Cheap: the recorder inserts no segment across a `paused` boundary. Needs one vector (`web-pause-makes-no-segment`) |
| 8 | No Web Notification | **Agree** |
| 9 | Settings on the Map card | **Agree** |
| 10 | Prune at the Map's opening | **Agree**; note `trackPoints` already filters `observeSince(now - 30 d)`, so this is storage hygiene only |
| 11 | Ship 80 / 25 and measure | **Agree with SHOULD-4** (two lanes 30 m apart in the field check) |
| 12 | Room 11 | **Confirmed free** at `4dd94a3f`; `feat/path-trace-repeats` and `feat/qr-scanner-and-backlog` carry no Room change |

## 8. Testability and process

- The TC rows are sufficient once MUST-1, -2, -5 are applied. Mutation list: add "unblock on any non-near sample" (MUST-2), "walk id 0
  treated as an id" (MUST-3), "newer by input index instead of walk id" (the extra `shown` vector).
- **Ticket order**: S4b-FR-13 first and alone is right. After it, FR-14 (phones' data), FR-15 (UI with fakes) and FR-17 (website) run in
  parallel; FR-16 (alert) after FR-13 and FR-14. The three stacks can proceed against the vectors from day one because the contract is
  pure. The website's twin (`trace-repeats.ts`) and the Kotlin are "written separately from the text": keep the rule that the two
  engineers do not read each other's code until both pass, and name in the PR who wrote which.
- **Brand words** (docs/12): no *Restore*; *Save a copy*, *Import a backup* used correctly. *Save with a house* / *Keep for 30 days* /
  *Delete this walk* are fine. One wording nit: `trace.alert.banner` "You have walked this path before." and `trace.alert.notifTitle`
  "You have walked this way before" should be the same sentence on both stacks.
- **hi/ta/te**: ship *under review* as the rule says; MT-80 covers them. The 65 new keys double the trace's string count: fine.
- **Licence headers**: new files `trace/*.kt`, `trace-repeats.ts`, `trace-store.ts`, `trace-recorder.service.ts` need the header
  (`licence-headers.py --fix`).
- **The guide** (FR-18) must carry the MUST-6 sentence.

## 9. Hunt mode on the website

Owner question of 2026-10-06: can Hunt mode itself come to the website, accepting that it works only while the page is open and
visible?

### 9.1 What Hunt mode is today (phones)

From `HuntEngine.kt`, `HuntService.kt`, `IosHunt.kt`, `StayDetector`, `StreetAlerts`, [11](../11-feature-parity-and-export-spec.md)
5.16–5.19, 5.27 and [01](../01-requirements.md) FR-013, FR-014, FR-017, FR-040, FR-083..FR-086, PRV-001, PRV-024..PRV-028:

| Behaviour | Rule today |
|---|---|
| Fixes | Foreground service (Android, `FOREGROUND_SERVICE_TYPE_LOCATION`, ongoing notification with *Stop*) or Core Location background updates under *When in use* (iPhone); 15 s walking, 60 s staying (`requestUpdates`); accuracy gate 50 m (`HuntState.MAX_ACCURACY_M`) |
| Near-house alert (FR-013) | Nearest non-approximate house within `alertRadiusM` (default 30 m), once per house per 30 min (`HOUSE_ALERT_AGAIN_MS`); the Hunt card names the nearest within 150 m |
| Street alert (FR-014) | Reverse geocode at most every 45 s or 80 m (`GEOCODE_AGAIN_MS/M`; Android `Geocoder`, iPhone `CLGeocoder`, on-device or platform service); *You've been on this street* when it has houses or visits, repeat after 60 min (`StreetAlerts.REPEAT_AFTER_MS`) |
| Stay and visit (5.x, `StayDetector`) | 40 m radius, `minStayMinutes` (default 4): a visit is saved (`VisitSource.AUTO`); with no house within 40 m, *Are you at a house?* offers to add one |
| Battery (FR-040) | Stops at 15 % not charging; stationary rate |
| Path trace (5.27) | `TrackRecorder` 20 m / 5 min; now v2 |
| Reminders (FR-083/084) | Exact alarms before a viewing with *Start Hunt mode*; iPhone local notifications |
| Area wake-up (FR-086) | Geofences with "Allow all the time"; *You're in <area>: start Hunt mode?* |
| App lock interplay | Alerts `VISIBILITY_SECRET` with the lock |

### 9.2 What a browser can do (honest table)

Facts: `watchPosition` delivers fixes only while the page is visible (Android Chrome throttles a hidden tab and stops when the screen
locks; iOS Safari and a home-screen web app stop at once on lock or app switch; no background geolocation, no geofencing API exists on
the web). Screen Wake Lock keeps the screen on (Chrome, Safari 16.4+, installed iOS web app only in newer releases). Web Notifications
through a service worker work on Android Chrome and on iOS 16.4+ only for an installed web app; a notification can be *shown* from the
open page (`registration.showNotification`, used today for viewing reminders) but nothing can *compute* in the background to decide to
show one. Web Audio needs a user gesture to start; iOS mutes it with the silent switch; `navigator.vibrate` is Android only. The web's
reverse geocoder is **Nominatim**, whose usage policy allows 1 request per second and which the app today calls **only from an explicit
button press** (`geocode.service.ts` line 18–19); an automatic lookup every 45 s while walking is a new kind of use of a free public
service and is the one feature with a policy question, not a browser limit.

| Hunt-mode behaviour | On the website, page open and visible | Verdict | What the person must be told |
|---|---|---|---|
| Fixes at 15 s / 60 s | `watchPosition({enableHighAccuracy: true})`; the browser decides the rate (usually 1–5 s on a phone, worse on a laptop with no GPS); the engine's thinning applies | **Works (degraded on computers)** | *Works only while this page is open and visible. A computer without GPS gives a rough location.* |
| Accuracy gate 50 m | `coords.accuracy` | **Works** | — |
| Near-house alert | Same rule in the page; a banner (`role="alert"`) and the beep of 5.27.5; a notification via `showNotification` while the page is open (optional) | **Works while visible** | *No alert when the screen is locked or another app is in front.* |
| Nearest-house card | Same | **Works** | — |
| Street alert | Needs automatic reverse geocoding: Nominatim policy question; also 45 s pacing is the minimum | **Defer** (owner decision: a cap of one lookup per 80 m and at most 60 per walk, with the User-Agent/Referer the policy asks, or no street alert on the web) | *Street alerts are not on the website.* |
| Stay → visit, *Are you at a house?* | `StayDetector` is pure common logic; a visit row is ordinary data (synced, exported, like today's visits) | **Works while visible**; a stay of 4 min with the screen locked is missed unless the wake lock is on | *Keep the screen on, or the visit may not be noticed.* |
| Save a house here | Exists today (*Add at my location*) | **Works** | — |
| Battery stop at 15 % | Battery Status API is Chrome-only and deprecated elsewhere | **Degraded**: on Chrome, stop at 15 % as the phones; elsewhere no stop, say so | *Your browser does not tell the page the battery level.* |
| Path trace + repeats + alert | 5.27.8 | **Works while visible** | As 5.27.8 |
| Reminders with *Start Hunt mode* | Viewing reminders exist on the web (`viewing-reminder.service.ts`, shown by the open app only) | **Degraded**: the reminder can carry a *Start Hunt mode* link that opens the Map and starts it on tap (a tap is the user gesture the audio needs) | *Reminders show only while the website is open.* |
| Area wake-up (geofences, background) | No web geofencing; no background location | **Cannot** | *Area wake-up is a phone feature.* |
| Ongoing notification with *Stop* | No foreground service; the page's own card with *Stop* | **Replaced by the card** | — |
| Pocket / locked screen | Nothing runs | **Cannot** | The sentence of 5.27.8 |
| App lock | The website has no app lock (T-I29 is a phone threat) | n/a | — |

### 9.3 Recommendation: a minimal *Hunt mode (while this page is open)*

Build it as a **follow-up ticket after the web trace (S4b-FR-17), not in the same batch**: FR-17 already creates every seam it needs
(`TraceRecorderService` with `watchPosition`, visibility, wake lock, the beep and banner, the permission flow, the Map-page card),
and adding house and stay rules to that batch would double FR-17's review surface. The follow-up is small because `HuntEngine`'s rules
are already pure and vectorised in spirit:

- **Share the rules, not the code**: a second vector file `docs/schemas/hunt-vectors.json` (format `doorprints-hunt-vectors/1`) with
  cases for the near-house alert (radius, once per 30 min, approximate houses never), the stay detector (40 m, `minStayMinutes`, the
  visit's arrival and leaving), the accuracy gate and the thinning. The Kotlin side runs them against `HuntEngine` with fakes (it has
  `HuntEngineTest` already); the web's `hunt-rules.ts` is written from [11](../11-feature-parity-and-export-spec.md) 5.x text the way
  `trace-repeats.ts` is. Constants in one table (`alertRadiusM` 30, `NEAREST_SHOWN_M` 150, `HOUSE_ALERT_AGAIN_MS`, `HOUSE_AT_M` 40,
  stay radius 40, `MAX_ACCURACY_M` 50, 20 m / 5 min). Street alerts stay out of v1.
- **Settings** on the Map page's card (the same place as the trace, q9): *Hunt mode (while this page is open)* switch (session only,
  never persisted: a reload starts it off), *Alert radius* (reuse the phones' values; local, `hunt.alertRadiusM`), *Minimum stay*
  (`hunt.minStayMinutes`), *Keep the screen on* (shared with the trace), *Trace my path* (as FR-17). The alert state (`houseAlertedAt`,
  the stay anchor) lives in memory only.
- **Privacy**: fixes are never stored except as trace points under 5.27.7; visits are ordinary rows (as on the phones: synced and
  exported, because a visit is the person's record of a house, PRV-001); nothing new in any export, Drive or AI path; the location
  permission only at the switch's tap (PRV-031 extended: "or starts Hunt mode on the website"); no Web Push, no server.
- **Permission flow**: the sentence of 5.27.8 (*trace.web.permissionExplain*) reworded for Hunt mode; the browser's prompt; denied and
  unavailable states as 5.27.8; notifications asked only if the person turns on *Also show a notification* (default off; the banner and
  beep need no permission).
- **Battery and wake lock**: the card says *Hunt mode on the website uses the GPS continuously while the page is open and keeps the screen
  on if you ask it to; on a phone, the app does this better and in your pocket.* Stop on `visibilitychange` to hidden after 30 minutes
  (the walk-gap rule) and say *Hunt mode stopped: the page was hidden.*
- **Accessibility**: the card's state in text (*Hunt mode is on: watching for your houses*), the banner `role="alert"`, the nearest-house
  text in `aria-live="polite"`, every alert a sentence not a sound alone (WCAG 1.4.1 for sound: the banner is the second cue), the
  Stop button reachable by keyboard; four languages.
- **Tests**: Vitest for `hunt-rules.ts` against the vectors and for the service with a fake `watchPosition`, fake `document.visibilityState`,
  fake `AudioContext`; a Playwright run in `tools/live-ui` with `context.grantPermissions(['geolocation'])` and `context.setGeolocation`
  stepped along a scripted street (a fixture of 60 fixes past a seeded house), asserting the banner text, the nearest-house card, the
  visit row after a 4-minute stay (the fixture's clock jumps through `page.clock`), the stop on hidden, and that IndexedDB holds no
  `hunt` rows; run after the deploy as TC-M-26 does (`node live-ui.js --hunt`).

### 9.4 Tickets (same style as S4b-FR-13..18; a follow-up batch)

| ID | Title | Depends on | Done when |
|---|---|---|---|
| S4b-FR-19 | **Hunt rules as a shared contract**: `docs/schemas/hunt-vectors.json` (near-house alert, stay/visit, gate, thinning); `HuntEngineTest` reads it; web `shared/hunt-rules.ts` written from the text; a named mutation per rule | FR-13 (the vector-test pattern) | Both stacks pass every case |
| S4b-FR-20 | **Hunt mode on the Map page (website)**: the card and switch, `watchPosition` through `TraceRecorderService`'s seam, the nearest-house text, the near-house banner and beep, the stay → visit with *Are you at a house?*, stop on hidden after 30 min, battery stop where the API exists, strings in four languages, a11y sweep | FR-17, FR-19 | Vitest TC-U-15x green; owner's TC-M-6x on a phone browser and a computer |
| S4b-FR-21 | **Playwright live-UI Hunt run**: scripted geolocation, the fixture street, the assertions of 9.3 | FR-20 | Runs green after a deploy |
| S4b-FR-22 | **Street alerts on the website**: owner decision on Nominatim's automatic use (cap, headers) or *not on the website*; guide text either way | FR-20 | Decision recorded in [10](../10-sprint-log.md); if built, the lookups capped and paced |
| S4b-FR-23 | **Guide**: *Hunt mode on the website: what works while the page is open* (en, hi, ta, te), the table of 9.2 in plain words | FR-20 | Pages merged; docs 01/02/11/06 updated (FR-108.., PRV-031 extended, T-I30) |

Requirements to add when the owner says yes: FR-108 (Hunt mode on the website: foreground-only, the behaviours of 9.2 marked *works*),
PRV-032 (the website's Hunt mode never stores a fix outside the trace and asks for the location only at the switch). Threat model: no
new asset (visits exist; the trace is T-I30); one new line in T-I30 for the in-memory alert state.

Owner decisions needed first: (1) build it at all (the phone app does this in a pocket; the website cannot); (2) street alerts and
Nominatim; (3) whether a visit recorded by the website's stay detector should be marked with its source (`VisitSource.AUTO` as on the
phones, or a new `AUTO_WEB` so a rougher laptop fix is told apart: recommend `AUTO`, with the accuracy gate the same).
