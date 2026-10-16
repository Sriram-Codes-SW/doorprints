# Android Drive, A2: device key, device lock, delete authorisation (S4b-BL-127, device-key part of S4b-BL-126)

> Session record, kept as written (2026-10-06). The decisions are in [15](../15-google-drive-backup-and-sharing.md) §9.10 (the device key, the wrapped scalar on API 26-30, the lock) and §10.2 (the device check as built); where this note and 15 differ, 15 is right.
>
> **Superseded in part (S4b-BL-135, Android half): see "Update: S4b-BL-135" at the end of this note.** The `DeviceAuthorizationGate.kt` row of the table below, decision 6 (the proof and the gate's grant) and the `TimedOut` result of decision 7 describe the state A2 left: the gate is deleted, `OperationProof.kt` keeps its types, and a timed-out prompt is a cancel at the `AuthResult` seam (S4b-BL-140).

Notes for the lead. Code: `android/app/src/main/java/app/doorprints/drive/device/`; tests: the same path under
`android/app/src/test/java/`. Design: docs/15 §10.1-§10.3, §9.9.

## What was built

| File | What |
|---|---|
| `DeviceKey.kt` | `DeviceKeyBackend` seam, `KeystoreDeviceIdentity` (implements `DeviceIdentity`), `DeviceKeyCryptoProvider`, `DeviceKeyException` (`LOST`, `NEEDS_UNLOCK`) |
| `WrappedScalarKeyBackend.kt` | The key for API 26-30: a P-256 scalar wrapped under a Keystore AES-GCM key (AAD = the public point) |
| `AndroidDeviceKeys.kt` | Keystore adapters: `KeystoreAgreeBackend` (API 31+, non-exportable EC key, `PURPOSE_AGREE_KEY`), `KeystoreSecretWrapper`, `FileBlobStore`, `AndroidDeviceKeys.backend(...)` |
| `DeviceAuthorizationGate.kt` | `DeviceAuthorizationGate : AuthorizationGate` plus `authorize(plan, reason)`, `ProofOutcome`, `DeleteAuthorization`, `OperationProof` |
| `AndroidOperationProvers.kt` | `KeystoreOperationProver` (API 30+: BiometricPrompt, `CryptoObject`, per-use HMAC key), `SoftwareOperationProver` (below API 30), `PromptErrors`, `OperationProvers.forThisDevice` |
| `DeviceLock.kt` | `DeviceLockActions` (`LockLossActions`), `DriveLockStore`, `DriveLockNotice` (the documented words), `DeviceLockDetectors` |

## Decisions the lead must confirm

1. **HPKE and a Keystore key.** `Hpke.setupBaseR` needs only `CryptoProvider.p256Agree(privateKey, peer)`; it never reads the
   scalar. But `JvmCryptoProvider.p256Agree` refuses any key but its own private `JvmP256Key`, so a Keystore key is wired in by
   giving the Drive services a `DeviceKeyCryptoProvider(platformCryptoProvider())` (a `CryptoProvider by delegate` that handles
   `KeystoreP256Key` in the Keystore and delegates everything else, including the ephemeral HPKE keys). **No change in
   `:shared` was needed, but the services (`DriveBackupService` etc.) must be constructed with this provider on Android.**
2. **The Keystore can do ECDH only from API 31.** On API 31+ the private key is non-exportable and the agreement runs in the
   Keystore. minSdk is 26, so on API 26-30 the design cannot be met literally: there the scalar is wrapped under a
   non-exportable Keystore AES key and stored in `noBackupFilesDir`; it is unwrapped for one agreement (milliseconds) and
   overwritten. At rest and across a lock removal it is as strong as the Keystore (the AES key is invalidated with the lock);
   in process memory during an agreement a rooted phone or a debugger could read it. this is **the fallback docs/15 §9.2 already specifies** (RR-21; an earlier version of this note wrongly said the design did not describe it), with its own test row (§9.8, TC-I-44 planned). Decided by the review of 2026-10-06 as acceptable; it is recorded in 15 §9.10 as built.
3. **Auth windows (§10.3).** Both device-key variants use `setUserAuthenticationRequired(true)` with a 6-hour window
   (`setUserAuthenticationParameters` on API 30+, the deprecated duration setter below). Android deletes or invalidates such keys
   when the lock is removed; making one without a lock fails, which is the same rule as "Drive can only be switched on with a lock".
4. **Key lost is a state, not a repair.** `KeystoreDeviceIdentity.key` throws `DeviceKeyException(LOST)` when the key is
   missing or invalidated **while `folderPinned()` is true**, and never creates a new one then. With no pinned folder it replaces
   the key. `NEEDS_UNLOCK` (outside the 6-hour window, or an unknown Keystore error) waits and never recreates: only the
   Keystore's own `KeyPermanentlyInvalidatedException` / `UnrecoverableKeyException` loses a key. The caller supplies
   `folderPinned` (from the pin store of the other agent's work); after the person re-enrols it must report false until the new pin.
5. **Reuse of `app.doorprints.deviceauth`.** The lock rules (`GateRules`, `DriveGate`, `AndroidLockLostDetector`,
   `AndroidDeviceAuth`) already existed in `:shared`; they are reused, not duplicated. Added here: `DeviceLockActions` (what a
   removed lock does *locally*: discard the dead key, persist `paused` / `needsReenrolment` / `keyDropped`; it holds no Drive
   client, so it cannot delete, revoke or re-key anything remote) and a detector that adds "the key was invalidated, or removed
   while a folder is pinned" to `isDeviceSecure`.
6. **The grant (§10.2).** A grant is made by `authorize(plan)` for one `operationId` (which already hashes level, root and ids),
   held in memory only (at most 4), good for 60 s to **start** one run: `isGenuine` is true once (the service calls it at the
   start of a run and of a resume), then `stillHolds` is true while the lock is present and false for good after the lock
   goes. The proof is `HMAC-SHA-256(utf8(operationId) 00 u64be(issuedAtMs))`, the web's message. The Keystore key cannot be
   used to verify later (per-use authentication), so genuineness is "the gate issued exactly this token", compared in constant
   time; an overlay cannot produce a proof without passing the prompt. Android below 11 uses the plain pass result and a
   process-local HMAC key, as §10.2 says; that binds the grant to the operation inside the app but is not hardware-bound.
7. **Results.** Granted, NotRequired (L1), Denied (platform lockout), Cancelled, TimedOut (prompt open over 60 s, cancelled, or the
   platform's own timeout code), LockRemoved (before or during the prompt), Unavailable (no activity, no hardware, lock state
   unreadable: fails closed), Failed.
8. **Words.** `DriveLockNotice` has the two English sentences of §10.3 as constants. The four-language string resources
   (hi, ta, te marked *under review*) are not added because they live outside the directories this task may touch.

## Not verified here (needs a device or emulator)

- `KeystoreAgreeBackend`, `KeystoreSecretWrapper`, `KeystoreOperationProver`: Robolectric has no AndroidKeyStore provider or
  BiometricPrompt, so these only compile; the decisions inside them are in pure classes that are tested. Needs: a Keystore
  ECDH against the JVM provider's result on API 31+; the wrap/unwrap on API 26-30; a CryptoObject prompt on API 30+;
  the lock removal invalidating each key (an emulator test as `AppLockEmulatorTest`, skipping when no PIN can be set).
- Some vendors' Keystores mishandle `PURPOSE_AGREE_KEY`; if one is found, `AndroidDeviceKeys.backend` can fall back to the
  wrapped scalar (the same interface).
- Zeroing of scalars is by `fill(0)` and is not asserted by a test.

## Update: S4b-BL-135 (Android half): the proof is the device check's HMAC

> Written 2026-10-06 on branch `feat/qr-scanner-s3-hmac`. The table above is the state A2 left; this is what changed.

**Decision (the lead to confirm): the proof is fed into `PhoneDeletionAuthorizer`; `DeviceAuthorizationGate` is deleted.**
The gate could not be *the* shared object without also re-doing what `DriveGate` already does (the policy: which action
needs which level, the refusals, the lock pause and the dropped keys; the approve / revoke / disconnect-all actions have
no plan and still need that policy). Feeding the proof into the authorizer was the smaller change and it removes a second
copy of "one use, 60 s, lock-aware" and a third outcome mapping.

- `DeviceAuthorizationGate.kt` is now `OperationProof.kt`: `ProofOutcome`, `OperationProver`, `OperationProof` (the
  message `utf8(operationId) 00 u64be(issuedAtMs)`, byte for byte the website's `proofMessage`). Nothing else moved.
- **Deleted as dead:** `DeviceAuthorizationGate` (the class, `MAX_GRANTS`, its 60 s prompt timeout), the gate's own
  `DeleteAuthorization` sealed type (7 variants; the controller's `DeleteAuthorization` is the one in use), and its second
  mapping `ProofOutcome` -> `DeleteAuthorization`. Its 25 tests are replaced by `PhoneAuthorizationProofTest` (the same
  rules, over the real `DriveGate` + `PhoneDeletionAuthorizer` + `ProverDeviceAuth`) and the new tests in
  `PhoneDeletionAuthorizerTest`.
- `ProverDeviceAuth` implements `OperationBoundAuth` (new, `:shared` `drive/connect`): `bindNext(operationId)` before the
  ask, `takeProof()` after it. `PhoneDeletionAuthorizer` binds the plan's operation id, lets `DriveGate` ask, and takes
  the proof the prover signed (the `CryptoObject`'s HMAC on Android 11+). The token is
  `AuthorizationToken(level, issuedAtMs = the moment the person passed, operationId, proof = 64 hex)`. One prompt, as before.
- A pass that signed nothing (or a proof that is not 64 lower-case hex digits) is **refused** (`AUTH_FAILED`) for L2/L3.
  L1 asks nothing and carries `proof = ""`. Approve / revoke / disconnect-all have no plan: they are asked under a one-off
  label (`doorprints/device-check/...`) and make no token.
- **The software prover's key (review O8) is now used:** its HMAC makes the proof a secret that only this process's pass
  could produce (the old proof was a sequential number anyone could guess). Verification is by comparison with the proof
  the authorizer stored at the pass, in constant time: a Keystore HMAC key is not exportable, so nothing else could check it.
  That is also the honest limit (§10.6): the proof stops a replayed, relabelled or forged token inside the app, not a
  changed app.
- **Cancelled, denied and timed out.** Cancelled (`AUTH_CANCELLED`) and denied/locked out (`AUTH_LOCKED_OUT`) are distinct to the
  person. A prompt that timed out is a cancel at the `DeviceAuth` seam (`AuthResult` has no timed-out value, by its own
  doc); `ProofOutcome.TimedOut` stays distinct inside the prover. A separate `AUTH_TIMED_OUT` needs `AuthResult` (outside
  this change) and four strings: left as a follow-up.
- The 60 s the old gate allowed for the *prompt itself* is gone: the platform ends a prompt by itself, and the 60 s that
  matters (pass to start) is measured from the pass.
