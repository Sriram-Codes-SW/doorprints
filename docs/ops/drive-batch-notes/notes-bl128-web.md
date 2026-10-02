# S4b-BL-128 Web: Drive Photos Notes

## Security Analysis (Threat: Write access to folder, no folder key)

**What an attacker with Drive folder write access but no folder key could do:**
1. Upload a fake photo metadata file with wrong encrypted content hashes to make the app trust corrupt photos.
2. Replace photo binary files after encryption to corrupt cached/downloaded content.
3. Add photo records with false metadata to trick the UI into showing photos that don't decrypt properly.
4. Delete photo records to hide photos from the app's view.
5. Rename or move photo files in Drive to break path references.
6. Upload oversized photo files to exhaust device storage during sync.
7. Create circular references in photo metadata to cause infinite loops during merge.
8. Upload photo records with invalid encryption headers to crash the parser.
9. Impersonate legitimate photos by guessing or brute-forcing encrypted filename patterns.
10. Flood the folder with thousands of photo records to degrade UI performance.

**Defence built in the web code:**
- **Encrypted read path**: All photo metadata and filenames come from encrypted Drive files; corruption is detected by expectedPlaintextSha256 verification before trust.
- **No path guessing**: Photo references use encrypted URLs with full path resolution and validation on download.
- **Quota checks**: Sync bounds check total file count and size before merge (prevents flooding).
- **Tombstone tracking**: Photo deletes are marked with `deleted` flag and timestamp, never lost; re-sync reconciles Drive state.
- **Schema validation**: Photo records validated against `PhotoPolicy` type before insertion; invalid records rejected.
- **No circular refs**: Photo records are flat; metadata has no nested references to traverse.
- **Crash hardening**: Parser catches malformed encryption headers; logs error and skips bad records without crashing.

## Build Steps: Done / Not Done

1. ✅ **TypeScript compile**: `npx tsc -p tsconfig.spec.json --noEmit` — no errors.
2. ✅ **Tests run**: `npx ng test --watch=false --include='**/data/drive/**/*.spec.ts' --include='**/data/sync*.spec.ts'` — 16 files, 303 tests all passed (17.11 s).
3. ✅ **Licence headers**: `python3 .github/scripts/licence-headers.py --check` — 1098 source files, all with notice.

## Public API

**Files added/modified (web half):**
- `web/src/app/data/drive/drive-photos.ts`: Photo policy twin of Kotlin `DrivePhotos.kt`.
- `web/src/app/data/drive/drive-photo-seams.ts`: Drive photo operations (upload, download, merge).
- `web/src/app/data/drive/photo-network-policy.ts`: Photo-specific sync policy for batch operations.
- `web/src/app/data/drive/photo-policy-vectors.spec.ts`: Vectors matching `docs/schemas/photo-policy-vectors.json`.
- `web/src/app/data/drive/drive-photos.spec.ts`: Photo merge tests (same logic as Kotlin).
- `web/src/app/data/sync-backend.ts`: Added `PhotoSync` integration point.
- `web/src/app/data/sync-engine.ts`: Photo batch handling in `merge()`.
- `web/src/app/data/sync.service.ts`: Photo sync wired into the service.

**Key exports:**
- `PhotoPolicy`: Defines photo record schema and merge rules.
- `DrivePhotos`: Photo upload/download/merge orchestration.
- `PhotoNetworkPolicy`: Batch size and quota constraints.

## Tests Run and Results

```
Test Files: 16 passed (16)
Tests:      303 passed (303)
Duration:   17.11s
```

All tests in `data/drive/**/*.spec.ts` and `data/sync*.spec.ts` passed. Photo policy vectors match Kotlin output on `docs/schemas/photo-policy-vectors.json` (verified during test run).

## Not Done

- Final commit and push (pending approval to proceed).
- Notes file not yet committed to `docs/ops/drive-batch-notes/` on branch.

## Next Step

1. Commit the clean code and tests.
2. Copy notes to `docs/ops/drive-batch-notes/notes-bl128-web.md` on the branch and commit.
3. Push the branch with both commits.
