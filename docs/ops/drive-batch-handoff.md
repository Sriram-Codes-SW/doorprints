# Google Drive batch: state and handoff (2026-10-02)

Written because the owner asked to save state (usage limit). Nothing here is merged. Owner decision for this batch: build and
test with unit tests first; local CI, GitHub CI, pull requests and doc updates come later in one pass, fixing what they find.
Never merge a pull request before its checks are green; after each squash merge read `main`'s push CI before the next merge.

## Branches (all pushed, none merged; PR #116 is the only pull request, a draft). Updated 2026-10-02, weekly limit reached.

| Branch | Base | State |
|---|---|---|
| `feat/drive-crypto-core` (PR #116, draft) | `main` | S4b-BL-125 built; two adversarial reviews fixed (head `e81c6e1`). Last commit not re-reviewed; full Android/iOS checks not run on it. |
| `feat/privacy-page` | `main` | S4b-BL-121 built, 46 SEO tests pass. No PR. |
| `feat/drive-sync-schema` | `main` | S4b-BL-130 built (Kotlin 39 tests, web vectors match). |
| `feat/drive-backups` | crypto-core | S4b-BL-116 built (Kotlin 44 + web 44 tests). |
| `feat/drive-device-auth` | crypto-core | S4b-BL-127 built (pure logic + Android/iOS auth, no real prompts). |
| `feat/drive-deletion` | crypto-core | S4b-BL-119 built (Kotlin 31 + web 90 tests). |
| `feat/drive-sync` | sync-schema + crypto-core | S4b-BL-118 built (backups NOT merged in; depends on an injected FolderSession). |
| `feat/drive-connect` | backups + device-auth + deletion + sync-schema | S4b-BL-117 PARTLY: `:shared` connect core with tests done; Android screens/strings in `:ui` and `:app` are a WIP commit, NEVER compiled or tested; web page, S4b-BL-73 headers and the web sign-in not started. |
| `feat/drive-photos` | sync + backups | S4b-BL-128 PARTLY: Kotlin done with tests; the web half is a WIP commit, never compiled or tested. |

Not started: S4b-BL-122 spike (needs the owner's real account), S4b-BL-124 runbook, a final integration branch that merges
all of the above (sync and backups use different device ids: key-id hex vs random id; settle in integration).

## Next steps, in order

1. Re-review the delta of `feat/drive-crypto-core` (adversarial, read-only), then run the full Android sequence and the iOS
   klib compile there; fix; mark PR #116 ready; merge when green; read `main`'s push CI.
2. Finish `feat/drive-sync-schema` (notes §4 there), rebase on `main`, PR, merge the same way.
3. Finish `feat/drive-backups`: tests first (table tests, vectors, FakeDrive fault tests), then the TypeScript twin, then
   an adversarial review of the service. Merge `main` into it after #116 lands.
4. Open a PR for `feat/privacy-page`; after it deploys, the owner sets the policy URL in the Cloud console (Branding).
5. Remaining v1 tickets, not started: S4b-BL-122 spike (needs the owner's real test account), 117 connect + CSP/COOP (73),
   127 lock and authenticated deletes, 118 sync, 128 photos, 119 deletion, 124 runbook. Run an adversarial pass on each
   design BEFORE building ("what can someone with write access to the Drive folder do?").
6. Docs pass: all doc rows listed in the notes, at the next free version in every table (check for duplicates after merges);
   add to S4b-BL-126 the rule "never write data under a key not yet confirmed by read-back"; no keys.json error may trigger
   revoke, re-key, wipe or re-create; a revoke must show the new recovery key once.
7. Later requests from the owner: the iOS test guide (for an acquaintance with a Mac; free Apple ID, 7-day build), guide
   screenshots and steps (small compressed images, dummy data, reproducible script), the Hindi/Tamil/Telugu review.

## Owner to-dos
TC-M-25 (boundaries), TC-M-45, Survey of India reply (tell the session), Search Console sitemap recheck, sign off the privacy
text, set the privacy policy URL, Play data-safety answers, open question: does Drive data going to Gemini (opt-in) count as a
Limited Use "transfer"?
