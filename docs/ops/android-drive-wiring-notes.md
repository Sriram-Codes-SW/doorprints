# Android Drive, wiring: notes for the lead and for the owner's phone test

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

## Decisions to confirm

1. **Drive replaces the server** while in use (above).
2. **Automatic backup starts on at the first connect** (docs/15 §1.3, "Defaults"); the controller's own default is off.
3. **The Drive backup has no photos** (they travel as their own files, §1.3, §5.2).
4. **Device check:** the gate asks through the Keystore prover, but the token's proof stays the grant id the controller
   notes describe (S4b-BL-135); `DeviceAuthorizationGate` itself is not in the graph (the `PhoneDeletionAuthorizer` is the
   service's gate). Wiring it needs `DriveDeletionService` and the controller to take one authorizer that carries the
   plan's operation id; not done here.
5. **QR scanning:** docs/15 §9.5 names no camera library, so there is none: `NoQrScanner`, and the screens offer the
   pasted `dp1.` text and the 8-digit code. The code is `PairingCode.pairingCode` over the offer's key and secret with a label:
   a check of the pasted text, not a second factor. The offer carries no device name, so the approver lists a new phone as
   "New phone" (the website's `dp1.` has none either). A camera needs a library (CameraX with ML Kit, or Google's code
   scanner): an owner decision.
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
