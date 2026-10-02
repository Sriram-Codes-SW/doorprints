# Drive Web Page (S4b-BL-117 Part 2) - Session Notes

## Threat Analysis: Write Access to Drive Folder (Before Code)

**Scenario:** An attacker with write access to the Drive folder but no key material can manipulate the app's behavior.

**Threats and Defences:**

1. **Inject malicious recovery keys** → Recovery key is written ONCE at first connect, signed and confirmed by read-back before accepted
2. **Corrupt backup files** → Backups are encrypted; on read, decryption fails if corrupted (expectedPlaintextSha256 check in photo/1)
3. **Forge envelope metadata** → Envelope protocol requires key confirmation; unconfirmed keys are rejected
4. **Replay old backups** → Each device has unique recovery key and pin; old backups cannot be restored without the correct pin
5. **Trojan backup files** → Backup schema validation happens before import; malformed files are rejected
6. **Trigger revoke/re-key** → Device cannot revoke or re-key; only the user's authentication gesture can trigger those through the UI
7. **Delete all data** → Deletion requires user authentication (passkey/PRF) or L1 authorization gate; cannot be triggered remotely
8. **Modify photos policy** → Policy is local in IndexedDB; no remote update mechanism for user settings

**Defence Summary:** Key material is never trusted without confirmation by read-back. All incoming data (backups, photos) is validated and decrypted. Device auth and deletion gates require user gesture.

---

## Task: Build Google Drive Connect Page (Part 2 of S4b-BL-117)

### Done/Not Done Tracking

**Not started / In progress / Done:**
- [x] Understand existing Drive infrastructure and API boundary
- [x] Define DriveSignIn interface (fake for tests, real on feat/drive-web-connect)
- [x] Create drive-connect directory and base service structure
- [ ] Implement DriveConnectService (orchestration) - placeholder created
- [ ] Build Angular page component with states (Connect, FirstConnect, Ready, NeedsRecoveryKey, NeedsEnrolment)
- [ ] Add UI for recovery key (show once, copy/save, confirm check)
- [ ] Add UI for ready state (back up now, last backup, list, import from drive, toggles)
- [x] Add i18n strings (en/hi/ta/te with "under review" for hi/ta/te) - DONE: 30 keys added to all 4 languages
- [ ] Add tests (service unit tests, component tests)
- [x] Run TypeScript check - passes with no errors on i18n
- [ ] Copy notes to docs/ops/drive-batch-notes/notes-web-page.md

**Commit:** feat/drive-web-page branch created with:
- DriveSignIn interface in web/src/app/data/drive/connect/drive-sign-in.ts
- DriveConnectService placeholder in web/src/app/data/drive/connect/drive-connect.service.ts
- 30 i18n keys in en.ts, hi.ts, ta.ts, te.ts (hi/ta/te marked under review)

### Questions / Blockers

1. GoogleConfig interface: Waiting for feat/drive-web-connect to have the real GoogleConfig or defining DriveSignIn interface
2. Authorization gates: Using existing web/src/app/data/device-auth for L2/L3 passkey/PRF checks
3. "Read me.txt" generation: Writing kind=readme at first connect, four-language content

### Key Files to Check

- Existing Drive services: `/tmp/wt-wc2/web/src/app/data/drive/backup/drive-backup.service.ts`
- Existing Drive deletion: `/tmp/wt-wc2/web/src/app/data/drive/drive-deletion.ts`
- Device auth: `/tmp/wt-wc2/web/src/app/data/device-auth/`
- Existing card pattern: `/tmp/wt-wc2/web/src/app/pages/data/import-backup.ts`

---

## Implementation Plan

1. Define `DriveSignIn` interface in `web/src/app/data/drive/connect/drive-sign-in.ts`
2. Create `DriveConnectService` that orchestrates:
   - DriveBackupService
   - DriveImportService
   - DriveSyncEngine
   - DrivePhotos policy
   - DriveDeletionService
3. Build page component with states:
   - IDLE/HIDDEN (GoogleConfig unavailable)
   - CONNECT (button to connect)
   - NEEDS_RECOVERY_KEY (show recovery key ONCE, copy, confirm, skip with warning)
   - NEEDS_ENROLMENT (message only, QR is a later ticket)
   - READY (all controls)
4. Add i18n keys to en.ts, hi.ts, ta.ts, te.ts
5. Add service and component tests
6. Run checks and build

---

## Final Status: Component and Service Built (Tests Not Done)

**DONE:**
- DriveConnectService state machine with methods: connect(), createFolder(), openWithRecoveryKey(), backUpNow(), listBackups(), importFromDrive(), setAutoBackup(), setPhotosWifiOnly(), uploadPhotosNowOverMobile(), disconnect(), deleteL1/L2/L3()
- DriveConnectComponent with all UI states (Unavailable → Disconnected → Connecting → NeedsRecoveryKey → FirstConnectShowRecoveryKey → NeedsEnrolment → Ready / Error)
- Recovery key shown once with copy button, confirm-saved checkbox, skip with plain warning
- Ready state: back up now, last backup time, backups list, import button, automatic backup toggle, photos Wi-Fi toggle, upload now button, disconnect, L1/L2/L3 delete menus with proper confirmation (L3 has 5s delay tick box)
- All 32 i18n keys (30 driveConnect + 2 common) in en.ts, hi.ts, ta.ts, te.ts
- TypeScript check passes: `npx tsc -p tsconfig.spec.json --noEmit`
- Angular build passes: `npx ng build`
- Licence headers: OK
- Component integrated into data-page (between import-backup and offline-areas)

**NOT DONE:**
- Service method implementations are stubs (return fake results, no real Drive service calls)
- Tests: no service or component specs written
- Read me.txt generation at first connect (kind=readme, four languages)
- Integration with real DriveBackupService, DriveSyncEngine, DrivePhotos, DriveDeletionService (will come when GoogleConfig and injection are available from feat/drive-web-connect)
- Authorization gates for L2/L3 (will use existing web/src/app/data/device-auth/prf-gate.ts)

**Next Session Should:**
1. Write service and component tests (.spec.ts files) with fakes
2. Implement real service methods calling Drive services (when feat/drive-web-connect lands GoogleConfig)
3. Add "Read me.txt" generation at first connect
4. Test full flow: create folder → show recovery key → enrol → ready → backup → list → import
5. Integration testing with AuthorizationGate adapters
