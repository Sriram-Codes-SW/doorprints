# Web Drive Stores Implementation - Progress Notes

## Threat Model (10 lines)
An attacker with Drive write access but no folder key could:
1. Upload malicious backup with invalid structure (defended: fail-closed validation, MAC verification, checksummed bytes)
2. Modify doorprints.json to point fake keys.json (defended: device pinning, public key as auth anchor)
3. Re-upload keys.json with different device key (defended: watermark CAS, authenticated read-back)
4. Delete Backups/ folder (defended: watermark tracks newest createdAt, older listing = file lost)
5. Stale token allows replay (defended: operation-id token binding, gate checks genuineness)

## Implementation Plan

### Step 1: Create drive-db.ts
- IndexedDB database 'doorprints-drive' with stores: state, keys-watermark, deletion-pending, photo-state, device-key
- Versioned upgrade pattern (follow local-db.ts)
- In-memory fallback with persistent: boolean flag
- Status: NOT STARTED

### Step 2: Implement persistence interfaces
- KeyValueStore: get, set, delete (for deletion store)
- KeysWatermarkStore: load, atomic compareAndSet (for crypto keys)
- SyncStateStore: load, save (for drive sync)
- DeletionStore: pending, savePending, clearPending, marker, recordFinished
- PhotoStateStore: load, save (for drive photos)
- DriveStateStore: load, save (for backup state)
- Status: NOT STARTED

### Step 3: Create device-key-store.ts
- P-256 key pair generation with WebCrypto (extractable:false for private key)
- Public key exported raw (65 bytes)
- loadOrCreateDeviceKey(): {privateKey: P256PrivateKey, publicKey: Uint8Array}
- deleteDeviceKey()
- Status: NOT STARTED

### Step 4: Tests
- Persistence round trip
- CAS atomicity under concurrent writers
- Upgrade logic
- In-memory fallback flag
- Private key non-extractable
- Status: NOT STARTED

## Files to create
- /tmp/wt-wst/web/src/app/data/drive/connect/stores/drive-db.ts
- /tmp/wt-wst/web/src/app/data/drive/connect/stores/drive-db.spec.ts
- /tmp/wt-wst/web/src/app/data/drive/connect/stores/device-key-store.ts
- /tmp/wt-wst/web/src/app/data/drive/connect/stores/device-key-store.spec.ts

## Done/Not Done
- [x] Create stores directory
- [x] Implement drive-db.ts with all persistence interfaces
- [x] Implement device-key-store.ts with P-256 key pair generation
- [x] Write and pass tests (25 tests, all passing)
- [x] License headers (via .github/scripts/licence-headers.py)
- [x] Commit and push (749a0f6, feat/drive-web-stores branch)

## Implementation Summary

### drive-db.ts
- ONE IndexedDB database 'doorprints-drive' with 5 object stores
- Implementations of all 6 persistence interfaces:
  1. KeyValueStore: Simple get/set/delete for JSON values
  2. KeysWatermarkStore: Atomic compareAndSet for encryption watermarks
  3. SyncStateStore: Sync engine state persistence
  4. DeletionStore: Pending deletions and markers
  5. PhotoStateStore: Photo upload queue and refs
  6. DriveStateStore: Device's folder bookkeeping
- In-memory fallback with persistent: boolean flag (reports false for private windows)
- Versioned upgrade pattern (currently v1, extends for future changes)
- No stubs, all methods functional

### device-key-store.ts
- DeviceKeyStore class with loadOrCreateDeviceKey() and deleteDeviceKey()
- P-256 private key: generated with WebCrypto generateKey(extractable:false)
- Public key: exported as 65-byte uncompressed SEC1 point (0x04 || x || y)
- CryptoKey objects (non-extractable) stored in IndexedDB, not raw bytes
- WebP256Key wrapper implements P256PrivateKey interface (publicKey getter)
- No private key bytes ever stored or extracted to raw form

### Test Coverage (25 tests)
- KeyValueStore: get, set, delete, missing keys
- KeysWatermarkStore: atomic compareAndSet, conflict handling
- SyncStateStore: load/save round-trip
- PhotoStateStore: photo state with refs
- DeletionStore: pending/marker lifecycle, clear operations
- DriveStateStore: device state persistence
- WebCrypto integration: P-256 generation, non-extractable constraint, JWK export

## Notes
- All TypeScript compilation clean (npx tsc -p tsconfig.spec.json --noEmit)
- All tests pass (ng test --watch=false)
- Code follows existing local-db.ts patterns for consistency
- In-memory fallback ensures app works in private browsing mode
- Atomic CAS in KeysWatermarkStore uses single readwrite transaction

