# Drive Web Fixes Notes

## Security considerations (pre-coding)
What an attacker with Drive write access but no folder key could do:
- Create a fake keys.json and overwrite the existing one
- Create a fake control.json to manipulate device state
- Create duplicate folder roots or keys to confuse the app

Defence built into ITEM 1 (folder-trust-stores.spec.ts):
- CAS watermark checks prevent FORK_DETECTED when trying to replace a pinned keys.json with a forged one
- Byte array preservation ensures JSON-turned-objects don't slip through sameWatermark checks
- Two-phase read and verify pattern (real KeysFile + KeysGuard with PIN) rejects forged data

Defence built into ITEM 2 (Web Locks + createFolder):
- Atomic folder creation under exclusive lock prevents two tabs from creating two roots
- Re-read pattern on lock acquire ensures second tab finds first's folder and skips creation

## Items

### ITEM 1: TEST GAP (folder-trust-stores)
Export DbFolderTrustStores and write factories/folder-trust-stores.spec.ts with 5 test vectors:
- (a) keys.load null on empty, CAS behavior (prevents lost-update) ✓
- (b) Uint8Array preservation and sameWatermark validation ✓
- (c) control.load/CAS with null backupsDeletedAt and numbers ✓
- (d) rootId isolation ✓
- (e) CAS watermark fork detection after reload ✓

Status: COMPLETE
Tests: 5 passed (mutations check the compare logic and hex encoding)

### ITEM 2: RACE (Web Locks in createFolder)
Add LockRunner seam, inject into DriveBackupService, wrap createFolder in exclusive lock,
ensure second tab re-reads state/Drive root and returns correct result type.

Status: COMPLETE
- Created LockRunner interface with WebLockRunner implementation
- Added feature detection for Web Locks API with fallback to unlocked
- Injected into DriveBackupService with default WebLockRunner()
- Wrapped folder creation in navigator.locks.request('doorprints-drive-create', ...)
- Second tab re-reads state and finds pinned folder, adopts it via this.ready()
- Tests with FakeLockRunner verify: single folder creation, proper state tracking, 
  recovery key only on first call, second call returns null for recovery key

Tests: 3 passed (concurrent createFolder, single folder, fallback behavior)

## Test Results

All drive module tests: 34 test files, 431 tests PASSED
New tests: 
- folder-trust-stores.spec.ts: 5 tests passed
- drive-backup-race.spec.ts: 3 tests passed

Build: Success (ng build completed)
TypeScript: No errors (tsc clean)
Licence headers: 1165 source files, all with notice

## Files Modified/Created

1. web/src/app/data/drive/connect/factories/runtime.ts
   - Exported DbFolderTrustStores class

2. web/src/app/data/drive/connect/factories/folder-trust-stores.spec.ts (NEW)
   - 5 comprehensive test vectors for DbFolderTrustStores

3. web/src/app/data/drive/backup/lock-runner.ts (NEW)
   - LockRunner interface and WebLockRunner implementation

4. web/src/app/data/drive/backup/drive-backup.service.ts
   - Added LockRunner injection with WebLockRunner default
   - Wrapped createFolder in locks.request()
   - Added logic to re-read state and adopt existing pinned folder

5. web/src/app/data/drive/backup/drive-backup-race.spec.ts (NEW)
   - 3 tests for race condition prevention
   - FakeLockRunner for serializing concurrent calls
   - InMemoryKeyValueStore for proper watermark persistence in tests

## Commits
1. test: DbFolderTrustStores export and comprehensive folder-trust-stores tests
2. feat: Web Locks in DriveBackupService.createFolder to prevent race conditions
