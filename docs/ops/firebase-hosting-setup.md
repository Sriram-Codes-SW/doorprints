# Firebase Hosting setup (owner guide): where it lives

| Field | Value |
|---|---|
| Document | Pointer to the Firebase Hosting owner guide and its status |
| Version | 0.1 |
| Date | 2026-09-23 |
| Author | Claude (Cowork), Docs team |
| Status | Current |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-23 | Claude (Cowork), Docs team | First version. `.github/workflows/web.yml` (job `firebase-setup`) names this path in its "Deployment skipped" notice; the guide itself lives in [07 §6.3](../07-secure-build-and-deploy.md#63-web-firebase-hosting), so it is kept in one place. |

The owner's click-by-click setup of Firebase Hosting for the Doorprints web app (`https://doorprints.web.app`), its
status, and how the deploy works are in **[docs/07 §6.3, "Web: Firebase Hosting"](../07-secure-build-and-deploy.md#63-web-firebase-hosting)**.

**Status on 2026-09-23:** steps 1–9 are **done** (Firebase project and default site `doorprints`, Spark plan with no
billing account, APIs, the service account `firebase-hosting-deploy` with Firebase Hosting Admin only, the Workload
Identity pool `github` and provider `github-web-deploy` pinned to `web.yml` on `main`, the repository secrets
`FIREBASE_WIF_PROVIDER` and `FIREBASE_SA_EMAIL`, the variables `FIREBASE_PROJECT_ID=doorprints` and
`FIREBASE_SITE_ID=doorprints`, GitHub Pages switched off). Step 10, the first deploy, runs once the `web.yml` change
is on `main`.

If you see the "Deployment skipped" notice in a `Web` run on `main`, one of those four values is missing at
repository level: see step 8 of that section. Never create a JSON key for the service account.

Related: [ADR-21](../03-design.md) (why Firebase Hosting) · [12 Brand and naming](../12-brand-and-naming.md) (why
`doorprints.web.app`) · [08 Operations runbook](../08-operations-runbook.md) (rollback, usage, a disabled site)
