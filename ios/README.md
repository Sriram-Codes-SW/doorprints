# Doorprints for iOS

The iOS app (CMP-8b, ADR-23) is a small SwiftUI shell, `Doorprints/DoorprintsApp.swift`, around the Compose
Multiplatform UI of the Gradle module `:ui` (`android/ui`). Gradle builds that UI as the static framework
`DoorprintsKit`; Swift calls `MainViewControllerKt.MainViewController()` and shows it edge to edge. There is no
Swift UI of our own. Owner rules: no paid Apple account, no signing, no App Store; the app runs on the simulator.

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

The first build step runs `./gradlew :ui:embedAndSignAppleFrameworkForXcode` in `android/` (JetBrains' "direct
integration"): it builds the framework for the chosen configuration and SDK into
`android/ui/build/xcode-frameworks/<Configuration>/<SDK>` and copies the Compose resources (the strings of the four
languages) into the app as `compose-resources/`. It takes JDK 21 from `JAVA_HOME` if that is set, else from
`/usr/libexec/java_home -v 21`, and stops with a clear error if neither has one. The simulator build is arm64 only
(Apple silicon Macs); Kotlin has no x86_64 simulator target here.

**Self-check.** A Debug build started with the argument `-DoorprintsSelfCheck` (in Xcode: Edit Scheme, Run,
Arguments) prints `DOORPRINTS-SELFCHECK <name> PASS|FAIL|SKIP ...` for resources, database, settings and keychain,
and then `DOORPRINTS-SELFCHECK done PASS` or `done FAIL`.

## What iOS does not have yet

Hidden on iOS, not shown disabled (owner decision of 2026-09-29, `docs/10` §13.11): the Hunt card, the camera and
gallery, *Save a copy*, *Import a backup* and the weekly backup. The map is a placeholder until CMP-8c (MapLibre iOS
with the India view). Hindi, Tamil and Telugu strings, the location purpose string included
(`<lang>.lproj/InfoPlist.strings`), ship marked *under review*.

## CI

Job `ios-app` in `.github/workflows/shared-ios.yml` (macOS runner, free for public repositories): installs XcodeGen
from its pinned release zip, generates the project, checks the plists, builds the Debug app for the arm64 simulator
with `CODE_SIGNING_ALLOWED=NO`, checks that the app holds `compose-resources` and the 120 Hz key, and runs
`ci/launch-smoke.sh`: it installs the app on an iPhone simulator, launches it with `-DoorprintsSelfCheck`, passes only
on `DOORPRINTS-SELFCHECK done PASS` and saves a screenshot. The screenshot and the logs are uploaded as the
`ios-app-launch` artifact. If the simulator ever refuses the unsigned app, the owner-approved fallback is ad-hoc
signing (`CODE_SIGN_IDENTITY=-`), still without an Apple account.
