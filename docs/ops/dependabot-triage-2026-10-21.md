# Dependabot alerts, triage of 2026-10-21

> Session record (not a numbered SSDLC document). It answers [13](../13-release-security-checklist.md) A6 for the 14 open
> Dependabot alerts the audit reported: for each package, where it comes from, whether it reaches something that
> ships, and what was done. Nothing here accepts a risk or approves a release.

| Field | Value |
|---|---|
| Document | Dependabot alert triage of 2026-10-21 ([13](../13-release-security-checklist.md) A6) |
| Date | 2026-10-21 |
| Author | Claude (Code), engineer |
| Code | `origin/main` at `85d672fd`; the fixes are on `fix/dependabot-alerts-2026-10-21` |
| Status | Fixes made and checked locally; the alerts close only after the merge, when the dependency graph is re-submitted (section 4) |

## 1. How this was derived

The session's token cannot read the alerts API (403). The owner pasted the list (14 open alerts, Security > Dependabot,
2026-10-21), and this record uses it as the ground truth. Each package was then traced in the resolved graphs and
looked up in OSV (`api.osv.dev`, which mirrors the GitHub advisories) and Maven Central.

| Ecosystem | How it was read |
|---|---|
| Web | `npm audit` and `npm audit --omit=dev` in `web/` and `tools/live-ui/`; `.github/firebase-tools` has no lock file |
| Android | `./gradlew :app\|:shared\|:ui:dependencies` (every configuration), `buildEnvironment`, `:app:dependencies --configuration androidLintTool`, scanned for each package below |
| Backend | `mvn dependency:tree` (all scopes): none of the Maven packages below is there |

All Maven alerts are reported against `android/settings.gradle.kts`: the `gradle-dependency-graph` job of `security.yml`
(main only) submits the whole Gradle graph, including the plugin classpath (AGP's signing and lint tooling), under that
one manifest. That is why a build tool shows up as an "Android" alert.

## 2. The alerts

| # | Package | Version | Advisory (severity) | Classpath | Ships? | Action |
|---|---|---|---|---|---|---|
| 3 | `org.jdom:jdom2` | 2.0.6 | GHSA-2363-cqg2-863c / CVE-2021-33813 (High, XXE; fixed 2.0.6.1) | Gradle plugin classpath (`buildEnvironment`, AGP 9.4.1) | No | Pinned to **2.0.6.1** |
| 1 | `org.apache.httpcomponents:httpclient` | 4.5.6 | GHSA-7r82-7xv7-xcpj / CVE-2020-13956 (Moderate, XSS; fixed 4.5.13) | `androidLintTool` (via `httpmime`) and the plugin classpath, where it already resolved to 4.5.14 | No | Pinned to **4.5.14** on both |
| 8 | `bcprov-jdk18on` | 1.80.2 | GHSA-9pwp-9qqc-pr26 / CVE-2026-8763 (**Critical**, name constraints bypass; fixed 1.85) | plugin classpath (AGP signing) and `androidLintTool` of `:app`, `:shared`, `:ui` | No | Pinned to **1.85** |
| 9 | `bcprov-jdk18on` | 1.80.2 | GHSA-qp49-qgx5-5m26 / CVE-2026-13506 (High, lazy ASN.1 nesting; fixed 1.85) | as #8 | No | Same pin |
| 6 | `bcprov-jdk18on` | 1.80.2 | GHSA-c3fc-8qff-9hwx / CVE-2026-0636 (Moderate, LDAP injection; fixed 1.84) | as #8 | No | Same pin |
| 5 | `bcpkix-jdk18on` | 1.80.2 | GHSA-wg6q-6289-32hp / CVE-2026-5588 (Moderate; fixed 1.84) | as #8 | No | Pinned to **1.85**, with `bcutil-jdk18on` so the three stay together |
| 2 | `org.apache.commons:commons-lang3` | 3.16.0 | GHSA-j288-q9x7-2f5v / CVE-2025-48924 (Moderate, uncontrolled recursion; fixed 3.18.0) | `androidLintTool` (via `commons-compress`) and the plugin classpath | No | Pinned to **3.18.0** |
| 4 | `org.bitbucket.b_c:jose4j` | 0.9.5 | GHSA-3677-xxcr-wjqv / CVE-2024-29371 (High, DoS; fixed 0.9.6) | plugin classpath only (AGP) | No | Pinned to **0.9.6** |
| 7 | `io.opentelemetry:opentelemetry-api` | not found | GHSA-rcgg-9c38-7xpx / CVE-2026-45292 (Moderate; fixed 1.62.0) | **Not in any graph this session could resolve**: not in the APK, test, lint or plugin classpaths, not in the Gradle cache. The graph that CI submits has a configuration this machine does not resolve (Kotlin/Native or similar, not available offline) | Not on any shipped path found | Pinned to **1.62.0** on the plugin classpath and in every project configuration, as a precaution. **Unproven**: whether it closes #7 depends on the CI graph (section 4) |
| 10 | `source-map-js` | 1.2.1 | GHSA-68fv-2mgg-jv7q (High) | web `devDependencies`, via `postcss` and `sass` | No (build tool, not in `dist/`) | Bumped to **1.2.2** in `web/package-lock.json` |
| 13, 14, 11 | `launch-editor` (reported against `vite`) | via `vite` 8.3.0 | GHSA-587h-7wmj-h5pm (High), GHSA-r825-w8mm-5pxw, GHSA-698v-4r3x-3vcv (Moderate) | web `devDependencies`: `@angular/build` 22.2.3 pins `vite` 8.3.0 exactly | No (dev server only) | `vite` raised to **8.3.3** with an `overrides` entry (and `rolldown` ~1.2.11, which 8.3.3 needs, so there is one hoisted copy); `launch-editor` is no longer in the lock file at all |

**Nothing that ships was affected.** The APK's `releaseRuntimeClasspath` contains none of the Maven packages above, the
backend's runtime jar (all Maven scopes) contains none of them, and `npm audit --omit=dev` found nothing before or after.
The unit-test classpaths already resolved Bouncy Castle 1.85 (Robolectric 4.17). The alerts are about build tooling, so
the fix is a pin, not a library change. No code in the repository calls these libraries.


## 3. What changed

| File | Change |
|---|---|
| `web/package.json`, `web/package-lock.json` | `overrides`: `vite` 8.3.3 and `rolldown` ~1.2.11; `source-map-js` 1.2.2 in the lock file. `npm audit`: 0 vulnerabilities, with and without `--omit=dev` |
| `android/build.gradle.kts` | `buildscript { dependencies { constraints { classpath(...) } } }` for the plugin classpath, and a `subprojects` rule that sets the same versions on `androidLintTool` (and the OpenTelemetry API on every configuration). Remove each pin when AGP's own version reaches it (re-read on every AGP bump) |

The repository has no `gradle.lockfile` and no `verification-metadata.xml`, so there was nothing else to keep in step. The
`buildEnvironment` and `androidLintTool` outputs now show `jose4j 0.9.5 -> 0.9.6`, `jdom2 2.0.6 -> 2.0.6.1`, `bcprov` and `bcpkix 1.80.2 -> 1.85`, `commons-lang3 3.16.0 -> 3.18.0` and `httpclient 4.5.6 -> 4.5.14`.

## 4. What the owner must do

1. **After the merge to `main`:** run the `Security` workflow by hand (Actions tab; its `gradle-dependency-graph` job runs on
   `main`, on the weekly schedule and by hand) so the Gradle graph is re-submitted, then open **Security > Dependabot**.
   **Expected to close:** #3, #1, #8, #9, #6, #5, #2, #4 (Gradle) and #10, #11, #13, #14 (npm, on the lock file change).
   **May remain:** #7 `opentelemetry-api`, whose dependency path could not be found here (section 2); if it stays open,
   send the path the alert shows (*Manifest*, *Relationship*) and it will be traced. Owner decision needed if it proves to
   be a build tool that cannot be pinned: it is not on the APK or backend path found here, but no acceptance is recorded.
2. **Enable secret scanning** (and push protection) in **Settings > Code security** (the repository is public, so it is free).
   It is the same page as *Dependabot alerts*; [13](../13-release-security-checklist.md) A5 and A6 want both on.
3. Optional: a token that can read Dependabot alerts (a fine-grained token with *Dependabot alerts: read*) lets a later
   session read the alerts instead of relying on a pasted list.

## 5. What was not verified

- Where #7 comes from (not resolvable offline here), and so whether the OpenTelemetry pin closes it.
- That the submitted dependency graph now reports the pinned versions (main only); `buildEnvironment` and the
  `androidLintTool` configuration were checked locally instead.
- The backend's `mvn verify` (needs JDK 25 and the PostGIS container); no backend file changed and the dependency tree
  was checked only.
