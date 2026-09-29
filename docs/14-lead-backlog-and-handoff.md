# 14. Lead backlog and handoff

| Field | Value |
|---|---|
| Version | 0.38 |
| Date | 2026-09-29 |
| Owner | Sriram (product owner); lead: Claude |
| Purpose | Everything pending at the end of the Cowork sessions of 2026-09-22..24, in one place, so a new Claude Code session (web or CLI) can continue without the old session's notes. Team-level tickets stay in [10](10-sprint-log.md) §12.7 (S4b-BL-1..65); this file lists the lead-level items and points to the rest. |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-24 | Claude (Cowork), lead | First version: state of `main`, open pull requests, the ordered next steps, parked items and owner decisions still in force. |
| 0.2 | 2026-09-24 | Claude (Code), lead | N1 done: PR #14 merged by the owner's instruction (`f8ee821`). N3 built on `fix/brand-footprints`, PR #15: the owner chose **left/right/left** over the right/left/right first written in N3 (so the big toes face each other) and kept the same three prints in the favicon; renders shown to the owner before the PR. §1 updated to `main` = `f8ee821` and open PR #15. The top print moved from (79,55) to (78.5,51.5) in the design review (spacing only, as N3 allows), so that its heel no longer touches the middle print. |
| 0.3 | 2026-09-24 | Claude (Code), Docs team | The doubled lines and the Assam-Arunachal Pradesh state line are fixed on branch `fix/india-boundary-lines`, PR #16 ([10](10-sprint-log.md) §12.10, S4b-BL-11, -15, -16; after the design review the India-China border is drawn by India's outline alone): new line in §1; N2 records TC-M-25 step (3) with Google Maps' India region, and the owner's check now also looks at the India-China border, the hand-overs and the state line. |
| 0.4 | 2026-09-24 | Claude (Code), Docs team | N2 after the Singalila spur fix (round 2 reviews) of PR #16: the owner's check also looks for no spur on the Singalila ridge, the known minors are sized (Sikkim tri-junction loops; the tile line's overrun at Jomotsangkha and Longwa, [10](10-sprint-log.md) S4b-BL-17); the backlog range in the header and N6 is S4b-BL-1..17. |
| 0.5 | 2026-09-24 | Claude (Code), Docs team | **Owner request of 2026-09-24: move the Android UI to Compose Multiplatform** ([03](03-design.md) ADR-23, [10](10-sprint-log.md) §13). §1: PR #16 merged (`4100f7a`, was open), `main` = `01937f6` (Dependabot #12 on top of `4100f7a`), and CMP-1 (`be86f50`) on branch `claude/doorprints-dev-continue-fzcge2`. N5: Dependabot #12 is merged (`01937f6`: Kotlin 2.4.20, AGP 9.4.1, Compose BOM 2026.09.00); #10, a major, is left for a ticket. New **N7**: the Compose Multiplatform track, next CMP-2 (strings to compose-resources). **P3** (iPhone app) now points to ADR-23. |
| 0.6 | 2026-09-24 | Claude (Code), Docs team | **Owner requests of 2026-09-24: test the Android APK (all three options: emulator CI job, screenshot tests, Firebase Test Lab) and test the web UI in detail after every merge to `main`.** §1: PR #17 (CMP-1) merged, `main` = `75f049d`; the test harness (CMP-0, commit `afe4064`) is open as PR #18. N7: the phases now rely on the screenshot and smoke tests. New **N8** (the live UI test after each merge) and an owner to-do (Firebase Test Lab setup). §5: new standing rule, the live web UI test after every merge to `main`. |
| 0.7 | 2026-09-24 | Claude (Code), Docs team | Round 2 of the test-harness pull request: the first emulator run found a real crash (opening the app from a notification while it was not running), fixed in `Root.kt`; Firebase Test Lab gets a Workload Identity provider of its own (`FTL_WIF_PROVIDER`), so the owner to-do no longer touches the Hosting provider; N8 names `npx playwright install chromium` and the exit code. |
| 0.8 | 2026-09-24 | Claude (Code), Docs team | Round 3 review of PR #18: the screenshot test id is **TC-U-56** (was TC-U-60); the Test Lab owner to-do names a new pool `github-test-lab`. |
| 0.9 | 2026-09-24 | Claude (Code), Docs team | Round 4 of PR #18: the Test Lab owner to-do adds the results bucket and the variable `FTL_RESULTS_BUCKET`, and the decision it needs first (a bucket needs a billing account; zero-cost rule). |
| 0.10 | 2026-09-24 | Claude (Code), Docs team | PR #18 round 5: the smoke tests **passed on the emulator (CI, commit `bc57361`)**; Test Lab is off by default at zero cost (a results bucket needs a billing account), options for the owner in 07 §7.2. |
| 0.11 | 2026-09-24 | Claude (Code), Docs team | PR #18 round 6: a second real bug found by the pull-request emulator run on `bc57361` (the Map's camera moved off the main thread after `currentLocation()`; fixed in `MapScreen.kt` with `withContext(Dispatchers.Main.immediate)`); the result now reads "passed on the emulator; one of two runs on `bc57361` found a threading bug, fixed in the next commit; the re-run is pending". |
| 0.12 | 2026-09-24 | Claude (Code), Docs team | PR #18 round 7: §1 and N7 use the agreed emulator wording (the threading bug fixed in `6376706`, re-run pending). Then both emulator runs on `6376706` passed (push and pull request; `ee30b92`). |
| 0.13 | 2026-09-24 | Claude (Code), Docs team | Reviews of PR #19 (CMP-2 and the ADR-24 rename). §1: PR #18 merged, `main` = `6da0e56`; PR #19 open on `claude/doorprints-dev-continue-fzcge2` (CMP-2 `80b198b`, the rename, review fixes `927d54b`). N6: backlog S4b-BL-1..21. N7: CMP-2 done in PR #19, awaiting merge; next CMP-3. New **N9**: the backlog from the PR #19 reviews (S4b-BL-18..21). §6: owner to-dos for a self-hosted server (the mandatory dump and restore of [08](08-operations-runbook.md) §11; the MCP tool `askHouseHunt` → `askDoorprints` in saved permissions). |
| 0.14 | 2026-09-24 | Claude (Code), engineer | CMP-3. §1: PR #19 merged, `main` = `7080a7f`; CMP-3 done in code on `claude/doorprints-dev-continue-fzcge2`, PR #20 open (the owner merges). N6: backlog S4b-BL-1..22. N7: CMP-3 done in code; next CMP-4. N9: S4b-BL-18 done in CMP-3; S4b-BL-22 new. |
| 0.15 | 2026-09-24 | Claude (Code), engineer | CMP-4 P4a. §1: PR #20 (CMP-3) merged, `main` = `fccf8a1`; CMP-4 P4a (Room KMP in `:shared`) done in code on `claude/doorprints-dev-continue-fzcge2`, PR #21, open (the owner merges). N6: backlog S4b-BL-1..25. N7: P4a done in code; next P4b. N9: S4b-BL-23 and S4b-BL-24 (new in P4a). |
| 0.16 | 2026-09-24 | Claude (Code), engineer | CMP-4 P4b. §1: PR #21 (CMP-4 P4a) merged, `main` = `e480b83`; CMP-4 P4b (settings, `SecretStore` and `ServerUrl` in `:shared`) done in code on `claude/doorprints-dev-continue-fzcge2`, PR #22, open (the owner merges). N6: backlog S4b-BL-1..30. N7: P4b done in code; next P4c. N9: S4b-BL-25 listed; S4b-BL-26 to S4b-BL-30 (new in P4b). N8 and §5: the live UI test only after a merge that runs the `Web` deploy (owner rule refined, 2026-09-24). |
| 0.17 | 2026-09-24 | Claude (Code), engineer | CMP-4 P4c. §1: PR #22 (CMP-4 P4b) merged, `main` = `aa8f73a`; CMP-4 P4c (a common `Repository` interface, `CompareScreen` and `HouseFormRules` in `:ui`) done in code on `claude/doorprints-dev-continue-fzcge2`, PR #23, open (the owner merges). Commits: `a4e7e59` (CLAUDE.md's tickets pointer names the open-ended S4b-BL backlog and the CMP track, owner request), `17cbf2d`, `c9be8e8`, `16f5bf0`. N6 and the header: backlog S4b-BL-1..34. N7: CMP-4 done in code; next CMP-5, CMP-6 and CMP-7 as one combined change (owner request of 2026-09-24), one PR per phase except CMP-5..7. N9: S4b-BL-23 and S4b-BL-28 done; S4b-BL-31 to S4b-BL-34 (new in P4c and its review). |
| 0.18 | 2026-09-24 | Claude (Code), lead | CMP-5, CMP-6 and CMP-7 as one change with the web backlog, the shared items and the phone display fixes. §1: PR #23 (CMP-4 P4c) merged, `main` = `f8dd6f9`; PR #24 open on `claude/doorprints-dev-continue-fzcge2` with the combined change ([10](10-sprint-log.md) §13.9). N2: the hand-over minors after S4b-BL-17 and the zoom 9-14 admin-line check; the device check after CMP-8. N6 and the header: backlog S4b-BL-1..50. N7: CMP-5..7 done in code; next CMP-8, then the owner's real-device check. N8: after PR #24 merges, the live UI test with the mobile pass. N9: the backlog of PR #24 (S4b-BL-35 to S4b-BL-50). §6: owner decisions from PR #24. |
| 0.19 | 2026-09-28 | Claude (Code), lead | New session (branch `claude/sleepy-brown-479259`). §1: PR #24 merged, `main` = `3b33d37`, deployed; N8's live UI test run on it (passed; 5 failures from the session's proxy, re-checked). N5: Dependabot #25 and #26 (minor and patch) open. N7 and N9: **S4b-BL-32 done in code** (the common repository, [10](10-sprint-log.md) §13.10); next CMP-8. New **N10**: the owner's four feature requests of 2026-09-28 ([10](10-sprint-log.md) §15, S4b-FR-1..4), after CMP-8. |
| 0.20 | 2026-09-28 | Claude (Code), lead | Owner request of 2026-09-28: follow the Survey of India's page of the DST geospatial guidelines of 2021. New **N11** (done in docs: the self-certification, [03](03-design.md) §11.1; SoI's boundary as TC-M-25's reference; S4b-BL-10 re-read; new S4b-BL-51; a draft letter to SoI). N9: S4b-BL-52 (iOS photo paths, with CMP-8), from the code review of S4b-BL-32. §5: the boundary rule names SoI as the standard. §6: the owner's download of SoI's Administrative Boundary Database and the letter. |
| 0.21 | 2026-09-29 | Claude (Code), lead | N5: the web dependency pull request (Angular 22.2 by `ng update`, maplibre-gl 6.11.2 checked against the boundary rules, Dependabot's `npm-angular` group) supersedes #25; S4b-BL-53's CI check with it. §6: the Survey of India request covers the web, Android and iOS apps ([ops/soi-boundary-data-request.md](ops/soi-boundary-data-request.md) v0.2, with steps). |
| 0.22 | 2026-09-29 | Claude (Code), lead | Review of the web dependency pull request: the header and N6 name S4b-BL-1..55; N5 names §16 and S4b-BL-54 and -55; §6: the Survey of India letter was sent (2026-09-28 22:11 UTC) with the three web map screenshots ([ops/soi-boundary-data-request.md](ops/soi-boundary-data-request.md) v0.4, after v0.3: the portal read and the Gmail draft). Also in this branch since v0.21: §1 `main` = `8ad682c` (#26 merged), N10's search decision, §6's contributor-list to-do. |
| 0.23 | 2026-09-29 | Claude (Code), lead | §1: PR #28 merged (`22b43fd`), deployed, and its live UI test passed 1 977 of 1 977 (N8); the branch now carries **CMP-8a** ([10](10-sprint-log.md) §13.11). N7: CMP-8 is three pull requests (owner, 2026-09-29), 8a done in code, next 8b then 8c. N6: backlog S4b-BL-1..57. |
| 0.24 | 2026-09-29 | Claude (Code), Docs team | Owner request of 2026-09-29, the **user guide**: a new user guide ([`guide/`](../guide/docs/index.md)) v0.1 with screenshots ([10](10-sprint-log.md) §17). §1: a line for it. New **N12**. The header and N6: backlog S4b-BL-1..62 (S4b-BL-60, the guide in hi/ta/te and an in-app Help link; S4b-BL-61, the web Plan page's form shown without a server). |
| 0.25 | 2026-09-29 | Claude (Code), lead | §1: the branch now carries **CMP-8c**, the iPhone map ([10](10-sprint-log.md) §13.13). New **N13**: the owner's order after CMP-8c (TC-M-28, the release security gate, Google sign-in with a hosted server, then features S4b-FR-2..4, then S4b-BL-63). |
| 0.26 | 2026-09-29 | Claude (Code), lead | N13: the release security gate exists (automated in CI, the manual list in the new [13](13-release-security-checklist.md)); it has not yet passed on a release candidate. |
| 0.27 | 2026-09-29 | Claude (Code), lead | N4 done: the licence is `AGPL-3.0-only` with `NOTICE`; the backlog range is S4b-BL-1..65. |
| 0.28 | 2026-09-29 | Claude (Code), lead | N13: device pairing and the owner page next, then Google sign-in with Drive sync and no hosted server (D-28); the owner's principle recorded. |
| 0.29 | 2026-09-29 | Claude (Code), lead | N13 (3): the server side and the Gemini key on the owner page are merged (#43, #45); the website's *Connect* by code or link is on branch `feat/connect-by-code-or-qr`; Android and iPhone next. |
| 0.30 | 2026-09-29 | Claude (Code), lead | N13 (3): the website's *Connect* merged (#46); Android and iPhone on branch `feat/app-connect-by-code`; F-01b fixed. Next: (3b) Google sign-in with Drive sync. |
| 0.31 | 2026-09-29 | Claude (Code), lead | N13: (3) done (#43, #45, #46, #47); new (3a) AI with your own Gemini key on the device, next to server AI (D-29, [03](03-design.md) ADR-26), before (3b) Google sign-in. |
| 0.32 | 2026-09-29 | Claude (Code), lead | N13 (3a): ADR-26 merged (#48); the core (#49) and the phones (`feat/ai-own-key-phones`); the website next, then (3b) Google sign-in. |
| 0.33 | 2026-09-29 | Claude (Code), lead | N13 (3a): the phones merged (#51); the website on `feat/ai-own-key-web`; then (3b) Google sign-in. |
| 0.34 | 2026-09-29 | Claude (Code), lead | N13 (3a) done: phones (#51) and website (#52); a real-key smoke test in *AI evals* (TC-U-88); next (3b) Google sign-in. |
| 0.35 | 2026-09-29 | Claude (Code), lead | Saved the state for a fresh session (owner request): §1 rewritten as today's state (per-PR history in git and [10](10-sprint-log.md)); N13 status; §5 the owner's merge permission; new §7, how a Claude session works here. |
| 0.36 | 2026-09-29 | Claude (Code), lead | §1, §7: the live UI test reports network faults apart and runs its areas side by side. |
| 0.37 | 2026-09-29 | Claude (Code), lead | §1: #56 and #57 merged; §7: the session's learnings (tools and traps) and the efficiency list, done and open. |
| 0.38 | 2026-09-29 | Claude (Code), lead | N13: the order from the owner's decision D-30 (new features from the gap review, folded into the planned work). |

## 1. Where things stand (2026-09-29, end of the session that built ADR-25 and ADR-26)

The history of each merged pull request is in [10](10-sprint-log.md) and the [CHANGELOG](../CHANGELOG.md); this
section is only today's state. Earlier versions of this file (git history) carry the per-PR detail up to CMP-8c.

- **Live:** https://doorprints.web.app, deployed from `main` by `web.yml` (Firebase Hosting, Workload Identity
  Federation, main only). The user guide is built by `pages.yml` (GitHub Pages).
- **Done and merged, in order:** CMP-0..CMP-8 (the Compose Multiplatform track, iPhone app on the simulator with the
  map, PRs #17-#35); the **release security gate** exists (automated checks in CI, PRs #36, #37; manual list
  [13](13-release-security-checklist.md); **not yet passed** on a release candidate); **device pairing and the owner
  page** (ADR-25: per-device `dpk_` keys by code or QR/connect link, the owner page with per-device AI switches and the
  Gemini key stored encrypted; PRs #43, #45, #46, #47); the plain-language guide (#44); **AI with your own Gemini key on
  the device, next to server AI** (ADR-26, D-29: the common core in `:shared` #49 with parity vectors shared by Java,
  Kotlin and TypeScript; phones #51; website #52; live-UI test fix #50).
- **Real-key check passed** (2026-09-29, *AI evals* run 36601652513, `suites: on-device`, TC-U-88): the phones' and
  the website's own-key AI made a real Extract (2BHK, 25 000, 2 bedrooms), Ask (grounded, citing only the houses sent)
  and Plan (2 stops, 9.5 km, not the fallback) with `AI_API_KEY`, and no saved contact left the device (#53 merged).
- **Optimisation review merged** (#54: the reindex's N+1 visit query, a tombstone-id query, fewer full IndexedDB reads
  and sorts on the website; backend `mvn verify` 315 tests and web 554 tests passed locally before the push).
- **This file's pull request (#55)** is the last of the session; if it is still open, merge it when green.
- **Second efficiency pass merged** (#57): one language downloaded at a time and MapLibre's CSS with the first map
  (first download 782 -> 488 KB), house checklists batch-fetched, npm cache in CI, Gradle parallel. The live UI test's
  network-fault handling is in #56 (and this file's pull request adds dropped connections).
- **Last live UI test** (after the #57 deploy, 2026-09-29, 1 007 s): **1 996 of 1 997** passed (pages 720, i18n 240,
  theme 144, a11y 144, console 365, flows 12, pwa 4, map 23, mobile 323). The fault handling worked: `maplibre.css`
  came back as `text/plain` through the proxy, was fetched again and its page reloaded. The one failure was a request
  the proxy reset (`net::ERR_CONNECTION_RESET`, no response at all), which the test did not know yet; it now treats a
  dropped connection the same way (fetched again; counted only if it fails twice), checked with a local server that
  drops the first chunk request (reported, not counted) and one that always drops a chunk (fails).
- **Owner checks still open:** the own-key AI on a real phone and in a real browser (the code paths are proven by the
  real-key run above); TC-M-28 (the iPhone map); TC-M-25/-27 on a device;
  a release candidate through [13](13-release-security-checklist.md).
- **CI** runs on pushes to every branch and on pull requests to `main` ([07](07-secure-build-and-deploy.md) §1).
  Deploy, signing and the dependency graph are main-only. With a PR open, one push gives two runs per workflow (branch
  and merge result); accepted in [07](07-secure-build-and-deploy.md) §3. If the wait matters more than testing the
  merge result, the owner can decide to skip the `pull_request` run for same-repository branches (fork PRs keep it).

## 2. Next, in order

| Id | Item | Owner | Notes |
|---|---|---|---|
| N1 | ~~**Finish PR #14 review**: Docs and Android managers review only the delta since `3ad2b58` (text only). Apply fixes on `fix/india-boundaries`.~~ **Done:** merged on the owner's instruction, 2026-09-24 (`f8ee821`). | Docs, Android | No behaviour change. |
| N2 | **Owner device check of the boundary fix** (TC-M-25 in [06](06-test-plan.md)): live site, zoom 3-8 over Jammu and Kashmir, Ladakh, Aksai Chin and Arunachal Pradesh, compared with Google Maps as seen from India. Also install the new Android APK from the latest `Android` run on `main` and check the same. Run on the live site on 2026-09-24 (before `fix/india-boundary-lines`): passed except step (3), which was then done the same day with the owner's Google Maps link: with the India region (google.co.in) Google Maps shows the same outer boundary as ours; from outside India it shows the disputed (dashed) view. **PR #16 is merged (`4100f7a`); once it is deployed, check again and also look at:** one line (no second line or stray piece beside it) along the India-China border (for example Shipki La and the Mana Pass at zoom 10-12) and in Kalapani, Sikkim, Bhutan's south-east corner, Myanmar and the Wakhan; the hand-overs between India's outline and the tiles' line (a small step at street zoom, no spur, including on the Singalila ridge; loops at Sikkim's two tri-junctions, about 13 x 3 km at Nepal-China-India from about zoom 10 and about 2 km at Doklam; from about zoom 10 (a small hook at Jomotsangkha from zoom 9) the tile line running on past the hand-over at Jomotsangkha, about 9 km, and Longwa, about 3 km, S4b-BL-17); and the Assam-Arunachal Pradesh state line from zoom 5, dashed like the other state lines. **After PR #24** (S4b-BL-12, -17): no loop at Nepal-China-India and no overrun at Jomotsangkha or Longwa (a narrow V at Longwa), the Doklam loop of about 2 km kept on purpose; at zoom 9-14 no Pakistani or Chinese district line over Gilgit-Baltistan, PoK or Aksai Chin, and Indian district lines near the LoC and LAC still shown ([06](06-test-plan.md) TC-M-25). **The owner does the device run after CMP-8** (owner, 2026-09-24); map labels are checked there too, because the emulator does not draw them (S4b-BL-48). | Owner | Known limits: [03](03-design.md) ADR-22 Consequences; S4b-BL-11, -15, -16 fixed on `fix/india-boundary-lines` ([10](10-sprint-log.md) §12.10). |
| N3 | **App icon footprints, option C (owner's choice 2026-09-24).** The web `favicon.svg` footprints are two large gold ovals. Replace them with three small footprints (sole, heel, four toes), alternating right/left/right, walking up beside the door. Geometry in the Android 108x108 space: prints at translate (78,80), (84,68), (79,55), rotate -10, scale 0.9; sole ellipse rx 2.7 ry 3.9 at y -1.6; heel ellipse rx 2.0 ry 2.4 at y 4.6 (offset 0.3 to the outer side); toes r 0.95/0.85/0.75/0.65 near y -6.5..-7.6, big toe on the inner side. Colours unchanged (#1F6F5C tile, #FFFFFF door, #F2B84B prints). | Web, Android; Design Director may refine spacing only | Web: `web/public/favicon.svg` and every PNG in `web/public/icons` (maskable safe zone). Android: `res/drawable/ic_launcher.xml` (and `ic_stat_doorprints.xml` if it has footprints). Show renders to the owner before the PR. Separate branch `fix/brand-footprints`. **Built, PR #15** (owner's choices: feet **left/right/left**, not right/left/right, so the big toes face each other; the favicon keeps the same three prints; [12](12-brand-and-naming.md) N-06 v0.4; design review moved the top print to (78.5,51.5), spacing only). |
| N4 | ~~**Licence change MIT -> AGPL-3.0-only**~~ **Done 2026-09-29** ([10](10-sprint-log.md) §12.6): `LICENSE` from gnu.org, `NOTICE` with the section 7 permission (EPL and Google Play services libraries) and the trademark notice, README, `web/package.json`. The in-app "Source code" links are S4b-BL-65. | Docs, Web, Android | PolyForm Noncommercial only if the owner asks later. |
| N5 | **Dependabot pull requests** opened after the Sprint 4a merge (npm major for web dev tools, Gradle, Maven, Docker, GitHub Actions). Triage: merge patch/minor when CI is green; majors get a ticket. **2026-09-24:** #12 is merged (`01937f6`: Kotlin 2.4.20, AGP 9.4.1, Compose BOM 2026.09.00); #10 is a major and is left for a ticket, per this rule. **2026-09-28:** #25 (web npm, 7 minor and patch updates) and #26 (Android Gradle, 5 minor and patch updates) are open. **#26 merged (`8ad682c`)** on the owner's instruction after a run on `main` `b222d66` merged with it: Gradle 9.8.0 (wrapper jar sha256 equal to Gradle's published `238e777f…abd5`), core-ktx 1.19.1, androidx navigation 2.10.2, work 2.12.0; `assembleDebug`, `:app` 160, `:shared` 220 and `:ui` 110 tests, the 64 screenshots, the metadata and iOS simulator compiles all pass; its own CI (emulator on API 26, 34 and 36 included) was green. JetBrains navigation-compose is still 2.9.2 (its latest stable), so on Android it runs on androidx navigation 2.10.2 (compatible within the major; minSdk 24 for navigation 2.10 and work 2.12, ours is 26). **#25 fails `npm ci`** (ERESOLVE): it moves the Angular runtime packages to 22.2.0 but leaves the dev tooling (`@angular/compiler-cli` 22.1.7, which needs `@angular/compiler` exactly 22.1.7; `@angular/build` and `@angular/cli` 22.1.8), although 22.2.0 of all of them was on npm days before the run and accepts our TypeScript 6.0. Fix: a hand-made Angular 22.2 update (`ng update`) with maplibre-gl 6.11.2 in its own pull request, which supersedes #25; and a Dependabot group for `@angular/*` and `@angular-devkit/*` so they move together. **Done in the web dependency pull request of 2026-09-28** (with the `commit-identity` CI job of S4b-BL-53); Dependabot closes #25 once `main` has those versions. The maplibre-gl 6.11.2 check is [10](10-sprint-log.md) §16 (no regression; 17 map views pixel-identical to 6.10.0) and raised **S4b-BL-54** (CI does not check that MapLibre's worker files ship) and **S4b-BL-55** (main thread and worker must be the same version; a worker failure shows as offline); take maplibre-gl 6.11.3 or later through the same check (an upstream attribution sanitizer fix, #8569). The maplibre-gl bump is a base-map renderer change: check the boundary code's maplibre-gl 6.10.0 source references and run TC-M-26's map pass (and TC-M-25 on the live site) after its deploy. | DevSecOps | Branch CI runs on each. |
| N6 | **Sprint 4b**, as recorded in [10](10-sprint-log.md) §12: S4b-00 import definition, **web import first**, release security gate (S4b-SEC-1..3), screenshot tests, design-first spec step, component kit, end-to-end tests, S4b-EFF-1..6, backlog S4b-BL-1..65. | All | The guard rule stays: no Play Store release and no public server until the release security gate exists and passes. |
| N7 | **Compose Multiplatform track** ([03](03-design.md) ADR-23, [10](10-sprint-log.md) §13, CMP-0..CMP-9). CMP-1 (`be86f50`) is done and merged (PR #17); confirm its `shared-ios.yml` run is green. CMP-0 (the test harness) is merged (PR #18, `6da0e56`); its smoke tests passed on both runs on `6376706`; each later phase shows no screen changed with the screenshot tests (TC-U-56) and the emulator smoke tests (TC-I-35), or re-records the images it changes on purpose. CMP-2 is merged (PR #19, `7080a7f`; [10](10-sprint-log.md) §13.4). CMP-3 is merged (PR #20, `fccf8a1`; [10](10-sprint-log.md) §13.5). CMP-4 P4a is merged (PR #21, `e480b83`; [10](10-sprint-log.md) §13.6: the Room database in `:shared` commonMain). CMP-4 P4b is merged (PR #22, `aa8f73a`; [10](10-sprint-log.md) §13.7). CMP-4 P4c is merged (PR #23, `f8dd6f9`; [10](10-sprint-log.md) §13.8). **CMP-5, CMP-6 and CMP-7 are merged as one combined change** (PR #24, `3b33d37`; [10](10-sprint-log.md) §13.9). **S4b-BL-32, CMP-8's prerequisite, is merged** (PR #27; [10](10-sprint-log.md) §13.10). **CMP-8 is three pull requests** (owner, 2026-09-29; [10](10-sprint-log.md) §13.11): **8a done in code** (the simulator tests and data fixes, S4b-BL-24, -26, -30, -33, -52, -56, most of -40), **next 8b** (the iOS app shell: Xcode project by XcodeGen, `DoorprintsKit`, the `ios-app` CI job with a launch self-check; features iOS lacks hidden; free ad-hoc signing only if CI's Keychain needs it; S4b-BL-36, -39, the rest of -40, -57) **and 8c** (the MapLibre iOS map, S4b-BL-43, with the in-app boundary check as the CI gate and TC-M-28 on a device), **then the owner's real-device check** (TC-M-25, zoom 3-14, and TC-M-27, on the APK after CMP-8). The combined change was planned as (owner request of 2026-09-24: "After CMP4 is done, make all CMP-5, CMP-6 and CMP-7 changes at once and then test them together so that time can be saved"; [10](10-sprint-log.md) §13): one branch and one pull request, tested together (the 64 screenshots, TC-I-35, the iOS compile, the TC-M-25 re-run for the map, ADR-22), committed in steps that each build and pass (navigation, ViewModels and screens; then HouseEdit, Export and Import; then the map); code, design/UX and docs reviews, the map's India view checked on its own. Then CMP-8, separately. | Android, Docs | One pull request per phase, except CMP-5..7 (owner, 2026-09-24); each keeps `android.yml` green and runs the CLAUDE.md review steps. Signing, devices and the App Store stay out of scope (no Mac, no paid Apple account). |
| N8 | **Live web UI test after a merge to `main` that runs the `Web` deploy** (after PR #24: **done 2026-09-28**, passed; after PR #28: **done 2026-09-29**, 1 977 of 1 977 passed, the map shots looked at (TC-M-26 records what they show); [06](06-test-plan.md) TC-M-26; next: after the next merge that changes the web app. In a Claude Code cloud session, Chromium must first trust the session proxy's CA in `~/.pki/nssdb`; the owner allows that step) (owner rule, 2026-09-24, refined the same day: "Do the web app testing only if the web app changes"; §5). Only when the merge changes a path in `web.yml`'s filter (`web/**`, `.github/workflows/web.yml`, `.github/firebase-tools/**`); an Android-only or docs-only merge skips it. Once the `Web` deploy of the merge has finished: `cd tools/live-ui && npm ci && npx playwright install chromium && node live-ui.js` (exits 1 on any failure), then report the counts per area (pages, i18n, theme, a11y, flows, pwa, map, console) and look at the map screenshots in `out/shots`. A failure becomes a ticket before the next merge. First run: [06](06-test-plan.md) TC-M-26. | Lead | Needs Chromium and the npm registry in the session's container; `out/` and `node_modules/` are git-ignored. |
| N9 | **Backlog from the PR #19 reviews** ([10](10-sprint-log.md) §12.7): ~~S4b-BL-18, prices, dates and Indic typography follow `configuration.locales[0]`~~ done in CMP-3; S4b-BL-22, Export's default language still follows `locales[0]` (new in CMP-3); S4b-BL-19, `MainActivity` could be `exported="false"`; S4b-BL-20, clients detect a reset server and push and pull everything again; S4b-BL-21, the device-only checks of [06](06-test-plan.md) TC-M-27 ([Marathi, Hindi] phone; language switch on API 29 and 34; rotation on API 32 or lower; upgrade with a pinned icon). From CMP-4 P4a: ~~S4b-BL-23, the `Repository`'s Android-only Room calls~~ done in P4c; S4b-BL-24, Room never opened on iOS (with CMP-8); S4b-BL-25, no committed v1 schema. From CMP-4 P4b: S4b-BL-26, the iOS settings and Keychain store never run (with CMP-8); S4b-BL-27, the real Keystore path has no automated test (an emulator test, and an upgrade with a saved key on a phone); ~~S4b-BL-28, `SyncHealthTest` and `ExportGrantsTest` to commonTest~~ done in P4c; S4b-BL-29, `AppSettings.toString()` prints the API key (redact it); S4b-BL-30, the iOS `KeychainSecretStore` fixes (with CMP-8). From CMP-4 P4c: S4b-BL-31, the status and checklist labels in two places (with CMP-5 and P6a); ~~S4b-BL-32, the sync and import logic still in `AndroidRepository` (before CMP-8)~~ done on `claude/sleepy-brown-479259` ([10](10-sprint-log.md) §13.10); S4b-BL-52, photo rows' absolute paths go stale on iOS (with CMP-8); S4b-BL-33, `parseCoordinate` unchecked on iOS (with CMP-8); S4b-BL-34, Compare's missing screenshots and the wrapper's name (wrapper done in CMP-5; screenshots open). From PR #24 (the combined CMP-5..7 change, [10](10-sprint-log.md) §13.9): S4b-BL-31 and S4b-BL-35 done; S4b-BL-36, -37, -39, -40 and -43, the iOS side of the seams and the Map (with CMP-8); S4b-BL-38, the stay-alert link with a visit id untested; S4b-BL-41, result sentences built twice; S4b-BL-42 done; S4b-BL-44, a Map chrome screenshot set; S4b-BL-45, a reset notice on Android (owner or design decision); S4b-BL-46, the unconfined test dispatcher elsewhere; S4b-BL-47, a Vulkan APK variant at a Play release; S4b-BL-48, map labels not drawn on the emulator (device check); S4b-BL-49, the map counters on the smallest phones (legend to the top); S4b-BL-50, a CC-0 outline's accuracy (after the owner's S4b-BL-10 decision). | Android, Web, QA | S4b-BL-21 next (PR #19 is merged); the others as Sprint 4b tickets. |
| N10 | **The owner's feature requests of 2026-09-28** ([10](10-sprint-log.md) §15), after CMP-8, in this order: **S4b-FR-1** search the saved houses (**both apps already search the list** as you type: the web over label, address, street, locality, notes and contact name, Android the same without the contact name; **owner decision of 2026-09-28: search grows with the house values**, so each change that adds or changes a house field updates both apps' search with it, and the next one closes the Android contact-name gap; rule in CLAUDE.md); **S4b-FR-2** the path travelled as a coloured line on the map while visiting houses (privacy review first: a stored track is location history); **S4b-FR-3** sharing list updates between two people who know each other (zero cost, no public server); **S4b-FR-4** a house from a MagicBricks, 99acres, Housing.com, NoBroker, Square Yards or NestAway listing link (the portals' terms and the photos' copyright checked first; the web cannot read those pages from the browser). | Design Director and UX lead first, then Web and Android | Each a Sprint 4b story with the design-first step; one pull request each. |
| N11 | **The Government of India's geospatial guidelines** (owner request of 2026-09-28, with `https://onlinemaps.surveyofindia.gov.in/GeospatialGuidelines.aspx`: the DST guidelines of 15 February 2021, read in full). **Done in docs:** Doorprints' self-certification clause by clause ([03](03-design.md) §11.1, clause 8 ii(1)); the Survey of India's boundary is now the primary reference for TC-M-25 step (3) and FR-098 (clause 8 xiii: SoI maps and boundary data are the standard; Google Maps from India is the second check); [02](02-threat-model.md) RR-16 and S4b-BL-10 re-read (the earlier "display and printing only" reading missed "Others may publish such maps that adhere to these standards"); new S4b-BL-51 (re-read the self-certification and look for DST's negative list at each release); a draft letter to SoI ([ops/soi-boundary-data-request.md](ops/soi-boundary-data-request.md)). **Next:** the owner downloads SoI's free Administrative Boundary Database (§6); the lead measures the outline against it (S4b-BL-10 step (2)) and, with SoI's permission or a clear licence, rebuilds the outline from it and re-runs TC-M-25. | Owner, then lead | Docs only so far; no map change. Not legal advice. |
| N12 | **User guide** (owner request of 2026-09-29, [10](10-sprint-log.md) §17). **Done in docs:** the user guide ([`guide/`](../guide/docs/index.md)) v0.1 in English. **Next:** S4b-BL-60 (the guide in Hindi, Tamil and Telugu, *under review*, and an in-app **Help** link on the web and in Android's Settings); keep the guide (`guide/docs/`) in step whenever a label, screen or feature it names changes; when the iPhone gets the map (CMP-8c) and adding houses, update the guide's platform table (`index.md`) and its iPhone section (`screen-at-a-glance.md`). **Owner to-do:** Settings > Pages > Source: *GitHub Actions*; the site is then https://sriram-codes-sw.github.io/doorprints/ (`.github/workflows/pages.yml`). S4b-BL-61 (found while taking the screenshots): the web Plan page shows its form under the no-server note. | Docs; Web and Android for the Help link | Docs only; no code change. |
| N13 | **Order after CMP-8c** (owner decisions of 2026-09-29): (1) the owner's look at the iPhone map, TC-M-28 ([06](06-test-plan.md)); (2) the **release security gate**, S4b-SEC-1..3 ([10](10-sprint-log.md) §12.5), which a public server needs first (**exists since 2026-09-29**: the automated checks of [06](06-test-plan.md) §11.1 in CI, PRs #36 and #37 and branch `docs/release-security-checklist`, and the manual list [13](13-release-security-checklist.md); **not yet passed** on a release candidate); (3) **device pairing and the owner page for self-hosted servers** ([03](03-design.md) §12.1, ADR-25; owner, 2026-09-29: no pasting of the API key; per-device keys by code or QR; per-device AI, new devices off; the Gemini key on the owner page), then (3b) **Google sign-in with Drive sync, no hosted server** ([11](11-feature-parity-and-export-spec.md) D-28, replacing "Google sign-in with a hosted server"; owner: "a flow for Google Single Sign On for easy setup for non tech users", then "I don't want to host a server for the Google Sign in"): sign in with Google on the website, Android and iPhone, the houses synced through the person's own Google Drive (non-sensitive scopes), AI on the device with their own Gemini key (D-27), the owner hosting nothing and holding nobody's data; the self-hosted server and its owner page stay the advanced option. Owner's principle (2026-09-29): "Users should take care of how they use the app. I am only creating the means for them to easily utilize the services." **Status 2026-09-29:** (1) waits on the owner; (2) exists, not yet passed on a release candidate; (3) and (3a) done (ADR-25, ADR-26, PRs #43-#53); **next (3b)**. **Order from here (owner decision D-30, 2026-09-29; [11](11-feature-parity-and-export-spec.md) 5.19..5.26, [10](10-sprint-log.md) §15 S4b-FR-5..12):** (3b) Google sign-in with Drive sync **and the app lock** (S4b-FR-5); (4a) the map work: the path trace (S4b-FR-2) **and offline maps** (S4b-FR-6), one TC-M-25 re-check; (4b) sharing list updates (S4b-FR-3), then a house from a listing link (S4b-FR-4) **with brokers** (S4b-FR-11); (4c) the Sprint 4b set of [11](11-feature-parity-and-export-spec.md) 14.2, enlarged: weighted criteria and rooms **with the real cost of a house** (S4b-FR-7), viewing questions and photo tags, viewings **with moving in** (S4b-FR-10), hunting areas **with my places and area notes** (S4b-FR-8, -9), in one data-model and format change; (5) S4b-BL-63. Voice notes (S4b-FR-12) are parked. (4) then the features S4b-FR-2..4 of N10; (5) S4b-BL-63, the end-to-end test of the guide's server setup with the owner, after the last sprint. | Lead, all | The guard rule stays: no public server until the gate passes. The deep self-run pentest comes before sign-in ships ([10](10-sprint-log.md) §12.5). |

## 3. From the first deploy's ZAP baseline (2026-09-23; 0 fail, 7 warn)

| Id | Item | Decision |
|---|---|---|
| Z1 | CSP `connect-src https:` wildcard | Accepted: the user's own server address is unknown at build time. Record in [07](07-secure-build-and-deploy.md). |
| Z2 | No SRI on the Google Fonts stylesheet | **Sprint 4b:** self-host the Noto Sans Devanagari, Tamil and Telugu subsets; drop fonts.googleapis.com and fonts.gstatic.com from the CSP (privacy and zero cost). |
| Z3 | COEP header missing | Accepted: no cross-origin isolation needed; `require-corp` would break tiles and fonts. |
| Z4 | Cache-control warnings (`no-cache` on everything) | Optional Sprint 4b: `immutable, max-age=31536000` for hashed JS/CSS. |
| Z5 | Suspicious comments in shipped JS | Check whether ours or a library's. Low. |

## 4. Parked or owner-decision items

| Id | Item | Status |
|---|---|---|
| P1 | **Map clustering** of all saved houses (MapLibre GeoJSON `cluster`, supercluster/KD-tree) | Parked by the owner until testing of the boundary release is complete; then a Sprint 4b story, Design Director and UX lead design first. Both apps already draw every saved house from one GeoJSON source. |
| P2 | **Spatial index on the clients** (quad tree / R-tree) | Not needed at personal scale (tens to hundreds of houses); revisit above about 10,000 points. The server already has PostGIS GiST indexes. |
| P3 | **iPhone app** | **Decided in [03](03-design.md) ADR-23 (2026-09-24):** the Android UI moves to Compose Multiplatform (`android/ui`) in phases, and phase P8 (CMP-8) builds an ad-hoc signed iOS shell for the simulator on GitHub's macOS runners ([10](10-sprint-log.md) §13; N7). No native app for users today; the web app on the Home Screen is the iPhone path. Signing, device installs, TestFlight and the App Store need a Mac and the Apple Developer Program (about US$99/year), which conflicts with the zero-cost rule: still the owner's call. |
| P4 | **Android build JDK 25** (backend already uses 25; Android builds on JDK 21 and targets Java 17 bytecode for ART) | Optional; small gain; do after the first release, not before. |
| P5 | Point 3 of the process improvements (smaller batches) | On hold (owner). |
| P6 | Custom domain | Only after the web can import a backup (browser storage is per origin). |

## 5. Owner rules still in force

- Zero cost (Spark plan, free runners, public-domain or free data).
- India's boundaries are always shown as the Government of India depicts them ([03](03-design.md) ADR-22); the Survey of India's maps and boundary data are the standard (DST geospatial guidelines of 2021, clause 8 xiii; self-certification in [03](03-design.md) §11.1). A change to the base map style or tiles must re-run TC-M-25.
- Brand vocabulary: *Import a backup* (in), *Save a copy* (out), *readable copies*, *Add a shared listing*; never "Restore" as a button label ([12](12-brand-and-naming.md)).
- Hindi, Tamil and Telugu ship marked *under review* until a native speaker checks them.
- "Save the state" requests from the owner are top priority.
- Every change is reviewed before it reaches `main`. **Since 2026-09-29 the owner lets a Claude session merge its own pull request once it is good** (all checks green, no conflict, no open review thread; §7).
- **After a merge to `main` that runs the `Web` deploy, the live web UI is tested in detail** with `tools/live-ui`
  once the deploy has finished; an Android-only or docs-only merge skips it (owner, 2026-09-24, refined the same day;
  N8, [06](06-test-plan.md) TC-M-26).

## 6. Owner to-dos

- **Survey of India boundary data** (N11, [10](10-sprint-log.md) S4b-BL-10; steps in
  [ops/soi-boundary-data-request.md](ops/soi-boundary-data-request.md) v0.4). **No account is needed.** (1) Download the
  free Administrative Boundary Database **OVSF/1M/7** (whole country, district level) from the portal's *Quick Access*;
  the download asks for a CAPTCHA and a tick-box, which only you can do; keep the ZIP out of the repository and attach
  it in a session to have the outline measured. (2) **The letter was sent** on 2026-09-28 (22:11 UTC) to `mtr.soi@gov.in` (The Director, NGDR & UGI
  Directorate) with the three web map screenshots; tell the next session when the reply comes (S4b-BL-10).
- **GitHub contributor list** ([10](10-sprint-log.md) S4b-BL-53): `noreply` is listed because of 17 commits of
  2026-09-23 made as `noreply@users.noreply.github.com`. Decide whether it is worth a history rewrite (costly, see the
  ticket); optionally add and verify `owner-email-removed` in GitHub's email settings so the 14
  anonymous commits of 2026-09-22 count as yours.
- Storage audit on the live site (`web/README.md`, *Storage audit on the live site*).
- Firebase Hosting: set releases to keep = 10.
- Firebase Test Lab: the setup in [07](07-secure-build-and-deploy.md) §7.2 ([ops/firebase-test-lab-setup.md](ops/firebase-test-lab-setup.md)): two APIs, the service account `ftl-runner`, a Workload Identity pool and provider of its own (`github-test-lab`, a new pool; condition `android-emulator.yml` on `main`; the Hosting provider stays as it is), a results bucket (`doorprints-test-lab-results`, 30-day delete rule, Storage Object Admin for `ftl-runner` on it only) named in the variable `FTL_RESULTS_BUCKET`, and the secrets `FTL_WIF_PROVIDER` and `FTL_SA_EMAIL`, when you want the smoke tests on a Test Lab device too; until then that job is skipped. **Decision first:** the bucket needs a billing account on `doorprints` (Spark projects no longer get Cloud Storage; Always Free needs billing and is US-only), which the zero-cost rule excludes: leave Test Lab off, link billing with a ₹0 budget alert, or go back to Test Lab's default bucket (a workflow change, and Editor for `ftl-runner`).
- Remove the old `house-hunt` folder from the old Cowork task (no longer used).
- **After PR #19 merges, on a self-hosted server started with `docker compose`:** follow [08](08-operations-runbook.md)
  §11 before starting the new version. The dump and restore of the old `househunt` database is **mandatory** for a
  database a phone or the web app has synced with (on a new, empty database devices silently miss each other's
  changes). Then remove the old volume `<project>_dbdata18` once the counts match.
- **From PR #24** (after it merges): (a) the real-device check after CMP-8, TC-M-25 at zoom 3-14 with the map labels
  (S4b-BL-48) and TC-M-27; (b) S4b-BL-10: keep Natural Earth, write to the Survey of India for written permission to
  redistribute the outline, or have a CC-0 source checked (S4b-BL-50); (c) Doklam's 2 km loop is kept on purpose,
  say if anything else is wanted; (d) S4b-BL-45: whether Android should notify a server reset once; (e) S4b-BL-47:
  a Vulkan APK variant only at a Play release; (f) a self-hosted server: clients now detect a reset database and
  re-send everything, but the §11 dump and restore stays mandatory ([08](08-operations-runbook.md) §11.1).
- **MCP clients:** the tool `askHouseHunt` is now `askDoorprints`; update any saved permission, allow-list or prompt
  that names the old tool.

## 7. How a Claude session works here (owner's standing instructions, 2026-09-29)

A new session reads CLAUDE.md, then this file, and continues from §2 without asking again for what is below.

**Standing permissions (owner, 2026-09-29)**

- **Merge your own pull request when it is good:** CI green on the head (every check, including the macOS iOS jobs and
  the three emulators), no conflict, no open review thread; squash merge. ("Yes go ahead and merge if PR is good.")
- **Git commands with a valid cause are allowed**, including merging `main` into a working branch. If the session's
  safety check blocks one, do not work around it: ask the owner in one line.
- **Go with your best judgement** on design choices that do not compromise function or design; say what you chose and
  why, and treat it as the default unless the owner objects.
- The owner may say "save the state": that is top priority (§5).

**Order of work** is §2 N13 (then N10, then S4b-BL-63). Pick up open pull requests first.

**Test locally, not in CI (owner, 2026-09-29: "Get Docker here. Don't use CI if there is another way.")** Every
suite runs in a cloud session; CI is the second check, never the first:

- Docker: the daemon is installed but not started. `rm -f /var/run/docker.pid /var/run/docker.sock; nohup dockerd
  >/tmp/dockerd.log 2>&1 &`, then wait for `docker info`. Builds need `docker build --network host` (the session
  proxy; `/root/.ccr/README.md`).
- JDK 25 for the backend: `curl -sSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"`
  into the scratchpad and run Maven with `JAVA_HOME` pointing at it (the container has JDK 21 only).
- Backend: `docker build --network host -t doorprints-db:ci backend/db`, start it as `backend.yml` does (port 5432,
  user, password and database `doorprints`), then `DB_URL=jdbc:postgresql://127.0.0.1:5432/doorprints DB_USER=doorprints
  DB_PASSWORD=doorprints mvn -B -ntp verify` (about 5 minutes, 315 tests on 2026-09-29).
- The disk allowance is small: delete scratch downloads you no longer need before pulling images.

**Per change**

1. A branch `<type>/<topic>` from the latest `main` (CLAUDE.md); never a session's generated name; one PR each.
2. Code, tests, and the docs in the same change: the SSDLC doc sections touched, their version and change-log rows
   (01-14, `docs/ai/ai-design.md`), the CHANGELOG *Unreleased* entry, and the user guide (`guide/docs`) when a label,
   screen or feature it names changes. UI text in en, hi, ta and te (hi/ta/te marked *under review*).
3. Self-check (CLAUDE.md "How changes are reviewed"): accessibility, four languages, both themes, loading/empty/error.
   For UI: screenshots checked by eye (Android `recordRoborazziDebug`; web: Playwright against the built site, in all
   four languages and both themes).
4. Local checks before every push: web `npx ng test --watch=false && npx ng build`; Android
   `./gradlew assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest
   :shared:compileCommonMainKotlinMetadata :ui:compileCommonMainKotlinMetadata -Proborazzi.test.verify=true` (add
   `-Pkotlin.incremental=false` after switching branches); backend `mvn -B -ntp verify` (needs PostGIS, see
   `backend.yml`); `python3 .github/scripts/licence-headers.py --fix` (new files: `git add -N` first); the guide with
   `mkdocs build --strict` **and read its output for WARNING lines** (an anchor warning once passed locally and failed
   CI).
5. Commit as `Claude <noreply@anthropic.com>` with the trailers `Co-Authored-By: Claude <noreply@anthropic.com>` and
   the session link; push; open the PR with What / Tests / Docs sections in plain words; subscribe to its activity and
   set one check-in about 50 minutes out.
6. On CI red: find the cause in the job log, reproduce it locally, fix, push. Never skip a test.
7. When green: merge (above). After a merge that runs the `Web` deploy, wait for the deploy, then run
   `tools/live-ui` (`npm ci && node live-ui.js`; Chromium at `/opt/pw-browsers/chromium` in cloud sessions) and report
   the counts per area, and any `transient` entries in `out/results.json` (network faults the test fetched again and did
   not count; many of them are worth a ticket). Give the run 20 minutes before any time limit.
8. Report to the owner in short, plain language: what changed, what was checked, what is next, and anything only the
   owner can do.

**Things that are easy to get wrong**

- Personal data: never write the owner's name, address or phone beyond "Sriram (Sriram-Codes-SW)".
- A real Gemini key is never needed in a session: the `AI_API_KEY` secret is used only by *AI evals*; sessions test
  against fakes, or with a made-up key to check that Google is reached.
- Keep the three AI implementations in step: a change to a prompt, redaction or check goes to the Java server, the
  Kotlin core (`android/shared/.../shared/ai`) and the TypeScript core (`web/src/app/core/ai`) together, and the parity
  vectors are refilled (`ParityVectorsTest -Dparity.write=true`, then `.github/scripts/parity-vectors-kotlin.py`).
- Search grows with the house values (CLAUDE.md).
- Zero cost; no Play release and no public server until the release security gate passes on a release candidate.

**Learnings of 2026-09-29 (tools and traps)**

- **Web tests both ways:** `npx ng test --watch=false` isolates spec files; `CI=true npm run test:ci` (what CI runs)
  shares modules between them. A spec that depends on module state (the loaded languages, for one) can pass the first
  and fail the second; run both before pushing a web change.
- **`npm run build`, not `npx ng build`,** when the service worker matters: only the `postbuild` step
  (`scripts/sw-precache.mjs`) writes the precache list into `sw.js`. Check a new lazy chunk or asset is in it.
- **Guide:** `mkdocs build --strict` output must be read for `WARNING` lines (an anchor warning once passed locally and
  failed CI); renaming a heading breaks `#anchor` links in other pages.
- **Live UI test (`tools/live-ui`):** about 15 minutes with the areas in parallel; give it 20-30 before any time limit.
  Network faults (a 502 from this session's proxy, often with a `text/plain` body: that was the "stylesheet as
  text/plain" of 2026-09-28/-29) are fetched again, reported under `transient` in `out/results.json` and not counted; a
  page one left half loaded is loaded again. A fault seen twice is a real failure.
- **Testing the live UI test itself:** serve the built site locally like Firebase (SPA rewrite; `.mjs` must be
  `text/javascript`, or MapLibre's worker is refused) and inject faults; a plain local server fails the "house opens
  offline" checks whatever the code (the live site passes them). Never rebuild `web/dist` while such a run is using
  it: the chunk names change under it.
- **Processes:** `pkill -f <pattern>` also matches the shell running it and kills that; use
  `for p in $(pgrep -f "^node .*name"); do kill $p; done`. `ss` is not installed.
- **The session's command safety check** sometimes returns no verdict for a while: use the file tools (Read, Edit,
  Write, Grep) meanwhile and retry the shell later; do not hammer it (ten misses in a row end the turn). If it
  *denies* something with a reason, do not work around it: ask the owner in one line.
- **Disk:** the per-session allowance is small; the Docker image, JDK and Gradle caches need about 3 GB, so delete
  scratch downloads first.

**Efficiency: done and still open (2026-09-29, #54 and #57)**

- Done: the reindex's N+1 visit query and tombstone ids (#54); house checklists batch-fetched (`default_batch_fetch_size`);
  fewer full IndexedDB reads and sorts; one language downloaded at a time and MapLibre's CSS with the first map (first
  download 782 -> 488 KB); npm cache in CI; Gradle parallel.
- Open, each worth its own change: S4b-BL-66 (photo reads through a `houseId` index); long `Cache-Control` for hashed
  files once the `**` rewrite stops answering missing chunks with the HTML shell (`web/README.md`); self-hosted Noto
  font subsets (§3 Z2, also a privacy gain); a `COLLATE NOCASE` street index on Android (needs a Room migration); the
  Gradle configuration cache (check KMP and Roborazzi first); skipping the `pull_request` CI run for same-repository
  branches (the owner's call, §1).
