/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.drive.ios

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The source-level privacy rules of the iPhone's Drive (S4b-BL-117, docs/15 §5.5, §9.10, [02](../../docs/02-threat-model.md)
 * RR-27): no token, key, code or secret reaches a log or an exception message; no Google client id, key or token is in the
 * repository (Info.plist and the xcconfig carry only the build setting's name, empty); Info.plist registers the redirect
 * scheme from a variable. Run on the host (reads the files); the behaviour is in the common and iOS tests.
 */
class IosDrivePrivacySourceTest {
    private val repo: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas/trace-repeat-vectors.json").exists()) dir = dir.parentFile
        checkNotNull(dir) { "the repository root was not found" }
    }

    private fun file(path: String) = File(repo, path)

    private val shared = "android/shared/src"
    private val ui = "android/ui/src/iosMain/kotlin/app/doorprints/ui"

    /** Every iPhone Drive source and the common sign-in and device code it runs. */
    private fun driveSources(): List<File> {
        val roots = listOf(
            "$shared/iosMain/kotlin/app/doorprints/drive", "$shared/commonMain/kotlin/app/doorprints/drive/auth",
            "$shared/commonMain/kotlin/app/doorprints/drive/device", "$shared/commonMain/kotlin/app/doorprints/drive/store",
            "$shared/commonMain/kotlin/app/doorprints/drive/keychain",
        )
        val files = roots.flatMap { file(it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() } +
            listOf("IosDriveServices.kt", "IosDriveSection.kt", "IosClipboardSeam.kt", "IosForeground.kt").map { file("$ui/$it") }
        files.forEach { assertTrue(it.exists(), "${it.name} is missing: the test would be vacuous") }
        return files
    }

    /** Code only: comments name the very things this test keeps out of messages. */
    private fun code(f: File): String =
        f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun noLogLineAndNoPrintInAnyDriveSource() {
        val forbidden = listOf("println(", "print(", "NSLog", "logLine(", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "os_log", "debugPrint")
        var scanned = 0
        for (f in driveSources()) {
            scanned++
            val text = code(f)
            for (token in forbidden) assertFalse(text.contains(token), "${f.name} calls `$token`: Drive code logs nothing")
        }
        assertTrue(scanned > 25, "the scan must read the files ($scanned)")
    }

    @Test
    fun noExceptionMessageOrStringTemplateCarriesASecret() {
        val secret = Regex("""\$\{?(token|refreshToken|accessToken|secret|code|verifier|psk|key|privateKey|scalar|data|bytes|recoveryKey)\b""")
        for (f in driveSources()) {
            for (line in code(f).lines()) {
                if (!(line.contains("throw ") || line.contains("Exception(") || line.contains("toString"))) continue
                assertFalse(secret.containsMatchIn(line), "${f.name}: a secret in a message: ${line.trim()}")
            }
        }
    }

    @Test
    fun theSecretCarryingTypesHaveAToStringThatShowsNone() {
        val code = code(file("$shared/commonMain/kotlin/app/doorprints/drive/auth/browser/TokenEndpoint.kt"))
        assertTrue(code.contains("""override fun toString() = "Tokens(scopes=${'$'}scopes)""""), "TokenResult.Tokens")
        assertTrue(code(file("$shared/commonMain/kotlin/app/doorprints/drive/device/SecretOperationProver.kt")).contains("""override fun toString() = "Opened""""))
        assertTrue(code(file("$shared/commonMain/kotlin/app/doorprints/drive/keychain/KeychainItems.kt")).contains("""KeychainResult(status=${'$'}status)"""))
    }

    @Test
    fun noGoogleClientIdKeyOrTokenIsInTheRepositoryOnlyTheBuildSettingsName() {
        val clientId = Regex("""\b\d{6,}-[a-z0-9]{10,}\.apps\.googleusercontent\.com""")
        val token = Regex("""\b(ya29\.[A-Za-z0-9_-]{20,}|1//[A-Za-z0-9_-]{30,}|AIza[A-Za-z0-9_-]{30,})""")
        var scanned = 0
        for (root in listOf("ios", "android/shared/src", "android/ui/src", "android/app/src", "docs", "web/public")) {
            file(root).walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "swift", "plist", "xcconfig", "yml", "md", "xml", "js", "json", "strings") }
                .forEach { f ->
                    scanned++
                    val text = f.readText()
                    assertFalse(clientId.containsMatchIn(text), "${f.relativeTo(repo)} holds a Google client id")
                    assertFalse(token.containsMatchIn(text), "${f.relativeTo(repo)} holds a Google key or token")
                }
        }
        assertTrue(scanned > 200, "the scan must read the files ($scanned)")
    }

    @Test
    fun infoPlistTakesTheClientFromABuildSettingAndRegistersTheRedirectSchemeFromAnother() {
        val plist = file("ios/Doorprints/Info.plist").readText()
        assertTrue(Regex("""<key>GoogleIOSClientId</key>\s*<string>\$\(GOOGLE_IOS_CLIENT_ID\)</string>""").containsMatchIn(plist), "GoogleIOSClientId")
        assertTrue(plist.contains("<string>\$(GOOGLE_IOS_URL_SCHEME)</string>"), "the redirect scheme comes from GOOGLE_IOS_URL_SCHEME")
        assertTrue(plist.contains("<string>doorprints</string>"), "the connect link scheme stays")
    }

    @Test
    fun theCommittedBuildSettingsAreEmptyAndTheLocalOverridesAreIgnoredByGit() {
        val config = file("ios/Config/Drive.xcconfig").readText()
        val lines = config.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals("GOOGLE_IOS_CLIENT_ID =", lines.first { it.startsWith("GOOGLE_IOS_CLIENT_ID") }.replace(Regex("\\s+"), " "))
        assertTrue(lines.any { it == "#include? \"Drive.local.xcconfig\"" }, "the owner's values come from a local file")
        val ignore = file("ios/.gitignore").readText()
        assertTrue(ignore.lines().any { it.trim() == "Config/Drive.local.xcconfig" }, "Drive.local.xcconfig is git-ignored")
    }
}
