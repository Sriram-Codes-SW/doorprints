# S4b-BL-130 notes: the `sync/1` schema and the Drive merge rule

Branch `feat/drive-sync-schema` (worktree `/tmp/wt-ss`, from `origin/main` dd92933). Running file: decisions, doc rows
to add, open questions. Docs tables and changelogs are NOT edited in this change (owner process for this batch).

## 0. Adversarial pass (written before coding)

The layers: S4b-BL-125/126 wrap each sync file in `dpx/1` (AEAD, header `kid` = the writer, epoch). S4b-BL-118 lists,
downloads, decrypts and gunzips, then hands the plaintext to this ticket's parser and merge. So the attackers are:

### A. Someone who can WRITE to the Drive folder but has no folder key

(Google account access, a leaked token, another app with `drive.file` on the same files, the person by hand.)

| They can | Effect | What stops it (layer) |
|---|---|---|
| Forge or alter a sync file | Refused | AEAD of `dpx/1` (125); never reaches this parser |
| Put device A's real file into device B's slot (swap, or copy) | B's rows "change" | Parser takes `expectedDeviceId` (the envelope's authenticated `kid`, cross-checked with the file's `appProperties` by 118) and refuses `deviceId` mismatch: `WRONG_DEVICE` |
| Restore an older Drive revision of a real file (rollback) | A stale snapshot | `seq` per writer; reader keeps the highest seen per device; lower `seq` = `stale`, not merged, reported. Even if merged, LWW changes nothing newer (vector + property test) |
| Leave two real files for one device (old copy + new) | Confusing | 118 takes the highest `seq`; a lower one is stale |
| Delete or withhold a device's file (freeze) | That device's changes stop arriving | Nothing lost locally (1.4 item 1); the device rewrites its file next sync (118) |
| Upload a huge file / zip bomb in a device slot | DoS | Refused at the envelope (no key); the parser still caps bytes before parsing (16 MiB) and rows per list; 118 caps the gunzip output at the same 16 MiB |
| Fill quota | Writes fail | 115's `QUOTA_EXCEEDED`; not this ticket |

### B. An enrolled device that is malicious or buggy (fully trusted by design, docs/15 §9.3; we bound the damage)

| It can | Bound or clamp built here |
|---|---|
| Stamp `updatedAt` far in the future so its row wins for ever | **Held**: a row stamped more than 24 h after the reader's clock (`MAX_AHEAD_MS`) is not applied and not dropped; it is applied once the reader's clock is within a day of it (so all devices still converge, unlike a clamp, which would rewrite the stamp differently on each device). A row within the day wins only until the next local edit, because `nextStamp = max(now, previous + 1)` always moves past it. Tested |
| Stamp a row at 0, before 2000, or with a malformed time | Whole file refused (`BAD_ROW`): a row that cannot be ordered is a bug or tampering |
| Huge arrays, deep nesting | Byte cap before parse (16 MiB of UTF-8), per-list caps (houses 20 000, visits/records/photos 50 000, total 100 000), `TOO_LARGE`; kotlinx's parser is deep-recursion safe, `JSON.parse` errors are caught (`NOT_JSON`) |
| Duplicate ids in a list | Whole file refused (`DUPLICATE_ROW`): which copy is "the" row would depend on the reader |
| Tombstone for everything | **Shrink guard**: if one file would delete at least 10 live houses and more than half of the live houses here, those house deletions are deferred until the person confirms (L1), the rest of the file is merged; plus the dated backups keep the old state (1.4 item 4/6). A far-future tombstone set is held (above). Tested |
| Resurrect deleted houses with a newer edit | Allowed by the rule (a newer edit wins over a delete, docs/15 §5.1); the backups have the deleted state. Not bounded further (trusted device) |
| Two different rows with the same `updatedAt` and `by` (breaks its own stamping rule) | Total order key is (`updatedAt`, `by`, tombstone over live); a full tie keeps the local row. Two live versions with an identical key can stay apart until the next edit: documented, needs a device that breaks `nextStamp` |
| Spoof `by` (another device's id) | Only changes a tie-break at the same millisecond; ids are validated in format |
| `seq` jumped to the maximum | Harms only itself (its later files look stale only if it goes back); capped at 2^53 - 1 (safe integer on both stacks) |
| Rows of unknown kinds / unknown fields | Unknown top-level keys and row fields are ignored (forward compatible within `/1`); a new list a reader must not lose is `/2` (docs/schemas §1.1) and refused as `UNSUPPORTED_VERSION` |
| Bad field content (a house with lat 999) | The loop's existing per-row validation (web `tryHouseFromDto`, Kotlin `toEntity`) skips it, as for the server's rows; this parser checks only what the merge needs |
| A photo row pointing at someone else's Drive file | `driveFileId` format checked, `sha256` 64 hex required with it; the bytes are verified against `sha256` by 118/128 (`downloadVerified`) |

## 1. Decisions

1. **Format id** `doorprints-sync/1` (the docs say "sync/1"; spelled like `doorprints-backup/<n>`). A reader accepts
   `/1` only (`MAX_VERSION` 1); `doorprints-sync/<n>` above is `UNSUPPORTED_VERSION` ("update the app"), anything else
   `NOT_A_SYNC_FILE`.
2. **Shape**: `{format, deviceId, seq, writtenAt, houses[], visits[], records[], photos[]}`; rows are the sync DTOs
   (`HouseDto`, `VisitDto`, `RecordDto`, `PhotoChangeDto`) plus `by` (the device that made this version of the row),
   photos plus optional `driveFileId` and `sha256`. `syncVersion` is not written and ignored when read (the loop's
   position comes from 118). Tombstones are rows with `deleted: true` in the same lists, kept for ever.
3. **Per-row `by`**, not only the file's `deviceId`: a device relays other devices' rows in its own snapshot, and the
   tie-break must name who made the version, or two readers would break the same tie differently.
4. **Strict parsing**: the envelope fields and each row's merge fields (`id`, `type` for records, `updatedAt`, `deleted`,
   `by`, photo `houseId`) are required with exact JSON types (no string-for-number, no string-for-boolean), ids match
   `RecordRules.isValidId` (`[A-Za-z0-9._-]{1,64}`, not `.`/`..`), record types `[a-z][a-zA-Z0-9]{0,39}`, device ids
   `[A-Za-z0-9_-]{8,64}`. One bad row refuses the whole file (as the backup import). Unknown keys are ignored.
5. **Times**: `updatedAt` and `writtenAt` are ISO-8601 UTC strings as the DTOs carry them, parsed by a strict parser
   written the same on both stacks (`YYYY-MM-DDTHH:MM:SS[.f{1,9}]Z`, real calendar dates, no offsets, fraction truncated
   to milliseconds), not `Date.parse`/`Instant.parse`, so both stacks read the same millisecond or refuse the same text.
   Kotlin writes `…:00Z` for a whole second and JS `…:00.000Z`; both are read the same. Earliest 2000-01-01 (the
   server's `ClientClock.EARLIEST`). `seq` is a JSON number that is an integer 1..2^53-1 (`1.0` and `1e0` read as 1 on
   both stacks).
6. **Order**: a row version is ordered by (`updatedAt`, `by` by code units, tombstone over live). Incoming wins only if
   strictly greater; a full tie keeps the local row (idempotent, writes nothing). This is a total order on the key, so
   the merge is a per-key maximum: commutative, associative, idempotent (property tests). `dirty` plays no part.
7. **Stamp** each local edit (and delete) `max(now, previous + 1)` (`DriveMerge.nextStamp`).
8. **Hold, not clamp**, for a stamp more than 24 h ahead of the reader's clock (§0 B). The plan lists the held rows; 118
   must not move that device's checksum cursor past a file with held rows (or must keep them) so they are applied later.
9. **Shrink guard** in the merge plan: deferred house deletions when `deleted >= 10` and `deleted * 2 > live houses here`;
   `confirmShrink = true` applies them (L1 confirmation, words in 118).
10. **Rollback**: `seq < highest seen for that device` is `stale` and nothing is merged; equal `seq` merges (idempotent).
11. **Seam**: `SyncRecord` (Kotlin) gains `deleted` and `writer` with defaults (false, null); the three entities mark their
    `deleted` `override`. `SyncRules.driveMerge` / web `driveMerge` is the LWW `MergeRule`; `writer` null compares as "".
    Persisting `writer` locally (a Room column, an IndexedDB field) is 118's (open question 1).
12. **Left-overs of the seam** fixed here: (a) the web pull read only dirty records for the merge: now all records
    (`LocalStore.allRecords()`), so a rule that can keep a clean row works; (b) the device-id tie-break exists in the
    rule; (c) "serverReset" renamed to backend-neutral names: Kotlin `SyncOutcome.serverReset` -> `remoteReset`, web
    `serverWasReset` -> `pushShowsReset` (the Kotlin name), `ServerReset` -> `RemoteReset`, `serverResetAt` ->
    `remoteResetAt`. The i18n key `data.serverReset` and `sync_server_reset` stay (their words say "server", right for
    the server backend; Drive's own words come with 118).
13. **Canonical writer**: rows sorted by key (records by type, then id; code-unit order), lists in fixed order, keys of the
    envelope in fixed order; the writer refuses to write what a reader would refuse. Not byte-identical across stacks
    (number formatting), same as the backup ("the same document, not the same bytes").
14. **Schema file**: `docs/schemas/sync-1.schema.json` (JSON Schema 2020-12) documents the shape and the caps; no
    library validates against it (no new library): both stacks' tests check their constants equal the vectors'
    `limits`, which mirror the schema.
15. **Random vectors**: both stacks run the same xorshift32 generator from the seeds in the vectors (3 devices with
    skewed clocks, edits, deletes, pairwise syncs in random order) and must reach the digest written in the vectors
    (computed by the Kotlin run, checked by TypeScript).

## 2. Doc rows to add (the later docs pass)

- docs/schemas/README.md: §1 table rows "Sync file" (`sync-1.schema.json`) and "Shared sync vectors"
  (`sync-vectors.json`: Kotlin `SyncVectorsTest`, web `sync-vectors.spec.ts`); new §6.3 "The shared sync vectors";
  §7 limits rows (sync file 16 MiB; per-list caps; 24 h ahead); new §10 or a section "The sync file (`sync/1`)" with the
  decisions 1-10 above; change log row.
- docs/15 §5.1: point the sync file bullet at the schema; the hold rule (24 h), the shrink guard for sync, `seq` rollback
  rule, per-row `by`; §7 phase 4d row: **built**; a §7.2 "What S4b-BL-130 built, and what it decided"; change log.
- docs/10 S4b-BL-130 row: Done (branch `feat/drive-sync-schema`); change log row; new backlog rows for open questions.
- docs/03 ADR-33 / §10.1: the merge order key (`updatedAt`, `by`, tombstone) and the hold; version row.
- docs/02: T-rows for a far-future stamp (held), a mass tombstone (shrink guard), a file swapped between device slots
  (`WRONG_DEVICE`), a rolled-back file (`seq`); version row.
- docs/06: TC-U rows: sync file parser and limits (`SyncFileTest`, `sync-file.spec.ts`), Drive merge properties
  (`DriveMergeTest`, `drive-merge.spec.ts`), shared vectors (`SyncVectorsTest`, `sync-vectors.spec.ts`), the web pull
  reading every record (`sync.service.spec.ts`).
- docs/14 §1/§2 N17: S4b-BL-130 built; change log.
- android/shared/README.md and web README: the new files; CHANGELOG *Unreleased*.

## 3. Open questions

1. Persisting `by` (writer) per row locally: needs a Room migration (houses, visits, records, photos) and an IndexedDB
   field; until then a local row's writer compares as "" and loses every same-millisecond tie. For 118.
2. Photo rows: one `updatedAt` stamps add, meta edit and delete, but the local stores keep `metaUpdatedAt` separately and
   do not bump `updatedAt` on a meta edit. 118 must stamp `updatedAt = nextStamp(...)` on a meta edit (or the file
   writer uses `max(updatedAt, metaUpdatedAt)`).
3. The shrink guard counts one file at a time; a device deleting 40 % per file over several files is not caught (the
   backups still hold the history). A cumulative guard (since the last confirmation) is a possible later step.
4. Tombstones are kept for ever (~100 bytes each); at 20 000 houses of tombstones the file is about 2 MB uncompressed.
   The 16 MiB cap is far away; compaction is docs/15 §5.1's later step.
5. Whether 24 h is right for the hold window (the server's skew clamp is a different, smaller number, and it clamps
   because the server is the one clock; Drive has none).

## 4. State at the WIP stop (2026-10-02, owner near a usage limit)

**Done and passing (Kotlin, `:shared:testAndroidHostTest --tests 'app.doorprints.shared.sync.*'`: 39 tests, 0 failures):**
- `android/shared/.../shared/sync/SyncFile.kt` (SyncKind, SyncFileProblem, SyncFileException, SyncStamp, SyncRow.of,
  SyncFile, SyncFiles.parse/encode/utf8Length/nestingDepth, SyncTime.parse), `DriveMerge.kt` (nextStamp, takesIncoming,
  isHeld, rule, plan, MergePlan); `SyncRecord` gains `deleted`/`writer` defaults, entities' `deleted` marked `override`.
- Tests: commonTest `SyncSim.kt`, `DriveMergeTest.kt`, `SyncFileTest.kt`; androidHostTest `SyncVectorsTest.kt`.
- `docs/schemas/sync-1.schema.json`, `docs/schemas/sync-vectors.json` (random digests filled by the Kotlin run).
- Generator: scratchpad `gen_sync_vectors.py` (run `python3 gen_sync_vectors.py "$(cat /tmp/sync-random.json)"`).
- Found by the tests: kotlinx's tree reader overflows the stack on deep nesting -> `MAX_DEPTH` 64 pre-scan (both stacks).
- Kotlin rename `SyncOutcome.serverReset` -> `remoteReset` (shared, ui, :app tests edited by sed; **:app and :ui not
  compiled yet**).

**Half-done (written, never compiled or tested):** web `web/src/app/data/drive/sync-file.ts`, `drive-merge.ts`;
`sync-rules.ts` MergeRule type gains `deleted?`/`by?`.

**Not started:** web specs (`sync-file.spec.ts`, `drive-merge.spec.ts` with the xorshift32 `simulate` twin of
`SyncSim.kt`, `sync-vectors.spec.ts` reading the vectors incl. `random.cases`); web pull reading all records
(`LocalStore.allRecords()` replacing `dirtyRecords()` at sync.service.ts ~681, plus a spec); web renames
(`serverWasReset` -> `pushShowsReset`, `ServerReset` -> `RemoteReset`, `serverResetAt` -> `remoteResetAt` in
sync.service.ts, data-page.html, sync.service.spec.ts, sync-backend.spec.ts, sync-backend.ts comment).

**Next steps, in order:**
1. `cd android && ./gradlew :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata
   :app:testDebugUnitTest --tests '*SyncServerResetTest' --tests '*SyncBackendSeamTest' --tests '*RecordsTest'`.
2. Web: the three specs, then `npx ng test --watch=false --include='src/app/data/drive/**' --include='src/app/data/sync*'`.
3. Web pull fix + renames + their specs.
4. `python3 .github/scripts/licence-headers.py --fix` (git add -N first), commit, push.
