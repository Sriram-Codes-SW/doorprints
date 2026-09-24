# `:ui` — Compose Multiplatform UI

| | |
|---|---|
| Version | 1.10 |
| Date | 2026-09-24 |
| Sprint | Compose Multiplatform track ([docs/10](../../docs/10-sprint-log.md) §13, CMP-1..CMP-9) |
| Owner | Android team |

**Change log**

| Version | Date | Change |
|---|---|---|
| 1.10 | 2026-09-24 | **CMP-5, CMP-6 and CMP-7 done in code as one change** (branch `claude/doorprints-dev-continue-fzcge2`, PR #24; [docs/03](../../docs/03-design.md) ADR-23 P5-P7, [docs/10](../../docs/10-sprint-log.md) §13.9). Section 2: navigation-compose 2.9.2 and lifecycle 2.11.0 (`api`), kotlinx-serialization-json, Coil 3, MapLibre (OpenGL ES build) in androidMain. Section 4: every screen, `Root.kt`, the seams `AppServices`, `PlatformServices` (grown), `PlatformMap`, `StyleOps` (`IndiaViewOps.kt`, with the held-areas rule of S4b-BL-12), the backup seams and texts, the androidMain and iosMain actuals, the new commonTests; `RootScreens` gone. Section 5: the emulator on API 26, 34 and 36. Section 6: P5, P6 and P7 done in code. |
| 1.9 | 2026-09-24 | **CMP-4 P4c done in code** (branch `claude/doorprints-dev-continue-fzcge2`, PR #23; [docs/03](../../docs/03-design.md) ADR-23 P4c, [docs/10](../../docs/10-sprint-log.md) §13.8). Section 4: `CompareScreen` (taking the houses and visit counts), `HouseFormRules.kt` (public), `ModelLabels.kt` (`labelResource`, `glyph`, `ChecklistResources`), `formatPositional` in `Format.kt`, and `offerDeletedHouseUndo(repo, …)` over the common `Repository`; the 10 checklist labels added to the Compose resources (413 strings); `HouseFormRulesTest` and `ModelLabelsTest` in commonTest, a Compare case in `FormatsParityTest`. Section 6: P4a and P4b merged, P4c done in code; P5, P6 and P7 ship as one combined change (owner request of 2026-09-24). No visual change (the 64 screenshots verify). |
| 1.8 | 2026-09-24 | **CMP-4 P4b done in code** (branch `claude/doorprints-dev-continue-fzcge2`, PR #22; [docs/03](../../docs/03-design.md) ADR-23 P4b, [docs/10](../../docs/10-sprint-log.md) §13.7): settings, `SecretStore` and `ServerUrl` are in `:shared` commonMain; section 6's P4 row says so, and P4a is merged (PR #21). No change to `:ui` itself. |
| 1.7 | 2026-09-24 | **CMP-4 P4a done in code** (branch `claude/doorprints-dev-continue-fzcge2`; [docs/03](../../docs/03-design.md) ADR-23 P4a, [docs/10](../../docs/10-sprint-log.md) §13.6): the Room database is in `:shared` commonMain (Room KMP); section 6's P4 row says so. No change to `:ui` itself. P2 and P3 marked **Done** (PRs #19 and #20 merged). |
| 1.6 | 2026-09-24 | **CMP-3 done in code** (branch `claude/doorprints-dev-continue-fzcge2`; [docs/03](../../docs/03-design.md) ADR-23 P3, [docs/10](../../docs/10-sprint-log.md) §13.5). Section 4: `PlatformServices` and `LocalPlatformServices`, `Format.kt` (common amounts and scores, `expect` dates, `appLanguage()`), `LiveMessage`, `DeletedHouseUndo`, `ActionBar`, `ResultCard`; `uiLanguage()` follows the resolved language (S4b-BL-18); `MapRulesTest`, `IndiaViewRulesTest` and `FormatsTest` in commonTest, `FormatsParityTest` in androidHostTest; `ui/UiStrings.kt` removed. Section 5: the iOS klibs compile on Linux with klib cross-compilation on. Section 6: P3 done in code. No visual change (the 64 screenshots verify). |
| 1.5 | 2026-09-24 | **CMP-2 done in code** (PR #19, `80b198b`, review fixes `927d54b`; [docs/03](../../docs/03-design.md) ADR-23 P2, [docs/10](../../docs/10-sprint-log.md) §13.4). Section 2: the `org.jetbrains.compose` plugin and `components-resources`; section 4: the Compose resources row (403 strings and 16 plurals per language) and the service strings that stay in `:app`; section 6: P2 done. No visual change (the 64 screenshots verify). |
| 1.4 | 2026-09-24 | **Legacy House Hunt names renamed** (owner request of 2026-09-24; [docs/03](../../docs/03-design.md) ADR-24). Section 3: the Kotlin package and the Android namespace are both `app.doorprints.ui` (were `com.househunt.app.ui` and `com.househunt.ui`), the resources class `app.doorprints.ui.res.Res`; section 4: `DoorprintsTheme`. No visual change (the 64 screenshots verify). |
| 1.3 | 2026-09-24 | Round 3 review of PR #18: the screenshot test id is **TC-U-56** (was TC-U-60); the android.yml command in section 5 is split over three lines. |
| 1.2 | 2026-09-24 | Section 5: `android.yml` runs the unit tests with `-Proborazzi.test.verify=true`, and each phase is checked by the JVM screenshot tests of `:app` (docs/06 TC-U-56) and the emulator smoke tests (TC-I-35, `android-emulator.yml`); CMP-0, commit `afe4064` ([docs/10](../../docs/10-sprint-log.md) §13.3). |
| 1.1 | 2026-09-24 | Code review of CMP-1 (commit `06e482d`): `WORLD_MAX_ZOOM` is computed from the float's bits (`Float.nextDown()` is JVM-only); `android.yml` compiles `:shared` and `:ui` commonMain metadata on Linux; Room version follows the catalog. |
| 1.0 | 2026-09-24 | First version (CMP-1, commit `be86f50`). `:ui` module created with the JetBrains Compose Multiplatform libraries; the theme, the list rows, the server status, the Map and India view rules, and a few small UI types move out of `:app` into commonMain. No visual change. |

## 1. What this module is

Owner request (2026-09-24): "The Compose needs to be changed to Kotlin Compose to allow easy iOS app creation".
`:ui` is where Doorprints' Android UI moves, phase by phase, from Jetpack Compose in `:app` to JetBrains **Compose
Multiplatform** (CMP), so that a later iOS app can reuse the same screens. The decision and the phase plan are
**ADR-23** in [docs/03](../../docs/03-design.md) §14 (it amends ADR-14). `:app` stays the Android application around
these screens; `:shared` ([README](../shared/README.md)) stays the home of the domain rules and the API client.

## 2. Targets and dependencies

| Target | Built where | Purpose |
|---|---|---|
| Android (`com.android.kotlin.multiplatform.library`, `kotlin { android { … withHostTest {} } }`) | ubuntu CI (`android.yml`) | consumed by `:app` |
| `iosArm64`, `iosSimulatorArm64` | macOS CI (`shared-ios.yml`) | **compile-only**, like `:shared`: proves commonMain builds for iOS; no framework binary yet (phase 8) |

Plugins: `kotlin.multiplatform`, `android.kotlin.multiplatform.library`, `kotlin.compose` and, since phase 2,
`org.jetbrains.compose` for the string resources (`compose.resources`: a public `Res` class in `app.doorprints.ui.res`;
`androidResources { enable = true }`, because Compose resources are packaged as Android assets).

commonMain dependencies (all `api`, because the theme and composables take and return Compose types):

| Library | Version | Android mapping |
|---|---|---|
| `org.jetbrains.compose.runtime:runtime`, `foundation:foundation`, `ui:ui` | 1.12.1 | androidx.compose 1.12.1 |
| `org.jetbrains.compose.material3:material3` | 1.9.0 | androidx material3 1.4.0 |
| `org.jetbrains.compose.material:material-icons-core` | 1.7.3 | androidx 1.7.3 (Gradle resolves to the 1.7.8 `:app` already uses) |
| `org.jetbrains.compose.components:components-resources` | 1.12.1 | – (Compose Multiplatform only); `api`; since CMP-7 only `:app`'s tests read `Res` (`AppLocaleTest`), so `implementation` would do (not changed) |
| `org.jetbrains.androidx.navigation:navigation-compose` (CMP-5) | 2.9.2 | androidx navigation-compose 2.9.7 |
| `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose`, `-viewmodel-compose`, `-viewmodel-savedstate` (CMP-5) | 2.11.0 | androidx lifecycle 2.11.0 |
| `io.coil-kt.coil3:coil-compose` (CMP-6, `implementation`) | 3.6.3 | the same library (photos in the house form) |
| `kotlinx-serialization-json` (CMP-5, `implementation`) | the catalog's | – (routes, the houses' GeoJSON) |

androidMain adds `androidx.core:core-ktx`, `activity-compose` and MapLibre Android 13.6.1, the **OpenGL ES build**
(`org.maplibre.gl:android-sdk-opengl`, the catalog's `maplibre-android`; the default build renders with Vulkan and
crashed on the API 26 emulator, [docs/03](../../docs/03-design.md) ADR-22).
| `project(":shared")` | – | domain types the UI is built on (`HouseStatus`, `SyncOutcome`) |

Versions live in `android/gradle/libs.versions.toml` (`cmp`, `cmp-material3`, `cmp-material-icons`).

## 3. Package name

The Android namespace (R class) and the Kotlin package are both **`app.doorprints.ui`** (the same package as
`:app`'s screens, so `:app`'s imports do not change and every move is a plain file move); the Compose resources'
generated class is `app.doorprints.ui.res.Res`. Declarations `:app` uses are `public` instead of `internal`. Until
2026-09-24 the packages kept the old product name (`com.househunt.app.ui`, namespace `com.househunt.ui`).

## 4. What is in it now (phases 1 to 7)

| Source set | File | Contents |
|---|---|---|
| commonMain | `Theme.kt` | `DoorprintsTheme`, colours, type; the language lookup is `expect fun uiLanguage()` |
| commonMain | `Rows.kt`, `ServerStatus.kt` | list rows; the server status line and its rules |
| commonMain | `MapRules.kt`, `IndiaViewRules.kt` | the Map's layout rules; India's boundary rules as data (ADR-22), `WORLD_MAX_ZOOM` computed from the float's bits (common code; the same value as `Math.nextDown`). By the renderers' sources both maplibre-native (`src/mln/tile/geometry_tile.cpp:317` at `android-v13.6.1`, `GeometryTile::setLayers` skips a layer when `id.overscaledZ < floor(minZoom)`) and maplibre-gl (`src/source/worker_tile.ts:109`, 6.10.0) leave a minzoom 5 layer out of a zoom 0-4 tile, so the adm0 rule and the tile-zoom guard are defence in depth and parity on Android and the web alike, not the fix on either (S4b-BL-13; not yet checked on a device, TC-M-25 (7) to (9)) |
| commonMain | `Buttons.kt` | `ANIMATION_MS`, `ButtonLabel`, `BUTTON_LABEL_MAX_LINES` (from `ActionBar.kt`) |
| commonMain | `ResultTone.kt`, `LocationFix.kt` | from `ResultCard.kt` and `LocationPermission.kt` |
| commonMain | `PlatformServices.kt` | the platform seam (phase 3; `LocalPlatformServices`, no default: a root must provide it): `isScreenReaderOn()`; since CMP-5 `locationAccess()`, `locationAsked()`, `markLocationAsked()`, `canAskLocation()`, `openAppSettings()`, `canPostNotifications()`; since CMP-6 `dial(number)`, `openUrl(url)` |
| commonMain | `AppServices.kt` | what the screens ask of the app (CMP-5..7; `LocalAppServices`): `repository`, `appScope`, `location` (`LocationSource`), `copyImports`, `settingsScreen` (`SettingsServices`), `houseForm` (`HouseFormServices`), `exportScreen` and `importScreen` (`BackupServices.kt`), `mapScreen` (`MapServices`: Hunt mode, the notification prompt, motion and font settings), `consumeLanguageChange()`. `:app`'s `AndroidAppServices` and `AndroidBackupServices` implement them |
| commonMain | `Root.kt` | `DoorprintsRoot(deepLinks, onDeepLinkHandled)`: the theme, the bottom bar, the `NavHost` (JetBrains navigation-compose), the deep-link effect, `Routes` and `DeepLink` (CMP-5); every screen is common since CMP-7, so there is no `RootScreens` slot |
| commonMain | `HouseListScreen.kt`, `AssistantScreen.kt`, `SettingsScreen.kt`, `NotifyAsk.kt`, `LocationPermission.kt`, `PermissionRequests.kt` | CMP-5: the screens; `AssistantViewModel(repository, LocationSource, SavedStateHandle)`; the location rules and `LocationAsk` over `PlatformServices`; `expect` permission requests |
| commonMain | `HouseEditScreen.kt`, `HouseFormServices.kt`, `BackHandler.kt`, `ListText.kt` | CMP-6 P6a: the house form and `PhotoDeleteViewModel`; the camera, picker, geocoder and `addPhoto` seam; `expect PlatformBackHandler`; `joinList`, `joinedList`; `isWebLink` (in `HouseFormRules.kt`) and `Formats.coordinate` |
| commonMain | `ExportScreen.kt`, `ImportScreen.kt`, `ImportViewModel.kt`, `BackupServices.kt`, `BackupTexts.kt` | CMP-6 P6b: the screens and the import view model; `ExportServices`, `ImportServices`, `RunState`, `ExportRun`, `ImportRun`; the result texts and failure reasons from Compose resources (S4b-BL-35) |
| commonMain | `MapScreen.kt`, `PlatformMap.kt` | CMP-7: the Map's chrome; `expect fun PlatformMap(houses, labelSizeSp, showLocation, attribution, events, modifier)`, `MapControl`, `MapEvents`, `MapAttribution`, `CameraSpot` |
| commonMain | `IndiaViewOps.kt` | CMP-7: `interface StyleOps` (layers, filters, minzoom, paint, sources, `readAsset`, warnings) and `fun applyIndiaView(ops: StyleOps)`, the steps of the old `:app` `IndiaView.kt` in the same order with the same fallbacks and warnings, plus step 2c, the held-areas rule on `boundary_3` (S4b-BL-12, `geo/in-held-areas.geojson`) |
| commonMain | `Format.kt` | `Formats`: `rupees` (₹ and lakh grouping, `indianGrouping`), `price`, `score` (one decimal, half up) in common code; `date` and `dateTime` through `internal expect fun formatDate`; `expect fun appLanguage()`; the composables `priceText(price, priceType)` (`price_per_month` from Compose resources), `scoreText()`, `dateText()`; `formatPositional` (`%1$s` and `%1$d` filled as Compose resources fill them, in place of `String.format`; CMP-4 P4c) |
| commonMain | `LiveMessage.kt`, `DeletedHouseUndo.kt` | the live region that is there before its message; the "Deleted …" snackbar with *Undo*, over the common `Repository` (since CMP-4 P4c; `:app`'s `DeletedHouses.kt` adapter is gone) or the two lambdas it is built on |
| commonMain | `CompareScreen.kt` | the Compare tab (CMP-4 P4c), given the live houses (null until the database answers) and the visit counts; since CMP-5 the root collects them with the common lifecycle library (`:app`'s `CompareTab.kt` is gone) |
| commonMain | `HouseFormRules.kt`, `ModelLabels.kt` | the house form's and lists' pure rules (`fillPlace`, `mergeListing`, `parseCoordinate`, `sortByPrice`, …; public, since `:app`'s form uses them); the statuses' and checklist keys' Compose resource labels (`labelResource`, `ChecklistResources`) and the status `glyph` (CMP-4 P4c) |
| commonMain | `ActionBar.kt`, `ResultCard.kt` | Export's and Import's sticky bar, `StatusLine`, `WorkProgress`, `ProgressBar`, `BarButton`, `StateButton`, `DangerButton`; the result cards and `ResultActionsRow` |
| androidMain | `PlatformServices.android.kt` | `AndroidPlatformServices` (TalkBack's touch exploration, the location grants, the app's settings, dial and URLs, from the composition's context) and `ProvidePlatformServices { }`, used by `MainActivity` and the screenshot tests |
| androidMain | `LocationPermission.android.kt`, `PermissionRequests.android.kt`, `BackHandler.android.kt`, `Clock.android.kt` | the permission prefs file `map_permissions` (unchanged), the system prompts, androidx `BackHandler`, `SystemClock.elapsedRealtime` |
| androidMain | `PlatformMap.android.kt`, `MapLibreStyleOps.kt` | the MapLibre `MapView` in `AndroidView` (an empty box in inspection mode: MapLibre's native library does not load on the JVM), the house layers, `MAP_STYLE_URL`; `StyleOps` over MapLibre's `Style` and the asset manager (log tag `IndiaView`) |
| iosMain | `PlatformMap.ios.kt`, `PermissionRequests.ios.kt`, `BackHandler.ios.kt`, `Clock.ios.kt` | compile-only stand-ins until CMP-8: an empty map box, no-op permission requests and back handler, `systemUptime` ([docs/10](../../docs/10-sprint-log.md) S4b-BL-36, -40, -43) |
| androidMain | `Format.android.kt` | `java.time`'s medium date and short time for `<language>-IN`; `appLanguage()` is `Locale.getDefault()`, which `AppLocale.applyDefault` keeps on the resolved language |
| androidMain | `UiLanguage.android.kt` | `appLanguage()`, read again when `LocalConfiguration` changes (since CMP-3, S4b-BL-18; was the configuration's first locale) |
| iosMain | `UiLanguage.ios.kt`, `Format.ios.kt` | Compose's `Locale.current`; `NSDateFormatter` (medium date, short time) for `<language>_IN` |
| commonMain | `composeResources/values{,-hi,-ta,-te}/strings.xml` | the UI strings (phase 2): 444 strings and 17 plurals per language since PR #24 (413 and 16 before: the backup results and reasons, the list patterns and `sync_server_reset` added), positional placeholders only, a plain `'` (no Android escapes); checked by `:app`'s `StringParityTest` (docs/06 TC-U-59) |
| commonTest | `ServerStatusTest.kt`, `MapRulesTest.kt`, `IndiaViewRulesTest.kt`, `IndiaViewOpsTest.kt`, `FormatsTest.kt`, `HouseFormRulesTest.kt`, `ModelLabelsTest.kt`, `LocationAccessTest.kt`, `LocationAskTest.kt`, `AssistantViewModelTest.kt`, `ImportStartOnceTest.kt`, `JoinListTest.kt`, `BackupTextsTest.kt` | `kotlin.test`, 105 tests; the Map and India view tests moved from `:app` (JUnit) in phase 3, `HouseFormRulesTest` in P4c, `LocationAccessTest`, `ImportStartOnceTest` and `JoinListTest` in CMP-5..7; all compile for iOS too (docs/06 TC-U-50, -51, -69, -70, -71, -73) |
| androidHostTest | `FormatsParityTest.kt` | the common amounts and scores against the JVM's `NumberFormat` and `String.format` in the four languages; since P4c Compare's four formats through `formatPositional` |

Since CMP-7 every screen and the map view's seam are here; `:app` keeps the Android implementations of the seams
(`AndroidAppServices`, `AndroidBackupServices`, `ProvideAppServices`, `CurrentLocation.kt`), `MainActivity`'s intent
parsing and the service strings (notifications, workers, `HuntService`) in
`android/app/src/main/res/values*/strings.xml`, with `'` escaped as `\'`; keys both use are in both places with the same
text. Outside composition `:app` reads a UI string with Compose's suspend `getString(Res.string.x)` in a coroutine or
effect (the blocking `ui/UiStrings.kt` helper went in CMP-3), or resolves it with `stringResource` in composition and
hands it to the click handler. Compose resources take the language from the process's default locale, which `:app`'s
`AppLocale.applyDefault` keeps on the language Android resolved (docs/05 §8.2, TC-U-60); the dates and the theme's
language rules read the same (`appLanguage()`, TC-U-61).

## 5. Build and test

From `android/`:

```bash
./gradlew :ui:testAndroidHostTest     # :ui's JVM tests; :app:testDebugUnitTest depends on it, android.yml also names it
./gradlew assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest \
  :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata \
  -Proborazzi.test.verify=true   # what android.yml runs
./gradlew :ui:compileCommonMainKotlinMetadata   # commonMain against the common libraries, on Linux: catches JVM-only calls
# macOS only (shared-ios.yml): compile-only, nothing is linked, signed or run on a simulator
./gradlew :ui:compileKotlinIosArm64 :ui:compileKotlinIosSimulatorArm64 :ui:compileTestKotlinIosSimulatorArm64
```

On Linux the iOS tasks are skipped (`kotlin.native.enableKlibsCrossCompilation=false`, see the `:shared` README §5).
To compile the iOS klibs on Linux anyway, add `-Pkotlin.native.enableKlibsCrossCompilation=true` to the macOS line
above (done for CMP-3; nothing is linked). `shared-ios.yml` on macOS stays the check of record.
`shared-ios.yml` watches `android/ui/**`; its job name is unchanged in case it is a required check. The rules for
commonMain in the `:shared` README §6 apply here too.

**How a phase shows that no screen changed.** The screens are still rendered from `:app`, so the checks live there: the
JVM screenshot tests (`app/src/test/.../screenshots/ScreensScreenshotTest`, docs/06 TC-U-56; every screen but the Map,
en/hi/ta/te, light and dark, 64 reference images in `app/src/test/screenshots`) fail `android.yml` on a changed screen,
and the smoke tests (`app/src/androidTest/.../SmokeTest`, TC-I-35, the Map's since CMP-7) run the APK on emulators at
API 26, 34 and 36 (`android-emulator.yml`). The emulator lays out but does not draw the map's labels (S4b-BL-48). A
phase that changes a screen on purpose re-records with `./gradlew :app:recordRoborazziDebug` and names the changed
images in its pull request. When a screen moves into `:ui`, update the test's import, not the image.

## 6. Phases (ADR-23)

Each phase (and each lettered sub-phase) is one pull request that keeps `android.yml` green and runs the CLAUDE.md
review steps. Exception (owner request of 2026-09-24): CMP-5, CMP-6 and CMP-7 are one combined change, one branch
and one pull request, tested together (the 64 screenshots, TC-I-35, the iOS compile, the TC-M-25 re-run), committed
in steps that each build and pass ([docs/10](../../docs/10-sprint-log.md) §13). CMP-8 stays separate.

| Phase | Ticket | Scope | Status |
|---|---|---|---|
| P1 | CMP-1 | `:ui` module; theme and pure UI code | **Done** (`be86f50`); iOS compile pending on CI (macOS) |
| P2 | CMP-2 | UI strings to Compose resources (`values{,-hi,-ta,-te}`), `org.jetbrains.compose` plugin, `Res.string`; service strings stay Android resources; `AppLocale.applyDefault` on every API level; `localeFilters`; `StringParityTest`, `AppLocaleTest` | **Done** (PR #19, merged as `7080a7f`) |
| P3 | CMP-3 | `PlatformServices` seam (screen reader only, so far); `Format.kt` (common amounts, `expect` dates), `LiveMessage`, `DeletedHouseUndo`, `ActionBar`, `ResultCard`; `UiStrings.kt` removed; S4b-BL-18; `MapRulesTest`, `IndiaViewRulesTest` to commonTest | **Done** (PR #20, merged as `fccf8a1`) |
| P4a, P4b, P4c | CMP-4 | Room KMP (the catalog's version, 2.8.5 since Dependabot #12) in `:shared` (db v2 identity hash kept, migration test); DataStore KMP, `SecretStore`, `ServerUrl` in common; `Repository` interface in common, `CompareScreen` and `HouseFormRules` move | **P4a done** (the Room database in `:shared`, PR #21, merged as `e480b83`; [docs/10](../../docs/10-sprint-log.md) §13.6); **P4b done** (settings, `SecretStore`, `ServerUrl` in `:shared`, PR #22, merged as `aa8f73a`; §13.7); **P4c done** (a common `Repository` interface, `CompareScreen` and `HouseFormRules` in `:ui`, PR #23, merged as `f8dd6f9`; §13.8) |
| P5 | CMP-5 | JetBrains navigation-compose and lifecycle; ViewModels; HouseList, Assistant, Settings, NotifyAsk, LocationPermission | **Done in code** (PR #24, `84fa735`, `13091ac`; one combined change with P6 and P7, [docs/10](../../docs/10-sprint-log.md) §13.9) |
| P6a, P6b | CMP-6 | HouseEditScreen (photo and camera seam); Export and Import screens, `ImportViewModel`, workers behind an interface | **Done in code** (PR #24, `f7e9ecd`, `7e73411`, `cff4a63`) |
| P7 | CMP-7 | Map: common chrome, `expect PlatformMap`, common `applyIndiaView(ops: StyleOps)`; TC-M-25 re-run | **Done in code** (PR #24, `e2b163c`; the iOS map view is CMP-8; the TC-M-25 device run by the owner after CMP-8) |
| P8 | CMP-8 | iOS shell (`iosApp`, `ComposeUIViewController`, MapLibre iOS via SPM), simulator build on macOS CI, unsigned | Planned |
| Spike | CMP-9 | maplibre-compose (0.17, pre-1.0); re-assess at 1.0 | Planned |

Out of scope until there is a Mac and a paid Apple Developer account (about US$99 a year, against the zero-cost
rule): signing, device installs, TestFlight and the App Store. iPhone users keep the PWA until then.
