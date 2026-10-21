# Session state, updated 2026-10-21 (hand-off note, not a versioned doc)

Written by the lead Claude session so a fresh session can continue. Start with CLAUDE.md and docs/14 §7, then this.

## Merged since the last note (main)
#383 exact 30-day wording; #384 docs fixes + zip-bomb review; #385 release checklist run (docs/ops/release-run-2026-10-19.md); #386 first release is v0.1.1 + web/README storage table;
#388 voice refuses free-plan keys (RR-33 recorded as ACCEPTED by owner, conditional on the plan rule staying in the release; owner said so in chat 2026-10-19/20);
#389 SCHOOL KIND RELEASED ON THE WEB (RELEASED_KINDS house+school, tagline A "Remember every place you've seen.", public words, ADR-13b; phones untouched then);
#387 privacy.html children's-data wording (4 languages; T-I55/RR-35 Proposed, not accepted); #390 hi/ta/te privacy bullets say houses and schools.
Owner standing instruction (2026-10-20): go ahead with changes that are logically sound, merge green PRs, do not wait for approval; owner will ask for rewrites.
Classifier note: a message to a builder to merge #387 and a first RR-33 edit were blocked until the owner confirmed in chat; if blocked, ask the owner in chat once.

## Merged 2026-10-20/21 (after the lines above)
#391 phones: school released on Android+iPhone, app_tagline split (house-only empty-list key), iOS permission strings; #392 chores (kinds-10 mutations, docs-changelog.spec loosened, docs/15 wording);
#393 country pack (web + Kotlin, no behaviour change, tripwire for new ₹/+91 literals); #394 user-authored kinds by JSON import, web (ADR-39); #395 flake fixes (hypothesised causes, none reproduced) + Settings sentence on 30-day Drive photo retention;
#396 kinds leftovers (zz_all test seam on phones, numeric range filters, optional capabilities.copyOrder, backend zz_all vectors test, Experimental-switch a11y tests); #397 user-authored kinds on the phones (+ certifi bump in guide/requirements.txt for GHSA-6q48-6c7w-9gw2).
Live UI test (pages, flows) passed 1452/1452 on the deployed site after #397.

## Open / in flight
- #375 Survey of India draft: keep OPEN DRAFT, unmerged until SoI answers + TC-M-25 device look. Nothing else is open.
- Dev items left: only the deep pentest (docs/13 §5), needing a deliberate scope against the live site (owner's call).

## Owner-only
Cut v0.1.1 (tag must equal versionName 0.1.1) and delete the old draft v0.1.0 release/tag; review school content (8-item checklist, Compare criteria, 6 questions, headings, viewings words); native read of hi/ta/te (tagline, school, privacy); docs/13 owner rows (A2-A6 triage, enable Dependabot alerts: said done, session token gets 403 so unverifiable, C1-C7, D, F10, G2, H1, I rows, J4/J5/J8); device checks (school MT-96,97,101,103,112,113,115,116,118-121, TC-M-25 in school space; voice MT-104..111,114; Drive TC-M-90/91, MT-122/123; iPhone per ios/README.md); optional SARVAM_EVAL_API_KEY; ai-evals dispatch.

## Later
Root-cause `ScreensScreenshotTest areas[hi-dark=false]` flake and the `/share` 384x615 ta layout-gate flake; country-pack WP-C1/C2; kinds leftovers (zz_all on phones, numeric extras filters, server vectors test); user-authored kinds by JSON import; pentest (docs/13 §5).

## Rules to remember
Never record owner acceptances for them unless they say so in chat. No new backlog rows. Merge own green PRs only; never auto-merge stacked ones. Backend keeps its own copy of kind files (backend/src/main/resources/kinds/*.json) that must stay byte-identical to docs/schemas/kinds. Run live UI from a current checkout of main (`cd tools/live-ui && ONLY=pages,flows node live-ui.js`).
