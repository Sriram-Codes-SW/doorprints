# 10: Sprint log

| Field | Value |
|---|---|
| Document | Agile sprint log (goals, stories, sign-offs, CI results, retrospectives) |
| Version | 0.5 |
| Date | 2026-09-22 |
| Author | Claude (Cowork), Docs team |
| Status | Draft (Sprint 2 done, Sprint 3 closing, Sprint 4 candidates awaiting the product owner) |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) | First version: working agreement, Sprint 1 (initial build, CI green except Security) and Sprint 2 (security fixes, key rotation, release signing, tests) with goals, stories, sign-offs, CI results, retrospectives and next-sprint candidates. Includes `ai-evals.yml` in S2-09/C-07, C-12 and a note that the first-push CI row is reconstructed from the `689927d` commit message. C-07 traces to TC-AI-10 (covers the TC-AI-01..08 cases) and AI-001, matching [06](06-test-plan.md). |
| 0.2 | 2026-09-22 | Claude (Cowork) | Sprint 2 outcome: all four workflows green on `f7da5ab` and `0e4e22a` (web `package-lock.json` committed from the CI artifact); S2 stories marked Done; C-12 Done, C-01 Part. Added Sprint 3 (section 5): the first real AI eval run (C-07) found the Gemini OpenAI-compatible embeddings incompatibility (index not set) and a false PASS in the scorecard; the AI team fixed both in code (native Gemini `batchEmbedContents` embeddings; scorecard fails on zero cases or harness errors), not yet confirmed by an eval run. Candidates moved to section 6 with a status column. New candidate C-13 (owner AI): the contact name reaches the LLM provider unredacted (02 F-30, AI-010 now Part). |
| 0.3 | 2026-09-22 | Claude (Cowork) | Lead decisions for Sprint 3: C-13 (F-30, contact name to the LLM provider) assigned to the AI team this sprint as S3-03, now done in code (F-30 Fixed in [02](02-threat-model.md) v0.6, waiting on CI); F-01 split into F-01a (Fixed) and F-01b (Open, C-04); `docker-compose.yml` moved to the Backend team's files (it passes the AI settings); new S3-04 (Docs). Section 1: new **Process** note: team managers upgraded to senior reviewers with a mandatory runtime pre-mortem, third-party wire-format verification and contract tests, because the Sprint 3 embeddings incompatibility (E-01) passed compile, unit tests and review. |
| 0.4 | 2026-09-22 | Claude (Cowork) | S3-03 records the final redaction rules (ai-design v0.10, including initials-style names) and the `[contact]` label contract for clients, with the reindex after the v0.10 deploy; S3-04 lists the coordinator's rework (06 v0.7, 01 v0.6, 02 v0.7, 08 v0.6, CHANGELOG, README). |
| 0.5 | 2026-09-22 | Claude (Cowork) | Sprint 3 outcome: commit `6a348cc` pushed, Backend and Security workflows green (Android and Web not triggered by their path filters). First successful real Gemini eval run on 2026-09-22 (Actions run 35720654442): 12/13 cases passed, every metric passes except `citationPrecision` 0.86 (threshold 0.90) because of one correct contrast citation, recorded as E-03 (AI team: `allowedCitations` in the golden set and an Ask prompt tweak). E-01 and E-02 confirmed fixed; S3-01 Done with the E-03 follow-up; lead decision: **F-30 closed as Fixed** (evidence: TC-AI-15 green in the Backend workflow on `6a348cc`; the eval run is a no-regression check only, S3-03 Done, C-13 Done, [02](02-threat-model.md) v0.8). New S3-05 (local labels instead of `[contact]` in AI citations, not started) and new section 7: Sprint 4 candidates C-14..C-21, not committed scope, awaiting the product owner. |

Related: [Requirements](01-requirements.md) · [Threat model](02-threat-model.md) · [Test plan](06-test-plan.md) · [Build and deploy](07-secure-build-and-deploy.md) · [Runbook](08-operations-runbook.md) · [CHANGELOG](../CHANGELOG.md)

---

## 1. How we work

House Hunt is built by small AI engineering teams, each with a senior reviewer (a team manager until Sprint 2), for one product owner (the user).
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
| Backend | `backend/**` (except `backend/src/main/java/com/househunt/ai/**` and its tests), `docker-compose.yml` (since Sprint 3; it passes the `AI_*` settings, see [07](07-secure-build-and-deploy.md) §7) |
| AI | Spring AI code and tests, `docs/ai/**` |
| Android | `android/**` |
| Web | `web/**` |
| DevOps / Security | `.github/**`, `.gitleaks*`; Docker files other than `backend/**` as agreed (the dev `docker-compose.yml` moved to Backend in Sprint 3) |
| Design | `docs/05-ux-accessibility-i18n.md`, string resources with Android and Web |
| Docs | `docs/**` except `docs/ai/**`, `README.md`, `CHANGELOG.md`, this log |

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
| 3 | 2026-09-22 | First real AI eval run and its fixes; contact redaction (C-13); remaining Sprint 2 candidates | Mostly met (`6a348cc`): embeddings and scorecard fixes confirmed by the first real Gemini eval run (12/13 cases passed, all metrics but `citationPrecision` pass, E-03 open with the AI team); F-30 closed as Fixed. Closing | Backend ✅ · Security ✅ · Android and Web not triggered (no `android/` or `web/` changes) |

## 3. Sprint 1: initial build

**Goal:** a working, zero-cost House Hunt: Spring Boot + PostGIS API, offline-first Android app with Hunt mode,
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
| S3-01 | As the owner the first real AI eval run (`ai-evals.yml`, C-07) is recorded and its defects are fixed | AI | Done: E-01 and E-02 fixed and confirmed by the first successful real Gemini eval run (2026-09-22, Actions run 35720654442, section 5.3); follow-up E-03 (`citationPrecision` 0.86 < 0.90) in progress |
| S3-02 | As the owner the docs record the Sprint 2 outcome and Sprint 3 start, and describe the embedding and scorecard fixes | Docs | Done (01 v0.4, 02 v0.5, 03 v0.4, 04 v0.3, 06 v0.5, 07 v0.5, 08 v0.4, this log v0.2, CHANGELOG); the DF-32 re-check found F-30 (C-13) |
| S3-03 | As a contact person my name and phone never reach the LLM provider (C-13, [02](02-threat-model.md) F-30, AI-010), assigned to the AI team this sprint by the lead | AI | Done. Closed by lead decision: redaction tests TC-AI-15 pass in the Backend workflow on `6a348cc`; the real Gemini eval run 35720654442 confirms the AI paths work with the redactor in place (its fixtures hold no contact data, so it is a no-regression check, not a redaction test). `ContactRedactor` on the embedding text and metadata, the Ask context and citations (also chunks indexed before the fix) and the agent/MCP tool results (`HouseDetails.contactName` removed); tests TC-AI-15; name parts are removed from every free-text field, place fields match the whole name in order or reversed and initials-style names ("C/o K Ramesh"), the saved phone only at 8+ digits (ai-design v0.10 §9.1); `Citation.label` / `PlannedStop.label` may contain `[contact]` / `[phone]` (apps show the local label by `houseId`); run `POST /api/ai/reindex` once after deploying the final (v0.10) code (08 §1.1). F-30 **Fixed** in 02 v0.6, status cell aligned in v0.7, closed in v0.8 |
| S3-04 | As the owner the docs record the lead decisions of Sprint 3 | Docs | Done: F-01 split into F-01a (32-char minimum and `APP_API_KEY_NEXT` rotation, Fixed) and F-01b (per-device keys, Open, C-04), totals 26 Fixed / 3 Part / 2 Open of 31 (02 v0.6); F-30 Fixed (01 v0.5, 02, 03 v0.5, 04 v0.4, 06 v0.6, 08 v0.5); `docker-compose.yml` owned by Backend, AI settings in the 07 v0.6 env table; 02 v0.5 change-log row corrected; *Process* note in section 1; 09 v0.3; CHANGELOG. Coordinator rework: 06 v0.7 (TC-AI-12: 5-minute window), 01 v0.6 (PRV-009 reason for Part), 02 v0.7 (F-30 cell per ai-design v0.10), 08 v0.6 (reindex after the v0.10 deploy), CHANGELOG *Changed* corrected, README index F-01a/F-01b..F-30 |
| S3-05 | As the user I see my own house label, not `[contact]` / `[phone]`, in AI citations and planned stops (apps look up the local label by `houseId`, client contract in [02](02-threat-model.md) F-30 and ai-design §13) | Android, Web | Open: not started in Sprint 3; carried to Sprint 4 as candidate C-19 (section 7) |
| S3-06 | As the owner the docs record the Sprint 3 outcome, close F-30 and list the Sprint 4 candidates | Docs | Done: this log v0.5 (sections 2, 5, 6, 7), 02 v0.8 (F-30 closed), 01 v0.7 (AI-010, AI-012, PRV-009), 06 v0.8 (TC-AI-10 gap row), docs index v0.7, CHANGELOG |

### 5.2 Findings of the first real AI eval run

| # | Finding | Effect | Owner | Status |
|---|---|---|---|---|
| E-01 | Gemini's OpenAI-compatible embeddings endpoint is not compatible with the app's OpenAI embeddings client: the embedding `index` is not set | Embeddings against Gemini fail, so RAG indexing and retrieval cannot be evaluated | AI | **Fixed, confirmed** by the real Gemini eval run of 2026-09-22 (Actions run 35720654442): indexing and retrieval ran against Gemini. Embeddings use the native Gemini `batchEmbedContents` API (`GeminiEmbeddingModel`, new `AI_EMBEDDING_PROVIDER`/`_API_KEY`/`_BASE_URL`/`_TASK_TYPE` settings; TC-AI-11, TC-AI-14); reindex returns 503 if any batch fails (TC-AI-12). Backend CI green on `6a348cc` |
| E-02 | The eval scorecard reported a false PASS | The scorecard cannot be trusted until fixed; no AI quality result is recorded for this run | AI | **Fixed, confirmed** by the same run (12/13 cases passed; the scorecard gave a per-metric verdict and reported the one failing metric instead of a false PASS): the scorecard FAILs when zero cases ran or on any harness error (seeding or reindex), with *Errors* and *Why FAIL* sections (TC-AI-10, TC-AI-13). Backend CI green on `6a348cc` |
| E-03 | Run 35720654442: `citationPrecision` 0.86, below the 0.90 threshold; every other metric passes. Cause: one contrast citation. In `ask-02-filtered-parking` ("Which one has car parking?") the model answered that the Corner flat has covered car parking "whereas the Blue gate house only has bike parking" and cited both houses; the contrast is grounded in the fixture notes, but the golden set expected only the Corner flat | The eval gate is not passed yet, so AI stays off by default (AI-012) | AI | In progress: golden set v0.3 adds optional `allowedCitations` (houses that may be cited but are not required; `citationPrecision` counts them as correct, `citationRecall` still uses `expectedHouseIds` only) and the Ask prompt says to cite a house only for a fact taken from its record and to mention other houses only as brief, cited contrasts. Needs green Backend CI and a new `ai-evals.yml` run |

Detail and the fixes are in the AI docs ([ai/](ai/)); tests in [06](06-test-plan.md) §8 (TC-AI-10..14), data flow
DF-32 in [04](04-data-flow-diagrams.md), settings in [08](08-operations-runbook.md) §1.1. The eval gate for turning AI on by
default (AI-012) stays unmet until a run passes every metric (E-03).

### 5.3 First successful real AI eval run

| Field | Value |
|---|---|
| Date | 2026-09-22 |
| Workflow run | `ai-evals.yml` (manual), GitHub Actions run 35720654442, free-tier Gemini key (`AI_API_KEY` repository secret) |
| Code | Sprint 3 (expected `6a348cc`; confirm the head SHA on the run page at sign-off, since `ai-evals.yml` is manual and runs the ref chosen when it is started): native Gemini embeddings (E-01), fail-safe scorecard (E-02), contact redaction (F-30) |
| Cases | 13 ran, 12 passed (scorecard "Cases 12 / 13 passed"); failing case: `ask-02-filtered-parking`, check "cites only expected houses" |
| Metrics | All pass their thresholds except `citationPrecision`: 0.86 vs 0.90 (E-03, one contrast citation) |
| Verdict | FAIL on one metric; the scorecard is now trustworthy (no false PASS) and the remaining gap is a golden-set and prompt issue, not a provider or harness defect |
| Follow-up | E-03 (AI team): `allowedCitations` in the golden set (v0.3) and an Ask prompt tweak, then a new run |

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
| AI | Pending | Pending (E-03 open; confirm that run 35720654442's head SHA is `6a348cc`) |
| Backend, DevOps / Security, Docs | Pending | Pending |
| Product owner | – | Pending: lead decision recorded to close F-30 as Fixed; AI-012 gate still unmet (E-03) |

## 6. Next sprint candidates (Sprint 3)

| ID | Candidate | Why | Link | Status |
|---|---|---|---|---|
| C-01 | Commit `web/package-lock.json` (from the `web-package-lock` artifact) and switch `web.yml` to `npm ci` only | Reproducible web builds, A03 | 07 §2 | Part: lock file committed (`0e4e22a`); `web.yml` still has the `npm install` fallback |
| C-02 | `release.yml`: tag → signed APK + `.sha256` on a GitHub Release, signer fingerprint in the README | Finish F-11 / SEC-018 | 07 §5 | Open |
| C-03 | R8 keep rules (`proguard-rules.pro`) and a release smoke test, then turn on `isMinifyEnabled`/`isShrinkResources` | F-11, M7 | 07 §5 | Open |
| C-04 | Per-device API keys, stored hashed, with names and revocation | Close F-01b (SEC-025) | 02 §5 | Open |
| C-05 | Remaining Android unit tests: sync cursor and tombstones with a fake `ApiClient`, house-alert cooldown, accuracy gate, DTO mapping, photo pipeline | TC-U-06..08, TC-U-10, TC-U-11 | 06 §10 | Open |
| C-06 | Web: specs for `ai.service.ts` (`splitCitations`, `aiErrorMsg`), Playwright smoke + axe | TC-A-01, AI UI | 06 §10 | Open |
| C-07 | Run `ai-evals.yml` with a free-tier Gemini key (repository secret `AI_API_KEY`) and record the scorecard | TC-AI-10 (covers TC-AI-01..08 cases), AI-001 | [ai/](ai/) | Done: run 35720654442 (2026-09-22) recorded in section 5.3; follow-up E-03 |
| C-08 | `deploy.yml`, `backup.yml`, `keepalive.yml` | Operations (NFR-008) | 08 §3 | Open |
| C-09 | Drop the Tomcat override once Spring Boot manages 11.0.25+ | Keep the BOM authoritative | 07 §1 | Open |
| C-10 | Pin scanner images by digest; Gradle dependency locking so Trivy can scan Android dependencies | Supply chain (T-T5) | 07 §1 | Open |
| C-11 | Field walk test TC-F-01..08 on a real phone with the signed APK | Hunt mode quality, R-01 | 06 §6 | Open |
| C-12 | Dev `docker-compose.yml`: pass `APP_API_KEY_NEXT: ${APP_API_KEY_NEXT:-}` to the `api` service so the local stack can rehearse the key-rotation drill TC-O-02 (raised with the Backend/DevOps manager) | Rotation drill without a production-like stack | 08 §5.1 | Done (`docker-compose.yml`, `f7da5ab`) |
| C-13 | Leave the contact name out of the text sent to the LLM provider: `HouseDocuments.text()` (embedding text DF-32 and Ask context DF-21) and `HouseQueries.HouseDetails` (planner/MCP tool results); add a test that no contact name or phone is in that text; reindex after the change. Found by the Docs team in the DF-32 re-check; raised with the AI team | AI-010 says contact names are redacted by default, but the name goes to the provider on every index and reindex (F-30) | 02 §5 (F-30), 04 DF-32 | Done (S3-03): F-30 closed as Fixed by lead decision; evidence: TC-AI-15 green in the Backend workflow on `6a348cc` (the eval run's fixtures hold no contact data, so it is not a redaction test; see C-21) |

## 7. Sprint 4 candidates (not committed)

These are **candidates only**, not committed scope: they wait for the product owner to choose and order them at
Sprint 4 planning. The open Sprint 3 candidates in section 6 (C-01..C-06, C-08..C-11) stay on the list, and E-03
finishes first. Every candidate keeps the zero-cost rule and AI off by default (AI-001); any new third-party data
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
