# Web Drive Connect Wiring - Session Notes

**Date:** 2026-10-02  
**Task:** Wire up provideDriveConnect() and adapter providers to make the Drive card on Your data page work  
**Branch:** feat/drive-web-page (worktree: /tmp/wt-wc2)

## Threat Model (10 lines)

An attacker with Drive write access but no folder key could:
1. Upload malicious backup with invalid encryption wrapper or inner structure
2. Modify doorprints.json to point to a fake keys.json folder
3. Re-upload keys.json with different device public key (revocation attempt)
4. Delete Backups/ folder to trigger "folder gone" state
5. Stale token allows replay of operations

**Defenses:**
1. Device pinning before opening anything from Drive (backup-adapter.ts)
2. Fail-closed validation: MAC verified, bytes checksummed, dpx/1 header checked
3. Watermark compare-and-set in DriveStateStore tracks newest createdAt
4. No revoke/wipe on Drive errors (drive-deletion-rules.ts)
5. Operation-id token binding, gate checks genuineness

## Current State

✓ Stores: drive-db.ts, device-key-store.ts (25 tests passing)
✓ Local sync: local-rows.ts, backup-source.ts, import-sink.ts
✓ Adapters: backup-adapter.ts, sync-adapter.ts, deletion-adapter.ts (classes exist with specs)
✓ FetchDriveClient: implemented with fetch + token provider
✓ Google token provider skeleton exists

✗ **MISSING:** provideDriveConnect() not registered in app.config.ts
✗ **MISSING:** The three adapter injection tokens never provided with real factories
✗ **MISSING:** config.js loading in index.html
✗ **MISSING:** Component injection of service with {optional: true} (silently broken)

## Step-by-step plan

1. [IN PROGRESS] Understand current implementation state (services, factories)
2. Build provider factories for the three adapters
3. Register provideDriveConnect() in app.config.ts
4. Fix component injection (remove {optional: true}, add state='Unavailable' when no config)
5. Load config.js in index.html (handle 404 gracefully)
6. Write comprehensive specs
7. Run tsc, ng test, ng build, check bundle sizes
8. Commit/push each step

## Known Issues

- Device ID derivation: sync-adapter expects deviceId as hex of key ID; need to verify it matches backup code
- LocalRows TODO: collectRecordTypes() not implemented (returns empty set)
- ByteSink write() must be sync or return void | Promise<void>

## Completed

- [x] Read backup service implementation to understand dependencies
- [x] Check Google token provider for complete OAuth flow
- [x] Implement adapter provider factories (with TODOs for full wiring)
- [x] Update app.config.ts to register provideDriveConnect()
- [x] Fixed TypeScript errors in drive-db.ts (naming conflict in MemoryDeletionStore)
- [x] Build successful (ng build completed)
- [x] Remove {optional: true} from component injection
- [x] Add GoogleConfig param to DriveConnectService
- [x] Service.connect() checks if configured, returns early if not
- [x] Add tests for missing Google config (2 new tests, 16 total passing)
- [x] Load config.js in index.html with onerror fallback
- [x] Verify app gracefully handles missing config.js

## Commits pushed

1. `feat: register provideDriveConnect()` - wired providers, registered in app.config.ts
2. `fix: remove optional injection` - component always injects service
3. `test: add tests for missing Google config` - 16 tests passing
4. `feat: add config.js loading` - config.js loads from public/ or fails gracefully

## To Do (for next session)

- [ ] Wire real implementations for sync/deletion adapters
- [ ] Complete DriveBackupService wiring with all dependencies (TODOs marked)
- [ ] Add integration tests for adapters
- [ ] Verify bundle size impact
- [ ] Handle state transitions when config changes (if needed)
