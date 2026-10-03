# Shared schemas: the Doorprints JSON backup

| Field | Value |
|---|---|
| Document | `doorprints-backup/1` — the one backup format for server, Android and web |
| Version | 1.24 |
| Date | 2026-10-02 |
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
| 1.5 | 2026-09-23 | Claude (Cowork), Docs team | **Device note under section 6 rule 6** (Android handover item 19, `android/shared/README.md` §9; it was addressed to Backend, and the Docs team, which owns `docs/**`, applied it so that it lands before the first deploy; [10](../10-sprint-log.md) §11.5 row 19). Rule 6 describes the server import. The note records where the Android device import goes further when it writes a house over a tombstone that has reached the server: it relinks the visits the purge unlinked and re-adds the photos from the backup's bytes under fresh ids, so a device import says the photos **come back**. It also records the one exception (a tombstone not yet pushed was never purged) and that the web importer (S4b-00a) follows the same rule. Nothing else in this file changed; the server's behaviour and wording are unchanged. |
| 1.4 | 2026-09-23 | Claude (Cowork), Docs team | **New section 0, "What an import is"** (Docs team; nothing else in this file changed): the import product definition the owner approved on 2026-09-23 for Sprint 4b story S4b-00 — what an import is, the only two accepted files, what a backup can contain, what an import never contains or changes, the behaviour (with pointers to sections 6 and 7 here), and what is out of scope. Requirements [01](../01-requirements.md) FR-089..FR-097; vocabulary [12](../12-brand-and-naming.md) section G. Sections 1–9 are unchanged and remain the Backend team's. |
| 1.3 | 2026-09-22 | Claude (Cowork) – Backend team | **Three review items closed, and the handover table brought up to date.** (1) **`checklist` is the one lenient always-present field** (sections 3.1 and 4.4). Section 4.4 said an omitted always-present field is refused, while the server's `BackupHouse` and the Android reader both read a missing checklist as `{}` — so an import could clear a house's scores in silence. The format now says what the readers do (absent or `null` → no scores), because "no scores" is a true statement about a house where a defaulted `0, 0` is not; and the server no longer does it silently: `BackupHouse` keeps the `null` (its compact-constructor default is gone), and when a written row has no checklist but the server's copy has scores, the report names the house and the number of scores cleared, in the preview too. Server test `BackupApiTest.aMissingChecklistReadsAsNoScoresAndTheReportSaysWhatItClears`. The Android reader still refuses an explicit `null` there — new ticket **S4-00/g**. (2) **One `data.json` cap: 16 MiB** (section 7, closing [10](../10-sprint-log.md) §11.3 row 7). It was 64 MiB here and in `BackupFormat`, 16 MiB in `:shared` and the web mirror, and 8 MiB effective on the server. 16 MiB is what [01](../01-requirements.md) SEC-041, [02](../02-threat-model.md) T-T8, `:shared` and the web mirror already say, so the server moved: `BackupFormat.MAX_DATA_JSON_BYTES` is 16 MiB and `app.limits.max-import-bytes` defaults to it (`AppProperties`, `application.yml`, `docker-compose.yml`), so any backup a device accepts restores to a server. New backend test `BackupParityTest` pins all six copies, reading the two client constants as source text, and also checks that the web byte golden is still an exact copy of `backup-sample.json`. (3) New ticket **S4-00/f** (AI): `GoldenSetEvalTest` writes to the shared test database without `@ResourceLock("database")`. Section 9 gains a *State* column: S4-00/a and /b are done in the working tree (Android `CanonicalSampleTest` and a grouping `BackupData.of`; the web golden regenerated and byte-identical), so the "known divergence" of section 5 is closed and S4-00/e is reworded — the client coverage it asked Docs to stop claiming now exists. New ticket **S4-00/h** (Docs) carries the cap change into 01/02/10, and S4-00/d gains the extra paths the new test reads. |
| 1.2 | 2026-09-22 | Claude (Cowork) – Backend team | **Says what the sample can and cannot be a golden for.** Version 1.1 called `backup-sample.json` a byte-exact golden for both device writers; it can only be one for the web writer. House 2 has no location, and a whole-number `double` is written `0` by `JSON.stringify` but `0.0` by Jackson and by `kotlinx.serialization`, so an Android byte golden against this file cannot pass. Section 8 now states the byte claim as web-only and pins the other two implementations on the *parsed* document (rows, order, keys, numbers compared as numbers); section 3.1 records the formatting split; ticket S4-00/a asks for a parsed-JSON comparison instead of a byte golden, and new ticket **S4-00/e** asks Docs to stop [06](../06-test-plan.md) TC-I-34 claiming three-way coverage that only exists server-side. Sections 4.4 and 6 also record three server changes: a file that omits (or nulls) `lat`/`lon` is now refused instead of importing the house at `0, 0`, the report's `problems` list is capped like the 400 message is, and the restore-of-a-deleted-house line no longer claims visit links are lost when the same file can re-link them. The server-side javadoc that still said the Android and web tests read this sample (`CanonicalSample`, `BackupApiTest`, `BackupMapper`) now says the opposite, matching S4-00/e. |
| 1.1 | 2026-09-22 | Claude (Cowork) – Backend team | **The sample now proves the ordering rule.** `backup-sample.json` gained a third visit (house 3, arriving *between* house 1's two visits) and a second photo (house 3, created *before* house 1's), so grouped-by-house and globally-sorted output are no longer the same bytes; the old fixture agreed with both by accident and pinned nothing. Section 5 records the resulting **divergence: the Android writer sorts globally** and must be changed (see section 9). Section 6 gains the three import rules that were implemented but undocumented: `createdAt` is taken from the file on an update too, restoring a deleted house cannot bring its photos or visit links back, and a restore of more than 50 houses does not update the AI index row by row. New section 9 lists the open handovers to the Android, Web, Docs and DevOps teams. |
| 1.0 | 2026-09-22 | Claude (Cowork) – Backend team | First version (S4-00). Pins the format id, the entities, the field names, the null semantics (NFR-025), the ordering and the limits that [11](../11-feature-parity-and-export-spec.md) section 5.2 describes in prose. Adds `backup-sample.json` as the canonical golden file, and records the server's half of the format: `GET /api/export` emits it, `POST /api/import` accepts it. |

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
| Canonical sample | [`backup-sample.json`](backup-sample.json) in this folder; [`update-sample.json`](update-sample.json) for an update file's `/3` |
| Shared import vectors | [`import-vectors.json`](import-vectors.json) (section 6.1): Kotlin `ImportVectorsTest` and `BackupReaderParityTest`, web `backup-import.spec.ts` |
| Shared Drive vectors | [`drive-vectors.json`](drive-vectors.json) (section 6.2): Kotlin `DriveVectorsTest`, web `drive-vectors.spec.ts` |
| Encryption vectors | [`hpke-vectors.json`](hpke-vectors.json) and [`dpx-vectors.json`](dpx-vectors.json) (section 6.3): Kotlin `PrimitivesTest`, `HpkeVectorsTest`, `CryptoVectorsTest`, web `hpke-vectors.spec.ts`, `crypto-vectors.spec.ts` |

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
| Photo bytes | `photos/<id>.jpg` in the ZIP | `GET /api/photos/{id}`, uploaded with `POST /api/houses/{id}/photos` |

`manifest.json` (device only) carries `format`, `app`, `appVersion`, `createdAt` (ISO-8601), `language`, `scope`,
`includeRejected`, `photoScope`, `includeContacts`, `counts {houses, visits, photos}` (and `brokers` in a `/2` file) and
`files[] {path, sizeBytes, sha256}` (and `deleted` in a `/3` update file, section 3.13). The server has no options and no ZIP, so it writes no manifest; an importer
must not require one when it is handed a bare `data.json`. Since 1.7 an **update file** (sharing updates,
[11](../11-feature-parity-and-export-spec.md) 5.28) may add `sharedSince` (ISO-8601: only rows changed after it are
in the file) and `sharedTo` (the name it was made for), and is named `Doorprints-updates-<UTC date>.zip`; the rows
and the import are exactly a backup's, and a reader uses the two fields for its header only.

## 3. `data.json`

```json
{"format":"doorprints-backup/1","exportedAt":1790072130000,"houses":[…],"visits":[…],"photos":[…]}
```

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
bare `data.json`, and the refusals of sections 6 and 7). `android/shared`'s `ImportVectorsTest` runs them through the common
reader and `ImportPlan`, `android/app`'s `BackupReaderParityTest` through Android's `java.util.zip` reader, and the website's
`backup-import.spec.ts` through its reader and `import-plan.ts`. A new rule is a new case here first. The stamps are in the
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
shared fixture (section 8.1 says why that writer and not another). Three live houses — one full (since slice 5 TAKEN, with a move-in record, floor 3), one nearly empty with a `=SUM(...)` label and a Tamil name, one rejected
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
portal shape is a new case here first.

## 8.3 The update sample

[`update-sample.json`](update-sample.json) is the `data.json` of a `/3` update file: one changed house and one deletion
(section 3.13). Kotlin's writer writes it byte for byte (`BackupFieldsTest.theUpdateSampleIsWhatThisWriterWritesAndPassesTheCheck`;
no whole-number coordinate in it, section 8.1) and the website reads it (`backup-import.spec.ts`). The web writer writes no
update file yet.

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
