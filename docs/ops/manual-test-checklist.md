# Manual test checklist: real devices, real Google, real servers

| Field | Value |
|---|---|
| Document | The complete manual test list for Doorprints before real-system testing starts and before Android/iOS development continues: every check a person redoes on real devices, whether or not an automated suite or a review session already covered it |
| Version | 0.2 |
| Date | 2026-10-06 |
| Author | Claude, senior reviewer |
| Status | Draft |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-10-06 | Claude, senior reviewer | First version, from the full-system review of `main` `8c367a40`: environments, the phone-vs-computer differences, TC-M-25..45 and TC-M-46..56 of [06](../06-test-plan.md) rewritten as runnable steps, Google sign-in and Drive, passkeys (Windows Hello, phone by QR, security key, no-lock computer), the recovery-key last resort, deletion L1/L2/L3 with a partial run, enrolment and revocation, languages and themes, offline/PWA, the map's worker message, India's boundaries, search, export/import round trip, Android and server smoke tests, security checks. |
| 0.2 | 2026-10-06 | Claude | The review's should-fix items were fixed in the same pull request: the camera header (MT-32 step 5, MT-36), the passkey state kept across a reload (MT-28 step 7), the recovery-key wording (MT-19). |

## 1. How to use this list

- **One line per test, filled in by hand.** Each test ends with `Pass / Fail / Date: ____  by: ____`. Write the device, browser and build (the `main` commit or the deploy's build id from *Settings > About*) next to the date. A failure becomes a ticket in [10](../10-sprint-log.md) §12.7 and a fix on a branch from `main`; never fix on a device.
- **Who runs it.** *Owner*: needs the owner's Google account, phones or machines. *Anyone*: a tester with the live site and a fresh browser profile. *Native reader*: a Hindi, Tamil or Telugu speaker.
- **"Automated or reviewed by"** names the automated test (TC-U/TC-I ids or spec names) that covers the same rule, and whether the review session of 2026-10-06 ("the reviewer") ran it. *Reviewer ran it: no* means the only evidence so far is code reading; **do not skip those.**
- **Before the Drive tests**, make a *Save a copy* Full backup (ZIP) of every device you will test with, and use a **test Google account** that holds nothing you care about. Deletion tests really delete.
- **Order.** Run §3 (website basics) before §8 (Drive); §8 tests build on each other (connect first, then backups, then passkeys, then deletion). Run §9 (security) last on the same browser.
- **Reference data:** TC-M ids are the manual rows of [06](../06-test-plan.md) §16; the Drive design is [15](../15-google-drive-backup-and-sharing.md); labels are the English ones of `web/src/app/i18n/en.ts` and the user guide (`guide/docs`).

## 2. Environments

| Code | Environment | Notes |
|---|---|---|
| E1 | Windows 11 computer **with** Windows Hello (PIN, fingerprint or face), Chrome or Edge | The owner's main machine. Passkey through Windows Hello. |
| E2 | Windows computer **without** Hello and without a password or lock | A local account with no PIN. The card must point to setting one up and must **not** offer the recovery key. |
| E3 | Linux desktop, Chrome or Chromium and Firefox | No platform authenticator: the passkey must be the phone (QR) or a security key. |
| E4 | Android phone (API 26-28 if at hand, plus a current one), Chrome | Website on the phone's browser **and** the Android app (debug APK from the latest `Android` run on `main`, artifact `doorprints-debug-apk`; the release APK only for the release gate). |
| E5 | iPhone, Safari (and the website added to the Home Screen) | The iPhone app itself needs a Mac for a device build; its CI gate is the simulator (TC-I-37/38/41). |
| E6 | Chrome, Edge, Firefox, Safari on a computer | Browser matrix for §3, §4, §5 and §8 (Firefox's encryption import path, Safari's passkeys). |
| E7 | A second computer or phone browser for the two-device Drive tests | A fresh profile counts as a second device. |
| E8 | A self-hosted server (`docker compose up --build -d`) on a Windows or Mac computer, Tailscale Serve optional | Only for §11. |

Fill in the device, OS and browser version with every Pass/Fail line.

## 3. Website basics and phone-vs-computer differences

Source: `guide/docs/phone-or-computer.md`, TC-M-24, TC-M-26 (automated part).

#### MT-01 Layout on a phone and on a computer
- **Area:** Web UI. **Who:** anyone. **Env:** E4 or E5 browser, and E1/E3.
- **Setup:** `https://doorprints.web.app`, a fresh profile, two houses saved.
- **Steps:** (1) Phone: the map is on top and the list below (screens up to 760 px); the menu is a bar at the bottom; zoom and location buttons at the map's bottom right (top right in landscape). (2) Computer: the list beside the map; the menu at the top; the controls at the map's top right. (3) Phone: one finger scrolls the page, two fingers move the map, the short hint says so; in add mode one finger moves the map and a tap places the pin. (4) Computer: drag moves the map; click places the pin.
- **Expected:** as each step says; no sideways scroll of the page at 360 px and 320 px wide; touch targets at least 44 px on the phone.
- **Automated or reviewed by:** TC-M-26 `tools/live-ui` (mobile area, 323 checks: layout, 44 px targets, keyboard overlap). Reviewer ran it: no (needs Chromium trusting the session proxy). Unit: `web/src/app/pages/map/*.spec.ts` (phone layout, TC-U-77), Reviewer ran it: yes (in the 2 736-test run).
- Pass / Fail / Date: ____  by: ____

#### MT-02 Installing the website
- **Area:** PWA. **Who:** anyone. **Env:** E1 (Chrome/Edge), E4 (Chrome), E5 (Safari).
- **Steps:** (1) Computer Chrome/Edge: the install button in the address bar, or the *Install Doorprints* card's **Install app**; install; open from the Start menu. (2) Android Chrome: browser menu > **Install app**. (3) iPhone Safari: **Share > Add to Home Screen**; the card shows the three iOS steps (`pwa.iosStep1..3`) and no install button.
- **Expected:** each opens as a standalone window with the icon; the four-language card text matches the platform; **Not now** hides the card.
- **Automated or reviewed by:** `pwa.service.spec.ts`, TC-M-15 (not yet run). Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-03 Files, printing and sharing on each device
- **Area:** Your data. **Who:** anyone. **Env:** E1, E4.
- **Steps:** (1) Phone: *Your data > Save a copy > PDF > Open print view* opens a new tab; the hint `data.pdfAndroidHint` shows; follow it to Save as PDF. (2) Computer: PDF prints from the same page. (3) Phone: *Import a backup* from the Files app and from a WhatsApp attachment opened with Doorprints. (4) Android, installed site: share a portal listing to Doorprints (MT-61 step 5).
- **Expected:** the saved PDF holds the copy, not the Your data page; the import preview appears before anything is written.
- **Automated or reviewed by:** TC-M-20 (not yet run), TC-U-114. Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-04 What the website does not do on any device
- **Area:** Scope. **Who:** anyone. **Env:** E1, E4.
- **Steps:** Look for Hunt mode, *Wake me in my hunting areas* and background location on the website; set a viewing reminder, close the tab, wait past its time.
- **Expected:** none of the three exists on the website; the reminder shows only while Doorprints is open (the help text says so).
- **Automated or reviewed by:** TC-U-105 (website while-open reminders). Reviewer ran it: yes (unit).
- Pass / Fail / Date: ____  by: ____

#### MT-05 Browser-decided behaviour
- **Area:** Web platform. **Who:** anyone. **Env:** E6.
- **Steps:** (1) A browser without WebGL 2 (or `chrome://flags` WebGL off): the map is replaced by a message, the rest works. (2) Firefox: the one-time "keep data permanently" (persistent storage) prompt appears at first save. (3) Safari: data survives a day without visiting? (Safari may evict; the iOS eviction warning shows for an uninstalled site.)
- **Expected:** as said; no blank page in any case.
- **Automated or reviewed by:** `map-style.spec.ts` (`mapErrorMessageKey`), `local-store.spec.ts`. Reviewer ran it: yes (unit).
- Pass / Fail / Date: ____  by: ____

## 4. Languages and themes

#### MT-06 Four languages, every page
- **Area:** i18n. **Who:** anyone (hi/ta/te content: native reader, MT-07). **Env:** E1 and E4.
- **Setup:** *Settings* (the gear) > Language.
- **Steps:** For each of English, हिन्दी, தமிழ், తెలుగు: open `/`, `/compare`, `/data`, `/connect`, `/brokers`, `/criteria`, `/questions`, `/viewings`, `/areas`, `/places`, `/share`, `/houses/new`, a house page and a missing page. On *Your data* open the Drive card in each state you can reach (not connected, connected).
- **Expected:** no English left over in hi/ta/te (no raw `key.name` text); dates and times in each language's own digits/format on the Drive backups rows (TC-U-137); the **"under review"** note is visible for hi, ta and te (settings language chooser and the guide's page banner); `<html lang>` and the page title follow the language.
- **Automated or reviewed by:** TC-M-26 (`i18n` 240 checks, Reviewer ran it: no), `dictionaries.spec.ts` (every key in all four, Reviewer ran it: yes), `tools/check-templates.mjs` (Reviewer ran it: yes, 258 files, 0 problems), TC-U-137 (Reviewer ran it: yes).
- Pass / Fail / Date: ____  by: ____

#### MT-07 The native reading (TC-M-43)
- **Area:** i18n content. **Who:** native readers of Hindi, Tamil and Telugu. **Env:** any.
- **Steps:** Read every string marked *under review* (the app in that language; the default questions and move-in items; the guide's hi/ta/te pages; `privacy.html` and `about.html` in that language; the Drive cards including the recovery-key and deletion wording).
- **Expected:** meaning preserved; the brand words kept (*Import a backup*, *Save a copy*, *readable copies*, *Add a shared listing*; never "Restore"); the words S4b-BL-99 (f) lists chosen once for both apps.
- **Automated or reviewed by:** none can. Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-08 Both themes
- **Area:** Theme. **Who:** anyone. **Env:** E1, E4.
- **Steps:** Settings > Theme light, then dark, then system (and flip the OS theme). Visit the pages of MT-06, the map (markers, legend, the purple path line if any), dialogs, the Drive cards (recovery key shown once, the delete confirm step, the passkey card's help), the toggle switches.
- **Expected:** readable contrast everywhere (switch contrast fix of Wave E), the map legend readable over tiles, no white flash on reload in dark, the theme kept after reload.
- **Automated or reviewed by:** TC-M-26 (`theme` 144, `a11y` 144 with axe). Reviewer ran it: no. Unit a11y: TC-U-WEB-A11Y-1..10, Reviewer ran it: yes.
- Pass / Fail / Date: ____  by: ____

## 5. Offline, PWA, update

#### MT-09 First offline start after one online visit
- **Area:** PWA. **Who:** anyone. **Env:** E1, E4.
- **Steps:** (1) Fresh profile, open the site once, add a house, wait 10 s. (2) Airplane mode / DevTools Offline. (3) Open `https://doorprints.web.app/` and a deep link `/compare` and the house page.
- **Expected:** every route opens from the cached shell; the house is there; the map shows the offline message (`map.offline`) and the list still works; nothing blank.
- **Automated or reviewed by:** TC-M-26 flows (`house opens offline`, `pwa` 4). Reviewer ran it: no. `sw-precache.spec.ts`, Reviewer ran it: yes.
- Pass / Fail / Date: ____  by: ____

#### MT-10 The update banner
- **Area:** PWA. **Who:** owner (needs two deploys). **Env:** E1.
- **Steps:** Keep a tab open across a deploy to `main`; wait or reload once.
- **Expected:** "A new version is ready. Reload to use it. Save any changes first." with **Reload**; after reload *Settings > About* shows the new build; Cache Storage holds one `doorprints-shell-<id>/` cache only (DevTools > Application).
- **Automated or reviewed by:** `pwa.service.spec.ts`, `sw-precache.spec.ts`. Reviewer ran it: yes (unit).
- Pass / Fail / Date: ____  by: ____

#### MT-11 The map's helper failed message (S4b-BL-55)
- **Area:** Map. **Who:** anyone. **Env:** E1 Chrome.
- **Steps:** (1) Online: DevTools > Network > block the request pattern `*maplibre*worker*` (or `*.mjs`), reload the Map page and a house's location map. (2) Then go offline and reload.
- **Expected:** (1) "The map could not start its helper. Reload the page. If it keeps failing, use Save a copy on Your data first, then clear this site's data and open Doorprints again." (`map.workerFailed`), **no** *Try again* offline button. (2) The offline message instead. The list and the house page work throughout.
- **Automated or reviewed by:** TC-U-134 (`map-style.spec.ts`, `map-page.spec.ts`, `location-map.spec.ts`), mutations `tools/mutations/map-worker-failed.json`, `maplibre-worker-check.json`. Reviewer ran it: yes (all killed).
- Pass / Fail / Date: ____  by: ____

#### MT-12 Offline maps on the website with the boundary re-check (TC-M-36)
- **Area:** Map/offline. **Who:** owner. **Env:** E1 and E4 browser.
- **Steps:** (1) Zoom to a neighbourhood, *Save this area for offline*: size and free storage shown; on a metered connection the mobile-data warning; a whole-state box refused with the tile count. (2) *Your data* lists the area with its size. (3) Offline: the area draws to street level with the houses. (4) TC-M-25 steps (1)-(2) on the offline tiles over Jammu and Kashmir, Ladakh, Aksai Chin and Arunachal Pradesh. (5) Delete the area; *Remove all data* removes the rest (Cache Storage and IndexedDB empty).
- **Expected:** as said; India's outline identical online and offline.
- **Automated or reviewed by:** TC-U-115. Reviewer ran it: yes (unit). Boundary bytes: web and Android `in-boundaries.geojson` and `in-held-areas.geojson` byte-identical (sha256 `c3cdf5fb…`, `8fa2db12…`), the reviewer checked: yes.
- Pass / Fail / Date: ____  by: ____

## 6. The map and India's boundaries

#### MT-13 TC-M-25: what the map shows, against the Survey of India (pending its data) and Google Maps from India
- **Area:** Map/ADR-22. **Who:** owner or QA. **Env:** E1 (live site, hard reload) **and** E4 (Android app).
- **Reference:** the Survey of India's Administrative Boundary Database OVSF/1M/7 is the standard (download pending, N16); until it is at hand, Google Maps with the India region (`google.co.in`) is the second check. Record every finding with coordinates and zoom in [10](../10-sprint-log.md).
- **Steps:** (1) Country view, zoom 3-4.9: one solid outline around all of Jammu and Kashmir and Ladakh (PoK, Gilgit-Baltistan, Shaksgam, Aksai Chin) and Arunachal Pradesh; no dashed line near India; no line through Kashmir. (2) Zoom 5, 6, 7, 8 (exactly 5.0 too) over Kashmir/Ladakh, then Arunachal Pradesh, Sikkim, Uttarakhand: no LoC, no LAC, no "Azad Kashmir" or "Gilgit-Baltistan" label; "Jammu and Kashmir", "Ladakh", "Arunachal Pradesh" shown; one line only along the 7 shared stretches (Kalapani; Sikkim and the Darjeeling-Kalimpong hills with Nepal and Bhutan; Bhutan's south-east corner; Myanmar south of 26.65 N; the Wakhan) and the whole India-China border (Shipki La, Mana Pass at zoom 10-12). (3) Hand-overs: a step of at most about 7 km; Doklam's 2 km loop kept; a narrow V at Longwa; **fail** on a second line beside the border, a stray tile piece, a gap, a line through Indian territory, a dashed country line, a spur, a loop at Nepal-China-India, an overrun at Jomotsangkha or Longwa. (4) State lines: the Assam-Arunachal Pradesh line dashed from zoom 5. (5) Admin lines, zoom 6-8 and 9-14 over Gilgit-Baltistan, PoK, Aksai Chin, Shaksgam: no Pakistani or Chinese unit line; Indian lines still shown near the LoC and LAC (Uri, Poonch, Kargil, Turtuk); recorded minors: a few dashed lines next to the LoC at zoom 9-11 only. (6) Labels drawn on the device (the emulator draws none, S4b-BL-48). (7) Compare each view with the reference.
- **Expected:** as said, identical on web and Android.
- **Automated or reviewed by:** TC-U-55/-73 (`IndiaViewRulesTest`, `IndiaViewOpsTest`), `india-boundaries.spec.ts`, TC-I-35 (emulator shots, lines only), TC-I-38 (iPhone gate), TC-M-26 map shots (six spots, looked at by a person). Reviewer ran it: Kotlin tests pending in the Gradle run; web spec yes; device: no.
- Pass / Fail / Date: ____  by: ____

#### MT-14 The iPhone map (TC-M-28)
- **Area:** iOS map. **Who:** owner (iPhone or a Mac's simulator). **Env:** E5 app.
- **Steps:** MT-13 (1)-(2) on the iPhone app; (3) the same offline after a first online start; (4) markers by status, names below, tap opens, long press adds, *Save house here* and *My location* with location allowed, refused and approximate; (5) VoiceOver on the map; (6) the attribution button above the bottom controls and legend.
- **Expected:** as TC-M-25; record device and iOS version in [10](../10-sprint-log.md) §13.13.
- **Automated or reviewed by:** TC-I-38 (`indiaView PASS`, `map PASS` on the simulator). Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

## 7. Houses, search, copies and imports

#### MT-15 Search over every house field
- **Area:** Search (owner rule: search grows with the house values). **Who:** anyone. **Env:** E1 and E4 app.
- **Setup:** one house with: label "Lake View", address, street, locality, notes "south-facing", contact name "Ravi", a broker (name, agency, fee terms), a room named "Study" with notes, a question answered "yes, 2 lakh deposit", an area note reaching it, move-in items, floor −1 (basement) and floor 12; language Hindi for one run.
- **Steps:** On the website's list and on Android's Houses tab type, one at a time: the label, part of the address, the street, the locality, "south", "Ravi", the broker's agency, "Study", the room's note, "deposit", the area note's text, a move-in item, "basement", "12th"/"twelfth", "ground"; then the floor word in Hindi.
- **Expected:** the house matches each; a word not in it does not; counts update; both apps agree.
- **Automated or reviewed by:** TC-U-93 (`HouseSearch` shared cases, Kotlin and `map-list.spec.ts`), TC-U-100/-101/-102/-103/-107/-109 per slice. Reviewer ran it: web yes; Kotlin pending.
- Pass / Fail / Date: ____  by: ____

#### MT-16 Export/import round trip across the three stacks
- **Area:** Data compatibility. **Who:** owner. **Env:** E1, E4 app, E8 optional.
- **Steps:** (1) Website: 5 houses with photos, visits, a broker, rooms, criteria, a viewing, an area and a place; *Save a copy > Full backup (ZIP)*. (2) Android: *Settings > Import a backup*, the ZIP: preview, *Merge*; then *Save a copy > Full backup* on Android. (3) Website: *Import a backup* the Android ZIP with *Keep mine*, then again with *Add everything as new copies*, then undo. (4) Readable copies (HTML, PDF, CSV, Excel, Markdown) open outside Doorprints; the tables list rooms, scores, answers, visits, viewings, photos, brokers. (5) Server (if E8): `POST /api/import` the ZIP's `data.json` through *Connect*; pull on a second device. (6) A tampered ZIP (a changed byte in `data.json`, a zip-slip name, an entry over 16 MiB) is refused with a reason and nothing written.
- **Expected:** every value survives each hop (cost, carpet area, approximate location flag, broker, rooms, answers, criteria, viewing, areas, places, photo tags, move-in); format `doorprints-backup/2` written only when a broker or room exists; an older reader refuses `/2` rather than importing part of it.
- **Automated or reviewed by:** TC-U-98/-99/-100, `backup-vectors.json`, `import-vectors.json`, `backup-sample.json` read by all three stacks (the reviewer checked the vector files are referenced by Kotlin and web/backend tests: yes), TC-S-17 (unit half), TC-I-42 (server). Reviewer ran it: web and tools yes; Kotlin pending; backend no.
- Pass / Fail / Date: ____  by: ____

#### MT-17 Open a backup from the system (TC-M-45)
- **Area:** PWA file handling. **Who:** owner. **Env:** E1 Chrome/Edge installed.
- **Steps:** (1) Install the site. (2) Double-click `Doorprints-backup-<date>.zip` (or *Open with > Doorprints*); allow once. (3) The same with the app already open. (4) A `.zip` that is not a backup. (5) Network off. (6) Firefox or Safari, and a plain Chrome tab.
- **Expected:** (2)-(3) the app opens on Your data, *Import a backup* names the file and shows *What this would change*, nothing written until *Import*; (4) refused with the reason; (5) works; (6) nothing changes.
- **Automated or reviewed by:** TC-U-114 (S4b-BL-108 handler). Reviewer ran it: yes (unit).
- Pass / Fail / Date: ____  by: ____

#### MT-18 Slice 5 and Wave B on a device (TC-M-44)
- **Area:** House values. **Who:** owner. **Env:** E4 app and E1.
- **Steps:** (1) Mark a house Taken: the other returns to Shortlisted; *Mark the other houses Not chosen?*. (2) *Start moving in*, tick and add items, a move-in date; *Add a photo* from the condition record (camera, Move-in tag chosen). (3) *Close this hunt* and the copy. (4) Basement floor on the number keyboard: with **Basement** on, typing 2 saves basement 2 (phone and website in a phone's browser). (5) The cost filters. (6) A listing shared from a portal, *Find "<place>"* on Android and on the website.
- **Expected:** as said.
- **Automated or reviewed by:** TC-U-109, TC-U-113. Reviewer ran it: web yes; Kotlin pending.
- Pass / Fail / Date: ____  by: ____

## 8. Google sign-in and Drive (website)

All with a **test Google account**. Record the Google Cloud project's consent-screen state (Testing, test users) first.

#### MT-19 Real Google sign-in and the `drive.file` consent (TC-M-46 part 1)
- **Area:** Drive connect. **Who:** owner. **Env:** E1, then E3 Firefox, then E5 Safari.
- **Setup:** `web/public/config.js` on the live site has the Web client id (`view-source:https://doorprints.web.app/config.js`, `googleClientId` set).
- **Steps:** (1) *Your data > Back up to Google Drive > Connect to Google Drive*. (2) Google's popup: read the consent screen. (3) Close the popup without choosing (once); then deny (once); then allow but **untick** the Drive permission (granular consent); then allow fully.
- **Expected:** (2) exactly one permission, "See, edit, create and delete only the specific Google Drive files you use with this app" (`drive.file`); nothing else (no `drive`, `drive.appdata`, `openid`, email). (3) Closed: a sentence, *Connect* stays; denied: a sentence; unticked: refused as denied (fail closed); allowed: "Connection in progress." then the recovery key step. The popup works with COOP `same-origin-allow-popups` (no "window.closed" error in the console).
- **Automated or reviewed by:** `google-token-provider.spec.ts` (popup closed/blocked/denied, `hasGrantedAllScopes` fail-closed), TC-U-133. Reviewer ran it: unit yes; real Google: no. the reviewer reviewed `google-token-provider.ts` (token in memory only, scope checker fails closed): yes.
- Pass / Fail / Date: ____  by: ____

#### MT-20 The recovery key shown once (TC-M-53 part 1)
- **Area:** Drive crypto. **Who:** owner. **Env:** E1.
- **Steps:** After the first connect: (1) the key is shown in groups of four (27 symbols, the last a check symbol); **Copy recovery key** copies it; print if offered. (2) Try to continue without ticking *I have saved my recovery key in a safe place*. (3) Tick and continue. (4) Reload: the key is not shown again anywhere; DevTools > Application: no recovery key, no folder key and no token in `localStorage`, `sessionStorage` or IndexedDB (`doorprints-drive` holds a non-extractable device key, a watermark, state). (5) *Skip* path (on a fresh account later): the warning and the tick box, then Settings shows "No recovery key" with *Make a recovery key*.
- **Expected:** as said. The join form's help says "27 letters and digits in groups of 4" (the key's 26 symbols plus its check symbol); a typed key with spaces, lower case or o/l for 0/1 is accepted.
- **Automated or reviewed by:** TC-U-127 (`RecoveryKeyTest`, `keys-file.spec.ts`: every substitution and swap refused), `drive-connect.spec.ts`, `keys-reload.spec.ts`, review probe (fullwidth digits, combining marks, ZWJ refused: Reviewer ran it: yes, 12/12). Real browser: no.
- Pass / Fail / Date: ____  by: ____

#### MT-21 Back up now, Import a backup from Drive, Save a copy (TC-M-46 part 2)
- **Area:** Drive backups. **Who:** owner. **Env:** E1, then E7.
- **Steps:** (1) Add three houses with one photo; **Back up now**. (2) drive.google.com: the *Doorprints* folder with `Backups/`, `keys.json`, `doorprints.json`; open the backup file's preview. (3) Download the backup file and the photo file; open in a hex viewer. (4) The backups list: date, time, houses, size in each of the four languages. (5) On the row, **Import a backup**: the file is handed to the *Import a backup* card; the preview; *Merge*; nothing written before you confirm. (6) On E7, connect and join (MT-28), then *Import a backup* of the same row. (7) *Save a copy > Full backup* still works and is **not** the Drive file.
- **Expected:** (2)-(3) each file starts with `DPX1`, no house text, no name, no JPEG header, Drive's preview shows nothing; file names and `appProperties` carry no house data; the backup has `state=complete`. (4) dates in the language's own format (TC-U-137). (5)-(6) the preview counts match; after import the houses are there with photos.
- **Automated or reviewed by:** TC-U-128 (`dpx-vectors.json`, every header byte flipped refused), `drive-backup.service.spec.ts`, `backup-e2e.spec.ts`, TC-U-137. Reviewer ran it: unit yes; real Drive no.
- Pass / Fail / Date: ____  by: ____

#### MT-22 Automatic backup and the shrink guard
- **Area:** Drive backups. **Who:** owner. **Env:** E1.
- **Steps:** (1) *Automatic backup* on (default after connect); the help "On the website, backups happen only while Doorprints is open." (2) Change the system clock a day forward (or wait a day), keep the tab open: a backup appears. (3) Delete most houses locally, *Back up now*: the shrink question "This backup is much smaller than your earlier ones…" with *Keep older backups* / *Confirm, the smaller backup is right*. (4) Confirm: older ones pruned to 7/4/6; keep: nothing pruned.
- **Expected:** as said; never fewer than the newest good backup remains.
- **Automated or reviewed by:** `drive-backups.spec.ts`, `drive-backup-results.ts` tests, `backup-vectors`. Reviewer ran it: unit yes.
- Pass / Fail / Date: ____  by: ____

#### MT-23 Sync between two browsers (TC-M-47, website part)
- **Area:** Drive sync. **Who:** owner. **Env:** E1 + E7.
- **Steps:** (1) Both connected and enrolled. (2) Edit a house on A; within about two minutes while the Your data page is open, B shows it after *Sync now* or its own timer. (3) B offline for 10 minutes with edits; back online. (4) Delete a house on A; B converges. (5) Two tabs on A: only one syncs (the `doorprints-drive-sync` lock). (6) Put a plain (unencrypted) file and a tampered file into `Sync/` by hand.
- **Expected:** no house lost or brought back; the deleted one stays deleted; "Some files from other devices were skipped (n)" for the bad files; statuses *Syncing… / Up to date at … / Offline…*.
- **Automated or reviewed by:** TC-U-130 (`drive-sync-scenarios.json` on both stacks), `sync-adapter.spec.ts`, `sync-e2e.spec.ts`, `drive-sync.spec.ts`. Reviewer ran it: unit yes; two real browsers: no.
- Pass / Fail / Date: ____  by: ____

#### MT-24 Photos on Wi-Fi only (website part of TC-M-54)
- **Area:** Drive photos. **Who:** owner. **Env:** E4 browser on mobile data, then Wi-Fi.
- **Steps:** (1) *Upload photos only on Wi-Fi* on (default); add a photo on 4G; status "Waiting for Wi-Fi to upload photos"; the text still syncs. (2) *Upload photos now over mobile data (size)* once. (3) Switch off; photos go on 4G. (4) Browser Data Saver on: held.
- **Expected:** as said.
- **Automated or reviewed by:** `photo-policy-vectors.json` on both stacks, `drive-photos.spec.ts`. Reviewer ran it: unit yes.
- Pass / Fail / Date: ____  by: ____

#### MT-25 Passkey with Windows Hello (TC-M-56 a)
- **Area:** Passkey/PRF. **Who:** owner. **Env:** E1 Chrome and Edge.
- **Steps:** (1) Connected card > *Passkey for this device* > **Set up a passkey**. (2) Windows Hello asks for the PIN (or fingerprint); give it. (3) Cancel once on a second try. (4) Reload; the card says "A passkey is set up on this device."
- **Expected:** (2) either "A passkey is set up on this device." or, when Windows returns no PRF output, "This passkey could not protect deletions: it did not return the secret value (PRF output) Doorprints needs." plus *Technical details (no secrets)* with **Copy details** (paste the details into the ticket: it is `create: … ; assertion: …` with step names and flags only). (3) "The prompt was cancelled." and *Set up a passkey* stays. Nothing stored until the sealed blob exists (`doorprints-deletion-sealed-blob` in IndexedDB only after success).
- **Automated or reviewed by:** TC-U-133/-136 (`web-authn-prf-authenticator.spec.ts`: rp.id, ES256 then RS256, discoverable, UV, `prf.eval`, a PIN with no PRF not stored), `drive-passkey.spec.ts`, mutations `passkey-*.json`. Reviewer ran it: unit and mutations yes; Windows Hello: no. **Known from 2026-10-03:** the owner's machine returned no PRF (N18); the cause is open.
- Pass / Fail / Date: ____  by: ____

#### MT-26 Passkey with the phone by QR, and with a security key (TC-M-56 c, d)
- **Area:** Passkey/PRF. **Who:** owner. **Env:** E3 Linux Chrome (no platform authenticator) and E1.
- **Steps:** (1) **Set up a passkey**: the browser's chooser offers *Use a phone or tablet* (QR) and *USB security key*; it must **not** be limited to "this device". (2) Scan the QR with the Android phone (Google Password Manager) or the iPhone; unlock on the phone. (3) Repeat with a FIDO2 security key with PIN (if at hand). (4) After each, *Delete all backups* (MT-30) asks the same passkey again.
- **Expected:** a passkey that returns a PRF output is accepted; Android/iOS phone passkeys and most FIDO2 keys do (`hmac-secret`); an authenticator that does not gives the no-PRF sentence and the details line.
- **Automated or reviewed by:** TC-U-136 (no `authenticatorAttachment` set), mutation `passkey-widened.json`. Reviewer ran it: unit yes; real hardware no.
- Pass / Fail / Date: ____  by: ____

#### MT-27 A computer with no lock: the card points to setup and never offers the recovery key (TC-M-56 b)
- **Area:** Passkey policy. **Who:** owner. **Env:** E2.
- **Steps:** (1) Connect and enrol. (2) Open the passkey card. (3) Press *Set up a passkey* and let the browser offer the phone/QR path; cancel. (4) Open *Delete all backups* up to the confirm step.
- **Expected:** (2) "This computer reports no built-in lock for passkeys. Turn one on: on Windows, Settings, Accounts, Sign-in options, PIN…" (`drivePasskey.noBuiltInHelp`); *Set up a passkey* stays enabled (the phone can still be the passkey). (4) **No recovery-key field**: "This browser cannot finish this deletion. Set up a passkey on this device, or delete from a phone…" (`driveDelete.usePhone`). *Delete older backups* (L1) still works.
- **Automated or reviewed by:** TC-U-135 (e), TC-U-136, `recoveryKeyOffered` rule (`prfCapability === false && builtIn === true` or a no-PRF registration in this page), mutations `recovery-service.json`, `passkey-no-builtin.json`, `policy-use-phone.json`. Reviewer ran it: yes (unit + mutations). Real E2: no.
- Pass / Fail / Date: ____  by: ____

#### MT-28 The recovery key as last resort for one deletion (TC-M-56 e)
- **Area:** Recovery factor. **Who:** owner. **Env:** the machine that returned no PRF in MT-25.
- **Steps:** (1) In the **same page session** as the failed *Set up a passkey*, open *Delete all backups*, tick the box, *Proceed*. (2) The **Recovery key** field (`type=password`) with the help "Last resort…". (3) Type a wrong key (one letter changed): refused, nothing deleted. (4) Type a malformed text: "That does not look like a recovery key…". (5) Type the right key: the deletion runs; afterwards the field is empty. (6) Start a second deletion: the key is asked again (not remembered). (7) **Reload the page**, then open *Delete all backups* again **without** pressing *Set up a passkey*.
- **Expected:** (3) "That recovery key does not open this Google Drive…", no file touched (check Drive). (5) deleted; `keys.json` kept. (7) **After a reload:** the "passkey returned no PRF" state and its details are remembered in this browser, so after a reload the recovery key is still offered (and the details text is still there to copy) until a passkey setup succeeds (`drive-connect.service.spec.ts`, *passkey state survives a reload*). Note what you see.
- **Automated or reviewed by:** TC-U-135 (a)-(f), `recovery-deletion-e2e.spec.ts` (wrong key, forged grant, grant after `forgetProof` delete nothing), mutations `recovery-factor.json`, `web-authorizer-recovery.json`, `verify-recovery-key.json`, `recovery-deletion-e2e.json`, `recovery-card.json`, `delete-factor.json`. Reviewer ran it: yes (all mutations killed). Real machine: no.
- Pass / Fail / Date: ____  by: ____

#### MT-29 Deletion L1: older backups, and one backup (TC-M-48 part)
- **Area:** Drive deletion. **Who:** owner. **Env:** E1.
- **Steps:** (1) With 3 complete backups: *Delete this backup* on a middle row: the plan "What will be deleted" (counts and bytes only), *Proceed*, confirm. (2) *Delete older backups*: the plan lists all but the newest complete one. (3) Delete the **last** complete backup by its row.
- **Expected:** (1)-(2) no passkey asked (L1), the files gone from Drive and **not in the bin**; (3) needs L2 (a passkey or MT-28): "the last backup" rule. "Deleted." afterwards; the list refreshes.
- **Automated or reviewed by:** `delete-vectors.json` on both stacks (`selectBackups`, `operationId`), review probe (last complete backup is L2: Reviewer ran it: yes), `drive-delete.spec.ts`, `drive-deletion.spec.ts`. Real Drive: no.
- Pass / Fail / Date: ____  by: ____

#### MT-30 Deletion L2: Delete all backups (TC-M-48, TC-M-50 website)
- **Area:** Drive deletion. **Who:** owner. **Env:** E1 with a working passkey (MT-25/26).
- **Steps:** (1) *Delete all backups*: plan, *Proceed*, the tick box "I understand this cannot be undone"; the button disabled until ticked; **no countdown** on the website. (2) Confirm: the passkey prompt; cancel it once. (3) Confirm again, pass the passkey. (4) Wait 70 s between the passkey and the start (DevTools breakpoint or a slow network) once.
- **Expected:** (2) "Nothing was deleted."; (3) backups gone, not in the bin, `keys.json` and `doorprints.json` stay, automatic backup turned off, the other device (E7) asks before backing up again; (4) asked again (60 s grant).
- **Automated or reviewed by:** `delete-policy-vectors.json` (both stacks), `production-deletion-e2e.spec.ts` (PRF-backed L2; grant for plan A refused for plan B; spent after one use; gone after reload), `deletion-gate.spec.ts` (forged 64-hex proof fails). Reviewer ran it: unit yes.
- Pass / Fail / Date: ____  by: ____

#### MT-31 Deletion L3: Delete everything, and a partial run with *Try again*
- **Area:** Drive deletion. **Who:** owner. **Env:** E1 + a second device E7 connected.
- **Steps:** (1) *Delete everything Doorprints keeps in Google Drive*: the plan names backups, sync files, photos, read-me, `doorprints.json`, `keys.json`, the folders; *Save a copy first* works from the dialog; tick; passkey. (2) Mid-run, turn Wi-Fi off (or DevTools offline). (3) The card: "{left} of {total} files are still in Google Drive." with **Try again**. (4) Reload the page, come back: the partial run is still shown. (5) *Try again* online: it asks for the passkey (or the recovery key) again and finishes. (6) Offline from the start: refused with nothing deleted. (7) E7 afterwards.
- **Expected:** (5) the folder is gone from Drive, not in the bin; (7) E7 shows "The folder was deleted. Connect again to create a new one." and its local houses are intact; the old recovery key opens nothing (new folder, new key).
- **Automated or reviewed by:** `drive-delete.spec.ts` (partial, Try again), `drive-deletion.spec.ts` (phase order, `stopsRun`), `deletion-pending` store in IndexedDB. Reviewer ran it: unit yes.
- Pass / Fail / Date: ____  by: ____

#### MT-32 A new browser joins: recovery key, 8-digit code, QR (TC-M-52, TC-M-53)
- **Area:** Enrolment. **Who:** owner. **Env:** E1 (enrolled) + E7 (fresh).
- **Steps:** (1) E7: *Connect to Google Drive*: "This Google Drive already has Doorprints backups from another device…" and **nothing is written** (check Drive: `keys.json` unchanged). (2) E7: *Join this folder* with a wrong recovery key, then the right one. (3) Disconnect E7; connect again; this time **8-digit code**: copy the pairing request, paste on E1, *Make a reply*, *Show the 8-digit code* on both; the numbers match; E1 approves (passkey asked on E1); paste the revealed message back; E7 joins. (4) Repeat with a request left for 11 minutes. (5) Repeat with **Show a QR code** on E7; on E1 *Scan or paste a QR code* (camera if the browser can, else paste). (6) E1's Devices card lists E7 with its name and platform.
- **Expected:** (2) wrong: "This recovery key does not open this folder."; right: joined, the old backups open. (3) both screens show the same 8 digits; a mismatched pair fails. (4) "This pairing request is older than ten minutes. Start again." (5) joined without typing the recovery key.
- **Automated or reviewed by:** TC-U-132 (`forge.ts`, `poison.ts`: re-wrapped lists FORK/PIN_MISMATCH), `pairing-code.spec.ts`, `pairing-flow.spec.ts`, `qr-enrol.spec.ts`, `drive-enrol.spec.ts`. Reviewer ran it: unit yes; two real browsers: no.
- Pass / Fail / Date: ____  by: ____

#### MT-33 Revoke a device, new recovery key, rollback refused (TC-M-52/-53, I16)
- **Area:** Key epochs. **Who:** owner. **Env:** E1 + E7.
- **Steps:** (1) E1 Devices card: *Revoke this device* on E7 (passkey): a **new recovery key** is shown once; save it. (2) E7: next sync or reload: "revoked" sentence; it can read nothing new. (3) Make a backup on E1; on a fresh browser E7' join with the **new** recovery key: the new backup opens; the **old** recovery key is refused. (4) In drive.google.com, *Manage versions* on `keys.json`: restore the previous version. (5) Reload E1.
- **Expected:** (5) E1 refuses the rolled-back list: the sentence says the same list is read again, *Try again* reads it again, nothing is rolled forward, no local keys are dropped; restoring the newest version again recovers.
- **Automated or reviewed by:** TC-U-131 (`KeysGuardTest`: watermark only moves up, ROLLED_BACK), TC-U-132, `drive-devices.spec.ts`, `drive-connect-errors.spec.ts`. Reviewer ran it: unit yes.
- Pass / Fail / Date: ____  by: ____

#### MT-34 Disconnect this device, Disconnect on all devices (I8)
- **Area:** Drive lifecycle. **Who:** owner. **Env:** E1 + E7.
- **Steps:** (1) E7: *Disconnect this device*: houses stay; the Drive card returns to *Connect*. (2) E1: *Disconnect on all devices* (passkey): Google's token revoked (`oauth2.revoke`), devices revoked. (3) Google Account > Security > Third-party apps: Doorprints listed only while connected; remove it there and reload the site.
- **Expected:** no local data deleted anywhere; the card says disconnected; after (3) the next Drive call asks Google again.
- **Automated or reviewed by:** `google-token-provider.spec.ts` (revoke), `drive-devices.spec.ts`. Reviewer ran it: unit yes.
- Pass / Fail / Date: ____  by: ____

#### MT-35 Firefox and Safari encryption paths
- **Area:** WebCrypto compatibility. **Who:** owner. **Env:** E3 Firefox 132+, E5 Safari.
- **Steps:** Connect, recovery key, *Back up now*, join from another browser with the recovery key, *Delete older backups*; in Safari also the passkey (Touch ID or iPhone).
- **Expected:** no "this browser cannot encrypt" (`CRYPTO_UNAVAILABLE`) on Firefox (the x(d·G) import path); Safari: the same cards work; where a feature is missing the card says so without *Try again* in that browser.
- **Automated or reviewed by:** `crypto-provider` specs (`p256FromStoredKey` without JWK export), `drive-connect-errors.spec.ts`. Reviewer ran it: unit yes (jsdom only).
- Pass / Fail / Date: ____  by: ____

## 9. Security checks on the website

#### MT-36 Headers and CSP on the live site (TC-M-19, TC-S-23)
- **Area:** Security headers. **Who:** anyone. **Env:** a terminal.
- **Steps:** `curl -sI https://doorprints.web.app/` and `/compare` and `/config.js`.
- **Expected:** HTTP 200; `Content-Security-Policy` with `default-src 'self'`, `script-src 'self' https://accounts.google.com/gsi/client`, `frame-src 'self' blob: https://accounts.google.com/gsi/`, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`; `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: geolocation=(self), camera=(self), microphone=()…`, `Cross-Origin-Opener-Policy: same-origin-allow-popups`, `Cache-Control: no-cache`. The in-page `<meta>` CSP equals the header minus `frame-ancestors`. Known accepted: `connect-src https:` wildcard (Z1/RR-30). Note: the camera is allowed for this origin (`camera=(self)`, fixed after the review) so the QR camera scan works; MT-32 step 5 proves it on a real camera.
- **Automated or reviewed by:** `web.yml` `deploy-firebase` header check (TC-S-23), `firebase.json` read by the reviewer: yes. `curl` by the reviewer: no (no live access from the session).
- Pass / Fail / Date: ____  by: ____

#### MT-37 No secrets in the console, storage or network
- **Area:** Privacy. **Who:** owner. **Env:** E1 DevTools open through MT-19..MT-31.
- **Steps:** (1) Console: no error on any page; no token, key, recovery key or PRF value printed (search the console for `ya29.`, the recovery key's first group, `prf`). (2) Application > Storage: `localStorage`/`sessionStorage` hold only settings (language, theme, the pin for the self-hosted server if remembered); IndexedDB `doorprints-drive` holds `device-key` (a CryptoKey, non-extractable), `keys-watermark`, `state`, `deletion-pending`, `photo-state`; the sealed blob is ciphertext. (3) Network: requests go only to `doorprints.web.app`, `accounts.google.com`, `www.googleapis.com`, `tiles.openfreemap.org`, Nominatim only when you press *Find*; no request carries house text in a URL; the access token only in `Authorization` headers to googleapis. (4) Grep the shipped bundle: `curl -s https://doorprints.web.app/main-*.js | grep -c client_secret` is 0.
- **Expected:** as said.
- **Automated or reviewed by:** TC-M-26 (`console` 365 checks), `gitleaks` and `trivy` in `security.yml`, the reviewer grep of the repository for personal e-mail addresses (only `noreply@`, the Survey of India's and Cursor's agent address): yes; `console.*` calls in Drive/crypto code: 0 (the reviewer grep): yes.
- Pass / Fail / Date: ____  by: ____

#### MT-38 Untrusted files in Drive (I9, I11)
- **Area:** Fail closed. **Who:** owner. **Env:** E1 + drive.google.com.
- **Steps:** Put into `Doorprints/Backups` and `Doorprints/Sync`: a zip-slip ZIP, a 1 GB-of-zeros ZIP, a file whose SHA-256 does not match its row, a plain JSON sync file, a `keys.json` with one extra byte, a `keys.json` re-wrapped to a key of your own (keep the originals). Reload, *Sync now*, open the backups list.
- **Expected:** each refused with a translated sentence (never `String(err)`), nothing written locally, no revoke, no re-key, no folder re-created, the pin unchanged; "Some files from other devices were skipped".
- **Automated or reviewed by:** TC-U-128/-130/-132, `drive-connect-errors.spec.ts` (`KEYS_UNREADABLE`, `MAC_INVALID` words), TC-S-17 unit half. Reviewer ran it: unit yes.
- Pass / Fail / Date: ____  by: ____

## 10. Android app and iPhone

#### MT-39 Android smoke on a device
- **Area:** Android. **Who:** owner. **Env:** E4 app (debug APK from `main`).
- **Steps:** (1) Install; first launch asks nothing until needed. (2) Map, Houses, Compare, Settings each open; the Assistant tab only with AI on. (3) *Save house here*; long-press adds; a house's page; edit; search (MT-15). (4) Settings > *Save a copy* (every format, *Save to…* and *Share*); *Import a backup* (MT-16). (5) Settings > Language each of four and the "Language changed to…" snackbar; theme. (6) Notification tap opens the right screen from a cold start.
- **Expected:** as said; no crash; map labels drawn (the emulator cannot).
- **Automated or reviewed by:** TC-I-35 (`SmokeTest` on API 26/34/36 emulators), TC-U-56 (Roborazzi screenshots), `:app` 540 / `:ui` 165 / `:shared` 609 host tests. Reviewer ran it: Gradle host suite started in the session (result in the report); emulator and device: no.
- Pass / Fail / Date: ____  by: ____

#### MT-40 TC-M-27: languages, rotation and upgrade on real phones
- **Steps:** (1) System languages [Marathi, Hindi]: everything in Hindi. (2) In-app switch on API 29 and 34: en/hi/ta/te and a notification in each. (3) API 32 or lower, rotation keeps the app language. (4) Upgrade from a pre-PR-#19 build with a pinned icon, houses and a queued sync.
- **Expected:** as TC-M-27. **Automated or reviewed by:** TC-U-52/-54 (locale rules). Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-41 TC-M-29: the app lock
- **Steps:** (1) No screen lock: *Lock Doorprints* off, says to set one; set one and come back. (2) Leave for less than, then more than, the chosen time. (3) Recent-apps view shows no house. (4) Rotation and language switch do not lock. (5) A half-typed house survives unlocking. (6) Back on the lock screen leaves the app. (7) Turning the lock off asks the credential.
- **Automated or reviewed by:** TC-U-90, TC-I-43 (`AppLockEmulatorTest`, S4b-BL-113 open for API 26-28). Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-42 TC-M-30 and TC-M-31: Hunt mode, the path trace, iPhone
- **Steps:** Android: *Trace my path on the map* off by default; on, hunt 10 minutes: a purple line under the dots in both themes; no leap after a 30-minute pause; *Save a copy*, a backup and the server hold no track; *Clear the path*. iPhone: Hunt mode from the Map (prompts in context), lock and walk past a saved house: the alert; "Are you at a house?" with the address from Apple's geocoder; location off in Settings stops it; low battery stops it.
- **Automated or reviewed by:** TC-U-92/-94 (`HuntEngine`), TC-I-41 (simulator). Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-43 TC-M-32: a saved area on both phones
- **Steps:** Wi-Fi: *Save this area for offline* (locality and MB named), progress in Settings > Offline maps; a whole state refused with the tile numbers; mobile data note; airplane mode and restart: the area draws to street level, India's boundary as in MT-13; delete it.
- **Automated or reviewed by:** TC-U-… offline estimate and cap in common code. Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-44 TC-M-33 and TC-M-34: updates between two phones, a shared listing
- **Steps:** TC-M-33 (1)-(7): *Share updates with…*, WhatsApp, B imports, edits travel both ways, newer wins, idempotent, a delete stays on B, *Include contact details* off. TC-M-34 (1)-(5): a MagicBricks/99acres/Housing/NoBroker share into Doorprints; duplicate link; a WhatsApp message with price and phone; airplane mode; the website's installed app.
- **Automated or reviewed by:** TC-U-96, TC-U-97 (`listing-fixtures.json` on both stacks), TC-U-114. Reviewer ran it: web yes; Kotlin pending; device no.
- Pass / Fail / Date: ____  by: ____

#### MT-45 TC-M-35 and TC-M-37: the area wake-up and reminders
- **Steps:** Android: save an area you can reach; *Wake me in my hunting areas*: the explanation, precise and "Allow all the time"; leave and come back: "You're in <area>. Start Hunt mode?" within minutes; *Dismiss* then a second entry within 6 hours gives nothing; withdraw "all the time": the switch turns off and says why; restart the phone. iPhone: "While using" then "Always", the notification opens the Map offering Hunt mode. Reminders: a viewing reminder and a Hunt reminder arrive on time on both; Android's *Questions* and *Directions*; with the app lock on, a Hunt alert shows nothing on the lock screen.
- **Automated or reviewed by:** TC-U-108, TC-U-110, TC-U-105/-106. Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-46 TC-M-38: iPhone copies, imports and the calendar file
- **Steps:** *Save a copy* each format through the share sheet and Files (no PDF); *Import a backup* from Files/iCloud/Mail/WhatsApp with the preview and three modes; an Android backup and the website's; an update file with a deletion; *Add to calendar*.
- **Automated or reviewed by:** TC-U-111 (compile and simulator only). Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

## 11. Self-hosted server (only if E8)

#### MT-47 TC-M-39: the *Set up your own server* page, end to end
- **Steps:** Follow the page: Docker Desktop, `.env` with `APP_API_KEY` (32+ chars) and `APP_CORS_ORIGINS=https://doorprints.web.app`, `docker compose up --build -d` (note the time), `GET /actuator/health` is `{"status":"UP"}`, `tools/server-smoke.sh http://localhost:8080` all PASS, Tailscale Serve for HTTPS, the owner page at `/owner` with the setup link from the log, *Connect* on the website and Android **by code** and by QR, a sync both ways, per-device AI switch, a Gemini key saved on the owner page, Ask and Plan.
- **Expected:** every step as the page says; `GET /api/houses` without a key is 401, a wrong key 401 then 429 after 10 tries; the owner page refuses cross-site calls (403).
- **Automated or reviewed by:** `mvn -B -ntp verify` (505 tests with PostGIS; TC-I-36/-39/-40/-42), ZAP API scan in `backend.yml`. Reviewer ran it: **no** (needs JDK 25 and a PostGIS container; the session had JDK 21 and 1 GB of disk). the reviewer read `ApiKeyFilter`, `OwnerFilter`, `RecordController`, `WebConfig` CORS, `SecurityHeadersFilter`, `application.yml`: yes.
- Pass / Fail / Date: ____  by: ____

#### MT-48 Server upgrade with data (docs/08 §11)
- **Steps:** On a server that synced before 2026-09-24: dump and restore `househunt` into `doorprints` before starting the new image; then start; the clients' next sync; remove the old volume once counts match.
- **Expected:** no device misses the others' changes; a reset database is detected by the clients (TC-U-74) and everything is re-sent.
- **Automated or reviewed by:** TC-U-74, TC-I-36. Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

## 12. Accessibility, SEO and release gate

#### MT-49 TC-M-41 and TC-M-42: screen readers, text size, keyboard, display settings
- **Steps:** TalkBack (Android), VoiceOver (iPhone app and Safari), NVDA with Firefox or Chrome: add a house, edit (the floor error), a viewing and its reminder, Moving in, Compare, *Your data* (save a copy, import, offline maps, the Drive cards: the recovery key is read, the status "Connection in progress." is announced, the delete confirm step's tick box and the recovery field's label), Settings. Text at 200 % on Android, largest Dynamic Type on iPhone, the website at 200 % zoom and 360 px; 48 dp / 44 px targets; the focus ring; Windows forced colours; the map and markers by keyboard; the dialog focus trap and focus return; reduced motion; Devanagari, Tamil and Telugu fonts on two OEM phones; Switch Access.
- **Automated or reviewed by:** TC-U-117 (Android accessibility checks in Robolectric), TC-U-WEB-A11Y-1..10, TC-M-26 axe. Reviewer ran it: web unit yes; devices no.
- Pass / Fail / Date: ____  by: ____

#### MT-50 TC-M-40: Search Console and link previews
- **Steps:** Add the site to Search Console (HTML tag in `index.html`), submit `sitemap.xml`; URL inspection of `/houses/new` says `noindex`; a shared link's preview in WhatsApp and one more app; the Rich Results test reads `SoftwareApplication`.
- **Automated or reviewed by:** TC-U-118. Reviewer ran it: unit yes.
- Pass / Fail / Date: ____  by: ____

#### MT-51 The release security gate on a release candidate ([13](../13-release-security-checklist.md))
- **Steps:** Parts A-I of docs/13 on a tagged release candidate (the release APK from `doorprints-release-apk`, the deployed site, a server if public), including H (TC-M-25 on the release build) and I1-I17 (the Drive rows; I15 n/a until S4b-BL-129).
- **Expected:** every row Pass or an accepted, recorded exception; record the run in docs/13 §6.
- **Automated or reviewed by:** the CI gate jobs (`security.yml`, CodeQL, MobSF, ZAP, gitleaks, trivy), the reviewer read the workflow permissions and secret handling: yes (OIDC/WIF only, no JSON keys, `permissions: {}` at workflow level). Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

## 13. Summary table (tick as you go)

| Id | Title | Who | Env | Reviewer ran it | Result |
|---|---|---|---|---|---|
| MT-01 | Layout phone vs computer | anyone | E1/E4/E5 | partly (unit) | |
| MT-02 | Installing | anyone | E1/E4/E5 | no | |
| MT-03 | Files, printing, sharing | anyone | E1/E4 | no | |
| MT-04 | Not on the website | anyone | E1/E4 | unit | |
| MT-05 | Browser-decided behaviour | anyone | E6 | unit | |
| MT-06 | Four languages | anyone | E1/E4 | unit + template check | |
| MT-07 | Native reading (TC-M-43) | native readers | any | no | |
| MT-08 | Both themes | anyone | E1/E4 | unit | |
| MT-09 | Offline start | anyone | E1/E4 | unit | |
| MT-10 | Update banner | owner | E1 | unit | |
| MT-11 | Map helper failed | anyone | E1 | unit + mutations | |
| MT-12 | Offline maps + boundary (TC-M-36) | owner | E1/E4 | unit; bytes checked | |
| MT-13 | TC-M-25 boundaries | owner/QA | E1 + E4 app | web unit; device no | |
| MT-14 | TC-M-28 iPhone map | owner | E5 app | no | |
| MT-15 | Search over every field | anyone | E1 + E4 app | web unit | |
| MT-16 | Export/import round trip | owner | E1/E4/E8 | web unit | |
| MT-17 | Open a backup from the system (TC-M-45) | owner | E1 | unit | |
| MT-18 | Slice 5 and Wave B (TC-M-44) | owner | E4/E1 | web unit | |
| MT-19 | Real Google sign-in, consent | owner | E1/E3/E5 | unit | |
| MT-20 | Recovery key shown once | owner | E1 | unit + probe | |
| MT-21 | Back up, import, save a copy | owner | E1/E7 | unit | |
| MT-22 | Automatic backup, shrink guard | owner | E1 | unit | |
| MT-23 | Sync two browsers (TC-M-47) | owner | E1+E7 | unit | |
| MT-24 | Photos on Wi-Fi (TC-M-54 web) | owner | E4 browser | unit | |
| MT-25 | Passkey: Windows Hello | owner | E1 | unit + mutations | |
| MT-26 | Passkey: phone QR, security key | owner | E3/E1 | unit + mutations | |
| MT-27 | No-lock computer | owner | E2 | unit + mutations | |
| MT-28 | Recovery key last resort | owner | E1 | unit + mutations | |
| MT-29 | Deletion L1 | owner | E1 | unit + probe | |
| MT-30 | Deletion L2 | owner | E1 | unit | |
| MT-31 | Deletion L3 + partial resume | owner | E1+E7 | unit | |
| MT-32 | Join: recovery key, 8-digit, QR | owner | E1+E7 | unit | |
| MT-33 | Revoke, new key, rollback | owner | E1+E7 | unit | |
| MT-34 | Disconnect, disconnect all | owner | E1+E7 | unit | |
| MT-35 | Firefox and Safari crypto | owner | E3/E5 | unit (jsdom) | |
| MT-36 | Headers and CSP | anyone | terminal | config read | |
| MT-37 | No secrets in console/storage/network | owner | E1 | greps | |
| MT-38 | Untrusted files in Drive | owner | E1 | unit | |
| MT-39 | Android smoke | owner | E4 app | host tests (see report) | |
| MT-40 | TC-M-27 | owner | E4 | no | |
| MT-41 | TC-M-29 app lock | owner | E4 | no | |
| MT-42 | TC-M-30/-31 Hunt, trace, iPhone | owner | E4/E5 | no | |
| MT-43 | TC-M-32 saved area | owner | E4/E5 | no | |
| MT-44 | TC-M-33/-34 | owner | two E4 | web unit | |
| MT-45 | TC-M-35/-37 | owner | E4/E5 | no | |
| MT-46 | TC-M-38 iPhone copies | owner | E5 | no | |
| MT-47 | TC-M-39 server page | owner | E8 | no (read only) | |
| MT-48 | Server upgrade with data | owner | E8 | no | |
| MT-49 | TC-M-41/-42 accessibility | owner/QA | all | web unit | |
| MT-50 | TC-M-40 Search Console | owner | web | unit | |
| MT-51 | Release gate (docs/13) | owner | all | CI gate read | |
