# Changelog

All notable changes to House Hunt are recorded here. The format follows
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Security fixes reference the finding ids (F-xx) in the
[threat model](docs/02-threat-model.md); sprint detail is in the [sprint log](docs/10-sprint-log.md).

Versions: Android `versionName` (`android/app/build.gradle.kts`), backend `pom.xml` and web `package.json` move
together. Until a release is tagged, everything on `main` after 0.1.0 is listed under **Unreleased**.

The version headings are not links yet: they will link to GitHub compare/tag URLs (link reference definitions at
the end of this file) once `v0.1.0` is tagged (C-02 in the [sprint log](docs/10-sprint-log.md)).

## [Unreleased]

Sprint 2 (2026-09-22). Merged to `main` only after all four CI workflows are green.

### Added

- API key rotation without downtime: optional second key `APP_API_KEY_NEXT`, accepted alongside `APP_API_KEY`
  while clients move to the new key (SEC-017). Procedure: [runbook 5.1](docs/08-operations-runbook.md).
- Signed Android release builds: `app/build.gradle.kts` reads `HH_KEYSTORE_FILE`, `HH_KEYSTORE_PASSWORD`,
  `HH_KEY_ALIAS` and `HH_KEY_PASSWORD`; the `android.yml` job `release` (push to `main` or manual only) builds,
  verifies with `apksigner` and uploads `house-hunt-release-apk` when the secrets exist (F-11, part).
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

### Changed

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

### Security

- F-28 (Critical): embedded Tomcat raised from 11.0.24 to **11.0.25** with a `tomcat.version` override in
  `backend/pom.xml` for CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525. Remove the override when Spring Boot
  manages 11.0.25 or later.
- F-29 (Trivy DS-0002): the development/CI database image `backend/db/Dockerfile` now runs as the `postgres` user
  instead of root. Host bind mounts for its data must be owned by uid 999.
- F-01 (High, now partly fixed): 32-character minimum key length and dual-key rotation; per-device keys are still
  backlog (SEC-025).

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
