# 08: Operations runbook

| Field | Value |
|---|---|
| Document | Operations runbook |
| Version | 0.18 |
| Date | 2026-09-24 |
| Author | Claude (Cowork) |
| Status | Draft |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) | First version: monitoring, backups/restore, key rotation, incident response, data export/deletion, release checklist. |
| 0.2 | 2026-09-22 | Claude (Cowork) | Wave 2: export and delete-all now use the API (`GET /api/export`, `DELETE /api/data`), automatic 90-day tombstone purge, new log lines for auth failures and rate limits, CI-based dependency scanning, key rotation steps for the masked Android key field. |
| 0.3 | 2026-09-22 | Claude (Cowork) | Sprint 2 ([10](10-sprint-log.md)): zero-downtime API key rotation with `APP_API_KEY_NEXT` (section 5.1), emergency rotation and the 32-character minimum upgrade note; Trivy (SBOM, config) replaces Dependency-Check in the routine tasks; check to remove the Tomcat override (F-28); signed release APK from `android.yml`; new section 9 Troubleshooting (non-root DB image, uid 999 bind-mount ownership, F-29; key-length and rotation 401s). |
| 0.4 | 2026-09-22 | Claude (Cowork) | Sprint 3 ([10](10-sprint-log.md)): new section 1.1 with the AI settings, including the new `AI_EMBEDDING_PROVIDER`, `AI_EMBEDDING_API_KEY`, `AI_EMBEDDING_BASE_URL` and `AI_EMBEDDING_TASK_TYPE`; Ollama needs `AI_EMBEDDING_PROVIDER=openai`. Monitoring row for the summarised AI indexing WARN lines and the reindex 503; LLM key rotation covers `AI_EMBEDDING_API_KEY`; two troubleshooting rows. The AI indexing monitoring row quotes the reindex WARN line (the 503 body is a generic problem detail). |
| 0.5 | 2026-09-22 | Claude (Cowork) | Sprint 3 lead decisions ([10](10-sprint-log.md)): section 1.1: run `POST /api/ai/reindex` once after deploying the contact-redaction fix (C-13, F-30); the dev compose passes the AI settings. AI indexing monitoring row matches the AI team's final logging (one summary WARN per 5 minutes, one WARN per reindex run, recovery INFO). Troubleshooting row for short keys points at F-01a (F-01 split in [02](02-threat-model.md) v0.6). |
| 0.6 | 2026-09-22 | Claude (Cowork) | Section 1.1: the one-off `POST /api/ai/reindex` for the contact-redaction fix (F-30) must run after the final (ai-design v0.10) code is deployed; lists what older vectors may still hold. Matches [02](02-threat-model.md) v0.7. |
| 0.7 | 2026-09-22 | Claude (Cowork), Docs team | Product rename to **Doorprints** ([03](03-design.md) ADR-13): password manager entry "Doorprints ops" (rename an existing "House Hunt ops" entry), export ZIP `doorprints-export-<date>.zip` (the API download is now `doorprints-export-<date>.json`, `format` still `house-hunt-export/1` — *superseded in 0.12: since Sprint 4a it is `Doorprints-backup-<UTC date>.json` in `doorprints-backup/1`*), Settings → Apps → Doorprints, release checklist artifact `doorprints-release-apk`. Section 6.2: builds from before the rename (`com.househunt.app`) are a separate app with their own local data; sync and uninstall them. Backup file names (`househunt-*.dump.age`, `househunt-backup.agekey`), the database and the `house-hunt-db` image keep their names so existing backups and volumes still match. |
| 0.8 | 2026-09-22 | Claude (Cowork), Docs team | Product-owner decisions of 2026-09-22: new **section 10** (repository now public as `Sriram-Codes-SW/doorprints` with MIT `LICENSE`, `SECURITY.md` and a ruleset on `main`: settings to check and the history re-check; paid AI key with a hard cap and budget alerts; Google Cloud $300 trial plan and tear-down), new **IR-8** (private vulnerability report) and **IR-9** (AI spend alert or cap reached). Section 1.1: `AI_API_KEY` is the free key only for synthetic evals; real data needs the paid Gemini API tier or Vertex AI ([01](01-requirements.md) PRV-022, [ai/vertex-setup.md](ai/vertex-setup.md)). Section 4: monthly AI spend check, trial end date. Release checklist: no personal data in public logs or artifacts. |
| 0.9 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes. Section 10.2 rewritten around three layers: in-app per-user and global daily caps; a Google Cloud **spend cap budget** (Preview) on the AI-only project and the Vertex AI or Gemini API service (monthly, gross of credits, not instant, can pause cloud AI mid-request; fallback: budget notification that disables billing); budget alerts at 50/90/100 %. The Quotas-page limit is now optional and unverified (Vertex Gemini uses dynamic shared quota). Spend cap budgets do not cover Cloud SQL: staging needs its own budget alert and a tear-down date (section 10.3). IR-9: what to do when the spend cap trips. Section 10.3: day-1 check that the credit covers the models; Test Lab after the trial runs within the no-cost Spark quota. Links to `ai/vertex-setup.md` marked as being written by the AI team. |
| 0.10 | 2026-09-22 | Claude (Cowork), Docs team | Vertex AI code landed (same change set): "being written" markers on [ai/vertex-setup.md](ai/vertex-setup.md) removed. Section 1.1 now lists `AI_PROVIDER`, `GCP_PROJECT_ID`, `GCP_LOCATION`, `AI_VERTEX_EMBEDDING_LOCATION`, `AI_VERTEX_ENDPOINT`, `AI_VERTEX_API_VERSION`, `AI_INDEX_ON_CHANGE` and `GOOGLE_APPLICATION_CREDENTIALS`, and says to re-index after switching provider. Section 5.2: Vertex AI credential row (WIF and Cloud Run have nothing to rotate; JSON key only on a non-Google host). IR-9 and section 9: the new `503` problem with `code: AI_QUOTA_EXHAUSTED` and `Retry-After: 60`, the eval result **STOPPED: provider quota exhausted**, the re-index that stops at the first quota error, and how a spend cap trip looks today (a generic `503`, not yet a distinct "cloud AI paused" state; AI-017). New troubleshooting rows for Vertex AI startup and 403/404 errors; the 403/404 row points to the `setupHint` in the `503` body. Section 10.2 and IR-9: vertex-setup step 7's $50/$150/$250 alerts are the trial-period form of the 50/90/100 % alerts. |
| 0.11 | 2026-09-22 | Claude (Cowork), Docs team | **Vertex AI setup outcome** (owner, 2026-09-22; [ai/vertex-setup.md](ai/vertex-setup.md) v0.3): project `doorprints-ai`; chat `gemini-3.5-flash` verified in `asia-south1`, `gemini-embedding-2` only on `global`; GitHub variables `GCP_PROJECT_ID=doorprints-ai`, `GCP_LOCATION=asia-south1`, `AI_VERTEX_EMBEDDING_LOCATION=global`. Section 1.1 rows name these values and the residency consequence (embedding text processed on `global`). New **section 10.4 "Google Cloud trial end checklist"**: credit ends **22 Dec 2026**; export by about 15 Dec; decide AI Studio (paid key for real data) or a paid upgrade with the spend cap in place; switch before the 22nd; tear down staging; remove WIF secrets if Vertex is dropped. Section 4 calendar: one-off trial-end dates. Section 10.3: trial dates. Section 10.1: new row "Dependency graph: On" (needed by the Sprint 3.5 `gradle-dependency-graph` job, whose first run failed at submission). |
| 0.12 | 2026-09-22 | Claude (Cowork), Docs team | Ticket S4-00/c (`docs/schemas/README.md` §9). Section 6.1 still named the API download `doorprints-export-<date>.json` with `format: house-hunt-export/1` and read photo ids as `.photos[].photo.id`. Since Sprint 4a `GET /api/export` returns the shared `doorprints-backup/1` object as `Doorprints-backup-<UTC date>.json` (`photos[]` rows carry `id` directly); the example saves it as `data.json`, fetches photo bytes by `.photos[].id`, and says how to restore it (`POST /api/import`, `?dryRun=true` first, or Android Import, which accepts a bare `data.json`). The v0.7 row's file name is marked superseded. |
| 0.13 | 2026-09-23 | Claude (Cowork), Docs team | **Owner decision (2026-09-23): the web app is hosted on Cloudflare Pages** ([03](03-design.md) ADR-21). §7 IR-5 (free-tier outage, move provider): the web can move to another host that reads `_headers` (Netlify), **not** to GitHub Pages, and `npm run build:pages` is no longer a GitHub Pages build. §8 release checklist: the web step names the `web.yml` Cloudflare Pages deploy and its header check, the `pages.dev` address, and `APP_CORS_ORIGINS`. §4 quarterly review: Cloudflare Pages terms. New note on withdrawing an old deployment ([02](02-threat-model.md) RR-12). §5.2: rotating the Cloudflare API token. |
| 0.14 | 2026-09-23 | Claude (Cowork), Docs team | Review fix, §5.2 Cloudflare API token row: "replace the repository secret" now says to replace it where it is stored, preferably the `cloudflare-pages` environment's secrets restricted to `main` ([07](07-secure-build-and-deploy.md) v0.21 §4 and §6.3 step 4). |
| 0.15 | 2026-09-23 | Claude (Cowork), Docs team | **Owner decision of 2026-09-23: the web app is on Firebase Hosting at `https://doorprints.web.app`** (Spark plan, no billing account; [03](03-design.md) ADR-21), replacing the Cloudflare Pages plan, which was never set up. §1 components; **§2** new *Web host usage* row (Hosting > Usage: transfer against 360 MB/day or 10 GB/month, storage against 10 GB; there is no budget alert on Spark); **§4** monthly usage check and 10 releases kept, quarterly free-tier terms name Firebase Hosting; **§5.2** the Cloudflare token row becomes the web deploy identity (Workload Identity, nothing to rotate; what to do on suspicion); **§7 IR-5** rewritten for Firebase (a site disabled for quota; moving host); new **IR-10** *Bad web release: roll back* (Firebase console → Hosting → release history → Rollback, no build) and deploy / manual re-run; **§8** release checklist. |
| 0.16 | 2026-09-24 | Claude (Code), engineer | **Legacy House Hunt names renamed** (owner request of 2026-09-24; [03](03-design.md) ADR-24). New **section 11**: what changes for a self-hosted server (the compose database, user, password default and volume are `doorprints`; the MCP tool `askHouseHunt` is `askDoorprints`), that a server which sets `DB_URL`, `DB_USER` and `DB_PASSWORD` is not affected and no environment variable is renamed, and how to carry a local compose database over (dump with the old names, restore into the new one). Section 3: the dump file, schema and age key examples use `doorprints`. |
| 0.17 | 2026-09-24 | Claude (Code), Docs team | Reviews of PR #19. **§11**: the dump and restore of a local compose database is **mandatory** for a database that clients have synced with; the claim that phones and the web app sync a full copy back to an empty server was false (clients push only changed rows and pull after a stored cursor, so on a new database they silently miss each other's changes; [10](10-sprint-log.md) S4b-BL-20). The steps now use `docker compose up -d --wait db`, say that `pg_restore` reports "already exists" for the PostGIS objects and exits non-zero, count the rows before and after, and give the throwaway-container fallback a build step, a `pg_isready` wait and `docker rm -f old-db`. §6.2: a pre-rename test build is `com.househunt.app` (was `app.doorprints`, a find-and-replace error). The §11 intro rewrapped. |
| 0.18 | 2026-09-24 | Claude (Code), lead | Clients detect a reset server (S4b-BL-20, branch `claude/doorprints-dev-continue-fzcge2`, PR #24). New **§11.1**: `GET /api/stats` returns `maxSyncVersion`; what Android and the web do when it is below their cursors; the dump and restore of §11 stays mandatory; what to expect after restoring an older dump. §11's backlog pointer and IR-4 step 4 updated. |

Related: [Build and deploy](07-secure-build-and-deploy.md) · [Threat model](02-threat-model.md) · [Test plan](06-test-plan.md)

---

## 1. Service summary

| Item | Value |
|---|---|
| Components | API (Render/Koyeb/Oracle VM), Postgres+PostGIS (Supabase/Neon), web (Firebase Hosting, Spark plan, `https://doorprints.web.app`), Android app (sideloaded) |
| Health | `GET https://<api>/actuator/health` → `{"status":"UP"}` (public, includes the DB check) |
| Targets | RPO ≤ 24 h (nightly backup; the phone also holds a full offline copy), RTO ≤ 4 h (NFR-008) |
| On-call | The owner (single user). Keep this runbook and the password manager entry "Doorprints ops" (formerly "House Hunt ops") up to date. |

### 1.1 AI settings (only when `APP_AI_ENABLED=true`)

AI is off by default (AI-001); none of these variables is needed then. Full list and defaults: [ai/ai-design.md](ai/ai-design.md) §11 and [07 §7](07-secure-build-and-deploy.md).

| Variable | Default | When to change it |
|---|---|---|
| `AI_PROVIDER` | `aistudio` | `aistudio`: Gemini API with `AI_API_KEY` (the rows below). `vertex`: Google Cloud Vertex AI with Application Default Credentials; needs `GCP_PROJECT_ID`, and `AI_API_KEY` / `AI_EMBEDDING_*` URLs and keys are ignored. Other values stop startup. Owner setup: [ai/vertex-setup.md](ai/vertex-setup.md); switch back at any time with `AI_PROVIDER=aistudio` (step 13). |
| `AI_API_KEY` | – | Gemini API key from Google AI Studio; for Ollama any non-empty value (for example `ollama`). Used for chat and, by default, for embeddings. Secret. Only used with `AI_PROVIDER=aistudio`. **With real data use a key on a billing-enabled (paid) project or Vertex AI** ([ai/vertex-setup.md](ai/vertex-setup.md)); a free-tier key is for synthetic evals only, because the free tier may use prompts to improve Google products ([01](01-requirements.md) PRV-022, [02](02-threat-model.md) T-I20). |
| `AI_EMBEDDING_PROVIDER` | `google-genai` | `google-genai`: embeddings from the native Gemini API (`models/{model}:batchEmbedContents`). **Ollama (or any other OpenAI-compatible server) needs `AI_EMBEDDING_PROVIDER=openai`**, which sends embeddings to `AI_BASE_URL` like chat. Any other value stops startup with a message naming the variable. |
| `AI_EMBEDDING_API_KEY` | `AI_API_KEY` | Only for `google-genai`, and only if embeddings should use a different Gemini key than chat. Secret. |
| `AI_EMBEDDING_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta` | Only for `google-genai`; leave the default. |
| `AI_EMBEDDING_TASK_TYPE` | empty | Only for `google-genai` with `AI_EMBEDDING_MODEL=gemini-embedding-001` (for example `RETRIEVAL_DOCUMENT`); `gemini-embedding-2` rejects task types. |
| `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIMENSIONS` | `gemini-embedding-2`, `768` | Ollama: `nomic-embed-text`. Dimensions must stay `768` (the `vector(768)` column). Same values on both providers. |
| `GCP_PROJECT_ID` | – | Vertex only, **required** (startup fails without it): the AI-only project's id (not its number). |
| `GCP_LOCATION` | `asia-south1` | Vertex only: location for chat and, by default, embeddings. If a model answers 404 `NOT_FOUND` there, use `global` (widest availability, no India residency guarantee) or `us-central1` ([ai/vertex-setup.md](ai/vertex-setup.md) step 8). **This project: `asia-south1`** (chat `gemini-3.5-flash` verified there on 2026-09-22; project `doorprints-ai`). |
| `AI_VERTEX_EMBEDDING_LOCATION` | = `GCP_LOCATION` | Vertex only: set (for example `global`) when the embedding model is not offered in `GCP_LOCATION`. **This project: `global`**, because `gemini-embedding-2` is not offered in `asia-south1` (verified 2026-09-22). Consequence: chat stays in India, but the redacted embedding text (house notes, street, locality; every save or re-index) and Ask questions are processed on `global` ([02](02-threat-model.md) T-I20). Set it on every host that runs with `AI_PROVIDER=vertex`, not only in GitHub. |
| `AI_VERTEX_ENDPOINT`, `AI_VERTEX_API_VERSION` | derived from the location, `v1beta1` | Vertex only; leave the defaults (the endpoint override is for tests and Private Service Connect). |
| `AI_INDEX_ON_CHANGE` | `true` | `false` stops embedding each house right after a save; only `POST /api/ai/reindex` embeds then (fewer provider calls). Deletes still leave the index at once. |
| `GOOGLE_APPLICATION_CREDENTIALS` | unset | Vertex only, standard ADC variable: path to a credential file. Not needed on Cloud Run (the attached service account) or locally after `gcloud auth application-default login`; on a non-Google host it points to the mode-600 key file ([07](07-secure-build-and-deploy.md) §4). |

After changing the embedding provider or model, or `AI_PROVIDER`, run `POST /api/ai/reindex` so every house is embedded with the new model on the new endpoint (both providers use the same model, so this is a precaution when only `AI_PROVIDER` changed).

**Once after deploying the contact-redaction fix** (Sprint 3, C-13, [02](02-threat-model.md) F-30): run `POST /api/ai/reindex` until it returns 200, **after the final version of the fix is deployed** ([ai/ai-design.md](ai/ai-design.md) v0.10 §9.1; a reindex run on an earlier build of the fix must be repeated). Vectors indexed before it may still hold the contact name (before ai-design v0.7), a first name in the label (v0.7), a "C/o <owner>" address (v0.8 and older) or an initials-style "C/o K Ramesh" address (v0.9 and older) in pgvector (the database, not the provider); the Ask path already scrubs them before any prompt or citation, and the reindex replaces them with redacted text. The dev `docker-compose.yml` passes all these settings from the shell or a `.env` file ([07 §7](07-secure-build-and-deploy.md), column *Dev compose*).

## 2. Monitoring

| What | How (free) | Threshold / action |
|---|---|---|
| API up | UptimeRobot free HTTP monitor on `/actuator/health` (5-min checks), or `keepalive.yml` in GitHub Actions | 2 consecutive failures → check the host dashboard. A sleeping free instance answers after a cold start, so don't alert on the first slow response. |
| DB alive / not paused | `keepalive.yml` every 3 days (health hits the DB) | Supabase "paused" email → resume in the dashboard |
| DB size | Monthly: `select pg_size_pretty(pg_database_size(current_database()));` and `select pg_size_pretty(pg_total_relation_size('photo'));` | > 60% of the free quota → act on F-06 (cap photos / move to object storage) |
| Sync health | Android Settings shows the last sync time and message | Last sync older than 24 h while online → Sync now, check the key/URL |
| Auth failures | Host logs, WARN lines `auth.fail reason=wrong-key client=<hash> …`, `auth.throttled …` (429 after 10 wrong keys/min per address) and `auth.reject reason=non-canonical-path …` | Sudden spike of `auth.fail`/`auth.throttled` → IR-2 (possible leaked or guessed key). `auth.reject` bursts are scanners probing path tricks (F-20): no action unless combined with 200s. |
| Rate limiting | 429 responses in the host's request log | Many 429 from one address → a runaway client or a flood; consider a Cloudflare rule |
| Web host usage | Firebase console → project `doorprints` → Hosting → **Usage** (monthly, and after sharing the link widely): data transfer and storage. Spark has no billing account, so **no budget alert** exists | Transfer approaching **360 MB/day** (pricing page) or **10 GB/month** (quota page; plan for the stricter) → look for a download loop or an unexpected audience; storage near 10 GB → check *Releases to keep* is 10. Site shows as disabled → IR-5 |
| Web deploy | `Web` workflow runs on `main`: `deploy-firebase` green, its *Security headers are served* step green (GitHub notifies on failure) | A red header check → the live site may be missing a header: compare `web/firebase.json` with the last good release; roll back (IR-10) if a release is broken |
| Tombstone purge | INFO line `privacy.purge tombstones: houses=… visits=… photos=…` daily at 03:30 | None expected; absent for weeks with deletes happening → check the scheduler |
| Dependencies | Dependabot PRs, `security.yml` (Trivy, npm audit, Semgrep, gitleaks) on every push and weekly | Critical/High → patch within 7 days |
| Backups | `backup.yml` run status (GitHub notifies on failure) | Failed 2 nights in a row → fix the same day |
| AI usage (when enabled) | Usage counters (D8) / provider console | ≥ 80% of the daily free quota → check for abuse (IR-6) |
| AI indexing (when enabled) | WARN line `AI index update failed for N house(s)…; they are searchable again after POST /api/ai/reindex` (at most one per 5 minutes, a summary instead of one line per house; one INFO `AI index updates are working again` after an outage); one WARN per reindex run `AI reindex incomplete: N house(s) in b of n batch(es) not indexed, k indexed` (per-batch detail at DEBUG); `POST /api/ai/reindex` then answers **503** (generic problem detail), logged as `Re-indexing failed (ReindexFailedException: …)` | Check the provider key, quota and `AI_EMBEDDING_*` settings (section 1.1), then run `POST /api/ai/reindex` again until it returns 200. House ids and the provider error class are logged at DEBUG only. |

Logs: use the host's log viewer (Render/Koyeb dashboard, `docker compose logs` on the VM). Logs must not contain keys, coordinates or notes (SEC-016).

## 3. Backups

**Strategy:** a nightly logical dump from GitHub Actions, encrypted with [age](https://github.com/FiloSottile/age) to a public key. The private key is kept offline. Retention 30 days. Optionally, push a monthly copy to free object storage (Cloudflare R2, 10 GB free).

```yaml
# .github/workflows/backup.yml (sketch)
name: backup
on:
  schedule: [{ cron: '30 20 * * *' }]   # 02:00 IST
  workflow_dispatch:
permissions: { contents: read }
jobs:
  dump:
    runs-on: ubuntu-latest
    environment: production
    container: postgres:17            # match the server major version
    steps:
      - run: apt-get update && apt-get install -y age
      - env:
          BACKUP_DB_URL: ${{ secrets.BACKUP_DB_URL }}        # read-only role, sslmode=require
          AGE_RECIPIENT: ${{ vars.BACKUP_AGE_RECIPIENT }}   # public key
        run: |
          set -euo pipefail
          f="doorprints-$(date -u +%F).dump.age"
          pg_dump -Fc --no-owner --no-privileges --schema=public "$BACKUP_DB_URL" | age -r "$AGE_RECIPIENT" -o "$f"
          ls -l "$f"
          echo "FILE=$f" >> "$GITHUB_ENV"
      - uses: actions/upload-artifact@<full-sha>
        with: { name: db-backup, path: '${{ env.FILE }}', retention-days: 30 }
```

Notes:

- Use `--schema=doorprints` if you moved the tables into their own schema (07 section 6.1). On Supabase, don't dump its internal schemas.
- Photos are in the DB (ADR-08), so dumps grow with them. Watch the artifact storage quota (500 MB on private Free repos). If it gets close, keep 7 days in artifacts and send weekly copies to R2.
- Never upload **unencrypted** dumps (T-I11). Don't print row data in logs.
- Extra copies of the data: the Android Room DB (full offline copy, not a backup of the server) and a manual export (section 6).

### 3.1 Restore (tested quarterly, TC-O-01)

```bash
# 1. Fresh target DB with PostGIS (local docker or a new free project)
docker run -d --name restore -e POSTGRES_PASSWORD=x -p 5433:5432 postgis/postgis:17-3.5
psql "postgresql://postgres:x@localhost:5433/postgres" -c 'CREATE EXTENSION IF NOT EXISTS postgis;'
# 2. Decrypt and restore
age -d -i ~/secure/doorprints-backup.agekey doorprints-YYYY-MM-DD.dump.age \
  | pg_restore --no-owner --no-privileges -d "postgresql://postgres:x@localhost:5433/postgres"
# 3. Check
psql "postgresql://postgres:x@localhost:5433/postgres" -c 'select count(*) from house; select max(sync_version) from house; select last_value from sync_seq;'
# 4. Point DB_URL at the restored DB (for a real recovery) and redeploy; health must be UP
```

After restoring into production, make sure `sync_seq.last_value` ≥ `max(sync_version)` across `house` and `visit`. Otherwise clients will not pull new changes. Fix it with `select setval('sync_seq', (select greatest(max(h.sync_version), max(v.sync_version)) from house h, visit v));`. Then tell each client to **reset its cursor** (change the server URL and change it back, which does a full download). Any phone edits made since the backup will re-sync because they are still marked dirty. Changes that were already synced but made after the backup are lost unless some device still has them.

## 4. Routine maintenance calendar

| Frequency | Task |
|---|---|
| Weekly | Merge Dependabot PRs after CI passes. Read the weekly `security.yml` run (Trivy SBOM/fs/config, npm audit, gitleaks, Semgrep) and any ZAP report. When a Spring Boot patch manages Tomcat 11.0.25 or later, remove the `tomcat.version` override from `backend/pom.xml` (F-28, 07 §1). |
| Monthly | DB size check. Check the backup run history. Update the Android app if a release exists. Check AI spend against the budget and the per-user and global caps (section 10.2). While the Google Cloud trial runs: credit left and days left (section 10.3). Firebase Hosting usage (section 2) and *Releases to keep* = 10. |
| One-off (2026) | **By about 15 Dec 2026:** Google Cloud trial export and provider decision; **before 22 Dec 2026:** switch or upgrade, tear down staging (section 10.4). Put both dates in the calendar and the password manager entry. |
| Quarterly | Restore drill (TC-O-01). Rebuild the base image. Check free-tier terms (Render, Supabase/Neon, Firebase Hosting Spark, OpenFreeMap, Nominatim, LLM). If a custom domain was ever bought: auto-renew on and the renewal date in the calendar ([12](12-brand-and-naming.md) section D). |
| 6-monthly | Rotate `APP_API_KEY` (section 5.1). Review the threat model findings. |
| Yearly | Rotate DB, backup-role and CI secrets. Review the docs (bump versions). Check the keystore backup is readable. |

## 5. Key and secret rotation

### 5.1 API key (`APP_API_KEY`, `APP_API_KEY_NEXT`)

Since Sprint 2 the API accepts two keys at once (SEC-017): the current `APP_API_KEY` and, during a rotation only, `APP_API_KEY_NEXT`. Both must be at least 32 characters; the API refuses to start otherwise and the log names the variable, never the value. Rotation therefore has **no 401 window**: clients move to the new key while the old one still works.

**Routine rotation (every 6 months, zero downtime)**

| Step | Action | Check |
|---|---|---|
| 1 | Generate: `openssl rand -hex 32` (64 chars). Save it in the password manager as "next". | Length ≥ 32 |
| 2 | Set `APP_API_KEY_NEXT=<new>` in the host's secret settings (Render/Koyeb) or the VM `.env` (`compose.prod.yml` passes it through). Keep `APP_API_KEY=<old>`. Redeploy/restart. | Health UP. `curl -s -o /dev/null -w '%{http_code}' -H "X-API-Key: <old>" https://<api>/api/stats` → `200`; with `<new>` → `200` |
| 3 | Web: Connect page → paste the new key → Test → Save (on each browser). | Map loads |
| 4 | Android: Settings → paste the new key (the field is empty; the hint shows the last 4 characters of the saved key) → Save and test → Sync now, on every phone. Unsynced local changes are kept and pushed. | Settings shows the new last 4 characters; sync OK |
| 5 | Any other client (MCP client, scripts): update the key. | – |
| 6 | Promote: set `APP_API_KEY=<new>` and clear `APP_API_KEY_NEXT` (empty or removed). Redeploy/restart. | `<old>` → `401`, `<new>` → `200` |
| 7 | Record the date in the password manager entry; delete the old key there. Watch the logs for a day: `auth.fail` lines from a known address mean a client was missed (it can be given the new key at any time). | TC-O-02 |

Do not leave `APP_API_KEY_NEXT` set after a rotation: while it is set, two keys are valid.

**Emergency rotation (key leaked or suspected, IR-2)**: do **not** use the overlap. Set `APP_API_KEY=<new>`, make sure `APP_API_KEY_NEXT` is empty, redeploy, confirm the old key gets `401`, then update the clients (steps 3 to 5). Clients get 401 until they are updated; sync retries later and the phone stays fully usable offline.

**Upgrading from a 16–31 character key** (before Sprint 2 the minimum was 16): the new version will not start with it. Set a new 32+ key as `APP_API_KEY` in the same deploy (emergency-style swap, then update the clients), or rotate to a 32+ key with the old version first.

### 5.2 Other secrets

| Secret | Procedure |
|---|---|
| DB password | Reset in the provider dashboard → update `DB_PASSWORD` (and `BACKUP_DB_URL`) → redeploy → check health |
| LLM provider key | Revoke in the provider console → new key in the host env (`AI_API_KEY`, and `AI_EMBEDDING_API_KEY` if it is set separately) → redeploy. See [ai/](ai/). |
| Vertex AI credential | GitHub Actions (Workload Identity Federation) and Cloud Run (attached service account): no stored key, nothing to rotate; on suspicion remove the `roles/iam.workloadIdentityUser` binding or disable the service account, then re-grant after the fix. JSON key on a non-Google host only: quarterly and on suspicion, create a new key → replace the file named by `GOOGLE_APPLICATION_CREDENTIALS` → redeploy → delete the old key in IAM. [07](07-secure-build-and-deploy.md) §4, [02](02-threat-model.md) T-I22. |
| Deploy hook / SSH key | Regenerate in Render / replace the `authorized_keys` line → update the GitHub environment secret |
| Web deploy identity (`FIREBASE_WIF_PROVIDER`, `FIREBASE_SA_EMAIL`; Firebase project `doorprints`) | **Nothing to rotate**: Workload Identity Federation issues a token of about an hour to `web.yml` on `main` only, and the service account `firebase-hosting-deploy` has no key ([07](07-secure-build-and-deploy.md) §6.3). **On suspicion** (an unexpected release in *Hosting → release history*, a changed provider condition, a key found on the service account): Cloud console → project `doorprints` → *IAM & Admin → Service Accounts* → `firebase-hosting-deploy` → *Disable* (or remove its *Workload Identity User* binding); delete any key listed under *Keys*; check the provider `github-web-deploy`'s attribute condition against 07 §6.3 step 6; roll back the site (IR-10); then re-enable. The two GitHub secrets are identifiers, not credentials. If the Cloudflare secrets were ever added, delete them (07 §6.3 step 8). |
| GitHub PAT | Revoke at github.com/settings/tokens. Create a fine-grained one with expiry ≤ 90 days only if needed. |
| Age backup key | Create a new key pair → update `BACKUP_AGE_RECIPIENT`. Keep the old private key until the old backups expire (30 days). |
| Android signing key | **Do not rotate** unless it is compromised (see IR-7) |

## 6. Data export and deletion (privacy operations)

Required by PRV-004/PRV-005. The API implements both (FR-031/FR-032); app buttons are backlog, so use `curl`.

### 6.1 Export

```bash
API=https://<api>; KEY=<key>
curl -sf -H "X-API-Key: $KEY" "$API/api/export" -o data.json      # doorprints-backup/1: houses, visits, photo rows
mkdir -p photos
for p in $(jq -r '.photos[].id' data.json); do
  curl -sf -H "X-API-Key: $KEY" "$API/api/photos/$p" -o "photos/$p.jpg"
done
zip -r doorprints-export-$(date +%F).zip data.json photos   # store encrypted: it holds location history
```

Since Sprint 4a the API download is named `Doorprints-backup-<UTC date>.json` and is the `data.json` half of the
shared **`doorprints-backup/1`** format ([schemas/README.md](schemas/README.md), [03](03-design.md) ADR-20; it was
`doorprints-export-<date>.json` with `format: house-hunt-export/1` before). Photo bytes are not in it; the loop above
fetches them. The ZIP made here is an archive for the owner, **not** a device backup ZIP (it has no `manifest.json`).
To restore: `POST /api/import` with the `data.json` body (`?dryRun=true` first to see the counts; 413 above
`MAX_IMPORT_BYTES`, 16 MiB, or `MAX_IMPORT_ROWS`), or Android *Import*, which accepts a bare `data.json` and reports
its photos as missing from the file. A full export for migration is the encrypted `pg_dump` from section 3.

### 6.2 Deletion

| Scope | Procedure (SQL on the prod DB, admin role, **take a backup first**) |
|---|---|
| One house | Delete it in the app (or `DELETE /api/houses/<id>`). The server blanks its content, deletes its photo bytes, unlinks its visits and keeps a content-free tombstone so other devices delete their copy; the tombstone is purged automatically after 90 days. |
| Purge tombstones | Automatic, daily at 03:30 (`DataService.purgeTombstones`, `TOMBSTONE_RETENTION_DAYS`, default 90). A device that has not synced for longer than that keeps its local copy of rows deleted elsewhere. |
| Old visits (retention, PRV-006) | `delete from visit where arrived_at < now() - interval '6 months';` (devices keep their copies until the app's data is cleared) |
| Everything (end of hunt) | 1) Export if wanted. 2) `curl -X DELETE -H "X-API-Key: $KEY" -H "X-Confirm-Delete: DELETE-ALL-MY-DATA" "$API/api/data"` (hard-deletes houses, visits, photos and AI index rows; 428 without the exact header), or delete the DB project. 3) Delete the API service. 4) Delete the backup artifacts / R2 objects. 5) Android: Settings → Apps → Doorprints → Storage → Clear storage, then uninstall. A test build from before the rename (app name "House Hunt", `com.househunt.app`) is a separate app with its own local copy: clear and uninstall it too. 6) Web: Connect → Disconnect, and clear site data. 7) If AI was used: delete the embeddings (same DB) and check the LLM provider's retention/deletion options. |

Third-party contact data (landlord/agent phone numbers) is removed with the house. If a third party asks you to delete their details, use the "one house" or field-level update (set `contact_name`/`contact_phone` to null through the app so it syncs).

## 7. Incident response

General flow: **Detect → Contain → Eradicate → Recover → Learn.** Record each incident (date, what happened, actions, follow-ups) in a private note or issue. Update the threat model (02) if you learned something new.

### IR-1 Lost or stolen phone

| Step | Action |
|---|---|
| Contain (first hour) | 1. Lock/erase the phone via Google Find My Device. 2. **Rotate the API key** (section 5.1): the key is stored in plaintext on the phone (F-03), so treat it as compromised. |
| Assess | Check for unexpected server changes since the loss: `select id, label, deleted, updated_at, sync_version from house where updated_at > '<loss time>' order by sync_version;` (the same for `visit`) |
| Recover | If tampered: restore from the last backup before the loss (section 3.1), or fix the rows by hand. Install the APK on the new phone → new key → sync (full download). Unsynced edits on the lost phone are gone. |
| Learn | Check that the screen lock was on. Finish F-03 (Keystore-encrypted key, backups excluded). |
| Third-party data | Contacts on the phone may be exposed. For personal use no DPDP notice is required (section 3(c)(i)), but think about warning agents/landlords if misuse is likely. |

### IR-2 Leaked API key (committed to git, pasted in chat/logs, screenshot, spike of accesses)

| Step | Action |
|---|---|
| Contain | Rotate immediately with the **emergency** procedure in section 5.1 (no `APP_API_KEY_NEXT` overlap; if a rotation was in progress and the leaked key is the next key, clear `APP_API_KEY_NEXT` too). Revocation is the real fix. Removing the key from history does not undo the leak. |
| Eradicate | If it was committed: remove it from the code, rewrite history with `git filter-repo` if the repo is public, force-push, and ask GitHub support to purge cached views if needed. Check that gitleaks/push protection were on. |
| Assess | Host logs: requests from unknown IPs / user agents since the leak. Data check as in IR-1. If AI was enabled, check provider usage. |
| Recover | Restore from backup if data was changed. Update all clients. |
| Learn | Add the pattern to the gitleaks config if it was missed. Consider per-device keys (SEC-025). |

### IR-3 Database credential leak or provider breach notice

Reset the DB password (section 5.2). Check roles (`\du`) for unknown users. Check the provider's network restrictions. Compare the data with the last backup. If the provider says data was accessed, treat all C3 data as disclosed: rotate the API key too, and tell affected third parties if their contact data could be misused.

### IR-4 Sync corruption or unexpected data changes

1. Stop background sync on devices: turn off network / clear the server URL in Settings. Local data is safe.
2. Export the current server state (section 6.1) and a `pg_dump`.
3. Compare with the latest backup and with the phone data. Find the cause (clock skew → F-08, concurrency → F-09).
4. Fix the data, `setval` the sequence if you restored, then re-enable sync and reset cursors. A restore that leaves
   `sync_seq` below the clients' cursors is detected by clients from 2026-09-24 on, which then send everything again
   (§11.1).

### IR-5 Free-tier outage, suspension or policy change

The phone keeps working offline. To move provider: restore the latest dump to another provider (section 3.1) → update `DB_URL` → redeploy. The API image runs on any Docker host. Tiles: change `MAP_STYLE_URL` (web `shared/map-style.ts`, Android `MAP_STYLE_URL`) if OpenFreeMap is unavailable.

**Web (Firebase Hosting, Spark).** If `https://doorprints.web.app` shows that the site is disabled, the Spark transfer quota was exceeded ([02](02-threat-model.md) T-D9, RR-13): Firebase re-enables it when the day or month resets. Nothing is lost — each user's houses are in their own browser, the Android app and a synced server are unaffected — and an installed web app keeps starting from its service-worker precache. Check *Hosting → Usage* for a download loop; tell users to use the installed app or the phone meanwhile. **Never attach a billing account to lift it** (that moves the project to Blaze, CON-01). If it recurs, the owner decides between another static host that sends the same headers (translate `web/firebase.json`: Netlify reads `_headers` plus a `/* /index.html 200` rule; Cloudflare Pages is the natural host **with** a custom domain, [12](12-brand-and-naming.md) section E) and Blaze with a budget (not zero-cost). **Not** GitHub Pages: it sends no headers and shares one origin across the owner's Pages sites ([02](02-threat-model.md) F-31, RR-11). A move to another address strands every user's browser data at the old one, so announce it with a *Full backup* export and import step, and only once the web can import (Sprint 4b); then add the new origin to `APP_CORS_ORIGINS` on every API.

### IR-6 AI abuse or prompt-injection incident (when AI is enabled)

Set the AI flag to off (AI-001) and redeploy. Rotate the LLM key. Look at the offending input (listing/note). Add it to the injection test suite (TC-AI-04). Review whether any tool was called and what it returned. Re-enable only after the eval suite passes.

### IR-7 Signing key compromise or malicious APK circulating

Publish a warning in the repo README with the correct certificate fingerprint. Create a new keystore. Tell users (yourself/family) to uninstall and reinstall. **Sync first**, because uninstalling wipes local data. Rotate the API key.

### IR-8 Vulnerability report received (private vulnerability reporting)

A report arrives under **Security → Advisories** (from `SECURITY.md`). Acknowledge within 7 days (the promise in
`SECURITY.md`). Reproduce, rate it with the [02](02-threat-model.md) scale and record it as a finding. Fix in a
private fork of the advisory (GitHub offers a temporary private fork), then publish the fix and the advisory together;
credit the reporter if they agree. If a report is opened as a public issue by mistake, ask the reporter to use private
reporting, and hide or delete the issue if it contains exploit details.

### IR-9 AI spend alert or hard cap reached

A budget alert (50/90/100 %; during the trial the $50/$150/$250 alerts of [ai/vertex-setup.md](ai/vertex-setup.md) step 7), the spend cap tripping (cloud AI calls fail with provider errors), a run of provider
quota errors, or, once the Sprint 5 cap exists, the app's global-cap log line (section 10.2).

How the signals look today: a provider quota error (HTTP 429 / `RESOURCE_EXHAUSTED` from AI Studio or Vertex AI,
after the bounded retries) is logged as a WARN containing `[provider quota exhausted]` and answered with `503`, a
problem body with `"code": "AI_QUOTA_EXHAUSTED"`, `"retryable": true` and a `Retry-After: 60` header. A re-index
stops at the first such error (the remaining batches count as failed; `POST /api/ai/reindex` returns 503), and an
eval run ends with **STOPPED: provider quota exhausted** instead of PASS/FAIL. A per-minute quota clears by itself;
a quota error that keeps coming back for hours points to a daily quota, shared-capacity limits or a loop. Check the per-user counters for
one account using most of the requests; remove that invitation or lower its quota. If the pattern looks like a stolen
session, revoke it (IR-1 / IR-2 as applicable) and rotate the AI key or Vertex credentials. If the spend comes from a
bug (a retry loop), set the AI flag to off (AI-001) and redeploy. Budget alerts only notify; the spend cap budget
(section 10.2, layer 2) is what stops billing for the AI service.

If the **spend cap** has tripped, cloud AI calls fail for everyone (on-device AI keeps working). Until AI-017 is
done the app does not recognise this case: it answers a generic `503` with `retryable: true` (or
`AI_QUOTA_EXHAUSTED` if Google reports it as HTTP 429), so clients say "try again later" although nothing will
work until the cap is lifted. Check Cloud Billing → Budgets before assuming an outage. Find the cause first (above). Then either leave it paused until the 1st of the next month, or
raise the target or lift the cap in Cloud Billing → Budgets. Never lift it while the cause is unknown. If the
billing-disable fallback fired instead, re-link the billing account to the AI project after the fix.

### IR-10 Bad web release: roll back (Firebase Hosting)

A release of the web app is broken (a blank page, a failed header check, a security fix that must be undone or a
regression users report). **Roll back first, fix second:** Firebase console → project `doorprints` → *Hosting* →
release history → the last good release (its message is `web.yml <commit sha>`) → **⋮** → **Rollback**. It takes
effect at once and needs no build; 10 releases are kept. Installed apps pick the rolled-back build up on their next
update check (the precache is per build). Then fix on a branch and merge; the next push to `main` deploys again.
**Deploy or re-run by hand:** GitHub → *Actions* → *Web* → *Run workflow* on `main` (the same checks run). A deploy
that fails with `PERMISSION_DENIED`: send the red line to DevSecOps; do not add roles to the service account by hand
([07](07-secure-build-and-deploy.md) §6.3). Old releases are not reachable at addresses of their own, so there is
nothing else to withdraw.

## 8. Release checklist

- [ ] Version bumped: `android/app/build.gradle.kts` (`versionCode` +1, `versionName`), `backend/pom.xml`, `web/package.json`.
- [ ] New Flyway migration (if any) is additive, reviewed and tested on a restored copy of prod.
- [ ] CI green on `main`: tests, Semgrep, gitleaks, Trivy, SCA. No unaccepted Critical/High.
- [ ] Test plan exit criteria met ([06 section 11](06-test-plan.md#11-entry-and-exit-criteria)), including the field walk test when location code changed.
- [ ] Threat model findings: fixed ones marked, new risks recorded. Docs versions and change logs updated.
- [ ] Fresh backup taken (manual `backup.yml` run) within 24 h.
- [ ] Deploy the API by image digest → health UP → smoke test (stats, create/delete a test house, then purge it).
- [ ] Web deployed to Firebase Hosting (`web.yml` job `deploy-firebase` green, **including its security-header check**) → open `https://doorprints.web.app`: Connect and Map load, no CSP errors in the console; `APP_CORS_ORIGINS` on the API lists `https://doorprints.web.app` (no path); the release is in *Hosting → release history*.
- [ ] Release APK built by the `android.yml` `release` job (`doorprints-release-apk`; later `release.yml`), `apksigner verify` fingerprint in the log matches the published one, SHA-256 published.
- [ ] Install on the phone **after syncing**. Settings show the right server. Sync OK. Hunt mode starts and stops.
- [ ] If AI changed: eval results attached and meet the thresholds. Flag default stays off unless approved.
- [ ] Public repository: no secrets, personal data or unencrypted dumps in the commit, workflow logs or artifacts (section 10.1).
- [ ] Release notes: features, fixes, security fixes, migrations, known issues.

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Dev/CI database container (`doorprints-db`, `backend/db/Dockerfile`) exits on first start with a permission error: `initdb: error: could not change permissions of directory …`, `mkdir: cannot create directory '/var/lib/postgresql/18/docker': Permission denied`, or `FATAL: data directory … has wrong ownership` | Since Sprint 2 the image runs as `USER postgres` (uid 999, F-29), so the entrypoint cannot `chown` the data directory. A **host bind mount** (for example `./pgdata:/var/lib/postgresql`), or a volume first initialised by another uid, is not owned by uid 999. The default named volume `dbdata18` is not affected. | On the host: `sudo chown -R 999:999 ./pgdata` and start again, or switch back to the named volume in `docker-compose.yml`. Do **not** add `user: root` to compose (it reverts F-29). See [07 §6.2](07-secure-build-and-deploy.md#62-api). |
| API refuses to start: log names `APP_API_KEY` or `APP_API_KEY_NEXT` as too short | Key shorter than 32 characters (F-01a, since Sprint 2) | Section 5.1, "Upgrading from a 16–31 character key" |
| All clients get 401 right after a deploy | `APP_API_KEY` changed without the `APP_API_KEY_NEXT` overlap | Section 5.1: put the old key back as `APP_API_KEY` and the new one as `APP_API_KEY_NEXT`, or finish updating the clients |
| API with AI enabled refuses to start: log names `app.ai.embedding.provider` / `AI_EMBEDDING_PROVIDER`, or says `google-genai` needs an API key | Provider value other than `google-genai` or `openai`, or no `AI_API_KEY` / `AI_EMBEDDING_API_KEY` for the default Gemini embeddings | Section 1.1. For Ollama set `AI_EMBEDDING_PROVIDER=openai`. |
| `POST /api/ai/reindex` returns 503; Ask finds no houses | Embedding calls failing (key, quota, wrong provider for the server, model or dimensions) | Section 1.1 and the `AI reindex` WARN lines (section 2); fix, then reindex again |
| AI endpoints answer `503` with `"code": "AI_QUOTA_EXHAUSTED"` and `Retry-After: 60`; WARN lines contain `[provider quota exhausted]` | The provider (AI Studio or Vertex AI) answered HTTP 429 / `RESOURCE_EXHAUSTED`: per-minute or daily quota, or Vertex shared capacity | Wait a minute and retry; if it persists, IR-9. A re-index that hit it stopped early: run it again later |
| AI eval scorecard says **STOPPED: provider quota exhausted** (job fails with an "AI eval STOPPED" annotation) | Same quota error; the harness waited once for `Retry-After` and then stopped instead of failing every remaining case | Wait, raise `delay_ms` (for example `10000`), run fewer `types`, check Billing; then re-run. A trial account cannot request quota increases |
| API with `AI_PROVIDER=vertex` refuses to start: "needs GCP_PROJECT_ID" or "needs Google Application Default Credentials" | Project id missing, or no ADC on this machine | Set `GCP_PROJECT_ID`; locally run `gcloud auth application-default login`; on Cloud Run attach the service account; elsewhere set `GOOGLE_APPLICATION_CREDENTIALS` ([ai/vertex-setup.md](ai/vertex-setup.md) steps 11, 12) |
| Vertex AI calls fail with `HTTP 403 (SERVICE_DISABLED)` / `(IAM_PERMISSION_DENIED)` or `HTTP 404 (NOT_FOUND)` | Vertex AI API not enabled; service account lacks `roles/aiplatform.user`; model not offered in the location | Read the `setupHint` in the `503` body (also in the backend WARN line and the eval scorecard warnings): it names the variable to change (`GCP_LOCATION` for chat, `AI_VERTEX_EMBEDDING_LOCATION` for embeddings) and a location to try (`global`, or `us-central1` when already on `global`); a 401 hint points to the credentials. Then [ai/vertex-setup.md](ai/vertex-setup.md) steps 2, 3 and 8 |

## 10. Public repository, paid AI and Google Cloud trial (2026-09-22)

### 10.1 Public repository

The repository is **`Sriram-Codes-SW/doorprints`** (renamed from `house-hunt`; the old URL redirects) and is
**public** with an MIT `LICENSE` and `SECURITY.md`. Check these settings after any change to the repository:

| Setting | Expected |
|---|---|
| Ruleset on `main` | Active; blocks deletion and force pushes. Required status checks **off** until the PR flow and the `CI summary` check exist ([07](07-secure-build-and-deploy.md) §3.1). |
| Private vulnerability reporting | On (Settings → Security) |
| Secret scanning and push protection | On (free for public repositories) |
| Dependency graph | **On** (Settings → Security → Advanced Security → Dependency graph). Needed by the `security.yml` job `gradle-dependency-graph` (Sprint 3.5), whose first run on `8f583af` failed at submission, most likely because this was off ([07](07-secure-build-and-deploy.md) §1). After enabling it, re-run Security on `main` and check the job log says "Submitted …" |
| Actions | Approval required for workflows from outside contributors; no `pull_request_target` |
| Artifacts and logs | Readable by anyone: no personal data or secrets; DB dumps only `age`-encrypted (section 3) |

History re-check: the weekly `security.yml` gitleaks job scans the whole history. If it ever finds a real secret,
rotate that secret first (IR-2); rewriting public history does not help, because clones and forks may already hold
it.

### 10.2 Paid AI key with a hard cap

Cloud AI (owner and invited users only, [01](01-requirements.md) AI-013) runs on a paid key: Vertex AI or a Gemini API
key on a billing-enabled project (`AI_PROVIDER`, section 1.1; owner setup in [ai/vertex-setup.md](ai/vertex-setup.md)). Use a
**dedicated Google Cloud project for AI only**, so that the budget, the spend cap and the credential
([02](02-threat-model.md) T-I22) cover nothing else. The cap has three layers (AI-015):

1. **App:** per-user daily quota and a global daily cap on cloud requests and tokens (planned, Sprint 5); until then
   `AI_RATE_LIMIT_*` and the token caps (AI-009) apply. This is the only layer that stops spending *per user* and
   *per day*, and the only one under our control.
2. **Provider kill switch: spend cap budget** (Google Cloud Billing, **Preview**). A budget scoped to the AI project
   and to the one AI service in use (Vertex AI, shown as "Gemini Enterprise Agent Platform", or the Gemini API).
   When the month's cost passes 100 % of the target, Google blocks **new** usage of that service in that project
   (requests already running complete) until the owner lifts the cap. Limits to know:
   - one project and one service per budget; **monthly only** (from the 1st), so it caps the month, not the day;
   - measured on **gross cost before credits**, so it also trips during the free trial while the credit still
     pays the bill (section 10.3);
   - enforcement is not instant (it runs on cost data that lags behind usage); overage is billed, so set the
     target below the real limit;
   - Preview: terms and supported services can change. If it is unavailable for the account, the documented
     fallback is a programmatic budget notification (Pub/Sub) that triggers a function which disables billing on
     the AI project (this stops **all** billed services in that project, which is another reason to keep it
     AI-only).
   When it trips, every **new** cloud AI request fails for everyone, possibly in the middle of a user's session
   (for example between two steps of the planner), until the cap is lifted or the month rolls over (IR-9).
   Setting it up needs the Billing Account Administrator role (or the project-level spend cap permission).
3. **Budget alerts** on the same budget: email at 50 %, 90 % and 100 %. Alerts only notify; they do not stop
   billing on their own. During the Google Cloud trial, [ai/vertex-setup.md](ai/vertex-setup.md) step 7's alerts at
   $50, $150 and $250 (20/60/100 % of a $250 budget) are the trial-period form of these alerts: use those while the
   $300 credit lasts, and 50/90/100 % of the monthly budget afterwards.

Optional, only if the model exposes an adjustable quota: a lower requests-per-day or tokens-per-minute limit on the
Quotas page. Gemini on Vertex AI now uses pay-as-you-go dynamic shared quota, so such a per-project limit may not
exist; **verify when the project is set up** and do not count on it.

Spend cap budgets do **not** cover Cloud SQL (nor most other services). Staging Cloud SQL (section 10.3) needs its
own budget alert and a fixed tear-down date.

### 10.3 Google Cloud free trial ($300, 90 days)

Plan of the product owner (sprint candidates C-24..C-28 in [10](10-sprint-log.md) §8):

| Use | Notes |
|---|---|
| Vertex AI for cloud AI | If the trial credit applies to the chosen models; synthetic data in evals, real data only for the owner and invited users |
| Firebase Test Lab | Robo and instrumented runs on Indian-market Android devices; also a check of on-device AI (Gemini Nano) support. After the trial, Test Lab keeps working within the no-cost Spark plan daily quota (at the time of writing 5 physical-device and 10 virtual-device runs a day; check the current numbers in the Firebase docs) |
| Staging backend | Cloud Run + Cloud SQL for PostgreSQL (PostGIS, pgvector), synthetic data only, for the trial period ([07](07-secure-build-and-deploy.md) §6.5) |
| Speech-to-Text prototype | For the voice-notes candidate (C-16); synthetic recordings |

Trial dates (owner's account): the credit ends on **22 Dec 2026**; checklist in section 10.4.

Operations: on day 1, confirm that the trial credit covers the chosen Gemini models on Vertex AI; create the
AI project's spend cap budget (section 10.2; it counts cost **before** credits, so it can pause cloud AI while
credit is left) and a separate budget with alerts for the staging project, because spend cap budgets do **not**
cover Cloud SQL; write the trial end date and the Cloud SQL tear-down date (at the latest the trial end) in the
password manager entry; check credit and end date monthly (section 4). The trial ends **without an automatic
charge** unless the billing account is upgraded to a paid account; do not upgrade by accident. Before the end,
export anything worth keeping (staging has only synthetic data), delete the Cloud SQL instance and the Cloud Run
service, and move the production AI key to the paid setup of section 10.2 if cloud AI should continue.

### 10.4 Google Cloud trial end checklist (credit ends 22 Dec 2026)

Project `doorprints-ai` (AI), plus a staging project if one was created (section 10.3). When the trial ends, Google
stops the billed services until the billing account is upgraded; it does not charge automatically. With
`AI_PROVIDER=vertex` cloud AI would then fail (clients show the AI-unavailable message; on-device AI and every non-AI
feature keep working). Owner: the product owner. Step by step for the AI part: [ai/vertex-setup.md](ai/vertex-setup.md) step 14.

| When | Task | Done when |
|---|---|---|
| Monthly until then | Billing > Overview: credit left and days left; Billing > Reports: Vertex AI cost per SKU (and whether the credit is applied, vertex-setup step 10) | Numbers written in the password manager entry |
| **By about 15 Dec 2026** | **Export.** Anything worth keeping from Google Cloud: staging database (synthetic data; usually nothing to keep), eval reports beyond the 30-day artifact retention, billing reports as CSV. The Doorprints data itself lives in the production database and on the phones, not in Google Cloud: take a normal backup (section 3) anyway | Files stored outside Google Cloud |
| By about 15 Dec 2026 | **Decide** (product owner): (a) **back to AI Studio**: `AI_PROVIDER=aistudio`; real data only with a paid-tier Gemini API key ([01](01-requirements.md) PRV-022; the free tier is for synthetic evals only); or (b) **upgrade the billing account** to paid and keep Vertex AI, with the spend cap budget and alerts of section 10.2 created **before** the upgrade; or (c) turn cloud AI off (`APP_AI_ENABLED=false`) | Decision recorded in the sprint log ([10](10-sprint-log.md)) |
| **Before 22 Dec 2026** | Carry out the decision on every host (`AI_PROVIDER`, keys or credentials) and run `POST /api/ai/reindex` if the provider changed; run one small `ai-evals.yml` run (`types` = `extract`) on the provider now in use | Eval run green or STOPPED only by quota |
| Before 22 Dec 2026 | **Staging tear-down** (section 10.3, [07](07-secure-build-and-deploy.md) §6.5): delete the Cloud SQL instance and the Cloud Run service; spend cap budgets do not cover Cloud SQL | Nothing billable left in the staging project |
| If Vertex AI is dropped | Disable the Vertex AI API or shut down `doorprints-ai`; delete the GitHub secrets `GCP_WIF_PROVIDER` and `GCP_SA_EMAIL`; remove the Workload Identity principal binding; leave `ai-evals.yml` on `provider=aistudio` | No Google Cloud credential left that can call Vertex AI |
| If Vertex AI is kept | Spend cap budget and 50/90/100 % alerts active on the paid account (section 10.2); the trial-period $50/$150/$250 alerts replaced | Budget page shows the monthly target |
| Firebase Test Lab | Nothing to do: it keeps working within the no-cost daily quota (section 10.3) | – |

## 11. Upgrading a self-hosted server to the Doorprints names (2026-09-24)

On 2026-09-24 the last House Hunt names in the server were renamed ([03](03-design.md) ADR-24,
[CHANGELOG](../CHANGELOG.md)).
There is no public server yet, so this is documented rather than made backward compatible. **Breaking for a local
`docker compose` database; nothing else changes for a server configured through its environment.**

| What | Before | Now | What to do |
|---|---|---|---|
| Compose database, user, dev password default | `househunt` / `househunt` / `househunt` | `doorprints` / `doorprints` / `doorprints` | See the steps below for local data |
| Compose volume | `dbdata18` | `doorprints-pgdata18` | The old volume is not touched and not removed. Remove it yourself (`docker volume rm <project>_dbdata18`) once the data is moved or no longer needed |
| `application.yml` defaults of `DB_URL`, `DB_USER`, `DB_PASSWORD` | `…/househunt`, `househunt`, `househunt` | `…/doorprints`, `doorprints`, `doorprints` | Nothing if the server sets all three (every real deployment does: the defaults are for development only, [07](07-secure-build-and-deploy.md) §7). A server that relied on a default sets it explicitly, for example `DB_URL=jdbc:postgresql://<host>:5432/househunt`, and keeps its database as it is |
| Spring properties and environment variables | `app.*`, `APP_*`, `AI_*`, `DB_*`, … | unchanged | Nothing: no property prefix or variable was ever named after the product |
| MCP tool | `askHouseHunt` | `askDoorprints` | An MCP client picks the new name from `tools/list` on its next connection; a saved prompt or allow-list that names the old tool is updated by hand ([ai/ai-design.md](ai/ai-design.md) section 12) |
| Image tags in the examples | `house-hunt-api`, `house-hunt-db` | `doorprints-api`, `doorprints-db` | Only a local tag; rebuild or retag |
| Maven coordinates, Java packages | `com.househunt:house-hunt-api`, `com.househunt.*` | `app.doorprints:doorprints-api`, `app.doorprints.server.*` | Nothing (the image copies `target/*.jar`); log filters that match on the logger name `com.househunt` change to `app.doorprints.server` |
| Flyway migrations | V1-V3 | unchanged, not edited | Nothing: their checksums stay the same |

**Carry a local compose database over: mandatory** for a server that phones or the web app have synced with. Do
not start the new, empty database in its place. The clients do not send everything again to an empty server: each
pushes only the rows changed on it since its last sync, and pulls only changes after the cursor it stored for that
server (the highest `syncVersion` it has seen), which is never reset while the server's address stays the same. A new
database starts `sync_seq` at 1, so the old houses are not on the server, and every device silently skips the other
devices' new changes until the sequence passes its cursor: no error is shown. The dump carries the rows and
`sync_seq`'s position over, so the stored cursors stay valid. Clients from 2026-09-24 on detect a reset server and
send everything again (§11.1), but older app versions do not, and a dump keeps what no device holds any more. Only a
database that no device has ever synced with can be left behind.

```bash
# 1. With the checkout still on the old compose file (before pulling this change): dump the old database.
docker compose up -d --wait db
docker compose exec -T db pg_dump -Fc --no-owner --no-privileges -U househunt -d househunt > househunt.dump
docker compose exec -T db psql -U househunt -d househunt -c 'select count(*) from house'   # note the number
docker compose down
# 2. Pull the change and start only the new, empty database (it creates the doorprints user and database).
#    Not the API yet: Flyway would create empty tables and the restore would clash with them.
docker compose up -d --wait db
# 3. Restore. Flyway's history table and sync_seq's position come along, so the API sees the schema as migrated.
#    pg_restore prints "already exists" errors for the PostGIS extensions and the topology and tiger objects the
#    image created (and possibly "duplicate key" errors on tiger.* loader tables), and exits non-zero: expected
#    (a script with `set -e` stops here; run it on its own line). Step 4 is the real check.
docker compose exec -T db pg_restore --no-owner --no-privileges -U doorprints -d doorprints < househunt.dump
# 4. Check the rows arrived: the same number as in step 1.
docker compose exec db psql -U doorprints -d doorprints -c 'select count(*) from house'
# 5. Start the API and check it.
docker compose up -d api
curl -H "X-API-Key: $APP_API_KEY" http://127.0.0.1:8080/api/stats
```

Any error in step 3 other than "already exists" or a duplicate key in a `tiger.*` table (for example a missing role or a failed `COPY`) means the restore is
incomplete: fix it before step 5. If the API was started on the new database before the restore, stop everything
(`docker compose down`), remove the new volume (`docker volume rm <project>_doorprints-pgdata18`) and start again at
step 2.

If the old compose file is gone already, the old volume can still be read with a throwaway container. Build the
image first (`docker compose build db`, which tags `doorprints-db:pg18`), then:

```bash
docker run -d --name old-db -v <project>_dbdata18:/var/lib/postgresql -e POSTGRES_PASSWORD=x doorprints-db:pg18
until docker exec old-db pg_isready -U househunt -d househunt; do sleep 1; done
docker exec old-db pg_dump -Fc --no-owner --no-privileges -U househunt -d househunt > househunt.dump
docker exec old-db psql -U househunt -d househunt -c 'select count(*) from house'   # note the number
docker rm -f old-db
```

and continue with steps 2 to 5 above.

### 11.1 Clients detect a reset server (2026-09-24)

`GET /api/stats` also returns **`maxSyncVersion`**: the highest sync version the server has handed out, read from
`sync_seq` (0 before the first write; [03](03-design.md) §9). It never goes back on a healthy server, also after
`DELETE /api/data`, which hard-deletes rows while the sequence carries on. Check it after a restore:

```bash
curl -s -H "X-API-Key: $APP_API_KEY" http://127.0.0.1:8080/api/stats   # ... "maxSyncVersion": <n>
```

Android and the web read it before each sync once they have synced ([03](03-design.md) §10.1, S4b-BL-20). A value
below one of their stored cursors means the database was replaced: a new, empty database, or a restore from a dump
older than their last sync. They then mark every house, visit and stored photo for upload, set their cursors to 0,
send everything, download everything and say so (web: an announcement and a line on *Your data*; Android: the server
status line in Settings). Nothing on a device is lost; last-write-wins still decides each row. An older server
without the field is detected at the first accepted push instead.

| Situation | What happens |
|---|---|
| Restore from a current dump (§11 steps) | `sync_seq` comes along; `maxSyncVersion` is at or above every cursor: nothing happens |
| Restore from an older dump, or a new empty database | Each device re-sends all its data on its next sync (photos on Wi-Fi if that setting is on); expect a burst of uploads and more disk use |
| `DELETE /api/data` | Not a reset (the sequence carries on); devices keep their copies and do not re-send them |
| An app version before 2026-09-24 | No detection: it keeps pulling from its old cursors; the §11 dump and restore stays mandatory |

Watch the upload burst with the monitoring of §2; a device that syncs rarely re-sends only when it next syncs.

