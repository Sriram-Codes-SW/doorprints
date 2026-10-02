# 13: Release security checklist

| Field | Value |
|---|---|
| Document | The manual part of the release security gate: the one-hour list per release, and the scope of the deep self-run pentest |
| Version | 0.7 |
| Date | 2026-10-02 |
| Author | Claude (Code), lead |
| Status | Draft. Written (story S4b-SEC-3); **not yet run on a release candidate**, which is the story's last acceptance item |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-29 | Claude (Code), lead | First version (S4b-SEC-3, owner decision 1 of 2026-09-23, [10](10-sprint-log.md) §12.5): what the list is for and when it runs (1), what must be true before it starts (2), the list itself, about one hour, in eight parts (3), how a result is judged and recorded (4), the deep self-run pentest's scope (5), and a record to copy for each release (6). [06](06-test-plan.md) §11.1 gives each automated check its owner and threshold. |
| 0.2 | 2026-10-02 | Claude (Code), lead | **Re-scoped for D-28** (S4b-BL-74; design [15](15-google-drive-backup-and-sharing.md)): there is no hosted server, so no server-side OAuth, OIDC, sessions or data-fiduciary case; new part I, Google sign-in and Drive (client OAuth with PKCE on the iPhone, Play services on Android, the GIS token model on the website; token storage; the one scope `drive.file`; what sits in the person's Drive; deleting it); G3 rewritten (the right to erasure is the in-app deletion); §1 and §5 name Drive's go-live instead of Sprint 5's sign-in. |
| 0.3 | 2026-10-02 | Claude (Code), lead | Part I gains I10..I14 for the owner's additions to [15](15-google-drive-backup-and-sharing.md) v0.2: encryption with device keys and the recovery key, device authentication for deletes, the required screen lock, photos on mobile data. |
| 0.4 | 2026-10-02 | Claude (Code), lead | I15, the authenticator app ([15](15-google-drive-backup-and-sharing.md) §10.5). |
| 0.5 | 2026-10-02 | Claude (Code), lead | After the review of [15](15-google-drive-backup-and-sharing.md) v0.4: I5 depends on the Android sign-in choice; I14 for the QR enrolment and the recovery public key; new I16 (a restored old `keys.json` refused) and I17 (Limited Use and the privacy page). |
| 0.6 | 2026-10-02 | Claude (Code), lead | The owner decided [15](15-google-drive-backup-and-sharing.md) (v0.5): I15 (authenticator app) deferred; I12 for the website's PRF-only rule; I5 with Android's browser-and-PKCE default. |
| 0.7 | 2026-10-02 | Claude (Code), lead | The encryption core is built (S4b-BL-125, [15](15-google-drive-backup-and-sharing.md) §9.9): I10, I11, I14 and I16 name the unit tests that already hold their rules on Linux (TC-U-128, TC-U-130, TC-U-127; [06](06-test-plan.md)), so the release run checks the real Drive and the screens; I14 adds the website's recovery key in Firefox and Safari ([02](02-threat-model.md) RR-27). I13 is unchanged (the screen lock is S4b-BL-127). |

## 1. What this is, and when it runs

The owner's guard rule ([10](10-sprint-log.md) §12.5, Decision 1): **no Play Store release and no public server until the
release security gate exists and passes**, and once it exists, every web deploy passes it too. The gate has three parts:

1. **Automated**, in CI on every push: the checks in [06](06-test-plan.md) §11.1, each with its tool, owner and threshold.
2. **Manual, about one hour per release**: this document's section 3.
3. **A deep self-run pentest** before the Play Store launch and again before Google Drive sign-in goes to production: section 5.

Run section 3 on the **release candidate**: the exact commit, APK and web build that would ship. It runs before:

- a Play Store release (any track, internal testing included);
- a server that anyone outside the owner's own devices can reach (a self-hosted one opened to the internet; there is
  no hosted server, D-28);
- publishing the Google Cloud project's sign-in to production, so that people beyond the listed test users can connect
  Google Drive ([15](15-google-drive-backup-and-sharing.md) §2.3, decision 7);
- a web deploy, once this gate exists. A web-only deploy runs parts A, B, F (if AI changed) and H only; the rest
  covers code that did not change.

Who: the owner, or a Claude session with the owner at hand for the device steps. Use **test data only**: never a
real landlord's name or phone, never the owner's own house list.

## 2. Before you start

- [ ] Every workflow on the release commit is green: Web, Backend, Android, Shared-iOS, Security, CodeQL.
- [ ] The artifacts are at hand: `backend-zap-api-scan` and the Trivy image output (Backend), `android-mobsf-report`
      (Android), the Security run's licence-check log.
- [ ] A release APK built from that commit (signed, from `android.yml`'s `release` job, for a Play release; unsigned
      from the `mobsf` job otherwise).
- [ ] Tools: Docker (MobSF, and ZAP if needed), an Android emulator or a test phone with `adb`, a desktop browser with
      developer tools, `curl`, and for a public server `testssl.sh`.
- [ ] A test house list: three houses, one with the payloads `<img src=x onerror=alert(1)>` as its label and
      `javascript:alert(1)` as its listing URL, one with a made-up contact (`Test Owner`, `90000 00001`).

## 3. The list

About one hour. Each line: what to check, how, and what passes. A line that does not apply to this release (for
example part E with no public server) is marked *n/a* with the reason.

### A. Read the automated gate (5 minutes)

| # | Check | How | Pass |
|---|---|---|---|
| A1 | Every check of [06](06-test-plan.md) §11.1 ran on the release commit | The workflow runs of that commit | All green; nothing skipped that should have run |
| A2 | ZAP's Medium and Low alerts (TC-S-04) | `backend-zap-api-scan` → `zap.html` | Each one is a known, recorded item or is new and triaged now (fix, or a backlog item) |
| A3 | MobSF's accepted Highs still hold (TC-S-06) | `android/ci/mobsf-accepted.json` against `android-mobsf-report` | Every accepted item's reason is still true for this release |
| A4 | Licence warnings | The Security run's *Licence check* step | Each warning has an owner decision, or is in `.github/licence-exceptions.json` with a reason |
| A5 | Code scanning | GitHub → Security → Code scanning, filtered to the release branch | No open alert of severity *error* or *high*; each warning seen |
| A6 | Dependabot | GitHub → Security → Dependabot | No open High or Critical alert |

### B. Web app, WSTG-lite (15 minutes)

On the release build: the live site for a deploy that already happened, or `npx ng build` served locally otherwise.

| # | Check (OWASP WSTG) | How | Pass |
|---|---|---|---|
| B1 | Headers (WSTG-CONF-07, -12, -14) | `curl -sI https://doorprints.web.app/` | CSP, HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` and frame refusal as in `web/firebase.json` (TC-S-23) |
| B2 | Stored XSS (WSTG-INPV-02) | Add the test house with the payload label; open the map, the list, the house page, *Save a copy* as HTML and the print view | The payload shows as text everywhere; nothing runs (TC-S-16) |
| B3 | Dangerous links (WSTG-CLNT-04) | Open the house with `javascript:alert(1)` as its listing URL | The link is not clickable or does nothing |
| B4 | Malicious backup (WSTG-BUSL-09) | *Import a backup* with the TC-S-17 files (zip slip, bomb, hash mismatch) | Each one is refused with a message; no house changed |
| B5 | Client storage (WSTG-CLNT-12) | The storage audit in `web/README.md`, *Storage audit on the live site* | As that section says; *Remove all data* leaves nothing (TC-S-19) |
| B6 | Third-party requests | Developer tools → Network, one session with the map | Only the site's own origin, the map tile and font hosts, and the user's own server if connected |
| B7 | Shared text (share target) | Share a listing with a payload into the installed web app | Treated as text, stripped from the address (T-I26) |

### C. Android app, MASTG-lite (15 minutes)

On the release APK.

| # | Check (OWASP MASTG / MASVS) | How | Pass |
|---|---|---|---|
| C1 | Manifest (MASVS-PLATFORM, -NETWORK) | `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` | `debuggable` absent or false; `allowBackup` as documented; only the reviewed components exported; no cleartext outside the dev-host config (S4b-BL-64) |
| C2 | Signature (MASVS-RESILIENCE) | `apksigner verify --print-certs app-release.apk` | Verifies; the SHA-256 matches the published fingerprint (TC-S-15). *n/a* for an unsigned candidate |
| C3 | Logs (MASVS-STORAGE) | `adb logcat` while adding, syncing and exporting the test houses | No contact name, phone, note text or exact location in the log |
| C4 | Deep links (MASVS-PLATFORM) | The TC-S-12 `adb shell am start` commands | No crash; bad input ignored |
| C5 | Permissions at use | Fresh install; walk to Hunt mode | Location asked only when needed, with the rationale; *Only this time* and *Deny* both handled |
| C6 | Files the user saves | *Save a copy* and the weekly backup | Written only where the user chose (the system picker), never to shared storage unasked |
| C7 | Captive portal (TC-S-13) | Only when sync changed | As TC-S-13 |

### D. MobSF dynamic run (10 minutes)

MobSF's dynamic analyzer on an emulator image it supports (check MobSF's documentation for the versions; the static
scan's image `opensecurity/mobile-security-framework-mobsf`, pinned in `android/ci/mobsf-scan.sh`, has it).

| # | Check | Pass |
|---|---|---|
| D1 | Install the release APK, start the dynamic analysis, use the app for five minutes (add a house, Hunt mode, export, sync to a test server) | The run completes |
| D2 | Traffic | Only HTTPS to the tile hosts and the test server; no API key or house data to any other host |
| D3 | Files and shared preferences | Nothing sensitive outside the app's private storage; no key in shared preferences in clear |
| D4 | Logs and clipboard | No personal data |

### E. Server (10 minutes; only for a server others can reach)

| # | Check | How | Pass |
|---|---|---|---|
| E1 | TLS (TC-S-09) | `testssl.sh https://<server>` | TLS 1.2 or later only, HSTS, a valid certificate, no High finding |
| E2 | Authorisation (TC-S-08, TC-S-10) | The TC-S-10 `curl` list and a wrong key, against the real server | 401/400/404 every time, never data |
| E3 | Exposed endpoints | `curl` `/actuator`, `/actuator/env`, `/v3/api-docs`, `/mcp` without a key | Only `/actuator/health`; everything else 401 or 404 |
| E4 | Rate limits | 400 fast requests with one key (past `RATE_LIMIT_BURST`, 300 by default), then 30 fast wrong keys | 429 for both once the burst is used up (F-05) |
| E5 | Upload abuse (TC-S-11) | A 6 MB file and a polyglot | 400 or 413; the server stays up |
| E6 | CORS | A request with `Origin: https://evil.example` | No `Access-Control-Allow-Origin` for it |

### F. The OWASP Top 10 for LLM applications (5 minutes; only when AI is on in this release)

Run **AI evals** (`ai-evals.yml`, manual) on the release commit first. The golden set's prompt-injection cases
(TC-AI-04) are part of it.

| # | Risk (2025 list) | Where it is covered | Pass |
|---|---|---|---|
| F1 | LLM01 Prompt injection | 25 golden-set cases (TC-AI-04), nonce-delimited data blocks | `injectionResistance` 1.0 |
| F2 | LLM02 Sensitive information disclosure | `ContactRedactor` (TC-AI-15), `ask-09-injection-contact-leak` | Passed |
| F3 | LLM03 Supply chain | The provider and model named in the run's scorecard; Dependabot on Spring AI | The model is the documented one |
| F4 | LLM04 Data and model poisoning | No training or fine-tuning; notes are data | Still true |
| F5 | LLM05 Improper output handling | TC-AI-06; `extract-12-injection-markup`; B2 above | Passed |
| F6 | LLM06 Excessive agency | Every AI tool is read-only (`VisitPlannerTools`, the MCP tools); no write tool exists | No new tool, or a new one reviewed |
| F7 | LLM07 System prompt leakage | The golden set's leak markers (`Rules:` and the prompts' own sentences) | Passed |
| F8 | LLM08 Vector and embedding weaknesses | One store per deployment; deletion (TC-AI-08) | Passed |
| F9 | LLM09 Misinformation | Citations and refusal (TC-AI-02, TC-AI-03) | Thresholds met |
| F10 | LLM10 Unbounded consumption | Quota and limits (TC-AI-07); the provider's spend cap (AI-015) | Cap set; 429 seen at the quota |

### G. Privacy: the DPDP Act and Play's data safety (5 minutes)

| # | Check | Pass |
|---|---|---|
| G1 | What the release collects, sends and keeps | Matches [04](04-data-flow-diagrams.md) and the privacy note in the apps; a new flow is documented first |
| G2 | Play's data-safety form (Play release only) | Its answers match G1: location and contacts stay on the device or go to the user's own server; map tiles see the IP address; AI text goes to the chosen provider only when AI is on |
| G3 | The DPDP Act, 2023 | Personal use is outside the Act ([01](01-requirements.md) §9, section 3(c)(i)). There is no hosted server (D-28): the owner never receives anyone's data, so Doorprints holds nothing to notify about or erase on request. **The person's right to erasure is exercised directly in the app**: *Delete this backup*, *Delete all backups* and *Delete everything Doorprints keeps in my Google Drive* work on every platform and really delete (I7), *Remove all data* clears a device, and the privacy page ([15](15-google-drive-backup-and-sharing.md) S4b-BL-121) says in plain words what each does, what stays (other devices, shared copies others imported, Google's own retention) and how to remove Doorprints' access at Google. Recheck if a hosted service is ever added |

### I. Google sign-in and Drive (10 minutes; only once Drive is built, [15](15-google-drive-backup-and-sharing.md))

| # | Check | How | Pass |
|---|---|---|---|
| I1 | The scopes asked for | Google Auth Platform > Data access, and the consent screen each app shows | Exactly `drive.file`; no other scope (no `drive`, `drive.appdata`, `openid`) |
| I2 | No secret in the apps | Search the APK, the website's bundle and the iOS app for the web client's secret and any `client_secret` | None; only client ids, the iOS URL scheme, the Picker browser key and the project number |
| I3 | The Picker key's restrictions | Credentials > the API key | Websites `https://doorprints.web.app/*` only, API *Google Picker API* only |
| I4 | Client OAuth | iPhone: the authorisation request carries `code_challenge` (S256) and `state`, the token request `code_verifier` and no secret; Android: the grant through Play services; website: the GIS token popup works with the release's COOP (`same-origin-allow-popups`) and CSP | As said; the callback refuses a wrong `state` |
| I5 | Token storage | Website: developer tools > Application (no token in `localStorage`, `sessionStorage` or IndexedDB); iPhone: the Keychain item is *this device only*; Android (the browser with PKCE, the default decided 2026-10-02): the refresh token only sealed by a Keystore key bound to the screen lock (with the Play-services fallback: no token at all); all: `adb logcat` / the console during a sync | No token anywhere but memory and the iPhone's Keychain; no token in a log |
| I6 | What sits in the person's Drive | drive.google.com with the test account after a backup, a sync and a share | Only the *Doorprints* folder of [15](15-google-drive-backup-and-sharing.md) §5.1; contact details absent where they were left out; the shared file shared as *Viewer* with only the chosen account |
| I7 | Deleting | *Delete this backup*, *Delete all backups*, *Delete everything …* on the test account (TC-M-48) | The files are gone and not in Drive's bin; sharing removed first; automatic backup stays off; another device asks before backing up again |
| I8 | Disconnect and revoke | *Disconnect Google Drive*, then *Disconnect on all devices*, then Google's *third-party connections* page | Each does what 15 §3.5 says; no local data deleted; the app shows *Google Drive disconnected* |
| I9 | Untrusted files | Put the TC-S-17 files (zip slip, bomb, hash mismatch) into the test account's *Doorprints/Sync* and *Backups* and share one from a second account | Each refused with a message; nothing written |
| I10 | Encryption | Download a backup, a sync file and a photo from the test account's Drive; open Drive's preview | Each starts with `DPX1` and shows no house text, name or JPEG header; Drive's preview shows nothing; file names and `appProperties` carry no house data. The format's own refusals are unit-tested since S4b-BL-125 (TC-U-128) |
| I11 | `keys.json` and downgrade | Add an entry to `keys.json` by hand, and put a plain (unencrypted) sync file in *Sync* | The entry is refused and reported; the plain file is ignored. The MAC and the `NOT_DPX` refusal are unit-tested since S4b-BL-125 (TC-U-128, TC-U-130); the report and the ignore rule come with S4b-BL-116/-118 |
| I12 | Device authentication (TC-M-50) | *Delete all backups* and *Delete everything* on each phone; cancel once; wait over a minute once | The phone's own check every time, nothing deleted on cancel, asked again after the minute; the website offers L2/L3 only with a passkey whose PRF extension seals its key, otherwise L1 only |
| I13 | The screen lock (TC-M-51) | Connect on a phone with no lock; then with a lock, connect and remove the lock | Refused without a lock; paused with its message after removal, no upload or delete; re-enrolment needed afterwards |
| I14 | Recovery key and enrolment (TC-M-52, TC-M-53) | Save the recovery key at connect; a fresh device sees the existing folder and writes nothing until it joins; enrol it by QR code and once by the code fallback; revoke it; open a backup made after the revoke with the recovery key on a fresh browser | As 15 §9.3..§9.5: no silent adoption, a swapped key refused, the recovery key opens post-revoke files without having been typed in between; it appears nowhere in the app's storage, logs or Drive. The recovery key's derivation, check symbol and post-revoke wrap are unit-tested since S4b-BL-125 (TC-U-127, TC-U-130); on the website, open with the recovery key in Chromium, Firefox and Safari ([02](02-threat-model.md) RR-27) |
| I15 | Authenticator app (TC-M-55; **deferred**, owner 2026-10-02: n/a until S4b-BL-129) | Set it up; use a code on the website; replay it; five wrong codes; download `keys.json` | A replayed code and the sixth try are refused; the lock lasts 5 minutes; `keys.json` holds the secret only wrapped; a valid code alone never approves a device unattended |
| I16 | Rollback | Revoke a device, then bring back the older `keys.json` with Drive's *Manage versions* | Every device refuses it and says so; the revoked device's later files are skipped. The refusal (`ROLLED_BACK`) and the skip rule are unit-tested since S4b-BL-125 (TC-U-130, TC-U-131); the words and the report come with S4b-BL-126/-118 |
| I17 | Google's user-data policy | The privacy page and the consent screen | The page says what 15 S4b-BL-121 lists, including the Limited Use sentence; the consent screen links it |

### H. India's boundaries (5 minutes)

| # | Check | Pass |
|---|---|---|
| H1 | The geospatial self-certification, [03](03-design.md) §11.1, re-read against DST's guidelines and negative list | Still true for this release |
| H2 | TC-M-25 on the release build (web and Android) | Passed |

## 4. Judging and recording

- **Pass**: every line passes or is *n/a* with a reason.
- **A failure blocks the release**, unless the owner accepts the risk **in writing**: a line in
  [02](02-threat-model.md) §7 (an `RR-` residual risk) with the finding, the reason and an expiry date. A High or Critical finding cannot be
  accepted; it is fixed first.
- **Record**: copy section 6 into the release's entry in [10](10-sprint-log.md), fill it in, and link the artifacts.
  A finding becomes an `F-` item in [02](02-threat-model.md) and, when it is not fixed in the release, an `S4b-BL-`
  item in [10](10-sprint-log.md) §12.7.

## 5. The deep self-run pentest

Before the **Play Store launch**, and again before **Google Drive sign-in goes to production**. Time box: two days, with
the owner. It is run by us on our own systems: no third party, no paid tool (zero cost).

**In scope:** the release APK; the web app on a preview deploy or served locally; the backend in its release image
with PostGIS, on a private network or a test host; its API, `/mcp`, the AI endpoints (with a capped test key) and
sync; once Drive is built, the client OAuth flows on the three platforms, token storage, the Drive files, sharing
and deletion ([15](15-google-drive-backup-and-sharing.md); [02](02-threat-model.md) §10).

**Out of scope:** Google's, Firebase's and the tile providers' systems; the phone's operating system; load or denial
of service; social engineering; anything not ours. Nothing against `https://doorprints.web.app` beyond normal use and
the passive ZAP baseline the first-deploy checks already run ([06](06-test-plan.md) §11).

**Method:**

- The threat model is the test plan: each threat in [02](02-threat-model.md) rated High, and each trust boundary, gets
  at least one attempt.
- Web: the OWASP WSTG chapters for configuration, identity, authentication, authorisation, session, input validation,
  client side and business logic.
- Android: MASVS L1 with the MASTG tests for storage, crypto, network, platform and code, plus a Frida or objection
  session on a rooted emulator (root detection is not a requirement; the point is to see what an attacker with the
  device sees).
- Server: OWASP ASVS level 2 for authentication, session, access control and API (the self-hosted server; there is
  no hosted one, D-28).
- Client OAuth: ASVS's OAuth client requirements and RFC 8252 (OAuth for native apps) for the phones (PKCE, state, the
  redirect, no embedded web view), the GIS token model on the website, and every file read from Drive treated as
  untrusted ([02](02-threat-model.md) T-T15).
- AI: the Top 10 for LLM applications with hand-made payloads beyond the golden set.

**Tools:** OWASP ZAP (active scan and manual requests), Burp Suite Community, MobSF, Frida and objection,
`testssl.sh`, `sqlmap` against the local database only, `curl`.

**Exit:** no open Critical or High; each Medium fixed or accepted in writing ([02](02-threat-model.md) §7); the
report, as findings in [02](02-threat-model.md) and a summary in [10](10-sprint-log.md), with how each was tested.

## 6. Release record (copy for each release)

```
Release: <version>  Commit: <sha>  Date: <yyyy-mm-dd>  Run by: <name>
Kind: Play release / public server / web deploy
A automated gate   pass / fail   notes:
B web              pass / fail / n/a
C Android          pass / fail / n/a
D MobSF dynamic    pass / fail / n/a
E server           pass / fail / n/a
F LLM Top 10       pass / fail / n/a   AI evals run: <link>
G privacy          pass / fail
H boundaries       pass / fail
I Google Drive     pass / fail / n/a
Findings: <F- ids>   Accepted risks: <02 §7 RR- ids, expiry>
Pentest (Play launch or Drive go-live only): <date, report link>
Decision: ship / do not ship   Owner: <name>
```
