# S4b-BL-127: device lock and authenticated deletes (notes)

Branch `feat/drive-device-auth`, stacked on `feat/drive-crypto-core`. Development and unit tests only.

## Threat lines (written before coding): someone with Drive write access, no folder key, against this feature

1. Drive has no say in a local check: they can delete or overwrite any file with Drive's own API. No in-app level stops that (docs/15 10.6); the defence is the encryption and each device's own copy. Nothing here pretends otherwise.
2. They can plant or edit a `keys.json`/control file to look like "lock removed", "backups deleted" or "folder gone". Defence: `DriveGate` takes the lock state ONLY from the local `LockLostDetector`; no Drive content is an input to the gate, to `DeletionPolicy` or to key dropping.
3. They can delete the folder to provoke a "lock lost" reaction. Defence: the lock-loss reaction (`LockLossActions`) has no Drive client and no network at all: it can only drop LOCAL keys and flag re-enrolment. It cannot delete, re-key, revoke or re-create anything remote.
4. They can make `files.list` slow/odd so a delete runs long past its authentication. Defence: a grant is one operation, 60 s from authentication to start, spent on first use, bound to one action; re-checked against the lock when redeemed.
5. They can fake a "backup count" (add/remove backup files) to turn the last-backup delete into an L1 one. Defence: an unknown count (null) is treated as the last backup (L2); the count is the caller's job and must come from a fresh listing; a count of 0 or 1 is L2.
6. They can force offline-looking errors so the delete "queues". Defence: delete-type actions are refused offline, never queued (policy outcome OFFLINE); there is no queue in this ticket.
7. They cannot reach the device's Keystore/Keychain keys; they could try to cause LOCKED_OUT or errors on the prompt: every non-SUCCESS result, and any exception from the platform, ends as "Nothing was deleted" (fail closed).
8. Website: with developer tools anyone at the browser can skip a page check. Defence per docs/15 10.4: no PRF, no L2/L3 (REFUSED/USE_PHONE); the PRF seam seals the key, so the check guards a real key. The page check alone is never a factor.
9. A Drive-side attacker cannot learn the PRF output or the seal key; the sealed blob is stored locally (IndexedDB), never in Drive. Wrong PRF output opens nothing (AES-GCM fails closed).
10. Unknown lock state (detector cannot tell) pauses Drive but does NOT drop keys: dropping needs a positive "REMOVED"; no error kind triggers a wipe, revoke or re-create.
