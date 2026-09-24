# Work in progress: CMP-5+6+7 combined change (handoff notes)

Status on 2026-09-24. This file saves the state of the combined change so a new session can continue. **Delete it**
once its content is in the SSDLC docs (the docs step below). Branch `claude/doorprints-dev-continue-fzcge2`, on
`main` = `f8dd6f9` (PR #23, CMP-4 P4c, merged). No pull request is open yet for this work (it will be PR #24).

## Owner decisions this session (verbatim where quoted)
- "After CMP4 is done, make all CMP-5, CMP-6 and CMP-7 changes at once and then test them together so that time can be
  saved": one PR for CMP-5, 6 and 7, committed in steps.
- "Complete Web backlog fixes (4 items) now along with other Android changes of CMP 5-6-7 as they are fixes and also
  finish Shared items that also change the web app (6 marked "Both") now": S4b-BL-1, 2, 6, 7 and 9, 10, 12, 13, 17, 20
  in the same PR.
- "The web app has display issues when opened on a mobile browser, check and fix those as well" (with a screenshot of
  the Map page on Android Chrome at a larger font size): in the same PR.
- The real-device map check (TC-M-25) is done by the owner "after CMP-8 as real world testing"; until then the emulator
  job runs on API 26, 34 and 36 (done, `e5e43af`).
- Vulkan vs OpenGL ES MapLibre: OpenGL ES for all now; a Vulkan build for Vulkan phones would need a second APK (backlog,
  at a Play release). iOS uses Metal, no split.
- Only run the live web UI test after a merge that runs the Web deploy (already in CLAUDE.md and docs/06 TC-M-26).

## Done and pushed (see `git log --oneline f8dd6f9..`)
CMP-5 (`84fa735`, `13091ac`), MapLibre OpenGL ES fix (`b47a67a`), CMP-6 (`f7e9ecd`, `7e73411`, `cff4a63`), web backlog
(`a9c28e8`, `95256e0`, `92de59e`), CMP-7 (`e2b163c`), S4b-BL-13 (`eaf06a0`), S4b-BL-17 (`52453f7`), S4b-BL-20 with the
backend `maxSyncVersion` (`0989550`), S4b-BL-42 (`91dfa7a`), map label diagnostic (`c7d0f8b`), S4b-BL-12 (`18b631e`).
CI green on the pushed commits (Android, emulator API 26/34/36, iOS compile, backend, web, security).

## In progress: mobile web display fixes (WIP commit)
Committed as work in progress; NOT finished. Fixed so far: the map attribution collapses to (i) on narrow screens; the
status legend is one line above "Add house" with no overlap; a mobile pass in `tools/live-ui/live-ui.js` (Pixel 7,
Galaxy S9+, iPhone SE, iPhone 14, 320/360 widths, landscape, text 130-200%). Still open:
1. The after screenshot of the owner's view (384x615, text 130%) shows NO map labels (before had them): check the
   `web/src/app/shared/map-style.ts` change, or whether the shot was taken before glyphs loaded; add a live-ui check
   that symbol layers render.
2. The "Your houses" counter row is still mostly under the bottom nav (numbers and captions hidden) — the owner's issue.
   Work started in `web/src/app/pages/map/list-peek.ts`.
3. 14 live-ui mobile failures "house-not-created" in hi/te (the script probably clicks an English label).

## Remaining steps
1. Finish the mobile fixes; web tests (`cd web && npx -y node@24 node_modules/@angular/cli/bin/ng.js test
   --watch=false` and `build`) and the live-ui mobile pass against a local build must pass.
2. Docs step for everything (the notes below): ADR-22 (BL-12 `within` rule, side effects, BL-17, Doklam unchanged),
   ADR-23 P5-P7 as built and the combined-PR amendment, docs/02 RR-16, docs/05, docs/06 (new tests, TC-I-35 three API
   levels, TC-M-25 zoom 9-14 and deferred to after CMP-8, emulator can't draw labels), docs/08/03 API (`maxSyncVersion`),
   docs/10 (§13.9+ for CMP-5/6/7, backlog S4b-BL-35..46 and new ones: Vulkan APK variant, emulator label limitation,
   BL-20 web announce/Android notification), docs/14, READMEs, CHANGELOG. Then delete this file.
3. Open PR #24; reviews: code, design/UX, web, docs, and the India view on its own; fix; merge when green and approved.
4. After the merge: the live web UI test (the web app changed). Then CMP-8 (iOS shell), then the owner's device check.

## Detailed notes from each step (copied from the session's working notes)
# Notes for the CMP-5..7 docs step (lead)
## Branch commits so far (on f8dd6f9)
- e5e43af ci: emulator smoke tests at API 26, 34, 36 (owner request 2026-09-24: "Till then extend the emulator job in GitHub Actions to two or three Android versions, e.g. the oldest supported and the newest."). Owner will do TC-M-25 real-device check after CMP-8 ("I will do this after CMP-8 as real world testing after completion of development").
- 84fa735 / 13091ac: STEP 1 = CMP-5 (see step-1 report below).
- b47a67a fix: MapLibre android-sdk-opengl instead of android-sdk (Vulkan). Found by the API 26 job (run 35968526446, SmokeTest.everyTabOpens: java.lang.Error "No Vulkan compatible GPU found" in VulkanRendererStrategy). Owner asked about Vulkan for new phones + OpenGL for old: not possible in one APK (same classes/native lib names, no runtime fallback); possible with two build variants + Play multi-APK uses-feature android.hardware.vulkan.version routing. Recommendation: OpenGL ES for all now; backlog item for a Vulkan variant at Play release. iOS uses Metal (MapLibre iOS), no split.
## Step 1 (CMP-5) report summary
- Deps: JetBrains navigation-compose 2.9.2 (-> androidx nav 2.9.7), JetBrains lifecycle 2.11.0 (runtime-compose, viewmodel-compose, viewmodel-savedstate) as api in :ui commonMain; kotlinx-serialization-json in :ui commonMain; coroutines-test commonTest; core-ktx + activity-compose in :ui androidMain. Catalog androidx-navigation 2.9.5->2.9.7, androidx-lifecycle 2.9.4->2.11.0.
- Moved to :ui commonMain: Root.kt (DoorprintsRoot, bottom bar, NavHost, deep-link effect, Routes + SETTINGS/EXPORT/IMPORT/NOTIFICATION_SCREENS; Tab->NavTab), HouseListScreen (+undoneSentence), AssistantScreen (+aiErrorText; AssistantViewModel common ViewModel(repo, LocationSource, SavedStateHandle); Formats.oneDecimal), SettingsScreen (+SyncOutcome.text, SectionHeading), NotifyAsk, LocationPermission (rules public; LocationAsk(platform)).
- New in :ui commonMain: AppServices/LocalAppServices (repository, appScope, location, copyImports, settingsScreen: SettingsServices, consumeLanguageChange), PermissionRequests (expect), PlatformServices + locationAccess/locationAsked/markLocationAsked/canAskLocation/openAppSettings/canPostNotifications, DeepLink sealed interface, RootScreens slots.
- :ui androidMain: PlatformServices.android (keeps composition context), LocationPermission.android (prefs file map_permissions unchanged), PermissionRequests.android. :ui iosMain: PermissionRequests.ios stubs.
- :shared commonMain export/CopyRecord.kt: CopyRecord (+KEEP_MS), CopyUndoOutcome (was CopyImportUndo.Outcome).
- :app new: AndroidAppServices, ui/AndroidRootScreens (+ProvideAppServices), ui/AppRepository; removed CompareTab.kt (S4b-BL-34 wrapper part done). Intent parsing stays in MainActivity. Notifications.SCREEN_* == Routes.*.
- House list reads status labels from Compose resources (S4b-BL-31 part).
- Backlog proposed: S4b-BL-35 (ExportProblem.messageRes Android strings via SettingsServices.backupErrorText; move with P6b), S4b-BL-36 (iOS seams stubs; CMP-8), S4b-BL-37 (AppServices features Android-only: CopyImportUndo/ImportUndo java.io.File, AppLocale, WorkManager/SAF backup, Play services location; related S4b-BL-32), S4b-BL-38 (stay-alert link with visitId has no JVM test; cover in emulator smoke test).
- Tests: :app 177->173 (-9 LocationAccessTest moved, +5 RootNavigationTest), :ui 67->86 (+9 LocationAccessTest, +6 AssistantViewModelTest, +3 LocationAskTest, +1 FormatsParityTest case), :shared 196. 64 screenshots unchanged.
- Behaviour notes: house list undo records one IO hop per read; Settings "Back up now" flow lazily created once per process; NotifyAsk condition equivalent.

## Web backlog (S4b-BL-1, 2, 6, 7, 13 web, 20 web)

Commits (web/ only, not pushed): a9c28e8 (sync + reset), 95256e0 (house page + styles + boundary comments), 92de59e (specs + web/README.md).

- **S4b-BL-1** (`web/src/app/data/sync.service.ts`): `lastError` is cleared only when a run succeeds, not at the start. New `recordFailure()`: `failureRun` goes up only when the run was forced or the message differs (`sameMsg`: key + params). An unforced run failing the same way changes nothing, so `lastErrorForced` stays as it was and the forced alert does not move to the polite line. Your data (`pages/data/data-page.html`): the forced failure card sits in `.refresh-slot` with `[class.stale]="sync.running()"` and a `role="progressbar"` bar named `data.syncing` ("Syncing…") while running. First-run banner (`shared/app-banners.ts`): the failure line is no longer hidden while running. It stays in a `.refresh-slot.failure-slot`: glyph at `--stale-alpha`, bar named `data.migrationRunning`, constant bottom padding so nothing moves. `app.ts` comment updated.
- **S4b-BL-2** (`pages/house-detail/house-detail-page.ts/.html`): `persist()`, `useMyLocation()`, `fillFromListing()` and `fillAddress()` no longer clear their card at the start. Each clears it on success and replaces it on failure. The top save-error card, the listing-fill error and the location/lookup message each sit in `.refresh-slot` `[class.stale]` while saving / filling / locating or geocoding, with a bar named `house.saving`, `listingFill.working`, `house.locating` or `house.lookingUp`. Docs still has to record the scope in docs/05 §5 and §5.1; I did not touch docs/.
- **S4b-BL-6** (`web/src/styles.css`): `input[aria-invalid='true'], textarea[aria-invalid='true'] { border-color: var(--error-text); box-shadow: inset 0 0 0 1px var(--error-text); }`. That draws a 2px edge with no layout shift, and it is placed after `:focus-visible`, so it stays on focus while the outline shows focus. Contrast (WCAG 1.4.11, computed):
  - light `#B3261E`: 6.54:1 on `--surface` `#FFFFFF`, 6.02:1 on `--bg` `#F4F6F5` (5.79:1 on `--surface-2` `#EEF2F0`);
  - dark `#FF8E86`: 7.40:1 on `--surface` `#19211F`, 8.25:1 on `--bg` `#101614` (6.57:1 on `--surface-2` `#212B28`).
  - docs/05 §4 could list these (Docs).
- **S4b-BL-7**:
  - `pages/plan/plan-page.spec.ts` (TestBed, 4 cases): a typed start; a field made invalid then corrected; a location failure (code 1) withdrawn by a map click; `aria-describedby` / `aria-invalid` for "Choose a start point first" (lat marked, both described by `start-msg`, focus on lat). jsdom has no WebGL 2, so the specs replace maplibre-gl with a stand-in through `vi.mock('maplibre-gl')`, classes built in `vi.hoisted`, with no `importOriginal`: loading the real module from the factory is a chunk cycle in the full suite. The Angular unit-test builder refuses `vi.mock` of relative paths.
  - `pages/house-detail/house-detail-page.spec.ts` (TestBed): exactly the two `startWithoutPosition()` guard cases. For `/houses/new` with no position and a stored draft present, the page is destroyed before `houses()` resolves, and again before it rejects. In both, no draft is opened, `house.draftRestored` is not announced and `TitleOverride.message` is never set non-null.
  - The same file also has 4 S4b-BL-2 cases in a separate `describe` (save, locate, lookup, listing fill): the same card node stays, stale, with the named bar, and is removed when the run ends.
  - Mutation-checked: dropping the two `destroyed` guards fails the 2 guard cases, and reverting the BL-2 code fails the 4 BL-2 cases.
- **S4b-BL-20 web half, detection signal**: the backend has no "max syncVersion" endpoint, and an empty pull (`?since=cursor` → `[]`) looks the same for a reset server and a healthy one. So the client uses the **push response**:
  - `PUT /api/houses/{id}` and `PUT /api/visits/{id}` return the stored DTO. Every accepted write takes `nextval('sync_seq')`, one sequence for all tables, under an advisory lock (backend `SyncVersions`), so on a healthy server it is above every cursor.
  - `serverWasReset(sentUpdatedAt, answer, highestCursor)` (exported) is true when `highestCursor > 0`, the answer's `syncVersion` is usable and ≤ `max(house, visit, photo cursor)`, and the write was **accepted** (answer `updatedAt` ≤ sent `updatedAt`). When LWW keeps the server's newer row, the backend returns that row with its old version and a later `updatedAt`, and that is excluded. `ClientClock` only clamps times down, so an accepted write is never later.
  - Server "delete all my data" is not a false positive, because the sequence carries on.
  - On detection (`ServerReset` thrown in `push()`, handled in `pass()`): `LocalStore.markAllForResync()` marks all houses and visits dirty and sets `uploaded=false` on stored photos with blobs (pending photo deletes kept; a delete of a missing photo is a no-op server-side). Then the three cursors go to `'0'` (rows are marked first, so an interrupted reset re-detects), push runs again, pull runs from 0, `serverResetAt` is set, and `Announcer` says `data.serverReset`. Your data shows the same string on the sync card, outside the live region. Cursors at 0 mean it cannot re-fire in the run.
  - **Limit**: detection needs a push, so a browser with nothing dirty keeps pulling from its old cursors until its next edit. Full detection without a push needs a backend change, for example the highest version in `GET /api/stats`. That is a backlog item; no backend change was made.
  - The spec's FakeApi now answers pushes with an incrementing version (starting at 1000), as a real server does.
- **S4b-BL-13 web half** (comments only): `shared/india-boundaries.ts` header rule 2, the `TILE_ZOOM_GUARD` doc and the `takesTileZoomGuard` doc now say that maplibre-gl (`worker_tile.ts:109`) **and** maplibre-native (`geometry_tile.cpp:317`) skip a minzoom 5 layer in a zoom 0-4 tile, so the guard is defence in depth on both renderers. The native part is "not yet checked on a device (TC-M-25 (7)-(9))". The web/README.md intro sentence says the same. No expression changed.
- **New strings**: `data.serverReset` in en/hi/ta/te (hi/ta/te marked under review in a comment):
  - en: "Your server seems to have been reset or set back to an older copy. Doorprints is sending everything in this browser to it again and downloading everything from it. Nothing here is lost."
  - Tamil uses சேவையகம் as the existing strings do.
  - No "Restore" wording.
- **Specs**:
  - `sync.service.spec.ts` +9: 3 for BL-1 (kept during retry / cleared on success; background repeat keeps the node, a new message renews it; a forced failure is kept through a background repeat, and a forced repeat renews it) and 6 for BL-20 (reset detected from an empty DB; a DB set back to an older dump; no false positive on an ordinary empty pull; none on a push answered above the cursor; none on a kept newer LWW row; `serverWasReset` unit table).
  - `plan-page.spec.ts` 4; `house-detail-page.spec.ts` 6.
  - Total 19 new.
- **Results** (`npx -y node@24 node_modules/@angular/cli/bin/ng.js …`): `test --watch=false` passes 39 files and 492 tests. `build` passes; the only warnings are the two NG8102 that were there before (house-detail-page.html `contactPhone ?? ''`, map-page.html `bedrooms ?? 0`). The live UI tool was not run.
- web/README.md: new change-log row dated 2026-09-24. The *Deferred to Sprint 4b* rows for S4b-BL-1, -2, -6 and -7 are now **Done**.

## Step 2 (CMP-6)
Commits (interleaved with the lead's web commits a9c28e8..92de59e): f7e9ecd P6a, 7e73411 P6b, cff4a63 fix + tests.

### What moved
- P6a: HouseEditScreen (+ PhotoDeleteViewModel, now `internal`, a common ViewModel(repository, appScope); photo viewer, paste dialog, checklist/rating rows) -> :ui commonMain. RootScreens.HouseForm slot gone; Root calls HouseEditScreen directly.
- P6b: ExportScreen, ImportScreen, ImportViewModel -> :ui commonMain. RootScreens.Export/Import slots gone; RootScreens now only has Map (AndroidRootScreens forwards to MapScreen). AppRepository.repository() is used only by MapScreen.
- :shared commonMain (package app.doorprints.export): ExportProblem (enum + fromCode; `ExportProblem.of(Throwable)` stays in :app as a companion extension, export/ExportProblemOf.kt), new ImportFlow.kt with ImportCheck (+REPLACED_LABELS), ImportStaging (was Imports.Staging), ImportRequest (data + KEY_*; `toData()` / `ImportRequest.fromData()` are :app extensions in ExportRequest.kt), ImportStart(id: String, queued). data/ExportMappers.kt: `Repository.LocalRows.toBundle(options)` (ExportBuilder.build delegates; the Export count uses it).

### Seams / API (all in :ui commonMain unless said)
- AppServices gains `houseForm: HouseFormServices`, `exportScreen: ExportServices`, `importScreen: ImportServices` (Android impls: AndroidHouseFormServices in AndroidAppServices.kt; AndroidExportServices, AndroidImportServices in app/export/AndroidBackupServices.kt).
- HouseFormServices: reverseGeocode(lat, lon): Place?; clearVisitAlert(visitId); @Composable rememberPhotoSources(onPicked): PhotoSources {takePhoto, pickFromGallery}; addPhoto(houseId, PickedPhoto(uri: String)): AddPhotoResult; photoModel(path): Any (Android: File). Camera file/FileProvider/TakePicture/PickVisualMedia unchanged in the Android impl.
- PlatformServices gains dial(number) and openUrl(url): Boolean (from the activity context, as before). New `expect PlatformBackHandler(enabled, onBack)` (Android: androidx.activity BackHandler; iOS no-op).
- ExportServices: runs(): Flow<List<ExportRun>>, start(format, target, options): String, stop(run?), shareCopyTarget(fileName), cleanShareCopies(), clearDoneNotification(), screenVisible(Boolean) (ScreenWatch), @Composable rememberDefaultOptions(): () -> ExportOptions (composition context, as ExportBuilder.defaults(context)), rememberSaveToPicker(onPicked): (mime, name) -> Boolean (ExportGrants.take + hold inside the Android callback, false on ActivityNotFound), rememberFileActions(): ExportFileActions {open, share}.
- ImportServices: runs(), stop(), clearDoneNotification(), screenVisible(), @Composable rememberBackupPicker(onPicked): (folder?) -> Boolean (Saf.treeRoot of the settings folder computed at tap time), displayName/stage/preview/isStaged/discard/start.
- RunState (WorkInfo.State 1:1), data classes ExportRun / ImportRun (progress, target tag, output keys, same defaults: finishedAt 0, notified true, problem UNKNOWN / NOT_A_BACKUP).
- ImportViewModel(imports: ImportServices, copyImports: CopyImportUndoes, saved, scope?, startWork: (ImportRequest) -> ImportStart = imports::start); pick(file: String); startImport(withMode, skipUpdates) (no Context). Refresh gate reads `internal expect fun elapsedRealtimeMillis()` (Android SystemClock.elapsedRealtime, unchanged behaviour; iOS systemUptime).
- Common helpers: isWebLink (port of android.net.Uri.parse's scheme/host rule), Formats.coordinate (expect formatSixDecimals: Android String.format(Locale.ROOT, "%.6f"), iOS NSString format), internal nowMillis() (kotlin.time.Clock), joinList + joinedList (@Composable) + joinedListText (suspend) in ListText.kt; BackupTexts.kt: ExportProblem.messageResource, BackupProblem.messageResource, importWriteFailedResource, importStoppedResource, isShareCopy, exportResultText, importedText. Kotlin Uuid for new house ids. Coil 3 (libs.coil.compose) added to :ui commonMain.
- SettingsServices.backupErrorText removed; Settings reads stringResource(ExportProblem.fromCode(code).messageResource).

### Strings
- S4b-BL-31 (house form part): form reads status/checklist labels from Compose resources; `ChecklistLabels` and the Android check_* strings (4 languages) removed; HouseStatus.labelRes kept for HuntService. ModelMappingTest's ChecklistLabels asserts removed (ModelLabelsTest in :ui covers ChecklistResources).
- S4b-BL-35 done: copied to Compose resources (all 4 languages, identical text, StringParityTest green): import_list_two/three/middle (P6a), export_problem_* (3), problem_* (9), export_done, export_done_in, export_done_plain, export_done_partial(_in/_plain), export_ready, export_ready_partial, import_added, import_updated, import_done_nothing, import_restored_result (plural), import_write_failed(_copy). Android copies kept for the notifications. import_stopped / import_stopped_copy MOVED (no Android user left; ImportWorker.stoppedRes removed).

### Stayed in :app and why (new backlog ids)
- S4b-BL-39 (new): the Android implementations behind the CMP-6 seams have no iOS counterpart yet: HouseFormServices (Geocoder, camera + FileProvider, photo picker, addPhoto with Bitmap/ExifInterface), ExportServices/ImportServices (ExportWorker/ImportWorker on WorkManager, SAF pickers and ExportGrants, FileProvider share/open, notifications, Imports staging/preview with java.io.File/ZipFile/BackupReader), ExportProblem.of (JVM exception types), ImportRequest's WorkManager Data, ExportBuilder.defaults (AppLocale, TimeZone). For CMP-8; related S4b-BL-32, -36, -37.
- S4b-BL-40 (new): iOS stand-ins added by CMP-6 are unverified: PlatformBackHandler no-op (swipe-back), Formats.coordinate via NSString "%.6f" (may round exact halves differently from Java's HALF_UP), elapsedRealtimeMillis via systemUptime (excludes sleep), isWebLink / joinList only JVM-tested. Run the commonTests on the simulator with CMP-8 (like S4b-BL-33).
- S4b-BL-41 (new): result sentences duplicated in logic, not only text: ExportWorker.resultText vs exportResultText, ImportWorker.importedText vs importedText, writeFailedRes vs importWriteFailedResource, ProblemMessages vs messageResource, ImportWorker.joined vs joinedList. StringParityTest pins the texts; the branching is written twice. Proposal: one common builder taking a string lookup, used by both notification and screen.
- S4b-BL-42 (new): flaky screenshot run: export[hi-dark=true] once failed with CalledFromWrongThreadException (1 failure in 5 full runs + 3 screenshot-only reruns; all other runs green, 64/64 verified). Cause: Export's count LaunchedEffect resumes after withContext(Dispatchers.Default) on the worker under the compose-test continuation interceptor, which then applies the recomposition off the main thread (same mechanism step 1 noted for RootNavigationTest). The code path is unchanged from before CMP-6 (pre-existing race). Fix options: in the test, a main-thread effect dispatcher; or compute counts via a flow with flowOn(Default).

### Carry-in fix (PR #23 review)
- AndroidRepository.applyCopy: `written` is now Map<File, photoId>; the catch calls `discardUncommittedPhotoFiles(written)` = withContext(NonCancellable) { delete a file only if db.photos().get(id) == null (read failure counts as missing) }. Transaction not wrapped. Test RepositoryTransactionTest.aCancellationAfterTheCommitKeepsThePhotoFilesOfCommittedRows: after a committed copy, runs the cleanup in a cancelled coroutine (UNDISPATCHED + cancelAndJoin): committed cp1 file kept, rowless file deleted. The real commit-then-cancel ordering cannot be forced from a test (Room resumes the caller itself). Note: Room 2.8's suspend DAO read did not throw in a cancelled coroutine (checked by removing NonCancellable: test still passed), so NonCancellable is defensive; the test pins the row-present rule.

### Tests (JVM counts)
- :app 173 -> 172 (P6a: JoinListTest moved) -> 167 (P6b: ImportStartOnceTest 5 moved) -> 173 (+2 LinkParityTest, +3 BackupRunsTest, +1 RepositoryTransactionTest). :ui 86 -> 87 (P6a) -> 95 (P6b: +5 ImportStartOnceTest, +3 BackupTextsTest) -> 97 (+1 HouseFormRulesTest isWebLink, +1 FormatsTest coordinate). :shared 196 unchanged. 64 screenshots verify (no re-record).
- Moved/changed: JoinListTest -> :ui commonTest; ImportStartOnceTest -> :ui commonTest (FakeImports, NoUndo; kotlin.test); ImportModeTagTest keeps writeFailedRes only; ExportProblemTest imports `of`; RootNavigationTest drives the real house form ("Save a house", lat/lon "12.971600"/"77.594600", back arrow; stored house -> "House details") and real Export ("Save a copy"), with the FileProvider sCache reset in @Before (as ScreensScreenshotTest).
- Commands: the full command (assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest both metadata compiles, -Proborazzi.test.verify=true, assembleDebugAndroidTest) and the iOS set (-Pkotlin.native.enableKlibsCrossCompilation=true :shared:compileKotlinIosSimulatorArm64 :ui:compileKotlinIosSimulatorArm64 :ui:compileTestKotlinIosSimulatorArm64) passed before each commit (final state after the amend: MAIN-OK, IOS-OK). About 2-3 min per full run here.

### Behaviour notes
- No intended behaviour change. Equivalences: new house id via kotlin Uuid (same lowercase 36-char form); times via kotlin.time Clock (= currentTimeMillis); paste summary and partial-backup/Replace-dialog lists read Compose resources instead of Android ones (same text, same default-locale language); picker start folder computed at tap instead of remembered per setting (same value); the export picker's grant is taken and held in the Android callback before the screen's onPicked (same order relative to start); cameraUri still computed at composition (FileProvider) in the Android impl.
- iOS: back handler, pickers, workers are not implemented (compile-only), see S4b-BL-39/40.

## Boundary data (S4b-BL-9, 10, 12, 17)

Done 2026-09-24 by a sub-agent, branch `claude/doorprints-dev-continue-fzcge2`. Scratch evidence in
`scratchpad/geo/` (all paths below are relative to /tmp/claude-0/-home-user-doorprints/4aa17aaa-16c1-5798-800b-0b242ead6062/scratchpad/).
Natural Earth inputs fetched at commit `ca96624` from raw.githubusercontent.com into `geo/ne/geojson/`; the current file
was first rebuilt byte for byte (sha256 `25984afa…a024`) before anything changed. Tile cache: `geo/tilecache/` (about
12 000 tiles). No Gradle run.

### S4b-BL-9: re-check after an OpenFreeMap update (recurring; stays open)
- Check of 2026-09-24 ~08:30 UTC: `https://tiles.openfreemap.org/planet` TileJSON still serves
  `planet/20260913_164504_pt` = the planet the data and `SHARED` were built on. Nothing to rebuild.
- Style: `https://tiles.openfreemap.org/styles/liberty` (sprite `ofm_f384`, 111 layers) compared with the web fixture
  `web/src/app/shared/testing/liberty-style.fixture.ts`: all 18 fixture layers (`boundary_3`, `boundary_2`,
  `boundary_disputed`, every `place` label layer incl. `label_state`, and their neighbours) are JSON-identical, same
  order, same sources and sprite; no new layer on source-layers `boundary` or `place`. Copy in `geo/liberty.json`.
- `find_shared_stretches.py` re-run on a `--no-shared` build (3 min 50 s cold, output `geo/find_out.txt`): the 7
  stretches are identical to `SHARED` digit for digit. Conclusion: SHARED and in-boundaries.geojson did not need
  rebuilding for BL-9 (the BL-17 rebuild below is the only data change).

### S4b-BL-10: a Survey of India-derived outline — no swap; owner decision stays open
Findings (no file found whose licence clearly allows redistribution in an AGPL app):
- Survey of India's Online Maps Portal (https://onlinemaps.surveyofindia.gov.in) lists the "Administrative Boundary
  Database" and Open Series Maps at Rs 0 (Pricing Policy https://onlinemaps.surveyofindia.gov.in/PricingPolicy.aspx; FAQ
  https://onlinemaps.surveyofindia.gov.in/FAQs.aspx Q6/Q7: free to download, some items without login). Neither page
  states any licence, redistribution, derivative-work or attribution terms; the Pricing Policy says the GIS data "are
  free for users registered as 'Government Users'".
- SoI's own Digital Licence (https://surveyofindia.gov.in/files/Digital%20Licence.pdf, text extracted to
  `geo/soi_digital_licence.txt`) excludes, without SoI's prior written permission, "(i) The development of
  value-added products", "(iv) The incorporation of digital products in a third party product" and publication "The
  term publish includes sale or free distribution"; 4.8 "The Licensee shall ensure that no SOI mapping is exported";
  licensees are Indian individuals/organisations. Incompatible with bundling in an AGPL app / public repo.
- Geospatial Guidelines 2021 (https://onlinemaps.surveyofindia.gov.in/GeospatialGuidelines.aspx): "SoI published maps
  or SoI digital boundary data are the standard to be used, which shall be made easily downloadable for free and their
  digital display and printing shall be permissible", and public-funded data accessible "to all Indian Entities". This
  permits display/printing, not redistribution or derivatives worldwide; National Geospatial Policy 2022 (PIB
  https://static.pib.gov.in/WriteReadData/specificdocs/documents/2025/feb/doc2025227509401.pdf) is a policy, not a
  licence. OSM wiki (https://wiki.openstreetmap.org/wiki/Open_Geospatial_Data_from_Government_of_India) likewise lists no
  SoI boundary dataset with redistribution-compatible terms.
- GODL-India (worldwide, royalty-free, derivatives allowed with attribution; https://jk.data.gov.in/godl) would be
  compatible, but I found no SoI India-outline file published under it (data.gov.in "District Boundary"
  https://www.data.gov.in/resource/district-boundary could not be inspected: page is JS-rendered/403 to fetchers).
- datameet/maps (https://github.com/datameet/maps, Country/README.md): `india-soi.geojson` is "Dissolved shapefiles from
  the official Indian shapefile data" (Census 2011 districts / SoI) under "CC-by-sa 2.5 / ODbL" as asserted by datameet;
  the SoI copyright behind it is not cleared by SoI, so not "clearly" redistributable. `india-composite.geojson` is CC-0
  but built from US LSIB + a Pakistan dataset + Natural Earth — not SoI-derived.
Recommendation: keep Natural Earth. Options for the owner: (a) write to SoI (mtr.soi@gov.in, per the FAQ) for written
permission to redistribute the external boundary in an open-source app, or wait for SoI to publish it under GODL; (b)
if only accuracy matters, evaluate datameet `india-composite.geojson` (CC-0, LSIB-based) against the tiles with the
same decode as RR-16 — a separate backlog item, not an SoI source.

### S4b-BL-12: Pakistani and Chinese admin lines inside India's outline — FOUND from zoom 9; rule NOT added (blocked)
Method: `geo/bl12.py` (output `geo/bl12_z5_8.txt`, `geo/bl12_z9_14.txt`): every tile of the planet 20260913
`boundary` layer over the PAK/CHN-held parts of India's outline (NE IND-view polygon ∩ NE default-view PAK/CHN, west
and middle sectors, +5 km) at zooms 5-12 (4/8/16/36/90/255/798/2748 tiles), and at 13-14 every tile along an admin
3-6 line found at z12 (1992/5145 tiles). A feature counts when Liberty's `boundary_3` filter draws it (admin 3-6, not
maritime, `disputed` != 1, no `claimed_by`) and it reaches more than 1 km inside the outline.
- Zoom 5-8: only admin level 4 in the tiles here; one drawn piece in the held part: admin 4, disputed 0, tile
  5/22/12 … 11/1442/822, from (73.586, 33.426) to (73.61, 33.288): Pakistan's Punjab-AJK line, which in India's view
  is the claimed border itself; it runs along our outline and dips about 1.5 km inside (Natural Earth offset). Same
  class as the doubled lines (S4b-BL-11), not a Pakistani unit line inside. Everything else admin 4 there is disputed
  (GB-AJK, GB-KPK, Xinjiang-Tibet: 16-208 parts flagged disputed or `claimed_by` CN/IN), confirming the Docs decode.
- Zoom 9 and above: the tiles add admin levels 5 and 6 (none at z5-8), and these are NOT flagged disputed. Pieces drawn
  inside the PAK/CHN-held part: z9 92, z10 141, z11 248, z12 468, z13 909, z14 1804. At z12 by area (km inside the
  outline, tile overlaps double-counted): Gilgit-Baltistan admin 5 ~673 km / admin 6 ~1401 km; AJK admin 5 ~130 /
  admin 6 ~349; Aksai Chin admin 5 ~7 / admin 6 ~247 (a Chinese county line from about (79.6, 36.0) to (79.9, 34.9));
  Shaksgam admin 5 ~1 / 6 ~69. Properties carry only `admin_level`, `disputed: 0`, `maritime: 0` (no adm0, no name),
  e.g. tile 10/721/402 admin 5 from (73.834, 35.876); 9/360/200 admin 6 from (73.637, 36.022). Map:
  `geo/bl12_boundary3_z12.png`. So TC-M-25's admin-line check (zoom 6-8) passes, but at zoom 9+ Pakistani district and
  tehsil lines show as dashed lines across Gilgit-Baltistan and PoK, and a Chinese county line in Aksai Chin.
- Why not added: the properties give no way to tell these lines apart, so the only rule is spatial: in
  `boundary_3`'s filter, `["!", ["within", P]]` with a polygon P. On Android the boundary_3 filter is applied in
  `android/ui/.../IndiaViewOps.kt` (IndiaViewRules.kt only holds strings; `tileZoomGuardFor(existing)` has no layer id),
  which is outside the paths I may edit, and IndiaViewRules.kt is being edited by the other agent. A web-only rule
  would break parity, so neither app was changed. Also to confirm before building: maplibre-native's `within` on
  LineString features (maplibre-gl supports it), and the owner/ADR-22 wording (a polygon in the style).
- Candidate ready for Web + Android: `geo/bl12_candidate_polygon.geojson` (1 polygon, 404 vertices): the PAK/CHN-held
  parts of the NE outline grown 10 km outward, minus the Indian-administered part shrunk by 2 km (so P reaches 2 km
  across the LoC/LAC), simplified at 0.01 degrees. On the z12 decode, `within P` hides 471 of the 476 drawn pieces in
  the held parts (GB 336, AJK 86, Aksai Chin 41, Shaksgam 8) and 0 of 161 Indian-administered pieces; 5 edge pieces
  (~23 km) still show. Side effect: Pakistani/Chinese admin lines within 10 km outside India's outline also hide.
  Needs: the filter in `india-boundaries.ts` and `IndiaViewRules.kt`/`IndiaViewOps.kt`, tests on both sides, ADR-22,
  TC-M-25 extended to zoom 9-14.

### S4b-BL-17: hand-overs at Sikkim NW tri-junction, Jomotsangkha, Longwa — done; Doklam left as it is (commit 52453f7)
- `find_shared_stretches.py`: new `tidy()` step after each stretch's hand-over is chosen (constants CROSS_KM 10,
  RUNON_KM 12, RUNON_MIN_KM 0.5, TRIJUNCTION_DEG 0.0005; `lines_at` also keeps neighbours' drawn lines and other
  undisputed admin-2 lines; `drawn()` helper). (1) Where our outline, going on past a hand-over, first crosses a
  neighbour's drawn tile line within 10 km, the stretch ends at that crossing with no connector. (2) Else, where India's
  tile line runs on past the hand-over and stops within 12 km where the tiles' only continuation is disputed, the
  connector goes from the nearest point of our outline beyond the old hand-over (≤ MAX_KM 7) to where the tile line
  stops. A tile line that stops at a tri-junction of the tiles' own keeps its hand-over. Docstrings of both scripts
  updated. Re-run output (`geo/find_out_new.txt`) pasted into `SHARED`; it changes exactly 3 of 7 stretches:
  - Sikkim NW (stretch 3 end): (88.16462, 27.84536)->(88.17786, 27.85627) becomes the crossing (88.11262, 27.8688) on
    the Nepal-China tile line (adm0 CHN|NPL); the 13 x 3 km loop is gone (our line meets the tile line in a T; the
    Natural Earth tri-junction is 88.118218, 27.860885, OSM's 88.135, 27.8816).
  - Jomotsangkha (stretch 5 end): hand-over (92.06749, 26.88307)->(92.0777, 26.85844) becomes
    (92.0758, 26.90129)->(92.11504, 26.89452), the tile line's end (tile run-on 6.0 km at z11; beyond it the tiles have
    only a disputed `claimed_by: CN` piece); the connector is now 4.0 km, roughly east-west.
  - Longwa (stretch 6 start): tile point (95.22614, 26.6697) becomes the tile line's end (95.23396, 26.68247) (run-on
    1.7 km at z11, same end at z9/z14; continuation disputed `claimed_by: CN`); outline point unchanged (95.24693,
    26.6489), connector 4.0 km (was 3 km). Visual note for the lead: the tile line and the connector now meet at about
    49 degrees, a narrow V instead of a dangling 1.7 km end.
  - Doklam: NOT changed. Our outline (NE tri-junction at Batang La, 88.892331, 27.315543) never crosses the tile line
    at tile zooms 9-14; the "loop" is the tile Bhutan line (adm0_l BTN only) running on 3.7-4.5 km east of the
    hand-over to (88.9055, 27.274), OSM's Gyemo Chen tri-junction, where an undisputed CHN line and a disputed line
    meet. Handing over there would join India's outline to the tri-junction China claims (India holds Batang La), so
    the rule deliberately skips tri-junction ends. Lead/owner decision if anything else is wanted there.
- Data: both copies rebuilt byte-identically. sha256 old `25984afa459110523eec6088ee0440eca95567dd290541c9ccb8c5ad2c3ea024`
  (415 608 bytes) -> new `c3cdf5fb79cf3620526b6ec9906c8c6a38999091eb5821a799f3cc1efa8bf63f` (415 606 bytes); `claim` 5
  pieces 1 666 points (was 1 670), shared stretches 7 / 175 points (was 171), `world` 359 lines, `state` 2 lines 214
  points. `IndiaBoundaryDataTest.EXPECTED_SHA256` updated (+ one KDoc line); not run (no Gradle).
- No change elsewhere: segment-level diff old vs new (`geo/diffgeo.py`), both for what draws below zoom 5
  (world+claim+state) and from zoom 5 (claim+state): every changed segment lies in the three changed places
  (Jomotsangkha 3-4 segments, Longwa 1, Sikkim NW 2-5); outside the four places max displacement 0 km (no segment
  added or removed). Inside: removed claim up to 6.6 km (the Sikkim loop), new connectors up to 2.8 km off the old line.
- Renders (before | after, grey = tile `boundary_2` after the rules, red = our claim, blue dashed = our world below zoom
  5, purple = boundary_3): `geo/bl17_{sikkim_nw_trijunction,doklam,jomotsangkha,longwa}_z10.png` (tile z10, place
  extent) and `…_z12.png` (tile z12, close-up).
- Tests: web `ng test` 39 files / 492 tests passed after the rebuild. Android `IndiaBoundaryDataTest` not run.
- Docs to update by the lead (not done here): docs/10 §12.7 rows BL-9/10/12/17 and §12.10 figures, docs/03 ADR-22
  Consequences (loops/overruns), docs/02 RR-16, docs/06 TC-M-25 (zoom 9+ admin lines), CHANGELOG, web/README,
  android/shared/README (new sha256).

## Step 3 (CMP-7, BL-13 Android, BL-20 full, BL-42)
Commits on the branch (not pushed): e2b163c A (CMP-7), eaf06a0 B (S4b-BL-13), 0989550 C (S4b-BL-20), 91dfa7a D (S4b-BL-42). The boundary-data agent's commit 52453f7 (S4b-BL-17) sits between B and C; none of its files were touched here.

### A) CMP-7, the Map (e2b163c)
**What moved / changed**
- `MapScreen` (the chrome: Hunt card, location/notification notes, controls column/row, legend, snackbar, attribution placement, saved camera, first framing, retry) moved to `:ui` commonMain (`ui/.../MapScreen.kt`), with the same KDoc, rules (MapRules) and composition. The root calls it directly: `RootScreens` and `:app`'s `AndroidRootScreens` are gone; `DoorprintsRoot(deepLinks, onDeepLinkHandled)` (no `screens` parameter). `:app`'s `ui/AppRepository.kt` (`repository()`) removed (its only user was the Map); `ProvideAppServices` moved to `:app` `ui/ProvideAppServices.kt` (was in AndroidRootScreens.kt). `currentLocation`/`lookUpLocation` stayed in `:app` as `ui/CurrentLocation.kt` (Play services; AppServices.location uses it).
- `HuntState` moved to `:shared` commonMain (`app.doorprints.location`, package kept) with `HuntState.MAX_ACCURACY_M = 50f`; `HuntService.MAX_ACCURACY_M` now aliases it.
- New seams (`:ui` commonMain):
  - `PlatformMap.kt`: `expect fun PlatformMap(houses, labelSizeSp, showLocation, attribution: MapAttribution, events: MapEvents, modifier)`; `MapControl` (setCamera(lat, lon, zoom, bearing?), moveTo(lat, lon, zoom, animate), zoomIn/zoomOut(animate), frame(points, paddingPx, maxZoom), camera(), reloadStyle()); `MapEvents` (onReady(control) before any listener and before the style load, onStyleLoaded, onFailed, onUserGesture, onCameraIdle(spot), onHouseTap(id), onLongPress(lat, lon)); `MapAttribution(startPx, bottomPx, shown)`; `CameraSpot` + saver; India start 20.59/78.96 z4.
  - `AppServices.mapScreen: MapServices` (hunt: StateFlow<HuntState.State>, startHunt(): Boolean, stopHunt(), clearHuntStopReason(), notificationsReachUser(), @Composable rememberAllowNotifications(onAnswered), animationsOff(), fontScale()). Android: `AndroidMapServices` in `AndroidAppServices.kt` with MapScreen's old code (HuntService.start/stop with the application context; the notification prompt and `ACTION_APP_NOTIFICATION_SETTINGS` from the composition's context, `notificationsRefused` rememberSaveable moved into it; `Settings.Global.ANIMATOR_DURATION_SCALE`; `resources.configuration.fontScale`).
  - Location permission: the chrome uses the common `rememberLocationPermissionRequest` and `platform.locationAccess() == PRECISE` (= `hasLocationPermission`, the fine grant); `platform.openAppSettings()`.
- Android actual `PlatformMap.android.kt` (`:ui` androidMain, `implementation(libs.maplibre.android)` = the catalog's OpenGL ES `android-sdk-opengl`, line in libs.versions.toml untouched): the same `MapView` in `AndroidView`, `rememberMapViewWithLifecycle`, north-up settings, click hit test (48 dp), long press, camera move/idle listeners, `setStyle(MAP_STYLE_URL)` → `applyIndiaView(MapLibreStyleOps(s))` → `addHouseLayers` → `onStyleLoaded`; the style-keyed effects (houses GeoJSON, label size, location dot) and the attribution effect moved here unchanged. In inspection mode (previews, Robolectric) it draws an empty box, because MapLibre's native library cannot load on the JVM. `MAP_STYLE_URL`, `housesGeoJson` (org.json) moved here.
- iOS actual `PlatformMap.ios.kt`: an empty `Box` (compile-only; the chrome then shows "Loading the map…"). MapLibre iOS (`UIKitView` + `MLNMapView`, its own StyleOps) is CMP-8 (S4b-BL-43).
- India's view: `IndiaViewOps.kt` (`:ui` commonMain): `interface StyleOps` (layers(), kind(id), minZoom/setMinZoom, filter(id) as toArray form, filterText(id), andFilter(id, extraJson), hide(id), hasSource/addGeoJsonSource, linePaint(layerId, LinePaint): PaintValue?, addLineLayer(NewLineLayer, Placement), warn(msg, error)), `LinePaint`, `PaintValue` (Copied(platform value) | Color | Number | Dashes), `NewLineLayer`, and `fun applyIndiaView(ops: StyleOps)`: the steps of `:app`'s old `IndiaView.kt` in the same order with the same fallbacks, step isolation and warning texts. `MapLibreStyleOps` (`:ui` androidMain) is the MapLibre `Style` implementation (Log.w tag `IndiaView` as before). `IndiaView.kt` deleted. `IndiaViewRules` unchanged in behaviour (only its header KDoc points at IndiaViewOps); `IndiaViewRulesTest` unchanged except one comment line.
- Equivalences to note (no intended visual/behaviour change): the effects keyed on MapLibre's `Style` object now key on a style-load counter (`styleLoads`, same restarts: first load and each retry); the camera-idle listener hands every rest to the chrome, which saves it only when `framed` (as before); a failed "above/below" add retries on top with a new `LineLayer` object instead of re-adding the same Java object; Hunt start/stop use the application context instead of the activity (startForegroundService/stopService behave the same); `animationsOff`/`fontScale` read from the application (system-wide settings).
**Tests**: `IndiaViewOpsTest` (commonTest, 7 cases, a fake Liberty-shaped StyleOps that parses the filters as `toArray` would): all rules on Liberty (hide, country filter + minzoom 5, tile-zoom guard on boundary_2/boundary_3, outline above boundary_2 with WORLD_MAX_ZOOM, claim above world, copied paint, state line above boundary_3 from 5 with butt caps, both state label layers); a second run adds no layer twice; deprecated-syntax filters get the legacy rules and no guard (warned); no base layers → outline below the first symbol layer with Liberty's paint and the four warnings; per-step failures logged and skipped (filter, minzoom read, paint read, add-at-place → top, label filter read); a failed read of boundary_2 still adds the outline; a failed source add skips the outline and the state line and still filters labels. RootNavigationTest draws the real Map chrome (inspection mode; "Hunt mode" instead of the stand-in text). `SmokeTest.theMapShowsIndiasBoundary` (instrumented, compiled by assembleDebugAndroidTest, NOT run here: no emulator): waits for the "Loading the map…" line to go, taps Zoom in (so the first framing never moves the camera), finds the `MapView`, moves the camera to India z4 (22.5, 80.0), Jammu and Kashmir with Ladakh z6 (35.0, 76.5) and Arunachal Pradesh z6 (28.0, 94.0), waits for `OnDidFinishRenderingMap(fully=true)` up to 45 s, saves `screens/20_map_india_z4.png`, `21_map_kashmir_ladakh_z6.png`, `22_map_arunachal_z6.png` with UiAutomation (includes the GL surface) and `screens/map_notes.txt` (style/failure flags and which frames finished; also logcat tag SmokeTest). It passes when tiles do not load and says so in the notes file. android-emulator.yml already uploads the test storage (not touched).
**Stayed / backlog**: S4b-BL-43 (new): iOS side of the Map: PlatformMap is an empty box, no `StyleOps` over `MLNStyle`, no `MapServices` (Hunt mode, notification check, motion/font settings) — CMP-8 (with S4b-BL-36/-39/-40). S4b-BL-44 (new): the Map still has no JVM screenshot; its chrome can now be rendered in inspection mode (empty map), so a Map chrome screenshot set (4 languages × 2 themes) could be recorded (new reference images; CMP-0-BL-4 related). TC-M-25 re-run on a device: owner, after CMP-8 (unchanged).

### B) S4b-BL-13, Android half (eaf06a0)
Re-read maplibre-native `android-v13.6.1` `src/mln/tile/geometry_tile.cpp` (fetched from GitHub raw): line 317 in `GeometryTile::setLayers` (starts line 296) is `if (id.overscaledZ < std::floor(layerImpl.minZoom) || id.overscaledZ >= std::ceil(layerImpl.maxZoom)) { continue; }`; `tile_pyramid.cpp` calls `setLayers` at 167 and 193; `geometry_tile_worker.cpp:502` evaluates filters with `overscaledZ`. The KDoc was already corrected in shared README 1.39/1.40; this commit quotes the condition in `ADM0_PRESENT`'s KDoc and says in `tileZoomGuardedLayers`' KDoc that the guard is defence in depth and parity on both renderers, "not the fix on either" (device check TC-M-25 (7)-(9)); the `:ui` README's IndiaViewRules row says the same. The shared README 1.37/1.38/1.39 rows already carry the "corrected in 1.39" notes and were left as history. Comments/README only.

### C) S4b-BL-20 complete (0989550)
- Backend: `GET /api/stats` → `Stats(..., maxSyncVersion)`; `SyncVersions.highest()` = `select case when is_called then last_value else 0 end from sync_seq` (@Transactional(readOnly)). Chosen over max(sync_version) of the three tables because it is O(1) and never goes back on a healthy server, also after "delete all my data" (hard delete of rows, sequence continues), while a max over rows would drop and raise a false reset. A version taken by a still-open transaction may already count (only makes it higher: no false positive; cursors come from committed rows). Tests: `ApiIntegrationTest.statsCarryTheHighestSyncVersion` (≥ every returned/pulled version, grows with a new write), `BackupApiTest.deleteAllKeepsTheHighestSyncVersion`. `mvn -B -ntp verify` with JDK 25.0.4.1 (Temurin, downloaded to the scratchpad) against the `backend/db` image (docker build + run as backend.yml): BUILD SUCCESS, 287 tests, 0 failures, 1 skipped (GoldenSetEvalTest, as before). docs/03 §API table (line ~761, `/api/stats` → add `maxSyncVersion`) and docs/08 §11 are for the docs step; there is no OpenAPI file.
- Android: `StatsDto.maxSyncVersion: Long? = null` (old servers → null); `SyncRules.serverBehind(maxSyncVersion, cursors)` and `SyncRules.pushShowsReset(sentUpdatedAt, answerUpdatedAt, answerVersion, highestCursor)` (the web's `serverWasReset`, plus version 0 = unknown since the DTO defaults it); `SyncOutcome.serverReset` (stored as a sixth field `R` only when true: every other outcome keeps the five-field form, old values decode as before); `SettingsStore.resetCursors()`; DAO `HouseDao.markAllDirty()`, `VisitDao.markAllDirty()`, `PhotoDao.markAllForUpload()` (queries only, schema/identity hash unchanged). `AndroidRepository.sync`: once any cursor > 0, `api.stats()` first (a failure → unknown, the push surfaces real errors); behind → `resetForServer()` (rows first, then cursors), push all, pull from 0; otherwise the push checks each accepted house/visit answer and on a reset throws internally, resets, pushes everything again (the rows already pushed counted), pulls from 0. `AndroidRepository` gained `apiFor: (url, key) -> ApiClient = Api::client` for tests. Settings' outcome line (and the house list's warning reason, which uses the same `SyncOutcome.text()`) says `sync_server_reset` before the counts: en "Your server seems to have been reset or set back to an older copy. Doorprints is sending everything on this phone to it again and downloading everything from it. Nothing here is lost."; hi/ta/te = the web's `data.serverReset` with "this browser" → "this phone" (ta: இந்தத் தொலைபேசியில், as export_intro), marked under review in an XML comment. No "Restore" wording.
- Web: `StatsDto.maxSyncVersion?: number | null`; `serverBehind(maxSyncVersion, cursors)` exported; `SyncService.pass()` asks `api.stats()` (through `call`, so 429 handling and cancellation apply) once a cursor > 0 and resets before the push when behind; a failed or field-less stats answer is unknown; the PUT-based `serverWasReset` stays. Its doc no longer calls full detection backlog.
- Tests: `:app` SyncServerResetTest (Robolectric + Ktor MockEngine, 5): stats behind → everything pushed (h1, v1, p1), pulls since=0, cursors 0, rows clean, `serverReset`; healthy (max = highest cursor) and older server (no field) → pulls from 250/240/230, nothing pushed; older server + PUT answered at version 7 → reset (h2 pushed twice, 4 pushed); LWW-kept newer row → no reset. Mutation check: disabling both detections fails 2 of them. `:shared`: SyncRulesTest +2, SyncOutcomeTest +1, ApiClientContractTest +1 (field present / absent). `testImplementation(libs.ktor.client.mock)` in app/build.gradle.kts. Web specs +5 (stats reset with nothing to send; healthy; field-less and failed stats = unknown; no stats before the first pull; `serverBehind` table): 39 files, 497 tests pass; `ng build` passes with the two known NG8102 warnings.
- Backlog S4b-BL-45 (new): on Android the reset is said only in the stored sync outcome (Settings' server status line, the house list's warning reason when shown); a background sync gives no notification or snackbar, while the web announces it. Decide whether a one-time notice is wanted.

### D) S4b-BL-42, the flaky Export screenshot (91dfa7a)
**Root cause (thread and mechanism, with evidence).** `createComposeRule()` runs the composition's effects on an `UnconfinedTestDispatcher` wrapped in `ApplyingContinuationInterceptor` (compose ui-test 1.12.1: `ComposeUiTest_androidKt.createDefaultTestDispatcher` → `UnconfinedTestDispatcher` unless a TestDispatcher is given; `ApplyingContinuationInterceptor$SendApplyContinuation.resumeWith` calls `Snapshot.sendApplyNotifications()` after every resume). An unconfined continuation resumes on the thread that completed the suspension, so after a thread switch the effect keeps running there, writes its state there, and the interceptor applies the global snapshot there. A scratch probe (not committed; Snapshot.registerApplyObserver recording non-main threads while ExportScreen settles) showed three off-main applies per Export render on the current tree: `ResultMarks` (DataStore flow via `collectAsStateWithLifecycle`), `LocalRows` (Room flow) and `ExportCounts` (the counts `LaunchedEffect` after `withContext(Dispatchers.Default)`), on `DefaultDispatcher-worker-N` threads, stack `SendApplyContinuation.resumeWith → Snapshot.sendApplyNotifications`. The app is not affected: there the effect dispatcher is the main thread's (AndroidUiDispatcher), so the same code resumes and writes on main; the same probe on a `StandardTestDispatcher` shows 0 off-main applies and 84 on main, ExportCounts among them. So this is a test-harness race, not a production threading bug. These worker-thread continuations and applies are the only composition work the screenshot tests run off the main thread, so they are the source of the CalledFromWrongThreadException by elimination; the failing stack itself was not captured again (see below), so the exact View call that threw is not named.
**Predates step 2**: the same probe on a worktree at 13091ac (before CMP-6) showed 4 off-main applies (ResultMarks on `arch_disk_io_2`, a MutableState and LocalRows on DefaultDispatcher workers, and the counts), so the mechanism is older than step 2 (CMP-6 moved ExportScreen unchanged).
**Reproduction attempts before the fix (all green, so the rate is well below the 1-in-8 seen once in step 2)**: 10× export-only (80 shots), 10× the whole ScreensScreenshotTest (640 shots), 60 stressed Export renders with two CPU burners, 10× the whole `:app:testDebugUnitTest` (178 tests each). Repeating runs at 13091ac was skipped as it could not discriminate; the probe is the evidence.
**Fix (test only; no production change needed)**: ScreensScreenshotTest uses `createComposeRule(StandardTestDispatcher())`; `awaitStableFrame` runs the waiting continuations on the main thread (`compose.runOnIdle { effects.scheduler.runCurrent() }` in ten 15 ms steps per ~150 ms pass; runCurrent, not advanceUntilIdle, so no delay is skipped) and waits for three identical frames (was two) up to 30 passes. A first version (one runCurrent per 150 ms pass, three identical frames) failed once in 10 full runs (`export[ta-dark=false]` shot before the counts arrived), hence the short steps. Results with the final version: 64/64 verify (no image re-recorded), then 12 consecutive full `:app:testDebugUnitTest -Proborazzi.test.verify=true --rerun` runs all green (178 tests and 64 screenshots each, 768 shots), then the full CI command and the iOS set green before the commit.
**Backlog S4b-BL-46 (new)**: the other Compose tests still use the unconfined default: RootNavigationTest (works around it by avoiding the visit-id path) and the instrumented SmokeTest (`createEmptyComposeRule`; the production `currentLocation` hops to `Dispatchers.Main.immediate` because of it). Move them to a StandardTestDispatcher, or record why not.

### Commands and results (each commit)
- `cd android && ./gradlew -q --no-daemon assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata -Proborazzi.test.verify=true assembleDebugAndroidTest`: exit 0 before A, B, C and D (A was run with the daemon, same tasks). iOS: `-Pkotlin.native.enableKlibsCrossCompilation=true :shared:compileKotlinIosSimulatorArm64 :ui:compileKotlinIosSimulatorArm64 :ui:compileTestKotlinIosSimulatorArm64`: exit 0 before each commit.
- JVM test counts: `:app` 173 → 178 (+5 SyncServerResetTest; RootNavigationTest 5 unchanged in number), `:ui` 97 → 104 (+7 IndiaViewOpsTest), `:shared` 196 → 200. 64 screenshots verify (no re-record) after every commit.
- commonMain of `:ui` and `:shared`: no `android.*` or `java.*` imports (grep). No key-like literals in tests (SyncServerResetTest makes its key at run time).
- Not run here: the emulator smoke tests (no emulator; the new Map test only compiled), the live UI tool, CI.

## Map labels missing on the emulator (lead, 2026-09-24)
- CI emulator screenshots (API 26/34, run on 91dfa7a) show the map with no text labels. Code reading (c7d0f8b agent): nothing in our code hides base labels; filter composition, map options, style URL unchanged vs f8dd6f9; state-label filter only touches label_state/label_other.
- Diagnostic in SmokeTest.theMapShowsIndiasBoundary (c7d0f8b; CI run 35988442240, API 34): at zoom 4 over India, style fully loaded, glyphs URL correct, 31 symbol layers, queryRenderedFeatures = 49 placed symbols (label_city 40, label_city_capital 3, label_country_1 2, label_country_2 2), 1070 place features in tiles. So labels are laid out but not drawn: MapLibre Native's text rendering on the emulator's SwiftShader GPU (maplibre-native #3939, #3617, open, no fix). Not an app bug. Real-device confirmation: TC-M-25 after CMP-8 (owner). Record as an emulator limitation / backlog item; emulator screenshots can't show labels.

## S4b-BL-12 fix

Done 2026-09-24, commit `18b631e` on `claude/doorprints-dev-continue-fzcge2` (not pushed, no PR). Scratch in `geo/`:
`bl12_collect.py` (every boundary_3-drawn feature at tile z9-14, tile-local geometry incl. buffer -> `bl12_feats.pkl`,
z9 95 / z10 312 / z11 1115 / z12 4183 tiles over held.buffer(0.35), z13 2841 / z14 7816 along the z12 lines),
`bl12_facto12.py` (tiles' LoC/LAC at z13), `bl12_eval.py` (renderer `within` port + classification), `bl12_sweep.py`,
`bl12_shallow.py`, `bl12_port_check.mjs`, `bl12_render.py`.

### `within` semantics for LineString features (read from source; identical in both renderers)
- maplibre-gl 6.10.0 -> style spec 26.4.4 `src/expression/definitions/within.ts`: `evaluate` (l.234-243) -> false
  without geometry/canonical; `linesWithinPolygons` (l.157-182): polygon and feature in world tile coords at the
  canonical zoom (Mercator, EXTENT 8192; `getTileLines` l.100-127), `boxWithinBox(lineBBox, polyBBox)` STRICT, then
  EVERY part must satisfy `lineStringWithinPolygon(s)` (`src/util/geometry_util.ts` l.134-159: all vertices
  `pointWithinPolygon` with a vertex on an edge = outside (l.112-125), and no segment properly crossing any ring edge
  `lineIntersectPolygon` l.99-109). MultiPolygon: each part within one polygon. FeatureCollection: all polygons merged.
- maplibre-native android-v13.6.1 (c7506d6; files now under `src/mln/`): `src/mln/style/expression/within.cpp`
  `featureWithinPolygons` l.147-177 (same bbox + `lineStringWithinPolygons` for all parts of a LineString feature),
  `Within::evaluate` l.221-235 (false without feature/canonical); `src/mln/util/geometry_util.cpp` l.116-141 (same
  algorithm, int64). Filter evaluated with the canonical tile id: `geometry_tile_worker.cpp:502-503`. Geometry scaled
  to EXTENT 8192 (`vector_mvt_tile_data.cpp:49`). Differences: native `Within::parse` (l.266-272) uses only the FIRST
  polygon feature of a FeatureCollection -> we pass a bare Polygon geometry. MapLibre Android `Expression.raw`
  (Expression.java `Converter.convert` l.4846-4847) converts `within`'s argument with `Polygon.fromJson` -> Polygon only
  (native side reads the `json` member, `android_conversion.hpp` l.105-127). So: whole tile feature (all parts, incl.
  tile buffer) must be strictly inside; a crossing feature is drawn whole.
- Cross-check: my Python port vs the real `featureFilter` of the web's style spec on all 8505 collected features
  (z9-14) with the final P: 8505/8505 agree (`bl12_port_check.mjs`).

### P design (web/scripts/geo/build_in_held_areas.py; `--check` prints the z9-11 counts)
1. Held areas from the tiles' own LoC/LAC: NE IND-view India polygon cut by the disputed admin-2 lines of the z13 tiles
   (taken along the z10 ones), widened 0.001 deg; a face is held when NE default-view PAK/CHN covers >50% of it
   (PoK+GB 7.74, Aksai Chin 3.057, Shaksgam 0.477 deg2 + 4 tiny faces). Using NE's LoC instead (candidate) hid Indian
   pieces up to ~3 km from the LoC; NE-vs-NE also produced slivers along Punjab/Himachal that grew P there.
2. Outer: held.buffer(0.2 deg ~20 km) U convex hulls (+0.03) of every z9-11 boundary_3 feature reaching >1 km into a
   held area and not >1 km into the Indian part; simplify 0.03.
3. Minus Indian part.buffer(-0.007 ~700 m).simplify(0.0035). Largest polygon, 5 dp. Result: 1 Polygon, 0 holes,
   629 vertices, bounds 72.316-80.522 E, 32.57-37.253 N, 12 575 bytes.
   Tried: g 0.1 left 40-165 held pieces at z12-14 (lines crossing >10 km out); s 1-2 km hid Indian line ends;
   s 0.5 km needed ~1000 vertices.

### Counts (final P, renderer port; "held" = reaches >1 km into held part and not into the Indian part; "mixed" = one
tile feature with parts on both sides; "shallow" = hidden feature with >0.2 km on the Indian side)
| tile z | held hidden / drawn | Indian hidden / drawn | mixed drawn (km in held) | shallow hidden | foreign outside hidden |
| 9  | 39 / 0   | 0 / 48   | 8 (300 km) | 0 | 4 (43 km) |
| 10 | 87 / 0   | 0 / 100  | 5 (155 km) | 1 (1.0 km) | 22 (520 km) |
| 11 | 194 / 0  | 0 / 207  | 4 (63 km)  | 1 (0.5 km) | 56 (734 km) |
| 12 | 398 / 0  | 0 / 423  | 0          | 4 (2.0 km) | 119 (808 km) |
| 13 | 810 / 0  | 0 / 840  | 0          | 5 (4.3 km) | 242 (877 km) |
| 14 | 1678 / 0 | 0 / 1720 | 0          | 6 (4.9 km) | 500 (919 km) |
- Remaining: the 8/5/4 "mixed" features at z9-11 cannot be hidden without hiding an Indian line (tiles merge one
  admin level per tile into a multi-line): PoK next to the LoC (~73.8-74.3 E, 33.0-34.3 N) and held side north of
  Gurez/Dras/Kargil (~75.1-76.5 E, 34.6-34.9 N). None from z12.
- Shallow: pieces <=2.05 km long, <=0.74 km from the tiles' LoC, all at 73.95-74.73 E (Indian line ends at the LoC
  lose their last few hundred m at z10+).
- Side effect: foreign (PAK/CHN/AFG) admin 3-6 lines within ~20 km outside India's outline (more near the hulls,
  e.g. west of AJK) not drawn at any zoom; none near India's outline away from the held areas.
- Towns (IndiaBoundaryDataTest): inside Gilgit, Hunza, Skardu, Muzaffarabad, Mirpur, Shaksgam, Aksai Chin; outside
  Srinagar, Jammu, Leh, Kargil (7.8 km from P), Dras, Uri (4 km), Poonch (5.5 km), Turtuk, Lukung; Islamabad outside (6 km).

### Files (commit 18b631e)
- Data: `web/public/geo/in-held-areas.geojson` = `android/app/src/main/assets/geo/in-held-areas.geojson`, sha256
  `8fa2db123b4c4370a88ca9b3da31b212b847205c2ba529ca43b4afbb27b780c3` (FeatureCollection, 1 Feature
  `{"kind":"held"}`, Polygon). One shared file chosen (not embedded coordinates): same pattern as in-boundaries,
  `android.yml` already triggers on `web/public/geo/**`, pinned in both suites. Web bundles it via
  `import ... with { loader: 'text' }` (+ `web/src/geojson-text.d.ts`) because the rule is needed synchronously at
  `style.load`; it is also served/precached (12.6 KB, unused at runtime).
- Build: `web/scripts/geo/build_in_held_areas.py <NE ca96624> <out> [tilecache] [--check]` (planet 20260913_164504_pt;
  reproduces the sha256 from `geo/tilecache`).
- Web `india-boundaries.ts`: `HeldAreasGeometry`, `heldAreasGeometry(text)` (one Polygon feature, closed rings, else
  null), `HELD_AREAS`, `heldAreasRule(g)` = `['!', ['within', g]]`; step 2c: boundary_3 filter becomes
  `['all', ['all', liberty, guard], rule]`; warnings: polygon missing/malformed; legacy boundary_3 filter; silent when
  boundary_3 absent. `indiaBoundaryStyle(style, url, heldAreas = HELD_AREAS)`.
- Android: `IndiaViewRules.HELD_AREAS_ASSET_PATH`, `heldAreasGeometry(text): String?`, `heldAreasFilter`,
  `heldAreasFilterFor(existing, g)`; `IndiaViewOps.kt`: `StyleOps.readAsset(path)`, `applyIndiaView` step 2c after
  the guard (andFilter on boundary_3; same warnings); `MapLibreStyleOps(style, assets: AssetManager)`,
  `PlatformMap.android.kt` passes `context.assets`.
- docs/06 TC-M-25 "Admin lines": zoom 6-8 and 9-14 over GB, PoK, Aksai Chin (+Shaksgam), Indian lines near LoC/LAC
  must show, expected minors listed.

### Tests
- Web `ng test`: 40 files / 510 tests pass (was 501; +9 held-area cases using MapLibre's own `featureFilter` from
  `@maplibre/maplibre-gl-style-spec` (transitive dep of maplibre-gl, not in package.json): sha256 pin, filter shape,
  held lines hidden z9/z14 (Gilgit, Skardu, Muzaffarabad, Shaksgam, Aksai Chin), Indian kept (Srinagar, Leh, Kargil,
  Poonch, Jammu), crossing / mixed multi-line kept, Liberty conditions, malformed polygon, no filter, parser cases);
  4 existing expectations updated. `ng build` OK.
- Android full command incl. roborazzi verify and assembleDebugAndroidTest: EXIT 0. IndiaViewRulesTest 21 (+3, with a
  Kotlin port of native within), IndiaViewOpsTest 10 (+3), IndiaBoundaryDataTest 4 (+2). iOS
  `:ui:compileKotlinIosSimulatorArm64 :ui:compileTestKotlinIosSimulatorArm64` OK.

### Renders
`geo/bl12_after_{gilgit_baltistan,pok,aksai_chin}_z{9,12}.png`: before | after, purple dashed = boundary_3 features
drawn, blue dashed = P, red = India outline, grey dotted = tiles' LoC/LAC.

### Left for the lead
docs/03 ADR-22, docs/10 §12.7 BL-12 row, docs/02 RR-16, TC-U-54/55 counts in docs/06 §15, CHANGELOG, web/README,
android/shared/README (new file + sha256), ADR note on the side effect; not checked on a device (TC-M-25). Re-run the
build script after each planet update (BL-9).
