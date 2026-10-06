# Android Drive, A1: token provider and persisted stores (S4b-BL-117, Android half)

What was built, for the lead. All in `:app` (`android/app/src/main/java/app/doorprints/drive/{auth,store}`); `:shared` is untouched.

## Token provider (`drive/auth`)

- `AndroidDriveTokenProvider(authorizer, resolver, nowMs)` implements `:shared`'s `TokenProvider` (`accessToken`, `onRejected`) plus `revokeAccess()`.
- Decisions live here and are tested on a fake `GoogleAuthorizer`; `PlayGoogleAuthorizer` is the thin adapter over `Identity.getAuthorizationClient` (not unit-testable on Linux; needs a device or emulator with Play services).
- Rules (as the website's `google-token-provider.ts`): exactly `https://www.googleapis.com/auth/drive.file`; token in memory only (reused 50 minutes, then asked again; Play answers quietly while the grant holds); an answer whose granted scopes lack `drive.file`, or with no token, is `SignInException.Kind.DENIED` (fail closed); a closed prompt is `CANCELLED`; `OFFLINE`, `UNAVAILABLE`; `CONSENT_REQUIRED` when Google wants a screen and no Activity resolver is registered (a background worker); concurrent callers share one request (mutex); `onRejected` drops the token here **and** calls `clearToken` at Google (Play would otherwise hand the same bad token out again).
- Consent: `AuthorizerResult.NeedsConsent(PendingConsent)` goes to a `ConsentResolver` the Activity implements (`fun interface`, `suspend fun resolve(consent): AuthorizerResult`). The real consent is `PlayPendingConsent(pendingIntent)`; after the Activity launches it and gets the result it calls `PlayGoogleAuthorizer.fromActivityResult(resultCode, data)` and returns that. The provider checks that answer by the same rules. One round only (a second `NeedsConsent` is `UNAVAILABLE`, no loop).
- Revoke: memory cleared first, then `authorizer.revoke(token?, scopes)` (Play: a quiet `authorize` names the account, `clearToken`, `revokeAccess`); a Google failure is swallowed, as the web's.
- `SignInException` is the Android twin of the web's `SignInError`. The DriveConnect controller maps its `kind` to screen words.
- Library: `play-services-auth` 21.6.0 in `libs.versions.toml` and `:app`. It resolves from Google's Maven; offline resolution works once cached (it was not cached at the start, so the first resolution needs network).

## Stores (`drive/store`)

- `DriveFileStores(dir)` bundles `driveState` (`DriveStateStore`), `sync` (`SyncStateStore`), `photos` (`PhotoStateStore`), `trust` (`FolderTrustStores`), `deletion` (`DeletionStore`). Pass `Context.noBackupFilesDir/drive` so Android's own backup never copies a device id or a pin to another phone.
- Storage: one small JSON file per store, written to `<name>.tmp`, fsynced, atomic rename (`AtomicJsonFile`). No DataStore, no Room migration. kotlinx.serialization DTOs carry `"v":1`; a file that is missing, empty, truncated, not JSON, wrong shape or another version reads as **absent** (the empty state; for a watermark: no pin, so `KeysGuard` stays `NOT_PINNED`, fail closed). Unknown JSON keys are ignored; an unknown enum value drops only that entry (photo `bad`, `lastFailure`) except the pending deletion, which becomes absent as a whole.
- Watermarks: `FileKeysWatermarkStore` / `FileControlWatermarkStore`, one file per root folder (the root id is the file name when it is `[A-Za-z0-9_-]{1,100}`, else `h.` + SHA-256 hex, so no id can leave the folder). `compareAndSet(expected, next)` stores only when the stored value equals `expected` (null: nothing stored), under a per-canonical-path lock shared by every instance in the process. Not a cross-process lock (one app process, one writer per store).
- `DeletionStore.recordFinished(marker, forgetFolder)`: the marker is written first; with `forgetFolder = true` a `FolderForgetter` (`FileFolderForgetter` over the three stores) resets the Drive state to just the device id, and the sync and photo state to empty. Both steps repeat safely.
- No secrets in the files: a device id, Drive file ids, watermark hashes. The device private key is not here (`DeviceIdentity`, Keystore, another agent's).

## For the lead to confirm

1. `recordFinished(forgetFolder = true)` forgetting is implemented here (the web's store ignores the flag; the Kotlin contract says to drop the ids). It resets sync and photo state as well as the ids in `DriveDeviceState`.
2. Token reuse of 50 minutes is a guess (`AuthorizationResult` carries no expiry); a 401 corrects it through `onRejected`.
3. A corrupt watermark file reads as no pin and lets `compareAndSet(null, x)` succeed (as the web's IndexedDB would with a lost record); the person's own proof is still needed before `KeysGuard` pins again.
4. `PlayGoogleAuthorizer` is compiled but not run; check on an emulator with Play services (S4b-BL-122 spike).
