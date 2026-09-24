# `:ui` — Compose Multiplatform UI

| | |
|---|---|
| Version | 1.8 |
| Date | 2026-09-24 |
| Sprint | Compose Multiplatform track ([docs/10](../../docs/10-sprint-log.md) §13, CMP-1..CMP-9) |
| Owner | Android team |

**Change log**

| Version | Date | Change |
|---|---|---|
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
| `org.jetbrains.compose.components:components-resources` | 1.12.1 | – (Compose Multiplatform only); `api` because `:app`'s screens call `stringResource(Res.string.…)` until they move |
| `project(":shared")` | – | domain types the UI is built on (`HouseStatus`, `SyncOutcome`) |

Versions live in `android/gradle/libs.versions.toml` (`cmp`, `cmp-material3`, `cmp-material-icons`).

## 3. Package name

The Android namespace (R class) and the Kotlin package are both **`app.doorprints.ui`** (the same package as
`:app`'s screens, so `:app`'s imports do not change and every move is a plain file move); the Compose resources'
generated class is `app.doorprints.ui.res.Res`. Declarations `:app` uses are `public` instead of `internal`. Until
2026-09-24 the packages kept the old product name (`com.househunt.app.ui`, namespace `com.househunt.ui`).

## 4. What is in it now (phases 1 to 3)

| Source set | File | Contents |
|---|---|---|
| commonMain | `Theme.kt` | `DoorprintsTheme`, colours, type; the language lookup is `expect fun uiLanguage()` |
| commonMain | `Rows.kt`, `ServerStatus.kt` | list rows; the server status line and its rules |
| commonMain | `MapRules.kt`, `IndiaViewRules.kt` | the Map's layout rules; India's boundary rules as data (ADR-22), `WORLD_MAX_ZOOM` computed from the float's bits (common code; the same value as `Math.nextDown`) |
| commonMain | `Buttons.kt` | `ANIMATION_MS`, `ButtonLabel`, `BUTTON_LABEL_MAX_LINES` (from `ActionBar.kt`) |
| commonMain | `ResultTone.kt`, `LocationFix.kt` | from `ResultCard.kt` and `LocationPermission.kt` |
| commonMain | `PlatformServices.kt` | the platform seam (phase 3): `interface PlatformServices { fun isScreenReaderOn(): Boolean }` and `LocalPlatformServices` (no default: a root must provide it); later members are listed in its KDoc |
| commonMain | `Format.kt` | `Formats`: `rupees` (₹ and lakh grouping, `indianGrouping`), `price`, `score` (one decimal, half up) in common code; `date` and `dateTime` through `internal expect fun formatDate`; `expect fun appLanguage()`; the composables `priceText(price, priceType)` (`price_per_month` from Compose resources), `scoreText()`, `dateText()` |
| commonMain | `LiveMessage.kt`, `DeletedHouseUndo.kt` | the live region that is there before its message; the "Deleted …" snackbar with *Undo*, given the repository's read and write as two lambdas (`:app`'s `DeletedHouses.kt` passes the Room repository until CMP-4) |
| commonMain | `ActionBar.kt`, `ResultCard.kt` | Export's and Import's sticky bar, `StatusLine`, `WorkProgress`, `ProgressBar`, `BarButton`, `StateButton`, `DangerButton`; the result cards and `ResultActionsRow` |
| androidMain | `PlatformServices.android.kt` | `AndroidPlatformServices` (TalkBack's touch exploration from `AccessibilityManager`) and `ProvidePlatformServices { }`, used by `MainActivity` and the screenshot tests |
| androidMain | `Format.android.kt` | `java.time`'s medium date and short time for `<language>-IN`; `appLanguage()` is `Locale.getDefault()`, which `AppLocale.applyDefault` keeps on the resolved language |
| androidMain | `UiLanguage.android.kt` | `appLanguage()`, read again when `LocalConfiguration` changes (since CMP-3, S4b-BL-18; was the configuration's first locale) |
| iosMain | `UiLanguage.ios.kt`, `Format.ios.kt` | Compose's `Locale.current`; `NSDateFormatter` (medium date, short time) for `<language>_IN` |
| commonMain | `composeResources/values{,-hi,-ta,-te}/strings.xml` | the UI strings (phase 2): 403 strings and 16 plurals per language, positional placeholders only, a plain `'` (no Android escapes); checked by `:app`'s `StringParityTest` (docs/06 TC-U-59) |
| commonTest | `ServerStatusTest.kt`, `MapRulesTest.kt`, `IndiaViewRulesTest.kt`, `FormatsTest.kt` | `kotlin.test`; the Map and India view tests moved from `:app` (JUnit) in phase 3, and compile for iOS too |
| androidHostTest | `FormatsParityTest.kt` | the common amounts and scores against the JVM's `NumberFormat` and `String.format` in the four languages |

Screens, the map view and everything that touches Android services are still in `:app`, and so are the service
strings (notifications, workers, `HuntService`) in `android/app/src/main/res/values*/strings.xml`, with `'` escaped as
`\'`; keys both use are in both places with the same text. Outside composition `:app` reads a UI string with Compose's
suspend `getString(Res.string.x)` in a coroutine or effect (the blocking `ui/UiStrings.kt` helper went in CMP-3), or
resolves it with `stringResource` in composition and hands it to the click handler. Compose resources take the language
from the process's default locale, which `:app`'s `AppLocale.applyDefault` keeps on the language Android resolved
(docs/05 §8.2, TC-U-60); the dates and the theme's language rules read the same (`appLanguage()`, TC-U-61).

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
and the smoke tests (`app/src/androidTest/.../SmokeTest`, TC-I-35) run the APK on an API 34 emulator
(`android-emulator.yml`). A phase that changes a screen on purpose re-records with `./gradlew :app:recordRoborazziDebug`
and names the changed images in its pull request. When a screen moves into `:ui`, update the test's import, not the
image.

## 6. Phases (ADR-23)

Each phase (and each lettered sub-phase) is one pull request that keeps `android.yml` green and runs the CLAUDE.md review steps.

| Phase | Ticket | Scope | Status |
|---|---|---|---|
| P1 | CMP-1 | `:ui` module; theme and pure UI code | **Done** (`be86f50`); iOS compile pending on CI (macOS) |
| P2 | CMP-2 | UI strings to Compose resources (`values{,-hi,-ta,-te}`), `org.jetbrains.compose` plugin, `Res.string`; service strings stay Android resources; `AppLocale.applyDefault` on every API level; `localeFilters`; `StringParityTest`, `AppLocaleTest` | **Done** (PR #19, merged as `7080a7f`) |
| P3 | CMP-3 | `PlatformServices` seam (screen reader only, so far); `Format.kt` (common amounts, `expect` dates), `LiveMessage`, `DeletedHouseUndo`, `ActionBar`, `ResultCard`; `UiStrings.kt` removed; S4b-BL-18; `MapRulesTest`, `IndiaViewRulesTest` to commonTest | **Done** (PR #20, merged as `fccf8a1`) |
| P4a, P4b, P4c | CMP-4 | Room KMP (the catalog's version, 2.8.5 since Dependabot #12) in `:shared` (db v2 identity hash kept, migration test); DataStore KMP, `SecretStore`, `ServerUrl` in common; `Repository` interface in common, `CompareScreen` and `HouseFormRules` move | **P4a done** (the Room database in `:shared`, PR #21, merged as `e480b83`; [docs/10](../../docs/10-sprint-log.md) §13.6); **P4b done in code** (settings, `SecretStore`, `ServerUrl` in `:shared`, PR #22; §13.7); P4c planned |
| P5 | CMP-5 | JetBrains navigation-compose and lifecycle; ViewModels; HouseList, Assistant, Settings, NotifyAsk, LocationPermission | Planned |
| P6a, P6b | CMP-6 | HouseEditScreen (photo and camera seam); Export and Import screens, `ImportViewModel`, workers behind an interface | Planned |
| P7 | CMP-7 | Map: common chrome, `expect PlatformMap`, common `applyIndiaView(ops: StyleOps)`; TC-M-25 re-run | Planned |
| P8 | CMP-8 | iOS shell (`iosApp`, `ComposeUIViewController`, MapLibre iOS via SPM), simulator build on macOS CI, unsigned | Planned |
| Spike | CMP-9 | maplibre-compose (0.17, pre-1.0); re-assess at 1.0 | Planned |

Out of scope until there is a Mac and a paid Apple Developer account (about US$99 a year, against the zero-cost
rule): signing, device installs, TestFlight and the App Store. iPhone users keep the PWA until then.
