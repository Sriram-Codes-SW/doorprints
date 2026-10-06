# The phones' Drive controller: API, states and strings

> Session record, kept as written (2026-10-06). The decisions are in [15](../15-google-drive-backup-and-sharing.md) §3.4, §7.2 and §10.2 (the controller's rules; the phones have no recovery-key factor); where this note and 15 differ, 15 is right.

Notes for the UI agent and the lead (S4b-BL-117, with -116, -118 and -119 as the screens see them). The code is
`android/shared/src/commonMain/kotlin/app/doorprints/drive/connect/` (package `app.doorprints.drive.connect`); it is
the Kotlin twin of the website's `web/src/app/data/drive/connect/` (`drive-connect.service.ts`, `delete-flow.ts` and the
adapters). Both phones use the one `DriveConnectController`; nothing in it is platform-specific.

## Rules the controller keeps

- It only routes to the core (`DriveBackupService`, `DriveSyncBackend`, `DriveDeletionService`, `DeviceEnrolment`).
  The first-connect rule, "a folder is adopted only when pinned", rollback and fork refusal, and the recovery key
  shown once are enforced there and are neither repeated nor bypassed.
- Every failure is a `DriveReason` (a dictionary key). No exception message ever reaches a result.
- The recovery key is returned once (`ConnectResult.recoveryKey`, `RevokeDone.recoveryKey`) and kept nowhere: not in
  `state`, not in a field, not in `toString()`.
- On the phones the factor of a level 2 or 3 action is the **device check** (screen lock, fingerprint or face). There
  is no passkey and **no recovery-key factor** (website only, docs/15 §10.4a): `DeleteFactor` has only `NONE` and
  `DEVICE_AUTH`, and `authorizeDelete` has no key parameter.
- Cancellation is never swallowed; every Drive-writing call runs under one lock (one at a time).

## Construction

```kotlin
DriveConnectController(
    backup: DriveBackupService, drive: DriveClient, p: CryptoProvider, identity: DeviceIdentity,
    trust: FolderTrustStores, enrolment: DeviceEnrolment,
    deletion: DriveDeletionService, deletionStore: DeletionStore, authorizer: DeleteAuthorizer,
    syncRigs: SyncRigFactory, network: NetworkState, prefs: DrivePrefs, clock: () -> Long,
    configured: Boolean,                       // false (no Google client id): state UNAVAILABLE
    backupSource: BackupSource? = null,        // the Full backup ZIP; null: Back up now says NO_BACKUP_SOURCE
    signIn: DriveSignIn? = null,               // the phones' browser/PKCE sign-in; null in tests
    syncDriver: (suspend (DriveSyncBackend) -> Unit)? = null,
)
```

Wiring, for the lead:

- `DriveDeletionService(drive, gate = phoneAuthorizer, store, isOnline, now)` and the controller share one
  `PhoneDeletionAuthorizer(DriveGate(AuthPlatform.PHONE, deviceAuth, lockDetector, lossActions, clock), deviceAuth, clock)`.
  It is both the `AuthorizationGate` (one use, 60 seconds, bound to the operation, lock still present before each file)
  and the `DeleteAuthorizer`; the proof is the grant id until S4b-BL-135 (superseded: on Android the proof is now the device check's HMAC, see the S4b-BL-135 section of [android-drive-wiring-notes.md](android-drive-wiring-notes.md)).
- `syncRigs = DefaultSyncRigFactory(drive, p, syncStateStore, photoStateStore, localRows, clock, photoConfig, paused)`;
  `paused = { deletionStore.pending() != null }` pauses sync while a delete is half done (docs/15 §3.3).
- `syncDriver`: pass `{ backend -> repository.sync(photosAllowed = controller.photosAllowed()) }` when the repository's
  sync loop already uses this backend (it calls `commitPushes()` and applies the pulled rows); with null the controller
  calls `commitPushes()` itself (a Drive pass without applying rows, as the website's adapter does).
- `DriveSignIn`, `DrivePrefs` (a `SharedPreferences` wrapper), `DeletionStore` (persistent) and `NetworkState`
  (`AndroidNetworkState`) are the platform parts.
- **`DeviceEnrolment` is the one seam still to wire.** It has the web's names and shapes; the real implementation is
  `DriveBackupService` plus the HPKE PSK mode from `feat/android-drive-e-enrolment` (`approveDevice`,
  `approveDevicePsk`, `joinFromWrap`, `joinFromPsk`, `revokeDevice`). The lead writes a thin adapter from that branch's
  `ApproveDeviceOutcome` / `RevokeDeviceOutcome` to this interface:

```kotlin
interface DeviceEnrolment {
    suspend fun approveDevice(publicKey: ByteArray, name: String, platform: DevicePlatform): EnrolmentApproval
    suspend fun approveDevicePsk(publicKey: ByteArray, name: String, platform: DevicePlatform, psk: ByteArray): EnrolmentApproval
    suspend fun joinFromWrap(enc: ByteArray, ct: ByteArray, epoch: Int): DriveConnection
    suspend fun joinFromPsk(enc: ByteArray, ct: ByteArray, epoch: Int, psk: ByteArray): DriveConnection
    suspend fun revokeDevice(kid: ByteArray): EnrolmentRevoke
}
sealed interface EnrolmentApproval {
    class Approved(val connection: DriveConnection.Ready, val wrapEnc: ByteArray, val wrapCt: ByteArray, val epoch: Int)
    data class Failed(val problem: DriveProblem)
}
class EnrolmentRevoke(val connection: DriveConnection, val recoveryKey: RecoveryKey?)
```

The pairing code (8 digits), the pairing messages and the `dp1.` QR payload (parse and encode) are **not** in the
controller (they come with the enrolment branch). The UI parses a scanned `dp1.` text into a public key and a PSK and
passes the bytes to `approveJoinedDevicePsk`; the newcomer shows its own `devicePublicKey()` and PSK.

## State

`val state: StateFlow<ConnectState>`, `val enrolmentNotice: StateFlow<DriveReason?>` (`DEVICE_REVOKED` after a
revoke, else null), `val isReady: Boolean`.

```
                     configured = false
   UNAVAILABLE <---------------------------- (constructor)
                                  connect()
   DISCONNECTED --------------------------------> CONNECTING
        ^   ^                                          |  sign-in closed: back to where it was (SIGNIN_CLOSED)
        |   | NoFolder / FolderGone(FOLDER_GONE)       v
        |   +---------------------------------- handle(DriveConnection)
        |                                              |
        |   createFolder()  -> FIRST_CONNECT_SHOW_RECOVERY_KEY --confirm/skip--> READY
        |   (withRecoveryKey=false -> READY at once)
        |                     NEEDS_ENROLMENT  --openWithRecoveryKey / joinFromWrap / joinFromPsk--> READY
        |                     NEEDS_RECOVERY_KEY (revoked: enrolmentNotice = DEVICE_REVOKED) --openWithRecoveryKey--> READY
        |                     READY
        |                     ERROR (Drive problem, sign-in denied/failed, unexpected exception)
        +-- disconnect() / disconnectAll() / a finished "Delete everything" (from any state)
```

- A wrong or missing recovery key while on `NEEDS_ENROLMENT` or `NEEDS_RECOVERY_KEY` keeps that state and returns the
  error (`JOIN_WRONG_KEY`, `NO_RECOVERY_KEY`); a typo (`JOIN_INVALID_FORMAT`) never reaches Drive.
- `confirmRecoveryKeySaved()` and `skipRecoveryKeyWithWarning()` do nothing unless the key screen is showing.
- Backups work while the key screen is showing (the folder is open).
- Differences from the website: a closed sign-in returns to the previous state, not `ERROR`; approving a device does not
  leave the key screen; `syncStatus()` before the first pass is `NOT_RUN` (the website says `error`); a refused revoke keeps
  the old folder open. The website-only recovery-key-unshown flag and the passkey calls do not exist here.

## Functions (all `suspend` unless marked)

| Area | Signature | Result |
|---|---|---|
| Connect | `connect()`, `refresh()` | `ConnectResult(state, recoveryKey?, error?)` |
| | `createFolder(withRecoveryKey: Boolean = true)` | `ConnectResult` (key once) |
| | `openWithRecoveryKey(text: String)` | `ConnectResult` |
| | `confirmRecoveryKeySaved()`, `skipRecoveryKeyWithWarning()`, `hasShownRecoveryKey()` (plain) | |
| | `disconnect()` | Unit (files in Drive stay; signs out locally) |
| | `disconnectAll(promptReason: String)` | `Outcome<Unit>` (device check L2, revoke grant, disconnect) |
| | `accountEmail()` | `String?` |
| Backups | `backUpNow()` | `Outcome<BackUpDone(backup, shrinkHoldBackupId?, missingNewer)>` |
| | `confirmShrink(backupId)` | Unit |
| | `listBackups()`, `lastBackup()` | `Outcome<BackupList>`, `Outcome<BackupSummary>` |
| | `importFromDrive(backupId, staging: StagingSink)` | `Outcome<ImportedBackup(backup, format, plaintextSize)>`; the ZIP is in `staging`, hand it to the existing import preview; the sink is discarded on any refusal |
| | `runDueBackup()` | `DueBackupResult.Ran / NotRan(BackupSchedule.Reason) / Failed(reason)` |
| | `autoBackupEnabled()`, `setAutoBackup(Boolean)` (plain) | |
| Sync | `syncNow(confirmShrink: Boolean = false)` | `SyncInfo(state, lastSyncAt, skipped, error?, housesToDelete?, liveHouses?)`; `state` is `SYNCED, WAITING, PAUSED, OFFLINE, NEEDS_CONFIRMATION, SKIPPED_FILES, ERROR, NOT_RUN` |
| | `syncStatus()` (plain), `prepareSync()` (plain) | `SyncInfo` |
| Photos | `photoSettings()`, `setPhotosWifiOnly(Boolean)`, `uploadPhotosNowOverMobile()`, `photosAllowed()`, `photoStatus(pending)` (plain) | |
| | `pendingPhotoBytes()` | `Long?` (0 before connect, null when the store fails) |
| Devices | `devicePublicKey()`, `listedDevices()` (plain) | `ByteArray`, `List<ListedDevice(kidHex, name, platform, self)>` |
| | `approveJoinedDevice(publicKey, name, platform, promptReason)` | `Outcome<EnrolmentWrap(wrapEnc, wrapCt, epoch)>` (base64 text) |
| | `approveJoinedDevicePsk(publicKey, name, platform, psk, promptReason)` | `Outcome<EnrolmentWrap>` |
| | `joinFromWrap(wrapEnc, wrapCt, epoch)`, `joinFromPsk(wrapEnc, wrapCt, epoch, psk)` | `ConnectResult` |
| | `revokeListedDevice(kidHex, promptReason)` | `Outcome<RevokeDone(recoveryKey)>` (key once) |
| Deleting | `deletePlan(action)` | `Outcome<DeletionPlan>` (counts and bytes only) |
| | `deleteConfirmInfo(action)` | `Outcome<DeleteConfirmInfo(level, factor, tickBoxRequired, delayMs)>` |
| | `deleteFactor(action)` | `DeleteFactor?` (`NONE`, `DEVICE_AUTH`; null when refused) |
| | `authorizeDelete(plan, promptReason)`, `authorizeResume(promptReason)` | `Outcome<DeleteGrant>` |
| | `executeDelete(plan, grant?)`, `resumeDelete(grant?)` | `Outcome<DeleteRun(finished, left, total, stopped?)>` |
| | `pendingDeletion()`, `deletedMarker()` | the list of what is left; the finished-marker (reconnect path) |

`DeletionAction` is the plan type (`OneBackup(fileId)`, `OlderBackups`, `AllBackups`, `Everything`). The UI order for a
delete: `deleteConfirmInfo` (shows level, tick box, delay) -> `deletePlan` (the dialog's counts) -> `authorizeDelete`
(the device check, asked here) -> `executeDelete`. When `executeDelete` says `finished = false`, show `left` of `total`
and *Try again* = `authorizeResume` then `resumeDelete`. After a finished *Delete everything* the state is
`DISCONNECTED` and `deletedMarker().reconnectPath` is `FULL_FIRST_CONNECT`. `promptReason` is the translated line
for the system prompt.

## Error keys (`DriveReason.key`)

Keys that exist on the website keep their website name; the rest are new for the phones (marked *new*).

- Connect: `driveConnect.notConfigured`, `driveConnect.folderGone`, `driveConnect.failed`,
  `driveConnect.connectionInProgress`, `driveConnect.noBackupSource`, `driveConnect.noBackups`,
  `driveBackups.error.notConnected`, `driveBackups.error.backupNotFound`, `driveBackups.error.retrieveFailed`
  (listed, not produced: the stream hand-off has no such case), `driveJoin.errorInvalidFormat`, `driveJoin.errorWrongKey`,
  `driveEnrol.badMessage`.
- Drive problems: `driveProblem.` + `OFFLINE, UNAUTHORIZED, QUOTA_EXCEEDED, RATE_LIMITED, SERVER, DRIVE, CORRUPT,
  KEYS_ROLLED_BACK, KEYS_UNTRUSTED, KEYS_UNREADABLE, NO_RECOVERY_KEY, DEVICE_REVOKED, CONTROL_ROLLED_BACK,
  CONTROL_INVALID, FOLDER_WITHOUT_KEYS, FOLDER_EXISTS, BACKUP_REFUSED, SOURCE_FAILED, CRYPTO_UNAVAILABLE`.
- Sign-in: `driveProblem.SIGNIN_CLOSED`, `driveProblem.SIGNIN_DENIED`, `driveProblem.SIGNIN_UNAVAILABLE`,
  `driveProblem.CONNECT_FAILED`.
- Deleting (*new*, `driveDelete.reason.` + `OFFLINE, NOT_AUTHORIZED, AUTHORIZATION_TOO_WEAK, AUTHORIZATION_STALE,
  AUTHORIZATION_OTHER_OPERATION, STALE_PLAN, ROOT_NOT_FOUND, NOT_A_BACKUP, NOTHING_TO_DELETE, OTHER_DELETION_PENDING,
  NOTHING_PENDING, DRIVE_ERROR`).
- The device check (*new*, `driveDelete.reason.` + `NO_DEVICE_LOCK, AUTH_CANCELLED, AUTH_FAILED, AUTH_LOCK_NOT_SET,
  AUTH_NOT_AVAILABLE, AUTH_LOCKED_OUT, AUTH_PAUSED_NO_LOCK, AUTH_PAUSED_UNKNOWN`).

## Strings the UI needs (keys only; the lead assigns the text)

Everything above, plus these screen strings, which the controller does not produce but its states and results imply
(the website's names where it has one):

- Connect card by state: `driveConnect.heading`, `driveConnect.intro`, `driveConnect.unavailable`,
  `driveConnect.connect`, `driveConnect.connecting`, `driveConnect.firstConnect`, `driveConnect.recoveryKeyNote`,
  `driveConnect.recoveryKeyCopy`, `driveConnect.recoveryKeyCopied`, `driveConnect.copyFailed`,
  `driveConnect.confirmSavedRecoveryKey`, `driveConnect.recoveryKeyWarning`, `driveConnect.needsEnrolmentHeading`,
  `driveConnect.needsEnrolmentMessage`, `driveConnect.ready`, `driveConnect.disconnect`, `driveConnect.disconnecting`.
- Join: `driveJoin.heading`, `driveJoin.description`, `driveJoin.label`, `driveJoin.helpText`, `driveJoin.joinButton`,
  `driveJoin.errorEmpty`, `driveJoin.lostKeyMessage`, `driveJoin.disconnect`; the revoked sentence is
  `driveProblem.DEVICE_REVOKED`.
- Backups: `driveConnect.lastBackup`, `driveConnect.never`, `driveConnect.backUpNow`, `driveConnect.backupsList`,
  `driveConnect.importFromDrive` (*Import a backup*), `driveConnect.automaticBackup`, `driveBackups.errorState`, the
  shrink question (*new*: `driveBackups.shrinkHeading`, `driveBackups.shrinkBody`, `driveBackups.shrinkConfirm`).
- Sync (*new*, one per `SyncState`): `driveSync.synced`, `driveSync.waiting`, `driveSync.paused`, `driveSync.offline`,
  `driveSync.needsConfirmation` (with the two counts), `driveSync.skippedFiles`, `driveSync.error`, `driveSync.notRun`,
  `driveSync.syncNow`.
- Photos: `driveConnect.uploadPhotos`, `driveConnect.uploadPhotosNow`, plus *new* `drivePhotos.waitingForWifi`,
  `drivePhotos.uploading`, `drivePhotos.pausedOffline`, `drivePhotos.done`.
- Devices (*new* on the phones): `driveDevices.heading`, `driveDevices.thisDevice`, `driveDevices.approve`,
  `driveDevices.revoke`, `driveDevices.revokeConfirm`, `driveDevices.newRecoveryKey`, `driveDevices.disconnectAll`,
  `driveDevices.scanQr`, `driveDevices.showQr`, `driveDevices.codeInstead`, `driveDevices.codesMatch`.
- Delete dialog: `driveDelete.heading`, `driveDelete.oneBackup`, `driveDelete.olderBackups`, `driveDelete.allBackups`,
  `driveDelete.everything`, `driveDelete.planHeading`, `driveDelete.planWarning`, `driveDelete.proceed`,
  `driveDelete.confirmHeading`, `driveDelete.confirmWarning`, `driveDelete.confirmCheckbox`, `driveDelete.tickRequired`,
  `driveDelete.countdown`, `driveDelete.deleting`, `driveDelete.done`, `driveDelete.saveCopyFirst`,
  `driveDelete.deleteForGood`, `driveDelete.tryAgain`, `driveDelete.left`, `driveDelete.nothingDeleted`, and *new*
  `driveDelete.deviceCheckHeading`, `driveDelete.deviceCheckPrompt` (the `promptReason`).
  Not used on the phones: `driveDelete.passkeyRequired`, `driveDelete.setupPasskey`, `driveDelete.usePhone`,
  `driveDelete.recovery*` (website only).

All four languages (hi, ta, te marked *under review*); brand words *Import a backup*, *Save a copy*; never "Restore".
