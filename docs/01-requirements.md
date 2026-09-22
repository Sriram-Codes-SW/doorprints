# 01: Software Requirements Specification (SRS)

| Field | Value |
|---|---|
| Document | Software Requirements Specification |
| Version | 0.11 |
| Date | 2026-09-22 |
| Author | Claude (Cowork) |
| Status | Draft |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) | First version, based on the code in `backend/`, `android/` and `web/` as of 2026-09-22. |
| 0.2 | 2026-09-22 | Claude (Cowork) | Wave 2: statuses updated after security hardening, Android i18n/a11y, AI UI and CI. New FR-034..FR-041, NFR-017..NFR-020, SEC-026..SEC-030. RTM extended with the new tests. |
| 0.3 | 2026-09-22 | Claude (Cowork) | Sprint 1 fixes and Sprint 2 ([10](10-sprint-log.md)): SEC-002 Impl (32-char minimum), SEC-017 Impl (`APP_API_KEY_NEXT` dual key), SEC-018 Part (signed release APK in CI, R8 still off), SEC-013 (Trivy on a CycloneDX SBOM, Tomcat 11.0.25 override for F-28), SEC-024 (DB image non-root, F-29). CON-03: compileSdk 37, MapLibre GL 6.10. RTM: new web and Android unit tests (TC-U-05..07, TC-U-18..21), TC-I-21, TC-S-14, TC-S-15, AI eval harness TC-AI-09/10. |
| 0.4 | 2026-09-22 | Claude (Cowork) | Sprint 3 ([10](10-sprint-log.md)): RTM row AI-001..AI-012 traces the new AI tests TC-AI-11..14 from [06](06-test-plan.md) §8 (native Gemini embeddings, indexer failure handling, scorecard verdict, embedding provider selection). No requirement text changed. AI-010 status set to **Part**: the contact name still reaches the LLM provider ([02](02-threat-model.md) F-30). |
| 0.5 | 2026-09-22 | Claude (Cowork) | Sprint 3 lead decisions ([10](10-sprint-log.md)): AI-010 back to **Impl** (C-13: the AI team's `ContactRedactor` fixes F-30; waiting on CI); RTM traces TC-AI-15 (contact redaction) and TC-AI-16 (contract tests). F-01 is split in [02](02-threat-model.md) v0.6: SEC-002 and SEC-017 trace to F-01a (Fixed), SEC-025 to F-01b (Open). |
| 0.6 | 2026-09-22 | Claude (Cowork) | PRV-009 stays **Part** now that F-30 is fixed; the status cell gives the reasons (best-effort free-text redaction, pasted listing text sent for extraction, erasure does not reach backups, unsynced devices or data a provider already received) and what is done. No requirement text changed. |
| 0.7 | 2026-09-22 | Claude (Cowork) | Sprint 3 outcome ([10](10-sprint-log.md) §5): AI-010 stays **Impl** and PRV-009 stays **Part**; their "waiting on CI" notes are replaced by the evidence (F-30 closed as Fixed by lead decision: TC-AI-15 green in the Backend workflow on `6a348cc`; the real Gemini eval run 35720654442 is a no-regression check only, its fixtures hold no contact data). AI-012 stays **Part** and records the first real eval result (all 13 cases ran, 12/13 cases passed, all metrics pass except `citationPrecision` 0.86 vs 0.90, E-03). No requirement text changed. |
| 0.8 | 2026-09-22 | Claude (Cowork), Docs team | Product rename to **Doorprints** (tagline "Remember every house you've seen."; [03](03-design.md) ADR-13): sections 1 and 2, SEC-019 and the section 9 privacy introduction use the new name; section 2 says the app is a personal record of the houses the user has seen, not a listings site. RTM row AI-001..AI-012 now also traces **TC-AI-17** (Ask prompt citation and contrast rules, AI-002/AI-003; added in [06](06-test-plan.md) v0.8 but missing here). No requirement text or status changed. |
| 0.9 | 2026-09-22 | Claude (Cowork), Docs team | Product-owner decisions of 2026-09-22 ([11](11-feature-parity-and-export-spec.md) v0.3 D-21, D-22). **AI access policy:** new **AI-013** (cloud AI only for the owner and invited users), **AI-014** (on-device AI for guests via Gemini Nano / ML Kit GenAI Prompt API, otherwise hidden), **AI-015** (hard cost cap on the paid key), **AI-016** (two active providers, Vertex AI and the Gemini API / AI Studio, switchable by configuration; AI Studio code kept). New **PRV-022** (real user data only to a paid tier or Vertex AI; the free AI Studio tier only with synthetic data) and **PRV-023** (on-device AI sends nothing off the phone). Changed: AI-001 and AI-009 (paid, hard-capped key instead of "stays within the free tier"), CON-01 (one owner-paid exception), CON-05. Section 11.3: **bring-your-own-key** is out of scope (rejected). RTM rows for the new IDs. The repository is now public (`Sriram-Codes-SW/doorprints`, MIT). All new IDs are **Plan** except AI-016 (**Part**). |
| 0.10 | 2026-09-22 | Claude (Cowork), Docs team | Review fixes. **AI-015** now names three cap layers: in-app per-user and global daily caps, a Google Cloud **spend cap budget** (Preview; monthly, before credits) on an AI-only project and service, and budget alerts; the Quotas-page limit is optional. AI-016 points to the credential rules (07 §4, 02 T-I22). Links to `ai/vertex-setup.md` marked as being written by the AI team. |
| 0.11 | 2026-09-22 | Claude (Cowork), Docs team | Vertex AI code landed in the same change set, so the "being written" markers on `ai/vertex-setup.md` are removed. **AI-016** now says what is implemented (`AI_PROVIDER` = `aistudio` default or `vertex`; Vertex chat through Spring AI's Google GenAI starter, embeddings through the app's `VertexEmbeddingModel`, Application Default Credentials only, no API key; provider quota errors return `503` with `code: AI_QUOTA_EXHAUSTED` and `Retry-After: 60`); status stays **Part** until the first CI run and the owner's setup steps 8 and 10 (model location, credit check). New **AI-017**: when the Google Cloud spend cap (AI-015) trips, the API returns a clear "cloud AI paused" problem that is not retried, covered by a contract test (review finding: today's quota path covers HTTP 429 only). RTM rows updated with the new Vertex tests (TC-AI-16, TC-AI-18..TC-AI-21 in [06](06-test-plan.md) v0.10). |

Related: [README](README.md) · [Threat model](02-threat-model.md) · [Design](03-design.md) · [DFDs](04-data-flow-diagrams.md) · [UX/a11y/i18n](05-ux-accessibility-i18n.md) · [Test plan](06-test-plan.md) · [AI docs](ai/)

---

## 1. Purpose

This SRS defines what Doorprints (called House Hunt until 2026-09-22, see [03](03-design.md) ADR-13) must do and how well it must do it. It sets the security, privacy and AI rules the system must follow. It is the baseline for design (03), threat modelling (02) and testing (06).

## 2. Scope

Doorprints ("Remember every house you've seen.") helps one person keep track of the houses they see while looking for a house to rent or buy in India. It is a personal record of the user's own visits, not a property-listings site. While walking or driving around a locality, the app:

- remembers every house viewed: location, notes, star rating, a 10-item checklist, photos, price, contact and status,
- warns when you pass a house you have already seen, or enter a street you have been on before,
- notices when you stay in one place for a few minutes and offers to save it as a house,
- works fully offline on Android and syncs to a small personal server when a network is available,
- offers a web app for reviewing and comparing houses on a larger screen,
- (planned, optional) answers questions about your own hunt with an LLM, pre-fills houses from listing text, plans viewing routes, and exposes house tools over MCP.

| In scope | Out of scope (v1) |
|---|---|
| Android app (sideloaded APK), Spring Boot API, Angular web app | iOS app |
| One user, one shared API key | Multi-user accounts, sharing and roles |
| Free-tier hosting only | Paid hosting, paid map/geocoding APIs |
| Houses, visits, photos, checklist, compare | Listing scraping, property portals integration, payments, legal checks |
| Optional AI features that are off unless configured | AI that acts without user confirmation |

## 3. Glossary

| Term | Meaning |
|---|---|
| House | A place the user viewed or plans to view. Has a point location and attributes. |
| Visit | A time-stamped presence at a location. It can be linked to a house. `AUTO` = found by stay detection, `MANUAL` = user tapped "been here". |
| Hunt mode | Android foreground service that tracks location while the user is actively house hunting. |
| Stay | Remaining within 40 m of an anchor point for at least N minutes (default 4). |
| Sync version | Server-wide number that increases on every write (Postgres sequence `sync_seq`). Clients pull changes with `since=<cursor>`. |
| LWW | Last-write-wins conflict resolution using the record's `updatedAt`. |
| Tombstone | Soft-deleted row (`deleted = true`) kept so the deletion can sync. |
| BHK | Bedrooms-Hall-Kitchen, the Indian way of stating house size. `bedrooms` field. |
| RAG | Retrieval-augmented generation over the user's own notes and visits. |
| MCP | Model Context Protocol. Exposes house tools to an AI client. |

## 4. Personas

| ID | Persona | Goals | Context |
|---|---|---|---|
| PER-1 | **Asha, the hunter** (primary, the only user) | See many houses in a few weekends, don't re-visit rejected ones, compare fairly | Walks and rides through Bengaluru/Chennai/Hyderabad localities with patchy mobile data. One hand on the phone. May prefer Hindi, Tamil or Telugu. |
| PER-2 | **Asha at home** | Review, score and shortlist on a laptop | Uses the web app on a desktop browser |
| PER-3 | **Family member** (read-mostly, informal) | Look at the shortlist | Uses the same web app with the same key. No separate identity in v1. |
| PER-4 | **Operator** (same person) | Deploy for free, keep data safe, rotate keys | GitHub, a free PaaS and a free Postgres |
| PER-A | **Attacker** (anti-persona) | Steal location history or contacts, change data, run up usage | Internet scanner, thief who takes the phone, or a malicious listing text that tries to inject prompts |

## 5. User stories

| ID | As a... | I want to... | So that... | Req |
|---|---|---|---|---|
| US-01 | hunter | save the house I am standing at with one tap | I don't have to type an address | FR-001 |
| US-02 | hunter | add notes, stars, checklist scores, price, BHK, contact and photos | I remember what the house was like | FR-002..FR-007 |
| US-03 | hunter | get an alert when I pass a house I saw before | I don't view it twice or I remember why I rejected it | FR-013 |
| US-04 | hunter | know when I enter a street I have walked before | I can cover new streets instead | FR-014 |
| US-05 | hunter | be asked "are you at a house?" after I stand still for a few minutes | houses get recorded even if I forget | FR-015 |
| US-06 | hunter | use everything without mobile data | poor coverage doesn't stop me | FR-017, NFR-004 |
| US-07 | hunter at home | see all houses on a map and in a list, filter by status | I can plan and decide | FR-009, FR-010, FR-021 |
| US-08 | hunter | compare up to 4 houses side by side | I can decide on a shortlist | FR-011 |
| US-09 | hunter | mark houses shortlisted or rejected | my list stays focused | FR-006 |
| US-10 | operator | protect my data with a secret key and HTTPS | strangers cannot read my location history | SEC-001, SEC-004 |
| US-11 | hunter | export or permanently delete my data | I keep control once the hunt is over | PRV-004, PRV-005 |
| US-12 | hunter | ask "which 2BHKs under 25k had good water?" and get answers with links to the houses | I don't re-read all my notes | AI-002 |
| US-13 | hunter | paste listing text and get the form pre-filled | I save time typing | AI-004 |
| US-14 | hunter | get a suggested order to view today's shortlisted houses | I waste less time travelling | AI-006 |
| US-15 | hunter | use the app in Hindi, Tamil or Telugu and with TalkBack | it works for me and my family | NFR-006, NFR-007 |

## 6. Functional requirements

Priority: **M**ust, **S**hould, **C**ould, **W**on't (this release). Status: **Impl** (in code), **Part** (partial), **Plan** (not yet built).

### 6.1 Houses

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-001 | The user can create a house at the current GPS location ("Save house here"), by long-pressing the map (Android), or by clicking the map (web). The client generates the house ID (UUID v4) so it can be created offline. | M | Impl |
| FR-002 | A house has: label (required, at most 200 chars), address, street, locality, lat/lon, price (INR, ≥ 0), price type RENT/SALE, bedrooms (BHK), contact name, contact phone, listing URL and notes (at most 20 000 chars). | M | Impl |
| FR-003 | The user can give a house a star rating from 1 to 5. | M | Impl |
| FR-004 | The user can score a fixed 10-item checklist from 0 to 5: water, power, parking, sunlight, ventilation, noise, security, maintenance, neighbourhood, commute. Web and Android use the same keys. | M | Impl |
| FR-005 | The system computes an overall score from 0 to 5: the mean of the checklist scores, averaged 50/50 with the star rating when both exist. It is null when nothing has been scored. | S | Impl |
| FR-006 | A house has a status of NEW, SHORTLISTED or REJECTED. The user can change it at any time (state machine in 03 section 8.1). | M | Impl |
| FR-007 | The user can attach photos from the camera or the gallery. Photos are shrunk to 1600 px on the longest side and saved as JPEG before storage/upload. The server accepts JPEG/PNG/WebP up to 5 MB, detected from the bytes, at most 20 per house. | M | Impl |
| FR-008 | The system records visits: MANUAL ("been here now") and AUTO (from stay detection). Visits can be linked to a house. | M | Impl |
| FR-009 | Map view shows all houses as markers coloured by status, on OpenFreeMap vector tiles. | M | Impl |
| FR-010 | List view with text search (name, street, notes), a status filter and sorting. | S | Impl (Android) |
| FR-011 | Compare view shows 2 to 4 houses side by side with the checklist, rating, price and score. | S | Impl |
| FR-012 | Delete leaves a tombstone so it syncs to other devices. The tombstone keeps no content (see PRV-005). | M | Impl |

### 6.2 Hunt mode (Android)

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-013 | While Hunt mode is on, the user gets a notification when within the alert radius (default 30 m, configurable) of a saved house. Each house alerts at most once per 30 minutes. Tapping the alert opens the house. | M | Impl |
| FR-014 | While Hunt mode is on, reverse geocoding (at most once per 45 s unless moved at least 80 m) finds the current street. If saved houses or visits exist on that street, the user gets a notification ("You've been on X before", counts, first visit date). Each street alerts at most once per 60 minutes. | M | Impl |
| FR-015 | Stay detection: staying within 40 m for at least N minutes (default 4, configurable) creates an AUTO visit. If no house exists within 40 m, a notification asks "Are you at a house?" and opens a new-house form pre-filled with the location. Leaving the place sets `leftAt`. | M | Impl |
| FR-016 | Location fixes with accuracy worse than 50 m are ignored for alerts and stays, and the UI shows "Weak GPS, alerts paused". | M | Impl |
| FR-017 | Hunt mode runs as a visible foreground service with an ongoing notification and a Stop action. It stops when the user turns it off. | M | Impl |
| FR-018 | The Hunt card on the map shows the current street, how many houses and visits exist on it, the nearest saved house within 150 m and the GPS accuracy. | S | Impl |

### 6.3 Offline and sync

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-019 | All Android create/read/update/delete features work without a network, using the on-device Room database and photo files. | M | Impl |
| FR-020 | Sync runs about 3 s after each local change and every 30 minutes, only when a network is available. Failed runs retry with exponential backoff (30 s start, up to 5 attempts); single requests retry up to 3 times with jittered backoff. | M | Impl |
| FR-021 | Sync pushes dirty houses, visits, queued photo deletes and new photos. It then pulls house, visit and photo changes with `since=<cursor>` (photo tombstones remove local copies; new photos of live houses are downloaded). | M | Impl |
| FR-022 | Conflicts are resolved last-write-wins on `updatedAt`, on both the server and the client. Tombstones sync. | M | Impl |
| FR-023 | Changing the server URL resets the sync cursors, so everything is downloaded again. | S | Impl |

### 6.4 Web app

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-024 | The web app has a Connect page (API base URL and key, "test connection", "remember on this device"), a Map page, a House detail/edit page (checklist, rating, photos, visits) and a Compare page. Confirmations use an in-app dialog. | M | Impl |
| FR-025 | "Fill address from map" reverse-geocodes with OSM Nominatim, only when the user presses the button. | S | Impl |

### 6.5 Server

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-026 | Nearby search: houses within a radius (default 50 m, capped at 5 000 m), nearest first, at most 50, with the distance in metres. | S | Impl |
| FR-027 | Street search: live houses whose street matches, ignoring case, using the `lower(street)` index. | S | Impl |
| FR-028 | Stats: counts of houses, shortlisted, rejected, visits and distinct streets. | C | Impl |
| FR-029 | Health endpoint for uptime checks, with no authentication and no details. | M | Impl |

### 6.6 Settings and data control

| ID | Requirement | Pri | Status |
|---|---|---|---|
| FR-030 | Android settings: server URL (HTTPS only), API key (masked, stored encrypted), photos only on Wi-Fi, language, alert radius, minimum stay minutes, last sync time and result, "Sync now", "Save and test". | M | Impl |
| FR-031 | Export all data (houses, visits, checklist, photo metadata with photo URLs) as JSON from the API (`GET /api/export`). App buttons for export are backlog. | S | Impl (API) |
| FR-032 | Permanently delete all data including tombstones, photos and embeddings (`DELETE /api/data` with the header `X-Confirm-Delete: DELETE-ALL-MY-DATA`). Deleting one house purges its content and photos. | S | Impl (API) |
| FR-033 | Notification taps deep-link to the right screen (open a house, or a new house with lat/lon and visit ID). | M | Impl |
| FR-034 | Photo deletes made offline are queued and sent on the next sync; photos deleted on another device are removed locally. | M | Impl |
| FR-035 | Photos upload and download only on unmetered networks when "Photos only on Wi-Fi" is on (default); houses and visits always sync. Waiting photos are counted and moved by a Wi-Fi-only job. | S | Impl |
| FR-036 | Android UI in English, Hindi, Tamil and Telugu with an in-app language picker (per-app language via `LocaleManager` on Android 13+, stored preference on 8–12). | S | Impl |
| FR-037 | Ask (AI, optional): questions about the saved houses with cited houses linked (web page, Android Assistant tab). Hidden unless `GET /api/ai/status` reports `enabled`. | C | Impl |
| FR-038 | Import listing text (AI, optional): paste an ad on the new-house form (web) or edit screen (Android) to pre-fill fields; nothing is saved until the user saves. | C | Impl |
| FR-039 | Plan visits (AI, optional): an ordered walking route from the current or chosen start point (web: list + map; Android: list). | C | Impl |
| FR-040 | Hunt mode lowers its GPS rate while the user stands still and stops itself when the battery is at 15% or less and not charging. | S | Impl |
| FR-041 | Android dark theme, and colours taken from the web design tokens. | S | Impl |

## 7. Non-functional requirements

| ID | Category | Requirement | Measure / target | Pri |
|---|---|---|---|---|
| NFR-001 | Performance | API responses when the server is warm | p95 < 500 ms for list/nearby with at most 1 000 houses | S |
| NFR-002 | Performance | Tolerate free-tier cold starts | Clients wait up to 90 s (Android read timeout is already 90 s). The web shows a "waking server" hint. | M |
| NFR-003 | Performance | Hunt-mode check per fix | Nearby-house check at most 50 ms for 1 000 houses on a mid-range phone (in-memory haversine) | S |
| NFR-004 | Offline | Core Android features need no network | 100% of FR-001..FR-019 work offline. Maps show cached tiles only. | M |
| NFR-005 | Battery | Hunt-mode energy use | At most about 8% battery per hour on a mid-range phone (high-accuracy fixes, 15 s interval, 5 s minimum, 5 m displacement). Must be verified in the field test (TC-F-06). | S |
| NFR-006 | Accessibility | WCAG 2.2 AA (web), TalkBack support (Android) | As in [05](05-ux-accessibility-i18n.md) | M |
| NFR-007 | i18n | English, Hindi, Tamil, Telugu UI. Indian number format (₹, lakh/crore grouping via `en-IN`). | As in [05](05-ux-accessibility-i18n.md) | S |
| NFR-008 | Availability | Best effort on free tiers | Target about 99% monthly for the API when not sleeping. Data durability matters more than uptime: RPO ≤ 24 h, RTO ≤ 4 h (see 08). | S |
| NFR-009 | Capacity | Stay within free DB quotas | About 500 MB DB. Budget: 1 000 houses, 5 000 visits, about 2 000 photos at about 250 KB each. Photos dominate (see F-06). | M |
| NFR-010 | Resource | API fits in 512 MB RAM | JVM flags `MaxRAMPercentage=75`, SerialGC, Hikari pool of 5 | M |
| NFR-011 | Portability | Runs anywhere with Docker and Postgres 15+ with PostGIS 3 | `docker compose up` works locally | M |
| NFR-012 | Fair use | Respect third-party usage policies | Nominatim at most 1 request/s, only on user action, with a proper User-Agent/Referer. OpenFreeMap fair use. Android Geocoder throttled (45 s / 80 m). | M |
| NFR-013 | Maintainability | Automated tests and CI | Backend integration tests on PostGIS in CI. Android unit tests for StayDetector/Geo. Lint clean. | S |
| NFR-014 | Cost | Zero running cost | See section 11 | M |
| NFR-015 | Usability | One-handed, outdoor use | Main actions reachable by thumb, readable in sunlight (see 05) | S |
| NFR-016 | Sync latency | Changes reach the server soon | At most 1 minute after a change when online | S |
| NFR-017 | Resilience | Network faults and cold starts | Idempotent requests retried up to 3 times with exponential backoff and full jitter (cap 15 s); captive portals detected; no redirects followed. See [09](09-osi-layer-analysis.md). | M |
| NFR-018 | Bandwidth | Mobile data use | JSON gzip-compressed by the API; photos only on Wi-Fi by default | S |
| NFR-019 | Capacity | Photo storage bounded | At most 20 photos per house (server 409, app check) | M |
| NFR-020 | Accessibility | Android touch targets and font scaling | 48 dp targets, radio/checkbox/switch semantics, headings, layouts that wrap at 200% font scale | M |

## 8. Security requirements

| ID | Requirement | Pri | Status | Threat / finding |
|---|---|---|---|---|
| SEC-001 | Deny by default: every request needs a valid API key (`X-API-Key`) except `GET/HEAD /actuator/health` and CORS preflights. Non-canonical paths (`;`, `%`, backslash, `//`, dot segments) are rejected with 400. | M | Impl | T-S1, T-S2, F-20 |
| SEC-002 | The API key must be random with at least 128 bits of entropy (for example 32+ chars from `openssl rand -hex 32`). The server refuses to start when `APP_API_KEY` (or a non-empty `APP_API_KEY_NEXT`) is shorter than 32 chars; the error names the variable, never the value. | M | Impl (Sprint 2) | F-01a |
| SEC-003 | Keys are compared in constant time. With two keys configured, both are always compared (no early exit). | M | Impl (`MessageDigest.isEqual`) | T-S1 |
| SEC-004 | Production traffic uses TLS only. Android allows cleartext only to `localhost`/`127.0.0.1`/`10.0.2.2` (network security config) and trusts only system CAs; Settings rejects non-HTTPS URLs. The web app must use an HTTPS API URL. | M | Impl | F-02 |
| SEC-005 | CORS allows only the configured web origins, and only on `/api/**`. | M | Impl | T-S3 |
| SEC-006 | All input is validated at the API (bean validation for lengths, ranges, enums and checklist 0 to 5). Out-of-range lat/lon and radius on query endpoints are rejected. | M | Impl | F-19 |
| SEC-007 | Uploads are limited to 5 MB. The type is detected from magic bytes (JPEG/PNG/WebP), metadata is stripped, uploads to deleted houses are refused and there are at most 20 photos per house. | M | Impl | F-07, F-26 |
| SEC-008 | Rate limiting per client address: 600 requests/min (burst 300) in general, 10/min for wrong keys, 10/min for AI (burst 5). 429 with `Retry-After`. | S | Impl | F-05 |
| SEC-009 | No secrets in the repository, images or APK. Secrets come from environment variables / GitHub Secrets. CI runs gitleaks on the full history. | M | Impl | F-17 |
| SEC-010 | The API key is stored encrypted on the device (AES-256-GCM, Android Keystore key) and excluded from backups. On the web it is kept in sessionStorage unless the user chooses "remember on this device". | S | Impl | F-03, F-04 |
| SEC-011 | Android: `allowBackup=false`; data extraction rules allow no cloud backup and device transfer of the database and photos only. | M | Impl | F-03 |
| SEC-012 | Security headers: the API sends `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, CSP `default-src 'none'`, `Cache-Control: no-store` on JSON and HSTS over HTTPS. The web app ships a strict CSP, HSTS and `frame-ancestors 'none'` in `_headers`. | S | Impl | F-10 |
| SEC-013 | Dependencies are scanned in CI (Trivy on the backend CycloneDX SBOM, Trivy fs for npm and secrets, npm audit) and weekly. Critical/High findings with a fix block merges; a fix the framework BOM does not manage yet is applied as a commented version override (e.g. Tomcat 11.0.25). Dependabot covers Maven, npm, Gradle, Actions and Docker. | M | Impl | F-22, F-27, F-28 |
| SEC-014 | SAST (Semgrep OSS) runs in CI and blocks on ERROR findings. Android Lint runs and is reported (not yet blocking). | S | Part | F-22 |
| SEC-015 | Error responses use RFC 7807 with no stack traces or SQL. The clients show translated error categories, never raw server bodies. | M | Impl | F-12 |
| SEC-016 | Logs contain no API keys, coordinates, notes or phone numbers. Auth failures are logged with a salted client-address hash, method and path. | S | Impl | F-18 |
| SEC-017 | The key can be rotated with a documented procedure. The server supports a current and a next key during rotation (`APP_API_KEY`, `APP_API_KEY_NEXT`). | S | Impl (Sprint 2; procedure in 08 §5.1) | F-01a |
| SEC-018 | Release APKs are signed with a private keystore kept outside the repo, built with R8 minify/shrink, `debuggable=false`, and published with a SHA-256 checksum. | M | Part (Sprint 2: signing from `HH_*` secrets and `apksigner verify` in CI; R8 off until keep rules exist; checksum publishing with `release.yml` next) | F-11 |
| SEC-019 | The DB connection uses TLS (`sslmode=require`) and a non-superuser app role that owns only the app schema (`househunt`; the database, role and schema names were kept at the Doorprints rename, [03](03-design.md) ADR-13). Flyway migrations run with the same role or a separate migration role. | M | Plan | T-I4 |
| SEC-020 | The server clamps client `updatedAt` values more than 5 minutes in the future to server time and rejects dates more than 365 days ahead or before 2000, so records cannot be "frozen". | M | Impl | F-08 |
| SEC-021 | Android components are not exported unless needed. PendingIntents are immutable. The deep-link extras from `MainActivity` are validated (UUID format, lat/lon range). | S | Impl | F-25 |
| SEC-022 | Alert notifications use `VISIBILITY_PRIVATE` with a redacted public version, so the lock screen does not show house names or prices. | C | Impl | F-14 |
| SEC-023 | Actuator exposes only `health` without details. | M | Impl | - |
| SEC-024 | The containers run as non-root users: the API as UID 10001, the dev/CI database image (`backend/db`) as `postgres`; docker-compose adds a read-only filesystem, `cap_drop: ALL` and `no-new-privileges`. Trivy config blocks HIGH/CRITICAL Dockerfile findings. | S | Impl | F-23, F-29 |
| SEC-025 | Future: per-device keys or OAuth2/OIDC (for example a free-tier IdP) with revocation. | C | Plan | F-01b |
| SEC-026 | JSON request bodies are capped at 256 KB (413); Tomcat connection timeout 20 s. | S | Impl | F-05 |
| SEC-027 | Sync versions are assigned under a transaction-scoped advisory lock so that a `since` cursor never skips a change. | M | Impl | F-09 |
| SEC-028 | The Android client does not follow HTTP redirects (the key must never reach another host) and treats non-JSON answers as a captive portal. | M | Impl | 09 §3 |
| SEC-029 | docker-compose binds all ports to 127.0.0.1 and refuses to start without `APP_API_KEY`. | M | Impl | F-17 |
| SEC-030 | Third-party GitHub Actions are pinned to a commit SHA or run as pinned container images; workflows have read-only `permissions`. | M | Impl | F-22, T-E4 |

## 9. Privacy requirements

Location history and third-party contact details are the most sensitive data here. India's **Digital Personal Data Protection Act, 2023 (DPDP Act)** does not apply to personal data "processed by an individual for any personal or domestic purpose" (section 3(c)(i)). Still, Doorprints holds **other people's** data (landlord/agent names and phone numbers, photos that may show people) and sends data to processors (hosting, LLM). We therefore follow DPDP principles voluntarily: purpose limitation, data minimisation, accuracy, storage limitation, security safeguards and erasure. If the app is ever offered to other users, the DPDP obligations (notice, consent, Data Principal rights, breach notice to the Data Protection Board) would apply in full.

| ID | Requirement | Pri | Status |
|---|---|---|---|
| PRV-001 | Location is collected only while Hunt mode is on or the map screen is open. The app does not request `ACCESS_BACKGROUND_LOCATION`. The foreground service is the only background collector. | M | Impl |
| PRV-002 | Tracking is always visible (ongoing notification) and can be stopped in one tap. | M | Impl |
| PRV-003 | Raw GPS tracks are **not** stored. Only visits (stay points) and house points are stored. | M | Impl |
| PRV-004 | Right to access / portability: export all data (FR-031). | S | Impl (API) |
| PRV-005 | Right to erasure: deleting a house blanks its content, tombstones its photos and unlinks its visits; "delete everything" hard-deletes all rows and embeddings (FR-032). Tombstones are purged after 90 days (`app.privacy.tombstone-retention-days`). | S | Impl |
| PRV-006 | Retention: the user can purge visits older than a chosen age. Default suggestion: delete all data 6 months after the hunt ends. | C | Plan |
| PRV-007 | Third parties that receive data are disclosed in-app: Google Play services (fused location, Android Geocoder: coordinates), OpenFreeMap (tile requests: map area + IP), OSM Nominatim (web: coordinates on button press), hosting/DB providers (all data), the LLM provider if AI is enabled (see AI-010). | S | Part (Android Settings and AI screens; web privacy page backlog) |
| PRV-008 | Photo EXIF metadata (GPS, device) is removed before storage/upload. Android and web re-encode through a Bitmap/canvas, which drops EXIF; the server strips metadata again (JPEG APP1/COM, PNG text/eXIf, WebP EXIF/XMP). | M | Impl |
| PRV-009 | Third-party contact data (names, phones) is stored only when the user enters it, is used only to contact about that house, and is removed by erasure. It is never sent to an LLM unless the user opts in (redacted by default). | M | Part. Done: stored only when entered; removed with the house, by delete-all and by clearing the fields ([08](08-operations-runbook.md) §6.2); the structured contact fields are never sent to an LLM, and names and phones typed into other fields are redacted ([02](02-threat-model.md) F-30 Fixed, Sprint 3, closed by lead decision; TC-AI-15 green on `6a348cc`). Still Part because: (1) free-text redaction is best effort ([ai/](ai/ai-design.md) §9.1 Limits: nicknames and other spellings, a first name alone in a street or locality, short local numbers); (2) listing extraction sends the pasted text as given, which may hold a contact (explicit user action, disclosed); (3) erasure does not reach encrypted backups until they expire (30 days, [08](08-operations-runbook.md) §3), devices that have not synced, or text a hosted provider already received (before the fix or the post-deploy reindex). |
| PRV-010 | Prefer data residency in India where the free tier allows it (Supabase `ap-south-1` Mumbai, Oracle Mumbai/Hyderabad home region). | C | Plan |
| PRV-011 | Server and CI logs hold no personal data (see SEC-016). Backups are encrypted (see 08). | M | Part |
| PRV-022 | Real user data (houses, notes, visits, questions, listing text) is sent only to a provider tier whose terms do not use it to improve the provider's products: Vertex AI or the paid Gemini API tier. The free AI Studio tier, which may use prompts to improve Google products, is used only with synthetic data (evals). See [02](02-threat-model.md) T-I20. | M | Plan (with AI-016). Today: the owner's own data on the free tier, accepted and disclosed (CON-05). |
| PRV-023 | On-device AI (AI-014) sends no prompt, data or output off the phone, and the app says so. | M | Plan (Sprint 5) |

## 10. AI requirements

AI features are **optional** and **off unless configured**. The AI team owns the detailed design in [docs/ai/](ai/). The requirements below are the contract this document set relies on.

| ID | Requirement | Pri | Status |
|---|---|---|---|
| AI-001 | Every AI feature is disabled unless the operator configures a provider (Vertex AI, the Gemini API, or a local Ollama; AI-016). The free Gemini API tier is for evals with synthetic data only (PRV-022). When disabled, AI endpoints return 404 and both clients hide AI entry points (`GET /api/ai/status`). | M | Impl |
| AI-002 | "Ask my house hunt": RAG with Spring AI 2.0.1 and pgvector over the user's own houses, notes and visits. Every answer cites the house IDs it used, and the UI links the citations. | S | Impl |
| AI-003 | Grounding: answers use only retrieved context. If the context is not enough, the answer says so. Citations must refer to records that exist and were retrieved (checked on the server). | M | Impl (backend, see [ai/](ai/)) |
| AI-004 | Listing extractor: turns pasted listing text into a **draft** house (price, BHK, locality, contact...). The user must review and save. It never saves automatically. | S | Impl |
| AI-005 | Output validation: LLM output is parsed into a typed schema and checked with the same bean validation as `HouseDto`. Invalid fields are dropped, not "fixed". Output is rendered as text only (no HTML/Markdown injection). | M | Impl (backend, see [ai/](ai/)) |
| AI-006 | Viewing-day planner agent: plans an order and time for viewing selected houses using tools (list houses, distance matrix from PostGIS, visit history). Tools are read-only. There is a step limit (for example at most 8 tool calls) and a timeout. | C | Impl (backend, see [ai/](ai/)) |
| AI-007 | MCP server: exposes house tools (search, get, nearby, stats) behind the same authentication as the API. Read-only by default. Any write tool needs explicit opt-in config and client-side confirmation. | C | Impl (backend, see [ai/](ai/)) |
| AI-008 | Prompt-injection defence: listing text, notes and retrieved content are treated as untrusted data and delimited in prompts. The model cannot call write tools because of instructions inside that data. The system prompt holds no secrets. | M | Impl (backend, see [ai/](ai/)) |
| AI-009 | Cost/abuse limits: a per-day request quota, max input/output tokens, request timeouts and a concurrency limit of 1 to 2. Cost stays within the owner's hard cap on a paid key (AI-015); a free tier may still be used for synthetic-data evals. On quota exhaustion the feature degrades gracefully. | M | Impl (backend, see [ai/](ai/)); the paid-key hard cap is AI-015 (Plan) |
| AI-010 | Data sent to third-party LLMs: the user is told which provider is configured. Contact names/phones are redacted by default. A local model (Ollama) is supported for full privacy. Prompt/response logging is off by default. | M | Impl (Sprint 3, C-13; closed by lead decision: redaction tests TC-AI-15 pass in the Backend workflow on `6a348cc`; the real Gemini eval run 35720654442 confirms the AI paths work with the redactor in place (its fixtures hold no contact data, so it is a no-regression check, not a redaction test)): provider disclosure, Ollama option and logging off; `ContactRedactor` keeps the contact name and phone out of the embedding text, Ask context, citations and agent/MCP tool results ([02](02-threat-model.md) F-30 Fixed, [ai/](ai/ai-design.md) §9.1). Pasted listing text for extraction is sent as the user gave it. |
| AI-011 | Embeddings are stored with a foreign key to their source and are deleted/re-computed when the source changes or is deleted (PRV-005). | M | Impl (backend, see [ai/](ai/)) |
| AI-012 | An eval suite (golden Q&A, citation accuracy, injection cases) must pass agreed thresholds before an AI feature is enabled by default in a release (see 06 section 8). | S | Part (golden set in `ai/evals/`; first real Gemini run 2026-09-22, Actions run 35720654442: 12/13 cases passed, all metrics pass except `citationPrecision` 0.86 vs 0.90, E-03 in [10](10-sprint-log.md) §5.2; AI stays off by default until a run passes every metric) |
| AI-013 | **Cloud AI access.** Server-side AI (Ask, planner, listing extraction, custom export) is available only to the owner and to Google accounts the owner invites. Guests and signed-in users who are not invited get no cloud AI and are not prompted to sign in for AI. Until Sprint 5 the owner's API-key install is the only cloud AI user. ([11](11-feature-parity-and-export-spec.md) D-21, 5.13) | M | Plan (Sprint 5, S5-07). Today: only the owner has access through the API key. |
| AI-014 | **On-device AI for guests.** On Android devices that support it, "Improve with AI" and the AI custom export run on-device with Gemini Nano through the ML Kit GenAI Prompt API (beta), after a runtime feature-status check and an optional model download on Wi-Fi. On unsupported devices and in the web app/PWA, AI entry points are hidden for users without cloud access. Every feature works without AI. | S | Plan (Sprint 5, S5-07 spike) |
| AI-015 | **Hard cost cap.** Cloud AI runs on the owner's paid key in a Google Cloud project used only for AI, capped in three layers: (1) a per-user daily quota and a global daily cap in the app; (2) a Google Cloud **spend cap budget** (Preview) on that project and the AI service, which blocks new AI usage when the monthly target is passed (monthly, counted before credits, not instant); (3) budget alerts at 50/90/100 %, which only notify. A Quotas-page limit is used only if the model exposes an adjustable one. When the in-app cap is reached, cloud AI pauses until the next day; when the spend cap trips, until the owner lifts it or the month ends (the API response for that case: AI-017). On-device AI keeps working in both cases. See [08](08-operations-runbook.md) §10.2. | M | Plan (Sprint 5). Today AI-009 limits apply. |
| AI-016 | **Two active providers.** Vertex AI and the Gemini API (AI Studio key) are both supported and chosen by configuration only (`AI_PROVIDER` = `aistudio`, the default, or `vertex`); the AI Studio code path is kept so the owner can switch back. Vertex AI authenticates only with Application Default Credentials (Workload Identity Federation in CI, the attached service account on Cloud Run, `gcloud auth application-default login` locally); no Vertex API key is supported. A provider quota error (HTTP 429 / `RESOURCE_EXHAUSTED`) from either provider returns `503` with `code: AI_QUOTA_EXHAUSTED` and `Retry-After: 60`, and a re-index or eval run stops at the first one instead of spending more calls. Setup in [ai/vertex-setup.md](ai/vertex-setup.md); credential rules in [07](07-secure-build-and-deploy.md) §4 and [02](02-threat-model.md) T-I22. Users never supply their own key (bring-your-own-key rejected, 11.3). | S | Part (code landed 2026-09-22: `AI_PROVIDER` switch, `VertexAiConfiguration`, `VertexEmbeddingModel`, quota path; waiting for the first CI run and the owner's [ai/vertex-setup.md](ai/vertex-setup.md) steps 8 and 10: model availability in `asia-south1` and whether the trial credit pays for Vertex AI) |
| AI-017 | **Spend cap trip is a clear, final state.** When the Google Cloud spend cap budget (AI-015) blocks the AI service, the backend recognises the provider's response, returns a problem that clients show as "Cloud AI is paused" (not as a generic outage or a quota that clears in a minute), does **not** retry the call (no SDK or embedding retries, no `Retry-After` suggesting a quick retry, `retryable: false`), and the re-index and the eval harness stop at the first one. The response shape (HTTP status and google.rpc reason, for example `403` with a billing reason, or `429`) is captured from a real trip or Google's documentation and pinned in a contract test next to the `AI_QUOTA_EXHAUSTED` tests. Today the `AI_QUOTA_EXHAUSTED` path covers HTTP 429 only; any other provider error is a generic `503` with `retryable: true`. On-device AI keeps working. | M | Plan (Sprint 4 candidate with C-24, [10](10-sprint-log.md)) |

## 11. Constraints, assumptions, out of scope

### 11.1 Constraints

| ID | Constraint |
|---|---|
| CON-01 | **Zero running cost.** Only free tiers: Oracle Cloud Always Free, Render/Koyeb for the API; Supabase/Neon for Postgres + PostGIS (+ pgvector); Cloudflare Pages/Netlify for the web; OpenFreeMap tiles; Nominatim; Android Geocoder; GitHub Actions; Ollama or an AI free tier for synthetic-data evals. **One exception (product owner, 2026-09-22):** cloud AI for the owner and invited users runs on the owner's paid, hard-capped key (AI-015); users never pay. A time-limited Google Cloud trial credit may fund Vertex AI, Test Lab and a staging backend ([10](10-sprint-log.md)). |
| CON-02 | The APK is sideloaded. Play Store publishing ($25 one-time) is deferred. |
| CON-03 | Stack: Java 25, Spring Boot 4.1.1 (embedded Tomcat overridden to 11.0.25), Flyway, PostGIS; Kotlin, Compose, Room, WorkManager, minSdk 26 / targetSdk 36 / compileSdk 37; Angular 22, MapLibre GL 6.10 (web), MapLibre Android 13. Node is a build tool for the web app only (ADR-06). |
| CON-04 | Free-tier limits: sleeping instances (cold start 30 to 60 s), about 500 MB DB, projects paused after inactivity (Supabase), GitHub Actions minutes (unlimited for public repos, 2 000 min/month for private). |
| CON-05 | Third-party policies: Nominatim (at most 1 req/s, no bulk use), OpenFreeMap fair use, LLM free-tier terms (the free AI Studio tier may use prompts to improve Google products, so real user data goes only to a paid tier or Vertex AI, PRV-022; AI-010 discloses the provider). |

### 11.2 Assumptions

| ID | Assumption |
|---|---|
| AS-01 | There is one user. Everyone holding the key is trusted equally. |
| AS-02 | The phone has a screen lock and file-based encryption (default on Android 10+). |
| AS-03 | Phone clocks are roughly correct (NTP), because LWW depends on them. |
| AS-04 | Google Play services are present (fused location, Geocoder). Without them, street alerts are disabled but house alerts still work. |
| AS-05 | Under 1 000 houses per hunt, so the in-memory nearby check on the phone is enough. |

### 11.3 Out of scope

Multi-tenant accounts, sharing links, iOS, push notifications from the server, scraping property portals, payments, bring-your-own AI key (rejected 2026-09-22: consumer UX, payment-linked secret risk, support burden), legal/title verification, turn-by-turn navigation, offline tile packs (Could, later).

## 12. Requirements traceability matrix

Design sections refer to [03-design.md](03-design.md). Tests refer to [06-test-plan.md](06-test-plan.md). "Gap" means no test exists yet.

| Req | Design | Code module(s) | Test(s) |
|---|---|---|---|
| FR-001 | 03 §7.1 | android `ui/MapScreen.kt` (Save house here, long-press), web `pages/map`, `core/models.ts newHouse` | TC-M-01, TC-F-01 |
| FR-002 | 03 §6, §9 | backend `house/HouseDto`, `House`; android `data/Models.kt`; web `core/models.ts` | TC-I-03, TC-I-06 |
| FR-003 | 03 §6 | `HouseDto.rating @Min(1) @Max(5)` | TC-I-06 |
| FR-004 | 03 §6 | `house_checklist`, `Checklist.items`, `CHECKLIST` | TC-I-03, TC-U-05, TC-U-19 |
| FR-005 | 03 §6.3 | `HouseEntity.score`, web `houseScore()` | TC-U-05 (`ChecklistScoreTest`), TC-U-19 (`models.spec.ts`) |
| FR-006 | 03 §8.1 | `HouseStatus` (backend, android, web) | TC-I-03, TC-M-03 |
| FR-007 | 03 §7.4 | `photo/PhotoService`, `ImageSanitizer`, android `Repository.addPhoto`, web `image-resize.ts` | TC-I-08, TC-I-09, TC-U-11, TC-U-14 |
| FR-008 | 03 §7.3 | `visit/VisitController`, `Repository.markVisitedNow` | TC-I-07 |
| FR-009 | 03 §4.2 | `MapScreen.kt addHouseLayers`, web `map-page` | TC-M-02 |
| FR-010 | 03 §4.2 | `HouseListScreen.kt` | TC-M-03 |
| FR-011 | 03 §4.2 | `CompareScreen.kt`, web `compare-page` | TC-M-04 |
| FR-012 | 03 §10 | `HouseService.delete/purge`, `VisitController.delete` | TC-I-05, TC-I-16 |
| FR-013 | 03 §7.2, §8.2 | `location/HuntService.checkNearbyHouses` | TC-U-07, TC-F-02 |
| FR-014 | 03 §7.2 | `HuntService.checkStreet`, `location/StreetAlerts`, `ReverseGeocoder` | TC-U-07 (`StreetAlertsTest`), TC-F-03 |
| FR-015 | 03 §7.3 | `location/StayDetector`, `HuntService.onStayStarted/Ended` | TC-U-01..03, TC-F-04 |
| FR-016 | 03 §8.2 | `HuntService.onLocation` accuracy gate | TC-U-08, TC-F-05 |
| FR-017 | 03 §8.2, ADR-01 | `HuntService`, `Notifications.CHANNEL_HUNT`, manifest `foregroundServiceType=location` | TC-F-01, TC-F-07 |
| FR-018 | 03 §4.2 | `HuntState`, `MapScreen.HuntCard` | TC-F-02 |
| FR-019 | 03 §10 | `data/AppDatabase`, `Repository` | TC-F-08 |
| FR-020 | 03 §10, 09 §5 | `data/SyncWorker`, `data/RetryInterceptor` | TC-F-08, TC-U-17 |
| FR-021 | 03 §7.1, §10 | `Repository.sync`, `data/SyncRules`, `PhotoController.changes` | TC-U-06 (part), TC-I-05, TC-I-17 |
| FR-022 | 03 §10 | `HouseService.upsert`, `VisitController.upsert`, `SyncVersions`, `Repository.sync`, `data/SyncRules.keepLocal` | TC-I-04, TC-U-06 (`SyncRulesTest`), TC-I-14 |
| FR-023 | 03 §10 | `SettingsStore.saveServer` | TC-U-06 |
| FR-024 | 03 §4.3 | web `pages/*`, `core/config.*`, `core/api.interceptor.ts` | TC-M-05, TC-U-09, TC-U-19, TC-U-20 |
| FR-025 | 03 §4.3 | web `core/geocode.service.ts` | TC-M-05 |
| FR-026 | 03 §11 | `HouseRepository.findNearby` | TC-I-03, TC-I-13 |
| FR-027 | 03 §11 | `HouseRepository.findLiveOnStreet` | TC-I-03, TC-I-19 |
| FR-028 | 03 §9 | `StatsController` | TC-I-07 |
| FR-029 | 03 §9 | actuator config in `application.yml` | TC-I-02 |
| FR-030 | 03 §4.2 | `data/Settings.kt`, `data/ServerUrl.kt`, `ui/SettingsScreen.kt` | TC-M-06, TC-U-15 |
| FR-031 | 03 §9 | `privacy/DataController`, `DataService.export` | TC-I-18 |
| FR-032 | 03 §9 | `privacy/DataController`, `DataService.deleteAll` | TC-I-18 |
| FR-033 | 03 §7.3 | `MainActivity.handle`, `Root.kt` deep links | TC-F-04, TC-S-12 |
| FR-034 | 03 §10 | android `Repository.deletePhoto/sync`, backend `PhotoService.delete` | TC-I-17, TC-F-08 |
| FR-035 | 09 §3 | `SyncWorker`, `Repository.sync(photosAllowed)`, `Settings.photosOnWifiOnly` | TC-F-10 |
| FR-036 | 05 §8.2 | `res/values*/strings.xml`, `i18n/AppLocale.kt`, `res/xml/locales_config.xml`; web `i18n/*` | TC-L-01, TC-L-05, TC-U-21 |
| FR-037 | 03 §7.5 | web `pages/ask`, android `ui/AssistantScreen.kt` | TC-M-09, TC-AI-01..03 |
| FR-038 | 03 §7.6 | web `house-detail-page` import, android `HouseEditScreen.PasteListingDialog` | TC-M-09, TC-AI-05 |
| FR-039 | 03 §7.7 | web `pages/plan`, android `AssistantScreen.PlanPane` | TC-M-09, TC-AI-07 |
| FR-040 | 09 §2 | `HuntService.requestUpdates/stopIfBatteryLow` | TC-F-06, TC-F-09 |
| FR-041 | 05 §4 | `ui/Theme.kt`, `res/values-night` | TC-A-06 |
| NFR-001 | 03 §11 | GIST indexes in `V1__init.sql` | TC-P-01 |
| NFR-002 | 03 §5 | `ApiClient` read timeout 90 s | TC-P-02 |
| NFR-003 | 03 §7.2 | `HuntService.checkNearbyHouses` | TC-P-03 |
| NFR-004 | 03 §10 | Room + WorkManager | TC-F-08 |
| NFR-005 | 03 §8.2, ADR-01 | `LocationRequest` settings | TC-F-06 |
| NFR-006, NFR-007 | 05 | see 05; web `i18n/translation.service.ts`, dictionaries | TC-A-01..05, TC-L-01..04, TC-U-21 |
| NFR-008 | 03 §5, 08 | deployment, backups | TC-O-01 (restore drill) |
| NFR-009 | 03 §6, ADR-08 | `photo` table | TC-P-04 |
| NFR-010 | 03 §5 | `Dockerfile` JVM flags, Hikari pool | TC-P-02 |
| NFR-011 | 03 §5 | `docker-compose.yml` | CI build |
| NFR-012 | 03 §4 | `geocode.service.ts`, `HuntService.checkStreet` | TC-U-07, review |
| NFR-013 | 06 | CI (07): `.github/workflows/*` | CI |
| NFR-017 | 09 | `RetryInterceptor`, `ApiClient`, `NetworkState` | TC-U-17, TC-S-13 |
| NFR-018 | 09 §7 | `server.compression`, `Settings.photosOnWifiOnly` | TC-F-10 |
| NFR-019 | 03 §6 | `PhotoService`, `MAX_PHOTOS_PER_HOUSE` | TC-I-17 |
| NFR-020 | 05 §7.1 | `ui/*.kt` semantics | TC-A-03, TC-A-04 |
| SEC-001, SEC-003 | 03 §12 | `config/ApiKeyFilter`, `RequestPaths`; web `core/api.interceptor.ts` (key only to the API) | TC-I-01, TC-I-15, TC-U-12, TC-U-18, TC-U-20, TC-S-08, TC-S-10 |
| SEC-002 | 03 §12 | `ApiKeyFilter.validateKeys` (`MIN_KEY_LENGTH` 32), called by the `config/WebConfig` constructor | TC-U-18, TC-I-10b |
| SEC-004 | 03 §12 | `network_security_config.xml`, `ServerUrl` | TC-U-15, TC-S-07, TC-S-09 |
| SEC-005 | 03 §12 | `WebConfig.corsFilter` | TC-I-11 |
| SEC-006 | 03 §9 | DTO validation, `HouseController.nearby` | TC-I-06, TC-I-13 |
| SEC-007 | 03 §7.4 | `PhotoService.upload`, `ImageSanitizer` | TC-I-08, TC-I-17, TC-U-14 |
| SEC-008 | 03 §12 | `ApiRateLimitFilter`, `ApiKeyFilter` failure bucket, `AiRateLimitFilter` | TC-U-12, TC-I-12 |
| SEC-009, SEC-013, SEC-014 | 07 | CI (`security.yml`), `.gitleaksignore` (reviewed fingerprints only), `backend/pom.xml` `tomcat.version` override | TC-S-01..03 |
| SEC-010, SEC-011 | 03 §12 | `ApiKeyCipher`, `Settings.kt`, `data_extraction_rules.xml`, web `config.service.ts` | TC-S-07, TC-M-06, TC-M-11, TC-U-19 |
| SEC-012 | 03 §12, 07 | `SecurityHeadersFilter`, `web/public/_headers` | TC-I-20, TC-S-04 |
| SEC-015, SEC-016 | 03 §12 | `common/ApiExceptionHandler` | TC-S-04, review |
| SEC-017 | 08 §5.1, 07 §7 | `ApiKeyFilter` (list of current + next key), `AppProperties.apiKeyNext`, `application.yml` `app.api-key-next` | TC-U-18, TC-I-21, TC-O-02 |
| SEC-018 | 07 §5 | `app/build.gradle.kts` (`signingConfigs.release` from `HH_*`), `android.yml` job `release` | TC-S-06, TC-S-15 |
| SEC-019 | 07 §6 | DB setup | Review |
| SEC-020 | 03 §10 | `sync/ClientClock` | TC-I-10, TC-U-13 |
| SEC-021, SEC-022 | 03 §12 | `MainActivity`, `Notifications` | TC-S-06, TC-M-07 |
| SEC-023 | 03 §9 | `application.yml` management | TC-I-02 |
| SEC-024 | 07 | `backend/Dockerfile`, `backend/db/Dockerfile`, `docker-compose.yml` | TC-S-05, TC-S-14 |
| SEC-026..SEC-030 | 03 §12, 07, 09 | `RequestSizeLimitFilter`, `SyncVersions`, `ApiClient`, `docker-compose.yml`, workflows | TC-I-12, TC-I-14, TC-S-13, CI |
| PRV-001..003 | 03 §7.2, 04 §4 | `HuntService`, manifest permissions | TC-F-07, TC-S-07 |
| PRV-004, PRV-005 | 08 §6 | `privacy/DataService`, `HouseService.purge`, V3 migration | TC-I-16, TC-I-18 |
| PRV-008 | 03 §7.4 | `Repository.addPhoto`, `image-resize.ts`, `ImageSanitizer` | TC-U-11, TC-U-14 |
| PRV-009, PRV-010, PRV-011 | 04 §6, 07, 08 | config / ops | Review |
| PRV-022, PRV-023 | 02 T-I20, [11](11-feature-parity-and-export-spec.md) 5.13, [ai/vertex-setup.md](ai/vertex-setup.md) | AI provider configuration; Android on-device AI (planned) | Review; TC-U-34, TC-S-21 when accepted into 06 |
| AI-001..AI-012 | 03 §13, [ai/](ai/) | `backend/.../ai/**`, web `core/ai.service.ts`, `pages/ask`, `pages/plan`, android `AssistantScreen.kt`; eval harness `backend/src/test/.../ai/eval/`, `docs/ai/evals/golden-set.json` | TC-AI-01..08 (measured by TC-AI-09/10), TC-AI-11 (native Gemini embeddings), TC-AI-12 (indexing failures, reindex 503; AI-011), TC-AI-13 (scorecard verdict; AI-012), TC-AI-14 (AI-001 embedding provider selection), TC-AI-15 (contact redaction; AI-010), TC-AI-16 (provider wire-format contract tests), TC-AI-17 (Ask prompt citation and contrast rules; AI-002, AI-003), TC-M-09 |
| AI-013..AI-015 | [11](11-feature-parity-and-export-spec.md) 5.13, 02 T-D4, T-I22, [08](08-operations-runbook.md) §10.2 | planned: AI allowlist, quota and cap in `backend/.../ai/**`, Android on-device AI | Planned TC-U-34, TC-S-21 ([11](11-feature-parity-and-export-spec.md) §13) |
| AI-016 | [ai/ai-design.md](ai/ai-design.md) 2.1, 3.3, [ai/vertex-setup.md](ai/vertex-setup.md), 03 §13, 02 T-I22, 07 §4 | `backend/.../ai/config/AiProperties.java` and `AiDefaultsEnvironmentPostProcessor.java` (`app.ai.provider`), `backend/.../ai/vertex/**` (`VertexAiConfiguration`, `GoogleAccessTokenSource`, `VertexEndpoints`), `backend/.../ai/embedding/VertexEmbeddingModel.java`, `backend/.../ai/ProviderErrors.java`, `backend/.../ai/web/AiExceptionHandler.java`, `HouseIndexer` (quota stop, `AI_INDEX_ON_CHANGE`), `.github/workflows/ai-evals.yml` (`provider` input, WIF) | TC-AI-16 (Vertex chat and embedding contract tests), TC-AI-18 (provider error classification), TC-AI-19 (Vertex settings and auto-configuration), TC-AI-20 (provider selection), TC-AI-21 (re-index and eval stop on quota); TC-AI-10 real run against Vertex AI pending |
| AI-017 | AI-015, [08](08-operations-runbook.md) §10.2 and IR-9, [10](10-sprint-log.md) C-24 | planned: `ProviderErrors` / `AiExceptionHandler` extension (spend cap reason next to `AI_QUOTA_EXHAUSTED`) | Planned: contract test in TC-AI-16 with the captured spend cap response; not yet in 06 |
