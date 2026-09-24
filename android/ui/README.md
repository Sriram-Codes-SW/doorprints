# `:ui` — Compose Multiplatform UI

| | |
|---|---|
| Version | 1.2 |
| Date | 2026-09-24 |
| Sprint | Compose Multiplatform track ([docs/10](../../docs/10-sprint-log.md) §13, CMP-1..CMP-9) |
| Owner | Android team |

**Change log**

| Version | Date | Change |
|---|---|---|
| 1.2 | 2026-09-24 | Section 5: `android.yml` runs the unit tests with `-Proborazzi.test.verify=true`, and each phase is checked by the JVM screenshot tests of `:app` (docs/06 TC-U-60) and the emulator smoke tests (TC-I-35, `android-emulator.yml`); CMP-0, commit `afe4064` ([docs/10](../../docs/10-sprint-log.md) §13.3). |
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

Plugins: `kotlin.multiplatform`, `android.kotlin.multiplatform.library`, `kotlin.compose`. The
`org.jetbrains.compose` plugin is not needed yet; it comes with the string resources in phase 2.

commonMain dependencies (all `api`, because the theme and composables take and return Compose types):

| Library | Version | Android mapping |
|---|---|---|
| `org.jetbrains.compose.runtime:runtime`, `foundation:foundation`, `ui:ui` | 1.12.1 | androidx.compose 1.12.1 |
| `org.jetbrains.compose.material3:material3` | 1.9.0 | androidx material3 1.4.0 |
| `org.jetbrains.compose.material:material-icons-core` | 1.7.3 | androidx 1.7.3 (Gradle resolves to the 1.7.8 `:app` already uses) |
| `project(":shared")` | – | domain types the UI is built on (`HouseStatus`, `SyncOutcome`) |

Versions live in `android/gradle/libs.versions.toml` (`cmp`, `cmp-material3`, `cmp-material-icons`).

## 3. Package name

The Android namespace (R class) is **`com.househunt.ui`**, but the Kotlin package of the moved files stays
**`com.househunt.app.ui`**, so `:app`'s imports do not change and every move is a plain file move. Declarations `:app`
uses are `public` instead of `internal`. Package names keep the old product name on purpose (ADR-13).

## 4. What is in it now (phase 1)

| Source set | File | Contents |
|---|---|---|
| commonMain | `Theme.kt` | `HouseHuntTheme`, colours, type; the language lookup is `expect fun uiLanguage()` |
| commonMain | `Rows.kt`, `ServerStatus.kt` | list rows; the server status line and its rules |
| commonMain | `MapRules.kt`, `IndiaViewRules.kt` | the Map's layout rules; India's boundary rules as data (ADR-22), `WORLD_MAX_ZOOM` computed from the float's bits (common code; the same value as `Math.nextDown`) |
| commonMain | `Buttons.kt` | `ANIMATION_MS`, `ButtonLabel`, `BUTTON_LABEL_MAX_LINES` (from `ActionBar.kt`) |
| commonMain | `ResultTone.kt`, `LocationFix.kt` | from `ResultCard.kt` and `LocationPermission.kt` |
| androidMain | `UiLanguage.android.kt` | `LocalConfiguration`'s locale, as before |
| iosMain | `UiLanguage.ios.kt` | Compose's `Locale.current` |
| commonTest | `ServerStatusTest.kt` | 5 tests (`kotlin.test`) |

Strings, screens, the map view and everything that touches Android services are still in `:app`.

## 5. Build and test

From `android/`:

```bash
./gradlew :ui:testAndroidHostTest     # :ui's JVM tests; :app:testDebugUnitTest depends on it, android.yml also names it
./gradlew assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest \
  :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata -Proborazzi.test.verify=true   # what android.yml runs
./gradlew :ui:compileCommonMainKotlinMetadata   # commonMain against the common libraries, on Linux: catches JVM-only calls
# macOS only (shared-ios.yml): compile-only, nothing is linked, signed or run on a simulator
./gradlew :ui:compileKotlinIosArm64 :ui:compileKotlinIosSimulatorArm64 :ui:compileTestKotlinIosSimulatorArm64
```

On Linux the iOS tasks are skipped (`kotlin.native.enableKlibsCrossCompilation=false`, see the `:shared` README §5).
`shared-ios.yml` watches `android/ui/**`; its job name is unchanged in case it is a required check. The rules for
commonMain in the `:shared` README §6 apply here too.

**How a phase shows that no screen changed.** The screens are still rendered from `:app`, so the checks live there: the
JVM screenshot tests (`app/src/test/.../screenshots/ScreensScreenshotTest`, docs/06 TC-U-60; every screen but the Map,
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
| P2 | CMP-2 | Strings to compose-resources (`values{,-hi,-ta,-te}`), `org.jetbrains.compose` plugin, `Res.string`, `Locale.setDefault` on API 26–32, `StringParityTest` | Next |
| P3 | CMP-3 | `PlatformServices` seam; `Format.kt`, `LiveMessage`, `DeletedHouseUndo`, `ActionBar`, `ResultCard`, pure helpers | Planned |
| P4a, P4b, P4c | CMP-4 | Room KMP (the catalog's version, 2.8.5 since Dependabot #12) in `:shared` (db v2 identity hash kept, migration test); DataStore KMP, `SecretStore`, `ServerUrl` in common; `Repository` interface in common, `CompareScreen` and `HouseFormRules` move | Planned |
| P5 | CMP-5 | JetBrains navigation-compose and lifecycle; ViewModels; HouseList, Assistant, Settings, NotifyAsk, LocationPermission | Planned |
| P6a, P6b | CMP-6 | HouseEditScreen (photo and camera seam); Export and Import screens, `ImportViewModel`, workers behind an interface | Planned |
| P7 | CMP-7 | Map: common chrome, `expect PlatformMap`, common `applyIndiaView(ops: StyleOps)`; TC-M-25 re-run | Planned |
| P8 | CMP-8 | iOS shell (`iosApp`, `ComposeUIViewController`, MapLibre iOS via SPM), simulator build on macOS CI, unsigned | Planned |
| Spike | CMP-9 | maplibre-compose (0.17, pre-1.0); re-assess at 1.0 | Planned |

Out of scope until there is a Mac and a paid Apple Developer account (about US$99 a year, against the zero-cost
rule): signing, device installs, TestFlight and the App Store. iPhone users keep the PWA until then.
