# Firebase Test Lab setup (owner guide): where it lives

| Field | Value |
|---|---|
| Document | Pointer to the Firebase Test Lab owner guide and its status |
| Version | 0.4 |
| Date | 2026-09-24 |
| Author | Claude (Code), Docs team |
| Status | Current |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-24 | Claude (Code), Docs team | First version, next to [firebase-hosting-setup.md](firebase-hosting-setup.md). The guide itself lives in [07 §7.2](../07-secure-build-and-deploy.md#72-firebase-test-lab-owner-setup-keyless), so it is kept in one place. |
| 0.2 | 2026-09-24 | Claude (Code), Docs team | Test Lab has a Workload Identity provider of its own (`github-test-lab`) and the secret `FTL_WIF_PROVIDER`; the Hosting provider is not widened. `.github/workflows/android-emulator.yml` names this file in its header and in its "Skipped" notice. |
| 0.3 | 2026-09-24 | Claude (Code), Docs team | Round 3 review of PR #18: a **new** pool `github-test-lab` is required; the role pair's source; Storage Legacy Bucket Reader if the first run fails on `storage.buckets.get`. |
| 0.4 | 2026-09-24 | Claude (Code), Docs team | Test Lab writes to an owner-created results bucket (repository variable `FTL_RESULTS_BUCKET`); the cost check: a bucket needs a billing account, so this step waits for an owner decision. |

`.github/workflows/android-emulator.yml` (job `firebase-test-lab`; [06](../06-test-plan.md) TC-I-35) names this file.
The owner's step-by-step setup is in **[docs/07 §7.2, "Firebase Test Lab (owner setup,
keyless)"](../07-secure-build-and-deploy.md#72-firebase-test-lab-owner-setup-keyless)**.

**Status on 2026-09-24: not set up.** The job is skipped with a notice until the variables `FIREBASE_PROJECT_ID` (set
already) and `FTL_RESULTS_BUCKET` and the repository secrets `FTL_WIF_PROVIDER` and `FTL_SA_EMAIL` are all set.

**Cost warning (checked 2026-09-24).** The results bucket in step 3 needs a Cloud Billing account on `doorprints`:
Cloud Storage for Firebase no longer supports Spark projects, and Google Cloud's Always Free storage needs a billing
account and is US-only. That conflicts with the zero-cost rule, so **stop before step 3** until the owner decides
([07 §7.2](../07-secure-build-and-deploy.md#72-firebase-test-lab-owner-setup-keyless), *Cost*: leave Test Lab off,
link billing with a ₹0 budget alert, or go back to Test Lab's default bucket). It runs only from `main` (a
push or a manual run). The emulator job in the same workflow runs without any of this.

In short, in project `doorprints`:

1. Turn on the Cloud Testing API and the Cloud Tool Results API.
2. Create the service account `ftl-runner` with Firebase Test Lab Admin and Firebase Analytics Viewer: with your
   own results bucket, exactly the pair Firebase documents for gcloud runs (it can reach the project's buckets).
3. Create the results bucket, for example `doorprints-test-lab-results`: one region (the project's), uniform access, a
   lifecycle rule that deletes objects after 30 days. Grant `ftl-runner` Storage Object Admin on that bucket only,
   plus Storage Legacy Bucket Reader there if the first run fails on `storage.buckets.get`.
4. Create a **new** Workload Identity pool and provider, both `github-test-lab` (not in the pool `github`, which would
   let this workflow act as the Hosting deploy account), whose condition accepts only
   `.github/workflows/android-emulator.yml@refs/heads/main` of this repository, on push or manual run. Grant
   `ftl-runner` (Workload Identity User) to that pool's principal set for this repository. Do **not** change the Hosting
   provider `github-web-deploy`.
5. Add the repository secrets `FTL_WIF_PROVIDER` (the new provider's full name) and `FTL_SA_EMAIL`, and the
   repository variable `FTL_RESULTS_BUCKET` (the bucket's name).

Free quota: 10 virtual and 5 physical device runs a day. Never create a JSON key.

If you see the "Skipped" notice in an `Android emulator` run on `main`, one of the four values is missing.

Related: [firebase-hosting-setup.md](firebase-hosting-setup.md) (same project, separate provider) ·
[06 §16](../06-test-plan.md) (what the tests check) · [10 §13.3](../10-sprint-log.md) (the ticket)
