# Doorprints for iOS

The iOS app (CMP-8b, ADR-23) is a small SwiftUI shell, `Doorprints/DoorprintsApp.swift`, around the Compose
Multiplatform UI of the Gradle module `:ui` (`android/ui`). Gradle builds that UI as the static framework
`DoorprintsKit`; Swift calls `MainViewControllerKt.MainViewController()` and shows it edge to edge. There is no
Swift UI of our own. Owner rules: no paid Apple account, no certificate or signing identity, no App Store; the app
runs on the simulator, ad-hoc signed (below).

## Build on a Mac

1. Xcode (CI uses 26.4.1) with an iOS simulator runtime, and **JDK 21** (for example Temurin 21).
2. XcodeGen 2.46.0: `brew install xcodegen`, or the release zip from
   https://github.com/yonaskolb/XcodeGen/releases/tag/2.46.0 (CI pins its SHA-256 in `shared-ios.yml`).
3. Generate the project and open it:

   ```sh
   cd ios
   xcodegen generate
   open Doorprints.xcodeproj
   ```

4. Pick an iPhone simulator and run the `Doorprints` scheme.

`Doorprints.xcodeproj` is generated from `project.yml` and not committed; change `project.yml` and generate again.

**Signing.** Simulator builds are ad-hoc signed (`CODE_SIGN_STYLE: Manual`, `CODE_SIGN_IDENTITY` `-` for the
simulator SDK in `project.yml`): no team, certificate or Apple account, but a real signature, so the simulator gives
the app its own Keychain for the server's API key. To run on your own iPhone, choose a team and automatic signing in
Xcode (a free Apple ID is enough for your own device); do not commit that change.

**Servers over `http://`.** App Transport Security stays at its default (no exceptions in `Info.plist`). It refuses
plain `http://` to a domain name, so such a server must be `https://`. A server on a LAN IP address
(`http://192.168.1.20:8080`) or a `.local` name is not covered by ATS and connects without TLS, as on Android; iOS
first asks for local network access (`NSLocalNetworkUsageDescription`, in the four languages).

The first build step runs `./gradlew :ui:embedAndSignAppleFrameworkForXcode` in `android/` (JetBrains' "direct
integration"): it builds the framework for the chosen configuration and SDK into
`android/ui/build/xcode-frameworks/<Configuration>/<SDK>` and copies the Compose resources (the strings of the four
languages) into the app as `compose-resources/`. It takes JDK 21 from `JAVA_HOME` if that is set, else from
`/usr/libexec/java_home -v 21`, and stops with a clear error if neither has one. The simulator build is arm64 only
(Apple silicon Macs); Kotlin has no x86_64 simulator target here.

**Self-check.** A Debug build started with the argument `-DoorprintsSelfCheck` (in Xcode: Edit Scheme, Run,
Arguments) prints `DOORPRINTS-SELFCHECK <name> PASS|FAIL|SKIP ...` for resources, database, settings, keychain, `indiaView`
and `map` (CMP-8c: the map's style keeps India's boundary rules, and the map on screen loaded all of it) and `hunt`
(S4b-BL-69: with the location permission and a simulated location next to a house the check saves, Hunt mode starts
and names that house), and then `DOORPRINTS-SELFCHECK done PASS` or `done FAIL`, to the console and to the unified
log (`NSLog`).

## What iOS does not have yet

Hidden on iOS, not shown disabled (owner decision of 2026-09-29, `docs/10` §13.12): the camera and gallery, *Save a
copy*, *Import a backup* and the weekly backup. Hindi, Tamil and Telugu strings, the purpose strings included
(`<lang>.lproj/InfoPlist.strings`), ship marked *under review*.

## Google Drive (S4b-BL-117; docs/15 §9.11)

Settings > Google Drive on the iPhone is the common Drive screens over the same graph Android uses. **It says "not
available" until your iOS client id is set**; no client id is in the repository. To try it on your own build:

1. Make the *iOS* OAuth client of your Google Cloud project for bundle id `app.doorprints` (docs/15 §2.4).
2. Create the git-ignored `ios/Config/Drive.local.xcconfig` with two lines (`Config/Drive.xcconfig` explains them):

   ```
   GOOGLE_IOS_CLIENT_ID = <number>-<random>.apps.googleusercontent.com
   GOOGLE_IOS_URL_SCHEME = com.googleusercontent.apps.<number>-<random>
   ```

3. `xcodegen generate` again and build with your own team to an iPhone with a **passcode** (the Simulator has no Secure
   Enclave and no passcode by default; there the device key is a software Keychain key so the screens can be seen, but
   Google's sign-in and Face ID need the phone). The first becomes Info.plist's `GoogleIOSClientId`, the second the
   registered redirect scheme.

The sign-in, the Secure Enclave key and the Keychain items are signed-app features: an ad-hoc signed Simulator build
has Keychain access, an unsigned test binary does not (the real-Keychain test skips there). The phone checks are
MT-86..MT-92 in `docs/ops/manual-test-checklist.md`.

## Offline maps (S4b-FR-6)

`MapLibreOfflineMaps.swift` implements the Kotlin interface `IosOfflineMaps` (IosMap.kt) over `MLNOfflineStorage`:
a saved area is one tile-pyramid offline pack of the Liberty style from zoom 0 to 14, its id and name in the pack's
context, every change reported to Kotlin as one snapshot (KVO on `packs`, the progress and error notifications);
`NWPathMonitor` says whether the network is expensive. Registered in `DoorprintsApp.init` beside the map factory.
`docs/10` §13.15.

## Hunt mode (S4b-BL-69)

`IosHunt.kt` (android/ui's iosMain) is the iPhone's adapter around the common `HuntEngine`: `CLLocationManager` with
background updates under the *When in use* permission (`UIBackgroundModes` `location` in `Info.plist`; the blue
indicator shows while it runs; *Always* is never asked for), the alerts as local notifications (`IosNotifications.kt`;
the Swift app sets the notification delegate in its `init`, `MainViewControllerKt.installNotifications()`), street
names and the new house's address from Apple's `CLGeocoder`, the battery from `UIDevice`. `docs/10` §13.14.

## The map (CMP-8c)

MapLibre iOS 6.31.0 from MapLibre's Swift package, pinned by commit in `project.yml` (Xcode resolves it on the first
build). `Doorprints/MapLibreMapView.swift` wraps `MLNMapView` behind the Kotlin interfaces of `IosMap.kt`; Kotlin
downloads the OpenFreeMap Liberty style, applies India's boundary rules to its JSON with the same common steps as
Android (`prepareMapStyle`, ADR-22) and hands MapLibre the finished style, so Swift never touches a filter. The
boundary files are Android's own (`android/app/src/main/assets/geo`), bundled as `geo/`. `docs/10` §13.13.

## CI

Job `ios-app` in `.github/workflows/shared-ios.yml` (macOS runner, free for public repositories): installs XcodeGen
from its pinned release zip, generates the project, checks the plists, builds the Debug app for the arm64 simulator
ad-hoc signed (`CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO`; owner-approved, no identity or Apple account), checks
that the app holds `compose-resources`, the 120 Hz key, both purpose strings, the location background mode and a
valid signature, and runs `ci/launch-smoke.sh`: it installs the app on an iPhone simulator, grants it location and
sets a simulated location next to the self-check's house (`simctl privacy`, `simctl location`), launches it with
`-DoorprintsSelfCheck`, passes only on `DOORPRINTS-SELFCHECK done PASS` with the `keychain`, `indiaView`, `map` and `hunt` lines each `PASS` and no `SKIP` line
(read from the console, or from the simulator's unified log when the console has no `done` line) and saves a
screenshot. The screenshot and the logs are uploaded as the `ios-app-launch` artifact.
