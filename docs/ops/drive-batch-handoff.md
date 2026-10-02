# Google Drive batch: state and handoff (2026-10-02)

Written because the owner asked to save state (usage limit). Nothing here is merged. Owner decision for this batch: build and
test with unit tests first; local CI, GitHub CI, pull requests and doc updates come later in one pass, fixing what they find.
Never merge a pull request before its checks are green; after each squash merge read `main`'s push CI before the next merge.

## Branches (all pushed, none merged; PR #116 is the only pull request, a draft)

| Branch | Base | State |
|---|---|---|
| `feat/drive-crypto-core` (PR #116, draft) | `main` | S4b-BL-125 built; two adversarial review rounds fixed (head `e81c6e1`). The last commit (revoke issues a new recovery key, revision reset per epoch, repin) has NOT been re-reviewed. Full Android sequence and iOS compile not run for the last two commits. |
| `feat/privacy-page` | `main` | S4b-BL-121 built (`web/public/privacy.html`, 46 SEO tests pass). No PR. |
| `feat/drive-sync-schema` | `main` | S4b-BL-130 WIP (`ddf6e7f`): Kotlin done, 39 tests pass; web half written, never compiled or tested. |
| `feat/drive-backups` | `feat/drive-crypto-core` | S4b-BL-116 WIP (`6ebfa24`): Kotlin core compiles; NO tests, no web. |

Per-ticket notes (decisions, adversarial passes, doc rows to add, exact next steps): `docs/ops/drive-batch-notes/`.
Delete this directory when the batch is merged and its decisions are in the docs.

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
