# 01: Software Requirements Specification (SRS)

| Field | Value |
|---|---|
| Document | Software Requirements Specification |
| Version | 0.26 |
| Date | 2026-09-24 |
| Author | Claude (Cowork) |
| Status | Draft |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) | First version, based on the code in `backend/`, `android/` and `web/` as of 2026-09-22. |
| 0.2 | 2026-09-22 | Claude (Cowork) | Wave 2: statuses updated after security hardening, Android i18n/a11y, AI UI and CI. New FR-034..FR-041, NFR-017..NFR-020, SEC-026..SEC-030. RTM extended with the new tests. |
| 0.3 | 2026-09-22 | Claude (Cowork) | Sprint 1 fixes and Sprint 2 ([10](10-sprint-log.md)): SEC-002 Impl (32-char minimum), SEC-017 Impl (`APP_API_KEY_NEXT` dual key), SEC-018 Part (signed release APK in CI, R8 still off), SEC-013 (Trivy on a CycloneDX SBOM, Tomcat 11.0.25 override for F-28), SEC-024 (DB image non-root, F-29). CON-03: compileSdk 37, MapLibre GL 6.10. RTM: new web and Android unit tests (TC-U-05..07, TC-U-18..21), TC-I-21, TC-S-14, TC-S-15, AI eval harness TC-AI-09/10. |
| 0.4 | 2026-09-22 | Claude (Cowork) | Sprint 3 ([10](10-sprint-log.md)): RTM row AI-001..AI-012 traces the new AI tests TC-AI-11..14 from [06](06-test-plan.md) §8 (native Gemini embeddings, indexer failure handling, scorecard verdict, embedding provider selection). No requirement text changed. AI-010 status set to **Part**: the contact name still reaches the LLM provider ([02](02-threat-model.md) F-30). |
| 0.5 | 2026-09-22 | Claude (Cowork) | Sprint 3 lead decisions ([10](10-sprint-log.md)): AI-010 back to **Impl** (C-13: the AI team's `ContactRedactor` fixes F-30; waiting on CI); RTM traces TC-AI-15 (contact redaction) and TC-AI-16 (contract tests). F-01 is split in [02](02-threat-model.md) v0.6: SEC-002 and SEC-017 trace to F-01a (Fixed), SEC-025 to F-01b (Open). |
| 0.6 | 2026-09-22 | Claude (Cowork) | PRV-009 stays **Part** now that F-30 is fixed; the status cell gives the reasons (best-effort free-text redaction, pasted listing text sent for extraction, erasure does not reach backups, unsynced devices or data a provider already received) and what is done. No requirement text changed. |
| 0.7 | 2026-09-22 | Claude (Cowork) | Sprint 3 outcome ([10](10-sprint-log.md) §5): AI-010 stays **Impl** and PRV-009 stays **Part**; their "waiting on CI" notes are replaced by the evidence (F-30 closed as Fixed by lead decision: TC-AI-15 green in the Backend workflow on `6a348cc`; the real Gemini eval run 35720654442 is a no-regression check only, its fixtures hold no contact data). AI-012 stays **Part** and records the first real eval result (all 13 cases ran, 12/13 cases passed, all metrics pass except `citationPrecision` 0.86 vs 0.90, E-03). No requirement text changed. |
| 0.8 | 2026-09-22 | Claude (Cowork), Docs team | Product rename to **Doorprints** (tagline "Remember every house you've seen."; [03](03-design.md) ADR-13): sections 1 and 2, SEC-019 and the section 9 privacy introduction use the new name; section 2 says the app is a personal record of the houses the user has seen, not a listings site. RTM row AI-001..AI-012 now also traces **TC-AI-17** (Ask prompt citation and contrast rules, AI-002/AI-003; added in [06](06-test-plan.md) v0.8 but missing here). No requirement text or status changed. |
| 0.9 | 2026-09-22 | Claude (Cowork), Docs team | Product-owner decisions of 2026-09-22 ([11](11-feature-parity-and-export-spec.md) v0.3 D-21, D-22). **AI access policy:** new **AI-013** (cloud AI only for the owner and invited users), **AI-014** (on-device AI for guests via Gemini Nano / ML Kit GenAI Prompt API, otherwise hidden), **AI-015** (hard cost cap on the paid key), **AI-016** (two active providers, Vertex AI and the Gemini API / AI Studio, switchable by configuration; AI Studio code kept). New **PRV-022** (real user data only to a paid tier or Vertex AI; the free AI Studio tier only with synthetic data) and **PRV-023** (on-device AI sends nothing off the phone). Changed: AI-001 and AI-009 (paid, hard-capped key instead of "stays within the free tier"), CON-01 (one owner-paid exception), CON-05. Section 11.3: **bring-your-own-key** is out of scope (rejected). RTM rows for the new IDs. The repository is now public (`Sriram-Codes-SW/doorprints`, MIT). All new IDs are **Plan** except AI-016 (**Part**). |
| 0.10 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes. **AI-015** now names three cap layers: in-app per-user and global daily caps, a Google Cloud **spend cap budget** (Preview; monthly, before credits) on an AI-only project and service, and budget alerts; the Quotas-page limit is optional. AI-016 points to the credential rules (07 §4, 02 T-I22). Links to `ai/vertex-setup.md` marked as being written by the AI team. |
| 0.11 | 2026-09-22 | Claude (Cowork), Docs team | Vertex AI code landed in the same change set, so the "being written" markers on `ai/vertex-setup.md` are removed. **AI-016** now says what is implemented (`AI_PROVIDER` = `aistudio` default or `vertex`; Vertex chat through Spring AI's Google GenAI starter, embeddings through the app's `VertexEmbeddingModel`, Application Default Credentials only, no API key; provider quota errors return `503` with `code: AI_QUOTA_EXHAUSTED` and `Retry-After: 60`); status stays **Part** until the first CI run and the owner's setup steps 8 and 10 (model location, credit check). New **AI-017**: when the Google Cloud spend cap (AI-015) trips, the API returns a clear "cloud AI paused" problem that is not retried, covered by a contract test (review finding: today's quota path covers HTTP 429 only). RTM rows updated with the new Vertex tests (TC-AI-16, TC-AI-18..TC-AI-21 in [06](06-test-plan.md) v0.10). |
| 0.12 | 2026-09-22 | Claude (Cowork), Docs team | **Sprint 3.5 (KMP foundation, commit `8f583af`, [03](03-design.md) ADR-14):** RTM rows FR-005, FR-014, FR-019, FR-020, FR-022, NFR-017, SEC-015 and SEC-026..030 name the shared-module code (`com.househunt.shared.*`: `HouseScore`, `StreetAlerts`, `SyncRules`, Ktor `ApiClient` + `RetryPolicy`, which replaced OkHttp's `RetryInterceptor`) and the new tests TC-U-35 (`ApiClientContractTest`), TC-U-36 (`RoomSchemaTest`), TC-U-37 (iOS compile, `shared-ios.yml`); NFR-013 and CON-03 updated; section 2 and 11.3: a native iOS app stays out of scope (no Apple Developer fee), iPhone users get the PWA, and the shared module keeps an iOS app possible later. **Product-owner decisions of 2026-09-22 (Sprint 4b scope and location permissions):** new section 6.7 **FR-083..FR-088** (Hunt mode reminders before a planned viewing; *Hunting areas* with area wake-up through the Geofencing API), **NFR-030** (area wake-up battery), **PRV-024..PRV-027** (foreground-only location by default; "Allow all the time" only for area wake-up, after a rationale screen; permission re-checks and precise/approximate handling; geofence privacy), **SEC-049** (notification actions and boot receiver); **PRV-001** amended (background location only for area wake-up, opt-in); PRV-010 status notes the Vertex AI residency (chat in `asia-south1`, embeddings on `global`). Detail in [11](11-feature-parity-and-export-spec.md) v0.6 sections 5.16..5.18. |
| 0.13 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes. **FR-084** no longer promises "at most 5 minutes late": the official Android alarms guide (checked 2026-09-22) says `setWindow` windows under 10 minutes are typically clipped to 10 minutes for apps targeting Android 12+ and `setWindow` is not allow-while-idle. The reminder is now exact (`setExactAndAllowWhileIdle`) when "Alarms & reminders" is allowed, otherwise a 10-minute window that ends at the target time (early, never late, except under Doze or battery saver), with a Settings link to the system permission page ([11](11-feature-parity-and-export-spec.md) v0.7 5.16, TC-U-38). **SEC-049**: the geofencing `PendingIntent` is mutable (required by the API) and explicit; notification-action and alarm intents stay immutable. **SEC-021** now names that geofencing `PendingIntent` as the one exception to "PendingIntents are immutable". |
| 0.14 | 2026-09-22 | Claude (Cowork), Docs team | **Sprint 4a (S4-06): local-first web, offline copies and import.** New section 6.8 with **FR-042..FR-048** (six deterministic formats, self-contained HTML/PDF, the exact `doorprints-backup/1` JSON backup, built on the device and offline, options, import with preview and merge/copy, optional weekly Android backup) and **FR-070..FR-073** (installable PWA, local-first web, share target, storage durability), each with the status the Sprint 4a code actually has — FR-046 is **Part** (the *selected houses* scope exists in the model but no UI offers it) and FR-047 is **Part** (import is on Android and the server; **the web app exports but cannot yet import**). New **NFR-021..NFR-027** (export timing, not yet measured; portability; determinism and the mechanics that hold it up; offline; unknown-field survival; install quality; browser-storage durability), **SEC-041** (import validation incl. the zip-slip path rule and the server's size/row caps), **SEC-042** (output encoding: HTML CSP meta, CSV/XLSX formula guard on text cells only, Markdown escaping), **SEC-044** (app-shell-only service worker), **PRV-012** (contacts included by default with the warning; "leave out" also strips them from `data.json`) and **PRV-018** (an exported file leaves our control). **FR-024** amended (the Connect page is no longer the entry point, no route is guarded) and **FR-031** amended (`GET /api/export` now emits `doorprints-backup/1`; `POST /api/import` restores it). RTM rows for all of the above. **AI-012** records the first fully green eval run (35758157317, `provider=vertex`, 13/13 cases and every metric) and states why AI still stays off by default. |
| 0.15 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes — three requirements described controls the shipped code does not have. **FR-048**: the weekly auto backup's status claimed "charging, battery-not-low and an unmetered network"; `AutoBackupWorker.constraints` sets only `setRequiresCharging(true)` and `setRequiresBatteryNotLow(true)` and there is no `setRequiredNetworkType` outside `SyncWorker` (photo upload), so the network clause is dropped and the reason it would be wrong to add is stated (the backup is a local SAF write). If the owner does want one — because the granted folder may be a cloud provider's — it is a gap, recorded in [10](10-sprint-log.md) §11.3, not an implemented control. **SEC-041**: the `data.json` cap is **16 MiB** (`BackupFormat.MAX_DATA_JSON_BYTES` in `:shared`), not 64 MiB, and the status no longer claims "the same rules in the web and server readers" — the server's effective limit is its 8 MiB body cap and the web has no importer, so its mirrored 64 MiB constant enforces nothing; the disagreement (schemas/README.md §7 and `backup-export.ts` say 64 MiB) is now an open item in 10 §11.3 instead of a parity claim. **NFR-027**: the Safari rule reworded to MDN's actual criterion — no interaction in the last seven days of browser use, not "about seven days without a visit" — matching [02](02-threat-model.md) RR-10 v0.16. No requirement added or removed. |
| 0.16 | 2026-09-22 | Claude (Cowork), Docs team | Fifth review round (docs versus the code as built). **FR-070**: "an Install app menu item" → a permanent *Install the app* card on *Your data* plus a one-time banner after the first saved house (30-day "Not now"), and the build-stamped absolute manifest `id`. **FR-072**: the share target now states how the shared text is handled (plain text, stripped from the address bar and the tab's history entry *(overstated, corrected in 0.17: the URL is stripped, the text stays in `history.state`)*, passed on in navigation state; [02](02-threat-model.md) T-I26). **FR-048**: turning the weekly backup off releases the folder grant and forgets the folder; the keep count is a 1–8 slider (stored value clamped to 1–20); persisted grants in [02](02-threat-model.md) T-I25. **SEC-044**: the worker caches this build's files, not "the app shell only" — still never an API response or a cross-origin request. **SEC-041**: the `data.json` cap is one number, 16 MiB, in all three readers (working tree, pinned by `BackupParityTest`, not yet run in CI); the status no longer says the three numbers disagree. RTM rows for FR-042..FR-048, FR-070..FR-073 and SEC-041 name the new tests ([06](06-test-plan.md) v0.17: TC-I-34, TC-U-43..TC-U-47, TC-M-19, TC-M-20). |
| 0.17 | 2026-09-22 | Claude (Cowork), Docs team | Sixth review round. **FR-072** said the shared text is removed from the tab's history entry; only the URL is. Navigation state is kept by Angular in `history.state`, so the text stays in session history until the map strips it (`/`) or the house is saved (`/houses/new`), and an abandoned form keeps it until the tab closes ([02](02-threat-model.md) T-I26 v0.21, residual). **SEC-008** traceability adds [06](06-test-plan.md) TC-U-48 (the web sync's 429 handling). The 0.16 row is annotated. |
| 0.18 | 2026-09-23 | Claude (Cowork), Docs team | **FR-070**: the manifest `id` example follows the owner's hosting decision of 2026-09-23 — Cloudflare Pages at the root of its own origin, so the build writes `"id": "/"`; the GitHub Pages path `/doorprints/` is no longer used ([03](03-design.md) ADR-21). No requirement changes. |
| 0.19 | 2026-09-23 | Claude (Cowork), Docs team | **Owner decisions of 2026-09-23.** (1) **Hosting:** the web app is on **Firebase Hosting** at `https://doorprints.web.app` (Spark plan, no billing account), replacing the Cloudflare Pages plan, which was never set up ([03](03-design.md) ADR-21): FR-070's `id` example and **CON-01** (the web's free tier) and **SEC-012** (the web headers now come from `web/firebase.json`; RTM row with TC-S-23, TC-S-24, TC-M-19) follow. (2) **The approved import definition** (Sprint 4b story S4b-00) becomes new section **6.9, FR-089..FR-097**: what an import is, the only two accepted files, what a backup can contain, what an import never changes, validate-first, preview, merge by newest edit, never delete, add as copies on Android **and web**, and the import screen's wording; FR-047 points to it; other-app and spreadsheet import is added to 11.3 as a later, separately named feature. (3) **FR-038** is renamed *Fill in from listing text* (the Web team renamed the feature and its keys `import.*` → `listingFill.*` on 2026-09-23, so that "import" means only a backup; [12](12-brand-and-naming.md) G.1). (4) **FR-047** status records Android's opt-in undelete of houses deleted on the phone, with visits relinked and photos under fresh ids after a synced delete (`android/shared/README.md` 1.13–1.14; device check 10b not yet run). RTM rows for FR-089..FR-097. |
| 0.20 | 2026-09-23 | Claude (Cowork), Docs team | Traceability only (final Sprint 4a round; [06](06-test-plan.md) v0.21): the RTM rows **FR-001** (TC-U-51, TC-M-23, TC-M-24), **FR-011** (TC-U-53), **FR-042..FR-048** (TC-U-50, TC-U-52, TC-M-21, TC-M-22) and **NFR-020** (TC-A-13, TC-M-22, TC-M-23; [05](05-ux-accessibility-i18n.md) §5.1, §7.2) name the tests of the whole-app UX audit. No requirement changed. |
| 0.21 | 2026-09-24 | Claude (Cowork), Docs team | **Owner decision of 2026-09-24 (P0 on the live site): India's boundaries on the map** ([03](03-design.md) ADR-22). New section **6.10, FR-098**: both apps show India's external boundary as the Government of India depicts it (all of Jammu and Kashmir and Ladakh, including PoK, Gilgit-Baltistan, Shaksgam and Aksai Chin, and Arunachal Pradesh inside India; no Line of Control, Line of Actual Control or other claim line; no switch). **FR-009** points to it. RTM row FR-098 → TC-U-54, TC-U-55, TC-S-25, TC-M-25 ([06](06-test-plan.md) §15). The id follows this document's FR scheme ([README](README.md) *Requirement ID scheme* has no MAP- prefix). |
| 0.22 | 2026-09-24 | Claude (Cowork), Docs team | Round 1 review of the Docs change for India's boundaries. **FR-098** said both apps do it "identically" and draw no other line "at any zoom"; Android has since added a guard to [03](03-design.md) ADR-22 rule 2 (only tile country lines with an adm0 side, so a zoom 0-4 tile shown while a closer tile loads, or offline, never draws its ISO-view line through Kashmir or Arunachal Pradesh; `android/shared/README.md` 1.37) and the web has not. FR-098 now requires it while tiles load and offline, and its status says **Impl on Android, Partial on the web** until Web adds the guard (open parity gap, [11](11-feature-parity-and-export-spec.md) §10). Compared with the code as of 2026-09-23 19:56 UTC (2026-09-24 01:26 IST). |
| 0.23 | 2026-09-24 | Claude (Cowork), Docs team | Docs re-synced with the code at HEAD `3ad2b58` (comment and docs round, no behaviour change). **FR-098** is **Impl on Android and the web**: the web's `shared/india-boundaries.ts` has had rule 2's adm0 clause (`COUNTRY_LINE_RULE`, `COUNTRY_LINE_RULE_LEGACY`) and the tile-zoom guard (`TILE_ZOOM_GUARD`) as committed in `aef007c`, so the 0.22 "open parity gap (web)" no longer holds and is removed. The status names the two known limits (the Assam-Arunachal Pradesh state line from zoom 5, S4b-BL-15; two close lines in the middle sector and the Wakhan, S4b-BL-16). |
| 0.24 | 2026-09-24 | Claude (Code), Docs team | **FR-098** known limits re-synced with branch `fix/india-boundary-lines` (PR #16; CI green on `5af2f4d`, HEAD `9e0036e` running; not deployed): the Assam-Arunachal Pradesh state line is drawn from zoom 5, the two close lines are gone and the India-China border is the outline's alone (S4b-BL-11, -15, -16); the remaining limits are the hand-over steps, the 3-5 km loops at Sikkim's two tri-junctions and no line on the 7 shared stretches at zoom 5+ while tiles load or offline. |
| 0.25 | 2026-09-24 | Claude (Code), Docs team | **FR-098** known limits after the Singalila spur fix (round 2 reviews): the Sikkim tri-junction loops sized (about 13 x 3 km at Nepal-China-India, from about zoom 10; about 2 km at Doklam) and the tile line running on past the hand-over at Jomotsangkha and Longwa from about zoom 10 (a small hook at Jomotsangkha from zoom 9) (S4b-BL-17). |
| 0.26 | 2026-09-24 | Claude (Code), Docs team | §12 RTM: the Android APK and live web UI tests of [06](06-test-plan.md) §16 (PR #18) added to the rows they trace to: **TC-I-35** (emulator and Test Lab smoke tests) to FR-001, FR-006, FR-010 and FR-011; **TC-U-56** (JVM screenshot tests) to FR-041 and NFR-006/NFR-007; **TC-M-26** (the live web UI test after every merge) to FR-041, NFR-006/NFR-007 and FR-098 (map screens only). |

Related: [README](README.md) · [Threat model](02-threat-model.md) · [Design](03-design.md) · [DFDs](04-data-flow-diagrams.md) · [UX/a11y/i18n](05-ux-accessibility-i18n.md) · [Test plan](06-test-plan.md) · [AI docs](ai/)

---

## 1. Purpose

This SRS defines what Doorprints (called House Hunt until 2026-09-22, see [03](03-design.md) ADR-13) must do and how well it must do it. It sets the security, privacy and AI rules the system must follow. It is the baseline for design (03), threat modelling (02) and testing (06).

## 2. Scope

Doorprints ("Remember every house you've seen.") helps one person keep track of the houses they see while looking for a house to rent or buy in India. It is a personal record of the user's own visits, not a property-listings site. While walking or driving around a locality, the app:

- remembers every house viewed: location, notes, star rating, a 10-item checklist, photos, price, contact and status,
- warns when you pass a house you have already seen, or enter a street you have been on before,
- notices when you stay in one place for a few minutes and offers to save it as a house,
- works fully offline on Android and syncs to a small personal server when a network is available,
- offers a web app for reviewing and comparing houses on a larger screen, which since Sprint 4a is **local-first and installable**: it keeps its own copy in the browser (IndexedDB), works offline and needs no server or account, and uses the network only for sync, AI and map tiles,
- saves an offline copy of everything in six formats (HTML, PDF, CSV, XLSX, Markdown and an exact JSON backup) built on the device, and can import a backup again,
- (planned, optional) answers questions about your own hunt with an LLM, pre-fills houses from listing text, plans viewing routes, and exposes house tools over MCP.

| In scope | Out of scope (v1) |
|---|---|
| Android app (sideloaded APK), Spring Boot API, Angular web app | Native iOS app (no Apple Developer fee; iPhone users use the web app / PWA). The Android logic sits in a Kotlin Multiplatform module that already compiles for iOS ([03](03-design.md) ADR-14), so an iOS app stays possible later |
| One user, one shared API key | Multi-user accounts, sharing and roles |
| Free-tier hosting only | Paid hosting, paid map/geocoding APIs |
| Houses, visits, photos, checklist, compare | Listing scraping, property portals integration, payments, legal checks |
| Optional AI features that are off unless configured | AI that acts without user confirmation |

## 3. Glossary

| Term | Meaning |
|---|---|
| House | A place the user viewed or plans to view. Has a point location and attributes. |
| Visit | A time-stamped presence at a location. It can be linked to a house. `AUTO` = found by stay detection, `MANUAL` = user tapped "been here". |
| Hunt mode | Android foreground service that tracks location while the user is actively house hunting. |
| Stay | Remaining within 40 m of an anchor point for at least N minutes (default 4). |
| Sync version | Server-wide number that increases on every write (Postgres sequence `sync_seq`). Clients pull changes with `since=<cursor>`. |
| LWW | Last-write-wins conflict resolution using the record's `updatedAt`. |
| Tombstone | Soft-deleted row (`deleted = true`) kept so the deletion can sync. |
| BHK | Bedrooms-Hall-Kitchen, the Indian way of stating house size. `bedrooms` field. |
| RAG | Retrieval-augmented generation over the user's own notes and visits. |
| MCP | Model Context Protocol. Exposes house tools to an AI client. |

## 4. Personas

| ID | Persona | Goals | Context |
|---|---|---|---|
| PER-1 | **Asha, the hunter** (primary, the only user) | See many houses in a few weekends, don't re-visit rejected ones, compare fairly | Walks and rides through Bengaluru/Chennai/Hyderabad localities with patchy mobile data. One hand on the phone. May prefer Hindi, Tamil or Telugu. |
| PER-2 | **Asha at home** | Review, score and shortlist on a laptop | Uses the web app on a desktop browser |
| PER-3 | **Family member** (read-mostly, informal) | Look at the shortlist | Uses the same web app with the same key. No separate identity in v1. |
| PER-4 | **Operator** (same person) | Deploy for free, keep data safe, rotate keys | GitHub, a free PaaS and a free Postgres |
| PER-A | **Attacker** (anti-persona) | Steal location history or contacts, change data, run up usage | Internet scanner, thief who takes the phone, or a malicious listing text that tries to inject prompts |

## 5. User stories

| ID | As a... | I want to... | So that... | Req |
|---|---|---|---|---|
| US-01 | hunter | save the house I am standing at with one tap | I don't have to type an address | FR-001 |
| US-02 | hunter | add notes, stars, checklist scores, price, BHK, contact and photos | I remember what the house was like | FR-002..FR-007 |
| US-03 | hunter | get an alert when I pass a house I saw before | I don't view it twice or I remember why I rejected it | FR-013 |
| US-04 | hunter | know when I enter a street I have walked before | I can cover new streets instead | FR-014 |
| US-05 | hunter | be asked "are you at a house?" after I stand still for a few minutes | houses get recorded even if I forget | FR-015 |
| US-06 | hunter | use everything without mobile data | poor coverage doesn't stop me | FR-017, NFR-004 |
| US-07 | hunter at home | see all houses on a map and in a list, filter by status | I can plan and decide | FR-009, FR-010, FR-021 |
| US-08 | hunter | compare up to 4 houses side by side | I can decide on a shortlist | FR-011 |
| US-09 | hunter | mark houses shortlisted or rejected | my list stays focused | FR-006 |
| US-10 | operator | protect my data with a secret key and HTTPS | strangers cannot read my location history | SEC-001, SEC-004 |
| US-11 | hunter | export or permanently delete my data | I keep control once the hunt is over | PRV-004, PRV-005 |
| US-12 | hunter | ask "which 2BHKs under 25k had good water?" and get answers with links to the houses | I don't re-read all my notes | AI-002 |
| US-13 | hunter | paste listing text and get the form pre-filled | I save time typing | AI-004 |
| US-14 | hunter | get a suggested order to view today's shortlisted houses | I waste less time travelling | AI-006 |
| US-15 | hunter | use the app in Hindi, Tamil or Telugu and with TalkBack | it works for me and my family | NFR-006, NFR-007 |

## 6. Functional requirements

Priority: **M**ust, **S**hould, **C**ould, **W**on't (this release). Status: **Impl** (in code), **Part** (partial), **Plan** (not yet built).

### 6.1 Houses

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-001 | The user can create a house at the current GPS location ("Save house here"), by long-pressing the map (Android), or by clicking the map (web). The client generates the house ID (UUID v4) so it can be created offline. | M | Impl |
| FR-002 | A house has: label (required, at most 200 chars), address, street, locality, lat/lon, price (INR, ≥ 0), price type RENT/SALE, bedrooms (BHK), contact name, contact phone, listing URL and notes (at most 20 000 chars). | M | Impl |
| FR-003 | The user can give a house a star rating from 1 to 5. | M | Impl |
| FR-004 | The user can score a fixed 10-item checklist from 0 to 5: water, power, parking, sunlight, ventilation, noise, security, maintenance, neighbourhood, commute. Web and Android use the same keys. | M | Impl |
| FR-005 | The system computes an overall score from 0 to 5: the mean of the checklist scores, averaged 50/50 with the star rating when both exist. It is null when nothing has been scored. | S | Impl |
| FR-006 | A house has a status of NEW, SHORTLISTED or REJECTED. The user can change it at any time (state machine in 03 section 8.1). | M | Impl |
| FR-007 | The user can attach photos from the camera or the gallery. Photos are shrunk to 1600 px on the longest side and saved as JPEG before storage/upload. The server accepts JPEG/PNG/WebP up to 5 MB, detected from the bytes, at most 20 per house. | M | Impl |
| FR-008 | The system records visits: MANUAL ("been here now") and AUTO (from stay detection). Visits can be linked to a house. | M | Impl |
| FR-009 | Map view shows all houses as markers coloured by status, on OpenFreeMap vector tiles. India's boundaries on every map follow FR-098. | M | Impl |
| FR-010 | List view with text search (name, street, notes), a status filter and sorting. | S | Impl (Android) |
| FR-011 | Compare view shows 2 to 4 houses side by side with the checklist, rating, price and score. | S | Impl |
| FR-012 | Delete leaves a tombstone so it syncs to other devices. The tombstone keeps no content (see PRV-005). | M | Impl |

### 6.2 Hunt mode (Android)

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-013 | While Hunt mode is on, the user gets a notification when within the alert radius (default 30 m, configurable) of a saved house. Each house alerts at most once per 30 minutes. Tapping the alert opens the house. | M | Impl |
| FR-014 | While Hunt mode is on, reverse geocoding (at most once per 45 s unless moved at least 80 m) finds the current street. If saved houses or visits exist on that street, the user gets a notification ("You've been on X before", counts, first visit date). Each street alerts at most once per 60 minutes. | M | Impl |
| FR-015 | Stay detection: staying within 40 m for at least N minutes (default 4, configurable) creates an AUTO visit. If no house exists within 40 m, a notification asks "Are you at a house?" and opens a new-house form pre-filled with the location. Leaving the place sets `leftAt`. | M | Impl |
| FR-016 | Location fixes with accuracy worse than 50 m are ignored for alerts and stays, and the UI shows "Weak GPS, alerts paused". | M | Impl |
| FR-017 | Hunt mode runs as a visible foreground service with an ongoing notification and a Stop action. It stops when the user turns it off. | M | Impl |
| FR-018 | The Hunt card on the map shows the current street, how many houses and visits exist on it, the nearest saved house within 150 m and the GPS accuracy. | S | Impl |

### 6.3 Offline and sync

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-019 | All Android create/read/update/delete features work without a network, using the on-device Room database and photo files. | M | Impl |
| FR-020 | Sync runs about 3 s after each local change and every 30 minutes, only when a network is available. Failed runs retry with exponential backoff (30 s start, up to 5 attempts); single requests retry up to 3 times with jittered backoff. | M | Impl |
| FR-021 | Sync pushes dirty houses, visits, queued photo deletes and new photos. It then pulls house, visit and photo changes with `since=<cursor>` (photo tombstones remove local copies; new photos of live houses are downloaded). | M | Impl |
| FR-022 | Conflicts are resolved last-write-wins on `updatedAt`, on both the server and the client. Tombstones sync. | M | Impl |
| FR-023 | Changing the server URL resets the sync cursors, so everything is downloaded again. | S | Impl |

### 6.4 Web app

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-024 | The web app has a Map page, a House detail/edit page (checklist, rating, photos, visits), a Compare page and a "Your data" page. Confirmations use an in-app dialog. **Amended in Sprint 4a (FR-071):** the Connect page (API base URL and key, "test connection", "remember on this device") still exists at `/connect`, but it is no longer the way in and no route requires it — the app opens on the map with the browser's own data, and connecting a server only adds sync. | M | Impl (amended 4a) |
| FR-025 | "Fill address from map" reverse-geocodes with OSM Nominatim, only when the user presses the button. | S | Impl |

### 6.5 Server

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-026 | Nearby search: houses within a radius (default 50 m, capped at 5 000 m), nearest first, at most 50, with the distance in metres. | S | Impl |
| FR-027 | Street search: live houses whose street matches, ignoring case, using the `lower(street)` index. | S | Impl |
| FR-028 | Stats: counts of houses, shortlisted, rejected, visits and distinct streets. | C | Impl |
| FR-029 | Health endpoint for uptime checks, with no authentication and no details. | M | Impl |

### 6.6 Settings and data control

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-030 | Android settings: server URL (HTTPS only), API key (masked, stored encrypted), photos only on Wi-Fi, language, alert radius, minimum stay minutes, last sync time and result, "Sync now", "Save and test". | M | Impl |
| FR-031 | Export all data as JSON from the API (`GET /api/export`). **Amended in Sprint 4a:** the endpoint now emits the shared `doorprints-backup/1` object — the same thing a device backup carries as `data.json` — instead of its own `house-hunt-export/1` shape, so a server copy and a device copy are one format ([schemas/README.md](schemas/README.md)); the download is named `Doorprints-backup-<UTC date>.json`. Photo bytes are not in it (they come from `GET /api/photos/{id}`; a device backup puts them in the ZIP instead). The matching `POST /api/import` restores such a file, with `?dryRun=true` for a preview. The apps no longer depend on this endpoint for a copy: FR-042..FR-045 build every format on the device. | S | Impl |
| FR-032 | Permanently delete all data including tombstones, photos and embeddings (`DELETE /api/data` with the header `X-Confirm-Delete: DELETE-ALL-MY-DATA`). Deleting one house purges its content and photos. | S | Impl (API) |
| FR-033 | Notification taps deep-link to the right screen (open a house, or a new house with lat/lon and visit ID). | M | Impl |
| FR-034 | Photo deletes made offline are queued and sent on the next sync; photos deleted on another device are removed locally. | M | Impl |
| FR-035 | Photos upload and download only on unmetered networks when "Photos only on Wi-Fi" is on (default); houses and visits always sync. Waiting photos are counted and moved by a Wi-Fi-only job. | S | Impl |
| FR-036 | Android UI in English, Hindi, Tamil and Telugu with an in-app language picker (per-app language via `LocaleManager` on Android 13+, stored preference on 8–12). | S | Impl |
| FR-037 | Ask (AI, optional): questions about the saved houses with cited houses linked (web page, Android Assistant tab). Hidden unless `GET /api/ai/status` reports `enabled`. | C | Impl |
| FR-038 | **Fill in from listing text** (AI, optional; called "Import listing text" until 2026-09-23, renamed because "import" is reserved for backups, [12](12-brand-and-naming.md) G.3): paste an ad on the new-house form (web) or edit screen (Android) to pre-fill fields; nothing is saved until the user saves. | C | Impl (web keys `listingFill.*` since 2026-09-23; Android already titles its dialog "Fill in from listing text", `house_paste_title`, opened by *Paste listing*) |
| FR-039 | Plan visits (AI, optional): an ordered walking route from the current or chosen start point (web: list + map; Android: list). | C | Impl |
| FR-040 | Hunt mode lowers its GPS rate while the user stands still and stops itself when the battery is at 15% or less and not charging. | S | Impl |
| FR-041 | Android dark theme, and colours taken from the web design tokens. | S | Impl |

### 6.7 Sprint 4b additions: Hunt mode reminders and Hunting areas (Android)

Accepted by the product owner on 2026-09-22 for Sprint 4b. Design and stories: [11](11-feature-parity-and-export-spec.md) 5.16 (reminders), 5.17 (areas), 5.18 (location permission model). Of the ids [11](11-feature-parity-and-export-spec.md) §7 proposes, **FR-042..FR-048 and FR-070..FR-073 are now real requirements in section 6.8** (Sprint 4a); FR-049..FR-069 and FR-074..FR-082 stay reserved for Sprint 4b and Sprint 5.

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-083 | **Hunt mode reminder.** For a planned viewing (11 FR-062) the phone shows a local notification a set time before it (default 15 minutes, configurable 5 to 60) with the actions **Start Hunt mode** and **Dismiss**; tapping the body opens the viewing. Works offline, needs no server, survives reboot and time or time-zone changes, respects Do Not Disturb (normal priority, no full-screen intent, no DND override), and its lock-screen public version shows no address or house name. | S | Plan (4b) |
| FR-084 | Hunt mode reminders have a global switch in Settings (default on) and a per-viewing switch. When the user has allowed "Alarms & reminders" (`canScheduleExactAlarms()` true) the reminder is an exact allow-while-idle alarm at the chosen time. Otherwise it is an inexact window alarm that **ends** at the chosen time (window 10 minutes, the minimum Android 12+ typically allows), so it arrives up to about 10 minutes early and never late, except when Doze or battery saver defers it; WorkManager is the fallback. The app works without the exact-alarm permission; Settings says "Reminders may arrive up to about 10 minutes early (or later if the phone is in battery saver)" and offers a link to the system "Alarms & reminders" page. Details and source: [11](11-feature-parity-and-export-spec.md) 5.16. | S | Plan (4b) |
| FR-085 | **Hunting areas.** The user can mark up to 20 neighbourhoods on the map (tap or draw a circle, radius 200 m to 2 km, a name), edit, disable and delete them. Areas are stored on the device (Room), included in the offline exports and backups, and synced later (Sprint 5, with sign-in). | S | Plan (4b) |
| FR-086 | **Area wake-up** (off by default): when on, the app registers one Geofencing API geofence per enabled area (ENTER transitions only). Entering an area shows a notification "You're in <area>: start Hunt mode?" with **Start Hunt mode** and **Dismiss**. It **never starts tracking without a tap**. Each area notifies at most once per 6 hours. | S | Plan (4b) |
| FR-087 | Geofences are registered again after a reboot, an app update, a `GEOFENCE_NOT_AVAILABLE` recovery (location turned back on) and a permission change, and are removed when area wake-up is turned off or the background permission is lost. | M (with FR-086) | Plan (4b) |
| FR-088 | Reminder and area notifications, the Settings texts and the background-location rationale screen exist in English, Hindi, Tamil and Telugu, and the notification actions have TalkBack labels. | M (with FR-083, FR-086) | Plan (4b) |

### 6.8 Sprint 4a: local-first web, offline copies and import

Built in Sprint 4a ([10](10-sprint-log.md) section 11; design in [11](11-feature-parity-and-export-spec.md) 5.2 and
5.10, format pinned in [schemas/README.md](schemas/README.md)). *Status* is the state of the code in the working
tree of Sprint 4a, not of a released build.

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-042 | The user can save an offline copy in **six deterministic formats**: HTML, PDF, CSV (a ZIP of tables), XLSX, Markdown and a JSON full backup. The same data and the same options give the same bytes, apart from the export time the copy states on its cover. | M | Impl (Android + web; the ordering, filtering and rows are one shared implementation, `com.househunt.shared.export`, mirrored in TypeScript) |
| FR-043 | HTML and PDF are self-contained readable copies: cover (date, counts, options, privacy note), ranking table and one section per house with details, score breakdown, checklist, visits, notes, photos and (optional) contact, in the chosen language, one house per printed page. The HTML has **no scripts and no external resources** and carries `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">`. | M | Impl |
| FR-044 | The JSON full backup (`doorprints-backup/1`) is **exact**: every field the apps store round-trips, including ids and timestamps. One format for all three implementations — server, Android and web ([schemas/README.md](schemas/README.md)). | M | Impl |
| FR-045 | Android (from Room) and the web app (from IndexedDB) build every format **on the device, offline, without an account or a server**. Android saves through the Storage Access Framework or the share sheet; the web app downloads the file or uses Web Share. Never to storage that an uninstall removes. | M | Impl |
| FR-046 | Export options: scope, photos (all / shortlisted / none), contact details (included by default, with a warning), output language and "include rejected houses". | M | **Part**: the shared model also has a *selected houses* scope (`ExportScope.SELECTED`), but neither UI offers it yet — both screens show *all* and *shortlisted* only. The rest is implemented on both platforms. |
| FR-047 | The user can import a JSON backup: validate, preview ("*a* new, *b* newer in the file, *c* newer here"), then **merge by id with last-write-wins** or **import as a copy** with fresh ids. Importing the same file twice changes nothing the second time. Other formats are not importable. | M | **Part**: done on Android (Import screen, preview, both modes) and on the server (`POST /api/import`, `?dryRun=true` for the preview). **The web app can export but not yet import** — the gap is carried in [10](10-sprint-log.md) section 11, and the web import is Sprint 4b under the approved definition in 6.9 (FR-089..FR-097). Android also offers, in a merge, an opt-in **undelete** of houses deleted on the phone ("Also bring back *n* houses deleted on this phone", **Bring them back**; original ids kept, stamped as edited now so the undelete syncs) and a *Keep mine, add only what's new* choice in the Replace dialog; after a delete that has already synced, the house's visits are relinked and its photos written under fresh ids (`android/shared/README.md` 1.13–1.14, `ImportPlanTest`; [06](06-test-plan.md) TC-U-42). Device check 10b (the undelete after a synced delete, checked on the web) has **not** been run. |
| FR-048 | Optional automatic weekly JSON backup on Android, off by default, into a folder the user grants once, keeping the last few files. | C | Impl (`AutoBackupWorker`: weekly, `setRequiresCharging(true)` and `setRequiresBatteryNotLow(true)` and **no network constraint** — the file is written straight into the folder the user granted, so there is nothing to upload; keeps the last 4 by default, a 1–8 slider in Settings (the stored value is clamped to 1–20); if the folder grant is revoked it turns itself off and says so rather than failing silently every week. **Turning it off forgets the folder**: the persisted folder grant is released and the stored folder and last error cleared, so turning it on again opens the picker; choosing another folder releases the old grant ([02](02-threat-model.md) T-I25, [05](05-ux-accessibility-i18n.md) §14.8)) |
| FR-070 | The web app is an installable **PWA**: manifest, 192/512 and maskable icons, an Apple touch icon, a service worker, an install offer that never opens the browser's dialog without a tap — a permanent *Install the app* card on *Your data*, and a banner shown once after the first saved house, whose "Not now" lasts 30 days — and the iOS "Add to Home Screen" steps. The manifest's `id` is the absolute deployment path, written by the build (`/` on Firebase Hosting at `https://doorprints.web.app`, the host since 2026-09-23, where the site is served from the root; a sub-path deployment would get that path). | M | Impl ([05](05-ux-accessibility-i18n.md) §14.4, [07](07-secure-build-and-deploy.md) §6.3) |
| FR-071 | The web app is **local-first**: all data is in IndexedDB, every non-AI feature works offline and without connecting a server, and the network is used only for sync, AI and map tiles. Connecting a server is optional and only adds sync. | M | Impl (no route is guarded any more; the Connect page is no longer the way in) |
| FR-072 | The installed web app is a share target (`/share`). The shared text is untrusted and may carry an owner's phone number: it is shown as plain text, removed from the address bar and from the URL of the tab's history entry at once, and passed to the map and the new-house form only in navigation state, never in a URL. Navigation state is `history.state`, so the text does stay in session history (the `/` entry until the map strips it, an unsaved `/houses/new` entry until the tab closes) — a known residual, [02](02-threat-model.md) T-I26. | S | **Part**: the manifest declares the GET share target and the `/share` route accepts it, with the handling above (`share-page.ts`; [02](02-threat-model.md) T-I26, [04](04-data-flow-diagrams.md) DF-47); the listing parser that fills a draft from it is Sprint 4b (S4-13). |
| FR-073 | The web app asks the browser to keep its data (`navigator.storage.persist()`), shows how much space it uses, offers an update prompt when a new build is waiting, and warns when the browser has **not** promised to keep the data — including the iOS case, where Safari can evict a site that is not installed. | S | Impl |

### 6.9 Sprint 4b: what an import is (story S4b-00, approved 2026-09-23)

The owner (Sriram) approved this product definition on 2026-09-23 at 08:10 IST, before the web import is built.
It binds every importer: Android, the web app and a self-hosted server. The format side is
[schemas/README.md](schemas/README.md) section 0 (and sections 6 and 7); the user-facing words are
[12](12-brand-and-naming.md) section G. FR-047 stays the Sprint 4a requirement; these refine it for 4b.

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-089 | **An import brings a Doorprints Full backup in**, into the Android app, the web app or a self-hosted server. Nothing else is called an import: text or a link shared into the app is *Add a shared listing* (one new house), and the AI form helper is *Fill in from listing text* (FR-038). | M | Android and server: Impl. Web: **Planned (4b)** |
| FR-090 | **Only two files are accepted:** a Doorprints backup ZIP (`Doorprints-backup-YYYY-MM-DD.zip`: `manifest.json`, `data.json`, `photos/` and the readable HTML copy) and a bare `data.json` of format `doorprints-backup/1` (for example from the server's `GET /api/export`), which has no photo bytes. The file is recognised by its content, not its name. Readable copies (HTML, PDF, CSV, XLSX, Markdown), other apps' files and arbitrary spreadsheets are refused with a reason, before anything is written. | M | Android: Impl (ZIP and bare `data.json`). Server: Impl (`data.json` only). Web: **Planned (4b)** |
| FR-091 | **What a backup can contain:** houses (label, address, street, locality, location, status, price with rent or sale, BHK, rating, the 10-point checklist, listing link, notes, and the contact name and phone **only if the backup was made with contact details included**), visits (place, street, arrived and left, automatic or manual, the link to their house or none) and photos (JPEG, EXIF stripped). | M | Impl (format, FR-044) |
| FR-092 | **What an import never contains or changes:** settings, language, server address, API key, Hunt-mode settings, hunting areas (FR-085..FR-087), AI settings and the AI index, and trusted devices or sign-in (Sprint 5). | M | Impl by construction on Android and the server (none of these is in the format); web: **Planned (4b)**, with a test that the web import leaves `localStorage` and the settings store untouched |
| FR-093 | **Validate everything first.** One bad row, and nothing is imported. The limits of [schemas](schemas/README.md) §7 apply: `data.json` at most 16 MiB, at most 5 000 ZIP entries and 1 GiB uncompressed, zip slip blocked (SEC-041). | M | Android and server: Impl. Web: **Planned (4b)** |
| FR-094 | **Preview before writing:** the counts *new*, *newer in the file* and *newer here*, and for a house that was deleted here the wording of [schemas](schemas/README.md) §6 rule 6 (never "the links are lost"). The preview equals what the import then writes. | M | Android: Impl (`ImportPlan.preview`, preview-equals-plan test for every flag combination, [06](06-test-plan.md) TC-U-42); server: Impl (`?dryRun=true`). Web: **Planned (4b)** |
| FR-095 | **Merge by id, the newest edit wins; identical rows write nothing; an import never deletes** anything already on the device or server. | M | Android and server: Impl. Web: **Planned (4b)** |
| FR-096 | **Optional "add as copies"** (every row gets a new id) on **Android and the web** (owner decision (b), for parity); the server always merges. | S | Android: Impl (all or nothing, one transaction). Web: **Planned (4b)**, also all or nothing (Android handover item 11(b)) |
| FR-097 | **The import screen says what comes in and what is never touched**, in the user's language, with the names *Import a backup* and *Full backup*: "Brings back houses, visits and photos from a Doorprints backup, and never deletes anything already here." and "Your settings, language, server address, API key, Hunt mode and AI settings stay exactly as they are." ([12](12-brand-and-naming.md) G.2; hi/ta/te first drafts, [05](05-ux-accessibility-i18n.md) I18N-B06). | S | **Planned (4b)** on both platforms (Android today shows `import_lead` only) |

**Out of scope for 4b (owner decision (c)):** importing from other apps or arbitrary spreadsheets, perhaps with
AI-assisted column mapping, is a possible later feature with its own name (11.3).

### 6.10 Map: India's boundaries (owner decision, 2026-09-24)

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-098 | **Every map shows India's external boundary as the Government of India depicts it**, on Android and the web, identically, as the only view (no switch; every user is in India). All of Jammu and Kashmir and Ladakh are inside India, including the areas the tiles call Azad Kashmir, Gilgit-Baltistan, the Shaksgam valley and Aksai Chin, and so is Arunachal Pradesh. The map draws one solid outline and **no Line of Control, Line of Actual Control or other de facto or claim line** at any zoom, including while closer tiles are still loading and offline, and no "Azad Kashmir" or "Gilgit-Baltistan" state label. The rules are applied on every load of the base style (first load, retries, reloads); a base-style change that removes a layer the rules refer to never breaks the map, and India's outline is still drawn. No new network host. Design: [03](03-design.md) ADR-22. | M | **Impl on Android and the web** (branch `fix/india-boundaries`, HEAD `3ad2b58` pushed, CI run on it not finished at the time of writing; not deployed). Both apps apply the same five rules of [03](03-design.md) ADR-22, including rule 2's adm0 clause and tile-zoom guard (web `shared/india-boundaries.ts` `COUNTRY_LINE_RULE`, `TILE_ZOOM_GUARD`; Android `ui/IndiaViewRules.kt` `COUNTRY_LINE_EXTRA_FILTER`, `TILE_ZOOM_GUARD`). Known limits, not failures of this requirement: along the 7 stretches where the tiles draw India's border themselves (with Nepal, Bhutan, Myanmar and, in the Wakhan, Afghanistan), a small step at street zoom where the at most 7 km connector joins the tile line, small loops at Sikkim's two tri-junctions (about 13 x 3 km at Nepal-China-India (on glaciers, seen only from about zoom 10) and about 2 km at Doklam), the tile line running on past the hand-over at Jomotsangkha (about 9 km) and Longwa (about 3 km) from about zoom 10 (a small hook at Jomotsangkha from zoom 9), and no line there at zoom 5+ while those tiles load or offline without them ([03](03-design.md) ADR-22 *Consequences*). The Assam-Arunachal Pradesh state line is drawn from zoom 5, the two close lines are gone and the whole India-China border is drawn by India's outline alone since branch `fix/india-boundary-lines` (PR #16; CI green on `5af2f4d`, HEAD `9e0036e` running; not deployed) (S4b-BL-11, S4b-BL-15, S4b-BL-16). Compared with the code as of HEAD `3ad2b58` (2026-09-24) and `9e0036e` (`fix/india-boundary-lines`) |

## 7. Non-functional requirements

| ID | Category | Requirement | Measure / target | Pri |
|---|---|---|---|---|
| NFR-001 | Performance | API responses when the server is warm | p95 < 500 ms for list/nearby with at most 1 000 houses | S |
| NFR-002 | Performance | Tolerate free-tier cold starts | Clients wait up to 90 s (Android read timeout is already 90 s). The web shows a "waking server" hint. | M |
| NFR-003 | Performance | Hunt-mode check per fix | Nearby-house check at most 50 ms for 1 000 houses on a mid-range phone (in-memory haversine) | S |
| NFR-004 | Offline | Core Android features need no network | 100% of FR-001..FR-019 work offline. Maps show cached tiles only. | M |
| NFR-005 | Battery | Hunt-mode energy use | At most about 8% battery per hour on a mid-range phone (high-accuracy fixes, 15 s interval, 5 s minimum, 5 m displacement). Must be verified in the field test (TC-F-06). | S |
| NFR-006 | Accessibility | WCAG 2.2 AA (web), TalkBack support (Android) | As in [05](05-ux-accessibility-i18n.md) | M |
| NFR-007 | i18n | English, Hindi, Tamil, Telugu UI. Indian number format (₹, lakh/crore grouping via `en-IN`). | As in [05](05-ux-accessibility-i18n.md) | S |
| NFR-008 | Availability | Best effort on free tiers | Target about 99% monthly for the API when not sleeping. Data durability matters more than uptime: RPO ≤ 24 h, RTO ≤ 4 h (see 08). | S |
| NFR-009 | Capacity | Stay within free DB quotas | About 500 MB DB. Budget: 1 000 houses, 5 000 visits, about 2 000 photos at about 250 KB each. Photos dominate (see F-06). | M |
| NFR-010 | Resource | API fits in 512 MB RAM | JVM flags `MaxRAMPercentage=75`, SerialGC, Hikari pool of 5 | M |
| NFR-011 | Portability | Runs anywhere with Docker and Postgres 15+ with PostGIS 3 | `docker compose up` works locally | M |
| NFR-012 | Fair use | Respect third-party usage policies | Nominatim at most 1 request/s, only on user action, with a proper User-Agent/Referer. OpenFreeMap fair use. Android Geocoder throttled (45 s / 80 m). | M |
| NFR-013 | Maintainability | Automated tests and CI | Backend integration tests on PostGIS in CI. Android unit tests for the shared rules and the API client contract (`:shared` commonTest, Sprint 3.5) and the Room identity hash; the shared module's iOS targets compile on macOS (TC-U-37). Lint clean. | S |
| NFR-014 | Cost | Zero running cost | See section 11 | M |
| NFR-015 | Usability | One-handed, outdoor use | Main actions reachable by thumb, readable in sunlight (see 05) | S |
| NFR-016 | Sync latency | Changes reach the server soon | At most 1 minute after a change when online | S |
| NFR-017 | Resilience | Network faults and cold starts | Idempotent requests retried up to 3 times with exponential backoff and full jitter (cap 15 s); captive portals detected; no redirects followed. See [09](09-osi-layer-analysis.md). | M |
| NFR-018 | Bandwidth | Mobile data use | JSON gzip-compressed by the API; photos only on Wi-Fi by default | S |
| NFR-019 | Capacity | Photo storage bounded | At most 20 photos per house (server 409, app check) | M |
| NFR-020 | Accessibility | Android touch targets and font scaling | 48 dp targets, radio/checkbox/switch semantics, headings, layouts that wrap at 200% font scale | M |
| NFR-021 | Performance | Building a copy on the device | A JSON backup of 1 000 houses / 2 000 photos in at most 3 minutes on a mid-range phone; a PDF of 100 houses in at most 60 s. **Not yet measured** (TC-P-05 is planned, not run); the design keeps peak memory down by streaming photo bytes into the ZIP instead of holding them, and `ExportPhoto` deliberately carries no image data | S |
| NFR-022 | Portability | The readable copies open without Doorprints | HTML in current Chrome, Firefox, Safari, Samsung Internet and Edge; PDF in the Android and iOS viewers; XLSX in Excel, Google Sheets and LibreOffice; CSV as UTF-8 with a BOM, RFC 4180 | M |
| NFR-023 | Compatibility | Backup format lifetime and determinism | Every later version imports every earlier `formatVersion`; the same input and options give byte-identical output. Held up by: fixed ordering (houses by `createdAt` then `id`), a passed-in export time and UTC offset rather than a clock read inside the exporters, JSON with no pretty-printing, and ZIP entries **stored** (no deflate) with the export time as the entry timestamp | M |
| NFR-024 | Offline | Local-first on both clients | Every non-AI, non-sync feature works in flight mode with no account: Android from Room, the web app from IndexedDB | M |
| NFR-025 | Compatibility | An older client never erases a newer client's data | Unknown fields survive a read-and-write cycle (`ignoreUnknownKeys` in Kotlin, ignored extra properties in TypeScript and Jackson); a null collection in a PUT means "unchanged" | M |
| NFR-026 | Usability | Web app quality as an installed app | Meets the Chromium install criteria (manifest, icons, service worker, HTTPS); the app shell is served from the cache on a repeat visit, so it starts with no network | S |
| NFR-027 | Durability | Browser storage | `navigator.storage.persist()` is requested; used space is shown; when the browser does not promise durability the app says so and recommends a backup. Safari and Chromium browsers do not prompt — they decide from the user's interaction history with the site, and Safari commonly denies; Firefox asks. **Safari additionally deletes script-written storage for an origin with no user interaction in the last seven days of browser use** (server-set cookies are exempt), which no code can prevent; a site saved to the Home Screen or the Dock gets the browser app's quota instead of the smaller in-app WebKit one ([MDN, *Storage quotas and eviction criteria*](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria), [02](02-threat-model.md) RR-10). The iOS advice is "install to the Home Screen **and** export a backup". When IndexedDB is blocked entirely (private browsing, site data off) the app falls back to an in-memory store and says clearly that nothing is being kept | S |
| NFR-030 | Battery | Area wake-up (FR-086) costs little while Hunt mode is off | Geofences only (no location requests of our own), default responsiveness; target at most 1% battery per day extra with 20 areas, to be measured in the field test ([11](11-feature-parity-and-export-spec.md) TC-F-12). Alerts may arrive about 2 to 6 minutes after entering an area (Android's geofencing latency), which the UI states | S |

## 8. Security requirements

| ID | Requirement | Pri | Status | Threat / finding |
|---|---|---|---|---|
| SEC-001 | Deny by default: every request needs a valid API key (`X-API-Key`) except `GET/HEAD /actuator/health` and CORS preflights. Non-canonical paths (`;`, `%`, backslash, `//`, dot segments) are rejected with 400. | M | Impl | T-S1, T-S2, F-20 |
| SEC-002 | The API key must be random with at least 128 bits of entropy (for example 32+ chars from `openssl rand -hex 32`). The server refuses to start when `APP_API_KEY` (or a non-empty `APP_API_KEY_NEXT`) is shorter than 32 chars; the error names the variable, never the value. | M | Impl (Sprint 2) | F-01a |
| SEC-003 | Keys are compared in constant time. With two keys configured, both are always compared (no early exit). | M | Impl (`MessageDigest.isEqual`) | T-S1 |
| SEC-004 | Production traffic uses TLS only. Android allows cleartext only to `localhost`/`127.0.0.1`/`10.0.2.2` (network security config) and trusts only system CAs; Settings rejects non-HTTPS URLs. The web app must use an HTTPS API URL. | M | Impl | F-02 |
| SEC-005 | CORS allows only the configured web origins, and only on `/api/**`. | M | Impl | T-S3 |
| SEC-006 | All input is validated at the API (bean validation for lengths, ranges, enums and checklist 0 to 5). Out-of-range lat/lon and radius on query endpoints are rejected. | M | Impl | F-19 |
| SEC-007 | Uploads are limited to 5 MB. The type is detected from magic bytes (JPEG/PNG/WebP), metadata is stripped, uploads to deleted houses are refused and there are at most 20 photos per house. | M | Impl | F-07, F-26 |
| SEC-008 | Rate limiting per client address: 600 requests/min (burst 300) in general, 10/min for wrong keys, 10/min for AI (burst 5). 429 with `Retry-After`. | S | Impl | F-05 |
| SEC-009 | No secrets in the repository, images or APK. Secrets come from environment variables / GitHub Secrets. CI runs gitleaks on the full history. | M | Impl | F-17 |
| SEC-010 | The API key is stored encrypted on the device (AES-256-GCM, Android Keystore key) and excluded from backups. On the web it is kept in sessionStorage unless the user chooses "remember on this device". | S | Impl | F-03, F-04 |
| SEC-011 | Android: `allowBackup=false`; data extraction rules allow no cloud backup and device transfer of the database and photos only. | M | Impl | F-03 |
| SEC-012 | Security headers: the API sends `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, CSP `default-src 'none'`, `Cache-Control: no-store` on JSON and HSTS over HTTPS. The web app ships a strict CSP, HSTS and `frame-ancestors 'none'` (with `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy` and COOP), sent by Firebase Hosting from `web/firebase.json` since 2026-09-23 (was `web/public/_headers`). | S | Impl | F-10 |
| SEC-013 | Dependencies are scanned in CI (Trivy on the backend CycloneDX SBOM, Trivy fs for npm and secrets, npm audit) and weekly. Critical/High findings with a fix block merges; a fix the framework BOM does not manage yet is applied as a commented version override (e.g. Tomcat 11.0.25). Dependabot covers Maven, npm, Gradle, Actions and Docker. | M | Impl | F-22, F-27, F-28 |
| SEC-014 | SAST (Semgrep OSS) runs in CI and blocks on ERROR findings. Android Lint runs and is reported (not yet blocking). | S | Part | F-22 |
| SEC-015 | Error responses use RFC 7807 with no stack traces or SQL. The clients show translated error categories, never raw server bodies. | M | Impl | F-12 |
| SEC-016 | Logs contain no API keys, coordinates, notes or phone numbers. Auth failures are logged with a salted client-address hash, method and path. | S | Impl | F-18 |
| SEC-017 | The key can be rotated with a documented procedure. The server supports a current and a next key during rotation (`APP_API_KEY`, `APP_API_KEY_NEXT`). | S | Impl (Sprint 2; procedure in 08 §5.1) | F-01a |
| SEC-018 | Release APKs are signed with a private keystore kept outside the repo, built with R8 minify/shrink, `debuggable=false`, and published with a SHA-256 checksum. | M | Part (Sprint 2: signing from `HH_*` secrets and `apksigner verify` in CI; R8 off until keep rules exist; checksum publishing with `release.yml` next) | F-11 |
| SEC-019 | The DB connection uses TLS (`sslmode=require`) and a non-superuser app role that owns only the app schema (`househunt`; the database, role and schema names were kept at the Doorprints rename, [03](03-design.md) ADR-13). Flyway migrations run with the same role or a separate migration role. | M | Plan | T-I4 |
| SEC-020 | The server clamps client `updatedAt` values more than 5 minutes in the future to server time and rejects dates more than 365 days ahead or before 2000, so records cannot be "frozen". | M | Impl | F-08 |
| SEC-021 | Android components are not exported unless needed. PendingIntents are immutable, with one exception: the Sprint 4b geofencing `PendingIntent` must be `FLAG_MUTABLE` (the Geofencing API fills in the event) and is explicit to a non-exported receiver (SEC-049, [02](02-threat-model.md) T-E8). The deep-link extras from `MainActivity` are validated (UUID format, lat/lon range). | S | Impl | F-25 |
| SEC-022 | Alert notifications use `VISIBILITY_PRIVATE` with a redacted public version, so the lock screen does not show house names or prices. | C | Impl | F-14 |
| SEC-023 | Actuator exposes only `health` without details. | M | Impl | - |
| SEC-024 | The containers run as non-root users: the API as UID 10001, the dev/CI database image (`backend/db`) as `postgres`; docker-compose adds a read-only filesystem, `cap_drop: ALL` and `no-new-privileges`. Trivy config blocks HIGH/CRITICAL Dockerfile findings. | S | Impl | F-23, F-29 |
| SEC-025 | Future: per-device keys or OAuth2/OIDC (for example a free-tier IdP) with revocation. | C | Plan | F-01b |
| SEC-026 | JSON request bodies are capped at 256 KB (413); Tomcat connection timeout 20 s. | S | Impl | F-05 |
| SEC-027 | Sync versions are assigned under a transaction-scoped advisory lock so that a `since` cursor never skips a change. | M | Impl | F-09 |
| SEC-028 | The Android client does not follow HTTP redirects (the key must never reach another host) and treats non-JSON answers as a captive portal. | M | Impl | 09 §3 |
| SEC-029 | docker-compose binds all ports to 127.0.0.1 and refuses to start without `APP_API_KEY`. | M | Impl | F-17 |
| SEC-030 | Third-party GitHub Actions are pinned to a commit SHA or run as pinned container images; workflows have read-only `permissions`. | M | Impl | F-22, T-E4 |
| SEC-041 | **Backup import validation before any write**: the format id and version, the SHA-256 of every entry against `manifest.json`, at most 5 000 entries, at most 1 GiB uncompressed, a compression ratio of at most 100:1, `data.json` at most **16 MiB** (`BackupFormat.MAX_DATA_JSON_BYTES` in `:shared`), and a path check on **every** entry (no absolute path, no drive letter, no backslash, no `..` or `.` segment) so a zip-slip name is refused even when the importer would not read it. A photo entry must be exactly `photos/<name>`, one level deep. The server's `POST /api/import` adds a body-size cap (`app.limits.max-import-bytes`, 413) and a row cap (`app.limits.max-import-rows`, 413). | M | Impl in **`:shared`** (`BackupFormat`/`BackupValidation`), which is the device reader that enforces these rules today — it is what Android imports through. The `data.json` cap is now **one number, 16 MiB**, everywhere: `:shared`, the server's `BackupFormat` and its import body cap (`app.limits.max-import-bytes`, default 16 777 216, also in `docker-compose.yml`), and the web mirror in `backup-export.ts` — which still enforces nothing, because the web app has no importer. Pinned by the backend's `BackupParityTest` (working tree, not yet run in CI; [10](10-sprint-log.md) §11.3 item 7) | [02](02-threat-model.md) T-T8, F-31 |
| SEC-042 | **Export output encoding.** HTML escapes every value and carries a `default-src 'none'` CSP meta, so a copy opened from `file://` runs nothing and fetches nothing. CSV and XLSX prefix a leading `=`, `+`, `-`, `@`, tab or carriage return in a **text** cell with an apostrophe (OWASP CSV-injection guard; numeric cells are left alone so a negative number stays a number). Markdown escapes its control characters. The PDF holds text and images only — no JavaScript, no links, no embedded files. | M | Impl | [02](02-threat-model.md) T-T9 |
| SEC-044 | The service worker caches **this build's own files only** (the app shell, bundles, icons, the manifest, the MapLibre worker). No `/api/...` response and no cross-origin request (map tiles, fonts, the user's own server) ever enters Cache Storage, so a shared computer keeps no copy of the user's data outside IndexedDB, which "Remove all Doorprints data from this browser" clears. | M | Impl (`web/public/sw.js`: same-origin GET under its own base path only, `/api` and `sw.js` skipped; one cache per build) | [02](02-threat-model.md) T-I17 |
| SEC-049 | Notification-action and alarm `PendingIntent`s are immutable and explicit to non-exported components; the geofencing `PendingIntent` is mutable (the Geofencing API requires `FLAG_MUTABLE` on Android 12+) but explicit to a non-exported receiver; **Start Hunt mode** starts the foreground service only from the user's tap on the notification (Android's exemption for starting a location foreground service from a notification interaction), never from the geofence or alarm broadcast itself. The boot and package-replaced receivers only re-register geofences and alarms (no location request, no network). | M | Plan (4b) | [11](11-feature-parity-and-export-spec.md) T-E8 |

## 9. Privacy requirements

Location history and third-party contact details are the most sensitive data here. India's **Digital Personal Data Protection Act, 2023 (DPDP Act)** does not apply to personal data "processed by an individual for any personal or domestic purpose" (section 3(c)(i)). Still, Doorprints holds **other people's** data (landlord/agent names and phone numbers, photos that may show people) and sends data to processors (hosting, LLM). We therefore follow DPDP principles voluntarily: purpose limitation, data minimisation, accuracy, storage limitation, security safeguards and erasure. If the app is ever offered to other users, the DPDP obligations (notice, consent, Data Principal rights, breach notice to the Data Protection Board) would apply in full.

| ID | Requirement | Pri | Status |
|---|---|---|---|
| PRV-001 | Location is collected only while Hunt mode is on or the map screen is open. The foreground service is the only background collector. `ACCESS_BACKGROUND_LOCATION` is not requested (today) and, from Sprint 4b, only when the user turns on area wake-up (PRV-025); even then the app itself collects no location in the background: Google Play services watches the geofences and the app is only told that an area was entered. | M | Impl (amended 2026-09-22 for 4b) |
| PRV-002 | Tracking is always visible (ongoing notification) and can be stopped in one tap. | M | Impl |
| PRV-003 | Raw GPS tracks are **not** stored. Only visits (stay points) and house points are stored. | M | Impl |
| PRV-004 | Right to access / portability: export all data (FR-031). | S | Impl (API) |
| PRV-005 | Right to erasure: deleting a house blanks its content, tombstones its photos and unlinks its visits; "delete everything" hard-deletes all rows and embeddings (FR-032). Tombstones are purged after 90 days (`app.privacy.tombstone-retention-days`). | S | Impl |
| PRV-006 | Retention: the user can purge visits older than a chosen age. Default suggestion: delete all data 6 months after the hunt ends. | C | Plan |
| PRV-007 | Third parties that receive data are disclosed in-app: Google Play services (fused location, Android Geocoder: coordinates), OpenFreeMap (tile requests: map area + IP), OSM Nominatim (web: coordinates on button press), hosting/DB providers (all data), the LLM provider if AI is enabled (see AI-010). | S | Part (Android Settings and AI screens; web privacy page backlog) |
| PRV-008 | Photo EXIF metadata (GPS, device) is removed before storage/upload. Android and web re-encode through a Bitmap/canvas, which drops EXIF; the server strips metadata again (JPEG APP1/COM, PNG text/eXIf, WebP EXIF/XMP). | M | Impl |
| PRV-009 | Third-party contact data (names, phones) is stored only when the user enters it, is used only to contact about that house, and is removed by erasure. It is never sent to an LLM unless the user opts in (redacted by default). | M | Part. Done: stored only when entered; removed with the house, by delete-all and by clearing the fields ([08](08-operations-runbook.md) §6.2); the structured contact fields are never sent to an LLM, and names and phones typed into other fields are redacted ([02](02-threat-model.md) F-30 Fixed, Sprint 3, closed by lead decision; TC-AI-15 green on `6a348cc`). Still Part because: (1) free-text redaction is best effort ([ai/](ai/ai-design.md) §9.1 Limits: nicknames and other spellings, a first name alone in a street or locality, short local numbers); (2) listing extraction sends the pasted text as given, which may hold a contact (explicit user action, disclosed); (3) erasure does not reach encrypted backups until they expire (30 days, [08](08-operations-runbook.md) §3), devices that have not synced, or text a hosted provider already received (before the fix or the post-deploy reindex). |
| PRV-010 | Prefer data residency in India where the free tier allows it (Supabase `ap-south-1` Mumbai, Oracle Mumbai/Hyderabad home region). | C | Plan. AI on Vertex AI (owner's setup, 2026-09-22): chat in `asia-south1` (Mumbai); embeddings on `global` because `gemini-embedding-2` is not offered in `asia-south1`, so embedding text has no India residency guarantee ([02](02-threat-model.md) T-I20, [ai/vertex-setup.md](ai/vertex-setup.md) step 8) |
| PRV-011 | Server and CI logs hold no personal data (see SEC-016). Backups are encrypted (see 08). | M | Part |
| PRV-012 | An offline copy includes contact names and phone numbers **by default**, and the export screen says so in one visible sentence before the user builds it ("This copy contains phone numbers of owners and brokers…"). Turning the option off removes the contact name and phone from every format, not just from the visible ones — the backup's `data.json` loses them too, so a copy made without contacts can never leak them later. | M | Impl (the filter runs once, in `ExportBundle.build`, so no exporter can forget it) |
| PRV-018 | An exported file leaves Doorprints' control the moment it is saved or shared: it is a plain file in the user's storage, chat or cloud drive, with no expiry and no remote wipe. The app says this on the export screen rather than implying the copy is still protected. The optional automatic backup is **off by default** and writes only to a folder the user granted. | M | Impl |
| PRV-022 | Real user data (houses, notes, visits, questions, listing text) is sent only to a provider tier whose terms do not use it to improve the provider's products: Vertex AI or the paid Gemini API tier. The free AI Studio tier, which may use prompts to improve Google products, is used only with synthetic data (evals). See [02](02-threat-model.md) T-I20. | M | Plan (with AI-016). Today: the owner's own data on the free tier, accepted and disclosed (CON-05). |
| PRV-023 | On-device AI (AI-014) sends no prompt, data or output off the phone, and the app says so. | M | Plan (Sprint 5) |
| PRV-024 | **Foreground-only by default** (product owner, 2026-09-22). Normal use and Hunt mode ask only for foreground location ("While using the app" or "Only this time"), never for background location at first launch. Hunt mode runs as a foreground service started from the visible app or a notification action, which Android allows with foreground permission. If the user chose "Only this time", the next Hunt mode start asks again, and the text explains that "While using the app" avoids the repeated prompt. | M | Part: today's app asks only for foreground location (PRV-001); the "Only this time" copy is Plan (4b) |
| PRV-025 | **"Allow all the time" only for area wake-up.** When the user turns area wake-up on, the app first shows its own rationale screen (why, what is collected, battery, how to turn it off), then asks for `ACCESS_BACKGROUND_LOCATION`; on Android 11+ this can only be granted in system settings, so the app opens them with clear step-by-step text. If the permission is denied, or later downgraded or revoked, area wake-up turns itself off with a notice; Hunt mode and everything else keep working. If the app is ever published on Google Play, the background-location declaration and prominent disclosure are needed first ([11](11-feature-parity-and-export-spec.md) 5.18). | M | Plan (4b) |
| PRV-026 | The location permission state is checked again every time the app comes to the foreground. With approximate location only, the app explains that house-level alerts need precise location and offers to change it; it does not start house alerts on approximate fixes. | M | Plan (4b) |
| PRV-027 | **Geofence privacy.** Hunting areas (names, centres, radii) stay on the device (and in the user's own exports) until sync with sign-in exists; geofence transitions are not stored as location history; only each area's last-notified time is kept, for the cooldown. Google Play services processes the geofences on the device (disclosed with the other Google location services in PRV-007). | M | Plan (4b) |

## 10. AI requirements

AI features are **optional** and **off unless configured**. The AI team owns the detailed design in [docs/ai/](ai/). The requirements below are the contract this document set relies on.

| ID | Requirement | Pri | Status |
|---|---|---|---|
| AI-001 | Every AI feature is disabled unless the operator configures a provider (Vertex AI, the Gemini API, or a local Ollama; AI-016). The free Gemini API tier is for evals with synthetic data only (PRV-022). When disabled, AI endpoints return 404 and both clients hide AI entry points (`GET /api/ai/status`). | M | Impl |
| AI-002 | "Ask my house hunt": RAG with Spring AI 2.0.1 and pgvector over the user's own houses, notes and visits. Every answer cites the house IDs it used, and the UI links the citations. | S | Impl |
| AI-003 | Grounding: answers use only retrieved context. If the context is not enough, the answer says so. Citations must refer to records that exist and were retrieved (checked on the server). | M | Impl (backend, see [ai/](ai/)) |
| AI-004 | Listing extractor: turns pasted listing text into a **draft** house (price, BHK, locality, contact...). The user must review and save. It never saves automatically. | S | Impl |
| AI-005 | Output validation: LLM output is parsed into a typed schema and checked with the same bean validation as `HouseDto`. Invalid fields are dropped, not "fixed". Output is rendered as text only (no HTML/Markdown injection). | M | Impl (backend, see [ai/](ai/)) |
| AI-006 | Viewing-day planner agent: plans an order and time for viewing selected houses using tools (list houses, distance matrix from PostGIS, visit history). Tools are read-only. There is a step limit (for example at most 8 tool calls) and a timeout. | C | Impl (backend, see [ai/](ai/)) |
| AI-007 | MCP server: exposes house tools (search, get, nearby, stats) behind the same authentication as the API. Read-only by default. Any write tool needs explicit opt-in config and client-side confirmation. | C | Impl (backend, see [ai/](ai/)) |
| AI-008 | Prompt-injection defence: listing text, notes and retrieved content are treated as untrusted data and delimited in prompts. The model cannot call write tools because of instructions inside that data. The system prompt holds no secrets. | M | Impl (backend, see [ai/](ai/)) |
| AI-009 | Cost/abuse limits: a per-day request quota, max input/output tokens, request timeouts and a concurrency limit of 1 to 2. Cost stays within the owner's hard cap on a paid key (AI-015); a free tier may still be used for synthetic-data evals. On quota exhaustion the feature degrades gracefully. | M | Impl (backend, see [ai/](ai/)); the paid-key hard cap is AI-015 (Plan) |
| AI-010 | Data sent to third-party LLMs: the user is told which provider is configured. Contact names/phones are redacted by default. A local model (Ollama) is supported for full privacy. Prompt/response logging is off by default. | M | Impl (Sprint 3, C-13; closed by lead decision: redaction tests TC-AI-15 pass in the Backend workflow on `6a348cc`; the real Gemini eval run 35720654442 confirms the AI paths work with the redactor in place (its fixtures hold no contact data, so it is a no-regression check, not a redaction test)): provider disclosure, Ollama option and logging off; `ContactRedactor` keeps the contact name and phone out of the embedding text, Ask context, citations and agent/MCP tool results ([02](02-threat-model.md) F-30 Fixed, [ai/](ai/ai-design.md) §9.1). Pasted listing text for extraction is sent as the user gave it. |
| AI-011 | Embeddings are stored with a foreign key to their source and are deleted/re-computed when the source changes or is deleted (PRV-005). | M | Impl (backend, see [ai/](ai/)) |
| AI-012 | An eval suite (golden Q&A, citation accuracy, injection cases) must pass agreed thresholds before an AI feature is enabled by default in a release (see 06 section 8). | S | Part. First real Gemini (AI Studio) run 2026-09-22, Actions run 35720654442: 12/13 cases, `citationPrecision` 0.86 vs 0.90 (E-03). **First fully green run: 35758157317 on `19006bc`, `provider=vertex`, golden set v0.5 — 13/13 cases and every metric, 198 s** ([10](10-sprint-log.md) §10.2). AI still stays **off by default**: one green run on one provider is not the bar. The bar (unchanged) is a green run on the provider that would be enabled, on the commit being released, with the spend controls in place (AI-015, AI-017) — and the AI Studio path has not been re-run since the golden set moved to v0.5 |
| AI-013 | **Cloud AI access.** Server-side AI (Ask, planner, listing extraction, custom export) is available only to the owner and to Google accounts the owner invites. Guests and signed-in users who are not invited get no cloud AI and are not prompted to sign in for AI. Until Sprint 5 the owner's API-key install is the only cloud AI user. ([11](11-feature-parity-and-export-spec.md) D-21, 5.13) | M | Plan (Sprint 5, S5-07). Today: only the owner has access through the API key. |
| AI-014 | **On-device AI for guests.** On Android devices that support it, "Improve with AI" and the AI custom export run on-device with Gemini Nano through the ML Kit GenAI Prompt API (beta), after a runtime feature-status check and an optional model download on Wi-Fi. On unsupported devices and in the web app/PWA, AI entry points are hidden for users without cloud access. Every feature works without AI. | S | Plan (Sprint 5, S5-07 spike) |
| AI-015 | **Hard cost cap.** Cloud AI runs on the owner's paid key in a Google Cloud project used only for AI, capped in three layers: (1) a per-user daily quota and a global daily cap in the app; (2) a Google Cloud **spend cap budget** (Preview) on that project and the AI service, which blocks new AI usage when the monthly target is passed (monthly, counted before credits, not instant); (3) budget alerts at 50/90/100 %, which only notify. A Quotas-page limit is used only if the model exposes an adjustable one. When the in-app cap is reached, cloud AI pauses until the next day; when the spend cap trips, until the owner lifts it or the month ends (the API response for that case: AI-017). On-device AI keeps working in both cases. See [08](08-operations-runbook.md) §10.2. | M | Plan (Sprint 5). Today AI-009 limits apply. |
| AI-016 | **Two active providers.** Vertex AI and the Gemini API (AI Studio key) are both supported and chosen by configuration only (`AI_PROVIDER` = `aistudio`, the default, or `vertex`); the AI Studio code path is kept so the owner can switch back. Vertex AI authenticates only with Application Default Credentials (Workload Identity Federation in CI, the attached service account on Cloud Run, `gcloud auth application-default login` locally); no Vertex API key is supported. A provider quota error (HTTP 429 / `RESOURCE_EXHAUSTED`) from either provider returns `503` with `code: AI_QUOTA_EXHAUSTED` and `Retry-After: 60`, and a re-index or eval run stops at the first one instead of spending more calls. Setup in [ai/vertex-setup.md](ai/vertex-setup.md); credential rules in [07](07-secure-build-and-deploy.md) §4 and [02](02-threat-model.md) T-I22. Users never supply their own key (bring-your-own-key rejected, 11.3). | S | Part (code landed 2026-09-22: `AI_PROVIDER` switch, `VertexAiConfiguration`, `VertexEmbeddingModel`, quota path; waiting for the first CI run and the owner's [ai/vertex-setup.md](ai/vertex-setup.md) steps 8 and 10: model availability in `asia-south1` and whether the trial credit pays for Vertex AI) |
| AI-017 | **Spend cap trip is a clear, final state.** When the Google Cloud spend cap budget (AI-015) blocks the AI service, the backend recognises the provider's response, returns a problem that clients show as "Cloud AI is paused" (not as a generic outage or a quota that clears in a minute), does **not** retry the call (no SDK or embedding retries, no `Retry-After` suggesting a quick retry, `retryable: false`), and the re-index and the eval harness stop at the first one. The response shape (HTTP status and google.rpc reason, for example `403` with a billing reason, or `429`) is captured from a real trip or Google's documentation and pinned in a contract test next to the `AI_QUOTA_EXHAUSTED` tests. Today the `AI_QUOTA_EXHAUSTED` path covers HTTP 429 only; any other provider error is a generic `503` with `retryable: true`. On-device AI keeps working. | M | Plan (Sprint 4 candidate with C-24, [10](10-sprint-log.md)) |

## 11. Constraints, assumptions, out of scope

### 11.1 Constraints

| ID | Constraint |
|---|---|
| CON-01 | **Zero running cost.** Only free tiers: Oracle Cloud Always Free, Render/Koyeb for the API; Supabase/Neon for Postgres + PostGIS (+ pgvector); Firebase Hosting on the no-cost Spark plan with no billing account for the web (`https://doorprints.web.app`, [03](03-design.md) ADR-21; a custom domain only later, [12](12-brand-and-naming.md) section D); OpenFreeMap tiles; Nominatim; Android Geocoder; GitHub Actions; Ollama or an AI free tier for synthetic-data evals. **One exception (product owner, 2026-09-22):** cloud AI for the owner and invited users runs on the owner's paid, hard-capped key (AI-015); users never pay. A time-limited Google Cloud trial credit may fund Vertex AI, Test Lab and a staging backend ([10](10-sprint-log.md)). |
| CON-02 | The APK is sideloaded. Play Store publishing ($25 one-time) is deferred. |
| CON-03 | Stack: Java 25, Spring Boot 4.1.1 (embedded Tomcat overridden to 11.0.25), Flyway, PostGIS; Kotlin 2.4.10, Compose, Room, WorkManager, Ktor client 3.6 in the Kotlin Multiplatform module `:shared` (Android + compile-only iOS, ADR-14), minSdk 26 / targetSdk 36 / compileSdk 37; Angular 22, MapLibre GL 6.10 (web), MapLibre Android 13. Node is a build tool for the web app only (ADR-06). |
| CON-04 | Free-tier limits: sleeping instances (cold start 30 to 60 s), about 500 MB DB, projects paused after inactivity (Supabase), GitHub Actions minutes (unlimited for public repos, 2 000 min/month for private). |
| CON-05 | Third-party policies: Nominatim (at most 1 req/s, no bulk use), OpenFreeMap fair use, LLM free-tier terms (the free AI Studio tier may use prompts to improve Google products, so real user data goes only to a paid tier or Vertex AI, PRV-022; AI-010 discloses the provider). |

### 11.2 Assumptions

| ID | Assumption |
|---|---|
| AS-01 | There is one user. Everyone holding the key is trusted equally. |
| AS-02 | The phone has a screen lock and file-based encryption (default on Android 10+). |
| AS-03 | Phone clocks are roughly correct (NTP), because LWW depends on them. |
| AS-04 | Google Play services are present (fused location, Geocoder). Without them, street alerts are disabled but house alerts still work. |
| AS-05 | Under 1 000 houses per hunt, so the in-memory nearby check on the phone is enough. |

### 11.3 Out of scope

Multi-tenant accounts, sharing links, a native iOS app (the PWA serves iPhones; the shared KMP module keeps a later iOS app possible, ADR-14), push notifications from the server, scraping property portals, payments, bring-your-own AI key (rejected 2026-09-22: consumer UX, payment-linked secret risk, support burden), importing from other apps or arbitrary spreadsheets (owner decision of 2026-09-23: later, as its own separately named feature, never called "import"; 6.9), legal/title verification, turn-by-turn navigation, offline tile packs (Could, later).

## 12. Requirements traceability matrix

Design sections refer to [03-design.md](03-design.md). Tests refer to [06-test-plan.md](06-test-plan.md). "Gap" means no test exists yet.

| Req | Design | Code module(s) | Test(s) |
|---|---|---|---|
| FR-001 | 03 §7.1 | android `ui/MapScreen.kt` (Save house here, long-press), web `pages/map`, `core/models.ts newHouse` | TC-M-01, TC-F-01, TC-U-51, TC-M-23, TC-M-24, TC-I-35 |
| FR-002 | 03 §6, §9 | backend `house/HouseDto`, `House`; android `data/Models.kt`; web `core/models.ts` | TC-I-03, TC-I-06 |
| FR-003 | 03 §6 | `HouseDto.rating @Min(1) @Max(5)` | TC-I-06 |
| FR-004 | 03 §6 | `house_checklist`, `Checklist.items`, `CHECKLIST` | TC-I-03, TC-U-05, TC-U-19 |
| FR-005 | 03 §6.3 | `HouseScore.of` (`:shared`), `HouseEntity.score`, web `houseScore()` | TC-U-05 (`HouseScoreTest`), TC-U-19 (`models.spec.ts`) |
| FR-006 | 03 §8.1 | `HouseStatus` (backend, android, web) | TC-I-03, TC-M-03, TC-I-35 |
| FR-007 | 03 §7.4 | `photo/PhotoService`, `ImageSanitizer`, android `Repository.addPhoto`, web `image-resize.ts` | TC-I-08, TC-I-09, TC-U-11, TC-U-14 |
| FR-008 | 03 §7.3 | `visit/VisitController`, `Repository.markVisitedNow` | TC-I-07 |
| FR-009 | 03 §4.2 | `MapScreen.kt addHouseLayers`, web `map-page` | TC-M-02 |
| FR-010 | 03 §4.2 | `HouseListScreen.kt` | TC-M-03, TC-I-35 |
| FR-011 | 03 §4.2 | `CompareScreen.kt`, web `compare-page` | TC-M-04, TC-U-53, TC-I-35 |
| FR-012 | 03 §10 | `HouseService.delete/purge`, `VisitController.delete` | TC-I-05, TC-I-16 |
| FR-013 | 03 §7.2, §8.2 | `location/HuntService.checkNearbyHouses` | TC-U-07, TC-F-02 |
| FR-014 | 03 §7.2 | `HuntService.checkStreet`, `StreetAlerts` (`:shared` location), `ReverseGeocoder` | TC-U-07 (`StreetAlertsTest`), TC-F-03 |
| FR-015 | 03 §7.3 | `location/StayDetector`, `HuntService.onStayStarted/Ended` | TC-U-01..03, TC-F-04 |
| FR-016 | 03 §8.2 | `HuntService.onLocation` accuracy gate | TC-U-08, TC-F-05 |
| FR-017 | 03 §8.2, ADR-01 | `HuntService`, `Notifications.CHANNEL_HUNT`, manifest `foregroundServiceType=location` | TC-F-01, TC-F-07 |
| FR-018 | 03 §4.2 | `HuntState`, `MapScreen.HuntCard` | TC-F-02 |
| FR-019 | 03 §10, §6.2 | `data/AppDatabase` (schema export, `app/schemas/…/2.json`), `Repository` | TC-F-08, TC-U-36 (`RoomSchemaTest`) |
| FR-020 | 03 §10, 09 §5 | `data/SyncWorker`, `ApiClient` + `RetryPolicy` (`:shared` api; replaced `data/RetryInterceptor` in Sprint 3.5) | TC-F-08, TC-U-17, TC-U-35 |
| FR-021 | 03 §7.1, §10 | `Repository.sync`, `data/SyncRules`, `PhotoController.changes` | TC-U-06 (part), TC-I-05, TC-I-17 |
| FR-022 | 03 §10 | `HouseService.upsert`, `VisitController.upsert`, `SyncVersions`, `Repository.sync`, `SyncRules.keepLocal` (`:shared` sync) | TC-I-04, TC-U-06 (`SyncRulesTest`), TC-I-14 |
| FR-023 | 03 §10 | `SettingsStore.saveServer` | TC-U-06 |
| FR-024 | 03 §4.3 | web `pages/*`, `core/config.*`, `core/api.interceptor.ts` | TC-M-05, TC-U-09, TC-U-19, TC-U-20 |
| FR-025 | 03 §4.3 | web `core/geocode.service.ts` | TC-M-05 |
| FR-026 | 03 §11 | `HouseRepository.findNearby` | TC-I-03, TC-I-13 |
| FR-027 | 03 §11 | `HouseRepository.findLiveOnStreet` | TC-I-03, TC-I-19 |
| FR-028 | 03 §9 | `StatsController` | TC-I-07 |
| FR-029 | 03 §9 | actuator config in `application.yml` | TC-I-02 |
| FR-030 | 03 §4.2 | `data/Settings.kt`, `data/ServerUrl.kt`, `ui/SettingsScreen.kt` | TC-M-06, TC-U-15 |
| FR-031 | 03 §9 | `privacy/DataController`, `DataService.export` | TC-I-18 |
| FR-032 | 03 §9 | `privacy/DataController`, `DataService.deleteAll` | TC-I-18 |
| FR-033 | 03 §7.3 | `MainActivity.handle`, `Root.kt` deep links | TC-F-04, TC-S-12 |
| FR-034 | 03 §10 | android `Repository.deletePhoto/sync`, backend `PhotoService.delete` | TC-I-17, TC-F-08 |
| FR-035 | 09 §3 | `SyncWorker`, `Repository.sync(photosAllowed)`, `Settings.photosOnWifiOnly` | TC-F-10 |
| FR-036 | 05 §8.2 | `res/values*/strings.xml`, `i18n/AppLocale.kt`, `res/xml/locales_config.xml`; web `i18n/*` | TC-L-01, TC-L-05, TC-U-21 |
| FR-037 | 03 §7.5 | web `pages/ask`, android `ui/AssistantScreen.kt` | TC-M-09, TC-AI-01..03 |
| FR-038 | 03 §7.6 | web `house-detail-page` *Fill in from listing text* (`listingFill.*`), android `HouseEditScreen.PasteListingDialog` | TC-M-09, TC-AI-05 |
| FR-039 | 03 §7.7 | web `pages/plan`, android `AssistantScreen.PlanPane` | TC-M-09, TC-AI-07 |
| FR-040 | 09 §2 | `HuntService.requestUpdates/stopIfBatteryLow` | TC-F-06, TC-F-09 |
| FR-041 | 05 §4 | `ui/Theme.kt`, `res/values-night` | TC-A-06, TC-U-56, TC-M-26 |
| FR-083, FR-084 | [11](11-feature-parity-and-export-spec.md) 5.16 | planned: reminder scheduler (alarm + WorkManager fallback), `Notifications`, Settings | Planned TC-U-38, TC-M-18 ([06](06-test-plan.md) §13, [11](11-feature-parity-and-export-spec.md) §13) |
| FR-085..FR-088 | [11](11-feature-parity-and-export-spec.md) 5.17, 03 ADR-01 | planned: `HuntingArea` model and cooldown (`:shared` candidates), Room 3 table, geofence registrar, boot receiver, notifications | Planned TC-U-39, TC-U-40, TC-M-18, TC-F-12, TC-A-12 |
| NFR-001 | 03 §11 | GIST indexes in `V1__init.sql` | TC-P-01 |
| NFR-002 | 03 §5 | `ApiClient` read timeout 90 s | TC-P-02 |
| NFR-003 | 03 §7.2 | `HuntService.checkNearbyHouses` | TC-P-03 |
| NFR-004 | 03 §10 | Room + WorkManager | TC-F-08 |
| NFR-005 | 03 §8.2, ADR-01 | `LocationRequest` settings | TC-F-06 |
| NFR-006, NFR-007 | 05 | see 05; web `i18n/translation.service.ts`, dictionaries | TC-A-01..05, TC-L-01..04, TC-U-21, TC-U-56, TC-M-26 |
| NFR-008 | 03 §5, 08 | deployment, backups | TC-O-01 (restore drill) |
| NFR-009 | 03 §6, ADR-08 | `photo` table | TC-P-04 |
| NFR-010 | 03 §5 | `Dockerfile` JVM flags, Hikari pool | TC-P-02 |
| NFR-011 | 03 §5 | `docker-compose.yml` | CI build |
| NFR-012 | 03 §4 | `geocode.service.ts`, `HuntService.checkStreet` | TC-U-07, review |
| NFR-013 | 06 | CI (07): `.github/workflows/*` | CI |
| NFR-017 | 09 | `ApiClient`, `RetryPolicy` (`:shared` api, Ktor), `data/Api.kt` (one `HttpClient`), `NetworkState` | TC-U-17, TC-U-35, TC-S-13 |
| NFR-030 | [11](11-feature-parity-and-export-spec.md) 5.17 | planned: geofence registration settings | Planned TC-F-12 |
| NFR-018 | 09 §7 | `server.compression`, `Settings.photosOnWifiOnly` | TC-F-10 |
| NFR-019 | 03 §6 | `PhotoService`, `MAX_PHOTOS_PER_HOUSE` | TC-I-17 |
| NFR-020 | 05 §7.1, §7.2, §5.1 | `ui/*.kt` semantics | TC-A-03, TC-A-04, TC-A-13, TC-M-22, TC-M-23 |
| SEC-001, SEC-003 | 03 §12 | `config/ApiKeyFilter`, `RequestPaths`; web `core/api.interceptor.ts` (key only to the API) | TC-I-01, TC-I-15, TC-U-12, TC-U-18, TC-U-20, TC-S-08, TC-S-10 |
| SEC-002 | 03 §12 | `ApiKeyFilter.validateKeys` (`MIN_KEY_LENGTH` 32), called by the `config/WebConfig` constructor | TC-U-18, TC-I-10b |
| SEC-004 | 03 §12 | `network_security_config.xml`, `ServerUrl` | TC-U-15, TC-S-07, TC-S-09 |
| SEC-005 | 03 §12 | `WebConfig.corsFilter` | TC-I-11 |
| SEC-006 | 03 §9 | DTO validation, `HouseController.nearby` | TC-I-06, TC-I-13 |
| SEC-007 | 03 §7.4 | `PhotoService.upload`, `ImageSanitizer` | TC-I-08, TC-I-17, TC-U-14 |
| SEC-008 | 03 §12 | `ApiRateLimitFilter`, `ApiKeyFilter` failure bucket, `AiRateLimitFilter`; web client: `SyncService` 429 wait | TC-U-12, TC-I-12, TC-U-48 |
| SEC-009, SEC-013, SEC-014 | 07 | CI (`security.yml`), `.gitleaksignore` (reviewed fingerprints only), `backend/pom.xml` `tomcat.version` override | TC-S-01..03 |
| SEC-010, SEC-011 | 03 §12 | `ApiKeyCipher`, `Settings.kt`, `data_extraction_rules.xml`, web `config.service.ts` | TC-S-07, TC-M-06, TC-M-11, TC-U-19 |
| SEC-012 | 03 §12, 07 §6.3 | `SecurityHeadersFilter`, `web/firebase.json`, `.github/firebase-tools/check-live-headers.sh` | TC-I-20, TC-S-04, TC-S-23, TC-S-24, TC-M-19 |
| SEC-015, SEC-016 | 03 §12 | `common/ApiExceptionHandler`; Android `SyncOutcome` and `ApiException` (`:shared`) | TC-S-04, TC-U-16, TC-U-35, review |
| SEC-017 | 08 §5.1, 07 §7 | `ApiKeyFilter` (list of current + next key), `AppProperties.apiKeyNext`, `application.yml` `app.api-key-next` | TC-U-18, TC-I-21, TC-O-02 |
| SEC-018 | 07 §5 | `app/build.gradle.kts` (`signingConfigs.release` from `HH_*`), `android.yml` job `release` | TC-S-06, TC-S-15 |
| SEC-019 | 07 §6 | DB setup | Review |
| SEC-020 | 03 §10 | `sync/ClientClock` | TC-I-10, TC-U-13 |
| SEC-021, SEC-022 | 03 §12 | `MainActivity`, `Notifications` | TC-S-06, TC-M-07 |
| SEC-023 | 03 §9 | `application.yml` management | TC-I-02 |
| SEC-024 | 07 | `backend/Dockerfile`, `backend/db/Dockerfile`, `docker-compose.yml` | TC-S-05, TC-S-14 |
| SEC-026..SEC-030 | 03 §12, 07, 09 | `RequestSizeLimitFilter`, `SyncVersions`, `ApiClient` (`:shared`, no redirects, content-type check), `docker-compose.yml`, workflows | TC-I-12, TC-I-14, TC-S-13, TC-U-35, CI |
| SEC-041 | [schemas/README.md](schemas/README.md), 03 §16 | `shared/export/Backup.kt` (`BackupFormat`, `BackupValidation`), `app/export/BackupReader.kt`, `backend/backup/BackupController` | TC-U-28, TC-S-17, `BackupApiTest`, `BackupParityTest` |
| SEC-042 | 03 §16.3 | `shared/export/HtmlWriter.kt`, `CsvWriter.kt`, `XlsxWriter.kt`, `MarkdownWriter.kt`; `web/src/app/export/*.ts` | TC-U-27, TC-S-16 |
| SEC-044 | 03 §16.4 | `web/public/sw.js`, `web/src/app/pages/data/data-page.ts` (clear) | TC-S-19 |
| SEC-049 | [11](11-feature-parity-and-export-spec.md) 5.16..5.18 | planned: notification actions, boot/package-replaced receivers | Planned TC-S-22 |
| PRV-001..003 | 03 §7.2, 04 §4 | `HuntService`, manifest permissions | TC-F-07, TC-S-07 |
| PRV-024..PRV-027 | [11](11-feature-parity-and-export-spec.md) 5.18, 03 ADR-01 | planned: permission state checks on resume, rationale screen, settings deep link, area wake-up auto-off | Planned TC-U-40, TC-M-18 (permission matrix), TC-A-12 |
| FR-042..FR-048 | 03 §16, [schemas/README.md](schemas/README.md) | `shared/export/**`, `app/export/**`, `app/ui/ExportScreen.kt`, `app/ui/ImportScreen.kt`, `web/src/app/export/**`, `web/src/app/pages/data/**`, `backend/backup/**` | TC-U-26..29, TC-U-42, TC-U-45..47, TC-U-50, TC-U-52, TC-I-33, TC-I-34, TC-S-16, TC-S-17, TC-M-12, TC-M-20, TC-M-21, TC-M-22, TC-A-10 |
| FR-089..FR-097 | [schemas/README.md](schemas/README.md) §0, §6, §7; 03 §16.3; [12](12-brand-and-naming.md) G | Android `shared/export/ImportPlan.kt`, `BackupValidation`, `app/export/BackupReader.kt`, `app/ui/ImportScreen.kt`; server `backend/backup/**`; web: planned (4b) | TC-U-42, TC-U-28, TC-S-17, TC-I-33, TC-I-34; the web import's tests are planned with S4b-00 |
| FR-098 | 03 ADR-22, §4.2, §4.3; [05](05-ux-accessibility-i18n.md) §7.3; [11](11-feature-parity-and-export-spec.md) D-26, §10 | web `shared/india-boundaries.ts`, `shared/map-style.ts` (`createMlMap`), `public/geo/in-boundaries.geojson`; Android `ui/IndiaView.kt`, `ui/IndiaViewRules.kt`, `ui/MapScreen.kt` (`loadStyle`), `assets/geo/in-boundaries.geojson`; data builder `web/scripts/geo/build_in_boundaries.py` | TC-U-54, TC-U-55, TC-S-25, TC-M-25, TC-M-26 (screens only) |
| FR-070..FR-073 | 03 §16.4 | `web/public/manifest.webmanifest`, `web/public/sw.js`, `core/pwa.service.ts`, `data/storage.service.ts`, `data/local-db.ts`, `shared/app-banners.ts`, `pages/share/share-page.ts`, `scripts/sw-precache*.mjs` | TC-U-31, TC-U-43, TC-U-44, TC-S-19, TC-M-15, TC-M-19 |
| NFR-021..NFR-027 | 03 §16 | `shared/export/ExportModel.kt` (fixed order, passed-in clock), `web/src/app/export/zip.ts` (stored entries) | TC-U-26, TC-U-29, TC-P-05 (planned) |
| PRV-012, PRV-018 | 03 §16.2 | `ExportBundle.build` (one redaction point), export screens | TC-U-27 |
| PRV-004, PRV-005 | 08 §6 | `privacy/DataService`, `HouseService.purge`, V3 migration | TC-I-16, TC-I-18 |
| PRV-008 | 03 §7.4 | `Repository.addPhoto`, `image-resize.ts`, `ImageSanitizer` | TC-U-11, TC-U-14 |
| PRV-009, PRV-010, PRV-011 | 04 §6, 07, 08 | config / ops | Review |
| PRV-022, PRV-023 | 02 T-I20, [11](11-feature-parity-and-export-spec.md) 5.13, [ai/vertex-setup.md](ai/vertex-setup.md) | AI provider configuration; Android on-device AI (planned) | Review; TC-U-34, TC-S-21 when accepted into 06 |
| AI-001..AI-012 | 03 §13, [ai/](ai/) | `backend/.../ai/**`, web `core/ai.service.ts`, `pages/ask`, `pages/plan`, android `AssistantScreen.kt`; eval harness `backend/src/test/.../ai/eval/`, `docs/ai/evals/golden-set.json` | TC-AI-01..08 (measured by TC-AI-09/10), TC-AI-11 (native Gemini embeddings), TC-AI-12 (indexing failures, reindex 503; AI-011), TC-AI-13 (scorecard verdict; AI-012), TC-AI-14 (AI-001 embedding provider selection), TC-AI-15 (contact redaction; AI-010), TC-AI-16 (provider wire-format contract tests), TC-AI-17 (Ask prompt citation and contrast rules; AI-002, AI-003), TC-M-09 |
| AI-013..AI-015 | [11](11-feature-parity-and-export-spec.md) 5.13, 02 T-D4, T-I22, [08](08-operations-runbook.md) §10.2 | planned: AI allowlist, quota and cap in `backend/.../ai/**`, Android on-device AI | Planned TC-U-34, TC-S-21 ([11](11-feature-parity-and-export-spec.md) §13) |
| AI-016 | [ai/ai-design.md](ai/ai-design.md) 2.1, 3.3, [ai/vertex-setup.md](ai/vertex-setup.md), 03 §13, 02 T-I22, 07 §4 | `backend/.../ai/config/AiProperties.java` and `AiDefaultsEnvironmentPostProcessor.java` (`app.ai.provider`), `backend/.../ai/vertex/**` (`VertexAiConfiguration`, `GoogleAccessTokenSource`, `VertexEndpoints`), `backend/.../ai/embedding/VertexEmbeddingModel.java`, `backend/.../ai/ProviderErrors.java`, `backend/.../ai/web/AiExceptionHandler.java`, `HouseIndexer` (quota stop, `AI_INDEX_ON_CHANGE`), `.github/workflows/ai-evals.yml` (`provider` input, WIF) | TC-AI-16 (Vertex chat and embedding contract tests), TC-AI-18 (provider error classification), TC-AI-19 (Vertex settings and auto-configuration), TC-AI-20 (provider selection), TC-AI-21 (re-index and eval stop on quota); TC-AI-10 real run against Vertex AI pending |
| AI-017 | AI-015, [08](08-operations-runbook.md) §10.2 and IR-9, [10](10-sprint-log.md) C-24 | planned: `ProviderErrors` / `AiExceptionHandler` extension (spend cap reason next to `AI_QUOTA_EXHAUSTED`) | Planned: contract test in TC-AI-16 with the captured spend cap response; not yet in 06 |
