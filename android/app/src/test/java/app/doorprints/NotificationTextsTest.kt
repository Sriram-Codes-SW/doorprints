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
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import app.doorprints.export.ExportProblem
import app.doorprints.i18n.AppLocale
import app.doorprints.shared.export.BackupProblem
import app.doorprints.shared.export.ImportMode
import app.doorprints.ui.exportResultSentence
import app.doorprints.ui.importWriteFailedResource
import app.doorprints.ui.importedSentence
import app.doorprints.ui.messageResource
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.export_failed
import app.doorprints.ui.res.import_failed
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The export, import and automatic-backup workers' notifications read the screens' Compose resources since
 * S4b-BL-106 (they had their own Android copies), outside composition and in the language `AppLocale.wrap` sets as the
 * default: the same sentences as before, in the app's language (docs/06 TC-U-59).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and WorkManager.
@Config(sdk = [35], application = Application::class)
class NotificationTextsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val savedDefault = LocaleList.getDefault()

    @After
    fun restore() {
        LocaleList.setDefault(savedDefault)
    }

    private fun appIn(tag: String) = AppLocale.applyDefault(
        context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) },
        ),
    )

    @Test
    fun theImportSentenceNamesOnlyTheNonZeroParts() = runBlocking {
        appIn("en")
        assertEquals(
            "Brought back 1 house. Added 2 houses, 1 visit and 20 photos. Updated 2 houses.",
            importedSentence(houses = 5, visits = 1, photos = 20, updatedHouses = 2, restoredHouses = 1),
        )
        assertEquals("Import finished. Nothing needed to be added or updated.", importedSentence(0, 0, 0))
    }

    @Test
    fun theExportSentenceIsTheResultCardsOwn() = runBlocking {
        appIn("en")
        val document = "content://com.android.providers.downloads.documents/document/msf%3A1000001234"
        assertEquals("Saved to Download: a.html", exportResultSentence(document, "a.html", "Download", partial = false))
        assertEquals("Saved.", exportResultSentence(document, null, null, partial = false))
        assertEquals(
            "Partial backup ready to share: b.zip",
            exportResultSentence("/data/user/0/app.doorprints/cache/exports/b.zip", null, null, partial = true),
        )
    }

    @Test
    fun aFailureIsAWholeSentenceInTheAppsLanguage() = runBlocking {
        appIn("en")
        val english = getString(Res.string.export_failed, getString(ExportProblem.NO_SPACE.messageResource))
        assertEquals("The copy could not be saved: there is not enough free space", english)
        assertEquals(
            "This file cannot be imported: it is not a Doorprints backup",
            getString(Res.string.import_failed, getString(BackupProblem.NOT_A_BACKUP.messageResource)),
        )
        val englishCopy = getString(importWriteFailedResource(ImportMode.COPY))
        appIn("hi")
        assertNotEquals(english, getString(Res.string.export_failed, getString(ExportProblem.NO_SPACE.messageResource)))
        assertNotEquals(englishCopy, getString(importWriteFailedResource(ImportMode.COPY)))
    }
}
