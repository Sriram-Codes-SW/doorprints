# S4b-BL-127: device lock and authenticated deletes (notes)

Branch `feat/drive-device-auth`, stacked on `feat/drive-crypto-core`. Development and unit tests only.

## Threat lines (written before coding): someone with Drive write access, no folder key, against this feature

1. Drive has no say in a local check: they can delete or overwrite any file with Drive's own API. No in-app level stops that (docs/15 10.6); the defence is the encryption and each device's own copy. Nothing here pretends otherwise.
2. They can plant or edit a `keys.json`/control file to look like "lock removed", "backups deleted" or "folder gone". Defence: `DriveGate` takes the lock state ONLY from the local `LockLostDetector`; no Drive content is an input to the gate, to `DeletionPolicy` or to key dropping.
3. They can delete the folder to provoke a "lock lost" reaction. Defence: the lock-loss reaction (`LockLossActions`) has no Drive client and no network at all: it can only drop LOCAL keys and flag re-enrolment. It cannot delete, re-key, revoke or re-create anything remote.
4. They can make `files.list` slow/odd so a delete runs long past its authentication. Defence: a grant is one operation, 60 s from authentication to start, spent on first use, bound to one action; re-checked against the lock when redeemed.
5. They can fake a "backup count" (add/remove backup files) to turn the last-backup delete into an L1 one. Defence: an unknown count (null) is treated as the last backup (L2); the count is the caller's job and must come from a fresh listing; a count of 0 or 1 is L2.
6. They can force offline-looking errors so the delete "queues". Defence: delete-type actions are refused offline, never queued (policy outcome OFFLINE); there is no queue in this ticket.
7. They cannot reach the device's Keystore/Keychain keys; they could try to cause LOCKED_OUT or errors on the prompt: every non-SUCCESS result, and any exception from the platform, ends as "Nothing was deleted" (fail closed).
8. Website: with developer tools anyone at the browser can skip a page check. Defence per docs/15 10.4: no PRF, no L2/L3 (REFUSED/USE_PHONE); the PRF seam seals the key, so the check guards a real key. The page check alone is never a factor.
9. A Drive-side attacker cannot learn the PRF output or the seal key; the sealed blob is stored locally (IndexedDB), never in Drive. Wrong PRF output opens nothing (AES-GCM fails closed).
10. Unknown lock state (detector cannot tell) pauses Drive but does NOT drop keys: dropping needs a positive "REMOVED"; no error kind triggers a wipe, revoke or re-create.

## What was built (all on `feat/drive-device-auth`)

Kotlin, `android/shared` package `app.doorprints.deviceauth`:
- `DeviceAuth` (commonMain; `isDeviceLockEnabled()`, `authenticate(reason, level)` -> `AuthResult`), `DeleteLevel`.
- `DeletionPolicy` (pure; `decide`, `levelOf`, `confirmEnabled`, `grantCheck`), typed `DeletionDecision`/`Requirements`/`RefusalReason`.
- `DriveGate` (`canConnect`, `beforeRun`, `authorize`, `redeem`), `GateRules`, `LockLostDetector`, `LockLossActions` (local only, no Drive client), `AuthGrant`.
- `AndroidDeviceAuth` + `AndroidAuthErrors` + `AndroidLockLostDetector` (androidMain), `IosDeviceAuth` + `IosLockLostDetector` (iosMain). No expect/actual needed: interfaces in common, platform classes implement them.
- `docs/schemas/delete-policy-vectors.json` (47 decisions, 8 confirm, 6 grants, 8 gate rows; expectations written by hand from docs/15).
- Tests: `DeletionPolicyVectorsTest` (5), `DeletionPolicyTest` (4), `DriveGateTest` (16), `AndroidAuthErrorsTest` (1): all pass (`:shared:testAndroidHostTest --tests 'app.doorprints.deviceauth.*'`). iOS klib compile (`:shared:compileKotlinIosSimulatorArm64`) and `compileCommonMainKotlinMetadata` pass.
Web, `web/src/app/data/device-auth/`: `delete-policy.ts` (twin), `prf-seal.ts` (PRF seam interface `PrfAuthenticator`, HKDF+AES-GCM sealing of the website key, `FakePrfAuthenticator`), `web-authorizer.ts` (passkey factor = opening the sealed key; grants), specs run the same vectors: 79 tests pass. Prettier applied.

## Decisions
- androidx.biometric is NOT a dependency: used platform `android.hardware.biometrics.BiometricPrompt` (as the app lock does). BIOMETRIC_STRONG|DEVICE_CREDENTIAL from API 30; API 29 device-credential flag; API 26-28 needs a `ConfirmCredentialLauncher` (UI seam), absent -> NOT_AVAILABLE (fail closed).
- Actions not in docs/15 10.1 table were classed: `REMOVE_SHARED_HUNT`, `DISCONNECT_THIS_DEVICE`, `TURN_AUTO_BACKUP_OFF` L1 and local (work offline); the last two also work with no lock and no PRF (they only stop Drive use). Everything else needs the lock on phones.
- Tick box and 5 s delay: L3 (decision 8); the box alone also for Delete all backups (below). **Owner decision, 2026-10-02:** "Delete all backups" (L2) also carries the tick box: device authentication + box, NO 5-second delay (delay stays L3 only). Built in both stacks and the vectors.
- Unknown backup count = last backup (L2). Order of refusals: no lock, website w/o PRF, offline.
- Unknown lock state pauses but never drops keys. Timeout of the prompt maps to CANCELLED (no TIMED_OUT result in the spec'd enum).
- `BiometricConstants.ERROR_NEGATIVE_BUTTON` (13) is not public in the platform class; literal constant used.
- Grants live in memory in the gate (a process restart loses them: safe direction). `clock` is wall time; a monotonic clock may be injected; a clock set back gives NOT_YET.
- Not thread safe (the gate is used from one coroutine scope); note if sync and UI both call it.

## Not done / needs a device or real account
- Operation-bound Keystore HMAC signature through a CryptoObject on Android 11+ (15 10.2): needs a device; not built. `setUserAuthenticationRequired(true)` Keystore device key (6 h window) and iOS `WhenPasscodeSetThisDeviceOnly` items belong with S4b-BL-125/-131 key storage; `AndroidLockLostDetector.keyProbe` is the seam for "key invalidated".
- Browser WebAuthn PRF implementation of `PrfAuthenticator` (`navigator.credentials`, `prf` extension, `userVerification: required`): not wired; Firefox/Safari support to be checked on real browsers (TC-M-50).
- Real prompts: TC-M-50, TC-M-51, I13; emulator test by `AppLockEmulatorTest`'s rules not written. `IosDeviceAuth` compiled only (error codes via `error?.code` unverified on a device).
- No Compose/Angular UI, no notification on Android for a paused background run, no wiring into the Drive services (the callers: S4b-BL-117/-116/-119 must call `beforeRun`/`authorize`/`redeem`).

## Doc rows to add (later pass)
- docs/15 9/10: a section "What S4b-BL-127 built" (above); docs/06: TC-U rows for `DeletionPolicyVectorsTest`, `DriveGateTest`, web `delete-policy.spec.ts`, `prf-seal.spec.ts`; docs/schemas/README: section 6.4 `delete-policy-vectors.json` (format `doorprints-delete-policy-vectors/1`) + change-log row; docs/10 S4b-BL-127 status; docs/02 T-E11/T-I39 mitigations (lock loss reaction has no Drive access; PRF-only website).
- Add the owner's rule to S4b-BL-126/-119: callers must pass a fresh backup count; unknown = last.

## Needs a real device/account to verify
Android 8-15 prompt behaviours (API 26-28 launcher, 29, 30+), lockout, lock removal -> `REMOVED`; iPhone passcode removal; Windows Hello/Touch ID PRF output stability across page loads.
