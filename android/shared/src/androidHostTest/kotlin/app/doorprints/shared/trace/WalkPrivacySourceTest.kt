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

package app.doorprints.shared.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The source-level half of TC-U-151 and TC-U-154 (docs/11 5.27.7a, 5.27.13; threat model T-I30, T-I42; PRV-028, PRV-030,
 * PRV-032): saved walks and trace points are in **no** export, backup, sync or AI path, no log line holds a walk, and
 * the statements about Android's transfer and the iPhone's backup exclusion stay true until the text is re-read.
 * (`WalkPrivacyTest` in `:app` is the behavioural half: marker coordinates searched in what each path builds.)
 */
class WalkPrivacySourceTest {
    private val repo: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas/trace-repeat-vectors.json").exists()) dir = dir.parentFile
        checkNotNull(dir) { "the repository root was not found" }
    }

    private fun android(path: String) = File(repo, "android/$path")
    private val shared = "shared/src/commonMain/kotlin/app/doorprints"
    private val appMain = "app/src/main/java/app/doorprints"

    private fun kotlinFiles(path: String): List<File> {
        val f = android(path)
        assertTrue("$path does not exist: the test would be vacuous", f.exists())
        return if (f.isDirectory) f.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else listOf(f)
    }

    /** Code only: comments are the place the text names these tables. */
    private fun code(file: File): String =
        file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    /** Every export, backup, sync, Drive and AI path (docs/11 5.27.7: `ExportBundle.build`, `LocalRows`, the writers). */
    private val outPaths = listOf(
        "$shared/shared/export", "$shared/drive", "$shared/shared/ai", "$shared/shared/sync", "$shared/crypto",
        "$shared/data/ServerSyncBackend.kt", "$shared/data/ExportMappers.kt", "$shared/data/SyncBackend.kt",
        "$appMain/export", "$appMain/drive/wiring",
    )

    private val forbidden = listOf(
        "TrackDao", "SavedWalkDao", "track_points", "saved_walks", "TrackPointEntity", "SavedWalkEntity", "SavedWalkSummary",
        "WalkStore", "TraceWalk", "WalkCodec", ".track()", ".savedWalks()", "trackPoints", "savedWalk", "walkAskedUpTo",
        "repeatLook", "repeatAlert", "shared.trace",
    )

    @Test
    fun noExportBackupSyncDriveOrAiFileMentionsATraceOrAWalk() {
        var scanned = 0
        for (path in outPaths) for (file in kotlinFiles(path)) {
            scanned++
            val text = code(file)
            for (token in forbidden) assertFalse("${file.relativeTo(repo)} mentions `$token`: a walk is local only (PRV-028)", text.contains(token))
        }
        assertTrue("the scan must read the files ($scanned)", scanned > 40)
    }

    @Test
    fun theDriveSyncRowsReadFourDaosAndNoneIsAWalkTable() {
        val text = code(android("$shared/drive/wiring/RoomSyncRows.kt"))
        val daos = Regex("db\\.(\\w+)\\(\\)").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("houses", "visits", "records", "photos"), daos)
    }

    @Test
    fun theInvalidationTrackerBehindTheExportsListsOnlyTheFourExportTables() {
        val text = android("$shared/data/DatabaseTransactions.kt").readText()
        val tables = Regex("LOCAL_TABLES = arrayOf\\(([^)]*)\\)").find(text)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }
        assertEquals(listOf("houses", "visits", "photos", "records"), tables)
    }

    @Test
    fun noLogLineOrBreadcrumbCarriesAWalkAPointOrACount() {
        val files = kotlinFiles("$shared/shared/trace") + kotlinFiles("$shared/location") + kotlinFiles("$shared/data/WalkStore.kt") +
            kotlinFiles("$appMain/location") + kotlinFiles("ui/src/iosMain/kotlin/app/doorprints/ui/IosHunt.kt")
        val risky = Regex("(?i)walk|point|\\blat\\b|\\blon\\b|track|count|size|latitude|longitude|trace")
        var logLines = 0
        for (file in files) for (line in code(file).lines()) {
            if (line.contains("Log.") || line.contains("breadcrumb(")) {
                logLines++
                assertFalse("${file.name}: `${line.trim()}` logs a walk, a point or a count", risky.containsMatchIn(line) && !line.contains("fun breadcrumb"))
            }
        }
        assertTrue("IosHunt's breadcrumbs were found, so the scan is not vacuous", logLines >= 3)
    }

    @Test
    fun theTracePackageIsPureCommonCodeWithNoPlatformNetworkOrLogging() {
        for (file in kotlinFiles("$shared/shared/trace")) {
            val text = code(file)
            for (token in listOf("import java.", "import android.", "import platform.", "import io.ktor", "Log.", "println(", "breadcrumb(", "rememberSaveable", "savedState", "TrackDao", "SavedWalkDao", "System.")) {
                assertFalse("${file.name} contains `$token` (docs/11 5.27.3, 5.27.13: pure, nothing stored, sent or logged)", text.contains(token))
            }
        }
    }

    @Test
    fun androidsTransferStatementStaysTrueUntilTheTextIsReread() {
        val xml = android("app/src/main/res/xml/data_extraction_rules.xml").readText()
        val cloud = Regex("<cloud-backup>(.*?)</cloud-backup>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
        val transfer = Regex("<device-transfer>(.*?)</device-transfer>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
        fun entries(s: String, tag: String) = Regex("<$tag domain=\"(\\w+)\" path=\"([^\"]*)\"").findAll(s).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(
            "cloud backup excludes everything",
            listOf("root" to ".", "file" to ".", "database" to ".", "sharedpref" to ".", "external" to "."), entries(cloud, "exclude"),
        )
        assertEquals("cloud backup includes nothing", emptyList<Pair<String, String>>(), entries(cloud, "include"))
        assertEquals(
            "device transfer includes the database (walks with it, on purpose) and the photos, nothing else",
            listOf("database" to ".", "file" to "photos/"), entries(transfer, "include"),
        )
        assertTrue("allowBackup is off", android("app/src/main/AndroidManifest.xml").readText().contains("android:allowBackup=\"false\""))
        // If this fails: re-read docs/11 5.27.7, docs/01 PRV-028 and docs/02 T-I30 / RR-31 before changing the expectation.
    }

    @Test
    fun theIphoneExcludesTheDataFolderFromICloudAndComputerBackups() {
        val text = android("shared/src/iosMain/kotlin/app/doorprints/data/IosDataDirectory.kt").readText()
        assertTrue(text.contains("NSURLIsExcludedFromBackupKey"))
        assertTrue(text.contains("setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey"))
    }
}
