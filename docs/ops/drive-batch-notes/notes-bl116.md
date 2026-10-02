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

## 2. STATE AT END (2026-10-02, second pass)

Branch `feat/drive-backups` (worktree /tmp/wt-db), stacked on `feat/drive-crypto-core`. No PR.

DONE
- Kotlin core (commonMain `drive/backup/`) now tested; compiles for commonMain metadata (`:shared` and `:ui`), Android host tests and the iOS simulator klib (`:shared:compileKotlinIosSimulatorArm64`, only pre-existing warnings).
- Kotlin tests (androidHostTest `drive/backup/`): `BackupVectorsTest` (3: constants, 19 retention, 18 schedule from `docs/schemas/backup-vectors.json`), `BackupMetaAndControlTest` (12: MAC covers every field and the file hash, non-canonical metadata, control MAC / rollback / fork / downgrade / forged / CAS), `DriveBackupServiceTest` (29, on `InMemoryFakeDrive` + `JvmCryptoProvider`): round trip, resumable upload with a dropped chunk, upload that never finishes, checksum mismatch, partial never listed and healed, stale junk binned after 24 h, retention exact sets over 40 daily runs, shrink guard hold and confirm, plain/planted/tiny/older/moved-metadata/copied files, delete-all mark and missing newest, refused imports (edited after listing, changed during download, gone, header mismatch, plain ZIP) with the staging discarded each time, new device not adopted, wrong recovery key, keys.json rollback, control rollback/forgery, missing control rewritten, folder gone/without keys, unfinished create, offline/quota/unauthorized, failing source. A mutation (skipping the MAC check in the lister) made the suite fail.
- Tests found no bug in the service code; no service code changed in this pass.
- TypeScript twin `web/src/app/data/drive/backup/`: backup-retention.ts, backup-schedule.ts, backup-meta.ts, control-file.ts, backup-names.ts, backup-listing.ts, drive-backup-seams.ts, drive-backup-results.ts, drive-backup.service.ts, drive-import.service.ts (same names, Promises; `selectRetention`, `decideBackup`, `backupName` are the function forms). Specs: backup-vectors.spec.ts (same vectors file), backup-meta-control.spec.ts, drive-backup.service.spec.ts (same 29 scenarios on the web fake Drive with `WebCryptoProvider`), helper backup-test-rig.ts (test only). `npx ng test --watch=false --include='**/data/drive/backup/**/*.spec.ts'`: 3 files, 44 tests pass. `tsc -p tsconfig.app.json` clean.

NOT DONE
- Read me.txt (D11), `Photos/` in backups (D9), wiring in screens/workers (S4b-BL-117/118), the web `openBackup` hand-off of the staged Blob (the import service writes into a `StagingSink`; the web caller builds the Blob).
- Full CI sequence, `tools/check.sh`, reviews (owner process: later pass). Android emulator / Roborazzi untouched.

## 3. Cross-branch rule from the deletion service (feat/drive-deletion, DeletionRules.kt)

A file counts as Doorprints-owned only with a known `kind` AND in the folder that kind belongs to (`backup` in Backups/, `keys`, `control`, `readme` in the root). This branch matches: keys.json (`kind=keys`) and doorprints.json (`kind=control`) are uploaded into the root, every backup (partial or complete) carries `kind=backup` and is created inside Backups/. Tests: `filesCarryTheKindsAndLiveWhereTheDeletionRulesExpectThem` (Kotlin), `writes the kinds and places the deletion rules expect` (web). No mismatch. Still open for the connect ticket (S4b-BL-117): Read me.txt must carry `kind=readme` in the root, or it is foreign and keeps the root folder. Also: the deletion service says another device learns of "Delete all backups" through `backupsDeletedAt` in doorprints.json; this branch reads and honours it (`ControlFile.next(..., backupsDeletedAt)` writes it; nothing here sets it yet, that is S4b-BL-119's call).

## 4. Needs a REAL Google account or device (owner)

sha256Checksum present and correct on every uploaded file immediately after upload (the lister ignores a file without it as NO_CHECKSUM until the next listing); appProperties limits (all keys+values here are under 124 bytes: `bm` is 44 chars) accepted; files.list by appProperties on Backups/ and for a just-written file (the code asks for the remembered id too); trashed files hidden by `trashed = false`; update of name + state in one call; resumable upload above 5 MB with a real network drop; Drive web UI showing the names; a second device listing; Manage versions rollback of keys.json and doorprints.json refused on the device. Web only: the Drive CORS / COOP behaviour of `FetchDriveClient` for these calls (S4b-BL-122 spike).

## 5. Open questions / follow-ups (unchanged from §2 of the first pass, plus)

- Control-file epoch after a revoke: a revoked device's old key can still MAC a control file (denial only via backupsDeletedAt); require control epoch >= latest revoke epoch once S4b-BL-126 rewrites it.
- Planted older keys.json confusing a fresh device's "oldest listed" choice = denial only.
- Healing a partial counts as this device's write (no check of who wrote it): fine, it is MAC-verified.
- The Kotlin `Scratch.read` is synchronous, the web `Scratch.read` is async (OPFS later); same names otherwise.
- `DriveDeviceState.confirmedDrops` is a Set in Kotlin and an array in TypeScript.
- web `backup-names.ts` is a separate file (Kotlin keeps `BackupNames` in BackupListing.kt).
- Security rules of the crypto review, checked by reading the service: no keys.json/Drive error kind revokes, re-keys, wipes or re-creates (connect maps every KeysException to a connection state, createFolder refuses an existing pinned folder); data is written under a key only after `createFirstDevice`'s keys.json was read back (`uploadSmall` read-back) and the pin is last; a device is pinned (guard.watermark non-null, or the three named first-pin paths) before it opens anything. A revoke is not in this ticket (S4b-BL-126). No change needed in `crypto/` (Kotlin or web); the two HKDF labels live in `BackupMeta.kt` / `backup-meta.ts` (D3), moving them into FolderKey is optional.

Doc rows to add later (owner's later pass): docs/15 §7 phase 3 "what S4b-BL-116 built" (D1..D11), docs/schemas README (backup-vectors.json, doorprints-control/1, backup metadata), docs/06 TC-U rows for the tests, docs/02 (A1..A17 threats; RR-26 createdAt is the writer's claim; control forged by a revoked device's old-epoch key can hide backups via backupsDeletedAt = denial only), docs/10 S4b-BL-116 status, CHANGELOG.

Needs a REAL Google account (owner): sha256Checksum present and correct on every uploaded file, immediately after upload; appProperties size limits (bm 46 bytes) accepted; files.list by appProperties on Backups/; trash and listing of trashed files; updateMetadata rename + state in one call; resumable upload > 5 MB; Drive web UI showing names; a second device listing; *Manage versions* rollback of keys.json and doorprints.json refused on the device.

Open questions: control-file epoch after a revoke (a revoked device's old key can still MAC a control file: require control epoch >= latest revoke epoch once S4b-BL-126 rewrites it?); planted older keys.json confusing a fresh device's choice (oldest listed) = denial only; should healing a partial count as this device's write.
