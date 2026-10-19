# Release security checklist: run record of 2026-10-19 (the v0.1.0 candidate)

> Session record (not a numbered SSDLC document). It is the filled run of [13](../13-release-security-checklist.md) section 3
> as far as it can be run from a cloud sandbox with no device, no Google console or account and no artifact download.
> Nothing here approves a release: the decision line at the end is the owner's.

| Field | Value |
|---|---|
| Document | Run record of the release security checklist ([13](../13-release-security-checklist.md)) on the `v0.1.0` candidate |
| Date | 2026-10-19 |
| Author | Claude (Code), engineer |
| Status | Draft: run, not signed off. Parts that need a device, console or account are marked OWNER |

## 1. The release record (13 section 6)

```
Release: 0.1.0 (android/app/build.gradle.kts versionName)  Commit: 11072090eb9de0cb5958d8be4e33de558b69192e (origin/main, 2026-10-20 02:58 IST)
Tag / draft release: v0.1.0 exists as a DRAFT but is built from 7095fc7b (2026-10-14), NOT from this commit: see P-3
Date: 2026-10-19  Run by: Claude (Code) session
Kind: Play release (the v0.1.0 draft) and a web deploy (the live site was deployed from this commit at 21:31 UTC)
A automated gate   2 pass, 1 not run, 3 owner   B web   pass except B5 (documentation finding F-B5)
C Android          NOT RUN here (no APK, no device)   D MobSF dynamic   OWNER   E server   n/a
F LLM Top 10       NOT RUN on this commit (no ai-evals run for it; latest green run is of 06034893)   G privacy   pass (G2 OWNER)
H boundaries       web look done, H1/H2 OWNER, H3 pass   J kinds and spaces   pass for the website, phones OWNER
I Google Drive     one row pass (I2), the rest OWNER
Findings: F-B5 (Low, docs), G-1 (process), P-3 (process), O-1 (observation)   Accepted risks: none proposed
Pentest: not run (section 5 is a separate two-day exercise)
Decision: OWNER (do not ship until the OWNER list in section 2 is done)
```

## 2. Summary

**Release commit:** `11072090eb9de0cb5958d8be4e33de558b69192e` (`origin/main` when this run started; the Web deploy of it is live at https://doorprints.web.app).

### 2.1 Counts (76 rows: A1-A6, B1-B7, C1-C7, D1-D4, E1-E6, F1-F11c, G1-G4, I1-I18, H1-H3, J1-J8; F11 is split in three)

| Status | Rows |
|---|---|

| PASS | 19 |
| FAIL | 1 |
| NOT RUN | 13 |
| OWNER | 34 |
| N/A | 9 |

| Part | PASS | FAIL | NOT RUN | OWNER | N/A |
|---|---|---|---|---|---|
| A | 2 | 0 | 1 | 3 | 0 |
| B | 6 | 1 | 0 | 0 | 0 |
| C | 0 | 0 | 2 | 5 | 0 |
| D | 0 | 0 | 0 | 4 | 0 |
| E | 0 | 0 | 0 | 0 | 6 |
| F | 4 | 0 | 7 | 2 | 0 |
| G | 2 | 0 | 0 | 1 | 1 |
| I | 1 | 0 | 0 | 16 | 1 |
| H | 1 | 0 | 0 | 2 | 0 |
| J | 3 | 0 | 3 | 1 | 1 |

**Meaning.** PASS = checked here with the evidence in section 3. FAIL = checked here and differs from *Pass*. NOT RUN = could have been run but was not, or only partly, reason given. OWNER = needs a device, console, account, artifact download or the owner's judgement; code-level evidence is given where there is some, but it does not replace the check. N/A = does not apply to this release, reason given. Rows are PASS only for what the row asks; where a row has a part I could not do, the evidence says so.

### 2.2 FAIL and findings

| Id | Row | Severity | Finding | Recommended fix |
|---|---|---|---|---|
| F-B5 | B5 | Low (documentation, no data exposure) | The storage audit's expected-values table (`web/README.md:496-512`) is out of date. Live site: IndexedDB `doorprints` is version 4 with 7 stores (`houses`, `photos`, `records`, `saved_walks`, `settings`, `trace_points`, `visits`), README says 4 stores, all empty; a second cache `doorprints-offline-maps-v1` appears (README: exactly one cache). The audit says *anything not in this table is a finding*, so the audit as written cannot pass. Behaviour itself is right: no key outside session storage, no API data in any cache, *Remove all data* leaves 0 houses, no caches, no `localStorage`/`sessionStorage` keys, no service worker, no cookies. | Update the table to match `docs/16-architecture-overview.md:116` (stores and what stays after removal: the seeded `records` and one `settings` row), add `doorprints-offline-maps-v1`, and give each new `doorprints.*` key its row. Docs only; file as an S4b-BL item. Also finish the two B5 steps not done here (the *Remember on this device* move, private-window pass 2). |

Gaps and preconditions found while running (not rows of the list, but they block or weaken the gate):

| Id | Where | Finding | Recommended fix |
|---|---|---|---|
| G-1 | A6 | Dependabot alerts are **disabled** for the repository (`gh api .../dependabot/alerts` -> `403 Dependabot alerts are disabled for this repository`), so A6 *no open High or Critical alert* has nothing to read; code scanning alerts are also not readable by the session token (A5). | Owner: enable Dependabot alerts (free) in Settings > Code security, then read A5 and A6 in the web UI; or amend A6 to name `npm audit` and Trivy as the evidence while alerts stay off. |
| P-3 | section 2 of 13, *a release APK built from that commit* | A **draft release `v0.1.0` and the annotated tag `v0.1.0` (object `499a0fd5`) already exist and point to `7095fc7b` (2026-10-14)**; main has moved by hundreds of files since (voice, kinds refactor, photo restore). The draft's APK is therefore not the candidate this run checked. A dry run on `11072090` is green (C1), but the dry run publishes nothing. | Before publishing: owner deletes the stale draft release and tag and pushes `v0.1.0` again at the release commit (the `release.yml` guard requires the tag to equal `versionName` and the commit to be on main), or bumps `versionName` and tags `v0.1.1`. Then re-run C1/C2 on that APK. |
| O-1 | G1 | `web/public/privacy.html` says nothing about voice input audio (it goes to the AI provider the person chose), though voice ships on the website and both phones. The in-app disclosure covers it, which is what G4 asks for. | Add one sentence (four languages) at the next privacy-page update; docs/13 G4 already plans it for the Play form. |
| O-2 | B1 | HSTS on the live site is Firebase's (`max-age=31556926; includeSubDomains; preload`), not `web/firebase.json`'s value. Stronger, so no action; the checklist's wording *as in firebase.json* is loose for this header only. | None. |
| M-1 | the candidate itself | `origin/main` moved twice while this run was in progress: **#382** (`f7846a18`: Android Gradle dependency versions, `android/gradle/libs.versions.toml`) and **#383** `e988f985` (docs; the photo wording in `web/public/privacy.html`, the guide, docs 02/03/04/10/13 v0.22/15 now says *30 days, and for longer only while a backup you kept still holds it*). Everything in this record is for `11072090`. G1's photo clause was read against the v0.21 wording that was live then; a release commit that includes #383 needs G1 re-read, and one that includes #382 needs the Android jobs (A1, C1, C2) on it. | Decide the release commit once (P-3), then re-run only what the later commits touch. |

No High or Critical finding was found. Nothing was fixed in this change; the one document edit is this record and its index row.

### 2.3 What the owner must do before publishing the v0.1.0 draft (checklist order)

1. **P-3:** make the draft a build of the release commit (re-tag, see above); check its `.sha256` and signer fingerprint against `README.md:84` (**C2**).
2. **A2:** download `backend-zap-api-scan` of the Backend run [38982781854](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781854) and triage `zap.html`. **A3:** skim `android-mobsf-report` of [38982781941](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781941). **A4:** read the *Licence check* step's warnings in the Security run [39327752065](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39327752065). **A5/A6:** read Code scanning and Dependabot in the web UI after enabling alerts (**G-1**).
3. **B5:** update the README table (**F-B5**); optionally redo the two skipped B5 steps by hand.
4. **C1** on the release APK (`aapt2 dump xmltree`, merged manifest, permission list); **C3-C7** on a device (logcat, deep links, permissions at use with the microphone after the tap, saved files, captive portal if sync changed).
5. **D1-D4:** MobSF dynamic run on an emulator.
6. **F1-F3, F5, F7-F9, J6:** dispatch **AI evals** (`ai-evals.yml`) on `main` (needs the repository AI secret) and read the scorecard (`injectionResistance` 1.0, model named, leak markers, thresholds, kind cases); **F10:** the provider's spend cap and a 429 at the quota; **F11c:** the iPhone and Sarvam rows (MT-104, MT-105, MT-107, MT-109, MT-110) and Android's MT-82..MT-87.
7. **G2:** the Play data-safety form answers (if the Play release goes ahead); consider **O-1**.
8. **I1, I3:** Google Auth Platform (scope `drive.file` only; the Picker key restricted to `https://doorprints.web.app/*` and the Picker API); **I4-I14, I16-I18:** the Drive rows on two phones and the test account (TC-M-48..53, TC-M-90, MT-122), including the consent screen linking the privacy page (I17).
9. **H1:** re-read docs/03 section 11.1 against DST's guidelines and negative list; **H2:** TC-M-25 on an Android device (and the formal comparison on the website).
10. **J4, J5:** with two kinds shown on the website, *Delete all in this space*, *Recently deleted* and the *Remove all data* dialog with both counts; **J8:** the phones' fresh-install behaviour (MT-96, MT-112, MT-113).
11. Then record *ship* or *do not ship* in docs/10 (13 section 4) and publish; the deep pentest (13 section 5) is separate and still owed before a Play launch and Drive go-live.

## 3. Preconditions (13 section 2)

| Precondition | Status | Evidence |
|---|---|---|
| Every workflow on the release commit is green | PASS | A1 |
| Artifacts at hand (ZAP, Trivy image, MobSF, licence log) | NOT RUN | The artifacts exist (`backend-zap-api-scan`, `backend-openapi`, `backend-sbom-cyclonedx`, `android-mobsf-report`, `android-reports`, `doorprints-release-apk`, none expired) but the session proxy cannot download them (A2, A3, A4) |
| A release APK built from that commit | FAIL (P-3) | The draft `v0.1.0` is of `7095fc7b`; a dry run on `11072090` is green but only uploads a workflow artifact ([https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39330194280](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39330194280)) |
| Tools: Docker, emulator, `adb`, browser, `curl`, `testssl.sh` | partly | Only a browser (Playwright 1.56.1 with the sandbox's Chromium) and `curl` were available |
| A test house list with the payloads | done | B2/B3: payload house, contact house, plain house; thrown away with the browser profile |

## 4. The list, row by row


### A. Read the automated gate

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| A1 | Every §11.1 check ran on the release commit | **PASS** | `gh api repos/.../commits/11072090.../check-runs` lists 15 check runs, all `success` except 2 `skipped` by design: Web [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39327752054](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39327752054) (ng test + ng build, PWA files, Phone layout gate, Firebase Hosting config, Deploy to Firebase Hosting), Security [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39327752065](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39327752065) (Semgrep, gitleaks, npm audit, Trivy, Android dependency graph), CodeQL [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39327752051](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39327752051) (java-kotlin, javascript-typescript). Skipped: *OWASP ZAP baseline (manual)* (`workflow_dispatch` input) and *Commit author emails* (`security.yml:130`: pull requests and non-main pushes only). **Backend, Android, Android emulator, Shared iOS and Pages did not run on `11072090`** because of their path filters: `git diff --name-only a00f3433 11072090` touches `web/` only (5 files, none under a filter). Their runs on the last commit that did touch their paths, `a00f3433` (content-identical for those areas), are all green: Backend [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781854](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781854), Android [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781941](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781941), Android emulator [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781723](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781723) (API 26, 34, 36; the *Firebase Test Lab* job is skipped: owner setup of docs/07 §7.2 not done), Shared iOS [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781910](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781910), Pages [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781711](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781711). |
| A2 | ZAP Medium and Low alerts (TC-S-04) | **OWNER** | The scan ran and passed its blocking threshold: Backend job *Build container image* ([job](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781854/job/117247803151)), steps *OWASP ZAP API scan (blocking on High)* and *Upload the ZAP report* `success`; artifact `backend-zap-api-scan` exists (not expired). **The artifact cannot be downloaded through the session proxy**, so the Medium/Low triage of `zap.html` was not done. Owner: download `backend-zap-api-scan` from the Backend run above and triage `zap.html`. |
| A3 | MobSF's accepted Highs still hold (TC-S-06) | **PASS** | `android/ci/mobsf-accepted.json` has 2 entries. Android job *MobSF static scan of the release APK* `success` ([job](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781941/job/117247250146); the step fails on any unaccepted High, per the file's `_comment`), so no new High. Reasons re-read against the tree: (1) *Missing Code Signing certificate*: CI scans the unsigned build; the signed build is `release.yml`, dry run green (C2). (2) *cleartext domain config*: `android/app/src/main/res/xml/network_security_config.xml` has `base-config cleartextTrafficPermitted="false"`, system CAs only, and cleartext for exactly `localhost`, `127.0.0.1`, `10.0.2.2`: still true. The `android-mobsf-report` artifact itself was not downloaded (proxy). |
| A4 | Licence warnings | **NOT RUN** | Security job *Dependencies and IaC (Trivy)* ([job](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39327752065/job/118317863050)) step 5 *Licence check (runtime dependencies, AGPL-3.0-only compatible)* is `success` (it is blocking, so no incompatible licence); `.github/licence-exceptions.json` holds 2 reviewed exceptions (`org.antlr:ST4`, `org.antlr:antlr-runtime`). **The step's warning lines were not read**: the log is dominated by Trivy lines and the log download redirects to a host the proxy refuses. Owner: open the step in the run and confirm each warning has a decision or an exception. |
| A5 | Code scanning: no open error/high alert | **OWNER** | `gh api repos/Sriram-Codes-SW/doorprints/code-scanning/alerts?state=open` -> `403 Resource not accessible by integration`. CodeQL itself ran green on the commit (A1). Owner: Security > Code scanning, filter `main`. |
| A6 | Dependabot: no open High or Critical alert | **OWNER** | `gh api repos/Sriram-Codes-SW/doorprints/dependabot/alerts?state=open` -> `403 Dependabot alerts are disabled for this repository`. **Gate gap (see finding G-1):** with alerts disabled the row cannot be satisfied as written. Related automated evidence, not a substitute: `npm audit (web)` and Trivy sbom/fs steps green (A1). |

### B. Web app, WSTG-lite (live site, a throwaway Chromium profile per run)

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| B1 | Headers | **PASS** | `curl -sSI https://doorprints.web.app/` (HTTP/2 200; `last-modified: Mon, 19 Oct 2026 21:31:50 GMT`, i.e. the deploy of the release commit). Compared header by header with `web/firebase.json` (python diff): `content-security-policy` identical (incl. `frame-ancestors 'none'`, `object-src 'none'`), `x-frame-options: DENY`, `x-content-type-options: nosniff`, `referrer-policy: strict-origin-when-cross-origin`, `cross-origin-opener-policy: same-origin-allow-popups`, `cache-control: no-cache`, and `permissions-policy: geolocation=(self), camera=(self), microphone=(self), payment=(), usb=()` (no `*`). One difference, not a failure: `strict-transport-security: max-age=31556926; includeSubDomains; preload` (Firebase's own value, stronger than `max-age=31536000; includeSubDomains` in `web/firebase.json`). |
| B2 | Stored XSS | **PASS** | Playwright (`/tmp` script, live site, fresh context, `page.on('dialog')` recording any dialog). Added a house whose name, address, locality and notes are `<img src=x onerror=alert(1)>`, plus a contact house (`Test Owner`, `90000 00001`) and a plain house. Map/list `/`, `/compare` and the house page `/houses/<id>`: the payload shows as text (`innerText` contains it) and `img[src="x"]` count is 0; no dialog on any page. *Save a copy* as HTML (`Doorprints-copy-2026-10-19.html`, 5 472 bytes) opened from `file://`: raw file has `&lt;img src=x onerror=alert(1)&gt;` and no raw `<img src=x onerror`, 0 injected `img`, rendered as text, 0 requests from the copy, a CSP `<meta>` present, no dialog. Markdown and CSV copies also downloaded. The *PDF* (print) path builds the same HTML in a blob iframe: its document has 0 injected `img` and the payload as text (it was driven; the OS print dialog itself is not observable headless). |
| B3 | Dangerous links | **PASS** | House with listing URL `javascript:alert(1)`: the value is kept as plain input text (`#house-listing`), the page has 0 `<a>` whose `href` contains `javascript`/`alert`, no link to the listing is rendered, and no dialog fired. |
| B4 | Malicious backup | **PASS** | Built with Python `zipfile` from `docs/schemas/backup-sample.json` (3 houses) and a manifest with the right SHA-256, then mutated; each file given to *Import a backup* on `/data`; houses counted in IndexedDB before and after (3 and 3 throughout). Baseline `.zip` and bare `.json`: preview shown (so the fixtures are valid). Refused with a message, nothing written: `../../evil.txt` and `photos/../../evil.jpg` and `/etc/evil` -> *it holds an unsafe file name*; a 40 MB zero photo (43 KB zipped, ratio ~1000) -> *it is too large*; manifest hash `000...` -> *it is damaged: the checksum does not match*; 5 001 entries -> *it holds too many files*; `doorprints-backup/99` (zip and bare json) -> *made by a newer version*; `{"hello":1}` -> *not a Doorprints backup*. Code: `web/src/app/export/backup-reader.ts:75-80,193-197`. |
| B5 | Client storage (storage audit, `web/README.md`) | **FAIL** | Run: Pass 1 steps 1-4 (clean start, three test houses, an unsaved draft, a made-up API key kept in the session, *Remove all data*, re-read). **The app behaves as the audit wants; the audit's expected-values table is stale (finding F-B5, Low, docs).** Behaviour: clean start has one cache `doorprints-shell-48e0cf98fbe8d014/` (62 entries, host `doorprints.web.app` only, no `/api/`), `localStorage` only `doorprints.mapView`, session storage empty, no cookies, one service worker (scope `/`, active). With a house form open the draft key `doorprints.houseDraft:new:22.5726,88.3639` exists and is gone after *Add house*. With the made-up key saved, `doorprints.api-config` is in **session** storage only; `localStorage` has no key and IndexedDB `settings` has no key (`brokers.migrated`, `questions.seeded`, `storage.persist-asked`, `sync.server`). After *Remove all data*: 0 houses, caches `{}`, `localStorage` `{}` (no language was chosen), session `{}`, 0 service workers, no cookies. **Differences from `web/README.md:502-512`**: IndexedDB is version 4 with 7 stores (`houses`, `photos`, `records` (14 rows at first start, 14 again after removal; the `questions.seeded` setting suggests the default question bank), `saved_walks`, `settings`, `trace_points`, `visits`), the README says 4 stores, all empty; a second, empty cache `doorprints-offline-maps-v1` appeared after the map/offline-maps code ran (README: exactly one cache). `docs/16-architecture-overview.md:116` is current. **Not done:** the *Remember on this device* move to `localStorage` (my second attempt left it in session storage; inconclusive), pass 2 (private window), the `chrome://serviceworker-internals` step. |
| B6 | Third-party requests | **PASS** | Playwright request log over two sessions (map at several zooms, add/open houses, Compare, Your data, Connect, share): hosts `doorprints.web.app` (239 and 274 requests) and `tiles.openfreemap.org` (174 map requests) only; fonts are self-hosted (`font-src 'self'`). Nominatim (only on a tap), Google Identity (only on Connect Drive) and AI hosts were not exercised. |
| B7 | Shared text (share target) | **PASS** | `/share?title=T<script>alert(2)</script>&text=<img src=x onerror=alert(1)> call owner 90000 00001&url=javascript:alert(3)`: address bar after load is `https://doorprints.web.app/share` (query stripped); the text sits in the `#shared` textarea as text; 0 injected `img`; no dialog; the phone number is not in `history.state`; the only session key is `doorprints.shareText` (as the README table says). |

### C. Android app, MASTG-lite

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| C1 | Manifest | **NOT RUN** | No APK can be downloaded here (artifact `release-files` of the dry run, 30.8 MB, is behind a refused host) and there is no `aapt2` on the sandbox. Release dry run on the release commit: [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39330194280](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39330194280) (workflow_dispatch on `main`, `11072090`): *Release signing secrets present?* success, *Signed release APK* success ([job](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39330194280/job/118326177511), steps *Guard the ref*, *assembleRelease (signed)*, *Verify the signature, write the checksum and the notes* all `success`), *Create the draft release* skipped (dry run). **Source-level pre-check of `android/app/src/main/AndroidManifest.xml`:** no `debuggable`; `allowBackup="false"` (line 60) with `dataExtractionRules`; `networkSecurityConfig` as in A3; 13 `uses-permission` = INTERNET, ACCESS_NETWORK_STATE, ACCESS_FINE/COARSE/BACKGROUND_LOCATION, POST_NOTIFICATIONS, FOREGROUND_SERVICE (+_LOCATION, +_DATA_SYNC), SCHEDULE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, USE_BIOMETRIC, RECORD_AUDIO (once, line 52) with `android.hardware.microphone` `required="false"` (line 56): the reviewed list; exported components: `MainActivity` and its alias only, services, receivers and providers `exported="false"`. The binary's merged manifest still has to be read from the release APK (owner or a session with the APK). |
| C2 | Signature | **NOT RUN** | CI evidence: dry-run job above, step *Verify the signature...* `success` = `apksigner verify --print-certs` passed and exactly one 64-hex certificate digest was found; `fingerprint` output set. The **digest value is not readable** from the API (job output), so the match with the README (`ea23f3ca1175eaacef73f124449895537d6c16ff4f8de4da1e6d00d749be4381`, `README.md:84`) was not compared for this build. (The existing draft release `v0.1.0` carries the same fingerprint in its notes, but that draft is built from an older commit, see precondition P-3.) Owner: compare the dry run's/draft's fingerprint and `.sha256`. |
| C3 | Logs | **OWNER** | Needs a device or emulator with `adb logcat`. |
| C4 | Deep links | **OWNER** | Needs a device or emulator (TC-S-12 `adb shell am start` commands). |
| C5 | Permissions at use | **OWNER** | Fresh install on a device: location rationale, *Only this time*/*Deny*, the microphone only after the tap (MT-82). Code-level support: F11b. |
| C6 | Files the user saves | **OWNER** | Needs a device (system picker). |
| C7 | Captive portal (TC-S-13) | **OWNER** | Only when sync changed; needs a device/network. |

### D. MobSF dynamic run

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| D1 | Install, five-minute dynamic run | **OWNER** | Needs Docker with MobSF dynamic analyzer and an emulator image; not available in this sandbox. |
| D2 | Traffic | **OWNER** | As D1. |
| D3 | Files and shared preferences | **OWNER** | As D1. |
| D4 | Logs and clipboard | **OWNER** | As D1. |

### E. Server (only for a server others can reach)

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| E1 | TLS | **N/A** | No server that anyone outside the owner's devices can reach: `docs/14-lead-backlog-and-handoff.md:251` (*no Play Store release and no public server until [13] passes*), D-28 (*there is no hosted server*, `docs/13-release-security-checklist.md:49-50`, `:165`); the privacy page says *no server of ours*. Run E before any self-hosted server is opened to the internet. |
| E2 | Authorisation | **N/A** | No server that anyone outside the owner's devices can reach: `docs/14-lead-backlog-and-handoff.md:251` (*no Play Store release and no public server until [13] passes*), D-28 (*there is no hosted server*, `docs/13-release-security-checklist.md:49-50`, `:165`); the privacy page says *no server of ours*. Run E before any self-hosted server is opened to the internet. |
| E3 | Exposed endpoints | **N/A** | No server that anyone outside the owner's devices can reach: `docs/14-lead-backlog-and-handoff.md:251` (*no Play Store release and no public server until [13] passes*), D-28 (*there is no hosted server*, `docs/13-release-security-checklist.md:49-50`, `:165`); the privacy page says *no server of ours*. Run E before any self-hosted server is opened to the internet. |
| E4 | Rate limits | **N/A** | No server that anyone outside the owner's devices can reach: `docs/14-lead-backlog-and-handoff.md:251` (*no Play Store release and no public server until [13] passes*), D-28 (*there is no hosted server*, `docs/13-release-security-checklist.md:49-50`, `:165`); the privacy page says *no server of ours*. Run E before any self-hosted server is opened to the internet. |
| E5 | Upload abuse | **N/A** | No server that anyone outside the owner's devices can reach: `docs/14-lead-backlog-and-handoff.md:251` (*no Play Store release and no public server until [13] passes*), D-28 (*there is no hosted server*, `docs/13-release-security-checklist.md:49-50`, `:165`); the privacy page says *no server of ours*. Run E before any self-hosted server is opened to the internet. |
| E6 | CORS | **N/A** | No server that anyone outside the owner's devices can reach: `docs/14-lead-backlog-and-handoff.md:251` (*no Play Store release and no public server until [13] passes*), D-28 (*there is no hosted server*, `docs/13-release-security-checklist.md:49-50`, `:165`); the privacy page says *no server of ours*. Run E before any self-hosted server is opened to the internet. |

### F. OWASP Top 10 for LLM applications

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| F1 | LLM01 Prompt injection (golden set, `injectionResistance` 1.0) | **NOT RUN** | Checklist says run *AI evals* on the release commit first. **No ai-evals run exists for `11072090`.** Latest run: [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38693945231](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38693945231) (`workflow_dispatch`, 2026-10-15, `06034893`), `Golden-set eval (JDK 25, PostGIS 18 + pgvector, real model)` job `success` (the run's other jobs skipped), artifact `ai-eval-report`; the scorecard values are in that artifact (not downloadable here). It is **stale**: 589 files changed since `06034893`, including `web/src/app/core/ai/*` (kind-ai, transcriber), `docs/ai/evals`, the server's kind reader (#373), voice (#352,#364,#367). `EvalScorerTest` (golden-set shape) runs in every `mvn verify` and was green at `a00f3433`. Owner: dispatch `ai-evals.yml` on `main` (needs the repository AI secret) and read `injectionResistance`. |
| F2 | LLM02 `ContactRedactor` (TC-AI-15), `ask-09-injection-contact-leak` | **NOT RUN** | Unit half (`ContactRedactorTest`, `HouseDocumentsTest`, ...) runs in `mvn verify`, green at `a00f3433` (A1). The eval case `ask-09` needs the F1 run. |
| F3 | LLM03 Supply chain (provider and model named in the scorecard) | **NOT RUN** | The scorecard is in the undownloadable artifact; see F1. Dependabot on Spring AI: see A6 (alerts disabled). |
| F4 | LLM04 Poisoning | **PASS** | No training or fine-tuning in the tree: `git grep -il 'fine.tun'` over `backend/src/main` returns nothing; notes are passed as data in nonce-delimited blocks (docs/ai/ai-design.md). |
| F5 | LLM05 Improper output handling (TC-AI-06, `extract-12-injection-markup`, B2) | **NOT RUN** | B2 passed (payload rendered as text everywhere). The eval case needs the F1 run. |
| F6 | LLM06 Excessive agency (all tools read-only) | **PASS** | `@Tool` methods: `VisitPlannerTools.java:96-174` = `searchHouses`, `nearbyHouses`, `houseDetails`, `visitHistory`, `orderByNearestNeighbour`, `estimateWalkMinutes`; `McpHouseTools.java:46-75` = `searchHouses`, `houseDetails`, `nearbyHouses`, `askDoorprints`; `HouseQueries.java:42` says it deliberately has no `@Tool` annotations. No write tool exists. (MCP and AI are off by default: `application.yml:114,211` `APP_MCP_ENABLED`/`APP_AI_ENABLED` default false.) |
| F7 | LLM07 System-prompt leakage (leak markers) | **NOT RUN** | Needs the F1 run. |
| F8 | LLM08 Vector and embedding weaknesses (TC-AI-08 deletion) | **NOT RUN** | Unit half green in `mvn verify` at `a00f3433`; the eval half needs the F1 run. |
| F9 | LLM09 Misinformation (citations, refusal) | **NOT RUN** | Thresholds are in the F1 scorecard. |
| F10 | LLM10 Unbounded consumption (quota, spend cap AI-015) | **OWNER** | Defaults in code: `application.yml:224-225` `AI_RATE_LIMIT_PER_MINUTE=10`, `AI_RATE_LIMIT_BURST=5`. The provider's spend cap (AI-015) is a console setting and "429 seen at the quota" needs a running server with AI on. |
| F11a | Voice input on the website: off by default, permission only at use, header, safety proof | **PASS** | Live, fresh profile, `navigator.mediaDevices.getUserMedia` wrapped to count calls: `/`, `/ask`, `/plan`, `/houses/new`, `/connect`, `/data` -> 0 calls, 0 microphone/Speak buttons, microphone permission state `prompt`; no voice setting is shown until AI features are on. Code: `voice-input.service.ts:199` (on only when `localStorage` `doorprints.voice-input` is `'1'`), `audio-recorder.ts:150` (the only `getUserMedia`, called from the button). `Permissions-Policy` has `microphone=(self)` (B1). TC-S-51 `core/voice/voice-safety.spec.ts` is part of `ng test`, green on the release commit ([job]({JOB(39327752054,118317863100)})). |
| F11b | Voice input on Android (code level) | **PASS** | Default off: `android/shared/.../data/Settings.kt:374` (`p[Keys.voiceInput] ?: false`). `RECORD_AUDIO` requested from one place, after the tap: `android/app/.../voice/AndroidVoiceServices.kt:62-70`; manifest has it once with the microphone feature `required=false` (C1). TC-S-52 `VoiceSafetyTest` runs in the Android job *assembleDebug + unit tests*, green at `a00f3433` ([job](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/38982781941/job/117247250119)). The real prompt flow is C5/MT-82 (owner). |
| F11c | Voice input on the iPhone and Sarvam | **OWNER** | TC-S-53 `IosVoiceSafetyTest` (source-level) is in `:ui:testAndroidHostTest`, green at `a00f3433`. The system prompt after a tap, recording ending when the app leaves the front, no audio in the container and Sarvam's 30 s stop need an iPhone (MT-104, MT-105, MT-107, MT-109, MT-110). |

### G. Privacy

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| G1 | What the release collects, sends and keeps matches docs/04 and the privacy note | **PASS** | By reading: `web/public/privacy.html` (*Last updated: 16 October 2026*) says no account and no server of ours, data on the device, optional own server and own Drive (encrypted first), the one scope `drive.file`, OpenFreeMap/Nominatim/AI/Firebase as the other reach, deletion levels and what stays. **The deleted-photo sentence** (*at least 30 days ... Recently deleted ... Delete everything removes it at once*) is in English (`privacy.html:152-157`) and in Hindi (`:204`), Tamil (`:237`) and Telugu (`:269`); also `guide/docs/google-drive.md:45` and `docs/15` (30-day rows). Voice: DF-49 is in `docs/04-data-flow-diagrams.md` (v0.18-0.25); the in-app disclosure/hint name the host (`web/src/app/i18n/en.ts:1956,1966`). **Observation O-1:** `privacy.html` has no sentence on voice audio (0 hits for voice/microphone/audio) although voice ships on all three platforms; G4 defines the voice *privacy note* as the in-app disclosure, so this is not a failure, but the public page should say it at its next update. |
| G2 | Play data-safety form (Play release only) | **OWNER** | Play Console form; not submittable from here, and no Play release exists (owner decision 2026-09-23). |
| G3 | The DPDP Act, 2023: erasure is exercised in the app | **PASS** | `privacy.html` (*Deleting your data from Google Drive*, *Remove all data*, *Doorprints never received your data, so there is no request to send to anyone*); *Remove all data* verified live in B5; no hosted server (E). Recheck if a hosted service is ever added. |
| G4 | Voice input in the privacy note and Play's form | **N/A** | The checklist marks it *a note until a Play release exists* (no form is submitted). In-app web strings exist: `en.ts:1955-1956,1966`. |

### I. Google sign-in and Drive

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| I1 | Scopes asked for | **OWNER** | Console (Data access) and the consent screen are owner-only. Code level: one constant `DRIVE_FILE_SCOPE = .../auth/drive.file` (`android/shared/.../drive/auth/GoogleAuthorizer.kt:28`), `BrowserGoogleAuthorizer.kt:100` refuses any other scope list; web `google-token-provider.ts:22`; the live web bundle contains exactly one `googleapis.com/auth/` string (`drive.file`) and no `openid` (grep over the 47 precached files). |
| I2 | No secret in the apps | **PASS** | `git grep -n -i 'client_secret\\|GOCSPX'` outside `docs/`: one hit, `KtorTokenEndpointTest.kt:82` asserting `f["client_secret"]` is null (*a native client has no secret*); the two `docs/` hits are the checklist and manual checklist. Live site: all 47 files of the service worker's precache list (4.3 MB: `main-*.js`, `chunk-*.js`, `config.js`, ...) fetched with `curl` and `grep -rli 'client_secret\\|GOCSPX'` -> none; `config.js` holds only the client id `355069700891-pe7c9...apps.googleusercontent.com`. **Not done:** the built APK and iOS app binaries (source only). |
| I3 | The Picker key's restrictions | **OWNER** | Google Cloud console. |
| I4 | Client OAuth (PKCE S256, state, no secret) | **OWNER** | Real Google/Safari/GIS flows need accounts. Code level, shared by Android's browser path and the iPhone (`IosWebAuthLauncher.kt`, `ASWebAuthenticationSession`, no web view): auth request carries `code_challenge`, `code_challenge_method=S256`, `state` (`BrowserGoogleAuthorizer.kt:240-242`); token request carries `code_verifier` (`TokenEndpoint.kt:113`) and no secret (`KtorTokenEndpointTest.kt:82`); a callback with a wrong `state` is refused, constant-time compare (`BrowserRedirect.kt:96`). COOP `same-origin-allow-popups` and the CSP `script-src ... accounts.google.com/gsi/client` are live (B1). |
| I5 | Token storage | **OWNER** | Code level: web token memory-only (`google-token-provider.ts:81`; no storage call in the file); iPhone Keychain `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` (`DriveKeychain.kt:126`); Android refresh token wrapped by `KeystoreSecretWrapper` (`DriveServices.kt:118`). Live web: after the B5 session no token in `localStorage`, `sessionStorage` or IndexedDB (Drive was not connected). Phones and logs need devices. |
| I6 | What sits in the person's Drive | **OWNER** | Needs the test Google account. |
| I7 | Deleting (TC-M-48) | **OWNER** | Needs the test Google account. |
| I8 | Disconnect and revoke | **OWNER** | Needs the test Google account. |
| I9 | Untrusted files in Drive | **OWNER** | Needs the test Google accounts. The web's refusal of the same TC-S-17 files is B4; the Drive path reuses it. |
| I10 | Encryption (`DPX1`) | **OWNER** | Needs real Drive downloads. Format refusals are unit-tested (TC-U-128), CI green. |
| I11 | `keys.json` and downgrade | **OWNER** | Needs the test account (unit: TC-U-128/130/132). |
| I12 | Device authentication (TC-M-50) | **OWNER** | Needs phones and a passkey. |
| I13 | The screen lock (TC-M-51) | **OWNER** | Needs a phone. |
| I14 | Recovery key and enrolment (TC-M-52/53) | **OWNER** | Needs two devices and Drive (unit: TC-U-127/130). |
| I15 | Authenticator app | **N/A** | Deferred by the owner (2026-10-02) until S4b-BL-129. |
| I16 | Rollback | **OWNER** | Needs the test account (unit: TC-U-130/131). |
| I17 | Google's user-data policy | **OWNER** | The privacy page half is fine by reading (`privacy.html`: *Limited Use requirements*, *not used to develop, improve or train ... models*, link to the API Services User Data Policy). The consent screen linking it is a console check. |
| I18 | A deleted photo comes back (TC-M-90, MT-122) | **OWNER** | Needs the real Drive round trip (also open in docs/14 §1). |

### H. India's boundaries

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| H1 | Geospatial self-certification re-read (docs/03 §11.1) | **OWNER** | A re-read of DST's guidelines and negative list at each release is the owner's judgement. §11.1 (`docs/03-design.md:1354`) exists, was last read in full on 2026-09-28; this run did not check `geospatial.dst.gov.in` for a newly notified negative list. |
| H2 | TC-M-25 on the release build, house space and each other released kind | **OWNER** | TC-M-25 is a manual comparison with the Survey of India's boundary and an Android device look. **Web look done (live site, house space, Chromium, OpenFreeMap tiles):** `tools/live-ui` `ONLY=boundaries` ran (1 check pass: `in-boundaries.geojson` served with world, claim and state; screenshots) and my own captures at zoom 5 and 8 over Kashmir (34.5 N 76.5 E) and 5 and 8 over Arunachal (28.2 N 94.5 E), plus zoom 6 Kashmir and 7 Arunachal, were read by eye: one solid outline around all of Jammu and Kashmir and Ladakh (Gilgit and Aksai Chin inside) and around Arunachal Pradesh, no dashed LoC/LAC, no line through Kashmir, labels *Jammu and Kashmir*, *Ladakh*, *Arunachal Pradesh*, no *Azad Kashmir* label. The school's space: the TC-M-89 web half was recorded earlier in `docs/06` (8 views pixel-identical). Owner: the Android device look and the formal comparison. |
| H3 | Kind-file validator rejects map keys | **PASS** | Tests exist: `web/src/app/core/kind-capabilities.spec.ts:189` (*a kind file has no map configuration...*), `android/shared/src/androidHostTest/.../KindCapabilitiesVectorsTest.kt:228` `aKindFileHasNoMapConfiguration`, `backend/src/test/.../KindCapabilitiesFileTest.java:56` `theSchemaHasNoMapConfigurationAndMapIsABooleanOnly`, `web/src/app/shared/india-boundaries-kinds.spec.ts`. They ran green in CI: web `ng test` on the release commit ([job]({JOB(39327752054,118317863100)})), `:shared:testAndroidHostTest` and `mvn verify` at `a00f3433`. Tripwire run here: `node tools/check-kind-literals.mjs` -> `kind tripwire: 987 source files, 33 allowed, no new kind literal, no zz_all, the map rules read no kind.` |

### J. Kinds, spaces and deletion

| # | Check | Status | Evidence / finding |
|---|---|---|---|
| J1 | Compiled in, off (web) | **PASS** | `web/angular.json:45` production `define` `DOORPRINTS_KINDS: 'house,school'`; the only `DOORPRINTS_KINDS`/`--define` in `web/package.json`, `web.yml`, `angular.json` is that one (`web/src/app/core/release-kinds.spec.ts:33,40` pin it). Live, fresh profile: `/` is the map (`#houses-heading`, no `#chooser-heading`); Your data shows `#kinds-experimental` **unchecked** (*Experimental: more than houses*), no `#kind-rows`, no `#kind-on-school`; `/k/school` ends at `https://doorprints.web.app/`. Title, meta description and manifest description say houses only; `about.html` has no *school*. Server: `backend/src/main/resources/application.yml:208` `available: ${KINDS_AVAILABLE:house}` and `docker-compose.yml:128` default `house`. |
| J2 | A stored switch is the feature, not a bypass | **PASS** | Live: `doorprints.kinds.experimental=1` -> switch checked, the school row offered (`#kind-on-school`), once shown `/` is the chooser with 2 cards. `doorprints.dev.kinds=school` + `kinds.enabled=[house,school]` + `kinds.default=school` with the switch off -> `/` map, switch off, no rows, `/k/school` back to `/`. A backup whose `search` is `{kinds:[house,school],defaultKind:school}` imported with the switch off: stored `kinds.enabled`/`kinds.default` change but the app stays the house (map, no chooser, switch off, no rows). |
| J3 | A hostile file | **PASS** | `search` `{kinds:[constructor,__proto__],defaultKind:constructor}` plus a candidate of kind `constructor`: preview shown, imported (3 -> 4 houses), no error screen, no page error, the candidate is listed on the map as a house, Compare opens. An extra of the wrong type (`bedrooms:"many"`) or out of range (`floor:1e12`): refused with *its data could not be read* (the usual BROKEN_DATA reason). |
| J4 | No delete reaches another kind | **NOT RUN** | Needs two kinds shown with data in both and the bulk-delete flow; not driven here. Unit coverage exists in `ng test` (`delete-undo.service.spec.ts`, green). |
| J5 | The global deletions say so | **NOT RUN** | The *Remove all data* dialog with both kinds' counts and the Drive level-3 wording were not read with two kinds on. The server half (428 without `X-Confirm-Delete`) is E (N/A). |
| J6 | AI input of a kind | **NOT RUN** | Needs the F1 eval run (`kind-ai-vectors.json` cases). |
| J7 | The index follows the list | **N/A** | No deployed server; `KINDS_AVAILABLE` default is `house` and has not changed. |
| J8 | The phones: compiled in, off | **OWNER** | Build config by reading: `android/app/build.gradle.kts:68` `DEV_KINDS=false`, `:73` `AVAILABLE_KINDS="house,school"` in `defaultConfig`; the `release` build type (`:105-110`) overrides neither (only `debug` can turn `DEV_KINDS` on); `versionName = "0.1.0"` (`:56`). `ReleaseKindsTest` and `IosKindsGateTest` green at `a00f3433`. A fresh install of each phone (Map first, no chooser, switch off, imported school entry listed as a house, switch on shows the chooser) is a device check (MT-96, MT-112, MT-113). |

## 5. How this was run

- **Commit and worktree.** `git fetch origin`, worktree `/home/user/wt-release-run` on `docs/release-checklist-run-2026-10-19` from `origin/main` = `11072090eb9de0cb5958d8be4e33de558b69192e`; `/home/user/doorprints` untouched.
- **Part A.** `gh api repos/Sriram-Codes-SW/doorprints/actions/runs?head_sha=...`, `.../commits/<sha>/check-runs`, `.../actions/workflows/<file>/runs?branch=main`, `.../actions/runs/<id>/jobs` (steps), `.../code-scanning/alerts` and `.../dependabot/alerts` (both 403). Logs of a job come only through the GitHub MCP `get_job_logs`; artifacts cannot be downloaded.
- **Release dry run.** `release.yml` was dispatched once on `main` through the GitHub MCP `actions_run_trigger` (no run existed for this commit; the last dry run was on `1c3bc489`): [https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39330194280](https://github.com/Sriram-Codes-SW/doorprints/actions/runs/39330194280), conclusion `success`.
- **Part B and J on the live site.** Playwright 1.56.1 from `tools/live-ui/node_modules` (launched as `tools/live-ui/live-ui.js` does: swiftshader flags, no extra profile), a new in-memory browser context per scenario, service worker allowed for the storage audit and blocked elsewhere, `acceptDownloads`. Nothing else in the sandbox or the owner's data was touched; the test houses live only in those throwaway contexts. TC-S-17 fixtures were generated with Python `zipfile` from `docs/schemas/backup-sample.json` (a valid manifest with the data file's SHA-256), then one defect each: an entry `../../evil.txt`, `photos/../../evil.jpg`, `/etc/evil`, a 40 MB zero photo (ratio about 1000 against the 100 limit), a wrong manifest hash, 5 001 entries (limit 5 000), a foreign format `doorprints-backup/99`, and a JSON that is not a backup. The kinds fixtures change `search` and the candidate kind to `constructor`/`__proto__`/`school`.
- **Part I.** `git grep` over the repository, and `curl` of the 47 files in the live service worker's precache list (`/sw.js`), `grep -rli 'client_secret\|GOCSPX'` over them.
- **Part H.** `ONLY=boundaries node tools/live-ui/live-ui.js https://doorprints.web.app <out>` (the area only takes screenshots and checks the GeoJSON is served) and four more captures read by eye.
- **Not run** (see the rows): C3-C7, D, I (except I2), F evals, A2/A3/A4 artifact reading, A5/A6, H1/H2 device parts, J4-J6.
- **Local checks of this change:** `node tools/check-docs-versions.mjs`, `node tools/check-sprint-status.mjs`, `python3 .github/scripts/licence-headers.py --check`.

