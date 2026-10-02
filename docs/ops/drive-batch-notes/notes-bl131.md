# S4b-BL-131: Web DriveSyncAdapter implementation

## Security Analysis (10 lines)

What someone with Drive write access but no folder key could do to the adapter feature:

1. **File tampering**: Modify sync files (seq, content) to cause rollbacks, stale data, or duplicates; cannot decrypt envelopes but can detect device ids in keys.json appProperties.
   Defence: All checks in DriveSyncEngine before using read files (rollback detection, epoch check, size limits, envelope format); no state change from a skip or bad file.

2. **Listing manipulation**: Omit sync files, reorder listing, fake timestamps in Drive to confuse peers.
   Defence: Drive file timestamps not trusted; appProperties seq and peer state in DriveSyncState; any skip is reported, none blocks or deletes.

3. **Injection**: Add fake files to the folder with forged kind/role to trigger reads.
   Defence: appProperties validated (id format check with isDeviceId), each Device is in keys.json opened list, Dpx envelope authenticated.

4. **Recovery key**: Not part of this adapter (handled by backup service and keys-guard).

Interface is typed (no injected Angular DI), all errors typed (DriveSyncNotYet, KeysError, DpxError, DriveError, SyncFileError), constructor requires FolderSession (opened keys + guard) and DriveClient.

## Status - COMPLETE

- [x] DriveSyncAdapter interface + IDriveSyncAdapter public API
- [x] syncNow({confirmShrink?}) -> UI-friendly status (synced|waiting-wifi|offline|needs-confirmation|skipped-files|error)
- [x] isBehind()
- [x] Photo settings: getPhotoSettings(), setPhotosWifiOnly(bool), setUploadOnMobile(bool)
- [x] Network state seam: PhotoUploadGate + webNetworkState
- [x] decideBackup(force?: boolean) scheduling
- [x] uploadPhotosNowOverMobile() -> 30-minute OneOffGrant
- [x] pendingPhotoBytes() for button label
- [x] Spec: 9 tests all passing (initialization, settings, grant TTL, sync, pending bytes, backup, behind, confirm shrink)
- [x] Lint, test pass, commit, push

## Notes

- Real implementation: use DriveSyncEngine.run(), DriveSyncBackend, PhotoUploadGate
- No stubs: every method does the real thing or throws typed error
- Adapter is a plain class, injected dependencies only
- Web network state seam: navigator.connection (unknown treated as ALLOWED per docs/15 §11 _if the preface rule changes_, else METERED; will update photo-policy-vectors.json vectors if one-line Kotlin change applies)
- One-off photo upload grant via PhotoUploadGate: 30 minutes TTL
- Auto-backup/sync decision: via decideBackup (check existing backup schedule)
- Share one FolderSession across engine and photos
