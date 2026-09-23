# 12: Brand and naming

| Field | Value |
|---|---|
| Document | Brand and naming: the web address, the app icon, fallbacks, brand screening, custom-domain policy, naming guidelines and the import / backup / copy vocabulary |
| Version | 0.4 |
| Date | 2026-09-24 |
| Author | Claude (Cowork), Docs team, from the brand advisor's naming brief of 2026-09-23 |
| Status | Draft. The address (section A) and the import vocabulary (section G) are **decided** by the owner; the hi/ta/te wording in section G is a **first draft pending native-speaker review** (I18N-B06) |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-23 | Claude (Cowork), Docs team | First version, from the brand advisor's naming brief (2026-09-23) and the owner's decisions of the same day: the live web address is **`https://doorprints.web.app`** (Firebase Hosting, Spark plan; [03](03-design.md) ADR-21), a custom domain comes **later, and only after web import ships**, "import" is reserved for Doorprints backups, and the import product definition is approved ([01](01-requirements.md) §6.9, [schemas](schemas/README.md) §0). Sections A–G: the address as a brand touchpoint, site ID and fallbacks, brand screening, custom-domain policy, hosts compared, standing naming guidelines, and the import / backup / copy vocabulary in four languages. |
| 0.2 | 2026-09-23 | Claude (Cowork), Docs team | Android §9 item 20 applied (coordinator's final review of 2026-09-23): **G.3 rule 3** now records that Android renamed *Restore from a backup* (`houses_restore`, deleted) to **Import a backup** (`import_title`) in Sprint 4a, `android/shared/README.md` 1.15, not in Sprint 4b. **G.1**: the web's Tamil `data.formatBackup` already uses the joined காப்புப்பிரதி, so that Sprint 4b item is done. |
| 0.3 | 2026-09-23 | Claude (Cowork), Docs team | New decision **N-06, the app icon as a brand touchpoint** (Docs pre-review buddy): the owner asked for the web icons to be redrawn before the first deploy, and in the final Sprint 4a round the Web team redrew `favicon.svg`, `icon-192.png`, `icon-512.png`, `icon-maskable-512.png` and `apple-touch-icon.png` from the Android launcher mark (`ic_launcher.xml`). Both platforms now show the same mark; the 16 px favicon leaves the toes out. Part of the first release's Definition of Done ([10](10-sprint-log.md) §12.5 Decision 4). |
| 0.4 | 2026-09-24 | Claude (Code), lead | **N-06, the icon's footprints, option C** ([14](14-lead-backlog-and-handoff.md) N3): three small footprints (sole, heel, four toes) walking up beside the door, left, right, left, in place of two large gold ovals, on Android (`ic_launcher.xml`, and the one print of `ic_stat_doorprints.xml`) and on the web (`favicon.svg` and every app-icon PNG). Colours, the door and each icon's layout are unchanged. The favicon keeps the same prints (owner's choice; it no longer leaves the toes out). |

Related: [Design, ADR-21](03-design.md) · [Build and deploy §6.3](07-secure-build-and-deploy.md#63-web-firebase-hosting) · [UX, a11y and i18n](05-ux-accessibility-i18n.md) · [Requirements §6.9](01-requirements.md) · [Backup format](schemas/README.md) · [Sprint log §11.6](10-sprint-log.md)

---

## Decisions in one place

| # | Decision | Status |
|---|---|---|
| N-01 | The web app lives at **`https://doorprints.web.app`**: Firebase Hosting, no-cost Spark plan with no billing account, project ID `doorprints`, site ID `doorprints` | **Decided** by the owner on 2026-09-23 with the brand advisor; the owner's console setup is done ([07](07-secure-build-and-deploy.md) §6.3) |
| N-02 | The twin address `https://doorprints.firebaseapp.com` serves the same site but is **never shared** | Decided |
| N-03 | **No custom domain now.** If one is ever bought (`doorprints.in` preferred), it is bought **before** a public launch and the app moves there **only after web import ships** (Sprint 4b, story S4b-00 and the web import) | Decided (default: never, unless the audience grows beyond family and friends) |
| N-04 | "Import" means only bringing a **Doorprints Full backup** in. Everything else that comes in is *add* or *fill in*; everything that goes out is a *copy* or the backup itself | Decided and approved with the import definition ([01](01-requirements.md) FR-089..FR-097) |
| N-05 | Four user-facing names: **Import a backup**, **Full backup**, **Readable copy**, **Add a shared listing** | Decided; hi/ta/te wording pending native review |
| N-06 | **One app icon on every platform: the door-and-footprints mark.** A white arched front door with its doorstep and knob, and three small gold footprints (#F2B84B) walking up beside it, left, right, left, each a sole, a heel and four toes (**option C**, the owner's choice of 2026-09-24, [14](14-lead-backlog-and-handoff.md) N3; the owner also chose left/right/left over the right/left/right first written there, so that the big toes face each other as in a real walk), on the brand teal #1F6F5C. The source is the Android launcher icon, `android/app/src/main/res/drawable/ic_launcher.xml`. The web uses the same mark: `web/public/favicon.svg` (rounded square); `web/public/icons/icon-192.png` and `icon-512.png` (rounded squares with transparent corners); `icon-maskable-512.png` (full bleed, the whole mark inside the 80 % safe circle); and `apple-touch-icon.png` (180 × 180, full bleed, no transparency, because iOS rounds the corners). **The favicon is the same mark** with the same three prints, drawn a little larger (option C; the owner chose one mark everywhere, accepting that at 16 × 16 the toes do not show). The Android status-bar icon (`ic_stat_doorprints.xml`, a white alpha mask) has the door and one print of the same shape. The manifest's `theme_color` and `background_color` are the same teal. The PWA shortcut icons are white glyphs on the same teal, not the mark. A new icon, or a change to the mark, changes `ic_launcher.xml` and the five web files together | **Decided**: the owner asked for the web icons to match before the first deploy (first-release Definition of Done, [10](10-sprint-log.md) §12.5 Decision 4). The web redraw was done in the final Sprint 4a round (`web/README.md`, *Installable (PWA)*) |

---

## A. The address as a brand touchpoint

What matters most to an Indian house-hunter who gets the link from a family member on WhatsApp is: does it look
real and safe, can it be said on a phone call, and can it be typed on a phone.

| Ending | Real app or test site? | Saying it aloud (hi / ta / te conversation) | Typing on a phone | Fit with name and tagline | Verdict |
|---|---|---|---|---|---|
| **`doorprints.web.app`** | Reads as a real product. "web app" is plain English that describes the thing; no dev, test or staging word | "doorprints dot web dot app": "web" and "app" are everyday loanwords in Hindi, Tamil and Telugu speech, so nothing needs translating. Four short parts | 18 characters, lowercase letters and dots only | Strong: "Doorprints, the web app" repeats the brand and the category and does not compete with the tagline | **Chosen** |
| `doorprints.firebaseapp.com` | Feels like a technical address; "Firebase" means nothing to most users, and "fire" is an odd word next to "house" | Long, and invites "what is firebase?" | 26 characters | Weak: draws attention to the supplier | Served by Firebase, **never shared** (N-02) |
| `doorprints.netlify.app` | Acceptable | People will say "netflix" by mistake | 22 characters | Neutral | Runner-up (section E) |
| `doorprints.vercel.app` | Acceptable, but "vercel" is hard to spell by ear | Needs spelling out on a call | 21 characters | Neutral | Third |
| `doorprints.pages.dev` | **Rejected by the owner** on 2026-09-23: "dev" reads as "developer version" to anyone who has worked in IT, which is many of the families using this app | "dot pages dot dev" | 20 characters | Undercuts trust | No |
| `sriram-codes-sw.github.io/doorprints/` | Reads as a personal code page, not an app | Long; a path to dictate | 37 characters | Weak | **Rejected** earlier for security reasons: no response headers and an origin shared with the owner's other sites ([03](03-design.md) ADR-21) |
| `doorprints.in` (custom) | Strongest trust signal for India: looks like an Indian business | "doorprints dot in": shortest | 13 characters | Best possible | Costs money every year: section D |
| `doorprints.app` (custom) | Very strong and modern | "doorprints dot app" | 14 characters | Excellent | Costs more to renew: section D |

**The history of the decision** (all on 2026-09-23; the technical record is [03](03-design.md) ADR-21):

1. **GitHub Pages** (`sriram-codes-sw.github.io/doorprints/`) was switched on in Sprint 4a and rejected before
   anything was published: it cannot send the app's security headers, and its origin is shared with every other
   Pages site of the owner's account ([02](02-threat-model.md) F-31, RR-11).
2. **Cloudflare Pages** was chosen at 07:30 IST to fix both, and rejected by the owner later that morning,
   before any account, token or deploy existed: the `*.pages.dev` ending reads as a development or test address.
3. **Firebase Hosting** at `doorprints.web.app` was chosen with the brand advisor. It fixes the same two security
   problems (headers from `web/firebase.json`, an origin of its own) and its address reads as a product.

**Notes on the name when said aloud.** "Door" sounds like Hindi दूर (far), Tamil தூரம் and Telugu దూరం (distance).
This does no harm, but on a phone call people may spell it "dur-" or "dhoor-". Two cheap defences: share the link
as text, not by voice; and, optionally, claim the singular `doorprint` as a second free site that only redirects
to the main address (section B).

**WhatsApp sharing.** WhatsApp turns any `https://` address into a link with a preview card showing the page title
and the meta description, which is already the tagline in the reader's language: "Doorprints — Remember every
house you've seen." Keep the `<title>` and Open Graph tags aligned with it. **Always share the full
`https://doorprints.web.app` form**: the bare form without `https://` is still linked by WhatsApp, but some older
Android browsers may not treat it as a web address.

## B. Site ID and fallbacks

Firebase site IDs are globally unique within Firebase, must be a valid hostname label (no `.` or `_`) and at most
30 characters. The first site of a project gets the **project ID** as its name and can never be deleted; a project
can have up to 36 sites ([Firebase multisite docs](https://firebase.google.com/docs/hosting/multisites)).

**Outcome (2026-09-23):** the owner created the Firebase project with project ID **`doorprints`**, so its default
site is **`doorprints`** and the address is `https://doorprints.web.app`. None of the fallbacks below was needed.
They stay on record in case a second site is ever needed, and as the order to use for social handles (section F).

| Rank | Site ID | Address | Why |
|---|---|---|---|
| Primary | `doorprints` | `doorprints.web.app` | The brand, nothing else. **In use** |
| 1 | `doorprintsapp` | `doorprintsapp.web.app` | Brand plus category, joined; reads cleanly, no hyphen to say |
| 2 | `getdoorprints` | `getdoorprints.web.app` | A common, trusted pattern for product sites ("get the app") |
| 3 | `mydoorprints` | `mydoorprints.web.app` | Personal, fits a private app; weaker, because "my" prefixes look like user accounts |

**Never** use an ID containing `dev`, `test`, `uat`, `staging`, `beta`, `demo`, `preview`, `prod`, `sandbox`,
`-v2`, `-1234`, or the old name `house-hunt` / `househunt`.

**Defensive redirect site (optional, not done).** A second free site `doorprint` (singular) in the same project,
whose only rule redirects every path to `https://doorprints.web.app`, would catch the common misspelling. It would
need its own `hosting` entry, and `web.yml`'s configuration gate today allows exactly one `hosting` object, so this
is a DevSecOps change as well as an owner step. Not scheduled.

**Availability snapshot** (the brief's checks, 2026-09-23, before the project was created): `doorprints.web.app`,
`doorprints.firebaseapp.com`, `doorprint.web.app`, `doorprintsapp.web.app`, `getdoorprints.web.app`,
`mydoorprints.web.app`, `doorprints.netlify.app` and `doorprints.vercel.app` all answered HTTP 404. A 404 is not
proof that a name is free; only creating the site is.

## C. Brand screening: "Doorprints" / "Door Prints"

| Search | What came up | Conflict? |
|---|---|---|
| "Doorprints" app | Only the owner's own GitHub repository ranks for the exact name; app-store results were other names | No |
| "Doorprints" trademark | No record for DOORPRINTS or DOOR PRINTS in the results seen (US listings showed other "Door…" marks only) | None found |
| "Door Prints" real estate | A generic phrase for printed door designs (craft and print galleries) | No: descriptive use in another trade |
| "doorprints" India | Sellers of "digital door prints" (printed door laminates) | No: a generic product term in a different trade |
| Facebook | A page "DoorPrints" in Dumaguete City, Philippines, apparently a design and print business; the page could not be opened by the research tools | Low: different country, different trade |
| Google Play / App Store | Both refused automated reading; web search showed no Play listing named Doorprints | Not verified directly |

**Risk: LOW** for a free personal app; **LOW to MODERATE** before any commercial use or Play Store listing, only
because the Indian trademark register could not be searched with the tools available. "Door prints" is a
descriptive phrase in the printing trade, which makes the word weak to defend as a mark but also unlikely to be
blocked.

**Before a Play Store listing or any public launch**, the owner (or a lawyer) runs the free public search of the IP
India trademark registry for "DOORPRINTS" and "DOOR PRINTS" in class 9 (software), class 42 (software as a
service) and class 36 (real-estate services), and searches Google Play directly on a phone.

## D. Custom domain policy: later, and only after web import ships

**Registration snapshot** (registries' public RDAP lookups, 2026-09-23; a snapshot, not a hold):

| Domain | Status |
|---|---|
| `doorprints.com` | **Taken**, held by a domain reseller (registered 2013-01-20, expires 2027-01-20, "for sale" name servers). Not worth a premium for an India-only product |
| `doorprints.app` | Appears unregistered (Google Registry RDAP: not found) |
| `doorprints.in` | Appears unregistered (NIXI RDAP: not found) |
| `doorprints.co.in` | Appears unregistered (NIXI RDAP: not found) |

**Cost** (published prices, 2026-09-23): `.in` from about US$5 to register and US$5.4–6.5 a year to renew at
low-cost registrars (Indian resellers list ₹499–899); `.app` about US$5 the first year but US$14–15 a year to renew,
and HTTPS only. Serving a custom domain is free on Firebase Spark.

**Why the timing matters more than the price.** Since Sprint 4a the web app keeps a user's houses and photos in the
browser (IndexedDB), and browsers tie that storage to the exact address. If the app moved from
`doorprints.web.app` to `doorprints.in`, every user's houses would stay behind at the old address. The web app
cannot read a backup back in until the Sprint 4b web import ships ([05](05-ux-accessibility-i18n.md) UX-B07).

**Policy:**

1. **Default: never.** For a personal, zero-cost app shared with family and friends, `doorprints.web.app` is enough
   and costs nothing. It cannot lapse while the Firebase project exists.
2. **If the owner decides to publish to the Play Store or promote the web app publicly**, buy **`doorprints.in`**
   (not `.app`: cheaper to renew, and it signals India), **before** that launch.
3. **Move the web app only after web import ships** (Sprint 4b: the web can import a Full backup, FR-089..FR-097),
   in one announced release: users save a Full backup on the old address and import it on the new one. The move
   also needs `APP_CORS_ORIGINS` updated on every server ([07](07-secure-build-and-deploy.md) §7), the post-deploy
   HSTS check made a failure again ([07](07-secure-build-and-deploy.md) §6.3), and a Google OAuth authorised
   domain if Sprint 5 sign-in exists by then ([11](11-feature-parity-and-export-spec.md) RK-06).
4. A custom domain is a **lifelong obligation**: if a renewal is missed, someone else can buy the name and serve
   their own code where users' data sits. Put the renewal on auto-renew and in the runbook's quarterly check
   ([08](08-operations-runbook.md) §4).
5. Registering `doorprints.in` now only to hold it (about ₹500 a year) is reasonable insurance but optional; it is
   not done today, to keep the zero-cost rule (CON-01).
6. Do not chase `doorprints.com`.

## E. Hosts compared

| Host and address | Cost and limits | Verdict |
|---|---|---|
| **Firebase Hosting, Spark**, `doorprints.web.app` | No card, no billing account. 10 GB storage; transfer 360 MB/day on the pricing page, 10 GB/month on the quota page (plan for the stricter one); over the limit the site is **disabled** until the period resets. `firebase.json` sends real security headers | **Chosen** (N-01). Watch Hosting > Usage monthly ([08](08-operations-runbook.md) §2, §4) |
| Netlify Free, `doorprints.netlify.app` | Hard limit of 300 credits a month; a production deploy costs 15; when credits run out **all** the account's sites are paused | Runner-up. Fine only if deploys were rare and batched |
| Vercel Hobby, `doorprints.vercel.app` | Non-commercial personal use only; any ad, paid feature or paid help ends it | Not recommended; the name is also hard to say |
| Cloudflare Pages, `doorprints.pages.dev` | Unmetered static bandwidth; `_headers` supported | Rejected for the address. It becomes the natural host **with** a custom domain, and is the contingency if Spark's transfer limit ever becomes a problem ([08](08-operations-runbook.md) §7 IR-5) |
| GitHub Pages | Free | Rejected: no response headers, shared origin ([02](02-threat-model.md) F-31) |

## F. Standing naming guidelines for Doorprints

### F.1 Principles

1. **The brand is one word, "Doorprints", capital D, never translated or transliterated.** In every language and
   script it stays in Latin letters; suffixes attach to it ("Doorprints-க்கு"). ([05](05-ux-accessibility-i18n.md)
   §9.3 and the glossary row `app.name`.)
2. **Lowercase `doorprints` in addresses and IDs** (web addresses, site IDs, handles, package IDs): no hyphen, no
   "door-prints", no capital letters inside addresses. **File names are the exception:** people read them, so they
   start with the brand as it is written, `Doorprints-` (G.3 rule 4).
3. **Nothing public may sound unfinished.** Never use dev, test, uat, qa, staging, beta, demo, preview, sandbox,
   temp, old, new, v2, final or numbers in anything a user sees.
4. **Plain English words users already know** (web, app, backup, map, hunt) beat clever or supplier words
   (firebase, netlify, vercel, pwa).
5. **Say it, then type it.** Before approving a name, say it aloud in a Hindi, Tamil or Telugu sentence and type it
   on a phone keyboard. If either needs spelling out, choose again.
6. **One name per thing, forever.** Renaming costs user trust and, for the web app, user data. Decide once.
7. **Internal names may keep history.** Code packages (`com.househunt`), storage keys and database names keep the
   old name on purpose ([03](03-design.md) ADR-13), as long as users never see them.

### F.2 Do and don't

| Area | Do | Don't |
|---|---|---|
| Web address | `https://doorprints.web.app` (or `doorprints.in` if ever bought, section D). Always share it with `https://` | `doorprints.pages.dev`, `doorprints-test…`, the `firebaseapp.com` twin, GitHub Pages addresses |
| Subdomains or extra sites (if a custom domain is bought) | Plain English nouns: `help.`, `status.`, `api.` | `dev.`, `beta.`, `app2.`, `new.` |
| Non-public test sites | Non-public and outside the brand: a separate Firebase project with a neutral ID, linked from nowhere. Hosting preview channels are **not** used ([07](07-secure-build-and-deploy.md) §6.3): each is a public address | Preview links sent to real users |
| Store listing name | **"Doorprints"**. If Play requires more words: "Doorprints: House Hunt Diary" or "Doorprints – Remember every house" (listing only) | "Doorprints Lite", "Doorprints Beta", "Doorprints India", keyword stuffing ("Rent Flat PG House Finder") |
| Store short description | The tagline in each language, from the glossary `app.tagline` | New wording that drifts from the tagline |
| Package / bundle IDs for new apps | The existing Android app keeps `app.doorprints` (changing it makes a new app). New apps: `in.doorprints.<app>` only if `doorprints.in` is owned; otherwise `io.github.sriramcodessw.doorprints.<app>` | IDs under a domain the owner does not control, such as `com.doorprints.*` (the `.com` is a reseller's) |
| Feature names | Capitalised two-word names with an everyday noun: **Hunt mode**, **Street memory**, **Stay detection**. The feature name is translated (unlike the brand), with the glossary as the single source | Brand-prefixed names ("DoorHunt", "PrintMode"), jargon ("geofence alerts"), puns that do not survive translation |
| Export and backup file names | See G.3 rule 4: `Doorprints-backup-YYYY-MM-DD.zip` (or `.json`) and `Doorprints-copy-YYYY-MM-DD.<ext>`; brand as written, ISO date (UTC), English on every language setting so files sort and match across devices | Spaces, translated file names, local date formats (`23-09-2026`), a name without `backup` or `copy`, `house-hunt-…` |
| Email sender (if ever needed) | Display name "Doorprints"; `hello@` or `noreply@` on a domain the owner owns | A personal Gmail showing as "Doorprints", `test@`, supplier default senders (`noreply@<project-id>.firebaseapp.com`) shown to users |
| Page titles and share cards | "Page · Doorprints" (brand last), meta description = translated tagline, one Open Graph image with the door-and-footprints icon | The brand translated in the title, empty preview cards |
| Social handles (if ever) | `@doorprints`; if taken, `@doorprintsapp` (the order of section B) | Different handles on each network |

### F.3 How to ask for a new name

Send the brand advisor the candidate, where users will see it, and in which languages. Expect back a say-aloud and
type test, a conflict check with sources, and one recommendation with a runner-up. Record the outcome here.

## G. Import, backup, copies and share-in (decided 2026-09-23)

Context: the owner approved `https://doorprints.web.app`, deferred a custom domain, put web import in Sprint 4b
before any address move, and approved the import definition: **only a Doorprints backup can be imported**
([01](01-requirements.md) §6.9, [schemas](schemas/README.md) §0). This section fixes the words for it.

**The rule behind every name below: one verb per direction.**

- **Import** brings a backup **in**. Nothing else is ever called import.
- **Save a copy** (Android *Save a copy*, web *Your data*) sends data **out**. The output is either a *Full backup*
  or a *readable copy*.
- **Add** makes a **new house**: by hand, from a place on the map, or from something shared into the app.
- **Share** always means sending something **out** to another app.

### G.1 User-facing names

Wherever possible these reuse strings that already ship, so no translation is thrown away. **The hi, ta and te
columns are first drafts pending native-speaker review** ([05](05-ux-accessibility-i18n.md) I18N-B06).

| Thing | en | hi | ta | te | Where it already exists |
|---|---|---|---|---|---|
| Import action (button and screen title) | **Import a backup** | बैकअप आयात करें | காப்புப்பிரதியை இறக்குமதி செய் | బ్యాకప్ దిగుమతి చేయండి | Android `import_title`. The web adopts it with its import (Sprint 4b) |
| The file you import (ZIP, or a bare `data.json`) | **Full backup** | पूरा बैकअप | முழு காப்புப்பிரதி | పూర్తి బ్యాకప్ | Web `data.formatBackup` ("Full backup (ZIP)") |
| The read-only exports (HTML, PDF, CSV, XLSX, Markdown) | **Readable copy** (plural *copies*); action *Save a copy* | पढ़ने योग्य प्रति | படிக்கக்கூடிய நகல் | చదవగలిగే కాపీ | *copy* / प्रति / நகல் / కాపీ from Android `export_title`, `import_intro` and web `data.contactsWarning` |
| Share-in feature | **Add a shared listing** (in the system share sheet the target shows only the brand, **Doorprints**, with the icon) | शेयर की गई लिस्टिंग जोड़ें | பகிர்ந்த விளம்பரத்தைச் சேர் | షేర్ చేసిన లిస్టింగ్ జోడించండి | "listing" as in the web `listingFill.*` translations (लिस्टिंग / விளம்பரம் / లిస్టింగ్) |

- **Tamil spelling of "backup":** use the joined form **காப்புப்பிரதி** everywhere. Android `import_title` and
  `settings_server_intro` (since 1.15) have it, and so does the web's `data.formatBackup` ("முழு காப்புப்பிரதி (ZIP)",
  seen in `web/src/app/i18n/ta.ts` on 2026-09-23). On the native-speaker review list (I18N-B06).
- **The AI feature is not an import.** Its visible title was "Import from listing text"; the Web team renamed it
  **"Fill in from listing text"** and moved its keys from `import.*` to `listingFill.*` in all four languages on
  2026-09-23 (`web/README.md` change log), before backup import ships on the web.
- **"Share to Doorprints"** stays the team's internal feature name ([11](11-feature-parity-and-export-spec.md)
  §5.9); users see *Add a shared listing*.

### G.2 The import screen: what comes in, what is never touched

These two sentences go under the title, in this order. The first is the Android `import_lead` plus the no-delete
promise. **hi, ta and te are first drafts pending native-speaker review** (I18N-B06).

| | en | hi | ta | te |
|---|---|---|---|---|
| Brings in | Brings back houses, visits and photos from a Doorprints backup, and never deletes anything already here. | Doorprints बैकअप से मकान, दौरे और तस्वीरें वापस लाता है, और यहाँ पहले से मौजूद कुछ भी नहीं मिटाता। | Doorprints காப்புப்பிரதியிலிருந்து வீடுகள், வருகைகள், படங்களை மீட்டெடுக்கிறது; இங்கே ஏற்கெனவே உள்ள எதையும் நீக்காது. | Doorprints బ్యాకప్ నుండి ఇళ్లు, సందర్శనలు, ఫోటోలను తిరిగి తెస్తుంది; ఇక్కడ ఇప్పటికే ఉన్న దేనినీ తొలగించదు. |
| Never touches | Your settings, language, server address, API key, Hunt mode and AI settings stay exactly as they are. | आपकी सेटिंग्स, भाषा, सर्वर का पता, API कुंजी, हंट मोड और AI सेटिंग्स जैसी हैं वैसी ही रहती हैं। | உங்கள் அமைப்புகள், மொழி, சேவையக முகவரி, API விசை, தேடல் பயன்முறை, AI அமைப்புகள் எதுவும் மாறாது. | మీ సెట్టింగ్‌లు, భాష, సర్వర్ చిరునామా, API కీ, హంట్ మోడ్, AI సెట్టింగ్‌లు ఏమీ మారవు. |

Glossary terms used: visit (दौरा / வருகை / సందర్శన), API key (API कुंजी / API விசை / API కీ), settings (Android
`settings_title`), Hunt mode (Android `map_hunt_mode`). Contacts need no line here: the preview already lists what
the file holds. Neither sentence ships yet; they are for the Sprint 4b import screens on both platforms.

### G.3 Naming rules

1. **"Import" is reserved for Doorprints backups.** Other ways in are *add* (a new house) or *fill in* (a form from
   text). Never "import a listing", "import CSV" or "import from WhatsApp".
2. **"Backup" means only the file that can be imported.** Everything else that goes out is a *copy*. Never call a
   PDF or Excel file a backup, and never call the backup a "copy" or an "export".
3. **Never "restore" as a button label.** It suggests the phone will be put back to how it was, and an import never
   deletes. "Bring back" is fine in explanations and in the Android undelete's **Bring them back**
   ([05](05-ux-accessibility-i18n.md) §14.3). **Done on Android in Sprint 4a** (`android/shared/README.md` 1.15,
   §9 item 20): the empty house list's outlined button reads *Import a backup* (`import_title`, en/hi/ta/te) and
   `houses_restore` (*Restore from a backup*) is deleted; since 1.16 it sits under a filled *Add a house on the map*
   ([05](05-ux-accessibility-i18n.md) §14.10).
4. **Every exported file name says what it is:** `Doorprints-backup-YYYY-MM-DD.zip` (or `.json`) and
   `Doorprints-copy-YYYY-MM-DD.html` / `.pdf` / `.xlsx` / `.md` / `.zip` (CSV tables). The date is UTC, as today.
   State on 2026-09-23: the backups already follow it on both platforms; the web's HTML copy is
   `Doorprints-copy-<date>.html` (Web team, 2026-09-23); Android's readable copies are still `Doorprints-<date>.<ext>`
   (`ExportFormat.fileName`, pinned by `ExportFormatTest`) and move in Sprint 4b. The format string
   `doorprints-backup/1` stays as it is: it is internal.
5. **The format name travels with the feature.** Any future importable format is another version of the backup
   (`doorprints-backup/2`) with the same user-facing name, *Full backup*. It is never a new noun.

### G.4 Where the other documents follow this section

| Document | What follows |
|---|---|
| [01](01-requirements.md) | §6.9 import definition (FR-089..FR-097); FR-038's title |
| [05](05-ux-accessibility-i18n.md) | §14.6 glossary rows point here; I18N-B06 lists the G.1/G.2 drafts |
| [11](11-feature-parity-and-export-spec.md) | §5.9 and G-02 say *add*, not import |
| [schemas](schemas/README.md) | §0 "What an import is" |
| [10](10-sprint-log.md) | §12 Sprint 4b: S4b-00 and the renames still to do |

## Sources

The brand advisor's brief (2026-09-23) used only WebSearch and WebFetch. Main sources:
[Firebase multisite](https://firebase.google.com/docs/hosting/multisites) ·
[Firebase pricing](https://firebase.google.com/pricing) ·
[Firebase Hosting quotas](https://firebase.google.com/docs/hosting/usage-quotas-pricing) ·
[Firebase full-config (headers, redirects)](https://firebase.google.com/docs/hosting/full-config) ·
[Netlify credits](https://docs.netlify.com/manage/accounts-and-billing/billing/billing-for-credit-based-plans/how-credits-work/) ·
[Vercel fair use](https://vercel.com/docs/limits/fair-use-guidelines) ·
[Verisign RDAP, doorprints.com](https://rdap.verisign.com/com/v1/domain/doorprints.com) ·
[Google Registry RDAP, doorprints.app](https://pubapi.registry.google/rdap/domain/doorprints.app) ·
[NIXI RDAP, doorprints.in](https://rdap.nixiregistry.in/rdap/domain/doorprints.in) ·
[TLD-List .in](https://tld-list.com/tld/in) · [TLD-List .app](https://tld-list.com/tld/app).

**Not possible with the tools used:** Google Play and App Store search, the Facebook page, the IP India trademark
register, and the full Public Suffix List. Section C says what the owner checks by hand before a public launch.
