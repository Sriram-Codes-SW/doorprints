# Session state, 2026-10-19 (hand-off note, not a versioned doc)

Written by the lead Claude session so a fresh session can continue. Start with CLAUDE.md and docs/14 §7, then this.

## Merged this session (main)
#383 exact 30-day wording; #384 docs fixes + zip-bomb review (no gap); #385 release checklist run (docs/ops/release-run-2026-10-19.md);
#386 first release is v0.1.1 (versionName, MARKETING_VERSION), web/README storage table (B5), docs/13 A5/A6.

## Open pull requests
- #375 Survey of India boundary lines: DRAFT. Keep open and unmerged until SoI answers and TC-M-25 is looked at on a device.
- #387 `docs/privacy-children-data` (DRAFT): privacy.html Children section in 4 languages, docs/02 T-I54/RR-35 (Proposed, NOT accepted), docs/04, docs/13 G5. Builder was merging main in (docs versions: README 0.132, docs/13 v0.25). Merge only with/after the school release; owner must approve wording; natives review hi/ta/te.
- School release web draft PR, branch `feat/school-release-web` (builder ad6893bd3d060d1b6): RELEASED_KINDS house+school, tagline A "Remember every place you've seen." (hi/ta/te drafts in the plan), public words, test inversions (seo.spec, release-kinds.spec, experimental-off.spec, kinds-10-web-settings mutation, i18n-title strategy, kind-word-vectors), ADR-13b, docs/12 §H, docs/13 J1/J8, close S4b-BL-282. Phones unchanged. HELD until owner gates pass. Never merge before the gates.
- Voice free-tier refusal PR, branch `feat/voice-refuse-free-tier` (builder ad3ab6393f4a35c26): refuse free-tier provider keys (verdict ALLOW|REFUSE_FREE|REFUSE_UNKNOWN|CHECKING, vectors voice-tier-vectors.json). Must also record RR-33 acceptance (owner said "I accept this") conditional on that PR. Owner decision pending: keep/delete the unknown-provider acknowledgement switch.

## School release plan (Opus, 2026-10-19), summary
Schools are NOT live: built in, off behind "Experimental: more than houses". Release after named gates, website first, phones later.
Owner-only blockers: review school drafts (8-item checklist, Compare criteria, 6 questions, headings, viewings words; check "Taken" label); approve privacy wording (#387); native read of ~25 hi/ta/te strings; docs/13 on the web RC (A, B, F, H with H2 in school space, J1-J7); website manual tests MT-93, 94, 100, 102, 117-121.
Phones flip (later): KindSettingRules.RELEASED, preview marks, split `app_tagline` (HouseListScreen.kt:507/519 uses it as the empty-list title: add a house-only key first), iOS InfoPlist/Info.plist "houses you save" -> "places you save", re-record Roborazzi, device checks MT-96, 97, 101, 103, 112, 113, 115, 116, 118-121, TC-M-25 in school space.
Do not change live public words before the release; flip the seo.spec tripwire deliberately.

## Release checklist run (docs/ops/release-run-2026-10-19.md)
19 pass, 1 fail (B5, fixed in #386), 13 not run, 34 owner, 9 n/a. No High/Critical. Owner: enable Dependabot alerts (said done; API returns 403 to the session token, so unverifiable here), cut v0.1.1 and delete the old draft v0.1.0 release/tag, ZAP/MobSF triage, console rows, ai-evals dispatch, device checks.

## Later (quota permitting)
Root-cause `ScreensScreenshotTest areas[hi-dark=false]` flake; loosen docs-changelog.spec to ascending/unique; docs/15 §5.1 "7 months" string mismatch; country-pack WP-C1/C2; kinds leftovers (zz_all on phones, numeric extras filters, server vectors test); user-authored kinds by JSON import. Live UI test for #383 privacy.html after deploy: `cd tools/live-ui && ONLY=pages,flows node live-ui.js` (not yet run).

## Rules to remember
Never record owner acceptances/sign-offs for them. No new backlog rows. Merge own green PRs only; never auto-merge stacked ones. CI facts: strict up-to-date rule; gitleaks scans branch history; docs-changelog.spec pins docs/15 versions (now through 0.40).
