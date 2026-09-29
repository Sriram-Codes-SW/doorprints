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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.doorprints.ui.LegalNotice
import app.doorprints.ui.LegalNoticeBlock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → About shows the AGPL's legal notices and offers the source (the FSF's "How to Use GNU Licenses";
 * docs/10 S4b-BL-65, docs/06 TC-U-82): the copyright in English, "no warranty", and links that open the source and
 * the licence, or show the address when no app can open it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegalNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun showsTheCopyrightAndTheLicenceAndOpensTheSource() {
        val opened = mutableListOf<String>()
        compose.setContent { Column { LegalNoticeBlock(openUrl = { opened += it; true }) } }

        compose.onNodeWithText(LegalNotice.COPYRIGHT).assertIsDisplayed()
        compose.onNodeWithText("NO WARRANTY", substring = true).assertIsDisplayed()
        compose.onNodeWithText("GNU Affero General Public License, version 3", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Source code").performClick()
        compose.onNodeWithText("Licence").performClick()

        assertEquals(listOf(LegalNotice.SOURCE_URL, LegalNotice.LICENCE_URL), opened)
    }

    @Test
    fun showsTheAddressWhenNoAppCanOpenIt() {
        compose.setContent { Column { LegalNoticeBlock(openUrl = { false }) } }

        compose.onNodeWithText("Source code").performClick()

        compose.onNodeWithText(LegalNotice.SOURCE_URL, substring = true).assertIsDisplayed()
    }
}
