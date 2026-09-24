# Shared schemas: the Doorprints JSON backup

| Field | Value |
|---|---|
| Document | `doorprints-backup/1` — the one backup format for server, Android and web |
| Version | 1.6 |
| Date | 2026-09-24 |
| Author | Claude (Cowork) – Backend team |
| Status | Pinned by story S4-00 (Sprint 4a). Changing anything here changes all three implementations at once. |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 1.6 | 2026-09-24 | Claude (Code), engineer | Legacy House Hunt names renamed (owner request of 2026-09-24; [03](03-design.md) ADR-24). Code paths follow the moved packages (`app.doorprints.shared.export`, `app.doorprints.server.backup`). **The format is unchanged**: `doorprints-backup/1` was already the brand name (ADR-20); the pre-Sprint 4a `house-hunt-export/1` was never an import format and is still rejected as not a backup. |
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
| Web / PWA | `web/src/app/export/backup-export.ts` |
| Canonical sample | [`backup-sample.json`](backup-sample.json) in this folder |

A backup written on a phone must import in a browser and on a server, and the other way round. **Nothing below may
be renamed, reordered or given a new meaning on one side only.** A new field is added to all three at once, always
optional, and old readers ignore what they do not know (`ignoreUnknownKeys` in Kotlin, extra properties ignored in
TypeScript and by Jackson).

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
`includeRejected`, `photoScope`, `includeContacts`, `counts {houses, visits, photos}` and
`files[] {path, sizeBytes, sha256}`. The server has no options and no ZIP, so it writes no manifest; an importer
must not require one when it is handed a bare `data.json`.

## 3. `data.json`

```json
{"format":"doorprints-backup/1","exportedAt":1790072130000,"houses":[…],"visits":[…],"photos":[…]}
```

| Field | Type | Meaning |
|---|---|---|
| `format` | string | Always `doorprints-backup/1`. A reader that sees anything else refuses the file (`UNSUPPORTED_VERSION`). |
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
| `status` | string | yes | `NEW`, `SHORTLISTED` or `REJECTED`. |
| `price` | number | no | Whole rupees, ≥ 0. |
| `priceType` | string | no | `RENT` or `SALE`. |
| `bedrooms` | number | no | ≥ 0. |
| `rating` | number | no | 1..5. |
| `contactName` | string | no | Max 200. Left out entirely when the user exported without contact details. |
| `contactPhone` | string | no | Max 50. Same. |
| `listingUrl` | string | no | Max 1000. |
| `notes` | string | no | Free text, max 20 000. |
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
5. **`deleted` and `syncVersion` are not in the format.** Tombstones are never exported, and sync state belongs to
   the store that holds it. An imported row is live and, on a device, dirty.
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
5. **Never deletes.** A row this store has and the file does not is left alone.
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
shared fixture (section 8.1 says why that writer and not another). Three live houses — one full, one nearly empty with a `=SUM(...)` label and a Tamil name, one rejected
with an empty label. Three visits: two on house 1, of which the second is still open, and one on house 3 that
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

## 9. Open items (handovers)

Each ticket below is in another team's files, so it is listed here rather than edited across ownership
boundaries. *State* is what the working tree showed on 2026-09-22 when version 1.3 was written; the owning team
closes a ticket by changing its state here (or asking the Backend team to).

| # | Owner | State | What | Why it cannot wait |
|---|---|---|---|---|
| S4-00/a | **Android** | **Done** (working tree, uncommitted) | Group `visits` and `photos` by house before writing `data.json`, and compare `BackupData.of(bundle)`'s output with `backup-sample.json` as **parsed JSON** — not a byte golden, because `kotlinx.serialization` writes house 2's `lat`/`lon` as `0.0` where the sample has `0` (section 8.1). Delivered: `BackupData.of` flat-maps over the bundle's houses (`android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup.kt`), and `android/app/src/test/java/app/doorprints/CanonicalSampleTest.kt` reads the sample itself, with the fixture rows fed in shuffled. | — (was: an Android backup and a server/web backup of the same data were different files.) |
| S4-00/b | **Web** | **Done** (working tree, uncommitted) | Regenerate `web/src/app/export/golden/backup.golden.ts` from the three-visit, two-photo sample and keep the two byte-identical. Delivered: identical, 2 144 bytes plus the file's trailing newline; `exporters.spec.ts` asserts the byte count and the grouped order. The copy is now also checked against the sample from the backend side (`BackupParityTest.theWebGoldenIsTheCanonicalSample`), which answers the golden's own note that nothing compared the two automatically. | — (was: `exporters.spec.ts` kept passing against a stale copy.) |
| S4-00/c | **Docs** | Open | `docs/11-feature-parity-and-export-spec.md` section 5.2 still says "Import writes to Room / IndexedDB only; no server endpoint is needed", and its section 9 note says `/api/import/batch` was dropped because "exports and imports are local". Both contradict the shipped `POST /api/import` (`?dryRun=true` for the preview) that [01](../01-requirements.md) FR-047, [03](../03-design.md) section 9 / ADR-20 and this document describe. `docs/08-operations-runbook.md` (the v0.7 change-log row and section 5, the curl example and the paragraph after it) still calls the API download `doorprints-export-<date>.json` with `format: house-hunt-export/1`; it is `Doorprints-backup-<UTC date>.json` with `doorprints-backup/1`. Bump both documents' version/change log (2026-09-22). | A reader following docs/11 would build a client that never calls the restore endpoint, and the runbook's curl example names a file and a format that do not exist. |
| S4-00/d | **DevOps** | Comment **done** (working tree); **extended in 1.3**, open | The `docs/schemas/**` path-filter comment in `.github/workflows/backend.yml` now names `CanonicalSample` and the tests that use it — done. **New in 1.3:** `BackupParityTest` (also through `CanonicalSample`) reads four files outside `backend/` — `docker-compose.yml`, `web/src/app/export/golden/backup.golden.ts`, `web/src/app/export/backup-export.ts` and `android/shared/src/commonMain/kotlin/app/doorprints/shared/export/Backup.kt`. Per the workflow's own header rule ("whoever adds such a test adds its path here"), please add those four paths to both the `push` and the `pull_request` lists, with a comment naming the test, and add `BackupParityTest` to the `docs/schemas/**` comment. | Without the four paths, a client change that breaks the shared cap or the web golden copy stays green until the next backend change happens to run the test — the near-miss shape the header describes. |
| S4-00/e | **Docs** | Open, **reworded in 1.3** | Version 1.2 asked Docs to reword [06](../06-test-plan.md) TC-I-34 (line 362) and its v0.14 change-log sentence to "server-side only", because no client test read the sample. With S4-00/a and S4-00/b done that is no longer true, so **do not** make that edit. Instead, make TC-I-34's *State* cell name the tests that now exist: server `BackupMapperTest`, `BackupApiTest.exportMatchesTheCanonicalSample` and `BackupParityTest` (all via `CanonicalSample`); Android `CanonicalSampleTest` (parsed JSON); web `exporters.spec.ts` against `backup.golden.ts` (byte-exact, kept equal to the sample by `BackupParityTest`). Bump that document's version/change log (2026-09-22). | The test plan should credit the coverage by name, so the next review can check it rather than take "all three" on trust. |
| S4-00/f | **AI** | Open, **new in 1.3** | `backend/src/test/java/app/doorprints/server/ai/eval/GoldenSetEvalTest.java` is a `@SpringBootTest` that seeds houses and visits through `PUT /api/houses/{id}` / `PUT /api/visits/{id}` into the database `DB_URL` names — the same database `ApiIntegrationTest` and `BackupApiTest` use, and `BackupApiTest` wipes it with `DELETE /api/data` before every test. Add `@ResourceLock("database")` (`org.junit.jupiter.api.parallel.ResourceLock`) to the class, as those two carry. | It is harmless today only because Surefire runs classes one after another and the class is `@Tag("llm-eval")` + `@EnabledIf` (off in CI). The day JUnit parallel execution is switched on, a `mvn test` with a provider configured would let `BackupApiTest` delete the eval's fixture mid-run and produce a wrong score rather than a failure. The lock is the guard the other two classes already rely on. |
| S4-00/g | **Android** | Open, **new in 1.3** | `ExportHouse.checklist` in `:shared` reads an **absent** checklist as `{}` (the Kotlin default) but **refuses** an explicit `"checklist": null`, while rule 4.1 says absent and `null` are the same and the server reads both as `{}` (section 4.4). Make `null` read as `{}` too — for example a nullable wire property mapped to `emptyMap()`, or a small custom serializer. **Not** `coerceInputValues = true` on the shared `Json`: that would also turn a `null` `status` into its Kotlin default `NEW`, which 4.4 forbids. Then flip the second half of `BackupTest.checklistLeniencyAsItStandsToday`, which was written to change with exactly this decision, and close the joint-decision item its comment points to (`android/shared/README.md` section 9, item 2 — not in that README yet when this was written) with a pointer here: the decision is option (b), *document the leniency, absent or `null`*. Optionally show the server's "checklist score(s) … are cleared" line in the import preview. If Android would rather refuse *both* absent and `null`, that is a joint change: say so here and the server refuses them in the same commit. | A hand-edited or third-party file with `"checklist": null` restores on the server and is refused on the phone — the kind of reader disagreement this document exists to prevent. |
| S4-00/h | **Docs** | Open, **new in 1.3** | The `data.json` cap is now one number, 16 MiB, on every reader and as the server's default body cap (section 7). Close [10](../10-sprint-log.md) §11.3 row 7 (Backend + Web) with that decision. In [01](../01-requirements.md) SEC-041 (status column) and [02](../02-threat-model.md) T-T8 (mitigation column), replace "the server's effective limit is the 8 MiB body cap" with "the server's body cap defaults to the same 16 MiB (`app.limits.max-import-bytes`)", and drop the "the three numbers disagree" clause. Name `BackupParityTest` as the check. Bump the three documents' versions/change logs (2026-09-22). | Three SSDLC documents currently record an open parity defect that the code no longer has, and a server limit it no longer uses. |

Section 5's ordering rule was the one real disagreement between the three writers, and it is closed. What must not
come back is a contract document that one of the three implementations does not follow: if a team wants a rule
changed — the ordering, the checklist leniency, the cap — the change goes into all three implementations, this
document and `backup-sample.json` in one commit.
