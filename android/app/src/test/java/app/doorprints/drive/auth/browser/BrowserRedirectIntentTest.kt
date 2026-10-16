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

package app.doorprints.drive.auth.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserRedirectIntentTest {
    private val base = BrowserRedirect.DEFAULT_REDIRECT_URI

    @Test
    fun onNewIntentTakesAViewIntentOnly() {
        val r = BrowserRedirect()
        val req = r.begin("st1")
        assertFalse(r.onNewIntent(null))
        assertFalse(r.onNewIntent(Intent(Intent.ACTION_SEND, Uri.parse("$base?state=st1&code=a"))))
        assertFalse(r.onNewIntent(Intent(Intent.ACTION_VIEW)))
        assertTrue(r.onNewIntent(Intent(Intent.ACTION_VIEW, Uri.parse("$base?state=st1&code=a"))))
        assertEquals(BrowserRedirect.Outcome.Code("a").code, (runBlocking { req.await() } as BrowserRedirect.Outcome.Code).code)
    }
}
