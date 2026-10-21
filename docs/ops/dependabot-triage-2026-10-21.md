# Dependabot alerts, triage of 2026-10-21

> Session record (not a numbered SSDLC document). It answers [13](../13-release-security-checklist.md) A6 for the 13 open
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

The session's token cannot read the alerts API (403), so the alerts were **derived, not read**: the lock files and the
resolved Gradle and Maven graphs were scanned, and each package and version was looked up in the OSV database
(`api.osv.dev`, which mirrors the GitHub advisories) and Maven Central's metadata. The advisory ids below are from OSV;
they match what Dependabot reports for the same package and version, but the exact alert numbers were not seen.

| Ecosystem | How it was read |
|---|---|
| Web | `npm audit` and `npm audit --omit=dev` in `web/` and `tools/live-ui/`; `.github/firebase-tools` has no lock file (nothing to audit) |
| Android | `./gradlew :app|:shared|:ui:dependencies` (every configuration) and `buildEnvironment`, scanned for `bcprov`, `bcpkix`, `bcutil`, `jose4j`, `jdom2` |
| Backend | `mvn dependency:tree` (all scopes): **no** Bouncy Castle, jose4j or jdom2 |

The Android graph is submitted whole by the `gradle-dependency-graph` job of `security.yml` (main only), so the build
tooling's classpaths are what Dependabot sees, not only the APK's.

## 2. The alerts

| Package | Found at | Advisory (severity) | Classpath | Ships? | Action |
|---|---|---|---|---|---|
| `vite` | 8.3.0 | launch-editor advisories GHSA-698v-4r3x-3vcv, GHSA-r825-w8mm-5pxw, GHSA-587h-7wmj-h5pm (High; affects 8.3.0 to 8.3.2) | web `devDependencies`, via `@angular/build` 22.2.3, which pins `vite` 8.3.0 exactly | No: dev server and build tool; not in `dist/` | **Bumped to 8.3.3** with an `overrides` entry in `web/package.json` (the first release outside the range; Angular's own pins are unchanged). The override also moves `rolldown` to 1.2.15 (`~1.2.11` is what vite 8.3.3 needs; one hoisted copy, no nested one) |
| `source-map-js` | 1.2.1 | GHSA-68fv-2mgg-jv7q (High; 1.0.0 to 1.2.1) | web `devDependencies`, via `postcss` and `sass` | No: build tool | **Bumped to 1.2.2** inside its semver range (`npm update source-map-js`, lock file only) |
| `org.bouncycastle:bcprov-jdk18on` | 1.80.2 | GHSA-9pwp-9qqc-pr26 / CVE-2026-8763 (**Critical**, fixed 1.85); GHSA-qp49-qgx5-5m26 / CVE-2026-13506 (**High**, fixed 1.85); GHSA-c3fc-8qff-9hwx (Moderate, fixed 1.84) | Gradle plugin classpath (`buildEnvironment`, from AGP 9.4.1) and the `androidLintTool` configuration of `:app`, `:shared`, `:ui` (AGP's lint) | **No.** Absent from `releaseRuntimeClasspath` and every other `:app`, `:shared`, `:ui` runtime and compile classpath. The unit-test classpaths already resolve 1.85 (Robolectric 4.17 brings it) | **Pinned to 1.85** for the plugin classpath and for `androidLintTool` (`android/build.gradle.kts`) |
| `org.bouncycastle:bcpkix-jdk18on` | 1.80.2 | GHSA-wg6q-6289-32hp / CVE-2026-5588 (Moderate, fixed 1.84) | as bcprov | No | **Pinned to 1.85**, with `bcutil-jdk18on` (no advisory itself, same release train) so the three stay together |
| `org.bitbucket.b_c:jose4j` | 0.9.5 | GHSA-3677-xxcr-wjqv / CVE-2024-29371 (**High**, fixed 0.9.6) | Gradle plugin classpath only (AGP 9.4.1) | **No** | **Pinned to 0.9.6** |
| `org.jdom:jdom2` | 2.0.6 | GHSA-2363-cqg2-863c / CVE-2021-33813 (**High**, XXE, fixed 2.0.6.1) | Gradle plugin classpath only (AGP 9.4.1) | **No** | **Pinned to 2.0.6.1** |

The advisories above are 10 (vite 3, source-map-js 1, bcprov 3, bcpkix 1, jose4j 1, jdom2 1); the audit counts 13, most
likely because one advisory is raised once per manifest (the Gradle graph is per module). The exact match was not
seen. If any alert remains after the merge, it is a package not listed here (section 4, step 2).

**Nothing that ships was affected.** The APK's `releaseRuntimeClasspath` contains none of the four Java packages, and the
backend's runtime jar (checked with all Maven scopes) contains none either. The web production dependencies
(`npm audit --omit=dev`) had no finding before or after. The alerts were about build tooling, which is why the fix is a
pin and not a library change. Nothing in the repository's own code calls these libraries.

## 3. What changed

| File | Change |
|---|---|
| `web/package.json`, `web/package-lock.json` | `overrides`: `vite` 8.3.3 and `rolldown` ~1.2.11; `source-map-js` 1.2.2 in the lock file. `npm audit`: 0 vulnerabilities, with and without `--omit=dev` |
| `android/build.gradle.kts` | `buildscript { dependencies { constraints { classpath(...) } } }` for the plugin classpath, and a `subprojects` rule that sets the same versions on `androidLintTool`. Remove each pin when AGP's own version reaches it (re-read on every AGP bump) |

The repository has no `gradle.lockfile` and no `verification-metadata.xml`, so there was nothing else to keep in step. The
`buildEnvironment` output now shows `jose4j 0.9.5 -> 0.9.6`, `jdom2 2.0.6 -> 2.0.6.1`, `bcprov` and `bcpkix 1.80.2 -> 1.85`.

## 4. What the owner must do

1. **After the merge to `main`:** the `Security` workflow re-submits the Gradle graph (its `gradle-dependency-graph` job runs
   on `main`, on the weekly schedule and by hand). Then open **Security > Dependabot** and confirm the alerts above are
   closed. If a Gradle alert stays open, run the job by hand from the Actions tab and look again; the web alerts close
   when the lock file changes on `main`.
2. If an alert is still open after that, send its package and manifest path (the alerts page lists both); this record
   was written without being able to read them.
3. **Enable secret scanning** (and push protection) in **Settings > Code security** (the repository is public, so it is free).
   It is the same page as *Dependabot alerts*; [13](../13-release-security-checklist.md) A5 and A6 want both on.
4. Optional: a token that can read Dependabot alerts (`security_events` or a fine-grained *Dependabot alerts: read*) lets a
   later session read the alerts themselves instead of deriving them.

## 5. What was not verified

- The alert list itself (403), so the match between the six packages and the 13 alerts rests on the advisory data.
- The backend's `mvn verify` (needs JDK 25 and the PostGIS container, neither here); no backend file changed, and the
  dependency tree was checked only.
- That the dependency graph's submission now reports the pinned versions (it runs on `main` only); `buildEnvironment` and
  the `androidLintTool` configuration were checked locally instead.
