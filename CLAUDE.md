# Doorprints: notes for Claude Code sessions

Doorprints remembers the houses you visit while house-hunting in India. Android app (Kotlin, Compose, Room, MapLibre),
web app/PWA (Angular, MapLibre GL, IndexedDB) at https://doorprints.web.app, and an optional self-hosted Spring Boot
server (Java 25, PostGIS). Shared Kotlin Multiplatform logic lives in `android/shared`;
`android/ui` (`:ui`) holds the Compose Multiplatform UI (ADR-23).

## Start here

1. Read `docs/14-lead-backlog-and-handoff.md`: current state, open pull requests and the next steps, in order.
2. Team-level tickets: `docs/10-sprint-log.md` §12 (Sprint 4b) and §12.7 (S4b-BL-1..17).
3. The SSDLC set is `docs/01`..`docs/12` (index: `docs/README.md`). Docs are updated in the same change as code.

## Rules (owner decisions)

- **Zero cost.** No paid services, plans or data.
- **India's boundaries** are shown as the Government of India depicts them, on web and Android alike
  (`docs/03-design.md` ADR-22; `web/src/app/shared/india-boundaries.ts`, `android/.../ui/IndiaViewRules.kt`;
  data `web/public/geo/in-boundaries.geojson`, byte-identical Android copy). Any base-map change re-runs TC-M-25.
- **No Play Store release and no public server** until the release security gate exists and passes (`docs/10` §12.5).
- **Brand words:** *Import a backup*, *Save a copy*, *readable copies*, *Add a shared listing*; never "Restore" as a
  button label (`docs/12`). Hindi, Tamil and Telugu strings ship marked *under review*.
- **Every change goes through a pull request to `main`; the owner merges.** Do not merge your own pull request.
- Work on a branch; CI runs on every branch push (Web, Backend, Android, Shared-iOS, Security, CodeQL). Deploy,
  signing and the dependency graph run on `main` only.
- Commit trailer: `Co-Authored-By: Claude <noreply@anthropic.com>`.

## How changes are reviewed (keep it lean)

For each change: the engineer self-checks (UI: accessibility, all four languages, both themes, loading/empty/error
states; security: no secrets, least privilege, input validation), then one reviewer pass per area touched
(code; plus design/UX for UI changes), then a second pass only on the delta. Record anything out of scope as a
backlog item instead of expanding the change.

## Build and test (what CI runs)

- Web: `cd web && npm ci && npx ng test --watch=false && npx ng build` (Node 24).
- Android: `cd android && ./gradlew assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest
  :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata -Proborazzi.test.verify=true` (JDK 21;
  the metadata tasks catch JVM-only calls in common code on Linux; the flag fails on a changed screenshot, re-record
  with `./gradlew :app:recordRoborazziDebug`).
- Backend: `cd backend && mvn -B -ntp verify` (JDK 25; needs the PostGIS container from `backend/db`, see
  `.github/workflows/backend.yml`).
- After every merge to `main`, once the deploy has finished, test the live web UI with `tools/live-ui`
  (`npm ci && npx playwright install chromium && node live-ui.js`; owner rule 2026-09-24, `docs/06` TC-M-26).
- The workflow files in `.github/workflows` are the source of truth for exact steps and path filters.
