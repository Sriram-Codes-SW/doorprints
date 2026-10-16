# Shared schemas: the Doorprints JSON backup

| Field | Value |
|---|---|
| Document | `doorprints-backup/1` — the one backup format for server, Android and web |
| Version | 1.74 |
| Date | 2026-10-16 |
| Date | 2026-10-06 |
| Author | Claude (Cowork) – Backend team |
| Status | Pinned by story S4-00 (Sprint 4a). Changing anything here changes all three implementations at once. |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 1.6 | 2026-09-24 | Claude (Code), engineer | Legacy House Hunt names renamed (owner request of 2026-09-24; [03](../03-design.md) ADR-24). Code paths follow the moved packages (`app.doorprints.shared.export`, `app.doorprints.server.backup`). **The format is unchanged**: `doorprints-backup/1` was already the brand name (ADR-20); the pre-Sprint 4a `house-hunt-export/1` was never an import format and is still rejected as not a backup. |
| 1.7 | 2026-09-30 | Claude (Code), lead | **Two optional manifest fields for an update file** (sharing updates, [11](../11-feature-parity-and-export-spec.md) 5.28): `sharedSince` and `sharedTo`; the file name `Doorprints-updates-<date>.zip`. A reader ignores both (nothing gates on them); the web writer does not write them yet. |
| 1.8 | 2026-09-30 | Claude (Code), lead | **`listing-fixtures.json`** (format `doorprints-listing-fixtures/1`): the share texts the no-AI listing parser reads the same way on Android and the web ([11](../11-feature-parity-and-export-spec.md) 5.29); not part of the backup format. |
| 1.9 | 2026-09-30 | Claude (Code), lead | **Notice of `doorprints-backup/2`** ([11](../11-feature-parity-and-export-spec.md) 5.30, ADR-28; not yet written): the Sprint 4b data model adds nested house values and named lists (`criteria`, `questions`, `viewings`, `huntingAreas`, `places`, `areaNotes`, `brokers`, `photoMeta`, `moveIn`, `preferences`), and with them the versioning rule of S4b-BL-72: a new list means a new format number, readers accept `1..MAX`, a newer file is refused with "update the app". This file changes to `/2` in slice 0 of the batch, with `backup-sample.json` and the three writers in one commit. Nothing in `/1` changes. |
| 1.10 | 2026-09-30 | Claude (Code), lead | **The versioning rule, in force** (S4b-BL-72, slice 0 of [11](../11-feature-parity-and-export-spec.md) 5.30): new section 1.1. Every reader accepts `doorprints-backup/1` and `/2` (`BackupFormat.READ_IDS` in Kotlin and Java, `BACKUP_FORMATS_READ` on the web) and refuses a higher number with "update the app"; every writer still writes `/1` until slice 1 adds the first `/2` list. Section 3's `format` row says so. |
| 1.11 | 2026-09-30 | Claude (Code), lead | **Three optional house fields within `/1`** (slice 1a of [11](../11-feature-parity-and-export-spec.md) 5.30, the rule of §1.1): `areaSqft`, `locationSource` and the nested `cost`, after `notes` and before `checklist` in every writer; `backup-sample.json` carries them on houses 1 and 3 (the web byte golden regenerated; the parsed comparisons unchanged in kind). An old reader ignores them. |
| 1.12 | 2026-09-30 | Claude (Code), lead | **`doorprints-backup/2` is written** (slice 1b of [11](../11-feature-parity-and-export-spec.md) 5.30, brokers): a `brokers` list after `photos` (new §3.4), `brokerId` on a house after `cost`, `counts.brokers`; a writer writes `/2` only when the copy has a broker and `/1` otherwise, and a copy made without contact details has neither. `backup-sample.json` is now a `/2` document with two brokers (the web byte golden regenerated, 2 928 bytes). |
| 1.13 | 2026-09-30 | Claude (Code), lead | **Rooms** (slice 1c of [11](../11-feature-parity-and-export-spec.md) 5.6 and 5.30): an optional `rooms` array on the house, after `cost` and before `brokerId` (§3.1, §3.5); a writer now writes `/2` when the copy has a broker **or a room**, and a copy made without contact details keeps its rooms. `backup-sample.json` carries two rooms on house 1 (3 232 bytes; the web byte golden regenerated). |
| 1.14 | 2026-09-30 | Claude (Code), lead | **Criteria and preferences** (slice 2 of [11](../11-feature-parity-and-export-spec.md) 5.4 and 5.30): two top-level lists after `brokers` (§3.6); a writer writes `/2` when a copy holds a broker, a room, a criterion or a preference, and keeps criteria and preferences in a copy made without contact details. `backup-sample.json` carries three criteria and one preference (3 649 bytes; the web byte golden regenerated). |
| 1.15 | 2026-09-30 | Claude (Code), lead | **Viewing questions** (slice 3a of [11](../11-feature-parity-and-export-spec.md) 5.5 and 5.30): a `questions` list after `preferences` (§3.7) and an optional `answers` array on the house after `rooms` (§3.8); a writer writes `/2` when a copy holds a question or a house with answers (with the earlier reasons). New `default-questions.json` (format `doorprints-default-questions/1`), the seed of the question bank in four languages, not part of the backup format. `backup-sample.json` grew to 4 569 bytes (3 questions, two answers on house 1). |
| 1.16 | 2026-09-30 | Claude (Code), lead | **Viewings** (slice 3b-1 of [11](../11-feature-parity-and-export-spec.md) 5.8 and 5.30): a `viewings` list after `questions` (§3.10); a writer writes `/2` when a copy holds a viewing. New `viewing-sample.ics`, the byte-exact calendar file of the sample's second viewing (§3.11; its line ends are CRLF, kept by `.gitattributes`). `backup-sample.json` grew to 5 121 bytes (2 viewings). |
| 1.17 | 2026-09-30 | Claude (Code), lead | **Areas, places and area notes** (slice 4a of [11](../11-feature-parity-and-export-spec.md) 5.17, 5.22, 5.23): the lists `areas`, `places`, `areaNotes` after `viewings` (§3.12); a writer writes `/2` when a copy holds any of them. `backup-sample.json` grew to 5 847 bytes (2 areas, 2 places, 2 area notes). |
| 1.18 | 2026-10-01 | Claude (Code), engineer | **The house's floor** (S4b-BL-87, which the duplicate-flat warning S4b-BL-85 needs): an optional `floor` on the house (§3.1), a whole number -5..200 with 0 the ground floor and a negative one a basement level, after `moveIn` and before `brokerId` in every writer; a writer writes `/2` when a house has a floor (0 included). A reader on a device takes a value out of range as unknown; the server refuses the file (`houses[i].floor must be -5..200`) and a sync `PUT` (400). `backup-sample.json` carries `floor` 3 on house 1 and 0 on house 3 (6 304 bytes with the trailing newline; the web byte golden regenerated, 6 303). |
| 1.19 | 2026-10-01 | Claude (Code), engineer | **Deletions in an update file, `doorprints-backup/3`** (S4b-BL-82, [11](../11-feature-parity-and-export-spec.md) 5.28 item 3): a top-level `deleted` list after `areaNotes` (new §3.13), written only into an update file and applied only by an update import (§6 rule 5); a new list, so a new number by the rule of §1.1, and every reader accepts `1..3` (Kotlin and Java `MAX_VERSION` 3, web `BACKUP_FORMATS_READ`); `counts.deleted` in the manifest. The server reads `/3` as a restore and ignores the list. **The website reads backups** (S4b-BL-75): `web/src/app/export/backup-reader.ts`, `backup-check.ts`, `import-plan.ts`. New **`import-vectors.json`** (format `doorprints-import-vectors/1`, §6.1): the data checks, merge previews and archives every reader must answer alike, and **`update-sample.json`** (§8.3), the `/3` golden. `backup-sample.json` is unchanged (a backup has no `deleted` list). |
| 1.20 | 2026-10-01 | Claude (Code), lead | **Slice 5 written down here** (photo tags and moving in, [11](../11-feature-parity-and-export-spec.md) 5.7, 5.24; the code, the sample and `default-movein.json` came with the slice, this file had not caught up): the statuses `TAKEN` and `NOT_CHOSEN` (§3.1), the house's `moveIn` after `answers` and before `floor` (§3.1, new §3.14), the photo's `roomId`, `tags`, `caption` and `metaUpdatedAt` (§3.3), the `/2` rule for them, and `default-movein.json` (new §3.15). `backup-sample.json` (6,304 bytes) has house 1 TAKEN with a move-in and photo 1 with meta. Nothing in the format changed. |
| 1.21 | 2026-10-01 | Claude (Code), lead | **A floor out of range on import** (S4b-BL-104 d): unchanged rule (a device reads it as unknown, the server refuses the file), now reported: the preview counts the houses it writes with such a floor (`floorsLeftBlank`, Kotlin `ImportPreview` and web `import-plan.ts`) and shows a warning line. New merge case in `import-vectors.json`, "a floor out of range lands blank and is counted". Nothing in the format changed. |
| 1.22 | 2026-10-02 | Claude (Code), lead | **`drive-vectors.json`** (format `doorprints-drive-vectors/1`, new §6.2, S4b-BL-115): Drive v3's `q` strings, the requests and answers of seven exchanges, the error mapping and the backoff rule, the same for Kotlin's `HttpDriveClient` and the website's `FetchDriveClient`; not part of the backup format. |
| 1.23 | 2026-10-02 | Claude (Code), lead | **`hpke-vectors.json` and `dpx-vectors.json`** (formats `doorprints-hpke-vectors/1` and `doorprints-dpx-vectors/1`, new §6.3, S4b-BL-125): the crypto primitives' and HPKE's known answers (official ones named by source, regression ones marked), and the parity vectors of the recovery key, the `dpx/1` envelope and a `keys.json` life. Not part of the backup format. |
| 1.24 | 2026-10-02 | Claude (Code), lead | `dpx-vectors.json`'s `keys` regenerated after the review of S4b-BL-125: the recovery entry carries its anchor (`anchorEpoch`, `anchor`); every other vector is unchanged; checked again by the independent decoder, which now also opens the anchor. |
| 1.25 | 2026-10-06 | Claude (Code), lead | **`trace-repeat-vectors.json`** (format `doorprints-trace-repeat-vectors/1`, status *proposed*, new §6.4, S4b-FR-13): the cases that Kotlin and the website's TypeScript must both pass for the path trace's repeat detection and alert ([11](../11-feature-parity-and-export-spec.md) 5.27.3). Not part of the backup format: walks are never in a backup (PRV-028). |
| 1.26 | 2026-10-06 | Claude (Code), lead | **`trace-repeat-vectors.json` after the senior review** ([11](../11-feature-parity-and-export-spec.md) v0.57): 37 cases (six added: `split-walk-id-zero-is-no-id`, `shown-newest-draws-whatever-the-input-order`, `web-pause-makes-no-segment`, `alert-one-bad-fix-does-not-ring-again`, `alert-not-for-the-walk-just-finished`, `alert-walk-finished-30-minutes-ago-counts`); points may carry a fifth number (resumed after a pause); `constants` gain `maxWalkPoints` and `maxDetectionPoints`; `overlap-60m-is-not-a-repeat` moved off the 80 m boundary; the convention's metre-to-degree figure corrected to 0.000008993216; the Kotlin test is in `androidHostTest` (with an inline `commonTest`); section 6.4 states the hygiene rule. |
| 1.27 | 2026-10-06 | Claude (Code), lead | **`trace-repeat-vectors.json`: the `placeChecks` section** ([11](../11-feature-parity-and-export-spec.md) v0.58, 5.27.13, S4b-FR-24): 21 cases of the on-demand place check (on the line and the interpolated time, 24.9 / 25.1 / 49.9 / 50.1 m, a round cap at a walk's end, a gap between two walks, two walks newest first, a saved walk, no walks, a one-point fragment, a poor-accuracy fix, a pause with no segment, an out-and-back, latitude 13, an invalid place), `placeCheckConventions`, `nearBandM` and `maxFixAccuracyM` in `constants`; the 37 repeat cases are unchanged; status still *proposed*. |
| 1.28 | 2026-10-06 | Claude (Code), engineer | **`qr-enrol-vectors.json`** (format `doorprints-qr-enrol-vectors/1`, §6.3): the `dp1.` enrolment offer text both ways (`valid` offers with their exact bytes, `forms` the pasted shapes that still find the offer, `invalid` inputs every stack refuses). Run by `QrEnrolTest` and `qr-enrol.spec.ts`; both stacks agreed on every vector. |
| 1.29 | 2026-10-06 | Claude (Code), lead | **`trace-repeat-vectors.json` status `proposed` to `confirmed`** (S4b-FR-13, S4b-FR-24): both stacks, built on `feat/path-trace-v2` (PR #146), run all 58 cases (37 repeat, 21 place-check); the Kotlin tests are `TraceVectorsTest` (`androidHostTest`) and the inline `RepeatDetectorTest` and `PlaceCheckTest` (`commonTest`), the web specs `trace-repeats-vectors.spec.ts` and `trace-place-check-vectors.spec.ts`. Only the top-level `status` changed; no expected value was touched. Section 6.4 and the vector table follow. |
| 1.30 | 2026-10-08 | Claude (Code), lead | **`india-place-vectors.json`** (format `doorprints-india-place-vectors/1`, S4b-BL-174) and 20 more cases in `listing-fixtures.json` (§8.2, §8.2a): places across India with whether the held areas' polygon holds them, read by the website's `india-boundaries.spec.ts` and the Android `IndiaBoundaryDataTest`; share texts from the north-east, the hills, the islands and the coasts, with landlines, capitals-only forwards and `2.5 BHK`. |
| 1.31 | 2026-10-10 | Claude (Code), engineer | **The kind files, `kinds/`** (§8.4; S4b-BL-205, [03](../03-design.md) ADR-36 §18): `kind.schema.json` (format `doorprints-kind/1`), `house.json` (today's house as a kind) and `kind-vectors.json` (format `doorprints-kind-vectors/1`, what it resolves to). Nothing in the backup format changes here; the reset of S4b-BL-206 will. |
| 1.32 | 2026-10-10 | Claude (Code), engineer | **`qr-enrol-vectors.json` carries the platform byte** (S4b-BL-144, §6.3): the offer is `pk_new ‖ s ‖ platform`; each `valid` row names its `platform` (`unknown` for the 97-byte form), four new `valid` rows with the byte, `unassignedPlatform` (byte 0, 4 and 255: read as unknown, read only), two `forms`, and `invalid` rows for 99 bytes and for a platform offer with a wrong first byte, padding, two characters missing and a non-canonical last character (the 98-byte row of the old file is valid now). Not a backup, sync or `keys.json` change. |
| 1.33 | 2026-10-12 | Claude (Code), engineer | **The format reset, kinds step 3** (S4b-BL-206; [03](../03-design.md) ADR-36 §18.4 doors 1 and 2): a writer writes `doorprints-backup/1` with `search` and `candidates` (`kind`, `money` with its cadence, `extras`) and `candidateId` on visits, photos and viewings, the manifest counting `candidates` (§1.1, §2, §3.0); a reader reads that family and **still reads the family before it** (`houses`, `/1` to `/3`), told apart by shape; one mapping per stack (`BackupWire`, `backup-family.ts`). `backup-sample.json` and `update-sample.json` are the new family; `backup-sample-houses.json` is the sample as it was, which the new one reads back as byte for byte on the three stacks (§8); `import-vectors.json` has the new family's cases and keeps the old ones as "the family before (houses): ..." (§6.1). `kinds/house.json`'s `was` stays: it names the place of each value in the family before. |
| 1.34 | 2026-10-13 | Claude (Code), engineer | **Kinds step 4, the phone** (S4b-BL-206): new §6.5, the Drive sync file after the reset: `sync-1.schema.json` describes `candidates` and `candidateId` beside `houses` and `houseId` (never both), `sync-vectors.json` has eight new cases (a file of the new shape is read, both names refused, a `candidateId` that is a path or a number refused, a null one is no house). §3.0: the phone now keeps an unknown extra in Room and on the wire; its backup still leaves it out (S4b-BL-248). The backup format itself is unchanged. |
| 1.35 | 2026-10-13 | Claude (Code), engineer | **The website writes the sync file in the candidate shape; a backup keeps a newer app's extras** (S4b-BL-246, S4b-BL-248; `feat/kinds-4b-sync-and-extras`): §6.5 (the website's writer as the phone's, a row taken from another device's file keeps its shape; `sync-1.schema.json`'s `candidates` items allow such a row; a `sync-vectors.json` case with `from`, the rows a written file was made of), §3.0 `extras` (kept through a backup on every stack), §6.1 (`candidatesWrittenBack`, the same bytes on the website, the phone and the server). |
| 1.36 | 2026-10-13 | Claude (Code), engineer | **Photo row key order in the sync file** (S4b-BL-252): §6.5 says the order, and the written case of `sync-vectors.json` holds a photo with a Drive file and one without, compared byte for byte by both stacks. |
| 1.37 | 2026-10-13 | Claude (Code), engineer | **The viewing payload says `candidateId`** (S4b-BL-247; `feat/kinds-4c-viewing-candidate-id`): new §6.6 and [`viewing-payload-vectors.json`](viewing-payload-vectors.json) (format `doorprints-viewing-payload-vectors/1`, 14 cases): a viewing record's payload names its candidate `candidateId`; one stored, synced or kept on a server before names it `houseId` and is read the same on the three stacks; both keys is a bad row; what each stack writes back says `candidateId`, byte for byte. §3.10: the backup row of the family before keeps `houseId` (the new family says `candidateId`, as before). |
| 1.38 | 2026-10-13 | Claude (Code), engineer | **The label resolver's vectors** (S4b-BL-207; `feat/kinds-5-label-resolver`): new [`kinds/kind-label-vectors.json`](kinds/kind-label-vectors.json) (format `doorprints-kind-label-vectors/1`, 75 cases, §8.4): what `KindLabels` answers for a kind, a ref and a money cadence on the website and the phone. Not a backup, sync or kind file change. |
| 1.39 | 2026-10-13 | Claude (Code), engineer | **The typed-extras renderer's vectors** (S4b-BL-208; `feat/kinds-6-typed-extras-renderer`): new [`kinds/kind-field-vectors.json`](kinds/kind-field-vectors.json) (format `doorprints-kind-field-vectors/1`, §8.4): each extra's control and, per stack, its label key and input, and the rows each section's extras are drawn in per money cadence. The kind file gains the optional placement key `forCadences` (`kind.schema.json`; `house.json`'s six rent-only cost fields): the field shows only while the candidate's money has one of those cadences, or none. The kind's `version` stays 1. Not a backup or sync change. |
| 1.40 | 2026-10-13 | Claude (Code), engineer | §8.4: the phone reads `kinds/kind-field-vectors.json` too (S4b-BL-208, `feat/kinds-6b-typed-extras-android`): `:shared`'s `KindFieldsVectorsTest`, `:ui`'s `KindExtrasLayoutTest` and `:app`'s `HouseFormFieldsTest`. No file changed. |
| 1.41 | 2026-10-13 | Claude (Code), engineer | **The hidden schools kind** (S4b-BL-209; `feat/kinds-7-schools-kind`, §8.4): new [`kinds/school.json`](kinds/school.json), the second built-in kind behind a developer flag, and [`kinds/school-vectors.json`](kinds/school-vectors.json) (format `doorprints-school-vectors/1`): its resolved labels in four languages, the renderer's controls and rows per stack, and one school in a backup and on the wire that every stack keeps byte for byte. No change to the backup, sync or kind formats, or to the house's files and samples; the server's backup rows now keep a candidate's kind (a school came back a house). |
| 1.42 | 2026-10-13 | Claude (Code), engineer | §8.4: the phone reads `kinds/school.json` and `kinds/school-vectors.json` (S4b-BL-209, `feat/kinds-7b-schools-android`): `:shared`'s `SchoolKindVectorsTest`, `:ui`'s `SchoolKindScreensTest`, `:app`'s `SchoolFormTest`; the vectors' Android rows corrected to the phone's pairing rule (grades beside the commute). The phone's backup rows keep a candidate's kind. |
| 1.43 | 2026-10-13 | Claude (Code), engineer | §8.4: **`kinds/kind-search-vectors.json`** (S4b-BL-210; `feat/kinds-8-extras-search-copies`): a kind's typed extras in search and the readable copies, read by both stacks; `school.json`'s `search` names its seven extras. |
| 1.44 | 2026-10-13 | Claude (Code), engineer | §8.4: **`kinds/kind-ai-vectors.json`** (S4b-BL-211; `feat/kinds-9-kind-aware-ai`): kind-aware AI, read by the server, the website and the phone; `school.json`'s `ai` slots are filled. |
| 1.45 | 2026-10-14 | Claude (Code), engineer | §8.4: **`kinds/search-kind-vectors.json`** (format 2, S4b-BL-212; `feat/kinds-10-settings-kind-guide`): spaces: the resolver's answers per build and stored setting; a backup's `search` is `{"kind":"house"}` for the house alone and `kinds`/`defaultKind` otherwise; every reader reads past it; `school-vectors.json` and `kind-search-vectors.json` notes updated. |
| 1.46 | 2026-10-14 | Claude (Code), engineer | §8.4: **`kinds/space-scope-vectors.json`** (format 1, S4b-BL-279; `feat/kinds-11-spaces-scoping`): what a space contains, Compare's pick and refusal, a space's copy file names, when a space shows the assistant, the kind a shared listing lands in. No format of the wire, the backup or a kind file changes. |
| 1.47 | 2026-10-14 | Claude (Code), engineer | **`delete-scope-vectors.json`** (format `doorprints-delete-scope-vectors/1`, status *proposed*, S4b-BL-287, deletion safeguards PR 1): nine cases of the blast-radius invariant of deleting one candidate (delete, undo, restore, the *Recently deleted* list per space); read by the website's `data/delete-scope-vectors.spec.ts`, by `CommonRepositoryTest` and `HouseServiceTest` in the phone and server pull requests. Not part of any backup or sync format. |
| 1.48 | 2026-10-14 | Claude (Code), engineer | §8.4: the phones read `kinds/search-kind-vectors.json` (format 2) and `kinds/space-scope-vectors.json` (S4b-BL-274, S4b-BL-289; `feat/kinds-13-phones-spaces`, `feat/kinds-13b-phones-spaces-scoping`): `:shared`'s `KindSettingVectorsTest` and `SpaceScopeVectorsTest`; the Kotlin writer writes the `search` object of §8.4 byte for byte (`BackupWire.encodeData`) and a phone that never chose takes it as a hint after *Import a backup*. No file changed. |
| 1.49 | 2026-10-14 | Claude (Code), engineer | §8.4: the server reads `kinds/search-kind-vectors.json` `write` too (S4b-BL-280; `fix/kinds-server-available-flag`): `BackupWire.searchOf` in `BackupWireTest`. |
| 1.50 | 2026-10-14 | Claude (Code), engineer | **`delete-scope-vectors.json`**: two `deleteSpace` cases (*Delete all in this space*, S4b-BL-284: the step's `id` is the space's kind; the live rows of that space become tombstones in one batch, an old tombstone and every other row stay byte-identical; a kind with no space belongs to the default space). Eleven cases. |
| 1.51 | 2026-10-14 | Claude (Code), engineer | **`delete-scope-vectors.json`** is read on the phones too (S4b-BL-285): `DeleteScopeVectorsTest` runs all eleven cases on real Room with an injected clock, `deleteSpace` through `DeleteScope.liveIdsOf`; status stays *proposed* until the server reads it. |
| 1.52 | 2026-10-14 | Claude (Code), engineer | §8.4: new [`kinds/status-scope-vectors.json`](kinds/status-scope-vectors.json) (format `doorprints-status-scope-vectors/1`, S4b-BL-306; `fix/kinds-scope-taken-and-search-cap`): *Taken* and *Close this hunt* act within the chosen candidate's own kind; `kinds/search-kind-vectors.json` gains `adopt` (S4b-BL-307): what a device keeps of a `search` list (the built-in kinds only, once each). No format of the wire, the backup or a kind file changes. |
| 1.53 | 2026-10-14 | Claude (Code), engineer | **`delete-scope-vectors.json`**: status *proposed* is now *confirmed* and the note says who reads it (the website's `delete-scope-vectors.spec.ts`, #319 and #324; `DeleteScopeVectorsTest` on real Room, #325; the server's `HouseServiceTest`, #328, reading 6 of the 11 cases, the other 5 being the apps' local-store rules). No case changed. |
| 1.54 | 2026-10-14 | Claude (Code), engineer | §8.4: **`kinds/kind-search-vectors.json`** gains `section` (`details` or `cost`) on each `copies` entry and `invalid.copies` entry (S4b-BL-266; `feat/kinds-16-school-wording-web`): the kind section an extra belongs to, which the website's readable copies write it under (the fees under *Fees*); nothing else in the file changed except its note; readers that do not know `section` (the phones' `KindSearchVectorsTest`) are unaffected and the phones read it in their half. |
| 1.55 | 2026-10-14 | Claude (Code), engineer | §8.4: new [`kinds/house-ai-slot-vectors.json`](kinds/house-ai-slot-vectors.json) (format `doorprints-house-ai-slot-vectors/1`, S4b-BL-271; `feat/kinds-17-school-evals-and-slots`): what the house's hand-written AI code carries as ids of `house.json`'s `ai` slots, read by the three stacks' `HouseAiSlotsTest`; `house.json`'s `ai.ask` gains `viewings`, `areaNotes`, `distances` and `visits`, `ai.plan` gains `street`, `ai.extract` gains `areaSqft` and `amenities` (the house's AI output is unchanged). |
| 1.56 | 2026-10-14 | Claude (Code), engineer | §8.4: the phones read `section` in `kinds/kind-search-vectors.json` (kinds PR 18, `feat/kinds-18-school-wording-phones`): `KindSearchVectorsTest` checks it per extra, and a school's readable copies write the fees under *Fees*; no case changed. |
| 1.57 | 2026-10-14 | Claude (Code), engineer | §8.4: [`kinds/kind-ai-vectors.json`](kinds/kind-ai-vectors.json) gains `prompts` (the school's Ask system text and refusal, the Plan system and user text; S4b-BL-269), the Plan summary's `extras` and the device line without price or BHK, and `details` (the server's `houseDetails` extras; S4b-BL-270), and the `kinds` entry gains `records` and `refusal`; the tag in the injection cases is `schools-abc123`. [`kinds/school.json`](kinds/school.json) `ai.plan` names six extras. [`kinds/house-ai-slot-vectors.json`](kinds/house-ai-slot-vectors.json) `planStructural` gains `extras` (a Plan record's component a house never fills). No wire or backup format changes (`feat/kinds-19-school-prompts`). |
| 1.58 | 2026-10-14 | Claude (Code), engineer | §8.4 and the AI parity vectors (S4b-BL-234; `feat/ai-abusive-words-rule`; [ai-design](../ai/ai-design.md) 16.11): the Extract, Ask and Plan system texts of the house and of the school gain one rule, *Do not repeat abusive words from the text; stay neutral and factual.* `kinds/kind-ai-vectors.json` `extract.system`, `prompts.ask.system` and `prompts.plan.system` carry it; `../ai/evals/parity-vectors.json` `prompts` (`ask`, `plan.server`, `plan.device`) carries it and gains `neutralRule` and `extract` (the house's Extract system text, which no stack compared before). No other key, no format number changes. |
| 1.59 | 2026-10-15 | Claude (Code), engineer | §8.4 (S4b-BL-290, S4b-BL-313; `feat/kinds-23-server-kind-ai`; [ai-design](../ai/ai-design.md) 16.12): `kinds/kind-ai-vectors.json` `prompts.plan` gains `serverSystem`, the school's planner text for the server's tool-using planner (read by the server's `KindScopedPlanTest`; the website and the phones ignore it); `kinds/space-scope-vectors.json` `assistant` cases gain an optional `serverKinds` (the kinds the server's status named; absent is none) and five cases, and the website's and the phones' readers pass it to `aiShownIn`; `../ai/evals/school-set.json` 0.3 (`input.kind: school` on its nine Ask and Plan cases). The status response of `GET /api/ai/status` gains the optional `kinds`. No format number changes; nothing of the house's is touched. |
| 1.60 | 2026-10-15 | Claude (Code), engineer | §8.4 and the AI parity vectors: `parity-vectors.json` gains `sanitizeNative` (3 cases: numbers in the digits of Indian scripts) and 6 `redact` cases; [`listing-fixtures.json`](listing-fixtures.json) 3 cases (Devanagari, Tamil, Telugu digits); [`india-place-vectors.json`](india-place-vectors.json) gains `borderTowns` and `abroad` for the place lookup (S4b-BL-174b, S4b-BL-174c; [06](../06-test-plan.md) TC-U-257, TC-U-258). All additive: no existing case changed. |
| 1.61 | 2026-10-15 | Claude (Code), engineer | §8.4: **the phone words in the kind files** (S4b-BL-316; `feat/kinds-22-phone-words-in-kind-files`): `kind.schema.json` `words` names 48 more ids after the sixteen (`web` may be null where only the phone has the word), `house.json` and `school.json` give them, and new [`kinds/kind-word-vectors.json`](kinds/kind-word-vectors.json) (format `doorprints-kind-word-vectors/1`) pins the house's keys and texts in en, hi, ta and te as they were before the phones' tables were retired, and the school's keys; read by the website's `kind-words.spec.ts` and `KindWordVectorsTest`. |
| 1.5 | 2026-09-23 | Claude (Cowork), Docs team | **Device note under section 6 rule 6** (Android handover item 19, `android/shared/README.md` §9; it was addressed to Backend, and the Docs team, which owns `docs/**`, applied it so that it lands before the first deploy; [10](../10-sprint-log.md) §11.5 row 19). Rule 6 describes the server import. The note records where the Android device import goes further when it writes a house over a tombstone that has reached the server: it relinks the visits the purge unlinked and re-adds the photos from the backup's bytes under fresh ids, so a device import says the photos **come back**. It also records the one exception (a tombstone not yet pushed was never purged) and that the web importer (S4b-00a) follows the same rule. Nothing else in this file changed; the server's behaviour and wording are unchanged. |
| 1.4 | 2026-09-23 | Claude (Cowork), Docs team | **New section 0, "What an import is"** (Docs team; nothing else in this file changed): the import product definition the owner approved on 2026-09-23 for Sprint 4b story S4b-00 — what an import is, the only two accepted files, what a backup can contain, what an import never contains or changes, the behaviour (with pointers to sections 6 and 7 here), and what is out of scope. Requirements [01](../01-requirements.md) FR-089..FR-097; vocabulary [12](../12-brand-and-naming.md) section G. Sections 1–9 are unchanged and remain the Backend team's. |
| 1.3 | 2026-09-22 | Claude (Cowork) – Backend team | **Three review items closed, and the handover table brought up to date.** (1) **`checklist` is the one lenient always-present field** (sections 3.1 and 4.4). Section 4.4 said an omitted always-present field is refused, while the server's `BackupHouse` and the Android reader both read a missing checklist as `{}` — so an import could clear a house's scores in silence. The format now says what the readers do (absent or `null` → no scores), because "no scores" is a true statement about a house where a defaulted `0, 0` is not; and the server no longer does it silently: `BackupHouse` keeps the `null` (its compact-constructor default is gone), and when a written row has no checklist but the server's copy has scores, the report names the house and the number of scores cleared, in the preview too. Server test `BackupApiTest.aMissingChecklistReadsAsNoScoresAndTheReportSaysWhatItClears`. The Android reader still refuses an explicit `null` there — new ticket **S4-00/g**. (2) **One `data.json` cap: 16 MiB** (section 7, closing [10](../10-sprint-log.md) §11.3 row 7). It was 64 MiB here and in `BackupFormat`, 16 MiB in `:shared` and the web mirror, and 8 MiB effective on the server. 16 MiB is what [01](../01-requirements.md) SEC-041, [02](../02-threat-model.md) T-T8, `:shared` and the web mirror already say, so the server moved: `BackupFormat.MAX_DATA_JSON_BYTES` is 16 MiB and `app.limits.max-import-bytes` defaults to it (`AppProperties`, `application.yml`, `docker-compose.yml`), so any backup a device accepts restores to a server. New backend test `BackupParityTest` pins all six copies, reading the two client constants as source text, and also checks that the web byte golden is still an exact copy of `backup-sample.json`. (3) New ticket **S4-00/f** (AI): `GoldenSetEvalTest` writes to the shared test database without `@ResourceLock("database")`. Section 9 gains a *State* column: S4-00/a and /b are done in the working tree (Android `CanonicalSampleTest` and a grouping `BackupData.of`; the web golden regenerated and byte-identical), so the "known divergence" of section 5 is closed and S4-00/e is reworded — the client coverage it asked Docs to stop claiming now exists. New ticket **S4-00/h** (Docs) carries the cap change into 01/02/10, and S4-00/d gains the extra paths the new test reads. |
| 1.2 | 2026-09-22 | Claude (Cowork) – Backend team | **Says what the sample can and cannot be a golden for.** Version 1.1 called `backup-sample.json` a byte-exact golden for both device writers; it can only be one for the web writer. House 2 has no location, and a whole-number `double` is written `0` by `JSON.stringify` but `0.0` by Jackson and by `kotlinx.serialization`, so an Android byte golden against this file cannot pass. Section 8 now states the byte claim as web-only and pins the other two implementations on the *parsed* document (rows, order, keys, numbers compared as numbers); section 3.1 records the formatting split; ticket S4-00/a asks for a parsed-JSON comparison instead of a byte golden, and new ticket **S4-00/e** asks Docs to stop [06](../06-test-plan.md) TC-I-34 claiming three-way coverage that only exists server-side. Sections 4.4 and 6 also record three server changes: a file that omits (or nulls) `lat`/`lon` is now refused instead of importing the house at `0, 0`, the report's `problems` list is capped like the 400 message is, and the restore-of-a-deleted-house line no longer claims visit links are lost when the same file can re-link them. The server-side javadoc that still said the Android and web tests read this sample (`CanonicalSample`, `BackupApiTest`, `BackupMapper`) now says the opposite, matching S4-00/e. |
| 1.1 | 2026-09-22 | Claude (Cowork) – Backend team | **The sample now proves the ordering rule.** `backup-sample.json` gained a third visit (house 3, arriving *between* house 1's two visits) and a second photo (house 3, created *before* house 1's), so grouped-by-house and globally-sorted output are no longer the same bytes; the old fixture agreed with both by accident and pinned nothing. Section 5 records the resulting **divergence: the Android writer sorts globally** and must be changed (see section 9). Section 6 gains the three import rules that were implemented but undocumented: `createdAt` is taken from the file on an update too, restoring a deleted house cannot bring its photos or visit links back, and a restore of more than 50 houses does not update the AI index row by row. New section 9 lists the open handovers to the Android, Web, Docs and DevOps teams. |
| 1.0 | 2026-09-22 | Claude (Cowork) – Backend team | First version (S4-00). Pins the format id, the entities, the field names, the null semantics (NFR-025), the ordering and the limits that [11](../11-feature-parity-and-export-spec.md) section 5.2 describes in prose. Adds `backup-sample.json` as the canonical golden file, and records the server's half of the format: `GET /api/export` emits it, `POST /api/import` accepts it. |
| 1.62 | 2026-10-15 | Claude (Code), engineer | §8.4 (`fix/kinds-default-and-house-off`; [03](../03-design.md) §18.18): `kinds/space-scope-vectors.json` gains a `createKind` section (five cases: the kind a new entry is made as from the space it is made in, the enabled kinds and the default; a kind that is off takes no new entry) and two `contains` cases (the school alone holds the kept house entries; two kinds with the school the default). Read by the website's `space-scope.spec.ts` and the phones' `SpaceScopeVectorsTest`. No format number changes; nothing of the house's is touched. |
| 1.63 | 2026-10-15 | Claude (Code), docs | **Stale "developer flag" wording** (`docs/school-compiled-in-wording`): §8.4 and the `kinds/school.json` and `kind-ai-vectors.json` notes say the school is compiled into the releases but off, and the AI reads kinds while the switch has the school offered or the developer flag is on (tests); no vector or pin changed. |
| 1.64 | 2026-10-15 | Claude (Code), engineer | §8.4: **the kind files' `capabilities`** (kinds PR 25; `feat/kinds-25-capabilities-schema`; [03](../03-design.md) §18.19): `kind.schema.json` gains the optional `capabilities` block, `house.json` and `school.json` declare what they do today, and new [`kinds/capabilities-vectors.json`](kinds/capabilities-vectors.json) (format `doorprints-capabilities-vectors/1`) is read by the website and the phones. Schema and resolver only; no wire, backup or sync format changes, and nothing reads the block yet. |
| 1.65 | 2026-10-15 | Claude (Code), engineer | §8.4: **Hunt as a capability** (kinds PR 26; `feat/kinds-26-hunt-capability`; [03](../03-design.md) §18.20): `kind.schema.json`'s `capabilities.hunt` is `{alertRadiusM 30..300, stayCreates, streetMemory, areas}` (all four required; PR 25 had 20..500 and two keys), `house.json` keeps 30 m with everything on and `school.json` gains Hunt (100 m, everything on); `words` gains twelve ids (`huntSeen` ... `huntNotificationsOff`, phone keys, `web` null), the house's equal to today's strings, in `kind-word-vectors.json` too; `capabilities-vectors.json` updated (the school's block, the new limits, five more invalid cases, one more valid). The kind's `version` stays 1; not a backup or sync change. |
| 1.66 | 2026-10-15 | Claude (Code), engineer | §8.4: **Compare per kind** (kinds PR 27; `feat/kinds-27-compare-capability`; [03](../03-design.md) §18.21): `school.json` gains a `checklist` of eight items, the `checklist` section and `capabilities.compare` (the eight items, `fees`, `commuteMinutes`), `capabilities-vectors.json` and `school-vectors.json` follow, and new [`kinds/compare-vectors.json`](kinds/compare-vectors.json) (format `doorprints-compare-vectors/1`) pins each kind's resolved criteria with direction, the scoring and sample outcomes, read by the website and the phones. No wire, backup or sync format changes: a school's scores travel in the candidate's existing `checklist` map. |
| 1.67 | 2026-10-15 | Claude (Code), engineer | §8.4: **the server reads the kind files** (kinds PR 28; `feat/kinds-28-server-kind-reader`; [03](../03-design.md) §18.22): `kind.schema.json` gains the optional `ai.words` (`noun`, `records`, and `words` with `options` for each typed extra an `ai` slot names; short English words only, no markup, colon or line break), `house.json` gains `{noun: House, records: houses}` and `school.json` the words of its seven extras (the values of `kinds/kind-ai-vectors.json` `kinds[]`); the server packages a byte-identical copy of the two files (`backend/src/main/resources/kinds/`, held by `KindFileResourcesTest`) and reads `capabilities-vectors.json` (all `valid` and `invalid` cases) and `space-scope-vectors.json` (`serverKinds`). Additive: a reader that does not know `ai.words` ignores it. No wire, backup or vector file changed. |
| 1.68 | 2026-10-15 | Claude (Code), engineer | §8.4: **viewings, questions and contacts per kind** (kinds PR 29; `feat/kinds-29-contacts-rooms-viewings`; [03](../03-design.md) §18.23): new [`kinds/school-questions.json`](kinds/school-questions.json) (`doorprints-kind-questions/1`); `kind.schema.json`'s `viewings.questionsSeed` is `house` or `school` and `words` gains eighteen ids; `school.json` lists the `questions` and `viewings` sections and names the new seed; `capabilities-vectors.json` (the school's `viewings`, a valid case, `sectionsFollow.questions`), `kind-word-vectors.json` and `school-vectors.json` follow. The kind's `version` stays 1; not a backup or sync change. |
| 1.69 | 2026-10-15 | Claude (Code), engineer | §8.4: **filters and the baseline kind** (kinds PR 30; `feat/kinds-30-copies-filters`; [03](../03-design.md) §18.24): `kind.schema.json`'s `capabilities` gains `baseline` (a boolean, the house's) and `filters` (at most six extra ids); `house.json` and `school.json` declare them (the school's `board`, `medium`, `busService`); `capabilities-vectors.json` carries both members in every resolved set, two valid and six invalid cases. The kind's `version` stays 1; not a backup or sync change. |
| 1.70 | 2026-10-15 | Claude (Code), engineer | §8.4: **every word has an id** (kinds PR 31; `feat/kinds-31-words-from-files`; [03](../03-design.md) §18.25): `kind.schema.json` `words` names 65 more ids (159 in all; additive), `web` and `android` null where only one stack has the word; the website's and the phones' tables of words are generated from `house.json` and `school.json` by `tools/gen-kind-words.mjs`; `kind-word-vectors.json` pins 143 words after the sixteen; the backend's copies of the two files are updated. |
| 1.71 | 2026-10-16 | Claude (Code), engineer | `test-all.json`, the synthetic conformance kind `zz_all` (kinds PR 32; [03](../03-design.md) §18.26): every capability on, valid against the schema and the Java loader, in no build's list. A kind's `ai.extract` names no number extra (the loader refuses it). |
| 1.72 | 2026-10-16 | Claude (Code), engineer | `kinds/school-questions.json` gains `categoryLabels` (the school's own headers for the bank's six category ids, en, hi, ta and te; [03](../03-design.md) §18.23); the admission question's category is `LEGAL`. The house's headers are unchanged. |
| 1.73 | 2026-10-16 | Claude (Code), engineer | **`photo-restore-vectors.json`** (format `doorprints-photo-restore-vectors/1`, status *proposed*, S4b-BL-293, restoring photos from Drive): eight `plan` cases (which photos belong to a restored candidate: deleted a minute before it or later; the exact 30-day edge; a Drive file named by the sync file's row or by the device's own bookkeeping) and eight `summary` cases (the reasons in a fixed order, photos nobody recorded); read by the website's `data/drive/photo-restore-rules.spec.ts` and the phones' `PhotoRestoreVectorsTest`. Not part of any backup or sync format. |
| 1.74 | 2026-10-16 | Claude (Code), engineer | **`photo-sweep-vectors.json`** (format `doorprints-photo-sweep-vectors/1`, status *proposed*, S4b-BL-293, the 30-day clean-up): thirteen cases of which deleted photos' Drive files the backup tidy may take (older than the window and strictly older than the oldest backup that stays; each boundary; no backup staying; a photo with no Drive file; one file named twice); read by the website's `data/drive/photo-sweep-rules.spec.ts` and the phones' `PhotoSweepRulesTest`. Not part of any backup or sync format. |

Related: [11 Feature parity and export spec](../11-feature-parity-and-export-spec.md) section 5.2 ·
[03 Design](../03-design.md) ADR-20 and the API table (`GET /api/export`, `POST /api/import`) ·
[01 Requirements](../01-requirements.md) FR-031, FR-042..FR-047, NFR-023, NFR-025, SEC-041 ·
[06 Test plan](../06-test-plan.md) · [12 Brand and naming](../12-brand-and-naming.md) section G

---

## 0. What an import is

The product definition of an import, **approved by the owner (Sriram) on 2026-09-23** for Sprint 4b story
**S4b-00**, before the web import is built. It applies to every importer: Android, the web app and a self-hosted
server. Requirements: [01](../01-requirements.md) §6.9, FR-089..FR-097. User-facing names:
[12](../12-brand-and-naming.md) section G ("Import a backup", "Full backup", "Readable copy", "Add a shared
listing"). Sections 1–9 below are the format itself.

**0.1 What an import is.** Bringing a Doorprints **Full backup** into this app (Android or web) or into a
self-hosted Doorprints server. Nothing else is called an import.

**0.2 Accepted files: only these two.**

| File | What it holds | Where it comes from |
|---|---|---|
| `Doorprints-backup-YYYY-MM-DD.zip` | `manifest.json`, `data.json`, `photos/` and the readable HTML copy (section 2) | The *Full backup* of the Android app or the web app, manual or the Android weekly backup |
| A bare `data.json` (format `doorprints-backup/1`) | The rows only, **no photo bytes** | For example the server's `GET /api/export` (`Doorprints-backup-<date>.json`) |

The file is recognised by its content (the `format` id and the checks of section 6.1), not by its name.
**Not accepted:** the readable copies (HTML, PDF, CSV, XLSX, Markdown), files from other apps, and arbitrary
spreadsheets. Each is refused with a reason before anything is written.

**0.3 What a backup can contain.**

- **Houses:** label, address, street, locality, location, status, price with rent or sale, BHK, rating, the
  10-point checklist, listing link, notes, and the contact name and phone **only if the backup was made with
  contact details included**.
- **Visits:** place, street, arrived and left times, automatic or manual, and the link to their house (or none,
  for a Hunt-mode visit at a place that is not a house yet).
- **Photos:** JPEG, EXIF stripped.

**0.4 What an import never contains or changes.** Settings, language, server address, API key, Hunt-mode
settings, hunting areas (Sprint 4b), AI settings and the AI index, and trusted devices or sign-in (Sprint 5).
None of these is in the format, and an importer must not change them.

**0.5 Behaviour.**

1. **Validate everything first:** one bad row, and nothing is imported (section 6, rule 1).
2. **Preview the counts** before anything is written: new, newer in the file, newer here (section 6, rule 2).
3. **Merge by `id`, the newest edit wins; identical rows write nothing** (section 6, rule 3).
4. **Never deletes** (section 6, rule 5).
5. **Optional "add as copies"** with new ids, on Android **and on the web** (decision (b) below); the server
   always merges (section 6, rule 9).
6. **A house that was deleted here** is described as section 6, rule 6 says, and the importer never tells the
   user that the links are lost.
7. **Limits** as in section 7: `data.json` at most 16 MiB, at most 5 000 ZIP entries and 1 GiB uncompressed,
   zip slip blocked.

**0.6 Out of scope** (possible later features, each with its own name, never "import"):

- importing from other apps or arbitrary spreadsheets (perhaps with AI-assisted column mapping);
- **Add a shared listing** (Sprint 4b): it creates **one new house** from text or a link shared into the app, and
  is not an import.

**Decisions (owner, 2026-09-23):** (a) **yes**, only Doorprints backups can be imported; (b) **yes**, the web app
gets "add as copies", for parity with Android; (c) importing from other apps or spreadsheets is **later**, as its
own separately named feature, and not in Sprint 4b.

## 1. What this pins

One format, three implementations, no converters:

| Where | File |
|---|---|
| Server | `backend/src/main/java/app/doorprints/server/backup/` (`BackupFormat`, `BackupData`, `BackupHouse`, `BackupVisit`, `BackupPhoto`) |
| Android | `android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup.kt` and `ExportModel.kt` |
| Web / PWA | `web/src/app/export/backup-export.ts` (writer); `backup-reader.ts`, `backup-check.ts`, `import-plan.ts` and `import.service.ts` (reader, S4b-BL-75) |
| The numbers a reader accepts | Kotlin `BackupFormat.READ_IDS`, Java `BackupFormat.READ_IDS`, web `BACKUP_FORMATS_READ` (one constant per stack, section 1.1) |
| Canonical sample | [`backup-sample.json`](backup-sample.json) in this folder; [`update-sample.json`](update-sample.json) for an update file; [`backup-sample-houses.json`](backup-sample-houses.json), the same sample in the family before the reset (section 1.1) |
| The two families (kinds step 3) | Java `BackupWire` (at `POST /api/import` and `GET /api/export`), Kotlin `BackupWire` (`android/shared`, every reader and writer), web `backup-family.ts` (`backup-check.ts`, `backup-reader.ts`, `backup-export.ts`) |
| Shared import vectors | [`import-vectors.json`](import-vectors.json) (section 6.1): Kotlin `ImportVectorsTest` and `BackupReaderParityTest`, web `backup-import.spec.ts` |
| Shared Drive vectors | [`drive-vectors.json`](drive-vectors.json) (section 6.2): Kotlin `DriveVectorsTest`, web `drive-vectors.spec.ts` |
| Encryption vectors | [`hpke-vectors.json`](hpke-vectors.json) and [`dpx-vectors.json`](dpx-vectors.json) (section 6.3): Kotlin `PrimitivesTest`, `HpkeVectorsTest`, `CryptoVectorsTest`, web `hpke-vectors.spec.ts`, `crypto-vectors.spec.ts` |
| Path-trace repeat vectors | [`trace-repeat-vectors.json`](trace-repeat-vectors.json) (section 6.4, **confirmed**): Kotlin `RepeatDetectorVectorsTest` and `PlaceCheckVectorsTest` (`androidHostTest`, as the Drive vectors; a small inline `RepeatDetectorTest` in `commonTest` for the iOS simulator), web `trace-repeats-vectors.spec.ts` and `trace-place-check-vectors.spec.ts` (all 58 cases, built on `feat/path-trace-v2`, PR #146) |

A backup written on a phone must import in a browser and on a server, and the other way round. **Nothing below may
be renamed, reordered or given a new meaning on one side only.** A new field is added to all three at once, always
optional, and old readers ignore what they do not know (`ignoreUnknownKeys` in Kotlin, extra properties ignored in
TypeScript and by Jackson).

### 1.1 Versions

The format id is `doorprints-backup/<n>`. **An optional field on a row that exists may be added within a number**
(old readers ignore it, which loses nothing a person would miss). **A new list at the top level of `data.json`, a
renamed or re-typed field, or a field whose loss would change what a house means, is a new number.** Every reader
accepts `1..MAX` (one constant per stack, listed in section 1) and refuses a higher number as `UNSUPPORTED_VERSION`
with the sentence "made by a newer version of Doorprints; update Doorprints to import it", never importing part of
the file. A writer writes the lowest number that holds everything it writes, so an app whose data fits `/1` keeps
writing `/1` and an older reader can still take its files. `MAX` is 2 since 2026-09-30 (the lists of
[11](../11-feature-parity-and-export-spec.md) 5.30, written from slice 1 of that design on); a `/2` document with none of
the new lists is the same document as `/1`. `MAX` is 3 since 2026-10-01: an update file's `deleted` list (section 3.13,
S4b-BL-82) is a new list, and its loss would keep a house the sender deleted, so an older app refuses such a file with
"update the app" instead; only an update file that carries a deletion is `/3`. The path trace is never in a backup.

**The format reset (2026-10-12, kinds step 3, S4b-BL-206; [03](../03-design.md) ADR-36 §18.4 doors 1 and 2).** The
family restarts at `doorprints-backup/1`, written for every copy, a full one and an update file alike: `search`
(`{"kind":"house"}`, the data set's own object, door 10) after `exportedAt`, the list `candidates` in place of `houses`
(each with `kind`, `money` and `extras`, section 3.0), `candidateId` in place of `houseId` on visits, photos and
viewings, a deletion of kind `candidate` in place of `house`, and `candidates` in place of `houses` in the manifest's
counts. **A reader still reads every file of the family before** (`houses`, `/1` to `/3`), exactly as it did: an older
Doorprints' backup keeps importing, and its houses become candidates in the store. The two `/1` are told apart by
shape: a file with `candidates` is the new family, one with `houses` the family before, one with both is
`BROKEN_DATA`; a file of the new family with a number above `/1` is `UNSUPPORTED_VERSION`. Each stack does this in one
place (section 1, *The two families*): a writer builds the rows it always built and the mapping writes them as the new
family at the end; a reader checks what only the new family has and reads it back as the family before, so one reader
of rows, ranges and ids serves both. The mapping is lossless (section 8): the sample read as the family before is
[`backup-sample-houses.json`](backup-sample-houses.json) byte for byte, and that file written as the new family is the
sample, on the three stacks. What only the new family can break is `BROKEN_DATA`: a candidate without a kind id
(`^[a-z][a-z0-9_]{1,31}$`); `money` that is not an object, has neither an amount nor a cadence, a cadence that is not a
text or an amount that is not whole rupees 0..10¹²; a money extra without its amount or its cadence; `extras` that is not an
object; an extra this reader does not know whose id is not a field id (`^[a-z][A-Za-z0-9]{0,39}$`) or whose value is
not of an extras type (a finite number, a text of at most 2,000 characters, a boolean, or a money object of exactly
an amount and a cadence), or more than 30 of them. **A well-formed file with a cadence this reader does not support** (one
outside the vocabulary `once`, `day`, `week`, `month`, `quarter`, `year`; a house's money that is not `month` or `once`;
`deposit` or `brokerage` not `once`, `maintenance` not `month`) is `UNSUPPORTED_VERSION`, a newer app's file ("update the
app"), not `BROKEN_DATA`. A known extra's value of the wrong type is refused as the field of
the family before was (section 4.4); an out-of-range one is read as before (`floorsLeftBlank`). An unknown extra of an
extras type is read; the website keeps it and writes it back after the known ones, the phone and the server do not
keep it yet.

## 2. The container

A device backup is a ZIP; the server speaks its `data.json` half over HTTP.

```
Doorprints-backup-<UTC date>.zip
  manifest.json            format, app, versions, options, counts, SHA-256 of every other entry
  data.json                the rows (this document)
  photos/<photo id>.jpg    the photo bytes that data.json's photo rows name
  Doorprints-<date>.html   the readable copy, so a backup opens without the app
```

| | Device (ZIP) | Server (HTTP) |
|---|---|---|
| Write | Android `ExportWorker`, web `export.service.ts` | `GET /api/export` → `data.json` as the response body, `Content-Disposition: attachment; filename="Doorprints-backup-<UTC date>.json"` |
| Read | Android/web importer | `POST /api/import` (body = `data.json`), `?dryRun=true` for the preview |
| Photo bytes | `photos/<id>.jpg` in the ZIP | `GET /api/photos/{id}`, uploaded with `POST /api/candidates/{id}/photos` (since #281) |

`manifest.json` (device only) carries `format`, `app`, `appVersion`, `createdAt` (ISO-8601), `language`, `scope`,
`includeRejected`, `photoScope`, `includeContacts`, `counts {candidates, visits, photos}` (`houses` in the family before; and `brokers` and the other lists when they are in the file) and
`files[] {path, sizeBytes, sha256}` (and `deleted` in a `/3` update file, section 3.13). The server has no options and no ZIP, so it writes no manifest; an importer
must not require one when it is handed a bare `data.json`. Since 1.7 an **update file** (sharing updates,
[11](../11-feature-parity-and-export-spec.md) 5.28) may add `sharedSince` (ISO-8601: only rows changed after it are
in the file) and `sharedTo` (the name it was made for), and is named `Doorprints-updates-<UTC date>.zip`; the rows
and the import are exactly a backup's, and a reader uses the two fields for its header only.

## 3. `data.json`

```json
{"format":"doorprints-backup/1","exportedAt":1790072130000,"search":{"kind":"house"},"candidates":[…],"visits":[…],"photos":[…]}
```

Since the reset (section 1.1) the rows of houses are `candidates` (section 3.0) and a visit, photo or viewing names its
house as `candidateId`; the tables below describe each row of **the family before** (`houses`, `houseId`), which every
reader still reads. Each field of section 3.1 has one place in a candidate, named by `was` in
[`kinds/house.json`](kinds/house.json).

### 3.0 Candidate (since the reset, kinds step 3)

The house of section 3.1 with `kind` after `id`, `money` where `price` was, and `extras` after `locationSource`;
`bedrooms`, `areaSqft`, `floor` and `cost` are gone from the row. Key order: `id, kind, label, address, street,
locality, lat, lon, status, money, rating, contactName, contactPhone, listingUrl, notes, locationSource, extras, rooms,
answers, moveIn, brokerId, checklist, createdAt, updatedAt`.

| Field | Type | Always present | Notes |
|---|---|---|---|
| `kind` | string | yes | The kind id; `house` for every candidate today (one kind per data set). |
| `money` | object | no | `{amount?, cadence?}`: whole rupees and how often (`month` for a rent, `once` for a sale). `price` and `priceType` of the family before: `RENT` is `month`, `SALE` `once`, and a price without a type has no cadence (owner, 2026-10-12: never a monthly rent). Each key is left out while unknown (a rent not known yet has no `amount`, a price of type *Not set* no `cadence`); a money with neither is refused; absent when neither is known. |
| `extras` | object | no | Keyed by extra id, in the kind file's order: `bedrooms`, `areaSqft`, `floor`, then the eleven cost fields; `deposit` and `brokerage` are money `once`, `maintenance` money `month`, `myOffer` and `agreedPrice` money as often as the candidate's own money (`month` when it has none), the others as in `cost`. Then an extra a newer app wrote, by id. Absent when empty, never `{}` (a `{}` read is none). Every stack keeps such an extra through an import and writes it back (the website since kinds step 3, the phone and the server since S4b-BL-248: at most 30, a field id with a value of an extras type, text of at most 2,000 characters; `import-vectors.json` `candidatesWrittenBack`). |

| Field | Type | Meaning |
|---|---|---|
| `format` | string | `doorprints-backup/1` today; a reader accepts `/1`, `/2` and `/3` (section 1.1) and refuses a higher number (`UNSUPPORTED_VERSION`, "update the app") or anything that is not `doorprints-backup/<n>` (`NOT_A_BACKUP`). |
| `exportedAt` | number | When the copy was made, epoch milliseconds UTC. The only value in the file that is not user data. |
| `houses`, `visits`, `photos` | arrays | The rows, in the order of section 5. Always present, possibly empty. |

### 3.1 House

| Field | Type | Always present | Notes |
|---|---|---|---|
| `id` | string (UUID) | yes | The merge key. |
| `label` | string | yes | **May be empty** (`""`) for a house saved before it was named. Max 200 characters. |
| `address` | string | no | Max 500. |
| `street` | string | no | Max 200. |
| `locality` | string | no | Max 200. |
| `lat`, `lon` | number | yes | Degrees, WGS 84. `0, 0` means "no location yet", which is what the apps store — house 2 of the sample. A reader must not invent them: a file that leaves one out, or writes `null`, is refused (section 4.4), not imported at `0, 0`. **They are the one place where the three writers differ in *spelling*, not in value:** a whole number is written `0` by `JSON.stringify` and `0.0` by Jackson and by `kotlinx.serialization` (`Double.toString`). Same number, different bytes — see section 8. |
| `status` | string | yes | `NEW`, `SHORTLISTED` or `REJECTED`; since slice 5 also `TAKEN` (the chosen house; the apps keep at most one) and `NOT_CHOSEN` (the end of a hunt), which make the file `/2`. |
| `price` | number | no | Whole rupees, ≥ 0. |
| `priceType` | string | no | `RENT` or `SALE`. |
| `bedrooms` | number | no | ≥ 0. |
| `rating` | number | no | 1..5. |
| `contactName` | string | no | Max 200. Left out entirely when the user exported without contact details. |
| `contactPhone` | string | no | Max 50. Same. |
| `listingUrl` | string | no | Max 1000. |
| `notes` | string | no | Free text, max 20 000. |
| `areaSqft` | number | no | Carpet area in sq ft as the person wrote it, 1..100000 (slice 1a, 2026-09-30). |
| `locationSource` | string | no | `GPS`, `MAP` or `APPROX`: how the location was set; `APPROX` is drawn hollow and never alerts. Absent on a house saved before this. |
| `cost` | object | no | `{deposit, depositMonths, maintenance, maintenanceIncluded, brokerage, brokerageMonths, lockInMonths, noticeMonths, availableFrom, myOffer, agreedPrice}`, each optional (rupees as whole numbers 0..10¹², months 0..120, `availableFrom` a `YYYY-MM-DD` date, `maintenanceIncluded` a boolean). Written only when at least one field is set; an empty object reads as absent. |
| `moveIn` | object | no | `/2` only (slice 5): the move-in record, after `answers` and before `floor`; section 3.14. Absent when it has no date, no notes and no items. |
| `floor` | number | no | `/2` only (S4b-BL-87): the floor the flat is on, a whole number -5..200, 0 the ground floor, below 0 a basement level; after `moveIn` and before `brokerId`. Written whenever it is known, 0 included. A device reader takes a value out of range as unknown, and its import preview counts such houses (`floorsLeftBlank`, S4b-BL-104 d); the server refuses it. |
| `rooms` | array | no | `/2` only (slice 1c): the house's rooms, at most 30, after `cost` and before `brokerId`; section 3.5. Absent when there are none. |
| `checklist` | object | yes (written) | `{item: score}`, score 0..5, item ≤ 100 characters. **Keys sorted alphabetically.** May be `{}`. Unknown keys from a newer app are kept as they are (NFR-025). Every writer emits it, `{}` when there are no scores. **The one lenient field on read:** a reader takes an absent or `null` checklist as `{}` ("no scores") instead of refusing the file — section 4.4 says why this field and no other, and what the server reports when that clears scores. |
| `createdAt`, `updatedAt` | number | yes | Epoch milliseconds UTC. |

### 3.2 Visit

`id` (UUID, always), `houseId` (UUID, absent when the visit's house was deleted), `lat`, `lon` (always),
`street` (≤ 200), `arrivedAt` (always), `leftAt` (absent while a visit is still open), `source`
(`AUTO` or `MANUAL`, always), `updatedAt` (always). Times are epoch milliseconds UTC.

### 3.3 Photo

`id` (UUID), `houseId` (UUID), `fileName` (`<photo id>.jpg`), `createdAt` (epoch milliseconds) — all always
present. Photos are re-encoded to JPEG before they are stored, so the extension is fixed. The row carries **no
bytes**: in a ZIP they are `photos/<fileName>`, on the server `GET /api/photos/{id}`.

**Meta** (`/2` only, slice 5; [11](../11-feature-parity-and-export-spec.md) 5.7), after `createdAt`, each written only when
set: `roomId` (1..64 characters, a room of the same house; it may dangle and then shows as untagged), `tags` (1..10, each a
fixed key `EXTERIOR`, `ENTRANCE`, `KITCHEN_FITTINGS`, `BATHROOM_FITTINGS`, `DAMP`, `CRACK`, `LEAK`, `VIEW`, `WATER_TANK`,
`METER`, `PARKING`, `LIFT`, `GOOD_POINT`, `PROBLEM`, `MOVE_IN` or the person's own text of 1..30 characters; no two the same
ignoring case, and an own tag never equal to a fixed key in any case; never `[]`), `caption` (1..200), `metaUpdatedAt`
(epoch milliseconds above 0, the meta's own last-write-wins clock). A photo with any of them makes the file `/2`. A bad value
refuses the file whole on every reader. An import takes the meta when its `metaUpdatedAt` is newer than the one here; a
photo's meta is not a contact detail, so a copy made without contact details keeps it. On the server they are the
`photo` columns of Flyway V12 and travel in `PhotoDto`.

### 3.4 Broker (`/2`, slice 1b)

A person who shows houses, kept once instead of on every house. `brokers` follows `photos` in `data.json`, present only in a
`/2` file, ordered by `updatedAt` then `id`. Fields, in this order: `id` (string, the merge key), `name` (1..200, always),
`phone` (≤50), `agency` (≤200), `feeTerms` (≤500, free text such as "15 days' rent, once"), `notes` (≤2000), `rating` (1..5),
`updatedAt` (epoch milliseconds, always). A house names its broker with `brokerId` (after `cost`, before `checklist`); the id
may dangle and then reads as no broker. Brokers merge by `id`, the newest `updatedAt` wins, and an import never deletes
one. A copy made without contact details leaves out the list and every `brokerId` and is written as `/1`. On a device a
broker is a record of type `broker` (slice 0) and on a self-hosted server a row of the `record` table.

### 3.5 Room (`/2`, slice 1c)

`rooms` sits inside a house, after `cost` and before `brokerId`; at most 30, ordered by `sort` then `id`, present only when the
house has one (an empty array reads as none). Keys in this order: `id` (string, unique within the house, `[A-Za-z0-9._-]{1,64}` but
not `.` or `..`), `type` (`BEDROOM`, `HALL`, `KITCHEN`, `BATHROOM`, `BALCONY`, `POOJA`, `STUDY`, `UTILITY`, `STORE`, `OTHER`;
an unknown value reads as `OTHER`), `name` (≤60, may be blank), `lengthCm` and `widthCm` (0..5000, whole centimetres, absent when
unknown), `condition` (1..5, absent when not checked), `notes` (≤2000), `sort` (≥0). A file with a bad value, a 31st room or a
duplicate id is refused whole by the server and by the phone. Rooms belong to the house row and merge with it. They are not
contact details, so a copy made without contact details keeps them.

### 3.6 Criteria and preferences (`/2`, slice 2)

The checklist's scoring settings; only records that exist are written, never the defaults. `criteria` and `preferences` follow
`brokers`, each ordered by `updatedAt` then `key`, present only in a `/2` file. **Criterion**, keys in this order: `key` (the merge
key: one of the ten built-in keys `water`, `power`, `parking`, `sunlight`, `ventilation`, `noise`, `security`, `maintenance`,
`neighbourhood`, `commute`, or a custom `c_` plus eight hex characters), `label` (custom criteria only, ≤60; a built-in key with a
label is refused), `weight` (0 Ignore, 1 Low, 2 Medium, 3 High), `mustHave` (boolean), `minScore` (1..5), `sort` (≥0), `archived`
(only when true), `updatedAt`. At most 40 criteria. A built-in with no record is weight 2, not a must-have, minimum score 3, sorted by
its place in the list above. **Preference**: `key`, `value` (string ≤500), `updatedAt`; the one key so far is `score.ratingShare`,
a decimal from 0 to 1 (default 0.5) for how much the star rating counts against the checklist. Scores stay in each house's
`checklist` under the criterion's key; a key that is not a known criterion is ignored by the scoring but kept. A file with a bad
value or a repeated key is refused whole. Merge by `key`, the newest `updatedAt` wins, an import never deletes.

### 3.7 Questions (`/2`, slice 3a)

The question bank, one record each; `questions` follows `preferences`, ordered by `updatedAt` then `id`. Keys in this order: `id` (the merge
key: a seeded default has a fixed id `qd_` plus a name, a custom question `q_` plus eight hex characters; `[A-Za-z0-9._-]{1,64}`), `text` (1..300),
`category` (`MONEY`, `WATER_POWER`, `RULES`, `BUILDING`, `LEGAL`, `OTHER`; unknown reads as `OTHER`), `appliesTo` (`RENT`, `SALE`, `BOTH`; unknown
reads as `BOTH`), `defaultOn` (boolean: asked by the *usual questions* button), `sort` (≥0), `archived` (only when true), `updatedAt`. At most 100
questions. A file with a bad value, a repeated id or more than 100 is refused whole; merge by `id`, newest `updatedAt` wins, an import never deletes.

### 3.8 Answer (`/2`, slice 3a)

`answers` sits inside a house, after `rooms` and before `brokerId`; at most 60, present only when the house has one. Keys in this order: `id`
(unique within the house), `questionId` (the bank question it came from; may dangle), `text` (1..300, the question as asked, a snapshot), `answer`
(1..2000, absent when empty), `status` (`OPEN`, `ANSWERED`, `SKIPPED`), `sort` (≥0). A non-empty answer with status `OPEN` reads as `ANSWERED`, and
`ANSWERED` without an answer reads as `OPEN`. A bad value, a repeated id or a 61st answer is refused whole. Answers belong to the house row and
merge with it; they are not contact details, so a copy made without contact details keeps them.

### 3.9 `default-questions.json`

Not part of the backup: the seed of the question bank, `doorprints-default-questions/1`, fourteen questions with their fixed ids, category, scope,
`defaultOn`, `sort` and the text in `en`, `hi`, `ta` and `te` (the last three under review). Both stacks embed the texts and a test reads this file.

### 3.10 Viewings (`/2`, slice 3b-1)

One row per viewing; `viewings` follows `questions`, ordered by `updatedAt` then `id`. Keys in this order: `id` (`v_` plus eight hex characters from the app; import accepts any `[A-Za-z0-9._-]{1,64}`),
`houseId` (required, at most 64 characters, may name a house that is not in the file), `startsAt` (epoch milliseconds, above 0), `durationMin` (5..480), `kind` (`FIRST`, `SECOND`, `FOLLOW_UP`), `status`
(`PLANNED`, `DONE`, `CANCELLED`), `remindMin` (0, 15, 30, 60, 120 or 1440), `huntReminder` (only when true), `withWhom` (0..200, contact data: left out of a copy made without contact details; only when non-empty),
`notes` (0..2000, only when non-empty), `visitId` (at most 64 characters, only when set), `updatedAt`. A file with a bad value, an unknown `kind` or `status`, a repeated id or more than the row cap is refused whole;
a missing `durationMin`, `kind`, `status` or `remindMin` reads as its default (30, `FIRST`, `PLANNED`, 60). A stored record is coerced on read (a bad value becomes the default, a row with no house or time is skipped).
Merge by `id`, newest `updatedAt` wins, an import never deletes, and a newer row brings a deleted viewing back. "Missed" is never stored: a PLANNED viewing that ended more than two hours ago is shown as missed.
This row is the family before; a file of the new family names the house `candidateId` (§1.1). The viewing *record* that
an import writes (the payload, without `id` and `updatedAt`) names it `candidateId` too since S4b-BL-247 (§6.6).

### 3.11 `viewing-sample.ics`

The calendar file of the sample's viewing `v_a1b2c3d4` (house "Green View 2BHK", address "12, MG Road"), which both stacks must produce byte for byte: CRLF line ends on every line, `DTSTAMP` from the viewing's
`updatedAt` (so it is deterministic), UTC times, `SUMMARY` "Viewing: <house>", `LOCATION` and `DESCRIPTION` only when the address and the notes exist (never `withWhom`), one `VALARM` when `remindMin` is above 0, text
escaped (`\\`, `\;`, `\,`, `\n`), lines folded at 75 octets without splitting a UTF-8 character.

### 3.12 Areas, places and area notes (`/2`, slice 4a)

Three lists after `viewings`, each ordered by `updatedAt` then `id`, each left out when empty. **`areas`** (at most 20): `id` (`a_` plus eight hex; import accepts any `[A-Za-z0-9._-]{1,64}`), `name` (1..100), `lat`, `lon`, `radiusM`
(200..2000; a missing value reads 500; a file with another value is refused), `enabled` (only when false), `updatedAt`. **`places`** (at most 10): `id` (`p_`), `name` (1..60), `lat`, `lon`, `updatedAt`. **`areaNotes`** (at most 200):
`id` (`n_`), exactly one of `areaId` (at most 64 characters; may name an area that is not in the file) or `street` (1..100), `text` (1..1000), `updatedAt`. A file with a bad id, a repeated id, bad coordinates, a blank or over-long
text, a note with neither or both targets, or more than the cap is refused whole. Merge by `id`, newest `updatedAt` wins, an import never deletes, a newer row revives a deleted one. A place is the person's own data, not a contact:
it stays in a copy made without contact details. Which houses a note reaches and the distances are derived, never stored (docs/11 5.8 and the design of slice 4a).

### 3.13 Deletions (`/3`, update files only; S4b-BL-82)

An update file ([11](../11-feature-parity-and-export-spec.md) 5.28) carries the houses deleted on the sender's phone after
`sharedSince` in a top-level **`deleted`** list, the last list of `data.json` (after `areaNotes`), ordered by `updatedAt` then
`id`, left out when empty. Keys in this order: `kind` (`house` so far; a reader ignores a kind it does not know), `id` (a row
id, `[A-Za-z0-9_-]{1,64}`), `updatedAt` (the delete's time, epoch milliseconds). At most 20 000; a blank or over-long kind
(32), a bad id, a negative time, a (kind, id) twice, or a `house` the same file also carries live refuses the file whole. A
writer writes the list only into an update file (`sharedSince` set) and then writes `/3`; a backup, a copy and a full share
never carry one. **Only an update import applies it** (the manifest has `sharedSince`; section 6 rule 5): a MERGE deletes a
live house here whose `updatedAt` is older than the delete, as the person's own delete would (a tombstone stamped now, pushed
on the next sync); a house edited here after the delete, one already deleted here, a COPY and *Keep mine* leave it. The
preview says how many: "Houses deleted by the sender". A bare `data.json` has no manifest and is a restore; the server's
`POST /api/import` is a restore too and ignores the list. The sender's name is not in the file (only `sharedTo`, who it is for).

### 3.14 Moving in (`/2`, slice 5)

`moveIn` sits inside a house, after `answers` and before `floor`. Keys in this order, each optional: `date` (epoch
milliseconds above 0, the move-in day), `notes` (1..2000), `items` (at most 30, ordered by `sort` then `id`): `id` (unique
within the house, `[A-Za-z0-9._-]{1,64}`; a default item has a fixed `mi_` id, an own item `mi_` plus eight hex characters),
`text` (1..200, the words as added; a default is never translated again), `done` (only when true), `sort` (≥0). The object
is absent when it has nothing. A date not above 0, notes over 2000, a 31st item, a bad or repeated id, a blank or over-long
text or a negative sort refuses the file whole; a stored record is coerced on read. It belongs to the house row and merges
with it; it is not a contact detail. Server: `house.move_in jsonb` (Flyway V11), blanked by the tombstone purge.

### 3.15 `default-movein.json`

Not part of the backup: `doorprints-default-movein/1`, the six items *Start moving in* adds (rental agreement, police
verification, ID copies, deposit receipt, meter readings, keys) with their fixed ids, `sort` and the text in `en`, `hi`,
`ta` and `te` (the last three under review). Both stacks embed the texts and a test reads this file
(`DefaultMoveInFileTest`, `move-in.spec.ts`).

## 4. Null semantics (NFR-025)

1. **A field with no value is left out.** Writers never emit `null` (Kotlin `explicitNulls = false`, TypeScript
   `undefined`, Jackson `@JsonInclude(NON_NULL)`). Readers treat **absent and `null` as the same thing: "no value"**,
   so a hand-edited file with explicit `null`s still imports.
2. **Absent is not "unchanged".** A backup is a complete copy of a row, so importing it sets every optional field,
   including clearing one that is absent in the file but filled in here — the whole row wins or loses together, by
   `updatedAt` (section 6). ("Null means unchanged" in [11](../11-feature-parity-and-export-spec.md) is about the
   Sprint 4b sync PUTs of *collections*, not about this file.)
3. **Empty is not absent.** `""` and `{}` are values: an empty `label` is a real (unnamed) house and an empty
   `checklist` really has no scores. Only *missing* means "no value".
4. **The fields that are always there** are listed in section 3 and never omitted, whatever their value. A
   reader refuses a file that omits one of them (or writes `null` there): "no value" is not a legal value for
   those fields, so the server answers 400 rather than filling in a default. `lat`/`lon` are the reason this is
   spelled out — a defaulted `0, 0` is a real place in the Gulf of Guinea, and a truncated or hand-edited file
   would have imported houses there in silence. On the server `lat`/`lon` are boxed (`Double`) in `BackupHouse`
   and `BackupVisit` for exactly this reason; the 400 names the row (*"houses[0].lat is required"*). Server test:
   `BackupApiTest.invalidBackupsAreRefusedWhole` (a house without `lat`, a house with `"lon":null`, a visit without
   `lat`).

   **The one exception is `checklist`.** Writers always emit it, but a reader takes an absent or `null` checklist as
   `{}` — no scores — and imports the row. The rule above exists because a default would *invent* something: `0, 0`
   is a place, `NEW` is a decision nobody made. "No scores" invents nothing; it is exactly what a house nobody has
   scored looks like, and it is what the Android reader in `:shared` (`ExportHouse.checklist = emptyMap()`) and the
   server have always done with a missing one. Refusing it would turn every hand-trimmed file into a 400 for no
   safety gain. What the leniency must not be is **silent**: because the whole row wins (4.2), a newer row without a
   checklist clears the scores the store already has. So the server keeps the `null` in `BackupHouse` (the record
   has no default) and, when it writes such a row over a house that has scores, adds a row line to `problems` —
   *"house &lt;id&gt;: the file has no checklist, which is read as no scores, so the N checklist score(s) this
   server had are cleared"* — in the preview and in the applied import. A create, a house with no scores here, and
   an explicit `{}` (a value, not a missing one) get no line. Server test:
   `BackupApiTest.aMissingChecklistReadsAsNoScoresAndTheReportSaysWhatItClears`. Device importers should show the
   same line in their preview. **Open on Android** (S4-00/g): `:shared` reads an *absent* checklist as `{}` but
   refuses an explicit `null`, which rule 1 says means the same thing.
5. **`deleted` and `syncVersion` are not in the format** as row fields. Tombstones are never exported, and sync state
   belongs to the store that holds it. An imported row is live and, on a device, dirty. (An update file's top-level
   `deleted` list of section 3.13 names deleted rows by id; it is not a row field.)
6. **The sync API is the other way round.** `HouseDto`, `VisitDto` and `PhotoDto` (`GET/PUT /api/houses/{id}` …)
   write every field, `null` included, and a `PUT` is a full replacement: an absent field there also means "no
   value", so it clears the column. See the class comment on `HouseDto`.

## 5. Ordering

Two copies of the same data must be the same file, so the order is fixed:

- houses by `createdAt`, then `id` compared as text;
- visits and photos **grouped by their house**, in that same house order; inside a group, visits by `arrivedAt`
  then `id`, photos by `createdAt` then `id`;
- rows whose house is not in the file come last, in the same sort order. Only the server writes such rows today (a
  visit whose house was deleted keeps its history but loses the link); the device writers walk visits through their
  house and therefore drop those rows. **Known parity gap** — a device copy of server data loses unlinked visits.
- `checklist` keys alphabetically.

An importer must **not** rely on the order: it exists to make two exports comparable, not to carry meaning.

**The sample proves this rule, on purpose.** In [`backup-sample.json`](backup-sample.json) house 3's visit
(`…aaa3`, `arrivedAt` 1788700000000) arrives *between* house 1's two visits, and house 3's photo (`…bbb2`) was
created *before* house 1's. Grouped by house, both of house 3's rows still come last; sorted globally by
`arrivedAt` / `createdAt` they would not. A fixture whose rows do not interleave — the first version of this file, where every visit and the
only photo belonged to house 1 — agrees with both rules by accident and pins neither.
`BackupMapperTest.canonicalSampleOrderIsGroupedByHouseAndTheMapperReproducesIt` asserts both the expected order
*and* that the two rules still disagree on this fixture, so the sample cannot be quietly simplified back.

> **Resolved divergence (S4-00/a, version 1.3).** Until version 1.2 the Android writer sorted all visits and all
> photos globally, so an Android `data.json` and a server/web `data.json` of the same data were different files.
> `BackupData.of(bundle)` in `:shared` now flat-maps over the bundle's houses exactly like the web writer
> (`backup-export.ts`) and the server (`BackupMapper`), and Android's `CanonicalSampleTest` compares its output for
> the shared fixture against the sample (rows, order, key order, numbers as numbers), with the rows fed in shuffled
> so it checks the rule and not the read order. The bundle's own lists stay globally sorted on purpose: the CSV and
> XLSX tables are built from them and are pinned that way on both clients.

## 6. Import and merge

1. **Validate first.** Format id, then every row (required fields, lengths, ranges, duplicate ids, `leftAt` not
   before `arrivedAt`, timestamps inside the clock guard of F-08). One bad row rejects the whole file — nothing is
   half-imported.
2. **Preview** (`?dryRun=true` on the server, a screen on the devices): the same validation, the same decisions and
   the same `problems`, nothing written. Counts are "*a* new, *b* newer in file, *c* newer here".
3. **Merge by `id`, last write wins on `updatedAt`.** Newer in the file → write; newer here → keep; **equal → write
   nothing**, so importing the same file twice changes nothing and syncs nothing.
4. **The whole row is written, `createdAt` included.** An update takes the file's `createdAt` as well as its other
   fields (section 4.2: the row wins or loses together). Keeping the store's own `createdAt` would be a partial
   restore, and because the export is ordered by `createdAt` it would let a restore re-order the very file it was
   made from. Server test: `BackupApiTest.anUpdateTakesTheFilesCreatedAt`.
5. **Never deletes.** A row this store has and the file does not is left alone. The one exception is an **update
   import's deletions** (section 3.13): a device importing an update file deletes the live houses its `deleted` list names
   that are older here than the delete. A restore (a backup, a full share, a bare `data.json`, the server) never does.
6. **Restoring a deleted row brings back an almost empty one.** A delete *purges* the content (F-16): the house's
   photos are tombstoned with their bytes dropped and its visits are unlinked (`houseId` cleared). An import makes
   the house live again, and the photo bytes are gone for good — but a visit *can* come back, because the unlink
   was itself a write: if the same file carries that visit with an `updatedAt` newer than the unlink, rule 3 writes
   the row and `houseId` is restored with it. That is the normal case for a full backup restored over a delete. So
   the server names every restored house in `problems` and says exactly that much:
   *"house &lt;id&gt; was deleted here: the row is restored, and its photos and their bytes are gone for good; a
   visit row in this file re-links to it only if its updatedAt is newer than that delete"*, in the preview as well
   as in the applied import. Device importers should say the same, and must not tell the user the links are lost.
   Server test: `BackupApiTest.restoringADeletedHouseSaysItsPhotosAreGoneAndItsVisitsCanReLink`, which restores a
   house and its visit over a delete and checks both the wording and that the visit is linked again.

   **Device note (Android since `android/shared/README.md` 1.14–1.15; the web importer, S4b-00a, follows the same
   rule).** A device has the backup's photo bytes, which the server does not, so a device import that writes a
   house over a tombstone (the opt-in undelete *Bring them back*, `ImportPlan`'s `restoreDeleted`, or a row in the
   file that is newer than the delete) goes further than the server, once that delete **has reached the server**
   (Android: `deleted = 1 AND dirty = 0`, pushed from this phone or pulled from another device):
   (a) the house's visits that the purge unlinked, and that the device holds as live visits with no house, are
   **relinked** whatever their timestamps: `houseId` is set again and `updatedAt` becomes the later of *now* and
   the visit's own `updatedAt` + 1, so the relink beats the server's unlink on the next sync;
   (b) its photos are **re-added under fresh ids** from the backup's bytes, because the server never takes a
   tombstoned photo id back.
   So a device import says that the photos **come back**, not that they are gone. The one exception: a tombstone
   still waiting to be pushed was never purged, so its visits are still linked and its photos keep their ids. The
   server import's wording above stays as it is, because the server has no bytes. Tests:
   `ImportPlanTest.restoreAfterASyncedDeleteRelinksTheVisitAndGivesThePhotoANewId`,
   `anUpdateOverASyncedTombstoneRelinksTooWithoutTheOptIn` and `aTombstoneThatWasNeverPushedKeepsItsPhotoIds`
   (`:shared`); the server behaviour both parts rely on is pinned by
   `ApiIntegrationTest.aNewerWriteBringsBackAHouseDeletedWithPutAndSyncsIt` and `…WithDeleteAndSyncsIt`.
7. **Photos.** A device import copies `photos/<fileName>` out of the ZIP. A server import has no bytes: the photo
   rows are counted as skipped and the client uploads them with `POST /api/houses/{id}/photos`.
8. **A large restore does not update the AI index row by row** (server only). With `app.ai.enabled=true` and the
   default `app.ai.index-on-change=true`, every changed house is normally embedded after the commit — one provider
   call each, no batching and no quota short-circuit, unlike `POST /api/ai/reindex` (batches of 20, stops on a
   provider 429). With `app.limits.max-import-rows` at 20 000 a per-row fan-out would let one request queue tens of
   thousands of embedding calls and drain a free-tier quota while the caller is told the import succeeded. So the
   server collects the touched house ids instead, publishes **one** change event per house, and above
   `BackupService.MAX_INDEX_EVENTS` (50) publishes **none** and puts this in `problems`:
   *"AI index not updated for N house(s): an import this large is not indexed row by row. If AI search is enabled,
   run POST /api/ai/reindex"*. The line appears whether or not AI is enabled, because the importer does not decide
   that; with AI off it is simply a note about what would need re-indexing. A preview (`?dryRun=true`) publishes
   nothing at all — it writes nothing, so there is nothing to re-index — but it does carry the same line, because
   the preview must show the problems the real import would report. Server test:
   `BackupApiTest.anImportTooLargeToIndexSaysSoInsteadOfFanningOut`.
9. **"Import as a copy"** (new ids for everything) is a device-only option; the server always merges.
10. **`problems` is not exhaustive.** Both the 400 message and the report name at most
    `BackupService.MAX_REPORTED_PROBLEMS` (20) *per-row* problems and then add one tail line — *"and N more"* at
    the end of the 400 message, *"and N more row problem(s) not listed"* as the last entry of the report. Without
    a cap a 20 000-row restore over deleted houses would answer a 16 MiB request with megabytes of near-identical
    lines. The whole-file notes (the photo-bytes line of rule 7
    and the AI-index line of rule 8) are listed first and are never dropped, so a client can rely on those being
    present; a client must not treat the row lines as a complete list, and must not parse them — they are text for
    a human. Server test: `BackupApiTest.aLargeRestoreReportsATailLineInsteadOfOneLinePerHouse` (25 restored houses
    → 20 lines and the tail, in the preview and in the applied import).

### 6.1 The shared import vectors

[`import-vectors.json`](import-vectors.json) (format `doorprints-import-vectors/1`) holds what every device reader must answer
alike: `checks` (a `data.json` and the problem it gets, or none), `merges` (a file, what is here, the mode and flags, and the
preview's counts, deletions of an update file included) and `archives` (a whole file as base64, stored and deflated ZIPs, a
bare `data.json`, and the refusals of sections 6 and 7). Since the reset (section 1.1) the cases are files of the new family,
including what only it can refuse; the cases named "the family before (houses): ..." are the earlier cases unchanged, so
every reader is held to reading an older Doorprints' file as before. `android/shared`'s `ImportVectorsTest` runs them through the common
reader and `ImportPlan`, `android/app`'s `BackupReaderParityTest` through Android's `java.util.zip` reader, and the website's
`backup-import.spec.ts` through its reader and `import-plan.ts`. A check with `candidatesWrittenBack` (S4b-BL-248) is
also read into each stack's own rows and written again, and its `candidates` must be those, byte for byte: on the
website (`backup-import.spec.ts`), the phone (`ImportVectorsTest`, through its database rows) and the server
(`BackupWireTest`). A new rule is a new case here first. The stamps are in the
past (2026-05-28); the photo is 300 bytes whose byte *i* is *i* × 31 mod 256.

### 6.2 The shared Drive vectors

[`drive-vectors.json`](drive-vectors.json) (format `doorprints-drive-vectors/1`, S4b-BL-115; not part of the backup format)
pins what the two Drive clients say to Google Drive v3 and how they read its answers
([15](../15-google-drive-backup-and-sharing.md) §7.1): `queries` (a query and the exact `q` text), `exchanges` (a call, every
request it must make, by method, URL, query parameters, the headers named, the JSON or multipart body or the bytes of an upload
chunk, the answer to give or a dropped connection, and the result: ids, waits, rejected tokens or the error kind), `errors` (a
status, headers and body, and the kind, `Retry-After` in milliseconds, Drive's reason and whether it is retried) and `backoff`
(the rule's defaults and the wait for an attempt, a random value, a kind and a `Retry-After`). `android/shared`'s
`DriveVectorsTest` runs them through `HttpDriveClient` over Ktor's `MockEngine`, the website's `drive-vectors.spec.ts` through
`FetchDriveClient` over a scripted `fetch`. An upload's byte *i* is (*i* × 31 + seed) mod 251; tokens are `token-1`, then
`token-2` after a rejection; waits use the random value 0.5.

### 6.3 The encryption vectors

Not part of the backup format; the Drive encryption of [15](../15-google-drive-backup-and-sharing.md) §9 (S4b-BL-125, §9.9).

- [`hpke-vectors.json`](hpke-vectors.json) (format `doorprints-hpke-vectors/1`, hex): `primitives` (HMAC-SHA-256 from RFC 4231,
  HKDF from RFC 5869, AES-256-GCM from the GCM specification's test cases 13..16, ECDH from RFC 5903 §8.1, P-256 multiples)
  and `hpke.official` (RFC 9180 Appendix A.3.1, DHKEM(P-256, HKDF-SHA256) + HKDF-SHA256 + AES-128-GCM, base mode, with
  sequence numbers 0, 1, 2, 4 and 255) are **published values**, each with its `source`. They were reproduced from the
  published texts and every one also passes an independent implementation (python `cryptography` 50); a published
  value that did not (A.3.1's sequence number 256) was left out rather than guessed. `hpke.regression` (three
  AES-256-GCM base-mode cases, the suite Doorprints uses, for which RFC 9180 prints no vector) are **regression
  vectors**: produced by the Kotlin implementation, then checked against python `cryptography`'s own HPKE (the case with
  empty `info` and AAD) and against an independent HKDF/ECDH/AES-GCM assembly (all cases). Run by `PrimitivesTest`,
  `HpkeVectorsTest` and `hpke-vectors.spec.ts`.
- [`dpx-vectors.json`](dpx-vectors.json) (format `doorprints-dpx-vectors/1`): `recovery` (bytes, text, scalar, public key,
  kid), `recoveryParse` (typed keys and the expected bytes or refusal), `envelope` (`dpx/1` files in deterministic mode:
  folder key, epoch, kid, inner, content key, wrap nonce and nonce prefix given, plaintext byte *i* = (31 *i* + 7) mod
  256; the whole file in base64 up to 4 KiB, its size and SHA-256 always) and `keys` (one random stream, block *i* =
  SHA-256(`doorprints-fake-random` ‖ seed ‖ u32 *i*), device keys from HPKE's DeriveKeyPair; the `keys.json` bytes after
  create, two adds and a revoke, with the folder keys each step opens to). All are **regression vectors** for parity: made
  by `CryptoVectorsTest` with `DPX_VECTORS_OUT` set, checked by an independent decoder (python `cryptography`: every
  wrap, MAC, chain link, recovery anchor, chunk and the canonical JSON), run by `CryptoVectorsTest` and `crypto-vectors.spec.ts`.
  Regenerate only for a deliberate format change, which is a new format number (§1.1).
- [`qr-enrol-vectors.json`](qr-enrol-vectors.json) (format `doorprints-qr-enrol-vectors/1`, hex): the enrolment offer of
  [15](../15-google-drive-backup-and-sharing.md) §9.5, the text `dp1.` plus base64url without padding of `pk_new` (65
  bytes, first byte `0x04`), `s` (32 bytes) and, since S4b-BL-144, one platform byte (1 android, 2 ios, 3 web; an offer without it, 97 bytes, is still valid and its platform unknown). `valid` (offers with the exact bytes, platform and text), `unassignedPlatform` (a platform byte of 0 or not assigned: the offer is read, its platform unknown), `forms` (a bare code,
  surrounding whitespace, a URL fragment or query, a sentence: each still finds a `valid` offer) and `invalid` (a
  non-canonical last character, padding, the standard alphabet, truncated, one byte more or less, a compressed-point or
  other first byte, a wrong prefix, a foreign character, a 5 000-character paste: every stack answers *not an offer*).
  **Regression vectors** made from the rule by a script, not published values. The 8-digit offer code is derived by the
  phones' codec only (the website shows none for a `dp1.` offer), so it has no vector; the phones' 4 096-character cap
  on a paste is the codec's, tested there. Run by `QrEnrolTest` and `qr-enrol.spec.ts`.

### 6.4 The path-trace repeat vectors (confirmed)

Not part of the backup format, and the opposite of it: a walk is location history and is **never** in a backup, a copy, an
update file or a sync (PRV-028, [02](../02-threat-model.md) T-I30). [`trace-repeat-vectors.json`](trace-repeat-vectors.json)
(format `doorprints-trace-repeat-vectors/1`, S4b-FR-13) pins the algorithm of
[11](../11-feature-parity-and-export-spec.md) 5.27.3 so that the phones (`:shared`, Kotlin) and the website (TypeScript), written
separately from that text, give the same answers. It has `constants` (a test compares each with the code's constant, so a
drift fails), `conventions` and 37 `cases` of three kinds: `split` (a flat list of `[lat, lon, atMs]`, `[lat, lon, atMs, walkId]` or, on the
website, `[lat, lon, atMs, walkId, resumed]` and the walks it makes, by input index: a gap of exactly 30 minutes, the walk id,
a walk id of 0 that is no id, unsorted and repeated times, a lone point), `repeats` (walks, or a flat trace to split first, and for each walk its `repeated`
stretches and the part it `shown`, as arc-length metres to 0.5 m: the same street twice, walked back, a junction crossing,
a T-junction, parallel streets 100, 30 and 20 m apart, GPS jitter, an out-and-back in one walk, a second pass 10 minutes
later, a gap that splits a walk, overlaps of 60 and 90 m, a short and a long detour, three walks, a stay, an east-west
street at latitude 12.97, a one-point walk, an invalid latitude, an empty trace, the newest of three walks drawing whatever the input order, a pause with the page hidden) and `alert` (the live walk's kept points
and the other walks, and the indexes at which the alert rings: after 100 m, the cooldown, 60 m is not enough, never for
the walk itself, a parallel street, one bad fix that must not ring again, the walk just finished at 29 minutes 59.999 seconds and at exactly 30). Most cases sit on the equator so the arithmetic is done by hand; the expected values
were produced by a throwaway reference implementation of the text (Python, not committed) and each was checked by hand
against the geometry it names; since the senior review of 2026-10-06 an independent reference written from the text alone
(also Python, not committed) runs all 37 cases and agrees on every one.

**`placeChecks`** (S4b-FR-24, [11](../11-feature-parity-and-export-spec.md) 5.27.13) is a second top-level section of the same file, beside
`cases`, so that a reader of the repeat cases ignores it: 21 cases of the on-demand *Have I been here?* check, each a `place`, `walks` (as above, with the
website's `resumed` fifth number), optional `walkSources` (`trace` or `saved`) and `fixAccuracyM`, and an `expect` of `status` (`walked`, `close`, `none`,
`empty`, `imprecise`, `invalidPlace`), `fuzzy`, `nearestM` and one row per walk within 50 m (newest first: `walkIndex`, `distanceM` to 0.5 m, `atMs` to 1 s,
`band`, `source`); `placeCheckConventions` says so in the file. The same hygiene applies (no case within 0.01 m of 25 or 50, so the inclusive 25.0 is a unit test,
not a vector). The reference is the reviewers' Python (`ref3.py`, extending `ref2.py`, not committed): it passes all 58 cases (37 + 21), and the construction of each
new case (the distance it was built to have) is asserted independently of the reference. The `constants` block gains `nearBandM` and `maxFixAccuracyM`.

**Hygiene.** No case may sit within 0.01 m of `TOLERANCE_M`, `BRIDGE_M`, `MIN_RUN_M` or `ALERT_MIN_RUN_M`, or within 0.01 of a
densify step boundary (`L / 10 + 0.5` integral), because `cos`, `floor` and the constant `K` differ by an ulp between the JVM
and V8; a new case is checked by running the reference with each of those constants moved by 0.02 and requiring the same
results. The `constants` block carries every number of 5.27.2 that the algorithm or its callers read (also `maxWalkPoints` and
`maxDetectionPoints`), so the drift test covers them.

**Status `confirmed`** (2026-10-06, `feat/path-trace-v2`, PR #146: Kotlin `TraceVectorsTest` and the web `trace-repeats-vectors.spec.ts` / `trace-place-check-vectors.spec.ts` run all 58 cases, 37 repeat and 21 place-check, and agree; no expected value was edited to make a test pass). It was `proposed` while the vectors were written with the specification, before any code. The first implementation had to run
every case. If one fails, either the code or the text is wrong: the text and the vector are corrected **in a documented
change to [11](../11-feature-parity-and-export-spec.md) 5.27.3 and this file**, never by editing an expected value to make a
test pass; both stacks pass every case, so the status is `confirmed`. A later change to an expected value reopens it to `proposed` until both stacks pass again. The
look setting (*Clear*, *Subtle*, *Off*) does not change detection and so appears nowhere in the file.

### 6.5 The Drive sync file (`sync-1.schema.json`, `sync-vectors.json`)

Not part of the backup format: the inner format of each device's sync file in Google Drive
([15](../15-google-drive-backup-and-sharing.md) §5.1, S4b-BL-130), read by `SyncFiles.parse` (`android/shared`) and
`parseSyncFile` (`web/src/app/data/drive/sync-file.ts`), both run against `sync-vectors.json`. **Since kinds step 4**
(S4b-BL-206, 2026-10-13) the format number stays `doorprints-sync/1` and the rows follow the reset: the phone writes the
houses under `candidates` as candidates (`kind`, `money`, `extras`, the shape of `/api/candidates`) and a visit's or
photo's house as `candidateId`, and so does the website since S4b-BL-246 (`syncJsonOf` in `sync-file.ts`). On both
stacks only the device's own rows are made in that shape: a row taken from another device's file is written on as it
came, so a `candidates` list may hold a house row without `kind`. Both readers read a file of either shape: `houses`
and `houseId` from a file written before the reset, a house row without `kind` read as the
house it is (the mapping of §3.0, `BackupWire.candidateOfHouse` on the phone, `houseOf` on the website). A file with
both `candidates` and `houses`, or a row with both `candidateId` and `houseId`, is `BAD_ROW`, as a `candidateId` that is
not an id. The earlier vectors keep the shape before and still pass unchanged; the new cases follow the first one. A
case with `from` (S4b-BL-246) also lists the rows the file was made of, in the shape before: the website writes the
file from them byte for byte (`sync-vectors.spec.ts`), and the phone reads each row of the file as the same house,
visit or photo as its row in `from` (`SyncVectorsTest`).
**Key order of a photo row** (S4b-BL-252): a photo row is written as `id`, `candidateId`, the rest of the photo
(`contentType`, `sizeBytes`, ... `deleted`), then `by`, then `driveFileId` and `sha256` when the photo has a Drive file
(the Drive file is added to a row that already names its writer; `withRef` on the website, `SyncRows.withRef` on the
phone). The schema (JSON Schema) does not fix key order, so this note and the vectors do. The case's `from` lists the photo
without its Drive file and `photoRefs` the Drive file by photo id; the website writes the row with `syncRowOf` and `withRef`,
the phone with `SyncRows.photo(dto, by, ref)`, and each compares the photo rows byte for byte, as it does the houses and visits.
A row taken from another device's file keeps the order it came in; both readers read either.

### 6.6 The viewing payload (`viewing-payload-vectors.json`)

Not part of the backup format: the payload of a record of type `viewing` (a device's records table, a Drive sync file's
`records`, the server's `record` table and `/api/records`). **Since S4b-BL-247** (2026-10-13) every writer names the
viewing's candidate `candidateId`: the website's `viewingToPayload`, the phone's and the iPhone's `ViewingType`
(`ViewingPayloadSerializer` in `:shared`) and the server's backup import (`BackupViewing.toPayload`). The keys
are, in this order, `candidateId`, `startsAt`, `durationMin`, `kind`, `status`, `remindMin`, then `huntReminder` (only
when true), `withWhom`, `notes` and `visitId` (each only when set). **A payload written before names it `houseId`, and
every reader reads it the same**: the apps' stores (so the screens, reminders, copies, search and the AI on the device),
the server's AI (`ViewingLine`, and `RecordRepository.findLiveViewingsOfHouse`, which compares
`coalesce(payload ->> 'candidateId', payload ->> 'houseId')`) and its backup export (`BackupMapper`). **No stored row
is rewritten**: there is no Flyway migration (the server keeps its one squashed `V1`, and reading both keys costs one
`coalesce` in one query), and a device rewrites a row only when it is next saved there (then with `candidateId`). A
payload with **both** keys, whatever their values (a `null` too), is a bad row: skipped by every reader, like one with
no start. Each case of `reads` gives a stored payload, the candidate read (`null`: skipped) and, when read, the payload
the stack writes back (`written`), byte for byte; the web's `viewing-payload-vectors.spec.ts`, the phone's
`ViewingPayloadVectorsTest` and the server's `ViewingPayloadVectorsTest` (with `HouseDocumentsTest` for the AI line) read
it. A record's payload stays opaque to the sync file, `/api/records` and `sync-1.schema.json`, which therefore do not change.

## 7. Limits

| Limit | Value | Where |
|---|---|---|
| ZIP entries | 5 000 | device readers |
| Uncompressed size | 1 GiB | device readers |
| Compression ratio | 100:1 | device readers |
| `data.json` size | **16 MiB** (16 777 216 bytes) | every reader: `MAX_DATA_JSON_BYTES` in `:shared` and in the web mirror, `BackupFormat.MAX_DATA_JSON_BYTES` on the server |
| Entry paths | no absolute path, no drive letter, no `..`, no `\` (zip slip) | device readers |
| `POST /api/import` body | `app.limits.max-import-bytes` (`MAX_IMPORT_BYTES`), **16 MiB by default** — the same number → 413 | server |
| `POST /api/import` rows | `app.limits.max-import-rows`, 20 000 by default (houses + visits + photos) → 413 | server |
| API key | required, like every `/api` path | server |

**One number for `data.json`, on purpose.** Until version 1.3 the cap was 64 MiB in this table and in
`BackupFormat`, 16 MiB in `:shared`, and an effective 8 MiB on the server, whose body cap sat below its own
constant ([10](../10-sprint-log.md) §11.3 row 7). A 12 MiB backup made on a phone would then have been refused by
the server, in a format documented as "one format, importable everywhere" ([03](../03-design.md) ADR-20). 16 MiB is
the number [01](../01-requirements.md) SEC-041 and [02](../02-threat-model.md) T-T8 already state, that the only
reader in use (Android) already enforces and that the web mirror already carries, and a real backup is far below it
(a few thousand rows are well under 1 MiB, and the 20 000-row cap is reached long before 16 MiB). The server's body
cap now defaults to it, so any `data.json` a device accepts restores to a server and the other way round. An
operator may lower `MAX_IMPORT_BYTES`; raising it only admits files no device can read back. **`BackupParityTest`**
(backend) pins every copy — the server constant, the `AppProperties` default, `application.yml`,
`docker-compose.yml`, and the two client constants, read as source text — so the number cannot drift apart again
without a red build.

## 8. The canonical sample

[`backup-sample.json`](backup-sample.json) is one line of JSON: exactly what the web writer produces for the
shared fixture (section 8.1 says why that writer and not another). Since the reset (section 1.1) it is the new family;
[`backup-sample-houses.json`](backup-sample-houses.json) is the same sample in the family before (the file as it was up
to 2026-10-12, a `/2` copy), and each stack checks that one reads back as the other, byte for byte, both ways (web
`backup-family.spec.ts`, Kotlin `BackupFieldsTest.theSampleReadsBackAsTheFamilyBeforeAndIsWrittenAgainUnchanged`, Java
`BackupWireTest`). Three live houses — one full (since slice 5 TAKEN, with a move-in record, floor 3), one nearly empty with a `=SUM(...)` label and a Tamil name, one rejected
with an empty label (floor 0). Photo 1 carries room, tags (two fixed keys and an own one), a caption and `metaUpdatedAt` 1790000000000, a time in the past. Three visits: two on house 1, of which the second is still open, and one on house 3 that
*arrives between them*. Two photos: one on house 1 and one on house 3 that was *created before it*. A deleted
house and a deleted visit must **not** appear. The two interleaving rows are what make the ordering rule of
section 5 testable — do not remove them. Ignore the trailing newline; the line itself is the bytes.

### 8.1 What the three implementations owe each other: the same document, not the same bytes

ADR-20 and NFR-023 require that two copies of the same data be **the same document** — same rows, same order, same
keys, same values, no nulls. Byte equality would need one more thing the three runtimes do not share: a single way
to print a number. A whole-number `double` is written `0` by `JSON.stringify` and `0.0` by Jackson *and* by
`kotlinx.serialization` (both go through `Double.toString`), and house 2's `"lat":0,"lon":0` — the "no location
yet" case of section 3.1 — is exactly that. Every other number in this fixture (13.006, 80.2574, 13.05, 80.28,
13.0061, 80.2575 and all the integers) is printed identically by all three, so that one field is the whole of the
divergence. It is a real field, not an artefact: houses with no location exist, so a golden that dodged them would
pin a byte equality that the first real export breaks.

Use the sample as:

- **the byte-exact golden of the web writer** (`web/src/app/export/golden/backup.golden.ts`) — same fixture in,
  same bytes out. The sample is stored the way a browser writes it (one line, `0` for a whole number), so the web
  writer is the one implementation that can be compared byte for byte against it. Ticket S4-00/b regenerates that
  golden for the three-visit fixture (done). Because that golden is a *copy*, the backend's `BackupParityTest`
  checks it against this file byte for byte, so a change here that the web copy has not followed turns the backend
  workflow red — the only workflow that runs when `docs/schemas/` alone changes.
- **the parsed-JSON golden of the server and of Android.** Put the same rows in, export, parse both sides and
  compare: the rows, their order, the key order, the absence of nulls, and every value — with numbers compared
  **as numbers**, so `0` and `0.0` are equal. The server does this in
  `BackupApiTest.exportMatchesTheCanonicalSample` (JSONAssert in `STRICT` mode plus an explicit key-order check,
  ignoring `exportedAt` and the photos' `createdAt`, which the server stamps itself) and, without HTTP, in
  `BackupMapperTest.canonicalSampleOrderIsGroupedByHouseAndTheMapperReproducesIt`. Android does it in
  `android/app/src/test/java/app/doorprints/CanonicalSampleTest.kt` (ticket S4-00/a), which reads this file
  directly. **Do not add a byte-exact `data.json` golden against this file on Android**: it cannot pass while any
  house has `lat`/`lon` at a whole number, which is the normal state of a house with no location.

Do not edit it to make a test pass: change the writers deliberately, then update this file and this document. In
particular, do not "fix" house 2's `0, 0` to a fractional value to make a byte comparison possible — it would buy
a golden that agrees with all three writers on a case none of them will meet in the field.

---

## 8.2 The listing fixtures

`listing-fixtures.json` (format `doorprints-listing-fixtures/1`) is a second, unrelated fixture in this folder: the
share texts a portal's app or a browser hands to Doorprints, in the portals' shapes (hand-written samples; the
numbers and links are made up), with what the no-AI listing parser must read from each
([11](../11-feature-parity-and-export-spec.md) 5.29). `android/shared`'s `ListingFixturesTest` and the web's
`listing-text.spec.ts` run every case, so a change to one parser that the other does not make fails a test. A new
portal shape is a new case here first. Since 1.30 the file also has share texts from many cities (S4b-BL-174): a parser fix that makes one of them read differently on the two stacks fails a test.

## 8.2a The India place vectors

[`india-place-vectors.json`](india-place-vectors.json) (format `doorprints-india-place-vectors/1`) lists 55 places across India with their latitude and longitude and, for the 12 inside the held areas, `heldBy`. The website's `india-boundaries.spec.ts` draws a short admin line at each, through MapLibre's own filter evaluator and the real polygon, at tile zoom 9 and 14; the Android `IndiaBoundaryDataTest` tests the same points against the same polygon. Both must agree with the file, so a place cannot be 'in' on one app and 'out' on the other. The file does not change the boundary data (ADR-22); a new place is a new row here first.

## 8.3 The update sample

[`update-sample.json`](update-sample.json) is the `data.json` of an update file: one changed candidate and one deletion
of kind `candidate` (section 3.13), in the new family (section 1.1; it reads as a `/3` file of the family before). Kotlin's writer writes it byte for byte (`BackupFieldsTest.theUpdateSampleIsWhatThisWriterWritesAndPassesTheCheck`;
no whole-number coordinate in it, section 8.1) and the website reads it (`backup-import.spec.ts`). The web writer writes no
update file yet.

## 8.4 The kind files (`kinds/`, S4b-BL-205)

[`kinds/kind.schema.json`](kinds/kind.schema.json) (JSON Schema 2020-12) describes a kind file, format
`doorprints-kind/1`: what a kind of candidate is ([03](../03-design.md) ADR-36 and §18). [`kinds/house.json`](kinds/house.json)
is today's house written as a kind: the same fields, sections, statuses, checklist and question scopes, with labels as
keys into each stack's dictionaries and `was` naming where each value lives in this format (`price` and `priceType`,
`cost.deposit`, ...). [`kinds/kind-vectors.json`](kinds/kind-vectors.json) (format `doorprints-kind-vectors/1`) pins what
that file resolves to: the labels per stack in the four languages, the field lists, the layout, the visibility, the
search fields and the AI slots. It was written once from the dictionaries and is not regenerated: a change to a vector
belongs with the change it pins. Read by the website's `house-kind-file.spec.ts` (which validates the file against the
schema with Ajv, already in the tree), Android's `HouseKindFileTest` and the server's `HouseKindFileTest`. The format reset
(S4b-BL-206, section 1.1) moved the values to `kind`, `money` and `extras`; `was` stays, since the family before is still
read, and names where each value lives there.

[`kinds/kind-label-vectors.json`](kinds/kind-label-vectors.json) (format `doorprints-kind-label-vectors/1`, S4b-BL-207, [03](../03-design.md) §18.7) pins the label and visibility resolver (`KindLabels` in `:shared`, `core/kind-labels.ts` on the website): each case gives a kind, a ref (`kind.new`, `status.NEW`, `section.cost`, `field.money`, `cadence.month`, `check.water`), a money `cadence` where it matters, and the answer: the `web` and `android` keys (`null`: no text on that stack), `visible`, and `vector`, the label of `kind-vectors.json` whose words in the four languages the key must show; or `resolved: null` for a place or kind that does not exist (the caller shows nothing). The keys are the ones the screens read on main when the file was written. Read by the website's `kind-labels.spec.ts`, `:shared`'s `KindLabelsVectorsTest` and `:ui`'s `KindLabelsScreensTest`; the server does not show labels and does not read it.

[`kinds/kind-word-vectors.json`](kinds/kind-word-vectors.json) (format `doorprints-kind-word-vectors/1`, S4b-BL-316, [03](../03-design.md) §18.16) pins the 143 words that name the kind beyond the sixteen of `words` (48 phone words of S4b-BL-316, the Hunt and viewing words of kinds PR 26 and 29 and, since kinds PR 31, the website's words, [03](../03-design.md) §18.25) (Compare, the Assistant, the copies' scope, the map's location notes, the form's titles and questions, the walk check, the Areas and Places lines): per id the house's `web` and `android` keys and its text in en, hi, ta and te as they were before `SpaceWords` and `PageWords` stopped holding tables, and the school's keys and English text. Readers: web `kind-words.spec.ts`, Android `KindWordVectorsTest`.

[`kinds/kind-field-vectors.json`](kinds/kind-field-vectors.json) (format `doorprints-kind-field-vectors/1`, S4b-BL-208, [03](../03-design.md) §18.8) pins the typed-extras renderer: per kind, each extra's `control` (`number`, `level`, `money`, `bool`, `date`), its `forCadences`, the website's element id, label key, suffix and input attributes, and the phone's label key, keyboard and digits; and `rows`, the ids drawn side by side per stack, section and money cadence (`month`, `once`, none). Written from the hand-written forms on main at 7fbde93c before the renderer existed. Read by the website's `kind-fields.spec.ts` and `house-form-fields.spec.ts` (which also checks it against the page), and on the phone by `:shared`'s `KindFieldsVectorsTest`, `:ui`'s `KindExtrasLayoutTest` and `:app`'s `HouseFormFieldsTest` (which checks it against the form). A placement's `forCadences` (`kind.schema.json`, since S4b-BL-208) names the cadences of the candidate's own money for which the field shows; with no cadence it shows too; `house.json` gives it to the deposit, its months, the maintenance, *Included in the rent*, the lock-in and the notice (`["month"]`).

[`kinds/school.json`](kinds/school.json) (S4b-BL-209, [03](../03-design.md) §18.9) is the second built-in kind, compiled into the release builds of the website, Android and the iPhone but off (it appears only after the device-local switch *Experimental: more than houses*; it is not released, `RELEASED_KINDS` stays the house). The developer flag (the website: a development build with localStorage `doorprints.dev.kinds` = `school`; the phone: a debug build made with `-Pdoorprints.devKinds=true`) now only shows more than the shipped list, in tests. Valid against `kind.schema.json`; no `was` (no school was ever stored in the family before the reset). It hides the rooms and moving-in sections and its core `money` and `brokerId` (its questions and viewings sections are on since kinds PR 29, §18.23, as its checklist is since kinds PR 27); its extras are `board` and `medium` (enum), `grades` (text), `commuteMinutes` (number, `minute`), `busService` (bool), `fees` (money, `year`) and `admissionFee` (money, `once`). A school on the wire, in a backup or in a Drive sync file is a candidate with `kind: "school"` and those extras, which every reader keeps (a reader that does not know the kind keeps them as extras it does not know, §3.0); since S4b-BL-212 a backup's `search` object lists the kinds shown and the default when more than the house is shown (`search-kind-vectors.json`, below), and a stored school is drawn as a school whatever the settings say.

[`kinds/school-vectors.json`](kinds/school-vectors.json) (format `doorprints-school-vectors/1`, S4b-BL-209) pins the school: `kind`, what `school.json` resolves to in the shape of `kind-vectors.json` (sections, the hidden ones, the layout, the extras with their options, search, and the labels per stack in en, hi, ta and te, with `option.<extra>.<id>` for an enum's options); `fields` and `rows`, the renderer's controls and rows per stack in the shape of `kind-field-vectors.json` (no money cadence: a school has none of its own); `backup`, a `doorprints-backup/1` file with one school whose `candidates` every stack reads and writes back byte for byte; and `candidate`, the same school on the wire, which every stack's mapping (the website's IndexedDB and Drive sync rows, `/api/candidates`, the phone's Room row) writes back as it came. Read by the website's `school-kind.spec.ts` and `school-form.spec.ts` (with `school-form.golden.json`), the server's `SchoolKindTest` and `SchoolCandidateApiTest` (PostGIS), and on the phone `:shared`'s `SchoolKindVectorsTest`, `:ui`'s `SchoolKindScreensTest` and `:app`'s `SchoolFormTest`. The house's files and samples are unchanged by it.

[`kinds/kind-search-vectors.json`](kinds/kind-search-vectors.json) (format `doorprints-kind-search-vectors/1`, S4b-BL-210, [03](../03-design.md) §18.10) pins a kind's typed extras in search and the readable copies for `school-vectors.json`'s `candidate`: `searched` (the extras `school.json`'s `search` names), `search.<lang>` (the words they add to the list search, in order, in en, hi, ta and te), `invalid` (wrong values add no word and no row; a switch that is off is a copy's *No*), `flagOff` (a school read with the readers' `on` argument false is searched and copied as a house; since S4b-BL-212 no screen passes false: a stored school is drawn, searched and copied as a school), and `copies.<lang>` (each extra's readable `text`, its CSV `cell`, its XLSX cell type and, since S4b-BL-266, the kind `section` it is written under: `details` or `cost`); the labels are `school-vectors.json`'s per stack. Readers: the website's `kind-search.spec.ts`, the phone's `KindSearchVectorsTest` (`:shared`). A house's copies and search are pinned apart, by the website's `golden/house-pins.golden.ts` and the phone's `HouseCopyPinsTest` and `HouseListSearchTest`.

[`kinds/kind-ai-vectors.json`](kinds/kind-ai-vectors.json) (format `doorprints-kind-ai-vectors/1`, S4b-BL-211, [03](../03-design.md) §18.11, [ai-design](../ai/ai-design.md) §16) pins what the AI sends for a school, read through `school.json`'s `ai` slots while the kinds are on (the switch with the school offered, or the developer flag in tests): `kinds` (each extra's English words and the first line's noun), `documents` (a candidate on the wire and its Ask document lines; with the flag off or for a kind that is not built in, a house's document as before), `ask` (the Ask prompt's user text), `filters` (the Ask filters), `plan` and `planFilters` (the Plan summary, the on-device candidate line, the server's `searchHouses` filters), and `extract` (the school's prompt, its schema's descriptions, and the draft of each raw answer). Since S4b-BL-269 and S4b-BL-270: `kinds[].records` and `refusal` (the records' block name and the Ask refusal sentence), `plan[].summary.extras` and `plan[].line` (a school's candidate line has its details and no price or bedrooms), `details` (the extras the server's `houseDetails` gives, the `ai.ask` slot's), and `prompts` (`ask.system` for the three stacks; `plan.system` and `plan.user` for the website and the phone only, since the server's planner takes no kind). The injection cases use the tag `schools-abc123`. Readers: the server's `AiKindFileTest`, `KindAskVectorsTest`, `KindPlanVectorsTest` and `KindExtractVectorsTest`, and the website's and the phone's tests of their stacked pull requests. A house's AI output is pinned apart, on each stack (the server's `house-ai-pins`). Since S4b-BL-290 `prompts.plan.serverSystem` is the school's system text for the server's tool-using planner (the server only; it is not the devices' `prompts.plan.system`).

[`kinds/house-ai-slot-vectors.json`](kinds/house-ai-slot-vectors.json) (format `doorprints-house-ai-slot-vectors/1`, S4b-BL-271, [03](../03-design.md) §18.11, [ai-design](../ai/ai-design.md) §16.8) holds `house.json`'s `ai` slots to the house's hand-written AI code, which is not read through them: `ask` maps the label of each line of an Ask document (the text before the first colon, `Distance to <place>` as `Distance to`) to the slot ids it carries, `plan` and `extract` do the same for the fields of a Plan candidate and of an extracted draft, `planStructural` and `extractStructural` name the fields that carry no value of the house, and `noAiOnly` the slot ids only the no-AI parsers fill (`areaSqft`: the server's draft has no area). Readers: the website's `core/ai/house-ai-slots.spec.ts`, the server's `HouseAiSlotsTest` (and `HouseKindFileTest` for the extract slot) and the phone's `HouseAiSlotsTest`; each builds a house with every field and fails on a line or field no slot names, an id outside the slot or in `never`, and a slot id no line carries.

[`kinds/search-kind-vectors.json`](kinds/search-kind-vectors.json) (format `doorprints-search-kind-vectors/2`, S4b-BL-212, [03](../03-design.md) §18.12) pins spaces: `setting` (what each stack's resolver answers for the build's available kinds and the stored *Experimental: more than houses*, enabled kinds and default: the rows Settings > What you track offers, the enabled kinds, the default, and whether there are spaces; every doubt is the house alone), `write` (the `search` object of a backup's `data.json`, right after `exportedAt`, the rest of the file unchanged: `{"kind":"house"}` for the house alone, byte for byte the file before; `{"kinds":[...],"defaultKind":"..."}` otherwise; a list that is not well formed is written as the house's) and `read` (the `search` values every reader, the server's included, reads past without refusing the file or changing a candidate; a file without `search` imports as before; only a device that never chose takes `kinds` and `defaultKind` as a hint). The settings are device-local and never synced. The website and the phones (S4b-BL-274, `BackupWire.encodeData`) write the bytes; the server (S4b-BL-280, `BackupWire.searchOf`, read by `BackupWireTest`) writes the same bytes, though its export, not told a device's kinds, writes the house's. Readers: the website's `kind-setting.spec.ts`; the phones' `KindSettingVectorsTest`.

[`kinds/status-scope-vectors.json`](kinds/status-scope-vectors.json) (format `doorprints-status-scope-vectors/1`, S4b-BL-306) pins the TAKEN rule (at most one Taken candidate per kind, never across kinds) and *Close this hunt* (the open candidates of the taken one's own kind only; a candidate with no `kind` is a house, any other id is a kind of its own): `choose` and `close` cases, read by the website's `house-status.spec.ts` and `:shared`'s `StatusScopeVectorsTest`. The server has no such rule. `kinds/search-kind-vectors.json`'s `adopt` section (S4b-BL-307) pins what a device that never chose keeps of a file's `search` list: the built-in kinds only, once each, in the build's order, and no hint when the default is not among them or the house is alone.

[`kinds/space-scope-vectors.json`](kinds/space-scope-vectors.json) (format `doorprints-space-scope-vectors/1`, S4b-BL-279, [03](../03-design.md) §18.13) pins how the tools read one space: `contains` (a space holds a candidate of its own kind; with one space every candidate; a kind with no space here is in the default space), `compare` (the picks kept in order, in the running, at most four, and the live candidates of another space refused; unknown, deleted and out-of-the-running ids dropped), `fileName` (a space's copy puts the lower-case English plural of `kind.many` before the first date; the house's names, backups, a noun with no letters and a name with no date are unchanged), `assistant` (Ask and Plan are shown in a space other than the house only when the build offers the kind and the assistant is the person's own; the server's take no kind) and `shareKind` (the chosen kind when enabled, else the default). Readers: the website's `space-scope.spec.ts`; the phones' `SpaceScopeVectorsTest` (S4b-BL-289). Since S4b-BL-290 an `assistant` case may carry `serverKinds`, the kinds the server's `GET /api/ai/status` named (absent is none): a kind other than the house is also shown when the server named it.

[`kinds/capabilities-vectors.json`](kinds/capabilities-vectors.json) (format `doorprints-capabilities-vectors/1`, [03](../03-design.md) §18.19) pins the kind file's `capabilities` block: `defaults` (a kind with none, and every id in `unknown`: the map and the paths on, the rest off), `kinds` (the resolved set of the house and the school), `today` (the hard-coded behaviour the house's set equals and the sections the switches follow), `valid` (objects and what they resolve to; a feature left out is null) and `invalid` (objects as JSON text, so a `__proto__` key stays an own key, that the schema and both strict readers refuse), with the `icons` and `colours` lists. Since kinds PR 26 ([03](../03-design.md) §18.20) `hunt` is `{alertRadiusM 30..300, stayCreates, streetMemory, areas}` and the school has it (100 m). Readers: the website's `kind-capabilities.spec.ts`; the phones' `KindCapabilitiesVectorsTest`; the server's `KindCapabilitiesFileTest` reads the block's shape and limits. The phones read the Hunt block at run time (`HuntSpace`, `HuntEngine`); the server reads nothing of it.

[`kinds/school-questions.json`](kinds/school-questions.json) (format `doorprints-kind-questions/1`, kinds PR 29, [03](../03-design.md) §18.23) is the school's question seed, a DRAFT for the owner's review: six questions in en, hi, ta and te (hi, ta and te under review) and `categoryLabels` (the bank's six category ids, each worded for a school in the four languages; a category with no label reads the house's word), ids `qd_school_<name>` (a question the person adds in a school space is `q_school_` and 8 hex, so a question's kind is read from its id and no backup, sync or server format changes). The website's `SCHOOL_QUESTIONS` and the phones' `DefaultQuestions.SCHOOL` are copies, read by `question.spec.ts` and `DefaultQuestionsFileTest`; `school.json`'s `questions.seed` names the file and `capabilities.viewings.questionsSeed` is `school` (the schema's enum is `house`, `school`). The kind files' `words` gain eighteen ids for the viewings, the questions and the reminders (`kind-word-vectors.json` pins the house's keys and four-language texts); the school's `sections` gain `questions` and `viewings`.

[`kinds/compare-vectors.json`](kinds/compare-vectors.json) (format `doorprints-compare-vectors/1`, [03](../03-design.md) §18.21) pins Compare per kind: `kinds` (each kind's resolved criteria in the kind file's order, each a checklist item or an extra, with its direction `higher`, `lower` or none, its label key per stack and its English words; a kind with no `compare` has none), `resolve` (lists of ids that must resolve alike: an unknown id is dropped, a text or an enum has no direction), `scoring` (the house's is the person's own, the school's its eight items, Medium each) and `outcomes` (entries with a rating, checklist scores and extras, to the ranking, the overall scores and the cells marked best, worked by hand). Readers: the website's `compare-criteria.spec.ts` and the phones' `CompareCriteriaVectorsTest`.

## 9. Open items (handovers)

Each ticket below is in another team's files, so it is listed here rather than edited across ownership
boundaries. *State* is what the working tree showed on 2026-09-22 when version 1.3 was written; the owning team
closes a ticket by changing its state here (or asking the Backend team to).

| # | Owner | State | What | Why it cannot wait |
|---|---|---|---|---|
| S4-00/a | **Android** | **Done** (committed 2026-09-22) | Group `visits` and `photos` by house before writing `data.json`, and compare `BackupData.of(bundle)`'s output with `backup-sample.json` as **parsed JSON** — not a byte golden, because `kotlinx.serialization` writes house 2's `lat`/`lon` as `0.0` where the sample has `0` (section 8.1). Delivered: `BackupData.of` flat-maps over the bundle's houses (`android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup.kt`), and `android/app/src/test/java/app/doorprints/CanonicalSampleTest.kt` reads the sample itself, with the fixture rows fed in shuffled. | — (was: an Android backup and a server/web backup of the same data were different files.) |
| S4-00/b | **Web** | **Done** (committed 2026-09-22) | Regenerate `web/src/app/export/golden/backup.golden.ts` from the three-visit, two-photo sample and keep the two byte-identical. Delivered: identical, 2 144 bytes plus the file's trailing newline; `exporters.spec.ts` asserts the byte count and the grouped order. The copy is now also checked against the sample from the backend side (`BackupParityTest.theWebGoldenIsTheCanonicalSample`), which answers the golden's own note that nothing compared the two automatically. | — (was: `exporters.spec.ts` kept passing against a stale copy.) |
| S4-00/c | **Docs** | Open | `docs/11-feature-parity-and-export-spec.md` section 5.2 still says "Import writes to Room / IndexedDB only; no server endpoint is needed", and its section 9 note says `/api/import/batch` was dropped because "exports and imports are local". Both contradict the shipped `POST /api/import` (`?dryRun=true` for the preview) that [01](../01-requirements.md) FR-047, [03](../03-design.md) section 9 / ADR-20 and this document describe. `docs/08-operations-runbook.md` (the v0.7 change-log row and section 5, the curl example and the paragraph after it) still calls the API download `doorprints-export-<date>.json` with `format: house-hunt-export/1`; it is `Doorprints-backup-<UTC date>.json` with `doorprints-backup/1`. Bump both documents' version/change log (2026-09-22). | A reader following docs/11 would build a client that never calls the restore endpoint, and the runbook's curl example names a file and a format that do not exist. |
| S4-00/d | **DevOps** | Comment **done** (working tree); **extended in 1.3**, open | The `docs/schemas/**` path-filter comment in `.github/workflows/backend.yml` now names `CanonicalSample` and the tests that use it — done. **New in 1.3:** `BackupParityTest` (also through `CanonicalSample`) reads four files outside `backend/` — `docker-compose.yml`, `web/src/app/export/golden/backup.golden.ts`, `web/src/app/export/backup-export.ts` and `android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup.kt`. Per the workflow's own header rule ("whoever adds such a test adds its path here"), please add those four paths to both the `push` and the `pull_request` lists, with a comment naming the test, and add `BackupParityTest` to the `docs/schemas/**` comment. | Without the four paths, a client change that breaks the shared cap or the web golden copy stays green until the next backend change happens to run the test — the near-miss shape the header describes. |
| S4-00/e | **Docs** | Open, **reworded in 1.3** | Version 1.2 asked Docs to reword [06](../06-test-plan.md) TC-I-34 (line 362) and its v0.14 change-log sentence to "server-side only", because no client test read the sample. With S4-00/a and S4-00/b done that is no longer true, so **do not** make that edit. Instead, make TC-I-34's *State* cell name the tests that now exist: server `BackupMapperTest`, `BackupApiTest.exportMatchesTheCanonicalSample` and `BackupParityTest` (all via `CanonicalSample`); Android `CanonicalSampleTest` (parsed JSON); web `exporters.spec.ts` against `backup.golden.ts` (byte-exact, kept equal to the sample by `BackupParityTest`). Bump that document's version/change log (2026-09-22). | The test plan should credit the coverage by name, so the next review can check it rather than take "all three" on trust. |
| S4-00/f | **AI** | Open, **new in 1.3** | `backend/src/test/java/app/doorprints/server/ai/eval/GoldenSetEvalTest.java` is a `@SpringBootTest` that seeds houses and visits through `PUT /api/houses/{id}` / `PUT /api/visits/{id}` into the database `DB_URL` names — the same database `ApiIntegrationTest` and `BackupApiTest` use, and `BackupApiTest` wipes it with `DELETE /api/data` before every test. Add `@ResourceLock("database")` (`org.junit.jupiter.api.parallel.ResourceLock`) to the class, as those two carry. | It is harmless today only because Surefire runs classes one after another and the class is `@Tag("llm-eval")` + `@EnabledIf` (off in CI). The day JUnit parallel execution is switched on, a `mvn test` with a provider configured would let `BackupApiTest` delete the eval's fixture mid-run and produce a wrong score rather than a failure. The lock is the guard the other two classes already rely on. |
| S4-00/g | **Android** | **Done** (`LenientChecklistSerializer` in `ExportModel.kt` reads `null` as `{}`; noted 2026-09-29) | `ExportHouse.checklist` in `:shared` reads an **absent** checklist as `{}` (the Kotlin default) but **refuses** an explicit `"checklist": null`, while rule 4.1 says absent and `null` are the same and the server reads both as `{}` (section 4.4). Make `null` read as `{}` too — for example a nullable wire property mapped to `emptyMap()`, or a small custom serializer. **Not** `coerceInputValues = true` on the shared `Json`: that would also turn a `null` `status` into its Kotlin default `NEW`, which 4.4 forbids. Then flip the second half of `BackupTest.checklistLeniencyAsItStandsToday`, which was written to change with exactly this decision, and close the joint-decision item its comment points to (`android/shared/README.md` section 9, item 2 — not in that README yet when this was written) with a pointer here: the decision is option (b), *document the leniency, absent or `null`*. Optionally show the server's "checklist score(s) … are cleared" line in the import preview. If Android would rather refuse *both* absent and `null`, that is a joint change: say so here and the server refuses them in the same commit. | A hand-edited or third-party file with `"checklist": null` restores on the server and is refused on the phone — the kind of reader disagreement this document exists to prevent. |
| S4-00/h | **Docs** | Open, **new in 1.3** | The `data.json` cap is now one number, 16 MiB, on every reader and as the server's default body cap (section 7). Close [10](../10-sprint-log.md) §11.3 row 7 (Backend + Web) with that decision. In [01](../01-requirements.md) SEC-041 (status column) and [02](../02-threat-model.md) T-T8 (mitigation column), replace "the server's effective limit is the 8 MiB body cap" with "the server's body cap defaults to the same 16 MiB (`app.limits.max-import-bytes`)", and drop the "the three numbers disagree" clause. Name `BackupParityTest` as the check. Bump the three documents' versions/change logs (2026-09-22). | Three SSDLC documents currently record an open parity defect that the code no longer has, and a server limit it no longer uses. |

Section 5's ordering rule was the one real disagreement between the three writers, and it is closed. What must not
come back is a contract document that one of the three implementations does not follow: if a team wants a rule
changed — the ordering, the checklist leniency, the cap — the change goes into all three implementations, this
document and `backup-sample.json` in one commit.

[`delete-scope-vectors.json`](delete-scope-vectors.json) (format `doorprints-delete-scope-vectors/1`, S4b-BL-287, [02](../02-threat-model.md) T-D15) pins that one delete in a space writes one row. Each case seeds rows (`id`, `kind`, `label`, `deleted`, `dirty`, `updatedAt`), runs `steps` (`delete`, `undo`, `restore`, `deleteSpace`, each with an injected clock `at`; a stamp is the later of `at` and the previous `updatedAt` + 1 ms) and names the expected rows and the ids that must be byte-identical; the list cases name the ids of the *Recently deleted* list per space (`deleted` and `updatedAt` after now - 30 days, newest first; a row with no kind is a house, a kind with no space is the default space's). Status *confirmed*: the website, the phones and the server read it.

[`photo-restore-vectors.json`](photo-restore-vectors.json) (format `doorprints-photo-restore-vectors/1`, S4b-BL-293, [15](../15-google-drive-backup-and-sharing.md) §1.4 item 11, [03](../03-design.md) §20) pins the rules of getting a restored candidate's photos back from the person's own Google Drive. `plan` cases give the candidate (`houseId`, `houseDeletedAt`), `now`, the photo tombstones still stored on the device (`local`: `id`, `deletedAt`), the tombstone rows of the device's own sync file (`file`: `id`, `houseId` or `candidateId`, `deleted`, `deletedAt`, optional `driveFileId` and `sha256`) and the device's own bookkeeping (`refs`), and name the expected `FETCH`, `PAST_WINDOW` or `MISSING` per photo, in order (the local photos first); a photo counts as deleted with its candidate when its tombstone is at most `withCandidateMs` before the candidate's, and as past the window when more than `windowDays` days before `now`. `summary` cases give how many photos the candidate had (`noted`), how many are back, the reasons of the others and how far Drive itself was reached (`connection`), and name the total, the restored count and the reasons once each in a fixed order. Not part of any backup or sync format; status *proposed* until both stacks have read it in a merged pull request.

[`photo-sweep-vectors.json`](photo-sweep-vectors.json) (format `doorprints-photo-sweep-vectors/1`, S4b-BL-293, [15](../15-google-drive-backup-and-sharing.md) §5.1, [03](../03-design.md) §20) pins which deleted photos' encrypted files the backup tidy may move to Drive's bin. `now` and `windowDays` are given once; each case gives `oldestStayingBackupAgoMs` (null when no backup stays), the photos (`id`, `deletedAgoMs`, `driveFileId` or null) and `expect`, the file ids to take, in order, each once. A file is taken when its photo's tombstone is more than the window before `now` and strictly older than the oldest backup that stays. Not part of any backup or sync format; status *proposed* until both stacks have read it in a merged pull request.
