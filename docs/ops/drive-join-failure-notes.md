# Drive join failure on a real phone: audit notes (S4b-BL-146)

Report (owner, signed release APK, real phone): joining a Drive folder with the recovery key shows "This phone could not
prepare the backup. Try again" (`DriveProblem.Kind.SOURCE_FAILED`). That text is the catch-all of
`DriveBackupService.connection { }` for any exception that is not a `DriveException`, `KeysException`, `ControlException`,
`DpxException` or `CryptoException` (`DriveBackupResults.kt` `DriveProblem.of`, `else ->`). Facts from the owner: the
folder was made on the computer (website client id; the phone signs in with the Android client id, same Cloud project,
scope `drive.file`); making a folder on the phone worked earlier; after deleting the folder, clearing the app data and
using *Disconnect all devices*, linking the same phone gave the same text.

## What was done

- **The screen now says where and what**: *Code: step/ClassName* (class name only; `DriveCode`, tested not to read a message,
  cause or stack). The join steps are `join-locate` (finding the folder and `keys.json`), `join-key` (this phone's device
  key), `join-recover` (opening `keys.json` with the recovery key), `join-add` (listing this phone), `join-write`
  (uploading `keys.json`), `join-ready` (the control file and the folder session). Other steps: `connect`, `create`,
  `open`, `approve`, `backup`, `sync`, `screen`. `Error`s (a missing class, a failed initialiser) are caught on these
  paths too, so the screen shows a code instead of hanging. `OutOfMemoryError` is shown with a code, not rethrown: the
  common code has no `VirtualMachineError` and the failure is local to the step.
- **A definite bug was found and fixed** (suspect 1).

## Suspects, ranked

1. **DEFINITE, fixed: the join pinned the folder before this phone's device key existed.** `openWithRecoveryKey` ran
   `openWithRecovery` (which pins the folder through `KeysGuard.accept`) and only then read `device.key` (`myKid`). The key
   is made on first use (`KeystoreDeviceIdentity.ensureLocked`, `DeviceKey.kt:139`), and once a folder is pinned a missing
   key is "lost" and is never made again (`DeviceKey.kt:146`, `FolderPinProbe.isPinned`, `DriveFiles.kt:166`, wired in
   `DriveAssembly.kt:136`). So a phone that had no key yet (a fresh install, cleared data, or any phone that had not shown
   its QR code or made a folder) threw `DeviceKeyException(LOST)`, which is not typed, so the screen said SOURCE_FAILED.
   Folder creation is not affected: `createFolder` reads `device.key` before `pinCreated`. It also explains the clean-state
   retry: cleared data empties the pin and (on most phones) the key alias, so the first use of the key is again inside the
   join. The fake-backed test `aPhoneWhoseDeviceKeyIsNotMadeYetCanJoinWithTheRecoveryKey` reproduced it (code
   `join-recover/app.doorprints.drive.device.DeviceKeyException`) and passes now (`join-key` makes the key first). If the
   owner's next screen still shows a code, this one is ruled out. Not run on a phone.
2. **Typed exceptions that the catch-all hides** (not changed: they need words in four languages and a product decision).
   `DeviceKeyException` (`LOST`, `NEEDS_UNLOCK`, `DeviceKey.kt:69`) and `SignInException` (`DENIED`, `CANCELLED`,
   `CONSENT_REQUIRED`, `OFFLINE`, `UNAVAILABLE`; thrown from `DriveTokenProvider.accessToken` *inside* any Drive call,
   `SignInException.kt:22`) are not in `DriveProblem.of`, so they too end as SOURCE_FAILED. A grant that Play services no
   longer holds (for example after *Disconnect all devices*, which revokes it) or a consent screen with no Activity gives
   `SignInException` in the middle of a join. The code shows them by class name now.
3. **Raw Keystore exceptions around the device key** (`AndroidDeviceKeys.kt`): `KeystoreAgreeBackend.create()` (line 106)
   makes a key with `setUserAuthenticationRequired(true)`, which throws `IllegalStateException` /
   `InvalidAlgorithmParameterException` / `ProviderException` on a phone with no secure screen lock; `status()` (95) and
   `publicKey()` (118) only wrap `DeviceKeyException`, so a `KeyStoreException` or `ProviderException` from
   `KeyStore.load`, `containsAlias` or `getCertificate` escapes untyped. The JVM has no Keystore, so no test sees this.
   A stale alias that survives clearing data on some phones (key present, no blob, no pin) would give `READY` and then a
   Keystore ECDH that needs the user's unlock window (`NEEDS_UNLOCK`).
4. **Software ECDH on Conscrypt** (`CryptoProvider.android.kt`): `p256FromScalar` (136) calls `KeyFactory.generatePrivate`
   (172) and `p256Agree` calls `generatePublic` (193) outside the `try` that maps `GeneralSecurityException`, so an
   `InvalidKeySpecException` from Android's provider would be raw. `AlgorithmParameters.getInstance("EC")` (54) differs
   between Conscrypt and the JVM's SunEC. Lower: folder creation uses the same `p256FromScalar` for the recovery key and
   worked, so the provider's basic path is fine.
5. **Folder or `keys.json` made by another OAuth client (the website's)**: traced, and all of its cases are *typed*, so
   none can produce SOURCE_FAILED. `drive.file` shows an app only the files that app created or was handed
   ([15](../15-google-drive-backup-and-sharing.md) §2.2; whether a grant is shared across the clients of one project is the
   still open spike S4b-BL-122). If the folder is not visible: `locateKeys` gets `ensureFolder == null`, the join returns
   `NoFolder` (no error text), and `connect()` would not have offered the join (`NeedsEnrolment`) at all. If a file is
   listed but not readable: `downloadVerified` gives `DriveException(NOT_FOUND)` -> `Kind.DRIVE`. If readable but not
   writable: `writeKeys` (`PATCH`) gives 403/404 -> `FORBIDDEN`/`NOT_FOUND` -> `Kind.DRIVE`. Ktor's OkHttp engine supports
   `PATCH`. So a website-made folder explains a Drive message, not this one. The code lets the next try prove it.
6. **Drive HTTP exceptions that are not `IOException`** (`HttpDriveClient.kt:234-237` maps only `IOException` to OFFLINE):
   a Ktor/OkHttp failure of another type (an `IllegalStateException` from a closed engine, for example) would be untyped.
   Low; the code will name it.
7. **Stale local state**: `FolderPinProbe.isPinned` treats any read failure as pinned (`DriveFiles.kt:171`), which for a
   missing key becomes `LOST`; the state and pin files are JSON (`DriveStates.kt`) and a damaged file would raise a
   serialization exception. Cleared data removes both, so low for the clean-state retry.

## Clean-state path (folder deleted, data cleared, link again)

Folder gone: `connect()` finds no folder (`NoFolder`) and the screen offers to make one; *join with the recovery key* is
offered only after `connect()` found a folder with a key set (`NeedsEnrolment`), so a join with no folder returns
`NoFolder` and no error. With the folder still in the bin, `ensureFolder` skips it (`trashed=false`). Stale values that
can break the first Drive call: the sealed refresh token and Keystore alias (suspects 2 and 3), a Keystore device key kept
after clearing data (it is then used with no blob or pin: `READY`, or `NEEDS_UNLOCK` until the unlock window), and the
pin, which clearing data removes. Suspect 1 applies to any join where the key is made for the first time.

## Outcome (owner, 2026-10-07)

Confirmed on a real Android phone with the release APK built from `main` at `e564ecae`: joining with the recovery key works, for a
folder made on the phone and for one made on the computer. So suspect 1 (the device key made after the pin) was the cause, and a
folder made by the website's OAuth client is visible and writable from the phone's client (one Google Cloud project, `drive.file`): this
answers the cross-client part of the open spike S4b-BL-122 for Android (the iPhone's custom-scheme redirect is still open).

## To confirm on the phone (kept for a future failure)

Join again with the build that has the code and send the *Code: ...* line. `join-key/...DeviceKeyException` or
`join-locate/...SignInException` means suspect 2 or 3; `join-recover/...InvalidKeySpecException` means 4; no error means 1
was the cause.
