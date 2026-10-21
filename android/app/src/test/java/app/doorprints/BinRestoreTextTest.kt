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

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.BinRestore
import app.doorprints.drive.photo.PhotoRestoreReason
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.i18n.AppLocale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.os.LocaleList
import java.util.TimeZone

/**
 * The words after *Restore* in *Recently deleted* (S4b-BL-293): nothing special when no photo is in question, all of them
 * back from Google Drive, some back and why the others are not, none back and why, each reason once in the language's own
 * list pattern; in all four languages (hi, ta, te under review), with every placeholder filled.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class BinRestoreTextTest {
    private val locales: LocaleList = LocaleList.getDefault()
    private val zone: TimeZone = TimeZone.getDefault()

    @After fun restore() {
        LocaleList.setDefault(locales)
        TimeZone.setDefault(zone)
    }

    private fun say(lang: String, r: BinRestore, name: String = "Blue gate"): String {
        RuntimeEnvironment.setQualifiers("+$lang")
        AppLocale.applyDefault(ApplicationProvider.getApplicationContext())
        return runBlocking { app.doorprints.ui.binRestoreMessage(name, r) }
    }

    @Test fun inEnglish() {
        assertEquals("Restored Blue gate", say("en", BinRestore.PLAIN))
        assertEquals("Restored Blue gate", say("en", BinRestore(photosTotal = 3, photosBack = 3)))
        assertEquals("Restored Blue gate. Its photos came back from your Google Drive (photos: 3).", say("en", BinRestore(3, 3, 3)))
        assertEquals(
            "Restored Blue gate. Photos that came back: 2 of 3. The others could not be restored: no copy is left in your Google Drive.",
            say("en", BinRestore(3, 2, 2, listOf(PhotoRestoreReason.MISSING))),
        )
        assertEquals(
            "Restored Blue gate. Its photos could not be restored: Google Drive is not connected on this device.",
            say("en", BinRestore(3, 0, 0, listOf(PhotoRestoreReason.NOT_CONNECTED))),
        )
        val three = say("en", BinRestore(3, 0, 0, listOf(PhotoRestoreReason.OFFLINE, PhotoRestoreReason.PAST_WINDOW, PhotoRestoreReason.UNREADABLE)))
        assertEquals(
            "Restored Blue gate. Its photos could not be restored: there was no connection to Google Drive, they were deleted more than 30 days ago and a copy in your Google Drive could not be opened or checked.",
            three,
        )
    }

    @Test fun inEveryReasonAndEveryLanguageNothingIsLeftUnfilled() {
        for (lang in listOf("en", "hi", "ta", "te")) {
            for (reason in PhotoRestoreReason.entries) {
                val none = say(lang, BinRestore(2, 0, 0, listOf(reason)))
                val some = say(lang, BinRestore(2, 1, 1, listOf(reason)))
                for (text in listOf(none, some)) {
                    assertTrue("$lang $reason: $text", text.contains("Blue gate"))
                    assertFalse("$lang $reason: $text", text.contains("%"))
                }
                assertTrue("$lang some: $some", some.contains("1") && some.contains("2"))
            }
            val all = say(lang, BinRestore(4, 4, 4))
            assertTrue("$lang: $all", all.contains("Blue gate") && all.contains("4") && !all.contains("%"))
        }
    }

    @Test fun hindiTamilAndTeluguSayTheirOwnWords() {
        assertTrue(say("hi", BinRestore(2, 1, 1, listOf(PhotoRestoreReason.OFFLINE))).contains("Google Drive से कनेक्शन नहीं था"))
        assertTrue(say("ta", BinRestore(2, 1, 1, listOf(PhotoRestoreReason.PAST_WINDOW))).contains("30 நாட்களுக்கு முன்"))
        assertTrue(say("te", BinRestore(2, 0, 0, listOf(PhotoRestoreReason.UNREADABLE))).contains("Google Driveలోని కాపీని తెరవలేకపోయాము"))
    }
}
