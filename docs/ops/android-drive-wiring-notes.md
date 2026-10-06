# Android Drive, wiring: notes for the lead and for the owner's phone test

> Session record, kept as written (2026-10-06). The decisions are in [15](../15-google-drive-backup-and-sharing.md) §1.3 (Drive replaces the server while in use), §7.2 (the object graph) and §10.2; where this note and 15 differ, 15 is right.

S4b-BL-117, -118 and -127 as one working feature. The pieces (controller, token provider, stores, device key and lock,
enrolment, the phones' screens) were built apart; this change puts them together in `:app`
(`android/app/src/main/java/app/doorprints/drive/wiring/`), places the section in Settings and adds the background run.

## The object graph (`DriveAssembly`, built by `DriveServices` from the app)

| Piece | Built from |
|---|---|
| One crypto provider | `DeviceKeyCryptoProvider(platformCryptoProvider())`, given to the backup service, the controller and the sync rigs (a plain provider refuses ECDH with a Keystore key) |
| Device identity | `KeystoreDeviceIdentity(AndroidDeviceKeys.backend, Build.MODEL, folderPinned = FolderPinProbe)`: the pin of the folder in use (stored root id) decides "lost key" versus "make a new key" |
| Gate and authorizer | one `DriveGate(PHONE, ...)` and one `PhoneDeletionAuthorizer` shared by the controller and `DriveDeletionService` |
| Device check | `ProverDeviceAuth` over `OperationProvers.forThisDevice` (Keystore HMAC behind BiometricPrompt from Android 11; the plain prompt below); Android 8 to 9 use the keyguard's confirm screen through the Activity |
| Lock | `DeviceLockDetectors.forContext` with the device-key probe; `DeviceLockActions` + `FileDriveLockStore` (drops the local key only, records the pause) |
| Token | `AndroidDriveTokenProvider(PlayGoogleAuthorizer, ConsentBridge)`; `HttpDriveClient` on the app's one Ktor client (`Api.httpClient()`) |
| Sign-in seam | `TokenDriveSignIn`: closed prompt = CANCELLED, unticked permission = DENIED, no Activity = UNAVAILABLE; refuses without a screen lock |
| Enrolment seam | `BackupDeviceEnrolment` over `DriveBackupService` (approve, approve PSK, join, join PSK, revoke) |
| Sync | `DefaultSyncRigFactory` over `RoomSyncRows` (houses, visits, records, live photos, tombstones included); paused while a deletion is half done or the lock check says so |
| Network | `ConnectivityNetworkState` (validated internet, not metered, not roaming, Data Saver) |
| Backup | `AndroidDriveBackupSource`: the *Full backup* ZIP without photos into a cache file, deleted on close |

Tests build this same graph over fakes (`DriveAssemblyTest`) and run two phones through Drive with real Room databases
(`DriveSyncEndToEndTest`).

## Sync: Drive replaces the server while it is in use

`DriveSyncChoice.backendFor` is the repository's `syncBackendFor` (S4b-BL-70's seam). While Drive is in use (a small
persisted flag, set when the folder is open, cleared by *Disconnect*) the repository uses the Drive backend of the pass
that is running (`DriveSyncRoute`, a thread-local coroutine element set by the controller's sync pass) and **never**
the server: the two would share the rows' clean marks and the pull cursors. Outside a Drive pass nothing syncs (the
repository says "not configured") until the folder is open again. Not in use: the server exactly as before.
`SyncWorker` runs one Drive pass instead of the server sync while Drive is in use.

## Background (`DriveBackupWorker`, `DriveBackgroundRunner`)

Unique periodic work every 6 hours, network connected and battery not low; it exists only while Drive is in use **and**
automatic backup is on (`DriveServices.rescheduleWork`, called at start and when either switch changes). A run goes only
when Drive is connected, automatic backup is on and the screen lock is present, and asks the lock again before each step.
A missing lock stops the run, posts the documented notice once (`drive_lock_notif_title`, `drive_lock_paused`), and
reaches nothing in Drive. Photos follow the controller's gate (Wi-Fi only by default). A restarted process reconnects
quietly first; if Google needs a screen, that is a failure that waits for the person.

## Fixes after the review of PR 142 (as built)

| Review item | What is built |
|---|---|
| 3 hand-back | When Drive stops being in use (*Disconnect*, or the controller dropping to disconnected after the folder went), `DriveAssembly` calls `DriveDeps.handBack` (`CommonRepository.resetForServer`, now public) **before** the flag flips: every row is marked for upload and the pull cursors restart, so rows that went only to Drive reach the server. If it throws, Drive stays in use and the next state change tries again. A fresh process starting at "disconnected" hands nothing back (it has not seen the folder open). |
| 4 lock pause | The pause notice is shown when a run first finds the lock gone. While `lock.json` says paused and the keyguard is still insecure (or the key store is still at fault), `DriveServices.runInBackground` ends the run at once through `DriveLockRules.standingPause`: the Drive graph is not built, the key store is not asked, nothing is posted again. Cleared by the folder opening again with a lock (`lockStore.clear()`), as before. If the lock comes back without re-enrolment the run looks again. |
| 7 key store cost | `KeystoreDeviceIdentity` remembers READY after the first success and asks the key store again only after a use of the key failed (or after `discard`). A key waiting for an unlock, absent or invalidated is never remembered. Consequence: a key the vendor invalidates behind our back is noticed at its next use (the ECDH fails, the cache drops, the next check sees it), not at the next check. The lock check still reads the keyguard first. |
| 8 main thread | `FolderPinProbe` reads the stored root id with `FileDriveStateStore.loadNow()` (one small file read); no `runBlocking`. |
| 11 iOS | `p256FromScalar` wipes the `pub ‖ d` buffer after `importKey`. Compile-checked only (`:shared:compileKotlinIosSimulatorArm64`); not run on a device. |
| 12 key store fault | A key probe that fails **while the keyguard is secure** is not a removed lock: `DeviceLockDetectors.forContext(..., onKeyFault)` calls `DriveLockStore.keyStoreFault`; Settings shows `LockNotice.KEY_LOST` and a paused run's notification says `drive_lock_key_lost` ("the phone's key store lost the key; connect again", English, hi, ta, te under review). The gate's behaviour is unchanged (the dead key is dropped, re-enrolment asked). |
| 15 | `PhoneDeletionAuthorizer` keeps at most `MAX_ISSUED` (4) grants, oldest first, as `DeviceAuthorizationGate.MAX_GRANTS`. |
| 16 | `AndroidDriveBackupSource.swept(...)` (used by `DriveServices.build()`) removes the temp ZIPs of an earlier process once, as the source is made. |
| 17 | `Imports.stage` deletes the decrypted `cache/imports/drive-*.zip` as soon as the Import screen has copied it (`DriveImportStaging.discardIfStaged`: only a `file:` address of that name directly in the staging folder). A cancelled copy keeps it for the six-hour sweep. |
| 18 | The English constants of `DriveLockNotice` are deleted; `strings.xml` is the only source. |
| O4 | `play-services-auth` without `play-services-fido` and `play-services-auth-api-phone` (`releaseRuntimeClasspath`, `gms` lines: 24 before, 16 after). |
| O6 | `DriveWork.reschedule` returns at start when `prefs.json` does not exist (a phone that never used Drive: no WorkManager cancel/enqueue); `FileDrivePrefs` parses its file once while the file's modification time and length are unchanged. |
| O7 | `HouseDao.deleted()` and `VisitDao.deleted()`: the tombstones in one query each (was one query per tombstone). Same rows. |
| O8 | `DriveLockNotice` is gone. `SoftwareOperationProver` keeps its HMAC: the kept `DeviceAuthorizationGate` (S4b-BL-135) requires a 64-hex proof bound to a key, and `ProverDeviceAuth` discarding it is the "as built" state of §5 of the review; remove it together with S4b-BL-135. |

## Decisions to confirm

1. **Drive replaces the server** while in use (above).
2. **Automatic backup starts on at the first connect** (docs/15 §1.3, "Defaults"); the controller's own default is off.
3. **The Drive backup has no photos** (they travel as their own files, §1.3, §5.2).
4. **Device check:** the gate asks through the Keystore prover, but the token's proof stays the grant id the controller
   notes describe (S4b-BL-135); `DeviceAuthorizationGate` itself is not in the graph (the `PhoneDeletionAuthorizer` is the
   service's gate). Wiring it needs `DriveDeletionService` and the controller to take one authorizer that carries the
   plan's operation id; not done here.
5. **QR scanning (owner decision 2026-10-06):** Google's code scanner, `com.google.android.gms:play-services-code-scanner`
   16.1.0 (the one new library). `AndroidQrScanner` (logic, JVM-tested) sits over the thin `QrScanBackend`; `GmsQrScanBackend`
   is the only code that calls Google (QR codes only, auto zoom). Google's own screen scans, so the app adds **no CAMERA
   permission**. The scanned text goes back to the screens exactly as pasted text does (the codec's 4096-character limit, the
   `dp1.` check and the *not a Doorprints code* error apply; a longer text is replaced by a non-code word before parsing). Both
   screens that take text (the connected phone reading the new device's QR; the new device reading the approver's reply)
   get the same Scan button, shown only when `isAvailable`. Without Google Play services (the browser-fallback phones)
   `isAvailable` is false, the button is hidden and the pasted text and the 8-digit code remain. The text is never logged and
   `QrScanBackendResult.Text.toString` is redacted.
   **Module download (for the owner):** the scanner module is not in the APK. On first use Play services downloads it
   (a few MB, over the network, once). `moduleReady()` checks `ModuleInstall`; when it is missing it asks for the install and
   the screen shows the existing "no camera" line for that tap; the next tap works once the download has finished.
   The library also pulls Google's telemetry helper libraries (datatransport, firebase-encoders, ML Kit common); they ship
   in the APK and Google may report scanner usage metrics through Play services. The code paste path needs none of this.
   The offer carries no device name, so the approver lists a new phone as "New phone" (the website's `dp1.` has none either).
   The code is `PairingCode.pairingCode` over the offer's key and secret with a label: a check of the pasted text, not a
   second factor.
6. **Replies** are the website's JSON (`wrapEnc`, `wrapCt`, `epoch`), so a phone and the website enrol each other.
7. **Import a backup** from Drive writes the decrypted ZIP to `cache/imports/drive-*.zip` and opens the existing Import
   screen with that file (it copies it again; both are swept after six hours).
8. `PairingFlow` (the commit-and-reveal 8-digit path) is not used: the screens ask only for the QR/PSK path.

## Edits to other pieces (all small)

- `AndroidDriveTokenProvider.forget()` (memory only), for *Disconnect this device*.
- `SettingsServices.DriveSection()` with an empty default, one call in `SettingsScreen` before the server section; `:ui` otherwise untouched, nothing under `iosMain`.
- `Api.httpClient()`, `Notifications.DRIVE_LOCK_ID`, `SyncWorker` (Drive pass), `AppContainer` (database, route, flag, drive), `MainActivity` (registers the foreground Activity and the two result launchers).
- `app/build.gradle.kts`: `InMemoryFakeDrive.kt` and `FakeDriveFaults.kt` of `:shared`'s commonTest are compiled into `:app`'s unit tests (plus a 14-line copy of the internal 401 helper, `drive/FakeDriveSupport.kt`).

## What needs a real phone (not verified here)

Google sign-in and consent (Play services), the Keystore keys (API 31 ECDH, API 26 to 30 wrapped scalar), BiometricPrompt
with the CryptoObject, the keyguard confirm screen on Android 8 to 9, the lock removal invalidating the key, WorkManager
running the worker, the notification, and the Import hand-off with a real file. No instrumented test was written (the
emulator has no Google account and a lock state the test cannot rely on).

Manual steps for the owner (needs the Google Cloud setup of docs/15 §2.4 done for the debug package and its SHA-1):

1. Phone with a PIN set. Settings > Back up to Google Drive > *Connect*: Google's consent shows once; allow Drive.
2. The recovery key shows once: copy it, tick the box, *Next*. Check *Back up now*, then the list shows one backup.
3. Add a house, tap *Sync now*: status says up to date. On a second phone or the website: join (QR text or recovery key), sync, see the house.
4. Revoke the second device; its sync must refuse. Note the new recovery key.
5. *Import a backup* on a row: the Import screen opens with the file and its preview.
6. Remove the screen lock: Settings shows the paused words; wait for or force the worker (`adb shell cmd jobscheduler run -f app.doorprints <id>`): a notification appears and nothing changes in Drive. Set a lock again: the phone asks to connect and enrol again (the key was invalidated).
7. Turn automatic backup off: the periodic work is cancelled (`adb shell dumpsys jobscheduler | grep drive`).
8. Mobile data with *Upload photos only on Wi-Fi* on: photos wait; *Upload photos now over mobile data* sends them.
9. *Disconnect this device*: the server sync (if configured) takes over again.

## Tests and mutation gates

`app/src/test/java/app/doorprints/drive/wiring/`: decisions, runner, worker result, adapters (with the real
`DriveBackupService` on the in-memory Drive: approve then join across two services, the PSK path, revoke gives a new
recovery key and the old one stops verifying), consent bridge and launcher, codec, files, the graph (`DriveAssemblyTest`),
Room rows, two phones syncing end to end, and the lock notice and strings in four languages. Each mutation below was
applied by hand to the production file, the named test class run, a named test failed, and the file restored.

| Mutation | Test that failed |
|---|---|
| plain crypto provider instead of the device-key one | aSecondPhoneEnrolsThroughTheKeystoreLikeKeyOnBothSides (and 4 more) |
| folderPinned always false | aLostKeyWithAPinnedFolderIsNeverReplaced |
| deletion service gets its own authorizer | aDeleteGrantMadeByTheControllerIsTheOneTheServiceRedeems |
| sync never paused by a deletion or the lock | aHalfFinishedDeletionPausesTheSync, aRemovedLockPausesTheSync... |
| photos always allowed in the sync pass | theSyncPassGetsTheBackendAndThePhotoGate |
| automatic backup default only when already chosen | automaticBackupStartsOnAtTheFirstConnect, aChoiceTheyAlreadyMadeIsKept |
| pause never cleared | aPausedPhoneIsClearedOnceTheFolderIsOpenAgainWithALock |
| sign-in seam dropped | theControllerSignsInThroughTheSignInSeam |
| engagement forgotten by a fresh process | aFreshProcessStartingDisconnectedKeepsWhatWasRemembered |
| server used while Drive is in use | withDriveInUseButNotReconnectedNothingSyncsAndTheServerIsNotUsed |
| auto-backup off ignored | autoBackupOffSkips |
| lock asked before the other conditions | theLockIsAskedOnlyWhenTheOtherConditionsHold |
| engaged and unlocked shows "needs a lock" | theNoticeFollowsTheLockAndWhetherDriveIsInUse |
| lock not asked again before the backup | theLockIsAskedBeforeEveryStep |
| no notice on a lock pause | aRemovedLockStopsBeforeAnythingAndSaysTheDocumentedNoticeOnce |
| no reconnect after a restart | aRestartedProcessReconnectsFirst |
| closed prompt mapped to denied | aClosedPromptIsNotADenial |
| lock not required to connect | withoutAScreenLockNothingIsAskedOfGoogle |
| revoke drops the new recovery key | aRevokeGivesANewRecoveryKeyAndTheRevokedDeviceLosesTheFolder |
| PSK approve uses the base wrap | theQrPskPathWorksAcrossTwoServices |
| level 1 passes the device check | levelOneAsksNothingAndNeverPasses |
| lockout read as cancelled | theDeviceCheckResultsMapOneToOne |
| captive portal counts as online | aNetworkWithoutValidatedInternetIsNotOnline |
| Data Saver ignored | mobileDataIsMeteredAndRoamingAndDataSaverAreSeen |
| tombstoned houses left out of the rows | liveAndDeletedHousesAreBothThereWithTheirStamps |
| queued photo deletions included | aPhotoQueuedForDeletionIsLeftOutOfTheRowsButAnswersByIdAsDeleted |
| reply with epoch 0 accepted | aBadReplyIsRefused |
| offer code ignores key and secret | theCodeDependsOnBothTheKeyAndTheSecret (added after this mutation survived) |
| unreadable state treated as "no pin" | aStateThatCannotBeReadIsTreatedAsPinnedNotAsFree |
| a second screen does not close the first | aSecondScreenClosesTheFirstAsCancelled |
| no Activity answered as cancelled | withNoActivityTheConsentIsUnavailableNotRefused |
| worker retries a failure that needs the person | aFailureThatNeedsThePersonDoesNotRetry |

Fixes after the review of PR 142 (each mutation applied by hand, the named test failed, file restored):

| Mutation | Test that failed |
|---|---|
| hand-back call dropped | aDriveOnlyHouseIsNotLostWhenTheFolderIsGoneAndThePersonDisconnects |
| failed hand-back still disengages | aFailedHandBackKeepsDriveInUseAndTriesAgainAtTheNextChange |
| fresh process forgets engagement (seenConnected guard dropped) | aFreshProcessStartingDisconnectedKeepsWhatWasRemembered |
| READY not remembered | a key waiting for an unlock is probed every time, never remembered as ready |
| failed use does not drop the remembered READY | aKeyTheKeyStoreLostWithTheLockStillThereIsItsOwnPause |
| any status remembered (waiting for unlock too) | a key waiting for an unlock is used and never recreated |
| standing pause check removed from the runner | aPauseThatStandsBuildsNothingAndSaysNothingAgain |
| standing pause ignores a gone lock | aStandingPauseIsLeftAloneWhileTheLockIsStillGoneOrTheKeyStoreStillAtFault |
| key fault never reported | a key the key store lost while the lock is there is a key fault, not only a removed lock |
| every removal reported as key fault | a removed keyguard is never a key fault, even with the key gone with it |
| notice ignores the key store fault | aKeyTheKeyStoreLostWithTheLockThereHasItsOwnNotice |
| key-lost screen shows the removed-lock words | tamilKeyLost |
| notification always says removed lock | aKeyTheKeyStoreLostWithTheLockThereHasItsOwnNotice |
| sweep not run when the source is made | theBackupSourceSweepsEarlierLeftoversWhenItIsMade |
| decrypted import file kept after the copy | theImportScreensCopyIsMadeAndThenTheDecryptedFileIsGone |
| tombstones read one by one again | tombstonesAreReadInOneQueryEachNotOnePerRow |
| prefs memo ignored | theWorkFollowsInUseAndAutomaticBackupOnce |
| start-up schedules even when Drive was never used | aPhoneThatNeverUsedDriveSchedulesNothingAtStart |
| pin probe blocks on a coroutine again | theProbeIsNeverBuiltOnRunBlockingBecauseItRunsOnTheMainThread |
| deletion grants never evicted | onlyTheNewestGrantsAreKeptSoTheMapDoesNotGrowForEver |
| staging discard ignores the folder (canonical parent check dropped) | anythingElseIsLeftAlone |

## Final wiring (W2): browser sign-in, sensitive clipboard, folder gone

### 1. Browser PKCE fallback (`drive/auth/browser/DriveAuthorizers.kt`, `DriveServices`, `MainActivity`)

- `DriveServices.browserRedirect` is the one `BrowserRedirect`. `MainActivity` hands every intent to `DriveAuthorizers.deliver(redirect, intent)`
  from `onCreate` (a real start) and `onNewIntent`; when it returns true nothing else is done with the intent (and its data is emptied, so a
  recreation cannot replay it). A stale or foreign `app.doorprints:/oauth2redirect` returns false and falls through to the deep-link code, which ignores it.
- `DriveAuthorizers.assemble` builds `BrowserGoogleAuthorizer` (config from `BuildConfig.GOOGLE_ANDROID_CLIENT_ID`, `ActivityBrowserLauncher`,
  `HttpTokenEndpoint`, `SealedRefreshTokenStore(KeystoreSecretWrapper("doorprints_drive_refresh_wrap"), FileBlobStore(noBackupFilesDir/drive/refresh-token.bin))`)
  and picks with `chooseGoogleAuthorizer`. `PlayGoogleAuthorizer` is wrapped in `LazyGoogleAuthorizer`: it is constructed on first use only, so a phone
  without Play services never constructs it (`Identity.getAuthorizationClient` is not called there).
- The consent resolver given to the token provider is `BrowserAwareResolver(ConsentBridge-or-null)` **only while an Activity is on screen**; a worker gets
  null, so the provider says `CONSENT_REQUIRED` and no browser opens by itself.
- `DriveDeps.configured` is "Play services is there, or a client id was built in": a phone with neither shows "Drive is not available".
- `FileBlobStore` does not make its folder; `DriveServices.build()` now makes `noBackupFilesDir/drive` first (otherwise the first refresh token write
  failed silently and the next sign-in asked again).
- Not wired: the optional Cancel -> `BrowserGoogleAuthorizer.cancel()` (the card has no Cancel while the browser is open; the 5-minute wait ends it as CANCELLED).

### 2. Sensitive clipboard (`wiring/AndroidClipboardSeam.kt`)

`AndroidClipboardSeam(context, sdk)`: `ClipData.newPlainText` plus, from API 33, a `PersistableBundle` on the description with
`ClipDescription.EXTRA_IS_SENSITIVE = true`; `ClipboardManager.setPrimaryClip`. `DriveSection` passes it as `DriveHost(clipboard = ...)`.
The level is a constructor seam because Robolectric has only the Android 15 image offline (the gradle cache holds no 13 or 11 image and a new
dependency is out of scope): the tests pass 33 and 32 and check the flag present and absent.

### 3. Folder gone (`DriveConnectController.folderGone`, `DriveEngagement`, `DriveSection`)

- The controller is the only one that sees `DriveConnection.FolderGone` (it comes from `connect()`, which the card, the background runner's reconnect and
  *Start again* all use), so the flag lives there: `DriveConnectController.folderGone: StateFlow<Boolean>` (a small edit in `:shared`). It is set **before**
  the state drops to DISCONNECTED and cleared **after** the state moves on (*Start again*, any other connection result, *Disconnect*, *Delete everything*).
- `DriveEngagement.next(now, state, folderGone)`: DISCONNECTED/UNAVAILABLE with the flag set keeps `engaged` TRUE (the server stays off for this phone);
  without the flag it is the person's Disconnect and ends the use as before. A phone that has Drive on and finds the folder gone remembers that it has
  seen the folder, so the Disconnect that follows in a fresh process is honoured too.
- `DriveAssembly.watchEngagement` collects `combine(state, folderGone)`: a *Disconnect* pressed on the card while the folder is gone changes only the
  flag (the state is already DISCONNECTED), and that is what ends the use and runs the hand-back to the server.
- `DriveGraph.folderGone` is the controller's flow; `DriveSection` gives it to `ControllerDriveActions(controller, folderGone = ...)`.
  (The brief said a `MutableStateFlow` in `DriveServices`; the flag has to be set inside the controller to be ordered against the state, so the graph exposes it.)
- Limit: a sync or backup on an already open session that meets a deleted folder reports its own error (not FolderGone); the next connect finds it.

### Owner: supplying the Google client id locally

Add one line to **`~/.gradle/gradle.properties`** (never to the repository):

```
GOOGLE_ANDROID_CLIENT_ID=1234567890-abcdefgh.apps.googleusercontent.com
```

(or `-PGOOGLE_ANDROID_CLIENT_ID=...` on the command line, or the same environment variable). Rebuild (`./gradlew :app:assembleDebug`). Empty or missing means the
browser sign-in is unavailable and opens nothing; an id with characters outside `A-Z a-z 0-9 . _ -` is treated as empty. The id is the one of the **Android**
OAuth client of docs/15 §2.4 with "Enable custom URI scheme" on (the debug and release builds have their own client, so use the one for the build you install).

### Mutations of W2 (each applied by hand to the production file, the named test failed, file restored)

| Mutation | Test that failed |
|---|---|
| Play never chosen | playServicesIsTheSignInWhenItIsAvailableAndTheBrowserStaysClosed |
| Play authorizer built eagerly | theBrowserIsTheSignInWithoutPlayServicesAndPlayIsNeverBuilt |
| resolver given with no Activity | withNoActivityThereIsNoResolverSoNoBrowserOpensByItself |
| card available without a client id | theDriveCardIsAvailableOnlyWithPlayServicesOrAClientId |
| redirect intent not emptied | theRedirectIntentIsTakenOnceAndEmptied |
| blank client id accepted | aBlankClientIdIsUnavailableAndOpensNothing |
| clip flag always set | beforeAndroid13ItIsAPlainCopyWithoutTheFlag |
| clip flag never set / set to false | onAndroid13TheClipIsMarkedSensitive |
| folder gone disengages | aFolderFoundGoneKeepsDriveInUseAndShowsTheCardState |
| fresh process forgets the folder was gone | aFreshProcessThatFindsTheFolderGoneStillHonoursTheDisconnectAfterIt |
| assembly ignores the flag | disconnectingAfterAFreshProcessFoundTheFolderGoneIsStillTheirs |
| Disconnect does not clear the flag | disconnectingAfterTheFolderWentHandsTheRowsBackAndClearsTheCard |
| Start again does not clear the flag | startAgainClearsTheCardAndKeepsDriveInUse |
| flag set after the state drops | aFolderFoundGoneKeepsDriveInUseAndShowsTheCardState |
