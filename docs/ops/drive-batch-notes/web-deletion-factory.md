# Web Deletion Factory Implementation Notes

## Security Analysis (Pre-coding)

**What can someone with Drive write access to the folder do?**
- View encrypted backup files
- Upload files to the folder
- Modify file properties
- Delete files from Drive (but not through our deletion service without authorization)

**Defenses built in:**
1. **Authorization gate**: All L2/L3 deletions require a genuine, fresh, operation-bound token from WebAuthorizer
2. **PRF-based authentication**: L2/L3 deletions need WebAuthn PRF evaluation (user verification required)
3. **Token expiration**: Tokens expire in 60 seconds, one-use only
4. **Operation binding**: Each token is bound to a specific operationId (derived from level, rootId, and item ids)
5. **Time validation**: Plans are checked for staleness (max age: typically minutes)
6. **Persistent deletion store**: Resumed deletions are verified against persisted state
7. **Platform isolation**: Website L2/L3 requires PRF support; policy refuses without it

## Implementation Tasks

1. **WebAuthnPrfAuthenticator** - Implement PrfAuthenticator seam
   - Use navigator.credentials.create/get with 'prf' extension
   - User verification REQUIRED
   - Feature-detect support
   - Persist credential ID in runtime.db
   - Return NOT_SUPPORTED when unsupported or no passkey registered

2. **SessionHolder** - Wire root folder ID
   - Create in factories/runtime.ts
   - Set on Ready event
   - Used by deletion adapter to get rootId

3. **deletion-factory.ts** - Lazy factory pattern
   - createDeletionAdapter(rt): DriveDeletionAdapterImpl
   - createLazyDeletionAdapterProxy(getRuntime): DriveDeletionAdapter
   - Wire DriveDeletionService with authorization gate
   - Wire PersistentDeletionStore with IndexedDB

4. **drive-connect.providers.ts** - Replace stub
   - Use createLazyDeletionAdapterProxy pattern
   - Inject getRuntime() result

5. **Tests** (deletion-factory.spec.ts)
   - L1 delete works without passkey on fake drive
   - L2/L3 refused without passkey
   - L2/L3 with fake passkey needs tick box
   - L3 needs 5s delay
   - Grants are one-use and expire in 60s
   - Interrupted deletion resumes after reload
   - Feature detection and graceful fallback

## Files to Create/Modify

- Create: web/src/app/data/drive/connect/factories/deletion-factory.ts
- Create: web/src/app/data/drive/connect/factories/deletion-factory.spec.ts
- Create: web/src/app/data/device-auth/web-authn-prf-authenticator.ts
- Modify: web/src/app/data/drive/connect/factories/runtime.ts (add SessionHolder if needed)
- Modify: web/src/app/data/drive/connect/drive-connect.providers.ts (replace stub)

## Status
- [x] SessionHolder type created in runtime.ts
- [x] WebAuthnPrfAuthenticator implemented (web-authn-prf-authenticator.ts)
- [x] deletion-factory.ts with lazy proxy and test helper
- [x] deletion-adapter provider wired in drive-connect.providers.ts
- [x] Unit tests pass (15 tests: factories, L1/L2/L3, passkey, policy decisions)
- [x] tsc clean, ng build clean (no errors)
- [x] Licence headers applied (on all new files)
- [x] Commits pushed to feat/drive-web-deletion-factory

## Test Results
Test Files: 19 passed (255 tests), connect/**/*.spec.ts + device-auth/**/*.spec.ts
Build: successful (no bundle or type errors)

## Files Created
1. web/src/app/data/device-auth/web-authn-prf-authenticator.ts (185 lines)
   - Real browser WebAuthn PRF implementation
   - navigator.credentials.create/get with user verification
   - Credential ID persistence in IndexedDB
   - registerPasskey() for UI enrollment
   - Feature detection and graceful fallback

2. web/src/app/data/drive/connect/factories/deletion-factory.ts (226 lines)
   - createDeletionAdapter(rt, keyValueStore?): builds real adapter
   - createLazyDeletionAdapterProxy(getRuntime): lazy initialization
   - createTestDeletionAdapter: test helper with fake PRF
   - Proper runtime session integration

3. web/src/app/data/drive/connect/factories/deletion-factory.spec.ts (347 lines)
   - 15 tests covering all factory functions
   - L1/L2/L3 deletion policies
   - Passkey requirement checking
   - Tick box and delay validation
   - Online/offline and lock state logic

## Files Modified
1. web/src/app/data/drive/connect/factories/runtime.ts
   - Added SessionHolder interface
   - Added session?: SessionHolder to DriveRuntime

2. web/src/app/data/drive/connect/drive-connect.providers.ts
   - Replaced stub DRIVE_DELETION_ADAPTER
   - Wired lazy proxy with tokenProvider, localStore, crypto

## Commits (feat/drive-web-deletion-factory branch)
1. feat: add WebAuthnPrfAuthenticator for browser-based passkey authentication
2. feat: add deletion-factory with lazy proxy pattern
3. feat: add SessionHolder to DriveRuntime
4. feat: wire DriveDeletionAdapter in provideDriveConnect

## Not Done / Deferred
- Real browser tests (requires browser test runner, currently unit tests with fake)
- Device key sealing (sealed blob returns null; implementation requires key enrollment UX)
- Real authorization gate implementation (currently trusts WebAuthorizer; real checks in S4b-BL-119)
- Full integration tests with Drive API (requires test account)
