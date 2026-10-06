# Manual test checklist: real devices, real Google, real servers

| Field | Value |
|---|---|
| Document | The complete manual test list for Doorprints before real-system testing starts and before Android/iOS development continues: every check a person redoes on real devices, whether or not an automated suite or a review session already covered it |
| Version | 0.6 |
| Date | 2026-10-06 |
| Author | Claude, senior reviewer |
| Status | Draft |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-10-06 | Claude, senior reviewer | First version, from the full-system review of `main` `8c367a40`: environments, the phone-vs-computer differences, TC-M-25..45 and TC-M-46..56 of [06](../06-test-plan.md) rewritten as runnable steps, Google sign-in and Drive, passkeys (Windows Hello, phone by QR, security key, no-lock computer), the recovery-key last resort, deletion L1/L2/L3 with a partial run, enrolment and revocation, languages and themes, offline/PWA, the map's worker message, India's boundaries, search, export/import round trip, Android and server smoke tests, security checks. |
| 0.2 | 2026-10-06 | Claude | The review's should-fix items were fixed in the same pull request: the camera header (MT-32 step 5, MT-36), the passkey state kept across a reload (MT-28 step 7), the recovery-key wording (MT-19). |
| 0.3 | 2026-10-06 | Claude | Section 14 follows what the branch decided after the review: the folder-gone card (*Start again* / *Disconnect*), *Delete this backup* on a row, the rows going back to the server on *Disconnect*, the approver naming the new device, the lock notice once; the not-built list (browser + PKCE sign-in, camera scan, the Android HMAC proof, the "New device enrolled" notice). |
| 0.4 | 2026-10-06 | Claude (Code), lead | New section 15, **the path trace, version 2** (designed in [11](../11-feature-parity-and-export-spec.md) 5.27.0..5.27.11, not built yet): MT-75..MT-80 for TC-M-57..TC-M-60; run them when S4b-FR-13..S4b-FR-18 are merged. |
| 0.5 | 2026-10-06 | Claude (Code), lead | Section 15 after the senior review of the design ([11](../11-feature-parity-and-export-spec.md) v0.57): MT-75 adds two lanes 30 m apart and the protanopia form-only check and names the new automated test; MT-77 the killed-app walk; MT-78 the Android phone-to-phone transfer (walks arrive) instead of "a new phone starts without saved walks"; MT-79 the 5-minute hidden pause with no line across it. |
| 0.6 | 2026-10-06 | Claude (Code), lead | New **MT-81** in section 15, *Have I been here?* (the on-demand place check, [11](../11-feature-parity-and-export-spec.md) 5.27.13, S4b-FR-24, TC-M-61); summary row 15.1. |

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

## 14. Phones: Google Drive on Android and iPhone

Added 2026-10-06 by the senior reviewer of PR #142 (branch `feat/android-drive`, `53f34f66`), the phones' half of [15](../15-google-drive-backup-and-sharing.md). Everything below needs a **real phone**: the Google sign-in (Play services), the Android Keystore, BiometricPrompt and the keyguard, WorkManager and the lock removal have no Robolectric or emulator proof (docs/ops/android-drive-wiring-notes.md "What needs a real phone"). Before starting: the Google Cloud setup of [15](../15-google-drive-backup-and-sharing.md) §2.4 for the **debug** package name and its SHA-1, a **test Google account**, two Android phones if possible (one on Android 8-10, one on 12 or newer; Android 11 on its own covers the third row of MT-71), the website signed in to the same account (E1 or E7), and a *Save a copy* Full backup of each phone. *Reviewer ran it* below means the JVM host suites of 2026-10-06 (`:shared` 1059, `:ui` 239, `:app` 871 tests, all green, Roborazzi verify on) and the reviewer's temporary adversarial probes (wrong PSK/epoch/tamper, off-curve key, foreign and rolled-back `keys.json`, two writers of one revision, revoked device, forged/expired/reused grants, offline and lock-removed mid-delete, malformed `dp1.` and reply JSON, tampered device-key blob) plus six mutation checks, each caught by a named test. **None of it ran on a phone.**

Gaps found in the review and **fixed on the branch afterwards** (the tests below check the fixes on a phone): the *Folder was deleted* dead end (MT-67: *Start again* / *Disconnect*), *Delete this backup* on a row (MT-62), the Drive-to-server hand-back after *Disconnect* (MT-69: `resetForServer`), the lock-pause notification repeating at every background run (MT-65: once, cleared when the lock is back), the approver naming the new device (MT-56, MT-57). **Not built yet, so not tested here:** the browser + PKCE sign-in for phones without Google Play services (owner decision of 2026-10-06, [15](../15-google-drive-backup-and-sharing.md) §5.5; add a test when it is built), the camera QR scan (no library chosen: the phones paste the `dp1.` text, [15](../15-google-drive-backup-and-sharing.md) §9.5 i), the operation-bound HMAC proof on Android (S4b-BL-135), and the "New device enrolled" notice on the phones ([15](../15-google-drive-backup-and-sharing.md) §9.3). Decisions and what was built: [15](../15-google-drive-backup-and-sharing.md) §9.9 and §10.2.

#### MT-52 First connect on a phone with a lock: Google consent (`drive.file` only) and the recovery key shown once (TC-M-46, TC-M-53)
- **Area:** Android app. **Who:** owner. **Env:** E4 app (phone with a PIN or biometrics set), test Google account.
- **Steps:** (1) Settings > *Back up to Google Drive*: the card shows the intro and *Connect to Google Drive*. (2) Tap it: Google's own account picker and consent screen appear; read the consent text: it asks only to "See, edit, create and delete only the specific Google Drive files you use with this app" (`drive.file`), nothing else. (3) Allow. The recovery key screen shows the key once (7 groups, `XXXX-…-XXX`), *Copy recovery key*, the warning, the tick box *I have saved my recovery key*, *Next* (off until ticked) and *Skip*. (4) Tap *Copy*: "Copied to clipboard"; paste it somewhere safe (the test account's notes are fine, this is a test). On Android 13+ note whether the clipboard preview shows the key in plain (it will; a should-fix is open). (5) Tick, *Next*: the Ready card (Backups, Sync, Devices, Enrol, Delete, Disconnect). (6) Rotate the phone on the key screen before step 5: the key must still be on screen (ViewModel scope). (7) Kill the app on the key screen and reopen: the key is gone for good (in memory only); Settings shows Ready; the key cannot be shown again. (8) In drive.google.com: a `Doorprints` folder with `doorprints.json`, `Read me.txt`, `keys.json` and nothing else yet; open `keys.json`: noise, one device named after `Build.MODEL`, platform `android`.
- **Expected:** consent lists `drive.file` only; the key appears exactly once; *Next* needs the tick; no key in logcat (`adb logcat | grep -i -E 'DP[0-9A-Z]{3}-|recovery'` shows nothing).
- **Automated or reviewed by:** `AndroidDriveTokenProviderTest` (scope exactly `drive.file`, granular-consent refusal, token in memory only), `DriveConnectStateTest`, `DriveHolderTest` (key once, dropped on *Next*/*Skip*), `DriveScreenshotTest` (`drive_key_*`), reviewer mutation M5 (scope check dropped: 7 tests fail). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-53 Connect on a phone **without** a screen lock, and granular-consent refusal (TC-M-51, TC-M-46)
- **Area:** Android app. **Who:** owner. **Env:** E4 app, a phone (or the same phone after removing its lock) with Drive **not** yet in use.
- **Steps:** (1) Phone Settings > Security: remove the screen lock (None/Swipe). (2) Doorprints Settings: the Drive card is replaced by the heading *Back up to Google Drive*, the sentence "Google Drive backup needs a screen lock on this phone (a PIN, pattern, password, fingerprint or face). Set one in the phone's settings, then come back." and *Open settings*. (3) Tap *Open settings*: the phone's security settings open (or the general settings on a phone without that screen). (4) Set a PIN, press back: the card appears without restarting the app (read on resume). (5) Connect, and on Google's consent screen **untick** the Drive permission (granular consent) and continue: the card shows the *denied* sentence (`driveProblem.SIGNIN_DENIED`), nothing is created in Drive, no folder, no key. (6) Connect again and close the consent screen with Back: the card goes back to *Connect* (closed is not denied, no error box). (7) Airplane mode, Connect: the offline sentence; nothing crashes.
- **Expected:** without a lock nothing is asked of Google (no consent screen at all); unticked permission = denied and fail closed; closed = back to where it was.
- **Automated or reviewed by:** `DriveDecisionsTest` (`DriveLockRules.notice`), `DriveAdaptersTest` (`withoutAScreenLockNothingIsAskedOfGoogle`, `aClosedPromptIsNotADenial`), `ConsentBridgeTest`, `AndroidDriveTokenProviderTest`, `DriveLockStringsTest` (four languages). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-54 Back up now, the list, Import a backup from Drive (TC-M-46 part 2)
- **Area:** Android app. **Who:** owner. **Env:** E4 app, connected (MT-52), a few houses with photos.
- **Steps:** (1) *Back up now*: "N houses backed up at <time>"; the list shows one row (date, houses, size). In drive.google.com, `Doorprints/Backups/Doorprints-backup-<date>.dpx` exists, no `partial-` file remains, its size is small (no photo bytes). (2) Download the `.dpx` and open it in a text editor: noise after `DPX1`. (3) Back up twice more; the list has three rows, newest first. (4) Row > *Import a backup*: the existing Import screen opens with the file and its preview (houses count matches); import it; the houses are as before (no duplicates). (5) Check `cache/imports/` is swept (`adb shell run-as app.doorprints ls cache/imports`): the decrypted ZIP is deleted after import or within six hours. (6) Airplane mode > *Import a backup*: the offline sentence, nothing imported. (7) Toggle *Automatic backup* off and on: the switch state survives an app restart. (8) Make the Drive almost full (not practical) — skip; instead confirm the *shrink* question appears when a backup is much smaller than the previous one (delete most houses, back up): *Keep the older backups* and *Confirm*.
- **Expected:** backups are encrypted `.dpx` files without photos; import goes through the normal preview; no plaintext backup left in the cache after the sweep.
- **Automated or reviewed by:** `DriveConnectBackupSyncTest`, `DriveFilesTest` (`AndroidDriveBackupSource`, `DriveImportFile`), `DriveBackupServiceTest` (`:shared`), `DriveHolderTest` (backups card). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-55 Sync between two phones and the website (TC-M-47)
- **Area:** Android app + website. **Who:** owner. **Env:** E4 (two phones, or one phone and E7), E1 website, one test account; the second device enrolled by MT-57 or MT-58 first.
- **Steps:** (1) Add a house on phone A, *Sync now*: "Synced at <time>". (2) On the website: sync; the house appears. (3) Edit it on the website, *Sync now* on A: the edit arrives. (4) Delete it on phone B, sync A and the website: gone on both (tombstone wins). (5) Phone A in airplane mode: edit two houses, add one; after 30 minutes online again (or *Sync now*): everything converges, no duplicates, `Sync/device-<id>.dpx` per device in Drive. (6) Edit the same house on A (offline) and on the website (online) with different values; bring A online and sync: the later `updatedAt` wins on all three. (7) Check the sync status line on each phone and that `Settings > Server` sync (if a server is configured) is **not** used while Drive is connected (the server's row count does not change).
- **Expected:** three devices converge; last-write-wins; nothing is sent to the self-hosted server while Drive is in use.
- **Automated or reviewed by:** `DriveSyncEndToEndTest` (two Room databases through the fake Drive), `RoomSyncRowsTest`, `DriveDecisionsTest` (`DriveSyncChoice`), `DriveSyncEngineTest` and the merge vectors (`:shared`). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-56 The phone enrols a second phone by pasted `dp1.` offer and the 8-digit check (TC-M-52)
- **Area:** Android app, two phones. **Who:** owner. **Env:** E4 x2 (phone A connected; phone B fresh install, same test account, lock set).
- **Steps:** (1) Phone B: Connect, sign in: "This Google Drive already has Doorprints backups…" (the Join card), the recovery-key field and *Show my code*; nothing is written to Drive (check `keys.json`'s modified time). (2) B: *Show my code*: a QR, the `dp1.` text with *Copy*, and an 8-digit code. (3) Copy the text to A (Nearby Share, a message, or type it); on A: Ready card > *Approve a new device* > paste > *Next*: A shows an 8-digit code and a name field (default "New device") with a Phone / Computer choice. (4) Compare the two codes: they must match; change one character of the paste and repeat: A shows a **different** code (or "not a valid code"). (5) With matching codes tap *The numbers match*: the device check (BiometricPrompt or PIN) appears; cancel it: "Nothing was changed", `keys.json` unchanged. (6) Repeat and pass the check: A shows the reply (QR + text, *Copy*). (7) Paste the reply on B > *Join now*: B becomes Ready, lists both devices, opens A's backups (*Import a backup* preview works). (8) On A the Devices card lists B under the name typed in step 3 (the offer carries no name). (9) Paste a reply with a changed character on B: "That reply is not valid", B stays on the Join card.
- **Expected:** B writes nothing until joined; the approver's device check is mandatory; a wrong paste shows a different code; the reply only opens on the phone that showed the offer (the PSK).
- **Automated or reviewed by:** `DriveEnrolmentTest`, `QrEnrolTest`, `QrCodeKnownAnswerTest`, `DriveEnrolmentCodecTest` (`theCodeDependsOnBothTheKeyAndTheSecret`), `DriveAdaptersTest` (`theQrPskPathWorksAcrossTwoServices`), `DriveConnectEnrolmentTest`, `DriveHolderTest` (enrol dialogs), `DriveScreenshotTest` (`drive_join_qr_en_light`); reviewer probes (wrong PSK, wrong epoch, tampered ciphertext, base wrap as PSK wrap: all refused, no pin) and mutations M1 (first-pin compare dropped: 3 tests fail) and M6 (PSK wrap replaced by the base wrap: 5 tests fail). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-57 The website enrols a phone, and a phone enrols the website (TC-M-52 cross-platform)
- **Area:** Android app + website. **Who:** owner. **Env:** E4 + E1/E7.
- **Steps:** (1) Fresh phone on the Join card > *Show my code*; on the connected website: Devices > approve > paste the `dp1.` text (or scan with the camera where the browser can); passkey check; copy the reply JSON (`{"wrapEnc","wrapCt","epoch"}`) to the phone > *Join now*: Ready. (2) The reverse: a fresh browser shows its `dp1.`; the phone pastes it, device check, shows the reply; paste on the website: Ready. (3) Both list the devices by the same names; the website lists the phone by its model, the phone lists the browser under the name the approver typed, as a Computer. (4) A phone enrolled by the website opens a backup the website wrote, and the reverse.
- **Expected:** the formats are the same on both stacks (`dp1.` base64url of 65 + 32 bytes; the reply JSON; `psk_id doorprints/dpx1/qr-psk`).
- **Automated or reviewed by:** `QrCodeKnownAnswerTest` (byte-identical matrices to the website's `encodeQr`), `DriveEnrolmentCodecTest` (the website's JSON), `HpkePlatformVectorsTest`; the parity vectors `docs/schemas/dpx-vectors.json`, `hpke-vectors.json` on both stacks. Reviewer ran it: yes (JVM); cross-stack on real devices: no.
- Pass / Fail / Date: ____  by: ____

#### MT-58 Join with the recovery key, a wrong key, and a typo (TC-M-53)
- **Area:** Android app. **Who:** owner. **Env:** E4, a fresh phone (or MT-53's phone after re-enrolment is needed).
- **Steps:** (1) Join card: type the recovery key with lower case, no hyphens, `o` for `0`: *Join this folder* → Ready. (2) Type a key with one changed character: "That key does not read" at once (no network traffic: airplane mode gives the same answer). (3) Type a well-formed key that is not this folder's (another test account's, or MT-61's old key): "That key does not open this folder"; the Join card stays; `keys.json` is unchanged. (4) The typed key is cleared from the field after *Join*. (5) After a successful join the Devices card lists the new phone.
- **Expected:** the check symbol catches a typo before Drive is asked; a wrong key never writes; the typed key is never kept.
- **Automated or reviewed by:** `RecoveryKeyTest`, `DriveConnectStateTest` (wrong key keeps the join form), `DriveEnrolmentTest`, reviewer probe (`recoveryKeyParsingNeverReachesDriveOnATypo`). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-59 Revoke a device: new recovery key shown once, the revoked phone stops (TC-M-52/-53)
- **Area:** Android app, two phones. **Who:** owner. **Env:** E4 x2 enrolled (MT-56).
- **Steps:** (1) On A: Devices > *Revoke* next to B: the dialog names B; *Revoke* asks for the device check; cancel: nothing changes. (2) Again, pass: A shows **a new recovery key** with the tick box and *Next*; the dialog says the old key no longer opens anything. Copy it; destroy the old one. (3) Tick, *Next*: the key leaves the screen. (4) On B: *Sync now* or reopen Settings: the card says this device was revoked (`driveProblem.DEVICE_REVOKED`), with only the recovery-key field and *Disconnect* (no enrol button). (5) B types the **old** recovery key: refused. B types the **new** key: refused too (a revoked device stays revoked; it must be a new device: *Disconnect*, then join again by MT-56, which lists it as a new kid). (6) A backs up after the revoke; the website (enrolled, not revoked) still opens the new backup without typing anything (the new epoch reached it). (7) The row for *This phone* has no *Revoke*.
- **Expected:** every revoke makes a new epoch and a new recovery key; the old key stops verifying; the revoked phone cannot read new files nor write.
- **Automated or reviewed by:** `DriveEnrolmentTest` (revoke, new epoch, old key refused), `DriveAdaptersTest` (`aRevokeGivesANewRecoveryKeyAndTheRevokedDeviceLosesTheFolder`), `DriveConnectEnrolmentTest`, `DriveHolderTest` (new key once), reviewer probe (`revokedDeviceCannotApproveAndOldRecoveryKeyStopsWorking`). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-60 Deletion L1: older backups (TC-M-48 part)
- **Area:** Android app. **Who:** owner. **Env:** E4 connected with three or more backups.
- **Steps:** (1) Delete card > *Delete older backups*: the plan (counts and bytes, never file names), *Save a copy first* (opens the Export screen and comes back), *Proceed*. (2) The confirm step: no tick box, no countdown, no device check for L1; *Delete*. (3) "Done"; the list shows one backup; drive.google.com's bin does **not** hold them (permanent delete). (4) Airplane mode, repeat: "You are offline…", nothing deleted, nothing queued (back online: nothing happens by itself).
- **Expected:** L1 is the dialog alone; offline refused, not queued.
- **Automated or reviewed by:** `DriveConnectDeleteTest`, `DriveDeletionServiceTest`, `DeletionPolicyVectorsTest` (shared vectors with the website), `DriveScreenStateTest`. Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-61 Deletion L2: Delete all backups with the device check: cancel, wrong fingerprint, pass (TC-M-48, TC-M-50)
- **Area:** Android app. **Who:** owner. **Env:** E4 connected, two backups, fingerprint or face enrolled plus a PIN.
- **Steps:** (1) *Delete all backups* > plan > *Proceed*: the confirm step has the tick box, no countdown, and the device-check heading and sentence ("These checks protect Doorprints on this device…"). (2) *Delete for good* is off until ticked; tap it unticked: the hint "Tick the box first". (3) Tick, tap: BiometricPrompt (Android 10 and newer: title = the Doorprints line; "Use PIN" offered) or the keyguard confirm screen (Android 8-9). Cancel: "Nothing was deleted"; the two backups are still in Drive; the confirm step stays with the reason. (4) Again: use a **wrong finger** three times: the prompt stays up (wrong finger is not an error); then cancel: nothing deleted. (5) Again, pass with the PIN: "Done"; `Backups/` is empty, the bin is empty; *Automatic backup* is now **off** on this phone; `doorprints.json` records `backupsDeletedAt`. (6) On the second phone or the website: the next sync says backups were deleted and asks before backing up again (the control file). (7) Wait more than 60 seconds between passing the check and… (not possible here, the run starts at once; see MT-63 step 5 for the resume path). (8) Check on the connected phone that *Automatic backup* can be switched on again by hand.
- **Expected:** L2 needs the factor every time; cancel or fail deletes nothing; the other devices stop automatic backups and ask.
- **Automated or reviewed by:** `DriveConnectDeleteTest`, `PhoneDeletionAuthorizerTest`, `DriveGateTest`, `OperationProversTest` (`PromptErrors.map`), `DriveScreenshotTest` (`drive_delete_confirm_en_light`); reviewer probe (`deleteEverythingWithoutGrantOrWithForgedTokenDeletesNothing`: no grant, forged token, cancelled check, grant of another plan, grant older than 60 s, clock set back: nothing deleted) and mutations M2/M4 (grant age ignored; redeem not marked spent: caught). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-62 Deletion of **one** backup from its row
- **Area:** Android app. **Who:** owner. **Env:** E4 connected, two backups.
- **Steps:** (1) Each backup row has *Import a backup* and *Delete this backup* ([15](../15-google-drive-backup-and-sharing.md) §3.1, §5.7; the website has both). (2) *Delete this backup* on one of two rows is L1 (dialog only); when it is the **last** backup it is L2 (device check).
- **Expected:** the row action exists and the last-backup rule holds.
- **Automated or reviewed by:** `DeletionPolicyVectorsTest` (the last-backup rule exists in the policy), `DriveScreenStateTest` (`deleteMenu(oneBackupId)` adds the choice only when a row is chosen; the review found that no screen passed one, and the fix passes the chosen row). Reviewer ran it: JVM yes; phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-63 Deletion L3: Delete everything, the 5-second countdown, interrupted by airplane mode, and lock removed half way (TC-M-48, TC-M-50, TC-M-51)
- **Area:** Android app. **Who:** owner. **Env:** E4 connected with backups, sync files of two devices and 20 or more photos uploaded (so the run takes a while).
- **Steps:** (1) *Delete everything Doorprints keeps in my Google Drive* (red): the plan lists backups, sync files, photos, "other" (control, key list, read me) and the total; *Proceed*. (2) The confirm step: tick box, the countdown "Wait N s" from 5 (TalkBack reads it), the device-check sentence; *Delete for good* stays off until the tick **and** the countdown; set the phone's clock forward 10 s during the countdown and it must not skip (the countdown follows the monotonic clock, not the wall clock). (3) Pass the check; after two or three files switch airplane mode on: "X of Y files are still in your Drive" with *Try again*; nothing was re-created; the houses on the phone are intact. (4) Back online, *Try again*: a **fresh** device check is asked (the first grant is spent); cancel it: still X left; pass it: the rest goes; "Done"; the card is now *Disconnected*; drive.google.com has no `Doorprints` folder and nothing in the bin. (5) Repeat the whole test on a second run and, while the run is going, remove the screen lock in the phone's settings (split screen): the run stops before the next file ("authorisation lost"), *Try again* is refused until a lock is set again, and then this phone must re-enrol (MT-64). (6) After *Delete everything*, the **other** enrolled phone: Settings shows the folder-gone sentence (see MT-67 for the dead end) and never re-creates the folder by itself; the website asks "Your Doorprints data in Google Drive was deleted. Back up again?".
- **Expected:** L3 = tick + 5 s + device check; a stop leaves a resumable list, never a re-created folder; *Try again* needs a fresh check; a lock removed mid-run stops the run before the next file.
- **Automated or reviewed by:** `DriveDeletionServiceTest` (`theLockRemovedHalfWayStopsBeforeTheNextFileAndAFreshGrantFinishes`), `DriveConnectDeleteTest`, `DeletionStoreTest` (the pending list and the marker survive a restart), reviewer probes (`offlineMidDeleteStopsAndResumeNeedsAFreshCheck`, `lockRemovedMidDeleteStopsBeforeTheNextFile`) and mutation M3 (`stillHolds` dropped: 2 tests fail). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-64 The lock removed while Drive is in use: pause, notice, key invalidated, re-enrolment (TC-M-51)
- **Area:** Android app. **Who:** owner. **Env:** E4 connected and synced; a second device enrolled or the recovery key at hand.
- **Steps:** (1) Phone settings: remove the screen lock. (2) Open Doorprints Settings: above the card "Google Drive backup is paused because this phone no longer has a screen lock. Your houses are safe on this phone. Set a screen lock to continue." with *Open settings*; the card stays so *Disconnect* is possible. (3) *Sync now*: paused, nothing uploaded (check `Sync/device-…` modified time in Drive). (4) Force the background run (`adb shell cmd jobscheduler run -f app.doorprints <jobId>` from `dumpsys jobscheduler | grep drive-backup`): **one** notification "Google Drive backup paused" appears; tap it: Settings opens. Force it again: note whether a second notification appears (it will re-post; a should-fix is open). Nothing changes in Drive. (5) Set a PIN again: the card now says the phone must connect again and enrol (the Keystore key was invalidated with the lock): Connect, sign in (quiet, the Play grant holds), the Join card; join by MT-56 or MT-58; the houses on the phone are intact throughout; the Devices card on the other device shows the phone twice (old kid and new kid) until the old one is revoked. (6) On Android 8-10 the same (the wrapping AES key is invalidated instead of the EC key; `noBackupFilesDir/drive-device-key.bin` stays until discarded).
- **Expected:** no upload, download or delete while paused; one notice; the keys die with the lock; re-enrolment needed; local houses untouched.
- **Automated or reviewed by:** `DeviceLockTest`, `DriveBackgroundRunnerTest` (`aRemovedLockStopsBeforeAnythingAndSaysTheDocumentedNoticeOnce`, `theLockIsAskedBeforeEveryStep`), `DeviceKeyTest` (`aLostKeyWithAPinnedFolderIsNeverReplaced`), `KeystoreErrorsTest`, `DriveLockNoticeTest`, `DriveGateTest`; mutation M10 (an UNKNOWN lock state must not drop keys: caught). Reviewer ran it: yes (JVM); the Keystore invalidation itself: no (needs a phone).
- Pass / Fail / Date: ____  by: ____

#### MT-65 The background worker: runs, retries, cancellation, battery and network (docs/15 §1.3, §11)
- **Area:** Android app. **Who:** owner. **Env:** E4 connected, *Automatic backup* on.
- **Steps:** (1) `adb shell dumpsys jobscheduler | grep -A3 drive-backup`: one periodic job (6 h, CONNECTED, battery not low). (2) Force it: a sync pass and, if due (daily), a backup; the list gains a row the next day. (3) Switch *Automatic backup* off: the job disappears from dumpsys; on: it is back. (4) *Disconnect this device*: the job is gone. (5) Battery saver on and battery below 15 %: the job waits. (6) Airplane mode, force the job: `WorkManager` retry with backoff (dumpsys shows the backoff); five retries then it waits for the next period. (7) Kill the app, force the job: it reconnects quietly (no consent screen) and runs; if Google wants a screen it fails without a notification and waits for the person. (8) `adb shell dumpsys batterystats --charged app.doorprints` after a day: the Drive run is a few seconds of CPU per 6 h; note the GHASH cost on photos only on iPhone (MT-73).
- **Expected:** the job exists only while in use and automatic backup is on; the "Drive is paused, the screen lock is off" notification appears once per pause, not at every run, and is cleared when the lock is back; nothing runs without the lock; a transient failure retries, a person-needed failure waits.
- **Automated or reviewed by:** `DriveBackupWorkerTest` (`result`), `DriveBackgroundRunnerTest` (`aFailureThatNeedsThePersonDoesNotRetry`, `aRestartedProcessReconnectsFirst`), `DriveDecisionsTest` (`shouldSchedule`, `decide`), `DriveAssemblyTest` (reschedule on both switches). Reviewer ran it: yes (JVM); WorkManager on a phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-66 Photos: Wi-Fi only by default, the one-off over mobile data, Data Saver and roaming (TC-M-54)
- **Area:** Android app. **Who:** owner. **Env:** E4 connected, a SIM with data, Wi-Fi available.
- **Steps:** (1) On mobile data add three photos, *Sync now*: text syncs; the switch *Upload photos only on Wi-Fi* is on and shows "waiting for Wi-Fi"; *Upload photos now over mobile data (N MB)* appears with the size. (2) Join Wi-Fi, *Sync now*: the photos go (`Photos/p-….dpx` in Drive, each 0.2-2 MB). (3) Mobile data again, more photos, tap *Upload photos now*: they go once; the switch stays on; after 30 minutes new photos wait again. (4) Switch off: photos go on mobile data. (5) Data Saver on (phone settings): photos wait even with the switch off; the one-off still works. (6) Roaming (if testable) the same as Data Saver. (7) Kill the app during a 2 MB upload on Wi-Fi: the next run resumes the session (one file in Drive, not two).
- **Expected:** the policy of §11; sizes shown; the one-off is a 30-minute exception.
- **Automated or reviewed by:** `PhotoUploadPolicyTest` and the shared vectors, `DriveAdaptersTest` (`mobileDataIsMeteredAndRoamingAndDataSaverAreSeen`, `aNetworkWithoutValidatedInternetIsNotOnline`), `DriveAssemblyTest` (`theSyncPassGetsTheBackendAndThePhotoGate`), `DriveHolderTest` (upload now). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-67 The folder deleted elsewhere: *Start again* or *Disconnect* (docs/15 §3.4)
- **Area:** Android app. **Who:** owner. **Env:** E4 connected; the folder deleted from drive.google.com or by *Delete everything* on another device.
- **Steps:** (1) Open Settings: the card says the folder was deleted (`driveConnect.folderGone`) and asks the §3.4 question, with *Start again* and *Disconnect*; nothing is created by opening the screen or by a background run. (2) *Start again*: a new folder is made and a new recovery key is shown once. (3) Repeat the deletion, kill and reopen the app: the same card (never a silent new folder, and no loop). (4) *Disconnect*: Drive is left and the server path returns (MT-69 step 4).
- **Expected:** never a silent re-creation; always a way out.
- **Automated or reviewed by:** reviewer probe `folderGoneIsADeadEndForConnectButCreateFolderStartsAgain` (it found the dead end: `connect()` repeats FOLDER_GONE and `DriveHolder.connect` created only when `error == null`; the fix adds the folder-gone state to the holder, with the tests that name *Start again* and *Disconnect*). Reviewer ran it: JVM yes; phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-68 Hostile or damaged files in Drive on the phone (I9, I11, docs/15 §9.9)
- **Area:** Android app. **Who:** owner. **Env:** E4 connected; drive.google.com open.
- **Steps:** (1) In Drive, *Manage versions* on `keys.json`: restore an older version after a device was added: the phone says the key list was replaced by an older copy (`KEYS_ROLLED_BACK`), writes nothing, and *Sync now* / *Back up now* are refused; restore the newest version: Ready again. (2) Edit `keys.json` by hand (one character): `KEYS_UNREADABLE`/`KEYS_UNTRUSTED`, nothing written; the pin is not moved (the next genuine list still opens). (3) Replace `keys.json` with another test account's `keys.json`: "This folder belongs to another key set" (Join card), nothing adopted, the recovery key of the **genuine** list still works after restoring it. (4) Edit a backup `.dpx` by hand: its row's *Import a backup* refuses ("could not be opened"), no partial file left in the cache. (5) Edit another device's `Sync/device-….dpx`: the sync reports a skipped file and the rest syncs. (6) Delete `Backups/` only: the list is empty; the next backup re-creates `Backups/` (allowed) but never the root.
- **Expected:** every file read is validated; no error kind triggers a revoke, re-key, wipe or re-creation (§9.9).
- **Automated or reviewed by:** `KeysFileTest`, `KeysGuardTest`, `DpxTest` (tamper rows), `DriveEnrolmentTest`, reviewer probe (`hostileDriveForeignListRollbackAndFolderGone`, `twoWritersOfOneRevisionAreAForkNotASilentAdoption`). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-69 Disconnect this device, Disconnect on all devices, and the server path afterwards (docs/15 §3.5)
- **Area:** Android app (+ a self-hosted server if E8). **Who:** owner. **Env:** E4 connected; optionally E8 configured in Settings > Server.
- **Steps:** (1) *Disconnect this device* (red): the card returns to *Connect*; the files in Drive stay; the other devices keep working; the Play grant stays (Connect again needs no consent screen). (2) Connect again: Ready at once, same device (the Keystore key was kept), no new entry in `keys.json`. (3) Devices > *Disconnect on all devices*: the dialog, the device check (cancel: nothing happens), pass: this phone disconnects and Google's *Your connections to third-party apps* no longer lists Doorprints; the other devices show "Google Drive disconnected" at their next run. (4) **Server hand-back:** with E8 configured, add a house while Drive is connected and synced to Drive, then *Disconnect this device*: the server sync runs again at the next pass. The house must reach the server: when Drive is left the rows go back to the server (`resetForServer`, [15](../15-google-drive-backup-and-sharing.md) §1.3), because a push to Drive had marked them clean.
- **Expected:** disconnect deletes nothing; disconnect-all revokes the grant at Google; the server receives everything edited while Drive was in use.
- **Automated or reviewed by:** `DriveConnectStateTest` (disconnect), `AndroidDriveTokenProviderTest` (revoke clears memory first), `DriveDecisionsTest` (`DriveEngagement`), reviewer probe (`approveAndRevokeNeedTheDeviceCheckAndDisconnectAllToo`: disconnect-all cancelled → no revoke call). Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-70 Android 8-10 versus 11 versus 12 and newer: the device key and the device check
- **Area:** Android app. **Who:** owner. **Env:** E4: an API 26-29 phone, an API 30 phone if at hand, an API 31+ phone.
- **Steps:** (1) **API 26-29:** connect; `run-as app.doorprints ls no_backup/drive/` shows `drive-device-key.bin` (65 + wrapped scalar bytes) and the Keystore alias `doorprints_drive_device_wrap`; MT-56 (as the newcomer) and MT-55 work; the L2 check is the **keyguard confirm screen** (`createConfirmDeviceCredentialIntent`), not BiometricPrompt; remove the lock: `status()` says invalidated and MT-64 holds. (2) **API 30:** the device key is still the wrapped scalar; the L2/L3 check is BiometricPrompt with a `CryptoObject` (per-use HMAC key `doorprints_drive_delete_hmac`); confirm the prompt offers both biometrics and the PIN. (3) **API 31+:** no `drive-device-key.bin`; the Keystore EC key `doorprints_drive_device_ec` does the ECDH (`PURPOSE_AGREE_KEY`); enrol with an API 29 phone in both directions (the shared secret must agree between the Keystore path and the wrapped-scalar path). (4) On all: six hours after the last unlock (or at once on a phone that never unlocked since boot) the background run must **wait** (`NEEDS_UNLOCK`), not re-enrol; unlocking the phone and forcing the job works. (5) A vendor Keystore that refuses `PURPOSE_AGREE_KEY` (some older Samsung/Xiaomi builds): connect fails with the generic Drive sentence; record the model (the fallback to the wrapped scalar is a one-line change in `AndroidDeviceKeys.backend`).
- **Expected:** the three code paths interoperate; the proof on Android 11+ is bound to the prompt (the CryptoObject HMAC is computed but **the gate's grant id is still the proof**, docs/15 §10.4's open item; a rooted phone is out of scope §10.6).
- **Automated or reviewed by:** `WrappedScalarKeyBackendTest`, `DeviceKeyTest`, `KeystoreErrorsTest`, `OperationProversTest`, `DeviceAuthorizationGateTest` (the gate that would carry the HMAC proof; **not wired** in the graph), reviewer probe (`wrappedScalarBlobTamperIsLostNotAWrongKey`). Reviewer ran it: yes (JVM, fake backends); every real Keystore call: no.
- Pass / Fail / Date: ____  by: ____

#### MT-71 Four languages and dark mode on every Drive card (TC-M-27, docs/05)
- **Area:** Android app. **Who:** owner, then native readers (MT-07). **Env:** E4.
- **Steps:** (1) Settings > Language: Hindi, Tamil, Telugu, English; for each walk the Disconnected card, the key screen, the Join card (with the QR), the Ready card (all six sections), the delete plan and confirm steps, the revoke dialog, the lock notice, the notification and the BiometricPrompt title line. (2) Dark theme for the same screens: the QR stays black on white; error text readable; buttons' colours from the theme. (3) Every sentence is a translation, never a key such as `driveDelete.reason.AUTH_FAILED` (a missing key falls back to the generic "could not connect" sentence: report any card that shows that sentence out of place). (4) Brand words: *Import a backup*, *Save a copy*, never "Restore".
- **Expected:** all strings present in hi/ta/te (marked under review in the XML), both themes legible.
- **Automated or reviewed by:** `DriveStringsTest` (every key in four languages, brand words), `DriveLockStringsTest`, `DriveScreenshotTest` (`*_hi_light`, `*_en_dark`), `DriveScreenStateTest` (`driveResName`). Reviewer ran it: yes (JVM, screenshots verified); phone: no.
- Pass / Fail / Date: ____  by: ____

#### MT-72 TalkBack and 200 % text on the Drive cards (TC-M-41/-42, docs/05)
- **Area:** Android app. **Who:** owner/QA. **Env:** E4, TalkBack on; then font size and display size at maximum.
- **Steps:** (1) TalkBack: every button is announced with its label (rows: "Import a backup, <date>", "Revoke: <name>"); the recovery key is read **letter by letter** (not as a word); the 8-digit code digit by digit; the QR has a description; headings are announced as headings; the countdown, "connecting", "done", errors and the paused notice are announced when they change (live regions); the tick box rows toggle from the whole row; the BiometricPrompt is TalkBack's own. (2) 200 % text and large display: no clipped button label (labels wrap), the key box wraps, the QR stays whole, the backup rows do not overlap, the delete buttons remain 48 dp and tappable.
- **Expected:** no unlabelled control; nothing clipped.
- **Automated or reviewed by:** code reading of `DriveScreens.kt` (`heightIn(48.dp)`, `LiveMessage`, `spellCode`, `codeSpoken`, `ButtonLabel`), `DriveScreenshotTest` at default scale only. Reviewer ran it: no.
- Pass / Fail / Date: ____  by: ____

#### MT-73 iPhone: what CI's macOS job and the owner's Mac must prove (S4b-BL-131)
- **Area:** iPhone (`:shared` iosSimulatorArm64, the iOS app). **Who:** CI (`shared-ios.yml` job `ios-sim-tests`) and the owner on a Mac. **Env:** E5 + a Mac with Xcode.
- **Steps:** (1) CI: `:shared:iosSimulatorArm64Test` green with `PlatformCryptoProviderTest` (GCM NIST cases through `CCCrypt` ECB, RFC 4231 HMAC, RFC 5903 ECDH, the public key of a scalar accepted by `SecKeyCreateWithData(04‖x‖y‖d)` and equal to Apple's), `HpkePlatformVectorsTest`, `IosGcmTest`, `IosP256Test`; note the job time (GHASH is bit-serial: a 1 MB input is slow). (2) Owner's Mac: run the same on a **real iPhone** (Secure Enclave and Keychain differ from the simulator): generate, agree, from-scalar, GCM 4 kB. (3) Time `aesGcmSeal` of a 2 MB buffer on the phone (expected: hundreds of milliseconds with the bit-serial GHASH; a 4-bit table variant is the planned fix if it matters). (4) Embed one `dpx-vectors.json` row in a common test so the simulator proves the envelope too (gap noted in docs/ops/ios-crypto-notes.md §5.4). (5) The iPhone Drive **screens, sign-in (`ASWebAuthenticationSession`), Keychain `WhenPasscodeSetThisDeviceOnly` items and `LAContext` check are not built on this branch**: nothing to test yet beyond the provider; the `:ui` Drive screens compile for iOS (`:ui:compileKotlinIosSimulatorArm64` green on Linux).
- **Expected:** every vector green on Apple's own primitives; a measured GHASH time recorded.
- **Automated or reviewed by:** Linux: `:shared:compileKotlinIosSimulatorArm64` and `:ui:compileKotlinIosSimulatorArm64` (reviewer ran: yes, green); `IosGcmTest`/`IosP256Test` on the JVM (yes, green; plus the lead's 2092-scalar and 852-shape differential runs). On a simulator or iPhone: no (no macOS here).
- Pass / Fail / Date: ____  by: ____

#### MT-74 Secrets never leave the phone's memory: logcat, files, Android backup
- **Area:** Android app. **Who:** owner. **Env:** E4 connected, `adb` at hand.
- **Steps:** (1) During MT-52, MT-56, MT-59 and MT-61 keep `adb logcat` running: no recovery key, no `dp1.` secret, no access token (`ya29.`), no HMAC proof in the log. (2) `run-as app.doorprints find . -type f` then `grep -l ya29` and the recovery key: nothing in files; `no_backup/drive/` holds `prefs.json`, `lock.json`, `state.json`, the watermarks, `deletion-*.json` and (API 26-30) `drive-device-key.bin` only. (3) `adb shell bmgr backupnow app.doorprints` (or a cloud backup to a second phone): the second phone must **not** come up as an enrolled device (the pin and the device id are in `noBackupFilesDir`). (4) Share the app's exported bug report / `Save a copy`: no Drive secrets inside.
- **Expected:** tokens in memory only; pins and keys per device; nothing in Android's own backup.
- **Automated or reviewed by:** `AndroidDriveTokenProviderTest` (`theTokenNeverAppearsInResultsOrErrors`), `DriveStatesTest`, `AtomicJsonFileTest`, `ConnectModels` `toString`s (`ConnectResult`, `RevokeDone`, `ShownKey`, `NewcomerOffer` print "shown once"/"secret"), code reading of every `toString`. Reviewer ran it: yes (JVM); phone: no.
- Pass / Fail / Date: ____  by: ____

### 14.1 Summary rows (add to §13 when the owner starts the run)

| Id | Title | Who | Env | Reviewer ran it | Result |
|---|---|---|---|---|---|
| MT-52 | First connect, consent `drive.file`, recovery key once | owner | E4 | JVM only | |
| MT-53 | No screen lock; granular consent refused | owner | E4 | JVM only | |
| MT-54 | Back up now, list, Import a backup | owner | E4 | JVM only | |
| MT-55 | Sync two phones + website | owner | E4 x2/E1 | JVM only | |
| MT-56 | Enrol a phone by `dp1.` paste + 8-digit check | owner | E4 x2 | JVM + probes + mutations | |
| MT-57 | Website enrols a phone and the reverse | owner | E4 + E1 | JVM only | |
| MT-58 | Join with the recovery key | owner | E4 | JVM only | |
| MT-59 | Revoke; new recovery key once | owner | E4 x2 | JVM + probe | |
| MT-60 | Deletion L1 | owner | E4 | JVM only | |
| MT-61 | Deletion L2 with the device check | owner | E4 | JVM + probes + mutations | |
| MT-62 | Delete this backup on a row | owner | E4 | JVM (screen state) | |
| MT-63 | Deletion L3, countdown, airplane mode, lock removed | owner | E4 | JVM + probes + mutation | |
| MT-64 | Lock removed: pause, notice, re-enrol | owner | E4 | JVM only | |
| MT-65 | Background worker | owner | E4 | JVM only | |
| MT-66 | Photos Wi-Fi/mobile/Data Saver | owner | E4 | JVM only | |
| MT-67 | Folder deleted elsewhere (Start again / Disconnect) | owner | E4 | JVM probe and fix tests | |
| MT-68 | Hostile files in Drive | owner | E4 | JVM + probes | |
| MT-69 | Disconnect, disconnect all, server hand-back | owner | E4 (+E8) | JVM + probe | |
| MT-70 | Android 8-10 / 11 / 12+ key and check paths | owner | E4 x3 | JVM fakes only | |
| MT-71 | Four languages, dark mode | owner, natives | E4 | screenshots | |
| MT-72 | TalkBack, 200 % text | owner/QA | E4 | no | |
| MT-73 | iPhone crypto on CI macOS and a Mac | CI/owner | E5/Mac | Linux compile only | |
| MT-74 | No secrets in logcat, files, Android backup | owner | E4 | JVM only | |

## 15. The path trace, version 2: repeats, the alert, saved walks, the website (planned)

Added 2026-10-06 with the design ([11](../11-feature-parity-and-export-spec.md) 5.27.0..5.27.11, S4b-FR-13..S4b-FR-18). **Not built yet:** run these after the tickets are merged. Needs a **real phone** for the sound, the pocket and the system's notification settings, and a real browser for the page-visibility and wake-lock behaviour. Before them, make a *Save a copy* Full backup of every device you test with. A convenient way to walk "the same street twice" without a long walk: a street of 300 m near you, walked twice (an hour apart, or with *Finish walk* between), and a second street that crosses it.

#### MT-75 Repeats stand out: Clear, Subtle, Off, applied at once (TC-M-57)
- **Area:** Android app, iPhone app, website. **Who:** owner. **Env:** E4, E5, E1.
- **Steps:** (1) Settings > Hunt mode (website: Map page > *Trace my path*): turn the trace on; *How repeated paths look* shows *Clear* selected. (2) Walk the street, *Finish walk* (*Keep for 30 days*), walk it again: the second pass is thicker, dashed and orange over the purple line; the street that only crosses is not marked. (3) With the walk running, choose *Subtle*, then *Off*, then *Clear*: the map changes at once, without stopping Hunt mode or the walk. (4) Light and dark theme, zoom 12, 15, 18, over roads, India's boundary and the state lines. (5) Screenshot through a colour-vision simulator (protanopia, deuteranopia, tritanopia): the dash still shows the repeat. (6) The legend row shows *Walked once* and *Walked more than once*.
- **Also:** walk two parallel lanes about 30 m apart: they must not light up as repeats (if they do, `TOLERANCE_M = 20`, 11 5.27.11 question 11); under a protanopia simulation the orange line and an amber or star marker are near-identical in colour and must be told apart by form.
- **Expected:** the repeat is never marked by colour alone; *Off* draws it like any path; the choice survives a restart; the orange is none of the base map's or the markers' colours.
- **Automated or reviewed by:** TC-U-147 (vectors), TC-U-149 (`TrackStyleTest`, `MapScreenLookTest`, screenshots); reviewer ran it: no (planned).
- Pass / Fail / Date: ____  by: ____

#### MT-76 The alert rings once, from a pocket, and the system can mute it (TC-M-59)
- **Area:** Android app, iPhone app. **Who:** owner. **Env:** E4, E5.
- **Steps:** (1) The alert switch is off by default; turn it on: the notification permission is asked with its reason; refuse it once: the switch goes back off and says why. (2) Allow it; walk a street you have walked before with the phone in a pocket, screen off: one short sound after about 100 m; walking on does not ring again; ring again only after leaving the paths and returning more than 10 minutes later. (3) Mute the *Repeated path* channel (Android: Settings > Apps > Doorprints > Notifications; iPhone: Settings > Notifications > Doorprints): silence. (4) *How repeated paths look* = *Off*: the alert still rings. (5) Do Not Disturb or Focus on: as for any notification. (6) App lock on: the lock screen shows no detail.
- **Expected:** one alert per run and per 10 minutes; the system controls the sound; nothing rings with the alert off.
- **Automated or reviewed by:** TC-U-148 (the vector file's alert cases through the engine, `alertRingsWithLookOff`); reviewer ran it: no (planned).
- Pass / Fail / Date: ____  by: ____

#### MT-77 Finish a walk, save it to a house, and the Undo window (TC-M-58)
- **Area:** Android app, iPhone app, website. **Who:** owner. **Env:** E4, E5, E1.
- **Steps:** (1) Walk past a saved house for 10 minutes, *Finish walk*: *Save this walk?* with the distance and minutes; the nearest house is preselected when within the alert radius. *Save with a house*: the walk is on the house page (*Show on map* fits the Map to it) and on the Map. (2) Another walk, dismiss the sheet: *Keep for 30 days*. (3) Another, *Delete this walk*: confirms first. (4) Stop Hunt mode from the notification, close the app, open the Map: the sheet for that walk appears, once. (5) Delete a house with a saved walk and let the Undo bar close: the walk is gone from the Map and the house list; delete another house and tap *Undo*: the walk is back. (6) *Delete all saved walks*. (7) A house with 20 saved walks refuses a 21st with the message.
- **Expected:** a saved walk is the house's and never outlives it; the default is to keep 30 days; nothing is asked twice.
- **Automated or reviewed by:** TC-U-150 (`SavedWalkDaoTest`, `WalkSweeperTest`, `WalkHousePicker`); reviewer ran it: no (planned).
- Pass / Fail / Date: ____  by: ____

#### MT-78 No walk in any copy, backup or sync (TC-M-58 step 6, PRV-028)
- **Area:** all. **Who:** owner. **Env:** E4, E5, E1 (+ E8 for the server).
- **Steps:** (1) With a trace and two saved walks, *Save a copy* (every format and the Full backup ZIP), *Share updates*, the Google Drive backup and sync (MT-54, MT-55) and, if used, the self-hosted server: open each file or, for the server, `GET /api/export`; search for a coordinate of the walks (the first digits of a latitude on the walk) in each. (2) On a phone, ask the assistant a question about a house with a saved walk: the prompt (logcat or the web request in DevTools) holds no coordinate of the walk. (3) Import the Full backup on a second phone: no saved walks, no trace. (4) **Android only:** transfer to another Android phone by cable or at setup: the saved walks and the trace arrive with the database (they are the phone's own copy; the hint under *Delete all saved walks* says so); delete them before the transfer to leave them behind.
- **Expected:** none anywhere; a phone set up from a backup, a copy, Drive or the server starts without saved walks, and Android's own phone-to-phone transfer is the one exception (the guide says so).
- **Automated or reviewed by:** TC-U-151, TC-U-152 (marker coordinates and the source tests); reviewer ran it: no (planned).
- Pass / Fail / Date: ____  by: ____

#### MT-79 A walk on the website: only while the page is open and visible (TC-M-60)
- **Area:** Web UI. **Who:** anyone. **Env:** E4 browser, E5 Safari and the installed website, E1.
- **Steps:** (1) Turn the trace on: no location prompt yet. *Start a walk*: the sentence about the permission, then the browser's prompt; deny it: the blocked message; allow it in the site settings and try again. (2) Walk 15 minutes with the page open: the line grows. (3) Lock the screen for two minutes, unlock: *Recording paused while this page was hidden.*, then it resumes; with the page hidden for more than 5 minutes no straight line is drawn across the gap; the page says plainly that the browser records only while the page is open and visible. (4) With *Keep the screen on while I walk* on: the screen stays on (iPhone Safari: where unsupported the setting says so). (5) *Finish walk*, save it to a house. (6) A second walk of the same street: the repeat style, the banner *You have walked this way before.* and a beep (after the *Start a walk* tap; none with the iPhone's silent switch on, the banner still shows). (7) *Remove all Doorprints data from this browser*: DevTools > Application > IndexedDB `doorprints`: `trace_points` and `saved_walks` are empty.
- **Expected:** nothing asks for the location on load; nothing is recorded in the background; no walk in any file.
- **Automated or reviewed by:** TC-U-152 (`TraceRecorderService`, `local-db.spec.ts`); reviewer ran it: no (planned).
- Pass / Fail / Date: ____  by: ____

#### MT-80 Hindi, Tamil and Telugu, large text, TalkBack, VoiceOver for the new strings
- **Area:** all. **Who:** owner, native readers. **Env:** E4, E5, E1.
- **Steps:** (1) The *Trace my path* settings, the *Save this walk?* sheet, the house picker, the *Saved walks* card and the banner in hi, ta and te (marked *under review*): nothing clipped at 200 % text; the *Clear / Subtle / Off* group is read as a group with the selected one; the sheet's default button is announced; the alert banner is announced on arrival (`role="alert"`). (2) A native reader reads the strings of 5.27.9 and marks any that is wrong in [10](../10-sprint-log.md).
- **Expected:** every string translated or shown in English with the *under review* mark; no hard-coded text.
- **Automated or reviewed by:** TC-U-149 screenshots in four languages and two themes, the web a11y sweep; reviewer ran it: no (planned).
- Pass / Fail / Date: ____  by: ____

#### MT-81 *Have I been here?* compares a place with your walks, on demand, and keeps nothing (TC-M-61)
- **Area:** Android app, iPhone app, website. **Who:** owner. **Env:** E4, E5, E1.
- **Steps:** (1) Have two walks of one street on different days and one saved walk (MT-75, MT-77). Stand on the street, Map > *Have I been here?* > *Where I am now*: the location permission is asked **now** and not earlier; *Finding your location...*; the sentence names the days, newest first, and a distance (*You walked within 12 m of here on ...*); the map shows a halo on the matched stretch and a hollow ring with a cross at the place; TalkBack or VoiceOver reads the sentence on arrival. (2) Walk 100 m away and check again: *No walk of yours passed within 25 m of here in the last 30 days or in your saved walks.* (3) Indoors, or with Precise location off: *Location not precise enough. Try again outdoors.* (4) A house you passed: *Did I walk past this house?* answers with the walks; a house you never passed: the none sentence; a house with an approximate location: *This house has no exact spot yet...*; a spot 35 m from a walk (long press on the map, or *A spot on the map* with the crosshair and *Check this spot*): *No walk of yours passed within 25 m ... but one came within 35 m*. (5) Turn *Trace my path* off: the check still reads what is stored; *Clear the path* and *Delete all saved walks*: the empty words. (6) Airplane mode: a house and a spot still answer; on the website the browser's network panel shows **no request** when the button is pressed. (7) Close the sheet and reopen the app: no result is kept and there is no list of past checks; a *Save a copy* made afterwards has no coordinate of a walk. (8) Website: from the current location only while the page is open; the denied message in the browser's site settings. (9) hi, ta, te (under review), 200 % text, both themes.
- **Expected:** it never runs by itself; the answer is words first; *close* is never worded as *walked*; nothing is stored, logged or sent.
- **Automated or reviewed by:** TC-U-153 (the vectors), TC-U-154 (gate, words, privacy source tests, screenshots); reviewer ran it: no (planned).
- Pass / Fail / Date: ____  by: ____

### 15.1 Summary rows (add to §13 when the owner starts the run)

| Id | Title | Who | Env | Reviewer ran it | Result |
|---|---|---|---|---|---|
| MT-75 | Repeats: Clear, Subtle, Off, live | owner | E4/E5/E1 | no (planned) | |
| MT-76 | The alert: once, from a pocket, muted by the system | owner | E4/E5 | no (planned) | |
| MT-77 | Finish a walk, save to a house, Undo | owner | E4/E5/E1 | no (planned) | |
| MT-78 | No walk in any copy, backup, sync or AI request | owner | all | no (planned) | |
| MT-79 | A walk on the website: visible page only | anyone | E4/E5/E1 | no (planned) | |
| MT-80 | hi, ta, te, large text, screen readers | owner, natives | E4/E5/E1 | no (planned) | |
| MT-81 | Have I been here? (on-demand place check) | owner | E4/E5/E1 | no (planned) | |
