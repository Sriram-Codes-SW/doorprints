# 06: Test plan

| Field | Value |
|---|---|
| Document | Test plan (functional, security, accessibility, i18n, AI) |
| Version | 0.3 |
| Date | 2026-09-22 |
| Author | Claude (Cowork) |
| Status | Draft |

## Change log

| Version | Date | Author | Change |
|---|---|---|---|
| 0.1 | 2026-09-22 | Claude (Cowork) | First version. Lists the 7 existing backend integration tests. Defines unit, integration, field, security, a11y, i18n and AI eval cases. |
| 0.2 | 2026-09-22 | Claude (Cowork) | Wave 2: new unit tests TC-U-12..17 (backend and Android), integration tests TC-I-15..20, statuses of TC-I-08..13 updated, new manual/field cases for captive portals, Wi-Fi-only photos, battery auto-stop, language picker, dark theme and the AI UI. CI now runs the automated tests (07). |
| 0.3 | 2026-09-22 | Claude (Cowork) | First CI run fixes: backend tests no longer contain API-key literals (ApiIntegrationTest supplies a per-run random key via `@DynamicPropertySource`, ApiKeyFilterTest generates one too), so TC-S-03 gitleaks has nothing to flag in the tree. Jackson 3 (Boot 4) configured not to reject JSON that omits primitive fields (`deleted`, `syncVersion`), which every TC-I sync call relies on. |

Related: [Requirements](01-requirements.md) · [Threat model](02-threat-model.md) · [Design](03-design.md) · [UX/a11y/i18n](05-ux-accessibility-i18n.md) · [Build and deploy](07-secure-build-and-deploy.md) · [AI docs](ai/)

---

## 1. Strategy

| Level | What | Tooling (all free) | Runs | Status |
|---|---|---|---|---|
| Unit | Pure logic: StayDetector, Geo, score, LWW merge, DTO mapping, URL normalisation | JUnit 4 (Android local tests), JUnit 5 (backend), Vitest (Angular 21+ default) | Every push (CI) | **Gap**: no Android/web unit tests yet |
| Integration | API + real PostGIS: auth, validation, LWW, change feed, geospatial, photos | Spring Boot test + `RestClient` against a PostGIS service container (`postgis/postgis:17-3.5`). Testcontainers as a local option. | Every push (CI) | 7 tests exist (`ApiIntegrationTest`) |
| Android instrumentation | Room DAO queries, WorkManager sync with MockWebServer, Compose UI | AndroidX Test, Room in-memory, `work-testing`, OkHttp MockWebServer, Compose UI test, emulator in CI (`reactivecircus/android-emulator-runner`) | Later (nightly / pre-release) | Planned |
| Manual UI | Screens, deep links, permissions | Checklists in section 5 | Pre-release | Planned |
| Field | Hunt mode walk test | Script in section 6 | Pre-release + after location changes | Planned |
| Security | SAST, SCA, secrets, container, DAST, mobile static | Semgrep OSS, OWASP Dependency-Check, Trivy, npm audit, gitleaks, OWASP ZAP baseline, MobSF, Android Lint | CI + pre-release | Planned (see 07) |
| Accessibility | WCAG 2.2 AA, TalkBack | axe-core (Playwright), Lighthouse, Android Accessibility Scanner, TalkBack manual | Pre-release | Owned with the design team ([05](05-ux-accessibility-i18n.md)) |
| i18n | en/hi/ta/te, pseudo-locales, number formats | Android pseudo-locales, Angular i18n extraction, manual review | Pre-release | Owned with the design team |
| AI evals | Grounding, citations, injection, extraction accuracy | Golden set + JUnit/Spring AI eval harness (AI team) | Before enabling AI by default, and on model/prompt change | Planned ([ai/](ai/)) |

Principles: test risk-first (threats with risk ≥ 6 in 02 need a test), keep external services out of automated tests (fake the Geocoder and the LLM, no Nominatim calls), use fixture data with no real personal data.

## 2. Test environments

| Env | Components | Data |
|---|---|---|
| Local | `docker compose up` (PostGIS + API), `ng serve`, Android emulator (`10.0.2.2:8080`, debug build only) | Fixtures |
| CI | GitHub Actions: PostGIS service, API on a random port, web build, APK build | Fixtures |
| Staging (optional) | A second free Render service + a Neon branch | Synthetic data |
| Field | Release APK on the user's phone against prod | Real data. Remove test houses afterwards. |

## 3. Unit tests

| ID | Target | Case | Req |
|---|---|---|---|
| TC-U-01 | `StayDetector` | Fixes within 40 m for at least `minStayMs` produce exactly one `Started` with mean position and `since = anchorTime` | FR-015 |
| TC-U-02 | `StayDetector` | After `Started`, a fix more than 40 m away produces `Ended(arrivedAt, leftAt = lastInside)` and resets | FR-015 |
| TC-U-03 | `StayDetector` | Jitter of 35 m does not reset. Leaving before `minStayMs` produces no event. `minStayMs` changes take effect. | FR-015 |
| TC-U-04 | `Geo.distanceM` | Known pairs (for example 0.00018° lat ≈ 20 m) within 0.5% | FR-013 |
| TC-U-05 | `HouseEntity.score` / `houseScore()` | Checklist only, rating only, both (50/50), none gives null. Android and web give the same results. | FR-005 |
| TC-U-06 | `Repository.sync` merge (with a fake `ApiClient`) | Dirty local newer is kept. Incoming newer overwrites. Tombstone applied. Cursor = max syncVersion. `markClean` skipped if edited during sync. Server URL change resets cursors. | FR-021..023 |
| TC-U-07 | `HuntService` alert logic (extract to a pure class) | House alert cooldown 30 min, street alert cooldown 60 min, geocode throttle 45 s / 80 m | FR-013, FR-014, NFR-012 |
| TC-U-08 | Accuracy gate | Fix with accuracy 51 m triggers no alert and no stay update | FR-016 |
| TC-U-09 | Web `normalizeBaseUrl`, `ConfigService` | Trailing slashes trimmed, whitespace trimmed, corrupt storage gives null | FR-024 |
| TC-U-10 | DTO mapping (`Api.kt`) | Epoch ms ↔ ISO-8601 round trip. Unknown status gives NEW. | FR-021 |
| TC-U-11 | Photo pipeline | Output at most 1600 px. EXIF rotation applied. **EXIF GPS tag absent** in the output (Android `Repository.addPhoto`, web `resizeImage`). | FR-007, PRV-008 |
| TC-U-12 | Backend `ApiKeyFilterTest` | 17 non-canonical paths get 400 even with the key; every path outside the allowlist needs the key; health is public for GET/HEAD only; only real CORS preflights skip the key; Bearer accepted; wrong keys throttled to 429 while the right key still passes | SEC-001, SEC-008, F-20 |
| TC-U-13 | Backend `ClientClockTest` | Null = now; past and small skew kept; +5 h and +30 days clamped to now; 2030 and 1999 rejected; event times validated but not clamped | SEC-020, F-08 |
| TC-U-14 | Backend `ImageSanitizerTest` | JPEG: Exif (GPS), COM and trailer removed, JFIF/ICC and image data kept, idempotent. PNG: `tEXt`/`eXIf` and trailing bytes removed. WebP: `EXIF`/`XMP ` removed, VP8X flags cleared, RIFF size fixed. HTML/SVG, truncated and null input rejected | SEC-007, PRV-008, F-07 |
| TC-U-15 | Android `ServerUrlTest` | HTTPS accepted; `http://` only for localhost/10.0.2.2; LAN and public `http://` refused; junk, user-info, query strings refused | SEC-004, F-02 |
| TC-U-16 | Android `SyncOutcomeTest` | Stored form round-trips; v0.1 free text ignored; errors classified (auth, captive portal, rate limit, server, network) without server text | SEC-015, F-12 |
| TC-U-17 | Android `RetryInterceptorTest` | 503/502 retried then success; gives up after 3 attempts; network errors retried for idempotent calls; plain POST not retried, POST tagged `Idempotent` retried; 401 not retried; backoff jittered and capped at 15 s | NFR-017, FR-020 |

## 4. Integration tests (backend + PostGIS)

Existing tests are in `backend/src/test/java/com/househunt/ApiIntegrationTest.java`. They need `DB_URL` pointing at PostGIS (CI service container).

| ID | Test method / case | Expected | Req | Status |
|---|---|---|---|---|
| TC-I-01 | `rejectsRequestsWithoutKey` | 401 without the key | SEC-001 | Exists |
| TC-I-02 | `healthIsPublic` | `/actuator/health` UP without the key, no details | FR-029, SEC-023 | Exists |
| TC-I-03 | `createsHouseAndFindsItNearbyAndOnStreet` | ~20 m point found within 50 m, not within 5 m. Street match ignores case. Checklist saved. | FR-002, FR-004, FR-026, FR-027 | Exists |
| TC-I-04 | `olderEditDoesNotOverwriteNewer` | LWW keeps the newer label (timestamps now relative to the current time, no 2030 dates) | FR-022 | Exists (updated) |
| TC-I-05 | `syncReturnsChangesIncludingDeletions` | Feed after the cursor includes the tombstone. The live list excludes it. | FR-012, FR-021 | Exists |
| TC-I-06 | `validatesInput` | Blank label / lat 200 gives 400 ProblemDetail | SEC-006 | Exists |
| TC-I-07 | `recordsVisits` | Visit is stored, listed by house, counted in stats | FR-008, FR-028 | Exists |
| TC-I-08 | Photo upload types | Non-image bytes get 400 (in `photoUploadStripsMetadataAndDeletesSyncAsTombstones`). More than 5 MB gives 413 (manual). | FR-007, SEC-007 | Exists (part) |
| TC-I-09 | Photo idempotency | Same `id` posted twice gives one row (in `photoUploadStripsMetadataAndDeletesSyncAsTombstones`). Unknown house gives 404. | FR-007 | Exists (part) |
| TC-I-10 | `clientClockIsClampedOrRejected` | +3 h clamped to server time (a later normal edit still wins); 2030 gives 400 | SEC-020 | Exists |
| TC-I-10b | Startup key check | Context fails to start with a key shorter than the minimum length | SEC-002 | New |
| TC-I-11 | CORS | Preflight from an allowed origin gives ACAO. From a disallowed origin: no ACAO. 401 responses carry CORS headers. | SEC-005 | New |
| TC-I-12 | Rate limit | Failed-key throttle covered by TC-U-12; body size cap by `rejectsOversizedJsonBodies` (400/413). A general 429 test with a low `app.rate-limit.burst` is still a gap. | SEC-008, SEC-026 | Part |
| TC-I-13 | `nearbyRejectsOutOfRangeParameters` | lat 95 or radius −1 gives 400 | FR-026, SEC-006 | Exists |
| TC-I-14 | Concurrent writes and cursor | Two parallel upserts: a client pulling in between never misses a row. The advisory-lock design is argued in 03 §10.4; the test itself is backlog (OSI-B03). | FR-021, SEC-027 | Gap |
| TC-I-15 | `pathTricksCannotBypassTheKeyFilter` | 10 raw paths (JDK client, no normalisation): 400/401 without the key, 400/404 with it, never 200 | SEC-001, F-20 | Exists |
| TC-I-16 | `deletingAHousePurgesItsContent` | PUT `deleted:true`: tombstone has no notes, phone, street; label empty; photo 404; visit unlinked | FR-012, PRV-005, F-16 | Exists |
| TC-I-17 | `photoUploadStripsMetadataAndDeletesSyncAsTombstones` | Stored JPEG has no GPS Exif; retry with the same id stores once; delete gives a tombstone in `GET /api/photos?since=`; non-image 400; 4th photo over a cap of 3 gives 409 | FR-007, FR-034, F-06, F-07, F-15 | Exists |
| TC-I-18 | `exportContainsLiveDataAsAnAttachment`, `deleteAllNeedsTheConfirmationHeader` | Export is an attachment with houses/visits/photos; delete-all without or with a wrong header gives 428, with the header wipes houses including tombstones | FR-031, FR-032, PRV-004 | Exists |
| TC-I-19 | `indicTextRoundTripsEndToEnd` | Telugu label, Hindi notes and a Tamil street round-trip through JSON, the query string and Postgres; street search finds it | NFR-007, 09 §7 | Exists |
| TC-I-20 | `securityHeadersAndNoStoreOnJson` | `nosniff`, `no-store`, CSP `default-src 'none'`, `Referrer-Policy: no-referrer` on JSON | SEC-012, F-10 | Exists |

## 5. Manual UI and instrumentation checks

| ID | Case | Req |
|---|---|---|
| TC-M-01 | Save house here: with GPS, with no permission (prompt + long-press tip), long-press map | FR-001 |
| TC-M-02 | Map markers coloured by status. Tap opens the house. The location dot appears after permission. | FR-009 |
| TC-M-03 | List search/filter/sort. Status change is reflected in counts. | FR-006, FR-010 |
| TC-M-04 | Compare: fewer than 2 selected shows the hint. 4 max. Tap a column to open. | FR-011 |
| TC-M-05 | Web: Connect (wrong key gives a clear error, HTTP API from an HTTPS page is blocked), map, detail edit, photo upload, Nominatim fill, compare | FR-024, FR-025 |
| TC-M-06 | Android settings: masked key, Test connection, Sync now shows the result, radius/stay values used by Hunt mode | FR-030 |
| TC-M-07 | Lock-screen notification content is redacted ("House Hunt alert" only) | SEC-022 |
| TC-M-08 | Android: add a 21st photo (limit message), pick a non-image file (unreadable message), delete a photo in airplane mode then reconnect (gone on the web after sync) | FR-034, NFR-019 |
| TC-M-09 | AI UI with `APP_AI_ENABLED=true`: Ask shows an answer with numbered links to houses; Import from listing fills the new-house form and shows warnings; Plan visits lists stops in order and draws them on the map (web). With AI off, none of these entry points is visible on web or Android. Errors 429/503 show translated messages. | FR-037..FR-039, AI-001 |
| TC-M-10 | Web confirm dialog: delete a photo/visit/house, leave with unsaved changes, disconnect. The dialog is in the app language, Esc cancels, focus returns to the button that opened it. Map popup: Esc dismisses it; it stays while the pointer moves onto it | A11Y-B01, A11Y-B05 |
| TC-M-11 | Web Connect: with "Remember on this device" off, closing the tab forgets the key; with it on, the key survives a browser restart | SEC-010, F-04 |

## 6. Field test: Hunt mode walk test

**Setup:** release APK, prod or staging server, alert radius 30 m, min stay **2 min** (for the test), battery ≥ 80%, screen off during the walk. Beforehand, save 3 houses (H1, H2, H3) on street S1 about 100 m apart. S2 is a street never visited.

| Step | Action | Expected | TC |
|---|---|---|---|
| 1 | Start Hunt mode from the map card. Grant fine location + notifications. | Ongoing "Hunt mode is on" notification with a Stop action. The card shows the street and accuracy. | TC-F-01 |
| 2 | Walk past H1 on the pavement (within 30 m) | Alert "You've seen this one: H1" within about 15 s of passing. Tap opens H1. | TC-F-02 |
| 3 | Walk back past H1 within 30 min | No second alert (cooldown) | TC-F-02 |
| 4 | Turn into S1 from another street | "You've been on S1 before, 3 saved houses…" once | TC-F-03 |
| 5 | Walk into S2 | No street alert. The card shows S2 with 0/0. | TC-F-03 |
| 6 | Stand still inside a new building (no saved house) for more than 2 min | "Are you at a house?" notification. Tap opens a new-house form with the location filled in. AUTO visit recorded. | TC-F-04 |
| 7 | Walk away more than 40 m | The visit gets `leftAt` (check the house's visit list after saving) | TC-F-04 |
| 8 | Go indoors / under a flyover where accuracy is worse than 50 m | Card shows "Weak GPS, alerts paused". No false stay/alerts. | TC-F-05 |
| 9 | Walk for 60 min with the screen off | Battery drop recorded. Target ≤ 8%/h (NFR-005). | TC-F-06 |
| 10 | Swipe the app away from recents. Also leave the phone idle for 20 min (Doze). | Service keeps running, or stops cleanly with a visible notification. No crash. Record behaviour on Android 14+ (R-01). Note: Android 14+ lets users dismiss FGS notifications. Check the service still shows in the task manager. | TC-F-07 |
| 11 | Airplane mode: save a house with 2 photos, edit another, then turn airplane mode off | Everything works offline. Within about 1 min of reconnecting, the web app shows the changes and photos. | TC-F-08 |
| 12 | Stop Hunt mode | Notification removed, location updates stop (the location indicator in the status bar disappears) | TC-F-01, PRV-002 |

Additional field cases (wave 2):

| ID | Action | Expected | Req |
|---|---|---|---|
| TC-F-09 | Run Hunt mode with the battery at 16%, unplugged, until it reaches 15% | The service stops and posts "Hunt mode stopped: battery low (15%)" | FR-040 |
| TC-F-10 | "Photos only on Wi-Fi" on; on mobile data add 2 photos; then join Wi-Fi | Houses sync at once; Settings shows "Photos waiting for Wi-Fi: 2"; photos upload within minutes of joining Wi-Fi | FR-035 |
| TC-F-11 | On a carrier known to be IPv6-only (informational) | Sync works (NAT64/464XLAT) | 09 §4 |

Record: device model, Android version, alerts expected/received, false alerts, battery %/h, and notes. Pass if there are no missed house alerts at ≤ 30 m in good GPS and at most 1 false alert per km.

## 7. Security testing

| ID | Tool / method | Scope | Pass criteria | Threats / req |
|---|---|---|---|---|
| TC-S-01 | **Semgrep OSS** (`p/java`, `p/kotlin`, `p/typescript`, `p/owasp-top-ten`, `p/secrets`) | whole repo | No new ERROR findings. WARN findings triaged. | SEC-014 |
| TC-S-02 | **OWASP Dependency-Check** (Maven plugin / CLI with a free NVD API key), **Trivy fs**, **npm audit --audit-level=high**, Gradle dependency report + Dependabot | backend, android, web | No unfixed Critical/High with a known fix. Exceptions documented with an expiry date. | SEC-013, T-T5 |
| TC-S-03 | **gitleaks** (full history on first run, then on PRs) | repo | No secrets (tests generate their API keys at runtime; none are committed) | SEC-009 |
| TC-S-04 | **OWASP ZAP baseline** (`zaproxy/action-baseline`) against the CI-started API and a preview of the web build | API, web | No High alerts. Headers (SEC-012) present after the fix. No stack traces. | SEC-012, SEC-015 |
| TC-S-05 | **Trivy image** scan of `house-hunt-api` | container | No Critical. Runs as non-root (after F-23). | SEC-024 |
| TC-S-06 | **MobSF** static scan (Docker, local) + Android Lint security checks on the release APK | APK | No cleartext, `allowBackup` false/rules, `debuggable` false, signed with the release cert, exported components reviewed | SEC-004, SEC-011, SEC-018, SEC-021 |
| TC-S-07 | Manifest/config assertion (script in CI) | `AndroidManifest.xml`, network security config | `usesCleartextTraffic` not true in release, backup excluded | SEC-004, SEC-010, SEC-011 |
| TC-S-08 | Auth tests: wrong key, empty key, key with a different length, timing check (informational) | API | 401 in all cases. No difference in response body. | SEC-001, SEC-003 |
| TC-S-09 | TLS check (`testssl.sh` or SSL Labs) on the prod API and web | prod | TLS 1.2+, HSTS, valid cert | SEC-004 |
| TC-S-10 | **Path normalisation bypass**: `curl` without a key to `/api;x=y/houses`, `/%61pi/houses`, `/API/houses`, `//api/houses`, `/api/./houses`, `/api/houses/..;/stats` | API | Every one returns 401/400/404, **never 200 with data** | SEC-001, F-20 |
| TC-S-11 | Upload abuse: 5 MB+ file, polyglot, many concurrent uploads | API | 400/413. No OOM at 512 MB. | SEC-007, T-D3 |
| TC-S-12 | Deep-link fuzz: `adb shell am start -n com.househunt.app/.MainActivity --es openHouse "../x"` and `--ed newLat 999` | Android | No crash. Invalid input is ignored. | SEC-021 |
| TC-S-13 | Captive portal: connect the phone to a Wi-Fi with a sign-in page (or a local proxy that answers every request with a 302 or an HTML 200), then sync | Android | Sync reports "this Wi-Fi needs a sign-in page first"; the proxy log shows no request carrying `X-API-Key` to a redirect target | SEC-028, 09 §3 |

## 8. AI evaluation (AI team to finalise in [ai/](ai/))

Fixture: a synthetic hunt of about 25 houses and 60 visits across 3 localities, with notes in English and some Hindi/Tamil/Telugu, plus 30 labelled Indian listing texts (prices in lakh/crore, "2BHK", "semi-furnished", …).

| ID | Eval | Metric | Proposed threshold | Req |
|---|---|---|---|---|
| TC-AI-01 | Golden Q&A (≥ 40 questions: filters, comparisons, "when did I visit…") | Answer correctness (exact or rubric-checked) | ≥ 85% | AI-002 |
| TC-AI-02 | Citation validity | Cited IDs ⊆ retrieved IDs, and each cited record supports the claim | 100% valid IDs, ≥ 90% supporting | AI-002, AI-003 |
| TC-AI-03 | Unanswerable questions (≥ 10) | Says it doesn't know / no fabricated house | ≥ 90% | AI-003, LLM09 |
| TC-AI-04 | Prompt-injection suite (≥ 25 payloads in notes, listing text and questions: "ignore instructions", tool-call requests, data exfiltration via URLs, system prompt reveal, multilingual payloads) | No write tool called, no system prompt leaked, no out-of-schema output, no other house's contact data revealed | 100% | AI-008, LLM01, LLM06, LLM07 |
| TC-AI-05 | Listing extractor accuracy | Field-level accuracy on price, priceType, BHK, locality, contact | ≥ 90% per field. Invalid values dropped. | AI-004, AI-005 |
| TC-AI-06 | Output handling | HTML/Markdown/script in the model output is rendered as text | 100% | AI-005, LLM05 |
| TC-AI-07 | Limits | Daily quota gives 429. Token/time limits enforced. Agent step limit ≤ 8. Provider down gives a graceful message. | 100% | AI-006, AI-009, LLM10 |
| TC-AI-08 | Deletion | A deleted house never appears in retrieval or answers | 100% | AI-011, LLM08 |

Evals run with a fixed model/version and temperature 0 where the provider allows it. Results are stored as a CI artifact. A regression of more than 5 points blocks the prompt/model change.

## 9. Accessibility, i18n, performance, operations

| ID | Case | Tool | Req |
|---|---|---|---|
| TC-A-01 | Web pages have no serious/critical axe violations | `@axe-core/playwright`, Lighthouse | NFR-006 |
| TC-A-02 | Keyboard-only use of the web (connect, edit, compare). Visible focus. Map has non-map alternatives (list). | Manual | NFR-006 |
| TC-A-03 | TalkBack: every control labelled (for example the tab icons have `contentDescription = null` but the tabs have text labels, so check what is announced). The map FAB and Hunt toggle are announced. Notifications are readable. | TalkBack, Accessibility Scanner | NFR-006 |
| TC-A-04 | Font scale 200%, display size large. No clipped text in the compare table and cards. | Manual | NFR-006 |
| TC-A-05 | Contrast of status colours (NEW #3C5A99, SHORTLISTED #1A7A43, REJECTED #B3261E, star #A86A00) with text/background ≥ 4.5:1 (3:1 for the star glyph), and status not shown by colour alone | Contrast checker | NFR-006 |
| TC-A-06 | Android dark theme: every screen readable, status and star colours from the dark token set, no white flash on start | Manual | FR-041 |
| TC-A-07 | TalkBack: rating announced as a radio group ("3 out of 5 stars, selected"), checklist rows as radio groups, Hunt-mode row as one switch, Compare rows as checkboxes, headings navigable | TalkBack | NFR-020 |
| TC-L-01 | All UI strings come from resources (Android `strings.xml` in 4 languages, Angular dictionaries). Android lint `MissingTranslation`/`ExtraTranslation` are errors (reported in CI); the web build fails on a missing key. | Lint, `ng build` | NFR-007 |
| TC-L-02 | hi/ta/te rendering: fonts, line height, truncation, pseudo-locale `en-XA` expansion | Manual + pseudo-locales | NFR-007 |
| TC-L-03 | Number/currency: ₹ with Indian grouping (1,00,000). Dates follow the locale. | Unit + manual | NFR-007 |
| TC-L-04 | Notifications and Hunt card strings translated | Manual | NFR-007 |
| TC-L-05 | Android language picker: switch to हिन्दी, தமிழ், తెలుగు and back to system default on Android 12 and 14. On 13+ the choice also appears in system Settings > Apps > House Hunt > Language | Manual | FR-036 |
| TC-P-01 | `/api/houses/nearby` and list p95 with 1 000 houses / 5 000 visits | k6 OSS or a JMeter smoke test | NFR-001 |
| TC-P-02 | Cold start time on the free host and memory at 512 MB under 5 concurrent photo uploads | Manual + `docker stats` | NFR-002, NFR-010 |
| TC-P-03 | Nearby check time per fix with 1 000 houses on a mid-range phone | Microbenchmark / log timing | NFR-003 |
| TC-P-04 | DB size after 500 photos, projected against quota | SQL `pg_total_relation_size` | NFR-009 |
| TC-O-01 | Backup restore drill: decrypt the latest dump and restore into a fresh PostGIS, app starts, counts match | 08 section 3 | NFR-008 |
| TC-O-02 | Key rotation drill: rotate, old key gives 401, clients updated | 08 section 5 | SEC-017 |

## 10. Requirement coverage and gaps

The full mapping is the RTM in [01 section 12](01-requirements.md#12-requirements-traceability-matrix). Current gaps:

| Gap | Impact | Action |
|---|---|---|
| Sync merge (TC-U-06) and Hunt alert logic (TC-U-07) have no unit tests | Regressions in core Android logic | Extract to pure classes, then test |
| No web tests | Regressions in the SPA | Add Vitest for `core/*` (`splitCitations`, `ConfigService`, `aiErrorMsg`) and a Playwright smoke + axe test |
| CI added but not yet run (no GitHub access from the build sandbox) | First runs may fail on environment details | Lead runs the workflows and fixes forward |
| TC-I-14 concurrency, general 429 test | F-09 and SEC-008 rely on design review | OSI-B03 |
| AI UI | Manual only (TC-M-09) | Playwright with a stubbed `/api/ai/*` |

## 11. Entry and exit criteria

| Level | Entry | Exit |
|---|---|---|
| Unit / integration (per PR) | Code compiles. PostGIS service available. | 100% pass. No new Semgrep ERROR. No new Critical/High SCA with a fix. gitleaks clean. |
| Release candidate | All PR gates green on `main`. Version bumped. Change log updated. | All TC-I and TC-U pass. TC-S-01..07 and TC-S-10 pass. ZAP baseline has no High. Manual TC-M pass. Field test TC-F-01..08 pass (or deviations accepted in the release notes). a11y: no critical axe issues. Open High findings in 02 either fixed or risk-accepted in writing. |
| AI feature enable-by-default | Feature behind a flag. Eval fixture ready. | TC-AI-01..08 meet the thresholds |
| Production deploy | RC exit met. Backup taken within 24 h. | Health UP. Smoke test (stats, one house CRUD on a test record, then delete + purge). |

## 12. Defect severity

| Severity | Definition | Release rule |
|---|---|---|
| S1 Critical | Data loss/leak, auth bypass, crash on start, Hunt mode unusable | Blocks release |
| S2 High | Wrong alerts, sync conflict losing edits, security finding rated High | Blocks release unless risk-accepted |
| S3 Medium | Feature degraded with a workaround | Fix in the next release |
| S4 Low | Cosmetic | Backlog |
