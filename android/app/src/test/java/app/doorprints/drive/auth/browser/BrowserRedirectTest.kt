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
class BrowserRedirectTest {
    private val base = BrowserRedirect.DEFAULT_REDIRECT_URI

    private fun code(r: BrowserRedirect.Request) = (runBlocking { r.await() } as BrowserRedirect.Outcome.Code).code

    @Test
    fun theRightRedirectCompletesThePendingRequest() {
        val r = BrowserRedirect()
        val req = r.begin("st1")
        assertTrue(r.deliver("$base?state=st1&code=abc%2F1"))
        assertEquals("abc/1", code(req))
    }

    @Test
    fun aStateMismatchIsRefusedAndLeavesTheRequestWaiting() {
        val r = BrowserRedirect()
        val req = r.begin("st1")
        assertFalse(r.deliver("$base?state=other&code=evil"))
        assertFalse(req.result.isCompleted)
        assertTrue(r.deliver("$base?state=st1&code=good"))
        assertEquals("good", code(req))
    }

    @Test
    fun aMissingStateIsRefused() {
        val r = BrowserRedirect()
        r.begin("st1")
        assertFalse(r.deliver("$base?code=evil"))
    }

    @Test
    fun withNoPendingRequestAnyRedirectIsStale() {
        assertFalse(BrowserRedirect().deliver("$base?state=st1&code=abc"))
    }

    @Test
    fun aRedirectIsSingleUse() {
        val r = BrowserRedirect()
        r.begin("st1")
        assertTrue(r.deliver("$base?state=st1&code=abc"))
        assertFalse(r.deliver("$base?state=st1&code=abc"))
    }

    @Test
    fun aForeignAddressIsRefused() {
        val r = BrowserRedirect()
        r.begin("st1")
        assertFalse(r.deliver("https://evil.example/oauth2redirect?state=st1&code=x"))
        assertFalse(r.deliver("app.doorprints:/other?state=st1&code=x"))
        assertFalse(r.deliver("app.doorprints://host/oauth2redirect?state=st1&code=x"))
        assertFalse(r.deliver("not a uri at all"))
        assertFalse(r.deliver(null))
    }

    @Test
    fun aRepeatedParameterIsRefused() {
        val r = BrowserRedirect()
        r.begin("st1")
        assertFalse(r.deliver("$base?state=st1&code=a&code=b"))
    }

    @Test
    fun anErrorWithTheRightStateCompletesAsError() {
        val r = BrowserRedirect()
        val req = r.begin("st1")
        assertTrue(r.deliver("$base?state=st1&error=access_denied"))
        assertEquals(BrowserRedirect.Outcome.Error("access_denied"), runBlocking { req.await() })
    }

    @Test
    fun anErrorWithTheWrongStateIsRefused() {
        val r = BrowserRedirect()
        val req = r.begin("st1")
        assertFalse(r.deliver("$base?state=zzz&error=access_denied"))
        assertFalse(req.result.isCompleted)
    }

    @Test
    fun aRedirectWithNeitherCodeNorErrorIsRefused() {
        val r = BrowserRedirect()
        r.begin("st1")
        assertFalse(r.deliver("$base?state=st1"))
    }

    @Test
    fun aSecondBeginCancelsTheFirstAndOnlyTheNewStateWorks() {
        val r = BrowserRedirect()
        val first = r.begin("one")
        val second = r.begin("two")
        assertTrue(first.result.isCompleted)
        assertEquals(BrowserRedirect.Outcome.Cancelled, runBlocking { first.await() })
        assertFalse(r.deliver("$base?state=one&code=a"))
        assertTrue(r.deliver("$base?state=two&code=b"))
        assertEquals("b", code(second))
    }

    @Test
    fun abandonOfAnOldRequestDoesNotKillTheNewOne() {
        val r = BrowserRedirect()
        val first = r.begin("one")
        val second = r.begin("two")
        r.abandon(first)
        assertFalse(second.result.isCompleted)
        assertTrue(r.deliver("$base?state=two&code=b"))
    }

    @Test
    fun cancelPendingEndsTheRequest() {
        val r = BrowserRedirect()
        val req = r.begin("st1")
        r.cancelPending()
        assertEquals(BrowserRedirect.Outcome.Cancelled, runBlocking { req.await() })
        assertFalse(r.deliver("$base?state=st1&code=a"))
    }

    @Test
    fun onNewIntentTakesAViewIntentOnly() {
        val r = BrowserRedirect()
        val req = r.begin("st1")
        assertFalse(r.onNewIntent(null))
        assertFalse(r.onNewIntent(Intent(Intent.ACTION_SEND, Uri.parse("$base?state=st1&code=a"))))
        assertFalse(r.onNewIntent(Intent(Intent.ACTION_VIEW)))
        assertTrue(r.onNewIntent(Intent(Intent.ACTION_VIEW, Uri.parse("$base?state=st1&code=a"))))
        assertEquals("a", code(req))
    }
}
