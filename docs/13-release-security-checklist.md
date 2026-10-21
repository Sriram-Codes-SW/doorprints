# 13: Release security checklist

| Field | Value |
|---|---|
| Document | The manual part of the release security gate: the one-hour list per release, and the scope of the deep self-run pentest |
| Version | 0.33 |
| Date | 2026-10-21 |
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
| 0.8 | 2026-10-02 | Claude (Code), lead | I11 adds a list re-wrapped under another folder key (the review of S4b-BL-125: refused by the pin and the recovery anchor, TC-U-132). |
| 0.9 | 2026-10-10 | Claude (Code), engineer | **Voice input, planned** ([03](03-design.md) ADR-37, S4b-BL-218): F11 (voice off by default, permissions asked at use, no audio at rest) and G4, the privacy note and Play data-safety answer to be done at voice pull request 5 (S4b-BL-222), a Planned note and not a claim. |
| 0.10 | 2026-10-14 | Claude (Code), engineer | **Part J, kinds and spaces** (S4b-BL-216; [02](02-threat-model.md) v0.77 T-E13, T-T21, T-I49, T-D15): J1..J7, run on every release (web, Android, server) until the owner opens a kind's release gate, and again after it; the release record gains line J. |
| 0.11 | 2026-10-14 | Claude (Code), engineer | **J1** notes the two unit tests that read the build config (S4b-BL-309: `release-kinds.spec.ts`, `ReleaseKindsTest`); the hand check stays for a workflow that adds a `--define` or a `-P` property. |
| 0.12 | 2026-10-14 | Claude (Code), engineer | §2 and §6: the release APK for a Play release comes from `release.yml` (a draft GitHub Release, C-02); its notes say *not approved* and this gate is what approves it; the record gains the draft's tag. |
| 0.13 | 2026-10-15 | Claude (Code), engineer | **B1 and F11:** the header row names `microphone=(self)` (voice input on the website, S4b-BL-221, TC-S-23, TC-S-51); F11's website clause is now a fact, the phones' clauses stay planned. |
| 0.14 | 2026-10-15 | Claude (Code), engineer | **Voice input on Android (S4b-BL-222):** F11's Android clause is now a fact (TC-S-52, TC-I-52; the manifest, the permission at the tap, no audio at rest); **C1** and **C5** name `RECORD_AUDIO` (the permission list); **G4** is written (the in-app disclosure, the rationale and the data-safety answer), still a note until a Play release. |
| 0.15 | 2026-10-15 | Claude (Code), engineer | **F11 on the iPhone** (S4b-BL-223): the iPhone clause is a fact to check on a build: `NSMicrophoneUsageDescription` is in `Info.plist` and its four `InfoPlist.strings`, the built `Info.plist` has no `audio` background mode (`shared-ios.yml`), the prompt appears only after a tap on *Speak* and the rationale (MT-104), recording ends when the app leaves the front (MT-105) and no audio file is in the app's container (MT-107). C5 names the iPhone's prompt. G4's note covers both phones; no form is submitted. |
| 0.16 | 2026-10-15 | Claude (Code), engineer | **Part J rewritten for "compiled in, off"** (owner, 2026-10-15; `feat/kinds-23-ship-school-web`): J1 the production build lists exactly `house,school` and offers the house alone (the switch off, no chooser); J2 a stored `kinds.experimental=1` is the feature, not a bypass; J4 wording; J3, J5-J7 unchanged. |
| 0.17 | 2026-10-15 | Claude (Code), engineer | **F11 and Sarvam (S4b-BL-224):** F11 gains the Sarvam clause (its own key slot, the host named, the 30 s stop; MT-109, MT-110). |
| 0.18 | 2026-10-15 | Claude (Code), engineer | **Part J, the phones: "compiled in, off"** (owner, 2026-10-15; `feat/kinds-24-ship-school-phones`): new J8 (the release APK and the iPhone list `house,school` and offer the house alone; the switch is the only way to the school; the phones' half of J1) |
| 0.19 | 2026-10-15 | Claude (Code), engineer | **Part H: every kind** (owner constraint, `feat/kinds-25-capabilities-schema`): H2 runs on the house space and on each other released kind's space; new H3, the kind-file validator rejects map keys. |
| 0.20 | 2026-10-16 | Claude (Code), engineer | H3 names the tripwire script and the per-kind boundary spec ([03](03-design.md) §18.26); H1 and H2 are re-read and unchanged (H2 still wants the TC-M-25 run, now also in a school's space, TC-M-89). |
| 0.21 | 2026-10-16 | Claude (Code), engineer | **G1 and I7 reconciled with restoring photos from Drive** (S4b-BL-293): a photo deleted in the app leaves the device at once but its encrypted copy stays in the person's own Drive for at least 30 days so *Restore* can bring it back; *Delete everything* still deletes it for good; new **I18**. |
| 0.22 | 2026-10-19 | Claude (Code), engineer | **The photo wording made exactly true** (S4b-BL-293, owner decision 2026-10-19, `docs/thirty-days-wording`): G1 and I7. The words say *30 days, and for longer only while a backup you kept still holds it*, which is what the clean-up of [15](15-google-drive-backup-and-sharing.md) §5.1 does (`photo-sweep-rules.ts`, `PhotoSweepRules.kt`: the file moves to Drive's bin once its tombstone is older than 30 days and no kept backup could hold it); "at least 30 days" was written before the clean-up existed. |
| 0.23 | 2026-10-19 | Claude (Code), engineer | **A7 new** (the `.gitleaksignore` entries as reviewed items, with the four kinds of reason, re-read at each release) and **C1a new** (the Android exported components named from the manifest, with the reason of each). **B4 and I9** name the bombs of TC-S-17 (a lying header, claims that add up past 1 GiB, 5 001 entries) ([06](06-test-plan.md) TC-S-17, [02](02-threat-model.md) T-T8). |
| 0.24 | 2026-10-19 | Claude (Code), engineer | **A5 and A6 name the owner's settings** (found in the release run, [ops/release-run-2026-10-19.md](ops/release-run-2026-10-19.md)): Dependabot alerts must be enabled in the repository settings (free), and reading code-scanning or Dependabot alerts from a terminal needs a token that can read them. The first release is **v0.1.1**, not v0.1.0 ([14](14-lead-backlog-and-handoff.md)). |
| 0.25 | 2026-10-19 | Claude (Code), engineer | **F11 and the plan rule** (owner decision 2026-10-19, [03](03-design.md) ADR-37, [02](02-threat-model.md) T-I54, RR-33): *a free Gemini key: the microphone is disabled with the free-plan sentence; a paid key: enabled after the probe* (TC-M-92, TC-M-93, TC-M-94; **not run**), and **the provider facts and quota ids are re-verified at each release** ([ai/voice-input](ai/voice-input.md) section 8 lists them): RR-33's residual is bounded by this re-check, which is its standing control ([02](02-threat-model.md)). |
| 0.27 | 2026-10-20 | Claude (Code), engineer | **J1 and J8 rewritten for the school's release on the website** (owner, 2026-10-19, [03](03-design.md) ADR-13b; `feat/school-release-web`, #389): J1 says *released*, not "compiled in, off" (a Schools row, off, and no Experimental switch); J8 keeps the phones compiled in and off; H2 applies in the school's space on the website. |
| 0.28 | 2026-10-20 | Claude (Code), docs | **G5 new** (`docs/privacy-children-data`): the privacy page states how children's data is handled, in four languages, and the contact redactor's limit is documented (O-1 of the release run of 2026-10-19 is covered by the same page update: the voice-audio sentence). [02](02-threat-model.md) v0.98 T-I55, RR-35. |
| 0.29 | 2026-10-20 | Claude (Code), engineer | **J8 rewritten for the school's release on the phones** (owner approved going ahead, 2026-10-20, [03](03-design.md) ADR-13b; `feat/school-release-phones`): `RELEASED` is the house and the school, a *Schools* row (off) and no *Experimental* switch, no preview marks; the phone device checks are named as the owner's and not run. H2 applies in the school's space on the phones. |
| 0.30 | 2026-10-20 | Claude (Code), engineer | **New J9**, kinds a person adds ([03](03-design.md) ADR-39, `feat/user-authored-kinds-web`). |
| 0.31 | 2026-10-21 | Claude (Code), engineer | **J9 on the phones too** ([03](03-design.md) ADR-39, `feat/user-authored-kinds-phones`). |
| 0.32 | 2026-10-21 | Claude (Code), engineer | C5 names the microphone check MT-127 (renumbered from MT-82, which the QR scanner row also held). |
| 0.33 | 2026-10-21 | Claude (Code), engineer | A6 points to the Dependabot triage of 2026-10-21 ([ops/dependabot-triage-2026-10-21.md](ops/dependabot-triage-2026-10-21.md); `fix/dependabot-alerts-2026-10-21`). |

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
- [ ] A release APK built from that commit (signed, for a Play release: the **draft GitHub Release** that `release.yml` makes
      from the pushed tag, [07](07-secure-build-and-deploy.md) §5, whose notes say it is not approved until this list passes; or
      `android.yml`'s `release` job artifact; unsigned from the `mobsf` job otherwise). Check its `.sha256` and that the signer
      fingerprint in the notes is the one in the README.
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
| A5 | Code scanning | GitHub → Security → Code scanning, filtered to the release branch. Reading the alerts from a terminal or a Claude session needs a token that can read code-scanning alerts (a fine-grained token with *Code scanning alerts: read*, or `security_events`); the session's own token may not. **Owner action** | No open alert of severity *error* or *high*; each warning seen |
| A6 | Dependabot | GitHub → Security → Dependabot. **Dependabot alerts must be enabled** first in the repository's settings (Settings → Code security → *Dependabot alerts*; free); with them off the page is empty and the row proves nothing. Reading them from a terminal needs a token that can read Dependabot alerts. The 13 alerts of 2026-10-21 (Bouncy Castle, jose4j, jdom2, vite, source-map-js: all build tooling, none shipped) are triaged and pinned in [ops/dependabot-triage-2026-10-21.md](ops/dependabot-triage-2026-10-21.md); confirm them closed after the merge. **Owner action** | Dependabot alerts are on, and no open High or Critical alert |
| A7 | The reviewed secret-scan exceptions | Read `.gitleaksignore` (113 fingerprints, each `commit:path:rule:line`, under a dated *Reviewed* comment) against the Security run's *gitleaks* step, which scans the whole history | Every entry is still one of the reviewed kinds and no entry is new since the last release without a comment: (1) a throwaway or made-up key in a test (the initial commit's backend test keys, 2026-09-22; `KeychainSettingsTest`, 2026-09-29; `AIzaTestKey1234`, `AIzaOwnKeyForTests1234`, the `sk-test-…` and voice-test strings, 2026-10-07 to 2026-10-15); (2) the type or variable name after `key:` or `privateKey:` in Drive code (`P256PrivateKey`, `override val key`, 2026-10-02 to 2026-10-06); (3) published known-answer vectors (RFC 9180 HPKE hex, the DPX regression base64, in `docs/schemas` and the `androidTest` copies, 2026-10-02 to 2026-10-10); (4) made-up recovery keys and hostile exception messages in tests (2026-10-06, 2026-10-07). None is a live credential; the commits are named, so a real key on another line or in a later commit still fails the scan. The file is **re-read at each release**: a fingerprint whose commit is gone from every branch can be deleted, and a new one needs a dated reason first (recorded in [07](07-secure-build-and-deploy.md)) |

### B. Web app, WSTG-lite (15 minutes)

On the release build: the live site for a deploy that already happened, or `npx ng build` served locally otherwise.

| # | Check (OWASP WSTG) | How | Pass |
|---|---|---|---|
| B1 | Headers (WSTG-CONF-07, -12, -14) | `curl -sI https://doorprints.web.app/` | CSP, HSTS, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` and frame refusal as in `web/firebase.json` (TC-S-23); the `Permissions-Policy` reads `geolocation=(self), camera=(self), microphone=(self), payment=(), usb=()` with no `*` (the microphone since S4b-BL-221, voice input) |
| B2 | Stored XSS (WSTG-INPV-02) | Add the test house with the payload label; open the map, the list, the house page, *Save a copy* as HTML and the print view | The payload shows as text everywhere; nothing runs (TC-S-16) |
| B3 | Dangerous links (WSTG-CLNT-04) | Open the house with `javascript:alert(1)` as its listing URL | The link is not clickable or does nothing |
| B4 | Malicious backup (WSTG-BUSL-09) | *Import a backup* with the TC-S-17 files (zip slip, bomb, a bomb whose header lies about its size, claims that add up past 1 GiB, hash mismatch, 5 001 entries); the bombs are small and can be made as the web's `backup-limits.spec.ts` makes them | Each one is refused with a message (too large, or too many entries); no house changed. The limits are those of [06](06-test-plan.md) TC-S-17 |
| B5 | Client storage (WSTG-CLNT-12) | The storage audit in `web/README.md`, *Storage audit on the live site* | As that section says; *Remove all data* leaves nothing (TC-S-19) |
| B6 | Third-party requests | Developer tools → Network, one session with the map | Only the site's own origin, the map tile and font hosts, and the user's own server if connected |
| B7 | Shared text (share target) | Share a listing with a payload into the installed web app | Treated as text, stripped from the address (T-I26) |

### C. Android app, MASTG-lite (15 minutes)

On the release APK.

| # | Check (OWASP MASTG / MASVS) | How | Pass |
|---|---|---|---|
| C1 | Manifest (MASVS-PLATFORM, -NETWORK) | `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` | `debuggable` absent or false; `allowBackup` as documented; only the reviewed components exported; no cleartext outside the dev-host config (S4b-BL-64); **the permission list is the reviewed one** (the location pair, background location for the area wake-up only, notifications, the foreground-service types, exact alarms, boot, biometric, internet and network state, and `RECORD_AUDIO` for voice input, S4b-BL-222: one `uses-permission`, with `android.hardware.microphone` as `required=false`); a new permission is documented first (TC-S-52 reads this one) |
| C1a | Exported components, by name (MASVS-PLATFORM; the reviewed list of C1) | Compare the components `aapt2 dump xmltree` shows in the release APK with this list, which is `android/app/src/main/AndroidManifest.xml` as of 2026-10-19 | **Exported, two, both by design:** the activity `.MainActivity` (`singleTop`), with five filters: `doorprints://connect` (a server owner page's QR code or link; `ConnectLink.parse` checks it and the app asks before connecting), `app.doorprints:/oauth2redirect` (the return from the system browser's Google sign-in; acted on only for the pending request's own `state`), VIEW of `content://` and SEND of `application/zip` and `application/json` (a backup or update file, validated like any picked one, TC-S-17), and SEND of `text/plain` (*Add a shared listing*; parsed on the device, the page is never fetched); and the `activity-alias` `com.househunt.app.MainActivity` (the launcher entry, `MAIN`/`LAUNCHER`; the old class name is kept so home-screen icons survive the rename of 2026-09-24; it opens `.MainActivity`). **Not exported:** the service `.location.HuntService` (foreground, location); the receivers `.ViewingReminderReceiver`, `.ViewingReminderRescheduleReceiver` (system broadcasts only: boot, time and zone changes, package replaced, the exact-alarm permission change) and `.AreaGeofenceReceiver` (boot and package replaced; the geofence `PendingIntent` is explicit); WorkManager's `androidx.work.impl.foreground.SystemForegroundService` (`dataSync`) and the `androidx.startup.InitializationProvider`; and the `FileProvider` `${applicationId}.files` (grants by URI only). A third exported component, a changed filter, or a library component exported in the merged manifest that is not named here is a finding to explain before release |
| C2 | Signature (MASVS-RESILIENCE) | `apksigner verify --print-certs app-release.apk` | Verifies; the SHA-256 matches the published fingerprint (TC-S-15). *n/a* for an unsigned candidate |
| C3 | Logs (MASVS-STORAGE) | `adb logcat` while adding, syncing and exporting the test houses | No contact name, phone, note text or exact location in the log |
| C4 | Deep links (MASVS-PLATFORM) | The TC-S-12 `adb shell am start` commands | No crash; bad input ignored |
| C5 | Permissions at use | Fresh install; walk to Hunt mode; turn on *Voice input* and tap the microphone | Location asked only when needed, with the rationale; *Only this time* and *Deny* both handled; **the microphone is not asked at install or start-up, only after the tap and the rationale; *Not now* and *Deny* leave the field working** (MT-127; on the iPhone iOS asks once, MT-104) |
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
| F11 | Voice input (planned, [03](03-design.md) ADR-37; only when voice is in this release) | *Voice input* is **off by default** on a fresh install (Settings > AI features) and on the website; the microphone permission is **asked at first use**, with a rationale, never at install or start-up (Android `RECORD_AUDIO` is not requested before a tap); the microphone button is hidden for a provider that cannot transcribe; the audio is in no backup, copy or sync file (T-I48 tests); the disclosure names the host; the website's `Permissions-Policy` has `microphone=(self)` (shipped with the web voice pull request, S4b-BL-221: TC-S-23, TC-S-51, B1); **on Android (S4b-BL-222: TC-S-52, TC-I-52) the recorder is `AudioRecord` into memory with no file, `RECORD_AUDIO` is requested from one place after a tap and a rationale, recording is foreground only, and the manifest and backup rules take no audio**; **on the iPhone (S4b-BL-223: TC-S-53, `shared-ios.yml`) the recorder is an `AVAudioEngine` tap into memory with no file, `NSMicrophoneUsageDescription` is in `Info.plist` and the four `InfoPlist.strings`, no `audio` background mode is declared, the system question comes only after a tap and the rationale, and recording ends when the app leaves the front** | Still true, or voice is not in this release **Sarvam (S4b-BL-224):** if the release offers Sarvam, its key is in a slot of its own that no backup, copy, sync or Drive file holds (TC-U-265, TC-S-51..TC-S-53), the disclosure names `api.sarvam.ai`, and a Sarvam recording stops at 30 s (MT-109). **Plan rule (owner decision 2026-10-19; TC-U-286, TC-U-287, TC-U-288, TC-S-51..TC-S-53; TC-M-92..TC-M-94):** *a free Gemini key: the microphone is disabled with the free-plan sentence, and nothing is recorded; a paid key: enabled after the probe*; an OpenRouter key whose `GET /api/v1/key` says `is_free_tier` true, or a `:free` model, is refused the same way; Groq and Sarvam are refused until the person confirms "my plan is paid" for that key; **the provider facts and quota ids are re-verified at each release** (the probe model still has no free tier on Google's pricing page; a free Gemini 429 still names a `FreeTier` quota id; OpenRouter's key endpoint still answers `is_free_tier`; OpenAI still has no free API tier; no Groq or Sarvam call tells the plan yet): [ai/voice-input](ai/voice-input.md) section 8 lists what was read and when. |

### G. Privacy: the DPDP Act and Play's data safety (5 minutes)

| # | Check | Pass |
|---|---|---|
| G1 | What the release collects, sends and keeps | Matches [04](04-data-flow-diagrams.md) and the privacy note in the apps; a new flow is documented first. **A deleted photo (S4b-BL-293):** the privacy page, the guide and [15](15-google-drive-backup-and-sharing.md) §1.4 item 11 say the same sentence in all four languages: *deleting drops the photo from the device at once; its encrypted copy stays in your own Google Drive for 30 days so Recently deleted can bring it back, and for longer only while a backup you kept still holds it; Delete everything removes it at once* (T-I53, RR-34) |
| G2 | Play's data-safety form (Play release only) | Its answers match G1: location and contacts stay on the device or go to the user's own server; map tiles see the IP address; AI text goes to the chosen provider only when AI is on |
| G3 | The DPDP Act, 2023 | Personal use is outside the Act ([01](01-requirements.md) §9, section 3(c)(i)). There is no hosted server (D-28): the owner never receives anyone's data, so Doorprints holds nothing to notify about or erase on request. **The person's right to erasure is exercised directly in the app**: *Delete this backup*, *Delete all backups* and *Delete everything Doorprints keeps in my Google Drive* work on every platform and really delete (I7), *Remove all data* clears a device, and the privacy page ([15](15-google-drive-backup-and-sharing.md) S4b-BL-121) says in plain words what each does, what stays (other devices, shared copies others imported, Google's own retention) and how to remove Doorprints' access at Google. Recheck if a hosted service is ever added |
| G4 | Voice input in the privacy note in the apps and in Play's data-safety form (Play release only; written in S4b-BL-222, **a note until a Play release exists**) | The in-app privacy note is the *Voice input* setting's disclosure, the button's hint and the permission rationale: audio goes from the phone to the AI provider the person configured (host and list price named), only when *Voice input* is on, anything said is sent as audio and a spoken number is not redacted first, and Doorprints does not store or share it. The form's answer: audio and voice data, collected, sent to a third party the person chose, optional, off by default, not stored or shared by Doorprints; the microphone permission rationale matches [04](04-data-flow-diagrams.md) DF-49 and [11](11-feature-parity-and-export-spec.md) 5.18a. No form is submitted (no Play release, owner decision 2026-09-23) |
| G5 | Children's data (the school kind is released, so the page must say it; [02](02-threat-model.md) T-I55, RR-35) | `web/public/privacy.html` states, in English, Hindi, Tamil and Telugu (hi, ta and te marked under review): Doorprints is a personal tool for adults; a school entry may hold details about a child (school, fees, commute); it stays on the device unless the person backs up, syncs (their own Drive or server) or turns on AI with their own key; **the contact redactor removes saved contact names and numbers but does not remove a child's name written in a note, so the person should not write a child's name or other identifying details in notes**; no accounts, no tracking; and the voice-audio sentence (audio stays in memory and goes only to the provider the person chose, with their own key). Pass only if all four sections say it and [04](04-data-flow-diagrams.md) §6 and T-I55 give the same limit. Automated: `seo.spec.ts` *states children's-data handling in every language*. RR-35 is not accepted until the owner records it |

### I. Google sign-in and Drive (10 minutes; only once Drive is built, [15](15-google-drive-backup-and-sharing.md))

| # | Check | How | Pass |
|---|---|---|---|
| I1 | The scopes asked for | Google Auth Platform > Data access, and the consent screen each app shows | Exactly `drive.file`; no other scope (no `drive`, `drive.appdata`, `openid`) |
| I2 | No secret in the apps | Search the APK, the website's bundle and the iOS app for the web client's secret and any `client_secret` | None; only client ids, the iOS URL scheme, the Picker browser key and the project number |
| I3 | The Picker key's restrictions | Credentials > the API key | Websites `https://doorprints.web.app/*` only, API *Google Picker API* only |
| I4 | Client OAuth | iPhone: the authorisation request carries `code_challenge` (S256) and `state`, the token request `code_verifier` and no secret; Android: the grant through Play services; website: the GIS token popup works with the release's COOP (`same-origin-allow-popups`) and CSP | As said; the callback refuses a wrong `state` |
| I5 | Token storage | Website: developer tools > Application (no token in `localStorage`, `sessionStorage` or IndexedDB); iPhone: the Keychain item is *this device only*; Android (the browser with PKCE, the default decided 2026-10-02): the refresh token only sealed by a Keystore key bound to the screen lock (with the Play-services fallback: no token at all); all: `adb logcat` / the console during a sync | No token anywhere but memory and the iPhone's Keychain; no token in a log |
| I6 | What sits in the person's Drive | drive.google.com with the test account after a backup, a sync and a share | Only the *Doorprints* folder of [15](15-google-drive-backup-and-sharing.md) §5.1; contact details absent where they were left out; the shared file shared as *Viewer* with only the chosen account |
| I7 | Deleting | *Delete this backup*, *Delete all backups*, *Delete everything …* on the test account (TC-M-48) | The files **these actions name** are gone and not in Drive's bin; sharing removed first; automatic backup stays off; another device asks before backing up again. *Delete everything* removes every file of `Photos/`, the files of photos the person deleted in the app included (they stay in Drive until then, I18), so nothing is restorable from *Recently deleted* afterwards; *Delete all backups* keeps the photos, as its dialog says. This is about files in Drive: deleting a photo or a candidate **inside the app** is not one of these three actions and keeps the photo's encrypted copy for 30 days, and for longer only while a kept backup still holds it |
| I8 | Disconnect and revoke | *Disconnect Google Drive*, then *Disconnect on all devices*, then Google's *third-party connections* page | Each does what 15 §3.5 says; no local data deleted; the app shows *Google Drive disconnected* |
| I9 | Untrusted files | Put the TC-S-17 files (zip slip, bomb, a lying-header bomb, hash mismatch) into the test account's *Doorprints/Sync* and *Backups* and share one from a second account | Each refused with a message; nothing written |
| I10 | Encryption | Download a backup, a sync file and a photo from the test account's Drive; open Drive's preview | Each starts with `DPX1` and shows no house text, name or JPEG header; Drive's preview shows nothing; file names and `appProperties` carry no house data. The format's own refusals are unit-tested since S4b-BL-125 (TC-U-128) |
| I11 | `keys.json` and downgrade | Add an entry to `keys.json` by hand, and put a plain (unencrypted) sync file in *Sync* | The entry is refused and reported; the plain file is ignored. The MAC and the `NOT_DPX` refusal are unit-tested since S4b-BL-125 (TC-U-128, TC-U-130); the report and the ignore rule come with S4b-BL-116/-118; and replace `keys.json` by one made with another folder key wrapped to every listed device (TC-U-132's forge, by hand): every device and the recovery key refuse it |
| I12 | Device authentication (TC-M-50) | *Delete all backups* and *Delete everything* on each phone; cancel once; wait over a minute once | The phone's own check every time, nothing deleted on cancel, asked again after the minute; the website offers L2/L3 only with a passkey whose PRF extension seals its key, otherwise L1 only |
| I13 | The screen lock (TC-M-51) | Connect on a phone with no lock; then with a lock, connect and remove the lock | Refused without a lock; paused with its message after removal, no upload or delete; re-enrolment needed afterwards |
| I14 | Recovery key and enrolment (TC-M-52, TC-M-53) | Save the recovery key at connect; a fresh device sees the existing folder and writes nothing until it joins; enrol it by QR code and once by the code fallback; revoke it; open a backup made after the revoke with the recovery key on a fresh browser | As 15 §9.3..§9.5: no silent adoption, a swapped key refused, the recovery key opens post-revoke files without having been typed in between; it appears nowhere in the app's storage, logs or Drive. The recovery key's derivation, check symbol and post-revoke wrap are unit-tested since S4b-BL-125 (TC-U-127, TC-U-130); on the website, open with the recovery key in Chromium, Firefox and Safari ([02](02-threat-model.md) RR-27) |
| I15 | Authenticator app (TC-M-55; **deferred**, owner 2026-10-02: n/a until S4b-BL-129) | Set it up; use a code on the website; replay it; five wrong codes; download `keys.json` | A replayed code and the sixth try are refused; the lock lasts 5 minutes; `keys.json` holds the secret only wrapped; a valid code alone never approves a device unattended |
| I16 | Rollback | Revoke a device, then bring back the older `keys.json` with Drive's *Manage versions* | Every device refuses it and says so; the revoked device's later files are skipped. The refusal (`ROLLED_BACK`) and the skip rule are unit-tested since S4b-BL-125 (TC-U-130, TC-U-131); the words and the report come with S4b-BL-126/-118 |
| I17 | Google's user-data policy | The privacy page and the consent screen | The page says what 15 S4b-BL-121 lists, including the Limited Use sentence; the consent screen links it |
| I18 | A deleted photo comes back (TC-M-90, MT-122) | Delete a candidate with photos in the app; look in the test account's Drive; *Recently deleted* > *Restore*; then *Delete everything* and look again | The photo files are still in `Photos/` after the delete and are `DPX1`; *Restore* brings the candidate and its photos back and the words say how many of how many; with Drive disconnected or offline the candidate is back and the words say why the photos are not; after *Delete everything* no file is left and *Restore* says *no copy is left in your Google Drive*; the scope is still only `drive.file` (I1) |

### H. India's boundaries (5 minutes)

| # | Check | Pass |
|---|---|---|
| H1 | The geospatial self-certification, [03](03-design.md) §11.1, re-read against DST's guidelines and negative list | Still true for this release |
| H2 | TC-M-25 on the release build (web and Android), on the house space and on each other released kind's space (the school space too, once shown, on the website and the phones) | Passed |
| H3 | The kind-file validator rejects map keys: the schema test `kind-capabilities.spec.ts` ("a kind file has no map configuration"), `KindCapabilitiesVectorsTest.aKindFileHasNoMapConfiguration` and `KindCapabilitiesFileTest.theSchemaHasNoMapConfigurationAndMapIsABooleanOnly` pass, as do the tripwire `tools/check-kind-literals.mjs` (no map-rules file names a kind, no source names `zz_all`) and `india-boundaries-kinds.spec.ts` (the boundary style is byte-identical for every kind) | Passed |

### J. Kinds, spaces and deletion (10 minutes; every release, [02](02-threat-model.md) T-E13, T-T21, T-I49, T-D15)

| # | Check | Pass |
|---|---|---|
| J1 | **Released on the website** (owner, 2026-10-19, ADR-13b: after the named gates, website first, phones later). The release build lists the school (*available*) and `RELEASED_KINDS` is `house` and `school` (*released*), so it is offered with no switch. The website's production configuration lists exactly `house,school` (`angular.json` `define`; the build command and the `start` and `test` scripts add none), and in a private window `/` is the map, there is no chooser (the house alone is enabled), Your data shows *What you track* with a **Houses** row on and a **Schools** row **off** and **no** *Experimental: more than houses* switch (it hides itself while no listed kind is unreleased), and `/k/school` goes back to the home page until *Show Schools* is on. The tagline is "Remember every place you've seen." and the meta description, the manifest and `about.html` name houses and schools (`seo.spec.ts`). The server runs with `KINDS_AVAILABLE` unset or `house`: listing the school there is the operator's own choice (`KINDS_AVAILABLE=house,school`), not part of the release. (The phones' `AVAILABLE_KINDS` are J8.) (Unit tests `release-kinds.spec.ts` and `experimental-off.spec.ts` fail on a changed define or a changed released list; this check covers the workflow's command line and the built artefact.) | All true. A change to what is released is a deliberate decision of the owner, recorded in [10](10-sprint-log.md) and [03](03-design.md) ADR-13b |
| J2 | **A stored switch is the feature, not a bypass.** In the release build set `doorprints.kinds.experimental` to `1` by hand (it is what turning the switch on stores): the school is offered in Your data and, once shown, the chooser appears, as the person would get it. `doorprints.dev.kinds` set to `school` does nothing (a development build only), and `doorprints.kinds.enabled` naming a kind while the switch is off, or a backup whose `search` names `school` while it is off, changes nothing a person sees | With the switch off the app is the house alone: nothing is listed, drawn or sent to the AI as a school; with it on, only what *What you track* shows |
| J3 | **A hostile file.** Import a file with `search` of `{"kinds":["constructor","__proto__"],"defaultKind":"constructor"}`, a candidate of kind `constructor`, and an extra of the wrong type and out of range | The file imports or is refused for its usual reasons only; no error screen; the candidate is drawn as a house |
| J4 | **No delete reaches another kind.** With two kinds shown (the release build with the switch on, or a development build): *Delete all in this space* in one, then look at the other; *Recently deleted* of each; one delete then *Undo* | The other space has every entry; each list shows its own space's deletions; the second bulk action within 10 seconds waits |
| J5 | **The global deletions say so.** *Remove all data* lists every kind's count; Drive deletion level 3 says *all kinds*; `DELETE /api/data` answers 428 without its `X-Confirm-Delete` header (E) | Said in words before the action |
| J6 | **AI input of a kind.** With a kind available: the evals' kind cases (TC-AI-04, `kind-ai-vectors.json`) pass; an extra with an instruction as its text, a number out of range or a money value with a foreign cadence is left out of the document; the contact's name and phone do not appear | Passed |
| J7 | **The index follows the list.** After `KINDS_AVAILABLE` changes, `POST /api/ai/reindex` has been run (T-I49) | Done, or the list did not change |
| J8 | **The phones: released** (owner approved going ahead, 2026-10-20, ADR-13b; the phones' half of J1; `feat/school-release-phones`; no Play release exists). The release APK's `AVAILABLE_KINDS` is `"house,school"` and `DEV_KINDS` is `false` (`BuildConfig`, or `aapt`/`jadx` of the signed APK); the iPhone's `IOS_AVAILABLE_KINDS` is the same (`ReleaseKindsTest`, `IosKindsGateTest`), `KindSettingRules.RELEASED` is the house and the school, and the iPhone's Info.plist names no kind (its location text says *places you save*). On a fresh install of each: the Map is the first screen, no chooser, no chip (the house alone is enabled), Settings has *What you track* with a **Houses** row on and a **Schools** row **off** and **no** *Experimental: more than houses* switch (it hides itself while no listed kind is unreleased); a stored `kinds.enabled` or `kinds.default` naming the school changes nothing a person sees until *Show Schools* is on. Turn on *Show Schools*: the chooser appears. The switch hint and the school listing tips carry no *preview* mark (en, hi, ta, te; hi, ta, te under review). Settings > About says "Remember every place you've seen.", and the empty house list still says houses. **Owner-pending device checks:** MT-96, MT-97, MT-101, MT-103, MT-112, MT-113, MT-115, MT-116, MT-118..MT-121 and TC-M-25 in the school's space on a phone are not run | All true. Releasing a second kind (adding it to `RELEASED`) is a deliberate decision of the owner, recorded in [10](10-sprint-log.md) and [03](03-design.md) ADR-13b |
| J9 | **Kinds a person adds** ([03](03-design.md) ADR-39, T-T23). On the release website, an Android phone and an iPhone, each with *Kinds you add* on: the sample gym of `user-kind-vectors.json` imports, shows its preview, is added, turned on and used as a space; three hostile cases of the vectors (markup in a label, a map style, a prototype key) are refused with their reason and nothing is stored; the map in the gym's space draws India's boundaries as the house's (TC-M-25); *Remove kind* keeps its entries | Done, with the browser or phone and the date |

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
Release: <version>  Commit: <sha>  Tag / draft release: <vX.Y.Z, link>  Date: <yyyy-mm-dd>  Run by: <name>
Kind: Play release / public server / web deploy
A automated gate   pass / fail   notes:
B web              pass / fail / n/a
C Android          pass / fail / n/a
D MobSF dynamic    pass / fail / n/a
E server           pass / fail / n/a
F LLM Top 10       pass / fail / n/a   AI evals run: <link>
G privacy          pass / fail
H boundaries       pass / fail
J kinds and spaces pass / fail
I Google Drive     pass / fail / n/a
Findings: <F- ids>   Accepted risks: <02 §7 RR- ids, expiry>
Pentest (Play launch or Drive go-live only): <date, report link>
Decision: ship / do not ship   Owner: <name>
```
