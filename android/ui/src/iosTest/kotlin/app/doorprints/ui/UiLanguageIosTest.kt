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

package app.doorprints.ui

import platform.Foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S4b-BL-40: iOS's app language is always one the app ships strings for (en, hi, ta, te), English otherwise. */
class UiLanguageIosTest {
    @Test
    fun aShippedLanguageIsKeptWhateverItsRegionOrScript() {
        assertEquals("hi", shippedLanguageOf("hi-IN"))
        assertEquals("hi", shippedLanguageOf("hi"))
        assertEquals("ta", shippedLanguageOf("ta-LK"))
        assertEquals("te", shippedLanguageOf("te_IN"))
        assertEquals("en", shippedLanguageOf("en-GB"))
        assertEquals("hi", shippedLanguageOf("HI-in"))
        assertEquals("hi", shippedLanguageOf("hi-Latn-IN"))
    }

    @Test
    fun anyOtherLanguageFallsBackToEnglish() {
        listOf("mr-IN", "kn-IN", "bn", "ur-IN", "zh-Hans-CN", "fr-FR", "tam", "", null)
            .forEach { assertEquals("en", shippedLanguageOf(it), "$it") }
    }

    @Test
    fun theDevicesLanguageResolvesToAShippedOne() {
        assertTrue(appLanguage() in listOf("en", "hi", "ta", "te"), appLanguage())
        // And it is the device's first preferred language, as Compose resources pick the strings.
        val first = NSLocale.preferredLanguages.firstOrNull() as String?
        assertEquals(shippedLanguageOf(first), appLanguage(), "$first")
    }
}
