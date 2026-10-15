# Doorprints

**Remember every house you've seen.** A local-first notebook of the places you visit while choosing one, built for India;
houses are the kind it ships with, and the model underneath takes other kinds from a data file.

[![Backend](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/backend.yml/badge.svg?branch=main)](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/backend.yml) [![Web](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/web.yml/badge.svg?branch=main)](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/web.yml) [![Android](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/android.yml) [![Security](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/security.yml/badge.svg?branch=main)](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/security.yml) [![Shared iOS](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/shared-ios.yml/badge.svg?branch=main)](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/shared-ios.yml)

`AGPL-3.0-only` ([LICENSE](LICENSE), with the section 7 permissions and the trademark notice in [NOTICE](NOTICE)) ·
Report vulnerabilities privately ([SECURITY.md](SECURITY.md)) · **Web app: https://doorprints.web.app** ·
[User guide](https://sriram-codes-sw.github.io/doorprints/) (source in [`guide/`](guide/docs/index.md))

Doorprints is a free, open-source notebook for house-hunting in India. It is **not** a listings site: it holds only
the houses you have seen yourself. Save each house on a map with its price, BHK, photos, a checklist, a rating,
contacts and notes; compare your favourites; keep the paths you walked; and switch on **Hunt mode** so your phone tells
you when you pass a house or a street you already know. Everything works on the device with no account. If you want
your data on more than one device, back it up and sync through your own Google Drive, encrypted on the device, or run
your own small server. Nothing costs money, by rule.

Underneath, a house is one `candidate` record with a `kind`, typed `extras` and `money` with a cadence (rent a month, price
once), described by a **kind file** ([`docs/schemas/kinds/house.json`](docs/schemas/kinds/house.json)). Its fields, sections,
statuses, search, copies and AI prompts come from that file, and each kind lives in its own space. A second kind, schools,
is **compiled into the web, Android and iPhone builds but off**: it is not released, and appears only after the device-local switch *Experimental: more than houses*
([ADR-36](docs/03-design.md#14-architecture-decision-records), [docs/03 §18](docs/03-design.md),
[guide](guide/docs/other-kinds.md)).

English · हिन्दी · தமிழ் · తెలుగు (Hindi, Tamil and Telugu ship *under review*: machine-drafted, native review pending)

> Doorprints was called *House Hunt* until 2026-09-22 ([ADR-24](docs/03-design.md#14-architecture-decision-records)).

## Who it is for

**People:** one person, or a household, choosing a home to rent or buy in India, who wants their own record of what they
saw, on their own devices, without giving the data to anyone.

**Developers:** one typed, offline-first model across the web, Android and iPhone apps (Kotlin Multiplatform) and an
optional server, with the contracts pinned by shared vector files ([docs/schemas](docs/schemas/README.md)).

## What it does

*Shipped* means in the app as built for release (the default build). *No* means a development build only.

| Feature | Web ([doorprints.web.app](https://doorprints.web.app)) | Android | iPhone | Shipped |
|---|---|---|---|---|
| **Any kind** | | | | |
| One candidate model: kind file, typed extras, money with cadence; search, copies and AI prompts follow it | Yes | Yes | Yes | Yes (houses) |
| Spaces: a chooser and *What you track*, one space per kind, Delete all in this space | Dev build | Dev build | Dev build | No |
| Optional AI with your own key (Gemini, an OpenAI-compatible endpoint or Anthropic): Ask, fill from listing text, plan visits | Yes | Yes | Yes | Yes |
| Voice input into the AI boxes (off by default, audio kept in memory only) | Yes | Yes | Yes (early, untested on a device) | Yes |
| Google Drive: encrypted backups and sync between your devices | Yes | Yes | Yes (build with your own Google client id) | Yes |
| Optional self-hosted server as a sync hub | Yes | Yes | Yes | Yes |
| Share updates with someone you hunt with (a file in the backup format) | Yes | Yes | Yes | Yes |
| Recently deleted: bring a deleted candidate back within 30 days | Yes | Yes | Yes | Yes |
| Four languages, dark theme, WCAG 2.2 AA target, TalkBack and VoiceOver labels | Yes | Yes | Yes | Yes |
| **Houses** | | | | |
| Houses on a map with photos, checklist, rating, rooms, costs, notes; search, filter, sort | Yes | Yes | Yes | Yes |
| Compare two to four; weighted criteria; viewing questions; viewings with reminders | Yes | Yes | Yes | Yes |
| Hunt mode: alerts near a saved house or on a known street, stay detection, hunting areas | – | Yes | Yes | Yes |
| Your paths: the walk you took, "Have I been here?" | Yes | Yes | Yes | Yes |
| Save a copy (HTML, PDF, CSV, XLSX, Markdown) and a full backup; Import a backup | Yes | Yes | Yes | Yes |
| **Schools** (experimental, off by default) | | | | |
| Board, medium, school bus, fees and commute as extras; its own statuses, words and AI prompts | Compiled in, off | Compiled in, off | Compiled in, off | No |

The map uses OpenFreeMap vector tiles (OpenStreetMap data, ODbL). **India's boundaries are shown as the Government of
India depicts them**, the only view, on every platform; the rules, the data source and the known limits are in
[ADR-22](docs/03-design.md#14-architecture-decision-records) and [docs/03 §11.1](docs/03-design.md).
Screenshots are in the user guide, for example the [map](guide/docs/images/android-map.png) and the
[Ask screen](guide/docs/images/web-ai-ask-answer.png).

## Platforms and how you get them

| Platform | How | Status |
|---|---|---|
| Web, desktop and phone browsers | https://doorprints.web.app (Firebase Hosting, free plan); installable as an app from the browser menu | Live |
| Android 8.0+ (API 26) | APK from a GitHub release or the Actions artifact (see below). **No Play Store release and no public server until the release security gate passes** ([docs/10 §12.5](docs/10-sprint-log.md)) | Sideload |
| iPhone | The Compose Multiplatform app in a Swift shell ([`ios/`](ios/README.md)), built and launched on a simulator in CI. An App Store release would need the paid Apple Developer Program, which the zero-cost rule excludes | Built in CI |

Location is asked "while using the app"; Hunt mode is a visible foreground service you start and stop. "All the time"
is asked only when you turn on hunting areas, after a screen that says why ([docs/11](docs/11-feature-parity-and-export-spec.md) 5.18).

## Quick start

**Install the Android app.** Open a release on the [Releases](https://github.com/Sriram-Codes-SW/doorprints/releases)
page (a tag `v*.*.*` builds a signed APK, its `.sha256` and the signer fingerprint as a draft; the owner publishes it
after the release security checklist, [docs/13](docs/13-release-security-checklist.md)). Signer certificate SHA-256:
`ea23f3ca1175eaacef73f124449895537d6c16ff4f8de4da1e6d00d749be4381`; check with `apksigner verify --print-certs`.
Between releases, [Actions → Android](https://github.com/Sriram-Codes-SW/doorprints/actions/workflows/android.yml)
has the `doorprints-debug-apk` artifact of every green run on `main` (a debug build cannot be installed over a release build).

**Run the web app.** Node 24: `cd web && npm ci && npm start` (http://localhost:4200). Tests: `npm run test:ci`; build: `npm run build`.

**Build the Android app.** JDK 21 and the Android SDK (compileSdk 37):
`cd android && ./gradlew assembleDebug testDebugUnitTest :shared:testAndroidHostTest :ui:testAndroidHostTest`.

**Run the server.** JDK 25 or Docker:

```bash
export APP_API_KEY=$(openssl rand -hex 32)   # refused without a key of 32+ characters
docker compose up --build                    # PostGIS + pgvector and the API on http://127.0.0.1:8080
curl -H "X-API-Key: $APP_API_KEY" http://127.0.0.1:8080/api/stats
```

Allow the web app's origin with `APP_CORS_ORIGINS` and give the server an `https://` address before pointing the live
site at it; the server's own AI needs `APP_AI_ENABLED=true` and a key ([docs/07 §6](docs/07-secure-build-and-deploy.md#6-free-tier-deployment)).

All checks at once, as CI runs them: `tools/check.sh` (the areas your branch touches; `tools/check.sh all` for everything).

## Architecture

Three stacks around one local-first model, one candidate with its kind from a data file: the web app (Angular, MapLibre GL, IndexedDB), the Android and iPhone
apps (Kotlin Multiplatform and Compose Multiplatform: `android/shared`, `android/ui`, `android/app`, `ios/`) and an
optional self-hosted server (Spring Boot, PostGIS). Every client works alone; Google Drive and the server are optional
sync hubs. The diagram, each store's current version, the data contracts and a ten-minute reading path are on one page:
**[docs/16 Architecture overview](docs/16-architecture-overview.md)**.

## Repository structure

| Path | What |
|---|---|
| [`web/`](web/) | Angular web app and PWA ([README](web/README.md)) |
| [`android/shared/`](android/shared/) | Kotlin Multiplatform `:shared`: models, sync, export, Drive, AI core ([README](android/shared/README.md)) |
| [`android/ui/`](android/ui/) | Compose Multiplatform `:ui`: the screens of Android and iPhone ([README](android/ui/README.md)) |
| [`android/app/`](android/app/) | The Android app shell: Room, WorkManager, Hunt mode service, Play services |
| [`ios/`](ios/) | The iPhone shell and build notes ([README](ios/README.md)) |
| [`backend/`](backend/) | Spring Boot server, Flyway `V1__init.sql`, optional AI module; `backend/db` is the PostGIS + pgvector image ([README](backend/README.md)) |
| [`docs/`](docs/) | The secure-SDLC set 01–16, [`docs/ai/`](docs/ai/), [`docs/schemas/`](docs/schemas/README.md) (backup, sync and kind contracts with vectors; kind files in [`kinds/`](docs/schemas/kinds/)) |
| [`guide/`](guide/docs/index.md) | The user guide (MkDocs) |
| [`tools/`](tools/) | Local checks (`check.sh`), the live UI test, mutation lists |
| [`.github/`](.github/) | Workflows: `web`, `android`, `android-emulator`, `backend`, `shared-ios`, `security`, `codeql`, `pages`, `ai-evals`, `release`; Dependabot |

## Documentation

[Docs index](docs/README.md) · [01 Requirements](docs/01-requirements.md) · [02 Threat model](docs/02-threat-model.md) ·
[03 Design and ADRs](docs/03-design.md) · [04 Data flows](docs/04-data-flow-diagrams.md) · [05 UX, accessibility, i18n](docs/05-ux-accessibility-i18n.md) ·
[06 Test plan](docs/06-test-plan.md) · [07 Build and deploy](docs/07-secure-build-and-deploy.md) · [08 Operations](docs/08-operations-runbook.md) ·
[09 OSI resilience](docs/09-osi-layer-analysis.md) · [10 Sprint log](docs/10-sprint-log.md) · [11 Feature parity and export](docs/11-feature-parity-and-export-spec.md) ·
[12 Brand and naming](docs/12-brand-and-naming.md) · [13 Release security checklist](docs/13-release-security-checklist.md) ·
[14 Lead backlog and hand-off](docs/14-lead-backlog-and-handoff.md) · [15 Google Drive](docs/15-google-drive-backup-and-sharing.md) ·
[16 Architecture overview](docs/16-architecture-overview.md) · [AI design](docs/ai/ai-design.md) · [CHANGELOG](CHANGELOG.md)

## Security and privacy

No account, no telemetry, no data on anyone's server unless you run one. Backups in Google Drive are encrypted on the
device with a recovery key; the server, if you run one, takes an API key and validates every input; contact names and
numbers are redacted before anything reaches an AI provider; your AI key stays on the device. The threat model is
[docs/02](docs/02-threat-model.md); the release gate is [docs/13](docs/13-release-security-checklist.md); report
problems privately ([SECURITY.md](SECURITY.md)).

## Contributing

A personal project built in the open. External pull requests are not expected and may be closed without review; issues
with ideas are welcome. Anything contributed is under the [LICENSE](LICENSE) with the [NOTICE](NOTICE) permissions.

Working rules (full list: [docs/14 §7](docs/14-lead-backlog-and-handoff.md), [`CLAUDE.md`](CLAUDE.md)):
every change is a pull request to `main` on a branch named `<type>/<topic>` (`feat`, `fix`, `docs`, `ci`, `chore`,
`refactor`, `test`); CI runs on pull requests and on `main`; docs change in the same pull request as code (the
docs-versions check enforces it); every source file carries the licence header (`python3 .github/scripts/licence-headers.py --fix`);
every user-visible string in all four languages (`web/src/app/i18n/{en,hi,ta,te}.ts`, `android/ui/src/commonMain/composeResources/values{,-hi,-ta,-te}/strings.xml`);
no secrets in git; nothing that costs money.

## Status

Where things stand, the open pull requests and the ordered next steps: [docs/14 §1 and §2](docs/14-lead-backlog-and-handoff.md); what is left is the owner's (device checks, the translation review, the release gate), listed first in §2.
Release notes: [CHANGELOG.md](CHANGELOG.md).
