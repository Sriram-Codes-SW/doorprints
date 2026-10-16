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

import android.app.Application
import android.net.Uri
import app.doorprints.ui.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S4b-BL-169 (docs/06 TC-U-180): a backup or update file reaches Doorprints as a `content://` document and nothing
 * else. Two seams: the manifest's intent filters for the file mime types (parsed from the source file, since a unit
 * test has no installed package to ask), and [ImportLink], which `MainActivity.parse` calls for both the view intent's
 * data and the share intent's stream. What a device still has to show (TC-S-12): `adb shell am start -d
 * file:///sdcard/x.zip` opens nothing, and a real "open with" from a file app still arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImportLinkTest {
    private val manifest: File = run {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        dir.resolve("app/src/main/AndroidManifest.xml")
    }

    /** Each intent filter of the manifest as (actions, schemes, mime types). */
    private fun filters(): List<Triple<Set<String>, Set<String>, Set<String>>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val out = mutableListOf<Triple<Set<String>, Set<String>, Set<String>>>()
        val nodes = doc.getElementsByTagName("intent-filter")
        for (i in 0 until nodes.length) {
            val filter = nodes.item(i) as Element
            fun names(tag: String, attr: String): Set<String> {
                val found = filter.getElementsByTagName(tag)
                return (0 until found.length)
                    .mapNotNull { (found.item(it) as Element).getAttributeNS(ns, attr).takeIf(String::isNotEmpty) }
                    .toSet()
            }
            out += Triple(names("action", "name"), names("data", "scheme"), names("data", "mimeType"))
        }
        return out
    }

    @Test
    fun theFileOpenFilterTakesContentAndTheTwoBackupTypesOnly() {
        val view = filters().single { (actions, _, mimes) -> "android.intent.action.VIEW" in actions && "application/zip" in mimes }
        assertEquals(setOf("content"), view.second)
        assertEquals(setOf("application/zip", "application/json"), view.third)
    }

    @Test
    fun theShareFilterTakesTheTwoBackupTypesAndPlainText() {
        val share = filters().filter { "android.intent.action.SEND" in it.first }.flatMap { it.third }.toSet()
        assertEquals(setOf("application/zip", "application/json", "text/plain"), share)
    }

    @Test
    fun noIntentFilterDeclaresTheFileScheme() {
        assertEquals(emptyList<Set<String>>(), filters().map { it.second }.filter { "file" in it })
    }

    /** `parse` is private and needs an Activity, so the two routes into [ImportLink] are pinned in the source (as the repository's other source tests do). */
    @Test
    fun bothIntentRoutesGoThroughImportLinkAndTheActivityNamesNoFileScheme() {
        val source = manifest.resolveSibling("java/app/doorprints/MainActivity.kt").readText()
        assertEquals(1, Regex("ImportLink\\.of\\(intent\\.data\\)").findAll(source).count())
        assertEquals(1, Regex("ImportLink\\.of\\(stream\\)").findAll(source).count())
        assertEquals(emptyList<String>(), Regex("scheme == \"file\"").findAll(source).map { it.value }.toList())
    }

    @Test
    fun aContentDocumentIsTakenAsTheImportFile() {
        val document = "content://com.android.providers.downloads.documents/document/1"
        assertEquals(DeepLink.ImportFile(document), ImportLink.of(Uri.parse(document)))
    }

    @Test
    fun aFilePathALinkOrNothingIsRefused() {
        assertNull(ImportLink.of(Uri.parse("file:///sdcard/Download/x.zip")))
        assertNull(ImportLink.of(Uri.parse("https://example.com/x.zip")))
        assertNull(ImportLink.of(Uri.parse("doorprints://connect")))
        assertNull(ImportLink.of(Uri.parse("/sdcard/x.zip")))
        assertNull(ImportLink.of(null))
    }
}
