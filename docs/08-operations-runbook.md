# 08: Operations runbook

| Field | Value |
|---|---|
| Document | Operations runbook |
| Version | 0.2 |
| Date | 2026-09-22 |
| Author | Claude (Cowork) |
| Status | Draft |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) | First version: monitoring, backups/restore, key rotation, incident response, data export/deletion, release checklist. |
| 0.2 | 2026-09-22 | Claude (Cowork) | Wave 2: export and delete-all now use the API (`GET /api/export`, `DELETE /api/data`), automatic 90-day tombstone purge, new log lines for auth failures and rate limits, CI-based dependency scanning, key rotation steps for the masked Android key field. |

Related: [Build and deploy](07-secure-build-and-deploy.md) · [Threat model](02-threat-model.md) · [Test plan](06-test-plan.md)

---

## 1. Service summary

| Item | Value |
|---|---|
| Components | API (Render/Koyeb/Oracle VM), Postgres+PostGIS (Supabase/Neon), web (Cloudflare Pages), Android app (sideloaded) |
| Health | `GET https://<api>/actuator/health` → `{"status":"UP"}` (public, includes the DB check) |
| Targets | RPO ≤ 24 h (nightly backup; the phone also holds a full offline copy), RTO ≤ 4 h (NFR-008) |
| On-call | The owner (single user). Keep this runbook and the password manager entry "House Hunt ops" up to date. |

## 2. Monitoring

| What | How (free) | Threshold / action |
|---|---|---|
| API up | UptimeRobot free HTTP monitor on `/actuator/health` (5-min checks), or `keepalive.yml` in GitHub Actions | 2 consecutive failures → check the host dashboard. A sleeping free instance answers after a cold start, so don't alert on the first slow response. |
| DB alive / not paused | `keepalive.yml` every 3 days (health hits the DB) | Supabase "paused" email → resume in the dashboard |
| DB size | Monthly: `select pg_size_pretty(pg_database_size(current_database()));` and `select pg_size_pretty(pg_total_relation_size('photo'));` | > 60% of the free quota → act on F-06 (cap photos / move to object storage) |
| Sync health | Android Settings shows the last sync time and message | Last sync older than 24 h while online → Sync now, check the key/URL |
| Auth failures | Host logs, WARN lines `auth.fail reason=wrong-key client=<hash> …`, `auth.throttled …` (429 after 10 wrong keys/min per address) and `auth.reject reason=non-canonical-path …` | Sudden spike of `auth.fail`/`auth.throttled` → IR-2 (possible leaked or guessed key). `auth.reject` bursts are scanners probing path tricks (F-20): no action unless combined with 200s. |
| Rate limiting | 429 responses in the host's request log | Many 429 from one address → a runaway client or a flood; consider a Cloudflare rule |
| Tombstone purge | INFO line `privacy.purge tombstones: houses=… visits=… photos=…` daily at 03:30 | None expected; absent for weeks with deletes happening → check the scheduler |
| Dependencies | Dependabot PRs, `security.yml` (Trivy, npm audit, Semgrep, gitleaks) on every push and weekly | Critical/High → patch within 7 days |
| Backups | `backup.yml` run status (GitHub notifies on failure) | Failed 2 nights in a row → fix the same day |
| AI usage (when enabled) | Usage counters (D8) / provider console | ≥ 80% of the daily free quota → check for abuse (IR-6) |

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
          f="househunt-$(date -u +%F).dump.age"
          pg_dump -Fc --no-owner --no-privileges --schema=public "$BACKUP_DB_URL" | age -r "$AGE_RECIPIENT" -o "$f"
          ls -l "$f"
          echo "FILE=$f" >> "$GITHUB_ENV"
      - uses: actions/upload-artifact@<full-sha>
        with: { name: db-backup, path: '${{ env.FILE }}', retention-days: 30 }
```

Notes:

- Use `--schema=househunt` if you moved the tables into their own schema (07 section 6.1). On Supabase, don't dump its internal schemas.
- Photos are in the DB (ADR-08), so dumps grow with them. Watch the artifact storage quota (500 MB on private Free repos). If it gets close, keep 7 days in artifacts and send weekly copies to R2.
- Never upload **unencrypted** dumps (T-I11). Don't print row data in logs.
- Extra copies of the data: the Android Room DB (full offline copy, not a backup of the server) and a manual export (section 6).

### 3.1 Restore (tested quarterly, TC-O-01)

```bash
# 1. Fresh target DB with PostGIS (local docker or a new free project)
docker run -d --name restore -e POSTGRES_PASSWORD=x -p 5433:5432 postgis/postgis:17-3.5
psql "postgresql://postgres:x@localhost:5433/postgres" -c 'CREATE EXTENSION IF NOT EXISTS postgis;'
# 2. Decrypt and restore
age -d -i ~/secure/househunt-backup.agekey househunt-YYYY-MM-DD.dump.age \
  | pg_restore --no-owner --no-privileges -d "postgresql://postgres:x@localhost:5433/postgres"
# 3. Check
psql "postgresql://postgres:x@localhost:5433/postgres" -c 'select count(*) from house; select max(sync_version) from house; select last_value from sync_seq;'
# 4. Point DB_URL at the restored DB (for a real recovery) and redeploy; health must be UP
```

After restoring into production, make sure `sync_seq.last_value` ≥ `max(sync_version)` across `house` and `visit`. Otherwise clients will not pull new changes. Fix it with `select setval('sync_seq', (select greatest(max(h.sync_version), max(v.sync_version)) from house h, visit v));`. Then tell each client to **reset its cursor** (change the server URL and change it back, which does a full download). Any phone edits made since the backup will re-sync because they are still marked dirty. Changes that were already synced but made after the backup are lost unless some device still has them.

## 4. Routine maintenance calendar

| Frequency | Task |
|---|---|
| Weekly | Merge Dependabot PRs after CI passes. Read the ZAP and Dependency-Check reports. |
| Monthly | DB size check. Check the backup run history. Update the Android app if a release exists. |
| Quarterly | Restore drill (TC-O-01). Rebuild the base image. Check free-tier terms (Render, Supabase/Neon, Pages, OpenFreeMap, Nominatim, LLM). |
| 6-monthly | Rotate `APP_API_KEY` (section 5.1). Review the threat model findings. |
| Yearly | Rotate DB, backup-role and CI secrets. Review the docs (bump versions). Check the keystore backup is readable. |

## 5. Key and secret rotation

### 5.1 API key (`APP_API_KEY`)

Today there is one key, so rotation has a short window in which clients get 401 until they are updated. Sync retries later, and the phone stays fully usable offline.

| Step | Action |
|---|---|
| 1 | Generate: `openssl rand -base64 32`. Save it in the password manager. |
| 2 | Update `APP_API_KEY` in the host's secret settings (Render/Koyeb) or the VM `.env`. Redeploy/restart. |
| 3 | Check: `curl -s -o /dev/null -w '%{http_code}' -H "X-API-Key: <old>" https://<api>/api/stats` → `401`. With the new key → `200`. |
| 4 | Web: Connect page → paste the new key → Test → Save (on each browser). |
| 5 | Android: Settings → paste the new key (the field is empty; the hint shows the last 4 characters of the saved key) → Save and test → Sync now. Unsynced local changes are kept and pushed. The key is stored encrypted with a Keystore key. |
| 6 | Record the date in the password manager entry |

Planned zero-downtime flow (SEC-017): set `APP_API_KEY_NEXT` → update clients → move NEXT to `APP_API_KEY` → remove NEXT.

### 5.2 Other secrets

| Secret | Procedure |
|---|---|
| DB password | Reset in the provider dashboard → update `DB_PASSWORD` (and `BACKUP_DB_URL`) → redeploy → check health |
| LLM provider key | Revoke in the provider console → new key in the host env → redeploy. See [ai/](ai/). |
| Deploy hook / SSH key | Regenerate in Render / replace the `authorized_keys` line → update the GitHub environment secret |
| GitHub PAT | Revoke at github.com/settings/tokens. Create a fine-grained one with expiry ≤ 90 days only if needed. |
| Age backup key | Create a new key pair → update `BACKUP_AGE_RECIPIENT`. Keep the old private key until the old backups expire (30 days). |
| Android signing key | **Do not rotate** unless it is compromised (see IR-7) |

## 6. Data export and deletion (privacy operations)

Required by PRV-004/PRV-005. The API implements both (FR-031/FR-032); app buttons are backlog, so use `curl`.

### 6.1 Export

```bash
API=https://<api>; KEY=<key>
curl -sf -H "X-API-Key: $KEY" "$API/api/export" -o export.json      # houses, visits, photo metadata
mkdir -p photos
for p in $(jq -r '.photos[].photo.id' export.json); do
  curl -sf -H "X-API-Key: $KEY" "$API/api/photos/$p" -o "photos/$p.jpg"
done
zip -r house-hunt-export-$(date +%F).zip export.json photos   # store encrypted: it holds location history
```

A full export for migration is the encrypted `pg_dump` from section 3.

### 6.2 Deletion

| Scope | Procedure (SQL on the prod DB, admin role, **take a backup first**) |
|---|---|
| One house | Delete it in the app (or `DELETE /api/houses/<id>`). The server blanks its content, deletes its photo bytes, unlinks its visits and keeps a content-free tombstone so other devices delete their copy; the tombstone is purged automatically after 90 days. |
| Purge tombstones | Automatic, daily at 03:30 (`DataService.purgeTombstones`, `TOMBSTONE_RETENTION_DAYS`, default 90). A device that has not synced for longer than that keeps its local copy of rows deleted elsewhere. |
| Old visits (retention, PRV-006) | `delete from visit where arrived_at < now() - interval '6 months';` (devices keep their copies until the app's data is cleared) |
| Everything (end of hunt) | 1) Export if wanted. 2) `curl -X DELETE -H "X-API-Key: $KEY" -H "X-Confirm-Delete: DELETE-ALL-MY-DATA" "$API/api/data"` (hard-deletes houses, visits, photos and AI index rows; 428 without the exact header), or delete the DB project. 3) Delete the API service. 4) Delete the backup artifacts / R2 objects. 5) Android: Settings → Apps → House Hunt → Storage → Clear storage, then uninstall. 6) Web: Connect → Disconnect, and clear site data. 7) If AI was used: delete the embeddings (same DB) and check the LLM provider's retention/deletion options. |

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
| Contain | Rotate immediately (section 5.1). Revocation is the real fix. Removing the key from history does not undo the leak. |
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
4. Fix the data, `setval` the sequence if you restored, then re-enable sync and reset cursors.

### IR-5 Free-tier outage, suspension or policy change

The phone keeps working offline. To move provider: restore the latest dump to another provider (section 3.1) → update `DB_URL` → redeploy. The API image runs on any Docker host. The web is static and can go to Netlify/GitHub Pages (`npm run build:pages`). Tiles: change `MAP_STYLE_URL` (web `shared/map-style.ts`, Android `MAP_STYLE_URL`) if OpenFreeMap is unavailable.

### IR-6 AI abuse or prompt-injection incident (when AI is enabled)

Set the AI flag to off (AI-001) and redeploy. Rotate the LLM key. Look at the offending input (listing/note). Add it to the injection test suite (TC-AI-04). Review whether any tool was called and what it returned. Re-enable only after the eval suite passes.

### IR-7 Signing key compromise or malicious APK circulating

Publish a warning in the repo README with the correct certificate fingerprint. Create a new keystore. Tell users (yourself/family) to uninstall and reinstall. **Sync first**, because uninstalling wipes local data. Rotate the API key.

## 8. Release checklist

- [ ] Version bumped: `android/app/build.gradle.kts` (`versionCode` +1, `versionName`), `backend/pom.xml`, `web/package.json`.
- [ ] New Flyway migration (if any) is additive, reviewed and tested on a restored copy of prod.
- [ ] CI green on `main`: tests, Semgrep, gitleaks, Trivy, SCA. No unaccepted Critical/High.
- [ ] Test plan exit criteria met ([06 section 11](06-test-plan.md#11-entry-and-exit-criteria)), including the field walk test when location code changed.
- [ ] Threat model findings: fixed ones marked, new risks recorded. Docs versions and change logs updated.
- [ ] Fresh backup taken (manual `backup.yml` run) within 24 h.
- [ ] Deploy the API by image digest → health UP → smoke test (stats, create/delete a test house, then purge it).
- [ ] Web deployed (Pages) → Connect and Map load. No CSP errors in the console.
- [ ] Release APK built by `release.yml`, `apksigner verify` fingerprint matches, SHA-256 published.
- [ ] Install on the phone **after syncing**. Settings show the right server. Sync OK. Hunt mode starts and stops.
- [ ] If AI changed: eval results attached and meet the thresholds. Flag default stays off unless approved.
- [ ] Release notes: features, fixes, security fixes, migrations, known issues.
