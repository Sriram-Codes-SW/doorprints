# 10: Sprint log

| Field | Value |
|---|---|
| Document | Agile sprint log (goals, stories, sign-offs, CI results, retrospectives) |
| Version | 0.13 |
| Date | 2026-09-22 |
| Author | Claude (Cowork), Docs team |
| Status | Draft (Sprint 3.5 KMP foundation delivered, CI result to record; Sprint 4a/4b scope set by the product owner, 4b with the 2026-09-22 additions) |

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
| Backend | `backend/**` (except `backend/src/main/java/com/househunt/ai/**` and its tests), `docker-compose.yml` (since Sprint 3; it passes the `AI_*` settings, see [07](07-secure-build-and-deploy.md) §7) |
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
| 3 | 2026-09-22 | First real AI eval run and its fixes; contact redaction (C-13); remaining Sprint 2 candidates | Mostly met (`6a348cc`): embeddings and scorecard fixes confirmed by the first real Gemini eval run (12/13 cases passed, all metrics but `citationPrecision` pass, E-03 open with the AI team); F-30 closed as Fixed; product renamed to Doorprints (S3-07, waiting on CI). Closing | Backend ✅ · Security ✅ on `6a348cc` (Android and Web not triggered: no `android/` or `web/` changes). S3-07 rename: all four workflows pending on the merge commit |
| 3.5 | 2026-09-22 | KMP foundation: make the Android code Kotlin Multiplatform-ready without an iOS app (section 9) | Delivered in `8f583af` (`:shared` module, Ktor client, Room schema guard, compile-only iOS CI) | Security ❌ on `8f583af` (new `gradle-dependency-graph` job failed at submission; follow-up `feb0294`: Security ✅, submission to confirm from the log). Shared iOS compile ✅, Android ✅ to confirm, Backend ❌ on `feb0294` (`EvalScorerTest` empty-list assertion, test-only fix by the AI team, 9.2) |

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
| S3-01 | As the owner the first real AI eval run (`ai-evals.yml`, C-07) is recorded and its defects are fixed | AI | Done: E-01 and E-02 fixed and confirmed by the first successful real Gemini eval run (2026-09-22, Actions run 35720654442, section 5.3); follow-up E-03 (`citationPrecision` 0.86 < 0.90) in progress |
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
| E-03 | Run 35720654442: `citationPrecision` 0.86, below the 0.90 threshold; every other metric passes. Cause: one contrast citation. In `ask-02-filtered-parking` ("Which one has car parking?") the model answered that the Corner flat has covered car parking "whereas the Blue gate house only has bike parking" and cited both houses; the contrast is grounded in the fixture notes, but the golden set expected only the Corner flat | The eval gate is not passed yet, so AI stays off by default (AI-012) | AI | In progress: golden set v0.3 adds optional `allowedCitations` (houses that may be cited but are not required; `citationPrecision` counts them as correct, `citationRecall` still uses `expectedHouseIds` only) and the Ask prompt says to cite a house only for a fact taken from its record and to mention other houses only as brief, cited contrasts. Needs green Backend CI and a new `ai-evals.yml` run |

Detail and the fixes are in the AI docs ([ai/](ai/)); tests in [06](06-test-plan.md) §8 (TC-AI-10..14), data flow
DF-32 in [04](04-data-flow-diagrams.md), settings in [08](08-operations-runbook.md) §1.1. The eval gate for turning AI on by
default (AI-012) stays unmet until a run passes every metric (E-03).

### 5.3 First successful real AI eval run

| Field | Value |
|---|---|
| Date | 2026-09-22 |
| Workflow run | `ai-evals.yml` (manual), GitHub Actions run 35720654442, free-tier Gemini key (`AI_API_KEY` repository secret) |
| Code | Commit `6a348cc` (Sprint 3; confirmed on the run page, `ai-evals.yml` is manual and runs the ref chosen when it is started): native Gemini embeddings (E-01), fail-safe scorecard (E-02), contact redaction (F-30) |
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
| AI | Pending | Pending (E-03 open; run 35720654442 ran on `6a348cc`, confirmed) |
| Backend, Android, Web, DevOps / Security, Docs | Pending | Pending (S3-07 rename: CI on the merge commit) |
| Product owner | – | Pending: lead decision recorded to close F-30 as Fixed; AI-012 gate still unmet (E-03) |

## 6. Next sprint candidates (Sprint 3)

| ID | Candidate | Why | Link | Status |
|---|---|---|---|---|
| C-01 | Commit `web/package-lock.json` (from the `web-package-lock` artifact) and switch `web.yml` to `npm ci` only | Reproducible web builds, A03 | 07 §2 | Part: lock file committed (`0e4e22a`); `web.yml` still has the `npm install` fallback |
| C-02 | `release.yml`: tag → signed APK + `.sha256` on a GitHub Release, signer fingerprint in the README | Finish F-11 / SEC-018 | 07 §5 | Open |
| C-03 | R8 keep rules (`proguard-rules.pro`) and a release smoke test, then turn on `isMinifyEnabled`/`isShrinkResources` | F-11, M7 | 07 §5 | Open |
| C-04 | Per-device API keys, stored hashed, with names and revocation | Close F-01b (SEC-025) | 02 §5 | Open |
| C-05 | Remaining Android unit tests: sync cursor and tombstones with a fake `ApiClient`, house-alert cooldown, accuracy gate, DTO mapping, photo pipeline | TC-U-06..08, TC-U-10, TC-U-11 | 06 §10 | Part (Sprint 3.5): DTO mapping done (`ModelMappingTest`, TC-U-10); a fake `ApiClient` is now easy with Ktor `MockEngine` |
| C-06 | Web: specs for `ai.service.ts` (`splitCitations`, `aiErrorMsg`), Playwright smoke + axe | TC-A-01, AI UI | 06 §10 | Open |
| C-07 | Run `ai-evals.yml` with a free-tier Gemini key (repository secret `AI_API_KEY`) and record the scorecard | TC-AI-10 (covers TC-AI-01..08 cases), AI-001 | [ai/](ai/) | Done: run 35720654442 (2026-09-22) recorded in section 5.3; follow-up E-03 |
| C-08 | `deploy.yml`, `backup.yml`, `keepalive.yml` | Operations (NFR-008) | 08 §3 | Open |
| C-09 | Drop the Tomcat override once Spring Boot manages 11.0.25+ | Keep the BOM authoritative | 07 §1 | Open |
| C-10 | Pin scanner images by digest; Gradle dependency locking so Trivy can scan Android dependencies | Supply chain (T-T5) | 07 §1 | Part (Sprint 3.5): the `gradle-dependency-graph` job in `security.yml` sends the Android graph to Dependabot alerts; digests and lock files still open |
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
| C-22 | **CodeQL** code scanning (Java/Kotlin, JavaScript/TypeScript) and SARIF upload of Semgrep to the Security tab | Free now that the repository is public; better SAST coverage | DevOps, Security | Sprint **4a** candidate. Kotlin with AGP 9 built-in Kotlin may need advanced setup with a manual Gradle build; check before committing ([07](07-secure-build-and-deploy.md) §1) |
| C-23 | PR flow and an always-running **`CI summary`** check, then required status checks, "require PR" and linear history in the ruleset | Required checks today would block PRs whose paths skip a workflow | DevOps, Docs | [07](07-secure-build-and-deploy.md) §3.1; only `CI summary` becomes required |
| C-24 | **Vertex AI** provider next to AI Studio, switchable by configuration; evals run against both | PO-3; real data off the free tier (PRV-022) | AI, Backend | **Owner setup done (2026-09-22, section 10):** project `doorprints-ai`, GitHub variables `GCP_PROJECT_ID=doorprints-ai`, `GCP_LOCATION=asia-south1`, `AI_VERTEX_EMBEDDING_LOCATION=global` (step 8: chat verified in `asia-south1`, `gemini-embedding-2` only on `global`); done-when item (2) is met for step 8; step 10 is **started**: the first `provider=vertex` eval (run 35753477789, `gemini-3.5-flash` in `asia-south1` + `gemini-embedding-2` on `global`, commit `8f583af`) failed only on `citationPrecision` 0.78 (7/9), answered in `feb0294` by golden set v0.5 and the rule that only inline `[house:id]` markers count as citations (thresholds not lowered); the re-run on `feb0294` or later and the **credit check** are open, and so are items (3), (4); DevSecOps review of `ai-evals.yml` signed off. Earlier status: **Code landed (2026-09-22), pending first CI.** Shipped: `AI_PROVIDER` switch (`aistudio` default, `vertex`), Vertex chat through Spring AI's Google GenAI starter with the app's own `Client` (ADC only, bounded retries), `VertexEmbeddingModel` (`:embedContent` / `:predict`, one text per call), `503` + `code: AI_QUOTA_EXHAUSTED` + `Retry-After: 60` for provider quota errors, re-index and eval stop on quota, `AI_INDEX_ON_CHANGE`, `ai-evals.yml` `provider` (default `aistudio`; `vertex` chosen by hand after vertex-setup steps 1-8, the default flips to `vertex` only after step 10) and `embedding_model` inputs with Workload Identity Federation; a `setupHint` on the `503` for Vertex AI 401/403/404 (`AiExceptionHandlerTest`, TC-AI-22); owner setup in [ai/vertex-setup.md](ai/vertex-setup.md). **Done when:** (1) Backend workflow green with the new tests (TC-AI-16, TC-AI-18..22 in [06](06-test-plan.md)); (2) owner's setup step 8 (models answer in `asia-south1`, or `AI_VERTEX_EMBEDDING_LOCATION` / `GCP_LOCATION=global` chosen) and step 10 (the trial credit pays for Vertex AI; first `extract` eval run green, with `provider=vertex` chosen by hand), then the `ai-evals.yml` default flipped to `vertex` (step 10 item 6; until then the default run uses AI Studio); (3) full eval (TC-AI-10) on `provider=vertex` recorded in [ai/ai-design.md](ai/ai-design.md) §8.5; (4) **spend cap trip (manager review):** the provider response when the AI-015 spend cap blocks the service is captured (or taken from Google's documentation), mapped to a clear "cloud AI paused" problem that is **not retried** (no SDK/embedding retries, `retryable: false`, no short `Retry-After`; re-index and eval stop at once), and pinned in a contract test next to the `AI_QUOTA_EXHAUSTED` tests, which today cover HTTP 429 only ([01](01-requirements.md) AI-017). Docs sync done in this change set (02 T-I22, 03 §13, 04 E6/DF-21/DF-32, 06, 07 §4 and §7, 08 §1.1, IR-9, §9) |
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
| `feb0294` | Backend (35755840287) | ❌ **Failed** (`mvn verify` exit code 1): 258/259 tests passed; `EvalScorerTest.goldenSetFileIsConsistent` threw `IllegalArgumentException: The iterable of values to look for should not be empty`. Cause: `ask-01` in golden set v0.5 has `allowedCitations` but no `mustNotCite`, and AssertJ's `doesNotContainAnyElementsOf` throws on an empty list (a test bug, not a product bug; the new Ask citation rule tests passed). Fix: AI team, `backend/src/test/java/com/househunt/ai/eval/EvalScorerTest.java` only, in the next commit: every id-list check skips an empty or missing list ("no constraint"); golden set and thresholds unchanged ([06](06-test-plan.md) TC-AI-09, [CHANGELOG](../CHANGELOG.md)) | High: failure and cause confirmed by the AI team from the run's test report; green Backend run on the fix commit still to record |

`feb0294` touches no Android, web or shared code, so Android, Web and Shared iOS compile did not run on it.

### 9.3 Phase 2 plan (not scheduled)

1. Room KMP (Room 2.8 in `commonMain`, bundled SQLite driver), keeping `househunt.db`, version 2, `MIGRATION_1_2` and
   `2.json`; migration test from real v1/v2 files; then the mappers.
2. DataStore KMP; `expect/actual` secret storage (Android Keystore / iOS Keychain).
3. `ServerUrl` as a common parser or `expect/actual`.
4. iOS app (SwiftUI over the shared framework, or Compose Multiplatform) only when a Mac and the Apple Developer
   Program are available; `iosMain` with `ktor-client-darwin`; run the iOS tests on a simulator in CI.
5. iOS platform services: `CLLocationManager` (region monitoring also suits Hunting areas, 20 regions per app),
   `CLGeocoder`, `BGTaskScheduler`, `NWPathMonitor`.

Until then iPhone users are served by the PWA ([11](11-feature-parity-and-export-spec.md) 5.10, Sprint 4a).

### 9.4 Sign-off and retrospective

Sign-off: the Android and DevSecOps senior reviewers' sign-offs are to be recorded here with the CI results (9.2); the Docs change is this one (S3.5-06). Retrospective notes: the
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
| Next | Re-run `provider=vertex` on `feb0294` or later; step 10 credit check (did the cost come off the trial credit?); step 9 (capture real responses); then flip the `ai-evals.yml` default (C-24) |
