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

package app.doorprints.i18n

import android.app.Application
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.doorprints.export.ExportBuilder
import app.doorprints.ui.Formats
import app.doorprints.ui.appLanguage
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.nav_map
import app.doorprints.ui.uiLanguage
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * [AppLocale.applyDefault] puts the process's default locale on the language Android resolved for the resources, so
 * the Compose strings (which read the default) and the Android strings agree (docs/06 TC-U-60); and the dates and the
 * theme's language rules follow that language too, not the phone's first one (S4b-BL-18, docs/06 TC-U-61).
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: DoorprintsApp would start MapLibre (native code) and WorkManager.
@Config(sdk = [35], application = Application::class)
class AppLocaleTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val savedDefault = LocaleList.getDefault()

    @After
    fun restore() {
        LocaleList.setDefault(savedDefault)
    }

    private fun phoneSetTo(vararg tags: String) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(*tags.map(Locale::forLanguageTag).toTypedArray()))
        },
    )

    @Test
    fun anUnsupportedFirstLanguageFallsThroughToTheNextSupportedOne() {
        val phone = phoneSetTo("mr-IN", "hi-IN")

        AppLocale.applyDefault(phone)

        assertEquals("hi", Locale.getDefault().language)
        assertEquals("नक्शा", uiString(Res.string.nav_map))
    }

    @Test
    fun theExportsDefaultLanguageIsTheResolvedOneNotTheConfigurationsFirst() {
        // S4b-BL-22: Marathi first (not shipped), Hindi second: the screens are Hindi, so the copy is proposed in Hindi.
        assertEquals("hi", ExportBuilder.defaults(phoneSetTo("mr-IN", "hi-IN")).language)
        assertEquals("en", ExportBuilder.defaults(phoneSetTo("fr-FR")).language)
        assertEquals("ta", ExportBuilder.defaults(phoneSetTo("ta-IN", "hi-IN")).language)
    }

    @Test
    fun noSupportedLanguageMeansEnglishOnBothSides() {
        val phone = phoneSetTo("fr-FR")

        AppLocale.applyDefault(phone)

        assertEquals("en", Locale.getDefault().language)
        assertEquals("Map", uiString(Res.string.nav_map))
    }

    @Test
    fun datesFollowTheResolvedLanguageNotThePhonesFirstOne() {
        AppLocale.applyDefault(phoneSetTo("mr-IN", "hi-IN"))

        assertEquals("hi", appLanguage())
        val millis = 1_760_000_000_000
        assertEquals(Formats.date(millis, "hi"), Formats.date(millis))
        assertEquals(Formats.dateTime(millis, "hi"), Formats.dateTime(millis))
        // Before S4b-BL-18 the dates were written for the configuration's first locale, Marathi.
        assertNotEquals(Formats.date(millis, "mr"), Formats.date(millis))
    }

    @Test
    fun theUiLanguageIsTheResolvedOneNotTheConfigurationsFirst() {
        // The activity's configuration starts with Marathi, which the app does not ship.
        RuntimeEnvironment.setQualifiers("+mr")
        AppLocale.applyDefault(phoneSetTo("mr-IN", "hi-IN"))
        var language = ""

        compose.setContent { language = uiLanguage() }
        compose.waitForIdle()

        assertEquals("hi", language)
    }

    /** A Compose string outside composition, as the app reads one in a coroutine. */
    private fun uiString(resource: StringResource) = runBlocking { getString(resource) }
}
