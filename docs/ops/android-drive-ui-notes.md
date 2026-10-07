# The phones' Google Drive screens: notes for the lead and for translation review

> Session record, kept as written (2026-10-06). The decisions are in [15](../15-google-drive-backup-and-sharing.md) §5.7 (the screens), §3.1, §3.4 and §9.5 i; where this note and 15 differ, 15 is right.

S4b-BL-116 to -119 and -126, as the person sees them on a phone. Code: `android/ui/src/commonMain/kotlin/app/doorprints/ui/drive/`
(package `app.doorprints.ui.drive`, common code for Android and iOS). Flows: `docs/15` sections 2 to 5, the device check in
section 10. The reference is the website's `web/src/app/pages/data/drive/*`.

## Files

| File | What it is |
|---|---|
| `DriveScreenState.kt` | Pure rules: which card, which buttons are on, the tick box and the 5 second countdown, the sync line, photos, devices, sizes, `driveResName`. |
| `DriveHolder.kt` | The state holder ("view model" without Android): wraps `DriveActions`, one `DriveUiState`. The recovery keys are `ShownKey`s that print as `<shown once>`. |
| `DriveActions.kt` | `DriveActions` (the part of `DriveConnectController` the screens use) and `ControllerDriveActions` (the adapter); the seams `QrScanner` and `EnrolmentCodec`. |
| `DriveViewModel.kt` | `DriveViewModel` (holder on `viewModelScope`, survives rotation) and the composable `DriveSettings(viewModel, scanner, host)`. |
| `DriveScreens.kt` | `DriveSettingsSection(holder, scanner, host)` and every card; `QrCodeView` draws a QR with `encodeQr` on a `Canvas`. |
| `DriveStringTable.kt` | Generated: dictionary key to `Res.string.*` (Compose resources have no lookup by name; the controller's reasons arrive as keys). `DriveStringsTest` keeps it equal to the resource files. |

## Wiring (the lead)

```kotlin
val vm = viewModel { DriveViewModel(ControllerDriveActions(controller), codec = enrolmentCodec, deviceName = { androidDeviceName(...) }) }
DriveSettings(vm, scanner = cameraScanner /* or NoQrScanner */, host = DriveHost(onImportBackup = { backup -> ... }, onSaveCopy = { ... }))
```

- Put `DriveSettings` in Settings (under *Your data*, before the server section). It is a Column; Settings scrolls.
- `DriveHost.onImportBackup(backup)`: call `controller.importFromDrive(backup.id, staging)` and open the existing import preview with the
  staging file; set `importBusy` while it works and `importError` (a `DriveReason`) when it refuses.
- `DriveHost.onSaveCopy`: navigate to the Export screen (*Save a copy*).
- `QrScanner`: a camera seam (CameraX and ML Kit on Android, AVFoundation on iOS). `scan()` returns the text of the first code read
  (`QrScan.Scanned`), `Cancelled`, or `NoCamera`. No camera code is in `:ui`; with `NoQrScanner` the screens offer paste only. (Since S4b-BL-136 Android passes `AndroidQrScanner`, Google's code scanner with no CAMERA permission, and the iPhone has `iosQrScanner()` in `:ui` iosMain, wired into the iPhone's Drive screen since `feat/ios-drive` (docs/15 §9.11): [android-drive-wiring-notes.md](android-drive-wiring-notes.md) item 5, [ios-qr-scanner-notes.md](ios-qr-scanner-notes.md).)
- `EnrolmentCodec`: the `dp1.` offer and reply text, built from the enrolment branch (`feat/android-drive-e-enrolment`): `newOffer`
  (QR text, the 8-digit code, the PSK), `parseOffer`, `encodeReply`, `parseReply`. Without a codec the enrolment buttons do nothing.
  The 8-digit code must be the same on both phones (it is `PairingCode.pairingCode` there).
- The device check prompts are strings the screen reads (`driveDelete.deviceCheckPrompt`, `driveDevices.promptRevoke`,
  `driveDevices.promptApprove`, `driveDevices.promptDisconnectAll`) and passes to the controller as `promptReason`.

## What the person sees, by state

- **Disconnected**: the intro and *Connect to Google Drive*. A failed or closed sign-in shows its reason and *Try again* (not for "this
  phone cannot encrypt"); No folder yet: the holder makes one at once and shows the recovery key.
- **Folder deleted** (`DriveCard.FOLDER_GONE`, docs/15 section 3.4): "Your Doorprints data in Google Drive was deleted. Back up this device to Drive again?" with *Start again* (makes a new folder, shows its recovery key once) and *Disconnect*. Nothing is ever created by itself, and *Connect* is not offered (it would only ask again). The app keeps Drive engaged until the person answers: `DriveActions.folderGone` (a `StateFlow<Boolean>` the wiring sets when sync or backup finds the folder gone; default never) shows the same card when Settings opens.
- **Recovery key (once)**: the key in monospace, read letter by letter, *Copy recovery key*, the warning, the tick *I have saved my
  recovery key*, *Next* (only ticked) and *Skip*. The key lives in `DriveUiState.connectKey` only until the screen is left.
- **Join / enrol**: a recovery-key field (the only one on a phone), *Join this folder*, then *Enrol this phone* (*Show a QR code*, the
  8-digit code, scan or paste the reply). A revoked phone gets the revoked sentence and the recovery-key field only.
- **Connected**: *Backups* (last backup, *Back up now*, the shrink question, the list with *Import a backup* and *Delete this backup* per row (the delete's plan, tick box, countdown and device check show under the list; the last complete backup is level 2 and shows the device-check wording), *Automatic backup*),
  *Sync* (status line, *Sync now*, the shrink guard, *Upload photos only on Wi-Fi* with the one-off *Upload photos now over mobile
  data*), *Devices* (account, list with *Revoke*, the new recovery key once, *Disconnect on all devices*), *Enrol another device* (the approver names the new device, default "New device", and picks Phone or Computer; both go to the keys file),
  *Delete data in Google Drive* (older, all, everything; plan counts and bytes; tick box; 5 second countdown for everything; device
  check; *Try again* only after a partial run), *Disconnect this device*.
- Never on a phone: a passkey, a recovery-key field in any delete, revoke or disconnect step (docs/15 section 10.4a).

## Strings

Every key of `docs/ops/android-drive-controller-notes.md` is in `values`, `values-hi`, `values-ta` and `values-te` as
`drive_*` resources (`driveDelete.reason.AUTH_FAILED` is `drive_delete_reason_auth_failed`). Sentences the website has use its exact
wording in each language (placeholders `{n}` become `%1$d`, `%1$s`); the sections of the resource files are marked *under review*
for hi, ta and te. Where the website's sentence says "browser" or "website", a phone sentence was written instead (listed below).
A few keys the controller notes list but the website words differently are aliases of the website's key (see the `web` lines).

The two blocks below are read by `DriveStringsTest`: it checks that a `web` key reads in the resource files exactly as in the website's
dictionaries, in all four languages, and that every `phone-only` key is a key of the table.

<!-- keys:start -->
```text
web driveConnect.heading driveConnect.heading
web driveConnect.intro driveConnect.intro
web driveConnect.connect driveConnect.connect
web driveConnect.connecting driveConnect.connecting
web driveConnect.connectionInProgress driveConnect.connectionInProgress
web driveConnect.firstConnect driveConnect.firstConnect
web driveConnect.recoveryKeyNote driveConnect.recoveryKeyNote
web driveConnect.recoveryKeyCopy driveConnect.recoveryKeyCopy
web driveConnect.recoveryKeyCopied driveConnect.recoveryKeyCopied
web driveConnect.copyFailed driveConnect.copyFailed
web driveConnect.confirmSavedRecoveryKey driveConnect.confirmSavedRecoveryKey
web driveConnect.recoveryKeyWarning driveConnect.recoveryKeyWarning
web driveConnect.needsEnrolmentHeading driveConnect.needsEnrolmentHeading
web driveConnect.needsEnrolmentMessage driveConnect.needsEnrolmentMessage
web driveConnect.ready driveConnect.ready
web driveConnect.lastBackup driveConnect.lastBackup
web driveConnect.never driveConnect.never
web driveConnect.backUpNow driveConnect.backUpNow
web driveConnect.backupsList driveConnect.backupsList
web driveConnect.noBackups driveConnect.noBackups
web driveConnect.importFromDrive driveConnect.importFromDrive
web driveConnect.automaticBackup driveConnect.automaticBackup
web driveConnect.uploadPhotos driveConnect.uploadPhotos
web driveConnect.uploadPhotosNow driveConnect.uploadPhotosNow
web driveConnect.disconnect driveConnect.disconnect
web driveConnect.disconnecting driveConnect.disconnecting
web driveConnect.deleting driveConnect.deleting
web driveConnect.notConfigured driveConnect.notConfigured
web driveConnect.folderGone driveConnect.folderGone
web driveConnect.failed driveConnect.failed
web driveJoin.heading driveJoin.heading
web driveJoin.description driveJoin.description
web driveJoin.label driveJoin.label
web driveJoin.helpText driveJoin.helpText
web driveJoin.joinButton driveJoin.joinButton
web driveJoin.errorEmpty driveJoin.errorEmpty
web driveJoin.errorInvalidFormat driveJoin.errorInvalidFormat
web driveJoin.errorWrongKey driveJoin.errorWrongKey
web driveJoin.lostKeyMessage driveJoin.lostKeyMessage
web driveJoin.disconnect driveJoin.disconnect
web driveJoin.disconnectAriaLabel driveJoin.disconnectAriaLabel
web driveBackups.backUpNow driveBackups.backUpNow
web driveBackups.housesBackedUp driveBackups.housesBackedUp
web driveBackups.backups driveBackups.backups
web driveBackups.date driveBackups.date
web driveBackups.time driveBackups.time
web driveBackups.houses driveBackups.houses
web driveBackups.size driveBackups.size
web driveBackups.import driveBackups.import
web driveBackups.importBackup driveBackups.importBackup
web driveBackups.emptyState driveBackups.emptyState
web driveBackups.loadingState driveBackups.loadingState
web driveBackups.errorState driveBackups.errorState
web driveBackups.retry driveBackups.retry
web driveBackups.autoBackup driveBackups.autoBackup
web driveBackups.keepOlderBackupsNote driveBackups.keepOlderBackupsNote
web driveBackups.missingNewer driveBackups.missingNewer
web driveBackups.sizeUnknown driveBackups.sizeUnknown
web driveBackups.error.notConnected driveBackups.error.notConnected
web driveBackups.error.backupNotFound driveBackups.error.backupNotFound
web driveBackups.error.retrieveFailed driveBackups.error.retrieveFailed
web driveProblem.UNAUTHORIZED driveProblem.UNAUTHORIZED
web driveProblem.QUOTA_EXCEEDED driveProblem.QUOTA_EXCEEDED
web driveProblem.RATE_LIMITED driveProblem.RATE_LIMITED
web driveProblem.SERVER driveProblem.SERVER
web driveProblem.DRIVE driveProblem.DRIVE
web driveProblem.CORRUPT driveProblem.CORRUPT
web driveProblem.KEYS_ROLLED_BACK driveProblem.KEYS_ROLLED_BACK
web driveProblem.KEYS_UNTRUSTED driveProblem.KEYS_UNTRUSTED
web driveProblem.KEYS_UNREADABLE driveProblem.KEYS_UNREADABLE
web driveProblem.NO_RECOVERY_KEY driveProblem.NO_RECOVERY_KEY
web driveProblem.DEVICE_REVOKED driveProblem.DEVICE_REVOKED
web driveProblem.CONTROL_ROLLED_BACK driveProblem.CONTROL_ROLLED_BACK
web driveProblem.CONTROL_INVALID driveProblem.CONTROL_INVALID
web driveProblem.FOLDER_WITHOUT_KEYS driveProblem.FOLDER_WITHOUT_KEYS
web driveProblem.FOLDER_EXISTS driveProblem.FOLDER_EXISTS
web driveProblem.BACKUP_REFUSED driveProblem.BACKUP_REFUSED
web driveProblem.SIGNIN_CLOSED driveProblem.SIGNIN_CLOSED
web driveProblem.SIGNIN_DENIED driveProblem.SIGNIN_DENIED
web driveSync.heading driveSync.heading
web driveSync.syncNow driveSync.syncNow
web driveSync.skippedFiles driveSync.skippedFiles
web driveSync.shrinkConfirm driveSync.shrinkConfirm
web driveSync.shrinkConfirmDetails driveSync.shrinkConfirmDetails
web driveSync.applyShrink driveSync.applyShrink
web driveSync.notNow driveSync.notNow
web driveSync.uploadNow driveSync.uploadNow
web driveSync.photoError driveSync.photoError
web driveDelete.heading driveDelete.heading
web driveDelete.olderBackups driveDelete.olderBackups
web driveDelete.allBackups driveDelete.allBackups
web driveDelete.everything driveDelete.everything
web driveDelete.oneBackup driveDelete.oneBackup
web driveDelete.planHeading driveDelete.planHeading
web driveDelete.planWarning driveDelete.planWarning
web driveDelete.proceed driveDelete.proceed
web driveDelete.confirmHeading driveDelete.confirmHeading
web driveDelete.confirmWarning driveDelete.confirmWarning
web driveDelete.confirmCheckbox driveDelete.confirmCheckbox
web driveDelete.tickRequired driveDelete.tickRequired
web driveDelete.countdown driveDelete.countdown
web driveDelete.deleting driveDelete.deleting
web driveDelete.done driveDelete.done
web driveDelete.saveCopyFirst driveDelete.saveCopyFirst
web driveDelete.deleteForGood driveDelete.deleteForGood
web driveDelete.tryAgain driveDelete.tryAgain
web driveDelete.left driveDelete.left
web driveDelete.nothingDeleted driveDelete.nothingDeleted
web driveEnrol.badMessage driveEnrol.badMessage
web driveEnrol.codeLabel driveEnrol.codeLabel
web driveEnrol.numbersMatch driveEnrol.numbersMatch
web driveEnrol.copied driveEnrol.copied
web driveEnrol.copyFailed driveEnrol.copyFailed
web driveEnrol.joinNow driveEnrol.joinNow
web driveEnrol.expired driveEnrol.expired
web driveEnrol.mismatch driveEnrol.mismatch
web driveDevices.heading driveDevices.heading
web driveDevices.revoke driveDevices.revoke
web driveDevices.revokeNote driveDevices.revokeNote
web driveDevices.disconnectAll driveDevices.disconnectAll
web driveDevices.threeDisconnect driveDevices.threeDisconnect
web driveDevices.threeRemove driveDevices.threeRemove
web driveDevices.threeDelete driveDevices.threeDelete
web driveDevices.email driveDevices.email
web driveConnect.unavailable driveConnect.notConfigured
web driveSync.synced driveSync.statusUpToDate
web driveSync.syncedAt driveSync.statusUpToDateAt
web driveSync.waiting driveSync.statusWaitingWifi
web driveSync.syncing driveSync.statusSyncing
web driveSync.error driveSync.statusError
web drivePhotos.waitingForWifi driveSync.statusWaitingWifi
web driveBackups.shrinkBody driveBackups.shrinkConfirmQuestion
web driveBackups.shrinkConfirm driveBackups.confirmShrink
web driveBackups.keepOlder driveBackups.keepOlderBackups
web driveDevices.newRecoveryKey driveDevices.newRecovery
web driveDevices.codesMatch driveEnrol.numbersMatch
web driveEnrol.copyCode driveEnrol.copyLink
web driveDelete.reason.DRIVE_ERROR driveProblem.SERVER
web driveConnect.accountEmail driveDevices.email
web drive.common.next common.next
web drive.common.skip common.skip
web drive.common.retry common.retry
web drive.common.close common.close
web drive.common.loading common.loading
phone-only driveConnect.noBackupSource
phone-only driveConnect.folderGoneAsk
phone-only driveConnect.startAgain
phone-only driveEnrol.deviceName
phone-only driveEnrol.deviceKind
phone-only driveEnrol.kindPhone
phone-only driveEnrol.kindComputer
phone-only driveProblem.OFFLINE
phone-only driveProblem.SOURCE_FAILED
phone-only driveProblem.CODE
phone-only driveProblem.CONNECT_FAILED
phone-only driveProblem.CRYPTO_UNAVAILABLE
phone-only driveProblem.SIGNIN_UNAVAILABLE
phone-only driveBackups.shrinkHeading
phone-only driveDevices.thisDevice
phone-only driveDevices.approve
phone-only driveDevices.revokeConfirm
phone-only driveDevices.scanQr
phone-only driveDevices.showQr
phone-only driveDevices.codeInstead
phone-only driveDevices.noLock
phone-only driveDevices.deviceCheckNote
phone-only driveDevices.promptRevoke
phone-only driveDevices.promptApprove
phone-only driveDevices.promptDisconnectAll
phone-only driveEnrol.headingJoin
phone-only driveEnrol.headingApprove
phone-only driveEnrol.newHelp
phone-only driveEnrol.approveHelp
phone-only driveEnrol.confirmMatch
phone-only driveEnrol.pasteOffer
phone-only driveEnrol.pasteReply
phone-only driveEnrol.replyLabel
phone-only driveEnrol.approve
phone-only driveEnrol.scan
phone-only driveEnrol.cameraMissing
phone-only driveEnrol.cameraDenied
phone-only driveEnrol.qrDescription
phone-only driveEnrol.done
phone-only driveSync.offline
phone-only driveSync.paused
phone-only driveSync.notRun
phone-only driveSync.needsConfirmation
phone-only drivePhotos.uploading
phone-only drivePhotos.pausedOffline
phone-only drivePhotos.done
phone-only driveDelete.deviceCheckHeading
phone-only driveDelete.deviceCheckPrompt
phone-only driveDelete.deviceCheckNote
phone-only driveDelete.plan.backup
phone-only driveDelete.plan.sync
phone-only driveDelete.plan.photo
phone-only driveDelete.plan.shared
phone-only driveDelete.plan.other
phone-only driveDelete.plan.total
phone-only driveDelete.plan.foreign
phone-only driveDelete.reason.OFFLINE
phone-only driveDelete.reason.NOT_AUTHORIZED
phone-only driveDelete.reason.AUTHORIZATION_TOO_WEAK
phone-only driveDelete.reason.AUTHORIZATION_STALE
phone-only driveDelete.reason.AUTHORIZATION_OTHER_OPERATION
phone-only driveDelete.reason.STALE_PLAN
phone-only driveDelete.reason.ROOT_NOT_FOUND
phone-only driveDelete.reason.NOT_A_BACKUP
phone-only driveDelete.reason.NOTHING_TO_DELETE
phone-only driveDelete.reason.OTHER_DELETION_PENDING
phone-only driveDelete.reason.NOTHING_PENDING
phone-only driveDelete.reason.NO_DEVICE_LOCK
phone-only driveDelete.reason.AUTH_CANCELLED
phone-only driveDelete.reason.AUTH_TIMED_OUT
phone-only driveDelete.reason.AUTH_FAILED
phone-only driveDelete.reason.AUTH_LOCK_NOT_SET
phone-only driveDelete.reason.AUTH_NOT_AVAILABLE
phone-only driveDelete.reason.AUTH_LOCKED_OUT
phone-only driveDelete.reason.AUTH_PAUSED_NO_LOCK
phone-only driveDelete.reason.AUTH_PAUSED_UNKNOWN
```
<!-- keys:end -->

## Phone-only sentences to review (hi, ta, te are drafts)

The 67 `phone-only` keys above are written for the phones (the website says "browser", or has no such sentence): the device check
wording (`driveDelete.deviceCheck*`, `driveDelete.reason.*` for the device check and the deletion refusals, `driveDevices.prompt*`),
"no screen lock pauses Drive" (`driveDevices.noLock`, `driveSync.paused`, `driveDelete.reason.NO_DEVICE_LOCK`,
`AUTH_PAUSED_NO_LOCK`, `AUTH_PAUSED_UNKNOWN`), the plan lines (`driveDelete.plan.*`), the QR and 8-digit enrolment steps
(`driveEnrol.*`, `driveDevices.scanQr/showQr/codeInstead`), the phone versions of the problems that name a browser
(`driveProblem.OFFLINE/SOURCE_FAILED/CONNECT_FAILED/CRYPTO_UNAVAILABLE/SIGNIN_UNAVAILABLE`), `drivePhotos.uploading/pausedOffline/done`
and the sync lines `driveSync.offline/paused/notRun/needsConfirmation`. The English is final; the Hindi, Tamil and Telugu were
drafted by the session and have had no native review. `driveSync.needsConfirmation` and `driveDevices.revokeConfirm` carry
positional placeholders: check the word order reads right.

## Tests and screenshots

- `DriveScreenStateTest` (28) and `DriveHolderTest` (35) in `ui/src/commonTest`, `DriveStringsTest` (8) in `ui/src/androidHostTest`.
- `DriveScreenshotTest` in `app/src/test/java/app/doorprints/screenshots`: 5 states (disconnected, recovery key, join with QR, connected,
  delete-everything confirm), English light and dark and Hindi light, 15 images in `app/src/test/screenshots/drive_*.png` (24 to 150 KB;
  the tall states are the largest).
- Not covered: a screen reader pass on a device, 200 % text and the real camera and clipboard (all need a device or emulator), and the
  iOS compile of `:ui` (`compileKotlinIosSimulatorArm64`; the common metadata compile passes).

## Clipboard and clock seams (review of PR #142, items 10 and 14)

- `ClipboardSeam.copySensitive(text)` (in `DriveActions.kt`): the screens copy the recovery key and the enrolment messages only through it
  (`DriveHost.clipboard`). Without one, a plain Compose copy is made. The Android implementation sets `ClipDescription.EXTRA_IS_SENSITIVE`
  on the `ClipData` (Android 13 and later); an iOS one should use a local-only, expiring pasteboard item.
- The delete countdown reads `DriveHolder.now()`, a monotonic clock (`monotonicMillis()`, `kotlin.time.TimeSource.Monotonic`), never the
  wall clock: changing the phone's time cannot shorten it.
