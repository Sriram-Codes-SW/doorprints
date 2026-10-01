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

package app.doorprints.legal

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.doorprints.ui.HelpLink
import app.doorprints.ui.UserGuide
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → About's *Help* (docs/10 S4b-BL-60): a link to the user guide in the app's language, whose accessible name
 * starts with its label and says it opens the browser, and the address shown when no app can open it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HelpLinkTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun opensTheGuideInTheAppsLanguage() {
        val opened = mutableListOf<String>()
        compose.setContent { Column { HelpLink(openUrl = { opened += it; true }, language = "ta") } }

        compose.onNodeWithText("The user guide", substring = true).assertIsDisplayed()
        compose.onNode(hasContentDescription("Help: the user guide, opens in the browser") and hasClickAction())
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf("https://sriram-codes-sw.github.io/doorprints/ta/"), opened)
    }

    @Test
    fun showsTheAddressWhenNoAppCanOpenIt() {
        compose.setContent { Column { HelpLink(openUrl = { false }, language = "en") } }

        compose.onNodeWithText("Help").performClick()

        compose.onNodeWithText(UserGuide.URL, substring = true).assertIsDisplayed()
    }
}
