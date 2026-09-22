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

Sprint 2 (2026-09-22): all four CI workflows (Backend, Android, Web, Security) are green on `f7da5ab` and
`0e4e22a`. See the [sprint log](docs/10-sprint-log.md).

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

### Added

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
  allowed house is neither expected nor `mustNotCite` (TC-AI-09). A new `ai-evals.yml` run is still needed to confirm
  E-03.

### Fixed

Confirmed by green Backend CI on `6a348cc` and the first successful real Gemini eval run (2026-09-22, Actions run
35720654442, which ran on `6a348cc`).

- E-01: AI embeddings against Gemini failed because Gemini's OpenAI-compatible `/embeddings` response has no
  `data[].index`, which the OpenAI client in Spring AI rejects. Embeddings now use the native Gemini API
  (`GeminiEmbeddingModel`, `POST …/models/{model}:batchEmbedContents`, up to 100 texts per call), with the key sent
  only in the `x-goog-api-key` header and errors that carry the HTTP status only. Chat is unchanged.
- E-02: the AI eval scorecard reported PASS when no case had run. `GoldenSetEvalTest` now fails when zero cases ran,
  on any harness error (seeding the fixtures or re-indexing failed) or when a metric misses its threshold; the
  scorecard lists *Errors* and *Why FAIL*.

### Security

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
