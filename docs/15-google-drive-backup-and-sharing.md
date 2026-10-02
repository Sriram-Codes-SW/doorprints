# 15: Google Drive backup, sync, deletion and sharing (design)

| Field | Value |
|---|---|
| Document | Design of Google sign-in for backup, automatic sync, deletion and sharing through each person's own Google Drive (N13 3b, D-28) |
| Version | 0.2 |
| Date | 2026-10-02 |
| Author | Claude (Code), lead |
| Status | Draft for the owner's decisions (§6). Docs only: nothing here is built. The tickets are S4b-BL-70, -73, S4b-BL-115..122, -124 and S4b-BL-125..128 ([10](10-sprint-log.md) §12.7); the order is [14](14-lead-backlog-and-handoff.md) N17 |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-10-02 | Claude (Code), lead | First version, for the owner's request of 2026-10-02 (backup and restore through Google, automatic or by hand, no data loss, shareable) and the owner's addition of the same day (deleting the data in the backup as the person wants). Goals (§1), where in Drive and the owner's Google Cloud setup (§2), deletion (§3), sharing (§4), data and format (§5), the owner's decisions (§6), the phased plan with tickets and checks (§7), risks (§8). |
| 0.2 | 2026-10-02 | Claude (Code), lead | Three owner additions of the same day: **encryption without a passphrase** (new §9: device keys made silently, a folder key wrapped per device with ECDH P-256 and HKDF, a mandatory recovery-key step skippable after a warning, enrolment by approval or the recovery key, revocation with key epochs, sharing by key cards, the `dpx/1` envelope, vectors; replaces v0.1's "no passphrase" of §5.4); **device authentication for deletes and a required device lock** (new §10: levels L1..L3, the line at L2, the app lock's code path, keys that stop working when the lock is removed, the website's passkey); **photos on Wi-Fi or mobile data** (new §11). §1.3, §1.5, §2.1, §3.2, §5.1, §5.4, §5.5, §5.7, §6 (decisions 3, 6 and 8), §7 (S4b-BL-125..128, TC-M-50..54) and §8 (R10..R12) follow. |

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
| G6 | "shareable to others using the same app/website" | §4: a person shares their hunt with someone they know, read-only by default, and can stop at any time. |
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
| Photos | Wi-Fi only by default (*Upload photos only on Wi-Fi*); switched off, mobile data too, up to a monthly limit; Data Saver and roaming respected; *Upload photos now over mobile data* once (§11) | The same (`NWPathMonitor`: `isExpensive`, `isConstrained`) | Automatically, unless the browser says *save data* or *cellular*; *Upload photos now* (§11) |
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
2. **A backup is never overwritten.** Each backup is a new file, written as `partial-…` and renamed only when it is
   complete and checked (the pattern of `AutoBackupWorker`); a partial file never counts as a backup.
3. **Checked after upload.** The manifest carries a SHA-256 per entry (as today); after the upload Doorprints compares
   Drive's own `sha256Checksum` of the file with the one it computed, and downloads and opens the newest backup once a
   week to prove it reads.
4. **Many versions kept.** 7 daily, 4 weekly and 6 monthly backups (17 files, about 1 to 5 MB each without photos);
   decision 5. Retention deletes only finished, checked backups older than the kept set, never the newest good one,
   and **never right after a backup that holds far fewer houses than the one before** (the *shrink guard*: a backup
   with less than half the live houses of the previous one keeps every older backup until the person confirms the
   drop on that device). A wiped or broken phone therefore cannot push the history out.
5. **Automatic pruning goes to Drive's bin**, where Drive keeps it for 30 days; a deletion the person asks for does
   not (§3).
6. **Sync conflicts lose nothing for good.** Two devices editing the same house: the later edit wins, as today
   (`SyncRules`, RR-06); the other version is still in that day's backups.
7. **Deletions travel as tombstones and are kept for ever** (an id and a time, about 100 bytes each), so a device
   that was offline for months cannot bring a deleted house back. This is a deletion *inside* the data; deleting a
   *backup* is §3, a different thing.
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
| **Delete everything Doorprints keeps in my Google Drive** | The whole `Doorprints` folder: backups, sync files, photos, the person's own shared files (§4; sharing with others stops) | Each device's own data (the houses on this phone stay); copies others already imported from a share; copies saved elsewhere with *Save a copy* |

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
> [ ] I understand this cannot be undone
> **Save a copy first** · **Cancel** · **Delete for good**
>
> (then the phone's own check: "Confirm it's you to delete", §10)

- *Save a copy first* opens *Save a copy* with a Full backup to the device, then comes back to the dialog.
- *Delete for good* is enabled only once the box is ticked and 5 seconds have passed since the dialog opened (it
  counts down in its label for screen readers too). No typed word: typing a word is hard on Indic keyboards and for
  some people (decision 8).
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

## 4. Sharing with someone who uses Doorprints

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
   Ravi for Priya") and then merges it quietly at each sync (last edit wins, ADR-27; deletions only from an update
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

- **The sync file** of each device is its whole `data.json` (houses, visits, records, photo metadata with the Drive
  file id, and every tombstone), gzipped: about 100 to 500 KB for a few hundred houses. On each sync a device writes
  its own file if anything is dirty, lists the folder once (`files.list` with `sha256Checksum`, `modifiedTime`), reads
  the other devices' files whose checksum changed since last time, validates each like an import (docs/schemas §6:
  every Drive file is untrusted input) and merges row by row with `SyncRules.keepLocal` (tombstones applied: this is
  the same person's sync, not a restore). The cursor is the map of device id to checksum.
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
  to it.
- **The website**: one tab syncs at a time (`navigator.locks`), so two tabs of one browser are one device.

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
| Android | Google Play services `Identity.getAuthorizationClient(…).authorize(…)` with `drive.file` (`play-services-auth`: a new dependency, justified because the platform has no OAuth API; Google Play services libraries are covered by `NOTICE`'s section 7 permission) | Nothing: Play services keeps and refreshes the grant; the app asks for a fresh access token each sync | Memory only |
| iPhone | `ASWebAuthenticationSession` (the platform's own), authorisation code with **PKCE**, the iOS client, redirect to the reversed client id; no Google SDK | The refresh token | **Keychain**, `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` (not in iCloud Keychain, not in a device backup, deleted by iOS if the passcode is removed, §10.3; so background sync runs only while the phone is unlocked); the access token in memory |

- `AppSettings.toString()` and every log stay free of tokens (S4b-BL-29's rule).
- A phone without Google Play services (some Android phones): Drive is not offered in version 1; *Save a copy* and
  the other ways keep working.
- `invalid_grant` or a revoked grant: "Google Drive disconnected. Your houses are safe on this device. Connect
  again?" Nothing local is deleted, queued work waits.

### 5.6 The refresh model, per platform

- **Website**: no refresh token exists in Google's token model; the app asks again when the hour is over, quietly if
  the person is still signed in to Google and has agreed before (a popup may flash; a browser that blocks it shows a
  *Connect again* chip). This is the website's honest limit of §1.3.
- **Android**: Play services refreshes; the app calls `authorize` each time, and it returns at once without a screen
  while the grant holds.
- **iPhone**: the refresh token at Google's token endpoint with PKCE and no secret; refresh before each sync if the
  access token has under 5 minutes left. In Testing status the refresh token lasts 7 days (§2.3).

### 5.7 What the screens say (labels; hi/ta/te *under review*)

Settings > **Google Drive**: *Connect Google Drive* (Google's own button rules: the Drive name and logo as Google
allows; it is not *Sign in with Google*, because Doorprints keeps no account, only Drive access) · the account's email
· *Automatic backup and sync* (on/off) · *Upload photos only on Wi-Fi* (and, off, *Mobile data limit for photos*; *Upload photos now over mobile data*, §11) · *Devices* (the enrolled devices, *Revoke this device*, *Approve a new device*) · *Recovery key* (saved, or *Make a recovery key*) · *Back up to Google Drive now* · "Last
backup: today 09:30, checked" · *Backups in Google Drive* (the list, each with *Delete this backup*) · *Share my hunt
with…* · *Add a shared hunt* · *Disconnect Google Drive* · *Disconnect on all devices* · *Delete all backups* ·
*Delete everything Doorprints keeps in my Google Drive*. *Import a backup* gets *From this device* and *From Google
Drive*. *Save a copy* gets *Save to Google Drive*. Never "Restore" (12 G.3 rule 3); "bring back" only in explanations.

### 5.8 When the person changes things in Drive by hand

A file moved out of the folder, renamed, or deleted: the app finds its files by `appProperties` and the folder id,
not by name. A missing backup is simply not in the list; a missing photo is "not in your Drive any more" and uploaded
again from the device if the device has it; a missing sync file of another device is that device gone; the whole
folder gone is §3.4's question. A file edited by hand fails the import checks and is skipped with a message.

## 6. The owner's decisions

| # | Decision | Recommendation | Cost of the alternative |
|---|---|---|---|
| 1 | Which Drive scope | **`drive.file` only**, a visible *Doorprints* folder (amends D-28's `drive.appdata`) | `drive.appdata`: files hidden, not shareable, unreadable without the app and lost for ever if Google stops the project. Full `drive`: Google's restricted-scope review and a paid yearly security assessment (breaks zero cost) |
| 2 | How devices sync through Drive | **One sync file per device plus dated backups** (S4b-BL-70's question) | One shared file: two devices overwrite each other (no lock in Drive). A file per record: thousands of calls, slow, near rate limits |
| 3 | Encryption | **On by default, with device keys made silently (no passphrase) and a mandatory recovery-key step at the first connect, skippable only after a plain warning** (§9) | Off by default: Google (and anyone in the Google account) can read the backups. Recovery key with no skip: some people will not connect at all. No recovery key at all: losing every device (or the lock on the only phone) loses the backups for good. A passphrase: typed often, forgotten, total loss |
| 4 | Sharing in version 1 | **A read-only file per person, shared with one Google account; photos off by default** (opt-in, up to 50 MB) | A shared folder both write: depends on undocumented Picker behaviour and gives editor rights. No sharing in version 1: the owner's "shareable" waits |
| 5 | How many backups to keep | **7 daily, 4 weekly, 6 monthly** (about 17 small files) | "The last 4" of 5.2: a mistake noticed after four days cannot be undone from Drive |
| 6 | Automatic backup and sync after connecting; photos on mobile data | **On; text and backups on any network; *Upload photos only on Wi-Fi* on, switchable to mobile data with a 500 MB monthly limit, plus a one-off *Upload photos now over mobile data*** (§11) | Off: people who connect and forget have no backup. Photos on mobile data by default: a surprise data bill. No switch: photos never leave a phone that is never on Wi-Fi |
| 7 | Google's publishing status | **Testing while building; production with brand verification once the release gate passes** (needs the privacy page, S4b-BL-121) | Staying in Testing: only 100 named people, and the iPhone asks again every 7 days |
| 8 | Confirming a deletion, and where device authentication starts | **The dialog for L1; the dialog and the phone's own authentication for L2 and L3** (all backups, the last backup, stop sharing, revoke or approve a device, everything); a tick box and a 5-second delay for L3; no typed word. **On the website, L2 and L3 only with a passkey** (§10) | Authentication for every delete: tiresome for one old backup, and people learn to tap through it. Only for *Delete everything*: deleting all backups would need no proof. A typed word: hard on Indic keyboards. Allowing L2/L3 on the website without a passkey: anyone at an unlocked computer could delete everything |

## 7. The plan: phases, tickets, checks

Each phase is one pull request (or a few), tested on Linux CI against **a fake Drive** (`FakeDriveApi`: an in-memory
Drive with files, folders, `appProperties`, checksums, permissions, quota, and injected 401, 403 rate-limit, 404,
429 and 5xx answers, and a "stop after N calls" switch), in Kotlin common code and TypeScript, with shared vectors
where the rules are shared. Nothing in CI talks to Google. The device and real-Drive checks are TC-M-46..TC-M-54
([06](06-test-plan.md)), for the owner at the end (N15 step 6's rule).

| Phase | Ticket | What | Tests on Linux | Owner or device |
|---|---|---|---|---|
| 0 | this change, S4b-BL-74 | This design; docs/13 re-scoped for client OAuth and Drive; docs/02 §10 | `licence-headers --check` | Decisions §6 |
| 1 | **S4b-BL-70** | The `SyncBackend` seam on both stacks: push, pull since an opaque cursor, photos, "is it behind"; today's code becomes `ServerSyncBackend`, no behaviour change | Existing sync tests through the seam; a `FakeSyncBackend` | — |
| 2 | **S4b-BL-115** | `DriveApi` (Ktor in `:shared`, `fetch` on the website: list, create multipart and resumable, get, update, delete, permissions, about), error mapping, backoff; `FakeDriveApi` | Contract tests run against the fake on both stacks; backoff vectors | — |
| 2c | **S4b-BL-125**, **S4b-BL-126** | Encryption (§9): the `dpx/1` envelope, the primitives per platform, the device keys, `keys.json` with its MAC, the folder key and epochs; then enrolment by approval and by recovery key, the recovery-key screens, revocation and *Lock old backups again*, the share key and key cards. Before any file is written to Drive | Known-answer tests, shared Kotlin/TypeScript envelope vectors, tamper and downgrade tests, an added `keys.json` entry refused, enrolment and revocation on the fake | TC-M-52, TC-M-53 |
| 2s | **S4b-BL-122** | Spike with the owner's client in Testing: does a grant made on the website reach the phones (one project)? Does the mobile Picker return to the Android and iOS apps? Does a picked folder give its files? `sha256Checksum` on upload? | — | Owner's client ids (§2.4) |
| 3 | **S4b-BL-116** | Backups in Drive: the folder and control file, the backup writer without photo bytes, `partial-` and rename, the checksum check, retention with the shrink guard, *Back up to Google Drive now*, *Import a backup* > *From Google Drive* through the existing preview | Fake: partial never listed, retention keeps 17, shrink guard, a corrupted file refused, import vectors | — |
| 4 | **S4b-BL-117**, **S4b-BL-73** | Connecting per platform: the website's GIS token client with the CSP and COOP change and the self-hosted Noto subsets (one live UI run), Android's AuthorizationClient, the iPhone's PKCE flow and Keychain; Settings > Google Drive (connect, account, disconnect, disconnect on all devices) | Token handling against fakes; CSP and header tests; the iOS klib compile | TC-M-46 |
| 4b | **S4b-BL-127** | The device lock (§10.3): required to switch Drive on, checked before every run, the pause and its words, the auth-bound Keystore keys and `WhenPasscodeSet` Keychain items; the delete levels and device authentication (§10.1, §10.2) with the operation-bound signature on Android 11+; the website's passkey (and PRF where offered) | A fake authenticator: passed, denied, cancelled, timed out, lock removed half way; a fake lock state: no lock refuses connect, a removed lock pauses; the emulator test by `AppLockEmulatorTest`'s rules | TC-M-50, TC-M-51 |
| 5 | **S4b-BL-118** | `DriveSyncBackend`: the per-device files, merge, photos, the triggers of §1.3, the status line, one-tab lock on the website, "the folder was deleted" question | Two and three fake devices converging; offline then back; tombstones; a hand-edited file skipped | TC-M-47 |
| 5b | **S4b-BL-128** | Photos on Wi-Fi or mobile data (§11): the switch, the monthly limit, the one-off, Data Saver, roaming, Low Data Mode and Low Power Mode, resumable sessions kept across network changes, the website's *Upload photos now* | `PhotoUploadPolicy` vectors on both stacks over a fake network-conditions provider; a resumed upload continues from its byte | TC-M-54 |
| 6 | **S4b-BL-119** | Deleting from Drive (§3): the three actions, the dialog, *Save a copy first*, permanent delete, the resumable list, offline refusal, automatic backup off afterwards and on other devices | Fake: nothing left after each action; a stop half way reports what is left and *Try again* finishes; offline refused with nothing deleted; shared files' permissions removed first; no re-creation afterwards | TC-M-48 |
| 7 | **S4b-BL-120** | Sharing version 1 (§4): *Share my hunt with…*, the consent text, the outbox file, *Add a shared hunt* with the Picker, *Stop sharing*, *Remove a hunt shared with me* | Two fake accounts: share, pick, update, delete travels, stop sharing ends access | TC-M-49 |
| side | **S4b-BL-121** | The website's privacy page (`privacy.html`, static, four languages) for the consent screen, linked from About and the apps | `seo.spec`-style check that it exists and is `noindex`-free | Owner signs off the text |
| side | **S4b-BL-124** | docs/08: incidents for Drive (the project stopped or the client deleted, a leaked Picker key, a user's lost access) and the guide's Google Drive pages | `mkdocs build --strict` | — |

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
- **TC-M-49**: two Google accounts: share, the Google email arrives, *Add a shared hunt* through the Picker on the
  website and on a phone, an update and a delete travel, *Stop sharing* ends the updates and the other keeps their
  copy.
- **TC-M-50**: device authentication for deletes: *Delete this backup* asks nothing more; *Delete all backups*, *Stop
  sharing* and *Delete everything* ask for the phone's PIN, fingerprint or face; cancel deletes nothing; waiting over a
  minute after the check asks again; on the website the same with a passkey, and without one only L1 is offered.
- **TC-M-51**: the device lock: on a phone with no screen lock *Connect Google Drive* refuses with the words of §10.3;
  with a lock, connect, then remove the lock: Drive pauses with its message, nothing is uploaded or deleted; set a lock
  again: the phone must re-enrol (approval or recovery key) and the houses on it are intact.
- **TC-M-52**: a new device: enrol by approval (the 8-digit codes match, the enrolled phone asks for its own
  authentication), then *Revoke this device* from the other and see it stop.
- **TC-M-53**: the recovery key: save it at connect (print or copy; the two groups asked back); on a fresh browser or
  a reinstalled phone with no other device, open the backups with it; *Skip* shows the warning and Settings says "No
  recovery key".
- **TC-M-54**: photos on mobile data: with the switch on, photos wait on 4G/5G and go on Wi-Fi; switch it off: they go
  on mobile data up to the limit; Data Saver (Android) and Low Data Mode (iPhone) hold them; *Upload photos now over
  mobile data* shows the size and works once; an upload interrupted by leaving Wi-Fi resumes.

## 8. Risks

| # | Risk | What we do |
|---|---|---|
| R1 | Google suspends or changes the Doorprints project (policy, brand verification) | Visible files that outlive the app (§2.2); the self-hosted server and file sharing stay; docs/08 incident (S4b-BL-124) |
| R2 | The mobile Picker or the cross-client grant does not work as read | Spike first (S4b-BL-122); fallback: pick on the website; sharing on phones waits |
| R3 | The website's one-hour token makes automatic backup feel unreliable | Said on the screen (§1.3); the phones are the reliable automatic path |
| R4 | A person deletes or edits files in Drive by hand | §5.8; every file read is validated as an import |
| R5 | Clock skew between devices decides last-write-wins wrongly | As today (RR-06); backups keep the other version |
| R6 | Sharing leaks contact details or photos | Off by default, the consent text, viewer rights only, *Stop sharing* |
| R7 | A wiped device prunes the history | The shrink guard (§1.4 item 4) |
| R8 | A deletion surprises (half done, or re-created later) | §3.3 and §3.4; TC-M-48 |
| R9 | `play-services-auth` adds size and a Google dependency | The only new library; phones without Play services keep every other feature |
| R10 | Lost keys make the data unreadable (no recovery key, every device gone, a screen lock removed) | The recovery-key step at connect (§9.4), enrolment by approval (§9.5), the plain warnings; decision 3 |
| R11 | A crypto mistake (format, nonce reuse, a platform difference) | One format in common code, known-answer and cross-stack vectors, tamper tests, a review pass by the strongest model (§9.8) |
| R12 | Photos on mobile data cost the person money | Wi-Fi only by default, a monthly cap when switched off, Data Saver and Low Data Mode respected (§11) |

## 9. Encryption without a passphrase: device keys and a recovery key (owner addition, 2026-10-02)

**The owner's words:** "public/private-key encryption, without user intervention if possible". This replaces the
passphrase idea of v0.1 (§5.4). **Recommendation: on by default**, with keys each device makes by itself, no
passphrase and no prompt in daily use, plus **one recovery-key step when Drive is first connected** (decision 3).

### 9.1 The scheme in one paragraph

Every file Doorprints writes to Drive (backups, sync files, photos, shared files) is encrypted on the device before
upload with its own random **content key** (AES-256-GCM, chunked, §9.8). Each content key is wrapped (encrypted) with
the account's **folder key** (a random 256-bit AES key, numbered by *epoch*). The folder key is wrapped once for each
**enrolled device's public key** and once for the **recovery key**, and these wraps live in `Doorprints/keys.json`.
A device opens a file by unwrapping the folder key with its private key (once per run) and then the file's content
key. Wrapping content keys under a folder key, rather than directly for every device, means that adding a device is
one new wrap, not a rewrite of every file, and that a photo costs one cheap AES unwrap, not one public-key operation.

### 9.2 Which public-key algorithm: ECDH P-256 with HKDF-SHA-256

| | ECDH P-256 + HKDF + AES-GCM (chosen) | RSA-OAEP-256 | X25519 |
|---|---|---|---|
| Website (WebCrypto) | Yes, everywhere; private key `extractable: false` | Yes | Only recent browsers |
| Android (Keystore, minSdk 26) | Key agreement in the Keystore from API 31 (`PURPOSE_AGREE_KEY`, StrongBox when present); **API 26-30: a software P-256 key whose private part is sealed by a Keystore AES-GCM key** and opened in memory only while used (weaker; RR-21) | Keystore OAEP uses MGF1-SHA-1 before API 34, so it cannot open what WebCrypto's OAEP-SHA-256 wraps (SHA-1 would work but is a second, older choice) | Keystore from API 33 only |
| iPhone | **Secure Enclave** P-256 key agreement (`SecKeyCopyKeyExchangeResult`, callable from Kotlin/Native through the Security framework) | Keychain only; the Secure Enclave has no RSA | No Secure Enclave support |
| Wrap size per recipient | 65-byte ephemeral key + 48 bytes | 256 bytes | 32 + 48 bytes |

A wrap for a device: a fresh ephemeral P-256 key pair; `shared = ECDH(ephemeral, devicePublic)`; `kek =
HKDF-SHA-256(shared, salt = ephemeralPublic ‖ devicePublic, info = "doorprints/dpx1/wrap/" + kid)`; the folder key
sealed with AES-256-GCM under `kek`. **Kotlin Multiplatform:** the envelope format, HKDF (built on an HMAC primitive),
chunking and the vectors are common code; the primitives (AES-GCM, HMAC-SHA-256, ECDH, random bytes, key storage) are
an interface with `actual` implementations on platform APIs only: `javax.crypto` and the Keystore on Android, the
Security framework plus a few lines of Swift calling CryptoKit for AES-GCM on the iPhone (CommonCrypto's GCM is not
public), WebCrypto on the website. No JVM-only call in `commonMain`, no new library.

### 9.3 Device keys: made silently, never leave the device

At the first connect each device makes its key pair without asking anything: Android Keystore (bound to the screen
lock, §10.3), iPhone Secure Enclave (`kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`, `.privateKeyUsage`), website a
non-extractable WebCrypto key kept as a `CryptoKey` in IndexedDB. The public key, its id (`kid`, the first 16 bytes of
the SHA-256 of the public key) and the device's name go into `keys.json`. Nothing about this is shown in daily use.

**`keys.json` is authenticated.** A person who gets into the Google account could otherwise add their own public key
and have every device wrap future files for it. So each entry and the whole file carry an HMAC-SHA-256 under a key
derived from the folder key (`HKDF(folderKey, "doorprints/dpx1/dir")`), which only enrolled devices and the recovery
key can compute; a device ignores an entry or a file whose MAC fails, and says so (T-S13).

### 9.4 The recovery key (mandatory step, skippable only after a warning)

At the first connect, after the device key exists, the app shows a **recovery key**: 128 random bits, written as 26 Crockford base32
characters plus one check character, in groups of four (`DP7K-3QXW-…`). It wraps the folder key
(`HKDF(recoveryKey, "doorprints/dpx1/recovery")`, AES-256-GCM). The app never stores it.

> **Save your recovery key.** If you lose this phone and have no other device with Doorprints, this key is the only
> way to open your backups. Nobody else has it, not even Doorprints. Print it, or keep it in a password manager. Do
> not keep it only in this Google account.
> **Print** · **Copy** · **I have saved it**

*I have saved it* asks for two of the groups back to prove it. *Skip* is offered only after: "Without a recovery key,
losing all your devices means losing your backups for good. Skip anyway?" and a tick box; Settings then shows "No
recovery key" with *Make a recovery key*. Making a new one (L3, §3.6) replaces the old wrap.

### 9.5 The hard parts, and the answers

| # | Case | What happens |
|---|---|---|
| i | **A new or reinstalled phone** has no private key | It makes its key pair and asks to join: (A) **approval from an enrolled device**: the new device shows an 8-digit code (from the hash of its public key); an enrolled device shows "Pixel 8 asks to join, code 4821 7730", the person compares, approves with device authentication (L2), and that device wraps the folder keys for the newcomer; or (B) **the recovery key** typed on the new device. Without either, the new device can back up its own new data under a new key set but cannot open the old files |
| ii | **The website's data is cleared** (or a new browser) | The key in IndexedDB is gone: the same as (i) |
| iii | **Every device lost** | Only the recovery key opens the files (on the website too: *Import a backup* > a downloaded `.dpx` file and the recovery key). Without it the data in Drive cannot be read by anyone, ever. The app says this at connect and in Settings |
| iv | **A device lost or stolen: revoke it** (L2) | Its entry leaves `keys.json`, a **new folder-key epoch** is made and wrapped for the remaining devices and the recovery key; new files use it. Old files stay readable to the old key, so a thief who has both the phone and access to the Google account could open the backups made before the revocation; *Lock old backups again* (offered after a revoke) rewrites the kept backups and sync files under the new epoch (small); photos only on request (large, Wi-Fi) |
| v | **Sharing** | The recipient's app has an account **share key** pair (its private part wrapped under the recipient's own folder key, so all their devices have it). The recipient sends a **key card**, a Doorprints link or QR code carrying the public key (`https://doorprints.web.app/k#…`), on any app; the sharer's app shows its fingerprint ("ask Priya to read her code: 3F7A 91C2") and wraps the shared file's content key for it. *Stop sharing* deletes the file; new shares never use her key |
| vi | **What Google still sees** | File names, sizes, times, the number of files (so roughly the number of photos), who a file is shared with, and the account. The names carry no house data (`Doorprints-backup-<date>.dpx`, photos `p-<random>.dpx`); `appProperties` carry only random ids, no house id or plaintext checksum. Drive's preview, search and virus scan see only noise, by design |
| vii | **Readable without the app** | No longer: an encrypted backup opens only in Doorprints (any platform, the website included, with a device key or the recovery key). If Google stops the project (R1), the person downloads the `.dpx` files from Drive and opens them with the website and the recovery key; *Save a copy* to the device stays unencrypted, as today |

### 9.6 The envelope format (`dpx/1`)

`"DPX1"` magic, a 2-byte header length, a JSON header (`v`, `alg` `A256GCM-STREAM-64K`, `epoch`, `wrappedKey` (the
content key under that epoch's folder key), `noncePrefix`, `chunkSize` 65536, `inner` (`doorprints-backup/2`,
`sync/1`, `photo/1`), `shareWraps` for shared files), then the chunks. The inner bytes are the formats that exist (the
backup ZIP with its manifest and per-entry SHA-256, the sync file, the JPEG). A reader that does not know `dpx/1`
refuses the file with "update the app" (the versioning rule, docs/schemas §1.1); the backup's own number does not
change for encryption.

**Downgrade and tamper.** The control file `doorprints.json` (MACed like `keys.json`) records `encryption: dpx/1`;
once it does, devices ignore an unencrypted file in the folder (someone with the Google account cannot slip a plain
sync file in). Every chunk authenticates the header (AAD) and its index and a last-chunk flag, so a changed header, a
reordered, dropped or truncated chunk, a swapped key id or a flipped bit is refused.

**Two checksums.** Drive's `sha256Checksum` of the ciphertext is compared after upload (§1.4 item 3); the manifest's
SHA-256 of each plaintext entry is checked after decryption.

### 9.7 Performance, large photo sets

Chunks of 64 KiB, each its own AES-GCM operation with the nonce `noncePrefix (7 bytes) ‖ index (4 bytes) ‖ last (1
byte)`, so memory stays flat for any file size and WebCrypto (which has no streaming AEAD) can do it chunk by chunk.
AES-GCM runs in hardware on current phones and in the browser's native code, far faster than the upload; the one
public-key operation is the folder-key unwrap, once per run (tens of milliseconds in the Secure Enclave), and each
photo then costs one AES unwrap.

### 9.8 Tests

Known-answer tests for each primitive on each platform (RFC 5869 for HKDF, NIST vectors for AES-GCM and P-256 ECDH);
shared envelope vectors in `docs/schemas/dpx-vectors.json` (fixed keys, nonces and plaintexts; a file written by Kotlin
opens in TypeScript and the reverse, the repository's parity-vector pattern); tamper tests (each item of §9.6);
`keys.json` MAC tests (an added entry is refused); enrolment by approval and by recovery key, revocation and the new
epoch, all against the fake Drive with fake key stores. Crypto code is reviewed by the strongest model (docs/14 §7).

## 10. Device authentication and the device lock (owner addition, 2026-10-02)

**The owner's words:** "Deletes crossing a certain level would need the user to authenticate using device
authentication, and this feature's usage also needs device lock to be enabled."

### 10.1 Levels

| Level | Actions | What is asked |
|---|---|---|
| **L1** | *Delete this backup* (not the last one left); *Remove a hunt shared with me*; *Disconnect Google Drive* on this device; turning automatic backup off | The dialog of §3.2 |
| **L2** | *Delete all backups*; deleting the **last** remaining backup; *Stop sharing with…* / deleting a shared file; *Revoke this device*; approving a new device (§9.5 i); *Disconnect on all devices* | The dialog **and device authentication** |
| **L3** | *Delete everything Doorprints keeps in my Google Drive*; anything that weakens protection: making or skipping a recovery key after connect, turning encryption off (not offered in v1) | The dialog with the tick box and the 5-second delay, **and device authentication** |

**Recommendation: the line is between L1 and L2.** L2 and L3 are the actions after which nothing is left to go back
to, or that change who can read the data. One backup out of seventeen is not.

### 10.2 How it asks (the app lock's code path, S4b-FR-5)

- `rememberDeviceCredentialCheck` (`:ui`, `CredentialCheck` PASSED / CANCELLED / NO_SCREEN_LOCK) with a stricter class
  for deletes: Android BiometricPrompt with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (the app lock allows
  `BIOMETRIC_WEAK`), the keyguard's confirm-credential screen on API 26-28; iPhone `LAPolicyDeviceOwnerAuthentication`
  (Face ID, Touch ID or the passcode). The `USE_BIOMETRIC` permission is already declared.
- **On Android 11+ the authentication is bound to the operation**: a Keystore HMAC key with per-use authentication
  (timeout 0) signs the operation id through a `CryptoObject`; the delete runs only with that signature, so an overlay
  or a tap cannot fake a pass. Below API 30 the plain result is used, as the app lock does.
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

- **A passkey for this browser** (WebAuthn, platform authenticator, `userVerification: "required"`: Windows Hello,
  Touch ID, the phone's screen lock), registered when Drive is connected on the website. **L2 and L3 on the website
  need it**; the page checks the signed assertion against the public key it stored. Where the browser offers the
  WebAuthn **PRF extension**, the website's device key is also sealed with a key derived from the passkey, so the key
  itself needs the person's verification (once per page load).
- **Without passkey support** the website does L1 only, and says: "To delete all backups, use Doorprints on your phone,
  or a browser with a passkey."
- **What is weaker, and what the person is told** when connecting on the website: "This browser cannot show Doorprints
  whether your computer is locked. Anyone who can use this browser profile can open your houses and your Drive backups
  here. Use your own computer, and *Disconnect Google Drive* on a shared one." The website check runs in the page, so
  it guards against someone at an unlocked computer, not against a changed page (nothing can, without a server).

## 11. Photos on Wi-Fi or mobile data (owner addition, 2026-10-02)

**The owner's words:** "Uploading photos over Wi-Fi should be on by default but the user should have the option to
switch to a normal 4G/5G upload as well."

| Setting (Settings > Google Drive) | Default | What it does |
|---|---|---|
| **Upload photos only on Wi-Fi** | On | Photos wait for an unmetered network |
| (switched off) **Mobile data limit for photos** | 500 MB a month | Photos also go on mobile data up to the limit (Off, 100 MB, 500 MB, 1 GB, no limit); counted on the device per calendar month |
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
- **One rule, tested once**: `PhotoUploadPolicy.allowed(conditions, settings, usedThisMonth, oneOff)` in common code
  with a TypeScript twin, over a fake network-conditions provider (unmetered, metered, roaming, Data Saver or Low Data
  Mode, low power, unknown), with shared vectors.
