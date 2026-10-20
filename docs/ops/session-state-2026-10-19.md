# Session state, updated 2026-10-20 (hand-off note, not a versioned doc)

Written by the lead Claude session so a fresh session can continue. Start with CLAUDE.md and docs/14 §7, then this.

## Merged since the last note (main)
#383 exact 30-day wording; #384 docs fixes + zip-bomb review; #385 release checklist run (docs/ops/release-run-2026-10-19.md); #386 first release is v0.1.1 + web/README storage table;
#388 voice refuses free-plan keys (RR-33 recorded as ACCEPTED by owner, conditional on the plan rule staying in the release; owner said so in chat 2026-10-19/20);
#389 SCHOOL KIND RELEASED ON THE WEB (RELEASED_KINDS house+school, tagline A "Remember every place you've seen.", public words, ADR-13b; phones untouched then);
#387 privacy.html children's-data wording (4 languages; T-I55/RR-35 Proposed, not accepted); #390 hi/ta/te privacy bullets say houses and schools.
Owner standing instruction (2026-10-20): go ahead with changes that are logically sound, merge green PRs, do not wait for approval; owner will ask for rewrites.
Classifier note: a message to a builder to merge #387 and a first RR-33 edit were blocked until the owner confirmed in chat; if blocked, ask the owner in chat once.

## Open / in flight
- #375 Survey of India draft: keep OPEN DRAFT, unmerged until SoI answers + TC-M-25 device look.
- Builder PRs started 2026-10-20: `chore/mutations-changelog-spec-docs15` (kinds-10 mutation drift, loosen docs-changelog.spec, docs/15 7-months string vs the 30-day wording) and `feat/school-release-phones` (phones RELEASED school, app_tagline split with a new house-only empty-list key, iOS permission strings, preview marks, screenshots). Merge when green, strict up-to-date rule applies.

## Owner-only
Cut v0.1.1 (tag must equal versionName 0.1.1) and delete the old draft v0.1.0 release/tag; review school content (8-item checklist, Compare criteria, 6 questions, headings, viewings words); native read of hi/ta/te (tagline, school, privacy); docs/13 owner rows (A2-A6 triage, enable Dependabot alerts: said done, session token gets 403 so unverifiable, C1-C7, D, F10, G2, H1, I rows, J4/J5/J8); device checks (school MT-96,97,101,103,112,113,115,116,118-121, TC-M-25 in school space; voice MT-104..111,114; Drive TC-M-90/91, MT-122/123; iPhone per ios/README.md); optional SARVAM_EVAL_API_KEY; ai-evals dispatch.

## Later
Root-cause `ScreensScreenshotTest areas[hi-dark=false]` flake and the `/share` 384x615 ta layout-gate flake; country-pack WP-C1/C2; kinds leftovers (zz_all on phones, numeric extras filters, server vectors test); user-authored kinds by JSON import; pentest (docs/13 §5).

## Rules to remember
Never record owner acceptances for them unless they say so in chat. No new backlog rows. Merge own green PRs only; never auto-merge stacked ones. Backend keeps its own copy of kind files (backend/src/main/resources/kinds/*.json) that must stay byte-identical to docs/schemas/kinds. Run live UI from a current checkout of main (`cd tools/live-ui && ONLY=pages,flows node live-ui.js`).
