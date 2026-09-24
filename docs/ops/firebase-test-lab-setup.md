# Firebase Test Lab setup (owner guide): where it lives

| Field | Value |
|---|---|
| Document | Pointer to the Firebase Test Lab owner guide and its status |
| Version | 0.2 |
| Date | 2026-09-24 |
| Author | Claude (Code), Docs team |
| Status | Current |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-24 | Claude (Code), Docs team | First version, next to [firebase-hosting-setup.md](firebase-hosting-setup.md). The guide itself lives in [07 §7.2](../07-secure-build-and-deploy.md#72-firebase-test-lab-owner-setup-keyless), so it is kept in one place. |
| 0.2 | 2026-09-24 | Claude (Code), Docs team | Test Lab has a Workload Identity provider of its own (`github-test-lab`) and the secret `FTL_WIF_PROVIDER`; the Hosting provider is not widened. `.github/workflows/android-emulator.yml` names this file in its header and in its "Skipped" notice. |

`.github/workflows/android-emulator.yml` (job `firebase-test-lab`; [06](../06-test-plan.md) TC-I-35) names this file.
The owner's step-by-step setup is in **[docs/07 §7.2, "Firebase Test Lab (owner setup,
keyless)"](../07-secure-build-and-deploy.md#72-firebase-test-lab-owner-setup-keyless)**.

**Status on 2026-09-24: not set up.** The job is skipped with a notice until the variable `FIREBASE_PROJECT_ID` (set
already) and the repository secrets `FTL_WIF_PROVIDER` and `FTL_SA_EMAIL` are all set. It runs only from `main` (a
push or a manual run). The emulator job in the same workflow runs without any of this.

In short, in project `doorprints` (Spark plan, no billing):

1. Turn on the Cloud Testing API and the Cloud Tool Results API.
2. Create the service account `ftl-runner` with Firebase Test Lab Admin and Firebase Analytics Viewer.
3. Give it Storage Object Admin on the `test-lab-…` results bucket only (least privilege; project Editor also works
   but is far broader).
4. Create a Workload Identity provider of its own, `github-test-lab` (best in a new pool), whose condition accepts only
   `.github/workflows/android-emulator.yml@refs/heads/main` of this repository, on push or manual run. Grant
   `ftl-runner` (Workload Identity User) to that pool's principal set for this repository. Do **not** change the Hosting
   provider `github-web-deploy`.
5. Add the repository secrets `FTL_WIF_PROVIDER` (the new provider's full name) and `FTL_SA_EMAIL`.

Free quota: 10 virtual and 5 physical device runs a day. Never create a JSON key.

If you see the "Skipped" notice in an `Android emulator` run on `main`, one of the three values is missing.

Related: [firebase-hosting-setup.md](firebase-hosting-setup.md) (same project, separate provider) ·
[06 §16](../06-test-plan.md) (what the tests check) · [10 §13.3](../10-sprint-log.md) (the ticket)
