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

package app.doorprints.drive.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The six lock strings exist in all four languages and the three translations are marked under review (the resource files' rule). */
class DriveLockStringsTest {
    private val keys = listOf("drive_lock_heading", "drive_lock_needs", "drive_lock_paused", "drive_lock_open_settings", "drive_lock_notif_title", "drive_lock_key_lost")

    private fun file(folder: String) = File("src/main/res/$folder/strings.xml").also { assertTrue("missing ${it.path}", it.isFile) }

    private fun value(text: String, key: String): String? =
        Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)

    @Test
    fun everyLanguageHasEveryKeyAndOnlyEnglishIsEnglish() {
        val english = file("values").readText()
        for (folder in listOf("values-hi", "values-ta", "values-te")) {
            val text = file(folder).readText()
            for (k in keys) {
                val v = value(text, k)
                assertTrue("$folder lacks $k", !v.isNullOrBlank())
                assertNotEquals("$folder/$k is still English", value(english, k), v)
            }
        }
        for (k in keys) assertTrue("values lacks $k", !value(english, k).isNullOrBlank())
    }

    @Test
    fun theTranslationsAreMarkedUnderReview() {
        for (folder in listOf("values-hi", "values-ta", "values-te")) {
            val text = file(folder).readText()
            val comment = Regex("<!--[^\\n]*S4b-BL-127[^\\n]*-->").find(text)?.value.orEmpty()
            assertTrue("$folder is not marked under review", comment.contains("under review"))
        }
    }

    @Test
    fun theDocumentedEnglishWordsAreExactlySection103s() {
        val english = file("values").readText()
        assertEquals(
            "Google Drive backup needs a screen lock on this phone (a PIN, pattern, password, fingerprint or face). Set one in the phone\\'s settings, then come back.",
            value(english, "drive_lock_needs"),
        )
        assertEquals(
            "Google Drive backup is paused because this phone no longer has a screen lock. Your houses are safe on this phone. Set a screen lock to continue.",
            value(english, "drive_lock_paused"),
        )
    }

    @Test
    fun theKeyStoreSentenceIsItsOwnWordsAndNotTheLockRemovedOnes() {
        val english = file("values").readText()
        assertEquals(
            "Google Drive backup is paused because this phone\\'s key store lost the key. Your houses are safe on this phone. Connect to Google Drive again to continue.",
            value(english, "drive_lock_key_lost"),
        )
        for (folder in listOf("values", "values-hi", "values-ta", "values-te")) {
            val text = file(folder).readText()
            assertNotEquals("$folder: a vendor error must not read as a removed lock", value(text, "drive_lock_paused"), value(text, "drive_lock_key_lost"))
        }
    }
}
