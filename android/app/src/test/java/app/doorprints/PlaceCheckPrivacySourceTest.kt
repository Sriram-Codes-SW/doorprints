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

package app.doorprints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TC-U-154's source tests (docs/11 5.27.13, T-I42): the place check is on demand only (the one caller of `PlaceCheck.check`
 * is reached only from the buttons' handlers), and nothing under the check's files logs, remembers across a rotation, talks
 * to the network, writes a walk or reaches an export or sync class.
 */
class PlaceCheckPrivacySourceTest {
    private val root: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        dir
    }

    private fun kotlinFiles(vararg dirs: String): List<File> =
        dirs.flatMap { d -> root.resolve(d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private val mainSources = kotlinFiles("ui/src/commonMain", "ui/src/androidMain", "ui/src/iosMain", "app/src/main", "shared/src/commonMain")

    private fun callSites(needle: String) = mainSources.filter { needle in it.readText() }.map { it.name }.sorted()

    private fun lines(file: String, needle: String) =
        mainSources.single { it.name == file }.readLines().count { needle in it }

    @Test fun thereIsOneCallerOfPlaceCheckCheckAndItIsInTheController() {
        assertEquals(listOf("PlaceCheckController.kt"), callSites("PlaceCheck.check("))
        assertEquals(1, lines("PlaceCheckController.kt", "PlaceCheck.check("))
    }

    @Test fun theControllersRunnerIsCalledOnlyByItsStartFunction() {
        // runPlaceCheck is declared and used in the controller's file only, and only inside start().
        assertEquals(listOf("PlaceCheckController.kt"), callSites("runPlaceCheck("))
        val text = mainSources.single { it.name == "PlaceCheckController.kt" }.readText()
        val start = text.substringAfter("fun start(").substringBefore("fun denied(")
        assertTrue("start() runs the check", "runPlaceCheck(" in start)
        assertEquals("one declaration and the two calls of start()", 3, Regex("runPlaceCheck\\(").findAll(text).count())
    }

    /** The head of a block: what stands before its `{` on the line, trimmed. */
    private fun headOf(text: String, open: Int): String = text.substring(maxOf(0, open - 200), open).substringAfterLast('\n').trim()

    /** The index of the `{` that encloses [at], skipping the blocks that closed before it; -1 at the top level. */
    private fun enclosingOpen(text: String, at: Int): Int {
        var depth = 0
        var i = at - 1
        while (i >= 0) {
            when (text[i]) {
                '}' -> depth++
                '{' -> if (depth == 0) return i else depth--
            }
            i--
        }
        return -1
    }

    private val effectHead = Regex("""\b(LaunchedEffect|DisposableEffect|produceState|remember|snapshotFlow|derivedStateOf|SideEffect|rememberCoroutineScope)\b""")
    private val onParamHead = Regex("""\bon[A-Z]\w*\s*=\s*$""")
    private val funHead = Regex("""\bfun\s+(\w+)\s*\(""")
    private val blockHead = Regex("""^(\}\s*)?(if|else|else if|for|while|when|try|catch|finally)\b|->\s*$|^else$""")

    /** One effect that may call a button's function: the permission callback sets `grantedFor` only for a button's own tap. */
    private val allowedEffects = setOf("LaunchedEffect(grantedFor)")

    /**
     * Why the call at [at] in [text] is not reached from a button, or null when it is: its enclosing lambda (climbing out
     * of `if`/`else`/`when` blocks) must be an `on*` parameter (`onClick = {`), or a function whose every call is reached
     * from a button in the same way. An effect, `produceState`, `remember` or any other lambda fails.
     */
    internal fun whyNotAButton(text: String, at: Int, seen: Set<String> = emptySet()): String? {
        var open = enclosingOpen(text, at)
        while (open >= 0) {
            val head = headOf(text, open)
            when {
                onParamHead.containsMatchIn(head) -> return null
                funHead.containsMatchIn(head) -> {
                    val name = funHead.find(head)!!.groupValues[1]
                    if (name in seen) return null
                    val calls = Regex("""(?<![\w.])$name\(""").findAll(text).map { it.range.first }
                        .filter { !funHead.containsMatchIn(text.substring(maxOf(0, it - 8), it + name.length + 1)) }.toList()
                    if (calls.isEmpty()) return "function $name is never called from a button"
                    return calls.firstNotNullOfOrNull { c -> whyNotAButton(text, c, seen + name)?.let { "via $name: $it" } }
                }
                effectHead.containsMatchIn(head) -> {
                    val effect = effectHead.find(head)!!.value + head.substringAfter(effectHead.find(head)!!.value).substringBefore(")").let { "$it)" }
                    return if (effect.replace(" ", "") in allowedEffects) null else "inside $effect"
                }
                blockHead.containsMatchIn(head) -> open = enclosingOpen(text, open)
                else -> return "inside the lambda `$head`"
            }
        }
        return "outside any lambda or function"
    }

    @Test fun theControllersStartIsCalledOnlyFromTheButtonsOfTheMapAndTheHousePage() {
        val callers = mainSources.filter { Regex("placeCheck\\.start\\(").containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(listOf("HouseEditScreen.kt", "MapScreen.kt"), callers)
        // Each call's enclosing lambda is an on* parameter (onClick, onHere, onCheck, onConfirm), or a function that only
        // such lambdas call: never an effect, produceState or remember (S4b-FR-33).
        for (name in callers) {
            val text = mainSources.single { it.name == name }.readText()
            for (m in Regex("placeCheck\\.start\\(").findAll(text)) {
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                assertEquals("$name:$line is called from a button", null, whyNotAButton(text, m.range.first))
            }
        }
    }

    @Test fun theCheckerRejectsAnEffectAProduceStateARememberAndAFunctionAnEffectCalls() {
        fun problem(source: String): String? = whyNotAButton(source, source.indexOf("placeCheck.start("))
        assertEquals(null, problem("Button(onClick = { placeCheck.start(HERE) })"))
        assertEquals(null, problem("Row(\n    onConfirm = {\n        if (spot != null) placeCheck.start(SPOT)\n    },\n)"))
        assertEquals(null, problem("fun checkHere() {\n placeCheck.start(HERE)\n}\nBox(onHere = { checkMenuOpen = false; checkHere() })"))
        assertTrue(problem("LaunchedEffect(Unit) { placeCheck.start(HERE) }")!!.contains("LaunchedEffect"))
        assertTrue(problem("LaunchedEffect(key) {\n if (a) {\n placeCheck.start(HERE)\n }\n}")!!.contains("LaunchedEffect"))
        assertTrue(problem("val x by produceState(0) { placeCheck.start(HERE) }")!!.contains("produceState"))
        assertTrue(problem("remember { placeCheck.start(HERE) }")!!.contains("remember"))
        assertTrue(problem("DisposableEffect(a) { placeCheck.start(HERE); onDispose {} }")!!.contains("DisposableEffect"))
        assertTrue(problem("scope.launch { placeCheck.start(HERE) }")!!.contains("launch"))
        assertTrue(problem("fun checkHere() {\n placeCheck.start(HERE)\n}\nLaunchedEffect(Unit) { checkHere() }")!!.contains("checkHere"))
        assertTrue(problem("fun checkHere() {\n placeCheck.start(HERE)\n}")!!.contains("never called"))
        assertTrue(problem("placeCheck.start(HERE)")!!.contains("outside"))
        // The one allowed effect: the permission callback's carry-on after a button's own tap.
        assertEquals(null, problem("fun checkHere() {\n placeCheck.start(HERE)\n}\nLaunchedEffect(grantedFor) { checkHere() }"))
    }

    @Test fun theChecksFilesLogNothingRememberNothingAcrossARotationAndTouchNoNetworkOrStore() {
        val files = listOf(
            "shared/src/commonMain/kotlin/app/doorprints/shared/trace/PlaceCheck.kt",
            "shared/src/commonMain/kotlin/app/doorprints/shared/trace/MatchedStretch.kt",
            "ui/src/commonMain/kotlin/app/doorprints/ui/PlaceCheckController.kt",
            "ui/src/commonMain/kotlin/app/doorprints/ui/PlaceCheckSheet.kt",
            "ui/src/commonMain/kotlin/app/doorprints/ui/PlaceCheckUi.kt",
            "ui/src/commonMain/kotlin/app/doorprints/ui/BestFix.kt",
        )
        val banned = listOf(
            "Log.", "breadcrumb(", "println(", "logLine(", "rememberSaveable", "SavedStateHandle", "savedStateHandle",
            "HttpClient", "ApiClient", "URL(", "fetch(", "TrackDao", "SavedWalkDao", "saveTrackPoint", "saveWalk(", "deleteSavedWalk",
            "settings.save", "ExportBundle", "DriveBackup", "SyncBackend", "AiHouse",
        )
        for (f in files) {
            val text = root.resolve(f).readText()
            for (b in banned) assertTrue("$f mentions $b", b !in text)
        }
        // The Android fix function logs nothing either.
        val fix = root.resolve("app/src/main/java/app/doorprints/ui/CurrentLocation.kt").readText().substringAfter("suspend fun bestLocation")
        assertTrue("Log." !in fix && "println(" !in fix)
    }

    @Test fun noExportOrSyncFileReachesTheCheck() {
        val exportish = mainSources.filter { f ->
            val p = f.path
            "/shared/export/" in p || "/drive/" in p || f.name == "ServerSyncBackend.kt" || "/shared/ai/" in p || "/app/src/main/java/app/doorprints/export/" in p
        }
        for (f in exportish) {
            val t = f.readText()
            assertTrue("${f.name} reaches the place check", "PlaceCheck" !in t && "PlaceFix" !in t)
        }
    }
}
