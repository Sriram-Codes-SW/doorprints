# S4b-BL-116 notes: backups to Google Drive and Import from Drive

Branch `feat/drive-backups` (worktree /tmp/wt-db), stacked on `feat/drive-crypto-core`. No PR (owner process for this batch).

## 0. Adversarial pass (written before coding)

Attacker A: can WRITE anything to the person's Drive folder (has the Google account, or any app the person authorised
with `drive.file` on our public client id), but holds **no folder key** (not an enrolled device, no recovery key).

What A can do, and the defence built:

| # | Attack | Defence |
|---|---|---|
| A1 | **Plant a plain file** named `Doorprints-backup-….dpx` with `appProperties kind=backup state=complete` | A file counts as a backup only when its **backup metadata MAC** (appProperties `bm`, HMAC under a key derived from the folder key of its epoch) verifies over the metadata *and Drive's own `sha256Checksum`*. No folder key, no MAC: never listed, never imported, never counted by retention or the shrink guard. Import decrypts again (dpx/1, fail closed: a plain ZIP is `NOT_DPX`). |
| A2 | **Swap an older backup in as the newest**: change `createdAt` / name / Drive `modifiedTime` of an old genuine backup | "Newest" is decided by the **authenticated `createdAt`** (inside the MAC), never by Drive's `modifiedTime`, `createdTime` or the name. Changing the value breaks the MAC. |
| A3 | **Move metadata from one file to another** (copy the `bm` of a large backup onto a small one) | The MAC covers the ciphertext SHA-256 as Drive computed it (`sha256Checksum`), so metadata only verifies on the exact bytes it was made for. Import re-checks the downloaded bytes against that checksum, then decrypts. |
| A4 | **Replay**: copy a genuine old backup (bytes and appProperties) as a new file | It verifies, but keeps its old authenticated `createdAt`: it is an *old* backup, not a new one. Duplicates (same ciphertext SHA-256) are collapsed in listing and pruned by retention. |
| A5 | **Replay after "Delete all backups"** (bring files back from the bin) | `doorprints.json` (MACed control file) carries `backupsDeletedAt`; backups with `createdAt <= backupsDeletedAt` are ignored. (Written by S4b-BL-119; read and honoured here.) |
| A6 | **Delete** backups (or all of them) | Cannot be prevented with write access. Detected: the device keeps the newest authenticated `createdAt` it has seen (`newestSeenAt`) and the id of its own last backup; a listing whose newest is older reports `missingNewer = true` ("Your newest backup is no longer in Google Drive"). Never acts destructively on it. |
| A7 | **Flip `state=complete` to `partial`** on a genuine backup so the partial clean-up deletes it | The clean-up only trashes a partial file **whose MAC does not verify** (and only after 24 h, to Drive's bin). A partial file whose MAC verifies is a genuine upload that missed its final update: it is *completed* (renamed, `state=complete`), never deleted. |
| A8 | **Flip `state` to `partial`** to hide a backup | Hidden from the list until the next backup run heals it (A7). Same as a delete in effect; detected by A6. |
| A9 | **Replace `keys.json`** with a forged list (own folder key wrapped to the listed public keys) or **roll it back** with Drive's *Manage versions* | `KeysGuard` (S4b-BL-125): the pin. Forged list: `PIN_MISMATCH`/`FORK_DETECTED`; older revision: `ROLLED_BACK`. The service maps each to `Error(KEYS, kind)` and writes nothing. |
| A10 | **Roll back `doorprints.json`** (e.g. to a copy without `backupsDeletedAt`) | Its own watermark (revision, body hash) on the device, compare-and-set; lower revision `CONTROL_ROLLED_BACK`, same revision other body `CONTROL_FORK`. |
| A11 | **Forge `doorprints.json`** | MACed under HKDF(folder key of its epoch, `doorprints/dpx1/control`); refused (`CONTROL_INVALID`). |
| A12 | **Downgrade**: plant an unencrypted ZIP and hope the import takes it | Import only takes `dpx/1` with inner `doorprints-backup/<n>`; the plaintext is handed to the existing import path only after every chunk authenticated and the checksums matched. |
| A13 | **Tamper with a backup's bytes** (flip a bit, truncate) | Drive's `sha256Checksum` changes, so the MAC (A3) fails: not listed. If A races between list and download: `downloadVerified` checks bytes vs the listed checksum, then dpx/1 authenticates every chunk (fail closed, no plaintext). |
| A14 | **A file from another Doorprints folder** (another account, another key set) | MAC and content key under another folder key: not listed, `KEY_UNWRAP_FAILED` if forced. |
| A15 | **Wrong epoch / revoked writer**: a file claiming an epoch it was not written under, or written by a revoked device | The metadata names epoch and writer kid inside the MAC; at import the dpx header must have the same epoch and kid (`headerCheck`), and `RevokedEpochRule` is applied with the authenticated `createdAt`. |
| A16 | **Plant a folder** `Doorprints` before the person's first connect | First connect to an existing folder is never adopted: `NEEDS_ENROLMENT` (join by QR or recovery key), nothing is written. |
| A17 | **Make the shrink guard fire forever** (plant a tiny backup) | Only authenticated backups count, so A cannot plant one. |

What an *enrolled* device (or a thief with an old epoch key before a revoke) can do is out of scope here (docs/15
§9.3: every enrolled device is fully trusted; RR-26 the `createdAt` is the writer's own claim, so a revoked device
could backdate a file under its old epoch: `RevokedEpochRule` accepts it as an old file; same limit as sync files).

## 1. Decisions (default unless the owner objects)

(filled while building, below)

Decisions so far:
- D1 Backup metadata in `appProperties` (createdAt, houses, epoch, kid) with MAC `bm` = HMAC(HKDF(folderKey(epoch), "doorprints/dpx1/backup-meta"), "doorprints-backup-meta/1"‖0‖u64 createdAt‖u32 houses‖u32 epoch‖kid16‖sha256 of the dpx file). Bound to Drive's sha256Checksum. Listing needs no download.
- D2 `doorprints.json` = `doorprints-control/1` {revision, epoch, createdAt, encryption "dpx/1", backupsDeletedAt}, MAC under HKDF label "doorprints/dpx1/control"; own watermark (revision, body hash, backupsDeletedAt) with CAS; missing file rewritten by a pinned device keeping the watermark's backupsDeletedAt.
- D3 The two HKDF labels live in `drive/backup/BackupMeta.kt` (`BackupKeys`, via the crypto module's internal `Hkdf`), NOT in crypto/FolderKey.kt (coordinator: do not touch crypto dirs). Coordinator may want them moved into FolderKey later.
- D4 Shrink guard rule: hold when newest.houses*2 < max houses of earlier backups, counted back to (and including) the newest confirmed backup; confirmation = backup id in `confirmedDrops` (per device, last 32 kept).
- D5 Retention GFS: newest of each of the newest 7 local days / 4 Monday weeks / 6 months, plus the newest; union (13 for a year of dailies, at most 17). Days at the device's UTC offset at prune time. Ties by id.
- D6 Schedule: daily 24h after last success; RETRYABLE waits 30 min, QUOTA 24 h, UNAUTHORIZED/BLOCKED wait (manual runs anyway); clock moved back (>5 min ahead) = due; weekly verify flag.
- D7 Partial files: a MAC-valid partial is completed ("healed"), never deleted; a non-verifying partial is binned after 24 h (Drive createdTime); duplicates (same sha) binned; pruning goes to the bin (trash), 404 = done.
- D8 createFolder pins LAST (after keys.json + doorprints.json read back + Backups/), so a half-done create leaves nothing trusted; a retry by the same device (creatingRootId) bins its own orphan keys/control files and starts again. Edge: a crash after pinCreated but before the caller shows the recovery key loses that key (recovery entry exists nobody saw) -> ticket.
- D9 Drive backups still carry photos (today's Full backup ZIP) until Photos/ exists (S4b-BL-118); "without photo bytes" deferred there. Inner format = the ZIP's manifest format (doorprints-backup/1..3).
- D10 Recovery path lists this device in keys.json (addDevice approved by the recovery kid) so other devices accept its backups (RevokedEpochRule SKIP_UNKNOWN_WRITER otherwise).
- D11 Read me.txt (four languages) not written: needs hi/ta/te strings; left to the connect ticket S4b-BL-117.

## 2. STATE AT STOP (2026-10-02, owner near usage limit)

Branch `feat/drive-backups` = origin/feat/drive-crypto-core e81c6e1 (fast-forwarded, includes the new-API commit) + one WIP commit.

DONE (Kotlin commonMain, compiles: `:shared:compileCommonMainKotlinMetadata` OK; NOT yet compiled for Android/iOS targets, NO tests yet):
- `android/shared/src/commonMain/kotlin/app/doorprints/drive/backup/`: BackupRetention.kt, BackupSchedule.kt, BackupMeta.kt (+BackupKeys), ControlFile.kt, DriveBackupSeams.kt (DeviceIdentity, DriveDeviceState, DriveStateStore, FolderTrustStores, BackupSource/BackupPayload, Scratch/ScratchSpace/MemoryScratchSpace, StagingSink), DriveBackupResults.kt (DriveProblem, DriveConnection states, ReadyFolder, CreateOutcome, DriveBackup, BackupListing, TidyReport, BackupOutcome, ImportDownload), BackupListing.kt (BackupNames, BackupLister), DriveBackupService.kt (connect, createFolder, openWithRecoveryKey, openWithFolderKey, backUp, listBackups, confirmShrink, verifyNewest, schedule), DriveImportService.kt (download -> StagingSink, fail closed).
- `docs/schemas/backup-vectors.json`: 19 retention + 18 schedule vectors from an independent Python implementation (generator kept in this scratchpad: gen-backup-vectors.py; not committed).

HALF-DONE: nothing half-edited; the service code is unreviewed and untested (expect fixes when tests run).

NOT STARTED:
1. Kotlin tests: commonTest table tests (retention, schedule); androidHostTest `BackupVectorsTest` (read docs/schemas/backup-vectors.json via crypto test `Vectors.load`); `DriveBackupServiceTest` on InMemoryFakeDrive + JvmCryptoProvider + MemoryWatermarkStore: round trip (create -> backUp -> list -> download -> BackupArchive.open of a CopyWriter ZIP, see ArchiveTest helpers), upload interrupted (FaultScript stopAfter / DropAfter), checksum mismatch (DriveFault.CorruptContent -> CORRUPT, file binned), partial never listed, retention exact sets, shrink guard, keys.json rollback (FakeDriveServer.rollBack) -> KEYS_ROLLED_BACK, tampered backup (editByHand) refused, planted plain file ignored, swapped createdAt -> MAC_INVALID, replay copy -> DUPLICATE, wrong recovery key -> WRONG_RECOVERY_KEY, NeedsEnrolment for an unpinned device, control rollback.
2. Android/iOS compile: `./gradlew :shared:testAndroidHostTest` and `-Pkotlin.native.enableKlibsCrossCompilation=true :ui:compileKotlinIosSimulatorArm64` (check no JVM-only calls; `floorDiv` used, OK in common).
3. The whole TypeScript twin in `web/src/app/data/drive/backup/` (async; Hkdf from crypto/hpke.ts; ByteSource/Sink async; import hands a Blob to pages/data/import-backup.ts `check(blob)`), with `backup-vectors.spec.ts` and the same fake-Drive tests.
4. Review pass on the service (strongest model): security rules from the coordinator (no keys.json error kind triggers revoke/re-key/wipe/re-create; never write under an unconfirmed key; photo/1 needs expectedPlaintextSha256 - not used here).

Doc rows to add later (owner's later pass): docs/15 §7 phase 3 "what S4b-BL-116 built" (D1..D11), docs/schemas README (backup-vectors.json, doorprints-control/1, backup metadata), docs/06 TC-U rows for the tests, docs/02 (A1..A17 threats; RR-26 createdAt is the writer's claim; control forged by a revoked device's old-epoch key can hide backups via backupsDeletedAt = denial only), docs/10 S4b-BL-116 status, CHANGELOG.

Needs a REAL Google account (owner): sha256Checksum present and correct on every uploaded file, immediately after upload; appProperties size limits (bm 46 bytes) accepted; files.list by appProperties on Backups/; trash and listing of trashed files; updateMetadata rename + state in one call; resumable upload > 5 MB; Drive web UI showing names; a second device listing; *Manage versions* rollback of keys.json and doorprints.json refused on the device.

Open questions: control-file epoch after a revoke (a revoked device's old key can still MAC a control file: require control epoch >= latest revoke epoch once S4b-BL-126 rewrites it?); planted older keys.json confusing a fresh device's choice (oldest listed) = denial only; should healing a partial count as this device's write.
