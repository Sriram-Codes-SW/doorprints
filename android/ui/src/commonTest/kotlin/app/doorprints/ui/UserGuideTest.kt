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

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Help link's address (S4b-BL-60): the guide in the app's language, English for any other. */
class UserGuideTest {
    @Test fun eachLanguageHasItsGuide() {
        assertEquals("https://sriram-codes-sw.github.io/doorprints/", UserGuide.url("en"))
        assertEquals("https://sriram-codes-sw.github.io/doorprints/hi/", UserGuide.url("hi"))
        assertEquals("https://sriram-codes-sw.github.io/doorprints/ta/", UserGuide.url("ta"))
        assertEquals("https://sriram-codes-sw.github.io/doorprints/te/", UserGuide.url("te"))
    }

    @Test fun anotherLanguageGetsTheEnglishGuide() {
        assertEquals(UserGuide.URL, UserGuide.url("fr"))
        assertEquals(UserGuide.URL, UserGuide.url(""))
    }

    @Test fun theAiWorthItSectionIsInTheAppsLanguage() {
        assertEquals("https://sriram-codes-sw.github.io/doorprints/settings-and-privacy.html#is-ai-worth-it", UserGuide.aiWorthItUrl("en"))
        assertEquals("https://sriram-codes-sw.github.io/doorprints/hi/settings-and-privacy.html#is-ai-worth-it", UserGuide.aiWorthItUrl("hi"))
        assertEquals("https://sriram-codes-sw.github.io/doorprints/ta/settings-and-privacy.html#is-ai-worth-it", UserGuide.aiWorthItUrl("ta"))
        assertEquals("https://sriram-codes-sw.github.io/doorprints/te/settings-and-privacy.html#is-ai-worth-it", UserGuide.aiWorthItUrl("te"))
        assertEquals("https://sriram-codes-sw.github.io/doorprints/settings-and-privacy.html#is-ai-worth-it", UserGuide.aiWorthItUrl("fr"))
    }
}
