# 15: Google Drive backup, sync, deletion and sharing (design)

| Field | Value |
|---|---|
| Document | Design of Google sign-in for backup, automatic sync, deletion and sharing through each person's own Google Drive (N13 3b, D-28) |
| Version | 0.16 |
| Date | 2026-10-03 |
| Author | Claude (Code), lead |
| Status | **Decided by the owner on 2026-10-02** (§6, §6.1: "Let us implement it. After real world use, we can change as needed."); [03](03-design.md) ADR-33. Built so far: S4b-BL-70, S4b-BL-115, S4b-BL-125, S4b-BL-124 (the runbook incidents, [08](08-operations-runbook.md) IR-11..IR-13), and the **website** version 1 cards (draft PR #118): connect, backups, sync, photos on Wi-Fi, L1 on the site, L2/L3 only with a PRF-sealed passkey whose proof is an HMAC, 8-digit pairing and QR enrolment (paste, or the camera when the browser can scan), non-extractable folder keys, `config.js` from the repository variable `GOOGLE_OAUTH_WEB_CLIENT_ID`. Android and iPhone Drive UI is paused. Version 1 is §1.6; the tickets are S4b-BL-70, -73, -115..119, -121, -122, -124..128 and -130 ([10](10-sprint-log.md) §12.7), deferred S4b-BL-120 and -129; QR on the phones and the phones' HMAC proof stay open with S4b-BL-134 and S4b-BL-135; the order is §7 and [14](14-lead-backlog-and-handoff.md) N17 |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-10-02 | Claude (Code), lead | First version, for the owner's request of 2026-10-02 (backup and restore through Google, automatic or by hand, no data loss, shareable) and the owner's addition of the same day (deleting the data in the backup as the person wants). Goals (§1), where in Drive and the owner's Google Cloud setup (§2), deletion (§3), sharing (§4), data and format (§5), the owner's decisions (§6), the phased plan with tickets and checks (§7), risks (§8). |
| 0.2 | 2026-10-02 | Claude (Code), lead | Three owner additions of the same day: **encryption without a passphrase** (new §9: device keys made silently, a folder key wrapped per device with ECDH P-256 and HKDF, a mandatory recovery-key step skippable after a warning, enrolment by approval or the recovery key, revocation with key epochs, sharing by key cards, the `dpx/1` envelope, vectors; replaces v0.1's "no passphrase" of §5.4); **device authentication for deletes and a required device lock** (new §10: levels L1..L3, the line at L2, the app lock's code path, keys that stop working when the lock is removed, the website's passkey); **photos on Wi-Fi or mobile data** (new §11). §1.3, §1.5, §2.1, §3.2, §5.1, §5.4, §5.5, §5.7, §6 (decisions 3, 6 and 8), §7 (S4b-BL-125..128, TC-M-50..54) and §8 (R10..R12) follow. |
| 0.3 | 2026-10-02 | Claude (Code), lead | Owner addition of the same day, *an authenticator app*: new §10.5 (TOTP, RFC 6238, as an option for the authentication steps only, never for encryption or recovery; the website's second way to L2/L3 next to a passkey; a second check at device approval; an optional extra factor for L3 on the phones); §10.1's table by factor and platform; §10.6, the limit of every gate inside the app; decision 8; S4b-BL-129 and TC-M-55 in §7; R13. |
| 0.4 | 2026-10-02 | Claude (Code), lead | Fixes from an adversarial review: the recovery key as a P-256 key pair whose public key receives every epoch (§9.4); enrolment out of band by QR code with HPKE PSK mode, the commit-then-reveal code fallback, a 10-minute expiry, and no silent adoption of an existing folder (§9.3, §9.5 i); rollback refused by a `revision` counter and the highest epoch kept on each device, files written after a revoke skipped (§9.3, §9.5 iv); a revoke does not revoke Google's grant; *Delete everything* ends the recovery key; local copies rebuild `keys.json`; chained epochs (§9.1); HPKE (RFC 9180) for the wraps, fresh nonces, no content-key reuse, key commitment discussed (§9.2, §9.6); `sync/1` as a new schema (S4b-BL-130); 64-bit key-card fingerprints and the sender from Drive's metadata; every enrolled device fully trusted, with "New device enrolled" notices; the Drive merge rule as last-write-wins on `updatedAt`, not `SyncRules.keepLocal` (§5.1); `state=complete` as the backup marker (§1.4); tombstones vs import; the shrink guard's L1 confirmation and the 7-month photo note; the Android Custom Tab alternative (§5.5); the website's storage eviction; the GIS popup's user gesture and the CSP entries; the overlay-not-root note and §10.6 on the screen; TOTP no longer approves devices; the privacy page's contents and Limited Use; the spike first (§7). New §6.1: six open questions of scope. |
| 0.5 | 2026-10-02 | Claude (Code), lead | **The owner decided** (2026-10-02): the eight decisions of §6 as written and the reviewer's answer to all six questions of §6.1 (the authenticator app cut from v1; website L2/L3 only with a PRF-sealed passkey; sharing deferred until the spike; no monthly mobile-data cap; *Lock old backups again* deferred; Android sign-in by Custom Tab and PKCE unless the spike shows it fails). New §1.6, version 1 and later; §4, §5.5, §5.7, §7, §9.5, §10.1, §10.4, §10.5 and §11 follow; [03](03-design.md) ADR-33. |
| 0.6 | 2026-10-02 | Claude (Code), lead | **S4b-BL-115 built** (the Drive client and the fake Drive, both stacks): new §7.1, the contract details the build had to decide (names, retries and duplicate creates, the listing's consistency, resumable chunks, the late checksum, the error kinds, the token rule, where the token may go, what waits); §7's phase 2 row; S4b-BL-122 gains the website's CORS question. |
| 0.7 | 2026-10-02 | Claude (Code), lead | **S4b-BL-125 built** (the encryption core, both stacks, no screen and no Drive wiring): new §9.9, what it built and the details this design did not decide (key separation under the folder key, the AAD layouts, canonical JSON, the `keys.json` layout and MAC input, the recovery key's text and check symbol, HPKE's ephemeral key from DeriveKeyPair, the error kinds, the limits); §9.4 now says how each platform computes the recovery public key (the platform's own operations, no point multiplication in common code); §7's phase 2c row; the iPhone's provider is S4b-BL-131. |
| 0.8 | 2026-10-02 | Claude (Code), lead | **Fixes from the independent adversarial review of S4b-BL-125** (§9.9): a list is trusted by each device's **pin** of its folder key, not by the MAC (anyone in the Google account could re-wrap a key of their own to every listed public key); the recovery key's **anchor** in `keys.json` (the recovery entry gains `anchorEpoch` and `anchor`); the watermark ordered by (epoch, revision); forks detected; HPKE's ephemeral key from the platform's key generation; a photo opens only against its row's SHA-256; a new recovery key revokes the old kid. |
| 0.9 | 2026-10-02 | Cursor Agent, lead | **Website Drive UI composed** (draft PR #118): connect, recovery key shown once, backups, sync, L1/L2/L3 deletion (website L1; L2/L3 only with a PRF-sealed passkey; tick box; no delay), 8-digit pairing (S4b-BL-126 web; QR deferred as S4b-BL-134), `web/public/config.js` empty in the tree and written at deploy from `vars.GOOGLE_OAUTH_WEB_CLIENT_ID`, privacy link, user-guide pages. Android/iOS Drive UI paused. Real Google sign-in waits on the owner's Web client id. |
| 0.10 | 2026-10-02 | Cursor Agent, lead | **§10.4:** the website's L3 confirm is a tick box and **no countdown** (owner: no delay on the website). The shared delete-policy vectors keep **5 s for L3 on the phones** (Kotlin stays in step with decision 8). The website binds a delete grant to a passkey PRF open; HMAC of the operation id is S4b-BL-135. |
| 0.11 | 2026-10-02 | Cursor Agent, lead | **§10.4:** an L2/L3 grant is registered at **authorize** with that preflight plan's `operationId`. Execute of another plan of the same action (a different `operationId`) is refused. The in-memory grant is gone after a reload; the sealed passkey remains and a new PRF open is required. HMAC of the operation id as the proof is still S4b-BL-135. |
| 0.12 | 2026-10-02 | Cursor Agent, lead | Website Drive cards (draft PR #118): connect, join, backups, delete and sync show **translated reasons** (`TKey`, `problemToMsg` / `msgOfThrown`), never `String(err)` or a raw English sentence from the service. Empty `GOOGLE_CONFIG` is Unavailable with no Connect button. |
| 0.13 | 2026-10-03 | Cursor Agent, lead | Website version 1 gaps on draft PR #118: the 8-digit path enrols with this device's real public key and the HPKE wrap (`approveDevice` / `joinFromWrap`; the recovery key is not required to finish). *Revoke this device* (L2) shows a new recovery key once and says it does not sign the device out of Google. *Disconnect on all devices* (L2) revokes the in-memory GIS token, then drops the local session. A delete that stops with files left shows the count and *Try again*. Sync runs about two minutes after the last local change while Your data is open. Screen copy: backups only while open, the browser-lock warning, the 2-Step Verification sentence, the three disconnect actions, website photo behaviour. QR enrolment stays S4b-BL-134. Non-extractable folder keys (S4b-BL-132) and HMAC deletion proof (S4b-BL-135) stay open. The repository variable `GOOGLE_OAUTH_WEB_CLIENT_ID` is set; a real Google sign-in in the browser is the remaining owner step. |
| 0.14 | 2026-10-03 | Cursor Agent, lead | Website version 1 closed on draft PR #118. **§9.9:** an opened folder key is a non-extractable HKDF `CryptoKey` (S4b-BL-132, website). **§9.5:** the website shows a QR (`dp1.` of `pk_new` ‖ `s`) and the enrolled browser scans it or the person pastes it; the wrap is HPKE PSK mode. The website's `s` is **32 bytes** (RFC 9180's minimum), not the 128 bits of the prose, because a shorter PSK is refused; `psk_id` is `doorprints/dpx1/qr-psk`. `keys.json` device wraps stay base mode. **§10.4:** an L2/L3 proof is an HMAC of `operationId` and `issuedAtMs` under a key from the passkey PRF (S4b-BL-135, website); L1 stays the grant id. Android and iPhone QR and HMAC stay open. A real Google sign-in in the browser is the remaining owner step. |
| 0.15 | 2026-10-03 | Cursor Agent, lead | **S4b-BL-124 runbook half:** the three incidents named in §7 are [08](08-operations-runbook.md) v0.22 IR-11 (the project stopped or a client deleted), IR-12 (a leaked Picker key) and IR-13 (a person who lost access to their Google account). §8 R1 points at IR-11. The guide pages were already on `main`. The privacy page (S4b-BL-121) is unchanged. |
| 0.16 | 2026-10-03 | Cursor Agent, lead | **Website, empty first folder.** A Doorprints folder with no key list and no other file (nothing in the bin either) is an unfinished first connect: the website writes the key list there. A folder that still holds any file is left as it is; the card tells the person to open a device that already connected, or to delete that folder in Google Drive and connect again. A listing Drive marks incomplete is not treated as empty. The phone still refuses the folder (no Drive screen yet). |

**The owner's words (2026-10-02).** "Google Sign-In is to make a secure backup and restore drive and if possible to
make it shareable to others using the same app/website. The backup can be time synced or manual with possibility of
not having to worry about data loss." And, the same day: "we need a method to delete the data in the backup as the
user wants."

**What stays as it is.** D-28 ([11](11-feature-parity-and-export-spec.md) §2): no hosted server; the owner hosts
nothing and holds nobody's data; every person's data goes to their own Google Drive with a client-side OAuth grant.
Zero cost (CLAUDE.md). Local-first (P-2): every feature keeps working without Google; Drive is an extra copy and a
channel, never the only home of the data. The file format is the backup format that exists (`doorprints-backup/1..3`,
[schemas](schemas/README.md)), read through the import checks that exist (`ImportPlan`, the website's
`backup-import`). The brand words of [12](12-brand-and-naming.md) G: *Import a backup* brings a backup in, *Save a
copy* sends one out, never "Restore" on a button.

## 1. Goals and non-goals

### 1.1 Goals

| # | Goal (owner's words) | What it means here |
|---|---|---|
| G1 | "secure backup" | A backup in the person's own Drive, **encrypted on the device** so that only the person's enrolled devices, their recovery key and whoever they share with can read it, not Google (§9); deletes past a level need the phone's own authentication and the feature needs a screen lock (§10); the app asks for the narrowest Drive permission there is (§2); tokens never leave the device; nothing goes to any server of ours. |
| G2 | "restore" | *Import a backup* gets a second source, *Google Drive*: the list of backups there, newest first, then the existing preview, *Merge*, *Keep mine*, *Add everything as new copies*, and the undo. On a new phone: connect Drive, pick the newest backup, import. |
| G3 | "manual" | *Back up to Google Drive now* (Settings > Google Drive), and *Save a copy* gets *Google Drive* as a place to save a Full backup. |
| G4 | "time synced" | *Automatic backup and sync* (§1.3): the person's devices keep each other up to date through Drive, and a dated backup is kept every day, on its own. |
| G5 | "not having to worry about data loss" | The promises of §1.4, said honestly, and what Doorprints cannot promise (§1.5). |
| G6 | "shareable to others using the same app/website" | §4: a person shares their hunt with someone they know, read-only by default, and can stop at any time. **Deferred from v1** (owner, 2026-10-02) until the spike proves Google's Picker on the phones (§1.6). |
| G7 | "delete the data in the backup as the user wants" | §3: delete one backup, all backups, or everything Doorprints keeps in the person's Drive, from inside the app on every platform; deleting really deletes. |

### 1.2 Non-goals

- Any server of ours, a relay, push messages, or an account system (D-28, D-03).
- Reading or writing anything in the person's Drive that Doorprints did not create or that the person did not hand to
  it (no full-Drive scope, §2).
- Real-time co-editing between two people; a shared list both edit at once (§4: later, if ever).
- Google Photos, Gmail, Contacts or any other Google product.
- A backup of the app's settings and keys (the API key, the Gemini key, the app lock): as today, they are never in a
  backup ([12](12-brand-and-naming.md) G.2, "never touches").
- The path trace (S4b-FR-2): never in a backup, never synced ([11](11-feature-parity-and-export-spec.md) 5.27).

### 1.3 By hand, and on its own: when things happen

| Trigger | Android | iPhone | Website |
|---|---|---|---|
| A change (a house saved, a visit, a note) | Sync two minutes after the last change (WorkManager unique work, replaced on each change, any network) | The same while the app is open | The same while the page is open (the existing 3 s debounce becomes 2 min for Drive) |
| The app goes to the background | Sync at once if anything is waiting (expedited work) | Sync in the time iOS gives (`beginBackgroundTask`, about 30 s) | `visibilitychange` to hidden: best effort, may not finish |
| Regularly | Every 6 hours with a network (periodic work) | When iOS wakes the app (`BGAppRefreshTask`, iOS chooses when; may be never) | Every 30 minutes while open (the existing timer) |
| The daily backup | Once a day, battery not low (the backup is small, §5.2; photos are already in Drive) | On the first start or background after 24 h | On the first visit after 24 h, while open |
| Photos | Wi-Fi only by default (*Upload photos only on Wi-Fi*); switched off, mobile data too (no monthly limit in v1); Data Saver and roaming respected; *Upload photos now over mobile data* once (§11) | The same (`NWPathMonitor`: `isExpensive`, `isConstrained`) | Automatically, unless the browser says *save data* or *cellular*; *Upload photos now* (§11) |
| *Back up to Google Drive now* | Any network, photos by the Wi-Fi rule | The same | The same |

**The website's honest limit.** A browser cannot wake a closed website without a push service (D-03, D-07), and
Google gives a website an access token for one hour with no refresh token (§5.6). So the website syncs and backs up
**only while it is open** and connected; after an hour it asks Google again quietly at the next tap. The Periodic
Background Sync API (Chromium, installed sites only) does not help: without a valid token it can do nothing. The
website says so under the switch: "On the website, backups happen only while Doorprints is open."

**Defaults.** *Automatic backup and sync* is **on** once the person connects Drive (they connected it for that), with
photos on Wi-Fi only; one switch turns it off. Decision 6 (§6).

### 1.4 "No data loss": what Doorprints promises

1. **The device is never emptied by Drive.** Drive is an extra copy. No Drive error, full Drive, revoked access or
   deleted folder ever removes anything on the device; only the person's own deletes do (and §3's deletes act on Drive,
   never on the device).
2. **A backup is never overwritten.** Each backup is a new file. It is uploaded with `appProperties`
   `state=partial`, and only after the checksum check (item 3) does a final `files.update` set `state=complete` (and
   the name from `partial-…` to its real one, for the person's eyes; the app goes by `appProperties`, not by names,
   §5.8). Retention, *Import a backup* and the "last backup" line count only `state=complete` files; a partial or
   unmarked file never counts as a backup, and a partial one older than a day is deleted.
3. **Checked after upload.** The manifest carries a SHA-256 per entry (as today); after the upload Doorprints compares
   Drive's own `sha256Checksum` of the file with the one it computed, and downloads and opens the newest backup once a
   week to prove it reads.
4. **Many versions kept.** 7 daily, 4 weekly and 6 monthly backups (17 files, about 1 to 5 MB each without photos);
   decision 5. Retention deletes only finished, checked backups older than the kept set, never the newest good one,
   and **never right after a backup that holds far fewer houses than the one before** (the *shrink guard*: a backup
   with less than half the live houses of the previous one keeps every older backup until the person confirms the
   drop on that device, an L1 confirmation there: "Your newest backup has 3 houses; the one before had 48. Keep the
   older backups?"). A wiped or broken phone therefore cannot push the history out. Each device prunes only from its
   own fresh `files.list` of `Backups/`, so two devices pruning at once only try to delete the same old files, and a
   404 counts as done.
5. **Automatic pruning goes to Drive's bin**, where Drive keeps it for 30 days; a deletion the person asks for does
   not (§3).
6. **Sync conflicts lose nothing for good.** Two devices editing the same house: the later edit wins by
   `updatedAt` (ties broken by device id), the Drive merge rule of §5.1; the other version is still in that day's
   backups (RR-06).
7. **Deletions travel as tombstones and are kept for ever** (an id and a time, about 100 bytes each, for houses,
   visits, records and photos, in each device's `sync/1` file), so **an old device cannot bring a deleted house
   back**. *Import a backup* from Google Drive can, on purpose: it is the person choosing an older state, with the
   preview first. This is a deletion *inside* the data; deleting a *backup* is §3, a different thing.
8. **Preview before anything is written**, and undo after: *Import a backup* from Drive shows the existing preview
   ("*a* new, *b* newer in the file, *c* newer here") and has the existing undo.
9. **Each device writes only its own sync file** (§5.1), so two devices never overwrite each other in Drive.

### 1.5 What Doorprints cannot promise (said in the guide and on the screen)

- Edits made on the website and not yet synced are lost if the browser's storage is cleared before the next sync
  (RR-10); on the website, back up before clearing site data.
- If the person deletes the Doorprints folder in Drive and empties Drive's bin, and also loses the phone, the data is
  gone. Doorprints cannot keep a copy anywhere else (it has no server).
- If the Google account is lost or closed, its Drive goes with it.
- If Drive is full, backups stop (the app says so, once, and keeps everything on the device).
- If Google stops the Doorprints project (§8 R1), the app can no longer reach Drive; the files stay in the person's
  Drive, where they can download them and open them with the Doorprints website and their recovery key (they are
  encrypted, §9.5 vii). (This is why §2 recommends a visible folder, not the hidden app folder.)
- If the person skipped the recovery key and loses every device (or removes the screen lock on their only phone,
  §10.3), the backups in Drive can never be opened again (§9.5 iii).

### 1.6 Version 1, and what comes later (owner, 2026-10-02)

**Version 1:** connect Google Drive (§5.5, §5.6: the website's token model, the iPhone's PKCE flow, Android by a
browser tab with PKCE unless the spike S4b-BL-122 shows it fails) · a screen lock required on the phones (§10.3) ·
encryption on by default with device keys and the recovery key (§9.1..§9.4) · joining a new device by QR code, the
code fallback or the recovery key (§9.5 i) · revoking a device (§9.5 iv, without *Lock old backups again*) · backups
in Drive with *Import a backup* from Drive (§1.4, §5.1) · sync between the person's devices (§5.1) · deleting from
Drive at L1, L2 and L3 with the phone's own authentication (§3, §10); on the website L1, and L2/L3 only where a
passkey with the PRF extension seals the website's key (§10.4) · photos on Wi-Fi only by default, with the switch and
the one-off *Upload photos now over mobile data* (§11).

**Later (deferred by the owner, kept designed):** sharing with someone who uses Doorprints (§4, S4b-BL-120, after the
spike proves Google's Picker on the phones) · the authenticator app (§10.5, S4b-BL-129) · *Lock old backups again*
after a revoke (§9.5 iv) · a monthly limit for photos on mobile data (§11). "After real world use, we can change as
needed."

## 2. Where in Drive, and the owner's Google Cloud setup

### 2.1 The three choices

| | `drive.appdata` (hidden app folder) | `drive.file` (files the app made or was handed) | `drive` (the whole Drive) |
|---|---|---|---|
| Google's class | Non-sensitive | Non-sensitive | **Restricted** |
| Google's review | None (brand verification only, to show the name and logo) | None (the same) | Full verification **and a yearly security assessment by an approved assessor** (CASA), paid: excluded by the zero-cost rule |
| The person can see the files | No (only its size, under Drive's settings > *Manage apps*) | Yes, in a normal *Doorprints* folder | Yes |
| Shareable | **No** (Drive refuses permissions on app-folder files) | Yes, with Drive's own sharing | Yes |
| Readable without the app | No | Yes, by download (with §9's encryption: with the Doorprints website and the recovery key) | Yes |
| If Google stops the project | **Unreachable for ever** | Still in the person's Drive | Still there |
| Mobile Google Picker (§4) | Not possible | The only scope it allows | Not allowed with it |
| Can the person break it | No | Yes, by moving or deleting files (handled: §5.8) | Yes |

### 2.2 Recommendation: `drive.file` alone

One scope, `https://www.googleapis.com/auth/drive.file`, and nothing else: no `openid`, `email` or `profile` (the
account shown in Settings comes from Drive's own `about.user`), no `drive.appdata`. It is non-sensitive, so no
review is needed beyond brand verification; it is the only scope Google's Picker for phones accepts; the files outlive
the app; and they can be shared. This **amends D-28 (1)**, which put the sync in `drive.appdata`; the cost is that a
person can move or delete the files by hand, which the app handles (§5.8). Decision 1.

Google's own page (*Choose Google Drive API scopes*, read 2026-10-02) lists `drive.file`, `drive.appdata` and
`drive.install` as non-sensitive and recommended, and `drive`, `drive.readonly` and `drive.metadata*` as restricted.
Re-read it at setup.

### 2.3 Verification, testing and the 100 users

- **Only non-sensitive scopes: verification is not mandatory.** To show the name *Doorprints* and the logo on
  Google's consent screen, Google asks for **brand verification**, which is free and takes a few working days. It
  needs a home page, a privacy policy on the same domain (S4b-BL-121: the website has none yet), the domain's
  ownership proved in Google Search Console (the landing page already carries the Search Console tag, TC-M-40), and
  a support contact.
- **Testing status**: only test users listed by email (at most 100) can connect, and Google's refresh tokens for an
  app in testing **expire after 7 days** (Google's OAuth 2.0 page), so the iPhone would ask again every week and the
  website is unaffected (it has no refresh token anyway). Good for the build and the TC-M checks.
- **In production**: anyone can connect; with only non-sensitive scopes there is no user cap (the 100-user cap is for
  unverified apps asking for sensitive or restricted scopes). Decision 7: publish when the release gate passes.
- **Other Google limits that matter:** at most 100 refresh tokens per Google account per client (the oldest goes
  quietly); a refresh token unused for six months stops working; the person can remove Doorprints at
  myaccount.google.com at any time (§3.4).

### 2.4 The owner's setup checklist (Google Cloud Console, free, no billing account)

Do this in the Google Cloud project `doorprints` (the Firebase project; the Spark plan stays). Google renamed the
*OAuth consent screen* to **Google Auth Platform** (Branding, Audience, Data access, Clients); the steps below use
those names.

- [ ] **APIs**: *APIs & Services > Library*: enable **Google Drive API**, and **Google Picker API** (for sharing, §4).
      Neither needs billing.
- [ ] **Branding**: app name *Doorprints*; user support email (an address you choose for this; it is shown to users;
      do not use one you want private); logo 120 x 120 px PNG (`web/public/icons`; adding it starts brand
      verification); home page `https://doorprints.web.app`; privacy policy `https://doorprints.web.app/privacy.html`
      (S4b-BL-121 first); authorised domain `doorprints.web.app`; developer contact email.
- [ ] **Search Console**: the site verified there (TC-M-40, the tag is already on the landing page) by the same
      Google account that owns the Cloud project, so brand verification can see it.
- [ ] **Audience**: user type **External**; status **Testing** while building; add yourself and the testers (at most
      100) as test users. Later *Publish app* (decision 7).
- [ ] **Data access**: add the one scope `.../auth/drive.file`. Nothing else.
- [ ] **Clients**, three (all free; none has a secret that the apps use):
  - [ ] *Web application* "Doorprints website": authorised JavaScript origins `https://doorprints.web.app` and, for
        local work, `http://localhost:4200`. No redirect URI (the website uses Google's popup token flow). Google
        shows a client secret: **do not copy it anywhere**; the website never uses it.
  - [ ] *Android* "Doorprints Android": package `app.doorprints`; the **SHA-1 of the release signing certificate**
        (`keytool -list -v -keystore <release keystore> -alias <alias>`; if you ever use Play App Signing, add
        Play's app-signing SHA-1 as a second Android client). A second Android client "Doorprints Android debug" with
        the SHA-1 of the debug key you build with (`keytool -list -v -keystore ~/.android/debug.keystore -alias
        androiddebugkey -storepass android`). CI's debug APKs are signed by a fresh key per runner, so sign-in is
        tested in CI only against the fake (§7), never against Google.
  - [ ] *iOS* "Doorprints iPhone": bundle ID `app.doorprints` (ad-hoc builds use the same id); Google shows the
        client id and its *iOS URL scheme* (the reversed client id).
  - [ ] *Picker*: the Picker on the website needs a **browser API key** (*Credentials > Create credentials > API
        key*), restricted to *Websites* `https://doorprints.web.app/*` and to the *Google Picker API* only, and the
        project **number** (the Picker's app id). A browser key is public by nature (it ships in the page); the
        restrictions are what protect it. On the phones the Picker uses the Android or iOS client (§4.3).
- [ ] **Tell the session** the three client ids, the iOS URL scheme, the Picker key and the project number. They
      are **not secrets**: they go into the apps' configuration (the website's build configuration, Android's
      `BuildConfig` from a Gradle property, the iPhone's `Info.plist`), never into the docs. Until then everything is
      built and tested against the fake Drive.
- [ ] Later, for the phones' link back from the website (§4.3): `assetlinks.json` (Android App Links, the release
      SHA-256) and `apple-app-site-association` on Firebase Hosting; free.

**What the owner supplies is configuration, not credentials.** The native apps use **PKCE** (RFC 7636) and no client
secret; the website uses Google's token model, which has no secret either. No owner key, token or secret ever enters
the repository, CI, or a user's device other than the person's own tokens.

## 3. Deleting the data in Drive (owner's addition, 2026-10-02)

A first-class feature on the website, Android and iPhone, in Settings > Google Drive, and on each row of *Import a
backup > Google Drive*.

### 3.1 What can be deleted

| Action (label) | What goes | What stays |
|---|---|---|
| **Delete this backup** (on a backup's row) | That one backup file | The other backups, the sync files, the photos, everything on every device |
| **Delete all backups** | Every file in `Doorprints/Backups` | The sync files (the devices keep syncing), the photos, every device's data |
| **Delete everything Doorprints keeps in my Google Drive** | The whole `Doorprints` folder: backups, sync files, photos, the person's own shared files (§4; sharing with others stops), and `keys.json` (so the recovery key stops working; connecting again makes a new key set and a new recovery key, §9.4) | Each device's own data (the houses on this phone stay); copies others already imported from a share; copies saved elsewhere with *Save a copy* |

The deletion of a house **inside** the data (a tombstone that syncs, §1.4 item 7) is a different thing and keeps its
existing words; these actions delete **files in Drive**.

### 3.2 The confirmation (plain words; all four languages, hi/ta/te *under review*)

One dialog, the same on every platform, saying what goes, that it cannot be undone, and what stays. Example for the
largest one:

> **Delete everything Doorprints keeps in your Google Drive?**
> This deletes 14 backups, the sync files of 3 devices and 212 photos (1.4 GB) from your Google Drive. They are
> deleted for good, not moved to Drive's bin, and cannot be brought back.
> The houses on this phone stay. Your other devices keep their own houses but stop syncing. People you shared with
> keep what they already imported; they get no more updates.
> Your recovery key will stop working. If you connect Google Drive again, you get a new one.
> [ ] I understand this cannot be undone
> **Save a copy first** · **Cancel** · **Delete for good**
>
> (then the phone's own check: "Confirm it's you to delete", §10)

- *Save a copy first* opens *Save a copy* with a Full backup to the device, then comes back to the dialog.
- *Delete for good* is enabled only once the box is ticked and, **on the phones**, 5 seconds have passed since the
  dialog opened (it counts down in its label for screen readers too). **On the website there is no countdown** (owner:
  a tick box only; §10.4). No typed word: typing a word is hard on Indic keyboards and for some people (decision 8).
- *Delete this backup* has the same dialog without the box (one file, and the other backups stay).
- **Device authentication** after *Delete for good* for every L2 and L3 action (§10.1): *Delete all backups*, the
  last backup, *Stop sharing*, *Delete everything*. Cancelled or failed: "Nothing was deleted."
- Never "Restore", never "Reset" on these buttons; *Delete* is the verb.

### 3.3 How it deletes, honestly

- **Permanently, not to the bin**: `files.delete` (Drive's permanent delete for files the person owns), file by file,
  children before the folder. A deliberate deletion that left everything in Drive's bin for 30 days would surprise the
  person. Automatic pruning (§1.4 item 5) is the only thing that uses the bin.
- **Where it may linger, said in the guide:** Google removes deleted data from its own systems on its own schedule
  (Google's privacy policy describes it); Doorprints cannot speed that up. Drive's revisions of a file go with the
  file. Copies on the person's other devices, in *Save a copy* files and in what others imported stay where they are.
- **Resumable and idempotent**: the app writes the list of file ids to delete on the device first, then deletes; a
  404 counts as deleted. If it stops half way (no network, app closed) it says what is left ("12 of 52 files are still
  in your Drive. Try again") and *Try again* finishes the list.
- **Offline: refused, not queued.** "You are offline. Deleting from Google Drive needs a connection; nothing was
  deleted." A delete that ran by itself hours later would surprise the person.
- **Shared files first**: before deleting a file it shared, the app removes the people's access (`permissions.delete`)
  so a half-finished delete never leaves a shared file behind.

### 3.4 Afterwards: nothing comes back by itself

- After **Delete all backups**, automatic backups are **off on this device** and the folder's control file
  (`doorprints.json`, §5.1) records `backupsDeletedAt`; every other device that reads it turns its own automatic backup
  off and asks: "Backups in your Google Drive were deleted on <date>. Start backing up again?" Sync goes on.
- After **Delete everything**, this device disconnects from Drive. Another device that finds the folder gone (or
  its own sync file gone while it remembers one) **stops and asks**: "Your Doorprints data in Google Drive was deleted.
  Back up this device to Drive again?" It never re-creates the folder quietly. (The Drive form of S4b-BL-20's "server
  was reset", with the opposite default.)

### 3.5 Three different things, in plain words

| Action | What it does | Where |
|---|---|---|
| **Disconnect Google Drive** | This device stops syncing and backing up. Nothing is deleted anywhere; your other devices go on. | Settings > Google Drive, on each device |
| **Remove Doorprints' access to your Google account** | Every device loses access at once (they show "Google Drive disconnected"). Your files stay in your Drive. | *Disconnect on all devices* in the app (it revokes the grant at Google), or Google's own page *myaccount.google.com > Security > Your connections to third-party apps & services* |
| **Delete your Doorprints data from Google Drive** | §3.1. Your devices keep their houses. | Settings > Google Drive |

For a full erasure the guide gives the order: delete the data from Drive, then remove the access, then *Remove all
data* on each device. This is the person's right to erasure exercised directly: Doorprints never received the data,
so there is no request to make to anyone ([13](13-release-security-checklist.md) G3).

## 4. Sharing with someone who uses Doorprints (deferred, not in v1: owner, 2026-10-02)

Deferred until the spike S4b-BL-122 proves Google's Picker on the phones (§6.1 question 3); the design below is
kept for then. Until then, sharing stays the update file of ADR-27, sent through any app.

### 4.1 The options

| | (a) Share a Drive folder | (b) A shared "hunt" both write | (c) A read-only file ("outbox") per person |
|---|---|---|---|
| What | The person shares their Doorprints folder (or a sub-folder) with another Google account | One folder both write into; the apps merge with the S4b-FR-2/-3 rules | Each person's app keeps one file of what they share (the update-file format of ADR-27, with deletions, ADR-29) and shares **that file** with the other account as *viewer*; each app imports the other's file |
| Works with `drive.file` | Only if picking a folder through the Picker gives access to the files in it, which Google does not document (spike S4b-BL-122) | The same, plus both must write in the other's folder | **Yes**: the other person picks one file once with the Picker; the file keeps its id while its owner updates it |
| Conflicts | — | Two writers on one file: Drive has no lock | None: each writes only their own file |
| Least privilege | Too wide (the whole backup and photos) | Editor rights | Viewer only: neither can change or delete the other's data |
| Revocation | Drive's sharing | Drive's sharing | *Stop sharing* removes the permission |

### 4.2 Recommendation: (c) in the first version, the rest later

**Version 1 (S4b-BL-120): *Share my hunt with…***

1. Settings > Google Drive > *Share my hunt with…* asks for the other person's Google email, a name to call them
   (the `share_contacts` name of 5.28), what to include (all houses or the shortlist; contact details off by default
   with the existing warning; photos off by default, decision 4), and shows the consent text (below).
2. The app writes `Doorprints/Shared/Doorprints-shared-<random>.dpx`, encrypted for the other person's key card
   (§9.5 v: before the first share, the other person sends theirs by a Doorprints link or QR code, and the two compare
   its short code), an update file (`doorprints-backup/1..3` with
   `sharedTo`), as a **full current state of what is shared plus its deletions** (not only the changes, so a missed
   update is never lost), and gives the other account *viewer* access (`permissions.create`; Google sends its own
   email with a link). It updates the same file in place after each sync, by the §1.3 rules.
3. It also offers a Doorprints link to send on any app (`https://doorprints.web.app/shared#file=<id>`), which opens
   step 4 directly.
4. The other person, in their own Doorprints: *Add a shared hunt* → Google's Picker opens on that file
   (`setFileIds`) → they pick it once → their app imports it with the existing preview the first time ("Updates from
   ravi@example.com for Priya": the sender is the **Drive file owner's email from Drive's metadata**, never a name the
   file declares about itself) and then merges it quietly at each sync (last edit wins, ADR-27; deletions only from an update
   file, ADR-29). The houses they import become theirs, in their own data and backups.
5. Two-way sharing is the same thing done by both people.

**Later (only after the spike, S4b-BL-122):** a shared folder with photos as separate files (option a/b); a
read-only link for someone without Doorprints is already covered by *Save a copy* (a readable copy) and stays so.

### 4.3 How each platform picks the file

- **Website**: Google's Picker (`gapi` `picker`, `DocsView.setFileIds`), with the browser key and the project number
  (§2.4). The picked file is then readable by the Doorprints project for that Google account.
- **Android and iPhone**: Google's Picker for mobile apps opens in the browser, with the `drive.file` scope only (it
  "can't be combined with any other scope", which is why §2.2 asks for no other), and returns the picked ids to the
  app's redirect. If the spike finds it unusable on one platform, the fallback is the website: pick once there with
  the same Google account; because the grant belongs to the project, the phone can then read the file (also confirmed
  by the spike).

### 4.4 Privacy, consent and revocation

- **The consent text** (before the first share): "Priya's Google account will be able to see the houses you share:
  their addresses, prices, notes and ratings{, the contact details}{, and photos}. She can keep what she imports.
  Stop sharing at any time in Settings > Google Drive; she then gets no more updates, but keeps what she already has."
- **What the other person sees**: what was chosen, nothing else (no visits without a house, no path trace, no
  settings, no keys). Contact details only if ticked (PRV-012).
- **Stop sharing with Priya**: removes her access to the file and deletes the file (§3.3); what she imported stays
  with her, and the dialog says so.
- **Remove a hunt shared with me**: this app stops reading it; the houses already imported stay until deleted by hand
  (the person can also *Delete the houses from this hunt*, which deletes the houses that came only from it and were
  not edited here).
- **Delete the shared data for everyone**: not possible, and the app does not pretend: the owner of the file can
  delete their file (and so the source of updates), never what the other person imported.

## 5. Data and format

### 5.1 The layout (decision 2: snapshot files per device, not a file per record)

```
My Drive/
  Doorprints/                              (created by the app; appProperties {doorprints: "root"})
    doorprints.json                        (control file, MACed: format, encryption, createdAt, backupsDeletedAt)
    Read me.txt                            (four lines in four languages: what the folder is, do not edit)
    keys.json                              (enrolled devices' public keys and wrapped folder keys, MACed, §9.3)
    Backups/Doorprints-backup-2026-10-02-0930.dpx   (daily and by hand; "partial-" while written)
    Sync/device-<random>.dpx               (one per device, written only by that device)
    Photos/p-<random>.dpx                  (written once, never changed; appProperties: a random id only)
    Shared/Doorprints-shared-<random>.dpx  (§4, one per person shared with)
```

- **The sync file** (`sync/1`, a **new inner format**: its schema goes into docs/schemas with S4b-BL-130) of each
  device is its whole state: houses, visits, records, photo metadata with the Drive file id, and the tombstones of
  houses, visits, records and photos, gzipped: about 100 to 500 KB for a few hundred houses. On each sync a device
  writes its own file if anything is dirty, lists the folder once (`files.list` with `sha256Checksum`,
  `modifiedTime`, `appProperties`), reads the other devices' files whose checksum changed since last time, validates
  each like an import (docs/schemas §6: every Drive file is untrusted input) and merges row by row. The cursor is the
  map of device id to checksum.
- **The Drive merge rule is last-write-wins on `updatedAt`, ties broken by the writing device's id, with tombstones
  compared the same way** (a delete wins over an older edit and loses to a newer one). It is **not**
  `SyncRules.keepLocal`, the server sync's rule, which lets any incoming row overwrite a clean local row: with the
  server that is right (the server already merged), but with per-device files an older snapshot from a device that
  has not caught up would overwrite newer data. Each edit stamps `updatedAt = max(now, previous updatedAt + 1)`, so a
  device whose clock is behind still moves a row forward. Vectors (Kotlin and TypeScript): three devices editing and
  deleting the same house in different orders converge; a stale snapshot from a device offline for a month changes
  nothing newer; one sync file rolled back by a Drive revision changes nothing newer (and is reported, §9.6).
- **Why not one shared file**: Drive has no compare-and-swap on update, so two devices writing one file would lose
  each other's changes. **Why not a file per record**: thousands of calls, slow, near the rate limits. If the sync file
  grows past about 1 MB compressed, a later step adds per-device change segments with compaction.
- **The backup** is a normal `doorprints-backup` ZIP (manifest with SHA-256, `data.json`, the HTML copy) **without
  the photo bytes**: each photo row carries its `driveFileId` and SHA-256, and *Import a backup* from Drive fetches the
  photos from `Photos/` (the "Drive-only photos" case 5.2 already names). A Full backup with photos inside stays
  available by hand (*Save a copy* > Google Drive). Whether the photo reference needs a new format number is checked
  against the versioning rule (docs/schemas §1.1) in S4b-BL-116: an older reader reports the photos as missing,
  which is visible, not silent.
- **A photo is deleted from `Photos/`** only when it is a tombstone older than 30 days **and** no kept backup refers
  to it. With the 6 monthly backups that means a deleted photo leaves Drive within about 7 months; Settings says so
  ("Photos you delete leave your Google Drive within 7 months, when no kept backup needs them"), and *Delete all
  backups* or *Delete everything* removes them at once.
- **The website**: one tab syncs at a time (`navigator.locks`), so two tabs of one browser are one device. Its keys
  and data live in the browser's storage, which the browser may clear (Safari after 7 days without a visit for a site
  that is not on the Home Screen; any browser under storage pressure): at connect the website asks
  `navigator.storage.persist()` and says honestly "This browser may clear Doorprints' data and keys if you do not use
  it for a while. Your backups stay in Google Drive; to use them here again, scan the code from your phone." The easy
  way back is the QR enrolment of §9.5 i.

### 5.2 Size, quota and limits

- The space is the person's own Drive (15 GB free, shared with Gmail and Photos). The app reads `about.storageQuota`
  before uploading photos and shows "Doorprints uses 1.4 GB of your Google Drive" in Settings.
- Full Drive (`storageQuotaExceeded`): photo uploads pause, the small backups and sync go on while they fit, one
  notice; nothing local is touched.
- Uploads: multipart up to 5 MB, resumable above (photos are about 0.2 to 2 MB after the app's resize).
- Rate limits (403 `userRateLimitExceeded`, 429) and 5xx: exponential backoff with jitter, honouring `Retry-After`,
  at most five tries, then the next trigger. Doorprints' traffic is a few calls a minute at most, far below Google's
  per-user limits.

### 5.3 The offline queue

Nothing new: the dirty flags of the local stores are the queue (as for the server sync). WorkManager retries on
Android; the iPhone and the website retry at the next trigger. A device offline for months merges correctly when it
comes back, because tombstones are kept for ever.

### 5.4 Encryption (decision 3)

v0.1 recommended no passphrase in version 1. The owner asked on 2026-10-02 for public/private-key encryption without
user intervention: **§9** is that design, on by default, with a mandatory recovery-key step. A passphrase stays
rejected: typed often, forgotten easily, and a forgotten one is total loss. Argon2id is not needed: every key in §9 is random, none
is made from a password.

### 5.5 Tokens: where they live

| Platform | How the app gets access | What is kept | Where |
|---|---|---|---|
| Website | Google Identity Services, token model (`google.accounts.oauth2.initTokenClient`, popup) | The access token (one hour) | **Memory only**, never `localStorage`; lost on reload, asked again quietly (`prompt: ''`) at the next tap |
| Android (**default**, owner 2026-10-02, unless the spike S4b-BL-122 shows Google refuses it) | The system browser (`ACTION_VIEW`, no new library; a Custom Tab only if `androidx.browser` is ever added for another reason) with authorisation code and **PKCE**, the redirect back by an App Link on `doorprints.web.app` (the spike confirms which client type Google accepts for it); no `play-services-auth` | The refresh token | Sealed by a Keystore AES-GCM key bound to the screen lock (as the iPhone's Keychain item, §10.3) |
| Android (fallback, only if the spike shows the default fails) | Google Play services `Identity.getAuthorizationClient(…).authorize(…)` with `drive.file` (`play-services-auth`, a new dependency, covered by `NOTICE`'s section 7 permission) | Nothing: Play services keeps and refreshes the grant | Memory only |
| iPhone | `ASWebAuthenticationSession` (the platform's own), authorisation code with **PKCE**, the iOS client, redirect to the reversed client id; no Google SDK | The refresh token | **Keychain**, `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` (not in iCloud Keychain, not in a device backup, deleted by iOS if the passcode is removed, §10.3; so background sync runs only while the phone is unlocked); the access token in memory |

- `AppSettings.toString()` and every log stay free of tokens (S4b-BL-29's rule).
- **Decided (open question 6, 2026-10-02):** the browser with PKCE, for no Google library, one OAuth model with the
  iPhone, and phones without Google Play services included. Its costs: Google's Android guidance prefers its own
  library, Google may refuse the redirect for an Android client (the spike checks), and the app keeps a refresh token
  itself (sealed, as above). If the spike shows it fails, Play services is the fallback and phones without it get no
  Drive in version 1.
- `invalid_grant` or a revoked grant: "Google Drive disconnected. Your houses are safe on this device. Connect
  again?" Nothing local is deleted, queued work waits.

### 5.6 The refresh model, per platform

- **Website**: no refresh token exists in Google's token model; the app asks again when the hour is over, quietly if
  the person is still signed in to Google and has agreed before (a popup may flash; a browser that blocks it shows a
  *Connect again* chip). The popup needs a **user gesture** (a tap), so the request is made from the next tap, never
  from a timer, and it may fail where third-party cookies are blocked; then *Connect again* opens it from a tap. This is
  the website's honest limit of §1.3. The CSP and header change is S4b-BL-73: `script-src https://accounts.google.com/gsi/client
  https://apis.google.com`, `frame-src https://accounts.google.com/gsi/ https://docs.google.com`, `connect-src`
  already `https:` (Google's token and Drive endpoints), `style-src https://accounts.google.com/gsi/style`, and
  `Cross-Origin-Opener-Policy: same-origin-allow-popups`; its exit includes one live UI run (N8).
- **Android**: Play services refreshes; the app calls `authorize` each time, and it returns at once without a screen
  while the grant holds.
- **iPhone**: the refresh token at Google's token endpoint with PKCE and no secret; refresh before each sync if the
  access token has under 5 minutes left. In Testing status the refresh token lasts 7 days (§2.3).

### 5.7 What the screens say (labels; hi/ta/te *under review*)

Settings > **Google Drive**: *Connect Google Drive* (Google's own button rules: the Drive name and logo as Google
allows; it is not *Sign in with Google*, because Doorprints keeps no account, only Drive access) · the account's email
· *Automatic backup and sync* (on/off) · *Upload photos only on Wi-Fi* (*Upload photos now over mobile data*, §11) · *Devices* (the enrolled devices, *Revoke this device*, *Approve a new device*) · *Recovery key* (saved, or *Make a recovery key*) · *Back up to Google Drive now* · "Last
backup: today 09:30, checked" · *Backups in Google Drive* (the list, each with *Delete this backup*) · *Disconnect Google Drive* · *Disconnect on all devices* · *Delete all backups* ·
*Delete everything Doorprints keeps in my Google Drive*. *Import a backup* gets *From this device* and *From Google
Drive*. *Save a copy* gets *Save to Google Drive*. Never "Restore" (12 G.3 rule 3); "bring back" only in explanations.

### 5.8 When the person changes things in Drive by hand

A file moved out of the folder, renamed, or deleted: the app finds its files by `appProperties` and the folder id,
not by name. A missing backup is simply not in the list; a missing photo is "not in your Drive any more" and uploaded
again from the device if the device has it; a missing sync file of another device is that device gone; the whole
folder gone is §3.4's question. A file edited by hand fails the import checks and is skipped with a message.

## 6. The owner's decisions (decided 2026-10-02)

The owner agreed to all eight as written, with decision 3 (encryption on by default, silent device keys, a mandatory
recovery key skippable only after a warning) and decision 8 as amended by §6.1 questions 1 and 2.

| # | Decision | Recommendation | Cost of the alternative |
|---|---|---|---|
| 1 | Which Drive scope | **`drive.file` only**, a visible *Doorprints* folder (amends D-28's `drive.appdata`) | `drive.appdata`: files hidden, not shareable, unreadable without the app and lost for ever if Google stops the project. Full `drive`: Google's restricted-scope review and a paid yearly security assessment (breaks zero cost) |
| 2 | How devices sync through Drive | **One sync file per device plus dated backups** (S4b-BL-70's question) | One shared file: two devices overwrite each other (no lock in Drive). A file per record: thousands of calls, slow, near rate limits |
| 3 | Encryption | **On by default, with device keys made silently (no passphrase) and a mandatory recovery-key step at the first connect, skippable only after a plain warning** (§9) | Off by default: Google (and anyone in the Google account) can read the backups. Recovery key with no skip: some people will not connect at all. No recovery key at all: losing every device (or the lock on the only phone) loses the backups for good. A passphrase: typed often, forgotten, total loss |
| 4 | Sharing in version 1 | **A read-only file per person, shared with one Google account; photos off by default** (opt-in, up to 50 MB) | A shared folder both write: depends on undocumented Picker behaviour and gives editor rights. No sharing in version 1: the owner's "shareable" waits |
| 5 | How many backups to keep | **7 daily, 4 weekly, 6 monthly** (about 17 small files) | "The last 4" of 5.2: a mistake noticed after four days cannot be undone from Drive |
| 6 | Automatic backup and sync after connecting; photos on mobile data | **Decided: on; text and backups on any network; *Upload photos only on Wi-Fi* on, switchable to mobile data, plus a one-off *Upload photos now over mobile data*** (§11); the monthly limit deferred (§6.1 question 4) | Off: people who connect and forget have no backup. Photos on mobile data by default: a surprise data bill. No switch: photos never leave a phone that is never on Wi-Fi |
| 7 | Google's publishing status | **Testing while building; production with brand verification once the release gate passes** (needs the privacy page, S4b-BL-121) | Staying in Testing: only 100 named people, and the iPhone asks again every 7 days |
| 8 | Confirming a deletion, and where device authentication starts | **Decided: the dialog for L1; the dialog and the phone's own authentication for L2 and L3** (all backups, the last backup, revoke or approve a device, everything); a tick box and a 5-second delay for L3; no typed word. **On the website L1, and L2/L3 only where a passkey with the PRF extension seals the website's key**, otherwise "use your phone" (§10.4; §6.1 questions 1 and 2) | Authentication for every delete: tiresome for one old backup, and people learn to tap through it. Only for *Delete everything*: deleting all backups would need no proof. A typed word: hard on Indic keyboards. A check in the page without PRF: anyone with the browser's developer tools skips it |

### 6.1 Questions raised by the review (closed: the owner took the reviewer's recommendation on all six, 2026-10-02)

An adversarial review of v0.3 (2026-10-02) found the defects fixed in v0.4 and raised six questions of scope. The
owner agreed to the reviewer's recommendation on each; §1.6 is the result. Question 6 is recorded as: the browser with
PKCE by default, Play services only if the spike shows that fails.

| # | Question | Reviewer's recommendation | For | Against |
|---|---|---|---|---|
| 1 | Cut the authenticator app (S4b-BL-129) from version 1? | **Cut it; defer S4b-BL-129** | On a phone it is a second factor on the same phone that already asked for its own lock; on the website the check runs in the page, so anyone with the browser's developer tools can skip it; less code and fewer screens | The owner asked for it as an easier way; without it, the website without a passkey stays at L1 (question 2) |
| 2 | Website L2/L3 only where the WebAuthn PRF extension seals the device key; otherwise the website is L1 only and says "use your phone"? | **Yes** | With PRF the check guards a real key (no passkey, no key); without it, a check in the page is theatre against anyone with developer tools | Many browsers and computers lack PRF today, so many website users cannot delete all backups there and must use a phone |
| 3 | Defer sharing v1 (S4b-BL-120) until the spike proves Google's Picker for mobile apps? | **Defer** | No build on an unproven Picker; the file sharing of ADR-27 already works without Drive | The owner's "shareable" waits for the spike's answer |
| 4 | Drop the monthly mobile-data limit for photos from version 1 (keep the Wi-Fi switch and the one-off button)? | **Drop it** | One setting fewer, no counter to keep per month; the switch and the one-off cover the owner's ask | A person who turns Wi-Fi only off has no ceiling on data use |
| 5 | Defer *Lock old backups again* after a revoke? | **Defer** | Rewriting backups is extra code and risk; the old epoch only matters to someone who has both the lost phone and the Google account | Until then, files from before a revoke stay readable to that phone's key |
| 6 | Android sign-in: Google Play services (`play-services-auth`) or a Custom Tab with PKCE (§5.5)? | **Ask the spike first**; the reviewer leans to Custom Tab + PKCE | Custom Tab: no Google library, one OAuth model with the iPhone, works without Play services | Play services: Google's recommended path, no refresh token for the app to keep, and the Custom Tab redirect for an Android client may not be accepted |

## 7. The plan: phases, tickets, checks

Each phase is one pull request (or a few), tested on Linux CI against **a fake Drive** (`InMemoryFakeDrive`, built in S4b-BL-115, §7.1: an in-memory
Drive with files, folders, `appProperties`, checksums, permissions, quota, and injected 401, 403 rate-limit, 404,
429 and 5xx answers, and a "stop after N calls" switch), in Kotlin common code and TypeScript, with shared vectors
where the rules are shared. Nothing in CI talks to Google. The device and real-Drive checks for version 1 are TC-M-46..TC-M-48 and TC-M-50..TC-M-54 ([06](06-test-plan.md)),
for the owner at the end (N15 step 6's rule); TC-M-49 and TC-M-55 wait with their deferred features. **The build
order is final** (owner, 2026-10-02); the rows below are in that order, the deferred ones at the end.

| Phase | Ticket | What | Tests on Linux | Owner or device |
|---|---|---|---|---|
| 0 | this change, S4b-BL-74 | This design; docs/13 re-scoped for client OAuth and Drive; docs/02 §10; ADR-33 | `licence-headers --check` | Decided 2026-10-02 |
| 0s | **S4b-BL-122** | **The spike, first after the owner's §2.4 checklist and before the crypto phase**, with the owner's clients in Testing: is a `drive.file` grant visible across the Android, iOS and web clients of one project? Does Google's Picker for mobile apps return to the Android and iOS apps, and does a picked folder give its files? Does revoking one client revoke all? Are `sha256Checksum`, `appProperties` and `files.list` consistent right after a write? Does Google's CORS expose `Location` and `Range` to the website for a resumable upload (§7.1)? Does the 7-day testing expiry also hit the Play-services grant? Does Google accept the browser-and-PKCE redirect for Android (the default of open question 6; if not, Play services)? | — | Owner's client ids (§2.4) |
| 1 | **S4b-BL-70** | The `SyncBackend` seam on both stacks: push, pull since an opaque cursor, photos, "is it behind"; today's code becomes `ServerSyncBackend`, no behaviour change | Existing sync tests through the seam; a `FakeSyncBackend` | — |
| 2 | **S4b-BL-115** (**built**, §7.1) | `DriveClient` (Ktor `HttpDriveClient` in `:shared`, `FetchDriveClient` on the website: list, create multipart and resumable, get, update, download, delete and the bin, revisions, about), error mapping, backoff; `InMemoryFakeDrive` with a fault script. Permissions wait for sharing (S4b-BL-120) | The contract run on both clients of each stack (the HTTP one over the fake behind Drive's HTTP surface); `docs/schemas/drive-vectors.json` (queries, exchanges, errors, backoff) on both | — |
| 2c | **S4b-BL-125** (**built**, §9.9), **S4b-BL-126** | Encryption (§9): the `dpx/1` envelope, HPKE and the primitives per platform, the device keys, `keys.json` with its MAC, `revision` and rollback check, chained epochs, the local copies that rebuild it; then the first-connect rule, enrolment by QR (HPKE PSK mode) and by the code fallback, the recovery key and its public key, revocation (files after a revoke skipped) and revocation without *Lock old backups again* (deferred); the share key and key cards wait with sharing. Before any file is written to Drive | Known-answer and RFC 9180 vectors, shared Kotlin/TypeScript envelope vectors, tamper, downgrade and rollback tests, enrolment, revocation and epoch-chaining tests on the fake, the Android 26-30 software-key row | TC-M-52, TC-M-53 |
| 3 | **S4b-BL-116** | Backups in Drive: the folder and control file, the backup writer without photo bytes, `partial-` and rename, the checksum check, retention with the shrink guard, *Back up to Google Drive now*, *Import a backup* > *From Google Drive* through the existing preview | Fake: partial never listed, retention keeps 17, shrink guard, a corrupted file refused, import vectors | — |
| 4 | **S4b-BL-117**, **S4b-BL-73** | Connecting per platform: the website's GIS token client with the CSP and COOP change and the self-hosted Noto subsets (one live UI run), Android's AuthorizationClient, the iPhone's PKCE flow and Keychain; Settings > Google Drive (connect, account, disconnect, disconnect on all devices) | Token handling against fakes; CSP and header tests; the iOS klib compile | TC-M-46 |
| 4b | **S4b-BL-127** | The device lock (§10.3): required to switch Drive on, checked before every run, the pause and its words, the auth-bound Keystore keys and `WhenPasscodeSet` Keychain items; the delete levels and device authentication (§10.1, §10.2) with the operation-bound signature on Android 11+; the website's passkey, used for L2/L3 only where PRF seals the website's key | A fake authenticator: passed, denied, cancelled, timed out, lock removed half way; a fake lock state: no lock refuses connect, a removed lock pauses; the emulator test by `AppLockEmulatorTest`'s rules | TC-M-50, TC-M-51 |
| 4d | **S4b-BL-130** | The `sync/1` inner format (§5.1): its schema in docs/schemas (houses, visits, records, photo metadata and every tombstone; the writer's device id; caps), its validation as untrusted input on both stacks, and the merge vectors of §5.1 | Schema and golden files; the three-device, stale-snapshot and rolled-back-file vectors | — |
| 5 | **S4b-BL-118** | `DriveSyncBackend`: the per-device files, merge, photos, the triggers of §1.3, the status line, one-tab lock on the website, "the folder was deleted" question | Two and three fake devices converging; offline then back; tombstones; a hand-edited file skipped | TC-M-47 |
| 5b | **S4b-BL-128** | Photos on Wi-Fi or mobile data (§11): the switch, the one-off (no monthly limit in v1), Data Saver, roaming, Low Data Mode and Low Power Mode, resumable sessions kept across network changes, the website's *Upload photos now* | `PhotoUploadPolicy` vectors on both stacks over a fake network-conditions provider; a resumed upload continues from its byte | TC-M-54 |
| 6 | **S4b-BL-119** | Deleting from Drive (§3): the three actions, the dialog, *Save a copy first*, permanent delete, the resumable list, offline refusal, automatic backup off afterwards and on other devices | Fake: nothing left after each action; a stop half way reports what is left and *Try again* finishes; offline refused with nothing deleted; shared files' permissions removed first; no re-creation afterwards | TC-M-48 |
| side | **S4b-BL-121** | The website's privacy page (`privacy.html`, static, four languages) for the consent screen, linked from About and the apps. It must say: there is no server of ours and the owner receives nothing; what Google sees (§9.5 vi); that the files are encrypted and a lost recovery key with no device left means the backups cannot be opened; that sharing includes other people's contact details only if ticked; deletion (§3) and the three disconnect actions (§3.5); how it maps to Play's data-safety form; and **Google's Limited Use sentence**: "Doorprints' use and transfer to any other app of information received from Google APIs will adhere to the Google API Services User Data Policy, including the Limited Use requirements." | A check that it exists, carries the Limited Use sentence and is linked | Owner signs off the text |
| side | **S4b-BL-124** (**done**) | docs/08 IR-11 (the project stopped or a client deleted), IR-12 (a leaked Picker key), IR-13 (a person who lost access to their Google account); the guide's Google Drive pages (already on `main`; hi/ta/te *under review*) | `mkdocs build --strict` | — |
| later | **S4b-BL-120** (deferred until the spike proves the mobile Picker, owner 2026-10-02) | Sharing version 1 (§4): *Share my hunt with…*, the consent text, the outbox file, *Add a shared hunt* with the Picker, *Stop sharing*, *Remove a hunt shared with me* | Two fake accounts: share, pick, update, delete travels, stop sharing ends access | TC-M-49 |
| later | **S4b-BL-129** (deferred, owner 2026-10-02) | An authenticator app as an option (§10.5): set up with the QR code, the wrapped secret in `keys.json`, checks with the window, replay and lockout, the website's second way to L2/L3, the second check at device approval, *Also ask for my authenticator code* for L3 on the phones, *Set up again* and *Turn off* as L3 | RFC 6238 vectors on both stacks, a fake clock, the lockout and reset paths | TC-M-55 |
| later | — | *Lock old backups again* (§9.5 iv); a monthly limit for photos on mobile data (§11) | — | — |

Before production (decision 7): the release gate on a release candidate with [13](13-release-security-checklist.md)
part I, and the deep self-run pentest's Drive scope (13 §5).

**The device checks** (added to [06](06-test-plan.md) as planned rows):

- **TC-M-46**: connect Drive on the website, an Android phone and an iPhone with a test account; *Back up to Google
  Drive now*; see the file in drive.google.com; *Import a backup* from Drive on another device; disconnect.
- **TC-M-47**: two devices of one account: edits on both, one offline for a while, a delete on one; both converge; the
  daily backup appears; a photo on Wi-Fi only.
- **TC-M-48**: in a real Drive: *Delete this backup*, *Delete all backups*, *Delete everything*; check Drive's bin
  is empty of them, the storage figure drops, automatic backup stays off and another device asks before backing up
  again; interrupt one deletion (airplane mode) and finish it with *Try again*.
- **TC-M-49** (deferred with sharing): two Google accounts: share, the Google email arrives, *Add a shared hunt* through the Picker on the
  website and on a phone, an update and a delete travel, *Stop sharing* ends the updates and the other keeps their
  copy.
- **TC-M-50**: device authentication for deletes: *Delete this backup* asks nothing more; *Delete all backups*, the
  last backup and *Delete everything* ask for the phone's PIN, fingerprint or face; cancel deletes nothing; waiting over
  a minute after the check asks again; on the website L2/L3 only with a passkey whose PRF seals the key, and otherwise
  only L1 with "use your phone".
- **TC-M-51**: the device lock: on a phone with no screen lock *Connect Google Drive* refuses with the words of §10.3;
  with a lock, connect, then remove the lock: Drive pauses with its message, nothing is uploaded or deleted; set a lock
  again: the phone must re-enrol (approval or recovery key) and the houses on it are intact.
- **TC-M-52**: a new device: a fresh second phone (an Android reinstall, or a new iPhone; a reinstalled iPhone may
  keep its Keychain key, §9.5 i) sees "This Google Drive already has Doorprints backups" and writes nothing; it shows a
  QR code; the enrolled phone scans it, asks for its own authentication, and the new phone opens the old backups; the
  8-digit fallback on the website shows the same code on both screens; a request left alone expires after 10 minutes;
  every device shows "New device enrolled"; *Revoke this device* from the first stops the second and the revoke dialog
  names Google's *Your devices* page.
- **TC-M-53**: the recovery key: save it at connect (print or copy; the two groups asked back); on a fresh browser or
  a reinstalled Android phone (or an iPhone after removing its old Keychain key by *Disconnect*) with no other device,
  open the backups with it; revoke a device and check that the recovery key still opens a backup made afterwards
  (it got the new epoch without being typed); *Skip* shows the warning and Settings says "No recovery key".
- **TC-M-54**: photos on mobile data: with the switch on, photos wait on 4G/5G and go on Wi-Fi; switch it off: they go
  on mobile data up to the limit; Data Saver (Android) and Low Data Mode (iPhone) hold them; *Upload photos now over
  mobile data* shows the size and works once; an upload interrupted by leaving Wi-Fi resumes.
- **TC-M-55** (deferred with the authenticator app): the authenticator app: set it up with Google Authenticator or Aegis from the QR code; on the website
  without a passkey, *Delete all backups* with a code; the same code a second time is refused; five wrong codes lock
  the check for 5 minutes; on a phone with *Also ask for my authenticator code* on, *Delete everything* asks for both;
  *Set up again* after "losing" the app needs the phone's own check or the recovery key.

### 7.1 What S4b-BL-115 built, and what it decided

The Drive access layer, with no screen, sign-in, encryption or sync logic: `app.doorprints.drive` in `android/shared`
(`DriveClient`, `HttpDriveClient`, `InMemoryFakeDrive` over a `FakeDriveServer`, `DriveOps.kt`) and `web/src/app/data/drive/`
(the same names; Promises, which the Drive sync backend wraps). The phase 2 row named them `DriveApi` and `FakeDriveApi`; the
build uses `DriveClient` and `InMemoryFakeDrive`. What this design did not say, and the build chose (the default unless the
owner objects, or the spike S4b-BL-122 shows Drive differs):

- **One call, one request, its own retries.** Every call but a resumable chunk and a status query retries by the rule of
  §5.2: five tries, `Retry-After` honoured up to 60 s (longer goes back to the caller, so a worker is not held), else full
  jitter `floor(random * (min(32 s, 1 s * 2^(n-1)) + 1))`, for 429, 403 `userRateLimitExceeded`/`rateLimitExceeded`, 5xx and no
  answer. A 401 reports the token to the `TokenProvider` and asks once more, then fails (`UNAUTHORIZED`: "Connect again").
- **Creates are retried, so a lost answer can leave two files** (Drive has no idempotent create). Callers find files by
  `appProperties` and take the oldest (`ensureFolder`; listings are in `createdTime` order); a duplicate `partial` backup is
  pruned after a day (§1.4 item 2).
- **Listings are eventually consistent; `getFile` by id is not.** A device keeps the ids it wrote and asks by id;
  absence from one listing right after a write proves nothing. `ensureFolder(spec, parent, create, knownId)` reads the
  known id first and **never re-creates a folder without `create`** (§3.4): a folder deleted or in the bin is null.
- **Resumable uploads** go in chunks of a multiple of 256 KiB (1 MiB by default); after a dropped connection, a 5xx or
  a rate limit the upload waits, asks Drive how far it got (`Content-Range: bytes */size`) and goes on from Drive's
  `Range`; a session Drive forgot (404) starts again once. The session URI is a capability: never logged or printed. Up to
  5 MB, one multipart request (§5.2).
- **The checksum**: `markComplete` reads Drive's `sha256Checksum` and sets `state=complete` (and the real name) only
  when it matches; a checksum Drive has not computed yet is asked again by the backoff, up to five reads, then `CORRUPT`.
  `downloadVerified` checks the bytes against Drive's checksum and the expected one.
- **The error kinds** are `UNAUTHORIZED`, `FORBIDDEN`, `QUOTA_EXCEEDED` (403 `storageQuotaExceeded`), `RATE_LIMITED`,
  `NOT_FOUND` (404, 410), `CONFLICT` (409, 412), `BAD_REQUEST` (400, 416, other 4xx; added to the planned list), `SERVER`,
  `OFFLINE` (no answer, and a redirect: a captive portal), `CANCELLED` (499, or stopped between chunks) and `CORRUPT` (an
  answer Drive does not send, a wrong checksum). Kotlin's `SyncOutcome.fromError` and the website's `errorMsg` read them in
  the words the server's failures have; a full Drive reads as a server problem until S4b-BL-118 gives it its own words.
- **Where the token goes**: only to `https://www.googleapis.com` (port 443), checked before every request, session URIs
  included; file ids are checked before they enter a path; the website sends no cookies and does not follow redirects,
  except on a session PUT, where the fetch standard returns Google's `308 Resume Incomplete` as it is only with
  `redirect: 'follow'` (it has no `Location`). **The spike S4b-BL-122 checks that Google's CORS exposes `Location` and
  `Range` to the website.**
- **Deletes go file by file**, a 404 counting as done, and `deleteAll` stops at the first failure and reports what is
  left (§3.3); Drive's batch endpoint is not used. **Permissions** (`permissions.delete` before a shared file is deleted,
  §3.3) come with sharing (S4b-BL-120).
- **The fake** (`InMemoryFakeDrive`, test code, never shipped: `:shared`'s commonTest and the website's specs; a later ticket whose `:ui` or `:app` tests need it moves it to a small test-fixtures module): files
  and folders with `appProperties` (124 bytes per key and value), content with `sha256Checksum`, revisions and a roll-back,
  the bin inherited from a folder and a permanent delete that takes a folder's contents, a quota that counts the bin,
  resumable sessions with Drive's chunk rule, `modifiedTime` from a `FakeClock` (content changes only), a listing lag,
  hand edits by "the person", and a deterministic `FaultScript` (by call number, by request kind and number, the next
  N, always, stop after N; offline, an expired token, 403 quota and rate limits, 429 with `Retry-After`, 5xx, 404, 409,
  499, a lost answer, a dropped chunk, corrupt content, a late checksum, another writer in between). The contract runs the
  same cases on the fake and on the HTTP client over the fake behind Drive's HTTP surface.

## 8. Risks

| # | Risk | What we do |
|---|---|---|
| R1 | Google suspends or changes the Doorprints project (policy, brand verification) | Visible files that outlive the app (§2.2); the self-hosted server and file sharing stay; [08](08-operations-runbook.md) IR-11 |
| R2 | The mobile Picker or the cross-client grant does not work as read | Spike first (S4b-BL-122); fallback: pick on the website; sharing on phones waits |
| R3 | The website's one-hour token makes automatic backup feel unreliable | Said on the screen (§1.3); the phones are the reliable automatic path |
| R4 | A person deletes or edits files in Drive by hand | §5.8; every file read is validated as an import |
| R5 | Clock skew between devices decides last-write-wins wrongly | As today (RR-06); backups keep the other version |
| R6 | Sharing leaks contact details or photos | Off by default, the consent text, viewer rights only, *Stop sharing* |
| R7 | A wiped device prunes the history | The shrink guard (§1.4 item 4) |
| R8 | A deletion surprises (half done, or re-created later) | §3.3 and §3.4; TC-M-48 |
| R9 | `play-services-auth` adds size and a Google dependency | The only new library; phones without Play services keep every other feature; the Custom Tab alternative is open question 6 (§5.5, §6.1) |
| R10 | Lost keys make the data unreadable (no recovery key, every device gone, a screen lock removed) | The recovery-key step at connect (§9.4), enrolment by approval (§9.5), the plain warnings; decision 3 |
| R11 | A crypto mistake (format, nonce reuse, a platform difference) | One format in common code, known-answer and cross-stack vectors, tamper tests, a review pass by the strongest model (§9.8) |
| R12 | Photos on mobile data cost the person money | Wi-Fi only by default, a monthly cap when switched off, Data Saver and Low Data Mode respected (§11) |
| R13 | A person expects the app's checks to stop someone who has their Google account | §10.6 says plainly what they cover; encryption keeps the contents closed; the guide recommends Google's 2-Step Verification |

## 9. Encryption without a passphrase: device keys and a recovery key (owner addition, 2026-10-02)

**The owner's words:** "public/private-key encryption, without user intervention if possible". This replaces the
passphrase idea of v0.1 (§5.4). **Recommendation: on by default**, with keys each device makes by itself, no
passphrase and no prompt in daily use, plus **one recovery-key step when Drive is first connected** (decision 3).
v0.4 applies the fixes of an adversarial review (enrolment out of band, rollback, the recovery public key, epoch
chaining, HPKE, the merge rule of §5.1).

### 9.1 The scheme in one paragraph

Every file Doorprints writes to Drive (backups, sync files, photos, shared files) is encrypted on the device before
upload with its own random **content key** (AES-256-GCM, chunked, §9.7). **A content key is never reused**: a new one
for every file and for every rewrite of a file. Each content key is wrapped with the account's **folder key** (a
random 256-bit AES key, numbered by *epoch*). The folder key of the current epoch is wrapped once for each **enrolled
device's public key** and once for the **recovery public key** (§9.4), and these wraps live in
`Doorprints/keys.json`. A device opens a file by unwrapping the folder key with its private key (once per run) and
then the file's content key. Wrapping content keys under a folder key, rather than directly for every device, means
that adding a device is one new wrap, not a rewrite of every file, and that a photo costs one cheap AES unwrap, not
one public-key operation.

**Epochs are chained** (so a device enrolled today opens a six-month-old backup): the record of epoch *N* in
`keys.json` holds the folder key of epoch *N−1* wrapped under the folder key of *N* (AES-256-GCM, AAD the two epoch
numbers). Each device and the recovery public key hold one wrap only, of the current epoch, and walk the chain back
as far as a file needs.

### 9.2 Which public-key scheme: HPKE (RFC 9180) with DHKEM(P-256, HKDF-SHA-256) and AES-256-GCM

| | ECDH P-256 (chosen) | RSA-OAEP-256 | X25519 |
|---|---|---|---|
| Website (WebCrypto) | Yes, everywhere; private key `extractable: false` | Yes | Only recent browsers |
| Android (Keystore, minSdk 26) | Key agreement in the Keystore from API 31 (`PURPOSE_AGREE_KEY`, StrongBox when present); **API 26-30: a software P-256 key whose private part is sealed by a Keystore AES-GCM key** and opened in memory only while used (weaker; RR-21; its own test row, §9.8) | Keystore OAEP uses MGF1-SHA-1 before API 34, so it cannot open what WebCrypto's OAEP-SHA-256 wraps | Keystore from API 33 only |
| iPhone | **Secure Enclave** P-256 key agreement (`SecKeyCopyKeyExchangeResult`, callable from Kotlin/Native through the Security framework) | Keychain only; the Secure Enclave has no RSA | No Secure Enclave support |

**The wrap is HPKE base mode** (RFC 9180: `DHKEM(P-256, HKDF-SHA256)`, `HKDF-SHA256`, `AES-256-GCM`), `info =
"doorprints/dpx1/wrap"`, `aad = epoch ‖ kid ‖ purpose`, chosen over the hand-made "ECDH, then HKDF with our own salt
and info" of v0.2: it is the same primitives, but a published construction with **published test vectors** (RFC 9180
Appendix A and the CFRG's full vector file cover this KEM and KDF), so each stack can prove it computes the same bytes,
and its key schedule takes care of binding the ephemeral and recipient keys. None of the three platforms offers HPKE
with hardware keys, so it is written once in common code (Kotlin, with its TypeScript twin) over the platform's ECDH,
HMAC and AES-GCM. **Enrolment uses HPKE's PSK mode** (§9.5 i). **Symmetric wraps** (a content key under the folder
key, epoch *N−1* under *N*, the TOTP secret, §10.5) use AES-256-GCM with **a fresh random 96-bit nonce per wrap**
and `epoch ‖ kid ‖ purpose ‖ inner` as the AAD. **Signing** (enrolment fallback, §9.5 i): each device also makes an
ECDSA P-256 key the same way (WebCrypto, Keystore since API 23, Secure Enclave).

**Kotlin Multiplatform:** the envelope format, HPKE, HKDF (built on an HMAC primitive), chunking and the vectors are
common code; the primitives (AES-GCM, HMAC-SHA-256, ECDH, ECDSA, random bytes, key storage) are an interface with
`actual` implementations on platform APIs only: `javax.crypto` and the Keystore on Android, the Security framework
plus a few lines of Swift calling CryptoKit for AES-GCM on the iPhone (CommonCrypto's GCM is not public), WebCrypto
on the website. No JVM-only call in `commonMain`, no new library.

### 9.3 Device keys and `keys.json`

At the first connect each device makes its key pairs without asking anything: Android Keystore (bound to the screen
lock, §10.3), iPhone Secure Enclave (`kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`, `.privateKeyUsage`), website
non-extractable WebCrypto keys kept as `CryptoKey`s in IndexedDB. Nothing about this is shown in daily use.

**`keys.json`** holds, inside one MACed body: a **`revision`** (a counter raised by every write), the current
**`epoch`**, the epoch chain, one entry per enrolled device (`kid` = the first 16 bytes of the SHA-256 of its public
key, name, platform, public keys, `enrolledAt`, `enrolledBy`, its HPKE wrap), the **recovery public key** with its
wrap, revoked entries (`kid`, `revokedAt`, `revokedAtEpoch`), and the wrapped TOTP secret if any (§10.5).

- **The MAC** is HMAC-SHA-256 under `HKDF(folderKey of the current epoch, "doorprints/dpx1/dir")`. Only enrolled
  devices and the recovery key can compute it, so someone in the Google account cannot add a key that devices accept
  (T-S13).
- **What the MAC does not do:** it cannot protect a device that is not enrolled yet (it has no folder key to check
  with; §9.5 i and the first-connect rule), and on its own it cannot stop a rollback to an older, validly MACed copy.
- **Rollback and replay are refused:** each device stores on itself the highest `epoch` and `revision` it has seen and
  refuses a `keys.json` (or a `doorprints.json`) with a lower one, for example an old revision brought back with
  Drive's *Manage versions*; it reports it ("The key list in your Google Drive was replaced by an older copy; nothing
  was changed here").
- **Every enrolled device is fully trusted**: under a symmetric MAC any of them could rewrite the list. So every
  device shows, on its next run, "New device enrolled: Pixel 8, by your iPhone" (and the same for a revoke). Per-entry
  signatures, which would let a device prove which device added an entry, are a later option.
- **A local copy rebuilds it:** each device keeps, sealed on itself, its own wrap and the recovery public key; if
  `keys.json` is missing or damaged **while the folder and its control file remain**, any enrolled device rebuilds
  it (a new `revision`) and says so. When the whole folder is gone (*Delete everything*), nobody rebuilds anything
  (§3.4).

**First connect to a folder that already exists** (a new device, or a device whose keys were lost): the folder is
**never adopted silently**, because a device that cannot yet check the MAC cannot tell a real list from a forged one.
It shows: "This Google Drive already has Doorprints backups. To use them here, join from one of your devices (scan
its code) or type your recovery key." with *Join with another device*, *Use my recovery key*, and, as L3, *Delete
everything and start again*. Until it has joined, it writes nothing into the folder. A list that fails the check after
joining reads "This folder belongs to another key set" with the same choices.

**An empty first folder (website).** If the folder has no key list and no other file — no control file, no backup, no photo, no sync file, and nothing in the bin, including inside subfolders — it is an unfinished first connect, not someone else's backups. The website writes the key list into that same folder. It does not delete the folder and it does not adopt a folder that still holds any file. When any file remains, the card says the key list is missing and other files are still there, and tells the person to open Doorprints on a device that already connected, or to delete the Doorprints folder in Google Drive and connect again. A listing Drive marks incomplete is not treated as empty. The phone still refuses this folder.

### 9.4 The recovery key (mandatory step, skippable only after a warning)

At the first connect, after the device keys exist, the app shows a **recovery key**: 128 random bits, written as 26
Crockford base32 characters plus one check character, in groups of four (`DP7K-3QXW-…`). The app never stores it.

**It is also a key pair, so it receives every new epoch without being typed.** From the recovery key the app derives
a P-256 private key deterministically: `seed = HKDF-SHA-256(ikm = recoveryKey, salt = "doorprints/dpx1/recovery",
info = "p256", L = 48)`, `d = (seed as a big-endian integer mod (n − 1)) + 1`, the method of FIPS 186-5 A.2.1 with 64
extra bits, which always gives a valid scalar (no retry rule is needed, and the bias is below 2⁻⁶⁴). The public key is
computed from `d` by each platform's own code (CryptoKit's `P256.KeyAgreement.PrivateKey(rawRepresentation:)` on the
iPhone, S4b-BL-131; a PKCS #8 import on the website; on Android, which has no call for it, two of the platform's own
ECDH operations, x(d·G) and x((d+1)·G), and the sign of y chosen by one addition of public points, §9.9). **As built,
there is no point multiplication in common code** (v0.4 planned one for Android); the result is checked against the
RFC 5903 and well-known P-256 vectors. **Only the public key is
stored**, in `keys.json`; every new epoch, re-wrap and the TOTP secret's re-wrap is HPKE-wrapped to it like a device.
The private key exists only in memory while the person has typed the recovery key to open something.

> **Save your recovery key.** If you lose this phone and have no other device with Doorprints, this key is the only
> way to open your backups. Nobody else has it, not even Doorprints. Print it, or keep it in a password manager. Do
> not keep it only in this Google account.
> **Print** · **Copy** · **I have saved it**

*I have saved it* asks for two of the groups back to prove it. *Skip* is offered only after: "Without a recovery key,
losing all your devices means losing your backups for good. Skip anyway?" and a tick box; Settings then shows "No
recovery key" with *Make a recovery key*. Making a new one (L3, §10.1) replaces the recovery public key in `keys.json`
and wraps the current epoch to it. *Delete everything* deletes `keys.json`, so the recovery key stops working; the next
connect makes a new key set and a new recovery key (§3.2).

### 9.5 The hard parts, and the answers

| # | Case | What happens |
|---|---|---|
| i | **A new phone, a reinstalled Android phone, a cleared browser** has no private key | It makes its keys and asks to join. **Main path, by QR code (the pairing pattern of ADR-25):** the new device shows a QR code (and a link) holding `pk_new ‖ s`, where `s` is 128 random bits; an enrolled device scans it with the phone's camera, authenticates the person (L2), and HPKE-wraps the current folder key **for exactly the `pk_new` bytes it scanned**, in **PSK mode with `psk = s`**, so only someone who saw that screen could make a wrap the newcomer accepts; the newcomer checks it, then checks `keys.json` with the folder key it got. **Without a camera (fallback), the 8-digit comparison,** done as a commit-then-reveal numeric comparison (the pattern of Bluetooth's numeric comparison): the newcomer posts `pk_new` and a commitment to its nonce `n_new`; the approver posts its nonce `n_a` and `pk_approver`; the newcomer reveals `n_new`; both show `code = first 8 digits of SHA-256(n_new ‖ n_a ‖ pk_approver ‖ pk_new)`; the person compares; the approver signs its wrap with its static ECDSA key. Neither side can choose its nonce after seeing the other's, so a key swapped in Drive shows different codes. **Either way:** a pending request expires after 10 minutes, and there is one at a time. **Or the recovery key**, typed on the new device. **iPhone reinstall:** Keychain items survive an app's removal on iOS, so a reinstalled iPhone may find its old key; it reuses it only if `keys.json` still lists that `kid` unrevoked, and otherwise deletes the stale items and joins as new |
| ii | **The website's data is cleared** (by the person, by Safari after 7 days without a visit, or under storage pressure) | The same as (i); the QR path is the easy one (§5.1) |
| iii | **Every device lost** | Only the recovery key opens the files (on the website too: *Import a backup* > a downloaded `.dpx` file and the recovery key). Without it the data in Drive cannot be read by anyone, ever. The app says this at connect and in Settings |
| iv | **A device lost or stolen: revoke it** (L2) | Its entry becomes a revoked entry, a **new epoch** is made, chained to the old one, and HPKE-wrapped for the remaining devices **and the recovery public key** (nobody types the recovery key); new files use it. The remaining devices **skip and report** any sync or backup file written after `revokedAt` under an older epoch or by the revoked device ("A file written by the revoked Pixel 8 after it was revoked was ignored"). Old files stay readable to the old key, so a thief who has both the phone and access to the Google account could open the backups made before the revocation; *Lock old backups again* rewrites the kept backups and sync files under the new epoch (**deferred, not in v1**, owner 2026-10-02). **A revoke does not revoke that device's Google access**: the dialog says "This stops that device from opening new Doorprints backups. It does not sign it out of your Google account: use *Disconnect on all devices*, or Google's *Your devices* page, and change your Google password if it was stolen" |
| v | **Sharing** (deferred with §4) | The recipient's app has an account **share key** pair (its private part wrapped under the recipient's own folder key, so all their devices have it). The recipient sends a **key card**: a QR code carrying the whole public key, or a Doorprints link (`https://doorprints.web.app/k#…`) whose **64-bit fingerprint** (16 characters in four groups) the two compare by voice or in person; the sharer's app wraps the shared file's content key for it. *Stop sharing* deletes the file; new shares never use her key |
| vi | **What Google still sees** | File names, sizes, times, the number of files (so roughly the number of photos), who a file is shared with, and the account. The names carry no house data (`Doorprints-backup-<date>.dpx`, photos `p-<random>.dpx`); `appProperties` carry only random ids and `state`, no house id or plaintext checksum. Drive's preview, search and virus scan see only noise, by design |
| vii | **Readable without the app** | No longer: an encrypted backup opens only in Doorprints (any platform, the website included, with a device key or the recovery key). If Google stops the project (R1), the person downloads the `.dpx` files from Drive and opens them with the website and the recovery key; *Save a copy* to the device stays unencrypted, as today |

**Website QR (draft PR #118, S4b-BL-134).** A new browser shows a QR, drawn in the page with no extra library, and a copyable link. The payload is `dp1.` plus the base64url of `pk_new` (65 bytes) followed by `s`. The enrolled browser scans it with `BarcodeDetector` when that API exists, or the person pastes the text. After an L2 passkey check the enrolled browser returns an HPKE **PSK** wrap of the current folder key (not the base-mode wrap stored in `keys.json`). The newcomer opens that wrap and pins. The wrap's reply is pasted, the same channel as the 8-digit path. **`s` on the website is 32 bytes**, RFC 9180's minimum `Nsk`. The prose above says 128 bits; `keySchedule` refuses a shorter PSK, so the website does not use 16 bytes. `psk_id` is the UTF-8 of `doorprints/dpx1/qr-psk`. Scanning a real camera is TC-M-52. Android and iPhone QR enrolment stay open.

### 9.6 The envelope format (`dpx/1`)

`"DPX1"` magic, a 2-byte header length, a JSON header (`v`, `alg` `A256GCM-STREAM-64K`, `epoch`, `kid` of the writing
device, `wrappedKey` (the content key under that epoch's folder key, with its own nonce), `noncePrefix`, `chunkSize`
65536, `inner` (`doorprints-backup/2`, `sync/1`, `photo/1`), `shareWraps` for shared files), then the chunks. The inner
bytes are the backup ZIP with its manifest and per-entry SHA-256, the new `sync/1` file (schema: S4b-BL-130), or the
JPEG. A reader that does not know `dpx/1` refuses the file with "update the app" (the versioning rule, docs/schemas
§1.1); the backup's own number does not change for encryption.

**Downgrade and tamper.** The control file `doorprints.json` (MACed like `keys.json`, with the same rollback check)
records `encryption: dpx/1`; once it does, devices ignore an unencrypted file in the folder. Every chunk authenticates
the header (AAD) and its index and a last-chunk flag, so a changed header, a reordered, dropped or truncated chunk, a
swapped key id or a flipped bit is refused.

**Two checksums.** Drive's `sha256Checksum` of the ciphertext is compared after upload (§1.4 item 3, before
`state=complete`); the manifest's SHA-256 of each plaintext entry is checked after decryption.

**Key commitment is not an issue here.** AES-GCM does not commit to its key, which matters when an attacker can make
one ciphertext open under several keys it chooses (partitioning-oracle and "invisible salamander" attacks). Here every
content key is random, wrapped under keys an outsider does not hold, and bound by the wrap's AAD to its epoch, writer
and inner format; the only parties who could choose keys are enrolled devices, which are fully trusted anyway (§9.3).

### 9.7 Performance, large photo sets

Chunks of 64 KiB, each its own AES-GCM operation with the nonce `noncePrefix (7 bytes) ‖ index (4 bytes) ‖ last (1
byte)` (the `noncePrefix` random per file, and the content key never reused, so no nonce repeats), so memory stays
flat for any file size and WebCrypto (which has no streaming AEAD) can do it chunk by chunk. AES-GCM runs in hardware
on current phones and in the browser's native code, far faster than the upload; the one public-key operation is the
folder-key unwrap, once per run (tens of milliseconds in the Secure Enclave), and each photo then costs one AES unwrap.

### 9.8 Tests

Known-answer tests for each primitive on each platform (RFC 5869 for HKDF, NIST vectors for AES-GCM, P-256 ECDH and
the point multiplication of §9.4) and **RFC 9180's HPKE vectors** for base and PSK mode; shared envelope vectors in
`docs/schemas/dpx-vectors.json` (fixed keys, nonces and plaintexts; a file written by Kotlin opens in TypeScript and the
reverse, the repository's parity-vector pattern); the recovery-key derivation vectors (key text → scalar → public
key); tamper tests (each item of §9.6); `keys.json` tests: an added entry refused, **an older revision restored from
Drive's version history refused**, a damaged list rebuilt from the local copies, a never-joined folder not adopted;
**a stale sync file written under an older epoch after a revoke skipped and reported**; enrolment by QR (a swapped
`pk_new` refused), by the code fallback (a swapped key gives different codes; an expired or second pending request
refused) and by recovery key; revocation and the new epoch reaching the recovery public key; epoch chaining (a device
enrolled at epoch 5 opens an epoch-2 backup). **The Android API 26-30 software-key path has its own test row**
(Robolectric with a fake Keystore, and the emulator on API 29), apart from the Keystore key-agreement path. All
against the fake Drive with fake key stores. Crypto code is reviewed by the strongest model (docs/14 §7).

### 9.9 What S4b-BL-125 built, and what it decided

The encryption core, with no screen, enrolment, key storage, sync or Drive call: `app.doorprints.crypto` in
`android/shared` (commonMain, with the provider's `actual`s) and `web/src/app/data/crypto/` (the same names; Promises,
because WebCrypto is asynchronous). Tests: TC-U-125..TC-U-131 ([06](06-test-plan.md)); vectors:
`docs/schemas/hpke-vectors.json` and `dpx-vectors.json` (docs/schemas §6.3).

| Piece | Kotlin / TypeScript | What it is |
|---|---|---|
| Primitives | `CryptoProvider` (`JvmCryptoProvider` on Android, `WebCryptoProvider`; the iPhone's `actual` fails closed with `UNAVAILABLE` until S4b-BL-131) | Random bytes, SHA-256, HMAC-SHA-256, AES-GCM with AAD and a caller's 96-bit nonce (opening fails closed: `AUTH_FAILED`), P-256 generate, from-scalar, validate (65 bytes, `04`, x and y < p, on the curve) and ECDH; constant-time comparison |
| HKDF, HPKE | `Hkdf`, `Hpke` | RFC 5869 over HMAC; RFC 9180 base mode, DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-256-GCM (AES-128-GCM only for the A.3 vectors); `setupBaseS/R`, `seal`, `open`; the website also has `setupPskS/R`, `sealPsk`, `openPsk` (mode `0x01`) for the QR channel of §9.5 |
| The recovery key | `RecoveryKey` | §9.4, the text and the key pair |
| The envelope | `Dpx` | `dpx/1`, streaming and in memory |
| The key list | `KeysFile`, `OpenedKeys` | Create (first device), open by a device or the recovery key, add a device, new epoch (revoke, new recovery key), the chain |
| The rules | `KeysGuard`, `RevokedEpochRule` | The rollback watermark over an injected store; which files to skip after a revoke |

**The bytes.** `dpx/1`: `"DPX1"`, a 2-byte big-endian header length (1..4096), the header as canonical JSON
`{"v":1,"alg":"A256GCM-STREAM-64K","epoch":E,"kid":…,"wrappedKey":{"nonce":…,"ct":…},"noncePrefix":…,"chunkSize":65536,"inner":"…"}`
(base64 with padding, RFC 4648 §4), then chunks of 65536 plaintext bytes plus a 16-byte tag, the last 0..65536 + 16; nonce
`noncePrefix(7) ‖ u32 index ‖ u8 last`, AAD `header bytes ‖ u32 index ‖ u8 last`. `keys.json`:
`{"format":"doorprints-keys/1","body":{"revision","epoch","chain":[{"epoch","nonce","ct"}],"devices":[{"kid","name","platform","publicKey","enrolledAt","enrolledBy","wrap":{"enc","ct"}}],"recovery":{"kid","publicKey","anchorEpoch","anchor":{"nonce","ct"},"wrap"}|null,"revoked":[{"kid","kind":"device|recovery","revokedAt","revokedAtEpoch"}]},"mac":…}`.

**What makes a list trusted (after the review of 2026-10-02).** The MAC proves only that its writer knew *some*
folder key, and HPKE base mode does not say who wrapped a key: anyone in the Google account can pick a folder key,
wrap it to every public key in the list (they are in plain sight), add a device and MAC the result. So each device
keeps a **pin** beside its watermark (`KeysGuard`, an injected store with an atomic compare-and-set): the highest
(epoch, revision) it accepted, `keyId = HKDF(that epoch's folder key, "doorprints/dpx1/key-id")` and the SHA-256 of
that body. A list of the pinned epoch must have the pinned key id (else `FORK_DETECTED`), and of the pinned revision
the same body (else `FORK_DETECTED`); a list of a higher epoch must open its whole chain down to the pinned epoch and
end at the pinned key (else `PIN_MISMATCH`), whatever its revision; a lower epoch or revision is `ROLLED_BACK`. A
device with no pin opens nothing from Drive (`NOT_PINNED`) except by three named paths, each with its own proof:
`openFirstPin` (the folder key received over S4b-BL-126's QR/PSK enrolment, compared in constant time),
`openWithRecovery` (the anchor, below) and `KeysGuard.pinCreated` (this device made the folder). **The recovery
anchor**: when a recovery key is made at epoch E0, the recovery entry stores the folder key of E0 under
`HKDF(recovery key bytes, "doorprints/dpx1/recovery-anchor")` with the AAD `u8 len ‖ "dpx1/recovery-anchor" ‖ u32 E0
‖ recovery kid`; opening with the recovery key walks the chain from the current epoch down to E0 and requires the
anchored key at the end, so a list re-wrapped by an outsider is refused there too (`RECOVERY_ANCHOR_INVALID`). **The
anchor alone does not stop a device that was enrolled**: it can walk the chain down to the anchor's epoch, chain epochs
of its own below the anchored key, copy the public anchor and wrap to the recovery public key (second review,
2026-10-02). So **every revoke issues a new recovery key** (`newEpoch(revokeKid, newRecovery)`; without one it is
`NEW_RECOVERY_REQUIRED`, and re-using the old key is refused the same way): the new anchor holds the new epoch's key,
which the revoked device never had, and the old recovery kid moves to the revoked list. **UI requirement (S4b-BL-126):
revoking a device shows the new recovery key once, with the same *Print* / *Copy* / *I have saved it* step as at the
first connect, and says the old one no longer opens anything.** A new epoch without a revoke keeps the anchor, so then the
recovery key does not protect against a device enrolled before it (fully trusted anyway, §9.3). An old recovery key the
person kept after a revoke still opens lists a thief forges from the old anchor ([02](02-threat-model.md) RR-29): the
screen says to destroy it. An enrolled device that knows an epoch's key (a thief before the revoke) can also extend that
epoch for a device that has not yet seen the revoke; when that device then sees the genuine list it reads
`ROLLED_BACK` (the thief chained to a higher epoch) or `FORK_DETECTED` (the same epoch), never something benign, and
the way back is a **repin** on a stronger proof than the old pin: `KeysFile.repinFirstPin` (the folder key over the
QR/PSK enrolment again) or `repinWithRecovery` (the current recovery key's anchor), only on the person's action.
**No error kind from `keys.json` may trigger a revoke, a re-key, a wipe of local keys or data, or re-creating the
folder**: anyone with write access to the folder can cause every one of them; the app reports, stops writing and asks.
**Writing** (S4b-BL-126, -118): re-read and open the head of `keys.json` just before a change, upload, read it back,
and only then `KeysGuard.acceptWritten`; two writers at once make two lists of one revision, which every device
refuses as a fork; **never write data under a folder key not yet confirmed by that read-back**. A new epoch starts at
revision 1, and a revision more than 1024 above the pinned one in the same epoch is refused (`REVISION_JUMP`), so a
stolen device cannot exhaust the revisions and block a revoke.

What this design did not say, and the build chose (the default unless the owner or the review objects):

- **Key separation.** The folder key is only ever HKDF input (empty salt): `doorprints/dpx1/dir` for the MAC (as §9.3),
  `doorprints/dpx1/content-wrap` for content keys and `doorprints/dpx1/chain-wrap` for the epoch chain, so no key is
  both an AES key and an HMAC key.
- **The AADs** are length-prefixed so no two inputs collide: content key `u8 len ‖ "dpx1/content-key" ‖ u32 epoch ‖
  kid(16) ‖ u8 len ‖ inner`; folder-key wrap `u8 len ‖ "dpx1/folder-key" ‖ u32 epoch ‖ recipient kid(16)` (HPKE `info`
  `doorprints/dpx1/wrap`); chain `u8 len ‖ "dpx1/epoch-chain" ‖ u32 N ‖ u32 N − 1`.
- **Canonical JSON** for the header and `keys.json`: no whitespace, keys in the listed order, integers only, minimal
  escapes, UTF-8. A reader writes back what it parsed and requires the same bytes, so whitespace, duplicate or unknown
  keys and other spellings are refused on both stacks alike. Unknown fields are refused, so a later field (`shareWraps`
  for sharing, the TOTP wrap, the devices' ECDSA keys for the code fallback of S4b-BL-126) comes with a new header `v`
  or a new `doorprints-keys/2`.
- **The MAC input** is `"doorprints-keys/1" ‖ 0x00 ‖ canonical body`; it is checked after the device's wrap opened (only
  then is the key known) and before anything from the list is used; the watermark moves only after it.
- **HPKE's ephemeral key** comes from the platform's own key generation (`p256Generate`: WebCrypto `generateKey`,
  Android `KeyPairGenerator`), so a wrap never depends on importing a raw scalar; the vectors inject DeriveKeyPair
  keys (`FakeRandomProvider`), so a whole `keys.json` is still a byte-exact vector.
- **The recovery key's text**: the 16 bytes as a big-endian number after two zero bits (so the first symbol is 0..7),
  26 symbols, then Crockford's own check symbol (the value mod 37, which can be `*`, `~`, `$`, `=` or `U`; it catches
  every single wrong symbol and every swap of two neighbours), shown `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXX`. Reading
  ignores case, spaces and hyphens and reads O as 0, I and L as 1. The HKDF input is the 16 bytes, not the text.
- **The recovery public key per platform**: Android, which cannot give the public key of a raw scalar, uses two of its
  own ECDH operations (x(d·G) and x((d+1)·G)) and one affine addition of public points to fix the sign of y; the
  website imports a PKCS #8 key without its public key (the browser computes it) and reads it from a JWK, then imports
  the scalar again as non-extractable (Chromium and Node checked; Firefox and Safari wait for TC-M-53, [02](02-threat-model.md)
  RR-27); the iPhone will use CryptoKit (S4b-BL-131). The reduction `(seed mod (n − 1)) + 1` is common code with fixed
  limbs and no secret-dependent branch.
- **A new recovery key starts a new epoch** (the old one may have been seen) with a new anchor, and the old recovery kid
  joins the revoked list (`"kind":"recovery"`, never an accepted writer); a revoke always comes with one (above); a revoked device moves from `devices` to `revoked` and cannot be listed again; `enrolledBy`
  must name a listed, recovery or revoked kid; the last device cannot be revoked when there is no recovery key.
- **The rollback rule** orders (epoch, revision) lexicographically: a higher epoch always wins, so a holder of an old
  epoch's key cannot block a revoke by writing revision 2⁵³ − 1 (a pinned device refuses the jump, `REVISION_JUMP`;
  a list at that revision cannot be written on, `REVISION_LIMIT`, but a new epoch starts at revision 1). **`RevokedEpochRule`**: a revoked writer's
  file is skipped if written after its revoke or under an epoch it never had; any file under an epoch older than a
  revoke and written after it is skipped; an unknown writer is skipped; the time is the caller's (Drive's
  `modifiedTime`), not authenticated ([02](02-threat-model.md) RR-26).
- **Chunks**: an empty file is one empty last chunk; a size that is a multiple of 64 KiB ends with a full last chunk;
  an empty last chunk after data is refused (`NON_CANONICAL_CHUNKS`). A block that opens with the other last flag
  tells `TRAILING_DATA` or `TRUNCATED` apart from `CHUNK_AUTH_FAILED`; a reordered and a duplicated chunk are the same
  kind (GCM cannot tell them apart), with the chunk's index.
- **Limits**: plaintext 4 GiB by default (a caller passes less), header 4096 bytes, `inner` a format name and number,
  `keys.json` 256 KiB, 64 devices, 1024 revoked entries, names 1..64 characters without control characters.
- **Checksums**: no checksum inside the format (GCM covers it); `encrypt` returns the SHA-256 of the whole file (what
  Drive's `sha256Checksum` must equal) and of the plaintext (the photo row's), and `decrypt` checks either if asked.
- **A file is bound to its row** (review, 2026-10-02): every photo is a valid file under the same folder key, so one
  photo file could be swapped for another. Of the two sound fixes (a random file id in the header compared with the
  row, or the row's SHA-256) the smaller is chosen: `decrypt` refuses a `photo/1` file without
  `expectedPlaintextSha256` (`CHECKSUM_REQUIRED`), and the photo rows already carry that SHA-256 (§5.1). A sync file is
  bound by its writer (the caller compares the header's `kid` in `headerCheck`), a backup by the date in its manifest.
- **Partial output and keys in memory**: `encrypt` does not know the length in advance, so a failure (`TOO_LARGE`, a
  failing source or sink) can leave a partial file on the sink, which the caller discards; `decrypt` writes each chunk
  only once it authenticated, but the whole file is proven only when it returns. Content keys, folder-key copies,
  derived keys and the recovery scalar are overwritten after use where the code holds them; the platforms' own key
  objects and JavaScript strings are out of reach ([02](02-threat-model.md) RR-28). **On the website an opened folder key
  is now a non-extractable HKDF base** (S4b-BL-132): imported once with `extractable: false`, and the MAC, content-wrap,
  chain-wrap and key-id keys are derived from it. `currentFolderKey()` throws on that object. A wrap or a chain link
  still needs the raw key once, so `rawFolderKey()` opens the device or recovery wrap again and the caller wipes the
  copy. A key the page has just created still holds raw bytes until that same object writes its next wrap. The vectors
  in `dpx-vectors.json` stay byte-identical. Content keys and the recovery scalar are still raw while used. Android
  still holds folder keys as bytes.
- **Not in this ticket**: the local copies that rebuild `keys.json` and the first-connect rule (S4b-BL-126), the MACed
  `doorprints.json` (S4b-BL-116, with `KeysGuard` and a MAC of its own label), Keystore, Secure Enclave and IndexedDB key
  storage (S4b-BL-127, -131, -126), RFC 9180 A.3.2 known-answer vectors for PSK mode (the website's `sealPsk` /
  `openPsk` are in for the QR channel), the vectors on the Android runtime's own provider (Conscrypt, S4b-BL-133).

## 10. Device authentication and the device lock (owner addition, 2026-10-02)

**The owner's words:** "Deletes crossing a certain level would need the user to authenticate using device
authentication, and this feature's usage also needs device lock to be enabled."

### 10.1 Levels

| Level | Actions | What is asked |
|---|---|---|
| **L1** | *Delete this backup* (not the last one left); *Remove a hunt shared with me*; *Disconnect Google Drive* on this device; turning automatic backup off | The dialog of §3.2 |
| **L2** | *Delete all backups*; deleting the **last** remaining backup; *Stop sharing with…* / deleting a shared file; *Revoke this device*; approving a new device (§9.5 i); *Disconnect on all devices* | The dialog **and one factor** (below) |
| **L3** | *Delete everything Doorprints keeps in my Google Drive*; anything that weakens protection: making or skipping a recovery key after connect, turning encryption off (not offered in v1) | The dialog with the tick box and the 5-second delay, **and the factor** below |

Which factor satisfies a level, per platform:

| Platform | L2 | L3 | Approving a new device |
|---|---|---|---|
| Android phone, iPhone | **Device authentication** | Device authentication | Scanning the new device's QR code (or, without a camera, the 8-digit comparison of §9.5 i) **and** device authentication on the approving phone |
| Website with a passkey whose PRF extension seals the website's key | The passkey (user verification) | The passkey | The QR of §9.5 i (scan when the browser can, otherwise paste) or the 8-digit comparison, and the passkey |
| Website without that | Not offered: "To do this, use Doorprints on your phone" | Not offered | Not offered |

The authenticator app (§10.5) is deferred and is not a factor in version 1.

**Recommendation: the line is between L1 and L2.** L2 and L3 are the actions after which nothing is left to go back
to, or that change who can read the data. One backup out of seventeen is not.

### 10.2 How it asks (the app lock's code path, S4b-FR-5)

- `rememberDeviceCredentialCheck` (`:ui`, `CredentialCheck` PASSED / CANCELLED / NO_SCREEN_LOCK) with a stricter class
  for deletes: Android BiometricPrompt with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (the app lock allows
  `BIOMETRIC_WEAK`), the keyguard's confirm-credential screen on API 26-28; iPhone `LAPolicyDeviceOwnerAuthentication`
  (Face ID, Touch ID or the passcode). The `USE_BIOMETRIC` permission is already declared.
- **On Android 11+ the authentication is bound to the operation**: a Keystore HMAC key with per-use authentication
  (timeout 0) signs the operation id through a `CryptoObject`; the delete runs only with that signature, so an overlay
  or a tap cannot fake a pass. It does not stop a rooted phone or a changed app, which can skip any check inside the
  app (§10.6). Below API 30 the plain result is used, as the app lock does.
- **Valid for one operation, at most 60 seconds**, never cached; an app-lock unlock does not count; a cancel or
  failure changes nothing ("Nothing was deleted").
- **Tests**: a fake authenticator behind the same interface: passed, denied, cancelled, timed out (over 60 s before the
  delete starts), the lock removed half way (the run stops before the next file and reports what is left); the
  emulator test follows `AppLockEmulatorTest`'s rules (skips when no PIN can be set, API 26-28 skipped, S4b-BL-113).

### 10.3 The device lock is required on the phones

- **To switch Drive on**: `PlatformServices.hasScreenLock()` (Android `KeyguardManager.isDeviceSecure`, iPhone
  `iosHasScreenLock()`, `LAContext.canEvaluatePolicy(.deviceOwnerAuthentication)`, false without a passcode). Without
  one: "Google Drive backup needs a screen lock on this phone (a PIN, pattern, password, fingerprint or face). Set one
  in the phone's settings, then come back." with *Open settings* (Android `ACTION_SECURITY_SETTINGS`; the iPhone has
  no link to it, so the text says *Settings > Face ID & Passcode*).
- **Checked before every run** (sync, backup, delete, share). If the lock was removed, Drive **pauses**: no upload,
  download or delete. The person sees, on opening the app and in Settings > Google Drive: "Google Drive backup is
  paused because this phone no longer has a screen lock. Your houses are safe on this phone. Set a screen lock to
  continue." (One notification on Android when a background run finds it.)
- **The keys stop working by design.** Android: the device key and the delete-signing key are Keystore keys with
  `setUserAuthenticationRequired(true)` (the device key with a 6-hour window after an unlock, so background sync works
  on a phone used that day; otherwise it waits), which Android **permanently invalidates when the screen lock is
  removed**. iPhone: the Secure Enclave key and the refresh token are `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`,
  which iOS **deletes when the passcode is removed**. **Consequence:** once a lock is set again, this phone is a new
  device for §9: it connects again (on the iPhone Google's consent again, the token being gone) and re-enrols by
  approval from another device or with the recovery key. Its local houses were never touched. This is why the
  recovery key matters even for a person with one phone.

### 10.4 The website

A website cannot learn whether the computer has a lock and cannot call the operating system's authentication.
Google's token flow cannot force a fresh password either (it offers only `prompt` = none, consent or
select_account), so it is not a check of who is at the keyboard and is not used as one. The honest equivalent:

- **A passkey for this browser with the PRF extension** (WebAuthn, platform authenticator, `userVerification:
  "required"`: Windows Hello, Touch ID, the phone's screen lock), offered when Drive is connected on the website. The
  website's device key is sealed with a key derived from the passkey's PRF output, so the key itself needs the
  person's verification (once per page load). **Only then are L2 and L3 offered on the website** (decided 2026-10-02,
  §6.1 question 2): a check made only in the page, without a key behind it, is skipped by anyone with the browser's
  developer tools.
- **Without it** (no passkey, or no PRF in this browser) the website does L1 only, and says: "To delete all backups,
  use Doorprints on your phone." The authenticator app is not offered (deferred, §10.5).
- **What is weaker, and what the person is told** when connecting on the website: "This browser cannot show Doorprints
  whether your computer is locked. Anyone who can use this browser profile can open your houses and your Drive backups
  here. Use your own computer, and *Disconnect Google Drive* on a shared one." The website check runs in the page, so
  it guards against someone at an unlocked computer, not against a changed page (nothing can, without a server).
- **L3 confirm on the website: tick box, no delay.** Decision 8 and the shared delete-policy vectors still say a
  5-second delay for L3 so the phones stay in step (owner-approved phone behaviour; Kotlin `DELAY_SECONDS_L3`). The
  owner decided the **website** skips that countdown and uses a tick box only. That is deliberate, not a silent
  divergence: the website UI ignores `delayMs` from the policy; `drive-delete.spec.ts` asserts the countdown is
  absent. A grant is issued only after a WebAuthn PRF open, is bound at **authorize** to that preflight plan's
  `operationId` (`registerGrant` then, not at execute), and is redeemed once (`WebAuthorizer.redeem` inside
  `RealAuthorizationGate.isGenuine`). Execute of another plan of the same action (a different `operationId`) is
  refused (`AUTHORIZATION_OTHER_OPERATION`). The grant map is in memory only: after a reload it is gone, the sealed
  passkey in IndexedDB remains, and a new PRF open is required. **The proof bytes are an HMAC** (S4b-BL-135, website):
  after the PRF open, the page derives a non-extractable HMAC key (HKDF info `doorprints/deletion-proof/1`) and
  `AuthorizationToken.proof` is the hex HMAC of `utf8(operationId) ‖ 0x00 ‖ u64be(issuedAtMs)`. A 64-hex proof that
  was not made under that key fails, including one registered in the page without the PRF. L1 keeps the numeric grant
  id as its proof. The phones' HMAC, under the device lock, is still open.

### 10.5 An authenticator app as an option (owner addition, 2026-10-02; **deferred, not in v1**)

**Deferred by the owner on 2026-10-02** (§6.1 question 1): on a phone it repeats the phone's own lock, and on the
website a check in the page is skipped with developer tools. The design is kept for later (S4b-BL-129).

**The owner's words:** "An authenticator app can also be used if it makes things easier for the user than the public
private approach." An authenticator app (Google Authenticator, Microsoft Authenticator, Aegis and others) shows a
6-digit code that changes every 30 seconds: TOTP, RFC 6238 (HMAC-SHA-1, 6 digits, 30-second steps), checked on the
device with no server.

**What it cannot do.** It cannot replace the encryption of §9: a rotating 6-digit code proves that someone has the
authenticator app at that moment; it is not a key, and the secret inside the authenticator app cannot be read back out
to open a file. It cannot recover anything when every device is lost (only the recovery key can). So §9 stays as it
is, and TOTP is **an option for the authentication steps only** (§10.1).

**Where it helps.** (a) **The website**, whose only way to L2/L3 was a passkey, which not every browser and computer
offers: an authenticator code is the second way, so the website is not stuck at L1 (whether that is worth having is
open question 2, §6). (b) **Not for approving a new device** (removed after the review): the approving device's own
authentication and the QR code or code comparison of §9.5 i already do that, an authenticator code adds nothing the
newcomer could check, and it **cannot** stand in for an old device that is not at hand, because the new device needs
the folder key, which only an enrolled device or the recovery key can give. Without an old device, the recovery key is
the way. (c) **An optional extra factor for L3 on the phones**, for people who want two. **Device authentication stays the default on the phones**: it needs
no extra app.

| Item | Design |
|---|---|
| Setting up | Settings > Google Drive > *Authenticator app* > *Set up* (L3 itself: the phone's own authentication, or on the website a passkey; on a website with neither, the recovery key). A 160-bit random secret is made on the device and shown **once** as a QR code (`otpauth://totp/Doorprints:<account email>?secret=…&issuer=Doorprints&algorithm=SHA1&digits=6&period=30`) and as text in groups of four for typing; the person enters one code to prove it works |
| Where the secret lives | **Only wrapped under the folder key in `keys.json`** (AES-256-GCM under `HKDF(folderKey, "doorprints/dpx1/totp")`, inside the MACed file), so every enrolled device can check a code and nothing in Drive holds it in plain. A device unwraps it only for a check and keeps it in memory for that check alone; the website the same (its folder key is open while the page is, §9.3; the secret is not kept beside it). A new folder-key epoch re-wraps it under the new folder key, which reaches the devices and the recovery public key as any epoch does (§9.4); nobody types anything |
| Checking | The current step and one step either side (±30 s for clock skew); **each step accepted once** per device (the last accepted step stored on the device), so a code seen over a shoulder cannot be used again; a valid code is good for one operation within 60 s, like device authentication (§10.2) |
| Wrong codes | 5 wrong codes in a row lock the code check for 5 minutes, then 15, then 60, counted on the device ("Too many wrong codes. Try again in 5 minutes, or use your phone's own check"). On the website the count lives in IndexedDB; clearing the site's data resets it, but also removes the website's device key, which then has to enrol again |
| Lost authenticator app | *Set up again* or *Turn off* is L3: device authentication on an enrolled phone, a passkey on the website, or the recovery key. Never silent, never by e-mail (there is none) |
| Backup codes | **None.** The recovery key already plays that part (it opens everything, so it can also reset the authenticator), and a second set of secrets to keep would be one more thing to lose |
| Words and accessibility | One 6-digit field, the number keypad (`inputmode="numeric"`, `autocomplete="one-time-code"`), paste accepted (spaces removed), digits read as digits by screen readers, the time left not required (any current code works); four languages, hi/ta/te *under review*; the authenticator app's own name is shown as the person's app calls it ("Doorprints") |
| No new library | HMAC-SHA-1 from the platforms: WebCrypto, `javax.crypto.Mac` on Android, CommonCrypto `CCHmac` on the iPhone, behind the same primitives interface as §9.2; the TOTP rule (counter, dynamic truncation, the window, replay, the lockout times) in common code and its TypeScript twin |
| Tests | RFC 6238's own test vectors (the SHA-1 rows) in `docs/schemas/totp-vectors.json`, run by Kotlin and TypeScript; a fake clock: the window edges, a replayed step refused, the lockout and its growth, the reset paths; the QR text's format |

### 10.6 The limit of every check inside the app

Device authentication and a passkey (and, later, an authenticator code) are checks **inside Doorprints**. They stop someone using
an unlocked phone or an open browser from deleting or exposing the data through the app. They do **not** stop someone
who holds the person's Google account: Drive cannot enforce them, and such a person can delete the files with Drive's
own pages or API. What still protects the person then: the encryption (§9: the contents stay unreadable), the copy on
each device (never deleted by Drive), and Google's own sign-in protection (2-Step Verification, which the guide
recommends). This is said in the guide and in [02](02-threat-model.md) (T-E12, RR-25), and **on the screen**, under
the Google Drive settings and in the L2/L3 dialogs: "These checks protect Doorprints on this device. Someone who has
your Google password can still delete your files in Google Drive, but cannot read them. Turn on 2-Step Verification
for your Google account." A rooted phone or a changed app can also skip them.

## 11. Photos on Wi-Fi or mobile data (owner addition, 2026-10-02)

**The owner's words:** "Uploading photos over Wi-Fi should be on by default but the user should have the option to
switch to a normal 4G/5G upload as well."

| Setting (Settings > Google Drive) | Default | What it does |
|---|---|---|
| **Upload photos only on Wi-Fi** | On | Photos wait for an unmetered network |
| (switched off) | — | Photos also go on mobile data. **A monthly limit is deferred** (owner, 2026-10-02); the note below says how much is waiting |
| **Upload photos now over mobile data** | — | A one-off button when photos wait and the phone is on mobile data: "37 photos are waiting, about 48 MB. Upload them now on mobile data?" It does not change the setting |

- The note under the switch: "Photos can use a lot of mobile data. 37 photos are waiting (about 48 MB)."
- **Text and the backups are not photos**: the sync files and backups (about 100 to 500 KB) go on any network by
  default.
- **The phone's own savings are respected**: Android `NET_CAPABILITY_NOT_METERED` (WorkManager `UNMETERED` for the
  photo work), Data Saver (`getRestrictBackgroundStatus()` enabled: no background photo upload on mobile data, the
  one-off still works in the app), roaming (`NET_CAPABILITY_NOT_ROAMING`: no photos while roaming except the one-off),
  battery not low for photo work. iPhone `NWPathMonitor` `isExpensive` (mobile data, a hotspot) and `isConstrained`
  (Low Data Mode: like Data Saver), and Low Power Mode: wait; `URLSession` with `allowsExpensiveNetworkAccess` and
  `allowsConstrainedNetworkAccess` set from the same rule.
- **Resume**: photos use Drive's resumable upload; the session address is kept on the device (Google keeps it about a
  week), so an upload stopped by a change from Wi-Fi to mobile data, with the switch on, continues from the same byte
  on Wi-Fi.
- **The website** cannot reliably know the network: the Network Information API (`navigator.connection`) exists only
  in Chromium and often has no `type` on a computer. So on the website photos upload automatically unless
  `navigator.connection.saveData` is set or `type` is `cellular`; then they wait and *Upload photos now* is shown. The
  setting there reads *Upload photos automatically*.
- **One rule, tested once**: `PhotoUploadPolicy.allowed(conditions, settings, oneOff)` in common code
  with a TypeScript twin, over a fake network-conditions provider (unmetered, metered, roaming, Data Saver or Low Data
  Mode, low power, unknown), with shared vectors.
