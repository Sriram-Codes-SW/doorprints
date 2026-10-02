# Google Drive Web Sign-in Implementation Notes (S4b-BL-117 + S4b-BL-73)

## What was completed

### 1. GoogleTokenProvider implementation
- **File**: `web/src/app/data/drive/connect/google-token-provider.ts`
- Implements TokenProvider interface for Drive auth
- Uses Google Identity Services TOKEN model (google.accounts.oauth2.initTokenClient)
- Scope: `https://www.googleapis.com/auth/drive.file`
- Token lifetime: 1 hour, no refresh token, no secret stored
- Token held in memory only (never localStorage/IndexedDB)
- GIS script loaded lazily only when user initiates Connect
- Handles errors: popup blocked, popup closed, denied, offline
- Injectable ScriptLoader and GoogleConfig seams for testability

### 2. OAuth Client ID Configuration
**How to supply the client ID at deploy time:**

The app reads the Google OAuth client ID from `window.__DOORPRINTS__.googleClientId`. By default it's empty, making the Connect feature report `unavailable` and hidden.

To enable Google Drive Connect:

1. **Create an untracked config file** (`web/public/config.js`):
   ```javascript
   window.__DOORPRINTS__ = window.__DOORPRINTS__ || {};
   window.__DOORPRINTS__.googleClientId = 'YOUR-CLIENT-ID.apps.googleusercontent.com';
   ```

2. **Add to .gitignore** (already should be):
   ```
   web/public/config.js
   ```

3. **Load in index.html** (before the app):
   ```html
   <script src="config.js" defer></script>
   ```

4. **At deploy time** (e.g., in CI):
   - Create `web/public/config.js` with the real client ID from a CI secret
   - The build (Firebase Hosting) serves it beside index.html
   - The app loads and uses it before starting

**Future enhancement**: Modify `.github/workflows/web.yml` to write the config file from a `GOOGLE_CLIENT_ID` CI variable before deploy (not done in this commit per instructions).

### 3. Firebase Headers (S4b-BL-73)
**File**: `web/firebase.json`

Updated headers for GIS popup support:

- **COOP**: `same-origin` → `same-origin-allow-popups` (allows GIS popup window)
- **CSP script-src**: Added `https://accounts.google.com/gsi/client` (GIS script)
- **CSP connect-src**: Added `https://www.googleapis.com` and `https://accounts.google.com` (token/API calls)
- **CSP frame-src**: Added `https://accounts.google.com/gsi/` (GIS iframe)
- **CSP style-src**: Added `https://accounts.google.com` (GIS styles if any)

All existing CSP tests pass (verified with `sw-precache.spec.ts`).

### 4. Comprehensive Tests
**File**: `web/src/app/data/drive/connect/google-token-provider.spec.ts`
- 10 tests, all passing
- Success case: token request and GIS script load
- Caching: token cached within 1 hour
- Error handling: denied, popup_closed, popup_blocked
- Script load failure
- onRejected: clears cached token on 401
- No client ID configuration
- Script loads only once

## What is NOT done (needs real account or device)

1. **End-to-end flow with real Google account**: Pop user directly from button click
2. **Token expiry and silent refresh**: Verify 1-hour expiry, handle silent re-request
3. **Live GIS popup behavior**: Verify popup opens correctly with COOP headers
4. **Integration with DriveClient**: Verify token is used in actual Drive API calls
5. **Multi-language strings**: Labels like "Connect to Google Drive" and error messages marked as under review

## Security notes (crypto review requirements)

**Threat**: User with Drive write access but no folder key could upload bogus data.

**Defenses implemented**:
- Token is ephemeral (1 hour, memory-only)
- No refresh token or secret stored
- GIS popup-only flow, no silent token grant from cache
- Scope is minimal: `drive.file` (only files created by this app)
- All error handling explicit (popup_blocked, popup_closed, denied, offline)

**What still needs crypto review**:
- Interaction with encrypted folder keys during sync
- Data validation before Drive write (handled by sync engine, not here)
- Device pinning before opening any folder from Drive

## Test results

```
✓ TypeScript compiler: web/src/app/data/drive/connect/**/*.ts - PASS
✓ Drive tests (merged branch):
  - All 17 test files PASS
  - 309 tests PASS
✓ GoogleTokenProvider tests: 10/10 PASS
✓ CSP/COOP headers in sw-precache.spec.ts: 27/27 PASS
```

## Files created/modified

Created:
- `web/src/app/data/drive/connect/google-token-provider.ts` (235 lines)
- `web/src/app/data/drive/connect/google-token-provider.spec.ts` (203 lines)

Modified:
- `web/firebase.json` (CSP + COOP headers)

All source files have AGPL license headers via `python3 .github/scripts/licence-headers.py --fix`.

## Open questions / Next steps

1. **CI workflow update**: Not modified in this session per instructions, but should write `config.js` from `GOOGLE_CLIENT_ID` secret
2. **Translation strings**: "Connect" and error messages marked under review in docs
3. **Real account testing**: Once CI is set up with a test Google client ID
4. **Sync integration**: How GoogleTokenProvider feeds into DriveClient in the actual backup/sync flow
