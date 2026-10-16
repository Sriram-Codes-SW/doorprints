# Android Drive, F3: the system-browser sign-in with PKCE (docs/15 §5.5, §8 R2)

Notes for the lead. Owner decision of 2026-10-06: **both**. Google Play services stays the default sign-in on Android;
this path is the fallback for phones without Google Play services. Code: `android/app/src/main/java/app/doorprints/drive/auth/browser/`;
tests: the same path under `src/test`. No new library: `ACTION_VIEW` for the browser, `java.net` for the token call.

## What was built

| File | What |
|---|---|
| `Pkce.kt` | Verifier (32 random bytes, base64url, 43 characters), `S256` challenge (RFC 7636 appendix B vector tested), `state` nonce |
| `BrowserRedirect.kt` | One pending request at a time; `onNewIntent(intent)` / `deliver(uri)`; refuses a foreign address, a wrong, missing or repeated `state`, a repeated parameter, a replay (single use), a stale redirect (nothing pending) |
| `TokenEndpoint.kt` | `TokenEndpoint` interface, `HttpTokenEndpoint` (`HttpURLConnection`, form POST, no secret), `TokenResult` |
| `RefreshTokenStore.kt` | `SealedRefreshTokenStore` (A2's `SecretWrapper` + `BlobStore`), `MemoryRefreshTokenStore` |
| `BrowserGoogleAuthorizer.kt` | `GoogleAuthorizer` over the above; `BrowserLauncher`, `ActivityBrowserLauncher`, `BrowserOAuthConfig`, `BrowserPendingConsent`, `BrowserAwareResolver` |
| `ChooseAuthorizer.kt` | `chooseGoogleAuthorizer(context, play, browser)`: Play services when `GoogleApiAvailability` says SUCCESS, else the browser |
| `AndroidManifest.xml` | One intent filter on `MainActivity`: `app.doorprints:/oauth2redirect` (VIEW, BROWSABLE) |

`PlayGoogleAuthorizer` and `AndroidDriveTokenProvider` are untouched: the provider's rules (exactly `drive.file`, no scope is DENIED, a closed
prompt is CANCELLED, token in memory only, revoke clears memory then asks Google) sit above this authorizer, and a test class drives both together.

## How it fits the existing interface

- `authorize([drive.file])` with a stored refresh token refreshes quietly (no screen). With none (or `invalid_grant`) it answers
  `NeedsConsent(BrowserPendingConsent)`; the provider then asks the Activity's `ConsentResolver`. With no resolver (a background run) the
  provider says `CONSENT_REQUIRED` and **no browser ever opens by itself**.
- The resolver opens the browser, waits for the redirect (5 minutes, then `Cancelled`), exchanges the code and returns `Granted`.
- Mapping into `SignInException`: `access_denied` -> a grant with no scopes -> **DENIED**; a token response without `drive.file`
  (granular consent) -> not stored, revoked at Google, no token returned -> **DENIED**; browser left, timeout, a second try or `cancel()` -> **CANCELLED**;
  `IOException` -> **OFFLINE**; no browser, no client id, a 5xx, `invalid_client`, a wrong scope list, any other `error` -> **UNAVAILABLE**;
  a locked Keystore (`NEEDS_UNLOCK`) -> UNAVAILABLE (wait; nothing is cleared); a lost Keystore key or `invalid_grant` -> the sealed file is cleared and the person is asked again.
- `clearToken` does nothing: Google does not keep our access tokens for us to hand out again (unlike Play), the provider already dropped it, and the next `authorize` refreshes.
- `revoke`: the sealed token is read, the file cleared, a pending sign-in cancelled, then Google's revoke endpoint is asked for the refresh token (or the access token if none). A failure there is thrown and the provider swallows it, as for Play.

## Where the refresh token lives, and why

Chosen: **sealed by a Keystore AES-GCM key (`KeystoreSecretWrapper` with its own alias) in a file in `noBackupFilesDir/drive/`** (`FileBlobStore`),
with the AAD `doorprints-drive-refresh-token/1`. This is the A2 seam reused (`SecretWrapper`, `BlobStore`), so the token is never plain on disk, Android
backup never copies it (no-backup directory, `allowBackup=false`), and a restored phone has no key to open it. The wrapper has the 6-hour user-authentication
window of A2 decision 3 (the screen-lock binding §5.5 asks for); a read outside the window is `NEEDS_UNLOCK`, which waits. The device key's own
`WrappedScalarKeyBackend` was not reused because a P-256 scalar is a different secret; only the generic wrap/unwrap fits. If the store cannot keep the
token (no lock, Keystore refuses) the sign-in works for that hour and the next one asks again: memory-only, never plain on disk. Not verified on a device:
`KeystoreSecretWrapper` (Robolectric has no AndroidKeyStore); the tests use an AES-GCM wrapper on the JVM with the same contract.

## What the lead must wire

1. `MainActivity` (exists, `singleTop`): call `driveBrowserRedirect.onNewIntent(intent)` from **both** `onCreate` (`intent`) and `override fun onNewIntent(intent)`; if it returns true, do nothing else with that intent. Only the `app.doorprints:/oauth2redirect` VIEW reaches it; the existing `doorprints://connect` link is unaffected (different scheme).
2. `DriveServices`: one shared `BrowserRedirect()` instance (also reachable from `MainActivity`), then
   ```kotlin
   val browser = BrowserGoogleAuthorizer(
       BrowserOAuthConfig(clientId = { BuildConfig.GOOGLE_ANDROID_CLIENT_ID }),   // see the owner's list; blank = UNAVAILABLE
       redirect, ActivityBrowserLauncher { currentActivity ?: appContext }, HttpTokenEndpoint(),
       SealedRefreshTokenStore(KeystoreSecretWrapper("doorprints_drive_refresh_wrap"), FileBlobStore(File(noBackupFilesDir, "drive/refresh-token.bin"))))
   val authorizer = chooseGoogleAuthorizer(context, play, browser)
   ```
   and the Activity's `ConsentResolver` becomes `BrowserAwareResolver(existingPlayResolver)` so both kinds of consent resolve. Construct `PlayGoogleAuthorizer` only when Play is present if that constructor can throw on a phone without it (the chooser takes both ready-made, as asked).
3. `build.gradle.kts` (not touched here): a `BuildConfig` string field `GOOGLE_ANDROID_CLIENT_ID` from a Gradle property (empty default); no id in the repository.
4. *Disconnect on all devices* should call `AndroidDriveTokenProvider.revokeAccess()` as before; *Cancel* while the browser is open may call `BrowserGoogleAuthorizer.cancel()`.
5. A "Google Drive disconnected" notice on `invalid_grant` is the controller's: the authorizer only asks for consent again.

## What the owner must configure in Google Cloud (docs/15 §2.4; ids are not secrets, no secret is used)

- The **Android** OAuth client of §2.4 (package `app.doorprints`, SHA-1 of the signing key; one for debug, one for release) with **"Enable custom URI scheme"** switched on
  (Google Auth Platform > Clients > the Android client > Advanced settings). With it Google accepts the redirect `app.doorprints:/oauth2redirect` (the package name as the scheme, one slash) for an authorisation-code + PKCE request with no secret.
- Give the client id to the session; it becomes the Gradle property above.
- **Design deviation to confirm:** §5.5 names an App Link on `doorprints.web.app`. Google accepts custom-scheme redirects for Android clients, not https App Links, so this path uses the custom scheme (the redirect is one constant, `BrowserRedirect.DEFAULT_REDIRECT_URI`, and `BrowserOAuthConfig.redirectUri`; the spike S4b-BL-122 confirms Google accepts it). If the spike shows Google refuses it for an Android client, the fallback is an iOS-type or Web client with a different redirect, a change of constants and the manifest filter only.
- In Testing status the refresh token lasts 7 days (§2.3): `invalid_grant` is then normal and asks for consent again.
- Real calls (browser, Google's endpoints, Keystore) are **not verified here**: no network, no device. Only the spike on a phone without Play services proves them.
