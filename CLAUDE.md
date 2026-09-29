# Doorprints: notes for Claude Code sessions

Doorprints remembers the houses you visit while house-hunting in India. Android app (Kotlin, Compose, Room, MapLibre),
web app/PWA (Angular, MapLibre GL, IndexedDB) at https://doorprints.web.app, and an optional self-hosted Spring Boot
server (Java 25, PostGIS). Shared Kotlin Multiplatform logic lives in `android/shared`;
`android/ui` (`:ui`) holds the Compose Multiplatform UI (ADR-23).

## Start here

1. Read `docs/14-lead-backlog-and-handoff.md`: current state (§1), the next steps in order (§2, N13), and how a
   session works here (§7: standing permissions, per-change steps, pitfalls). Pick up open pull requests first.
2. Team-level tickets: `docs/10-sprint-log.md` §12 (Sprint 4b), §12.7 (the S4b-BL backlog, S4b-BL-1 onwards) and §13
   (the Compose Multiplatform track, CMP-0..CMP-9).
3. The SSDLC set is `docs/01`..`docs/12` (index: `docs/README.md`). Docs are updated in the same change as code.

## Rules (owner decisions)

- **Zero cost.** No paid services, plans or data.
- **India's boundaries** are shown as the Government of India depicts them, on web and Android alike; the Survey of
  India's maps and boundary data are the standard (DST geospatial guidelines of 2021, clause 8 xiii; self-certification
  in `docs/03-design.md` §11.1, re-read at each release) (`docs/03-design.md` ADR-22;
  `web/src/app/shared/india-boundaries.ts`, `android/.../ui/IndiaViewRules.kt`;
  data `web/public/geo/in-boundaries.geojson`, byte-identical Android copy). Any base-map change re-runs TC-M-25.
- **No Play Store release and no public server** until the release security gate exists and passes (`docs/10` §12.5).
- **Search grows with the house values** (owner, 2026-09-28): a change that adds a house field or changes what one holds
  also updates both apps' search (web `searchText` in `pages/map/map-list.ts`, Android `HouseListScreen.kt`), with a test.
- **Brand words:** *Import a backup*, *Save a copy*, *readable copies*, *Add a shared listing*; never "Restore" as a
  button label (`docs/12`). Hindi, Tamil and Telugu strings ship marked *under review*.
- **Keep the repository optimised** (owner, 2026-09-29) on every branch, pull request and `main`: no new library where
  the platform has the API, few and small binary files (screenshots included), efficient code paths, CI used only
  after local checks; details in `docs/14-lead-backlog-and-handoff.md` §7.
- **Every change goes through a pull request to `main`.** Since 2026-09-29 the owner lets a Claude session merge its
  own pull request once it is good (all checks green, no conflict, no open review thread); how a session works here,
  step by step, is `docs/14-lead-backlog-and-handoff.md` §7.
- Work on a branch and open the pull request first (a draft is fine): CI runs on pull requests and on pushes to
  `main`, not on branch pushes (Web, Backend, Android, Android emulator, Shared-iOS, Security, CodeQL, Pages; since
  2026-09-29). Deploy, signing and the dependency graph run on `main` only.
- **Branch names say what the work is** (owner, 2026-09-29): `<type>/<topic>`, the topic a few lowercase words joined
  by hyphens, with the ticket id when there is one. Types: `feat`, `fix`, `docs`, `ci`, `chore`, `refactor`, `test`.
  Examples: `feat/cmp-9-maplibre-compose`, `fix/india-boundary-lines`, `docs/branch-naming-rule`. One branch per
  pull request, made from the latest `main`; never reuse a merged branch. This applies to Claude sessions too: do
  not work on a session's generated name (such as `claude/sleepy-brown-479259`); make a descriptive branch instead.
- **Licence notices** (FSF, 2026-09-29): every new source file starts with the copyright and AGPL notice; run
  `python3 .github/scripts/licence-headers.py --fix` before committing (CI's `--check` fails without it). Licence:
  `AGPL-3.0-only` with the section 7 permissions and trademark notice in `NOTICE`.
- Commit trailer: `Co-Authored-By: Claude <noreply@anthropic.com>`.
- **Commit identity:** Claude sessions commit as `Claude <noreply@anthropic.com>`; the owner as
  `329133251+Sriram-Codes-SW@users.noreply.github.com`. Never a bare `*@users.noreply.github.com` address: GitHub credits
  `NAME@users.noreply.github.com` to the account NAME (17 commits of 2026-09-23 made as `noreply@users.noreply.github.com`
  list the unrelated account `noreply` as a contributor; `docs/10` S4b-BL-53). Check `git config user.email` first.

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
  with `./gradlew :app:recordRoborazziDebug`). Also, on Linux, `./gradlew -Pkotlin.native.enableKlibsCrossCompilation=true
  :ui:compileKotlinIosSimulatorArm64` after a change under `iosMain` (Kotlin/Native-only errors, a minute locally).
- Cloud sessions: `.claude/hooks/session-start.sh` installs the Android SDK, a Gradle mirror, `web/node_modules` and
  MkDocs at start (`docs/14` §7).
- Backend: `cd backend && mvn -B -ntp verify` (JDK 25; needs the PostGIS container from `backend/db`, see
  `.github/workflows/backend.yml`).
- Not CI: after a merge to `main` that runs the `Web` deploy (a change under `web.yml`'s path filter: `web/**`,
  `.github/workflows/web.yml`, `.github/firebase-tools/**`), once the deploy has finished, test the live web UI with
  `tools/live-ui` (`npm ci && npx playwright install chromium && node live-ui.js`). An Android-only or docs-only merge
  skips it (owner rule 2026-09-24, refined the same day: no web change, no run; `docs/06` TC-M-26).
- The workflow files in `.github/workflows` are the source of truth for exact steps and path filters.
