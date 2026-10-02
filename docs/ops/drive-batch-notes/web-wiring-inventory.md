# Web Drive Feature Wiring Inventory

**Status:** feat/drive-web-page branch  
**Date:** 2026-10-02  
**Purpose:** Complete inventory of what's missing to wire up Google Drive feature on web app

---

## Executive Summary

The web app's Google Drive feature has skeleton implementations for all three adapters (backup, sync, deletion) and related services (backup, import, photos, sync engine), but **the entire dependency injection chain is missing**:

1. `provideDriveConnect()` is defined but **never registered** in `app.config.ts`
2. The three adapter injection tokens (DRIVE_BACKUP_ADAPTER, DRIVE_SYNC_ADAPTER, DRIVE_DELETION_ADAPTER) are **never provided**
3. The `DriveConnectComponent` injects `DriveConnectService` with `{optional: true}`, so it silently shows nothing when missing
4. No routes exist for Drive-related pages
5. No service worker/ngsw config for background sync
6. `config.js` (public Google API config) not loaded in index.html

---

## Part 1: Detailed Inventory Table

| **Dependency** | **Type** | **Interface/File** | **Production Implementation** | **Persistence & Storage** | **Notes** |
|---|---|---|---|---|---|
| **DRIVE_BACKUP_ADAPTER** | Token | `DriveBackupAdapter` / `backup-adapter.ts` | Not yet (skeleton only) | None (plain class) | Needs: DriveBackupService, DriveImportService, DriveClient, DriveStateStore |
| **DRIVE_SYNC_ADAPTER** | Token | `DriveSyncAdapter` / `sync-adapter.ts` | Not yet (skeleton only) | None (plain class) | Needs: FolderSession, DriveClient, PhotoStateStore, LocalRows |
| **DRIVE_DELETION_ADAPTER** | Token | `DriveDeletionAdapter` / `deletion-adapter.ts` | Not yet (skeleton only) | None (plain class) | Needs: DriveDeletionService (from shared), policy decision engine |
| **DriveBackupService** | Service | `drive-backup.service.ts` | Not yet (spec only) | Device state: IndexedDB `'drive-state'` store | Handles folder creation, backup encryption, listing, shrink guard |
| **DriveImportService** | Service | `drive-import.service.ts` | Not yet (minimal) | None (reads during import only) | Downloads + decrypts backup from Drive; output to StagingSink |
| **DriveSyncEngine** | Service | `drive-sync-engine.ts` | Not yet (spec & vectors only) | Sync state: IndexedDB (via LocalRows sync store) | Bi-directional sync logic; needs FolderSession + LocalRows |
| **DrivePhotos** | Service | `drive-photos.ts` | Not yet (skeleton only) | Photo state: IndexedDB `'drive-photos'` store | Handles photo upload queue, WiFi-only policy, resume |
| **DriveDeletionService** | Service | Shared (kotlin/web) | Not yet | Deletion plan cache: IndexedDB `'drive-deletion'` store | References device-auth policy engine; L1/L2/L3 gates |
| **DriveClient** (FetchDriveClient) | Service | `drive-client.ts` | Partial (fetch-based stub) | None (stateless HTTP layer) | Wraps Google Drive API; needs token provider, error handling |
| **GoogleTokenProvider** | Service | `google-token-provider.ts` | Not yet (WindowGoogleConfig stub only) | Tokens: localStorage `'drive-tokens'` or session storage | Handles OAuth, token refresh, revocation |
| **FolderSession** | Interface | `drive-sync-seams.ts` | Not yet | Derived from DriveStateStore + folder metadata | Session state: root, keys, control, backups IDs |
| **DriveStateStore** | Interface | Implemented by adapter | Not yet (in-memory stub only) | **IndexedDB store: `'drive-state'`** (atomic CAS per field) | Device's folder bookkeeping: IDs, last success, failures, confirmed drops |
| **LocalRows** | Interface | `drive-sync-seams.ts` | Not yet (stub returns empty) | **LocalStore methods (partial)** | Must call `allRows()`, `markClean()`, `applyRemote()` on LocalStore |
| **StagingSink** | Interface | `drive-backup-seams.ts` | Not yet (in-memory stub) | **Temp file or memory** | For import: decrypted backup ZIP before validation + import |
| **KeysFile / KeysGuard** (read-only) | Crypto layer | `crypto/keys-file.ts` | Not touched (shared) | **IndexedDB store: `'drive-keys'`** (with watermark) | Stores encrypted folder key set; device-pinning anchor |
| **CryptoProvider (WebCryptoProvider)** | Service | `crypto/crypto-provider.ts` | ✅ **Implemented** | Non-extractable P256 key pairs as CryptoKey objects | HPKE, HMAC, AES-GCM, ECDH operations |
| **GOOGLE_CONFIG** | Token | `google-token-provider.ts` | **Partial:** reads `window.__DOORPRINTS__.googleClientId` | localStorage or env | OAuth client ID + redirect URI from `config.js` |

---

## Part 2: Answers to Specific Questions

### (a) Device's HPKE Key Pair Storage

**Question:** Where would the device's HPKE key pair live? Can a stored non-extractable CryptoKey be used, or does it need raw scalar bytes?

**Answer:**

The **P-256 private key** (used for HPKE decapsulation and ECDH with backup encryption) lives as a **non-extractable `CryptoKey` object** in memory (wrapped in the `WebP256Key` class in `crypto-provider.ts`, lines 112–123).

- **CryptoProvider interface** returns `P256PrivateKey` (an opaque interface with only a `.publicKey` getter returning the 65-byte uncompressed point).
- **WebCryptoProvider implementation** wraps the key as a non-extractable CryptoKey (lines 186–189: `generateKey(..., false, ['deriveBits'])`).
- **Cannot extract:** WebCrypto's non-extractable restriction prevents exporting the private bytes. The key lives **only in memory** during this session.
- **Persistence needed:** The **device's scalar** (recovered from the recovery key, or generated at first connect) must be persisted as **raw 32-byte bytes** somewhere to re-derive the key on reload:
  - Store in **IndexedDB `'drive-keys'` store** (alongside the watermark; see KeysWatermarkStore).
  - On reload, call `p256FromScalar(recoveryOrStoredScalar)` → returns a fresh `WebP256Key` with the same public key.
  
**Implementation approach:** The `KeysGuard` (from shared crypto layer, see `crypto/keys-file.ts`) handles this: it seals the scalar under the folder's encryption and stores the sealed bytes in IndexedDB. No raw key bytes ever touch localStorage or unencrypted storage.

---

### (b) Existing LocalStore Methods for LocalRows

**Question:** Which existing LocalStore methods exist for LocalRows (all rows incl. photos, mark clean, apply remote rows)?

**Answer:**

The `LocalStore` service (`local-store.service.ts`) provides these for the LocalRows interface:

| **Method** | **What it does** | **Usage for Drive sync** |
|---|---|---|
| `allHouses()` | Returns all HouseRecord[] (incl. dirty flag) | `LocalRows.all()` → fetch for backup source |
| `allVisits()` | Returns all VisitRecord[] | Backup source data |
| `allPhotos()` | Returns all PhotoRecord[] with metadata | Photos for backup; download metadata |
| `allRecordsOf(type)` | Returns records of given type (broker, criterion, etc.) | Backup source: questions, criteria, etc. |
| `markHouseClean(id, pushedUpdatedAt)` | Sets dirty=false, syncVersion from server | After successful sync push |
| `markVisitClean(id, pushedUpdatedAt)` | Sets dirty=false | After successful sync push |
| `markPhotoMetaClean(id, pushedAt)` | Clears dirty flag on photo metadata | After sync push |
| `putImported(rows: { houses?, visits?, photos?, records? })` | **Batch atomic write** of imported/synced rows | Main entry for importing from backup or remote sync |
| `putHouseFromServer(dto)`, `putVisitFromServer(dto)` | Writes incoming row with last-write-wins logic | Sync: merge remote into local |
| `dirtyHouses()`, `dirtyVisits()` | Returns only rows with dirty=true | Sync: find what to push |

**For Drive sync**, `LocalRows` interface (`drive-sync-seams.ts`) expects:
```typescript
interface LocalRows {
  all(): Promise<Row[]>;                          // → allHouses() + allVisits() + allPhotos() + allRecordsOf(...)
  photo(): Promise<PhotoRow | null>;              // → single photo for incremental upload
  markClean(rows: Row[]): Promise<void>;          // → markHouseClean(), markVisitClean(), etc. per row
  applyRemote(rows: Row[]): Promise<void>;        // → putImported() or putHouseFromServer() etc.
}
```

**Currently**, the sync adapter has a **stub** that returns empty:
```typescript
this.local = {
  all: async () => [],
  photo: async () => null,
};
```

**To implement:** Wire these to LocalStore methods. The backup source (`BackupSource`) is already implemented in `export.service.ts` → `buildBackupZip()` and can be reused.

---

### (c) How 'Import a backup' and 'Save a copy' Produce/Consume Backup ZIP

**Question:** How do the existing 'Import a backup' and 'Save a copy' code produce/consume the backup ZIP? What are BackupSource and the import sink the Drive services expect?

**Answer:**

#### **'Save a copy' (Export)**

**File:** `export/export.service.ts` and `export/backup-export.ts`

- **Build:** `buildBackupZip()` collects all local data (houses, visits, photos, metadata) into a ZIP.
- **Format:** `doorprints-backup/1` (or `/2`, `/3` for newer features) - JSON manifest + data.json + photos/ subfolder.
- **Checksum:** SHA-256 hex of the ZIP bytes, stored in export metadata.
- **Output:** Blob → downloaded or shared.

**Code flow:**
```
DataPage.run('save') 
  → ExportService.build('backup', options, ...)
    → ExportService.buildBackupZip(bundle, ...)
      → backup-export.ts: buildBackupZip() constructs ZIP entries
      → zip() seals into bytes
      → Blob(bytes, 'application/zip')
```

#### **'Import a backup' (Existing)**

**File:** `export/import.service.ts` and `export/backup-reader.ts`

- **Input:** User picks a ZIP file (via file input on ImportBackupCard component).
- **Parse:** `BackupArchive` (from `backup-reader.ts`) extracts manifest, data.json, photo entries.
- **Preview:** `ImportService.preview()` shows what would be imported, applies last-write-wins logic.
- **Execute:** `ImportService.apply()` → `LocalStore.putImported(rows)`.

**Code flow:**
```
ImportBackupCard (file picker)
  → ImportService.preview(archive)
    → import-plan.ts: preview() computes diffs
  → ImportService.apply(archive, flags)
    → LocalStore.putImported({ houses, visits, photos, records })
```

#### **BackupSource (for Drive backup upload)**

**Type:** `() => Promise<BackupPayload>`

The Drive backup service needs a source of the backup ZIP to upload:
```typescript
interface BackupPayload {
  source: ByteSource;      // Async byte stream (read-only)
  format: string;          // e.g., 'doorprints-backup/1'
  houses: number;          // Count of houses in this backup
  close?: () => void;       // Optional cleanup
}

type BackupSource = () => Promise<BackupPayload>;
```

**Current stub** (in backup-adapter.spec.ts):
```typescript
const mockSource: BackupSource = async () => ({
  source: stringSource('{"manifest": ...}'),
  format: 'doorprints-backup/1',
  houses: 5,
});
```

**To implement for real:**
```typescript
export function createDriveBackupSource(exporter: ExportService, options: ExportOptions): BackupSource {
  return async () => {
    const result = await exporter.build('backup', options, new Date());
    const bytes = new Uint8Array(await result.blob!.arrayBuffer());
    return {
      source: bytesSource(bytes),
      format: BACKUP_FORMAT,  // from backup-export.ts
      houses: result.bundle.counts.houses,
    };
  };
}
```

#### **StagingSink (for Drive import to local)**

**Type:** Interface with write sink + discard

```typescript
interface StagingSink {
  write: ByteSink;              // Async bytes writer
  discard(): void | Promise<void>;  // Called if import fails
}
```

**Current stub:** In-memory buffer.

**To implement for real:**
1. Create temporary IndexedDB blob store or origin-private file system.
2. On success: read back decrypted ZIP → `ImportService.preview()` → `apply()`.
3. On error: call `discard()` to clean up temp file.

---

### (d) Angular Providers, Routes, Navigation Entries Still Needed

**Question:** Which Angular providers/routes/navigation entries still need registering?

**Answer:**

#### **1. app.config.ts — Missing providers**

**Currently missing:**
```typescript
// app.config.ts does NOT include:
// ...provideDriveConnect()
// ...provideBackupAdapter()
// ...provideSyncAdapter()
// ...provideDeletionAdapter()
// ...provideGoogleTokenProvider()
// ...provideFetchDriveClient()
```

**To add:**
```typescript
// app.config.ts
import { provideDriveConnect } from './data/drive/connect/drive-connect.providers';
import { 
  DRIVE_BACKUP_ADAPTER, 
  DRIVE_SYNC_ADAPTER, 
  DRIVE_DELETION_ADAPTER 
} from './data/drive/connect/drive-connect.service';

export const appConfig: ApplicationConfig = {
  providers: [
    // ... existing providers ...
    ...provideDriveConnect(),
    // Provide the three adapters (import from their factories):
    { provide: DRIVE_BACKUP_ADAPTER, useFactory: () => new DriveBackupAdapter(...) },
    { provide: DRIVE_SYNC_ADAPTER, useFactory: () => new DriveSyncAdapter(...) },
    { provide: DRIVE_DELETION_ADAPTER, useFactory: () => new DriveDeletionAdapter(...) },
  ],
};
```

#### **2. app.routes.ts — Route for Drive Connect page (optional)**

Currently, `DriveConnectComponent` is **embedded in DataPage** (line 107 of data-page.ts). No dedicated route needed if it stays there.

**If a dedicated page is wanted later:**
```typescript
// app.routes.ts
{
  path: 'drive-connect',
  title: 'title.driveConnect',
  loadComponent: () => import('./pages/data/drive-connect').then(m => m.DriveConnectComponent),
}
```

#### **3. data-page.html — Navigation entry to Drive Connect**

The Drive Connect component is already included in DataPage (`imports: [..., DriveConnectComponent]`). Just ensure the template includes it and provides a heading/section.

**Current:** `<app-drive-connect />` is likely a card in data-page.html (needs verification).

#### **4. Service Worker / ngsw-config.json**

For background backup schedule (optional, Phase 2):
```json
{
  "dataGroups": [
    {
      "name": "drive-config",
      "urls": ["/public/config.js"],  // Google API config
      "cacheConfig": { "strategy": "performance", "maxAge": "1d" }
    }
  ],
  "navigationUrls": [
    "!/data/**",  // Don't serve from cache for data operations
    "!/api/**"
  ]
}
```

#### **5. index.html — Load config.js**

**Currently missing:**
```html
<!-- index.html -->
<script src="/public/config.js"></script>
<script>
  // Sets window.__DOORPRINTS__.googleClientId, googleRedirectUri
  // from the config.js response (or environment)
</script>
```

**public/config.js** should define:
```javascript
// public/config.js
(function() {
  window.__DOORPRINTS__ = window.__DOORPRINTS__ || {};
  // Loaded from Firebase hosting config, environment secret, or hardcoded in dev
  window.__DOORPRINTS__.googleClientId = '...';
  window.__DOORPRINTS__.googleRedirectUri = 'https://doorprints.web.app/callback';
})();
```

**Note:** GoogleConfig token (`GOOGLE_CONFIG`, `WindowGoogleConfig`) already reads from `window.__DOORPRINTS__.googleClientId` (drive-connect.providers.ts, lines 28–31).

---

## Part 3: Security Threat Model (10-line summary)

**Threat:** An attacker with Drive write access but no folder key could:
1. Upload a malicious backup with valid encryption wrapper but invalid inner structure.
2. Modify `doorprints.json` to point to a fake keys.json folder.
3. Re-upload keys.json with a different device public key (revocation).
4. Delete the Backups/ folder to trigger a "folder gone" state.

**Defenses built into the code:**
1. **Device pinning (backup-adapter.ts §1.4):** The device's public key is part of the folder's authentication chain (keys.json signed by recovery key). A key change is detected on the next read.
2. **Fail-closed validation (backup-adapter.ts, importFromDrive §):** Metadata MAC verified; bytes checksummed; dpx/1 header epoch checked; every chunk authenticated. On any refusal, staging sink is discarded.
3. **Watermark compare-and-set:** The `DriveStateStore` records the newest `createdAt` seen; listing older than it means a file was lost.
4. **No revoke/wipe on Drive errors:** drive-deletion-rules.ts ensures no keys.json or Drive error kind triggers revoke, re-key or wipe.
5. **Recovery key shown once, never stored:** createFolder() returns it to the caller; the adapter never keeps it (backup-adapter.ts line 65).

---

## Part 4: Ordered Build Plan (6 steps, smallest first)

### **Step 1: Set up IndexedDB stores and DriveStateStore implementation**
- **Files to edit/create:**
  - `web/src/app/data/local-db.ts` — add `'drive-state'`, `'drive-keys'`, `'drive-deletion'`, `'drive-photos'` object stores
  - `web/src/app/data/drive/backup/drive-state-store.impl.ts` (new) — implement DriveStateStore interface using LocalDb
  
- **Depends on:** Nothing (foundational)
- **Test:** Unit test: `DriveStateStore` reads/writes/CAS on a fake IndexedDB

---

### **Step 2: Wire DriveClient (FetchDriveClient) with GoogleTokenProvider**
- **Files to edit/create:**
  - `web/src/app/data/drive/fetch-drive-client.impl.ts` (new) — implement DriveClient interface using fetch + token provider
  - `web/src/app/data/drive/connect/google-oauth.service.ts` (new) — OAuth 2.0 code → token exchange
  
- **Depends on:** Step 1 (needs IndexedDB for token storage)
- **Test:** Unit test: FetchDriveClient calls Google API with valid bearer token; refreshes on 401

---

### **Step 3: Create BackupSource factory and wire ExportService**
- **Files to edit/create:**
  - `web/src/app/data/drive/backup/backup-source.factory.ts` (new) — export createDriveBackupSource(exporter, options)
  
- **Depends on:** Nothing (exports already exist)
- **Test:** Unit test: backupSource() returns ByteSource with correct format and house count

---

### **Step 4: Implement LocalRows adapter for sync**
- **Files to edit/create:**
  - `web/src/app/data/drive/local-rows.impl.ts` (new) — wire LocalStore to LocalRows interface
  
- **Depends on:** Nothing (LocalStore already exists)
- **Test:** Unit test: LocalRows.all() returns all houses+visits+photos; markClean() / applyRemote() call LocalStore

---

### **Step 5: Create DriveBackupAdapter factory and register in app.config.ts**
- **Files to edit/create:**
  - `web/src/app/data/drive/connect/backup-adapter.factory.ts` (new) — export function to instantiate DriveBackupAdapter
  - `web/src/app/app.config.ts` — add `{ provide: DRIVE_BACKUP_ADAPTER, useFactory: ... }`
  
- **Depends on:** Steps 1–3 (DriveStateStore, DriveClient, BackupSource)
- **Test:** Integration test: DriveBackupAdapter.connect() reads from DriveStateStore; createFolder() writes

---

### **Step 6: Create DriveSyncAdapter and DriveDeletionAdapter factories, wire all three to DriveConnectService**
- **Files to edit/create:**
  - `web/src/app/data/drive/connect/sync-adapter.factory.ts` (new)
  - `web/src/app/data/drive/connect/deletion-adapter.factory.ts` (new)
  - `web/src/app/app.config.ts` — add DRIVE_SYNC_ADAPTER and DRIVE_DELETION_ADAPTER providers
  - `web/src/app/app.config.ts` — add `...provideDriveConnect()` to providers array
  - `web/index.html` — load `/public/config.js` before the app bootstrap
  
- **Depends on:** Step 5
- **Test:** Integration test: DriveConnectComponent no longer shows "Unavailable"; DriveConnectService is injected and ready

---

## Notes for the next session

- **Android twin:** `android/app/src/main/kotlin/app/doorprints/ui/DriveConnectScreen.kt` already wires its adapters; mirror its DI structure.
- **Crypto layer off-limits:** Do not modify `web/src/app/data/crypto/` or `android/shared/src/.../crypto/` (shared, sensitive).
- **Recovery key:** Never log, store, or transmit the plaintext recovery key; `BackupAdapter.createFolder()` returns it once for the caller to show.
- **Photos persistence:** `DrivePhotos` needs a photo upload queue stored in IndexedDB; tie to the LocalRows photo() method.
- **Integration edge case:** If GoogleConfig.googleClientId is empty or missing, the adapter should degrade gracefully (show "Not configured" state).

