# 11: Feature parity (SeenHouse) and offline copy: specification

| Field | Value |
|---|---|
| Document | Feature parity and offline-copy export specification |
| Version | 0.60 |
| Date | 2026-10-06 |
| Author | Claude (Cowork) – Product/Architecture |
| Status | Draft: product-owner decisions D-01, D-02, D-03, D-08, D-21 (AI access) and D-23..D-25 (Sprint 4b reminders, hunting areas, location permissions) and D-26 (India's boundaries on the map, 2026-09-24) applied; ready for Sprint 4 planning |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) – Product/Architecture | First version (commit `baf26b8`). Product-owner (Sriram) decisions (A) parity with SeenHouse and (B) an offline copy of the user's data. Gap analysis against the code as of 2026-09-22 (Flyway V1–V3, Room v2, API-key auth), designs, user stories, new requirement IDs, Flyway V4..V11, API, UI, i18n/a11y, STRIDE additions, tests, phased plan, open decisions D-01..D-14. |
| 0.2 | 2026-09-22 | Claude (Cowork) – Product/Architecture | Product-owner decisions applied (section 2). **D-01**: local-first. Doorprints is fully usable without signing in (Android Room, PWA IndexedDB); optional **Google Sign-In** unlocks sync, the web app with your data, Google Drive storage and more AI. No own email/password accounts: Argon2id and TOTP parity is **delegated to Google** (section 3.1), trusted devices stay. Guest AI allowance with a fair sign-in prompt. **D-02**: photos on the device by default, optionally in the user's own Google Drive (`drive.file`), only thumbnails and metadata on the server. **D-03**: no transactional email; reminders are local notifications. **D-08**: account deletion with a 7-day grace period. **Export**: deterministic offline exporters for HTML, PDF, CSV, XLSX, JSON backup and Markdown, plus an **AI custom export** (5.3). Requirement IDs renumbered (FR-042..FR-082, NFR-021..NFR-029, SEC-031..SEC-048, PRV-012..PRV-021; still only proposals). Removed: own passwords, Argon2id, TOTP, recovery codes, email, invites, `/api/backup/data`, `/api/import/batch`. Sprint 4 split into 4a/4b; Sprint 5 re-planned around Google. Repository rename note. |
| 0.3 | 2026-09-22 | Claude (Cowork), Docs team | Product-owner decision **D-21, AI access policy** (section 2): guests get **no cloud AI**. On Android they get on-device AI (Gemini Nano through the ML Kit GenAI Prompt API, beta) where the device supports it; elsewhere AI is hidden. Cloud AI is only for the owner and Google accounts the owner invites, on a paid key with a hard cap (Vertex AI or the paid Gemini API tier; the free AI Studio tier is never used with real user data, [02](02-threat-model.md) T-I20). **D-22: bring-your-own-key rejected** (consumer UX, payment-linked secret risk, support burden). Section 5.13 rewritten (the guest AI allowance, installation ID, `X-Doorprints-Install` and the sign-in-for-more-AI prompt are removed). Changed: summary, D-01 row, V8 (`ai_usage` per user, `ai_cloud_allowed`), G-02, G-19, G-21, P-1, P-3, 5.1, 5.3, US-35, FR-049, FR-067, FR-075, FR-079, SEC-031, SEC-036, SEC-047, PRV-014, PRV-020, API, UI, a11y, T-D7, DF-39, RR-09, TC-U-34, TC-S-21, S5-07, C-15, RK-07, D-15, D-20, section 17. Repository note: renamed to `Sriram-Codes-SW/doorprints` (public, MIT). Requirement IDs copied into [01](01-requirements.md) v0.9 as AI-013..AI-016 and PRV-022..PRV-023. |
| 0.4 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes. 5.13 hard cap: layer (2) is now a Google Cloud **spend cap budget** (Preview; monthly, before credits) on an AI-only project instead of an unverified Quotas-page limit, which is optional; SEC-047 and D-21 updated; provider row names the Vertex credential rules (02 T-I22, 07 §4). T-D7 mitigation updated. Status note: accepted AI-access rows are already in 01 v0.9 and 02 v0.10. |
| 0.5 | 2026-09-22 | Claude (Cowork), Docs team | Vertex AI code and [ai/vertex-setup.md](ai/vertex-setup.md) landed in the same change set: "being written" markers removed from D-21 and 5.13. 5.13 provider row: the Vertex credential is Application Default Credentials only (no Vertex API key), and the spend cap trip gets its own requirement ([01](01-requirements.md) AI-017). |
| 0.6 | 2026-09-22 | Claude (Cowork), Docs team | **Product-owner decisions of 2026-09-22 (Sprint 4b scope additions and the location permission model)**, section 2 D-23..D-25: new **5.16 Hunt mode reminders** (local notification 5 to 60 min before a planned viewing, default 15, actions Start Hunt mode / Dismiss; exact alarm when the user allows "Alarms & reminders", otherwise an early-starting 10-minute window, corrected in v0.7), **5.17 Hunting areas and area wake-up** (up to 20 circles of 200 m to 2 km, Geofencing API ENTER geofences, notification with a Start Hunt mode action, never auto-start, 6-hour cooldown per area, re-registration after reboot and update), **5.18 location permission model** (foreground-only by default; "Allow all the time" only when area wake-up is turned on, after a rationale screen; auto-off when downgraded; re-check on resume; approximate location; Play policy note). Accepted rows copied to [01](01-requirements.md) v0.12: FR-083..FR-088, NFR-030, SEC-049, PRV-024..PRV-027 (PRV-001 amended). New US-38, US-39; T-I23, T-I24, T-E8, DF-40; TC-U-38..40, TC-M-18, TC-F-12, TC-S-22, TC-A-12; stories S4-17..S4-19 (Sprint 4b grows to 63 points, RK-01 and RK-15); Room 3 adds `hunting_areas` and `viewings.huntReminder`; UI and section 17 rows. Sprint 3.5 (KMP `:shared` module, [03](03-design.md) ADR-14): **ADR-14 is now the KMP decision**, so this spec's proposed ADRs are renumbered ADR-15 (identity), ADR-16 (Drive), ADR-17 (no portal scraping), ADR-18 (AI custom export); Room `exportSchema` is already on (8.2, S4-00: only the migration test remains); area model and cooldown logic are `:shared` candidates. |
| 0.7 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes. **5.16 Scheduling and Settings**: v0.6 relied on a `setWindow` window of at most 5 minutes ("up to 5 minutes late"), which the platform does not give: the official guide "Schedule alarms" (checked 2026-09-22) says `windowLengthMillis` under 600000 is typically clipped to 10 minutes for apps targeting Android 12+, and `setWindow` is not allow-while-idle. Now: `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` is true; otherwise `setWindow(T − 10 min, 10 min)` so the reminder is early, never late (except in Doze or battery saver); `SCHEDULE_EXACT_ALARM` declared (not `USE_EXACT_ALARM`) with an optional Settings link to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`; reschedule on grant broadcast, resume and boot. Merge rule with the viewing reminder is now 10 minutes. 5.8 Android reminders use the same scheduler. Settings copy: "may arrive up to about 10 minutes early (or later if the phone is in battery saver)". **TC-U-38** asserts the early window and no exact call without the permission. **T-E8 / TC-S-22**: notification-action and alarm `PendingIntent`s immutable; the geofencing `PendingIntent` mutable (required) and explicit to a non-exported receiver. RK-10 mitigation updated. T-I23, T-I24, T-E8 copied to [02](02-threat-model.md) and TC-U-38..40, TC-M-18, TC-F-12, TC-S-22, TC-A-12 to [06](06-test-plan.md) (section 17). |
| 0.8 | 2026-09-22 | Claude (Cowork), Docs team | Sprint 4a as built (tickets S4-00/c and the Android handover item 6 from `docs/schemas/README.md` §9). **5.2 Import**: "Import writes to Room / IndexedDB only; no server endpoint is needed" contradicted the shipped `POST /api/import` (`?dryRun=true` for the preview; [01](01-requirements.md) FR-047, [03](03-design.md) §9 and ADR-20); it now says devices import locally and the server has its own restore endpoint, and lists the **bare `data.json`** (the server's `GET /api/export` download) as an import source Android accepts. **Section 9**: the "dropped" note no longer says imports are local only. **5.10 Updates, install**: "menu item" → the *Your data* card plus a one-time banner with a 30-day "Not now". **SEC-044 row**: this build's files, not "the app shell only". Plan text elsewhere is left as the plan. |
| 0.9 | 2026-09-22 | Claude (Cowork), Docs team | Android round of 2026-09-22 23:10–23:15 (`android/shared/README.md` 1.9, handover item 9). **5.2 Options**: a JSON backup of *All houses* now carries the visits that belong to no house (Android, `ExportBundle.unlinkedVisits`, last in `data.json`); the readable copies and tables do not; the web writer still drops them. |
| 0.10 | 2026-09-23 | Claude (Cowork), Docs team | Owner decision of 2026-09-23: the web app is hosted on **Cloudflare Pages** at `https://<project>.pages.dev` ([03](03-design.md) ADR-21), and `web/public/_headers` is now actually served. **RK-06**: the web origin *is* a `pages.dev` subdomain now, so the authorised-domain question is concrete for S5-01. **RK-12**: `_headers` sends `Cross-Origin-Opener-Policy: same-origin`, which Google documents as breaking the Sign in with Google popup when FedCM is not used (it asks for `same-origin-allow-popups`), so S5-01 must decide COOP together with the GIS CSP additions (SEC-048). No scope change. |
| 0.11 | 2026-09-23 | Claude (Cowork), Docs team | **Owner decisions of 2026-09-23.** The web app is on **Firebase Hosting at `https://doorprints.web.app`** ([03](03-design.md) ADR-21), replacing the Cloudflare Pages plan, which was never set up: **RK-06** (a `web.app` subdomain is Google-owned, so it cannot be verified as the owner's authorised domain; a custom domain comes only after web import ships, [12](12-brand-and-naming.md) section D) and **RK-12** (the COOP header now comes from `web/firebase.json`). **Naming** ([12](12-brand-and-naming.md) section G; brand advisor): "import" is reserved for Doorprints backups, so **G-02** and **§5.9** say *add* ("Add a shared listing"), not import; the AI helper is *Fill in from listing text*. The approved import definition is [01](01-requirements.md) §6.9 (FR-089..FR-097). §5.10 *Headers* row: as built, `web/firebase.json`. |
| 0.12 | 2026-09-23 | Claude (Cowork), Docs team | Android §9 item 22 applied (coordinator's final review of 2026-09-23): **§10 UI changes** records the Android add-house flow as it is (*Add a house on the map* → the Map tab and, from the house list, a tip naming *Save house here* and long-press; *Save house here* at the current location) and its **parity target, an accessible pick-a-spot mode** (centre crosshair and a 48 dp *Save house at centre*), planned for Sprint 4b and listed under **§14.2**; a *Map orientation* row records that both maps are north-up (Android 1.31; the web in the working tree since 2026-09-23, handover 33). |
| 0.13 | 2026-09-24 | Claude (Cowork), Docs team | **Owner decision D-26 (2026-09-24, P0): India's boundaries on the map** ([03](03-design.md) ADR-22, [01](01-requirements.md) FR-098). Section 2 gains **D-26**; **§10 UI changes** gains the row *Map boundaries (India)*: web and Android apply the same five rules with the same layer and source ids to the same bundled file, so both draw the same map; the one deliberate difference is the "Natural Earth" attribution credit on the web only (`android/shared/README.md` 1.36 item 37, `web/README.md` row of 2026-09-24). |
| 0.14 | 2026-09-24 | Claude (Cowork), Docs team | Round 1 review of the Docs change for India's boundaries (major). **§10 *Map boundaries (India)*** no longer says "Identical on both": compared rule by rule (`IndiaViewRules.kt` and `IndiaView.kt` against `india-boundaries.ts`, code as of 2026-09-23 19:56 UTC, 2026-09-24 01:26 IST), Android's rule 2 also requires an adm0 side on `boundary_2` lines (`android/shared/README.md` 1.37) and the web's does not. Recorded as an **open parity gap** handed to Web (Android §9 item 36), not a deliberate difference; the row goes back to "the same five rules" when Web adds the guard and its spec cases. Android's `in-boundary-world` maxzoom `Math.nextDown(5f)` is recorded as the same behaviour as the web's exclusive maxzoom 5. The only deliberate difference is still the web's "Natural Earth" credit. |
| 0.15 | 2026-09-24 | Claude (Cowork), Docs team | **§10 *Map boundaries (India)*** re-synced with the code at HEAD `3ad2b58` (comment and docs round, no behaviour change): the web's `shared/india-boundaries.ts` has rule 2's adm0 clause (`COUNTRY_LINE_RULE`, `COUNTRY_LINE_RULE_LEGACY`) and the tile-zoom guard (`TILE_ZOOM_GUARD`), the same as Android's `IndiaViewRules.kt`, so the **open parity gap is removed** and the row says "the same five rules" again, naming the guard. The row records that the guard is defence in depth on both renderers (read from source), and the two known limits shared by both apps (the Assam-Arunachal Pradesh state line from zoom 5, S4b-BL-15; doubled lines, S4b-BL-11 and S4b-BL-16). Still one deliberate difference: the web's "Natural Earth" credit. |
| 0.16 | 2026-09-24 | Claude (Code), Docs team | **§10 *Map boundaries (India)*** re-synced with branch `fix/india-boundary-lines` (PR #16, HEAD `9e0036e`): both apps' country-line rule also leaves out India's line with China (`INDIA_CHINA_LINE`, both syntaxes), both have the new state layer `in-boundary-state` (from zoom 5, directly above `boundary_3`, drawn like it), and the data file stays byte-identical (sha256 `2c497e2e…56d7`, kinds `world`, `claim`, `state`); the known limits of the state line and the two close lines are fixed (S4b-BL-11, -15, -16). |
| 0.17 | 2026-09-24 | Claude (Code), Docs team | **§10 *Map boundaries (India)*** after the Singalila spur fix (round 2 reviews): sha256 `25984afa…a024`; the shared known limits sized (Sikkim tri-junction loops about 13 x 3 km and 2 km; the tile line's overrun at Jomotsangkha and Longwa from about zoom 10 (a small hook at Jomotsangkha from zoom 9)). No parity change. |
| 0.18 | 2026-09-24 | Claude (Code), engineer | Legacy House Hunt names renamed (owner request of 2026-09-24; [03](03-design.md) ADR-24). The repository note says packages, storage keys and database names follow the brand since 2026-09-24. |
| 0.19 | 2026-09-24 | Claude (Code), engineer | The Room migration test (R-06) is done: CMP-4 P4a, [06](06-test-plan.md) TC-U-63 (the Room row and S4-00). |
| 0.20 | 2026-09-29 | Claude (Code), lead | Map boundaries row: maplibre-gl 6.11.2 `worker_tile.ts:110` ([10](10-sprint-log.md) §16). |
| 0.21 | 2026-09-29 | Claude (Code), lead | New owner decision **D-23**: signed-in users may bring their own Gemini key (stored encrypted and write-only on the hosted server, used only for them); it supersedes **D-22**. To be built with Google sign-in, after the release security gate. |
| 0.22 | 2026-09-29 | Claude (Code), lead | New **D-27** (a signed-in person's own Gemini key stays on their device; OAuth to the Gemini API and "using a key by reference" considered and not taken) and **D-28** (no hosted server: Google sign-in syncs through each person's own Google Drive). |
| 0.23 | 2026-09-29 | Claude (Code), lead | New **D-29**: AI with the person's own Gemini key on the device, next to server AI ([03](03-design.md) ADR-26). |
| 0.24 | 2026-09-29 | Claude (Code), lead | New **D-30** and 5.19..5.26 (app lock, offline maps, the real cost of a house, my places, area notes, moving in, brokers; voice notes parked); 14.2: S4-11 widened, new S4-20..22. |
| 0.25 | 2026-09-29 | Claude (Code), lead | 5.19 **app lock built** on Android and iPhone (S4b-FR-5): the choices of time, turning it on or off behind the credential, the fail-closed read. |
| 0.26 | 2026-09-29 | Claude (Code), lead | New **5.27**: the path trace built (S4b-FR-2) on Android, inside `HuntEngine` so the iPhone gets it with S4b-BL-69. |
| 0.27 | 2026-09-29 | Claude (Code), lead | 5.27: the path trace is recorded on iPhone too (S4b-BL-69, Hunt mode on iPhone; [10](10-sprint-log.md) §13.14). |
| 0.28 | 2026-09-30 | Claude (Code), lead | 5.20 **offline maps built** on Android and iPhone (S4b-FR-6): the map's visible area as one of MapLibre's offline packs, the estimate and the cap in common code; the website is S4b-BL-79. |
| 0.29 | 2026-09-30 | Claude (Code), lead | New **5.28**, the design of sharing updates between two people who know each other (S4b-FR-3): an update file in the backup format, sent through any app, imported with the existing merge; the Drive folder of D-28 later as the automatic channel. New US-40. |
| 0.30 | 2026-09-30 | Claude (Code), lead | 5.28 **built on Android** (S4b-FR-3): *Share updates with…* from Settings > Your data, the update file, a received file opening in the Import screen. |
| 0.31 | 2026-09-30 | Claude (Code), lead | New **5.29**, the design of a house from a listing link (S4b-FR-4) with brokers (S4b-FR-11): the portal's share text parsed on the device, never the page (5.9 stands); brokers as the data-model change of (4c). New US-41. |
| 0.32 | 2026-09-30 | Claude (Code), lead | 5.29 **built** (S4b-FR-4, the listing flow): the no-AI parser on Android and the web with one fixture file, the Android share receiver, the map step, the duplicate check. |
| 0.33 | 2026-09-30 | Claude (Code), lead | New **5.30**, the design of the Sprint 4b data model in one change of format (N13 4c; [03](03-design.md) ADR-28): nested house values, one record envelope for every other new entity, `doorprints-backup/2`, Room 4 and IndexedDB 2, six slices. 8.1 and 8.2 updated. |
| 0.34 | 2026-09-30 | Claude (Code), lead | 5.30 **slice 0 built** (the records foundations, [10](10-sprint-log.md) §13.18): the format rule, the server's `record` table and endpoints, the `records` table and store with their sync, the web's upgrade path. |
| 0.35 | 2026-09-30 | Claude (Code), lead | 5.30 **slice 1a built** (the house's cost, carpet area and location source, [10](10-sprint-log.md) §13.19): slice 1 goes in three steps, 1a these values, 1b brokers, 1c rooms; 5.21 built. |
| 0.36 | 2026-09-30 | Claude (Code), lead | 5.30 **slice 1b built** (brokers, [10](10-sprint-log.md) §13.20): the first record type and the first `doorprints-backup/2` list; 5.25 built. |
| 0.37 | 2026-09-30 | Claude (Code), lead | 5.6 and 5.30 **slice 1c built** (rooms, [10](10-sprint-log.md) §13.21): rooms nested in the house, the length units, the `/2` rule now "a broker or a room". |
| 0.38 | 2026-09-30 | Claude (Code), lead | 5.4 and 5.30 **slice 2 built** (criteria and ranking, [10](10-sprint-log.md) §13.22): criteria and preferences as records, one scoring implementation per stack, the *Criteria* screen, the ranking. |
| 0.39 | 2026-09-30 | Claude (Code), lead | 5.5 and 5.30 **slice 3a built** (viewing questions, [10](10-sprint-log.md) §13.23); slice 3 is split into 3a questions, 3b viewings with reminders, 3c Hunt reminders. |
| 0.40 | 2026-09-30 | Claude (Code), lead | **Slice 3b designed** (5.8 viewings, [10](10-sprint-log.md) §13.24): the `viewing` record, the split into 3b-1 (data, screens, history, calendar file, backup, copies, server) and 3b-2 (the reminders on Android, iPhone and the website), the pure reminder rules, the vectors V1..V6. |
| 0.41 | 2026-09-30 | Claude (Code), lead | 5.8 and 5.30 **slice 3b-1 built** (viewings: the record, screens, history, calendar file, backup, copies, AI, server; [10](10-sprint-log.md) §13.24). The reminders (3b-2) and the Hunt reminder (3c) are not built. |
| 0.42 | 2026-09-30 | Claude (Code), lead | **Slice 3b-2 designed** (5.8 reminders: one pure rule set, Android alarms, iPhone notifications, the website's while-open notifications; [10](10-sprint-log.md) §13.25). |
| 0.43 | 2026-09-30 | Claude (Code), lead | **Slice 3b-2 built** (5.8 reminders on Android, iPhone and the website; [10](10-sprint-log.md) §13.25). |
| 0.44 | 2026-09-30 | Claude (Code), lead | **Slice 3c designed** (5.16 Hunt mode reminder before a viewing; [10](10-sprint-log.md) §13.26). |
| 0.45 | 2026-09-30 | Claude (Code), lead | **Slice 3c built** (5.16 Hunt mode reminder; [10](10-sprint-log.md) §13.26). |
| 0.46 | 2026-09-30 | Claude (Code), lead | **Slice 4a designed** (hunting areas as records, my places with distances, area notes; [10](10-sprint-log.md) §13.27). The area wake-up (geofences, 5.17/5.18) is slice 4b. |
| 0.47 | 2026-09-30 | Claude (Code), lead | **Slice 4a built** (areas, places, area notes; [10](10-sprint-log.md) §13.27). The wake-up (4b) is not built. |
| 0.48 | 2026-09-30 | Claude (Code), lead | **Slice 4b designed** (5.17 and 5.18: the area wake-up, the background-location rationale and the cooldown; [10](10-sprint-log.md) §13.28). |
| 0.49 | 2026-09-30 | Claude (Code), lead | **Slice 4b built** (the area wake-up on Android; [10](10-sprint-log.md) §13.28). The iPhone part (S4b-BL-96) follows. |
| 0.50 | 2026-09-30 | Claude (Code), lead | **Slice 5 designed** (5.7 photo tags, 5.24 moving in: the statuses Taken and Not chosen, the move-in checklist and condition record, *Close this hunt*; [10](10-sprint-log.md) §13.29). |
| 0.53 | 2026-10-01 | Claude (Code), lead | 5.24: the houses **in the running** (not Rejected, not Not chosen) are what Compare offers and a Plan visits, on the three stacks (S4b-BL-99 a); the website's copies label the move-in date *Move-in date* (S4b-BL-99 d). |
| 0.54 | 2026-10-01 | Claude (Code), lead | 5.2 and 5.28: the installed website opens a Doorprints `.zip` from the system in *Import a backup* (`file_handlers`, Chromium on a computer; S4b-BL-108). |
| 0.55 | 2026-10-02 | Claude (Code), lead | D-28 and 5.28 item 4 marked as amended by [15](15-google-drive-backup-and-sharing.md) §2.2 (`drive.file` only, no `drive.appdata`) and §4.2 (sharing v1 as a read-only file per person). |
| 0.56 | 2026-10-06 | Claude (Code), lead | **The path trace, version 2: design only** (owner request of 2026-10-06; S4b-FR-13..S4b-FR-18). New 5.27.0..5.27.11: repeats drawn thicker, in a second colour and dashed, with the person's choice of look (*Clear*, *Subtle*, *Off*); an optional sound alert; ending a walk and saving it linked to a house; the website's trace; the shared repeat-detection algorithm and its vector file `schemas/trace-repeat-vectors.json` (*proposed*); every string; twelve open questions with recommendations. 5.27 amended: saved walks and the website's trace stay local-only. |
| 0.57 | 2026-10-06 | Claude (Code), lead | **Senior review of the path trace design applied** ([ops/path-trace-spec-review.md](ops/path-trace-spec-review.md)). Six corrections: the live look is a `PlatformMap` `repeatLook` parameter (Android `LineLayer.setProperties`, iPhone `setRepeatLook`, website `setPaintProperty`), not `JsonStyleOps` (5.27.4); the alert's `blocked` flag survives one bad fix (5.27.3, vector `alert-one-bad-fix-does-not-ring-again`); a walk id of 0 is no id (5.27.2, 5.27.3, vector `split-walk-id-zero-is-no-id`); `walkAskedUpTo` replaces `walkToAsk`, so a walk cut by process death is asked about (5.27.6); the vector tests run in `androidHostTest` with a small inline `commonTest` (5.27.10); Android's device-to-device transfer copies saved walks, said honestly (5.27.0, 5.27.7). Also: the walk just finished is left out of the alert's others (vectors `alert-not-for-the-walk-just-finished`, `alert-walk-finished-30-minutes-ago-counts`; owner may overrule, question 1), the website splits its line across a page hidden for more than 5 minutes (question 7 adopted; the `resumed` flag, vector `web-pause-makes-no-segment`), the parallel-lane field check and the `TOLERANCE_M = 20` fallback, the `NonCancellable` sweep, the search-rule sentence, luminance 0.10, `#E65100` confirmed (protanopia: told apart from the amber star by form only), the log rule, the shared-browser sentence, the T-I29 residual, one banner wording, vector hygiene (`overlap-60m-is-not-a-repeat` moved off the 80 m boundary), questions 13 and 14. New 5.27.12: Hunt mode on the website, proposed, not scheduled (S4b-FR-19..23). |
| 0.58 | 2026-10-06 | Claude (Code), lead | **The path trace: an on-demand place check, *Have I been here?*** (owner request of 2026-10-06; S4b-FR-24; design only). New 5.27.13: the button (Map screen, house page, long press or crosshair), the exact semantics both stacks share (distance from the place to each walk's polyline on the local plane, `TOLERANCE_M` 25 m inclusive for *walked*, a second *close* band to 50 m, the 50 m accuracy gate, per-walk rows newest first with the time at the nearest point, no result from a fragment, no segment across a resumed point, the stored walks read whether or not the trace is on), the words and the `trace.here.*` strings, the map's matched-stretch halo and ring, accessibility, privacy (on demand, no network request, shown never stored or sent). 5.27.0 item 7, 5.27.2 constants (`NEAR_BAND_M`, `MAX_FIX_ACCURACY_M`, `CHECK_STRETCH_M`), 5.27.10 and open questions 15 to 17; the proposed website Hunt mode's requirements renumbered FR-109 and PRV-033. Vector file: new `placeChecks` section (21 cases). |
| 0.59 | 2026-10-06 | Claude (Code), lead | **The place check: the senior review applied** ([ops/path-trace-check-review.md](ops/path-trace-check-review.md), owner: go with the recommendations). 5.27.13: the website's *Here* is `watchPosition` through `shared/locate-once.ts`, stopped at the first fix of 50 m or better or at 15 s with the best so far (MUST-1); `{tolerance}` in `close`, `none` and `noneSaved` (MUST-2); `trace.here.and2` and `and3` deleted (the dictionary's `list.*` is used) and `a11y.stretch` renamed `stretchA11y` (MUST-3); the permission sentence in the choice dialog every time, no flag (MUST-4); no result, place or distance in a URL, `history.state` or storage, the house page draws on its own `LocationMap` (MUST-5); the headline announced once and withdrawn on close with `Announcer.cancel` (MUST-6); the check without blocking the page on the website (SHOULD-1), no website check before FR-17 and `trace-geo.ts` first (SHOULD-2), a trace walk with a saved walk's id left out (SHOULD-3), `L² = 0` gives `t = 0` (SHOULD-4), the weekday by skeleton `EEEdMMM` on the phones and an optional time zone on the website helper (SHOULD-5), `fuzzy` semantics (SHOULD-6), the button hidden on `/houses/new` (SHOULD-7). Open questions 15 to 17 decided as recommended; new question 18 (a one-press *Mark the houses I walked past*): LATER, not in S4b. |
| 0.60 | 2026-10-06 | Claude (Code), docs pass | **The path trace v2 and the place check moved from *planned* to *built*** (`feat/path-trace-v2`, PR #146, not yet merged; S4b-FR-13..FR-17 and FR-24 built, FR-18 the guide done). 5.27.0 and 5.27.13 no longer say *design, not built*; 5.27.10 names the real test classes (`TraceVectorsTest` replaces the planned two vector classes); the vector file is *confirmed*; the Room 11 and IndexedDB 3 rows say built. Owner device checks (TC-M-25 re-run, TC-M-57..61, MT-75..81) stay open and are not claimed. The review of the branch found six must-fix items (the Android Map redraw, the walk to ask about, the live walk's own points after the Room upgrade, the website's alert switch, `HuntService`'s Finish walk intent, the docs) and sixteen smaller ones: [10](10-sprint-log.md) S4b-FR-25..S4b-FR-37. |
| 0.52 | 2026-10-01 | Claude (Code), lead | 5.6: the **Basement** switch under Floor and the import's tolerant reading of a floor out of range, with a warning in the preview (S4b-BL-104 c, d); search finds the floor in the app's language too (S4b-BL-104 b). |
| 0.51 | 2026-10-01 | Claude (Code), lead | **The finishing batch built** (on stacked branches, [10](10-sprint-log.md) §13.29..§13.40): built notes for 5.2 (copies in UTC, the iPhone's copies and imports, the website's import), 5.6 (the floor, moving rooms), 5.7 (photo tags), 5.8 (the iPhone's calendar file, the reminder follow-ups), 5.17 and 5.18 (the iPhone wake-up), 5.19 (the emulator test, Hunt alerts with the app lock), 5.20 (offline maps on the website), 5.21 (the cost filters), 5.24 (moving in, the statuses Taken and Not chosen), 5.25 (the duplicate-flat warning), 5.28 (deletions in an update file, `/3`) and 5.29 (the locality lookup). |

Related: [01 Requirements](01-requirements.md) · [02 Threat model](02-threat-model.md) · [03 Design](03-design.md) · [04 DFDs](04-data-flow-diagrams.md) · [05 UX/a11y/i18n](05-ux-accessibility-i18n.md) · [06 Test plan](06-test-plan.md) · [10 Sprint log](10-sprint-log.md) · [AI design](ai/ai-design.md)

> **Status of this document.** It adds no code. Accepted AI-access rows were copied into [01](01-requirements.md) v0.9
> (AI-013..AI-016, PRV-022, PRV-023) and [02](02-threat-model.md) v0.10, and the accepted Sprint 4b additions
> (D-23..D-25: Hunt mode reminders, hunting areas, location permission model) into [01](01-requirements.md) v0.12
> (FR-083..FR-088, NFR-030, SEC-049, PRV-024..PRV-027); the rest is a proposal. When the product
> owner accepts it, the Docs team copies the accepted rows into 01, 02, 03, 04, 05, 06, 07, 08 and 10 (section 17).
> IDs here are reserved for that purpose.
>
> **Repository name.** Done on 2026-09-22: the repository is now **`Sriram-Codes-SW/doorprints`**, public, with an
> MIT `LICENSE` and a `SECURITY.md` (private vulnerability reporting). GitHub redirects the old `house-hunt` URL; the
> README was updated in the same change. Code packages, storage keys and database names kept the old name until
> 2026-09-24 (ADR-13) and use Doorprints since then (ADR-24).

---

## 1. Summary

| | |
|---|---|
| **Goal A** | Doorprints offers every feature SeenHouse (seenhouse.uk) offers, adapted to India and zero running cost (CON-01). |
| **Goal B** | The user can export their data **in several formats** that stay readable without the app: self-contained HTML (print to PDF), PDF, CSV, XLSX, Markdown, and an exact, re-importable JSON backup (ZIP with photos). All of these are **deterministic and work offline** on Android and in the PWA. An optional **AI custom export** writes free-form summaries ("a WhatsApp summary of my shortlist in Tamil for my parents"); it is labelled as AI-generated and is never a backup. |
| **Product shape (D-01)** | **Local-first.** Anyone installs Doorprints (Android APK or PWA) and uses **every** feature without signing in; data lives on the device. **Google Sign-In is optional** and unlocks what needs an account: sync across devices, the web app with your data, Google Drive storage for photos and backups. **AI (D-21):** guests get on-device AI only (Android devices that support Gemini Nano), otherwise AI is hidden; cloud AI is only for the owner and invited users. |
| **Gaps (section 3)** | Of 22 rows: 4 Have, 10 Partial, 5 Missing, and 3 (own accounts, Argon2id, TOTP 2FA) **met by delegation to Google** (3.1); trusted devices are built by us. (v0.1 miscounted this as 5/7/10.) |
| **Plan (section 14)** | **Sprint 4a:** local-first web (IndexedDB), all deterministic exporters, import, PWA. **Sprint 4b:** weighted criteria and ranking, viewing questions, rooms, photo tags, viewings and reminders, Hunt mode reminders, hunting areas with area wake-up, Share to Doorprints. **Sprint 5:** Google Sign-In, sync ownership per Google user, Google Drive storage, trusted devices, account deletion, AI access tiers (AI custom export below the cut line). **Sprint 6+:** API-key mode removed, earlier AI and map candidates. |
| **Still open** | Section 16: smaller decisions only (invited-user quota and hard-cap numbers, cover thumbnail, Drive encryption, CSP for Google's script). |

## 2. Product-owner decisions (2026-09-22)

| ID | Decision | Consequences in this spec |
|---|---|---|
| D-01 | **Local-first; optional Google Sign-In.** No login needed for any feature that can run on the device. Sign-in unlocks: cross-device sync, the web app with the user's data, Google Drive storage. No own email/password accounts; password security and 2FA are delegated to Google. Trusted devices are still built. Sign-in prompts appear only at features that need an account, never as item limits. (The v0.2 guest AI allowance is replaced by D-21.) | 5.1, 5.11; web becomes local-first (IndexedDB, 5.10); section 3.1 equivalence; own-account items from v0.1 removed |
| D-02 | **Photos: the user chooses.** Default: photos stay on the device. With the user's permission: photos and backups go to the user's own Google Drive (`drive.file` scope, app-created files only, a "Doorprints" folder). Revoked access and deleted files are handled gracefully. Only minimal thumbnails and metadata on our server. | 5.12, 5.15; server no longer stores full photos for new data (ADR-08 revisited); DB quota risk from v0.1 largely gone |
| D-03 | **No transactional email.** Reminders are local notifications (Android AlarmManager/WorkManager, PWA notifications). | No email service, no password reset (Google handles account recovery); 5.8 |
| D-08 | **Account deletion: 7-day grace period.** Offer an offline copy first. Deletion removes server data; Drive files stay in the user's Drive and the user is told so. | 5.14 |
| Export | **Multiple formats**: deterministic offline exporters for HTML, PDF, CSV, XLSX, JSON full backup (exact, re-importable) and Markdown; plus an **AI custom export** that works only from a structured export of the selected data, is labelled AI-generated, is never the backup, has numbers and prices validated against the source, excludes contacts unless the user opts in, and follows the AI access rules (D-21). | 5.2, 5.3 |
| D-21 | **AI access policy.** Guests get **no cloud AI**. On Android, guests (and signed-in users who are not invited) get **on-device AI**: Gemini Nano through the **ML Kit GenAI Prompt API** where the device supports it (feature-status check at runtime); where it is not supported, and in the web app/PWA, AI entry points are **hidden**, with no sign-in prompt. **Cloud AI** (Ask, planner, extraction and custom export on the server) is only for the **owner and Google accounts the owner invites**, on a **paid key with a hard cap**. The paid key is on Vertex AI or the paid Gemini API tier, because the free AI Studio tier may use prompts to improve Google's products; the free tier is kept for evals with synthetic data only. The AI Studio code path is kept so the owner can switch back (`AI_PROVIDER`; [ai/vertex-setup.md](ai/vertex-setup.md) step 13). | 5.1, 5.13; FR-079, SEC-031, SEC-036, SEC-047, PRV-020; 01 AI-013..AI-016, PRV-022, PRV-023 |
| D-23 | **Hunt mode reminders** (2026-09-22, Sprint 4b). A planned viewing can remind the user shortly before it (default 15 min, 5 to 60) with a "Start Hunt mode" action; global and per-viewing switches; offline, Do Not Disturb respected, 4 languages, accessible. | 5.16; [01](01-requirements.md) FR-083, FR-084 |
| D-24 | **Hunting areas / area wake-up** (2026-09-22, Sprint 4b). The user marks neighbourhoods; Android geofences offer Hunt mode on entering one; never auto-start; cooldown per area; stored locally, exported, synced later. | 5.17; [01](01-requirements.md) FR-085..FR-088, NFR-030 |
| D-25 | **Location permission model** (2026-09-22). Foreground-only by default; "Allow all the time" only when area wake-up is turned on, after a rationale screen; area wake-up turns itself off if the permission goes away. | 5.18; [01](01-requirements.md) PRV-024..PRV-027, SEC-049, PRV-001 amended |
| D-26 | **India's boundaries as the Government of India depicts them** (2026-09-24, P0 on the live site). Every map on both apps shows all of Jammu and Kashmir and Ladakh (PoK, Gilgit-Baltistan, Shaksgam, Aksai Chin included) and Arunachal Pradesh inside India, with no Line of Control, Line of Actual Control or other claim line. The only view: every user is in India, so no switch. | [03](03-design.md) ADR-22; [01](01-requirements.md) FR-098; §10 *Map boundaries (India)*; [02](02-threat-model.md) RR-16 |
| D-22 | **Superseded by D-23 (2026-09-29).** **Bring-your-own-key (BYOK) rejected.** Users will not paste their own Gemini/Vertex key. Reasons: consumer UX (creating a Cloud project, billing and a key is far beyond a house hunter), a payment-linked secret on phones and in our server is a high-value target with unbounded cost if leaked, and the support burden (quota errors, billing questions, revoked keys) falls on one owner. | None of the v0.2 text had BYOK; recorded so it is not proposed again |
| D-23 | **Bring-your-own-key allowed for signed-in users; replaces D-22** (owner, 2026-09-29: "We should also allow AI for users who have their own SSO and own key"). A user signed in with Google (D-01) may add their own Gemini API key in Settings; cloud AI then works for them without an invitation, on their key and at their cost. Invited users keep the owner's capped key (D-21); guests keep on-device AI only; self-hosters keep the key in their server's settings. How D-22's reasons are met: the key is optional and the user guide explains getting one (and the paid tier); it is stored only on the hosted server, encrypted at rest, write-only (the app shows its last four characters), used only for that user's requests, deletable (*Remove key*), never kept in the browser; the server's own per-user limits still apply; adding a key shows the free-tier privacy warning (Google may read what is sent) with a link to the guide's paid tier; a key the provider refuses shows a clear error and never falls back to the owner's key. Stored user keys are a new threat-model item and part of the release security gate's authorisation tests (one user can never use or read another's key). Built with Google sign-in ([14](14-lead-backlog-and-handoff.md) N13). | Changes D-22, P-1 ("never bring a key") and the AI tiers in 5.13 when built |
| D-27 | **A signed-in user's own Gemini key stays on their device; it never goes to the hosted server** (owner, 2026-09-29, on hosted-server trust: "I don't think the user will be comfortable with pasting their key there"; amends D-23, whose "stored only on the hosted server, encrypted at rest" no longer holds). (1) The key is kept only on the user's phone or browser, in the same secure storage as the device key (Android Keystore, iOS Keychain, the browser's storage), and sent straight from there to Google; the hosted server never receives it. Listing extraction calls Gemini from the device; for Ask and the visit planner the server finds the user's houses and returns them, and the device makes the Gemini call, so the prompts and the grounding checks run on the device too (a Sprint 5 cost, built with Google sign-in). (2) The user guide has users make a **separate key just for Doorprints** in AI Studio, which they can delete there at any time, with a budget alert (and on the paid tier a quota cap) in Google Cloud. **Considered and not taken now:** using the key "by reference" is not possible (an API key is sent by whoever calls; Google has no delegation for it); OAuth to the Gemini API (the user approves Doorprints on Google's consent screen) is, in Google's own words, a setup "appropriate for a testing environment", needs the broad `cloud-platform` scope and Google's verification for outside users, and does not cover billing a third-party app's calls to each user's own project; Google's advice for third-party tools is an API key. Revisit if Google adds delegated Gemini access. Self-hosters are unchanged: their key is on their own server (the owner page, [03](03-design.md) §12.1). | 5.11, 5.12; [03](03-design.md) §12.1, ADR-25 |
| D-28 | **No hosted server: Google sign-in syncs through each person's own Google Drive** (owner, 2026-09-29: "I don't want to host a server for the Google Sign in"). Replaces the hosted-server part of D-01 and the owner's capped key for invited users in D-21; D-23 and D-27 apply as "every signed-in person uses their own Gemini key, kept on their device". **Amended by [15](15-google-drive-backup-and-sharing.md) §2.2 (2026-10-02, a design for the owner's decision): `drive.file` only, a visible folder, no `drive.appdata`.** (1) *Sign in with Google* on the website, Android and iPhone; the houses, visits and photos sync through a private app folder in the person's own Drive (`drive.appdata`), and shared lists (S4b-FR-3) through a Drive folder they share (`drive.file`). Both scopes are **non-sensitive**: only Google's basic app verification (name, logo, privacy policy, the `doorprints.web.app` domain), free, no security assessment (Google's *Choose Google Drive API scopes*). (2) AI runs on the device with the person's own key (D-27): the app finds the relevant houses itself (a personal list is small) and calls Gemini directly. (3) The owner hosts nothing and holds nobody's data; the owner's only part is the free Google Cloud project that registers Doorprints for sign-in. The self-hosted server stays as the advanced option, with device pairing and the owner page ([03](03-design.md) §12.1, ADR-25). | 5.1, 5.11, 5.12; [03](03-design.md) §12.1 |
| D-29 | **AI with your own Gemini key on the device, next to server AI, behind one interface** (owner, 2026-09-29: "both are separate modules and the calling AI is common … he can simply enter an API key and get responses as well as choose the server"). Built before Google sign-in, which reuses it (D-27). | All three apps: Settings or *Connect* → *AI features* offers *Use my own Gemini key on this device* and *Use my server*; design [03](03-design.md) §13.1, ADR-26 |
| D-30 | **Features for an offline house hunt, from the gap review of 2026-09-29** (owner: "I would like to go ahead with your suggestion"). Take: an app lock (5.19), offline maps for the hunting area (5.20), the real cost of a house with my offer and the agreed price (5.21), my places and distances (5.22), area notes (5.23), moving in (5.24), brokers (5.25). Park: voice notes (5.26). Each goes with work already planned, so the data model and the sync, backup and export formats change once (14.2). | Order: [14](14-lead-backlog-and-handoff.md) N13; designs 5.19..5.26; tickets [10](10-sprint-log.md) §15 S4b-FR-5..12 |

v0.1 decisions now closed (v0.3 adds D-21 and D-22): D-01, D-02, D-03, D-07 (Web Push: not planned; local notifications only), D-08, D-10
(web session storage: see 5.11), D-11 (rely on device encryption). Still open: section 16.

## 3. Feature gap table

Status: **Have** = built and in [01](01-requirements.md); **Partial** = part is built; **Missing** = not built;
**Delegated** = met through the user's Google account.

| # | SeenHouse feature | Doorprints today | Status | How we build it (India, zero cost) | New IDs | Sprint |
|---|---|---|---|---|---|---|
| G-01 | Property logging | Houses with location, price in ₹, BHK, status, contact, notes (FR-001, FR-002, FR-006) | Have | Add carpet area and "approximate location" for houses saved from a listing before a visit | FR-059, FR-068 | 4b |
| G-02 | Add a house from a listing link or shared listing, with details filled in (*Add a shared listing*; not an "import", [12](12-brand-and-naming.md) G.3) | `listing_url` field; AI fills a draft from pasted text (FR-038, AI-004) | Partial | **No server-side scraping** of MagicBricks, 99acres, NoBroker or Housing.com. Android **Share to Doorprints**, PWA **share target**, paste box; on-device **no-AI parser**; optional "Improve with AI" (on-device where supported, cloud for invited users, D-21) | FR-066..FR-069 | 4b |
| G-03 | Custom weighted scoring | Fixed 10-item checklist, equal weights (FR-004, FR-005) | Partial | User criteria + weights + must-haves; default weights give exactly today's score | FR-051..FR-053 | 4b |
| G-04 | Automatic shortlist ranking | Sort by score | Partial | Ranking view (must-haves, weighted score, coverage, price) | FR-054 | 4b |
| G-05 | Side-by-side comparison | Compare 2–4 houses (FR-011) | Have | Add weighted score, must-haves, rooms, answers | FR-055 | 4b |
| G-06 | Searchable viewing history | Visits recorded (FR-008), list search on houses only | Partial | Viewings timeline with search and filters | FR-065 | 4b |
| G-07 | Photo capture with tagging | Photos (FR-007), no tags | Partial | Room link, tags, caption | FR-060 | 4b |
| G-08 | Notes | House notes | Have | Notes per visit, room, answer | FR-061 | 4b |
| G-09 | Custom viewing question checklists | – | Missing | Question bank with India defaults, per-house answers | FR-056, FR-057 | 4b |
| G-10 | Viewing reminders, second viewings | – | Missing | Planned viewings, **local** reminders (Android alarms, PWA notifications), `.ics`, second viewing with re-check list | FR-062..FR-064 | 4b |
| G-11 | Room sizes and condition | – | Missing | Rooms with size (ft/m), condition 1–5, notes | FR-058, FR-059 | 4b |
| G-12 | Accounts (email/password, no postal address) | Shared API key (ADR-03) | Missing → **Delegated** | **No own accounts.** Optional Google Sign-In creates a Doorprints account keyed by the Google subject; we store no password. Without sign-in everything works locally. | FR-074, FR-075 | 5 |
| G-13 | Google sign-in | – | Missing | Credential Manager (Android), Google Identity Services (web), server verifies the ID token | FR-074, SEC-032 | 5 |
| G-14 | Two-factor authentication (TOTP) | – | Missing → **Delegated** | Google 2-Step Verification (authenticator app, passkeys, security keys, prompts) protects the account (3.1) | – | 5 |
| G-15 | Argon2id password hashing | – | Missing → **Delegated** | No passwords stored by Doorprints; Google stores and protects credentials (3.1) | – | 5 |
| G-16 | Encrypted photo storage at rest | Provider disk encryption, Android FBE | Partial | Device: FBE / browser storage; Drive: Google encryption at rest; server thumbnails: app-level AES-256-GCM (5.15) | SEC-040 | 5 |
| G-17 | Trusted device management | – (C-04 open) | Missing | Signed-in devices list (name, platform, last seen), revoke one, sign out all others; replaces C-04 | FR-076, SEC-034 | 5 |
| G-18 | Mobile-first, installable PWA, no app install | Responsive, online-only web app | Partial | `@angular/pwa` + **local-first web** (IndexedDB), works fully without sign-in | FR-070..FR-073 | 4a |
| G-19 | Completely free | Zero running cost | Have | On-device first; Google Drive uses the user's own free 15 GB; AI on-device for guests; cloud AI only for invited users on the owner's hard-capped paid key (D-21) | – | – |
| G-20 | Data export and account deletion | JSON `GET /api/export`, `DELETE /api/data` | Partial | Goal B exporters; deletion with 7-day grace | FR-042..FR-050, FR-080 | 4a, 5 |
| G-21 | Login rate limiting | Per-address limits (SEC-008) | Partial | Rate limits on sign-in and on cloud AI per invited user; Google protects the credential step | SEC-036 | 5 |
| G-22 | **Goal B: offline copy** (beyond SeenHouse) | JSON export only | Partial | Six deterministic formats + AI custom export (5.2, 5.3) | FR-042..FR-050 | 4a, 5 |

Doorprints keeps its lead where SeenHouse has nothing: Hunt mode (FR-013..FR-018), a fully offline Android app,
map, AI assistant, four Indian languages, and now **no account needed at all**.

### 3.1 Security parity by delegation to Google

SeenHouse runs its own accounts, so it must hash passwords and offer 2FA. Doorprints never handles passwords: the
Google account is the only credential, and Doorprints issues its own revocable device sessions after Google has
authenticated the user.

| SeenHouse control | Doorprints equivalent | Who provides it | Note |
|---|---|---|---|
| Email + password sign-up, no postal address | Google Sign-In; we keep the Google subject ID, email and (optional) name only | Google | Nothing to sign up for when used locally |
| Argon2id password hashing | No password is stored or seen by Doorprints | Google | Removes the whole password-database risk (breach, credential stuffing against us) |
| TOTP two-factor authentication | Google 2-Step Verification: authenticator app (TOTP), passkeys, security keys, Google prompts | Google | We cannot see or enforce it; the Account screen recommends turning it on with a link to Google's security settings |
| Password reset by email | Google account recovery | Google | Fits D-03 (no transactional email) |
| Brute-force protection on login | Google's risk checks on sign-in; our rate limits on `/api/auth/google` (SEC-036) | Google + Doorprints | |
| Trusted device management | Doorprints device sessions: list, rename, revoke, sign out others (5.11) and Google's own device list | **Doorprints** + Google | Revoking a Doorprints session stops sync and Drive use from that device within 60 s |
| Encrypted photos at rest | FBE on Android, Drive encryption at rest, AES-256-GCM for server thumbnails | Device / Google / **Doorprints** | 5.15 |

Residual: Doorprints account security equals the user's Google account security, and a stolen Google session can
sign in to Doorprints (T-S9). Users without a Google account can still use every local feature; they only miss sync,
the web app with synced data and Drive storage (cloud AI also needs an owner invitation, D-21).

## 4. Principles

| # | Principle |
|---|---|
| P-1 | **Zero cost for users.** On-device first. Google Drive storage is the user's own free quota. No email service, no push service, no scraping. The only paid API is cloud AI for the owner and invited users, on the owner's key with a hard cap (D-21); users never pay and never bring a key (D-22). |
| P-2 | **Local-first everywhere.** Android (Room) and the PWA (IndexedDB) hold the data; every non-AI feature works offline and without an account (NFR-024). The server is an optional sync hub for signed-in users. |
| P-3 | **Sign-in only where an account is needed.** Prompts appear at sync, the web app with synced data and Drive. AI is never a sign-in incentive (D-21). Never item limits, never blocking a local feature, never nagging (5.13). |
| P-4 | **Backward compatible sync.** A missing (null) collection in a PUT means "unchanged", an empty array means "clear" (NFR-025). |
| P-5 | **Same formula everywhere.** Scoring, ranking, the no-AI parser and the exporters share test-vector files run by the Android and web tests. |
| P-6 | **Your data, your copy.** Exports are deterministic, offline and readable without Doorprints. AI output is never the backup. |
| P-7 | **Least data on our server.** Signed-in users sync houses, visits and metadata; full photos stay on the device or in the user's Drive; the server holds thumbnails and metadata only. |

## 5. Feature designs

### 5.1 Local-first with optional Google Sign-In

| | **Guest (default)** | **Signed in with Google** |
|---|---|---|
| Where data lives | Android: Room + `filesDir/photos`. PWA: IndexedDB (records and photo blobs) | Same local stores, **plus** sync to the Doorprints server (per Google user) |
| All house, scoring, question, room, viewing, reminder, map, Hunt-mode features | Yes | Yes |
| Deterministic exports and import | Yes (on the device) | Yes |
| Sync between phone, PWA and laptop | – | Yes |
| Web app showing the phone's data | – (the PWA has its own local data) | Yes |
| Photos and backups in Google Drive | – | Optional (separate Drive permission, 5.12) |
| On-device AI (Improve with AI, AI custom export) | Android with Gemini Nano support only; otherwise hidden (5.13) | Same |
| Cloud AI (Ask, planner, listing extraction, AI custom export) | – | Only if the owner invited this Google account (5.13) |
| Self-hosted server with API key (today's mode) | Kept until Sprint 6 for the owner's existing install (5.11 migration) | – |

Where the server is: the app ships with the **Doorprints hosted server URL** as default (free tier, operated by the
owner). Advanced settings keep "Use my own server" for self-hosters, who configure their own Google OAuth client.

### 5.2 Export: deterministic formats and re-import (Goal B)

Every format below is built **on the device** (Android from Room, PWA from IndexedDB), **offline**, and gives the
same output for the same data and options (fixed ordering by `createdAt` then `id`, no timestamps inside except the
export time in the cover/manifest).

| Format | File | Content | Android builder | Web/PWA builder |
|---|---|---|---|---|
| **HTML** (readable copy) | `Doorprints-<date>.html` | Self-contained: inline CSS, **no JavaScript**, photos as `data:` URIs (1024 px, JPEG q70), CSP `<meta>` `default-src 'none'; img-src data:; style-src 'unsafe-inline'`. Cover (date, counts, options, privacy note), ranking table, one section per house: details, weighted score breakdown, checklist, rooms, questions and answers, visits and viewings, notes, tagged photos, contact (optional). `@media print`: one house per page | Kotlin template with HTML escaping | TypeScript template, same structure |
| **PDF** | `Doorprints-<date>.pdf` | Same content and order as HTML, A4, one house per page, photos smaller | `android.graphics.pdf.PdfDocument` + `StaticLayout` (platform text shaping handles Devanagari, Tamil, Telugu; no library) | Print view of the HTML with `window.print()` → "Save as PDF" (browser shaping; JS PDF libraries do not shape Indic scripts well) |
| **CSV** | `Doorprints-<date>-csv.zip` | `houses.csv`, `scores.csv`, `rooms.csv`, `answers.csv`, `visits.csv`, `viewings.csv`, `photos.csv`; UTF-8 with BOM, RFC 4180, formula-injection guard | Own writer | Own writer + `fflate` (MIT) for the ZIP |
| **XLSX** | `Doorprints-<date>.xlsx` | One sheet per CSV table, header row frozen, ₹ and dates as typed cells, formula guard | Small own SpreadsheetML writer (XLSX is zipped XML; Apache POI is too large for the APK) | Same own writer in TS + `fflate` (avoid SheetJS: its current releases are not on npm) |
| **Markdown** | `Doorprints-<date>.md` | Headings per house, tables for scores/rooms/answers, photo list by file name (no images embedded); escapes Markdown control characters | Own writer | Own writer |
| **JSON full backup** | `Doorprints-backup-<date>.zip` | `manifest.json` (format `doorprints-backup/1`, versions, options, counts, SHA-256 per file), `data.json` (exact: preferences, criteria, questions, houses with checklist/rooms/answers, visits, viewings, photo metadata incl. Drive IDs), `photos/<id>.jpg` (local photos; Drive-only photos are downloaded if Drive is connected, else listed as `driveOnly`), plus the HTML copy | Streaming `ZipOutputStream` | `fflate` streaming in a Web Worker |

**Options:** scope (all · shortlisted · selected houses), photos (all · shortlisted only · none), contact details
(include, the default, with the warning "This copy contains phone numbers of owners and brokers…" · leave out),
language (en/hi/ta/te), include rejected houses. Tombstones are never exported. **Visits that belong to no house** (Hunt mode records a dwell at a place that is not a house yet with no `houseId`) are carried **only in the JSON backup**, only for scope *All houses*, after the grouped visits in `data.json` (docs/schemas/README.md §5 order); the readable copies and the tables leave them out. Android does this since `android/shared/README.md` 1.9 (`ExportBundle.unlinkedVisits`, `BackupTest.visitsWithoutAHouseAreInTheBackupAndComeLast`); **the web writer still drops them** (`export-model.ts`), so a web backup restored elsewhere loses them — [10](10-sprint-log.md) §11.3 item 11.

**Save:** Android: Storage Access Framework (`CreateDocument`: Downloads, Google Drive app, SD card), share sheet
(`FileProvider`, `cache/exports/`, deleted after 24 h), or, when connected, **straight to the Doorprints folder in
Google Drive** (5.12). Never app-specific storage, so the file survives uninstall. PWA: download (`<a download>`,
`showSaveFilePicker` on Chromium for large files) or Web Share API with files.

**Automatic backups (optional, off by default):** weekly JSON backup, only while charging (Android), to a folder
picked once (`OpenDocumentTree`) or to Google Drive `Doorprints/Backups`, keeping the last 4.

**Import (exact round trip):** pick a `doorprints-backup` ZIP → validate (format, version, SHA-256, ≤ 5 000 entries,
≤ 1 GB uncompressed, ratio ≤ 100:1, no `..`/absolute paths, DTO validation) → preview ("*a* new, *b* newer in file,
*c* newer here") → merge by UUID with last-write-wins, or "import as a copy" with new IDs → imported rows are local
and dirty, so they sync if the user is signed in. A device import writes to Room / IndexedDB and needs no server.
*As built (Sprint 4a):* the server also has a restore endpoint, `POST /api/import` with `?dryRun=true` for the
preview ([03](03-design.md) §9, ADR-20), and Android also accepts a **bare `data.json`** — what the server's
`GET /api/export` downloads (`Doorprints-backup-<UTC date>.json`) — whose photo rows are reported as missing from
the file; the web app has no import yet ([10](10-sprint-log.md) §11.3).
Only the JSON backup is importable; HTML, PDF, CSV, XLSX, Markdown and AI output are not.

```mermaid
sequenceDiagram
    actor U as User
    participant S as Export screen
    participant E as Exporter
    participant L as Local store Room or IndexedDB
    participant T as Target SAF, download or Drive
    U->>S: Choose format, scope, photos, contacts, language
    S->>E: build with options
    E->>L: read records and photos in fixed order
    loop each house
        E->>E: render section, escape text, resize photos
    end
    E->>E: write manifest with SHA-256 for backups
    E->>T: stream file
    T-->>S: saved
    S-->>U: Saved. Open or Share
```

**Built later (2026-10-01, [10](10-sprint-log.md) §13.31, §13.32, §13.34).** The Kotlin copies now write their times
in UTC, as the website's always did (the cover says "Times shown for UTC +00:00"; the writer still takes any offset,
and its goldens keep +05:30 to prove it), so one backup reads the same on every device (S4b-BL-92c). The iPhone has
*Save a copy* (every copy but the PDF) through the share sheet and Files and *Import a backup* from Files or from
another app, on common code (`Zip.kt`, `BackupArchive.kt`, `CopyWriter.kt`, `ArchiveImports.kt`; S4b-BL-81, compiled
only). The website has *Import a backup* (S4b-BL-75): a Doorprints ZIP or the server's `.json`, the checks of
docs/schemas §6 over the shared `import-vectors.json`, the preview, *Merge*, *Add everything as new copies*, *Keep
mine*, and undo while the page is open. Installed on a computer (Chrome or Edge), the website also opens a
Doorprints `.zip` double-clicked in the file manager straight in *Import a backup* (`file_handlers`, S4b-BL-108). An import keeps to 100 questions and 40 criteria; the undo of a copy import
removes the houses, visits, photos, brokers, viewings, questions and criteria it created (not preferences, areas,
places or notes).

### 5.3 AI custom export

| Item | Design |
|---|---|
| What | The user describes the output in their own words ("a WhatsApp-ready summary of my shortlist in Tamil for my parents", "a letter comparing my top 2 houses for my landlord", "a table of rents and deposits"). |
| Input to the model | **Only a structured export of the data the user selected** (the same JSON as the backup, trimmed to chosen houses and fields, max 30 houses / 64 KB), built on the device. Contact names, phones and `withWhom` are **removed unless the user ticks "Include contact details"**; the server's `ContactRedactor` runs on free text regardless of the tick for fields other than the contact fields. No photos. The instruction is capped at 500 characters. |
| Endpoint | `POST /api/ai/custom-export {instruction, language, style: message/letter/table/summary, data}`. No tools, no retrieval; the data is delimited as untrusted (AI-008). Cloud path for invited users only; guests use the on-device path (5.13). |
| Number check | The server extracts every number from the output (₹ amounts, `k`/lakh/crore, sq ft, BHK, dates, counts, ranks, scores) and normalises it; each must match a value in the input data or a simple derivation listed in the prompt (rank position, count, difference of two prices). The prompt asks for digits, not number words. Mismatches: one automatic retry; if still wrong, the output is shown with the unverified numbers highlighted and "Check these numbers before sending", and the Copy/Share buttons ask for confirmation. |
| Contact check | Without the opt-in, any phone-number pattern or input contact name in the output is removed and noted. |
| Labelling | Always shown under the heading "AI-generated from your Doorprints data, <date>. Check before sharing." (translated); the saved or shared text keeps a one-line footer "Written with AI from Doorprints data" that the user can see and edit. |
| Output use | Copy, share (Android share sheet / Web Share API, e.g. WhatsApp), save as `.txt` or `.md`. **Never** saved as a backup, never importable, not stored on the server (only usage counters). |
| Offline | Not available offline (AI needs the network); the deterministic formats are offered instead. |
| Evals | New golden cases: number fidelity, Tamil/Hindi/Telugu output, contact exclusion, injection text inside notes (AI-012). |
### 5.4 Custom weighted criteria and ranking

| Item | Design |
|---|---|
| Criterion | `key` (built-in: `water`..`commute`, unchanged; custom: `c_<8 hex>`), `label` (null for built-ins, which stay translated; user text for custom), `weight` 0 Ignore · 1 Low · 2 Medium · 3 High, `mustHave` (bool), `minScore` (1–5, default 3), `sort`, `archived`. At most 40 criteria (built-in included). |
| Scores | Still `house_checklist(item, score 0–5)`; `item` = criterion key. The API already accepts any key ≤ 100 chars (`HouseDto.checklist`), so **no change to existing rows**. |
| Weighted checklist | `wc = Σ(wᵢ·sᵢ) / Σ(wᵢ)` over criteria with a score and `wᵢ > 0`; null when none. |
| Overall score | `overall = (1 − r)·wc + r·rating` when both exist, else whichever exists; `r` = preference `score.ratingShare`, default **0.5**. |
| Compatibility | Migration seeds the 10 built-ins with weight 2 and `mustHave = false`: with equal weights and `r = 0.5` the result is **identical to FR-005**. Test vectors check this (TC-U-22). |
| Coverage | `Σ wᵢ (scored) / Σ wᵢ (all > 0)`, shown as "scored 7 of 10 that matter". |
| Must-have | A house fails when any must-have criterion has a score below `minScore`. An unscored must-have is "not checked yet", not a failure. |
| Ranking | Scope: SHORTLISTED (toggle: + NEW). Order: (1) no failed must-have first, (2) `overall` desc (null last), (3) coverage desc, (4) price asc, (5) `updatedAt` desc. Computed on the client (Room/web); no server endpoint needed. |
| Old clients | Android stores the checklist as a JSON map and web as a `Record`, so custom keys round-trip; to verify in TC-I-23 before release. |

**Built (2026-09-30, slice 2 of 5.30; [10](10-sprint-log.md) §13.22).** Criteria are records of type `criterion` (id = the key) and the rating share a
record of type `preference`, so there is no new table or migration; a built-in with no record uses the defaults, and only what
differs is stored. One `HouseScore`/`scoring.ts` implementation per stack over eight shared vectors; every caller of the old
two-argument score now passes the effective scoring. Archived criteria and weight-0 criteria (*Ignore*) do not count, an unscored
must-have is "not checked yet", and a score under a key that is not a known criterion is ignored. The house list's *best first*, Compare
and the readable copies rank by the five-step order above; a house that misses a must-have shows a chip and sorts after the rest. Settings >
Criteria (a card on Your data on the web) sets weights, must-haves, order, archiving, custom criteria and the rating share, and resets to the
defaults. Backup: `criteria` and `preferences` lists (docs/schemas §3.6). The objective criteria from data (D-13: budget fit, BHK match) are later.

### 5.5 Viewing question checklists

| Item | Design |
|---|---|
| Question bank | `question`: `text` (≤ 300), `category` (Money, Water & power, Rules, Building, Legal (buy), Other), `appliesTo` RENT/SALE/BOTH, `defaultOn`, `sort`, `archived`. Seeded on first run from translated defaults in the user's language (the text is then user data and is not re-translated). |
| India defaults (examples) | Maintenance per month and what it covers · Deposit (months) and refund terms · Lock-in and notice period · Water source (corporation, borewell, tanker) and hours · Power backup (full/lift only) · Pets / bachelors / non-veg rules · Brokerage · Parking slot allotted? · Which floor, lift? · Buy: OC and CC received? RERA registration number? Khata / property tax paid? |
| Per house | `answers[]` embedded in `HouseDto`: `{id, questionId?, text (snapshot), answer (≤ 2 000), status OPEN/ANSWERED/SKIPPED, sort}`. The snapshot keeps the copy readable if the bank question is deleted. Ad-hoc questions for one house are allowed (`questionId` null). |
| At a viewing | "Questions to ask" card on the house screen and in the viewing reminder; open questions first; large touch targets for one-handed use. |

**Built (2026-09-30, slice 3a of 5.30; [10](10-sprint-log.md) §13.23).** The bank is a `question` record per question (fixed ids `qd_…` for the fourteen
seeded defaults in `docs/schemas/default-questions.json`, so two devices seed the same records; `q_` plus eight hex for your own) and the answers are
nested in the house (`answers`, at most 60, Room 8 and Flyway V10). Seeding writes the texts once in the app's language and never brings back a
deleted default; *Reset to defaults* does. *Add the usual questions* asks the `defaultOn`, non-archived questions that fit the house (rent, buy or both)
that it does not have yet, and pre-fills the deposit, maintenance, brokerage and lock-in answers from the house's cost (5.21). The form's *Questions
to ask*, *Settings > Questions* (a card on Your data on the web), the readable copies (a Questions table on each house page, `answers.csv`, an Answers
sheet), the AI documents (answered and still-open questions, contact details redacted) and search over answers are built. The reminder card in a
viewing reminder waits for 3b.

### 5.6 Rooms with sizes and condition

`rooms[]` embedded in `HouseDto`: `{id, type (BEDROOM, HALL, KITCHEN, BATHROOM, BALCONY, POOJA, STUDY, UTILITY, STORE, OTHER), name (≤ 60), lengthCm, widthCm (0–5 000), condition 1–5 (null = not checked), notes (≤ 2 000), sort}`.
Display in feet (default, India) or metres (preference `units.length`); area in sq ft or m². At most 30 rooms per house.
House gets `areaSqft` (carpet area as the user writes it). Sizes are stored in centimetres so the unit choice never loses precision.

**Built (2026-09-30, slice 1c of 5.30; [10](10-sprint-log.md) §13.21):** the fields as designed, `rooms` after `cost` on the house
in all three stacks (Room 7, Flyway V9), at most 30, ordered by `sort`; the length unit (*Feet* or *Metres*) is a local
setting, not synced and not in a backup; sizes show as feet and inches or metres with one decimal, and a room's area in sq ft
or m². The form's **Rooms** section adds, edits and deletes rooms (no reordering yet, S4b-BL-87), Compare shows the count and
the total area, the copies gain a `rooms` column, `rooms.csv`, a Rooms sheet and a Rooms table on each house page, the AI
documents carry names, sizes and condition (never a room's notes), and search covers room names and notes. The house's
*floor*, which the duplicate-flat warning needs, is not a field yet (S4b-BL-85).

**Built later (2026-10-01, S4b-BL-87, [10](10-sprint-log.md) §13.33):** *Move up* and *Move down* on each room (its
`sort`), and the house's `floor`, a whole number -5..200 (0 the ground floor, below 0 a basement), after `moveIn` in
every writer, a `/2` field (Room 10, Flyway V13). The form refuses a floor out of range with a message and focus on
both apps (the website silently dropped it until Wave D). Search finds it as "floor 3", "ground floor" or "basement 2",
and in the app's language too ("भूतल", "बेसमेंट 2"; S4b-BL-104 b). A **Basement** switch under Floor (S4b-BL-104 c) holds
the sign, so a basement level 1..5 is typed without a minus (some number keypads have none); a minus typed anyway turns
the switch on. **Import** (S4b-BL-104 d, decided 2026-10-01): a device reads a floor outside -5..200 as unknown and imports
the house with the floor blank, and the preview says so as a warning ("Houses whose floor in the file is outside -5 to
200; their floor is left blank"); the server refuses such a file (`houses[i].floor must be -5..200`). The device stays
tolerant because it reads an area out of range the same way, and a refusal there would say only that the file is
damaged, not which value; no app writes such a floor, so only an edited file has one.

### 5.7 Photo tags

Photo metadata: `roomId` (a room of the same house or null), `tags` (≤ 10; fixed keys `EXTERIOR, ENTRANCE, KITCHEN_FITTINGS, BATHROOM_FITTINGS, DAMP, CRACK, LEAK, VIEW, WATER_TANK, METER, PARKING, LIFT, GOOD_POINT, PROBLEM` translated in the UI, plus custom tags ≤ 30 chars), `caption` (≤ 200).
Edited with `PUT /api/photos/{id}/meta` (LWW on `metaUpdatedAt`, new `sync_version`), carried in the photo change feed.
A `roomId` that no longer exists is shown as "untagged" (no foreign key, so photos may sync before the house).

**Built (2026-10-01, slice 5 of 5.30; [10](10-sprint-log.md) §13.29).** As the design of slice 5 (5.24) says: `roomId`,
`tags` (the fixed keys above plus `MOVE_IN`, or the person's own, at most 10, each at most 30 characters, a custom tag
never equal to a fixed key), `caption` (at most 200) and `metaUpdatedAt` on the photo, on the three stacks (Room 9,
Flyway V12, the website's photo store without a version change); *Room, tags and caption* on each photo of a house;
`PUT /api/photos/{id}/meta` and the fields in `PhotoDto`, so both syncs carry them; the backup's photo rows write the
four keys only when set. Not built: search over captions and tags (neither list shows photos), the tags' translated
words in the copies (they write the keys) (S4b-BL-99).

### 5.8 Viewings: schedule, local reminders, second viewing, history

| Item | Design |
|---|---|
| Planned viewing | Entity `viewing`: `houseId`, `startsAt`, `durationMin` (30), `kind` FIRST/SECOND/FOLLOW_UP, `status` PLANNED/DONE/CANCELLED/MISSED, `remindMin` (60; 0 = none), `withWhom` (contact data), `notes`, `visitId`. |
| Link to visits | A visit at the house within ±2 h of a planned viewing offers "Mark viewing done". Visits get `notes` and `viewingId`. |
| Reminders, Android | Local only (D-03): the same scheduler as 5.16 (exact `setExactAndAllowWhileIdle` when "Alarms & reminders" is allowed, otherwise an early-starting `setWindow(target − 10 min, 10 min)` so the reminder comes early, never late), WorkManager as fallback; rescheduled on boot, time and time-zone change; `VISIBILITY_PRIVATE` with a redacted public version; actions Open house, Directions (Maps intent on tap), Questions. Offline, no server. |
| Reminders, PWA | `Notification` / `ServiceWorkerRegistration.showNotification` scheduled by the app while it is open or recently used; **browsers cannot wake a closed PWA without a push service**, which D-03/D-07 exclude. So the PWA also shows an "Upcoming" list, offers an `.ics` file per viewing (the phone's calendar then reminds reliably), and says so in the reminder settings. iOS: notifications only for an installed PWA (iOS 16.4+). |
| Calendar | Android `ACTION_INSERT` into `CalendarContract` (no permission); PWA `.ics`. |
| Second viewing | After DONE: "Book a second viewing?" with a re-check list (open questions, criteria scored ≤ 2, photos tagged PROBLEM). |
| History | "Viewings" screen: timeline of visits and viewings; search (house label, street, locality, visit notes, answers); filters (date, kind, status). |

**Design of 2026-09-30 (slice 3b of 5.30; [10](10-sprint-log.md) §13.24).** Decisions taken before building:

| Item | Decision |
|---|---|
| Storage | A `viewing` is a record (ADR-28: type `viewing`, `id` `v_` plus eight hex, the app's own key). Payload keys in this order: `houseId` (required; may dangle, shown as "a house that is gone"), `startsAt` (epoch ms), `durationMin` (5..480, default 30), `kind` (`FIRST`, `SECOND`, `FOLLOW_UP`), `status` (`PLANNED`, `DONE`, `CANCELLED`), `remindMin` (0, 15, 30, 60, 120, 1440; default 60; 0 is none), `huntReminder` (boolean, default false; used by 3c), `withWhom` (0..200, contact data), `notes` (0..2000), `visitId` (optional, the visit that made it DONE). At most 5,000 live viewings (the record cap); a house may have any number. |
| MISSED | Not stored. A PLANNED viewing that ended more than 2 hours ago shows as "Missed?" with the buttons *It happened* (DONE) and *Cancel*; nothing writes it by itself, so two devices never fight over it. The enum in the file is `PLANNED`, `DONE`, `CANCELLED`; an unknown status reads as PLANNED. |
| Link to visits | A visit at the house within 2 hours of a PLANNED viewing offers *Mark viewing done* (sets `status` DONE and `visitId`). `Visit.viewingId` is **not** added: the link is one way, from the viewing, so visits stay as they are and no visit migration is needed. |
| Second viewing | After DONE: *Book a second viewing?* opens the form with `kind` SECOND and the house; the re-check list is the house's open questions, its criteria scored 2 or less, and (when 5.7 exists) its PROBLEM photos. |
| Screens | *Viewings* (Settings on Android, a page on the website; also a card on the house screen: next viewing, *Plan a viewing*): the timeline of planned, done and cancelled viewings with filters (date range, kind, status) and search over house label, street, locality, `withWhom`, `notes`. The form: house, date and time (the phone's locale, 24 h or 12 h), duration, kind, reminder, Hunt reminder switch (hidden until 3c), with whom, notes. |
| Calendar | Android `ACTION_INSERT` on `CalendarContract.Events` (no permission); the website and the iPhone share a `.ics` file per viewing (one `VEVENT`, `UID` = `<id>@doorprints`, `DTSTAMP`, `DTSTART`, `DTEND`, `SUMMARY` "Viewing: <house>", `LOCATION` the address when known, `DESCRIPTION` the notes without `withWhom`, one `VALARM` `-PT<remindMin>M` when `remindMin` > 0; UTC times, CRLF lines, lines folded at 75 octets, text escaped). |
| Reminders | **3b-2.** Pure rules in `:shared` and the website: `ViewingReminders.nextAt(startsAt, remindMin) = startsAt - remindMin minutes`; a viewing that is not PLANNED, or whose time has passed, has none; the reminders to schedule are the next 60 by time (iOS keeps 64 pending notifications). Android: `AlarmManager` as in 5.16 (exact when allowed, otherwise the early window), rescheduled on boot, time and time-zone change, app update and every edit; actions *Open house*, *Directions*, *Questions*; private lock-screen version. iPhone: `UNUserNotificationCenter` calendar triggers, rescheduled on every start and edit (needs the notification permission, asked at the first reminder, never at start-up). Website: the *Upcoming* list, the `.ics` and a notification while the app is open. The words of the reminder never carry `withWhom`. |
| Backup and copies | A `viewings` list after `questions` in `doorprints-backup/2` (a copy with a viewing is `/2`); `withWhom` is blanked in a copy made without contact details, the rest is kept. Copies: a Viewings table on each house page, `viewings.csv`, a Viewings sheet; the AI documents carry "Viewing: date, kind, status, notes" (no `withWhom`, contact details redacted). |
| Server | Nothing new: `viewing` is a record type of `/api/records`, plus validation in the backup import (same style as `question`). |
| Slices | **3b-1** the record, screens, history, calendar file and insert, backup, copies, AI, search, server. **3b-2** the reminders on the three platforms and the Settings words. **3c** the Hunt reminder (5.16). |
| Vectors | V1 `nextAt` for 60 min before 10:00 is 09:00; V2 `remindMin` 0 gives none; V3 a CANCELLED or DONE viewing gives none; V4 a past viewing gives none; V5 the pending list keeps the earliest 60 of 70; V6 the `.ics` of a fixed viewing equals `docs/schemas/viewing-sample.ics` byte for byte. |

**Built (2026-09-30, slice 3b-1 of 5.30; [10](10-sprint-log.md) §13.24).** The `viewing` record (`docs/schemas/README.md` §3.10), the *Viewings* history (Settings on Android, `/viewings` on the website) with
the filters, search and the *Missed?* buttons, the form (plan, edit, cancel, delete, *Add to calendar*: Android's calendar insert, the website's `.ics` download), the house card (next viewing, *Plan a viewing*, *Mark
viewing done* when a visit is within two hours, the second-viewing dialog with its re-check list), the `viewings` list in `doorprints-backup/2` (`withWhom` blanked without contact details), a Viewings section in the readable
copies plus `viewings.csv` and a sheet, the AI lines and the server (a `viewing` record type with its import validation). **Not built:** any reminder (3b-2), the iPhone's `.ics` share, a backup importer on the website.

**Design of slice 3b-2, the reminders (2026-09-30; [10](10-sprint-log.md) §13.25).** Builds on the 5.16 scheduling text; 3c adds the Hunt action later.

| Item | Decision |
|---|---|
| The rule (shared) | `ViewingReminders` (`:shared` commonMain, TypeScript twin): a viewing has a reminder at `fireAt = startsAt - remindMin minutes` when it is PLANNED, `remindMin` > 0 and `fireAt` is after now. `upcoming(viewings, nowMs, limit = 60)` returns the (viewing, `fireAt`) pairs, earliest first, ties by id. A reminder whose time has passed is not sent (nothing fires late or at once when a viewing is planned close to its start). Vectors R1 60 min before 10:00 is 09:00; R2 `remindMin` 0 gives none; R3 DONE or CANCELLED gives none; R4 a `fireAt` at or before now gives none; R5 70 viewings give the earliest 60. |
| Setting | Local setting `viewings.remind` (default on, not synced): *Remind me about viewings*, in Settings > Viewings on the phones and on the Viewings page of the website (there it means *Notify me while Doorprints is open* and needs a tap to ask the browser). Off cancels every scheduled reminder. |
| Android | `:app` scheduler as in 5.16: `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()`, otherwise `setWindow(fireAt - 10 min, 10 min)` (early, never late), WorkManager one-time work when an alarm cannot be set; one `PendingIntent` per viewing (request code from its id, immutable); `SCHEDULE_EXACT_ALARM` and `RECEIVE_BOOT_COMPLETED` declared. Everything is rescheduled from the stored viewings (cancel all known ids, tombstones included, set the upcoming 60) on app start, on every change of the viewings, on boot, time and time-zone change, app update and the exact-alarm permission change. Notification channel `viewings` (default importance, private on the lock screen with the public text "Doorprints reminder"); the text is worked out when it is shown ("Viewing at <house> in 25 min"); actions *Open house*, *Directions* (a `geo:` intent, only when the house has a real position) and *Questions* (opens the house at its questions); never `withWhom`. POST_NOTIFICATIONS (Android 13+) is asked when the first reminder is saved, never at start-up. Settings shows the 5.16 note and the *Allow on-time reminders* button while exact alarms are not allowed. |
| iPhone | `UNUserNotificationCenter`: on start, on resume and after each change remove the pending requests whose identifier starts with `viewing-` and add the upcoming 60 (iOS keeps 64) with calendar triggers at `fireAt`; the body "Viewing at <house> at 10:00", `hiddenPreviewsBodyPlaceholder` "Doorprints reminder"; a tap opens the house through the existing tap handler; the authorization is asked when the first reminder is saved. |
| Website | While the app is open (a timer re-checks every minute, nothing long-lived): when permission is granted the reminder is a browser notification through the service worker, otherwise an in-page banner; the text carries the time only ("A viewing at 10:00. Open Doorprints for the place") because a browser notification has no private version. The Viewings page says: "Reminders on the website only work while it is open. Add to calendar for a reminder that always arrives." |
| Not here | The Hunt action (3c); a server push (excluded by D-03). |

**Built (2026-09-30, slice 3b-2 of 5.30; [10](10-sprint-log.md) §13.25).** `ViewingReminders` (R1..R6) on both stacks; the local setting `viewings.remind`; on Android the scheduler, receivers, channel `viewings`
(notification id 9, tag `viewing:<id>`), the exact-alarm note and button, and the notification permission asked when the first reminder is saved; on the iPhone `IosViewingReminders` (compiled, not yet run on a device); on the website the
while-open timer, banner and browser notification with a click handler in the service worker. **As built, differing from or adding to the design:** the title says "in N min" only up to 90 minutes ahead, further ahead it says "Viewing at X" and
"Starts <date time>"; the alarm checks the stored viewing again when it fires (still PLANNED, not started, at most about 11 minutes early) so an alarm left behind by a deletion shows nothing; the *Questions* action opens the house but
does not scroll to its questions; the exact-alarm note shows only while reminders are on; the permission question shares the single "asked" flag with Export and Import.

**Design of slice 3c, the Hunt mode reminder (2026-09-30; [10](10-sprint-log.md) §13.26).** Builds on 5.16 and on the 3b-2 reminders.

| Item | Decision |
|---|---|
| Field and setting | The viewing's `huntReminder` (already in the record and the backup) gets its switch in the viewing form on the phones (*Offer Hunt mode before this viewing*, off by default, hidden on the website, which has no Hunt mode). The lead time is the local, unsynced setting `hunt.reminderMin` (5, 10, 15, 20, 30, 45 or 60; default 15) and the global switch *Offer Hunt mode before viewings* (`hunt.remind`, default on), both in Settings > Hunt mode. |
| The rule (shared) | `HuntReminders.fireAt(viewing, leadMin) = startsAt - leadMin minutes` for a PLANNED viewing with `huntReminder` true; `upcoming(viewings, leadMin, nowMs, limit)` as `ViewingReminders.upcoming` (after now, earliest first, ties by id). `merged(viewings, leadMin, nowMs)` joins a viewing reminder and a Hunt reminder of the same viewing when their times are within 10 minutes: one notification at the earlier time carrying both actions. Vectors H1 15 min before 10:00 is 09:45; H2 `huntReminder` false gives none; H3 DONE/CANCELLED gives none; H4 a past time gives none; H5 the viewing reminder at 09:00 and the Hunt reminder at 09:45 stay two; H6 at 09:50 and 09:45 they merge into one at 09:45; H7 the cap of 60 over both kinds. |
| Android | The 3b-2 scheduler also schedules the Hunt reminders (the same exact or early-window rules, the same reschedule triggers and WorkManager fallback); channel `hunt_reminders` (default importance, no full-screen intent, no Do Not Disturb bypass, private on the lock screen with the public text "Doorprints reminder"); the text is worked out when shown ("Viewing at <house> in 15 min. Start Hunt mode?"); actions **Start Hunt mode** (an immutable `PendingIntent` that starts `HuntService` when foreground location is granted, otherwise opens the app at the location question of 5.18) and **Dismiss**; tapping the body opens the viewing. A merged notification also carries *Open house*. Hunt mode already on: the action is left out. |
| iPhone | A `viewing-hunt-<id>` calendar notification with the same text; a tap opens the app (an iPhone app cannot start location tracking from a notification action), and if Hunt mode is off the Map offers it. |
| Website | Nothing (no Hunt mode there). |

**Built (2026-09-30, slice 3c of 5.30; [10](10-sprint-log.md) §13.26).** `HuntReminders` with H1..H7, the local settings `hunt.remind` and `hunt.reminderMin`, the form switch (phones only), Settings > Hunt mode, the Android alarms for both kinds
through the 3b-2 scheduler (a merged pair is one alarm at the earlier time, posted as the Hunt notification, with *Open house*), channel `hunt_reminders` (id 10, tag `hunt:<id>`), *Start Hunt mode* (starts the service with fine location,
otherwise opens the Map, which asks through its own Hunt switch) and *Dismiss*, and the iPhone `viewing-hunt-<id>` notification (compiled, not yet run). **Not built:** TalkBack labels on the notification actions (a notification action has no
content description), "the Map offers Hunt mode" after an iPhone tap (a tap opens the viewing).

**Built later (2026-10-01, [10](10-sprint-log.md) §13.31, §13.32).** The iPhone's *Add to calendar* shares the
viewing's `.ics` (S4b-BL-92a; its `DTSTAMP` is the share time, S4b-BL-101). A reminder's *Questions* opens the house at
its questions; a reminder tap on either phone opens the Map with *Start Hunt mode?* (`DeepLink.OfferHunt`); a deep link
clears the tab's stack first; the reminders keep their own "notifications asked" flag (`viewings.notificationsAsked`);
the notification actions name what they do (*Directions to the house*), for TalkBack. A saved or imported viewing
refreshes its house's AI document on the server.

### 5.9 Share to Doorprints: *Add a shared listing* (Indian portals)

"Share to Doorprints" is the team's internal name; users see **Add a shared listing**. It creates **one new house** from
shared text or a link and is **not an import** (only a Doorprints backup is imported: [01](01-requirements.md) §6.9,
[12](12-brand-and-naming.md) section G). In the system share sheet the target shows only the brand, **Doorprints**.

| Item | Design |
|---|---|
| Why not URL fetching | Fetching portal pages from our server would be automated scraping. MagicBricks, 99acres, NoBroker and Housing.com restrict it in their terms/robots rules, pages need JavaScript, and a free-tier IP would be blocked. **Doorprints never fetches the listing page** (keeps 01 §11.3 "scraping property portals" out of scope). |
| Android | New activity `ShareReceiverActivity` (exported, `ACTION_SEND`, `text/plain`) receives `EXTRA_TEXT` / `EXTRA_SUBJECT` from portal apps and browsers. Text is capped at 20 000 chars and treated as untrusted (SEC-043). |
| Parse | (1) First `https://` URL → `listingUrl` (tracking parameters `utm_*`, `fbclid`, `gclid` removed); portal recognised by host allowlist (`magicbricks.com`, `99acres.com`, `nobroker.in`, `housing.com`) → source label. (2) **No-AI parser** (Kotlin and TypeScript, same fixtures): price (`₹ 25,000`, `25k`, `45 Lac`, `1.2 Cr`, `/month`), RENT/SALE words, BHK (`2 BHK`, `2BHK`, `2 bedroom`), area (`1200 sq ft`, `sqft`), locality (`in <X>, <City>`), furnishing. Phone numbers are **not** auto-filled from shared text (PRV-013). (3) If AI is available (5.13), the user may tap "Improve with AI": on-device Gemini Nano for guests, or, for invited users, `POST /api/ai/extract-listing` with the text (never the URL fetched); the result goes through `DraftSanitizer` as today (AI-004, AI-005). |
| Location | Portal share text rarely has coordinates, and a house needs `lat/lon`. The draft asks "Where is it?": current location · pick on map · look up the locality (Android Geocoder, on the user's tap; already disclosed in PRV-007). Houses saved this way get `locationSource = APPROX` and a hollow marker, and **do not trigger Hunt-mode alerts** until the user confirms the location (FR-068). |
| Duplicates | Same normalised `listingUrl` or same label within 100 m → "You saved this on 12 Sep. Open it?" |
| Web / PWA | Manifest `share_target` (GET `/share?title&text&url`, Chromium on Android when the PWA is installed) and a "Paste a listing link or text" box on the new-house page run the same parser. |
| Nothing is saved until the user presses Save | Same rule as AI-004. |

```mermaid
sequenceDiagram
    actor U as User
    participant P as Portal app or browser
    participant SR as ShareReceiverActivity
    participant NP as No-AI parser
    participant API as API /api/ai/extract-listing
    participant ED as House edit screen
    U->>P: Share listing
    P->>SR: ACTION_SEND text and URL
    SR->>SR: cap 20000 chars, clean URL, detect portal
    SR->>NP: parse text
    NP-->>SR: draft - price, BHK, area, locality
    SR->>ED: open draft, ask Where is it
    opt AI on, online, user taps Improve with AI
        ED->>API: POST text only
        API-->>ED: sanitised draft
    end
    U->>ED: choose location, review, Save
    ED->>ED: save locally, dirty, sync later
```


### 5.10 PWA: installable and local-first

| Item | Design |
|---|---|
| Setup | `ng add @angular/pwa`: manifest (standalone, design-token colours, 192/512 + maskable icons, `share_target` GET `/share?title&text&url`), `ngsw-config.json`, `provideServiceWorker(... registerWhenStable:30000)`. |
| **Local store** | IndexedDB through `Dexie` (Apache-2.0) or `idb` (ISC): tables mirror Room (houses, visits, photos as `Blob`, criteria, questions, viewings, preferences, sync cursors, `dirty` flags). The web data layer (`house-api.service.ts` today) becomes a repository over IndexedDB; the network is used only by the sync engine (signed in, or the legacy API-key server). The Connect page is no longer the entry point: the app opens straight to the map. |
| Sync engine (TS) | Same algorithm as Android (03 §10): push dirty rows, pull with `since` cursors, LWW, tombstones, photo metadata; runs on start, on change (debounced 3 s), on `online`, and every 30 min while open. |
| Storage durability | Call `navigator.storage.persist()` after the first saved house; show used space (`storage.estimate()`). **Safari may delete a non-installed site's storage after 7 days without use**: iOS guests see "Install to Home Screen or make a backup to keep your data" (NFR-027). |
| Caching | App shell prefetch, lazy chunks lazy. **No API responses in Cache Storage** (they are in IndexedDB under app control); no map tiles (OpenFreeMap fair use). Offline the PWA works fully except AI and sync. |
| Updates, install | `SwUpdate` "new version, reload"; `beforeinstallprompt` → "Install app", never a pop-up. *As built:* a permanent card on *Your data* and a banner shown once after the first saved house ("Not now" = 30 days); iOS Add-to-Home-Screen steps ([05](05-ux-accessibility-i18n.md) §14.4). |
| Headers | `/ngsw.json`, `/ngsw-worker.js`, `/manifest.webmanifest` → `Cache-Control: no-cache` in `web/public/_headers`. *As built (2026-09-23):* no `ngsw` files (a hand-written `sw.js`), and `Cache-Control: no-cache` on every path from `web/firebase.json` on Firebase Hosting ([07](07-secure-build-and-deploy.md) §6.3). |
| Sign-out on a shared computer | "Sign out" asks: "Keep this browser's copy" or "Remove all Doorprints data from this browser" (clears IndexedDB, Cache Storage, storage). |
| Migration of today's online web users | First start of the new web app while an API key is configured: "Download your houses to this browser" (pull all into IndexedDB), then continue in legacy-sync mode until Sprint 5 sign-in. |

**PWA or Android app?** Android: Hunt mode (background-capable foreground location), reliable reminders, share from
portal apps on any browser. PWA: iPhone, laptop, family, no APK. Both are local-first and use the same formats and sync.

### 5.11 Google Sign-In, device sessions and sync ownership

| Topic | Design |
|---|---|
| Android sign-in | Credential Manager with Sign in with Google (`GetSignInWithGoogleOption`) → Google **ID token** (with a server nonce) → `POST /api/auth/google`. Needs Google Play services (AS-04); without it, local use continues. |
| Web sign-in | Google Identity Services (GIS) "Sign in with Google" button → ID token → same endpoint. GIS loads `https://accounts.google.com/gsi/client`: CSP gains `script-src https://accounts.google.com/gsi/client`, `frame-src https://accounts.google.com/gsi/`, `style-src https://accounts.google.com/gsi/style` (only on the web app, SEC-048). Alternative without Google script: authorization-code flow through the API (v0.1 design); decision D-16. |
| Server check | Verify the ID token: JWKS signature, `iss`, `aud` ∈ configured client IDs, `exp`, `nonce`, `email_verified`. Find or create `app_user` by Google `sub` (not email). |
| Doorprints session | Opaque 256-bit token (`dp_…`), stored as SHA-256 in `device_session`, `Authorization: Bearer`, 90 days sliding (Android), 30 days sliding (web with "keep me signed in", else browser session); revocation effective ≤ 60 s. Android keeps it with a Keystore AES-GCM key, excluded from backup; web in `sessionStorage` / `localStorage`. Google tokens are **not** used as API tokens and are never sent to our server except the one-time ID token. |
| Trusted devices | "Signed-in devices": name (editable, e.g. "Pixel 7 · Android app"), platform, created, last seen (day), current device marked; **revoke** one; **sign out all other devices**. Revoke stops sync at once; on the revoked device local data stays and the app shows "Signed out on another device". No 2FA-skip trust flag (2FA is Google's). |
| Sensitive actions | Deleting the account and revoking all devices need a fresh Google sign-in in the last 5 minutes (new ID token), SEC-035. |
| Ownership | `owner_id` on every synced table; every query filtered by the caller; another user's IDs → 404; per-user advisory lock; RAG/planner/MCP filter by owner (AI team). |
| First sign-in on a device with local data | "Upload this device's houses to your account" (default) · "Keep them only on this device". A device that later signs in to a **different** Google account asks again and never merges silently (FR-082). |
| Existing self-hosted install (API key) | Mode `both` in Sprint 5: the owner signs in with Google, then **claims** the existing data with the current API key (`POST /api/auth/claim`); existing full photos stay readable and the app offers "Move my photos to Google Drive" (5.12). Sprint 6: mode `accounts`, API key removed, `owner_id NOT NULL`. |
| Audit | `audit_event`: sign-in, device revoked, Drive connected/disconnected, deletion requested/cancelled/done; salted address hash; 90 days; shown as "Recent activity". |

```mermaid
sequenceDiagram
    actor U as User
    participant C as App Android or PWA
    participant G as Google
    participant A as Doorprints API
    participant DB as PostgreSQL
    U->>C: Sign in with Google (to sync)
    C->>A: GET /api/auth/nonce
    A-->>C: nonce
    C->>G: Credential Manager or GIS with nonce
    G->>G: Google password, 2-Step Verification, passkey
    G-->>C: ID token
    C->>A: POST /api/auth/google with ID token and device name
    A->>A: verify signature, iss, aud, exp, nonce
    A->>DB: find or create user by sub, insert device_session, audit
    A-->>C: Doorprints session token
    C->>U: Upload this device's houses to your account?
    C->>A: sync with Bearer token
```

### 5.12 Google Drive storage for photos and backups (optional)

| Item | Design |
|---|---|
| Choice | Settings → Photos: **"On this device only"** (default) · **"Also in my Google Drive"**. Asked again only when the user opens that setting or exports to Drive. |
| Scope | `https://www.googleapis.com/auth/drive.file` only: the app sees only files it created (or the user opened with it). Non-sensitive scope: no Google security assessment. Requested **incrementally** when the user turns Drive on, not at sign-in. Android: `Identity.getAuthorizationClient().authorize(...)`; web: GIS token client (`initTokenClient`). Access tokens stay on the device; **our server never receives Drive tokens or photo bytes**. |
| Layout | Folder `Doorprints` (created by the app; its ID synced in preferences so all devices use it) with `Photos/` and `Backups/`. Photo file name `<house label>-<date>-<photoId8>.jpg`, `appProperties` `{doorprintsPhotoId, houseId}`. |
| Upload | Android: WorkManager, Wi-Fi only if "Photos only on Wi-Fi" (FR-035), resumable upload, retries with backoff. PWA: while open. After upload the photo row gets `driveFileId`; the local file is kept unless the user turns on "Free up space: keep only thumbnails on this phone" (off by default). |
| Other devices | See the server thumbnail at once; the full photo loads from Drive with that device's own token (same OAuth project; cross-client visibility of `drive.file` files to be confirmed in the S5-01 spike). |
| Server copy | Only a **thumbnail** (max 320 px, ~15 KB, EXIF-free, AES-GCM encrypted, 5.15) and metadata (house, room, tags, caption, `driveFileId`, storage mode). Default: thumbnails for every photo of signed-in users; per-user cap (NFR-028). |
| Access revoked (user removed Doorprints in Google account settings, or token refresh fails with `invalid_grant`) | Drive work stops; banner "Google Drive disconnected. Photos are safe on this device. Reconnect?"; queued uploads wait; nothing local is deleted; server thumbnails stay. |
| File deleted or trashed in Drive (404 / `trashed`) | Photo marked `driveMissing`; if a local copy exists: "Upload again?"; else the thumbnail is shown with "Full photo was deleted from your Drive". Never re-creates silently, never errors in a loop. |
| Drive full (`storageQuotaExceeded`) | Pause uploads, notify once, keep photos locally. |
| Backups | Exports and automatic backups can be saved to `Doorprints/Backups`; the last 4 automatic backups are kept (older ones deleted by the app, which only touches files it created). |
| Existing server photos (owner's install) | "Move my photos to Google Drive": download from the server, upload to Drive, then the server keeps only the thumbnail and deletes the full bytes. Without Drive, the owner can choose "Keep on my devices only" (download to the device, then delete on the server). |

### 5.13 AI access: on-device where supported, cloud AI for invited users (D-21)

| Item | Design |
|---|---|
| Tiers | **Guest, or signed in but not invited:** on-device AI only. **Owner and invited users:** cloud AI on the owner's paid, hard-capped key, plus on-device AI where supported. **Nobody** brings their own key (D-22). |
| On-device AI (Android) | Gemini Nano through the **ML Kit GenAI Prompt API** (beta: no SLA, may change). At start the app checks the feature status; if the model is downloadable it offers the download on Wi-Fi; if unsupported, AI entry points are hidden. Features: "Improve with AI" on a listing draft and AI custom export (smaller limit than the cloud, e.g. 10 houses, because of the on-device context size; numbers set in the S5-07 spike). Output goes through the same on-device checks as the cloud path (draft sanitising, number and contact checks, 5.3, 5.9). Nothing leaves the phone (PRV-023). Ask and the viewing planner need the server index and tools, so they stay cloud-only. |
| Web app / PWA | No on-device model is committed. Guests and non-invited users see no AI entry points (browser built-in AI can be evaluated later, not planned). Invited users get cloud AI. |
| Cloud AI allowlist | The owner invites Google accounts by email on an owner-only screen; the flag is `app_user.ai_cloud_allowed` (V8), set when that account signs in. `/api/ai/*` checks the allowlist on every call; the owner's API-key install counts as the owner until Sprint 6. |
| Hard cap | In an AI-only Google Cloud project ([08](08-operations-runbook.md) §10.2): (1) per-user daily request quota and the existing rate limit (10/min), and a **global daily cap** on cloud requests and tokens in the app; when reached, cloud AI shows "AI is resting until tomorrow" (on-device AI still works). (2) A Google Cloud **spend cap budget** (Preview) on the AI project and the Vertex AI or Gemini API service: when the monthly target is passed, Google blocks new AI usage until the owner lifts the cap or the month ends (monthly only, counted before credits, not instant). The app treats the resulting provider errors like the global cap (cloud AI paused, on-device AI still works). (3) Budget alerts at 50/90/100 %, which only notify. A Quotas-page limit on the model is used only if the model exposes an adjustable one (to verify at setup). |
| Provider | Vertex AI (active) or the paid Gemini API tier (AI Studio key with billing). The free AI Studio tier may use prompts to improve Google products, so it is used only for evals with synthetic data (PRV-022). Selected with `AI_PROVIDER` (`aistudio` default, `vertex`); setup: [ai/vertex-setup.md](ai/vertex-setup.md). The Vertex credential is a payment-linked secret: Application Default Credentials only (no Vertex API key), AI-only project, service account with `roles/aiplatform.user` only, Workload Identity Federation in CI, no JSON key in the repository or CI ([02](02-threat-model.md) T-I22, [07](07-secure-build-and-deploy.md) §4). When the spend cap trips, the API must return a clear "cloud AI paused" problem that is not retried ([01](01-requirements.md) AI-017). |
| Disclosure | Invited users see which provider receives their question and selected data (AI-010). On-device AI says "Runs on this phone; nothing is sent". |
| No sign-in pressure | AI is not an incentive to sign in: there is no allowance counter or "sign in for more AI" prompt for guests. |
| Server | `ai_usage(day, user_id, count, tokens)` for invited users only; no installation ID and no guest records. |

### 5.14 Account deletion (7-day grace)

| Step | Behaviour |
|---|---|
| 1 | Account screen → "Delete my Doorprints account": explains what is deleted (everything on our server: synced houses, visits, thumbnails, AI index, sessions) and what **stays**: files in your Google Drive `Doorprints` folder ("they are yours; delete the folder in Google Drive if you want them gone") and the data on each of your devices. |
| 2 | Offers an offline copy first (5.2) with one tap. |
| 3 | Fresh Google sign-in (SEC-035), then confirm. Account status `PENDING_DELETION`, `delete_after = now + 7 days`; all sessions except the current one revoked; sync stops. |
| 4 | During the 7 days: signing in again shows "Your account will be deleted on <date>" with "Keep my account" (cancels) and "Continue". |
| 5 | After 7 days a scheduled job hard-deletes all owned rows, thumbnails, embeddings, sessions and the user's data key (crypto-shredding of thumbnails in DB backups); audit rows are anonymised. Local data on devices stays until the user clears it ("Also remove from this device" offered at step 3). |

### 5.15 Data at rest

| Where | Protection |
|---|---|
| Android | App-private storage under file-based encryption (AS-02); excluded from cloud backup (SEC-011); tokens Keystore-encrypted. |
| PWA | Browser profile storage (OS-level disk encryption where enabled); "Remove data from this browser" on sign-out. |
| Google Drive | Google's encryption at rest; files are normal JPEG/ZIP so the user can open them without Doorprints. Optional passphrase for backup ZIPs: D-17. |
| Server | Thumbnails and legacy full photos: AES-256-GCM, per-user data key wrapped by a master key from `APP_DATA_KEY` (env, no KMS), AAD = photo ‖ house ‖ owner IDs; rotation with `APP_DATA_KEY_NEXT` (re-wrap job); migration job encrypts existing rows. Losing the master key loses thumbnails only (full photos are on devices or Drive). |

### 5.16 Hunt mode reminders (Sprint 4b, D-23)

Builds on 5.8 (planned viewings). The reminder does not start anything by itself; it offers Hunt mode at the right
moment.

| Item | Design |
|---|---|
| Trigger | A viewing with status PLANNED and `huntReminder = true`. Lead time `huntReminderMin` (preference, default **15**, choices 5, 10, 15, 20, 30, 45, 60 minutes). Separate from the 5.8 viewing reminder (`remindMin`, default 60); when both are due within 10 minutes of each other (the length of the inexact window, see Scheduling), one notification carries both actions and fires at the earlier time. |
| Notification | "Viewing at <house> in 15 min. Start Hunt mode?" (the minutes are computed from `startsAt` when the notification is shown, because an inexact reminder can come early). Actions **Start Hunt mode** and **Dismiss**; tapping the body opens the viewing. Channel "Hunt mode reminders" (default importance, so Do Not Disturb applies as the user set it; no full-screen intent, no DND bypass). `VISIBILITY_PRIVATE` with the public version "Doorprints reminder" (no house name or address, SEC-022 style). |
| Start Hunt mode | The action's immutable `PendingIntent` starts `HuntService` (or opens `MainActivity`, which starts it). Android lets a foreground service start from the background, and lets a location foreground service use while-in-use location, when it is started by the user's interaction with a notification (Android developer docs, "Restrictions on starting a foreground service from the background", checked 2026-09-22). If foreground location is not granted (or was "Only this time"), the action opens the app and asks first (5.18). |
| Scheduling | Target time `T = startsAt − huntReminderMin`. (1) If `canScheduleExactAlarms()` is true (the user allowed "Alarms & reminders"): `setExactAndAllowWhileIdle(RTC_WAKEUP, T, …)`, so the reminder is on time, also in Doze (the system rate-limits allow-while-idle alarms per app, which one reminder per viewing does not hit). (2) Otherwise an inexact window that **ends** at the target: `setWindow(RTC_WAKEUP, T − 10 min, 10 min, …)`, so the reminder lands in [T − 10 min, T], early and never late. The window is 10 minutes because for apps targeting Android 12 or higher `windowLengthMillis` values under 600000 are typically clipped to 600000, and the system can delay a window alarm by at least 10 minutes; `setWindow` is not allow-while-idle, so while the phone is in Doze or battery saver it can arrive later than T (official guide "Schedule alarms", developer.android.com/develop/background-work/services/alarms/schedule, page last updated 2026-09-16, checked 2026-09-22). If `T − 10 min` is already past, schedule at now. The manifest declares `SCHEDULE_EXACT_ALARM` (not `USE_EXACT_ALARM`, which is limited to alarm-clock and calendar apps and to Play policy); it is not pre-granted to fresh installs targeting Android 13+, so the app never depends on it and never asks for it at start-up, only through the optional Settings link below. Without a declared "Alarms & reminders" permission an exact call on Android 12+ throws `SecurityException`, so the scheduler checks `canScheduleExactAlarms()` before every exact call and falls back to (2). On `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (sent when the permission is granted) and on every app resume it reschedules all reminders; when the permission is revoked the system stops the app and cancels its future exact alarms, so the next start (and the boot receiver) reschedules them as window alarms. WorkManager one-time work as a fallback when an alarm cannot be set. Rescheduled on boot, time and time-zone change, app update, and when a viewing is edited, cancelled or done. Offline, no server. |
| Settings | Settings → Hunt mode: "Remind me before viewings" (global, default on), lead time; per viewing: "Hunt mode reminder" switch. Without exact alarms a note says "Reminders may arrive up to about 10 minutes early (or later if the phone is in battery saver)." with a button **Allow on-time reminders** that opens `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` for the app (Android 12+; the user decides, no Play policy issue for `SCHEDULE_EXACT_ALARM`, which is user-granted); the note and button hide when `canScheduleExactAlarms()` is true (checked on resume). |
| i18n and a11y | All texts in en/hi/ta/te; action buttons with TalkBack labels ("Start Hunt mode for the viewing at <house>"); no time-limited UI. |
| Shared module | Lead-time and "next reminder" calculation are pure functions, candidates for `:shared` (`commonMain`). |

### 5.17 Hunting areas and area wake-up (Sprint 4b, D-24)

| Item | Design |
|---|---|
| Hunting areas | The user marks neighbourhoods they are searching in: tap the map (or long-press) to place a circle, drag the handle or pick a radius **200 m to 2 km** (default 500 m), name it ("Indiranagar 2nd stage"). At most **20 areas** (geofencing allows 100 per app; 20 keeps the list and battery small). Edit, enable/disable, delete. Stored in Room (`hunting_areas`, 8.2), included in every export and the JSON backup (5.2), synced later with sign-in (Sprint 5). |
| Area wake-up | Setting "Wake me in my hunting areas" (off by default). When on and the background permission is granted (5.18), one geofence per enabled area is registered with the Geofencing API (Google Play services location), `GEOFENCE_TRANSITION_ENTER` only, no expiry, default responsiveness. |
| On entering | A `BroadcastReceiver` (not exported; the geofencing `PendingIntent` is mutable as the API requires, and targets only that receiver) checks the cooldown and shows "You're in <area>. Start Hunt mode?" with **Start Hunt mode** and **Dismiss**. It **never starts tracking without a tap**. This is a privacy decision, not a platform limit: Android does exempt geofencing events from the background foreground-service start restriction (checked 2026-09-22), but Doorprints only ever starts Hunt mode on the user's tap. The start works as in 5.16. |
| Cooldown | Once per area per **6 hours** (`lastNotifiedAt` per area); no notification while Hunt mode is already on; "Dismiss" counts as notified. Pure logic, a `:shared` candidate (`AreaCooldown`). |
| Re-registration | After `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` (defensive), `GEOFENCE_NOT_AVAILABLE` (location turned off; re-register when it is back), permission changes, and app data restore. Removed when the feature is turned off or the permission is lost. |
| Latency and battery | Android delivers ENTER alerts usually within about 2 minutes, up to about 6 minutes when the phone was still; the Settings text says "within a few minutes". No location requests of our own (NFR-030). Recommended minimum radius per Android docs is 100 to 150 m, so 200 m is safe. |
| Offline | Works offline (geofencing uses on-device location; no server). Needs Google Play services (AS-04); without them the setting is hidden. |
| Shared module | `HuntingArea` model with validation (radius range, name length, count cap) and the cooldown are `commonMain` candidates; geofence registration stays in `:app` (an iOS app would use `CLLocationManager` region monitoring, which allows 20 regions per app, matching the cap). |

### 5.18 Location permission model (D-25, applies to Hunt mode and area wake-up)

| Situation | Behaviour |
|---|---|
| First launch, normal use, Hunt mode | Ask only for **foreground** location (`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`: "While using the app" or "Only this time"). Never ask for background location at first launch. Hunt mode is a foreground service started from the visible app or a notification action, which works with foreground permission. |
| "Only this time" | Permission ends when the app leaves the foreground for a while; the next Hunt mode start asks again. The prompt text explains that "While using the app" avoids the repeated question. |
| Approximate only | House-level alerts need precise location (the alert radius is 30 m). The app explains this and offers the precise upgrade dialog; house alerts and stay detection do not start on approximate fixes (the map still works). |
| Area wake-up turned on | 1. In-app **rationale screen** (why: to notice when you enter your hunting areas; what: Google Play services checks the areas on the phone, Doorprints stores no location history; battery: small; how to turn off). 2. Request `ACCESS_BACKGROUND_LOCATION` ("Allow all the time"). On Android 11+ this cannot be granted from a dialog: the system opens the app's location permission page; the rationale screen tells the user which option to pick and the app deep-links there (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` as the fallback). On Android 10 the system dialog offers the option directly. |
| Denied, downgraded or revoked | Area wake-up switches itself off, geofences are removed, and a one-time notice says why (in the app on next open). Hunt mode, reminders and everything else keep working. |
| Every app resume | Re-check fine/coarse/background state (and notification permission on Android 13+) and update the switches; no prompt without a user action. |
| Play policy (if ever published) | Google Play requires a background-location declaration in the Play Console with a video, an in-app prominent disclosure before the request, and a core-feature justification. The rationale screen is written to serve as that disclosure; the declaration is a pre-publication task (CON-02: sideloaded today). |
| Design review | Rationale screen and permission copy go through the Design Director review in [05](05-ux-accessibility-i18n.md) (4 languages, non-manipulative wording, equal Allow/Not now buttons). |

### 5.19 App lock (D-30)

An optional lock for the phone apps: PIN, fingerprint or face (Android BiometricPrompt with device-credential fallback;
iOS LocalAuthentication), asked when the app opens and after it has been in the background for a chosen time (default
1 minute). Why: the app holds other people's names, phone numbers and home locations, and phones are often shared in a
family. Off by default; turned on in Settings. No own PIN store: the phone's own lock screen credential is used, so
nothing new to forget or leak. The website gets no lock (a browser's profile is the boundary there; *Clear everything*
stays the tool for shared computers). Recent-apps preview hidden while locked (FLAG_SECURE on Android, the iOS
privacy snapshot). Goes with Google sign-in (14, N13 3b), which puts account tokens on the phone. Threat model: new
item for the lost or shared phone; tests on the emulator (a device credential can be set in CI) and TC-M on a device.

**Built (2026-09-29, S4b-FR-5).** Settings > Privacy > *Lock Doorprints*, on both phones. The times are *Right away*,
*1 minute* (the default), *5 minutes* and *15 minutes* in the background, measured on the phone's monotonic clock (a
changed wall clock does not open it). Turning the lock on or off asks for the phone's credential first (it proves the
lock works, and someone handed the unlocked phone cannot quietly turn it off). Without a screen lock on the phone the
switch says to set one; if the screen lock is removed while the app lock is on, the app opens and the lock turns off
(removing it needed that credential). Android: the platform's BiometricPrompt with the device credential (API 29+),
the keyguard's confirm-credential screen on API 26-28, no extra library; the recents picture off from API 33
(`setRecentsScreenshotEnabled`, screenshots stay possible), `FLAG_SECURE` below. iOS: LocalAuthentication's
device-owner policy (Face ID, Touch ID or the passcode; `NSFaceIDUsageDescription`), the app covered when it resigns
active. The screens stay composed under the lock, so a half-typed house is kept; Back on the lock screen leaves the
app. A rotation or a language switch is not leaving the app. A settings file that cannot be read locks (fail closed).
Tests: `AppLockGateTest`, `SettingsStoreTest`, the `app_lock` screenshots ([06](06-test-plan.md) TC-U-90); on a
device TC-M-29; the emulator test with a device PIN is S4b-BL-67.

**Later (2026-10-01, [10](10-sprint-log.md) §13.32, §13.36).** With the lock on, Hunt mode's alerts are posted
`VISIBILITY_SECRET`, so a locked phone's screen shows nothing of them (S4b-BL-68; they were `VISIBILITY_PRIVATE`).
`AppLockEmulatorTest` (S4b-BL-67) sets a PIN on the emulator and unlocks with it; it is compiled and joins the emulator
matrix, and its first run is the stacked pull request's.

### 5.20 Offline maps for the hunting area (D-30)

Today the houses are on the device but the map tiles come from OpenFreeMap over the network (NFR-004 promises only
cached tiles). *Save this area for offline*: the user draws or picks an area (a city or a hunting area, 5.17) and the
app downloads that area's vector tiles up to a street zoom (about 14) into a single file, then draws from it with no
network; free sources only (an OpenStreetMap extract in PMTiles, or OpenFreeMap's own tiles fetched once within its
terms; the size shown before download, a cap per area, Wi-Fi only by default). **India's boundary rules must hold on
the offline tiles too** (ADR-22: the same style changes apply, and the offline file is checked with TC-M-25 before it
ships); the boundary file itself is already bundled. Web: the PWA stores the file in the Origin Private File System or
Cache Storage, with the storage estimate shown. Goes with the path trace (S4b-FR-2), so the map changes and the
boundary re-check happen once.

**Built (2026-09-30, Android and iPhone; S4b-FR-6, [10](10-sprint-log.md) §13.15).** On the Map, *Save this area for
offline* (the download button among the controls) takes the box on screen: the dialog says the size to download
(about 50 KB a tile, every zoom from 0 to 14, street level; `OfflineTiles` in common code), warns on mobile data
("Wi-Fi is cheaper for this"; there is no setting: the person decides each time), takes a name (the area's locality
from the geocoder, or "My area") and refuses a box over 2,000 tiles with the numbers ("zoom in"). The download is
MapLibre's own offline pack on each phone (Android `OfflineManager`, iPhone `MLNOfflineStorage` through the Swift
shell), the same store as the map's cache, so no new library, no second file, and the offline tiles serve the map's
style as it is; the download carries on in the background and is picked up after a restart. Settings > Offline maps
lists the areas with their size or progress and deletes one after asking. India's boundary rules apply on every style
load, offline as online (ADR-22); the on-device re-check offline is TC-M-32 (owner). The source stays OpenFreeMap's
public tiles, one person's area at a time within the cap ([03](03-design.md) §11.2; the owner's word on the terms is
in [14](14-lead-backlog-and-handoff.md) §6). Not built: drawing an area or picking a hunting area (5.17 comes with
4c), and the website (S4b-BL-79: the service worker ignores cross-origin tiles, so it needs MapLibre's `addProtocol`
over Cache Storage). Tests: `OfflineTilesTest`, the `offline_maps` screenshots ([06](06-test-plan.md) TC-U-95).

**Built on the website (2026-10-01, S4b-BL-79, [10](10-sprint-log.md) §13.35; [03](03-design.md) ADR-30).** *Save this
area for offline* on the Map takes the box on screen to zoom 14 with the same estimate and caps (at most 2,000 tiles a
box, 10 areas), adding the raster layer, the style files and the glyph ranges of the Indic scripts (about 5 MB more);
the dialog shows the size and the browser's free storage and warns on a metered connection. The tiles go into Cache
Storage (`doorprints-offline-maps-v1`, apart from the service worker's shell cache) and MapLibre GL JS reads them through
`addProtocol` (`dpmap-tile`, `dpmap-file`), offline as online, with India's boundary rules applied as on every style
load. *Offline maps* on *Your data* lists and deletes them; *Remove all data* clears them. Tests [06](06-test-plan.md)
TC-U-115; the boundary re-check offline is TC-M-36.

### 5.21 The real cost of a house, my offer and the agreed price (D-30)

New house fields, all optional: **deposit** (rupees, or months of rent, shown as both), **maintenance** per month and
whether the rent includes it, **brokerage** (rupees or months), **lock-in** and **notice** (months), **available from**
(date), and for negotiation **my offer** and **agreed price**. With **carpet area** (already planned with rooms, S4-11)
the apps show, and Compare lines up: **monthly cost** (rent + maintenance, or for a sale, none), **money needed to move
in** (deposit + brokerage + first month), and **cost per sq ft**. The asked price stays the listing's price; the agreed
price, when set, is what ranking and exports use. Search grows with these values (CLAUDE.md rule: the new text fields
are not searchable; the numbers join the filters). One data-model change with rooms and criteria (S4-08): HouseDto v2,
the backup format and every exporter, on web and Android together, with the viewing questions for deposit and
maintenance (5.5) pre-filled from these fields.

**Built (2026-09-30, slice 1a of 5.30; [10](10-sprint-log.md) §13.19):** the fields, the arithmetic, the form's *Cost*
section, Compare and the copies; the viewing questions' pre-fill waits for 5.5 (slice 3) and the filters for S4b-BL-84. **Filters built** (2026-10-01,
S4b-BL-84, [10](10-sprint-log.md) §13.33): *Filter by cost* over monthly cost, money to move in and cost per sq ft
(whole rupees, from and up to); a house whose number cannot be worked out is left out while a range is set (a sale
has no monthly cost); on the website the ranges are kept in the address, on Android until the app is closed.

### 5.22 My places and distances (D-30)

The user saves the places that matter (work, school, parents' home; a name and a point, at most 10). Every house then
shows its straight-line distance and an estimated travel time to each (the same offline estimate as Plan: haversine x
1.3 road factor, walking or a chosen speed), in its detail and as optional columns in the list and Compare, sortable.
The checklist's *commute* score stays the user's own judgement; the numbers only inform it. Stored and synced like
hunting areas (5.17), and in the backup; not sent to AI unless the user asks about commute (then only names and
distances, never the coordinates).

### 5.23 Area notes (D-30)

A note attached to a hunting area (5.17) or a street ("this road floods in the monsoon", "water tanker every morning"),
shown on every house inside that area or on that street, and in exports. Plain text, the same length limits and
redaction as house notes (contacts removed before any AI use); searchable (the search rule).

**Design of slice 4a (2026-09-30; [10](10-sprint-log.md) §13.27).** The data, screens, distances and area notes of 5.17, 5.22 and 5.23. The geofence wake-up, the background-location rationale and the cooldown of 5.17/5.18 are **slice 4b**.

| Item | Decision |
|---|---|
| Area | Record type `area`, id `a_` plus eight hex. Payload keys in this order: `name` (1..100), `lat`, `lon` (valid coordinates), `radiusM` (200..2000; other reads 500), `enabled` (written only when false; used by 4b). At most 20 live areas. |
| Place | Record type `place`, id `p_` plus eight hex: `name` (1..60), `lat`, `lon`. At most 10 live places. |
| Area note | Record type `areanote`, id `n_` plus eight hex: exactly one of `areaId` (an area's id; may dangle) or `street` (1..100, compared case-insensitively after trimming with a house's `street`), then `text` (1..1000). At most 200 live notes. A note row with neither or both targets, or a blank text, is skipped on read and refused in a file. |
| Which houses a note reaches | A note on an area reaches every house whose point is within the area's `radiusM` (the haversine distance of `RouteOptimizer.haversineMeters`, on the web its twin; a house with `locationSource` APPROX is not placed and gets none); a note on a street reaches the houses with that street. A house may get several. |
| Distances | To each place: straight-line kilometres `haversine / 1000` shown with one decimal, and (screens only) the Plan estimate, `distance x DETOUR_FACTOR (1.3)` at the speed of the user's travel mode in Plan. The house detail has a *Distances* section (per place: name, km, minutes); Compare gets one row per place; a sortable list column is not built (S4b-BL). Vectors D1 (13.0067, 80.2574) to (13.0827, 80.2707) is 8 572.7 m, 8.6 km; D2 the same point is 0 m, 0.0 km; D3 (12.9716, 77.5946) to (13.0, 77.6) is 3 211.7 m, 3.2 km (one decimal, half up; the metres to one decimal). |
| Screens | *My areas* and *My places* (Settings on Android, cards on Your data and pages on the website): a list, add (name, then the point: *Use my current location*, *Pick on the map* with the existing picker, or on the website the coordinates and the map), edit, delete, the radius as a slider of 100 m steps for an area; *Area notes* are added from the house page (*Add a note for this street / this area*) and listed on the areas screen; the caps say "At most 20 areas", "At most 10 places", "At most 200 notes". Four languages, both themes. |
| House page and search | The house detail shows *Area notes* (the notes that reach it, newest first) and *Distances*. The house list search and `searchText` include the text of the notes that reach a house. |
| Backup and copies | Lists `areas`, `places`, `areaNotes` after `viewings` in `doorprints-backup/2` (any of them makes a copy `/2`); the manifest counts after `counts.viewings`. A copy made without contact details keeps them all (a place is the person's own data, not a contact). The house page of the readable copies gets an *Area notes* section and a *Distances* table (name, km) after the Viewings section; no new file. |
| AI | After the viewing lines: `Area note: <text>` (at most 5, newest first, through the contact redactor) and `Distance to <place>: <km> km` (names and distances only, at most 10; never the coordinates of a place). The three stacks write the same words. |
| Server | Three record types with import validation as the questions; `HouseDocuments` computes the same notes and distances. |

**Built (2026-09-30, slice 4a of 5.30; [10](10-sprint-log.md) §13.27).** The three record types (`docs/schemas/README.md` §3.12), *My areas* and *My places* (Settings on Android, cards on Your data on the website) with the radius slider, the point
(current location, typed coordinates, and *Pick on the map* on Android; on the website the map picker and the coordinates), the area notes list, the house page's *Area notes* (add for the street or an area) and *Distances* sections, a Compare row per
place, search over the notes that reach a house, the Area notes and Distances sections of the readable copies, the AI lines, the backup lists and the server. As built: a house at exactly (0,0) or with an APPROX position counts as having no point for
area-based notes (an APPROX house keeps its distances; street notes always reach); the minutes are the Plan walking estimate ("about N min on foot"), there being no travel-mode setting; deleting an area leaves its notes, shown as "An area that is gone";
*Pick on the map* is not run on a device. **Not built:** the sortable distance column of the list, any wake-up (4b).

**Design of slice 4b, the area wake-up (2026-09-30; [10](10-sprint-log.md) §13.28).** Android only (an iPhone needs region monitoring and "Always" permission; it is left to a later slice, S4b-BL-96). It turns the `enabled` flag of 4a into behaviour and follows 5.17 and 5.18.

| Item | Decision |
|---|---|
| Setting | Local, unsynced `areas.wakeup` (default off): *Wake me in my hunting areas*, in Settings > My areas. Hidden where Google Play services are missing (`GoogleApiAvailability`) and on the iPhone and the website (`PlatformFeatures`). |
| Turning it on | 1. The in-app **rationale screen** (why: to notice when you arrive in an area you are searching; what: Google Play services compares your position with your areas on the phone and Doorprints keeps no location history; battery: small; how to turn it off), buttons **Continue** and **Not now** of equal weight. 2. Fine/coarse foreground location first when missing (5.18). 3. **Allow all the time** (`ACCESS_BACKGROUND_LOCATION`): on Android 10 the system dialog; on Android 11 and later the app's location permission page (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` when the direct page is not available), the screen says which option to pick. The switch stays on only when background location is granted when the person returns; otherwise it is off and a line says why. |
| Geofences | One geofence per enabled area (radius = `radiusM`, `GEOFENCE_TRANSITION_ENTER` only, no expiry), registered with the Geofencing API behind a small interface (faked in tests); the `PendingIntent` is mutable as the API requires, explicit, to a non-exported `AreaGeofenceReceiver`. Registered again after boot, app update, `GEOFENCE_NOT_AVAILABLE` (location switched back on), every change to the areas or the setting, every app resume, and a permission change; removed when the setting is off, when an area is disabled or deleted, and when the permission is lost. At most the 20 areas (the API allows 100). |
| On entering | `AreaCooldown` (pure, `:shared`): notify when the area's `lastNotifiedAt` (local, unsynced, per area id) is at least 6 hours old, Hunt mode is not running and the setting is on; *Dismiss* counts as notified. The notification on channel `area_wakeup` (default importance, private on the lock screen with the public text "Doorprints reminder", no full-screen intent, no Do Not Disturb bypass): "You're in <area>. Start Hunt mode?" with **Start Hunt mode** (the 3c path: the service with fine location, otherwise the Map asks) and **Dismiss**. It never starts tracking by itself. |
| Revoked or downgraded | On resume, when background (or fine) location is no longer granted the setting is switched off, the geofences are removed and a card in My areas says once why ("Area wake-up is off because Doorprints no longer has location access all the time."). Hunt mode, reminders and the rest keep working. |
| Vectors | C1 never notified: notify; C2 notified 5 h 59 min ago: no; C3 exactly 6 h ago: notify; C4 Hunt mode running: no; C5 the setting off: no; C6 a disabled or deleted area: no geofence; C7 the registered set equals the enabled areas, at most 20. |

**Built (2026-09-30, slice 4b of 5.30; [10](10-sprint-log.md) §13.28).** `AreaCooldown` and `AreaWakeup` (C1..C7) in `:shared`; the local settings `areas.wakeup`, a stamp per area and the one-time "switched off" notice; on Android `AreaGeofenceManager`
(ENTER only, no expiry, radius `radiusM`, request id = the area id, initial trigger 0; the mutable, explicit PendingIntent to the non-exported `AreaGeofenceReceiver`), registered again at start, on every change, on resume, after boot and an
update and on `GEOFENCE_NOT_AVAILABLE`; the channel `area_wakeup` (notification id 11, tag `area:<id>`), *Start Hunt mode* (the 3c path; the service with precise location, otherwise the Map asks) and *Dismiss*; the rationale screen and the permission
steps (precise location first, then "Allow all the time": the system dialog on Android 10, the app's location page on Android 11 and later, `ACTION_APPLICATION_DETAILS_SETTINGS` as the fallback); the card when the permission is lost. As built: the
wake-up needs precise as well as background location (geofencing requires it); the cooldown stamp is set when the notification is posted and again on *Dismiss*; there is no listener for "location switched back on" (Android 8+ gives a manifest receiver
no `PROVIDERS_CHANGED`), so recovery after `GEOFENCE_NOT_AVAILABLE` comes at the next resume, boot or edit. The switch is hidden without Google Play services and on the iPhone. **Not run:** real geofencing and the permission pages (TC-M).

**Built on iPhone (2026-10-01, S4b-BL-96, [10](10-sprint-log.md) §13.30; compiled, not run).** `IosAreaWakeup`: Core
Location region monitoring of the enabled areas (at most 20, the platform's limit), registered again at start, on
changes and on resume; the same rationale screen naming the iPhone's two prompts ("While using", then "Always",
`NSLocationAlwaysAndWhenInUseUsageDescription`), the notification permission asked after "Always"; on entering, "You're
in <area>. Start Hunt mode?" as a notification whose tap opens the Map and offers Hunt mode (an iPhone app cannot
start tracking from a notification). No *Dismiss* on iPhone, so the 6-hour cooldown is stamped when it is posted. The
switch shows on iPhone where Core Location can monitor regions. On a device TC-M-35 (iPhone part).

### 5.24 Moving in (D-30)

When a house is chosen: status **Taken** (new; only one at a time, the others can be marked *Not chosen* in one step),
then a **move-in checklist** (India defaults in four languages, editable: rental agreement signed and registered, police
verification, ID copies exchanged, deposit receipt, meter readings, keys) and a **move-in condition record**: dated
photos per room with tags (5.7) and notes, which the user keeps for when the deposit is returned. Finally *Close this
hunt*: the hunt's houses are archived, not deleted, and a readable copy is offered. Goes with viewings (5.8, S4-12).

**Design of slice 5 (2026-09-30; [10](10-sprint-log.md) §13.29).** Photo tags (5.7) and moving in (5.24), in one change of the photo and house data.

| Item | Decision |
|---|---|
| Photo meta | A photo gains `roomId` (a room id of its house, at most 64 characters, may dangle: shown as "untagged"), `tags` (at most 10, each a fixed key `EXTERIOR, ENTRANCE, KITCHEN_FITTINGS, BATHROOM_FITTINGS, DAMP, CRACK, LEAK, VIEW, WATER_TANK, METER, PARKING, LIFT, GOOD_POINT, PROBLEM, MOVE_IN` or a custom text of 1..30 characters; no duplicates ignoring case; a custom tag may not equal a fixed key) and `caption` (at most 200), with `metaUpdatedAt` (epoch ms, 0 = never edited) for last-write-wins. Server: `PUT /api/photos/{id}/meta` (an older `metaUpdatedAt` changes nothing and answers the current meta), `PhotoDto` carries the fields so the change feed does; Flyway V12. Android Room **9** (columns on `photos`), the website's IndexedDB photos gain the fields (no version change). Backup photo rows: `id, houseId, fileName, createdAt, roomId, tags, caption, metaUpdatedAt`, the new keys written only when set. |
| Statuses | `HouseStatus` gains `TAKEN` and `NOT_CHOSEN` (a file with either is `/2`). At most one house is TAKEN: choosing TAKEN for a house returns the previous TAKEN one to SHORTLISTED, and asks *Mark the other houses Not chosen?* (**Mark them Not chosen** / **Keep them**). `closeTargets(houses, takenId)` = every house that is not the TAKEN one and not already REJECTED or NOT_CHOSEN. The lists and filters show the two new statuses; REJECTED keeps its meaning (rejected after looking). |
| Moving in | A TAKEN house has a **Moving in** card: *Start moving in* adds the six default items of `docs/schemas/default-movein.json` (fixed ids, the app's language, added once: an id already there is skipped), each item can be ticked, edited, removed, and the person adds their own (at most 30 items, text 1..200); the move-in date (optional) and notes (at most 2000); the **condition record**: the photos tagged MOVE_IN, grouped by room, each with its date and caption, and *Add a photo* which takes a photo with MOVE_IN already chosen. Stored nested in the house as `moveIn` (after `answers`): `date` (epoch ms, optional), `notes` (optional), `items` (`id`, `text`, `done` only when true, `sort`); Room 9 (`houses.moveIn`), Flyway V11 (`move_in jsonb`, blanked by the tombstone purge). |
| Close this hunt | On the Moving in card: **Close this hunt** marks every `closeTargets` house NOT_CHOSEN in one step (nothing is deleted), then offers *Save a copy* of everything. |
| Copies, AI, search | The house page of the readable copies gets a **Moving in** section (date, notes, the items with a tick) and a photo listing with room, tags and caption; `photos.csv` gains the columns room, tags, caption; the AI lines `Moving in: <done> of <total> done` and `Moving in notes: <text>` (redacted, one line); the list search includes the move-in item texts and notes and each photo's caption and tags where the list has them. |
| Vectors | M1 choosing TAKEN returns the previous TAKEN house to SHORTLISTED; M2 `closeTargets` leaves out the TAKEN, REJECTED and NOT_CHOSEN houses; M3 after any sequence of choices at most one house is TAKEN; M4 *Start moving in* adds the six items in order and again adds nothing; M5 the cap of 30 items; M6 a custom tag equal to a fixed key (any case) is refused, a repeated tag is dropped, more than 10 tags are refused. |

**Built (2026-10-01, slice 5 of 5.30; [10](10-sprint-log.md) §13.29).** As designed, on the three stacks (Room 9,
Flyway V11 and V12), with these choices made while building: the statuses' colours are Taken amber `#8A5A00` and Not
chosen grey `#5F6B66` (dark `#F2C265`, `#B4BEB9`), the same on both apps; the *Moving in* card is on the house form on
Android and on the house page on the website, and appears once the house is Taken; *Close this hunt* asks first ("Mark
*n* other houses Not chosen and close this hunt?"), and needs the Taken status saved. In the copies a photo's tags are
the stored keys, the move-in date is labelled *Move-in date* (the website said *When* until S4b-BL-99 d), and a
*Shortlisted only* copy leaves the Taken house out. **Not built or not the same everywhere** (S4b-BL-99): archiving the houses at *Close this
hunt* (they are marked, not hidden); search over photo captions and tags. Tests [06](06-test-plan.md) TC-U-109.

**In the running (2026-10-01, S4b-BL-99 a).** A house is *in the running* unless it is REJECTED (turned down after
looking) or NOT_CHOSEN (passed over once another house was taken); a status sent by an unknown name counts as in the
running. Compare offers only houses in the running (shortlisted first), and a Plan visits only them: the prompt says
*skip REJECTED and NOT_CHOSEN unless asked*, and the fallback route (no usable plan from the model) leaves them out. The
same on the server (`HouseStatus.inTheRunning`, `VisitPlannerService`), the phones (`HouseStatusRules.inTheRunning`,
`CompareScreen`, `PlanChecks`, `AiPrompts`) and the website (`house-status.ts` `inTheRunning`, `compare-page.ts`,
`ai-core.ts`), pinned by the vector `inTheRunning` in `docs/ai/evals/parity-vectors.json`. A Taken house stays in the
running. Changing a Not chosen house back to Shortlisted or New brings it back.

### 5.25 Brokers (D-30)

A broker (or owner) becomes a contact of its own: name, phone, agency, fee terms, the user's notes and rating; a house
links to one. The apps list *all houses from this broker*, keep one copy of the number, and warn when two brokers show
the same flat (the duplicate check of S4-13, within about 30 m with the same bedrooms and floor). The existing contact
name and phone on a house are migrated into brokers on first run (one broker per distinct phone number). Contacts stay
on the device and in the user's own sync; never sent to AI (the existing redaction applies). Goes with *Add a house
from a listing link* (S4b-FR-4), since listings bring the broker's details with them.

**Built (2026-09-30, slice 1b of 5.30; [10](10-sprint-log.md) §13.20):** a broker is a record of type `broker` (name, phone,
agency, fee terms, notes, rating) and a house carries `brokerId` with copies of the broker's name and phone in its own
contact fields, so old apps, the exports and the AI redaction keep working. Saving a house with a phone number links the
broker with that number or creates it (one broker per distinct number, compared on the last ten digits); the contacts of
existing houses were migrated once the same way. *Brokers* (Settings) lists them, and a broker's page edits it, calls it
and lists its houses. Not built: the duplicate-flat warning (S4b-BL-85; it needs the floor, which arrives with rooms). **Built later**
(2026-10-01, S4b-BL-85, [10](10-sprint-log.md) §13.33): the house form warns inline, "Maybe the same flat as …: within
about 30 m, with the same bedrooms and floor", against the other saved houses; a house with an approximate location is
never compared. Not built: the same warning on the broker's page. The import preview counts new and updated brokers
(S4b-BL-86).

### 5.26 Voice notes (parked, D-30)

Parked: the phones' keyboards already turn speech into text in Hindi, Tamil and Telugu, and audio files would add to
every sync, backup and Drive copy. Revisit if users ask for recordings rather than text.

### 5.27 The path trace (S4b-FR-2)

**Built (2026-09-29, Android; the iPhone since S4b-BL-69 the same day, [10](10-sprint-log.md) §13.14).** Settings > Hunt mode > *Trace my path on the map*, off by
default. While it is on and Hunt mode runs, the engine keeps the fixes that pass Hunt mode's accuracy gate (50 m or
better), thinned to one point per 20 m or 5 minutes (`TrackRecorder`), and the Map draws them as a purple line under
the house markers, split into one line per walk (a gap of 30 minutes or more starts a new line, so the map never draws
a leap between two walks). The privacy review ([02](02-threat-model.md) T-I30, [01](01-requirements.md) PRV-028): the
trace is location history, so it stays on the phone only, in its own table (`track_points`, Room version 3), and is
never in a backup, a readable copy, the sync to a self-hosted server or an AI request; it is pruned to the last 30
days each time Hunt mode starts, and *Clear the path* under the switch removes it at once. Turning the switch off
stops recording and keeps what is there until it ages out or is cleared, so a day with the trace off does not lose
the week. The line's colour (`#8E24AA`) is none of the marker colours and none of the base map's own line colours
(roads, India's boundary, the state lines), readable on the light tiles both themes show; TC-M-30 checks it on a
device. Phone accuracy only, so the DST guidelines' 1 m threshold does not apply ([03](03-design.md) §11.1). The
website has no Hunt mode; its trace, and saved walks, are designed in 5.27.0..5.27.11 and built on `feat/path-trace-v2` (2026-10-06, PR #146, not yet merged). Tests: `HuntEngineTest` (the trace and the pruning), `TrackStyleTest` (`trackGeoJson`, which replaced `TrackGeoJsonTest` on that branch),
`JsonStyleOpsTest` (the layer under the houses on iOS), `AppDatabaseMigrationTest` (2 to 3), the `hunt_trace`
screenshots ([06](06-test-plan.md) TC-U-94).

#### 5.27.0 The path trace, version 2 (owner request of 2026-10-06; built on `feat/path-trace-v2`, PR #146, not yet merged)

**The purpose** (owner): *help a person avoid the same path and see where she has travelled.* Today's trace is one
static purple line. The owner decided, on 2026-10-06:

1. **Repeats stand out.** Where the person walked the same path in two or more different walks, the line is drawn
   thicker, in a second colour and dashed (5.27.4). Never colour alone.
2. **A sound alert, optional, off by default,** when she takes a path she has walked before while Hunt mode runs
   (5.27.5).
3. **History.** The trace stays 30 days as today. When a walk ends (Hunt mode stops, or *Finish walk*) the person is
   asked whether to **save it, linked to a house**; the default is to keep it 30 days and let it age out. A saved
   walk is not pruned by the 30-day rule (5.27.6).
4. **Privacy: "Phone only".** A saved walk stays on the phone or in the browser only, like today's trace: never in a
   backup, a *Save a copy* file or readable copy, an update file, the Google Drive backup or sync, the sync with a
   self-hosted server, or an AI request. A phone set up from a backup, a copy, Drive or the server starts without saved
   walks; Android's own phone-to-phone transfer is the one exception and the text says so (5.27.7; PRV-028, T-I30).
5. **The website gets the trace too**, with the browser's Geolocation API while the page is open, stored in
   IndexedDB, under the same privacy rule (5.27.8).
6. **The look is the person's choice** (owner, same day): a thicker line may look clumsy to some, so a setting *How
   repeated paths look* has three levels, **Clear** (the default), **Subtle** and **Off** (5.27.4). The alert
   setting is independent of it.

7. **A place check on demand** (owner, later the same day): a button *Have I been here?* compares a place (where she is, a house, a
   spot on the map) with her walks and answers in words; never automatic, never stored or sent (5.27.13).

Everything below is the design, and the code now follows it: S4b-FR-13..S4b-FR-17 and S4b-FR-24 are built on `feat/path-trace-v2` (PR #146; S4b-FR-18, the guide, is done) ([10](10-sprint-log.md) §15). **What is built and what is not:** the shared algorithm, saved walks (Room 11, IndexedDB 3), the alert, the place check and every screen on Android, the iPhone (common code and the Swift `setRepeatLook`; compiled, not run on a Mac here) and the website. **Not built:** the website's Hunt mode (5.27.12, S4b-FR-19..23, not scheduled). **Not yet done on a device (owner):** TC-M-25 (re-run, the base map gained layers), TC-M-57..TC-M-61 and MT-75..MT-81; nothing below claims them. The review of the branch ([10](10-sprint-log.md) S4b-FR-25..S4b-FR-37) lists what was found and what is still being fixed. The
sections 5.27.2 and 5.27.3 are the contract that the Kotlin and the TypeScript implementations share, held to each
other by the vector file [`docs/schemas/trace-repeat-vectors.json`](schemas/trace-repeat-vectors.json) (status
*confirmed* on 2026-10-06: Kotlin and TypeScript both pass every case; a later change to an expected value is made only in a documented change to this text, never silently). What 5.27 above says of the build of 2026-09-29 stays true where this design does not amend it; where this
design changes it, the change is named (*Amended*).

#### 5.27.1 What the person sees, in one page

- **Settings > Hunt mode** (phones) and the Map page's *Trace my path* card (website): the switch *Trace my path on the
  map* (as today), then, under it, **How repeated paths look** (Clear, Subtle, Off), **Warn me when I walk a path again**
  (off by default), **Saved walks: n** with *Delete all saved walks*, and *Clear the path* (as today; it clears the
  30-day trace only). The website adds *Keep the screen on while I walk*.
- **The Map:** each path walked once is a solid line; each stretch walked in two or more different walks is a dashed
  line in the second colour, thicker with *Clear*. A small legend row (*Walked once* / *Walked more than once*) shows
  both samples while the trace is not empty.
- **Hunt card** (phones): a new button *Finish walk*. **Website Map page:** *Start a walk* and *Finish walk*.
- **When a walk ends:** a bottom sheet *Save this walk?* with **Save with a house** (opens the house picker),
  **Keep for 30 days** (the default, the highlighted button) and **Delete this walk**. Closing the sheet any other way
  is *Keep for 30 days*.
- **House page:** a card *Saved walks* (date, distance, minutes; *Show on map*, *Delete walk*).
- **The alert:** a short system notification sound (phones) or a beep and a banner (website): *You have walked this way
  before.*

#### 5.27.2 Words and constants (both stacks use exactly these)

| Word | Meaning |
|---|---|
| **Fix** | A location reading. Only fixes of 50 m accuracy or better are used (`HuntState.MAX_ACCURACY_M`; the website uses the same gate on `coords.accuracy`). |
| **Point** | A *kept* fix: `TrackRecorder` keeps one at least 20 m from the last kept point or 5 minutes after it (the website the same). A point is `(lat, lon, atMs)`; stored points also carry the **walk id**, and the website's a **resumed** flag (the first point after the page was hidden for more than 5 minutes, step 3). |
| **Walk** | A run of at least two points in time order with no gap of 30 minutes or more between neighbours, and one walk id. A walk ends when Hunt mode stops, when the person taps *Finish walk*, or at a gap of 30 minutes or more. Walks are **different walks** when they are not the same walk: points with different walk ids, or a gap of 30 minutes or more between them. |
| **Walk id** | The `atMs` of the walk's first kept point (a `Long`). `TrackRecorder.reset()` (Hunt mode start) and *Finish walk* make the next kept point start a new walk id. **A walk id of 0 is no id**: rows written before this design, and the website's points before a walk id is known, carry 0 and are split by the gap rule alone; the id rule of step 1 applies only when both points have a non-zero id. The id is assigned at the first *kept* point, not at *Hunt start* or *Start a walk*: a walk that never gets a fix has no id and no row. |
| **Segment** | The straight line between two consecutive points of one walk, except into a resumed point (step 3: there is none). |
| **Sample** | A point on a walk's polyline used for matching: every original point, plus points that divide each segment into equal parts (below). |
| **Corridor** | The set of places within `TOLERANCE_M` of another walk's polyline, including a round cap at that walk's ends. |
| **Near** | A sample is near when its distance to some **other** walk's polyline is at most `TOLERANCE_M` (inclusive). |
| **Run** | A maximal series of consecutive near samples of one walk, after bridging. |
| **Repeated stretch** | A run whose length is at least `MIN_RUN_M`. |

| Constant | Value | Why |
|---|---|---|
| `EARTH_RADIUS_M` | 6 371 000 | The same radius as `Geo.distanceM` (haversine), so a distance means the same everywhere. |
| `WALK_GAP_MS` | 1 800 000 (30 min) | As today (`TRACK_GAP_MS`): a longer pause is a new walk. |
| `DENSIFY_M` | 10 | Sample spacing. The 20 m thinning leaves points 20 m apart or more (hundreds of metres on a bike or a bus); matching points rather than segments would miss a street walked with points in different places. 10 m is half the thinning step and well under the tolerance. |
| `TOLERANCE_M` | 25 | Two walks of one street differ by their GPS error. Fixes pass the gate at 50 m, but the usual urban error is 5 to 15 m, so two readings of one path are mostly 20 m apart or less; Indian lanes that run side by side are often 30 to 60 m apart. 25 m accepts the first and refuses the second **when the readings are exact**; with real noise (the reviewer's probe, 8 m error a side, 400 m of two lanes) lanes 40 m or more apart are refused, at 35 m a few trials light up, and at 30 m apart about half the trials mark about a sixth of the length. If the owner's field check (TC-M-57, two lanes about 30 m apart) shows them lit, the fallback is `TOLERANCE_M = 20` with the vectors regenerated from the reference and the constant changed here, in the vector file and in both stacks together. |
| `BRIDGE_M` | 30 | A run may be broken by one bad fix (the gate lets 50 m through). A stretch of non-near samples between two near samples is filled when the two near samples are at most 30 m apart along the walk (at most two samples at 10 m). |
| `MIN_RUN_M` | 80 | **A junction is not a repeat.** Two streets crossing at an angle θ stay within the corridor for about `2 x 25 / sin θ` metres: 50 m at 90 degrees, 58 m at 60, 71 m at 45. At 80 m only a crossing under about 38 degrees counts, and that is nearly the same street. |
| `ALERT_MIN_RUN_M` | 100 | The alert needs a clearer repeat than the line: the person has followed the path for 100 m. |
| `ALERT_COOLDOWN_MS` | 600 000 (10 min) | Between two alerts. |
| `REPEAT_MIN_WALKS` | 2 | A stretch is repeated when this walk **and at least one other different walk** cover it. A third walk changes nothing (open question 6). |
| `MAX_WALK_POINTS` | 5 000 | A walk is saved only up to this many points (5.27.6). |
| `MAX_DETECTION_POINTS` | 20 000 | The most points the detection reads: the newest walks first (by walk id), whole walks only. |
| `NEAR_BAND_M` | 50 | **The place check only (5.27.13):** a walk 25 to 50 m from the place is reported as *close*, never as *walked*. 50 m is the accuracy gate. |
| `MAX_FIX_ACCURACY_M` | 50 | **The place check only:** a *here* fix worse than this is refused (the Hunt gate, `HuntState.MAX_ACCURACY_M`). |
| `CHECK_STRETCH_M` | 60 | **The place check only, drawing only (not in the vector file):** the highlighted stretch runs 60 m each side of the nearest point. |
| `PAUSE_SPLIT_MS` | 300 000 (5 min) | **Website only, in the recorder, not in the detection:** a page hidden for longer than this marks the first point after it *resumed* (5.27.8). The vector file carries the flag, not this number. |

`MAX_WALK_POINTS`, `MAX_DETECTION_POINTS`, `NEAR_BAND_M` and `MAX_FIX_ACCURACY_M` are also in the `constants` block of the vector file, so the drift test of TC-U-147 covers every constant of this table that the algorithm or its callers read (`CHECK_STRETCH_M` only draws).

#### 5.27.3 The repeat-detection algorithm (shared; the contract of the vector file)

Written once in `:shared` commonMain (`app.doorprints.shared.trace.RepeatDetector`) and once in TypeScript
(`web/src/app/shared/trace-repeats.ts`), from this text, not one from the other. Pure functions: no clock, no I/O.
Inputs are doubles; saved walks come back from their storage already rounded to 1e-6 degrees (about 0.11 m), which
cannot change a result outside the 0.5 m the vectors allow.

**Step 1, clean and split.** `splitWalks(points)`: drop a point whose latitude is not in [-90, 90] or longitude not in
[-180, 180] or any number not finite; sort by `atMs`, **stable** (the input order breaks a tie: the vector `split-unsorted-and-duplicate-times` depends on it;
Room's `ORDER BY at` does not order ties, so the rule holds for the in-memory list); drop a point whose `atMs` equals the one
before it (the first stays); start a new walk when the gap to the previous point is `>= WALK_GAP_MS` (a gap of exactly
1 800 000 splits, 1 799 999 does not) or when **both points carry a non-zero walk id** and the ids differ; keep walks of at
least two points. **A walk id of 0 is no id** (rows from before this design, and the website's points before a walk id is
known): with a 0 on either side the gap rule alone decides, so `[.., 5], [.., 0], [.., 6]` with 60 s gaps is one walk (the
vector `split-walk-id-zero-is-no-id`; Kotlin's `TracePoint.walkId` defaults to 0 and the web's `walk` field means the same). (Stored walks need no splitting: they are already walks.)

**Step 2, distance.** All distances are in metres on a local flat plane: for a point `q` and a point or segment around
it, `x = (lon - q.lon) * cos(rad(q.lat)) * K`, `y = (lat - q.lat) * K`, `K = EARTH_RADIUS_M * pi / 180`. Between two
points of a walk (segment length, arc length) the latitude used is the mean of the two. Why this and not a fixed
grid of cells: a grid of 20 m cells would call two readings 21 m apart "different" when they straddle a cell edge and
"the same" at 39 m when they sit in one cell; a distance has no edges, is exact to centimetres over a walk's size, and
is the same arithmetic in both languages. (The index in step 5 may use a grid; the answer may not depend on it.)

**Step 3, samples.** For each segment of length `L`: `n = max(1, floor(L / 10 + 0.5))` equal parts; the samples are the
segment's start and the `n` points at `i / n` of the way (so the end is the next segment's start and appears once).
Each sample has its arc length from the walk's first point (the sum of segment lengths so far plus `i/n` of `L`). A
walk's first point is sample 0 at arc 0. A segment of length 0 (a stay: two points at one spot) gives one sample at the same
arc, `n = 1`; that is harmless and an implementation must not "fix" it. **A resumed point** (the website's first point after
a page hidden for more than `PAUSE_SPLIT_MS`; `TracePoint.resumed`, false on the phones) begins a new *part* of the same walk:
there is **no segment** between it and the point before it, so no samples, no length and nothing near it on that stretch
(the arc length does not grow across it), and the point itself is a sample like the walk's first, at the arc reached so far.
A walk is still one walk for the "different walks" rule: a pause does not make a second walk, so a person who goes out, is
paused and comes back along her own path is not a repeat. The vector `web-pause-makes-no-segment`.

**Step 4, near flags.** For a walk W, the *others* are all walks in the input except W (never W itself, so a walk that
goes up a street and comes back is one walk and no repeat: the owner's "two or more different walks"). Order does not
matter: an earlier and a later walk are others to each other, and a stretch is repeated in both. A sample is near
when its distance to some other walk's polyline (the point-to-segment distance, the segment clamped at its ends; the
other walk's segments are its original ones between kept points, not its densified samples, and without any segment
into a resumed point) is `<= TOLERANCE_M`.

**Step 5, bridging.** Take the near flags. For each maximal series of non-near samples that has a near sample on both
sides, if the arc length between those two near samples is `<= BRIDGE_M`, mark the series near. (One pass over the
original flags; filled samples do not bridge further.) A non-near series at the start or the end of the walk is never
filled, and **a bridge never crosses a part boundary** (a resumed point): the two near samples must be in one part.

**Step 6, runs.** A run is a maximal series of consecutive near samples **of one part** (a resumed point ends a run). Its length is the arc length from its first
sample to its last. A run of length `>= MIN_RUN_M` is a **repeated stretch**, reported as `[fromM, toM]` (arc lengths
from the walk's first point). A run has round caps, so it can stick out up to 25 m past where the other walk ends: the
corridor, not the exact overlap. The relation is not symmetric in length (the vectors `t-junction-shared-stem` and
`overlap-90m-is-a-repeat` show it) and need not be.

**Step 7, who draws it (`shown`).** Two walks over one street would draw two dashed lines out of step, which reads as
a solid one. So each repeated stretch is drawn by one walk: a repeated sample of walk W is **left to a newer walk V**
(newer: a later walk id, which for a walk is the `atMs` of its first point, never the input index; the later input index only on
a tie: the vector `shown-newest-draws-whatever-the-input-order`) when V is within `TOLERANCE_M` of the sample **and** the point
of V nearest to the sample lies inside one of V's own repeated stretches (a margin of 0.5 m). The samples that remain,
as maximal series of at least two, are the **shown** stretches; they become the dashed overlay. Every walk is also
drawn whole as the solid base line, so a stretch left to a newer walk still shows (under that walk's dashes).

**Output.** `detect(walks)` returns a `List<WalkRepeats>`, one per walk in input order: `repeated: List<Stretch>` and
`shown: List<Stretch>`, `Stretch(fromM, toM)`; plus a helper `pieces(walk, stretches)` that cuts the walk's samples
at the stretch boundaries into polylines for GeoJSON (the stretch's samples, with the boundary samples shared so the
overlay meets the base without a gap).

**The alert's test, one point at a time (`RepeatAlert`).** State: `blocked` (false), `lastAlertAt` (none). **The others** for
the alert are every other walk stored, saved walks included, **except a walk whose last point is less than `WALK_GAP_MS`
before the live walk's first point** (`live.first.atMs - other.last.atMs < WALK_GAP_MS`; exactly 30 minutes counts): after
*Finish walk* at a house, walking back the way one came is a different walk, but not one to warn about. The exclusion is for
the alert only; the Map's detection still marks the street (vectors `alert-not-for-the-walk-just-finished` and
`alert-walk-finished-30-minutes-ago-counts`; open question 1). At each kept point `P` of the live walk (index 0 never
alerts), build the live walk's samples up to `P` and the near flags and bridging of steps 3 to 5 against the others (the live
walk is W). If `P` (the last sample) is not near: no alert, and `blocked` becomes false **only when `arc(P) - arc(last near sample) > BRIDGE_M`** (that is, the arc length from the last near sample to `P` exceeds `BRIDGE_M`), which means bridging could no
longer join `P` to the run behind it (or there is no run behind it, or a part boundary lies between). One or two off samples
inside a run neither ring nor unblock (the vector `alert-one-bad-fix-does-not-ring-again`: one fix 40 m off the street, inside
the 50 m gate, must not ring the same street again after the cooldown). Otherwise the **trailing run** is the series of near
samples (after bridging, within one part) that ends at `P`; its length is the arc length from its first sample to `P`. The
alert rings when the trailing run is `>= ALERT_MIN_RUN_M`, `blocked` is false and (`lastAlertAt` is none or `P.atMs -
lastAlertAt >= ALERT_COOLDOWN_MS`); then `blocked = true` and `lastAlertAt = P.atMs`. A run that qualifies while the cooldown
still holds does not ring and stays eligible at the next point of the same run (the vector
`alert-cooldown-suppresses-a-second-alert`). `blocked` keeps one run to one alert; a new alert needs the walk to leave the
paths walked before for more than `BRIDGE_M` and come back.

**Cost and limits.** Reading is per walk against the others; an index (a grid of 100 m cells over the others'
segments, or an R-tree) and a bounding-box test (each other walk's box grown by 25 m) are required, because a month of
trace plus up to 200 saved walks is tens of thousands of segments, and results must equal the plain loops (the tests
run both on the vectors and on a random city: the same stretches). At most `MAX_DETECTION_POINTS` points are read,
newest walks first, whole walks only; walks over the limit are not compared (they are still drawn; on the phones, when all the walks together exceed that many points, the older walks' base lines are drawn thinned, their ends and every k-th point kept, the newest whole, and the repeat pieces always come from the whole walks). The Map computes
off the main thread, when the Map opens, when the walks change (a walk ends, a walk is saved or deleted) and, while
a walk records, at most once every 5 seconds; it caches the result by the walks' ids and point counts. The alert
needs only the samples near the newest point, so an implementation may look at the live walk's last `ALERT_MIN_RUN_M +
BRIDGE_M` metres plus one sample (130 m) instead of the whole walk, provided the window starts on a sample and gives the same
flags for the last 100 m; the vectors and the random-city test must return the same alert indexes as the full definition
(a window that cuts a non-near series can bridge it differently, and the unblock rule above needs the trailing non-near
series as far back as `BRIDGE_M` too).

**With *How repeated paths look* = Off the detection still runs** when the alert is on; with both off it may be
skipped, because nothing shows its result. The look never changes a result (the vector file has no look field).

#### 5.27.4 How repeats look

**Layers** (one GeoJSON source `track`, features tagged by property `kind`): the **base** line (`kind = "base"`, layer
`track-line`, as today: solid, round caps, `#8E24AA`, opacity 0.85, widths by zoom as today) for every walk whole; the
**repeat** overlay (`kind = "repeat"`, layer `track-repeat-line`, drawn above the base and under the house layers)
for every shown stretch. `trackGeoJson(walks, shown)` gets the second kind; `trackLayerJson()` stays; new
`trackRepeatLayerJson(look)`. The `track` source stays one so offline packs and the India-boundary checks of
TC-M-25 are unaffected.

**The second colour: `#E65100`** (a deep orange; web and phones, the same on the light map in both themes, because the
map's tiles stay light in both). Chosen against four constraints:
- *Not a marker colour:* the markers are blue `#3C5A99`, green `#1A7A43`, red `#B3261E`, amber `#8A5A00`, grey `#5F6B66`
  (and `#888888`). The nearest are red and amber; `#E65100` is lighter and more saturated than both, and a marker is a
  filled dot with a white ring while a repeat is a line.
- *Not a base-map line colour:* OpenFreeMap's roads are white, pale yellow and pale orange casings, state and country
  lines are grey and India's boundary is dark grey; none is this saturated an orange. TC-M-25 and the new TC-M-57 check it.
- *Apart from the purple `#8E24AA` for red-green colour-vision deficiency* (the common kind): purple is a blue-red mix
  and reads as blue; the deep orange reads as yellow-brown, so the pair sits on the blue-yellow axis that protan and
  deutan eyes keep. For tritan eyes (rare) the pair differs in luminance: relative luminance 0.10 (purple) against 0.23
  (orange) on top of the dash, and the dash is the rule that does not depend on any of this.
- *Visible on the light map:* contrast with white 3.79:1 (at least 3:1 for a graphic, WCAG 1.4.11); the purple has 7.04:1.
  Orange on purple is 1.86:1, which is why the dash, not the colour pair, carries the cue.

**Checked, 2026-10-06 (senior review, [ops/path-trace-spec-review.md](ops/path-trace-spec-review.md) 5): `#E65100` stays.**
Machado, Oliveira and Fernandes (2009) at severity 1.0, CIEDE2000 between the orange and the purple: 48.5 for normal
vision, 57.1 protanopia (olive against blue), 59.1 deuteranopia, 26.3 tritanopia (at least 20 is a different colour). One
weakness, stated here so nobody is surprised: **under protanopia the orange line and the amber and star marker colours are
near-identical in colour (CIEDE2000 4 to 7); they are told apart by form only** (a dot with a white ring against a dashed
line) and by the purple base under the dashes. That is why *Off* is never the default and why the dash is non-negotiable.
The device check of TC-M-57 (4) on a map screenshot stays as the confirmation; if it fails, the colour is changed in this
section and in `TRACK_REPEAT_COLOR`, nowhere else.

**Dash:** `line-dasharray [3, 2]` (units of the line's width: a dash of three widths, a gap of two), `line-cap: butt`
(round caps would close the gaps), `line-join: round`; opacity 0.95. The gaps show the solid base line beneath, so the
stretch reads as orange dashes on a purple line.

**Widths by zoom (px).** The base keeps today's `TRACK_WIDTHS = 10 to 1.5, 14 to 3.0, 18 to 5.0` (linear between).
The overlay's widths are the base's times the look's factor:

| Look | Factor | Overlay at zoom 10 | 14 | 18 | Dash | Colour |
|---|---|---|---|---|---|---|
| **Clear** (default) | 1.8 | 2.7 | 5.4 | 9.0 | yes, `[3, 2]` | `#E65100` |
| **Subtle** | 1.0 | 1.5 | 3.0 | 5.0 | yes, `[3, 2]` | `#E65100` |
| **Off** | | no overlay layer shown; a repeated stretch is the base line like any other path | | | | |

At zoom 10 the dash of 1.5 px is 4.5 px long and the gap 3 px: it reads as a dotted line, which is why the overlay
fades in from zoom 11 (opacity 0 at zoom 10.5, 0.95 at 11) for both looks. Constants `TRACK_REPEAT_FACTOR_CLEAR = 1.8`
and `..._SUBTLE = 1.0` are in the shared style code (`MapStyleJson.kt`, web `trace-style.ts`), and a test pins the
table above.

**Never colour alone** (WCAG 1.4.1): *Clear* and *Subtle* both keep the dash, which is the second cue; *Clear* adds
width as a third. The legend row draws the same sample lines, and its text says *Walked more than once*. With *Off* the
look is "colour and dash and width: none": the repeats are not marked, which is the person's explicit choice, and the
Map says so once (the setting's hint). The Map's content description names the repeat style: *Paths you walked more
than once are dashed.*

**The setting:** `repeatLook` with the values `CLEAR` (default), `SUBTLE`, `OFF`; stored with the other trace settings
(Android and iPhone: `AppSettings.repeatLook`, DataStore key `repeatLook`; website: settings-store key
`trace.look`), **per device**, never synced, exported or backed up (a display choice, not data; 5.27.7). A radio group
with a one-line description each, under the trace switch, shown (enabled) whether or not the trace is on.

**Live:** the choice applies at once, with no restart of Hunt mode or of a walk, and the GeoJSON is not rebuilt.
`PlatformMap` gains a parameter `repeatLook: RepeatLook`. **Android:** `LaunchedEffect(style, repeatLook)` finds the layer
`style.getLayerAs<LineLayer>(TRACK_REPEAT_LAYER)` and calls `setProperties(lineWidth(repeatWidthExpression(look)),
visibility(if OFF NONE else VISIBLE))`; the layer is added once, after `track-line`, from the same values as
`trackRepeatLayerJson`. **iPhone:** a Swift method `setRepeatLook(widthStops: [[Double]], visible: Bool)` on the map wrapper,
called from `PlatformMap.ios.kt` the way `setTrack` is. **Website:** `map.setPaintProperty('track-repeat-line', 'line-width',
...)` and `setLayoutProperty(..., 'visibility', ...)`. (`JsonStyleOps` is not involved: it edits a style's JSON before the map
loads it and has no live property setter; the trace is not built through it on Android either.) **Proof:** `TrackStyleTest`
pins `trackRepeatLayerJson(look)` and `repeatWidthExpression(look)` (2.7/5.4/9.0 and 1.5/3.0/5.0, `visibility none` for OFF);
`MapScreenLookTest` (`:ui` commonTest, a fake `PlatformMap` recording its parameters) shows that a settings change
re-renders with the new `repeatLook` and the same `track` list instance; the Android layer call is covered by the Roborazzi
screenshots `trace_look_{clear,subtle,off}` (4 languages x 2 themes for the settings card, English light and dark for the
map); the website by `trace-style.spec.ts` with a fake map. **The alert does not depend on the look**: `HuntEngineTest.alertRingsWithLookOff` (and the
web twin) run the engine with `OFF` and the alert on and assert the same alert indexes as with `CLEAR`.

#### 5.27.5 The alert

**Setting:** `repeatAlert`, **off by default**, per device (`AppSettings.repeatAlert`; website `trace.alert`). Label *Warn
me when I walk a path again*; help (phones) *While Hunt mode runs, play a short sound when you follow a path you have
already walked. You can mute it in your phone's notification settings. Needs Trace my path.* Enabled only while *Trace
my path on the map* is on (otherwise disabled with *Turn on Trace my path first.*). Turning it on asks for the
notification permission when it is not yet granted (Android 13 and later, iPhone) with the reason; if the person
refuses, the switch goes back off and says so.

**When it rings:** while Hunt mode runs (phones) or a walk records (website), the trace is on, and the algorithm's
alert test (5.27.3, `RepeatAlert`) passes at a kept point: the live walk has followed paths of other walks for at
least `ALERT_MIN_RUN_M` = 100 m. Only fixes that pass the 50 m gate and are kept points are tested, so the check costs
nothing between points. At most one alert per run and one per 10 minutes (the cooldown), and one bad fix inside a run does
not start a new run (5.27.3), so walking a long street rings once. **Not for the walk just finished:** a walk that ended
less than 30 minutes before this one began (*Finish walk* at a house, then back the way she came) does not count for the
alert, though the Map still marks it (5.27.3; open question 1). It is independent of the house and street alerts of Hunt mode (they have their own cooldowns).

**Android.** `HuntEffects.alertRepeat(runM: Int)`; the platform posts a notification on a **dedicated channel**
`repeat_path` (`Notifications.CHANNEL_REPEAT_PATH`), name *Repeated path*, importance `DEFAULT` (it makes the sound,
no heads-up), default notification sound, no vibration, `setShowBadge(false)`; title *You have walked this way before*,
text *This path is on your map from an earlier walk.*; tap opens the Map; auto-cancel; timeout 2 minutes; lock screen
`VISIBILITY_PRIVATE` with a public version and `VISIBILITY_SECRET` when the app lock is on (S4b-BL-68), exactly as the
Hunt alerts do ([03](03-design.md) §17); the public version is the existing *Doorprints alert* of `CHANNEL_ALERTS`, so no new
string reaches the lock screen. No sound is played by Doorprints itself: the channel's sound is the phone's, so **the person mutes it, or
changes its sound, in Android's own settings** (Settings > Apps > Doorprints > Notifications > Repeated path), and Do
Not Disturb and the media/ring volume rules apply as for any notification. With the app in front the Map also shows
the same sentence as a snackbar. **iPhone:** a local notification with `UNNotificationSound.default`, category
`repeat-path`, thread `repeat-path`, `interruptionLevel .active`; muting is in Settings > Notifications > Doorprints,
and the silent switch and Focus apply. **Pocket and a locked phone:** Hunt mode is a foreground service (Android) and
background location under *When in use* (iPhone), so fixes keep coming and the notification sounds from a pocket or on
a locked screen; this is the case the alert exists for. **Battery:** no new location request, no sensor, no network;
the cost is the detection pass per kept point (milliseconds). **Permissions:** notifications only (already asked for
Hunt mode's alerts); nothing new for location.

**Website.** A banner on the Map page (`role="alert"`, text *You have walked this way before.* (the same sentence as the phones'
notification title), a *Dismiss* button,
auto-hidden after 10 s) and a short beep: two 0.15 s sine tones at 880 Hz, 0.1 s apart, volume 0.3, made with the Web
Audio API (`AudioContext`). Browsers play audio only after the person has interacted with the page, so the
`AudioContext` is created and resumed inside the click of **Start a walk**; if its state is not `running` when an alert
comes (a browser that suspended it), only the banner shows and, where `navigator.vibrate` exists, a vibration of
200-100-200 ms; the settings card says *Sound is off until you start a walk from this page. The note still appears.*
while the context is not running. No Web Notification and no service-worker push (a page that is not open cannot
track, so there is nothing to notify about). The website cannot ring with the screen off (5.27.8).

**Unit test of the alert as an engine rule:** `HuntEngineTest` (phones) drives the engine with the vector file's alert
cases and a fake `HuntEffects` and asserts `alertRepeat` is called at exactly `alertAtIndexes`; the web twin does it on
`TraceRecorderService`.

#### 5.27.6 Ending a walk, saving it, and what a saved walk is

**When a walk ends.** (a) Hunt mode stops (the person, the engine's `stop(reason)`, low battery, permission lost), (b)
*Finish walk* on the Hunt card or the website's Map page (Hunt mode keeps running; the next kept point begins a new
walk id), (c) the website's page closes (below), or (d) **the process dies without a stop** (Android kills the service and
`START_STICKY` restarts it, iOS terminates the app, the website's tab is closed): then `stopped()` never ran, and the next
`reset()` gives the next walk a new id without a question for the old one. So the question is **computed, not stored**, from
a watermark:

**`walkAskedUpTo`** (a walk id, default 0; `AppSettings.walkAskedUpTo`, website `trace.askedUpTo`): the newest walk the *Save
this walk?* sheet has handled. **The walk to ask about** is the newest walk id in `track_points` that is not the live walk's
id, is greater than `walkAskedUpTo`, and has **at least 5 points and 100 m**; a shorter walk is just kept for 30 days
without asking. It is looked for (a) at once after *Finish walk* or a stop from the Map, (b) when the Map opens, and (c) at
Hunt start, before `reset()`, for the walk a restart cut. Any answer or dismissal sets `walkAskedUpTo` to that id, so each
walk is asked once and an unanswered older walk is skipped (kept 30 days). `HuntData` gains `suspend fun lastEndedWalk():
Long?` and `Settings.saveWalkAskedUpTo`; the engine writes no setting itself. `walkAskedUpTo` is a timestamp of a walk: it is
never in `AppSettings.toString` (`repeatLook` and `repeatAlert` are a boolean and an enum and may print, like `pathTrace`).

**The prompt.** The sheet *Save this walk?* shows the walk's distance and minutes and the sentence *Link it to a house
to keep it. Otherwise it stays for 30 days, then it goes. It stays on this phone and is never in a backup or a copy.*
Buttons: **Save with a house**, **Keep for 30 days** (primary: it is the default), **Delete this walk** (confirms:
*Delete this walk? This cannot be undone.*). Dismissing the sheet (back, outside tap) is *Keep for 30 days*. It appears
(a) at once when the person ended the walk with *Finish walk* or by stopping Hunt mode from the Map; (b) otherwise, when
the Map next opens and a walk is to be asked about (Hunt mode stopped by itself, from the notification, was killed, or the
app was closed); each walk is asked once, as the watermark above says.

**The house picker** (*Which house was this walk to?*): a list of the person's live houses (not tombstones). The first
row, **preselected when there is one**, is the house **nearest to where the walk stopped**: the smallest distance from
the walk's last point to a house whose location is not approximate (`LocationSource.APPROX` excluded, as for Hunt
mode's nearest house), shown only when that distance is `<= alertRadiusM` (Settings > Hunt mode's radius, default 30 m;
the website has no such setting and uses 30 m). Under it, the houses within 150 m (`HuntEngine.NEAREST_SHOWN_M`) of any
point of the walk, by their smallest distance, each with that distance; under that, a search box over all houses
(the picker calls the same `HouseSearch.matches` as the list, `HouseListScreen.kt`; a saved walk is **not a house field**, so
the search rule of S4b-FR-1 (search grows with the house values) does not apply and `searchText` does not change). *Save walk* is enabled once a house is chosen. With no houses the picker
says *You have no saved houses yet. Add a house first, or keep the walk for 30 days.* A walk links to **one** house
(open question 3).

**Saving** moves the walk: in one transaction its points are encoded into a `saved_walks` row and its `track_points`
rows (those with its walk id, or the run between its first and last time when old rows have walk id 0) are deleted, so
nothing is stored twice and the 30-day prune cannot touch it. **Limits:** a walk of more than `MAX_WALK_POINTS` = 5 000
points is refused (*This walk is too long to save (more than 5,000 points). It stays for 30 days.*), a house holds at
most 20 saved walks (*This house already has 20 saved walks. Delete one first.*), the device at most 200 (the same with
200). A walk needs at least 2 points to be saved (one point is no walk, on both stacks), the minutes the sheet and the house card show are whole minutes and at least 1 (`max(1, round)`, a 20 s walk is *1 min*), and the walk to ask about compares the unrounded length with 100 m (99.6 m is not 100 m). A refused save changes nothing and the walk stays in the 30-day trace. Size: 5 000 points is about 40 KB
(5.27.7 and [03](03-design.md) §6.2), 200 walks at most about 8 MB; a day's walk is a few hundred points. Nobody reaches 5 000 points on foot (a 20 m step is
250 points for 5 km), and the Map's base line draws a walk over the limit whole anyway; only the saving is refused.

**Where a saved walk shows.** On the **house page**: the card *Saved walks* (newest first; each row *date, distance, minutes*;
*Show on map* opens the Map fitted to the walk, *Delete walk* confirms). On the **Map**: saved walks are drawn with the
trace (the same base line and the same repeat style); *Show on map* from a house page fits the view to that walk and
briefly thickens it (a 2 px halo for 3 seconds, not read as a repeat). Saved walks older than 30 days are drawn too:
they are the person's own history of where she walked. Repeat detection reads **all** walks, saved included, whether
or not a walk is on screen. In Settings: *Saved walks: n* and *Delete all saved walks*.

**Deleting.** *Delete walk* removes the row at once. *Delete all saved walks* confirms with the count. *Clear the path*
removes only the 30-day trace (its hint says so). Turning the trace switch off keeps everything (as today).
**The house is deleted:** a saved walk is the house's; on the phones, deleting a house leaves its tombstone (a delete is
never a hard delete there), and its saved walks are **hidden from the Map, the house page and the detection at once**
and **deleted when the delete is final**: when *Undo*'s snackbar (10 s, `offerDeletedHouseUndo`) closes without *Undo*,
or at the next app start, Hunt start, sync end or import end, whichever comes first (`sweepWalksOfDeletedHouses`: every
saved walk whose house is a tombstone or missing). The sweep after the snackbar's `Dismissed` runs under `NonCancellable`
(like `restore()`): `offerDeletedHouseUndo`'s coroutine is cancelled when the screen is left, and a missed sweep is caught
by the next trigger. The other places that tombstone houses are an import that deletes (`CommonRepository`, `deleteHouse(id)`
inside the import) and `CopyUndo`; both end in "import end", and the test covers them by name. *Undo* inside the 10 s brings the house back **with** its walks
(they were only hidden). The house form's delete confirmation says *Its saved walks are deleted too.* The website has no
undo: `LocalStore.deleteHouse` deletes the house's saved walks in the same operation. A house tombstone that arrives by
sync or import (a delete on another device) is swept the same way; **a saved walk never outlives its house**. *Delete all
my data* (phones) and *Remove all Doorprints data from this browser* (website) remove saved walks and the trace.

**The 30-day prune** (`pruneTrack`, at each Hunt start and now also at the Map's opening and at each walk's end)
deletes `track_points` older than 30 days and never reads `saved_walks`. *Amended:* it used to run at Hunt start only.

#### 5.27.7 Data and privacy (see [03](03-design.md) §6.2, §7.2a, ADR-34; [02](02-threat-model.md) T-I30)

Two stores on each device, both **local only**: the 30-day trace (`track_points`; web `trace_points`), and the saved
walks (`saved_walks`; web the same name). Neither is in: the JSON backup or the six readable copies, a *Save a copy*
file or an update file (`ExportBundle.build`, `LocalRows`), the Google Drive backup or sync (`DriveBackupService`,
`DriveSyncBackend`, the website's `drive-backup.service.ts`, `sync-file.ts`), the server sync (`ServerSyncBackend`, the
website's `sync.service.ts`), the weekly backup (`AutoBackupWorker`), the shared-listing files, an AI request
(`AiHouse`, `HouseDocuments`, the website's `core/ai/**`), a log or a crash report. **A phone set up from a backup, a copy, Drive or the server starts without saved walks**, and the
guide says so. **Android's own phone-to-phone transfer (cable or Wi-Fi at setup) is the one exception: it copies the whole
Doorprints database, walks included, because it is the phone's own copy and not a backup** (`data_extraction_rules.xml`:
cloud backup excludes everything, device transfer includes the database on purpose so houses and photos move; Room tables
cannot be excluded one by one, and nothing can tell a transfer from an upgrade at first start). The iPhone is clean:
`IosDataDirectory` excludes the directory from iCloud and computer backups. What the person can do: before a cable transfer,
*Clear the path* and *Delete all saved walks* (Settings > Hunt mode; a hint under the button says so on Android), or set the
new phone up from a backup. A person who wants a walk to survive a phone change has no way to; that is the price of "phone
only" (PRV-028; [02](02-threat-model.md) T-I30, RR-31). The settings `repeatLook`, `repeatAlert`, `walkAskedUpTo` and `trace.keepAwake` are local
preferences like `pathTrace`: never exported. Whoever opens an unlocked phone sees the walks, and the recent-apps thumbnail or a screenshot of the Map with its
dashes or of the house page with its walk rows shows where she walked (T-I29's residual; the app lock covers the first). The house picker never leaves the device. **No log line, breadcrumb or crash text holds a walk id, a point or a count**
(`Log.` and `breadcrumb(` in the trace package; `HuntService` never logs fixes; `IosHunt`'s `breadcrumb()` logs stop reasons
only); a source test greps for it (TC-U-151). The reference points one way: `saved_walks.houseId` names a house, a house
never names a walk, so a house in a sync, a share or an AI request leaks no walk. A saved walk is location history linked to a house, so a
**house page screenshot** or the readable copy of a house (which does not include it) must not mention walks: the
copy writers do not read `saved_walks`, and the test of 5.27.7a asserts it.

*5.27.7a What a test must assert* (TC-U-151 in [06](06-test-plan.md)): that no export or sync path reads the two
stores, by (1) a backup and a copy of every format built from a database with walks have no coordinate of a walk (a
unique marker coordinate is searched in the bytes, the ZIP entries inflated); (2) the invalidation tracker that feeds
`localRowsFlow` (`db.localTablesChanged()`) does not list `track_points` or `saved_walks`; (3) the Drive backup payload
and the sync file built from a world with walks contain neither; (4) a source test lists the DAO classes `TrackDao` and
`SavedWalkDao` and fails when a file under `shared/export`, `drive`, `data/ServerSyncBackend.kt`, `shared/ai` or
`app/.../export` refers to them; the website twin greps the store names `trace_points` and `saved_walks` in
`web/src/app/export`, `web/src/app/data/drive`, `web/src/app/core/ai` and `sync*.ts`.

#### 5.27.8 The website

The website records a walk with the browser's **Geolocation API** (`navigator.geolocation.watchPosition`), **only
while the page is open and visible**. Browsers do not track a page in the background: when the screen locks, the tab is
hidden or the app is closed, the page gets no more fixes (a phone browser may keep a few). The page says so, in plain
words, wherever a walk starts and while it records: *Your browser records only while this page is open and visible.
Keep this page open while you walk.* and, when the page becomes hidden mid-walk, on return: *Recording paused while
this page was hidden.* This is not Hunt mode: there are no house or street alerts on the website; the only alert is the
repeated-path one.

- **Start and finish.** The Map page's *Trace my path* card has the same switch as the phones (`trace.on`, off by
  default); when on, the Map shows **Start a walk**. Pressing it (a user gesture) explains the permission in one
  sentence (*To record your walk, your browser will ask for your location. It is used only on this page, while it is
  open, and kept only in this browser.*) and then calls `watchPosition` with `{ enableHighAccuracy: true, maximumAge: 0,
  timeout: 30000 }`; the browser asks for its own permission. The permission is never asked on page load. While a walk
  records, the button reads **Finish walk**, with *Recording your walk*. Denied: *Location is blocked for this site.
  Allow it in your browser's site settings to record a walk.* No Geolocation: *This browser cannot give your location.*
  The page must be served over HTTPS (it is).
- **Same constants.** The 50 m accuracy gate, the 20 m or 5 minutes thinning (`TraceRecorder.accept`, a TypeScript twin
  of `TrackRecorder`, same tests), the 30-minute gap, the repeat algorithm and the alert are the ones of 5.27.2 and
  5.27.3. Walk ids and `reset` follow *Start a walk* and *Finish walk*; a closed page ends the walk (the next *Start a
  walk* is a new id, and the walk to ask about is found by the watermark of 5.27.6, so a closed tab is not forgotten). The id
  is the `atMs` of the walk's first *kept* point (a walk that never gets a fix has no id and no row). **A page hidden for more
  than 5 minutes** (`PAUSE_SPLIT_MS`; the screen locked, another tab) and visible again: the same walk continues, but the first
  point after it is stored with `resumed = true`, so **no straight line is drawn or matched across the pause** (5.27.3 step 3:
  a leap over a neighbourhood nobody walked would draw a path never walked and could create false repeats); a pause of 5
  minutes or less keeps the line; a gap of 30 minutes or more is a new walk, as before (open question 7).
- **Screen Wake Lock (optional).** *Keep the screen on while I walk* (`trace.keepAwake`, off by default, shown only where
  `navigator.wakeLock` exists): requested at *Start a walk*, released at *Finish walk*, requested again when the page
  becomes visible (the lock is released when it is hidden). Hint: *Uses more battery. Without it, the screen may lock
  and recording stops.* iPhone Safari supports it from iOS 16.4 and a home-screen web app only from a later release;
  where it fails the setting says *Your browser could not keep the screen on.* and nothing else changes.
- **iPhone Safari and the installed website:** Geolocation works in the foreground only; a locked screen or another
  app stops fixes; no background, no wake lock where unsupported. The beep needs the page's `AudioContext` started by the
  *Start a walk* tap (the silent switch mutes web audio too).
- **Storage.** IndexedDB, database `doorprints`, version 2 to 3 (`DB_VERSION = 3`, `upgradeLocalDb` step `oldVersion < 3`):
  new object stores `trace_points` (key `id`, a string `"<walkId>-<atMs>"`, where the walk id is the first kept point's `atMs`; a row
  carries `resumed` when true; index `walk`) and `saved_walks` (key `id`;
  index `houseId`). `STORE_NAMES`, `STORE_KEY_PATH`, `STORE_INDEXES` and `MemoryDb` gain them; `db.clear()` already
  empties every store, so *Remove all Doorprints data from this browser* clears them. Points are written one by one as
  they are kept (a closed tab loses nothing but the last point). Walks are stored with the same quantisation as the
  phones (microdegrees as integers, seconds from the walk's start), as plain arrays (no BLOB codec on the website).
  Where IndexedDB is blocked (`MemoryDb`), a walk lives for the page only and the card says nothing is kept.
  **A shared computer:** IndexedDB is per browser profile, and *Remove all Doorprints data from this browser* clears it; the
  Map card says *Other people using this browser profile can see your walks.* The service worker caches the app shell and
  hashed build files only; the tiles that go through `addProtocol` reveal where the map was looked at, not where she walked.
- **Privacy rule:** exactly 5.27.7: not in the export (`export/backup-export.ts` and the other writers), not in the Drive
  backup or sync (`drive-backup.service.ts`, `sync-file.ts`, `drive-sync-backend.ts`), not in the server sync
  (`sync.service.ts`, `sync-backend.ts`), not in an AI request (`core/ai/**`); the only network use is the map's tiles, as
  today.

#### 5.27.9 Strings (English; hi, ta and te are translated by the lead and shipped *under review*)

Key rule: a website key is `trace.<name>` in `i18n/en.ts`; the Android and iPhone resource is the same name with dots and
capitals turned into underscores (`trace.repeatLook.title` is `trace_repeat_look_title`). `{n}`-style placeholders are
the website's `{name}` and the phones' `%1$s` in the order given. Brand words: *Save a copy*, *Import a backup* stay as
they are; *Restore* is never a button.

| Key | English |
|---|---|
| `trace.repeatLook.title` | How repeated paths look |
| `trace.repeatLook.hint` | Paths you have walked more than once can stand out on the map. This does not change the sound alert. |
| `trace.repeatLook.clear` | Clear |
| `trace.repeatLook.clearDesc` | Thicker and dashed in a second colour |
| `trace.repeatLook.subtle` | Subtle |
| `trace.repeatLook.subtleDesc` | Normal width, dashed in a second colour |
| `trace.repeatLook.off` | Off |
| `trace.repeatLook.offDesc` | Drawn like any other path |
| `trace.alert.title` | Warn me when I walk a path again |
| `trace.alert.hint` | While Hunt mode runs, play a short sound when you follow a path you have already walked. You can mute it in your phone's notification settings. Needs Trace my path. |
| `trace.alert.hintWeb` | While a walk is recording, play a short beep and show a note when you follow a path you have already walked. Works only while this page is open. |
| `trace.alert.needsTrace` | Turn on Trace my path first. |
| `trace.alert.permissionDenied` | Notifications are off for Doorprints, so there is no sound. Allow them in your phone's settings. |
| `trace.alert.channel` | Repeated path |
| `trace.alert.channelDesc` | A short sound when you walk a path you have walked before. |
| `trace.alert.notifTitle` | You have walked this way before. |
| `trace.alert.notifText` | This path is on your map from an earlier walk. |
| `trace.alert.notifPublic` | Doorprints alert (the existing public text of the Hunt alerts; the key is the Hunt alerts' own, no new string) |
| `trace.alert.banner` | You have walked this way before. |
| `trace.alert.dismiss` | Dismiss |
| `trace.alert.soundOff` | Sound is off until you start a walk from this page. The note still appears. |
| `trace.legend.title` | Your paths |
| `trace.legend.once` | Walked once |
| `trace.legend.repeated` | Walked more than once |
| `trace.a11y.map` | Paths you walked more than once are dashed. |
| `trace.walk.start` | Start a walk |
| `trace.walk.finish` | Finish walk |
| `trace.walk.recording` | Recording your walk |
| `trace.walk.onlyOpen` | Your browser records only while this page is open and visible. Keep this page open while you walk. |
| `trace.walk.paused` | Recording paused while this page was hidden. |
| `trace.walk.keepAwake` | Keep the screen on while I walk |
| `trace.walk.keepAwakeHint` | Uses more battery. Without it, the screen may lock and recording stops. |
| `trace.walk.keepAwakeFailed` | Your browser could not keep the screen on. |
| `trace.web.permissionExplain` | To record your walk, your browser will ask for your location. It is used only on this page, while it is open, and kept only in this browser. |
| `trace.web.denied` | Location is blocked for this site. Allow it in your browser's site settings to record a walk. |
| `trace.web.unavailable` | This browser cannot give your location. |
| `trace.web.shared` | Other people using this browser profile can see your walks. |
| `trace.end.title` | Save this walk? |
| `trace.end.summary` | Walk of {distance} in {minutes} min |
| `trace.end.body` | Link it to a house to keep it. Otherwise it stays for 30 days, then it goes. It stays on this phone and is never in a backup or a copy. |
| `trace.end.bodyWeb` | Link it to a house to keep it. Otherwise it stays for 30 days, then it goes. It stays in this browser and is never in a backup or a copy. |
| `trace.end.save` | Save with a house |
| `trace.end.keep` | Keep for 30 days |
| `trace.end.delete` | Delete this walk |
| `trace.end.deleteConfirm` | Delete this walk? This cannot be undone. |
| `trace.pick.title` | Which house was this walk to? |
| `trace.pick.nearest` | Nearest to where you stopped, {distance} away |
| `trace.pick.near` | {distance} from your walk |
| `trace.pick.search` | Search your houses |
| `trace.pick.none` | You have no saved houses yet. Add a house first, or keep the walk for 30 days. |
| `trace.pick.confirm` | Save walk |
| `trace.saved.snack` | Walk saved with {house}. |
| `trace.kept.snack` | Walk kept for 30 days. |
| `trace.deleted.snack` | Walk deleted. |
| `trace.save.tooLong` | This walk is too long to save (more than {max} points). It stays for 30 days. |
| `trace.save.houseFull` | This house already has {max} saved walks. Delete one first. |
| `trace.save.allFull` | You have {max} saved walks. Delete one first. |
| `trace.save.failed` | Could not save the walk: {reason} |
| `trace.house.title` | Saved walks |
| `trace.house.row` | {date}, {distance}, {minutes} min |
| `trace.house.show` | Show on map |
| `trace.house.delete` | Delete walk |
| `trace.house.deleteConfirm` | Delete this saved walk? |
| `trace.house.empty` | No saved walks. When you finish a walk, you can link it to this house. |
| `trace.house.local` | Saved walks stay on this phone only. They are not in a backup, a copy, Google Drive or on a server. |
| `trace.house.localWeb` | Saved walks stay in this browser only. They are not in a backup, a copy, Google Drive or on a server. |
| `trace.houseDelete.note` | Its saved walks are deleted too. |
| `trace.settings.saved` | Saved walks: {n} |
| `trace.settings.deleteAll` | Delete all saved walks |
| `trace.settings.deleteAllConfirm` | Delete all {n} saved walks? This cannot be undone. |
| `trace.settings.transferNote` | Android's phone-to-phone transfer also copies saved walks. To leave them behind, delete them before you transfer. (Android only.) |
| `trace.settings.clearHint` | Clears the walks kept for 30 days. Saved walks stay until you delete them. |

The strings of the on-demand place check (*Have I been here?*) are in 5.27.13, keys `trace.here.*`.

The existing `settings_path_trace_hint` gains *Saved walks, if you save any, stay here too.* in the same change; its
current text ("never in a backup, a copy or on the server, and gone after 30 days") is made true for both stores by
5.27.6.

#### 5.27.10 Tests and tickets

Tests, as built (the class names and files are in [06](06-test-plan.md) TC-U-147..TC-U-154): TC-U-147..TC-U-152, TC-M-57..TC-M-60 and the manual entries MT-75..MT-80 (and, for the place check of 5.27.13, TC-U-153, TC-U-154, TC-M-61 and MT-81) of
[ops/manual-test-checklist.md](ops/manual-test-checklist.md); the TC-M and MT entries are owner device checks and are **still open**. **Where the vector tests run:** `commonTest` has no file API, so
`TraceVectorsTest` (all 58 cases: split, repeats, alert and `placeChecks`; it replaced the planned `RepeatDetectorVectorsTest` and `PlaceCheckVectorsTest`) is in `:shared` **`androidHostTest`** on the JVM and walks up from the working directory to
`docs/schemas/trace-repeat-vectors.json`, as `DriveVectorsTest` does for the Drive vectors; a small `RepeatDetectorTest` in
`commonTest` with six inline cases (same street, junction, bridge, gap split, alert 100 m, cooldown) lets the iOS simulator
job execute the common code too; the website's `trace-repeats-vectors.spec.ts` reads the file as `drive-vectors.spec.ts`
does. The two engineers (Kotlin and TypeScript) do not read each other's code until both pass the vectors, and the pull
requests name who wrote which. The new files (`trace/*.kt`, `trace-repeats.ts`, `trace-store.ts`,
`trace-recorder.service.ts`) start with the licence notice: `python3 .github/scripts/licence-headers.py --fix` before the
commit. Tickets: [10](10-sprint-log.md) §15, S4b-FR-13..S4b-FR-18.
Requirements: [01](01-requirements.md) FR-102..FR-107 and PRV-030, PRV-031 (PRV-028 amended); the place check: FR-108, PRV-032
(5.27.13, S4b-FR-24). The proposed website Hunt mode follow-up is 5.27.12 (S4b-FR-19..S4b-FR-23, not scheduled; its requirements are FR-109 and PRV-033).

#### 5.27.11 Open questions (each with the recommended answer; none is silently assumed)

1. **Is an out-and-back inside one walk a repeat?** As written, no: "different walks" means separated by a 30-minute
   gap, a stop or *Finish walk*, so retracing a street within one walk, which is normal when looking for a house
   number, is not marked. *Recommend: keep as written* (the senior review agrees for the Map). The alternative (a repeat within one walk when the
   person returns after more than N minutes) can be added later without changing the vectors. **For the alert the review
   found a different case and the design applies its recommendation, the owner may overrule:** after *Finish walk* at a
   house, walking back the way one came is a different walk, so the alert would ring on the way back. The alert therefore
   leaves out a walk that ended less than 30 minutes before the live walk began (5.27.3, vectors
   `alert-not-for-the-walk-just-finished` and `alert-walk-finished-30-minutes-ago-counts`); the Map still marks the street.
   *Overrule = delete that exclusion sentence and the two vectors.*
2. **A deleted house's saved walks: removed when the delete is final** (Undo window, next start, sync end), hidden
   meanwhile. *Recommend: as written* (the sweep after the Undo snackbar runs under `NonCancellable`). The alternative, removing them at
   the tap and losing them on Undo, is simpler but makes Undo lossy.
3. **One house per walk.** A walk that visited three houses is saved once, for one. *Recommend: one in v1;* a later
   "also link to..." can add rows pointing at the same points.
4. **The second colour `#E65100`**: checked by simulation on 2026-10-06 (5.27.4: contrast 3.79:1; CIEDE2000 48.5 normal, 57.1
   protanopia, 59.1 deuteranopia, 26.3 tritanopia; verdict pass). *Recommend: keep it;* TC-M-57 (4) stays as the device check,
   and the colour changes in one place if it fails. Its one weakness (orange against the amber and star markers under
   protanopia, told apart by form only) is stated in 5.27.4.
5. **Saved walks always draw on the Map** (and take part in detection) whatever their age. A *Show saved walks* switch
   would let the person tidy the map. *Recommend: no switch in v1;* add it if the map feels crowded, computing
   detection on all walks and drawing only the chosen.
6. **A third level for walked three times or more.** *Recommend: no;* two cues (dash, colour) are enough, a third
   would need a third colour.
7. **The website's walk when the tab is hidden.** On the website a hidden page is the normal case (the screen locks), and a
   straight line across a neighbourhood draws a path never walked and can create false repeats. *Recommend (the senior
   review's, adopted in 5.27.3 step 3 and 5.27.8): split the walk's line across a pause of more than 5 minutes* (the same walk
   id, a `resumed` flag on the next point, no segment across it; the vector `web-pause-makes-no-segment`); on the phones a
   gap of 29 minutes still draws a straight line, as today.
8. **Web notifications for the alert** (Notification API with the page open but another tab in front). *Recommend: no
   in v1:* a page in a background tab is throttled and gets no fixes either.
9. **Where the website's settings live.** *Recommend: the Map page's *Trace my path* card* (the actions are there); *Your
   data* gets only *Saved walks: n* and *Delete all saved walks*.
10. **Prune at the Map's opening** (new) so a person who never starts Hunt mode again still sees 30 days at most.
    *Recommend: yes, as written.* (`trackPoints` already reads only the last 30 days, so this is storage hygiene.)
11. **`MIN_RUN_M` of 80 m and `TOLERANCE_M` of 25 m** are reasoned, not measured. *Recommend: ship, then check on the
    owner's own walks (TC-M-57, which includes two parallel lanes about 30 m apart) and adjust the two constants together with
    the vectors; the fallback is `TOLERANCE_M = 20`.* The review's simulations: two 400 m passes of one street with an error of
    5 to 8 m a side are one stretch every time; at 12 m the dashes split into two stretches in 31 of 200 trials and at 15 m in 81
    (a bad GPS day shows a dashed street in pieces: acceptable); two lanes 30 m apart light up about half the time at 8 m error.
12. **Room version 11** is the next number at `main` `4dd94a3f` (confirmed by the review: `AppDatabase.version = 10`; IndexedDB 3);
    another branch may take it first. *Recommend: the implementer reads `AppDatabase.kt` at the start and takes the next free
    number, updating 03 and this section.*
13. **A speed gate for the alert** (not while driving: no alert when the last two kept points imply more than 12 km/h) is cheap and
    avoids a bus ride ringing through a known street. *Recommend: not in v1; ask the owner whether it is wanted.*
14. **Later, not this batch** (backlog candidates, none scheduled): the houses passed listed on a saved walk's row; a *Streets not
    walked* view (needs the road network, which the app has not offline: park); naming a walk or linking one walk to several
    houses (question 3); a *Show saved walks* switch (5) or a third level (6); exporting one saved walk as GPX on request (against
    "phone only": park until asked); quiet hours for the alert; a *new area* cue (the inverse of the alert: not asked for).

15. **The place check's second band and its weekday** (5.27.13). *Decided 2026-10-06 (owner: go with the recommendations of [ops/path-trace-check-review.md](ops/path-trace-check-review.md)): as recommended.* *Recommend: keep the 25 to 50 m* `CLOSE` *answer* (a house's saved spot is itself 10 to 30 m
    off the door; *no* for a walk 30 m away would be wrong more often than *close*); the owner can drop it by deleting the `CLOSE` status and the four vectors that
    use it (`pc-25-1-m-away-is-close-not-walked`, `pc-49-9-m-away-is-close`, `pc-30-m-past-the-end-is-close`, `pc-gap-between-two-walks-is-close-to-the-nearer`).
    The dates carry a short weekday (the owner's example); the alternative is the existing medium date with no weekday and no new helper option.
16. **The live walk is left out of *Here* only** (5.27.13). *Decided 2026-10-06: as recommended.* *Recommend: as written.* The alternative (also leave it out of a house or a spot) would hide that
    she walked past this house earlier in the same walk.
17. **A house with an approximate location** gets a note and no answer. *Decided 2026-10-06: as recommended.* *Recommend: as written;* the alternative (compare with the area's centre) would answer
    with a distance that means nothing. Also decided, say if wrong: the check keeps **no history** of past checks, and there is **no sound, no notification and
    no automatic check on arriving at a house** (that would be Hunt mode's job, and would be a background use the owner did not ask for).

18. **A one-press *Mark the houses I walked past* on the house list** (several houses at once). *Decided 2026-10-06: not in S4b; LATER.* It is the check run for every
    house without a press on a house, which PRV-032's *on demand only* forbids by construction for any automatic form (a *Walked past* filter or badge), and a stored badge
    would be a fact derived from location history written into a house row, which sync, export, share and AI would then carry (PRV-028 broken by a flag). If it is wanted
    later: an explicit button that runs once, shows transient chips that are never stored, and clears on leaving the page; the owner decides after S4b-FR-24 has been used.

#### 5.27.12 Hunt mode on the website (proposed follow-up; status *proposed, not scheduled*)

Owner question of 2026-10-06: can Hunt mode itself come to the website, accepting that it works only while the page is open and
visible? The senior review ([ops/path-trace-spec-review.md](ops/path-trace-spec-review.md) 9) answered it as below. **Nothing
here is built or scheduled; the tickets S4b-FR-19..S4b-FR-23 ([10](10-sprint-log.md) §15) are written so the owner can say yes
or no.** It would follow the website's trace (S4b-FR-17), which already builds every seam it needs (`TraceRecorderService` with
`watchPosition`, visibility, wake lock, the beep and banner, the permission flow, the Map page card).

What a browser can and cannot do: `watchPosition` delivers fixes only while the page is visible (Android Chrome throttles a hidden
tab and stops when the screen locks; iOS Safari and a home-screen web app stop at once); there is no background geolocation and no
geofencing API. Screen Wake Lock keeps the screen on where it exists. A notification can be *shown* from the open page but nothing
can compute in the background to decide to show one. Web Audio needs a gesture; iOS mutes it with the silent switch.

| Hunt mode behaviour | On the website, page open and visible | Verdict | What the person must be told |
|---|---|---|---|
| Fixes (15 s walking, 60 s staying) | `watchPosition`; the browser sets the rate; the engine's thinning applies | Works (rough on computers without GPS) | *Works only while this page is open and visible. A computer without GPS gives a rough location.* |
| Accuracy gate 50 m, nearest-house card | `coords.accuracy`; the same rule | Works | |
| Near-house alert | Same rule; banner (`role="alert"`) and beep; an optional notification while the page is open | Works while visible | *No alert when the screen is locked or another app is in front.* |
| Street alert | Needs automatic reverse geocoding: Nominatim allows 1 request a second and the app calls it today only from a button press | **Defer** (owner decision 2) | *Street alerts are not on the website.* |
| Stay to visit, *Are you at a house?* | `StayDetector` is pure; a visit is an ordinary row | Works while visible (a 4-minute stay with the screen locked is missed unless the wake lock is on) | *Keep the screen on, or the visit may not be noticed.* |
| Battery stop at 15 % | Battery Status API is Chrome-only | Degraded | *Your browser does not tell the page the battery level.* |
| Path trace, repeats, alert | 5.27.8 | Works while visible | As 5.27.8 |
| Reminders with *Start Hunt mode* | The reminder can carry a link that starts it on a tap | Degraded: shown only while the website is open | *Reminders show only while the website is open.* |
| Area wake-up (geofences), pocket or locked screen | No web geofencing, nothing runs | **Cannot** | *Area wake-up is a phone feature.* The sentence of 5.27.8. |
| Ongoing notification with *Stop*, app lock | No foreground service; the page's own card with *Stop*; the website has no app lock | Replaced by the card | |

**Recommended shape if built:** a *Hunt mode (while this page is open)* switch on the Map page's card, session only and never
persisted (a reload starts it off), with the alert radius and minimum stay as local settings; the rules shared as a second
vector file `docs/schemas/hunt-vectors.json` (near-house alert, stay detector, gate, thinning) that `HuntEngineTest` and the
website's `hunt-rules.ts` both run; alert state in memory only; fixes stored only as trace points (5.27.7); visits as ordinary
rows; the location permission only at the switch's tap (PRV-031 extended); no Web Push, no server; the page stops Hunt mode
after 30 minutes hidden and says so; accessibility as 5.27.8 (state in text, `role="alert"`, `aria-live="polite"`, a banner as
the second cue after the sound). New requirements if the owner says yes: FR-109 and PRV-033 (FR-108 and PRV-032 are the place check, 5.27.13); one new line in T-I30.

**Owner decisions needed first (recommendations):**

1. **Build it at all?** The phone app does this in a pocket; the website cannot. *Recommend: yes, as a small follow-up after
   S4b-FR-17, because the rules are already pure; the owner's call.*
2. **Street alerts and Nominatim.** An automatic lookup every 45 s sends the person's coordinates to Nominatim all the time she
   walks, which is against the project's data-minimisation stance and a new kind of use of a free public service. *Recommend:
   **not in v1**; the website has no street alert (S4b-FR-22 records the decision either way).*
3. **A visit's source.** *Recommend: `VisitSource.AUTO` as on the phones, with the same accuracy gate,* rather than a new
   `AUTO_WEB` to tell a rougher laptop fix apart.

#### 5.27.13 *Have I been here?*: an on-demand check of a place against the person's walks (owner request of 2026-10-06; built on `feat/path-trace-v2`, PR #146, not yet merged)

**The ask** (owner): *"Can we compare the location with the trace on click of a button so that the user has an option to check
whether they visited it?"* The answer is a button, never a background job: the person picks a place, presses, and reads in plain
words whether she walked there, when and how close. **Requirements:** [01](01-requirements.md) FR-108 and PRV-032. **Ticket:**
S4b-FR-24 ([10](10-sprint-log.md) §15). **Tests:** TC-U-153, TC-U-154, TC-M-61, MT-81. **Threat:** [02](02-threat-model.md) T-I42.
It reads what 5.27.2..5.27.7 already store; it adds **no store, no setting, no permission of its own and no network request**.

**Which place** (the three sources; a *place* is one point, `(lat, lon)`):

| Source | What the person does | Where the point comes from |
|---|---|---|
| **Here** | Map screen > **Have I been here?** > *Where I am now* | One fresh location fix, asked the usual way (below). |
| **A house** | House page > **Did I walk past this house?** | The house's saved location. A house whose `LocationSource` is `APPROX` has no exact spot (it is an area): the button answers *This house has no exact spot yet, only an area. Place it on the map first, then check.* and compares nothing. A house with no location at all hides the button, and so does a house not yet saved (the website's `/houses/new`). |
| **A spot on the map** | Phones: **long press** on empty map > *Did I walk here?* (the menu that already offers *Save house here*). All three (website included): Map screen > **Have I been here?** > *A spot on the map* enters the crosshair mode of the add-a-house flow (A11Y-B02: the accessible way, no long press needed), with *Check this spot* instead of *Place here*. | The map's centre under the crosshair, or the long-pressed point. A plain tap is not used: it selects houses. |

**Where the buttons live.** The **Map screen** action: a button in the Map's action column next to the location button (phones,
`:ui` common) and in the website's map toolbar, label *Have I been here?*, with a footprints icon, a 48 dp target and the same text
as its content description. It is shown **whether or not the trace switch is on and whether or not Hunt mode runs**. **No new
setting** (the check is a button, so it needs no switch; FR-103's *How repeated paths look* does not change it). The **house page**
button sits in the location card, under the address, next to the *Saved walks* card (5.27.6). The long-press menu is a small
popup of two rows. The result is a **bottom sheet** on the phones and a **panel** on the website (below).

**Exact semantics** (the contract that Kotlin, `app.doorprints.shared.trace.PlaceCheck.check`, and TypeScript,
`web/src/app/shared/trace-place-check.ts`, share, written from this text and held to the `placeChecks` section of
[`docs/schemas/trace-repeat-vectors.json`](schemas/trace-repeat-vectors.json), status *confirmed*, 21 cases). Pure function: no clock, no I/O.

*Inputs.* `place` (lat, lon); `walks`: a list of walks, each already split (5.27.3 step 1; saved walks come back from
their codec as walks) with `TracePoint(lat, lon, atMs, walkId, resumed)` and a `source` of `TRACE` or `SAVED`; `fixAccuracyM`: only
for the *Here* source, the fix's reported accuracy, absent for the other two. **The walks are every walk stored**: the 30-day
trace as the store returns it (`trackPoints` reads 30 days; the check never reads further back) **and every saved walk, whatever
its age**, **whether or not the trace switch is on** (turning the trace off keeps what is stored, 5.27.6, and the check reads
it). It does **not** apply `MAX_DETECTION_POINTS`: the check is one pass over the segments, with no pairwise cost, and on the phones it reads the saved walks one at a time (each is decoded, compared and let go; only a walk with a row is kept for the matched stretch).
The website has no trace until S4b-FR-17's store exists, so on the website nothing of the check lands before FR-17; the `placeCheck`
function itself needs only the shared plane and distance (`shared/trace-geo.ts`), which S4b-FR-13 writes first and both algorithms use.
**A trace walk whose walk id equals a saved walk's id is left out** (a save that was cut between its two writes on the website); the store's
save deletes the trace rows and writes the saved row in one transaction where the platform has one (the phones' Room does; the website's
`LocalDb` gets a two-store transaction, [03](03-design.md) §6.2b).

*Constants* (added to 5.27.2's table and to the vector file's `constants`): `TOLERANCE_M` = 25 (the same constant as the repeat
corridor, inclusive); **`NEAR_BAND_M` = 50**; **`MAX_FIX_ACCURACY_M` = 50** (the Hunt gate, `HuntState.MAX_ACCURACY_M`).

1. **Gate.** A place whose latitude or longitude is not finite or outside [-90, 90] / [-180, 180] gives `INVALID_PLACE` (a corrupt house location; the
   words *This spot has no valid location.*). With `fixAccuracyM` given: if it is not finite, is negative, or **is greater than 50**
   the answer is `IMPRECISE` and nothing is compared (*Location not precise enough. Try again outdoors.*); exactly 50 passes, as in Hunt mode.
   The result carries `fuzzy = fixAccuracyM > TOLERANCE_M` for every status but `IMPRECISE` and `INVALID_PLACE` (the sheet shows the line only with a `WALKED`, `CLOSE` or `NONE` answer): an accepted but loose fix, which adds the line *Your location is
   only accurate to about {n} m, so this answer may be off.* The accuracy is **never added to the tolerance** (a loose fix does not
   make "walked" easier; it only warns). `fuzzy` uses the same tolerance constant the *walked* test uses; the tolerance can be overridden in unit tests only (it is not a setting), and then both follow it.
2. **Distance, per walk.** For each walk, the distance from the place to the walk's polyline: the minimum over its segments of the
   point-to-segment distance, the segment clamped at its ends (a segment of length 0, a stay, takes `t = 0`; so each end has a round cap, as in step 4), on the same local flat
   plane as step 2 centred on the place (`x = (lon - place.lon) * cos(rad(place.lat)) * K`, `y = (lat - place.lat) * K`).
   **The walk's original segments, not its densified samples, and no segment into a resumed point** (a page-hidden pause draws and
   matches nothing: the vectors `pc-the-middle-of-a-pause-is-not-walked` and `pc-after-the-pause-the-walk-counts-again`). **One
   result per walk**, not per segment: a walk that goes up a street and comes back near the place gives one row, for its nearest
   point (`pc-out-and-back-in-one-walk-is-one-row`). The row's time `atMs` is the **time at that nearest point**, interpolated along its
   segment, `a.atMs + floor(t * (b.atMs - a.atMs) + 0.5)` for the clamped fraction `t` (a long walk passes a place at one moment, not
   "on the day it began"). Ties on distance keep the first segment found.
3. **No result from a fragment.** A walk with **fewer than two points, or no segment at all** (all its points but one resume) gives no row and is
   **not counted as a walk** for the empty answer (`pc-a-fragment-of-one-point-is-no-walk`); a stray point is not a path.
4. **Bands.** A row is kept when its distance is `<= NEAR_BAND_M`. Its **band** is `WALKED` when `<= TOLERANCE_M` (inclusive: **25.0 m
   counts**) and `CLOSE` when above 25 and at most 50. Boundaries are inclusive; the vectors keep 0.01 m clear of them (hygiene), so the
   inclusive 25.0 is pinned by a unit test that passes the computed distance itself as the tolerance, not by a vector.
5. **Status**, in this order: `INVALID_PLACE`, `IMPRECISE`, `EMPTY` (no walk with at least one segment: the empty state),
   `WALKED` (a row of band `WALKED`), `CLOSE` (rows, none `WALKED`), `NONE` (walks exist, no row).
6. **Order.** Rows newest first by `atMs`; a tie by the later input index (the vector `pc-newest-first-even-when-the-older-walk-is-nearer`:
   never by distance). `nearestM` is the smallest row distance, or none.

**Why a second band (25 to 50 m, `CLOSE`) and not only a yes or no.** A *Did I walk past this house?* question compares **two noisy
things**: the walk (kept fixes pass the gate at up to 50 m) and the house's saved spot (a tap on the map or a phone fix at the
gate, often 10 to 30 m from the door; the app's own map pin is rarely on the exact door). A hard 25 m wall says *no* to a person who
walked past the next gate. Reporting the 25 to 50 m walks separately, in different words (*No walk of yours passed within 25 m,
but one came within 41 m*), is honest, never says *walked* for what might be the next lane (5.27.2 keeps lanes 30 to 60 m apart as
different paths), and costs one comparison. 50 m is the accuracy gate: a walk farther than that is below what the readings can tell. The band
changes no repeat result (the repeat algorithm does not read it). *Recommend: keep it* (open question 15 lets the owner drop it by
deleting the `CLOSE` status and four vectors).

**Getting *here*: one fix, with the usual permission flow.**
- **Android and iPhone:** the call the Map's location button already uses (foreground only; no background permission, no new
  manifest or `Info.plist` entry). The permission is asked the usual way (the rationale, then the system dialog) and **only when the person
  presses the button**; refused: *Location is off for Doorprints. Allow it in your settings to check where you are.* The sheet opens at once with
  *Finding your location...* and a *Cancel*; the first fix of 50 m or better is used; fixes worse than 50 m are ignored while waiting; **at 15
  seconds** the best fix so far decides: worse than 50 m gives `IMPRECISE`, none gives *Could not get your location. Try again outdoors.*
  While Hunt mode runs the engine is not asked and its last fix is **not** reused (a stale fix can mislead); the check asks for its own.
- **Website:** the same rule as the phones through `shared/locate-once.ts`: `watchPosition` with `{ enableHighAccuracy: true, maximumAge: 0 }`
  started inside the button's click (a user gesture), stopped (`clearWatch`) at the first fix of 50 m or better or after 15 s, when the best
  fix so far decides (worse than 50 m gives `IMPRECISE`, none gives the timeout words). One fix is used; none is stored. The choice dialog
  carries one sentence, every time (the check keeps no flag): `trace.here.permissionExplain` (*To check where you are, your browser
  will ask for your location. It is used once, only on this page, and not kept.*). Denied: *Location is blocked for this site. Allow it in your browser's
  site settings to check where you are.* No Geolocation: the existing *This browser cannot give your location.* **The website works from the current
  location only while the page is open** (a browser gives a page a fix only while it is open and visible; there is nothing to
  do in the background). *A house or a picked spot works with any stored trace, with or without location permission.* The
  website's stored walks hold only what it recorded while a page was open (5.27.8), so its negative answer adds *On the website, only
  walks recorded while this page was open are included.*
- **The live walk.** For the *Here* source only, **the walk now recording is left out** (the person is standing on it: *you walked here today* would be
  true and useless); every earlier walk, the one that just ended included, counts. For a house or a picked spot the live walk counts like
  any other (the points kept so far).
- The fix is used for this one answer and **never stored, never added to the trace, never logged, never sent**. The OS's own location service
  may use the network to find the fix, as for any location button; Doorprints makes no request of its own.

**How the answer reads** (the words are the keys below; `{place}` is *here*, *this house* or *this spot*):
- **Dates.** The calendar day of each row's `atMs` **in the device's time zone at the moment of the check** (a walk at 23:50 in one zone and a
  check made after a flight shows the day on the new clock: stated, accepted), through the app's existing date helpers
  (phones: `formatDate(epochMillis, language, withTime = false)`, `:ui` `Format.kt`; website: `TranslationService.dateOnly`, which uses `Intl`: Angular
  has no locale data for hi, ta and te, so no `date` pipe with a language, `tools/check-templates.mjs`) in the app language, with a
  **short weekday added** (website `Intl.DateTimeFormat(locale, { weekday: 'short', day: 'numeric', month: 'short', year })`, the year only when it is not the
  current year; phones: the locale's own pattern for the skeleton `EEEdMMM` (`EEEdMMMy` for another year): Android `DateFormat.getBestDateTimePattern(locale, "EEEdMMM")`,
  iPhone `setLocalizedDateFormatFromTemplate("EEEdMMM")`; Tamil and Telugu put the weekday last, and the three stacks must agree; the owner's example, *Tue 7 Oct*, is this;
  the website helper takes an optional time zone for its unit test). A pure-digit date is never used alone.
- **Headline.** `WALKED`: *You walked within {distance} of {place} on {dates}.* The dates are the **distinct days** of the `WALKED` rows, newest first, **at most three**
  joined by the dictionary's existing list words (`list.two`, `list.three`; website `TranslationService.list`, phones `joinList`), then *and {n} more* when there are more. `{distance}` is **the largest
  distance among the walks on the listed days, rounded up to a whole metre, at least 1 m** ("within" is then true for every listed date).
  `CLOSE`: *No walk of yours passed within {tolerance} of {place}, but one came within {distance} on {dates}.* (the `CLOSE` rows, the same rule). `NONE`:
  *No walk of yours passed within {tolerance} of {place} in the last 30 days.*, or, when at least one saved walk exists, *... in the last 30 days or in your saved walks.*
  `EMPTY`, `IMPRECISE`, `INVALID_PLACE` and the permission states have their own sentences. `CLOSE` and `NONE` add *This covers only the walks Doorprints recorded.*
- **Rows**, under the headline, one per walk of the headline's band, newest first, **at most five** then *and {n} more walks*: *{date}, {distance} away*, with *, saved walk* for a saved walk.
- **Actions:** *Show on map* (Map screen and house page: closes the sheet and shows the highlight below), *Check again* (for *Here*), *Close*.
  **Nothing is remembered:** closing the sheet discards the result; it is held in memory only (no `rememberSaveable`, no saved state, no history of checks).
  On the website the house page's answer and its halo and ring are drawn on the house page's own map (`LocationMap`), and the Map page's on the Map;
  *Show on map* never navigates with a result, and no result, place or distance is ever put in a URL, in `history.state`, in session or local storage,
  in `ListReturn` or a query parameter.

**The map shows the matched stretch.** For each `WALKED` row the **matched stretch** is the part of that walk's polyline from 60 m before to 60 m after
its nearest point, along the walk, **within one part** (never across a resumed point; shorter at a walk's end): `CHECK_STRETCH_M` = 60. It is drawn as a **halo**
(the *Show on map* look of 5.27.6: a 2 px halo, here kept while the sheet is open, not 3 seconds), **not** with the repeat look (no orange, no dash: a
check is not a repeat, and with *Off* the check still highlights), and a **ring** with a cross at the place (a form, not a colour: a marker is a filled dot, this is a hollow ring with
a centre cross), plus the label *You are here*, *This house* or *This spot*. The cue is never colour alone: the halo is a casing wider than the
line and the ring has a form no marker has. The map fits the view to the place and the stretches when they are not on screen. The stretches are a separate GeoJSON
source `track-check` over the base line, cleared on close. *Screen readers:* the sheet is a live region: the headline and the rows are read as text on
arrival (`role="status"`, `aria-live="polite"` on the website; TalkBack `liveRegion`, VoiceOver announcement), focus moves to the sheet's title (the website announces the headline once through the app's polite live region, `Announcer`, and withdraws it when the panel
closes, `Announcer.cancel`, so the sentence is not left in the page), and the map highlight is
decoration with the content description *The stretch of your walk near {place} is outlined on the map.* (the text answer is the whole answer). Hindi, Tamil and Telugu strings ship *under review*.

**Cost.** The check runs off the main thread on the phones and without blocking the page on the website (a synchronous pass that yields between saved walks; no Web Worker: a few milliseconds); it decodes one saved walk at a time and discards it (at most 200 walks of 5 000 points), and rejects a
segment cheaply when it lies outside the place's box grown by `NEAR_BAND_M` before any trigonometry; the result must equal the plain loop (a random-city test, TC-U-153).
It is not run when the sheet is closed, never repeatedly, and never in the background.

**Privacy** ([01](01-requirements.md) PRV-032; [02](02-threat-model.md) T-I42, RR-31). The check is **on demand only**: no code path calls it except the button's handler (a source test, TC-U-154).
It makes **no network request** (the tests run it with the network stubbed to throw; a house or a picked spot never needs location). The answer is
**shown, never stored and never sent**: no log, breadcrumb or crash text holds a place, a distance, a date or a count; the check on a house does
not leave the device (the house's location is read locally and no house is sent anywhere because of it); **the saved-walk rule is unchanged**
(5.27.7: the store and the codecs are untouched, and the check adds no export, sync or AI path). The answer reveals location history to whoever holds the
unlocked phone (or sees a screenshot of the sheet), exactly as the trace and the Saved walks card already do (T-I29, T-I30, RR-31); the app lock covers it.

**Strings** (English; same rules as 5.27.9; the website key is `trace.here.<name>`):

| Key | English |
|---|---|
| `trace.here.button` | Have I been here? |
| `trace.here.buttonHouse` | Did I walk past this house? |
| `trace.here.menuHere` | Where I am now |
| `trace.here.menuSpot` | A spot on the map |
| `trace.here.pressMenu` | Did I walk here? |
| `trace.here.pickHint` | Move the map so the cross is on the spot, then press Check this spot. |
| `trace.here.pickConfirm` | Check this spot |
| `trace.here.title` | Have I been here? |
| `trace.here.titleHouse` | Did I walk past this house? |
| `trace.here.titleSpot` | Did I walk here? |
| `trace.here.placeHere` | here |
| `trace.here.placeHouse` | this house |
| `trace.here.placeSpot` | this spot |
| `trace.here.labelHere` | You are here |
| `trace.here.labelHouse` | This house |
| `trace.here.labelSpot` | This spot |
| `trace.here.permissionExplain` | To check where you are, your browser will ask for your location. It is used once, only on this page, and not kept. |
| `trace.here.locating` | Finding your location... |
| `trace.here.walked` | You walked within {distance} of {place} on {dates}. |
| `trace.here.close` | No walk of yours passed within {tolerance} of {place}, but one came within {distance} on {dates}. |
| `trace.here.none` | No walk of yours passed within {tolerance} of {place} in the last 30 days. |
| `trace.here.noneSaved` | No walk of yours passed within {tolerance} of {place} in the last 30 days or in your saved walks. |
| `trace.here.onlyRecorded` | This covers only the walks Doorprints recorded. |
| `trace.here.onlyRecordedWeb` | On the website, only walks recorded while this page was open are included. |
| `trace.here.andMore` | {dates} and {n} more |
| `trace.here.row` | {date}, {distance} away |
| `trace.here.rowSaved` | {date}, {distance} away, saved walk |
| `trace.here.rowsMore` | and {n} more walks |
| `trace.here.empty` | There are no walks to compare yet. Turn on Trace my path in Settings, then walk with Hunt mode on. |
| `trace.here.emptyWeb` | There are no walks to compare yet. Turn on Trace my path on the Map page and start a walk. |
| `trace.here.imprecise` | Location not precise enough. Try again outdoors. |
| `trace.here.fuzzy` | Your location is only accurate to about {n} m, so this answer may be off. |
| `trace.here.denied` | Location is off for Doorprints. Allow it in your settings to check where you are. |
| `trace.here.deniedWeb` | Location is blocked for this site. Allow it in your browser's site settings to check where you are. |
| `trace.here.timeout` | Could not get your location. Try again outdoors. |
| `trace.here.approxHouse` | This house has no exact spot yet, only an area. Place it on the map first, then check. |
| `trace.here.invalid` | This spot has no valid location. |
| `trace.here.privacy` | Shown only here. Nothing is saved or sent. |
| `trace.here.again` | Check again |
| `trace.here.stretchA11y` | The stretch of your walk near {place} is outlined on the map. |

`{tolerance}` is `TOLERANCE_M` formatted as a distance (so the 20 m fallback of 5.27.2 changes no string). The key is `stretchA11y`, not `a11y.stretch`: the
website's dictionary has no four-level key (`map.sort.recent` is the depth in use). The existing `trace.house.show` (*Show on map*), `trace.web.unavailable`, the dictionary's
`list.two` and `list.three` and the common *Cancel* and *Close* are reused. No other string changes.

**What changes elsewhere.** 5.27.2 gains the three constants; 5.27.9 points here for these keys; the website's walks and the
phones' tables, the settings and the exports are **unchanged**; the guide page *Your paths* (S4b-FR-18) gains a paragraph; [05](05-ux-accessibility-i18n.md)
gets the sheet and the ring when the ticket is built.

**The website's files and order** are in [03](03-design.md) §6.2b (the implementation plan from the senior review, [ops/path-trace-check-review.md](ops/path-trace-check-review.md), a session
record) and in the tickets S4b-FR-13, FR-15, FR-17 and FR-24 ([10](10-sprint-log.md)): the website's check lands after the website's trace (FR-17).

**Owner decisions of 2026-10-06** ("go with the recommendations" of that review): the website's *Here* is the 15-second best-fix watch above (not one `getCurrentPosition`);
a one-press *Mark the houses I walked past* on the house list is **not** in S4b (LATER, question 18); questions 15 to 17 stand as recommended.

### 5.28 Sharing updates with someone you know (S4b-FR-3, design)

**The ask** (owner, 2026-09-28): two people who know each other, say a couple or a parent and a child, hunt together,
each on their own phone, and want each other's updates: a house one of them saved, a visit, a note, a status change.
Zero cost and no public server (owner rules), and it must work before Google sign-in exists (its OAuth client is the
owner's to make, [14](14-lead-backlog-and-handoff.md) §6).

**Design (lead, 2026-09-30; ADR-27).** Sharing is a file, in the backup format the apps already write and read,
sent through whatever app the two people already talk on (WhatsApp, email, AirDrop, a USB cable), and imported with
the merge that exists. Nothing new is stored anywhere but the two phones, so it costs nothing and needs no account.

1. **Share updates with…** (Settings > Your data, next to *Save a copy*; the Map's and the list's share is a later
   round): the person picks who it is for from a short list of names they typed once ("Priya", "Amma"), or adds one.
   Doorprints keeps, per name, when the last update went to them (`share_contacts`: id, name, `lastSharedAt`; on
   the phone only, never synced or exported). The first share to a name is the whole list (with the usual scope:
   all, or shortlisted); every later one carries only what changed since `lastSharedAt`: the houses, visits and
   photos with `updatedAt` after it, plus the photos of a changed house. The file is a normal backup ZIP
   (`doorprints-backup/1`, docs/schemas), with two manifest fields added, `sharedSince` (ISO-8601, absent on a full
   share) and `sharedTo` (the name, so the recipient's import can say "updates from Ravi for Priya"); its name is
   `Doorprints-updates-<date>.zip`. The contact-details choice and its warning apply as to any copy; the size is
   shown before the share sheet opens. `lastSharedAt` moves forward only once the share sheet reports the file
   handed over.
2. **Receiving**: the file opens in Doorprints from the other app (Android: an intent filter for the backup's
   MIME type and name pattern that starts the Import screen with the file; iPhone: the document type and
   `onOpenURL`, once the iPhone has imports, S4b-BL-81; web: a PWA `file_handlers` entry, once the web has a backup
   reader, S4b-BL-75). The Import screen's preview and merge are unchanged: "*a* new, *b* newer in file, *c* newer
   here", last write wins on `updatedAt`, *Keep mine, add only what's new* for a cautious import, *import as a
   copy* for a separate list. Importing the same file twice changes nothing.
3. **Conflicts and deletions**: the existing rule, last edit wins per row, both phones converging once each has
   imported the other's latest file. A deletion does not travel in the first version (the backup format carries no
   tombstones, docs/schemas §6 rule 5): the other person's copy keeps the house until they delete it too. Carrying
   deletions is S4b-BL-82 (a `deleted` marker in the update file, applied only by an update import, never by a
   backup restore).
4. **Later, automatically** (amended by [15](15-google-drive-backup-and-sharing.md) §4.2: a read-only file per person, shared through Google's Picker): with Google sign-in (D-28) the same update file goes into a Drive folder the two share
   (`drive.file`), and each phone imports what the other put there; the file format, the per-name bookkeeping and
   the merge stay as built here. A shared self-hosted server (ADR-25 pairing) stays the option for people who run
   one: it needs no files at all.

**Why a file, not a server or a link.** No server exists that the owner will host (D-28), a self-hosted one is for
few, and a peer-to-peer link needs both phones online at once and a relay. A file works offline, on every platform,
through the apps people already trust each other on, and it is the format the apps already test in three stacks
(TC-U-93). What it costs: the two people must remember to share; the update carries no deletions at first; the
iPhone and the website need their import first.

**Built on Android (2026-09-30, [10](10-sprint-log.md) §13.16).** Settings > Your data > *Share updates with…*: the
names (`ShareContact` in the settings store, on the phone only: id, name, `lastSharedAt`), *Add a name*, what the
chosen person last got and when, *Include contact details* with its warning, *Include photos*, the counts the file
will hold ("3 houses, 2 visits and 4 photos"; "Nothing has changed since then" disables the button), and *Share
updates with Priya*: the export worker writes a backup ZIP into the share-copies folder with `since` and `sharedTo`
in the options (`ExportBundle.build` keeps the houses and visits with `updatedAt` after `since`, the photos with
`createdAt` after it, every visit and photo of a changed house, and the unchanged house of a changed visit so it has
somewhere to land), the share sheet opens with it and the name's `lastSharedAt` moves to the export's instant once
the sheet has opened (Android does not report whether a file was sent, so this is the nearest moment). The manifest
carries `sharedSince` and `sharedTo` (docs/schemas §2); the file is `Doorprints-updates-<date>.zip`. A ZIP or JSON
another app opens in Doorprints (*open with*, or *share to Doorprints*) arrives as `DeepLink.ImportFile` and the
Import screen picks it as if chosen; its header then reads "Updates for Priya, made on <date>". Tests
[06](06-test-plan.md) TC-U-96; the exchange between two phones TC-M-33 (owner).

**Deletions and the other platforms (2026-10-01, S4b-BL-82, -75, -81; [10](10-sprint-log.md) §13.31, §13.34;
[03](03-design.md) ADR-29).** An update file now carries the houses deleted on the sender's phone after
`sharedSince` in a `deleted` list (kind `house`, id, the delete's `updatedAt`), which makes the file
`doorprints-backup/3` (docs/schemas §3.13); a file without deletions stays `/1` or `/2`. Only an update import applies
it, with *Merge*: a live house here older than the delete is deleted as if by the person (a tombstone that syncs); a
house edited here after the delete, *Keep mine*, *Add as copies* and a backup restore leave it. The preview says
"Houses deleted by the sender" (the sender's name is not in the file, S4b-BL-107). The server reads `/3` as a restore
and ignores the list. The website now reads update files with its importer; the iPhone imports them from Files or
another app (compiled only); neither shares updates yet. The installed website opens a `.zip` from the system
in *Import a backup* (the manifest's `file_handlers` with `window.launchQueue`, Chromium on a computer only; S4b-BL-108).

**Order of work.** (1) Android: the per-name bookkeeping, the "since" filter in `ExportBundle`, the manifest
fields, the share sheet, the intent filter, the Import screen's "updates from" line; the readable copies unchanged.
(2) The web's backup reader (S4b-BL-75) and then its share and file handler. (3) iPhone copies and imports
(S4b-BL-81), then its share. Privacy review: PRV-029 (the names and times stay on the phone; the file is what the
person chose to send; contact details as in any copy). Tests: the "since" filter and the manifest fields in the
backup format tests of all three stacks; the intent filter's checks (F-25: a file, not a link, validated as any
import); TC-U row; a device exchange between two phones (TC-M).

### 5.29 A house from a listing link, and brokers (S4b-FR-4, S4b-FR-11, design)

**The ask** (owner, 2026-09-28): "a house from a MagicBricks, 99acres, Housing.com, NoBroker, Square Yards or
NestAway listing link", the photos and details filling in the new-house form; and brokers as contacts of their own
(5.25, D-30), since listings bring the broker's details.

**Design (lead, 2026-09-30).** 5.9 stands: **Doorprints never fetches the listing page.** The portals' terms and robots
rules restrict automated reading, the pages need JavaScript, a free-tier address would be blocked, and the website
cannot read another site from the browser at all. Open Graph preview data is the page too. So "from a link" means
what the portal itself hands over when the person taps *Share* on a listing: its share text (title, price, BHK,
locality, sometimes the area and the furnishing) and the link. That text is parsed **on the device, without AI**, into
the new-house form; the link is kept (`listingUrl`, cleaned of `utm_*`, `fbclid` and `gclid`) and the portal named
from a host allowlist. Photos are never fetched (the portal's copyright, and no fetch); the person's own screenshots
go in through the photo picker as any photo. *Fill in from listing text* (FR-038, AI on the device with the person's
key, or the server) stays as the optional second pass over the same text.

1. **The no-AI parser** (`ListingText.parse` in `:shared`, a TypeScript port on the web, one fixture file of real
   share texts per portal in `docs/schemas/listing-fixtures.json` that both run): price (`₹ 25,000`, `25k`, `45 Lac`,
   `1.2 Cr`, `per month`), RENT or SALE from the words, BHK (`2 BHK`, `2BHK`, `2 bedroom`), the locality ("in
   Indiranagar, Bengaluru"), the first `https://` link, and the carpet area and furnishing into the notes (no fields
   of their own until 4c). A phone number only when it is in the text. The result is a `HouseDraftDto` through the
   existing `DraftSanitizer` (caps, the phone and the link must be in the text), so the form's *Fill in from listing
   text* merge (`mergeListing`) and its summary of what was filled apply unchanged.
2. **The flow.** Android: `ShareReceiverActivity` (exported, `ACTION_SEND` `text/plain`, the text capped at 20,000
   characters, untrusted, SEC-043) hands the text over as `DeepLink.NewHouseFromListing`; the Map opens with "Where is
   it?" (tap the map, or *Save house here* with the location), and the new-house form opens with the parsed draft
   merged and the summary ("Filled in price, BHK, locality and the link"). Web: the share page (`/share`, FR-072)
   runs the same parser and the same map step. A listing pasted into *Fill in from listing text* runs the parser
   first, AI second. The house is saved only when the person taps *Save* (AI-004).
3. **Duplicates.** Before the form opens: the same cleaned `listingUrl`, or the same label within 100 m of the chosen
   place, says "You saved this on 12 Sep. Open it?" with *Open* and *Add anyway*.
4. **Location.** A house needs `lat`/`lon` before it is saved, as today, so "Where is it?" comes first; the locality
   lookup (forward geocoding: Android's `Geocoder.getFromLocationName`, the iPhone's `CLGeocoder`, the website's
   Nominatim search under its one-request-a-second policy, on the person's tap) is S4b-BL-83. The hollow
   "approximate" marker and the Hunt-alert exclusion of FR-068 need a house field and come with the data-model change
   of (4c).
5. **Brokers** (5.25, S4b-FR-11) are a data-model change on all three stacks: a `brokers` table (id, name, phone,
   agency, fee terms, notes, rating, `updatedAt`, `deleted`, `dirty`) and `houses.brokerId`, Room version 4 with the
   contact name and phone migrated into one broker per distinct phone number, the server's Flyway migration and sync
   endpoints, the website's IndexedDB store, and the backup format's additive `brokers` list and `brokerId` (the
   format id stays `doorprints-backup/1`: a reader ignores unknown keys; docs/schemas says which fields are new);
   never sent to AI (the redaction applies). Built as its own change after the listing flow, in the (4c) batch of
   data-model changes, so the format, the schemas and the three stacks' tests change once.

**Why this shape.** It keeps the zero-cost and terms rules of 5.9, needs no new library, and reuses what exists: the
share handler on the web, the AI fill's merge and summary on both apps, the photo picker. What it costs: only what the
portal's share text says fills in (usually the price, BHK and locality; no photos); brokers wait for the data-model
change.

**Built (2026-09-30, [10](10-sprint-log.md) §13.17).** `ListingText.parse` (`:shared`) and `parseListingText`
(`web/src/app/shared/listing-text.ts`), both over `docs/schemas/listing-fixtures.json` (seven share texts in the
portals' shapes, hand-written; `ListingFixturesTest`, `listing-text.spec.ts`): the label from the first line, the
price (₹ or Rs with k, lakh or crore, or a bare lakh or crore amount; ten lakh and above without rent words is a
sale), RENT or SALE from the words, BHK or a studio, the locality before a city name, the area and the furnishing at
the top of the notes with the whole text under them, the first link with `utm_*`, `fbclid` and `gclid` removed, the
portal from a host allowlist, a phone number only from the text; on Android through `DraftSanitizer`. Android:
MainActivity takes `ACTION_SEND` `text/plain` (the subject first, the text capped at 20,000 characters) as
`DeepLink.NewHouseFromListing`; a house with the same cleaned link asks "You saved this listing on 12 Sep" with
*Open* or *Add anyway*; else the Map opens with "Where is this house?" and the new-house form takes the parsed draft
through the same merge and summary as *Fill in from listing text*. Web: the share page's text reaches the new-house
page as before and the parser fills the fields on arrival, the AI fill staying the second pass. Tests
[06](06-test-plan.md) TC-U-97; on a phone TC-M-34 (owner). The label-within-100 m duplicate and the locality lookup
(S4b-BL-83) are not built. **Locality lookup built** (2026-10-01, S4b-BL-83, [10](10-sprint-log.md) §13.34): *Find
"<place>" on the map* offers the parser's locality, looked up only on the person's tap (`PlaceLookup` in common code;
Android `Geocoder.getFromLocationName`, iPhone `CLGeocoder` compiled only, the website Nominatim `/search` at most once a
second, restricted to India, with the reverse lookup under the same throttle); the pin lands roughly there for the
person to move, and the privacy note on the website names both lookups (`house.lookupNote`). Android fills the form but
moves no pin on the map yet (S4b-BL-107).

**Order of work.** (1) The parser with its fixtures, the Android share receiver, the web share page's parser, the
duplicate check, the map step (one change). (2) S4b-BL-83, the locality lookup. (3) Brokers with (4c).

### 5.30 The Sprint 4b data model, in one change of format (N13 4c, design)

**The ask** (owner, D-30 and N13 of 2026-09-29): the Sprint 4b set of 14.2, enlarged by the gap review, "in one
data-model and format change": weighted criteria (5.4), the viewing questions (5.5), rooms (5.6), photo tags (5.7),
viewings (5.8), hunting areas (5.17), the real cost of a house (5.21, S4b-FR-7), my places (5.22, S4b-FR-8), area notes
(5.23, S4b-FR-9), moving in (5.24, S4b-FR-10) and brokers (5.25, S4b-FR-11). Zero cost; the repository kept optimised
(no new library, few files per field); the search rule; the docs of [14](14-lead-backlog-and-handoff.md) §8 findings
3, 4, 12 and 14, which this design settles.

**Design (lead, 2026-09-30; [03](03-design.md) ADR-28).** Two shapes carry everything, so that the format, the
schemas and the three stacks change once and each later entity costs one class per stack instead of nine:

1. **The house keeps its own new values, nested.** `HouseDto` version 2 is additive: `cost` (one object:
   `deposit`, `depositMonths`, `maintenance`, `maintenanceIncluded`, `brokerage`, `brokerageMonths`, `lockInMonths`,
   `noticeMonths`, `availableFrom` (a date, `YYYY-MM-DD`), `myOffer`, `agreedPrice`; rupees are whole `Long`s,
   months `Int`s), `areaSqft`, `locationSource` (`GPS`, `MAP` or `APPROX`; FR-068's hollow marker and Hunt-alert
   exclusion read `APPROX`), `brokerId`, `rooms[]` (5.6) and `answers[]` (5.5), plus the status `TAKEN` and
   `NOT_CHOSEN` (5.24). On Android `cost` is `@Embedded(prefix = "cost_")` and `rooms`/`answers` JSON text columns
   through the converters the checklist already uses; on the web the same nested object in the `houses` store; on
   the server three `jsonb` columns (`cost`, `rooms`, `answers`) and the scalar columns, Flyway `V7` (slice 1; `V6` is the `record`
   table of slice 0), no `house_room` or `house_answer` table (the earlier 8.1 rows V5 and V7 fold into it; nothing queries a room by
   itself). Named arguments in every mapper (finding 4a, done) and a round-trip completeness test per stack keep the
   eleven same-typed numbers from mis-mapping.
2. **Every other new thing is a record.** `criteria`, `questions`, `viewings`, `huntingAreas`, `places`,
   `areaNotes`, `brokers`, `photoMeta` (5.7: `roomId`, `tags`, `caption`, keyed by the photo's id, so the photo
   endpoints and bytes are untouched), `moveIn` (5.24: the checklist and the condition record, keyed by the house's
   id) and `preferences` are each a small `@Serializable` class in `:shared` (with its caps and validation, the
   TypeScript twin on the web), kept in **one `records` table** on the phone and **one `records` store** in the browser
   (`type`, `id`, `payload` as JSON text, `updatedAt`, `deleted`, `dirty`; an index on `type`), read through typed
   accessors in common code (`RecordType<T>`: the name, the serializer, the caps), so a new kind of data is one class
   and no table, no DAO and no migration; and **one sync path**: the envelope `{type, id, updatedAt, deleted, syncVersion, payload}` (finding 14). The server stores
   it opaquely in one `record` table (`type`, `id`, `owner` later, `payload jsonb`, `updated_at`, `deleted`,
   `sync_version`, `PRIMARY KEY (type, id)`; `GET /api/records?type=&since=` and `PUT /api/records/{type}/{id}`,
   the same cursor rule as houses, payload at most 64 KB, at most 5 000 rows per type) and never reads a payload:
   no server entity, DTO or controller per record type, and the server's AI never sees them. Houses, visits and
   photos keep their tables and endpoints (they have the geo queries and the bytes). Google Drive sync (D-28) and
   sharing (5.28) carry the same envelope list, so the three transports share one code path (`RecordSync` in
   common code).
3. **The backup format becomes `doorprints-backup/2`** (finding 3, S4b-BL-72; the rule written into
   docs/schemas/README.md): a new entity list means a new format number; readers accept `1..MAX` (one constant per
   stack), write the newest, and refuse a newer file with "update the app" instead of dropping its lists in silence.
   `data.json` gains the house fields of item 1 and the lists of item 2 by their names (`criteria`, `questions`,
   `viewings`, `huntingAreas`, `places`, `areaNotes`, `brokers`, `photoMeta`, `moveIn`, `preferences`), each ordered
   by `updatedAt` then `id`, `counts` gaining one integer per list; the server's `GET /api/export` and
   `POST /api/import` follow. The path trace stays out (5.27). The six readable copies gain the new values: the
   cost lines and the rooms on the house's page in HTML, PDF and Markdown; `rooms.csv`, `viewings.csv`,
   `brokers.csv` and `areas.csv` in the CSV ZIP and as sheets in XLSX. `backup-sample.json` gains one row of every
   list, and the web byte golden is regenerated once.
4. **Local stores.** Room **4** (`AppDatabase.MIGRATION_3_4` in slice 0: the `records` table; Room **5** in slice 1:
   the house columns, and the contact name and phone of every house copied into one broker per distinct phone
   number, `brokerId` set, the old columns kept until Sprint 5); IndexedDB **2** (the `records` store and the photo index) in
   slice 0 and **3** (the house values) in slice 1, after S4b-BL-71 gives the web an
   upgrade path (the pilot is the photo index, S4b-BL-66, in the same change), the browser's `versionchange` closing
   the older tab with the update banner.
5. **Who sees what.** AI documents (`AiHouse`, the server's index) gain the cost, the rooms and the answers, with
   the redaction of 5.13; brokers, places (the coordinates), area notes' authorship and the move-in record are never
   sent to AI, and `my offer` is never sent either (a negotiation is the person's own). Exports include everything;
   *without contact details* now also leaves the brokers out. **Search** (the owner's rule): the broker's name and
   agency (in place of the contact name), the room names, the answers' text, the viewing notes and the area notes
   join it, on both apps with the shared case list (`HouseSearch`, `map-list.spec.ts`).
6. **Screens** (design step of §12 before each slice: four languages, both themes, loading, empty and error
   states): the house form gains four sections (Cost, Rooms, Questions, Broker) behind section headers, shot per
   section in the screenshot tests (S4b-BL-77); Compare gains monthly cost, money to move in and cost per sq ft;
   the list gains sort by distance to a place; *Criteria*, *Viewings*, *Hunting areas* (with my places and area
   notes) and *Brokers* are screens of their own under Settings and the house; *Taken* with the move-in checklist on
   the house. Reminders as 5.8 and 5.16 (Android alarms, the iPhone's `UNUserNotificationCenter` that Hunt mode
   already uses, the web's `.ics`).

**Why this shape.** One envelope means one server migration and one sync path for ten kinds of data, on a server the
owner does not host (D-28), with the same envelope serving Drive and sharing; nested house values mean the readable
copies, the forms and the mappers grow by one object, not by eleven columns; a numbered format means an old app
refuses rather than loses. What it costs: a format bump the web importer (S4b-BL-75) must read before anyone shares
a `/2` file with a browser, and the server cannot query inside a record (nothing needs it before Sprint 6).

**Slice 3 is split** (2026-09-30): **3a** the viewing questions (5.5, built), **3b** viewings with their local reminders, the second-viewing prompt and
the history (5.8; a `viewing` record, Android alarms, the iPhone's notifications, the website's `.ics`), **3c** the Hunt mode reminder before a
viewing (5.16). 3b and 3c are platform work and get their own design step.

**Order of work**, each slice its own pull request with the model, both apps, the server, the backup and the
readable copies, the search, the tests and the docs, in this order so that every slice ships something usable:
(0) the foundations: the format rule and `/2` readers (S4b-BL-72; the writers stay at `/1` until slice 1 writes the
first list), the IndexedDB upgrade path with the photo index (S4b-BL-71, S4b-BL-66), the server's `record` table and
endpoints, the `records` table and store with their sync, no visible change;
(1) the house's own values and brokers, in three steps so each pull request stays readable: **1a** the cost, the carpet
area and `locationSource` with the hollow marker (S4b-FR-7; within `/1`, since they are optional fields on a row that
exists), **1b** brokers with the migration (S4b-FR-11; the first list, so `/2`), **1c** rooms (5.6); Compare and search
with each;
(2) criteria and ranking with the preferences (5.4);
(3) viewing questions and viewings with reminders and the history screen (5.5, 5.8, 5.16);
(4) hunting areas, area wake-up, my places and area notes (5.17, 5.18, S4b-FR-8, S4b-FR-9);
(5) photo tags and moving in (5.7, 5.24, S4b-FR-10).
Slices 1 to 5 change the format once more only if a slice adds a list slice 0 did not name; the list above is
complete, so `/2` is the format for all of them.

**Built, slice 0 (2026-09-30, [10](10-sprint-log.md) §13.18).** The versioning rule in docs/schemas README §1.1 with every
reader accepting `/1` and `/2`; the server's `record` table (Flyway V6) and `/api/records`; Room 4 with the `records`
table, `RecordType<T>` and the records' sync in `:shared`; IndexedDB 2 with the upgrade path, the `records` store,
the `houseId` indexes and the "updated in another tab" notice on the web. No record type yet: brokers open slice 1.

**Built, slice 1a (2026-09-30, [10](10-sprint-log.md) §13.19).** `areaSqft`, `locationSource` (`GPS`, `MAP`, `APPROX`) and
the nested `cost` on the house in all three stacks (Room 5, Flyway V7, the backup within `/1`, the six readable copies,
the AI documents without *my offer*); `CostSummary` in common code with its TypeScript twin over one list of vectors
(monthly cost, money to move in, cost per sq ft); the form's **Cost** section and *Approximate location* switch; the
hollow marker on both maps and its legend entry; Hunt mode never alerts at an approximate house; Compare's new rows;
the listing parser fills the carpet area. Search is unchanged (numbers are not searched; the filters are a later
round, S4b-BL-84).

**Built, slice 1b (2026-09-30, [10](10-sprint-log.md) §13.20).** Brokers as the first record type: `Broker` in `:shared` and
TypeScript, `houses.brokerId` (Room 6, Flyway V8), the repository's `ensureBroker`, `saveBroker`, `deleteBroker` and the
once-only migration of contacts, the first `doorprints-backup/2` list (`brokers`, written only when the copy has one), the
server mapping the list to its `record` table, a `broker` column and `brokers.csv` in the copies, search over the broker's
name, agency and fee terms, and the *Brokers* screen on both apps. Brokers are never sent to AI.

**Built, slice 1c (2026-09-30, [10](10-sprint-log.md) §13.21).** Rooms nested in the house: `rooms` after `cost`, Room 7, Flyway V9, the length-unit
setting, a writer that now writes `/2` when a copy holds a broker or a room, the copies, the AI line, search and the form's Rooms
section on both apps.

## 6. User stories

Continues [01 §5](01-requirements.md#5-user-stories) (US-01..US-15).

| ID | As a... | I want to... | So that... | Acceptance criteria (summary) | Req | Sprint |
|---|---|---|---|---|---|---|
| US-16 | hunter | save a readable copy (HTML or PDF) to Downloads or Google Drive | I keep my notes and photos even if I uninstall the app | Works in flight mode without an account; opens on another device without Doorprints; all houses, scores, rooms, answers, visits, photos; chosen language; one house per printed page | FR-042, FR-043, FR-045 | 4a |
| US-17 | hunter | get my data as a spreadsheet (CSV or XLSX) or Markdown | I can sort it in Excel/Sheets or paste it into notes | Tables for houses, scores, rooms, answers, visits, viewings, photos; ₹ and dates typed; opens in Excel, Google Sheets, LibreOffice; Indic text intact | FR-042 | 4a |
| US-18 | hunter | make a full backup and restore it on a new phone or in the browser | I can move or restore my hunt without an account | Export → fresh install → import gives identical data; importing twice changes nothing; newer local records kept | FR-044, FR-047 | 4a |
| US-19 | hunter | choose whether owner/broker phone numbers go into a copy | I can share a copy with family safely | "Leave out" removes contact fields from every format; default "Include" shows the warning | FR-046, PRV-012 | 4a |
| US-20 | hunter | ask for a custom summary, like "a WhatsApp message in Tamil for my parents about my top 3" | I can share my shortlist the way my family reads it | Uses only the selected data; labelled AI-generated; numbers checked against my data; no phone numbers unless I tick the box; share to WhatsApp; never offered as a backup | FR-049, FR-050, PRV-021 | 5 |
| US-21 | hunter | add my own criteria and say how much each matters | the score reflects what my family cares about | Up to 40 criteria; weights Ignore/Low/Medium/High; default weights give today's score | FR-051, FR-052 | 4b |
| US-22 | hunter | mark a criterion as a must-have | houses that fail it drop to the bottom | "Fails: water" badge; ranked after all others | FR-053, FR-054 | 4b |
| US-23 | hunter | see my shortlist ranked automatically | I know which house is best at a glance | Rank, score, coverage, must-have status; updates instantly; offline | FR-054 | 4b |
| US-24 | hunter | keep questions to ask at every viewing and record answers | I don't forget maintenance, deposit or water | India defaults in my language; edit/reorder/hide; Open/Answered/Skipped per house | FR-056, FR-057 | 4b |
| US-25 | hunter | note each room's size and condition | I can compare space and repairs | Type, name, size in ft or m, condition 1–5, notes | FR-058, FR-059 | 4b |
| US-26 | hunter | tag photos with the room and what they show | I find the photo of the leaking bathroom | Room + tags + caption; gallery filters; in every export | FR-060 | 4b |
| US-27 | hunter | schedule a viewing and get reminded | I arrive on time with my questions | Android: local reminder even offline and after reboot, no address on the lock screen; PWA: reminder while open, `.ics` for the calendar | FR-062, FR-063, PRV-016 | 4b |
| US-28 | hunter | book a second viewing | I re-check what I wasn't sure about | Re-check list of open questions, low scores, problem photos | FR-064 | 4b |
| US-29 | hunter | search my viewing history | I find "the one with the big balcony in Madhapur" | Search notes and answers; date/type filters | FR-065 | 4b |
| US-30 | hunter | share a listing from MagicBricks, 99acres, NoBroker or Housing.com to Doorprints | I don't retype price, BHK and area | Draft with URL, price, BHK, area; nothing saved until Save; works offline without AI | FR-066..FR-069 | 4b |
| US-31 | anyone | install Doorprints from the browser and use it without an account | I can try it on my iPhone or laptop right away | Installs on Chrome Android, Samsung Internet, iOS Safari, desktop; all local features work offline; no sign-in asked | FR-070, FR-071, FR-073 | 4a |
| US-32 | hunter | sign in with Google only when I want sync | my phone and laptop show the same houses | Sign-in offered in Sync settings and "Open on the web"; first sign-in offers to upload local data; another account never merges silently | FR-074, FR-075, FR-082 | 5 |
| US-33 | hunter | keep my photos in my own Google Drive | they are backed up and visible on my other devices | Off by default; `drive.file` only; "Doorprints" folder; revoked access or deleted files never lose local photos | FR-077, FR-078, PRV-019 | 5 |
| US-34 | hunter | see and revoke my signed-in devices | a lost phone stops syncing | List with name, platform, last seen; revoke ≤ 60 s; sign out all others | FR-076, SEC-034 | 5 |
| US-35 | guest | use AI that runs on my phone without an account | I get help without my data leaving the phone | On a supported Android device "Improve with AI" and custom export run on-device; unsupported devices and the web show no AI entry points and no sign-in prompt for AI; nothing else blocked | FR-079 | 5 |
| US-36 | hunter | delete my account and change my mind within a week | a mistake is not permanent | Offline copy offered; 7-day grace with "Keep my account"; told that Drive files and device data stay | FR-080, PRV-015 | 5 |
| US-37 | operator | move my existing API-key install to my Google account | I keep my houses and photos | Claim once with the old key; all rows mine; "Move photos to Drive"; old builds sync until Sprint 6 | FR-081 | 5 |
| US-38 | hunter | be reminded shortly before a viewing and start Hunt mode from the reminder | I don't forget to turn on alerts as I walk to the house | Reminder 5 to 60 min before (default 15); Start Hunt mode works from the notification with the app closed; Dismiss; offline; Do Not Disturb respected; no address on the lock screen | FR-083, FR-084 | 4b |
| US-41 | hunter | share a listing from a portal app or a browser into Doorprints and get a house drafted from it | I do not type what the ad already says | The share sheet shows Doorprints; the price, BHK, locality and link fill the form; I place it on the map; a duplicate is offered to open instead; nothing is fetched from the portal and nothing is saved until I save (5.29) | S4b-FR-4 | 4b |
| US-40 | hunter hunting with someone | share my latest houses, visits and notes with the other person, and get theirs, without an account or a server | we hunt as one from two phones | *Share updates with…* makes a file of what changed since the last share to that person; sent through any app; opens in Doorprints on the other phone; merged with last edit wins; the same file twice changes nothing (5.28) | S4b-FR-3 | 4b |
| US-39 | hunter | mark the neighbourhoods I'm searching in and be asked to start Hunt mode when I get there | I never walk through my target area with Hunt mode off | Up to 20 areas, 200 m to 2 km; off by default; asks for "Allow all the time" only when I turn it on, with an explanation first; never starts tracking by itself; once per area per 6 h; still works after a reboot; turns itself off if I remove the permission | FR-085..FR-088, PRV-024..PRV-027 | 4b |

## 7. New requirements (proposed for 01)

Priorities: M = must, S = should, C = could.

### 7.1 Functional

| ID | Requirement | Pri | Sprint |
|---|---|---|---|
| FR-042 | The user can export in **six deterministic formats**: HTML, PDF, CSV (ZIP of tables), XLSX, Markdown and JSON full backup (5.2). The same data and options give the same content. | M | 4a |
| FR-043 | HTML and PDF are self-contained readable copies: every selected house with details, weighted score breakdown, checklist, rooms, questions and answers, visits, viewings, notes, tagged photos and (optional) contacts, in the chosen language, one house per printed page. HTML has no scripts and no external resources. | M | 4a |
| FR-044 | The JSON full backup (`doorprints-backup/1`) is **exact**: import restores every field, including IDs, timestamps, criteria, questions, preferences and photo metadata. | M | 4a |
| FR-045 | Android (from Room) and the PWA (from IndexedDB) build every format **on the device, offline, without an account or server**; save via Storage Access Framework, share sheet, download, Web Share, or Google Drive when connected; never to storage that is removed on uninstall. | M | 4a |
| FR-046 | Export options: scope (all / shortlisted / selected), photos (all / shortlisted / none), contact details (include by default with a warning / leave out), language, include rejected houses. | M | 4a |
| FR-047 | The user can import a JSON backup on Android and in the PWA: validate, preview, merge by UUID with last-write-wins, or import as a copy with new IDs; importing twice is a no-op. Other formats are not importable. | M | 4a |
| FR-048 | Optional automatic weekly JSON backup (Android: chosen folder or Google Drive; keep last 4; while charging; off by default). | C | 4a (stretch), Drive target 5 |
| FR-049 | **AI custom export**: the user describes an output; the model receives only a structured export of the selected data (no photos; contact fields only with opt-in); output can be copied, shared or saved as text/Markdown. Guests: on-device only where supported (D-21); invited users: cloud. | S | 5 |
| FR-050 | AI custom export output is labelled "AI-generated" in the UI and in a visible footer, every number is validated against the input data (5.3), and it is never saved as, or accepted as, a backup. | M | 5 |
| FR-051 | Custom **criteria**: 10 built-in + up to 30 custom, weights 0–3, reorder, archive; synced when signed in. | M | 4b |
| FR-052 | The overall score is the weighted checklist mean blended with the star rating by `score.ratingShare` (default 0.5); default weights equal FR-005 (FR-005 amended). | M | 4b |
| FR-053 | **Must-have** criteria with a minimum score; failing houses are marked. | S | 4b |
| FR-054 | **Ranking** view of shortlisted (optionally NEW) houses, offline. | M | 4b |
| FR-055 | Compare (FR-011) adds weighted score, must-haves, rooms and answers. | S | 4b |
| FR-056 | **Question bank** seeded with translated India defaults; add, edit, reorder, hide; RENT/SALE filter. | M | 4b |
| FR-057 | Per-house **answers** (text, Open/Answered/Skipped) with the question text kept. | M | 4b |
| FR-058 | Up to 30 **rooms** per house: type, name, length, width, condition 1–5, notes. | M | 4b |
| FR-059 | Carpet area (sq ft); units feet or metres. | S | 4b |
| FR-060 | Photo **tags** (≤ 10), room link and caption; gallery filters. | M | 4b |
| FR-061 | Notes on visits (rooms and answers have their own). | S | 4b |
| FR-062 | **Plan a viewing**; mark done/cancelled/missed; link a nearby visit. | M | 4b |
| FR-063 | **Local reminders** only (no email, no server push): Android alarms that survive reboot and work offline; PWA notifications while the app runs plus `.ics`; Android "Add to calendar". | M | 4b |
| FR-064 | **Second viewing** with a re-check list. | S | 4b |
| FR-065 | **Viewings** timeline with search and filters. | M | 4b |
| FR-066 | Android receives shared text/URLs; on-device no-AI parser fills a draft; URL stored without tracking parameters; the listing page is never fetched. | M | 4b |
| FR-067 | "Improve with AI" on the draft: on-device where supported, or cloud for invited users (D-21). | S | 4b |
| FR-068 | Houses without a GPS/map position are `APPROX`, drawn hollow, excluded from Hunt-mode alerts until confirmed. | M | 4b |
| FR-069 | Duplicate warning on the same normalised URL or same label within 100 m. | S | 4b |
| FR-070 | The web app is an installable **PWA** (manifest, icons, service worker, install item, iOS help). | M | 4a |
| FR-071 | The web app is **local-first**: all data in IndexedDB, every non-AI feature works offline and **without signing in**; the network is used only for sync, AI and map tiles. | M | 4a |
| FR-072 | The installed PWA is a share target using the FR-066 parser; the new-house page has a paste box. | S | 4a (target), 4b (parser) |
| FR-073 | The PWA shows an update prompt, requests persistent storage, shows used space, and warns iOS guests about storage eviction. | S | 4a |
| FR-074 | Optional **Sign in with Google** (Android Credential Manager, web GIS); Doorprints stores no passwords and has no own sign-up. | M | 5 |
| FR-075 | Signing in unlocks sync across devices, the web app with synced data, Google Drive storage; cloud AI needs sign-in **and** an owner invitation; nothing else requires an account. | M | 5 |
| FR-076 | **Signed-in devices**: list, rename, revoke one, sign out all others; activity list. | M | 5 |
| FR-077 | Photo storage choice: device only (default) or also Google Drive (`drive.file`, `Doorprints/Photos`); the server keeps thumbnails and metadata only. Optional "keep only thumbnails on this phone". | M | 5 |
| FR-078 | Google Drive: backups to `Doorprints/Backups`; revoked access, deleted/trashed files and a full Drive are handled without data loss or error loops (5.12). | M | 5 |
| FR-079 | **AI access tiers** (D-21, 5.13): on-device AI (Gemini Nano, ML Kit GenAI Prompt API) for everyone on supported Android devices, AI hidden elsewhere; cloud AI only for the owner and invited users within a per-user quota and a global hard cap. | M | 5 |
| FR-080 | **Account deletion** with a 7-day grace period, offline copy offered first, clear statement that Drive files and device data stay (5.14). | M | 5 |
| FR-081 | **Claim** of an existing API-key install by the first Google user with the current key; move legacy server photos to Drive or devices. | M | 5 |
| FR-082 | A device binds its local data to the first account it syncs with; signing in to another account asks, never merges silently; sign-out offers to keep or remove local data. | M | 5 |
| FR-083..FR-088 | Hunt mode reminders (5.16) and Hunting areas with area wake-up (5.17). **Accepted** (D-23, D-24) and defined in [01](01-requirements.md) v0.12 §6.7; not repeated here. | S/M | 4b |

### 7.2 Non-functional

| ID | Category | Requirement | Measure / target | Pri |
|---|---|---|---|---|
| NFR-021 | Performance | Export on the device | JSON backup of 1 000 houses / 2 000 photos ≤ 3 min on a mid-range phone with ≤ +64 MB heap; PDF of 100 houses ≤ 60 s | S |
| NFR-022 | Portability | Readable copies open without Doorprints | HTML/PDF in current and previous Chrome, Firefox, Safari, Samsung Internet, Edge, and Android/iOS PDF viewers; XLSX in Excel, Google Sheets, LibreOffice | M |
| NFR-023 | Compatibility | Backup format lifetime and determinism | Every version imports every earlier `formatVersion`; identical input + options → identical output apart from the export time | M |
| NFR-024 | Offline | Local-first | Every non-AI, non-sync feature works in flight mode, without an account, on Android and in the installed PWA | M |
| NFR-025 | Compatibility | Old clients never erase new data | Null collections in PUT = unchanged; unknown checklist keys kept | M |
| NFR-026 | Usability | PWA quality | Installable per Chromium criteria; app shell from the service worker < 2 s on repeat visits | S |
| NFR-027 | Durability | Browser storage | Persistent storage requested; iOS eviction warning; backup reminder after 30 days without a backup for guests (dismissible) | S |
| NFR-028 | Capacity | Server stays in the free DB | No full photos for new data; thumbnails ≤ 20 KB; per-user caps (1 000 houses, 2 000 thumbnails, configurable) | M |
| NFR-029 | Quality | AI custom export | p95 < 20 s; 100% of numbers verified on the eval set; contact leakage 0 without opt-in | S |
| NFR-030 | Battery | Area wake-up | Accepted, defined in [01](01-requirements.md) v0.12 | S |

### 7.3 Security

| ID | Requirement | Sprint |
|---|---|---|
| SEC-031 | Deny by default stays. Public: health, `/api/auth/config`, `/api/auth/nonce`, `/api/auth/google`. AI endpoints need a session of an invited user (or the owner's API key while mode `both`); no guest access. Everything else needs a Doorprints session (or the API key while mode `both`). | 5 |
| SEC-032 | Google ID tokens are verified (JWKS signature, `iss`, `aud`, `exp`, single-use server `nonce`, `email_verified`); users are keyed by `sub`, never by email. | 5 |
| SEC-033 | Doorprints session tokens: 256-bit random, stored as SHA-256, Bearer, sliding expiry (Android 90 days, web 30 days or browser session), revocation ≤ 60 s; Android Keystore-encrypted and excluded from backup. | 5 |
| SEC-034 | Device sessions can be listed, renamed and revoked; "sign out all others". | 5 |
| SEC-035 | Account deletion and "sign out all others" need a Google sign-in within the last 5 minutes. | 5 |
| SEC-036 | Rate limits: `/api/auth/google` 10/min per address; cloud AI per invited user per minute and per day, plus a global daily hard cap; 429 with `Retry-After`. | 5 |
| SEC-037 | Per-user authorization on every endpoint and change feed; other users' IDs → 404; automated IDOR test over every endpoint. | 5 |
| SEC-038 | RAG, planner and MCP filter by owner (AI team). | 5 |
| SEC-039 | Google Drive uses only `drive.file`, requested incrementally; Drive tokens stay on the device; the server never receives Drive tokens or full photos. | 5 |
| SEC-040 | Server thumbnails (and legacy full photos until moved) are encrypted with AES-256-GCM envelope encryption; `APP_DATA_KEY` required when accounts are on; rotation via `APP_DATA_KEY_NEXT`. | 5 |
| SEC-041 | Backup import validation (format, version, SHA-256, entry count, size, ratio, paths, DTO rules) before any write. | 4a |
| SEC-042 | Export output encoding: HTML escaped, no scripts, CSP meta; CSV/XLSX formula guard; Markdown control characters escaped; PDF holds only text and images (no JavaScript, no links). | 4a |
| SEC-043 | `ShareReceiverActivity` accepts only `text/plain`, ≤ 20 000 chars, `http(s)` URLs only, no network and no save without a user action. | 4b |
| SEC-044 | The service worker caches this build's own files only (as built: the whole build, one cache per build); IndexedDB and caches can be cleared on sign-out; no API responses in Cache Storage. | 4a |
| SEC-045 | AI custom export: data delimited as untrusted, no tools, instruction ≤ 500 chars, output validated for numbers and contacts, output rendered as text only. | 5 |
| SEC-046 | Security events audited (sign-in, revoke, Drive connect/disconnect, deletion) with salted address hashes, 90 days, visible to the user; no tokens in logs. | 5 |
| SEC-047 | The cloud AI allowlist is managed only by the owner; removing an invitation takes effect on the next request; the provider credential is a server secret (never in clients; least-privilege service account, 02 T-I22), capped by a spend cap budget on the AI project (5.13). (v0.2 guest installation ID withdrawn.) | 5 |
| SEC-048 | CSP changes for Google Identity Services are limited to `https://accounts.google.com/gsi/*` (script, frame, style) and apply only to the web app. | 5 |
| SEC-049 | Notification actions and boot receiver for reminders and area wake-up. Accepted, defined in [01](01-requirements.md) v0.12 | 4b |

### 7.4 Privacy

| ID | Requirement | Sprint |
|---|---|---|
| PRV-012 | Exports include contact data by default with a visible warning; "Leave out" removes contact name, phone and `withWhom` from every format. | 4a |
| PRV-013 | Phone numbers in shared listing text are not auto-filled; `withWhom` counts as contact data. | 4b |
| PRV-014 | A Doorprints account stores only Google `sub`, email, optional name, locale and security data; no postal address, phone or geo-IP. Guests have no server-side record. | 5 |
| PRV-015 | Deletion: 7-day grace, then all server data and the user's data key are erased; the user is told that Drive files and device data stay and how to remove them. | 5 |
| PRV-016 | Viewing reminders are private on the lock screen; calendar entries only on the user's tap. | 4b |
| PRV-017 | New free-text fields go to AI indexing only through `ContactRedactor`, and only when AI is on. | 4b |
| PRV-018 | Exported files leave Doorprints' control; the app says so; automatic backups are off by default. | 4a |
| PRV-019 | Google Drive files are the user's own; Doorprints sees only files it created; our server holds thumbnails and metadata only. Disclosed in PRV-007 (Google as a processor for sign-in and Drive). | 5 |
| PRV-020 | On-device AI sends nothing off the phone and says so; cloud AI (invited users) discloses the provider (AI-010) and uses only a paid tier or Vertex AI (01 PRV-022). | 5 |
| PRV-021 | AI custom export sends contact fields only with the user's explicit tick for that export; output is not stored on the server. | 5 |
| PRV-024..PRV-027 | Location permission model (5.18) and geofence privacy. Accepted (D-25), defined in [01](01-requirements.md) v0.12; PRV-001 amended there | 4b |

## 8. Data model changes

### 8.1 Flyway migrations (current highest: `V3__photo_tombstones_and_privacy.sql`)

| Version | File | Changes | Sprint |
|---|---|---|---|
| V4 | `V4__criteria_and_preferences.sql` | `criterion` (id, key UNIQUE, label, weight 0–3, must_have, min_score, sort, archived, updated_at, deleted, sync_version); seed 10 built-ins with weight 2; `preference` (id, key UNIQUE, value, updated_at, deleted, sync_version) | 4b |
| V5 | `V5__rooms_questions_house_fields.sql` | `house_room`, `question`, `house_answer`; `house.area_sqft`, `house.location_source` (default `GPS`) | 4b |
| V6 | `V6__photo_metadata.sql` | `photo.room_id` (no FK), `photo.tags text[]` (≤ 10), `photo.caption`, `photo.meta_updated_at` | 4b |
| V7 | `V7__viewings_and_visit_notes.sql` | `viewing`; `visit.notes`, `visit.viewing_id` | 4b |
| V8 | `V8__google_accounts.sql` | `app_user` (id, google_sub UNIQUE, email, display_name, locale, status ACTIVE/PENDING_DELETION, delete_after, role OWNER/USER, created_at, last_sign_in_at); `auth_nonce` (hash, expires_at, used); `device_session`; `audit_event`; `ai_usage` (day, user_id, count, tokens; invited users only, D-21); `app_user.ai_cloud_allowed` (boolean, set by the owner) | 5 |
| V9 | `V9__ownership.sql` | Nullable `owner_id` on `house`, `visit`, `photo`, `criterion`, `question`, `viewing`, `preference`; indexes `(owner_id, sync_version)`; unique `(owner_id, key)` | 5 |
| V10 | `V10__photo_storage_and_encryption.sql` | `photo.storage` (SERVER legacy / DEVICE / DRIVE), `photo.drive_file_id`, `photo.drive_missing`, `photo.thumb bytea`, `photo.enc_version`, `photo.key_id`, `photo.nonce`; `user_key` (user_id, kek_id, wrapped_dek) | 5 |
| V11 | `V11__owner_not_null_and_legacy_photos.sql` | Fail if any `owner_id IS NULL`, then NOT NULL; purge full bytes of photos already moved | 6 |

No password, TOTP, recovery-code, email-token or invite tables (v0.1 V8 replaced).

**Revised by 5.30 (2026-09-30):** the server's shipped migrations went on to `V4__device_keys_and_pairing.sql` and
`V5__server_secrets.sql` (ADR-25), so the 4b change is **`V6__records.sql`** (the one `record` table of 5.30 item 2, slice 0)
and **`V7__house_values.sql`** (the house's scalar and `jsonb` columns of item 1, slice 1), in place of the V4..V7 rows
above; V8 onwards renumber when Sprint 5 starts.

```mermaid
erDiagram
    APP_USER ||--o{ HOUSE : "owns (V9)"
    APP_USER ||--o{ CRITERION : "owns"
    APP_USER ||--o{ QUESTION : "owns"
    APP_USER ||--o{ DEVICE_SESSION : "signed in on"
    APP_USER ||--|| USER_KEY : "has data key"
    APP_USER ||--o{ AUDIT_EVENT : "logged"
    HOUSE ||--o{ HOUSE_CHECKLIST : "scored on"
    CRITERION ||--o{ HOUSE_CHECKLIST : "key = item"
    HOUSE ||--o{ HOUSE_ROOM : "has"
    HOUSE ||--o{ HOUSE_ANSWER : "has"
    QUESTION |o--o{ HOUSE_ANSWER : "asked as"
    HOUSE ||--o{ PHOTO : "has"
    HOUSE ||--o{ VIEWING : "planned"
    VIEWING |o--o| VISIT : "done as"
    APP_USER {
        uuid id PK
        varchar google_sub "unique, the identity"
        varchar email "from Google, informational"
        varchar status "ACTIVE PENDING_DELETION"
        timestamptz delete_after "grace end"
        varchar role "OWNER USER"
    }
    DEVICE_SESSION {
        uuid id PK
        uuid user_id FK
        varchar name "editable"
        varchar platform "ANDROID WEB"
        bytea token_hash "SHA-256"
        timestamptz last_seen_at
        timestamptz revoked_at
    }
    PHOTO {
        uuid id PK
        uuid house_id FK
        varchar storage "SERVER DEVICE DRIVE"
        varchar drive_file_id "nullable"
        boolean drive_missing
        bytea thumb "AES-GCM, max 20 KB"
        uuid room_id "no FK"
        text_array tags "max 10"
    }
    CRITERION {
        uuid id PK
        varchar key "unique per owner"
        int weight "0 to 3"
        boolean must_have
        int min_score
    }
    VIEWING {
        uuid id PK
        uuid house_id FK
        timestamptz starts_at
        varchar kind "FIRST SECOND FOLLOW_UP"
        varchar status "PLANNED DONE CANCELLED MISSED"
        int remind_min
    }
```

### 8.2 Local stores

| Store | Version | Change | Sprint |
|---|---|---|---|
| Room | 2 (unchanged) | 4a exporters read today's schema. `exportSchema` is on since Sprint 3.5 (`2.json` committed, `RoomSchemaTest`); the `MigrationTestHelper` test (R-06) is done in CMP-4 P4a ([06](06-test-plan.md) TC-U-63) | 4a |
| Room | 3 | `track_points`, the path trace (5.27, S4b-FR-2; on the phone only, never in a backup). `3.json`, `MIGRATION_2_3` tested | 4a (built 2026-09-29) |
| Room | 4 | The 4b model of **5.30**, slice 0: one `records` table (`type`, `id`, `payload` JSON, `updatedAt`, `deleted`, `dirty`) for criteria, questions, viewings, hunting areas, places, area notes, brokers, photo metadata, the move-in record and preferences. `4.json`, `MIGRATION_3_4` tested | 4b |
| Room | 5 | Slice 1: `houses.cost_*` (embedded), `rooms`/`answers` (JSON), `areaSqft`, `locationSource`, `brokerId`; the contacts migrated into brokers. `5.json`, `MIGRATION_4_5` tested | 4b |
| Room | 6 | `photos.storage`, `driveFileId`, `driveMissing`, `thumbOnly`; bound account ID in encrypted settings | 5 |
| Room | 11 (built on `feat/path-trace-v2`) | `saved_walks` (5.27.6, [03](03-design.md) §6.2): the walks the person saved, linked to a house; on the phone only, never in a backup. `track_points` gains `walkId`. `11.json`, `MIGRATION_10_11` tested | S4b-FR-14 |
| IndexedDB | 3 (built on `feat/path-trace-v2`; `DB_VERSION` is 2 on `main`) | `trace_points` and `saved_walks` stores (5.27.8), website only, never exported; `upgradeLocalDb` step `oldVersion < 3` | S4b-FR-17 |
| IndexedDB | 1 → 4 | Mirrors Room 2 (version 1, 4a), Room 4 and 5 (versions 2 and 3, 4b, with the upgrade path S4b-BL-71), Room 6 (version 4, 5); the hand-written wrapper of `local-db.ts`, no Dexie (4a decision) | 4a–5 |

Definition of done for every 4b/5 story that adds data: the new fields appear in **all six export formats** and round-trip through the JSON backup.

## 9. API changes

| Method | Path | Notes | Sprint |
|---|---|---|---|
| GET, PUT, DELETE | `/api/criteria`, `/api/questions`, `/api/viewings`, `/api/preferences` (+ `/{id}` or `/{key}`) | Sync pattern with `since`, LWW, tombstones, caps | 4b |
| PUT | `/api/houses/{id}` | HouseDto v2: `rooms[]`, `answers[]`, `areaSqft`, `locationSource` (null = unchanged) | 4b |
| PUT | `/api/photos/{id}/meta` | Room, tags, caption; in the photo change feed | 4b |
| PUT | `/api/visits/{id}` | `notes`, `viewingId` | 4b |
| GET | `/api/auth/config` (public) | Google client IDs, server mode, whether cloud AI is available to this user | 5 |
| GET, POST | `/api/auth/nonce`, `/api/auth/google`, `/api/auth/logout` | 5.11 | 5 |
| POST | `/api/auth/claim` | API key + signed-in session; only while no OWNER exists | 5 |
| GET, PATCH | `/api/account` | Profile, locale, storage mode | 5 |
| DELETE, POST | `/api/account`, `/api/account/deletion/cancel` | 7-day grace (fresh sign-in) | 5 |
| GET, PATCH, DELETE, POST | `/api/account/devices`, `/{id}`, `/revoke-others` | FR-076 | 5 |
| GET | `/api/account/activity` | Audit, 90 days | 5 |
| PUT, GET | `/api/photos/{id}/thumbnail` | Upload/read the encrypted thumbnail; `storage`, `driveFileId` in metadata | 5 |
| POST | `/api/photos/{id}/release` | Server drops legacy full bytes after the device confirms the photo is in Drive or on the device | 5 |
| GET | `/api/ai/allowance` | Remaining cloud requests today for an invited user (403 otherwise) | 5 |
| POST | `/api/ai/custom-export` | 5.3 | 5 |
| (changed) | all `/api/**` data endpoints; `/api/ai/*` | Owner-scoped; AI only for invited users | 5 |

Dropped from v0.1: `/api/backup/data`, `/api/import/batch` (devices export and import locally), all password, 2FA, email and invite endpoints. *As built:* Sprint 4a added `POST /api/import` (restore a `doorprints-backup/1` `data.json` onto the server, `?dryRun=true` for a preview) and changed `GET /api/export` to emit that format ([03](03-design.md) §9).

## 10. UI changes

| Area | Android | Web / PWA | Sprint |
|---|---|---|---|
| App start | Unchanged (local) | Opens to the map with local data; no Connect page required | 4a |
| Add a house at a chosen spot | **Today:** *Add a house on the map* (`common_add_on_map`, on the house list's first run and the Export and Compare empty states) switches to the Map tab; from the house list it also shows a tip in a snackbar (`map_add_tip`: "Tip: tap ‘Save house here’ when you are at the house, or long-press the map to place one anywhere.", or `map_add_tip_a11y` with TalkBack or without location). *Save house here* uses the current location. Long-press is not available to TalkBack or switch-access users ([05](05-ux-accessibility-i18n.md) A11Y-B02), and the current location is wrong for someone planning from home. **Parity target (Android handover 22, `android/shared/README.md` 1.17): an accessible pick-a-spot mode** — entered from *Add a house on the map*, a centre crosshair over the map plus a labelled 48 dp *Save house at centre* button, the Android equivalent of the web's add mode | Add mode: *Add house* puts the map into add mode (crosshair, *Place here*, the hint of [05](05-ux-accessibility-i18n.md) §5); without WebGL 2, *Add at my location* and *Type latitude and longitude* | Android pick-a-spot mode: **4b** |
| Map orientation | North-up: rotation, tilt and the compass off (`MAP_NORTH_UP`, 1.31) | North-up and flat in every map (`createMlMap`: no drag-rotate, twist, tilt or Shift+arrow rotate; Android handover 33, in the working tree since 2026-09-23) | 4a |
| Map boundaries (India) | `applyIndiaView(style)` (`ui/IndiaView.kt`, rules as data in `ui/IndiaViewRules.kt`) from `MapScreen.loadStyle`, before the house layers, on every style load; data `asset://geo/in-boundaries.geojson`; a skipped rule is `Log.w` (tag `IndiaView`). No "Natural Earth" attribution credit (MapLibre Android 13.6.1 has no API for a runtime source's attribution; public domain, so none is required) | `applyIndiaBoundaries` (`shared/india-boundaries.ts`) registered by `createMlMap` on every `style.load` (Map, Plan, house pages; also after `watchMapStyle`'s offline retry); data `geo/in-boundaries.geojson` against the base href, precached; a skipped rule is `console.warn`; "Natural Earth" in the attribution. **The same five rules on both** (compared rule by rule with the code at HEAD `3ad2b58`, 2026-09-24, and the India-China rule and the state layer at `9e0036e`, branch `fix/india-boundary-lines`): the same file (sha256 `25984afa…a024`, kinds `world`, `claim` and `state`; byte identity held by `IndiaBoundaryDataTest`), the same ids (`in-boundaries`, `in-boundary-world`, `in-boundary-claim`, `in-boundary-state`), the state layer from zoom 5 directly above `boundary_3` with its paint copied, else directly below `in-boundary-world` with Liberty's `boundary_3` values (web `STATE_FALLBACK_LINE_PAINT`, Android `STATE_FALLBACK_LINE_*` and `statePlacement()`), rules 1, 3, 4 and 5, all of rule 2: its minzoom `max(own, 5)`, the country-line rule (an adm0 side present, not a PAK/CHN pair, and not India's line with China, that is CHN on one side and IND or no country on the other, `INDIA_CHINA_LINE` on both; web `COUNTRY_LINE_RULE` and `COUNTRY_LINE_RULE_LEGACY`, Android `COUNTRY_LINE_EXTRA_FILTER` and `_LEGACY`) and the tile-zoom guard `[">=", ["zoom"], 5]` on `boundary_2`, `boundary_3` and every other `boundary` line layer from zoom 5, never `boundary_disputed`, and skipped with a warning on a deprecated-syntax filter (web `TILE_ZOOM_GUARD`, Android `TILE_ZOOM_GUARD`), the name lists, the placement and fallback paint, the deprecated-syntax safety net. The world lines hand over at exactly zoom 5.0 on both: the web's maxzoom 5 is exclusive in maplibre-gl, and Android's is `Math.nextDown(5f)` because maplibre-native includes both ends of a zoom range; the same behaviour, not a difference. Both renderers already leave a zoom 0-4 tile out of a minzoom 5 layer (maplibre-gl 6.11.2 `worker_tile.ts:110`, maplibre-native android-v13.6.1 `geometry_tile.cpp:317`, read from source), so the guard is defence in depth on both ([03](03-design.md) ADR-22 rule 2). **Deliberate difference:** the "Natural Earth" credit, web only. **Known limits, the same on both:** along the 7 stretches where the tiles draw India's border themselves, no line at zoom 5+ while those tiles load or offline without them; the small steps at the hand-overs and loops at Sikkim's two tri-junctions (about 13 x 3 km at Nepal-China-India (on glaciers, seen only from about zoom 10) and about 2 km at Doklam) and, from about zoom 10 (a small hook at Jomotsangkha from zoom 9), the tile line running on past the hand-over at Jomotsangkha (about 9 km) and Longwa (about 3 km) (the two close lines and the missing Assam-Arunachal Pradesh state line are fixed on `fix/india-boundary-lines`; [10](10-sprint-log.md) S4b-BL-11, S4b-BL-15, S4b-BL-16) (D-26, [03](03-design.md) ADR-22, [05](05-ux-accessibility-i18n.md) §7.3) | 4a fix (2026-09-24); no parity gap |
| Export and import | Settings → "Your data": format list (HTML, PDF, Spreadsheet CSV/XLSX, Markdown, Full backup), options sheet, save target (device, share, Drive when connected), Import backup, Automatic backup | Same, page `/data` | 4a |
| AI custom export | "Your data" → "Custom (AI)": house picker, text box with examples, language, style, contact tick, result with AI label, number warnings, Copy/Share/Save | Same | 5 |
| Parity screens | Criteria, ranking, compare additions, questions, rooms, photo tags, viewings timeline, reminders, second viewing, share draft (as v0.1) | Same (with `.ics`) | 4b |
| Hunt mode reminders, hunting areas | Settings → Hunt mode: reminder switch and lead time, "Wake me in my hunting areas"; per-viewing reminder switch; map layer and editor for areas (circle, radius, name, list); background-location rationale screen and settings hand-off; one-time notice when area wake-up switched itself off (5.16..5.18) | Areas listed read-only after sync (Sprint 5); no geofencing in the PWA | 4b |
| PWA | – | Install item, iOS help, update snackbar, storage used, iOS eviction warning | 4a |
| Account | Settings → "Sync and account": Sign in with Google (explains what it unlocks), signed-in devices, activity, photo storage (device / Drive), Drive status and Reconnect, delete account; "Use my own server" (advanced) | Same page `/account` | 5 |
| AI access | On-device badge "Runs on this phone"; model download on Wi-Fi; AI hidden when unsupported; invited users see the remaining cloud count; owner's "Invite to cloud AI" screen (5.13) | No on-device AI; cloud AI only for invited users | 5 |

## 11. i18n and accessibility

| Topic | Note |
|---|---|
| Strings | About 300 new strings × en/hi/ta/te (web build fails on missing keys; Android lint). Native-speaker review, especially for the sign-in prompt and deletion texts, which must stay neutral and non-manipulative in every language. |
| Glossary (05) | criterion, weight, must-have, best match, coverage, viewing, second viewing, question bank, carpet area, offline copy, full backup, custom export (AI), guest, sign in with Google, signed-in devices, sync, Google Drive storage, grace period |
| Export languages | All six formats use the chosen language for labels and `en-IN`/locale formats; ₹ lakh/crore grouping; HTML/Markdown `lang`; PDF via Android `StaticLayout` shapes Indic scripts; XLSX/CSV keep UTF-8 (CSV with BOM). Column keys in CSV/XLSX stay English for tools, with a translated header row option. HTML font stack `system-ui, "Noto Sans", "Noto Sans Devanagari", "Noto Sans Tamil", "Noto Sans Telugu", "Nirmala UI", sans-serif`. |
| AI output language | The custom export writes in the chosen language; numbers always in Western digits (validation). |
| Web a11y (WCAG 2.2 AA) | Radio groups for weights; reorder with buttons (2.5.7); announcements via `AnnouncerService`; Google button with an accessible name; remaining cloud count as text, not colour; dialogs focus-trapped with Escape = cancel; 24 px targets (2.5.8); no time limits. |
| Android a11y | 48 dp, TalkBack labels ("Condition 3 of 5", "Weight: High"), 200% font (NFR-020). |
| Exports | Semantic headings and tables with `<th scope>`, alt text from room + tags + caption, tagged PDF not available from `PdfDocument` (known limit: the HTML copy is the accessible one), XLSX header row and sheet names. |

## 12. Security, privacy and threats

### 12.1 New STRIDE items (proposed for 02)

| ID | STRIDE | Threat | Mitigation | Req |
|---|---|---|---|---|
| T-S8 | Spoofing | Attacker with the victim's Google session signs in to Doorprints | Delegated to Google (2-Step Verification recommended in-app); device list, activity, revoke | FR-076, SEC-046 |
| T-S9 | Spoofing | Forged or replayed Google ID token, token for another app | Full verification, single-use nonce, `aud` check | SEC-032 |
| T-S10 | Spoofing | Doorprints session token stolen from web storage via XSS (incl. a compromised third-party script) | Strict CSP, GIS limited to `accounts.google.com/gsi`, token lifetime, revoke | SEC-033, SEC-048 |
| T-S11 | Spoofing | Crafted share intent from a malicious app | Caps, allowlist, no auto network/save | SEC-043 |
| T-T8 | Tampering | Malicious backup ZIP (zip slip, bomb, overwrite newer data) | Validation, preview, LWW, import as copy | SEC-041 |
| T-T9 | Tampering | Injection through notes into HTML, CSV, XLSX, Markdown exports | Output encoding, no scripts, formula guard | SEC-042 |
| T-T10 | Tampering | Old client erases new fields | Null = unchanged | NFR-025 |
| T-T11 | Tampering | AI custom export invents or changes prices, rents or sizes | Number validation, retry, highlighted warnings, AI label, never a backup | FR-050, SEC-045 |
| T-T12 | Tampering | Prompt injection in notes steers the custom export | Untrusted delimiting, no tools, text-only output | SEC-045, AI-008 |
| T-R3 | Repudiation | No record of sign-ins, revokes, deletion | Audit events | SEC-046 |
| T-I12 | Info disclosure | IDOR between Google users | Owner filters, 404, IDOR matrix | SEC-037 |
| T-I13 | Info disclosure | Exports with contacts leak from Downloads/Drive/chats | Warning, "Leave out", automatic backups off | PRV-012, PRV-018 |
| T-I14 | Info disclosure | `APP_DATA_KEY` leaked or lost | Host secret store, rotation; impact now thumbnails only | SEC-040 |
| T-I15 | Info disclosure | RAG/planner/MCP cross-user results | Owner filter, reindex, tests | SEC-038 |
| T-I16 | Info disclosure | Reminders on the lock screen | Private notifications | PRV-016 |
| T-I17 | Info disclosure | Local data left in a shared browser | Sign-out choice to remove local data | SEC-044, FR-082 |
| T-I18 | Info disclosure | Over-broad Google scopes or Drive tokens on our server | `drive.file` only, incremental, tokens stay on the device | SEC-039 |
| T-I19 | Info disclosure | Custom export sends or prints contact numbers | Contact fields only with opt-in, output scan | PRV-021 |
| T-D7 | Denial of service / cost | An invited user's stolen session or a bug drains the paid AI budget | Per-user daily quota, global hard cap, spend cap budget on the AI project, budget alerts; guests have no cloud AI | SEC-036, SEC-047 |
| T-D8 | Denial of service | Large export/import exhausts phone or browser memory | Streaming, limits | NFR-021, SEC-041 |
| T-E6 | Elevation | Someone claims an existing install | Claim needs the API key and works only while no OWNER exists | FR-081 |
| T-E7 | Elevation | Old shared API key keeps access after accounts | Mode `both` maps it to the OWNER only; removed in Sprint 6 | SEC-031 |
| T-E8 | Elevation | Another app triggers "Start Hunt mode" or the boot receiver to start location tracking | Notification-action and alarm `PendingIntent`s are immutable (`FLAG_IMMUTABLE`) and explicit to non-exported components; the geofencing `PendingIntent` is **mutable** (`FLAG_MUTABLE`, required by the Geofencing API on Android 12+ so Play services can add the event) but explicit (component set) to a non-exported receiver (5.17); the service starts only from the user's notification tap; boot receiver only re-registers | SEC-049 |
| T-I23 | Info disclosure | Over-broad location permission: background location held for longer or for more than area wake-up needs (least privilege) | Foreground-only by default; background only while area wake-up is on, after a rationale; auto-off when revoked; no own background location requests; re-check on resume | PRV-024, PRV-025, PRV-026 |
| T-I24 | Info disclosure | Hunting areas reveal where the user plans to live (stolen phone, shared export) | Stored on the device only (encrypted storage, AS-02), in exports under the same warnings as other data, no geofence history kept | PRV-027, PRV-018 |

### 12.2 New data flows (proposed for 04)

| ID | Flow | Data |
|---|---|---|
| DF-33 | Portal app / browser → `ShareReceiverActivity` | Listing text, URL (untrusted) |
| DF-34 | Device → user storage (SAF, download, share targets) | Exports incl. contacts and photos |
| DF-35 | Device ↔ Google Sign-In; ID token → Doorprints API | Google `sub`, email, name |
| DF-36 | Device ↔ Google Drive API (never through our server) | Full photos, backups |
| DF-37 | Device → Doorprints API | Thumbnails, photo metadata, synced records |
| DF-38 | Device → calendar provider (tap) / `.ics` file | House label, time |
| DF-39 | Device → API → LLM provider (custom export, invited users only) | Selected structured data, instruction; paid tier or Vertex AI only. On-device AI has no external flow |
| DF-40 | App ↔ Google Play services location (on the device): geofence registration and ENTER events | Area centres and radii; the device's location stays inside Play services (Google's location services may use it as for fused location, PRV-007) |

### 12.3 Residual risks (proposed for 02)

| ID | Risk | Level |
|---|---|---|
| RR-07 | Exported files are outside Doorprints' control | Medium (accepted, warned) |
| RR-08 | Account security equals the user's Google account security | Medium (accepted by D-01; 2SV recommended) |
| RR-09 | Paid AI spend up to the hard cap if an invited account is misused | Low (hard cap, alerts) |
| RR-10 | Browser storage eviction (Safari) for guests who do not install or back up | Medium (warnings, backup reminder) |

## 13. Test cases (proposed for 06)

| ID | Level | Test | Req |
|---|---|---|---|
| TC-U-22 | Unit (Android + web, shared vectors) | Weighted score; default weights equal FR-005 | FR-052 |
| TC-U-23 | Unit | Ranking order | FR-053, FR-054 |
| TC-U-24 | Unit (Kotlin + TS) | No-AI parser fixtures for all four portals | FR-066 |
| TC-U-25 | Unit | URL cleaning, portal detection, duplicates, bad schemes | FR-066, FR-069, SEC-043 |
| TC-U-26 | Unit (both platforms) | **Golden-file exports**: fixture data → HTML, CSV, XLSX (unzipped XML), Markdown, JSON identical to checked-in files; PDF page count and extracted text | FR-042, NFR-023 |
| TC-U-27 | Unit | Output encoding: script/attribute payloads inert in HTML; formula guard in CSV/XLSX; Markdown escaping; contacts absent with "Leave out" | SEC-042, PRV-012 |
| TC-U-28 | Unit | Import validator: zip slip, bomb, entry count, hash mismatch, newer version | SEC-041 |
| TC-U-29 | Unit | Backup round trip: export → import on an empty store → deep-equal | FR-044, FR-047 |
| TC-U-30 | Unit (Robolectric) | Reminder scheduling, reboot, time-zone change | FR-063 |
| TC-U-31 | Unit (web) | IndexedDB repository and sync engine with a fake API (cursor, LWW, tombstones, photo metadata) | FR-071 |
| TC-U-32 | Unit | AI custom export validator: Indian number formats, derived values, mismatches flagged, phone scrub | FR-050, SEC-045 |
| TC-U-33 | Unit | Thumbnail AES-GCM envelope, wrong AAD fails, KEK re-wrap | SEC-040 |
| TC-U-34 | Unit | AI access: allowlist check, per-user quota, IST reset, global hard cap; on-device feature-status handling hides AI when unsupported | FR-079, SEC-036 |
| TC-I-22 | Integration | Sync of criteria, questions, viewings, preferences | FR-051, FR-056, FR-062 |
| TC-I-23 | Integration | Null-collection semantics; custom keys round-trip | NFR-025 |
| TC-I-24 | Integration | Flyway V4–V7 on a V3 DB with data; scores unchanged | FR-052 |
| TC-I-25 | Android | Room 2 → 3 → 4 migrations | R-06 |
| TC-I-26 | Contract | Google ID-token verification against a local JWKS and recorded Google responses (10 §1 Process) | SEC-032 |
| TC-I-27 | Contract | Drive client against recorded responses: create folder, resumable upload, 401 `invalid_grant`, 404, trashed, `storageQuotaExceeded`, 429 | FR-078 |
| TC-I-28 | Integration | Sign-in, sessions, revoke ≤ 60 s, sign out others, fresh-sign-in rule | FR-074, FR-076, SEC-035 |
| TC-I-29 | Integration | IDOR matrix over every endpoint and feed | SEC-037 |
| TC-I-30 | Integration | Claim, mode `both`, legacy photo release | FR-081, SEC-031 |
| TC-I-31 | Integration | Deletion: grace, cancel, purge job, crypto-shred | FR-080, PRV-015 |
| TC-I-32 | Integration (AI) | RAG/planner/MCP owner filter; custom-export golden cases (numbers, languages, contacts, injection) | SEC-038, NFR-029 |
| TC-S-16 | Security | Exported HTML with payloads opened from `file://`: nothing runs | SEC-042 |
| TC-S-17 | Security | Malicious backups rejected without writes | SEC-041 |
| TC-S-18 | Security | Share-intent fuzzing | SEC-043 |
| TC-S-19 | Security | Cache Storage has no API data; "remove local data" clears IndexedDB | SEC-044 |
| TC-S-20 | Security | Server never receives Drive tokens or full photos (proxy capture of a sync session) | SEC-039 |
| TC-S-21 | Security | Cloud AI without an invitation (guest, non-invited user, removed invitation) gets 403; global cap stops cloud calls | SEC-031, SEC-036, SEC-047 |
| TC-M-12 | Manual (Android) | Each format in flight mode without account; save to Drive app via SAF; uninstall; open on laptop; print PDF | FR-045, NFR-022 |
| TC-M-13 | Manual | Share from MagicBricks, 99acres, NoBroker, Housing.com apps and Chrome | FR-066 |
| TC-M-14 | Manual | Reminders under Doze, reboot, on 2 Indian-market OEM phones; PWA reminder while open; `.ics` import | FR-063 |
| TC-M-15 | Manual | PWA install and offline use on Chrome Android, Samsung Internet, iOS Safari, desktop; iOS eviction warning | FR-070..FR-073 |
| TC-M-16 | Manual | Google sign-in, Drive connect, revoke access in Google settings, delete a file in Drive, Drive full | FR-074, FR-077, FR-078 |
| TC-A-08 | Accessibility (web) | axe + keyboard on new pages incl. sign-in prompt and custom export | NFR-006 |
| TC-A-09 | Accessibility (Android) | TalkBack, 200% font on new screens | NFR-020 |
| TC-A-10 | Accessibility / content | Exports in 4 languages: headings, tables, alt text, print | FR-043 |
| TC-A-11 | UX review | Sign-in prompt and deletion flow checked against a dark-pattern checklist (equal buttons, no timers, no confirmshaming, shown ≤ once/day) in all 4 languages | FR-079, FR-080 |
| TC-P-05 | Performance | Export timings on a mid-range phone | NFR-021 |
| TC-U-38 | Unit (Robolectric) | Hunt mode reminder: lead time 5 to 60, per-viewing and global switches; with `canScheduleExactAlarms()` false the scheduler calls `setWindow` with window start `T − 10 min` and length 10 min (never a window that starts at or after T), and makes no exact call; with it true, `setExactAndAllowWhileIdle` at T; `T − 10 min` in the past schedules at now; reschedule on permission-granted broadcast, resume, boot, time zone, edit, cancel; WorkManager fallback; merged with the viewing reminder when due within 10 min of each other; Settings note and **Allow on-time reminders** button shown only without exact alarms | FR-083, FR-084 |
| TC-U-39 | Unit (`:shared` commonTest) | `HuntingArea` validation (radius 200 m to 2 km, name, 20-area cap) and `AreaCooldown` (6 h per area, Dismiss counts, none while Hunt mode is on) | FR-085, FR-086 |
| TC-U-40 | Unit | Permission state → feature state: foreground only, "Only this time", approximate only, background granted/denied/revoked (area wake-up switches off, Hunt mode unaffected) | PRV-024..PRV-026 |
| TC-M-18 | Manual (Android 10, 11, 12, 13, 14+ and one Indian-market OEM phone) | **Permission matrix**: first launch asks foreground only; "Only this time" re-asks at the next Hunt start; approximate only explained; area wake-up rationale → settings hand-off → granted; downgrade to "While using the app" in system settings → area wake-up off with notice, Hunt mode still starts; reminder and area notifications in 4 languages and under Do Not Disturb | PRV-024..PRV-027, FR-083..FR-088 |
| TC-F-12 | Field | Walk into a hunting area: notification within a few minutes, no tracking before the tap, tap starts Hunt mode with the app closed; second entry within 6 h is silent; after a reboot the areas still work; battery over a day with 20 areas (NFR-030) | FR-086, FR-087, NFR-030 |
| TC-S-22 | Security | `adb shell am broadcast` / `am startservice` from another app cannot start Hunt mode or register geofences; `PendingIntent` flags checked in a manifest and code review: notification-action and alarm intents immutable, the geofencing intent mutable and explicit to a non-exported receiver (a reviewer must not force `FLAG_IMMUTABLE` on it, which breaks geofence delivery) | SEC-049 |
| TC-A-12 | Accessibility / content | Rationale screen and reminder/area texts: TalkBack, 200% font, equal Allow/Not now buttons, 4 languages, Design Director review | FR-088, PRV-025 |

## 14. Phased plan and sizing

Story points (1, 2, 3, 5, 8, 13); Sprint 2 was about 35 points. The v0.1 Sprint 4 (81 points) is split into **4a** and
**4b** because making the web app local-first (IndexedDB + sync engine) adds a large story, and every parity feature
should be built once, on top of the local stores. Stories below a cut line move to the next sprint if time runs out.

### 14.1 Sprint 4a: local-first web, offline copy in all formats, PWA

Goal: anyone can install Doorprints (APK or PWA), use it without an account, and export or restore everything offline.

| ID | Story | Teams | Pts | Depends on |
|---|---|---|---|---|
| S4-00 | Foundations: HouseDto null semantics (NFR-025), Room migration test (R-06, done in CMP-4 P4a, [06](06-test-plan.md) TC-U-63; `exportSchema` and `RoomSchemaTest` already landed in Sprint 3.5), shared fixture files (scores, parser, export golden files) | Backend, Android, Web | 3 | – |
| S4-01 | Web local-first: IndexedDB repository, TS sync engine against today's API-key server (optional), "download my houses to this browser" migration, persistent storage | Web | 13 | S4-00 |
| S4-02 | Android exporters: HTML, PDF, CSV, XLSX, Markdown, JSON backup; options; SAF, share | Android, Design | 13 | S4-00 |
| S4-03 | Web exporters: same six formats (PDF via print view) from IndexedDB; download, Web Share | Web, Design | 8 | S4-01 |
| S4-04 | Import JSON backup on Android and web (validation, preview, merge, as copy) | Android, Web | 5 | S4-02, S4-03 |
| S4-05 | PWA: manifest, service worker, install item, iOS help, share-target route, `_headers`, storage warnings | Web, DevOps / Security | 3 | S4-01 |
| S4-06 | Docs: 01, 02, 03, 04, 05, 06, 10 updated for 4a; README repository-rename links (after the owner renames the repo) | Docs | 3 | all |
| | **Cut line** (48 pts) | | | |
| S4-07 | Android automatic weekly backup to a chosen folder | Android | 3 | S4-02 |
| | **Sprint 4a total** | | **51** | |

### 14.2 Sprint 4b: SeenHouse parity features

| ID | Story | Teams | Pts | Depends on |
|---|---|---|---|---|
| S4-08 | Backend V4–V7 and sync endpoints (criteria, questions, viewings, preferences, HouseDto v2, photo metadata, visit notes); Room 3 and IndexedDB 2 | Backend, Android, Web | 8 | S4-00 |
| S4-09 | Weighted criteria, must-haves, ranking, compare additions (+ exports) | Android, Web, Design | 8 | S4-08 |
| S4-10 | Viewing question bank and answers, India defaults in 4 languages (+ exports) | Android, Web, Design | 5 | S4-08 |
| S4-11 | Rooms, carpet area, units, **and the real cost of a house (5.21, D-30): deposit, maintenance, brokerage, lock-in, notice, available from, my offer, agreed price; monthly cost, money to move in, cost per sq ft** (+ exports) | Android, Web | 8 | S4-08 |
| S4-12 | Viewings, local reminders (Android alarms, PWA notifications, `.ics`, calendar), second viewing, timeline and search | Android, Web | 8 | S4-08 |
| S4-21 | Moving in (5.24, D-30): *Taken* status, move-in checklist, condition record with tagged photos, close the hunt | Android, Web, Design | 3 | S4-12, S4-15 |
| S4-13 | Share to Doorprints, no-AI parser (Kotlin + TS), "Where is it?", APPROX houses, duplicates, PWA share target and paste box | Android, Web | 5 | S4-05 |
| S4-14 | Docs and translations for 4b | Docs, Design | 3 | all |
| S4-17 | Hunt mode reminders (5.16, D-23): scheduler, notification actions, settings, 4 languages | Android, Design | 3 | S4-12 |
| S4-18 | Hunting areas and area wake-up (5.17, D-24): Room table, map editor, geofence registrar, boot/package receivers, cooldown in `:shared`, exports | Android, Design | 8 | S4-08, S4-19 |
| S4-20 | My places and distances (5.22) and area notes (5.23), D-30 | Android, Web, Design | 5 | S4-18 |
| S4-22 | Brokers (5.25, D-30): broker contacts, links, migration of existing contacts, duplicate warning | Android, Web | 3 | S4-13 |
| S4-19 | Location permission model (5.18, D-25): foreground-only flow, rationale screen, settings hand-off, re-check on resume, approximate handling; Design Director review; permission matrix TC-M-18 | Android, Design, Security | 3 | – |
| | **Cut line** (56 pts; was 42 before D-23..D-25) | | | |
| S4-15 | Photo tags, room link, caption, gallery filters (+ exports) | Android, Web | 3 | S4-08, S4-11 |
| S4-16 | Carry-ins: C-19 local labels for `[contact]` (2), C-20 AI-enabled CI smoke test (2) | Android, Web, DevOps | 4 | – |
| | **Sprint 4b total** | | **77** (63 + 14 for D-30) | |

**D-30 (2026-09-29)** adds S4-20, S4-21 and S4-22 and widens S4-11 (+14 points); the S4-08 migration takes their fields
too, so the data model and the sync, backup and export formats change once. Two D-30 items come **before** Sprint 4b:
the app lock (5.19) with Google sign-in, and offline maps (5.20) with the path trace (S4b-FR-2); order in
[14](14-lead-backlog-and-handoff.md) N13. Voice notes (5.26) are parked.

The three new stories (product owner, 2026-09-22) push Sprint 4b well past the ~35-point history (RK-01). If the
product owner wants to keep it near 49 points, the candidates to move to Sprint 5 are S4-13 (Share to Doorprints) and
S4-15; S4-19 must stay with S4-18, because area wake-up must not ship without the permission model.

**Also for Sprint 4b, not yet pointed** (Android handover 22, 2026-09-23): the Android **pick-a-spot add mode** of
section 10 (centre crosshair, *Save house at centre*, 48 dp and labelled), so that adding a house at a chosen spot
no longer depends on a long-press. Owners: Android and Design; it belongs with the web import's parity work in
[10](10-sprint-log.md) §12.

### 14.3 Sprint 5: Google Sign-In, sync ownership, Drive, trusted devices, deletion

| ID | Story | Teams | Pts | Depends on |
|---|---|---|---|---|
| S5-01 | Spike + ADR-15 (identity) and ADR-16 (Drive) (renumbered in v0.6: ADR-14 is the KMP decision in [03](03-design.md)): Credential Manager, `AuthorizationClient` with `drive.file`, GIS on the web and its CSP, cross-client visibility of `drive.file` files, ID-token verification library (e.g. Nimbus via `spring-security-oauth2-jose`), OAuth consent screen requirements | Backend, Android, Web, Security | 3 | – |
| S5-02 | Google Sign-In on Android and web; V8; nonce, ID-token check, device sessions, rate limits, audit | Backend, Android, Web | 8 | S5-01 |
| S5-03 | Sync ownership per Google user: V9, owner filters, per-user lock, IDOR matrix, RAG/planner/MCP owner filter (+ C-21 fixture), claim and mode `both`, first-sign-in upload and account binding | Backend, AI, Android, Web | 8 | S5-02 |
| S5-04 | Signed-in devices: list, rename, revoke, sign out others, activity | Backend, Android, Web | 3 | S5-02 |
| S5-05 | Google Drive storage: photos (Android, PWA), thumbnails to server (V10, encryption, `APP_DATA_KEY`), revoked/deleted/full handling, exports and backups to Drive, "move my photos" for legacy server photos | Backend, Android, Web | 13 | S5-03 |
| S5-06 | Account deletion with 7-day grace, purge job, crypto-shred, UI texts | Backend, Android, Web | 3 | S5-03 |
| S5-07 | AI access tiers (D-21): cloud AI allowlist and invite screen, per-user quota, global hard cap; Android on-device AI spike and integration (ML Kit GenAI Prompt API, feature status, model download); AI hidden where unsupported | Backend, AI, Android, Web, Design | 5 | S5-02 |
| S5-08 | Security review (ASVS L2: V3 sessions, V4 access control, V6 crypto, V51 OAuth) and docs incl. section 3.1 equivalence | Security, Docs | 3 | all |
| | **Cut line** (46 pts) | | | |
| S5-09 | AI custom export: endpoint, number and contact validators, UI, golden eval cases | AI, Android, Web | 8 | S5-07 |
| S5-10 | Automatic backups to Google Drive (extends S4-07) | Android | 2 | S5-05 |
| | **Sprint 5 total** | | **56** | |

Release order in Sprint 5: deploy V8–V10 with `app.auth.mode = both` → owner signs in with Google and claims →
new Android and web builds → check sync and Drive on every device → keep `both` until Sprint 6.

### 14.4 Sprint 6 and later

| Item | Place | Reason |
|---|---|---|
| V11, mode `accounts`, API key removed; legacy full photos purged | Sprint 6 | After all devices run the Sprint 5 build |
| OAuth consent screen publishing (brand verification, privacy policy page, authorised domain) | Before inviting the public (Sprint 6) | Google limits unverified apps; see RK-06 |
| C-02, C-03 (release pipeline, R8) | Sprint 6 | Before a public launch |
| C-15 natural-language map filter | Sprint 6 | Filter schema includes criteria, rooms, viewings; follows the AI access tiers (D-21) |
| C-16 voice notes | Sprint 6 | Fills answers and room notes; transcript only to the provider |
| C-18 alert one-liner (+ Directions, already in S4-12) | Sprint 6 | Uses weighted score and must-haves |
| C-14 offline map areas | Sprint 6 | Local-first fits; OpenFreeMap bulk-download terms first |
| C-17 neighbourhood summary | Sprint 7+ | New third-party flows with coordinates |
| C-19, C-20 | Sprint 4b (S4-16) | Small, overdue |
| C-21 | Sprint 5 (S5-03) | With the owner-filter tests |
| C-04 per-device keys | Closed, superseded by S5-04 | |
| Other open candidates (C-01, C-05, C-06, C-08..C-11) | Backlog | As capacity allows |

## 15. Risks

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| RK-01 | Three large sprints (51, 63, 56 pts; 4b was 49 before D-23..D-25) against ~35-point history | Slips | Cut lines; 4a/4b split; small PRs; move S4-13/S4-15 to Sprint 5 if needed (14.2) |
| RK-02 | Web rewrite to local-first (S4-01) regresses today's web app | Broken web for the owner | Behind a build flag until the IndexedDB repository passes the existing page tests; migration path from online mode |
| RK-03 | Six formats × two platforms drift apart | Inconsistent copies | Golden-file tests from one fixture set (TC-U-26); one format spec in 03 |
| RK-04 | Safari evicts IndexedDB of non-installed sites after 7 days unused | Guest data loss on iOS | Install/backup warnings, backup reminder, sign-in for sync offered (NFR-027) |
| RK-05 | `drive.file` files created on Android may not be visible to the web client (or the reverse) | Photos not shown across devices | S5-01 spike; same Cloud project for all clients; fallback: thumbnails + "open on the phone" |
| RK-06 | Google OAuth consent screen: unverified apps are limited to test users, production needs a privacy policy and an authorised domain (a `web.app` or `onrender.com` subdomain belongs to Google or Render, so the owner cannot verify it as his own; since 2026-09-23 the web app's origin **is** `https://doorprints.web.app`, [03](03-design.md) ADR-21) | Public sign-in blocked; a domain costs money | Verify in S5-01 whether publishing needs a verified domain for these scopes; `drive.file` and basic scopes avoid security assessment; if a domain is needed, that is a product-owner decision (`doorprints.in`, about ₹500/year), and moving the web app to it must wait until web import ships ([12](12-brand-and-naming.md) section D) |
| RK-07 | Paid cloud AI costs more than planned; ML Kit GenAI Prompt API (beta) changes or supports few Indian-market phones | Money spent; guests see no AI | Hard cap and budget alerts (5.13); on-device AI treated as a bonus, every feature works without AI; Test Lab runs on Indian-market devices ([10](10-sprint-log.md)) |
| RK-08 | AI custom export number check misses number words, Indic numerals or rounding ("about 25k") | Wrong figures shared with family | Prompt asks for digits; Indic digit normalisation; warnings instead of silent pass; eval cases |
| RK-09 | IDOR after the multi-user retrofit | Data leak | Central owner filter, IDOR matrix over every endpoint, ASVS review |
| RK-10 | Android reminders delayed by OEM battery savers | Missed viewing | Exact alarm when allowed, otherwise an early-starting `setWindow` (5.16), calendar option, battery-optimisation help, OEM tests |
| RK-11 | PWA reminders do not fire when the app is closed (no push by D-03) | Missed viewing on iPhone/laptop | Clear text in settings; `.ics` to the phone calendar |
| RK-12 | Google Identity Services script on our origin | Supply-chain / XSS surface; and, now that Firebase Hosting sends the headers of `web/firebase.json` (2026-09-23), its `Cross-Origin-Opener-Policy: same-origin` breaks the Sign in with Google popup when FedCM is not used (Google asks for `same-origin-allow-popups`) | CSP limited to `/gsi/`; alternative code flow kept as D-16; S5-01 decides the COOP value together with the CSP additions (SEC-048) and re-runs the header check ([07](07-secure-build-and-deploy.md) §6.3) |
| RK-13 | Dependency on Google (Play services absent, policy change, account suspension) | No sync for affected users | Local-first: every feature except sync, Drive and cloud AI still works; exports independent of Google |
| RK-14 | Repository rename to `doorprints` breaks hard-coded links, badges or CI references | Broken links | Renamed 2026-09-22 (`Sriram-Codes-SW/doorprints`); GitHub redirects; README updated; grep for `house-hunt` URLs, keep package names |
| RK-15 | Area wake-up (5.17) depends on Google Play services geofencing, OEM battery savers and a permission users are wary of; geofence alerts can take minutes | Missed or late area prompts; users refuse "Allow all the time" | Opt-in only, clear rationale, "within a few minutes" in the text, OEM battery help, TC-M-18 and TC-F-12 on an Indian-market phone; everything else works without it (D-25) |

## 16. Open decisions (smaller)

| ID | Decision | Options | Recommendation |
|---|---|---|---|
| D-04 | Accept the Sprint 4a/4b split | Split · one big Sprint 4 | Split |
| D-09 | Postgres row-level security as defence in depth | Yes · no | Sprint 6 |
| D-12 | Rating vs checklist share | 50/50 default, user-adjustable · rating as a criterion | 50/50 default |
| D-13 | Objective criteria from data (budget fit, BHK match) | Later · 4b | Later |
| D-14 | Translated default questions | Team translation + native review · English only | Translated |
| D-15 | Cloud AI quota and hard cap | Per invited user per day and a global daily cap (configurable) | e.g. 50 per user, global cap within the monthly budget; reviewed after 2 weeks of counters |
| D-16 | Web Google sign-in method | GIS script (needed anyway for Drive on the web) · server code flow without Google script (then no Drive on the web) | GIS, limited CSP |
| D-17 | Passphrase for backup ZIPs (device, Drive) | No · optional | Optional, backups only; readable copies stay plain |
| D-18 | Server thumbnails | Every photo · one cover per house | Every photo within the per-user cap |
| D-19 | Domain for OAuth publishing, if Google requires one (RK-06) | Free subdomain · buy a domain | Decide after S5-01 |
| D-20 | AI custom export timing | Sprint 5 below the cut line · Sprint 6 | Sprint 5 if the AI access tiers land early |

## 17. Documents to update when this is accepted

| Document | What to add |
|---|---|
| [01](01-requirements.md) | US-16..US-37; FR-042..FR-082 (amend FR-005, FR-011, FR-024 Connect page, FR-031); NFR-021..NFR-029; SEC-031..SEC-048 (SEC-025 superseded); PRV-012..PRV-021; PRV-007 adds Google Sign-In and Drive; section 2 scope (local-first, optional Google); 11.3 out of scope: "multi-tenant accounts" now in via Google, "scraping property portals", "push notifications" and "own passwords/email" stay out; AS-01, PER-3 change in Sprint 5 |
| [02](02-threat-model.md) | T-S8..T-S11, T-T8..T-T12, T-R3, T-I12..T-I19, T-D7, T-D8, T-E6, T-E7; RR-07..RR-10; F-01b closed in Sprint 5; section 3.1 equivalence |
| [03](03-design.md) | Local-first web architecture, IndexedDB schema, ERD, weighted formula, export format spec, API, sequences (5.2, 5.11), ADR-08 revisited (no full photos on the server), ADR-15 identity (Google only), ADR-16 Drive, ADR-17 no portal scraping, ADR-18 AI custom export (ADR-14 is taken by the KMP decision); R-05, R-06 closed; Sprint 4b: design of reminders, hunting areas and the permission model (5.16..5.18), ADR-01 note (done in 03 v0.8) |
| [04](04-data-flow-diagrams.md) | DF-33..DF-39; export files and Drive in the data classification |
| [05](05-ux-accessibility-i18n.md) | New screens, glossary, sign-in prompt copy rules, export typography |
| [06](06-test-plan.md) | TC-U-22..34, TC-I-22..32, TC-S-16..21, TC-M-12..16, TC-A-08..11, TC-P-05 |
| [07](07-secure-build-and-deploy.md) / [08](08-operations-runbook.md) | `APP_DATA_KEY(_NEXT)`, `APP_AUTH_MODE`, Google client IDs, cloud AI allowlist, quota and hard-cap settings, budget alerts; claim procedure; key rotation; deletion purge job; Google consent-screen setup |
| [10](10-sprint-log.md) | Sprints 4a, 4b, 5 from section 14; candidate moves |
| Sprint 4b additions (D-23..D-25) | Done in v0.6 for 01 (FR-083..FR-088, NFR-030, SEC-049, PRV-024..PRV-027, PRV-001) and 03 (ADR-01 note). Done in v0.7 for 02 v0.14 (T-I23, T-I24, T-E8, least privilege) and 06 v0.12 section 13 (TC-U-38..40, TC-M-18, TC-F-12, TC-S-22, TC-A-12). Still to copy when 4b starts: 04 (DF-40), 05 (rationale screen and permission copy in 4 languages, Design Director review), 10 (S4-17..S4-19) |
| README | Local-first positioning, optional Google sign-in (repository rename done 2026-09-22) |
