# `:shared` — Kotlin Multiplatform foundation

| | |
|---|---|
| Version | 1.1 |
| Date | 2026-09-22 |
| Sprint | 3.5 "KMP foundation" |
| Owner | Android team |

**Change log**

| Version | Date | Change |
|---|---|---|
| 1.1 | 2026-09-22 | Review follow-ups: section 5 matches CI (`shared-ios.yml` compiles only; `kotlin.native.enableKlibsCrossCompilation=false` keeps iOS off the ubuntu job), MapLibre on OkHttp 5.5.0 documented with a manual map-tile smoke test, Room schema export + identity-hash guard (`RoomSchemaTest`), photo upload streamed from the file again, sync worker no longer records a WorkManager stop as a failed sync. |
| 1.0 | 2026-09-22 | First version. `:shared` module created; pure logic, DTOs and the API client moved out of `:app`; OkHttp client replaced by a Ktor client with the same behaviour. |

## 1. Why this module exists

Product decision (Sprint 3.5): make the Android code base **Kotlin Multiplatform-ready now, ship iOS later**. No iPhone,
no Mac and no Apple developer fee are available, so this sprint adds **no iOS app and no iOS UI**. The goal is to
make sure that the code an iOS app would reuse already compiles without Android or JVM APIs, so Phase 2 does not
start with a large refactor.

For the user nothing changes: same screens, same strings, same Room database file and migrations, same sync
protocol and the same HTTP requests (checked by contract tests, section 5).

## 2. Module layout and targets

```
android/
├── gradle/libs.versions.toml   one version catalog for :app and :shared
├── app/                        Android app (unchanged package com.househunt.app)
└── shared/                     this module
    └── src/
        ├── commonMain/         platform-neutral code (no java.*, no android.*)
        ├── commonTest/         kotlin.test suites for everything in commonMain
        └── androidMain/        Android-only glue (Ktor OkHttp engine)
```

| Target | Plugin / DSL | Built where | Purpose |
|---|---|---|---|
| Android | `com.android.kotlin.multiplatform.library` (AGP 9.4.0), `kotlin { android { … } }` | ubuntu CI (`android.yml`) | consumed by `:app` |
| `iosArm64`, `iosSimulatorArm64` | `org.jetbrains.kotlin.multiplatform` (Kotlin 2.4.10) | macOS CI job (DevSecOps) | **compile-only** guard: proves commonMain has no JVM/Android APIs; no framework binary is produced |

`android {}` is the AGP 9 name of the Android target block (`androidLibrary {}` is deprecated since AGP 9.1).
Host tests are opt-in with this plugin; `withHostTest {}` enables them.

Package: **`com.househunt.shared`**, next to the app's `com.househunt.app` and the backend's `com.househunt.*`
(the product was renamed to Doorprints, but package names stayed on purpose, see `app/build.gradle.kts`).

| Package | Contents |
|---|---|
| `model` | `HouseStatus`, `VisitSource` (wire/DB names + `fromWire`), `Checklist.keys`, `HouseScore` (score and ranking), `MAX_PHOTOS_PER_HOUSE` |
| `sync` | `SyncRecord` (implemented by the Room entities), `SyncRules` (last-edit-wins), `SyncOutcome` (stored sync result code) |
| `location` | `Geo.distanceM` (haversine, pure math), `StayDetector`, `StreetAlerts` |
| `api` | DTOs (`HouseDto`, `VisitDto`, `PhotoChangeDto`, AI DTOs, …), `IsoTime`, `ApiException`, `RetryPolicy`, `ApiClient`, `ApiHttp`; `AndroidApiHttp` in androidMain |

## 3. What stays in `:app` and why

| Stays in `:app` | Why |
|---|---|
| Room database (`AppDatabase`, DAOs, entities `HouseEntity`/`VisitEntity`/`PhotoEntity`) | Moving Room to KMP (Room 2.7+ `commonMain` + `BundledSQLiteDriver` or the Android driver) touches the on-device database of every tester: schema export, identity hash, migrations 1→2, the checklist `TypeConverter`. That deserves its own sprint with migration tests on real devices. The entities already implement the shared `SyncRecord` and use the shared enums, so they can move almost unchanged. |
| Entity ↔ DTO mappers (`data/Mappers.kt`) | They reference the Room entities; all the logic they use (`IsoTime`, `fromWire`) is shared. |
| `ServerUrl` validation | Uses `java.net.URI`, whose exact parsing (IPv6, spaces, user-info) is what the tests pin. A common rewrite would need an `expect/actual` (NSURLComponents on iOS); Phase 2. |
| DataStore settings, Keystore API-key encryption (`ApiKeyCipher`) | Android APIs; DataStore has a KMP artifact (Phase 2), the key store needs `expect/actual` (Android Keystore / iOS Keychain). |
| WorkManager (`SyncWorker`), `NetworkState`, `HuntService`, fused location, `ReverseGeocoder`, notifications | Platform services. iOS equivalents: `BGTaskScheduler`, `NWPathMonitor`, `CLLocationManager`, `CLGeocoder`. |
| Compose UI, MapLibre, string resources, `HouseStatus.labelRes`, `ChecklistLabels` | UI and translations stay per platform in this phase. |

## 4. Decisions

* **HTTP: Ktor 3.6.0**, `ktor-client-core` in commonMain, `ktor-client-okhttp` in androidMain (OkHttp 5.5.0, the
  version Ktor 3.6.0 is built against). One `HttpClient` for the whole app (`app/.../data/Api.kt`), so connections
  are pooled as before.
* **No ContentNegotiation plugin.** The client must first check the response's `Content-Type` (a captive portal
  answers `200 text/html`) and only then decode, and it must send exactly the JSON the old client sent. So
  `ApiClient` encodes and decodes with its own `Json { ignoreUnknownKeys = true; explicitNulls = false }` (same
  settings as v0.1) and sends `application/json; charset=UTF-8`. This also avoids two extra artifacts.
* **Retries in `ApiClient`, not Ktor's `HttpRequestRetry`.** The old `RetryInterceptor` rules (idempotent methods
  plus the explicitly marked photo upload, codes 408/429/502/503/504, full-jitter backoff 1 s base / 15 s cap,
  3 attempts, short `Retry-After` honoured, long one handed back) are kept exactly in `RetryPolicy`.
* **Timeouts.** Socket timeouts are engine settings (`AndroidApiHttp`: connect 20 s, read 90 s, write 60 s). The
  4-minute limit for a whole call including retries is in `ApiClient` (`withTimeoutOrNull`), which throws
  `ApiTimeoutException`, an `IOException`, as OkHttp's call timeout did. Ktor's `HttpTimeout` plugin is not
  installed, because it would set read and write to the same value.
* **Redirects are never followed** (`followRedirects = false` in `ApiHttp`, and the OkHttp engine's own
  `followRedirects(false)`). A 3xx is reported as `CAPTIVE_PORTAL` and the API key never reaches another host.
* **Time: `kotlin.time.Instant`** from the Kotlin standard library (stable since Kotlin 2.3) instead of
  kotlinx-datetime (latest tag v0.8.0), so no extra dependency. Output equals `java.time.Instant.toString()` for
  millisecond values (tests pin this).
* **`IOException` in common code is `kotlinx.io.IOException`** (a typealias of `java.io.IOException` on Android,
  pulled in by Ktor). `ApiException` extends it, so `SyncOutcome.fromError` classifies errors as before.
* **Logging** is injected (`ApiClient(debugLog = …)`): `:app` sends status, method and path to logcat only when
  `Log.isLoggable("HouseHuntApi", DEBUG)`. Bodies and the key are never logged (threat model F-12).

Known, intentional differences from the OkHttp client (none visible to the user):

* Ktor adds `Accept: */*` and `Accept-Charset: UTF-8` request headers, and the `User-Agent` is Ktor's instead of
  `okhttp/4.12.0`. The backend does not look at these.
* The response body is read inside the retry loop, so a connection that drops while a GET/PUT/DELETE body is being
  read is now retried too (before, only failures before the headers arrived were).
* The photo upload is streamed from the file as before (`ApiClient.uploadPhoto(…, size) { source }`, Ktor
  `InputProvider`); the file is opened again for each retry. The multipart body is unchanged, including the part's
  `Content-Length`, and the request keeps a known length.
* **OkHttp 5.5.0 instead of 4.12.0 for MapLibre.** MapLibre Android 13.6.1 is built against OkHttp 4.12.0 (its
  `platform/android/gradle/libs.versions.toml`) and has no OkHttp of its own in the APK; Ktor 3.6.0's OkHttp engine
  brings OkHttp 5.5.0, and Gradle resolves the whole app to 5.5.0. OkHttp 5 is meant to be binary compatible with
  4.x for the public API MapLibre uses (client builder, requests, callbacks), but **no CI job exercises MapLibre
  networking**: an incompatibility would only show up on a device as a `NoSuchMethodError` or tiles that never load.
  Hence the manual smoke test in section 5, run on every build that changes the Ktor or MapLibre version.

## 5. Tests

| Where | Suite | What it pins |
|---|---|---|
| `commonTest/api` | `ApiClientContractTest` | Ktor `MockEngine` with responses recorded from the backend's DTOs, filters and exception handlers: X-API-Key header, URLs and exact request JSON, multipart upload parts (in-memory and streamed, one fresh file source per attempt), 401/403 → AUTH, 404/409/400/413 mapping, 429 with short and long `Retry-After`, AI 503 → AI_UNAVAILABLE, 502/503 retried then given up, network errors retried only for idempotent calls, HTML captive-portal page, 302/307 not followed, wrong content types, overall call timeout |
| `commonTest/api` | `IsoTimeTest`, `RetryPolicyTest` | Same ISO strings as `java.time.Instant`; backoff bounds |
| `commonTest/model`, `sync`, `location` | `HouseScoreTest`, `ModelTest`, `SyncRulesTest`, `SyncOutcomeTest`, `StayDetectorTest`, `GeoTest`, `StreetAlertsTest` | The rules moved from `:app` (the old JUnit tests, ported to `kotlin.test`) |
| `app/src/test` | `ModelMappingTest`, `ServerUrlTest`, `RoomSchemaTest` | Entity ↔ DTO mapping, labels for every shared key/status, shared rules on Room entities; URL validation; Room identity hash of database version 2 (see below) |

Commands (from `android/`):

```bash
./gradlew assembleDebug testDebugUnitTest      # CI command; :app:testDebugUnitTest depends on :shared:testAndroidHostTest
./gradlew :shared:testAndroidHostTest          # shared tests only (JVM, no emulator)
./gradlew :shared:allTests                     # android.yml also runs this; on Linux it runs the same host tests
# macOS only (shared-ios.yml, DevSecOps): compile-only, nothing is linked or run on a simulator
./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64
```

Reports: `shared/build/reports/tests/testAndroidHostTest/` and `shared/build/test-results/testAndroidHostTest/`.

**iOS targets on Linux.** Kotlin 2.4.10 can cross-compile iOS klibs on Linux and does so by default, so
`:shared:allTests` on ubuntu would compile the iOS main and test klibs. `gradle.properties` sets
`kotlin.native.enableKlibsCrossCompilation=false`, so on Linux the iOS compile, link and test tasks are disabled and
skipped, and `kotlin.native.ignoreDisabledTargets=true` hides the "cannot be built on this host" notice. The iOS
check therefore happens only on macOS (`shared-ios.yml`), where the flag has no effect. The iOS tests themselves
(`:shared:iosSimulatorArm64Test`) are not run anywhere yet; that needs a simulator and is Phase 2.

**Room identity-hash guard.** `AppDatabase` exports its schema (`exportSchema = true`, KSP argument
`room.schemaLocation`), and `app/schemas/com.househunt.app.data.AppDatabase/2.json` is committed. `RoomSchemaTest`
checks that both that file and the generated `AppDatabase_Impl` carry the identity hash of the version-2 layout the
app shipped with (`539964c2013f14439605fab0d18a142a`). Any drift in the tables, for example through the
`HouseStatus`/`VisitSource` types that now live in `:shared`, fails `testDebugUnitTest` instead of crashing upgraded
installs with "Room cannot verify the data integrity". A real schema change needs a version bump, a migration and a
new `<version>.json`, never an edit to `2.json`.

**Manual smoke test on a device** (no automated test covers this; run it for every build that changes the Ktor,
OkHttp or MapLibre version, see section 4):

1. Install the debug APK from CI on a phone with a working internet connection; start with an empty tile cache
   (fresh install or cleared app storage).
2. Open the map: street tiles load and keep loading while panning and zooming into an area not viewed before.
3. Add a house and a photo, run "Sync now": the sync succeeds and the photo appears in the web app.
4. `adb logcat | grep -iE "NoSuchMethodError|NoClassDefFoundError|okhttp|maplibre"` shows no errors.

## 6. Rules for commonMain

* No `java.*`, `javax.*`, `android.*`, `System.*`, `Math.*`, `String.format`, `Thread`, `synchronized`. Use
  `kotlin.math`, `kotlin.time`, `kotlinx.coroutines`, `kotlinx.io`, Ktor. The iOS compile in CI enforces this.
* Anything with a wire or database name (`HouseStatus`, `VisitSource`, checklist keys, `SyncOutcome.encode`) must
  never be renamed; tests pin the names.
* New platform needs go into `androidMain` (and later `iosMain`) behind a small common interface or an injected
  function, like `debugLog` and `HttpClientEngine`.

## 7. Phase 2 plan (not in this sprint)

1. **Room KMP.** Move entities, DAOs and `AppDatabase` to commonMain (Room 2.8 KMP + `androidx.sqlite`
   bundled driver), keep the file name `househunt.db`, version 2 and `MIGRATION_1_2`; the exported `2.json` and
   `RoomSchemaTest` (section 5) must keep passing, and add a migration test that opens a v1/v2 database created by
   the current app. Then the mappers move too.
2. **DataStore KMP** (`datastore-preferences-core` + okio) for settings and sync cursors; `expect/actual` secret
   storage (Android Keystore today, iOS Keychain).
3. **`ServerUrl`** as `expect/actual` or a common parser with the existing test cases.
4. **iOS app**: SwiftUI over the shared module (SKIE or plain Kotlin/Native framework), or Compose Multiplatform
   if the Compose UI is to be shared. Decide when a Mac and the Apple Developer Program are available; until then
   only the compile-only check runs. `iosMain` then gets `ktor-client-darwin` and its timeouts.
5. **iOS platform services**: location via `CLLocationManager` (significant-change + region monitoring feeding the
   shared `StayDetector` and `StreetAlerts`), reverse geocoding via `CLGeocoder`, background sync via
   `BGTaskScheduler`, reachability via `NWPathMonitor` (captive-portal detection stays in the shared client).
