# Owner's manual-testing and release runbook

| Field | Value |
|---|---|
| Document | What the owner does, in order, once all development is done: install, the device checks, the reviews, the release gate, cutting `v0.1.1`, and what to do when something fails |
| Written | 2026-10-21, against `main` after #399 (not a versioned document: the ids below are copied from the documents named, and those win if they differ) |
| Status | A plan, not a record. **Nothing here is ticked, signed or accepted.** The decision to ship is the owner's, in the owner's own words |
| Sources | [docs/ops/manual-test-checklist.md](manual-test-checklist.md) (MT rows), [docs/06](../06-test-plan.md) §16 (TC-M rows), [docs/13](../13-release-security-checklist.md) (the gate), [docs/14](../14-lead-backlog-and-handoff.md) §1, §2, §7, [release-run-2026-10-19.md](release-run-2026-10-19.md), [pentest-2026-10-21.md](pentest-2026-10-21.md) |

**How to use it.** Print it or copy it somewhere private, then tick the boxes in your copy. Do the parts in order. In each device
session do every row of that device, so each device is set up once. Write the real result on the row's own
`Pass / Fail / Date` line in the checklist (the device, OS and build next to the date), or paste the result to a Claude session
and ask it to record it. **A failure is never fixed on the device**: stop, collect what part 6 lists, and tell the session.

**State on `main` (2026-10-21).** The school is released on the website (#389) and on the phones (#391); the voice free-plan
refusal is merged (#388); kinds a person adds are merged on the website (#394) and the phones (#397); the first release is
**v0.1.1** (the tag must equal `versionName` `0.1.1`). A stale **draft** release and tag `v0.1.0` exist and are deleted in part 5.
Nothing below has been run on a device. No Play Store release and no public server until part 5 passes.

## Map of the work

| Part | What | Roughly | Needs |
|---|---|---|---|
| 1 | Prerequisites | 1 h, once | The devices, accounts and keys of 1.1 |
| 2 | Install | 30 min | A computer, the phones |
| 3 | The device checks, in four sessions: **A** computer and browsers, **B** Android phone, **C** iPhone and Mac, **D** GitHub only | A 3 h, B 5 h, C 3 h, D 20 min | Part 1 |
| 4 | The review pass: Hindi, Tamil, Telugu, the school's words, India's outline | a few days (other people) | Native speakers |
| 5 | The release gate, `v0.1.1`, publish, delete `v0.1.0` | 1 day plus the pentest steps you choose | Parts 3 and 4 done |
| 6 | What a failure triggers | per failure | A session |
| 7 | After the release | 30 min | A session |

## 1. Prerequisites

### 1.1 Devices, accounts and keys

| What | Why | Needed by |
|---|---|---|
| An **Android phone with API 26 to 28** (Android 8 to 9) **and a recent one** (API 34 or newer; one with Android 11 if you can, for the Drive `CryptoObject` path), with a screen lock set, TalkBack, Google Play services, Hindi, Tamil and Telugu available | The phone rows; the Drive device-key paths differ by API (MT-70) | B |
| An **iPhone on iOS 17 or later** and a **Mac with Xcode 26.4.1 and JDK 21** (only if you want the iPhone checked; [ios/README.md](../../ios/README.md) *Build on a Mac*: `brew install xcodegen`, `cd ios && xcodegen generate`, open `Doorprints.xcodeproj`, choose your team in Xcode with a free Apple ID for a build on your own iPhone; do not commit that change) | MT-104..MT-108, MT-113, J9 on the iPhone. Without it these rows stay *not run* and the release notes must say so | C |
| A **test Google account** that holds nothing you care about, listed as a test user of the Google Cloud project's consent screen; the Android OAuth client for the build you test (the debug package and its SHA-1; for the release APK the release certificate; [docs/15](../15-google-drive-backup-and-sharing.md) §2.4); the repository variable `GOOGLE_ANDROID_CLIENT_ID` | Google sign-in, Drive, MT-122, MT-123 | A, B |
| **Your own AI keys, test keys only:** one **free-tier Gemini key** (from a Google project with no billing), one **paid Gemini key** (a billing-enabled project), an OpenRouter key from an account that never bought credits and one from an account that did, a Groq key and a Sarvam key (free account) | MT-124..MT-126 (TC-M-92..TC-M-94), MT-109, MT-110, the voice rows. **Never paste a key anywhere but the app's own field** | A, B |
| Optional: the repository secret `SARVAM_EVAL_API_KEY` (Settings > Secrets and variables > Actions) and `AI_API_KEY` for the evals | MT-111; the AI evals of part 5 | D |
| A second browser profile (a fresh one counts as a second device), headphones with a cable and a Bluetooth pair, a second phone to ring the phone under test | MT-105, MT-114, the two-device Drive rows | A, B, C |
| `adb` on a computer, `apksigner` (Android build-tools), `sha256sum`, `git`, and either the GitHub CLI `gh` or the GitHub website | Part 2 and part 5 | all |
| Test data only: three fake houses and two fake schools; fake phone numbers such as `90000 00001`; never a real landlord, school or your own list | Every row | all |

Before the Drive rows make a **Save a copy** Full backup of every browser and phone you test with: the deletion rows really delete.

## 2. Install (30 minutes)

| Step | Where | How | Pass / on failure |
|---|---|---|---|
| 2.1 Web app | https://doorprints.web.app | Open it in Chrome or Edge; do a hard reload (Ctrl+Shift+R) so the new service worker serves the new build; Settings > About shows the build; on a phone use *Add to Home Screen* (Chrome menu on Android, Share menu in Safari) | Map loads, language switch works. Fails: tell the session with the build id |
| 2.2 Android, tests | GitHub > Actions > **Android** > the latest green run **on `main`** > artifact `doorprints-debug-apk` (needs a GitHub login) | Unzip, copy `app-debug.apk` to the phone, allow installing from that source, open it. A debug build cannot be installed over a release build: uninstall one before the other | App opens on the Map. Use this build for the device sessions |
| 2.3 Android, the release candidate | The draft release made by `release.yml` from the tag (part 5.3), or `android.yml`'s `release` job artifact | Only for the gate rows C1..C7 and D1..D4; see 5.3 for the checksum and signer check | |
| 2.4 iPhone | Your Mac | Part 1.1 steps, then Run the `Doorprints` scheme on your iPhone. Optional self-check: Edit Scheme > Run > Arguments `-DoorprintsSelfCheck` prints `DOORPRINTS-SELFCHECK done PASS` | Fails: paste the Xcode console lines to the session |
| 2.5 Tooling for later | Your computer | `git clone` the repo (or use the session) | |

## 3. The device checks

Tick-lists. Time is an estimate per row in minutes. **Result** is `☐ pass ☐ fail`. The row id's steps are in
[manual-test-checklist.md](manual-test-checklist.md) (the section is named in each table heading); TC-M ids are in
[docs/06](../06-test-plan.md) §16. *Pass / Fail record:* the row's own `Pass / Fail / Date` line. *On failure:* part 6.

**Notes that apply to many rows.**
- The release has no *Experimental: more than houses* switch: the school is a *Schools* row (off) under Your data (website) or Settings
  (phones) > *What you track*; *Show Schools* turns it on and Doorprints then opens on the chooser with two cards. The rows say so.
- Hindi, Tamil and Telugu strings are *under review*: read them for sense and fit here, and send wording problems to the
  review of part 4.
- Ids renumbered on 2026-10-21: the phones-with-the-school row of docs/06 is now **TC-M-95** (TC-M-81 is the Sarvam one, MT-109..MT-111), and the AI-declines-a-text row is **MT-133** (MT-88 is the iPhone Drive enrolment row).
- Light and dark theme, the largest text size and, where named, a screen reader belong to the row; do them while you are on the screen.

### Session A: computer and browsers (about 3 hours)

Chrome or Edge on Windows with Hello (E1), Firefox or Chrome on Linux (E3) if you have it, a browser with the network panel,
the live site, and Safari on a Mac for MT-129. Do A-1 before A-2.

| Id | Device | What to do, in one line | Expected | Min | Result | Notes |
|---|---|---|---|---|---|---|
| MT-93 | Web | Your data > *What you track*; show Schools; chooser, chip, Back; hi, ta, te, dark, largest text, a screen reader (checklist §18) | Houses and Schools rows, chooser cards with *Default*, chip *Schools*; no word "kind" or "space"; the choice is this browser's only | 25 | ☐ pass ☐ fail | |
| MT-94 | Web | A school space: add one, make it the default, deep link, *Save a copy* backup, turn Show Schools off, import into a fresh profile (§18) | `data.json` has `search.kinds`; nothing lost; old backups still import | 25 | ☐ pass ☐ fail | |
| MT-100 / TC-M-75 | Web | Walk a school's pages and the four copies (web page, Markdown, CSV, spreadsheet) by eye (§20) | No house word (price, score, BHK, broker) in any school page or copy | 20 | ☐ pass ☐ fail | |
| MT-102 / TC-M-77 | Web | Schools list chips (Board, Medium, Bus), *Save a copy of* select, Compare's best marks, backup and import words (§20) | The kind's words with two kinds on; the house list unchanged | 20 | ☐ pass ☐ fail | |
| MT-115 / TC-M-83 | Web | School as the default; turn Houses off; delete and *Restore* the house; turn Show Houses on again and Show Schools off, then on (§20) | Nothing lost or hidden; no house can be added while Houses is off | 15 | ☐ pass ☐ fail | |
| MT-117 / TC-M-85 | Web | A school's *Checklist* (8 items) and Compare of three schools (§19) | Checklist score row, best marks on score, lower fees and commute; admission fee unmarked; the house's Compare as before | 20 | ☐ pass ☐ fail | |
| MT-118 / TC-M-86 | Web | School Questions (6) and *School visits*; plan a visit (§19) | School's own words; house viewings and questions as before; no broker from a school phone | 15 | ☐ pass ☐ fail | |
| MT-119 / TC-M-87 | Web | Filter by Board and Bus; *Save a copy* of the Schools space (§19) | File named `Doorprints-schools-...`; headings *School visits*, *Questions for a visit* | 10 | ☐ pass ☐ fail | |
| MT-120 / TC-M-88 | Web | The tour and the walk sheet with the school as the default; texts say *entries*; English and Hindi (§19) | School's words; with the house alone every text as before | 15 | ☐ pass ☐ fail | |
| MT-121 / TC-M-89 | Web | TC-M-25 steps 1 to 3 at zoom 5 to 8 over Kashmir and Arunachal in the house's space and the school's space (§19; the synthetic `zz_all` space is in no release build and is optional) | The lines are identical in every space | 20 | ☐ pass ☐ fail | |
| TC-M-25 | Web | On the live site, hard reload: country view, zoom 5 to 8, one line, admin lines at 9 to 14, state line (docs/06 TC-M-25; [03](../03-design.md) ADR-22) | One solid outline around J&K, Ladakh and Arunachal; no dashed or double line; expected minors recorded, not failures | 30 | ☐ pass ☐ fail | |
| MT-127 | Web | *Voice input* off at first; on; tap the button; deny, then allow (§16) | Permission asked at first use only; the keyboard still works | 10 | ☐ pass ☐ fail | |
| MT-128 | Web | Speak the rent sentence in en, hi, ta, te; edit; send; a fake phone number; an injected instruction (§16) | Transcript shown and editable first; `[phone]` in the request; the instruction is text | 15 | ☐ pass ☐ fail | |
| MT-129 | Web (Safari Mac) | Record 5 s in Safari, Chrome and Firefox; look at the request type (§16) | `audio/mp4` in Safari, `audio/webm;codecs=opus` in Chrome and Firefox; no button without a microphone | 10 | ☐ pass ☐ fail | iPhone Safari is in session C |
| MT-130 | Web | Silent 10 s; talk past 30 s (60 s for other providers); switch tab (§16) | "Nothing was heard", nothing sent; stops by itself; leaving ends it | 10 | ☐ pass ☐ fail | |
| MT-131 | Web | The button with NVDA or a screen reader, largest text, four languages (§16) | Announced as a button with a name; target 44 px; no overlap | 10 | ☐ pass ☐ fail | |
| MT-132 | Web | After several recordings make a copy and a readable copy; look for audio; one network request (§16) | No audio at rest; one provider host only | 10 | ☐ pass ☐ fail | |
| MT-114 | Web | Say a sentence, then start a call that takes the microphone or unplug the headset; again with nothing said; again with Cancel (§19) | Words so far kept with *The recording ended early*; nothing said, nothing shown; Cancel keeps nothing | 10 | ☐ pass ☐ fail | TC-M-82 |
| MT-124 / TC-M-92 | Web | **Free** Gemini key, *Voice input* on (§23) | One probe to `generativelanguage.googleapis.com`, a 429 naming a `...-FreeTier` quota id; *Speak* dimmed with the free-plan sentence; no microphone prompt | 15 | ☐ pass ☐ fail | Write the quota id down |
| MT-125 / TC-M-93 | Web | **Paid** Gemini key; speak; reload; then use the free key for an ordinary Ask until a free-tier 429 (§23) | Enabled after one probe (200); no second probe for 7 days; the free 429 turns voice off | 15 | ☐ pass ☐ fail | |
| MT-126 / TC-M-94 | Web | OpenRouter free and paid keys; a `:free` model; Groq and Sarvam confirmation switch (§23) | Free refused; paid allowed; `:free` refused; Groq and Sarvam refused until "my plan is paid" is on; the verdict is in no copy (`voice.tier`) | 15 | ☐ pass ☐ fail | |
| MT-109 | Web | Choose Sarvam, save your key, speak Hindi, Tamil, Telugu, English (§16.2 table) | Words land in the box; stops at 30 s; disclosure names `api.sarvam.ai` | 15 | ☐ pass ☐ fail | TC-M-81 (Sarvam) |
| MT-110 | Web | Look for the Sarvam key in *Save a copy*, a backup, a sync file and a Drive backup; *Remove Sarvam key*; *Remove all data* | The key is in none; removal removes it; the AI key is untouched | 10 | ☐ pass ☐ fail | |
| MT-19 / MT-20 / MT-21 | Web | Real Google sign-in with the test account, the recovery key, *Back up now* and *Import a backup* from Drive (§8; MT-22..MT-38 are the rest of the website Drive run: sync, passkeys, deletion L1 to L3, joining, revoke) | Consent lists `drive.file` only; the recovery key shown once | 20 | ☐ pass ☐ fail | I1, I4 |
| MT-122 / TC-M-90 | Web | Photos synced to Drive; delete a candidate; *Recently deleted* > *Restore*; Drive disconnected; a file removed; after *Delete everything* (§21) | Photos back with the right words each time ("N of M", "not connected", "no copy is left"); nothing uploaded or deleted by a restore | 20 | ☐ pass ☐ fail | Real test Drive |
| J9 web | Web | Make the four files of 3.1 below. Your data > *Kinds you add* on > import `gym.json`: preview, **Add this kind**, turn on, use it as a space; draw the map in it; *Remove kind*. Import the three `bad-*.json` | The gym works as a space and India's boundaries are drawn as in the house's (TC-M-25); *Remove kind* keeps the entries; the three bad files are refused with a reason and nothing is stored | 20 | ☐ pass ☐ fail | docs/13 J9 |

**3.1 The four J9 files.** From a clone of the repo, in an empty folder, run (`R` is the clone's path):

```sh
node -e 'const fs=require("fs"),v=JSON.parse(fs.readFileSync(process.argv[1]+"/docs/schemas/kinds/user-kind-vectors.json","utf8"));
const gym=v.valid[0].file,w=(n,o)=>fs.writeFileSync(n,typeof o=="string"?o:JSON.stringify(o,null,2));w("gym.json",gym);
const a=structuredClone(gym);a.labels.many.text="<img src=x onerror=alert(1)>";w("bad-markup.json",a);
const b=structuredClone(gym);b.style="https://tiles.example/style.json";w("bad-style.json",b);
w("bad-proto.json",v.invalid.find(i=>i.code=="prototypeKey"&&i.json).json)' "$R"
```

Move the four files to each phone (AirDrop, Drive, a cable) for sessions B and C. The expected refusals are `textChars`, `mapKey`
and `prototypeKey`.

### Session B: Android phone (about 5 hours; do it once on the API 26 to 28 phone for the first half, then on the recent phone)

Debug APK of 2.2, Hindi, Tamil and Telugu installed as phone languages, TalkBack, the test Google account, headphones. Rows with
a walk (MT-116) need a place outside.

| Id | Device | What to do, in one line | Expected | Min | Result | Notes |
|---|---|---|---|---|---|---|
| MT-112 / TC-M-95 | Android | Fresh install: Map first, Settings > *What you track* with *Houses* on, *Schools* off, no *Experimental* switch; import a backup with a school; Show Schools; TalkBack; largest text; hi, ta, te tip has no *preview* mark; About says "Remember every place you've seen." (§19) | House app until Show Schools; nothing cut at 200 % | 20 | ☐ pass ☐ fail | |
| MT-96 / TC-M-72 | Android | Release then debug build: the chooser, the chip, Back, a school space's words; hi, ta, te; TalkBack (§19) | As the row; hint shows once; the last shown row cannot be turned off | 25 | ☐ pass ☐ fail | |
| MT-97 / TC-M-72 | Android | School space: save a school, Compare, copy and backup names, a shared listing into *Schools*, a link, import on a second phone (§19) | Names `Doorprints-schools-...-csv.zip` and `Doorprints-backup-...zip`; a school phone makes no broker | 25 | ☐ pass ☐ fail | |
| MT-101 / TC-M-76 | Android | A school's list, page, Compare, areas, places, five copy formats by eye (§20) | No house word | 20 | ☐ pass ☐ fail | |
| MT-103 / TC-M-78 | Android | Filter rows, *Save a copy of*, Compare's best marks and TalkBack "(best)", Map notes in a school space (§20) | The kind's words; none of "house" or "Hunt mode" in the school space | 20 | ☐ pass ☐ fail | |
| MT-115 / TC-M-83 | Android | The default kind and Houses off; the reminder's *Start Hunt mode* refused with one line; Hunt stops when Houses is turned off (§20) | Lines as the row; no walk started | 15 | ☐ pass ☐ fail | |
| MT-116 / TC-M-84 | Android | **Outside:** Hunt mode in Schools; walk to a saved school; lock the phone; stay at an empty place (§20) | Alert about 100 m away, own channel *School and street alerts*, lock screen *Doorprints alert* with no name, *Are you at a school?* | 40 | ☐ pass ☐ fail | iPhone step 5 is in C |
| MT-117 / TC-M-85 | Android | Checklist and Compare of schools; hi, ta, te; TalkBack (§19) | As the web row | 15 | ☐ pass ☐ fail | |
| MT-118 / TC-M-86 | Android | Questions, a visit 2 minutes ahead, the reminder on *School visit reminders*, lock-screen text, Settings > Viewings, no broker (§19) | Reminder says *Visit to <school> in 2 min*; lock screen no name | 20 | ☐ pass ☐ fail | |
| MT-119 / TC-M-87 | Android | Filters and the school copy (§19) | As the web row | 10 | ☐ pass ☐ fail | |
| MT-120 / TC-M-88 | Android | Tour, walk sheet, Settings texts, the visit notification *A visit at ...* (§19) | As the web row | 15 | ☐ pass ☐ fail | |
| MT-121 / TC-M-89 | Android | TC-M-25 steps 1 to 3 in the house's and the school's space | Identical lines in each | 20 | ☐ pass ☐ fail | |
| TC-M-25 | Android | TC-M-25 on the Map: country view to zoom 14, labels too (the emulator draws none) | As the web row | 30 | ☐ pass ☐ fail | |
| MT-127 | Android | Rationale, *Not now*, deny and *Open settings*, *Only this time*, revoke while recording (§16) | Asked after the tap and rationale only; denial leaves a working screen | 15 | ☐ pass ☐ fail | |
| MT-128 | Android | Speak, review, then send, in four languages; a fake number; an injected sentence (§16) | As the web row | 15 | ☐ pass ☐ fail | |
| MT-130 | Android | Silent 10 s; 30 s stop; home, lock, a call, rotate; a 2 MB cap (§16) | Leaving the app ends it; the status-bar microphone goes out | 15 | ☐ pass ☐ fail | |
| MT-131 | Android | TalkBack on the button; largest text; four languages; both themes (§16) | Named, 48 dp target, no overlap | 10 | ☐ pass ☐ fail | |
| MT-132 | Android | Copies and a Drive backup hold no audio; `adb shell run-as app.doorprints ls -R files cache no_backup` (debug build); airplane mode (§16) | No `.m4a` `.webm` `.ogg` `.wav`; one host | 10 | ☐ pass ☐ fail | |
| MT-114 / TC-M-82 | Android | Say a sentence, then connect or unplug a headset; nothing said; Cancel; quick *Stop* presses (§19) | As the web row; *Stop* never freezes | 10 | ☐ pass ☐ fail | |
| MT-124 / TC-M-92 | Android | The free Gemini key on the phone (Settings > AI features), then hi, ta, te (§23) | Free-plan sentence in Settings and on the button; no microphone permission asked | 10 | ☐ pass ☐ fail | |
| MT-109 / MT-110 | Android | Sarvam with your key: the 30 s stop; the key in no copy, backup, sync or Drive file (§16.2 table) | As the web rows | 15 | ☐ pass ☐ fail | |
| MT-52 | Android | Settings > Back up to Google Drive > Connect (phone with a lock): Google consent, recovery key (§14) | Consent lists `drive.file` only; key shown once; *Next* needs the tick; no key in `adb logcat` | 15 | ☐ pass ☐ fail | Google sign-in on a phone, N19 |
| MT-53 | Android | Connect with no lock; unticked consent (§14) | Nothing asked of Google without a lock; unticked = denied | 10 | ☐ pass ☐ fail | |
| MT-54 | Android | *Back up now*; the list; download the `.dpx`; *Import a backup* from Drive (§14) | Encrypted `DPX1`; imports through the normal preview | 15 | ☐ pass ☐ fail | |
| MT-55..MT-74 | Android | The rest of the Drive run, in order: sync, enrolment, recovery key, revoke, deletion L1 to L3, worker, photos on Wi-Fi, hostile files, disconnect, API 26 to 28 against 11 against 12+, four languages, TalkBack (§14, TC-M-46..TC-M-54) | As each row. These also feed gate rows I4 to I14 | 120 | ☐ pass ☐ fail | One result per row |
| MT-122 / TC-M-90 | Android | *Recently deleted* > *Restore* for a candidate deleted on the website with its photos (§21 step 6) | Photos come back on the phone | 10 | ☐ pass ☐ fail | |
| MT-123 / TC-M-91 | Android | Delete, back up, move the clock 31 days, back up again; *Retry photos* after an offline Restore (§22) | Photo files go to Drive's bin only when no kept backup could hold them; each photo comes back once; Retry never duplicates | 30 | ☐ pass ☐ fail | |
| J9 Android | Android | Settings > *What you track* > *Kinds you add* on > Import a kind file (the system picker) with `gym.json`; add, turn on, use; map; remove; then the three `bad-*.json` | As J9 web; the picker finds the file; refusals show their reason | 20 | ☐ pass ☐ fail | docs/13 J9 |

### Session C: iPhone and Mac (about 3 hours, only if you run the iPhone)

An iPhone with the app from 2.4, the Mac with Xcode (Window > Devices and Simulators), a test AI key, headphones.

| Id | Device | What to do, in one line | Expected | Min | Result | Notes |
|---|---|---|---|---|---|---|
| MT-104 | iPhone | Voice off at first; the app's own sheet before the system question; *Not now*; *Don't Allow* and *Open settings* (§16.2) | The question appears once, after a tap and the sheet | 15 | ☐ pass ☐ fail | |
| MT-105 / TC-M-82 | iPhone | While recording: Home, lock, Control Center, a call, Siri, headphones in and out, music before (§16.2) | Each ends the recording; words so far kept with *The recording ended early*; music resumes | 20 | ☐ pass ☐ fail | |
| MT-106 | iPhone | A real recording in four languages; 5 s silence; past 1:00; permission off mid-way; airplane mode (§16.2) | Transcript first, editable; `audio/wav`; errors leave the box as it was | 20 | ☐ pass ☐ fail | |
| MT-107 | iPhone | Download the app container in Xcode and read the console while recording (§16.2) | No audio file, no base64, no words in the log | 15 | ☐ pass ☐ fail | |
| MT-108 | iPhone | VoiceOver on the button, the largest text, four languages, both themes (§16.2) | Announced once per state; no overlap | 15 | ☐ pass ☐ fail | |
| MT-113 / TC-M-95 | iPhone | Install: Map first; Settings > *What you track* with *Schools* off and no switch; VoiceOver; Show Schools; the location prompt says *places you save* (§19) | As MT-112 on the iPhone | 20 | ☐ pass ☐ fail | |
| MT-114 | iPhone | Say a sentence, then ring the phone or change headphones; nothing said; Cancel (§19) | As the web row | 10 | ☐ pass ☐ fail | |
| MT-118 / TC-M-86 | iPhone | Plan a school visit with a reminder (§19) | Body says *Visit to <school> at <time>* | 10 | ☐ pass ☐ fail | |
| MT-116 / TC-M-84 | iPhone | Step 5 of the row: the Hunt card as one switch with VoiceOver and the alert text (§20) | Read as one switch in both spaces | 10 | ☐ pass ☐ fail | Outside, with the Android walk |
| MT-127..MT-132 | iPhone | The iPhone parts of the voice rows: permission, speak-review-send, auto-stop, VoiceOver, no audio at rest (§16) | As the web and Android rows | 30 | ☐ pass ☐ fail | One result per row |
| MT-129 | iPhone | Record in iPhone Safari (and the Home Screen app) (§16) | `audio/mp4` and a right transcript | 5 | ☐ pass ☐ fail | |
| MT-109 / MT-110 | iPhone | Sarvam with your key; the key in no copy or backup (§16.2 table) | As the web rows | 15 | ☐ pass ☐ fail | |
| J9 iPhone | iPhone | Settings > *What you track* > *Kinds you add* on > Import a kind file (the **Files** picker) with `gym.json`; add, turn on, use; map; remove; then the three `bad-*.json` | As J9 web; the picker is compiled but has never run on a device, so a crash or an empty picker is the likeliest failure | 20 | ☐ pass ☐ fail | docs/13 J9 |
| MT-122 | iPhone | *Restore* with photos from the real Drive (§21 step 6, optional) | Photos return | 10 | ☐ pass ☐ fail | Optional |

### Session D: GitHub only (about 20 minutes, no device)

| Id | Device | What to do, in one line | Expected | Min | Result | Notes |
|---|---|---|---|---|---|---|
| MT-111 | GitHub | Actions > **AI evals** > Run workflow on `main`, suite `voice`, provider `gemini` (secret `AI_API_KEY`); with `SARVAM_EVAL_API_KEY` set, provider `sarvam` too; read the summary (§16.2 table) | Entity error by language and the stop-rule line; clips are synthetic speech | 20 | ☐ pass ☐ fail | Optional with Sarvam |

Every row appears once per device that has a part of it (MT-114, MT-109, MT-110, MT-122, MT-124 and the voice rows). A row done on a
different device than named (an iPhone for an *E4* row) is fine: write the device on the line.

## 4. The review pass

### 4.1 Native-speaker review of Hindi, Tamil and Telugu (send to one reader per language)

Hindi, Tamil and Telugu ship marked *under review* (owner decision 2026-09-23); this pass is what moves them on. Send each reader
**the files below and the screens of the row**, ask for corrections as a list of `key: current text: better text: why`, and
paste the list to a session, which edits the files in a pull request.

| What to send | Where | The regions to read |
|---|---|---|
| The website's words | `web/src/app/i18n/hi.ts`, `ta.ts`, `te.ts` (each 2,065 keys; the English key is in `en.ts`) | `school.*` (141 keys: the school's pages, the checklist `school.check.*`), `ukind.*` and `userKinds.*` (kinds a person adds), `voice.*` (46), `kinds.*`, `chooser.*`, `space.*`, `status.TAKEN`, `driveSync.photoRetention`, and the Sarvam words |
| The phones' words | `android/ui/src/commonMain/composeResources/values-hi`, `values-ta`, `values-te` (`strings.xml`; also used by the iPhone) and `android/app/src/main/res/values-hi`, `values-ta`, `values-te` (`strings.xml`) | `school_*` (129), `ukind_*` (96), `userkinds_*` (49), `voice_*` (55), `kinds_*`, `chooser_*`, `space_*`, `schools_title`, `status_TAKEN`, `drive_sync_photo_retention`, the location prompt (`places you save`) and the iPhone's four `InfoPlist.strings` |
| The privacy page | `web/public/privacy.html`: the Hindi, Tamil and Telugu sections (`h-hi`, `h-ta`, `h-te`), including *Children* and the voice-audio sentence ([13](../13-release-security-checklist.md) G5) | Read all four sections against each other: same limits |
| The user guide | `guide/i18n/hi`, `guide/i18n/ta`, `guide/i18n/te` (pages the guide's `mkdocs.<lang>.yml` lists) | At least `other-kinds`, `houses`, `google-drive`, `settings-and-privacy` |
| The vocabulary | [docs/12](../12-brand-and-naming.md): *Import a backup*, *Save a copy*, *readable copies*, *Add a shared listing* in each language (drafts pending native review) | The table of words |

Method for the reader: open the live site or the app in that language, use the screens of MT-93, MT-117, MT-118, MT-127 and
MT-131 for ten minutes, and mark every sentence that is wrong, stiff or too long for its button. Record the reader's name and date
in your message, not in a document.

### 4.2 The school's content (the drafts are yours to approve or change)

Read each in English first, then in a language you can judge. Say *OK* or send a changed text; a session edits
`docs/schemas/kinds/school.json` and `school-questions.json` and regenerates the words with `node tools/gen-kind-words.mjs`.

| Item | Where to read it | Question to answer |
|---|---|---|
| The 8-item checklist | A school's page, *Checklist* (MT-117); `school.json` `checklist`: safe drop-off and pick-up, classrooms and playground, drinking water and toilets, teachers are approachable, class size, homework and daily schedule, sports and activities, safety and CCTV | Are these the eight things a parent checks? Anything missing or wrong? (Ten is the limit) |
| The Compare criteria | A school's Compare (MT-117); `school.json` `compare.criteria`: the eight items, `fees` per year (lower is better), `commuteMinutes` (lower is better); the admission fee is shown but not marked | Right criteria and right direction? |
| The 6 questions | Settings > Questions in the Schools space (MT-118); `school-questions.json`: fees, admission, class size, safety, water and toilets, hours and holidays | Right questions, in the order a parent asks? |
| The headings | The School visits and Questions for a visit headings, the page title *School details*, the list headings (MT-100, MT-101) | Do they read like a school, not a house? |
| The viewings words | *School visits*, *Plan a visit*, *Visit to <school>*, "open days", the reminder channel *School visit reminders* (MT-118) | Visits and open days: the right words? |
| The *Taken* label | A school's status chips and list (MT-100) in en, hi, ta, te: `status.TAKEN` reads "Taken" (Hindi *ले लिया*) and the school's file uses the same word; the design lets a kind say another ([03](../03-design.md) §18.4 item 8: a school's `TAKEN` may read *Admitted*) | Does "Taken" fit a school? If not, name the word in each language |

### 4.3 India outline geometry review (S4b-BL-174c, `docs/10` §12.7)

Not the drawn boundary (that is ADR-22's reviewed data and TC-M-25): this is **the filter outline** a place search tests a point
against, built from the repository's own boundary lines plus two kinds of new geometry in open water.

- **Files:** `web/src/app/shared/india-outline.geojson` (the website) and `android/shared/src/commonMain/kotlin/app/doorprints/shared/location/IndiaOutlineData.kt` (the phones, generated; `IndiaOutlineTest` pins that both hold the same points); the builder is `web/scripts/geo/build_in_outline.py` (`--check` says if the committed files are stale); the source lines are `web/public/geo/in-boundaries.geojson`.
- **Look at:** (1) the land ring, 16 lines from Sir Creek anticlockwise to the Sundarbans (the largest hand-over, 27 km, is at Singalila): it matches India as the Government of India depicts it; (2) the sea closure and the two island boxes (Andaman and Nicobar, Lakshadweep): they are new geometry, in open water, used only to filter search results and never drawn; (3) a point within a few hundred metres of a border can fall either side (the data is 1:10m).
- **How:** open `india-outline.geojson` in https://geojson.io (or QGIS, free) over a base map; zoom to the hand-overs and the sea closure; search 5 places just inside and 5 just outside India in a Doorprints place search (the vectors list 16 border towns and 22 towns abroad in `docs/schemas/india-place-vectors.json`).
- **Answer:** *accept* or a list of coordinates to change. A change to boundary linework re-runs TC-M-25 on the web and Android; a session does the rebuild.

## 5. The release gate and `v0.1.1`

Run [docs/13](../13-release-security-checklist.md) §3 on **the release candidate: the exact commit** that will be tagged. Part 3 and
part 4 should be finished first, because a fix changes the candidate (part 6). The last full run, on the old candidate `11072090`,
is [release-run-2026-10-19.md](release-run-2026-10-19.md): its *OWNER* rows are your list, its *NOT RUN* rows a session can redo.

### 5.1 Fix the candidate and let a session do its part

1. Pick the candidate: the tip of `main` once everything is merged. Note the sha.
2. Ask the session: *"Run docs/13 on `<sha>` and write `docs/ops/release-run-<date>.md`."* It does A1, B1..B7, F11b, G1, G3..G5, H3 and the code-level part of the rest. Tell it the old record's `M-1` points: G1's photo wording was re-read for #383 and the Android jobs (A1, C1, C2) must be on the new candidate.
3. In Settings > Code security of the repository check **Dependabot alerts** (the 2026-10-19 run found them off, so A6 could not be read), **Dependabot security updates**, **Secret scanning** and **Push protection** are on (free for a public repository); then read **Security > Code scanning** and **Security > Dependabot** (a triage note may exist as `docs/ops/dependabot-triage-2026-10-21.md` if another pull request added it).

### 5.2 The owner-only rows of docs/13

| Rows | What to do | Time | Where the result goes | On failure |
|---|---|---|---|---|
| **A2** | Download artifact `backend-zap-api-scan` of the Backend run on the candidate; read `zap.html`: each Medium and Low is known or triaged | 15 min | The run record's A2 line | A new alert: tell the session (fix, or a backlog item) |
| **A3** | `android/ci/mobsf-accepted.json` against artifact `android-mobsf-report` | 10 min | A3 line | A Not-true reason: tell the session |
| **A4** | Read the warning lines of the Security run's *Licence check* step | 10 min | A4 line | |
| **A5, A6** | Security tab: no open error or high code-scanning alert; no open High or Critical Dependabot alert (needs 5.1 step 3) | 10 min | A5, A6 lines | An alert: fix branch (part 6) |
| **C1..C7** | On the **release APK** (5.3): `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` (reviewed permissions and the two exported components of C1a); `apksigner verify --print-certs` (C2); `adb logcat` while adding, syncing, exporting (C3); the TC-S-12 `adb shell am start` deep-link commands (C4); a fresh install with Hunt mode and the microphone after the tap (C5, MT-127); *Save a copy* and the weekly backup write only where you chose (C6); captive portal only if sync changed (C7) | 60 min | C lines | |
| **D1..D4** | MobSF dynamic run: `docker run -it -p 8000:8000 opensecurity/mobile-security-framework-mobsf@sha256:83bc8aaf940d66344b7c10ebc12e921086bec43369a8259582e6dc258f2924b0`, Dynamic Analyzer on an emulator, use the app five minutes (add a house, Hunt mode, export) | 45 min | D lines | |
| **F1..F3, F5, F7..F9** | Actions > **AI evals** > Run workflow on `main` (needs `AI_API_KEY`); read the scorecard: `injectionResistance` 1.0, the model named, leak markers, thresholds, the kind cases (J6) | 30 min | F lines | A failed case: tell the session with the scorecard link |
| **F10** | The provider's spend cap is set (a console setting, AI-015) and a 429 is seen at the quota | 15 min | F10 line | |
| **F11** | Re-read the provider facts ([ai/voice-input](../ai/voice-input.md) §8): the Gemini probe model still has no free tier on Google's pricing page; a free 429 still names a `FreeTier` quota id (MT-124); OpenRouter still answers `is_free_tier` | 20 min | F11 line | If a fact changed the refusal rule must change, and RR-33's acceptance depends on it: tell the session before shipping |
| **G2** | Play's data-safety form: **n/a** unless a Play release is planned (none; owner decision 2026-09-23). Write *n/a: no Play release* | 1 min | G2 line | |
| **H1** | Re-read [03](../03-design.md) §11.1 (the self-certification) against DST's 2021 guidelines and negative list | 30 min | H1 line | |
| **H2** | TC-M-25 on the release build, web and Android, house and school spaces: done in session A and B | (done) | H2 line | |
| **I1, I3** | Google Auth Platform > Data access: exactly `drive.file`; Credentials > the Picker key restricted to `https://doorprints.web.app/*` and the Picker API | 15 min | I lines | |
| **I4..I14, I16..I18** | The Drive run: MT-52..MT-74, MT-122, MT-123 on two phones and the test account (TC-M-46..54, TC-M-90); I15 is n/a (deferred, S4b-BL-129); I2 was passed in code on 2026-10-19; I17: the consent screen links the privacy page | (done in B) | I lines | |
| **J4, J5** | With two kinds shown: *Delete all in this space* in one and look at the other; *Recently deleted* of each; *Undo*; *Remove all data* lists every kind's count (website, then phone) | 20 min | J lines | |
| **J8, J9** | The phones' fresh-install behaviour (MT-96, MT-112, MT-113) and kinds a person adds (J9 rows of sessions A to C) | (done) | J lines | |

**The deep pentest** ([13](../13-release-security-checklist.md) §5) is owed before a Play launch and before Drive goes to production, not
before this GitHub release. [pentest-2026-10-21.md](pentest-2026-10-21.md) §6 lists what the sandbox could not run; do those when
you choose: the self-hosted server on a test host (ASVS L2: `curl`, 30 wrong keys, `sqlmap` on a test database, `testssl.sh`), the
ZAP active scan against a local copy (never the live site), the rooted-emulator Frida and objection session on the release APK,
MobSF dynamic (D1..D4), real OAuth and Drive on two phones (I4..I18), and AI with a real provider (F1..F11). Its findings
PT-1, PT-3, PT-4 and PT-5 are Low and fixed; none is open.

### 5.3 Cut `v0.1.1`

`release.yml` runs on a pushed tag `v*.*.*`, requires the tag to **equal** `versionName` (`android/app/build.gradle.kts` says
`0.1.1`; `ios/project.yml` `MARKETING_VERSION` says `0.1.1`) and the commit to be on `main`, and always makes a **draft**. Nothing
publishes by itself.

| Step | Do | Check |
|---|---|---|
| 1 | Candidate green: Actions shows Web, Backend, Android, Shared-iOS, Security and CodeQL green on `<sha>` | [13](../13-release-security-checklist.md) §2 first box |
| 2 | `git fetch origin && git log -1 origin/main` is `<sha>`; `grep versionName android/app/build.gradle.kts` prints `0.1.1` | The same commit the gate ran on |
| 3 | Optional dry run: Actions > **Release** > Run workflow, branch `main` | It builds and signs and creates no release |
| 4 | `git tag -a v0.1.1 -m "Doorprints 0.1.1" <sha> && git push origin v0.1.1` (or create the tag in the GitHub UI on that commit) | The **Release** workflow starts; the `release` environment holds the four signing secrets |
| 5 | Wait for the run: a **draft** release `v0.1.1` appears under Releases with `doorprints-v0.1.1.apk`, its `.sha256` and notes holding the signer fingerprint | A failed guard names the reason (tag not equal to `versionName`, commit not on main) |
| 6 | Download both files; `sha256sum -c doorprints-v0.1.1.apk.sha256`; `apksigner verify --print-certs doorprints-v0.1.1.apk` | Passes, and the SHA-256 digest equals the fingerprint in the notes **and** the README's `ea23f3ca1175eaacef73f124449895537d6c16ff4f8de4da1e6d00d749be4381` (README.md, *Install the Android app*) |
| 7 | Run C1..C7 and D1..D4 on **this** APK (5.2); install it on a phone and open it | A different fingerprint, a failed checksum or a failed row: do not publish; part 6 |
| 8 | Fill the release record (docs/13 §6; below), then **publish**: Releases > the `v0.1.1` draft > Edit > *Publish release* | Only you publish. A session cannot and must not |
| 9 | Delete the stale draft and tag, **after** step 8 or at least after the `v0.1.1` draft exists: Releases > the `v0.1.0` draft > Delete; then `git push origin --delete v0.1.0` (the tag, annotated at `499a0fd5`, pointing to the old commit `7095fc7b`); or with `gh`: `gh release delete v0.1.0 --cleanup-tag --yes` | `git ls-remote --tags origin` lists `v0.1.1` and no `v0.1.0`; Releases shows no draft `v0.1.0` |

**The release record (docs/13 §6).** Copy the block from [13](../13-release-security-checklist.md) §6 into your message and fill it
with the results above: release `v0.1.1`, commit, tag and draft link, date, run by, kind (a GitHub APK release; web deploys already
run), each part pass, fail or n/a, the `F-` ids and any accepted risks, the pentest line (*not run, owed before a Play launch and
Drive go-live*), and `Decision: ship` or `do not ship` with your name. Ask the session to enter it in the release's entry in
[docs/10](../10-sprint-log.md) ([13](../13-release-security-checklist.md) §4) and to update [14](../14-lead-backlog-and-handoff.md)
§1 and §2 and the CHANGELOG (`Unreleased` to `0.1.1`). **A failure blocks the release unless you accept the risk in writing**
(an `RR-` line in [02](../02-threat-model.md) §7 with the finding, the reason and an expiry date); a High or Critical finding cannot be
accepted and is fixed first. A session records only what you write; it never records your acceptance for you.

## 6. What a failure triggers

1. **Stop on the row.** Do not retry until it works and do not change settings to get past it; a second attempt that behaves differently is itself a finding.
2. **Paste this to a session** (a new chat is fine; name the repository):

   | Give | Example |
   |---|---|
   | The row id and part | `MT-116 step 2` / `docs/13 C1` |
   | The build | The APK's run number or `versionName`, or the web build id from Settings > About, or the `main` sha |
   | The device | Model, Android or iOS version, browser and version, language, theme, text size, screen reader on or off |
   | What you did | The steps as you took them, with the test data used (never a real number or key) |
   | Expected and actual | The row's *Expected* line and what you saw, in words |
   | Evidence | A screenshot or screen recording; for Android `adb logcat -d` around the time (remove any personal data); for the web the console and the Network line; for the iPhone the Xcode console lines |
   | Where it stands | Anything else that failed in the same session |

3. **The session works the loop** ([14](../14-lead-backlog-and-handoff.md) §7): a backlog row when it is a defect, a branch `fix/<topic>` made from the latest `main` (never your device), a test that fails first, the fix, the local checks, one pull request, a merge when green. It never marks your row *pass*.
4. **You re-run the failed row** on the new build (install the new debug APK or hard-reload the site), and only the rows the fix can touch: a map or boundary change re-runs TC-M-25; a change to words re-checks the screen in each language; a change to a web page is followed by the live UI run of part 7.
5. **A change after the gate changes the candidate.** The gate is run on the exact commit. Rows tied to the changed code are re-run on the new commit; a new tag is needed. **If `v0.1.1` was only a draft**, ask the session to guide you through deleting that draft and its tag and tagging the new commit (`release.yml` allows only a tag equal to `versionName` on a commit from `main`, as `v0.1.0` showed on 2026-10-19; if you prefer, bump to `0.1.2` in a pull request). **Never move or reuse a tag that has been published.**
6. **Not a failure:** a row that cannot be run as written (a Play-only row); a record of a limit the row names as expected (TC-M-25's listed minors). Tell the session so the row is corrected in the checklist.

## 7. After the release

1. Tell the session: *"`v0.1.1` is published, the record is `<your block>`, the draft `v0.1.0` and its tag are deleted."* It updates [14](../14-lead-backlog-and-handoff.md) §1 and §2 (the release posture, the open owner list), the CHANGELOG, [13](../13-release-security-checklist.md)'s record in [10](../10-sprint-log.md), and the status of the rows you ran. It does not change the rule *no Play Store release and no public server until the gate passes*: that line moves only when you say so.
2. **Live UI test of the website**, only if a merge since the last run changed `web/**`, `.github/workflows/web.yml` or `.github/firebase-tools/**` (an Android-only or docs-only merge skips it), once its `Web` deploy has finished: `cd tools/live-ui && npm ci && npx playwright install chromium && ONLY=pages,flows node live-ui.js` (the areas the change can touch: `ONLY=mobile`, `ONLY=pages`, ...; the full run, no `ONLY`, only before a release or when you ask). It exits 1 on any failure; report the counts per area and look at the map screenshots in `out/shots`. In a cloud session Chromium must first trust the session proxy's CA; you allow that step. A failure becomes a ticket before the next merge.
3. Keep: the Hindi, Tamil and Telugu *under review* marks until each reader has said the language is right; the Play data-safety form and the pentest before any Play launch; the optional `SARVAM_EVAL_API_KEY` and the next `MT-111` run when you change the voice providers.
