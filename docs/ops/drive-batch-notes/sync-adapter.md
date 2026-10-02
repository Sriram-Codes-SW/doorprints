# S4b-BL-118/128: Web DriveSyncAdapter (sync + photos)

## Security Analysis

What someone with Drive write access but no folder key could do:
1. File tampering → Defence: DriveSyncEngine checks (rollback, epoch, size, Dpx) before any state change
2. Listing manipulation → Defence: timestamps not trusted; appProperties seq and peer state guard ordering
3. Injection → Defence: appProperties validated (isDeviceId), Device in keys.json, Dpx authenticated

Interface typed (no Angular DI), all errors typed; no silent failures.

## Status

- [x] pendingPhotoBytes() implemented (sum sizeBytes of unuploaded photos via local rows + photo refs)
- [x] Web network rule: UNKNOWN -> ALLOWED (not METERED); parameter-based so Kotlin keeps METERED
- [x] photo-policy-vectors.json: platform field added, unknown-network rows split (web/android)
- [x] PhotoUploadGate: webUnknownAllowed parameter (true for web)
- [x] DriveSyncAdapter: passes webUnknownAllowed=true to gate
- [x] sync-adapter.md notes file (renamed from notes-bl131)
- [x] Tests to add: offline, waiting-wifi, shrink-confirm, skipped-file, grant-expiry, two-adapters-converging

## Remaining

- Tests: 5 new comprehensive tests (offline → 'offline' state, wifi-gating → waiting-wifi, shrink flow, skipped file reported, grant expiry at 30 min + 1ms, two devices through same fake drive)
- tsc, drive specs, licence headers, commit + push

## Notes

- pendingPhotoBytes sums sizeBytes of row.kind='photos' && !deleted && !in(refs)
- photo-policy-vectors.json "platform" field values: "web", "android", "ios", or omit for all
- Android/iOS PhotoNetworkPolicy stays unchanged (unknown=METERED, no webUnknownAllowed param)
- Web tests: photo-policy-vectors.spec.ts checks both web and phone branches
