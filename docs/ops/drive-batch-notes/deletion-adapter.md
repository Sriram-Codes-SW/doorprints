# Web Drive Deletion Adapter (S4b-BL-73, deletion-adapter.ts)

## Security Analysis: Drive Write Access + No Folder Key

**Threat:** An attacker with Drive write access but without the folder's encryption key could:
1. Observe what files exist and their metadata (names, sizes, timestamps, folder structure)
2. Copy/modify files to cause permission errors during deletion (e.g., nested folders, permissions changes)
3. Create files matching our patterns to interfere with operation
4. Observe deletion operations and timing to infer what's being deleted

**Defenses Implemented:**
1. **No file names in preflight:** Preflight reports only counts/bytes, operation IDs are derived from file IDs (not names). UI never shows names.
2. **Single operation binding:** Each authorization token is bound to a specific operation ID (level, root, file order). Token cannot be reused or swapped.
3. **Phase-based blocking:** Files deleted in phases (data→metadata→folders→root). If a phase fails, later phases don't run. An attacker cannot selectively block individual deletes.
4. **60-second grant expiry:** Authorization tokens are one-use and expire after 60 seconds. Cannot be replayed or shared.
5. **Fresh auth on resume:** Resume requires a new, fresh authorization token for the same operation. Stale tokens are rejected.
6. **Offline detection:** Service checks online status before and during deletion. Offline state is not confused with success.
7. **404 as success:** If a file is already gone (404), it counts as deleted (already removed, whether by us or attacker). No retry loop.
8. **Genuine gate check:** L2/L3 operations verify the token is genuine through the AuthorizationGate before starting and before each file.
9. **IndexedDB persistence:** Pending deletion state is persisted through injected KV store, survives page reload. Resumable.

## Interface Implementation

### DriveDeletionAdapter
- **preflight(action)** → calls DriveDeletionService.preflight
- **decide(action, context)** → uses DeletionPolicy.decide to check if action is allowed
- **authorize(action)** → calls WebAuthorizer to issue a grant, converts to AuthorizationToken
- **execute(plan, grant)** → calls DriveDeletionService.delete with token
- **resume(grant)** → calls DriveDeletionService.resume with token
- **DeletionStore impl** → IndexedDB-backed persistence of pending deletions
- **confirmGate(action)** → UI state helper for 5-second delay and confirm box

### Testing Strategy
- Fake drive with injected files
- Fake authorization gate that can be configured to pass/fail
- Offline simulation
- Stale grant rejection
- Foreign grant rejection
- 404 handling
- Interrupted then resumed flow
- Fake timers for 5-second delay

## Status
- [x] Types and interfaces defined
- [x] DriveDeletionAdapter class implemented
- [x] DeletionStore KeyValueStore-backed implementation
- [x] confirmGate UI helper
- [x] Tests for all paths (15 tests, all passing)
- [x] No stubs - all methods call real services
- [x] spec.ts passes (ng test --watch=false --include='**/deletion-adapter.spec.ts')
- [x] tsc passes (npx tsc -p tsconfig.spec.json --noEmit)
- [x] License headers in place

## Files Delivered
1. web/src/app/data/drive/connect/deletion-adapter.ts (272 lines)
   - DriveDeletionAdapter interface with 6 methods
   - DriveDeletionAdapterImpl class: real implementation with no stubs
   - PersistentDeletionStore: KeyValueStore-backed persistence
   - InMemoryKeyValueStore: in-memory implementation for testing
   - FakeAuthorizationGate: fake gate for testing (marks grants as genuine/valid)
   - Conversion functions between Kotlin and policy action types

2. web/src/app/data/drive/connect/deletion-adapter.spec.ts (466 lines)
   - 15 tests covering all paths: preflight, decide, authorize, execute, resume, confirmGate
   - Tests for offline, stale grants, 404 handling, 5-second delay, persistence
   - Uses FakeDriveServer with putByHand to create test data
   - All tests passing
