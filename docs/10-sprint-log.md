# 10: Sprint log

| Field | Value |
|---|---|
| Document | Agile sprint log (goals, stories, sign-offs, CI results, retrospectives) |
| Version | 0.1 |
| Date | 2026-09-22 |
| Author | Claude (Cowork), Docs team |
| Status | Draft (Sprint 2 in progress) |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) | First version: working agreement, Sprint 1 (initial build, CI green except Security) and Sprint 2 (security fixes, key rotation, release signing, tests) with goals, stories, sign-offs, CI results, retrospectives and next-sprint candidates. Includes `ai-evals.yml` in S2-09/C-07, C-12 and a note that the first-push CI row is reconstructed from the `689927d` commit message. C-07 traces to TC-AI-10 (covers the TC-AI-01..08 cases) and AI-001, matching [06](06-test-plan.md). |

Related: [Requirements](01-requirements.md) · [Threat model](02-threat-model.md) · [Test plan](06-test-plan.md) · [Build and deploy](07-secure-build-and-deploy.md) · [Runbook](08-operations-runbook.md) · [CHANGELOG](../CHANGELOG.md)

---

## 1. How we work

House Hunt is built by small AI engineering teams, each with a team manager, for one product owner (the user).
Sprints are short (about one working session each). The log records what each sprint set out to do, what was
delivered, who signed it off and what CI said. It is the Agile record; the SSDLC documents 01 to 09 hold the detail.

| Role | Responsibility |
|---|---|
| Product owner (user) | Sets the goal, accepts the sprint result, decides on risks that stay open |
| Team manager (one per team) | Assigns the stories, reviews the team's work (logic, scope, file ownership), gives feedback, signs off the team's delivery |
| Team engineer | Builds the story inside the team's files, re-reads every changed file, reports what could not be verified |
| CI (GitHub Actions) | The only build and test environment (the engineering sandbox cannot reach Maven Central, Google Maven, Gradle or npm) |

**Teams and file ownership**

| Team | Owns |
|---|---|
| Backend | `backend/**` (except `backend/src/main/java/com/househunt/ai/**` and its tests) |
| AI | Spring AI code and tests, `docs/ai/**` |
| Android | `android/**` |
| Web | `web/**` |
| DevOps / Security | `.github/**`, `.gitleaks*`, Docker and compose files as agreed |
| Design | `docs/05-ux-accessibility-i18n.md`, string resources with Android and Web |
| Docs | `docs/**` except `docs/ai/**`, `README.md`, `CHANGELOG.md`, this log |

**Definition of done** (every story)

1. Code and docs in the same change; the document version is bumped and a dated change-log row added (docs/README).
2. New or changed tests are listed in [06](06-test-plan.md) and the RTM in [01 §12](01-requirements.md#12-requirements-traceability-matrix).
3. Security-relevant changes update the threat model ([02](02-threat-model.md)).
4. The team manager has reviewed and signed off.
5. All four CI workflows (`Backend`, `Web`, `Android`, `Security`) are green on the merge commit.
6. User-visible changes are in [CHANGELOG.md](../CHANGELOG.md) under Unreleased.

## 2. Sprint summary

| Sprint | Dates | Goal | Result | CI on the last commit |
|---|---|---|---|---|
| 1 | 2026-09-22 | Build the whole app (API, Android, web, AI, docs, CI) and get CI running | Delivered as 0.1.0 (`4b034d3`, `689927d`). Goal met except the Security gate. | Backend ✅ · Android ✅ · Web ✅ · Security ❌ (Trivy: Tomcat CVEs, DS-0002) |
| 2 | 2026-09-22 | All four workflows green; close the High/Critical findings; safer key and release handling; more tests | In progress (Unreleased) | Pending the first CI run after merge |

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
| S2-01 | As the owner the API runs on a Tomcat without known Critical CVEs | Backend | `tomcat.version` 11.0.25 override; `trivy sbom` clean for Tomcat (F-28 Fixed) | Done, awaiting CI |
| S2-02 | As the owner no image in the repo runs as root | Backend, DevOps / Security | `backend/db/Dockerfile` `USER postgres`; `trivy config` blocking on HIGH/CRITICAL (F-29 Fixed, TC-S-14) | Done, awaiting CI |
| S2-03 | As the owner a weak API key cannot be deployed | Backend | Startup fails below 32 chars, message names the variable only (F-01, SEC-002, TC-U-18) | Done, awaiting CI |
| S2-04 | As the owner I can rotate the API key without breaking my phone's sync | Backend, Docs | `APP_API_KEY_NEXT` accepted alongside the current key (SEC-017, TC-U-18, TC-I-21); runbook 08 §5.1 | Done, awaiting CI |
| S2-05 | As the user I can install a signed APK and check its signer | Android, DevOps / Security | Signing from `HH_*` secrets, `release` job verifies with `apksigner`, never on PRs (F-11 Part, TC-S-15) | Done, awaiting CI (skipped until the secrets are set) |
| S2-06 | As a developer web regressions are caught before merge | Web | Vitest + jsdom specs for score, config, interceptor, i18n; `npm run test:ci` in `web.yml` (TC-U-19..21) | Done, awaiting CI |
| S2-07 | As a developer core Android rules are unit-tested | Android | `SyncRules`, `StreetAlerts` extracted and tested; `ChecklistScoreTest`; more `ServerUrlTest` cases (TC-U-05..07, TC-U-15) | Done, awaiting CI |
| S2-08 | As the user Hunt mode and the map do not crash on permission loss or lifecycle changes | Android | Guarded `startForeground`, geocoder timeout, paired `MapView` lifecycle (03 R-01) | Done, awaiting CI and field test TC-F-07 |
| S2-09 | As the owner AI answer quality is measured before AI is turned on by default | AI | Golden set v0.2 with thresholds; `EvalScorerTest` runs in every build, `GoldenSetEvalTest` runs against a real model only with `AI_API_KEY` (TC-AI-09/10, see [ai/](ai/)); manual workflow `.github/workflows/ai-evals.yml` runs it and publishes the scorecard (job summary, artifact `ai-eval-report`) | Harness and workflow done, awaiting CI; first model run is C-07 |
| S2-10 | As the owner the docs match the code | Docs | 01, 02, 03, 06, 07, 08, 09 updated (Sprint 1 fixes: maplibre-gl 6.10 worker and CSP, F-27, SBOM, gitleaks ignore, Dependabot, compileSdk 37; Sprint 2 stories); CHANGELOG and this log created and linked | Done |

### 4.2 CI results

| Run | Backend | Web | Android | Security |
|---|---|---|---|---|
| First run after merge | Pending | Pending | Pending | Pending |

Record the run here when it finishes. Expected: Security turns green (F-28, F-29); the Android `release` job is
skipped with a notice until the four `HH_*` secrets exist.

### 4.3 Sign-off

| Team | Manager review | Sign-off |
|---|---|---|
| Backend | Pending | Pending |
| Android | Pending | Pending |
| Web | Pending | Pending |
| AI | Pending | Pending |
| DevOps / Security | Pending | Pending |
| Docs | Pending | Pending |
| Product owner | – | Pending green CI |

Each manager records the review result (accepted / changes requested) and the date. The product owner accepts the
sprint when all four workflows are green.

### 4.4 Retrospective (draft, to confirm at sprint review)

| What went well | What to improve |
|---|---|
| Manager reviews again kept each team's delivery logical and inside its own files; teams worked in parallel on backend, Android, web, AI, CI and docs without conflicts | Still no local builds: every compile and test result comes from CI, so a mistake costs a full round trip. Mitigation: small changes, verify uncertain APIs and versions by reading upstream source, re-read every changed file |
| Security findings were fixed at the source (Tomcat override with a removal note, `USER postgres`) instead of being suppressed | Version overrides (Tomcat) need a reminder to remove them; the runbook's weekly task now covers it |
| Tests were added with the changes (key rules, rotation, sync and street rules, web core) | R8 is still off and there are no instrumented Android tests, so the release build is not exercised on a device |
| The key rotation design (dual key) turned an outage into a routine task | Sign-offs and CI results must be recorded here as soon as they happen |

## 5. Next sprint candidates (Sprint 3)

| ID | Candidate | Why | Link |
|---|---|---|---|
| C-01 | Commit `web/package-lock.json` (from the `web-package-lock` artifact) and switch `web.yml` to `npm ci` only | Reproducible web builds, A03 | 07 §2 |
| C-02 | `release.yml`: tag → signed APK + `.sha256` on a GitHub Release, signer fingerprint in the README | Finish F-11 / SEC-018 | 07 §5 |
| C-03 | R8 keep rules (`proguard-rules.pro`) and a release smoke test, then turn on `isMinifyEnabled`/`isShrinkResources` | F-11, M7 | 07 §5 |
| C-04 | Per-device API keys, stored hashed, with names and revocation | Close F-01 (SEC-025) | 02 §5 |
| C-05 | Remaining Android unit tests: sync cursor and tombstones with a fake `ApiClient`, house-alert cooldown, accuracy gate, DTO mapping, photo pipeline | TC-U-06..08, TC-U-10, TC-U-11 | 06 §10 |
| C-06 | Web: specs for `ai.service.ts` (`splitCitations`, `aiErrorMsg`), Playwright smoke + axe | TC-A-01, AI UI | 06 §10 |
| C-07 | Run `ai-evals.yml` with a free-tier Gemini key (repository secret `AI_API_KEY`) and record the scorecard | TC-AI-10 (covers TC-AI-01..08 cases), AI-001 | [ai/](ai/) |
| C-08 | `deploy.yml`, `backup.yml`, `keepalive.yml` | Operations (NFR-008) | 08 §3 |
| C-09 | Drop the Tomcat override once Spring Boot manages 11.0.25+ | Keep the BOM authoritative | 07 §1 |
| C-10 | Pin scanner images by digest; Gradle dependency locking so Trivy can scan Android dependencies | Supply chain (T-T5) | 07 §1 |
| C-11 | Field walk test TC-F-01..08 on a real phone with the signed APK | Hunt mode quality, R-01 | 06 §6 |
| C-12 | Dev `docker-compose.yml`: pass `APP_API_KEY_NEXT: ${APP_API_KEY_NEXT:-}` to the `api` service so the local stack can rehearse the key-rotation drill TC-O-02 (raised with the Backend/DevOps manager) | Rotation drill without a production-like stack | 08 §5.1 |
