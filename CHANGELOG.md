# Changelog

All notable changes to Doorprints (called House Hunt until 2026-09-22; repository `Sriram-Codes-SW/doorprints`,
renamed from `house-hunt` on 2026-09-22) are recorded here. The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Security fixes reference the finding ids (F-xx) in the
[threat model](docs/02-threat-model.md); sprint detail is in the [sprint log](docs/10-sprint-log.md).

Versions: Android `versionName` (`android/app/build.gradle.kts`), backend `pom.xml` and web `package.json` move
together. Until a release is tagged, everything on `main` after 0.1.0 is listed under **Unreleased**.

The version headings are not links yet: they will link to GitHub compare/tag URLs (link reference definitions at
the end of this file) once `v0.1.0` is tagged (C-02 in the [sprint log](docs/10-sprint-log.md)).

## [Unreleased]

**Current CI status (2026-09-22): green on `19006bc`**, the tip of `main` — Backend ✅, Security ✅,
Android ✅ and Shared iOS compile ✅. `19006bc` changes backend test code only, so of the five workflows only Backend
and Security are triggered by the push; Android, Shared iOS compile and Web are path-filtered. The Android and
shared-iOS trees are unchanged since `8f583af` and green; the last green **Web** run is `0e4e22a`. Quote `19006bc`
as "the last green build", and read the older green commits below as the state at the end of their own sprint rather
than the current one. Run ids and the path-filter caveat: [sprint log](docs/10-sprint-log.md) 9.2.

Sprint 2 (2026-09-22): at the end of that sprint all four CI workflows (Backend, Android, Web, Security) were green
on `f7da5ab` and `0e4e22a`. See the [sprint log](docs/10-sprint-log.md).

Sprint 3 (2026-09-22): the first real AI eval run (`ai-evals.yml`) found two defects, E-01 (Gemini embeddings failed)
and E-02 (false PASS in the scorecard). Both are fixed (see *Fixed*): commit `6a348cc` passed the Backend and Security
workflows (Android and Web had no changes). The first successful real Gemini eval run on 2026-09-22 (Actions run
35720654442, on commit `6a348cc`, confirmed) ran all 13 cases; 12/13 passed (ask-02-filtered-parking failed only its
citation check), and every metric passed except `citationPrecision` (0.86, threshold 0.90) because of that one
contrast citation (E-03; the AI team is adding `allowedCitations` to the golden set and tightening the Ask prompt). AI
stays off by default (AI-001, AI-012). C-13 (F-30, contact names sent to the LLM provider) is closed as fixed by lead
decision (see *Security*). Sprint 4 candidates (offline map areas, AI map filter, voice notes, neighbourhood summary,
alert one-liners with directions, local labels in AI citations, an AI-enabled CI smoke test, a golden-set fixture that
tests redaction) are listed in the sprint log for the product owner; they are not committed scope. The product is
renamed to **Doorprints** (see *Changed*; stories S3-07 and S3-08 in the sprint log).

Product-owner decisions (2026-09-22): the repository is renamed to `Sriram-Codes-SW/doorprints` and is public; AI
access policy (on-device AI for guests, cloud AI only for the owner and invited users on a paid, hard-capped key,
bring-your-own-key rejected); Vertex AI added as a provider next to AI Studio; Google Cloud trial plan. See the
[sprint log](docs/10-sprint-log.md) section 8. The Vertex AI provider code landed in the same change (AI team, see
*Added* and *Changed*); it is off unless `APP_AI_ENABLED=true` and `AI_PROVIDER=vertex`, and waits for its first CI run
and the owner's setup in [docs/ai/vertex-setup.md](docs/ai/vertex-setup.md) (C-24).

Sprint 3.5 "KMP foundation" (2026-09-22, commit `8f583af`): the Android code is Kotlin Multiplatform-ready with a
`:shared` module that CI also compiles for iOS; no iOS app ([ADR-14](docs/03-design.md#14-architecture-decision-records),
[sprint log](docs/10-sprint-log.md) section 9). On `8f583af` the new Security job `gradle-dependency-graph` failed at submission (most likely the repository's
dependency graph setting is off). Commit `feb0294` makes only its submit step non-blocking
(`dependency-graph-continue-on-failure: true`), so **a green Security run no longer proves the graph was submitted**:
only the notice `Submitted dependency-graph-reports/...` in that job's log does ([07](docs/07-secure-build-and-deploy.md) §1).
The CI results of `8f583af` and `feb0294` are in the [sprint log](docs/10-sprint-log.md) 9.2. Later the same day the
owner finished the Google Cloud setup for Vertex AI (project `doorprints-ai`; chat in `asia-south1`, embeddings on
`global`; trial credit ends 22 Dec 2026) and approved Sprint 4b additions: Hunt mode reminders, hunting areas and a
foreground-first location permission model ([sprint log](docs/10-sprint-log.md) section 10). The Sprint 4b decisions
change nothing in the apps yet; they are requirements FR-083..FR-088 and PRV-024..PRV-027. The first `provider=vertex`
eval run (35753477789, `gemini-3.5-flash` + `gemini-embedding-2` on `global`) failed only on `citationPrecision` 0.78
(7/9); commit `feb0294` answers it (see *Changed*) without lowering any threshold. The re-run is **done and green**:
Actions run **35758157317** (2026-09-22, `provider=vertex`, `gemini-3.5-flash` in `asia-south1` + `gemini-embedding-2`
on `global`, golden set v0.5, commit `19006bc`) passed **13/13 cases and every metric** in 198 s, with two `503`
retries from the provider that the harness's bounded retry absorbed. E-03 is therefore closed by a real run, not only
by code. The trial **credit check** (vertex-setup step 10) is done: on 2026-09-23 the owner found **₹45 of the trial
credit** used for the Vertex eval runs, so their cost came off the credit ([sprint log](docs/10-sprint-log.md) 10.2).
Still open from the Vertex checklist: vertex-setup step 9 (capture real responses for the contract tests) and flipping the `ai-evals.yml`
`provider` default to `vertex` ([vertex-setup](docs/ai/vertex-setup.md) status, C-24 in the
[sprint log](docs/10-sprint-log.md)).

Product-owner decision (2026-09-22, PO-9): **both AI providers stay.** `AI_PROVIDER` keeps `aistudio` (default) and
`vertex` as a real switch; which becomes the long-term default is decided later. The owner takes the billing risk
explicitly, and the cost controls are unchanged (AI off by default, spend cap budget, trial credit ends 22 Dec 2026).
There is no removal story for either provider ([sprint log](docs/10-sprint-log.md) section 10.3).

Sprint 4a (2026-09-22, **not pushed yet**): the web app becomes local-first and installable, and both apps can write
six offline copies of everything and read a backup back. Detail in the [sprint log](docs/10-sprint-log.md) section 11.
The format is pinned once, in [docs/schemas/README.md](docs/schemas/README.md), and implemented three times (server,
Android, web) with golden files holding the three together. Two things are deliberately recorded as unfinished: **the
web app can export but cannot yet import** (Sprint 4b), and the live web app at **https://doorprints.web.app** is
**not deployed yet**: the owner's Firebase Hosting setup is done, and the first push of this change set deploys it.
The GitHub Pages deployment this change set first added — which would have served the app without security response
headers, on an origin shared with the owner's other Pages sites — was replaced before it was ever pushed, first by a
Cloudflare Pages plan and then by Firebase Hosting (owner decisions 2026-09-23, see *Changed*).

Owner decisions (2026-09-23, later that morning): **Firebase Hosting at `https://doorprints.web.app`** replaces the
Cloudflare Pages plan, which the owner rejected before it was set up because a `pages.dev` address reads as a test
site; the address was chosen with a brand advisor, whose brief is now [docs/12](docs/12-brand-and-naming.md) (address,
fallbacks, brand screening, **custom domain only later, after web import ships**, naming guidelines, and the
import / backup / copy vocabulary in four languages). The **import definition is approved** for Sprint 4b (story
S4b-00): only a Doorprints *Full backup* — a backup ZIP or a bare `data.json` — can be imported; it never changes
settings, the server address, the API key, Hunt-mode or AI settings; it validates first, previews, merges by newest
edit and never deletes; and the web gets "add as copies" too ([requirements](docs/01-requirements.md) 6.9,
FR-089..FR-097; [backup format](docs/schemas/README.md) section 0). Importing from other apps or spreadsheets is a
possible later feature with its own name.

Whole-app UX audit (2026-09-23, **not pushed yet**): the Senior Lead UX Developer's audit of both clients, the
go-ahead for the first deploy, is **approved** — Android at gate round 10 (`android/shared/README.md` 1.24–1.33),
Web at gate round 4 (`web/README.md` audit rows and rounds 1–3), each with a few minors carried as Sprint 4b
candidates. It is a code-review sign-off: the Android device checks (one of them, the Indic map labels, a release
gate) and the web phone checks are still to run ([sprint log](docs/10-sprint-log.md) 11.7; see *Changed* and
*Fixed*). Owner decisions of the same day ([sprint log](docs/10-sprint-log.md) 12.5, 12.6): **no Play Store release
and no public server until the release security gate exists and passes**, and once it exists every web deploy
passes it too. The gate has an automated part (the existing CI checks plus a MobSF static scan, a ZAP API scan
against the backend started in CI, authorisation tests, `testssl.sh` for any self-hosted server, an AGPL-compatible
licence scan and the LLM prompt-injection set), a manual one-hour list per release
(`docs/13-release-security-checklist.md`, Sprint 4b) and a deep pentest before the Play Store launch and before
Sprint 5 sign-in. The first web deploy gets the existing CI plus an OWASP ZAP baseline, the header check and a
storage audit, run before the address is announced, with rollback through Firebase release history on any finding.
The **first release's Definition of Done**: every review gate approved, every workflow that runs on the merge commit
green, those checks passed, the web icons redrawn from the Android mark, and **Hindi, Tamil and Telugu shipped marked
*under review*** (machine-drafted, native-speaker review pending). Six review-efficiency measures (screenshot tests,
design-first specs, a component kit, review rules, a buddy pre-check, a first-pass approval metric) and the
security playbook become Sprint 4b stories, and six of seven further process improvements are approved (smaller
batches on hold), with new lint, Semgrep and i18n checks going inside the existing workflows, not new ones; the two self-check playbooks are published as
[docs/05 §15](docs/05-ux-accessibility-i18n.md) and [docs/07 Appendix A](docs/07-secure-build-and-deploy.md). The
licence change from MIT to `AGPL-3.0-only` with a trademark notice is approved as the next, separate item:
**`LICENSE` is still MIT** until it lands.

### Added

- **Offline copies in six formats** (Sprint 4a, FR-042..FR-046): HTML, PDF, CSV (a ZIP of `houses.csv`,
  `scores.csv`, `visits.csv`, `photos.csv`), XLSX, Markdown and the exact JSON backup `doorprints-backup/1`. Built
  **on the device, offline, with no account and no server** — Android from Room, the web app from IndexedDB — and
  deterministic: the same data and options give the same bytes apart from the export time on the cover. Options:
  scope, photos, contacts (on by default, with a warning), language (en/hi/ta/te) and "include rejected". Android
  saves through the Storage Access Framework or the share sheet, the web app downloads or uses Web Share. The
  shared writers live in `android/shared/.../shared/export/` and are mirrored in `web/src/app/export/`.
- **Import a backup** (FR-047): validation before any write (format, per-entry SHA-256, entry count, size, ratio,
  and a zip-slip path check on every entry), a preview of what would change, then merge by last-write-wins or
  "import as a copy" with fresh ids. Importing the same file twice changes nothing. **Android and the server only
  so far.** Server: `POST /api/import`, with `?dryRun=true` as the preview and 413 for an over-large body or too
  many rows. Android's preview also warns, in all four languages, when a newer row in the file has no checklist and would
  clear scores this phone has; an absent or `null` `checklist` in a file reads as "no scores".
- **Android: bring back houses deleted on this phone, and keep your own edits** (Android rounds 10–12,
  `android/shared/README.md` 1.12–1.14; not pushed yet). In a merge, houses the backup has but the phone deleted are
  counted as "Deleted on this phone; they stay deleted", and an opt-in switch, "Also bring back *n* houses deleted
  on this phone" (off by default; **Bring them back** when that is the only thing the backup would change), restores
  exactly those houses with their own ids and marks them as edited now, so the undelete survives the next sync.
  After a delete that has already synced, the house's visits are relinked and its photos written under fresh ids
  (the server never takes a deleted photo id back); **this last part is not yet confirmed on a device** (device
  check 10b), so the release notes promise only that the houses come back with their own ids. The Replace dialog
  counts houses and visits apart, names up to five houses and offers **Keep mine, add only what's new** first; a
  stopped merge offers **Finish import**; the result says "Added … Updated …" with only the non-zero parts, and lists
  of any length keep every item in all four languages (`import_list_middle`, `JoinListTest`). Export: a *Full
  backup* made with narrowed options shows an amber note naming what it leaves out, with **Use everything**, and is
  saved as a "partial backup"; results nobody was told about stay on screen until seen; notifications are asked
  for once, in context. Tests: `ImportPlanTest` (undelete, keep mine, relink, and the preview equal to the plan for
  every flag combination over eight phone states), `BackupCompletenessTest`, `JoinListTest`, `ImportStartOnceTest`.
- **Android backups keep visits that belong to no house yet** (Hunt mode's dwells at places that are not houses):
  a JSON backup of *All houses* carries them, last in `data.json`; the readable copies leave them out. The web
  app's backup does not carry them yet.
- **Optional weekly backup on Android** (FR-048, off by default): into a folder granted once, while charging and
  with the battery not low, keeping the last 4 files by default. There is **no network constraint** — the file is
  written straight into the granted folder, so there is nothing to upload. If the folder grant is revoked the
  feature turns itself off with a reason, instead of failing quietly every week. Turning the backup off releases the
  folder's access and forgets the folder, so turning it on again asks for a folder again.
- **The web app is local-first and installable** (FR-070..FR-073): IndexedDB (`doorprints` v1) holds houses,
  visits, photo blobs and settings; no route is guarded any more and the Connect page only adds sync; a
  hand-written service worker, stamped per build, caches **this build's own files** (never an API response, nothing
  cross-origin) and starts the app from the cache first; install is offered on *Your data* and once as a banner after
  the first saved house ("Not now" lasts 30 days), with the Add-to-Home-Screen steps on iOS; the build writes the
  manifest's `id` as the absolute deployment path (`/` on Firebase Hosting, where the site is served from the root); phones get a bottom navigation
  bar; an update prompt instead of a silent reload; `navigator.storage.persist()` with an honest
  warning when the browser will not promise durability, and a memory-only fallback that says so when IndexedDB is
  blocked.
- Tests: golden-file exports on both platforms from the same fixture (`ExportGoldenTest`, `exporters.spec.ts`),
  `ImportPlanTest`, `BackupTest` (zip slip, foreign format, duplicate ids), `ExportStringsTest` (all four languages
  complete), `BackupRoundTripTest`, `BackupApiTest`, and the canonical `docs/schemas/backup-sample.json`: the server and
  Android (`CanonicalSampleTest`) read it, and `BackupParityTest` (backend) checks that the web writer's golden copy
  is identical to it and that all three readers use the same 16 MiB `data.json` cap.

- **Kotlin Multiplatform module `android/shared`** (Sprint 3.5): models and wire names, score, sync rules and outcome
  codes, stay detection, street alerts, distance, DTOs and the API client, for Android and (compile-only)
  `iosArm64`/`iosSimulatorArm64`. One version catalog `android/gradle/libs.versions.toml`.
- CI: **`shared-ios.yml`** ("Shared iOS compile", macOS, compile-only, path-filtered to shared code); `android.yml`
  runs `:shared:testAndroidHostTest`; `security.yml` job `gradle-dependency-graph` sends the Android dependency graph
  to Dependabot alerts (the only job with `contents: write`, never on pull requests).
- Android tests: `ApiClientContractTest` (Ktor `MockEngine`, TC-U-35), `RoomSchemaTest` (Room identity hash of
  database version 2, TC-U-36), `ModelMappingTest` (TC-U-10), `HouseScoreTest`, `ModelTest`, `IsoTimeTest`,
  `RetryPolicyTest`; the iOS compile is TC-U-37. Room schema export is on (`app/schemas/.../2.json` committed).
- Docs: ADR-14 and the `:shared` module boundaries ([03](docs/03-design.md) 4.2.1); Sprint 4b design for Hunt mode
  reminders, hunting areas / area wake-up and the location permission model ([11](docs/11-feature-parity-and-export-spec.md)
  v0.6, 5.16..5.18; requirements FR-083..FR-088, NFR-030, SEC-049, PRV-024..PRV-027 in [01](docs/01-requirements.md)
  v0.12); Google Cloud trial end checklist ([runbook 10.4](docs/08-operations-runbook.md)) and
  [vertex-setup](docs/ai/vertex-setup.md) step 14; the owner's Vertex setup results as a worked example (step 8);
  README refresh with CI badges, platforms, AI access policy, roadmap and a full documentation index.

- **Vertex AI provider** (AI team; AI-016, C-24), chosen with the new setting `AI_PROVIDER` (`app.ai.provider`):
  `aistudio` (default, unchanged behaviour: Gemini API key `AI_API_KEY`) or `vertex` (Google Cloud Vertex AI with
  Application Default Credentials, no API key). Vertex chat uses Spring AI's Google GenAI chat starter
  (`spring-ai-starter-model-google-genai`, new backend dependency managed by the Spring AI 2.0.1 BOM; its
  auto-configuration stays off unless `vertex` is selected) with the app's own google-genai `Client`
  (`VertexAiConfiguration`, `GoogleAccessTokenSource`, bounded retries). Vertex embeddings use the new
  `VertexEmbeddingModel` (`:embedContent` for `gemini-embedding-2`, `:predict` for `gemini-embedding-001`, one text
  per call). New settings `GCP_PROJECT_ID` (required with `vertex`), `GCP_LOCATION` (default `asia-south1`),
  `AI_VERTEX_EMBEDDING_LOCATION`, `AI_VERTEX_ENDPOINT`, `AI_VERTEX_API_VERSION` (default `v1beta1`); the standard
  `GOOGLE_APPLICATION_CREDENTIALS` is honoured through ADC. Owner setup: [docs/ai/vertex-setup.md](docs/ai/vertex-setup.md);
  settings: [runbook 1.1](docs/08-operations-runbook.md), [07 section 7](docs/07-secure-build-and-deploy.md#7-environment-variables).
- `setupHint` property on the AI `503` problem when Vertex AI answers 401, 403 or 404 (`AI_PROVIDER=vertex` only): an
  owner-facing hint that names the setting to change (`GCP_LOCATION` for chat, `AI_VERTEX_EMBEDDING_LOCATION` for
  embeddings, with a location to try, or the credentials for a 401). It holds env-var names and the configured location
  only, never the project id or the provider message. The eval harness does not retry such a `503` and lists each hint
  once in the scorecard warnings. See [03 section 9](docs/03-design.md) and [runbook section 9](docs/08-operations-runbook.md).
- `AI_INDEX_ON_CHANGE` (default `true`): `false` stops embedding each house right after a save; only
  `POST /api/ai/reindex` embeds then (deletes still leave the index at once). The eval harness uses `false`.
- `ai-evals.yml`: new inputs `provider` (default `aistudio`; choose `vertex` after
  [docs/ai/vertex-setup.md](docs/ai/vertex-setup.md) steps 1-8; the default moves to `vertex` only after the owner's
  step-10 run) and `embedding_model`. With `vertex` the job authenticates through Workload Identity Federation (`google-github-actions/auth`, `id-token: write` on that job
  only; secrets `GCP_WIF_PROVIDER`, `GCP_SA_EMAIL`, variables `GCP_PROJECT_ID`, `GCP_LOCATION`, optional
  `AI_VERTEX_EMBEDDING_LOCATION`); no key is stored. `AI_API_KEY` is only passed on the `aistudio` path.
- Tests for the Vertex AI provider: `VertexGenerateContentContractTest` and `VertexEmbeddingContractTest` (TC-AI-16),
  `ProviderErrorsTest` (TC-AI-18), `VertexSettingsTest` and `VertexAutoConfigurationTest` (TC-AI-19), provider cases
  in `AiDefaultsEnvironmentPostProcessorTest` (TC-AI-20), the quota stop in `HouseIndexerTest` and the STOPPED verdict
  in `EvalScorerTest` (TC-AI-21), `AiExceptionHandlerTest` (TC-AI-22). The Vertex response bodies follow the SDK
  and Google's reference; they were not captured from a live call yet.
- Requirement **AI-017** (planned, manager review): when the Google Cloud spend cap trips, the API returns a clear
  "cloud AI paused" problem that is not retried, pinned by a contract test; today's quota path covers HTTP 429 only.
- MIT `LICENSE` and `SECURITY.md` (private vulnerability reporting through GitHub Security Advisories).
- Docs: sprint log section 8 (product-owner decisions of 2026-09-22 and Sprint 4 candidates C-22..C-28: CodeQL, PR
  flow with an always-running `CI summary` check, Vertex AI provider, AI access tiers, Firebase Test Lab on
  Indian-market devices, Cloud Run + Cloud SQL staging for the trial period, Speech-to-Text prototype); runbook
  section 10 (public repository settings, paid AI key with a hard cap, Google Cloud $300 trial and tear-down) and
  incidents IR-8 (vulnerability report) and IR-9 (AI spend); build guide 3.1 (`CI summary` check), 3.2
  (public-repository rules) and 6.5 (time-limited staging on Google Cloud).
- Requirements (planned): AI-013 cloud AI only for the owner and invited users, AI-014 on-device AI (Gemini Nano via
  the ML Kit GenAI Prompt API) for guests where supported, otherwise hidden, AI-015 hard cost cap, AI-016 Vertex AI and
  AI Studio as switchable providers; PRV-022 real user data only on a paid tier or Vertex AI, PRV-023 on-device AI
  sends nothing off the phone.
- API key rotation without downtime: optional second key `APP_API_KEY_NEXT`, accepted alongside `APP_API_KEY`
  while clients move to the new key (SEC-017). Procedure: [runbook 5.1](docs/08-operations-runbook.md).
- Signed Android release builds: `app/build.gradle.kts` reads `HH_KEYSTORE_FILE`, `HH_KEYSTORE_PASSWORD`,
  `HH_KEY_ALIAS` and `HH_KEY_PASSWORD`; the `android.yml` job `release` (push to `main` or manual only) builds,
  verifies with `apksigner` and uploads the release APK artifact (`doorprints-release-apk` since the rename) when
  the secrets exist (F-11, part).
- Web unit tests (Vitest + jsdom through `@angular/build:unit-test`): score, config storage, API interceptor,
  translation dictionaries and formatting. `npm test`, `npm run test:ci`; run in `web.yml`.
- Android unit tests: `ChecklistScoreTest`, `SyncRulesTest`, `StreetAlertsTest`, more `ServerUrlTest` cases. The
  sync keep-local rule and the street-alert rule now live in the pure classes `SyncRules` and `StreetAlerts`.
- Backend tests for the 32-character minimum and the dual key (`ApiKeyFilterTest`,
  `acceptsTheNextKeyDuringRotation`).
- AI evaluation harness (AI team): golden set `docs/ai/evals/golden-set.json` v0.2 with thresholds,
  `EvalScorerTest` (scoring, every build) and `GoldenSetEvalTest` (real model, only when `AI_API_KEY` is set;
  writes `target/ai-eval-report.md`). See [docs/ai](docs/ai/).
- `ai-evals.yml`: manual GitHub Actions run of the golden-set eval against a real model (`workflow_dispatch` only,
  needs the `AI_API_KEY` repository secret; scorecard in the job summary and the `ai-eval-report` artifact).
  See [07 section 1](docs/07-secure-build-and-deploy.md#1-pipeline-overview).
- `CHANGELOG.md` and the [sprint log](docs/10-sprint-log.md).
- `web/package-lock.json` committed (generated by CI), so `web.yml` installs with `npm ci` (C-01, part).
- AI embedding settings (only used with `APP_AI_ENABLED=true`): `AI_EMBEDDING_PROVIDER` (`google-genai`, default,
  or `openai`; **Ollama needs `openai`**), `AI_EMBEDDING_API_KEY` (defaults to `AI_API_KEY`), `AI_EMBEDDING_BASE_URL`
  and `AI_EMBEDDING_TASK_TYPE`. See [runbook 1.1](docs/08-operations-runbook.md).
- AI tests `GeminiEmbeddingModelTest`, `HouseIndexerTest`, new `EvalScorerTest` verdict cases and embedding provider
  cases in `AiDefaultsEnvironmentPostProcessorTest` (TC-AI-11..14 in the [test plan](docs/06-test-plan.md)).
- Contact redaction tests `ContactRedactorTest`, `AskContextRedactionTest`, `ToolResultRedactionTest` and new
  `HouseDocumentsTest` cases (TC-AI-15), and provider contract tests `GeminiEmbeddingContractTest` and
  `GeminiOpenAiChatContractTest` that replay recorded Gemini payloads through the real clients (TC-AI-16).
- The dev `docker-compose.yml` passes every AI setting (`APP_AI_ENABLED`, `APP_MCP_ENABLED`, `AI_*`) from the shell
  or a `.env` file, with Gemini and local Ollama examples in its header; see
  [07 section 7](docs/07-secure-build-and-deploy.md#7-environment-variables).

### Changed

- **App icon: three small footprints (option C).** On Android (`ic_launcher.xml`, and the status-bar icon
  `ic_stat_doorprints.xml`) and on the web (`favicon.svg`, `icons/favicon-32.png`, `icon-192.png`, `icon-512.png`,
  `icon-maskable-512.png`, `apple-touch-icon.png`), the two large gold prints beside the door (a sole with two toes; plain ovals in the favicon)
  are now three small footprints walking up to it, left, right, left, each with a sole, a heel and four toes. Colours, the door and the
  layout of each icon are unchanged; the favicon now shows the same prints ([12](docs/12-brand-and-naming.md) N-06,
  [14](docs/14-lead-backlog-and-handoff.md) N3).
- **CI runs on every branch** (owner decision, 2026-09-23: "We need to have the pipelines run on branches as well
  because we need to be sure that the code is right before merging into main"; [sprint log](docs/10-sprint-log.md)
  12.5 Decision 5). `backend.yml`, `web.yml`, `android.yml`, `shared-ios.yml`, `security.yml` and `codeql.yml` run on
  a push to any branch with their path filters unchanged; pull requests to `main` still run (CodeQL still not on pull
  requests). Deploying (`web.yml` `firebase-setup` / `deploy-firebase`), release signing (`android.yml`
  `release-signing-check`, which now requires `main` too, and `release`, which runs only on its output) and the Android dependency-graph submission stay
  on `main`; the backend image build and non-root check (never pushed) now also run on branches. For signing that
  guard holds only while `android.yml` is unmodified, because a push runs the pushed branch's copy of the workflow and
  the `HH_*` signing secrets are still repository secrets; moving them into a `release` environment restricted to
  `main` is backlog ticket S4b-BL-8. A newer push to a branch or pull request cancels the older run; an in-progress
  run on `main` is never cancelled, and when several pushes queue up only the newest waiting run starts. Required status checks stay off, and
  with a pull request open each push runs every triggered workflow twice. Before merging, check the latest run of each
  triggered workflow on the branch and bring the branch up to date with `main`. Not yet run in CI. See
  [07 section 1](docs/07-secure-build-and-deploy.md#1-pipeline-overview) (*Branch runs*).
- **Android, whole-app UX audit** (Sprint 4a, before the first deploy; `android/shared/README.md` 1.24–1.34, not
  pushed yet). Location and notifications are asked only in context (the Hunt switch, *Save house here*, *My
  location*, *Use my current location*, *Plan visits*), never on arrival; *Approximate* location is its own state with
  one calm amber note per screen and *Turn on precise location*; a refusal is said once, by that note, with a "reject"
  haptic and a one-sentence snackbar when a tap starts nothing. The Map lays itself out from measured sizes: the top
  band stops above the controls and scrolls, a short map (under 480 dp) puts the controls in one row, markers differ
  by size, ring and opacity with a legend (`MapLegend`) whose place follows its measured width, MapLibre's attribution
  is lifted above the legend and its logo is off, and the map is north-up (rotation, tilt and the compass off). The
  house form gains the unsaved-changes dialog, photo undo, a not-found state, a photo viewer, Latitude / Longitude
  fields, a segmented checklist (0–5, then "–") and a 640 dp column; Compare keeps its selection and has an empty
  state; the house list's first run offers **Add a house on the map** and **Import a backup** (was *Restore from a
  backup*); a copy import can be undone for 24 hours, from the Import screen or, with a confirmation, from the list.
  Settings and the Assistant keep the last result or error card in place, dimmed, while a new run is busy
  ("Updating…" / "Thinking…" / "Planning…"). Dangerous confirmations use an error-outlined button; selected chips
  and the active tab are teal (`secondaryContainer` = `--primary-soft`), never the star amber. 489 string resources in
  each of en/hi/ta/te. Tests: `MapRulesTest`, `LocationAccessTest`, `HouseFormRulesTest`, `ServerStatusTest`,
  `SyncHealthTest`, `CopyUndoTest`, `ImportUndoTest`, `ImportModeTagTest` ([test plan](docs/06-test-plan.md) TC-U-51,
  TC-U-52; device checks TC-M-22, TC-M-23).
- **Web, whole-app UX audit** (Sprint 4a, before the first deploy; `web/README.md` audit rows, rounds 1–3 and the
  final round, not pushed yet). Under 600 px the house pages (`/houses/new`, `/houses/:id`) hide the bottom bar and
  get the full screen with the toolbar's Back to the map (`hidesBottomBar`), as Android does; from 601 to 900 px the
  header is one scrolling row with a fade and the current item in view. The add-house hint is a plain paragraph read
  once by the announcer, with the shorter `map.addHintShort` under 760 px; *Show all* frames the houses clear of the
  overlays (`fitPadding`); every map is north-up and flat; the list keeps search, filter and sort in the URL and
  Compare its selection in `?ids=`; Back returns to where a house was opened from; *Fill in from listing text* and
  *Fill address from map* fill only empty fields; unsaved edits survive a discarded tab; Ask, Plan and Connect keep
  the last card in place, dimmed, while a new run is busy; the house form's Save says "Saving…"; hints say "choose",
  not "tap"; 44 px targets on touch; Indic line heights for headings and buttons; the partial-backup note with *Use
  everything* and the *Add a house on the map* empty state on *Your data* (Android parity). The app icons
  (`favicon.svg`, `icon-192.png`, `icon-512.png`, `icon-maskable-512.png`, `apple-touch-icon.png`) are redrawn from the
  brand mark with the same names and sizes. Tests: `fit-padding`, `nav-section`, `map-center`, `map-list`,
  `back-target`, `house-draft-merge`, `compare-selection`, `app.config`, `session-leftovers` and
  `backup-completeness` specs ([test plan](docs/06-test-plan.md) TC-U-53, TC-U-50; phone checks TC-M-24).
- **Docs for the audit:** [docs/05](docs/05-ux-accessibility-i18n.md) v0.10 (Android components, the Map at large
  text, location asks, the undo, the web shell, I18N-B06 and the design and UX self-check),
  [docs/06](docs/06-test-plan.md) v0.21 (the new tests and device checks, the release guard),
  [docs/07](docs/07-secure-build-and-deploy.md) v0.23 (the security self-check), [docs/10](docs/10-sprint-log.md) v0.24
  (the sign-off, handovers 19–34, the owner's decisions), [docs/11](docs/11-feature-parity-and-export-spec.md) v0.12
  (the Android pick-a-spot add mode for Sprint 4b) and [docs/12](docs/12-brand-and-naming.md) v0.2. Then, from the
  owner's recorded decisions: [docs/10](docs/10-sprint-log.md) v0.25 (the full release security gate, story S4b-SEC-3
  for `docs/13-release-security-checklist.md`, the process improvements and the first release's Definition of Done),
  [docs/06](docs/06-test-plan.md) v0.22 (§11 *First web deploy* and *Play Store release* rows; the `announcer` and
  `focus` specs), [docs/05](docs/05-ux-accessibility-i18n.md) v0.11 (hi/ta/te *under review*; the Telugu
  empty-state wording differs between web and Android; a failed save cancels "Saving…"),
  [docs/07](docs/07-secure-build-and-deploy.md) v0.24 (A.5), [docs/12](docs/12-brand-and-naming.md) v0.3 (N-06, the
  app icon), [docs/03](docs/03-design.md) v0.15 (ADR-13) and [docs/schemas/README.md](docs/schemas/README.md) v1.5
  (the device note under §6 rule 6, Android handover 19). Then, from the round 1 review of that change:
  [docs/10](docs/10-sprint-log.md) v0.26 (S4b-EFF-4 states the owner-approved review rules: one complete pass in
  round 1, later rounds the delta plus its regressions only, the blocker/major/minor rubric, out-of-scope findings as
  `BACKLOG:` minors that never block, `NEW RULE:` for a new class; review rules and the buddy pre-check in use since
  the final Sprint 4a round; first-pass approval target 35 % to more than 70 % by the end of Sprint 4b),
  [docs/05](docs/05-ux-accessibility-i18n.md) v0.12 and [docs/07](docs/07-secure-build-and-deploy.md) v0.25 (the
  same rules at the top of §15.5 and A.5) and [docs/06](docs/06-test-plan.md) v0.23 (the `connect-page` and
  `run-result` specs; the 0.22 change-log row reattached to its table).
- **Docs for the pre-deploy close-out** (2026-09-23): [docs/05](docs/05-ux-accessibility-i18n.md) v0.13–v0.14,
  [docs/06](docs/06-test-plan.md) v0.24–v0.26, [docs/10](docs/10-sprint-log.md) v0.27–v0.29 and the
  [docs index](docs/README.md) v0.27–v0.29. The last sync before the first deploy applies the Web team's close-out
  review rows up to `web/README.md` *Pre-deploy close-out, round 3 review fixes* (the last handover applied;
  `android/shared/README.md` up to 1.34): Plan and the new-house start in docs/05 §5, two NEW rules in docs/05 §15.3 R6
  and R9, the new spec cases in docs/06 TC-U-53, §14.3 and TC-S-19, and backlog tickets S4b-BL-6 and S4b-BL-7 with
  rule candidates (i)–(k) in docs/10 §12.7.
- **The web app is hosted on Firebase Hosting at https://doorprints.web.app** (owner decisions, 2026-09-23; not
  pushed yet). It replaces GitHub Pages, which cannot send security headers and shares one origin across the owner's
  Pages sites, and a Cloudflare Pages plan of the same morning, which the owner rejected before it was set up because
  a `pages.dev` address reads as a test site ([ADR-21](docs/03-design.md#14-architecture-decision-records),
  [docs/12](docs/12-brand-and-naming.md)). Firebase project and site `doorprints`, no-cost Spark plan, no billing
  account. `web.yml` gains `firebase-config` (checks `web/firebase.json` and installs `firebase-tools` 15.30.2, pinned,
  without install scripts), `firebase-setup` (skips cleanly while the setup values are missing) and `deploy-firebase`,
  which deploys the tested production build with a short-lived **Workload Identity** token — accepted only for
  `web.yml` on `main`, for a service account whose only role is Firebase Hosting Admin; **no key exists** — and then
  checks the live headers and caching ([build guide 6.3](docs/07-secure-build-and-deploy.md#63-web-firebase-hosting)).
  The owner's setup was done on 2026-09-23. The security headers now live in **`web/firebase.json`** (Web team):
  CSP with `frame-ancestors 'none'`, HSTS, `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`,
  COOP, and `Cache-Control: no-cache` on every path (Firebase's default would be an hour); the build copies the CSP
  into `index.html` as a `<meta>` and fails if the file's policy is missing or doubled; `public/_headers`,
  `public/_redirects` and the `build:pages` script are gone. The site is served from the root: the `**` rewrite
  answers deep links, there is no `404.html`, the manifest `"id"` is `"/"`, and the app refuses to start inside a
  frame. No file gets a long-lived `immutable` rule, because the rewrite answers a missing old chunk with the HTML
  shell. Nothing was ever deployed to GitHub Pages or Cloudflare Pages. **For browser sync, `APP_CORS_ORIGINS` on your
  server must list `https://doorprints.web.app`.** Roll back a bad release in the Firebase console
  ([runbook](docs/08-operations-runbook.md) IR-10).
- **Words: "import" means only a Doorprints backup** ([docs/12](docs/12-brand-and-naming.md) section G). The web's AI
  helper is now **Fill in from listing text** (keys `listingFill.*`, was "Import from listing text"; Android already
  used that title), and the web's readable HTML copy is named `Doorprints-copy-<date>.html`, for the download and
  inside the backup ZIP. Android's readable copies follow in Sprint 4b.
- **`GET /api/export` speaks the shared backup format** (Sprint 4a). It returns the `doorprints-backup/1` object —
  the same thing a device backup carries as `data.json` — instead of the old `house-hunt-export/1` shape, so there
  is one format and no converter, and the download is named `Doorprints-backup-<UTC date>.json`. Photo bytes are
  not in it; fetch them from `GET /api/photos/{id}`. The old id had no reader other than the owner, so nothing
  needs migrating. This supersedes the "export `format` id unchanged" note in ADR-13.
- **CI path filters cover the files the tests read** (Sprint 4a, not pushed yet). `backend.yml` also runs on
  `docs/ai/evals/**`, `docs/schemas/**`, `docker-compose.yml`, `web/src/app/export/backup-export.ts`,
  `web/src/app/export/golden/**` and the Kotlin `Backup.kt`, because `GoldenSet`, `CanonicalSample` and
  `BackupParityTest` read them; `android.yml` also runs on `docs/schemas/**` (`CanonicalSampleTest`). An edit to the
  shared backup sample, the web golden or a size-cap constant alone now runs the test that checks it
  ([build guide](docs/07-secure-build-and-deploy.md) section 1). The Pages build's manifest guard required
  `"id": "/doorprints/"` exactly; since the move to Firebase Hosting the deploy job requires `"id": "/"`.
- **The web app's entry point moved** (Sprint 4a): it opens on the map with the browser's own data and no route is
  guarded. `/connect` still exists, and configuring a server now only adds sync.
- **Ask citations need an inline marker** (commit `feb0294`, AI change set; only with AI enabled). The server now cites
  a house only when the answer marks it inline as `[house:<id>]` (in order of first appearance, retrieved houses
  only); the model's `citedHouseIds` list is used only when the answer has no marker at all, and the refusal sentence
  (curly apostrophes folded) never has citations. Clients see fewer chips for houses the answer never mentions
  ([ai-design](docs/ai/ai-design.md) 6, 8.2, 13). Golden set **v0.5**: `ask-01` allows the two other houses with a
  water fact as a grounded comparison (evidence: Vertex run 35753477789); thresholds unchanged. The eval scorecard
  shows the full output of failing and erroring cases.
- CI: the Security job `gradle-dependency-graph` does not fail on the submit step while the repository's dependency
  graph setting is off (`dependency-graph-continue-on-failure: true`, `feb0294`); a Gradle resolution failure still
  fails it. A green run therefore does not show that the graph was submitted; check the job log for `Submitted ...`,
  then the input is removed again. Dependabot keeps the Maven build image on JDK 25.
- Docs (Sprint 4b design review): Hunt mode reminders are exact (`setExactAndAllowWhileIdle`) when the user allows
  "Alarms & reminders", otherwise an inexact 10-minute window that ends at the chosen time, so they come up to about
  10 minutes early rather than late ([11](docs/11-feature-parity-and-export-spec.md) v0.7 5.16, FR-084); the geofencing
  `PendingIntent` is mutable as the API requires (T-E8, SEC-049); the 4b threats and tests are now also in
  [02](docs/02-threat-model.md) and [06](docs/06-test-plan.md).
- **Android HTTP client: Ktor 3.6 instead of OkHttp 4 with `RetryInterceptor`** (Sprint 3.5). Same requests, retry
  rules (idempotent calls and the photo upload; 408/429/502/503/504; full-jitter backoff 1 s to 15 s; 3 attempts;
  short `Retry-After` honoured), captive-portal detection and no redirects; new: a dropped connection while a
  retriable response body is read is retried too, and each call is limited to 4 minutes including retries. The photo
  upload streams from the file. MapLibre now runs on OkHttp 5.5.0 (brought by Ktor); a manual map smoke test (TC-M-17)
  covers it. The old `RetryInterceptorTest`, `ChecklistScoreTest` and app-side `StayDetectorTest`, `SyncRulesTest`,
  `StreetAlertsTest` moved to `commonTest` (TC-U-17 now points at `RetryPolicyTest` and `ApiClientContractTest`).
- `google-github-actions/auth` in `ai-evals.yml` pinned to a commit SHA (v3.0.0) after the DevSecOps review, which
  is now recorded (merge gate 2 in [ai-design](docs/ai/ai-design.md) section 14 closed).
- Vertex AI configuration for this project (owner, 2026-09-22): GitHub variables `GCP_PROJECT_ID=doorprints-ai`,
  `GCP_LOCATION=asia-south1`, `AI_VERTEX_EMBEDDING_LOCATION=global`, because `gemini-embedding-2` is not offered in
  `asia-south1`. Chat stays in India; embedding text is processed on the `global` endpoint (threat model T-I20). The
  `ai-evals.yml` default stays `aistudio` until the first `provider=vertex` run and credit check.
- Docs versions: 01 v0.13, 02 v0.14, 03 v0.8, 04 v0.7, 06 v0.12, 07 v0.12, 08 v0.11, 09 v0.5, sprint log (10) v0.12,
  11 v0.7 (its proposed ADRs renumbered to ADR-15..ADR-18), docs index v0.13, ai-design v0.18, vertex-setup v0.4.
  PRV-001 amended: background location only for the opt-in Sprint 4b area wake-up.
- AI provider quota errors (HTTP 429 / `RESOURCE_EXHAUSTED` from AI Studio or Vertex AI, after the bounded retries)
  are now told apart from outages: the AI endpoints still answer `503` (`"retryable": true`), and the problem body
  adds `"code": "AI_QUOTA_EXHAUSTED"` and the response a `Retry-After: 60` header (`ProviderErrors`,
  `AiExceptionHandler`). Existing clients are unaffected; see [03 section 9](docs/03-design.md#9-api-reference).
- `POST /api/ai/reindex` stops at the first batch that fails with a provider quota error instead of spending more
  calls on the remaining batches (they are counted as failed; the answer is `503` with `AI_QUOTA_EXHAUSTED`).
- The AI eval (`GoldenSetEvalTest`) runs when a provider is configured (`AI_API_KEY`, or `AI_PROVIDER=vertex` with
  `GCP_PROJECT_ID`), not only with `AI_API_KEY`. On a provider quota error it waits once for `Retry-After`, then stops:
  the scorecard result is **STOPPED: provider quota exhausted** and `ai-evals.yml` fails with an "AI eval STOPPED"
  annotation, instead of recording every remaining case as an error.
- Repository renamed from `house-hunt` to **`Sriram-Codes-SW/doorprints`** and made **public** (GitHub redirects the
  old URL). `main` is protected by a ruleset (no deletion, no force push); required status checks are deliberately
  not enabled until a PR flow with an always-running `CI summary` check exists (07 §3). README: license and security
  lines, repository link, the "repository still named `house-hunt`" note removed.
- AI access policy (feature spec 11 v0.3, D-21 and D-22): guests get no cloud AI (on-device Gemini Nano where the
  Android device supports it, otherwise AI is hidden); cloud AI only for the owner and invited users on a paid key
  with a hard cap; the v0.2 guest AI allowance and sign-in-for-more-AI prompt are withdrawn; bring-your-own-key is
  rejected and listed as out of scope. Vertex AI is added as an active provider next to AI Studio (AI team, setup in
  `docs/ai/vertex-setup.md`); the AI Studio code path stays.
- Feature spec 11 v0.2 (Product/Architecture): product-owner decisions D-01 (local-first, optional Google Sign-In),
  D-02 (photos on the device or in the user's own Google Drive), D-03 and D-08 applied to the proposal; v0.3 then
  replaced its guest AI allowance with the D-21 access policy.
- AI hard cap (AI-015, runbook 10.2) rebuilt on three layers: in-app per-user and global daily caps; a Google Cloud
  **spend cap budget** (Preview; monthly, counted before credits) on an AI-only project and the Vertex AI or Gemini
  API service, which pauses new AI usage when the target is passed; budget alerts at 50/90/100 %. A Quotas-page limit
  is optional (only if the model exposes one). Spend caps do not cover Cloud SQL, so staging has its own budget alert
  and a tear-down date. Firebase Test Lab keeps running within its no-cost daily quota after the trial.
- Docs versions: 01 v0.11, 02 v0.12, 03 v0.7, 04 v0.6, 06 v0.10, 07 v0.10, 08 v0.10, sprint log v0.9, feature spec
  11 v0.5, README change log. The Vertex AI sync: 03 section 13 describes both providers and the `AI_QUOTA_EXHAUSTED`
  problem; 04 E6, DF-21 and DF-32 name the Vertex endpoints, OAuth tokens from ADC and the location as a
  data-residency attribute; 06 adds TC-AI-18..22 and updates TC-AI-10 and TC-AI-16; 07 sections 4 and 7 and 08
  section 1.1, IR-9 and section 9 list the new settings, the quota error, the `setupHint` and the eval STOPPED
  result; 03 section 9 documents `setupHint`; 07 and 06 state that `ai-evals.yml` defaults to `aistudio`; sprint log C-24
  says the code landed and adds the spend cap acceptance item (AI-017).
- **Renamed to Doorprints** (tagline "Remember every house you've seen."), because "House Hunt" suggested a
  property-listings site. Names only, no behaviour change; decision record ADR-13 in
  [03](docs/03-design.md#14-architecture-decision-records).
  - Display name in all four languages: Android app name, notification texts and the lock-screen public version
    ("Doorprints alert"); web `<title>`, header and page titles ("Compare · Doorprints"). New translated tagline on
    the Android first-run house list and a new Settings *About* section, and as the web meta description. New Android
    launcher icon (a door with footprints).
  - **Android `applicationId` is now `app.doorprints`** (was `com.househunt.app`). Builds from before the rename are
    not upgraded: they install side by side and keep their own local data. Sync the old app, set up and sync
    Doorprints, then uninstall the old one.
  - CI artifacts: **`doorprints-debug-apk`**, **`doorprints-release-apk`** and **`doorprints-web-dist`** (were
    `house-hunt-*`).
  - API: `spring.application.name` `doorprints-api`; the MCP server reports `serverInfo.name` `doorprints` (endpoint
    `/mcp` and tool names unchanged); `GET /api/export` downloads as `doorprints-export-<date>.json` (the `format`
    field stays `house-hunt-export/1`). Web package `doorprints-web`, Gradle root project `Doorprints`, Maven
    `<name>` `doorprints-api`. AI eval scorecard title "Doorprints AI eval scorecard"; golden set v0.4 (description
    only; cases and thresholds unchanged).
  - Unchanged on purpose: the repository name `house-hunt`, Java/Kotlin packages and class names (`com.househunt`),
    browser storage keys (`house-hunt.lang`, `house-hunt.api-config`), the Android Keystore alias and Room database
    file, database/user/schema names (`househunt`), the compose volume `dbdata18`, the image names `house-hunt-api`
    and `house-hunt-db`, the `HH_*` signing secrets, MCP tool names, and "Hunt mode". Existing settings, data, backups
    and deployments keep working.
  - Docs: README, CHANGELOG and SSDLC documents 01 to 10 use the new name (01 v0.8, 02 v0.9, 03 v0.6, 04 v0.5, 05
    v0.3, 06 v0.9, 07 v0.7, 08 v0.7, 09 v0.4, sprint log v0.6, docs index v0.8); historical change-log rows keep the
    old name. 01 v0.8 also traces TC-AI-17 in the requirements traceability matrix.
- **Breaking for deployments:** `APP_API_KEY` must now be at least 32 characters (was 16), and so must
  `APP_API_KEY_NEXT` when set. The API refuses to start otherwise. Generate one with `openssl rand -hex 32` (F-01).
- `trivy config` in `security.yml` now fails the build on HIGH/CRITICAL Dockerfile findings; MEDIUM findings are
  reported in a separate, non-blocking pass.
- Hunt mode no longer crashes when the location permission was revoked or Android refuses a background
  foreground-service restart; it stops quietly and the map card asks for the permission again.
- Android reverse geocoding is time-limited (10 s) and skips invalid coordinates and devices without a geocoder.
- The Android map keeps its lifecycle calls paired and destroys the `MapView` exactly once.
- SSDLC documents 01, 02, 03, 06, 07, 08 and 09 updated for Sprint 1 fixes and Sprint 2 (new versions in each change
  log). 09 marks key rotation (row 5.5, OSI-B04) as done; 07 and 08 note that the non-root database image needs
  host bind mounts owned by uid 999.
- AI re-indexing (`HouseIndexer`): a failing batch no longer stops the run; the remaining batches still run and
  `POST /api/ai/reindex` then answers **503** (generic problem detail); the WARN log gives only counts (houses and batches not
  indexed, houses indexed), no house content. Index failures are summarised instead of logged one line per
  house: a failed reindex logs one WARN per run (per-batch detail at DEBUG), and failed per-house updates log at
  most one WARN per 5 minutes (also when 429s alternate with successes) plus one INFO when updates work again;
  house ids and the provider error class are at DEBUG only.
- Sprint log v0.2 records the Sprint 2 CI results, the start of Sprint 3 and the eval findings E-01/E-02; threat
  model v0.5 corrects the F-01 finding text to the 32-character minimum (F-01 stays partly fixed until per-device
  keys, SEC-025) and re-checks the new embedding flow, which found **F-30** (the contact name reached the LLM
  provider in the embedding text and Ask context; fixed later in Sprint 3 by C-13, see *Security* and threat model
  v0.6). 01 v0.4, 03 v0.4, 04 v0.3 (new DF-32 embedding request),
  06 v0.5, 07 v0.5 and 08 v0.4 describe the Sprint 3 AI fixes.
- Docs for the Sprint 3 lead decisions: threat model v0.6 splits F-01 into F-01a and F-01b and marks F-30 fixed
  (31 findings: 26 Fixed, 3 Part, 2 Open) and corrects its v0.5 change-log row, which wrongly said "Totals
  unchanged"; 07 v0.6 lists every AI variable and which ones the dev compose passes; the sprint log v0.3 records the
  new review process (senior reviewers with a mandatory runtime pre-mortem, third-party wire-format verification and
  contract tests, after the Sprint 3 embeddings incompatibility). 01 v0.5, 03 v0.5, 04 v0.4, 06 v0.6, 08 v0.5 and
  09 v0.3 follow. Rework: threat model v0.7 aligns the F-30 entry with the final redaction rules; 01 v0.6 gives the
  reasons PRV-009 stays partly met; 06 v0.7 (TC-AI-12) and 08 v0.6 match the final indexer logging and reindex
  rollout; sprint log v0.4.
- Docs for the Sprint 3 outcome: sprint log v0.5 records the `6a348cc` CI results, the first successful real Gemini
  eval run (all 13 cases ran, 12/13 cases passed; `citationPrecision` 0.86 vs 0.90 open as E-03), F-30 closed by lead
  decision (evidence: TC-AI-15 green in the Backend workflow), the new story S3-05
  (local labels instead of `[contact]` in AI citations) and the Sprint 4 candidates C-14..C-21 (not committed);
  threat model v0.8 (F-30 closed, totals unchanged), 01 v0.7 (AI-010, AI-012, PRV-009), test plan v0.8 (TC-AI-10 gap
  row; section 1 now names golden set v0.3 and the first real run; `allowedCitations` traced in TC-AI-09 and the Ask
  prompt rules in new TC-AI-17) and the docs index v0.7 follow.
- Ask answers (E-03, AI team; only with AI enabled): the Ask system prompt (`AskPrompts`) now tells the model to cite
  a house only where the answer states a fact about it from its record, never for a passing mention; to answer with
  the houses that satisfy the question first; and to mention another house only as a brief contrast, cited when it
  does (the prompt's example is the neutral "X is over budget", so it does not repeat the `ask-02` eval fixture).
  Answers may therefore name fewer houses and cite contrast houses. The injection rules and the exact refusal sentence
  are unchanged (`AskPromptsTest`, TC-AI-17 in the [test plan](docs/06-test-plan.md)).
- AI eval golden set v0.3 (`docs/ai/evals/golden-set.json`): ask cases can list optional `allowedCitations`, houses
  that may be cited (such as a grounded contrast) but are not required. `citationPrecision` now counts a cited house
  as correct when it is in `expectedHouseIds` or `allowedCitations`; `citationRecall` still uses `expectedHouseIds`
  only. `ask-02` allows the Blue gate house; thresholds unchanged. `EvalScorerTest` covers the rule and checks that an
  allowed house is neither expected nor `mustNotCite` (TC-AI-09). **E-03 is confirmed fixed** by the `provider=vertex`
  eval run 35758157317 on `19006bc` (13/13 cases, every metric passes; `citationPrecision` back above its 0.90
  threshold).

### Fixed

- **India's boundaries on the map** (owner issue P0 of 2026-09-24 on the live site, near Jammu and Kashmir and near
  Arunachal Pradesh; branch `fix/india-boundaries`, HEAD `3ad2b58` pushed, **not yet seen green in CI, not deployed**). Both apps drew OpenFreeMap
  Liberty's ISO view: the Line of Control, the Line of Actual Control and claim lines, a Pakistan line through
  Kashmir below zoom 5, the Pakistan-China line at Khunjerab, and the "Azad Kashmir" and "Gilgit-Baltistan" state
  labels. Every map on the web and Android now shows India's external boundary as the Government of India depicts
  it, the only view: all of Jammu and Kashmir and Ladakh (PoK, Gilgit-Baltistan, Shaksgam and Aksai Chin included)
  and Arunachal Pradesh inside India, one solid outline, no LoC or LAC. On every style load both apps hide
  `boundary_disputed`, start `boundary_2` at zoom 5 without the Pakistan-China line, draw a bundled Natural Earth
  outline (public domain, India point of view; `in-boundary-world` below zoom 5, `in-boundary-claim` at every zoom)
  and hide the two state labels; a missing layer is a warning, never a crash. Both apps also draw only tile country
  lines that carry a country code (`COUNTRY_LINE_RULE` on the web, `COUNTRY_LINE_EXTRA_FILTER` on Android) and take
  `boundary_2`, `boundary_3` and every other boundary line layer from zoom 5 only from zoom 5+ tiles
  (`TILE_ZOOM_GUARD`, `[">=", ["zoom"], 5]`), so a zoom 0-4 tile that MapLibre shows while a closer tile loads, or
  offline, never brings back the Pakistan line through Kashmir or a line through Arunachal Pradesh. Read from the
  renderer sources, maplibre-gl and maplibre-native already skip a minzoom 5 layer in a zoom 0-4 tile, so these two
  guards are defence in depth on both apps ([docs/03](docs/03-design.md) ADR-22 rule 2). Known limits, both apps:
  the outline is 1:10m Natural Earth, a median of about 1.55-1.6 km off the true line (90th percentile 3.9 km); in
  the Wakhan, the middle sector (Himachal Pradesh, Uttarakhand, Kalapani and Dharchula), Sikkim, Bhutan's south-east
  corner and Myanmar south of 26.65 N the tiles' own border line is drawn beside the outline, a median 1.5-2.8 km
  apart (at most 5.3 km), so two close lines can show when zoomed in (S4b-BL-11, S4b-BL-16); from zoom 5 the
  Assam-Arunachal Pradesh state line is not drawn, because the tiles carry it as a disputed line (S4b-BL-15); both
  fixed by the next entry. Web:
  `shared/india-boundaries.ts` (37 spec cases); Android: `ui/IndiaView.kt`, `ui/IndiaViewRules.kt` (18 JVM tests,
  16 + 2, reported passing locally). No new network host. New CI checks (committed, not yet seen green) cover
  the data file in the build, its byte identity in both apps, and the live copy after each deploy. See [docs/03](docs/03-design.md) ADR-22,
  [docs/01](docs/01-requirements.md) FR-098, [docs/06](docs/06-test-plan.md) §15 (TC-M-25 is a release gate).
- **India's boundary: one line from zoom 5, and the Assam-Arunachal Pradesh state line** (owner request of
  2026-09-24; S4b-BL-11, S4b-BL-15, S4b-BL-16; branch `fix/india-boundary-lines`, PR #16, **not deployed**; CI green
  on `5af2f4d`, the run on `9e0036e` pending). Both apps no longer draw the base map's own India-China line (the
  tiles cut it into drawn and hidden pieces, which showed as stray pieces beside the outline at street zoom): the
  Natural Earth outline draws the whole India-China border at every zoom. Along the 7 stretches where the
  OpenFreeMap tiles draw India's border with Nepal, Bhutan or Myanmar themselves, or in the Wakhan, the outline now
  draws below zoom 5 only and the tiles' more precise line takes over from zoom 5, so the two close lines are gone;
  each hand-over has a connector of about 7 km at most, so the border has no gap. Kashmir, Ladakh, Jammu-Sialkot and
  Arunachal Pradesh keep the outline at every zoom. The stretches come from the new
  `web/scripts/geo/find_shared_stretches.py`, re-run after each OpenFreeMap planet or style update. The
  Assam-Arunachal Pradesh state line (Natural Earth 1:10m) is drawn from zoom 5 on both apps, dashed like the other
  state lines (web `in-boundary-state`, Android `IndiaViewRules.STATE_OVERLAY_LAYER`). New data file, byte-identical
  in both apps (sha256 `25984afa…a024`, kinds `world`, `claim`, `state`; `web.yml` requires all three), with no
  connector-only spur (two had shown into Nepal on the Singalila ridge). Known minors: a small step at each hand-over;
  loops at Sikkim's two tri-junctions (about 13 x 3 km at Nepal-China-India, on glaciers, from about zoom 10; about
  2 km at Doklam); from about zoom 10 (a small hook at Jomotsangkha from zoom 9) the tile line running on past the hand-over at Jomotsangkha (about 9 km) and Longwa
  (about 3 km); the India-China rule also hides about 12 km of the China-North Korea line on the Tumen islets
  (harmless for India); while closer
  tiles load, or offline without them, those stretches show no line from zoom 5. Web: 41 spec cases, 463 tests in
  all; Android: 20 JVM tests. See [docs/03](docs/03-design.md) ADR-22, [docs/10](docs/10-sprint-log.md) §12.10.

Found by the whole-app UX audit (2026-09-23; working tree, **not pushed yet and not built in CI**):

- Android: a rotation, the language switch or reopening from Recents re-ran a notification's deep link and reopened
  the house or stacked new-house forms (blocker; `MainActivity` handles an intent once).
- Android: the keyboard covered the house form, Settings and the Assistant (`imePadding`); the Map never framed the
  houses, because the first "whole of India" view was saved as the user's camera; a double Save left an empty screen; *Approximate* location was
  treated as "location off".
- Android: the map legend covered MapLibre's logo and attribution "i" and took their taps (the OpenStreetMap credit
  must stay visible); the compass could land under a button, leaving a rotated map with no way back to north; a
  refusal snackbar covered the Hunt card's own button on a short map; Settings' first *Save and test* result was not
  announced.
- Web: on a browser without WebGL 2 the map page offered a dead *Place here* (blocker); it now offers *Add at my
  location* and *Type latitude and longitude*.
- Web: a Back cancelled by the unsaved-changes guard overwrote the list's history entry; focus fell to `<body>` when
  a button went away; the header's sync-problem dot was 1.02:1 on the teal header (WCAG 1.4.11); Telugu headings and
  the phone toolbar title were clipped; on phones the add hint grew down onto the crosshair; the map could be rotated
  with no way back to north.

Confirmed by green Backend CI on `6a348cc` and the first successful real Gemini eval run (2026-09-22, Actions run
35720654442, which ran on `6a348cc`).

- E-01: AI embeddings against Gemini failed because Gemini's OpenAI-compatible `/embeddings` response has no
  `data[].index`, which the OpenAI client in Spring AI rejects. Embeddings now use the native Gemini API
  (`GeminiEmbeddingModel`, `POST …/models/{model}:batchEmbedContents`, up to 100 texts per call), with the key sent
  only in the `x-goog-api-key` header and errors that carry the HTTP status only. Chat is unchanged.
- E-02: the AI eval scorecard reported PASS when no case had run. `GoldenSetEvalTest` now fails when zero cases ran,
  on any harness error (seeding the fixtures or re-indexing failed) or when a metric misses its threshold; the
  scorecard lists *Errors* and *Why FAIL*.
- Backend CI on `feb0294` (run 35755840287) failed: `EvalScorerTest.goldenSetFileIsConsistent` threw
  `IllegalArgumentException: The iterable of values to look for should not be empty` because `ask-01` in golden set
  v0.5 has `allowedCitations` but no `mustNotCite`, and AssertJ's `doesNotContainAnyElementsOf` rejects an empty
  list (258/259 tests passed). The golden-set consistency check now skips an empty or missing id list ("no
  constraint"); `ask01AllowsOnlyTheHousesWhoseWaterFactsAreInTheFixture` compares lower-cased ids on both sides. Test
  code only; golden set and thresholds unchanged. **Confirmed by the green Backend run on `19006bc`** (the one case that failed on `feb0294` now passes),
  together with a green Security, Android and Shared iOS compile on the same commit (recorded in the
  [sprint log](docs/10-sprint-log.md) 9.2, TC-AI-09 in the [test plan](docs/06-test-plan.md)).
- **Web, pre-deploy close-out (2026-09-23; not yet built in CI):** an answer from the location prompt or the 15 s fix
  that arrives after the map, Plan or the house form was left is dropped, so leaving the page no longer opens a
  new-house form nobody asked for or announces on another page (`shared/locate-once.ts`, `locate-once.spec.ts`,
  [test plan](docs/06-test-plan.md) TC-U-53). A 32 px tab icon, `icons/favicon-32.png` rendered from `favicon.svg`, is
  listed first, so browsers no longer shrink the 192 px icon for the tab.
- **Web, pre-deploy close-out, buddy pre-review (2026-09-23; not yet built in CI):** *Plan route* with an unusable
  start now focuses the first start field still to fix, so with the latitude typed and the longitude empty it focuses
  the longitude, not the latitude (`pages/plan/start-field.ts`, `start-field.spec.ts`; [sprint log](docs/10-sprint-log.md)
  §11.7 W2). The map's *Download now* no longer announces "*n* houses downloaded" on another page when the map was
  left while the download ran (§12.7 S4b-BL-5).
- **Web, Plan's typed start** (pre-deploy close-out, round 1 review, 2026-09-23; not yet built in CI): a coordinate
  typed while no start is set is dropped when its field turns invalid or is cleared, so Plan never sets a start from a
  value the user removed (`nextTypedStart` in `pages/plan/start-field.ts`, `start-field.spec.ts`;
  [sprint log](docs/10-sprint-log.md) §11.7 W2).
- **Web, a stale start message withdrawn by every start** (pre-deploy close-out, round 2 review, 2026-09-23; not yet
  built in CI): a *location blocked* or *location unavailable* note under Plan's start fields now goes away however the
  start is then set (map, marker drag, typing, the newest house, *Use my location*), so it is no longer read with the
  fields on every focus ([docs/05](docs/05-ux-accessibility-i18n.md) §5, *Start point (web)*).
- **Web, no start in central India on the untouched default view** (pre-deploy close-out, round 1 review, 2026-09-23;
  not yet built in CI): the map view saved on the map page's first layout (the untouched country view) is not read as
  the user's choice, so the new-house form opened with no position, and Plan's first start, no longer start in the
  middle of India (`loadStartPoint` in `shared/map-center.ts`, `map-center.spec.ts`;
  [test plan](docs/06-test-plan.md) TC-U-53).
- **Web, the `/houses/new` read guarded after the page is gone** (pre-deploy close-out, round 2 review, 2026-09-23;
  not yet built in CI): leaving the new-house form while it reads the houses for its starting view no longer announces
  *Draft restored* or sets the title on the next page (`startWithoutPosition()` in `house-detail-page.ts`;
  [sprint log](docs/10-sprint-log.md) §12.7 candidate (f); its TestBed cases are S4b-BL-7).

### Security

- Sprint 4a threat model additions (v0.15..v0.20): malicious backup files (T-T8), injection into exports (T-T9),
  static hosting without response headers (T-T13, F-31 — **fixed on 2026-09-23 by moving the host, to Firebase
  Hosting**, see below), the PWA share target as untrusted inbound text
  that may carry phone numbers (T-I26: shown as plain text and removed from the address bar and the URL of the
  tab's history entry; the text itself stays in that entry's navigation state, `history.state`, until the house is
  saved, or until the tab closes if the form is abandoned, and session restore may keep it — a recorded residual),
  and Android's persisted document grants (T-I25: newest 5 exports and the backup folder, released when no
  longer needed). The `data.json` size cap is now one number, **16 MiB**, on Android, the web and the server
  (`MAX_IMPORT_BYTES`), instead of three.
- **F-31 fixed, RR-11 closed** (threat model v0.22–v0.24, 2026-09-23): the web app moves to its own origin,
  `https://doorprints.web.app` on Firebase Hosting, which sends every header in `web/firebase.json` (CSP with
  `frame-ancestors 'none'`, HSTS, `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`, COOP) instead
  of the `github.io` origin shared with the owner's other Pages sites. Checked on 2026-09-23 that the other Pages site
  on that origin (secure-doc-viewer) ran no scripts: its `gh-pages` branch at `68e3a625` (2026-09-21) holds only HTML,
  images, a README and `.nojekyll`, with no `<script>` element, so nothing was exposed. Each deploy checks the live
  headers (test plan TC-S-23), and `web/firebase.json` is checked before any deploy credential exists (TC-S-24: no
  lifecycle hooks, no function rewrites, `public` only the build). The build-time `<meta>` CSP and the app's refusal
  to run inside a frame stay as defence in depth. New threats **T-T14** (replacing the live site through the deploy
  path: Workload Identity pinned to `web.yml` on `main`, no key, Hosting Admin only) and **T-D9** (the Spark quota
  disables the site); new residuals **RR-13** (quota, no budget alert), **RR-14** (Firebase's reserved `/__/*` paths
  are outside our headers; no Firebase Web App is registered) and **RR-15** (HSTS on `web.app` comes from the `.app`
  preload); **RR-12** (Cloudflare's per-deployment addresses) is withdrawn. Live evidence is still to come: the first
  deploy and the manual check TC-M-19.
- To sync the web app with your own server, allow its origin: `APP_CORS_ORIGINS` must include
  `https://doorprints.web.app` (an origin has no path); the compose default allows only `http://localhost:4200`
  ([build guide 6.3](docs/07-secure-build-and-deploy.md#63-web-firebase-hosting)).
- Vertex AI uses **only** Application Default Credentials (short-lived OAuth tokens; Workload Identity Federation in
  CI, the attached service account on Cloud Run); there is no Vertex API key path in the code. Threat model v0.12
  T-I22 and build guide section 4 updated: nothing to rotate for WIF and Cloud Run, quarterly rotation only for a
  JSON key on a non-Google host.
- Threat model v0.11: **T-I22** (Vertex AI / Google Cloud credential leak → spend and project access): AI-only
  project, service account with `roles/aiplatform.user` only, no JSON key in the repository, image, artifacts or logs,
  Workload Identity Federation (GitHub OIDC) for CI, quarterly rotation, spend cap budget as blast-radius limit; new
  credential row in the build guide section 4.
- Threat model v0.10: **T-I20** (the free AI Studio tier may use prompts to improve Google products; real user data
  only on the paid tier or Vertex AI, PRV-022) and **T-I21** (public repository: history, logs and artifacts are
  public; gitleaks on the full history, secret scanning, private vulnerability reporting, no `pull_request_target`).
  T-I11 no longer counts a private repository as a mitigation. CodeQL is free now that the repository is public and is
  a Sprint 4a candidate (C-22).
- F-28 (Critical): embedded Tomcat raised from 11.0.24 to **11.0.25** with a `tomcat.version` override in
  `backend/pom.xml` for CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525. Remove the override when Spring Boot
  manages 11.0.25 or later.
- F-29 (Trivy DS-0002): the development/CI database image `backend/db/Dockerfile` now runs as the `postgres` user
  instead of root. Host bind mounts for its data must be owned by uid 999.
- F-01 (High) is split in the threat model: **F-01a** (fixed): 32-character minimum key length and dual-key rotation
  with `APP_API_KEY_NEXT`; **F-01b** (open, backlog C-04): per-device keys (SEC-025).
- F-30 (Medium): the contact name of a house no longer reaches the LLM provider. `ContactRedactor` keeps the contact
  name and phone out of the embedding text, the Ask context and citations and the planner/MCP tool results
  (`houseDetails` no longer returns `contactName`); names and phone numbers typed into other fields or notes are
  replaced by `[contact]` / `[phone]` (best effort, see the threat model). Ask citation labels and plan stop labels
  may therefore contain `[contact]`; the apps can show their own label for the `houseId`. Only relevant with AI
  enabled. **After deploying, run `POST /api/ai/reindex` once** so stored vectors no longer hold contact names
  ([runbook 1.1](docs/08-operations-runbook.md)). Closed by lead decision (C-13, AI-010): the redaction tests TC-AI-15
  pass in the Backend workflow on `6a348cc`. The real Gemini eval run 35720654442 only shows that the AI paths still
  work with the redactor in place; its fixture houses hold no contact data, so it is not a redaction test.
- **Web, "Remove all data" leaves less behind** (pre-deploy close-out, 2026-09-23; not yet built in CI): it now also
  removes the app's `hh.*` and `doorprints.*` keys from `localStorage` (the install and storage-advice "Not now"
  dates were left behind; the language choice stays) and from this tab's `sessionStorage` (every such key, not only
  unsaved drafts and shared listing text), and unregisters this deployment's own service worker, and no
  other (`session-leftovers.spec.ts`, `pwa.service.spec.ts`; [test plan](docs/06-test-plan.md) TC-S-19). The live
  storage audit of the first web deploy follows `web/README.md`, *Storage audit on the live site*.

## [0.1.0] - 2026-09-22

First version (Sprint 1): commits `4b034d3` (initial app) and `689927d` (first CI fixes). Not tagged; there is no
signed release of this version.

### Added

- Spring Boot 4.1.1 / Java 25 API on PostgreSQL + PostGIS: houses, visits, photos, 10-point checklist, change feed
  for offline sync (last writer wins), nearby and same-street queries, statistics, export and delete-all.
- Android app (Kotlin, Compose, Room, WorkManager, MapLibre Android 13): offline-first capture, map, list, compare,
  Hunt mode with house and street alerts and automatic visit detection, background sync with retries, four
  languages (English, Hindi, Tamil, Telugu), dark theme, TalkBack support.
- Angular 22 web app (zoneless, signals, MapLibre GL): map, house detail, compare, runtime language switch in the same
  four languages, WCAG 2.2 AA target.
- Optional AI features, off by default (Spring AI 2.0.1, optional pgvector): ask with cited sources, listing text
  extraction, visit planner, MCP server.
- Secure SDLC documents 01 to 09 and the AI design in `docs/`.
- CI: `backend.yml`, `web.yml`, `android.yml`, `security.yml` (Semgrep, gitleaks, Trivy, npm audit, optional ZAP)
  and Dependabot.

### Changed

- Android `compileSdk` 37 (targetSdk 36, minSdk 26).
- Trivy scans the backend from a CycloneDX SBOM (`trivy sbom`) and runs `trivy fs --offline-scan`, after Maven
  Central rate-limited the POM resolution of `trivy fs`.
- Dependabot tuned: weekly on Monday, at most 5 open PRs per ecosystem, grouped minor/patch and security updates,
  framework major versions ignored (upgraded by hand).

### Fixed

- Backend test compile error on the first CI run.
- Test API keys are generated at runtime instead of being written in the source; the two old findings in the first
  commit are listed by exact fingerprint in a reviewed `.gitleaksignore`.

### Security

- Wave 2 hardening before the first push: 21 of the 26 threat-model findings fixed, among them deny-by-default path
  handling for the API key (F-20), rate limits (F-05), image metadata stripping (F-07), security headers (F-10),
  cleartext and backup rules on Android (F-02, F-03) and a non-root API container (F-23).
- F-27 (Critical): web map library upgraded to maplibre-gl 6.10 for GHSA-jrc7-96c5-q579 (`DOM.sanitize()` bypass).
  The MapLibre 6 module worker is served from `/maplibre/`, so the CSP uses `worker-src 'self'` without `blob:`.
