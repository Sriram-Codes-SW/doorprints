# S4b-BL-116/117 notes: web backup adapter

S4b-BL-116 (backups to Drive, Kotlin), S4b-BL-117 (connect ticket). This notes: web adapter design, threat model, decisions, and test coverage.

Branch `feat/drive-web-backup-adapter`, from `origin/feat/drive-web-page`.

## 0. Adversarial pass: what someone with Drive write access but no folder key could do

Attacker A: can WRITE anything to the person's Drive folder (has the Google account or any app with `drive.file` on the public client id), but holds **no folder key** (not an enrolled device, no recovery key). The adapter sits between the service and the screen, wiring up user actions.

| # | What A can do | Defence in the adapter |
|---|---|---|
| A1 | Plant a plain file named `Doorprints-backup-….dpx` with `appProperties kind=backup state=complete` | The adapter lists backups via `DriveBackupService.listBackups`, which calls `BackupLister.list`. The lister checks the **backup metadata MAC** (appProperties `bm`, HMAC under a key derived from the folder key) against Drive's own `sha256Checksum`. No folder key, no MAC: never listed, never shown to user. |
| A2 | Swap an older backup in as newest: change `createdAt` / name / Drive `modifiedTime` | The adapter shows the authenticated `createdAt` inside the MAC, never Drive's `modifiedTime`. It is bound by the MAC, so changing it breaks verification. |
| A3 | Move metadata from one file to another (copy `bm` of a large backup onto a small one) | The MAC covers the ciphertext SHA-256 as Drive computed it (`sha256Checksum`). Metadata only verifies on the exact bytes. Import via `DriveImportService.download` re-checks bytes vs. the listed checksum, then decrypts fail-closed. |
| A4 | Replay an old backup after the person deleted it, or swap it into a different folder | A replayed backup is an *old* backup by its authenticated `createdAt`. The adapter tracks the newest seen (`newestSeenAt`) in device state via the service. Duplicates are collapsed in listing and pruned by retention. Backups from a different folder fail key unwrap (different folder key). |
| A5 | Suppress newly-backed-up files by marking them partial, hiding them from the list shown to the user | The adapter calls `DriveBackupService.tidy` after every backup. Partial files whose MAC verifies are healed (completed, marked `state=complete`). Non-verifying partials are binned after 24 h. A partial with a valid MAC is a genuine upload that needs finishing. |
| A6 | Delete the person's backups entirely | Cannot be prevented by the adapter with write access. The adapter detects this via `missingNewer` flag (the newest authenticated `createdAt` it has seen is gone). Reported to user, never acted on destructively. |
| A7 | Plant a folder `Doorprints` before first connect, then trick the adapter into adopting it | The adapter's `connect()` returns `NEEDS_ENROLMENT` for an unadopted folder (no pin). The service never adopts silently; the adapter must show enrollment UI (QR code or recovery key). First connect never pins without explicit user action. |
| A8 | Make `confirmShrink()` fail to persist, so the user is asked again next time | The adapter calls `confirmShrink` which writes to device state store via the service. State store is the browser's IndexedDB (with same-origin policy). The adapter relies on the service and IndexedDB integrity; no local check can prevent a write refusal. |

The adapter implements no crypto itself and holds no folder keys. All trust is delegated to `DriveBackupService` (docs/15 §1.4, §9.3; trust rule: device is pinned before opening anything; no keys.json error triggers revoke/re-key/wipe).

## 1. Decisions (default unless the owner objects)

- D1 The `DriveBackupAdapter` interface exports the public API as a plain class with constructor-injected dependencies (no Angular DI inside). Matches the Kotlin twin's public surface for wiring in service containers later.
- D2 Every method does the real thing: connects to `DriveBackupService` and `DriveImportService`, maps results to typed errors (e.g., `{ kind: 'ERROR', problem }` to `NotYetError`), shows the actual encrypted file list.
- D3 `connect(tokenProvider)` is async because token refresh and folder lookup may wait. Returns state union: `NoFolder | FolderGone | NeedsEnrolment | NeedsRecoveryKey | Ready | Error`. Mirrors `DriveConnection`.
- D4 `createFolder()` wires `DriveBackupService.createFolder(true)` (recovery key generation always enabled for web). Shows recovery key once via return value `{ recoveryKeyText }` (the key object, not string storage). Device is pinned in the last step; half-done creates leave nothing trusted.
- D5 `openWithRecoveryKey(text)` takes the typed recovery key, calls the service, returns the connection state. Adds this device to the keys.json list if not already there (approved by the recovery key).
- D6 `backUpNow(zipSource)` wraps `DriveBackupService.backUp`, returns `{ kind: 'done' | 'failed' | 'uploaded_shrink_holds' }` with a `confirmShrink()` callback. Typed shrink result: `HOLD` means retention is holding a backup; user must confirm it can be dropped. Offline/quota/unauthorized wait (schedule handles retry).
- D7 `listBackups()` calls `DriveBackupService.listBackups`, returns the list newest-first, each with `createdAt`, `houses`, `name`, `fileId`. Missing-newer flag reported to user ("Your newest backup is no longer in Google Drive").
- D8 `importFromDrive(backupId, sink)` wires `DriveImportService.download`, discards sink on refusal (no partial import shown). Returns `{ verified | refused }` with the ZIP format and plaintext size.
- D9 `writeReadMe(lang)` is a stub (D11 of the service notes: not wiring it until hi/ta/te strings are ready). Returns success; called after `createFolder` to write `Read me.txt` in root with `kind=readme` and appProperties.
- D10 `schedule(enabled, ready)` wraps `DriveBackupService.schedule`, returns a `ScheduleDecision` (daily 24h after success; RETRYABLE waits 30 min, QUOTA waits 24 h, UNAUTHORIZED waits).
- D11 Constructor takes `DriveBackupService`, `DriveImportService`, `decideBackup` function from `backup-schedule`, and `selectRetention` from `backup-retention` if the adapter needs to compute schedules locally (not used in this pass, wired through the service).

## 2. State at end (2026-10-02)

NOT YET DONE
- The file exists with stub method bodies (return NotYetError or success values).
- Tests written but not implemented (WIP after adapter file is complete).
- No full `npx ng test` run yet (will do once file is created).

DONE
- Interface design complete (D1..D11 above).
- Notes written (this file).

## 3. Open questions

- Should `importFromDrive` take the full `DriveBackup` object or just the id? Current: id from list + redundant checks in download (docs/15 §1.4 item 8, fail closed).
- Should `backUpNow` accept a pre-built source (function) or a Blob? Current: function (`BackupSource` from the service).
- Staging sink design: adapter calls `staging.write()` in a loop, `staging.discard()` on refusal. Full buffer in memory or stream to IndexedDB? Current: via `ScratchSpace` from the service.

## 4. Not touched

- `web/src/app/data/crypto/` (owner rule, no crypto changes here).
- `DriveBackupService` and `DriveImportService` (only call and wire).
- The wiring in screens/components (S4b-BL-73 component-side work, separate).
