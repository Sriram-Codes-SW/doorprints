# 14. Lead backlog and handoff

| Field | Value |
|---|---|
| Version | 0.3 |
| Date | 2026-09-24 |
| Owner | Sriram (product owner); lead: Claude |
| Purpose | Everything pending at the end of the Cowork sessions of 2026-09-22..24, in one place, so a new Claude Code session (web or CLI) can continue without the old session's notes. Team-level tickets stay in [10](10-sprint-log.md) §12.7 (S4b-BL-1..16); this file lists the lead-level items and points to the rest. |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-24 | Claude (Cowork), lead | First version: state of `main`, open pull requests, the ordered next steps, parked items and owner decisions still in force. |
| 0.2 | 2026-09-24 | Claude (Code), lead | N1 done: PR #14 merged by the owner's instruction (`f8ee821`). N3 built on `fix/brand-footprints`, PR #15: the owner chose **left/right/left** over the right/left/right first written in N3 (so the big toes face each other) and kept the same three prints in the favicon; renders shown to the owner before the PR. §1 updated to `main` = `f8ee821` and open PR #15. The top print moved from (79,55) to (78.5,51.5) in the design review (spacing only, as N3 allows), so that its heel no longer touches the middle print. |
| 0.3 | 2026-09-24 | Claude (Code), Docs team | The doubled lines and the Assam-Arunachal Pradesh state line are fixed on branch `fix/india-boundary-lines` (commit `5af2f4d`, PR to follow; [10](10-sprint-log.md) §12.10, S4b-BL-11, -15, -16): new line in §1; N2's owner check now also looks at the hand-overs and the state line. |

## 1. Where things stand (2026-09-24, ~03:30 IST)

- **Live:** https://doorprints.web.app, deployed from `main` by `web.yml` (Firebase Hosting, Workload Identity Federation, main only).
- **`main` = `f8ee821`**: Sprint 4a (PR #11), the India boundary fix (PR #13, [03](03-design.md) ADR-22) and its docs and comment sync with this file (PR #14, merged on the owner's instruction on 2026-09-24).
- **PR #15** (branch `fix/brand-footprints`): N3, the icon's footprints (story S4b-BR-1, [10](10-sprint-log.md) §12.9), merged on the owner's instruction after all reviews approved (`76449fb`) and deployed.
- **Branch `fix/india-boundary-lines`** (commit `5af2f4d`, PR to follow, not yet deployed): from zoom 5 one line along the stretches where the tiles draw India's border themselves (no more two close lines in Himachal Pradesh, Uttarakhand, Sikkim, the Wakhan and elsewhere), and the Assam-Arunachal Pradesh state line from zoom 5 ([03](03-design.md) ADR-22, [10](10-sprint-log.md) §12.10; S4b-BL-11, -15, -16 done there). Not yet seen green in CI; Android not yet checked on a device.
- **CI** runs on pushes to every branch and on pull requests to `main` ([07](07-secure-build-and-deploy.md) §1). Deploy, signing and the dependency graph are main-only.
- **Merging is the owner's click.** An automated session must not merge its own pull request.

## 2. Next, in order

| Id | Item | Owner | Notes |
|---|---|---|---|
| N1 | ~~**Finish PR #14 review**: Docs and Android managers review only the delta since `3ad2b58` (text only). Apply fixes on `fix/india-boundaries`.~~ **Done:** merged on the owner's instruction, 2026-09-24 (`f8ee821`). | Docs, Android | No behaviour change. |
| N2 | **Owner device check of the boundary fix** (TC-M-25 in [06](06-test-plan.md)): live site, zoom 3-8 over Jammu and Kashmir, Ladakh, Aksai Chin and Arunachal Pradesh, compared with Google Maps as seen from India. Also install the new Android APK from the latest `Android` run on `main` and check the same. Run on the live site on 2026-09-24 (before `fix/india-boundary-lines`): passed except step (3), the Google Maps comparison; the owner has since supplied a Google Maps India screenshot for reference. **After `fix/india-boundary-lines` is merged and deployed, check again and also look at:** one line (no second line beside it) in Himachal Pradesh, Uttarakhand, Kalapani, Sikkim, Bhutan's south-east corner, Myanmar and the Wakhan; the hand-overs between India's outline and the tiles' line (a small step of at most 7 km at street zoom, a 2 km loop in Sikkim); and the Assam-Arunachal Pradesh state line from zoom 5, dashed like the other state lines. | Owner | Known limits: [03](03-design.md) ADR-22 Consequences; S4b-BL-11, -15, -16 fixed on `fix/india-boundary-lines` ([10](10-sprint-log.md) §12.10). |
| N3 | **App icon footprints, option C (owner's choice 2026-09-24).** The web `favicon.svg` footprints are two large gold ovals. Replace them with three small footprints (sole, heel, four toes), alternating right/left/right, walking up beside the door. Geometry in the Android 108x108 space: prints at translate (78,80), (84,68), (79,55), rotate -10, scale 0.9; sole ellipse rx 2.7 ry 3.9 at y -1.6; heel ellipse rx 2.0 ry 2.4 at y 4.6 (offset 0.3 to the outer side); toes r 0.95/0.85/0.75/0.65 near y -6.5..-7.6, big toe on the inner side. Colours unchanged (#1F6F5C tile, #FFFFFF door, #F2B84B prints). | Web, Android; Design Director may refine spacing only | Web: `web/public/favicon.svg` and every PNG in `web/public/icons` (maskable safe zone). Android: `res/drawable/ic_launcher.xml` (and `ic_stat_doorprints.xml` if it has footprints). Show renders to the owner before the PR. Separate branch `fix/brand-footprints`. **Built, PR #15** (owner's choices: feet **left/right/left**, not right/left/right, so the big toes face each other; the favicon keeps the same three prints; [12](12-brand-and-naming.md) N-06 v0.4; design review moved the top print to (78.5,51.5), spacing only). |
| N4 | **Licence change MIT -> AGPL-3.0-only** with a README trademark notice ([10](10-sprint-log.md) §12.6, approved). Fetch the licence text, do not retype it; in-app "Source code" links; `package.json` licence field. | Docs, Web, Android | PolyForm Noncommercial only if the owner asks later. |
| N5 | **Dependabot pull requests** opened after the Sprint 4a merge (npm major for web dev tools, Gradle, Maven, Docker, GitHub Actions). Triage: merge patch/minor when CI is green; majors get a ticket. | DevSecOps | Branch CI runs on each. |
| N6 | **Sprint 4b**, as recorded in [10](10-sprint-log.md) §12: S4b-00 import definition, **web import first**, release security gate (S4b-SEC-1..3), screenshot tests, design-first spec step, component kit, end-to-end tests, S4b-EFF-1..6, backlog S4b-BL-1..16. | All | The guard rule stays: no Play Store release and no public server until the release security gate exists and passes. |

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
| P3 | **iPhone app** | No native app today; the web app on the Home Screen is the iPhone path. A native app would use the KMP `android/shared` module plus Compose Multiplatform or SwiftUI screens, built on GitHub macOS runners; distribution needs the Apple Developer Program (about US$99/year), which conflicts with the zero-cost rule. Owner's call. |
| P4 | **Android build JDK 25** (backend already uses 25; Android builds on JDK 21 and targets Java 17 bytecode for ART) | Optional; small gain; do after the first release, not before. |
| P5 | Point 3 of the process improvements (smaller batches) | On hold (owner). |
| P6 | Custom domain | Only after the web can import a backup (browser storage is per origin). |

## 5. Owner rules still in force

- Zero cost (Spark plan, free runners, public-domain or free data).
- India's boundaries are always shown as the Government of India depicts them ([03](03-design.md) ADR-22). A change to the base map style or tiles must re-run TC-M-25.
- Brand vocabulary: *Import a backup* (in), *Save a copy* (out), *readable copies*, *Add a shared listing*; never "Restore" as a button label ([12](12-brand-and-naming.md)).
- Hindi, Tamil and Telugu ship marked *under review* until a native speaker checks them.
- "Save the state" requests from the owner are top priority.
- Every change is reviewed before it reaches `main`; the owner merges.

## 6. Owner to-dos

- Storage audit on the live site (`web/README.md`, *Storage audit on the live site*).
- Firebase Hosting: set releases to keep = 10.
- Remove the old `house-hunt` folder from the old Cowork task (no longer used).
