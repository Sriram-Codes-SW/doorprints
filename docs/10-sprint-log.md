# 10: Sprint log

| Field | Value |
|---|---|
| Document | Agile sprint log (goals, stories, sign-offs, CI results, retrospectives) |
| Version | 0.48 |
| Date | 2026-09-24 |
| Author | Claude (Cowork), Docs team |
| Status | Draft (Sprint 3.5 KMP foundation delivered and green on `19006bc`; Sprint 4a in progress, section 11; web host **Firebase Hosting at `https://doorprints.web.app`** since 2026-09-23, owner setup done, first deploy pending, §11.6; Sprint 4b scope set by the product owner with the 2026-09-22 additions and the 2026-09-23 import definition, section 12; **whole-app UX audit approved on both clients**, the go-ahead for the first deploy, §11.7; owner's security guard rule, release security gate, process improvements and **first-release Definition of Done** (hi/ta/te ship *under review*) §12.5; licence change to AGPL-3.0-only approved as the next item, §12.6; pre-deploy close-out, what is left, backlog tickets and rule candidates, §12.7; **owner issue P0 of 2026-09-24, India's boundaries on the map, merged (PRs #13 and #14) and live**, §12.8; **story S4b-BR-1, the app icon's footprints (option C), PR #15, merged (`76449fb`)**, §12.9; **owner request of 2026-09-24, the doubled lines and the Assam-Arunachal Pradesh state line, fixed on branch `fix/india-boundary-lines`, PR #16, merged (`4100f7a`)**, §12.10; **owner request of 2026-09-24, the Compose Multiplatform track (ADR-23), CMP-1 done in `be86f50`**, §13; **owner request of 2026-09-24, testing the APK and the live web UI, CMP-0 in `afe4064`**, §13.3) |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) | First version: working agreement, Sprint 1 (initial build, CI green except Security) and Sprint 2 (security fixes, key rotation, release signing, tests) with goals, stories, sign-offs, CI results, retrospectives and next-sprint candidates. Includes `ai-evals.yml` in S2-09/C-07, C-12 and a note that the first-push CI row is reconstructed from the `689927d` commit message. C-07 traces to TC-AI-10 (covers the TC-AI-01..08 cases) and AI-001, matching [06](06-test-plan.md). |
| 0.2 | 2026-09-22 | Claude (Cowork) | Sprint 2 outcome: all four workflows green on `f7da5ab` and `0e4e22a` (web `package-lock.json` committed from the CI artifact); S2 stories marked Done; C-12 Done, C-01 Part. Added Sprint 3 (section 5): the first real AI eval run (C-07) found the Gemini OpenAI-compatible embeddings incompatibility (index not set) and a false PASS in the scorecard; the AI team fixed both in code (native Gemini `batchEmbedContents` embeddings; scorecard fails on zero cases or harness errors), not yet confirmed by an eval run. Candidates moved to section 6 with a status column. New candidate C-13 (owner AI): the contact name reaches the LLM provider unredacted (02 F-30, AI-010 now Part). |
| 0.3 | 2026-09-22 | Claude (Cowork) | Lead decisions for Sprint 3: C-13 (F-30, contact name to the LLM provider) assigned to the AI team this sprint as S3-03, now done in code (F-30 Fixed in [02](02-threat-model.md) v0.6, waiting on CI); F-01 split into F-01a (Fixed) and F-01b (Open, C-04); `docker-compose.yml` moved to the Backend team's files (it passes the AI settings); new S3-04 (Docs). Section 1: new **Process** note: team managers upgraded to senior reviewers with a mandatory runtime pre-mortem, third-party wire-format verification and contract tests, because the Sprint 3 embeddings incompatibility (E-01) passed compile, unit tests and review. |
| 0.4 | 2026-09-22 | Claude (Cowork) | S3-03 records the final redaction rules (ai-design v0.10, including initials-style names) and the `[contact]` label contract for clients, with the reindex after the v0.10 deploy; S3-04 lists the coordinator's rework (06 v0.7, 01 v0.6, 02 v0.7, 08 v0.6, CHANGELOG, README). |
| 0.5 | 2026-09-22 | Claude (Cowork) | Sprint 3 outcome: commit `6a348cc` pushed, Backend and Security workflows green (Android and Web not triggered by their path filters). First successful real Gemini eval run on 2026-09-22 (Actions run 35720654442): 12/13 cases passed, every metric passes except `citationPrecision` 0.86 (threshold 0.90) because of one correct contrast citation, recorded as E-03 (AI team: `allowedCitations` in the golden set and an Ask prompt tweak). E-01 and E-02 confirmed fixed; S3-01 Done with the E-03 follow-up; lead decision: **F-30 closed as Fixed** (evidence: TC-AI-15 green in the Backend workflow on `6a348cc`; the eval run is a no-regression check only, S3-03 Done, C-13 Done, [02](02-threat-model.md) v0.8). New S3-05 (local labels instead of `[contact]` in AI citations, not started) and new section 7: Sprint 4 candidates C-14..C-21, not committed scope, awaiting the product owner. |
| 0.6 | 2026-09-22 | Claude (Cowork), Docs team | Product rename to **Doorprints** (tagline "Remember every house you've seen."), product owner request: new stories S3-07 (all code teams) and S3-08 (Docs), [03](03-design.md) ADR-13; section 1 and the Sprint 1 goal use the new name. Actions run 35720654442 ran on commit `6a348cc` (confirmed): section 5.3 *Code* row and the AI sign-off note no longer ask to confirm it. S3-06 now lists everything [06](06-test-plan.md) v0.8 changed (was only the TC-AI-10 gap row). Sprint 3 CI cell in section 2: Backend and Security green on `6a348cc`; the S3-07 rename (touches `android/`, `web/` and the workflows) waits on all four workflows on the merge commit. |
| 0.7 | 2026-09-22 | Claude (Cowork), Docs team | New section 8: product-owner decisions of 2026-09-22 (repository renamed to `Sriram-Codes-SW/doorprints` and made public with MIT `LICENSE`, `SECURITY.md` and a ruleset on `main`, required checks deliberately off; AI access policy with on-device AI for guests and cloud AI only for the owner and invited users on a paid, hard-capped key; bring-your-own-key rejected; Vertex AI added next to AI Studio), new Sprint 4 candidates **C-22..C-28** (CodeQL, PR flow with a `CI summary` check, Vertex AI provider, AI access tiers, Firebase Test Lab on Indian-market devices, Cloud Run staging, Speech-to-Text prototype) and the Google Cloud $300 trial operations notes (8.3). |
| 0.8 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes. Section 8.3: the spend cap budget (Preview; monthly, before credits) on the AI-only project is the provider-side stop, alerts only notify; new row: spend cap budgets do not cover Cloud SQL, so staging has its own budget alert and a tear-down date; credit coverage of the models confirmed on day 1. C-24: sync 04 (E6, DF-21, DF-32: Vertex endpoint and OAuth auth) and 02 T-I22 when the AI team's code lands. C-26: after the trial Test Lab runs within its no-cost Spark daily quota. |
| 0.9 | 2026-09-22 | Claude (Cowork), Docs team | **C-24** updated: the AI team's Vertex AI code and [ai/vertex-setup.md](ai/vertex-setup.md) landed in this change set (status: code landed, waiting for the first CI run and the owner's setup steps 8 and 10); the docs sync it asked for is done ([02](02-threat-model.md) T-I22, [03](03-design.md) §13, [04](04-data-flow-diagrams.md) E6, DF-21, DF-32, [06](06-test-plan.md), [07](07-secure-build-and-deploy.md) §4 and §7, [08](08-operations-runbook.md) §1.1, IR-9, §9). New acceptance item from the manager review: design and contract-test the provider response when the spend cap trips ([01](01-requirements.md) AI-017). PO-3: "being written" marker removed. Review fix: C-24 said the `ai-evals.yml` `provider` input defaults to `vertex`; it defaults to `aistudio` until the owner's step-10 run, and C-24's done-when item (2) now includes flipping it; C-24 also lists the `setupHint` and TC-AI-22. |
| 0.10 | 2026-09-22 | Claude (Cowork), Docs team | New **section 9, Sprint 3.5 "KMP foundation"** (commit `8f583af`): stories S3.5-01..S3.5-06 (`:shared` Kotlin Multiplatform module, Ktor `ApiClient` replacing OkHttp's `RetryInterceptor`, Room schema export and `RoomSchemaTest`, compile-only iOS job `shared-ios.yml`, Gradle dependency graph and SHA-pinned `google-github-actions/auth`, docs), CI results to be recorded, Phase 2 plan; sprint summary row. New **section 10**: product-owner decisions later on 2026-09-22 (PO-5 Hunt mode reminders, PO-6 hunting areas / area wake-up, PO-7 location permission model, all Sprint 4b; [11](11-feature-parity-and-export-spec.md) v0.6 S4-17..S4-19) and the **Vertex AI setup outcome** (PO-8: project `doorprints-ai`, chat in `asia-south1`, embeddings on `global`, GitHub variables set; trial credit ends 22 Dec 2026). C-24 status updated (steps 1-8 done, steps 9-10 open); C-05 (DTO mapping part done), C-10 (Android dependencies now reach Dependabot through the dependency graph job) updated; 8.3 credit row names the end date. Team table: Docs owns `docs/ai/**` for this sprint (AI team idle). |
| 0.11 | 2026-09-22 | Claude (Cowork), DevSecOps team | §9.2: corrected the `gradle-dependency-graph` follow-up (only the submit/upload phase is non-blocking; a Gradle resolution or configuration-filter failure still fails the job; an earlier wording said a generate or submit failure becomes a warning), added the no-report check and the exit criterion (`Submitted dependency-graph-reports/...` in the job log). |
| 0.12 | 2026-09-22 | Claude (Cowork), Docs team | §9.2: runs of `8f583af` and the follow-up commit `feb0294` recorded from the public Actions pages with a confidence column (Shared iOS compile ✅; Android ✅ to confirm; Security ✅ on `feb0294`, but green does not prove the dependency graph was submitted, the `Submitted ...` log notice does; Backend on `feb0294` unclear, owner to check first); sprint summary row updated. C-24 and §10.2: first `provider=vertex` eval run 35753477789 failed only on `citationPrecision` 0.78, answered by `feb0294` (inline-marker citation rule, golden set v0.5, thresholds not lowered); re-run and credit check open. §10.1: 4b threats and tests copied to 02 and 06; reminder scheduling corrected in 11 v0.7. |
| 0.13 | 2026-09-22 | Claude (Cowork), AI team | §9.2 and section 2: the `feb0294` Backend run (35755840287) **failed** (was "Unclear", Low confidence): 258/259 tests passed and `EvalScorerTest.goldenSetFileIsConsistent` threw `IllegalArgumentException` because `ask-01` in golden set v0.5 has `allowedCitations` but no `mustNotCite`, and AssertJ `doesNotContainAnyElementsOf` rejects an empty list. Fixed by the AI team in the next commit (test only: an empty or missing id list means no constraint and is skipped); golden set and thresholds unchanged. |
| 0.14 | 2026-09-22 | Claude (Cowork), Docs team | **Sprint 3.5 closed green.** §9.2: commit `19006bc` recorded — Backend ✅, Security ✅, Android ✅, Shared iOS compile ✅, with the path-filter caveat spelled out (only Backend and Security are triggered by that push); the "green Backend run still to record" and "Android ✅ to confirm" notes are resolved; §9.4 sign-off recorded. §10.2 and C-24: the `provider=vertex` re-run **35758157317** on `19006bc` passed **13/13 cases and every metric** (golden set v0.5, 198 s, two provider `503`s absorbed by the bounded retry), so E-03 is closed by a real run; the credit check, vertex-setup step 9 and the `ai-evals.yml` default flip stay open, and **both providers stay in the code** (product-owner decision PO-9, section 10.3). Section 2 sprint summary updated. New **section 11: Sprint 4a** (local-first web, exporters, import, PWA) with stories S4-00..S4-07 and the docs story S4-06. |
| 0.15 | 2026-09-22 | Claude (Cowork), Docs team | **Review fixes — E-03 and C-22 closed where a reader looks for them.** v0.14 closed E-03 in §10.2, C-24 and the CHANGELOG but left the defect register and the sign-off at the Sprint 3 state, so the same document said both. Now: §5.2 E-03 is **Fixed, closed** by run 35758157317 on `19006bc` (13/13 cases and every metric, golden set v0.5; fix shipped in `feb0294`+`19006bc`, no threshold lowered); the paragraph under §5.2 states the AI-012 bar as [01](01-requirements.md) does (a green run on the provider that would be enabled, on the commit being released) instead of "until E-03"; §5.5 AI and product-owner rows, S3-01, the §5.3 follow-up row, C-07, the §7 intro and the Sprint 3 summary row follow. §9.2: the `feb0294` Backend confidence cell no longer asks for a green run that the `19006bc` row below it already records. §8: **C-22 flipped from Sprint 4a candidate to landed** (`codeql.yml`, §11.1) with its three follow-ups (Android Kotlin not analysed, Semgrep SARIF still artifact-only, no run yet). |
| 0.16 | 2026-09-22 | Claude (Cowork), Docs team | **Review fixes — doc-versus-code mismatches that only a code read catches.** §11.3 item 1 said "the pure `ImportPlan` is already ported and tested"; a search of `web/src` for `ImportPlan`, `importDecision` or `newerInFile` returns nothing, so the item now separates what exists (the Kotlin `ImportPlan` + `ImportPlanTest`, the constants mirrored in `backup-export.ts`) from what does not (no TypeScript port, no spec, no web counterpart to TC-U-42) — the largest correctness-sensitive piece of the 4b story was recorded as built in the table 4b plans from. **S4-07** and [01](01-requirements.md) FR-048 no longer claim an unmetered-network constraint: `AutoBackupWorker` sets charging + battery-not-low only. Two new §11.3 rows: **7** the `data.json` cap disagreement (16 MiB in `:shared`, 64 MiB in schemas/README §7 and the web mirror, 8 MiB effective on the server) for Backend/Web to reconcile, and **8** whether the auto backup should require an unmetered network at all, as an open product decision rather than a shipped control. **S4-06** result cell updated to the versions the documents actually carry after this round (01 v0.15, 02 v0.17, 03 v0.10, 04 v0.9, 05 v0.5, 06 v0.15, 07 v0.15, this log v0.16) and 07 added to the story, which the same change set had touched. |
| 0.17 | 2026-09-22 | Claude (Cowork), Docs team | **The owner has switched GitHub Pages on** (Settings → Pages → Source = “GitHub Actions”), so the site will be public at **https://sriram-codes-sw.github.io/doorprints/** from the first Sprint 4a push to `main` (no pushed commit has the Pages jobs yet; the URL answers 404 until then). §11.1: new story row **S4-05a** (Web team: relative `start_url`/`scope`, manifest `id` removed so the identity falls back to `start_url`, worker URL and scope from `document.baseURI`, Cache Storage names scoped by deployment path with deletion limited to this app's caches on the shared github.io origin, the `build-pages` guard; done in the working tree, not yet exercised on the live URL); the postscript no longer says the deploy job waits for Pages to be switched on, and records the Web team's later per-build service-worker stamp and build-time `<meta>` CSP. §11.2 watch point 3 rewritten: the first push is the first real deployment, and a green `deploy-pages` plus an answering URL is DevSecOps' cue to remove `continue-on-error`. §11.3 row 2 (**F-31**): the header gap is no longer hypothetical, RR-11's "only while Pages is a free preview host" condition has lapsed, and the owner has to accept the remaining gap explicitly or move the real site before that push. S4-06 lists 02 v0.18, 04 v0.10, 07 v0.16 and this log v0.17. |
| 0.18 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes, with [06](06-test-plan.md) v0.16, [07](07-secure-build-and-deploy.md) v0.17, [02](02-threat-model.md) v0.19 and [04](04-data-flow-diagrams.md) v0.11. **S4-05a**: the manifest identity without `id` "defaults to `start_url`, which resolves against the manifest URL (`/doorprints/`)" (was "base-href-relative"); the cache sentence now says what each path deletes ("Remove all data": every `doorprints-shell-*` cache; `activate`: only stale ones of the same deployment path); the state cell names the tests — **TC-U-43** (automated, written), the live check **TC-M-19** and the manual `activate` half of **TC-S-19** (both not yet run) — instead of "06 §14", which had no such row. **S4-06** cell: 02 v0.19, 04 v0.11, 06 v0.16, 07 v0.17, this log v0.18. **§11.1**: after the first push, Pages republishes only on a push to `main` that touches `web/**` or `web.yml` (or a manual run), and `continue-on-error` comes off when TC-M-19 passes, not merely when the URL answers. **§11.2 watch point 3** points to TC-M-19 and adds one runtime symptom to watch: a map that does not render on Pages would mean the MapLibre worker (`/doorprints/maplibre/maplibre-gl-worker.mjs`, `worker-src 'self'`) did not load — `map-style.ts` builds its URL from `document.baseURI`, so it should, but nobody has seen it run there. **§11.3 row 2**: the same push qualifier, and `Referrer-Policy` (not sent, browser default equals the configured value). |
| 0.19 | 2026-09-22 | Claude (Cowork), Docs team | Fifth review round, with [01](01-requirements.md) v0.16, [02](02-threat-model.md) v0.20, [03](03-design.md) v0.11, [04](04-data-flow-diagrams.md) v0.12, [05](05-ux-accessibility-i18n.md) v0.6, [06](06-test-plan.md) v0.17, [07](07-secure-build-and-deploy.md) v0.18, [08](08-operations-runbook.md) v0.12 and [11](11-feature-parity-and-export-spec.md) v0.8. **S4-05a**: the manifest `"id"` is no longer "removed" — the build stamps the absolute base path (`/doorprints/` or `/`), tested by `sw-precache.spec.ts`; "deletes every `doorprints-shell-*` cache and nothing else" now says that the prefix also matches the legacy unscoped `doorprints-shell-v1`. **S4-00**: pinned on the server and on Android (`CanonicalSampleTest`), and the web golden against the sample by `BackupParityTest` (ticket S4-00/e applied in 06 TC-I-34). **S4-03**: the web PDF button says *Open print view*; phones use a new tab, desktops a hidden frame. **S4-07**: turning the backup off forgets the folder; persisted grants. **§11.3**: item 7 (three `data.json` caps) is fixed in the working tree — 16 MiB everywhere, pinned by `BackupParityTest`; item 2's referrer sentence is hedged for Safari; new item 9 (should `docker-compose.yml`'s CORS default include the GitHub Pages origin — lead decision) and item 10 (the parity test's path filters). New **§11.5** copies the Android team's handover items 1, 3 and 6 as asked. |
| 0.20 | 2026-09-22 | Claude (Cowork), Docs team | Sixth review round, with [06](06-test-plan.md) v0.18 and [07](07-secure-build-and-deploy.md) v0.19. **§11.3 item 10** (the web half of the format pin depended on `backend.yml` running) is struck through as **fixed in the working tree**: DevSecOps added the web golden, `backup-export.ts`, `Backup.kt`, `docker-compose.yml` and `docs/schemas/**` to `backend.yml` and `docs/schemas/**` to `android.yml` (not yet triggered in CI). **§11.2 item 5** and the **S4-05a** row: the `build-pages` guard is described as it now is — `id` must equal `/doorprints/` exactly, and an absent `id` fails too. **§9.2**: "Backend is filtered to `backend/**` plus the golden set" was wrong for `19006bc` (the filter then was `backend/**` only); annotated. **Android round 7** (`android/shared/README.md` 1.9): new §11.3 item 11 (web backups drop visits with no house) and §11.5 rows 7, 8, 9 and 5 (the last done by DevSecOps). |
| 0.21 | 2026-09-23 | Claude (Cowork), Docs team | **Owner decision (Sriram, 2026-09-23 07:30 IST): the web app is hosted on Cloudflare Pages, not GitHub Pages** — new **§11.6** (decision, reason, what each team changed, owner setup, what to check on the first deploy), with [02](02-threat-model.md) v0.22 (F-31 Fixed, RR-11 closed, RR-12), [03](03-design.md) v0.12 (ADR-21), [04](04-data-flow-diagrams.md) v0.14, [05](05-ux-accessibility-i18n.md) v0.8, [06](06-test-plan.md) v0.19, [07](07-secure-build-and-deploy.md) v0.20 and [08](08-operations-runbook.md) v0.13. **§11.1**: S4-05 no longer "F-31 open"; S4-05a notes that the `/doorprints/` project path it handled is no longer used (the base-href-relative code stays correct at `/`); the paragraph on GitHub Pages publishing is marked superseded. **§11.2** watch points 3 and 5 are rewritten for the Cloudflare Pages deploy. **§11.3** item 2 is decided (struck through) and item 9 now asks about the Cloudflare Pages origin. **§11.5**: the Android round 8/9 docs handovers (docs/05 §4 token mapping, §4.3 Android Indic line heights, §14.2 amber contact note, §14.3 shipped import labels) are recorded as landed. |
| 0.22 | 2026-09-23 | Claude (Cowork), Docs team | Review fixes to 0.21, §11.6. **Web** row: the Web team's 02:27 caching change (no long-lived `immutable` rules in `_headers`, because Cloudflare's SPA fallback answers a missing chunk with HTML and status 200 and rules match the request path), and a note that `web/README.md` still gives a guessed `pages.dev` address as a CORS example. New **DevSecOps (open)** row: pin `cloudflare/wrangler-action` by commit SHA (`v4` → `ebbaa158…`, `v4.1.1` → `4e888469…` on 2026-09-23), align the `web.yml` owner comment with the environment-secret recommendation, and optionally make the header check catch a long-lived rule on a missing chunk. **Owner** row: after the first deploy, write the real address into the README, [07](07-secure-build-and-deploy.md) §7 and `APP_CORS_ORIGINS`; the secrets are recommended as environment secrets of `cloudflare-pages`. **Evidence**: the secure-doc-viewer check now cites its `gh-pages` branch at `68e3a625` (no `<script>` element) instead of a time and browser detail that the owner did not supply. **Docs** row lists [02](02-threat-model.md) v0.23, [03](03-design.md) v0.13, [07](07-secure-build-and-deploy.md) v0.21, [08](08-operations-runbook.md) v0.14 and [09](09-osi-layer-analysis.md) v0.6. |
| 0.23 | 2026-09-23 | Claude (Cowork), Docs team | **Owner decisions of 2026-09-23 (later that morning).** (1) **§11.6 rewritten: the web app moves to Firebase Hosting at `https://doorprints.web.app`** (Spark plan, no billing account; project and site `doorprints`), chosen with the brand advisor after the owner rejected Cloudflare Pages because a `*.pages.dev` address reads as a development or test site; Cloudflare was never set up. The owner completed the Firebase and GitHub setup by 09:40 IST ([07](07-secure-build-and-deploy.md) §6.3 steps 1–9); the first deploy waits for the `web.yml` change to reach `main`. Each team's change and what to check on the first deploy are recorded; the Cloudflare-only open items of v0.22 (the `wrangler-action` SHA pin, the environment-secret wording, the long-lived-rule probe) are **closed as moot**. S4-05, S4-05a, §11.1's GitHub Pages paragraph, §11.2 watch point 3 and §11.3 items 2 and 9 follow. (2) **§11.5 records Android rounds 10–12** (`android/shared/README.md` 1.12–1.14, handover items 14–18: the undelete of houses deleted on the phone, *keep mine*, relinking after a synced delete, `JoinListTest`, *Bring them back*), which were missing from the SSDLC set (coordinator major); [05](05-ux-accessibility-i18n.md) v0.9 and [06](06-test-plan.md) v0.20 carry them. (3) **§10.2: Vertex credit check done** — ₹45 of trial credit was used for the Vertex eval runs (vertex-setup step 10); §11.3 item 6 updated; the matching update to [ai/vertex-setup.md](ai/vertex-setup.md) is handed to the AI team. (4) New **section 12, Sprint 4b items recorded on 2026-09-23**: story **S4b-00**, the approved import definition ([01](01-requirements.md) §6.9, FR-089..FR-097; [schemas](schemas/README.md) §0), the web import, the web parity items from Android handovers 11(a), 13 and 17(a) (coordinator Web minor), the naming follow-ups of [12](12-brand-and-naming.md) section G, the custom-domain rule, and the handovers to other teams. |
| 0.24 | 2026-09-23 | Claude (Cowork), Docs team | **Final Sprint 4a round** (coordinator's final cross-team review of 2026-09-23, Docs majors 1 and 2). New story **S4-UX** in §11.1 and new **§11.7, the whole-app UX audit's sign-off**: the go-ahead for the first deploy is **approved** for Android (gate round 10: 0 blockers, 0 majors, 4 minors) and Web (gate round 4: 3 minors), as a code-review sign-off with the device checks still to run; the carried minors are listed as Sprint 4b candidates with their status (Android A1–A5 open; Web W1 and W3 and the retry difference X1 fixed in the working tree in the final round, W2 open). Sprint summary row 4a follows. **§11.5** adds Android handovers **19–34** with where each landed (20–23, 25–32 and 34 applied in [05](05-ux-accessibility-i18n.md) v0.10, [06](06-test-plan.md) v0.21, [11](11-feature-parity-and-export-spec.md) v0.12 and [12](12-brand-and-naming.md) v0.2; 19 open with Backend; 24 with the web import; **33, the web's north-up map, implemented by Web in the final round**). **§12.2/§12.3**: handovers 13 and 17(a), the `houses_restore` rename and the Tamil spelling are done; item 22 (Android pick-a-spot add mode) added; §12.4 adds the device checks and handover 19. New **§12.5, owner decisions of 2026-09-23**: the security split and the guard rule (no Play Store release and no public server until the release security gate exists and passes; the first web deploy gets the existing CI, a ZAP baseline, the header check and a storage audit), and the six efficiency measures plus the security playbook as Sprint 4b stories S4b-EFF-1..6 and S4b-SEC-1..2. New **§12.6**: the licence change MIT → `AGPL-3.0-only` with a trademark notice, approved, is the next item; `LICENSE` unchanged in this round. |
| 0.25 | 2026-09-23 | Claude (Cowork), Docs team | **§12.5 brought up to the owner's recorded decisions** (Docs pre-review buddy, two majors and two minors). **Decision 1** was a shorter gate than the one approved at 17:53 IST (from the 17:31 proposal): the release security gate now has its three parts — the **automated gate** (DevSecOps: the existing Semgrep, CodeQL, gitleaks, Trivy, npm audit, Dependabot and header check, plus a MobSF static scan, a **ZAP API scan against the backend started in CI**, **authorisation tests**, `testssl.sh` for any self-hosted server, an **AGPL-compatible licence scan** and the **LLM prompt-injection set**), the **manual one-hour per-release list** (new Docs story **S4b-SEC-3**, `docs/13-release-security-checklist.md`: WSTG/MASTG-lite, the storage audit, MobSF dynamic) and a **deep self-run pentest** before the Play Store launch and before Sprint 5 sign-in; **once the gate exists, web deploys pass it too**; the first deploy's checks run before the address is announced, with rollback through Firebase release history on any finding. S4b-SEC-1 follows. New **Decision 3**: the owner's 19:55 IST answer on the seven process improvements (1, 2, 4, 5, 6, 7 approved; 3, smaller batches, on hold), with its constraint — new lint, Semgrep and i18n checks go **inside the existing workflows, no new workflow, CI runtime kept flat**, and draft-PR CI runs in the background and never blocks — added to S4b-EFF-1, S4b-SEC-1 and S4b-SEC-2. New **Decision 4**: the **first-release Definition of Done** (20:03 IST): every review gate approved, every workflow that runs on the merge commit green, the first-deploy security checks passed, the web icons redrawn, and **hi/ta/te shipped marked *under review*** (machine-drafted, native-speaker review pending). **§11.5 row 19** and the §12.4 Backend row: the device note under [schemas](schemas/README.md) §6 rule 6 is **applied by Docs** (schemas README v1.5). §12.2 row 13 notes the Telugu wording difference ([05](05-ux-accessibility-i18n.md) v0.11). |
| 0.26 | 2026-09-23 | Claude (Cowork), Docs team | Round 1 review of the Docs final Sprint 4a round, one major, checked against the lead's decision log (18:55 IST proposal, 19:45 IST launch) and the review rules in the workflow scripts, not against the playbooks. **§12.5 Decision 2**: **S4b-EFF-4** repeated the playbooks' review items instead of the approved review rules; it now states them: one complete pass in round 1; later rounds the delta plus the regressions it could cause, with no new finding on unchanged code unless it is a blocker; the blocker/major/minor severity rubric; out-of-scope findings as `BACKLOG:` minors that never block approval; a new class of problem flagged `NEW RULE:` and added to the playbook (the playbook items are kept). **S4b-EFF-4 and S4b-EFF-5** were shown as unstarted stories; measures 4 and 5 went into the workflow scripts at once and have been **in use since the final Sprint 4a round**, and the stories make them permanent; the Decision 2 lead-in says so. **S4b-EFF-6**'s goal said "double first-pass design and UX approvals within two sprints"; the approved target is **35 % to more than 70 % by the end of Sprint 4b**. With [05](05-ux-accessibility-i18n.md) v0.12 (§15.5 pointer, and rule 6's goal aligned) and [07](07-secure-build-and-deploy.md) v0.25 (A.5 pointer). |
| 0.27 | 2026-09-23 | Claude (Cowork), Docs team | **Pre-deploy close-out** (Sprint 4a; the delivery coordinator's final cross-team review, second pass). New **§12.7**. It lists what is still open for the first release's Definition of Done: CI on the merge commit, the checks after the deploy, and the device and phone checks. It also records the Web geolocation guard on the map and Plan, which was recommended before the first deploy. It adds the backlog tickets **S4b-BL-1..5**: `sync.service.ts` `lastError`, the house page's retry cards, the 32 px favicon, the Android map attribution hidden behind snackbars (licence-credit exposure) and the map's *Download now* after the page was left (found by Web under candidate rule (f)). The favicon and the map and Plan geolocation guard were done by Web in the same round (`web/README.md` close-out row) and are marked done, awaiting review. It lists the `NEW RULE:` candidates (a)–(g): (b)–(e) are folded into [05](05-ux-accessibility-i18n.md) §15.3 R9, R16 and R17 (v0.13), and (a), (f) and (g) wait for a reviewer to adopt them. **§11.7**: X1 is fixed on Ask, Plan and Connect only, and device check 21 known minor (e) now points to S4b-BL-4. **§12.5 Decision 1**: the storage audit's steps are in `web/README.md`, *Storage audit on the live site*, where the log used to say they were still to be written. **Decision 4 item 5**: the owner confirmed *under review* for hi/ta/te ("Under Review it is for now"). Status line names §12.7. |
| 0.28 | 2026-09-23 | Claude (Cowork), Docs team | Round 1 review of the pre-deploy close-out (two majors, two minors): the Web team's buddy pre-review row in `web/README.md` (*Pre-deploy close-out, web buddy pre-review*) landed after the v0.27 edits and is now applied. **§11.7 W2** is fixed in the working tree (`pages/plan/start-field.ts`, `startFieldToFix`, with `start-field.spec.ts`: *Plan route* focuses the first start field still to fix), awaiting review. **§12.4** Web row: only the phone checks TC-M-24 are left. **§12.7**: **S4b-BL-5** (`downloadHere()` returns when the page is gone) and the **(W2)** row are struck through as done, awaiting review, in the same way as S4b-BL-3; candidate rule **(f)** has both first cases done; the storage audit's expected results were corrected by Web (`hh.mapView` from the first map start) and "Remove all data" now also sweeps this tab's `hh.*` and `doorprints.*` sessionStorage keys. New `NEW RULE:` candidate **(h)**: a Docs hand-off names the last handover row it applied from each team README, and the reviewer treats a later row as not yet applied, not as a finding ([README](README.md) update rule 11). |
| 0.29 | 2026-09-23 | Claude (Cowork), Docs team | **Last Docs sync before the first deploy** (the delivery coordinator's final review of the close-out): the Web team's round 1, 2 and 3 review rows in `web/README.md` (*Pre-deploy close-out, round 1/2/3 review fixes*), which landed after v0.28, are applied; the last row applied is the round 3 row (README update rule 11). **§11.7 W2**: `nextTypedStart` drops a typed coordinate when its field turns invalid or is cleared, so a set start is never taken from a value the user removed. **§12.7**: new tickets **S4b-BL-6** (no visible `aria-invalid` style; one `styles.css` rule, an `--error-text` border at 3:1 or more, WCAG 1.4.11) and **S4b-BL-7** (no TestBed spec for `plan-page.spec.ts` or `house-detail-page.spec.ts`, with the two `startWithoutPosition()` guard cases), both owner Web; the register's lead-in says it is the one source of backlog ids. Candidate **(f)** gains its third finished case, the house form's `startWithoutPosition()` guards after `houses()` resolves and after it rejects. New candidates **(i)** (a value the app writes by itself is never read back as the user's choice) and **(j)** (every path that resolves a message's cause withdraws a message referenced from a field's `aria-describedby`), both added to [05](05-ux-accessibility-i18n.md) §15.3 R6 and R9 (v0.14), and **(k)** (a team README takes the next free backlog id from §12.7 and says it is new). |
| 0.30 | 2026-09-23 | Claude (Cowork), Docs team | **§12.5 Decision 5: CI runs on every branch** (owner, 2026-09-23: "We need to have the pipelines run on branches as well because we need to be sure that the code is right before merging into main"), with the lead's six-point specification that DevSecOps implements; not yet run in CI. §12.5 title and Decision 4 item 2 (`codeql.yml` now also runs on branch pushes) follow. With [07](07-secure-build-and-deploy.md) v0.26 and [02](02-threat-model.md) v0.25. |
| 0.31 | 2026-09-23 | Claude (Cowork), Docs team | Pre-review fixes to Decision 5 (with [07](07-secure-build-and-deploy.md) v0.27 and [02](02-threat-model.md) v0.26). **§12.5 Decision 5**: item 2 says the `main` ref guards hold only for an unmodified workflow (a push runs the pushed branch's workflow file) and that the `HH_*` repository secrets need the `release` environment restricted to `main`; item 3 says an in-progress run on `main` is never cancelled and only the newest waiting run starts; new item 7 reconciles the decision with Decision 3's constraint (more runs, same per-run runtime, no new workflow file, free minutes, CON-04); the Status line lists all five `main`-only jobs skipped on a branch. **§12.7**: new backlog ticket **S4b-BL-8** (CI, owner DevSecOps with the owner): `HH_*` into the `release` environment and `environment: release` on `release-signing-check` and `release`. **C-22** row: `codeql.yml` runs on push to any branch since Decision 5. |
| 0.32 | 2026-09-24 | Claude (Cowork), Docs team | **Owner issue P0 of 2026-09-24: India's boundaries on the map** (live site; Jammu and Kashmir, then the same issue near Arunachal Pradesh). New **§12.8**: the owner's decision ([03](03-design.md) ADR-22), what each team did on branch `fix/india-boundaries` (lead: the data, commit `a37ecbd`; Web, Android, DevSecOps: working tree), what is verified and what is not, and the release gate TC-M-25. **§12.7 register**: new backlog tickets **S4b-BL-9** (re-check the boundaries after each OpenFreeMap planet or style update) and **S4b-BL-10** (consider a Survey of India-derived outline). Hand-off rule 11: last rows applied are `android/shared/README.md` 1.36 (2026-09-24) and the `web/README.md` row of 2026-09-24 (*India's boundaries on every map*). |
| 0.33 | 2026-09-24 | Claude (Cowork), Docs team | Round 1 review of the Docs change for India's boundaries (two majors, one minor). **§12.8** re-synced with the code as of 2026-09-23 19:56 UTC (2026-09-24 01:26 IST): Android's round 1 fixes (`android/shared/README.md` 1.37: rule 2's adm0 guard, `in-boundary-world` maxzoom `Math.nextDown(5f)`, a 13th rules test, so **15** tests, not 12) and the web's missing adm0 guard, recorded as an open parity gap handed to Web; *Verified* corrected (the lead's zoom 5 render shows a tile line beside the outline in the middle box, not one outline) and a Docs decode of the lead's zoom 4, 5 and 6 tiles added (adm0 sides, doubled stretches, `boundary_3`); new *Open before release*. **§11.5** handover table gains Android items 35 to 38 (README 1.36 and 1.37 ask for them in the Sprint 4b handover list). **§12.7 register**: new backlog tickets **S4b-BL-11** (doubled lines inside the claim areas; lead, then Web and Android) and **S4b-BL-12** (Pakistani or Chinese admin lines inside the outline, if TC-M-25 finds any; Web and Android, with the lead). Hand-off rule 11: last rows applied are `android/shared/README.md` 1.37 (2026-09-24) and the `web/README.md` row of 2026-09-24 (*India's boundaries on every map*, unchanged since). |
| 0.34 | 2026-09-24 | Claude (Cowork), Docs team | **Comment and docs sync of the India's boundaries change** (coordinator's round; branch `fix/india-boundaries`, HEAD `3ad2b58` pushed, CI running; no behaviour change). Compared with the code at `3ad2b58` (`india-boundaries.ts`, `IndiaViewRules.kt`, `IndiaView.kt` and their tests). **§12.8**: the web has rule 2's adm0 clause and tile-zoom guard, so the v0.33 "open parity gap" is removed (Web row: 37 spec cases; Android row: 18 tests, the round 2 tile-zoom guard); parity paragraph: read from the renderer sources, both maplibre-gl 6.10.0 and maplibre-native android-v13.6.1 skip a minzoom 5 layer in a zoom 0-4 tile, so the guards are defence in depth on both (this corrects this round's brief, which called it "the fix on Android"; S4b-BL-13 reopened for Android's KDoc); *Open before release* and *Backlog* updated. **§11.5** items 36 (done by Web) and 38 ((b) corrected, (c) sign-off open). **§12.7 register**: S4b-BL-11's list corrected (Arunachal-Bhutan and Arunachal-Myanmar are not doubled); new **S4b-BL-13** (renderer wording in comments; Android's 1.39 wording still to correct), **S4b-BL-14** (`web/README.md` intro; done by Web this round, awaiting review), S4b-BL-10 with the measured offsets, **S4b-BL-15** (Assam-Arunachal Pradesh state line from zoom 5), **S4b-BL-16** (doubled lines in the middle sector and the Wakhan, style-side). Hand-off rule 11: last rows applied are `android/shared/README.md` 1.39 (2026-09-24) and the `web/README.md` row of 2026-09-24, *India's boundaries, comment and README sync* (its finding (4), maplibre-native's `geometry_tile.cpp:317` skip, checked in the source and adopted). |
| 0.35 | 2026-09-24 | Claude (Code), lead | New **§12.9, story S4b-BR-1: the app icon's footprints, option C** ([14](14-lead-backlog-and-handoff.md) N3; PR #15, branch `fix/brand-footprints`): the owner's choices (left/right/left, the same prints in the favicon), what changed on each platform, the checks and the review sign-off. |
| 0.36 | 2026-09-24 | Claude (Code), Docs team | New **§12.10, owner request of 2026-09-24: the doubled lines and the Assam-Arunachal Pradesh state line** (branch `fix/india-boundary-lines` (PR #16; CI green on `5af2f4d`, HEAD `9e0036e` running; not deployed)): the owner's summary, what was done (rule 2 leaves India's line with China to the outline; 7 shared stretches drawn by the tiles from zoom 5, connectors, no spurs; the `state` kind and layer on both apps), the design review that led to the India-China rule, how it was verified (including TC-M-25 step (3) with Google Maps' India region) and what is still unverified. **§12.7**: S4b-BL-11, S4b-BL-15 and S4b-BL-16 struck through as done on that branch; S4b-BL-9 also re-runs `find_shared_stretches.py`. **§12.8**: the known limits (with the owner's accuracy summary), *Open before release* and *Backlog* point to §12.10. Header status updated. |
| 0.37 | 2026-09-24 | Claude (Code), Docs team | **§12.10** after the round 2 reviews of PR #16: the Singalila spur fix (a cut within 1e-4 degrees of a claim line's end counts as the end; data sha256 `25984afa…a024`, 415 608 bytes, `claim` 5 pieces), the known minors sized (Sikkim tri-junction loops about 13 x 3 km and 2 km; the tile line's overrun at Jomotsangkha and Longwa; `INDIA_CHINA_LINE` also hiding the Tumen China-North Korea line), and the round 2 review results (code, design: changes requested, major fixed; docs: approved, minor fixed). **§12.7**: new backlog ticket **S4b-BL-17** (cleaner hand-overs at the Sikkim tri-junctions, Jomotsangkha and Longwa; lead). |
| 0.38 | 2026-09-24 | Claude (Code), Docs team | New **§13, the Compose Multiplatform track** (owner request of 2026-09-24: "The Compose needs to be changed to Kotlin Compose to allow easy iOS app creation"; [03](03-design.md) ADR-23): tickets **CMP-1..CMP-9** for phases P1-P8 and the maplibre-compose spike; **CMP-1 done** in commit `be86f50` (new `:ui` module, theme and pure UI code moved, no visual change; iOS compile pending on CI). §2 has a row for the track; the status line records PR #16 as merged (`4100f7a`). |
| 0.39 | 2026-09-24 | Claude (Code), Docs team | **Owner request of 2026-09-24: "Is there any way you can test the Android APK?"** (the owner chose all three options: an emulator CI job, screenshot tests and Firebase Test Lab) **and "Test the Web UI in detail as well after every main merge"** (a standing rule). New ticket **CMP-0** in §13.1, the test harness the Compose Multiplatform phases rely on, and new **§13.3** (what was done in commit `afe4064`, how it was verified, the first live UI run, what is not verified, the owner rule, and why a new workflow was accepted against §12.5 Decision 3's constraint). PR #17 (CMP-1) merged at `75f049d`. |
| 0.40 | 2026-09-24 | Claude (Code), Docs team | **§13.3, round 2 of the test-harness pull request.** The first emulator run (CI run 35943533129, `afe4064`) found a **real crash**: opening the app from a notification while it was not running threw "Navigation graph has not been set"; fixed in `Root.kt`. Firebase Test Lab gets a Workload Identity provider of its own and the secret `FTL_WIF_PROVIDER` (the Hosting provider is not widened), and runs only from `main`. The test changes of round 2 are listed. New backlog tickets **CMP-0-BL-1..5** (the Export screen's tall format cards; emulator runs in hi/ta/te and dark; a tablet size; a Map shot on the emulator; a workflow and a Dependabot entry for `tools/live-ui`). |
| 0.41 | 2026-09-24 | Claude (Code), Docs team | §13.3 after the round 3 review of PR #18: the second emulator run (`ef0a5dd`: the cold-start fix worked; the add-a-house test tapped the off-screen second Save, fixed in `bc57361`, re-run pending); screenshot tests restore the time zone; new backlog ticket **CMP-0-BL-6** (cold starts from the open-house and open-screen notifications); TC-U-60 renumbered TC-U-56; the Docs line names [14] v0.7. |
| 0.42 | 2026-09-24 | Claude (Code), Docs team | §13.3, PR #18 round 5: the smoke tests **passed on the emulator (CI, commit `bc57361`)**; Firebase Test Lab stays off by default at zero cost (option (a); a results bucket needs a billing account), options (b) and (c) for the owner; CodeQL's "Incomplete string escaping" in `tools/live-ui` fixed; the Docs line names the current versions; CMP-0's status cell updated. |
| 0.43 | 2026-09-24 | Claude (Code), Docs team | §13.3, PR #18 round 6: a second real bug found by the pull-request emulator run on `bc57361` (the Map's camera moved off the main thread after `currentLocation()`; fixed in `MapScreen.kt` with `withContext(Dispatchers.Main.immediate)`); the result now reads "passed on the emulator; one of two runs on `bc57361` found a threading bug, fixed in the next commit; the re-run is pending". |
| 0.44 | 2026-09-24 | Claude (Code), Docs team | §13.3, PR #18 round 7: the emulator results use the agreed wording (the threading bug fixed in `6376706`, re-run pending); the fix covers every caller of `currentLocation()`; new backlog ticket **CMP-0-BL-7** (`--results-bucket` conditional if the owner picks Test Lab option (c)); the Docs line names the current versions. Then both emulator runs on `6376706` passed (push and pull request; `ee30b92`). |
| 0.45 | 2026-09-24 | Claude (Code), engineer | Legacy House Hunt names renamed (owner request of 2026-09-24; [03](03-design.md) ADR-24). New **§14**: the rename, what carries stored names over, what is kept and why, and the checks run. Paths in earlier sections follow the moved files; history is left as written. |
| 0.46 | 2026-09-24 | Claude (Code), Docs team | Reviews of PR #19. §13.1: CMP-0 **done** (PR #18 merged, `6da0e56`), CMP-2 **done in code** in PR #19 (`80b198b`, review fixes `927d54b`). New **§13.4**: CMP-2 as built and its five differences from the plan. §13.2 notes that the package names are now `app.doorprints…`. §14: PR #19 on `claude/doorprints-dev-continue-fzcge2` with its commits, *Disconnect* removing a leftover `house-hunt.api-config`, and 235 `:app` unit test runs in the checks. §12.7: new backlog **S4b-BL-18** (formatting follows `locales[0]`), **S4b-BL-19** (`MainActivity` not exported), **S4b-BL-20** (clients detect a reset server) and **S4b-BL-21** (device-only checks, TC-M-27). §9.3 item 1 rewrapped. |
| 0.47 | 2026-09-24 | Claude (Code), engineer | CMP-3. §13.1: CMP-3 **done in code** (branch `claude/doorprints-dev-continue-fzcge2`, PR #20, open; the owner merges). New **§13.5**: CMP-3 as built and where it differs from the plan. §12.7: **S4b-BL-18 done** (the dates and `uiLanguage()` follow the resolved language); new **S4b-BL-22** (Export's default language follows `locales[0]`); new **CMP-0-BL-8** (§13.3 backlog). |
| 0.48 | 2026-09-24 | Claude (Code), engineer | CMP-4 P4a. §13.1: CMP-4 **P4a done in code** (branch `claude/doorprints-dev-continue-fzcge2`, after PR #20 was merged as `fccf8a1`), P4b and P4c planned. New **§13.6**: the Room database in `:shared` commonMain (Room KMP), how the identity hash and the file name were kept, the migration test, what stays in `:app` and why. §12.7: new **S4b-BL-23** (the `Repository`'s Android-only Room calls, for P4c) and **S4b-BL-24** (Room never opened on iOS). CMP-2 and CMP-3 rows marked **Done** (PRs #19 and #20 merged). Code review: the iOS database goes in Application Support; new S4b-BL-25 (a committed v1 schema). |

Related: [Requirements](01-requirements.md) · [Threat model](02-threat-model.md) · [Test plan](06-test-plan.md) · [Build and deploy](07-secure-build-and-deploy.md) · [Runbook](08-operations-runbook.md) · [CHANGELOG](../CHANGELOG.md)

---

## 1. How we work

Doorprints (called House Hunt until the Sprint 3 rename, S3-07) is built by small AI engineering teams, each with a senior reviewer (a team manager until Sprint 2), for one product owner (the user).
Sprints are short (about one working session each). The log records what each sprint set out to do, what was
delivered, who signed it off and what CI said. It is the Agile record; the SSDLC documents 01 to 09 hold the detail.

| Role | Responsibility |
|---|---|
| Product owner (user) | Sets the goal, accepts the sprint result, decides on risks that stay open |
| Team manager / senior reviewer (one per team) | Assigns the stories, reviews the team's work (logic, scope, file ownership), gives feedback, signs off the team's delivery. Since Sprint 3 a senior reviewer with the extra review duties in the *Process* note below |
| Team engineer | Builds the story inside the team's files, re-reads every changed file, reports what could not be verified |
| CI (GitHub Actions) | The only build and test environment (the engineering sandbox cannot reach Maven Central, Google Maven, Gradle or npm) |

**Teams and file ownership**

| Team | Owns |
|---|---|
| Backend | `backend/**` (except `backend/src/main/java/app/doorprints/server/ai/**` and its tests), `docker-compose.yml` (since Sprint 3; it passes the `AI_*` settings, see [07](07-secure-build-and-deploy.md) §7) |
| AI | Spring AI code and tests, `docs/ai/**` |
| Android | `android/**` |
| Web | `web/**` |
| DevOps / Security | `.github/**`, `.gitleaks*`; Docker files other than `backend/**` as agreed (the dev `docker-compose.yml` moved to Backend in Sprint 3) |
| Design | `docs/05-ux-accessibility-i18n.md`, string resources with Android and Web |
| Docs | `docs/**` except `docs/ai/**`, `README.md`, `CHANGELOG.md`, this log. Sprint 3.5 follow-up (2026-09-22): also `docs/ai/**` while the AI team is idle |

**Process (since Sprint 3)**

Team managers are upgraded to **senior reviewers**. Besides the logic, scope and file-ownership review, every
review of a change now includes:

1. **Runtime pre-mortem (mandatory):** before sign-off the reviewer writes down how the change could fail at run
   time even though it compiles and its unit tests pass (wrong endpoint or payload, missing or renamed field,
   provider limits, timeouts, configuration defaults, startup order), and checks each item against the code.
2. **Third-party wire-format verification:** for any call to an external API or library that talks to one, the
   request and response format is checked against the provider's own documentation or upstream source (not
   against a compatibility layer's promise), including field names, required fields, headers and error shapes.
3. **Contract tests:** each such integration gets a test that runs the real client against a recorded or
   documented provider response (for example Spring's `MockRestServiceServer` or a local JDK `HttpServer`), so a
   mismatch fails in CI and not first in a manual run.

Reason: in Sprint 3 the first real AI eval run found that Gemini's OpenAI-compatible embeddings endpoint does not
return what the app's OpenAI embeddings client expects (E-01, section 5.2). The code compiled, all unit tests
passed and the reviews were green; the incompatibility only showed at run time against the real provider. The
AI team's first contract tests are `GeminiEmbeddingContractTest` and `GeminiOpenAiChatContractTest` (TC-AI-16 in
[06](06-test-plan.md) §8); the chat test also checks the Gemini OpenAI-compatible chat path against the openai-java
SDK version that Spring AI 2.0.1 uses.

**Definition of done** (every story)

1. Code and docs in the same change; the document version is bumped and a dated change-log row added (docs/README).
2. New or changed tests are listed in [06](06-test-plan.md) and the RTM in [01 §12](01-requirements.md#12-requirements-traceability-matrix).
3. Security-relevant changes update the threat model ([02](02-threat-model.md)).
4. The team's senior reviewer has reviewed and signed off, including the runtime pre-mortem, wire-format check and contract tests from the *Process* note when the change calls a third-party API.
5. All four CI workflows (`Backend`, `Web`, `Android`, `Security`) are green on the merge commit.
6. User-visible changes are in [CHANGELOG.md](../CHANGELOG.md) under Unreleased.

## 2. Sprint summary

| Sprint | Dates | Goal | Result | CI on the last commit |
|---|---|---|---|---|
| 1 | 2026-09-22 | Build the whole app (API, Android, web, AI, docs, CI) and get CI running | Delivered as 0.1.0 (`4b034d3`, `689927d`). Goal met except the Security gate. | Backend ✅ · Android ✅ · Web ✅ · Security ❌ (Trivy: Tomcat CVEs, DS-0002) |
| 2 | 2026-09-22 | All four workflows green; close the High/Critical findings; safer key and release handling; more tests | Goal met (Unreleased; `f7da5ab`, `0e4e22a`) | Backend ✅ · Android ✅ · Web ✅ · Security ✅ |
| 3 | 2026-09-22 | First real AI eval run and its fixes; contact redaction (C-13); remaining Sprint 2 candidates | Mostly met (`6a348cc`): embeddings and scorecard fixes confirmed by the first real Gemini eval run (12/13 cases passed, all metrics but `citationPrecision` pass, E-03 with the AI team — closed in Sprint 3.5 by run 35758157317, §10.2); F-30 closed as Fixed; product renamed to Doorprints (S3-07, waiting on CI). Closing | Backend ✅ · Security ✅ on `6a348cc` (Android and Web not triggered: no `android/` or `web/` changes). S3-07 rename: all four workflows pending on the merge commit |
| 3.5 | 2026-09-22 | KMP foundation: make the Android code Kotlin Multiplatform-ready without an iOS app (section 9) | Delivered in `8f583af` (`:shared` module, Ktor client, Room schema guard, compile-only iOS CI); closed green on `19006bc` | **On `19006bc`: Backend ✅ · Security ✅ · Android ✅ · Shared iOS compile ✅** (Web not triggered; Android and Shared iOS compile are path-filtered too, see the caveat in 9.2). Earlier on the way there: Security ❌ on `8f583af` (`gradle-dependency-graph` submission), Backend ❌ on `feb0294` (`EvalScorerTest` empty-list assertion, test-only fix). The repository's **Dependency graph setting is now enabled** by the owner; the exit criterion for the `gradle-dependency-graph` job (the `Submitted dependency-graph-reports/...` notice in its log, after which the job goes back to fail-closed) is still to be read from a Security run made after that (9.2) |
| 4a | 2026-09-22 → | Local-first web (IndexedDB), the six deterministic exporters, JSON backup import, PWA (section 11) | In progress. The whole-app UX audit, the go-ahead for the first deploy, is **approved** on both clients (Android gate round 10, Web gate round 4, 2026-09-23; §11.7) | – (first 4a commit not pushed yet) |
| CMP | 2026-09-24 → | Compose Multiplatform track: move the Android UI to JetBrains Compose Multiplatform in a `:ui` module so an iOS app can reuse it (section 13, ADR-23) | In progress. CMP-1 (phase 1) done in `be86f50`: `:ui` module, theme and pure UI code, no visual change | – (iOS compile of `:ui` pending on `shared-ios.yml`) |

## 3. Sprint 1: initial build

**Goal:** a working, zero-cost House Hunt (now Doorprints): Spring Boot + PostGIS API, offline-first Android app with Hunt mode,
Angular web app, optional AI, SSDLC documents and a CI pipeline, all pushed to GitHub.

### 3.1 Stories

| ID | Story | Team | Status |
|---|---|---|---|
| S1-01 | As the user I can save, edit and compare houses with checklist, rating, price, photos and visits, through a REST API on PostGIS | Backend | Done |
| S1-02 | As the user I can hunt offline on Android: map, list, compare, Hunt mode alerts, stay detection, background sync | Android | Done |
| S1-03 | As the user I can review and edit my hunt in a browser, in four languages | Web, Design | Done |
| S1-04 | As the user I can optionally ask questions, import listing text and plan visits with AI (off by default) | AI | Done |
| S1-05 | As the owner I have an SSDLC document set (requirements, threat model, design, DFDs, UX, tests, build/deploy, runbook, OSI analysis) | Docs, Design, AI | Done |
| S1-06 | As the owner every push is built, tested and scanned (Backend, Web, Android, Security) and Dependabot watches dependencies | DevOps / Security | Done |
| S1-07 | Wave 2 hardening from the first threat model: 21 of 26 findings fixed | All | Done |
| S1-08 | Fix the first CI run: test compile error, random test keys, compileSdk 37, maplibre-gl 6.10 (F-27), Trivy via SBOM, gitleaks ignore of reviewed old test keys, Dependabot tuning | Backend, Android, Web, DevOps | Done (`689927d`) |

### 3.2 CI results

| Run | Backend | Web | Android | Security |
|---|---|---|---|---|
| First push (`4b034d3`)¹ | ❌ test compile error | ✅ build; `npm audit` found F-27 | ❌ compileSdk | ❌ Trivy fs hit Maven Central `429`; gitleaks flagged test keys |
| `689927d` | ✅ all tests | ✅ `ng build` | ✅ `assembleDebug` + unit tests | ❌ Trivy only: tomcat-embed-core 11.0.24 CRITICAL CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 (fixed in 11.0.25); DS-0002 HIGH "Specify at least 1 USER command" in `backend/db/Dockerfile` |

¹ Reconstructed from the `689927d` commit message ("Fix first CI run: test compile error, ... compileSdk 37, ..."); the CI logs of the first push were not reviewed.

### 3.3 Sign-off

| Team | Sign-off | Note |
|---|---|---|
| Backend, Android, Web, AI, Design, Docs managers | Delivered | Work reviewed by the team managers before it was handed to the product owner |
| DevOps / Security manager | Delivered with an exception | Security workflow red on two Trivy findings, carried into Sprint 2 as S2-01 and S2-02 |
| Product owner | Not recorded | 0.1.0 is the state at `689927d` (not tagged, no signed release); the red Security gate became the Sprint 2 goal |

### 3.4 Retrospective

| What went well | What to improve |
|---|---|
| Manager reviews caught logic and scope problems before hand-off, so fixes arrived as one coherent change per team | Nothing could be compiled locally (no Maven Central, Google Maven, Gradle or npm access), so the first push found a compile error and a compileSdk mismatch that a local build would have caught |
| The security gates worked on day one: `npm audit` found F-27 and Trivy found F-28 before any release | Trivy `fs` on `pom.xml` depended on Maven Central and was rate-limited; scans should not depend on flaky remote resolution (fixed with the SBOM) |
| Clear file ownership let teams work in parallel without conflicts | Test code used key-like literals, which secret scanning rightly flagged; generate test secrets at runtime |
| Docs and code were written together, with traceability from requirement to test | Dependabot opened too many PRs at once until it was tuned |

## 4. Sprint 2: green CI, key rotation, release signing, tests

**Goal:** all four workflows green on `main`; fix the Critical Tomcat CVEs and DS-0002; move F-01 and F-11 forward
(32-character keys, dual-key rotation, signed release APK); add web and more Android unit tests; start the AI eval
harness; bring the docs up to date and start this log and the CHANGELOG.

### 4.1 Stories

| ID | Story | Team | Acceptance | Status |
|---|---|---|---|---|
| S2-01 | As the owner the API runs on a Tomcat without known Critical CVEs | Backend | `tomcat.version` 11.0.25 override; `trivy sbom` clean for Tomcat (F-28 Fixed) | Done |
| S2-02 | As the owner no image in the repo runs as root | Backend, DevOps / Security | `backend/db/Dockerfile` `USER postgres`; `trivy config` blocking on HIGH/CRITICAL (F-29 Fixed, TC-S-14) | Done |
| S2-03 | As the owner a weak API key cannot be deployed | Backend | Startup fails below 32 chars, message names the variable only (F-01a, SEC-002, TC-U-18) | Done |
| S2-04 | As the owner I can rotate the API key without breaking my phone's sync | Backend, Docs | `APP_API_KEY_NEXT` accepted alongside the current key (SEC-017, TC-U-18, TC-I-21); runbook 08 §5.1 | Done |
| S2-05 | As the user I can install a signed APK and check its signer | Android, DevOps / Security | Signing from `HH_*` secrets, `release` job verifies with `apksigner`, never on PRs (F-11 Part, TC-S-15) | Done (CI green; the `release` job needs the four `HH_*` secrets) |
| S2-06 | As a developer web regressions are caught before merge | Web | Vitest + jsdom specs for score, config, interceptor, i18n; `npm run test:ci` in `web.yml` (TC-U-19..21) | Done |
| S2-07 | As a developer core Android rules are unit-tested | Android | `SyncRules`, `StreetAlerts` extracted and tested; `ChecklistScoreTest`; more `ServerUrlTest` cases (TC-U-05..07, TC-U-15) | Done |
| S2-08 | As the user Hunt mode and the map do not crash on permission loss or lifecycle changes | Android | Guarded `startForeground`, geocoder timeout, paired `MapView` lifecycle (03 R-01) | Done (CI green); field test TC-F-07 still open (C-11) |
| S2-09 | As the owner AI answer quality is measured before AI is turned on by default | AI | Golden set v0.2 with thresholds; `EvalScorerTest` runs in every build, `GoldenSetEvalTest` runs against a real model only with `AI_API_KEY` (TC-AI-09/10, see [ai/](ai/)); manual workflow `.github/workflows/ai-evals.yml` runs it and publishes the scorecard (job summary, artifact `ai-eval-report`) | Done (CI green); the first model run (C-07, Sprint 3) found two defects, see section 5 |
| S2-10 | As the owner the docs match the code | Docs | 01, 02, 03, 06, 07, 08, 09 updated (Sprint 1 fixes: maplibre-gl 6.10 worker and CSP, F-27, SBOM, gitleaks ignore, Dependabot, compileSdk 37; Sprint 2 stories); CHANGELOG and this log created and linked | Done |

### 4.2 CI results

| Run | Backend | Web | Android | Security |
|---|---|---|---|---|
| `f7da5ab` (Sprint 2 merge) | ✅ | ✅ | ✅ | ✅ (Trivy clean for Tomcat 11.0.25, F-28; DS-0002 gone, F-29) |
| `0e4e22a` (web `package-lock.json` committed from the CI `web-package-lock` artifact) | ✅ | ✅ | ✅ | ✅ |

Whether the Android `release` job ran or was skipped depends on the `HH_*` secrets; this log does not record it.

### 4.3 Sign-off

| Team | Manager review | Sign-off |
|---|---|---|
| Backend | Pending | Pending |
| Android | Pending | Pending |
| Web | Pending | Pending |
| AI | Pending | Pending |
| DevOps / Security | Pending | Pending |
| Docs | Pending | Pending |
| Product owner | – | Pending (acceptance condition met: all four workflows green) |

Each manager records the review result (accepted / changes requested) and the date. The product owner accepts the
sprint when all four workflows are green.

### 4.4 Retrospective (draft, to confirm at sprint review)

| What went well | What to improve |
|---|---|
| Manager reviews again kept each team's delivery logical and inside its own files; teams worked in parallel on backend, Android, web, AI, CI and docs without conflicts | Still no local builds: every compile and test result comes from CI, so a mistake costs a full round trip. Mitigation: small changes, verify uncertain APIs and versions by reading upstream source, re-read every changed file |
| Security findings were fixed at the source (Tomcat override with a removal note, `USER postgres`) instead of being suppressed | Version overrides (Tomcat) need a reminder to remove them; the runbook's weekly task now covers it |
| Tests were added with the changes (key rules, rotation, sync and street rules, web core) | R8 is still off and there are no instrumented Android tests, so the release build is not exercised on a device |
| The key rotation design (dual key) turned an outage into a routine task | Sign-offs and CI results must be recorded here as soon as they happen |

## 5. Sprint 3: first AI eval run, remaining candidates

**Goal:** run the golden-set eval against a real model (C-07) and fix what it finds, keep contact names away from the
LLM provider (C-13, assigned to the AI team by the lead), then continue with the candidates in section 6.

### 5.1 Stories

| ID | Story | Team | Status |
|---|---|---|---|
| S3-01 | As the owner the first real AI eval run (`ai-evals.yml`, C-07) is recorded and its defects are fixed | AI | Done: E-01 and E-02 fixed and confirmed by the first successful real Gemini eval run (2026-09-22, Actions run 35720654442, section 5.3); follow-up E-03 (`citationPrecision` 0.86 < 0.90) **closed in Sprint 3.5** by run 35758157317 on `19006bc` (§10.2) |
| S3-02 | As the owner the docs record the Sprint 2 outcome and Sprint 3 start, and describe the embedding and scorecard fixes | Docs | Done (01 v0.4, 02 v0.5, 03 v0.4, 04 v0.3, 06 v0.5, 07 v0.5, 08 v0.4, this log v0.2, CHANGELOG); the DF-32 re-check found F-30 (C-13) |
| S3-03 | As a contact person my name and phone never reach the LLM provider (C-13, [02](02-threat-model.md) F-30, AI-010), assigned to the AI team this sprint by the lead | AI | Done. Closed by lead decision: redaction tests TC-AI-15 pass in the Backend workflow on `6a348cc`; the real Gemini eval run 35720654442 confirms the AI paths work with the redactor in place (its fixtures hold no contact data, so it is a no-regression check, not a redaction test). `ContactRedactor` on the embedding text and metadata, the Ask context and citations (also chunks indexed before the fix) and the agent/MCP tool results (`HouseDetails.contactName` removed); tests TC-AI-15; name parts are removed from every free-text field, place fields match the whole name in order or reversed and initials-style names ("C/o K Ramesh"), the saved phone only at 8+ digits (ai-design v0.10 §9.1); `Citation.label` / `PlannedStop.label` may contain `[contact]` / `[phone]` (apps show the local label by `houseId`); run `POST /api/ai/reindex` once after deploying the final (v0.10) code (08 §1.1). F-30 **Fixed** in 02 v0.6, status cell aligned in v0.7, closed in v0.8 |
| S3-04 | As the owner the docs record the lead decisions of Sprint 3 | Docs | Done: F-01 split into F-01a (32-char minimum and `APP_API_KEY_NEXT` rotation, Fixed) and F-01b (per-device keys, Open, C-04), totals 26 Fixed / 3 Part / 2 Open of 31 (02 v0.6); F-30 Fixed (01 v0.5, 02, 03 v0.5, 04 v0.4, 06 v0.6, 08 v0.5); `docker-compose.yml` owned by Backend, AI settings in the 07 v0.6 env table; 02 v0.5 change-log row corrected; *Process* note in section 1; 09 v0.3; CHANGELOG. Coordinator rework: 06 v0.7 (TC-AI-12: 5-minute window), 01 v0.6 (PRV-009 reason for Part), 02 v0.7 (F-30 cell per ai-design v0.10), 08 v0.6 (reindex after the v0.10 deploy), CHANGELOG *Changed* corrected, README index F-01a/F-01b..F-30 |
| S3-05 | As the user I see my own house label, not `[contact]` / `[phone]`, in AI citations and planned stops (apps look up the local label by `houseId`, client contract in [02](02-threat-model.md) F-30 and ai-design §13) | Android, Web | Open: not started in Sprint 3; carried to Sprint 4 as candidate C-19 (section 7) |
| S3-06 | As the owner the docs record the Sprint 3 outcome, close F-30 and list the Sprint 4 candidates | Docs | Done: this log v0.5 (sections 2, 5, 6, 7), 02 v0.8 (F-30 closed), 01 v0.7 (AI-010, AI-012, PRV-009), 06 v0.8 (TC-AI-10 gap row with the first real scorecard; section 1 AI evals row: golden set v0.3 and the first real run; TC-AI-09 traces golden set v0.3 `allowedCitations`; new TC-AI-17 for the Ask prompt citation and contrast rules), docs index v0.7, CHANGELOG |
| S3-07 | As the user the app's name says what it is: **Doorprints**, "Remember every house you've seen.", instead of "House Hunt", which sounded like a property-listings site (product owner request, [03](03-design.md) ADR-13) | Android, Web, Backend, AI, DevOps / Security | Done in code, waiting on CI: display name and tagline in all four languages (Android `app_name`/`app_tagline`, Settings *About*, first-run house list; web title, header, page titles, meta description), new launcher icon, `applicationId` `app.doorprints`, CI artifacts `doorprints-debug-apk` / `doorprints-release-apk` / `doorprints-web-dist`, `spring.application.name` and MCP server name `doorprints(-api)`, export file `doorprints-export-<date>.json`, eval scorecard title, golden set v0.4 (description only). Kept: Java/Kotlin packages, repository `house-hunt`, storage keys, database, image and volume names. Pre-rename Android test builds are not upgraded (new id): sync, then uninstall |
| S3-08 | As the owner the docs, README and CHANGELOG use the new name and record the rename decision | Docs | Done: 01 v0.8 (also TC-AI-17 in the RTM), 02 v0.9, 03 v0.6 (ADR-13), 04 v0.5, 05 v0.3, 06 v0.9, 07 v0.7, 08 v0.7, 09 v0.4, this log v0.6, docs index v0.8, README, CHANGELOG. Historical change-log rows keep the old name. Coordinator nits fixed: S3-06 and the docs index v0.7 row now describe 06 v0.8 in full; the CHANGELOG quotes the neutral Ask prompt example ("X is over budget"); run 35720654442 recorded as run on `6a348cc` |

### 5.2 Findings of the first real AI eval run

| # | Finding | Effect | Owner | Status |
|---|---|---|---|---|
| E-01 | Gemini's OpenAI-compatible embeddings endpoint is not compatible with the app's OpenAI embeddings client: the embedding `index` is not set | Embeddings against Gemini fail, so RAG indexing and retrieval cannot be evaluated | AI | **Fixed, confirmed** by the real Gemini eval run of 2026-09-22 (Actions run 35720654442): indexing and retrieval ran against Gemini. Embeddings use the native Gemini `batchEmbedContents` API (`GeminiEmbeddingModel`, new `AI_EMBEDDING_PROVIDER`/`_API_KEY`/`_BASE_URL`/`_TASK_TYPE` settings; TC-AI-11, TC-AI-14); reindex returns 503 if any batch fails (TC-AI-12). Backend CI green on `6a348cc` |
| E-02 | The eval scorecard reported a false PASS | The scorecard cannot be trusted until fixed; no AI quality result is recorded for this run | AI | **Fixed, confirmed** by the same run (12/13 cases passed; the scorecard gave a per-metric verdict and reported the one failing metric instead of a false PASS): the scorecard FAILs when zero cases ran or on any harness error (seeding or reindex), with *Errors* and *Why FAIL* sections (TC-AI-10, TC-AI-13). Backend CI green on `6a348cc` |
| E-03 | Run 35720654442: `citationPrecision` 0.86, below the 0.90 threshold; every other metric passes. Cause: one contrast citation. In `ask-02-filtered-parking` ("Which one has car parking?") the model answered that the Corner flat has covered car parking "whereas the Blue gate house only has bike parking" and cited both houses; the contrast is grounded in the fixture notes, but the golden set expected only the Corner flat | At the time: the eval gate was not passed, so AI stayed off by default (AI-012). AI is still off by default, for the reason under this table — not for this defect | AI | **Fixed, closed** by a real run: **35758157317** (2026-09-22, commit `19006bc`, `provider=vertex`, golden set **v0.5**) passed **13/13 cases and every metric** (§10.2). The fix shipped in `feb0294` + `19006bc`: golden set v0.3 added optional `allowedCitations` (houses that may be cited but are not required; `citationPrecision` counts them as correct, `citationRecall` still uses `expectedHouseIds` only) and the Ask prompt says to cite a house only for a fact taken from its record and to mention other houses only as brief, cited contrasts; golden set v0.5 (`feb0294`) added `allowedCitations` to `ask-01` after the first Vertex run and made only an inline `[house:id]` marker count as a citation; `19006bc` fixed the `EvalScorerTest` empty-list assertion that this introduced (§9.2). **No threshold was lowered.** The AI Studio path has not been re-run since golden set v0.5 |

Detail and the fixes are in the AI docs ([ai/](ai/)); tests in [06](06-test-plan.md) §8 (TC-AI-10..14), data flow
DF-32 in [04](04-data-flow-diagrams.md), settings in [08](08-operations-runbook.md) §1.1. E-03 is closed, but a green run
is not by itself the eval gate for turning AI on by default. The bar, as [01](01-requirements.md) **AI-012** states it, is a
green run **on the provider that would be enabled, on the commit being released**, with the spend controls in place
(AI-015, AI-017). Run 35758157317 meets it for the **Vertex** path on `19006bc`; the **AI Studio** path has not been
re-run since the golden set moved to v0.5, so AI stays **off by default** (AI-001).

### 5.3 First successful real AI eval run

| Field | Value |
|---|---|
| Date | 2026-09-22 |
| Workflow run | `ai-evals.yml` (manual), GitHub Actions run 35720654442, free-tier Gemini key (`AI_API_KEY` repository secret) |
| Code | Commit `6a348cc` (Sprint 3; confirmed on the run page, `ai-evals.yml` is manual and runs the ref chosen when it is started): native Gemini embeddings (E-01), fail-safe scorecard (E-02), contact redaction (F-30) |
| Cases | 13 ran, 12 passed (scorecard "Cases 12 / 13 passed"); failing case: `ask-02-filtered-parking`, check "cites only expected houses" |
| Metrics | All pass their thresholds except `citationPrecision`: 0.86 vs 0.90 (E-03, one contrast citation) |
| Verdict | FAIL on one metric; the scorecard is now trustworthy (no false PASS) and the remaining gap is a golden-set and prompt issue, not a provider or harness defect |
| Follow-up | E-03 (AI team): `allowedCitations` in the golden set (v0.3) and an Ask prompt tweak, then a new run. **Done:** golden set v0.5 and the inline-marker citation rule (`feb0294`), run 35758157317 green on `19006bc` (§10.2) |

The per-metric values other than `citationPrecision` are in the run's scorecard (job summary and the `ai-eval-report`
artifact); this log does not copy them.

### 5.4 CI results

| Run | Backend | Web | Android | Security |
|---|---|---|---|---|
| `6a348cc` (Sprint 3: native Gemini embeddings, contact redaction, fail-safe scorecard, compose AI settings, docs) | ✅ | not triggered (no `web/` change) | not triggered (no `android/` change) | ✅ |

The last Web and Android runs are green on `0e4e22a` (section 4.2).

### 5.5 Sign-off

| Team | Senior review | Sign-off |
|---|---|---|
| AI | Pending | Sprint 3 defects **all closed**: E-01, E-02 (run 35720654442 on `6a348cc`, confirmed) and **E-03**, closed by run **35758157317** on `19006bc` (`provider=vertex`, golden set **v0.5**, 13/13 cases and every metric, §10.2). Still pending: the senior review itself, and the two Vertex items carried into 4a — the trial **credit check** and vertex-setup step 9 (§10.2, §11.3 item 6) |
| Backend, Android, Web, DevOps / Security, Docs | Pending | Pending (S3-07 rename: CI on the merge commit) |
| Product owner | – | Pending: lead decision recorded to close F-30 as Fixed. AI-012: the first fully green run is now recorded (35758157317, Vertex), and AI still stays **off by default** — the bar is a green run on the provider that would be enabled, on the commit being released ([01](01-requirements.md) AI-012) |

## 6. Next sprint candidates (Sprint 3)

| ID | Candidate | Why | Link | Status |
|---|---|---|---|---|
| C-01 | Commit `web/package-lock.json` (from the `web-package-lock` artifact) and switch `web.yml` to `npm ci` only | Reproducible web builds, A03 | 07 §2 | Part: lock file committed (`0e4e22a`); `web.yml` still has the `npm install` fallback |
| C-02 | `release.yml`: tag → signed APK + `.sha256` on a GitHub Release, signer fingerprint in the README | Finish F-11 / SEC-018 | 07 §5 | Open |
| C-03 | R8 keep rules (`proguard-rules.pro`) and a release smoke test, then turn on `isMinifyEnabled`/`isShrinkResources` | F-11, M7 | 07 §5 | Open |
| C-04 | Per-device API keys, stored hashed, with names and revocation | Close F-01b (SEC-025) | 02 §5 | Open |
| C-05 | Remaining Android unit tests: sync cursor and tombstones with a fake `ApiClient`, house-alert cooldown, accuracy gate, DTO mapping, photo pipeline | TC-U-06..08, TC-U-10, TC-U-11 | 06 §10 | Part (Sprint 3.5): DTO mapping done (`ModelMappingTest`, TC-U-10); a fake `ApiClient` is now easy with Ktor `MockEngine` |
| C-06 | Web: specs for `ai.service.ts` (`splitCitations`, `aiErrorMsg`), Playwright smoke + axe | TC-A-01, AI UI | 06 §10 | Open |
| C-07 | Run `ai-evals.yml` with a free-tier Gemini key (repository secret `AI_API_KEY`) and record the scorecard | TC-AI-10 (covers TC-AI-01..08 cases), AI-001 | [ai/](ai/) | Done: run 35720654442 (2026-09-22) recorded in section 5.3; follow-up E-03 closed by run 35758157317 (§10.2) |
| C-08 | `deploy.yml`, `backup.yml`, `keepalive.yml` | Operations (NFR-008) | 08 §3 | Open |
| C-09 | Drop the Tomcat override once Spring Boot manages 11.0.25+ | Keep the BOM authoritative | 07 §1 | Open |
| C-10 | Pin scanner images by digest; Gradle dependency locking so Trivy can scan Android dependencies | Supply chain (T-T5) | 07 §1 | Part (Sprint 3.5): the `gradle-dependency-graph` job in `security.yml` sends the Android graph to Dependabot alerts; digests and lock files still open |
| C-11 | Field walk test TC-F-01..08 on a real phone with the signed APK | Hunt mode quality, R-01 | 06 §6 | Open |
| C-12 | Dev `docker-compose.yml`: pass `APP_API_KEY_NEXT: ${APP_API_KEY_NEXT:-}` to the `api` service so the local stack can rehearse the key-rotation drill TC-O-02 (raised with the Backend/DevOps manager) | Rotation drill without a production-like stack | 08 §5.1 | Done (`docker-compose.yml`, `f7da5ab`) |
| C-13 | Leave the contact name out of the text sent to the LLM provider: `HouseDocuments.text()` (embedding text DF-32 and Ask context DF-21) and `HouseQueries.HouseDetails` (planner/MCP tool results); add a test that no contact name or phone is in that text; reindex after the change. Found by the Docs team in the DF-32 re-check; raised with the AI team | AI-010 says contact names are redacted by default, but the name goes to the provider on every index and reindex (F-30) | 02 §5 (F-30), 04 DF-32 | Done (S3-03): F-30 closed as Fixed by lead decision; evidence: TC-AI-15 green in the Backend workflow on `6a348cc` (the eval run's fixtures hold no contact data, so it is not a redaction test; see C-21) |

## 7. Sprint 4 candidates (not committed)

These are **candidates only**, not committed scope: they wait for the product owner to choose and order them at
Sprint 4 planning. The open Sprint 3 candidates in section 6 (C-01..C-06, C-08..C-11) stay on the list. (E-03, which
this paragraph originally put first, was closed in Sprint 3.5 by run 35758157317, §10.2.) Every candidate keeps the zero-cost rule and AI off by default (AI-001); any new third-party data
flow needs a DFD ([04](04-data-flow-diagrams.md)) and threat-model ([02](02-threat-model.md)) update before it is built,
and any new external API gets the wire-format check and contract test from the *Process* note in section 1.

| ID | Candidate | Why | Teams (proposed) | Notes for planning |
|---|---|---|---|---|
| C-14 | Offline map areas with MapLibre: download the map for an area the user is about to hunt in, list and delete stored areas | Hunt mode is used in the street, often with a weak or no data connection | Android (web later, if at all) | MapLibre Android offline regions; tile count and storage limits, and the OpenFreeMap usage terms for bulk downloads, to check before building |
| C-15 | AI: natural-language map filter. The user types a question ("2BHK under 30k near the metro with parking"), the model returns a filter as JSON, the API validates it against a fixed schema and the map highlights the matching houses | Faster filtering on the map without building a complex filter UI | AI, Backend, Web, Android | Model output is data, never code or SQL: allowlisted fields, types and ranges (AI-005, LLM05), unknown fields rejected; only the question goes to the provider; new golden-set cases for the filter |
| C-16 | AI: voice notes during a visit. On-device speech-to-text on Android, then Gemini extracts structured fields (price, BHK, checklist hints, notes) from the transcript for the user to confirm | Hands-free capture while walking through a house | Android, AI | Audio stays on the device; only the transcript goes to the provider, after `ContactRedactor`; the user confirms before anything is saved; needs a PRV/AI requirement and a DFD row; offline falls back to saving the transcript as a note |
| C-17 | AI: neighbourhood summary. A 10-minute walk isochrone around the house, OpenStreetMap amenities inside it, and a grounded AI summary that cites the amenities it uses | Answers "what is near this house" with sources instead of guesses | Backend, AI, Web, Android | Isochrone and amenity sources must be free and used within their usage policies (to be chosen; each is a new third-party flow that receives house coordinates); cache results; the summary may only use and must cite the returned data (AI-002, AI-003); OSM attribution (ODbL) shown |
| C-18 | Hunt mode alert one-liner precomputed when the house is saved (template, with an optional AI polish), plus a "Directions to next stop" action that opens Google Maps through an intent | Alerts read well without any network call at alert time; one tap to the next planned house | Android, Backend, AI | Template output is the default and works offline; AI polish only with AI on, stored with the house and redacted like other provider-bound text; the directions action opens the Google Maps app or website (no API key), sending the destination to Google only on the user's tap (T-I5) |
| C-19 | Show the local house label instead of `[contact]` / `[phone]` in AI citations and planned stops (S3-05) | Redacted labels (F-30 client contract) are confusing to the user | Android, Web | Look up the label by `houseId` in the local store; fall back to the API label; no API change |
| C-20 | CI smoke test that starts the backend with AI enabled (`APP_AI_ENABLED=true`) and a stubbed provider key, and checks startup and health | AI wiring and configuration defaults are only exercised in manual eval runs today; a startup failure with AI on should fail CI (runtime pre-mortem, section 1) | Backend, DevOps / Security | No calls to a real provider and no real key (a generated dummy value, not a secret); runs in `backend.yml` |
| C-21 | Golden-set fixture house with a contact name and phone (also typed into a note and the address), plus `mustNotContain` checks on the answer and citations for that contact | Run 35720654442 did not exercise redaction against the provider: no fixture house has contact data, so F-30 rests on TC-AI-15 alone ([02](02-threat-model.md) F-30) | AI | Fictitious name and number only (no real person's data); fits the existing golden-set schema (`mustNotContain`); reuses the manual `ai-evals.yml` run, no extra cost |

## 8. Product-owner decisions of 2026-09-22 and new Sprint 4 candidates

### 8.1 Decisions

| # | Decision | Recorded in |
|---|---|---|
| PO-1 | Repository renamed to **`Sriram-Codes-SW/doorprints`** and made **public**, with an MIT `LICENSE` and `SECURITY.md` (private vulnerability reporting). `main` is protected by a ruleset (no deletion, no force push). Required status checks are **deliberately not enabled** until a PR flow with an always-running `CI summary` check exists (C-23). | [07](07-secure-build-and-deploy.md) §3, [08](08-operations-runbook.md) §10.1, [02](02-threat-model.md) T-I21, README |
| PO-2 | **AI access policy.** Guests get no cloud AI: on-device Gemini Nano through the ML Kit GenAI Prompt API where the Android device supports it, otherwise AI is hidden. Cloud AI only for the owner and invited users, on a paid key with a hard cap. **Bring-your-own-key rejected** (consumer UX, payment-linked secret risk, support burden). The owner's own Vertex credential carries the same payment-linked risk, handled by [02](02-threat-model.md) T-I22. The free AI Studio tier may use prompts to improve Google products, so real user data goes only to the paid tier or Vertex AI. | [11](11-feature-parity-and-export-spec.md) v0.3 D-21, D-22, 5.13; [01](01-requirements.md) AI-013..AI-016, PRV-022, PRV-023; [02](02-threat-model.md) T-I20 |
| PO-3 | **Vertex AI** becomes an active provider next to AI Studio; the AI Studio code stays so the owner can switch back. Implemented by the AI team in the same change set (`AI_PROVIDER=vertex`; the default stays `aistudio` until the owner has done the setup). | [ai/vertex-setup.md](ai/vertex-setup.md), [01](01-requirements.md) AI-016, AI-017 |
| PO-4 | **Google Cloud $300 free trial** (90 days) is used for Vertex AI (if the credit applies), Firebase Test Lab on Indian-market devices, a staging backend (Cloud Run + Cloud SQL) and a Speech-to-Text prototype, with a spend cap budget on the AI service and budget alerts (Cloud SQL is not covered by spend caps). The trial ends without an automatic charge. | 8.3, [08](08-operations-runbook.md) §10.3, [07](07-secure-build-and-deploy.md) §6.5 |

### 8.2 New Sprint 4 candidates (not committed)

Candidates only, for Sprint 4 planning next to section 7 and the plan in [11](11-feature-parity-and-export-spec.md) §14.

| ID | Candidate | Why | Teams (proposed) | Notes for planning |
|---|---|---|---|---|
| C-22 | **CodeQL** code scanning (Java/Kotlin, JavaScript/TypeScript) and SARIF upload of Semgrep to the Security tab | Free now that the repository is public; better SAST coverage | DevOps, Security | **Landed in Sprint 4a** (§11.1): `.github/workflows/codeql.yml` analyses `java-kotlin` and `javascript-typescript` with `build-mode: none`, on push to any branch (since §12.5 Decision 5; on push to `main` before), weekly and manually ([07](07-secure-build-and-deploy.md) §1). Follow-ups, still open: (a) **Android Kotlin is not analysed** — buildless Kotlin needs a real Gradle run, so it stays covered by Semgrep; (b) the **Semgrep SARIF is still only an artifact**, not uploaded to the Security tab; (c) no run yet (nothing in this change set is pushed, §11.2) |
| C-23 | PR flow and an always-running **`CI summary`** check, then required status checks, "require PR" and linear history in the ruleset | Required checks today would block PRs whose paths skip a workflow | DevOps, Docs | [07](07-secure-build-and-deploy.md) §3.1; only `CI summary` becomes required |
| C-24 | **Vertex AI** provider next to AI Studio, switchable by configuration; evals run against both | PO-3; real data off the free tier (PRV-022) | AI, Backend | **Owner setup done (2026-09-22, section 10):** project `doorprints-ai`, GitHub variables `GCP_PROJECT_ID=doorprints-ai`, `GCP_LOCATION=asia-south1`, `AI_VERTEX_EMBEDDING_LOCATION=global` (step 8: chat verified in `asia-south1`, `gemini-embedding-2` only on `global`); done-when item (2) is met for step 8; step 10 is **started**: the first `provider=vertex` eval (run 35753477789, `gemini-3.5-flash` in `asia-south1` + `gemini-embedding-2` on `global`, commit `8f583af`) failed only on `citationPrecision` 0.78 (7/9), answered in `feb0294` by golden set v0.5 and the rule that only inline `[house:id]` markers count as citations (thresholds not lowered); the **re-run is done and green** (run **35758157317** on `19006bc`, `provider=vertex`, golden set v0.5: **13/13 cases and every metric pass**, 198 s, two provider `503`s absorbed by the bounded retry), which closes E-03 and gives item (3) its evidence (the run itself; writing it into [ai/ai-design.md](ai/ai-design.md) §8.5 is the AI team's file); the **credit check** (step 10), step 9 (real captured responses) and the default flip are open, and so is item (4); DevSecOps review of `ai-evals.yml` signed off. **PO-9 (10.3): both providers stay** — AI Studio remains the default and Vertex AI is not replacing it, so C-24 does not end with removing either path. Earlier status: **Code landed (2026-09-22), pending first CI.** Shipped: `AI_PROVIDER` switch (`aistudio` default, `vertex`), Vertex chat through Spring AI's Google GenAI starter with the app's own `Client` (ADC only, bounded retries), `VertexEmbeddingModel` (`:embedContent` / `:predict`, one text per call), `503` + `code: AI_QUOTA_EXHAUSTED` + `Retry-After: 60` for provider quota errors, re-index and eval stop on quota, `AI_INDEX_ON_CHANGE`, `ai-evals.yml` `provider` (default `aistudio`; `vertex` chosen by hand after vertex-setup steps 1-8, the default flips to `vertex` only after step 10) and `embedding_model` inputs with Workload Identity Federation; a `setupHint` on the `503` for Vertex AI 401/403/404 (`AiExceptionHandlerTest`, TC-AI-22); owner setup in [ai/vertex-setup.md](ai/vertex-setup.md). **Done when:** (1) Backend workflow green with the new tests (TC-AI-16, TC-AI-18..22 in [06](06-test-plan.md)); (2) owner's setup step 8 (models answer in `asia-south1`, or `AI_VERTEX_EMBEDDING_LOCATION` / `GCP_LOCATION=global` chosen) and step 10 (the trial credit pays for Vertex AI; first `extract` eval run green, with `provider=vertex` chosen by hand), then the `ai-evals.yml` default flipped to `vertex` (step 10 item 6; until then the default run uses AI Studio); (3) full eval (TC-AI-10) on `provider=vertex` recorded in [ai/ai-design.md](ai/ai-design.md) §8.5; (4) **spend cap trip (manager review):** the provider response when the AI-015 spend cap blocks the service is captured (or taken from Google's documentation), mapped to a clear "cloud AI paused" problem that is **not retried** (no SDK/embedding retries, `retryable: false`, no short `Retry-After`; re-index and eval stop at once), and pinned in a contract test next to the `AI_QUOTA_EXHAUSTED` tests, which today cover HTTP 429 only ([01](01-requirements.md) AI-017). Docs sync done in this change set (02 T-I22, 03 §13, 04 E6/DF-21/DF-32, 06, 07 §4 and §7, 08 §1.1, IR-9, §9) |
| C-25 | **AI access tiers**: cloud AI allowlist (owner + invited), per-user quota, global hard cap; Android on-device AI spike (ML Kit GenAI Prompt API, beta) | PO-2 | Backend, AI, Android, Web | Sprint 5 story S5-07 in [11](11-feature-parity-and-export-spec.md); the Android spike can start in Sprint 4 |
| C-26 | **Firebase Test Lab** runs of the debug APK (Robo + instrumented tests) on Indian-market devices (popular Xiaomi/Redmi, Samsung Galaxy A/M, Realme, Vivo/Oppo models available in the catalogue) | Real-device coverage for Hunt mode, OEM battery savers and Gemini Nano support that we cannot test locally | Android, DevOps | Paid from the trial credit; manual `workflow_dispatch` job or console runs; no real personal data in test accounts; device list chosen from what Test Lab offers at the time. After the trial, Test Lab keeps working within its no-cost Spark plan daily quota (a few physical- and virtual-device runs a day; check the current numbers in the Firebase docs), so device testing does not stop when the credit runs out |
| C-27 | **Staging backend** on Cloud Run + Cloud SQL for PostgreSQL (PostGIS, pgvector) for the 90 trial days | A production-like place for migrations, AI and the Sprint 5 sign-in work | Backend, DevOps | Synthetic data only; torn down before the trial ends ([07](07-secure-build-and-deploy.md) §6.5) |
| C-28 | **Speech-to-Text prototype** for voice notes (C-16) | Compare Google Cloud Speech-to-Text (Indian English, Hindi, Tamil, Telugu) with on-device recognition | AI, Android | Prototype only, synthetic recordings; any real-user flow needs a DFD and threat-model update first; C-16 stays audio-on-device by default |

### 8.3 Google Cloud trial: operations notes

| Item | Note |
|---|---|
| Budget | On day 1 ([08](08-operations-runbook.md) §10.2, §10.3): a **spend cap budget** (Google Cloud Billing, Preview) on the AI-only project and the Vertex AI or Gemini API service, which blocks new AI usage when the monthly target is passed, plus alerts at 50 %, 90 % and 100 %. Alerts alone only notify. The spend cap counts cost **before** credits, so it can pause cloud AI while trial credit is left; size the target for that. |
| Cloud SQL | Spend cap budgets do **not** cover Cloud SQL. The staging project gets its own budget with alerts and a fixed tear-down date written in the password manager entry ([07](07-secure-build-and-deploy.md) §6.5). |
| Credit | Check monthly how much credit is left and the end date. **The owner's trial credit ends on 22 Dec 2026**; export by about 15 Dec and switch back to AI Studio or upgrade before the 22nd ([08](08-operations-runbook.md) §10.4). Whether the trial credit covers the chosen Vertex AI (Gemini) models is still **to be confirmed** (vertex-setup step 10). |
| Data | Staging, Test Lab and Speech-to-Text use synthetic data only. Real data only in production cloud AI for the owner and invited users. |
| End of trial | No automatic charge unless the billing account is upgraded. Tear down staging (Cloud SQL, Cloud Run) before the end; move production cloud AI to the paid, hard-capped setup ([08](08-operations-runbook.md) §10.2) or turn it off. |
| Zero-cost rule | The trial is time-limited; nothing in production may depend on it after it ends (CON-01). |

## 9. Sprint 3.5: KMP foundation

Goal (product owner, 2026-09-22): make the Android code base **Kotlin Multiplatform-ready now, ship iOS later**.
There is no Mac, no iPhone and no Apple Developer Program fee (zero cost), so the sprint adds no iOS app and no iOS UI;
it proves that the code an iOS app would reuse compiles without Android or JVM APIs. Decision record: [03](03-design.md)
ADR-14. Module detail: `android/shared/README.md`.

### 9.1 Stories

| ID | Story | Team | Result | Status |
|---|---|---|---|---|
| S3.5-01 | `:shared` KMP module (`com.android.kotlin.multiplatform.library` + Kotlin Multiplatform 2.4.10): models and wire names, `HouseScore`, `SyncRules`, `SyncOutcome`, `Geo`, `StayDetector`, `StreetAlerts`, DTOs, `IsoTime`; version catalog `android/gradle/libs.versions.toml` | Android | Module boundaries in [03](03-design.md) §4.2.1; the pure suites moved to `commonTest` | Done (`8f583af`) |
| S3.5-02 | Ktor 3.6 `ApiClient` (OkHttp 5.5 engine on Android) replaces the OkHttp 4 client and `RetryInterceptor` with the same behaviour; photo upload streamed from the file; `ApiClientContractTest` with Ktor `MockEngine` | Android | Same requests and retry rules (TC-U-17, TC-U-35); MapLibre now on OkHttp 5.5.0, manual smoke test TC-M-17 | Done |
| S3.5-03 | Room schema export, committed `2.json`, `RoomSchemaTest` identity-hash guard; `ModelMappingTest`; sync worker no longer records a WorkManager stop as a failed sync | Android | TC-U-36, TC-U-10; R-06 part | Done |
| S3.5-04 | `shared-ios.yml`: compile the iOS main and test klibs on `macos-latest` (no link, no signing), path-filtered; `kotlin.native.enableKlibsCrossCompilation=false` keeps iOS off the ubuntu job | DevSecOps | TC-U-37 | Done |
| S3.5-05 | `android.yml` runs `:shared:testAndroidHostTest`; `security.yml` job `gradle-dependency-graph` (Dependabot alerts for Android dependencies); `google-github-actions/auth` pinned by SHA; DevSecOps review of `ai-evals.yml` recorded in its header; Dependabot ignores majors of the KMP library plugin | DevSecOps | [07](07-secure-build-and-deploy.md) v0.11 | Done |
| S3.5-06 | Docs: ADR-14 and the component view, test plan, CI, OSI, DFD, requirements and runbook updates, README refresh, CHANGELOG | Docs | 01 v0.12, 02 v0.13, 03 v0.8, 04 v0.7, 06 v0.11, 07 v0.11, 08 v0.11, 09 v0.5, this log v0.10 | Done (this change) |

### 9.2 CI results

To be recorded in full from the GitHub Actions runs of `8f583af` (Android, Security, Shared iOS compile). Known so
far (DevSecOps, 2026-09-22): the new **`gradle-dependency-graph`** job in Security **failed** at the submission step
("Dependency submission failed"), most likely because the repository's dependency graph setting is off. DevSecOps'
follow-up keeps the job but makes only the submit/upload phase non-blocking
(`dependency-graph-continue-on-failure: true`, not a job-level `continue-on-error`; a Gradle resolution or
configuration-filter failure still fails the job) with a preflight that names the setting and a check that warns when
no graph JSON was produced; owner action: enable Settings → Security → Dependency graph
([08](08-operations-runbook.md) §10.1) and re-run Security on `main`. Exit criterion: the job log shows
`Submitted dependency-graph-reports/...` (a green job alone proves nothing while the input is on); then switch the job
back to fail-closed. Other watch points: the first `shared-ios.yml` run
downloads the Kotlin/Native toolchain (slow, then cached); MapLibre networking on OkHttp 5 is not covered by CI
(TC-M-17 on a phone).

**Runs recorded 2026-09-22 (Docs team).** The follow-up landed in commit `feb0294` (AI and DevSecOps change set, now
remote `main`). Read from the public GitHub Actions pages (github.com, not the API; the build environment cannot open
job logs), so each row needs the owner's confirmation in the Actions tab:

| Commit | Workflow (run) | Result as read | Confidence |
|---|---|---|---|
| `8f583af` | Security (35753477885) | ❌ `gradle-dependency-graph` failed at submission (above) | Confirmed by DevSecOps |
| `8f583af` | Android (35753477853) | ✅ run status "Success" and listed as "completed successfully"; debug APK and reports uploaded; signed release skipped (no signing secrets) | Medium: one reading of the run page showed the `assembleDebug + unit tests` job with a failure icon; check the job |
| `8f583af` | Shared iOS compile (35753478002, first run) | ✅ `:shared iOS targets compile (macOS, JDK 21)`, about 3.5 min | High |
| `8f583af` | AI evals, manual, `provider=vertex` (35753477789) | ❌ only `citationPrecision` 0.78 (7/9), see C-24 and [ai/ai-design.md](ai/ai-design.md) 8.5 | High (recorded by the AI change set in golden set v0.5) |
| `feb0294` | Security (35755840179) | ✅ all six jobs; the dependency graph job reported that dependency results were updated | Medium: **a green Security run does not prove submission** while `dependency-graph-continue-on-failure` is on; the exit criterion stays the `Submitted dependency-graph-reports/...` notice in that job's log |
| `feb0294` | Backend (35755840287) | ❌ **Failed** (`mvn verify` exit code 1): 258/259 tests passed; `EvalScorerTest.goldenSetFileIsConsistent` threw `IllegalArgumentException: The iterable of values to look for should not be empty`. Cause: `ask-01` in golden set v0.5 has `allowedCitations` but no `mustNotCite`, and AssertJ's `doesNotContainAnyElementsOf` throws on an empty list (a test bug, not a product bug; the new Ask citation rule tests passed). Fix: AI team, `backend/src/test/java/app/doorprints/server/ai/eval/EvalScorerTest.java` only, in the next commit: every id-list check skips an empty or missing list ("no constraint"); golden set and thresholds unchanged ([06](06-test-plan.md) TC-AI-09, [CHANGELOG](../CHANGELOG.md)) | High: failure and cause confirmed by the AI team from the run's test report; the green Backend run on the fix commit is recorded in the `19006bc` row below |
| `19006bc` | Backend | ✅ **Green** — `mvn verify` passes, so `EvalScorerTest.goldenSetFileIsConsistent`, the one case that failed on `feb0294` (258/259), now passes with the rest | Confirmed |
| `19006bc` | Security | ✅ all jobs green | Confirmed |
| `19006bc` | Android | ✅ green, `assembleDebug testDebugUnitTest :shared:testAndroidHostTest` — **but note how**: `android.yml` is path-filtered to `android/**`, and `19006bc` touches only `backend/src/test/...`, so the push itself does not trigger it. A green Android result on this commit therefore comes from a `workflow_dispatch` run (the workflow has one) rather than from the push | Confirmed as green; **the trigger should be checked in the Actions tab**, because "green on `19006bc`" and "green on the Android tree as of `19006bc`" are the same thing here only if someone dispatched it |
| `19006bc` | Shared iOS compile | ✅ green | Same caveat: path-filtered to `android/shared/**` and the root Gradle files, so `19006bc` does not trigger it on push |
| `19006bc` | AI evals, manual, `provider=vertex` (35758157317) | ✅ **PASS, 13/13 cases and every metric**, golden set v0.5, 198 s; two provider `503`s retried by the harness | Confirmed (10.2) |

`feb0294` touches no Android, web or shared code, so Android, Web and Shared iOS compile did not run on it.
`19006bc` touches backend test code only. Of the five workflows, only **Backend** and **Security** are triggered by
that push — Security has no path filter, Backend is filtered to `backend/**` *(corrected in v0.20: at `19006bc` the filter was `backend/**` and the workflow file only; `docs/ai/evals/**` and the other outside paths were added in the Sprint 4a working tree, [07](07-secure-build-and-deploy.md) §1)*. `android.yml`,
`shared-ios.yml` and `web.yml` are path-filtered and do **not** run on it. So "green on `19006bc`" means: the two
workflows the commit triggers are green, and the Android and shared-iOS trees (unchanged since `8f583af`) are green
too. The last green **Web** run stays `0e4e22a`. Worth keeping straight, because a path-filtered workflow that did
not run looks the same as one that passed on a branch's checks page.

**Sprint 3.5 is therefore closed green on `19006bc`**: every workflow that `19006bc` triggers passed, and the two
defects found on the way (`gradle-dependency-graph` submission, `EvalScorerTest` empty-list assertion) are fixed. The
one item that a green run cannot prove stays open: the `Submitted dependency-graph-reports/<file>.json` notice in the
`gradle-dependency-graph` job log. The owner has now **enabled Settings → Security → Dependency graph**
([08](08-operations-runbook.md) §10.1), so the next Security run on `main` is the one to read; after it shows the
notice, DevSecOps removes `dependency-graph-continue-on-failure` and the job is fail-closed again
([07](07-secure-build-and-deploy.md) §1).

### 9.3 Phase 2 plan (not scheduled)

1. Room KMP (Room 2.8 in `commonMain`, bundled SQLite driver), keeping `doorprints.db` (renamed from `househunt.db`
   since 2026-09-24, §14), version 2, `MIGRATION_1_2` and `2.json`;
   migration test from real v1/v2 files; then the mappers.
2. DataStore KMP; `expect/actual` secret storage (Android Keystore / iOS Keychain).
3. `ServerUrl` as a common parser or `expect/actual`.
4. iOS app (SwiftUI over the shared framework, or Compose Multiplatform) only when a Mac and the Apple Developer
   Program are available; `iosMain` with `ktor-client-darwin`; run the iOS tests on a simulator in CI.
5. iOS platform services: `CLLocationManager` (region monitoring also suits Hunting areas, 20 regions per app),
   `CLGeocoder`, `BGTaskScheduler`, `NWPathMonitor`.

Until then iPhone users are served by the PWA ([11](11-feature-parity-and-export-spec.md) 5.10, Sprint 4a).

### 9.4 Sign-off and retrospective

Sign-off (2026-09-22, on the green CI of `19006bc`): Android senior reviewer ✅ (`:shared` module, Ktor client, Room
schema guard — Android and Shared iOS compile green); DevSecOps senior reviewer ✅ **with one open item**, the
dependency-graph submission notice (9.2), which does not block the sprint because the job is additive (it feeds
Dependabot alerts) and everything else it guards is unchanged; AI senior reviewer ✅ for the `EvalScorerTest` fix
(Backend green on `19006bc`). The Docs change is S3.5-06 plus this record. Retrospective notes: the
KMP move kept every wire format stable by pinning it in contract tests first (good, repeat for Room KMP); the module
README and the workflow comments drifted slightly (the README still says `android.yml` also runs `:shared:allTests`
and lists a `GeoTest` that is part of `StayDetectorTest`), for the Android team to align.

## 10. Product-owner decisions later on 2026-09-22

### 10.1 Sprint 4b scope additions and location permissions

| # | Decision | Recorded in |
|---|---|---|
| PO-5 | **Hunt mode reminders**: a local notification before a planned viewing (default 15 min, 5 to 60) with a "Start Hunt mode" action; global and per-viewing switches; offline; Do Not Disturb respected. Sprint 4b (S4-17) | [11](11-feature-parity-and-export-spec.md) D-23, 5.16; [01](01-requirements.md) FR-083, FR-084 |
| PO-6 | **Hunting areas / area wake-up**: up to 20 user-drawn areas, Android geofences, a "Start Hunt mode?" notification on entering, never auto-start, 6-hour cooldown per area, re-registered after reboot and update; stored locally, exported, synced later. Sprint 4b (S4-18) | [11](11-feature-parity-and-export-spec.md) D-24, 5.17; [01](01-requirements.md) FR-085..FR-088, NFR-030 |
| PO-7 | **Location permission model**: foreground only by default ("While using the app" / "Only this time"); "Allow all the time" only when area wake-up is turned on, after a rationale screen and via system settings on Android 11+; area wake-up turns itself off if the permission is downgraded; re-check on every resume; approximate location explained. Sprint 4b (S4-19, with S4-18) | [11](11-feature-parity-and-export-spec.md) D-25, 5.18; [01](01-requirements.md) PRV-024..PRV-027, SEC-049, PRV-001; [03](03-design.md) ADR-01 note |

The accepted Sprint 4b threats (T-I23, T-I24, T-E8) and tests (TC-U-38..40, TC-M-18, TC-F-12, TC-S-22, TC-A-12)
are now in [02](02-threat-model.md) v0.14 and [06](06-test-plan.md) v0.12 §13, not only in 11; the reminder
scheduling rule was corrected in [11](11-feature-parity-and-export-spec.md) v0.7 5.16 (exact alarm when allowed,
otherwise a 10-minute window that ends at the reminder time, because Android 12+ clips shorter windows).

Sprint 4b grows from 49 to 63 points ([11](11-feature-parity-and-export-spec.md) 14.2, RK-01); moving S4-13 and S4-15 to
Sprint 5 is the suggested way back to about 49 if the product owner wants it.

### 10.2 Vertex AI setup outcome (PO-8)

| Item | Result |
|---|---|
| Project | `doorprints-ai` on the Google Cloud trial billing account; setup steps 1-8 of [ai/vertex-setup.md](ai/vertex-setup.md) done by the owner |
| Models | Chat `gemini-3.5-flash` verified in `asia-south1`; `gemini-embedding-2` **not** available in `asia-south1`, verified on `global` |
| GitHub variables | `GCP_PROJECT_ID=doorprints-ai`, `GCP_LOCATION=asia-south1`, `AI_VERTEX_EMBEDDING_LOCATION=global` (secrets `GCP_WIF_PROVIDER`, `GCP_SA_EMAIL` set) |
| Data residency | Chat stays in India; embedding text (redacted house text, Ask questions) is processed on `global` ([02](02-threat-model.md) T-I20) |
| Trial end | Credit ends **22 Dec 2026**: export by about 15 Dec, then switch back to AI Studio or upgrade billing ([08](08-operations-runbook.md) §10.4) |
| First Vertex eval | Run 35753477789 (2026-09-22, commit `8f583af`): FAIL only on `citationPrecision` 0.78 (7/9) from `ask-01`; answered in `feb0294` (golden set v0.5; Ask citations need an inline `[house:id]` marker, `citedHouseIds` only as a fallback). Thresholds not lowered ([ai/ai-design.md](ai/ai-design.md) 8.5) |
| **Vertex eval re-run (green)** | Run **35758157317** (2026-09-22, commit `19006bc`, `provider=vertex`, `gemini-3.5-flash` in `asia-south1` + `gemini-embedding-2` on `global`, golden set v0.5): **PASS — 13/13 cases and every metric**, wall time **198 s**. Two provider `503`s were retried by the harness's bounded retry and did not fail the run; they are the reason to keep the retry and to watch p95 latency when the eval becomes a gate. E-03 is closed by a real run |
| **Credit check (step 10), done** | Reported by the owner on 2026-09-23: **₹45 of the trial credit** was used for the Vertex eval runs (Billing → Reports, project `doorprints-ai`), so the runs' cost came off the $300 trial credit and nothing was charged. Recorded here; [ai/vertex-setup.md](ai/vertex-setup.md) (AI team) still shows step 10 as open — handed over (§12.4) |
| Next | Step 9 (capture real Vertex responses for the contract tests, which today use SDK/reference shapes); then flip the `ai-evals.yml` `provider` default to `vertex` (C-24). The `503`s make it worth re-reading the run's warnings before the flip |

### 10.3 Both AI providers stay (PO-9)

| Item | Decision |
|---|---|
| Question | After the green Vertex eval (10.2) the AI team asked whether Vertex AI should replace AI Studio, so that only one provider path is maintained. |
| **Decision (product owner, 2026-09-22)** | **Keep both.** `AI_PROVIDER` stays a real switch: `aistudio` (default) and `vertex`. Which one becomes the long-term default is decided later, not now — having two working paths is worth the extra code while the product is still finding its AI shape. |
| Cost | The owner takes the billing risk explicitly ("if billing is the issue, I will handle it"). The cost controls do not change: AI is off by default (AI-001), the spend cap budget on the AI-only project stays the provider-side stop (8.3), and the trial credit end date (22 Dec 2026, [08](08-operations-runbook.md) §10.4) still applies to the Vertex path. |
| What it means for the code | No removal story. Both providers keep their tests (TC-AI-16, TC-AI-18..22) and both stay in the docs: [03](03-design.md) §13, [07](07-secure-build-and-deploy.md) §7, [08](08-operations-runbook.md) §1.1. The `ai-evals.yml` `provider` input stays a choice; flipping its default (C-24) is a convenience, not a migration. |
| Maintenance cost to accept | Two wire formats to keep working (Gemini API and Vertex), two auth models (API key vs ADC/WIF) and two eval baselines. The mitigation is the contract tests: a provider change must fail a test before it fails a user. |
| Revisit when | The trial credit ends, the spend cap trips (AI-017), or one provider's eval baseline drifts from the other's. |

## 11. Sprint 4a: local-first web, offline copies, import, PWA

Goal (plan in [11](11-feature-parity-and-export-spec.md) 14.1): make the web app local-first, give both apps the six
deterministic offline copies, let a backup be imported again, and make the web app installable. 51 points planned.

The **format was pinned first** (S4-00) and everything else was built against it: `docs/schemas/README.md` and
`backup-sample.json` define `doorprints-backup/1` for all three implementations, so the server, Android and web
could then be written in parallel without converters ([03](03-design.md) ADR-20).

### 11.1 Stories

| ID | Story | Team | Result | Status |
|---|---|---|---|---|
| S4-00 | Pin the backup format: `docs/schemas/README.md` + the canonical `backup-sample.json`; server `BackupFormat`/`BackupData`/`BackupMapper` | Backend | One format, three implementations, pinned by a shared sample file: the server and Android read it (parsed JSON; S4-00/a closed by `CanonicalSampleTest`), and the web writer's byte golden is compared with it by the backend's `BackupParityTest` — no web spec reads it ([06](06-test-plan.md) TC-I-34, ticket S4-00/e applied) | Done |
| S4-01 | Web local-first: IndexedDB repository, TS sync engine against today's API-key server (optional), persistent storage | Web | `data/local-db.ts`, `local-store.service.ts`, `sync.service.ts`, `storage.service.ts`; no route is guarded any more, the Connect page is optional; `MemoryDb` fallback when IndexedDB is blocked | Done |
| S4-02 | Android exporters: HTML, PDF, CSV, XLSX, Markdown, JSON backup; options; SAF, share | Android, Design | Pure writers in `:shared` (`com.househunt.shared.export`), platform glue in `app/export` (`PdfExporter` on `PdfDocument`, `Saf` on `DocumentsContract`, `ExportWorker`) | Done |
| S4-03 | Web exporters: the same six formats from IndexedDB; download, Web Share | Web, Design | `web/src/app/export/*`; PDF is the browser's print path for the HTML copy, so the button says *Open print view*: phones (Android, iOS, installed or not) open it in a new tab with a translated hint, desktops print through a hidden frame ([06](06-test-plan.md) TC-M-20, not yet run) | Done |
| S4-04 | Import a JSON backup on Android and web (validation, preview, merge, as copy) | Android, Web | Shared `ImportPlan` + `BackupValidation`; Android `ImportScreen`, `BackupReader`, `ImportWorker`; server `POST /api/import` with `?dryRun=true` | **Part — the web app has no import.** See 11.3 |
| S4-05 | PWA: manifest, service worker, install item, iOS help, share-target route, `_headers`, storage warnings | Web, DevSecOps | `manifest.webmanifest`, hand-written `public/sw.js`, `PwaService`, `/share` route, `_headers` PWA rules, storage warnings on *Your data* | Done. F-31 (the GitHub Pages header gap) was open at the end of the story and is **Fixed** (by design, live evidence pending) since the owner moved the host on 2026-09-23 — to Firebase Hosting at `https://doorprints.web.app`, after a same-morning Cloudflare Pages plan that was never set up (§11.6). `_headers` is replaced by `web/firebase.json` |
| S4-05a | PWA under the GitHub Pages project path (`/doorprints/`) and on an origin shared with the owner's other project sites. **Since 2026-09-23 the site is served from the root of its own origin, `https://doorprints.web.app` on Firebase Hosting (§11.6)**, so the `/doorprints/` case no longer occurs in production; the base-href-relative code below stays and gives `/` there | Web | `manifest.webmanifest`: relative `"start_url"` and `"scope"` (`"./"`), and **no `"id"` in the source file**: a relative `id` resolves against the *origin* of `start_url` ([MDN](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Manifest/Reference/id)), so `"./"` would have given every project site on `sriram-codes-sw.github.io` one app identity. Instead the **build writes an absolute `id`** (`scripts/sw-precache.mjs` step 0, `stampManifestId`, from the built `<base href>`): `"/doorprints/"` on Pages, `"/"` at the root — the same identity an install made without `id` already had (`start_url` resolved against the manifest URL), so no install changes identity. `PwaService` takes the worker URL and its scope from `document.baseURI`. Cache Storage names scoped by deployment path (`doorprints-shell-v1<base path>`; the `v1` has since become a per-build id, see below), and neither deletes a neighbour's cache: "Remove all data" (`CACHE_NAME_PREFIX` in `local-store.service.ts`) deletes every `doorprints-shell-*` cache, of every deployment path, and no cache without that prefix, and `sw.js` `activate` (`isStaleOwnCache`) deletes only this app's *stale* caches for the same deployment path — both also delete the **legacy unscoped `doorprints-shell-v1`** of builds before S4-05a, which has no path in its name and so may have belonged to a Doorprints deployment at another path on the origin (no current worker reads it); the fetch handler ignores requests outside its base path. `web.yml` `build-pages` asserts the `<base>` tag and fails if `start_url`, `scope` or a shortcut URL points outside `/doorprints/`, or if the `id` is not exactly `/doorprints/` (an absent `id` fails too) | Done in the working tree. Automated: [06](06-test-plan.md) **TC-U-43** (`pwa.service.spec.ts`, `sw-precache.spec.ts`, including *the manifest id (per deployment, written from <base href>)*) and the "Remove all data" half of **TC-S-19** (`local-store.spec.ts`). **Not yet exercised on the live URL**: 06 **TC-M-19** (install, offline start, scope, deep link) and the `activate` half of TC-S-19 (manual; `public/sw.js` has no unit test) |
| S4-06 | Docs: 01, 02, 03, 04, 05, 06, 07, 10 updated for 4a | Docs | After the five review rounds: 01 v0.16, 02 v0.20, 03 v0.11, 04 v0.12, 05 v0.6, 06 v0.17, 07 v0.18, 08 v0.12, 11 v0.8, this log v0.19, README index row, root README (CORS) | Done (this change) |
| S4-07 | Android automatic weekly backup to a chosen folder | Android | `AutoBackupWorker`: weekly, charging + battery-not-low (**no network constraint** — the file is written locally through SAF), into a folder granted once with `OpenDocumentTree` (a persisted grant), keeping the last 4 by default (a 1–8 slider; stored value clamped to 1–20), written under a `partial-` name and renamed when complete; a revoked folder grant turns the feature off with a reason instead of failing every week. **Turning the backup off forgets the folder** (round 5): the persisted grant is released and the stored folder and last error cleared, so turning it on again opens the picker; choosing another folder releases the old grant; a run already writing keeps the grant until it ends. "Save to…" exports likewise keep persisted grants on their documents, bounded to the newest 5 ([02](02-threat-model.md) T-I25, [04](04-data-flow-diagrams.md) D11; tests TC-U-46) | Done (was the stretch item) |
| S4-UX | **Whole-app UX audit: the go-ahead for the first deploy.** The Senior Lead UX Developer audited both clients end to end (Android: 40 items, `android/shared/README.md` 1.24; web: items 0–42, `web/README.md` audit rows), then ran go-ahead gates until each client had no blocker or major left | Android, Web, Design, Docs | Android rounds 1–10 (README 1.24–1.33, handover items 27–34); web audit fixes and rounds 1–3 plus the final round (`web/README.md` change log); the tests TC-U-51..53 and device checks TC-M-22..24 ([06](06-test-plan.md) v0.21); [05](05-ux-accessibility-i18n.md) v0.10 | **Done: approved** — Android at gate round 10, Web at gate round 4, both 2026-09-23 (§11.7). Not yet built in CI; device checks not yet run |

Also landed in this change set, outside the 4a story list: **CodeQL** (`.github/workflows/codeql.yml`, candidate
**C-22**) for `java-kotlin` and `javascript-typescript` in `build-mode: none` — free now that the repository is
public; Android Kotlin is deliberately **not** analysed because Kotlin needs a real Gradle build, and stays covered
by Semgrep. And **GitHub Pages** publishing in `web.yml` (base href `/doorprints/`) — **superseded on 2026-09-23, before anything was ever published, first by a Cloudflare Pages plan and then by Firebase Hosting (§11.6); the rest of this paragraph is the state before those decisions.** The owner had switched
Pages on (Source = “GitHub Actions”), so the first push of this change set to `main` publishes the app at
https://sriram-codes-sw.github.io/doorprints/ — nothing is there before it. After that, a push to `main` republishes
it only when it touches `web/**` or `.github/workflows/web.yml` (the workflow's path filter), or on a manual run;
a docs-only or backend-only push publishes nothing. `deploy-pages` is still `continue-on-error: true`; DevSecOps
removes that once the first push shows a green deploy and the live check [06](06-test-plan.md) TC-M-19 passes
([07](07-secure-build-and-deploy.md) section 1). Later in the same change set, after S4-05a, the Web team's third
review round made `npm run build` stamp the service worker per build (`scripts/sw-precache.mjs`, `postbuild`: a
build id and the full precache list in `sw.js`, one cache per build) and write `_headers`' CSP into the **built**
`index.html` as a `<meta>` — the partial F-31 mitigation in 11.3 row 2. Neither has been built in CI yet.

### 11.2 CI results

**None yet: nothing in this change set has been pushed.** The last commit with CI results is `19006bc` (§9.2), which
is Sprint 3.5. Everything in section 11 is the state of the working tree. The first 4a push triggers Backend (the
new `backend/backup/` package and its tests), Web (Vitest over the new export and data specs, then the Pages build)
and Android (`:shared` `commonTest` gains the whole export suite, `:app` gains `BackupRoundTripTest` and
`ExportMappingTest`), plus Shared iOS compile, because `:shared` changed. Watch points for that first run:

1. the golden-file tests are the most likely first failure, and a real one — they compare bytes;
2. `ExportStringsTest` fails the build if any of the four languages is missing a key;
3. ~~the Pages deploy job runs for real for the first time (Pages is on): check that `deploy-pages` is green, that
   https://sriram-codes-sw.github.io/doorprints/ answers with the `<meta>` CSP in its `index.html`, that the
   service worker's scope is `/doorprints/`, and that the app installs and starts offline there (S4-05a) — the
   whole list is [06](06-test-plan.md) **TC-M-19**; only then does DevSecOps remove `continue-on-error`, which until
   that point still lets the job fail without turning `main` red. One runtime symptom to look for as well: **the
   map must render**. The `<meta>` CSP's `worker-src 'self'` allows `/doorprints/maplibre/maplibre-gl-worker.mjs`
   (same origin), and `web/src/app/shared/map-style.ts` passes `setWorkerUrl` a URL built from `document.baseURI`,
   so it should load; but nobody has seen it run under the project path, and a blank map with a CSP or 404 error
   for the worker in the console would be the sign that it does not;~~ **Replaced on 2026-09-23 (§11.6):** the
   Firebase Hosting deploy (`deploy-firebase`) runs for the first time on the first push of the workflow change to
   `main` (the owner's setup is done); check that it is green and not skipped, that its header check passed, and
   then run [06](06-test-plan.md) **TC-M-19** on `https://doorprints.web.app` — worker scope `/`, install, offline
   start, a deep link, and **the map must render** (worker `/maplibre/maplibre-gl-worker.mjs` under
   `worker-src 'self'`);
4. Shared iOS compile now has to compile the export writers for iOS too, not just the rules;
5. the backend's new `BackupParityTest` reads the Kotlin and TypeScript size-cap constants and the web golden as
   **source text** (by pattern), so a rename in either client fails the Backend workflow with the file and the
   pattern it looked for — a real signal, but one that lands on the backend team; and the Pages build's manifest
   must now carry `"id": "/doorprints/"` (the `build-pages` guard requires `id` to equal the base href
   `/doorprints/` exactly and fails the job otherwise, including when `id` is absent because the `postbuild` stamp
   was dropped). **Since 2026-09-23 there is no Pages build; the production build (base href `/`) must carry
   `"id": "/"`, which TC-M-19 checks on the live manifest.**

### 11.3 Open at the end of 4a

| # | Item | Why it matters | Owner |
|---|---|---|---|
| 1 | **The web app exports but cannot import** (S4-04, FR-047 Part) | This is the wrong half to ship first: a web-only user can make a backup and has no way to restore it. What exists is the **Kotlin** side: `ImportPlan` + `ImportPlanTest` in `:shared`, and the validation constants mirrored (as values only) in `web/src/app/export/backup-export.ts`. What does not exist is any TypeScript import code or spec — no `ImportPlan` port, no file picker, no ZIP reader, no photo-blob writes into IndexedDB, and so no web counterpart to TC-U-42 ([06](06-test-plan.md) §14.2). The plan port is small but it is **not** already done, and it is the correctness-sensitive part | Web, first item of 4b |
| 2 | ~~**F-31: the public GitHub Pages site has no security response headers**~~ — **decided 2026-09-23: the host moved; since later that morning Firebase Hosting at `https://doorprints.web.app`** ([02](02-threat-model.md) F-31 Fixed, RR-11 closed) | It was about to become a live exposure: Pages ignores `_headers`, so the site could be framed and had no `nosniff`, `Permissions-Policy` or COOP, and the origin `https://sriram-codes-sw.github.io` is shared with the owner's other Pages sites, including his repository secure-doc-viewer. The owner chose to move the real host and switch GitHub Pages off, rather than accepting the gap or restricting his other repository: first Cloudflare Pages, then — for its address — Firebase Hosting. Nothing was ever published to GitHub Pages or Cloudflare Pages. What is left is evidence, not a decision: the first green `deploy-firebase` run with its header check, then [06](06-test-plan.md) TC-M-19 (§11.6) | Owner (decided, setup done); DevSecOps + Web (done in the working tree); first deploy on the next push to `main` |
| 3 | The *selected houses* export scope exists in the model and in no UI (FR-046 Part) | "Send my brother these three" needs a workaround today | Android + Web |
| 4 | No PDF golden test; every manual and performance row of [06](06-test-plan.md) §14 is unrun | Determinism and escaping are well automated; *"does it open in Excel, does Tamil shape in the PDF, does it install on an iPhone"* is not tested at all yet | QA / owner device time |
| 5 | NFR-021 export timings never measured | The 3-minute and 60-second targets are design arguments until TC-P-05 runs | Android |
| 6 | Dependency-graph submission notice still unproven (§9.2); ~~the Vertex credit check still open~~ **credit check done 2026-09-23: ₹45 of trial credit used for the Vertex eval runs** (§10.2) | Carried from 3.5 | DevSecOps (graph notice); AI team (record step 10 in [ai/vertex-setup.md](ai/vertex-setup.md), §12.4) |
| 7 | ~~The `data.json` size cap is three different numbers~~ — **fixed in the working tree** | It was 16 MiB in `:shared`, 64 MiB in `docs/schemas/README.md` and in the web mirror, and effectively 8 MiB on the server, so a ~20 MiB web backup would have been refused on Android. Now **16 MiB everywhere**: `BackupFormat.MAX_DATA_JSON_BYTES` in `:shared` and on the server, `BACKUP_LIMITS.maxDataJsonBytes` in `backup-export.ts`, and the server's import body cap `app.limits.max-import-bytes` (default 16 777 216 in `AppProperties`, `application.yml` and `docker-compose.yml`). The backend's new `BackupParityTest` reads all of them — the Kotlin and TypeScript constants as source text — and fails on any drift. Not yet run in CI. [01](01-requirements.md) SEC-041, [02](02-threat-model.md) T-T8 and [07](07-secure-build-and-deploy.md) §7 (`MAX_IMPORT_BYTES`) updated | Backend + Web (done); CI confirmation on the first push |
| 8 | **Should the weekly Android auto backup require an unmetered network?** | It does not today, and that is correct for a plain local SAF write (`AutoBackupWorker` sets charging + battery-not-low only, no `setRequiredNetworkType`). But the folder the user grants may be a cloud provider's (Drive, OneDrive), in which case writing the file triggers that provider's upload on whatever connection is available. Recorded as an **unmade product decision**, not as an implemented constraint — the docs claimed the constraint until [01](01-requirements.md) v0.15 | Android + owner |
| 9 | **Should `docker-compose.yml` allow the web app's public origin by default?** (reworded 2026-09-23: the public origin is now **`https://doorprints.web.app`**, §11.6) | The compose file defaults `APP_CORS_ORIGINS` to `http://localhost:4200` only, so a user who runs the compose server and opens the public site gets a CORS failure on every sync. The docs say to add the origin, with no path: `APP_CORS_ORIGINS=http://localhost:4200,https://doorprints.web.app` ([07](07-secure-build-and-deploy.md) §6.3 and §7, README). Since the address is now fixed (a Firebase project's default site can never be renamed), a default **can** name it, which it could not while the Cloudflare address was unknown; adding it would make the public site work with a compose server out of the box, but would also let the public site call any dev server started with the defaults (it still needs the API key); and the site is HTTPS, so a plain `http://` compose server is limited by the browser anyway ([07](07-secure-build-and-deploy.md) §6.3). **Lead decision**; the docs describe the current default | Lead (Backend owns the file) |
| 10 | ~~**The web half of the format pin depends on the backend workflow**~~ — **fixed in the working tree** | ~~`BackupParityTest` compares the web golden and the web size-cap constant, but `backend.yml` does not run on `web/**`, and `android.yml` does not run on `docs/schemas/**` (so a sample-only change skips `CanonicalSampleTest`, Android handover item 5).~~ DevSecOps added the paths: `backend.yml` now also runs on `web/src/app/export/golden/**`, `web/src/app/export/backup-export.ts`, `android/shared/…/export/Backup.kt`, `docker-compose.yml` and `docs/schemas/**` (each commented with the test that reads it), and `android.yml` on `docs/schemas/**` ([07](07-secure-build-and-deploy.md) §1, [06](06-test-plan.md) TC-I-34, §14.2). Not yet triggered in CI | DevSecOps (done); CI confirmation on the first push |
| 11 | **Web backups drop visits that belong to no house** | Hunt mode records a dwell at a place that is not a house yet with no `houseId`. Android's JSON backup carries those visits since `android/shared/README.md` 1.9 (`ExportBundle.unlinkedVisits`, scope *All houses*, last in `data.json`), but the web writer skips them (`export-model.ts`: `if (visit.deleted \|\| !visit.houseId) continue`), so a web backup of a store that holds such visits (synced, or restored from a phone or server backup) loses them on restore. Android handover item 7 gives the suggested shape; `docs/schemas/README.md` §5 still calls it a known parity gap (handover item 8, Backend) | Web (4b, with S4-04 import) |

### 11.4 What went well, what to change

- **Pinning the format before writing any of the three implementations** (S4-00) was the decision that made the
  parallel work safe. The golden files then made drift a test failure instead of a support question.
- **Writing the exporters as pure code with the clock passed in** is what made byte-comparable tests possible at
  all. Worth repeating for anything that produces a file.
- **Deciding to add no npm dependency** ([03](03-design.md) ADR-19) kept the supply chain flat but means the team
  owns an IndexedDB wrapper, a ZIP writer and an XLSX writer. That is a real cost and it should be re-examined if
  any of the three needs a second round of features.
- **What to change:** S4-04 was written as one story across two platforms and finished on one. Splitting
  "import on Android" from "import on web" would have made the gap visible in the plan instead of at the end.

### 11.5 Handovers received from the Android team (rounds 5 to 12, and the whole-app UX audit)

The Android team's summary (`android/shared/README.md`, handover table) asked the Docs team to carry items 1, 3
and 6 (round 5), 7, 8 and 9 (round 7, `android/shared/README.md` 1.9, 2026-09-22 23:15) and 10 to 13 (rounds 8 and 9,
README 1.10 and 1.11; the Design director and UX lead review is `s4a-state/Android-review-8.json`) and 14 to 18
(rounds 10–12, README 1.12–1.14, recorded on 2026-09-23 after the coordinator found them missing) here. Items 19 to
34 (README 1.15–1.34: the rounds 13–21 of the Design and UX review and the whole-app UX audit) were added on
2026-09-23 after the coordinator's final review found them unapplied; item 34 (README 1.34, written during this
round at the coordinator's request) carries the 1.32 and 1.33 changes. Items 35 to 38 (README 1.36 and 1.37, India's
boundary on the map) were added on 2026-09-24. Where each landed:

| # | Item | Where it is now |
|---|---|---|
| 1 | Turning the weekly backup off forgets its folder (grant released, stored folder and last error cleared, the picker opens again on re-enable; another folder releases the old grant; a running backup keeps the grant until it ends) | S4-07 above; [05](05-ux-accessibility-i18n.md) §14.8; [01](01-requirements.md) FR-048; [04](04-data-flow-diagrams.md) D11 |
| 3 | Persisted Storage Access Framework grants: "Save to…" keeps a persistable read+write (or write-only) grant on the document it creates, the newest 5 (`ExportGrants.KEPT`), released when pushed out or when a run fails or is abandoned; the backup folder's grant; the manifest adds `FOREGROUND_SERVICE_DATA_SYNC` and declares WorkManager's `SystemForegroundService` with `foregroundServiceType="dataSync"` for the long export, import and backup jobs (Android 14+) | Grants: [02](02-threat-model.md) T-I25, [04](04-data-flow-diagrams.md) §6a and D11, test TC-U-46. The foreground-service type is recorded here only: no SSDLC document lists the Android manifest permissions one by one |
| 5 | (To DevSecOps, listed for completeness) add `docs/schemas/**` to `android.yml` | **Done** in the working tree at 23:07 (`android.yml` push and PR lists, comment naming `CanonicalSampleTest`); `android/shared/README.md` 1.9, written at 23:15, still lists it as open. [07](07-secure-build-and-deploy.md) §1 |
| 6 | Ticket S4-00/e: TC-I-34 pinned on the server **and on Android**, the web golden still S4-00/b; the bare `data.json` import | [06](06-test-plan.md) TC-I-34 (with the backend's `BackupParityTest` as the web check); the bare `data.json` import in TC-I-34 and in [11](11-feature-parity-and-export-spec.md) §5.2 |
| 7 | **Web**: web backups drop visits with no house (Android closed the same gap in 1.9); the web importer, when it exists, should show the *checklist scores will be cleared* preview line | §11.3 item 11; [11](11-feature-parity-and-export-spec.md) §5.2; the preview line in [05](05-ux-accessibility-i18n.md) §14.3 |
| 8 | **Backend**: `docs/schemas/README.md` §5 can say Android now writes unlinked visits (scope *All houses*) and keep the gap open for web | Backend's file; recorded here and in §11.3 item 11 |
| 9 | Import preview warning line ("Houses whose checklist scores will be cleared…: N", en/hi/ta/te) and unlinked visits in a JSON backup (and in the export screen's visit count for a backup) | [05](05-ux-accessibility-i18n.md) §14.3 and §14.1; [11](11-feature-parity-and-export-spec.md) §5.2; [06](06-test-plan.md) TC-U-29 and TC-U-42 |
| 10 | docs/05 to 0.8 from the Design and UX review (README 1.10): the import screen's states and sticky bar, *Import as a copy instead* only switching the mode, the preview's number column and groups, the copy hint, all-or-nothing copy imports; §14.1 chips and *Share this file*; §14.2 Android copy verbatim; §14.8 folder shown only while on; the new strings for native-speaker review | [05](05-ux-accessibility-i18n.md) v0.8 §14.1, §14.2, §14.3, §14.8, §14.7 I18N-B06. [03](03-design.md) v0.12 §16.3 (merge row by row, a copy import in one transaction). TC-A-09 (TalkBack announces the switched preview) is recorded as a check in 05 §14.3; TC-A-09 itself is defined in [11](11-feature-parity-and-export-spec.md) §13, not in 06 |
| 11 | (To Web) parity items: *Include rejected* shown for *Shortlisted only*; the future web import's preview | Web's files; recorded here |
| 12 | docs/05 to 0.8, what Android ships (README 1.11; supersedes the parts of item 10 it names): §4 token mapping, §4.3 Android `IndicTypography` line heights, §14.2 the calm amber contact note on both platforms, §14.3 shipped labels, the Replace dialog's three buttons, the copy-mode duplicate warning (live houses only), the three "nothing would change" messages, `import_blocked`; §14.1 *Add a house on the map*; §14.8 *Last backup* hidden while off | [05](05-ux-accessibility-i18n.md) v0.8: new §4.5, §4.1/§4.2 rows, §4.3 Android table, §14.1, §14.2, §14.3 (rewritten), §14.8, I18N-B06 |
| 13 | (To Web) empty-state label parity: "Add a house on the map" in four languages instead of *Map* | Web's files; noted in [05](05-ux-accessibility-i18n.md) §14.1 |
| 14 | docs/05 §14.3 and I18N-B06 from the Design and UX review of 1.11 (README 1.12): the file header, the success sentence ("Added … Updated …", zero parts left out), "Deleted on this phone, stay deleted", **Finish import** after a stopped merge, *Stop the other import*, "Check that the phone has free space"; the bar's *Share* · **Save to…** and stacking at font scale 1.3; the house list's *Restore from a backup*; the new strings | [05](05-ux-accessibility-i18n.md) v0.9 §14.1, §14.3, §14.7 I18N-B06, §14.10; [06](06-test-plan.md) v0.20 TC-M-21 (device checks 6–9) |
| 15 | (To Web) for the web importer: houses deleted on this device, the result sentence, *Finish import*, a status region that stays in the DOM | Web's files; carried into §12 (web import) |
| 16 | docs/05 §14.1, §14.3, §14.6 and I18N-B06 from the UX review of 1.12 (README 1.13): the **opt-in undelete** ("Also bring back *n* houses deleted on this phone", **Bring them back**, *Show as copies* as a text button), the Replace dialog ("Replace *n* houses and *n* visits?", up to five names, *Keep mine, add only what's new*), status lines per preview kind, results shown until seen, the partial-backup note and "partial backup" in the glossary, the shared progress phrasing, the notification ask; **qualified in 1.14**: "with their visits and photos" is held until device check 10b passes; `import_list_middle` added to I18N-B06 | [05](05-ux-accessibility-i18n.md) v0.9 §14.1, §14.3 (the clause marked *pending device check 10b*), §14.6, §14.7; [06](06-test-plan.md) v0.20 TC-U-42, TC-U-50, TC-M-21 (10a, 10b, 11–15); [01](01-requirements.md) v0.19 FR-047 status |
| 17 | (To Web) parity with Android 1.13: (a) the amber partial-backup warning on `/data`; (b) progress phrasing "*done* of *total*" (the web's `data.progressExport` already reads that way); (c) for the web importer, `restoreDeleted`, `skipUpdates` and, since 1.14, the relink rules and fresh photo ids | (a) and (c): §12 (Sprint 4b); (b): recorded as the shared phrasing in [05](05-ux-accessibility-i18n.md) §14.1 |
| 18 | (To Backend, optional) accept an upload to a tombstoned photo id once its house is live again, so a restore could keep the original ids | Backend's choice; §12.4, together with the coordinator's request for `ApiIntegrationTest` cases that pin what the undelete relies on ([06](06-test-plan.md) §14.2) |
| 19 | (To Backend) a device note under `docs/schemas/README.md` §6 rule 6: the Android import that brings a house back over a tombstone that reached the server relinks its visits and re-adds its photos under new ids, so a device import says the photos **come back** | **Done** by the Docs team, which owns `docs/**` (Backend was not in the final round): [schemas/README.md](schemas/README.md) v1.5, device note under §6 rule 6; the web importer reads the same rule (S4b-00a) |
| 20 | *Restore from a backup* renamed **Import a backup** on Android (`import_title`; `houses_restore` deleted; README 1.15) | **Done.** [12](12-brand-and-naming.md) v0.2 G.3 rule 3; [05](05-ux-accessibility-i18n.md) v0.10 §14.10 and I18N-B06; §12.3 row marked done |
| 21 | The house list's first run: a filled **Add a house on the map** (`common_add_on_map`) above an outlined **Import a backup**, the loading and no-match states, *Clear search and filter*, "Houses shown: *x* of *y*" (WCAG 4.1.3), the 540 dp dialog threshold, the new strings | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §14.10, §14.1, §14.3, section 7 row 4.1.3, I18N-B06 |
| 22 | docs/11: Android's equivalent of the web's add mode is an **accessible pick-a-spot mode** (centre crosshair, 48 dp *Save house at centre*), Sprint 4b | **Done.** [11](11-feature-parity-and-export-spec.md) v0.12 §10 and §14.2; [05](05-ux-accessibility-i18n.md) §5 and §14.10. Planned, not pointed: §12.2 |
| 23 | A copy import can be undone for 24 h (*Undo this import*, **Add *n* copies**, "Just imported (*n*)"), the action bar's buttons keep their place, *Save a copy* in the glossary, the undo's states, the Hindi कॉपी | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §14.3 (state rows and *Undoing a copy import*), §5.1 (`StateButton`), §14.6, I18N-B06; [06](06-test-plan.md) v0.21 TC-U-52 |
| 24 | (To Web) the same undo for the web importer, its confirmation away from the result, "Add *n* copies", the "Just imported" filter, stable button nodes, a measured action bar | Web's files; Sprint 4b with the web import (S4b-00a, §12.1) |
| 25 | `secondaryContainer` = `--primary-soft`; selection never the fill alone; the house list's undo as a result card with a **confirmation**; `common_close` instead of `common_dismiss`; the Hindi वापस लें; focus after the dialog | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §4.5, §14.3, I18N-B06; [06](06-test-plan.md) TC-M-22 (device check 19) |
| 26 | Danger confirmations (`DangerButton`), the segmented radio for the checklist and Rent/Buy, the house form's states, 14 new strings; docs/06 TC-A11Y-06 and a case for device check 20 | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §5.1, TC-A11Y-06, I18N-B06; [06](06-test-plan.md) TC-A-13, TC-M-22 |
| 27 | Whole-app UX audit (README 1.24): the 80 new strings, the Indic map-label decision, notifications and location asked in context, the house form's and Compare's new states | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 I18N-B06, §5.1, §14.1; the label decision is the release gate TC-M-23 (d) in [06](06-test-plan.md) §11 |
| 28 | Round 3 (1.26): approximate location as its own state and note, a note's next step inside it, the Map at large text, 3 new strings | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §14.1, §5.1, §7.2, I18N-B06 |
| 29 | Round 4 (1.27): each screen's own reason, the form's no-location text, refusals said once, the Assistant's ask, the short map's one row, new and changed strings | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §14.1, §7.2, I18N-B06 (with the 1.28 corrections: `ai_plan_location_off`; the two refusal snackbars withdrawn) |
| 30 | Round 5 (1.28): no refusal snackbar on the Map, the note read once and scrolled into view, the snackbar beside the row, markers by size, one quote style ‘ ’ | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §14.1, §7.2, I18N-B06 |
| 31 | Round 6 (1.29): WCAG 1.4.1 Android column "marker size, ring and opacity + legend", the reject haptic and snackbar, `map_legend` and the Telugu `map_notifications_off`, device check 21 (d) as a release gate | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 section 7 row 1.4.1, §14.1, I18N-B06; [06](06-test-plan.md) v0.21 §11 and TC-M-23 |
| 32 | Round 7 (1.30): the one-sentence refused-tap snackbar, the legend's measured place and the lifted attribution (WCAG 1.4.1, 2.5.8), `location_off_short` | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §14.1, section 7 rows 1.4.1 and 2.5.8, I18N-B06 |
| 33 | (To Web) a **north-up map on the web** too (WCAG 2.5.1): no rotation or tilt by right-drag, Ctrl-drag, a two-finger twist or Shift+arrow keys | Open at the coordinator's final review; **implemented by the Web team in the final Sprint 4a round** (working tree, 2026-09-23; `web/README.md` change log) in `createMlMap` (`web/src/app/shared/map-style.ts`), which the map, Plan and house-location maps use; review and CI still to come. Recorded in [05](05-ux-accessibility-i18n.md) row 2.5.1, [11](11-feature-parity-and-export-spec.md) §10, [06](06-test-plan.md) TC-M-24 (5) |
| 34 | Rounds 9 and 10 (README 1.32–1.33; row added in 1.34): `settings_status_updating` for I18N-B06; the result card kept in place and dimmed during a new run (`RefreshableResultCard`), the automatic backup's failure as a plain red card; Settings' withdrawn result; the Assistant's error style, kept card and title; the 37 dp attribution clearance; `ServerStatusTest` and device check 21 (x), (y) for docs/06 | **Done.** [05](05-ux-accessibility-i18n.md) v0.10 §5.1 (two result-card rows), §7.2, I18N-B06; [06](06-test-plan.md) v0.21 TC-U-51 (`ServerStatusTest`, `MapRulesTest.theBandAndTheSnackbarKeepClearOfTheAttributionAboveTheLegend`), TC-M-23. The web adopted the same keep-in-place rule in the final round ([05](05-ux-accessibility-i18n.md) §5.1) |
| 35 | (To DevSecOps) India's boundary (README 1.36): run the Android tests when the web copy of the boundary file changes (`web/public/geo/**` in `android.yml`'s path filters) | **Done by DevSecOps** (working tree; README 1.37). Recorded in [07](07-secure-build-and-deploy.md) section 1, [06](06-test-plan.md) TC-S-25 (2) and TC-U-55 |
| 36 | (To Web) India's boundary (README 1.36, extended in 1.37): the same five rules on the web map, and since 1.37 rule 2's adm0 guard `["any", ["has", "adm0_l"], ["has", "adm0_r"]]` in both syntaxes with its spec cases | **Done by Web** (committed, HEAD `3ad2b58`; README 1.39 (b)): `india-boundaries.ts` applies `COUNTRY_LINE_RULE` and `COUNTRY_LINE_RULE_LEGACY` (adm0 side present, not PAK/CHN) and `TILE_ZOOM_GUARD` on `boundary_2`, `boundary_3` and every `boundary` line layer from zoom 5; spec 37 cases. The v0.33 "open parity gap" is removed from [11](11-feature-parity-and-export-spec.md) §10, [03](03-design.md) ADR-22 rule 2, [06](06-test-plan.md) TC-U-54 and TC-M-25, [01](01-requirements.md) FR-098, [02](02-threat-model.md) RR-16, [05](05-ux-accessibility-i18n.md) §7.3 and §12.8 (v0.34 of this log) |
| 37 | India's boundary (README 1.36, extended in 1.37): docs/05 map section, docs/11 parity row, and the device check with loading, offline, cold start, exactly zoom 5.0 and street zoom | **Done.** [05](05-ux-accessibility-i18n.md) v0.15-v0.17 §7.3; [11](11-feature-parity-and-export-spec.md) v0.13-v0.15 D-26 and §10; [06](06-test-plan.md) v0.27-v0.29 TC-M-25 (6) to (10). README 1.38-1.39 additions (the expected degraded state, (vi) as a parity check, the Assam-Arunachal Pradesh state line limit) in v0.17 / v0.29 / v0.15 |
| 38 | (To the lead) India's boundary data and tiles (README 1.37): (a) confirm from the 20260913 decode that every admin-2 land line near India in the zoom 5+ tiles has an adm0 side; (b) BACKLOG, doubled lines at high zoom | (a) Zoom 5 confirmed (README 1.38); zoom 6 to 14 open for the lead. (b) Corrected in README 1.38 and measured in 1.39: Arunachal-Bhutan (0 of 96 samples), Arunachal-Myanmar (0 of 206) and Jammu-Sialkot (0 of 88) are drawn only by the claim outline; the shared stretches in the east are Bhutan's south-east corner and Myanmar south of 26.65 N; separation median 1.5-2.8 km, at most 5.3 km. Backlog **S4b-BL-11** and **S4b-BL-16** (§12.7), [03](03-design.md) ADR-22 Consequences, [06](06-test-plan.md) TC-M-25; the `boundary_3` part became S4b-BL-12. (c) The lead's sign-off on the tile-zoom guard's trade-off (README 1.38): open |

### 11.6 Owner decisions of 2026-09-23: the web app moves to Firebase Hosting

**Decision** (Sriram, 2026-09-23, with the brand advisor): host the web app on **Firebase Hosting** at
**`https://doorprints.web.app`** — no-cost Spark plan, **no billing account**, Firebase project and default site
`doorprints`, separate from the AI project `doorprints-ai` ([03](03-design.md) ADR-21, [12](12-brand-and-naming.md)).
It replaces two earlier plans of the same morning, neither of which was ever deployed:

1. **GitHub Pages** (Sprint 4a, `https://sriram-codes-sw.github.io/doorprints/`), rejected at 07:30 IST for two
   findings of the Sprint 4a review ([02](02-threat-model.md) F-31, RR-11):
   - **GitHub Pages cannot send response headers.** The app would have run with no frame protection
     (`frame-ancestors`, `X-Frame-Options`), no `nosniff`, no `Permissions-Policy` and no COOP; only a build-time
     `<meta>` CSP.
   - **The origin is shared.** Every GitHub Pages site of the account is served from
     `https://sriram-codes-sw.github.io`, and browser storage belongs to the origin, not the path. The owner's
     repository **secure-doc-viewer** also publishes there and he keeps developing it. Checked on 2026-09-23 that
     its Pages site ran **no scripts**: its `gh-pages` branch at commit `68e3a625` (2026-09-21) holds only
     `index.html`, `book.html`, two preview images, `README.txt` and `.nojekyll`, and neither page has a `<script>`
     element — so nothing was exposed. Keeping it that way would have meant a standing rule on the other
     repository, which the owner declined ("he does not want to restrict his other repository because he is
     working on this one").
2. **Cloudflare Pages** (`https://<project>.pages.dev`), chosen at 07:30 IST to fix both, then **rejected by the
   owner** later that morning: a `*.pages.dev` address reads as a development or test site to the IT-literate
   families who use the app. No Cloudflare account, token, secret or deploy ever existed.

The brand advisor compared the free addresses ([12](12-brand-and-naming.md) sections A and E): `doorprints.web.app`
reads as a product ("web app"), is easy to say in Hindi, Tamil and Telugu, and needs no card; Netlify's free plan
pauses every site when its credits run out, Vercel's is non-commercial only, and a custom domain costs money every
year and must wait for web import (per-origin browser storage). Firebase sends the headers of `web/firebase.json`
and gives the app its own origin, so F-31 stays Fixed and RR-11 closed. Zero cost (CON-01).

**Owner setup: done** (reported 2026-09-23 09:40 IST; [07](07-secure-build-and-deploy.md) §6.3, steps 1–9): Firebase
project `doorprints` (the ID was accepted, so the default site is `doorprints`), Hosting opened, the IAM, STS, IAM
Credentials, Resource Manager and Hosting APIs on without a billing prompt, service account
`firebase-hosting-deploy` with **Firebase Hosting Admin** only and no key, Workload Identity pool `github` and
provider `github-web-deploy` whose condition accepts only `web.yml` on `main` of this repository on push or manual
run, the repository secrets `FIREBASE_WIF_PROVIDER` and `FIREBASE_SA_EMAIL` and variables
`FIREBASE_PROJECT_ID=doorprints` and `FIREBASE_SITE_ID=doorprints`, GitHub Pages switched off. Cloudflare leftovers:
never created. Step 10, the first deploy, waits for the workflow change to reach `main`.

| Team | Change (working tree, 2026-09-23; not yet run in CI) | Status |
|---|---|---|
| DevSecOps | `web.yml`: the Cloudflare job is gone before it shipped; new jobs **`firebase-config`** (checks `web/firebase.json`, installs the pinned CLI, on every run), **`firebase-setup`** (no permissions; skips cleanly with a notice while the four values are missing) and **`deploy-firebase`** (*Deploy to Firebase Hosting*: `contents: read` + `id-token: write`, sparse checkout, the `firebase.json` gate before any credential, the root-deployment checks, `firebase-tools` 15.30.2 with `--ignore-scripts`, `google-github-actions/auth` pinned by SHA, `firebase deploy --only hosting:<site>`, the live header check with a 5-minute deadline, a job summary with the rollback path). New `.github/firebase-tools/` (`package.json`, `install.sh`, `check-firebase-json.sh`, `check-live-headers.sh`); Dependabot `npm` entry ([07](07-secure-build-and-deploy.md) §1 and §6.3, [06](06-test-plan.md) TC-S-23, TC-S-24) | Done in the working tree |
| DevSecOps (closed as moot, from the v0.22 list) | (1) pin `cloudflare/wrangler-action` by SHA — the job is gone (for the record: `v4` was v4.0.0 at `ebbaa158`, 2026-05-12; v4.1.1 at `4e888469`, 2026-09-22, was newer); (2) the "repository secrets" wording versus the `cloudflare-pages` environment — gone with the job; (3) the long-lived-rule probe on a missing chunk — the Firebase header check asserts `no-cache` on `/`, the deep link, `sw.js` and the manifest, and that this build's `main-*.js` is served as JavaScript | Closed |
| DevSecOps (open) | `web.yml`'s skip notice names `docs/ops/firebase-hosting-setup.md`: Docs added that page as a pointer to [07](07-secure-build-and-deploy.md) §6.3, so the link works; point the notice straight at 07 §6.3 when next editing. Commit the generated `.github/firebase-tools/package-lock.json` after the first run. Optional: `.github/CODEOWNERS` with `/web/firebase.json` and `/.github/` → DevSecOps | Open |
| Web | New `web/firebase.json` (the headers of the former `_headers`, one to one, plus `Cache-Control: no-cache` on `**`; the manifest `Content-Type`; the `**` → `/index.html` rewrite; `public` `dist/web/browser`; site `doorprints`; no hooks); the build reads the CSP from it (`cspFromFirebaseConfig`) and fails if it is missing, doubled or elsewhere; `public/_headers`, `public/_redirects` and the `build:pages` script deleted; `sw.js` `contentHashed` and a tested `isAcceptable` twin; comments and specs name `https://doorprints.web.app`; the AI helper renamed *Fill in from listing text* (`listingFill.*`); the readable HTML copy is `Doorprints-copy-<date>.html`; `web/README.md` "Deploy" rewritten and a "Deferred to Sprint 4b" list (handovers 11(a), 13, 17(a)) | Done in the working tree |
| Docs | [01](01-requirements.md) v0.19, [02](02-threat-model.md) v0.24, [03](03-design.md) v0.14, [04](04-data-flow-diagrams.md) v0.15, [05](05-ux-accessibility-i18n.md) v0.9, [06](06-test-plan.md) v0.20, [07](07-secure-build-and-deploy.md) v0.22, [08](08-operations-runbook.md) v0.15, [09](09-osi-layer-analysis.md) v0.7, [11](11-feature-parity-and-export-spec.md) v0.11, new [12](12-brand-and-naming.md) v0.1, [schemas](schemas/README.md) v1.4 (section 0 only), `docs/ops/firebase-hosting-setup.md`, the docs index, this log v0.23, README, CHANGELOG | Done (this change) |
| Backend | Nothing in code. `APP_CORS_ORIGINS` on every server that the site should sync with must list **`https://doorprints.web.app`** (origin, no path; [07](07-secure-build-and-deploy.md) §7); whether the compose default lists it is §11.3 item 9 | Owner / operator, per server |
| Owner | Setup steps 1–9 done. Still to do: run or watch the first deploy (step 10), add `https://doorprints.web.app` to `APP_CORS_ORIGINS` on his server, and confirm *Releases to keep* = 10 | Open |

**What to check on the first deploy** (replaces §11.2 watch point 3): `firebase-config` and `firebase-setup` green;
`deploy-firebase` green and not skipped; its *Security headers are served* step ends with "All security headers are
present"; the summary names `https://doorprints.web.app`; then [06](06-test-plan.md) **TC-M-19** on that address
(headers and `no-cache`, worker scope `/`, the map renders, install, offline start, a deep link, manifest `id` `/`,
CORS with the new origin, framing refused, the release in the console, no web-app config under `/__/`). A
`PERMISSION_DENIED` goes to DevSecOps as a red line; nobody adds roles by hand. Until then F-31 is fixed by design
only.

**Follow-ups.** Sprint 5's Google sign-in will need the COOP value revisited: `web/firebase.json` sends
`Cross-Origin-Opener-Policy: same-origin`, which Google documents as breaking the Sign in with Google popup when
FedCM is not used ([11](11-feature-parity-and-export-spec.md) RK-12), and a `web.app` address cannot be verified as
the owner's authorised domain (RK-06). New residual risks: the Spark transfer quota can disable the site
([02](02-threat-model.md) RR-13, [08](08-operations-runbook.md) IR-5), Firebase's reserved `/__/*` paths are outside
our headers (RR-14), and HSTS on `web.app` comes from the `.app` preload (RR-15). Rollback is in the console
([08](08-operations-runbook.md) IR-10). RR-12 (Cloudflare's per-deployment addresses) is withdrawn.

### 11.7 Whole-app UX audit: the go-ahead for the first deploy (sign-off)

**Story S4-UX** (§11.1). Before the first deploy of `https://doorprints.web.app` and the next APK, the Senior Lead UX
Developer audited both clients end to end and then gated them: a gate round verifies every audit item and the last
round's fixes against the code, and approves only with no blocker and no major open. The Design Director reviewed the
visual rounds. In numbers (the design and UX self-check, [05](05-ux-accessibility-i18n.md) §15): 57 Design Director /
UX lead review rounds on Android and Web over 2026-09-22/23, 37 of them sent back, **466 unique UX findings** (2
blockers, 130 majors, 334 minors), first-pass approval 20 of 57 (35 %).

| Client | Gate | Result | What was verified | Where it is written |
|---|---|---|---|---|
| **Android** | UX go-ahead gate, **round 10** (2026-09-23) | **Approved**: 0 blockers, 0 majors, 4 minors | The 40 audit items and round 9's five fixes (the withdrawn Settings result, the dimmed card with full-contrast text, the Assistant's kept error card, the Assistant title under 480 dp, the attribution hidden with the legend); exactly the 11 claimed files changed; strings in four languages and valid XML; the earlier approvals stand for the unchanged screens. Round 6 was sent back (the legend covered MapLibre's logo and attribution, a licence problem) and approved again from round 7 | `android/shared/README.md` 1.24–1.33 (§8 device check 21, §9 items 27–34) |
| **Web** | UX go-ahead gate, **round 4** (2026-09-23) | **Approved** with 3 minors | Audit items 0–42 (go-ahead 2; among them the one web blocker, a dead *Place here* on browsers without WebGL 2, now *Add at my location* and *Type latitude and longitude*), the 16 open items (round 3 gate), and round 3's six items: the phone add hint with `map.addHintShort`, `fitPadding`, the 601–900 px navigation (focus, fade, scroll), the shared `.checkbox` / `.field-error`, no bottom bar on the house pages with the borderless Back and Save's `aria-busy`, `.lang` before `<nav>` with hover rules inside `@media (hover: hover)`; 510 keys in four languages | `web/README.md` change log, the audit rows and rounds 1–3 |

**Sign-off.** The UX go-ahead for the first deploy is **given for both clients**. It is a code-review sign-off: the
Android device checks (TC-M-22, TC-M-23, with **TC-M-23 (d), the Indic map labels, a release gate**) and the web
phone checks (TC-M-24) have not been run, and nothing of Sprint 4a has been built in CI yet (§11.2). The first web
deploy also has the security conditions and the Definition of Done of §12.5 (Decisions 1 and 4).

**Carried minors → Sprint 4b candidates.** Accepted as open at the gates, so they are tracked here instead of being
found again (rule 4 of [05](05-ux-accessibility-i18n.md) §15.5: a minor carried twice becomes a major). Status as
read in the working tree on 2026-09-23 at about 14:30:

| # | Client | Minor (gate that carried it) | Status |
|---|---|---|---|
| A1 | Android | **Save and test reads its result twice**: the result is set before `refreshAiStatus()` while the run is still busy, so TalkBack reads it politely inside the dimmed "Updating…" card and again when the card is re-keyed (a failure can sit dimmed for a full timeout). Set the result and bump the run together after the AI status (r9, r10) | Open, Android |
| A2 | Android | **The automatic backup's error region is created already assertive**, so by `LiveMessage`'s own rule it may not be announced; use an always-composed `LiveMessage(assertive)` (r9, r10) | Open, Android |
| A3 | Android | **`IN_BAND` layout at 200 % in Tamil**: a wide *Save house here* can cover the top of MapLibre's attribution "i" at 4 dp (no column analogue of `rowReachesAttribution`) (r7–r10) | Open, Android |
| A4 | Android | **The `IN_BAND` legend clips** (`softWrap = false`, no ellipsis) when the band is narrower than the legend's measured width: windows of 320 dp or less, or display size Large at 200 % in Tamil (r8–r10) | Open, Android |
| A5 | Android | **No screen heading in the Assistant on windows under 480 dp tall** (the title is left out there to keep the field and *Ask* reachable) (r10, acknowledged) | Open, Android + Design |
| W1 | Web | **"tap" in device-neutral hints**: `map.addHintShort` (shown up to 760 px, so also in narrow desktop windows) and `plan.startHint` / `plan.startRequired` (r4) | **Fixed in the working tree** in the final Sprint 4a round: "choose" in all four languages (`web/README.md`); awaiting review |
| W2 | Web | **Plan's submit focuses the start latitude** when the latitude is typed and the longitude is empty (r3, r4) | **Fixed in the working tree** in the pre-deploy close-out (Web buddy pre-review, `web/README.md`): *Plan route* with an unusable start focuses the first start field still to fix, so with the latitude typed and the longitude empty it focuses the longitude (`pages/plan/start-field.ts`, `startFieldToFix`, with `start-field.spec.ts`); awaiting review. **Round 1 review of the close-out** (`web/README.md`, round 1 row): a coordinate typed while no start is set is kept only while its field is valid. `nextTypedStart` (same file and spec) drops it when its field turns invalid or is cleared, so a set start is never taken from a value the user removed: with a latitude typed, then made invalid, and then a longitude typed, no start is set and *Plan route* focuses the latitude. Setting the start writes each field it fills and clears that field's invalid flag in the same step; awaiting review |
| W3 | Web | **No spoken "Saving"** on the house form (`aria-busy` only; Android's Save says "Saving…") (r4) | **Fixed in the working tree** in the final round: the announcer says `house.saving` when a save starts; awaiting review |
| X1 | Both | **Retry behaviour differed** (coordinator): Android kept the earlier card, dimmed, while a retry ran; the web cleared it first | **Fixed in the working tree** in the final round: the web keeps the card too (`--stale-alpha`, `run-result.ts`; [05](05-ux-accessibility-i18n.md) §5.1), on Ask, Plan and Connect. Sync and the house form still clear their card first: §12.7 S4b-BL-1 and S4b-BL-2 |

Also accepted for release, and written in `android/shared/README.md` device check 21 as known minors (c), (d) and
(e): on a narrow row layout the add-house tip snackbar can cover the Hunt card note's button for its few seconds; a
snackbar beside the row that wraps to three or more lines can rise into the band; while a snackbar sits beside the
landscape row the attribution "i" is hidden with the legend and comes back when it goes. At the pre-deploy close-out the
coordinator rated (e) as licence-credit exposure, not a polish minor: §12.7 S4b-BL-4.

## 12. Sprint 4b: items recorded on 2026-09-23

Sprint 4b's scope was set on 2026-09-22 (section 10.1, [11](11-feature-parity-and-export-spec.md) 14.2). These items
were added or made concrete on 2026-09-23 and are **not committed to a plan yet**; they are recorded here so that
none is lost between teams.

### 12.1 Story S4b-00: what an import is (approved)

The owner approved the import product definition on 2026-09-23 at 08:10 IST, **before** the web import is built.
Requirements: [01](01-requirements.md) §6.9, **FR-089..FR-097**; format side: [schemas/README.md](schemas/README.md)
**section 0**; words: [12](12-brand-and-naming.md) section G.

| Decision | Outcome |
|---|---|
| (a) Only Doorprints backups can be imported | **Yes**: a backup ZIP or a bare `data.json` (`doorprints-backup/1`); readable copies, other apps' files and spreadsheets are refused with a reason |
| (b) The web gets "add as copies" | **Yes**, for parity with Android; all or nothing |
| (c) Import from other apps or spreadsheets | **Later**, as its own separately named feature, not in 4b |

Owners: Docs (done: FR ids, schemas section 0, this record), Backend (schema), Web and Android (UI copy, G.2).

| # | Item | Owner |
|---|---|---|
| S4b-00a | **Web import** of a Full backup (UX-B07, FR-047 Part → FR-089..FR-097): a TypeScript port of `ImportPlan` with a preview-equals-plan test, the ZIP reader with the limits of schemas §7, photo blobs into IndexedDB, merge and "add as copies" (one IndexedDB transaction), and Android's current behaviour — *Import as a copy instead* only switches the mode (handover 11(b)), live-houses-only duplicate count (13), deleted-here houses and a status region that stays in the DOM (15), `restoreDeleted`, `skipUpdates`, the 1.14 relink rules and fresh photo ids (17(c)) | Web |
| S4b-00b | Import-screen copy on both platforms: *Import a backup*, the two sentences of [12](12-brand-and-naming.md) G.2 (FR-097), in four languages, then native-speaker review (I18N-B06) | Web, Android, Design |
| S4b-00c | A test on each client that an import leaves settings, language, server address, API key, Hunt-mode and AI settings untouched (FR-092) | Web, Android |

### 12.2 Web parity with Android on *Your data* (deferred from Sprint 4a)

Recorded as Sprint 4b at the coordinator's request; the Web team lists them in `web/README.md`, "Deferred to Sprint
4b". Items 13 and 17(a) were **implemented in Sprint 4a** during the whole-app UX audit (`web/README.md`, round 2
row; [05](05-ux-accessibility-i18n.md) §14.1), so 11(a) and item 7 remain.

| Android handover | What `/data` should do | Owner |
|---|---|---|
| 11(a) | Show *Include rejected houses* only for *All houses* (`@if (options.scope === 'all')`, stored value kept) | Web |
| ~~13~~ | ~~Empty-state button **Add a house on the map** instead of *Map*~~ **Done in Sprint 4a** (`data.emptyAction`, en/hi/ta/te; the Telugu wording differs from Android's, ఇంటిని vs ఇల్లు, left to the Telugu reviewer in [05](05-ux-accessibility-i18n.md) I18N-B06) | Web |
| ~~17(a)~~ | ~~The amber partial-backup note with *Use everything*, and "Saved a partial backup: …"~~ **Done in Sprint 4a** (`backup-completeness.ts`, `backup-completeness.spec.ts`, TC-U-50) | Web |
| 22 | Android: an accessible **pick-a-spot add mode** (a centre crosshair and a 48 dp *Save house at centre*), the counterpart of the web's add mode ([11](11-feature-parity-and-export-spec.md) §10) | Android, Design |
| 7 / §11.3 item 11 | Web backups carry visits that belong to no house | Web |

### 12.3 Naming and brand follow-ups ([12](12-brand-and-naming.md) section G)

| Item | Owner |
|---|---|
| Android readable copies named `Doorprints-copy-<date>.<ext>` (today `Doorprints-<date>.<ext>`, `ExportFormat.fileName`, pinned by `ExportFormatTest`); the web HTML copy already follows | Android |
| ~~Android `houses_restore` *Restore from a backup* → **Import a backup** (G.3 rule 3)~~ **Done in Sprint 4a** (`android/shared/README.md` 1.15, handover item 20; [12](12-brand-and-naming.md) v0.2) | Android |
| ~~Tamil: the joined spelling காப்புப்பிரதி in the web's `data.formatBackup`~~ **Done** (the web's Tamil strings use it since the "Save a copy" wording change, `web/README.md`) | Web |
| [schemas/README.md](schemas/README.md) §2 still names the readable copy in the ZIP `Doorprints-<date>.html`; the web now writes `Doorprints-copy-<date>.html` | Backend (owner of that file) |
| **Custom domain: not now.** Only after the web import ships, bought before any public launch, moved in one announced release ([12](12-brand-and-naming.md) section D) | Owner |
| Before a Play Store listing or public launch: IP India trademark search for DOORPRINTS / DOOR PRINTS in classes 9, 42 and 36 ([12](12-brand-and-naming.md) section C) | Owner |

### 12.4 Handovers to other teams (Docs does not edit these files)

| To | What | Where |
|---|---|---|
| **AI team** | Record **vertex-setup step 10 as done**: the owner's credit check of 2026-09-23 found **₹45 of trial credit** used for the Vertex eval runs (the cost came off the credit; nothing was charged). Add the one line from the Firebase plan: the web-hosting Firebase project `doorprints` is separate and must never be linked to the trial billing account. | [ai/vertex-setup.md](ai/vertex-setup.md) status table and step 10 |
| **Backend** | Three `ApiIntegrationTest` cases that pin what Android 1.14's undelete relies on: (1) `PUT /houses/{id}` with `deleted=false` and a newer `updatedAt` over a purged tombstone brings the house back live; (2) a `PUT` of a visit unlinked by the purge, with `houseId` set and a newer `updatedAt`, relinks it; (3) a photo upload under a fresh id to the restored house is stored, while one under the tombstoned old id is silently ignored. Optional: Android handover 18. Also schemas §2's readable-copy name (12.3) | `backend/src/test/java/app/doorprints/server/ApiIntegrationTest.java`; [06](06-test-plan.md) §14.2 |
| **DevSecOps** | Point `web.yml`'s skip notice at [07](07-secure-build-and-deploy.md) §6.3 (the `docs/ops/` page is a pointer); commit the firebase-tools lock file after the first run; decide on `.github/CODEOWNERS` | `.github/workflows/web.yml`, `.github/firebase-tools/` |
| **Android** | Device checks 10a and 10b ([06](06-test-plan.md) TC-M-21), 16–20 (TC-M-22) and 21 (TC-M-23; **(d) is a release gate**); the readable-copy file names of 12.3; the carried minors A1–A5 of §11.7 | device time; `android/` |
| ~~**Backend**~~ | ~~Android handover 19: the device note under `docs/schemas/README.md` §6 rule 6 (§11.5)~~ **Done by Docs** (schemas README v1.5); Backend only needs to read it | `docs/schemas/README.md` |
| **Web** | The phone checks TC-M-24. ~~The carried minor W2 of §11.7~~ **done by Web** in the pre-deploy close-out (`start-field.ts`), awaiting review | device time; `web/` |

### 12.5 Owner decisions of 2026-09-23: security split, release guard, review efficiency, process improvements, the first release's Definition of Done and CI on every branch

The owner approved the Sprint 4a round that included the web icon fix, and on the same day decided the following,
after two self-check playbooks written from the Sprint 4a reviews were put to him: the **design and UX self-check**
([05](05-ux-accessibility-i18n.md) §15; 466 UX findings in 24 rule families) and the **security self-check**
([07](07-secure-build-and-deploy.md) Appendix A; 188 security findings in 15 families).

**Decision 1: the security split, the release security gate and the guard rule.** Proposed by the lead at 17:31 IST
in answer to the owner's per-release cybersecurity testing checklist, approved by the owner at 17:53 IST.

- **Split.** Security work has two parts. Each change carries the **Security Definition of Ready** and a **buddy
  pre-check** (07 A.1, A.4). The **release security gate** runs on a release candidate, not on each change.
- **The release security gate** has three parts:
  1. **Automated gate** (DevSecOps, story S4b-SEC-1). The checks CI already runs — Semgrep (TC-S-01), CodeQL, gitleaks
     (TC-S-03), Trivy and `npm audit` (TC-S-02), Dependabot, and the live header check (TC-S-23) — plus: a **MobSF
     static scan** of the release APK (TC-S-06); an **OWASP ZAP API scan against the backend started in CI** (the API
     half of TC-S-04); **authorisation tests** (today TC-S-08 and TC-S-10, the API key and path-bypass cases;
     per-user access control once accounts arrive in Sprint 5); **`testssl.sh`** for any self-hosted server (TC-S-09); an **SBOM licence scan** that every dependency
     is compatible with `AGPL-3.0-only` (07 A.5 item 5, §12.6); and the **LLM prompt-injection set** (TC-AI-04).
  2. **Manual per-release list, about one hour** (Docs, story S4b-SEC-3, `docs/13-release-security-checklist.md`):
     OWASP WSTG and MASTG "lite" items, the client **storage audit**, and a **MobSF dynamic** run.
  3. **Deep self-run pentest**, twice: **before the Play Store launch** and **before Sprint 5's sign-in** ships.

  The proposal also adds the OWASP Top 10 for LLM applications and the DPDP Act / Play data-safety answers to the
  gate's scope; S4b-SEC-3 says where each is checked.

  The proposal listed as not applicable yet: root detection, biometrics, MFA, roles and password hashing (no accounts
  until Sprint 5), CSRF protection (the API authenticates with a header, not a cookie) and obfuscation to protect the
  code (it is public). They come back into scope when that changes.
- **Guard rule.** **No Play Store release and no public server until the release security gate exists and passes.**
  **Once the gate exists, every web deploy passes it too.** "Exists" means: the automated part runs in CI (S4b-SEC-1),
  the manual list is published (S4b-SEC-3), and [06](06-test-plan.md) §11 names each check with its owner and
  threshold. "Passes" means on the release candidate ([06](06-test-plan.md) §11).
- **The first web deploy** (`https://doorprints.web.app`, a static site with no server of ours) is the one deploy
  before the gate exists. It goes ahead with the **existing CI** and three more checks: an **OWASP ZAP baseline**
  against the live site, the **header check** (`deploy-firebase`'s automated check and TC-M-19 by hand), and a
  **storage audit**. The storage audit checks what the site keeps in IndexedDB, Cache Storage, `localStorage` and
  `sessionStorage`, and that "Remove all data" leaves none of it. **Timing and rollback:** the checks run right after
  the first Firebase deploy, **before the address is announced or shared**; on any finding the owner **rolls back
  through Firebase Hosting's release history** ([07](07-secure-build-and-deploy.md) §6.3). The entry and exit are in
  [06](06-test-plan.md) §11 *First web deploy*. The storage audit's exact steps are in `web/README.md`, *Storage audit on
  the live site* (Web team, pre-deploy close-out, 2026-09-23).

**Decision 2: six efficiency measures and the security playbook.** In Sprint 4a, 65 % of Design Director / UX lead
rounds and 34 of 130 manager rounds sent the work back, mostly for the same families of problems. The lead proposed six
measures (decision log, 18:55 IST entry), and the owner's go-ahead for the final Sprint 4a round (launched 19:45 IST) put them in place as
proposed: **measures 4 (review rules) and 5 (buddy pre-check) went into the workflow scripts at once and have been in
use since the final Sprint 4a round**; measures 1–3 (screenshot tests, design-first spec, component kit) are the first
Sprint 4b stories; measure 6 (the first-pass approval rate) is recorded for each team from the final Sprint 4a round
on, with the target **35 % to more than 70 % by the end of Sprint 4b**. S4b-EFF-4 and S4b-EFF-5 below make
measures 4 and 5 permanent (the docs and the reviewers' standing instructions), not start them. The stories have no points yet. The owners in the table are
proposals for Sprint 4b planning.

| ID | Story | Done when | Proposed owner |
|---|---|---|---|
| S4b-EFF-1 | **Screenshot tests.** The screens reviewers check by eye are captured by tests and compared with approved images: ta and te at 200 %, 320–360 wide, portrait and landscape, light and dark. The tooling must be free. The tests run **inside the existing `android.yml` and `web.yml`, with no new workflow and the CI runtime kept flat** (Decision 3). | The Map, house form, list, Import and Export (Android) and the map, house page and *Your data* (web) have screenshot tests in CI, and a review can cite an image instead of a device run | Android, Web |
| S4b-EFF-2 | **Design-first spec.** Before code, a one-page screen spec: its five states (loading, empty, no match, error, offline), copy keys in four languages, tokens, the focus and announcement plan, and the parity note. The Design Director and the UX lead approve it. | Every Sprint 4b UI story starts from an approved spec | Design, Docs |
| S4b-EFF-3 | **Component kit.** One component per role on each platform for the patterns the reviews kept correcting: the amber note, the result card kept during a retry, the state button, the danger confirmation, the segmented radio, the empty state. Android already has `WarnNote`, `ResultCard`, `StateButton` and `DangerButton` ([05](05-ux-accessibility-i18n.md) §5.1); the web has the equivalents to collect. | Each pattern has one implementation per platform, and docs/05 §5 names it | Android, Web, Design |
| S4b-EFF-4 | **Review rules** (measure 4; **in use since the final Sprint 4a round**, this story makes them permanent). (1) **Round 1 reviews everything in scope in one complete pass**; no finding is held back for a later round. (2) **Later rounds review only the delta** since the reviewer's last review **plus the regressions it could cause**, and raise **no new finding on unchanged code unless it is a blocker**. (3) **Severity rubric**: *blocker*, data loss, a security hole, a crash or a flow that cannot be completed; *major*, a user is misled, some users are blocked (accessibility, i18n) or a documented rule is broken; *minor*, polish. A gate approves only with no blocker and no major. (4) **Out-of-scope findings** are raised as minors whose text starts `BACKLOG:`; they become backlog ticket candidates and **never block approval**. (5) A **new class of problem** is flagged `NEW RULE:` in the feedback and added to the playbook ([05](05-ux-accessibility-i18n.md) §15.3, [07](07-secure-build-and-deploy.md) A.3). The playbooks' own review rules still apply ([05](05-ux-accessibility-i18n.md) §15.5, [07](07-secure-build-and-deploy.md) A.5): no self-check, no review; review against the self-check first; carried minors are tracked, and a minor carried twice becomes a major; release gates are referenced, not run per change. | Rules (1)–(5) are stated in [05](05-ux-accessibility-i18n.md) §15.5 and [07](07-secure-build-and-deploy.md) A.5 and in every reviewer's standing instructions; every hand-off carries both filled checklists; review results carry a severity from the rubric and a `rule` field; each round's `BACKLOG:` items are listed as ticket candidates | Reviewers, delivery coordinator |
| S4b-EFF-5 | **Buddy pre-check** (measure 5; **in use since the final Sprint 4a round**, this story makes it permanent). An automated, read-only reviewer runs before the manager or reviewer sees the diff. It checks scope, maps paths to rule families, runs the grep packs on added lines only, checks claims and cross-module constants, and verifies the ticks ([07](07-secure-build-and-deploy.md) A.4; the design grep pack in [05](05-ux-accessibility-i18n.md) §15.4). | Its block/warn list is attached to every hand-off | DevSecOps, delivery coordinator |
| S4b-EFF-6 | **First-pass approval metric** (measure 6). Track the share of rounds approved on first pass (design and UX baseline 20 of 57, 35 %) and the `self-check-miss` count per family, and re-rank the families each sprint. | The numbers are in this log at each sprint's end; **goal (approved): first-pass approval from 35 % to more than 70 % by the end of Sprint 4b**. The security playbook's own goal, halving S1 and S2 findings within two sprints ([07](07-secure-build-and-deploy.md) A.5 item 7), is tracked alongside | Delivery coordinator |
| S4b-SEC-1 | **The automated release security gate** (Decision 1, part 1). Keep the existing checks (Semgrep, CodeQL, gitleaks, Trivy, `npm audit`, Dependabot, the live header check) and add: a MobSF static scan of the release APK, an OWASP ZAP **API scan against the backend started in CI**, authorisation tests, `testssl.sh` for any self-hosted server, an SBOM licence scan for `AGPL-3.0-only` compatibility, and the LLM prompt-injection set. Each check has an owner and an exit threshold. New checks go **inside the existing workflows (`security.yml`, `backend.yml`, `android.yml`), with no new workflow and the CI runtime kept flat** (Decision 3). With S4b-SEC-3, this is the gate the guard rule waits for. | [06](06-test-plan.md) §11 names each check with its tool, owner and threshold, and a release candidate can be run through it | DevSecOps, with Android, Web, Backend, AI |
| S4b-SEC-2 | **Security playbook in use.** Every hand-off to a manager carries the Security Definition of Ready ([07](07-secure-build-and-deploy.md) A.1) with one line of evidence per tick. The families are recounted each sprint. The "move checks into tooling" candidates (`zizmor`/`actionlint`, Semgrep rules for `File(dir, "$id")` and `innerHTML`, `continue-on-error` without `# exit:`, provider calls without the redactor) are planned, and each one that moves goes **inside an existing workflow, with no new workflow and the CI runtime kept flat** (Decision 3). | The playbook's families are re-ranked at the end of Sprint 4b | Application security lead, managers |
| S4b-SEC-3 | **The manual release security checklist** (Decision 1, parts 2 and 3). Write `docs/13-release-security-checklist.md`: a list of about one hour per release (OWASP WSTG and MASTG "lite" items, the client storage audit, a MobSF dynamic run, with the OWASP Top 10 for LLM applications and the DPDP Act / Play data-safety answers in scope), each item with its tool, how to run it, the pass criterion and where the result is recorded; and the scope of the **deep self-run pentest** before the Play Store launch and before Sprint 5 sign-in. | The document exists, [06](06-test-plan.md) §11 points to it, and one release candidate has been taken through it | Docs, with DevSecOps and the application security lead |

**Decision 3: seven process improvements (owner, 19:55 IST).** The lead proposed seven more improvements after the
first six. The owner approved **1, 2, 4, 5, 6 and 7** and put **3 on hold**:

| # | Improvement | Decision |
|---|---|---|
| 1 | A draft pull request for each round, so real CI results come back while the next round works | Approved. **Draft-PR CI runs in the background and never blocks a round** |
| 2 | Playbook rules turned into linters, Semgrep rules and an i18n key test (the "move checks into tooling" items of [05](05-ux-accessibility-i18n.md) §15.5 rule 5 and [07](07-secure-build-and-deploy.md) A.5 item 6) | Approved, with the constraint below |
| 3 | Smaller batches | **On hold** |
| 4 | Docs in the same change as the code they describe | Approved |
| 5 | A memory file per team | Approved |
| 6 | Sprint retrospective metrics (with S4b-EFF-6) | Approved |
| 7 | Budget-aware runs and an automatic save-state | Approved |

**Constraint (owner):** too many build checks slow development down, so teams follow the playbooks well enough that CI
failures are close to zero, and **new lint, Semgrep and i18n checks go inside the existing workflows: no new workflow
file, and the CI runtime stays flat.** S4b-EFF-1, S4b-SEC-1 and S4b-SEC-2 carry this constraint.

**Decision 4: the first release's Definition of Done (owner, 20:03 IST).** The owner will deploy only when everything
is properly done. The first release, the first deploy of `https://doorprints.web.app`, is done when:

1. **Every review gate is approved**: each team's manager, the Design Director, the UX lead and the delivery
   coordinator.
2. **Every workflow that runs on the merge commit is green**: `backend.yml`, `web.yml`, `android.yml`, `security.yml`
   and `shared-ios.yml` (the "five workflows" of the proposed Definition of Done; the change set touches `android/shared/**`, so the iOS
   compile runs), and `codeql.yml`, which runs on every push to `main` (since Decision 5 on every branch push too). `ai-evals.yml` is manual and not part of
   it while AI stays off by default.
3. **The first-deploy security checks pass** (Decision 1): the ZAP baseline, the header check and the storage audit,
   before the address is announced, with rollback through Firebase release history on any finding.
4. **The web icons are redrawn** from the Android launcher mark: done in the final Sprint 4a round (`web/README.md`,
   *Installable (PWA)*; [12](12-brand-and-naming.md) N-06).
5. **Hindi, Tamil and Telugu ship marked "under review"**: they are machine-drafted, and the native-speaker review
   is still to come. This is recorded in [05](05-ux-accessibility-i18n.md) (I18N-B03, I18N-B06, and the status note in
   §9.3) and on the README's *Four languages* row. The owner confirmed it at the pre-deploy close-out ("Under Review
   it is for now"): the status stays until the native-speaker review is done. The decision does not say whether the apps themselves show the status; nothing in the apps
   marks it now, and an in-app label would be a Web and Android change.

The entry and exit criteria are in [06](06-test-plan.md) §11 *First web deploy*.

**Decision 5: the pipelines run on every branch (owner, 2026-09-23).** In the owner's words: "We need to have the
pipelines run on branches as well because we need to be sure that the code is right before merging into main." The
lead turned it into this specification, which DevSecOps implements in `.github/workflows` ([07](07-secure-build-and-deploy.md)
§1 *Branch runs*):

1. `web.yml`, `backend.yml`, `android.yml`, `shared-ios.yml`, `security.yml` and `codeql.yml` run on **push to every
   branch** (`branches: ['**']`) with their path filters unchanged. `pull_request` to `main` stays (the merge result,
   and the only way fork PRs run); `codeql.yml` still does not run on `pull_request`. No `pull_request_target`.
2. **Deploy, signing and writes stay on `main`**: `web.yml` `firebase-setup` / `deploy-firebase` and `security.yml`
   `gradle-dependency-graph` keep their `main` guard, and `android.yml` `release-signing-check` / `release` now require
   `refs/heads/main` as well (before, a manual run on any branch could sign; as built, the guard sits on
   `release-signing-check` and `release` runs only on its output). `backend.yml` `image` (`docker build` and
   the non-root check, never pushed) now also runs on branch pushes, on purpose. **These guards hold only for an
   unmodified workflow**: a push runs the workflow file from the pushed branch, so someone with write access who edits
   `android.yml` on a branch runs branch code with the `HH_*` signing secrets, which are repository secrets today
   ([02](02-threat-model.md) T-E4). The control for the key is the `release` environment with a deployment-branch rule of
   `main` only (§12.7 S4b-BL-8, not done yet). The Firebase deploy is different: item 6 does not depend on the
   workflow file.
3. **Concurrency**: `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}` in every workflow, `codeql.yml`
   included, so a newer push to a branch or PR cancels the older run; an in-progress run on `main` is never
   cancelled, and if several pushes queue up, only the newest waiting run starts (GitHub replaces a pending run in the
   same group with the newer one). The deploy job's own group is unchanged.
4. The Gradle cache stays read-only off `main`.
5. With a PR open, one push gives **two runs per workflow** (the branch push and the `pull_request` run): accepted.
   **Required status checks stay off** ([07](07-secure-build-and-deploy.md) §3); a branch's runs are the pre-merge signal.
6. The Firebase Workload Identity condition is unchanged and still refuses any ref but `main`, so a branch cannot
   deploy even if a job asked for a token.
7. **Runtime.** Decision 5 is a later owner decision and knowingly **increases the number of runs** (every branch
   push, and two per push while a PR is open, including the macOS minutes of `shared-ios.yml`), against Decision 3's
   constraint that "the CI runtime stays flat". The per-run runtime and the no-new-workflow rule are unchanged, and
   Actions minutes are free for a public repository ([01](01-requirements.md) CON-04).

**Status: not yet run in CI.** The first push to a branch after the workflow change is the check (every triggered
workflow runs on the branch ref; the five `main`-only jobs `firebase-setup`, `deploy-firebase`,
`release-signing-check`, `release` and `gradle-dependency-graph` are skipped there).
Threat model: T-E4 and T-I21 ([02](02-threat-model.md)); the risk scores do not change.

### 12.6 Next item: the licence change (approved, separate follow-up)

**Decision (owner, 2026-09-23): the licence moves from MIT to `AGPL-3.0-only`, with a trademark notice for the name
Doorprints.** The decision is approved. Its implementation is a **separate follow-up and the next item** after this
round. **`LICENSE` is not changed now.** The repository stays MIT-licensed until that change lands, and the README's licence lines stay correct until
then (the MIT mentions in older change-log rows of [02](02-threat-model.md) and the docs index are history).

The follow-up covers these parts; confirm the scope when the item is planned:
- the `LICENSE` text;
- the licence lines in `README.md` (the line under the title, the docs table and *Contributing*);
- the `license` fields of the build files, where they are set;
- a trademark notice for "Doorprints" (with [12](12-brand-and-naming.md) section C and the IP India search of §12.3);
- a check that every dependency's licence is compatible with `AGPL-3.0-only` (the SBOM licence scan of S4b-SEC-1);
- a CHANGELOG entry.

Owner: the owner decides; Docs, DevSecOps and each module team make the changes.

### 12.7 Pre-deploy close-out (2026-09-23): what is left, backlog tickets and rule candidates

From the delivery coordinator's final cross-team review of the Sprint 4a working tree (second pass, 2026-09-23). The
status is as read in the working tree at the close-out, updated in v0.28 from the Web team's later buddy pre-review row
in `web/README.md` (*Pre-deploy close-out, web buddy pre-review*), and in v0.29 from its round 1, 2 and 3 review
rows (*Pre-deploy close-out, round 1/2/3 review fixes*; the last one applied is the round 3 row, README update rule 11).
Duplicates are merged: the same `sync.service.ts` item was raised three times and is one ticket.

**Still open for the first release's Definition of Done** (Decision 4 above; [06](06-test-plan.md) §11 *First web deploy*):

- **Item 2, CI.** CI passed on `0e4e22a`. The working tree holds about 113 changed files and many untracked ones that
  no CI run has built or tested (§11.7: nothing of Sprint 4a has been built in CI yet). Every tick in this log for
  Sprint 4a is a code-read claim until the workflows of Decision 4 item 2 pass on the merge commit.
- **Item 3, the first-deploy security checks**: the ZAP baseline and the header check run after the deploy. The
  storage audit's steps are in `web/README.md`, *Storage audit on the live site* (Decision 1). Web corrected its
  expected results in the buddy pre-review: local storage holds `hh.mapView` (the starting view) from the first time
  the map page shows, so that key after the first start is expected, not a finding. "Remove all data" now also
  removes every `hh.*` and `doorprints.*` key from this tab's sessionStorage ([06](06-test-plan.md) TC-S-19).
- **Device and phone checks**: TC-M-22, TC-M-23 (**(d), the Indic map labels, is a release gate**) and TC-M-24 (§12.4).
- **Web, before the first deploy** (the coordinator's recommendation, the Web team's own request): the success
  callbacks of the map's *Add at my location* (`map-page.ts`, `addAtMyLocation()`) and Plan's *Use my location*
  (`plan-page.ts`, `useMyLocation()`) do not check that the page still exists. A user who leaves while the permission
  prompt or the 15 s fix is pending is taken to a new-house form they did not ask for. The fix is the guard
  `house-detail-page.ts` already has (`destroyed` set first in `ngOnDestroy`, `if (this.destroyed) return;` at the top
  of each callback). **Done by Web in the working tree in the close-out round, awaiting review**: one tested helper,
  `shared/locate-once.ts` (`locate-once.spec.ts`, [06](06-test-plan.md) TC-U-53), drops a position or a failure that
  arrives after the page was left, on the map, Plan and the house form (`web/README.md`, close-out row).

**Backlog tickets (Sprint 4b candidates).** This table is the one register of backlog ids. A team README that needs a
new ticket takes the next free number from it and says in its Docs handover that the id is new (candidate (k) below).
S4b-BL-6 and S4b-BL-7 were added in v0.29 that way, from the Web team's round 2 and round 3 rows; S4b-BL-8 in v0.31, from the Docs pre-review of Decision 5; S4b-BL-9 and S4b-BL-10 in v0.32, from the owner's boundary issue (§12.8); S4b-BL-11 and S4b-BL-12 in v0.33, from the round 1 review of that Docs change; S4b-BL-13 to S4b-BL-16 in v0.34, from the coordinator's comment and docs sync of that change (S4b-BL-15 and S4b-BL-16 proposed in `android/shared/README.md` 1.39); S4b-BL-17 in v0.37, from the round 2 design review of PR #16 (§12.10);
S4b-BL-18 to S4b-BL-21 in v0.46, from the reviews of PR #19 (CMP-2 and the ADR-24 rename, §13.4 and §14);
S4b-BL-22 in v0.47, from CMP-3 (§13.5).

| # | Client | Ticket | Fix | Owner |
|---|---|---|---|---|
| S4b-BL-1 | Web | **`sync.service.ts` `lastError`** (raised three times). `runOnce()` sets `lastError` to null at the start of every run, so the forced failure card and the first-run banner disappear during *Sync now* or *Try again* and the content below jumps. The polite "Automatic sync failed" note is removed and read again on every failed background pass (every 30 min, and 3 s after an edit). `failureRun` also goes up on runs the user did not ask for | Clear `lastError` only on success. Bump `failureRun` only when the run is forced or the message changed. Render the card in `.refresh-slot.stale` while `sync.running()`, with a "Syncing…" refresh bar | Web |
| S4b-BL-2 | Web | **House page retry cards** (raised twice). `persist()`, `useMyLocation()`, the listing fill and the address lookup clear their card before the run, so the form jumps on a retry | Keep the `RunResult` card, dimmed with `.refresh-slot.stale`, while saving, filling, geocoding or locating runs, and replace it when the run ends. Docs then records the scope in [05](05-ux-accessibility-i18n.md) §5 and §5.1 | Web, then Docs |
| ~~S4b-BL-3~~ | Web | ~~**Favicon at 16 px.** Chromium may scale the 192 px PNG (the version with toes) down to 16 px instead of using the simplified SVG~~ **Done by Web in the close-out round, awaiting review**: `icons/favicon-32.png`, rendered from `favicon.svg`, is listed first in `index.html` with `sizes="32x32"` and precached (`sw-precache.spec.ts`) | Add a 32×32 PNG rendered from `favicon.svg`, listed with `sizes="32x32"` | Web |
| S4b-BL-4 | Android | **Map attribution hidden behind snackbars: licence-credit exposure.** `MapScreen.kt` sets `isAttributionEnabled = !snackbarAtStart`, so the OpenStreetMap / OpenFreeMap credit is hidden while a snackbar shows. With TalkBack's longer timeouts that can be 10 s or more. This was accepted for release as device check 21 known minor (e) (§11.7). The coordinator now rates it as licence-credit exposure, so it is no longer a polish minor | Move the attribution "i" above the snackbar instead of hiding it (R5 in [05](05-ux-accessibility-i18n.md) §15.3: nothing covers or hides the attribution) | Android, before any public Android release |
| ~~S4b-BL-5~~ | Web | ~~**Map's *Download now* from the empty list** (`downloadHere()`, `map-page.ts`) announces "*n* houses downloaded" and moves focus when the download ends, even if the page was left in the meantime. Nothing is navigated or filled: the announcement is heard on another page, and the focus calls find no element. Found by Web while applying candidate rule (f); a new minor~~ **Done by Web in the buddy pre-review, awaiting review**: `downloadHere()` returns after `await done`, and again after counting the houses, when the page is gone | Return early from the part after the download when the page is gone (the same `destroyed` flag) | Web |
| S4b-BL-6 | Web | **No visible invalid state** (Web round 2 design review; new id, `web/README.md` round 2 and round 3 rows). Fields marked `aria-invalid="true"` (Plan's start fields, the house name and coordinates, the Ask and Plan requests, Connect) look the same as valid ones; only the message under them shows the error | One rule in `styles.css` for `input` and `textarea` with `[aria-invalid="true"]`: a border in `--error-text`, at 3:1 or more against the page in both themes (WCAG 1.4.11), checked with the [05](05-ux-accessibility-i18n.md) §4 contrast table | Web |
| S4b-BL-7 | Web | **No TestBed spec for the Plan page or the new-house start** (Web round 2 and round 3 reviews; new id). The typed-start and start-message rules are tested through `start-field.spec.ts` and `map-center.spec.ts`, but the Plan page's wiring (`onCoord`, `setStart`, `fieldInvalid`, `coordsDescribedBy`) and the house form's two rule (f) guards in `startWithoutPosition()` are checked by reading only | A `plan-page.spec.ts` with TestBed: a typed start, a field made invalid then cleared, a location failure withdrawn by a map click, and the `aria-describedby` and `aria-invalid` attributes. A `house-detail-page.spec.ts` with TestBed and the two `startWithoutPosition()` guard cases: `/houses/new` with no position and the page destroyed before `houses()` resolves, and again before it rejects; in both, no draft is opened, *Draft restored* is not announced and no document title is set | Web |
| S4b-BL-8 | CI | **Signing secrets reachable from a branch** (Docs pre-review of Decision 5; new id). The `HH_*` keystore secrets are repository secrets, and the `refs/heads/main` guard on `android.yml` `release-signing-check` sits in each branch's own copy of the workflow, so a branch that edits `android.yml` runs with the key on its next push ([02](02-threat-model.md) T-E4; [07](07-secure-build-and-deploy.md) §4, §5). Key loss or leak means no in-place updates. More pertinent since branch runs | Owner: create the `release` environment with a deployment-branch rule of `main` only and move the four `HH_*` secrets into it (then delete the repository secrets). DevSecOps: add `environment: release` to **both** `release-signing-check` and `release` in `android.yml` (both read the secrets; without it on the check job, the check sees none and the release is skipped). Docs then marks 07 §3, §4 and §5 as done | DevSecOps, with the owner |
| S4b-BL-9 | Both | **Re-check India's boundaries after each OpenFreeMap planet or style update** (owner issue P0 of 2026-09-24, §12.8; new id). The rules name Liberty's layers (`boundary_disputed`, `boundary_2`, `label_state`) and rely on what the tiles carry (disputed lines flagged, `adm0_l`/`adm0_r` from zoom 5, the two state names); a renamed layer only gives a warning, and a new disputed layer or a new name would show on the map | Run [06](06-test-plan.md) TC-M-25 after each update OpenFreeMap announces and before each release; if a warning appears or a line shows, update both apps' rules together (`india-boundaries.ts`, `IndiaViewRules.kt`) and their tests. Later: a screenshot test ([06](06-test-plan.md) §10). Since branch `fix/india-boundary-lines` (§12.10), also re-run `web/scripts/geo/find_shared_stretches.py` on the new planet (on a build made with `build_in_boundaries.py --no-shared`), paste its output into `SHARED` in `build_in_boundaries.py`, rebuild both copies of `in-boundaries.geojson` and update the sha256 in `IndiaBoundaryDataTest`: a moved or newly disputed tile line would otherwise leave a gap or a doubled line | Lead, with Web and Android |
| S4b-BL-10 | Both | **Consider a Survey of India-derived outline** (§12.8; new id). The claim outline is Natural Earth 1:10m, a median of about 1.55 km (Jammu-Sialkot) and 1.6 km (McMahon line) off the true line, 3.9 km at the 90th percentile (Web team's tile decode; "about a kilometre" until v0.33) ([02](02-threat-model.md) RR-16) | Only if its licence clearly allows redistribution in an open-source app and its web build at zero cost; then rebuild `in-boundaries.geojson` with `web/scripts/geo/build_in_boundaries.py` (or a successor), update the sha256 in `IndiaBoundaryDataTest`, re-run TC-M-25 | Lead (owner decision needed on the source) |
| ~~S4b-BL-11~~ | Both | ~~**Doubled lines inside the claim areas** (round 1 review of the Docs change for India's boundaries, §12.8; `android/shared/README.md` §9 item 38 (b); new id). From zoom 5 `boundary_2` is not clipped to the four claim boxes, so the tiles' non-disputed country lines (the Wakhan, Himachal Pradesh and Uttarakhand with Tibet, Nepal near Kalapani and Dharchula, Sikkim with Nepal, Tibet and Bhutan, Bhutan's south-east corner and Myanmar south of 26.65 N; list corrected in v0.34 from README 1.38 item 38 (b): Arunachal-Bhutan and Arunachal-Myanmar are not doubled) draw beside India's 1:10m `claim` outline, a median 1.5-2.8 km apart, at most 5.3 km; zoomed in, the user sees two close, nearly parallel lines ([03](03-design.md) ADR-22 Consequences; a known limit that passes TC-M-25 up to 5.5 km apart). The style-side alternative is S4b-BL-16~~ **Done on branch `fix/india-boundary-lines` (PR #16; §12.10)**: the 7 stretches the tiles draw from zoom 5 (none with China) moved from kind `claim` to kind `world` (below zoom 5 only), with a connector of about 7 km at most at each hand-over and none at a box edge; rule 2 leaves India's line with China to the outline on both apps; no new kind or layer was needed for this | Data first (style alone cannot split one feature): the lead gives the claim stretches that `boundary_2` also draws their own `kind` (for example `claim-shared`) in `build_in_boundaries.py` and both copies of `in-boundaries.geojson` (new sha256 in `IndiaBoundaryDataTest`); then Web (`india-boundaries.ts`) and Android (`IndiaViewRules.kt`) draw that kind below zoom 5 only, keeping `claim` at every zoom for the stretches nothing else draws, with tests on both sides. Settle with TC-M-25 steps (2) and (10) | Lead, then Web and Android |
| S4b-BL-12 | Both | **Administrative lines of Pakistani or Chinese units inside India's outline** (round 1 review of the Docs change, minor; new id). The rules leave Liberty's `boundary_3` (admin levels 3 to 6, dashed, from zoom 5) as it is. A Docs decode of the zoom 5 and 6 tiles of the 2026-09-13 planet found those lines (Azad Kashmir-Gilgit-Baltistan, Xinjiang-Tibet through Aksai Chin) flagged `disputed`, so `boundary_3`'s own filter hides them; zoom 7 and above were not decoded, and a later planet may change the flags | Only if TC-M-25's admin-line check finds one: the lead identifies the tile features (zoom, properties); Web and Android add a `boundary_3` rule to both apps together (for example dropping lines whose unit is inside the `claim` outline, or with a PAK or CHN adm0 side if the tiles carry it), with tests on both sides and the ADR-22 rules updated | Web and Android, with the lead |
| S4b-BL-13 | Both | **Renderer wording in the boundary code comments** (coordinator's comment and docs sync, 2026-09-24; new id). Comments said that MapLibre, native and gl alike, checks a layer's minzoom against the map zoom only, so minzoom alone would let a zoom 0-4 parent tile's lines show at zoom 5+. Read from the sources, neither renderer does: maplibre-gl 6.10.0 skips a layer's bucket for a tile below `floor(minzoom)` (`src/source/worker_tile.ts:109`, `src/style/style_layer.ts:321-322`), and maplibre-native android-v13.6.1 leaves such a layer out in `GeometryTile::setLayers` (`src/mln/tile/geometry_tile.cpp:317`, called at `src/mln/renderer/tile_pyramid.cpp:167,193`) before the worker's parse loop (`geometry_tile_worker.cpp:446-502`, no check of its own). So the guards are defence in depth on both apps. This round, Web corrected its comments for maplibre-gl (`web/README.md` row of 2026-09-24, comment and README sync (a)) and raised `geometry_tile.cpp:317` as finding (4); Android's 1.39 (a) rewording (`IndiaViewRules.kt` KDoc of `ADM0_PRESENT` and `tileZoomGuardedLayers`) still says maplibre-native has no skip and the guard is the Android fix | Android: re-read `geometry_tile.cpp:317` and reword the KDoc and README 1.38-1.39 (the guard is defence in depth on both renderers). Web: check its comments name the native skip too. Docs: done in v0.34 ([03](03-design.md) ADR-22 rule 2). Settle on a device with TC-M-25 steps (7) to (9) | Android, then Web |
| ~~S4b-BL-14~~ | Web | ~~**`web/README.md` intro for India's boundaries** (new id). The intro paragraph (*India's boundaries (2026-09-24)*) lists rules 1, 3, 4 and 5 and "starts `boundary_2` at zoom 5 without the Pakistan-China line", but not the adm0 clause or the tile-zoom guard on `boundary_2`, `boundary_3` and the other `boundary` line layers, and ends "see its README for its status" about Android although both apps now apply the same rules~~ **Done by Web in this round, awaiting review** (`web/README.md` row of 2026-09-24, *comment and README sync* (b): the intro names `COUNTRY_LINE_RULE`, `COUNTRY_LINE_RULE_LEGACY` and `TILE_ZOOM_GUARD`, says Android applies the same rules, and states the known limits) | Name `COUNTRY_LINE_RULE` (adm0 side present, not PAK/CHN) and `TILE_ZOOM_GUARD` with the layers it guards, say it is defence in depth on maplibre-gl, and replace the Android sentence with "the same rules as Android (`IndiaViewRules.kt`)" | Web |
| ~~S4b-BL-15~~ | Both | ~~**The Assam-Arunachal Pradesh state line is not drawn from zoom 5** (coordinator's sync; proposed in `android/shared/README.md` 1.39 (c); new id). In tile 5/24/13 it is admin level 4, `disputed` 1, `claimed_by` CN; Liberty's `boundary_3` never draws a disputed line and rule 1 hides `boundary_disputed`, so on both apps Arunachal Pradesh has its external outline but no state line towards Assam ([03](03-design.md) ADR-22 Consequences, [05](05-ux-accessibility-i18n.md) §7.3 *State lines*; expected in TC-M-25)~~ **Done on branch `fix/india-boundary-lines` (PR #16; §12.10)**: new kind `state` (Natural Earth 1:10m admin-1) and layer `in-boundary-state` on both apps, from zoom 5, directly above `boundary_3` and drawn like it | Either draw India's state line from the bundled data (the lead adds a `state` kind for it in `build_in_boundaries.py` and both copies of `in-boundaries.geojson`, new sha256 in `IndiaBoundaryDataTest`; Web and Android draw it dashed like `boundary_3` from zoom 5), or show admin-level-4 disputed lines `claimed_by` CN inside India (a new rule on `boundary_3` in both apps, with tests on both sides and the ADR-22 rules updated). Settle with TC-M-25 over Arunachal Pradesh | Lead, then Web and Android |
| ~~S4b-BL-16~~ | Both | ~~**Doubled tile and claim lines in the middle sector and the Wakhan** (coordinator's sync; proposed in `android/shared/README.md` 1.39 (d); new id). From zoom 5 the tiles' own line and India's `claim` outline both draw there, a median 1.5-2.8 km apart, at most 5.3 km (Web team's tile decode)~~ **Done with S4b-BL-11's data fix on branch `fix/india-boundary-lines` (PR #16; §12.10)**: the claim outline is trimmed in the data where the tiles already draw India's line with Nepal, Bhutan, Myanmar and in the Wakhan, and in the middle sector rule 2 leaves the tiles' India-China line out (`INDIA_CHINA_LINE`), so the outline is the only line there | A style-side fix, instead of or before S4b-BL-11's data fix: hide `boundary_2` inside the claim boxes with a `within` filter, or trim the claim outline where the tiles already draw India's line; the same rule in both apps with tests on both sides; the shared stretches in the east (Bhutan's south-east corner, Myanmar south of 26.65 N) and in Sikkim checked with it. Settle with TC-M-25 steps (2) and (10) | Web and Android, with the lead |
| S4b-BL-17 | Both | **Cleaner hand-overs at the Sikkim tri-junctions, Jomotsangkha and Longwa** (round 2 design review of PR #16, §12.10; new id). At Sikkim's two tri-junctions India's outline and the tiles' neighbour lines meet in small loops (about 13 x 3 km at Nepal-China-India, on glaciers, from about zoom 10; about 2 km at Doklam), and at two hand-overs the tile line runs on past the hand-over and stops in open ground from about zoom 10 (a small hook at Jomotsangkha from zoom 9) (about 9 km at Jomotsangkha, Bhutan's south-east corner; about 3 km at Longwa, Nagaland-Myanmar); cosmetic known minors ([03](03-design.md) ADR-22 Consequences) | Make our outline end where it first crosses the tile line at the Sikkim tri-junctions, and hand over at the tile line's end at Jomotsangkha and Longwa (`find_shared_stretches.py` and `build_in_boundaries.py`; both copies of `in-boundaries.geojson`, new sha256 in `IndiaBoundaryDataTest`). Settle with renders at zoom 10-12 and TC-M-25 step (10) | Lead |
| ~~S4b-BL-18~~ | Android | ~~**Prices, dates and Indic typography can follow a different language than the text** (review of PR #19; new id; pre-existing on `main`). `ui/Format.kt` `indianLocale` and `:ui`'s `UiLanguage.android.kt` read `configuration.locales[0]`, not the language Android resolved for the strings (`resolved_language`, `AppLocale.applyDefault`). A phone set to [Marathi, Hindi] shows Hindi text with Marathi-locale number and date formatting and the Latin line-height rules.~~ **Done in CMP-3** (§13.5): `appLanguage()` in `:ui` is `Locale.getDefault()` on Android, which `AppLocale.applyDefault` keeps on the resolved language; `Formats.date`, `Formats.dateTime` and `uiLanguage()` read it (TC-U-61). Prices and scores no longer depend on a locale | Take the language from the resolved one (for example `Locale.getDefault()` after `applyDefault`, or `resolved_language`), with a test next to TC-U-60; best done with CMP-3's common `Format` | Android |
| S4b-BL-19 | Android | **`MainActivity` could be `exported="false"`** (review of PR #19; new id). Since the rename the launcher `activity-alias` `com.househunt.app.MainActivity` is the only external entry; notifications and widgets reach the activity through explicit `PendingIntent`s, which work on a non-exported activity. | Set `android:exported="false"` on `MainActivity` after checking every intent that opens it (TC-S-12, TC-I-35's new-house intent) | Android |
| S4b-BL-20 | Both | **Clients detect a reset server** (review of PR #19, [08](08-operations-runbook.md) §11; new id). Clients push only rows changed since their last sync and pull from a stored cursor that is reset only when the server's address changes, so a server whose database was replaced (a new, empty database; a restore from an older dump) silently loses the devices' older rows and hides changes below the cursors. | When the server's highest `syncVersion` is below a stored cursor, reset the cursors and mark every local row dirty (a full re-push and re-pull), and say so to the user; Android and web alike, with tests | Android, Web |
| S4b-BL-21 | Android | **Device-only checks for CMP-2 and the rename** (review of PR #19; new id). A JVM test cannot show these | Run [06](06-test-plan.md) **TC-M-27** on real phones: a phone set to [Marathi, Hindi]; the in-app language switch on API 29 and 34, including the "Language changed to …" snackbar; a rotation with a non-system app language on API 32 or lower; an upgrade from a build of `main` before PR #19 (applicationId `app.doorprints`, package `com.househunt.app`) with a pinned home-screen icon and existing houses | Android, QA |
| S4b-BL-22 | Android | **Export's default language follows `locales[0]`** (found in CMP-3; new id). `ExportBuilder.defaults` takes the saved app language, else the configuration's first locale if the app ships it, else English: a phone set to [Marathi, Hindi] with *System default* shows Hindi screens but proposes an English copy. Left out of CMP-3, which changes no export behaviour | Take the resolved language (`appLanguage()` from `:ui`, or `resolved_language`) as the fallback, with a test next to TC-U-61 | Android |
| S4b-BL-23 | Android | **The `Repository` uses Room calls that exist only on Android** (found in CMP-4 P4a; new id). `db.withTransaction { }` (two places) and `db.invalidationTracker.createFlow("houses", "visits", "photos")` are Room's Android API; the database itself is common since P4a (§13.6). Left in `:app` with the `Repository`, which moves in P4c | In P4c, write them with Room's common API (`useWriterConnection { it.immediateTransaction { } }` and the common `InvalidationTracker.createFlow`), with the import and undo tests (TC-U-52, `CopyUndoTest`) passing unchanged | Android |
| S4b-BL-24 | Android | **Room is compiled for iOS but never opened there** (CMP-4 P4a; new id). `iosAppDatabase()` (Application Support, `BundledSQLiteDriver`) and the KSP-generated iOS code compile (`shared-ios.yml`; locally cross-compiled on Linux), but no test runs on an iOS simulator, so the bundled driver, the file path and the DAOs are unchecked on iOS | With the iOS shell (CMP-8): a `commonTest` that opens the database with a driver, writes and reads each table and runs `MIGRATION_1_2`, run on the simulator | Android |
| S4b-BL-25 | Android | **No committed v1 schema** (CMP-4 P4a code review; new id). `AppDatabaseMigrationTest` rebuilds the version-1 layout from `2.json` minus `photos.deleted`, because `1.json` was never exported. | Commit that layout as `shared/schemas/app.doorprints.data.AppDatabase/1.json` with a note, so `MigrationTestHelper.createDatabase(1)` works and later migrations can reuse it | Android |
| ~~(W2)~~ | Web | ~~Plan's submit focuses the start latitude: already a carried minor in §11.7~~ **Done by Web in the buddy pre-review, awaiting review** (`pages/plan/start-field.ts`, `start-field.spec.ts`; §11.7); the round 1 review added `nextTypedStart` (§11.7) | As in §11.7 | Web |

**`NEW RULE:` candidates for the playbooks.** Items (b) to (e), (i) and (j) are now in the design and UX self-check.
(a), (f), (g) and (k) are recorded here until a reviewer adopts them, and (h) is applied as README update rule 11.
(f) and (g) are the coordinator's proposals from this round's backlog; (h) is from the round 1 review of this close-out
(v0.28); (i), (j) and (k) are the coordinator's from the Web team's round 1, 2 and 3 rows (v0.29).

| # | Rule | Raised by | Where it stands |
|---|---|---|---|
| (a) | Owner decisions written into the docs are checked against the lead's decision log and the approved review-rules constant in the workflow scripts, never against the playbooks | Docs reviewers | A rule for reviewing the docs, applied since the round 1 review of the final Sprint 4a round (v0.26). It is not a UX or security family, so it is not in [05](05-ux-accessibility-i18n.md) §15 or [07](07-secure-build-and-deploy.md) Appendix A |
| (b) | Every markdown table has a header and a delimiter row, with matching column counts; code fences are balanced; the header version equals the last change-log row | Docs reviewers | **Folded into [05](05-ux-accessibility-i18n.md) §15.3 R16** (v0.13). Tooling candidate under Decision 3 item 2, inside an existing workflow |
| (c) | R9: an in-progress announcement is replaced or withdrawn on every path it can end, and a shared service cancels only its caller's own message | Web's round 1 reviewers (`web/README.md` round 2 handover) | **Folded into R9** (v0.13) |
| (d) | R9: a repeatable alert or status is keyed on its run; a background re-read keeps its run, and a Retry the user asks for is a new run | Web's round 1 reviewers | **Folded into R9** (v0.13) |
| (e) | R17: every busy flag has its failure, cancel and destroy paths checked | Web's round 1 reviewers | **Folded into R17** (v0.13) |
| (f) | R6/R17: every async callback that can navigate, announce or change a map (geolocation, HTTP, timers) first checks that its page still exists | Delivery coordinator | Candidate for the Design Director and the UX lead. Three finished cases, done by Web in the working tree, awaiting review: the map and Plan guard above, S4b-BL-5, and the house form's `startWithoutPosition()` (`house-detail-page.ts`), which returns when the page is gone after `houses()` resolves and again after it rejects, so leaving `/houses/new` during that read no longer announces *Draft restored* or sets the title on the next page (`web/README.md`, round 2 row; its TestBed cases are S4b-BL-7) |
| (g) | R9/R20: a retry keeps the earlier card in place and dimmed on every screen, including sync and the house form, not only Ask, Plan and Connect | Delivery coordinator | Candidate. S4b-BL-1 and S4b-BL-2 are its first cases; §11.7 X1 is fixed for Ask, Plan and Connect only |
| (h) | When teams edit at the same time, a Docs hand-off names the last handover row it applied from each team README (date and title). A reviewer checks for rows added after it: such a handover is not yet applied, so it is not a new finding against the Docs engineer | Docs reviewers (round 1 review of the close-out) | Applied from v0.28 as [README](README.md) update rule 11. A rule for the Docs hand-off, not a UX or security family |
| (i) | R6/R11: a value the app writes by itself, such as a default or a value saved by a first-layout event, is never read back as the user's choice | Delivery coordinator, from `web/README.md` round 1 row (`hh.mapView` holding the untouched `COUNTRY_VIEW`, saved by MapLibre's first-layout `moveend`) | **Added to [05](05-ux-accessibility-i18n.md) §15.3 R6** (v0.14), with a pointer from R11 |
| (j) | R9/R18: when a message is referenced from a field's `aria-describedby`, every path that resolves its cause withdraws it, not only the path that raised it | Delivery coordinator, from `web/README.md` round 2 row (Plan's *location blocked* note left under the start fields after a map click) | **Added to [05](05-ux-accessibility-i18n.md) §15.3 R9** (v0.14), with a pointer from R18 |
| (k) | A team README never makes up a backlog id. It takes the next free number from this section's register, and its Docs handover says the id is new | Delivery coordinator, from `web/README.md` round 3 row (the round 2 row had reused S4b-BL-4 and S4b-BL-5) | Candidate, a process rule for the register, not a UX or security family. The register's lead-in above states it |

### 12.8 Owner issue P0 of 2026-09-24: India's boundaries on the map

**The issue.** The owner reported that the live site (`https://doorprints.web.app`) drew India's boundaries wrongly
near Jammu and Kashmir, and then that the same problem exists near Arunachal Pradesh. Both apps load OpenFreeMap's
Liberty style unchanged, and Liberty draws the ISO view: the Line of Control, the Line of Actual Control, the "Actual
Ground Position Line" and Chinese claim lines (`boundary_disputed`, dashed), the Pakistan line through Kashmir below
zoom 5, the Pakistan-China line at Khunjerab from zoom 5, and the state labels "Azad Kashmir" and "Gilgit-Baltistan"
inside India's territory. Near Arunachal Pradesh every India-China line in the tiles (planet of 2026-09-13) is a
disputed line. The Android app has the same map, so it had the same issue.

**Decision (owner, Sriram, 2026-09-24; [03](03-design.md) ADR-22, [01](01-requirements.md) FR-098,
[11](11-feature-parity-and-export-spec.md) D-26).** Every map on both apps shows India's external boundary as the
Government of India depicts it, as Google Maps shows it to users in India: all of Jammu and Kashmir and Ladakh
(including PoK, Gilgit-Baltistan, Shaksgam and Aksai Chin) and Arunachal Pradesh inside India, one solid outline, no
Line of Control, no Line of Actual Control and no other de facto or claim line. Every user is in India, so this is the
only view (no switch). Zero cost: no paid basemap.

**What was done, by team** (branch `fix/india-boundaries`, based on `main` `34808cb`):

| Team | Change | State |
|---|---|---|
| Lead | Decoded the OpenFreeMap planet tiles of 2026-09-13 and the Liberty style; built `in-boundaries.geojson` (Natural Earth, public domain, `natural-earth-vector` commit `ca96624`; kinds `world` and `claim`; four claim boxes: west, middle, sikkim, east) with `web/scripts/geo/build_in_boundaries.py`, byte-identical in both apps (sha256 `700646ea…4954`) | Committed, `a37ecbd` |
| Web | `shared/india-boundaries.ts` (the five rules, pure, and their application on every `style.load`, registered in `createMlMap`), including rule 2's `COUNTRY_LINE_RULE` / `COUNTRY_LINE_RULE_LEGACY` and `TILE_ZOOM_GUARD`; `india-boundaries.spec.ts` (37 cases) with a Liberty excerpt fixture, precache and type cases in `sw-precache.spec.ts`, `firebase.json` serves `.geojson` as `application/geo+json`, "Natural Earth" attribution | Committed (`aef007c`; [06](06-test-plan.md) TC-U-54); not yet seen green in CI |
| Android | `ui/IndiaView.kt` and `ui/IndiaViewRules.kt` (`applyIndiaView` from `MapScreen.loadStyle` before the house layers), `IndiaViewRulesTest`, `IndiaBoundaryDataTest` (`android/shared/README.md` 1.36). Round 1 fixes (1.37): rule 2 also requires an adm0 side, so a zoom 0-4 tile shown while a closer tile loads, or offline, never draws its ISO-view line; `in-boundary-world` maxzoom `Math.nextDown(5f)` (maplibre-native includes both ends of a zoom range); the paint reads and the two outline layers each in their own step. Round 2 fixes (1.38): the tile-zoom guard on `boundary_2`, `boundary_3` and every `boundary` line layer from zoom 5; 1.39: comment wording only | Committed (`641f32f`; TC-U-55); 18 tests (16 + 2), reported passing locally (README 1.38), not yet seen green in CI |
| DevSecOps | `web.yml` `pwa-files` requires the file; `check-live-headers.sh` checks it after each deploy; `android.yml` runs on `web/public/geo/**` | Working tree (TC-S-25); not yet run |
| Docs | [03](03-design.md) ADR-22, [01](01-requirements.md) FR-098, [02](02-threat-model.md) RR-16, [05](05-ux-accessibility-i18n.md) §7.3, [06](06-test-plan.md) §15, [07](07-secure-build-and-deploy.md) section 1 and 6.3, [11](11-feature-parity-and-export-spec.md) D-26 and §10, this section, README credits, CHANGELOG *Fixed* | Committed (`3ad2b58`); re-synced with the code at `3ad2b58` in v0.34 (working tree) |

**Verified, and how.** The lead's before/after renders of the proposed rules from the decoded tiles (country view at
zoom 4 and 5, and Kashmir) show India's outline around Jammu and Kashmir and Ladakh and no disputed line or Pakistan
line through Kashmir; at zoom 5 they also show a tile country line beside the outline in the middle box (Himachal
Pradesh with Tibet), a doubled line as described in [03](03-design.md) ADR-22 Consequences (lead's working files,
not in the repository). Web: the transformed Liberty style validated against the style spec 26.4.4 sources
(`web/README.md`, 2026-09-24 row). Android: 18 JVM tests reported passing locally with the Kotlin 2.0.21 compiler
(`android/shared/README.md` 1.38).

**Docs decode of the lead's tiles (2026-09-24; planet of 2026-09-13; zoom 4 tiles 10-12/6-7, the 15 zoom 5 tiles
21-25/12-14, which cover 56.25-112.5 E and 11.2-41.0 N, and zoom 6 tile 45/25 over Kashmir).** (a) No admin-2
line of the zoom 4 tiles carries `adm0_l` or `adm0_r`, which is why Android's adm0 guard drops them. (b) Every
non-maritime, non-disputed admin-2 line of the zoom 5 tiles (105) and of the zoom 6 tile (3) carries at least one of
the two (no line carries `IND`: India's side is always the missing one), so the guard drops none of the lines `boundary_2` should draw
there; this is evidence for `android/shared/README.md` §9 item 38 (a), which the lead still confirms for release.
(c) The stretches drawn twice from zoom 5 (tile line and `claim` outline) are the ones listed in ADR-22
Consequences (list corrected in v0.34 from `android/shared/README.md` 1.38 item 38 (b), which sampled the outline:
Arunachal-Bhutan 0 of 96, Arunachal-Myanmar 0 of 206 and Jammu-Sialkot 0 of 88 samples on a drawn tile line); the Jammu International Border north of 32.35 N and every India-China line in the west box and near
Arunachal Pradesh are `disputed` in these tiles, so only the outline draws them. (d) `boundary_3`: inside India's
outline only Indian lines pass Liberty's own filter (Jammu and Kashmir-Ladakh), apart from a few short
stretches that run along the outline near 73.6 E, 33.3 N; Azad Kashmir-Gilgit-Baltistan and
Xinjiang-Tibet through Aksai Chin are `disputed`. Zoom 7 and above were not decoded; TC-M-25 covers them.

**Parity, compared rule by rule** (`IndiaViewRules.kt` and `IndiaView.kt` against `india-boundaries.ts`, code at
HEAD `3ad2b58`, 2026-09-24; v0.34): the same five rules, including rule 2's adm0 clause and tile-zoom guard (web
`COUNTRY_LINE_RULE`, `COUNTRY_LINE_RULE_LEGACY`, `TILE_ZOOM_GUARD`; Android `COUNTRY_LINE_EXTRA_FILTER`, `_LEGACY`,
`TILE_ZOOM_GUARD`), so there is no parity gap (v0.33 recorded one; `android/shared/README.md` §9 item 36 is done). Read
from the renderer sources, both maplibre-gl 6.10.0 (`worker_tile.ts:109`) and maplibre-native android-v13.6.1
(`geometry_tile.cpp:317`) leave a zoom 0-4 tile out of a minzoom 5 layer, so the guards are defence in depth on both
apps ([03](03-design.md) ADR-22 rule 2; not observed on a device yet). Android's `nextDown(5)` maxzoom behaves as the web's
exclusive maxzoom 5. The one deliberate difference is the web's "Natural Earth" credit. Known limits shared by both at
`3ad2b58`: the Assam-Arunachal Pradesh state line is not drawn from zoom 5 (S4b-BL-15), and doubled lines in the Wakhan, the
middle sector, Sikkim, Bhutan's south-east corner and Myanmar south of 26.65 N (S4b-BL-11, S4b-BL-16), both fixed on
branch `fix/india-boundary-lines` (§12.10); the outline is a median of about 1.55-1.6 km off the true line, 3.9 km
at the 90th percentile (the owner's summary: typically 1.5-3 km off, up to about 5 km in a few mountain stretches,
visible only when zoomed into the Himalaya, never in a city). **Not verified:** no CI run of it seen green yet (the run on `3ad2b58` was not finished when v0.34 was written), nothing seen on a device or on the live site. The fix is done
only when the four workflows are green on the branch, it is merged and deployed, and **TC-M-25** passes on the live
site and on an Android device (a release gate from now on, [06](06-test-plan.md) §11).

**Open before release:** CI green on the branch; the lead confirms item 38 (a) for zoom 6 to 14 and signs off the
tile-zoom guard's trade-off (item 38 (c)); TC-M-25 on both apps, including the loading, offline, cold-start, zoom 5.0,
admin-line, doubled-line (since `fix/india-boundary-lines`, a second line beside the border anywhere fails; the
hand-overs and the state line are checked too, §12.10) and street-zoom steps.

**Alternatives rejected** (ADR-22): hiding the disputed lines only (no line between PoK and the rest of India from
zoom 5, and the Pakistan line through Kashmir stays below zoom 5); a paid basemap with a worldview option (cost); a
Survey of India outline file (licence and redistribution unclear, kept as S4b-BL-10).

**Backlog:** S4b-BL-9 (re-check after each OpenFreeMap planet or style update, and before each release),
S4b-BL-10 (a Survey of India-derived outline, if its licence allows), S4b-BL-11 (doubled lines inside the claim
areas; done on `fix/india-boundary-lines`, §12.10), S4b-BL-12 (Pakistani or Chinese admin lines
inside the outline, only if TC-M-25 finds one), S4b-BL-13 (renderer wording in the code comments),
S4b-BL-14 (`web/README.md` intro; done by Web this round), S4b-BL-15 (the Assam-Arunachal Pradesh state line) and S4b-BL-16 (doubled lines in
the middle sector and the Wakhan), both done on `fix/india-boundary-lines` (§12.10), in the §12.7 register.

### 12.9 Story S4b-BR-1: the app icon's footprints, option C

**Story.** As a user, I recognise Doorprints by one mark on every platform. The footprints beside the door read as
footprints, not as two gold ovals. Source: [14](14-lead-backlog-and-handoff.md) N3, the owner's choice of option C on
2026-09-24. Branch `fix/brand-footprints`, PR #15.

**Owner decisions (2026-09-24).** (1) Three small footprints (sole, heel, four toes) walking up beside the door, at the
positions, rotation and scale of N3, except that the design review moved the top print from (79,55) to (78.5,51.5)
(spacing only, as N3 allows) so that its heel no longer touches the middle print. (2) **Left/right/left**, not the right/left/right first written in N3: at those
positions right/left/right put the left foot to the right of the right feet, so the big toes faced away from each
other. The owner chose the swap after a side-by-side render. (3) The favicon keeps the same three prints; at 16 px
the toes do not show, and the owner accepted that for one mark everywhere.

**Done when:** `ic_launcher.xml`, `ic_stat_doorprints.xml`, `favicon.svg` and the five app-icon PNGs show the new
prints with colours, the door and each icon's layout unchanged; the maskable icon keeps the whole mark inside the
80 % safe circle; [12](12-brand-and-naming.md) N-06, `web/README.md` *Installable (PWA)* and CHANGELOG describe it;
the renders are shown to the owner before the pull request; CI is green on the branch; the code, design and UX and
docs reviews approve; the owner merges (or a session merges on the owner's instruction).

| Check | Result |
|---|---|
| Renders shown to the owner | Before/after comparison and final renders (512, maskable in the circle, Apple, 192, favicon at real size, status icon at 24/48/72 px), 2026-09-24 |
| Web | `ng test` 459 of 459, `ng build` and the precache stamp, locally (Node 24) and in CI |
| Android | `assembleDebug` and unit tests green in CI on the branch (no SDK in the session) |
| CI on the branch | All workflows green on `e2414f6` (round 1); the review-fix heads re-run by CI, and the owner's condition for the merge is green CI on the final head |
| Reviews, round 1 | Code (Web, Android): **approved**, three minors (the status icon's toes and the favicon's toes at small sizes, both owner-accepted or device checks; one BACKLOG). Design and UX: **approved**, one spacing minor applied (top print to (78.5,51.5)), three BACKLOG. Docs: **changes requested**, two majors (ADR-13 in [03](03-design.md) still said the favicon had no toes; [14](14-lead-backlog-and-handoff.md) §1 still showed PR #14 open) and five minors, all applied |
| Reviews, round 2 and 3 (delta only) | Code: **approved**. Design and UX: **approved** (gaps now 3.85 and 3.44 units; the maskable mark inside the 80 % circle). Docs: round 2 found one major (N-06's contrast warning named the wrong direction; it is a lighter teal or a darker gold that lowers the contrast) and one minor ("the five app-icon PNGs", not the shortcut icons), fixed in `c9d8bac`; round 3 **approved**. All gates approved |
| Device checks | Owner: the launcher icon on a round-mask launcher; the status-bar icon while Hunt mode runs |

**Review follow-ups (`BACKLOG:` candidates, not in this story).** (a) One source for the footprint: the path is
repeated in `ic_launcher.xml` (three groups), `ic_stat_doorprints.xml` and `favicon.svg`; a small script that writes
the web SVG and PNGs from the Android geometry would stop the two platforms drifting (code and design reviews).
(b) If the owner reopens the mark: a print scale of about 1.0 instead of 0.9, for more gold at the 48 px launcher size
(design review). (c) Check the status-bar icon's toes on an mdpi or hdpi phone; if they smear, drop them from the
small icon only (code review; device check above).

### 12.10 Owner request of 2026-09-24: the doubled lines and the Assam-Arunachal Pradesh state line

**The request.** After TC-M-25 was run on the live site on 2026-09-24 (it passed except step (3), the comparison with
Google Maps, done later that day, see below), the owner summarised the map as follows. The outline is typically
1.5-3 km off the true line and up to about 5 km in a few mountain stretches, visible only when zoomed into the
Himalaya and never in a city. In Himachal Pradesh, Uttarakhand and the Wakhan two close lines show (the tiles' line
and ours). Kashmir, Arunachal Pradesh and Jammu have only our line. From zoom 5 the Assam-Arunachal Pradesh state
line is missing. The owner asked for one line and for the state line (S4b-BL-11, S4b-BL-15, S4b-BL-16).

**What was done** (branch `fix/india-boundary-lines`, PR #16: `5af2f4d`, then `9e0036e` after the design review;
[03](03-design.md) ADR-22 v0.20):

- **The India-China border is ours alone.** Rule 2 also leaves out India's line with China: `boundary_2` draws no
  line with China on one side and India or no country on the other (web `COUNTRY_LINE_RULE` with
  `INDIA_CHINA_LINE`, Android `COUNTRY_LINE_EXTRA_FILTER` with `INDIA_CHINA_LINE`, both syntaxes). Our Natural Earth
  outline draws the whole India-China border (Ladakh with Aksai Chin, Himachal Pradesh and Uttarakhand with Tibet,
  Sikkim with Tibet, Arunachal Pradesh) at every zoom, as one line with no hand-over. The reason: the tiles cut that
  line into short undisputed (drawn) and disputed (hidden) pieces, which on `5af2f4d` still showed at zoom 10-12 as
  stray pieces beside our outline (Shipki La, the Mana Pass; design review). China's lines with Nepal, Bhutan and
  Myanmar still draw.
- **One line from zoom 5 on the 7 shared stretches.** Along the stretches where the OpenFreeMap tiles draw India's
  border themselves, none of them with China (planet 20260913: Nepal near Kalapani, 77.5 km; Sikkim and the
  Darjeeling and Kalimpong hills (West Bengal) with Nepal, 13.5 and 75.2 km, and with Bhutan, 44.6 km; Bhutan's
  south-east corner, 79.3 km; Myanmar south of about 26.65 N, 31.3 km; the Wakhan, 105.9 km), our outline moved from
  kind `claim` to kind `world`, so it draws below zoom 5 only and the tiles' more precise line takes over from zoom 5.
  At each hand-over the claim piece ends with a short straight connector (about 7 km at most) to the tile line, so
  the border has no gap. Where a stretch starts or ends at the end of a claim line (a box edge) there is no
  connector: on `5af2f4d` those were connector-only spurs of 2-7 km (Nagaland-Myanmar, design review). Repeated points
  are removed. A cut within 1e-4 degrees of a claim line's end counts as the end (`SHARED` is rounded to 5
  decimals): on `9e0036e` two connector-only pieces on the Singalila ridge (2.5 km and 2.3 km) still showed as spurs
  into Nepal from zoom 5 (round 2 code and design reviews). Jammu and Kashmir and Ladakh (with PoK, Gilgit-Baltistan and Shaksgam), Jammu-Sialkot and Arunachal
  Pradesh with Bhutan and Myanmar keep our outline at every zoom.
- **How the stretches were found.** New `web/scripts/geo/find_shared_stretches.py` decodes the 20260913 planet tiles
  at zooms 7, 9 and 11 and samples our outline every 250 m. A sample is shared only when, at all three zooms, a line
  that `boundary_2` draws after rule 2 and that is India's (India's side missing or IND, not China; or the Wakhan's
  Pakistan-Afghanistan line) runs beside it within 7 km and 60 degrees, not past one of its ends. Shared runs count
  from 2 km; each hand-over is put at the point of least separation within 5 km of the stretch's end; a stretch that
  reaches the end of a claim line runs to that end; short end pieces and gaps (under 30 km) are shared when the tile
  line stays within 12 km (tile-line ends accepted there). Tile downloads are retried and written atomically to
  `web/scripts/geo/.tilecache` (git-ignored). Its output is pasted into `SHARED` in `build_in_boundaries.py`
  (`--no-shared` gives the whole outline the finder needs); S4b-BL-9 now includes re-running it.
- **The state line.** New kind `state`: the Natural Earth 1:10m admin-1 lines "Assam - Arunachal Pradesh" (notes
  India_20 and India_200). New layer `in-boundary-state` from zoom 5, directly above Liberty's `boundary_3` and drawn
  like it (web `IN_BOUNDARY_STATE_LAYER`, `STATE_FALLBACK_LINE_PAINT`; Android `IndiaViewRules.STATE_OVERLAY_LAYER`,
  `STATE_FILTER`, `STATE_MIN_ZOOM`, `statePlacement()`, `IndiaView.kt` step 3b). The tiles carry this line as admin
  level 4, disputed, `claimed_by` CN, so it stays hidden there; the other hidden admin-4 lines (China's claim lines
  in the middle sector, one line in Aksai Chin marked `claimed_by` IN, Pakistan's lines in PoK and Gilgit-Baltistan)
  stay hidden on purpose.
- **Data.** `in-boundaries.geojson`, both copies byte-identical: sha256 `25984afa…a024` (was `700646ea…4954` on
  `main`), 415 608 bytes; `world` 359 lines, `claim` 5 pieces (1 667 points), `state` 2 lines (213 points). The rebuild is
  reproducible from `natural-earth-vector` commit `ca96624`; the old file was first rebuilt byte for byte. CI:
  `web.yml` `pwa-files` requires the kinds `world`, `claim` and `state`.
- **Docs.** [01](01-requirements.md) v0.24, [02](02-threat-model.md) v0.30, [03](03-design.md) v0.20,
  [05](05-ux-accessibility-i18n.md) v0.18, [06](06-test-plan.md) v0.30, [07](07-secure-build-and-deploy.md) v0.29,
  this section (v0.36), [11](11-feature-parity-and-export-spec.md) v0.16, [14](14-lead-backlog-and-handoff.md) v0.3,
  the root README, CHANGELOG, `web/README.md` and `android/shared/README.md` 1.41 and 1.42; after the round 2
  reviews [01](01-requirements.md) v0.25, [02](02-threat-model.md) v0.31, [03](03-design.md) v0.21,
  [05](05-ux-accessibility-i18n.md) v0.19, [06](06-test-plan.md) v0.31, this document v0.37,
  [11](11-feature-parity-and-export-spec.md) v0.17, [14](14-lead-backlog-and-handoff.md) v0.4 and
  `android/shared/README.md` 1.43.

**Trade-offs and known minors.** While zoom 5+ tiles load, or offline without them cached, the 7 shared stretches
show no line at zoom 5 and above (the same degraded state as the tiles' other country lines); the India-China border
and our outline elsewhere always show. At street zoom the other hand-overs show as a small step. At Sikkim's two
tri-junctions (Nepal-China-India in the north-west, and Doklam, Bhutan-China-India, in the north-east) our outline
and the tiles' neighbour lines meet in small loops, because Natural Earth and OpenStreetMap put the tri-junctions a
few km apart: about 13 x 3 km at Nepal-China-India (on glaciers, seen only from about zoom 10) and about 2 km at
Doklam. At two hand-overs the tile line runs on past the hand-over and stops in open ground, from zoom 11: about
9 km at Jomotsangkha (Bhutan's south-east corner) and about 3 km at Longwa (Nagaland-Myanmar). All cosmetic;
S4b-BL-17 (§12.7) would end our outline where it first crosses the tile line at the tri-junctions and hand over at
the tile line's end at Jomotsangkha and Longwa. `INDIA_CHINA_LINE` is global, not limited to India: it also hides
about 12 km of the China-North Korea line on the Tumen river islets (130.24-130.45 E, 42.55-42.78 N; tile sides
none and CHN), which is harmless for India. Where our outline is drawn it is still Natural Earth 1:10m (the
accuracy above).

**Verified, and how.** Web: `india-boundaries.spec.ts` 41 cases (4 new, among them "rule 2: India's line with China
is left to India's outline..."), the whole suite 463 tests passing locally. Android: `IndiaViewRulesTest` 18 tests
(new `indiasLineWithChinaIsNotDrawnFromTheTilesOurOutlineDrawsIt` and
`indiasStateLineGoesDirectlyAboveTheStateLinesFromZoomFive`) and `IndiaBoundaryDataTest` 2, 20 in all, passing on
the JVM with kotlinc 2.0.21 `-Werror` ([06](06-test-plan.md) TC-U-54, TC-U-55). CI green on `5af2f4d` (Android,
Web, Security, CodeQL). Renders of `5af2f4d` with live tiles at zoom 4 to 11 over Himachal Pradesh, Uttarakhand,
Kalapani, Sikkim, Bhutan's south-east corner, Myanmar, the Wakhan, Kashmir and Arunachal Pradesh, then of `9e0036e`
at zoom 8 to 12 over every hand-over and the spots flagged in review: one line, and the state line dashed like the
other state lines. **TC-M-25 step (3)** was done on 2026-09-24 on the build then live (before this branch) with the
owner's Google Maps link: opened with the India region (google.co.in), Google Maps shows the same outer boundary as
ours (Jammu and Kashmir and Ladakh with PoK, Gilgit-Baltistan, Shaksgam and Aksai Chin; Arunachal Pradesh); seen
from outside India it shows the disputed (dashed) view.

**Round 2 reviews (of `9e0036e`).** Code: changes requested, 1 major (the two connector-only spurs into Nepal on
the Singalila ridge), fixed (a cut within 1e-4 degrees of a claim line's end counts as the end; new data file
`25984afa…a024`); 1 minor (`INDIA_CHINA_LINE` also hides the China-North Korea line on the Tumen islets) recorded
above. Design: changes requested, 1 major (the same spurs), fixed; 2 minors (the Sikkim tri-junction loops, sized;
the tile line's overrun at Jomotsangkha and Longwa) recorded above, with backlog ticket S4b-BL-17. Docs: approved
with 1 minor (RR-16 *Mitigation* did not name the India-China clause, [02](02-threat-model.md) v0.31), fixed.

**Not verified:** CI on `9e0036e` (running when this was written; `IndiaView.kt` is compiled by CI only) and on the
spur fix; the Android app on a device; TC-M-25 on the live site and on a device after the deploy, including the
hand-overs, no spur on the Singalila ridge, the Sikkim tri-junction loops, the India-China border at street zoom and
the state line
([14](14-lead-backlog-and-handoff.md) N2).

## 13. Compose Multiplatform track (owner request of 2026-09-24)

**The request.** "The Compose needs to be changed to Kotlin Compose to allow easy iOS app creation" (owner, Sriram,
2026-09-24). That means moving the Android UI from Jetpack Compose to JetBrains **Compose Multiplatform** (CMP), so an
iOS app can reuse it. Decision record: [03](03-design.md) **ADR-23**, which amends ADR-14 (Sprint 3.5 rejected a
Compose Multiplatform UI) and settles its Phase 2 item (4), SwiftUI or CMP. Module detail:
[`android/ui/README.md`](../android/ui/README.md). The lead backlog item P3 (iPhone app) now points here
([14](14-lead-backlog-and-handoff.md)).

**How the work runs.** Each phase (and each lettered sub-phase) is **one pull request to `main`** that keeps
`android.yml` green and runs the CLAUDE.md review steps: the engineer's self-check (for UI: accessibility, all four
languages, both themes, loading/empty/error states), one reviewer pass per area touched, then a second pass on the
delta only. There is no visual change unless a phase says so. Anything out of scope becomes a backlog item.

**Out of scope** until there is a Mac and a paid Apple Developer account (about US$99 a year, against the zero-cost
rule): signing, device installs, TestFlight and the App Store. iPhone users keep the PWA until then.

### 13.1 Tickets

| ID | Phase | Story | Done when | Team | Status |
|---|---|---|---|---|---|
| CMP-0 | Prerequisite | **Test harness for the phases** (owner request of 2026-09-24, §13.3). JVM screenshot tests of every screen except the Map in four languages and both themes (Robolectric + Roborazzi, 64 references, verified in `android.yml`); instrumented smoke tests on an API 34 emulator (`android-emulator.yml`) and in Firebase Test Lab (`main` only, push or manual, after the owner's setup); the live web UI test `tools/live-ui` after every merge to `main` | [06](06-test-plan.md) TC-U-56, TC-I-35 and TC-M-26 exist; each later phase shows "no screen changed" with an unchanged TC-U-56 and a green TC-I-35, or re-records the images it changes on purpose and says so | Android, DevSecOps, Web, Docs | **Done** (PR #18, merged to `main` as `6da0e56`. On `bc57361` the push run passed both smoke tests; the pull-request run on the same commit crashed in `everyTabOpens` (a threading bug, fixed in `6376706`); both runs on `6376706` passed (push and pull request)); Test Lab off by default at zero cost, options for the owner in [07](07-secure-build-and-deploy.md) §7.2 |
| CMP-1 | P1 | New KMP module `:ui` (`android/ui`): plugins `kotlin.multiplatform`, `android.kotlin.multiplatform.library`, `kotlin.compose`; targets Android plus compile-only `iosArm64` and `iosSimulatorArm64`; Compose Multiplatform 1.12.1, material3 1.9.0, material-icons-core 1.7.3, `api(project(":shared"))`. The theme and pure UI code move to `commonMain` with the Kotlin package kept (`com.househunt.app.ui`) | `Theme.kt`, `Rows.kt`, `ServerStatus.kt`, `MapRules.kt`, `IndiaViewRules.kt`, `Buttons.kt` (`ANIMATION_MS`, `ButtonLabel`, `BUTTON_LABEL_MAX_LINES`), `ResultTone` and `LocationFix` in `:ui`; `expect fun uiLanguage()`; `ServerStatusTest` in `:ui` `commonTest`; `android.yml` and `shared-ios.yml` cover `:ui`; no visual change | Android, DevSecOps, Docs | **Done** (`be86f50`); iOS compile pending on CI |
| CMP-2 | P2 | **Strings to compose-resources.** The four `strings.xml` files move to `ui/src/commonMain/composeResources/values{,-hi,-ta,-te}`; add the `org.jetbrains.compose` plugin; code uses `Res.string`, service code `getString(Res.string)`; on API 26-32 `AppLocale` calls `Locale.setDefault` | Every screen shows the same text in en, hi, ta and te as before; a new `StringParityTest` checks that the four languages have the same keys; hi, ta and te stay marked *under review* | Android, Docs | **Done** (PR #19, merged as `7080a7f`). Differs from the plan in five points (§13.4): the service strings stay Android resources, services keep `R.string`, `AppLocale.applyDefault` sets the default locale on every API level, the APK carries only the four languages, and `StringParityTest` checks more than the keys |
| CMP-3 | P3 | **Platform seams.** A `PlatformServices` interface for announce, the screen reader, share and URLs, pickers, permission state and work progress. `Format.kt` moves (an `expect` date format; Indian digit grouping in common), with `LiveMessage`, `DeletedHouseUndo`, `ActionBar`, `ResultCard` and the pure helpers | The moved code has no `android.*` import; TalkBack announcements and share targets behave as before | Android | **Done** (PR #20, merged as `fccf8a1`). Also moved `MapRulesTest` and `IndiaViewRulesTest` to `:ui` commonTest (kotlin.test), removed `:app`'s blocking `UiStrings.kt` and fixed S4b-BL-18. Differs from the plan in four points (§13.5): `PlatformServices` has one member so far, `DeletedHouseUndo` takes two lambdas instead of the repository, amounts and scores need no locale, and `:app` keeps a thin `DeletedHouses.kt` |
| CMP-4 | P4a, P4b, P4c | **Data in common.** P4a: Room KMP (the catalog's version, 2.8.5 since Dependabot #12) in `:shared`, keeping the db v2 identity hash (`RoomSchemaTest`) and adding a migration test. P4b: DataStore KMP, a `SecretStore` interface (Android Keystore, later iOS Keychain) and `ServerUrl` in common. P4c: a `Repository` interface in common; `CompareScreen` and `HouseFormRules` move | Upgraded installs open their data unchanged; settings and the saved key survive; `ServerUrlTest` passes on the common parser | Android | **P4a done in code** (branch `claude/doorprints-dev-continue-fzcge2`, §13.6): `AppDatabase`, entities, DAOs, `Converters` and `MIGRATION_1_2` in `:shared` commonMain, identity hash and `doorprints.db` unchanged, new `AppDatabaseMigrationTest` (TC-U-63); the builder, `DatabaseFile` and the `Repository` stay in `:app`. **P4b and P4c planned** (P4b next) |
| CMP-5 | P5 | **Navigation and view models.** JetBrains navigation-compose 2.9.2 and lifecycle 2.11.0; ViewModels with injected dependencies; HouseList, Assistant, Settings, NotifyAsk and LocationPermission move | Deep links and Back behave as before; the moved screens pass the UI self-check | Android | Planned |
| CMP-6 | P6a, P6b | **Edit, export and import.** P6a: `HouseEditScreen`, with the photo picker and camera behind a seam. P6b: the Export and Import screens and `ImportViewModel`; the workers behind an interface | Photos, copies and imports work as before (TC-U-52 and the Sprint 4a export and import cases) | Android | Planned |
| CMP-7 | P7 | **Map.** The common `MapScreen` chrome and `expect PlatformMap`: on Android the existing MapLibre `MapView` in `AndroidView`, on iOS `UIKitView` around `MLNMapView` from Swift. India's boundary logic lifted into a common `applyIndiaView(ops: StyleOps)`, with `IndiaViewOpsTest` in `commonTest` | **TC-M-25 re-run** and passed (ADR-22); the map looks and behaves as before | Android, Docs | Planned |
| CMP-8 | P8 | **iOS shell.** `iosApp` in Xcode, `ComposeUIViewController`, MapLibre iOS via SPM; built for the simulator on GitHub's macOS runners with `CODE_SIGNING_ALLOWED=NO` (free) | The simulator build is green in CI; no signing, no device, no App Store | Android, DevSecOps | Planned |
| CMP-9 | Spike | **maplibre-compose** (`org.maplibre.compose` 0.17): pre-1.0 and would replace the Android map engine | A written finding; re-assessed when it reaches 1.0 | Android | Planned |

### 13.2 CMP-1 (phase 1), done

**What was done** (commit `be86f50`; package names now `app.doorprints…`, §14): the new module `android/ui` (`:ui`,
Android namespace `com.househunt.ui`).
Moved to `commonMain`: `Theme.kt`, `Rows.kt`, `ServerStatus.kt`, `MapRules.kt` and `IndiaViewRules.kt`, plus
`ANIMATION_MS`, `ButtonLabel` and `BUTTON_LABEL_MAX_LINES` (new `Buttons.kt`, from `ActionBar.kt`), `ResultTone`
(from `ResultCard.kt`) and `LocationFix` (from `LocationPermission.kt`). The moved files keep the Kotlin package
`com.househunt.app.ui`, so `:app`'s imports are unchanged; declarations `:app` uses are `public` instead of
`internal`. The theme's language lookup is `expect fun uiLanguage()`: on Android `LocalConfiguration`'s locale, as
before; on iOS Compose's `Locale.current`. `IndiaViewRules` computes `WORLD_MAX_ZOOM` from the float's bits instead
of `Math.nextDown` (same value, 4.9999995f; `Float.nextDown()` is JVM-only, which the code review caught with
`:ui:compileCommonMainKotlinMetadata`, now in `android.yml`).
`ServerStatusTest` moved to `:ui` `commonTest` (`kotlin.test`, 5 tests). `:app`'s `testDebugUnitTest` depends on
`:ui:testAndroidHostTest`, and `android.yml` also names it. `shared-ios.yml` also watches `android/ui/**` and compiles
`:ui:compileKotlinIosArm64`, `:ui:compileKotlinIosSimulatorArm64` and `:ui:compileTestKotlinIosSimulatorArm64`; the
job name is unchanged, in case it is a required check. `android/.gitignore` ignores `/ui/build/`.

**Verified, and how.** Locally: `assembleDebug`, the 156 `:app` unit tests, the `:shared` host tests and the 5 `:ui`
tests pass. No visual change (no UI code changed behaviour; the files moved).

**Not verified:** the iOS compile of `:ui` (CI on macOS only, `shared-ios.yml`; pending at this writing); the app on a
device. The `android-reports` CI artifact now also uploads `android/ui/build/reports` and its test results.

**Docs.** [03](03-design.md) v0.22 (ADR-23, ADR-14 amended, §4.2.1), [06](06-test-plan.md) v0.32,
[07](07-secure-build-and-deploy.md) v0.30, this section (v0.38), [14](14-lead-backlog-and-handoff.md) v0.5,
`android/ui/README.md` 1.1, `android/shared/README.md` 1.44, the root README, CHANGELOG, the docs index and CLAUDE.md.

### 13.3 CMP-0, testing the APK and the live web UI (owner request of 2026-09-24)

**The request.** "Is there any way you can test the Android APK?" (owner, 2026-09-24). Three options were offered and
the owner chose all three: an emulator CI job, screenshot tests and Firebase Test Lab. The owner also asked: "Test the
Web UI in detail as well after every main merge". It is filed here, as the prerequisite of the track, because every
later phase ([03](03-design.md) ADR-23) now shows "no screen changed" with these tests instead of by eye.

**Owner rule (2026-09-24): after every merge to `main`, the live web UI is tested in detail** with `tools/live-ui`
([06](06-test-plan.md) TC-M-26), once the deploy has finished. The lead session runs it and reports the counts; a
failure is a ticket before the next merge. Recorded in [14](14-lead-backlog-and-handoff.md) §5 and CLAUDE.md.

**What was done** (commit `afe4064` on branch `claude/doorprints-dev-continue-fzcge2`):

- **Screenshot tests on the JVM** ([06](06-test-plan.md) TC-U-56). `ScreensScreenshotTest` renders Houses, Compare,
  the house form (edit and new), Settings, Assistant, Export and Import in en, hi, ta and te, light and dark, with
  Robolectric 4.17 and Roborazzi 1.75.0: **64 reference PNGs** (about 3.5 MB) in `android/app/src/test/screenshots`,
  two sample houses seeded. `android.yml` passes `-Proborazzi.test.verify=true`, so a changed screen fails CI, and
  uploads the diffs in `android-reports`. Re-record with `./gradlew :app:recordRoborazziDebug`. The Map is left out:
  MapLibre is native code.
- **`HouseHuntApp` is `open`**, with `protected open fun startServices()` holding MapLibre, the notification channels,
  the WorkManager schedules and the start-up jobs. The test application overrides it and installs a test WorkManager.
  Production behaviour is unchanged.
- **Instrumented smoke tests** ([06](06-test-plan.md) TC-I-35): every tab opens; a house added from a new-house intent
  shows in the list; a screenshot per step through the test storage service.
- **`.github/workflows/android-emulator.yml`**: job `emulator` (API 34 `google_apis` x86_64, KVM, error dialogs
  hidden, `connectedDebugAndroidTest`, artifact `android-emulator-results`), and jobs `ftl-check` and
  `firebase-test-lab` (Firebase Test Lab on pushes to `main` and manual runs from `main`, keyless through a Workload
  Identity provider of its own, `FTL_WIF_PROVIDER`; the Hosting provider is not widened, as the code review asked;
  skipped with a notice until the owner's setup in [07](07-secure-build-and-deploy.md) §7.2 and
  [ops/firebase-test-lab-setup.md](ops/firebase-test-lab-setup.md)).
- **`tools/live-ui`** (Playwright 1.56.1, axe-core 4.13.0): every route x 4 languages x 2 themes x phone and desktop,
  the add/edit/list/compare/download/offline/delete flows, the map at the TC-M-25 spots.

**Verified, and how.** Locally: 220 `:app` unit tests pass (156 before plus 64 screenshots). The live UI test ran on
2026-09-24 against the live site (deploy `4100f7a`, `main` `75f049d`): pages 720/720, i18n 240/240, theme 143/143,
a11y 144/144, flows 12/12, pwa 4/4, map 1/1, console 145/146. The one console error was a stylesheet served once as
`text/plain`; 100 further loads all got `text/css`, from the server and from the service worker. The same run saw one
`502` from this container's egress proxy, so the error is put down to the test environment, not the site.

**First emulator run: a real crash found and fixed.** CI run 35943533129 (commit `afe4064`): `everyTabOpens`
passed; `addAHouseFromANewHouseIntent` failed. Opening the app from a new-house, open-house or open-screen
notification while the app was not running crashed with "Cannot navigate … Navigation graph has not been set":
`Root`'s deep-link `LaunchedEffect` ran before the `NavHost`, which sits inside the Scaffold's subcomposition, had set
its graph. The fix (`android/app/src/main/java/app/doorprints/ui/Root.kt`) waits for
`nav.currentBackStackEntryFlow.first()` before navigating. This is a user-visible bug in the shipped app (a tap on
"Are you at a house?" from Hunt mode could crash it), not a test artefact.

**Round 2 test changes** (same pull request):

- `SmokeTest` replaces the form's default name (`performTextReplacement`), waits for "House details" after Save, and
  also opens Settings (5 tab screenshots); the Assistant tab exists only with AI on, so it is not opened.
- The emulator job runs `adb shell settings put global hide_error_dialogs 1`: on the first run the launcher's "isn't
  responding" dialog appeared over the app.
- The screenshot tests wait until two frames in a row are identical before capturing (Room answers on its own
  threads; the Export screen's counts had raced), set the time zone to Asia/Kolkata, seed distinct timestamps and wrap
  the FileProvider cache reflection in `runCatching`. References must be recorded on Linux.
- `tools/live-ui` exits 1 on any failure, uses one error listener per page reset before each route, reads `rgba`
  backgrounds, checks the delete on `/` as well as Compare, and its usage line includes `npx playwright install
  chromium`.

**Second emulator run** (commit `ef0a5dd`): `everyTabOpens` passed, Settings included, and the cold-start fix
worked: the new-house form opened from the intent. `addAHouseFromANewHouseIntent` then failed: it tapped the form's
second Save button, which sits off screen at the end of the scrolling column. Fixed in `bc57361`: the test taps the
top bar's Save (`!hasAnyAncestor(hasScrollAction())`). The same commit makes the screenshot tests restore the default
time zone in `@After`.

**Third and fourth emulator runs (`bc57361`).** On `bc57361` the push run passed both smoke tests; the pull-request run
on the same commit crashed in `everyTabOpens` (a threading bug, fixed in `6376706`); both runs on `6376706` passed
(push and pull request).
The push run of "Smoke tests on an emulator (API 34)" passed `everyTabOpens` and `addAHouseFromANewHouseIntent` (a cold
start from a new-house intent, the house saved and shown in the list; screenshots in the `android-emulator-results`
artifact). The pull-request run found a **second real bug**: `everyTabOpens` crashed with "Animators may only be run on
Looper threads". The Map moved the MapLibre camera right after `currentLocation()`, which awaits a Play services `Task`
that completes on a Binder thread; under the Compose test rule's coroutine interceptor the continuation was not
dispatched back to main. The app's own main dispatcher does switch back, so it was never seen in normal use, but the
code relied on it. Fix in `6376706` (`android/app/src/main/java/app/doorprints/ui/MapScreen.kt`): `currentLocation()`
returns on the main thread (`withContext(Dispatchers.Main.immediate)`), which covers every caller: on the Map the first
framing, "go to me" and "save here", plus the house form's and the Assistant's location lookups. It was intermittent
(timing): the push run passed.

**Not verified at this writing:** Firebase Test Lab (not set up, see below); the app on a real device.

**Firebase Test Lab: off by default, at zero cost.** The workflow writes Test Lab results to an owner-created bucket
(`--results-bucket`, variable `FTL_RESULTS_BUCKET`), and a Cloud Storage bucket needs a billing account on
`doorprints`, which the zero-cost rule excludes ([07](07-secure-build-and-deploy.md) §7.2, *Cost*). So **option (a)
is the default**: Test Lab stays off (the job is skipped with a notice) and the emulator job covers the smoke tests.
Options (b), linking a billing account with a ₹0 budget alert and a bucket in `us-central1`, and (c), going back to
Test Lab's own default bucket with a workflow change and the Editor role for `ftl-runner`, are the owner's call.

**Also fixed in this pull request:** CodeQL's high finding "Incomplete string escaping" in `tools/live-ui/live-ui.js`:
the untranslated-key check escapes every regex metacharacter (`escapeRegExp`), not only dots ([06](06-test-plan.md)
TC-M-26).

**Backlog from this ticket** (the code review's out-of-scope items; also [06](06-test-plan.md) §10):

| ID | Item | Team |
|---|---|---|
| CMP-0-BL-1 | The Export screen's format cards look very tall, with empty space, in the screenshots. Either a real layout fault in `RadioCardGroup` (`Rows.kt`, `fillMaxRowHeight` in a one-column `FlowRow`) or a Robolectric artefact: check on the emulator or a device, fix if real, re-record the Export images | Android, Design |
| CMP-0-BL-2 | Emulator runs of the smoke tests in hi, ta and te and in dark mode | Android, DevSecOps |
| CMP-0-BL-3 | A tablet size (emulator run and a screenshot qualifier) | Android |
| CMP-0-BL-4 | A Map shot on the emulator that a test checks, until CMP-7 lets the Map be rendered on the JVM | Android |
| CMP-0-BL-5 | A workflow for `tools/live-ui` after the web deploy (a new workflow: owner's yes needed, §12.5 Decision 3) and a Dependabot npm entry for `/tools/live-ui` | DevSecOps, Web |
| CMP-0-BL-6 | Emulator cold-start cases for the other deep links: `EXTRA_OPEN_HOUSE` (a house's page) and `EXTRA_OPEN_SCREEN` (Settings, Export), which the `Root.kt` fix also covers but no test opens | Android |
| CMP-0-BL-7 | If the owner picks Test Lab option (c), Test Lab's own default bucket ([07](07-secure-build-and-deploy.md) §7.2 *Cost*): make `--results-bucket` in `android-emulator.yml` conditional on `FTL_RESULTS_BUCKET` instead of required (and `ftl-check` stop requiring the variable) | DevSecOps |
| CMP-0-BL-8 | The screenshot fixture has only rents under ₹1 lakh (28,000 and 22,500): lakh grouping (₹1,00,00,000), a SALE price and a dated visit row are in no reference image, only in `FormatsTest` and `FormatsParityTest` (CMP-3 design review, PR #20). Add a SALE house of ₹1 lakh or more and a visit to `ScreensScreenshotTest` in a phase that re-records images | Android |

**A new workflow, against Decision 3's constraint.** §12.5 Decision 3 asks for new checks inside the existing
workflows, with no new workflow file and a flat CI runtime. The screenshot tests follow it (inside `android.yml`,
though they add 64 Robolectric tests to its runtime). The emulator needs a job of its own on its own runner, and the
owner chose "an emulator CI job" explicitly on 2026-09-24, so `android-emulator.yml` is accepted as the exception; it
runs in parallel with `android.yml` and does not lengthen it. `tools/live-ui` is not a workflow.

**Relation to S4b-EFF-1** (§12.5 Decision 2). CMP-0 delivers part of it on Android (every screen but the Map, four
languages, both themes, one size) and none of the web screenshots it asks for; the rest (the Map, 320-360 dp, 200 %
text, landscape, the web pages) stays with S4b-EFF-1 ([06](06-test-plan.md) §10).

**Docs.** [01](01-requirements.md) v0.26 (RTM), [03](03-design.md) v0.24 (ADR-23 guard rails), [06](06-test-plan.md)
v0.38 (§16), [07](07-secure-build-and-deploy.md) v0.35 (§1, §4, §7.2), this section (v0.44),
[14](14-lead-backlog-and-handoff.md) v0.12, [README](README.md) v0.44,
[ops/firebase-test-lab-setup.md](ops/firebase-test-lab-setup.md) 0.5, `android/shared/README.md` 1.46,
`android/ui/README.md` 1.3, `web/README.md`, the CHANGELOG and CLAUDE.md.

### 13.4 CMP-2 (phase 2), done in code (PR #19)

**What was done** (commit `80b198b`, review fixes `927d54b`; PR #19 on branch `claude/doorprints-dev-continue-fzcge2`,
together with the rename of §14). The UI strings, **403 strings and 16 plurals** in each language, moved from
`android/app/src/main/res/values*/strings.xml` to `android/ui/src/commonMain/composeResources/values{,-hi,-ta,-te}/`.
`:ui` applies the `org.jetbrains.compose` plugin and generates a public `Res` in `app.doorprints.ui.res`
(`api(libs.cmp.components.resources)`, because `:app`'s screens use it until they move). Screens call
`stringResource(Res.string.x)` and `pluralStringResource`; code outside composition (click handlers, effects) calls
`Context.getString(StringResource)` in `:app`'s `ui/UiStrings.kt`, a blocking helper that CMP-3 removes. On the Compose
side placeholders are positional only and `'` is written plain: Compose resources fill in nothing else and show
Android's escapes as written ([05](05-ux-accessibility-i18n.md) §8.2 and §9.1).

**Where it differs from the plan in §13.1:**

1. **Not every string moved.** The service strings (notifications and their channels, the workers, `HuntService`,
   the manifest's label) stay Android resources, 83 strings and 4 plurals per language: a service reads them from a
   localised `Context` outside any composition. 17 keys both sides use are in both places with the same text.
2. **Services keep `context.getString(R.string.x)`**, not `getString(Res.string)`.
3. **The default locale is set on every API level**, not `Locale.setDefault` on API 26-32. Compose resources read the
   process's default locale and match that one locale only, while Android resources choose from the whole list. So
   `AppLocale.applyDefault(context)` sets `LocaleList.setDefault` to the language Android resolved (the
   `resolved_language` string in each `res/values*` folder), through `AppLocale.wrap()`, which
   `DoorprintsApp.onCreate` and `DoorprintsApp.onConfigurationChanged` also call.
4. **The APK carries only en, hi, ta and te** (`androidResources.localeFilters`, `927d54b`): without it a library's
   `values-mr` made Android resolve a phone set to [Marathi, Hindi] to Marathi and fall back to English.
5. **`StringParityTest` checks more than the keys** (TC-U-59): placeholders counted and in position, a plural's
   `other` form, no Android escapes on the Compose side, and a key in both places reading the same.

`price_per_month` is read from the Android resources by `ui/Format.kt` in `:app` (correct in services too); its
Compose copy is kept for CMP-3's common `Format`.

**Verified, and how.** Locally: the Android checks of §14 (with `-Proborazzi.test.verify=true`: the 64 screenshots
match the references recorded before this change, so no screen changed), including `StringParityTest` (4 tests,
[06](06-test-plan.md) TC-U-59) and `AppLocaleTest` (2 Robolectric tests: [mr-IN, hi-IN] gives Hindi, [fr-FR]
English; TC-U-60).

**Not verified:** CI and the emulator run on PR #19 (pending at this writing); the device-only checks of
[06](06-test-plan.md) TC-M-27 (S4b-BL-21). **Backlog from the reviews:** S4b-BL-18 (formatting follows
`locales[0]`, not the resolved language), S4b-BL-19 (`MainActivity` not exported), S4b-BL-20 (clients detect a
reset server) and S4b-BL-21 (§12.7).

**Docs.** [01](01-requirements.md) v0.28, [03](03-design.md) v0.26 (ADR-23 P2), [05](05-ux-accessibility-i18n.md)
v0.21 (§8.2, §9.1, §9.2), [06](06-test-plan.md) v0.40 (TC-U-59, TC-U-60, TC-M-27), this section (v0.46),
[14](14-lead-backlog-and-handoff.md) v0.13, `android/ui/README.md` 1.5, the CHANGELOG and the docs index.

### 13.5 CMP-3 (phase 3), done in code

**What was done** (branch `claude/doorprints-dev-continue-fzcge2`, on `main` at `7080a7f`: code `e563e2b`, tests
`3e5cdd5`, then these docs). The platform seam and the next UI files moved to `:ui` `commonMain`, package
`app.doorprints.ui` kept:

- **`PlatformServices.kt`**: `interface PlatformServices { fun isScreenReaderOn(): Boolean }` and
  `LocalPlatformServices` (a `staticCompositionLocalOf` with no default, so a root without it fails loudly). androidMain
  has `AndroidPlatformServices` (`AccessibilityManager.isTouchExplorationEnabled`, what `isTouchExploring(context)`
  read) and `ProvidePlatformServices { }`, which wraps `MainActivity`'s content and each screenshot test's. The Map,
  the Assistant and the house form read `LocalPlatformServices.current.isScreenReaderOn()` at the same moments as
  before.
- **`Format.kt`**: `Formats.rupees` (`₹` and lakh grouping, `indianGrouping`), `Formats.price` (with the
  `price_per_month` wording passed in) and `Formats.score` (one decimal, half up) in common code; `Formats.date` and
  `Formats.dateTime` through `internal expect fun formatDate` (androidMain: `java.time`'s medium date and short time for
  `<language>-IN`, as before; iosMain: `NSDateFormatter`); `expect fun appLanguage()`. The composables
  `priceText(price, priceType)` (`price_per_month` from Compose resources), `scoreText()` and `dateText()`.
  `HuntService` passes its Android `R.string.price_per_month`.
- **`LiveMessage.kt`**, **`ActionBar.kt`** and **`ResultCard.kt`** unchanged (public where `:app` uses them);
  **`DeletedHouseUndo.kt`** with Compose's suspend `getString`.
- **`ui/UiStrings.kt` is gone.** Root's "Language changed to …" and the photo-delete snackbar use the suspend
  `getString` in their effect or coroutine; the listing fill's summary is built in a coroutine; Export's *Open* and
  *Share this file* resolve their failure messages with `stringResource` and show them when the start fails, and the
  automatic share after a *Share* run reads its message in the effect. Service strings are untouched.
- **S4b-BL-18**: `appLanguage()` on Android is `Locale.getDefault()`, which `AppLocale.applyDefault` keeps on the
  resolved language; the dates and `uiLanguage()` (the theme's Indic line heights) read it, and `uiLanguage()` still
  reads `LocalConfiguration` so it recomposes on a change. A phone set to [Marathi, Hindi] now gets Hindi dates and
  the Indic typography with the Hindi text.
- **Tests**: `MapRulesTest` (22) and `IndiaViewRulesTest` (18) moved from `:app` to `:ui` commonTest (kotlin.test;
  `Math.nextUp` became the float's bits; the style JSON through `kotlinx-serialization-json` in commonTest). New
  `FormatsTest` (commonTest, 3), `FormatsParityTest` (androidHostTest, 3; TC-U-62) and two `AppLocaleTest` cases
  (TC-U-61).

**Where it differs from the plan in §13.1:**

1. **`PlatformServices` has one member.** The moved code needed only the screen-reader state; announce, share and
   URLs, pickers, permission state and work progress are listed in its KDoc for the phase that moves their first user
   (CMP-5, CMP-6), rather than defined unused now.
2. **`DeletedHouseUndo` takes two lambdas** (the house's label while it is deleted, and the write-back), not the
   Room `Repository`, which moves in CMP-4 (P4c). `:app` keeps a thin adapter, `ui/DeletedHouses.kt`.
3. **Amounts and scores need no locale.** en, hi, ta and te write them alike in their `IN` locales (`₹` before the
   digits, `#,##,##0`, a `.`, Latin digits), so only the dates are `expect`. The JVM's `NumberFormat` groups in
   thousands (`₹1,250,000`), which Android's ICU and the common code do not; the screenshots show amounts below a
   lakh, which read the same either way.
4. **Compare's prices** are read per house with `priceText` in composition (`key(h.id)`), since the row lambdas run
   outside it; Compare no longer reads `LocalConfiguration` (scores do not depend on the language).

**Left in `:app`, and why:** `HouseEntity.priceText()` became `priceText(h.price, h.priceType)` at its one caller;
the Android-only share and open intents (`ResultActions`), the listing link's `ACTION_VIEW` and the rest of the screens
move with their phases. **New backlog:** S4b-BL-22 (Export's default language still follows `locales[0]`).

**Verified, and how.** Locally, the CI command of the brief, with `-Proborazzi.test.verify=true` and
`assembleDebugAndroidTest`: green. `:app` 197 unit tests (40 fewer that moved, 2 new), `:ui` 51 host tests (5
`ServerStatusTest`, 22 `MapRulesTest`, 18 `IndiaViewRulesTest`, 3 `FormatsTest`, 3 `FormatsParityTest`), `:shared`
157; the 64 screenshots match the references, so no screen changed. `:ui:compileCommonMainKotlinMetadata` compiles the
moved code against the common libraries.

**iOS klibs on Linux.** With `-Pkotlin.native.enableKlibsCrossCompilation=true` (off in `gradle.properties`, see the
`:shared` README §5), `:ui:compileKotlinIosSimulatorArm64`, `:ui:compileTestKotlinIosSimulatorArm64`,
`:ui:compileKotlinIosArm64` and `:ui:compileTestKotlinIosArm64` pass locally: `iosMain`'s `NSDateFormatter` actual and
the moved tests compile for iOS. `shared-ios.yml` on macOS stays the check of record.

**Not verified:** TalkBack on a device (the screen-reader reads are the same calls at the same places) and TC-M-27.

**Docs.** [03](03-design.md) v0.27 (ADR-23 P3, §4.2.1), [05](05-ux-accessibility-i18n.md) v0.22 (§8.2 *Formatting*),
[06](06-test-plan.md) v0.41 (TC-U-51, TC-U-55, TC-U-61, TC-U-62), this section (v0.47),
[14](14-lead-backlog-and-handoff.md) v0.14, `android/ui/README.md` 1.6, the CHANGELOG and the docs index.

### 13.6 CMP-4 P4a (phase 4a), done in code

**What was done** (branch `claude/doorprints-dev-continue-fzcge2`, on `main` at `fccf8a1`, where PR #20 (CMP-3) was
merged: code `ddd0b3f`, test `c6d1d50`, then these docs; PR #21). The Room database moved from `:app` to `:shared`
`commonMain` as Room KMP code (Room 2.8.5, the catalog's version), with its Kotlin package `app.doorprints.data` kept:

- **`AppDatabase.kt`**: `AppDatabase` (version 2, `exportSchema`), `@ConstructedBy(AppDatabaseConstructor::class)`
  and `expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>` (KSP writes the actuals), the
  three DAOs and `Converters` (the checklist as JSON through kotlinx.serialization) unchanged. Every DAO function was
  already `suspend` or a `Flow`, so no caller changed. `MIGRATION_1_2` overrides `migrate(SQLiteConnection)`, Room's
  common overload; Room calls that overload with the framework open helper too (it wraps the database in a
  connection), so one migration serves Android and iOS.
- **`Entities.kt`** (was `:app`'s `data/Models.kt`): `HouseEntity`, `VisitEntity`, `PhotoEntity`, `HouseVisitCount`,
  `RowVersion`, unchanged.
- **Build**: plugins `androidx.room` (`room { schemaDirectory("$projectDir/schemas") }`) and KSP in `:shared`, with
  `room-compiler` for `kspAndroid`, `kspIosArm64` and `kspIosSimulatorArm64`; `api(room-runtime)` in commonMain;
  `sqlite-bundled` 2.6.2 in iosMain only. `:app` no longer applies KSP, and `room-ktx` is gone (empty since Room 2.7).
- **iosMain**: `iosAppDatabase()`: `doorprints.db` in the app's Application Support folder, `BundledSQLiteDriver`,
  `Dispatchers.IO`, `MIGRATION_1_2`. Compile-only; nothing calls it before the iOS shell (CMP-8).
- **`:app`**: `data/AppDatabaseFactory.kt` holds `AppDatabase.create(context)` (an extension on the companion, so
  the two callers only gained an import): `DatabaseFile.resolve(context)` first, then `Room.databaseBuilder` with
  `MIGRATION_1_2` and **no driver**, so Room keeps the framework SQLite through its open helper as before.
  `HouseEditScreen` reads `contactPhone` into a local: Kotlin does not smart-cast another module's public property.

**How upgraded installs keep their data.** (1) The **identity hash** is unchanged: the schema folder moved as is to
`android/shared/schemas/app.doorprints.data.AppDatabase/2.json` (git sees a pure rename; the build rewrote nothing),
and the generated `AppDatabase_Impl` for Android, `iosArm64` and `iosSimulatorArm64` each has
`RoomOpenDelegate(2, "539964c2013f14439605fab0d18a142a", …)`. `RoomSchemaTest` moved to `:shared` `androidHostTest`
and checks the JSON and the Android `AppDatabase_Impl`. (2) The **file** is the same: `DatabaseFile` (the
`househunt.db` → `doorprints.db` move) runs before Room opens, as before; `DatabaseFileTest` passes unchanged. (3)
The **SQLite** is the same: no driver on Android, so the journal mode (WAL where the device supports it), the
connection pool and the invalidation tracking are the framework open helper's, as before; the APK carries no bundled
SQLite library. (4) The backup format is untouched (`CanonicalSampleTest` and `BackupRoundTripTest` pass).

**Migration test** (R-06, TC-U-63). `AppDatabaseMigrationTest` (`:app`, Robolectric, 2 tests). No `1.json` was ever
exported (the export started at version 2), so each test writes a version-1 file with `2.json`'s SQL minus
`photos.deleted`. One runs Room's `MigrationTestHelper` (framework driver) and validates the result against the
committed `2.json`, which the test packs as the helper's assets (Robolectric serves only the app's own assets, and
nothing is added to the APK). The other opens a version-1 `househunt.db` through `AppDatabase.create`: the file
moves, migrates and the DAOs read the rows. A migration without `NOT NULL DEFAULT 0` fails both (checked by hand).

**Left in `:app`, and why:** the builder and `DatabaseFile` (the `Context`, `java.io.File`, the pre-rename file
names); the `Repository`, whose `withTransaction` and `invalidationTracker.createFlow` are Android API (P4c,
**S4b-BL-23**); the entity ↔ DTO and export mappers (`Mappers.kt`, `ExportMappers.kt`), which the `Repository` and the
export use (P4c); settings and the API key (P4b). **New backlog:** S4b-BL-23, S4b-BL-24 (Room never opened on iOS).

**Verified, and how.** Locally, the CI command of the brief with `-Proborazzi.test.verify=true` and
`assembleDebugAndroidTest`: green. `:app` 197 unit tests (2 moved out, 2 new), `:shared` 159 host tests (2 moved
in), `:ui` 51; the 64 screenshots match, so no screen changed. `:shared:compileCommonMainKotlinMetadata` compiles
the database against the common libraries.

**iOS klibs on Linux.** With `-Pkotlin.native.enableKlibsCrossCompilation=true`, KSP runs for both iOS targets and
`:shared:compileKotlinIosSimulatorArm64`, `:shared:compileKotlinIosArm64`, `:shared:compileTestKotlinIosSimulatorArm64`,
`:shared:compileTestKotlinIosArm64`, `:ui:compileKotlinIosSimulatorArm64` and `:ui:compileTestKotlinIosSimulatorArm64`
pass, `iosAppDatabase()` included. `shared-ios.yml` on macOS stays the check of record.

**Not verified:** an upgrade on a real phone from a build of `main` before this change (the kind of check TC-M-27
item 4 makes) and Room on iOS (S4b-BL-24).

**Docs.** [01](01-requirements.md) v0.29 (RTM FR-019), [03](03-design.md) v0.28 (ADR-23 P4a, §4.2.1, §6.2, R-06
closed), [06](06-test-plan.md) v0.42 (TC-U-36, TC-U-63), this section (v0.48), [14](14-lead-backlog-and-handoff.md)
v0.15, `android/shared/README.md` 1.48, `android/ui/README.md` 1.7, the CHANGELOG and the docs index.

## 14. Owner request of 2026-09-24: legacy House Hunt names become Doorprints

**The request.** "The app needs to be Doorprints and also references of legacy House Hunt needs to be changed to it"
(owner, 2026-09-24). The product was renamed on 2026-09-22 (S3-07, [03](03-design.md) ADR-13), but about 1,300
references to the old name were left in code packages, types, storage keys, database, image and tool names. Decision
record: [03](03-design.md) ADR-24.

**What was done** (PR #19 on branch `claude/doorprints-dev-continue-fzcge2`, after CMP-2's `80b198b`: `0d502f6`
Android, `bc9d5ea` web, `828845a` backend, `6f392dc` docs; review fixes `927d54b`):

| Area | Renamed | Carry-over for what is stored |
|---|---|---|
| Android | Packages `app.doorprints`, `app.doorprints.ui` (`:ui` resources class `app.doorprints.ui.res.Res`), `app.doorprints.shared`; namespaces the same; `DoorprintsApp`, `DoorprintsRoot`, `DoorprintsTheme`, `DoorprintsColors`, `Theme.Doorprints`, log tag `DoorprintsApi`; Room file `doorprints.db` and schema folder `app/schemas/app.doorprints.data.AppDatabase/` | `DatabaseFile` renames `househunt.db` and its `-wal`, `-shm`, `-journal` before Room opens it; `LegacyWorkerFactory` runs WorkManager jobs queued under `com.househunt.app.*`; the launcher `activity-alias` keeps the component name `com.househunt.app.MainActivity`; the Keystore alias `house_hunt_api_key_v1` is kept |
| Web | Storage keys `doorprints.lang`, `doorprints.api-config`, `doorprints.*` for every `hh.*` key | `core/storage-keys.ts` moves each old key in localStorage and sessionStorage before anything reads storage; "Remove all data" keeps the language and server settings through an explicit list; *Disconnect* (`ConfigService.clear()`) also removes a `house-hunt.api-config` that could not be moved (`927d54b`) |
| Backend | Packages `app.doorprints.server.*`, `DoorprintsApplication`, Maven `app.doorprints:doorprints-api`, database, user and compose volume `doorprints`, image tags `doorprints-api`/`doorprints-db`, MCP tool `askDoorprints` | None needed for a server that sets `DB_URL`/`DB_USER`/`DB_PASSWORD`; a local compose database is dumped and restored ([08](08-operations-runbook.md) §11). Flyway migrations are not edited |
| CI | Keystore temp file `doorprints-release.jks`, database and image names in `backend.yml` and `ai-evals.yml`, path filters | – |

**Kept on purpose** (each is justified in ADR-24): the Keystore alias, the old names inside the carry-over code, the
Flyway migrations, the `HH_*` signing secret names, history in change logs and the CHANGELOG, the `.gitleaksignore`
fingerprints (they name paths in old commits), and ordinary English ("house hunting", "a house hunter").

**Checks run.** Android: `assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest`, the
common and iOS metadata compiles and `assembleDebugAndroidTest`, with `-Proborazzi.test.verify=true` (all 64 reference
screenshots match): **235 `:app` unit test runs** (179 `@Test` methods counted in `android/app/src/test` after
`927d54b`; the 8 of `ScreensScreenshotTest` run in 8 configurations, 64 screenshots). Web: `ng test` (37 files, 473
tests, with `927d54b`) and `ng build`. Backend: `mvn -B -ntp verify` on JDK 25
against the `backend/db` PostGIS image (285 tests, one skipped: the golden-set eval without a key). Not run: the
instrumented tests on an emulator (`android-emulator.yml`) and the live web UI test (`tools/live-ui`, after the merge).
