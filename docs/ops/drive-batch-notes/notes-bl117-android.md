# S4b-BL-117 Android: Drive Connect Compile & Test Pass

## Status: DONE

All Android compilation and test steps completed successfully on 2026-10-02.

## Compilation steps completed

1. **Common metadata compilation**: ✅ PASS
   - `./gradlew --offline :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata`
   - 15 tasks executed in 48s

2. **App Kotlin compilation**: ✅ PASS (2 fixes applied)
   - Fixed: `AndroidAppServices.kt` - imported `AndroidDriveServices` instead of using qualified path (parameter `app` was shadowing package path)
   - Fixed: `AndroidDriveServices.kt` - passed `utcOffsetMinutes` as named parameter to `DriveBackupService` constructor (was being assigned to wrong parameter position)
   - `./gradlew --offline :app:compileDebugKotlin` - 26 tasks in 36s

3. **Drive strings test**: ✅ PASS
   - `./gradlew --offline :ui:testAndroidHostTest --tests '*DriveStringsTest*'` - 25 tasks in 7s

4. **Drive connect tests**: ✅ PASS
   - `./gradlew --offline :shared:testAndroidHostTest --tests 'app.doorprints.drive.connect.*'` - 10 tasks in 7s

## Verification steps completed

5. **String localization**: ✅ ALL FOUR LANGUAGES COMPLETE
   - English: 131 drive_ keys in `values/strings.xml`
   - Hindi: 131 drive_ keys in `values-hi/strings.xml`
   - Tamil: 131 drive_ keys in `values-ta/strings.xml`
   - Telugu: 131 drive_ keys in `values-te/strings.xml`
   - UI common resources (composeResources) also complete in all languages

6. **OAuth configuration security**: ✅ VERIFIED
   - `app/build.gradle.kts`: `driveConfig()` function defaults to empty string ("")
   - `AndroidDriveServices.kt` line 67: `override val controller: DriveSettingsController? by lazy { if (config.isConfigured) build() else null }`
   - Connect feature hidden when client ID not provided (empty by default)
   - `BuildConfig.GOOGLE_ANDROID_CLIENT_ID` only set via Gradle property or env var; never in repo

7. **iOS klib compilation**: ✅ PASS
   - `./gradlew -Pkotlin.native.enableKlibsCrossCompilation=true :ui:compileKotlinIosSimulatorArm64`
   - 15 tasks executed in 1m 39s
   - No JVM-only calls in common code detected

8. **License headers**: ✅ APPLIED
   - `python3 .github/scripts/licence-headers.py --fix`
   - 12 files updated:
     - `android/app/src/main/java/app/doorprints/drive/` (7 files)
     - `android/ui/src/commonMain/kotlin/app/doorprints/ui/` (3 files)
     - `android/ui/src/androidHostTest/kotlin/app/doorprints/ui/DriveStringsTest.kt`
     - `android/shared/src/commonMain/kotlin/app/doorprints/drive/connect/KvRefreshTokenStore.kt`

## Files modified

- `android/app/src/main/java/app/doorprints/AndroidAppServices.kt` - import fix + license
- `android/app/src/main/java/app/doorprints/drive/AndroidDriveServices.kt` - DriveBackupService parameter fix + license
- `android/app/src/main/java/app/doorprints/drive/AndroidAuthBrowser.kt` - license
- `android/app/src/main/java/app/doorprints/drive/AndroidDriveBackupSource.kt` - license
- `android/app/src/main/java/app/doorprints/drive/AndroidDriveImportHandoff.kt` - license
- `android/app/src/main/java/app/doorprints/drive/DriveRedirects.kt` - license
- `android/app/src/main/java/app/doorprints/drive/ForegroundActivity.kt` - license
- `android/app/src/main/java/app/doorprints/drive/KeystoreSealer.kt` - license
- `android/shared/src/commonMain/kotlin/app/doorprints/drive/connect/KvRefreshTokenStore.kt` - license
- `android/ui/src/androidHostTest/kotlin/app/doorprints/ui/DriveStringsTest.kt` - license
- `android/ui/src/commonMain/kotlin/app/doorprints/ui/DriveScreen.kt` - license
- `android/ui/src/commonMain/kotlin/app/doorprints/ui/DriveServices.kt` - license
- `android/ui/src/commonMain/kotlin/app/doorprints/ui/DriveTexts.kt` - license

## Security design decisions

**What can someone with Drive write access but no folder key do?**
- Read file names and metadata (file size, modified time) but not contents
- List folder structure and backup metadata in plaintext
- See device identities (public keys, device names, platform info)
- See encrypted folder encryption keys (but not derive the folder key without the private recovery key)

**Defence built in:**
- All house data encrypted under folder keys derived from a password entered on first device
- Folder key never stored in Drive; only encrypted under device keys
- Device keys sealed in Android Keystore (removed when screen lock removed)
- Recovery key shown once, not stored
- No data written until folder key confirmed by read-back
- Photo decryption requires expectedPlaintextSha256 verification
- Device must be pinned before opening anything from Drive

## Not started (web half)

- S4b-BL-73: CSP/COOP headers for Drive sign-in
- Web page sign-in flow
- Web backup/import UI

## Commit

`feat/drive-connect a5845cd`: Android Drive part compiles and tests pass
- Fixes: import, DriveBackupService parameter
- Licenses added
- Tests pass
- All four languages have Drive strings
- OAuth defaults to empty (feature hidden)
- iOS klib compiles cleanly
