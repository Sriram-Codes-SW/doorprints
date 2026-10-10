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

package app.doorprints.ui.drive

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The iPhone's Drive strings (S4b-BL-117, S4b-BL-142; docs/12): the passcode notices and the QR scanner's words exist in all
 * four languages, hi, ta and te are translated (not the English copied) and marked *under review*, and the scanner's words
 * are the ones `QrScannerLabels` defaults to.
 */
class IosDriveStringsTest {
    private val languages = mapOf("en" to "values", "hi" to "values-hi", "ta" to "values-ta", "te" to "values-te")

    private val keys = listOf(
        "lock_notice_ios_heading", "lock_notice_ios_needs", "lock_notice_ios_paused", "lock_notice_ios_key_lost", "lock_notice_ios_notif_title",
        "qr_scan_hint", "qr_scan_not_doorprints", "qr_scan_too_long", "qr_scan_viewfinder",
    )

    private fun root(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    private fun file(lang: String) = root().resolve("ui/src/commonMain/composeResources/${languages.getValue(lang)}/strings.xml")

    private fun resources(lang: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(lang)).documentElement.childNodes
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.takeIf { it.tagName == "string" }?.let { out[it.getAttribute("name")] = it.textContent }
        return out
    }

    @Test
    fun everyKeyIsInEveryLanguageAndNotBlank() {
        for (lang in languages.keys) {
            val have = resources(lang)
            for (key in keys) assertTrue(have[key].orEmpty().isNotBlank(), "$key in $lang")
        }
    }

    @Test
    fun theTranslationsAreTranslatedAndMarkedUnderReview() {
        val en = resources("en")
        for (lang in listOf("hi", "ta", "te")) {
            val have = resources(lang)
            for (key in keys) assertNotEquals(en.getValue(key), have.getValue(key), "$key in $lang is the English copied")
            val text = file(lang).readText()
            assertTrue(Regex("""<!-- S4b-BL-117[^>]*under review""").containsMatchIn(text), "$lang is marked under review")
        }
    }

    @Test
    fun theNoticesNameThePasscodeNotAScreenLockAndKeepTheirAmpersand() {
        val en = resources("en")
        assertTrue("passcode" in en.getValue("lock_notice_ios_needs"))
        assertTrue("Face ID & Passcode" in en.getValue("lock_notice_ios_needs"), "where to set it, since there is no link")
        assertTrue("screen lock" !in en.getValue("lock_notice_ios_needs"))
        assertTrue("passcode" in en.getValue("lock_notice_ios_paused"))
        assertTrue("safe on this iPhone" in en.getValue("lock_notice_ios_paused"), "the houses are safe")
        assertTrue("safe on this iPhone" in en.getValue("lock_notice_ios_key_lost"))
    }

    @Test
    fun theEnglishScannerWordsAreTheOnesTheScannerDefaultsTo() {
        val en = resources("en")
        val source = root().resolve("ui/src/iosMain/kotlin/app/doorprints/ui/IosQrScanner.kt").readText()
        assertEquals(en.getValue("qr_scan_hint"), Regex("""val hint: String = "([^"]*)"""").find(source)!!.groupValues[1])
        assertEquals(en.getValue("qr_scan_not_doorprints"), Regex("""val notDoorprints: String = "([^"]*)"""").find(source)!!.groupValues[1])
        assertEquals(en.getValue("qr_scan_too_long"), Regex("""val tooLong: String = "([^"]*)"""").find(source)!!.groupValues[1])
        assertEquals(en.getValue("qr_scan_viewfinder"), Regex("""val viewfinder: String = "([^"]*)"""").find(source)!!.groupValues[1])
    }

    @Test
    fun noDriveResourceNameIsTakenByTheseKeysSoTheDriveTablesTestStaysExact() {
        for (key in keys) assertTrue(!key.startsWith("drive_"), key)
    }
}
