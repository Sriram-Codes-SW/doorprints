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

    @Test fun theControllersStartIsCalledOnlyFromTheButtonsOfTheMapAndTheHousePage() {
        val callers = mainSources.filter { Regex("placeCheck\\.start\\(").containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(listOf("HouseEditScreen.kt", "MapScreen.kt"), callers)
        // Each call sits in a click handler (onClick, onHere, onCheck or the picker's onConfirm), never in an effect.
        for (name in callers) {
            val src = mainSources.single { it.name == name }.readLines()
            src.forEachIndexed { i, line ->
                if ("placeCheck.start(" in line) {
                    val context = src.subList(maxOf(0, i - 6), i + 1).joinToString("\n")
                    assertTrue("$name:${i + 1} is in a button handler", Regex("onClick|onHere|onCheck|onConfirm|checkHere").containsMatchIn(context))
                    assertTrue("$name:${i + 1} is not in an effect", !Regex("LaunchedEffect|DisposableEffect|produceState|snapshotFlow").containsMatchIn(context))
                }
            }
        }
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
